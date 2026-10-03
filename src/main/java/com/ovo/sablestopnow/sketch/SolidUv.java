package com.ovo.sablestopnow.sketch;

/**
 * 把"以方块为单位"的 UV 折进某张贴图在图集里的**子矩形**。
 *
 * <h2>为什么必须有这一步</h2>
 * 网格算出来的 UV 单位是**方块**（侧面 u = 沿轮廓的累积弧长、v = 高度；盖面 = 平面坐标）。
 * 直接用会出两个问题：
 * <ol>
 *   <li>UV 大于 1 时会取样到图集里**别的贴图**上（图集是很多张贴图拼起来的）；</li>
 *   <li>即使 UV 在 0..1 内，那也不是这张贴图的位置 —— 图集里每张贴图只占一小块。</li>
 * </ol>
 * 所以要取**小数部分**（= 平铺），再映射到 sprite 的子矩形
 * {@code [u0,u1]×[v0,v1]}（{@code TextureAtlasSprite.getU0()...} 给的就是它）。
 *
 * <p>⚠ 只依赖 JOML，可用 {@code tools/SketchModelCheck.java} 验证。
 */
public final class SolidUv {

    /** 一张贴图默认铺几个方块。1 = 一个方块一次，和原版方块一致。 */
    public static final double DEFAULT_BLOCKS_PER_TILE = 1.0;

    /**
     * **三平面映射（triplanar）**：给**任意曲面**贴图，保持贴图大小基本不变并平铺。
     *
     * <h2>为什么是这个算法（用户 2026-10 的要求）</h2>
     * 用户的原话是"对任意曲面的解决方案，将 16x 的贴图保持大小基本不变，同时平铺到曲面上"。
     * 这正好是三平面映射的定义特征：
     * <ul>
     *   <li>**尺度恒定** —— UV 直接取自**真实坐标**（除以每格方块数），所以贴图不会被拉伸；</li>
     *   <li>**对任意曲面成立** —— 不需要展开（unwrap），什么网格都能用；</li>
     *   <li>**天然平铺** —— 坐标可以无限延伸，靠 {@link #frac} 取小数部分平铺（见它的注释）。</li>
     * </ul>
     * 对比之前的做法（按草图平面坐标当 UV）：那等于把贴图从中心往外**投影**，
     * 曲面上的表现就是**放射状条纹**（用户实测截图确认）。
     *
     * <p>做法：看面法线的**主轴**（哪个分量绝对值最大），用**垂直于它的那两个真实坐标**当 UV。
     * 主轴是 Y 时按"顶/底"选贴图（与 {@link SolidMesh.FaceKind} 的语义一致），否则用侧贴图 ——
     * 这样"顶面年轮、侧面树皮"这种按面区分的贴图**仍然成立**。
     *
     * <p>⚠ 代价说明白：**不同主轴的交界处有硬接缝**（90° 转角处）。
     * 要消掉得三路采样按法线混合，那需要着色器；当前渲染是 CPU 顶点数据，所以先接受硬缝。
     *
     * @param blocksPerTile 每张贴图铺几个方块（越大贴图越大）
     */
    public static SolidMesh.Mesh applyTriplanar(final SolidMesh.Mesh mesh,
                                                final double blocksPerTile,
                                                final SpriteRect capUp, final SpriteRect capDown,
                                                final SpriteRect side) {
        return applyForRender(mesh, null, blocksPerTile, capUp, capDown, side, false);
    }

    /**
     * 贴图用的网格变换：**展开成四顶点一组**（QUADS 渲染模式的要求）+ 指定 UV 策略。
     *
     * @param meshUv true = **用网格自带的 UV**（生成时按累积弧长算的，
     *               所以贴图会**沿着轮廓/旋转出的曲线**平铺 —— 用户 2026-10 的要求：
     *               "贴图尽量沿着线框平铺，例如旋转拉伸时应当沿着旋转拉伸出的曲线去平铺"）；
     *               false = 用**三平面映射**（按面法线主轴取真实坐标，任意曲面兜底）
     */
    /**
     * **REPEAT 贴图路线的 UV**：只把"方块单位的 UV"除以每格方块数，**不做 `frac`、不折子矩形**。
     *
     * <h2>为什么这条路是对的（对比 {@link #applyForRender}）</h2>
     * 图集路线必须把 UV 折进子矩形，于是要么在顶点阶段做 `frac`（**错**：三角形内部会在两个
     * 贴图周期之间插值 → 垃圾），要么切割几何（缝还在、顶点爆炸）。
     * REPEAT 贴图没有这些约束：UV 可以自由超过 1，**回绕由 GL 的环绕模式完成**，
     * 插值始终发生在**连续的 UV 空间**里 —— 这正是"回绕只能在最后一步做"的正确落点。
     *
     * <p>于是这里只剩下一个除法，而且**不再需要四顶点展开**之外的特殊处理。
     *
     * @param meshUv true = 用网格自带的弧长 UV；false = 用面内坐标（平面投影）
     */
    public static SolidMesh.Mesh applyTiled(final SolidMesh.Mesh mesh,
                                            final double blocksPerTile,
                                            final boolean meshUv) {
        if (mesh == null || mesh.isEmpty()) {
            return mesh;
        }
        final double per = blocksPerTile <= 0.0 || !Double.isFinite(blocksPerTile)
                ? DEFAULT_BLOCKS_PER_TILE : blocksPerTile;
        final int[] idx = mesh.indices();
        final java.util.List<SolidMesh.Vertex> in = mesh.vertices();
        final java.util.List<SolidMesh.Vertex> out = new java.util.ArrayList<>(idx.length * 2);
        final java.util.List<Integer> newIdx = new java.util.ArrayList<>(idx.length * 2);
        int i = 0;
        while (i + 2 < idx.length) {
            final boolean quad = i + 5 < idx.length
                    && idx[i] == idx[i + 3] && idx[i + 2] == idx[i + 4];
            final int[] face = quad
                    ? new int[]{idx[i], idx[i + 1], idx[i + 2], idx[i + 5]}
                    : new int[]{idx[i], idx[i + 1], idx[i + 2], idx[i + 2]};
            for (int k = 0; k < 4; k++) {
                final SolidMesh.Vertex v = in.get(face[k]);
                newIdx.add(out.size());
                final double[] uv = new double[2];
                if (meshUv) {
                    // ⚠ 走**统一出口**：SURFACE 模式的公式全项目只有一份实现
                    //   （UvMapping.surfaceUv）。这里是"网格路径"的适配层 ——
                    //   网格生成时已把顶点的 (u,v) 写成方块量纲的弧长。
                    com.ovo.sablestopnow.sketch.param.UvMapping.surfaceUv(v.u(), v.v(), per, uv);
                } else {
                    uv[0] = v.u();
                    uv[1] = v.v();
                }
                out.add(new SolidMesh.Vertex(v.position(), v.normal(), uv[0], uv[1]));
            }
            i += quad ? 6 : 3;
        }
        return new SolidMesh.Mesh(out, toInts(newIdx));
    }

    public static SolidMesh.Mesh applyForRender(final SolidMesh.Mesh mesh,
                                                final org.joml.Vector3d planeNormal,
                                                final double blocksPerTile,
                                                final SpriteRect capUp, final SpriteRect capDown,
                                                final SpriteRect side,
                                                final boolean meshUv) {
        if (mesh == null || mesh.isEmpty()) {
            return mesh;
        }
        final double per = blocksPerTile <= 0.0 || !Double.isFinite(blocksPerTile)
                ? DEFAULT_BLOCKS_PER_TILE : blocksPerTile;
        final int[] idx = mesh.indices();
        final java.util.List<SolidMesh.Vertex> in = mesh.vertices();
        final java.util.List<SolidMesh.Vertex> out = new java.util.ArrayList<>(idx.length * 2);
        final java.util.List<Integer> newIdx = new java.util.ArrayList<>(idx.length * 2);
        // **平面投影用的面内正交基**（封盖面 = 平面，用它才不会变形）。
        // 用户 2026-10 的建议就落在这里："对平面直接不变形平铺即可，对圆柱面保证贴合"。
        // 取法与坐标系无关：拿 planeNormal 叉一个不与它平行的参考轴，再叉回去，得到平面内的一组正交基。
        final org.joml.Vector3d pu = new org.joml.Vector3d();
        final org.joml.Vector3d pv = new org.joml.Vector3d();
        if (planeNormal != null) {
            final org.joml.Vector3d ref = Math.abs(planeNormal.y) > 0.9
                    ? new org.joml.Vector3d(1, 0, 0) : new org.joml.Vector3d(0, 1, 0);
            planeNormal.cross(ref, pu).normalize();
            planeNormal.cross(pu, pv).normalize();
        }
        // ⚠⚠ **必须展开成"四顶点一组"**，因为贴图用的 RenderType（entityCutoutNoCull）
        //   是 **QUADS 模式** —— 顶点流按 4 个一组消费。
        //   之前我按**三角形**（每 3 个顶点）喂它，流就错位了，
        //   表现是**每个四边形只有一个三角形被填上**（用户实测："表现为线框的每一个四边形格子
        //   都只填充了一个三角形"）。这是系统性错位，不是 UV 算错。
        //
        //   同时这也让主轴**按"面"选**（四边形一个轴、三角形一个轴），
        //   比逐顶点选稳得多 —— 三角形内部不可能再出现主轴不一致。
        int i = 0;
        while (i + 2 < idx.length) {
            final boolean quad = i + 5 < idx.length
                    && idx[i] == idx[i + 3] && idx[i + 2] == idx[i + 4];
            final int[] face = quad
                    ? new int[]{idx[i], idx[i + 1], idx[i + 2], idx[i + 5]}
                    : new int[]{idx[i], idx[i + 1], idx[i + 2], idx[i + 2]}; // 退化第 4 点
            // 面的几何法线（与顶点自带的平滑法线无关）
            final org.joml.Vector3d p0 = in.get(face[0]).position();
            final org.joml.Vector3d p1 = in.get(face[1]).position();
            final org.joml.Vector3d p2 = in.get(face[2]).position();
            final org.joml.Vector3d n = new org.joml.Vector3d(p1).sub(p0)
                    .cross(new org.joml.Vector3d(p2).sub(p0), new org.joml.Vector3d());
            final double ax = Math.abs(n.x);
            final double ay = Math.abs(n.y);
            final double az = Math.abs(n.z);
            final int axis; // 0 = X, 1 = Y, 2 = Z
            if (ay >= ax && ay >= az) {
                axis = 1;
            } else if (ax >= az) {
                axis = 0;
            } else {
                axis = 2;
            }
            // ⚠⚠ **子矩形必须按"面的类别"选，和 UV 数值的来源是两回事。**
            //   我上一版在 meshUv 模式下让**所有面都用 `side` 那张**，
            //   于是封盖面的 UV 落进**错误的子矩形** → 取样到图集里相邻的另一张贴图
            //   → 看起来像玻璃/别的方块（用户实测："应用 oak 时出现部分疑似 glass 的材质"）。
            //   顶/底就该用 capUp/capDown，侧面才用 side —— 与 faceKind 的语义一致。
            final SolidMesh.FaceKind kind = planeNormal == null
                    ? (axis == 1 ? (n.y >= 0.0 ? SolidMesh.FaceKind.CAP_UP
                            : SolidMesh.FaceKind.CAP_DOWN) : SolidMesh.FaceKind.SIDE)
                    : SolidMesh.faceKind(n, planeNormal);
            final SpriteRect rect = switch (kind) {
                case CAP_UP -> capUp;
                case CAP_DOWN -> capDown;
                case SIDE -> side;
            };
            // 四个角的"方块单位"UV（还没折进子矩形）
            final double[] bus = new double[4];
            final double[] bvs = new double[4];
            for (int k = 0; k < 4; k++) {
                final SolidMesh.Vertex v = in.get(face[k]);
                if (kind != SolidMesh.FaceKind.SIDE && planeNormal != null) {
                    // **封盖面 = 平面 → 用平面内正交基做投影**，尺度与真实距离成正比，
                    //   所以**不变形、均匀平铺**。
                    //   ⚠ 以前这里用的是"网格自带 UV"，而封盖面是**扇形三角化**的，
                    //     拿它去铺就出现"所有贴图都成三角形向一个点拉伸"（用户实测）。
                    final org.joml.Vector3d p = v.position();
                    bus[k] = p.dot(pu) / per;
                    bvs[k] = p.dot(pv) / per;
                } else if (meshUv) {
                    // 侧面：网格 UV 是按**轮廓弧长 × 厚度**算的（单位已经是**方块**）
                    //   → **贴合圆柱/回转面**。
                    //   ⚠ 必须**除以** blocksPerTile（"几张方块铺一次"），
                    //     我原来写成了**乘**，尺度整个反了（blocksPerTile=2 时会变成铺 4 次）。
                    bus[k] = v.u() / per;
                    bvs[k] = v.v() / per;
                } else {
                    final org.joml.Vector3d p = v.position();
                    switch (axis) {
                        case 1 -> {
                            bus[k] = p.x / per;
                            bvs[k] = p.z / per;
                        }
                        case 0 -> {
                            bus[k] = p.z / per;
                            bvs[k] = p.y / per;
                        }
                        default -> {
                            bus[k] = p.x / per;
                            bvs[k] = p.y / per;
                        }
                    }
                }
            }
            if (rect == null) {
                for (int k = 0; k < 4; k++) {
                    newIdx.add(out.size());
                    out.add(in.get(face[k]));
                }
                i += quad ? 6 : 3;
                continue;
            }
            // ⚠⚠ **必须在贴图边界处细分。**
            //   图集**不能平铺**，所以 UV 是靠 `frac` 折进**一张**贴图的子矩形里的。
            //   一个面如果跨越多张贴图（UV 跨度 > 1），四个角的 frac 会各自回绕，
            //   插值出来就是**一片被拉成单色的区域**（用户实测："一个方块极致拉伸最终呈现的是
            //   其中某个像素的颜色"）。
            //   解法是把这个面切成若干**不超过一张贴图**的子面，每个子面自己算 frac —— 这才是
            //   真正的平铺。上限 8×8，避免一个面炸成几百个四边形。
            final double duMax = Math.max(Math.abs(bus[1] - bus[0]), Math.abs(bus[2] - bus[3]));
            final double dvMax = Math.max(Math.abs(bvs[3] - bvs[0]), Math.abs(bvs[2] - bvs[1]));
            final int nu = Math.max(1, Math.min(8, (int) Math.ceil(duMax - 1.0e-9)));
            final int nv = Math.max(1, Math.min(8, (int) Math.ceil(dvMax - 1.0e-9)));
            // 子面：在 (s,t) ∈ [0,1]² 上做**双线性插值**（我们的面都很小，双线性足够）
            final org.joml.Vector3d[] p = new org.joml.Vector3d[4];
            for (int k = 0; k < 4; k++) {
                p[k] = in.get(face[k]).position();
            }
            for (int iu = 0; iu < nu; iu++) {
                for (int iv = 0; iv < nv; iv++) {
                    final double s0 = (double) iu / nu;
                    final double s1 = (double) (iu + 1) / nu;
                    final double t0 = (double) iv / nv;
                    final double t1 = (double) (iv + 1) / nv;
                    // 四个子角：按 (s,t) 双线性取位置与 UV
                    final double[][] st = {{s0, t0}, {s1, t0}, {s1, t1}, {s0, t1}};
                    // 顺序与 face 的 (p0,p1,p2,p3) 一致：p0=(0,0) p1=(1,0) p2=(1,1) p3=(0,1)
                    final int[] base = new int[4];
                    for (int k = 0; k < 4; k++) {
                        base[k] = out.size();
                        newIdx.add(out.size());
                        final double s = st[k][0];
                        final double t = st[k][1];
                        // 双线性：v(s,t) = (1-s)(1-t)p0 + s(1-t)p1 + s t p2 + (1-s) t p3
                        final org.joml.Vector3d q = new org.joml.Vector3d(p[0])
                                .mul((1 - s) * (1 - t))
                                .add(new org.joml.Vector3d(p[1]).mul(s * (1 - t)))
                                .add(new org.joml.Vector3d(p[2]).mul(s * t))
                                .add(new org.joml.Vector3d(p[3]).mul((1 - s) * t));
                        final double bu = (1 - s) * (1 - t) * bus[0] + s * (1 - t) * bus[1]
                                + s * t * bus[2] + (1 - s) * t * bus[3];
                        final double bv = (1 - s) * (1 - t) * bvs[0] + s * (1 - t) * bvs[1]
                                + s * t * bvs[2] + (1 - s) * t * bvs[3];
                        out.add(new SolidMesh.Vertex(q, in.get(face[k]).normal(),
                                rect.u0() + frac(bu) * (rect.u1() - rect.u0()),
                                rect.v0() + frac(bv) * (rect.v1() - rect.v0())));
                    }
                    // 一个子面 = 四个顶点（QUADS 模式要求 4 个索引一组）
                    newIdx.add(base[0]);
                    newIdx.add(base[1]);
                    newIdx.add(base[2]);
                    newIdx.add(base[3]);
                }
            }
            i += quad ? 6 : 3;
            continue;
        }
        return new SolidMesh.Mesh(out, toInts(newIdx));
    }

    /** 装箱成 int[]（{@code Mesh} 的索引是 int 数组）。 */
    private static int[] toInts(final java.util.List<Integer> list) {
        final int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    private SolidUv() {
    }

    /**
     * 取"方块数"的小数部分，结果恒在 {@code [0,1)}。
     *
     * <p>⚠ 这是本类存在的**主要理由**：Java 的 {@code %} 对负数返回负值
     * （{@code -0.25 % 1 == -0.25}），直接拿它去插值会落到 sprite 子矩形**左边外面**，
     * 也就是取样到图集里相邻的那张贴图上 —— 表现为模型上出现一条别的东西的贴图。
     * 必须用 {@code x - Math.floor(x)}。
     */
    public static double frac(final double blocks) {
        if (!Double.isFinite(blocks)) {
            return 0.0;
        }
        return blocks - Math.floor(blocks);
    }

    /**
     * 把"方块数"映射到 sprite 子矩形里的一维坐标。
     *
     * @param blocks    以方块为单位的 UV（侧面是弧长、盖面是平面坐标）
     * @param spriteU0  sprite 在该轴上的起点（{@code TextureAtlasSprite.getU0()}）
     * @param spriteU1  sprite 在该轴上的终点
     * @param blocksPerTile 几个方块铺一次；{@code <=0} 时按 1 处理
     */
    public static double atlas(final double blocks, final double spriteU0, final double spriteU1,
                              final double blocksPerTile) {
        final double scale = blocksPerTile > 1.0e-9 ? blocksPerTile : DEFAULT_BLOCKS_PER_TILE;
        return spriteU0 + frac(blocks / scale) * (spriteU1 - spriteU0);
    }

    /** 默认铺法的简写。 */
    public static double atlas(final double blocks, final double spriteU0,
                               final double spriteU1) {
        return atlas(blocks, spriteU0, spriteU1, DEFAULT_BLOCKS_PER_TILE);
    }

    /**
     * 把整张网格的 UV 折进 sprite 子矩形。
     *
     * <p>不做"就地改"而是返回新网格：网格可以缓存复用，就地改会让第二次贴不同材质时
     * 拿到已经被折过一次的 UV（再折一次结果就错了 —— 小数部分不可逆）。
     *
     * @return 新的网格（顶点数与索引不变，只换 UV）
     */
    public static SolidMesh.Mesh apply(final SolidMesh.Mesh mesh, final double u0, final double u1,
                                       final double v0, final double v1,
                                       final double blocksPerTile) {
        if (mesh == null || mesh.isEmpty()) {
            return mesh;
        }
        final java.util.List<SolidMesh.Vertex> out =
                new java.util.ArrayList<>(mesh.vertices().size());
        for (final SolidMesh.Vertex vertex : mesh.vertices()) {
            out.add(new SolidMesh.Vertex(vertex.position(), vertex.normal(),
                    atlas(vertex.u(), u0, u1, blocksPerTile),
                    atlas(vertex.v(), v0, v1, blocksPerTile)));
        }
        return new SolidMesh.Mesh(java.util.List.copyOf(out), mesh.indices());
    }

    /**
     * 某张贴图在**图集**里占的子矩形（对应 {@code TextureAtlasSprite.getU0()…}）。
     *
     * <p>⚠ 这里只放四个数，不放 {@code TextureAtlasSprite} —— 那样这个类就得依赖 Minecraft，
     * 也就没法离线验证了。MC 那边查完 sprite 把四个数传进来即可。
     */
    public record SpriteRect(double u0, double u1, double v0, double v1) {

        public SpriteRect {
            if (!Double.isFinite(u0) || !Double.isFinite(u1)
                    || !Double.isFinite(v0) || !Double.isFinite(v1)) {
                throw new IllegalArgumentException("贴图子矩形必须是有限数");
            }
            // 顺序反了会让 UV 折到矩形外面（取样到图集里隔壁的贴图），是个很难查的画面问题，
            // 所以在这里直接拒掉，而不是等画出来才发现。
            if (u1 < u0 || v1 < v0) {
                throw new IllegalArgumentException("贴图子矩形要求 u1>=u0 且 v1>=v0");
            }
        }

        public double width() {
            return u1 - u0;
        }

        public double height() {
            return v1 - v0;
        }
    }

    /**
     * 按**面的类别**分别把 UV 折进对应的贴图子矩形 —— 这是贴图渲染的核心。
     *
     * <h2>为什么按类别分开</h2>
     * 方块模型的 6 个面贴图常常不同（原木：顶面年轮、侧面树皮；草方块：顶面绿、侧面带草边）。
     * 用一个面去贴所有三角形，木头就会长成"四面年轮"。
     * 类别由 {@link SolidMesh#faceKind} 判定。
     *
     * <p>三个 rect 任何一个为 {@code null} 表示"那一类没有贴图"——
     * 该类的三角形**保持原 UV**（调用方会在别处用纯色/线框画它），而不是折成一个垃圾值。
     *
     * <p>⚠ 不动位置、法线、索引：它只改 UV。拓扑是几何的事，贴图不该碰。
     *
     * @return 新的网格；{@code mesh} 为空时原样返回
     */
    public static SolidMesh.Mesh applyFaces(final SolidMesh.Mesh mesh,
                                            final org.joml.Vector3d planeNormal,
                                            final double blocksPerTile,
                                            final SpriteRect capUp,
                                            final SpriteRect capDown,
                                            final SpriteRect side) {
        if (mesh == null || mesh.isEmpty()) {
            return mesh;
        }
        final java.util.List<SolidMesh.Vertex> out =
                new java.util.ArrayList<>(mesh.vertices().size());
        for (final SolidMesh.Vertex vertex : mesh.vertices()) {
            final SolidMesh.FaceKind kind =
                    SolidMesh.faceKind(vertex.normal(), planeNormal);
            final SpriteRect rect = switch (kind) {
                case CAP_UP -> capUp;
                case CAP_DOWN -> capDown;
                case SIDE -> side;
            };
            if (rect == null) {
                out.add(vertex); // 这一类没贴图 → 原样保留
                continue;
            }
            out.add(new SolidMesh.Vertex(vertex.position(), vertex.normal(),
                    atlas(vertex.u(), rect.u0(), rect.u1(), blocksPerTile),
                    atlas(vertex.v(), rect.v0(), rect.v1(), blocksPerTile)));
        }
        return new SolidMesh.Mesh(java.util.List.copyOf(out), mesh.indices());
    }

    /**
     * 网格里每个顶点是否都落在**它那一类**的子矩形内（渲染前自检）。
     *
     * <p>⚠ 必须按类别分别判断，不能拿一个矩形套全部：盖面和侧面的矩形本来就不一样。
     */
    public static boolean withinFaces(final SolidMesh.Mesh mesh,
                                      final org.joml.Vector3d planeNormal,
                                      final SpriteRect capUp, final SpriteRect capDown,
                                      final SpriteRect side) {
        if (mesh == null) {
            return true;
        }
        final double eps = 1.0e-9;
        for (final SolidMesh.Vertex vertex : mesh.vertices()) {
            final SpriteRect rect = switch (SolidMesh.faceKind(vertex.normal(), planeNormal)) {
                case CAP_UP -> capUp;
                case CAP_DOWN -> capDown;
                case SIDE -> side;
            };
            if (rect == null) {
                continue; // 这一类没贴图，不参与判断
            }
            if (vertex.u() < rect.u0() - eps || vertex.u() > rect.u1() + eps
                    || vertex.v() < rect.v0() - eps || vertex.v() > rect.v1() + eps) {
                return false;
            }
        }
        return true;
    }

    /**
     * 网格里所有 UV 是否都落在给定子矩形内（含一点容差）。
     *
     * <p>用来当**渲染前的自检**：一个 UV 跑到子矩形外面，就意味着屏幕上会出现
     * 一张不属于这里的贴图 —— 这种错很难从画面反推，但一个断言就能钉住。
     */
    public static boolean within(final SolidMesh.Mesh mesh, final double u0, final double u1,
                                 final double v0, final double v1) {
        if (mesh == null) {
            return true;
        }
        final double eps = 1.0e-9;
        for (final SolidMesh.Vertex vertex : mesh.vertices()) {
            if (vertex.u() < u0 - eps || vertex.u() > u1 + eps
                    || vertex.v() < v0 - eps || vertex.v() > v1 + eps) {
                return false;
            }
        }
        return true;
    }
}
