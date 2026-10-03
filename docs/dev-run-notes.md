# 本地调试约定（自建 Sable 原生库相关）

> 这份文档记录 2026-10 搭建"自建凸包碰撞"过程中**踩过的坑与约定** ✓
> 目的：下次不要再踩一遍 ✗

## 一、运行客户端的铁律

| 规则 | 为什么 |
|---|---|
| **启动客户端前，先关掉上一个** | 同时跑两个客户端会占满内存 ✗ 而且两个进程都在写 `run/logs/` ✗ 日志会串，排查时会看到"不属于这次"的行 ✓ |
| **客户端运行期间绝不编译模组** | Gradle 会重写 `build/` ✗ 正在跑的客户端可能在用这些文件 ✓ 轻则行为错乱，重则崩 ✓ |
| 收到用户反馈后**可以自行关闭客户端** | 用户已授权 ✓ |

### ⚠⚠ 关客户端的正确写法（我在这里犯过错 ✗）

**别用内存大小过滤** ✗ —— 我一开始用 `WorkingSet64 -gt 500MB` ✓
结果**刚启动的客户端还没到 500MB** ✗ → 漏掉了 ✓ → **同时起了两个客户端** ✗✗
（用户为此专门提醒过我 ✓）

**要按命令行识别** ✓，并且**确认归零**再启动 ✓：

```powershell
# 关掉所有 MC 客户端（含刚启动、内存还很小的）
Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
  Where-Object { $_.CommandLine -match 'net\.minecraft\.client\.main\.Main|runClient' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }

# 必须确认是 0 才继续启动
(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
  Where-Object { $_.CommandLine -match 'net\.minecraft\.client\.main\.Main|runClient' } |
  Measure-Object).Count
```

**顺带**：`Get-CimInstance` 的 `CreationDate` 在本机转不成时间 ✗（返回 null ✓），
要拿启动时间用 `Get-Process -Id N` 的 `.StartTime` ✓

## 二、原生库怎么才能被真正加载（这是最大的坑 ✗）

**别去替换 `run/.sable/natives/` 里的 DLL** ✗ —— 实测无效：
Sable 每次启动都从自己 jar 里的 `natives/sable_rapier/sable_rapier_binaries.zip.l4z`
（**LZ4 压缩的 zip**）解压并**覆盖**那个目录 ✓

判定方法（指纹对比）：

```
运行中进程加载的：51A06DC79FCB77DE  ← 和原版完全相同 = 我们的没生效 ✗
我们编出来的：    4932DE9FC226A7DF
```

**正确做法**：把我们编的 DLL 打进**本模组 jar**，再用 Mixin 取消 Sable 的加载 ✓

```
src/main/resources/sablestopnow/native/sable_rapier_x86_64_windows.dll   ← build.gradle 的
                                                                            syncNativeLibrary 同步
com.ovo.sablestopnow.mixin.rapier.Rapier3DNativeOverrideMixin
    @Inject(method = "loadLibrary", at = @At("HEAD"), cancellable = true)
    → NativeLibrary.load() + ci.cancel()
```

`NativeLibrary.load()` 的两条要点：
1. 抽出文件**只做一次**，但 `System.load` **每次调用都执行** ✓
   —— JNI 符号解析是**按类加载器**的，多登记一次才保险 ✓
2. **必须只加载一份** ✗✗ —— Rapier 是静态链接的，两份 DLL = 两个互不接触的物理世界 ✓

## 三、凸包碰撞的三个必备知识

### 1. 坐标是**刚体局部的质心系** ✓

Sable 生成接触时算的就是 `local - center_of_mass`（`marten/src/algo.rs` L38-43），
`collider.rs` L120 也把 `local_com` 设成 ZERO ✓
**实测印证过** ✓ 不减质心的话碰撞体会落到几百万格之外（plot 坐标是 2e7 量级 ✗）

### 2. ⚠⚠ 加完碰撞体**必须把质量重新写回去** ✓（否则"陷地→弹飞"✗）

```java
collider_set.insert_with_parent(collider, parent, &mut rigid_body_set);
//                            ↑ 它会用碰撞体【重算刚体质量】✗
```

我们的凸包是 `density(0)` ✓ → **质量被重算成 0** ✗ → **零质量刚体遇接触就产生巨大速度** ✓
→ 表现为"**先陷入地里再弹出来获得大量动量**" ✓

**修法**：加完立刻用**同一套逻辑**写回质量 ✓（`ScaledMass.upload`，和
`RapierPhysicsPipelineMixin` L133 同一份定义 ✓）。**每个实体加完都要重设一次** ✓
—— 同一刚体上每加一个碰撞体都会再重算一次 ✓

### 3. 凸包表示不了洞 ✗

现在传的是**整个网格的顶点** → `convex_hull` 取单个凸包 ✓ → **带孔结构（环面/管子）会被填实** ✗
解法是**凸分解**（`parry3d` 自带 `convex_decomposition` ✓）后分多次 `attachConvex` ✓ —— 待做 ✓

## 四、开关与兜底

| 项 | 位置 |
|---|---|
| **曲面是否走凸包路** | `VoxelCollisionService.USE_CONVEX`（`false` 即切回体素路 ✓ 体素路完整保留 ✓）|
| 原生库源码 | `depends/sable-main/.../rapier/src/convex.rs`（新增）+ `lib.rs` 一行 `mod` ✓ |
| Rust 侧依赖改写 | `rapier/Cargo.toml` 把 git 依赖改成**本地路径** ✓（GitHub 的 git 协议不通 ✗ 但 HTTP 通 ✓，fork 是走镜像拉 tarball 拿到的 ✓）|

Rust 编译命令（在 `depends/sable-main/sable_rapier/src/main/rust`）：

```powershell
cargo build --release     # 编完 target/release/sable_rapier.dll，55 个 JNI 符号
```

然后 `gradlew build` 会经 `syncNativeLibrary` 自动把它同步进模组资源 ✓

## 五、客观依据：基准台

`depends/collision-bench`（Rust + 官方 Rapier，**不用开游戏** ✓）：

```powershell
cd depends/collision-bench
cargo run --release
```

实测结论（φ12×高3 圆柱，侧面落地）：

| 表示 | ms/步 | 接触对 | 陷地 | 弹跳 |
|---|---|---|---|---|
| 体素楼梯 | 8.561 | 12593 | 0.063 | 9 次 ✗ |
| **凸包** | **0.008** | **1** | **0.001** | **1 次** ✓ |
| 每块一刚体 + 固定约束 | — | — | −597 ✗ | 回弹 626 ✗✗ **已否定** |

**改任何东西之后都用它对照** ✓ —— 这是判断"有没有变好"的唯一客观依据 ✓

## 六、工作流铁律（用户 2026-10 明确要求）

1. **收到反馈后，我自己干掉客户端并自己读日志** —— 不再找用户要日志行。
   关法按命令行识别（见第一节），并确认归零。

2. **以后都是先自己测，测好了再开客户端** —— 任何物理参数/几何改动，
   **必须先在 `depends/collision-bench` 里跑出数**（不用开游戏，几十秒出结果），
   确认变好之后，才允许启动客户端。

   这一条是被代价换来的：本会话我"凭物理直觉"连着改坏 4 次
   （圆角凸包 ✗、CCD 子步 3→16 ✗、接触频率往低 ✗、碰撞体 density=1.0 ✗），
   **每一次基准台都能提前否掉**。直觉在物理参数上是不可靠的。

3. **不开游戏就能测的维度**（基准台已支持，用环境变量，不用重编）：
   - `BENCH_HZ`  物理频率（默认 20，对齐 Sable 的 dt = 1/20）
   - `BENCH_FREQ` 接触弹簧频率（Sable 用 40）
   - `BENCH_DAMPING` 接触阻尼比（Sable 用 5.0）
   - 场景矩阵里有：平放/棱接触(半面角) × 落 2 格/落 10 格
   - 指标：ms/步、接触对、**陷地 Δy**、**弹跳次数**、**最大回弹**、稳定步数

4. **游戏内还有原生诊断**：`SablePhysicsExt.bodyState` 返回
   `[质量, 位置xyz, 线速度xyz, 角速度xyz, 碰撞体数]`，由我们自己的 LOGGER 打
   （⚠ 不要用 Rust 的 log::warn! —— 会被 Sable 的 fern 过滤器吃掉，我踩过 ✗）。

## 七、质量修复的现状（供后续接手）

实测链路（每一步都有日志证据）：

| 观察 | 结论 |
|---|---|
| `刚体状态` 质量一直是 **0.5000** | 实体的质量**从未**贡献给刚体 ✗ |
| 首次实测 total 也是 0.5000 | 合成跑了，但**实体的 part 质量为 0** ✗ → `SOLID_DENSITY` 被我误改成 0.0 |
| 改回 1.0 后 `mass composed: … -> total=193.093` | **质量正常了** ✓（跟踪器每方块 0.5，该实体约 390 方块 ✓）|

⚠ **顺序坑**：`刚体状态` 那行诊断目前跑在**合成之前**，所以它仍显示 0.5000 ✗
—— 要把它移到合成块**之后**，两个数才能对上。

⚠ **不要再走的路**：给碰撞体设 `density(1.0)` ✗ —— 会变成"重碰撞体 + 轻刚体"的
不自洽组合，求解器猛烈弹开（实测"弹得比之前远得多" ✗）。质量只能走 `setMassProperties` ✓。
