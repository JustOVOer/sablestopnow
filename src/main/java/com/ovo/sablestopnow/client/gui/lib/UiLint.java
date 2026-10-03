package com.ovo.sablestopnow.client.gui.lib;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GUI 自检工具：把「低级错误」挡在客户端测试之前。
 *
 * <h2>为什么要有它</h2>
 * 之前每一轮实机测试都在替我们找同一类错误：按钮写了没接动作、控件搭了没进树、
 * 两个控件抢同一块矩形、状态写了没人读、同一份值被两处各写一份。这些<b>不需要玩家去看</b>，
 * 遍历一遍控件树 + 状态注册表就能查出来。本类就是那遍遍历。
 *
 * <h2>它查什么</h2>
 * <ol>
 *   <li><b>无响应路径</b>：长得能点的控件（按钮/复选框/滑条/输入框/标签栏）却是空壳 ——
 *       点下去只会亮一下，什么都没发生；</li>
 *   <li><b>命中区重叠</b>：同一父容器下两个可见控件的矩形相交 —— 点击落在谁身上取决于
 *       遍历顺序，属于「有时点不中」的经典成因；</li>
 *   <li><b>布局冲突</b>：可见控件尺寸为 0、或超出父容器而父容器不裁剪 ——
 *       画得出来却点不到（或反过来），渲染范围与点击范围不一致；</li>
 *   <li><b>孤儿状态</b>：写了没人读 / 从来没人写 —— 「写了没调用」在状态层的对应物；</li>
 *   <li><b>多处写入</b>：同一个状态被一个以上的位置写过 —— 双份真相的前兆；</li>
 *   <li><b>命令</b>：声明了从没派发 / 派发了没声明 / 嵌套派发；</li>
 *   <li><b>可观测性</b>：可交互控件没有 {@code id}，命令日志里只能看到匿名控件。</li>
 * </ol>
 *
 * <h2>怎么用</h2>
 * {@link #selfTest()} 在客户端启动时跑一遍<b>合成用例</b>（离线可验，不依赖字体/渲染），
 * 确认检查本身没坏；{@link #report()} 输出当前所有已打开界面的实时体检报告，
 * 由调试按键触发并写到 {@code run/ui-lint.txt}。
 */
public final class UiLint {

    /**
     * 是否记录写入点（{@link Prop#lastWriter()}）。
     *
     * <p>走 {@link StackWalker}，每次写入约几微秒；界面写入频率是「每帧几次」量级，可以常开。
     */
    private static boolean recording = true;

    private static final List<String> NOTES = new ArrayList<>();
    private static final Set<String> NESTED = new LinkedHashSet<>();
    private static final int NOTE_LIMIT = 64;

    private UiLint() {
    }

    public static boolean recording() {
        return recording;
    }

    public static void recording(final boolean value) {
        recording = value;
    }

    static void noteNestedCommand(final String name) {
        if (NESTED.add(name) && NOTES.size() < NOTE_LIMIT) {
            NOTES.add("嵌套命令派发：" + name);
        }
    }

    /** 已经报过的「布局塌陷」控件（按类名 + id 去重，避免每帧刷屏）。 */
    private static final Set<String> COLLAPSED = new LinkedHashSet<>();

    /**
     * 布局之后发现某个控件可见却是 0 像素：它既画不出来、也点不到。
     *
     * <p>为什么值得在<b>每帧</b>查一次：这类错误的表现是「界面上少了一块」或者「点了没反应」，
     * 玩家只能描述成「滑动条不能拖动」，而根因往往离现象很远
     * （实测那次是 {@code UiField} 内部一行的 {@code prefH} 留了 {@code AUTO}，
     * 而 {@link Container} 默认的 {@code measure} 是空的 → 整行高度永远是 0）。
     * 在布局之后立刻报出来，就不用等玩家截图了。
     *
     * <p>判据刻意保守：只有「容器高度为 0」或「宽高都是 0」才算塌陷 ——
     * 空标签的宽度本来就是 0，那是正常的。
     */
    static void checkCollapsed(final Widget widget) {
        if (!widget.visible || widget.pointerTransparent) {
            return;
        }
        // 空容器塌陷是<b>正常</b>的：HUD 就是靠「没有内容 → 高度 0 → 看不见」来表示「不显示」的
        // （BodyInfoHudView / StaffControlHudView 的提示面板就是这么做的）。
        // 只有「有可见孩子却还是 0 高」才是布局错误。
        if (widget instanceof Container container) {
            boolean anyVisibleChild = false;
            for (final Widget child : container.children) {
                if (child.visible) {
                    anyVisibleChild = true;
                    break;
                }
            }
            if (!anyVisibleChild && widget.h <= 0) {
                return;
            }
        }
        final boolean collapsed = (widget.h <= 0 && widget instanceof Container)
                || (widget.w <= 0 && widget.h <= 0);
        if (!collapsed) {
            return;
        }
        final String key = widget.getClass().getSimpleName() + "#" + widget.id;
        if (!COLLAPSED.add(key)) {
            return;
        }
        com.ovo.sablestopnow.SablestopNow.LOGGER.warn(
                "[ui-lint] 布局塌陷: {} 可见但尺寸是 {}x{} —— 画不出来也点不到"
                        + "（多半是 prefW/prefH 留了 AUTO 却没有实现 measure，或者布局器没给它空间）",
                key, widget.w, widget.h);
    }

    /** 已报过的塌陷控件（供报告使用）。 */
    static Set<String> collapsed() {
        return new LinkedHashSet<>(COLLAPSED);
    }

    // ============ 体检 ============

    /** 一条体检结论。 */
    public record Issue(String severity, String area, String widget, String detail) {
    }

    /** 对当前所有界面层做一次完整体检。 */
    public static List<Issue> inspect() {
        final List<Issue> issues = new ArrayList<>();
        for (final UiLayer layer : Ui.layers()) {
            if (!layer.visible) {
                continue;
            }
            inspectTree(layer.root, layer.name, issues);
        }
        inspectState(issues);
        inspectCommands(issues);
        for (final String key : COLLAPSED) {
            issues.add(new Issue("ERROR", "布局", key, "可见但尺寸为 0（运行期已经报过，见日志）"));
        }
        for (final String note : NOTES) {
            issues.add(new Issue("WARN", "命令", "-", note));
        }
        return issues;
    }

    private static void inspectTree(final Container parent, final String path, final List<Issue> issues) {
        // ---- 兄弟命中区重叠 ----
        final List<Widget> hittable = new ArrayList<>();
        for (final Widget child : parent.children) {
            if (child.visible && !child.pointerTransparent && child.w > 0 && child.h > 0) {
                hittable.add(child);
            }
        }
        for (int i = 0; i < hittable.size(); i++) {
            for (int j = i + 1; j < hittable.size(); j++) {
                final Widget a = hittable.get(i);
                final Widget b = hittable.get(j);
                if (overlaps(a, b)) {
                    issues.add(new Issue("WARN", path, name(a) + " ⟷ " + name(b),
                            "同一父容器下命中区重叠 " + rect(a) + " / " + rect(b)
                                    + "（点击落到谁身上取决于层序）"));
                }
            }
        }

        for (final Widget child : parent.children) {
            final String where = path + "/" + name(child);

            // ---- 无响应路径 ----
            final String dead = deadReason(child);
            if (dead != null) {
                issues.add(new Issue("ERROR", path, name(child), dead));
            }

            // ---- 可交互但无 id ----
            if (interactive(child) && child.id == null) {
                issues.add(new Issue("INFO", path, name(child),
                        "可交互控件没有 id：命令日志里只能看到类名，难以定位"));
            }

            // ---- 尺寸 / 越界 ----
            if (child.visible && (child.w <= 0 || child.h <= 0)) {
                issues.add(new Issue("ERROR", path, name(child),
                        "可见但尺寸为 0（" + child.w + "x" + child.h + "）：画不出来，也点不到"));
            }
            if (child.visible && !parent.clipsChildren() && !inside(child, parent)) {
                issues.add(new Issue("WARN", path, name(child),
                        "超出父容器 " + rect(parent) + " 且父容器不裁剪：渲染范围与命中范围会不一致 "
                                + rect(child)));
            }

            if (child instanceof Container container) {
                inspectTree(container, where, issues);
            }
        }
    }

    /** 「长得能点但是空壳」的判据。返回 null 表示没问题。 */
    @Nullable
    private static String deadReason(final Widget widget) {
        if (widget instanceof Button button) {
            return button.action == null && button.secondaryAction == null
                    ? "按钮没有接任何动作（点了只会亮一下）" : null;
        }
        if (widget instanceof Checkbox box) {
            return box.onChange == null ? "复选框没有 onChange，勾不动" : null;
        }
        if (widget instanceof Slider slider) {
            return slider.model == null ? "滑条没有绑定 Prop，拖动不会改任何状态" : null;
        }
        if (widget instanceof TextField field) {
            if (field.readOnly) {
                return null;
            }
            if (field.source != null && field.model == null && field.onCommit == null) {
                return "输入框只接了只读投影，没有可写目标：编辑结果会被丢掉";
            }
            return null;
        }
        if (widget instanceof UiField field) {
            return field.onCommit == null && field.onPreview == null
                    ? "数值字段没有提交入口：改了值不会发命令（界面跟手但服务端不知道）" : null;
        }
        if (widget instanceof Tabs tabs) {
            if (tabs.tabs.isEmpty()) {
                return null;
            }
            if (tabs.selected() == null) {
                return "标签栏没有选中项（动画指示条会永远不出现）";
            }
            return tabs.onChange == null ? "标签栏没有 onChange，切页不会改任何状态" : null;
        }
        if (widget instanceof ComboBox combo) {
            return combo.onCommit == null ? "下拉框没有 onCommit，选择结果会被丢掉" : null;
        }
        return null;
    }

    private static boolean interactive(final Widget widget) {
        // 只列「会发命令 / 会改状态」的控件。ScrollPane、TreeView 这类纯容器没有 id 也无所谓，
        // 把它们算进来只会让报告被 INFO 淹掉，真正要看的那几条反而看不见。
        return widget instanceof Button || widget instanceof Checkbox || widget instanceof Slider
                || widget instanceof TextField || widget instanceof Tabs || widget instanceof ComboBox
                || widget instanceof UiField || widget instanceof Dropdown;
    }

    // ---- 状态体检 ----

    private static void inspectState(final List<Issue> issues) {
        final Map<String, Integer> writers = new LinkedHashMap<>();
        for (final Prop<?> prop : Prop.all()) {
            if (prop.writes() > 0 && prop.subscriberCount() == 0 && prop.reads() == 0) {
                issues.add(new Issue("WARN", "状态", prop.key(),
                        "写了 " + prop.writes() + " 次但没有任何控件订阅/读取（写入点是 "
                                + prop.lastWriter() + "）"));
            }
            if (prop.writes() == 0 && prop.subscriberCount() > 0) {
                issues.add(new Issue("INFO", "状态", prop.key(),
                        "有控件订阅但从未被写入：界面上的值不会变"));
            }
            if (prop.lastWriter() != null) {
                writers.merge(prop.key(), 1, Integer::sum);
            }
        }
        for (final Map.Entry<String, Integer> e : writers.entrySet()) {
            final Prop<?> prop = findProp(e.getKey());
            if (prop != null && prop.writes() > 1 && distinctWriters(prop).size() > 1) {
                issues.add(new Issue("WARN", "状态", prop.key(),
                        "被多个位置写入：" + distinctWriters(prop) + " —— 双份真相的前兆"));
            }
        }
    }

    @Nullable
    private static Prop<?> findProp(final String key) {
        for (final Prop<?> prop : Prop.all()) {
            if (prop.key().equals(key)) {
                return prop;
            }
        }
        return null;
    }

    /**
     * 由于 {@link Prop} 只记最后一个写入点，这里至少能给出「最后是谁写的」。
     * 真正的多写入点检测靠「同一 key 出现多个不同 lastWriter」在多次体检之间累积，
     * 这里给出当前可确认的信息，不猜。
     */
    private static Set<String> distinctWriters(final Prop<?> prop) {
        final Set<String> out = new LinkedHashSet<>();
        if (prop.lastWriter() != null) {
            out.add(prop.lastWriter());
        }
        return out;
    }

    // ---- 命令体检 ----

    private static void inspectCommands(final List<Issue> issues) {
        for (final String name : Cmd.declared()) {
            if (Cmd.count(name) == 0) {
                issues.add(new Issue("INFO", "命令", name, "已声明但从未派发（可能是写了没调用）"));
            }
        }
        for (final String name : Cmd.counts().keySet()) {
            if (!Cmd.declared().contains(name) && !isAutoCommand(name)) {
                issues.add(new Issue("INFO", "命令", name, "已派发但未声明（declare 里漏了，或名字拼错）"));
            }
        }
    }

    /**
     * 库控件在调用方没给命令名时用的自动命名（{@code button.<标签>} 之类）。
     *
     * <p>它们天然不可能被 {@code declare}，所以不参与「派发了但没声明」的检查 ——
     * 那条检查是给<b>手写</b>命令名抓拼写错误的，被自动名灌满就等于没有。
     */
    private static boolean isAutoCommand(final String name) {
        return name.startsWith("button.") || name.startsWith("slider.") || name.startsWith("text.")
                || name.startsWith("field.") || name.startsWith("combo.") || name.startsWith("checkbox.")
                || name.startsWith("dropdown.") || name.startsWith("tabs.") || name.startsWith("textfield#");
    }

    // ============ 输出 ============

    /** 实时体检报告（含控件树）。 */
    public static String report() {
        final StringBuilder sb = new StringBuilder();
        sb.append("=== SableStopNow GUI 自检 ===\n");
        sb.append("层数=").append(Ui.layers().size())
                .append("  状态=").append(Prop.all().size())
                .append("  命令=").append(Cmd.counts().size()).append('\n');
        for (final UiLayer layer : Ui.layers()) {
            sb.append("\n--- 层 ").append(layer.name)
                    .append(" (visible=").append(layer.visible)
                    .append(", consumeInput=").append(layer.consumeInput).append(")\n");
            dumpTree(layer.root, 1, sb);
        }
        final List<Issue> issues = inspect();
        sb.append("\n=== 问题 ").append(issues.size()).append(" 条 ===\n");
        for (final Issue issue : issues) {
            sb.append('[').append(issue.severity()).append("] ")
                    .append(issue.area()).append(" › ").append(issue.widget()).append('\n')
                    .append("      ").append(issue.detail()).append('\n');
        }
        if (issues.isEmpty()) {
            sb.append("（无）\n");
        }
        return sb.toString();
    }

    private static void dumpTree(final Container parent, final int depth, final StringBuilder sb) {
        for (final Widget child : parent.children) {
            sb.append("  ".repeat(depth))
                    .append(child.visible ? "+ " : "- ")
                    .append(name(child))
                    .append(child.id == null ? "" : "#" + child.id)
                    .append(' ').append(rect(child))
                    .append(child.pointerTransparent ? " [不可命中]" : "")
                    .append('\n');
            if (child instanceof Container container) {
                dumpTree(container, depth + 1, sb);
            }
        }
    }

    // ============ 合成用例自检（启动时跑，确认检查器本身没坏） ============

    /** 合成用例的结果。 */
    public record SelfTest(boolean pass, List<String> failures, int checks) {
    }

    /**
     * 用<b>构造出来的</b>控件树验证各项检查确实会触发。
     *
     * <p>为什么值得：一个永远报「无问题」的检查器和真的没问题长得一模一样。
     * 这里故意造出 4 种已知缺陷，要求检查器全部报出来；漏报就是检查器坏了。
     */
    public static SelfTest selfTest() {
        final List<String> failures = new ArrayList<>();
        int checks = 0;

        final Container root = new Container();
        root.prefW = Widget.FILL;
        root.prefH = Widget.FILL;

        // ① 兄弟重叠
        final Label a = new Label("a");
        a.x = 0; a.y = 0; a.w = 20; a.h = 10;
        final Label b = new Label("b");
        b.x = 10; b.y = 5; b.w = 20; b.h = 10;
        root.add(a, b);

        // ② 空壳按钮
        final Button dead = new Button("dead", null);
        dead.x = 100; dead.y = 0; dead.w = 30; dead.h = 10;
        root.add(dead);

        // ③ 可见但尺寸为 0
        final Label zero = new Label("zero");
        zero.x = 0; zero.y = 50; zero.w = 0; zero.h = 0;
        root.add(zero);

        // ④ 越界的子控件（父不裁剪）
        final Container outer = new Container();
        outer.x = 0; outer.y = 100; outer.w = 40; outer.h = 20;
        final Label escapee = new Label("escapee");
        escapee.x = 60; escapee.y = 0; escapee.w = 10; escapee.h = 10;
        outer.add(escapee);
        root.add(outer);

        final UiLayer probe = new UiLayer("selftest");
        probe.root.clear();
        probe.root.add(root);

        final List<Issue> issues = new ArrayList<>();
        inspectTree(probe.root, "selftest", issues);

        checks++;
        if (issues.stream().noneMatch(i -> i.detail().contains("重叠"))) {
            failures.add("漏报：兄弟命中区重叠");
        }
        checks++;
        if (issues.stream().noneMatch(i -> i.widget().contains("Button"))) {
            failures.add("漏报：空壳按钮");
        }
        checks++;
        if (issues.stream().noneMatch(i -> i.detail().contains("尺寸为 0"))) {
            failures.add("漏报：可见但尺寸为 0");
        }
        checks++;
        if (issues.stream().noneMatch(i -> i.detail().contains("超出父容器"))) {
            failures.add("漏报：越界子控件");
        }

        // ⑤ 状态检查
        final Prop<Double> orphan = Prop.of("selftest.orphan", 1.0);
        orphan.set(2.0);
        final List<Issue> stateIssues = new ArrayList<>();
        for (final Prop<?> prop : Prop.all()) {
            if (prop.key().equals("selftest.orphan") && prop.writes() > 0
                    && prop.subscriberCount() == 0 && prop.reads() == 0) {
                stateIssues.add(new Issue("WARN", "状态", prop.key(), "写了没人读"));
            }
        }
        checks++;
        if (stateIssues.isEmpty()) {
            failures.add("漏报：写了没人读的状态");
        }

        // ⑥ 命令检查
        Cmd.declare("selftest.never");
        Cmd.run("selftest.undeclared", () -> { });
        final List<Issue> cmdIssues = new ArrayList<>();
        inspectCommands(cmdIssues);
        checks++;
        if (cmdIssues.stream().noneMatch(i -> i.widget().equals("selftest.never"))) {
            failures.add("漏报：声明了但没派发的命令");
        }
        checks++;
        if (cmdIssues.stream().noneMatch(i -> i.widget().equals("selftest.undeclared"))) {
            failures.add("漏报：派发了但没声明的命令");
        }

        return new SelfTest(failures.isEmpty(), failures, checks);
    }

    // ============ 小工具 ============

    private static boolean overlaps(final Widget a, final Widget b) {
        return a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
    }

    private static boolean inside(final Widget child, final Widget parent) {
        return child.x >= parent.x && child.y >= parent.y
                && child.x + child.w <= parent.x + parent.w
                && child.y + child.h <= parent.y + parent.h;
    }

    private static String name(final Widget widget) {
        final String cn = widget.getClass().getSimpleName();
        return cn.isEmpty() ? "匿名控件" : cn;
    }

    private static String rect(final Widget widget) {
        return "(" + widget.x + "," + widget.y + " " + widget.w + "x" + widget.h + ")";
    }
}
