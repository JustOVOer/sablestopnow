package com.ovo.sablestopnow.server;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 模型模式「左键拖动模型」的服务端搬运。
 *
 * <p>与配合的搬运（{@code MateRegistry.startSnap}）同一套做法、同一条纪律：
 * <b>限速走一小步 + 每个物理子步清零速度</b>。这样拖动是「缓慢移动」而不是瞬移，
 * 不会把整段位移一次性灌成动量（那正是之前把结构甩飞的原因）。
 *
 * <p>拖动期间持续登记 {@link MateRegistry#markMateMoved}：超速锁的豁免期跟着搬运续上，
 * 免得拉得快一点就被限速逻辑打断。
 *
 * <p>几何上只做平移（保持当前朝向）：SolidWorks 里拖动一个零件也是平移为主，
 * 旋转留给中键轨道相机与视图定向。配合的解锁/重锁走它们自己的搬运窗口，这里不插手 ——
 * 拖动时约束会把同一装配里的其它结构一起带走，松手后由约束求解器收尾。
 */
public final class ModelMoveService {

    /** 搬运速度（格/秒）。比配合的 6 快一点，拖动才跟手，但仍然远低于瞬移。 */
    private static final double SPEED = 9.0;
    /** 到位判定（格）。 */
    private static final double EPSILON = 0.02;
    /** 超过这么多 tick 还没到位就放弃（客户端可能已经卡死/掉线）。 */
    private static final long MAX_TICKS = 200;

    private static final Map<UUID, Move> MOVES = new HashMap<>();

    /**
     * 拖动前本来是「锁定」的那些结构。
     *
     * <p>锁定的物理体拖不动（Simulated 的锁定就是把它冻住），所以拖动必须按需求原文那样做：
     * <b>解锁 → 缓移 → 重新锁定</b>。这里记下「拖之前是锁着的」，松手时再锁回去，
     * 否则玩家拖一下就把自己的锁定状态弄丢了。
     */
    private static final java.util.Set<UUID> WAS_LOCKED = new java.util.HashSet<>();

    private record Move(Vector3d target, long deadline) {
    }

    private ModelMoveService() {
    }

    /**
     * 客户端请求：把某个结构缓慢搬到目标位置。
     *
     * @param target null = 结束拖动
     */
    public static void request(final ServerLevel level, final UUID body, @Nullable final Vector3d target) {
        if (target == null) {
            MOVES.remove(body);
            relockIfNeeded(level, body);
            return;
        }
        if (!MOVES.containsKey(body)) {
            // 拖动开始：锁定的结构要先解锁，否则它根本不动
            if (isLocked(level, body)) {
                WAS_LOCKED.add(body);
                StaffEnhanceServer.setLocks(level, false, java.util.List.of(body));
            }
        }
        MOVES.put(body, new Move(new Vector3d(target), level.getGameTime() + MAX_TICKS));
    }

    /** 结构是否处于 Simulated 的「锁定」状态。 */
    public static boolean isLocked(final ServerLevel level, final UUID body) {
        final var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) {
            return false;
        }
        final var sub = container.getSubLevel(body);
        if (!(sub instanceof final ServerSubLevel server) || server.isRemoved()) {
            return false;
        }
        try {
            return dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler.get(level)
                    .isLocked(server);
        } catch (final Throwable ignored) {
            return false;
        }
    }

    /** 松手/到期时把拖动前锁定的结构锁回去。 */
    private static void relockIfNeeded(final ServerLevel level, final UUID body) {
        if (WAS_LOCKED.remove(body)) {
            StaffEnhanceServer.setLocks(level, true, java.util.List.of(body));
        }
    }

    public static void clear() {
        MOVES.clear();
        WAS_LOCKED.clear();
    }

    /** 物理子步里推进搬运（由 {@link MateRegistry#physicsTick} 调用）。 */
    public static void tick(final ServerSubLevelContainer container, final ServerLevel level, final double timeStep) {
        if (MOVES.isEmpty()) {
            return;
        }
        final double dt = timeStep <= 0.0 ? 0.05 : Math.min(timeStep, 0.05);
        final Iterator<Map.Entry<UUID, Move>> it = MOVES.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<UUID, Move> entry = it.next();
            final ServerSubLevel sub = (ServerSubLevel) container.getSubLevel(entry.getKey());
            if (sub == null || sub.isRemoved()) {
                it.remove();
                relockIfNeeded(level, entry.getKey());
                continue;
            }
            final RigidBodyHandle handle = RigidBodyHandle.of(sub);
            if (handle == null) {
                it.remove();
                continue;
            }
            final Move move = entry.getValue();
            final Vector3d pos = new Vector3d(sub.logicalPose().position());
            final Quaterniond rot = new Quaterniond(sub.logicalPose().orientation());
            final double distance = pos.distance(move.target);
            final boolean expired = level.getGameTime() >= move.deadline;
            if (distance < EPSILON || expired) {
                handle.teleport(move.target, rot);
                resetVelocity(container, sub);
                it.remove();
                // 到位/到期：如果是拖动开始时解锁的，这里要锁回去
                relockIfNeeded(level, entry.getKey());
                continue;
            }
            final double step = Math.min(distance, SPEED * dt);
            final Vector3d next = new Vector3d(move.target).sub(pos).normalize().mul(step).add(pos);
            handle.teleport(next, rot);
            resetVelocity(container, sub);
            MateRegistry.markMateMoved(level, entry.getKey());
        }
    }

    private static void resetVelocity(final ServerSubLevelContainer container, final ServerSubLevel sub) {
        try {
            container.physicsSystem().getPipeline().resetVelocity(sub);
        } catch (final Exception ignored) {
            // 与 MateRegistry 同样：拿不到管线就跳过这一步，不打断其它搬运
        }
    }

    /** 供调试/推理：当前正在被拖动的结构数。 */
    public static int activeMoves() {
        return MOVES.size();
    }

    /** 供树状图读取（拖动中显示高亮用）。 */
    public static boolean isMoving(final UUID body) {
        return MOVES.containsKey(body);
    }

    /** 目标点的读取（测试/调试）。 */
    @Nullable
    public static Vector3dc targetOf(final UUID body) {
        final Move move = MOVES.get(body);
        return move == null ? null : move.target;
    }
}
