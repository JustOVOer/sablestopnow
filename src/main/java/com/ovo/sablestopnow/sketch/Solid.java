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
                       double n2x, double n2y, double n2z) {

            /** 旧版（无面法线）：法线置零，几何侧会退回"从网格搜相邻面"✓ */
            public EdgeRef(final java.util.UUID solidId, final double ax, final double ay,
                           final double az, final double bx, final double by, final double bz) {
                this(solidId, ax, ay, az, bx, by, bz, 0, 0, 0, 0, 0, 0);
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
        for (final Solid s2 : solids) {
            byId.put(s2.id(), s2);
        }
        final java.util.Map<UUID, List<Csg.Polygon>> filletPolyByBody = new java.util.HashMap<>();
        for (final Solid s2 : solids) {
            if (!(s2.shape() instanceof final Shape.Fillet f)) {
                continue;
            }
            final List<Csg.Polygon> polys = new ArrayList<>();
            for (final Shape.EdgeRef ref : f.edges()) {
                final Solid src = byId.get(ref.solidId());
                if (src == null) {
                    continue;
                }
                final Sketch srcSketch = src.resolve(sketches);
                if (srcSketch == null) {
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
                    continue;
                }
                final Vector3d fdir = new Vector3d(b).sub(a).normalize();
                polys.addAll(triPolys(FilletCutter.prism(a, b, nn[0], nn[1], f.radius()),
                        fdir, nn[0]));
            }
            if (!polys.isEmpty()) {
                filletPolyByBody.put(s2.body(), polys);
                lastCsgDiag = "[fillet] prism polys=" + polys.size() + " for body " + s2.body();
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
                if (targetMesh == null || targetMesh.isEmpty()) {
                    targetMesh = boxesInLocal(sketch, rawBoxes);
                }
                List<Csg.Polygon> acc = CsgMesh.fromMesh(targetMesh, null);
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
                // ★ item 8：圆角参与 CSG 减法 —— **通用方案**：沿棱扫出多边形棱柱（任意朝向）✓
                //   盒子路径仍保留：给碰撞用（轴对齐时精确），渲染用棱柱 ⇒ 斜棱也能倒圆 ✓
                if (!filletPolys.isEmpty()) {
                    acc = Csg.subtract(acc, ensureOutward(filletPolys));
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
            final List<double[]> boxes = new ArrayList<>();
            final List<Vector3d[]> edges = new ArrayList<>();
            SolidMesh.Mesh mesh = null;
            for (final UUID sid : m.sources()) {
                final Solid src = byId.get(sid);
                if (src == null || src.shape instanceof Shape.Plane
                        || src.shape instanceof Shape.Fillet || src.shape instanceof Shape.Mirror
                        || src.shape instanceof Shape.Cut) {
                    continue;
                }
                final Sketch srcSketch = src.resolve(sketches);
                if (srcSketch == null) {
                    continue;
                }
                for (final double[] b : src.boxes(srcSketch)) {
                    boxes.add(reflectBox(b, o, n));
                }
                for (final Vector3d[] e : src.edges(srcSketch)) {
                    edges.add(new Vector3d[] {reflectPoint(e[0], o, n), reflectPoint(e[1], o, n)});
                }
                final SolidMesh.Mesh sm = src.mesh(srcSketch);
                if (sm != null && !sm.isEmpty()) {
                    mesh = mesh == null ? reflectMesh(sm, o, n) : concatMesh(mesh,
                            reflectMesh(sm, o, n));
                }
            }
            if (boxes.isEmpty() || edges.isEmpty()) {
                continue;
            }
            // ★ 圆角也作用到镜像产物上 ✓
            mesh = cutWithFillets(mesh, filletPolyByBody.get(mirror.body()));
            out.add(new Placed(mirror, boxes, edges, mesh,
                    new Vector3d(m.nx(), m.ny(), m.nz())));
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
            final List<double[]> boxes = new ArrayList<>();
            final List<Vector3d[]> edges = new ArrayList<>();
            SolidMesh.Mesh mesh = null;
            for (final UUID sid : pt.sources()) {
                final Solid src = byId.get(sid);
                if (src == null || src.shape instanceof Shape.Plane
                        || src.shape instanceof Shape.Fillet || src.shape instanceof Shape.Mirror
                        || src.shape instanceof Shape.Pattern || src.shape instanceof Shape.Cut) {
                    continue;
                }
                final Sketch srcSketch = src.resolve(sketches);
                if (srcSketch == null) {
                    continue;
                }
                for (final double[] off : offsets) {
                    if (Math.abs(off[0]) + Math.abs(off[1]) + Math.abs(off[2]) < 1.0e-9) {
                        continue; // 原位置由源特征自己画 ✓
                    }
                    for (final double[] b : src.boxes(srcSketch)) {
                        boxes.add(translateBox(b, off));
                    }
                    for (final Vector3d[] e : src.edges(srcSketch)) {
                        edges.add(new Vector3d[] {
                            new Vector3d(e[0]).add(off[0], off[1], off[2]),
                            new Vector3d(e[1]).add(off[0], off[1], off[2])});
                    }
                    final SolidMesh.Mesh sm = src.mesh(srcSketch);
                    if (sm != null && !sm.isEmpty()) {
                        mesh = mesh == null ? translateMesh(sm, off)
                                : concatMesh(mesh, translateMesh(sm, off));
                    }
                }
            }
            if (boxes.isEmpty() || edges.isEmpty()) {
                continue;
            }
            // ★ 圆角也作用到阵列产物上 ✓
            mesh = cutWithFillets(mesh, filletPolyByBody.get(pat.body()));
            out.add(new Placed(pat, boxes, edges, mesh, null));
        }
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
        final List<Csg.Polygon> cut = Csg.subtract(base, ensureOutward(filletPolys));
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
        double sum = 0.0;
        for (final Csg.Polygon poly : polys) {
            final List<Csg.Vertex> vs = poly.vertices;
            for (int i = 1; i + 1 < vs.size(); i++) {
                final Csg.V3 p0 = vs.get(0).pos();
                final Csg.V3 p1 = vs.get(i).pos();
                final Csg.V3 p2 = vs.get(i + 1).pos();
                sum += p0.x() * (p1.y() * p2.z() - p1.z() * p2.y())
                        - p0.y() * (p1.x() * p2.z() - p1.z() * p2.x())
                        + p0.z() * (p1.x() * p2.y() - p1.y() * p2.x());
            }
        }
        if (sum >= 0.0) {
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

    /** 反射一个网格 ✓（位置反射、法线取反、三角形绕序翻转） */
    private static SolidMesh.Mesh reflectMesh(final SolidMesh.Mesh in, final double[] o,
                                              final double[] n) {
        final List<SolidMesh.Vertex> vs = new java.util.ArrayList<>(in.vertices().size());
        for (final SolidMesh.Vertex v : in.vertices()) {
            final Vector3d p = reflectPoint(v.position(), o, n);
            vs.add(new SolidMesh.Vertex(p,
                    new Vector3d(-v.normal().x, -v.normal().y, -v.normal().z), v.u(), v.v()));
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
