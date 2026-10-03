import com.ovo.sablestopnow.sketch.SolidCollision;

import java.util.ArrayList;
import java.util.List;

/**
 * **薄壳拟合（fitShell）的精度与规模检查**（纯 JDK）。
 *
 * <p>用户的条件是"**如果精度不差**"就做。所以这里把两条主张钉死：
 * <ol>
 *   <li><b>包含</b>：每个采样点都落在某个盒子里 → 物理上不穿模；</li>
 *   <li><b>偏差 ≤ 1/16</b>：采样点到"包含它的那个盒子"的距离不超过 1/16 方块 ——
 *       与原来 16³ 位图的精度**同级**；</li>
 *   <li><b>规模</b>：一格里的盒子数是 1~8 个量级，而不是几百个。</li>
 * </ol>
 *
 * <p>输入是**合成采样**（平面片、45° 斜面片、陡斜面片）—— 这里测的是"给定采样，拟合得多准"，
 * 不是在测试里重造几何定义（几何定义仍然只有 {@code SolidCollision}/{@code SolidMesh} 一份）。
 */
public final class ShellFitCheck {

    private static int checks;
    private static int failures;

    private static final int SUBDIV = 16;
    private static final double TOL = 1.0 / SUBDIV;

    public static void main(final String[] args) {
        // 1) 平面片（法线 +Y）：应当**一个盒子**就够
        final List<double[]> flat = patch(0, 1, 0, 0.0, 0.0);
        final List<double[]> bFlat = SolidCollision.fitShellForCheck(flat, SUBDIV, 4096);
        report("平面片", flat, bFlat, 1);

        // 2) 45° 斜面（法线 (1,1,0)/√2）：高度跨度约 1，需要若干台阶
        final List<double[]> slope45 = patch(0.7071, 0.7071, 0.0, 0.0, 1.0);
        final List<double[]> b45 = SolidCollision.fitShellForCheck(slope45, SUBDIV, 4096);
        report("45° 斜面", slope45, b45, 16);

        // 3) 陡斜面（法线 (0.3,0.95,0)）：主轴仍是 Y，高度跨度更大
        final List<double[]> steep = patch(0.3, 0.95, 0.0, 0.0, 0.9);
        final List<double[]> bSteep = SolidCollision.fitShellForCheck(steep, SUBDIV, 4096);
        report("陡斜面", steep, bSteep, 16);

        System.out.println("SHELL_FIT_CHECK=" + (failures == 0 ? "OK" : "FAIL")
                + " checks=" + checks + " failures=" + failures);
        if (failures != 0) {
            System.exit(1);
        }
    }

    /**
     * 造一片采样：{@code a = a0 + slope · b}（高度随 b 线性变化）。
     *
     * @param nx,ny,nz 面法线（决定主轴）
     */
    private static List<double[]> patch(final double nx, final double ny, final double nz,
                                        final double a0, final double slope) {
        final List<double[]> out = new ArrayList<>();
        // 主轴 = |n| 最大的轴；这里只在"高度轴"上按 slope 变化，另两轴铺满
        final int a = Math.abs(ny) >= Math.abs(nx) && Math.abs(ny) >= Math.abs(nz) ? 1
                : (Math.abs(nx) >= Math.abs(nz) ? 0 : 2);
        final int b = a == 1 ? 0 : 1;
        final int c = a == 2 ? 0 : 2;
        final int steps = 16;
        for (int i = 0; i <= steps; i++) {
            for (int j = 0; j <= steps; j++) {
                final double bv = 0.05 + 0.9 * i / steps;
                final double cv = 0.05 + 0.9 * j / steps;
                final double av = a0 + slope * bv;
                final double[] s = new double[6];
                s[a] = av;
                s[b] = bv;
                s[c] = cv;
                s[3] = nx;
                s[4] = ny;
                s[5] = nz;
                out.add(s);
            }
        }
        return out;
    }

    private static void report(final String what, final List<double[]> samples,
                               final List<double[]> boxes, final int maxBoxes) {
        check(what + "：出盒子", !boxes.isEmpty(), "盒子数 " + boxes.size());
        check(what + "：盒子数有界（≤" + maxBoxes + "）", boxes.size() <= maxBoxes,
                "盒子数 " + boxes.size());
        int uncovered = 0;
        double worst = 0.0;
        for (final double[] s : samples) {
            double best = Double.MAX_VALUE;
            for (final double[] b : boxes) {
                // 点到盒子的距离（在盒子内为 0）
                final double d = boxDistance(s[0], s[1], s[2], b);
                best = Math.min(best, d);
            }
            if (best == Double.MAX_VALUE) {
                uncovered++;
            } else {
                worst = Math.max(worst, best);
            }
        }
        check(what + "：所有采样点都被覆盖（不穿模）", uncovered == 0,
                "未覆盖采样 " + uncovered);
        check(what + "：偏差 ≤ 1/16", worst <= TOL + 1.0e-9,
                "最大偏差 " + worst + "（容差 " + TOL + "）");
    }

    /** 点到轴对齐盒子的距离（内部为 0）。 */
    private static double boxDistance(final double x, final double y, final double z,
                                      final double[] b) {
        final double dx = Math.max(Math.max(b[0] - x, 0.0), x - b[3]);
        final double dy = Math.max(Math.max(b[1] - y, 0.0), y - b[4]);
        final double dz = Math.max(Math.max(b[2] - z, 0.0), z - b[5]);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static void check(final String what, final boolean ok, final String detail) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL " + what + " : " + detail);
        }
    }
}
