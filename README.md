
# SableStopNow

一个为 Sable 物理引擎提供力限制功能的 NeoForge 模组。

## 简介

**AN AI GENERATED MOD**

SableStopNow 允许你对 Sable 物理引擎中施加的力进行监控和限制。当某个力超过设定的阈值时，你可以选择过滤该力、自动暂停物理模拟，并记录所有力的历史以供查询。

本模组专为 1.21.1 NeoForge 环境设计，与 Sable 物理引擎深度集成。

## 功能特性

### 力过滤与监控
- 按阈值过滤超限力
- 支持力组黑名单（如允许重力通过而不受限制）
- 实时记录所有力（含过滤状态、幅值、作用位置、目标物理体）
- 查询时按力组和物理体 ID 自动去重，保留最大幅值记录

### 自动暂停
- 当超限力被过滤时，自动暂停 Sable 物理模拟
- 向所有在线玩家发送聊天提醒，并附带可点击的命令建议
- 自动重置配置开关，避免反复触发

### 物理体管理
- 新物理体创建时自动添加固定约束（锁定）
- 与航空学模组的物理手杖兼容，锁定后可正常识别和解锁

### 方块放置优化
- 可选关闭物理体碰撞箱内的方块放置检测
- 允许在已有物理体的空间内自由放置方块

### 分裂确认
- 物理体分裂前要求玩家输入确认命令
- 支持 `/sablesn confirm` 同意分裂或 `/sablesn deny` 取消分裂
- 防止意外分裂造成的物理体丢失

### 多语言支持
- 内置中文（简体）和英文语言文件
- 命令输出、聊天提示、配置界面均支持多语言

## 配置说明

配置文件位于 `.minecraft/config/sablestopnow-common.toml`

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `filter_excessive_force` | boolean | false | 启用超限力过滤 |
| `threshold` | double | 1000.0 | 力阈值（Sable 单位） |
| `excluded_groups` | string list | [] | 排除的力组 ID 列表（如 `["sable:gravity"]`） |
| `max_records` | int | 1000 | 最大记录条数 |
| `auto_pause_on_filter` | boolean | false | 过滤时自动暂停物理 |
| `lock_new_sub_levels` | boolean | false | 锁定新创建的物理体 |
| `disable_placement_collision_check` | boolean | false | 禁用方块放置碰撞检测 |
| `require_confirmation_before_split` | boolean | false | 分裂前需要玩家确认 |

## 命令

所有命令均以 `/sablesn` 为前缀。

| 命令 | 说明 |
|------|------|
| `/sablesn forces [page]` | 显示所有力的记录（分页） |
| `/sablesn forces filtered [page]` | 显示已被过滤的力记录（分页） |
| `/sablesn confirm` | 确认当前挂起的分裂请求 |
| `/sablesn deny` | 取消当前挂起的分裂请求 |

### 交互特性

- 点击力记录中的物理体 ID 会自动填充 `/tp @p x y z` 命令，方便传送到力的作用位置
- 分页导航支持点击跳转
- 过滤状态以颜色区分（红色已过滤，绿色正常）

## 依赖

- Minecraft 1.21.1
- NeoForge 21.1.248
- Sable 2.0.4
- Simulated（航空学模组，用于物理体锁定功能）
- Create（可选，但通常与 Sable 生态一同使用）


## 使用建议

1. 所有功能默认关闭，请按需逐项启用
2. 建议先启用 `filter_excessive_force` 和 `auto_pause_on_filter` 测试力过滤效果
3. 使用 `/sablesn forces` 查看力记录，确认阈值是否合理
4. 物理体锁定功能需配合航空学模组使用，否则无法解锁

## 已知问题

- 分裂确认功能与 Sable 的热力图系统深度绑定，若 Sable 更新可能导致行为变化
- 物理体锁定依赖 Simulated 模组，若该模组未安装则锁定功能无效

## 许可证

MIT