package com.ovo.sablestopnow.sketch.nurbs;

/**
 * **弧长重参数化**：把 NURBS 的自然参数换成"沿曲面的真实距离"。
 *
 * <h2>为什么必须做这一步（贴图通用性的地基）</h2>
 * NURBS 的参数 {@code (u,v)} **不是弧长**：控制点密的地方参数走得慢、疏的地方走得快。
 * 直接拿 {@code (u,v)} 当贴图坐标，就会出现"同一个方块宽度的贴图，在曲面一处被拉长、
 * 另一处被压扁"——这正是我们之前看到的畸变来源之一。
 *
 * <p>重参数化之后：
 * <pre>
 *   (s,t) = (沿 u 方向的累积弧长 / 该方向总长, 沿 v 方向的累积弧长 / 该方向总长)
 * </pre>
 * 于是 {@code |∂S/∂s|} 在整条等参线上近似为常数 —— **尺度统一**，
 * 贴图只要按 {@code 弧长 / 每格方块数} 取坐标，就能均匀平铺。
 *
 * <h2>曲面怎么做（不是曲线）</h2>
 * 曲线只有一个方向，取累积弧长即可。曲面有两个方向，做法是：
 * <ol>
 *   <li>沿一组 {@code v = 常数} 的等参曲线，算各自的 u 向累积弧长表；</li>
 *   <li>把这些表**按 v 平均**成一张 u 向表（不同 v 处的长度分布略有差异，
 *       平均后是"整个曲面上最优的一个折中"）；</li>
 *   <li>v 方向同理。</li>
 * </ol>
 * ⚠ 这是**近似**（严格的等距参数化一般不存在，只有可展曲面才有）。
 * 所以本类不承诺"绝对均匀"，只承诺"比自然参数均匀得多"，而这一点是**可测的**——
 * {@code tools/NurbsCheck} 里断言重参数化后尺度变化范围显著收窄。
 *
 * <p>⚠ 参数边界处的**退化**（例如回转轴上的极点、半径为 0 的收缩点）会让该处的
 * 弧长增量为 0。表里必须做**单调化**（取累积最大值），否则逆映射会多值。
 */
public final class NurbsReparam {

    private final NurbsSurface surface;
    /** u 方向：参数 → 归一化累积弧长（单调不减）。 */
    private final double[] uParam;
    private final double[] uArc;
    /** v 方向同理。 */
    private final double[] vParam;
    private final double[] vArc;
    /** u 方向真实总弧长（方块）。 */
    private final double rawU;
    /** v 方向真实总弧长（方块）。 */
    private final double rawV;

    private NurbsReparam(final NurbsSurface surface, final double[] uParam, final double[] uArc,
                         final double[] vParam, final double[] vArc,
                         final double rawU, final double rawV) {
        this.surface = surface;
        this.uParam = uParam;
        this.uArc = uArc;
        this.vParam = vParam;
        this.vArc = vArc;
        this.rawU = rawU;
        this.rawV = rawV;
    }

    /**
     * 建立重参数化表。
     *
     * @param samples 每个方向的采样段数（越大越准，代价是建表时间与内存）
     */
    public static NurbsReparam build(final NurbsSurface s, final int samples) {
        final double u0 = s.knotsU[s.degreeU];
        final double u1 = s.knotsU[s.knotsU.length - s.degreeU - 1];
        final double v0 = s.knotsV[s.degreeV];
        final double v1 = s.knotsV[s.knotsV.length - s.degreeV - 1];
        final int n = Math.max(8, samples);
        // 先在若干条等参线上采样，再平均
        final double[] uParam = new double[n + 1];
        final double[] uArc = new double[n + 1];
        final int vLines = 8;
        for (int k = 0; k <= n; k++) {
            uParam[k] = u0 + (u1 - u0) * k / n;
        }
        final double[][] uLenPerLine = new double[vLines][n + 1];
        for (int l = 0; l < vLines; l++) {
            final double vv = v0 + (v1 - v0) * (l + 0.5) / vLines;
            double acc = 0.0;
            double[] prev = s.evaluate(uParam[0], vv);
            for (int k = 1; k <= n; k++) {
                final double[] cur = s.evaluate(uParam[k], vv);
                acc += dist(prev, cur);
                uLenPerLine[l][k] = acc;
                prev = cur;
            }
        }
        for (int k = 0; k <= n; k++) {
            double sum = 0.0;
            for (int l = 0; l < vLines; l++) {
                sum += uLenPerLine[l][k];
            }
            uArc[k] = sum / vLines;
        }
        monotonic(uArc);

        final double[] vParam = new double[n + 1];
        final double[] vArc = new double[n + 1];
        final int uLines = 8;
        for (int k = 0; k <= n; k++) {
            vParam[k] = v0 + (v1 - v0) * k / n;
        }
        final double[][] vLenPerLine = new double[uLines][n + 1];
        for (int l = 0; l < uLines; l++) {
            final double uu = u0 + (u1 - u0) * (l + 0.5) / uLines;
            double acc = 0.0;
            double[] prev = s.evaluate(uu, vParam[0]);
            for (int k = 1; k <= n; k++) {
                final double[] cur = s.evaluate(uu, vParam[k]);
                acc += dist(prev, cur);
                vLenPerLine[l][k] = acc;
                prev = cur;
            }
        }
        for (int k = 0; k <= n; k++) {
            double sum = 0.0;
            for (int l = 0; l < uLines; l++) {
                sum += vLenPerLine[l][k];
            }
            vArc[k] = sum / uLines;
        }
        monotonic(vArc);
        // 总弧长要在归一化**之前**取（归一化之后末项恒为 1，拿不到真实长度）
        final double rawU = uArc[uArc.length - 1];
        final double rawV = vArc[vArc.length - 1];
        return new NurbsReparam(s, uParam, normalize(uArc), vParam, normalize(vArc), rawU, rawV);
    }

    /** 取累积最大值，保证单调不减（处理退化点处的 0 增量）。 */
    private static void monotonic(final double[] a) {
        for (int i = 1; i < a.length; i++) {
            if (a[i] < a[i - 1]) {
                a[i] = a[i - 1];
            }
        }
    }

    private static double[] normalize(final double[] a) {
        final double last = a[a.length - 1];
        if (last <= 0.0) {
            final double[] out = new double[a.length];
            for (int i = 0; i < out.length; i++) {
                out[i] = (double) i / (out.length - 1);
            }
            return out;
        }
        final double[] out = new double[a.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = a[i] / last;
        }
        return out;
    }

    /** 归一化弧长 {@code s ∈ [0,1]} → 原来的参数 u。 */
    public double uFromArc(final double s) {
        return interp(uArc, uParam, clamp01(s));
    }

    /** 归一化弧长 {@code t ∈ [0,1]} → 原来的参数 v。 */
    public double vFromArc(final double t) {
        return interp(vArc, vParam, clamp01(t));
    }

    /** 原来的参数 u → 归一化弧长。 */
    public double arcFromU(final double u) {
        return interp(uParam, uArc, u);
    }

    /** 原来的参数 v → 归一化弧长。 */
    public double arcFromV(final double v) {
        return interp(vParam, vArc, v);
    }

    /** 把 {@code (s,t)} 直接求值到曲面（贴图管线最常用的入口）。 */
    public double[] evaluateByArc(final double s, final double t, final double[] out) {
        return surface.evaluate(uFromArc(s), vFromArc(t), out);
    }

    /** u 方向真实总弧长（方块单位）。 */
    public double totalArcU() {
        return rawU;
    }

    /** v 方向真实总弧长（方块单位）。 */
    public double totalArcV() {
        return rawV;
    }

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
        final double f = (x - xs[lo]) / span;
        return ys[lo] + f * (ys[hi] - ys[lo]);
    }

    private static double clamp01(final double v) {
        return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v);
    }

    private static double dist(final double[] a, final double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1])
                + (a[2] - b[2]) * (a[2] - b[2]));
    }
}
