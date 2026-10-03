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
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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
    /** 已经「放弃」过的配合 → 放弃的时刻（游戏刻）。到点会再试一次，见 §重建关节。 */
    private static final Map<UUID, Long> GAVE_UP_AT = new HashMap<>();
    /** 放弃之后每隔这么多游戏刻重试一次（100 刻 = 5 秒）。 */
    private static final long RETRY_AFTER_TICKS = 100L;
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
    /**
     * 刚被配合挪动过的物理体（id → 到期 tick）。
     *
     * <p>配合的吸附是 {@code teleport}，会在那一 tick 里造成一个巨大的<b>表观速度</b>。
     * 如果玩家开着「超速自动锁定」，这一下就会把刚配好的结构钉死 —— 表现就是
     * 「配合一建立结构就不动了 / 移动被限速」。所以给被搬动的体一小段豁免期
     * （和「拖拽中」「正在缩放」两类豁免同一个道理，见 {@code StaffEnhanceServer.speedScan}）。
     */
    private static final Map<UUID, Long> MATE_MOVED = new HashMap<>();

    /** 配合搬动过的豁免期（tick）。20 tick = 1 秒，足够跨过 teleport 之后的那一次扫描。 */
    private static final long MATE_MOVE_EXEMPT_TICKS = 20;

    /** 「解冻 → 移动 → 重新锁定」里，最短的移动窗口（tick）与最长等待（tick）。 */
    private static final long MOVE_MIN_TICKS = 5;
    private static final long MOVE_MAX_TICKS = 200;
    /** 判定「已经停下」的速度阈值（格/秒）。 */
    private static final double MOVE_SETTLED_SPEED = 0.05;

    /**
     * 正在「解冻移动」中的配合：mate id → (两侧体, 最早/最晚重新锁定的 tick)。
     *
     * <p>玩家反馈：配合后结构是<b>锁定</b>的（他们要的），但这样一来任何后续改动都推不动它 ——
     * 改距离、勾反转全部「不生效」。所以对配合的任何一次改动都必须走
     * <b>解锁 → 让关节把结构移到新位置 → 停稳后重新锁定</b>这三步。
     * 锁定的时机由这里在物理子步里判定：速度降下来（或等够久了）就锁回去。
     */
    private static final Map<UUID, MoveWindow> MOVING = new HashMap<>();

    private record MoveWindow(UUID a, UUID b, long earliest, long deadline) {
    }

    /** 缓慢搬运的速度上限（格/秒）与角速度上限（度/秒）。 */
    private static final double SNAP_SPEED = 6.0;
    private static final double SNAP_ANGULAR_SPEED = 180.0;
    /** 收尾判定：位置/角度小于它就算到位。 */
    private static final double SNAP_EPSILON = 0.02;
    private static final double SNAP_ANGLE_EPSILON = 0.5;
    /** 一次搬运最多持续多久（tick），超时直接吸到位，避免卡住。 */
    private static final long SNAP_MAX_TICKS = 600;

    /**
     * 正在「缓慢搬运」的配合：mate id → 目标位姿。
     *
     * <p>玩家反馈：「配合的时候应该缓慢移动而不是瞬间传送（这样容易产生大量动量）」。
     * 所以成配/重建时的吸附改成每个物理子步走一小步（限速 {@link #SNAP_SPEED} 格/秒、
     * {@link #SNAP_ANGULAR_SPEED} 度/秒），并且每步都把速度清零 —— 位置是"挪"过去的，
     * 结构不会带着巨额动量冲出去。搬运期间不建关节（否则关节和搬运互拉）。
     */
    private static final Map<UUID, SnapMove> SNAPPING = new HashMap<>();

    private static final class SnapMove {
        private final UUID a;
        private final UUID b;
        private final Vector3d targetPosition;
        private final Quaterniond targetOrientation;
        private final long deadline;

        private SnapMove(final UUID a, final UUID b, final Vector3d targetPosition,
                         final Quaterniond targetOrientation, final long deadline) {
            this.a = a;
            this.b = b;
            this.targetPosition = targetPosition;
            this.targetOrientation = targetOrientation;
            this.deadline = deadline;
        }
    }

    /**
     * 记下「这几个体刚被配合搬动过」—— 超速自动锁定要豁免它们。
     *
     * <p>配合的吸附是 teleport，会在那一 tick 里造出一个巨大的「表观速度」；
     * 不豁免的话玩家开着超速锁就会看到「配合一建立，结构立刻被钉死」。
     * 与「拖拽中」「正在缩放」是同一类豁免（见 {@code StaffEnhanceServer.speedScan}）。
     */
    public static void markMateMoved(final ServerLevel level, final UUID... bodies) {
        final long until = level.getGameTime() + MATE_MOVE_EXEMPT_TICKS;
        for (final UUID body : bodies) {
            if (body != null) {
                MATE_MOVED.put(body, until);
            }
        }
    }

    /** 该体是否处在「刚被配合搬动」的豁免期里（服务端超速锁用）。 */
    public static boolean isMateMoved(final ServerLevel level, final UUID body) {
        final Long until = MATE_MOVED.get(body);
        if (until == null) {
            return false;
        }
        if (level.getGameTime() >= until) {
            MATE_MOVED.remove(body);
            return false;
        }
        return true;
    }

    /**
     * 配合要动这两个体了：<b>先解锁</b>、登记一个「停稳后恢复原锁定状态」的窗口。
     *
     * <p>顺序很重要：解锁 → 移动（关节/teleport）→ 停稳 → 恢复。少了解锁这一步，
     * 已经锁死的结构根本推不动，改距离、勾反转就会「没反应」。
     *
     * <p>⚠ 恢复的时候要<b>按操作前各自的状态</b>恢复，不能一律锁上：
     * 玩家要求「拖动、配合时如果模型原来是锁着的，那么操作结束后也要上锁」——
     * 反过来说，本来是没锁的就该保持没锁。以前这里无条件把两侧都锁上，
     * 于是「碰一下配合，本来能动的结构就动不了了」。
     */
    private static void beginMove(final ServerLevel level, final UUID mateId, final UUID a, final UUID b) {
        // 只在**第一次**观察时记录：beginMove 会连续调用（改类型/改数值/翻转各一次），
        // 第一次之后结构已经被解锁了，再记就成了「本来就没锁」。
        final boolean lockA = ModelMoveService.isLocked(level, a);
        final boolean lockB = ModelMoveService.isLocked(level, b);
        MOVE_WAS_LOCKED.putIfAbsent(mateId, new boolean[] {lockA, lockB});
        try {
            StaffEnhanceServer.setLocks(level, false, java.util.List.of(a, b));
        } catch (final Exception e) {
            LOGGER.warn("[SableStopNow] failed to unlock bodies before mate move", e);
        }
        final long now = level.getGameTime();
        markMateMoved(level, a, b);
        MOVING.put(mateId, new MoveWindow(a, b, now + MOVE_MIN_TICKS, now + MOVE_MAX_TICKS));
    }

    /** 每条配合「进入搬运之前」两侧各自的锁定状态；搬运窗口关闭时消费掉。 */
    private static final Map<UUID, boolean[]> MOVE_WAS_LOCKED = new HashMap<>();

    /**
     * 每个物理子步检查「移动窗口」：结构停稳（或等够久）就把两侧重新锁上。
     *
     * <p>这就是玩家要的「配合后自动锁定」——但只在<b>移动结束之后</b>锁，
     * 移动过程中锁住会让配合的改动永远不生效。
     */
    private static void updateMoveWindows(final ServerSubLevelContainer container, final ServerLevel level) {
        if (MOVING.isEmpty()) {
            return;
        }
        final long now = level.getGameTime();
        final Iterator<Map.Entry<UUID, MoveWindow>> it = MOVING.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<UUID, MoveWindow> entry = it.next();
            final MoveWindow window = entry.getValue();
            // 还在缓慢搬运中：不锁（锁住会把搬运卡死），等搬完再说
            if (SNAPPING.containsKey(entry.getKey())) {
                continue;
            }
            final ServerSubLevel subA = (ServerSubLevel) container.getSubLevel(window.a());
            final ServerSubLevel subB = (ServerSubLevel) container.getSubLevel(window.b());
            if (subA == null || subA.isRemoved() || subB == null || subB.isRemoved()) {
                it.remove();
                continue;
            }
            final boolean settled = now >= window.earliest() && speedOf(subA) < MOVE_SETTLED_SPEED
                    && speedOf(subB) < MOVE_SETTLED_SPEED;
            if (!settled && now < window.deadline()) {
                continue;
            }
            it.remove();
            // 按「操作前各自的状态」恢复：锁过的锁回去，本来没锁的保持不动
            final boolean[] was = MOVE_WAS_LOCKED.remove(entry.getKey());
            final java.util.List<UUID> relock = new java.util.ArrayList<>(2);
            if (was == null || was[0]) {
                relock.add(window.a());
            }
            if (was == null || was[1]) {
                relock.add(window.b());
            }
            if (relock.isEmpty()) {
                continue;
            }
            try {
                StaffEnhanceServer.setLocks(level, true, relock);
            } catch (final Exception e) {
                LOGGER.warn("[SableStopNow] failed to re-lock bodies after mate move", e);
            }
        }
    }

    private static double speedOf(final ServerSubLevel sub) {
        return sub.latestLinearVelocity == null ? 0.0 : sub.latestLinearVelocity.length();
    }

    /** 登记一次「缓慢搬运」：B 会被限速挪到目标位姿，而不是瞬移过去。 */
    private static void startSnap(final ServerLevel level, final UUID mateId, final UUID a, final UUID b,
                                  final Vector3d targetPosition, final Quaterniond targetOrientation) {
        SNAPPING.put(mateId, new SnapMove(a, b, new Vector3d(targetPosition),
                new Quaterniond(targetOrientation), level.getGameTime() + SNAP_MAX_TICKS));
    }

    /**
     * 物理子步里推进所有搬运：限速走一小步 + 清零速度。
     *
     * <p>到位之后登记 {@link #MOVING} 窗口，由 {@link #updateMoveWindows} 在停稳后重新锁定 ——
     * 于是整条流程是「解锁 → 缓慢移动 → 停稳 → 锁定」。
     */
    private static void advanceSnaps(final ServerSubLevelContainer container, final ServerLevel level,
                                     final double timeStep) {
        if (SNAPPING.isEmpty()) {
            return;
        }
        final double dt = timeStep <= 0.0 ? 0.05 : Math.min(timeStep, 0.05);
        final Iterator<Map.Entry<UUID, SnapMove>> it = SNAPPING.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<UUID, SnapMove> entry = it.next();
            final SnapMove move = entry.getValue();
            final ServerSubLevel subB = (ServerSubLevel) container.getSubLevel(move.b);
            if (subB == null || subB.isRemoved()) {
                it.remove();
                continue;
            }
            final RigidBodyHandle handle = RigidBodyHandle.of(subB);
            if (handle == null) {
                it.remove();
                continue;
            }
            final Vector3d pos = new Vector3d(subB.logicalPose().position());
            final Quaterniond rot = new Quaterniond(subB.logicalPose().orientation());
            final double distance = pos.distance(move.targetPosition);
            final double dot = Math.min(1.0, Math.abs(rot.dot(move.targetOrientation)));
            final double angle = Math.toDegrees(2.0 * Math.acos(dot));
            final boolean expired = level.getGameTime() >= move.deadline;
            if ((distance < SNAP_EPSILON && angle < SNAP_ANGLE_EPSILON) || expired) {
                // 到位：精确落到目标位姿，清零速度，然后交给「停稳后重新锁定」的窗口
                handle.teleport(move.targetPosition, move.targetOrientation);
                resetVelocity(container, subB);
                it.remove();
                markMateMoved(level, move.a, move.b);
                final long now = level.getGameTime();
                MOVING.put(entry.getKey(), new MoveWindow(move.a, move.b,
                        now + MOVE_MIN_TICKS, now + MOVE_MAX_TICKS));
                continue;
            }
            // 位置：沿直线限速走一步；姿态：按角速度限速 slerp 一小段
            final double step = Math.min(distance, SNAP_SPEED * dt);
            final Vector3d next = distance < 1e-9 ? pos
                    : new Vector3d(move.targetPosition).sub(pos).normalize().mul(step).add(pos);
            final double t = angle <= 1e-6 ? 0.0 : Math.min(1.0, (SNAP_ANGULAR_SPEED * dt) / angle);
            final Quaterniond nextRot = new Quaterniond(rot).slerp(move.targetOrientation, t);
            handle.teleport(next, nextRot);
            // 每个子步都清速度：这一步的“表观速度”只在限速范围内，不会变成真实动量
            resetVelocity(container, subB);
            // 豁免期跟着搬运续上（搬运可能长于 20 tick），免得超速锁来打断
            markMateMoved(level, move.a, move.b);
        }
    }

    private static void resetVelocity(final ServerSubLevelContainer container, final ServerSubLevel sub) {
        try {
            container.physicsSystem().getPipeline().resetVelocity(sub);
        } catch (final Exception e) {
            LOGGER.warn("[SableStopNow] failed to reset velocity", e);
        }
    }

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
        return create(level, playerId, playerName, type, refA, refB, value,
                type.defaultValue2(), flip);
    }

    /** 带区间上限的重载（「限制」用得上）。 */
    public static Result create(final ServerLevel level, final UUID playerId, final String playerName,
                                final MateType type,
                                final MateRef refA, final MateRef refB,
                                final double value, final double value2, final boolean flip) {
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
        if (overConstrained(data, type, refA, refB, value, value2)) {
            return Result.fail(Status.OVER_CONSTRAINED, "mate.error.over_constrained");
        }

        // 求解（这一步同时给出吸附位姿）
        final MateFrames.Plan plan;
        try {
            plan = MateFrames.solve(subA, subB, type, refA, refB, value, value2, flip);
        } catch (final MateFrames.Unsolvable e) {
            return Result.fail(Status.DEGENERATE, e.getMessage());
        } catch (final Exception e) {
            LOGGER.error("[SableStopNow] failed to solve mate {}", type, e);
            return Result.fail(Status.ERROR, "mate.error.internal");
        }

        // 几何一致性：把 B 摆到新配合要求的位姿上，同一对结构上「本来已经成立」的配合是否还成立。
        //
        // ⚠ 这一步不是 {@link MateFrames.Budget} 的重复：账本只看「同一条轴有没有被钉到不同数值」，
        // 而「点点配合要求两点重合」与「面面重合要求另一个姿态」在账本里都是 LINEAR_Z=0（判定为一致），
        // 实际上两个关节互相拉扯 ⇒ 模型持续抽搐（用户实测反馈）。这里补的就是几何那一半。
        if (conflictsWithExisting(data, type, refA, refB, subA, subB, plan)) {
            return Result.fail(Status.OVER_CONSTRAINED, "mate.error.conflict");
        }

        // 先把 id 定下来：移动窗口要按 mate id 记，重新锁定的时候才找得到这一对
        final UUID mateId = UUID.randomUUID();
        // 任何一次成配都是「解锁 → 移动 → 停稳后重新锁定」：先把两侧解锁，
        // 否则已经锁死的结构根本吸不动（玩家反馈过「配合没反应」）。
        // 停止后由 {@link #updateMoveWindows} 在物理子步里重新锁上。
        beginMove(level, mateId, refA.body(), refB.body());

        if (plan.moveB()) {
            final double distance = plan.newPositionB().distance(subB.logicalPose().position());
            if (distance > MAX_SNAP_DISTANCE) {
                return Result.fail(Status.SNAP_TOO_FAR, "mate.error.snap_too_far");
            }
            // 缓慢搬运：不瞬移（瞬移会造出巨额动量，玩家明确不要），由物理子步限速挪过去。
            // 搬完之前不建关节（见 physicsTick），免得关节和搬运互拉。
            startSnap(level, mateId, refA.body(), refB.body(), plan.newPositionB(), plan.newOrientationB());
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
                mateId, type, refA, refB,
                type.needsSecondValue() ? Math.min(value, value2) : value,
                type.needsSecondValue() ? Math.max(value, value2) : value2,
                flip,
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
        MOVING.remove(mateId);
        MOVE_WAS_LOCKED.remove(mateId);
        SNAPPING.remove(mateId);
        data.remove(mateId);
        return true;
    }

    /**
     * 修改距离/角度，并重建关节（锁定值变了，旧关节必须丢）。
     *
     * <p>走「解锁 → 移动 → 重新锁定」：不在移动前解锁的话，结构是锁死的，
     * 关节推不动它，改数值等于没改（玩家反馈「改变距离未生效」）。
     */
    /** 修改距离/角度/区间，并重建关节。 */
    public static boolean updateValue(final ServerLevel level, final UUID mateId, final double value,
                                      final double value2) {
        final MateData data = MateData.get(level);
        final Mate old = data.all().get(mateId);
        if (old == null || !old.type().needsValue()) {
            return false;
        }
        final MateType type = old.type();
        final double clamped = Math.clamp(value, type.minValue(), type.maxValue());
        final double clamped2 = Math.clamp(value2, type.minValue(), type.maxValue());
        data.put(type.needsSecondValue()
                ? old.withRange(Math.min(clamped, clamped2), Math.max(clamped, clamped2))
                : old.withValue(clamped));
        invalidate(mateId);
        beginMove(level, mateId, old.a().body(), old.b().body());
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
        // 勾/取消「反转」要真的把结构翻过去 —— 同样必须先解锁再重建关节
        beginMove(level, mateId, old.a().body(), old.b().body());
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
        ModelMoveService.clear();
        KinematicAssist.clear();
        CouplingRegistry.clear();
        JOINTS.values().forEach(MateRegistry::safeRemove);
        JOINTS.clear();
        FAILURES.clear();
        GAVE_UP.clear();
        MATE_MOVED.clear();
        MOVING.clear();
        SNAPPING.clear();
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

        // 0) 推进「缓慢搬运」（限速挪动 + 清零速度），再收尾「移动窗口」（停稳后重新锁定）。
        //    即使一条配合都没有也要跑（删除配合后窗口可能还挂着）。
        advanceSnaps(container, level, timeStep);
        updateMoveWindows(container, level);
        // 0b) 模型模式的「左键拖动」也是同一种缓慢搬运，只是没有配合可挂
        ModelMoveService.tick(container, level, timeStep);
        // 0c1) 耦合类配合的「算」这一半：每子步测量 → 算出从动结构的目标位姿 → 交给执行端
        CouplingRegistry.tick(container, level, timeStep);
        // 0c) 耦合类配合（比例/齿轮）的执行端：把从动结构限速挪到耦合逻辑算出的目标位姿
        KinematicAssist.tick(container, level, timeStep);
        // 0d) 可控施力点：频道通电时，往受力点施加「力 × dt」的冲量
        ForcePointService.tick(container, level, timeStep);

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
            // 正在缓慢搬运：等搬到位再建关节，否则关节会和搬运互相拉扯
            if (SNAPPING.containsKey(mate.id())) {
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
                // ⚠ 以前这里是 `continue` —— 也就是「彻底放弃，永远不动」。
                // 而关节建不起来最常见的原因恰恰是**暂时的**：两个结构被撞开/掉远了、
                // 位姿一时退化。玩家把结构搬回来之后，配合本该自己恢复，实际却永久失效
                // （用户实测反馈「配合物相距过远失效」）。
                // 现在放弃之后仍然每隔 RETRY_AFTER_TICKS 再试一次。
                final long now = level.getGameTime();
                final long gaveUpAt = GAVE_UP_AT.getOrDefault(mate.id(), now);
                if (!GAVE_UP_AT.containsKey(mate.id())) {
                    GAVE_UP_AT.put(mate.id(), now);
                }
                if (now - gaveUpAt < RETRY_AFTER_TICKS) {
                    continue;
                }
                FAILURES.remove(mate.id());
                GAVE_UP_AT.remove(mate.id());
                // 落到下面继续尝试重建
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
            // 位置不吸附：位姿已经就位，重新吸附会在结构被推歪时把它突然拽回去。
            MateFrames.Plan plan = MateFrames.frames(subA, subB, mate.type(),
                    mate.a(), mate.b(), mate.value(), mate.value2(), mate.flip());

            // 但**朝向**必须重新对齐。
            //
            // 关节的锁轴语义是「把关节坐标系在两侧的相对位姿保持不变」——也就是把**建关节那一刻**
            // 的相对朝向钉死。所以如果重建时 B 的朝向不对（改过角度数值、被撞歪、或者这是换类型后的
            // 第一次重建），直接建关节等于把「歪掉的朝向」当成目标锁住：数值永远不生效。
            // 表现就是玩家反馈的「距离/角度配合约束不够强」。
            // 这里只转朝向、不动位置（动位置才是当初不做重建吸附的原因）。
            final org.joml.Quaterniond currentB =
                    new org.joml.Quaterniond(subB.logicalPose().orientation());
            if (plan.newOrientationB() != null
                    && Math.abs(currentB.dot(plan.newOrientationB())) < 0.99999) {
                final ServerLevel level = container.physicsSystem().getLevel();
                if (level != null) {
                    // 同样是缓慢转过去（不瞬移）：搬完之前不建关节，physicsTick 会跳过这一条
                    startSnap(level, mate.id(), mate.a().body(), mate.b().body(),
                            new Vector3d(subB.logicalPose().position()), plan.newOrientationB());
                    return null;
                }
            }

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
            // 「限制」是**区间**，做不进几何（几何只能是精确值），交给求解器的区间约束。
            // 区间不自相矛盾（min < max，而且这条轴不锁），所以不会出现 lock/limit 互拉。
            // 精确值类型（距离/角度）的数值已经做进关节坐标系（见 MateFrames 第 5 步：
            // 距离 = 一根虚拟硬杆的长度），那里 limitAxis 为 null，不会走到这支分支。
            if (plan.limitAxis() != null && handle instanceof final GenericConstraintHandle generic) {
                generic.setLimit(plan.limitAxis(), plan.limitValue(), plan.limitMax());
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
        return overConstrained(data, type, refA, refB, newValue,
                type.needsSecondValue() ? type.defaultValue2() : newValue);
    }

    private static boolean overConstrained(final MateData data, final MateType type,
                                           final MateRef refA, final MateRef refB,
                                           final double newValue, final double newValue2) {
        final MateFrames.Budget budget = new MateFrames.Budget();
        for (final Mate existing : data.all().values()) {
            if (!samePair(existing, refA.body(), refB.body())) {
                continue;
            }
            if (!budget.add(existing.type(), existing.a(), existing.b(),
                    existing.value(), existing.value2())) {
                return true;
            }
        }
        if (!budget.add(type, refA, refB, newValue, newValue2)) {
            return true;
        }
        return false;
    }

    /**
     * 新配合吸附后的位姿，会不会让同一对结构上「已经成立」的某条配合不再成立。
     *
     * <p>只在两侧都判：<b>已有配合在当前位姿下确实成立</b>（残差在容差内）、
     * 而<b>在新位姿下不成立</b>时才判为冲突。已有配合当前就没成立（例如还在吸附途中、
     * 或被外力推歪）时不作判断 —— 那种情况下拒绝玩家的新配合只会更费解。
     */
    private static boolean conflictsWithExisting(final MateData data, final MateType type,
                                                 final MateRef refA, final MateRef refB,
                                                 final ServerSubLevel subA, final ServerSubLevel subB,
                                                 final MateFrames.Plan plan) {
        if (!plan.moveB()) {
            // 不需要吸附（LOCK / 已经完全满足）：相对位姿不变，已有配合不可能被破坏
            return false;
        }
        final Pose3dc poseA = subA.logicalPose();
        final BoundingBox3ic boundsA = MateFrames.bounds(subA);
        final BoundingBox3ic boundsB = MateFrames.bounds(subB);
        final Pose3dc poseB = subB.logicalPose();
        // 候选位姿：只有位置/朝向变（吸附就是挪这两样），旋转轴心与缩放沿用当前值
        final Pose3d candidateB = new Pose3d(
                new Vector3d(plan.newPositionB()), new Quaterniond(plan.newOrientationB()),
                new Vector3d(poseB.rotationPoint()), new Vector3d(poseB.scale()));

        for (final Mate existing : data.all().values()) {
            if (!samePair(existing, refA.body(), refB.body())) {
                continue;
            }
            if (existing.type() == MateType.LOCK) {
                // LOCK = 保持当前相对位姿。新配合要把 B 挪走 ⇒ 与它直接矛盾。
                LOGGER.info("[SableStopNow] mate conflict: new {} would move B while LOCK exists", type);
                return true;
            }
            final String now = MateFrames.unsatisfiedReason(existing.type(), existing.a(), existing.b(),
                    existing.value(), existing.value2(), poseA, boundsA, poseB, boundsB);
            if (now != null) {
                continue; // 这条配合现在就没成立，不作为判据
            }
            final String after = MateFrames.unsatisfiedReason(existing.type(), existing.a(), existing.b(),
                    existing.value(), existing.value2(), poseA, boundsA, candidateB, boundsB);
            if (after != null) {
                LOGGER.info("[SableStopNow] mate conflict: new {} breaks existing {} ({})",
                        type, existing.type(), after);
                return true;
            }
        }
        return false;
    }

    private static boolean samePair(final Mate mate, final UUID a, final UUID b) {        return (mate.a().body().equals(a) && mate.b().body().equals(b))
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
