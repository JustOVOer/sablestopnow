import com.ovo.sablestopnow.sketch.ConvexDecomposition;
import com.ovo.sablestopnow.sketch.Extrude;
import com.ovo.sablestopnow.sketch.Revolve;
import com.ovo.sablestopnow.sketch.Sketch;
import com.ovo.sablestopnow.sketch.SketchPlane;
import com.ovo.sablestopnow.sketch.SketchProfiles;
import com.ovo.sablestopnow.sketch.Solid;
import com.ovo.sablestopnow.sketch.SolidMesh;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * **凸分解的离线断言**（纯 JDK + JOML，不依赖 Minecraft）✓。
 *
 * <p>要钉住的事，按重要性排：
 * <ol>
 *   <li><b>孔必须还是空的</b> ✓✓ —— 这是本次改动的**目的** ✓。
 *       用孔里的点去问"有没有被任何凸块覆盖"是最直接的判据 ✓；</li>
 *   <li><b>凸的形状不许变差</b> ✓ —— 圆/矩形必须仍然**恰好 1 块** ✗ 否则就是把
 *       已经验证过的行为改坏了 ✗（块数 = 碰撞体数 ✓ 直接进求解器 ✓）；</li>
 *   <li><b>面积守恒</b> ✓ —— 所有凸块面积之和 == 区域的净面积 ✓（外环减孔 ✓）。
 *       它同时挡住"分解出重叠"和"分解漏掉一块" ✗ 两种错 ✓；</li>
 *   <li><b>每块都是凸的</b> ✓ —— 用工具自己写的判据再验一遍 ✗（不信任被测代码自己的判断 ✓）；</li>
 *   <li><b>块数受得住</b> ✓ —— 块数就是碰撞体数 ✓ 必须 ≤ 预算 ✓
 *       （包括"为了压块数而降采样"这条自适应的结果 ✓）；</li>
 *   <li><b>凸块不超出网格包围盒</b> ✓ —— 碰撞体比画面胖是玩家最能直接看出来的错 ✗。</li>
 * </ol>
 *
 * <p>用法：{@code java ConvexDecompCheck}（无需参数 ✓）；全部通过时最后一行是
 * {@code CONVEX_DECOMP_CHECK=OK} ✓。
 *
 * <p>⚠ 这个工具**真的抓到过两个 bug** ✗✓（都只在离线可见 ✓）：
 * 顶点哈希碰撞（圆被拆成 11 块重叠多边形 ✗）、以及贪心顺序导致的圆环 48 块 ✗。
 * 改动分解算法后**务必重跑** ✓。
 */
public final class ConvexDecompCheck {

    private static int passed;
    private static int failed;

    private ConvexDecompCheck() {
    }

    public static void main(final String[] args) {
        // ---------- 拉伸：凸的形状（不许变差 ✓）----------
        final Sketch rect = new Sketch(UUID.randomUUID(), UUID.randomUUID(), "Rect",
                SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        rect.addLine(-2.0, -1.0, 2.0, -1.0);
        rect.addLine(2.0, -1.0, 2.0, 1.0);
        rect.addLine(2.0, 1.0, -2.0, 1.0);
        rect.addLine(-2.0, 1.0, -2.0, -1.0);
        expectPieces("拉伸 矩形（凸）", rect, Extrude.Params.symmetric(2.0), 1, 1, false);

        final Sketch circle = new Sketch(UUID.randomUUID(), UUID.randomUUID(), "Circle",
                SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        circle.addCircle(0.0, 0.0, 3.0);
        expectPieces("拉伸 圆（凸）", circle, Extrude.Params.symmetric(2.0), 1, 1, false);

        // ---------- 拉伸：L 形（凹，无孔）----------
        final Sketch ell = new Sketch(UUID.randomUUID(), UUID.randomUUID(), "L",
                SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        ell.addLine(0.0, 0.0, 4.0, 0.0);
        ell.addLine(4.0, 0.0, 4.0, 1.0);
        ell.addLine(4.0, 1.0, 1.0, 1.0);
        ell.addLine(1.0, 1.0, 1.0, 4.0);
        ell.addLine(1.0, 4.0, 0.0, 4.0);
        ell.addLine(0.0, 4.0, 0.0, 0.0);
        expectPieces("拉伸 L 形（凹）", ell, Extrude.Params.symmetric(2.0), 2, 2, false);

        // ---------- 拉伸：圆环 / 窗框（带孔 ✓✓ 本次的重点）----------
        final Sketch annulus = new Sketch(UUID.randomUUID(), UUID.randomUUID(), "Annulus",
                SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        annulus.addCircle(0.0, 0.0, 5.0);
        annulus.addCircle(0.0, 0.0, 2.5);
        expectPieces("拉伸 圆环（带孔）", annulus, Extrude.Params.symmetric(2.0), 2, 24, true);

        final Sketch frame = new Sketch(UUID.randomUUID(), UUID.randomUUID(), "Frame",
                SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        frame.addLine(-3.0, -2.0, 3.0, -2.0);
        frame.addLine(3.0, -2.0, 3.0, 2.0);
        frame.addLine(3.0, 2.0, -3.0, 2.0);
        frame.addLine(-3.0, 2.0, -3.0, -2.0);
        frame.addLine(-1.5, -1.0, 1.5, -1.0);
        frame.addLine(1.5, -1.0, 1.5, 1.0);
        frame.addLine(1.5, 1.0, -1.5, 1.0);
        frame.addLine(-1.5, 1.0, -1.5, -1.0);
        expectPieces("拉伸 窗框（带孔）", frame, Extrude.Params.symmetric(2.0), 4, 12, false);

        // ---------- 拉伸：**两个分离的圆**（多区域 ✓ 以前会被一个凸包架起"隐形桥" ✗）----------
        final Sketch twoCircles = new Sketch(UUID.randomUUID(), UUID.randomUUID(), "TwoCircles",
                SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        twoCircles.addCircle(-4.0, 0.0, 1.5);
        twoCircles.addCircle(4.0, 0.0, 1.5);
        expectPieces("拉伸 两个分离的圆", twoCircles, Extrude.Params.symmetric(2.0), 2, 4, false);

        // ---------- 回转：球（贴轴 ⇒ 整圈仍是凸的 ✓ 不许变差 ✓）----------
        final Sketch sphere = new Sketch(UUID.randomUUID(), UUID.randomUUID(), "Sphere",
                SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        sphere.addArc(0.0, 0.0, 1.0, 0.0, Math.PI);
        sphere.addLine(-1.0, 0.0, 1.0, 0.0);
        expectPieces("回转 球（贴轴）", sphere, new Revolve.Params(0.0, 0.0, 1.0, 0.0, 360.0, 1),
                1, 1, false);

        // ---------- 回转：圆环（离轴 ⇒ 必须分扇区 ✓ 且轮毂必须空 ✓✓）----------
        final Sketch torus = new Sketch(UUID.randomUUID(), UUID.randomUUID(), "Torus",
                SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        torus.addCircle(0.0, 6.0, 1.5);
        expectPieces("回转 圆环（离轴）", torus, new Revolve.Params(0.0, 0.0, 1.0, 0.0, 360.0, 1),
                2, 24, false);

        // ================= 2D 断言（孔 / 面积 / 凸性）=================
        report2D("圆环（48 段）", annulus, SketchProfiles.Sampling.RENDER,
                new double[][] {{0.0, 0.0}, {0.0, 1.0}, {0.0, -2.0}});
        report2D("圆环（8 段，降采样后）", annulus, SketchProfiles.Sampling.COLLISION,
                new double[][] {{0.0, 0.0}, {0.0, 1.0}, {0.0, -2.0}});
        report2D("窗框（带孔）", frame, SketchProfiles.Sampling.RENDER,
                new double[][] {{0.0, 0.0}, {0.0, 0.9}, {1.2, 0.5}});
        report2D("L 形（凹）", ell, SketchProfiles.Sampling.RENDER,
                new double[][] {{3.0, 3.0}, {2.0, 2.0}});
        report2D("圆（凸）", circle, SketchProfiles.Sampling.RENDER, new double[][] {});
        report2D("矩形（凸）", rect, SketchProfiles.Sampling.RENDER, new double[][] {});
        // 两个分离的圆之间**必须什么都没有** ✓ —— 单个凸包会在这里架出一座"隐形的桥" ✗✓
        report2D("两个分离的圆", twoCircles, SketchProfiles.Sampling.RENDER,
                new double[][] {{0.0, 0.0}, {2.4, 0.0}, {-2.4, 0.0}});

        // ================= 3D：孔在空间里也必须是空的 =================
        // 圆环：材料在 r ∈ [4.5, 7.5] ✓ ⇒ 所有凸块的点到轴的距离必须 >= 4.5 - 弓高容差 ✓
        final ConvexDecomposition.Result torusResult = decompose(torus,
                new Revolve.Params(0.0, 0.0, 1.0, 0.0, 360.0, 1));
        check("3D 圆环：分解出多于 1 块（否则整圈凸包会把孔填掉 ✗）",
                torusResult.pieces().size() > 1);
        final double torusMinR = minRadiusFromUAxis(torusResult.pieces());
        check(String.format(Locale.ROOT,
                        "3D 圆环：凸块最近只到 r=%.3f（材料内缘 4.5，容差 0.2）", torusMinR),
                torusMinR >= 4.3);

        // 对照：**旧的单个凸包**一定会伸进孔里 ✗ —— 这就是要修的现象 ✓
        // 证法：凸包必含任意两点的**中点** ✓ ⇒ 只要存在一对中点半径 < 材料内缘（4.5），
        //       旧做法就**把孔填掉了** ✓✓。
        final double[] torusMesh = allMeshPoints(torus,
                new Revolve.Params(0.0, 0.0, 1.0, 0.0, 360.0, 1));
        final double hullProxyMinR = minMidpointRadius(torusMesh);
        check(String.format(Locale.ROOT,
                        "对照：旧单个凸包里必有中点半径 %.3f < 4.5（⇒ 孔被填 ✗）", hullProxyMinR),
                hullProxyMinR < 4.5);

        // 球：仍然 1 块 ✓ 且点都贴在外壳上 ✓
        check("3D 球：仍然是 1 块（贴轴不许被拆 ✗）",
                decompose(sphere, new Revolve.Params(0.0, 0.0, 1.0, 0.0, 360.0, 1))
                        .pieces().size() == 1);

        // 凸块不许超出网格包围盒 ✓
        check("3D：凸块都在网格包围盒内（碰撞不许比画面胖 ✗）",
                insideMeshBounds(annulus, Extrude.Params.symmetric(2.0)));

        // ================= 预算开关（用户会拿它做"开/关对比" ✓）=================
        final ConvexDecomposition.Result tightAnnulus = decompose(annulus,
                Extrude.Params.symmetric(2.0), 1);
        check("预算=1：带孔形状放弃分解并报告 overBudget（= 关闭凸分解的开关 ✓）",
                tightAnnulus.pieces().isEmpty() && tightAnnulus.overBudget());
        final ConvexDecomposition.Result tightCircle = decompose(circle,
                Extrude.Params.symmetric(2.0), 1);
        check("预算=1：凸形状仍然分得出来（1 块 ✓ 不受影响 ✓）",
                tightCircle.pieces().size() == 1 && !tightCircle.overBudget());

        // ================= JNI 负载 / 凸包构造成本 =================
        int torusPoints = 0;
        for (final double[] pts : torusResult.pieces()) {
            torusPoints += pts.length / 3;
        }
        check(String.format(Locale.ROOT,
                        "回转圆环：总点数=%d <= 4000（JNI 负载与凸包构造成本 ✓）", torusPoints),
                torusPoints <= 4000 && torusPoints > 0);

        // ---------- 用户实测形状：**半径 3 / 壁厚 0.5 / 长 12 的圆管**（2026-10-03 ✓）----------
        // 它的三条症状（质量不足 / 进不去管口 / 悬停 2 格 / 落地插进地下 9 格）里，
        // "进不去管口"直接由【内凹 → 孔径】决定 ✓ ⇒ 这里把这张表算出来 ✓。
        final Sketch tube = new Sketch(UUID.randomUUID(), UUID.randomUUID(), "Tube",
                SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        tube.addCircle(0.0, 0.0, 3.0);
        tube.addCircle(0.0, 0.0, 2.5);
        final Extrude.Params tubeParams = Extrude.Params.oneSided(12.0);
        final ConvexDecomposition.Result tubeResult = decompose(tube, tubeParams);
        check(String.format(Locale.ROOT, "圆管（r3 / 壁厚 0.5 / 长 12）⇒ 块数=%d（预算 %d 之内 ✓）",
                        tubeResult.pieces().size(), ConvexDecomposition.DEFAULT_MAX_PIECES),
                !tubeResult.pieces().isEmpty()
                        && tubeResult.pieces().size() <= ConvexDecomposition.DEFAULT_MAX_PIECES);
        report2D("圆管（带孔）", tube, SketchProfiles.Sampling.RENDER,
                new double[][] {{0.0, 0.0}, {1.0, 0.0}, {2.0, 0.0}, {2.4, 0.0}});
        clearanceTable(tube, tubeResult);

        System.out.println();
        if (failed == 0) {
            System.out.println("CONVEX_DECOMP_CHECK=OK  (" + passed + " 项)");
        } else {
            System.out.println("CONVEX_DECOMP_CHECK=FAIL  (" + failed + " 项失败 / 共 "
                    + (passed + failed) + " 项)");
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 断言工具

    private static void check(final String what, final boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + what);
        } else {
            failed++;
            System.out.println("FAIL   " + what);
        }
    }

    private static void expectPieces(final String what, final Sketch sketch,
                                     final Extrude.Params params,
                                     final int min, final int max, final boolean coarsened) {
        report(what, decompose(sketch, params), min, max, coarsened);
    }

    private static void expectPieces(final String what, final Sketch sketch,
                                     final Revolve.Params params,
                                     final int min, final int max, final boolean coarsened) {
        report(what, decompose(sketch, params), min, max, coarsened);
    }

    private static void report(final String what, final ConvexDecomposition.Result r,
                               final int min, final int max, final boolean coarsened) {
        final int n = r.pieces().size();
        check(String.format(Locale.ROOT, "%s ⇒ 块数=%d（期望 %d..%d）采样=%d段 降采样=%s",
                what, n, min, max, r.segments(), r.coarsened()),
                n >= min && n <= max && r.coarsened() == coarsened);
    }

    private static ConvexDecomposition.Result decompose(final Sketch sketch,
                                                        final Extrude.Params params) {
        return decompose(sketch, params, ConvexDecomposition.DEFAULT_MAX_PIECES);
    }

    private static ConvexDecomposition.Result decompose(final Sketch sketch,
                                                        final Extrude.Params params,
                                                        final int maxPieces) {
        final Solid solid = Solid.create(sketch.body(), sketch.id(), 1, params);
        return ConvexDecomposition.decompose(solid, sketch, maxPieces);
    }

    private static ConvexDecomposition.Result decompose(final Sketch sketch,
                                                        final Revolve.Params params) {
        final Solid solid = Solid.createRevolution(sketch.body(), sketch.id(), 1, params);
        return ConvexDecomposition.decompose(solid, sketch, ConvexDecomposition.DEFAULT_MAX_PIECES);
    }

    /** 单块的全部网格点（= 旧行为的输入 ✓）。 */
    private static double[] allMeshPoints(final Sketch sketch, final Revolve.Params params) {
        final SolidMesh.Mesh mesh = Revolve.mesh(sketch, params);
        final double[] out = new double[mesh.vertices().size() * 3];
        int w = 0;
        for (final SolidMesh.Vertex v : mesh.vertices()) {
            out[w++] = v.position().x;
            out[w++] = v.position().y;
            out[w++] = v.position().z;
        }
        return out;
    }

    private static double minRadiusFromUAxis(final List<double[]> pieces) {
        double min = Double.MAX_VALUE;
        for (final double[] pts : pieces) {
            for (int i = 0; i + 2 < pts.length; i += 3) {
                // 轴 = u 轴（FRONT 平面的 axisU ✓ 即 x 轴 ✓）⇒ 半径 = sqrt(y²+z²) ✓
                min = Math.min(min, Math.hypot(pts[i + 1], pts[i + 2]));
            }
        }
        return min == Double.MAX_VALUE ? 0.0 : min;
    }

    /** 所有点对**中点**的最小半径 ✓（凸包必含中点 ✓ ⇒ 这是"凸包伸进孔里"的证据 ✓）。 */
    private static double minMidpointRadius(final double[] pts) {
        double min = Double.MAX_VALUE;
        final int n = pts.length / 3;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                final double my = (pts[i * 3 + 1] + pts[j * 3 + 1]) * 0.5;
                final double mz = (pts[i * 3 + 2] + pts[j * 3 + 2]) * 0.5;
                min = Math.min(min, Math.hypot(my, mz));
            }
        }
        return min == Double.MAX_VALUE ? 0.0 : min;
    }

    private static boolean insideMeshBounds(final Sketch sketch, final Extrude.Params params) {
        final List<double[]> pieces = decompose(sketch, params).pieces();
        final SolidMesh.Mesh mesh = SolidMesh.build(sketch, params);
        final double[] b = SolidMesh.bounds(mesh);
        if (b == null) {
            return false;
        }
        final double slack = 1.0e-6;
        for (final double[] pts : pieces) {
            for (int i = 0; i + 2 < pts.length; i += 3) {
                for (int a = 0; a < 3; a++) {
                    if (pts[i + a] < b[a] - slack || pts[i + a] > b[a + 3] + slack) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- 2D 报告

    /**
     * 2D 三项硬断言：面积守恒 ✓、每块凸 ✓、**给定点不被覆盖** ✓。
     *
     * @param outside 这些点**必须落在材料之外**（孔心 / 凹口 ✓）
     */
    private static void report2D(final String name, final Sketch sketch,
                                 final SketchProfiles.Sampling sampling,
                                 final double[][] outside) {
        final List<SketchProfiles.Region> regions = SketchProfiles.nest(
                SketchProfiles.findLoops(sketch, SketchProfiles.DEFAULT_WELD_TOLERANCE, sampling));
        double netArea = 0.0;
        double pieceArea = 0.0;
        int pieces = 0;
        boolean allConvex = true;
        final List<double[][]> all = new ArrayList<>();
        for (final SketchProfiles.Region region : regions) {
            if (!Extrude.extrudable(region)) {
                continue;
            }
            netArea += SketchProfiles.netArea(region);
            final List<double[][]> parts = ConvexDecomposition.decomposeRegion(region);
            pieces += parts.size();
            all.addAll(parts);
            for (final double[][] poly : parts) {
                pieceArea += Math.abs(shoelace(poly));
                if (!convex(poly)) {
                    allConvex = false;
                }
            }
        }
        check(String.format(Locale.ROOT, "2D %s：面积守恒 净=%.4f 块和=%.4f", name, netArea, pieceArea),
                Math.abs(netArea - pieceArea) < 1.0e-6 * Math.max(1.0, netArea));
        check("2D " + name + " 每块都凸（工具自己的判据 ✓）", allConvex);
        for (final double[] p : outside) {
            boolean covered = false;
            for (final double[][] poly : all) {
                if (containsConvex(poly, p[0], p[1])) {
                    covered = true;
                    break;
                }
            }
            check(String.format(Locale.ROOT,
                            "2D %s：点(%.2f,%.2f) 必须【不被覆盖】（孔/凹口 ✓）", name, p[0], p[1]),
                    !covered);
        }
        System.out.println(String.format(Locale.ROOT,
                "       %s: 块数=%d 净面积=%.4f  累计拒绝 面积=%d 凸=%d 自交=%d",
                name, pieces, netArea,
                ConvexDecomposition.rejectedArea, ConvexDecomposition.rejectedConvex,
                ConvexDecomposition.rejectedNonSimple));
    }

    /**
     * **内凹值 → 管口孔径** 对照表 ✓（用的是**真代码** {@link ConvexDecomposition#insetPerAxis} ✓
     * 不是复制品 ✓ —— 所以这张表跟游戏里算出来的必须是同一个数 ✓）。
     *
     * <p>为什么这张表重要：用户的验收标准就是"别的结构能不能进管口"✓，
     * 而**孔径 = 2 × 凸块到轴线的最小距离** ✓。内凹每面收一格 ⇒ 孔的**半径**少一格 ✓
     * （不是少两格 —— 孔径是直径 ✓；但结构**两边**都要让路 ⇒ 实际可用宽度还要再减它自己的内凹 ✓）。
     */
    private static void clearanceTable(final Sketch sketch, final ConvexDecomposition.Result r) {
        final org.joml.Vector3dc axisU = sketch.plane().axisU();
        final org.joml.Vector3dc axisV = sketch.plane().axisV();
        // 所有块共用的中心 —— 与运行时 commonCentre 同一套算法 ✓
        double sx = 0.0;
        double sy = 0.0;
        double sz = 0.0;
        long n = 0;
        for (final double[] pts : r.pieces()) {
            for (int i = 0; i + 2 < pts.length; i += 3) {
                sx += pts[i];
                sy += pts[i + 1];
                sz += pts[i + 2];
                n++;
            }
        }
        final double[] centre = {sx / n, sy / n, sz / n};
        System.out.println("       ── 内凹 → 管口孔径（真代码 insetPerAxis ✓）──");
        double r0 = -1.0;
        double rHalf = -1.0;
        for (final double inset : new double[] {0.0, 0.25, 0.5, 0.9}) {
            double minR = Double.MAX_VALUE;
            for (final double[] pts : r.pieces()) {
                final double[] moved = ConvexDecomposition.insetPerAxis(pts, centre, inset);
                for (int i = 0; i + 2 < moved.length; i += 3) {
                    final Vector3d p = new Vector3d(moved[i], moved[i + 1], moved[i + 2]);
                    minR = Math.min(minR, Math.hypot(p.dot(axisU), p.dot(axisV)));
                }
            }
            if (inset == 0.0) {
                r0 = minR;
            }
            if (inset == 0.5) {
                rHalf = minR;
            }
            System.out.println(String.format(Locale.ROOT,
                    "       inset=%.2f  孔半径=%.3f  孔径=%.3f 格", inset, minR, 2.0 * minR));
        }
        // 两条硬断言。
        // ⚠ 第一条是**实测纠正过**的 ✗→✓：按轴收对**斜向**的面收得更多 ✓
        //   （轴向正好 inset ✓、45° 方向 inset·√2 ✗）⇒ 圆孔会被收成圆角方形 ✓。
        // ⚠ 第二条是**管口被收死那次事故**之后加的硬不变式 ✓✓：
        //   内凹**永不允许吃掉超过一半**（否则薄壁块会塌成纸片 ⇒ 管口朝下就陷 ✗）。
        check(String.format(Locale.ROOT, "内凹 0 时孔半径 ≈ 材料内缘 2.5（实测 %.3f ✓）", r0),
                r0 > 2.4 && r0 <= 2.5 + 1.0e-6);
        final double drop = r0 - rHalf;
        check(String.format(Locale.ROOT,
                        "内凹的收缩量不超过 inset·√2（实测 drop=%.3f ≤ %.3f ✓）",
                        drop, 0.5 * Math.sqrt(2.0)),
                drop >= -1.0e-6 && drop <= 0.5 * Math.sqrt(2.0) + 1.0e-6);
        // ★ 硬不变式：逐块逐轴，内凹后该轴跨度不得小于原来的一半 ✓（防"薄块被收成纸片"✗）
        double worst = 1.0;
        for (final double[] pts : r.pieces()) {
            final double[] moved = ConvexDecomposition.insetPerAxis(pts, centre, 0.9);
            for (int a = 0; a < 3; a++) {
                double lo = Double.MAX_VALUE;
                double hi = -Double.MAX_VALUE;
                double lo2 = Double.MAX_VALUE;
                double hi2 = -Double.MAX_VALUE;
                for (int i = a; i < pts.length; i += 3) {
                    lo = Math.min(lo, pts[i]);
                    hi = Math.max(hi, pts[i]);
                    lo2 = Math.min(lo2, moved[i]);
                    hi2 = Math.max(hi2, moved[i]);
                }
                final double before = hi - lo;
                final double after = hi2 - lo2;
                if (before > 1.0e-9) {
                    worst = Math.min(worst, after / before);
                }
            }
        }
        check(String.format(Locale.ROOT,
                        "内凹 0.9（极端值）下，最坏的一块每轴仍保留 %.0f%% 跨度（≥50%% ✓）",
                        worst * 100.0),
                worst >= 0.5 - 1.0e-9);
    }

    /** 逆时针多边形面积（鞋带 ✓）。 */
    private static double shoelace(final double[][] poly) {
        double sum = 0.0;
        for (int i = 0; i < poly.length; i++) {
            final double[] p = poly[i];
            final double[] q = poly[(i + 1) % poly.length];
            sum += p[0] * q[1] - q[0] * p[1];
        }
        return sum * 0.5;
    }

    /** 独立的凸性判据 ✓（所有顶点左转 ✓）。 */
    private static boolean convex(final double[][] poly) {
        final int n = poly.length;
        for (int i = 0; i < n; i++) {
            final double[] a = poly[i];
            final double[] b = poly[(i + 1) % n];
            final double[] c = poly[(i + 2) % n];
            final double t = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
            if (t < -1.0e-9) {
                return false;
            }
        }
        return true;
    }

    /** 点是否落在凸多边形内（对每条边同号 ✓ —— 多边形已保证是凸的 ✓）。 */
    private static boolean containsConvex(final double[][] poly, final double x, final double y) {
        final int n = poly.length;
        for (int i = 0; i < n; i++) {
            final double[] a = poly[i];
            final double[] b = poly[(i + 1) % n];
            final double cross = (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
            if (cross < -1.0e-9) {
                return false;
            }
        }
        return true;
    }
}
