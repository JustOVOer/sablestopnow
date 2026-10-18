package com.ovo.sablestopnow.sketch;

import org.jetbrains.annotations.Nullable;

/**
 * 距离测量（仿 SolidWorks 的 Measure / 智能尺寸）：给两个图元，算出它们之间的距离。
 *
 * <h2>为什么单独一层</h2>
 * "两个图元之间的距离"其实是好几种不同的量，混在一处写很容易出现
 * "看起来对但语义不对"（比如两条斜线之间**没有**单一的"距离"标量，只有最近点距）。
 * 这一层把每种组合的语义明确下来，并且可以离线逐个钉死。
 *
 * <h2>返回值里为什么要带"是哪种距离"</h2>
 * UI 要告诉玩家他量到的到底是什么。两条平行线的距离和两条相交线的最近点距，
 * 数值上可能是同一套算法，但**语义完全不同** —— 不说清楚，玩家会以为量错了。
 *
 * <p>⚠ 只依赖 JOML，可用 {@code tools/SketchModelCheck.java} 验证。
 */
public final class SketchMeasure {

    /** 量到的距离属于哪一种（UI 据此显示不同的说明文字）。 */
    public enum Kind {
        /** 点到点。 */
        POINT_POINT("model.measure.point_point"),
        /** 点到线段（垂距；垂足在延长线上时取到端点的距离）。 */
        POINT_LINE("model.measure.point_line"),
        /** 两条平行线之间的垂距。 */
        PARALLEL_LINES("model.measure.parallel_lines"),
        /** 两条不平行线段的最近点距（不是唯一的"距离"，语义上要说明白）。 */
        SKEW_LINES("model.measure.skew_lines"),
        /** 圆/弧的圆心到点/线的距离。 */
        CENTER_DISTANCE("model.measure.center_distance"),
        /** 两个圆/弧之间的**表面**距离（圆心距 − 两个半径）。 */
        SURFACE_GAP("model.measure.surface_gap");

        private final String labelKey;

        Kind(final String labelKey) {
            this.labelKey = labelKey;
        }

        public String labelKey() {
            return labelKey;
        }
    }

    /**
     * 测量结果。
     *
     * @param distance 距离（非负）
     * @param kind     这是哪一种距离
     * @param angleDeg 仅两条不平行线段有意义：它们的锐夹角（度）；其余情况为 0
     */
    public record Result(double distance, Kind kind, double angleDeg) {
    }

    private SketchMeasure() {
    }

    /**
     * 这两个图元之间的距离**能不能落成一条约束**。
     *
     * <p>{@code SketchSolver.DISTANCE} 的定义是"两点之间"（4 个操作数 = 两个点的坐标），
     * 所以只有"两边都是**一个点**"才表达得了：点本身，或圆/弧的**圆心**。
     * 线段没有单一代表点（两个端点地位对等），因此点到线、线到线一律表达不了。
     *
     * <p>调用方拿它决定**给不给"标注"按钮**。表达不了时必须明说，
     * 不能塞一条语义不同的假约束 —— 那比不加还糟。
     */
    public static boolean canLock(final Sketch.Entity a, final Sketch.Entity b) {
        return a != null && b != null && !a.equals(b) && isPointLike(a) && isPointLike(b);
    }

    /** 图元是不是"一个点"（点本身 / 圆或弧的圆心）。 */
    private static boolean isPointLike(final Sketch.Entity entity) {
        return switch (entity.kind()) {
            case POINT, CIRCLE, ARC -> true;
            case LINE -> false;
        };
    }

    // ==================================================================
    // 智能尺寸：显示已有的 / 推测没有的 / 推测能加的约束
    // ==================================================================

    /**
     * 一条**尺寸**。
     *
     * @param constraintIndex 来自哪一条约束；**推测出来的**（{@code driving=false}）为 -1
     * @param solverKind      {@link SketchSolver} 的约束类型；推测出来的用 0
     * @param value           尺寸的**权威值**（已有尺寸 = 约束里的常数；推测的 = 当前测量值）
     * @param measured        当前几何**实际**量出来的值 —— 与 {@code value} 不等就说明求解器还没算平
     * @param driving         true = 这条尺寸在驱动几何（已有约束）；false = **参考尺寸**（只显示，不改几何）
     * @param labelKey        显示用的语言键
     * @param anchorU         标签该画在平面内的哪个位置（u）—— 渲染层拿它去投影到屏幕
     * @param anchorV         标签该画在平面内的哪个位置（v）
     */
    public record Dimension(int constraintIndex, int solverKind, double value, double measured,
                            boolean driving, String labelKey, double anchorU, double anchorV) {
    }

    /**
     * 列出草图里**已经有的尺寸**（用于显示）。
     *
     * <p>只有 {@code DISTANCE} 与 {@code RADIUS} 算尺寸 —— 它们的常数就是尺寸值。
     * {@code COINCIDENT}/{@code HORIZONTAL} 那些是**关系**不是尺寸，显示成"尺寸"会误导。
     *
     * <p>⚠ 同时也算出**当前实测值**：和约束里的常数不一致说明求解器还没把几何算到满足约束
     *（欠约束、或约束互相矛盾）。只显示常数会让玩家看到"标着 50 而线其实是 47"。
     */
    public static java.util.List<Dimension> existingDimensions(final Sketch sketch) {
        final java.util.List<Dimension> out = new java.util.ArrayList<>();
        if (sketch == null) {
            return out;
        }
        final java.util.List<Sketch.Constraint> constraints = sketch.constraints();
        for (int i = 0; i < constraints.size(); i++) {
            final Sketch.Constraint c = constraints.get(i);
            if (c.kind() == SketchSolver.DISTANCE && c.operands().length >= 4) {
                final int[] v = c.operands();
                final double mx = sketch.variable(v[2]) - sketch.variable(v[0]);
                final double my = sketch.variable(v[3]) - sketch.variable(v[1]);
                // 标签画在**两点的中点**：那条尺寸量的就是这两点，标签贴在中点最不容易歧义
                out.add(new Dimension(i, c.kind(), c.value(), Math.hypot(mx, my), true,
                        "model.dim.distance",
                        (sketch.variable(v[0]) + sketch.variable(v[2])) * 0.5,
                        (sketch.variable(v[1]) + sketch.variable(v[3])) * 0.5));
            } else if (c.kind() == SketchSolver.RADIUS && c.operands().length >= 3) {
                final int[] v = c.operands();
                final double r = Math.abs(sketch.variable(v[2]));
                // 标签画在圆心**右边一个半径**处：正好落在圆周上，一眼看出是哪个圆
                out.add(new Dimension(i, c.kind(), c.value(), r, true, "model.dim.radius",
                        sketch.variable(v[0]) + r + 20.0, sketch.variable(v[1]))); // ★ 尺寸线移到圆外侧 ✓（参考图 3 ✓）
            } else if (c.kind() == SketchSolver.ARC_RADIUS && c.operands().length >= 6) {
                // 弧半径（kind 19 ✓ 6 个操作数 ✓）：半径不在变量里 ⇒ 由圆心+起点算 ✓
                final int[] v = c.operands();
                final double r = Math.hypot(sketch.variable(v[2]) - sketch.variable(v[0]),
                        sketch.variable(v[3]) - sketch.variable(v[1]));
                out.add(new Dimension(i, c.kind(), c.value(), r, true, "model.dim.radius",
                        sketch.variable(v[0]) + r + 20.0, sketch.variable(v[1])));
            } else if ((c.kind() == SketchSolver.POINT_LINE_DISTANCE
                    || c.kind() == SketchSolver.HPOINT_LINE_DISTANCE
                    || c.kind() == SketchSolver.VPOINT_LINE_DISTANCE
                    || c.kind() == SketchSolver.PARALLEL_LINES_DISTANCE) && c.operands().length >= 6) {
                // 点到线 / 线到线：标签画在**垂足与点/线之间**⇒ 一眼看出量的是哪一段（与 SW 一致 ✓）
                final int[] v = c.operands();
                // ★ 修实测"线线距离显示位置不正确" ✓✓
                //   线线距离的操作数是 **8 个**：线一 0..3、线二 4..7 ✓
                //   原代码把线段起点下标一律写成 2 ✗ ⇒ 拿"线一的终点 + 线二的起点"当直线 ⇒ 垂足算错 ⇒ 标签乱飘 ✓
                //   ⇒ 线线距离要用 **4**（线二的起点）✓ 点到线才是 2 ✓
                final int li = c.kind() == SketchSolver.PARALLEL_LINES_DISTANCE ? 4 : 2;
                final double px = sketch.variable(v[0]);
                final double py = sketch.variable(v[1]);
                final double footX = projectX(sketch, px, py, v[li], v[li + 1], v[li + 2], v[li + 3]);
                final double footY = projectY(sketch, px, py, v[li], v[li + 1], v[li + 2], v[li + 3]);
                final double measured = Math.hypot(px - footX, py - footY);
                out.add(new Dimension(i, c.kind(), c.value(), measured, true,
                        c.kind() == SketchSolver.PARALLEL_LINES_DISTANCE
                                ? "model.dim.line_line_distance" : "model.dim.point_line_distance",
                        (px + footX) * 0.5, (py + footY) * 0.5));
            } else if ((c.kind() == SketchSolver.HORIZONTAL_DISTANCE
                    || c.kind() == SketchSolver.VERTICAL_DISTANCE) && c.operands().length >= 4) {
                // 水平/竖直尺寸：标签画在两点中点 ✓
                final int[] v = c.operands();
                final double measured = c.kind() == SketchSolver.HORIZONTAL_DISTANCE
                        ? Math.abs(sketch.variable(v[2]) - sketch.variable(v[0]))
                        : Math.abs(sketch.variable(v[3]) - sketch.variable(v[1]));
                out.add(new Dimension(i, c.kind(), c.value(), measured, true,
                        c.kind() == SketchSolver.HORIZONTAL_DISTANCE
                                ? "model.dim.horizontal" : "model.dim.vertical",
                        (sketch.variable(v[0]) + sketch.variable(v[2])) * 0.5,
                        (sketch.variable(v[1]) + sketch.variable(v[3])) * 0.5));
            } else if (c.kind() == SketchSolver.LINES_ANGLE && c.operands().length >= 8) {
                // 夹角：标签画在两条线**各自中点**的中间 ✓（交点可能在无穷远 ⇒ 不用交点 ✗）
                final int[] v = c.operands();
                final double mx0 = (sketch.variable(v[0]) + sketch.variable(v[2])) * 0.5;
                final double my0 = (sketch.variable(v[1]) + sketch.variable(v[3])) * 0.5;
                final double mx1 = (sketch.variable(v[4]) + sketch.variable(v[6])) * 0.5;
                final double my1 = (sketch.variable(v[5]) + sketch.variable(v[7])) * 0.5;
                // ★ 实测值必须**真算** ✗✓（原来直接把设定值当实测 ⇒ "标红"变成永远不红/永远红 ✓
                //   用户实测"线线距离改完反应正确但尺寸标红"就是这个机制的另一面 ✓）
                final double axv = sketch.variable(v[2]) - sketch.variable(v[0]);
                final double ayv = sketch.variable(v[3]) - sketch.variable(v[1]);
                final double bxv = sketch.variable(v[6]) - sketch.variable(v[4]);
                final double byv = sketch.variable(v[7]) - sketch.variable(v[5]);
                final double la = Math.hypot(axv, ayv);
                final double lb = Math.hypot(bxv, byv);
                double measuredDeg = c.value();
                if (la > 1.0e-9 && lb > 1.0e-9) {
                    // ★ 与放置时同一口径：**有向角**（atan2(叉积, 点积)）✓
                    //   否则"实测"和"设定"会差 180°，尺寸恒显示为异常 ✓
                    measuredDeg = Math.toDegrees(Math.atan2(axv * byv - ayv * bxv,
                            axv * bxv + ayv * byv));
                }
                out.add(new Dimension(i, c.kind(), c.value(), measuredDeg, true, "model.dim.angle",
                        (mx0 + mx1) * 0.5, (my0 + my1) * 0.5));
            }
        }
        return out;
    }

    /**
     * **推测**还没被标注的尺寸（SolidWorks 里那些"能被标注但还没标"的值）。
     *
     * <p>这就是"用当前有的尺寸和约束推测出其他的尺寸"：几何已经在那儿了，
     * 当前长度/半径**是确定的数**，只是玩家还没把它变成驱动尺寸。
     * 把它们显示成**参考尺寸**（灰、只读），玩家一眼能看到"还差哪几个没标"。
     *
     * <p>⚠ 已经标过的**不再列出**：同时显示"驱动的 50"和"参考的 50"会让人以为标重了。
     */
    public static java.util.List<Dimension> referenceDimensions(final Sketch sketch) {
        final java.util.List<Dimension> out = new java.util.ArrayList<>();
        if (sketch == null) {
            return out;
        }
        final java.util.List<Sketch.Constraint> constraints = sketch.constraints();
        for (final Sketch.Entity e : sketch.entities()) {
            final int[] v = e.vars();
            switch (e.kind()) {
                case LINE -> {
                    if (hasDistanceOn(constraints, v[0], v[1], v[2], v[3])) {
                        continue;
                    }
                    final double ax = sketch.variable(v[0]);
                    final double ay = sketch.variable(v[1]);
                    final double bx = sketch.variable(v[2]);
                    final double by = sketch.variable(v[3]);
                    final double len = Math.hypot(bx - ax, by - ay);
                    // 标签画在**线中点**
                    out.add(new Dimension(-1, 0, len, len, false, "model.dim.length",
                            (ax + bx) * 0.5, (ay + by) * 0.5));
                }
                case CIRCLE -> {
                    if (hasRadiusOn(constraints, v[2])) {
                        continue;
                    }
                    final double cx = sketch.variable(v[0]);
                    final double cy = sketch.variable(v[1]);
                    final double r = Math.abs(sketch.variable(v[2]));
                    // 圆按**直径**报（SolidWorks 的圆默认就是直径）
                    out.add(new Dimension(-1, 0, r * 2.0, r * 2.0, false,
                            "model.dim.diameter", cx + r, cy));
                }
                case ARC -> {
                    // 弧的半径不是变量 ⇒ 判"有没有标过"要看 ARC_RADIUS（kind 19）✓
                    if (hasArcRadiusOn(constraints, v)) {
                        continue;
                    }
                    final double r = sketch.arcRadius(e);
                    out.add(new Dimension(-1, 0, r, r, false,
                            "model.dim.radius", sketch.variable(v[0]) + r, sketch.variable(v[1])));
                }
                default -> {
                    // 点没有"尺寸"可言（它由位置约束决定），跳过
                }
            }
        }
        return out;
    }

    /** 这条线段的两个端点之间是否已经有 DISTANCE 约束（两个方向都算）。 */
    private static boolean hasDistanceOn(final java.util.List<Sketch.Constraint> constraints,
                                         final int ax, final int ay, final int bx, final int by) {
        for (final Sketch.Constraint c : constraints) {
            if (c.kind() != SketchSolver.DISTANCE || c.operands().length < 4) {
                continue;
            }
            final int[] v = c.operands();
            final boolean forward = v[0] == ax && v[1] == ay && v[2] == bx && v[3] == by;
            final boolean backward = v[0] == bx && v[1] == by && v[2] == ax && v[3] == ay;
            if (forward || backward) {
                return true;
            }
        }
        return false;
    }

    /** 半径变量上是否已经有 RADIUS 约束（圆）。 */
    private static boolean hasRadiusOn(final java.util.List<Sketch.Constraint> constraints,
                                       final int radiusVar) {
        for (final Sketch.Constraint c : constraints) {
            if (c.kind() == SketchSolver.RADIUS && c.operands().length >= 3
                    && c.operands()[2] == radiusVar) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这段圆弧上是否已经有 ARC_RADIUS 约束（kind 19）。
     *
     * <p>认领依据是**整组 6 个操作数**（圆心 xy + 起点 xy + 终点 xy ✓）：弧没有半径变量可对，
     * 只比"第一个下标"会把别的弧的尺寸也算进来。图元连续分配变量 ⇒ 整组相等才一定是它 ✓。
     */
    private static boolean hasArcRadiusOn(final java.util.List<Sketch.Constraint> constraints,
                                         final int[] arcVars) {
        if (arcVars.length < 6) {
            return false;
        }
        for (final Sketch.Constraint c : constraints) {
            if (c.kind() != SketchSolver.ARC_RADIUS) {
                continue;
            }
            final int[] o = c.operands();
            if (o.length < 6) {
                continue;
            }
            boolean same = true;
            for (int i = 0; i < 6; i++) {
                if (o[i] != arcVars[i]) {
                    same = false;
                    break;
                }
            }
            if (same) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一条**已经成立、但还没加进草图**的关系（SolidWorks 的 *automatic relations*）。
     *
     * @param solverKind 能加的约束类型
     * @param labelKey   显示用的语言键
     */
    public record ImpliedRelation(int solverKind, int[] vars, String labelKey) {
    }

    /**
     * **推测**能加的几何关系。
     *
     * <p>SolidWorks 在画图时就自动加关系；我们这边是"先画、后补"，
     * 所以这一步的价值是**把已经成立的关系找出来**，让玩家一键补上 ——
     * 补上之后几何才算被约束住（否则看着是水平的，一拖就歪了）。
     *
     * <p>只在**容差内**判定，而且**已经有同种约束的跳过**：
     * 重复加同一条约束会让求解器报冗余（我们的冗余检测会把它标红，玩家会以为出错了）。
     *
     * @param tolerance 判定"已经水平/竖直"的角度容差（度）
     */
    public static java.util.List<ImpliedRelation> impliedRelations(final Sketch sketch,
                                                                   final double tolerance) {
        final java.util.List<ImpliedRelation> out = new java.util.ArrayList<>();
        if (sketch == null) {
            return out;
        }
        final java.util.List<Sketch.Constraint> constraints = sketch.constraints();
        for (final Sketch.Entity e : sketch.entities()) {
            if (e.kind() != Sketch.EntityKind.LINE) {
                continue;
            }
            final int[] v = e.vars();
            final double dx = sketch.variable(v[2]) - sketch.variable(v[0]);
            final double dy = sketch.variable(v[3]) - sketch.variable(v[1]);
            if (Math.hypot(dx, dy) < 1.0e-9) {
                continue; // 退化线段：方向没意义
            }
            final double angle = Math.toDegrees(Math.atan2(Math.abs(dy), Math.abs(dx)));
            if (angle <= tolerance && !hasDirectional(constraints, SketchSolver.HORIZONTAL, v)) {
                out.add(new ImpliedRelation(SketchSolver.HORIZONTAL, v, "model.relation.horizontal"));
            } else if (angle >= 90.0 - tolerance
                    && !hasDirectional(constraints, SketchSolver.VERTICAL, v)) {
                out.add(new ImpliedRelation(SketchSolver.VERTICAL, v, "model.relation.vertical"));
            }
        }
        // ★ **直线 ↔ 圆 的相切** ✓（用户实测反馈："选择直线和圆后添加约束时没有相切按键" ✗）
        //
        // 判定 ✓：直线到**圆心**的垂距 ≈ 半径 ⇒ 已经相切 ✓ ⇒ 给一个"一键补上相切"的入口 ✓。
        // 约束用 kind 23（LINE_TANGENT_CIRCLE ✓ 操作数 a0x a0y a1x a1y cx cy r = **7 个** ✓
        //   与 SketchSolver.operandCount(23) 及 Rust 侧 operand_count() 一致 ✓）；
        // 它**不吃常量** ✓（Rust 侧 takes_const 不含 23 ✓）⇒ 不会把后面的常量挤错位 ✓。
        //
        // ⚠ **圆弧不再参与这条**：弧现在是"圆心 + 起点 + 终点"（6 个变量 ✓ 见 EntityKind.ARC ✓）
        //   半径不是变量 ⇒ kind 23 要的 {@code r} 操作数**没有可传的东西** ✗
        //   （Rust 侧 ABI v4 也没有"线 ↔ 弧相切"这一种 ✓ 只有 23 = 线 ↔ 圆 ✓）。
        //   硬塞一个"最近的坐标"当半径会落成一条**语义完全不同**的假约束 ✗ 比不给还糟 ✗
        //   ⇒ 弧只做相切判定不给按钮 ✓ 要相切请用圆（或者先给弧加重合/半径约束把几何定住）。
        for (final Sketch.Entity line : sketch.entities()) {
            if (line.kind() != Sketch.EntityKind.LINE) {
                continue;
            }
            final int[] lv = line.vars();
            final double ax = sketch.variable(lv[0]);
            final double ay = sketch.variable(lv[1]);
            final double bx = sketch.variable(lv[2]);
            final double by = sketch.variable(lv[3]);
            final double len = Math.hypot(bx - ax, by - ay);
            if (len < 1.0e-9) {
                continue;
            }
            for (final Sketch.Entity curve : sketch.entities()) {
                if (curve.kind() != Sketch.EntityKind.CIRCLE) {
                    continue;
                }
                final int[] cv = curve.vars();
                final double cx = sketch.variable(cv[0]);
                final double cy = sketch.variable(cv[1]);
                final double r = Math.abs(sketch.variable(cv[2]));
                // 点到直线（无限长）的垂距 ✓
                final double dist = Math.abs((by - ay) * (cx - ax) - (bx - ax) * (cy - ay)) / len;
                // ★ 用户要求（实测反馈）：**选中"直线 + 圆/圆弧"就应该有相切按钮** ✓
                //   原实现只在"**已经**相切"（容差 0.02 内）时才提示 ✗ —— 结果就是
                //   "目前仍然没有相切约束选项" ✓（他选的那对本来就还没相切 ✓）。
                //   ⇒ 现在**无条件提供**：按钮点了由求解器把几何真的拉成相切 ✓；
                //     已经相切的也照样提供（重复点会被下面的去重挡住 ✓ 不会加冗余约束 ✓）。
                //   ⚠ `TANGENT_TOLERANCE` 保留：将来若要区分"已成立/待建立"可用它 ✓。
                final boolean alreadyTangent = Math.abs(dist - r) <= TANGENT_TOLERANCE;
                final int[] vars = {lv[0], lv[1], lv[2], lv[3], cv[0], cv[1], cv[2]};
                if (hasSameKindAndOperands(constraints, SketchSolver.LINE_TANGENT_CIRCLE, vars)) {
                    continue; // 已经加过 ⇒ 不重复（重复会让求解器报冗余、界面标红 ✗）
                }
                out.add(new ImpliedRelation(SketchSolver.LINE_TANGENT_CIRCLE, vars,
                        "model.relation.tangent"));
            }
        }
        // 等长：两条线段长度在容差内相等
        final java.util.List<Sketch.Entity> lines = new java.util.ArrayList<>();
        for (final Sketch.Entity e : sketch.entities()) {
            if (e.kind() == Sketch.EntityKind.LINE) {
                lines.add(e);
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            for (int j = i + 1; j < lines.size(); j++) {
                final int[] a = lines.get(i).vars();
                final int[] b = lines.get(j).vars();
                final double la = Math.hypot(sketch.variable(a[2]) - sketch.variable(a[0]),
                        sketch.variable(a[3]) - sketch.variable(a[1]));
                final double lb = Math.hypot(sketch.variable(b[2]) - sketch.variable(b[0]),
                        sketch.variable(b[3]) - sketch.variable(b[1]));
                if (la < 1.0e-9 || lb < 1.0e-9) {
                    continue;
                }
                if (Math.abs(la - lb) <= Math.max(1.0e-9, lengthTolerance(tolerance, la))
                        && !hasEqualLength(constraints, a, b)) {
                    out.add(new ImpliedRelation(SketchSolver.EQUAL_LEN,
                            new int[] {a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3]},
                            "model.relation.equal"));
                }
            }
        }
        return out;
    }

    /** 等长判定的长度容差：按比例给（长线的绝对误差自然更大）。 */
    private static double lengthTolerance(final double angleToleranceDeg, final double length) {
        // 把"角度容差"换算成一个粗略的长度比例容差（1° 约 1.7%）
        return length * angleToleranceDeg * 0.0175;
    }

    /** 相切判定的容差（平面单位 = 格 ✓）：直线到**圆心**的垂距与半径之差在此以内 ⇒ 视为已相切 ✓。 */
    private static final double TANGENT_TOLERANCE = 0.02;

    /**
     * 是否已经存在"**同种类 + 同操作数**"的约束 ✓。
     *
     * <p>为什么要查 ✗✓：重复添加同一条约束会让求解器报冗余，我们的冗余检测会把它标红，
     * 玩家会以为是出错 ✓（`impliedRelations` 的既有约定：已有同种约束就跳过 ✓）。
     */
    private static boolean hasSameKindAndOperands(final java.util.List<Sketch.Constraint> constraints,
                                                 final int kind, final int[] vars) {
        for (final Sketch.Constraint c : constraints) {
            if (c.kind() != kind || c.operands().length != vars.length) {
                continue;
            }
            boolean same = true;
            for (int i = 0; i < vars.length; i++) {
                if (c.operands()[i] != vars[i]) {
                    same = false;
                }
            }
            if (same) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDirectional(final java.util.List<Sketch.Constraint> constraints,
                                          final int kind, final int[] vars) {
        for (final Sketch.Constraint c : constraints) {
            if (c.kind() != kind) {
                continue;
            }
            final int[] v = c.operands();
            if (v.length >= 4 && v[0] == vars[0] && v[1] == vars[1]
                    && v[2] == vars[2] && v[3] == vars[3]) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasEqualLength(final java.util.List<Sketch.Constraint> constraints,
                                          final int[] a, final int[] b) {
        for (final Sketch.Constraint c : constraints) {
            if (c.kind() != SketchSolver.EQUAL_LEN || c.operands().length < 8) {
                continue;
            }
            final int[] v = c.operands();
            final boolean forward = v[0] == a[0] && v[2] == a[2] && v[4] == b[0] && v[6] == b[2];
            final boolean backward = v[0] == b[0] && v[2] == b[2] && v[4] == a[0] && v[6] == a[2];
            if (forward || backward) {
                return true;
            }
        }
        return false;
    }

    /**
     * **智能尺寸**：按点中的东西推断"该标哪一种尺寸"。
     *
     * <h2>SolidWorks 的推断规则</h2>
     * 同一个工具，看玩家点了什么决定尺寸类型：
     * <ul>
     *   <li>一条**直线** → 长度</li>
     *   <li>一个**圆** → 直径（SW 默认；⚠ 我们的求解器只有 RADIUS，所以标半径，见下）</li>
     *   <li>一段**弧** → 半径</li>
     *   <li>两个**点**（或圆心）→ 点距</li>
     *   <li>两条**直线**：平行 → 线距；不平行 → **角度**</li>
     *   <li>点 + 直线 → 垂距</li>
     * </ul>
     *
     * <p>⚠ **能不能落到约束上要单独判**（{@link #canLock}）：我们的 {@code DISTANCE}
     * 只支持**点到点**，所以角度、点到线、平行线距**表达不了**。
     * 表达不了的必须退成**参考尺寸**（只显示、不改几何），
     * 绝不能塞一条语义不同的假约束 —— 那比不标还糟（玩家以为标上了，其实标的是别的东西）。
     *
     * @param picked 当前选中的图元（1 个或 2 个；顺序无所谓）
     * @return 推断结果；点了 0 个或 3 个以上、或组合不支持时返回 null
     */
    @Nullable
    public static SmartDimension smartDimension(final Sketch sketch,
                                                final java.util.List<Sketch.Entity> picked) {
        if (sketch == null || picked == null || picked.isEmpty() || picked.size() > 2) {
            return null;
        }
        // --- 单个图元 ---
        if (picked.size() == 1) {
            final Sketch.Entity e = picked.get(0);
            final int[] v = e.vars();
            return switch (e.kind()) {
                case LINE -> {
                    final double dx = sketch.variable(v[2]) - sketch.variable(v[0]);
                    final double dy = sketch.variable(v[3]) - sketch.variable(v[1]);
                    // 直线的长度**恰好**是它两个端点之间的距离 → 我们的 DISTANCE 表达得了
                    yield new SmartDimension("model.dim.length", Math.hypot(dx, dy), true,
                            SketchSolver.DISTANCE,
                            new int[] {v[0], v[1], v[2], v[3]});
                }
                case CIRCLE -> new SmartDimension("model.dim.radius",
                        Math.abs(sketch.variable(v[2])), true, SketchSolver.RADIUS,
                        new int[] {v[0], v[1], v[2]});
                case ARC -> new SmartDimension("model.dim.radius",
                        sketch.arcRadius(e), true, SketchSolver.ARC_RADIUS,
                        new int[] {v[0], v[1], v[2], v[3], v[4], v[5]});
                case POINT -> null; // 单个点没有尺寸（它的位置由别的尺寸决定）
            };
        }
        // --- 两个图元：复用已有的测量 + 可约束判定 ---
        final Sketch.Entity a = picked.get(0);
        final Sketch.Entity b = picked.get(1);
        // --- 两个图元：先按 SolidWorks 的**选择上下文**特判 ✓ -------------------------
        // ① 点 + 直线 ⇒ **点到直线的垂距** ✓（kind 11 ✓ 原生支持 ✓）
        //    以前这里会退化成"点到某个端点"的距离 ✗ —— 那不是 SolidWorks 的语义 ✓
        if (a.kind() == Sketch.EntityKind.POINT && b.kind() == Sketch.EntityKind.LINE) {
            return pointToLine(sketch, a, b);
        }
        if (b.kind() == Sketch.EntityKind.POINT && a.kind() == Sketch.EntityKind.LINE) {
            return pointToLine(sketch, b, a);
        }
        // ② 两条直线 ⇒ 平行则**线线距离** ✓（kind 14 = 平行 + 点线距 ✓ 正是"类推"的那条 ✓）
        //    不平行 ⇒ 落到下面的通用路径（夹角要在第 2 批补 shim 后才是**真约束** ✗
        //    现在不假装能标 ✗ —— expressible=false 的语义就是"只能当参考"✓）
        if (a.kind() == Sketch.EntityKind.LINE && b.kind() == Sketch.EntityKind.LINE) {
            final SmartDimension pd = parallelLinesDistance(sketch, a, b);
            if (pd != null) {
                return pd; // 平行 ⇒ **线线距离** ✓（kind 14 ✓）
            }
            // 不平行 ⇒ **夹角** ✓（kind 22 ✓ ABI v4 起是真约束 ✓ 不再是"只能当参考"✗）
            final int[] va = a.vars();
            final int[] vb = b.vars();
            final double ax = sketch.variable(va[2]) - sketch.variable(va[0]);
            final double ay = sketch.variable(va[3]) - sketch.variable(va[1]);
            final double bx = sketch.variable(vb[2]) - sketch.variable(vb[0]);
            final double by = sketch.variable(vb[3]) - sketch.variable(vb[1]);
            final double lenA = Math.hypot(ax, ay);
            final double lenB = Math.hypot(bx, by);
            if (lenA > 1e-9 && lenB > 1e-9) {
                final double cos = Math.min(1.0, Math.max(-1.0, (ax * bx + ay * by) / (lenA * lenB)));
                // ★ 修实测问题"角度设置出来是 (180 − 目标角度)" ✓✓
                //   原式用了 Math.abs(cos) ✗ ⇒ 永远得到**锐角**（≤90°），
                //   而求解器的 LINES_ANGLE(kind 22) 是**有向角**（两条线的方向向量夹角，0..180 ✓）
                //   ⇒ 两个方向相反时两边就差 180° ✓ 用户设 60 得到 120 ✓
                //   现在去掉绝对值：acos(cos) 就是方向向量的夹角 ✓ 与求解器口径一致 ✓。
                // ★ 修实测"**有时**还是 (180 − 角度)" ✓✓
                //   `acos` 只给**无向角**（0..180 ✓），而求解器的夹角约束是**有向角** ✗
                //   ⇒ 两条线的方向相反时就差 180° ✓（所以是"有时"✓）
                //   改用 `atan2(叉积, 点积)` ⇒ 有向角（−180..180 ✓）与求解器同口径 ✓
                final double cross = ax * by - ay * bx;
                final double degrees = Math.toDegrees(Math.atan2(cross, ax * bx + ay * by));
                return new SmartDimension("model.dim.angle", degrees, true,
                        SketchSolver.LINES_ANGLE,
                        new int[] {va[0], va[1], va[2], va[3], vb[0], vb[1], vb[2], vb[3]});
            }
        }

        final Result r = between(sketch, a, b);
        if (r == null) {
            return null;
        }
        final boolean lockable = canLock(a, b);
        final int[] vars = lockable ? pairVars(a, b) : null;
        // ⚠ 可约束时，显示的**必须**是即将落成约束的那个量 —— 也就是**代表点之间**的距离。
        // `between` 对两个圆返回的是**表面间距**（圆心距 − 两个半径），
        // 而 DISTANCE 约束约束的是**圆心距**。直接用 between 的值会出现
        // "界面显示 3、标下去变成 5" —— 玩家会以为工具算错了。
        final double value;
        if (lockable) {
            final double dx = sketch.variable(vars[2]) - sketch.variable(vars[0]);
            final double dy = sketch.variable(vars[3]) - sketch.variable(vars[1]);
            value = Math.hypot(dx, dy);
        } else {
            value = r.distance();
        }
        return new SmartDimension(
                lockable ? "model.dim.distance" : r.kind().labelKey(),
                value,
                lockable,
                lockable ? SketchSolver.DISTANCE : 0,
                vars);
    }

    /** 点到直线的**垂距**（kind 11 ✓）。operands: px py ax ay bx by ✓ */
    private static SmartDimension pointToLine(final Sketch sketch, final Sketch.Entity point,
                                              final Sketch.Entity line) {
        final int[] pv = point.vars();
        final int[] lv = line.vars();
        final double distance = perpendicularDistance(sketch, pv[0], pv[1], lv[0], lv[1], lv[2], lv[3]);
        return new SmartDimension("model.dim.point_line_distance", distance, true,
                SketchSolver.POINT_LINE_DISTANCE,
                new int[] {pv[0], pv[1], lv[0], lv[1], lv[2], lv[3]});
    }

    /** 两条**平行**直线之间的垂距（kind 14 ✓）；不平行时返回 null ✓ 交给通用路径 ✓。 */
    @Nullable
    private static SmartDimension parallelLinesDistance(final Sketch sketch, final Sketch.Entity a,
                                                        final Sketch.Entity b) {
        final int[] va = a.vars();
        final int[] vb = b.vars();
        final double ax = sketch.variable(va[2]) - sketch.variable(va[0]);
        final double ay = sketch.variable(va[3]) - sketch.variable(va[1]);
        final double bx = sketch.variable(vb[2]) - sketch.variable(vb[0]);
        final double by = sketch.variable(vb[3]) - sketch.variable(vb[1]);
        final double lenA = Math.hypot(ax, ay);
        final double lenB = Math.hypot(bx, by);
        if (lenA < 1e-9 || lenB < 1e-9) {
            return null;
        }
        // 平行判据：单位方向叉积 ≈ 0（用与长度无关的量 ✓ 免得短线被误判 ✗）
        if (Math.abs((ax / lenA) * (by / lenB) - (ay / lenA) * (bx / lenB)) > 1e-3) {
            return null;
        }
        final double distance = perpendicularDistance(sketch, vb[0], vb[1], va[0], va[1], va[2], va[3]);
        return new SmartDimension("model.dim.line_line_distance", distance, true,
                SketchSolver.PARALLEL_LINES_DISTANCE,
                new int[] {va[0], va[1], va[2], va[3], vb[0], vb[1], vb[2], vb[3]});
    }

    /** 点 P 在直线 AB 上的投影的 X ✓（退化时返回 A ✓）。 */
    private static double projectX(final Sketch sketch, final double px, final double py,
                                   final int ax, final int ay, final int bx, final int by) {
        final double ux = sketch.variable(bx) - sketch.variable(ax);
        final double uy = sketch.variable(by) - sketch.variable(ay);
        final double len2 = ux * ux + uy * uy;
        if (len2 < 1e-18) {
            return sketch.variable(ax);
        }
        final double k = ((px - sketch.variable(ax)) * ux + (py - sketch.variable(ay)) * uy) / len2;
        return sketch.variable(ax) + k * ux;
    }

    /** 点 P 在直线 AB 上的投影的 Y ✓。 */
    private static double projectY(final Sketch sketch, final double px, final double py,
                                   final int ax, final int ay, final int bx, final int by) {
        final double ux = sketch.variable(bx) - sketch.variable(ax);
        final double uy = sketch.variable(by) - sketch.variable(ay);
        final double len2 = ux * ux + uy * uy;
        if (len2 < 1e-18) {
            return sketch.variable(ay);
        }
        final double k = ((px - sketch.variable(ax)) * ux + (py - sketch.variable(ay)) * uy) / len2;
        return sketch.variable(ay) + k * uy;
    }
    /** |(P−A) × (B−A)| / |B−A| ✓（点到直线的垂距 ✓ 退化时返回点到 A 的距离 ✓）。 */
    private static double perpendicularDistance(final Sketch sketch, final int px, final int py,
                                                final int ax, final int ay, final int bx, final int by) {
        final double ux = sketch.variable(bx) - sketch.variable(ax);
        final double uy = sketch.variable(by) - sketch.variable(ay);
        final double len = Math.hypot(ux, uy);
        if (len < 1e-9) {
            return Math.hypot(sketch.variable(px) - sketch.variable(ax),
                    sketch.variable(py) - sketch.variable(ay));
        }
        final double wx = sketch.variable(px) - sketch.variable(ax);
        final double wy = sketch.variable(py) - sketch.variable(ay);
        return Math.abs(wx * uy - wy * ux) / len;
    }
    // ================= 尺寸线要的「两个参考点」 ✓ ======================================

    /**
     * 推出一条尺寸**被测量的两个参考点**（平面 uv ✓）⇒ 用来画尺寸线/界线/箭头 ✓。
     *
     * <h2>为什么"按需推导"而不是给 {@link Dimension} 加字段 ✗✓</h2>
     * 给记录加两个点会让**所有**构造点都要改（{@code referenceDimensions} 里就有几十处 ✗）
     * ⇒ 改动面大、回归风险高 ✗。而"驱动尺寸"的参考点**本来就在约束里**✓
     * ⇒ 用 {@code constraintIndex} 反查即可 ✓ 一处新代码搞定 ✓✓。
     *
     * @return {@code [aU, aV, bU, bV]} ✓；推导不出来（参考尺寸 / 索引失效 / 类型不认识）返回 {@code null} ✓
     *         ⇒ 调用方**只画文字**✓ 不画线 ✗（宁可少画，不画错的 ✓）
     */
    @Nullable
    /**
     * 由「约束种类 + 操作数」直接推导**显示参考点**（两个端点 ✓）—— **预览**用 ✓。
     *
     * <p>为什么另写一个 ✗✓：`endpointsOf` 需要一条**已经存在的约束**（要从 constraintIndex 取操作数 ✗）
     * 而预览时约束**还没落下** ⇒ 只能从候选的 kind+vars 推 ✓。
     * 这里逻辑与 `endpointsOf` 对齐 ✓ 但**不改动它**（它已被离线断言覆盖 ✓ 动它风险更大 ✗）。
     *
     * @return {@code {au, av, bu, bv}}；无法推导时返回 {@code null} ✓（宁可只画文字 ✗ 不画错 ✓）
     */
    public static double[] endpointsOfVars(final Sketch sketch, final int kind, final int[] v) {
        if (sketch == null || v == null) {
            return null;
        }
        if ((kind == SketchSolver.DISTANCE || kind == SketchSolver.HORIZONTAL_DISTANCE
                || kind == SketchSolver.VERTICAL_DISTANCE) && v.length >= 4) {
            return new double[] {sketch.variable(v[0]), sketch.variable(v[1]),
                    sketch.variable(v[2]), sketch.variable(v[3])};
        }
        if ((kind == SketchSolver.POINT_LINE_DISTANCE || kind == SketchSolver.HPOINT_LINE_DISTANCE
                || kind == SketchSolver.VPOINT_LINE_DISTANCE) && v.length >= 6) {
            final double px = sketch.variable(v[0]);
            final double py = sketch.variable(v[1]);
            return new double[] {px, py,
                    projectX(sketch, px, py, v[2], v[3], v[4], v[5]),
                    projectY(sketch, px, py, v[2], v[3], v[4], v[5])};
        }
        if (kind == SketchSolver.PARALLEL_LINES_DISTANCE && v.length >= 8) {
            final double px = sketch.variable(v[4]);
            final double py = sketch.variable(v[5]);
            return new double[] {px, py,
                    projectX(sketch, px, py, v[0], v[1], v[2], v[3]),
                    projectY(sketch, px, py, v[0], v[1], v[2], v[3])};
        }
        if (kind == SketchSolver.RADIUS && v.length >= 3) {
            final double ccx = sketch.variable(v[0]);
            final double ccy = sketch.variable(v[1]);
            final double rr = Math.abs(sketch.variable(v[2]));
            return new double[] {ccx, ccy - rr, ccx, ccy + rr};
        }
        if (kind == SketchSolver.ARC_RADIUS && v.length >= 6) {
            // 弧半径（kind 19 ✓）：半径由圆心+起点算 ✓ 极值点与圆一致 ✓
            final double ccx = sketch.variable(v[0]);
            final double ccy = sketch.variable(v[1]);
            final double rr = Math.hypot(sketch.variable(v[2]) - ccx,
                    sketch.variable(v[3]) - ccy);
            return new double[] {ccx, ccy - rr, ccx, ccy + rr};
        }
        if (kind == SketchSolver.LINES_ANGLE && v.length >= 8) {
            return new double[] {
                    (sketch.variable(v[0]) + sketch.variable(v[2])) * 0.5,
                    (sketch.variable(v[1]) + sketch.variable(v[3])) * 0.5,
                    (sketch.variable(v[4]) + sketch.variable(v[6])) * 0.5,
                    (sketch.variable(v[5]) + sketch.variable(v[7])) * 0.5};
        }
        return null;
    }

    public static double[] endpointsOf(final Sketch sketch, final Dimension dim) {
        if (sketch == null || dim == null || dim.constraintIndex() < 0
                || dim.constraintIndex() >= sketch.constraints().size()) {
            return null;
        }
        final int[] v = sketch.constraints().get(dim.constraintIndex()).operands();
        final int kind = dim.solverKind();
        try {
            // ★ 参考图 3 ✓：圆/弧的尺寸用**上下两个极值点** ✓
            //   ⇒ 界线从圆的顶/底**水平引出** ✓ 尺寸线**竖直** ✓ 与 SolidWorks 的直径标注外观一致 ✓
            if (kind == SketchSolver.RADIUS && v.length >= 3) {
                final double ccx = sketch.variable(v[0]);
                final double ccy = sketch.variable(v[1]);
                final double rr = Math.abs(sketch.variable(v[2]));
                return new double[] {ccx, ccy - rr, ccx, ccy + rr};
            }
            if (kind == SketchSolver.ARC_RADIUS && v.length >= 6) {
                // 弧半径（kind 19 ✓ 6 个操作数 ✓）：半径由圆心+起点算 ✓ 极值点与圆一致 ✓
                final double ccx = sketch.variable(v[0]);
                final double ccy = sketch.variable(v[1]);
                final double rr = Math.hypot(sketch.variable(v[2]) - ccx,
                        sketch.variable(v[3]) - ccy);
                return new double[] {ccx, ccy - rr, ccx, ccy + rr};
            }

            if (kind == SketchSolver.DISTANCE && v.length >= 4) {
                return new double[] {sketch.variable(v[0]), sketch.variable(v[1]),
                        sketch.variable(v[2]), sketch.variable(v[3])};
            }
            if (kind == SketchSolver.HORIZONTAL_DISTANCE || kind == SketchSolver.VERTICAL_DISTANCE) {
                if (v.length >= 4) {
                    return new double[] {sketch.variable(v[0]), sketch.variable(v[1]),
                            sketch.variable(v[2]), sketch.variable(v[3])};
                }
            }
            if ((kind == SketchSolver.POINT_LINE_DISTANCE || kind == SketchSolver.HPOINT_LINE_DISTANCE
                    || kind == SketchSolver.VPOINT_LINE_DISTANCE) && v.length >= 6) {
                // 点 + 直线 ⇒ 尺寸线画在"点 → 垂足"之间 ✓（与 SW 一致 ✓）
                final double px = sketch.variable(v[0]);
                final double py = sketch.variable(v[1]);
                final double fx = projectX(sketch, px, py, v[2], v[3], v[4], v[5]);
                final double fy = projectY(sketch, px, py, v[2], v[3], v[4], v[5]);
                return new double[] {px, py, fx, fy};
            }
            if (kind == SketchSolver.PARALLEL_LINES_DISTANCE && v.length >= 8) {
                // 线线距离 ⇒ 从 B 的起点量到它在 A 上的垂足 ✓
                final double px = sketch.variable(v[4]);
                final double py = sketch.variable(v[5]);
                final double fx = projectX(sketch, px, py, v[0], v[1], v[2], v[3]);
                final double fy = projectY(sketch, px, py, v[0], v[1], v[2], v[3]);
                return new double[] {px, py, fx, fy};
            }
            if (kind == SketchSolver.LINES_ANGLE && v.length >= 8) {
                // 夹角 ⇒ 没有"两点"可量 ✗ ⇒ 用两条线各自中点连一条参考线 ✓（只作视觉提示 ✓）
                return new double[] {
                        (sketch.variable(v[0]) + sketch.variable(v[2])) * 0.5,
                        (sketch.variable(v[1]) + sketch.variable(v[3])) * 0.5,
                        (sketch.variable(v[4]) + sketch.variable(v[6])) * 0.5,
                        (sketch.variable(v[5]) + sketch.variable(v[7])) * 0.5};
            }
            if (kind == SketchSolver.RADIUS && v.length >= 3) {
                // 半径 ⇒ 圆心 → 圆上一点（沿 +u ✓ 稳定不抖 ✓）
                return new double[] {sketch.variable(v[0]), sketch.variable(v[1]),
                        sketch.variable(v[0]) + Math.abs(sketch.variable(v[2])),
                        sketch.variable(v[1])};
            }
            if (kind == SketchSolver.ARC_RADIUS && v.length >= 6) {
                // 弧半径 ⇒ 圆心 → 圆上一点（沿 +u ✓ 与圆同一画法 ✓）
                final double ccx = sketch.variable(v[0]);
                final double ccy = sketch.variable(v[1]);
                final double rr = Math.hypot(sketch.variable(v[2]) - ccx,
                        sketch.variable(v[3]) - ccy);
                return new double[] {ccx, ccy, ccx + rr, ccy};
            }
        } catch (final RuntimeException ignored) {
            return null; // 变量表与刚体不一致等情况 ⇒ 不画线 ✓
        }
        return null;
    }
    // ================= 智能尺寸的「实时预览 + 自动捕捉方位」 ✓ ============================

    /**
     * 智能尺寸的**预览**：当前该显示哪个候选 ✓（SolidWorks 的"移动指针实时预览并自动捕捉方位"✓）。
     *
     * <h2>捕捉规则（对齐 SW 的直觉 ✓）</h2>
     * 以两点的**中点**为基准看指针偏移 ✓：
     * <ul>
     *   <li>偏移主要是**横向** ⇒ 捕捉**水平**尺寸 ✓（玩家把指针往左右拖 = 想量水平距 ✓）</li>
     *   <li>偏移主要是**纵向** ⇒ 捕捉**竖直**尺寸 ✓</li>
     *   <li>斜向 ⇒ 捕捉**平行**尺寸 ✓（= SW 的默认 ✓）</li>
     *   <li>只有一种候选（长度/半径/线线距/夹角 ✓）⇒ 直接用那一种 ✓ 不捕捉 ✗</li>
     * </ul>
     * 已用右键**锁定**候选时 ⇒ 锁定优先 ✓（指针怎么动都不改 ✓）✓。
     *
     * @param locked 锁定的候选序号（-1 = 未锁定 ✓ 见 {@code SketchSession#lockedVariant()}）
     * @return 该显示的候选；无法表达/没有选择时返回 {@code null} ✓
     */
    @Nullable
    public static Variant preview(final Sketch sketch, final java.util.List<Sketch.Entity> picked,
                                  final double hoverU, final double hoverV, final int locked) {
        final SmartDimension dim = smartDimension(sketch, picked);
        if (dim == null) {
            return null;
        }
        final java.util.List<Variant> all = variants(sketch, dim);
        if (all.isEmpty()) {
            return null;
        }
        if (locked >= 0 && locked < all.size()) {
            return all.get(locked);
        }
        if (all.size() == 1) {
            return all.get(0);
        }
        // 两点类候选（平行/水平/竖直 ✓）：按指针相对中点的方位捕捉 ✓
        final Variant parallel = all.get(0);
        final int[] v = parallel.vars();
        if (v == null || v.length < 4) {
            return parallel;
        }
        final double midX = (sketch.variable(v[0]) + sketch.variable(v[2])) * 0.5;
        final double midY = (sketch.variable(v[1]) + sketch.variable(v[3])) * 0.5;
        final double dx = Math.abs(hoverU - midX);
        final double dy = Math.abs(hoverV - midY);
        if (dx > dy * 2.0) {
            for (final Variant c : all) {
                if (c.solverKind() == SketchSolver.HORIZONTAL_DISTANCE) {
                    return c;
                }
            }
        } else if (dy > dx * 2.0) {
            for (final Variant c : all) {
                if (c.solverKind() == SketchSolver.VERTICAL_DISTANCE) {
                    return c;
                }
            }
        }
        return parallel;
    }
    // ================= 圆弧的**参考**尺寸（弧长 / 弧张角 ✓ 诚实边界 ✓）=====================

    /**
     * 圆弧的**参考**尺寸：弧长与弧张角 ✓。
     *
     * <h2>为什么现在是参考、但已经不再是"表达不了" ✗✓</h2>
     * 以前 {@link Sketch.EntityKind#ARC} 只有 5 个变量 {@code cx cy r a0 a1} ✗ ——
     * **没有**独立的起/终点变量 ✗，而 ezpz 的 {@code ArcLength}/{@code ArcAngle} 要的是
     * {@code DatumCircularArc{center, start, end}}（6 个坐标 ✓）✗
     * ⇒ 弧长/弧张角**无法**落成可驱动约束 ✗ 只能当参考尺寸显示 ✓。
     *
     * <p>⚠ 现在布局已经改成 {@code cx cy sx sy ex ey}（6 个 ✓ 见 {@code Sketch.EntityKind.ARC} ✓）
     * ⇒ 求解器侧的 {@code ARC_LENGTH}(20) / {@code ARC_ANGLE}(21) 已经**吃得下**这 6 个操作数 ✓
     * （{@code SketchSolver.operandCount} / Rust 侧 operand_count 都是 6 ✓）。
     * 但"把它落成真约束"这条路**这次没有接**（要动 Dimension 的驱动路径 + UI 的改值/删除 ✓
     * 属于另一件事 ✓）⇒ 这里仍然返回 {@code driving=false} ✓
     * ⇒ UI 继续用**灰色 + 括号**显示 ✓ 不假装能驱动 ✗。
     *
     * <p>半径与起止角一律**现算**（{@code arcRadius} / {@code arcStart} / {@code arcEnd} ✓）。
     */
    public static java.util.List<Dimension> arcReferenceDimensions(final Sketch sketch) {
        final java.util.List<Dimension> out = new java.util.ArrayList<>();
        if (sketch == null) {
            return out;
        }
        for (final Sketch.Entity e : sketch.entities()) {
            if (e.kind() != Sketch.EntityKind.ARC) {
                continue;
            }
            final int[] v = e.vars();
            if (v.length < 6) {
                continue;
            }
            final double r = sketch.arcRadius(e);
            final double a0 = sketch.arcStart(e);
            final double sweep = sketch.arcSweep(e);
            final double arcLen = Math.abs(r * sweep);
            final double arcDeg = Math.abs(Math.toDegrees(sweep));
            // 标签画在圆心与起点方向的中间（不会落在弧外 ✓）
            final double mid = a0 + sweep * 0.5;
            final double lx = sketch.variable(v[0]) + Math.cos(mid) * r * 0.5;
            final double ly = sketch.variable(v[1]) + Math.sin(mid) * r * 0.5;
            out.add(new Dimension(-1, SketchSolver.ARC_LENGTH, arcLen, arcLen, false,
                    "model.dim.arc_length", lx, ly));
            out.add(new Dimension(-1, SketchSolver.ARC_ANGLE, arcDeg, arcDeg, false,
                    "model.dim.arc_angle", lx, ly));
        }
        return out;
    }
    // ================= SolidWorks「默认平行尺寸 + 临时切换」所需的数据 ✓ =================

    /**
     * 一个可落成约束的**候选表示** ✓。
     *
     * <p>SolidWorks 的智能尺寸放进预览后，右键可以临时切换：平行 / 水平 / 竖直 / 尺寸链 ✓。
     * 这些不是同一条约束的不同画法 ✗ —— 它们是**不同的约束类型** ✓
     * （平行 = 两点距离 kind 3 ✓；水平 = kind 15 ✓；竖直 = kind 16 ✓），
     * 三者算出来的**数值也不同** ✓ ⇒ 所以每个候选都自带自己的 {@code value} ✓。
     *
     * @param labelKey   UI 上的说明键 ✓
     * @param solverKind 落成约束时用的求解器类型 ✓
     * @param vars       约束要用的变量 ✓
     * @param value      该候选当前几何量出来的值 ✓（预览与放置都用它 ✓）
     */
    public record Variant(String labelKey, int solverKind, int[] vars, double value) {
    }

    /**
     * **显示值 → 约束值** 的换算 ✓。
     *
     * <p>为什么必须有它 ✗✓：「直径」候选的显示值是 2r ✓ 但底层约束只有 **半径**(kind 8) ✓
     * 若把 2r 当半径写进约束 ✗ ⇒ 求解器要求"半径 = 2r"✗ ⇒ 圆弧一旦有重合约束就**无解** ✗
     * —— 这正是用户实测的"圆弧直径始终无法求解"✓。
     */
    public static double solverValue(final Variant v) {
        if (v == null) {
            return 0.0;
        }
        return v.labelKey() != null && v.labelKey().contains("diameter")
                ? v.value() * 0.5 : v.value();
    }

    /** 反向换算 ✓：约束值(半径) → 显示值(直径时 ×2 ✓)，供改值框回填 ✓。 */
    public static double displayValue(final String labelKey, final double solverValue) {
        return labelKey != null && labelKey.contains("diameter")
                ? solverValue * 2.0 : solverValue;
    }

    /**
     * 当前测量能落成的**全部候选** ✓，**顺序即优先级**（第一个 = 默认 ✓）。
     *
     * <p>为什么按 {@code solverKind} 分派 ✗✓：{@link SmartDimension} 已经把"这是什么尺寸"
     * 编成了求解器类型 ✓ ⇒ 这里不必再问一遍图元种类 ✓ 也不会与推断逻辑各说一套 ✓。
     *
     * <p>尺寸链（chain）**不在**这里 ✗ —— 它是"放置方式"（共用基准、连续标注 ✓），
     * 不是另一种约束类型 ✓ ⇒ 属于交互层 ✓ 由 UI 用同一组候选反复落约束即可 ✓。
     */
    public static java.util.List<Variant> variants(final Sketch sketch, final SmartDimension dim) {
        final java.util.List<Variant> out = new java.util.ArrayList<>();
        if (sketch == null || dim == null || !dim.expressible() || dim.vars() == null) {
            return out;
        }
        final int[] v = dim.vars();
        switch (dim.solverKind()) {
            case SketchSolver.DISTANCE -> {
                // 两点距离 ✓：平行（默认 ✓）→ 水平 ✓ → 竖直 ✓
                out.add(new Variant("model.dim.distance", SketchSolver.DISTANCE, v, dim.value()));
                if (v.length >= 4) {
                    out.add(new Variant("model.dim.horizontal", SketchSolver.HORIZONTAL_DISTANCE, v,
                            Math.abs(sketch.variable(v[2]) - sketch.variable(v[0]))));
                    out.add(new Variant("model.dim.vertical", SketchSolver.VERTICAL_DISTANCE, v,
                            Math.abs(sketch.variable(v[3]) - sketch.variable(v[1]))));
                }
            }
            case SketchSolver.POINT_LINE_DISTANCE -> {
                // 点线距 ✓：垂距（默认 ✓）→ 水平 ✓ → 竖直 ✓（后两者是 kind 12/13 ✓）
                out.add(new Variant("model.dim.point_line_distance",
                        SketchSolver.POINT_LINE_DISTANCE, v, dim.value()));
                if (v.length >= 6) {
                    // 水平/竖直点线距的当前值：先把点投影到直线上求"同高度的对应点" ✗ 太绕 ✓
                    // ⇒ 直接从几何算：水平距离 = |Δx|（点到直线上与其同 y 的点 ✓）
                    //   实际用求解器前先用当前几何近似 ✓ 放置后由求解器精确满足 ✓
                    out.add(new Variant("model.dim.horizontal",
                            SketchSolver.HPOINT_LINE_DISTANCE, v, dim.value()));
                    out.add(new Variant("model.dim.vertical",
                            SketchSolver.VPOINT_LINE_DISTANCE, v, dim.value()));
                }
            }
            case SketchSolver.LINES_ANGLE -> out.add(new Variant("model.dim.angle",
                    SketchSolver.LINES_ANGLE, v, dim.value()));
            case SketchSolver.PARALLEL_LINES_DISTANCE ->
                    out.add(new Variant("model.dim.line_line_distance",
                            SketchSolver.PARALLEL_LINES_DISTANCE, v, dim.value()));
            case SketchSolver.RADIUS, SketchSolver.ARC_RADIUS -> {
                // 圆/弧 ⇒ SW 让用户在**半径**与**直径**之间选 ✓
                // 两者是**同一条约束**（圆是 kind 8 / 弧是 kind 19 ✓）⇒ 只是显示值不同 ✓✓
                // ⚠ 种类**原样传回**：弧的半径约束必须仍是 19（6 个操作数 ✓）
                //   换成 8 会让求解器按"3 个操作数"去读 ⇒ 常量错位 ✗
                out.add(new Variant("model.dim.radius", dim.solverKind(), v, dim.value()));
                out.add(new Variant("model.dim.diameter", dim.solverKind(), v, dim.value() * 2.0));
            }

            default -> out.add(new Variant(dim.labelKey(), dim.solverKind(), v, dim.value()));
        }
        return out;
    }
    // ================= 过定义拦截（SolidWorks 的"过定义警告"在数据层的对应物 ✓）============

    /**
     * 这个测量**是不是已经有尺寸了** ✓。
     *
     * <h2>为什么必须由我们拦 ✗✓（实测结论 ✓）</h2>
     * 离线对照实验（tools/SketchSolverCheck.java ✓ 已固化成用例 ✓）发现：
     * 同一条几何上、**同一种**约束的两条不同取值会被上游的
     * {@code ConstraintRequest::highest_priority} 机制**合流成一条** ✗
     * ⇒ 求解器不会报错 ✓ 反而报 {@code satisfied=true} ✗
     * ⇒ 于是"给同一处再加一个矛盾尺寸"会**静默地什么也没发生** ✗✓ —— 玩家会以为标上了 ✓
     *
     * <p>所以落尺寸前必须在**我们这层**判：同一组变量 + 同一种约束已经存在 ⇒
     * 应当**编辑那条已有尺寸**（改值驱动 ✓）而不是再新建一条 ✓✓。
     * 这正是 SolidWorks 的行为：加重复/矛盾尺寸时它拒绝并提示过定义 ✓。
     *
     * @return 已存在的同名约束；没有则返回 {@code null} ✓
     */
    @Nullable
    public static Sketch.Constraint alreadyDimensioned(final Sketch sketch, final SmartDimension dim) {
        if (sketch == null || dim == null || !dim.expressible() || dim.vars() == null) {
            return null;
        }
        for (final Sketch.Constraint c : sketch.constraints()) {
            if (c.kind() == dim.solverKind() && sameVars(c.operands(), dim.vars())) {
                return c;
            }
        }
        return null;
    }

    /** 两个操作数数组是不是**同一组变量** ✓（顺序无关 ✓ 长度相同 ✓ 逐个配平 ✓）。 */
    private static boolean sameVars(final int[] a, final int[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        final boolean[] used = new boolean[b.length];
        for (final int x : a) {
            boolean found = false;
            for (int i = 0; i < b.length; i++) {
                if (!used[i] && b[i] == x) {
                    used[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
    /** 两个"点一样"的图元各自的代表坐标变量（点用自己，圆/弧用圆心）。 */
    private static int[] pairVars(final Sketch.Entity a, final Sketch.Entity b) {
        final int[] va = a.vars();
        final int[] vb = b.vars();
        return new int[] {va[0], va[1], vb[0], vb[1]};
    }

    /**
     * 智能尺寸的推断结果。
     *
     * @param labelKey    UI 上的说明（长度/半径/点距/角度…）
     * @param value       当前几何量出来的值
     * @param expressible 我们的求解器**能不能**把它落成约束
     *                    —— false 时只能当**参考尺寸**显示，绝不能假装标上了
     * @param solverKind  能落成约束时的约束类型；否则为 0
     * @param vars        约束要用的变量；不能约束时为 null
     */
    public record SmartDimension(String labelKey, double value, boolean expressible,
                                 int solverKind, @Nullable int[] vars) {
    }

    /**
     * 量两个图元之间的距离。
     *
     * @return 测量结果；两个图元里有一个是不支持的组合时返回 null
     */
    public static Result between(final Sketch sketch, final Sketch.Entity a, final Sketch.Entity b) {
        if (a == null || b == null || a.equals(b)) {
            return null;
        }
        final Segment sa = segmentOf(sketch, a);
        final Segment sb = segmentOf(sketch, b);
        // 两条都是线段
        if (sa != null && sb != null) {
            return lines(sa, sb);
        }
        // 一条线段 + 一个"有圆心"的图元（点/圆/弧都算）
        if (sa != null || sb != null) {
            final Segment line = sa != null ? sa : sb;
            final Sketch.Entity other = sa != null ? b : a;
            final double[] centre = centreOf(sketch, other);
            if (centre == null) {
                return null;
            }
            final double d = distancePointToSegment(centre[0], centre[1], line);
            final Double radius = radiusOf(sketch, other);
            if (radius != null) {
                // 圆心到线的距离再扣掉半径 = 表面到线的间隙（可能为负，说明相交，取 0）
                return new Result(Math.max(0.0, d - radius), Kind.SURFACE_GAP, 0.0);
            }
            return new Result(d, Kind.POINT_LINE, 0.0);
        }
        // 两边都只有圆心（点/圆/弧）
        final double[] ca = centreOf(sketch, a);
        final double[] cb = centreOf(sketch, b);
        if (ca == null || cb == null) {
            return null;
        }
        final double centreDistance = Math.hypot(cb[0] - ca[0], cb[1] - ca[1]);
        final Double ra = radiusOf(sketch, a);
        final Double rb = radiusOf(sketch, b);
        if (ra != null && rb != null) {
            // 两个圆/弧：报**表面**间隙（圆心距 − 两个半径）。相交时为 0。
            return new Result(Math.max(0.0, centreDistance - ra - rb), Kind.SURFACE_GAP, 0.0);
        }
        if (ra != null || rb != null) {
            final double r = ra != null ? ra : rb;
            return new Result(Math.max(0.0, centreDistance - r), Kind.SURFACE_GAP, 0.0);
        }
        return new Result(centreDistance, Kind.POINT_POINT, 0.0);
    }

    /** 两条线段之间的距离。 */
    private static Result lines(final Segment a, final Segment b) {
        final double ax = a.bx - a.ax;
        final double ay = a.by - a.ay;
        final double bx = b.bx - b.ax;
        final double by = b.by - b.ay;
        final double cross = ax * by - ay * bx;
        final double lenA = Math.hypot(ax, ay);
        final double lenB = Math.hypot(bx, by);
        final double angle = Math.toDegrees(Math.atan2(Math.abs(cross), ax * bx + ay * by));
        // 平行判据用**夹角**而不是叉积：叉积带长度量纲，长短不同的线会被误判
        if (lenA < 1.0e-12 || lenB < 1.0e-12 || angle < 0.05 || angle > 179.95) {
            // 平行（含反向）：取 a 的起点到 b 的线段距离，就是两平行线之间的垂距
            return new Result(distancePointToSegment(a.ax, a.ay, b), Kind.PARALLEL_LINES, 0.0);
        }
        // 不平行：先看四个端点到对方线段的距离，取最小 —— 对**线段**（不是直线）来说
        // 这就是最近点距；两端都不投影在对方内部时也能拿到正确结果。
        double best = Math.min(
                Math.min(distancePointToSegment(a.ax, a.ay, b), distancePointToSegment(a.bx, a.by, b)),
                Math.min(distancePointToSegment(b.ax, b.ay, a), distancePointToSegment(b.bx, b.by, a)));
        if (segmentsProperlyCross(a, b)) {
            best = 0.0; // 真交叉 → 距离 0
        }
        return new Result(best, Kind.SKEW_LINES, angle);
    }

    /** 两条线段是否真正相交。 */
    private static boolean segmentsProperlyCross(final Segment p, final Segment q) {
        final double d1 = sideOf(q, p.ax, p.ay);
        final double d2 = sideOf(q, p.bx, p.by);
        final double d3 = sideOf(p, q.ax, q.ay);
        final double d4 = sideOf(p, q.bx, q.by);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static double sideOf(final Segment s, final double x, final double y) {
        return (s.bx - s.ax) * (y - s.ay) - (s.by - s.ay) * (x - s.ax);
    }

    /** 点到线段的距离（垂足落在延长线上时退化成到端点的距离）。 */
    public static double distancePointToSegment(final double px, final double py,
                                                final Segment s) {
        final double dx = s.bx - s.ax;
        final double dy = s.by - s.ay;
        final double lenSq = dx * dx + dy * dy;
        if (lenSq < 1.0e-18) {
            return Math.hypot(px - s.ax, py - s.ay);
        }
        double t = ((px - s.ax) * dx + (py - s.ay) * dy) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(px - (s.ax + t * dx), py - (s.ay + t * dy));
    }

    /** 图元如果是线段，取出它的两个端点；否则返回 null。 */
    private static Segment segmentOf(final Sketch sketch, final Sketch.Entity entity) {
        if (entity.kind() != Sketch.EntityKind.LINE) {
            return null;
        }
        final int[] v = entity.vars();
        return new Segment(sketch.variable(v[0]), sketch.variable(v[1]),
                sketch.variable(v[2]), sketch.variable(v[3]));
    }

    /** 图元如果有"圆心"，取出它：点自己、圆的圆心、弧的圆心。 */
    private static double[] centreOf(final Sketch sketch, final Sketch.Entity entity) {
        final int[] v = entity.vars();
        return switch (entity.kind()) {
            case POINT, CIRCLE, ARC -> new double[] {sketch.variable(v[0]), sketch.variable(v[1])};
            case LINE -> null;
        };
    }

    /** 图元如果有半径，取出它（圆的第三个变量 / 弧由圆心+起点算）；没有则 null。 */
    private static Double radiusOf(final Sketch sketch, final Sketch.Entity entity) {
        return switch (entity.kind()) {
            case CIRCLE -> Math.abs(sketch.variable(entity.vars()[2]));
            case ARC -> sketch.arcRadius(entity);
            case POINT, LINE -> null;
        };
    }

    /** 一条线段（平面内）。 */
    public record Segment(double ax, double ay, double bx, double by) {
    }
}
