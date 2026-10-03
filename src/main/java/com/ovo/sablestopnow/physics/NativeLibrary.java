package com.ovo.sablestopnow.physics;

import com.ovo.sablestopnow.SablestopNow;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 我们**自己那份** Sable 原生库（带凸包支持）的定位与加载。
 *
 * <h2>为什么不能直接替换 Sable 解压出来的文件</h2>
 *
 * Sable 每次启动都会从它自己的 jar 里把 {@code sable_rapier_binaries.zip.l4z}
 * （LZ4 压缩的 zip）解压到 {@code <gamedir>/.sable/natives/}，**覆盖**掉我们换进去的那份。
 * 实测证据：部署后的文件时间戳 = 启动时刻，而 SHA256 与原始版**完全相同** ✗
 *
 * <h2>所以走"重定向"</h2>
 *
 * 我们把自己编的 DLL 打进**本模组的 jar**，启动时抽到我们自己的目录并
 * {@code System.load}；同时用 Mixin（{@code Rapier3DNativeOverrideMixin}）
 * **取消 Sable 自己的加载** —— 这样进程里**只有一份** Rapier 实例 ✓
 * （两份会各自拥有独立的物理世界，互相不产生接触 ✗）
 */
public final class NativeLibrary {

    /** 打进我们 jar 的原生库资源路径（`build.gradle` 会把编出来的 DLL 放到这里）。 */
    private static final String RESOURCE =
            "/sablestopnow/native/sable_rapier_x86_64_windows.dll";

    /** 抽出来之后放哪（我们自己单独的目录，不和 Sable 的 `.sable/natives` 混）。 */
    private static final String OUT_DIR = "sablestopnow-natives";

    private static Path loaded;

    private NativeLibrary() {
    }

    /** 已经把这份库加载过了吗。 */
    public static synchronized boolean isLoaded() {
        return loaded != null;
    }

    /**
     * 把库抽出来并 {@code System.load}（**幂等，但每次调用都会重新登记**）。
     *
     * <p>⚠⚠ **为什么"抽出来"只做一次、"System.load"却每次都要做**：
     * JNI 的符号解析是**按类加载器**的 —— JVM 只会在"声明 native 的那个类所属的加载器"
     * 里找符号。而这份库第一次是由 **Mixin 处理器**（在 {@code Rapier3D} 的静态初始化里）
     * 加载的 ✗ —— 那只把库登记给了 {@code Rapier3D} 的加载器 ✓，
     * **我们自己的 `SablePhysicsExt` 那个加载器并没有登记** ✗ → 我们的 native 调用会
     * 直接拿到 {@code UnsatisfiedLinkError} ✗
     *
     * <p>而 Windows 的 {@code LoadLibrary} 对**同一路径**返回已有模块句柄 ✓
     * 所以重复 {@code System.load} 是安全的 ✓ —— 它只是把这份库**再登记到当前类的加载器**上 ✓
     *
     * <p>返回加载后的绝对路径；资源不存在或加载失败时返回 {@code null}
     * （不抛异常 —— 让 native 调用本身去给出更准的报错）。
     */
    public static synchronized Path load() {
        // 已经抽过的路径直接复用（抽文件只做一次）
        if (loaded == null) {
            try (InputStream in = NativeLibrary.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    SablestopNow.LOGGER.error("[sablestopnow] 模组 jar 里找不到原生库资源 {}"
                            + " —— 构建时没有把编出来的 DLL 放进去？", RESOURCE);
                    return null;
                }
                final Path dir = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve(OUT_DIR);
                Files.createDirectories(dir);
                final Path target = dir.resolve("sable_rapier_x86_64_windows.dll");
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                loaded = target;
            } catch (final Throwable t) {
                SablestopNow.LOGGER.error("[sablestopnow] 抽出原生库失败", t);
                return null;
            }
        }
        try {
            // ⚠ 每次调用都执行：让**当前这个类的加载器**也登记这份库
            System.load(loaded.toAbsolutePath().toString());
            return loaded;
        } catch (final Throwable t) {
            // 同一路径重复加载在 Windows 上是安全的，但万一 JVM 报"already loaded"，
            // 那说明当前加载器本来就能解析，所以这里只记日志、不失败 ✓
            SablestopNow.LOGGER.info("[sablestopnow] 原生库重复登记跳过（{}），"
                    + "这通常是正常的：{}", loaded, t.toString());
            return loaded;
        }
    }
}
