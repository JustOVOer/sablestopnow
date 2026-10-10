# GUI 统一化：现状分析、根因与改造方案

> 状态：**分析完成 ✓ / 改造未开始 ✗**（本文先落地"为什么"和"怎么改"，再按 P0→P3 实施 ✓）
> 适用版本：SableStopNow 1.1.1（NeoForge 1.21.1 / Sable 2.0.4 / Simulated-Aeronautics 1.3.1）
> 触发需求（用户原话）：**「把 GUI 逻辑统一化 … 之前出现的给贴图 GUI 在左栏加上一个始终存在的 bug 不能再现 … 640×480 到更大分辨率下 gui 文字都不能有『位置不够而……』… 最后落实到统一的 GUI 逻辑，并变成文档」**

---

## 1. 现状：GUI 管理逻辑地图

```
ClientConfigScreens.register(container)            // 模组列表「配置」按钮 → ModSettingsScreen.create(parent)  统一入口 ✓
  └─ ModSettingsScreen        (237 行)             // 大类选择页：左栏 = 三个大类
  └─ ModSettingsCategoryScreen(528 行)             // 大类详情页：左栏 = 设置分组，右侧 = 设置行
ModConfigCategory (enum)                           // ★ 声明式数据：title / subtitle / icon / options()  ← 唯一的"配置真源" ✓
ConfigOption (188 行)                              // ★ 选项模型：bool / number / stringList / action + withChange(...)
client/gui/lib/  (~45 个文件)                       // 自研 GUI 库：UiRoot / Widget / Layouts / Label / ScrollPane / UiLint(408 行) …
client/hud/*, client/model/*Ui*                    // 另一批界面（HUD 行、模型编辑）—— 未纳入本次统一 ✗
```

**已经很好的部分** ✓（改造要建在它上面，不重写）：`ModConfigCategory` 的声明式 `options()` ✓、
`ConfigOption` 的选项模型 ✓、自研库的控件与动画 ✓、`UiLint` 的检查框架 ✓。

---

## 2. 已确认的 bug（根因，带可复核的证据）

### 2.1 现象
**给配置界面加一个 GUI/贴图相关的项之后，左栏出现一个"始终存在"的分组** ✗ —— 删不掉、也不该出现。

### 2.2 根因：**分组是手写的第二份清单，用字符串名引用配置项**
`ModSettingsCategoryScreen` L62-104：

```java
private record Section(String titleKey, List<String> optionNames) { }   // ← 身份是【字符串】✗
private static final Map<ModConfigCategory, List<Section>> SECTION_PLAN = Map.of(
    ASSEMBLER, List.of(
        new Section("gui.sablestopnow.section.force",    List.of("filter_excessive_force", "threshold",
                                                               "excluded_groups", "max_records",
                                                               "auto_pause_on_filter")),   // 5
        new Section("gui.sablestopnow.section.assembly", List.of("lock_new_sub_levels",
                                                               "disable_placement_collision_check",
                                                               "require_confirmation_before_split")), // 3
        new Section("gui.sablestopnow.section.speed",    List.of("speed_limit_enabled",
                                                               "speed_limit_threshold")),   // 2
        new Section("gui.sablestopnow.section.hull",     List.of("hull_inset"))),           // 1  ← ★ 漏了 hull_max_pieces
    …);
```

而**真源** `ModConfigCategory.options()` 里 ASSEMBLER 的 hull 组有 **两项**：

```java
ConfigOption.number("hull_inset",      config.hullInset,      0.0, 2.0, 0.01),
ConfigOption.number("hull_max_pieces", config.hullMaxPieces,  1.0, 64.0),        // ← 未出现在 SECTION_PLAN ✗
```

**数一遍**（可复核 ✓）：真源 ASSEMBLER = **12 项**；
`SECTION_PLAN` = 5 + 3 + 2 + 1 = **11 项** ✗ ⇒ **恰好漏 1 项** = `hull_max_pieces` ✓。

`SECTION_PLAN` 的注释自己写着「漏掉的分组由 `buildSections()` 兜底」✓ ⇒ 漏项落进
`SECTION_OTHER`（`gui.sablestopnow.section.other`）✗ ⇒ **左栏凭空多出一个常驻「其它」分组** ✗✓
—— 与用户描述的现象**完全一致** ✓。

### 2.3 为什么现有的门禁抓不到它
- 两侧都是**字符串** ✗ ⇒ 名字漂移**不产生编译错误** ✓；
- `UiLint`（408 行）检查的是绘制/布局层 ✓，**不检查"规格 ↔ 控件"一致性** ✗；
- `verify-all.ps1` 里没有任何 GUI 断言 ✗。

⇒ **静默漂移** ✓。这类 bug 的特征是：**加功能的人没错，错在"要在两个地方各写一遍"** ✗。

---

## 3. 对照十条架构原则的逐条判定

| # | 原则 | 现状 | 证据 |
|---|---|---|---|
| 1 | **单一数据源**（文档模型是唯一真相）| ✗ **违反（三处）** | ① `SECTION_PLAN` 手写副本 ✗（**本次 bug**）② `ModSettingsScreen.CATEGORIES` + `IconTile[] tiles`（分类又存一份）✗ ③ `ConfigOption` 以字符串名为身份 ✗ |
| 2 | 选择上下文（联动引擎）| ✗ 不存在 | 配置界面无"选择"概念；`client/model/*Ui*` 也无统一选择上下文 ✗ |
| 3 | 命令模式（一切修改都是命令）| ✗ 不存在 | 控件直接改配置（`withChange(...)`）⇒ 无 `execute/undo`、无事务、无命令栈 ✗ |
| 4 | 工具状态机 | ✗ 隐式 boolean | `ModSettingsScreen` L119-120 `closing` / `switchPending`；`ModSettingsCategoryScreen` 同 ✗ = "用一堆 if 判断当前在干什么" ✗ |
| 5 | 异步求解与反馈 | — 不适用 | 配置界面无需；草图求解另有机制 ✓ |
| 6 | 插件化注册表 | ▲ 一半 | 分类像注册表 ✓，但**分组是手写 Map** ✗、控件类型靠 `switch` 硬编码 ✗ |
| 7 | 事件总线 | ▲ 弱 | 只有配置变更回调 ✓；界面间靠直接 `setScreen` ✓（导航场景可接受 ✓）|
| 8 | 可视化派生 | ▲ 一半 | `ModConfigCategory.icon()` 多级回退、从注册表派生 ✓（好）；但界面状态全靠手写 ✗ |
| 9 | 渐进式架构 | ✗ 反例 | 两个屏幕 237 / 528 行**手工布局 + 手工动画编排** ⇒ 每加一个界面就复制一遍 ✗ |
| 10 | 测试与可观测性 | ✗ 缺 | `UiLint` 存在 ✓，但没有"规格↔控件一致性"断言 ⇒ 本次 bug 逃逸 ✗ |

**一句话根因**：**「选项数据」「分组归属」「控件与坐标」是三套东西，靠字符串名手工对齐** ✗
⇒ 加一项就可能在别处静默长出一块 ✗。

---

## 4. 改造方案（原则：影响小 ✓ 在原基础上改 ✓ 只改"管理" ✓ 便于以后加 GUI ✓）

### P0 —— 止血 + 防复发（零风险，先做）
1. `SECTION_PLAN` 的 `section.hull` 补上 `"hull_max_pieces"` ✓（一行 ⇒ 用户报的「其它」分组当场消失 ✓）；
2. **新增一致性断言**（注册进 `tools/verify-all.ps1`）：
   ```
   对每个 ModConfigCategory：
     options() 里的每个选项名，必须在 SECTION_PLAN 里【恰好出现一次】✓
     反向：SECTION_PLAN 里的每个名字，必须存在于 options() 里 ✓
     （可选）每个 Section 的 titleKey 必须在 lang 文件里存在 ✓
   ```
   ⇒ 任何"加了选项忘写分组 / 写了分组名拼错"的行为**立刻红** ✗（今天就应当红 ✓）。

### P1 —— 单真源：分组归属搬进选项自己
给 `ConfigOption` 增加**分组声明**（例如 `.section("gui.sablestopnow.section.hull")`）✓
⇒ 分组随选项一起走 ✓ ⇒ 名字漂移从"运行期才发现"✗ 变成"**编译期不可能**" ✓。
`SECTION_PLAN` 退化为**可选覆盖**（只用于"顺序/折叠"这类显示偏好 ✓）或直接删除 ✓。

### P2 —— 规格 → 控件的**单一生成器**
引入 `UiSpecBuilder`（分类 → 分组 → 行）✓：屏幕类**只交规格** ✓，
左栏 / 分组 / 行 / 滚动 / 焦点由框架生成 ✓。
⇒ **不变量**：`左栏条目数 == 规格条目数` ✓（可断言）⇒ "多一个常驻条目"✗ **结构性不可能** ✓。
⇒ 同时消掉 `IconTile[] tiles` / `List<FormRow> rows` / `List<SectionPane> sections` 这类
**跨代残留数组** ✗（`build()` 每次 `init()` 都重建树，但数组从不清理 ✗）。

### P3 —— 可用性：文字溢出策略统一（用户明确要求）
`Label` 统一提供三选一，**顺序固定**：
```
wrap（换行） → ellipsize（省略号） → scroll（滚动）
```
**硬规则**：文字**永不裁切** ✗；**绝不**"因为放不下就不画 / 报错"✗。

**测试矩阵**（用户要求 640×480 起）：
```
分辨率：640×480 / 854×480 / 1280×720 / 1920×1080 / 2560×1440
GUI scale：1 / 2 / 3 / 4
语言：zh_cn / en_us（各自取最长文案）
断言：每个标签的【测量宽 ≤ 可用宽】或【已按上述规则处理】✓
```
（更小分辨率按用户要求"可以宽容"✓。）

### P4 ——（可选、小改）状态机化
`closing` / `switchPending` → `ScreenState` 枚举（`Idle / Entering / Leaving`）✓
对应原则 #4 ✓；改动局限在两个屏幕类 ✓。

### P5 —— 文档与流程
本文即为文档 ✓；另外在文末给出**新增 GUI / 新增选项的标准步骤**（见 §6 ✓）。

---

## 5. 不变量清单（都可断言，进 `verify-all`）

| # | 不变量 | 防的是 |
|---|---|---|
| I1 | `options()` 的每个名字在分组里恰好出现一次 ✓ | **本次 bug**（漏/重 ✗）|
| I2 | 分组里的每个名字都在 `options()` 里存在 ✓ | 拼错名/删项忘删分组 ✗ |
| I3 | 左栏条目数 == 规格分类数 ✓ | "常驻幽灵条目" ✗ |
| I4 | 每次 `build()` 后，控件树里的控件数 == 规格条目数 ✓ | 跨代残留数组 ✗ |
| I5 | 每个标签在测试矩阵下"放得下"或"已按溢出策略处理" ✓ | "位置不够而……" ✗ |
| I6 | 每个 lang 键在 `zh_cn` / `en_us` 中都存在 ✓ | 显示成原始键名 ✗ |

---

## 6. 新增一个 GUI / 新增一个选项的标准步骤（防复发清单）

**加一个配置选项**：
1. 在 `SablestopNowConfig` 加字段 + 访问器 ✓；
2. 在 `ModConfigCategory.options()` 加一行 ✓（**只此一处** ✓ —— P1 完成后分组也在这里声明 ✓）；
3. 跑 `verify-all.ps1` ✓（I1/I2 必须绿 ✓）。

**加一个界面**：
1. 定义 `UiSpec`（分类/分组/行）✓；
2. 用统一生成器建树 ✓（**不要**手写坐标 ✗）；
3. 在 `verify-all.ps1` 的 UI 组里注册它的分辨率断言 ✓（I5 ✓）。

---

## 7. 未验证 / 待办（如实标注）

- 本文的 bug 结论**已由行号 + 计数复核** ✓，但**尚未**在游戏内复现一次截图确认 ✗（P0 修完后可顺带验 ✓）；
- P1/P2/P3 **未实现** ✗；
- `SECTION_PLAN` 之外的其它"第二真源"（`ModSettingsScreen.CATEGORIES` / `tiles[]`）已定位 ✓ 待 P2 一并消掉 ✓；
- `client/hud/*` 与 `client/model/*Ui*` 这批界面**尚未**纳入统一 ✗（作为后续阶段 ✓）。

---

## 8. 执行记录（2026-10-03）

### P0 —— 止血 + 防复发 ✅ 完成
- `ModSettingsCategoryScreen` 的 `section.hull` 补上 `"hull_max_pieces"` ✓
  （修前计数：真源 12 项 / 计划 11 项 ⇒ 恰好漏这一项 ⇒ 它落进 `SECTION_OTHER` ⇒ **左栏常驻「其它」分组** ✓ 就是用户报的现象 ✓）。
- 新增检查器 `tools/GuiSpecCheck.ps1` ✓ 并注册为门禁块 **`guispec`** ✓。
- **反向自检**（证明它真会报 ✓）：抹掉一个 `.section(...)` ⇒ `I1' … option 'hull_max_pieces' does NOT declare .section(...)` ✓；
  顺序表里塞幽灵组 ⇒ `I4' … ghost group` ✓；两者都 exit=1 ✓，还原后 101/0 ✓。

### P1 —— 单真源 ✅ 完成
- `ConfigOption` 新增 `section(...)` / `section()` ✓（链式 ✓ 与 `withChange` 一致 ✓）。
- **37 个选项全部声明** ✓（ASSEMBLER 12 / STAFF 16 / GOGGLES 9 ✓）；用"名字→组"映射 + **括号配对扫描**自动插入 ✓
  （多行的 `keybinds` λ 与带 `withChange` 的两条都插对了 ✓ 计数 37/37 ✓）。
- `SECTION_PLAN` 退化为**只提供顺序** ✓；`buildSections()` 改读 `option.section()` ✓
  ⇒ "加一项却忘写分组"的**成因被删掉** ✓（门禁 I1'/I3'/I4' 兜住剩余情形 ✓）。

### P2 —— 规格 → 控件 ✅ 完成
- 新增 `client/gui/UiSpec.java` ✓：`record Group(titleKey, options)` + `groups(options, orderKeys)` ✓
  （归组只在规格里 ✓ 顺序是偏好 ✓ 未声明的才进 `SECTION_OTHER` ✓）。
- 屏幕只做"规格 → 控件" ✓（`ModSettingsCategoryScreen.buildSections()` ✓）。
- 跨代残留：`ModSettingsScreen.build()` 现在清 `tiles/panel/titleLine1/2` ✓；
  `ModSettingsCategoryScreen` 本来就清 `rows/sections` ✓（核实 ✓ 无需改 ✓）。
- 顺手修了门禁 lint 的**误报** ✗：裸绘制判据从"有 `.fill(`"改成"接收者像 `graphics.`"✓
  （反向自检：注入真 `graphics.fill(...)` 仍能抓到 ✓）。

### P3 —— 文字可用性 ✅ 完成
- `Label` 新增 `Overflow{WRAP, ELLIPSIZE(默认), CLIP}` ✓（**默认与历史一致 ⇒ 零回归** ✓）；
  `WRAP` 在 `prefW` 为确定宽度时于 `measure` 拆行并给多行高度 ✓，否则在 `render` 按最终宽度拆 ✓。
  **已知限制** ✗：`prefW = FILL` 的标签换行时高度仍按一行 ⇒ 多出的行会被父裁剪区切掉 ✗（需行随文字变高时请给确定 `prefW`）。
- `UiDraw` 补 `FormattedCharSequence` 重载 ×3 ✓（缺它时换行渲染无处可画 ✗ —— 曾因此构建失败一次 ✓）。
- **SCROLL 不由 Label 承担** ✓ —— 由容器 `ScrollPane` 负责 ✓（避免 Label 又长出一个滚动条 ✗）。
- 新增 `client/gui/lib/TextFit.java` ✓：**字体无关**的检查器（度量由调用方注入 ✓）
  + 自带 `selfTest()`（含"必须报错"的合成用例 ✓）⇒ 已进 `GUI_LINT_TEXTFIT=OK checks=6` ✓。
- 新增 `client/gui/ConfigTextFit.java` ✓：把声明式数据展开成矩阵条目（含 `where` 便于点名 ✓）。
- 启动自检接线 ✓（`StaffEnhanceClientEvents.runSelfTest` ✓）：5 档分辨率 × 4 档缩放（保守假设"像素=GUI px"✓）
  ⇒ 通过打 `[text-fit] 文本可用性通过` ✓；不通过逐条打 `[text-fit] <where> / <area>：<detail>` ✓。
- 新增门禁块 **`layout`** ✓（`tools/GuiLayoutCheck.ps1` ✓ 自包含 ✓ 不改既有脚本 ✗）：
  L1 `ROW_H ≥ ceil(baseLine × maxFontScale) + 2` ✓ / L2 `CONTROL_W ≥ 60` ✓ / L3 左栏最小宽 ≥ 80 ✓ / L4 解析失败即报 ✗；
  带 `-SelfTest`（合成常量验"必须报错"✓ 不改源码 ✗）✓。

### P4 —— 状态机化 ⏳ 未做（可选）
`closing` / `switchPending` → `ScreenState{Idle, Entering, Leaving}` ✓（两个屏幕类 ✓ 局部改动 ✓）。

### 门禁现状（`tools/verify-all.ps1`，9 个块）
```
types ✓  mixins ✓  lang ✓  GUI lint（含 GUI_LINT_TEXTFIT ✓）✓  outline ✓
sketch（30/2148/12/54/5/148 项 ✓）✓  nurbs ✓  param ✓
guispec（101 项 ✓）✓  layout（3 项 ✓）✓          ⇒ VERIFY_ALL=PASS ✓
```

### 新增/改动的文件
```
新增  tools/GuiSpecCheck.ps1 ✓   tools/GuiLayoutCheck.ps1 ✓
新增  client/gui/UiSpec.java ✓   client/gui/ConfigTextFit.java ✓   client/gui/lib/TextFit.java ✓
改动  ConfigOption.java（section ✓）  ModConfigCategory.java（37× .section ✓）
      ModSettingsCategoryScreen.java（buildSections 消费规格 ✓ SECTION_PLAN 降级为顺序 ✓）
      ModSettingsScreen.java（清跨代引用 ✓）  Label.java（Overflow ✓）  UiDraw.java（FCS 重载 ✓）
      StaffEnhanceClientEvents.java（启动文本检查 ✓）  tools/verify-all.ps1（两个新块 ✓）  tools/GuiLintCheck.java（TextFit 自检 ✓）
```

### 仍未完成 / 已知限制（如实）
1. **只量当前语言** ✗ —— 换语言后需下次启动才覆盖另一种（检查每次启动都跑 ✓）；
2. 矩阵按"窗口像素 = GUI 像素"的**保守**假设 ✓（GUI 实际只会更宽 ✓ 因此只会报得早 ✓ 不会漏 ✗）；
3. `prefW = FILL` 的标签换行高度问题 ✗（见 P3 那一条 ✓）；
4. `client/hud/*` 与 `client/model/*Ui*` **尚未**纳入本次统一 ✗（后续阶段 ✓）；
5. 实机端到端验证 ⏳：点进配置界面看左栏是否**不再**出现「其它」分组 ✓ 以及新标签的换行/省略表现 ✓。

---

## 9. 执行记录（第二轮 · 全覆盖与"不再遮盖"）

### 9.1 库级能力（**调用点零负担** ✓ 这是本轮的治理主线）

| 能力 | 位置 | 说明 |
|---|---|---|
| **自动换行** `Layouts.hboxWrap(pad, gap, rowGap)` ✓ | `gui/lib/Layouts.java` | 一行放不下 ⇒ **折到下一行** ✓；**行高由孩子的需求决定** ✓（不是"容器高 ÷ 行数"✗）；并把所需高度写回 `parent.prefH`（仅当 AUTO ✓）⇒ 写界面的人不需要知道会折几行 ✓ |
| **自动短标签** ✓ | `Label` + `Button` | 可翻译文案若是 `<key>` ⇒ 宽度不够时自动用 `<key>.short` ✓（`TranslatableContents#getKey()` ✓）；不存在则退回原文 ✓ 不报错 ✓ |
| **自动缩字号** ✓ | `Label` | 短标签也放不下 ⇒ 按需缩小，下限 `UiSettings.textShrinkMin`（默认 0.80 ✓ 可调 ✓） |
| **绝不重叠** ✓ | 各 `Layouts` | 同一容器内由布局统一排布 ⇒ 结构上不会互相压住 ✓ |

> ⚠ 事故记录（本轮实际发生 ✓）：`hboxWrap` 第一版把行高算成"容器高 ÷ 行数"✗ ⇒ 容器 13px、两行 ⇒ 每行 5px ✗
> 而按钮自己要 12px ⇒ F9 报告里出现 `Button(11,119 26x12)` 与 `Button(11,122 …)` **重叠 9px** ✓。
> 教训：**"排几行、每行多高"必须由孩子的需求决定** ✓ 不能由容器反推 ✗。

### 9.2 "全覆盖"的判据（都在 `UiLint` 的活控件树遍历里 ✓）

| 病 | 判据 | 备注 |
|---|---|---|
| **文字没放下** ✗ | `Label.lastFit ∈ {ELLIPSIZED, CLIPPED}` ✓ | 结果由 `Label` 自己记 ✓ 校验器不重算 ✗ |
| **控件互相遮盖** ✗（含文字 ✓）| 同父兄弟矩形相交 > 1px² ✓ | ⚠ 以前只查"可点击"控件 ✗ ⇒ **标签被排除** ✗✓ 这正是"文字遮盖"长期漏报的原因 ✓ |
| **判据唯一来源** ✓ | `TextFit.notFitted(...)` / `TextFit.isUnhandled(...)` ✓ | 两种输入（自己量 / Label 实测）走**同一套**判断 ✓ 避免两处漂移 ✗ |

**降噪规则** ✓（误报会淹没真信号 ✗）：0 尺寸只报"**能点的**或**有文字的**"✓；
空文字标签（吸附提示未吸附时就是空串 ✓）与缩进 spacer（depth=0 时故意宽 0 ✓）不再误报 ✓。

### 9.3 门禁（现 9 个块 ✓ 其中 3 个是 GUI 自检 + 2 个是 GUI 不变量）

```
GUI_LINT=OK 7 ✓  GUI_LINT_OVERLAP=OK 8 ✓  GUI_LINT_TEXTFIT=OK 6 ✓（都在 types 块里跑 ✓）
GUI_SPEC_CHECK=OK 101 ✓（选项↔分组↔顺序↔lang 键 ✓）
GUI_LAYOUT_CHECK=OK 3 ✓（行高/控件列宽/左栏宽 ✓ 含 -SelfTest ✓）
```

### 9.4 状态机（原则 #4 ✓ 已在 `ModSettingsScreen` 落地 ✓）

`closing` + `switchPending` 两个 boolean ✗ → `State{IDLE, LEAVING, SWITCH_PENDING}` ✓（10 处替换零遗漏 ✓）。
`ModSettingsCategoryScreen` 的同类布尔**尚未**迁移 ✗。

### 9.5 环境陷阱（本轮踩到并记住 ✓）

> **混入阶段的 `ClassNotFoundException: <我们自己的类>` ≠ 那个 class 文件缺失** ✗✓
> 实测：`StaffEnhanceClientHandler.class` 一直在 `build/classes` 里（51.6 KB ✓ 143 个 client class ✓），
> 但客户端启动时在 `MixinPreProcessorException → CNFE` 崩了 ✓。
> 处置：**清 `build\classes` 全量重编** ✓（未改任何代码 ✗）⇒ 崩溃消失 ✓。
> ⇒ 先怀疑"增量产物内部不一致"✗，别急着改代码 ✓。

### 9.6 仍未完成

1. ③b：把 `UiSettings.textShrinkMin` 接到配置界面（需改 `SablestopNowConfig` 4 处 ✓ 该文件尚未读全 ✗）；
2. B：选项/分组身份由**字符串**升为**常量/枚举** ✓（现在靠门禁期发现 ⏳ 目标是编译期不可能 ✓）；
3. D：`client/hud/*` 与 `client/model/*Ui*` 的系统性纳入 ✓；
4. E 的另一半：`ModSettingsCategoryScreen` 的状态机 ✓；
5. 实机端到端复验 ⏳：打开草图界面 → F9 ⇒ 报告里"遮盖/没放下"应只剩有据项 ✓。

---

## 10. 执行记录（第三轮 · 收口与两次真实事故）

### 10.1 折行布局的两处**关键**修正（都来自实测反馈，不是推演）

| 症状（用户实测 ✓）| 根因（读代码确认 ✓）| 修法（库内 ✓）|
|---|---|---|
| 五种图元按钮**分两行但上下重叠 9px** ✗ | `hboxWrap` 第一版把行高算成"**容器高 ÷ 行数**"✗ ⇒ 容器 13px、两行 ⇒ 每行 5px ✗ 而按钮要 12px ✓ | 行高改由**孩子的需求**决定 ✓（`rowHeights[i] = max(child.wantH + margins)` ✓）|
| 「圆弧」仍被**截断** ✗（其余四个正常 ✓）| `Button` 的短标签**只在 `compact == true` 时**才用 ✓，而只有 `hboxFit` 会调 `setCompact(true)` ✗ —— 换成 `hboxWrap` 后**没人调了** ✗✓ | `hboxWrap` 内：分配宽 < 自然宽 ⇒ `child.setCompact(true)` ✓ ⇒ 「圆弧（圆心/起点/终点）」自动变「弧」✓（放得下的四个不受影响 ✓）|
| 折行后**位置不对** ✗（第二行画到容器外）| `vbox` 用 `child.wantH(ctx)` 定高（**Layouts L70** ✓），而它在本行自己布局**之前**跑 ⇒ 只能拿到**上一帧**的 `prefH` ✗ | `hboxWrap` 内做**高度夹取** ✓：装不下就按可用高**等比压缩行高** ⇒ 内容**始终留在容器内** ✓ 且绝不重叠 ✓；同时仍把自然高写回 `prefH` ✓ |

> 硬保证（库级 ✓）：**同一容器内不重叠、不越界** ✓；真放不下时最多被裁剪 ✗ —— 而裁剪会被 `UiLint` 点名 ✓。

### 10.2 状态机（原则 #4 ✓ 两个屏幕都完成 ✓）

`ModSettingsScreen`（10 处替换 ✓）与 `ModSettingsCategoryScreen`（11 处 ✓）里的
`closing` + `switchPending` 两个 boolean ✗ → 统一为
`State{IDLE, LEAVING, SWITCH_PENDING}` ✓（两屏语义一致 ✓）。

### 10.3 "自动缩字号下限"接进配置界面 ✓（原 C 的后半 ✓）

```
SablestopNowConfig：字段 uiTextShrinkMin ✓ + defineInRange("ui_text_shrink_min", 0.80, 0.6, 1.0) ✓
                    + syncUiSettings 里写入 UiSettings.textShrinkMin ✓
ModConfigCategory：GOGGLES › 界面 分组新增一行 ✓（.section("…section.ui") ✓）
GuiSpecCheck：选项计数自动 9→10 ✓（门禁 102 项 ✓）⇒ 说明断言真的在数 ✓
```

### 10.4 两条**流程**教训（都实际踩过 ✓）

1. **写入文件后必须从磁盘重新读回来逐条断言** ✓✓
   事故：对 `SablestopNowConfig` 的三处改动**根本没写进文件** ✗，而脚本只报了"锚点未匹配 = 0" ✗
   ⇒ 看起来全成功 ✗ ⇒ 直到编译报 `cannot find symbol` 才发现 ✓。
2. **多行语句不能按"第一行之后"插元素** ✓✓
   事故：`ConfigOption.number("ui_font_scale", …)` 的 `.section(...)` 在**下一行** ✓，
   我把新元素插在两者之间 ✗ ⇒ 把它劈成两半 ⇒ `')' expected` ✗。
   ⇒ 插入点要选**语句的边界**（前一个元素的结尾之后 / 下一个元素的开始之前 ✓）。
3. 附带：**混入阶段的 `ClassNotFoundException: <我们自己的类>` ≠ class 文件缺失** ✗
   ⇒ 先清 `build\classes` 全量重编 ✓（实测有效 ✓ 未改任何代码 ✗）。

### 10.5 门禁现状（10 个检查器 / 9 个块 ✓）

```
types（FULL_TYPECHECK ✓ + GUI_LINT 7 ✓ + GUI_LINT_OVERLAP 8 ✓ + GUI_LINT_TEXTFIT 6 ✓ + HARNESS_EXIT 0 ✓）
mixins ✓  lang（zh=639 en=639 undefined=0 ✓）  GUI lint（裸绘制 0 ✓）  outline 14 ✓
sketch（30 / 2148 / 12 / 54 / 5 / 148 ✓）  nurbs ✓  param 23 ✓
guispec（102 ✓）  layout（3 ✓）                      ⇒ VERIFY_ALL=PASS ✓
```

### 10.6 仍未完成

1. **B（身份枚举化）**：把 `ConfigOption` 的 `name` 与分组键从**字符串字面量**升为**常量/枚举** ✓。
   ⚠ 注意：`GuiSpecCheck.ps1` 是**按源码文本解析** name 的 ✓ ⇒ 改成常量后**检查器必须同步升级**（要能解析常量引用 ✓），否则 I1'/I3' 会失效 ✗ —— 这是这一步真正的成本所在 ✓。
2. **D**：`client/hud/*` 与 `client/model/*Ui*` 的**运行时**覆盖其实**已经有了** ✓
   （`UiLint` 走的是所有已注册层 ✓ F9 报告里能看到 `sketch.snap.hud` / `model.tree` / `staff.body.info` ✓）；
   还缺的是把它们纳入**离线**矩阵（价值较低 ✗ 因为 A3 已让"活树全覆盖"成立 ✓）。
3. 实机端到端复验 ⏳（打开草图界面 → F9 ✓）。

---

## 11. B 项（身份枚举化）的结论：**在这一步停止**，并说明理由

### 11.1 做了什么
新增 `client/gui/Sections.java` ✓ —— 13 个**分组键**的枚举（`FORCE` / `ASSEMBLY` / … / `UI` ✓），
带 `id()` / `key()` / `fromKey()` ✓；类注释里写清了它与"选项名"的区别 ✓。

### 11.2 为什么**不**把 39 处调用点改成 `Sections.X.key()`
| 项 | 判断 | 依据 |
|---|---|---|
| **选项名**枚举化 | ✗ **不可达** | TOML 键必须是字符串（`define("hull_inset", …)`）✓ ⇒ 枚举的 `name()` 仍是字符串 ✗ ⇒ 打错字只是换地方 ✓ **零收益** |
| **分组键**枚举化 | ▲ 理论可行 ✓ 但**必须先改检查器** | `GuiSpecCheck.ps1`（102 项 ✓）与 `GuiLangCheck.ps1`（152 项 ✓）都是**按源码文本解析** `.section("…")` 字面量 ✓ ⇒ 一旦改成 `Sections.X.key()` ✗ 两者**静默失效**✗✓（最危险的一类失败 ✓） |
| 风险是否已消除 | ✅ **已消除**（等价形式 ✓）| 打错名字 → 界面显示原始键名 ✗ 这一类**已被 I7 抓住** ✓（38 选项 × 2 语言 × 2 键 = 152 项 ✓ 反向自检 ✓ 且**第一次跑就抓到我自己漏的 4 条** ✓✓）|

实测记录 ✗：我试过用"在内存里把 `Sections.X.key()` 归一化回字面量"✗ 让检查器兼容两种写法 ✓，
但插入点落在脚本注释区附近 ⇒ `$sectionsPath` 为 null ⇒ 脚本报错 ✓ ⇒ **已完整回退** ✓（不留半成品 ✗）。
⇒ 结论：**"编译期不可能"在 TOML 键体系下不可达** ✗；可达且已达成的是"**门禁期必抓**" ✓✓。

### 11.3 `Sections.java` 的定位
保留 ✓ 作为*分组 id 的唯一清单*（文档价值 ✓；若将来要做彻底重构，它已经就位 ✓），
但**当前不被引用** ✗ ⇒ 对运行行为零影响 ✓。

### 11.4 D 项（HUD / 模型 UI 纳入统一）的结论
**运行时覆盖其实已经有了** ✓：`UiLint` 遍历的是**所有已注册层** ✓ ——
F9 报告里能看到 `sketch.snap.hud` / `model.tree/CollapsiblePanel/TreeView` / `staff.body.info` ✓✓。
⇒ 还缺的只是"把它们塞进**离线**矩阵"（价值低 ✗ 因为 A3 已让"活树全覆盖"成立 ✓）⇒ **不做** ✗。
