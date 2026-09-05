package com.ovo.sablestopnow.server;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.FreeConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.JOMLConversion;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
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

    // ============ 无碰撞标记（占位：视觉 + 状态记录，Sable 暂不支持真实幽灵化） ============

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
        }
    }
}
