package com.ovo.sablestopnow.sketch;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 实体盒子的**碰撞查询**（纯逻辑，不含任何 Minecraft 类型）。
 *
 * <h2>为什么需要"裁到格子"</h2>
 * Sable 的物理碰撞是按**方块格**组织的（{@code VoxelShape} 用的是方块局部 0..1 坐标），
 * 而我们的实体是任意位置、可以横跨很多格的自由几何。两者之间必须有一个转换：
 * 「实体在某格内的那一部分，表达成该格的局部坐标」= {@link #clipToCell}。
 *
 * <p>这一层对**两条候选碰撞路线都成立**：
 * <ul>
 *   <li>放代理方块：每个格子那个方块要贡献的就是 {@code clipToCell} 的结果；</li>
 *   <li>mixin 进 rapier 烘焙：也要先知道哪些格子被覆盖（{@link #occupiedCells}）。</li>
 * </ul>
 * 所以先把这层做扎实，路线选了之后只剩接线。
 *
 * <p>⚠ 只依赖 JDK，可用 {@code tools/SketchModelCheck.java} 验证。
 */
public final class SolidCollision {

    private SolidCollision() {
    }

    /**
     * 实体覆盖到的所有方块格。
     *
     * <p>返回打包成 {@code long} 的坐标集合而不是 {@code BlockPos} —— 这一层不该依赖 MC，
     * 而且一格一个对象在实体横跨上千格时开销可观。
     */
    public static Set<Long> occupiedCells(final List<double[]> boxes) {
        final Set<Long> out = new LinkedHashSet<>();
        for (final double[] b : boxes) {
            if (!valid(b)) {
                continue;
            }
            final int[] xs = cellRange(b[0], b[3]);
            final int[] ys = cellRange(b[1], b[4]);
            final int[] zs = cellRange(b[2], b[5]);
            for (int x = xs[0]; x <= xs[1]; x++) {
                for (int y = ys[0]; y <= ys[1]; y++) {
                    for (int z = zs[0]; z <= zs[1]; z++) {
                        out.add(pack(x, y, z));
                    }
                }
            }
        }
        return out;
    }

    /**
     * 实体在某一格内的部分，裁切并换算成**该格的局部坐标**（0..1）。
     *
     * <p>这就是喂给 {@code VoxelShape.create(...)} 那种接口的形态。
     *
     * @return 每个盒子仍是 {@code {minX,minY,minZ,maxX,maxY,maxZ}}，但值域是 [0,1]；不在该格内则为空表
     */
    public static List<double[]> clipToCell(final List<double[]> boxes,
                                            final int bx, final int by, final int bz) {
        final java.util.ArrayList<double[]> out = new java.util.ArrayList<>();
        for (final double[] b : boxes) {
            if (!valid(b)) {
                continue;
            }
            final double minX = Math.max(b[0], bx);
            final double minY = Math.max(b[1], by);
            final double minZ = Math.max(b[2], bz);
            final double maxX = Math.min(b[3], bx + 1.0);
            final double maxY = Math.min(b[4], by + 1.0);
            final double maxZ = Math.min(b[5], bz + 1.0);
            // 用 epsilon 而不是 0：贴着一个面但不进入该格的盒子不该在这一格留下零厚度的片
            if (maxX - minX < 1.0e-9 || maxY - minY < 1.0e-9 || maxZ - minZ < 1.0e-9) {
                continue;
            }
            out.add(new double[] {
                    minX - bx, minY - by, minZ - bz,
                    maxX - bx, maxY - by, maxZ - bz,
            });
        }
        return out;
    }

    /** 点是否在实体内部（边界算内部）。用于放置判定/选中判定。 */
    public static boolean contains(final List<double[]> boxes,
                                   final double x, final double y, final double z) {
        for (final double[] b : boxes) {
            if (!valid(b)) {
                continue;
            }
            if (x >= b[0] && x <= b[3] && y >= b[1] && y <= b[4] && z >= b[2] && z <= b[5]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 两批盒子是否相交（严格相交，只贴着不算）。
     *
     * <p>"切割台体改变方块碰撞箱"要用它：先问实体和这个方块的碰撞箱有没有真交集，
     * 没有就完全不用动那个方块。
     */
    public static boolean intersects(final List<double[]> a, final List<double[]> b) {
        for (final double[] p : a) {
            if (!valid(p)) {
                continue;
            }
            for (final double[] q : b) {
                if (!valid(q)) {
                    continue;
                }
                if (p[0] < q[3] - 1.0e-9 && q[0] < p[3] - 1.0e-9
                        && p[1] < q[4] - 1.0e-9 && q[1] < p[4] - 1.0e-9
                        && p[2] < q[5] - 1.0e-9 && q[2] < p[5] - 1.0e-9) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 盒子集合的总体积（用来验证裁切不丢体积）。 */
    public static double volume(final List<double[]> boxes) {
        double sum = 0.0;
        for (final double[] b : boxes) {
            if (valid(b)) {
                sum += (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]);
            }
        }
        return sum;
    }

    /**
     * 把盒子集合转成**物理引擎要的形态**：每个盒子一个 (中心, 半长)。
     *
     * <p>Sable 的 {@code BoxPhysicsObject(Pose3dc pose, Vector3dc halfExtents, double mass)}
     * 用的是"中心 + 半长"，而我们的盒子是"min/max" —— 这一层就是那个转换。
     * 放在纯逻辑里（不 import 任何 MC/Sable 类）是为了能离线断言，
     * 也让 MC 那一层只剩"把数组塞进 Vector3dc"这点活。
     *
     * @return 每项 {@code {cx, cy, cz, hx, hy, hz}}（plot 局部系）
     */
    public static List<double[]> boxSpecs(final List<double[]> boxes) {
        final java.util.ArrayList<double[]> out = new java.util.ArrayList<>(boxes.size());
        for (final double[] b : boxes) {
            if (!valid(b)) {
                continue; // 零厚度/反向的盒子喂给物理引擎只会添乱
            }
            out.add(new double[] {
                    (b[0] + b[3]) * 0.5, (b[1] + b[4]) * 0.5, (b[2] + b[5]) * 0.5,
                    (b[3] - b[0]) * 0.5, (b[4] - b[1]) * 0.5, (b[5] - b[2]) * 0.5,
            });
        }
        return out;
    }

    /**
     * 盒子的总质量（按密度 × 体积）。
     *
     * <p>⚠ 物理引擎的刚体**必须有质量**，而质量会影响转动惯量与碰撞响应。
     * 如果每个碰撞盒质量都算满，同一块材料会被重复计入（盒子是重叠/相邻的分解，
     * 不是独立的物体）。所以调用方应当把**整块实体**的质量按体积**分摊**给各个盒子 ——
     * {@link #massPerBox} 就是干这个的。
     */
    public static double massPerBox(final double[] spec, final double totalMass,
                                    final double totalVolume) {
        if (totalVolume <= 1.0e-12 || spec == null || spec.length < 6) {
            return 0.0;
        }
        final double boxVolume = 8.0 * spec[3] * spec[4] * spec[5]; // 半长 → 体积
        return totalMass * (boxVolume / totalVolume);
    }

    // ==================================================================
    // 有朝向的盒子（OBB）
    // ==================================================================

    /**
     * 一个可以**任意朝向**的盒子。
     *
     * <h2>为什么需要它</h2>
     * Sable 的 {@code BoxPhysicsObject} 收的是完整的 {@code Pose3dc}，
     * 里面**带朝向** —— 所以能喂给物理的不只是轴对齐盒子。
     * 这件事对**旋转拉伸**是必需的：回转体的表面是斜的，
     * 轴对齐盒子只能一层层去套（误差大、盒子多），而按角度分楔、每楔一个旋转盒子就贴得住。
     *
     * <p>⚠ 轴对齐的那些轮廓（矩形、L 形、圆）**仍然走轴对齐路径**（朝向为单位四元数）：
     * 那条路是精确分解，没有任何理由给它加朝向。
     *
     * @param centre      盒子中心（plot 局部系）
     * @param halfExtents 半长（局部轴上的，未旋转）
     * @param rotation    相对 plot 局部系的朝向
     */
    public record OrientedBox(Vector3d centre, Vector3d halfExtents, Quaterniond rotation) {

        public OrientedBox {
            centre = new Vector3d(centre);
            halfExtents = new Vector3d(halfExtents);
            rotation = new Quaterniond(rotation);
        }

        /** 体积（旋转不改变体积）。 */
        public double volume() {
            return 8.0 * halfExtents.x * halfExtents.y * halfExtents.z;
        }

        /** 是不是轴对齐（朝向为单位四元数）。 */
        public boolean axisAligned() {
            return Math.abs(rotation.w) >= 1.0 - 1.0e-9;
        }

        /**
         * 八个角点在 **plot 局部坐标系**里的位置。
         *
         * <h2>为什么这个必须放在生产代码里</h2>
         * "角点在哪"是**一个定义**，但凡是需要它的地方（内切检查、描边、命中、装配）
         * 都会各自手写一遍 —— 而手写几何数学是我这一路**最高频的 bug 来源**：
         * 旋转方向用反、到轴距离硬编码坐标分量、角点边界漏 `√2`，全是因为各写各的。
         * 所以这里是**唯一事实来源**，测试与所有调用方一律用它。
         *
         * <p>⚠ 旋转方向：{@code rotation} 是**局部 → 世界的旋转**（与 Sable 的位姿约定一致：
         * {@code boxPose = subPose × rotation}），所以角点 = 中心 + {@code rotation · (±half)}。
         * 这一点必须和 {@code SolidCollisionService} 里组装位姿的写法一致，否则两边会差一个逆。
         *
         * @return 长度 8 的数组，顺序为 (x,y,z) 各取 ±half 的全组合
         */
        public Vector3d[] corners() {
            final Vector3d[] out = new Vector3d[8];
            int n = 0;
            for (int i = -1; i <= 1; i += 2) {
                for (int j = -1; j <= 1; j += 2) {
                    for (int k = -1; k <= 1; k += 2) {
                        out[n++] = rotation.transform(new Vector3d(
                                i * halfExtents.x, j * halfExtents.y, k * halfExtents.z))
                                .add(centre);
                    }
                }
            }
            return out;
        }

        /**
         * 这个盒子的**径向/切向/轴向**三轴在 plot 局部系里的方向。
         * 约定：x→轴、y→径向、z→切向（分楔构造时就是这么拼 frame 的）。
         */
        public Vector3d axisVector() {
            return rotation.transform(new Vector3d(1, 0, 0));
        }

        /** 径向方向（见 {@link #axisVector()} 的约定）。 */
        public Vector3d radialVector() {
            return rotation.transform(new Vector3d(0, 1, 0));
        }

        /** 切向方向（见 {@link #axisVector()} 的约定）。 */
        public Vector3d tangentialVector() {
            return rotation.transform(new Vector3d(0, 0, 1));
        }
    }

    /**
     * 把轴对齐盒子规格转成 OBB（朝向为单位四元数）。
     *
     * <p>轴对齐轮廓走这条路：结果与原来完全一致，只是多带了一个"未旋转"的朝向，
     * 好让下游（物理接线）只有一条代码路径。
     */
    public static List<OrientedBox> orientedBoxSpecs(final List<double[]> boxes) {
        final List<OrientedBox> out = new ArrayList<>();
        for (final double[] spec : boxSpecs(boxes)) {
            out.add(new OrientedBox(
                    new Vector3d(spec[0], spec[1], spec[2]),
                    new Vector3d(spec[3], spec[4], spec[5]),
                    new Quaterniond()));
        }
        return out;
    }

    /**
     * 把一个盒子绕 {@code pivot} 转 {@code rotation}，得到 OBB。
     *
     * <p>用途：几何先在"平面/局部坐标系"里按轴对齐分解（那里分解是精确的），
     * 最后一步再把整组盒子转到实际朝向。这样分解算法完全不用懂旋转。
     *
     * <p>⚠ 参数形态与全类其余入口**一致**，都是 {@code {minX,minY,minZ,maxX,maxY,maxZ}}。
     * 之前这里单独收"中心+半长"，结果同一个 {@code double[6]} 在本类里有两种含义，
     * 调用方（包括我自己的测试）连错三次 —— 现在统一成 min/max。
     *
     * @param box   {@code {minX,minY,minZ,maxX,maxY,maxZ}}
     * @param pivot 旋转中心
     */
    public static OrientedBox rotate(final double[] box, final Quaterniond rotation,
                                     final Vector3d pivot) {
        if (box == null || box.length < 6 || !valid(box)) {
            throw new IllegalArgumentException("盒子必须是 {min, max} 且 max > min");
        }
        final List<double[]> specs = boxSpecs(List.of(box));
        final double[] spec = specs.get(0); // {cx,cy,cz,hx,hy,hz}
        final Vector3d centre = new Vector3d(spec[0], spec[1], spec[2]);
        final Vector3d half = new Vector3d(spec[3], spec[4], spec[5]);
        // 中心要**绕 pivot 转**：先平移到 pivot 的局部系，转完再平移回去。
        // 只转朝向而忘了转中心，是这类变换最常见的错 —— 盒子会待在原地但朝向变了。
        final Vector3d local = centre.sub(pivot, new Vector3d());
        final Vector3d moved = rotation.transform(local, new Vector3d()).add(pivot);
        return new OrientedBox(moved, half, new Quaterniond(rotation));
    }

    /** 一组轴对齐盒子整体绕 pivot 旋转（参数形态与全类一致：min/max）。 */
    public static List<OrientedBox> rotateAll(final List<double[]> boxes,
                                              final Quaterniond rotation,
                                              final Vector3d pivot) {
        final List<OrientedBox> out = new ArrayList<>();
        for (final double[] box : boxes) {
            if (valid(box)) {
                out.add(rotate(box, rotation, pivot));
            }
        }
        return out;
    }

    /** 一组 OBB 的总体积（用来验证旋转不丢体积）。 */
    public static double orientedVolume(final List<OrientedBox> boxes) {
        double sum = 0.0;
        for (final OrientedBox b : boxes) {
            sum += b.volume();
        }
        return sum;
    }

    /**
     * 两个 OBB 是否**相交**（分离轴定理）。
     *
     * <h2>为什么需要它</h2>
     * 物理那边**只能关掉"关节两端"的接触**（`setContactsEnabled`）。
     * 我们的盒子各自只和子结构成对，所以**盒子↔盒子**的接触关不掉
     *（`createBox` 没有碰撞组参数，源码确认过）。
     * 于是"盒子之间互相穿透"就成了剩下唯一会打架的来源 ——
     * 而这是个**纯几何**问题，可以离线量出来，不必靠猜。
     *
     * <p>轴取全 15 根：A 的 3 个面法线、B 的 3 个、以及 9 根边叉积。
     * 只取 6 根面法线的话，棱对棱的情形会**误报相交**，那会让这个度量失去意义。
     */
    public static boolean overlaps(final OrientedBox a, final OrientedBox b) {
        final Vector3d[] ax = axesOf(a);
        final Vector3d[] bx = axesOf(b);
        final Vector3d d = new Vector3d(b.centre()).sub(a.centre());
        // A 的三根轴 + B 的三根轴
        for (int i = 0; i < 3; i++) {
            if (separated(d, ax, bx, a.halfExtents(), b.halfExtents(), ax[i])) {
                return false;
            }
        }
        for (int i = 0; i < 3; i++) {
            if (separated(d, ax, bx, a.halfExtents(), b.halfExtents(), bx[i])) {
                return false;
            }
        }
        // 9 根边叉积（平行时叉积为零向量，跳过 —— 那两根轴已经查过了）
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                final Vector3d axis = new Vector3d(ax[i]).cross(bx[j]);
                if (axis.lengthSquared() > 1.0e-18
                        && separated(d, ax, bx, a.halfExtents(), b.halfExtents(), axis)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 盒子的三根局部轴（世界方向，已归一化）。 */
    private static Vector3d[] axesOf(final OrientedBox box) {
        final Vector3d[] out = new Vector3d[3];
        for (int i = 0; i < 3; i++) {
            final Vector3d v = new Vector3d(i == 0 ? 1 : 0, i == 1 ? 1 : 0, i == 2 ? 1 : 0);
            out[i] = box.rotation().transform(v).normalize();
        }
        return out;
    }

    /** 在给定轴上投影：是否分离。 */
    private static boolean separated(final Vector3d d, final Vector3d[] ax, final Vector3d[] bx,
                                     final Vector3d ha, final Vector3d hb, final Vector3d axis) {
        final double n = axis.length();
        if (n < 1.0e-12) {
            return false;
        }
        final double dist = Math.abs(d.dot(axis)) / n;
        double ra = 0.0;
        double rb = 0.0;
        for (int i = 0; i < 3; i++) {
            ra += ha.get(i) * Math.abs(ax[i].dot(axis)) / n;
            rb += hb.get(i) * Math.abs(bx[i].dot(axis)) / n;
        }
        // 留一点负容差：**刚好贴着**（共面、不重叠）不算相交。
        // 这一点很重要 —— 棱盒本来就是"相邻盒在共享顶点处相接"，贴面是设计意图，
        // 把它算成相交会让这个度量永远报警。
        return dist > ra + rb - 1.0e-6;
    }

    /**
     * 一组盒子里**互相穿透**的对数（含一点点重叠耐受）。
     *
     * <p>这是"盒子之间会不会打架"的度量。理想值 0；不为 0 就说明
     * 接触求解器有活干，而那活它干不了（盒子都被焊死了）→ 抖 + 阻滞。
     *
     * @param tolerance 认为"这只是贴着、不算穿透"的重叠深度（格）。
     *                  传 0 会把相邻贴面的盒子全算进来，那就没有参考价值了。
     */
    public static int penetratingPairs(final List<OrientedBox> boxes, final double tolerance) {
        int count = 0;
        for (int i = 0; i < boxes.size(); i++) {
            for (int j = i + 1; j < boxes.size(); j++) {
                if (overlaps(boxes.get(i), boxes.get(j))
                        && overlapDepth(boxes.get(i), boxes.get(j)) > tolerance) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 两个盒子在"最分离的那根轴"上的重叠深度（粗略度量，用来滤掉贴面）。 */
    private static double overlapDepth(final OrientedBox a, final OrientedBox b) {
        final Vector3d[] ax = axesOf(a);
        final Vector3d[] bx = axesOf(b);
        final Vector3d d = new Vector3d(b.centre()).sub(a.centre());
        double minOverlap = Double.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            minOverlap = Math.min(minOverlap, axisOverlap(d, ax, bx,
                    a.halfExtents(), b.halfExtents(), ax[i]));
        }
        for (int i = 0; i < 3; i++) {
            minOverlap = Math.min(minOverlap, axisOverlap(d, ax, bx,
                    a.halfExtents(), b.halfExtents(), bx[i]));
        }
        return minOverlap == Double.MAX_VALUE ? Double.MAX_VALUE : minOverlap;
    }

    private static double axisOverlap(final Vector3d d, final Vector3d[] ax, final Vector3d[] bx,
                                      final Vector3d ha, final Vector3d hb, final Vector3d axis) {
        final double n = axis.length();
        if (n < 1.0e-12) {
            return Double.MAX_VALUE;
        }
        final double dist = Math.abs(d.dot(axis)) / n;
        double ra = 0.0;
        double rb = 0.0;
        for (int i = 0; i < 3; i++) {
            ra += ha.get(i) * Math.abs(ax[i].dot(axis)) / n;
            rb += hb.get(i) * Math.abs(bx[i].dot(axis)) / n;
        }
        return ra + rb - dist; // >0 = 在这根轴上重叠
    }

    /**
     * 把一组 OBB 以其**各自中心**为基准整体缩小（半长乘 {@code scale}）。
     *
     * <h2>为什么碰撞体要小于视觉几何</h2>
     * 实体是画在**子结构自己的方块空间里**的，而方块**自己有体素碰撞体**。
     * 如果我们的盒子外侧面正好贴在同一处边界上，就会和方块碰撞体**共面接触**：
     * 盒子被固定约束焊死、动不了，接触求解器只能把力气撒在子结构上 ——
     * 后果是整个结构被顶起来、同时被约束拽住（实测：
     * **"自己悬浮起来、手杖无法拉动"**）。
     *
     * <p>碰撞代理略小于视觉是标准做法：宁可边缘差一点碰不到（手感略软），
     * 也不要碰撞体捅出去顶着别人（**整个结构行为失控**）。
     *
     * @param scale 0..1；{@code >= 1} 时原样返回（不复制）
     */
    // ==================================================================
    // 约束在创建时是否已被满足（事故回归护栏）
    // ==================================================================

    /** 盒子的**世界朝向**：子结构朝向 ∘ 盒子局部朝向（物理接线就是这么复合的）。 */
    public static Quaterniond composeOrientation(final Quaterniondc subRotation,
                                                 final Quaterniondc boxRotation) {
        return new Quaterniond(subRotation).mul(boxRotation);
    }

    /** 盒子的**世界位置**：子结构位置 + 子结构朝向作用在局部中心上。 */
    public static Vector3d composePosition(final Vector3dc subPosition,
                                           final Quaterniondc subRotation,
                                           final Vector3dc localCentre) {
        return new Quaterniond(subRotation).transform(new Vector3d(localCentre), new Vector3d())
                .add(subPosition);
    }

    /** 从两端刚体的世界朝向反解出**约束应当记录**的相对朝向：{@code 子朝向⁻¹ ∘ 盒子朝向}。 */
    public static Quaterniond relativeOrientation(final Quaterniondc subRotation,
                                                  final Quaterniondc boxWorldRotation) {
        return new Quaterniond(subRotation).conjugate().mul(boxWorldRotation);
    }

    /**
     * **创建时约束是否已被满足**（事故回归护栏）。
     *
     * <h2>为什么要有这个函数</h2>
     * 2026-09 出过一次严重事故：固定约束的相对朝向传了**单位四元数**，
     * 而棱盒（曲面用的 OBB）是斜的 → 约束在**创建的那一刻就被违反** →
     * 求解器猛拽 → 子结构被甩到极端坐标 → **服务端 TPS 归零**
     *（Sable 日志会打 {@code has an extreme Y coordinate}）。
     *
     * <p>轴对齐盒子的局部朝向恰好是单位四元数，所以"传单位四元数"**碰巧对** ——
     * 直到曲面路径才引爆。这个函数把"恰好对"变成"验过对"。
     *
     * @param constraintRotation 实际写进约束的相对朝向
     * @return 偏差角（度）；0 表示满足
     */
    public static double constraintViolationDegrees(final Quaterniondc subRotation,
                                                    final Quaterniondc boxRotation,
                                                    final Quaterniondc constraintRotation) {
        final Quaterniond boxWorld = composeOrientation(subRotation, boxRotation);
        final Quaterniond expected = relativeOrientation(subRotation, boxWorld);
        final Quaterniond actual = new Quaterniond(constraintRotation);
        // ⚠ 四元数 q 与 −q 是同一个旋转，所以必须按 |dot| 判 ——
        //   直接比分量会把"其实是同一个旋转"误判成 180° 偏差。
        double dot = Math.abs(expected.x * actual.x + expected.y * actual.y
                + expected.z * actual.z + expected.w * actual.w);
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(2.0 * Math.acos(dot));
    }

    // ==================================================================
    // 通用曲面→盒子：体素化 + 贪心合并
    // ==================================================================

    /**
     * 体素化的结果。
     *
     * @param boxes     合并后的盒子（plot 局部、轴对齐）
     * @param res       实际用的分辨率（最长轴最多几格）
     * @param cell      格子边长
     * @param coarsened 是否因为**超预算**而降过分辨率（诊断用）
     */
    public record VoxelResult(List<OrientedBox> boxes, int res, double cell, boolean coarsened) {
    }

    /**
     * **通用**地把一个闭合网格近似成一组盒子：体素化 + 贪心合并。
     *
     * <h2>为什么需要它</h2>
     * 分楔 OBB 只对**回转体**好，而且盒子数受"轮廓 × 角度"双重支配
     *（旋转一个圆能出几百个）；轴对齐扫描线只对**轴对齐轮廓**好。
     * 任意曲面（斜拉伸、回转体、以后别的成形方式）都需要一个**通用且可控**的近似 ——
     * 体素化 + 合并就是工程上那个答案（同类做法见 Unity 的 VoxelCollider：
     * 体素填充 + 可选贪心合并，专门为"动态刚体上稳定"设计）。
     *
     * <h2>做法</h2>
     * <ol>
     *   <li>按网格逐**列**投一条沿 z 的射线，收集与所有三角形的交点 z 并排序；
     *       按**奇偶规则**填充 → 得到"哪些格子在实体内"。
     *       ⚠ 每列只算一次，不是每格一次 —— 否则复杂度会翻一个量级；</li>
     *   <li>**贪心合并**：先沿 x 尽量长、再沿 y、再沿 z，把占用格并成尽量大的长方体。
     *       这一步是盒子数从"几百格"降到"几个到几十个"的关键；</li>
     *   <li>结果超过预算就把分辨率调粗重来 —— 于是**盒子数由预算决定**，
     *       而不是由形状碰巧决定。</li>
     * </ol>
     *
     * <p>⚠ **必然有不超过半格的过覆盖**：一格算不算"在实体内"是按**格心**判的，
     * 所以边界格可能探出去半个格子。这是体素化的固有代价，不是 bug；
     * 要严格不探出去就交给 {@link #shrink}（代价是整体略瘦）。
     *
     * <p>⚠ 只对**闭合**网格成立（奇偶规则）。拉伸/旋转生成的网格都是闭合的
     *（见 {@code SolidMesh.signedVolume} 的断言）。
     *
     * @param mesh       三角网格（plot 局部系）
     * @param targetCell 目标格边长（格）。**分辨率由它和网格真实包围盒决定** ——
     *                   ⚠ 不能用草图的包围盒来估：回转体的实际尺寸是
     *                   {@code 2×(离轴距离 + 半径)}，比草图里那个轮廓大好几倍，
     *                   按草图估会让格子大得又糙又外凸（实测过）。
     * @param maxBoxes   盒子数的**硬上限**（一个盒子 = 一个刚体 + 一条约束）。
     *                   超过就调粗分辨率 —— 这是保护物理开销的那道闸。
     * @param minRes     分辨率下限（太粗就不像了）
     * @param maxRes     分辨率上限（太细白烧 CPU）
     */
    public static VoxelResult voxelBoxes(final SolidMesh.Mesh mesh, final double targetCell,
                                         final int maxBoxes, final int minRes, final int maxRes) {
        final double[] b = SolidMesh.bounds(mesh);
        if (b == null || mesh.triangleCount() == 0) {
            return new VoxelResult(List.of(), 0, 0.0, false);
        }
        final double spanX = Math.max(b[3] - b[0], 1.0e-6);
        final double spanY = Math.max(b[4] - b[1], 1.0e-6);
        final double spanZ = Math.max(b[5] - b[2], 1.0e-6);
        final double span = Math.max(spanX, Math.max(spanY, spanZ));
        final int budget = Math.max(1, maxBoxes);
        // 分辨率从**网格真实包围盒**推出来
        final int want = (int) Math.round(span / Math.max(targetCell, 1.0e-3));
        int res = Math.max(Math.max(4, minRes), Math.min(Math.max(4, maxRes), want));
        VoxelResult last = null;
        while (true) {
            // ⚠ 每轴**精确整除**包围盒：用统一的"目标格边长"取整格数，再反推每轴真正的格边长。
            //   若直接用 span/res 当格边长，最后一格会**探出包围盒**之外 ——
            //   一个 6×4×2 的长方体在 0.375 的格子下会变成 16×11×6 格 = 55.7 而不是 48。
            //   那种"整块胖一圈"的误差在物理上就是"撞到空气"。
            final double target = span / res;
            final int nx = Math.max(1, (int) Math.round(spanX / target));
            final int ny = Math.max(1, (int) Math.round(spanY / target));
            final int nz = Math.max(1, (int) Math.round(spanZ / target));
            final double cx = spanX / nx;
            final double cy = spanY / ny;
            final double cz = spanZ / nz;
            final List<OrientedBox> merged = mergeCells(
                    occupancy(mesh, b, nx, ny, nz, cx, cy, cz), nx, ny, nz, b, cx, cy, cz);
            last = new VoxelResult(merged, res, Math.max(cx, Math.max(cy, cz)), res != want);
            if (merged.size() <= budget || res <= 4) {
                return last;
            }
            final int next = Math.max(4, (res * 3) / 4);
            if (next == res) {
                return last;
            }
            res = next;
        }
    }

    /**
     * 逐列/逐行射线 + 奇偶规则，算出哪些格子在实体内。
     *
     * <h2>为什么要三个轴都算一遍再投票</h2>
     * 单条射线在**擦过共面（面与射线平行）或共边**时会判错 —— 而"逐列 + 跨度填充"的
     * 结构意味着**一条射线判错会让整列都填错**。三个轴各算一遍、逐格**多数表决**，
     * 就能把这种个别方向的失误压掉。
     *
     * <p>⚠ 这个技巧来自一个 MIT 许可的 Unity 项目（`UnityNonConvexMeshColliders` 的
     * `VoxelCollider.IsPointInsideMeshByTrianglesLocal`：对 right/up/forward 三个方向各投一条射线再投票）。
     * 我们**没有搬它的代码**，只吸收了"多数表决"这个思路，并用了适合本项目的实现：
     * 它在**每个格**上投 3 条射线（O(格子数 × 三角形数)），
     * 我们是**每条线算一整列/行**再表决（O(格线数 × 三角形数)），复杂度低一个量级。
     * 见仓库根的 `THIRD-PARTY-NOTICES.md`。
     *
     * <p>⚠ 采样点要加一点**抖动**：格心正好落在三角形共享边/顶点上时，
     * "点在三角形投影内"的判定会摇摆，表现为某些列随机少填或多填一整段。
     * 两个互质的小数保证不会同时命中。
     */
    private static boolean[] occupancy(final SolidMesh.Mesh mesh, final double[] bounds,
                                       final int nx, final int ny, final int nz,
                                       final double cx, final double cy, final double cz) {
        final boolean[] a = fillAlong(mesh, bounds, nx, ny, nz, cx, cy, cz, 2);
        final boolean[] b = fillAlong(mesh, bounds, nx, ny, nz, cx, cy, cz, 0);
        final boolean[] c = fillAlong(mesh, bounds, nx, ny, nz, cx, cy, cz, 1);
        final boolean[] occ = new boolean[a.length];
        for (int i = 0; i < occ.length; i++) {
            // 多数表决：三个方向里至少两个说"在里面"才算
            final int votes = (a[i] ? 1 : 0) + (b[i] ? 1 : 0) + (c[i] ? 1 : 0);
            occ[i] = votes >= 2;
        }
        return occ;
    }

    /**
     * 沿一个轴做"逐线射线 + 奇偶填充"，返回占用表。
     *
     * @param axis 0 = 沿 x 投（投影到 YZ）、1 = 沿 y 投（投影到 XZ）、2 = 沿 z 投（投影到 XY）
     */
    private static boolean[] fillAlong(final SolidMesh.Mesh mesh, final double[] bounds,
                                       final int nx, final int ny, final int nz,
                                       final double cx, final double cy, final double cz,
                                       final int axis) {
        final boolean[] occ = new boolean[nx * ny * nz];
        final List<Double> hits = new ArrayList<>();
        final List<SolidMesh.Vertex> verts = mesh.vertices();
        final int[] idx = mesh.indices();
        // 每个轴：外层两级是"另外两个轴"的格号，内层是射线方向的格号
        final int n0 = axis == 0 ? ny : nx;
        final int n1 = axis == 2 ? ny : nz;
        final int nRay = axis == 0 ? nx : (axis == 1 ? ny : nz);
        final double d0 = axis == 0 ? cy : cx;
        final double d1 = axis == 2 ? cy : cz;
        final double dRay = axis == 0 ? cx : (axis == 1 ? cy : cz);
        for (int i = 0; i < n0; i++) {
            for (int j = 0; j < n1; j++) {
                // 采样点（带抖动，两个互质的小数）
                final double p0 = bounds[axis == 0 ? 1 : 0] + (i + 0.5) * d0 + d0 * 1.3e-3;
                final double p1 = bounds[axis == 2 ? 1 : 2] + (j + 0.5) * d1 + d1 * 2.7e-3;
                hits.clear();
                for (int t = 0; t + 2 < idx.length; t += 3) {
                    final Vector3d va = verts.get(idx[t]).position();
                    final Vector3d vb = verts.get(idx[t + 1]).position();
                    final Vector3d vc = verts.get(idx[t + 2]).position();
                    final double h = hitOnTriangle(p0, p1, va, vb, vc, axis);
                    if (!Double.isNaN(h)) {
                        hits.add(h);
                    }
                }
                if (hits.size() < 2) {
                    continue;
                }
                hits.sort(Double::compare);
                // 奇偶规则：第 0–1 个交点之间是实体、2–3 之间是实体……（孔自然被排除）
                final double base = bounds[axis];
                for (int k = 0; k + 1 < hits.size(); k += 2) {
                    final double h0 = hits.get(k);
                    final double h1 = hits.get(k + 1);
                    if (h1 - h0 < 1.0e-12) {
                        continue;
                    }
                    final int k0 = (int) Math.floor((h0 - base) / dRay);
                    final int k1 = (int) Math.ceil((h1 - base) / dRay) - 1;
                    for (int m = Math.max(0, k0); m <= Math.min(nRay - 1, k1); m++) {
                        // 把 (i, j, m) 按各自轴映射回 (x, y, z) 的线性下标
                        final int gx = axis == 0 ? m : i;
                        final int gy = axis == 0 ? i : (axis == 1 ? m : j);
                        final int gz = axis == 2 ? m : j;
                        occ[(gz * ny + gy) * nx + gx] = true;
                    }
                }
            }
        }
        return occ;
    }

    /**
     * 点 (p0,p1) 是否落在三角形在**该轴投影平面**内的投影里；是则返回该轴上的坐标，否则 NaN。
     *
     * <p>重心坐标三个权重都 ≥ 0 才算在内部。投影退化（面积≈0）时跳过 ——
     * 它对沿该轴的射线的贡献是零测度的。
     */
    private static double hitOnTriangle(final double p0, final double p1, final Vector3d a,
                                        final Vector3d b, final Vector3d c, final int axis) {
        final double ax = comp(a, axis == 0 ? 1 : 0);
        final double ay = comp(a, axis == 2 ? 1 : 2);
        final double bx = comp(b, axis == 0 ? 1 : 0);
        final double by = comp(b, axis == 2 ? 1 : 2);
        final double cxx = comp(c, axis == 0 ? 1 : 0);
        final double cyy = comp(c, axis == 2 ? 1 : 2);
        final double d = (by - cyy) * (ax - cxx) + (cxx - bx) * (ay - cyy);
        if (Math.abs(d) < 1.0e-15) {
            return Double.NaN;
        }
        final double w1 = ((by - cyy) * (p0 - cxx) + (cxx - bx) * (p1 - cyy)) / d;
        final double w2 = ((cyy - ay) * (p0 - cxx) + (ax - cxx) * (p1 - cyy)) / d;
        final double w3 = 1.0 - w1 - w2;
        if (w1 < 0.0 || w2 < 0.0 || w3 < 0.0) {
            return Double.NaN;
        }
        return w1 * comp(a, axis) + w2 * comp(b, axis) + w3 * comp(c, axis);
    }

    /** 取向量的第 {@code axis} 个分量（0=x, 1=y, 2=z）。 */
    private static double comp(final Vector3d v, final int axis) {
        return axis == 0 ? v.x : (axis == 1 ? v.y : v.z);
    }

    /** 贪心合并：先沿 x 尽量长、再沿 y、再沿 z，把占用格并成尽量大的长方体。 */
    private static List<OrientedBox> mergeCells(final boolean[] occ, final int nx, final int ny,
                                                final int nz, final double[] bounds,
                                                final double cx, final double cy,
                                                final double cz) {
        final boolean[] used = new boolean[occ.length];
        final List<OrientedBox> out = new ArrayList<>();
        for (int k = 0; k < nz; k++) {
            for (int j = 0; j < ny; j++) {
                for (int i = 0; i < nx; i++) {
                    if (!cellAt(occ, nx, ny, i, j, k) || cellAt(used, nx, ny, i, j, k)) {
                        continue;
                    }
                    int w = 1;
                    while (i + w < nx && cellAt(occ, nx, ny, i + w, j, k)
                            && !cellAt(used, nx, ny, i + w, j, k)) {
                        w++;
                    }
                    int h = 1;
                    while (j + h < ny && rowFree(occ, used, nx, ny, i, j + h, k, w)) {
                        h++;
                    }
                    int d = 1;
                    while (k + d < nz && slabFree(occ, used, nx, ny, i, j, k + d, w, h)) {
                        d++;
                    }
                    for (int kk = k; kk < k + d; kk++) {
                        for (int jj = j; jj < j + h; jj++) {
                            for (int ii = i; ii < i + w; ii++) {
                                used[(kk * ny + jj) * nx + ii] = true;
                            }
                        }
                    }
                    final double minX = bounds[0] + i * cx;
                    final double minY = bounds[1] + j * cy;
                    final double minZ = bounds[2] + k * cz;
                    final double maxX = bounds[0] + (i + w) * cx;
                    final double maxY = bounds[1] + (j + h) * cy;
                    final double maxZ = bounds[2] + (k + d) * cz;
                    out.add(new OrientedBox(
                            new Vector3d((minX + maxX) * 0.5, (minY + maxY) * 0.5,
                                    (minZ + maxZ) * 0.5),
                            new Vector3d((maxX - minX) * 0.5, (maxY - minY) * 0.5,
                                    (maxZ - minZ) * 0.5),
                            new Quaterniond()));
                }
            }
        }
        return out;
    }

    private static boolean cellAt(final boolean[] grid, final int nx, final int ny,
                                  final int i, final int j, final int k) {
        return grid[(k * ny + j) * nx + i];
    }

    private static boolean rowFree(final boolean[] occ, final boolean[] used, final int nx,
                                   final int ny, final int i, final int j, final int k,
                                   final int w) {
        for (int ii = i; ii < i + w; ii++) {
            if (!cellAt(occ, nx, ny, ii, j, k) || cellAt(used, nx, ny, ii, j, k)) {
                return false;
            }
        }
        return true;
    }

    private static boolean slabFree(final boolean[] occ, final boolean[] used, final int nx,
                                    final int ny, final int i, final int j, final int k,
                                    final int w, final int h) {
        for (int jj = j; jj < j + h; jj++) {
            if (!rowFree(occ, used, nx, ny, i, jj, k, w)) {
                return false;
            }
        }
        return true;
    }

    /**
     * **任意曲面 → 盒子壳**：只近似**表面**，不填内部。
     *
     * <h2>与 {@link #voxelBoxes} 的区别（这是用户明确要的方向）</h2>
     * `voxelBoxes` 是"填充实体"（射线奇偶判里外），盒子数随**体积**涨；
     * 用户要的是"**用盒子去近似曲面**，内部以后再说" —— 盒子数随**表面积**涨，
     * 而且对**任意**网格都成立（不依赖"里外"这个前提，非闭合网格也能用）。
     *
     * <h2>做法</h2>
     * <ol>
     *   <li>每个三角形**按面积撒点**（重心坐标格点，步长保证相邻采样 ≤ 半格），
     *       采样点落在哪个格子就标记哪个格子。撒点密度是关键：太稀会在曲面上留洞
     *      （壳漏风），太密纯属浪费。**半格步长是"不会漏"的保守选择**。</li>
     *   <li>**贪心合并**（复用 {@link #mergeCells}）。</li>
     *   <li>超预算就调粗重来 —— 盒子数由**预算**决定。</li>
     * </ol>
     *
     * <p>⚠ 这是**轴对齐**的壳：斜面靠阶梯逼近。要更贴只有两条路 ——
     * 更细的格子（盒子更多），或给盒子加朝向（那是分楔 OBB 的专用路，只对回转体好）。
     * 这里明确选**通用**，代价写在明面上。
     */
    public static VoxelResult surfaceBoxes(final SolidMesh.Mesh mesh, final double targetCell,
                                           final int maxBoxes, final int minRes, final int maxRes) {
        final double[] b = SolidMesh.bounds(mesh);
        if (b == null || mesh.triangleCount() == 0) {
            return new VoxelResult(List.of(), 0, 0.0, false);
        }
        final double spanX = Math.max(b[3] - b[0], 1.0e-6);
        final double spanY = Math.max(b[4] - b[1], 1.0e-6);
        final double spanZ = Math.max(b[5] - b[2], 1.0e-6);
        final double span = Math.max(spanX, Math.max(spanY, spanZ));
        final int budget = Math.max(1, maxBoxes);
        final int want = (int) Math.round(span / Math.max(targetCell, 1.0e-3));
        int res = Math.max(Math.max(4, minRes), Math.min(Math.max(4, maxRes), want));
        VoxelResult last = null;
        while (true) {
            final double target = span / res;
            final int nx = Math.max(1, (int) Math.round(spanX / target));
            final int ny = Math.max(1, (int) Math.round(spanY / target));
            final int nz = Math.max(1, (int) Math.round(spanZ / target));
            final double cx = spanX / nx;
            final double cy = spanY / ny;
            final double cz = spanZ / nz;
            final List<OrientedBox> merged = mergeCells(
                    surfaceCells(mesh, b, nx, ny, nz, cx, cy, cz), nx, ny, nz, b, cx, cy, cz);
            last = new VoxelResult(merged, res, Math.max(cx, Math.max(cy, cz)), res != want);
            if (merged.size() <= budget || res <= 4) {
                return last;
            }
            final int next = Math.max(4, (res * 3) / 4);
            if (next == res) {
                return last;
            }
            res = next;
        }
    }

    /**
     * 把三角形**按面积撒点**，标记每个采样点所在的格子。
     *
     * <p>重心坐标的规则格点：把三角形参数化成 {@code i+j <= n} 的整数格，
     * {@code n} 取到相邻采样间距 ≤ 半格。**不会漏格** ——
     * 漏格在碰撞上就是"壳上有洞"，比多几个盒子糟糕得多。
     */
    private static boolean[] surfaceCells(final SolidMesh.Mesh mesh, final double[] bounds,
                                          final int nx, final int ny, final int nz,
                                          final double cx, final double cy, final double cz) {
        final boolean[] occ = new boolean[nx * ny * nz];
        final double minCell = Math.min(cx, Math.min(cy, cz));
        final List<SolidMesh.Vertex> verts = mesh.vertices();
        final int[] idx = mesh.indices();
        for (int t = 0; t + 2 < idx.length; t += 3) {
            final Vector3d a = verts.get(idx[t]).position();
            final Vector3d bb = verts.get(idx[t + 1]).position();
            final Vector3d c = verts.get(idx[t + 2]).position();
            // ⚠ **跳过退化三角形**（面积≈0）。回转体的轮廓里那些**落在轴上的边**
            //   （比如半圆的直径）转出来就是一堆面积为零的三角形，它们**不在曲面上**；
            //   不跳过的话会在轴线上撒点，把穿过球心的格子也标成"表面" ——
            //   于是"壳"里出现一串穿过内部的盒子（实测挖出来的侵入盒正好含原点）。
            final Vector3d e1 = new Vector3d(bb).sub(a, new Vector3d());
            final Vector3d e2 = new Vector3d(c).sub(a, new Vector3d());
            final double area2 = e1.cross(e2, new Vector3d()).length();
            if (area2 < 1.0e-12) {
                continue;
            }
            final double longest = Math.max(a.distance(bb),
                    Math.max(bb.distance(c), c.distance(a)));
            final double step = Math.max(1.0e-3, minCell * 0.5);
            final int n = Math.max(1, (int) Math.ceil(longest / step));
            for (int i = 0; i <= n; i++) {
                for (int j = 0; j + i <= n; j++) {
                    final double w0 = (double) (n - i - j) / n;
                    final double w1 = (double) i / n;
                    final double w2 = (double) j / n;
                    final double px = w0 * a.x + w1 * bb.x + w2 * c.x;
                    final double py = w0 * a.y + w1 * bb.y + w2 * c.y;
                    final double pz = w0 * a.z + w1 * bb.z + w2 * c.z;
                    final int gx = (int) Math.floor((px - bounds[0]) / cx);
                    final int gy = (int) Math.floor((py - bounds[1]) / cy);
                    final int gz = (int) Math.floor((pz - bounds[2]) / cz);
                    if (gx >= 0 && gx < nx && gy >= 0 && gy < ny && gz >= 0 && gz < nz) {
                        occ[(gz * ny + gy) * nx + gx] = true;
                    }
                }
            }
        }
        return occ;
    }

    // ==================================================================
    // 1/16 体素化：直接喂给体素碰撞体的形状
    // ==================================================================

    /**
     * 一个方块格要吐给体素碰撞体的形状。
     *
     * @param bx,by,bz 方块格坐标（plot 局部，整数格）
     * @param boxes    盒子，每个是 {@code [x0,y0,z0,x1,y1,z1]}，坐标在**方块局部 [0,1]**
     *                 —— 这正是 {@code Rapier3D.addVoxelColliderBox} 要的单位
     *                 （依据：`RapierVoxelColliderData.addBox` 的 javadoc
     *                 "within a single voxel space of the block, 0-1"）。
     */
    public record BlockShape(int bx, int by, int bz, List<double[]> boxes) {
    }

    /**
     * 把网格按 **1/16 精度**体素化成"每个方块格一份局部形状"。
     *
     * <h2>为什么输出是这个形状</h2>
     * 体素碰撞体描述的是**一个方块**的形状（坐标 0..1），而方块数据是**逐格**引用形状下标的
     * —— 所以整条链要的正是"每个方块格一组局部的 1/16 子盒"。
     *
     * <h2>精度与超出边界（用户报的两个问题）</h2>
     * 子格算不算"表面经过它"是拿三角形**采样**判的；盒子最多探出**一个子格 = 1/16 格**。
     * 对比之前：`voxelBoxes` 按格心判内外会探出**半格**且格子本身还有 1/4~1/2 格粗；
     * `surfaceBoxes` 的壳天然跨在曲面两侧、也是半格量级。**1/16 小了 4~8 倍**，
     * 而且粒度就是用户要的那个数。
     *
     * <p>⚠ **跳过退化三角形**（面积≈0）：回转体轮廓里落在轴上的边（如半圆的直径）
     * 转出来全是零面积三角形，它们不在曲面上；不跳过会在轴线上撒点，
     * 把穿过内部的子格也标成表面（我踩过一次，壳里出现一串穿心的盒子）。
     *
     * @param mesh     三角网格（plot 局部系）
     * @param subdiv   一个方块切成几份（16 = 1/16 精度）
     * @param maxBoxesPerBlock 每格的盒子数提示上限（体素路径没有刚体/约束开销，不必为省盒子牺牲精度）
     */
    public static List<BlockShape> voxelizeToBlocks(final SolidMesh.Mesh mesh,
                                                    final int subdiv,
                                                    final int maxBoxesPerBlock) {
        return voxelizeToBlocks(mesh, subdiv, maxBoxesPerBlock, 0);
    }

    /**
     * 体素化，并可**沿法线向内加厚** {@code shellThickness} 个子格。
     *
     * <p>{@code shellThickness = 0} 就是原来的薄壳（现有断言用的就是这一档）。
     * 生产路径会传一个正数 —— 薄壳落地会陷地、会弹跳，见 {@code bitmapOf} 的说明。
     */
    public static List<BlockShape> voxelizeToBlocks(final SolidMesh.Mesh mesh,
                                                    final int subdiv,
                                                    final int maxBoxesPerBlock,
                                                    final int shellThickness) {
        return voxelizeToBlocks(mesh, subdiv, maxBoxesPerBlock, shellThickness, false);
    }

    /**
     * 体素化，可选**把内部填成粗实心**。
     *
     * <h2>为什么要填（用户实测 + 物理常识）</h2>
     * 空心薄壳有三个毛病：
     * <ol>
     *   <li><b>穿透</b>：穿透量一旦超过壳厚，下面**没有任何几何** → 直接沉下去
     *       （用户实测："圆柱面落地则陷入地面"）；</li>
     *   <li><b>质量算不对</b>：体素形状的 {@code volume} 参数是"这个形状的体积"，
     *       薄壳体积 ≈ 表面积×厚度，**远小于实体体积** → 质量被严重低估；</li>
     *   <li><b>惯量不对</b>：质量集中在表面 → 转动惯量偏大。</li>
     * </ol>
     * 填成**粗实心**（每格最多一个盒子）后，盒子体积之和 ≈ 实体体积 ✓
     * 而成本是 <b>O(包围盒格数)</b>，因为粗粒度是"一格一个盒"而不是 1/16。
     *
     * <p>做法用**洪水填充**（不需要"点在体内"的解析判定）：从包围盒边界出发，
     * 在未占用的格子之间蔓延；**蔓延不到的空格子就是内部** ✓
     */
    public static List<BlockShape> voxelizeToBlocks(final SolidMesh.Mesh mesh,
                                                    final int subdiv,
                                                    final int maxBoxesPerBlock,
                                                    final int shellThickness,
                                                    final boolean solidInterior) {
        final List<BlockShape> out = new ArrayList<>();
        final double[] b = SolidMesh.bounds(mesh);
        if (b == null || mesh.triangleCount() == 0) {
            return out;
        }
        final int n = Math.max(2, subdiv);
        final double sub = 1.0 / n;
        final List<int[]> keys = new ArrayList<>();
        // ⚠ **累积"曲面采样点 + 法线"，不再打 16³ 占用位图。**
        //   位图的问题是**每格最多几千个盒子**：20 方块的圆柱约 1257 个表面格，
        //   每格几百盒 → 几十万到上百万盒全在一次重建里做完 → 用户实测 MSPT 十几万。
        //   格子里真正的曲面只是**一层薄带**，几个盒子就能贴合（见 fitShell）。
        final List<List<double[]>> samples = new ArrayList<>();
        final java.util.Map<Long, Integer> indexOf = new java.util.HashMap<>();

        final List<SolidMesh.Vertex> verts = mesh.vertices();
        final int[] idx = mesh.indices();
        for (int t = 0; t + 2 < idx.length; t += 3) {
            final Vector3d a = verts.get(idx[t]).position();
            final Vector3d bb = verts.get(idx[t + 1]).position();
            final Vector3d c = verts.get(idx[t + 2]).position();
            // ⚠ 跳过退化三角形（见上面的注释）；顺便留下**面法线**给薄壳拟合用
            final Vector3d e1 = new Vector3d(bb).sub(a, new Vector3d());
            final Vector3d e2 = new Vector3d(c).sub(a, new Vector3d());
            final Vector3d fn = e1.cross(e2, new Vector3d());
            if (fn.length() < 1.0e-12) {
                continue;
            }
            fn.normalize();
            final double longest = Math.max(a.distance(bb),
                    Math.max(bb.distance(c), c.distance(a)));
            final double step = sub * 0.5;
            final int steps = Math.max(1, (int) Math.ceil(longest / step));
            for (int i = 0; i <= steps; i++) {
                for (int j = 0; j + i <= steps; j++) {
                    final double w0 = (double) (steps - i - j) / steps;
                    final double w1 = (double) i / steps;
                    final double w2 = (double) j / steps;
                    final double px = w0 * a.x + w1 * bb.x + w2 * c.x;
                    final double py = w0 * a.y + w1 * bb.y + w2 * c.y;
                    final double pz = w0 * a.z + w1 * bb.z + w2 * c.z;
                    final int gx = (int) Math.floor(px);
                    final int gy = (int) Math.floor(py);
                    final int gz = (int) Math.floor(pz);
                    final long key = packCell(gx, gy, gz);
                    Integer at = indexOf.get(key);
                    if (at == null) {
                        at = keys.size();
                        indexOf.put(key, at);
                        keys.add(new int[]{gx, gy, gz});
                        samples.add(new ArrayList<>());
                    }
                    // 存**格子局部坐标**（[0,1)³）与面法线 —— 薄壳拟合的输入
                    samples.get(at).add(new double[]{
                            px - gx, py - gy, pz - gz, fn.x, fn.y, fn.z});
                }
            }
        }
        for (int k = 0; k < keys.size(); k++) {
            final int[] key = keys.get(k);
            final List<double[]> cellSamples = samples.get(k);
            // ⚠⚠ **薄壳拟合暂未启用**（保留代码与断言，供后续打磨）。
            //
            //   用户的条件是"**如果精度不差**就用它"。实测**精度是差的**：
            //   现有断言在真实几何上直接红了 ——
            //     [16-C] 最大偏差 0.449~0.5（上限 1/16）
            //     [16-D] 200 个采样顶点没被任何盒子覆盖 → **会穿模**
            //   根因：一格里的采样点可能来自**不同朝向的曲面片**（曲面在格内弯折、
            //   或格里有不相连的两片），单一主轴的台阶拟合盖不住；
            //   而"逐格验证 + 不达标回退位图"也救不回偏差（盒子仍然肥）。
            //
            //   所以这里**保持原来的 16³ 位图表示**（精度 1/16，已验证），
            //   卡顿由 {@code VoxelCollisionService} 的**预算闸门**解决
            //   （超预算的实体走盒子方案，不再爆炸）。
            //   拟合本身有独立的精度断言：{@code tools/ShellFitCheck.java}。
            final List<double[]> boxes = mergeSubCells(bitmapOf(cellSamples, n, shellThickness),
                    n, maxBoxesPerBlock);
            if (!boxes.isEmpty()) {
                out.add(new BlockShape(key[0], key[1], key[2], boxes));
            }
        }
        if (solidInterior) {
            fillInterior(out, keys, mesh);
        }
        return out;
    }

    /**
     * **填粗实心**：用洪水填充找出"被壳围住、从外面走不到"的格子，每格出一个整格盒子。
     *
     * <p>为什么用洪水填充而不是"点在体内"的解析判定：后者需要我们重建一张参数曲面
     * （拉伸/回转的解析内外判定），而洪水填充**只需要壳已经建好** ✓
     * 代价是 O(包围盒格数) —— 对 φ20×2 的圆柱是 20×20×2 = 800 格，可忽略。
     *
     * <p>⚠ 前提是**壳是封闭的**。壳有洞（采样漏格）时填充会从洞里漏出去，
     * 结果是"内部没填上"而不是错误填充 —— 方向是安全的（退化成薄壳，不会凭空多出碰撞）。
     */
    private static void fillInterior(final List<BlockShape> out, final List<int[]> shellKeys,
                                     final SolidMesh.Mesh mesh) {
        final double[] b = SolidMesh.bounds(mesh);
        if (b == null) {
            return;
        }
        // 留 1 格边距，保证边界一定在"外部"
        final int minX = (int) Math.floor(b[0]) - 1;
        final int minY = (int) Math.floor(b[1]) - 1;
        final int minZ = (int) Math.floor(b[2]) - 1;
        final int maxX = (int) Math.floor(b[3]) + 1;
        final int maxY = (int) Math.floor(b[4]) + 1;
        final int maxZ = (int) Math.floor(b[5]) + 1;
        final int nx = maxX - minX + 1;
        final int ny = maxY - minY + 1;
        final int nz = maxZ - minZ + 1;
        final long total = (long) nx * ny * nz;
        if (total > 8_000_000L) {
            return; // 太大就放弃填充（宁可薄壳，也不要卡死）
        }
        final boolean[] occupied = new boolean[(int) total];
        for (final int[] k : shellKeys) {
            final int x = k[0] - minX;
            final int y = k[1] - minY;
            final int z = k[2] - minZ;
            if (x >= 0 && y >= 0 && z >= 0 && x < nx && y < ny && z < nz) {
                occupied[(x * ny + y) * nz + z] = true;
            }
        }
        // 洪水填充：从外边界开始，在"未占用"的格子里蔓延
        final boolean[] outside = new boolean[(int) total];
        final java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        for (int x = 0; x < nx; x++) {
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nz; z++) {
                    final boolean border = x == 0 || y == 0 || z == 0
                            || x == nx - 1 || y == ny - 1 || z == nz - 1;
                    if (border && !occupied[(x * ny + y) * nz + z]
                            && !outside[(x * ny + y) * nz + z]) {
                        outside[(x * ny + y) * nz + z] = true;
                        queue.add((x * ny + y) * nz + z);
                    }
                }
            }
        }
        while (!queue.isEmpty()) {
            final int idx = queue.poll();
            final int z = idx % nz;
            final int y = (idx / nz) % ny;
            final int x = idx / (nz * ny);
            for (int d = 0; d < 6; d++) {
                final int xx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
                final int yy = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
                final int zz = z + (d == 4 ? 1 : d == 5 ? -1 : 0);
                if (xx < 0 || yy < 0 || zz < 0 || xx >= nx || yy >= ny || zz >= nz) {
                    continue;
                }
                final int j = (xx * ny + yy) * nz + zz;
                if (occupied[j] || outside[j]) {
                    continue;
                }
                outside[j] = true;
                queue.add(j);
            }
        }
        // 既没被占用、又不连通外部的格子 = 内部 → 每格一个整格盒子（粗实心）
        final double[] full = {0.0, 0.0, 0.0, 1.0, 1.0, 1.0};
        for (int x = 0; x < nx; x++) {
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nz; z++) {
                    final int j = (x * ny + y) * nz + z;
                    if (!occupied[j] && !outside[j]) {
                        out.add(new BlockShape(minX + x, minY + y, minZ + z,
                                List.of(full.clone())));
                    }
                }
            }
        }
    }

    /** 采样点 → 16³ 占用位图。{@code thickness = 0} 表示只要表面那一层（薄壳）。 */
    private static boolean[] bitmapOf(final List<double[]> samples, final int n) {
        return bitmapOf(samples, n, 0);
    }

    /**
     * 采样点 → 占用位图，并**沿法线向内加厚** {@code thickness} 个子格。
     *
     * <h2>为什么必须加厚（用户实测的三个症状）</h2>
     * "大圆柱**不落地时正常**，落地后 MSPT 2000；**圆柱面落地则陷进地面**，然后卡顿、弹跳。"
     * 三个症状同源，都是**薄壳**的必然结果：
     * <ul>
     *   <li><b>陷地</b>：每格只有表面那一层子格，大质量压上去，接触解算压不住 → 平均意义上沉下去；</li>
     *   <li><b>弹跳</b>：薄壳的**穿透修正过冲** —— 修正量把物体推得比原位置更远；</li>
     *   <li><b>卡顿</b>：接触点数量随盒子数上升（这条主要靠降子格精度解决，见 {@code VoxelCollisionService}）。</li>
     * </ul>
     * 向内加厚（**沿 -法线**，不是向外膨胀）把接触变稳，
     * 而且成本仍是 <b>O(表面)</b> —— 不会退回 O(体积) 的实心填充。
     *
     * <p>⚠ 前提是网格法线朝**外**（我们生成的网格满足）。若某处法线朝内，
     * 那一处会被**向外**加厚（碰撞略大），不会穿模 —— 方向错的代价是安全的。
     */
    private static boolean[] bitmapOf(final List<double[]> samples, final int n,
                                      final int thickness) {
        final double sub = 1.0 / n;
        final boolean[] bits = new boolean[n * n * n];
        for (final double[] s : samples) {
            markSub(bits, n, s[0], s[1], s[2]);
            if (thickness > 0) {
                // 沿 -法线 走 k 个子格（向内），逐层打点
                for (int k = 1; k <= thickness; k++) {
                    final double off = k * sub;
                    markSub(bits, n, s[0] - s[3] * off, s[1] - s[4] * off,
                            s[2] - s[5] * off);
                }
            }
        }
        return bits;
    }

    /** 打一个子格（越界就丢掉 —— 加厚只在本格内生效，跨格的加厚由邻格自己的采样完成）。 */
    private static void markSub(final boolean[] bits, final int n, final double lx,
                                final double ly, final double lz) {
        if (lx < 0.0 || ly < 0.0 || lz < 0.0 || lx >= 1.0 || ly >= 1.0 || lz >= 1.0) {
            return;
        }
        final double sub = 1.0 / n;
        final int sx = clampSub(lx / sub, n);
        final int sy = clampSub(ly / sub, n);
        final int sz = clampSub(lz / sub, n);
        bits[(sz * n + sy) * n + sx] = true;
    }

    /**
     * 验证拟合结果：**每个采样点都要落在某个盒子里，且距离不超过容差**。
     *
     * <p>这是"精度不差"的判据本身 —— 不达标就回退，所以它是**保证**而不是期望。
     */
    private static boolean coversAll(final List<double[]> samples, final List<double[]> boxes,
                                     final double tol) {
        if (boxes.isEmpty()) {
            return false;
        }
        for (final double[] s : samples) {
            double best = Double.MAX_VALUE;
            for (final double[] b : boxes) {
                final double dx = Math.max(Math.max(b[0] - s[0], 0.0), s[0] - b[3]);
                final double dy = Math.max(Math.max(b[1] - s[1], 0.0), s[1] - b[4]);
                final double dz = Math.max(Math.max(b[2] - s[2], 0.0), s[2] - b[5]);
                final double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d < best) {
                    best = d;
                }
            }
            if (!(best <= tol + 1.0e-9)) {
                return false;
            }
        }
        return true;
    }

    /**
     * **薄壳拟合**：把一格里的曲面采样点用**少量轴对齐盒子**贴合。
     *
     * <h2>做法（台阶拟合）</h2>
     * <ol>
     *   <li>取采样点的平均法线，选 |n| 最大的轴作为**高度轴 a**（另两个记 b、c）；</li>
     *   <li>这一小片曲面于是可看作高度场 {@code a = h(b,c)}；</li>
     *   <li>沿 b 切成 K 个带，使**每带内 a 的跨度 ≤ stepTol**，K 反算：</li>
     * </ol>
     * <pre>
     *   K = clamp( ceil( (a_max − a_min) / stepTol ), 1, subdiv )
     * </pre>
     * 每带出一个盒子：{@code b 带 × c 全跨度 × [a_lo − margin, a_hi + margin]}。
     *
     * <h2>精度：不比原来的 1/16 差</h2>
     * 取 {@code stepTol = margin = 1/(2·subdiv)}，于是盒子离真实曲面的偏差
     * <pre>
     *   ≤ stepTol + margin = 1/subdiv = 1/16   ✓（与原来的 16³ 位图同精度）
     * </pre>
     * 且**盒子包含曲面片** → 物理上不穿模。
     * 换来的是**盒子数降两个数量级**（典型每格 1~8 个，而不是几百个）。
     */
    /**
     * 供 {@code tools/ShellFitCheck.java} 直接验证拟合精度用的入口。
     *
     * <p>暴露出来是为了让"**偏差 ≤ 1/16、且包含曲面**"这两条主张能被**离线断言**钉住 ——
     * 这是用户"如果精度不差就做"的条件，不能只靠推理。
     */
    public static List<double[]> fitShellForCheck(final List<double[]> samples, final int subdiv,
                                                  final int maxBoxesPerBlock) {
        return fitShell(samples, subdiv, maxBoxesPerBlock);
    }

    private static List<double[]> fitShell(final List<double[]> samples, final int subdiv,
                                           final int maxBoxesPerBlock) {        final List<double[]> out = new ArrayList<>();
        if (samples.isEmpty()) {
            return out;
        }
        final double tol = 1.0 / subdiv;
        final double stepTol = tol * 0.5;   // 每带内高度跨度上限
        final double margin = tol * 0.5;    // 外扩余量（保守，防穿模）
        double nx = 0.0;
        double ny = 0.0;
        double nz = 0.0;
        for (final double[] s : samples) {
            nx += s[3];
            ny += s[4];
            nz += s[5];
        }
        final int a;
        final int b;
        final int c;
        if (Math.abs(ny) >= Math.abs(nx) && Math.abs(ny) >= Math.abs(nz)) {
            a = 1; b = 0; c = 2;
        } else if (Math.abs(nx) >= Math.abs(nz)) {
            a = 0; b = 1; c = 2;
        } else {
            a = 2; b = 0; c = 1;
        }
        double aMin = Double.MAX_VALUE;
        double aMax = -Double.MAX_VALUE;
        double bMin = Double.MAX_VALUE;
        double bMax = -Double.MAX_VALUE;
        double cMin = Double.MAX_VALUE;
        double cMax = -Double.MAX_VALUE;
        for (final double[] s : samples) {
            aMin = Math.min(aMin, s[a]);
            aMax = Math.max(aMax, s[a]);
            bMin = Math.min(bMin, s[b]);
            bMax = Math.max(bMax, s[b]);
            cMin = Math.min(cMin, s[c]);
            cMax = Math.max(cMax, s[c]);
        }
        int k = (int) Math.ceil((aMax - aMin) / stepTol - 1.0e-9);
        k = Math.max(1, Math.min(subdiv, k));
        k = Math.min(k, Math.max(1, maxBoxesPerBlock));
        final double bSpan = Math.max(1.0e-9, bMax - bMin);
        for (int i = 0; i < k; i++) {
            final double b0 = bMin + bSpan * i / k;
            final double b1 = bMin + bSpan * (i + 1) / k;
            double lo = Double.MAX_VALUE;
            double hi = -Double.MAX_VALUE;
            // ⚠ **c 也要用"该带自己的跨度"**，不能用整格的 c 跨度：
            //   用整格跨度时，窄条带（c 方向很窄）会生成一个在 c 方向**肥出去**的盒子，
            //   盒子的角离曲面很远 —— 现有断言 [16-C] 直接测到 0.449 的偏差（上限 1/16）。
            double c0 = Double.MAX_VALUE;
            double c1 = -Double.MAX_VALUE;
            for (final double[] s : samples) {
                if (s[b] >= b0 - 1.0e-9 && s[b] <= b1 + 1.0e-9) {
                    lo = Math.min(lo, s[a]);
                    hi = Math.max(hi, s[a]);
                    c0 = Math.min(c0, s[c]);
                    c1 = Math.max(c1, s[c]);
                }
            }
            if (lo > hi) {
                continue; // 该带没有采样点（k 多于采样点时会出现）
            }
            final double[] box = new double[6];
            box[a] = Math.max(0.0, lo - margin);
            box[a + 3] = Math.min(1.0, hi + margin);
            box[b] = Math.max(0.0, b0);
            box[b + 3] = Math.min(1.0, b1);
            box[c] = Math.max(0.0, c0 - margin);
            box[c + 3] = Math.min(1.0, c1 + margin);
            if (box[3] > box[0] && box[4] > box[1] && box[5] > box[2]) {
                out.add(box);
            }
        }
        return out;
    }

    /** 格内坐标夹到 {@code [0, n-1]}（点正好落在 1.0 边界上时 floor 会越界一格）。 */
    private static int clampSub(final double v, final int n) {
        final int i = (int) Math.floor(v);
        return Math.max(0, Math.min(n - 1, i));
    }

    /**
     * **诊断用**：1/{@code subdiv} 精度下，曲面上**被占用的子格总数**（合并前）。
     *
     * <p>与 {@link #voxelizeToBlocks} 的盒子数一比就是**贪心合并的效率**。
     * 效率已经很高时再去改合并算法是白费功夫 —— 所以先量，再决定要不要优化。
     */
    public static int occupiedSubCellCount(final SolidMesh.Mesh mesh, final int subdiv) {
        final double[] b = SolidMesh.bounds(mesh);
        if (b == null || mesh.triangleCount() == 0) {
            return 0;
        }
        final int n = Math.max(2, subdiv);
        final double sub = 1.0 / n;
        final java.util.Set<Long> cells = new java.util.HashSet<>();
        final List<SolidMesh.Vertex> verts = mesh.vertices();
        final int[] idx = mesh.indices();
        for (int t = 0; t + 2 < idx.length; t += 3) {
            final Vector3d a = verts.get(idx[t]).position();
            final Vector3d bb = verts.get(idx[t + 1]).position();
            final Vector3d c = verts.get(idx[t + 2]).position();
            final Vector3d e1 = new Vector3d(bb).sub(a, new Vector3d());
            final Vector3d e2 = new Vector3d(c).sub(a, new Vector3d());
            if (e1.cross(e2, new Vector3d()).length() < 1.0e-12) {
                continue;
            }
            final double longest = Math.max(a.distance(bb),
                    Math.max(bb.distance(c), c.distance(a)));
            final double step = sub * 0.5;
            final int steps = Math.max(1, (int) Math.ceil(longest / step));
            for (int i = 0; i <= steps; i++) {
                for (int j = 0; j + i <= steps; j++) {
                    final double w0 = (double) (steps - i - j) / steps;
                    final double w1 = (double) i / steps;
                    final double w2 = (double) j / steps;
                    final double px = w0 * a.x + w1 * bb.x + w2 * c.x;
                    final double py = w0 * a.y + w1 * bb.y + w2 * c.y;
                    final double pz = w0 * a.z + w1 * bb.z + w2 * c.z;
                    final int gx = (int) Math.floor(px * n);
                    final int gy = (int) Math.floor(py * n);
                    final int gz = (int) Math.floor(pz * n);
                    cells.add((((long) gx & 0xFFFFF) << 40) | (((long) gy & 0xFFFFF) << 20)
                            | (gz & 0xFFFFF));
                }
            }
        }
        return cells.size();
    }

    /** 方块格坐标打成 long（各 21 位，够 ±100 万格）。 */
    public static long packCell(final int x, final int y, final int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21)
                | (z & 0x1FFFFF);
    }

    /**
     * **段内索引**：Sable 的约定是 {@code (x&15) + ((z&15)<<4) + ((y&15)<<8)}，
     * 也就是 **x + z·16 + y·256** —— ⚠ **不是**常见的 x + y·16 + z·256。
     *
     * <p>依据：`RapierPhysicsPipeline.add(KinematicContraption)` 里
     * {@code final int index = (x & 15) + ((z & 15) << 4) + ((y & 15) << 8);}。
     * 写错的话方块会出现在错误的格子上（而且是"看起来像随机错位"，极难查），
     * 所以这里做成**唯一实现**，调用方一律用它。
     */
    public static int sectionIndex(final int x, final int y, final int z) {
        return (x & 15) + ((z & 15) << 4) + ((y & 15) << 8);
    }

    /** 一个 16³ 段的方块数（4096）。 */
    public static final int SECTION_BLOCKS = 16 * 16 * 16;

    /** 体素碰撞体状态的序数：{@code [Empty, Face, Edge, Corner, Interior]} 里我们用的那个。 */
    public static final int STATE_ORDINAL = 3;

    /**
     * 把一个方块格**打包成段数据里的一个 int**。
     *
     * <p>依据 Rust `contraptions.rs`（读死的）：
     * <pre>
     * block_collider_id = (block >> 16)    // 高 16 位 = 体素碰撞体下标 **+1**（0 = 空气）
     * voxel_state_id    = (block & 0xFFFF) // 低 16 位 = 索引进 ALL_VOXEL_PHYSICS_STATES
     * </pre>
     * 「+1」的依据是 `lib.rs` 里用 {@code voxel_colliders[(block.0 - 1) as usize]} 取碰撞体
     * —— 方块自身的字段就是**下标+1**，0 表示空。
     *
     * @param voxelIndex 体素碰撞体下标（{@code newVoxelCollider} 返回的那个）
     */
    public static int packBlock(final int voxelIndex, final int stateOrdinal) {
        return ((voxelIndex + 1) << 16) | (stateOrdinal & 0xFFFF);
    }

    /**
     * 把方块形状**按 16³ 段分组并打包**成每段一个 {@code int[4096]}。
     *
     * <p>这就是 {@code addKinematicContraptionChunkSection(handle, id, sx, sy, sz, data)} 要的
     * {@code data}。
     *
     * @param shapes  每格一份形状（见 {@link #voxelizeToBlocks}）
     * @param indexOf 把一份形状映射成**运行时的体素碰撞体下标**（由调用方在服务端分配，
     *                因为它要调原生 {@code newVoxelCollider}）
     * @return 段坐标（{@link #packCell}）→ {@code int[4096]}
     */
    public static java.util.Map<Long, int[]> packSections(final List<BlockShape> shapes,
                                                          final java.util.function.ToIntFunction<BlockShape> indexOf) {
        final java.util.Map<Long, int[]> out = new java.util.HashMap<>();
        for (final BlockShape shape : shapes) {
            final int voxelIndex = indexOf.applyAsInt(shape);
            if (voxelIndex < 0) {
                continue; // 形状分配失败就跳过这一格，不要塞一个非法下标进去
            }
            final int sx = shape.bx() >> 4;
            final int sy = shape.by() >> 4;
            final int sz = shape.bz() >> 4;
            final long key = packCell(sx, sy, sz);
            final int[] data = out.computeIfAbsent(key, k -> new int[SECTION_BLOCKS]);
            data[sectionIndex(shape.bx(), shape.by(), shape.bz())] =
                    packBlock(voxelIndex, STATE_ORDINAL);
        }
        return out;
    }

    /**
     * 把一个方块格里的占用子格合并成**方块局部 [0,1]** 的盒子。
     *
     * <p>复用 {@link #mergeCells}（同一个贪心合并、同样的"先 x 再 y 再 z"），
     * 只是把结果从 plot 坐标换成**方块局部**并夹到 [0,1] —— 体素碰撞体只接受这个范围。
     */
    private static List<double[]> mergeSubCells(final boolean[] occ, final int n,
                                                final int maxBoxes) {
        final double[] origin = {0.0, 0.0, 0.0};
        final double cell = 1.0 / n;
        final List<OrientedBox> merged = mergeCells(occ, n, n, n, origin, cell, cell, cell);
        final List<double[]> out = new ArrayList<>(merged.size());
        for (final OrientedBox box : merged) {
            final Vector3d min = new Vector3d(box.centre()).sub(box.halfExtents());
            final Vector3d max = new Vector3d(box.centre()).add(box.halfExtents());
            // 夹到 [0,1]：体素碰撞体的坐标必须落在方块局部的单位立方体里
            out.add(new double[] {
                    Math.max(0.0, min.x), Math.max(0.0, min.y), Math.max(0.0, min.z),
                    Math.min(1.0, max.x), Math.min(1.0, max.y), Math.min(1.0, max.z)});
        }
        // ⚠ 体素路径**不该为省盒子牺牲精度**：它没有刚体/约束的线性开销，
        //   所以超了 maxBoxes 也如实返回，由调用方决定。
        return out;
    }

    public static List<OrientedBox> shrink(final List<OrientedBox> boxes, final double scale) {
        if (boxes == null || scale >= 1.0 || scale <= 0.0) {
            return boxes == null ? List.of() : boxes;
        }
        final List<OrientedBox> out = new ArrayList<>(boxes.size());
        for (final OrientedBox b : boxes) {
            out.add(new OrientedBox(b.centre(),
                    new Vector3d(b.halfExtents()).mul(scale), b.rotation()));
        }
        return out;
    }

    /**
     * 一个区间覆盖到的格子下标区间 {@code {first, last}}（闭区间）。
     *
     * <p>⚠ 上界要特殊处理：{@code maxX} 正好落在整数上时**不该**多算一格，
     * 否则每个实体都会往 +X 方向虚增一整层格子（那层是零厚度的）。
     */
    static int[] cellRange(final double min, final double max) {
        final int first = (int) Math.floor(min);
        final int last = (int) Math.ceil(max) - 1;
        // 退化区间（min == max）返回一个空区间，调用方靠 <= 判断自然跳过
        return new int[] {first, Math.max(last, first - 1)};
    }

    private static boolean valid(final double[] b) {
        return b != null && b.length >= 6
                && b[3] > b[0] && b[4] > b[1] && b[5] > b[2];
    }

    // ---- 坐标打包（不依赖 MC 的 BlockPos.longPosition）----

    public static long pack(final int x, final int y, final int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    public static int unpackX(final long packed) {
        return (int) (packed >> 38);
    }

    public static int unpackY(final long packed) {
        return (int) (packed << 26 >> 52);
    }

    public static int unpackZ(final long packed) {
        return (int) (packed << 38 >> 38);
    }
}
