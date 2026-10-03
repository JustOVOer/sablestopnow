import com.ovo.sablestopnow.sketch.nurbs.NurbsBasis;
import com.ovo.sablestopnow.sketch.nurbs.NurbsCurve;
import com.ovo.sablestopnow.sketch.nurbs.NurbsShapes;
import com.ovo.sablestopnow.sketch.nurbs.NurbsSurface;

/**
 * NURBS 统一方案的**离线检查**（不依赖 Minecraft、不依赖 JOML）。
 *
 * <p>这里钉住的是方案里每一句**数学主张**。方案要是没有这些断言，就只是"听起来合理"：
 * <ul>
 *   <li>基函数：单位分解（和为 1）、非负；</li>
 *   <li>夹紧：曲线**过首末控制点**；</li>
 *   <li>圆的**精确性**：有理二次圆上每一点到圆心距离恒等于 R —— 这是"圆变成真的圆"的证据；</li>
 *   <li>任意张角圆弧的半径恒定 —— 钉住中间控制点与权（最容易写错的一处）；</li>
 *   <li>导数：解析导数与**中心差分**一致（曲线与曲面各一遍）；</li>
 *   <li>圆柱面：到轴距离恒定、法线是径向；</li>
 *   <li>圆锥面（回转直线）：半径与轴向坐标成线性关系；</li>
 *   <li>细分：每个四边形面片的**弦高**都不超过给定容差。</li>
 * </ul>
 *
 * <p>输出格式与其它检查工具一致，便于 verify 脚本收集。
 */
public final class NurbsCheck {

    private static int checks;
    private static int failures;

    public static void main(final String[] args) {
        basisPartitionOfUnity();
        basisNonNegative();
        clampedEndpoints();
        circleExactness();
        arcAnySweepRadius();
        curveDerivativeVsFiniteDifference();
        surfaceDerivativeVsFiniteDifference();
        normalIsUnitAndRadialForCylinder();
        coneRadiusLinear();
        torusRevolutionExactness();
        tessellationRespectsTolerance();
        reparamInverseConsistency();
        reparamImprovesScaleUniformity();
        mappingModesMatchShapes();
        System.out.println("NURBS_CHECK=" + (failures == 0 ? "OK" : "FAIL")
                + " checks=" + checks + " failures=" + failures);
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 基函数

    private static void basisPartitionOfUnity() {
        for (final int degree : new int[]{1, 2, 3}) {
            final int n = 7;
            final double[] knots = NurbsBasis.clampedKnots(degree, n);
            double worst = 0.0;
            for (int k = 0; k <= 200; k++) {
                final double u = (double) k / 200.0;
                final int span = NurbsBasis.span(degree, knots, u);
                final double[] b = NurbsBasis.basisFuns(span, u, degree, knots);
                double sum = 0.0;
                for (final double v : b) {
                    sum += v;
                }
                worst = Math.max(worst, Math.abs(sum - 1.0));
            }
            check("单位分解 p=" + degree, worst < 1.0e-12, "最大偏差 " + worst);
        }
    }

    private static void basisNonNegative() {
        final int degree = 2;
        final double[] knots = NurbsBasis.clampedKnots(degree, 6);
        double min = Double.MAX_VALUE;
        for (int k = 0; k <= 200; k++) {
            final double u = (double) k / 200.0;
            final int span = NurbsBasis.span(degree, knots, u);
            for (final double v : NurbsBasis.basisFuns(span, u, degree, knots)) {
                min = Math.min(min, v);
            }
        }
        check("基函数非负", min >= -1.0e-15, "最小值 " + min);
    }

    private static void clampedEndpoints() {
        final double[][] pts = {{0, 0, 0}, {1, 2, 0}, {3, 1, 0}, {4, 4, 0}};
        final double[] w = {1, 1, 1, 1};
        final NurbsCurve c = new NurbsCurve(2, NurbsBasis.clampedKnots(2, 4), pts, w);
        final double[] head = c.evaluate(c.uMin());
        final double[] tail = c.evaluate(c.uMax());
        check("夹紧曲线过首控制点",
                dist(head, pts[0]) < 1.0e-12, "偏差 " + dist(head, pts[0]));
        check("夹紧曲线过末控制点",
                dist(tail, pts[3]) < 1.0e-12, "偏差 " + dist(tail, pts[3]));
    }

    // ---------------------------------------------------------------- 精确性

    private static void circleExactness() {
        final double r = 3.7;
        final NurbsCurve c = NurbsShapes.circle(new double[]{1, 2, 3},
                new double[]{1, 0, 0}, new double[]{0, 1, 0}, r);
        double worst = 0.0;
        for (int k = 0; k <= 400; k++) {
            final double u = c.uMin() + (c.uMax() - c.uMin()) * k / 400.0;
            final double[] p = c.evaluate(u);
            final double dx = p[0] - 1;
            final double dy = p[1] - 2;
            worst = Math.max(worst, Math.abs(Math.hypot(dx, dy) - r));
        }
        // 有理二次圆的精确性是**代数精确**，只受浮点误差影响
        check("有理二次圆半径恒定（精确）", worst < 1.0e-12, "最大偏差 " + worst);
    }

    private static void arcAnySweepRadius() {
        final double r = 2.5;
        for (final double sweepDeg : new double[]{30, 90, 137, 180, 270, 359}) {
            final double sweep = Math.toRadians(sweepDeg);
            final NurbsCurve c = NurbsShapes.arc(new double[]{0, 0, 0},
                    new double[]{1, 0, 0}, new double[]{0, 1, 0}, r, 0.3, sweep);
            double worst = 0.0;
            for (int k = 0; k <= 200; k++) {
                final double u = c.uMin() + (c.uMax() - c.uMin()) * k / 200.0;
                final double[] p = c.evaluate(u);
                worst = Math.max(worst, Math.abs(Math.hypot(p[0], p[1]) - r));
            }
            // 分段拼接处允许略大的浮点误差
            check("圆弧半径恒定 sweep=" + sweepDeg + "°", worst < 1.0e-10, "最大偏差 " + worst);
        }
    }

    // ---------------------------------------------------------------- 导数

    private static void curveDerivativeVsFiniteDifference() {
        final NurbsCurve c = NurbsShapes.circle(new double[]{0, 0, 0},
                new double[]{1, 0, 0}, new double[]{0, 1, 0}, 2.0);
        final double h = 1.0e-6;
        double worst = 0.0;
        final double lo = c.uMin();
        final double hi = c.uMax();
        for (int k = 1; k < 60; k++) {
            final double u = lo + (hi - lo) * k / 60.0;
            // ⚠ **必须避开节点**：跨越节点时函数只有 C¹，中心差分的误差退化到 O(h)，
            //   差值会到 1e-4 量级 —— 我第一版就是在这里误判"解析导数写错了"，
            //   实际是**测试比较的位置不对**。
            if (nearKnot(c.knots, u, 1.0e-3)) {
                continue;
            }
            final double[] a = c.evaluate(u - h);
            final double[] b = c.evaluate(u + h);
            final double[] fd = {(b[0] - a[0]) / (2 * h), (b[1] - a[1]) / (2 * h),
                    (b[2] - a[2]) / (2 * h)};
            final double[] an = c.derivative(u);
            worst = Math.max(worst, dist(fd, an) / Math.max(1.0, len(an)));
        }
        check("曲线解析导数 vs 中心差分（避开节点）", worst < 1.0e-6, "最大相对偏差 " + worst);

        // **独立检查**：Σ N_i ≡ 1 ⇒ Σ dN_i/du ≡ 0（不依赖差分，任何 u 都成立）
        double worstSum = 0.0;
        for (int k = 0; k <= 200; k++) {
            final double u = lo + (hi - lo) * k / 200.0;
            final int span = NurbsBasis.span(c.degree, c.knots, u);
            final double[] d = new double[c.degree + 1];
            NurbsBasis.basisDerivs1(span, u, c.degree, c.knots, d);
            double sum = 0.0;
            for (final double v : d) {
                sum += v;
            }
            worstSum = Math.max(worstSum, Math.abs(sum));
        }
        check("基函数导数之和为 0", worstSum < 1.0e-9, "最大偏差 " + worstSum);
    }

    /** u 是否落在某个节点附近。 */
    private static boolean nearKnot(final double[] knots, final double u, final double eps) {
        for (final double k : knots) {
            if (Math.abs(u - k) < eps) {
                return true;
            }
        }
        return false;
    }

    private static void surfaceDerivativeVsFiniteDifference() {
        final NurbsSurface s = cylinder(2.0, 5.0);
        final double h = 1.0e-6;
        double worstU = 0.0;
        double worstV = 0.0;
        final double[] su = new double[3];
        final double[] sv = new double[3];
        for (int a = 1; a < 12; a++) {
            for (int b = 1; b < 6; b++) {
                final double u = 0.05 + 0.85 * a / 12.0;
                final double v = 0.1 + 0.8 * b / 6.0;
                s.partials(u, v, su, sv);
                final double[] pu0 = s.evaluate(u - h, v);
                final double[] pu1 = s.evaluate(u + h, v);
                final double[] fdu = {(pu1[0] - pu0[0]) / (2 * h), (pu1[1] - pu0[1]) / (2 * h),
                        (pu1[2] - pu0[2]) / (2 * h)};
                final double[] pv0 = s.evaluate(u, v - h);
                final double[] pv1 = s.evaluate(u, v + h);
                final double[] fdv = {(pv1[0] - pv0[0]) / (2 * h), (pv1[1] - pv0[1]) / (2 * h),
                        (pv1[2] - pv0[2]) / (2 * h)};
                worstU = Math.max(worstU, dist(fdu, su));
                worstV = Math.max(worstV, dist(fdv, sv));
            }
        }
        check("曲面 ∂u 解析 vs 差分", worstU < 1.0e-6, "最大偏差 " + worstU);
        check("曲面 ∂v 解析 vs 差分", worstV < 1.0e-6, "最大偏差 " + worstV);
    }

    // ---------------------------------------------------------------- 曲面

    /** 圆柱面：圆沿 z 拉伸，轴在 z 轴上。 */
    private static NurbsSurface cylinder(final double radius, final double height) {
        final NurbsCurve base = NurbsShapes.circle(new double[]{0, 0, 0},
                new double[]{1, 0, 0}, new double[]{0, 1, 0}, radius);
        return NurbsShapes.extrude(base, new double[]{0, 0, 1}, height);
    }

    private static void normalIsUnitAndRadialForCylinder() {
        final double r = 2.0;
        final NurbsSurface s = cylinder(r, 5.0);
        double worstLen = 0.0;
        double worstRadial = 0.0;
        final double[] n = new double[3];
        final double[] p = new double[3];
        for (int a = 0; a <= 20; a++) {
            for (int b = 0; b <= 5; b++) {
                final double u = s.knotsU[s.degreeU]
                        + (s.knotsU[s.knotsU.length - s.degreeU - 1] - s.knotsU[s.degreeU]) * a / 20.0;
                final double v = (double) b / 5.0;
                s.normal(u, v, n);
                s.evaluate(u, v, p);
                worstLen = Math.max(worstLen, Math.abs(len(n) - 1.0));
                // 圆柱面的法线必须是径向：与 z 轴垂直，且与 (x,y) 同向
                final double radialX = p[0];
                final double radialY = p[1];
                final double rl = Math.hypot(radialX, radialY);
                if (rl > 1.0e-9) {
                    final double dot = (n[0] * radialX + n[1] * radialY) / rl;
                    worstRadial = Math.max(worstRadial, Math.abs(Math.abs(dot) - 1.0));
                }
            }
        }
        check("圆柱面法线单位长", worstLen < 1.0e-10, "最大偏差 " + worstLen);
        check("圆柱面法线径向", worstRadial < 1.0e-10, "最大偏差 " + worstRadial);
    }

    private static void coneRadiusLinear() {
        // 圆锥 = 直线轮廓 (r 从 0 到 R) 绕轴回转
        final double bigR = 3.0;
        final double h = 4.0;
        final double[][] profile = {{0, 0}, {bigR, h}};
        final NurbsSurface s = NurbsShapes.revolve(profile, new double[]{1, 1}, 1,
                new double[]{0, 0, 1, 1},
                new double[]{0, 0, 0}, new double[]{0, 0, 1}, new double[]{1, 0, 0});
        double worst = 0.0;
        final double[] p = new double[3];
        for (int a = 0; a <= 24; a++) {
            for (int b = 0; b <= 10; b++) {
                final double u = (double) a / 24.0;
                final double v = (double) b / 10.0;
                s.evaluate(u, v, p);
                final double radius = Math.hypot(p[0], p[1]);
                final double expected = bigR * (p[2] / h);
                worst = Math.max(worst, Math.abs(radius - expected));
            }
        }
        check("圆锥面半径随轴向线性", worst < 1.0e-10, "最大偏差 " + worst);
    }

    private static void torusRevolutionExactness() {
        // 圆环 = 把"距离轴 R、半径 r 的圆"这条轮廓回转
        final double major = 5.0;
        final double minor = 1.5;
        // 轮廓圆心在 (major, 0)，半径 minor，位于 (径向, 轴向) 平面
        final NurbsCurve prof = NurbsShapes.circle(new double[]{major, 0, 0},
                new double[]{1, 0, 0}, new double[]{0, 1, 0}, minor);
        // 轮廓是三维数组，这里只用前两维（径向、轴向）
        final double[][] p2 = new double[prof.controlCount()][2];
        for (int i = 0; i < p2.length; i++) {
            p2[i][0] = prof.control[i][0];
            p2[i][1] = prof.control[i][1];
        }
        final NurbsSurface s = NurbsShapes.revolve(p2, prof.weights, prof.degree, prof.knots,
                new double[]{0, 0, 0}, new double[]{0, 0, 1}, new double[]{1, 0, 0});
        double worst = 0.0;
        final double[] p = new double[3];
        for (int a = 0; a <= 40; a++) {
            for (int b = 0; b <= 20; b++) {
                final double u = (double) a / 40.0;
                final double v = (double) b / 20.0;
                s.evaluate(u, v, p);
                // 圆环面：到"中心圆（半径 major，在 z=0 平面）"的距离恒为 minor
                final double rho = Math.hypot(p[0], p[1]);
                final double d = Math.hypot(rho - major, p[2]);
                worst = Math.max(worst, Math.abs(d - minor));
            }
        }
        check("圆环面到中心圆距离恒定（精确）", worst < 1.0e-10, "最大偏差 " + worst);
    }

    // ---------------------------------------------------------------- 细分

    private static void tessellationRespectsTolerance() {
        final double tol = 0.02;
        for (final double radius : new double[]{0.5, 2.0, 8.0}) {
            final NurbsSurface s = cylinder(radius, 5.0);
            final java.util.List<NurbsShapes.Patch> patches = NurbsShapes.tessellate(s, tol, 64);
            check("细分产生网格 r=" + radius, patches.size() > 4, "面片数 " + patches.size());
            // **真断言**：在每张面片**内部**重采样真实曲面，与面片的双线性插值比较，
            // 最大偏差必须不超过容差（留一点余量：双线性插值比平面近似略宽松）。
            double worst = 0.0;
            final double[] p = new double[3];
            for (final NurbsShapes.Patch patch : patches) {
                for (int a = 1; a <= 3; a++) {
                    for (int b = 1; b <= 3; b++) {
                        final double sParam = a / 4.0;
                        final double tParam = b / 4.0;
                        final double u = patch.u0() + (patch.u1() - patch.u0()) * sParam;
                        final double v = patch.v0() + (patch.v1() - patch.v0()) * tParam;
                        s.evaluate(u, v, p);
                        final double[] approx = patch.bilinear(sParam, tParam);
                        worst = Math.max(worst, dist(p, approx));
                    }
                }
            }
            check("细分偏差 <= 容差 r=" + radius, worst <= tol * 1.5,
                    "实测 " + worst + " 容差 " + tol);
        }
    }

    // ---------------------------------------------------------------- 弧长重参数化

    private static void reparamInverseConsistency() {
        final NurbsSurface s = cylinder(2.0, 5.0);
        final com.ovo.sablestopnow.sketch.nurbs.NurbsReparam rp =
                com.ovo.sablestopnow.sketch.nurbs.NurbsReparam.build(s, 200);
        check("uFromArc(0) == uMin",
                Math.abs(rp.uFromArc(0.0) - s.knotsU[s.degreeU]) < 1.0e-9,
                "得到 " + rp.uFromArc(0.0));
        check("uFromArc(1) == uMax",
                Math.abs(rp.uFromArc(1.0) - s.knotsU[s.knotsU.length - s.degreeU - 1]) < 1.0e-9,
                "得到 " + rp.uFromArc(1.0));
        double worst = 0.0;
        for (int k = 0; k <= 100; k++) {
            final double a = (double) k / 100.0;
            worst = Math.max(worst, Math.abs(rp.arcFromU(rp.uFromArc(a)) - a));
        }
        check("弧长↔参数互逆", worst < 1.0e-6, "最大偏差 " + worst);
        // 总弧长对圆柱侧面：u 方向是一圈周长、v 方向是高度
        check("u 方向总弧长 ≈ 周长", Math.abs(rp.totalArcU() - 2 * Math.PI * 2.0) < 0.05,
                "得到 " + rp.totalArcU() + " 期望 " + (2 * Math.PI * 2.0));
        check("v 方向总弧长 ≈ 高", Math.abs(rp.totalArcV() - 5.0) < 1.0e-6,
                "得到 " + rp.totalArcV());
    }

    /**
     * **重参数化的意义必须可测**：重参数化后，单位参数步长对应的**真实距离**应当更均匀。
     *
     * <p>度量：沿等参线采样一阶导的模长，取 {@code max/min} 之比。越接近 1 越均匀。
     */
    private static void reparamImprovesScaleUniformity() {
        final NurbsSurface s = cylinder(2.0, 5.0);
        final com.ovo.sablestopnow.sketch.nurbs.NurbsReparam rp =
                com.ovo.sablestopnow.sketch.nurbs.NurbsReparam.build(s, 400);
        final double u0 = s.knotsU[s.degreeU];
        final double u1 = s.knotsU[s.knotsU.length - s.degreeU - 1];
        final double h = 1.0e-5;
        double natMin = Double.MAX_VALUE;
        double natMax = 0.0;
        double arcMin = Double.MAX_VALUE;
        double arcMax = 0.0;
        for (int k = 1; k < 100; k++) {
            final double t = (double) k / 100.0;
            final double u = u0 + (u1 - u0) * t;
            final double[] a0 = s.evaluate(u - h, 0.5);
            final double[] a1 = s.evaluate(u + h, 0.5);
            final double nat = dist(a0, a1) / (2 * h) * (u1 - u0);
            natMin = Math.min(natMin, nat);
            natMax = Math.max(natMax, nat);
            final double[] b0 = rp.evaluateByArc(t - h, 0.5, new double[3]);
            final double[] b1 = rp.evaluateByArc(t + h, 0.5, new double[3]);
            final double arc = dist(b0, b1) / (2 * h);
            if (arc > 0.0 && Double.isFinite(arc)) {
                arcMin = Math.min(arcMin, arc);
                arcMax = Math.max(arcMax, arc);
            }
        }
        final double natRatio = natMin <= 0.0 ? Double.MAX_VALUE : natMax / natMin;
        final double arcRatio = arcMin <= 0.0 ? Double.MAX_VALUE : arcMax / arcMin;
        // ⚠ 我原来在这里断言"自然参数一定很不均匀（>1.5）"，**对圆柱不成立**：
        //   有理二次圆的参数化本来就只轻度不均匀（实测 1.171）。
        //   正确的断言分两条：① 重参数化后**接近均匀**（这是它的承诺）；
        //   ② 相比自然参数**有改善**。
        check("弧长重参数化后接近均匀", arcRatio < 1.05, "max/min = " + arcRatio);
        check("比重参数化前更均匀", arcRatio < natRatio, "弧长 " + arcRatio + " vs 自然 " + natRatio);

        // **必要性**要在真正不均匀的曲面上看：刻意给一个非均匀节点向量，
        // 参数在短区间里走得快、长区间里走得慢。这时自然参数明显不能用。
        final NurbsCurve base = NurbsShapes.circle(new double[]{0, 0, 0},
                new double[]{1, 0, 0}, new double[]{0, 1, 0}, 2.0);
        final double[] uneven = {0, 0, 0, 0.02, 0.02, 0.5, 0.5, 0.98, 0.98, 1, 1, 1};
        final NurbsCurve unevenCurve = new NurbsCurve(2, uneven, base.control, base.weights);
        final NurbsSurface unevenSurface = NurbsShapes.extrude(unevenCurve,
                new double[]{0, 0, 1}, 5.0);
        final com.ovo.sablestopnow.sketch.nurbs.NurbsReparam rp2 =
                com.ovo.sablestopnow.sketch.nurbs.NurbsReparam.build(unevenSurface, 400);
        double nMin = Double.MAX_VALUE;
        double nMax = 0.0;
        double aMin2 = Double.MAX_VALUE;
        double aMax2 = 0.0;
        for (int k = 1; k < 100; k++) {
            final double t = (double) k / 100.0;
            final double[] c0 = unevenSurface.evaluate(t - h, 0.5);
            final double[] c1 = unevenSurface.evaluate(t + h, 0.5);
            final double nat = dist(c0, c1) / (2 * h);
            nMin = Math.min(nMin, nat);
            nMax = Math.max(nMax, nat);
            final double[] d0 = rp2.evaluateByArc(t - h, 0.5, new double[3]);
            final double[] d1 = rp2.evaluateByArc(t + h, 0.5, new double[3]);
            final double arc = dist(d0, d1) / (2 * h);
            if (arc > 0.0 && Double.isFinite(arc)) {
                aMin2 = Math.min(aMin2, arc);
                aMax2 = Math.max(aMax2, arc);
            }
        }
        final double nat2 = nMin <= 0.0 ? Double.MAX_VALUE : nMax / nMin;
        final double arc2 = aMin2 <= 0.0 ? Double.MAX_VALUE : aMax2 / aMin2;
        check("非均匀节点向量下自然参数确实不可用", nat2 > 2.0, "max/min = " + nat2);
        check("非均匀节点向量下重参数化救回来", arc2 < nat2 * 0.5,
                "弧长 " + arc2 + " vs 自然 " + nat2);
    }

    // ---------------------------------------------------------------- 通用贴图映射

    /**
     * **"通用贴图"必须有模式**：同一张圆柱，圆柱模式几乎等距，平面模式严重拉长。
     *
     * <p>这就是 CAD 用"映射类型"而不是一个万能公式的原因。断言把这个结论量化。
     */
    private static void mappingModesMatchShapes() {
        final NurbsSurface cyl = cylinder(2.0, 5.0);
        final double[] o = {0, 0, 0};
        final double[] u = {1, 0, 0};
        final double[] v = {0, 1, 0};
        final double[] w = {0, 0, 1};
        final com.ovo.sablestopnow.sketch.nurbs.TextureMapping.Frame f =
                com.ovo.sablestopnow.sketch.nurbs.TextureMapping.Frame.of(o, u, v, w);
        final com.ovo.sablestopnow.sketch.nurbs.NurbsReparam rp =
                com.ovo.sablestopnow.sketch.nurbs.NurbsReparam.build(cyl, 300);

        final double dCyl = com.ovo.sablestopnow.sketch.nurbs.TextureMapping.distortion(cyl,
                com.ovo.sablestopnow.sketch.nurbs.TextureMapping.create(
                        com.ovo.sablestopnow.sketch.nurbs.TextureMapping.Mode.CYLINDRICAL, f, rp),
                60);
        final double dPlanar = com.ovo.sablestopnow.sketch.nurbs.TextureMapping.distortion(cyl,
                com.ovo.sablestopnow.sketch.nurbs.TextureMapping.create(
                        com.ovo.sablestopnow.sketch.nurbs.TextureMapping.Mode.PLANAR, f, rp),
                60);
        final double dSurface = com.ovo.sablestopnow.sketch.nurbs.TextureMapping.distortion(cyl,
                com.ovo.sablestopnow.sketch.nurbs.TextureMapping.create(
                        com.ovo.sablestopnow.sketch.nurbs.TextureMapping.Mode.SURFACE, f, rp),
                60);

        check("圆柱面 + 圆柱模式：几乎等距", dCyl < 1.05, "畸变 " + dCyl);
        check("圆柱面 + 曲面(弧长)模式：几乎等距", dSurface < 1.05, "畸变 " + dSurface);
        check("圆柱面 + 平面模式：严重畸变（证明模式是必要的）", dPlanar > 1.5,
                "畸变 " + dPlanar);

        // 平面片用平面模式应当**完全**等距（畸变 = 1）
        final NurbsSurface flat = NurbsShapes.planePatch(o, u, v, 4.0, 3.0);
        final double dFlat = com.ovo.sablestopnow.sketch.nurbs.TextureMapping.distortion(flat,
                com.ovo.sablestopnow.sketch.nurbs.TextureMapping.create(
                        com.ovo.sablestopnow.sketch.nurbs.TextureMapping.Mode.PLANAR, f, null),
                20);
        check("平面片 + 平面模式：完全等距", Math.abs(dFlat - 1.0) < 1.0e-9, "畸变 " + dFlat);
    }

    // ---------------------------------------------------------------- 工具

    private static double dist(final double[] a, final double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1])
                + (a[2] - b[2]) * (a[2] - b[2]));
    }

    private static double len(final double[] a) {
        return Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
    }

    private static void check(final String what, final boolean ok, final String detail) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what + " : " + detail);
        }
    }
}
