# SableStopNow（Sable_stop_now）

> **English readme：**[README_EN.md](README_EN.md)

一个面向 **Create: Aeronautics / Simulated** + **Sable 物理引擎** 的 NeoForge 实用模组（MC 1.21.1）。

它包含两大块功能：

1. **力监控与限制**（历史功能）—— 为 Sable 物理体上的“力”做阈值过滤、记录与自动暂停保护；
2. **物理手杖增强**（v1.0.2 新增）—— 为航空学物理手杖提供多选、框选、穿透选择、整组移动/旋转/锁定等实用操作。

> 详细的手杖增强按键/配置说明见 [docs/staff-enhance-usage.md](docs/staff-enhance-usage.md)。

---

## 功能一览

### 力监控与限制（`/sablesn`）

- 超限力过滤：超过 `threshold` 的力可被丢弃；`excluded_groups` 可放行指定力组（如 `sable:gravity`）。
- 自动暂停：过滤触发时可自动暂停物理并全服广播，附点击建议 `/sablesn forces`。
- 力记录：`/sablesn forces [page]` / `/sablesn forces filtered [page]` 分页查看，目标体 ID 点击即填 `/tp` 命令。
- 分裂确认：热力图切分物理体前要求 `/sablesn confirm` / `/sablesn deny`。
- 新物理体自动锁定、禁用方块放置碰撞检测、物理体表面描边/坐标轴渲染等辅助项。

### 物理手杖增强（总开关 `[staff_enhance].enable_staff_enhance`）

| 操作 | 效果 |
|---|---|
| 持杖按 **Ctrl** | 进入/退出多选（进入后接管并禁用原手杖右键拖拽/左键锁定） |
| 多选 **右键 / Shift+右键** | 加入 / 移出选中队列 |
| 多选 **Alt+滚轮** | 穿透层数选择（0–16） |
| 多选 **Z** | 框选：两次都取**玩家所在块**为角点（玩家移动后确认），框内物理体入队 |
| 退出多选后 **Shift+右键** | 清空选中队列 |
| 退出多选后 **左键** | 智能锁定切换（全锁→解锁；部分锁/全未锁→先全锁） |
| 退出多选后 **右键**组内成员 | 先解除全部锁定，再进入**整组控制** |
| 整组控制中 **转视线 / 行走** | 整组按视线相对位移动（起步不瞬移） |
| 整组控制中 **滚轮** | 沿“眼睛→质心”连线缩放距离 |
| 整组控制中 **长按 C** | 让质心缓慢滑到视线中央 |
| 整组控制中 **TAB+鼠标** | 绕质心旋转整组 |
| 非多选按 **V** | 切换“无碰撞”标记（占位：视觉图标+服务端存档，Sable 暂不支持真实穿模） |

选中/悬停物理体用统一青色“只描轮廓”线框 + 圆环图标，全部操作均有双语提示。

## 按键与配置

配置文件：`.minecraft/config/sablestopnow-common.toml`

- `[force_limiter]`：阈值、豁免组、自动暂停、锁定新体、放置碰撞、分裂确认、描边/坐标轴等。
- `[staff_enhance]`：总开关与全部手杖增强键位（GLFW 键码）与灵敏度：

| 键位/数值 | 默认 | 说明 |
|---|---|---|
| `enable_staff_enhance` | false | 增强总开关 |
| `key_multi_select` | 341 (Ctrl) | 多选开关 |
| `key_box_select` | 90 (Z) | 框选 |
| `key_collision_toggle` | 86 (V) | 无碰撞标记 |
| `key_center_pull` | 67 (C) | 整组归中 |
| `rotate_sensitivity` | 0.35 | TAB 旋转灵敏度 |
| `scroll_sensitivity` | 0.6 | 滚轮灵敏度 |
| `center_pull_speed` | 0.06 | C 归中速度 |

> TAB 复用航空学“Physics Staff Rotate Mode”键位，可在游戏按键设置中修改；其余键位改 toml 后重启生效。

## 命令

- `/sablesn forces [page]`、`/sablesn forces filtered [page]`
- `/sablesn confirm`、`/sablesn deny`

## 依赖

- Minecraft **1.21.1**，NeoForge **21.1.248**
- **Sable 2.0.4**（物理引擎）
- **Simulated / Aeronautics 1.3.1**（bundled，提供物理手杖与锁定）
- Create 6.0.10（随 Sable 生态，可选）

## 构建

```bash
gradlew.bat build        # 产物在 build/libs/sablestopnow-<version>.jar
gradlew.bat runClient    # 开发客户端
```

## 许可证

见 `gradle.properties` 的 `mod_license`（默认 All Rights Reserved）。仓库内 `TEMPLATE_LICENSE.txt` 为 MIT 模板，发布前请按需声明。

## 已知限制

- “无碰撞”为视觉+存档占位（Sable 无运行期按体 ghost API）。
- 整组控制基于逐成员马达驱动，超大/高速结构可能有轻微弹动（服务端刚度/阻尼在 `server/StaffEnhanceServer`）。
