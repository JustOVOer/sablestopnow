# 物理手杖增强（Staff Enhance）—— 设计决策记录

> 状态：设计中（调研依赖 API 中）。本文记录已确认的需求与交互决策，随实现推进更新。
> 目标模组：SableStopNow（NeoForge 1.21.1 / NeoForge 21.1.248，Sable 2.0.4，Simulated/Aeronautics 1.3.1）。
> 实现原则：所有 Mixin / API 用法以 `sable-main` 与 `Simulated-Project-main` 实际源码 + `run/mods` 内 jar 的 javap 签名核验为准，不猜测。

## 1. 总开关（config）
新增 COMMON 配置开关 `enable_staff_enhance`（默认 false）。为 false 时本增强功能全部不生效（不拦截按键、不渲染、不改变手杖行为）。
建议在独立配置段 `staff_enhance` 下，避免与既有 `force_limiter` 混在一起。

## 2. 需求清单（已与用户确认）
多选/选择类功能**全部仅在手持物理手杖（PhysicsStaffItem，主手或副手）时可用**；均带文字提示（聊天/actionbar，走 translatable + en_us/zh_cn）。

| # | 功能 | 触发 | 说明（确认后细节） |
|---|---|---|---|
| 1 | 进入/退出多选模式 | 持杖单击 Ctrl（默认键，后续可改） | 进入有提示；进入后原手杖右键拖拽/左键锁定等全部被接管禁用 |
| 2 | 选中/取消选中物理体 | 多选模式下右键 | 指向物理体加描边（沿用既有 outline 方式）+ 世界空间选中图标；仅加入列表不移动 |
| 3 | 穿透层数 | 多选模式按住 Alt + 滚轮 | 数值提示；决定“视线穿透 N 个物理体后选中的第 N+1 个” |
| 4 | 范围选择 | 多选模式单击 Z 第一次/第二次 | 两点为对角的轴对齐立方体（格点角点），实时描边框；结束后盒内“任意方块在盒内”的物理体全部加入选中 |
| 5 | 整组移动/旋转 | 退出多选后右键组内任一物理体 | 沿用手杖拖拽手感：整组保持相对位姿、以整体质心为锚点/旋转中心移动与旋转（TAB+鼠标旋转、滚轮距离沿用）；左键锁定整组？——待细化 |
| 6 | 碰撞切换 | 非多选模式单击 V | 切视线所指物理体碰撞开关；服务端生效（SavedData 持久化，仿 PhysicsStaffServerHandler），无碰撞物体显示提示图标 |
| 7 | 图标 | 选中 / 无碰撞 | 世界空间 billboard 图标，参考航空学锁定图标实现 |

## 3. 输入层策略（待 API 报告确认后定稿）
候选 A：向 `SimClickInteractions.CLICK_INTERACTION_ENTRIES`（public static Set）注册我们自己的 `InteractCallback`。
问题：Set 迭代顺序不定，无法保证先于 `PhysicsStaffMouseHandler`（持杖时它会吞右键/左键）。
候选 B（倾向）：本项目自己的客户端 Mixin
- `MouseHandler` 注入：右键/左键/滚轮（持杖且功能生效时在 HEAD 消费并 cancel，阻止 vanilla + Simulated 的后续处理）
- `KeyboardHandler` 注入：Z / V / Ctrl 边沿
- `LocalPlayer.turn` 位置（仿 Simulated MouseHandlerMixin 的 local 捕获）拿鼠标移动增量用于整组旋转
候选 C：NeoForge `InputEvent.*`——与 Simulated 拦截顺序不可控，仅用于旁路提示。
> 结论倾向 B（完全自主可控、可 cancel、顺序确定）；进入多选后通过 cancel 天然屏蔽原手杖交互（需求 #1 的“接管禁用”）。

## 4. 拟新增代码模块（按包规划）
```
com.ovo.sablestopnow.staffenhance/           # 服务端/共享
├─ StaffEnhanceConfig（并入现有 SablestopNowConfig？—— 现有配置类集中管理，先并入）
├─ StaffEnhanceServerState extends SavedData # 碰撞开关等 per-level 服务端状态（+每维度）
├─ StaffGroupManager (server)                # 整组拖拽会话（约束驱动多体），挂在物理 tick
com.ovo.sablestopnow.staffenhance/client/
├─ StaffEnhanceClientHandler                 # 模式状态机 + 按键/滚动消费 + tick
├─ StaffEnhanceRenderer                      # 选中描边/选区框/图标/无碰撞图标渲染（世界空间）
com.ovo.sablestopnow.staffenhance/mixin/     # 输入层 mixin + 必要服务端/客户端目标 mixin
```
网络：自建 NeoForge 自定义 payload（C2S/S2C）或沿用 VeilPacketManager——取决于依赖方是否暴露可复用的注册点（报告中确认）。

## 5. 依赖 API 待确认清单（两路调研报告中回答）
1. PhysicsPipeline.addConstraint 签名/parent 语义/马达目标坐标语义 → 决定整组驱动方案
2. Sable 是否支持运行时单物理体碰撞开关（否则 V 需替代方案）
3. 视线穿透多物理体：现有 API vs 自采样
4. 选中/无碰撞图标：可复用 SimRenderTypes.lock() 及其贴图？还是自建 RenderType + 自备 16x16 贴图
5. 包注册模式：Veil 还是 NeoForge payload
6. 事件接线模式与 KeyboardHandler/MouseHandler mixin 的 client 配置放置（我们的 mixins.json 应加 client 分类）

## 6. 实施阶段（逐步、每阶段可编译可测）
- 阶段 A：config 总开关 + 客户端状态机骨架 + 输入拦截 Mixin（ctrl 进入/退出、提示、禁用手杖原交互）
- 阶段 B：右键点选 + 描边/图标 + Alt+滚轮穿透 + Z 范围选择 + 提示（纯客户端展示，服务端状态暂空）
- 阶段 C：V 碰撞切换（服务端 SavedData + 物理生效）
- 阶段 D：整组刚性移动/旋转（客户端几何 + 自建包 + 服务端组会话）
每阶段跑 `gradlew.bat build`，允许 `gradlew.bat runClient` 起游戏人工验证。

---

## 7. 实施状态（v0.1，2026-02 一轮完成）

**已完成且编译通过、客户端启动验证无 Mixin 注入失败：**

| 功能 | 状态 | 落点 |
|---|---|---|
| config 总开关 `sablestopnow.force_limiter... → staff_enhance.enable_staff_enhance`（默认 false） | ✅ | `SablestopNowConfig`（新 push("staff_enhance") 段） |
| Ctrl 进入/退出多选 + 文字提示 + 多选期原手杖交互接管禁用 | ✅ | `client/StaffEnhanceClientHandler` |
| 右键点选/取消（穿透层采样）+ 表面描边 + 选中图标 | ✅ | handler + `client/StaffEnhanceRenderer`（billboard 圆环线条字形，无贴图资源） |
| Alt+滚轮穿透层数（0..16，提示） | ✅ | handler.handleScroll |
| Z 两次标记→AABB 框选（实时预览、任意方块在盒内的物理体加入，上限 100 万格） | ✅ | handler.onBoxSelectKey + Renderer 预览 |
| 退出多选=整组待命；右键组内成员开始整组拖拽 | ✅（v1 用“焊接+领队体沿用航空学单体重物拖拽”） | `server/StaffEnhanceServer`（FixedConstraint 焊接）+ 客户端沿用 `PhysicsStaffDragPacket` 每 tick 驱动领队 |
| TAB+鼠标旋转整组、滚轮调距（航空学手感） | ✅ | handler.handleLookMove（LocalPlayer.turn HEAD 拦截）+ SimKeys.ROTATE_MODE |
| V 无碰撞切换 | ✅占位 | 服务端 `StaffCollisionData`(SavedData)+S2C 同步+橙色方块环图标+提示；Sable 无运行期 ghost API，**真实穿模未实现**（见 §5/§7 说明） |
| 自建 Veil 通道（3 个 C2S+1 个 S2C） | ✅ | `network/StaffEnhanceNetworking`（@Mod 构造 init） |

**新文件**：`client/StaffEnhanceClientHandler`、`client/StaffEnhanceRenderer`、`client/StaffEnhanceClientEvents`、`mixin/input/{KeyboardHandler,MouseHandler,LocalPlayer}StaffEnhanceMixin`（client mixins）、`network/StaffEnhanceNetworking`、`server/{StaffEnhanceServer,StaffCollisionData,StaffEnhanceServerEvents}`；语言键追加在 en_us/zh_cn。

**关键设计决策与偏差（务必进游戏实测后迭代）：**
1. 整组拖拽 v1 = **领队体锚点旋转/拖拽 + FixedConstraint 焊接**（Sable 无“多体合并刚体/质心枢轴”API）。因此旋转枢轴≈领队抓取点而不是严格“整体质心”；要严格绕组质心需改“每成员马达驱动+每物理子步 teleport+速度写回”方案（docs 参考 A 调研），留待 v1 实测后按需演进。
2. V：占位（视觉图标+SavedData 记录+同步），不真实穿模——Sable 原生/rust 无按体 sensor/ghost 开关；等 Sable 支持或后续打 rapier 层补丁再接真实效果。
3. 键位目前为代码常量（`StaffEnhanceClientHandler.KEY_*`：Ctrl/Z/V/左Alt），未做成 Controls 可改 KeyMapping——后续把 KEY_* 迁到配置或 KeyMapping 即可。
4. 图标为线条 billboard 字形（选中=青色圆环+十字、无碰撞=橙色方块环+斜杠），零贴图资产；要更精致可照抄航空学 LOCK 的 RenderType+16x16 png 方案替换。
5. 输入拦截走“自身 vanilla Mixin 于 HEAD 消费”，在多选/整组拖拽态吞掉原手杖交互；未启用/非持杖时完全放行原行为。

**需要人工进游戏验证/调参项：** 焊接与 FreeConstraint 马达并存下的手感与抖动（Sable Fixed 是带软度的 joint）；旋转灵敏度 ROTATE_SENSITIVITY=0.35、滚轮 SCROLL_SENSITIVITY=0.6（均 `StaffEnhanceClientHandler` 常量）；框选体积上限；多人在线时 S2C 同步图标一致性。
