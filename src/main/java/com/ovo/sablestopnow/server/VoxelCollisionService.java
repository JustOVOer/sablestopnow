package com.ovo.sablestopnow.server;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.mixin.scale.Rapier3DAccessor;
import com.ovo.sablestopnow.sketch.Sketch;
import com.ovo.sablestopnow.sketch.Solid;
import com.ovo.sablestopnow.sketch.SolidCollision;
import com.ovo.sablestopnow.physics.SablePhysicsExt;
import com.ovo.sablestopnow.physics.SolidMassComposition;
import com.ovo.sablestopnow.scale.ScaledMass;
import com.ovo.sablestopnow.sketch.SolidMesh;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * **曲面实体走体素碰撞体**（用户 2026-10 定的方案）。
 *
 * <h2>为什么</h2>
 * 盒子方案是"一个盒子 = 一个刚体 + 一条**弹簧**约束"，每个盒子每步都进求解器迭代
 * → 盒子一多就**大量卡顿**（实测半径 1 球的旋转体要 132 个盒子）。
 * 体素碰撞体则是**一个碰撞体喂 N 个盒子**：没有刚体、没有约束、不进求解器，
 * 而且能做到 **1/16 精度**（方块局部单位立方体的十六等分）。
 *
 * <h2>做法（每一步都在 Sable 自己的代码里核对过）</h2>
 * <ol>
 *   <li>把实体的网格按 1/16 体素化成"每个方块格一份方块局部 [0,1] 的形状"
 *       （{@link SolidCollision#voxelizeToBlocks}）；</li>
 *   <li>每格调一次 {@code newVoxelCollider} 拿形状下标，再把该格的子盒逐个
 *       {@code addVoxelColliderBox} 喂进去；</li>
 *   <li>把方块格**重基**到装置自己的局部坐标（装置是一个局部体素结构，
 *       由位姿摆到世界里）；</li>
 *   <li>{@code createKinematicContraption} —— mountId 用**子结构自己的刚体 id**，
 *       于是装置的碰撞体与子结构**同刚体**：同刚体上的碰撞体永不互相碰撞，
 *       所以与子结构自身的方块重合也**无害**，而且不新增刚体；</li>
 *   <li>{@code addKinematicContraptionChunkSection} 喂每段 4096 个 int；</li>
 *   <li>{@code setKinematicContraptionTransform} —— **漏了这步会崩**
 *       （{@code compute_local_aabb called before bounds were set}），
 *       而且它的第三个参数是**装置自己的质心**（合成装置就是 0），不是父体的；</li>
 *   <li>{@code setLocalBounds} 声明局部范围。</li>
 * </ol>
 */
public final class VoxelCollisionService {

    private VoxelCollisionService() {
    }

    /** 一个方块切成几份。16 = 用户要的 1/16 精度。 */
    public static final int SUBDIV = 16;

    /**
     * **按实体尺寸选子格精度**（LOD）。
     *
     * <p>用户实测："大圆柱不落地时正常，落地后 MSPT 2000"。根因是**接触点数**：
     * 碰撞体是上千个轴对齐盒子，一接触地面，Rapier **每对盒子生成一个接触点** ——
     * 一步几千个接触，物理引擎直接崩掉帧率。
     *
     * <p>子格越细 → 每格的盒子越多 → 接触点越多。而大实体上"1/16 的细节"本来也看不出来，
     * 所以**按尺寸降精度**是这类引擎的标准做法：
     * <pre>
     *   ≤ 4 方块 → 1/16（小物件要保持精度）
     *   ≤ 12     → 1/8
     *   更大     → 1/4
     * </pre>
     * 代价说明白：**大实体上碰撞精度降到 1/4 方块**。这是有意的取舍 ——
     * 薄接触本来也不准，而卡死（MSPT 2000）是不可接受的。
     */
    private static int subdivFor(final double[] bounds) {
        if (bounds == null) {
            return SUBDIV;
        }
        final double size = Math.max(bounds[3] - bounds[0],
                Math.max(bounds[4] - bounds[1], bounds[5] - bounds[2]));
        if (size <= 4.0) {
            return SUBDIV;   // 1/16
        }
        if (size <= 12.0) {
            return 8;        // 1/8
        }
        return 4;            // 1/4
    }

    /**
     * **壳的最小厚度**（子格数，沿法线向内）。
     *
     * <p>薄壳落地会**陷进地面**并**弹跳**：一层子格的壳在大质量下压不住，
     * 而穿透修正会过冲。向内加厚把接触变稳，成本仍是 O(表面)。
     */
    private static final int SHELL_THICKNESS = 8;

    /** 摩擦（从方块材质读是后续改进；先给一个合理的固定值，但**不再和体积混在一起**）。 */
    private static final double FRICTION = 0.6;

    /** 弹性。0 = 不弹（我们看到的"弹跳"来自穿透修正过冲，不是弹性）。 */
    private static final double RESTITUTION = 0.0;

    /** 每格盒子数提示上限（体素路径无刚体/约束开销，不必为省盒子牺牲精度）。 */
    private static final int MAX_BOXES_PER_BLOCK = 4096;

    /**
     * **体素路的方块格数上限**。超了就回退盒子方案。
     *
     * <p>512 格 ≈ 一个 8 方块见方的实体；再大就用盒子（盒子方案的规模是"面片数"级，
     * 有界且可预期）。这个数是**防爆炸的闸门**，不是性能调优参数。
     */
    private static final long MAX_BLOCKS = 512;

    /** **体素路的总盒子数上限**（防止"格数不多但每格盒子极多"的情况）。 */
    private static final long MAX_TOTAL_BOXES = 200_000;

    /** 只警告一次，避免每次重建都刷屏（刷屏本身也会拖慢日志）。 */
    private static boolean warned;

    private static void warnOnce(final int blocks, final long boxes) {
        if (warned) {
            return;
        }
        warned = true;
        SablestopNow.LOGGER.warn("[sablestopnow] 实体过大，体素碰撞路回退到盒子方案"
                + "（方块格 {} 上限 {}，子盒 {} 上限 {}）。"
                + "这是**防爆炸闸门**：体素路每格一个碰撞体，大实体上会到百万级盒子。",
                blocks, MAX_BLOCKS, boxes, MAX_TOTAL_BOXES);
    }

    /** 每个实体当前挂着的装置，用于重建时先拆掉（不拆就会泄漏 + 旧碰撞残留）。 */
    private static final Map<UUID, Attached> BY_SOLID = new HashMap<>();

    /**
     * 曲面实体当前挂着的**凸包碰撞体**句柄（自建原生库提供的能力）。
     *
     * <p>⚠⚠ **必须跟踪**：凸包是直接挂在**子结构自己的刚体**上的（不像装置有 id 可以整块拆 ✗），
     * 每次重建都会新增一个 ✗ → 不拆就会**每次重建漏一个碰撞体** ✓
     * （表现为：编辑几次之后碰撞体越堆越多、开始抖、最后乱飞 ✗）
     */
    private static final Map<UUID, long[]> CONVEX_BY_SOLID = new HashMap<>();
    // Per-body mass parts contributed by our solids (body UUID -> solid UUID -> part).
    // Sable's mass tracker only counts BLOCKS, so our solids contributed ZERO mass:
    // measured total mass of a phi12 cylinder + sphere structure was 0.5000 (one block).
    // Read by RapierPhysicsPipelineMixin - the single place where Sable uploads mass.
    // Strategy A: remember (structureBodyId, solidBodyId) per solid so the tick can watch them.
    // Purpose: the sub-level gets removed with "extreme Y coordinate range" - a SYMPTOM.
    // Watching the structure body's Y each second tells us WHICH mechanism:
    //   smooth descent -> it is not colliding (falls through the world)
    //   sudden jump    -> the joint/mass is dragging it
    private static final Map<UUID, int[]> A_BODIES = new HashMap<>();

    /** Called from the server tick: log the Y of every strategy-A pair so we can see it move. */
    public static void logABodies(final net.minecraft.server.level.ServerLevel level) {
        if (A_BODIES.isEmpty() || level == null) {
            return;
        }
        final long scene = Rapier3D.getSceneHandle(level);
        if (scene == 0L) {
            return;
        }
        for (final Map.Entry<UUID, int[]> e : A_BODIES.entrySet()) {
            final double[] st = SablePhysicsExt.bodyState(scene, e.getValue()[0]);
            final double[] so = SablePhysicsExt.bodyState(scene, e.getValue()[1]);
            if (st != null && st.length >= 11 && so != null && so.length >= 11) {
                SablestopNow.LOGGER.warn("[sablestopnow][watch] solid={} structY={} structVY={}"
                                + " solidY={} solidVY={} sep={}",
                        e.getKey().toString().substring(0, 8),
                        fmt(st[2]), fmt(st[5]), fmt(so[2]), fmt(so[5]),
                        fmt(Math.abs(st[2] - so[2])));
            }
        }
    }
    private static final Map<Integer, Map<UUID, SolidMassComposition.Part>>
            PARTS_BY_BODY = new HashMap<>();

    /** Mass parts contributed by every solid attached to this body (for mass composition). */
    public static java.util.List<SolidMassComposition.Part> partsForBody(
            final int mountId) {
        final Map<UUID, SolidMassComposition.Part> m =
                PARTS_BY_BODY.get(mountId);
        return m == null ? java.util.List.of() : new java.util.ArrayList<>(m.values());
    }

    /**
     * 曲面实体是否走**凸包路**（而不是体素路）。
     *
     * <p>实测对比（`depends/collision-bench`，φ12×高3 圆柱**侧面落地**）：
     * <pre>
     *   体素楼梯：8.561 ms/步、12593 接触对、陷地 0.063、弹跳 9 次   ✗
     *   凸包    ：0.008 ms/步、    1 接触对、陷地 0.001、弹跳 1 次   ✓  快 1000 倍
     * </pre>
     *
     * <p>⚠ **质量不受影响** ✓：质量与惯量由 {@code RapierPhysicsPipelineMixin} 拦
     * {@code setMassPropertiesFrom} 后经 {@code ScaledMass.upload} 显式设定（来自 Sable 的
     * 质量跟踪器）✓ —— **和碰撞体的形状、体积无关** ✓ 所以换几何不会改变质量，
     * 不会出现"变轻了被弹飞"✗。这里的凸包一律 {@code density(0)}，只提供几何 ✓。
     *
     * <p>要切回体素路把它改成 {@code false} 即可 ✓（体素路完整保留 ✓）。
     */
    private static final boolean USE_CONVEX = true;

    /**
     * 凸包的**圆角半径**（方块）。**当前取 0 = 普通凸包** ✗ —— 圆角这条路**实测更糟** ✓
     *
     * <h2>⚠ 实测记录（很重要，别再走一遍）</h2>
     *
     * 出发点是对的 ✓：凸包是有限面数的多面体 ✗，停在两个面的交界（棱）上时接触法线
     * 在相邻面之间**跳变** ✓ 表现为"浅陷但弹" ✓（用户实测圆柱面、球面都会 ✓）。
     *
     * 于是我按 1/32 方块用了 {@code round_convex_hull}（棱→圆角 ✓ 法线连续 ✓）。
     * **结果反而变差** ✗：从"浅陷 <1/4"变成"**陷入达 1 格**"✗✗
     *
     * 推测（未证实 ✗）：圆角形状在 Rapier 里是"**内芯形状 + 半径**"的分解 ✓，
     * 它的 CCD / 接触处理路径与普通凸包不同 ✗ —— 可能在高速时更容易穿进去 ✓。
     *
     * <h2>下一步该查什么（下一个接手的人看这里）</h2>
     *
     * "陷入 1 格"这个量级**大于**圆角半径 32 倍 ✗，所以它多半不是接触刚度问题 ✗，
     * 而更像"**碰撞多面体比可见曲面小**"✓：
     * 球体的网格本身就是多面体 ✗，它的**可见表面在面片之外**（矢高 sagitta ✓），
     * 网格越粗、这个差越大 ✓ —— 球半径 6、4 段时矢高约 1.76 格 ✓ ≈ 观测到的 1 格 ✓✓
     * **那样的话正解是提高网格细分** ✓（而不是动碰撞体的半径 ✗）。
     *
     * 判定方法：把网格的顶点数与包围盒打出来，和"多面体近似球/圆柱"的理论矢高
     * {@code R·(1-cos(π/N))} 对一下 ✓ —— 对得上就确认是这个原因 ✓。
     *
     * <p>取正数即启用圆角 ✓（想再对比时改这里 ✓ **不用重编 Rust** ✓）
     *
     * <h2>⚠ 2026-10-03 重测：从 0.0 改为 1/32</h2>
     *
     * 我早前试过 1/32 并记下"更糟"✗ —— 但**那个结论已被污染** ✗✓：
     * 当时轨迹实测 <b>质量 = 0.5</b>（339 格体积配 0.5 质量 ⇒ 等效密度 ~0.0015）✗，
     * 也就是说**任何几何改动都在一个质量完全错误的状态下被测** ✗，结论不可用 ✓。
     * 质量合成修好（实测 223.0824 ✓）之后，这条必须**重测** ✓。
     *
     * <h2>为什么要圆角（对应已知的 Rapier 缺陷）</h2>
     *
     * Rapier 官方示例 {@code debug_cylinder3.rs} 的注释原文：
     * <i>"cylinder in contact with a very large but very thin cuboid. In this case the EPA
     * returns an incorrect contact normal, resulting in the cylinder falling through the
     * floor."</i> ✓
     *
     * 我们的地面是 Sable 的 {@code LevelCollider}，其 AABB 是 <b>±3e7</b>（{@code collider.rs}
     * 里 {@code WORLD_SIZE = 30_000_000.0}）—— "very large" 到了极致 ✓；而我们的凸包是
     * **24 段棱柱，上下是纯平面** ✗ —— 正是触发 EPA 退化情形的输入 ✓。
     *
     * 给 hull 一个极浅的圆角（{@code round_convex_hull}）会把"平面 vs 平面"退化成
     * "曲面 vs 平面"✓，从输入侧绕开这个公认的病态情形 ✓ —— 这比移植 Rapier 的
     * {@code contact_skin} / Block Solver 便宜得多 ✓（后者我们已确认在 fork 里全部缺失 ✗）。
     */
    private static final double CONVEX_BORDER_RADIUS = 1.0 / 32.0;

    /**
     * **凸包内缩量（方块）** —— 把每个顶点沿"从局部中心向外的方向"往里收这么多 ✓。
     *
     * <h2>为什么要有它</h2>
     *
     * 实测（逐物理步轨迹 ✓ 可复现 ✓）：把 1 格结构放到圆柱上底面后，
     *   圆柱刚体 y = 64.4974，结构刚体 y = 67.4782
     *   按凸包 cap 在局部 +1.5 推算，结构中心应在 66.4974 ⇒ **偏高 0.98 ≈ 1 格** ✓
     * 而且**每个面都偏同样的量** ⇒ 表现就是"一圈均匀空隙" ✓
     *
     * <p>排查过程：`[frame]` 实测证明**凸包与 mesh 尺寸完全一致**（x 11.377 / y 2.000 ✓），
     * 所以空隙不来自我们的几何复制 ✗；追到 Sable 的 `dispatcher.rs`
     * `static_world_vs_collider`（把体素方块变成长方体去和外来凸包算接触的那条路 ✓），
     * 尝试补上 `collider.rs` 里那个 `max + 1.0` 的含端约定 ✗ ——
     * **实测回归**（空隙没修好 ✓ 反而出现陷地弹跳 ✗）⇒ 已回退 ✓。
     *
     * <p>于是改走**补偿**路线 ✓：既然接触面系统性地落在外面 ✓，
     * 就把我们的凸包【往里收】同样的量 ✓ —— 视觉与碰撞对齐 ✓ 而不去动 Sable 的物理 ✓。
     *
     * <h2>取值</h2>
     * 0.5 = 每个面往里收半格（总尺寸减 1 格 ✓）—— 正对上面测到的 0.98 ✓
     * 想关掉就设 0.0 ✓（不影响其它逻辑 ✓）
     */
    private static final double HULL_INSET = 0.5;

    /**
     * 实体碰撞体的**密度**（质量 = 体积 × 密度）。
     *
     * <p>取 1.0 = **和盒子路同一个约定** ✓（SolidCollisionService 里就是 olume × 1.0 ✓）
     * —— 质量的定义只能有一处 ✓
     *
     * <p>⚠ 原来凸包是 density(0) ✗ → **实体一点都不贡献质量** ✗
     * 实测：整个结构（φ12 大圆柱 + 球）的总质量只有 **0.5000** ✗（就是那一个方块 ✓）
     * → 又大又轻 → 任何接触冲量都把它打得乱飞 ✓ 这就是'停不下来'的根因 ✓
     */
    private static final double SOLID_DENSITY = 1.0;

    /** 一个实体当前的装置状态。 */
    private record Attached(int contraptionId, List<Integer> voxelIndices) {
    }

    /** 某个实体是否走体素路（曲面）。平面实体仍走盒子路：那更省、而且没有阶梯误差。 */
    public static boolean usesVoxelPath(final Solid solid, final Sketch sketch) {
        if (solid == null || sketch == null) {
            return false;
        }
        if (solid.isRevolution()) {
            return true; // 回转体一定是曲面
        }
        return solid.extrusion() != null
                && com.ovo.sablestopnow.sketch.Extrude.hasCurvature(sketch);
    }

    /**
     * 为一个实体建（或重建）体素碰撞装置。
     *
     * @return 盒子数（喂进去的子盒总数）；实体不可用时返回 0
     */
    public static int rebuild(final ServerLevel level, final UUID body, final ServerSubLevel sub,
                              final Solid solid, final Sketch sketch) {
        if (level == null || sub == null || solid == null || sketch == null) {
            return 0;
        }
        // 先拆旧的：不拆会泄漏（每次重建都新增装置与形状下标）+ 旧碰撞残留
        remove(level, solid.id());

        final SolidMesh.Mesh mesh = solid.mesh(sketch);
        if (mesh.isEmpty()) {
            return 0;
        }

        // ★ 凸包路：不再体素化 ✓（体素路完整保留在下面，改 USE_CONVEX 即可切回）
        if (USE_CONVEX) {
            return attachConvex(level, sub, solid, sketch, mesh);
        }

        final long t0 = System.nanoTime();
        // ⚠ **不填粗实心**。我加过一版"洪水填充内部"来修穿透，结果是**错的方向**：
        //   方块数据是**逐方块格**的，所以填内部必然是 O(体积) —— 实测一个 12 直径圆柱
        //   的壳只有约 190 格，填内部后变成 **459 格**，格数与盒子数都翻了倍 ✗
        //   （log：`方块格=459 子盒=2412`，三个实体合计 1171 格 / 6934 盒）。
        //   穿透改用**加厚**（O(表面)）+ 正确体积解决，见下面。
        final List<SolidCollision.BlockShape> blocks =
                SolidCollision.voxelizeToBlocks(mesh, subdivFor(SolidMesh.bounds(mesh)), 
                        MAX_BOXES_PER_BLOCK, SHELL_THICKNESS, false);
        if (blocks.isEmpty()) {
            return 0;
        }
        // ⚠⚠ **预算闸门：超预算就放弃体素路，让调用方回退到盒子方案。**
        //
        //   用户实测："编辑完草图、或用手杖拖动一个**大圆柱**时 MSPT 来到十几万，
        //   几分钟后大圆柱消失，但卡顿仍在。"
        //   根因就是这里**没有上限**：体素路是"**每个方块格一个碰撞体**"，
        //   而一个 20 方块的圆柱约 20×20×20 = 8000 格，每格最多 4096 个子盒 ——
        //   上百万个盒子 + 几千个碰撞体，全部在**一次重建**里做完 ✗
        //   而且中途失败会留下**已分配但没登记**的碰撞体（泄漏），这解释了
        //   "物体消失了卡顿还在"。
        //
        //   所以：**先把总量算清楚再决定做不做**，而不是边做边发现做不完。
        long totalBoxes = 0;
        for (final SolidCollision.BlockShape b : blocks) {
            totalBoxes += b.boxes().size();
        }
        if (blocks.size() > MAX_BLOCKS || totalBoxes > MAX_TOTAL_BOXES) {
            warnOnce(blocks.size(), totalBoxes);
            PhysicsDiag.budgetAbort(blocks.size(), totalBoxes);
            return 0; // 不分配任何碰撞体 → 调用方走盒子方案（有界）
        }
        // 3) 重基：装置的局部坐标从 0 开始（段坐标必须非负）
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (final SolidCollision.BlockShape b : blocks) {
            minX = Math.min(minX, b.bx());
            minY = Math.min(minY, b.by());
            minZ = Math.min(minZ, b.bz());
        }
        final List<SolidCollision.BlockShape> localBlocks = new ArrayList<>(blocks.size());
        for (final SolidCollision.BlockShape b : blocks) {
            localBlocks.add(new SolidCollision.BlockShape(b.bx() - minX, b.by() - minY,
                    b.bz() - minZ, b.boxes()));
        }

        final long scene = Rapier3D.getSceneHandle(level);
        if (scene == 0L) {
            return 0;
        }
        final List<Integer> indices = new ArrayList<>(localBlocks.size());
        int boxCount = 0;
        double totalVolume = 0.0;
        // 先算所有格子的"盒子体积"总和，作为**分摊比例的分母**
        final double[] cellBoxVolume = new double[localBlocks.size()];
        for (int i = 0; i < localBlocks.size(); i++) {
            double v = 0.0;
            for (final double[] box : localBlocks.get(i).boxes()) {
                v += Math.max(0.0, box[3] - box[0]) * Math.max(0.0, box[4] - box[1])
                        * Math.max(0.0, box[5] - box[2]);
            }
            cellBoxVolume[i] = v;
            totalVolume += v;
        }
        // ⚠⚠ **形状的 volume 参数要给"该格所代表的实体体积"，而不是它自己的盒子体积。**
        //
        //   为什么：`newVoxelCollider(friction, volume, ...)` 的 volume 是物理侧算质量/惯量的
        //   依据。薄壳的盒子体积 ≈ 表面积×厚度，**远小于实体体积** →
        //   直接传它会让质量被严重低估（我第一版甚至硬编码成 1.0）✗
        //
        //   正确做法：用**解析真实体积**（`solid.volume(sketch)`，帕普斯定理，精确）
        //   按各格盒子体积的**占比**分摊 ✓ 于是**各格体积之和 = 真实体积** ✓
        //   质量就精确了 —— 而且**不需要填实心**（格数保持 O(表面)）✓✓
        final double trueVolume = solid.volume(sketch);
        final boolean useTrue = !Double.isNaN(trueVolume) && trueVolume > 1.0e-9;
        final double assignTotal = useTrue ? trueVolume : totalVolume;
        double assigned = 0.0;
        for (int i = 0; i < localBlocks.size(); i++) {
            final SolidCollision.BlockShape b = localBlocks.get(i);
            final double share = totalVolume <= 1.0e-12
                    ? assignTotal / Math.max(1, localBlocks.size())
                    : assignTotal * (cellBoxVolume[i] / totalVolume);
            assigned += share;
            final int idx = Rapier3DAccessor.sablestopnow$newVoxelCollider(FRICTION, share,
                    RESTITUTION, false, null);
            if (idx < 0) {
                continue;
            }
            indices.add(idx);
            for (final double[] box : b.boxes()) {
                Rapier3D.addVoxelColliderBox(idx, box);
                boxCount++;
            }
        }
        totalVolume = assigned;
        if (indices.isEmpty()) {
            return 0;
        }

        // 4) 装置：挂到子结构自己的刚体上（同刚体 → 与子结构自身方块重合无害）
        final int mountId = Rapier3D.getID(sub);
        final int id = Rapier3D.nextBodyID();
        Rapier3DAccessor.sablestopnow$createKinematicContraption(scene, mountId, id,
                new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0});

        // 5) 段数据。索引分配用一个自增游标：packSections 会按格回调一次
        final int[] next = {0};
        final Map<Long, int[]> sections = SolidCollision.packSections(localBlocks,
                shape -> next[0] < indices.size() ? indices.get(next[0]++) : -1);
        for (final Map.Entry<Long, int[]> e : sections.entrySet()) {
            final long key = e.getKey();
            final int sx = (int) ((key >> 42) & 0x1FFFFF);
            final int sy = (int) ((key >> 21) & 0x1FFFFF);
            final int sz = (int) (key & 0x1FFFFF);
            // 段坐标是 21 位补码打包的，取回时要还原符号
            Rapier3DAccessor.sablestopnow$addKinematicContraptionChunkSection(scene, id,
                    sign(sx), sign(sy), sign(sz), e.getValue());
        }

        // 6) 位姿：`setLocalBounds` 前**必须**调它，否则步进时 panic。
        //    ⚠ 第三个参数是**装置自己的质心**（合成装置 = 0），传父体质心会把接触
        //      生成到几百万格之外（我踩过：装置在面前却完全碰不到）。
        //    ⚠ 位姿是**相对父体质心**的（Sable 就是 pos.sub(parentCenterOfMass)）。
        final int maxX = localBlocks.stream().mapToInt(SolidCollision.BlockShape::bx).max()
                .orElse(0) + 1;
        final int maxY = localBlocks.stream().mapToInt(SolidCollision.BlockShape::by).max()
                .orElse(0) + 1;
        final int maxZ = localBlocks.stream().mapToInt(SolidCollision.BlockShape::bz).max()
                .orElse(0) + 1;
        final Vector3dc com = sub.getMassTracker().getCenterOfMass();
        final double comX = com == null ? 0.0 : com.x();
        final double comY = com == null ? 0.0 : com.y();
        final double comZ = com == null ? 0.0 : com.z();
        Rapier3DAccessor.sablestopnow$setKinematicContraptionTransform(scene, id,
                new double[]{0.0, 0.0, 0.0},
                new double[]{minX - comX, minY - comY, minZ - comZ, 0.0, 0.0, 0.0, 1.0},
                new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0});
        Rapier3DAccessor.sablestopnow$setLocalBounds(scene, id, 0, 0, 0, maxX, maxY, maxZ);

        BY_SOLID.put(solid.id(), new Attached(id, indices));
        PhysicsDiag.rebuild("体素路", localBlocks.size(), boxCount, indices.size(),
                (System.nanoTime() - t0) / 1_000_000L);
        SablestopNow.LOGGER.info("[sablestopnow] 体素碰撞: solid={} 方块格={} 子盒={} 装置={} 体积={}",
                solid.id(), localBlocks.size(), boxCount, id,
                String.format(java.util.Locale.ROOT, "%.1f", totalVolume));
        // 体积对照：给物理的体积 vs 解析真实体积（trueVolume 在上面已算过，直接复用）
        if (!Double.isNaN(trueVolume) && trueVolume > 1.0e-6) {
            PhysicsDiag.volumeCompare(solid.id().toString(), totalVolume, trueVolume);
        }
        return boxCount;
    }

    /** 日志用的三位小数格式化（固定 Locale，避免某些地区用逗号当小数点 ✗）。 */
    private static String fmt(final double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** 21 位补码 → 有符号。 */
    private static int sign(final int v) {
        return (v & 0x100000) != 0 ? v - 0x200000 : v;
    }

    /**
     * 把实体的网格挂成**凸包碰撞体**（自建原生库的 {@code SablePhysicsExt.attachConvex}）。
     *
     * <h2>质量：这里一个字都不动 ✓</h2>
     *
     * 质量与惯量**不是**从这里来的 —— 它们由 {@code RapierPhysicsPipelineMixin} 拦
     * {@code setMassPropertiesFrom} 后经 {@code ScaledMass.upload} 显式设定（源头是 Sable 的
     * 质量跟踪器）✓ 所以换碰撞几何**不会**改变质量 ✓ 不会"变轻被弹飞"✗。
     * 这里的凸包一律 {@code density(0)}（在 Rust 侧写死），**只提供几何、不贡献质量** ✓。
     *
     * <h2>坐标：必须减质心 ✓</h2>
     *
     * 刚体局部是**质心系** —— Sable 生成接触时算的就是 {@code local - center_of_mass}
     * （{@code marten/src/algo.rs} L38-43），{@code collider.rs} L120 也把 {@code local_com}
     * 设成 ZERO ✓。**实机已用同一个约定验证成功** ✓（体素碰撞体实验里减质心的位置"有碰撞正常"）。
     * 不减的话碰撞体会落到几百万格之外（plot 坐标是 2e7 量级 ✗）。
     *
     * <h2>⚠ 已知限制：凸包表示不了洞</h2>
     *
     * 这里传的是**整个网格的顶点**，所以 {@code convex_hull} 会取**单个凸包** ✓ ——
     * 对凸实体（圆柱、棱柱、凸拉伸体）是**精确**的 ✓；但对**带孔的结构**（环面、管子）
     * 会把洞**填实** ✗。那时需要**凸分解**（{@code parry3d} 自带
     * {@code convex_decomposition}）后分多次调用 ✓ —— 这是后续工作 ✓。
     *
     * @return 喂进凸包的顶点数（已去重）；不可用时返回 0
     */
    private static int attachConvex(final ServerLevel level, final ServerSubLevel sub,
                                    final Solid solid, final Sketch sketch,
                                    final SolidMesh.Mesh mesh) {
        final long scene = Rapier3D.getSceneHandle(level);
        if (scene == 0L) {
            return 0;
        }
        final int mountId = Rapier3D.getID(sub);

        final var tracker = sub.getMassTracker();
        final org.joml.Vector3dc com = tracker == null ? null : tracker.getCenterOfMass();
        final double cx = com == null ? 0.0 : com.x();
        final double cy = com == null ? 0.0 : com.y();
        final double cz = com == null ? 0.0 : com.z();

        // ===== FRAME DIAGNOSTIC: where does the systematic +0.5 in Y come from? =====
        // Measured facts (hull diagnostics): span y=[-0.500, 1.500], centre y = +0.500,
        // while centre x/z are ~0.1 (normal). So the hull sits HALF A BLOCK too high in Y.
        // That single offset explains both remaining symptoms:
        //   - another physics structure placed on top rests with a visible ~1 block gap
        //   - the hull cannot reach the ground -> the solver pulls it in, then corrects
        //     -> the sink-then-eject cycle
        //
        // The subtraction below is correct in form (mesh and COM are both plot-absolute,
        // so their difference is body-local). So the +0.5 must come from the SOURCES:
        // either the mesh vertex Y uses block CORNERS while the tracker COM uses block
        // CENTRES, or the reverse. This block prints both raw ranges so the discrepancy
        // is read directly instead of guessed.
        {
            double rawYLo = Double.MAX_VALUE;
            double rawYHi = -Double.MAX_VALUE;
            double rawXLo = Double.MAX_VALUE;
            double rawXHi = -Double.MAX_VALUE;
            for (final SolidMesh.Vertex v : mesh.vertices()) {
                final org.joml.Vector3d q = v.position();
                rawYLo = Math.min(rawYLo, q.y);
                rawYHi = Math.max(rawYHi, q.y);
                rawXLo = Math.min(rawXLo, q.x);
                rawXHi = Math.max(rawXHi, q.x);
            }
            SablestopNow.LOGGER.warn("[sablestopnow][frame] mesh raw  x=[{},{}] y=[{},{}]"
                            + "  | COM=({},{},{})  | mesh-COM y=[{},{}] centre={}",
                    fmt(rawXLo), fmt(rawXHi), fmt(rawYLo), fmt(rawYHi),
                    fmt(cx), fmt(cy), fmt(cz),
                    fmt(rawYLo - cy), fmt(rawYHi - cy), fmt((rawYLo + rawYHi) * 0.5 - cy));
        }        // 顶点去重：网格的顶点重复率很高 ✗，而且 convex_hull 的构造成本随点数上升 ✓
        // 用"量化到 1e-4"当去重键（同一位置的重复顶点合成一个 ✓）
        final java.util.LinkedHashMap<Long, double[]> unique = new java.util.LinkedHashMap<>();
        for (final SolidMesh.Vertex v : mesh.vertices()) {
            final org.joml.Vector3d p = v.position();
            final double lx = p.x - cx;
            final double ly = p.y - cy;
            final double lz = p.z - cz;
            final long key = (Math.round(lx * 1.0e4) * 73856093L)
                    ^ (Math.round(ly * 1.0e4) * 19349663L)
                    ^ (Math.round(lz * 1.0e4) * 83492791L);
            unique.putIfAbsent(key, new double[]{lx, ly, lz});
        }
        if (unique.size() < 4) {
            return 0; // 凸包至少要 4 个顶点
        }
        final double[] points = new double[unique.size() * 3];
        // HULL_INSET: shrink the hull toward its own local centre. Compensation for the
        // systematic contact offset measured above; 0.0 disables it entirely.
        if (HULL_INSET > 0.0) {
            double ccx = 0.0, ccy = 0.0, ccz = 0.0;
            for (final double[] p : unique.values()) { ccx += p[0]; ccy += p[1]; ccz += p[2]; }
            final int n = Math.max(1, unique.size());
            ccx /= n; ccy /= n; ccz /= n;
            int w = 0;
            for (final double[] p : unique.values()) {
                final double dx = p[0] - ccx, dy = p[1] - ccy, dz = p[2] - ccz;
                // PER-AXIS inset, not a radial one.
                //
                // A radial inset (centre + d * (1 - INSET/|d|)) is dominated by the radius on
                // a cylinder: for a rim vertex at radius 6 with height +-1, |d| = 6.08 so the
                // radius shrinks by 0.5 while the height shrinks by only 0.08. That was
                // measured in game: the sides visibly came in, the top and bottom faces did
                // not move at all.
                //
                // Shrinking each axis independently by the same amount moves EVERY face by
                // exactly HULL_INSET, which is what a uniform gap needs.
                final double kx = Math.abs(dx) > 1.0e-9
                        ? Math.max(0.0, 1.0 - HULL_INSET / Math.abs(dx)) : 1.0;
                final double ky = Math.abs(dy) > 1.0e-9
                        ? Math.max(0.0, 1.0 - HULL_INSET / Math.abs(dy)) : 1.0;
                final double kz = Math.abs(dz) > 1.0e-9
                        ? Math.max(0.0, 1.0 - HULL_INSET / Math.abs(dz)) : 1.0;
                points[w++] = ccx + dx * kx;
                points[w++] = ccy + dy * ky;
                points[w++] = ccz + dz * kz;
            }
        } else {
            int i = 0;
            for (final double[] p : unique.values()) {
                points[i++] = p[0];
                points[i++] = p[1];
                points[i++] = p[2];
            }
        }

        // ⚠⚠ **顺序很重要**：先把跟踪器的质量写回去，**再**挂凸包 ✓
        //
        //   为什么：Rapier 的 `insert_with_parent(collider, parent, &mut rigid_body_set)`
        //   会**把该碰撞体的质量【加】到刚体上** ✓（不是覆盖 ✓）
        //   → 所以必须**先**设好跟踪器的质量（0.5 = 那个方块 ✓），**再**挂凸包，
        //     这样凸包的（真实密度算出的）质量才会**累加上去** ✓✓
        //   反过来（先挂再设）就会**把凸包的质量冲掉** ✗
        //   —— 实测就是这个顺序，导致质量一直是 **0.5000** ✗（诊断 native 读出来的 ✓）
        final var massTracker = sub.getMassTracker();
        if (massTracker != null && massTracker.getMass() > 0.0) {
            ScaledMass.upload(scene, mountId, massTracker,
                    sub.logicalPose().scale());
        }

        // ===== Strategy A: the solid owns its own body, welded by ONE fixed constraint =====
        //
        // Verified better in depends/collision-bench (20Hz, edge contact):
        //   B (mass composed onto the structure body): rest height 0.50 instead of 1.50 -> sinks 1.0 block
        //   A (own body + fixed joint)              : rest height 1.499, separation 0.000, stable at 40:1
        //
        // FRAME, derived from lib.rs createSubLevel: the pose is used DIRECTLY as the Rapier
        // translation, and a collider's coordinates are relative to that frame. Strategy B works
        // by attaching our points to the EXISTING body, so those points already live in that
        // body's LOCAL frame. Therefore the new body must sit at the SAME pose - then both local
        // frames coincide and the hull lands in exactly the same place.
        //
        // The earlier attempt passed the CENTRE OF MASS instead: the COM is an offset INSIDE the
        // body, not the body's pose, so the hull was displaced and Sable then removed the
        // sub-level with "extreme Y coordinate range".
        // ===== TEMPORARY GUARD: strategy A disabled until the setLocalBounds question is
        // resolved by reading its native implementation (see docs/dev-run-notes.md).
        // A crashed the client when committing the sketch (silent native death: no crash
        // report, no hs_err, log just stops). Suspects: setLocalBounds on a hull-only body,
        // or addFixedConstraint indexing sable_data.rigid_bodies.
        // Meanwhile fall back to B, which works mechanically (known 1-block sink only).
        // ===== FULL DIAGNOSTICS: one run should reveal everything =====
        {
            final double[] pLo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
            final double[] pHi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (int k = 0; k < points.length; k += 3) {
                for (int a = 0; a < 3; a++) {
                    pLo[a] = Math.min(pLo[a], points[k + a]);
                    pHi[a] = Math.max(pHi[a], points[k + a]);
                }
            }
            final double[] pose = new double[7];
            Rapier3D.getPose(scene, mountId, pose);
            final double[] st = SablePhysicsExt.bodyState(scene, mountId);
            SablestopNow.LOGGER.warn("[sablestopnow][diag] STRATEGY={} solid={} verts={}",
                    System.getProperty("sablestopnow.useA", "false"), solid.id(), unique.size());
            SablestopNow.LOGGER.warn("[sablestopnow][diag] hull span x=[{},{}] y=[{},{}] z=[{},{}]"
                            + " | centre=({},{},{})",
                    fmt(pLo[0]), fmt(pHi[0]), fmt(pLo[1]), fmt(pHi[1]), fmt(pLo[2]), fmt(pHi[2]),
                    fmt((pLo[0] + pHi[0]) * 0.5), fmt((pLo[1] + pHi[1]) * 0.5),
                    fmt((pLo[2] + pHi[2]) * 0.5));
            SablestopNow.LOGGER.warn("[sablestopnow][diag] getPose(mountId={}) = pos({},{},{})"
                            + " quat({},{},{},{})",
                    mountId, fmt(pose[0]), fmt(pose[1]), fmt(pose[2]),
                    fmt(pose[3]), fmt(pose[4]), fmt(pose[5]), fmt(pose[6]));
            SablestopNow.LOGGER.warn("[sablestopnow][diag] massTracker COM=({},{},{})",
                    fmt(cx), fmt(cy), fmt(cz));
            if (st != null && st.length >= 11) {
                SablestopNow.LOGGER.warn("[sablestopnow][diag] struct body: mass={} pos=({},{},{})"
                                + " colliders={}",
                        fmt(st[0]), fmt(st[1]), fmt(st[2]), fmt(st[3]), (int) st[10]);
            }
        // ============================================================================
// A/B selection as a LABELED BLOCK - critical structural detail:
//   the B branch must NOT return early, because everything AFTER the A block
//   (record mass part / compose+upload mass / CONVEX_BY_SOLID bookkeeping) has
//   to run for BOTH strategies.
//   An early return here silently skipped all of it, which measured as:
//     - mass stuck at 0.500 while colliders was 2  -> a 339-block hull at mass 0.5
//       (effective density ~0.0015) = float / sink / |dvy| spikes of ~1728
//     - CONVEX_BY_SOLID never registered -> remove() could not detach the old
//       hull -> collider count grew 2 -> 3 on every rebuild
//   `break strategyBlock` skips only the A implementation and still falls through.
// ============================================================================
        }
        final long handle;
        strategyBlock: {
        if (Boolean.parseBoolean(System.getProperty("sablestopnow.useA", "false"))) {
            SablestopNow.LOGGER.warn("[sablestopnow] strategy A requested via -Dsablestopnow.useA=true");
        } else {
            final long h = SablePhysicsExt.attachConvex(scene, mountId, points, FRICTION,
                    CONVEX_BORDER_RADIUS, SOLID_DENSITY);
            // Start the per-PHYSICS-STEP trace (20Hz) of this body into its own CSV.
            // Rationale: one line per second is far too coarse - a sink-then-eject event can
            // span 2-3 physics steps, so per-second sampling misses it entirely. The trace
            // writes to <gamedir>/sablestopnow-hull-trace.csv and costs nothing when unused.
            if (h >= 0) {
                HullTrace.trace(mountId, scene);
            }
            if (h < 0) {
                return 0;
            }
            handle = h;
            // skip the A implementation but STILL run the mass/bookkeeping tail below
            break strategyBlock;
        }        final double[] bodyPose = new double[7];
        Rapier3D.getPose(scene, mountId, bodyPose);
        final int solidBodyId = Rapier3D.nextBodyID();
        final long created = SablePhysicsExt.createConvexBody(scene, solidBodyId, points,
                SOLID_DENSITY, bodyPose);
        handle = created;
        if (handle < 0) {
            SablestopNow.LOGGER.warn("[sablestopnow] createConvexBody 失败：solid={} mountId={} 顶点={} 返回={}",
                    solid.id(), mountId, unique.size(), handle);
            return 0;
        }
        // ⚠⚠ **必须声明 bounds** —— 少了这步 `Rapier3D.step` 会 panic ✗
        //
        //   崩溃报告原文（实机 ✓）：
        //     java.lang.RuntimeException: Rapier native panic:
        //         compute_local_aabb called before bounds were set
        //       at Rapier3D.step(Native Method)
        //
        //   这条规则我**早就写在 VoxelColliderTestService 的注释里** ✗
        //   （"漏了这步服务端会崩：compute_local_aabb called before bounds were set" ✓）
        //   建新刚体时却忘了照做 ✗ —— 于是重建瞬间巨大振动、一旋转就崩 ✓
        //
        //   单位是**方块**（装置的 local bounds 就用 0..16 表示一个 16³ 段 ✓）
        //   我们的顶点也是方块单位 ✓ 所以直接取整、再各留 1 格余量 ✓
        final double[] hullLo = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        final double[] hullHi = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int k = 0; k < points.length; k += 3) {
            for (int a = 0; a < 3; a++) {
                hullLo[a] = Math.min(hullLo[a], points[k + a]);
                hullHi[a] = Math.max(hullHi[a], points[k + a]);
            }
        }        int bx0 = (int) Math.floor(Math.min(hullLo[0], hullHi[0])) - 1;
        int by0 = (int) Math.floor(Math.min(hullLo[1], hullHi[1])) - 1;
        int bz0 = (int) Math.floor(Math.min(hullLo[2], hullHi[2])) - 1;
        int bx1 = (int) Math.ceil(Math.max(hullLo[0], hullHi[0])) + 1;
        int by1 = (int) Math.ceil(Math.max(hullLo[1], hullHi[1])) + 1;
        int bz1 = (int) Math.ceil(Math.max(hullLo[2], hullHi[2])) + 1;

        // setLocalBounds 已移除 —— 它对凸包【非法】，而且是我这次崩端的真凶 ✓
        //
        // 读 collider.rs L88-96 得到的事实：
        //   compute_local_aabb() 是 Sable **自己的 LevelCollider（体素形状）** 的方法 ✓
        //   而它唯一的调用方 update_collider_aabb 会先 as_shape::<LevelCollider>() ✓
        //   → 我们的凸包是 parry 的 SharedShape::convex_hull ✗ **根本不是 LevelCollider**
        //   → **凸包压根不需要 local bounds** ✗ 我当初加它是误判 ✓
        //
        // 而 setLocalBounds 的内部是：
        //   level_colliders.get_mut(&(id)).unwrap()      ← 对不存在的表项直接 panic ✗
        // 我们的刚体注册在 rigid_bodies（见 createConvexBody）✗ 不在 level_colliders
        // → unwrap 于 None → **原生层静默死亡**（无 Java 异常、无崩溃报告、日志直接断 ✓）
        //   —— 与你看到的"点完成就崩"完全一致 ✓
        //
        // 第一次那个 "compute_local_aabb called before bounds were set" 也是假象 ✗：
        // 那次 A 与 B 同时在跑（我的替换没生效），崩溃来自 B 的装置而非 A ✓

        // both anchors at the origin: the two bodies now share the same local frame,
        // so a zero-offset fixed joint is exact (no relative rotation either)
        A_BODIES.put(solid.id(), new int[]{mountId, solidBodyId});
        final long joint = Rapier3D.addFixedConstraint(scene, mountId, solidBodyId,
                0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0);

        // ⚠⚠ **把这一对刚体之间的接触关掉** —— 少了这行 A 方案必崩 ✓
        //
        //   原因：Sable 自己的注释写着"**同刚体**上的碰撞体之间永不互相碰撞" ✓
        //   策略 B 把凸包挂在**同一个**刚体上 ✓ 所以天然不互碰 ✓ 机械上是通的 ✓
        //   而 A 把凸包放到**另一个**刚体上 ✗ → **两个刚体照样互相碰** ✗✗
        //   → 凸包与结构自身的方块碰撞**几何重叠** ✓
        //   → 求解器互相推开 ✓ 约束又把它拉回来 ✓ → **约束与接触打架** ✓✓
        //   表现就是：抖动、穿透循环、偶尔乱飞 ✓（我早前那些症状全对上了 ✓）
        //
        //   ⚠ 为什么基准台没发现 ✗：我的对照组是"1×1×1 的小方块上放圆柱" ✓
        //     **几何不重叠** ✓ 所以台子上干净 ✗ 游戏里打架 ✓ —— 台子与现实的关键差别 ✓
        //
        //   而 Sable 自己就是这么做的 ✓：它的"无碰撞标记"用的是
        //   "transient no-effect joints (**contacts_enabled=false**)" ✓ 同一个机制 ✓
        Rapier3D.setConstraintContactsEnabled(scene, joint, false);

        // ⚠⚠⚠ **必须把质量重新设回去** —— 这是"先陷入地里再弹出来获得大量动量"的根因 ✓
        //
        //   Rapier 的 `ColliderSet::insert_with_parent(collider, parent, &mut rigid_body_set)`
        //   会**用该刚体的碰撞体重新计算它的质量** ✗（那个 `&mut rigid_body_set` 参数就是干这个的）。
        //   而我们的凸包是 `density(0)`（在 Rust 侧写死 ✓ 我们**不想**让形状决定质量 ✓，
        //   质量应由 Sable 的质量跟踪器决定 ✓）→ 于是刚体质量被重算成 **0** ✗✗
        //   → **零质量刚体遇任何接触都会产生巨大速度** ✓ = "弹出并获得大量动量" ✓
        //
        //   为什么**平面没事**：平面走盒子路（每个盒子是**独立刚体**，走 BoxPhysicsObject ✓），
        //   根本不经过这里 ✓ —— 症状只在曲面出现 ✓ 完全吻合 ✓
        //
        //   修法：复用管线**同一份**逻辑（`ScaledMass.upload`，见 RapierPhysicsPipelineMixin
        //   L133）把质量/惯量重新写回去 ✓ 用同一套定义，不另算 ✗
        //   ⚠ 每个实体加完都重设一次也是**必要**的 ✓：同一刚体上每加一个碰撞体都会再重算一次 ✓
        // ★ 诊断：把刚体在引擎里的真实状态读出来，用**我们自己的 logger** 打 ✓
        //   用户的关键线索是"圆柱+球放在【只有一个方块】的物理结构上"✗ ——
        //   如果质量 ≈ 1.0 ✗ 而碰撞体有好几个 ✓，就证实了
        //   "**实体没有把质量贡献给刚体**"✓（体素路靠形状的 volume 给质量 ✓
        //   而凸包是 density(0) ✗）→ 又大又轻 → 乱弹 ✓
        final double[] st = SablePhysicsExt.bodyState(scene, mountId);
        if (st != null && st.length >= 11) {
            SablestopNow.LOGGER.warn("[sablestopnow] 刚体状态 solid={} 挂载刚体={}"
                            + " 质量={} 位置=({}, {}, {}) 线速度=({}, {}, {})"
                            + " 角速度=({}, {}, {}) 该刚体上的碰撞体数={}",
                    solid.id(), mountId,
                    String.format(java.util.Locale.ROOT, "%.4f", st[0]),
                    fmt(st[1]), fmt(st[2]), fmt(st[3]),
                    fmt(st[4]), fmt(st[5]), fmt(st[6]),
                    fmt(st[7]), fmt(st[8]), fmt(st[9]),
                    (int) st[10]);
            if (st[0] < 2.0 && st[10] > 1.0) {
                SablestopNow.LOGGER.warn("[sablestopnow] ⚠⚠ 质量只有 {} 却有 {} 个碰撞体"
                                + " —— 实体没有把质量贡献给刚体 ✗ 这正是'又大又轻→乱弹'的根因 ✓",
                        String.format(java.util.Locale.ROOT, "%.4f", st[0]), (int) st[10]);
            }
        }

        }   // end strategyBlock

        // Record this solid's mass part (box approximation from the same points we fed the hull).
        // Mass = analytic volume x density (same convention as the box path).
        {
            double lox = Double.MAX_VALUE, loy = Double.MAX_VALUE, loz = Double.MAX_VALUE;
            double hix = -Double.MAX_VALUE, hiy = -Double.MAX_VALUE, hiz = -Double.MAX_VALUE;
            for (int k = 0; k < points.length; k += 3) {
                lox = Math.min(lox, points[k]); hix = Math.max(hix, points[k]);
                loy = Math.min(loy, points[k + 1]); hiy = Math.max(hiy, points[k + 1]);
                loz = Math.min(loz, points[k + 2]); hiz = Math.max(hiz, points[k + 2]);
            }
            final double vol = Math.max(1.0e-6, solid.volume(sketch));
            final double mass = vol * SOLID_DENSITY;
            PARTS_BY_BODY.computeIfAbsent(mountId, k -> new HashMap<>())
                    .put(solid.id(), SolidMassComposition.box(
                            mass, (lox + hix) * 0.5, (loy + hiy) * 0.5, (loz + hiz) * 0.5,
                            Math.max(1.0e-3, hix - lox), Math.max(1.0e-3, hiy - loy),
                            Math.max(1.0e-3, hiz - loz)));
        }        // ★★ Compose mass/inertia NOW and upload it ourselves.
        //
        // WHY NOT RELY ON THE MIXIN: Sable uploads mass at ASSEMBLY time, which is BEFORE our
        // solids get their hulls. So when the mixin runs, PARTS_BY_BODY is still empty and the
        // composition is skipped - and Sable never uploads again afterwards.
        // Measured: mass stayed 0.5000 (one block) even after the key fix.
        {
            final java.util.List<SolidMassComposition.Part> parts = partsForBody(mountId);
            final var tr = sub.getMassTracker();
            if (tr != null && !parts.isEmpty()) {
                final org.joml.Vector3dc bc = tr.getCenterOfMass();
                final org.joml.Matrix3dc bi = tr.getInertiaTensor();
                final java.util.List<SolidMassComposition.Part> all = new java.util.ArrayList<>();
                // ⚠⚠⚠ **坐标系必须统一** —— 这一行曾把转动惯量放大 1e17 倍 ✗✗
                //
                // tr.getCenterOfMass() 返回的是 **plot 绝对坐标**（~2.048e7，实测 ✓），
                // 而我们的实体部分（points 的 AABB 中心）是 **刚体局部坐标**（±6 ✓）。
                // 两者一起喂进 parallel-axis 定理，d 就变成 ~2e7，d^2 ~ 4e14，
                // m*d^2 ~ 1.7e17 —— 于是刚体的角响应被冻住 ⇒
                //   「任意时候都难以旋转」（每次弹跳只转 1~2 度）✗
                //   「陷入-弹出循环」（求解器无法把物体转出穿透）✗
                //
                // 正确做法：**刚体坐标系的原点就是质心** ✓（Rapier 的约定，见 marten 的
                // algo.rs：collider 坐标 = local - center_of_mass，且 collider.rs 把
                // local_com 设为 Vec3::ZERO）。所以跟踪器在【刚体局部系】里的质心就是
                // (0,0,0) ✓ —— 用它的 plot 绝对坐标是错的 ✗。
                //
                // 注意：质量本身不受影响（它只是求和 ✓），所以 mass 一直是"看起来对"的
                // （实测 408.484 ✓）—— 只有惯量错了 ✗。这也是为什么此前一直没发现 ✓。
                all.add(new SolidMassComposition.Part(tr.getMass(),
                        0.0, 0.0, 0.0,
                        bi.m00(), bi.m11(), bi.m22(), bi.m01(), bi.m02(), bi.m12()));
                all.addAll(parts);
                final SolidMassComposition.Result res = SolidMassComposition.combine(all);
                if (res != null) {
                    ScaledMass.upload(scene, mountId, new SolidMassComposition.Composed(res),
                            sub.logicalPose().scale());
                    SablestopNow.LOGGER.warn("[sablestopnow] mass composed: tracker={} + solids={} -> total={}",
                            fmt(tr.getMass()), parts.size(), fmt(res.mass()));
                }
            }
        }        CONVEX_BY_SOLID.put(solid.id(), new long[]{handle});
        SablestopNow.LOGGER.info("[sablestopnow] 凸包碰撞: solid={} 挂载刚体={} 顶点={} 句柄={}",
                solid.id(), mountId, unique.size(), Long.toHexString(handle));
        return unique.size();
    }

    /** 拆掉某个实体的装置（含体素碰撞体本身）。 */
    public static void remove(final ServerLevel level, final UUID solidId) {
        // 先拆凸包（自己挂的，得自己拆 ✓）
        final long[] convex = CONVEX_BY_SOLID.remove(solidId);
        if (convex != null && level != null) {
            final long scene = Rapier3D.getSceneHandle(level);
            if (scene != 0L) {
                for (final long h : convex) {
                    SablePhysicsExt.detachConvex(scene, h);
                }
            }
        }
        for (final Map<UUID, SolidMassComposition.Part> m
                : PARTS_BY_BODY.values()) {
            m.remove(solidId);
        }        final Attached a = BY_SOLID.remove(solidId);
        if (a == null || level == null) {
            return;
        }
        final long scene = Rapier3D.getSceneHandle(level);
        if (scene != 0L) {
            Rapier3DAccessor.sablestopnow$removeKinematicContraption(scene, a.contraptionId());
        }
        for (final int idx : a.voxelIndices()) {
            Rapier3D.clearVoxelColliderBoxes(idx);
        }
    }

    /** 拆掉一个结构上所有实体走体素路的装置。 */
    public static void clearBody(final ServerLevel level, final UUID bodyId,
                                 final List<Solid> solids) {
        if (solids == null) {
            return;
        }
        for (final Solid s : solids) {
            if (s.body().equals(bodyId)) {
                remove(level, s.id());
            }
        }
    }

    /**
     * 清掉**已经没有实体对应的**装置（实体被删掉了）。
     *
     * <p>⚠ 为什么不能只在 {@code clearBody} 里按活着的实体清：
     * 实体被删时它已经不在 {@code solids} 列表里了，那条路**看不见它**，
     * 装置会留下来 —— 既泄漏又留下**残留碰撞**（看不见却挡路）。
     * 所以要在重建时按"活着的 id 集合"反查。
     */
    public static int clearOrphans(final ServerLevel level, final UUID bodyId,
                                   final List<Solid> live) {
        if (level == null) {
            return 0;
        }
        final java.util.Set<UUID> alive = new java.util.HashSet<>();
        if (live != null) {
            for (final Solid s : live) {
                alive.add(s.id());
            }
        }
        final List<UUID> dead = new ArrayList<>();
        for (final UUID id : BY_SOLID.keySet()) {
            if (!alive.contains(id)) {
                dead.add(id);
            }
        }
        for (final UUID id : dead) {
            remove(level, id);
        }
        return dead.size();
    }

    /** 当前挂了装置的实体数（诊断用）。 */
    public static int attachedCount() {
        return BY_SOLID.size();
    }
}
