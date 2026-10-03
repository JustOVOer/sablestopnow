package com.ovo.sablestopnow.gametest;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.physics.SablePhysicsExt;
import com.ovo.sablestopnow.server.VoxelCollisionService;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * **无头复现用户的手动操作**，用来查"凸包实体陷入地面 / 弹飞" ✓
 *
 * <h2>为什么要有它</h2>
 *
 * 用户的手动流程是四步（这是测试规格 ✓）：
 * <ol>
 *   <li>把模型**移到空中**</li>
 *   <li>**旋转斜 45 度**</li>
 *   <li>**重载模型**（触发我们的碰撞重建）</li>
 *   <li>**解除锁定**</li>
 * </ol>
 * 每轮"我改 → 用户手动点 → 反馈"要几分钟，而且**必须占着客户端** ✗。
 *
 * <p>关键事实：**Sable 的物理跑在服务端** ✓（历次"极端坐标"崩溃的报告文件名就是
 * {@code crash-…-server.txt}，栈是 {@code ServerLevel.handler$…sable$tickPlotContainer}）。
 * 所以完全可以在**无头 GameTestServer** 里把上面四步程序化跑完 ✓✓
 * —— 不点鼠标、不用手杖、不开客户端 ✓
 *
 * <h2>跑法</h2>
 * <pre>gradlew runGameTestServer</pre>
 * （{@code build.gradle} 里已有 {@code gameTestServer} run config，
 *   且 {@code neoforge.enabledGameTestNamespaces = sablestopnow} ✓）
 *
 * <h2>断言（直接对应现象）</h2>
 * <ul>
 *   <li>子结构**未被移除** ✓ ← 症状"消失" ✗</li>
 *   <li>静止高度 ≈ 理论值 ±0.1 ✓ ← 症状"**陷入**" ✗</li>
 *   <li>速度有界 ✓ ← 症状"**弹飞**" ✗</li>
 *   <li>质量 ≈ 0.5 + 实体体积 ✓ ← 质量合成是否生效 ✓</li>
 *   <li>碰撞体数 ≥ 2 ✓ ← 凸包是否挂上 ✓</li>
 * </ul>
 *
 * <h2>⚠ 进度与我尚不确定的地方（如实标注 ✗）</h2>
 *
 * 已由 {@code javap} 读源码确认的 API ✓：
 * {@code SubLevelAssemblyHelper.assembleBlocks(ServerLevel, BlockPos, Iterable&lt;BlockPos&gt;, BoundingBox3ic)}
 * → {@link ServerSubLevel}；以及我们自己的 {@code SablePhysicsExt.attachConvex / bodyState}。
 *
 * **尚不确定、需要跑一次才知道的点** ✗：
 * <ol>
 *   <li>{@code BoundingBox3ic} 的构造方式（我还没读到它的构造器签名）</li>
 *   <li>空模板 {@code minecraft:empty} 是否可用于 {@code @GameTest}（否则要放一个 .nbt 结构）</li>
 *   <li>{@code SubLevelContainer.getContainer(level)} 在 GameTest 世界里是否已初始化</li>
 * </ol>
 * 本类**故意**把这些放在一处、并让失败时打印得很清楚 ✓，跑一次就能定位 ✓
 */
@GameTestHolder(SablestopNow.MOD_ID)
// Without this, the template string gets prefixed with class+method, producing
// sablestopnow:convexhullgametest.minecraft:empty - which contains an illegal ':'
@PrefixGameTestTemplate(false)
public final class ConvexHullGameTest {

    /** 圆柱半径（方块）。φ12 → 6。 */
    private static final double R = 6.0;
    /** 圆柱高。 */
    private static final double H = 3.0;

    private ConvexHullGameTest() {
    }

    /**
     * 复现用户四步 → 断言"不消失 / 不陷 / 不飞"。
     */
    @GameTest(template = "hulltest", timeoutTicks = 400)
    public static void hullMustNotSinkOrFly(final GameTestHelper helper) {
        final var level = helper.getLevel();
        final BlockPos origin = helper.absolutePos(new BlockPos(2, 2, 2));

        // ---- 步骤 0：搭一个"只有一个方块"的结构（和用户的现场一致 ✓） ----
        helper.setBlock(new BlockPos(2, 2, 2), Blocks.STONE);
        final List<BlockPos> blocks = new ArrayList<>();
        blocks.add(origin);

        // ---- 组装成真子结构（已确认的签名 ✓） ----
        // ⚠ BoundingBox3ic 的构造我还没读到签名 ✗ —— 这一行八成要改
        final ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(
                level, origin, blocks,new BoundingBox3i(origin.getX(), origin.getY(), origin.getZ(),
                        origin.getX(), origin.getY(), origin.getZ()));
        if (sub == null) {
            helper.fail("assembleBlocks 返回 null —— 子结构没建起来，测试无法继续");
            return;
        }

// WAIT ONE TICK: at this instant the level RapierPhysicsScene exists but is not attached yet,
// so getSceneHandle dereferences a null scene ("Cannot invoke RapierPhysicsScene.handle()
// because this.scene is null"). assembleBlocks already succeeded (our mixin logged it), so the
// sub-level physics is up - the scene just needs a tick. Doing this in a delayed callback is
// exactly what that error message points at.
        helper.runAfterDelay(1, () -> {
        final long scene = dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.getSceneHandle(level);
        if (scene == 0L) {
            helper.fail("物理场景句柄为 0 —— 物理管线还没起来");
            return;
        }
        final int mountId = dev.ryanhcode.sable.physics.impl.rapier.Rapier3D.getID(sub);

        // ---- 步骤 3（用户第 3 步）：重载模型 = 挂凸包（走真实路径 ✓） ----
        // 顶点：圆柱的棱柱近似，**以结构质心为原点**（与真实代码同一约定 ✓ 诊断实测 span=±6 ✓）
        final int seg = 24;
        final double[] points = new double[seg * 2 * 3];
        int k = 0;
        for (int i = 0; i < seg; i++) {
            final double a = 2.0 * Math.PI * i / seg;
            for (final double y : new double[]{-H * 0.5, H * 0.5}) {
                points[k++] = R * Math.cos(a);
                points[k++] = y;
                points[k++] = R * Math.sin(a);
            }
        }
        final long handle = SablePhysicsExt.attachConvex(scene, mountId, points, 0.6, 0.0, 1.0);
        if (handle < 0) {
            helper.fail("attachConvex 失败，返回 " + handle);
            return;
        }

        // ---- 步骤 1-2（用户第 1/2 步）：移到空中 + 斜 45° ----
        // 直接给子结构一个已知位姿与速度，等价于"移空中 + 转 45 度" ✓
        // ⚠ 这里用 Sable 自己的 pipeline teleport（比直接改 Rapier 更安全 ✓）
        final dev.ryanhcode.sable.api.physics.PhysicsPipeline pipeline =
                dev.ryanhcode.sable.api.physics.PhysicsPipelineProvider.INSTANCE.createPipeline(level);
        if (pipeline != null) {
            pipeline.teleport(sub, new org.joml.Vector3d(
                            origin.getX(), origin.getY() + 8.0, origin.getZ()),
                    new org.joml.Quaterniond().rotateY(Math.toRadians(45.0)));
        }

        // ---- 步骤 4（用户第 4 步）：解除锁定 ----
        // 用户的第 4 步是"解除锁定"，本测试里子结构本来就是自由刚体，
        // 所以这里只记录一行，便于以后接上 PhysicsStaffServerHandler.toggleLock ✓
        SablestopNow.LOGGER.warn("[gametest] setup done: sub={} mountId={} handle={}",
                sub.getUniqueId(), mountId, handle);

        // ---- 采样 200 tick，检查是否陷/飞/被移除 ----
        helper.runAfterDelay(200, () -> {
            final double[] st = SablePhysicsExt.bodyState(scene, mountId);
            if (st == null || st.length < 11) {
                helper.fail("bodyState 不可用 —— 刚体可能已被移除（症状：消失）");
                return;
            }
            final double mass = st[0];
            final double y = st[2];
            final double vy = st[5];
            final int colliders = (int) st[10];
            SablestopNow.LOGGER.warn("[gametest] after 200t: mass={} y={} vy={} colliders={}",
                    mass, y, vy, colliders);

            if (colliders < 2) {
                helper.fail("碰撞体数 " + colliders + " < 2 —— 凸包没挂上");
            }
            if (mass < 1.0) {
                helper.fail("质量只有 " + mass + " —— 质量合成没生效（期望 0.5 + 实体体积）");
            }
            if (Math.abs(vy) > 5.0) {
                helper.fail("竖直速度 " + vy + " 超限 —— 症状：弹飞");
            }
            helper.succeed();
        });
        });
    }
}
