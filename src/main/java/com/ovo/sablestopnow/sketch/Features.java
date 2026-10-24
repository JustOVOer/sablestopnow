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
     * <p>判据：两块共享任一顶点，且法线夹角 < 12°（圆柱弦盒的相邻条带约 5–10° ✓）。
     * 合并结果标成 {@link Kind#CURVED} ⇒ **不产参考线、不能当基准面** ✓
     * 但**边界面**本身仍然可以高亮 ✓（玩家看得见是"一整块曲面"✓）
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
        final double cosLimit = Math.cos(Math.toRadians(12.0));
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                final Face a = faces.get(i);
                final Face b = faces.get(j);
                if (!a.planar() || !b.planar()) {
                    continue;
                }
                if (a.normal().dot(b.normal()) < cosLimit) {
                    continue;
                }
                if (!sharesVertex(a, b)) {
                    continue;
                }
                union(parent, i, j);
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
            final Vector3d n0 = faces.get(g.get(0)).normal();
            double w0 = 0;
            for (final int idx : g) {
                final Face f = faces.get(idx);
                tris.addAll(f.triangles());
                nSum.fma(f.area(), f.normal());
                centroid.fma(f.area(), f.origin());
                area += f.area();
            }
            final Vector3d normal = area > 0 ? nSum.normalize(new Vector3d()) : n0;
            final Vector3d origin = area > 0 ? centroid.div(area, new Vector3d()) : new Vector3d();
            w0 = normal.dot(origin);
            for (final Vector3d[] tri : tris) {
                for (final Vector3d p : tri) {
                    maxDev = Math.max(maxDev, Math.abs(normal.dot(p) - w0));
                }
            }
            final Kind kind = maxDev < 1.0e-3 ? Kind.PLANAR : Kind.CURVED;
            final List<double[]> segs = kind == Kind.PLANAR
                    ? faces.get(g.get(0)).segments() : List.of();
            out.add(new Face(out.size(), kind, origin, normal, tris, segs, area));
        }
        return out;
    }

    private static boolean sharesVertex(final Face a, final Face b) {
        for (final Vector3d[] ta : a.triangles()) {
            for (final Vector3d[] tb : b.triangles()) {
                for (final Vector3d pa : ta) {
                    for (final Vector3d pb : tb) {
                        if (pa.distanceSquared(pb) < 1.0e-8) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
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

        // ★ 过滤**微面**：圆柱面（弦盒近似）与 CSG 噪声会碎成大量小三角形面 ✗
        //   它们体积小、不能当基准面、更不该产参考线 ✓ ⇒ 面积小于阈值的面直接丢掉 ✓
        final List<Face> faces = new ArrayList<>();
        final List<Vector3d[]> allEdges = new ArrayList<>();
        final java.util.Set<Integer> usedTri = new java.util.HashSet<>();
        for (final List<Integer> bucket : buckets.values()) {
            for (final List<Integer> component : components(bucket, tris, vertTris)) {
                if (component.isEmpty()) {
                    continue;
                }
                usedTri.addAll(component);
                final Face f = makeFace(faces.size(), component, tris, verts, triNormal, triW,
                        allEdges);
                if (f.area() >= MIN_FACE_AREA) {
                    faces.add(f);
                } else {
                    // 微面：把它贡献的棱也撤掉（否则棱会有一大堆碎段 ✗）
                    for (final double[] seg : f.segments()) {
                        allEdges.removeIf(e -> sameSeg(e, seg));
                    }
                }
            }
        }

        // ★ 修实测"一个曲面被识别成多个平面"✗：把**相邻且法线夹角小**的平面块合并成曲面 ✓
        //   （圆柱的弦盒侧面每个三角形法线都略不同 ⇒ 会被拆成一堆小平面 ✗）
        final List<Face> merged = mergeSmooth(faces);
        faces.clear();
        faces.addAll(merged);

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
                                 final List<Double> triW, final List<Vector3d[]> outEdges) {
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
        if (kind == Kind.PLANAR) {
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
            for (final double[] s : segments) {
                outEdges.add(new Vector3d[] {new Vector3d(s[0], s[1], s[2]),
                        new Vector3d(s[3], s[4], s[5])});
            }
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
