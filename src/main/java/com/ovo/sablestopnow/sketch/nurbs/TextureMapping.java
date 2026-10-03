package com.ovo.sablestopnow.sketch.nurbs;

/**
 * **通用贴图映射**：把"方块贴图"铺到任意曲面上的统一做法。
 *
 * <h2>为什么需要"模式"而不是一个万能公式</h2>
 * 这是 CAD 业界的共识（SolidWorks 的贴图/贴花映射就有 平面 / 圆柱 / 球面 / 盒式 等类型）：
 * **没有任何单一映射对所有形状都低畸变**。圆柱用平面投影必然在侧面被拉长；
 * 平面用圆柱投影必然扭曲。所以正确做法是**按面的本性选模式**，
 * 而"面的本性"我们**本来就知道**——因为这些面是我们自己从草图扫出来的：
 *
 * <table border="1">
 *   <caption>形状 → 推荐模式</caption>
 *   <tr><th>面</th><th>模式</th><th>理由</th></tr>
 *   <tr><td>平面（拉伸的封盖、直壁）</td><td>{@link Mode#PLANAR}</td><td>面内投影天然等距</td></tr>
 *   <tr><td>圆柱/圆锥/回转面</td><td>{@link Mode#CYLINDRICAL}</td><td>(弧长, 轴向) 两向都等距</td></tr>
 *   <tr><td>球面/圆角</td><td>{@link Mode#SPHERICAL}</td><td>极点附近仍有畸变（本质限制）</td></tr>
 *   <tr><td>任意/自由曲面</td><td>{@link Mode#SURFACE}</td><td>用曲面自身弧长参数化（{@link NurbsReparam}）</td></tr>
 *   <tr><td>无法判断时</td><td>{@link Mode#BOX}</td><td>三平面兜底：任意形状都能用，代价是主轴交界有硬缝</td></tr>
 * </table>
 *
 * <h2>畸变是**可测的**</h2>
 * {@link #distortion} 给出一个客观数字：把"曲面上的真实距离"与"贴图坐标里的距离"之比
 * 在整张面上采样，取 {@code max/min}。**1.0 = 完美**（贴图处处一样大）。
 * 这个数字让"该用哪个模式"从口头争论变成可验证的结论，见
 * {@code tools/NurbsCheck} 里"同一张圆柱，圆柱模式 ≈1 而平面模式远大于 1"的断言。
 *
 * <p>⚠ 有三个**本质限制**，方案里必须承认，不能假装能解决：
 * <ol>
 *   <li><b>球面极点</b>：任何映射在极点处都会把贴图挤成一个点（拓扑决定的）；</li>
 *   <li><b>不可展曲面</b>：高斯曲率非零的面**不存在**等距参数化，只能最小化畸变；</li>
 *   <li><b>角度接缝</b>：圆柱/球面的角度在 ±π 处必然有一条缝（要么缝、要么重叠）。</li>
 * </ol>
 */
public final class TextureMapping {

    private TextureMapping() {
    }

    /** 映射模式。 */
    public enum Mode {
        /** 沿一个平面投影（面内等距）。 */
        PLANAR,
        /** 圆柱：绕轴的角度弧长 × 轴向距离。 */
        CYLINDRICAL,
        /** 球面：方位角弧长 × 极角弧长。 */
        SPHERICAL,
        /** 盒式/三平面：按法线主轴选投影平面。 */
        BOX,
        /** 曲面自身：用弧长重参数化的 (s,t)。 */
        SURFACE,
    }

    /** 映射用的坐标架。 */
    public record Frame(double[] origin, double[] axisU, double[] axisV, double[] axisW) {

        /** 由原点与三个正交单位向量构造。 */
        public static Frame of(final double[] origin, final double[] u, final double[] v,
                               final double[] w) {
            return new Frame(origin, u, v, w);
        }
    }

    /** 贴图坐标映射：给定三维点、**解析法线**与曲面参数，返回**方块单位**的贴图坐标。 */
    public interface Map {
        double[] uv(double[] point, double[] normal, double u, double v, double[] out);
    }

    /**
     * 构造映射。
     *
     * @param reparam {@link Mode#SURFACE} 需要它（弧长参数化）；其它模式可为 null
     */
    public static Map create(final Mode mode, final Frame f, final NurbsReparam reparam) {
        return switch (mode) {
            case PLANAR -> (p, n, u, v, out) -> {
                out[0] = dot(sub(p, f.origin()), f.axisU());
                out[1] = dot(sub(p, f.origin()), f.axisV());
                return out;
            };
            case CYLINDRICAL -> (p, n, u, v, out) -> {
                final double[] d = sub(p, f.origin());
                final double axial = dot(d, f.axisW());
                final double a = dot(d, f.axisU());
                final double b = dot(d, f.axisV());
                final double radius = Math.max(1.0e-9, Math.hypot(a, b));
                // 角度 × 半径 = 圆周方向的**弧长**，与轴向距离一起给出等距的两向坐标
                out[0] = Math.atan2(b, a) * radius;
                out[1] = axial;
                return out;
            };
            case SPHERICAL -> (p, n, u, v, out) -> {
                final double[] d = sub(p, f.origin());
                final double r = Math.max(1.0e-9, norm(d));
                final double polar = Math.acos(Math.max(-1.0, Math.min(1.0, d[2] / r)));
                final double azim = Math.atan2(d[1], d[0]);
                out[0] = azim * Math.sin(polar) * r; // 方位向弧长
                out[1] = polar * r;                  // 极角向弧长
                return out;
            };
            case BOX -> (p, n, u, v, out) -> {
                // 三平面：按**解析法线**的主轴选投影平面。
                // ⚠ 用解析法线而不是三角面片的几何法线，是为了避免
                //   "同一个四边形的两个三角形选了不同主轴"那种错位（我踩过）。
                final double[] d = sub(p, f.origin());
                final double ax = Math.abs(dot(n, f.axisU()));
                final double ay = Math.abs(dot(n, f.axisV()));
                final double az = Math.abs(dot(n, f.axisW()));
                if (az >= ax && az >= ay) {
                    out[0] = dot(d, f.axisU());
                    out[1] = dot(d, f.axisV());
                } else if (ax >= ay) {
                    out[0] = dot(d, f.axisV());
                    out[1] = dot(d, f.axisW());
                } else {
                    out[0] = dot(d, f.axisU());
                    out[1] = dot(d, f.axisW());
                }
                return out;
            };
            case SURFACE -> (p, n, u, v, out) -> {
                if (reparam == null) {
                    out[0] = u;
                    out[1] = v;
                    return out;
                }
                // 用弧长归一化后乘真实总长 → **方块单位**的两向坐标
                out[0] = reparam.arcFromU(u) * reparam.totalArcU();
                out[1] = reparam.arcFromV(v) * reparam.totalArcV();
                return out;
            };
        };
    }

    /**
     * **畸变指标**：在曲面上采样，比较"曲面真实距离"与"贴图坐标距离"的比值，
     * 返回 {@code max/min}。1.0 = 处处一样大（理想）。
     *
     * <p>做法：每个采样点沿 u、v 各走一小步，得到两个方向上的尺度比，全部采样一起取极值。
     * 这样既覆盖两个方向，也能发现"某一向被压缩"的情况。
     */
    public static double distortion(final NurbsSurface s, final Map map, final int samples) {
        final int n = Math.max(8, samples);
        final double u0 = s.knotsU[s.degreeU];
        final double u1 = s.knotsU[s.knotsU.length - s.degreeU - 1];
        final double v0 = s.knotsV[s.degreeV];
        final double v1 = s.knotsV[s.knotsV.length - s.degreeV - 1];
        final double du = (u1 - u0) / n;
        final double dv = (v1 - v0) / n;
        final java.util.List<Double> scales = new java.util.ArrayList<>();
        final double[] ua = new double[2];
        final double[] ub = new double[2];
        final double[] nrm = new double[3];
        // ⚠⚠ 我第一版有两个洞，都是断言逼出来的：
        //   ① **零压缩被静默跳过**：`td <= eps` 时我直接不采样。后果是"平面模式投到圆柱上"
        //      拿满分 1.0 —— 因为轴向被压成 0 的那一半**根本没被量**。零压缩必须是无限畸变。
        //   ② **接缝被当成畸变**：圆柱/球面的角度在 ±π 处有本质跳变（文档里承认过的限制），
        //      一跳就产生 2πr 的 UV 差，于是"圆柱模式"反而算出 64 的畸变。
        //      接缝不是畸变，是拓扑限制，按**离群值**剔除。
        boolean degenerate = false;
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= n; j++) {
                final double u = u0 + (u1 - u0) * i / n;
                final double v = v0 + (v1 - v0) * j / n;
                final double u2 = Math.min(u1, u + du);
                final double v2 = Math.min(v1, v + dv);
                s.normal(u, v, nrm);
                if (u2 > u) {
                    final double[] p0 = s.evaluate(u, v);
                    final double[] p1 = s.evaluate(u2, v);
                    map.uv(p0, nrm, u, v, ua);
                    map.uv(p1, nrm, u2, v, ub);
                    final double sd = dist(p0, p1);
                    final double td = Math.hypot(ua[0] - ub[0], ua[1] - ub[1]);
                    if (sd > 1.0e-9) {
                        if (td <= 1.0e-12) {
                            degenerate = true; // 曲面动了、贴图坐标没动 → 无限压缩
                        } else {
                            scales.add(sd / td);
                        }
                    }
                }
                if (v2 > v) {
                    final double[] p0 = s.evaluate(u, v);
                    final double[] p1 = s.evaluate(u, v2);
                    map.uv(p0, nrm, u, v, ua);
                    map.uv(p1, nrm, u, v2, ub);
                    final double sd = dist(p0, p1);
                    final double td = Math.hypot(ua[0] - ub[0], ua[1] - ub[1]);
                    if (sd > 1.0e-9) {
                        if (td <= 1.0e-12) {
                            degenerate = true;
                        } else {
                            scales.add(sd / td);
                        }
                    }
                }
            }
        }
        if (degenerate) {
            return Double.POSITIVE_INFINITY;
        }
        if (scales.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        // 接缝剔除：以**中位数**为基准，偏离 10 倍以上的样本判为跳变（接缝），不参与统计
        final java.util.List<Double> sorted = new java.util.ArrayList<>(scales);
        java.util.Collections.sort(sorted);
        final double median = sorted.get(sorted.size() / 2);
        double min = Double.MAX_VALUE;
        double max = 0.0;
        for (final double sc : scales) {
            if (median > 0.0 && (sc > median * 10.0 || sc < median / 10.0)) {
                continue; // 接缝
            }
            min = Math.min(min, sc);
            max = Math.max(max, sc);
        }
        if (min == Double.MAX_VALUE || min <= 0.0) {
            return Double.POSITIVE_INFINITY;
        }
        return max / min;
    }

    // ---------------------------------------------------------------- 向量小工具

    private static double[] sub(final double[] a, final double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double dot(final double[] a, final double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double norm(final double[] a) {
        return Math.sqrt(dot(a, a));
    }

    private static double dist(final double[] a, final double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1])
                + (a[2] - b[2]) * (a[2] - b[2]));
    }
}
