package com.ovo.sablestopnow.mate;

import org.jetbrains.annotations.Nullable;

/**
 * 比例配合的纯数学部分（不碰世界、不碰物理，因此可以在离线 checker 里直接钉死）。
 *
 * <h2>语义（已与用户确认）</h2>
 * 选择 2 个以上的面，<b>最后选择的那个是基准（驱动器）</b>，其余为从动：
 * <pre>
 *   k_i = d_i⁰ / d_1⁰        创建时按初始距离算出来，每个 k_i 可以单独改
 *   d_i = k_i · d_1          基准面自由移动时，从动结构按这个关系跟随
 * </pre>
 * 其中 {@code d_1} 是「基准面 ↔ 第一个从动面」的有向距离（沿该对参考自己的测量轴量），
 * 也就是整组的<b>驱动量</b>：它由玩家/物理自由决定，其余距离全部由它派生。
 *
 * <h2>为什么单独一个类</h2>
 * 比例配合不能做成虚拟刚体夹具（相对位姿是连续变化的，不是固定几何），所以它走的是
 * 「每个物理子步做一次运动学辅助」那条路（见 {@code docs/advanced-mates-design.md}）。
 * 把「算什么」抽出来以后，剩下的「怎么施加到物理体上」才是不可离线验证的部分 ——
 * 而那部分只是搬运代码，出错一眼可见。这个类是那个分界线的干净一侧。
 */
public final class RatioRule {

    /** 驱动距离小于它时就认为比例没有意义（除零 / 全零初始状态）。 */
    public static final double MIN_DRIVER = 1.0e-4;

    private RatioRule() {
    }

    /**
     * 由一组初始距离算出各从动面的比例。
     *
     * @param initialDistances 从动面到基准面的初始有向距离；下标 0 是驱动对（{@code d_1⁰}）
     * @return 与入参等长的比例数组（下标 0 恒为 1.0）；初始驱动距离为 0 时返回 null
     */
    @Nullable
    public static double[] ratios(final double[] initialDistances) {
        if (initialDistances == null || initialDistances.length == 0) {
            return null;
        }
        final double driver = initialDistances[0];
        if (Math.abs(driver) < MIN_DRIVER) {
            // 初始就重合的两对面之间没有比例可言：此时 k = d/0 会变成 inf，
            // 后面每子步都会往无穷远推 —— 必须在这里拒绝，而不是让它进到物理里。
            return null;
        }
        final double[] out = new double[initialDistances.length];
        out[0] = 1.0;
        for (int i = 1; i < initialDistances.length; i++) {
            final double k = initialDistances[i] / driver;
            if (!Double.isFinite(k)) {
                return null;
            }
            out[i] = k;
        }
        return out;
    }

    /** 第 i 个从动结构的目标距离。 */
    public static double target(final double ratio, final double driver) {
        return ratio * driver;
    }

    /**
     * 一组的全部目标距离。
     *
     * @param ratios 由 {@link #ratios(double[])} 得到（下标 0 恒为 1）
     */
    public static double[] targets(final double[] ratios, final double driver) {
        final double[] out = new double[ratios.length];
        for (int i = 0; i < ratios.length; i++) {
            out[i] = ratios[i] * driver;
        }
        return out;
    }

    /**
     * 一个比例值是否可用（GUI 里手填的值要过这一关）。
     *
     * <p>允许负数（负号表示「反向跟随」，与把结构翻个面等价），但不允许 NaN/Inf，
     * 也不允许大到离谱 —— 那通常是玩家手滑多打了几位数，直接放进物理里会把结构弹飞到世界外。
     */
    public static boolean isValidRatio(final double k) {
        return Double.isFinite(k) && Math.abs(k) <= 64.0;
    }
}
