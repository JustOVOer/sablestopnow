package com.ovo.sablestopnow.server;

import com.mojang.logging.LogUtils;
import com.ovo.sablestopnow.mate.Mate;
import com.ovo.sablestopnow.mate.MateFrames;
import com.ovo.sablestopnow.mate.MateRef;
import com.ovo.sablestopnow.mate.MateType;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintHandle;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 配合的服务端权威实现：创建 / 删除 / 求解 / 关节生命周期。
 *
 * <h2>关节为什么在物理子步里创建</h2>
 * 创建配合时要先把 B 结构 {@code teleport} 到位，而 Sable 的位姿要等物理系统读一次才落到 rapier 上。
 * 如果在同一帧内立刻 {@code addConstraint}，关节坐标系会按<b>吸附前</b>的位姿算出来，一建出来就带巨大误差。
 * 因此 {@link #create} 只做「校验 + 吸附 + 落盘」，真正的关节由 {@link #physicsTick} 在下一步补齐。
 *
 * <h2>重建不吸附</h2>
 * 关节失效（结构被移除后重建、快照回退等）时按<b>当前</b>位姿重算坐标系，而不再吸附一次。
 * 因为「锁定轴归零 / 距离钉在 v」都是相对<b>参考点</b>表达的，定义上就不需要重新吸附；
 * 而重新吸附会在结构被外力推歪后突然把它拽回去，反而更糟。
 */
public final class MateRegistry {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 吸附的最大平移距离（方块）。超过就拒绝，避免把远处的重物猛地拽过来。 */
    public static final double MAX_SNAP_DISTANCE = 64.0;

    /** 锚点夹进 plot 内部时留的边距：{@code validateAnchors} 要求锚点必须落在 plot 内。 */
    private static final double PLOT_EPSILON = 1.0e-3;

    /** 配合 id -> 运行期关节。 */
    private static final Map<UUID, PhysicsConstraintHandle> JOINTS = new HashMap<>();

    /** 已经重建失败过的配合 id -> 连续失败次数（避免每步刷屏，也避免无限重试拖慢物理）。 */
    private static final Map<UUID, Integer> FAILURES = new HashMap<>();
    /**
     * 刚建好、还没等到关节落地的配合。
     *
     * <p>关节是在<b>之后</b>的物理 tick 里建的（{@code create} 只写数据 + 吸附位姿）。这中间隔着
     * 至少一步：两侧结构会在这段时间里继续被重力、推力或玩家拖拽加速，等关节真的建出来时它们
     * 已经带着一个不该有的速度差，约束要在一个步长内消掉它 —— 就是「成配瞬间被甩飞」。
     * 所以记下这批配合，等关节第一次真正建成时再清一次速度。
     */
    private static final Set<UUID> PENDING_SETTLE = new HashSet<>();
    private static final int MAX_REBUILD_ATTEMPTS = 200;
    /** 已经为「彻底放弃」打过一次 WARN 的配合，避免重复刷日志。 */
    private static final Set<UUID> GAVE_UP = new HashSet<>();

    private MateRegistry() {
    }

    // ============ 结果类型 ============

    public enum Status {
        OK,
        /** 两端指向同一个物理结构。 */
        SAME_BODY,
        /** 同样的参考对 + 类型 + 对齐方向已经存在。 */
        DUPLICATE,
        /** 方向类配合但有一端没有方向（比如两个顶点）。 */
        NEEDS_DIRECTION,
        /** 参考或结构不合法 / 结构已消失。 */
        NO_BODY,
        /** 结构属于别人。 */
        NOT_OWNER,
        /** 会与已有配合一起把这对结构过约束（某个方向超过 3 个自由度）。 */
        OVER_CONSTRAINED,
        /** 需要移动的距离超过 {@link #MAX_SNAP_DISTANCE}。 */
        SNAP_TOO_FAR,
        /** 几何退化（两点重合、方向为零等）。 */
        DEGENERATE,
        ERROR
    }

    /** @param detail 本地化键或补充说明，供客户端提示 */
    public record Result(Status status, @Nullable Mate mate, @Nullable String detail) {
        public boolean ok() {
            return this.status == Status.OK;
        }

        public static Result fail(final Status status, final String detail) {
            return new Result(status, null, detail);
        }
    }

    // ============ 查询 ============

    /** 某个维度里的全部配合。 */
    public static List<Mate> of(final ServerLevel level) {
        return MateData.get(level).snapshot();
    }

    /** 某个物理结构牵扯到的配合。 */
    public static List<Mate> ofBody(final ServerLevel level, final UUID body) {
        final List<Mate> out = new ArrayList<>();
        for (final Mate mate : MateData.get(level).all().values()) {
            if (mate.touches(body)) {
                out.add(mate);
            }
        }
        return out;
    }

    @Nullable
    public static Mate byId(final ServerLevel level, final UUID mateId) {
        return MateData.get(level).all().get(mateId);
    }

    /** 结构移除时清掉相关配合（{@link #physicsTick} 也会兜底清理）。 */
    public static int dropMatesOf(final ServerLevel level, final UUID body) {
        final int removed = MateData.get(level).removeAllMentioning(body);
        if (removed > 0) {
            LOGGER.debug("[SableStopNow] dropped {} mate(s) involving {}", removed, body);
        }
        return removed;
    }

    // ============ 创建 / 删除 / 修改 ============

    /**
     * 建立一条配合：校验 → 冲突检测 → 吸附 B → 落盘。
     * 真正的关节在下一个物理子步由 {@link #physicsTick} 建好。
     *
     * <p>这里只收 {@code UUID + 名字} 而不是 {@code ServerPlayer}：注册表不该绑定在 MC 的玩家对象上，
     * 而且无玩家的自检（{@code MateSelfTest}）要用同一条路径建配合。
     */
    public static Result create(final ServerLevel level, final UUID playerId, final String playerName,
                                final MateType type,
                                final MateRef refA, final MateRef refB,
                                final double value, final boolean flip) {
        if (!refA.isWellFormed() || !refB.isWellFormed()) {
            return Result.fail(Status.NO_BODY, "mate.error.bad_ref");
        }
        if (refA.sameBody(refB)) {
            return Result.fail(Status.SAME_BODY, "mate.error.same_body");
        }
        if (!refA.supports(type) || !refB.supports(type)) {
            return Result.fail(Status.NEEDS_DIRECTION, "mate.error.needs_direction");
        }

        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return Result.fail(Status.NO_BODY, "mate.error.no_body");
        }
        final ServerSubLevel subA = (ServerSubLevel) container.getSubLevel(refA.body());
        final ServerSubLevel subB = (ServerSubLevel) container.getSubLevel(refB.body());
        if (subA == null || subA.isRemoved() || subB == null || subB.isRemoved()) {
            return Result.fail(Status.NO_BODY, "mate.error.no_body");
        }

        // 所有权：别人拥有的结构不能拿来配合（与多选/Aeronautics 锁定的规则保持一致）
        final StaffOwnershipData ownership = StaffOwnershipData.get(level);
        if (ownership.isOwnedByOther(refA.body(), playerId) || ownership.isOwnedByOther(refB.body(), playerId)) {
            return Result.fail(Status.NOT_OWNER, "mate.error.not_owner");
        }

        final MateData data = MateData.get(level);
        for (final Mate existing : data.all().values()) {
            if (existing.sameAs(type, refA, refB, flip)) {
                return Result.fail(Status.DUPLICATE, "mate.error.duplicate");
            }
        }
        if (overConstrained(data, type, refA, refB, value)) {
            return Result.fail(Status.OVER_CONSTRAINED, "mate.error.over_constrained");
        }

        // 求解（这一步同时给出吸附位姿）
        final MateFrames.Plan plan;
        try {
            plan = MateFrames.solve(subA, subB, type, refA, refB, value, flip);
        } catch (final MateFrames.Unsolvable e) {
            return Result.fail(Status.DEGENERATE, e.getMessage());
        } catch (final Exception e) {
            LOGGER.error("[SableStopNow] failed to solve mate {}", type, e);
            return Result.fail(Status.ERROR, "mate.error.internal");
        }

        if (plan.moveB()) {
            final double distance = plan.newPositionB().distance(subB.logicalPose().position());
            if (distance > MAX_SNAP_DISTANCE) {
                return Result.fail(Status.SNAP_TOO_FAR, "mate.error.snap_too_far");
            }
            try {
                final RigidBodyHandle handle = RigidBodyHandle.of(subB);
                if (handle == null) {
                    return Result.fail(Status.ERROR, "mate.error.no_handle");
                }
                handle.teleport(plan.newPositionB(), plan.newOrientationB());
            } catch (final Exception e) {
                LOGGER.error("[SableStopNow] failed to snap mate", e);
                return Result.fail(Status.ERROR, "mate.error.internal");
            }
        }

        // 成配瞬间把两侧的速度全部清零。
        //
        // 原来只清 B，而且只在真的发生了吸附时清。于是「A 正在被拖、B 静止」或「A、B 都在动」
        // 这类创建时刻，关节一建好就把两侧的动量硬拴在一起：约束会在一个步长内去消掉这个速度差，
        // 表现就是玩家看到的「其中一个速度过大被甩出去」。点—面这种只锁一个法向自由度的配合
        // 尤其明显 —— 它在面内还能自由滑动，任何残余速度都会立刻变成面内的甩动。
        //
        // 两边都清、且与是否发生吸附无关：配合的语义本就是「在此刻把两者固定住」，从静止开始最稳。
        try {
            final var pipeline = container.physicsSystem().getPipeline();
            pipeline.resetVelocity(subA);
            pipeline.resetVelocity(subB);
        } catch (final Exception e) {
            LOGGER.warn("[SableStopNow] failed to reset velocity after mate", e);
        }

        final Mate mate = new Mate(
                UUID.randomUUID(), type, refA, refB, value, flip,
                // 显示名保持为 null：GUI 按「该结构下第几条配合」本地化渲染成「配合N」，
                // 这样换语言不用改存档，重命名功能也留给后续
                null,
                playerId, playerName);
        data.put(mate);
        PENDING_SETTLE.add(mate.id());
        return new Result(Status.OK, mate, null);
    }

    /**
     * 自动选取对齐方向后建立配合。
     *
     * <p>「选满两个自动成配」与「在界面里换配合类型」都走这条路：这两种情况下玩家都无法事先声明
     * 要对齐还是反向，交给 {@link MateFrames#suggestFlip} 挑转动量最小的那个，吸附就只剩平移。
     * 之后可以在配合界面里用对齐按钮改。
     */
    public static Result createAuto(final ServerLevel level, final ServerPlayer player, final MateType type,
                                    final MateRef refA, final MateRef refB, final double value) {
        boolean flip = false;
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) {
            final ServerSubLevel subA = (ServerSubLevel) container.getSubLevel(refA.body());
            final ServerSubLevel subB = (ServerSubLevel) container.getSubLevel(refB.body());
            if (subA != null && subB != null) {
                flip = MateFrames.suggestFlip(subA, subB, type, refA, refB);
            }
        }
        return create(level, player, type, refA, refB, value, flip);
    }

    /** 便利重载：从玩家对象取 UUID 与显示名。 */
    public static Result create(final ServerLevel level, final ServerPlayer player, final MateType type,
                                final MateRef refA, final MateRef refB,
                                final double value, final boolean flip) {
        return create(level, player.getUUID(), player.getGameProfile().getName(),
                type, refA, refB, value, flip);
    }

    /** 删除一条配合。 */
    public static boolean remove(final ServerLevel level, final UUID mateId) {
        final MateData data = MateData.get(level);
        if (data.all().get(mateId) == null) {
            return false;
        }
        final PhysicsConstraintHandle handle = JOINTS.remove(mateId);
        safeRemove(handle);
        FAILURES.remove(mateId);
        data.remove(mateId);
        return true;
    }

    /** 修改距离/角度，并重建关节（锁定值变了，旧关节必须丢）。 */
    public static boolean updateValue(final ServerLevel level, final UUID mateId, final double value) {
        final MateData data = MateData.get(level);
        final Mate old = data.all().get(mateId);
        if (old == null || !old.type().needsValue()) {
            return false;
        }
        final double clamped = Math.clamp(value, old.type().minValue(), old.type().maxValue());
        data.put(old.withValue(clamped));
        invalidate(mateId);
        return true;
    }

    /**
     * 切换配合对齐（同向/反向）。
     *
     * <p>用 {@link Mate#supportsAlignment()} 而不是 {@code type().supportsAlignment()}：后者只看类型，
     * 会给「顶点—顶点重合」这种没有朝向可言的配合放行。
     */
    public static boolean updateFlip(final ServerLevel level, final UUID mateId, final boolean flip) {
        final MateData data = MateData.get(level);
        final Mate old = data.all().get(mateId);
        if (old == null || !old.supportsAlignment()) {
            return false;
        }
        data.put(old.withFlip(flip));
        invalidate(mateId);
        return true;
    }

    /** 重命名。 */
    public static boolean rename(final ServerLevel level, final UUID mateId, @Nullable final String name) {
        final MateData data = MateData.get(level);
        final Mate old = data.all().get(mateId);
        if (old == null) {
            return false;
        }
        data.put(old.withName(name));
        return true;
    }

    /** 丢掉某个配合的关节，让它下一步重建。 */
    public static void invalidate(final UUID mateId) {
        safeRemove(JOINTS.remove(mateId));
        FAILURES.remove(mateId);
    }

    /** 维度卸载 / 服务端停止：丢掉所有运行期关节（配合本身留在存档里）。 */
    public static void clearAll() {
        JOINTS.values().forEach(MateRegistry::safeRemove);
        JOINTS.clear();
        FAILURES.clear();
        GAVE_UP.clear();
    }

    // ============ 物理子步 ============

    /**
     * 每个物理子步维护关节。只在「正在步进的那个维度」上做事（与 {@code StaffEnhanceServer} 一致）。
     */
    public static void physicsTick(final SubLevelPhysicsSystem physicsSystem, final double timeStep) {
        final ServerLevel level = physicsSystem.getLevel();
        if (level == null) {
            return;
        }
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || container.physicsSystem() != physicsSystem) {
            return;
        }
        final MateData data = MateData.get(level);
        if (data.all().isEmpty()) {
            if (!JOINTS.isEmpty()) {
                JOINTS.values().forEach(MateRegistry::safeRemove);
                JOINTS.clear();
                FAILURES.clear();
            }
            return;
        }

        // 1) 清掉已消失的配合 / 已消失的结构
        final Set<UUID> live = new HashSet<>();
        for (final Mate mate : data.all().values()) {
            final ServerSubLevel subA = (ServerSubLevel) container.getSubLevel(mate.a().body());
            final ServerSubLevel subB = (ServerSubLevel) container.getSubLevel(mate.b().body());
            if (subA == null || subA.isRemoved() || subB == null || subB.isRemoved()) {
                continue;
            }
            live.add(mate.id());
        }
        for (final UUID id : new ArrayList<>(JOINTS.keySet())) {
            if (!live.contains(id)) {
                safeRemove(JOINTS.remove(id));
                FAILURES.remove(id);
                GAVE_UP.remove(id);
            }
        }

        // 2) 补齐 / 重建关节
        for (final Mate mate : data.all().values()) {
            if (!live.contains(mate.id())) {
                continue;
            }
            final PhysicsConstraintHandle existing = JOINTS.get(mate.id());
            if (existing != null && existing.isValid()) {
                continue;
            }
            if (existing != null) {
                safeRemove(existing);
                JOINTS.remove(mate.id());
            }
            final int failures = FAILURES.getOrDefault(mate.id(), 0);
            if (failures >= MAX_REBUILD_ATTEMPTS) {
                continue;
            }
            final Throwable cause = buildJoint(container, mate);
            if (cause == null) {
                FAILURES.remove(mate.id());
                GAVE_UP.remove(mate.id());
                continue;
            }
            final int attempts = failures + 1;
            FAILURES.put(mate.id(), attempts);
            // 关节建不起来意味着「配合在存档里存在、但什么都没约束住」——玩家只会看到
            // 「配合建好了但结构纹丝不动」，非常难自己查。所以第一次失败必须把原因打到 WARN，
            // 之后降级为 DEBUG 免得每物理子步刷屏；彻底放弃时再 WARN 一次。
            if (attempts == 1) {
                LOGGER.warn("[SableStopNow] cannot build joint for mate {} ({}); will keep retrying",
                        mate.id(), mate.type(), cause);
            } else if (attempts >= MAX_REBUILD_ATTEMPTS && GAVE_UP.add(mate.id())) {
                LOGGER.warn("[SableStopNow] giving up on mate {} after {} attempts; it will stay inert",
                        mate.id(), attempts);
            }
        }
    }

    /**
     * 按当前位姿重建一个关节。
     *
     * @return null 表示成功；否则返回失败原因（由调用方决定日志级别）
     */
    @Nullable
    private static Throwable buildJoint(final ServerSubLevelContainer container, final Mate mate) {
        final ServerSubLevel subA = (ServerSubLevel) container.getSubLevel(mate.a().body());
        final ServerSubLevel subB = (ServerSubLevel) container.getSubLevel(mate.b().body());
        if (subA == null || subB == null) {
            return new IllegalStateException("body missing");
        }
        try {
            // 不吸附：位姿已经就位，重新吸附会在结构被推歪时把它突然拽回去
            final MateFrames.Plan plan = MateFrames.frames(subA, subB, mate.type(),
                    mate.a(), mate.b(), mate.value(), mate.flip());

            final GenericConstraintConfiguration config = new GenericConstraintConfiguration(
                    clampToPlot(subA, plan.posA()),
                    clampToPlot(subB, plan.posB()),
                    plan.orientationA(),
                    plan.orientationB(),
                    plan.locked());

            final PhysicsConstraintHandle handle =
                    container.physicsSystem().getPipeline().addConstraint(subA, subB, config);
            if (handle == null) {
                return new IllegalStateException("addConstraint returned null");
            }
            // 距离/相切：把该轴钉在目标值上（锁定轴的语义是归零，表达不了非零间距）。
            // setLimit 只在 GenericConstraintHandle 上，所以这里必须按具体类型接住返回值。
            if (plan.limitAxis() != null && handle instanceof final GenericConstraintHandle generic) {
                generic.setLimit(plan.limitAxis(), plan.limitValue(), plan.limitValue());
            }
            // 已经配合在一起的两个结构不应该再互相碰撞 —— 否则共面的两个面会被接触力顶开、和关节对抗
            handle.setContactsEnabled(false);

            JOINTS.put(mate.id(), handle);

            // 关节第一次真正落地：再清一次两侧速度，把「建好之前的这几步」里攒下的速度差抹掉。
            // 只对刚创建的配合做一次，重建（结构被改过）不走这条路，免得把正在运动的配合冻住。
            if (PENDING_SETTLE.remove(mate.id())) {
                try {
                    container.physicsSystem().getPipeline().resetVelocity(subA);
                    container.physicsSystem().getPipeline().resetVelocity(subB);
                } catch (final Exception e) {
                    LOGGER.warn("[SableStopNow] failed to settle mate", e);
                }
            }
            return null;
        } catch (final Throwable t) {
            return t;
        }
    }

    /**
     * 把锚点夹进 plot 内部。
     *
     * <p>{@code PhysicsConstraintConfiguration.validateAnchors} 对落在 plot 外的锚点会直接抛
     * {@link IllegalArgumentException}。方块级参考取的是方块角点/棱中点，正好在包围盒边界上，
     * 所以这里留 1e-3 的余量把坐标收进去 —— 对物理没有任何可见影响。
     */
    private static Vector3dc clampToPlot(final ServerSubLevel sub, final Vector3dc p) {
        final BoundingBox3ic bb = sub.getPlot().getBoundingBox();
        return new Vector3d(
                Math.clamp(p.x(), bb.minX() + PLOT_EPSILON, bb.maxX() + 1 - PLOT_EPSILON),
                Math.clamp(p.y(), bb.minY() + PLOT_EPSILON, bb.maxY() + 1 - PLOT_EPSILON),
                Math.clamp(p.z(), bb.minZ() + PLOT_EPSILON, bb.maxZ() + 1 - PLOT_EPSILON));
    }

    // ============ 冲突检测 ============

    /**
     * 是否会过约束。
     *
     * <p>把同一对结构上已有配合（含本次）锁定的轴做个粗略累加：某个方向超过 3 个自由度就判定冲突。
     * 这是 SolidWorks「配合冲突」的轻量近似 —— 不做完整的自由度求解，但足以挡住
     * 「同一个面重合两次」「三个距离配合锁死一个体」这类明显矛盾。
     */
    /**
     * 是否会过约束（SolidWorks「配合冲突」的轻量近似）。
     *
     * <p>判定逻辑本身在纯函数 {@link MateFrames.Budget} 里（可离线验证），这里只负责把
     * 「同一对结构上已有的配合」喂给它，再加上本次要建的那一条。
     *
     * @param newValue 本次要建立的配合的数值（距离/角度；无值类型忽略）
     */
    private static boolean overConstrained(final MateData data, final MateType type,
                                           final MateRef refA, final MateRef refB,
                                           final double newValue) {
        final MateFrames.Budget budget = new MateFrames.Budget();
        for (final Mate existing : data.all().values()) {
            if (!samePair(existing, refA.body(), refB.body())) {
                continue;
            }
            if (!budget.add(existing.type(), existing.a(), existing.b(), existing.value())) {
                return true;
            }
        }
        if (!budget.add(type, refA, refB, newValue)) {
            return true;
        }
        return false;
    }

    private static boolean samePair(final Mate mate, final UUID a, final UUID b) {
        return (mate.a().body().equals(a) && mate.b().body().equals(b))
                || (mate.a().body().equals(b) && mate.b().body().equals(a));
    }

    // ============ 杂项 ============

    private static void safeRemove(@Nullable final PhysicsConstraintHandle handle) {
        if (handle == null) {
            return;
        }
        try {
            if (handle.isValid()) {
                handle.remove();
            }
        } catch (final Throwable ignored) {
            // 关节已经随物理世界一起没了
        }
    }

    /** 供命令/调试：当前存活关节数。 */
    public static int liveJointCount() {
        return JOINTS.size();
    }

    /** 某个维度所有配合涉及的结构 id（供客户端渲染树）。 */
    public static Collection<UUID> bodiesOf(final ServerLevel level) {
        final Set<UUID> out = new HashSet<>();
        for (final Mate mate : MateData.get(level).all().values()) {
            out.add(mate.a().body());
            out.add(mate.b().body());
        }
        return out;
    }

    static {
        // MateFrames.Budget 靠 ordinal() < 3 区分线性/角向；布局一变判定就会静默出错，所以这里把住。
        if (ConstraintJointAxis.values().length != 6
                || !EnumSet.of(ConstraintJointAxis.LINEAR_X, ConstraintJointAxis.LINEAR_Y,
                        ConstraintJointAxis.LINEAR_Z, ConstraintJointAxis.ANGULAR_X,
                        ConstraintJointAxis.ANGULAR_Y, ConstraintJointAxis.ANGULAR_Z)
                .containsAll(EnumSet.allOf(ConstraintJointAxis.class))) {
            LOGGER.error("[SableStopNow] ConstraintJointAxis layout changed; mate linear/angular split may be wrong");
        }
    }
}
