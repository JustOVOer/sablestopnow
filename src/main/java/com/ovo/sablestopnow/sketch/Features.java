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
        for (final List<Integer> around : byVertex.values()) {
            for (int i = 0; i < around.size(); i++) {
                for (int j = i + 1; j < around.size(); j++) {
                    final int a = around.get(i);
                    final int b = around.get(j);
                    if (find(parent, a) == find(parent, b)) {
                        continue;
                    }
                    if (canMerge(faces.get(a), faces.get(b), aspects[a], aspects[b])) {
                        union(parent, a, b);
                    }
                }
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

    /** 合并判据：法线近 + 至少一块是窄条/小面 + 尺度相差不大（防止圆角条带吞并大平面）。 */
    private static boolean canMerge(final Face a, final Face b,
                                    final double aspectA, final double aspectB) {
        if (!a.planar() || !b.planar()) {
            return false;
        }
        if (a.normal().dot(b.normal()) < SMOOTH_COS) {
            return false;
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
            return new Graph(null, List.of(), List.of(), List.of());
        }
        final Graph cached = CACHE.get(placed);
        if (cached != null) {
            return cached;
        }
        final Graph built = build(placed);
        CACHE.put(placed, built);
        return built;
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
     * 合并共线且首尾相接的短段 ⇒ 长直线 ✓（CSG/弦盒会把一条边切成很多小段 ✗）
     *
     * <p>做法：反复找"共享端点 + 方向几乎一致（|×| < 1e-3）"的两段并成一段 ✓
     */
    private static void mergeCollinear(final List<double[]> raw, final List<double[]> out) {
        final List<double[]> work = new ArrayList<>(raw);
        boolean merged = true;
        while (merged) {
            merged = false;
            outer:
            for (int i = 0; i < work.size(); i++) {
                for (int j = i + 1; j < work.size(); j++) {
                    final double[] a = work.get(i);
                    final double[] b = work.get(j);
                    final double[] m = tryMerge(a, b);
                    if (m != null) {
                        work.set(i, m);
                        work.remove(j);
                        merged = true;
                        break outer;
                    }
                }
            }
        }
        out.addAll(work);
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
