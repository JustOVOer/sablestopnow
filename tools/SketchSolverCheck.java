// 离线门禁：在真原生库上（经 FFM）验证草图求解器的 JVM 侧契约。
//
// 为什么不靠进游戏测：求解器是纯计算，进游戏测一次要等两三分钟加载，
// 而且失败信息淹没在几万行日志里。这个 harness 直接加载编译好的原生库，
// 几百毫秒跑完，能精确定位是哪一条约束编码错了。
//
// 用法（由 tools/verify-all.ps1 调用，也可单独跑）：
//   javac -encoding UTF-8 -d <out> SketchSolver.java SketchSolverCheck.java
//   java  -cp <out> SketchSolverCheck <原生库路径>
//
// JDK 24+ 会警告 restricted method（FFM 未开 native access）。
// 正式产物靠 MANIFEST 的 Enable-Native-Access；这里用 --enable-native-access 消掉噪音。
//
// 最后一行固定输出 SKETCH_SOLVER_CHECK=OK checks=N failures=0 供脚本 grep。

import com.ovo.sablestopnow.sketch.SketchSolver;
import com.ovo.sablestopnow.sketch.SketchSolver.Program;
import com.ovo.sablestopnow.sketch.SketchSolver.Result;

import java.nio.file.Path;

public final class SketchSolverCheck {

    private static int checks;
    private static int failures;

    private SketchSolverCheck() {
    }

    private static void check(final boolean condition, final String label) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("  FAIL " + label);
        }
    }

    private static void near(final double actual, final double expected, final String label) {
        check(Math.abs(actual - expected) < 1e-6,
                label + ": 期望 " + expected + "，实际 " + actual);
    }

    private static double dist(final double[] v, final int ix, final int iy,
                               final int jx, final int jy) {
        final double dx = v[ix] - v[jx];
        final double dy = v[iy] - v[jy];
        return Math.sqrt(dx * dx + dy * dy);
    }

    public static void main(final String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("用法: SketchSolverCheck <原生库路径>");
            System.exit(2);
        }
        final SketchSolver solver = SketchSolver.load(Path.of(args[0]));
        System.out.println("=== 草图求解器离线自检（真原生库 / FFM）===");

        horizontalLine(solver);
        verticalLine(solver);
        equalLength(solver);
        midpoint(solver);
        coincidence(solver);
        emptyProgram(solver);
        rectangle(solver);

        System.out.println("=== " + checks + " 项检查，" + failures + " 项失败 ===");
        System.out.println("SKETCH_SOLVER_CHECK=" + (failures == 0 ? "OK" : "FAIL")
                + " checks=" + checks + " failures=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    /** 一条从原点出发、长 4 的水平线。完全定义，解唯一（差一个正负号）。 */
    private static void horizontalLine(final SketchSolver solver) {
        // 变量: p.x p.y q.x q.y
        final double[] vars = {0.0, 0.0, 0.1, 0.1};
        final Program program = new Program()
                .fixed(0, 0.0).fixed(1, 0.0)
                .horizontal(0, 1, 2, 3)
                .distance(0, 1, 2, 3, 4.0);
        final Result r = solver.solve(vars, program.toOps(), program.toConsts());
        check(r.ok(), "水平线：应满足全部约束（iterations=" + r.iterations()
                + " unsatisfied=" + r.unsatisfied() + "）");
        near(vars[0], 0.0, "水平线 p.x");
        near(vars[1], 0.0, "水平线 p.y");
        near(vars[3], 0.0, "水平线 q.y（水平约束）");
        near(Math.abs(vars[2]), 4.0, "水平线 |q.x|（尺寸约束）");
    }

    /** 同一条线改成竖直：证明两条约束不是同一条代码路径。 */
    private static void verticalLine(final SketchSolver solver) {
        final double[] vars = {0.0, 0.0, 0.1, 0.1};
        final Program program = new Program()
                .fixed(0, 0.0).fixed(1, 0.0)
                .vertical(0, 1, 2, 3)
                .distance(0, 1, 2, 3, 3.0);
        final Result r = solver.solve(vars, program.toOps(), program.toConsts());
        check(r.ok(), "竖直线：应满足全部约束");
        near(vars[2], 0.0, "竖直线 q.x（竖直约束）");
        near(Math.abs(vars[3]), 3.0, "竖直线 |q.y|（尺寸约束）");
    }

    /** 等长约束应把第一条线段的尺寸传递到第二条。 */
    private static void equalLength(final SketchSolver solver) {
        // 变量: a0x a0y a1x a1y  b0x b0y b1x b1y
        final double[] vars = {0.0, 0.0, 5.0, 0.0, 0.0, 2.0, 3.0, 2.0};
        final Program program = new Program()
                .fixed(0, 0.0).fixed(1, 0.0).fixed(2, 5.0).fixed(3, 0.0)
                .horizontal(0, 1, 2, 3)
                .equalLength(0, 1, 2, 3, 4, 5, 6, 7);
        final Result r = solver.solve(vars, program.toOps(), program.toConsts());
        check(r.ok(), "等长：应满足全部约束");
        final double lenA = dist(vars, 0, 1, 2, 3);
        final double lenB = dist(vars, 4, 5, 6, 7);
        near(lenA, 5.0, "等长 |a|");
        near(lenB, lenA, "等长 |b| == |a|");
    }

    /** 中点约束把标记点拉到线段中心。 */
    private static void midpoint(final SketchSolver solver) {
        // 变量: ax ay bx by  mx my
        final double[] vars = {0.0, 0.0, 4.0, 2.0, 0.5, 0.5};
        final Program program = new Program()
                .fixed(0, 0.0).fixed(1, 0.0).fixed(2, 4.0).fixed(3, 2.0)
                .midpoint(0, 1, 2, 3, 4, 5);
        final Result r = solver.solve(vars, program.toOps(), program.toConsts());
        check(r.ok(), "中点：应满足全部约束");
        near(vars[4], 2.0, "中点 m.x");
        near(vars[5], 1.0, "中点 m.y");
    }

    /** 重合约束让两点合并。 */
    private static void coincidence(final SketchSolver solver) {
        final double[] vars = {1.0, 1.0, 9.0, 9.0};
        final Program program = new Program()
                .fixed(0, 1.0).fixed(1, 1.0)
                .coincident(0, 1, 2, 3);
        final Result r = solver.solve(vars, program.toOps(), program.toConsts());
        check(r.ok(), "重合：应满足全部约束");
        near(vars[2], 1.0, "重合 b.x");
        near(vars[3], 1.0, "重合 b.y");
    }

    /** 没有约束时应当是原样回显，而不是崩掉。 */
    private static void emptyProgram(final SketchSolver solver) {
        final double[] vars = {1.5, -2.25};
        final Result r = solver.solve(vars, new int[0], new double[0]);
        near(vars[0], 1.5, "空程序回显 v0");
        near(vars[1], -2.25, "空程序回显 v1");
        check(r.ok(), "空程序：无约束即平凡满足");
    }

    /**
     * 一个真实形状：3 × 2 的矩形。
     *
     * <p>这是第一个「像草图」的算例 —— 四条边、两两水平/竖直、两条尺寸。
     * 解不唯一（方向可翻），所以断言的是**不变量**（边长、水平/竖直）而不是具体坐标。
     */
    private static void rectangle(final SketchSolver solver) {
        // 变量: p0(0,1) p1(2,3) p2(4,5) p3(6,7)
        final double[] vars = {0.0, 0.0, 2.9, 0.1, 3.1, 2.1, 0.2, 1.9};
        final Program program = new Program()
                .fixed(0, 0.0).fixed(1, 0.0)
                .horizontal(0, 1, 2, 3).distance(0, 1, 2, 3, 3.0)
                .vertical(2, 3, 4, 5).distance(2, 3, 4, 5, 2.0)
                .horizontal(4, 5, 6, 7)
                .vertical(6, 7, 0, 1);
        final Result r = solver.solve(vars, program.toOps(), program.toConsts());
        check(r.ok(), "矩形：应满足全部约束（iterations=" + r.iterations()
                + " unsatisfied=" + r.unsatisfied() + "）");

        near(vars[1], 0.0, "矩形 p0.y 固定");
        near(vars[3], vars[1], "矩形 边 p0p1 水平（y 相等）");
        // ⚠ 竖直的判定是 **x 相等**（y 自由），不是 y 相等 —— 这里一开始写反了。
        near(vars[4], vars[2], "矩形 边 p1p2 竖直（x 相等）");
        near(vars[7], vars[5], "矩形 边 p2p3 水平（y 相等）");
        near(vars[6], vars[0], "矩形 边 p3p0 竖直（x 相等）");
        near(dist(vars, 0, 1, 2, 3), 3.0, "矩形 宽");
        near(dist(vars, 2, 3, 4, 5), 2.0, "矩形 高");
        // 对边相等是矩形闭合必然的结果 —— 顺带验一下几何自洽
        near(dist(vars, 4, 5, 6, 7), 3.0, "矩形 对边相等（宽）");
        near(dist(vars, 6, 7, 0, 1), 2.0, "矩形 对边相等（高）");
    }
}
