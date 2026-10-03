package com.ovo.sablestopnow.server;

import com.ovo.sablestopnow.SablestopNow;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * **运行期物理侦测**：把"卡顿、穿模、虚空动量"这些只在实际跑起来才暴露的东西**量化并记下来**，
 * 但**绝不刷屏**。
 *
 * <h2>为什么需要它</h2>
 * 用户的要求："在 mod 里写一些运行侦测，例如定位穿模的发生细节、创建盒子/体素的数量、
 * 虚空动量的产生、还有性能的影响，当然要有一定阈值，防止 log 刷屏。"
 *
 * <p>这几轮我反复吃亏在**只能靠推理**：MSPT 十几万、体积被高估、质心传错把接触点放到
 * 几百万格外……都是"跑起来才看得见"的问题，而当时没有观测手段，只能猜。
 *
 * <h2>两条纪律</h2>
 * <ol>
 *   <li><b>阈值</b>：正常情况**一条都不打**（低于阈值直接丢掉）。所以日志里出现的就是异常。</li>
 *   <li><b>限流</b>：同一类消息最快 {@link #WINDOW_MS} 毫秒一次，
 *       且每条消息都带"这窗口内发生了几次"，**信息量不减但不刷屏**。</li>
 * </ol>
 */
public final class PhysicsDiag {

    private PhysicsDiag() {
    }

    /** 同一 key 的最短重复间隔（毫秒）。 */
    private static final long WINDOW_MS = 3000;

    /** MSPT 超过这个值才记（毫秒）。50 是 20TPS 的预算。 */
    public static final double MSPT_WARN_MS = 75.0;

    /** 一次重建的方块格数超过这个才记。 */
    public static final int CELLS_WARN = 400;

    /** 一次重建的盒子数超过这个才记。 */
    public static final int BOXES_WARN = 30000;

    /** 一次重建耗时超过这个才记（毫秒）。 */
    public static final long REBUILD_WARN_MS = 40L;

    /** 质量属性与上一次偏差超过这个比例才记（发现"质量算错"这类问题）。 */
    public static final double MASS_DRIFT = 0.25;

    private static final Map<String, Window> WINDOWS = new ConcurrentHashMap<>();

    /** 限流窗口。 */
    private static final class Window {
        long lastMs;
        int suppressed;

        boolean allow(final long now) {
            if (now - lastMs < WINDOW_MS) {
                suppressed++;
                return false;
            }
            lastMs = now;
            return true;
        }

        int takeSuppressed() {
            final int s = suppressed;
            suppressed = 0;
            return s;
        }
    }

    private static Window window(final String key) {
        return WINDOWS.computeIfAbsent(key, k -> new Window());
    }

    /**
     * 记一条（带限流与"被压掉几次"）。
     *
     * <p>⚠⚠ **占位符必须是 SLF4J 的 `{}`，不能是 printf 的 `%d`/`%s`。**
     *   我第一版用了 `%d`，结果日志里打出来的是**原样的格式串、数字全丢**
     *   —— 用户让我查 log，而 log 里没有数字，等于白记。SLF4J 只做 `{}` 替换。
     */
    private static void log(final String key, final String format, final Object... args) {
        final Window w = window(key);
        final long now = System.currentTimeMillis();
        if (!w.allow(now)) {
            return;
        }
        final int suppressed = w.takeSuppressed();
        final String tail = suppressed > 0 ? "（同类消息另有 " + suppressed + " 次被限流）" : "";
        SablestopNow.LOGGER.warn("[phys-diag] " + format + tail, args);
    }

    // ---------------------------------------------------------------- 重建

    /**
     * 记录一次碰撞几何重建。
     *
     * @param what  "体素路" / "盒子路"
     * @param cells 方块格数
     * @param boxes 盒子总数
     * @param colliders 创建的碰撞体个数
     * @param ms    耗时（毫秒）
     */
    public static void rebuild(final String what, final int cells, final int boxes,
                               final int colliders, final long ms) {
        // 阈值：全部低于就一条都不打
        final boolean slow = ms >= REBUILD_WARN_MS;
        final boolean many = cells >= CELLS_WARN || boxes >= BOXES_WARN;
        if (!slow && !many) {
            return;
        }
        log("rebuild", "{} 重建偏重：格={} 盒={} 碰撞体={} 耗时={}ms{}{}",
                what, cells, boxes, colliders, ms,
                cells >= CELLS_WARN ? " [格数超阈值]" : "",
                boxes >= BOXES_WARN ? " [盒数超阈值]" : "");
    }

    /** 体素路被预算闸门挡下（这是有意的保护，但要知道它发生了）。 */
    public static void budgetAbort(final int cells, final long boxes) {
        log("budget", "实体过大，体素路已回退盒子路：格={} 盒={}（防爆炸闸门）",
                cells, boxes);
    }

    // ---------------------------------------------------------------- 体积保真

    /** 体素体积与解析体积偏差超过这个比例才记（质量会跟着错这么多）。 */
    public static final double VOLUME_DRIFT = 0.2;

    /**
     * **体积保真对照**：体素化后"给物理的体积" vs "解析真实体积"。
     *
     * <p>这个数直接决定质量对不对：{@code newVoxelCollider} 的 volume 参数是物理侧算质量的依据。
     * 空心薄壳时它会**远小于**真实体积（质量被低估几个数量级），
     * 这正是"陷地/弹跳/拖动阻滞"的来源。偏差超过 {@link #VOLUME_DRIFT} 就记一条。
     */
    public static void volumeCompare(final String key, final double voxelVolume,
                                     final double trueVolume) {
        final double drift = Math.abs(voxelVolume - trueVolume) / trueVolume;
        if (drift < VOLUME_DRIFT) {
            return;
        }
        log("volume:" + key, "体素体积与真实体积差 {}%：体素={} 真实={}（质量会差同样比例）",
                drift * 100.0, voxelVolume, trueVolume);
    }

    // ---------------------------------------------------------------- 性能

    private static double lastMspt;

    /** 服务端 tick 的 MSPT 观测（只在超过阈值时记）。 */
    public static void tick(final MinecraftServer server, final long tickNanos) {
        final double mspt = tickNanos / 1.0e6;
        if (mspt < MSPT_WARN_MS) {
            lastMspt = mspt;
            return;
        }
        log("mspt", "MSPT 偏高：{}ms（阈值 {}）上一次 {}ms，载荷 {} 个玩家",
                mspt, MSPT_WARN_MS, lastMspt, server == null ? 0.0 : server.getPlayerCount());
        lastMspt = mspt;
    }

    // ---------------------------------------------------------------- 质量属性

    private static final Map<String, double[]> LAST_MASS = new ConcurrentHashMap<>();

    /**
     * 记录质量属性，并与上一次比较。
     *
     * <p>为什么要盯这个：体素路此前把 `volume` 硬编码成 1.0，
     * 让引擎从**上千个空心盒子**去推断质量与惯量 —— 那必然错。
     * 质量漂移超过 {@link #MASS_DRIFT} 就记一条，这样"质量忽然变了"是可见的。
     *
     * @param com 质心（相对装置原点）
     */
    public static void massProperties(final String key, final double mass, final double[] com,
                                      final double inertiaTrace) {
        final double[] prev = LAST_MASS.get(key);
        final double[] now = {mass, com[0], com[1], com[2], inertiaTrace};
        LAST_MASS.put(key, now);
        if (prev == null) {
            log("mass:" + key, "质量属性首次设定：m={} 质心=({},{},{}) 惯量迹={}",
                    mass, com[0], com[1], com[2], inertiaTrace);
            return;
        }
        if (prev[0] <= 0.0) {
            return;
        }
        final double drift = Math.abs(mass - prev[0]) / prev[0];
        if (drift < MASS_DRIFT) {
            return;
        }
        log("mass:" + key, "质量属性漂移：m {} → {}（{}%）质心 ({},{},{}) → ({},{},{})",
                prev[0], mass, drift * 100.0,
                prev[1], prev[2], prev[3], com[0], com[1], com[2]);
    }

    // ---------------------------------------------------------------- 穿模 / 虚空动量

    private static final Map<String, double[]> LAST_MOTION = new ConcurrentHashMap<>();

    /** 速度突变超过这个（方块/秒）才算"异常动量"。 */
    public static final double DV_WARN = 8.0;

    /**
     * **虚空动量**探测：一步之内速度变化超过 {@link #DV_WARN}，而这一步**没有施加外力**。
     *
     * <p>"虚空动量"是物理系统的典型病症：物体忽然自己加速/反向，来源往往是
     * 接触修正过冲（薄壳穿透）、或质量/惯量算错。这里把它**记下来**，
     * 而不是等用户在游戏里看到"弹跳"再回来猜。
     *
     * @param forceNanos 本 tick 该刚体上累计的外力冲量估计（0 表示没有外力）
     */
    public static void motion(final String key, final double[] velocity, final double[] position,
                              final double forceNanos) {
        final double[] prev = LAST_MOTION.get(key);
        LAST_MOTION.put(key, new double[]{velocity[0], velocity[1], velocity[2],
                position[0], position[1], position[2]});
        if (prev == null || forceNanos > 1.0e-6) {
            return; // 有外力时的大变化是正常的
        }
        final double dv = Math.sqrt(sq(velocity[0] - prev[0]) + sq(velocity[1] - prev[1])
                + sq(velocity[2] - prev[2]));
        if (dv < DV_WARN) {
            return;
        }
        final double dy = position[1] - prev[4];
        log("momentum:" + key,
                "疑似虚空动量：无外力但 Δv={} 方块/秒，Δy={}，v=({},{},{})",
                dv, dy, velocity[0], velocity[1], velocity[2]);
    }

    /**
     * **穿模探测**（间接）：一步之内竖直位移超过 {@link #SINK_WARN} 方块，
     * 而速度并不大 —— 那多半是"穿过了薄壳"而不是"正常下落"。
     */
    public static final double SINK_WARN = 0.35;

    public static void penetration(final String key, final double dy, final double vy) {
        if (Math.abs(dy) < SINK_WARN) {
            return;
        }
        // 正常下落时 |dy| ≈ |vy|·dt；远大于它才可疑
        if (Math.abs(dy) < Math.abs(vy) * 0.1) {
            return;
        }
        log("penetration:" + key, "疑似穿透/陷落：Δy={} 而 vy={}（位移远大于速度应产生的量）",
                dy, vy);
    }

    private static double sq(final double v) {
        return v * v;
    }

    /** 资源/世界切换时清掉历史，免得跨世界比较出假漂移。 */
    public static void reset() {
        WINDOWS.clear();
        LAST_MASS.clear();
        LAST_MOTION.clear();
        lastMspt = 0.0;
    }
}
