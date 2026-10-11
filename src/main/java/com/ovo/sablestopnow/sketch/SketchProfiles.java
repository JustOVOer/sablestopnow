package com.ovo.sablestopnow.sketch;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 从草图的图元里求**闭合轮廓** —— 拉伸的地基。
 *
 * <h2>为什么这一步单独做</h2>
 * 拉伸要的是"一个封闭区域"，而草图里只有一堆线段和圆。这中间隔着一层拓扑问题：
 * 哪些线段首尾相接成了闭环、环的走向是顺还是逆、有没有自交。
 * 这一层全部是纯几何，可以完全离线钉住；先把它做扎实，拉伸那边就只剩"给定轮廓生成几何"。
 *
 * <h2>做法：按端点连通性串联</h2>
 * 把每条线段的两个端点按容差**焊接**成节点，然后从任一条没走过的边出发，
 * 一路找"共用同一个节点"的下一条边，直到回到起点（闭环）或走不下去（开链）。
 *
 * <p>这比"求平面图的环基"朴素得多，但对草图这个场景够用：玩家画的轮廓本来就该
 * 是一条首尾相接的链，而不是需要做图论分解的复杂网络。
 *
 * <p>⚠ 只依赖 JOML，可用 {@code tools/SketchModelCheck.java} 验证。
 */
public final class SketchProfiles {

    /** 默认焊接容差（格）：端点相距小于它就当成同一个点。 */
    public static final double DEFAULT_WELD_TOLERANCE = 1.0e-4;

    /**
     * 一条闭合轮廓。
     *
     * @param points     按顺序排列的顶点（首尾不重复，即最后一点与第一点相连）
     * @param signedArea 有符号面积：正 = 逆时针，负 = 顺时针。
     *                   <b>拉伸时必须知道方向</b>，否则生成的面会里外翻转。
     */
    public record Profile(List<double[]> points, double signedArea) {

        public Profile {
            points = List.copyOf(points);
        }

        /** 顶点数。 */
        public int size() {
            return points.size();
        }

        public boolean counterClockwise() {
            return signedArea > 0.0;
        }

        /** 顶点是否近似共线（面积接近 0）—— 这种轮廓拉伸出来是零体积的薄片，要拒掉。 */
        public boolean degenerate() {
            return Math.abs(signedArea) < 1.0e-9;
        }
    }

    private SketchProfiles() {
    }

    /**
     * 采样精度：圆与弧各用多少段折线近似。
     *
     * <h2>为什么渲染和碰撞要分开</h2>
     * 碰撞是**一个盒子 = 一个刚体 + 一条约束**（Sable 只暴露盒体碰撞），
     * 而每条扫描带出一个盒子 —— 48 段的圆就是 48 个刚体 + 48 条约束。
     * 渲染要好看（48 段几乎看不出是折线），碰撞却不需要那么细：
     * 12 段的圆对碰撞来说已经足够，刚体数直接降到 1/4。
     *
     * <p>这正是本模组一直在用的「渲染 ≠ 碰撞」分离：同一个轮廓，
     * 渲染按 RENDER 采样、碰撞按 COLLISION 采样，各取所需。
     */
    public record Sampling(int circleSegments, int arcSegmentsPerTurn, double maxSagitta) {

        /** 渲染用：够细，看不出折线。 */
        public static final Sampling RENDER = new Sampling(48, 32, 0.01);

        /**
         * 碰撞用：在**绝对误差上界**约束下尽量少段。
         *
         * <p>⚠ 不能简单固定成 12 段：折线的"弓高"（弦到弧的最大距离）是
         * {@code r·(1 − cos(π/n))} —— 与**半径成正比**。
         * r=3 时 12 段的误差是 0.10 格（可接受），r=20 时是 0.68 格（明显偏差）。
         * 所以按半径算最少段数，让误差始终不超过 {@link #maxSagitta} 格。
         */
        public static final Sampling COLLISION = new Sampling(8, 8, 0.08);

        public Sampling {
            if (circleSegments < 3 || arcSegmentsPerTurn < 2) {
                throw new IllegalArgumentException("采样段数太少，圆/弧会退化成多边形");
            }
            if (maxSagitta < 0.0) {
                throw new IllegalArgumentException("弓高上界不能为负");
            }
        }

        /**
         * 半径为 {@code radius} 的圆，应当用多少段折线。
         *
         * <p>解 {@code r·(1 − cos(π/n)) ≤ maxSagitta} 得
         * {@code n ≥ π / acos(1 − maxSagitta/r)}。半径很小（或上界很松）时用 {@link #circleSegments} 兜底。
         */
        public int segmentsFor(final double radius) {
            if (radius <= 1.0e-9 || maxSagitta <= 1.0e-9 || radius <= maxSagitta) {
                return circleSegments;
            }
            final double cos = Math.max(-1.0, 1.0 - maxSagitta / radius);
            final double n = Math.PI / Math.acos(cos);
            return Math.max(circleSegments, (int) Math.ceil(n));
        }
    }

    /**
     * 求草图里所有的闭合轮廓。
     *
     * <p>圆单独成环（它本身就是闭合的），线段则按端点串联。
     * 开链（没接上的线段）**不算轮廓** —— 它们拉伸不出实体，应该由 UI 提示玩家。
     */
    public static List<Profile> findLoops(final Sketch sketch) {
        return findLoops(sketch, DEFAULT_WELD_TOLERANCE, Sampling.RENDER);
    }

    public static List<Profile> findLoops(final Sketch sketch, final double weldTolerance) {
        return findLoops(sketch, weldTolerance, Sampling.RENDER);
    }

    /** 按指定采样精度求闭合轮廓（碰撞走 {@link Sampling#COLLISION}）。 */
    public static List<Profile> findLoops(final Sketch sketch, final double weldTolerance,
                                          final Sampling sampling) {
        final List<Profile> out = new ArrayList<>();
        // 圆直接就是一个环
        for (final Sketch.Entity entity : sketch.entities()) {
            if (sketch.isConstruction(entity)) { continue; } // 辅助线**不参与轮廓/拉伸** ✓（SolidWorks 一致 ✓）
            if (entity.kind() == Sketch.EntityKind.CIRCLE) {
                out.add(circleProfile(sketch, entity, sampling));
            }
        }
        out.addAll(lineProfiles(sketch, weldTolerance, sampling));
        return out;
    }

    /** 圆：用折线近似成闭合轮廓（段数由 {@link Sampling} 决定）。 */
    private static Profile circleProfile(final Sketch sketch, final Sketch.Entity circle,
                                         final Sampling sampling) {
        final int[] v = circle.vars();
        final double cx = sketch.variable(v[0]);
        final double cy = sketch.variable(v[1]);
        final double r = Math.abs(sketch.variable(v[2]));
        final int segments = sampling.segmentsFor(r);
        final List<double[]> points = new ArrayList<>(segments);
        for (int i = 0; i < segments; i++) {
            final double a = 2.0 * Math.PI * i / segments;
            points.add(new double[] {cx + r * Math.cos(a), cy + r * Math.sin(a)});
        }
        return new Profile(points, signedArea(points));
    }

    /**
     * 把**线段与圆弧**串成闭环。
     *
     * <p>每条边只走一次；走到某条边的另一端时找下一条共用该端点的边。
     * 回到起点即闭环；找不到下一条就是开链（丢弃）。
     *
     * <h2>弧和线段的两点不同</h2>
     * <ol>
     *   <li><b>方向不能靠交换端点下标来反转。</b>线段反转就是把 (a,b) 换成 (b,a)，
     *       而弧的扫向是写死的（永远从 a0 逆时针到 a1）—— 所以这里改用
     *       {@code forward} 标志表示"这条边是按 0→1 还是 1→0 走"。</li>
     *   <li><b>弧要按采样折线进链。</b>只往链里放两个端点的话，弧会退化成一条**弦**，
     *       面积、三角化、盒分解全都错。</li>
     * </ol>
     */
    private static List<Profile> lineProfiles(final Sketch sketch, final double tolerance,
                                              final Sampling sampling) {
        final List<Sketch.Entity> edges = new ArrayList<>();
        for (final Sketch.Entity entity : sketch.entities()) {
            if (sketch.isConstruction(entity)) { continue; } // 辅助线**不参与轮廓/拉伸** ✓（SolidWorks 一致 ✓）
            // 弧有两个端点，和线段一样参与端点焊接
            if (entity.kind() == Sketch.EntityKind.LINE || entity.kind() == Sketch.EntityKind.ARC) {
                edges.add(entity);
            }
        }
        final boolean[] used = new boolean[edges.size()];
        final List<Profile> out = new ArrayList<>();
        for (int start = 0; start < edges.size(); start++) {
            if (used[start]) {
                continue;
            }
            final List<double[]> chain = new ArrayList<>();
            int current = start;
            boolean forward = true;
            double[] currentPoint = endOf(sketch, edges.get(start), forward, false);
            final double[] startPoint = currentPoint;
            boolean closed = false;
            while (true) {
                used[current] = true;
                // 把这条边从"进入点"到"离开点"的采样点追加进链（进入点已经在链里了）
                appendEdge(sketch, chain, edges.get(current), forward, sampling);
                final double[] next = endOf(sketch, edges.get(current), forward, true);
                // 回到起点 → 闭环。（>= 3 而不是 >= 4：一段弧加两条线就是合法的封闭形状）
                if (near(next, startPoint, tolerance) && chain.size() >= 3) {
                    closed = true;
                    break;
                }
                // 找下一条共用这个端点的边
                int found = -1;
                boolean foundForward = true;
                for (int i = 0; i < edges.size(); i++) {
                    if (used[i]) {
                        continue;
                    }
                    if (near(endOf(sketch, edges.get(i), true, false), next, tolerance)) {
                        found = i;
                        foundForward = true;
                        break;
                    }
                    if (near(endOf(sketch, edges.get(i), true, true), next, tolerance)) {
                        found = i;
                        foundForward = false;
                        break;
                    }
                }
                if (found < 0) {
                    break; // 开链
                }
                current = found;
                // 从 next 出发 → 若 next 是那条边的起点则正向走，否则反向走
                forward = foundForward;
                currentPoint = next;
            }
            if (closed) {
                out.add(new Profile(chain, signedArea(chain)));
            }
        }
        return out;
    }

    /** 一条边在参数 {@code t ∈ [0,1]} 处的点：线段线性插值，弧按角度扫。 */
    public static double[] pointOn(final Sketch sketch, final Sketch.Entity edge, final double t) {
        if (edge.kind() == Sketch.EntityKind.ARC) {
            return sketch.arcPoint(edge, t);
        }
        final int[] v = edge.vars();
        final double ax = sketch.variable(v[0]);
        final double ay = sketch.variable(v[1]);
        final double bx = sketch.variable(v[2]);
        final double by = sketch.variable(v[3]);
        return new double[] {ax + (bx - ax) * t, ay + (by - ay) * t};
    }

    /**
     * 一条边的两个端点之一。
     *
     * @param forward   true → 取 t=0 那端；false → 取 t=1 那端（{@code start=true} 时反过来）
     * @param otherEnd  false → 取"进入点"，true → 取"离开点"
     */
    private static double[] endOf(final Sketch sketch, final Sketch.Entity edge,
                                  final boolean forward, final boolean otherEnd) {
        final boolean atEnd = forward == otherEnd;
        return pointOn(sketch, edge, atEnd ? 1.0 : 0.0);
    }

    /** 把一条边从进入点到离开点的采样点追加进链（**不含**进入点本身，它已在链里）。 */
    private static void appendEdge(final Sketch sketch, final List<double[]> chain,
                                   final Sketch.Entity edge, final boolean forward,
                                   final Sampling sampling) {
        final int segments = edge.kind() == Sketch.EntityKind.ARC
                ? arcSegments(sketch, edge, sampling) : 1;
        for (int i = 1; i <= segments; i++) {
            final double t = (double) i / segments;
            final double[] p = pointOn(sketch, edge, forward ? t : 1.0 - t);
            if (p != null) {
                chain.add(p);
            }
        }
    }

    /** 弧的采样段数：整圈段数按半径自适应，再按扫过的角度缩放。 */
    private static int arcSegments(final Sketch sketch, final Sketch.Entity arc,
                                   final Sampling sampling) {
        final int[] v = arc.vars();
        final double sweep = Sketch.sweep(sketch.variable(v[3]), sketch.variable(v[4]));
        final double radius = Math.abs(sketch.variable(v[2]));
        final int perTurn = Math.max(sampling.arcSegmentsPerTurn(), sampling.segmentsFor(radius));
        return Math.max(3, (int) Math.ceil(perTurn * sweep / (2.0 * Math.PI)));
    }

    private static boolean near(final double[] a, final double[] b, final double tolerance) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]) <= tolerance;
    }

    /**
     * 多边形的有符号面积（鞋带公式）。
     *
     * <p>正 = 逆时针。拉伸时必须知道方向 —— 方向反了生成的面会里外翻转，
     * 表现为"从外面看不见、从里面才看得见"。
     */
    /** 区域的**净面积**（外圈减掉所有孔），恒非负。 */
    public static double netArea(final Region region) {
        double a = Math.abs(region.outer().signedArea());
        for (final Profile hole : region.holes()) {
            a -= Math.abs(hole.signedArea());
        }
        return Math.max(a, 0.0);
    }

    /**
     * 多边形的**形心**（面积加权，不是顶点平均）。
     *
     * <p>⚠ 形心 ≠ 顶点平均：回转体的体积（帕普斯定理）用的是**形心**到轴的距离，
     * 拿顶点平均去代会让体积偏掉 —— 顶点在曲率大的地方更密，平均值会被拽向那边。
     * 面积为零（退化）时退回顶点平均，至少给个有限值。
     */
    public static double[] centroid(final List<double[]> points) {
        final int n = points.size();
        if (n == 0) {
            return new double[] {0.0, 0.0};
        }
        double twiceArea = 0.0;
        double cx = 0.0;
        double cy = 0.0;
        for (int i = 0; i < n; i++) {
            final double[] p = points.get(i);
            final double[] q = points.get((i + 1) % n);
            final double cross = p[0] * q[1] - q[0] * p[1];
            twiceArea += cross;
            cx += (p[0] + q[0]) * cross;
            cy += (p[1] + q[1]) * cross;
        }
        if (Math.abs(twiceArea) < 1.0e-12) {
            double sx = 0.0;
            double sy = 0.0;
            for (final double[] p : points) {
                sx += p[0];
                sy += p[1];
            }
            return new double[] {sx / n, sy / n};
        }
        return new double[] {cx / (3.0 * twiceArea), cy / (3.0 * twiceArea)};
    }

    /** 多边形有符号面积：正 = 逆时针，负 = 顺时针。 */
    public static double signedArea(final List<double[]> points) {
        double sum = 0.0;
        for (int i = 0; i < points.size(); i++) {
            final double[] a = points.get(i);
            final double[] b = points.get((i + 1) % points.size());
            sum += a[0] * b[1] - b[0] * a[1];
        }
        return sum * 0.5;
    }

    /** 轮廓是否自交（拉伸自交的轮廓会得到自穿的实体，必须拒掉）。 */
    public static boolean selfIntersects(final Profile profile) {
        final List<double[]> p = profile.points();
        final int n = p.size();
        for (int i = 0; i < n; i++) {
            for (int j = i + 2; j < n; j++) {
                // 相邻边共端点，跳过
                if (i == 0 && j == n - 1) {
                    continue;
                }
                if (segmentsCross(p.get(i), p.get((i + 1) % n),
                        p.get(j), p.get((j + 1) % n))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean segmentsCross(final double[] a, final double[] b,
                                         final double[] c, final double[] d) {
        final double d1 = cross(c, d, a);
        final double d2 = cross(c, d, b);
        final double d3 = cross(a, b, c);
        final double d4 = cross(a, b, d);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0))
                && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static double cross(final double[] o, final double[] a, final double[] b) {
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    }

    /**
     * 把一个轮廓**分解成一堆轴对齐矩形**（平面内），供拉伸生成盒子用。
     *
     * <h2>为什么是盒子</h2>
     * Sable 的物理碰撞只吃盒子（{@code VoxelColliderData} 只有 {@code addBox(min,max)}，
     * 没有任何多边形入口），所以"精确轮廓碰撞"唯一的达成方式就是**把轮廓分解成盒子集合**。
     * 好处是这套结果**渲染与碰撞共用**：渲染从轮廓直接生成面（不受盒子影响、边缘是精确的），
     * 碰撞用盒子的并集（凹多边形与孔都天然成立）。
     *
     * <h2>做法：按轮廓自身的 v 坐标切带</h2>
     * 取轮廓所有不同的 v 值作为带边界，每个带取中间高度扫一条水平线，
     * 求出该带内在多边形内部的 x 区间，每个区间出一个盒子、纵向撑满整个带。
     *
     * <p>对**边只有水平与竖直**的轮廓（矩形、L 形、带孔的矩形 —— 也就是玩家实际会画的东西），
     * 这是**完全精确**的分解，不是近似。斜边则精确到带的分辨率：带数等于不同 v 值的个数，
     * 所以斜线越多分得越细。
     *
     * @return 每个盒子是 {@code {minU, minV, maxU, maxV}}
     */
    public static List<double[]> toBoxes(final Profile profile) {
        // 委托给区域版本，**不要**在这里再写一份扫描线 ——
        // 两份实现一定会漂移，而漂移的表现是"独环和带孔环分解得不一样"。
        return toBoxes(new Region(profile, List.of()));
    }

    /**
     * 水平线 {@code v = y} 与多边形的交：返回若干 {@code {xStart, xEnd}} 区间（在内部的部分）。
     *
     * <p>用**奇偶规则**（射线法）：从左往右扫过所有与这条水平线相交的边，
     * 交点排序后两两配对就是内部区间。孔洞天然成立 —— 孔会把一个区间劈成两个。
     */
    private static List<double[]> spansAt(final List<double[]> points, final double y) {
        return spansAtRings(List.of(points), y);
    }

    /**
     * 同上，但可以一次喂**多个环**（外圈 + 它的孔）。
     *
     * <p>⚠ 为什么不能把外圈和孔的顶点**串成一个表**再调用单环版本：那样
     * {@code (i+1)%n} 会凭空造出两条连接两个环的假边，它们在扫描线上产生虚假交点，
     * 于是孔的判定就错了。必须按环分别遍历真实的边。
     */
    private static List<double[]> spansAtRings(final List<List<double[]>> rings, final double y) {
        final List<Double> crossings = new ArrayList<>();
        for (final List<double[]> points : rings) {
            final int n = points.size();
            for (int i = 0; i < n; i++) {
                final double[] a = points.get(i);
                final double[] b = points.get((i + 1) % n);
                // 半开区间判定（[min, max) ）：避免顶点正好落在扫描线上时被算两次
                final double minY = Math.min(a[1], b[1]);
                final double maxY = Math.max(a[1], b[1]);
                if (y < minY || y >= maxY) {
                    continue;
                }
                final double t = (y - a[1]) / (b[1] - a[1]);
                crossings.add(a[0] + t * (b[0] - a[0]));
            }
        }
        java.util.Collections.sort(crossings);
        final List<double[]> spans = new ArrayList<>();
        for (int i = 0; i + 1 < crossings.size(); i += 2) {
            final double x0 = crossings.get(i);
            final double x1 = crossings.get(i + 1);
            if (x1 - x0 > 1.0e-9) {
                spans.add(new double[] {x0, x1});
            }
        }
        return spans;
    }

    /**
     * 一个环内部的一点（保证在内部，不是包围盒中心 —— 凹多边形的中心可能在外部）。
     *
     * <p>取"穿过环中部的那条扫描线上的第一个区间的中点"：它按定义就在环的内部。
     * 用途是判断包含关系（{@link #nest}），必须在内部取点，否则凹轮廓会被判错父子。
     */
    @Nullable
    public static double[] interiorPoint(final Profile profile) {
        final List<double[]> points = profile.points();
        if (points.size() < 3) {
            return null;
        }
        double minV = Double.MAX_VALUE;
        double maxV = -Double.MAX_VALUE;
        for (final double[] p : points) {
            minV = Math.min(minV, p[1]);
            maxV = Math.max(maxV, p[1]);
        }
        final List<double[]> spans = spansAt(points, (minV + maxV) * 0.5);
        if (spans.isEmpty()) {
            return null;
        }
        final double[] first = spans.get(0);
        return new double[] {(first[0] + first[1]) * 0.5, (minV + maxV) * 0.5};
    }

    /** 点是否在多边形内部（射线法，奇偶规则）。边界上的判定未定义，调用方别拿边界点来问。 */
    public static boolean containsPoint(final Profile profile, final double x, final double y) {
        return containsPoint(profile.points(), x, y);
    }

    static boolean containsPoint(final List<double[]> points, final double x, final double y) {
        final int n = points.size();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            final double[] a = points.get(i);
            final double[] b = points.get(j);
            if ((a[1] > y) != (b[1] > y)
                    && x < (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]) + a[0]) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * 一个**区域**：一条外轮廓 + 它内部的所有孔。
     *
     * <p>拉伸必须以区域为单位，不能以轮廓为单位 —— 否则孔会被当成实体填实。
     */
    public record Region(Profile outer, List<Profile> holes) {

        public Region {
            holes = List.copyOf(holes);
        }

        /** 该区域的净面积（外圈减孔）。 */
        public double area() {
            double sum = Math.abs(outer.signedArea());
            for (final Profile hole : holes) {
                sum -= Math.abs(hole.signedArea());
            }
            return sum;
        }
    }

    /**
     * 把一堆互不相交的闭合轮廓按**包含关系**组织成区域（外圈 + 孔）。
     *
     * <p>判定用**嵌套深度**的奇偶：深度 0 是实体，深度 1 是孔，深度 2 是孔里的岛（又是实体）。
     * 每个环的深度 = 有多少个别的环包含它。孔挂到"包含它的、深度恰好小 1 的"那个环上。
     *
     * <p>这样"外框里挖个圆"会得到一个 {@code Region(外框, [圆])}，
     * 而不是两个各自被拉成实体的轮廓（那是把孔填实）。
     */
    public static List<Region> nest(final List<Profile> loops) {
        final List<Profile> valid = new ArrayList<>();
        for (final Profile loop : loops) {
            if (!loop.degenerate()) {
                valid.add(loop);
            }
        }
        final int n = valid.size();
        // 每个环取一个内部点，包含判断都基于它
        final double[][] inside = new double[n][];
        for (int i = 0; i < n; i++) {
            inside[i] = interiorPoint(valid.get(i));
        }
        final int[] depth = new int[n];
        for (int i = 0; i < n; i++) {
            if (inside[i] == null) {
                continue;
            }
            for (int j = 0; j < n; j++) {
                if (i == j || inside[j] == null) {
                    continue;
                }
                if (contains(valid.get(j), valid.get(i), inside[i])) {
                    depth[i]++;
                }
            }
        }
        // 偶数深度 = 实体；它的直接孔 = 深度恰好大 1 且被它包含的环
        final List<Region> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (depth[i] % 2 != 0 || inside[i] == null) {
                continue;
            }
            final List<Profile> holes = new ArrayList<>();
            for (int j = 0; j < n; j++) {
                if (depth[j] != depth[i] + 1 || inside[j] == null) {
                    continue;
                }
                if (contains(valid.get(i), valid.get(j), inside[j])) {
                    holes.add(valid.get(j));
                }
            }
            out.add(new Region(valid.get(i), holes));
        }
        return out;
    }

    /**
     * {@code outer} 是否真的包住 {@code inner}。
     *
     * <p>⚠ 光靠"inner 的一个内部点在 outer 里"是**不够的**，会得出互相包含的荒谬结论：
     * 外框 10×10 里挖一个圆心在 (5,5) 的圆时，外框自己的内部点也正好取在 (5,5)，
     * 于是外框被判成"在圆内"，两个环深度都是 1，谁都不成区域 —— 孔和实体一起消失。
     *
     * <p>所以用**面积仲裁**：大的才可能是小的的父亲。两个互不相交的环之间必定只有一个方向
     * 同时满足"面积更大"和"点在内部"，这就唯一确定了父子关系。
     */
    private static boolean contains(final Profile outer, final Profile inner, final double[] point) {
        if (Math.abs(outer.signedArea()) <= Math.abs(inner.signedArea())) {
            return false;
        }
        return containsPoint(outer, point[0], point[1]);
    }

    /**
     * 把一个**区域**（外圈 + 孔）分解成轴对齐矩形。
     *
     * <p>与单环版本唯一的区别：扫描线一次喂进外圈和所有孔，孔自然会把区间劈开。
     * 这就是"孔要精确碰撞"的落点 —— 之前按**轮廓**分解再全部加成实体，
     * 孔会被填实（注释写着支持带孔矩形，但 {@code findLoops} 把孔返回成独立轮廓，
     * 两者根本没接上）。
     */
    public static List<double[]> toBoxes(final Region region) {
        final List<double[]> out = new ArrayList<>();
        final List<List<double[]>> rings = new ArrayList<>();
        rings.add(region.outer().points());
        for (final Profile hole : region.holes()) {
            rings.add(hole.points());
        }
        // 带边界取**所有环**的 v：只用外圈的 v 会让斜孔的分段太粗
        final List<Double> vs = new ArrayList<>();
        for (final List<double[]> ring : rings) {
            for (final double[] p : ring) {
                vs.add(p[1]);
            }
        }
        final double[] sorted = vs.stream().mapToDouble(Double::doubleValue).distinct().sorted()
                .toArray();
        for (int i = 0; i + 1 < sorted.length; i++) {
            final double v0 = sorted[i];
            final double v1 = sorted[i + 1];
            if (v1 - v0 < 1.0e-9) {
                continue;
            }
            // 在带的**中点**取一次跨度，撑满整条带。
            //
            // ⚠ 这里试过"取两端跨度的交集"（想做成零多余覆盖的精确内接），**结果更糟**：
            // 带内的跨度是**横向平移**的，取交集只留下重叠部分，丢失量是**一阶**的 ——
            // 实测 r=3 时漏掉 15%（碰撞体明显比渲染出来小），比中点采样的误差大一个数量级。
            //
            // 中点采样在"宽度随 v 线性变化"时恰好是**单盒的最优折中**：
            // 多覆盖与漏覆盖上下对称、面积最小。实测 r=3 两侧各约 3.6%，
            // 且随半径增大**占比下降**（r=50 时各约 0.9%）。
            // 想进一步减小误差的正确手段是**加密采样**（见 Sampling），不是改取样位置。
            final double midV = (v0 + v1) * 0.5;
            for (final double[] span : spansAtRings(rings, midV)) {
                out.add(new double[] {span[0], v0, span[1], v1});
            }
        }
        return out;
    }

    // ==================================================================
    // 分解质量度量 —— "精度够不够"必须用数字回答，不能凭感觉
    // ==================================================================

    /**
     * 盒分解相对于真实轮廓的**覆盖误差**（按面积比例）。
     *
     * @param regionArea    区域净面积
     * @param uncoveredArea 在区域内、但**没有任何盒子盖住**的面积（碰撞会"漏"）
     * @param excessArea    在盒子内、但**在区域外**的面积（碰撞会"多"—— 玩家会撞到空气）
     */
    public record Coverage(double regionArea, double uncoveredArea, double excessArea) {

        /** 漏掉的面积占比。碰撞偏小：能从本该挡住的地方穿过去。 */
        public double uncoveredFraction() {
            return regionArea <= 1.0e-12 ? 0.0 : uncoveredArea / regionArea;
        }

        /** 多出来的面积占比。碰撞偏大：会撞到看不见的东西（比漏更让人恼火）。 */
        public double excessFraction() {
            return regionArea <= 1.0e-12 ? 0.0 : excessArea / regionArea;
        }

        /** 最大偏差的**等效线性尺度**（把误差面积摊成一条边长的量级）。 */
        public double linearError() {
            final double bboxSpan = Math.sqrt(Math.max(regionArea, 1.0e-12));
            return Math.max(uncoveredArea, excessArea) / bboxSpan;
        }
    }

    /**
     * 用规则网格采样，量出盒分解相对区域漏了多少、多了多少。
     *
     * <p>为什么要有这个东西：用户问过"大型圆柱按固定分解方式精度够不够"。
     * 这类问题**必须用数字回答** —— 光看"段数够不够多"是说不清的，
     * 而且半径一变结论就可能翻转。有了这个度量，任何分解方案（轴对齐、斜盒子、
     * 以后换别的算法）都能放在同一把尺子上比。
     *
     * <p>⚠ 过采样（{@code excessArea}）在碰撞里比漏更糟：玩家会撞到空气。
     * 所以断言里对 excess 的要求应当比 uncovered 更严。
     *
     * @param boxes 平面盒子 {@code {minU,minV,maxU,maxV}}（{@link #toBoxes} 的输出）
     * @param grid  每边的采样格数（越大越准，O(grid²)）
     */
    public static Coverage coverage(final Region region, final List<double[]> boxes,
                                    final int grid) {
        final double[] b = boundsOf(region);
        if (b == null || grid <= 0) {
            return new Coverage(0.0, 0.0, 0.0);
        }
        final double du = (b[2] - b[0]) / grid;
        final double dv = (b[3] - b[1]) / grid;
        final double cell = du * dv;
        double inside = 0.0;
        double uncovered = 0.0;
        double excess = 0.0;
        for (int i = 0; i < grid; i++) {
            for (int j = 0; j < grid; j++) {
                final double u = b[0] + (i + 0.5) * du;
                final double v = b[1] + (j + 0.5) * dv;
                final boolean inRegion = contains(region, u, v);
                final boolean inBoxes = inAnyBox(boxes, u, v);
                if (inRegion) {
                    inside += cell;
                    if (!inBoxes) {
                        uncovered += cell;
                    }
                } else if (inBoxes) {
                    excess += cell;
                }
            }
        }
        return new Coverage(inside, uncovered, excess);
    }

    /** 区域（外圈内、且不在任何孔里）是否包含该点。 */
    public static boolean contains(final Region region, final double u, final double v) {
        if (!containsPoint(region.outer(), u, v)) {
            return false;
        }
        for (final Profile hole : region.holes()) {
            if (containsPoint(hole, u, v)) {
                return false;
            }
        }
        return true;
    }

    private static boolean inAnyBox(final List<double[]> boxes, final double u, final double v) {
        for (final double[] box : boxes) {
            if (u >= box[0] && u <= box[2] && v >= box[1] && v <= box[3]) {
                return true;
            }
        }
        return false;
    }

    /** 区域（含孔）的包围盒 {@code {minU,minV,maxU,maxV}}。 */
    @Nullable
    public static double[] boundsOf(final Region region) {
        double minU = Double.MAX_VALUE;
        double minV = Double.MAX_VALUE;
        double maxU = -Double.MAX_VALUE;
        double maxV = -Double.MAX_VALUE;
        for (final double[] p : region.outer().points()) {
            minU = Math.min(minU, p[0]);
            minV = Math.min(minV, p[1]);
            maxU = Math.max(maxU, p[0]);
            maxV = Math.max(maxV, p[1]);
        }
        if (minU > maxU) {
            return null;
        }
        return new double[] {minU, minV, maxU, maxV};
    }

    // ==================================================================
    // 三角化（拉伸体顶/底面用；材质贴面必须要有三角面与 UV）
    // ==================================================================

    /**
     * 把一个区域（外圈 + 孔）三角化成三角形列表。
     *
     * <h2>为什么需要它</h2>
     * 渲染棱柱的**侧面**是平凡的（每条环边拉出一个四边形），
     * 但**顶/底面**是一个可能带孔、可能凹的多边形，图形 API 只吃三角形。
     * 而且这一层是"用方块材质贴面"的前置：贴图需要 UV，UV 需要面片。
     *
     * <h2>做法：孔桥接 + 耳切</h2>
     * 先把每个孔用一条"桥"缝进外圈，拼成一个**简单多边形**（孔变成一条缝），
     * 再对这个简单多边形做耳切（ear clipping）。
     * 桥的选取：取孔上 u 最大的顶点 M，找外圈上**可见**（连线不与任何边相交、且中点在外圈内）
     * 且距离最近的顶点 P，把孔环从 M 处剪开插到 P 后面。
     *
     * @return 每个三角形是 3 个 {@code {u,v}}；区域退化时返回空表
     */
    public static List<double[][]> triangulate(final Region region) {
        final List<double[]> outer = orient(region.outer().points(), true);
        if (outer.size() < 3) {
            return List.of();
        }
        // 孔按 u 最大者优先桥接（自右向左），这是标准做法，能避免桥互相穿过
        final List<List<double[]>> holes = new ArrayList<>();
        for (final Profile hole : region.holes()) {
            final List<double[]> ring = orient(hole.points(), false); // 孔取顺时针
            if (ring.size() >= 3) {
                holes.add(ring);
            }
        }
        holes.sort(java.util.Comparator.comparingDouble(SketchProfiles::maxU).reversed());

        List<double[]> poly = new ArrayList<>(outer);
        for (final List<double[]> hole : holes) {
            poly = bridgeHole(poly, hole);
            if (poly.size() > 4000) {
                return List.of(); // 桥接失控（不该发生）——宁可不出面，也不要卡死
            }
        }
        return earClip(poly);
    }

    /** 统一环的走向：{@code ccw=true} 要逆时针。 */
    private static List<double[]> orient(final List<double[]> ring, final boolean ccw) {
        final List<double[]> out = new ArrayList<>(ring);
        if ((signedArea(out) > 0.0) != ccw) {
            java.util.Collections.reverse(out);
        }
        return out;
    }

    private static double maxU(final List<double[]> ring) {
        double m = -Double.MAX_VALUE;
        for (final double[] p : ring) {
            m = Math.max(m, p[0]);
        }
        return m;
    }

    /** 把孔缝进外圈（返回新的简单多边形；顶点会有两处重复，这是桥接的正常形态）。 */
    private static List<double[]> bridgeHole(final List<double[]> poly, final List<double[]> hole) {
        // 孔上 u 最大的顶点
        int mi = 0;
        for (int i = 1; i < hole.size(); i++) {
            if (hole.get(i)[0] > hole.get(mi)[0]) {
                mi = i;
            }
        }
        final double[] m = hole.get(mi);
        // 外圈上可见且最近的顶点
        int best = -1;
        double bestDist = Double.MAX_VALUE;
        for (int pi = 0; pi < poly.size(); pi++) {
            final double[] p = poly.get(pi);
            if (!visible(poly, hole, p, m)) {
                continue;
            }
            final double dist = Math.hypot(p[0] - m[0], p[1] - m[1]);
            if (dist < bestDist) {
                bestDist = dist;
                best = pi;
            }
        }
        if (best < 0) {
            return poly; // 找不到桥就放弃这个孔（宁可少挖一块，也不要造出一个坏多边形）
        }
        final List<double[]> out = new ArrayList<>(poly.size() + hole.size() + 2);
        out.addAll(poly.subList(0, best + 1));
        for (int k = 0; k < hole.size(); k++) {
            out.add(hole.get((mi + k) % hole.size()));
        }
        out.add(m); // 回到孔的起点，把缝封上
        out.addAll(poly.subList(best, poly.size()));
        return out;
    }

    /** 线段 {@code a-b} 是否可见：不与任何环的边真正相交，且中点落在外圈内部。 */
    private static boolean visible(final List<double[]> poly, final List<double[]> hole,
                                   final double[] a, final double[] b) {
        for (int i = 0; i < poly.size(); i++) {
            if (crosses(a, b, poly.get(i), poly.get((i + 1) % poly.size()))) {
                return false;
            }
        }
        for (int i = 0; i < hole.size(); i++) {
            if (crosses(a, b, hole.get(i), hole.get((i + 1) % hole.size()))) {
                return false;
            }
        }
        return containsPoint(poly, (a[0] + b[0]) * 0.5, (a[1] + b[1]) * 0.5);
    }

    /**
     * 两条线段是否**真正**相交（共端点不算）。
     *
     * <p>桥接必须用"真正相交"：桥的端点一定与某条边共端点，
     * 用普通相交判定的话每条候选桥都会被自己的邻边否掉，一个桥也找不到。
     */
    private static boolean crosses(final double[] p1, final double[] p2,
                                   final double[] q1, final double[] q2) {
        if (same(p1, q1) || same(p1, q2) || same(p2, q1) || same(p2, q2)) {
            return false;
        }
        return segmentsCross(p1, p2, q1, q2);
    }

    private static boolean same(final double[] a, final double[] b) {
        return Math.abs(a[0] - b[0]) < 1.0e-9 && Math.abs(a[1] - b[1]) < 1.0e-9;
    }

    /** 耳切：反复找一个"凸且不含其它顶点"的耳朵切下来。 */
    private static List<double[][]> earClip(final List<double[]> poly) {
        final List<double[][]> out = new ArrayList<>();
        final List<double[]> v = new ArrayList<>(poly);
        int guard = 0;
        while (v.size() > 3) {
            boolean clipped = false;
            for (int i = 0; i < v.size(); i++) {
                final double[] prev = v.get((i + v.size() - 1) % v.size());
                final double[] cur = v.get(i);
                final double[] next = v.get((i + 1) % v.size());
                if (cross(prev, cur, next) <= 1.0e-12) {
                    continue; // 凹点或退化点，不是耳朵
                }
                boolean clear = true;
                for (int j = 0; j < v.size(); j++) {
                    if (j == i || v.get(j) == prev || v.get(j) == cur || v.get(j) == next) {
                        continue;
                    }
                    if (inTriangle(v.get(j), prev, cur, next)) {
                        clear = false;
                        break;
                    }
                }
                if (!clear) {
                    continue;
                }
                out.add(new double[][] {prev, cur, next});
                v.remove(i);
                clipped = true;
                break;
            }
            if (!clipped) {
                // 自交或数值退化：停在这里而不是死循环。
                // 已经切出来的那些三角形仍然可用（少一块面好过卡死客户端）。
                return out;
            }
            if (++guard > 100000) {
                return out;
            }
        }
        if (v.size() == 3) {
            out.add(new double[][] {v.get(0), v.get(1), v.get(2)});
        }
        return out;
    }

    private static boolean inTriangle(final double[] p, final double[] a,
                                      final double[] b, final double[] c) {
        final double d1 = cross(a, b, p);
        final double d2 = cross(b, c, p);
        final double d3 = cross(c, a, p);
        final boolean neg = d1 < -1.0e-12 || d2 < -1.0e-12 || d3 < -1.0e-12;
        final boolean pos = d1 > 1.0e-12 || d2 > 1.0e-12 || d3 > 1.0e-12;
        return !(neg && pos);
    }

    /** 轮廓的包围盒（拉伸/碰撞的粗筛用）。 */
    @Nullable
    public static double[] bounds(final Profile profile) {
        if (profile.points().isEmpty()) {
            return null;
        }
        double minU = Double.MAX_VALUE;
        double minV = Double.MAX_VALUE;
        double maxU = -Double.MAX_VALUE;
        double maxV = -Double.MAX_VALUE;
        for (final double[] p : profile.points()) {
            minU = Math.min(minU, p[0]);
            maxU = Math.max(maxU, p[0]);
            minV = Math.min(minV, p[1]);
            maxV = Math.max(maxV, p[1]);
        }
        return new double[] {minU, minV, maxU, maxV};
    }

    /** 轮廓顶点换算到子结构 plot 局部系（留给拉伸用）。 */
    public static List<Vector3d> toLocal(final Sketch sketch, final Profile profile) {
        final List<Vector3d> out = new ArrayList<>(profile.size());
        for (final double[] p : profile.points()) {
            out.add(sketch.plane().toLocal(p[0], p[1]));
        }
        return out;
    }
}
