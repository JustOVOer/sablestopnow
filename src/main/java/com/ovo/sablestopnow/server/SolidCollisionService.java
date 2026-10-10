package com.ovo.sablestopnow.server;

import com.ovo.sablestopnow.sketch.Extrude;
import com.ovo.sablestopnow.sketch.Sketch;
import com.ovo.sablestopnow.sketch.SketchData;
import com.ovo.sablestopnow.sketch.Solid;
import com.ovo.sablestopnow.sketch.SolidCollision;
import com.ovo.sablestopnow.sketch.SolidData;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.api.physics.constraint.FixedConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.object.box.BoxPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 把拉伸实体接进 Sable 的物理。
 *
 * <h2>为什么是"盒子刚体 + 固定约束"</h2>
 * Sable 2.0.4 **只暴露盒体碰撞**（原生库 53 个 JNI 导出里没有 hull/convex 绑定，
 * 凸包与面片级碰撞都不可用；详见 {@code .dshwolf/STATUS.md} 的查证记录）。
 * 而 {@code PhysicsPipeline} 上能加的对象只有 {@code addBox} / {@code addRope}，
 * {@code BoxPhysicsObject} 的 {@code halfExtents} 就是**一个**盒子。
 *
 * <p>所以做法是：实体的每个碰撞盒 → 一个独立的盒子刚体 → 用**固定约束**焊到子结构刚体上。
 * {@code ServerSubLevel} 自己就实现了 {@code PhysicsPipelineBody}，正是要焊的那一端。
 *
 * <h2>⚠ 一个盒子 = 一个刚体 + 一条约束</h2>
 * 所以盒子数直接决定物理开销。轴对齐轮廓（矩形、L 形）的扫描线分解是精确最优的，盒子很少；
 * **圆/弧是瓶颈**（48 边形 → 48 个刚体 + 48 条约束）。
 * 压缩手段是**降低碰撞用的多边形分辨率**（渲染仍 48 段、碰撞用约 12 段）——
 * 复用已确立的「渲染 ≠ 碰撞」分离。
 *
 * <h2>⚠ 质量必须分摊</h2>
 * 盒子是**分解**出来的、不是独立物体。每个盒子都按满密度算质量，
 * 会把同一块材料重复计入，刚体的转动惯量会大得离谱。
 * 所以整块实体的质量按体积分摊（{@link SolidCollision#massPerBox}）。
 */
public final class SolidCollisionService {

    /**
     * 实体材料的密度（每立方格的质量）。
     *
     * <p>不像方块质量那样取自方块本身 —— 拉伸体没有方块。取 1.0 是个中性值
     * （介于木头与石头之间），要调手感就改这里。
     */
    public static final double DENSITY = 1.0;

    /** 一个已建的盒子刚体 + 它的固定约束。 */
    private record Tracked(BoxPhysicsObject box, PhysicsConstraintHandle joint) {
    }

    /** 按维度 → 子结构 → 已建刚体。重建时先整体清掉这一组。 */
    private static final Map<ResourceLocation, Map<UUID, List<Tracked>>> BY_DIMENSION = new HashMap<>();

    private SolidCollisionService() {
    }

    /** 本次服务器运行里**已经补建过碰撞**的 body ✓（成功才记 ✓ 失败留到下次 ✓）。 */
    private static final java.util.Set<UUID> SELF_HEALED = new java.util.HashSet<>();

    /**
     * ⚠⚠ **临时开关：自愈先关掉** ✗（2026-10-03 17:1x 实机事故 ✓）。
     *
     * <p>现象：把自愈挂上之后，**重进世界约 1 秒**客户端**硬死** ✗ ——
     * 没有崩溃报告 ✗ 没有 hs_err ✗ 日志停在 `Dev joined the game` 之后 ✗
     * （这是**原生层硬死**的签名 ✓ 项目里见过一次 ✓：策略 A 静默死亡 ✓）。
     *
     * <p>它跑的时刻与死亡时刻**完全吻合** ✓（`tick % 20 == 7` ⇒ 进世界约 1 秒 ✓），
     * 而且它是这一串里**最新**的改动 ✓ ⇒ 按"一次只改一个变量"先把它关掉 ✓✓。
     *
     * <p>⚠ 关掉它**不会丢功能** ✓：玩家编辑一次草图/实体仍会走原来的网络包路径
     * （`SketchNetworking` / `SolidNetworking` ✓）把碰撞建起来 ✓。
     * 代价：重开存档后**必须手动编辑一次**才有碰撞 ✗（这就是自愈想解决的那件事 ✓）。
     *
     * <p>重开条件（都满足才敢再打开 ✓）：① 能稳定复现 ✓；② 定位到是
     * `clear` / `attachConvex` / `detachConvex` 哪一步 ✗；③ 确认它**不能**在物理步内发生 ✓。
     */
    private static final boolean SELF_HEAL_ENABLED = true;

    /**
     * **碰撞自愈**：把"还没建上碰撞"的实体补建一次 ✓✓。
     *
     * <h2>为什么必须有它（2026-10-03 实测 ✗→✓）</h2>
     *
     * {@link #rebuild} 原来**只**被"玩家保存草图 / 保存实体"的网络包调用 ✗ ——
     * 于是**重开存档或重连之后，实体的碰撞根本没有被建回来** ✗✗：
     * <ul>
     *   <li>曲面实体（管子 / 圆环 / 球）**一点碰撞都没有** ✗ —— 别的结构直接穿过去 ✓；</li>
     *   <li>而且它**不贡献质量** ✗ —— 质量是在挂载那一刻记进
     *       {@code VoxelCollisionService.PARTS_BY_BODY} 的 ✓，
     *       不重建就没有那一条 ✓ ⇒ 手感就是用户报的「**质量不足**」✗✓。</li>
     * </ul>
     *
     * <p>实测证据 ✓：重开存档进世界后，日志里**一条 `凸分解` / `凸包碰撞` 都没有** ✗，
     * 而同一个实体在"编辑过一次草图"的那一局里是有的 ✓ —— 差别只在有没有那张网络包 ✓。
     *
     * <p>⚠ 只在**结构已经加载**（{@link #find} 能拿到）时才补建 ✓：
     * 结构没加载时 {@link #rebuild} 会直接返回（那是正常的"等它加载" ✓ 不是失败 ✗），
     * 我们**绝不能**把它记成"已处理" ✗ —— 否则它就永远补不上了 ✗✓。
     */
    public static void ensureCollisions(final ServerLevel level) {
        if (!SELF_HEAL_ENABLED || level == null) {
            return;
        }
        for (final Solid solid : SolidData.get(level).all().values()) {
            final UUID body = solid.body();
            // ★ 地面补丁：与"自愈的一次性记录"**无关** ✓ —— 它必须**每轮刷新** ✓
            //   （实体在动 ⇒ 补丁要跟着走 ✓；放在下面那个 continue 之后就会被永远跳过 ✗✓）
            final ServerSubLevel patchSub = find(level, body);
            if (patchSub != null) {
                GroundPatchService.update(level, body, patchSub);
            }
            if (SELF_HEALED.contains(body)) {
                continue; // 这个 body 这次运行已经补过了 ✓
            }
            if (patchSub == null) {
                continue; // 结构还没加载 —— 下次再来 ✓（**不要**标记成已处理 ✗）
            }
            rebuild(level, body);
            // 软 CCD（用户建议 ✓）：只在该实体第一次补建时打开一次 ✓（只开标志 ✓ 独立变量 ✓）
            com.ovo.sablestopnow.physics.SablePhysicsExt.enableSoftCcd(
                    dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.getSceneHandle(level),
                    dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.getID(patchSub), 0.5);
            SELF_HEALED.add(body);
            com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                    "[sablestopnow] 碰撞自愈: body={} 的实体碰撞已补建 ✓"
                            + "（重开存档后凸包不会自己回来 ✗ 见 ensureCollisions 的注释 ✓）", body);
        }
    }

    /** 服务器停了：自愈记录也要清 ✓（否则换存档后新 body 会被误判成"已补过" ✗）。 */
    public static void clearSelfHeal() {
        SELF_HEALED.clear();
    }

    /**
     * 重建某个子结构上的全部实体碰撞。
     *
     * <p>⚠ **必须整体重建而不是增量更新**：实体的几何是从草图**现算**的
     * （改一个约束就可能让轮廓整体变形），增量去比对盒子差异既复杂又容易漏。
     * 盒子数不多时整体重建是最省心的正确做法。
     *
     * <p>⚠ 只能在**服务端线程**上调用。
     */
    public static void rebuild(final ServerLevel level, final UUID bodyId) {
        clear(level, bodyId);

        final ServerSubLevel sub = find(level, bodyId);
        if (sub == null) {
            // 结构没加载 —— 不是错误。等它加载/玩家再改一次时重建。
            return;
        }
        final List<Solid> solids = SolidData.get(level).ofBody(bodyId);
        if (solids.isEmpty()) {
            return;
        }
        final SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        if (system == null) {
            return;
        }
        final PhysicsPipeline pipeline = system.getPipeline();
        final List<Sketch> sketches = new ArrayList<>(SketchData.get(level).all().values());
        final Pose3d subPose = sub.logicalPose();

        final List<Tracked> built = new ArrayList<>();
        final long t0 = System.nanoTime();
        int boxPathCount = 0;
        // ⚠ 先清掉**已经没有实体对应的**体素装置（实体被删掉了）。
        //   实体被删时它已不在 solids 里，只能按"活着的 id 集合"反查 ——
        //   否则旧装置会留下：既泄漏，又留下**看不见却挡路的残留碰撞**。
        VoxelCollisionService.clearOrphans(level, bodyId, solids);
        for (final Solid solid : solids) {
            final Sketch sketch = solid.resolve(sketches);
            // ⚠ **曲面走体素碰撞体**（用户 2026-10 定的方案）：
            //   曲面用盒子近似要很多盒子，而每个盒子都是"刚体 + 弹簧"，
            //   每个都进求解器迭代 → 大量卡顿（实测半径 1 球的旋转体要 132 个盒子）。
            //   体素路径是"一个碰撞体喂 N 个盒子"，没有刚体/约束/求解器开销。
            //   **平面实体仍走盒子路**：那更省，而且没有阶梯误差。
            if (VoxelCollisionService.usesVoxelPath(solid, sketch)) {
                VoxelCollisionService.rebuild(level, bodyId, sub, solid, sketch);
                continue; // 不再给它建盒子刚体 —— 两套同时存在会互相打架
            }
            // ⚠ 走**实体自己的**碰撞几何（拉伸是轴对齐盒、旋转是分楔 OBB），
            // 不要在这里按拉伸去算 —— 那样旋转体会拿到空表，悄悄没有碰撞。
            final List<SolidCollision.OrientedBox> specs = solid.orientedBoxes(sketch);
            if (specs.isEmpty()) {
                continue; // 草图没同步过来 / 轮廓不闭合 / 轴选得退化
            }
            // ⚠ 质量基准必须是**实体真实体积**，不是"盒子体积之和"。
            // 棱盒是相互重叠的壳，之和远大于真实体积 → 质量被高估 → 手杖推不动
            //（用户实测："拖动含模型的物理结构有强烈阻滞感"）。
            double trueVolume = solid.volume(sketch);
            if (Double.isNaN(trueVolume)) {
                // 轴穿过轮廓 → 帕普斯不适用，退回盒子和（近似但有界）
                trueVolume = SolidCollision.orientedVolume(specs);
            }
            if (trueVolume <= 1.0e-12) {
                trueVolume = SolidCollision.orientedVolume(specs);
            }
            final double totalMass = trueVolume * DENSITY;
            final double boxVolumeSum = SolidCollision.orientedVolume(specs);
            for (final SolidCollision.OrientedBox spec : specs) {
                // 按各盒体积**占比**分摊（盒子是分解，不是独立物体；每个都算满会重复计入）
                final double mass = boxVolumeSum <= 1.0e-12 ? 0.0
                        : totalMass * (spec.volume() / boxVolumeSum);
                final Tracked t = attach(system, pipeline, sub, subPose, spec, mass);
                if (t != null) {
                    built.add(t);
                    boxPathCount++;
                }
            }
        }
        PhysicsDiag.rebuild("盒子路", 0, boxPathCount, boxPathCount,
                (System.nanoTime() - t0) / 1_000_000L);
        if (!built.isEmpty()) {
            BY_DIMENSION.computeIfAbsent(level.dimension().location(), k -> new HashMap<>())
                    .put(bodyId, built);
        }
    }

    /** 建一个盒子刚体并焊到子结构上（支持任意朝向的盒子）。 */
    private static Tracked attach(final SubLevelPhysicsSystem system, final PhysicsPipeline pipeline,
                                 final ServerSubLevel sub, final Pose3d subPose,
                                 final SolidCollision.OrientedBox spec, final double mass) {
        final Vector3d localCentre = new Vector3d(spec.centre());
        final Vector3d half = new Vector3d(spec.halfExtents());
        // ⚠ 盒子的朝向只在这一处取，**位姿和约束共用同一个值**。
        // 分成两处各取一次的话，以后有人改了一处就会变成"约束被违反" —— 而那个 bug
        // 的表现是**子结构被甩飞 + 服务端卡死**，极难从现象反推。
        final Quaterniond rotation = new Quaterniond(spec.rotation());
        if (half.x <= 0.0 || half.y <= 0.0 || half.z <= 0.0) {
            return null;
        }
        // 刚体的位姿在**物理场景系**（世界系）：把子结构的位姿作用到局部中心上。
        // ⚠ 只取位置与朝向，**不带 rotationPoint/scale** —— 那套是给渲染用的，
        // 原样搬进物理位姿会让盒子跑到别处。
        final Pose3d boxPose = new Pose3d();
        subPose.transformPosition(localCentre, boxPose.position());
        // 朝向要**复合**：子结构本身的朝向 × 盒子在局部系里的朝向。
        // 轴对齐盒子给的是单位四元数，乘上去等于没变 —— 与改进前完全一致。
        boxPose.orientation().set(subPose.orientation()).mul(rotation);

        final BoxPhysicsObject box = new BoxPhysicsObject(boxPose, half, mass);
        // addObject 内部会调 onAddition → pipeline.addBox(this)（照 BoxPhysicsObject 的实现）
        system.addObject(box);

        // 固定约束：把子结构上的**局部**点 localCentre 钉到盒子刚体的原点（盒子中心）。
        // ⚠ 锚点用"各自物体的局部系"是这类关节的常规约定 —— **已由实机验证通过**。
        //
        // 🔑 **相对朝向必须传盒子的朝向，不能传单位四元数。**
        // 这个是造成过严重事故的 bug（2026-09）：
        // 轴对齐盒子的朝向恰好是单位四元数，所以传单位四元数"碰巧对"；
        // 但**棱盒（曲面用的 OBB）是斜的**，传单位四元数等于**在创建的那一刻就违反约束**，
        // 求解器立刻猛拽 → 子结构被甩飞。实测后果：
        //   Sable 日志 `has an extreme Y coordinate`，随后物理数值爆炸 → **服务端 TPS 归零**。
        // 正确语义：`orientation` 是两端刚体**局部系之间**的相对旋转，
        // 而盒子的位姿就是 `子结构位姿 ∘ spec.rotation()`，所以这里必须传 `spec.rotation()`。
        final FixedConstraintConfiguration config = new FixedConstraintConfiguration(
                new Vector3d(localCentre), new Vector3d(0.0, 0.0, 0.0), new Quaterniond(rotation));
        final PhysicsConstraintHandle joint = pipeline.addConstraint(sub, box, config);
        // 🔑 **关掉这个关节两端的互相接触**（子结构 ↔ 我们的盒子）。
        //
        // 这是困扰了很久的那一类症状的**根因**：
        // 我们的盒子是 `RapierBoxHandle.create(...)` 建出来的**场景里独立的刚体**
        //（见 Sable 源码 `RapierPhysicsPipeline.addBox`），而子结构的方块**自己有体素碰撞体**。
        // 实体往往就画在结构所在的空间里 → 盒子一直在和子结构的方块碰撞体接触；
        // 而它又被固定约束焊死、动不了 → **接触求解器只能和固定约束互相打架**：
        //   · 结构被顶起来 → "自己悬浮"；
        //   · 被约束拽住 → "手杖拉不动"；
        //   · 每帧互推 → 抖动、阻滞感。
        //
        // `setContactsEnabled(false)` 是 Rapier 关节的 `contacts_enabled` 标志
        //（Sable 暴露在 `PhysicsConstraintHandle` 上），**默认是开的**。
        // 关掉之后这对刚体之间不再产生接触 —— 正好是我们想要的：
        // 盒子只是"挂在子结构上的碰撞外形"，不该和它自己的方块打架。
        //
        // ⚠ 注意它只关**这一对**。盒子与盒子之间仍然会接触
        //（它们各自只和子结构成对），所以碰撞盒之间不要互相穿透 —— 那也是
        // `Extrude.SHELL_DEPTH_SCALE` 取薄、以及 `BOX_SHRINK` 内缩的理由。
        if (joint != null && joint.isValid()) {
            joint.setContactsEnabled(false);
        }
        return new Tracked(box, joint);
    }

    /** 清掉某个子结构上的实体碰撞。 */
    public static void clear(final ServerLevel level, final UUID bodyId) {
        final Map<UUID, List<Tracked>> perBody =
                BY_DIMENSION.get(level.dimension().location());
        if (perBody == null) {
            return;
        }
        final List<Tracked> tracked = perBody.remove(bodyId);
        if (tracked == null) {
            return;
        }
        dispose(SubLevelPhysicsSystem.get(level), tracked);
    }

    /**
     * 拆掉一批刚体。
     *
     * <p>⚠ 刚体要用 {@code system.removeObject(...)} 拆，**不能**直接调
     * {@code BoxPhysicsObject.remove()} —— 那个是 {@code protected}，
     * 而且绕过 system 会让它的内部登记表留下悬空条目。
     * 顺序也要注意：**先拆约束再删刚体**，反过来会让约束指向一个不存在的刚体。
     */
    private static void dispose(final SubLevelPhysicsSystem system,
                                final List<Tracked> tracked) {
        for (final Tracked t : tracked) {
            if (t.joint() != null && t.joint().isValid()) {
                t.joint().remove();
            }
            if (t.box() != null && !t.box().isRemoved() && system != null) {
                system.removeObject(t.box());
            }
        }
    }

    /** 维度卸载时清干净，免得静态表里留着已经失效的 handle。 */
    public static void clearDimension(final ServerLevel level) {
        final Map<UUID, List<Tracked>> perBody =
                BY_DIMENSION.remove(level.dimension().location());
        if (perBody == null) {
            return;
        }
        final SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        for (final List<Tracked> tracked : perBody.values()) {
            dispose(system, tracked);
        }
    }

    /** 当前建出来的刚体总数（诊断用）。 */
    public static int bodyCount(final ServerLevel level) {
        final Map<UUID, List<Tracked>> perBody =
                BY_DIMENSION.get(level.dimension().location());
        if (perBody == null) {
            return 0;
        }
        int n = 0;
        for (final List<Tracked> tracked : perBody.values()) {
            n += tracked.size();
        }
        return n;
    }

    /** 按 UUID 找服务端的子结构；没加载时返回 null。 */
    static ServerSubLevel find(final ServerLevel level, final UUID bodyId) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        for (final ServerSubLevel sub : container.getAllSubLevels()) {
            if (sub.getUniqueId().equals(bodyId)) {
                return sub;
            }
        }
        return null;
    }
}
