package com.ovo.sablestopnow.server;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.FreeConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.GenericConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import com.ovo.sablestopnow.SablestopNowConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 物理手杖增强 —— 服务端“整组拖拽”会话（每成员马达驱动，保持组内相对位姿）。
 *
 * <p>v1 曾用 FixedConstraint 把成员焊到领队，实测会把相距的成员“吸到一起”（固定约束锚点
 * 会把两个体拉到同一锚点）。本实现改为逐成员 FreeConstraint：每物理子步把每个成员当作航空学
 * 单体重物拖拽那样驱动到“由组质心 + 组旋转”算出的精确目标位姿，天然保持相对间距。
 *
 * <p>每子步目标：
 * <pre>desired_i = centerGoal + rot · (CoM_i0 − CoM_0)；q_i = rot · q_i0</pre>
 * 客户端每 tick 发送组中心目标点（世界坐标）与累计旋转 rot（滚轮调距 = 移动 centerGoal）。
 */
public final class StaffEnhanceServer {

    private static final double LINEAR_STIFFNESS = 2650.0;
    private static final double LINEAR_DAMPING = 125.0;
    private static final double ANGULAR_STIFFNESS = 10000.0;
    private static final double ANGULAR_DAMPING = 850.0;

    /** player -> group drag session */
    private static final Map<UUID, GroupDrag> ACTIVE = new HashMap<>();

    /** 实验：无碰撞（幽灵）体与临近其它体的两两临时关节（无机械效果 + contacts_enabled=false）。 */
    private static final double GHOST_SEARCH_SQ = 160.0 * 160.0;
    private static final Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>, Map<UUID, Map<UUID, PhysicsConstraintHandle>>> GHOSTS = new HashMap<>();

    private StaffEnhanceServer() {
    }

    // ============ 生命周期 ============

    public static void startGroupDrag(final ServerLevel level, final UUID player, final UUID ignoredLeader,
                                      final Collection<UUID> members) {
        stopGroupDragInternal(player);
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        final GroupDrag drag = new GroupDrag(player, level);
        double massSum = 0;
        for (final UUID uuid : members) {
            final ServerSubLevel sub = (ServerSubLevel) container.getSubLevel(uuid);
            if (sub == null || sub.isRemoved()) {
                continue;
            }
            final Member m = new Member(sub);
            drag.members.add(m);
            massSum += m.mass;
        }
        if (drag.members.isEmpty()) {
            return;
        }
        // 加权质心（世界坐标 = logicalPose.position）
        final Vector3d centroid = new Vector3d();
        for (final Member m : drag.members) {
            centroid.fma(m.mass, m.com0World);
        }
        centroid.div(massSum);
        drag.centroid0.set(centroid);
        // 初始 centerGoal = 当前质心，客户端随后逐 tick 覆盖
        drag.centerGoal.set(centroid);
        ACTIVE.put(player, drag);
    }

    public static void stopGroupDrag(final ServerLevel level, final UUID player) {
        stopGroupDragInternal(player);
    }

    /** 客户端每 tick 的目标组中心 + 累计旋转。 */
    public static void moveGroup(final UUID player, final Vector3dc centerGoal, final Quaterniond rot) {
        final GroupDrag drag = ACTIVE.get(player);
        if (drag == null) {
            return;
        }
        drag.centerGoal.set(centerGoal);
        drag.rot.set(rot);
    }

    /** 服务端每物理子步（经 SableEventPlatform.onPhysicsTick 注册）：逐个成员马达驱动到目标位姿。 */
    public static void physicsTick(final SubLevelPhysicsSystem physicsSystem, final double timeStep) {
        prePhysicsTick(physicsSystem);
    }

    /** 服务端每物理子步（ForgeSablePrePhysicsTickEvent）：逐个成员马达驱动到目标位姿。 */
    public static void prePhysicsTick(final SubLevelPhysicsSystem physicsSystem) {
        for (final GroupDrag drag : ACTIVE.values()) {
            if (drag.level == null) {
                continue;
            }
            final ServerSubLevelContainer container = SubLevelContainer.getContainer(drag.level);
            if (container == null || container.physicsSystem() != physicsSystem) {
                continue; // 只驱动正在步进的这个维度
            }
            drag.physicsStep(container.physicsSystem().getPipeline());
        }
    }

    /** 服务端每 tick 清理：玩家离线 / 不再持杖 / 领队与成员消失。 */
    public static void serverTick() {
        final Iterator<Map.Entry<UUID, GroupDrag>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<UUID, GroupDrag> entry = it.next();
            final GroupDrag drag = entry.getValue();
            final ServerLevel level = drag.level;
            if (level == null) {
                it.remove();
                continue;
            }
            final ServerPlayer player = level.getServer().getPlayerList().getPlayer(entry.getKey());
            boolean anyAlive = false;
            for (final Member m : drag.members) {
                if (!m.sub.isRemoved()) {
                    anyAlive = true;
                    break;
                }
            }
            if (player == null || !PhysicsStaffItem.isHolding(player) || !anyAlive) {
                drag.dispose();
                it.remove();
            }
        }
    }

    public static void clearAll() {
        ACTIVE.values().forEach(GroupDrag::dispose);
        ACTIVE.clear();
        clearGhosts();
        STEPPING.clear();
        com.ovo.sablestopnow.PhysicsGhosts.clear();
        lastGhosts = Map.of();
    }

    private static void stopGroupDragInternal(final UUID player) {
        final GroupDrag drag = ACTIVE.remove(player);
        if (drag != null) {
            drag.dispose();
        }
    }

    // ============ 会话 ============

    private static final class GroupDrag {
        private final UUID player;
        private final ServerLevel level;
        private final List<Member> members = new ArrayList<>();
        private final Vector3d centroid0 = new Vector3d();
        private final Vector3d centerGoal = new Vector3d();
        private final Quaterniond rot = new Quaterniond();

        private GroupDrag(final UUID player, final ServerLevel level) {
            this.player = player;
            this.level = level;
        }

        private void dispose() {
            for (final Member m : this.members) {
                m.removeConstraint();
            }
            this.members.clear();
        }

        private void physicsStep(final PhysicsPipeline pipeline) {
            for (final Member m : this.members) {
                m.removeConstraint();

                // 当前成员朝向：累计组旋转 · 初始朝向
                final Quaterniond qCur = new Quaterniond(this.rot).mul(m.q0);
                // 期望世界位置：组中心 + rot·(成员初始CoM − 组初始质心)
                final Vector3d offset = new Vector3d(m.com0World).sub(this.centroid0);
                this.rot.transform(offset);
                final Vector3d desiredWorld = new Vector3d(this.centerGoal).add(offset);

                final FreeConstraintConfiguration config = new FreeConstraintConfiguration(
                        JOMLConversion.ZERO,
                        m.plotAnchor,
                        qCur);
                final PhysicsConstraintHandle handle = pipeline.addConstraint(null, m.sub, config);
                if (handle == null) {
                    continue;
                }
                m.handle = handle;

                // 角向：锁在当前朝向（无目标转角）
                for (final ConstraintJointAxis axis : ConstraintJointAxis.ANGULAR) {
                    handle.setMotor(axis, 0.0, ANGULAR_STIFFNESS, ANGULAR_DAMPING, false, 0.0);
                }
                // 线性：把成员的 CoM 锚点拉向期望世界点（与航空学单体重物拖拽同款做法）
                final Vector3d localGoal = new Vector3d(desiredWorld);
                qCur.transformInverse(localGoal);
                handle.setMotor(ConstraintJointAxis.LINEAR_X, localGoal.x(), LINEAR_STIFFNESS, LINEAR_DAMPING, false, 0.0);
                handle.setMotor(ConstraintJointAxis.LINEAR_Y, localGoal.y(), LINEAR_STIFFNESS, LINEAR_DAMPING, false, 0.0);
                handle.setMotor(ConstraintJointAxis.LINEAR_Z, localGoal.z(), LINEAR_STIFFNESS, LINEAR_DAMPING, false, 0.0);
            }
        }
    }

    private static final class Member {
        private final ServerSubLevel sub;
        private final Vector3d plotAnchor;   // CoM 的 plot 局部坐标（锚点）
        private final Vector3d com0World;    // 开始时世界坐标 CoM (= logicalPose.position)
        private final Quaterniond q0;        // 开始时世界朝向
        private final double mass;
        private PhysicsConstraintHandle handle;

        private Member(final ServerSubLevel sub) {
            this.sub = sub;
            this.plotAnchor = new Vector3d(sub.logicalPose().rotationPoint());
            this.com0World = new Vector3d(sub.logicalPose().position());
            this.q0 = new Quaterniond(sub.logicalPose().orientation());
            final double mass = sub.getMassTracker().getMass();
            this.mass = (mass > 0 && Double.isFinite(mass)) ? mass : 1.0;
        }

        private void removeConstraint() {
            if (this.handle != null) {
                if (this.handle.isValid()) {
                    this.handle.remove();
                }
                this.handle = null;
            }
        }
    }

    // ============ 实验：无碰撞（只对其它 Sable 体生效；地形/玩家无效） ============

    /** 每 tick（约半秒一次）维护“无碰撞体 ↔ 临近其它体”的两两临时关节。 */
    public static void ghostTick(final MinecraftServer server) {
        if (server == null) {
            return;
        }
        if (!SablestopNowConfig.isGhostReal()) {
            clearGhosts();
            return;
        }
        for (final ServerLevel level : server.getAllLevels()) {
            final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                continue;
            }
            final java.util.Set<UUID> marked = StaffCollisionData.get(level).getMarked();
            final Map<UUID, Map<UUID, PhysicsConstraintHandle>> dim = GHOSTS.computeIfAbsent(level.dimension(), k -> new HashMap<>());

            if (marked.isEmpty()) {
                dim.values().forEach(map -> map.values().forEach(StaffEnhanceServer::safeRemove));
                dim.clear();
                continue;
            }
            // 清掉已被取消标记/消失的体
            dim.entrySet().removeIf(e -> {
                if (!marked.contains(e.getKey())) {
                    e.getValue().values().forEach(StaffEnhanceServer::safeRemove);
                    return true;
                }
                return false;
            });

            final java.util.List<ServerSubLevel> all = container.getAllSubLevels();
            for (final ServerSubLevel body : all) {
                if (body == null || body.isRemoved() || !marked.contains(body.getUniqueId())) {
                    continue;
                }
                final UUID bodyId = body.getUniqueId();
                final Map<UUID, PhysicsConstraintHandle> pairs = dim.computeIfAbsent(bodyId, k -> new HashMap<>());
                final java.util.Set<UUID> keep = new java.util.HashSet<>();
                for (final ServerSubLevel other : all) {
                    if (other == null || other.isRemoved() || other.getUniqueId().equals(bodyId)) {
                        continue;
                    }
                    final UUID otherId = other.getUniqueId();
                    // 双方都标记时只由 id 更小的一侧建一次，避免重复
                    if (marked.contains(otherId) && otherId.compareTo(bodyId) < 0) {
                        continue;
                    }
                    if (body.logicalPose().position().distanceSquared(other.logicalPose().position()) > GHOST_SEARCH_SQ) {
                        continue;
                    }
                    keep.add(otherId);
                    if (!pairs.containsKey(otherId)) {
                        final PhysicsConstraintHandle handle = createNoCollideJoint(body, other);
                        if (handle != null) {
                            pairs.put(otherId, handle);
                        }
                    }
                }
                final Iterator<java.util.Map.Entry<UUID, PhysicsConstraintHandle>> it = pairs.entrySet().iterator();
                while (it.hasNext()) {
                    final java.util.Map.Entry<UUID, PhysicsConstraintHandle> entry = it.next();
                    if (!keep.contains(entry.getKey())) {
                        safeRemove(entry.getValue());
                        it.remove();
                    }
                }
                if (pairs.isEmpty()) {
                    dim.remove(bodyId);
                }
            }
        }
    }

    /** 无机械效果关节：GenericConstraint 不锁任何轴 + 关闭这对体的接触。 */
    private static PhysicsConstraintHandle createNoCollideJoint(final ServerSubLevel a, final ServerSubLevel b) {
        try {
            final ServerSubLevelContainer container = SubLevelContainer.getContainer(a.getLevel());
            if (container == null) {
                return null;
            }
            final Vector3d anchorA = new Vector3d(a.logicalPose().rotationPoint());
            final Vector3d anchorB = new Vector3d(b.logicalPose().rotationPoint());
            final GenericConstraintConfiguration config = new GenericConstraintConfiguration(
                    anchorA, anchorB, new Quaterniond(), new Quaterniond(), java.util.Set.of());
            final PhysicsConstraintHandle handle = container.physicsSystem().getPipeline().addConstraint(a, b, config);
            if (handle == null) {
                return null;
            }
            handle.setContactsEnabled(false);
            return handle;
        } catch (final Exception e) {
            return null;
        }
    }

    private static void safeRemove(final PhysicsConstraintHandle handle) {
        if (handle != null && handle.isValid()) {
            handle.remove();
        }
    }

    public static void clearGhosts() {
        GHOSTS.values().forEach(dim -> dim.values().forEach(map -> map.values().forEach(StaffEnhanceServer::safeRemove)));
        GHOSTS.clear();
    }

    // ============ 暂停步进（/sablesn tick） ============

    /** level -> 剩余需解除暂停的 tick 数（每个 server tick 步进一次后递减）。 */
    private static final Map<ServerLevel, Integer> STEPPING = new HashMap<>();

    /** 返回 false：未暂停或无法步进。 */
    public static boolean startStepping(final ServerLevel level, final int serverTicks) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || container.physicsSystem() == null) {
            return false;
        }
        final SubLevelPhysicsSystem system = container.physicsSystem();
        if (!system.getPaused()) {
            return false;
        }
        system.setPaused(false);
        STEPPING.put(level, serverTicks);
        return true;
    }

    private static void tickStepping() {
        final Iterator<Map.Entry<ServerLevel, Integer>> it = STEPPING.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<ServerLevel, Integer> entry = it.next();
            final ServerLevel level = entry.getKey();
            final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null || container.physicsSystem() == null) {
                it.remove();
                continue;
            }
            final int left = entry.getValue() - 1;
            if (left <= 0) {
                container.physicsSystem().setPaused(true);
                it.remove();
            } else {
                entry.setValue(left);
            }
        }
    }

    // ============ 超速自动锁定（拖拽中的体豁免） ============

    /** 上次尝试锁定的 tick（避免锁定失败/未持久时的重复广播刷屏）。 */
    private static final Map<UUID, Long> SPEED_LOCK_RETRY = new HashMap<>();

    /** 每 5 tick 扫一次：速度超阈值即锁定并全服提示（带冷却，只在真正锁定成功时广播一次）。 */
    private static void speedScan(final MinecraftServer server) {
        if (!SablestopNowConfig.isSpeedLimitEnabled()) {
            return;
        }
        final double threshold = SablestopNowConfig.speedLimitThreshold();
        for (final ServerLevel level : server.getAllLevels()) {
            final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                continue;
            }
            final java.util.Set<UUID> dragged = currentlyDragged(level);
            // ⚠ 正在被缩放的结构必须一并豁免：teleport 会造成巨大的“表观速度”，
            // 否则超速锁会把它们钉死在原地（表现为“组缩放拉开后被拉回/停住”）。
            final java.util.Set<UUID> scaling = StaffScaleData.activeOrScaledIds(level);
            final dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler locks =
                    dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler.get(level);
            for (final ServerSubLevel sub : container.getAllSubLevels()) {
                if (sub == null || sub.isRemoved() || dragged.contains(sub.getUniqueId())
                        || scaling.contains(sub.getUniqueId())) {
                    continue;
                }
                final UUID subUuid = sub.getUniqueId();
                final double speed = sub.latestLinearVelocity != null ? sub.latestLinearVelocity.length() : 0.0;
                if (speed <= threshold) {
                    SPEED_LOCK_RETRY.remove(subUuid);
                    continue;
                }
                if (locks.isLocked(sub)) {
                    SPEED_LOCK_RETRY.remove(subUuid);
                    continue;
                }
                final long now = server.getTickCount();
                final Long last = SPEED_LOCK_RETRY.get(subUuid);
                if (last != null && now - last < 60) {
                    continue; // 冷却中（上次尝试未成功，稍后静默重试）
                }
                SPEED_LOCK_RETRY.put(subUuid, now);
                locks.toggleLock(subUuid);
                if (!locks.isLocked(sub)) {
                    continue; // 锁定未生效（例如世界锚点校验失败），静默等待冷却后重试
                }
                SPEED_LOCK_RETRY.remove(subUuid);
                final String name = sub.getName() != null ? sub.getName() : subUuid.toString();
                final net.minecraft.world.phys.Vec3 pos = new net.minecraft.world.phys.Vec3(
                        sub.logicalPose().position().x(), sub.logicalPose().position().y(), sub.logicalPose().position().z());
                final String tpCmd = String.format("/tp @p %.2f %.2f %.2f", pos.x, pos.y, pos.z);
                final Component tp = Component.translatable("sablestopnow.speedlock.tp")
                        .withStyle(style -> style
                                .withColor(ChatFormatting.GOLD)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, tpCmd))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Component.translatable("sablestopnow.force.list.click_to_tp"))));
                final Component message = Component.translatable("sablestopnow.speedlock.message", name, String.format("%.1f", speed))
                        .append(Component.literal(" "))
                        .append(tp);
                server.getPlayerList().broadcastSystemMessage(message, false);
            }
        }
    }

    /** 正在被拖拽的物理体：本模组整组控制成员 + 航空学单体拖拽会话（反射读其私有 sessions）。 */
    private static java.util.Set<UUID> currentlyDragged(final ServerLevel level) {
        final java.util.Set<UUID> out = new java.util.HashSet<>();
        for (final GroupDrag drag : ACTIVE.values()) {
            if (drag.level == level) {
                for (final Member m : drag.members) {
                    out.add(m.sub.getUniqueId());
                }
            }
        }
        try {
            final Object handler = dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler.get(level);
            final Object sessions = findDraggingSessions(handler);
            if (sessions instanceof final Map<?, ?> map) {
                for (final Object session : map.values()) {
                    for (final java.lang.reflect.Field f : session.getClass().getDeclaredFields()) {
                        if (ServerSubLevel.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            final Object sub = f.get(session);
                            if (sub instanceof final ServerSubLevel s) {
                                out.add(s.getUniqueId());
                            }
                        }
                    }
                }
            }
        } catch (final Exception ignored) {
            // 反射失败时退化为只豁免本模组整组拖拽的成员
        }
        return out;
    }

    /**
     * 「物理结构 → 正在拖拽它的玩家」（用于把拖拽体对拖拽者幽灵化）。
     * 覆盖本模组整组控制 + 航空学单体拖拽（后者的 sessions map 的 key 就是玩家 UUID）。
     */
    private static Map<UUID, UUID> draggedMap(final ServerLevel level) {
        final Map<UUID, UUID> out = new HashMap<>();
        for (final GroupDrag drag : ACTIVE.values()) {
            if (drag.level == level) {
                for (final Member m : drag.members) {
                    out.put(m.sub.getUniqueId(), drag.player);
                }
            }
        }
        try {
            final Object handler = dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler.get(level);
            final Object sessions = findDraggingSessions(handler);
            if (sessions instanceof final Map<?, ?> map) {
                for (final Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof final UUID playerId) || entry.getValue() == null) {
                        continue;
                    }
                    final Object session = entry.getValue();
                    for (final java.lang.reflect.Field f : session.getClass().getDeclaredFields()) {
                        if (ServerSubLevel.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            if (f.get(session) instanceof final ServerSubLevel s) {
                                out.put(s.getUniqueId(), playerId);
                            }
                        }
                    }
                }
            }
        } catch (final Exception ignored) {
            // 反射失败时退化为只覆盖本模组整组拖拽
        }
        return out;
    }

    /** 上次广播出去的幽灵集合（只有变化时才发包）。 */
    private static Map<UUID, UUID> lastGhosts = Map.of();

    /**
     * 每 tick 权威计算「拖拽体 → 拖拽者」，变化时广播给所有客户端。
     * 玩家的真实碰撞在客户端算，所以客户端必须持有同一份状态（见 PhysicsGhosts / SubLevelEntityCollisionGhostMixin）。
     */
    private static void ghostSyncTick(final MinecraftServer server) {
        final Map<UUID, UUID> ghosts = new HashMap<>();
        final java.util.Set<UUID> globalGhosts = new java.util.LinkedHashSet<>();
        for (final ServerLevel level : server.getAllLevels()) {
            if (SablestopNowConfig.isDraggedNoPlayerCollision()) {
                ghosts.putAll(draggedMap(level));
            }
            // 缩放中的结构：物理碰撞体不随缩放变化，避免把玩家"吸住" → 对所有玩家幽灵化
            if (SablestopNowConfig.isScaledNoPlayerCollision()) {
                globalGhosts.addAll(StaffScaleData.get(level).allScales().keySet());
            }
        }
        if (ghosts.equals(lastGhosts) && globalGhosts.equals(lastGlobalGhosts)) {
            return;
        }
        lastGhosts = ghosts;
        lastGlobalGhosts = globalGhosts;
        com.ovo.sablestopnow.PhysicsGhosts.set(ghosts);
        com.ovo.sablestopnow.PhysicsGhosts.setGlobal(globalGhosts);
        com.ovo.sablestopnow.SablestopNow.LOGGER.info("[staff] ghost set updated: dragged={}, scaled={}",
                ghosts.size(), globalGhosts.size());
        final List<UUID> subs = new ArrayList<>(ghosts.keySet());
        final List<UUID> players = new ArrayList<>(ghosts.values());
        foundry.veil.api.network.VeilPacketManager.all(server)
                .sendPacket(new com.ovo.sablestopnow.network.StaffEnhanceNetworking.SyncActiveGhostsPayload(
                        subs, players, new ArrayList<>(globalGhosts)));
    }

    private static java.util.Set<UUID> lastGlobalGhosts = java.util.Set.of();

    /** 玩家登录时补发当前幽灵集合。 */
    public static void sendGhostsTo(final ServerPlayer player) {
        final List<UUID> subs = new ArrayList<>(lastGhosts.keySet());
        final List<UUID> players = new ArrayList<>(lastGhosts.values());
        foundry.veil.api.network.VeilPacketManager.player(player)
                .sendPacket(new com.ovo.sablestopnow.network.StaffEnhanceNetworking.SyncActiveGhostsPayload(
                        subs, players, new ArrayList<>(lastGlobalGhosts)));
    }

    /**
     * 从 PhysicsStaffServerHandler 反射取“拖拽会话”map（其内部类值含 ServerSubLevel 字段）。
     * 优先按字段名 draggingSessions，其次用“值对象含 ServerSubLevel 字段”的 Map 兜底，
     * 避免误选到只有 UUID/handle 的 locks map。
     */
    private static Object findDraggingSessions(final Object handler) throws IllegalAccessException {
        final java.lang.reflect.Field[] fields = handler.getClass().getDeclaredFields();
        // 1) 按名字
        for (final java.lang.reflect.Field f : fields) {
            if (java.util.Map.class.isAssignableFrom(f.getType()) && f.getName().contains("raging")) {
                f.setAccessible(true);
                return f.get(handler);
            }
        }
        // 2) 值对象含 ServerSubLevel 字段的 Map
        for (final java.lang.reflect.Field f : fields) {
            if (!java.util.Map.class.isAssignableFrom(f.getType())) {
                continue;
            }
            f.setAccessible(true);
            final Object map = f.get(handler);
            if (map instanceof final Map<?, ?> m && !m.isEmpty()) {
                final Object sample = m.values().iterator().next();
                if (hasServerSubLevelField(sample)) {
                    return map;
                }
            }
        }
        return null;
    }

    private static boolean hasServerSubLevelField(final Object value) {
        for (final java.lang.reflect.Field f : value.getClass().getDeclaredFields()) {
            if (ServerSubLevel.class.isAssignableFrom(f.getType())) {
                return true;
            }
        }
        return false;
    }

    /** 每个 server tick 收尾：暂停步进递减、限速扫描、幽灵关节维护。 */
    public static void serverFeatures(final MinecraftServer server) {
        tickStepping();
        if (server.getTickCount() % 5 == 0) {
            speedScan(server);
        }
        if (server.getTickCount() % 10 == 0) {
            ghostTick(server);
        }
        // 缩放值回灌（Sable 自己的序列化不写 scale，重载后会丢）
        if (server.getTickCount() % 20 == 0) {
            for (final ServerLevel level : server.getAllLevels()) {
                StaffScaleData.reapply(level);
            }
        }
        // 拖拽体对拖拽者幽灵化：每 tick 权威计算并在变化时广播
        ghostSyncTick(server);
    }

    // ============ 无碰撞标记（视觉 + 状态记录；真实只对其它 Sable 体，见上方 ghostTick） ============

    /** 按当前锁定状态幂等地把一组物理体设成锁定/解锁（航空学 FixedConstraint）。 */
    public static void setLocks(final ServerLevel level, final boolean lock, final Collection<UUID> subLevels) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        final dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler handler =
                dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler.get(level);
        for (final UUID uuid : subLevels) {
            final ServerSubLevel sub = (ServerSubLevel) container.getSubLevel(uuid);
            if (sub == null || sub.isRemoved()) {
                continue;
            }
            if (handler.isLocked(sub) != lock) {
                handler.toggleLock(uuid);
            }
        }
        broadcastLocks(level);
    }

    /** 该维度当前锁定（航空学）的物理体 UUID 快照。 */
    private static java.util.Set<UUID> lockedSnapshot(final ServerLevel level) {
        final java.util.Set<UUID> out = new java.util.LinkedHashSet<>();
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return out;
        }
        final dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler handler =
                dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler.get(level);
        for (final ServerSubLevel sub : container.getAllSubLevels()) {
            if (!sub.isRemoved() && handler.isLocked(sub)) {
                out.add(sub.getUniqueId());
            }
        }
        return out;
    }

    private static void broadcastLocks(final ServerLevel level) {
        foundry.veil.api.network.VeilPacketManager.all(level.getServer())
                .sendPacket(new com.ovo.sablestopnow.network.StaffEnhanceNetworking.SyncLocksPayload(level.dimension(), lockedSnapshot(level)));
    }

    public static boolean toggleNoCollision(final ServerLevel level, final UUID subLevel) {
        final boolean on = StaffCollisionData.get(level).toggle(subLevel);
        foundry.veil.api.network.VeilPacketManager.all(level.getServer())
                .sendPacket(new com.ovo.sablestopnow.network.StaffEnhanceNetworking.SyncNoCollisionPayload(level.dimension(),
                        StaffCollisionData.get(level).getMarked()));
        return on;
    }

    /** 幂等地把一组物理体设为“无碰撞标记”开/关（与 setLocks 同一语义）。 */
    public static void setNoCollision(final ServerLevel level, final boolean mark, final Collection<UUID> subLevels) {
        final StaffCollisionData data = StaffCollisionData.get(level);
        for (final UUID uuid : subLevels) {
            if (data.getMarked().contains(uuid) != mark) {
                data.toggle(uuid);
            }
        }
        foundry.veil.api.network.VeilPacketManager.all(level.getServer())
                .sendPacket(new com.ovo.sablestopnow.network.StaffEnhanceNetworking.SyncNoCollisionPayload(level.dimension(),
                        data.getMarked()));
    }

    public static java.util.Set<UUID> getNoCollision(final ServerLevel level) {
        return StaffCollisionData.get(level).getMarked();
    }

    public static void sendAllData(final ServerPlayer player) {
        for (final ServerLevel level : player.server.getAllLevels()) {
            foundry.veil.api.network.VeilPacketManager.player(player)
                    .sendPacket(new com.ovo.sablestopnow.network.StaffEnhanceNetworking.SyncNoCollisionPayload(level.dimension(),
                            StaffCollisionData.get(level).getMarked()));
            foundry.veil.api.network.VeilPacketManager.player(player)
                    .sendPacket(new com.ovo.sablestopnow.network.StaffEnhanceNetworking.SyncLocksPayload(level.dimension(),
                            lockedSnapshot(level)));
            broadcastOwnershipTo(player, level);
            // 缩放表补发（Sable 的位姿同步不含 scale）
            foundry.veil.api.network.VeilPacketManager.player(player)
                    .sendPacket(new com.ovo.sablestopnow.network.StaffEnhanceNetworking.SyncScalesPayload(
                            level.dimension().location(), StaffScaleData.scaleEntries(level)));
        }
    }

    // ============ 所有权（功能3） ============

    /** 把某维度的所有权表广播给全服（客户端用于 HUD 显示 + 本地判断）。 */
    public static void broadcastOwnership(final ServerLevel level) {
        final var entries = ownershipEntries(level);
        foundry.veil.api.network.VeilPacketManager.all(level.getServer())
                .sendPacket(new com.ovo.sablestopnow.network.StaffEnhanceNetworking.SyncOwnershipPayload(
                        level.dimension().location(), entries));
    }

    private static void broadcastOwnershipTo(final ServerPlayer player, final ServerLevel level) {
        foundry.veil.api.network.VeilPacketManager.player(player)
                .sendPacket(new com.ovo.sablestopnow.network.StaffEnhanceNetworking.SyncOwnershipPayload(
                        level.dimension().location(), ownershipEntries(level)));
    }

    private static java.util.List<com.ovo.sablestopnow.network.StaffEnhanceNetworking.OwnershipEntry> ownershipEntries(final ServerLevel level) {
        final StaffOwnershipData data = StaffOwnershipData.get(level);
        final java.util.List<com.ovo.sablestopnow.network.StaffEnhanceNetworking.OwnershipEntry> out = new java.util.ArrayList<>();
        for (final java.util.Map.Entry<UUID, UUID> entry : data.allOwners().entrySet()) {
            out.add(new com.ovo.sablestopnow.network.StaffEnhanceNetworking.OwnershipEntry(
                    entry.getKey(), entry.getValue(), data.ownerNameOf(entry.getKey())));
        }
        return out;
    }

    // ============ 快照（功能9） ============

    /** 把“该玩家有哪些结构存了快照”同步给他自己（用于图标提示）。 */
    public static void sendSnapshotState(final net.minecraft.world.entity.player.Player player) {
        if (!(player instanceof final ServerPlayer serverPlayer)) {
            return;
        }
        final var snapshot = StaffSnapshotRegistry.get(serverPlayer.getUUID());
        if (snapshot == null) {
            foundry.veil.api.network.VeilPacketManager.player(serverPlayer)
                    .sendPacket(new com.ovo.sablestopnow.network.StaffEnhanceNetworking.SyncSnapshotPayload(
                            serverPlayer.level().dimension().location(), java.util.List.of()));
            return;
        }
        foundry.veil.api.network.VeilPacketManager.player(serverPlayer)
                .sendPacket(new com.ovo.sablestopnow.network.StaffEnhanceNetworking.SyncSnapshotPayload(
                        snapshot.dimension().location(), new java.util.ArrayList<>(snapshot.data().keySet())));
    }
}
