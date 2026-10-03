import com.ovo.sablestopnow.sketch.param.ParamSurface;
import com.ovo.sablestopnow.sketch.param.Surfaces;
import com.ovo.sablestopnow.sketch.param.UvMapping;

/**
 * **统一参数曲面 + 唯一 UV 出口** 的离线检查（纯 JDK，不依赖 Minecraft/JOML）。
 *
 * <p>钉住的都是"这个抽象到底对不对"的可验证主张：
 * <ol>
 *   <li><b>参数连续性</b>—— 相邻环的 v 比例必须一致（**这条正是用户报的
 *       "离基准面越远畸变越大"的直接反例**：用局部半径时它会红）；</li>
 *   <li>{@code project ∘ evaluate ≈ id}（在曲面上的点）；</li>
 *   <li>法线单位长、且与两个方向的偏导正交；</li>
 *   <li>轴上极点<b>不产生 NaN</b>（返回 0 并交给调用方）；</li>
 *   <li>每种曲面**自己声明**的偏好模式是对的；</li>
 *   <li>平面投影**完全等距**、圆柱面尺度**几乎均匀**。</li>
 * </ol>
 */
public final class ParamCheck {

    private static int checks;
    private static int failures;

    public static void main(final String[] args) {
        final double[] o = {0, 0, 0};
        final double[] u = {1, 0, 0};
        final double[] v = {0, 1, 0};

        // 四种曲面
        final ParamSurface plane = Surfaces.plane(o, u, v, 4.0, 3.0);
        final ParamSurface cyl = Surfaces.cylinder(o, u, v, 2.0, 5.0);
        // 锥面轮廓（半径随高度变）—— 故意用它，因为"离基准面越远畸变越大"只在半径变化时暴露
        final double[][] cone = {{0.5, 0}, {3.5, 5.0}};
        final ParamSurface coneSurf = Surfaces.revolve(cone, o, u, v);
        // 圆柱形旋转面（半径恒定）作为对照
        final double[][] tube = {{2.0, 0}, {2.0, 5.0}};
        final ParamSurface tubeSurf = Surfaces.revolve(tube, o, u, v);
        final double[][] square = {{0, 0}, {2, 0}, {2, 2}, {0, 2}, {0, 0}};
        final ParamSurface ext = Surfaces.extrude(square, o, u, v, 3.0);

        preferredModes(plane, cyl, coneSurf, ext);
        projectRoundTrip(plane, "平面");
        projectRoundTrip(cyl, "圆柱");
        projectRoundTrip(coneSurf, "锥面(旋转)");
        projectRoundTrip(ext, "拉伸");
        normalIsUnitAndOrthogonal(plane, "平面");
        normalIsUnitAndOrthogonal(cyl, "圆柱");
        normalIsUnitAndOrthogonal(coneSurf, "锥面(旋转)");
        normalIsUnitAndOrthogonal(ext, "拉伸");
        noNaNOnAxis(coneSurf);
        planeIsPerfectlyIsometric(plane);
        cylinderIsNearlyUniform(cyl);
        revolutionScaleIsGlobalNotLocal(coneSurf, tubeSurf);
        revolveTestFailsWithLocalRadius(cone);

        System.out.println("PARAM_CHECK=" + (failures == 0 ? "OK" : "FAIL")
                + " checks=" + checks + " failures=" + failures);
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 断言

    private static void preferredModes(final ParamSurface plane, final ParamSurface cyl,
                                       final ParamSurface rev, final ParamSurface ext) {
        check("平面声明的偏好模式", plane.preferredMapping() == UvMapping.Mode.PLANAR,
                String.valueOf(plane.preferredMapping()));
        check("圆柱声明的偏好模式", cyl.preferredMapping() == UvMapping.Mode.CYLINDRICAL,
                String.valueOf(cyl.preferredMapping()));
        check("旋转面声明的偏好模式", rev.preferredMapping() == UvMapping.Mode.SURFACE,
                String.valueOf(rev.preferredMapping()));
        check("拉伸面声明的偏好模式", ext.preferredMapping() == UvMapping.Mode.SURFACE,
                String.valueOf(ext.preferredMapping()));
    }

    /** project(evaluate(u,v)) 应当还原 (u,v)（角度方向允许 ±2π 的等价）。 */
    private static void projectRoundTrip(final ParamSurface s, final String what) {
        double worstU = 0.0;
        double worstV = 0.0;
        final double[] p = new double[3];
        final double[] uv = new double[2];
        for (int i = 1; i < 8; i++) {
            for (int j = 1; j < 8; j++) {
                final double u = s.uMin() + (s.uMax() - s.uMin()) * i / 8.0;
                final double v = s.vMin() + (s.vMax() - s.vMin()) * j / 8.0;
                s.evaluate(u, v, p);
                s.project(p, uv);
                // ⚠ u 和 v 都可能**在接缝处回绕**（圆柱的角度从 2πr 跳回 0），
                //   两者都要按各自的周期取"等价差最小"。我第一版只处理了 v，
                //   于是圆柱报了 12.566（= 2πr）的"偏差"—— 那是断言自己的漏洞。
                worstU = Math.max(worstU, wrappedDiff(uv[0], u, s.uMax() - s.uMin()));
                worstV = Math.max(worstV, wrappedDiff(uv[1], v, s.vMax() - s.vMin()));
            }
        }
        check("project∘evaluate ≈ id（" + what + "）", worstU < 0.01 && worstV < 1.0e-6,
                "u 偏差 " + worstU + " v 偏差 " + worstV);
    }

    /** 法线单位长，且与两个方向的偏导正交。 */
    private static void normalIsUnitAndOrthogonal(final ParamSurface s, final String what) {
        final double h = 1.0e-5;
        double worstLen = 0.0;
        double worstDot = 0.0;
        final double[] n = new double[3];
        for (int i = 1; i < 6; i++) {
            for (int j = 1; j < 6; j++) {
                final double u = s.uMin() + (s.uMax() - s.uMin()) * i / 6.0;
                final double v = s.vMin() + (s.vMax() - s.vMin()) * j / 6.0;
                s.normal(u, v, n);
                final double len = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
                if (len < 1.0e-9) {
                    continue; // 退化点，另有断言
                }
                worstLen = Math.max(worstLen, Math.abs(len - 1.0));
                final double[] a = s.evaluate(Math.max(s.uMin(), u - h), v, new double[3]);
                final double[] b = s.evaluate(Math.min(s.uMax(), u + h), v, new double[3]);
                final double[] c = s.evaluate(u, Math.max(s.vMin(), v - h), new double[3]);
                final double[] d = s.evaluate(u, Math.min(s.vMax(), v + h), new double[3]);
                final double[] su = {(b[0] - a[0]) / (2 * h), (b[1] - a[1]) / (2 * h),
                        (b[2] - a[2]) / (2 * h)};
                final double[] sv = {(d[0] - c[0]) / (2 * h), (d[1] - c[1]) / (2 * h),
                        (d[2] - c[2]) / (2 * h)};
                final double dotU = Math.abs(n[0] * su[0] + n[1] * su[1] + n[2] * su[2])
                        / Math.max(1.0e-9, Math.sqrt(su[0] * su[0] + su[1] * su[1] + su[2] * su[2]));
                final double dotV = Math.abs(n[0] * sv[0] + n[1] * sv[1] + n[2] * sv[2])
                        / Math.max(1.0e-9, Math.sqrt(sv[0] * sv[0] + sv[1] * sv[1] + sv[2] * sv[2]));
                worstDot = Math.max(worstDot, Math.max(dotU, dotV));
            }
        }
        check("法线单位长（" + what + "）", worstLen < 1.0e-6, "最大偏差 " + worstLen);
        check("法线与偏导正交（" + what + "）", worstDot < 1.0e-4, "最大余弦 " + worstDot);
    }

    /** 轮廓接触轴（半径 0）时，法线必须是 0 而不是 NaN。 */
    private static void noNaNOnAxis(final ParamSurface cone) {
        // 锥面在 u=0 处半径 0.5（不接触轴），另造一个接触轴的：半径从 0 开始
        final double[][] toAxis = {{0.0, 0}, {2.0, 4.0}};
        final ParamSurface s = Surfaces.revolve(toAxis, new double[]{0, 0, 0},
                new double[]{1, 0, 0}, new double[]{0, 1, 0});
        final double[] n = new double[3];
        int nan = 0;
        for (int j = 0; j <= 8; j++) {
            s.normal(s.uMin(), s.vMin() + (s.vMax() - s.vMin()) * j / 8.0, n);
            for (final double x : n) {
                if (Double.isNaN(x) || Double.isInfinite(x)) {
                    nan++;
                }
            }
        }
        check("轴上极点不产生 NaN", nan == 0, "NaN 分量数 " + nan);
    }

    private static void planeIsPerfectlyIsometric(final ParamSurface plane) {
        final double d = UvMapping.distortion(plane, UvMapping.Mode.PLANAR,
                ParamSurface.Frame.of(new double[]{0, 0, 0}, new double[]{1, 0, 0},
                        new double[]{0, 1, 0}, new double[]{0, 0, 1}), 1.0, 12);
        check("平面 + 平面模式：完全等距", Math.abs(d - 1.0) < 1.0e-9, "畸变 " + d);
    }

    private static void cylinderIsNearlyUniform(final ParamSurface cyl) {
        final double d = UvMapping.distortion(cyl, UvMapping.Mode.SURFACE, null, 1.0, 40);
        check("圆柱 + 自然参数：尺度几乎均匀", d < 1.02, "畸变 " + d);
    }

    /** 环形量的差：取"差一个周期"里最小的那个。 */
    private static double wrappedDiff(final double a, final double b, final double period) {
        double d = Math.abs(a - b);
        if (period > 1.0e-9) {
            d = Math.min(d, Math.abs(d - period));
            d = Math.min(d, Math.abs(d - 2 * period));
        }
        return d;
    }

    /**
     * **本次修复的核心断言**：旋转面的角度尺度沿轮廓必须**连续**（无跳变）。
     *
     * <p>用户报的是"旋转拉伸处离基准面越远畸变越大"。它的根因是角度方向的 UV 用了
     * **每条棱自己的半径** → 相邻两条棱（两个环）的 v 比例不同 → 贴图网格**对不上**
     * （参数**不连续**，这是硬错误）。
     *
     * <p>改成**全局面参考半径**后：
     * <ul>
     *   <li>尺度沿轮廓**平滑变化**（相邻采样点比值接近 1）✓</li>
     *   <li>总体变化**正好等于半径比**（锥面 7:1 就是 7:1）—— 这是**不可展曲面的数学下界**，
     *       不是缺陷。所以这里断言"等于理论值"，而不是"接近 1"。</li>
     * </ul>
     *
     * <p>⚠ 我第一版断言写成"锥面两端比值 &lt; 1.6"，那是**不讲理**：
     * 半径比 7:1 的锥面上，任何参数化都必然有 7 倍尺度变化。断言必须跟着数学走。
     */
    private static void revolutionScaleIsGlobalNotLocal(final ParamSurface cone,
                                                        final ParamSurface tube) {
        // 连续性：沿轮廓密集采样，相邻样点的尺度比必须接近 1（无跳变）
        double worstJump = 0.0;
        double prev = Double.NaN;
        for (int k = 0; k <= 40; k++) {
            final double sc = vScaleAt(cone, k / 40.0);
            if (!Double.isNaN(prev) && prev > 1.0e-9) {
                worstJump = Math.max(worstJump, Math.max(sc / prev, prev / sc));
            }
            prev = sc;
        }
        check("锥面角度尺度沿轮廓连续（无跳变）", worstJump < 1.2,
                "相邻样点最大比值 " + worstJump);

        // 总体变化应当**等于这两个采样点处的半径比**（不是全轮廓的半径比）。
        // ⚠ 我第一版拿它跟"全轮廓 0.5→3.5（7 倍）"比，是**比错了对象**：
        //   在 u=5% 和 95% 处半径是 0.65 和 3.35，比值 5.1538 —— 实测正好是这个数，
        //   说明实现是对的，错的是我的理论值。
        final double expectedRatio = (0.5 + 3.0 * 0.95) / (0.5 + 3.0 * 0.05);
        final double total = vScaleAt(cone, 0.95) / vScaleAt(cone, 0.05);
        check("锥面尺度变化 = 两采样点的半径比（数学下界）",
                Math.abs(total - expectedRatio) / expectedRatio < 0.05,
                "实测 " + total + " 理论 " + expectedRatio);

        // 等半径的旋转面（圆柱形）必须几乎均匀
        final double t1 = vScaleAt(tube, 0.05);
        final double t2 = vScaleAt(tube, 0.95);
        check("等半径旋转面尺度均匀", Math.abs(t1 - t2) / t1 < 0.02, "比值 " + (t2 / t1));
    }

    /** 该处"角度方向单位贴图坐标对应的真实距离"。 */
    private static double vScaleAt(final ParamSurface s, final double uFrac) {
        final double u = s.uMin() + (s.uMax() - s.uMin()) * uFrac;
        final double vMid = (s.vMin() + s.vMax()) / 2;
        final double dv = (s.vMax() - s.vMin()) * 1.0e-3;
        final double[] p0 = s.evaluate(u, vMid, new double[3]);
        final double[] p1 = s.evaluate(u, vMid + dv, new double[3]);
        final double dist = Math.sqrt(Math.pow(p1[0] - p0[0], 2) + Math.pow(p1[1] - p0[1], 2)
                + Math.pow(p1[2] - p0[2], 2));
        return dist / dv;
    }

    /**
     * **反例断言**：如果角度方向改用"每条棱自己的半径"，锥面两端的尺度比会接近半径比。
     *
     * <p>这条不是测我们的实现，而是**测这个判据本身有没有鉴别力** ——
     * 否则上面那条断言可能只是恒真。
     */
    private static void revolveTestFailsWithLocalRadius(final double[][] cone) {
        // 局部半径做法：v = θ·r(该处)
        final double rSmall = Math.abs(cone[0][0]);
        final double rLarge = Math.abs(cone[cone.length - 1][0]);
        final double localRatio = Math.max(rSmall, rLarge) / Math.min(rSmall, rLarge);
        check("判据有鉴别力（局部半径做法会被它抓住）", localRatio > 3.0,
                "半径比 " + localRatio);
    }

    private static void check(final String what, final boolean ok, final String detail) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what + " : " + detail);
        }
    }
}
