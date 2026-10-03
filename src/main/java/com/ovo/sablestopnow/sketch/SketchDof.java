package com.ovo.sablestopnow.sketch;

/**
 * 自由度分析：判断草图里哪些变量还没被约束**唯一确定**。
 *
 * <h2>用途</h2>
 * SolidWorks 会把"完全定义"的图元画成黑色、欠定义画成蓝色。要做到这个，
 * 必须能回答"这个图元的变量还被自由度剩着吗"。
 *
 * <h2>原理</h2>
 * 约束可以写成 {@code r(x) = 0} 的方程组。在当前解附近做一阶展开，
 * 就是雅可比矩阵 {@code J = ∂r/∂x}（{@code m × n}）。{@code J} 的**零空间**里的方向
 * 就是"满足约束的同时还能动"的方向 —— 也就是**剩余的平移/旋转自由度**。
 * 某个变量只要在任何零空间基向量里有非零分量，它就是"还自由"的。
 *
 * <h2>为什么先单独做线性代数这一层</h2>
 * "求零空间"是纯数学，可以用手算矩阵精确断言；而"约束的残差怎么写"依赖求解器的
 * 操作数约定，容易写错又不容易发现。分开做，至少这一层是钉死的。
 *
 * <p>⚠ 只依赖 JDK，可用 {@code tools/SketchModelCheck.java} 验证。
 */
public final class SketchDof {

    /** 数值秩的判定阈值：RREF 中绝对值小于它的元素当成 0。 */
    public static final double EPSILON = 1.0e-9;

    private SketchDof() {
    }

    /**
     * 求哪些变量还自由。
     *
     * @param jacobian 约束对变量的偏导，{@code m × n}（行 = 残差分量，列 = 变量）
     * @return 长度 {@code n} 的数组，{@code true} = 该变量还没被确定
     */
    public static boolean[] freeVariables(final double[][] jacobian) {
        return freeVariables(jacobian, columns(jacobian));
    }

    /** 数值微分的步长：太小会被浮点噪声淹没，太大会被二阶项污染。 */
    public static final double STEP = 1.0e-6;

    /**
     * 一张草图里**还自由的变量**。
     *
     * <p>这就是"欠定义 / 完全定义"上色的依据：某个图元的变量只要有一个自由，
     * 它就是欠定义（SolidWorks 画蓝色；完全定义画黑色）。
     *
     * <p>⚠ **认不出的约束一律当成"没约束"**（宁可说它自由）。方向很重要：
     * 把"其实还能动"误判成"已锁死"会让玩家以为模型定死了、然后一拖就散，
     * 反过来只是显示得保守一点。
     */
    public static boolean[] freeVariablesOf(final Sketch sketch) {
        // ⚠ 只看**仍被图元引用**的变量。删图元时会保留它的变量（否则下标全体错位），
        // 那些孤儿变量不构成自由度 —— 算进去的话"还需要 N 个约束"永远减不下去。
        final int[] active = sketch.usedVariables();
        final boolean[] reduced = freeVariables(selectColumns(jacobian(sketch), active),
                active.length);
        final boolean[] out = new boolean[sketch.variableCount()];
        for (int i = 0; i < active.length; i++) {
            out[active[i]] = reduced[i];
        }
        return out;
    }

    /** 从矩阵里挑出指定的列（其余列整体丢掉）。 */
    static double[][] selectColumns(final double[][] matrix, final int[] columns) {
        final double[][] out = new double[matrix.length][columns.length];
        for (int r = 0; r < matrix.length; r++) {
            for (int c = 0; c < columns.length; c++) {
                out[r][c] = columns[c] < matrix[r].length ? matrix[r][columns[c]] : 0.0;
            }
        }
        return out;
    }

    /** 约束的**残差行数**（一条约束可能贡献多行，比如重合是两行）。 */
    public static int residualCount(final Sketch sketch) {
        int rows = 0;
        for (final Sketch.Constraint c : sketch.constraints()) {
            rows += residualRows(c.kind());
        }
        return rows;
    }

    private static int residualRows(final int kind) {
        return switch (kind) {
            case SketchSolver.COINCIDENT, SketchSolver.MIDPOINT -> 2;
            // 认不出的种类不产生残差行（等于没约束，见类注释的方向说明）
            case SketchSolver.FIXED, SketchSolver.DISTANCE, SketchSolver.HORIZONTAL,
                 SketchSolver.VERTICAL, SketchSolver.EQUAL_LEN, SketchSolver.RADIUS -> 1;
            default -> 0;
        };
    }

    /**
     * 数值雅可比 {@code J[i][j] = ∂r_i/∂x_j}（前向差分）。
     *
     * <p>用数值差分而不是手写解析导数：约束种类不多，但解析导数要为每种种类各写一遍、
     * 每加一种约束就要改两处（求解器编码 + 导数），迟早对不上。
     */
    public static double[][] jacobian(final Sketch sketch) {
        final int n = sketch.variableCount();
        final int m = residualCount(sketch);
        final double[][] out = new double[m][n];
        if (n == 0 || m == 0) {
            return out;
        }
        final double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = sketch.variable(i);
        }
        final double[] base = residuals(sketch, x);
        final double[] probe = new double[n];
        for (int j = 0; j < n; j++) {
            System.arraycopy(x, 0, probe, 0, n);
            probe[j] += STEP;
            final double[] moved = residuals(sketch, probe);
            for (int i = 0; i < m; i++) {
                out[i][j] = (moved[i] - base[i]) / STEP;
            }
        }
        return out;
    }

    /**
     * 在当前变量取值下，把每条约束写成 {@code r(x) = 0} 的残差。
     *
     * <p>操作数布局（与 {@link SketchSolver#operandCount} 一致）：
     * <ul>
     *   <li>{@code FIXED(v)} → {@code x[v] − c}</li>
     *   <li>{@code COINCIDENT(ax,ay,bx,by)} → 两行：{@code ax−bx}、{@code ay−by}</li>
     *   <li>{@code DISTANCE(ax,ay,bx,by)} → {@code |b−a| − c}</li>
     *   <li>{@code HORIZONTAL(ax,ay,bx,by)} → {@code by−ay}（y 相等）</li>
     *   <li>{@code VERTICAL(...)} → {@code bx−ax}</li>
     *   <li>{@code EQUAL_LEN(8)} → {@code |L1| − |L2|}</li>
     *   <li>{@code RADIUS(cx,cy,r)} → {@code |r| − c}</li>
     *   <li>{@code MIDPOINT(px,py,ax,ay,bx,by)} → 两行：{@code px−(ax+bx)/2}、{@code py−(ay+by)/2}</li>
     * </ul>
     */
    public static double[] residuals(final Sketch sketch, final double[] x) {
        final double[] out = new double[residualCount(sketch)];
        int row = 0;
        for (final Sketch.Constraint c : sketch.constraints()) {
            final int[] o = c.operands();
            switch (c.kind()) {
                case SketchSolver.FIXED -> out[row++] = at(x, o, 0) - c.value();
                case SketchSolver.COINCIDENT -> {
                    out[row++] = at(x, o, 0) - at(x, o, 2);
                    out[row++] = at(x, o, 1) - at(x, o, 3);
                }
                case SketchSolver.DISTANCE -> out[row++] =
                        Math.hypot(at(x, o, 2) - at(x, o, 0), at(x, o, 3) - at(x, o, 1))
                                - c.value();
                case SketchSolver.HORIZONTAL -> out[row++] = at(x, o, 3) - at(x, o, 1);
                case SketchSolver.VERTICAL -> out[row++] = at(x, o, 2) - at(x, o, 0);
                case SketchSolver.EQUAL_LEN -> out[row++] =
                        Math.hypot(at(x, o, 2) - at(x, o, 0), at(x, o, 3) - at(x, o, 1))
                                - Math.hypot(at(x, o, 6) - at(x, o, 4), at(x, o, 7) - at(x, o, 5));
                case SketchSolver.RADIUS -> out[row++] = Math.abs(at(x, o, 2)) - c.value();
                case SketchSolver.MIDPOINT -> {
                    out[row++] = at(x, o, 0) - (at(x, o, 2) + at(x, o, 4)) * 0.5;
                    out[row++] = at(x, o, 1) - (at(x, o, 3) + at(x, o, 5)) * 0.5;
                }
                default -> {
                    // 认不出的种类：不产生残差（等于没约束）。方向见 freeVariablesOf 的注释。
                }
            }
        }
        return out;
    }

    /** 安全取操作数指向的变量值；越界返回 0（坏数据不该让自由度分析抛异常）。 */
    private static double at(final double[] x, final int[] operands, final int index) {
        if (index >= operands.length) {
            return 0.0;
        }
        final int v = operands[index];
        return v >= 0 && v < x.length ? x[v] : 0.0;
    }

    /**
     * 求哪些变量还自由（显式给出变量个数）。
     *
     * <p>⚠ 一定要有这个重载：**一条约束都没有的时候，空矩阵推导不出"有几个变量"**
     * （{@code new double[0][3]} 的行数是 0，从行里读列宽会得到 0，于是全自由的结果变成空表）。
     * 调用方本来就知道变量个数，别让这一层去猜。
     *
     * @param jacobian      约束对变量的偏导，{@code m × n}
     * @param variableCount 变量个数 {@code n}
     * @return 长度 {@code n} 的数组，{@code true} = 该变量还没被确定
     */
    public static boolean[] freeVariables(final double[][] jacobian, final int variableCount) {
        final int n = variableCount;
        final boolean[] free = new boolean[n];
        if (n == 0) {
            return free;
        }
        if (jacobian.length == 0) {
            // 一条约束都没有：所有变量都自由
            java.util.Arrays.fill(free, true);
            return free;
        }
        final double[][] r = rref(jacobian);
        final int[] pivotColumnOfRow = new int[r.length];
        java.util.Arrays.fill(pivotColumnOfRow, -1);
        final boolean[] isPivotColumn = new boolean[n];
        for (int row = 0; row < r.length; row++) {
            for (int col = 0; col < n; col++) {
                if (Math.abs(r[row][col]) > EPSILON) {
                    pivotColumnOfRow[row] = col;
                    isPivotColumn[col] = true;
                    break;
                }
            }
        }
        for (int col = 0; col < n; col++) {
            if (!isPivotColumn[col]) {
                // 非主元列本身就是自由变量（可以取 1 而其余自由变量取 0 构造零空间向量）
                free[col] = true;
            }
        }
        // 主元变量也可能自由：从属变量 = −Σ(系数 × 自由变量)。
        // 只要某个自由列的系数非零，这个主元变量就会跟着动。
        for (int row = 0; row < r.length; row++) {
            final int pivot = pivotColumnOfRow[row];
            if (pivot < 0) {
                continue;
            }
            for (int col = 0; col < n; col++) {
                if (!isPivotColumn[col] && Math.abs(r[row][col]) > EPSILON) {
                    free[pivot] = true;
                    break;
                }
            }
        }
        return free;
    }

    /**
     * 一个图元是否**完全定义**（它的所有变量都不自由）。
     *
     * @param free   {@link #freeVariables} 的结果
     * @param entity 要判断的图元
     */
    public static boolean isFullyDefined(final boolean[] free, final Sketch.Entity entity) {
        for (final int v : entity.vars()) {
            if (v >= 0 && v < free.length && free[v]) {
                return false;
            }
        }
        return true;
    }

    /** 还自由的变量个数。 */
    public static int freeCount(final boolean[] free) {
        int n = 0;
        for (final boolean f : free) {
            if (f) {
                n++;
            }
        }
        return n;
    }

    /**
     * 雅可比的秩（独立约束个数）。
     *
     * <p>⚠ 不要和 {@link #freeCount} 混为一谈，它们是**两个不同的量**：
     * <ul>
     *   <li>{@code freeCount} = "还有哪些变量能动" —— 给**图元上色**用
     *       （图元只要有变量能动就是欠定义）。</li>
     *   <li>{@link #nullspaceDimension} = "还剩几个自由度" —— 给**整体状态**用
     *       （SolidWorks 底部的"还需要 N 个约束"）。</li>
     * </ul>
     * 例：两条线段接一个端点，消掉 2 个自由度（8→6），但那 8 个变量**个个都还能在
     * 某个方向上动**（整体平移、另一端各自摆），所以 {@code freeCount} 仍是 8。
     * 拿 {@code freeCount} 当"剩余自由度"就会得出"约束没生效"的错误结论。
     */
    public static int rank(final double[][] jacobian, final int variableCount) {
        if (jacobian.length == 0 || variableCount == 0) {
            return 0;
        }
        final double[][] r = rref(jacobian);
        int rank = 0;
        for (final double[] row : r) {
            for (int col = 0; col < variableCount; col++) {
                if (Math.abs(row[col]) > EPSILON) {
                    rank++;
                    break;
                }
            }
        }
        return rank;
    }

    /** 剩余自由度 = 变量数 − 独立约束数。0 表示整张草图已完全约束。 */
    public static int nullspaceDimension(final double[][] jacobian, final int variableCount) {
        return variableCount - rank(jacobian, variableCount);
    }

    /** 整张草图还剩几个自由度（UI 上"还需要 N 个约束"就是它）。 */
    public static int remainingDof(final Sketch sketch) {
        // 同上：只数仍被引用的变量，孤儿变量不算自由度
        final int[] active = sketch.usedVariables();
        return nullspaceDimension(selectColumns(jacobian(sketch), active), active.length);
    }

    /** 仍被图元引用的变量个数（"还需要 N 个约束"的分母口径）。 */
    public static int activeVariableCount(final Sketch sketch) {
        return sketch.usedVariables().length;
    }

    /**
     * 是否存在**冗余约束**（独立约束数少于约束行数）。
     *
     * <p>⚠ 冗余 ≠ 冲突。冗余只是"约束给多了、有一条是别人推出来的"，
     * 系统仍然自洽；真正**冲突**（自相矛盾）要看求解后的残差有多大，见 {@link #maxResidual}。
     * 这两件事必须分开报，否则 UI 会把"多标了一个尺寸"说成"约束打架"。
     *
     * <p>⚠ 矩形本身就是冗余的：4 条边 + 4 个重合（8 行）+ 4 个水平竖直（4 行）= 12 行，
     * 而变量只有 8 个 —— 这在 CAD 里很正常，**不要**据此报错。
     */
    public static boolean hasRedundantConstraints(final Sketch sketch) {
        final double[][] j = jacobian(sketch);
        return rank(j, sketch.variableCount()) < j.length;
    }

    /**
     * 当前变量取值下，所有约束残差的最大绝对值 —— 判断"系统自洽吗"。
     *
     * <p>求解成功之后它应当接近 0。明显大于 0 说明约束**互相矛盾**
     * （或者求解没收敛）。这种情况下自由度分析会给出误导结果，
     * UI 应当退化成"无法判断"而不是照常标颜色。
     */
    public static double maxResidual(final Sketch sketch) {
        final int n = sketch.variableCount();
        final double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            x[i] = sketch.variable(i);
        }
        double max = 0.0;
        for (final double r : residuals(sketch, x)) {
            max = Math.max(max, Math.abs(r));
        }
        return max;
    }

    /** 矩阵的列数（取最长的一行，容忍非矩形输入）。 */
    private static int columns(final double[][] m) {
        int n = 0;
        for (final double[] row : m) {
            n = Math.max(n, row.length);
        }
        return n;
    }

    /**
     * 行最简形（RREF），带部分主元选取。
     *
     * <p>自己写而不是引第三方库：这一层要能离线断言，而且规模极小
     * （变量几十个、约束几十条），不值得引一个线性代数库。
     */
    static double[][] rref(final double[][] input) {
        final int n = columns(input);
        final double[][] a = new double[input.length][n];
        for (int i = 0; i < input.length; i++) {
            System.arraycopy(input[i], 0, a[i], 0, input[i].length);
        }
        int pivotRow = 0;
        for (int col = 0; col < n && pivotRow < a.length; col++) {
            // 部分主元：选该列绝对值最大的行，减少数值误差
            int best = -1;
            double bestAbs = EPSILON;
            for (int row = pivotRow; row < a.length; row++) {
                final double abs = Math.abs(a[row][col]);
                if (abs > bestAbs) {
                    bestAbs = abs;
                    best = row;
                }
            }
            if (best < 0) {
                continue; // 这一列全是 0，不是主元列
            }
            final double[] tmp = a[pivotRow];
            a[pivotRow] = a[best];
            a[best] = tmp;

            final double pivotValue = a[pivotRow][col];
            for (int j = 0; j < n; j++) {
                a[pivotRow][j] /= pivotValue;
            }
            for (int row = 0; row < a.length; row++) {
                if (row == pivotRow) {
                    continue;
                }
                final double factor = a[row][col];
                if (Math.abs(factor) <= EPSILON) {
                    continue;
                }
                for (int j = 0; j < n; j++) {
                    a[row][j] -= factor * a[pivotRow][j];
                }
            }
            pivotRow++;
        }
        return a;
    }
}
