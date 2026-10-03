package com.ovo.sablestopnow.sketch;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 二维草图约束求解器：Java 门面，背后是 EZPZ（Rust）编成的<b>原生动态库</b>，
 * 通过 <b>JNA</b>（libffi 运行期绑定）调用它导出的 C ABI。
 *
 * <h2>为什么是原生库 + JNA（两条更"漂亮"的路都试过，都走不通）</h2>
 *
 * <p><b>WASM + Chicory</b>（没有 natives、跨平台一致，本来最理想）：
 * faer 在 wasm 上有两套互相独立的 SIMD 实现（`pulp` 与 `gemm`），只关一个另一个照旧执行
 * `V128_*`，而 Chicory 的默认解释器不支持；换 `SimdInterpreterMachine` 又要求
 * `--add-modules jdk.incubator.vector`。详见 {@code docs/sketch-solver-wasm-findings.md}。
 *
 * <p><b>原生库 + FFM（Panama）</b>：本模组按 NeoForge 1.21.1 的基线编译成
 * <b>Java 21</b>，而 {@code java.lang.foreign} 在 Java 21 里仍是<b>预览 API</b>，
 * 需要 {@code --enable-preview} 才能编译与运行 —— mod 没法要求玩家加这个参数。
 * FFM 到 Java 22 才转正。
 *
 * <p><b>JNA</b> 在 Java 21 上是普通库，且用运行期绑定，所以能直接调用我们那份
 * 已经过 30 项离线测试的 C ABI —— Rust 侧一行都不用改。
 * 代价是比 FFM 慢，但草图求解是秒级频率，完全无所谓。
 *
 * <p>⚠ 这个类**刻意不引用任何 Minecraft 类**，因此可以用
 * {@code tools/SketchSolverCheck.java} 脱离游戏直接跑，作为离线门禁。
 *
 * <h2>变量模型</h2>
 * 一张草图是一串平坦的标量**变量**（每个坐标、每个半径各占一个）。调用方给出初值，
 * 求解后同一数组被就地覆写成解。原生侧保证「变量下标 i ↔ 内部 Id 编号 i」一一对应。
 *
 * <h2>线程安全</h2>
 * {@link #solve} 已加锁。原生库本身无状态（所有状态都在调用方传入的缓冲区里），
 * 加锁只是为了不让两个线程同时写同一块暂存内存。
 */
public final class SketchSolver implements AutoCloseable {

    /** 必须与 Rust 侧 {@code ABI_VERSION} 一致；不一致说明 .dll/.so 是旧的。 */
    public static final int EXPECTED_ABI_VERSION = 2;

    /** 资源目录前缀。库文件按平台命名，例如 {@code ezpz_windows-x86_64.dll}。 */
    public static final String RESOURCE_DIR = "/sablestopnow/sketch/";

    // ---- 约束种类：必须与 Rust 侧 operand_count/takes_const 保持一致 ----
    /** 固定一个变量为某值。操作数 {@code v}，带常量。 */
    public static final int FIXED = 1;
    /** 两点重合。操作数 {@code ax ay bx by}。 */
    public static final int COINCIDENT = 2;
    /** 两点距离。操作数 {@code ax ay bx by}，带常量。 */
    public static final int DISTANCE = 3;
    /** 线段水平。操作数 {@code ax ay bx by}。 */
    public static final int HORIZONTAL = 4;
    /** 线段竖直。操作数 {@code ax ay bx by}。 */
    public static final int VERTICAL = 5;
    /** 两线段等长。操作数 8 个下标。 */
    public static final int EQUAL_LEN = 6;
    /** 点 M 是线段 AB 的中点。操作数 {@code ax ay bx by mx my}。 */
    public static final int MIDPOINT = 7;
    /** 圆半径。操作数 {@code cx cy r}，带常量。 */
    public static final int RADIUS = 8;

    /**
     * 这种约束是否吃一个常量。
     *
     * <p>⚠ 必须与 Rust 侧 {@code takes_const()} 完全一致。
     * 这里是「约束程序」与「常量数组」能否对齐的**唯一**依据：
     * 只要给不吃常量的约束（比如 HORIZONTAL）也塞一个值，后面所有常量就会整体错位 ——
     * 表现是"求解成功了，但尺寸用到了别的约束的值"，非常难查。
     * （这个坑真的踩过：矩形草图的宽塌成 0、高变成 3，就是常量数组错位造成的。）
     */
    public static boolean takesConstant(final int kind) {
        return kind == FIXED || kind == DISTANCE || kind == RADIUS;
    }

    /** 这种约束需要几个操作数（变量下标）。与 Rust 侧 {@code operand_count()} 一致。 */
    public static int operandCount(final int kind) {
        return switch (kind) {
            case FIXED -> 1;
            case COINCIDENT, DISTANCE, HORIZONTAL, VERTICAL -> 4;
            case EQUAL_LEN -> 8;
            case MIDPOINT -> 6;
            case RADIUS -> 3;
            default -> throw new IllegalArgumentException("未知的约束种类 " + kind);
        };
    }

    // ---- 错误码：必须与 Rust 侧 fail() 的取值一致 ----
    private static final int ERR_OK = 0;
    private static final int ERR_NO_VARS = -1;
    private static final int ERR_PROGRAM_TRUNCATED = -2;
    private static final int ERR_VAR_OUT_OF_RANGE = -3;
    private static final int ERR_CONSTS_EXHAUSTED = -4;
    private static final int ERR_UNKNOWN_KIND = -5;
    private static final int ERR_VAR_COUNT_MISMATCH = -6;
    private static final int ERR_SOLVER_FAILED = -7;

    private static SketchSolver bundled;

    /** C ABI 的直接映射。名字必须与 Rust 的 {@code #[no_mangle] extern "C"} 完全一致。 */
    private interface Ezpz extends Library {
        int sketch_abi_version();

        int sketch_init();

        /**
         * @return 0 成功；负数为错误码（-7 = 结构性无解，属正常交互情况）
         */
        int sketch_solve(Pointer vars, int varCount, Pointer cons, int consLen,
                         Pointer consts, int constsLen, Pointer info);

        Pointer sketch_last_error();

        int sketch_last_error_len();
    }

    private final Ezpz lib;

    private SketchSolver(final Ezpz lib) {
        this.lib = lib;
        final int abi = lib.sketch_abi_version();
        if (abi != EXPECTED_ABI_VERSION) {
            throw new IllegalStateException("sketch 原生库 ABI 版本不匹配：库是 " + abi
                    + "，Java 期望 " + EXPECTED_ABI_VERSION + "。需要重新编译 tools/ezpz-wasm。");
        }
        final int init = lib.sketch_init();
        if (init != EXPECTED_ABI_VERSION) {
            throw new IllegalStateException("sketch_init 返回 " + init + "，与期望的 ABI 版本不符");
        }
    }

    /** 从磁盘上的原生库加载。 */
    public static SketchSolver load(final Path nativeLibrary) {
        try {
            return new SketchSolver(Native.load(nativeLibrary.toAbsolutePath().toString(), Ezpz.class));
        } catch (final Throwable t) {
            throw new IllegalStateException("加载原生库失败：" + nativeLibrary, t);
        }
    }

    /**
     * 加载打包在 mod 里的求解器；进程内只解一次。
     *
     * <p>原生库不能直接从 jar 里加载，所以先解压到临时文件。
     */
    public static synchronized SketchSolver bundled() {
        if (bundled == null) {
            final String name = platformLibraryName();
            try (InputStream in = SketchSolver.class.getResourceAsStream(RESOURCE_DIR + name)) {
                if (in == null) {
                    throw new IllegalStateException("mod 里没有当前平台的原生库 " + RESOURCE_DIR + name
                            + " —— 这个平台还没编译（见 tools/ezpz-wasm/README）");
                }
                final Path tmp = Files.createTempFile("ezpz-", suffixFor(name));
                tmp.toFile().deleteOnExit();
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                bundled = load(tmp);
            } catch (final IOException e) {
                throw new IllegalStateException("释放原生库到临时文件失败", e);
            }
        }
        return bundled;
    }

    /** 当前平台对应的库文件名。 */
    public static String platformLibraryName() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        final String archTag = switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
        if (os.contains("win")) {
            return "ezpz_windows-" + archTag + ".dll";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "ezpz_macos-" + archTag + ".dylib";
        }
        if (os.contains("linux")) {
            return "ezpz_linux-" + archTag + ".so";
        }
        throw new IllegalStateException("不支持的操作系统：" + os);
    }

    private static String suffixFor(final String libraryName) {
        final int dot = libraryName.lastIndexOf('.');
        return dot < 0 ? ".tmp" : libraryName.substring(dot);
    }

    /** 求解结果。{@code values} 与传入的初值同序。 */
    public record Result(boolean satisfied, boolean converged, int iterations, int unsatisfied,
                         double[] values) {

        /** 是否可信：解出来且所有约束都满足。 */
        public boolean ok() {
            return satisfied && unsatisfied == 0;
        }
    }

    /**
     * 求解一张草图。
     *
     * @param vars    变量初值，会被就地覆写成解（长度即变量个数）
     * @param program 约束程序流（见 {@link Program}）
     * @param consts  约束常量，按约束出现顺序取用
     */
    public Result solve(final double[] vars, final int[] program, final double[] consts) {
        if (vars.length == 0) {
            throw new IllegalArgumentException("草图至少要有一个变量");
        }
        synchronized (this) {
            // JNA 的 Memory 在堆外分配、自动对齐到平台要求，所以 f64/i32 各一块即可，
            // 不会出现拼接缓冲区导致 f64 错位的问题。
            final Memory varsMem = new Memory(Math.max(8L, (long) vars.length * 8));
            final Memory progMem = new Memory(Math.max(4L, (long) program.length * 4));
            final Memory constsMem = new Memory(Math.max(8L, (long) consts.length * 8));
            final Memory infoMem = new Memory(16);

            for (int i = 0; i < vars.length; i++) {
                varsMem.setDouble((long) i * 8, vars[i]);
            }
            for (int i = 0; i < program.length; i++) {
                progMem.setInt((long) i * 4, program[i]);
            }
            for (int i = 0; i < consts.length; i++) {
                constsMem.setDouble((long) i * 8, consts[i]);
            }

            final int rc = lib.sketch_solve(varsMem, vars.length, progMem, program.length,
                    constsMem, consts.length, infoMem);

            if (rc != ERR_OK && rc != ERR_SOLVER_FAILED) {
                // -7 是「结构性无解」，在交互式草图里是正常情况（比如过约束），
                // 由调用方看 Result 决定怎么提示，不该抛异常。其余错误码是输入有问题。
                throw new IllegalStateException("草图求解器拒绝了输入（" + describeCode(rc) + "）："
                        + lastError());
            }

            for (int i = 0; i < vars.length; i++) {
                vars[i] = varsMem.getDouble((long) i * 8);
            }
            return new Result(
                    infoMem.getInt(0) != 0,
                    infoMem.getInt(4) != 0,
                    infoMem.getInt(8),
                    infoMem.getInt(12),
                    vars);
        }
    }

    /** 读取原生侧最近一次的报错文本。 */
    public String lastError() {
        synchronized (this) {
            final Pointer ptr = lib.sketch_last_error();
            final int len = lib.sketch_last_error_len();
            if (ptr == null || len <= 0) {
                return "(无信息)";
            }
            // Rust 侧的错误缓冲是「零初始化的定长数组」，所以字符串一定以 NUL 结尾。
            final String s = ptr.getString(0);
            return s == null ? "(无信息)" : s;
        }
    }

    /** 出错码的可读名字，日志用。 */
    public static String describeCode(final int code) {
        return switch (code) {
            case ERR_OK -> "ok";
            case ERR_NO_VARS -> "没有变量";
            case ERR_PROGRAM_TRUNCATED -> "约束程序在中途截断";
            case ERR_VAR_OUT_OF_RANGE -> "约束引用了不存在的变量下标";
            case ERR_CONSTS_EXHAUSTED -> "约束常量不够用";
            case ERR_UNKNOWN_KIND -> "未知的约束种类";
            case ERR_VAR_COUNT_MISMATCH -> "求解器返回的变量个数不一致";
            case ERR_SOLVER_FAILED -> "结构上无解";
            default -> "未知错误码 " + code;
        };
    }

    @Override
    public void close() {
        // JNA 加载的库随进程存活，没有需要显式释放的句柄。
    }

    /**
     * 约束程序的构造器。
     *
     * <p>手写那段平坦的 {@code int[]} 很容易把操作数顺序写错，而且写错了不会崩、
     * 只会解出奇怪的结果。所以所有约束都从这里出，顺序在一处定义。
     */
    public static final class Program {
        private final List<Integer> ops = new ArrayList<>();
        private final List<Double> consts = new ArrayList<>();

        /** 固定变量 {@code v} 为 {@code value}。 */
        public Program fixed(final int v, final double value) {
            ops.add(FIXED);
            ops.add(v);
            consts.add(value);
            return this;
        }

        /** 两点重合。 */
        public Program coincident(final int ax, final int ay, final int bx, final int by) {
            ops.add(COINCIDENT);
            addAll(ax, ay, bx, by);
            return this;
        }

        /** 两点距离为 {@code distance}（线段两端点即线段长度）。 */
        public Program distance(final int ax, final int ay, final int bx, final int by,
                                final double distance) {
            ops.add(DISTANCE);
            addAll(ax, ay, bx, by);
            consts.add(distance);
            return this;
        }

        /** 线段水平。 */
        public Program horizontal(final int ax, final int ay, final int bx, final int by) {
            ops.add(HORIZONTAL);
            addAll(ax, ay, bx, by);
            return this;
        }

        /** 线段竖直。 */
        public Program vertical(final int ax, final int ay, final int bx, final int by) {
            ops.add(VERTICAL);
            addAll(ax, ay, bx, by);
            return this;
        }

        /** 两线段等长。 */
        public Program equalLength(final int a0x, final int a0y, final int a1x, final int a1y,
                                   final int b0x, final int b0y, final int b1x, final int b1y) {
            ops.add(EQUAL_LEN);
            addAll(a0x, a0y, a1x, a1y, b0x, b0y, b1x, b1y);
            return this;
        }

        /** 点 M 在线段 AB 的中点。 */
        public Program midpoint(final int ax, final int ay, final int bx, final int by,
                                final int mx, final int my) {
            ops.add(MIDPOINT);
            addAll(ax, ay, bx, by, mx, my);
            return this;
        }

        /** 圆的半径为 {@code radius}。 */
        public Program radius(final int cx, final int cy, final int r, final double radius) {
            ops.add(RADIUS);
            addAll(cx, cy, r);
            consts.add(radius);
            return this;
        }

        private void addAll(final int... values) {
            for (final int v : values) {
                ops.add(v);
            }
        }

        /** 打成平坦的 i32 程序流。 */
        public int[] toOps() {
            final int[] out = new int[ops.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = ops.get(i);
            }
            return out;
        }

        /** 打成常量数组。 */
        public double[] toConsts() {
            final double[] out = new double[consts.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = consts.get(i);
            }
            return out;
        }

        public int constraintCount() {
            return consts.size();
        }
    }
}
