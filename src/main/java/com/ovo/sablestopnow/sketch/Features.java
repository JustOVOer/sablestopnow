package com.ovo.sablestopnow.sketch;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * **从模型重建几何特征**（面 / 棱 / 顶点）✓ —— 设计见
 * {@code docs/几何特征重建与基准面草图.md}。
 *
 * <p>为什么从**网格**重建而不是从参数化形状：切割（CSG）之后已经没有参数化真相了 ✗
 * ⇒ 统一从 {@link Solid.Placed#mesh()} 走一条路，参数化实体与切割实体都能覆盖 ✓
 * （网格本身就是从参数化生成的 ✓ 面也是精确的 ✓）
 *
 * <h2>算法（紧凑版）</h2>
 * <ol>
 *   <li><b>焊接</b>：位置按 1e-4 量化成 key ⇒ 顶点下标（CSG 会吐出重复顶点 ✓ 不焊会全碎）</li>
 *   <li><b>邻接</b>：把每条边归一成 {@code (min,max)} ⇒ 挂上用它的一到两个三角形</li>
 *   <li><b>共面生长</b>：先按"法线 + 平面偏移"分桶，再在同桶内用共享边做**连通分量** ⇒ 一个面 ✓
 *       （只分桶不做连通会把"两个平行的面"糊成一个 ✗）</li>
 *   <li><b>边界环</b>：只被本面用一次的边 ⇒ 串成环 ⇒ 合并共线 ⇒ **直线边**（参考线就取这些 ✓）</li>
 *   <li>非平面（顶点到平面偏差超容差）⇒ {@link Kind#CURVED}，**不产参考线** ✓</li>
 * </ol>
 *
 * <p>缓存按 {@link Solid.Placed} **实例**（模型一变就换实例 ⇒ 自动失效 ✓ 不用手动清）。
 */
public final class Features {

    private Features() {
    }

    /** 面的平面性。 */
    public enum Kind {
        /** 平面 ✓ 可以当草图基准面、可以产参考线 ✓ */
        PLANAR,
        /** 曲面（圆柱/斜条带…）✗ 不能当基准面、不产参考线 ✓ */
        CURVED,
    }

    /**
     * 一个面 ✓。
     *
     * @param triangles 组成这个面的三角形（每个 3 个顶点，**局部坐标** ✓ 渲染高亮直接用）
     * @param segments  面上的**直线边** ✓ {@code {x1,y1,z1,x2,y2,z2}}（参考线取这些）
     * @param origin    面积质心（平面面的基准面原点 ✓）
     * @param normal    面积加权的单位法线 ✓
     */
    public record Face(int id, Kind kind, Vector3d origin, Vector3d normal,
                       List<Vector3d[]> triangles, List<double[]> segments, double area) {

        public boolean planar() {
            return kind == Kind.PLANAR;
        }
    }

    /** 一个实体的特征图 ✓。 */
    public record Graph(java.util.UUID solidId, List<Face> faces,
                        List<Vector3d[]> edges, List<Vector3d> vertices) {
    }

    /**
     * 把**相邻且法线渐变**的平面块合并成**曲面**面 ✓（修"一个曲面识别成多个平面"✗）。
     *
     * <p>判据（2026-10 重做）：
     * <ul>
     *   <li>法线夹角 ≤ 25°（低模圆柱的一个条带也可能到 30°，取 25° 兼顾两者）；</li>
     *   <li>两块共享任一顶点；</li>
     *   <li><b>两块都得是"窄条"或"小面"</b> —— 这是本次修"圆角曲面点不中"的关键：
     *       以前只看法线，圆角条带的第一块与相邻的大平面**法线恰好相切**（夹角 0°），
     *       于是整个实体被合并成一个"曲面"，悬停时轮廓变成整个实体的外轮廓，
     *       圆角那一圈反而没有独立描边；反过来，微面在合并**之前**就被面积阈值删掉，
     *       小半径圆角的条带全是微面 ⇒ 曲面整块从特征图里消失 ⇒ 鼠标移上去毫无反应 ✗。</li>
     * </ul>
     *
     * <p>现在：只合并"窄条/小面"，大平面永远独立；微面先参与合并，合并后再按面积过滤。
     * 圆角/圆柱的条带会合成一整块曲面，边界由**并集的只出现一次的边**重算（不画内部
     * 细分线），悬停与描边都正常 ✓
     */
    private static List<Face> mergeSmooth(final List<Face> faces) {
        final int n = faces.size();
        if (n < 2) {
            return faces;
        }
        final int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        // 每个面的"窄条程度"只算一次：两两比较时不再遍历三角形
        final double[] aspects = new double[n];
        for (int i = 0; i < n; i++) {
            aspects[i] = aspect(faces.get(i));
        }
        // ★ 普通大面的法线（"主导平面"）：**按同法线的总面积**统计，
        //   而不是看单个面 —— 普通面被 CSG 切成碎片后，碎片面积小，但同一法线的
        //   总面积仍然很大 ⇒ 仍能挡住碎片并入圆角曲面（用户实测的"尖三角形泄漏"）。
        //   圆角条带每一小面的法线都不同，各自面积也小 ⇒ 不会被误当成主导平面。
        final Map<String, Vector3d> normalByKey = new HashMap<>();
        final Map<String, Double> areaByKey = new HashMap<>();
        for (final Face face : faces) {
            final String key = quant(face.normal());
            normalByKey.putIfAbsent(key, face.normal());
            areaByKey.merge(key, face.area(), Double::sum);
        }
        final List<Vector3d> dominants = new ArrayList<>();
        for (final Map.Entry<String, Double> e : areaByKey.entrySet()) {
            if (e.getValue() >= DOMINANT_AREA) {
                dominants.add(normalByKey.get(e.getKey()));
            }
        }
        final int[] dominantOf = new int[n];
        for (int i = 0; i < n; i++) {
            dominantOf[i] = dominantIndex(faces.get(i).normal(), dominants);
        }
        // 顶点 → 共用它的面（按 1e-4 量化，和特征图的焊接同一口径）
        final Map<String, List<Integer>> byVertex = new HashMap<>();
        for (int i = 0; i < n; i++) {
            final java.util.Set<String> keys = new java.util.HashSet<>();
            for (final Vector3d[] tri : faces.get(i).triangles()) {
                for (final Vector3d p : tri) {
                    keys.add(quant(p));
                }
            }
            for (final String key : keys) {
                byVertex.computeIfAbsent(key, x -> new ArrayList<>()).add(i);
            }
        }
        // ★ 只合并**至少共享两个顶点（一条边）**的面：只蹭到一个角的面不并
        //   （尖三角形常常只是搭在一个顶点上，以前按"共享任一顶点"就把它并进来了）。
        final Map<Long, int[]> pairCount = new HashMap<>();
        for (final List<Integer> around : byVertex.values()) {
            for (int i = 0; i < around.size(); i++) {
                for (int j = i + 1; j < around.size(); j++) {
                    final int a = around.get(i);
                    final int b = around.get(j);
                    final int lo = Math.min(a, b);
                    final int hi = Math.max(a, b);
                    final long key = ((long) lo << 32) | (hi & 0xFFFFFFFFL);
                    pairCount.computeIfAbsent(key, x -> new int[1])[0]++;
                }
            }
        }
        for (final Map.Entry<Long, int[]> e : pairCount.entrySet()) {
            final int a = (int) (e.getKey() >>> 32);
            final int b = (int) (e.getKey() & 0xFFFFFFFFL);
            if (find(parent, a) == find(parent, b)) {
                continue;
            }
            // ★ 普通平面被 CSG 切成多块时（共面、共享一个或多个顶点）要重新并回一整块，
            //   否则悬停会只亮一个三角形/多边形（用户实测"普通平面碎掉了"✗）。
            //   共面合并与曲面合并分开：前者不受"窄条/面积比/主导平面"限制，
            //   但仍然要求共享顶点（不能把两块隔空的共面面糊在一起）。
            if (coplanar(faces.get(a), faces.get(b))) {
                union(parent, a, b);
                continue;
            }
            if (e.getValue()[0] < 2) {
                continue; // 曲面合并只认共享一条边（≥2 个顶点）
            }
            if (canMerge(faces.get(a), faces.get(b), aspects[a], aspects[b],
                    dominantOf[a], dominantOf[b])) {
                union(parent, a, b);
            }
        }
        final Map<Integer, List<Integer>> groups = new HashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), x -> new ArrayList<>()).add(i);
        }
        final List<Face> out = new ArrayList<>();
        for (final List<Integer> g : groups.values()) {
            if (g.size() == 1) {
                out.add(faces.get(g.get(0)));
                continue;
            }
            // 合并：只有"所有法线都一致"才是平面，否则是曲面 ✓
            final List<Vector3d[]> tris = new ArrayList<>();
            final Vector3d nSum = new Vector3d();
            final Vector3d centroid = new Vector3d();
            double area = 0;
            double maxDev = 0;
            for (final int idx : g) {
                final Face f = faces.get(idx);
                tris.addAll(f.triangles());
                nSum.fma(f.area(), f.normal());
                centroid.fma(f.area(), f.origin());
                area += f.area();
            }
            final Vector3d normal = area > 0 ? nSum.normalize(new Vector3d())
                    : new Vector3d(faces.get(g.get(0)).normal());
            final Vector3d origin = area > 0 ? centroid.div(area, new Vector3d()) : new Vector3d();
            final double w0 = normal.dot(origin);
            for (final Vector3d[] tri : tris) {
                for (final Vector3d p : tri) {
                    maxDev = Math.max(maxDev, Math.abs(normal.dot(p) - w0));
                }
            }
            final Kind kind = maxDev < 1.0e-3 ? Kind.PLANAR : Kind.CURVED;
            // ★ 边界 = 并集里**只出现一次**的边（内部细分线不再画进悬停轮廓）
            final List<double[]> segs = new ArrayList<>();
            boundarySegments(tris, segs);
            out.add(new Face(out.size(), kind, origin, normal, tris, segs, area));
        }
        return out;
    }

    /** 两个平面面**严格共面**（同一朝法线 + 同一平面偏移）✓。 */
    private static boolean coplanar(final Face a, final Face b) {
        if (!a.planar() || !b.planar()) {
            return false;
        }
        if (a.normal().dot(b.normal()) < DOMINANT_DOT) {
            return false;
        }
        final double da = a.normal().dot(a.origin());
        final double db = b.normal().dot(b.origin());
        return Math.abs(da - db) < 1.0e-3;
    }

    /** 合并判据：法线近 + 至少一块是窄条/小面 + 尺度相差不大（防止圆角条带吞并大平面）。 */
    private static boolean canMerge(final Face a, final Face b,
                                    final double aspectA, final double aspectB,
                                    final int dominantA, final int dominantB) {
        if (!a.planar() || !b.planar()) {
            return false;
        }
        if (a.normal().dot(b.normal()) < SMOOTH_COS) {
            return false;
        }
        // ★ 只要有一块与某个普通大面严格共面，它就只能和**同一个普通面**合并。
        //   普通面的碎片法线 = 大面法线，而圆角小面相对大面有半个分段角 ⇒ 不会误并。
        if (dominantA != -1 || dominantB != -1) {
            if (dominantA != dominantB) {
                return false;
            }
        }
        final double minArea = Math.min(a.area(), b.area());
        final double maxArea = Math.max(a.area(), b.area());
        if (minArea < 1.0e-9 || maxArea / minArea > SMOOTH_MAX_AREA_RATIO) {
            return false;
        }
        final boolean small = a.area() < SMOOTH_SMALL_AREA && b.area() < SMOOTH_SMALL_AREA;
        final boolean thin = aspectA >= SMOOTH_MIN_ASPECT && aspectB >= SMOOTH_MIN_ASPECT;
        return small || thin;
    }

    /** 法线与某个"普通大面"严格共面时返回其下标；否则 -1。 */
    private static int dominantIndex(final Vector3d normal, final List<Vector3d> dominants) {
        for (int i = 0; i < dominants.size(); i++) {
            if (normal.dot(dominants.get(i)) >= DOMINANT_DOT) {
                return i;
            }
        }
        return -1;
    }

    /** 面的两个最大平面跨度之比（长条 ≫ 1，方块 ≈ 1）。 */
    private static double aspect(final Face face) {
        final double[] ext = extents(face);
        final double lo = Math.min(ext[0], ext[1]);
        final double hi = Math.max(ext[0], ext[1]);
        return lo < 1.0e-9 ? 0.0 : hi / lo;
    }

    /** 面在其平面内的两个方向的跨度 ✓。 */
    private static double[] extents(final Face face) {
        final Vector3d n = new Vector3d(face.normal()).normalize();
        Vector3d u = n.cross(new Vector3d(0, 1, 0), new Vector3d());
        if (u.lengthSquared() < 1.0e-9) {
            u = n.cross(new Vector3d(1, 0, 0), new Vector3d());
        }
        u.normalize();
        final Vector3d v = n.cross(u, new Vector3d()).normalize();
        double minU = Double.MAX_VALUE;
        double maxU = -Double.MAX_VALUE;
        double minV = Double.MAX_VALUE;
        double maxV = -Double.MAX_VALUE;
        for (final Vector3d[] tri : face.triangles()) {
            for (final Vector3d p : tri) {
                final Vector3d d = new Vector3d(p).sub(face.origin());
                final double du = d.dot(u);
                final double dv = d.dot(v);
                minU = Math.min(minU, du);
                maxU = Math.max(maxU, du);
                minV = Math.min(minV, dv);
                maxV = Math.max(maxV, dv);
            }
        }
        if (minU > maxU || minV > maxV) {
            return new double[] {0, 0};
        }
        return new double[] {maxU - minU, maxV - minV};
    }

    /** 由一组三角形的并集重算边界边（只出现一次的边），再合并共线 ✓。 */
    private static void boundarySegments(final List<Vector3d[]> tris, final List<double[]> out) {
        final Map<String, int[]> count = new HashMap<>();
        final Map<String, double[]> coords = new HashMap<>();
        for (final Vector3d[] tri : tris) {
            for (int k = 0; k < 3; k++) {
                final Vector3d a = tri[k];
                final Vector3d b = tri[(k + 1) % 3];
                if (a.distanceSquared(b) < 1.0e-12) {
                    continue;
                }
                final String ka = quant(a);
                final String kb = quant(b);
                final String key = ka.compareTo(kb) <= 0 ? ka + "|" + kb : kb + "|" + ka;
                count.computeIfAbsent(key, x -> new int[1])[0]++;
                coords.putIfAbsent(key, new double[] {a.x, a.y, a.z, b.x, b.y, b.z});
            }
        }
        final List<double[]> raw = new ArrayList<>();
        for (final Map.Entry<String, int[]> e : count.entrySet()) {
            if (e.getValue()[0] != 1) {
                continue;
            }
            final double[] s = coords.get(e.getKey());
            if (s != null) {
                raw.add(s);
            }
        }
        mergeCollinear(raw, out);
    }

    private static int find(final int[] parent, final int i) {
        return parent[i] == i ? i : (parent[i] = find(parent, parent[i]));
    }

    private static void union(final int[] parent, final int a, final int b) {
        final int ra = find(parent, a);
        final int rb = find(parent, b);
        if (ra != rb) {
            parent[rb] = ra;
        }
    }

    /** 面的最小面积（格²）✓ 小于它的当噪声丢掉（圆柱条带、CSG 碎三角形）✗。 */
    public static final double MIN_FACE_AREA = 0.02;

    /** 平滑合并：法线夹角阈值（低模圆柱条带可能接近 25°）。 */
    private static final double SMOOTH_COS = Math.cos(Math.toRadians(25.0));
    /** 小面的面积阈值：两块都小于它 ⇒ 允许合并（球面/小圆角的三角汤）。 */
    private static final double SMOOTH_SMALL_AREA = 0.35;
    /** 长条判据：面内两个跨度的比值 ≥ 2 视为"窄条"（圆角/圆柱条带）。 */
    private static final double SMOOTH_MIN_ASPECT = 2.0;
    /** 面积比上限：避免小圆角条带吞并大平面。 */
    private static final double SMOOTH_MAX_AREA_RATIO = 8.0;
    /**
     * "主导平面"的面积阈值：达到它的原始面被视为普通大面。
     *
     * <p>它的法线用于挡住**普通平面上的碎片**：这些碎片（CSG 产生的尖三角形/细条）
     * 面积可能很小、也可能很窄，光看"窄条/小面"会把它们并进圆角曲面 ⇒ 用户实测
     * "圆角面泄漏，把普通面的一部分当成圆角面"✗。碎片与大平面**严格共面**
     *（法线点积 ≈ 1），而圆角条带的每一小面相对大平面都有半个分段角，所以能区分 ✓
     */
    private static final double DOMINANT_AREA = 0.30;
    /** 判断"严格共面"的点积容差（约 0.08°）。 */
    private static final double DOMINANT_DOT = 1.0 - 1.0e-6;

    private static boolean sameSeg(final Vector3d[] e, final double[] seg) {
        return (Math.abs(e[0].x - seg[0]) < 1.0e-6 && Math.abs(e[0].y - seg[1]) < 1.0e-6
                && Math.abs(e[0].z - seg[2]) < 1.0e-6
                && Math.abs(e[1].x - seg[3]) < 1.0e-6 && Math.abs(e[1].y - seg[4]) < 1.0e-6
                && Math.abs(e[1].z - seg[5]) < 1.0e-6)
                || (Math.abs(e[0].x - seg[3]) < 1.0e-6 && Math.abs(e[0].y - seg[4]) < 1.0e-6
                && Math.abs(e[0].z - seg[5]) < 1.0e-6
                && Math.abs(e[1].x - seg[0]) < 1.0e-6 && Math.abs(e[1].y - seg[1]) < 1.0e-6
                && Math.abs(e[1].z - seg[2]) < 1.0e-6);
    }

    /** 缓存（键是 {@link Solid.Placed} 实例 ⇒ 模型变了键就变了 ✓）。 */
    private static final Map<Solid.Placed, Graph> CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** 取（或建）一个实体的特征图 ✓。 */
    public static Graph of(final Solid.Placed placed) {
        if (placed == null) {
            return EMPTY;
        }
        final Graph cached = CACHE.get(placed);
        if (cached != null) {
            return cached;
        }
        if (FAILED.contains(placed)) {
            return EMPTY;
        }
        // ★ **绝不在渲染线程建图**：大模型（40k 多边形 CSG 网格）的焊接/分桶/合并
        //   会让游戏卡几分钟（用户实测未响应）。这里只入队；建完之前返回空图，
        //   悬停/边框暂时没有，等后台线程完成后下一帧自然出现。
        schedule(placed);
        return EMPTY;
    }

    /** 空图（后台还没建好时用）✓。 */
    private static final Graph EMPTY = new Graph(null, List.of(), List.of(), List.of());

    /** 后台建图线程（单线程：顺序建，避免和渲染抢 CPU、也避免并发改静态缓存）✓。 */
    private static final java.util.concurrent.ExecutorService WORKER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                final Thread t = new Thread(r, "sablestopnow-features");
                t.setDaemon(true);
                return t;
            });

    /** 已入队 / 已失败（按对象身份）✓。 */
    private static final java.util.Set<Solid.Placed> PENDING =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    private static final java.util.Set<Solid.Placed> FAILED =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

    /** 清空失败标记（进入模型视图重试时用）✓。 */
    public static void clearFailures() {
        FAILED.clear();
    }

    private static void schedule(final Solid.Placed placed) {
        if (!PENDING.add(placed)) {
            return;
        }
        WORKER.execute(() -> {
            try {
                buildInto(placed);
            } catch (final Throwable t) {
                FAILED.add(placed);
                System.err.println("[features] background build failed: " + t);
            } finally {
                PENDING.remove(placed);
            }
        });
    }

    /** 真正建图：如果登记过"源图 + 变换"，就复用源图；否则从 placed.mesh 重建。 */
    private static void buildInto(final Solid.Placed placed) {
        if (placed == null || CACHE.get(placed) != null) {
            return;
        }
        final TransformSpec spec = TRANSFORMS.remove(placed);
        final Graph graph;
        if (spec != null) {
            final Graph source = ofMeshCached(spec.sourceId(), spec.mesh());
            graph = transform(source, placed.solid().id(),
                    spec.point(), spec.direction(), spec.areaScale());
        } else {
            graph = build(placed);
        }
        CACHE.put(placed, graph);
    }

    /**
     * 后台把一批实体建好（进入模型视图前调用）✓。
     *
     * <p>返回 future 供调用方在完成后切 UI；建图过程中游戏线程不被占用。
     */
    public static java.util.concurrent.CompletableFuture<Void> prepareAll(
            final java.util.List<Solid.Placed> placedList) {
        final java.util.List<Solid.Placed> snapshot =
                placedList == null ? List.of() : new java.util.ArrayList<>(placedList);
        return java.util.concurrent.CompletableFuture.runAsync(() -> {
            for (final Solid.Placed p : snapshot) {
                if (p == null || CACHE.get(p) != null || FAILED.contains(p)) {
                    continue;
                }
                try {
                    buildInto(p);
                } catch (final Throwable t) {
                    FAILED.add(p);
                    System.err.println("[features] prepare failed: " + t);
                }
            }
        }, WORKER);
    }

    /** 派生副本的图变换登记（源网格 + 变换），由后台线程真正执行 ✓。 */
    private record TransformSpec(java.util.UUID sourceId, SolidMesh.Mesh mesh,
                                 java.util.function.UnaryOperator<Vector3d> point,
                                 java.util.function.UnaryOperator<Vector3d> direction,
                                 double areaScale) {
    }

    private static final Map<Solid.Placed, TransformSpec> TRANSFORMS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * 登记"这个副本的图 = 源图做一次变换"。
     *
     * <p>不在渲染线程立即变换；第一次 {@link #of} 时由后台线程做。
     */
    public static void putTransform(
            final Solid.Placed placed,
            final java.util.UUID sourceId,
            final SolidMesh.Mesh sourceMesh,
            final java.util.function.UnaryOperator<Vector3d> point,
            final java.util.function.UnaryOperator<Vector3d> direction,
            final double areaScale) {
        if (placed == null) {
            return;
        }
        TRANSFORMS.put(placed, new TransformSpec(sourceId, sourceMesh, point, direction, areaScale));
    }

    /**
     * 给**派生特征**（克隆/镜像/阵列）预置一份图 ✓。
     *
     * <p>派生副本的网格是由源几何做刚体变换/等比缩放得到的，拓扑完全一样
     * （哪些三角形属于一个面、哪些边是边界都不变）⇒ 只需要对源图做同一套
     * 坐标变换，不必再对新网格重新焊接/分桶/合并。大网格（如 40k 多边形镜像体）
     * 的创建/加载开销因此从"重建拓扑"降到"变换顶点"。
     */
    public static void put(final Solid.Placed placed, final Graph graph) {
        if (placed != null && graph != null) {
            CACHE.put(placed, graph);
        }
    }

    /**
     * 按网格对象缓存特征图（克隆的源几何来自 `evaluateSelected` 的缓存，
     * 同一个网格对象在多次创建/参数调整间复用，没必要反复建图）。
     */
    private static final Map<SolidMesh.Mesh, Graph> MESH_CACHE =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static Graph ofMeshCached(final java.util.UUID solidId, final SolidMesh.Mesh mesh) {
        if (mesh == null || mesh.isEmpty()) {
            return new Graph(solidId, List.of(), List.of(), List.of());
        }
        final Graph cached = MESH_CACHE.get(mesh);
        if (cached != null) {
            return cached;
        }
        final Graph built = buildMesh(solidId, mesh);
        MESH_CACHE.put(mesh, built);
        return built;
    }

    /**
     * 把一张特征图整体做**仿射变换**（刚体/等比缩放/镜像）✓。
     *
     * @param point     顶点/原点变换
     * @param direction 法线/方向变换（只旋转/反射，不平移）
     * @param areaScale 面积缩放（平移/镜像为 1；等比缩放为 s，面积乘 s²）
     */
    public static Graph transform(
            final Graph source,
            final java.util.UUID solidId,
            final java.util.function.UnaryOperator<Vector3d> point,
            final java.util.function.UnaryOperator<Vector3d> direction,
            final double areaScale) {
        if (source == null) {
            return new Graph(solidId, List.of(), List.of(), List.of());
        }
        final List<Face> faces = new ArrayList<>(source.faces().size());
        for (final Face f : source.faces()) {
            final List<Vector3d[]> tris = new ArrayList<>(f.triangles().size());
            for (final Vector3d[] tri : f.triangles()) {
                tris.add(new Vector3d[] {point.apply(tri[0]), point.apply(tri[1]),
                        point.apply(tri[2])});
            }
            final List<double[]> segs = new ArrayList<>(f.segments().size());
            for (final double[] s : f.segments()) {
                final Vector3d a = point.apply(new Vector3d(s[0], s[1], s[2]));
                final Vector3d b = point.apply(new Vector3d(s[3], s[4], s[5]));
                segs.add(new double[] {a.x, a.y, a.z, b.x, b.y, b.z});
            }
            final Vector3d n = direction.apply(new Vector3d(f.normal()));
            if (n.lengthSquared() < 1.0e-12) {
                n.set(0, 1, 0);
            }
            n.normalize();
            faces.add(new Face(f.id(), f.kind(), point.apply(new Vector3d(f.origin())),
                    n, tris, segs, f.area() * areaScale * areaScale));
        }
        final List<Vector3d[]> edges = new ArrayList<>(source.edges().size());
        for (final Vector3d[] e : source.edges()) {
            edges.add(new Vector3d[] {point.apply(e[0]), point.apply(e[1])});
        }
        final List<Vector3d> vertices = new ArrayList<>(source.vertices().size());
        for (final Vector3d v : source.vertices()) {
            vertices.add(point.apply(v));
        }
        return new Graph(solidId, faces, edges, vertices);
    }

    // ================== 主流程 ==================

    /** 直接从**网格**建特征图 ✓（离线用例用；生产走 {@link #of(Solid.Placed)} ✓）。 */
    public static Graph ofMesh(final java.util.UUID id, final SolidMesh.Mesh mesh) {
        return buildMesh(id, mesh);
    }

    private static Graph build(final Solid.Placed placed) {
        SolidMesh.Mesh mesh = placed.mesh();
        if (mesh == null || mesh.isEmpty()) {
            // 没有贴图网格时用几何网格兜底（与切割渲染同一条纪律 ✓）
            mesh = SolidMesh.fromBoxes(placed.boxes());
        }
        return buildMesh(placed.solid().id(), mesh);
    }

    private static Graph buildMesh(final java.util.UUID solidId, final SolidMesh.Mesh mesh) {
        final List<SolidMesh.Vertex> vs = mesh.vertices();
        final int[] idx = mesh.indices();
        if (vs.isEmpty() || idx.length < 3) {
            return new Graph(solidId, List.of(), List.of(), List.of());
        }

        // ① 焊接
        final Map<String, Integer> keyToVert = new HashMap<>();
        final List<Vector3d> verts = new ArrayList<>();
        final List<int[]> tris = new ArrayList<>();
        for (int i = 0; i + 2 < idx.length; i += 3) {
            final int[] tri = new int[3];
            boolean ok = true;
            for (int k = 0; k < 3; k++) {
                final Vector3dc p = vs.get(idx[i + k]).position();
                final String key = quant(p);
                final Integer got = keyToVert.get(key);
                if (got == null) {
                    final int ni = verts.size();
                    verts.add(new Vector3d(p));
                    keyToVert.put(key, ni);
                    tri[k] = ni;
                } else {
                    tri[k] = got;
                }
            }
            if (tri[0] == tri[1] || tri[1] == tri[2] || tri[0] == tri[2]) {
                ok = false; // 焊接后退化 ✓ 丢掉
            }
            if (ok) {
                tris.add(tri);
            }
        }

        // ② 邻接：边 ⇒ 三角形列表（边界判定要用）＋ 顶点 ⇒ 三角形列表（连通判定要用）
        //   ★ 连通必须按**共享顶点**算：CSG 的三角汤有 T 型接点（一条边被对面切成两段 ✗）
        //     只认同一条边会把**同一块平面**拆成好几块 ⇒ 实测"一个平面识别成几个三角形/梯形"✗
        final Map<Long, List<Integer>> edgeTris = new HashMap<>();
        final Map<Integer, List<Integer>> vertTris = new HashMap<>();
        for (int t = 0; t < tris.size(); t++) {
            final int[] tri = tris.get(t);
            for (int k = 0; k < 3; k++) {
                edgeTris.computeIfAbsent(edgeKey(tri[k], tri[(k + 1) % 3]), x -> new ArrayList<>())
                        .add(t);
                vertTris.computeIfAbsent(tri[k], x -> new ArrayList<>()).add(t);
            }
        }

        // ③ 共面：先按"法线+偏移"分桶，再在桶内做连通分量
        final Map<String, List<Integer>> buckets = new HashMap<>();
        final List<Vector3d> triNormal = new ArrayList<>();
        final List<Double> triW = new ArrayList<>();
        for (int t = 0; t < tris.size(); t++) {
            final int[] tri = tris.get(t);
            final Vector3d n = normal(verts.get(tri[0]), verts.get(tri[1]), verts.get(tri[2]));
            final double w = n.dot(verts.get(tri[0]));
            triNormal.add(n);
            triW.add(w);
            buckets.computeIfAbsent(quant(n) + "|" + Math.round(w * 1000.0), x -> new ArrayList<>())
                    .add(t);
        }

        // ★ 微面**先不丢**：小半径圆角的曲面条带全是微面，先丢就会让整个圆角
        //   从特征图里消失（用户实测"圆角曲面鼠标移上去没反应、没有描边"）。
        //   现在先让它们参与平滑合并（合成一整块曲面），合并后再按面积过滤。
        final List<Face> raw = new ArrayList<>();
        for (final List<Integer> bucket : buckets.values()) {
            for (final List<Integer> component : components(bucket, tris, vertTris)) {
                if (component.isEmpty()) {
                    continue;
                }
                raw.add(makeFace(raw.size(), component, tris, verts, triNormal, triW));
            }
        }

        // ★ 修实测"一个曲面被识别成多个平面"✗：把**相邻且法线渐变**的窄条/小面合并成曲面 ✓
        final List<Face> merged = mergeSmooth(raw);
        final List<Face> faces = new ArrayList<>();
        final List<Vector3d[]> allEdges = new ArrayList<>();
        for (final Face f : merged) {
            // 合并后的**平面**微面当噪声丢掉；**曲面**即使面积小也保留
            //（小半径圆角/小圆柱的曲面本来就窄，但它是玩家看得见、点得到的真实表面）
            if (f.area() < MIN_FACE_AREA && f.kind() == Kind.PLANAR) {
                continue;
            }
            faces.add(f);
            for (final double[] s : f.segments()) {
                allEdges.add(new Vector3d[] {new Vector3d(s[0], s[1], s[2]),
                        new Vector3d(s[3], s[4], s[5])});
            }
        }

        final List<Vector3d> usedVerts = new ArrayList<>();
        final java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (final Vector3d[] e : allEdges) {
            for (final Vector3d p : e) {
                final String key = quant(p);
                final Integer id = keyToVert.get(key);
                if (id != null && seen.add(id)) {
                    usedVerts.add(verts.get(id));
                }
            }
        }
        // 棱去重（两个面共享一条棱会各报一次 ✗）
        final List<Vector3d[]> uniqueEdges = new ArrayList<>();
        final Map<String, Boolean> seenEdge = new HashMap<>();
        for (final Vector3d[] e : allEdges) {
            final String k1 = quant(e[0]);
            final String k2 = quant(e[1]);
            final String key = k1.compareTo(k2) <= 0 ? k1 + "|" + k2 : k2 + "|" + k1;
            if (seenEdge.put(key, Boolean.TRUE) == null) {
                uniqueEdges.add(e);
            }
        }
        return new Graph(solidId, faces, uniqueEdges, usedVerts);
    }

    /** 同一平面桶内按共享边做连通分量 ✓。 */
    private static List<List<Integer>> components(final List<Integer> bucket, final List<int[]> tris,
                                                  final Map<Integer, List<Integer>> vertTris) {
        final java.util.Set<Integer> remain = new java.util.HashSet<>(bucket);
        final List<List<Integer>> out = new ArrayList<>();
        for (final int seed : bucket) {
            if (!remain.remove(seed)) {
                continue;
            }
            final List<Integer> comp = new ArrayList<>();
            final java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
            queue.add(seed);
            comp.add(seed);
            while (!queue.isEmpty()) {
                final int t = queue.poll();
                final int[] tri = tris.get(t);
                for (int k = 0; k < 3; k++) {
                    final List<Integer> around = vertTris.get(tri[k]);
                    if (around == null) {
                        continue;
                    }
                    for (final int other : around) {
                        if (remain.remove(other)) {
                            comp.add(other);
                            queue.add(other);
                        }
                    }
                }
            }
            out.add(comp);
        }
        return out;
    }

    /** 把一个连通分量做成面（含边界环 ⇒ 直线边）✓。 */
    private static Face makeFace(final int id, final List<Integer> comp, final List<int[]> tris,
                                 final List<Vector3d> verts, final List<Vector3d> triNormal,
                                 final List<Double> triW) {
        // 面积加权法线 / 质心
        final Vector3d nSum = new Vector3d();
        final Vector3d centroid = new Vector3d();
        double areaSum = 0;
        double maxDev = 0;
        final List<Vector3d[]> triangles = new ArrayList<>(comp.size());
        for (final int t : comp) {
            final int[] tri = tris.get(t);
            final Vector3d a = verts.get(tri[0]);
            final Vector3d b = verts.get(tri[1]);
            final Vector3d c = verts.get(tri[2]);
            final double area = new Vector3d(b).sub(a).cross(new Vector3d(c).sub(a)).length() * 0.5;
            if (area < 1.0e-9) {
                continue;
            }
            areaSum += area;
            nSum.fma(area, triNormal.get(t));
            centroid.fma(area / 3.0, new Vector3d(a).add(b).add(c));
            triangles.add(new Vector3d[] {new Vector3d(a), new Vector3d(b), new Vector3d(c)});
        }
        final Vector3d normal = areaSum > 0 ? nSum.normalize(new Vector3d()) : new Vector3d(0, 0, 1);
        final Vector3d origin = areaSum > 0 ? centroid.div(areaSum, new Vector3d()) : new Vector3d();
        final double w = normal.dot(origin);
        for (final Vector3d[] tri : triangles) {
            for (final Vector3d p : tri) {
                maxDev = Math.max(maxDev, Math.abs(normal.dot(p) - w));
            }
        }
        final Kind kind = maxDev < 1.0e-3 ? Kind.PLANAR : Kind.CURVED;

        // 边界边（本分量里只用了一次的边）
        final Map<Long, Integer> count = new HashMap<>();
        final Map<Long, int[]> pair = new HashMap<>();
        for (final int t : comp) {
            final int[] tri = tris.get(t);
            for (int k = 0; k < 3; k++) {
                final int a = tri[k];
                final int b = tri[(k + 1) % 3];
                final long key = edgeKey(a, b);
                count.merge(key, 1, Integer::sum);
                pair.putIfAbsent(key, new int[] {a, b});
            }
        }
        final List<double[]> segments = new ArrayList<>();
        {   // ★ 曲面也要描边（用户实测"曲面只有变色没有描边"✗）⇒ 边界边对**所有面**都算 ✓
            //   是否用它当参考线由上层按 planar 过滤 ✓
            // 直接取边界边的**并集**当"直线边"候选，再按方向合并共线 ✓
            final List<double[]> raw = new ArrayList<>();
            for (final Map.Entry<Long, Integer> e : count.entrySet()) {
                if (e.getValue() != 1) {
                    continue;
                }
                final int[] pr = pair.get(e.getKey());
                if (pr == null) {
                    continue;
                }
                final Vector3d a = verts.get(pr[0]);
                final Vector3d b = verts.get(pr[1]);
                if (a.distance(b) < 1.0e-6) {
                    continue;
                }
                raw.add(new double[] {a.x, a.y, a.z, b.x, b.y, b.z});
            }
            mergeCollinear(raw, segments);
        }
        return new Face(id, kind, origin, normal, triangles, segments, areaSum);
    }

    /**
     * 合并共线且相接/重叠的短段 ⇒ 长直线 ✓。
     *
     * <p>★ 2026-11 重写：旧实现是"反复两两扫描，合并一次就从头再来"
     * ⇒ 40k 多边形的 CSG 网格会产生几万段边界，复杂度接近 O(n³)，
     * 实测 Render thread 在 `mergeCollinear` 里卡几分钟、游戏未响应（用户报告）。
     *
     * <p>现在按**无限直线**分组（方向 + 到原点的垂足），组内按参数 t 排序后
     * 一次扫过合并区间 ⇒ O(n log n)。圆弧段方向各不相同，不会被错误合并；
     * 直线边即使被 CSG 切成很多段也会重新接成一条。
     */
    private static void mergeCollinear(final List<double[]> raw, final List<double[]> out) {
        if (raw.isEmpty()) {
            return;
        }
        final Map<String, List<double[]>> byLine = new HashMap<>();
        for (final double[] s : raw) {
            final double dx = s[3] - s[0];
            final double dy = s[4] - s[1];
            final double dz = s[5] - s[2];
            final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0e-9) {
                continue;
            }
            double ux = dx / len;
            double uy = dy / len;
            double uz = dz / len;
            // 统一方向：相反朝向的段必须落到同一个 key
            if (ux < 0.0 || (ux == 0.0 && uy < 0.0)
                    || (ux == 0.0 && uy == 0.0 && uz < 0.0)) {
                ux = -ux;
                uy = -uy;
                uz = -uz;
            }
            // 直线上离原点最近的点（同一条直线的两段应得到几乎相同的点）
            final double t = s[0] * ux + s[1] * uy + s[2] * uz;
            final double px = s[0] - t * ux;
            final double py = s[1] - t * uy;
            final double pz = s[2] - t * uz;
            final String key = Math.round(ux * 10000.0) + ","
                    + Math.round(uy * 10000.0) + "," + Math.round(uz * 10000.0) + "|"
                    + Math.round(px * 1000.0) + "," + Math.round(py * 1000.0) + ","
                    + Math.round(pz * 1000.0);
            byLine.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }
        for (final List<double[]> group : byLine.values()) {
            if (group.size() == 1) {
                out.add(group.get(0));
                continue;
            }
            final double[] first = group.get(0);
            double ux = first[3] - first[0];
            double uy = first[4] - first[1];
            double uz = first[5] - first[2];
            final double len = Math.sqrt(ux * ux + uy * uy + uz * uz);
            if (len < 1.0e-9) {
                out.add(first);
                continue;
            }
            ux /= len;
            uy /= len;
            uz /= len;
            if (ux < 0.0 || (ux == 0.0 && uy < 0.0)
                    || (ux == 0.0 && uy == 0.0 && uz < 0.0)) {
                ux = -ux;
                uy = -uy;
                uz = -uz;
            }
            // {t0, t1, x0,y0,z0, x1,y1,z1}
            final List<double[]> intervals = new ArrayList<>(group.size());
            for (final double[] s : group) {
                final double ta = s[0] * ux + s[1] * uy + s[2] * uz;
                final double tb = s[3] * ux + s[4] * uy + s[5] * uz;
                if (Math.abs(ta - tb) < 1.0e-9) {
                    continue;
                }
                if (ta <= tb) {
                    intervals.add(new double[] {ta, tb, s[0], s[1], s[2], s[3], s[4], s[5]});
                } else {
                    intervals.add(new double[] {tb, ta, s[3], s[4], s[5], s[0], s[1], s[2]});
                }
            }
            intervals.sort((a, b) -> Double.compare(a[0], b[0]));
            final double eps = 1.0e-4;
            double curT0 = 0.0;
            double curT1 = -Double.MAX_VALUE;
            double curX0 = 0;
            double curY0 = 0;
            double curZ0 = 0;
            double curX1 = 0;
            double curY1 = 0;
            double curZ1 = 0;
            boolean have = false;
            for (final double[] seg : intervals) {
                if (!have) {
                    have = true;
                    curT0 = seg[0];
                    curT1 = seg[1];
                    curX0 = seg[2];
                    curY0 = seg[3];
                    curZ0 = seg[4];
                    curX1 = seg[5];
                    curY1 = seg[6];
                    curZ1 = seg[7];
                    continue;
                }
                if (seg[0] <= curT1 + eps) {
                    // 相接/重叠：延长终点（起点已按 t0 排序，不会更小）
                    if (seg[1] > curT1) {
                        curT1 = seg[1];
                        curX1 = seg[5];
                        curY1 = seg[6];
                        curZ1 = seg[7];
                    }
                    continue;
                }
                out.add(new double[] {curX0, curY0, curZ0, curX1, curY1, curZ1});
                curT0 = seg[0];
                curT1 = seg[1];
                curX0 = seg[2];
                curY0 = seg[3];
                curZ0 = seg[4];
                curX1 = seg[5];
                curY1 = seg[6];
                curZ1 = seg[7];
            }
            if (have) {
                out.add(new double[] {curX0, curY0, curZ0, curX1, curY1, curZ1});
            }
        }
    }

    /** 两段共线且相接 ⇒ 合并成一段；否则 null ✓。 */
    @Nullable
    private static double[] tryMerge(final double[] a, final double[] b) {
        final double[] d1 = {a[3] - a[0], a[4] - a[1], a[5] - a[2]};
        final double[] d2 = {b[3] - b[0], b[4] - b[1], b[5] - b[2]};
        final double[] c = {d1[1] * d2[2] - d1[2] * d2[1],
                d1[2] * d2[0] - d1[0] * d2[2],
                d1[0] * d2[1] - d1[1] * d2[0]};
        final double len1 = Math.sqrt(d1[0] * d1[0] + d1[1] * d1[1] + d1[2] * d1[2]);
        final double len2 = Math.sqrt(d2[0] * d2[0] + d2[1] * d2[1] + d2[2] * d2[2]);
        if (len1 < 1.0e-9 || len2 < 1.0e-9) {
            return null;
        }
        final double sin = Math.sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2]) / (len1 * len2);
        if (sin > 1.0e-3) {
            return null; // 不共线
        }
        // 找共享端点（容差 1e-4）
        final double[][] ends1 = {{a[0], a[1], a[2]}, {a[3], a[4], a[5]}};
        final double[][] ends2 = {{b[0], b[1], b[2]}, {b[3], b[4], b[5]}};
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                if (dist(ends1[i], ends2[j]) < 1.0e-4) {
                    final double[] other1 = ends1[1 - i];
                    final double[] other2 = ends2[1 - j];
                    return new double[] {other1[0], other1[1], other1[2],
                            other2[0], other2[1], other2[2]};
                }
            }
        }
        return null;
    }

    private static double dist(final double[] a, final double[] b) {
        final double dx = a[0] - b[0];
        final double dy = a[1] - b[1];
        final double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static long edgeKey(final int a, final int b) {
        final int lo = Math.min(a, b);
        final int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    private static String quant(final Vector3dc p) {
        return Math.round(p.x() * 10000.0) + "," + Math.round(p.y() * 10000.0) + ","
                + Math.round(p.z() * 10000.0);
    }

    private static Vector3d normal(final Vector3dc a, final Vector3dc b, final Vector3dc c) {
        final Vector3d n = new Vector3d(b).sub(a).cross(new Vector3d(c).sub(a));
        final double len = n.length();
        return len < 1.0e-12 ? new Vector3d(0, 0, 1) : n.div(len, new Vector3d());
    }
}
