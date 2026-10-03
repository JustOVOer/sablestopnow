# GUI 库与模型模式（设计说明）

> 状态：**第一阶段已落地**（GUI 库内核 + 控件 + 模型模式相机/选择/界面骨架）。
> 后续所有界面都必须只通过这套库搭建，见 §1 的「唯一入口」约定。

## 1. GUI 库（`com.ovo.sablestopnow.client.gui.lib`）

### 1.1 为什么自研保留模式库

Minecraft 的界面是**立即模式**：每帧把控件重新画一遍，没有「控件对象」。
SolidWorks 式的界面需要的是**保留模式**：控件有身份、有状态、有动画值，能展开/收起/滑入/淡出。
所以库的核心是「控件树 + 每帧布局 + 动画属性」，绘制只是它最外面的一层。

### 1.2 结构

| 文件 | 职责 |
| --- | --- |
| `Ui` | 库的唯一入口：层的开关、绘制、鼠标/键盘派发、帧间隔、已占用矩形 |
| `UiLayer` | 一个覆盖层（不占 `Screen` 栈，不暂停游戏） |
| `UiScreen` | 需要独占屏幕语义时用（配置界面的模糊背景 + Esc 返回） |
| `UiRoot` | 层的控件树根：布局、逐帧推进、焦点/拖拽/弹出层/提示气泡 |
| `Widget` / `Container` | 控件基类 / 容器（子控件 + 布局器 + 裁剪 + 背景） |
| `Layouts` | 布局器：`absolute` / `vbox` / `hbox` / `dock` / `grid` / `stack` |
| `Anim` | 缓动值（指数收敛，天然「由快变慢」）+ 颜色/数值插值 |
| `UiTheme` | 颜色、行高、间距、**字体缩放（默认 0.75）** |
| `UiDraw` | 绘制原语：矩形、渐变、面板、裁剪、文本、图标、物品、三角、对勾 |
| `UiContext` | 一帧的上下文（主题、字体、鼠标、动画开关、屏幕尺寸） |
| `UiSettings` | 全局开关：`animations`（配置里的「界面动画」）、`fontScale`、`debugBounds` |
| `UiDock` | HUD 避让：按角/边找一个不被面板压住的位置 |
| `UiRenderHook` | `RenderGuiEvent.Post` → `Ui.render` |

控件：`Label` `Button` `Checkbox` `Slider` `TextField` `ComboBox`（带实时补全）`Dropdown`
`MenuList`（下拉/补全/右键菜单共用）`ScrollPane` `TreeView` `Tabs` `Panel` `SectionPane` `Separator`。

### 1.3 唯一入口约定（此后所有界面都照这个来）

```java
// 建一个常驻侧栏（不占屏幕栈、不暂停游戏）
UiLayer layer = Ui.open(UiLayer.of("my.panel").size(0, 0));
layer.root.layout = Layouts.dock(4, 4);

Panel panel = new Panel().title(Component.translatable("my.title"));
panel.dock = Layouts.Dock.RIGHT;
panel.prefW = 140;
panel.layout = Layouts.vbox(3, 3);

Button button = new Button(Component.literal("点我"), () -> doSomething());
button.prefW = Widget.FILL;
panel.add(button);

layer.root.add(panel);
Ui.close("my.panel");   // 关闭
```

* 尺寸：`prefW/prefH` 为 `Widget.AUTO`（自己量）、`Widget.FILL`（撑满）或固定像素。
* 事件：控件的 `mouseDown/mouseUp/mouseDrag/mouseScroll/keyPressed/charTyped` 返回 `true` 即吞掉。
* 弹出层：`ctx.root.openPopup(menu, owner)`；提示气泡：覆盖 `Widget.tooltip()`。
* 动画：每个控件自带 `Anim` 字段，在 `update()` 里 `anim.to(target).tick(ctx.animations, dt)`；
  全局「界面动画」关掉时 `tick` 会直接跳到目标值，不需要各界面自己写开关分支。
* **禁止**在界面代码里直接 `GuiGraphics.fill/drawString` —— 需要新画法就加到 `UiDraw` 或做一个新控件。
* 输入转发：覆盖层不在 `Screen` 栈上，键盘/鼠标由 `KeyboardHandler` / `MouseHandler` 的两个 mixin
  转发给 `Ui.handleKey/handleChar/handleMouse/handleScroll`。顺序是 **GUI 库 → 模型模式 → 旧手杖逻辑**。

### 1.4 新增界面的硬性约定（「此后亦然」）

以后每加一个界面/HUD，都按这份清单走：

1. **只能通过库**：控件从 `client/gui/lib` 拿；需要新画法就加 `UiDraw` 原语或新控件，
   **不允许**在界面代码里出现裸 `GuiGraphics.fill/drawString`（原生 `Screen` 也一样）。
2. **选对载体**：
   * 常驻/游戏内操作面板 → `UiLayer`（不占屏幕栈、不暂停游戏、别的模组 `setScreen` 顶不掉它）；
   * 需要独占屏幕语义（背景模糊、Esc 返回、暂停）→ `UiScreen`。
3. **纯显示的 HUD**：`consumeInput = false`、`consumeKeys = false`、`reserveSpace = false`——
   显示层绝不能吞掉输入（输入永远由 `StaffEnhanceClientHandler` / 模型模式负责）。
4. **不要互相遮挡**：自己登记进 `Ui.reservedRects()`（覆盖层默认会登记），
   或者用 `UiDock.place(...)` 找一个空位；不要靠「反正它在我下面」这种假设。
5. **动画一律用 `Anim`**，并且直接受 `UiSettings.animations` 控制（关掉时立即到位）——
   不要自己写 `if (animationsEnabled)` 分支，库的 `Anim.tick(ctx.animations, dt)` 已经处理了。
6. **文本用 `Label`/`UiDraw.text`**（已按 `theme.fontScale` 缩放），不要再自己乘缩放系数。
7. **输入转发**：覆盖层不在 Screen 栈上，键盘/鼠标由两个 mixin 转发；
   新界面不需要自己接事件，只要把控件挂进 `UiLayer.root`。
8. 新增文案一律走 lang 键，两份语言文件都要加（`中文 | English` 格式）。

## 2. 模型模式（`com.ovo.sablestopnow.client.model`）

### 2.1 概念

把每一个 Sable 物理结构当成 SolidWorks 里的一个**模型**：相机绕它转，几何不动。
旧的「配合模式」（Y 键）升级成了模型模式，配合只是模式内的一个功能。
进入即**释放光标**（要能点侧栏），玩家被冻结（不移动/不攻击/不交互），退出时恢复。

### 2.2 相机（`ModelCamera`）

* 中键拖动 = 轨道旋转；`Ctrl` + 中键 = 平移；`Shift` + 中键 / 滚轮 = 缩放（以光标附近为中心）；
  空格 = 视图定向（前/后/左/右/上/下/等轴测）。
* 位置由「目标点 − 视线 × 距离」反推，所以「缩放 = 改距离」，透视关系天然正确。
* 相机改写发生在 `Camera.setup` 的 TAIL（`ModelCameraMixin`）：世界渲染、区块剔除、声音衰减都读同一个相机，
  只改渲染矩阵会导致「画面动了但区块还在原地剔」的闪烁。
* 视线用 Minecraft 自己的 yaw/pitch 约定，避免多一次换算和一堆符号错误。

### 2.3 几何：点/棱/面以**方块碰撞箱**为准

* `MateTargeting.boxesOf(body, pos)`：取方块碰撞箱（`getCollisionShape`，空则回退轮廓箱：火把/花这类）。
* `MateTargeting.pickBox(boxes, localHit)`：射线命中点落在哪个子碰撞盒。
* `BoxHit(body, block, face, hit, box)`：一次「碰撞箱级」命中，比旧的 `Hit` 多带一个**子盒**。
* 五类就近判定（角点 / 棱心 / 棱 / 面心 / 面）按**子盒尺寸归一化**后判定，所以台阶、楼梯上与整格一致。
* `MateRef` 新增 `box` 字段（方块局部 0..1，`null` = 整格）：`resolveLocal` 依旧只依赖
  「包围盒 + 参考描述」，是纯函数，因此能在离线 checker 里直接验证；NBT 与网络协议都会带上它
  （整格不写，保持与旧存档/旧协议兼容）。

### 2.4 界面（`ModelUi`）

三层覆盖层，互不遮挡：上边栏（`Tabs`：配合 / 模型 / 视图 + 当前标签页的功能按钮）、
左边栏（编辑区：当前功能、已选元素、可选配合类型、参数、完成/取消）、右边栏（树状图）。
树状图结构：`模型 → [配合 / 属性（隐藏·碰撞·锁定） / 可控施力点]`，模型间的配合在两个模型下都会出现。

### 2.5 左键拖动模型：为什么不显式「解锁」关节

需求原文是「解锁 → 缓移 → 锁定」。但**直接释放关节是有害的**，推演如下：

* 关节（`GenericConstraintHandle`）是按**创建那一刻的相对位姿**建立的；释放之后再重建，
  新关节会按**当时**的位姿建立 —— 也就是说，把 A 拖到新位置再重建，配合的数值会被
  悄悄改成「拖动后的那个距离/角度」。玩家会看到「配合还在、但它已经不约束原来的值了」，
  这比「拖不动」严重得多。
* 配合自己的搬运（`MateRegistry.startSnap`）之所以能安全地解锁 ↔ 重锁，是因为它在锁定前
  会**把从动结构挪到满足原约束的位姿**（`buildJoint` 再用此刻的几何建关节，两者一致）。
  拖动没有这个「解算目标」，所以不能照抄。

因此拖动的做法是：**保持关节、限速搬运驱动力 + 每个物理子步清零速度**。
约束求解器会把同一装配里的其它结构一起带走，配合的数值因此天然保持 —— 这就是
「幽灵物体只传递力」在拖动场景下的体现。到位的表现是「整组跟着缓慢移动」而不是「只有被拖的那个动」。

其它两件当时列为未完成的事已经补上：**「隐藏」现在真的不渲染**（三条渲染路径都在入口拦掉，
见 `mixin/client/*SubLevelRenderScaleMixin`），**属性开关（碰撞/锁定）直接读写服务端的权威状态**
（`ModelProperties` 不再自己存一份镜像）。
