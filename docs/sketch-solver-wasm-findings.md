# 草图求解器：EZPZ → wasm32 → Chicory 的实测结论

> 状态：**ABI 与工具链已打通并验证；求解器在 wasm 上因 SIMD 无法运行。等你定方向。**
> 记录时间：第一阶段起步阶段。

## 已经成立的部分（有证据）

| 事项 | 结果 | 证据 |
|---|---|---|
| Rust → wasm32 工具链 | ✅ | `cargo build --target wasm32-unknown-unknown --release` 出 419.5 KB `.wasm` |
| EZPZ 能编译到 wasm32 | ✅ | 依赖树里 `faer`/`gemm`/`qd`/`raw-cpuid` 全部编过 |
| EZPZ 真的链进产物 | ✅ | 二进制里含 `ezpz-0.2.29/src/constraints.rs`、`solver/newton.rs`、`faer-0.24.4/.../cholesky.rs` |
| 自研 C ABI | ✅ | 8 种约束（FIXED/COINCIDENT/DISTANCE/HORIZONTAL/VERTICAL/EQUAL_LEN/MIDPOINT/RADIUS），11 个导出 |
| **原生**契约测试 | ✅ **9/9 通过** | `cargo test --release`，含真实算例（3×2 矩形） |
| Java 侧加载 | ✅ | Chicory `Parser.parse` → `Instance.builder(...).build()` → 读 `sketch_abi_version` / `sketch_init` 正常 |
| 空程序 / 单约束 | ✅ | JVM 探针可跑通并返回 |
| **真实矩阵求解** | ❌ | 一进入 faer 的牛顿迭代就抛 `V128_LOAD` |

## 卡点：faer 的 wasm SIMD

失败形态固定：

```
java.lang.RuntimeException: Machine doesn't recognize Instruction
  AnnotatedInstruction{instruction=...: V128_LOAD ...}
    at com.dylibso.chicory.runtime.InterpreterMachine.evalDefault
```

即 wasm 里执行了 **simd128** 指令，而 Chicory 的默认解释器不认识。

### 试过的四条路（都已证伪，别再重复）

| # | 做法 | 结果 |
|---|---|---|
| 1 | 以为 `wasm32-unknown-unknown` 默认开 simd128 | ❌ 默认**不开**（`rustc --print cfg` 可证），所以不是这个原因 |
| 2 | `RUSTFLAGS="-C target-feature=-simd128"` 重编 | ❌ 无效，`.wasm` 体积分毫不变（377.8 KB）——SIMD 内核被无条件发出 |
| 3 | `pulp-wasm-simd-flag::disable_simd128()`（pulp 的开关） | ❌ 无效；更糟的是**加上它之后连「空程序」都开始 trap**，说明它会让 pulp 进入不一致状态 |
| 4 | `gemm_common::set_wasm_simd128(false)`（gemm 的开关） | ⚠ 部分有效（空程序、单约束能过），但一进真实求解仍是 `V128_LOAD` |
| 5 | 两个开关**同时**关 | ❌ 仍 `V128_LOAD`（空程序反而也挂） |
| 6 | Chicory 的 `SimdInterpreterMachine`（官方 SIMD 支持） | ⚠ 能解码执行，但随后 Rust 侧 panic（`Trapped on unreachable instruction`）；且要求 `--add-modules jdk.incubator.vector`，**mod 没法要求玩家加 JVM 参数** |

### 关键事实

- 有两套**互相独立**的 wasm SIMD 实现：**pulp**（faer 的 SIMD 抽象层，92 处提到 simd128）和
  **gemm**（矩阵乘后端，13~14 处）。只关一个，另一个照旧执行 `V128_*`。
- `gemm-common` 的 `DEFAULT_WASM_SIMD128` 在启用 `wasm-simd128-enable` feature 时是 **`true`**，
  也就是说 gemm 在 wasm 上**默认走 SIMD**，与 target feature 无关。
- `ezpz` 对 `faer` 的依赖是 `default-features = false, features = ["std", "sparse-linalg"]`，
  但 `gemm`/`pulp` 是 faer 的**非可选依赖**，关不掉。
- Cargo 的 feature 是可加的：**无法**从我们的 crate 里关掉别人开启的 feature
  （除非 `[patch.crates-io]` 把 `pulp`/`gemm-common` 换成自己 vendored 的版本）。

## 结论：最终走「原生库 + JNA」（已验证 30/30 通过）

`java.lang.foreign`（FFM/Panama）本来是最合适的选择，但**在本项目里不可用**：
本模组按 NeoForge 1.21.1 的基线编译成 **Java 21**
（`build.gradle` 的 `java.toolchain.languageVersion = 21`），而 FFM 在 Java 21 里
**仍是预览 API**，需要 `--enable-preview` 才能编译与运行 —— mod 没法要求玩家加这个参数。
FFM 到 **Java 22** 才转正。实测：用 JDK 25 的 javac 直接编可以过，但 `gradlew compileJava`
（release 21）报 57 个「Arena 是预览 API，默认情况下处于禁用状态」。

**JNA** 是同一架构（Rust 原生库、无 wasm）里 Java 21 可用的绑定方案：
用 libffi 做运行期绑定，能直接调用我们那份 C ABI（`sketch_*`），
所以 **Rust 侧一行都没改**，之前 9 项原生契约测试也全部保留。

| 方案 | 结果 |
|---|---|
| WASM + Chicory | ❌ faer 的 SIMMD 指令 Chicory 默认解释器执行不了；SIMD 机器要 incubator 模块 |
| 原生库 + FFM | ❌ Java 21 里 FFM 是预览 API，要 `--enable-preview` |
| **原生库 + JNA** | ✅ **离线 30/30 通过，`gradlew compileJava` 也通过** |

最终链路：

```
Rust (EZPZ + faer)  →  ezpz_windows-x86_64.dll  →  JNA (libffi)  →  SketchSolver
     tools/ezpz-wasm/       src/main/resources/sablestopnow/sketch/      Java 21 可用
```

### 附：JNA 的 native access 警告

JDK 24+ 会对 `System::load`（JNA 内部用它加载库）报
「A restricted method in java.lang.System has been called」。
**正确做法是写进 MANIFEST**，而不是让玩家加 `--enable-native-access`：

```groovy
tasks.named('jar', Jar) {
    manifest { attributes('Enable-Native-Access': 'ALL-UNNAMED') }
}
```

已加在 `build.gradle` 里。

### 仍然待办

- **发布前**：JNA 要走 JarJar（`jarJar "net.java.dev.jna:jna:..."`），
  否则正式产物里没有它。第一阶段先保证 `runClient` 能跑通。
- **多平台**：目前只编了 `windows-x86_64`。linux / macOS（x64 与 aarch64）需要各自 `cargo build --release`。
- **Rust 构建时间**：`--release`（LTO + `codegen-units=1`）要 2~3 分钟。
  **迭代时用 `cargo build`（dev profile）**，只有出货才 `--release`。

