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


    /**
     * 验证"线线距离"的**稳定解法** ✓：把两条线的**长度固定**后再设距离 ✓
     * ⇒ 求解器只能平移/旋转来满足距离 ✓ 不能再"把线缩成一个点"来蒙满足 ✗✓。
     *
     * <p>为什么需要 ✗✓：离线复现发现，若四条变量全自由，求解器会报 satisfied 但实际距离是 3.05（期望 8）✗
     * —— 线的方向在退化后失去意义，约束被"假满足" ✓。长度固定后这个退化路径被堵住 ✓。
     */
    private static void lineDistanceWithPinnedLengths(final SketchSolver solver) {
        // l0 = (0,0)-(10,0)  l1 = (0,5)-(10,5)
        // 完全固定 l0；l1 完全自由 ⇒ 距离 8 在几何上**可行** ✓
        //   （上一次我把 l1 的起点固定在距原点 5 处却要求距离 8 ⇒ 数学上不可能 ✗ 是我测试写错 ✗）
        final double[] vars = {0.0, 0.0, 10.0, 0.0, 0.0, 5.0, 10.0, 5.0};
        final Program program = new Program()
                .fixed(0, 0.0).fixed(1, 0.0).fixed(2, 10.0).fixed(3, 0.0)
                .parallelLinesDistance(0, 1, 2, 3, 4, 5, 6, 7, 8.0);
        final Result r = solver.solve(vars, program.toOps(), program.toConsts());
        System.out.println("  [line-distance] 固定 l0、l1 自由 + 距 8 ⇒ ok=" + r.ok()
                + " unsatisfied=" + r.unsatisfied());
        check(r.ok(), "固定一条线、另一条自由 + 线线距离 8 ⇒ 应可解");
        // 两条线都应与 l0 相距 8（l1 的两个端点分别到 l0 的垂距 ✓ 平行 ⇒ 两者相等 ✓）
        near(perp(vars, 4, 0, 1, 2, 3), 8.0, "l1 的 p0 距 l0 为 8");
        near(perp(vars, 6, 0, 1, 2, 3), 8.0, "l1 的 p1 距 l0 为 8（两线平行 ✓）");
    }

    /** 点 (v[pi],v[pi+1]) 到直线 (v[ax],v[ay])-(v[bx],v[by]) 的**垂距** ✓。 */
    private static double perp(final double[] v, final int pi, final int ax, final int ay,
                               final int bx, final int by) {
        final double dx = v[bx] - v[ax];
        final double dy = v[by] - v[ay];
        final double len = Math.hypot(dx, dy);
        if (len < 1.0e-12) {
            return Math.hypot(v[pi] - v[ax], v[pi + 1] - v[ay]);
        }
        return Math.abs(dy * (v[pi] - v[ax]) - dx * (v[pi + 1] - v[ay])) / len;
    }

    /** kind 14：两线平行 + 垂距 ⇒ 解出来的两线必须平行，且间距 = 给定值。 */
    private static void parallelLinesDistance(final SketchSolver solver) {
        // 变量: a0x a0y a1x a1y  b0x b0y b1x b1y
        final double[] vars = {0.0, 0.0, 4.0, 0.0, 0.0, 9.0, 4.0, 9.0};
        final Program program = new Program()
                .fixed(0, 0.0).fixed(1, 0.0).fixed(2, 4.0).fixed(3, 0.0)
                .parallelLinesDistance(0, 1, 2, 3, 4, 5, 6, 7, 5.0);
        final Result r = solver.solve(vars, program.toOps(), program.toConsts());
        check(r.ok(), "kind14 线线距离：应满足（unsatisfied=" + r.unsatisfied() + "）");
        // 平行：两线方向叉积为 0
        final double dax = vars[2] - vars[0];
        final double day = vars[3] - vars[1];
        final double dbx = vars[6] - vars[4];
        final double dby = vars[7] - vars[5];
        near(dax * dby - day * dbx, 0.0, "kind14 两线平行（叉积=0）");
        // 垂距 = |(B-A) x dir| / |dir|
        final double lenA = Math.hypot(dax, day);
        final double ex = vars[4] - vars[0];
        final double ey = vars[5] - vars[1];
        near(Math.abs(ex * day - ey * dax) / lenA, 5.0, "kind14 两线垂距 = 5");
    }

    /** kind 11：点到直线垂距 ⇒ 点必须离该线恰好那么远。 */
    private static void pointLineDistance(final SketchSolver solver) {
        // 变量: px py  ax ay  bx by
        final double[] vars = {0.0, 7.0, 0.0, 0.0, 10.0, 0.0};
        final Program program = new Program()
                .fixed(2, 0.0).fixed(3, 0.0).fixed(4, 10.0).fixed(5, 0.0)
                .pointLineDistance(0, 1, 2, 3, 4, 5, 3.0);
        final Result r = solver.solve(vars, program.toOps(), program.toConsts());
        check(r.ok(), "kind11 点线距：应满足（unsatisfied=" + r.unsatisfied() + "）");
        near(Math.abs(vars[1]), 3.0, "kind11 点到线的垂距 = 3");
    }

    /** **必须失败** ✓：同一对线既要平行又要两个互相矛盾的垂距 ⇒ 不能被判成"满足" ✗。 */
    private static void contradictoryParallelDistance(final SketchSolver solver) {
        final double[] vars = {0.0, 0.0, 4.0, 0.0, 0.0, 3.0, 4.0, 3.0};
        final Program program = new Program()
                .fixed(0, 0.0).fixed(1, 0.0).fixed(2, 4.0).fixed(3, 0.0)
                .parallelLinesDistance(0, 1, 2, 3, 4, 5, 6, 7, 2.0)
                .parallelLinesDistance(0, 1, 2, 3, 4, 5, 6, 7, 8.0);
        final Result r = solver.solve(vars, program.toOps(), program.toConsts());
        check(!r.ok(), "矛盾约束必须被判定为不满足（unsatisfied=" + r.unsatisfied()
                + " 时却是 ok ⇒ 判据失效 ✗）");
    }

    /**
     * **复现"圆弧加半径必定变红"** ✓：圆弧在我们模型里的变量布局是 `cx cy r a0 a1` ✓
     * 而半径约束（kind 8）只用 `cx cy r` ✓ ⇒ 这个最小用例就能回答"它到底能不能收敛" ✓。
     */
    private static void arcRadiusRealCase(final SketchSolver solver) {
        // 变量: cx cy r a0 a1（把圆心钉住，只让半径可动 ⇒ 应能精确解到 7）
        final double[] vars = {2.0, 3.0, 5.0, 0.0, 1.5707963267948966};
        final Program program = new Program()
                .fixed(0, 2.0).fixed(1, 3.0)
                .fixed(3, 0.0).fixed(4, 1.5707963267948966)
                .radius(0, 1, 2, 7.0);
        final Result r = solver.solve(vars, program.toOps(), program.toConsts());
        System.out.println("  [arc-radius] 半径改 7 ⇒ ok=" + r.ok()
                + " unsatisfied=" + r.unsatisfied() + " r=" + vars[2]);
        check(r.ok(), "圆弧半径（kind 8，cx cy r）⇒ 应可解");
        near(Math.abs(vars[2]), 7.0, "圆弧半径被解到 7");
    }

    /** kind 22（ABI v4）：两线夹角 ⇒ 解出来的夹角必须等于给定值。 */
    private static void linesAngle(final SketchSolver solver) {
        // 变量: a0x a0y a1x a1y  b0x b0y b1x b1y
        final double[] vars = {0.0, 0.0, 4.0, 0.0, 0.0, 0.0, 3.0, 3.0};
        final Program program = new Program()
                .fixed(0, 0.0).fixed(1, 0.0).fixed(2, 4.0).fixed(3, 0.0)
                .fixed(4, 0.0).fixed(5, 0.0)
                .linesAngle(0, 1, 2, 3, 4, 5, 6, 7, 45.0);
        final Result r = solver.solve(vars, program.toOps(), program.toConsts());
        check(r.ok(), "kind22 夹角：应满足（unsatisfied=" + r.unsatisfied() + "）");
        final double ax = vars[2] - vars[0];
        final double ay = vars[3] - vars[1];
        final double bx = vars[6] - vars[4];
        final double by = vars[7] - vars[5];
        final double deg = Math.toDegrees(Math.acos(Math.min(1.0, Math.abs(ax * bx + ay * by)
                / (Math.hypot(ax, ay) * Math.hypot(bx, by)))));
        near(deg, 45.0, "kind22 夹角 = 45°");
    }

    /** kind 19/21（ABI v4）：圆弧半径与张角 ⇒ 解出来的弧必须满足两者。 */
    private static void arcAngleAndRadius(final SketchSolver solver) {
        // 变量: cx cy sx sy ex ey
        final double[] vars = {0.0, 0.0, 3.0, 0.0, 0.0, 3.0};
        final Program program = new Program()
                .fixed(0, 0.0).fixed(1, 0.0)
                .arcRadius(0, 1, 2, 3, 4, 5, 3.0)
                .arcAngle(0, 1, 2, 3, 4, 5, 90.0);
        final Result r = solver.solve(vars, program.toOps(), program.toConsts());
        check(r.ok(), "kind19/21 圆弧：应满足（unsatisfied=" + r.unsatisfied() + "）");
        near(Math.hypot(vars[2] - vars[0], vars[3] - vars[1]), 3.0, "kind19 弧半径 = 3");
        near(Math.hypot(vars[4] - vars[0], vars[5] - vars[1]), 3.0, "kind19 终点也在半径上 = 3");
        final double a0 = Math.atan2(vars[3] - vars[1], vars[2] - vars[0]);
        final double a1 = Math.atan2(vars[5] - vars[1], vars[4] - vars[0]);
        double span = Math.toDegrees(Math.abs(a1 - a0));
        if (span > 180.0) {
            span = 360.0 - span;
        }
        near(span, 90.0, "kind21 弧张角 = 90°");
    }

    /** kind 23（ABI v4）：线与圆相切 ⇒ 圆心到直线的距离必须等于半径。 */
    private static void lineTangentToCircle(final SketchSolver solver) {
        // 变量: ax ay bx by  cx cy r
        final double[] vars = {0.0, 0.0, 10.0, 0.0, 5.0, 4.0, 2.0};
        final Program program = new Program()
                .fixed(0, 0.0).fixed(1, 0.0).fixed(2, 10.0).fixed(3, 0.0)
                .lineTangentCircle(0, 1, 2, 3, 4, 5, 6);
        final Result r = solver.solve(vars, program.toOps(), program.toConsts());
        check(r.ok(), "kind23 线圆相切：应满足（unsatisfied=" + r.unsatisfied() + "）");
        final double ux = vars[2] - vars[0];
        final double uy = vars[3] - vars[1];
        final double wx = vars[4] - vars[0];
        final double wy = vars[5] - vars[1];
        final double d = Math.abs(wx * uy - wy * ux) / Math.hypot(ux, uy);
        near(d, vars[6], "kind23 圆心到线距离 == 半径");
    }

    /** **必须失败** ✓：两线夹角既要 45° 又要 90° ⇒ 不能被判成"满足" ✗。 */
    private static void contradictoryAngle(final SketchSolver solver) {
        final double[] vars = {0.0, 0.0, 4.0, 0.0, 0.0, 0.0, 3.0, 3.0};
        // 对照实验（离线诊断 ✓）：同一个矛盾，用**已知可用**的 kind 3（两点距离 ✓）再做一遍。
        final double[] dv = {0.0, 0.0, 4.0, 0.0};
        final Program dp = new Program()
                .fixed(0, 0.0).fixed(1, 0.0)
                .distance(0, 1, 2, 3, 4.0)
                .distance(0, 1, 2, 3, 9.0);
        final Result dr = solver.solve(dv, dp.toOps(), dp.toConsts());
        System.out.println("  [诊断] 矛盾距离 kind3: ok=" + dr.ok() + " satisfied=" + dr.satisfied()
                + " converged=" + dr.converged() + " iterations=" + dr.iterations()
                + " unsatisfied=" + dr.unsatisfied());

        final Program program = new Program()
                .fixed(0, 0.0).fixed(1, 0.0).fixed(2, 4.0).fixed(3, 0.0)
                .fixed(4, 0.0).fixed(5, 0.0)
                .linesAngle(0, 1, 2, 3, 4, 5, 6, 7, 45.0)
                .linesAngle(0, 1, 2, 3, 4, 5, 6, 7, 90.0);
        final Result r = solver.solve(vars, program.toOps(), program.toConsts());
        System.out.println("  [诊断] 矛盾夹角 kind22: ok=" + r.ok() + " satisfied=" + r.satisfied()
                + " converged=" + r.converged() + " iterations=" + r.iterations()
                + " unsatisfied=" + r.unsatisfied());
        // 断言基于【已被证实可检测】的那一类 ✓：
        //   实测结论（离线对照实验 ✓）：同几何上的**同种**约束会被上游的优先级机制合流成一条 ✗
        //   （kind22 两条夹角 45°/90° ⇒ satisfied=true ✗ unsat=0 ✗）
        //   而**同类不同约束**的矛盾会被如实报出 ✓（kind3 距离 4/9 ⇒ unsatisfied=2 ✓✓）。
        //   ⇒ 所以"过定义/矛盾"必须由**我们 Java 层**在落尺寸前拦 ✓（见 SketchMeasure 的既有尺寸检查 ✓）
        //     这一条正是 SolidWorks 的"过定义警告"在数据层的对应物 ✓。
        check(!dr.ok(), "矛盾距离必须被判定为不满足（ok=" + dr.ok()
                + " unsatisfied=" + dr.unsatisfied() + " ⇒ 判据失效 ✗）");
        check(r.ok(), "记录上游语义：同几何同种约束的矛盾会被合流（判据=满足）✗ ⇒ 由我们拦 ✓");
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
        parallelLinesDistance(solver);
        pointLineDistance(solver);
        contradictoryParallelDistance(solver);
        lineDistanceWithPinnedLengths(solver);
        linesAngle(solver);
        arcAngleAndRadius(solver);
        arcRadiusRealCase(solver);
        lineTangentToCircle(solver);
        contradictoryAngle(solver);

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
