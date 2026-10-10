package com.ovo.sablestopnow.sketch;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * **凸分解**：把一个可能**非凸 / 带孔**的实体拆成若干**凸块**。
 *
 * <h2>为什么必须做这件事</h2>
 *
 * 碰撞侧给一个实体挂的是**一个凸包** ✗（{@code SablePhysicsExt.attachConvex}）。
 * 凸包会把**凹的地方和孔全部填掉** ✗ —— 表现是：
 * <ul>
 *   <li><b>带孔的形状</b>（垫圈 / 窗框 / 圆环）：孔被填成一个实心块 ✗
 *       ⇒ 方块穿不过去 ✓ 明明看着是空的却撞墙 ✓</li>
 *   <li><b>L 形 / 凹形</b>：凹口被填平 ✗ ⇒ 别的东西架不进去 ✓</li>
 * </ul>
 * 而"一个凸包"是**故意**的选择 ✓：凸包没有内部结构 ✓ 便宜 ✓ 而且曲面（圆柱）用一个
 * 内接多面体表示得很准 ✓。所以这里的做法不是"废掉凸包" ✗，而是**多挂几个凸包** ✓✓。
 *
 * <h2>为什么不去做通用的网格凸分解（VHACD 那类）</h2>
 *
 * 我们的几何**不是随便一份网格** ✓ —— 它由两个发生器产生 ✓：
 * <ul>
 *   <li>{@link Solid.Shape.Extrusion}：一个平面轮廓（{@link SketchProfiles.Region}，
 *       可带孔）沿法线拉伸；</li>
 *   <li>{@link Solid.Shape.Revolution}：一个平面轮廓绕平面内的一条轴旋转。</li>
 * </ul>
 * 对这两种发生器，**在"参数空间"里分解是精确的** ✓✓：
 * <pre>
 *   拉伸：轮廓 → 拆成若干【凸多边形】→ 每个凸多边形沿法线拉伸 = 一个【凸棱柱】✓
 *   回转：轮廓 → 拆成若干【凸子午线块】→ 每块只扫一段角度 = 一个【近似凸的楔】✓
 * </pre>
 * 而通用网格分解（VHACD）在**三角网格**上做平面切分 ✓ 得到的是**近似**结果 ✓
 * 而且它算在原生侧 ✗（没法离线断言 ✗ 这个项目已经因此吃过亏 ✗）。
 * 更要紧的是：**孔**在我们的数据里是**显式**的（{@link SketchProfiles.Region#holes()}）✓，
 * 用它比让分解器从三角网格里把孔重新"猜"出来可靠得多 ✓✓。
 *
 * <h2>分解算法（2D）：三角化 + 贪心合并</h2>
 *
 * <ol>
 *   <li>{@link SketchProfiles#triangulate} 已经会**正确地桥接孔** ✓ 得到覆盖
 *       "区域减孔"的三角形集合 ✓（三角形一定是凸的 ✓ 所以它本身就是一份合法的凸分解 ✓
 *       但块数 = 三角形数 ✗ 太多 ✗ —— 每个凸块在物理侧是**一个碰撞体** ✗）；</li>
 *   <li>于是把**相邻**的两个面沿公共边合并 ✓，只要合并出来的多边形**仍然是凸的** ✓
 *       就继续合 ✓ —— 这是 Hertel–Mehlhorn 的思路 ✓。</li>
 * </ol>
 *
 * <p>合并**绝不会跨过孔** ✓：孔的边界点在"材料在左"的逆时针多边形里是**凹顶点** ✗，
 * 而凸性检查是**逐个顶点**做的 ✓（不是只查接缝两端 ✓）⇒ 任何吃进孔的合并都会因为
 * 碰到那个凹顶点而被拒绝 ✓✓。这一条是"孔必须保持空"的**唯一**保证 ✓，改动时务必守住 ✓。
 *
 * <h2>块数是要管住的</h2>
 *
 * 每个凸块 = 物理侧**一个碰撞体** ✗，块数直接进求解器 ✓。所以：
 * <ul>
 *   <li>凸的形状（矩形、圆、椭圆）合并之后是**恰好 1 块** ✓ ⇒ **和以前完全一样** ✓✓
 *       （"圆用一个内接多面体"这个已经验证过的行为不会被这次改动破坏 ✓）；</li>
 *   <li>超过 {@code maxPieces} 就**整体放弃** ✓，由调用方退回"单个凸包" ✓
 *       —— 宁可退化成旧行为（孔被填 ✗），也不要为了一个畸形轮廓挂出几十个碰撞体 ✗。</li>
 * </ul>
 *
 * <p>⚠ 只依赖 JOML，可用 {@code tools/ConvexDecompCheck.java} 离线验证 ✓。
 */
public final class ConvexDecomposition {

    /** 默认的凸块数上限：超过就放弃分解、退回单个凸包 ✓（见类注释 ✓）。 */
    public static final int DEFAULT_MAX_PIECES = 24;

    /** 顶点量化（合并时判"同一个顶点"）。1e-6 格远小于任何真实特征 ✓。 */
    private static final double QUANT = 1.0e-6;

    /** 面积/共线判定的零阈（格²）。 */
    private static final double AREA_EPS = 1.0e-10;

    /**
     * 回转体**角度扇区**的目标弓高（格）。
     *
     * <p>一个扇区里的弧是用**弦**去逼近的 ✓，误差（弓高）= {@code r(1−cos(Δφ/2))} ✓。
     * 0.15 格 ≈ 半个像素级的缝 ✓ 而且误差方向是**往内**（内接 ✓）⇒ 表现为"略微能贴近"✓
     * 而不是"鼓出来" ✗（鼓出来才会让别的东西浮空 ✗）。
     */
    private static final double SECTOR_SAGITTA = 0.15;

    /** 角度扇区数的硬上限 ✓（每多一个扇区就多一个碰撞体 ✗）。 */
    private static final int MAX_SECTORS = 16;

    private ConvexDecomposition() {
    }

    // ==================================================================
    // 2D：区域 → 若干凸子区域
    // ==================================================================

    /**
     * 把一个区域（外环 + 孔）分解成若干**凸**子区域。
     *
     * @return 每个子区域是一圈**逆时针**的 {@code {u,v}} 顶点；区域退化时返回空表
     */
    public static List<double[][]> decomposeRegion(final SketchProfiles.Region region) {
        final List<double[]> verts = new ArrayList<>();
        final Map<Long, List<Integer>> index = new HashMap<>();
        final List<List<Integer>> faces = new ArrayList<>();
        final List<double[][]> tris = SketchProfiles.triangulate(region);
        if (debug) {
            System.out.println("  TRIS in=" + tris.size());
        }
        for (final double[][] tri : tris) {
            if (tri == null || tri.length != 3) {
                if (debug) {
                    System.out.println("  DROP 形状不对");
                }
                continue;
            }
            final int[] f = new int[3];
            for (int k = 0; k < 3; k++) {
                f[k] = intern(verts, index, tri[k]);
            }
            if (f[0] == f[1] || f[1] == f[2] || f[0] == f[2]) {
                if (debug) {
                    System.out.println("  DROP 下标重复 " + f[0] + "," + f[1] + "," + f[2]
                            + "  坐标 " + tri[0][0] + "," + tri[0][1]
                            + " | " + tri[1][0] + "," + tri[1][1]
                            + " | " + tri[2][0] + "," + tri[2][1]);
                }
                continue; // 桥接会留下零长度的退化三角形 ✓ 丢掉 ✓
            }
            // 统一成逆时针：下面的凸性判据假设 CCW ✓
            if (turn(verts.get(f[0]), verts.get(f[1]), verts.get(f[2])) < 0.0) {
                final int t = f[1];
                f[1] = f[2];
                f[2] = t;
            }
            if (Math.abs(turn(verts.get(f[0]), verts.get(f[1]), verts.get(f[2]))) < AREA_EPS) {
                if (debug) {
                    System.out.println("  DROP 共线 " + f[0] + "," + f[1] + "," + f[2]);
                }
                continue; // 共线（零面积）✓ 留着只会挡住合并 ✓
            }
            faces.add(new ArrayList<>(List.of(f[0], f[1], f[2])));
        }
        if (debug) {
            System.out.println("  verts=" + verts.size() + " faces=" + faces.size());
        }
        mergeAll(faces, verts);
        final List<double[][]> out = new ArrayList<>(faces.size());
        for (final List<Integer> face : faces) {
            final double[][] poly = new double[face.size()][];
            for (int i = 0; i < face.size(); i++) {
                poly[i] = verts.get(face.get(i));
            }
            out.add(poly);
        }
        return out;
    }

    /**
     * 贪心合并：反复找一对**相邻**的面，若合并后**仍是凸多边形**就合掉。
     *
     * <p>终止时每个面都是凸的 ✓（初始的三角形凸 ✓ 且每次合并都要求结果凸 ✓ 所以不变式成立 ✓）。
     */
    private static void mergeAll(final List<List<Integer>> faces, final List<double[]> verts) {
        if (debug) {
            System.out.println("  START faces=" + faces.size());
        }
        // **最优优先**（best-first），而不是"扫到第一个能合的" ✗：
        // 贪心的**顺序**直接决定最终块数 ✓，而块数就是碰撞体数 ✗（见类注释 ✓）。
        // 取"合并后面积最大"的那一对 ✓ —— 直觉是"先让大块吃成大块" ✓，
        // 实测把 48 段的圆环从 48 块压到 17 块 ✓（数字见 docs/凸分解.md ✓）。
        while (faces.size() > 1) {
            final Map<Long, int[]> firstSeen = new HashMap<>();
            List<Integer> best = null;
            int bestFi = -1;
            int bestOther = -1;
            double bestArea = -1.0;
            for (int fi = 0; fi < faces.size(); fi++) {
                final List<Integer> face = faces.get(fi);
                for (int i = 0; i < face.size(); i++) {
                    final int a = face.get(i);
                    final int b = face.get((i + 1) % face.size());
                    final long key = edgeKey(a, b);
                    final int[] other = firstSeen.get(key);
                    if (other == null) {
                        firstSeen.put(key, new int[] {fi, i});
                        continue;
                    }
                    if (other[0] < 0) {
                        continue; // 这条边这次已经试过、合不了 ✓
                    }
                    final List<Integer> candidate = tryMerge(
                            faces.get(other[0]), other[1], face, i, verts);
                    if (candidate == null) {
                        firstSeen.put(key, new int[] {-1, -1});
                        continue;
                    }
                    final double area = polygonArea(candidate, verts);
                    if (area > bestArea) {
                        bestArea = area;
                        best = candidate;
                        bestFi = fi;
                        bestOther = other[0];
                    }
                }
            }
            if (best == null) {
                break; // 再也合不了了 ✓ 剩下的每个面都是凸的 ✓
            }
            faces.set(bestOther, best);
            faces.remove(bestFi);
            if (debug) {
                System.out.println("  MERGE other=" + bestOther + " fi=" + bestFi
                        + " -> verts=" + best.size() + " area=" + bestArea
                        + " faces=" + faces.size());
            }
        }
        if (debug) {
            double sum = 0.0;
            for (final List<Integer> f : faces) {
                sum += polygonArea(f, verts);
            }
            System.out.println("  END faces=" + faces.size() + " areaSum=" + sum);
        }
    }

    /**
     * 试着把面 {@code A}（公共边在 {@code ia}）与面 {@code B}（公共边在 {@code ib}）合并。
     *
     * <p>两者都是逆时针 ✓，所以同一条公共边在 A 里是 {@code (u,v)}、在 B 里是 {@code (v,u)} ✓。
     * 合并 = 把这条边从两边的环里都去掉，再把剩下的路径接起来 ✓。
     *
     * @return 合并后的面（逆时针）；合不了返回 null
     */
    private static List<Integer> tryMerge(final List<Integer> a, final int ia,
                                          final List<Integer> b, final int ib,
                                          final List<double[]> verts) {
        final int na = a.size();
        final int nb = b.size();
        if (na < 3 || nb < 3) {
            return null;
        }
        // A 从公共边的第二个端点（v）开始绕一圈，正好在公共边的第一个端点（u）结束 ✓
        final List<Integer> out = new ArrayList<>(na + nb - 2);
        for (int k = 0; k < na; k++) {
            out.add(a.get((ia + 1 + k) % na));
        }
        for (int k = 2; k < nb; k++) {
            out.add(b.get((ib + k) % nb));
        }
        if (out.size() < 3) {
            return null;
        }
        // ① 面积必须**守恒** —— 这是"合并没有自交/没有吃进孔"的硬证据 ✓
        //    （凸性检查管的是"形状对不对"，面积管的是"还是同一块料" ✓ 两者都要 ✓）
        final double areaA = polygonArea(a, verts);
        final double areaB = polygonArea(b, verts);
        final double areaM = polygonArea(out, verts);
        if (Math.abs(areaM - (areaA + areaB)) > Math.max(AREA_EPS, 1.0e-9 * (areaA + areaB))) {
            rejectedArea++;
            return null;
        }
        // ② 逐个顶点查凸性 ✓（不是只查接缝两端 ✗ —— 孔上那些凹顶点就是这样被挡住的 ✓）
        if (!isConvex(out, verts)) {
            rejectedConvex++;
            return null;
        }
        // ③ **自交检查** ✓✓ —— 这一条是实测补上的 ✗：
        //    "所有顶点都左转"**并不等于**"多边形是简单多边形" ✗ ——
        //    一个自交的环（蝴蝶结）可以让每个**顶点**处的转角都是左转 ✗，
        //    而它的鞋带面积与真实面积无关 ✗ ⇒ 面积守恒检查也就跟着失去意义 ✗，
        //    于是一个坏合并会**悄悄传播**下去 ✓。
        //    实测症状：半径 3 的圆（本来是凸的 ✓ 应该合成 1 块）被判成 11 块 ✗，
        //    而且各块面积之和 = 净面积的 **3.06 倍** ✗✓ —— 离线断言就是这么抓到的 ✓。
        if (!isSimple(out, verts)) {
            rejectedNonSimple++;
            return null;
        }
        return out;
    }

    /** 诊断计数：因为"合并后自交"而被拒的次数 ✓（离线检查里读它 ✓ 正常应很小 ✓）。 */
    public static int rejectedNonSimple;
    /** 诊断计数：因为"面积不守恒"被拒的次数 ✓。 */
    public static int rejectedArea;
    /** 诊断计数：因为"合并后不凸"被拒的次数 ✓。 */
    public static int rejectedConvex;

    /** 临时排查开关 ✓（离线工具里打开会打出每次合并 ✓）。 */
    public static boolean debug;

    /**
     * 多边形是否**自身不相交** ✓（严格相交判据 ✓ —— 顶点正好落在另一条边上**不算** ✓）。
     *
     * <p>为什么不把"顶点贴边"也算相交：桥接出来的缝天生就是这种形态 ✓
     *（同一个顶点同时属于外环和它的桥 ✓），把它判成自交会让带孔的形状一个也合不了 ✗。
     *
     * <p>O(n²)，但块的顶点数是几十量级 ✓ 而且只在**尝试合并**时算 ✓。
     */
    private static boolean isSimple(final List<Integer> face, final List<double[]> verts) {
        final int n = face.size();
        for (int i = 0; i < n; i++) {
            final double[] a = verts.get(face.get(i));
            final double[] b = verts.get(face.get((i + 1) % n));
            for (int j = i + 1; j < n; j++) {
                // 相邻边共享端点 ⇒ 跳过 ✓（共享端点的两条边永远"碰"在一起 ✓ 那不是自交 ✓）
                if (j == i || (j + 1) % n == i || (i + 1) % n == j) {
                    continue;
                }
                final double[] c = verts.get(face.get(j));
                final double[] d = verts.get(face.get((j + 1) % n));
                if (properCross(a, b, c, d)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 两条线段是否**严格**相交 ✓（端点贴到另一条线上不算 ✓）。 */
    private static boolean properCross(final double[] p1, final double[] p2,
                                       final double[] q1, final double[] q2) {
        final double d1 = side(q1, q2, p1);
        final double d2 = side(q1, q2, p2);
        final double d3 = side(p1, p2, q1);
        final double d4 = side(p1, p2, q2);
        return ((d1 > 0.0 && d2 < 0.0) || (d1 < 0.0 && d2 > 0.0))
                && ((d3 > 0.0 && d4 < 0.0) || (d3 < 0.0 && d4 > 0.0));
    }

    /** 点 {@code p} 在有向直线 {@code a→b} 的哪一侧 ✓。 */
    private static double side(final double[] a, final double[] b, final double[] p) {
        return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0]);
    }

    /** 每个顶点都必须是左转（含共线）✓。 */
    private static boolean isConvex(final List<Integer> face, final List<double[]> verts) {
        final int n = face.size();
        for (int i = 0; i < n; i++) {
            final double[] p0 = verts.get(face.get(i));
            final double[] p1 = verts.get(face.get((i + 1) % n));
            final double[] p2 = verts.get(face.get((i + 2) % n));
            if (turn(p0, p1, p2) < -AREA_EPS) {
                return false;
            }
        }
        return true;
    }

    /** 逆时针多边形的面积（鞋带公式）✓。 */
    private static double polygonArea(final List<Integer> face, final List<double[]> verts) {
        double sum = 0.0;
        for (int i = 0; i < face.size(); i++) {
            final double[] p = verts.get(face.get(i));
            final double[] q = verts.get(face.get((i + 1) % face.size()));
            sum += p[0] * q[1] - q[0] * p[1];
        }
        return sum * 0.5;
    }

    /** {@code (b−a) × (c−b)} 的 z 分量：> 0 = 左转（逆时针凸角）✓。 */
    private static double turn(final double[] a, final double[] b, final double[] c) {
        return (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
    }

    /** 量化后的无向边键 ✓（A 的 (u,v) 与 B 的 (v,u) 必须落到同一个键 ✓）。 */
    private static long edgeKey(final int a, final int b) {
        final int lo = Math.min(a, b);
        final int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    /**
     * 把 2D 点量化后取下标（同一位置重复出现 —— 桥接的缝就是这样 ✓ —— 合并成一个 ✓）。
     *
     * <h2>⚠⚠ 这里曾经有一个只有离线断言才能发现的 bug ✗✓</h2>
     *
     * 第一版是 `HashMap<Long, Integer>`，键 = `qx·C1 ^ qy·C2` ✗ ——
     * **查到时直接返回，从不核对坐标** ✗✗。而"两个乘积做 XOR"这种键**会碰撞** ✗：
     * 实测 `(2.9743, −0.3916)` 与 `(−2.9743, +0.3916)`（关于原点对称的两点 ✗）撞到了同一个键 ✗，
     * 于是两个**不同**的点共用一个下标 ✗ ⇒ 三角化的拓扑被打乱 ✗
     * ⇒ 半径 3 的**圆**（本来是凸的 ✓ 应该合成 1 块）被拆成 **11 块互相重叠**的多边形 ✗，
     * 面积之和 = 净面积的 **3.06 倍** ✗✓。
     *
     * <p>⇒ 规则：**哈希键只能用来分桶，相等必须靠坐标本身** ✓。
     * 现在桶里逐个比量化坐标 ✓ ⇒ 碰撞只是多比较几次 ✓，不再改变结果 ✓。
     */
    private static int intern(final List<double[]> verts, final Map<Long, List<Integer>> index,
                              final double[] p) {
        final long qx = Math.round(p[0] / QUANT);
        final long qy = Math.round(p[1] / QUANT);
        final long key = qx * 73856093L ^ qy * 19349663L;
        final List<Integer> bucket = index.computeIfAbsent(key, k -> new ArrayList<>(1));
        for (final int id : bucket) {
            final double[] v = verts.get(id);
            if (Math.round(v[0] / QUANT) == qx && Math.round(v[1] / QUANT) == qy) {
                return id; // 真的是同一个点 ✓（量化后完全相等 ✓）
            }
        }
        verts.add(new double[] {p[0], p[1]});
        final int id = verts.size() - 1;
        bucket.add(id);
        return id;
    }

    // ==================================================================
    // 内凹：每个坐标轴朝中心收固定距离（纯函数 ✓ 可离线断言 ✓）
    // ==================================================================

    /**
     * **每面往里收 {@code inset} 格**（按轴收 ✓ 不是按半径收 ✗）。
     *
     * <p>形式：{@code x' = Cx + (x − Cx)·max(0.05, 1 − inset/|x − Cx|)} ✓。
     *
     * <h2>为什么必须是【按轴】而不是【按半径】</h2>
     * 半径收法（{@code C + d·(1 − inset/|d|)}）在圆柱上被半径主导 ✗：
     * 一个半径 6、高 ±1 的顶点 |d| = 6.08 ⇒ 侧面收 0.5、而上下底面只收 0.08 ✗ ——
     * 实机表现就是"侧面收进去了、上下底面没收进去" ✗✓（用户当场指出的 ✓）。
     *
     * <h2>为什么这个映射对【多块】是安全的 ✓</h2>
     * 它逐轴是**单调**的（每轴各减一个常量 inset ✓），所以
     * **两块重合的那个面，映射之后仍然重合** ✓✓ —— 前提是**所有块共用同一个中心** ✓。
     * 各块按自己的中心收，块与块之间会裂出一圈缝 ✗（缝宽 2·inset ✗）。
     *
     * <p>⚠ 这也是"两个都是我们的凸包"之间**会有 2×inset 空隙**的原因 ✓ ——
     * 每一边各自收了一次 ✓（这条只对"我们的凸包 vs Sable 体素地形"那一种配对是补偿 ✓）。
     *
     * @param points 扁平 xyz（**刚体局部** ✓）
     * @param centre 所有块共用的中心（同一套系 ✓）
     * @param inset  每面收多少格；{@code <= 0} 时原样返回
     * @return 收完的点（新数组 ✓ 不改入参 ✓）
     */
    public static double[] insetPerAxis(final double[] points, final double[] centre,
                                        final double inset) {
        if (inset <= 0.0 || points == null || centre == null) {
            return points;
        }
        final double[] out = new double[points.length];
        for (int i = 0; i + 2 < points.length; i += 3) {
            for (int a = 0; a < 3; a++) {
                final double d = points[i + a] - centre[a];
                // ⚠⚠ **硬不变式：内凹永不允许吃掉超过一半** ✗→✓（2026-10-03 实机事故 ✓）
                //
                //   原来是 `max(0.05, …)` ✗ —— 允许把某一维收掉 95% ✗。
                //   实机反例（用户那根 r3 / 壁厚 0.5 / 长 12 的**圆管** ✓）：
                //   管子被切成 24 块楔形 ✓，其中位于圆周"正前方"的块在某一轴上的跨度**只有 ~0.3** ✗
                //   ⇒ k = 1 − 0.92/0.3 < 0 ⇒ 被 5% 下限救成"5%" ✗ ⇒ 该维塌成 0.03 ✗
                //   ⇒ 那一块成了**纸片** ⇒ 管口朝下落地时"薄边接触"出现缺口 ✗
                //   ⇒ 现象正是用户报的「**只有管口朝下才会陷进去，横着放正常**」✓✓。
                //
                //   0.5 的含义：一块的某一维最多被收掉一半 ✓ ⇒ 薄壁/薄块自动受保护 ✓，
                //   而"内凹 0.5 格"这种正常取值在大尺寸轴上完全不受影响 ✓（6 格半宽 ⇒ k=0.92 ✓）。
                final double k = Math.abs(d) > 1.0e-9
                        ? Math.max(0.5, 1.0 - inset / Math.abs(d)) : 1.0;
                out[i + a] = centre[a] + d * k;
            }
        }
        return out;
    }

    // ==================================================================
    // 3D：实体 → 若干凸块（每块一个扁平 xyz 数组）
    // ==================================================================

    /**
     * 分解结果。
     *
     * @param pieces     每块一个扁平 {@code x,y,z,...}（plot 局部 ✓）
     * @param segments   实际采用的**圆/弧分段数** ✓（诊断与离线断言用 ✓）
     * @param coarsened  是否为了满足块数预算而**降过采样** ✓
     * @param overBudget 是不是**因为块数超预算**而放弃的 ✓（调用方要据此打日志 ✓
     *                   —— 否则"孔还被填着 ✗"在日志里没有任何线索 ✗）
     */
    public record Result(List<double[]> pieces, int segments, boolean coarsened,
                         boolean overBudget) {
    }

    /**
     * 采样精度阶梯：**从"和网格一致"开始，块数不够用就往下降** ✓✓。
     *
     * <h2>为什么必须自适应（这是实测得出的 ✗→✓）</h2>
     *
     * "严格凸覆盖 + 孔保持空"这两条同时要，**块数由几何自己决定** ✗，
     * 不是我们能随便压的 ✓：
     * <pre>
     *   圆环（外 5 / 内 2.5，48 段）⇒ 48 块 ✗✗ —— 测出来的（离线断言 ✓）
     * </pre>
     * 原因很硬：要"孔一块都不许碰" ✓，每块就只能含**很短的一段内弧** ✗
     *（内弧对这块来说是**凹的** ✗ ⇒ 一旦跨得长，合并出来的多边形就不凸 ✓ 被拒 ✓）。
     * 于是块数 ≈ 内圈的分段数 ✗。
     *
     * <p>而块数是**碰撞体数** ✗（直接进求解器 ✓）—— 所以必须压住 ✓。
     * 压的办法**不是放松"孔不许碰"** ✗（那正是本次要修的东西 ✗），
     * 而是**把曲面本身画粗一点** ✓：8 段的正八边形环，每段内缘是一条**直边** ✓
     * ⇒ 一块就能盖住一条边 ✓ ⇒ **8 块** ✓✓ 而且**孔依然是精确的** ✓✓
     *（块的边界就是内八边形的边 ✓ 一点没往里啃 ✓）。
     *
     * <p>代价：曲面的碰撞比画面**略糙** ✓（内接多面体 ✓ 永远往内 ✓ 不会鼓出去 ✗）。
     * 这和自己那套 {@link SketchProfiles.Sampling#COLLISION} 的取舍是同一个道理 ✓。
     */
    private static final SketchProfiles.Sampling[] SAMPLING_LADDER = {
            SketchProfiles.Sampling.RENDER,                    // 48 段：和网格一致 ✓ 首选 ✓
            new SketchProfiles.Sampling(32, 24, 0.02),         // 32 段
            new SketchProfiles.Sampling(24, 16, 0.04),         // 24 段
            new SketchProfiles.Sampling(16, 12, 0.08),         // 16 段
            new SketchProfiles.Sampling(12, 8, 0.12),          // 12 段
            SketchProfiles.Sampling.COLLISION,                 // 8 段：最后手段 ✓
    };

    /**
     * 把一个实体分解成若干**凸块**。
     *
     * <p>采样精度**从和网格一致的那一档开始试** ✓（{@link #SAMPLING_LADDER} ✓），
     * 块数超预算就换更粗的一档 ✓ —— 全部超预算才放弃 ✓（调用方回退到单个凸包 ✓）。
     *
     * @param maxPieces 块数上限 ✓
     * @return 分解结果；无法分解时 {@code pieces} 为空表
     */
    public static Result decompose(final Solid solid, final Sketch sketch, final int maxPieces) {
        if (solid == null || sketch == null || !sketch.id().equals(solid.sketchId())) {
            return new Result(List.of(), 0, false, false);
        }
        final int budget = Math.max(1, maxPieces);
        boolean overBudget = false;
        for (int i = 0; i < SAMPLING_LADDER.length; i++) {
            final SketchProfiles.Sampling sampling = SAMPLING_LADDER[i];
            final List<double[]> pieces = buildPieces(solid, sketch, sampling, budget);
            if (pieces != null) {
                return new Result(pieces, sampling.circleSegments(), i > 0, false);
            }
            overBudget = true;
        }
        return new Result(List.of(), 0, false, overBudget);
    }

    /**
     * 用指定采样分块。
     *
     * @return 分好的块；**块数超过预算时返回 null** ✓（让上层换更粗的采样再试 ✓）
     */
    private static List<double[]> buildPieces(final Solid solid, final Sketch sketch,
                                              final SketchProfiles.Sampling sampling,
                                              final int budget) {
        final List<SketchProfiles.Region> regions = SketchProfiles.nest(SketchProfiles.findLoops(
                sketch, SketchProfiles.DEFAULT_WELD_TOLERANCE, sampling));
        final SketchPlane plane = sketch.plane();
        final Extrude.Params extrusion = solid.extrusion();
        final Revolve.Params revolution = solid.revolution();
        final List<double[]> out = new ArrayList<>();
        for (final SketchProfiles.Region region : regions) {
            if (!Extrude.extrudable(region)) {
                continue;
            }
            for (final double[][] piece : decomposeRegion(region)) {
                if (extrusion != null) {
                    out.add(prism(plane, piece, extrusion));
                } else if (revolution != null) {
                    out.addAll(revolveWedges(plane, revolution, piece));
                }
                if (out.size() > budget) {
                    return null; // 这一档太细 ✗ 换粗的 ✓
                }
            }
        }
        return out;
    }

    /** 一个凸多边形沿法线拉伸 ⇒ 一个**凸棱柱**的点集（两个截面 × 每圈顶点 ✓）。 */
    private static double[] prism(final SketchPlane plane, final double[][] poly,
                                  final Extrude.Params params) {
        final double[] range = params.depthRange();
        final double[] out = new double[poly.length * 6];
        int w = 0;
        for (final double[] uv : poly) {
            final Vector3d a = Extrude.onPlane(plane, uv, range[0]);
            final Vector3d b = Extrude.onPlane(plane, uv, range[1]);
            out[w++] = a.x;
            out[w++] = a.y;
            out[w++] = a.z;
            out[w++] = b.x;
            out[w++] = b.y;
            out[w++] = b.z;
        }
        return out;
    }

    /**
     * 一个凸子午线块绕轴扫一段角度 ⇒ 若干**楔块**（每块一个点集）。
     *
     * <h2>为什么必须按角度分开</h2>
     *
     * 子午线块是凸的 ✓，但它**绕一圈**扫出来的体一般**不是凸的** ✗：
     * 只在"贴轴"时才凸 ✓（球/圆锥/圆柱 ✓ —— 那时整圈正好是一个凸体 ✓ 不用分 ✓）。
     * 离轴的块（圆环 / 垫圈 / 管子）扫整圈得到的是一个**带孔的环** ✗ ⇒ 整圈的凸包会把孔填掉 ✗✓
     * —— 这正是本次要修的现象 ✓。
     *
     * <p>所以：贴轴 ⇒ 1 块（= 和以前一样 ✓）；离轴 ⇒ 按角度切成 {@code K} 个扇区 ✓，
     * 每个扇区扫出来的楔块**近似凸** ✓，它的凸包**留在材料内部** ✓ ⇒ 孔还在 ✓✓。
     *
     * @return 该子午线块对应的所有楔块点集
     */
    private static List<double[]> revolveWedges(final SketchPlane plane,
                                                final Revolve.Params params,
                                                final double[][] meridian) {
        final double total = params.signedRadians();
        double minRadius = Double.MAX_VALUE;
        double maxRadius = 0.0;
        for (final double[] uv : meridian) {
            final double r = Revolve.radius(params, uv[0], uv[1]);
            minRadius = Math.min(minRadius, r);
            maxRadius = Math.max(maxRadius, r);
        }
        final int sectors = minRadius < 1.0e-6 ? 1 : sectorsFor(Math.abs(total), maxRadius);
        final int steps = Revolve.stepsFor(params);
        final List<double[]> out = new ArrayList<>(sectors);
        for (int s = 0; s < sectors; s++) {
            final double phi0 = total * s / sectors;
            final double phi1 = total * (s + 1) / sectors;
            // 扇区内部按**网格自己**的角度分辨率取点 ✓ ⇒ 凸包贴着真实曲面 ✓（不额外变糙 ✓）
            final int sub = Math.max(1, (int) Math.ceil(steps / (double) sectors));
            final double[] pts = new double[meridian.length * (sub + 1) * 3];
            int w = 0;
            for (int k = 0; k <= sub; k++) {
                final double phi = phi0 + (phi1 - phi0) * k / sub;
                for (final double[] uv : meridian) {
                    final Vector3d p = Revolve.rotated(plane, params, uv[0], uv[1], phi);
                    pts[w++] = p.x;
                    pts[w++] = p.y;
                    pts[w++] = p.z;
                }
            }
            out.add(pts);
        }
        return out;
    }

    /**
     * 需要几个角度扇区：让弓高 {@code r(1−cos(Δφ/2))} 不超过 {@link #SECTOR_SAGITTA} ✓。
     *
     * <p>上限 {@link #MAX_SECTORS} 优先于精度 ✗ —— 块数是物理开销 ✓（这个取舍和
     * {@code Solid.COLLISION_MAX_BOXES} 是同一个道理 ✓）。
     */
    private static int sectorsFor(final double span, final double maxRadius) {
        if (span <= 1.0e-9 || maxRadius <= SECTOR_SAGITTA) {
            return 1;
        }
        final double half = Math.acos(Math.max(-1.0, 1.0 - SECTOR_SAGITTA / maxRadius));
        final double arc = Math.max(1.0e-3, 2.0 * half);
        return Math.max(1, Math.min(MAX_SECTORS, (int) Math.ceil(span / arc)));
    }
}
