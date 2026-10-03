# GUI 库架构：状态、命令、自检

> 面向：以后要往这套界面里加东西的人（包括我自己）。
> 起因：用户实测反馈「设置施力点时滑动条和输入框没有联动」，并指出写 GUI 时
> 「经常有写了没调用或者多次调用」。本文记录重构后的架构、以及为什么是这些约定。

---

## 1. 三条规矩

整套界面只在做一件事：**把状态投影成画面，把玩家操作翻译成命令**。

1. **状态是唯一真相，UI 是投影。**
   控件不持有业务状态。它读一个 `Prop`，画出来；玩家操作时它写那个 `Prop` 或发一条命令。
2. **命令是唯一修改入口。**
   控件之间不互相调用。`Cmd.run("名字", 动作)` 是唯一的出口，于是「谁改了它」永远有迹可循。
3. **联动是派生，不是同步。**
   两个控件要一致，就让它们读**同一个** `Prop`；不要写「A 改了去改 B」的同步代码。

再加两条工程约束：

4. **渲染范围和点击范围必须由同一个函数算。**
   控件的 `x/y/w/h` 是布局器写的，`contains()` 与 `render()` 都只读它；不许在渲染里另算一套。
5. **位置由布局器算。**
   除 `Layouts` 与少数几何特殊的面板（沿折线排布的设置项，见 §6）之外，
   任何地方都不许给控件赋 `x` / `y`。

---

## 2. `Prop`：可观察的状态

```java
private static final Prop<Double> magnitude = Prop.of("force.magnitude", 0.0);
```

- **具名 + 单例**：`Prop.of(key, initial)` 对同一个 key 永远返回同一个实例。
  界面每 12 tick 重建一次控件树，状态若跟着控件一起新建，既会丢掉玩家正在编辑的内容，
  也会让调试注册表无限膨胀。**状态必须活在控件树之外。**
- **相等不广播**：`set()` 对相等的值直接返回 false。服务端回声、面板重建时的重复赋值
  因此不会引发重绘风暴（否则「重建 → 写 Prop → 触发重建」会自激）。
- **重入安全**：通知期间再次 `set` 会被推迟到本轮通知结束后再广播。
- **`batch(Runnable)`**：一批写入合并成一次广播（事务），用于一次改多个字段的场合。
- **`derived(key, source, fn)`**：派生状态，只读。显示用的格式化文本、筛选后的列表、
  「是否可点」都应该这样长出来，而不是各存一份再手工对齐。
- 每个 Prop 都在全局注册表里登记，`UiLint` 靠它找出「写了没人读」「从未写入」这类问题。

写 `Prop` 的代价是有可能被多个地方写。为了能查出来，`Prop.set` 会记录**写入点**
（`lastWriter`，走 `StackWalker`，只在 `UiLint.recording()` 打开时采集）。

---

## 3. `Cmd`：命令总线

```java
Cmd.run("force.magnitude", () -> ForceClientState.requestUpdate(id, v, null, null, null, null));
```

- `Cmd.declare("force.magnitude")` 在搭界面时声明「这些命令由我派发」。
  自检据此报出**已声明但从未派发**（写了没调用）和**已派发但没声明**（拼写错）。
- `Cmd.history()` 是一串命令名，等于一次完整操作序列，可直接回放定位问题。
- 命令体里只写「改状态 / 发包」，不要嵌套派发（自检会把嵌套标出来）。

---

## 4. 控件怎么读状态

| 控件 | 状态 | 说明 |
|---|---|---|
| `Slider` | `Prop<Double> model` | 拖动时**乐观**写 model（界面跟手），松手才 `onCommit` 发命令 |
| `TextField` | `Prop<String> model` / `source` 投影 / 自带的 `own` | 编辑期间用内部 `draft`，结束编辑即丢弃，显示回到投影 |
| `Checkbox` | `Prop<Boolean> model` | 只读投影用 `Prop.derived` 派生，不要另存一个布尔字段 |
| `ComboBox` | `Prop<String>`（通过内部 `TextField`） | 补全列表是它的投影 |
| `Tabs` | `Prop<String> model` | 切页后重建上边栏不会回到第一页 |
| `Dropdown<T>` | `Prop<T> model` | |
| `UiField` | `Prop<Double> value` | **标签 + 滑条 + 输入框三者同源**（见下） |

### `UiField`：为什么它修好了「滑条和输入框不联动」

以前是这么写的：

```java
final Slider slider = new Slider(min, max, point.magnitude(), 1.0);   // 自己一份值
slider.onChange(v -> ForceClientState.requestUpdate(...));            // 改自己 + 发包
final TextField amount = new TextField(String.format("%.0f", point.magnitude())); // 另一份
amount.commit(text -> ForceClientState.requestUpdate(...));           // 改自己 + 发包
```

同一个量在界面上有**三份**副本（滑条 / 输入框 / 服务端），中间没有任何东西保证它们相等 ——
不是漏写了一次同步，而是这种写法**必然**会不一致。

现在：

```java
final UiField amount = UiField.number("model.force.magnitude", CMD_MAGNITUDE, magnitude,
        ForcePoint.MIN_MAGNITUDE, ForcePoint.MAX_MAGNITUDE, 1.0);
amount.commit(v -> ForceClientState.requestUpdate(id, v, null, null, null, null));
```

- 滑条拖 → `value` 变 → 输入框读的是 `fmt(value)` → 立刻跟着变；
- 输入框打字 → 解析成功后写 `value` → 滑条读的是 `value` → 立刻跟着滑；
- 服务端回声写 `value` → 两边同时跟上。

三处只认一个真相，所以「忘了同步」这种失败模式**不存在**，也不需要测试去覆盖它。

### 发命令的时机

拖动 / 打字只改本地状态（界面立刻跟手），真的「提交」才发命令
（滑条松手、输入框回车或失焦）。这样既跟手，又不会一帧一个网络包。

### 服务端回声与「静默期」

`ModelForceUi` 每 12 tick 把服务端的权威值拉进编辑器状态。为了不让回声打断玩家正在进行的拖动，
本地写入后有一小段 `LOCAL_HOLD_MS`（700ms）不拉取。服务端若把值钳过，静默期结束后界面会
回到真实值 —— 这是权威值胜出，属于预期行为。

---

## 5. `Overlay`：弹出层只有一套规则

下拉列表、补全列表、右键菜单、提示气泡全部走 `Overlay`：

- 同一个宿主（owner）再开一次是**替换**，不是叠罗汉；
- 点外面 / Esc 关闭栈顶；
- `MenuList.height()` 与 `openAt()` 共用同一份高度计算，翻转到上方时不会差一像素。

以前各控件自己动 `UiRoot.popups`，于是有「选完信号下拉菜单不消失」「Esc 关的是提示不是菜单」
这类互相踩踏。

---

## 6. 布局

`Layouts` 提供 `vbox / hbox / hboxFit / dock / grid / stack / absolute / hud`。
控件用 `prefW/prefH`（`AUTO` = 自己量、`FILL` = 撑满）和 `margin` 声明意图，位置由布局器写。

**唯一的例外**是模组设置界面：设置项的横坐标沿一条 `>` 形折线走
（`ModSettingsCategoryScreen.alignRowsToChevron`），这条路径不能由矩形布局表达，
在那里直接写 `child.x/y` 是有意为之，注释里写明了原因。

---

## 7. `UiLint`：把低级错误挡在实机测试之前

每一轮实机测试都在替我们找同一类错误。这些不需要玩家去看，遍历一遍控件树就能查出来。

`UiLint.inspect()` 检查：

| 检查 | 抓的是什么 |
|---|---|
| 无响应路径 | 按钮没有动作、复选框没有 `onChange`、滑条没绑 `Prop`、输入框没有可写目标 |
| 命中区重叠 | 同一父容器下两个可见控件矩形相交（点击落到谁身上取决于层序） |
| 尺寸为 0 | 可见但 `w<=0 \|\| h<=0`：画不出来也点不到 |
| 越界 | 超出父容器且父容器不裁剪：渲染范围与命中范围不一致 |
| 孤儿状态 | 写了没人读 / 有订阅但从没被写 |
| 命令 | 声明了没派发 / 派发了没声明 / 嵌套派发 |
| 可观测性 | 可交互控件没有 `id`，命令日志里只能看到类名 |

**两个入口**：

- **启动自检 `UiLint.selfTest()`**：构造出 5 种已知缺陷的控件树，要求每项检查都报出来。
  一个永远报「无问题」的检查器和真的没问题长得一模一样 —— 所以检查器本身也要有测试。
  结果打到启动日志（`[ui-lint] 自检通过` / `自检失败`），并且**离线**跑在
  `tools/verify-all.ps1` 的第 1 步里（`tools/GuiLintCheck.java`），坏掉的检查器进不了实机测试。
- **F9 导出**：把当前所有界面的控件树 + 状态读写 + 命令统计写成 `run/ui-lint.txt`，
  同时打日志。

---

## 8. 加一个新界面 / 新控件的清单

1. 业务状态先在**控件树之外**定成 `Prop`（静态字段或一个 state 对象），不要写在 builder 里。
2. 数值参数用 `UiField`；布尔用 `Checkbox(Prop<Boolean>, ...)`；枚举用 `Dropdown(Prop<T>, ...)`。
3. 每个会发命令的控件都 `.command("模块.动作")`，并在 builder 顶部 `Cmd.declare(...)`。
4. 用布局器摆位置；确实需要绝对坐标时，在注释里写清楚为什么。
5. 写完跑一次 `tools/verify-all.ps1`，再进游戏按 F9 看一眼 `run/ui-lint.txt`。

### 每写完一个 GUI 模块要回答的五个问题

- **哪些控件可能有布局冲突？** —— 看 lint 的「命中区重叠 / 越界」两项。
- **哪些交互路径可能无响应？** —— 看 lint 的「无响应路径」；命令计数为 0 的声明也在里面。
- **哪些字段存在双向绑定？** —— 现在只有一个方向：控件 → `Prop` → 所有订阅者。
  真正意义上的「双向」只出现在 `UiField` 内部，而且两边绑的是同一个 `Prop`。
- **哪些状态变更会触发刷新？** —— `Prop.set` 的订阅者；控件是每帧读的，所以不需要手动标脏。
- **无法确认的地方** —— 标 TODO，不要猜。
