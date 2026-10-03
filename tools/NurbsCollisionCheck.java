import com.ovo.sablestopnow.sketch.nurbs.SweptSolid;
import com.ovo.sablestopnow.sketch.nurbs.Voxelizer;

import java.util.List;

/**
 * NURBS → **体素碰撞体** 的离线检查（纯 JDK，不依赖 Minecraft/JOML）。
 *
 * <p>钉住的主张：
 * <ol>
 *   <li><b>体积收敛</b>：体素体积随子格变细收敛到解析体积；</li>
 *   <li><b>误差有上界</b>：实测对称差体积 ≤ {@code Area·(cell/subdiv)·√3/2}；
 *       而且这个上界**不空泛**（实测值至少是上界的某个比例）；</li>
 *   <li><b>保守准则绝不漏判</b>：体素体积 ≥ 真实体积（物理上不会穿模）；</li>
 *   <li><b>单调性</b>：子格越细误差越小；</li>
 *   <li><b>不越界</b>：所有盒子都落在给定包围盒内。</li>
 * </ol>
 *
 * <p>这些数字就是"1/16 精度"这句话的**可验证含义**。
 */
public final class NurbsCollisionCheck {

    private static int checks;
    private static int failures;

    /** 测试用：半径 2、高 5 的圆柱，轴为 z，底面在 z=0。 */
    private static final double R = 2.0;
    private static final double H = 5.0;

    public static void main(final String[] args) {
        final SweptSolid cyl = cylinder();
        analyticVolume();
        volumeConvergence(cyl);
        errorBoundIsRespected(cyl);
        errorBoundIsNotVacuous(cyl);
        conservativeNeverUndercovers(cyl);
        noBoxEscapesBounds(cyl);
        boxesAreAxisAlignedAndNonDegenerate(cyl);
        System.out.println("NURBS_COLLISION_CHECK=" + (failures == 0 ? "OK" : "FAIL")
                + " checks=" + checks + " failures=" + failures);
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 构造

    /** 用圆形轮廓拉伸出的圆柱（离散成 256 边形轮廓，足够表达"圆"）。 */
    private static SweptSolid cylinder() {
        final int n = 256;
        final double[][] ring = new double[n][2];
        for (int i = 0; i < n; i++) {
            final double a = 2 * Math.PI * i / n;
            ring[i][0] = R * Math.cos(a);
            ring[i][1] = R * Math.sin(a);
        }
        return SweptSolid.extrude(new double[][][]{ring},
                new double[]{0, 0, 0}, new double[]{1, 0, 0}, new double[]{0, 1, 0}, 0.0, H);
    }

    // ---------------------------------------------------------------- 断言

    private static void analyticVolume() {
        final double v = Math.PI * R * R * H;
        check("解析体积", Math.abs(v - 62.8318530718) < 1.0e-6, "v=" + v);
    }

    private static void volumeConvergence(final SweptSolid cyl) {
        final double truth = Math.PI * R * R * H;
        final double area = 2 * Math.PI * R * H + 2 * Math.PI * R * R;
        final double[] lo = {-R - 1, -R - 1, -1};
        final double[] hi = {R + 1, R + 1, H + 1};
        double prevErr = Double.MAX_VALUE;
        boolean monotone = true;
        final StringBuilder detail = new StringBuilder();
        for (final int subdiv : new int[]{4, 8, 16}) {
            final List<double[]> boxes = Voxelizer.voxelize(cyl, lo, hi, 1.0, subdiv,
                    Voxelizer.Criterion.CENTER);
            final double v = Voxelizer.volume(boxes);
            final double err = Math.abs(v - truth);
            detail.append(" subdiv=").append(subdiv).append(" err=").append(fmt(err));
            // ⚠ 阈值必须**跟着理论上界走**，不能拍一个常数：
            //   我第一版对 subdiv=4 也要求 2%，而子格 1/4 时理论上界是 30% —— 那是在
            //   要求算法超过它自己的数学保证。这里改成分档：细档要求绝对精度，
            //   粗档只要求落在理论保证之内。
            final double bound = Voxelizer.errorBound(area, 1.0, subdiv);
            check("体积误差落在理论上界内 subdiv=" + subdiv, err <= bound,
                    "实测 " + fmt(err) + " 上界 " + fmt(bound));
            if (subdiv >= 16) {
                check("1/16 精度下的绝对体积误差", err / truth < 0.02,
                        "相对误差 " + fmt(err / truth));
            }
            if (err > prevErr * 1.05) {
                monotone = false;
            }
            prevErr = Math.min(prevErr, err);
        }
        check("子格越细误差不增大", monotone, detail.toString());
    }

    private static void errorBoundIsRespected(final SweptSolid cyl) {
        final double area = 2 * Math.PI * R * H + 2 * Math.PI * R * R;
        final double[] lo = {-R - 1, -R - 1, -1};
        final double[] hi = {R + 1, R + 1, H + 1};
        for (final int subdiv : new int[]{4, 8, 16}) {
            final List<double[]> boxes = Voxelizer.voxelize(cyl, lo, hi, 1.0, subdiv,
                    Voxelizer.Criterion.CENTER);
            final double measured = symmetricDifference(cyl, boxes, lo, hi, 160);
            final double bound = Voxelizer.errorBound(area, 1.0, subdiv);
            check("对称差 <= 理论上界 subdiv=" + subdiv, measured <= bound,
                    "实测 " + fmt(measured) + " 上界 " + fmt(bound));
        }
    }

    private static void errorBoundIsNotVacuous(final SweptSolid cyl) {
        // 上界如果比实测大两个数量级，那它就没有约束力（等于没写）
        final double area = 2 * Math.PI * R * H + 2 * Math.PI * R * R;
        final double[] lo = {-R - 1, -R - 1, -1};
        final double[] hi = {R + 1, R + 1, H + 1};
        final List<double[]> boxes = Voxelizer.voxelize(cyl, lo, hi, 1.0, 16,
                Voxelizer.Criterion.CENTER);
        final double measured = symmetricDifference(cyl, boxes, lo, hi, 200);
        final double bound = Voxelizer.errorBound(area, 1.0, 16);
        check("上界不空泛（实测 >= 上界/20）", measured >= bound / 20.0,
                "实测 " + fmt(measured) + " 上界 " + fmt(bound));
    }

    private static void conservativeNeverUndercovers(final SweptSolid cyl) {
        final double truth = Math.PI * R * R * H;
        final double[] lo = {-R - 1, -R - 1, -1};
        final double[] hi = {R + 1, R + 1, H + 1};
        for (final int subdiv : new int[]{4, 8, 16}) {
            final List<double[]> boxes = Voxelizer.voxelize(cyl, lo, hi, 1.0, subdiv,
                    Voxelizer.Criterion.CONSERVATIVE);
            final double v = Voxelizer.volume(boxes);
            // 保守准则**不允许**少覆盖：体积必须 >= 真实体积（宽容 0.5% 的离散误差）
            check("保守准则不漏判 subdiv=" + subdiv, v >= truth * 0.995,
                    "体素体积 " + fmt(v) + " 真实 " + fmt(truth));
        }
    }

    private static void noBoxEscapesBounds(final SweptSolid cyl) {
        final double[] lo = {-R - 1, -R - 1, -1};
        final double[] hi = {R + 1, R + 1, H + 1};
        final List<double[]> boxes = Voxelizer.voxelize(cyl, lo, hi, 1.0, 16,
                Voxelizer.Criterion.CENTER);
        int bad = 0;
        for (final double[] b : boxes) {
            if (b[0] < lo[0] - 1e-9 || b[1] < lo[1] - 1e-9 || b[2] < lo[2] - 1e-9
                    || b[3] > hi[0] + 1e-9 || b[4] > hi[1] + 1e-9 || b[5] > hi[2] + 1e-9) {
                bad++;
            }
        }
        check("盒子不越出包围盒", bad == 0, "越界盒子数 " + bad);
    }

    private static void boxesAreAxisAlignedAndNonDegenerate(final SweptSolid cyl) {
        final double[] lo = {-R - 1, -R - 1, -1};
        final double[] hi = {R + 1, R + 1, H + 1};
        final List<double[]> boxes = Voxelizer.voxelize(cyl, lo, hi, 1.0, 16,
                Voxelizer.Criterion.CENTER);
        int bad = 0;
        for (final double[] b : boxes) {
            if (!(b[3] > b[0] && b[4] > b[1] && b[5] > b[2])) {
                bad++;
            }
        }
        check("盒子非退化", bad == 0, "退化盒子数 " + bad);
        // 1/16 精度：所有边界都落在 1/16 的整数倍上
        int offGrid = 0;
        for (final double[] b : boxes) {
            for (int k = 0; k < 6; k++) {
                final double v = b[k] * 16.0;
                if (Math.abs(v - Math.round(v)) > 1.0e-6) {
                    offGrid++;
                }
            }
        }
        check("盒子都对齐到 1/16 网格", offGrid == 0, "未对齐坐标数 " + offGrid);
    }

    // ---------------------------------------------------------------- 度量

    /**
     * 用细网格估计**对称差体积**（体素集合与真实实体的差异）。
     *
     * <p>⚠ 这是估计：网格分辨率有限，且边界格本身有 ±半格的不确定。
     * 所以断言用的是"≤ 上界"这种**单边**比较，不给估计值本身设精度要求。
     */
    private static double symmetricDifference(final SweptSolid solid, final List<double[]> boxes,
                                              final double[] lo, final double[] hi,
                                              final int res) {
        final double dx = (hi[0] - lo[0]) / res;
        final double dy = (hi[1] - lo[1]) / res;
        final double dz = (hi[2] - lo[2]) / res;
        double diff = 0.0;
        final double[] c = new double[3];
        for (int i = 0; i < res; i++) {
            for (int j = 0; j < res; j++) {
                for (int k = 0; k < res; k++) {
                    c[0] = lo[0] + (i + 0.5) * dx;
                    c[1] = lo[1] + (j + 0.5) * dy;
                    c[2] = lo[2] + (k + 0.5) * dz;
                    final boolean inSolid = solid.inside(c);
                    final boolean inVox = inBoxes(boxes, c);
                    if (inSolid != inVox) {
                        diff += dx * dy * dz;
                    }
                }
            }
        }
        return diff;
    }

    private static boolean inBoxes(final List<double[]> boxes, final double[] p) {
        for (final double[] b : boxes) {
            if (p[0] >= b[0] && p[0] <= b[3] && p[1] >= b[1] && p[1] <= b[4]
                    && p[2] >= b[2] && p[2] <= b[5]) {
                return true;
            }
        }
        return false;
    }

    private static String fmt(final double v) {
        return String.format(java.util.Locale.ROOT, "%.5f", v);
    }

    private static void check(final String what, final boolean ok, final String detail) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what + " : " + detail);
        }
    }
}
