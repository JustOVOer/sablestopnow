# 物理手杖增强（Staff Enhance）—— 设计决策记录

> 状态：**v1.1.0（2026-09）**。多人选择 / 所有权 / 区域选择 / 快照 / 缩放 / 幽灵化 / 设置界面均已实机验证；§8.14 的彩蛋**未完成**（仅开发文档记录，不对外宣传）。本文保留需求与交互决策的来龙去脉，并在 §7/§8 记录最终实现与关键事实。
> 目标模组：Aeronautics: Tweaks & Toolkit（mod id `sablestopnow`，NeoForge 1.21.1 / 21.1.248，Sable 2.0.4，Simulated/Aeronautics 1.3.1）。
> 实现原则：所有 Mixin / API 用法以 `depends/sable-main` 与 `depends/Simulated-Project-main` 实际源码（均在仓库根 `depends/` 下，已 gitignore）+ `run/mods` 内 jar 的 javap 签名核验为准，**不猜测**。

---

## 1. 总开关（config）

COMMON 配置 `[staff_enhance]` 段 → `enable_staff_enhance`（默认 **false**）。为 false 时本增强功能全部不生效（不拦截按键、不渲染、不改变手杖行为）。独立成段，避免与既有 `[force_limiter]` 混在一起。

## 2. 需求清单（最终确认行为）

多选/选择类功能**全部仅在手持物理手杖（PhysicsStaffItem，主手或副手）且无 GUI 时可用**；均带文字提示（actionbar/chat，走 translatable + en_us/zh_cn）。

| # | 功能 | 触发 | 最终行为 |
|---|---|---|---|
| 1 | 进入/退出多选 | 持杖单击 Ctrl | 进入有提示；进入后原手杖右键拖拽/左键锁定被接管禁用 |
| 2 | 选中/取消 | 多选模式右键 / **Shift+右键** | 右键加入队列、Shift+右键移出；队列成员显示青色轮廓+圆环图标 |
| 3 | 穿透层数 | 多选模式 Alt+滚轮 | 0–16，决定“穿透 N 个物理体后的第 N+1 个”被点选 |
| 4 | 范围选择 | 多选模式两次按 Z | **两次都是“玩家自己所在方块”**（可在两次之间走动）；AABB 内物理体全部入队（上限 100 万格） |
| 5 | 整组移动/旋转 | 待命态右键组内成员 | 先解除全部锁定 → 进入整组控制：质心锚定（相对视线方向）、滚轮沿眼心线调距、长按 C 缓拉视线中央、TAB+鼠标绕质心旋转、右键结束（保留队列） |
| 6 | 碰撞切换（V） | **非多选态**按 V | 对**整个选中队列**切换无碰撞（与左键锁定同语义）；`ghost_real` 决定是否真实关闭选中体间碰撞 |
| 7 | 锁定（左键） | 待命态/整组控制中 | 智能切换：全部已锁→全解锁；否则（含部分）→先全锁 |
| 8 | 清除队列 | 待命态 Shift+右键 | 清空队列（**弃用**了原本的右键清除） |
| 9 | 图标 | 选中 / 无碰撞 / 悬停 | 世界空间 billboard 线条字形：选中=青色圆环，无碰撞=橙色方块环，悬停=白色轮廓（无图标） |
| 10 | 顶部 HUD | 持杖且功能开启 | 左上角常驻：模式 / 穿透层数 / 选中数 / 框选角点 |

## 3. 输入层策略（最终采用 候选 B）

自建客户端 Mixin（完全自主可控、可 cancel、顺序确定）：

- `mixin/input/MouseHandlerStaffEnhanceMixin`：`MouseHandler.onPress` / `onScroll` 的 HEAD 消费并 cancel；`turnPlayer` 用 MixinExtras `@Local(ordinal = 4/5/0)` 捕获**原始鼠标增量**（与航空学 Mixin 同法，取 raw magnitude），用于 TAB 旋转。
- `mixin/input/KeyboardHandlerStaffEnhanceMixin`：`KeyboardHandler.keyPress` HEAD 边沿（Ctrl / Z / V；C 为 raw key 查询）。
- 放行规则：未启用、非持杖、有 GUI 时**完全放行**原行为。
- 早期评估过的候选 A（`SimClickInteractions.CLICK_INTERACTION_ENTRIES` 注册 InteractCallback）因 Set 迭代顺序不定、无法保证先于 `PhysicsStaffMouseHandler` 而**弃用**；候选 C（`InputEvent.*`）顺序不可控，仅备用。

## 4. 代码模块（最终落点）

```
com.ovo.sablestopnow.client/
├─ StaffEnhanceClientHandler   # 模式状态机 + 拾取 + 组会话 + 输入入口（~900 行）
├─ StaffEnhanceRenderer        # 选中/悬停轮廓、图标、框选预览
├─ StaffEnhanceHud             # 左上角 HUD（RenderGuiEvent.Post）
├─ StaffEnhanceClientEvents    # 客户端事件接线
├─ ModRenderTypes / SubLevelOutlineRenderer  # 既有描边渲染（增强复用其 drawSubLevelContourOutline）
com.ovo.sablestopnow.mixin.input/  # KeyboardHandler / MouseHandler（client mixins）
com.ovo.sablestopnow.network/
└─ StaffEnhanceNetworking      # Veil 通道：C2S StartGroup/StopGroup/MoveGroup/SetLocks/
                               #   ToggleNoCollision(遗留)/SetNoCollision；S2C SyncLocks/SyncNoCollision
com.ovo.sablestopnow.server/
├─ StaffEnhanceServer          # 组马达驱动、ghost 关节、步进、超速锁定、锁/碰撞数据
├─ StaffCollisionData          # 无碰撞标记 SavedData（仿航空学 PhysicsStaffServerHandler）
└─ StaffEnhanceServerEvents    # ServerTickEvent 接线
```

网络：**沿用 Veil** `VeilPacketManager.create("sablestopnow", "0.1")`，在 `@Mod` 构造器 `StaffEnhanceNetworking.init()` 注册。手写 ByteBuf 编解码（ByteBuf 无 varint/RL 便利方法 → `writeInt` / 长度前缀 UTF-8 字节数组）。

## 5. 依赖 API 待确认清单 —— 调研结论（已全部核验）

1. **`PhysicsPipeline.addConstraint(bodyA, bodyB, config)`**；`FixedConstraintConfiguration(pos1,pos2,orientation)` = 焊接；`FreeConstraintConfiguration(ZERO, plotAnchor, orientation)` = 航空学拖拽所用；`GenericConstraintConfiguration(pos1,pos2,ori1,ori2,Set.of())` + `setContactsEnabled(false)` = **瞬态、无位姿约束的“关接触”关节**（ghost_real 的实验基础）。
2. **Sable 无运行期按体 ghost/穿模 API**（原生/rust 层均无 per-body sensor 开关）→ V 只能做视觉+存档占位，或走 §5.1 的邻近两两关节近似。
3. **视线穿透多体**：无现成 API → 自实现：客户端多次 `level.clip` + 位姿栈（见 §8.1）。
4. **图标**：复用航空学风格但**自建线条字形**（零贴图资产），未采用其 LOCK RenderType+16x16 png。
5. **包注册**：Veil 通道（航空学同款），非 NeoForge payload。
6. **输入**：自建 client mixin，`sablestopnow.mixins.json` 增 `client` 数组（`input.KeyboardHandlerStaffEnhanceMixin`、`input.MouseHandlerStaffEnhanceMixin`）。

### 5.1 `ghost_real` 的取舍（已与用户确认的实验方向）

`depends/sable-schematic-api-master` 里的 `setIgnoreOnPlace` 是**方块写入批处理**，不是运行期物理 ghost，不可用。
采用方案：对**邻近**（`GHOST_SEARCH_SQ = 160²`）的选中体**两两**创建 `GenericConstraintConfiguration`（锁定轴集合为空）+ `setContactsEnabled(false)` 的瞬态关节 → 选中体之间不碰撞，**地形/玩家不受影响**。关节随物理子步刷新、退出时 `clearGhosts()`。默认关闭，属实验特性。

## 6. 实施阶段（历史）

- 阶段 A：config 总开关 + 客户端状态机骨架 + 输入拦截 Mixin ✅
- 阶段 B：右键点选 + 描边/图标 + Alt+滚轮穿透 + Z 范围选择 + 提示 ✅
- 阶段 C：V 碰撞标记（服务端 SavedData + S2C 同步）✅
- 阶段 D：整组刚性移动/旋转（客户端几何 + 自建包 + 服务端组会话）✅

---

## 7. 实施状态（v1.0.4 最终）

| 功能 | 状态 | 落点 |
|---|---|---|
| config 总开关 + `[staff_enhance]` 全部键（含 `ghost_real`） | ✅ | `SablestopNowConfig`（含 try/catch 安全访问器，防配置未加载时输入事件崩溃） |
| Ctrl 进入/退出多选 + 提示 + 多选期原手杖交互接管 | ✅ | `client/StaffEnhanceClientHandler` |
| 右键加入 / Shift+右键移出 / 悬停白色轮廓（无图标） | ✅ | handler + `StaffEnhanceRenderer` |
| Alt+滚轮穿透层数（0–16） | ✅ | handler.handleScroll |
| Z 两次“玩家所在方块”→ AABB 框选（实时预览角点 B） | ✅ | handler.onBoxSelectKey（`volume > 1_000_000` 拒绝） |
| 待命态右键进入整组控制（先全解锁） | ✅ | handler.startGroupDrag + `StartGroupPayload` |
| **整组 = 逐成员 FreeConstraint 马达**（保住相对位姿，不再焊接） | ✅ | `server/StaffEnhanceServer`（`SableEventPlatform.onPhysicsTick` → 每物理子步驱动成员） |
| 质心按视线坐标系锚定（offsetF/R/U）+ 滚轮调距 + 长按 C 归中 | ✅ | handler（`MoveGroupPayload` 每 tick）+ server（马达目标） |
| TAB+鼠标绕质心旋转（raw 增量） | ✅ | `MouseHandlerStaffEnhanceMixin.turnPlayer` + `rotate_sensitivity` |
| 右键结束整组（保留队列） | ✅ | `StopGroupPayload` + `stopGroupDrag` |
| 左键智能锁定 / V 队列无碰撞（乐观提示 + S2C 纠正） | ✅ | `SetLocksPayload` / `SetNoCollisionPayload` + `SyncLocks` / `SyncNoCollision` |
| 顶部 HUD（模式/穿透/选中数/框选角点） | ✅ | `client/StaffEnhanceHud` |
| `/sablesn tick <steps>`（暂停中步进 N tick 后自动恢复暂停） | ✅ | `ForceCommand` + `StaffEnhanceServer.startStepping/tickStepping` |
| 超速自动锁定 + 可点击传送播报（60 tick 冷却、豁免被拖拽体） | ✅ | `StaffEnhanceServer.speedScan/currentlyDragged/findDraggingSessions` |
| 自建 Veil 通道（6 个 payload 类型） | ✅ | `network/StaffEnhanceNetworking` |

**验证方式**：`gradlew.bat build` + `gradlew.bat runClient` 人工实测（多选、框选、整组拖拽/旋转/归中、V、HUD、tick、超速锁定）。

## 8. 关键实现事实与陷阱（接手必读）

### 8.1 拾取必须用 Sable 的位姿感知射线 ⚠️
Sable 的物理体是**位姿渲染**（plot 占据的是组装时的基准网格坐标），世界坐标的 `Sable.HELPER.getContaining(level,pos)` / `getContainingClient` **只解析基准坐标** → 直接拿世界坐标采样会“选不中/选到空气”。
正解：客户端 `LevelPoseProviderExtension.sable$pushPoseSupplier(x -> subLevel.renderPose())` 后调 `level.clip(...)`（Sable 的 `BlockGetterMixin` 对它做了 `@Overwrite`），**命中坐标是基准坐标**，再用 `getContainingClient` 反查物理体。穿透层数 = 以命中点为起点多次 clip 并在其间把已命中物理体加入 ignore 集合。

### 8.2 整组拖拽历经两次重构
- v1：领队体拖拽 + `FixedConstraint` 焊接 → 成员**被吸到一起**（用户实测反馈“吸在一起”）。
- 最终：**每成员一个 FreeConstraint 马达**，每物理子步把成员驱动到“进入时记录的相对位姿 + 组变换”；进入瞬间客户端记录的旋转初值必须是**单位四元数**（服务端把客户端 rot 当作相对成员初始朝向的**增量**），否则会瞬间乱转。

### 8.3 速度锁的两个坑（v1.0.4 修复）
- **刷屏**：早期每次扫描都会广播。现为每物理体 60 tick 冷却（`SPEED_LOCK_RETRY`）+ **只在 `isLocked(sub)` 确认后**才播报。
- **误锁普通拖拽体**：反射取 `PhysicsStaffServerHandler` 的拖拽会话时，早期拿到的第一个 Map 常是 `locks`（导致把普通拖拽体当“未拖拽”锁定）。现改为：优先选**字段名含 "raging"** 的 Map，退化时再选“值对象含 `ServerSubLevel` 字段”的 Map。该反射**版本锁定于内置航空学 1.3.1**，失效时表现为误锁（不崩）。

### 8.4 配置读取时机
客户端输入事件可能早于配置加载 → 所有输入 mixin 读配置必须走 `SablestopNowConfig` 的安全访问器（内部 try/catch `IllegalStateException` 回退默认值），否则启动即崩（“Cannot get config value before config is loaded”）。

### 8.5 目录/包一致性
`ModRenderTypes` / `SubLevelOutlineRenderer` 早期“声明 `package …client` 却放在根目录”，现已**物理移入 `client/` 子目录**（`docs/功能梳理与扩展开发指南.md` §2 的旧警告已过时）。

### 8.6 键位：Minecraft KeyMapping（v1.1.0 起）
- 全部键位迁到 `client/StaffKeyMappings`（`@EventBusSubscriber(bus = Bus.MOD)` 里注册），旧的 `KeyboardHandlerStaffEnhanceMixin` 已删除；鼠标仍靠 `MouseHandlerStaffEnhanceMixin` 在 HEAD 吞掉（多选/区域选择必须真正 cancel 航空学的手杖交互）。
- ⚠ **原版在 GLFW REPEAT 时也会调 `KeyMapping.click()`**（1.21.1 `KeyboardHandler.keyPress` 字节码：`action != 0` 分支直接 `set(true)+click`）。因此切换类按键**不能**裸用 `consumeClick()` 的 while 循环，必须自己取上升沿：见 `StaffEnhanceClientHandler.justPressed()`（记录上一 tick `isDown()`，只在上升沿或同 tick 内按下并抬起时算一次）。
- ⚠ 多选键默认左 Ctrl 与原版「疾跑」同键，`KeyMapping.set` 会把同键的所有映射一起按下 → 每 tick 显式 `keySprint.setDown(false)` 压掉（`suppressVanillaKeyClash()`）。
- GUI 打开时原版不会更新 KeyMapping 状态，天然满足“有 GUI 时不响应”。

### 8.7 自建 RenderType 共用 shared buffer（渲染崩溃根因）
1.21.1 `MultiBufferSource.BufferSource`：**不在 `fixedBuffers` 里的自建 RenderType** 全部共用同一个 `sharedBuffer`，`getBuffer(另一个自建类型)` 会先把 `lastSharedType` 的 `BufferBuilder` `endBatch` 掉。
→ **同一段绘制里不能同时持有两种自建类型的 VertexConsumer**，否则先取的那个后续写顶点直接 `IllegalStateException: Not building!`。
现固定做法：**两段式绘制** —— 先画完所有盒式边框（`BOXES_NO_DEPTH`）并 `endBatch`，再取 `LINES_NO_DEPTH` 画图标/坐标轴/角点十字。`SubLevelOutlineRenderer`（盒式轮廓 + 坐标轴）与 `StaffEnhanceRenderer`（轮廓 + 图标）都已按此拆分。

### 8.8 世界坐标渲染必须平移 −相机位置
`StaffEnhanceRenderer` 的 Z 区域选择预览盒用世界坐标调用 `BoxOutlineRenderer.addWireBox`，顶点写的是绝对方块坐标，因此必须先 `poseStack.translate(-cameraPos)`；方块描边则是逐方块算“相机相对坐标”，两者不可混。

### 8.9 「仅描边边缘」的共面接缝判定
旧实现要求「这条棱两侧的两个邻居都存在且都暴露」才跳过 → 平坦表面最外圈方块的那条**内部接缝**因为一侧是空气而不再被跳过，表现成「边缘 + 向内一层的棱」同时描边。
正解：一条棱只可能跨过一条轴（垂直于面法线的两条轴里、中点带 ±half 偏移的那条），**只看跨过这条棱的那唯一的邻居**（同物理体 + 其对面对应面也暴露 → 跳过）；同一方块内 12 条棱再用位掩码去重（避免相邻暴露面重复画同一条棱）。

### 8.10 多人选择 / 所有权 / 快照（v1.1.0）
- **独占登记表** `server/StaffSelectionRegistry`：运行期内存（不落盘），`claim` 时同时校验「他人已选中」与「他人所有」；释放时机＝移出队列/清空/退出多选/掉线（退出多选只释放占用，**本地队列保留**供整组拖拽，因此 S2C 快照**不能**用来清空本地队列）。
- **颜色**：`StaffColors.PALETTE`（12 色），服务端按玩家分配索引并随选择快照下发；客户端只渲染同维度 + 64 格内的他人选择。
- **所有权** `server/StaffOwnershipData extends SavedData`：落盘；`/sablesn owner list|clear <玩家>|clear all` 为 OP 后门。
- **快照** `server/StaffSnapshotRegistry`：按玩家、运行期内存；内容来自 `SubLevelSerializer.toData(sub, List.of())`（方块 + 位姿 + 速度 + 名称）。
  回退 = `removeSubLevel(sub, REMOVED)`（不会把方块掉到地上）→ `SubLevelSerializer.fullyLoad(level, data)`（同 UUID 重建，plot 已被腾出）→ `pipeline.resetVelocity` + 按快照原值 `addLinearAndAngularVelocity`（绕开 `VELOCITY_RETAINED_ON_LOAD` 衰减）。
  ⚠ 回退前先 `StaffEnhanceServer.stopGroupDrag(level, player)`，否则马达会驱动到已被替换的物理体。

### 8.11 自定义设置界面（v1.1.0）
- `client/gui/`：`ModConfigScreen`（大类页，GUI1）、`ModConfigCategoryScreen`（详情页，GUI2）、`ConfigOption`（行模型，直接绑定 `ModConfigSpec` 值）、`ModConfigCategory`（大类→选项映射）、`Ease`（指数收敛缓动 + Create 风蓝色板）。
- 入口：`Ctrl+O`（`StaffKeyMappings.OPEN_CONFIG`，`KeyModifier.CONTROL` + O）与 NeoForge 模组列表「配置」（`client/ClientConfigScreens` 里注册 `IConfigScreenFactory`，**只在 `FMLEnvironment.dist.isClient()` 分支调用**，避免服务端加载客户端类）。
- 动画：指数收敛（`1 - e^(-speed·dt)`）天然“由快变慢”；离场先反向播完再 `setScreen`（用 `next` 字段延迟切换）。
- ⚠ `rowAt()` 返回可空 `Integer`，赋给 `int` 字段会因自动拆箱 NPE（已修）。
- ⚠ 界面改动直接 `SablestopNow.saveConfig()` 写回 `sablestopnow-common.toml`。
- Create 的蓝色按钮贴图来自 Catnip，而 Catnip 不是本项目的编译依赖（`lib/create-*.jar` 里不含 `net/createmod/catnip`），因此按钮是用色块 + 高光边**手绘**的同风格外观。

### 8.12 缩放（X）—— 四处必须自己补的洞（v1.1.0）
Sable 的 `Pose3d` 有 `scale` 字段，但**除了它自己的方块描边，整条链路都不认它**：
1. **同步**：`SableBufferUtils.write(ByteBuf, Pose3d)` 只写 position/orientation/rotationPoint，**不写 scale** → 客户端拿到的 `renderPose().scale()` 永远是 1。必须自建 `SyncScalesPayload`（`Set<ScaleEntry>`）并在客户端 `applySyncedScales()` 里覆盖，且**要放在「没持杖就 early-return」之前**，否则非持杖玩家看不到缩放。
2. **网络上的物理读回**：`SubLevelPhysicsSystem.readPose` 只读 position+orientation，**不读 scale** → 服务端每 tick 用物理结果覆盖位姿时会把 scale 抹掉；缩放数据因此独立存在 `server/StaffScaleData`（SavedData）里，并由 `reapply` 每 tick 写回。
3. **渲染**：Sable 两条区块渲染路径都只做 translate+rotate（`VanillaChunkedSubLevelRenderData.renderChunkedSubLevel`、`FancySubLevelRenderDispatcher.renderSectionLayer`），而它自己的方块描边 `LevelRendererMixin` **是**乘 scale 的 → 不补就会「碰撞盒/描边缩放、方块不缩放」。补法是改 `Matrix4f.mul` 的入参矩阵：`transform = T((1−s)·(pos−cam)) · R · S`。
   - 推导依据：原生顶点路径的 translation 列恒为 0，相机偏移被折进每 section 的 `CHUNK_OFFSET` uniform（`sectionPos − origin + R⁻¹·(renderPos − cam)`）；因此把「先缩放再平移」改写成上式的纯矩阵组合即可，且 `(1−s)` 那一项**不能**改 translation 列（那是恒 0 的）。
4. **超速自动锁定**：马达瞬移成员的线速度远超阈值 → 被自家 `speedScan` 锁定，表现为「松开滚轮就弹回/卡住」。修法：`StaffScaleData.activeOrScaledIds` 里的物理体在速度扫描中豁免，且缩放会话开始时把已锁定的成员临时解锁、结束时恢复；会话中若被锁定则直接解锁。

其它：`@ModifyArg` 的处理器参数类型必须写成目标方法**声明的**类型（`Matrix4fc`），写 `Object` 会 `InvalidInjectionException: Could not find arg matching type Ljava/lang/Object;`，表现却是「Network Protocol Error」/掉线，极易误判成网络问题。

### 8.13 幽灵化（拖拽体 / 缩放体不与玩家碰撞，v1.1.0）
- **唯一的漏斗**：实体↔物理体碰撞只有 `SubLevelEntityCollision.collide(...)`，由 Sable 的 `Entity.move` `@Redirect` 调一次；该类里 `getAllIntersecting(Level, BoundingBox3dc)` **只有一处调用**（`collide` 内，字节码偏移 306）。
- ⚠ **不能**全局过滤 `getAllIntersecting`、不能改 `getTrackingSubLevel`、更不能跳过 `ServerPlayer` 的「假地面」分支（会触发 moved-too-quickly 回弹 / “floating too long” 踢出）。
- ⚠ `ServerPlayer` 分支会提前返回一个假地面 → **玩家的物理体碰撞是在客户端算的** ⇒ Mixin 必须在 **common** 侧，且幽灵状态要**同步给客户端**（`SyncActiveGhostsPayload(subs, draggers, globalIds)`）。
- `PhysicsGhosts`（common）持有 `Map<UUID dragger, ? extends Collection<UUID>>` 与 `Set<UUID> globalGhosts`，`ignores(sub, entity)` 判断；Mixin 用 `@ModifyExpressionValue` 包住那次 `getAllIntersecting` 的返回值，**两趟**扫描且仅在真的剔除过幽灵时才新建集合。
- 残留：只关碰撞，搭车（tracking）仍在。

### 8.14 🚧 未完成的彩蛋（不在对外说明范围内）
> 该彩蛋**尚未完成**：只打通了「右键抓起 → BeginScale → 绝对放置」的链路，屏幕尺寸恒定、落点贴合与松手放下均未验收。
> 因此实现细节仅保留在此处，**不出现在 README / DESCRIPTION / CHANGELOG** 等对外文档中；游戏内开关提示也已标注「未完成·实验性」。

- 目标：抓取期间**屏幕上视觉大小不变**（强制透视）→ 看起来小就放得远，看起来大就放得近。
- 实现：右键抓取时以「当前视觉大小」反算基准距离 `superliminalBaseDistance`，之后每次移动把结构放到**视线命中的平面点**上（`superliminalTargetPoint` / `groupHalfExtent` 用于把结构“贴”在平面上而不是嵌进去），受 `superliminal_max_distance` 限制。
- 与普通整组拖拽共用 `sendGroupDragTick`，只是位置分支换成绝对放置（`SuperliminalPlacePayload`），手感是「直接粘在准星上」而非弹簧拖拽。
- **多选模式内外都能用**：非多选时右键会先 `pickAtDepth` 命中体 → `selected.add` → `startGroupDrag`，并**吞掉该次点击**，避免航空学同时启动它自己的拖拽。

## 9. 待办 / 可迭代点

- 大结构整组手感调参（线性 2650/125、角向 10000/850、`rotate/scroll_sensitivity`、`ghost_real` 的 160 格半径）。
- 键位从 config 键码迁移到 Minecraft `KeyMapping`（Controls 界面可改）。
- 用 `@Accessor/@Invoker` 或公开 API 取代对航空学内部拖拽会话的反射。
- ghost_real 若 Sable 后续提供官方 per-body 碰撞开关，改为官方 API。
