# Aeronautics: Tweaks & Toolkit（`sablestopnow`）

> **English readme：**[README_EN.md](README_EN.md) ｜ **更新日志：**[CHANGELOG.md](CHANGELOG.md)（[English](CHANGELOG_EN.md)）

一个面向 **Create: Aeronautics / Simulated** + **Sable 物理引擎** 的 NeoForge 实用模组（MC 1.21.1）。显示名已改为 *Aeronautics: Tweaks & Toolkit*，mod id 与包名仍是 `sablestopnow`（存档兼容）。

它包含两大块功能：

1. **力监控与限制**（历史功能）—— 为 Sable 物理体上的“力”做阈值过滤、记录与自动暂停保护；
2. **物理手杖增强** —— 把航空学物理手杖变成完整的建造工具：多人独立多选、所有权、区域选择、视角锁定、快照回退、整组缩放与幽灵化。

> 详细按键/配置说明见 [docs/staff-enhance-usage.md](docs/staff-enhance-usage.md)；实现细节与踩坑记录见 [docs/staff-enhance-design.md](docs/staff-enhance-design.md)；英文简介页文案见 [DESCRIPTION.md](DESCRIPTION.md)。

---

## 功能一览

### 力监控与限制（`/sablesn`）

- 超限力过滤：超过 `threshold` 的力可被丢弃；`excluded_groups` 可放行指定力组（如 `sable:gravity`）。
- 自动暂停：过滤触发时可自动暂停物理并全服广播，附点击建议 `/sablesn forces`。
- 力记录：`/sablesn forces [page]` / `/sablesn forces filtered [page]` 分页查看，目标体 ID 点击即填 `/tp` 命令。
- 分裂确认：热力图切分物理体前要求 `/sablesn confirm` / `/sablesn deny`。
- 暂停步进（`/sablesn tick <步数>`）：物理暂停时按指定数量步进，结束后自动恢复暂停。
- 超速自动锁定：物理体速度超过 `speed_limit_threshold` 时自动锁定并在全服聊天提示（**可点击填充传送指令**；正在被拖拽或正在被缩放的物理体不会被误锁）。
- 新物理体自动锁定、禁用方块放置碰撞检测、物理体表面描边/坐标轴渲染等辅助项。

### 物理手杖增强（总开关 `[staff_enhance].enable_staff_enhance`）

**全部按键都是 Minecraft `KeyMapping`**，可在「选项 → 按键控制 → **Aeronautics: Tweaks & Toolkit**」里随时修改，改完立即生效（无需重启）：

| 按键 | 默认 | 作用 |
|---|---|---|
| 多选模式 | 左 Ctrl | 进入/退出多选；进入时会把手里的选中队列重新登记为「我的」 |
| 区域选择 | Z | 进入/取消区域选择（滚轮调距离、视线定方向、右键定角点） |
| 整组无碰撞 | V | 对**整个选中队列**切换无碰撞标记 |
| 整组归中 / 视角锁定 | C | 整组控制中=把质心缓拉向视线中央；其余情况=长按锁定视角 |
| 所有权 | O | 对选中队列设置/取消所有权 |
| 快照 | K | 创建/取消整个队列的快照 |
| 回退快照 | R | 把快照整体回退到创建时的状态 |
| 缩放 | X | 按住 X + 滚轮缩放选中队列 |
| 穿透修饰键 | 左 Alt | 配合滚轮调穿透层数（**任何时候可用**） |
| 打开设置界面 | Ctrl+O | 打开本模组设置界面（不需要持杖） |

主要能力：

- **多人独立选择**：每人一条队列；同一物理结构同一时刻只能被一个玩家选中，已被他人选中/拥有的体会被拒绝并提示是谁；描边颜色按玩家分配（12 色），他人的选择只在同维度 64 格内渲染。
- **所有权**：O 设置/取消，落盘保存，准星指向某结构时 HUD 显示所有者；OP 可用 `/sablesn owner` 管理。
- **区域选择**：Z 进入，滚轮调选择点距离、视线定方向，第一点前是 1 格预览框；右键定第一个角点后，**实时以黄绿色预描边「将会被选中」的结构**；右键定第二点即把长方体相交的结构全部入队。
- **视角锁定**：不在多选/整组控制时长按 C，每 tick 选取与视线夹角最小的物理结构并锁定视角，松开解锁。
- **快照 / 回退**：K 存、R 回退，方块/位置/朝向/速度/名称全部还原；快照按玩家独立。
- **整组缩放**：X + 滚轮，以整组**公共质心**为基准等比缩放，形状不散架；HUD 显示当前倍率与准星所指结构的倍率。
- **幽灵化**：正在被拖拽的体不再与拖拽它的玩家碰撞；缩放 ≠ 1 的体不再与任何玩家碰撞（两项均可在设置界面关闭）。
- **整组控制**：退出多选后右键队列成员进入；转视线/行走移动整组，滚轮调距离，长按 C 归中，TAB+鼠标绕质心旋转，左键=整队列锁定切换，V=整队列无碰撞切换，右键结束。
- **描边与 HUD**：选中/悬停用 Create 蓝图风的细长长方体盒（粗细可调；准星命中队列成员时整组加粗）；HUD 只显示当前正在做的事（模式/穿透/已选数量/区域选择进度/视角锁定/结构名与所有者/缩放倍率）。

## 按键与配置

配置文件：`.minecraft/config/sablestopnow-common.toml`，或游戏内 **Ctrl+O** 打开的设置界面（所有改动即时写回文件）。

- `[force_limiter]`：阈值、豁免组、自动暂停、锁定新体、放置碰撞、分裂确认、超速锁定（`speed_limit_enabled` / `speed_limit_threshold`）、描边/坐标轴等。
- `[staff_enhance]`：总开关、描边粗细、缩放灵敏度与上下限、幽灵化开关等（键位改到原版按键设置里）。

| 配置项 | 默认 | 说明 |
|---|---|---|
| `enable_staff_enhance` | false | 增强总开关 |
| `staff_outline_thickness` | 0.06 | 选中/悬停轮廓粗细 |
| `staff_outline_bold_scale` | 2.2 | 整组高亮时的加粗倍数 |
| `scale_sensitivity` | 0.08 | X + 滚轮缩放灵敏度 |
| `scale_min` / `scale_max` | 0.2 / 8.0 | 缩放范围 |
| `dragged_no_player_collision` | true | 被拖拽的体不与拖拽它的玩家碰撞 |
| `scaled_no_player_collision` | true | 被缩放的体不与任何玩家碰撞 |
| `ghost_real` | false | V 无碰撞真实生效（与其它 Sable 体；实验性） |

## 命令

- `/sablesn forces [page]`、`/sablesn forces filtered [page]`
- `/sablesn confirm`、`/sablesn deny`
- `/sablesn tick <步数>`（需权限 2）
- `/sablesn owner list | clear <玩家> | clear all`（需权限 2）

## 依赖

- Minecraft **1.21.1**，NeoForge **21.1.248**
- **Sable 2.0.4**（物理引擎）
- **Simulated / Aeronautics 1.3.1**（bundled，提供物理手杖与锁定）
- Create 6.0.10（随 Sable 生态，可选）

## 构建

```bash
gradlew.bat build        # 产物在 build/libs/aeronautics-tweaks-toolkit-1.1.0.jar
gradlew.bat runClient    # 开发客户端
```

## 许可证

见 `gradle.properties` 的 `mod_license`。仓库内 `TEMPLATE_LICENSE.txt` 为 MIT 模板，发布前请按需声明。

## 已知限制

- **缩放是「视觉 + 玩家碰撞」层面的**：Rapier 的碰撞体尺寸与质量不随缩放改变，缩放体之间的物理仍按原尺寸结算；Sable 的序列化会丢掉 `scale`，因此由本模组自行持久化。
- 幽灵化只关闭碰撞，Sable 的搭车逻辑仍在（站上去仍可能被带走）。
- 「无碰撞」默认仅视觉+存档占位；开启 `ghost_real` 后真实生效，但只对**其它 Sable 物理体**有效（地形/玩家仍会碰撞）。
- 快照不落盘（重启即失效），回退是「移除+重建」，有一瞬间重生。
- 整组控制基于逐成员马达驱动，超大/高速结构可能有轻微弹动（服务端刚度/阻尼在 `server/StaffEnhanceServer`）。
