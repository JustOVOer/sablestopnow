package com.ovo.sablestopnow.sketch.nurbs;

/**
 * 把我们现有的各种曲面**精确**表示成 NURBS 的工厂。
 *
 * <h2>为什么能做到"精确"，而不是逼近</h2>
 * 我们现在的曲面全部来自"草图 → 拉伸/旋转"，而这类曲面的 NURBS 表示是**已知闭式解**，
 * 次数不超过 2。所以统一成 NURBS **不损失精度**，这是本方案成立的前提：
 *
 * <table border="1">
 *   <caption>曲面对照表</caption>
 *   <tr><th>曲面</th><th>次数 (u,v)</th><th>权</th><th>说明</th></tr>
 *   <tr><td>平面矩形片</td><td>(1,1)</td><td>全 1</td><td>4 个控制点，节点 {0,0,1,1}×{0,0,1,1}</td></tr>
 *   <tr><td>圆柱面</td><td>(2,1)</td><td>圆周为 1 与 √2/2 交替</td><td>圆用 4 段 90° 有理二次，z 向线性</td></tr>
 *   <tr><td>圆锥面</td><td>(2,1)</td><td>同圆柱</td><td>两个 v 层的半径不同即可（半径随 v 线性）</td></tr>
 *   <tr><td>一般拉伸面</td><td>(p,1)</td><td>继承轮廓</td><td>p = 轮廓次数（直线 1、圆弧 2）</td></tr>
 *   <tr><td>回转面（含圆环）</td><td>(p,2)</td><td>轮廓权 × 圆周权</td><td>角度方向是那 4 段有理二次圆</td></tr>
 * </table>
 *
 * <h3>为什么圆必须用"有理"（带权）</h3>
 * 非有理（不带权）的 B 样条**无法精确表示圆**，只能逼近 —— 我们现在的圆是折线拼的，
 * 换到 NURBS 之后圆是**真的圆**，这是统一带来的第一个实质收益。
 *
 * <p>⚠ 权重 {@code √2/2} 不是凑出来的：90° 圆弧用有理二次时，中间控制点的权
 * 必须是 {@code cos(45°)}，否则曲线不是圆。
 */
public final class NurbsShapes {

    private NurbsShapes() {
    }

    /** 90° 圆弧中间控制点的权：{@code cos(45°) = √2/2}。 */
    public static final double W90 = Math.sqrt(2.0) / 2.0;

    /**
     * **整圆**（在由 axisU/axisV 张成的平面内），精确有理二次。
     *
     * <p>9 个控制点（首尾重合以闭合）、4 段 90°，节点向量：
     * {@code {0,0,0, 1/4,1/4, 1/2,1/2, 3/4,3/4, 1,1,1}}。
     * 权重按 {@code 1, w, 1, w, …} 交替。
     *
     * @param center 圆心
     * @param axisU  平面内的第一个基向量（单位）
     * @param axisV  平面内的第二个基向量（单位）
     */
    public static NurbsCurve circle(final double[] center, final double[] axisU,
                                    final double[] axisV, final double radius) {
        // 外接方形上的 8 个点 + 首点收尾：这是 4 段有理二次圆的经典控制多边形
        final double[][] unit = {
                {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}, {1, 0},
        };
        final double[][] pts = new double[9][3];
        final double[] w = new double[9];
        for (int i = 0; i < 9; i++) {
            for (int k = 0; k < 3; k++) {
                pts[i][k] = center[k] + radius * (unit[i][0] * axisU[k] + unit[i][1] * axisV[k]);
            }
            w[i] = (i % 2 == 0) ? 1.0 : W90;
        }
        final double[] knots = {0, 0, 0, 0.25, 0.25, 0.5, 0.5, 0.75, 0.75, 1, 1, 1};
        return new NurbsCurve(2, knots, pts, w);
    }

    /**
     * **圆弧**（不足 90° 的部分用裁切表达，超过 90° 则按 90° 分段），精确有理二次。
     *
     * <p>做法是把总角度拆成 {@code ceil(|angle| / 90°)} 段，每段按标准有理二次表示，
     * 段与段之间用**重节点**拼接（重数 = 次数 = 2）。
     *
     * <p>⚠ 这是本类最容易写错的地方：段的中间控制点必须落在**两端的切线交点**上，
     * 权必须是 {@code cos(半角)} —— 两者都随每段实际角度变化，不能照抄 90° 的常数。
     * {@code tools/NurbsCheck} 里有对半径恒定的断言来钉住它。
     */
    public static NurbsCurve arc(final double[] center, final double[] axisU, final double[] axisV,
                                 final double radius, final double startAngle,
                                 final double sweep) {
        final int segs = Math.max(1, (int) Math.ceil(Math.abs(sweep) / (Math.PI / 2.0) - 1.0e-9));
        final double dphi = sweep / segs;
        final double half = Math.abs(dphi) / 2.0;
        final double wMid = Math.cos(half); // 半角余弦
        // 控制点：段端点 + 段中间（切线交点 = 单位方向向量之和归一化后放大 r/cos(半角)）
        final java.util.List<double[]> pts = new java.util.ArrayList<>();
        final java.util.List<Double> ws = new java.util.ArrayList<>();
        final java.util.List<Double> kn = new java.util.ArrayList<>();
        for (int s = 0; s <= segs; s++) {
            final double a = startAngle + dphi * s;
            pts.add(onCircle(center, axisU, axisV, radius, a));
            ws.add(1.0);
            if (s == 0) {
                kn.add(0.0);
                kn.add(0.0);
                kn.add(0.0);
            } else {
                final double t = (double) s / segs;
                kn.add(t);
                kn.add(t);
            }
            if (s < segs) {
                // 中间控制点 = 两端切线的交点，距离圆心 r / cos(half)
                final double mid = startAngle + dphi * (s + 0.5);
                pts.add(onCircle(center, axisU, axisV, radius / Math.cos(half), mid));
                ws.add(wMid);
            }
        }
        kn.add(1.0);
        final double[][] pa = pts.toArray(new double[0][]);
        final double[] wa = new double[ws.size()];
        for (int i = 0; i < wa.length; i++) {
            wa[i] = ws.get(i);
        }
        final double[] ka = new double[kn.size()];
        for (int i = 0; i < ka.length; i++) {
            ka[i] = kn.get(i);
        }
        return new NurbsCurve(2, ka, pa, wa);
    }

    private static double[] onCircle(final double[] c, final double[] u, final double[] v,
                                     final double r, final double a) {
        final double ca = Math.cos(a) * r;
        final double sa = Math.sin(a) * r;
        return new double[]{c[0] + ca * u[0] + sa * v[0], c[1] + ca * u[1] + sa * v[1],
                c[2] + ca * u[2] + sa * v[2]};
    }

    /** **平面片**：由一张二维控制网沿 axisU/axisV 拉伸，次数 (1,1)。 */
    public static NurbsSurface planePatch(final double[] origin, final double[] axisU,
                                          final double[] axisV, final double lenU,
                                          final double lenV) {
        final double[][][] c = new double[2][2][3];
        final double[][] w = new double[2][2];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                for (int k = 0; k < 3; k++) {
                    c[i][j][k] = origin[k] + (i * lenU) * axisU[k] + (j * lenV) * axisV[k];
                }
                w[i][j] = 1.0;
            }
        }
        return new NurbsSurface(1, 1, new double[]{0, 0, 1, 1}, new double[]{0, 0, 1, 1}, c, w);
    }

    /**
     * **拉伸面**：把轮廓曲线沿 {@code dir} 拉伸 {@code length}。
     *
     * <p>次数 {@code (p_profile, 1)}，v 方向线性 —— 这正是我们"拉伸"的数学本质，
     * 而且对**任意轮廓**（含圆弧）都精确成立。
     */
    public static NurbsSurface extrude(final NurbsCurve profile, final double[] dir,
                                       final double length) {
        final int n = profile.controlCount();
        final double[][][] c = new double[n][2][3];
        final double[][] w = new double[n][2];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < 3; k++) {
                c[i][0][k] = profile.control[i][k];
                c[i][1][k] = profile.control[i][k] + length * dir[k];
            }
            w[i][0] = profile.weights[i];
            w[i][1] = profile.weights[i];
        }
        final double[] kv = {0, 0, 1, 1};
        return new NurbsSurface(profile.degree, 1, profile.knots.clone(), kv, c, w);
    }

    /**
     * **回转面**：轮廓绕轴旋转。次数 {@code (p_profile, 2)}。
     *
     * <p>角度方向用那 4 段有理二次圆（整圈），轮廓方向的权与角度方向的权**相乘**。
     *
     * <p>轮廓点是二维的（{@code {半径, 轴向}}），这样回转面天然贴合"草图绕轴"的语义。
     *
     * @param profile2d 轮廓控制点 {@code {r, z}}（r 可正可负，负号会绕到另一侧）
     */
    public static NurbsSurface revolve(final double[][] profile2d, final double[] profileWeights,
                                       final int profileDegree, final double[] profileKnots,
                                       final double[] axisOrigin, final double[] axisDir,
                                       final double[] radialDir) {
        // 轴向正交基：axisDir 与 radialDir 必须是单位且正交（调用方保证）
        final double[] tangent = cross(axisDir, radialDir);
        final int n = profile2d.length;
        final double[][][] c = new double[n][9][3];
        final double[][] w = new double[n][9];
        // 角度方向那 9 个控制点（单位半径的圆），单位方形上的经典取法
        final double[][] ang = {
                {1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}, {1, 0},
        };
        for (int i = 0; i < n; i++) {
            final double r = profile2d[i][0];
            final double z = profile2d[i][1];
            for (int j = 0; j < 9; j++) {
                for (int k = 0; k < 3; k++) {
                    c[i][j][k] = axisOrigin[k] + z * axisDir[k]
                            + r * (ang[j][0] * radialDir[k] + ang[j][1] * tangent[k]);
                }
                w[i][j] = profileWeights[i] * (j % 2 == 0 ? 1.0 : W90);
            }
        }
        final double[] kv = {0, 0, 0, 0.25, 0.25, 0.5, 0.5, 0.75, 0.75, 1, 1, 1};
        return new NurbsSurface(profileDegree, 2, profileKnots.clone(), kv, c, w);
    }

    private static double[] cross(final double[] a, final double[] b) {
        return new double[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0],
        };
    }

    /**
     * 一张细分出来的四边形：四个角的位置与法线，外加**它自己的 uv 范围**。
     *
     * <p>为什么要带 uv 范围：容差是可验证的主张，验证时必须在面片内部**重新采样真实曲面**
     * 并与面片的双线性插值比较。没有 uv 范围就没法做到，只能"看起来差不多"。
     */
    public record Patch(double[][] corners, double[][] normals,
                        double u0, double u1, double v0, double v1) {

        /** 双线性插值出的一点（{@code s,t ∈ [0,1]²}）。 */
        public double[] bilinear(final double s, final double t) {
            final double[] out = new double[3];
            for (int k = 0; k < 3; k++) {
                out[k] = (1 - s) * (1 - t) * corners[0][k]
                        + s * (1 - t) * corners[1][k]
                        + s * t * corners[2][k]
                        + (1 - s) * t * corners[3][k];
            }
            return out;
        }
    }

    /**
     * **按弦高容差自适应细分**成四边形网格。
     *
     * <p>做法：u/v 两个方向各自递归二分，判据是**参数中点处的曲面点到该方向弦的距离**
     * 不超过容差。这是接现有渲染（{@code SolidMesh}）与碰撞（体素化）的桥：
     * 两条管线现在都吃三角/四边形网格，所以 NURBS 只要能按容差吐网格，就能**逐步替换**
     * 而不必一次性重写。
     *
     * @param tol     弦高容差（方块）
     * @param maxStep 每个方向最多细分到多少段（防止病态情况炸开）
     */
    public static java.util.List<Patch> tessellate(final NurbsSurface s, final double tol,
                                                   final int maxStep) {
        final double[] us = splitParams(s, true, tol, maxStep);
        final double[] vs = splitParams(s, false, tol, maxStep);
        final java.util.List<Patch> out = new java.util.ArrayList<>();
        final double[] p = new double[3];
        final double[] n = new double[3];
        for (int i = 0; i + 1 < us.length; i++) {
            for (int j = 0; j + 1 < vs.length; j++) {
                final double[][] corners = new double[4][3];
                final double[][] normals = new double[4][3];
                final double[][] uv = {
                        {us[i], vs[j]}, {us[i + 1], vs[j]},
                        {us[i + 1], vs[j + 1]}, {us[i], vs[j + 1]},
                };
                for (int k = 0; k < 4; k++) {
                    s.evaluate(uv[k][0], uv[k][1], p);
                    corners[k][0] = p[0];
                    corners[k][1] = p[1];
                    corners[k][2] = p[2];
                    s.normal(uv[k][0], uv[k][1], n);
                    normals[k][0] = n[0];
                    normals[k][1] = n[1];
                    normals[k][2] = n[2];
                }
                out.add(new Patch(corners, normals, us[i], us[i + 1], vs[j], vs[j + 1]));
            }
        }
        return out;
    }

    /** 单方向的自适应参数切分（递归二分，与曲线用同一套弦高判据）。 */
    private static double[] splitParams(final NurbsSurface s, final boolean uDir,
                                        final double tol, final int maxStep) {
        final double lo = uDir ? s.knotsU[s.degreeU] : s.knotsV[s.degreeV];
        final double hi = uDir
                ? s.knotsU[s.knotsU.length - s.degreeU - 1]
                : s.knotsV[s.knotsV.length - s.degreeV - 1];
        final java.util.List<Double> ts = new java.util.ArrayList<>();
        ts.add(lo);
        // ⚠ 我第一版用"边扫边插入"的循环，写成了 `if (idx + 1 >= ts.size()) break;`
        //   —— 初始只有 lo 一个元素时立刻 break，**永远不细分**（表现为整张圆只有一个面片、
        //   法线退化、细分偏差等于直径）。改成与曲线一致的**递归二分**，逻辑上不可能漏。
        subdivideParams(s, uDir, lo, hi, tol, maxStep, ts, 0);
        ts.add(hi);
        java.util.Collections.sort(ts);
        final double[] out = new double[ts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ts.get(i);
        }
        return out;
    }

    private static void subdivideParams(final NurbsSurface s, final boolean uDir,
                                        final double a, final double b, final double tol,
                                        final int maxStep, final java.util.List<Double> ts,
                                        final int depth) {
        if (depth >= maxStep) {
            return;
        }
        final double m = 0.5 * (a + b);
        final double dev = uDir ? chordDeviationU(s, a, m, b) : chordDeviationV(s, a, m, b);
        if (dev <= tol) {
            return;
        }
        subdivideParams(s, uDir, a, m, tol, maxStep, ts, depth + 1);
        ts.add(m);
        subdivideParams(s, uDir, m, b, tol, maxStep, ts, depth + 1);
    }

    private static double chordDeviationU(final NurbsSurface s, final double a, final double m,
                                          final double b) {
        final double[] pa = s.evaluate(a, 0.5);
        final double[] pb = s.evaluate(b, 0.5);
        final double[] pm = s.evaluate(m, 0.5);
        return pointLineDistance(pm, pa, pb);
    }

    private static double chordDeviationV(final NurbsSurface s, final double a, final double m,
                                          final double b) {
        final double[] pa = s.evaluate(0.5, a);
        final double[] pb = s.evaluate(0.5, b);
        final double[] pm = s.evaluate(0.5, m);
        return pointLineDistance(pm, pa, pb);
    }

    /** 点到线段的距离（弦高）。 */
    public static double pointLineDistance(final double[] p, final double[] a, final double[] b) {
        final double cx = b[0] - a[0];
        final double cy = b[1] - a[1];
        final double cz = b[2] - a[2];
        final double len = Math.sqrt(cx * cx + cy * cy + cz * cz);
        final double mx = p[0] - a[0];
        final double my = p[1] - a[1];
        final double mz = p[2] - a[2];
        if (len < 1.0e-15) {
            return Math.sqrt(mx * mx + my * my + mz * mz);
        }
        final double crx = cy * mz - cz * my;
        final double cry = cz * mx - cx * mz;
        final double crz = cx * my - cy * mx;
        return Math.sqrt(crx * crx + cry * cry + crz * crz) / len;
    }
}
