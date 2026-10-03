package com.ovo.sablestopnow.mate;

/**
 * 运动学辅助（kinematic assist）的限速数学。
 *
 * <h2>它解决什么问题</h2>
 * 比例/齿轮这类「耦合」配合不能做成虚拟刚体夹具，只能在每个物理子步里把从动结构
 * <b>朝目标挪一小步</b>。这一步的算法有两个必须做对的地方，而它们都是纯数学，所以抽到这里，
 * 由离线 checker 钉死：
 *
 * <ol>
 *   <li><b>限速</b>：一步最多挪 {@code maxStep}。不限制的话「挪到目标」等于瞬移，
 *       而瞬移会在下一个子步被求解器读成巨大速度 —— 那就是之前把结构甩飞的机理；</li>
 *   <li><b>角度走最短路径</b>：角度是周期量。{@code 170° → -170°} 的最短路是 +20°，
 *       直接相减会得到 -340°，结构会「绕一整圈」，同样是注能。</li>
 * </ol>
 *
 * <p>调用方（服务端每个子步）只需要把「测得的值 / 目标值 / 本步允许的最大变化量」喂进来，
 * 拿回这一步真正该施加的值，再交给 {@code handle.teleport(...)} 并清零速度。
 */
public final class AssistMath {

    private AssistMath() {
    }

    /**
     * 线性限速：从 {@code current} 朝 {@code target} 最多走 {@code maxStep}。
     *
     * @param maxStep 必须 ≥ 0；为 0 时原地不动
     */
    public static double approach(final double current, final double target, final double maxStep) {
        final double delta = target - current;
        if (!Double.isFinite(delta)) {
            return current;
        }
        final double limit = Math.max(0.0, maxStep);
        if (Math.abs(delta) <= limit) {
            return target;
        }
        return current + Math.signum(delta) * limit;
    }

    /**
     * 角度限速：朝目标转，最多转 {@code maxStepDeg} 度，走最短方向。
     *
     * @return 这一步该施加的绝对角（不会被规范化，保持调用方自己的坐标系连续）
     */
    public static double approachAngle(final double current, final double target, final double maxStepDeg) {
        final double delta = GearRule.shortestDelta(current, target);
        if (!Double.isFinite(delta)) {
            return current;
        }
        final double limit = Math.max(0.0, maxStepDeg);
        if (Math.abs(delta) <= limit) {
            return current + delta;
        }
        return current + Math.signum(delta) * limit;
    }

    /**
     * 一步允许的最大位移（格）。
     *
     * @param speed 格/秒
     * @param dt    子步时长（秒）；非法时按 50ms 兜底（与既有搬运代码同一约定）
     */
    public static double maxStep(final double speed, final double dt) {
        final double step = dt <= 0.0 ? 0.05 : Math.min(dt, 0.05);
        return Math.max(0.0, speed) * step;
    }

    /** 是否已经到位（用于「到位就精确落位并停止」的判定）。 */
    public static boolean arrived(final double current, final double target, final double epsilon) {
        return Math.abs(target - current) <= Math.max(0.0, epsilon);
    }

    /** 角度是否已经到位（走最短路径比较）。 */
    public static boolean arrivedAngle(final double current, final double target, final double epsilonDeg) {
        return Math.abs(GearRule.shortestDelta(current, target)) <= Math.max(0.0, epsilonDeg);
    }
}
