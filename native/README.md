# native/ —— 本模组可选的 Rapier 原生库（**不随仓库分发**）

## 一句话

本模组可以加载一个**自编译的 Rapier 原生库**来启用"模型碰撞"特性。
**这个原生库不随本仓库上传** ✓，需要的话由使用者**自己本地编译**并**手动放入**。

## 没有它时会怎样（默认就是这样）

| 情况 | 行为 |
|---|---|
| 没有 DLL | `NativeLibrary.load()` 返回 `null` ⇒ 不覆盖 Sable 的原生加载 ⇒ **本模组的碰撞特性关闭**（模型无碰撞）✓ |
| 手动放入 DLL | 加载成功 ⇒ 覆盖 Sable 的原生加载 ⇒ **本模组的碰撞特性启用** ✓ |

⇒ 也就是说：**开箱即用、模型无碰撞** ✓；想要碰撞就手动放一份你自己编的 DLL ✓。

## 手动放入的位置

```
<gamedir>/sablestopnow-natives/sable_rapier_x86_64_windows.dll
```

（`<gamedir>` 就是 `.minecraft`。加载器**先**看这个位置 ✓ 再看 jar 内资源 ✓。）

## 为什么要自己编（版权）

这个原生库是对 **Sable** 的 `sable_rapier` crate 的本地 fork ✓。
Sable 的许可是 **Polyform Shield License 1.0.0**（源码可见、**非开源**、含竞业限制 ✓）——
因此：

* **本仓库不分发**它的源码 ✗，也**不把它编译进 jar** ✗；
* 使用者请从 **Sable 官方渠道**取得你自己的副本 ✓，按下面步骤编译 ✓；
* 编译产物只属于你自己的本地环境 ✓。

出处与许可全文见仓库根 `THIRD-PARTY-NOTICES.md` ✓。

## 我们在这个 fork 里改了什么（**我们自己的描述** ✓ 不含对方源码 ✓）

1. 凸包 / 凸分解碰撞路径（`convex` 相关）：把逐轴内缩与分解结果接进碰撞流水线；
2. 分派器（`dispatcher`）：与 Java 侧 `NativeLibrary` 的"可选加载"配合，缺失时整条路径不启用；
3. 体素碰撞（`voxel_collider`）与组合体（`contraptions`）的若干修正。

> 具体补丁请对照你手上的 Sable 版本自行比对 ✓ —— 本仓库不提供对方源码的衍生物 ✗。

## 怎么编译（可选）

```bash
# 在你自己的 Sable sable_rapier crate 目录下
cargo build --release
# 产物 target/release/sable_rapier.dll
```
改名成 `sable_rapier_x86_64_windows.dll` ✓ 放进上面那个位置 ✓ 重启游戏 ✓。
