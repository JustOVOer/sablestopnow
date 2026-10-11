package com.ovo.sablestopnow.sketch;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 拉伸：把草图轮廓变成实体的几何。
 *
 * <h2>实体不存盒子</h2>
 * 实体只存「草图 id + 拉伸参数」，盒子**每次现算**。
 * 存派生数据会和草图漂移：玩家改了草图，实体还是旧盒子 ——
 * 那种不一致极难排查，而且渲染与碰撞会各说各话。
 *
 * <h2>为什么输出的是盒子</h2>
 * Sable 的物理碰撞只吃盒子（{@code VoxelColliderData} 只有 {@code addBox}），
 * 而我们的草图平面又都是轴对齐的（三基准面 + 方块面 → 法线必沿某坐标轴），
 * 所以「轮廓盒子分解 × 深度」得到的正好是**轴对齐的盒子**，碰撞与渲染共用同一份数据。
 *
 * <h2>四种深度模式</h2>
 * <ul>
 *   <li>{@code ONE_SIDED} —— 沿法线单侧拉 {@code depth1}：{@code [0, depth1]}</li>
 *   <li>{@code TWO_SIDED} —— 两侧各拉一段：{@code [-depth2, depth1]}</li>
 *   <li>{@code SYMMETRIC} —— 以草图平面为中心对称：{@code [-depth1/2, +depth1/2]}</li>
 *   <li>{@code MID_PLANE} —— 总厚 {@code depth1}，但整体平移到法线正侧：{@code [0, depth1]}</li>
 * </ul>
 * （{@code MID_PLANE} 与 {@code ONE_SIDED} 在数值上相同，保留它是为了将来接
 * "以中面为基准、向两侧等分"的语义时不必改存档格式。）
 *
 * <p>⚠ 只依赖 JOML，可用 {@code tools/SketchModelCheck.java} 验证。
 */
public final class Extrude {

    /** 拉伸方式。 */
    public enum Mode {
        /** 沿法线单侧。 */
        ONE_SIDED,
        /** 两侧不同深度。 */
        TWO_SIDED,
        /** 以草图平面为中心对称。 */
        SYMMETRIC,
        /** 中面为基准。 */
        MID_PLANE
    }

    /** 总厚上限（格）：挡住手滑输入一个会把世界撑爆的数。 */
    public static final double MAX_DEPTH = 64.0;

    /**
     * 拉伸参数。
     *
     * @param mode   拉伸方式
     * @param depth1 主深度（必须为正）
     * @param depth2 次深度（仅 {@code TWO_SIDED} 用；其余模式忽略）
     */
    public record Params(Mode mode, double depth1, double depth2) {

        public Params {
            if (!Double.isFinite(depth1) || depth1 <= 0.0) {
                throw new IllegalArgumentException("拉伸深度必须为正");
            }
            if (!Double.isFinite(depth2) || depth2 < 0.0) {
                throw new IllegalArgumentException("第二侧深度不能为负");
            }
            depth1 = Math.min(depth1, MAX_DEPTH);
            depth2 = Math.min(depth2, MAX_DEPTH);
        }

        public static Params oneSided(final double depth) {
            return new Params(Mode.ONE_SIDED, depth, 0.0);
        }

        public static Params symmetric(final double totalDepth) {
            return new Params(Mode.SYMMETRIC, totalDepth, 0.0);
        }

        public static Params twoSided(final double forward, final double backward) {
            return new Params(Mode.TWO_SIDED, forward, backward);
        }

        /** 沿法线的区间 {@code {d0, d1}}（d0 < d1）。 */
        public double[] depthRange() {
            return switch (mode) {
                case ONE_SIDED, MID_PLANE -> new double[] {0.0, depth1};
                case TWO_SIDED -> new double[] {-depth2, depth1};
                case SYMMETRIC -> new double[] {-depth1 * 0.5, depth1 * 0.5};
            };
        }
    }

    private Extrude() {
    }

    /**
     * 生成实体的局部盒子集合。
     *
     * @param plane        草图平面（决定 u/v/法线 如何映射到局部坐标轴）
     * @param profileBoxes {@link SketchProfiles#toBoxes} 的输出：每项 {@code {minU,minV,maxU,maxV}}
     * @param params       拉伸参数
     * @return 每个盒子 {@code {minX,minY,minZ,maxX,maxY,maxZ}}（子结构 plot 局部系）
     */
    public static List<double[]> localBoxes(final SketchPlane plane,
                                            final List<double[]> profileBoxes,
                                            final Params params) {
        final double[] range = params.depthRange();
        final Vector3d origin = plane.origin();
        final Vector3d u = plane.axisU();
        final Vector3d v = plane.axisV();
        final Vector3d n = plane.normal();

        final List<double[]> out = new ArrayList<>(profileBoxes.size());
        for (final double[] b : profileBoxes) {
            // 用 8 个角点求局部 AABB：不管平面法线朝哪根轴都对，
            // 不必为"u 是 X 还是 Z"写六种分支。
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double minZ = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            double maxZ = -Double.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                final double pu = (i & 1) == 0 ? b[0] : b[2];
                final double pv = (i & 2) == 0 ? b[1] : b[3];
                final double pd = (i & 4) == 0 ? range[0] : range[1];
                final double x = origin.x + pu * u.x + pv * v.x + pd * n.x;
                final double y = origin.y + pu * u.y + pv * v.y + pd * n.y;
                final double z = origin.z + pu * u.z + pv * v.z + pd * n.z;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
            out.add(new double[] {minX, minY, minZ, maxX, maxY, maxZ});
        }
        return out;
    }

    /**
     * 便利入口：直接从草图算出实体的盒子集合。
     *
     * <p>⚠ 必须按**区域**（外圈 + 它的孔）分解，不能按**轮廓**分解。
     * {@link SketchProfiles#findLoops} 把"外框里的一个圆"返回成**两个独立的轮廓**，
     * 逐个拉伸的话孔会被当成实心柱体填进去 —— 用户明确要求"孔也要精确碰撞"。
     *
     * <p>多个区域（比如两个分开的矩形）会得到多组盒子，这正是"一次拉伸出多个柱体"该有的行为。
     *
     * @return 盒子集合；草图上没有闭合轮廓时返回空表（UI 据此提示"轮廓不闭合"）
     */
    public static List<double[]> boxesOf(final Sketch sketch, final Params params) {
        // ⚠ 碰撞用**粗采样**：一个盒子 = 一个刚体 + 一条约束，
        // 48 段的圆就是 48 个刚体。12 段对碰撞足够，刚体数直接降到 1/4。
        // 渲染走 prismEdges/prismSideFaces（它们用 Sampling.RENDER）。
        return boxesOf(sketch, params, SketchProfiles.Sampling.COLLISION);
    }

    /**
     * 同上，但指定采样精度（测试要能显式对比两种精度）。
     *
     * @param sampling 圆/弧的折线段数，见 {@link SketchProfiles.Sampling}
     */
    public static List<double[]> boxesOf(final Sketch sketch, final Params params,
                                         final SketchProfiles.Sampling sampling) {
        final List<double[]> boxes = new ArrayList<>();
        for (final SketchProfiles.Region region
                : SketchProfiles.nest(SketchProfiles.findLoops(
                        sketch, SketchProfiles.DEFAULT_WELD_TOLERANCE, sampling))) {
            if (!extrudable(region)) {
                // 自交的轮廓会拉出自穿的实体，必须拒掉。跳过而不是抛异常 ——
                // 一张草图里可能有好几个区域，别让一个坏的影响其它。
                continue;
            }
            boxes.addAll(localBoxes(sketch.plane(), SketchProfiles.toBoxes(region), params));
        }
        return boxes;
    }

    /** 区域能不能拉伸：外圈和每个孔都不能自交（退化环已由 {@code nest} 滤掉）。 */
    public static boolean extrudable(final SketchProfiles.Region region) {
        if (SketchProfiles.selfIntersects(region.outer())) {
            return false;
        }
        for (final SketchProfiles.Profile hole : region.holes()) {
            if (SketchProfiles.selfIntersects(hole)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 棱柱的**侧面四边形**：每条环边拉出一个四边形，带朝外法线与 UV。
     *
     * <h2>法线公式对孔也成立</h2>
     * 统一取 {@code (q−p) × n}（n = 平面法线）。外圈按**逆时针**、孔按**顺时针**取向后，
     * 这个式子恰好两边都对：外圈给出背离材料的方向，孔给出指向孔内的方向 ——
     * 两者都是"背离材料"。所以不必分情况讨论。
     *
     * <h2>UV 按局部单位平铺</h2>
     * 沿边方向与沿深度方向**各按 plot 局部单位计**（本模组里 1 单位 = 1 格），
     * 于是贴图每格重复一次、相邻面之间连续 —— 看上去就是"方块表面"。
     * 换成"每面归一化"只需要改这一个方法。
     *
     * @return 每个面 4 个角（逆时针朝外）+ 单位法线 + 4 组 {@code {u,v}}
     */
    public static List<Quad> prismSideFaces(final Sketch sketch, final Params params) {
        final double[] range = params.depthRange();
        final double thickness = range[1] - range[0];
        final SketchPlane plane = sketch.plane();
        final List<Quad> out = new ArrayList<>();
        for (final SketchProfiles.Region region : SketchProfiles.nest(SketchProfiles.findLoops(sketch))) {
            if (!extrudable(region)) {
                continue;
            }
            // 外圈逆时针、孔顺时针 —— 法线公式依赖这个取向
            final List<List<double[]>> rings = new ArrayList<>();
            rings.add(oriented(region.outer().points(), true));
            for (final SketchProfiles.Profile hole : region.holes()) {
                rings.add(oriented(hole.points(), false));
            }
            for (final List<double[]> ring : rings) {
                final int n = ring.size();
                double run = 0.0; // 沿环累计的长度，用来让 UV 在环上连续
                for (int i = 0; i < n; i++) {
                    final double[] p = ring.get(i);
                    final double[] q = ring.get((i + 1) % n);
                    final double edge = Math.hypot(q[0] - p[0], q[1] - p[1]);
                    if (edge < 1.0e-9) {
                        continue;
                    }
                    // 平面内的朝外方向（u,v 分量）
                    final double tu = (q[0] - p[0]) / edge;
                    final double tv = (q[1] - p[1]) / edge;
                    // (q−p) × n 的平面分量：把切向转 −90° 得到朝外法线在平面内的方向
                    final double nu = tv;
                    final double nv = -tu;
                    final Vector3d normal = plane.axisU().mul(nu, new Vector3d())
                            .add(plane.axisV().mul(nv, new Vector3d()))
                            .normalize();
                    final Vector3d[] corners = {
                            onPlane(plane, p, range[0]), onPlane(plane, q, range[0]),
                            onPlane(plane, q, range[1]), onPlane(plane, p, range[1]),
                    };
                    final double[][] uv = {
                            {run, 0.0}, {run + edge, 0.0},
                            {run + edge, thickness}, {run, thickness},
                    };
                    out.add(new Quad(corners, normal, uv));
                    run += edge;
                }
            }
        }
        return out;
    }

    /** 一个待贴图的面：4 个角（逆时针朝外）、单位朝外法线、4 组 {@code {u,v}}。 */
    public record Quad(Vector3d[] corners, Vector3d normal, double[][] uv) {
    }

    // ==================================================================
    // 曲面：按"每条边一个盒子"分解（外侧面就是那条边）—— 斜盒子
    // ==================================================================

    /** 曲面临界：轮廓里出现圆/弧时走棱盒分解。 */
    public static boolean hasCurvature(final Sketch sketch) {
        for (final Sketch.Entity e : sketch.entities()) {
            if (sketch.isConstruction(e)) { continue; } // 辅助线**不参与轮廓/拉伸** ✓（SolidWorks 一致 ✓）
            if (e.kind() == Sketch.EntityKind.CIRCLE || e.kind() == Sketch.EntityKind.ARC) {
                return true;
            }
        }
        return false;
    }

    /**
     * 曲面壳的厚度比例（相对内切圆半径）。**故意取薄**。
     *
     * <h2>为什么必须薄</h2>
     * 物理那边**没有碰撞过滤 API**（`BoxPhysicsObject` / `PhysicsPipeline` 上都没有
     * collision group / filter / mask —— 查过 javap 了）。所以我们的盒子**各自是独立刚体、
     * 又互相不能相对运动**（都被固定约束焊在子结构上）。一旦两个盒子互相穿透，
     * 接触求解器就会和固定约束**互相打架** —— 表现正是用户报的
     * **"强烈阻滞感 + 摇晃"**。
     *
     * <p>扫描线盒是**划分**（不重叠）所以以前不显现；**棱盒是壳、天然互相重叠**。
     * 壳做薄之后：外表面照样是同一圈边（相邻盒在共享顶点处相接，外壳仍然闭合），
     * 但互相穿透的体积按厚度的平方下降。
     *
     * <p>薄壳内部是空的 —— 无所谓：外壳闭合，外面的东西进不去，
     * 而碰撞唯一在乎的就是从外面碰过来。质量另算（见 {@code Solid#volume}）。
     */
    public static final double SHELL_DEPTH_SCALE = 0.35;

    /**
     * 碰撞盒相对视觉几何的**内缩比例**。
     *
     * <h2>为什么必须内缩</h2>
     * 回归实证（用户 2026-09）：棱盒把外侧面**正好贴在那条边上**之后，
     * 结构变成**"自己悬浮起来、手杖拉不动"**。
     * 原因是**盒子和子结构自己的方块体素碰撞体在边界上抢空间** ——
     * 盒子被固定约束焊死、动不了，接触求解器只能把力气撒在子结构上，
     * 于是整个结构被顶起来、同时被约束拽住（拉不动）。
     *
     * <p>碰撞代理**略微小于**视觉几何是标准做法：
     * 宁可"边缘差一点点碰不到"，也不要"碰撞体捅出去顶着别人"。
     * 前者只是手感轻微偏软，后者是**整个结构行为失控**。
     *
     * <p>取值 0.92：外侧面与边界各留 4% 的余量，够避开共面接触，
     * 又小到肉眼看不出碰撞比模型瘦。
     */
    public static final double BOX_SHRINK = 0.92;

    /**
     * **曲面的碰撞分解**：每条边一个盒子，盒子的一个侧面**就是那条边本身**。
     *
     * <h2>为什么曲面不能用扫描线</h2>
     * 轴对齐扫描线分解出来的是**楼梯**（一层层轴对齐的台阶）。
     * 圆柱滚动时接触面就是这些台阶 —— 每滚过一级都是一次撞击，
     * 表现就是用户报的**"大圆柱滚动的顿挫感太强"**。
     * 而棱盒分解的外表面是**一圈平面**（多边形的边），滚动时是连续过渡。
     *
     * <p>代价：盒子是"壳"不是实心 —— 内部盖不满。但外壳是**闭合且精确**的，
     * 所以从外面碰过来跟实心没区别，而这也正好是碰撞唯一在乎的方向。
     *
     * <p>盒子数与扫描线相当（圆 12 段就是 12 个盒子），**收益是表面质量不是数量**：
     * 扫描线是楼梯、棱盒是一圈平面。
     *
     * @param depthScale 壳向内延伸的比例（1 = 伸到内切圆）。太小会留缝，太大相邻盒会互相穿出。
     */
    public static List<com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox> chordBoxes(
            final Sketch sketch, final Params params, final double depthScale) {
        final List<com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox> out = new ArrayList<>();
        final SketchPlane plane = sketch.plane();
        final double[] range = params.depthRange();
        final double thickness = range[1] - range[0];
        final double midDepth = (range[0] + range[1]) * 0.5;
        if (thickness <= 1.0e-9) {
            return out;
        }
        final Vector3d normal = plane.normal();
        final double scale = Math.max(0.05, Math.min(depthScale, 1.5));

        // ⚠ **必须用碰撞精度，不能用默认（渲染精度）**。
        // 默认是 `Sampling.RENDER` = 48 段 —— 那会给 r=4 的圆生成 48 个盒子
        //（一个盒子 = 一个刚体 + 一条约束），而碰撞只需要 16 个。
        // 大圆柱时更夸张（r=50 → 56 个），这正是刚体爆炸与摇晃的推手。
        for (final SketchProfiles.Region region
                : SketchProfiles.nest(SketchProfiles.findLoops(
                        sketch, SketchProfiles.DEFAULT_WELD_TOLERANCE,
                        SketchProfiles.Sampling.COLLISION))) {
            if (!extrudable(region)) {
                continue;
            }
            // 外圈：壳向内（朝重心）延伸；孔：壳向外（背离孔心）延伸
            emitChordRing(out, region.outer().points(), true, plane, normal,
                    range, thickness, midDepth, scale);
            for (final SketchProfiles.Profile hole : region.holes()) {
                emitChordRing(out, hole.points(), false, plane, normal,
                        range, thickness, midDepth, scale);
            }
        }
        return out;
    }

    /**
     * 一个环的棱盒。
     *
     * @param outer true = 外圈（壳朝环内延伸）；false = 孔（壳朝孔外延伸）
     */
    private static void emitChordRing(
            final List<com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox> out,
            final List<double[]> ring, final boolean outer, final SketchPlane plane,
            final Vector3d normal, final double[] range, final double thickness,
            final double midDepth, final double depthScale) {
        final int n = ring.size();
        if (n < 3) {
            return;
        }
        // 环心（用来定"向内"是哪一边）
        double cu = 0.0;
        double cv = 0.0;
        for (final double[] p : ring) {
            cu += p[0];
            cv += p[1];
        }
        cu /= n;
        cv /= n;
        // 内切深度：到各边的最小距离。用它当壳厚度的基准 ——
        // 取大了相邻的盒子会在中心互相穿出，取小了会在边上留缝。
        double inradius = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            final double[] p = ring.get(i);
            final double[] q = ring.get((i + 1) % n);
            inradius = Math.min(inradius, pointToSegment(cu, cv, p, q));
        }
        if (!Double.isFinite(inradius) || inradius <= 1.0e-9) {
            inradius = 0.5;
        }
        final double depth = inradius * depthScale;

        for (int i = 0; i < n; i++) {
            final double[] p = ring.get(i);
            final double[] q = ring.get((i + 1) % n);
            final double[] r = ring.get((i + 2) % n);
            final double edge = Math.hypot(q[0] - p[0], q[1] - p[1]);
            if (edge < 1.0e-9) {
                continue;
            }
            final double edgeNext = Math.hypot(r[0] - q[0], r[1] - q[1]);
            // ⚠ **不互相穿透的深度上限**。
            // 相邻两个棱盒在共享顶点处相接，各自的"内边"垂直于自己的边。
            // 深度超过 (min(两邻边)/2)·tan(转角/2) 之后，它们就会在那附近**互相穿透**。
            // 实测（K=16、r=4）：上限约 0.155，而按内切圆 0.35 算出来是 1.37 —— **深了 9 倍**，
            // 于是每一对相邻盒都穿透。而盒子之间的接触**关不掉**
            //（`setContactsEnabled` 只管关节两端；`createBox` 没有碰撞组参数）。
            // 所以唯一的办法就是**几何上别穿透**。
            final double turn = interiorTurn(p, q, r);
            final double limit = 0.9 * Math.min(edge, edgeNext) * 0.5 * Math.tan(turn * 0.5);
            final double boxDepth = Math.max(1.0e-3, Math.min(depth, limit));
            // 沿边的单位向量
            final double tu = (q[0] - p[0]) / edge;
            final double tv = (q[1] - p[1]) / edge;
            // 边的法线（平面内），指离环心的一侧
            double nu = tv;
            double nv = -tu;
            final double midU = (p[0] + q[0]) * 0.5;
            final double midV = (p[1] + q[1]) * 0.5;
            if ((midU - cu) * nu + (midV - cv) * nv < 0.0) {
                nu = -nu;
                nv = -nv;
            }
            // 外圈：壳从边**朝环内**延伸 → 方向取 −法线；孔：朝环外 → 取 +法线。
            // ⚠ 判据用"环心"而不是"我把环当外圈"，这样孔也自然对。
            final double sign = outer ? -1.0 : 1.0;
            // 盒子中心：边的中点沿延伸方向推 boxDepth/2
            final double cenU = midU + sign * nu * boxDepth * 0.5;
            final double cenV = midV + sign * nv * boxDepth * 0.5;
            final Vector3d centre = onPlane(plane, new double[] {cenU, cenV}, midDepth);
            // 局部三轴：x→沿边、y→延伸方向（平面内）、z→平面法线（拉伸方向）
            final Vector3d ex = plane.axisU().mul(tu, new Vector3d())
                    .add(plane.axisV().mul(tv, new Vector3d())).normalize();
            final Vector3d ey = plane.axisU().mul(sign * nu, new Vector3d())
                    .add(plane.axisV().mul(sign * nv, new Vector3d())).normalize();
            final Vector3d ez = new Vector3d(ex).cross(ey, new Vector3d()).normalize();
            final org.joml.Matrix3d frame = new org.joml.Matrix3d()
                    .setColumn(0, ex).setColumn(1, ey).setColumn(2, ez);
            out.add(new com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox(centre,
                    new Vector3d(edge * 0.5, boxDepth * 0.5, thickness * 0.5),
                    new org.joml.Quaterniond().setFromNormalized(frame)));
        }
    }

    /** 顶点 q 处的**转角**（两条相邻边方向之间的夹角，弧度）。直线共线时返回 0。 */
    private static double interiorTurn(final double[] p, final double[] q, final double[] r) {
        final double a1 = Math.atan2(q[1] - p[1], q[0] - p[0]);
        final double a2 = Math.atan2(r[1] - q[1], r[0] - q[0]);
        double turn = Math.abs(a2 - a1);
        while (turn > Math.PI) {
            turn = Math.abs(turn - 2.0 * Math.PI);
        }
        return turn;
    }

    /** 点到线段的距离（平面内）。 */
    private static double pointToSegment(final double cu, final double cv,
                                         final double[] p, final double[] q) {
        final double dx = q[0] - p[0];
        final double dy = q[1] - p[1];
        final double len2 = dx * dx + dy * dy;
        if (len2 < 1.0e-18) {
            return Math.hypot(cu - p[0], cv - p[1]);
        }
        double t = ((cu - p[0]) * dx + (cv - p[1]) * dy) / len2;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(cu - (p[0] + t * dx), cv - (p[1] + t * dy));
    }

    /** 统一环的走向（{@code ccw=true} 要逆时针）。 */
    private static List<double[]> oriented(final List<double[]> ring, final boolean ccw) {
        final List<double[]> out = new ArrayList<>(ring);
        if ((SketchProfiles.signedArea(out) > 0.0) != ccw) {
            java.util.Collections.reverse(out);
        }
        return out;
    }

    /**
     * 实体的**棱柱线框**：从草图轮廓直接生成，**不走盒子分解**。
     *
     * <h2>为什么渲染必须用这个而不是 {@link #boxesOf}</h2>
     * 盒子是给**碰撞**用的分解结果。拿它去渲染，形状就不对了 ——
     * 一个圆会被扫描线切成 48 个盒子，看起来是"一堆立方体拼出来的"，
     * 而不是"一个圆柱"（用户实测反馈）。盒子分解对矩形恰好只出一个盒子，
     * 所以这个 bug 只在圆/斜边/凹轮廓上暴露。
     *
     * <p>生成的是：每条环（外圈 + 每个孔）在两侧深度上各画一圈，再加每个顶点的竖棱。
     *
     * @return 每项是两个端点的 {@code {a, b}}（plot 局部系）；没有可拉伸区域时返回空表
     */
    public static List<Vector3d[]> prismEdges(final Sketch sketch, final Params params) {
        final double[] range = params.depthRange();
        final SketchPlane plane = sketch.plane();
        final List<Vector3d[]> out = new ArrayList<>();
        for (final SketchProfiles.Region region : SketchProfiles.nest(SketchProfiles.findLoops(sketch))) {
            if (!extrudable(region)) {
                continue;
            }
            final List<List<double[]>> rings = new ArrayList<>();
            rings.add(region.outer().points());
            for (final SketchProfiles.Profile hole : region.holes()) {
                rings.add(hole.points());
            }
            for (final List<double[]> ring : rings) {
                final int n = ring.size();
                for (int i = 0; i < n; i++) {
                    final double[] p = ring.get(i);
                    final double[] q = ring.get((i + 1) % n);
                    // 底面一圈 / 顶面一圈
                    out.add(new Vector3d[] {onPlane(plane, p, range[0]), onPlane(plane, q, range[0])});
                    out.add(new Vector3d[] {onPlane(plane, p, range[1]), onPlane(plane, q, range[1])});
                    // 竖棱（孔环也要，否则孔看起来是个没有壁的洞）
                    out.add(new Vector3d[] {onPlane(plane, p, range[0]), onPlane(plane, p, range[1])});
                }
            }
        }
        return out;
    }

    /** 平面内的 {@code (u,v)} 沿法线偏移 {@code d} 之后的 plot 局部坐标。 */
    public static Vector3d onPlane(final SketchPlane plane, final double[] uv, final double d) {
        final Vector3d local = plane.toLocal(uv[0], uv[1]);
        // ⚠ normal() 返回的是平面自己持有的向量，**不能原地改**（mul 到新对象里）
        return local.add(plane.normal().mul(d, new Vector3d()));
    }

    /** 盒子集合的总体包围盒 {@code {minX,minY,minZ,maxX,maxY,maxZ}}；空表返回 null。 */
    public static double[] bounds(final List<double[]> boxes) {
        if (boxes.isEmpty()) {
            return null;
        }
        final double[] out = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (final double[] b : boxes) {
            for (int i = 0; i < 3; i++) {
                out[i] = Math.min(out[i], b[i]);
                out[i + 3] = Math.max(out[i + 3], b[i + 3]);
            }
        }
        return out;
    }
}
