package com.ovo.sablestopnow.sketch;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import com.ovo.sablestopnow.sketch.csg.Csg;
import com.ovo.sablestopnow.sketch.csg.CsgMesh;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 一个拉伸实体：**只存身份 + 引用 + 参数，不存几何**。
 *
 * <h2>为什么盒子不入档</h2>
 * 实体的几何 = 「草图轮廓 × 拉伸深度」。这两样都已经在别处存着了，再存一份派生出来的盒子，
 * 就必然会出现"玩家改了草图，实体还是旧盒子"的漂移 —— 那种不一致极难排查，
 * 而且渲染和碰撞会各说各话。所以盒子**每次现算**（{@link #boxes}）。
 *
 * <h2>引用是弱引用</h2>
 * {@link #sketchId} 指向的草图可能已经被删掉、或者还没同步过来。
 * 这两种情况下 {@link #boxes} 都返回空表而不是抛异常 ——
 * 一个找不到草图的实体应该"画不出来"，而不是"让整个渲染崩掉"。
 *
 * <p>⚠ 只依赖 JOML，可用 {@code tools/SketchModelCheck.java} 验证。
 */
public record Solid(UUID id, UUID body, UUID sketchId, int index, Solid.Shape shape,
                    SolidAppearance appearance) {

    /**
     * 实体的**成形方式**：目前是"沿法线拉伸"或"绕平面内的轴旋转"。
     *
     * <p>做成 sealed 而不是"两个可空字段"：那两个字段永远只有一个非空，
     * 是隐性不变量，迟早会出现两个都空或都满的坏状态。sealed 让编译器帮忙守。
     */
    public sealed interface Shape {

        /** 沿草图平面法线拉伸。 */
        record Extrusion(Extrude.Params params) implements Shape {
            public Extrusion {
                Objects.requireNonNull(params, "拉伸参数不能为空");
            }
        }

        /** 绕草图平面内的一条轴旋转。 */
        /**
         * **切割体**（需求 ⑥）：拉伸出一个体，用来给**同一 body 内与它相交的实体打洞** ✓
         *
         * <p>它本身**不产生材料**（boxes/mesh/edges 都是空的）✓
         * 真正的减法在 {@link #placeAll} 里做：把同一 body 的切割体盒子从目标盒子里减掉 ✓
         * （盒子层布尔：稳、快、与碰撞天然一致 ✓）
         */
        record Cut(Extrude.Params params) implements Shape {
        }

        /**
         * **基准面特征** ✓（item 7）。
         *
         * <p>它不是材料，而是一个**可被引用的平面**：中心点（局部）+ 单位法线 + 到源面的距离（可负）✓
         * 塞进 Shape 是为了**自动**复用持久化 / 网络 / 客户端状态 / 树行 / 顺序 / 依赖校验 ✓
         *
         * @param sourceSolid 源实体（依赖它 ⇒ 顺序必须在它之后）
         * @param sourceFace  源面序号（仅用于依赖与重建）
         */
        /**
         * 圆角特征引用的**一条棱** ✓（item 8）：源实体 + 棱的两个端点（局部坐标）。
         *
         * <p>端点用来在重建时**核对棱是否还存在**（上游改了参数、棱没了 ⇒ 圆角失效并提示）✓
         */
        /**
         * **镜像特征** ✓（item 9）：关于一个平面镜像若干特征。
         *
         * <p>镜像产物**不单独占树行** —— 它们属于这个镜像特征（{@code placeAll} 会把反射后的
         * 盒子/线框/网格作为**该特征的 Placed** 输出）✓ 与 SolidWorks 一致 ✓
         *
         * <p>限制（第一版）：平面法线按**轴对齐**处理（反射 AABB 仍是 AABB ✓）；斜平面会退化 ✗
         */
        /**
         * 阵列的一个**方向** ✓（item 10）：单位方向 + 次数 + 间距（可负 = 反向）✓
         */
        record Dir(double dx, double dy, double dz, int count, double spacing) {
            public Dir {
                final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (!Double.isFinite(len) || len < 1.0e-9) {
                    throw new IllegalArgumentException("阵列方向不能是零向量");
                }
                dx /= len;
                dy /= len;
                dz /= len;
                count = Math.max(1, Math.min(64, count));
            }
        }

        /**
         * **阵列特征** ✓（item 10）：1–3 个方向 × 各自次数/间距，把源特征平移复制。
         *
         * <p>产物= N×M（两个方向）个副本，**归在"阵列N"一行**（与镜像同一套处理）✓
         */
        record Pattern(java.util.List<Dir> dirs, java.util.List<java.util.UUID> sources)
                implements Shape {
            public Pattern {
                dirs = dirs == null ? java.util.List.of() : java.util.List.copyOf(dirs);
                sources = sources == null ? java.util.List.of() : java.util.List.copyOf(sources);
            }
        }

        record Mirror(double ox, double oy, double oz, double nx, double ny, double nz,
                      java.util.List<java.util.UUID> sources) implements Shape {
            public Mirror {
                final double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (!Double.isFinite(len) || len < 1.0e-9) {
                    throw new IllegalArgumentException("镜像平面法线不能是零向量");
                }
                nx /= len;
                ny /= len;
                nz /= len;
                sources = sources == null ? java.util.List.of() : java.util.List.copyOf(sources);
            }
        }

        record EdgeRef(java.util.UUID solidId, double ax, double ay, double az,
                       double bx, double by, double bz,
                       double n1x, double n1y, double n1z,
                       double n2x, double n2y, double n2z,
                       double[] curve) {

            public EdgeRef {
                curve = curve == null ? new double[0] : curve;
            }

            /** 直线棱（12 参数签名，curve 为空）✓ */
            public EdgeRef(final java.util.UUID solidId, final double ax, final double ay,
                           final double az, final double bx, final double by, final double bz,
                           final double n1x, final double n1y, final double n1z,
                           final double n2x, final double n2y, final double n2z) {
                this(solidId, ax, ay, az, bx, by, bz, n1x, n1y, n1z, n2x, n2y, n2z,
                        new double[0]);
            }

            /** 旧版（无面法线）：法线置零，几何侧会退回"从网格搜相邻面"✓ */
            public EdgeRef(final java.util.UUID solidId, final double ax, final double ay,
                           final double az, final double bx, final double by, final double bz) {
                this(solidId, ax, ay, az, bx, by, bz, 0, 0, 0, 0, 0, 0, new double[0]);
            }

            /** 是**曲线棱**吗？（curve = 折线点，至少 3 个点 = 9 个数）✓ */
            public boolean isCurve() {
                return curve.length >= 9;
            }

            /** 曲线折线的点（局部坐标 {x,y,z} 每 3 个一组）✓ */
            public java.util.List<double[]> curvePoints() {
                final java.util.List<double[]> pts = new java.util.ArrayList<>();
                for (int i = 0; i + 2 < curve.length; i += 3) {
                    pts.add(new double[] {curve[i], curve[i + 1], curve[i + 2]});
                }
                return pts;
            }

            /** 曲线拆成有序线段 {x1,y1,z1,x2,y2,z2} ✓（直线棱返回空） */
            public java.util.List<double[]> curveSegs() {
                final java.util.List<double[]> out = new java.util.ArrayList<>();
                final java.util.List<double[]> pts = curvePoints();
                for (int i = 0; i + 1 < pts.size(); i++) {
                    final double[] p = pts.get(i);
                    final double[] q = pts.get(i + 1);
                    out.add(new double[] {p[0], p[1], p[2], q[0], q[1], q[2]});
                }
                return out;
            }

            @Override
            public boolean equals(final Object o) {
                if (this == o) {
                    return true;
                }
                if (!(o instanceof final EdgeRef r)) {
                    return false;
                }
                return java.util.Objects.equals(solidId, r.solidId)
                        && ax == r.ax && ay == r.ay && az == r.az
                        && bx == r.bx && by == r.by && bz == r.bz
                        && n1x == r.n1x && n1y == r.n1y && n1z == r.n1z
                        && n2x == r.n2x && n2y == r.n2y && n2z == r.n2z
                        && java.util.Arrays.equals(curve, r.curve);
            }

            @Override
            public int hashCode() {
                int h = java.util.Objects.hashCode(solidId);
                h = 31 * h + Double.hashCode(ax);
                h = 31 * h + Double.hashCode(ay);
                h = 31 * h + Double.hashCode(az);
                h = 31 * h + Double.hashCode(bx);
                h = 31 * h + Double.hashCode(by);
                h = 31 * h + Double.hashCode(bz);
                h = 31 * h + Double.hashCode(n1x);
                h = 31 * h + Double.hashCode(n1y);
                h = 31 * h + Double.hashCode(n1z);
                h = 31 * h + Double.hashCode(n2x);
                h = 31 * h + Double.hashCode(n2y);
                h = 31 * h + Double.hashCode(n2z);
                return 31 * h + java.util.Arrays.hashCode(curve);
            }
        }

        /**
         * **圆角特征** ✓（item 8）：等半径，凸棱；多条棱分别倒角后合并 ✓。
         *
         * <p>几何由 {@code FilletGeom} 算成**局部坐标的阶梯盒**，
         * 在 {@code placeAll} 里当作"本地的切割盒"用 —— 于是碰撞与渲染（CSG）都自动一致 ✓
         */
        record Fillet(double radius, java.util.List<EdgeRef> edges) implements Shape {
            public Fillet {
                if (!(radius > 0.0) || !Double.isFinite(radius)) {
                    throw new IllegalArgumentException("圆角半径必须为正");
                }
                edges = edges == null ? java.util.List.of() : java.util.List.copyOf(edges);
            }
        }

        record Plane(double ox, double oy, double oz,
                     double nx, double ny, double nz,
                     double distance, java.util.UUID sourceSolid, int sourceFace)
                implements Shape {

            public Plane {
                final double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (!Double.isFinite(len) || len < 1.0e-9) {
                    throw new IllegalArgumentException("基准面法线不能是零向量");
                }
                nx /= len;
                ny /= len;
                nz /= len;
            }

            /**
             * 面片的四个角（局部坐标，绕行一圈）✓ 渲染与预览**共用**这一段，
             * 保证"预览看到的就是创建出来的那个面" ✓
             *
             * @param half 半宽（格）
             */
            public java.util.List<Vector3d> corners(final double half) {
                final Vector3d n = new Vector3d(nx, ny, nz).normalize();
                Vector3d u = n.cross(new Vector3d(0, 1, 0), new Vector3d());
                if (u.lengthSquared() < 1.0e-9) {
                    u = n.cross(new Vector3d(1, 0, 0), new Vector3d());
                }
                u.normalize();
                final Vector3d v = n.cross(u, new Vector3d()).normalize();
                final double[] o = offsetOrigin();
                final Vector3d c = new Vector3d(o[0], o[1], o[2]);
                return java.util.List.of(
                        new Vector3d(c).fma(-half, u).fma(-half, v),
                        new Vector3d(c).fma(half, u).fma(-half, v),
                        new Vector3d(c).fma(half, u).fma(half, v),
                        new Vector3d(c).fma(-half, u).fma(half, v));
            }

            /** 法线方向朝外偏 {@code distance} 之后的中心点 ✓ */
            public double[] offsetOrigin() {
                return new double[] {ox + nx * distance, oy + ny * distance, oz + nz * distance};
            }
        }

        record Revolution(Revolve.Params params) implements Shape {
            public Revolution {
                Objects.requireNonNull(params, "旋转参数不能为空");
            }
        }
    }

    public Solid {
        Objects.requireNonNull(id, "实体 id 不能为空");
        Objects.requireNonNull(body, "实体必须挂在一个子结构上");
        Objects.requireNonNull(sketchId, "实体必须引用一张草图");
        Objects.requireNonNull(shape, "成形方式不能为空");
        // ⚠ 外观为 null 时兜底成 EMPTY，而不是抛 —— 它是后来才加的字段，
        // 老代码路径（和反序列化的坏数据）都可能给 null；为此让整个实体建不出来不值得。
        if (appearance == null) {
            appearance = SolidAppearance.EMPTY;
        }
        if (index < 1) {
            throw new IllegalArgumentException("实体序号从 1 开始");
        }
    }

    /** 新建一个**拉伸**实体：序号由调用方按同一子结构上已有实体算出（见 {@link #nextIndex}）。 */
    public static Solid create(final UUID body, final UUID sketchId, final int index,
                               final Extrude.Params params) {
        return create(body, sketchId, index, params, SolidAppearance.EMPTY);
    }

    /**
     * 新建一个**基准面特征** ✓（item 7）。
     *
     * <p>它没有草图 ⇒ 用一个**确定性 UUID**（由 body + index 派生）占位，
     * 这样 `SolidData` / 网络 / 校验这些既有链路都能原样复用 ✓
     * 依赖校验那边会**跳过面特征的草图检查**（见 {@code FeatureDeps}）✓
     */
    public static Solid createPlane(final UUID body, final UUID sketchId, final int index,
                                    final double ox, final double oy, final double oz,
                                    final double nx, final double ny, final double nz,
                                    final double distance, final UUID sourceSolid,
                                    final int sourceFace) {
        return new Solid(UUID.randomUUID(), body, sketchId, index,
                new Shape.Plane(ox, oy, oz, nx, ny, nz, distance, sourceSolid, sourceFace), SolidAppearance.EMPTY);
    }

    /** 新建一个**圆角特征** ✓（item 8；与基准面一样用确定性占位 sketch id） */
    public static Solid createFillet(final UUID body, final UUID sketchId, final int index, final double radius,
                                     final java.util.List<Shape.EdgeRef> edges) {
        return new Solid(UUID.randomUUID(), body, sketchId, index,
                new Shape.Fillet(radius, edges), SolidAppearance.EMPTY);
    }

    /** 新建一个**镜像特征** ✓（item 9；占位 sketch id 同其它新特征） */
    public static Solid createMirror(final UUID body, final UUID sketchId, final int index,
                                     final double ox, final double oy, final double oz,
                                     final double nx, final double ny, final double nz,
                                     final java.util.List<UUID> sources) {
        return new Solid(UUID.randomUUID(), body, sketchId, index,
                new Shape.Mirror(ox, oy, oz, nx, ny, nz, sources), SolidAppearance.EMPTY);
    }

    /** 新建一个**阵列特征** ✓（item 10） */
    public static Solid createPattern(final UUID body, final UUID sketchId, final int index,
                                      final java.util.List<Shape.Dir> dirs,
                                      final java.util.List<UUID> sources) {
        return new Solid(UUID.randomUUID(), body, sketchId, index,
                new Shape.Pattern(dirs, sources), SolidAppearance.EMPTY);
    }

    /** 是不是阵列特征 ✓ */
    public boolean isPattern() {
        return this.shape instanceof Shape.Pattern;
    }

    /** 是不是镜像特征 ✓ */
    public boolean isMirror() {
        return this.shape instanceof Shape.Mirror;
    }

    /** 把一个盒子关于平面（点 + 单位法线）反射 ✓（AABB ⇒ AABB） */
    public static double[] reflectBox(final double[] box, final double[] o, final double[] n) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            final double x = ((i & 1) == 0 ? box[0] : box[3]) - o[0];
            final double y = ((i & 2) == 0 ? box[1] : box[4]) - o[1];
            final double z = ((i & 4) == 0 ? box[2] : box[5]) - o[2];
            final double d = x * n[0] + y * n[1] + z * n[2];
            final double rx = x - 2 * d * n[0] + o[0];
            final double ry = y - 2 * d * n[1] + o[1];
            final double rz = z - 2 * d * n[2] + o[2];
            minX = Math.min(minX, rx);
            minY = Math.min(minY, ry);
            minZ = Math.min(minZ, rz);
            maxX = Math.max(maxX, rx);
            maxY = Math.max(maxY, ry);
            maxZ = Math.max(maxZ, rz);
        }
        return new double[] {minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** 是不是圆角特征 ✓ */
    public boolean isFillet() {
        return this.shape instanceof Shape.Fillet;
    }

    /**
     * 圆角特征要**挖掉**的盒子（**局部坐标**）✓（item 8）。
     *
     * <p>对每条棱：相邻两面取"盖面法线（草图平面法线）"与"侧面法线（棱方向 × 盖面法线）"，
     * 交给 {@link FilletGeom} 算阶梯盒 ✓（凸棱假设；凹棱/斜棱返回空 ⇒ 该棱不生效）
     */
    public java.util.List<double[]> filletBoxes(final java.util.Map<UUID, Solid> byId) {
        final java.util.List<double[]> out = new java.util.ArrayList<>();
        if (!(this.shape instanceof final Shape.Fillet f) || byId == null) {
            return out;
        }
        for (final Shape.EdgeRef ref : f.edges()) {
            final Solid src = byId.get(ref.solidId());
            if (src == null || src.shape instanceof Shape.Plane || src.shape instanceof Shape.Cut) {
                continue;
            }
            // ★ 曲线棱：碰撞盒也要**逐段**做（用弦线会把弧内部整块切掉）✓
            if (ref.isCurve()) {
                final org.joml.Vector3d curveCap = new org.joml.Vector3d(
                        ref.n1x(), ref.n1y(), ref.n1z());
                if (curveCap.lengthSquared() < 1.0e-9) {
                    curveCap.set(0, 1, 0);
                }
                curveCap.normalize();
                for (final double[] sg : ref.curveSegs()) {
                    final org.joml.Vector3d sa = new org.joml.Vector3d(sg[0], sg[1], sg[2]);
                    final org.joml.Vector3d sb = new org.joml.Vector3d(sg[3], sg[4], sg[5]);
                    final org.joml.Vector3d d = new org.joml.Vector3d(sb).sub(sa);
                    if (d.length() < 1.0e-6) {
                        continue;
                    }
                    d.normalize();
                    final org.joml.Vector3d side = new org.joml.Vector3d(d).cross(curveCap);
                    if (side.lengthSquared() < 1.0e-9) {
                        continue;
                    }
                    side.normalize();
                    out.addAll(FilletGeom.boxesFor(sa, sb, curveCap, side, f.radius(), true));
                }
                continue;
            }
            // 只用"棱方向"与"草图平面法线"两面 ⇒ 与 FilletGeom 的轴对齐判据配合 ✓
            final org.joml.Vector3d a = new org.joml.Vector3d(ref.ax(), ref.ay(), ref.az());
            final org.joml.Vector3d b = new org.joml.Vector3d(ref.bx(), ref.by(), ref.bz());
            final org.joml.Vector3d dir = new org.joml.Vector3d(b).sub(a);
            if (dir.length() < 1.0e-6) {
                continue;
            }
            dir.normalize();
            // 盖面法线：默认沿 +Y（轴对齐棱的常见情形）；侧面法线 = dir × cap
            final org.joml.Vector3d cap = new org.joml.Vector3d(0, 1, 0);
            if (Math.abs(dir.dot(cap)) > 0.99) {
                cap.set(1, 0, 0); // 棱本身竖直 ⇒ 换个参考轴 ✓
            }
            final org.joml.Vector3d side = new org.joml.Vector3d(dir).cross(cap).normalize();
            out.addAll(FilletGeom.boxesFor(a, b, cap, side, f.radius(), true));
        }
        return out;
    }

    /** 是不是基准面特征 ✓ */
    public boolean isPlane() {
        return this.shape instanceof Shape.Plane;
    }

    /** 新建一个拉伸实体，并指定外观（贴图）。 */
    public static Solid create(final UUID body, final UUID sketchId, final int index,
                               final Extrude.Params params, final SolidAppearance look) {
        return new Solid(UUID.randomUUID(), body, sketchId, index,
                new Shape.Extrusion(params), look);
    }

    /** 新建一个**切割体** ✓（需求 ⑥：拉伸出来给同一 body 内相交的实体打洞 ✓）。 */
    public static Solid createCut(final UUID body, final UUID sketchId, final int index,
                                  final Extrude.Params params) {
        return new Solid(UUID.randomUUID(), body, sketchId, index,
                new Shape.Cut(params), SolidAppearance.EMPTY);
    }

    /** 新建一个**旋转**实体。 */
    public static Solid createRevolution(final UUID body, final UUID sketchId, final int index,
                                         final Revolve.Params params) {        return createRevolution(body, sketchId, index, params, SolidAppearance.EMPTY);
    }

    /** 新建一个旋转实体，并指定外观（贴图）。 */
    public static Solid createRevolution(final UUID body, final UUID sketchId, final int index,
                                         final Revolve.Params params,
                                         final SolidAppearance look) {
        return new Solid(UUID.randomUUID(), body, sketchId, index,
                new Shape.Revolution(params), look);
    }

    /** 是拉伸还是旋转（UI 与存档要用）。 */
    public boolean isRevolution() {
        return shape instanceof Shape.Revolution;
    }

    /** 拉伸参数；不是拉伸实体时返回 null。 */
    @Nullable
    public Extrude.Params extrusion() {
        return shape instanceof Shape.Extrusion e ? e.params() : null;
    }

    /** 旋转参数；不是旋转实体时返回 null。 */
    @Nullable
    public Revolve.Params revolution() {
        return shape instanceof Shape.Revolution r ? r.params() : null;
    }

    /**
     * 同一子结构上下一个可用的序号。
     *
     * <p>取「已有最大序号 + 1」而不是「数量 + 1」：删掉中间一个再新建时，
     * 数量法会**撞上还活着的那个的序号**，树里就出现两个"拉伸2"。
     */
    public static int nextIndex(final Collection<Solid> existing, final UUID body) {
        int max = 0;
        for (final Solid s : existing) {
            if (s.body().equals(body)) {
                max = Math.max(max, s.index());
            }
        }
        return max + 1;
    }

    /**
     * 算出实体的盒子集合（子结构 plot 局部系）。
     *
     * @param sketch 解析出来的草图；为 null 或 id 对不上时返回空表
     * @return 每个盒子 {@code {minX,minY,minZ,maxX,maxY,maxZ}}；空表表示"画不出来"
     */
    public List<double[]> boxes(@Nullable final Sketch sketch) {
        if (sketch == null || !sketch.id().equals(sketchId)) {
            // id 对不上说明传错了草图 —— 宁可什么都不画，也不要拿别人的轮廓拉出一个错位的实体
            return List.of();
        }
        final Extrude.Params p = extrusion();
        if (p == null) {
            // ⚠ 旋转体**还没有盒分解**：它的面是斜的，轴对齐盒子套不住，
            // 要用按角度分楔的 OBB（基础设施已就绪：见 SolidCollision.OrientedBox）。
            // 在那之前返回空表 —— 让它**不参与碰撞**，而不是胡乱碰。
            return List.of();
        }
        return Extrude.boxesOf(sketch, p);
    }

    /**
     * 实体的**碰撞几何**：一组可以任意朝向的盒子。
     *
     * <p>两种成形方式在这里汇合成一条路径：
     * <ul>
     *   <li><b>拉伸</b>：轴对齐扫描线分解是**精确**的，朝向给单位四元数（等于没转）。</li>
     *   <li><b>旋转</b>：几何是斜的，按角度分楔、每楔一个 OBB（见 {@link Revolve#collisionBoxes}）。</li>
     * </ul>
     *
     * <p>⚠ 物理那边只有这一条路（`BoxPhysicsObject` 收带朝向的位姿），
     * 所以调用方不必知道实体是怎么成形出来的。
     */
    public List<SolidCollision.OrientedBox> orientedBoxes(@Nullable final Sketch sketch) {
        if (sketch == null || !sketch.id().equals(sketchId)) {
            return List.of();
        }
        final List<SolidCollision.OrientedBox> raw = switch (shape) {
            case Shape.Cut c -> List.of(); // 切割体没有自己的碰撞 ✓
            case Shape.Plane p -> List.of(); // 基准面不是材料 ✓
            case Shape.Fillet f -> List.of(); // 圆角本身不产生材料（它产生"要挖掉的盒子"）✓
            case Shape.Mirror m -> List.of(); // 镜像的材料由反射得出 ✓
            case Shape.Pattern pt -> List.of(); // 阵列材料由平移得出 ✓
            case Shape.Extrusion e -> {
                // ⚠ **曲面走棱盒（OBB），轴对齐轮廓走扫描线。** 判据是刻意的：
                //   矩形/L 形的扫描线分解是**精确**的、盒子也最少，没有理由给它加朝向；
                //   而曲面用扫描线会得到**楼梯** —— 圆柱滚起来就是一级级跳
                //   （用户实测报的"大圆柱滚动的顿挫感太强"）。
                //   棱盒的外侧面**就是那条边**，外表面是一圈平面，滚动才连续。
                if (Extrude.hasCurvature(sketch)) {
                    yield Extrude.chordBoxes(sketch, e.params(), Extrude.SHELL_DEPTH_SCALE);
                }
                yield SolidCollision.orientedBoxSpecs(Extrude.boxesOf(sketch, e.params()));
            }
            // 回转体：**体素化 + 贪心合并**（通用底座）。
            // 原来的"轮廓盒 × 角度楔"有两个硬伤（用户实测截图确认）：
            //   ① 径向给满而切向只给弦长 → 盒子外角**必然鼓出** r(1−cos(Δφ/2))；
            //   ② 盒子数 = 轮廓盒数 × 楔数 → 旋转一个圆能出几百个。
            // 体素化的固有误差是**半个格子**，且盒子数由**预算**决定。
            // 回转体：**体素方案**（用户实测：分楔 OBB 一个球要 132 个盒子，
            // 每个盒子 = 一个刚体 + 一条**弹簧**约束，每个都进求解器迭代 → 大量卡顿）。
            //
            // 实测（离线对照，见 tools/CollisionCostReport.java）：
            //     半径1球   分楔 132 | 体素填充 13 | 体素壳 45
            //     圆环      分楔 228 | 体素填充 158 | 体素壳 81
            // 结论：**分楔在两种形状下都是最差的**；而填充与壳各有胜负 ——
            //   贪心合并擅长填"实心团"（球 13），不擅长铺"薄曲面层"（球 45）；
            //   但有孔/细长的形状反过来（圆环 壳 81 < 填充 158；而且填充在某些网格上会**返回 0**）。
            // 所以这里**两个都算、取盒子数少的那个**，填充为空时无条件用壳。
            // 两种都是**轴对齐体素盒**，与将来接「一个碰撞体喂 N 个盒子」的体素路径同形。
            case Shape.Revolution r -> {
                final com.ovo.sablestopnow.sketch.SolidMesh.Mesh mesh =
                        Revolve.mesh(sketch, r.params());
                final SolidCollision.VoxelResult fill = SolidCollision.voxelBoxes(mesh,
                        COLLISION_TARGET_CELL, COLLISION_MAX_BOXES,
                        COLLISION_MIN_RES, COLLISION_MAX_RES);
                final SolidCollision.VoxelResult shell = SolidCollision.surfaceBoxes(mesh,
                        COLLISION_TARGET_CELL, COLLISION_MAX_BOXES,
                        COLLISION_MIN_RES, COLLISION_MAX_RES);
                yield fill.boxes().isEmpty() || shell.boxes().size() < fill.boxes().size()
                        ? shell.boxes() : fill.boxes();
            }
        };
        // ⚠ **碰撞盒相对视觉几何整体内缩**，在**这一个地方**统一做：
        // 三条路径（扫描线 / 棱盒 / 旋转）的外侧面都会贴在轮廓边界上，
        // 而结构自己的方块体素碰撞体也在那儿 —— 共面接触会让求解器跟固定约束打架。
        // 集中做一次，避免某条路径漏掉（漏掉的那条就会表现为"结构自己悬浮/拉不动"）。
        return SolidCollision.shrink(raw, Extrude.BOX_SHRINK);
    }

    /**
     * 实体的**真实体积**（用于分摊质量）。
     *
     * <h2>为什么不能用"盒子体积之和"</h2>
     * 碰撞盒只是**几何的近似**，它的体积之和**不等于**实体的体积：
     * <ul>
     *   <li>扫描线盒是**划分** —— 之和 ≈ 真实（所以以前看不出来）；</li>
     *   <li>但**棱盒是壳、彼此重叠** —— 之和明显大于真实。</li>
     * </ul>
     * 拿盒子和当质量基准会让实体**重得推不动**（实测表现：手杖拖拽"强烈阻滞感"）。
     * 质量是物理量，必须按真实几何算。
     *
     * <ul>
     *   <li>拉伸：{@code 截面积 × 厚度}（精确）。</li>
     *   <li>旋转：帕普斯定理 {@code V = θ · r_c · A}（θ 弧度、r_c 是形心到轴的距离）。
     *       ⚠ 该定理要求**轴不穿过轮廓**；轴穿过去时退回盒子体积之和（近似但有界），
     *       因为那时"两侧的材料反向绕"会让 r_c 的符号失去意义。</li>
     * </ul>
     */
    public double volume(@Nullable final Sketch sketch) {
        if (sketch == null || !sketch.id().equals(sketchId)) {
            return 0.0;
        }
        // ⚠ 刻意用**渲染精度**采样，而不是碰撞精度：
        // 质量是对"玩家想做的那个形状"的度量，不该随着碰撞分辨率的调节而变。
        // 用碰撞精度的话，以后为了省刚体把圆调粗，实体的质量会**跟着悄悄变小** ——
        // 那种 bug 极难查（"我只是调了性能，怎么手感变了"）。
        final java.util.List<SketchProfiles.Region> regions =
                SketchProfiles.nest(SketchProfiles.findLoops(
                        sketch, SketchProfiles.DEFAULT_WELD_TOLERANCE,
                        SketchProfiles.Sampling.RENDER));
        double area = 0.0;
        for (final SketchProfiles.Region region : regions) {
            if (Extrude.extrudable(region)) {
                area += SketchProfiles.netArea(region);
            }
        }
        if (area <= 1.0e-12) {
            return 0.0;
        }
        return switch (shape) {
            case Shape.Extrusion e -> {
                final double[] range = e.params().depthRange();
                yield area * Math.abs(range[1] - range[0]);
            }
            case Shape.Revolution r -> volumeOfRevolution(r.params(), regions, area);
            case Shape.Cut c -> 0.0; // 切割体不提供材料体积 ✓
            case Shape.Plane p -> 0.0; // 基准面不是材料 ✓
            case Shape.Fillet f -> 0.0; // 圆角本身不产生材料（它产生"要挖掉的盒子"）✓
            case Shape.Mirror m -> 0.0; // 镜像的材料由反射得出 ✓
            case Shape.Pattern pt -> 0.0; // 阵列材料由平移得出 ✓
        };
    }

    /** 帕普斯定理算回转体体积；轴穿过轮廓时返回 {@link Double#NaN}（调用方退回盒子和）。 */
    private static double volumeOfRevolution(final Revolve.Params params,
                                             final java.util.List<SketchProfiles.Region> regions,
                                             final double area) {
        double total = 0.0;
        for (final SketchProfiles.Region region : regions) {
            if (!Extrude.extrudable(region)) {
                continue;
            }
            final double a = SketchProfiles.netArea(region);
            if (a <= 1.0e-12) {
                continue;
            }
            // 形心到轴的距离（帕普斯定理要的是**形心**，不是顶点平均）
            final double[] centroid = SketchProfiles.centroid(region.outer().points());
            final double rc = Revolve.radius(params, centroid[0], centroid[1]);
            // ⚠ 轴穿过轮廓时帕普斯**不适用**（两侧材料反向绕，r_c 的符号失去意义）
            double minR = Double.MAX_VALUE;
            double maxR = -Double.MAX_VALUE;
            for (final double[] p : region.outer().points()) {
                final double r = signedRadius(params, p);
                minR = Math.min(minR, r);
                maxR = Math.max(maxR, r);
            }
            if (minR < 1.0e-9 && maxR > -1.0e-9) {
                return Double.NaN;
            }
            total += Math.abs(params.signedRadians()) * rc * a;
        }
        return total;
    }

    /** 带符号的垂距（用于判断轮廓在轴的哪一侧）。 */
    private static double signedRadius(final Revolve.Params params, final double[] p) {
        final double len = Math.hypot(params.dirU(), params.dirV());
        final double du = params.dirU() / len;
        final double dv = params.dirV() / len;
        return du * (p[1] - params.axisV()) - dv * (p[0] - params.axisU());
    }

    /** 碰撞盒数的**硬上限**（保护物理开销的那道闸）。 */
    public static final int COLLISION_MAX_BOXES = 192;
    /**
     * 回转体的**整圈楔数**。
     *
     * <p>⚠ 这是"贴合"与"盒子数"之间的旋钮，而且两侧都**不会超出**表面（内切）：
     * 楔数越少，半径大的一侧缝越宽（是**缝**，不是外凸）。12 个楔子时
     * 半径 7.5 的环上缝宽约 {@code 2·7.5·(1−cos15°) ≈ 0.5} 格。
     */
    public static final int COLLISION_REVOLVE_WEDGES = 12;
    /**
     * 目标格边长（格）。**越小越贴合、盒子越多**。
     *
     * <p>0.5 格 ≈ 半格的阶梯：视觉上基本贴着线框（实测 1.0 格太糙、明显外凸）。
     * 想更贴就调到 0.25，代价是盒子数大约翻倍。
     */
    public static final double COLLISION_TARGET_CELL = 0.5;
    /** 分辨率的上下限 —— 太大白烧 CPU，太小形状糙得不像。 */
    public static final int COLLISION_MIN_RES = 8;
    public static final int COLLISION_MAX_RES = 96;

    /**
     * 这个实体的**碰撞盒预算**。
     *
     * <p>用户要求"可配置，同时根据模型大小在一定范围内变化"。做法是**两个旋钮分开**：
     * <ul>
     *   <li>{@link #COLLISION_TARGET_CELL} 决定**分辨率** —— 它乘上网格的真实尺寸
     *       自然得到"越大的模型分到越多盒子"，即"随模型大小变化"；</li>
     *   <li>{@link #COLLISION_MAX_BOXES} 决定**硬上限** —— 物理开销是硬约束，
     *       不能被模型大小带着无限涨。</li>
     * </ul>
     *
     * <p>⚠ 千万别再拿**草图**的包围盒去估回转体的尺寸：回转体的实际尺寸是
     * {@code 2×(离轴距离 + 半径)}，比草图里那个轮廓大好几倍。按草图估会让格子大得
     * 又糙又外凸（这是实测过的 bug，见 {@code voxelBoxes} 的注释）。
     */
    public static int collisionBoxBudget(@Nullable final Sketch sketch) {
        return COLLISION_MAX_BOXES;
    }

    /**
     * 实体的总体包围盒；没有几何时返回 null。
     */
    @Nullable
    public double[] bounds(@Nullable final Sketch sketch) {
        return Extrude.bounds(boxes(sketch));
    }

    /** 空网格（常量，避免每次 new）。 */
    private static final SolidMesh.Mesh EMPTY_MESH =
            new SolidMesh.Mesh(java.util.List.of(), new int[0]);

    /**
     * 实体的**三角网格**（贴图用；plot 局部系）。
     *
     * <p>两种成形方式都有网格了：拉伸走 {@link SolidMesh#build}，旋转走 {@link Revolve#mesh}。
     * 调用方拿到空网格时应当**退回线框渲染**（轮廓不闭合、草图没同步过来等）。
     */
    public SolidMesh.Mesh mesh(@Nullable final Sketch sketch) {
        if (sketch == null || !sketch.id().equals(sketchId)) {
            return EMPTY_MESH;
        }
        return switch (shape) {
            case Shape.Extrusion e -> SolidMesh.build(sketch, e.params());
            case Shape.Revolution r -> Revolve.mesh(sketch, r.params());
            case Shape.Cut c -> EMPTY_MESH; // 切割体不渲染 ✓
            case Shape.Plane p -> EMPTY_MESH; // 基准面不是材料 ✓
            case Shape.Fillet f -> EMPTY_MESH; // 圆角本身不产生材料（它产生"要挖掉的盒子"）✓
            case Shape.Mirror m -> EMPTY_MESH; // 镜像的材料由反射得出 ✓
            case Shape.Pattern pt -> EMPTY_MESH; // 阵列材料由平移得出 ✓
        };
    }

    /**
     * 实体的**渲染线框**（棱柱棱边，从轮廓生成，plot 局部系）。
     *
     * <p>⚠ 渲染**不能**用 {@link #boxes}：那是给碰撞用的盒分解，
     * 圆会被切成几十个盒子、看起来是"立方体拼的"。
     */
    public List<Vector3d[]> edges(@Nullable final Sketch sketch) {
        if (sketch == null || !sketch.id().equals(sketchId)) {
            return List.of();
        }
        return switch (shape) {
            case Shape.Extrusion e -> Extrude.prismEdges(sketch, e.params());
            case Shape.Revolution r -> Revolve.edges(sketch, r.params());
            case Shape.Cut c -> List.of(); // 切割体不画线框 ✓
            case Shape.Plane p -> List.of(); // 基准面不是材料 ✓
            case Shape.Fillet f -> List.of(); // 圆角本身不产生材料（它产生"要挖掉的盒子"）✓
            case Shape.Mirror m -> List.of(); // 镜像的材料由反射得出 ✓
            case Shape.Pattern pt -> List.of(); // 阵列材料由平移得出 ✓
        };
    }

    /**
     * 设计树的**分组**：哪些草图被"吸收"到特征下、哪些是自由的。
     *
     * <h2>为什么要抽出来</h2>
     * SolidWorks 的 FeatureManager 约定：草图一旦被某个特征用掉，就**吸收到该特征下面**
     *（要展开特征才看得到）；没被用掉的留在顶层（*unabsorbed sketch*）。
     * 我们的树原来把草图一律放顶层，"这张草图被谁用、改它会影响谁"在树里看不出来。
     *
     * <p>把"谁挂在哪"做成**纯函数**：UI 那边只是把它画出来，
     * 而分组规则本身可以离线验证（一张草图被两个特征用、指向已删除草图等边界都在这里定死）。
     *
     * @param solids    已有的实体（特征）
     * @param sketchIds 现存草图的 id
     * @return 分组结果
     */
    public static TreeGrouping groupSketches(final Collection<Solid> solids,
                                            final Collection<UUID> sketchIds) {
        // ⚠ 两个入参都允许为 null：调用方是 UI，数据还没同步过来时集合可能是空的/没有。
        // 树画不出来可以接受，抛异常把整个界面带崩不行。
        if (solids == null || solids.isEmpty() || sketchIds == null) {
            return new TreeGrouping(
                    sketchIds == null ? List.of() : List.copyOf(sketchIds),
                    java.util.Map.of());
        }
        final Map<UUID, List<UUID>> absorbed = new LinkedHashMap<>();
        final Set<UUID> consumed = new LinkedHashSet<>();
        for (final Solid solid : solids) {
            // ⚠ 指向**已不存在**的草图时不挂上去：那会画出一个点不开的节点。
            // 这种脏数据由 dropOfSketch 负责清理，UI 这边只是别再放大它。
            if (solid.sketchId() == null || !sketchIds.contains(solid.sketchId())) {
                continue;
            }
            absorbed.computeIfAbsent(solid.id(), k -> new ArrayList<>()).add(solid.sketchId());
            consumed.add(solid.sketchId());
        }
        final List<UUID> free = new ArrayList<>();
        for (final UUID sketch : sketchIds) {
            if (!consumed.contains(sketch)) {
                free.add(sketch);
            }
        }
        return new TreeGrouping(List.copyOf(free), absorbed);
    }

    /**
     * 设计树的分组结果。
     *
     * @param freeSketches 没被任何特征用掉的草图（留在顶层）
     * @param absorbed     特征 id → 挂在它下面的草图 id（**一张草图可能同时挂在多个特征下**）
     */
    public record TreeGrouping(List<UUID> freeSketches, Map<UUID, List<UUID>> absorbed) {

        /** 某个特征下面挂了几张草图。 */
        public int absorbedCount(final UUID solidId) {
            final List<UUID> list = absorbed.get(solidId);
            return list == null ? 0 : list.size();
        }

        /**
         * 某张草图被几个特征使用（UI 要拿它提示"改草图会影响 N 处"）。
         *
         * <p>⚠ 这个提示是必需的：几何是**现算**的，改草图会连带改变所有引用它的特征。
         * 不说清的话，玩家改完发现别处也变了，会以为是 bug。
         */
        public int consumerCount(final UUID sketchId) {
            int n = 0;
            for (final List<UUID> list : absorbed.values()) {
                for (final UUID id : list) {
                    if (id.equals(sketchId)) {
                        n++;
                    }
                }
            }
            return n;
        }
    }

    /** 换一种成形方式（改深度/角度时用）—— **外观保留**。 */
    public Solid withShape(final Shape next) {
        return new Solid(id, body, sketchId, index, next, appearance);
    }

    /** 换一种外观（贴图 / 平铺比例）—— 成形方式与身份都不动。 */
    public Solid withAppearance(final SolidAppearance next) {
        return new Solid(id, body, sketchId, index, shape, next);
    }

    /** 换一组**拉伸**参数（把实体变成拉伸体）。 */
    public Solid withParams(final Extrude.Params next) {
        return withShape(new Shape.Extrusion(next));
    }

    /** 换一组**旋转**参数（把实体变成旋转体）。 */
    public Solid withRevolution(final Revolve.Params next) {
        return withShape(new Shape.Revolution(next));
    }

    /**
     * 在给定的一组草图里找出本实体引用的那张。
     *
     * @return 找不到时返回 null（草图被删 / 还没同步过来）
     */
    @Nullable
    public Sketch resolve(final Collection<Sketch> sketches) {
        for (final Sketch sketch : sketches) {
            if (sketch.id().equals(sketchId)) {
                return sketch;
            }
        }
        return null;
    }

    /**
     * 一个**切割特征** ✓：它的草图、平面、拉伸参数、以及它自己的盒子。
     *
     * <p>盒子用来做碰撞侧的减法；草图+参数用来重新生成**网格**参与 CSG ✓。
     */
    /** CSG 渲染开关（修好 {@code Csg.Node} 之后再打开 ✓）。 */
    public static final boolean CSG_RENDER_ENABLED = true;

    /** 上一次切割 CSG 的诊断（客户端打日志用 ✓；本包被离线编译 ⇒ 不能直接用 LOGGER ✓）。 */
    public static String lastCsgDiag = "none";

    /** 全链路追踪（客户端每帧取走并打成日志）✓ sketch 包不能直接用 LOGGER */
    public static final java.util.List<String> TRACE = new java.util.ArrayList<>();

    /** 追加一条追踪（有上限，避免刷爆）✓ */
    public static void trace(final String line) {
        if (line == null || TRACE.size() > 400) {
            return;
        }
        TRACE.add(line);
    }

    /** 最近一次圆角 CSG 的诊断（不含 idx 以外信息，供 dump 拼装）✓ */
    private static String lastFilletDiag = "";

    /**
     * 把**平面坐标**里的盒子（{@code {u0,v0,d0,u3,v3,d3}}）换成**局部坐标**的封闭网格 ✓。
     *
     * <p>只作为"没有贴图网格时的兜底" ✓ —— 正常路径用 {@code solid.mesh(sketch)}（本来就
     * 已经是局部坐标 ✓）。注意：平面基底若与方块轴不平行，这里的盒子会是个斜棱柱，
     * 用它的 AABB 只是近似 ✓（兜底路径可以接受 ✓）。
     */
    private static SolidMesh.Mesh boxesInLocal(final Sketch sketch, final List<double[]> boxes) {
        if (sketch == null || boxes == null || boxes.isEmpty()) {
            return new SolidMesh.Mesh(new ArrayList<>(), new int[0]);
        }
        final SketchPlane pl = sketch.plane();
        final Vector3d n = pl.normal();
        final List<double[]> local = new ArrayList<>(boxes.size());
        for (final double[] b : boxes) {
            if (b == null || b.length < 6) {
                continue;
            }
            final Vector3d lo = pl.toLocal(b[0], b[1]).fma(b[2], n, new Vector3d());
            final Vector3d hi = pl.toLocal(b[3], b[4]).fma(b[5], n, new Vector3d());
            local.add(new double[] {lo.x, lo.y, lo.z, hi.x, hi.y, hi.z});
        }
        return SolidMesh.fromBoxes(local);
    }

    /** 一个实体**评估后**的几何（已扣本体切割、已倒圆角）✓ */
    private record Eval(List<double[]> boxes, List<Vector3d[]> edges, SolidMesh.Mesh mesh) {
    }

    private record CutFeat(SketchPlane plane, Sketch sketch, Extrude.Params params,
                           List<double[]> boxes) {
    }

    /** 把一批实体解析成"实体 + 它的盒子 + 它的渲染线框 + 贴图网格"，供渲染与碰撞共用。 */
    public static List<Placed> placeAll(final Collection<Solid> solids,
                                        final Collection<Sketch> sketches) {
        // ★ 需求 ⑥：先按 body 收集**切割体**的盒子（这些就是要从别的实体上挖掉的洞 ✓）
        //   盒子层布尔：稳、快、与碰撞天然一致（物理只吃盒子 ✓）
        final java.util.Map<UUID, List<CutFeat>> cutters = new java.util.HashMap<>();
        for (final Solid solid : solids) {
            if (!(solid.shape() instanceof Shape.Cut cut)) {
                continue;
            }
            final Sketch cs = solid.resolve(sketches);
            if (cs == null) {
                // ★ 诊断：切割体**解析不到它的草图** ⇒ 这次切割什么都不会发生 ✓
                //   （实测"完全没有切割"也可能就是这一条 ✓ 常见于草图集合没传全 ✓）
                lastCsgDiag = "cutResolve=null body=" + solid.body();
                continue;
            }
            cutters.computeIfAbsent(solid.body(), k -> new ArrayList<>())
                    .add(new CutFeat(cs.plane(), cs, cut.params(),
                            Extrude.boxesOf(cs, cut.params())));
        }
        // ★ item 8：**圆角**要挖掉的盒子（局部坐标，直接可用 ✓ 不需要平面换算）
        final java.util.Map<UUID, Solid> byId = new java.util.HashMap<>();
        final java.util.Map<UUID, Eval> evalById = new java.util.HashMap<>();
        final java.util.Set<UUID> fallbackIds = new java.util.HashSet<>();
        for (final Solid s2 : solids) {
            byId.put(s2.id(), s2);
        }
        final java.util.Map<UUID, List<Csg.Polygon>> filletPolyByBody = new java.util.HashMap<>();
        // ★ 原始棱数据（a,b,n1,n2,r[,curveGroup]）：几何验证式求值用 ✓
        //   **按"被引用的实体 id"归集**（不是 body）：一个 body 可能有多个材料实体，
        //   圆角只能作用在**拥有这条棱的那个实体**上，否则探针看到的盒子/CSG 全在别处
        //   ⇒ 实测 quads 0/4（"创建后模型没变化"的真正原因）✓
        final StringBuilder filletLog = new StringBuilder();
        final java.util.Map<UUID, List<double[]>> filletEdgeBySolid =
                new java.util.HashMap<>();
        // ★ 圆角引用指向**镜像/阵列特征**时（用户在镜像产物上点棱）：
        //   棱数据单独归集，等镜像/阵列产物生成后**在变换后的空间**里应用 ✓
        final java.util.Map<UUID, List<double[]>> filletEdgeByMirror = new java.util.HashMap<>();
        final java.util.Map<UUID, List<double[]>> filletEdgeByPattern = new java.util.HashMap<>();
        int curveGroupSeq = 0;

        final java.util.function.BiFunction<Solid, Sketch, Eval> baseEvalOf = (srcS, sk) -> {
            if (srcS == null || sk == null) {
                return null;
            }
            final List<double[]> raw = srcS.boxes(sk);
            final List<double[]> cutBoxes = new ArrayList<>();
            for (final CutFeat cf : cutters.getOrDefault(srcS.body(), List.of())) {
                cutBoxes.addAll(cf.boxes());
            }
            final List<double[]> baseBoxes = cutBoxes.isEmpty() ? raw
                    : subtractBoxes(raw, cutBoxes);
            final List<Vector3d[]> baseEdges = srcS.edges(sk);
            SolidMesh.Mesh baseMesh = srcS.mesh(sk);
            if (baseMesh != null && !baseMesh.isEmpty() && !cutBoxes.isEmpty()) {
                final List<Csg.Polygon> bp = ensureOutward(CsgMesh.fromMesh(baseMesh, null));
                final List<Csg.Polygon> cp = ensureOutward(
                        CsgMesh.fromMesh(SolidMesh.fromBoxes(cutBoxes), null));
                if (!bp.isEmpty() && !cp.isEmpty()) {
                    final List<Csg.Polygon> rr = subtractLocal(bp, cp);
                    if (!rr.isEmpty()) {
                        baseMesh = CsgMesh.toMesh(rr);
                    }
                }
            }
            return new Eval(baseBoxes, baseEdges, baseMesh);
        };
        // ★ 圆角象限探针用的**体级**材料盒子（多个材料实体合并、只扣切割、不扣圆角）✓
        //   按单个实体取会漏：实测弧所在实体的 boxes 不含该区域/为空 ⇒ quads 0/4
        //   ⇒ 弧棱永远倒不出来（预览用的是全体盒子，所以预览正常）
        final java.util.Map<UUID, List<double[]>> bodyProbeBoxes = new java.util.HashMap<>();
        for (final Solid s : solids) {
            if (s.shape instanceof Shape.Cut || s.shape instanceof Shape.Plane
                    || s.shape instanceof Shape.Fillet || s.shape instanceof Shape.Mirror
                    || s.shape instanceof Shape.Pattern) {
                continue;
            }
            final Sketch sk = s.resolve(sketches);
            if (sk == null) {
                continue;
            }
            List<double[]> bx = s.boxes(sk);
            if (bx == null) {
                bx = List.of();
            }
            final List<CutFeat> cf = cutters.get(s.body());
            if (cf != null && !cf.isEmpty()) {
                final List<double[]> cb = new ArrayList<>();
                for (final CutFeat f : cf) {
                    cb.addAll(f.boxes());
                }
                if (!cb.isEmpty()) {
                    bx = subtractBoxes(bx, cb);
                }
            }
            final List<double[]> agg = bodyProbeBoxes.computeIfAbsent(
                    s.body(), k -> new ArrayList<>());
            agg.addAll(bx);
        }
        for (final java.util.Map.Entry<UUID, List<double[]>> en : bodyProbeBoxes.entrySet()) {
            trace("fillet: bodyProbe body=" + shortId(en.getKey())
                    + " boxes=" + en.getValue().size());
        }
        for (final Solid s2 : solids) {
            if (!(s2.shape() instanceof final Shape.Fillet f)) {
                continue;
            }
            trace("fillet: feature idx=" + s2.index() + " id=" + shortId(s2.id())
                    + " body=" + shortId(s2.body()) + " radius=" + f.radius()
                    + " edges=" + f.edges().size());
            final java.util.Map<UUID, List<Csg.Polygon>> polysBySrc =
                    new java.util.HashMap<>();
            // ★ 先按"这个特征自己的引用"收集，再**把首尾相接的相邻段并成一条链** ✓
            //   旧存档里的曲线棱是"一段一个引用"（48 段 = 48 个楔块）⇒ 逐段切会既慢又
            //   容易在合并多个互不相交的切割体时把网格弄坏（实测贴图消失/连接处破面）
            final java.util.Map<UUID, List<double[]>> featSolid = new java.util.HashMap<>();
            final java.util.Map<UUID, List<double[]>> featMirror = new java.util.HashMap<>();
            final java.util.Map<UUID, List<double[]>> featPattern = new java.util.HashMap<>();
            for (final Shape.EdgeRef ref : f.edges()) {
                trace("fillet:  ref solidId=" + shortId(ref.solidId())
                        + " a=(" + fmt(ref.ax()) + "," + fmt(ref.ay()) + "," + fmt(ref.az())
                        + ") b=(" + fmt(ref.bx()) + "," + fmt(ref.by()) + "," + fmt(ref.bz())
                        + ") n1=(" + fmt(ref.n1x()) + "," + fmt(ref.n1y()) + "," + fmt(ref.n1z())
                        + ") n2=(" + fmt(ref.n2x()) + "," + fmt(ref.n2y()) + "," + fmt(ref.n2z())
                        + ")");
                Solid src = byId.get(ref.solidId());
                String srcNote = "";
                // ★ 用户在**镜像/阵列产物**上点的棱：引用指向镜像/阵列特征本身。
                //   这些产物不经过主循环的材料累积 ⇒ 单独归集，在产物生成时做 ✓
                if (src != null && (src.shape instanceof Shape.Mirror
                        || src.shape instanceof Shape.Pattern)) {
                    final Vector3d mn1 = new Vector3d(ref.n1x(), ref.n1y(), ref.n1z());
                    final Vector3d mn2 = new Vector3d(ref.n2x(), ref.n2y(), ref.n2z());
                    if (mn1.lengthSquared() < 1.0e-12) {
                        filletLog.append(" [f").append(s2.index()).append(":no-normals]");
                        continue;
                    }
                    mn1.normalize();
                    if (mn2.lengthSquared() < 1.0e-12) {
                        mn2.zero();
                    } else {
                        mn2.normalize();
                    }
                    final int cg = ref.isCurve() ? (++curveGroupSeq) : -1;
                    final boolean isMirror = src.shape instanceof Shape.Mirror;
                    expandRef(isMirror ? featMirror : featPattern,
                            src.id(), ref, mn1, mn2, f.radius(), cg, null, -1);
                    trace("fillet:  target=" + (isMirror ? "mirror " : "pattern ")
                            + shortId(src.id()) + " segs=" + (ref.isCurve()
                                    ? ref.curveSegs().size() : 1));
                    continue;
                }
                // ★ 拾取到"切割体/基准面/镜像"等**非材料**实体的面时（最常见的例子：
                //   孔的圆柱面来自切割体的网格 ⇒ hover 给的是 Cut 的 id），
                //   solidId 会指向一个永远不会进主循环的实体 ⇒ 圆角数据挂在那儿没人用
                //   ⇒ 表现就是"预览正常、创建后模型不变"（实测）。这里统一改判为材料实体 ✓
                if (src != null && (src.shape instanceof Shape.Plane
                        || src.shape instanceof Shape.Cut
                        || src.shape instanceof Shape.Mirror
                        || src.shape instanceof Shape.Pattern
                        || src.shape instanceof Shape.Fillet)) {
                    srcNote = "src-nonmaterial->";
                    src = null;
                }
                if (src == null) {
                    // ★ 回退：老特征/被重建过的特征，solidId 可能已失效。
                    //   按"端点落在盒子里"在**同一 body** 的实体中找最匹配的那个 ✓
                    final Vector3d ea = new Vector3d(ref.ax(), ref.ay(), ref.az());
                    for (final Solid cand : solids) {
                        if (!cand.body().equals(s2.body())
                                || cand.shape() instanceof Shape.Plane
                                || cand.shape() instanceof Shape.Fillet
                                || cand.shape() instanceof Shape.Mirror
                                || cand.shape() instanceof Shape.Pattern
                                || cand.shape() instanceof Shape.Cut) {
                            continue;
                        }
                        final Sketch csk = cand.resolve(sketches);
                        if (csk == null) {
                            continue;
                        }
                        boolean hitBox = false;
                        for (final double[] b : cand.boxes(csk)) {
                            if (ea.x > b[0] - 0.05 && ea.x < b[3] + 0.05
                                    && ea.y > b[1] - 0.05 && ea.y < b[4] + 0.05
                                    && ea.z > b[2] - 0.05 && ea.z < b[5] + 0.05) {
                                hitBox = true;
                                break;
                            }
                        }
                        if (hitBox) {
                            src = cand;
                            srcNote = "resolved-by-endpoint";
                            break;
                        }
                    }
                }
                if (src == null) {
                    filletLog.append(" [f").append(s2.index()).append(":src-null]");
                    continue;
                }
                final Sketch srcSketch = src.resolve(sketches);
                if (srcSketch == null) {
                    filletLog.append(" [f").append(s2.index()).append(":no-sketch]");
                    continue;
                }
                final SolidMesh.Mesh srcMesh = src.mesh(srcSketch);
                final Vector3d a = new Vector3d(ref.ax(), ref.ay(), ref.az());
                final Vector3d b = new Vector3d(ref.bx(), ref.by(), ref.bz());
                Vector3d[] nn = null;
                if (Math.abs(ref.n1x()) + Math.abs(ref.n1y()) + Math.abs(ref.n1z()) > 1.0e-6
                        && Math.abs(ref.n2x()) + Math.abs(ref.n2y()) + Math.abs(ref.n2z()) > 1.0e-6) {
                    nn = new Vector3d[] {
                        new Vector3d(ref.n1x(), ref.n1y(), ref.n1z()),
                        new Vector3d(ref.n2x(), ref.n2y(), ref.n2z())};
                }
                if (nn == null) {
                    nn = FilletCutter.faceNormals(srcMesh, a, b);
                }
                if (nn == null) {
                    filletLog.append(" [f").append(s2.index()).append(":no-normals]");
                    continue;
                }
                int edgeConvex = -1;
                if (!ref.isCurve() && srcMesh != null && !srcMesh.isEmpty()) {
                    final double[] info = FilletCutter.edgeInfo(srcMesh, a, b);
                    if (info != null) {
                        nn = new Vector3d[] {
                            new Vector3d(info[0], info[1], info[2]),
                            new Vector3d(info[3], info[4], info[5])};
                        edgeConvex = info[6] > 0.5 ? 1 : 0;
                        trace("fillet:  edgeInfo convex=" + edgeConvex
                                + " f1=(" + fmt(info[0]) + "," + fmt(info[1]) + ","
                                + fmt(info[2]) + ") f2=(" + fmt(info[3]) + ","
                                + fmt(info[4]) + "," + fmt(info[5]) + ")");
                    } else {
                        final Vector3d[] tn = FilletCutter.faceNormals(srcMesh, a, b);
                        if (tn != null) {
                            nn = new Vector3d[] {tn[0], tn[1]};
                            trace("fillet:  edgeInfo: 凸凹未知，用网格面法线校正");
                        }
                    }
                }
                // ★ 曲线棱（一条弧 = 一个 EdgeRef + 折线）⇒ 逐段展开 ✓
                //   直线棱只有一段，行为与以前完全一致。
                //   同一条曲线的所有段共享 **curveGroup**（求值时合成一个扫掠体）。
                final java.util.List<Csg.Polygon> prismPolys = new ArrayList<>();
                expandRef(featSolid, src.id(), ref, nn[0], nn[1], f.radius(),
                        ref.isCurve() ? (++curveGroupSeq) : -1, prismPolys, edgeConvex);
                trace("fillet:  prism polys=" + prismPolys.size()
                        + " src=" + shortId(src.id()) + " srcBody="
                        + shortId(src.body()) + srcNote
                        + (ref.isCurve() ? " curveSegs=" + ref.curveSegs().size() : ""));
                polysBySrc.computeIfAbsent(s2.body(), k -> new ArrayList<>())
                        .addAll(prismPolys);
            }
            // ★ 把首尾相接、转角平滑的相邻段并成一条链（兼容"一段一个引用"的旧存档）✓
            curveGroupSeq = mergeInto(filletEdgeBySolid, featSolid, curveGroupSeq);
            curveGroupSeq = mergeInto(filletEdgeByMirror, featMirror, curveGroupSeq);
            curveGroupSeq = mergeInto(filletEdgeByPattern, featPattern, curveGroupSeq);
            for (final java.util.Map.Entry<UUID, List<Csg.Polygon>> en : polysBySrc.entrySet()) {
                filletPolyByBody.computeIfAbsent(en.getKey(), k2 -> new ArrayList<>())
                        .addAll(en.getValue());
            }
        }
        final java.util.Map<UUID, List<double[]>> localCutters = new java.util.HashMap<>();
        for (final Solid s2 : solids) {
            if (!(s2.shape() instanceof Shape.Fillet)) {
                continue;
            }
            final List<double[]> boxes2 = s2.filletBoxes(byId);
            lastCsgDiag = "[fillet] idx=" + s2.index() + " boxes=" + boxes2.size()
                    + (boxes2.isEmpty() ? " (棱不是轴对齐 ⇒ 第一版不支持)" : "");
            if (!boxes2.isEmpty()) {
                // ★ 圆角盒子也按**被引用实体**归集（碰撞侧不能溢出到别的实体）✓
                // 与切割一致：按 body 作用 ✓
                localCutters.computeIfAbsent(s2.body(), k -> new ArrayList<>()).addAll(boxes2);
            }
        }
        final List<Placed> out = new ArrayList<>(solids.size());
        for (final Solid solid : solids) {
            if (solid.shape() instanceof Shape.Cut) {
                continue; // 切割体本身不产生材料/线框/网格 ✓
            }
            if (solid.shape() instanceof Shape.Plane) {
                continue; // 基准面不是材料 ✓（它由渲染侧单独画一个面片）
            }
            if (solid.shape() instanceof Shape.Fillet) {
                continue; // 圆角特征本身不产生材料（它的作用是"挖盒子"）✓
            }
            // ★★ bug ⑩ 的真凶：**镜像/阵列特征自己也会被主循环处理**，
            //  它的 mesh() 是空的（材料由反射/平移得出）⇒ 走到 CSG 的"盒子回退"，
            //  结果产生一块**没有贴图、却可被拾取的碎片几何**（日志实测
            //  `31:t162:tex0:b0:e37:Mirror:FB`）⇒ 玩家看到的是"本体点不到、只剩被挖掉那块"✗
            //  它们自己的产物由后面的 mirror / pattern 段输出 ⇒ 主循环必须跳过 ✓
            if (solid.shape() instanceof Shape.Mirror
                    || solid.shape() instanceof Shape.Pattern) {
                continue;
            }
            final Sketch sketch = solid.resolve(sketches);
            final List<Vector3d[]> edges = solid.edges(sketch);
            if (edges.isEmpty()) {
                // ★ 草图**还没同步过来** ⇒ 什么都不画 ✓（这是原有语义，离线用例也这么断言 ✓）
                if (sketch == null) {
                    lastCsgDiag = "solidEmpty idx=" + solid.index() + " kind="
                            + solid.shape().getClass().getSimpleName() + " sketchResolved=false";
                    continue;
                }
                // ★ 但草图在、几何算不出来 ⇒ **保留实体**（不再静默丢弃）
                //   丢掉它会让玩家看到"草图被吃掉、新拉伸不出现" ✗
                lastCsgDiag = "solidEmpty idx=" + solid.index() + " kind="
                        + solid.shape().getClass().getSimpleName() + " sketchResolved=true"
                        + " ents=" + sketch.entities().size()
                        + " cons=" + sketch.constructionEntities().size()
                        + " refs=" + sketch.referenceEntities().size()
                        + " loops=" + SketchProfiles.findLoops(sketch).size();
            }
            // 网格与平面法线只为**贴图**准备；没有贴图时调用方不看它们。
            // ⚠ 平面法线要带上：SolidMesh.faceKind 靠它判断"这个面是盖子还是侧面"。
            final Vector3d normal = sketch == null ? null : sketch.plane().normal();
            final List<double[]> rawBoxes = solid.boxes(sketch);
            final List<CutFeat> feats = cutters.get(solid.body());
            // ★ item 8：有圆角也算"要切" ⇒ 走 CSG（否则只有圆角时渲染不变、碰撞却变了 ✗）
            final List<double[]> filletCuts0 = localCutters.getOrDefault(solid.body(), List.of());
            final List<Csg.Polygon> filletPolys =
                    filletPolyByBody.getOrDefault(solid.body(), List.of());
            final boolean cut = (feats != null && !feats.isEmpty()) || !filletCuts0.isEmpty()
                    || !filletPolys.isEmpty();
            final List<double[]> bodyCutters = new ArrayList<>();
            if (cut) {
                for (final CutFeat f : feats) {
                    bodyCutters.addAll(f.boxes());
                }
            }
            // ★ item 8：圆角的局部盒子直接追加（它们已经是**本实体局部坐标** ✓）
            final List<double[]> filletCuts = filletCuts0;
            bodyCutters.addAll(filletCuts);
            // ① 碰撞/几何：仍是**盒子相减** ✓（物理只吃盒子，稳且快 ✓）
            final List<double[]> boxes = cut ? subtractBoxes(rawBoxes, bodyCutters) : rawBoxes;
            // ★ 圆角象限探针用的盒子 = **只扣切割、不扣圆角**的材料 ✓
            //   如果拿扣过圆角的盒子做探针，探针正好落在"已经被圆角挖掉的区域"
            //   ⇒ 全部判成体外（实测 quads 0/4 × 96 ⇒ 整条弧一条都没倒成）
            final List<double[]> probeBoxes;
            if (feats != null && !feats.isEmpty()) {
                final List<double[]> cutOnly = new ArrayList<>();
                for (final CutFeat f : feats) {
                    cutOnly.addAll(f.boxes());
                }
                probeBoxes = cutOnly.isEmpty() ? rawBoxes : subtractBoxes(rawBoxes, cutOnly);
            } else {
                probeBoxes = rawBoxes;
            }
            // ② 渲染：**CSG 真布尔** ✓ —— 外观保持原样（顶点带 normal+uv ⇒ 贴图照常）✓
            SolidMesh.Mesh mesh = solid.mesh(sketch);
            List<Vector3d[]> drawnEdges = edges;
            // ★ CSG 渲染链路（csg.js 移植）✓ —— Node.clipTo 的"清空后再传入"坑修好后已可用 ✓
            if (cut && CSG_RENDER_ENABLED) {
                // ★ 修实测"现在是完全没有切割"✗
                //   真因：`solid.mesh(sketch)` 是**为贴图准备的网格**，没有贴图时可能是空 ✗
                //   CSG 拿到空输入 ⇒ 差集为空 ⇒ 落回原网格 ⇒ 看着"完全没切" ✓
                //   ⇒ 空就用**几何网格**（由盒子生成）✓ 保证 CSG 的输入一定是封闭实体 ✓
                SolidMesh.Mesh targetMesh = solid.mesh(sketch);
                final int targetTris = targetMesh == null ? 0 : targetMesh.indices().length / 3;
                // ★ 目标网格为空 ⇒ 用盒子当 CSG 输入（只为了"能布尔"，**不能拿去渲染**）
                //   之前这个回退结果被当成渲染网格 ⇒ 模型上盖了一层无贴图的盒子
                //   （实测"圆角后贴图消失"就是这样）✗
                final boolean fallbackMesh = targetMesh == null || targetMesh.isEmpty();
                if (fallbackMesh) {
                    fallbackIds.add(solid.id());
                    targetMesh = boxesInLocal(sketch, rawBoxes);
                }
                List<Csg.Polygon> acc = ensureOutward(CsgMesh.fromMesh(targetMesh, null));
                int cutterTris = 0;
                for (final CutFeat f : feats) {
                    SolidMesh.Mesh cutterMesh = SolidMesh.build(f.sketch(), f.params());
                    if (cutterMesh == null || cutterMesh.isEmpty()) {
                        cutterMesh = boxesInLocal(f.sketch(), f.boxes());
                    }
                    cutterTris += cutterMesh.indices().length / 3;
                    // ★ 修实测"仍然没有切割"✗ ✗
                    //   关键事实：`SolidMesh` 的顶点**已经是局部坐标**（`Extrude.onPlane` 已把
                    //   平面坐标 (u,v,depth) 换算成局部/世界点）✓
                    //   ⇒ 切割体网格与目标网格**本来就在同一个空间** ⇒ **不需要再变换** ✓
                    //   （之前用 `CsgMesh.between` 又按平面坐标变换了一次 ⇒ 切割体被搬到别处
                    //     ⇒ 差集切不到任何面 ⇒ 日志里 resultPolys 与 target 三角数一样 ✗）
                    final List<Csg.Polygon> cutterPolys = CsgMesh.fromMesh(cutterMesh, null);
                    if (cutterPolys.isEmpty()) {
                        continue;
                    }
                    acc = Csg.subtract(acc, cutterPolys);
                }
                lastCsgDiag = "tris target=" + targetTris + " cutter=" + cutterTris
                        + " resultPolys=" + acc.size();
                // ★ 圆角数据按**实体 id**取（一个 body 可能有多个材料实体）
                final List<double[]> filletEdgeData =
                        filletEdgeBySolid.getOrDefault(solid.id(), List.of());
                trace("fillet: solid idx=" + solid.index() + " id=" + shortId(solid.id())
                        + " body=" + shortId(solid.body())
                        + " edgeData=" + filletEdgeData.size()
                        + " probeBoxes=" + (bodyProbeBoxes.get(solid.body()) == null ? 0
                                : bodyProbeBoxes.get(solid.body()).size())
                        + " bySolidKeys=[" + bodyKeys(filletEdgeBySolid) + "]");
                if (!filletEdgeData.isEmpty()) {
                    if (filletLog.length() < 400) {
                        filletLog.append(" [s").append(solid.index()).append(']');
                    }
                    final List<double[]> probeAgg = bodyProbeBoxes.get(solid.body());
                    acc = applyFillets(acc, filletEdgeData, solid.index(), "solid", filletLog,
                            probeAgg != null && !probeAgg.isEmpty() ? probeAgg : probeBoxes);
                    trace("fillet: applied idx=" + solid.index() + " id=" + shortId(solid.id())
                            + " polys=" + acc.size() + " " + meshHealth(acc));
                }
                if (!filletCuts.isEmpty()) {
                    final List<Csg.Polygon> fb = CsgMesh.fromMesh(
                            SolidMesh.fromBoxes(filletCuts), null);
                    if (!fb.isEmpty() && filletPolys.isEmpty()) {
                        acc = Csg.subtract(acc, fb);
                    }
                }
                if (!acc.isEmpty()) {
                    mesh = CsgMesh.toMesh(acc);
                    // 线框也从结果提（旧轮廓已经不成立 ⇒ 画出来会"线框完整、实体有洞"✗）
                    final List<Vector3d[]> sharp = CsgMesh.sharpEdges(acc, 25.0);
                    if (!sharp.isEmpty()) {
                        drawnEdges = sharp;
                    }
                }
            }
            // ★ bug ⑨：缓存**评估后**的几何（已扣切割、已倒圆角），供镜像/阵列复用 ✓
            evalById.put(solid.id(), new Eval(boxes, drawnEdges, mesh));
            out.add(new Placed(solid, boxes, drawnEdges, mesh, normal));
        }
        // ★ item 9：**镜像特征**的产物（反射源特征的盒子/线框/网格）
        //   它们作为**该镜像特征自己的 Placed** 输出 ⇒ 树里只有"镜像N"一行 ✓（与 SolidWorks 一致）
        for (final Solid mirror : solids) {
            if (!(mirror.shape() instanceof final Shape.Mirror m)) {
                continue;
            }
            final double[] o = {m.ox(), m.oy(), m.oz()};
            final double[] n = {m.nx(), m.ny(), m.nz()};
            // ★ bug ⑨ 系统性修法：
            //   ① 每个源取**评估后**的几何（已扣切割、已倒圆角）
            //   ② **切割源不再跳过**：切割盒反射后从镜像材料里减掉（洞跟着反射走）
            //   ③ 每个源**单独输出**一份 Placed，用**源自己的外观** ⇒ 贴图不串号 ✓
            final List<double[]> cutBoxes = new ArrayList<>();
            // ★ 切割源镜像后也要有线框（用户实测"镜像的切割没有线框"）✓
            final List<Vector3d[]> cutWireEdges = new ArrayList<>();
            // ★ 渲染网格用**真实切割体网格**，不用盒子近似（否则切割面贴图错/轮廓成阶梯）
            final List<SolidMesh.Mesh> cutMeshes = new ArrayList<>();
            final List<Csg.Polygon> filletPend = new ArrayList<>();
            for (final UUID sid : m.sources()) {
                final Solid src = byId.get(sid);
                if (src == null || src.shape instanceof Shape.Plane
                        || src.shape instanceof Shape.Fillet
                        || src.shape instanceof Shape.Mirror) {
                    continue;
                }
                final Sketch srcSketch = src.resolve(sketches);
                if (src.shape() instanceof Shape.Cut cut0 && srcSketch != null) {
                    for (final double[] b : Extrude.boxesOf(srcSketch, cut0.params())) {
                        cutBoxes.add(reflectBox(b, o, n));
                    }
                    for (final Vector3d[] ce : Extrude.prismEdges(srcSketch, cut0.params())) {
                        cutWireEdges.add(new Vector3d[] {reflectPoint(ce[0], o, n),
                                reflectPoint(ce[1], o, n)});
                    }
                    final SolidMesh.Mesh cutMesh = SolidMesh.build(srcSketch, cut0.params());
                    if (cutMesh != null && !cutMesh.isEmpty()) {
                        cutMeshes.add(reflectMesh(cutMesh, o, n));
                    }
                    continue;
                }
                final Eval e = baseEvalOf.apply(src, srcSketch);
                if (e == null) {
                    continue;
                }
                final List<double[]> boxes = new ArrayList<>(e.boxes().size());
                for (final double[] b : e.boxes()) {
                    boxes.add(reflectBox(b, o, n));
                }
                final List<Vector3d[]> edges = new ArrayList<>(e.edges().size());
                for (final Vector3d[] ed : e.edges()) {
                    edges.add(new Vector3d[] {reflectPoint(ed[0], o, n),
                            reflectPoint(ed[1], o, n)});
                }
                // ★ 被镜像的切割也要有线框 ✓（以前只减了盒子，产品上没有切割的线）
                edges.addAll(cutWireEdges);
                SolidMesh.Mesh mesh = e.mesh() == null ? null : reflectMesh(e.mesh(), o, n);
                if (!cutMeshes.isEmpty()) {
                    // ★ 真网格减法（盒子近似会让切割面贴图错、轮廓成阶梯）
                    mesh = subtractCutterMeshes(mesh, cutMeshes);
                }
                final List<double[]> finalBoxes = cutBoxes.isEmpty() ? boxes
                        : subtractBoxes(boxes, cutBoxes);
                // ★ 镜像产物上的圆角：棱数据本来就存成镜像后的局部坐标 ⇒ 直接在产物上应用 ✓
                //   （以前这一步是死代码：filletPend 从没被填过 ⇒ "镜像体上无法圆角"）
                // ★★ 新增：**圆角特征本身也可以被镜像** —— 把被选中圆角特征的棱反射到镜像
                //   空间，与“在镜像体上点棱倒圆”的 filletEdgeByMirror 合并成一次 applyFillets ✓
                final java.util.Map<UUID, List<double[]>> srcFilletMap = new java.util.HashMap<>();
                for (final UUID fsid : m.sources()) {
                    final Solid fs = byId.get(fsid);
                    if (fs == null || !(fs.shape() instanceof final Shape.Fillet fil)) {
                        continue;
                    }
                    for (final Shape.EdgeRef ref : fil.edges()) {
                        if (!src.id().equals(ref.solidId())) {
                            continue; // 这条棱属于别材料 ⇒ 由那个材料的产物去倒
                        }
                        final Shape.EdgeRef mr = reflectRef(ref, o, n);
                        final Vector3d rn1 = reflectDir(
                                new Vector3d(ref.n1x(), ref.n1y(), ref.n1z()), n);
                        final Vector3d rn2 = reflectDir(
                                new Vector3d(ref.n2x(), ref.n2y(), ref.n2z()), n);
                        expandRef(srcFilletMap, src.id(), mr, rn1, rn2, fil.radius(),
                                mr.isCurve() ? (++curveGroupSeq) : -1, null, -1);
                    }
                }
                final List<double[]> mirEdges = new ArrayList<>();
                final List<double[]> mirOwn = filletEdgeByMirror.get(mirror.id());
                if (mirOwn != null) {
                    mirEdges.addAll(mirOwn);
                }
                mirEdges.addAll(srcFilletMap.getOrDefault(src.id(), List.of()));
                if (!mirEdges.isEmpty()
                        && mesh != null && !mesh.isEmpty()) {
                    List<Csg.Polygon> mp = ensureOutward(CsgMesh.fromMesh(mesh, null));
                    if (!mp.isEmpty()) {
                        trace("fillet: mirror=" + shortId(mirror.id())
                                + " edges=" + mirEdges.size()
                                + " probeBoxes=" + finalBoxes.size());
                        mp = applyFillets(mp, mirEdges, mirror.index(), "mirror", filletLog,
                                finalBoxes);
                        if (!mp.isEmpty()) {
                            mesh = CsgMesh.toMesh(mp);
                            trace("fillet: mirror=" + shortId(mirror.id())
                                    + " polys=" + mp.size() + " " + meshHealth(mp));
                        }
                    }
                }
                if (finalBoxes.isEmpty() || edges.isEmpty()) {
                    continue;
                }
                // 视图实体：id/index 用镜像特征（选中/名字一致），形状与外观用**源的**
                final Solid view = new Solid(mirror.id(), mirror.body(), src.sketchId(),
                        mirror.index(), src.shape(), src.appearance());
                // ★★ 贴图的投影法线必须用**源草图平面的法线**，不能用镜像平面法线 ✗
                //   （用户实测"镜像的贴图极度拉伸"：`planeNormal` 是贴图 UV 的投影轴，
                //    拿镜像面法线去投影 ⇒ 与几何方向完全不匹配 ⇒ 整面被拉成一条）
                final Vector3d viewNormal = srcSketch != null && srcSketch.plane() != null
                        ? srcSketch.plane().normal()
                        : new Vector3d(m.nx(), m.ny(), m.nz());
                out.add(new Placed(view, finalBoxes, edges, mesh, viewNormal));
            }
        }
        // item 10: **阵列**产物（N×M 个副本，归在"阵列N"一行）
        for (final Solid pat : solids) {
            if (!(pat.shape() instanceof final Shape.Pattern pt)) {
                continue;
            }
            final java.util.List<double[]> offsets = new ArrayList<>();
            offsets.add(new double[] {0, 0, 0});
            for (final Shape.Dir d : pt.dirs()) {
                final List<double[]> next = new ArrayList<>();
                for (final double[] o0 : offsets) {
                    for (int i = 0; i < d.count(); i++) {
                        final double t = d.spacing() * i;
                        next.add(new double[] {o0[0] + d.dx() * t, o0[1] + d.dy() * t,
                                o0[2] + d.dz() * t});
                    }
                }
                offsets.clear();
                offsets.addAll(next);
            }
            // ★ bug ⑨：阵列同样取**评估后**的几何 + 每个源单独输出（用源自己的外观）✓
            final List<double[]> cutOffsets = new ArrayList<>();
            final List<double[]> cutRaw = new ArrayList<>();
            // ★ 切割的线框也要跟着阵列（与镜像同一处缺口：`Solid.edges()` 对切割返回空）
            final List<Vector3d[]> cutWireRaw = new ArrayList<>();
            // ★ 渲染网格用真实切割体网格（盒子近似会让切割面贴图错/轮廓成阶梯）
            final List<SolidMesh.Mesh> cutMeshesRaw = new ArrayList<>();
            for (final UUID sid : pt.sources()) {
                final Solid src = byId.get(sid);
                if (src == null || !(src.shape() instanceof Shape.Cut cut0)) {
                    continue;
                }
                final Sketch srcSketch = src.resolve(sketches);
                if (srcSketch == null) {
                    continue;
                }
                for (final double[] b : Extrude.boxesOf(srcSketch, cut0.params())) {
                    cutRaw.add(b);
                }
                cutWireRaw.addAll(Extrude.prismEdges(srcSketch, cut0.params()));
                final SolidMesh.Mesh cm = SolidMesh.build(srcSketch, cut0.params());
                if (cm != null && !cm.isEmpty()) {
                    cutMeshesRaw.add(cm);
                }
            }
            for (final UUID sid : pt.sources()) {
                final Solid src = byId.get(sid);
                if (src == null || src.shape instanceof Shape.Plane
                        || src.shape instanceof Shape.Fillet
                        || src.shape instanceof Shape.Mirror
                        || src.shape instanceof Shape.Pattern
                        || src.shape instanceof Shape.Cut) {
                    continue;
                }
                final Eval e = baseEvalOf.apply(src, src.resolve(sketches));
                if (e == null) {
                    continue;
                }
                for (final double[] off : offsets) {
                    if (Math.abs(off[0]) + Math.abs(off[1]) + Math.abs(off[2]) < 1.0e-9) {
                        continue; // 原位置由源特征自己画 ✓
                    }
                    final List<double[]> cutHere = new ArrayList<>();
                    for (final double[] b : cutRaw) {
                        cutHere.add(translateBox(b, off));
                    }
                    final List<double[]> boxes = new ArrayList<>(e.boxes().size());
                    for (final double[] b : e.boxes()) {
                        boxes.add(translateBox(b, off));
                    }
                    final List<Vector3d[]> edges = new ArrayList<>(e.edges().size());
                    for (final Vector3d[] ed : e.edges()) {
                        edges.add(new Vector3d[] {
                            new Vector3d(ed[0]).add(off[0], off[1], off[2]),
                            new Vector3d(ed[1]).add(off[0], off[1], off[2])});
                    }
                    // ★ 被阵列的切割也要有线框 ✓（与镜像一致）
                    for (final Vector3d[] ce : cutWireRaw) {
                        edges.add(new Vector3d[] {
                            new Vector3d(ce[0]).add(off[0], off[1], off[2]),
                            new Vector3d(ce[1]).add(off[0], off[1], off[2])});
                    }
                    SolidMesh.Mesh mesh = e.mesh() == null ? null : translateMesh(e.mesh(), off);
                    // ★ 圆角也作用到阵列产物上（回归修复）✓
                    // ★ 切割用**真网格**平移后做减法（盒子近似会让切割面贴图错/轮廓成阶梯）
                    if (!cutMeshesRaw.isEmpty()) {
                        final List<SolidMesh.Mesh> here =
                                new ArrayList<>(cutMeshesRaw.size());
                        for (final SolidMesh.Mesh cm : cutMeshesRaw) {
                            here.add(translateMesh(cm, off));
                        }
                        mesh = subtractCutterMeshes(mesh, here);
                    }
                    final List<double[]> finalBoxes = cutHere.isEmpty() ? boxes
                            : subtractBoxes(boxes, cutHere);
                    // ★ 阵列产物上的圆角：棱数据按**本副本的平移量**搬过来再应用 ✓
                    // ★★ 新增：**圆角特征本身也可以被阵列** —— 把被选中圆角特征的棱按
                    //   本副本的平移量搬过来，与 filletEdgeByPattern 合并成一次 applyFillets ✓
                    final java.util.Map<UUID, List<double[]>> srcFilletMap = new java.util.HashMap<>();
                    for (final UUID fsid : pt.sources()) {
                        final Solid fs = byId.get(fsid);
                        if (fs == null || !(fs.shape() instanceof final Shape.Fillet fil)) {
                            continue;
                        }
                        for (final Shape.EdgeRef ref : fil.edges()) {
                            if (!src.id().equals(ref.solidId())) {
                                continue;
                            }
                            final Shape.EdgeRef tr = translateRef(ref, off);
                            expandRef(srcFilletMap, src.id(), tr,
                                    new Vector3d(ref.n1x(), ref.n1y(), ref.n1z()),
                                    new Vector3d(ref.n2x(), ref.n2y(), ref.n2z()),
                                    fil.radius(), tr.isCurve() ? (++curveGroupSeq) : -1,
                                    null, -1);
                        }
                    }
                    final List<double[]> patEdges = new ArrayList<>();
                    final List<double[]> patOwn = filletEdgeByPattern.get(pat.id());
                    if (patOwn != null) {
                        // 本副本的平移量对“在阵列体上点棱”的数据也要生效
                        for (final double[] ed : patOwn) {
                            patEdges.add(translateEdgeTuple(ed, off));
                        }
                    }
                    patEdges.addAll(srcFilletMap.getOrDefault(src.id(), List.of()));
                    if (!patEdges.isEmpty()
                            && mesh != null && !mesh.isEmpty()) {
                        final List<double[]> moved = patEdges;
                        List<Csg.Polygon> pp = ensureOutward(CsgMesh.fromMesh(mesh, null));
                        if (!pp.isEmpty()) {
                            trace("fillet: pattern=" + shortId(pat.id())
                                    + " edges=" + moved.size()
                                    + " probeBoxes=" + finalBoxes.size());
                            pp = applyFillets(pp, moved, pat.index(), "pattern", filletLog,
                                    finalBoxes);
                            if (!pp.isEmpty()) {
                                mesh = CsgMesh.toMesh(pp);
                                trace("fillet: pattern=" + shortId(pat.id())
                                        + " polys=" + pp.size() + " " + meshHealth(pp));
                            }
                        }
                    }
                    if (finalBoxes.isEmpty() || edges.isEmpty()) {
                        continue;
                    }
                    final Solid view = new Solid(pat.id(), pat.body(), src.sketchId(),
                            pat.index(), src.shape(), src.appearance());
                    // ★ 阵列产物也要带**贴图投影法线**：以前传 null ⇒ 渲染侧直接跳过
                    //   "带贴图绘制"（门限要求非 null）⇒ 阵列体永远没有贴图。
                    //   用源草图平面的法线（与源自己的 Placed 同一个值）✓
                    final Sketch srcSk = src.resolve(sketches);
                    final Vector3d viewNormal = srcSk != null && srcSk.plane() != null
                            ? srcSk.plane().normal() : null;
                    out.add(new Placed(view, finalBoxes, edges, mesh, viewNormal));
                }
            }
        }
        // ★ **镜像/阵列取"拉伸+切割之后、圆角之前"的几何** ✓
        //   用户要求：圆角是拉伸的后处理，不该被镜像带走。
        //   之前用的是 evalById（已扣圆角）⇒ 圆角看起来"同步到了镜像"✗

        // ★ 渲染清单诊断（贴图/高亮类问题唯一工具）✓
        final StringBuilder dump = new StringBuilder("placed=[" + out.size());
        for (final Placed pl : out) {
            final SolidMesh.Mesh dm = pl.mesh();
            dump.append(" | ").append(pl.solid().index())
                    .append(":t").append(dm == null ? 0 : dm.indices().length / 3)
                    .append(":tex").append(pl.solid().appearance().hasTexture() ? 1 : 0)
                    .append(":b").append(pl.boxes().size())
                    .append(":e").append(pl.edges().size())
                    .append(":").append(pl.solid().shape().getClass().getSimpleName());
            if (fallbackIds.contains(pl.solid().id())) {
                dump.append(":FB");
            }
        }
        dump.append(']');
        if (filletLog.length() > 0) {
            dump.append(" fillets:").append(filletLog);
        }
        if (!lastFilletDiag.isEmpty()) {
            dump.append(" prep:").append(lastFilletDiag);
        }
        lastCsgDiag = dump.toString();
        return out;
    }

    /**
     * 三角面 → CSG 多边形 ✓（**必须带 plane**：csg.js 的 BSP 直接用 polygon.plane，
     * 传 null 会在 Node.build 里 NPE 把游戏打崩 —— 实测崩过 ✓）
     */
    public static java.util.List<Csg.Polygon> triPolys(final java.util.List<Vector3d[]> tris) {
        final java.util.List<Csg.Polygon> out = new ArrayList<>();
        for (final Vector3d[] t : tris) {
            final Vector3d n = new Vector3d(t[1]).sub(t[0])
                    .cross(new Vector3d(t[2]).sub(t[0]));
            if (n.lengthSquared() < 1.0e-14) {
                continue; // 退化三角形：没有平面，绝不能进 BSP ✓
            }
            n.normalize();
            final Csg.Plane plane = Csg.Plane.fromPoints(
                    new Csg.V3(t[0].x, t[0].y, t[0].z),
                    new Csg.V3(t[1].x, t[1].y, t[1].z),
                    new Csg.V3(t[2].x, t[2].y, t[2].z));
            if (plane == null) {
                continue;
            }
            final java.util.List<Csg.Vertex> vs = new ArrayList<>(3);
            for (final Vector3d v : t) {
                vs.add(new Csg.Vertex(new Csg.V3(v.x, v.y, v.z),
                        new Csg.V3(n.x, n.y, n.z), new Csg.V3(0, 0, 0)));
            }
            out.add(new Csg.Polygon(vs, plane));
        }
        return out;
    }

    /**
     * 三角面 → CSG 多边形（**带 UV**）✓
     *
     * <p>圆角面片如果没有 UV（全 0），整面只会采样一个纹素 ⇒ 看着就是**全黑/没有贴图**（实测）。
     * 这里用"沿棱方向 × 沿弧方向"的平面投影当 UV（方块量纲），贴图会顺着棱和圆弧平铺 ✓
     */
    public static java.util.List<Csg.Polygon> triPolys(final java.util.List<Vector3d[]> tris,
                                                       final Vector3d axisU,
                                                       final Vector3d axisV) {
        final java.util.List<Csg.Polygon> out = new ArrayList<>();
        final Vector3d u = new Vector3d(axisU).normalize();
        final Vector3d v = new Vector3d(axisV).normalize();
        // ★ UV 必须**相对**棱起点：直接用绝对坐标会得到 2e7 量级的 UV ⇒
        //   贴图被拉到极高频率，看起来就是"每一小格单独贴图/全是碎块"（用户实测）✗
        final Vector3d origin = tris.isEmpty() || tris.get(0).length == 0
                ? new Vector3d()
                : new Vector3d(tris.get(0)[0]);
        for (final Vector3d[] t : tris) {
            final Vector3d n = new Vector3d(t[1]).sub(t[0])
                    .cross(new Vector3d(t[2]).sub(t[0]));
            if (n.lengthSquared() < 1.0e-14) {
                continue;
            }
            n.normalize();
            final Csg.Plane plane = Csg.Plane.fromPoints(
                    new Csg.V3(t[0].x, t[0].y, t[0].z),
                    new Csg.V3(t[1].x, t[1].y, t[1].z),
                    new Csg.V3(t[2].x, t[2].y, t[2].z));
            if (plane == null) {
                continue;
            }
            final java.util.List<Csg.Vertex> vs = new ArrayList<>(3);
            for (final Vector3d q : t) {
                final double uu = new Vector3d(q).sub(origin).dot(u);
                final double vv = new Vector3d(q).sub(origin).dot(v);
                vs.add(new Csg.Vertex(new Csg.V3(q.x, q.y, q.z),
                        new Csg.V3(n.x, n.y, n.z), new Csg.V3(uu, vv, 0)));
            }
            out.add(new Csg.Polygon(vs, plane));
        }
        return out;
    }


    /**
     * 把圆角切割体也作用到**镜像/阵列产出的网格**上 ✓
     *
     * <p>为什么必须单独做 ✗✓：镜像/阵列的材料是在 {@code placeAll} 末尾**生成**的 Placed，
     * 不经过主循环里的 CSG 减法 ⇒ 之前"镜像/阵列出来的模型上无法创建圆角"（用户实测）。
     */


    /** 局部原点下做**交集**（与 subtractLocal 同一套数值处理）✓ */
    private static List<Csg.Polygon> intersectLocal(final List<Csg.Polygon> base,
                                                    final List<Csg.Polygon> other) {
        if (base.isEmpty() || other.isEmpty()) {
            return List.of();
        }
        final Csg.V3 first = base.get(0).vertices.get(0).pos();
        final List<Csg.Polygon> a = shiftedPolys(base, -first.x(), -first.y(), -first.z());
        final List<Csg.Polygon> b = shiftedPolys(other, -first.x(), -first.y(), -first.z());
        final List<Csg.Polygon> res = Csg.intersect(a, b);
        return shiftedPolys(res, first.x(), first.y(), first.z());
    }

    /** 局部原点下做**并集**（凹棱补料）✓ */
    private static List<Csg.Polygon> unionLocal(final List<Csg.Polygon> base,
                                                final List<Csg.Polygon> other) {
        if (base.isEmpty()) {
            return other;
        }
        if (other.isEmpty()) {
            return base;
        }
        final Csg.V3 first = base.get(0).vertices.get(0).pos();
        final List<Csg.Polygon> a = shiftedPolys(base, -first.x(), -first.y(), -first.z());
        final List<Csg.Polygon> b = shiftedPolys(other, -first.x(), -first.y(), -first.z());
        final List<Csg.Polygon> res = Csg.union(a, b);
        return shiftedPolys(res, first.x(), first.y(), first.z());
    }

    /**
     * 把一批圆角棱**应用到材料上** ✓（凸棱减料 / 凹棱补料，四象限探针定象限）
     *
     * <h2>为什么按"项"（item）而不是按"段"处理</h2>
     * 曲线棱在文件里是**一个** EdgeRef + 折线，求值时展开成很多小段。逐段建棱柱会在
     * 弧的关节处留下**楔形空隙**（用户实测"每个小圆角之间有间隙"）⇒ 这里把同一
     * {@code curveGroup} 的连续段合成**一个扫掠体**（{@link FilletCutter#sweep}）：
     * <ul>
     *   <li>整条弧只做**一次**分类（中间段探针）⇒ 不会出现"个别碎棱没倒"</li>
     *   <li>只做**一次**布尔 ⇒ 几十段也不卡</li>
     *   <li>关节用角平分面连接 ⇒ 表面连续</li>
     * </ul>
     *
     * <h2>凸/凹判定</h2>
     * 棱中点沿四条角平分线、距离 {@code 0.5r} 各一个探针：1 个实心=凸（减料）、
     * 3 个实心=凹（补料）；2/0/4 说明不是棱或被薄壁骗了，从**相邻**项继承或跳过。
     */
    private static List<Csg.Polygon> applyFillets(final List<Csg.Polygon> acc,
                                                  final List<double[]> edges,
                                                  final int index, final String source,
                                                  final StringBuilder log,
                                                  final List<double[]> materialBoxes) {
        List<Csg.Polygon> out = acc;
        final int en = edges == null ? 0 : edges.size();
        if (en == 0) {
            return out;
        }
        // ★ 探针材料：**用碰撞盒子**（体素化后的真实材料，与预览同一份数据）✓
        final boolean useBoxes = materialBoxes != null && !materialBoxes.isEmpty();
        final List<Csg.Polygon> base = out; // lambda 只能捕获 effectively-final
        final java.util.function.Predicate<Vector3d> probe = useBoxes
                ? pt -> insideBoxes(materialBoxes, pt)
                : pt -> containsPoint(base, pt);

        // ① 分组：同一 curveGroup 的连续段 = 一条曲线（合成一个扫掠体）
        final List<int[]> items = new ArrayList<>();
        for (int i = 0; i < en; ) {
            final int g = curveGroupOf(edges.get(i));
            int j = i + 1;
            if (g >= 0) {
                while (j < en && curveGroupOf(edges.get(j)) == g) {
                    j++;
                }
            }
            items.add(new int[] {i, j});
            i = j;
        }
        // ② 每项分类（曲线用中间那段探针）+ 理论体积
        final int ni = items.size();
        trace("fillet: apply idx=" + index + " edges=" + en + " items=" + ni
                + " probeBoxes=" + (materialBoxes == null ? 0 : materialBoxes.size())
                + " useBoxes=" + useBoxes);
        final int[] itemQuads = new int[ni];
        final double[][] itemQd = new double[ni][];
        final double[] itemTheory = new double[ni];
        final SolidMesh.Mesh[] baseMesh = new SolidMesh.Mesh[1];
        for (int it = 0; it < ni; it++) {
            final int[] rg = items.get(it);
            final int mid = (rg[0] + rg[1] - 1) / 2;
            double theory = 0.0;
            for (int i = rg[0]; i < rg[1]; i++) {
                final double[] e = edges.get(i);
                final double r = e[12];
                final double dx = e[3] - e[0];
                final double dy = e[4] - e[1];
                final double dz = e[5] - e[2];
                final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                theory += (r * r - Math.PI * r * r / 4.0) * len;
            }
            itemTheory[it] = theory;
            final double[] e = edges.get(mid);
            // ★ 网格已经给出**精确凸凹**（直线棱）⇒ 象限直接解析求出，不用探针 ✓
            //   同一条链里只要有一段带"已知"标记就整条沿用 ✓
            int knownConvex = -1;
            for (int i = rg[0]; i < rg[1] && knownConvex < 0; i++) {
                final double[] t = edges.get(i);
                if (t.length > 14 && ((int) t[14]) >= 0) {
                    knownConvex = (int) t[14];
                }
            }
            // ★ 直线棱**没有网格依据时**：先用**当前材料多边形**再判一次（切割产生的棱
            //   不在源网格里）⇒ 仍判不出才跳过 ✓ 绝不靠探针猜（判反会把补料糊到外面）
            if (knownConvex < 0 && rg[1] - rg[0] <= 1) {
                if (baseMesh[0] == null) {
                    baseMesh[0] = CsgMesh.toMesh(base);
                }
                final double[] ee = edges.get(rg[0]);
                final double[] info2 = FilletCutter.edgeInfo(baseMesh[0],
                        new Vector3d(ee[0], ee[1], ee[2]),
                        new Vector3d(ee[3], ee[4], ee[5]));
                if (info2 != null) {
                    knownConvex = info2[6] > 0.5 ? 1 : 0;
                    ee[6] = info2[0];
                    ee[7] = info2[1];
                    ee[8] = info2[2];
                    ee[9] = info2[3];
                    ee[10] = info2[4];
                    ee[11] = info2[5];
                    ee[14] = knownConvex;
                    trace("fillet:  edgeInfo(material) convex=" + knownConvex
                            + " f1=(" + fmt(info2[0]) + "," + fmt(info2[1]) + ","
                            + fmt(info2[2]) + ")");
                } else {
                    itemQuads[it] = -3;
                    log.append(" [f").append(index).append(":no-edgeinfo]");
                    continue;
                }
            }
            if (knownConvex >= 0) {
                final double[] qdz = new double[4];
                qdz[0] = knownConvex == 1 ? -1.0 : 1.0;
                qdz[1] = qdz[0];
                qdz[2] = knownConvex == 1 ? 0.0 : 1.0;
                qdz[3] = 0.0;
                itemQd[it] = qdz;
                itemQuads[it] = knownConvex == 1 ? 1 : 3;
                trace("fillet:  known convex=" + knownConvex + " q=(" + (int) qdz[0] + ","
                        + (int) qdz[1] + ")");
                continue;
            }
            final double[] qd = new double[4];
            itemQd[it] = qd;
            itemQuads[it] = filletQuadrant(probe,
                    new Vector3d(e[0], e[1], e[2]), new Vector3d(e[3], e[4], e[5]),
                    new Vector3d(e[6], e[7], e[8]), new Vector3d(e[9], e[10], e[11]),
                    e[12], qd);
            // ★ 同一条棱/弧的**首段**必须与中间段同结论（凸/凹不能半路翻转）
            //   不一致 ⇒ 探针不可靠 ⇒ 整项跳过（否则会把补料糊到外面 ⇒ "模型崩坏"）
            final double[] ef = edges.get(rg[0]);
            final double[] qdf = new double[4];
            final int qf = filletQuadrant(probe,
                    new Vector3d(ef[0], ef[1], ef[2]), new Vector3d(ef[3], ef[4], ef[5]),
                    new Vector3d(ef[6], ef[7], ef[8]), new Vector3d(ef[9], ef[10], ef[11]),
                    ef[12], qdf);
            if ((itemQuads[it] == 1 || itemQuads[it] == 3)
                    && (qf == 1 || qf == 3) && qf != itemQuads[it]) {
                itemQuads[it] = -2; // 明确不可判定：不允许继承
                log.append(" [f").append(index).append(":disagree]");
            }
        }
        // ③ 曲线链里个别项探针落在盒子边界（0/4、2/4）⇒ 从**相邻**项继承象限 ✓
        for (int it = 0; it < ni; it++) {
            if (itemQuads[it] == 1 || itemQuads[it] == 3) {
                continue;
            }
            if (itemQuads[it] == -2 || itemQuads[it] == -3) {
                continue; // 明确不可判定/没有网格依据 ⇒ 不继承、不应用
            }
            boolean inherited = false;
            for (int jt = 0; jt < ni; jt++) {
                if (jt == it || (itemQuads[jt] != 1 && itemQuads[jt] != 3)
                        || !itemsTouch(edges, items.get(it), items.get(jt))) {
                    continue;
                }
                itemQd[it] = itemQd[jt];
                itemQuads[it] = itemQuads[jt];
                inherited = true;
                break;
            }
            log.append(" [f").append(index).append(inherited ? ":inh]" : ":quads "
                    + itemQuads[it] + "/4]");
        }
        // ③b **凸/凹过渡**：一个顶点上同时有"切料"和"补料"两种圆角时，两个圆角面
        //     在顶点处方向相反 ⇒ 直接摞起来会留下一个台阶。
        //     做法：两边都把半径**渐变收小到 ~0**（fillet runout）⇒ 在顶点处切向相接 ✓
        final boolean[] taperStart = new boolean[ni];
        final boolean[] taperEnd = new boolean[ni];
        for (int a = 0; a < ni; a++) {
            if (itemQuads[a] != 1 && itemQuads[a] != 3) {
                continue;
            }
            final int[] ra = items.get(a);
            final double[] aStart = edges.get(ra[0]);
            final double[] aEnd = edges.get(ra[1] - 1);
            for (int b = 0; b < ni; b++) {
                if (a == b || (itemQuads[b] != 1 && itemQuads[b] != 3)
                        || ((itemQuads[b] == 3) == (itemQuads[a] == 3))) {
                    continue;
                }
                final int[] rb = items.get(b);
                final double[] bStart = edges.get(rb[0]);
                final double[] bEnd = edges.get(rb[1] - 1);
                if (samePoint(aStart, 0, bStart, 0) || samePoint(aStart, 0, bEnd, 3)) {
                    taperStart[a] = true;
                }
                if (samePoint(aEnd, 3, bStart, 0) || samePoint(aEnd, 3, bEnd, 3)) {
                    taperEnd[a] = true;
                }
            }
        }
        // ④ 造楔块 / 扫掠体（凸=切料，凹=补料）
        final List<List<Csg.Polygon>> cutWedges = new ArrayList<>();
        final List<List<Csg.Polygon>> fillWedges = new ArrayList<>();
        final List<double[]> cutEdges = new ArrayList<>();
        // ★ 每块操作数的**理论体积 + 起点**（逐个应用时要量"实际去除量"，超了立刻退回）
        final List<Double> cutTh = new ArrayList<>();
        final List<Double> fillTh = new ArrayList<>();
        final List<double[]> cutDbg = new ArrayList<>();
        final List<double[]> fillDbg = new ArrayList<>();
        for (int it = 0; it < ni; it++) {
            final int quads = itemQuads[it];
            if (quads != 1 && quads != 3) {
                continue;
            }
            final int[] rg = items.get(it);
            final double[] qd = itemQd[it];
            final boolean concave = quads == 3;
            final boolean curve = rg[1] - rg[0] > 1;
            final double[] e0 = edges.get(rg[0]);
            final double r = e0[12];
            final Vector3d cap = new Vector3d(e0[6], e0[7], e0[8]).normalize();
            // ★★ 凸/凹共顶点：**不再 setback（让位）** —— 用户实测"让位"的后果是
            //   相交处既没有圆角也没有过渡（两条圆角各退 0.25，中间空一段原棱）。
            //
            //   几何事实（这次先算清再改）：
            //   · 凸棱楔块的端盖平面 = **凹棱的第二张面**（共享顶点处两面正交）
            //   · 凹棱补料的端盖平面 = **凸棱的第二张面**
            //   ⇒ 两条圆角各自沿自己的棱跑到公共顶点时，端盖恰好与那张面**共面**，
            //     圆角平顺地"跑出"到那张面上（SolidWorks 的 fillet runout），
            //     没有台阶、没有缝、也不需要角球（球面角块只适用于凸-凸顶点）。
            //   ⇒ 这里保留 cc 只为了两件事：
            //     a) 走扫掠路径（端盖与棱端齐平；prism 会沿棱外延 ext，会凸出到面外）
            //     b) 该顶点**不做角球**（混合凸凹用球面角块是错的）
            final boolean cc = taperStart[it] || taperEnd[it];
            final List<Vector3d> pts0 = new ArrayList<>(rg[1] - rg[0] + 1);
            pts0.add(new Vector3d(e0[0], e0[1], e0[2]));
            for (int i = rg[0]; i < rg[1]; i++) {
                final double[] ei = edges.get(i);
                pts0.add(new Vector3d(ei[3], ei[4], ei[5]));
            }
            // ★ runout 的链 + 半径表：**在离顶点 tl 处插分段点**，让锥形收尾只占 tl 那一小段
            //   （不插点的话直线棱只有 2 个点 ⇒ 整条棱都变成锥体，用户实测"整个圆角都是斜的"）
            final FilletCutter.Runout runout = FilletCutter.runoutProfile(pts0, r,
                    taperStart[it], taperEnd[it]);
            final List<Vector3d> pts = runout.points;
            final double[] radii = runout.radii;
            if (cc) {
                double rMin = r;
                for (final double rr : radii) {
                    rMin = Math.min(rMin, rr);
                }
                trace("fillet:  cc-runout idx=" + index + " pts=" + pts.size()
                        + " len=" + fmt(polylineLength(pts0))
                        + " tl=" + fmt(runout.taperLen)
                        + " start=" + taperStart[it] + " end=" + taperEnd[it]
                        + " rMin=" + fmt(rMin));
            }
            final double lenPts = polylineLength(pts);
            final double theory = FilletCutter.sweptVolume(pts, radii);
            // ★ 折痕指标：2·r·sin(θ/2)/最短相邻段 ⇒ >1 说明"局部曲率半径 < r"，
            //   扫掠体必然自交（SolidWorks 这时会判"半径过大"）。只记日志，最后一道
            //   体积门会把它拦下来 ✓
            double fold = 0.0;
            for (int i2 = 1; i2 + 1 < pts.size(); i2++) {
                final Vector3d d0 = new Vector3d(pts.get(i2)).sub(pts.get(i2 - 1));
                final Vector3d d1 = new Vector3d(pts.get(i2 + 1)).sub(pts.get(i2));
                final double l0 = d0.length();
                final double l1 = d1.length();
                if (l0 < 1.0e-9 || l1 < 1.0e-9) {
                    continue;
                }
                final double c2 = Math.max(-1.0, Math.min(1.0, d0.dot(d1) / (l0 * l1)));
                fold = Math.max(fold, 2.0 * r * Math.sqrt(Math.max(0.0, (1.0 - c2) * 0.5))
                        / Math.min(l0, l1));
            }
            trace("fillet:  item=" + it + " segs=" + (rg[1] - rg[0]) + " pts=" + pts.size()
                    + " curve=" + curve + " cc=" + cc + " len=" + fmt(lenPts)
                    + " fold=" + fmt(fold)
                    + " q=(" + (int) Math.round(qd[0]) + "," + (int) Math.round(qd[1]) + ")"
                    + " a=(" + fmt(e0[0]) + "," + fmt(e0[1]) + "," + fmt(e0[2]) + ")"
                    + " b=(" + fmt(e0[3]) + "," + fmt(e0[4]) + "," + fmt(e0[5]) + ")");
            final List<Csg.Polygon> wedge;
            if (curve || cc) {
                final double[] eMid = edges.get((rg[0] + rg[1] - 1) / 2);
                // ★ q2 是相对**存档里的 n2**，而扫掠的第二轴是 (tan×cap) ⇒ 两者可能反向
                //   （n2 的顺序来自网格三角形的遍历顺序，不是由我们定的）。
                //   不归一就会把楔块建到"另一个象限"⇒ 切掉相邻那一行材料 ✗
                final int q1i = (int) Math.round(qd[0]);
                final int q2i0 = (int) Math.round(qd[1]);
                final int q2i = FilletCutter.sweepQ2(cap,
                        new Vector3d(e0[9], e0[10], e0[11]),
                        new Vector3d(e0[3] - e0[0], e0[4] - e0[1], e0[5] - e0[2]), q2i0);
                final List<Vector3d[]> tris = FilletCutter.sweep(pts, cap, radii, q1i, q2i);
                if (tris == null || tris.isEmpty()) {
                    log.append(" [f").append(index).append(":sweep-empty]");
                    continue;
                }
                final Vector3d axisU = new Vector3d(eMid[3] - eMid[0],
                        eMid[4] - eMid[1], eMid[5] - eMid[2]).normalize();
                wedge = ensureOutward(triPolys(tris, axisU, cap));
            } else {
                final double[] e1 = edges.get(rg[0]);
                final Vector3d a = new Vector3d(e1[0], e1[1], e1[2]);
                final Vector3d b = new Vector3d(e1[3], e1[4], e1[5]);
                final Vector3d dir = new Vector3d(b).sub(a).normalize();
                final Vector3d m1 = new Vector3d(e1[6], e1[7], e1[8]).mul(-qd[0]);
                final Vector3d m2 = new Vector3d(e1[9], e1[10], e1[11]).mul(-qd[1]);
                wedge = wedgePolys(a, b, m1, m2, r, dir, cap);
            }
            if (wedge.isEmpty()) {
                log.append(" [f").append(index).append(":empty-prism]");
                continue;
            }
            trace("fillet:  quads=" + quads + "/4 pattern=" + (int) qd[3]
                    + " -> " + (concave ? "concave(fill)" : "convex(cut)")
                    + " idx=" + index + (curve ? " sweepSegs=" + (rg[1] - rg[0]) : "")
                    + (cc ? " cc" : ""));
            // ★ 楔块必须**封闭**：扫掠/棱柱一旦出现裂口/自交，CSG 会把它当成"内部"
            //   ⇒ 减出来就是"把整行都切掉"、贴图坏（实测）。不封闭 ⇒ 宁可不倒 ✓
            if (!isClosed(wedge)) {
                log.append(" [f").append(index).append(":not-closed]");
                continue;
            }
            // ★ 闭合还不够：**绕序必须一致朝外**。实测事故：端盖绕序写反 ⇒ 拓扑闭合
            //   但有向体积只剩 1/3 ⇒ CSG 把整行吃掉（`isClosed` 拦不住，见 isOriented）
            if (!isOriented(wedge)) {
                log.append(" [f").append(index).append(":wind]");
                continue;
            }
            final double wedgeVol = Math.abs(polysVolume(wedge));
            final double volRatio = wedgeVol / Math.max(1.0e-12, theory);
            // ★ 体积门要紧：实测正确楔块在 1.02~1.12×理论（8 段圆弧内接的差），
            //   一旦明显偏离就是自交/退化，绝不能让它进 CSG ✓
            if (volRatio > 1.8 || volRatio < 0.7) {
                log.append(" [f").append(index).append(":size ")
                        .append(String.format(java.util.Locale.ROOT, "%.4f", wedgeVol))
                        .append('/').append(String.format(java.util.Locale.ROOT, "%.4f", theory))
                        .append(" r=").append(fmt(volRatio)).append(']');
                continue;
            }
            trace("fillet:  wedge it=" + it + " vol=" + fmt(wedgeVol) + " th=" + fmt(theory)
                    + " ratio=" + fmt(volRatio) + " in=" + fmt(
                            Math.abs(polysVolume(intersectLocal(out, wedge)))));
            final List<Csg.Polygon> material = out;
            final double inVol = Math.abs(polysVolume(intersectLocal(material, wedge)));
            final double outVol = Math.max(0.0, wedgeVol - inVol);
            if (concave ? (inVol > 0.95 * wedgeVol) : (inVol < 0.05 * wedgeVol)) {
                log.append(" [f").append(index).append(":side ")
                        .append(String.format(java.util.Locale.ROOT, "%.4f/%.4f",
                                inVol, wedgeVol)).append(']');
                continue;
            }
            final boolean ok = concave ? (inVol < 0.35 * wedgeVol)
                    : (inVol > 0.65 * wedgeVol);
            log.append(" [f").append(index).append(concave ? ":fill " : ":cut ")
                    .append(String.format(java.util.Locale.ROOT, "%.4f/%.4f/%.4f",
                            inVol, outVol, theory))
                    .append(curve ? " c" : "").append(ok ? " ok]" : " ?!]");
            final double[] dbg = new double[] {e0[0], e0[1], e0[2], e0[3], e0[4], e0[5]};
            if (concave) {
                fillWedges.add(wedge);
                fillTh.add(theory);
                fillDbg.add(dbg);
            } else {
                cutWedges.add(wedge);
                cutTh.add(theory);
                cutDbg.add(dbg);
                // 顶点混合的候选：曲线只取**两端**的段（内部关节是平滑的，不需要角球）
                // ★ 与相反分类共顶点的那一端**不做角球**：凸/凹混合顶点不能套球面角块，
                //   那里靠"两条圆角各自跑到顶点、端盖与对面共面"自然平顺过渡 ✓
                final int[] ends = curve
                        ? new int[] {rg[0], rg[1] - 1} : new int[] {rg[0]};
                for (final int ei : ends) {
                    final boolean isStart = ei == rg[0];
                    if ((isStart && taperStart[it]) || (!isStart && taperEnd[it])) {
                        continue;
                    }
                    final double[] e = edges.get(ei);
                    cutEdges.add(new double[] {e[0], e[1], e[2], e[3], e[4], e[5],
                        e[6], e[7], e[8], e[9], e[10], e[11], e[12]});
                }
            }
        }
        // ⑤ 顶点混合 + **逐个**应用（永不先把多块并成一个操作数）
        //
        //   ✗ 旧做法 `mergeWedges`（CSG 并集再减）：多条棱共顶点时并集会产生共面/相切
        //     的碎片，BSP 判内外出错 ⇒ 减出来把整行吃掉（用户实测"直线棱在凸凹相交处
        //     切掉一整行"）。
        //   ✓ 现在：一块一块减，并对**每一次**操作量"材料体积实际掉了多少"——
        //     超出这块圆角自己的体积（×2.5）就立刻**退回**这一块，只留日志。
        final List<Double> cornerTh = new ArrayList<>();
        final List<List<Csg.Polygon>> corners = cornerBlends(cutEdges, cornerTh, log, index);
        if (!cutWedges.isEmpty()) {
            out = applyOperands(out, cutWedges, cutTh, cutDbg, false, index, log);
        }
        if (!corners.isEmpty()) {
            out = applyOperands(out, corners, cornerTh, null, false, index, log);
        }
        if (!fillWedges.isEmpty()) {
            out = applyOperands(out, fillWedges, fillTh, fillDbg, true, index, log);
        }
        return out;
    }

    /**
     * **逐个**把一堆操作数作用到材料上，并量**真实体积变化** ✓
     *
     * <p>这是最后一道保险：一个"拓扑闭合但自交/绕序坏"的楔块，`polysVolume` 可能只偏一点，
     * 但真正做布尔时会把整块材料判成"外部"⇒ 一次就去掉几十倍体积。这里用
     * {@code |V(after)| − |V(before)|} 直接量 ⇒ 超预算就**退回**这一块（宁可少倒一条棱，
     * 也不能把模型吃掉）。
     *
     * @param union true = 补料（凹棱），false = 切料（凸棱）
     */
    private static List<Csg.Polygon> applyOperands(final List<Csg.Polygon> mat,
                                                   final List<List<Csg.Polygon>> ops,
                                                   final List<Double> theory,
                                                   final List<double[]> dbg,
                                                   final boolean union,
                                                   final int index,
                                                   final StringBuilder log) {
        List<Csg.Polygon> out = mat;
        double totalTh = 0.0;
        double totalDelta = 0.0;
        int reverted = 0;
        for (int i = 0; i < ops.size(); i++) {
            final double v0 = Math.abs(polysVolume(out));
            final List<Csg.Polygon> next = union
                    ? unionLocal(out, ops.get(i)) : subtractLocal(out, ops.get(i));
            final double v1 = Math.abs(polysVolume(next));
            final double delta = union ? (v1 - v0) : (v0 - v1);
            final double th = theory != null && i < theory.size() && theory.get(i) != null
                    ? theory.get(i) : 0.0;
            totalTh += th;
            // 预算：这块圆角最多挪走它自己的体积（实测 1.02~1.12×）。给 2.5 倍余量
            // （多棱重叠时"实际去除"只会更小，不会更大），再加 0.02 的绝对兜底
            // 抵消多边形体积积分的噪声。
            final double budget = Math.max(th * 2.5, th + 0.02);
            if (th > 0.0 && delta > budget) {
                reverted++;
                log.append(" [f").append(index).append(union ? ":fill-over " : ":overcut ")
                        .append(String.format(java.util.Locale.ROOT, "%.4f", delta))
                        .append('/').append(String.format(java.util.Locale.ROOT, "%.4f", th));
                if (dbg != null && i < dbg.size() && dbg.get(i) != null) {
                    final double[] d = dbg.get(i);
                    log.append(" a=(").append(fmt(d[0])).append(',').append(fmt(d[1]))
                            .append(',').append(fmt(d[2])).append(')');
                }
                log.append(']');
                continue; // ★ 不应用这一块
            }
            out = next;
            totalDelta += delta;
        }
        log.append(union ? " [fill n=" : " [cut n=").append(ops.size());
        if (reverted > 0) {
            log.append(" rev=").append(reverted);
        }
        log.append(']');
        // 总账（诊断用）：实际总变化 vs 理论总和。差值大 ⇒ 有操作数在乱切/乱补
        if (totalTh > 0.0) {
            trace("fillet:  " + (union ? "fill" : "cut") + " idx=" + index
                    + " n=" + ops.size() + " rev=" + reverted
                    + " delta=" + fmt(totalDelta) + " th=" + fmt(totalTh));
        }
        return out;
    }

    /**
     * 网格健康度（诊断）✓ —— CSG 之后最值钱的一条：
     * {@code nan} = 顶点坐标非有限；{@code deg} = 没有平面/退化的多边形。
     * 这两个数一旦非 0，渲染就会出"贴图消失/破面"。
     */
    public static String meshHealth(final List<Csg.Polygon> polys) {
        int nan = 0;
        int deg = 0;
        int zero = 0;
        if (polys != null) {
            for (final Csg.Polygon p : polys) {
                boolean bad = false;
                for (final Csg.Vertex v : p.vertices) {
                    final Csg.V3 q = v.pos();
                    if (!Double.isFinite(q.x()) || !Double.isFinite(q.y())
                            || !Double.isFinite(q.z())) {
                        bad = true;
                        break;
                    }
                }
                if (bad) {
                    nan++;
                    continue;
                }
                if (p.plane == null) {
                    deg++;
                    continue;
                }
                final Csg.V3 nn = p.plane.normal;
                final double nx = nn.x();
                final double ny = nn.y();
                final double nz = nn.z();
                final double len2 = nx * nx + ny * ny + nz * nz;
                if (!Double.isFinite(nx) || !Double.isFinite(ny) || !Double.isFinite(nz)
                        || Math.abs(len2 - 1.0) > 0.5) {
                    deg++;
                }
                if (p.vertices.size() < 3) {
                    zero++;
                }
            }
        }
        return "nan=" + nan + " deg=" + deg + " lt3=" + zero;
    }

    /** 一组多边形是否**封闭**（每条无向边恰好被两个多边形共用）✓ —— 圆角楔块的入场券 */
    public static boolean isClosed(final List<Csg.Polygon> polys) {
        if (polys == null || polys.size() < 4) {
            return false;
        }
        final java.util.HashMap<String, Integer> ec = new java.util.HashMap<>();
        for (final Csg.Polygon p : polys) {
            final List<Csg.Vertex> vs = p.vertices;
            if (vs.size() < 3) {
                return false;
            }
            for (int i = 0; i < vs.size(); i++) {
                final String a = vkey(vs.get(i).pos());
                final String b = vkey(vs.get((i + 1) % vs.size()).pos());
                final String k = a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
                ec.merge(k, 1, Integer::sum);
            }
        }
        for (final int c : ec.values()) {
            if (c != 2) {
                return false;
            }
        }
        return true;
    }

    private static String vkey(final Csg.V3 v) {
        return Math.round(v.x() * 1000.0) + "," + Math.round(v.y() * 1000.0) + ","
                + Math.round(v.z() * 1000.0);
    }

    /**
     * 一组多边形是否**绕序一致且朝外**（每条有向边恰好出现一次、反向边也恰好一次）✓
     *
     * <p>为什么 `isClosed` 不够 ✗✓：实测事故里楔块**拓扑闭合**（每条无向边正好两个面），
     * 但**端盖绕序写反** ⇒ 有向体积只剩 1/3，CSG 把它当"里外一半颠倒"的壳
     * ⇒ 差集把整行材料吃掉、贴图崩坏。这个函数专门拦这种"闭合但朝向乱"的体。
     */
    public static boolean isOriented(final List<Csg.Polygon> polys) {
        if (polys == null || polys.size() < 4) {
            return false;
        }
        final java.util.HashMap<String, Integer> dir = new java.util.HashMap<>();
        for (final Csg.Polygon p : polys) {
            final List<Csg.Vertex> vs = p.vertices;
            if (vs.size() < 3) {
                return false;
            }
            for (int i = 0; i < vs.size(); i++) {
                final String a = vkey(vs.get(i).pos());
                final String b = vkey(vs.get((i + 1) % vs.size()).pos());
                if (a.equals(b)) {
                    return false; // 退化边
                }
                dir.merge(a + "|" + b, 1, Integer::sum);
            }
        }
        for (final java.util.Map.Entry<String, Integer> e : dir.entrySet()) {
            if (e.getValue() != 1) {
                return false; // 同向重复 ⇒ 面重叠/绕序冲突
            }
            final String k = e.getKey();
            final int sep = k.indexOf('|');
            final String rev = k.substring(sep + 1) + '|' + k.substring(0, sep);
            if (!Integer.valueOf(1).equals(dir.get(rev))) {
                return false;
            }
        }
        return true;
    }

    /** 折线总长 ✓（setback/体积估算用） */
    private static double polylineLength(final List<Vector3d> pts) {
        double total = 0.0;
        if (pts == null) {
            return total;
        }
        for (int i = 1; i < pts.size(); i++) {
            total += pts.get(i).distance(pts.get(i - 1));
        }
        return total;
    }

    /** 一组边元组的总长度 ✓（过渡半径的体积估算用） */
    private static double curveLength(final List<double[]> edges, final int[] rg) {
        double total = 0.0;
        for (int i = rg[0]; i < rg[1]; i++) {
            final double[] e = edges.get(i);
            final double dx = e[3] - e[0];
            final double dy = e[4] - e[1];
            final double dz = e[5] - e[2];
            total += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return total;
    }

    /**
     * 把一条 EdgeRef（直线 / 曲线折线）展开成求值用的**逐段元组** ✓
     *
     * <p>每段都重新算"第二个法线"= dir × 盖面法线 ⇒ 弧面逐段都正确；
     * 同一条曲线的所有段共享 {@code curveGroup}，求值时合成一个扫掠体。
     *
     * @param polysOut 非 null 时同时把逐段棱柱追加进去（镜像/阵列的旧路径用）
     */
    private static void expandRef(final java.util.Map<UUID, List<double[]>> map, final UUID key,
                                  final Shape.EdgeRef ref, final Vector3d n1v,
                                  final Vector3d n2v, final double rr, final int curveGroup,
                                  final java.util.List<Csg.Polygon> polysOut,
                                  final int convexKnown) {
        if (map == null || key == null || ref == null) {
            return;
        }
        final java.util.List<double[]> segs = ref.isCurve()
                ? ref.curveSegs()
                : java.util.List.of(new double[] {ref.ax(), ref.ay(), ref.az(),
                    ref.bx(), ref.by(), ref.bz()});
        final java.util.List<double[]> out = map.computeIfAbsent(key, k -> new ArrayList<>());
        for (final double[] sg : segs) {
            final Vector3d sa = new Vector3d(sg[0], sg[1], sg[2]);
            final Vector3d sb = new Vector3d(sg[3], sg[4], sg[5]);
            if (sa.distance(sb) < 1.0e-6) {
                continue;
            }
            final Vector3d sd = new Vector3d(sb).sub(sa).normalize();
            final Vector3d segN1 = new Vector3d(n1v).normalize();
            Vector3d segN2 = new Vector3d(sd).cross(segN1);
            if (!ref.isCurve() && n2v != null && n2v.lengthSquared() > 1.0e-9) {
                // ★ 直线棱：用**存下来的第二个真实面法线**（支持非 90° 棱）✓
                //   dir × n1 只有两个面互相垂直时才等于第二个面法线；
                //   非 90° 棱会因此把楔块转一个角度（实测"切割体被向外旋转 45°"）✗
                final Vector3d t2 = new Vector3d(n2v);
                t2.fma(-t2.dot(sd), sd);
                if (t2.lengthSquared() > 1.0e-9) {
                    segN2 = t2.normalize();
                } else if (segN2.lengthSquared() < 1.0e-9) {
                    continue;
                } else {
                    segN2.normalize();
                }
            } else if (segN2.lengthSquared() < 1.0e-9) {
                if (n2v == null || n2v.lengthSquared() < 1.0e-9) {
                    continue;
                }
                segN2.set(n2v);
            } else {
                segN2.normalize();
            }
            out.add(new double[] {sa.x, sa.y, sa.z, sb.x, sb.y, sb.z,
                segN1.x, segN1.y, segN1.z, segN2.x, segN2.y, segN2.z, rr, curveGroup,
                convexKnown});
            if (polysOut != null) {
                final List<Vector3d[]> tris =
                        FilletCutter.prism(sa, sb, segN1, segN2, rr);
                if (tris != null && !tris.isEmpty()) {
                    polysOut.addAll(triPolys(tris, sd, segN1));
                }
            }
        }
    }

    /** 平移一条圆角边元组（阵列副本用）✓ —— 15 项：坐标6+法线6+r+curveGroup+凸凹 */
    private static double[] translateEdgeTuple(final double[] e, final double[] off) {
        return new double[] {e[0] + off[0], e[1] + off[1], e[2] + off[2],
            e[3] + off[0], e[4] + off[1], e[5] + off[2],
            e[6], e[7], e[8], e[9], e[10], e[11], e[12],
            e.length > 13 ? e[13] : -1, e.length > 14 ? e[14] : -1};
    }

    /**
     * 把一组边元组里**首尾相接且转角平滑**的相邻段并到同一个 curveGroup ✓
     *
     * <p>兼容旧存档：那时曲线棱是"一段一个引用"（48 段 = 48 个独立的小棱柱），
     * 逐段切既慢又容易在合并一堆互不相交的切割体时把网格弄坏（贴图消失/破面）。
     * 合并后它们变成**一个扫掠体**，和新建的曲线棱完全一致。
     *
     * @return 更新后的 curveGroup 计数
     */
    public static int mergeRuns(final List<double[]> edges, final int seqIn) {
        int seq = seqIn;
        if (edges == null || edges.size() < 2) {
            return seq;
        }
        int group = -1;
        for (int i = 1; i < edges.size(); i++) {
            final double[] a = edges.get(i - 1);
            final double[] b = edges.get(i);
            final int ga = curveGroupOf(a);
            final int gb = curveGroupOf(b);
            // a 必须"还没分组"或"已经在当前这条链里"；b 必须还没分组
            if ((ga >= 0 && ga != group) || gb >= 0 || !samePoint(a, 3, b, 0)) {
                group = -1;
                continue;
            }
            final double ax = a[3] - a[0];
            final double ay = a[4] - a[1];
            final double az = a[5] - a[2];
            final double bx = b[3] - b[0];
            final double by = b[4] - b[1];
            final double bz = b[5] - b[2];
            final double la = Math.sqrt(ax * ax + ay * ay + az * az);
            final double lb = Math.sqrt(bx * bx + by * by + bz * bz);
            if (la < 1.0e-9 || lb < 1.0e-9) {
                group = -1;
                continue;
            }
            final double cx = ay * bz - az * by;
            final double cy = az * bx - ax * bz;
            final double cz = ax * by - ay * bx;
            final double sin = Math.sqrt(cx * cx + cy * cy + cz * cz) / (la * lb);
            if (sin > 0.5) {
                group = -1;
                continue;
            }
            if (group < 0) {
                group = ++seq;
                if (a.length > 13) {
                    a[13] = group;
                }
            }
            if (b.length > 13) {
                b[13] = group;
            }
        }
        return seq;
    }

    /** 合并 + 追加到目标表 ✓（同时打诊断：多少段被并成了一条链） */
    private static int mergeInto(final java.util.Map<UUID, List<double[]>> into,
                                 final java.util.Map<UUID, List<double[]>> from,
                                 final int seqIn) {
        int seq = seqIn;
        if (from == null || from.isEmpty()) {
            return seq;
        }
        for (final java.util.Map.Entry<UUID, List<double[]>> en : from.entrySet()) {
            final int before = en.getValue().size();
            final int gBefore = countGroups(en.getValue());
            seq = mergeRuns(en.getValue(), seq);
            final int gAfter = countGroups(en.getValue());
            if (gAfter != gBefore) {
                trace("fillet:  merge solid=" + shortId(en.getKey())
                        + " segs=" + before + " groups " + gBefore + "->" + gAfter);
            }
            into.computeIfAbsent(en.getKey(), k -> new ArrayList<>()).addAll(en.getValue());
        }
        return seq;
    }

    /** 一组边元组里有多少个"组"（直线棱各算一组）✓ */
    private static int countGroups(final List<double[]> edges) {
        int single = 0;
        final java.util.HashSet<Integer> g = new java.util.HashSet<>();
        for (final double[] e : edges) {
            final int gi = curveGroupOf(e);
            if (gi < 0) {
                single++;
            } else {
                g.add(gi);
            }
        }
        return single + g.size();
    }

    /** 边元组的曲线组号（-1 = 直线棱）✓ */
    private static int curveGroupOf(final double[] e) {
        return e != null && e.length > 13 ? (int) e[13] : -1;
    }

    /** 两组边是否共享端点 ✓（象限继承用） */
    private static boolean itemsTouch(final List<double[]> edges, final int[] a, final int[] b) {
        for (int i = a[0]; i < a[1]; i++) {
            for (int j = b[0]; j < b[1]; j++) {
                if (shareEndpoint(edges.get(i), edges.get(j))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 点是否在任意碰撞盒子里 ✓（圆角象限探针用） */
    private static boolean insideBoxes(final List<double[]> boxes, final Vector3d p) {
        if (boxes == null) {
            return false;
        }
        for (final double[] b : boxes) {
            if (b == null || b.length < 6) {
                continue;
            }
            if (p.x >= b[0] - 1.0e-4 && p.x <= b[3] + 1.0e-4
                    && p.y >= b[1] - 1.0e-4 && p.y <= b[4] + 1.0e-4
                    && p.z >= b[2] - 1.0e-4 && p.z <= b[5] + 1.0e-4) {
                return true;
            }
        }
        return false;
    }

    /** 两条棱是否共用端点 ✓（顶点混合/象限继承的邻接判据） */
    private static boolean shareEndpoint(final double[] e1, final double[] e2) {
        return samePoint(e1, 0, e2, 0) || samePoint(e1, 0, e2, 3)
                || samePoint(e1, 3, e2, 0) || samePoint(e1, 3, e2, 3);
    }

    private static boolean samePoint(final double[] a, final int ia,
                                     final double[] b, final int ib) {
        return Math.abs(a[ia] - b[ib]) < 2.0e-3
                && Math.abs(a[ia + 1] - b[ib + 1]) < 2.0e-3
                && Math.abs(a[ia + 2] - b[ib + 2]) < 2.0e-3;
    }

    /**
     * **顶点混合**（共顶点的多条切料圆角）✓
     *
     * <p>对每个被 2 条以上切料棱共用的顶点：从这两条棱的面法线里挑出 3 个两两近正交的
     * 方向 (n1,n2,n3)，在顶点处切掉
     * <pre>  角块 [0,r]³(沿 −n1,−n2,−n3)  −  球心 (−r,−r,−r)、半径 r 的球</pre>
     * 剩下的正好是"以顶点为中心、半径 r 的球面片" ⇒ 三条圆角在这里**共用同一张球面**，
     * 不再各切各的、在角上留尖刺 ✓
     */
    private static List<List<Csg.Polygon>> cornerBlends(final List<double[]> cutEdges,
                                                        final List<Double> thOut,
                                                        final StringBuilder log,
                                                        final int index) {
        final List<List<Csg.Polygon>> out = new ArrayList<>();
        if (cutEdges == null || cutEdges.size() < 2) {
            return out;
        }
        final java.util.List<double[]> verts = new ArrayList<>();
        final java.util.List<java.util.List<double[]>> atVert = new ArrayList<>();
        for (final double[] e : cutEdges) {
            for (int end = 0; end < 2; end++) {
                final int k = end == 0 ? 0 : 3;
                int vi = -1;
                for (int i = 0; i < verts.size(); i++) {
                    if (samePoint(verts.get(i), 0, e, k)) {
                        vi = i;
                        break;
                    }
                }
                if (vi < 0) {
                    verts.add(new double[] {e[k], e[k + 1], e[k + 2]});
                    atVert.add(new ArrayList<>());
                    vi = verts.size() - 1;
                }
                atVert.get(vi).add(e);
            }
        }
        for (int v = 0; v < verts.size(); v++) {
            final java.util.List<double[]> inc = atVert.get(v);
            if (inc.size() < 2) {
                continue;
            }
            // 收集入射棱的面法线（去重）
            final java.util.List<Vector3d> normals = new ArrayList<>();
            for (final double[] e : inc) {
                for (int k : new int[] {6, 9}) {
                    final Vector3d n = new Vector3d(e[k], e[k + 1], e[k + 2]);
                    if (n.lengthSquared() < 1.0e-9) {
                        continue;
                    }
                    n.normalize();
                    boolean dup = false;
                    for (final Vector3d o : normals) {
                        if (Math.abs(o.dot(n)) > 0.995) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) {
                        normals.add(n);
                    }
                }
            }
            if (normals.size() < 3) {
                continue;
            }
            // 挑 3 个两两近正交的
            Vector3d n1 = null;
            Vector3d n2 = null;
            Vector3d n3 = null;
            for (int i = 0; i < normals.size() && n1 == null; i++) {
                for (int j = 0; j < normals.size() && n1 == null; j++) {
                    if (j == i || Math.abs(normals.get(i).dot(normals.get(j))) > 0.35) {
                        continue;
                    }
                    for (int k = 0; k < normals.size(); k++) {
                        if (k == i || k == j) {
                            continue;
                        }
                        final Vector3d c = normals.get(k);
                        if (Math.abs(c.dot(normals.get(i))) > 0.35
                                || Math.abs(c.dot(normals.get(j))) > 0.35) {
                            continue;
                        }
                        n1 = normals.get(i);
                        n2 = normals.get(j);
                        n3 = c;
                        break;
                    }
                }
            }
            if (n1 == null) {
                continue;
            }
            final double r = inc.get(0)[12];
            final Vector3d vp = new Vector3d(verts.get(v)[0], verts.get(v)[1],
                    verts.get(v)[2]);
            final List<Csg.Polygon> blend = cornerBlend(vp, n1, n2, n3, r);
            if (!blend.isEmpty()) {
                out.add(blend);
                // 理论体积 = 角块 − 球缺 = r³(1−π/6)（实测 0.476r³）
                if (thOut != null) {
                    thOut.add(r * r * r * (1.0 - Math.PI / 6.0));
                }
            }
        }
        if (!out.isEmpty()) {
            log.append(" [f").append(index).append(":vblend ").append(out.size()).append(']');
        }
        return out;
    }

    /** 单个顶点的混合切割体 = 角块 − 球缺 ✓（harness 直接验几何） */
    public static List<Csg.Polygon> cornerBlend(final Vector3d v, final Vector3d n1,
                                                 final Vector3d n2, final Vector3d n3,
                                                 final double r) {
        if (r <= 1.0e-6) {
            return List.of();
        }
        // 材料侧方向（法线取反）
        final Vector3d a = new Vector3d(n1).negate();
        final Vector3d b = new Vector3d(n2).negate();
        final Vector3d c = new Vector3d(n3).negate();
        final List<Vector3d[]> cube = new ArrayList<>(12);
        final Vector3d[] p = new Vector3d[8];
        for (int i = 0; i < 8; i++) {
            p[i] = new Vector3d(v)
                    .fma((i & 1) == 0 ? 0.0 : r, a)
                    .fma((i & 2) == 0 ? 0.0 : r, b)
                    .fma((i & 4) == 0 ? 0.0 : r, c);
        }
        final int[][] faces = {
            {0, 2, 3, 1}, {4, 5, 7, 6}, {0, 1, 5, 4},
            {2, 6, 7, 3}, {0, 4, 6, 2}, {1, 3, 7, 5},
        };
        for (final int[] f : faces) {
            cube.add(new Vector3d[] {p[f[0]], p[f[1]], p[f[2]]});
            cube.add(new Vector3d[] {p[f[0]], p[f[2]], p[f[3]]});
        }
        final List<Csg.Polygon> cubePolys = ensureOutward(triPolys(cube));
        final Vector3d center = new Vector3d(v).fma(r, a).fma(r, b).fma(r, c);
        final List<Csg.Polygon> ballPolys = ensureOutward(triPolys(ball(center, r, 8, 16)));
        if (cubePolys.isEmpty() || ballPolys.isEmpty()) {
            return List.of();
        }
        return subtractLocal(cubePolys, ballPolys);
    }

    /** 经纬球（三角面）✓ */
    private static List<Vector3d[]> ball(final Vector3d c, final double r,
                                         final int stacks, final int slices) {
        final List<Vector3d[]> tris = new ArrayList<>();
        final double[][] grid = new double[(stacks + 1) * (slices + 1)][];
        for (int i = 0; i <= stacks; i++) {
            final double phi = Math.PI * i / stacks;
            for (int j = 0; j <= slices; j++) {
                final double th = 2.0 * Math.PI * j / slices;
                grid[i * (slices + 1) + j] = new double[] {
                    c.x + r * Math.sin(phi) * Math.cos(th),
                    c.y + r * Math.cos(phi),
                    c.z + r * Math.sin(phi) * Math.sin(th)};
            }
        }
        for (int i = 0; i < stacks; i++) {
            for (int j = 0; j < slices; j++) {
                final double[] p00 = grid[i * (slices + 1) + j];
                final double[] p01 = grid[i * (slices + 1) + j + 1];
                final double[] p10 = grid[(i + 1) * (slices + 1) + j];
                final double[] p11 = grid[(i + 1) * (slices + 1) + j + 1];
                tris.add(new Vector3d[] {new Vector3d(p00[0], p00[1], p00[2]),
                    new Vector3d(p10[0], p10[1], p10[2]),
                    new Vector3d(p11[0], p11[1], p11[2])});
                tris.add(new Vector3d[] {new Vector3d(p00[0], p00[1], p00[2]),
                    new Vector3d(p11[0], p11[1], p11[2]),
                    new Vector3d(p01[0], p01[1], p01[2])});
            }
        }
        return tris;
    }

    /** 把一批楔块合并成一个操作数（单个原样返回；多个顺序并集）✓ */
    private static List<Csg.Polygon> mergeWedges(final List<List<Csg.Polygon>> wedges) {
        if (wedges == null || wedges.isEmpty()) {
            return List.of();
        }
        List<Csg.Polygon> acc = wedges.get(0);
        for (int i = 1; i < wedges.size(); i++) {
            acc = unionLocal(acc, wedges.get(i));
        }
        return acc;
    }

    /**
     * 造圆角楔块（**指定象限**）✓
     *
     * <p>{@link FilletCutter#prism} 内部把传入法线取反作为楔块的延伸方向：
     * 所以传 {@code m1 = −q1·n1, m2 = −q2·n2}，得到的楔块沿 {@code (q1·n1, q2·n2)} 象限延伸 ✓
     */
    private static List<Csg.Polygon> wedgePolys(final Vector3d a, final Vector3d b,
                                                final Vector3d m1, final Vector3d m2,
                                                final double r, final Vector3d dir,
                                                final Vector3d uvNormal) {
        final List<Vector3d[]> tris = FilletCutter.prism(a, b, m1, m2, r);
        if (tris == null || tris.isEmpty()) {
            return List.of();
        }
        // UV 一律用**同一个**参考法线投影：换象限时贴图方向不能跟着乱翻 ✓
        return ensureOutward(triPolys(tris, dir, uvNormal));
    }

    /**
     * **圆角象限判定**（凸/凹 + 楔块该建在哪一象限）✓ —— 减料/补料都靠它
     *
     * <p>在棱中点沿四条角平分线、距离 {@code 0.5r} 各取一个探针，数材料占几个象限：
     * <ul>
     *   <li>**1 个实心** ⇒ 凸棱：楔块建在这个实心象限里（削掉锐角）</li>
     *   <li>**3 个实心** ⇒ 凹棱：楔块建在唯一空着的象限（填缺口）</li>
     *   <li>0 / 2 / 4 ⇒ 不是可用棱（平面、台阶、或点在体外）⇒ 调用方跳过</li>
     * </ul>
     * 关键在于「几实几空」和「哪一个空」都是**实测**的 ⇒ 与 n1/n2 的符号无关，
     * 存档里的法线朝里朝外都能选对象限（之前靠体积签名，凸凹同签名 ⇒ 必然随机）✓
     *
     * @param inside 点是否在材料内的判定（求值用 CSG 点测试；预览用体素盒）
     * @param out    输出 {q1, q2, concave?1:0, 象限位图}；q1/q2 是 ±1 的象限符号
     * @return 实心象限个数（0..4）
     */
    public static int filletQuadrant(final java.util.function.Predicate<Vector3d> inside,
                                     final Vector3d a, final Vector3d b,
                                     final Vector3d n1, final Vector3d n2,
                                     final double r, final double[] out) {
        final Vector3d mid = new Vector3d(a).add(b).mul(0.5);
        final double probe = 0.5 * r;
        final int[] s1s = new int[4];
        final int[] s2s = new int[4];
        final boolean[] filled = new boolean[4];
        int pattern = 0;
        int k = 0;
        for (int s1 = -1; s1 <= 1; s1 += 2) {
            for (int s2 = -1; s2 <= 1; s2 += 2) {
                s1s[k] = s1;
                s2s[k] = s2;
                final Vector3d d = new Vector3d(n1).mul(s1).add(new Vector3d(n2).mul(s2));
                final boolean in = d.lengthSquared() >= 1.0e-12
                        && inside.test(new Vector3d(mid).fma(probe, d.normalize()));
                filled[k] = in;
                if (in) {
                    pattern |= 1 << k;
                }
                k++;
            }
        }
        if (out != null && out.length >= 4) {
            out[3] = pattern;
        }
        int cnt = 0;
        int onlyFilled = -1;
        int onlyEmpty = -1;
        for (int i = 0; i < 4; i++) {
            if (filled[i]) {
                cnt++;
                onlyFilled = i;
            } else {
                onlyEmpty = i;
            }
        }
        if (out != null && out.length >= 3 && (cnt == 1 || cnt == 3)) {
            final int pick = cnt == 1 ? onlyFilled : onlyEmpty;
            out[0] = s1s[pick];
            out[1] = s2s[pick];
            out[2] = cnt == 3 ? 1.0 : 0.0;
        }
        return cnt;
    }

    /** 短 id（日志用）✓ */
    public static String shortId(final UUID id) {
        return id == null ? "-" : id.toString().substring(0, 8);
    }

    /** 数值格式化（日志用）✓ */
    public static String fmt(final double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** 键集合（日志用）✓ */
    private static String bodyKeys(final java.util.Map<UUID, List<double[]>> map) {
        final StringBuilder sb = new StringBuilder();
        for (final UUID k : map.keySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(shortId(k));
        }
        return sb.toString();
    }

    /**
     * 一组多边形的**有向体积** ✓（**必须在局部原点下算**）
     *
     * <h2>为什么不能直接用绝对坐标（这是圆角全线失效的真正根因，实测）</h2>
     * 玩家的模型在 2.0e7 附近。三重积的每一项 ≈ 2e7·(2e7·130) ≈ 1e14~1e21，而结果只有 ~0.05，
     * 差值的双精度抵消误差可达 **0.1~20**。实测：`theory=0.054` 的楔块被算成
     * `18.463` / `2.792` / `0.787`（全是噪声）⇒ 一方面体积门把我们自己的正确楔块判成"big"跳过，
     * 另一方面 {@link #ensureOutward} 靠这个符号翻面 ⇒ **符号随机 ⇒ 楔块一半是朝里的**
     * ⇒ CSG 差集什么都剪不到（"圆角建了模型没变化"）或剪出垃圾。
     *
     * <p>三重积和对**闭合曲面**是平移不变量 ⇒ 平移到第一个顶点后再算，误差降到 1e-12 级别，
     * 结果与理论值一致 ✓
     */
    public static double polysVolume(final List<Csg.Polygon> polys) {
        if (polys == null || polys.isEmpty()) {
            return 0.0;
        }
        final Csg.V3 o = polys.get(0).vertices.get(0).pos();
        final double ox = o.x();
        final double oy = o.y();
        final double oz = o.z();
        double sum = 0.0;
        for (final Csg.Polygon p : polys) {
            final List<Csg.Vertex> vs = p.vertices;
            for (int i = 1; i + 1 < vs.size(); i++) {
                final Csg.V3 a = vs.get(0).pos();
                final Csg.V3 b = vs.get(i).pos();
                final Csg.V3 c = vs.get(i + 1).pos();
                final double ax = a.x() - ox;
                final double ay = a.y() - oy;
                final double az = a.z() - oz;
                final double bx = b.x() - ox;
                final double by = b.y() - oy;
                final double bz = b.z() - oz;
                final double cx = c.x() - ox;
                final double cy = c.y() - oy;
                final double cz = c.z() - oz;
                sum += ax * (by * cz - bz * cy)
                        - ay * (bx * cz - bz * cx)
                        + az * (bx * cy - by * cx);
            }
        }
        return sum / 6.0;
    }

    /**
     * 点是否在**封闭实体内部** ✓（圆角凸/凹判定用）
     *
     * <p>射线奇偶法：三个互不平行的方向各打一条射线，**多数投票**。
     * 边/顶点被射线擦过的概率用三方向投票压掉；坐标以 p 为原点展开，
     * 避开 2e7 处相减丢精度 ✓
     */
    public static boolean containsPoint(final List<Csg.Polygon> polys, final Vector3d p) {
        if (polys == null || polys.isEmpty() || p == null) {
            return false;
        }
        final double[][] dirs = {
            {0.57735, 0.61111, 0.54167},
            {0.31220, -0.74010, 0.59430},
            {-0.68330, 0.40210, 0.61130},
        };
        int votes = 0;
        for (final double[] d : dirs) {
            final Vector3d dir = new Vector3d(d[0], d[1], d[2]).normalize();
            if (rayHitsOdd(polys, p, dir)) {
                votes++;
            }
        }
        return votes >= 2;
    }

    /** 射线从 p 出发（p 为原点）与实体相交次数是否为奇数 ✓ */
    private static boolean rayHitsOdd(final List<Csg.Polygon> polys, final Vector3d p,
                                      final Vector3d dir) {
        int hits = 0;
        for (final Csg.Polygon poly : polys) {
            final List<Csg.Vertex> vs = poly.vertices;
            for (int i = 1; i + 1 < vs.size(); i++) {
                if (rayHitsTri(vs.get(0).pos(), vs.get(i).pos(),
                        vs.get(i + 1).pos(), p, dir)) {
                    hits++;
                }
            }
        }
        return (hits & 1) == 1;
    }

    /** Möller–Trumbore（p 平移到原点，t>0 才算命中）✓ */
    private static boolean rayHitsTri(final Csg.V3 a, final Csg.V3 b, final Csg.V3 c,
                                      final Vector3d p, final Vector3d dir) {
        final double ax = a.x() - p.x;
        final double ay = a.y() - p.y;
        final double az = a.z() - p.z;
        final double bx = b.x() - p.x;
        final double by = b.y() - p.y;
        final double bz = b.z() - p.z;
        final double cx = c.x() - p.x;
        final double cy = c.y() - p.y;
        final double cz = c.z() - p.z;
        final double e1x = bx - ax;
        final double e1y = by - ay;
        final double e1z = bz - az;
        final double e2x = cx - ax;
        final double e2y = cy - ay;
        final double e2z = cz - az;
        final double hx = dir.y * e2z - dir.z * e2y;
        final double hy = dir.z * e2x - dir.x * e2z;
        final double hz = dir.x * e2y - dir.y * e2x;
        final double det = e1x * hx + e1y * hy + e1z * hz;
        if (Math.abs(det) < 1.0e-14) {
            return false;
        }
        final double inv = 1.0 / det;
        // s = origin − a
        final double sx = -ax;
        final double sy = -ay;
        final double sz = -az;
        final double u = (sx * hx + sy * hy + sz * hz) * inv;
        if (u < 0.0 || u > 1.0) {
            return false;
        }
        final double qx = sy * e1z - sz * e1y;
        final double qy = sz * e1x - sx * e1z;
        final double qz = sx * e1y - sy * e1x;
        final double v = (dir.x * qx + dir.y * qy + dir.z * qz) * inv;
        if (v < 0.0 || u + v > 1.0) {
            return false;
        }
        final double t = (e2x * qx + e2y * qy + e2z * qz) * inv;
        return t > 1.0e-9;
    }

    private static List<Csg.Polygon> subtractLocal(final List<Csg.Polygon> base,
                                                   final List<Csg.Polygon> cutter) {
        if (base.isEmpty() || cutter.isEmpty()) {
            return base;
        }
        final Csg.V3 first = base.get(0).vertices.get(0).pos();
        final double ox = first.x();
        final double oy = first.y();
        final double oz = first.z();
        final List<Csg.Polygon> a = shiftedPolys(base, -ox, -oy, -oz);
        final List<Csg.Polygon> b = shiftedPolys(cutter, -ox, -oy, -oz);
        final List<Csg.Polygon> res = Csg.subtract(a, b);
        return shiftedPolys(res, ox, oy, oz);
    }

    /** 平移一组多边形（连同平面常数）✓ */
    private static List<Csg.Polygon> shiftedPolys(final List<Csg.Polygon> polys,
                                                  final double dx, final double dy,
                                                  final double dz) {
        final List<Csg.Polygon> out = new ArrayList<>(polys.size());
        for (final Csg.Polygon p : polys) {
            final List<Csg.Vertex> vs = new ArrayList<>(p.vertices.size());
            for (final Csg.Vertex v : p.vertices) {
                vs.add(new Csg.Vertex(
                        new Csg.V3(v.pos().x() + dx, v.pos().y() + dy, v.pos().z() + dz),
                        v.normal(), v.uv()));
            }
            final Csg.Plane pl = p.plane;
            // 字段而不是 getter：Csg.Plane 是普通类（normal/w 是 public final 字段）✓
            final double w = pl.w + (pl.normal.x() * dx + pl.normal.y() * dy
                    + pl.normal.z() * dz);
            out.add(new Csg.Polygon(vs, new Csg.Plane(pl.normal, w)));
        }
        return out;
    }

    /** 网格减去一批盒子（碰撞/渲染共用）✓ */
    private static SolidMesh.Mesh subtractBoxesMesh(final SolidMesh.Mesh mesh,
                                                    final List<double[]> cutterBoxes) {
        if (mesh == null || mesh.isEmpty() || cutterBoxes == null || cutterBoxes.isEmpty()) {
            return mesh;
        }
        final SolidMesh.Mesh cutter = SolidMesh.fromBoxes(cutterBoxes);
        if (cutter == null || cutter.isEmpty()) {
            return mesh;
        }
        final List<Csg.Polygon> base = ensureOutward(CsgMesh.fromMesh(mesh, null));
        final List<Csg.Polygon> cut = ensureOutward(CsgMesh.fromMesh(cutter, null));
        if (base.isEmpty() || cut.isEmpty()) {
            return mesh;
        }
        final List<Csg.Polygon> res = subtractLocal(base, cut);
        return res.isEmpty() ? mesh : CsgMesh.toMesh(res);
    }

    /**
     * 用**真实的切割体网格**（不是碰撞盒近似）做减法 ✓ —— 镜像/阵列产物专用。
     *
     * <h2>为什么不能继续用 {@link #subtractBoxesMesh}</h2>
     * 盒子近似只适合碰撞/代价高的场合；它用在**渲染网格**上时：
     * <ul>
     *   <li>弧形/斜的切割口变成**阶梯状的方块轮廓**；</li>
     *   <li>新生成的切割面用的是盒子的**简单 UV**（每面 0..1）⇒ 贴图被拉伸/错位。</li>
     * </ul>
     * 源实体走的是真网格 CSG（`Csg.subtract` + 切割体网格），所以只有镜像/阵列
     * 产物上的切割贴图不对（用户实测）。这里换成同一套真网格减法。
     *
     * @param mesh    产物网格（局部坐标，已反射/平移）
     * @param cutters 切割体网格（与 mesh 在同一个空间）
     */
    private static SolidMesh.Mesh subtractCutterMeshes(final SolidMesh.Mesh mesh,
                                                       final List<SolidMesh.Mesh> cutters) {
        if (mesh == null || mesh.isEmpty() || cutters == null || cutters.isEmpty()) {
            return mesh;
        }
        List<Csg.Polygon> acc = ensureOutward(CsgMesh.fromMesh(mesh, null));
        if (acc.isEmpty()) {
            return mesh;
        }
        for (final SolidMesh.Mesh c : cutters) {
            if (c == null || c.isEmpty()) {
                continue;
            }
            final List<Csg.Polygon> cp = CsgMesh.fromMesh(c, null);
            if (cp.isEmpty()) {
                continue;
            }
            final List<Csg.Polygon> next = subtractLocal(acc, cp);
            if (!next.isEmpty()) {
                acc = next;
            }
        }
        return acc.isEmpty() ? mesh : CsgMesh.toMesh(acc);
    }

    private static SolidMesh.Mesh cutWithFillets(final SolidMesh.Mesh mesh,
                                                  final List<Csg.Polygon> filletPolys) {
        if (mesh == null || mesh.isEmpty() || filletPolys == null || filletPolys.isEmpty()) {
            return mesh;
        }
        // ★ 底也要先规范化朝向：镜像出来的网格绕序是反的（体积为负）⇒ 直接减会得到垃圾
        final List<Csg.Polygon> base = ensureOutward(CsgMesh.fromMesh(mesh, null));
        if (base.isEmpty()) {
            return mesh;
        }
        final List<Csg.Polygon> cut = subtractLocal(base, ensureOutward(filletPolys));
        if (cut.isEmpty()) {
            return mesh;
        }
        return CsgMesh.toMesh(cut);
    }


    /**
     * 保证一组多边形是**外向**的 ✓（有向体积为正）
     *
     * <p>为什么必须做 ✗✓：`csg.js` 的 BSP 要求实体多边形朝外；朝里的切割体会被当成"外部"
     * ⇒ `subtract` 什么都剪不到 ⇒ 圆角"建了但看不出变化"（实测症状）。
     * 这里按**有向体积**判断，负的就整体翻面，不依赖我们对绕序的推理 ✓
     */
    public static List<Csg.Polygon> ensureOutward(final List<Csg.Polygon> polys) {
        if (polys == null || polys.isEmpty()) {
            return polys;
        }
        // ★ 一定要用**局部原点**下的体积（2e7 处的绝对坐标三重积符号是随机的，实测）
        if (polysVolume(polys) >= 0.0) {
            return polys;
        }
        final List<Csg.Polygon> flipped = new ArrayList<>(polys.size());
        for (final Csg.Polygon poly : polys) {
            flipped.add(poly.flipped());
        }
        return flipped;
    }

    /** 平移一个盒子 ✓ */
    public static double[] translateBox(final double[] box, final double[] off) {
        return new double[] {box[0] + off[0], box[1] + off[1], box[2] + off[2],
                box[3] + off[0], box[4] + off[1], box[5] + off[2]};
    }

    /** 平移一个网格 ✓ */
    private static SolidMesh.Mesh translateMesh(final SolidMesh.Mesh in, final double[] off) {
        final List<SolidMesh.Vertex> vs = new java.util.ArrayList<>(in.vertices().size());
        for (final SolidMesh.Vertex v : in.vertices()) {
            vs.add(new SolidMesh.Vertex(
                    new Vector3d(v.position()).add(off[0], off[1], off[2]),
                    new Vector3d(v.normal()), v.u(), v.v()));
        }
        return new SolidMesh.Mesh(vs, in.indices());
    }

    /** 反射一个点 ✓ */
    public static Vector3d reflectPoint(final Vector3d p, final double[] o, final double[] n) {
        final double x = p.x - o[0];
        final double y = p.y - o[1];
        final double z = p.z - o[2];
        final double d = x * n[0] + y * n[1] + z * n[2];
        return new Vector3d(x - 2 * d * n[0] + o[0], y - 2 * d * n[1] + o[1],
                z - 2 * d * n[2] + o[2]);
    }

    /** 反射一个**方向**（只转方向、不平移）✓ */
    private static Vector3d reflectDir(final Vector3d d, final double[] n) {
        final double k = 2.0 * (d.x * n[0] + d.y * n[1] + d.z * n[2]);
        return new Vector3d(d.x - k * n[0], d.y - k * n[1], d.z - k * n[2]);
    }

    /**
     * 反射一条**圆角边引用** ✓（几何 + 两个面法线 + 曲线折线）
     *
     * <p>给“圆角特征也能被镜像”用：产物的网格在镜像后的局部空间里，
     * 所以棱也必须用同一个反射变换搬过去（法线按反射矩阵转，不是整体取反）。
     */
    private static Shape.EdgeRef reflectRef(final Shape.EdgeRef r, final double[] o,
                                            final double[] n) {
        final Vector3d a = reflectPoint(new Vector3d(r.ax(), r.ay(), r.az()), o, n);
        final Vector3d b = reflectPoint(new Vector3d(r.bx(), r.by(), r.bz()), o, n);
        final Vector3d n1 = reflectDir(new Vector3d(r.n1x(), r.n1y(), r.n1z()), n);
        final Vector3d n2 = reflectDir(new Vector3d(r.n2x(), r.n2y(), r.n2z()), n);
        double[] curve = new double[0];
        if (r.isCurve()) {
            final List<double[]> pts = r.curvePoints();
            curve = new double[pts.size() * 3];
            int k = 0;
            for (final double[] p : pts) {
                final Vector3d rp = reflectPoint(new Vector3d(p[0], p[1], p[2]), o, n);
                curve[k++] = rp.x;
                curve[k++] = rp.y;
                curve[k++] = rp.z;
            }
        }
        return new Shape.EdgeRef(r.solidId(), a.x, a.y, a.z, b.x, b.y, b.z,
                n1.x, n1.y, n1.z, n2.x, n2.y, n2.z, curve);
    }

    /** 平移一条**圆角边引用**（阵列副本用）✓ */
    private static Shape.EdgeRef translateRef(final Shape.EdgeRef r, final double[] off) {
        double[] curve = new double[0];
        if (r.isCurve()) {
            final List<double[]> pts = r.curvePoints();
            curve = new double[pts.size() * 3];
            int k = 0;
            for (final double[] p : pts) {
                curve[k++] = p[0] + off[0];
                curve[k++] = p[1] + off[1];
                curve[k++] = p[2] + off[2];
            }
        }
        return new Shape.EdgeRef(r.solidId(),
                r.ax() + off[0], r.ay() + off[1], r.az() + off[2],
                r.bx() + off[0], r.by() + off[1], r.bz() + off[2],
                r.n1x(), r.n1y(), r.n1z(), r.n2x(), r.n2y(), r.n2z(), curve);
    }

    /** 反射一个网格 ✓（位置反射、法线按**反射矩阵**变换、三角形绕序翻转） */
    private static SolidMesh.Mesh reflectMesh(final SolidMesh.Mesh in, final double[] o,
                                              final double[] n) {
        final List<SolidMesh.Vertex> vs = new java.util.ArrayList<>(in.vertices().size());
        final double[] zero = {0, 0, 0};
        for (final SolidMesh.Vertex v : in.vertices()) {
            final Vector3d p = reflectPoint(v.position(), o, n);
            // ★★ 法线必须用**反射矩阵**变换：R·n = n − 2(n·n̂)n̂ ✔
            //   以前直接写成 (−x,−y,−z)（整体取反）✗ —— 只有镜像面法线恰好竖直时才对，
            //   一般朝向（尤其斜镜像面）下法线是错的 ⇒ 带法线的着色/光影发暗、光照方向不对
            //   （用户实测"光照看起来有点暗"）。绕序已经翻过 ⇒ 存 R·n 与几何朝向一致 ✓
            final Vector3d rn = reflectPoint(v.normal(), zero, n);
            vs.add(new SolidMesh.Vertex(p, rn, v.u(), v.v()));
        }
        final int[] src = in.indices();
        final int[] outIdx = new int[src.length];
        for (int i = 0; i + 2 < src.length; i += 3) {
            outIdx[i] = src[i];
            outIdx[i + 1] = src[i + 2]; // 绕序翻转 ✓（否则法线朝内）
            outIdx[i + 2] = src[i + 1];
        }
        return new SolidMesh.Mesh(vs, outIdx);
    }

    /** 拼接两个网格 ✓（镜像多个源特征时用） */
    private static SolidMesh.Mesh concatMesh(final SolidMesh.Mesh a, final SolidMesh.Mesh b) {
        final List<SolidMesh.Vertex> vs = new java.util.ArrayList<>(a.vertices());
        vs.addAll(b.vertices());
        final int[] ia = a.indices();
        final int[] ib = b.indices();
        final int[] outIdx = new int[ia.length + ib.length];
        System.arraycopy(ia, 0, outIdx, 0, ia.length);
        for (int i = 0; i < ib.length; i++) {
            outIdx[ia.length + i] = ib[i] + a.vertices().size();
        }
        return new SolidMesh.Mesh(vs, outIdx);
    }

    /**
     * **盒子减法** ✓（需求 ⑥）：把 {@code target} 里每个盒子减去所有 cutter 覆盖的部分。
     *
     * <p>一个轴对齐盒被另一个盒切，最多剩下 6 块（左/右/下/上/前/后）✓
     * 这是"盒子层布尔"的核心 —— 与碰撞用同一套盒子 ⇒ 视觉与物理必然一致 ✓
     */
    public static List<double[]> subtractBoxes(final List<double[]> target,
                                               final List<double[]> cutters) {
        List<double[]> current = new ArrayList<>(target);
        for (final double[] cut : cutters) {
            final List<double[]> next = new ArrayList<>();
            for (final double[] box : current) {
                subtractOne(box, cut, next);
            }
            current = next;
            if (current.isEmpty()) {
                break;
            }
        }
        return current;
    }

    /** 一个盒子减一个盒子 ⇒ 0..6 块 ✓（盒子格式 {x0,y0,z0,x1,y1,z1} ✓）。 */
    private static void subtractOne(final double[] box, final double[] cut,
                                    final List<double[]> out) {
        if (cut[0] >= box[3] || cut[3] <= box[0]
                || cut[1] >= box[4] || cut[4] <= box[1]
                || cut[2] >= box[5] || cut[5] <= box[2]) {
            out.add(box); // 不相交 ⇒ 原样保留 ✓
            return;
        }
        if (cut[0] <= box[0] && cut[1] <= box[1] && cut[2] <= box[2]
                && cut[3] >= box[3] && cut[4] >= box[4] && cut[5] >= box[5]) {
            return; // 完全被吞掉 ✓
        }
        addIfPositive(out, box[0], box[1], box[2], Math.min(box[3], cut[0]), box[4], box[5]);
        addIfPositive(out, Math.max(box[0], cut[3]), box[1], box[2], box[3], box[4], box[5]);
        addIfPositive(out, box[0], box[1], box[2], box[3], Math.min(box[4], cut[1]), box[5]);
        addIfPositive(out, box[0], Math.max(box[1], cut[4]), box[2], box[3], box[4], box[5]);
        addIfPositive(out, box[0], box[1], box[2], box[3], box[4], Math.min(box[5], cut[2]));
        addIfPositive(out, box[0], box[1], Math.max(box[2], cut[5]), box[3], box[4], box[5]);
    }

    private static void addIfPositive(final List<double[]> out, final double x0, final double y0,
                                      final double z0, final double x1, final double y1,
                                      final double z1) {
        if (x1 - x0 > 1.0e-9 && y1 - y0 > 1.0e-9 && z1 - z0 > 1.0e-9) {
            out.add(new double[] {x0, y0, z0, x1, y1, z1});
        }
    }

    /**
     * 网格**按切割盒精确裁剪** ✓（需求 ⑥：用户明确"重心在显示/渲染"）。
     *
     * <h2>为什么可以做到精确</h2>
     * 我们的切割体**不是任意网格**：它由 {@code Extrude.boxesOf} 生成 ⇒ 是一堆**盒子**。
     * 三角形（凸）减一个盒子（凸）可以**精确分解**成"凸多边形"：
     * <pre>
     *   T \ B = T \ (H1 ∩ … ∩ H6) = ∪  (T ∩ ¬Hi ∩ H1 ∩ … ∩ H(i−1))
     * </pre>
     * 每一项都是**凸的**（半空间与凸多边形求交）⇒ 用 Sutherland–Hodgman 逐平面切即可 ✓
     *
     * <p>每个三角形对每个盒子最多变成 6 块；顶点带法线与 UV，插值后贴图仍然连续 ✓
     * （之前是"重心落在盒内就整块丢掉"⇒ 洞边是锯齿；现在边界是**真正裁出来的** ✓）
     */
    public static SolidMesh.Mesh clipMesh(final SolidMesh.Mesh mesh,
                                          final List<double[]> cutters) {
        if (mesh == null || mesh.isEmpty() || cutters == null || cutters.isEmpty()) {
            return mesh;
        }
        final List<SolidMesh.Vertex> vs = mesh.vertices();
        final int[] idx = mesh.indices();
        final List<SolidMesh.Vertex> outV = new ArrayList<>();
        final List<Integer> outI = new ArrayList<>();
        for (int i = 0; i + 2 < idx.length; i += 3) {
            List<CVert[]> polys = new ArrayList<>();
            polys.add(new CVert[] {
                    cv(vs.get(idx[i])), cv(vs.get(idx[i + 1])), cv(vs.get(idx[i + 2]))});
            for (final double[] cut : cutters) {
                final List<CVert[]> next = new ArrayList<>();
                for (final CVert[] poly : polys) {
                    subtractBox(poly, cut, next);
                }
                polys = next;
                if (polys.isEmpty()) {
                    break;
                }
            }
            for (final CVert[] poly : polys) {
                if (poly.length < 3) {
                    continue;
                }
                final int base = outV.size();
                for (final CVert v : poly) {
                    outV.add(new SolidMesh.Vertex(
                            new Vector3d(v.x, v.y, v.z),
                            new Vector3d(v.nx, v.ny, v.nz), v.u, v.v));
                }
                for (int k = 1; k + 1 < poly.length; k++) {
                    outI.add(base);
                    outI.add(base + k);
                    outI.add(base + k + 1);
                }
            }
        }
        final int[] ni = new int[outI.size()];
        for (int i = 0; i < ni.length; i++) {
            ni[i] = outI.get(i);
        }
        return new SolidMesh.Mesh(outV, ni);
    }

    /** 裁剪用的顶点（位置 + 法线 + UV ✓ 与 {@link SolidMesh.Vertex} 一一对应）。 */
    private record CVert(double x, double y, double z, double nx, double ny, double nz,
                         double u, double v) {
    }

    private static CVert cv(final SolidMesh.Vertex v) {
        return new CVert(v.position().x, v.position().y, v.position().z,
                v.normal().x, v.normal().y, v.normal().z, v.u(), v.v());
    }

    /** 三角形（凸多边形）减一个盒子 ⇒ 把剩余的凸块追加进 {@code out} ✓。 */
    private static void subtractBox(final CVert[] poly, final double[] box,
                                    final List<CVert[]> out) {
        // 盒子的 6 个"内部"半空间：a*x+b*y+c*z <= d ✓
        final double[][] planes = {
                {-1, 0, 0, -box[0]}, {1, 0, 0, box[3]},
                {0, -1, 0, -box[1]}, {0, 1, 0, box[4]},
                {0, 0, -1, -box[2]}, {0, 0, 1, box[5]},
        };
        CVert[] inside = poly;
        for (final double[] pl : planes) {
            if (inside.length < 3) {
                return;
            }
            final CVert[] outsidePart = clip(inside, pl, false);
            if (outsidePart.length >= 3) {
                out.add(outsidePart); // ¬Hi ∩ H1..H(i−1) 的那一块 ✓
            }
            inside = clip(inside, pl, true); // 继续往"盒内"那一侧收 ✓
        }
        // 剩下的 inside 就是被盒子挖掉的部分 ⇒ 丢弃 ✓
    }

    /** Sutherland–Hodgman：按半空间裁剪凸多边形 ✓（{@code keepInside} 决定保留哪一侧）。 */
    private static CVert[] clip(final CVert[] poly, final double[] pl,
                                final boolean keepInside) {
        if (poly.length == 0) {
            return poly;
        }
        final List<CVert> res = new ArrayList<>();
        for (int i = 0; i < poly.length; i++) {
            final CVert cur = poly[i];
            final CVert nxt = poly[(i + 1) % poly.length];
            final double dc = pl[0] * cur.x + pl[1] * cur.y + pl[2] * cur.z - pl[3];
            final double dn = pl[0] * nxt.x + pl[1] * nxt.y + pl[2] * nxt.z - pl[3];
            final boolean inCur = keepInside ? dc <= 0 : dc >= 0;
            final boolean inNxt = keepInside ? dn <= 0 : dn >= 0;
            if (inCur) {
                res.add(cur);
            }
            if (inCur != inNxt) {
                final double t = dc / (dc - dn);
                res.add(lerp(cur, nxt, t));
            }
        }
        return res.toArray(new CVert[0]);
    }

    private static CVert lerp(final CVert a, final CVert b, final double t) {
        return new CVert(
                a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t,
                a.nx + (b.nx - a.nx) * t, a.ny + (b.ny - a.ny) * t, a.nz + (b.nz - a.nz) * t,
                a.u + (b.u - a.u) * t, a.v + (b.v - a.v) * t);
    }

    /**
     * 一个实体连同它算出来的几何。
     *
     * <p>{@code boxes} 给**碰撞**（盒并集，Sable 的物理只吃盒子），
     * {@code edges} 给**线框渲染**（真正的棱柱棱边），{@code mesh} 给**贴图渲染**。
     * 三者刻意分开：拿盒子去渲染会得到"立方体拼接"而不是草图形状；
     * 而 mesh 在没有贴图时根本不会被用到（省掉一次三角化）。
     *
     * @param mesh        三角网格；旋转体目前是**空网格**（见 {@link #mesh}）
     * @param planeNormal 草图平面法线（= 拉伸方向）；{@code mesh} 为空时可能为 null
     */
    public record Placed(Solid solid, List<double[]> boxes, List<Vector3d[]> edges,
                         SolidMesh.Mesh mesh, @Nullable Vector3d planeNormal) {
    }
}
