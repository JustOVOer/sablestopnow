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
                        sketch.variable(v[0]) + r, sketch.variable(v[1])));
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
                case CIRCLE, ARC -> {
                    if (hasRadiusOn(constraints, v[2])) {
                        continue;
                    }
                    final double cx = sketch.variable(v[0]);
                    final double cy = sketch.variable(v[1]);
                    final double r = Math.abs(sketch.variable(v[2]));
                    final boolean isCircle = e.kind() == Sketch.EntityKind.CIRCLE;
                    // 圆按**直径**报（SolidWorks 的圆默认就是直径），弧按半径
                    out.add(new Dimension(-1, 0,
                            isCircle ? r * 2.0 : r, isCircle ? r * 2.0 : r, false,
                            isCircle ? "model.dim.diameter" : "model.dim.radius",
                            cx + r, cy));
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

    /** 半径变量上是否已经有 RADIUS 约束。 */
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
                        Math.abs(sketch.variable(v[2])), true, SketchSolver.RADIUS,
                        new int[] {v[0], v[1], v[2]});
                case POINT -> null; // 单个点没有尺寸（它的位置由别的尺寸决定）
            };
        }
        // --- 两个图元：复用已有的测量 + 可约束判定 ---
        final Sketch.Entity a = picked.get(0);
        final Sketch.Entity b = picked.get(1);
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

    /** 图元如果有半径，取出它（圆的第三个变量 / 弧的第三个变量）；没有则 null。 */
    private static Double radiusOf(final Sketch sketch, final Sketch.Entity entity) {
        return switch (entity.kind()) {
            case CIRCLE, ARC -> Math.abs(sketch.variable(entity.vars()[2]));
            case POINT, LINE -> null;
        };
    }

    /** 一条线段（平面内）。 */
    public record Segment(double ax, double ay, double bx, double by) {
    }
}
