package com.ovo.sablestopnow.server;

import com.ovo.sablestopnow.mate.AssistMath;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 通用的「运动学辅助」执行器：把某个结构<b>限速</b>挪到目标位姿。
 *
 * <p>它是比例/齿轮这类耦合配合的<b>执行端</b>：耦合逻辑每个子步算出「从动结构应该在哪」，
 * 交给这里执行；这里只负责两件事，而且都必须做对：
 *
 * <ol>
 *   <li><b>限速</b>：一步最多走 {@code speed · dt} / 转 {@code angularSpeed · dt}
 *       （{@link AssistMath}）。不限速 = 瞬移，下一个子步就会被求解器读成巨大速度；</li>
 *   <li><b>每个子步清零速度</b>：不动量，就没有「虚空动能」。这与配合的
 *       {@code MateRegistry.startSnap} 和模型拖动的 {@link ModelMoveService} 是同一条纪律，
 *       区别只在于这里的目标由耦合逻辑给出、而不是玩家或配合解算给出。</li>
 * </ol>
 *
 * <p>另外每次推进都登记 {@link MateRegistry#markMateMoved}：耦合会持续很多 tick，
 * 超速锁的豁免期必须跟着续上，否则辅助自己会被限速逻辑打断。
 */
public final class KinematicAssist {

    /** 位置到位判定（格）。 */
    private static final double POSITION_EPSILON = 0.004;
    /** 角度到位判定（度）。 */
    private static final double ANGLE_EPSILON = 0.05;
    /** 目标过期时间：客户端/耦合逻辑停更这么久就放弃（防止卡死在半路）。 */
    private static final long MAX_TICKS = 100;
    /**
     * 单个子步允许的最大位移（格）。
     *
     * <p>限速本来就该把它压得更小，所以一旦超过它，就说明是<b>目标算错了</b>而不是「走得快」。
     * 硬夹住，宁可这一子步少走一点，也不要把结构扔出去。
     */
    private static final double MAX_STEP_DISTANCE = 8.0;
    /**
     * 目标离当前位置超过这么远，就判为「目标算错了」，直接放弃这个目标。
     *
     * <p>耦合（比例/齿轮）的语义是<b>跟随</b>：从动结构只会小幅移动。
     * 一旦算出几十格开外的目标，那一定是几何退化（除以接近零的测量值、参考点重合……）算出来的。
     * 照它执行就是「瞬间飞走」——而且会被 Sable 按「Y 坐标越界」把整个结构删掉
     * （{@code Sub-level ... has an extreme Y coordinate range, removing}），玩家的船就没了。
     */
    private static final double MAX_GOAL_DISTANCE = 64.0;

    private static int rejectedGoals;
    private static long rejectedLastLogMs;

    private record Goal(Vector3d position, Quaterniond orientation,
                        double linearSpeed, double angularSpeed, long deadline) {
    }

    private static final Map<UUID, Goal> ACTIVE = new HashMap<>();

    private KinematicAssist() {
    }

    /**
     * 设定（或刷新）一个结构的目标位姿。
     *
     * <p>允许只给位置不给朝向（{@code orientation == null} 表示「保持当前朝向」），
     * 比例配合就是这么用的：它只沿测量轴平移。
     */
    public static void request(final ServerLevel level, final UUID body, final Vector3d position,
                               @Nullable final Quaterniond orientation,
                               final double linearSpeed, final double angularSpeed) {
        // 目标必须是有限数：NaN/Inf 会让 teleport 把结构送到未知位置（然后被 Sable 删掉）
        if (position == null || !isFinite(position)
                || (orientation != null && !isFinite(orientation))) {
            reject(body, "non-finite goal");
            return;
        }
        ACTIVE.put(body, new Goal(new Vector3d(position),
                orientation == null ? null : new Quaterniond(orientation),
                Math.max(0.0, linearSpeed), Math.max(0.0, angularSpeed),
                level.getGameTime() + MAX_TICKS));
    }

    private static boolean isFinite(final Vector3d v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    private static boolean isFinite(final Quaterniond q) {
        return Double.isFinite(q.x) && Double.isFinite(q.y) && Double.isFinite(q.z) && Double.isFinite(q.w);
    }

    /** 拒绝一个算错的目标，并留下一条（限流的）日志。 */
    private static void reject(final UUID body, final String why) {
        ACTIVE.remove(body);
        rejectedGoals++;
        final long now = System.currentTimeMillis();
        if (now - rejectedLastLogMs < 2000L) {
            return;
        }
        rejectedLastLogMs = now;
        com.ovo.sablestopnow.SablestopNow.LOGGER.warn(
                "[model-diag] kinematic assist rejected goal for {} ({}), {} in 2s — 结构没有被搬走",
                body, why, rejectedGoals);
        rejectedGoals = 0;
    }

    /** 撤销（耦合被删除 / 结构消失 / 世界卸载）。 */
    public static void release(final UUID body) {
        ACTIVE.remove(body);
    }

    public static void clear() {
        ACTIVE.clear();
    }

    public static boolean isActive(final UUID body) {
        return ACTIVE.containsKey(body);
    }

    public static int activeCount() {
        return ACTIVE.size();
    }

    /** 物理子步里推进（由 {@link MateRegistry#physicsTick} 调用）。 */
    public static void tick(final ServerSubLevelContainer container, final ServerLevel level, final double timeStep) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<UUID, Goal>> it = ACTIVE.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<UUID, Goal> entry = it.next();
            final ServerSubLevel sub = (ServerSubLevel) container.getSubLevel(entry.getKey());
            if (sub == null || sub.isRemoved()) {
                it.remove();
                continue;
            }
            final RigidBodyHandle handle = RigidBodyHandle.of(sub);
            if (handle == null) {
                it.remove();
                continue;
            }
            final Goal goal = entry.getValue();
            final Vector3d position = new Vector3d(sub.logicalPose().position());
            final Quaterniond orientation = new Quaterniond(sub.logicalPose().orientation());

            final double distance = position.distance(goal.position);
            // 目标算错了两条判据：不是有限数，或者远得不合理。照它执行 = 把结构扔出世界。
            if (!Double.isFinite(distance)) {
                reject(entry.getKey(), "non-finite distance");
                it.remove();
                continue;
            }
            if (distance > MAX_GOAL_DISTANCE) {
                reject(entry.getKey(), String.format(java.util.Locale.ROOT, "goal %.1f blocks away", distance));
                it.remove();
                continue;
            }

            final double angle = goal.orientation == null ? 0.0 : angleBetween(orientation, goal.orientation);
            final boolean expired = level.getGameTime() >= goal.deadline;
            if (expired
                    || (AssistMath.arrived(distance, 0.0, POSITION_EPSILON) && angle < ANGLE_EPSILON)) {
                // 到位/超时：只剩一点点就精确落位，否则**只停手不搬**。
                // ⚠ 以前不管差多远都 teleport 到目标 —— 目标一旦是过期/算错的，
                // 这一步就是「瞬移几十格」，而 Sable 会把 Y 越界的结构整个删掉。
                if (distance <= POSITION_EPSILON * 8.0) {
                    handle.teleport(goal.position, goal.orientation == null ? orientation : goal.orientation);
                }
                resetVelocity(container, sub);
                it.remove();
                continue;
            }

            // 位置：沿直线限速走一步（再硬夹一道，目标算错时也不至于被扔出去）
            final Vector3d nextPosition;
            if (distance < 1.0e-9) {
                nextPosition = position;
            } else {
                final double step = Math.min(MAX_STEP_DISTANCE, AssistMath.maxStep(goal.linearSpeed, timeStep));
                final double moved = AssistMath.approach(0.0, distance, step);
                nextPosition = new Vector3d(goal.position).sub(position)
                        .normalize().mul(moved).add(position);
            }
            // 朝向：按角速度限速 slerp（角度差走最短路径，见 GearRule.shortestDelta 的说明）
            final Quaterniond nextOrientation;
            if (goal.orientation == null || angle < 1.0e-9) {
                nextOrientation = orientation;
            } else {
                final double maxAngle = AssistMath.maxStep(goal.angularSpeed, timeStep);
                final double t = Math.min(1.0, maxAngle / angle);
                nextOrientation = new Quaterniond(orientation).slerp(goal.orientation, t);
            }
            handle.teleport(nextPosition, nextOrientation);
            resetVelocity(container, sub);
            MateRegistry.markMateMoved(level, entry.getKey());
        }
    }

    /** 两个朝向之间的夹角（度，0..180），取绝对值避免 q 与 -q 的歧义。 */
    private static double angleBetween(final Quaterniond a, final Quaterniond b) {
        final double dot = Math.min(1.0, Math.abs(a.dot(b)));
        return Math.toDegrees(2.0 * Math.acos(dot));
    }

    private static void resetVelocity(final ServerSubLevelContainer container, final ServerSubLevel sub) {
        try {
            container.physicsSystem().getPipeline().resetVelocity(sub);
        } catch (final Exception ignored) {
            // 与既有搬运代码一致：拿不到管线就跳过，不打断其它辅助
        }
    }
}
