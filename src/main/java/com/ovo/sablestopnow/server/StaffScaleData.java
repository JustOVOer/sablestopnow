package com.ovo.sablestopnow.server;

import com.ovo.sablestopnow.scale.ScaledColliders;
import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 物理结构缩放 —— 活跃会话 + 与 rapier 碰撞体/质量的联动。
 *
 * <p><b>职责范围（v1.1.0 起）</b>：
 * <ol>
 *   <li><b>活跃缩放会话</b>：按住 X 开始（先把选中的结构统一回 1.0 倍），滚轮给一个绝对倍率 f，
 *       每个成员被驱动到 {@code centroid0 + (pos0 - centroid0) * f}，自身缩放为 {@code f}
 *       —— 组内形状（相对质心的布局）保持不变；</li>
 *   <li><b>碰撞体 + 质量同步</b>：Sable 的 rapier 刚体没有 shape-scale，缩放后必须把体素晶格按新尺寸重采样
 *       （{@link com.ovo.sablestopnow.scale.ScaledColliders}），并把质量/惯量按体积因子重新上传
 *       （{@link com.ovo.sablestopnow.scale.ScaledMass}，由 {@code RapierPhysicsPipelineMixin} 接管）。</li>
 * </ol>
 *
 * <p><b>缩放值本身不再由本类持久化，也不再自建网络同步</b>：scale 就住在子关卡自己的位姿里，
 * 靠 {@code SableNBTUtilsMixin} 随子关卡 NBT 落盘、靠 {@code SableBufferUtilsMixin} 随 Sable 自己的位姿包
 * 到客户端。因此原来的 {@code SavedData}（sablestopnow_scale）与 {@code SyncScalesPayload} 都已删除。
 * 「哪些结构当前是缩放的」改为直接读已加载子关卡的位姿 scale（{@link #activeOrScaledIds}）。
 */
public final class StaffScaleData {

    private StaffScaleData() {
    }

    /** 位姿 scale 与 1.0 的判定阈值。 */
    private static final double SCALE_EPSILON = 1.0e-3;

    // ==================================================================
    //  活跃缩放会话
    // ==================================================================

    private static final Map<UUID, ScaleSession> SESSIONS = new HashMap<>();

    /**
     * 每个结构「我们上一次看到的位姿 scale」，用于发现不是本次会话造成的缩放（例如从存档载入、
     * 或其它系统改了 scale），以及避免重复重建。key 用弱引用，维度卸载后自动回收。
     */
    private static final Map<ServerLevel, Map<UUID, Float>> OBSERVED_SCALES = new WeakHashMap<>();

    /** 一个成员的初始状态（会话开始时记录）。 */
    private static final class Member {
        private final ServerSubLevel sub;
        private final Vector3d pos0 = new Vector3d();
        private final Quaterniond orientation = new Quaterniond();
        /** 会话开始时是否处于航空学锁定状态（缩放前会临时解锁，结束时还原）。 */
        private final boolean wasLocked;

        private Member(final ServerSubLevel sub, final boolean wasLocked) {
            this.sub = sub;
            this.pos0.set(sub.logicalPose().position());
            this.orientation.set(sub.logicalPose().orientation());
            this.wasLocked = wasLocked;
        }
    }

    private static final class ScaleSession {
        private final ServerLevel level;
        private final List<Member> members = new ArrayList<>();
        private final Vector3d centroid0 = new Vector3d();
        /** 最近一次下发的目标位置（用于诊断是否被物理/约束拉回）。 */
        private final Map<UUID, Vector3d> lastTargets = new LinkedHashMap<>();

        private ScaleSession(final ServerLevel level) {
            this.level = level;
        }
    }

    private static boolean isUnscaled(final Vector3dc scale) {
        return Math.abs(scale.x() - 1.0) < SCALE_EPSILON
                && Math.abs(scale.y() - 1.0) < SCALE_EPSILON
                && Math.abs(scale.z() - 1.0) < SCALE_EPSILON;
    }

    @Nullable
    private static PhysicsPipeline pipelineOf(final ServerLevel level, final ServerSubLevel sub) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || sub.isRemoved()) {
            return null;
        }
        final dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem physics = container.physicsSystem();
        return physics == null ? null : physics.getPipeline();
    }

    /**
     * 把 rapier 侧的碰撞体与质量对齐到 {@code sub} 当前位姿 scale —— <b>缩放的真正落地点</b>。
     *
     * <p>调用顺序对应参考实现 {@code SubLevelScale.applyToBody}：
     * <ol>
     *   <li>{@link ScaledColliders#onScaleChanged} 立刻按新尺寸重采样体素晶格（scale=1 时把碰撞体交还 Sable）；
     *       必须同步做完 —— 拖到 tick 末的话，这一 tick 里碰撞体还是旧的、而质量已经是新的；</li>
     *   <li>{@code pipeline.onStatsChanged} 主动推一次质量重传：Sable 只在「未缩放的质量/CoM/惯量变了」时上传，
     *       纯缩放不会触发它。{@code RapierPhysicsPipelineMixin} 会把质量 ×(sx·sy·sz)、惯量按 S² 变换后上传，
     *       并把 localBounds 换成缩放后的格子；</li>
     *   <li>{@code wakeUp} 让睡着的结构在新重量下重新结算；</li>
     *   <li>把「上次网络化的位置」推远：Sable 只在位姿超出 position/orientation 容差时才发快照
     *       （{@code Pose3dc.withinTolerance} 忽略 scale），单独改 scale 的静止结构否则永远不发包。</li>
     * </ol>
     */
    private static void syncPhysics(final ServerLevel level, final ServerSubLevel sub) {
        // 1) 碰撞体：按当前 scale 重采样（这也是 scale=1 时交还 Sable 碰撞体的路径）
        ScaledColliders.onScaleChanged(sub);

        // 2) 质量 / 惯量 / localBounds；第 3 步唤醒
        final PhysicsPipeline pipeline = pipelineOf(level, sub);
        if (pipeline != null) {
            pipeline.onStatsChanged(sub);
            pipeline.wakeUp(sub);
        }

        // 4) 逼 Sable 在下一次跟踪 tick 发一次完整位姿（把 scale 带给客户端）
        sub.lastNetworkedPose().position().add(0.0, 1.0E7, 0.0);

        // 记下我们看到的 scale，免得 reapply 再重建一次
        OBSERVED_SCALES.computeIfAbsent(level, key -> new HashMap<>())
                .put(sub.getUniqueId(), (float) sub.logicalPose().scale().x());
    }

    /**
     * 开始缩放会话：把选中的结构统一回 1.0 倍并记录基线。
     *
     * @return 参与的结构数
     */
    public static int begin(final ServerLevel level, final UUID player, final Collection<UUID> ids) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return 0;
        }
        final var handler = dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler.get(level);
        final ScaleSession session = new ScaleSession(level);
        for (final UUID id : ids) {
            final ServerSubLevel sub = (ServerSubLevel) container.getSubLevel(id);
            if (sub == null || sub.isRemoved()) {
                continue;
            }
            // 锁定状态（航空学固定约束）会把 teleport 拉回去，缩放期间必须先解锁，结束时还原
            final boolean locked = handler != null && handler.isLocked(sub);
            if (locked) {
                handler.toggleLock(id);
            }
            // 统一到未缩放状态（碰撞体也跟着回到 stock：ScaledColliders 会把 resample 过的区块删掉并重放 Sable 的上传）
            final boolean wasScaled = !isUnscaled(sub.logicalPose().scale());
            sub.logicalPose().scale().set(1.0, 1.0, 1.0);
            if (wasScaled) {
                syncPhysics(level, sub);
            }
            session.members.add(new Member(sub, locked));
        }
        if (session.members.isEmpty()) {
            return 0;
        }
        final Vector3d centroid = new Vector3d();
        for (final Member member : session.members) {
            centroid.add(member.pos0);
        }
        centroid.div(session.members.size());
        session.centroid0.set(centroid);
        SESSIONS.put(player, session);
        int lockedCount = 0;
        for (final Member member : session.members) {
            if (member.wasLocked) {
                lockedCount++;
            }
        }
        com.ovo.sablestopnow.SablestopNow.LOGGER.info("[staff] scale begin: {} members ({} were locked -> temporarily unlocked)",
                session.members.size(), lockedCount);
        return session.members.size();
    }

    /** 把活跃会话驱动到倍率 factor（成员自身缩放 + 相对质心布局一起缩放）。 */
    public static void update(final ServerLevel level, final UUID player, final float factor) {
        apply(level, player, factor, null);
    }

    /**
     * 彩蛋 Superliminal：按倍率缩放，并把整组放到指定的世界中心（绝对定位）。
     *
     * <p>必须走这条路而不是继续用整组拖拽马达 —— 马达会把成员驱动到“原始相对位姿”，
     * 和缩放会话里的“相对偏移 × 倍率”互相打架，表现就是结构乱飘。
     */
    public static void place(final ServerLevel level, final UUID player, final float factor, final Vector3dc center) {
        apply(level, player, factor, center);
    }

    private static void apply(final ServerLevel level, final UUID player, final float factor,
                              @Nullable final Vector3dc centerOverride) {
        final ScaleSession session = SESSIONS.get(player);
        if (session == null || session.level != level) {
            return;
        }
        final var handler = dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler.get(level);
        for (final Member member : session.members) {
            final ServerSubLevel sub = member.sub;
            if (sub.isRemoved()) {
                continue;
            }
            // 缩放期间被别的系统（例如超速锁）锁住的话要解锁，否则 teleport 会被约束拉回去
            if (handler != null && handler.isLocked(sub)) {
                handler.toggleLock(sub.getUniqueId());
            }
            // 位置：相对初始质心按倍率外扩/内收；有 centerOverride 时以它为组中心（彩蛋用）
            final Vector3d target = new Vector3d(member.pos0).sub(session.centroid0).mul(factor).add(session.centroid0);
            if (centerOverride != null) {
                target.set(member.pos0).sub(session.centroid0).mul(factor).add(centerOverride);
            }
            sub.logicalPose().position().set(target);
            sub.logicalPose().scale().set(factor, factor, factor);
            final RigidBodyHandle handle = RigidBodyHandle.of(sub);
            if (handle != null) {
                handle.teleport(target, member.orientation);
            }
            // 世界空间包围盒随尺寸变化（客户端 change-bounds 包用）
            sub.updateBoundingBox();
            // ★ 关键：让 rapier 的碰撞体与质量跟着新尺寸走
            syncPhysics(level, sub);
            session.lastTargets.put(sub.getUniqueId(), new Vector3d(target));
        }
    }

    /** 诊断：活跃会话成员是否被物理/约束拉回去了（每 20 tick 由 reapply 调用）。 */
    private static void diagnoseDrift(final ServerLevel level) {
        for (final ScaleSession session : SESSIONS.values()) {
            if (session.level != level) {
                continue;
            }
            for (final Member member : session.members) {
                final Vector3d target = session.lastTargets.get(member.sub.getUniqueId());
                if (target == null || member.sub.isRemoved()) {
                    continue;
                }
                final Vector3dc now = member.sub.logicalPose().position();
                final double drift = now.distance(target);
                if (drift > 0.5) {
                    com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                            "[staff] scale drift on {}: target=({}, {}, {}) now=({}, {}, {}) drift={}",
                            member.sub.getUniqueId(),
                            String.format("%.2f", target.x), String.format("%.2f", target.y), String.format("%.2f", target.z),
                            String.format("%.2f", now.x()), String.format("%.2f", now.y()), String.format("%.2f", now.z()),
                            String.format("%.2f", drift));
                    session.lastTargets.put(member.sub.getUniqueId(), new Vector3d(now));
                }
            }
        }
    }

    /** 结束会话（保留当前结果，并还原会话前处于锁定状态的成员）。 */
    public static void end(final ServerLevel level, final UUID player) {
        final ScaleSession session = SESSIONS.remove(player);
        if (session == null) {
            return;
        }
        final var handler = dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler.get(level);
        if (handler != null) {
            for (final Member member : session.members) {
                if (member.wasLocked && !member.sub.isRemoved() && !handler.isLocked(member.sub)) {
                    handler.toggleLock(member.sub.getUniqueId());
                }
            }
        }
    }

    public static void releaseAll(final UUID player) {
        SESSIONS.remove(player);
    }

    public static void clearSessions() {
        SESSIONS.clear();
        OBSERVED_SCALES.clear();
    }

    /** 是否正在缩放（用于 HUD/互斥判断）。 */
    public static boolean isScaling(final UUID player) {
        return SESSIONS.containsKey(player);
    }

    /**
     * 本维度「正在被缩放」或「已经缩放（倍率≠1）」的所有结构 id（供超速锁豁免等）。
     *
     * <p>缩放表不再由本模组保存，所以这里直接读已加载子关卡的位姿 scale —— 存档写进子关卡 NBT 的 scale
     * 一载入就在这里生效，比原来的 SavedData 更准（不会出现“存档说有、实体已换”的错配）。
     */
    public static Set<UUID> activeOrScaledIds(final ServerLevel level) {
        final Set<UUID> out = new HashSet<>();
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) {
            for (final ServerSubLevel sub : container.getAllSubLevels()) {
                if (!sub.isRemoved() && !isUnscaled(sub.logicalPose().scale())) {
                    out.add(sub.getUniqueId());
                }
            }
        }
        for (final ScaleSession session : SESSIONS.values()) {
            if (session.level == level) {
                for (final Member member : session.members) {
                    out.add(member.sub.getUniqueId());
                }
            }
        }
        return out;
    }

    /** 当前会话的成员（供 Superliminal 之类的驱动读取）。 */
    public static List<ServerSubLevel> sessionMembers(final UUID player) {
        final ScaleSession session = SESSIONS.get(player);
        if (session == null) {
            return List.of();
        }
        final List<ServerSubLevel> out = new ArrayList<>();
        for (final Member member : session.members) {
            if (!member.sub.isRemoved()) {
                out.add(member.sub);
            }
        }
        return out;
    }

    /** 会话中每个成员的初始世界位置（与 {@link #sessionMembers} 同序）。 */
    public static Map<UUID, Vector3d> sessionBaselines(final UUID player) {
        final ScaleSession session = SESSIONS.get(player);
        final Map<UUID, Vector3d> out = new LinkedHashMap<>();
        if (session == null) {
            return out;
        }
        for (final Member member : session.members) {
            out.put(member.sub.getUniqueId(), new Vector3d(member.pos0));
        }
        return out;
    }

    public static Vector3d sessionCentroid(final UUID player) {
        final ScaleSession session = SESSIONS.get(player);
        return session == null ? null : new Vector3d(session.centroid0);
    }

    /**
     * 每 N tick 的一致性兜底。现在的「回灌」不再是往位姿里写 SavedData 的旧值（那是重复持久化），
     * 而是：
     * <ol>
     *   <li>诊断活跃会话成员是否被物理/约束拉回（{@link #diagnoseDrift}）；</li>
     *   <li>发现<b>位姿 scale 在我们背后变了</b>的结构（典型是从存档载入一个已缩放的体、或别的系统改了 scale），
     *       立刻把它的 rapier 碰撞体与质量对齐到当前 scale。第一次见到某个体时只记录不动手 ——
     *       从磁盘载入的已缩放体由物理管线自己的 dirty → {@code ScaledColliders.flushAll} 流程处理。</li>
     * </ol>
     */
    public static void reapply(final ServerLevel level) {
        diagnoseDrift(level);
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        final Map<UUID, Float> observed = OBSERVED_SCALES.computeIfAbsent(level, key -> new HashMap<>());
        for (final ServerSubLevel sub : container.getAllSubLevels()) {
            final UUID id = sub.getUniqueId();
            if (sub.isRemoved()) {
                observed.remove(id);
                continue;
            }
            final float now = (float) sub.logicalPose().scale().x();
            final Float last = observed.get(id);
            if (last == null) {
                // 第一次见到：只记录（普通结构永远是 1，不插手）
                observed.put(id, now);
                continue;
            }
            if (Math.abs(last - now) < (float) SCALE_EPSILON) {
                continue; // 稳态
            }
            if (isScaling(level, id)) {
                continue; // 玩家正在缩放它，apply() 负责
            }
            com.ovo.sablestopnow.SablestopNow.LOGGER.info("[staff] scale changed outside a session on {}: {} -> {} - resyncing collider/mass", id, last, now);
            syncPhysics(level, sub);
        }
    }

    /** 该结构是否属于本维度某个活跃会话。 */
    private static boolean isScaling(final ServerLevel level, final UUID id) {
        for (final ScaleSession session : SESSIONS.values()) {
            if (session.level != level) {
                continue;
            }
            for (final Member member : session.members) {
                if (member.sub.getUniqueId().equals(id)) {
                    return true;
                }
            }
        }
        return false;
    }
}
