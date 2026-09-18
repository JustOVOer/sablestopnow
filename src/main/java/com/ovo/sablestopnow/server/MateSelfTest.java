package com.ovo.sablestopnow.server;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.mate.MateFrames;
import com.ovo.sablestopnow.mate.MateRef;
import com.ovo.sablestopnow.mate.MateType;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 配合系统的<b>引擎内</b>自检：让真实的物理引擎去验证「约束到底成不成立」。
 *
 * <p>{@code tools/MateFramesCheck} 只能验证几何推导（锁定轴坐标是否为零）；至于 Sable / rapier
 * 是否真的按 {@code lockedAxes} 与 {@code setLimit} 把两个体约束住，只有在游戏里跑物理才知道。
 *
 * <h2>怎么触发</h2>
 * 在游戏目录（{@code run/}）放一个标记文件 {@code mate-selftest.flag}，启动服务端，跑完会写
 * {@code run/mate-selftest-result.txt} 并自动关服。用文件而不是系统属性：Gradle 派生的游戏 JVM
 * 未必继承到新属性，而文件读写在这套环境里最可靠。
 *
 * <h2>⚠ 为什么必须给一对体的两侧施加<b>不同</b>的冲量</h2>
 * 第一版自检写错过：它把两个结构放在高空自由落体，然后测量「相对位姿有没有变」。
 * <b>自由落体的两个物体本来就保持相对位姿</b>——即使一个约束都没有，测量也会通过。
 * 那一版还真的报了 PASS，但日志里 {@code live joints: 0}：物理压根没步进，关节从未建立。
 *
 * <p>所以这一版做了三件事：
 * <ol>
 *   <li><b>强制区块 tick</b>（无玩家的专用服务端默认不会给这些区块跑物理，这也是上一版空转的根因）；</li>
 *   <li>对每对体的两侧施加<b>方向相反</b>的冲量，把约束往反方向拉——没有关节时相对位姿一定会变，
 *       测量因此不再空转；</li>
 *   <li>加一条<b>健全性断言</b>：物体必须真的动过。没动就直接判 FAIL 并写明「physics did not run」，
 *       绝不允许再出现「什么都没发生却报 PASS」。</li>
 * </ol>
 *
 * <h2>测什么</h2>
 * <ul>
 *   <li><b>DISTANCE</b>：基准面间距钉住 2 格，两侧沿该轴反向拉开 → 间距必须仍是 2。
 *       这是<b>唯一能验证 {@code setLimit(axis, v, v)} 语义</b>的用例。</li>
 *   <li><b>CONCENTRIC</b>：X 主轴共线，两侧沿 Z 反向推开 → 垂直轴线的偏移必须仍是 0，且轴线保持平行。</li>
 *   <li><b>LOCK</b>：两侧反向拉开 → 相对位姿必须不变。</li>
 * </ul>
 * 三对体彼此独立（第一版让 LOCK 和 CONCENTRIC 复用同一对体，被过约束检测正确拒绝了）。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID)
public final class MateSelfTest {

    private static final String FLAG = "mate-selftest.flag";
    private static final String RESULT = "mate-selftest-result.txt";

    /** 建配后先等这么多 tick 让关节建立。 */
    private static final int BUILD_TICKS = 20;
    /** 施加冲量后再等这么多 tick，让约束把偏差拉回来。 */
    private static final int SETTLE_TICKS = 80;
    /** 冲量给出的目标速度增量（格/秒）。按质量换算，避免对重结构推不动。 */
    private static final double IMPULSE_DELTA_V = 3.0;
    /** 判定“物体确实动过”的位移下限（格）。 */
    private static final double MOVED_EPSILON = 0.2;

    private static final double TOL = 0.05;

    private static final List<String> LINES = new ArrayList<>();
    private static int failures;

    @Nullable
    private static ServerLevel level;
    private static int phase; // 0 = idle, 1 = building, 2 = settling
    private static int countdown;

    /** 用例：一对体 + 两端参考 + 施加给两侧的冲量方向。 */
    private record Case(String label, UUID bodyA, UUID bodyB, MateRef refA, MateRef refB,
                        Vector3d pushA, Vector3d pushB) {
    }

    private static final List<Case> CASES = new ArrayList<>();
    /** 每个体的初始位置，用来证明物理确实跑了。 */
    private static final Map<UUID, Vector3d> INITIAL_POS = new HashMap<>();
    /** LOCK 用例建配瞬间的相对位置。 */
    private static final Vector3d LOCK_REL_BEFORE = new Vector3d();
    private static final List<UUID> LOCK_PAIR = new ArrayList<>(2);

    private MateSelfTest() {
    }

    // ============ 触发 ============

    @SubscribeEvent
    public static void onServerStarted(final ServerStartedEvent event) {
        final MinecraftServer server = event.getServer();
        final Path flag = server.getServerDirectory().resolve(FLAG).toAbsolutePath();
        final Path result = server.getServerDirectory().resolve(RESULT).toAbsolutePath();
        if (!Files.exists(flag)) {
            return;
        }
        try {
            Files.deleteIfExists(flag);
            Files.deleteIfExists(result);
        } catch (final IOException e) {
            SablestopNow.LOGGER.warn("[mate-selftest] cannot clear flag/result", e);
        }

        LINES.clear();
        CASES.clear();
        INITIAL_POS.clear();
        LOCK_PAIR.clear();
        failures = 0;

        log("=== mate self test (in-engine) ===");
        try {
            setup(server);
            phase = 1;
            countdown = BUILD_TICKS;
            log("setup ok; building joints for " + BUILD_TICKS + " ticks");
        } catch (final Throwable t) {
            fail("setup threw " + t);
            t.printStackTrace();
            finish(server, result);
        }
    }

    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post event) {
        if (phase == 0) {
            return;
        }
        if (--countdown > 0) {
            return;
        }
        final MinecraftServer server = event.getServer();
        if (phase == 1) {
            log("live joints after build phase: " + MateRegistry.liveJointCount());
            try {
                push();
            } catch (final Throwable t) {
                fail("push threw " + t);
                t.printStackTrace();
            }
            phase = 2;
            countdown = SETTLE_TICKS;
            log("applied opposing impulses; settling for " + SETTLE_TICKS + " ticks");
            return;
        }
        // phase 2
        try {
            measure();
        } catch (final Throwable t) {
            fail("measure threw " + t);
            t.printStackTrace();
        }
        finish(server, server.getServerDirectory().resolve(RESULT).toAbsolutePath());
    }

    // ============ 建立场景 ============

    private static void setup(final MinecraftServer server) {
        level = server.overworld();
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            throw new IllegalStateException("no sub-level container in overworld");
        }

        // 无玩家的专用服务端不会给这些区块跑物理 —— 必须强制 tick，否则整个自检是空转的
        for (int cx = 0; cx <= 3; cx++) {
            for (int cz = 0; cz <= 3; cz++) {
                level.setChunkForced(cx, cz, true);
            }
        }
        log("forced chunks (0..3, 0..3)");

        // 三对彼此独立的体，放在高空自由落体（不接触地形，排除接触力干扰）
        final ServerSubLevel a = assemble(level, new BlockPos(0, 160, 0));
        final ServerSubLevel b = assemble(level, new BlockPos(40, 160, 0));
        final ServerSubLevel c = assemble(level, new BlockPos(0, 200, 40));
        final ServerSubLevel d = assemble(level, new BlockPos(40, 200, 40));
        final ServerSubLevel e = assemble(level, new BlockPos(0, 240, 80));
        final ServerSubLevel f = assemble(level, new BlockPos(40, 240, 80));

        final UUID owner = UUID.nameUUIDFromBytes("mate-selftest".getBytes(StandardCharsets.UTF_8));
        final String name = "self-test";

        // 1) DISTANCE：沿 Y 的基准面间距 2，两侧沿 Y 反向拉开
        final MateRef planeA = MateRef.plane(a.getUniqueId(), 1);
        final MateRef planeB = MateRef.plane(b.getUniqueId(), 1);
        expectCreate("DISTANCE", MateRegistry.create(level, owner, name,
                MateType.DISTANCE, planeA, planeB, 2.0, false));
        CASES.add(new Case("DISTANCE=2", a.getUniqueId(), b.getUniqueId(), planeA, planeB,
                new Vector3d(0, 1, 0), new Vector3d(0, -1, 0)));

        // 2) CONCENTRIC：X 主轴共线，两侧沿 Z 反向推开（垂直于约束轴）
        final MateRef axisC = MateRef.axis(c.getUniqueId(), 0);
        final MateRef axisD = MateRef.axis(d.getUniqueId(), 0);
        expectCreate("CONCENTRIC", MateRegistry.create(level, owner, name,
                MateType.CONCENTRIC, axisC, axisD, 0.0, false));
        CASES.add(new Case("CONCENTRIC", c.getUniqueId(), d.getUniqueId(), axisC, axisD,
                new Vector3d(0, 0, 1), new Vector3d(0, 0, -1)));

        // 3) LOCK：两侧沿相反方向拉开；独立的一对体，不与上面两对共用
        final MateRef centerE = MateRef.center(e.getUniqueId());
        final MateRef centerF = MateRef.center(f.getUniqueId());
        LOCK_REL_BEFORE.set(relative(e, f));
        LOCK_PAIR.add(e.getUniqueId());
        LOCK_PAIR.add(f.getUniqueId());
        expectCreate("LOCK", MateRegistry.create(level, owner, name,
                MateType.LOCK, centerE, centerF, 0.0, false));
        CASES.add(new Case("LOCK", e.getUniqueId(), f.getUniqueId(), centerE, centerF,
                new Vector3d(0, 1, 0), new Vector3d(0, -1, 0)));

        INITIAL_POS.put(a.getUniqueId(), new Vector3d(a.logicalPose().position()));
        INITIAL_POS.put(b.getUniqueId(), new Vector3d(b.logicalPose().position()));
        INITIAL_POS.put(c.getUniqueId(), new Vector3d(c.logicalPose().position()));
        INITIAL_POS.put(d.getUniqueId(), new Vector3d(d.logicalPose().position()));
        INITIAL_POS.put(e.getUniqueId(), new Vector3d(e.logicalPose().position()));
        INITIAL_POS.put(f.getUniqueId(), new Vector3d(f.logicalPose().position()));
        log("assembled 6 bodies across 3 independent pairs");
    }

    /** 在 {@code base} 附近摆一个 3x3x3 的实心方块堆并组装成一个物理结构。 */
    private static ServerSubLevel assemble(final ServerLevel level, final BlockPos base) {
        final List<BlockPos> blocks = new ArrayList<>(27);
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    final BlockPos p = base.offset(x, y, z);
                    level.setBlock(p, Blocks.STONE.defaultBlockState(), 3);
                    blocks.add(p);
                }
            }
        }
        final BoundingBox3i bounds = BoundingBox3i.from(blocks);
        final ServerSubLevel sub = SubLevelAssemblyHelper.assembleBlocks(level, blocks.get(0), blocks, bounds);
        if (sub == null) {
            throw new IllegalStateException("assembleBlocks returned null at " + base);
        }
        return sub;
    }

    private static void expectCreate(final String label, final MateRegistry.Result result) {
        if (result.ok()) {
            log(label + ": create OK");
        } else {
            fail(label + ": create failed -> " + result.status() + " / " + result.detail());
        }
    }

    // ============ 施加冲量 ============

    /**
     * 对每对体的两侧施加方向相反的冲量。
     *
     * <p>冲量按质量换算成固定的速度增量：3x3x3 石头的质量不小，固定数值的冲量根本推不动它，
     * 那样又会退化成「什么都没发生」。
     */
    private static void push() {
        if (level == null) {
            return;
        }
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        for (final Case testCase : CASES) {
            impulse(container, testCase.bodyA(), testCase.pushA());
            impulse(container, testCase.bodyB(), testCase.pushB());
        }
    }

    private static void impulse(final ServerSubLevelContainer container, final UUID id, final Vector3d dir) {
        final ServerSubLevel body = sub(container, id);
        if (body == null) {
            fail("impulse target vanished: " + id);
            return;
        }
        final double mass = body.getMassTracker().getMass();
        final double scale = IMPULSE_DELTA_V * (mass > 0 && Double.isFinite(mass) ? mass : 1000.0);
        final RigidBodyHandle handle = RigidBodyHandle.of(body);
        if (handle == null) {
            fail("no rigid body handle for " + id);
            return;
        }
        handle.applyLinearImpulse(new Vector3d(dir).mul(scale));
        log("impulse " + String.format(java.util.Locale.ROOT, "%.0f", scale)
                + " along " + dir + " on " + id);
    }

    // ============ 测量 ============

    private static void measure() {
        if (level == null) {
            return;
        }
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            fail("container disappeared");
            return;
        }
        log("live joints at measure time: " + MateRegistry.liveJointCount());

        // ---- 健全性：物理到底跑了没有 ----
        double maxMove = 0;
        for (final Map.Entry<UUID, Vector3d> entry : INITIAL_POS.entrySet()) {
            final ServerSubLevel body = sub(container, entry.getKey());
            if (body == null) {
                fail("body vanished: " + entry.getKey());
                continue;
            }
            maxMove = Math.max(maxMove, body.logicalPose().position().distance(entry.getValue()));
        }
        final boolean physicsRan = maxMove > MOVED_EPSILON;
        log((physicsRan ? "PASS " : "FAIL ") + "physics actually ran (max body displacement "
                + String.format(java.util.Locale.ROOT, "%.3f", maxMove) + " > " + MOVED_EPSILON + ")");
        if (!physicsRan) {
            failures++;
            log("  -> measurement below is VACUOUS; do not trust it");
        }
        // 联锁：约束必须真的建立过，否则下面测的只是自由运动
        if (MateRegistry.liveJointCount() < CASES.size()) {
            failures++;
            log("FAIL expected " + CASES.size() + " live joints, got " + MateRegistry.liveJointCount());
        }

        for (final Case testCase : CASES) {
            final ServerSubLevel a = sub(container, testCase.bodyA());
            final ServerSubLevel b = sub(container, testCase.bodyB());
            if (a == null || b == null) {
                fail(testCase.label() + ": a body vanished");
                continue;
            }
            switch (testCase.label()) {
                case "DISTANCE=2" -> {
                    final Vector3d uA = MateFrames.worldDirection(a, MateFrames.resolveLocal(a, testCase.refA()));
                    final Vector3d qA = MateFrames.worldPoint(a, MateFrames.resolveLocal(a, testCase.refA()));
                    final Vector3d qB = MateFrames.worldPoint(b, MateFrames.resolveLocal(b, testCase.refB()));
                    final double gap = uA == null ? Double.NaN : new Vector3d(qB).sub(qA).dot(uA);
                    check("DISTANCE gap held at 2.0 despite opposing impulses", gap, 2.0, TOL);
                }
                case "CONCENTRIC" -> {
                    final Vector3d axis = MateFrames.worldDirection(a, MateFrames.resolveLocal(a, testCase.refA()));
                    final Vector3d pA = MateFrames.worldPoint(a, MateFrames.resolveLocal(a, testCase.refA()));
                    final Vector3d pB = MateFrames.worldPoint(b, MateFrames.resolveLocal(b, testCase.refB()));
                    if (axis == null) {
                        fail("CONCENTRIC: no axis direction");
                        break;
                    }
                    final Vector3d rel = new Vector3d(pB).sub(pA);
                    final Vector3d perp = new Vector3d(rel).fma(-rel.dot(axis), axis);
                    check("CONCENTRIC perpendicular offset held at 0", perp.length(), 0.0, TOL);
                    final Vector3d axisB = MateFrames.worldDirection(b, MateFrames.resolveLocal(b, testCase.refB()));
                    check("CONCENTRIC axes stayed parallel",
                            axisB == null ? 0.0 : Math.abs(axis.dot(axisB)), 1.0, TOL);
                }
                case "LOCK" -> {
                    final Vector3d after = relative(a, b);
                    check("LOCK relative position held", after.distance(LOCK_REL_BEFORE), 0.0, TOL);
                }
                default -> fail("unknown case " + testCase.label());
            }
        }
    }

    private static Vector3d relative(final ServerSubLevel a, final ServerSubLevel b) {
        return new Vector3d(b.logicalPose().position()).sub(a.logicalPose().position());
    }

    @Nullable
    private static ServerSubLevel sub(final ServerSubLevelContainer container, final UUID id) {
        final var found = container.getSubLevel(id);
        return found instanceof final ServerSubLevel server && !server.isRemoved() ? server : null;
    }

    // ============ 输出 ============

    private static void check(final String what, final double actual, final double expected, final double tol) {
        final boolean pass = Double.isFinite(actual) && Math.abs(actual - expected) <= tol;
        log((pass ? "PASS " : "FAIL ") + what + "  actual=" + fmt(actual)
                + " expected=" + fmt(expected) + " tol=" + tol);
        if (!pass) {
            failures++;
        }
    }

    private static String fmt(final double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    private static void fail(final String message) {
        failures++;
        log("FAIL " + message);
    }

    private static void log(final String line) {
        LINES.add(line);
        SablestopNow.LOGGER.info("[mate-selftest] {}", line);
    }

    private static void finish(final MinecraftServer server, final Path result) {
        phase = 0;
        log("=== " + (failures == 0 ? "ALL PASS" : (failures + " FAILURE(S)")) + " ===");
        try {
            Files.write(result, LINES, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            SablestopNow.LOGGER.error("[mate-selftest] cannot write result", e);
        }
        if (level != null) {
            for (int cx = 0; cx <= 3; cx++) {
                for (int cz = 0; cz <= 3; cz++) {
                    level.setChunkForced(cx, cz, false);
                }
            }
        }
        MateRegistry.clearAll();
        server.halt(false);
    }
}
