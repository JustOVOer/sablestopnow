package com.ovo.sablestopnow.server;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.physics.SablePhysicsExt;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **每物理步**记录凸包刚体的状态，写到专用文件，供离线工具分析"陷入地下 / 突变"。
 *
 * <h2>为什么要这样</h2>
 *
 * 每秒一行太粗 ✗ —— 物理是 20Hz ✓，一次"陷入再被弹出"可能只跨 2–3 个物理步 ✓，
 * 按秒采样会**整个漏掉** ✓。所以必须**每个物理步一行** ✓。
 *
 * <p>而且**不能写进游戏日志** ✗：日志有缓冲/过滤/刷盘策略 ✓ 20 行/秒会拖慢服务端 ✓
 * 也难解析 ✓。所以写**独立文件** ✓ 带 {@link BufferedWriter} ✓ 只在追踪开启时才动 ✓。
 *
 * <h2>输出格式（CSV，供工具抓）</h2>
 * <pre>
 * tick,bodyKind,mountId,mass,x,y,z,vx,vy,vz,wx,wy,wz,colliders
 * </pre>
 * bodyKind: {@code S} = 结构刚体（Sable 的子结构 ✓），{@code H} = 凸包（B 路时与 S 同一个 ✗ 所以会重复 ✓）
 *
 * <h2>工具怎么用</h2>
 * <pre>
 * tools\analyze-hull-trace.ps1        # 抓「y 穿过地面」与「|vy| 突变」两类事件
 * </pre>
 *
 * <h2>怎么开启</h2>
 * 只要 {@link VoxelCollisionService} 成功挂上凸包，就会自动开始追踪那个 mountId ✓
 * （见 {@code HullTrace.trace(mountId)} ✓）。没有任何追踪对象时，本类**完全不做任何事** ✓
 * 开销为零 ✓
 */
public final class HullTrace {

    private HullTrace() {
    }

    /**
     * 要追踪的 mountId → 物理场景句柄。
     *
     * <p>刻意在这里**缓存 scene** ✓：每个物理步再去查 level / scene 既慢又容易踩到
     * "场景未就绪"的坑 ✗（无头测试里就撞过 `RapierPhysicsScene.scene is null` ✗）。
     * 挂载成功的那一刻 scene 一定是好的 ✓ 直接存下来最稳 ✓ 也让 mixin 里只需一行调用 ✓
     *
     * <p>空 = 完全不工作（零开销）✓
     */
    private static final Map<Integer, Long> TRACED = new ConcurrentHashMap<>();

    private static BufferedWriter writer;
    private static Path outFile;
    private static long lastTick = -1L;
    private static int linesThisSession;

    /**
     * 除显式追踪的 mountId 之外，还记录该场景下 0..TRACE_ID_MAX 的全部刚体 ✓
     * —— 目的是自动捕获【被放在圆柱上面的那个结构】✓（id 通常都很小 ✓）
     * bodyState 对不存在的 id 返回 null ✓ 所以扫这个范围是安全的 ✓
     */
    private static final int TRACE_ID_MAX = 23;

    /** 单次运行最多写这么多行，防止忘记关掉时把磁盘写满。20 行/秒 → 60 万行约 8 小时。 */
    private static final int MAX_LINES = 600_000;

    /** 开始追踪某个子结构的刚体。由 {@link VoxelCollisionService} 在挂载成功后调用。 */
    public static void trace(final int mountId, final long scene) {
        if (scene == 0L) {
            return;
        }
        if (TRACED.put(mountId, scene) == null) {
            SablestopNow.LOGGER.warn("[hull-trace] 开始追踪 mountId={} scene={} → {}",
                    mountId, scene, fileHint());
        }
    }

    /** 停止追踪（重建/卸载时调用，避免追踪已失效的刚体 id）。 */
    public static void untrace(final int mountId) {
        if (TRACED.remove(mountId) != null) {
            SablestopNow.LOGGER.warn("[hull-trace] 停止追踪 mountId={}（本会话已写 {} 行）",
                    mountId, linesThisSession);
        }
    }

    public static void untraceAll() {
        TRACED.clear();
    }

    private static String fileHint() {
        try {
            return FMLPaths.GAMEDIR.get().resolve("sablestopnow-hull-trace.csv").toString();
        } catch (final Throwable t) {
            return "sablestopnow-hull-trace.csv";
        }
    }

    /**
     * **每个物理步调用一次**（由 {@code SubLevelPhysicsSystemMixin} 在 Sable 的
     * {@code tickPipelinePhysics} 之后调用 ✓ 那正是物理步的边界 ✓）。
     *
     * <p>刻意做成"失败就静默关闭"✗：追踪只是诊断工具 ✓ 绝不能因为它让物理或服务端出错 ✓
     */
    public static void step() {
        if (TRACED.isEmpty()) {
            return;
        }
        try {
            ensureWriter();
            if (writer == null) {
                return;
            }

            final long tick = ++physStep;
            final StringBuilder sb = new StringBuilder(160);
            for (final Map.Entry<Integer, Long> e : TRACED.entrySet()) {
                // Sweep a range of body ids as well. Measured caveat: bodyState returns non-null
                // even for ids that do not exist, and those fake rows show a CONSTANT position
                // (ids 3..23 all reported exactly the same sample count). So the sweep is still
                // the cheapest way to capture "the structure placed on the cylinder", but any
                // analysis MUST ignore rows whose y never changes - that is the fingerprint of
                // a non-existent id. Real structures move by at least a few 1e-4 during settling.
                // kind: S = a body we attached a hull to, O = any other id in the scene.
                for (int id = 0; id <= TRACE_ID_MAX; id++) {
                    appendBody(sb, e.getValue(), id, id == e.getKey() ? "S" : "O", tick);
                }
            }
            if (sb.length() > 0) {
                writer.write(sb.toString());
                linesThisSession++;
                if (linesThisSession == 1 || linesThisSession % 200 == 0) {
                    writer.flush(); // 周期性刷盘 ✓ 崩溃时也能保住数据 ✓
                }
                if (linesThisSession >= MAX_LINES) {
                    SablestopNow.LOGGER.warn("[hull-trace] 达到 {} 行上限，停止追踪", MAX_LINES);
                    close();
                    TRACED.clear();
                }
            }
        } catch (final Throwable t) {
            // 诊断工具绝不能影响游戏 ✓
            SablestopNow.LOGGER.warn("[hull-trace] 记录失败，已停用：{}", t.toString());
            close();
            TRACED.clear();
        }
    }

    /** 物理步计数器（= 写入文件的 tick 列 ✓ 每个物理步 +1 ✓ 便于工具精确对齐 ✓）。 */
    private static long physStep;

    private static void appendBody(final StringBuilder sb, final long scene, final int mountId,
                                   final String kind, final long tick) {
        final double[] st = SablePhysicsExt.bodyState(scene, mountId);
        if (st == null || st.length < 11) {
            return; // 刚体已被移除（这本身也是重要信号 ✗ 工具会看到序列中断 ✓）
        }
        sb.append(tick).append(',').append(kind).append(',').append(mountId);
        for (int i = 0; i < 11; i++) {
            sb.append(',').append(fmtNum(st[i]));
        }
        // 末尾补一个 colliders 便于工具判断；bodyState 第 11 个就是碰撞体数 ✓
        sb.append('\n');
    }

    private static String fmtNum(final double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "0";
        }
        return Double.toString(Math.round(v * 10000.0) / 10000.0);
    }

    private static void ensureWriter() throws IOException {
        if (writer != null) {
            return;
        }
        final Path dir = FMLPaths.GAMEDIR.get();
        outFile = dir.resolve("sablestopnow-hull-trace.csv");
        final boolean fresh = !Files.exists(outFile);
        writer = Files.newBufferedWriter(outFile, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (fresh) {
            writer.write("tick,kind,mountId,mass,x,y,z,vx,vy,vz,wx,wy,wz,colliders\n");
        }
        SablestopNow.LOGGER.warn("[hull-trace] 写入 {}", outFile);
    }

    /** 服务端停止时调用，flush 并关闭 ✓ */
    public static void close() {
        if (writer != null) {
            try {
                writer.flush();
                writer.close();
            } catch (final IOException ignored) {
                // 关文件失败无所谓 ✓
            }
            writer = null;
        }
    }

    public static Map<String, Object> status() {
        return Map.of(
                "traced", TRACED.size(),
                "lines", linesThisSession,
                "file", String.valueOf(outFile));
    }
}
