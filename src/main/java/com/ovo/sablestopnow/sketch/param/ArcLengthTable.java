package com.ovo.sablestopnow.sketch.param;

/**
 * **两个方向都弧长化**的累积表：把"参数 → 沿曲面的真实距离（方块）"预计算成单调表。
 *
 * <h2>为什么预计算</h2>
 * 弧长 {@code s(u) = ∫|C'(t)|dt} 没有解析原函数，要数值积分 + 求逆。
 * 那是**每帧热路径里绝对不能做**的事（一张面几千个顶点）。
 * 所以：**建面时算一次，之后只查表**。
 *
 * <h2>为什么必须有它</h2>
 * 用户实测反馈"旋转拉伸处离基准面越远畸变越大"。根因就是角度方向的 UV 用了
 * **每条棱自己的半径**（局部量）→ 相邻环的 v 比例不同 → 网格对不上。
 * 正确做法是让角度方向的尺度**全局面一致**：{@code v = θ · R_ref}，
 * 其中 {@code R_ref} 是整张面的**参考半径**（例如轮廓半径的均值）。
 *
 * <p>⚠ 参考半径是**近似**：锥面（半径随高度变）上不可能处处等距 ——
 * 这是**不可展曲面的数学下界**，不是实现缺陷。本表保证的是
 * **连续、不累积、可预测**，而不是"处处一样宽"。
 */
public final class ArcLengthTable {

    /** 参数采样点（单调）。 */
    private final double[] params;
    /** 对应的累积弧长（方块），单调不减。 */
    private final double[] arcs;

    private ArcLengthTable(final double[] params, final double[] arcs) {
        this.params = params;
        this.arcs = arcs;
    }

    /**
     * 按函数采样建表。
     *
     * @param from     参数下界
     * @param to       参数上界
     * @param samples  采样段数（越大越准）
     * @param distance 相邻参数之间的**真实距离**计算器
     */
    public static ArcLengthTable build(final double from, final double to, final int samples,
                                       final Distance distance) {
        final int n = Math.max(8, samples);
        final double[] params = new double[n + 1];
        final double[] arcs = new double[n + 1];
        for (int i = 0; i <= n; i++) {
            params[i] = from + (to - from) * i / n;
        }
        for (int i = 1; i <= n; i++) {
            arcs[i] = arcs[i - 1] + distance.between(params[i - 1], params[i]);
            // ⚠ 退化处（半径为 0 的收缩点、重复控制点）增量可能是 0，
            //   必须保持单调不减，否则逆映射会多值。
            if (arcs[i] < arcs[i - 1]) {
                arcs[i] = arcs[i - 1];
            }
        }
        return new ArcLengthTable(params, arcs);
    }

    /** 两点之间的距离计算器（由曲面提供）。 */
    public interface Distance {
        double between(double a, double b);
    }

    /** 总弧长（方块）。 */
    public double total() {
        return arcs[arcs.length - 1];
    }

    /** 参数 → 累积弧长。 */
    public double arcAt(final double param) {
        return interp(params, arcs, param);
    }

    /** 累积弧长 → 参数（求逆）。 */
    public double paramAt(final double arc) {
        return interp(arcs, params, arc);
    }

    /** 分段线性插值（两张表都单调，二分即可）。 */
    private static double interp(final double[] xs, final double[] ys, final double x) {
        if (x <= xs[0]) {
            return ys[0];
        }
        if (x >= xs[xs.length - 1]) {
            return ys[ys.length - 1];
        }
        int lo = 0;
        int hi = xs.length - 1;
        while (hi - lo > 1) {
            final int mid = (lo + hi) / 2;
            if (xs[mid] <= x) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        final double span = xs[hi] - xs[lo];
        if (span <= 0.0) {
            return ys[lo];
        }
        return ys[lo] + (xs[hi] == xs[lo] ? 0.0 : (x - xs[lo]) / span) * (ys[hi] - ys[lo]);
    }
}
