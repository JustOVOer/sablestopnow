# 配合系统 交接文档（给下一个接手的人 / AI）

> 目标读者：**没有参与过本功能开发**、要在本仓库继续做「SolidWorks 式配合」的人。
> 读这份就够开工；想理解几何推导再看 [`mate-system-design.md`](./mate-system-design.md)。
>
> 最后更新：标准 8 种配合已实现并可在游戏内跑通；高级 6 种**未实现**。

---

## 0. 先用 5 分钟把项目跑起来

```powershell
$env:JAVA_HOME = "E:\workwork\.tools\jdk21\jdk-21.0.12.1+1"   # 本机 JDK 21
cd E:\workwork\sablestopnow
.\gradlew.bat build          # 首次会拉依赖，几分钟
.\gradlew.bat runClient      # 开发客户端
```

* 工程：NeoForge 21.1.248 / MC 1.21.1 / Java 21 / Gradle 9.2.1（wrapper 指向腾讯镜像）。
* 依赖 Sable（`dev.ryanhcode.sable`）与 Simulated 的物理 API；`lib/` 下的 jar **必须存在**（`bin/` 被 gitignore）。
* 本机访问外网要走 Watt Toolkit 代理（`127.0.0.1:26561`），Gradle 与 git 都已配好；
  JDK `cacerts` 里已导入 `CN=SteamTools Certificate`，否则 Maven 下载会 `PKIX path building failed`。

### ⚠ 两条会浪费你半天的纪律

1. **客户端开着的时候不要跑 `gradlew`。** `build/classes/java/main` 会被重写，而客户端是**懒加载**的
   （比如第一次进配合模式才加载 `MateTreeScreen`），于是你在游戏里点一下就 `NoSuchMethodError`。
   要一边测试一边验证代码，用下面这个**离线**脚本。
2. **`.ps1` 脚本只能写 ASCII。** PowerShell 5.1 会把无 BOM 的 `.ps1` 当 ANSI 读，中文全烂。
   另外**改 JSON 资源文件（`lang/*.json`）别用 PowerShell**：曾经因为编码问题产出过非法 JSON，
   而 Gradle 依然报 `BUILD SUCCESSFUL`，进游戏直接崩。用编辑器/文件工具写 UTF-8。

### 离线验证（客户端开着也能跑）

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-mate-check-nogradle.ps1
# FULL_TYPECHECK=OK                      ← 全部 73 个源文件完整编译
# === 329 checks, 0 failures === ALL PASS
# SCRIPT_EXIT=0
```

它做两件事，**只写临时目录，绝不碰 `build/classes`**：

1. **全量类型检查**：把 `src/main/java` 下所有源文件编译一遍，用来抓「穷尽 switch 少一个分支」
   「方法签名对不上」这类错误。**失败时脚本退出码是 2**，不要只看 `ALL PASS` —— 早期版本只编译
   `mate/` 目录里 4 个文件，于是它报全过、Gradle 却抓到了 `switch` 少 `MATE` 分支的真实错误。
2. **几何验算器**（`tools/MateFramesCheck.java`，329 项断言）：不需要启动游戏就能验参考解算、
   吸附位姿、锁定轴集合、冲突预算、NBT 往返。**改动 `mate/` 包后一定要跑。**

> 如果全量类型检查报 `FULL_TYPECHECK=FAILED`，先看 `%TEMP%\matecheck-full.log`。
> 新增依赖时要把对应的 jar 组加进脚本里的 `$g` 列表（MixinExtras 就是这么补上的）。

---

## 1. 当前状态（一句话）

**标准 8 种配合**（重合 / 平行 / 垂直 / 相切 / 同心 / 锁定 / 距离 / 角度）已实现，
可进入世界实际操作：Y 进配合模式 → 右键选两端（带预览）→ 自动成配 → 吸附对齐 → 落盘；
右侧常驻树状边栏可查看/改类型/改数值/对齐/删除。**高级 6 种（轮廓中心 / 对称 / 宽度 / 路径 /
线性耦合 / 限制）未实现。**

---

## 2. 玩法（你现在就能在游戏里试的）

| 操作 | 效果 |
|---|---|
| 手持物理手杖按 **Y** | 进入 / 退出配合模式 |
| **右键** | 选取当前准星指向的参考（角点 / 棱 / 面），带预览渲染 |
| **Shift + 右键** | 撤销上一端 |
| 按住 **Ctrl 滚轮** | 切换「结构级派生基准」（中心 / 主轴 / 基准面） |
| **Tab** | 释放 / 锁回鼠标（释放后才能点边栏） |
| 选满两端 | 自动成配；冲突则拒绝并回执提示 |
| 点边栏的配合行 | 展开 / 收起（**数值与更改按钮只在展开后出现**） |
| 点配合的**类型图标** | 循环切换配合类型 |
| 边栏「锁定 / 取消鼠标锁定」按钮 | 同 Tab |

---

## 3. 代码地图（改动最常落在这里）

```
mate/                       纯几何与数据模型（不依赖 MC 世界，可离线测）
  MateType.java             8 种类型的元数据：needsValue / needsDirection / supportsAlignment
  RefKind.java              参考几何种类：方块级 VERTEX|EDGE|FACE，结构级 BODY_CENTER|BODY_AXIS|BODY_PLANE
  MateRef.java              一端参考的描述（body + kind + block/face/feature/axis）+ 面内角点/棱工具
  Mate.java                 一条配合（id/type/refA/refB/value/flip/owner）
  MateFrames.java           ★几何核心：参考解算、吸附位姿、世界公共基座 W、锁定轴集合、冲突预算
server/
  MateRegistry.java         创建/删除/改数值/改对齐/关节生命周期/物理 tick/冲突检测
  MateData.java             每维度 SavedData（只存描述，关节句柄不可序列化）
  MateSelectionRegistry.java 待选两端 + 类型推断
  MateSelfTest.java         引擎内自检（见 §6）
network/
  MateNetworking.java       5 个载荷：mode / pick / command / sync / feedback
client/
  MateClientState.java      客户端状态（模式、配合表、待选、预览候选、自动聚焦、边栏实例）
  MateTargeting.java        射线求交 + 角点/棱/面分类 + 结构级参考循环 + 局部↔世界换算
  MateModeInput.java        Y / Tab / 鼠标按键 / 滚轮 的路由
  MatePreviewRenderer.java  世界内预览（点/棱/面/结构基准）
  MateSidebarRenderer.java  把边栏挂到 RenderGuiEvent.Post（**不是 Screen**）
  gui/MateTreeScreen.java   树状边栏本体：结构→配合两级、展开收起、按钮、滚动、定位高亮
  StaffControl.java         模式枚举，`Mode.MATE` 与 NORMAL/MULTI/DRAG **同级**
  StaffControlHud.java      左上角模式 HUD（配合模式让位给边栏）；右上角结构详情按边栏宽度左移
  StaffEnhanceClientHandler.java  模式状态入口：newControlMode()/functionsFor 索引等
tools/                      离线验算器 + 脚本（见 §0）
```

---

## 4. 核心设计（必须理解的 5 件事）

### 4.1 一条配合 = 在 6 自由度关节上锁若干轴 + 一次吸附

Sable 的 `GenericConstraintConfiguration(pos1, pos2, orient1, orient2, lockedAxes)` 提供 6 个轴：
`LINEAR_X/Y/Z` + `ANGULAR_X/Y/Z`（`ConstraintJointAxis.ALL` 是其数组）。

「锁某个轴」的语义是**把该轴上的偏差归零**。因此：

* 重合 / 平行 / 同心这类「零偏差」配合 → 直接 `lockedAxes`。
* 距离 / 相切这类**非零**偏差 → 必须 `setLimit(axis, v, v)` 把轴钉在 v 上。
  `setLimit` 只在 `GenericConstraintHandle` 上（**不在** `PhysicsConstraintHandle` 上），
  所以要 `handle instanceof GenericConstraintHandle` 再调。

### 4.2 世界公共基座 W

为了让「平行 / 垂直 / 同心」有可比较的方向，两侧的基座都相对同一个世界基座 W 构造：

```
M1 = R_A⁻¹ · W        M2 = R_B⁻¹ · W
```

这是设计文档 §3 的全部内容。**垂直**靠把 B 的方向放在 M2 的 X 轴上实现。
改动 `MateFrames` 前务必先跑 §0 的验算器。

### 4.3 先吸附，再在零误差处建关节

创建流程（`MateRegistry.create`）：

1. `MateFrames.solve(...)` 求出 B 的目标位姿（一次性吸附）。
2. `handle.teleport(pos, quat)` 把 B 搬过去 → 再 `resetVelocity`。
3. 写 `MateData`（落盘）+ 记入 `PENDING_SETTLE`。
4. **关节不在这一步建**，而是在**之后的物理 tick**（`physicsTick`）里按当前位姿建
   （`MateFrames.frames(...)`，不是 `solve`）。
5. 关节第一次建成时，如果它在 `PENDING_SETTLE` 里，再 `resetVelocity` 一次。

**为什么 3/4/5 这么绕**：吸附后的位姿要等物理系统读一次才落到 rapier 上；这段时间里结构还会被
重力/推力/玩家拖拽加速，等关节真建出来时已经带着不该有的速度差，约束要在一个步长内消掉它 →
表现就是**成配瞬间把结构甩飞**。所以速度要清两次，且**两侧都清**（早期只清 B 一侧）。

**重建不重新吸附**：关节失效后按当前位姿重建。所以结构被推歪之后重建，歪掉的状态就成了新基准
（约束自洽，但不再是原始装配姿态）。这是有意为之，见设计文档 §9。

### 4.4 参考几何是 plot 局部坐标

`level.clip` 在 Sable 里是**位姿感知**的（`LevelPoseProviderExtension.sable$pushPoseSupplier`），
返回的命中位置与朝向**已经是 plot 局部坐标**，正好就是 `MateRef` 要存的东西。
不要自己再乘一次结构位姿 —— 会得到「预览偏离实际面」这类错误。

世界坐标换算统一走 `MateTargeting.worldPoint / worldDirection / poseOf`，
`Pose3dc.transformPosition(p) = position + orientation · (scale · (p − rotationPoint))`（含缩放）。

`PhysicsConstraintConfiguration.validateAnchors` 对落在 plot 外的锚点会**直接抛**
`IllegalArgumentException`。方块级参考取的是方块角点，天然在包围盒边界上，
所以 `MateRegistry.clampToPlot` 留了 `1e-3` 余量把坐标收进去。

### 4.5 配合模式是「一等模式」，不是界面

配合模式与 `NORMAL / MULTI / DRAG` **同级**，沿用同一套接口：

* `StaffEnhanceClientHandler.newControlMode()` 在配合模式激活时返回 `StaffControl.Mode.MATE`；
* `StaffControl.functionsFor(mode)` 是穷尽 switch，**加模式必须同步加分支**（漏了编译就挂，
  但早期离线脚本抓不到，见 §0）；
* 滚轮功能下标各模式独立记忆（`newIndexMate`）；
* 鼠标/滚轮仍旧走原有的 `handleMouse / handleScroll` 入口。

**边栏绝对不能是 `Screen`。** 这是踩过的坑，见 §5.1。

---

## 5. 踩过的坑（照着做，别重犯）

### 5.1 边栏用 Screen → 按 Y 瞬间闪退

现象：按 Y 边栏出现后 18ms 内消失。日志证明 `Screen.removed()` 触发了两次，
**没有** `onClose()`、**没有**异常、**没有** `keyPressed` —— 说明是**别的东西调了
`setScreen(其它界面)`** 把它从屏幕栈顶了下去。

结论：**任何要常驻的界面都不能占屏幕栈**。现在边栏只是一个字段
（`MateClientState.sidebar()`），由 `MateSidebarRenderer` 挂在 `RenderGuiEvent.Post` 上绘制，
**从不调用 `setScreen`**。`MateTreeScreen` 仍 `extends Screen` 只是为了白拿
`font / width / height`，用 `prepareForOverlay(mc, w, h)` 补齐绘制前置条件。

### 5.2 Y 键会连续触发

原版对 `GLFW_REPEAT` 会调 `KeyMapping.click()`，长按 Y 会疯狂切换。
用 `StaffEnhanceClientHandler.justPressed(KeyMapping)` 做上升沿检测（已改 public）。

### 5.3 边栏内容互相叠字

一条 22px 的行里塞了图标 + 名字 + 「→ 对面结构名」+ 数值 + 减/加/对齐/删除，
面板宽度又只有屏宽的 34%，窄屏必然叠。现在分两级：
**收起态**只有图标 + 名字 + 展开箭头；**展开态**下面多一条 20px 明细带，
左边数值、右边按钮，两块区域互不相交。点整行切换展开。
`layoutButtons` 与 `rowHeight` 都跟着展开状态走 —— 收起时**不留看不见的点击区**
（否则点空行会误触按钮）。新建的配合由 `expandAround` 自动展开。

### 5.4 渲染顺序：预览被结构描边盖住

预览用的是 `NO_DEPTH` 材质，但「不测深度」只保证自己不被别人挡，**挡不住后画的东西盖住自己**。
Sable 的结构描边在实体之后才画，于是选棱时描边压掉预览。
现在挂在 `RenderLevelStageEvent.Stage.AFTER_LEVEL`（整帧最后一站）。

### 5.5 右上角结构详情盖住右侧边栏

结构详情面板默认贴右边缘。现在 `StaffControlHud.renderBodyInfo` 会按
`MateClientState.sidebarWidth()` 整体左移。**新增任何贴边 HUD 都要做同样的让位。**

### 5.6 其它

* `RefKind.VERTEX.hasDirection()` 曾经是 `true`，但 `resolveLocal` 返回的是无方向的点 →
  错误的 `supports()` 与错误的过约束自由度记账。现在验算器里有一条元数据一致性断言
  专门钉死「`hasDirection()` == `resolveLocal(...).direction() != null`」。
* `MateType.supportsAlignment()` 曾经两边都不自洽（COINCIDENT 用了 flip 却声明 false；
  PERPENDICULAR 声明 true 却忽略 flip）。
* 退出配合模式会发 `MateModePayload(false)`，早期 `MateNetworking` 在「没拿手杖」时
  直接 `context.disconnect` → 一退出就被踢。**已移除该断线逻辑。**
* 运行期日志级别是 **INFO**：`LOGGER.debug` 看不见。排查时用 `LOGGER.info`，
  或者把三条被调高的 `catch (Throwable)`（`StaffEnhanceClientHandler`、`MouseHandlerStaffEnhanceMixin`）
  当作入口 —— 它们现在是 ERROR 级。
* Modrinth 上的 `veil-neoforge-1.21.1-4.3.2.jar` 其实是 **Fabric** 构建，
  直接用会有约 100 个编译错误；NeoForge 版要从 Sable 的 `META-INF/jarjar/` 里取。

---

## 6. 引擎内自检（`MateSelfTest`）

`server/MateSelfTest.java` 是**在真实引擎里**跑的验算：触发方式是放一个
`run/mate-selftest.flag`，它会强制加载区块、给两侧施加反向冲量、并断言
**物理真的跑过**，结果写到 `run/mate-selftest-result.txt`。

> 早期版本报 PASS 是**假阳性**：`live joints: 0`、物理根本没步进，
> 而自由落体恰好保持相对位姿，于是「看起来没散」。现在有「物理确实运行了」的断言。

**当前待验证项**：确认 `setLimit(axis, v, v)` 真的把偏移钉在 v 上
（距离/相切配合依赖这个语义）。跑这个自检时**必须先关掉开发客户端**。

---

## 7. 下一步该做什么

1. **高级 6 种配合**（轮廓中心 / 对称 / 宽度 / 路径 / 线性耦合 / 限制）——
   在标准 8 种验证通过之后再做。`MateType` 加枚举值 + `MateFrames.lockedAxes` 加分支 +
   `MateIcons` 加图标 + 两个 `lang` 文件加键，注意 §4.5 的穷尽 switch。
2. **补 `setLimit` 语义的引擎内验证**（§6）。
3. **`docs/mate-system-design.md` §9「已知限制」**随实现推进同步更新。
4. **README / CHANGELOG / DESCRIPTION** 目前**还没写进配合系统**，需要补。

### 待确认的问题

* 「配合后的移动后要自动锁定两个物理结构」的准确口径：是指**建立约束关节**（现在就是这样），
  还是还要把两个结构写进 Simulated 现有的 **lock 状态**（手杖锁定功能那套）？
* 距离/角度配合的 GUI 目前只有 ±0.5 / ±15° 步进按钮，是否需要直接输入数值的输入框。

---

## 8. 变更记录（本轮修的 4 个 bug）

| 现象 | 根因 | 修法 |
|---|---|---|
| 选棱时预览被结构描边挡住 | 预览挂 `AFTER_ENTITIES`，描边在它之后画 | 改挂 `AFTER_LEVEL` |
| 结构详情盖住右侧边栏 | 详情面板贴右边缘，不知道边栏存在 | 按 `sidebarWidth()` 整体左移 |
| 面预览有时偏离所选面 | 见 §4.4：坐标空间混用 | 统一走 `MateTargeting` 的世界换算 |
| 点—面配合把结构甩飞 | 只清 B 一侧速度、且只在吸附时清；关节晚一步建 | 两侧都清，并在关节首次落地时再清一次（`PENDING_SETTLE`） |
