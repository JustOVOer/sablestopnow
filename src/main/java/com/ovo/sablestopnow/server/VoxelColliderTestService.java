package com.ovo.sablestopnow.server;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.mixin.scale.Rapier3DAccessor;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 「体素碰撞体」实验：在选中的子结构上挂一个**方块大小的装置**，看它是否真的产生碰撞。
 *
 * <h2>为什么先做最小实验</h2>
 * 这条路（体素碰撞体 → 运动学装置）是我们查源码推出来的，**每一步都还没有实机证据**。
 * 一次把整个模型体素化接上去，出问题时无法定位是哪一步错的。
 * 所以先回答最小的问题：**一个方块大小的体素碰撞体，挂到子结构上，会不会碰撞？**
 *
 * <h2>用到的原生入口（都是 private/包级，经 Mixin @Invoker 调用）</h2>
 * <ol>
 *   <li>{@code newVoxelCollider(friction, volume, restitution, isFluid, cb)} → 形状下标</li>
 *   <li>{@code addVoxelColliderBox(index, bounds)} —— bounds 在**方块局部 [0,1]**</li>
 *   <li>{@code createKinematicContraption(handle, mountId, id, pose)} —— mountId 挂到子结构刚体</li>
 *   <li>{@code addKinematicContraptionChunkSection(handle, id, sx, sy, sz, data)} —— 16³ 的 int[4096]</li>
 *   <li>{@code setLocalBounds(handle, id, minX..maxZ)} —— **不调的话装置没有范围**</li>
 * </ol>
 *
 * <p>打包方式（Rust `contraptions.rs` 读死的）：高 16 位 = 体素碰撞体下标 **+1**，低 16 位 = 状态序数。
 * 状态表是 {@code [Empty, Face, Edge, Corner, Interior]}，**Sable 自己给装置用的是 CORNER(3)**，照它。
 * ⚠ 段内索引是 {@code (x&15) + ((z&15)<<4) + ((y&15)<<8)}（x + z·16 + y·256），不是常见顺序。
 */
public final class VoxelColliderTestService {

    private VoxelColliderTestService() {
    }

    /** 体素碰撞体状态的序数：{@code VoxelNeighborhoodState.CORNER}（Sable 给装置用的值）。 */
    private static final int STATE_CORNER = 3;

    public static void run(final ServerLevel level, final UUID bodyId,
                           final net.minecraft.server.level.ServerPlayer player) {
        final ServerSubLevel sub = find(level, bodyId);
        if (sub == null) {
            feedback(player, "找不到那个子结构（可能没加载）");
            return;
        }
        final long scene = Rapier3D.getSceneHandle(level);
        if (scene == 0L) {
            feedback(player, "物理场景还没有句柄");
            return;
        }
        // 1) 两个形状。
        //
        // ⚠ **为什么不能只放一个完整立方体**：完整立方体和一个普通方块看起来一模一样，
        //   它只能验证"机制通不通"，**验证不了"方块局部 [0,1] 的 1/16 解释对不对"**。
        //   所以并排放第二个：**16 级台阶**（每级 1/16 高、沿 x 递升）。
        //   它一眼就能看出三件事：机制通、坐标被当作方块局部、粒度确实是 1/16。
        final int cubeIndex = Rapier3DAccessor.sablestopnow$newVoxelCollider(
                0.45, 1.0, 0.0, false, null);
        if (cubeIndex < 0) {
            feedback(player, "newVoxelCollider 返回了负数：" + cubeIndex);
            return;
        }
        Rapier3D.addVoxelColliderBox(cubeIndex,
                new double[]{0.0, 0.0, 0.0, 1.0, 1.0, 1.0});

        final int stairIndex = Rapier3DAccessor.sablestopnow$newVoxelCollider(
                0.45, 1.0, 0.0, false, null);
        if (stairIndex < 0) {
            feedback(player, "第二个 newVoxelCollider 返回了负数：" + stairIndex);
            return;
        }
        final int steps = 16;
        for (int i = 0; i < steps; i++) {
            // 每级：x ∈ [i/16, (i+1)/16]，高度 y = (i+1)/16，z 占满
            Rapier3D.addVoxelColliderBox(stairIndex, new double[]{
                    (double) i / steps, 0.0, 0.0,
                    (double) (i + 1) / steps, (double) (i + 1) / steps, 1.0});
        }

        // 2) 装置：挂到**子结构自己的刚体**上。
        //    同刚体上的碰撞体之间永不互相碰撞 → 与子结构自身的方块重合也无害，且不新增刚体。
        final int mountId = Rapier3D.getID(sub);
        final int id = Rapier3D.nextBodyID();
        // pose 在 Rust 侧是 _pose（被忽略），给个合法长度即可
        Rapier3DAccessor.sablestopnow$createKinematicContraption(scene, mountId, id,
                new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0});

        // 3) 一个 16³ 段：立方体放 (0,0,0)，台阶放 (2,0,0)（隔一格好看清是两块）
        //
        // ⚠ **段坐标是装置自己的局部坐标，不是 plot 绝对坐标** ——
        //   装置是一个"局部体素结构"，由 setKinematicContraptionTransform 把它摆到世界里。
        //   我第一次写成了绝对 section 坐标，那是错的（会和 bounds 对不上）。
        final int[] data = new int[com.ovo.sablestopnow.sketch.SolidCollision.SECTION_BLOCKS];
        data[com.ovo.sablestopnow.sketch.SolidCollision.sectionIndex(0, 0, 0)] =
                com.ovo.sablestopnow.sketch.SolidCollision.packBlock(cubeIndex, STATE_CORNER);
        data[com.ovo.sablestopnow.sketch.SolidCollision.sectionIndex(2, 0, 0)] =
                com.ovo.sablestopnow.sketch.SolidCollision.packBlock(stairIndex, STATE_CORNER);
        final int sx = 0;
        final int sy = 0;
        final int sz = 0;
        Rapier3DAccessor.sablestopnow$addKinematicContraptionChunkSection(scene, id, sx, sy, sz,
                data);

        // 3b) **必须**把装置摆到世界里 —— 漏了这步服务端会崩：
        //     `Rapier native panic: compute_local_aabb called before bounds were set`。
        //
        // ⚠⚠ **位姿是相对于父体质心的，而质心在结构内部** —— 传 (0,0,0) 就等于把装置
        //   埋进方块里：**看不到也碰不到**（用户实测反馈"什么都看不到"就是这个原因）。
        //   所以这里往上抬到**结构顶面之上**，确保能走到它旁边。
        final org.joml.Vector3dc parentCom = sub.getMassTracker().getCenterOfMass();
        final double comX = parentCom == null ? 0.0 : parentCom.x();
        final double comY = parentCom == null ? 0.0 : parentCom.y();
        final double comZ = parentCom == null ? 0.0 : parentCom.z();
        double topY = comY + 2.0;
        double useX = comX;
        double useZ = comZ;
        // ⚠⚠ **不能用 `getPlot().getBoundingBox()`** —— 那是 **plot 分配区域**的包围盒，
        //   不是结构实际占据的范围。用它算出来的位置与结构无关：装置被放进地形里，
        //   而它**刚性地挂在子结构刚体上** → 求解器把整个子结构顶起来 → **结构浮空**。
        //   （用户实测："台阶面碰撞有问题 + 物理结构有浮空"，两个症状同一个根因。）
        //
        // 结构**真实范围**是 `sub.boundingBox()`，而且它是**世界系** ——
        // 必须用位姿逆变换回 plot 局部。这正是 `SketchClient.begin` 里做过、
        // 并专门写了警告注释的那件事（世界系 vs 局部系）。
        try {
            final var wb = sub.boundingBox();
            // 服务端没有 renderPose()（那是客户端的），用 logicalPose() —— 同一套 plot→世界 的位姿
            final var pose3 = sub.logicalPose();
            final org.joml.Vector3d a3 = pose3.transformPositionInverse(
                    new org.joml.Vector3d(wb.minX(), wb.minY(), wb.minZ()),
                    new org.joml.Vector3d());
            final org.joml.Vector3d b3 = pose3.transformPositionInverse(
                    new org.joml.Vector3d(wb.maxX(), wb.maxY(), wb.maxZ()),
                    new org.joml.Vector3d());
            final org.joml.Vector3d lo = a3.min(b3, new org.joml.Vector3d());
            final org.joml.Vector3d hi = a3.max(b3, new org.joml.Vector3d());
            topY = hi.y + 2.0;
            useX = (lo.x + hi.x) * 0.5;
            useZ = (lo.z + hi.z) * 0.5;
        } catch (final Throwable t) {
            // 拿不到就退回"质心上方 2 格"，不要因为这一步把实验堵死
            SablestopNow.LOGGER.warn("[sablestopnow] 取结构真实范围失败，退回质心上方 2 格", t);
        }
        final double poseX = useX - comX;
        final double poseY = topY - comY; // 相对父体质心
        final double poseZ = useZ - comZ;
        // ⚠⚠ **第三个参数是"装置自己的质心"，不是父体的** —— 我传错过一次，代价是"碰撞也没有"：
        //   Sable 自己传的是 `contraption.sable$getMassTracker().getCenterOfMass()`；
        //   而 dispatcher 生成接触时要 `- center_of_mass_1`：
        //       center = 盒中心 + (x,y,z) - center_of_mass_1
        //   传父体质心（plot 绝对坐标，2e7 量级）会把接触**生成到几百万格之外**，
        //   于是装置在你面前却完全碰不到。
        //   我们的合成装置没有自己的质量跟踪器，局部质心就是 (0,0,0)。
        Rapier3DAccessor.sablestopnow$setKinematicContraptionTransform(scene, id,
                new double[]{0.0, 0.0, 0.0},
                new double[]{poseX, poseY, poseZ, 0.0, 0.0, 0.0, 1.0},
                new double[]{0.0, 0.0, 0.0, 0.0, 0.0, 0.0});

        // 4) 声明范围（局部坐标：我们的段是 (0,0,0)，所以范围就是 0..16）
        Rapier3DAccessor.sablestopnow$setLocalBounds(scene, id, 0, 0, 0, 16, 16, 16);

        SablestopNow.LOGGER.info("[sablestopnow] 体素碰撞体实验: body={} mountId={} contraption={}"
                        + " 立方体形状={} 台阶形状={} section=(0,0,0) bounds=0..16",
                bodyId, mountId, id, cubeIndex, stairIndex);
        feedback(player, "实验装置已建：立方体形状=" + cubeIndex + " 台阶形状=" + stairIndex
                + " 装置=" + id + " 挂在刚体 " + mountId);
        feedback(player, "① 装置是**纯碰撞体，不会被渲染** —— 看不见是正常的，要**走过去撞/站上去**试。");
        feedback(player, "② 位置：结构顶面之上约 3 格（质心 +" + String.format("%.1f", poseY)
                + "）。方块局部 1/16 若正确，台阶应有 16 级。");

        // 5) **凸包对照组**（我们自己给原生库加的能力，见 physics/SablePhysicsExt）
        //
        // 为什么放在同一个实验里：这样**同一次触发**就能对比"体素楼梯"和"凸包"两种碰撞
        // 在同一位置的表现 —— 走过去撞一下就知道差别（体素那个是台阶，凸包这个是光滑圆柱）。
        //
        // ⚠ 顶点是**刚体局部坐标**（相对父体质心），和上面 setKinematicContraptionTransform
        //   的 pose 同一套约定；写成 plot 绝对坐标（2e7 量级）会生成到几百万格之外。
        //
        // ⚠ **凸包表示不了洞**：带孔的结构要凸分解后分多次 attachConvex（每块一个）。
        final double hullRadius = 1.5;
        final double hullHeight = 3.0;
        final int segments = com.ovo.sablestopnow.physics.SablePhysicsExt.segmentsFor(hullRadius, 1.0 / 16.0);
        // 摆在台阶旁边（沿 x 偏移 3 格），避免和体素立方体/台阶重叠
        final double[] hull = com.ovo.sablestopnow.physics.SablePhysicsExt.cylinderHull(
                hullRadius, hullHeight, segments, 0.0);
        final double hullX = poseX + 3.0;
        // cylinderHull 以 y=0 居中，这里平移到 poseY
        for (int i = 1; i < hull.length; i += 3) {
            hull[i] += poseY;
        }
        for (int i = 0; i < hull.length; i += 3) {
            hull[i] += hullX;
            hull[i + 2] += poseZ;
        }
        final long hullHandle = com.ovo.sablestopnow.physics.SablePhysicsExt.attachConvex(
                scene, mountId, hull, 0.6, 0.0, 1.0);
        if (hullHandle < 0) {
            feedback(player, "✗ 凸包挂载失败（返回 " + hullHandle + "）—— 看日志有没有 UnsatisfiedLinkError");
            SablestopNow.LOGGER.warn("[sablestopnow] 凸包挂载失败：mountId={} 顶点数={} 返回={}",
                    mountId, hull.length / 3, hullHandle);
        } else {
            feedback(player, "③ 凸包对照：段数=" + segments + " 顶点=" + (hull.length / 3)
                    + " 半径=" + hullRadius + " 高=" + hullHeight + " 偏移 x=+3.0"
                    + " → 句柄 " + Long.toHexString(hullHandle));
            // ⚠⚠ **这一段是第一次调试的关键证据** —— 我们提交的顶点是"**刚体局部**"坐标，
            //   而刚体局部原点是**质心**还是**结构原点**我还没有实证。两者的差就是
            //   父体质心 (comX, comY, comZ)。把两种解释都打出来，一次测试就能判定：
            //     · 若描边处**能碰到** → 约定就是"减质心"（当前实现）✓
            //     · 若描边处碰不到、而"不减质心"的补偿位置能碰到 → 需要反过来 ✓
            SablestopNow.LOGGER.info("[sablestopnow] 凸包已挂载：mountId={} segments={} verts={}"
                            + " r={} h={} handle={} | 父体质心=({}, {}, {})"
                            + " | 提交的中心(减质心)=({}, {}, {})"
                            + " | plot 局部中心(不减质心)=({}, {}, {})",
                    mountId, segments, hull.length / 3, hullRadius, hullHeight,
                    Long.toHexString(hullHandle),
                    String.format("%.3f", comX), String.format("%.3f", comY), String.format("%.3f", comZ),
                    String.format("%.3f", hullX), String.format("%.3f", poseY), String.format("%.3f", poseZ),
                    String.format("%.3f", hullX + comX), String.format("%.3f", poseY + comY),
                    String.format("%.3f", poseZ + comZ));
        }
    }

    /** 按 UUID 找服务端子结构；没加载时返回 null。 */
    private static ServerSubLevel find(final ServerLevel level, final UUID bodyId) {
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

    /**
     * 给玩家一句反馈。
     *
     * <p>⚠ 实验类功能**必须**有反馈：静默失败最难查（我为此吃过一次亏 ——
     * 服务端对解不出区域的实体是静默丢弃的，表现就是"点了没反应"）。
     */
    private static void feedback(final net.minecraft.server.level.ServerPlayer player,
                                 final String message) {
        if (player != null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                    "[体素碰撞体实验] " + message), false);
        }
    }
}
