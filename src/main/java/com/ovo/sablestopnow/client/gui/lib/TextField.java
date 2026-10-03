package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 单行文本输入框：光标、选区、剪贴板、横向滚动、提交/取消回调。
 *
 * <h2>文本存在哪儿</h2>
 * 和 {@link Slider} 同一条规矩：<b>控件不自持业务真值</b>。
 * <ul>
 *   <li>未绑定时用自己的一份 {@link Prop}（仍然可观察、可自检）——「这段文字」本身就是它的状态；</li>
 *   <li>绑定时读 {@link #model}；也可以用 {@link #source} 接一个<b>投影</b>
 *       （例如数值框显示的是「格式化后的 double」，那不是一段独立的字符串状态）；</li>
 *   <li>{@link #draft} 只在<b>编辑期间</b>存在，结束编辑就丢弃，显示回到投影 ——
 *       于是「滑条改了值、输入框立刻跟着变」是自动的，因为两者读同一个真相。</li>
 *   <li>{@link #onChanged} 是「通知」（每次输入），{@link #onCommit} 是「提交」
 *       （回车/失焦/命令）。</li>
 * </ul>
 */
public class TextField extends Widget {

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** 文本真值（绑定时）。 */
    @Nullable
    public Prop<String> model;
    /** 只读投影（优先级高于 {@link #model}）：用于「显示的是别的东西格式化来的文本」。 */
    @Nullable
    public Supplier<String> source;
    /** 提交通知（回车 / 失焦）；不负责改值 —— 改值由 {@link #model} 或 {@link #own} 完成。 */
    @Nullable
    public Consumer<String> onCommit;
    /** 每次输入的通知（不做状态变更时不要在这里写状态，用 {@link #commitValue}）。 */
    @Nullable
    public Consumer<String> onChanged;
    @Nullable
    public Runnable onCancel;
    @Nullable
    public Supplier<String> placeholder;
    /** 输入过滤（返回 false 则该次输入被拒绝）。 */
    @Nullable
    public Predicate<String> filter;
    /** 归属命令名（自检/日志用）。 */
    @Nullable
    public String command;

    /**
     * 自己的那份状态（未绑定外部真值时使用）。
     *
     * <p>用 {@link Prop#local} 而不是 {@link Prop#of}：这是控件的实现细节，不是业务状态。
     * 控件会随界面反复重建，登记进全局注册表就等于每开一次界面留一个永远没人再用的条目。
     */
    private final Prop<String> own = Prop.local("textfield#" + SEQ.incrementAndGet(), "");

    public int maxLength = 96;
    public int caret;
    /** 选区锚点；-1 = 无选区。 */
    public int anchor = -1;

    /** 编辑缓冲：只有 {@link #editing} 为真时才有意义。 */
    private String draft = "";
    private boolean editing;
    /** 进入编辑时的文本（Esc 还原用）。 */
    private String original = "";

    public final Anim focus = Anim.of(0.0).speed(18.0);
    public final Anim hover = Anim.of(0.0).speed(20.0);

    private long blinkStart = System.nanoTime();
    /** 只读展示模式（不可编辑，点击不进入编辑）。 */
    public boolean readOnly;
    /** 每次按键都提交（数值框用：边打字边预览）。 */
    public boolean commitOnType;
    /**
     * 进入编辑时全选。
     *
     * <p>数值框几乎总是「整段替换」而不是「在中间插一个字符」——
     * 不全选的话玩家点了力大小输入框，看到的就是「200」，必须先 Ctrl+A 或按几次退格才能改成别的值。
     * 这是 CAD 里的常规行为（双击尺寸即全选）。
     */
    public boolean selectAllOnEdit;

    public TextField() {
        this.prefW = FILL;
        this.prefH = 12;
    }

    public TextField(final String staticText) {
        this();
        own.set(staticText == null ? "" : staticText);
    }

    public static TextField of(final String staticText) {
        return new TextField(staticText);
    }

    // ============ 绑定 ============

    /** 绑定到一份字符串状态：读它、提交时写它。 */
    public TextField bind(final Prop<String> prop) {
        this.model = prop;
        this.command = prop.key();
        Cmd.declare(prop.key());
        return this;
    }

    /** 绑定到一个只读投影（显示用；要写就用 {@link #commit}）。 */
    public TextField project(final Supplier<String> projection) {
        this.source = projection;
        return this;
    }

    public TextField maxLength(final int value) {
        this.maxLength = value;
        return this;
    }

    public TextField placeholder(final String key) {
        this.placeholder = () -> net.minecraft.network.chat.Component.translatable(key).getString();
        return this;
    }

    public TextField commit(final Consumer<String> listener) {
        this.onCommit = listener;
        Cmd.declare("text.commit");
        return this;
    }

    public TextField changed(final Consumer<String> listener) {
        this.onChanged = listener;
        return this;
    }

    public TextField command(final String name) {
        this.command = name;
        Cmd.declare(name);
        return this;
    }

    public TextField numeric() {
        this.filter = s -> s.isEmpty() || s.matches("-?\\d*(\\.\\d*)?");
        return this;
    }

    public TextField integer() {
        this.filter = s -> s.isEmpty() || s.matches("-?\\d*");
        return this;
    }

    // ============ 文本投影 ============

    /** 真值（不含编辑缓冲）。 */
    public String canonical() {
        if (source != null) {
            final String s = source.get();
            return s == null ? "" : s;
        }
        if (model != null) {
            final String s = model.get();
            return s == null ? "" : s;
        }
        return own.get();
    }

    /** 当前应显示的文本：编辑中是缓冲，否则是真值投影。 */
    public String text() {
        return editing ? draft : canonical();
    }

    /** 编辑缓冲原文（调试/自检用）。 */
    public String draft() {
        return draft;
    }

    public boolean editing() {
        return editing;
    }

    /** 值来源（自检用）：返回 null 表示编辑结果无处可去。 */
    @Nullable
    public Object valueSink() {
        if (model != null) {
            return model;
        }
        if (source != null) {
            return source;
        }
        return own;
    }

    /** 外部设置文本：走命令写进真值（不做提交通知）。 */
    public void setText(final String value) {
        commitValue(value == null ? "" : value, false);
    }

    /** 结束编辑；{@code commit} 为真且内容有变化时提交。 */
    public void endEdit(final boolean commit) {
        if (!editing) {
            return;
        }
        final String committed = draft;
        editing = false;
        anchor = -1;
        if (commit && !committed.equals(canonical())) {
            commitValue(committed, true);
        }
    }

    /** 立即提交当前文本。 */
    public void commitNow() {
        commitValue(text(), true);
    }

    /** 走命令写值 + 发提交通知。 */
    private void commitValue(final String value, final boolean notify) {
        original = value;
        // ⚠ 编辑缓冲必须一起更新。
        // 少了这一步，「程序改文本」（下拉框选中候选、外部同步）在编辑期间是<b>看不到</b>的：
        // text() 仍然返回旧 draft，于是 ComboBox 每帧都判定「查询词变了」→ 重新弹出补全 ——
        // 用户实测反馈的「选完频道下拉菜单不消失」就是这么来的。
        draft = value;
        caret = Math.max(0, Math.min(caret, draft.length()));
        final Prop<String> target = model;
        if (target != null) {
            Cmd.run(command == null ? target.key() : command, () -> target.set(value));
        } else if (source == null) {
            Cmd.run(command == null ? own.key() : command, () -> own.set(value));
        }
        if (notify && onCommit != null) {
            Cmd.run((command == null ? "text" : command) + ".notify", () -> onCommit.accept(value));
        }
    }

    public void cancelEdit() {
        draft = original;
        caret = draft.length();
        anchor = -1;
        editing = false;
        if (onChanged != null) {
            onChanged.accept(draft);
        }
        if (onCancel != null) {
            onCancel.run();
        }
    }

    /** 是否处于选区状态（外部可用于判断 Esc 该关列表还是取消编辑）。 */
    public boolean hasSelection() {
        return anchor >= 0 && anchor != caret;
    }

    /**
     * 按键拦截：返回 true 表示这一次按键已被外部处理（如补全列表的上下键）。
     * 参数是「键码, 修饰键」。
     */
    @Nullable
    public java.util.function.BiPredicate<Integer, Integer> keyInterceptor;

    public void beginEdit() {
        if (editing || readOnly) {
            return;
        }
        draft = canonical();
        original = draft;
        caret = Math.min(caret, draft.length());
        editing = true;
        blinkStart = System.nanoTime();
    }

    // ============ 尺寸 ============

    @Override
    public void measure(final UiContext ctx) {
        if (prefW == AUTO) {
            w = Math.max(40, ctx.textW(text()) + 8);
        }
        if (prefH == AUTO) {
            h = ctx.lineH() + 5;
        }
    }

    @Override
    public boolean focusable() {
        return !readOnly && enabled;
    }

    @Override
    public void onFocusGained(final UiContext ctx) {
        beginEdit();
    }

    @Override
    public void onFocusLost(final UiContext ctx) {
        endEdit(true);
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        final boolean focusedNow = ctx.root != null && ctx.root.focused == this;
        focus.to(focusedNow ? 1.0 : 0.0).tick(ctx.animations, dt);
        hover.to(ctx.isHovered(this) && enabled ? 1.0 : 0.0).tick(ctx.animations, dt);
    }

    // ============ 输入 ============

    @Override
    public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
        if (readOnly || !enabled) {
            return true;
        }
        final boolean wasEditing = editing;
        beginEdit();
        if (selectAllOnEdit && !wasEditing) {
            anchor = 0;
            caret = draft.length();
        } else {
            caret = caretAt(ctx, mx);
            anchor = hasShift() ? (anchor < 0 ? 0 : anchor) : -1;
        }
        blinkStart = System.nanoTime();
        return true;
    }

    private static boolean hasShift() {
        return net.minecraft.client.gui.screens.Screen.hasShiftDown();
    }

    @Override
    public boolean keyPressed(final UiContext ctx, final int keyCode, final int scanCode, final int modifiers) {
        if (readOnly) {
            return false;
        }
        if (keyInterceptor != null && keyInterceptor.test(keyCode, modifiers)) {
            return true;
        }
        final boolean ctrl = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        final boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        switch (keyCode) {
            case GLFW.GLFW_KEY_BACKSPACE -> {
                if (deleteSelection()) {
                    return true;
                }
                if (caret > 0) {
                    draft = draft.substring(0, caret - 1) + draft.substring(caret);
                    caret--;
                    changed();
                }
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                if (deleteSelection()) {
                    return true;
                }
                if (caret < draft.length()) {
                    draft = draft.substring(0, caret) + draft.substring(caret + 1);
                    changed();
                }
                return true;
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (shift) {
                    if (anchor < 0) {
                        anchor = caret;
                    }
                } else {
                    anchor = -1;
                }
                caret = Math.max(0, caret - 1);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT -> {
                if (shift) {
                    if (anchor < 0) {
                        anchor = caret;
                    }
                } else {
                    anchor = -1;
                }
                caret = Math.min(draft.length(), caret + 1);
                return true;
            }
            case GLFW.GLFW_KEY_HOME -> {
                caret = 0;
                anchor = -1;
                return true;
            }
            case GLFW.GLFW_KEY_END -> {
                caret = draft.length();
                anchor = -1;
                return true;
            }
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                final String value = draft;
                editing = false;
                anchor = -1;
                commitValue(value, true);
                return true;
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                cancelEdit();
                return true;
            }
            case GLFW.GLFW_KEY_A -> {
                if (ctrl) {
                    anchor = 0;
                    caret = draft.length();
                    return true;
                }
                return false;
            }
            case GLFW.GLFW_KEY_C -> {
                if (ctrl) {
                    setClipboard(selected());
                    return true;
                }
                return false;
            }
            case GLFW.GLFW_KEY_X -> {
                if (ctrl) {
                    setClipboard(selected());
                    deleteSelection();
                    return true;
                }
                return false;
            }
            case GLFW.GLFW_KEY_V -> {
                if (ctrl) {
                    insert(clipboard());
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public boolean charTyped(final UiContext ctx, final char codePoint, final int modifiers) {
        if (readOnly || !enabled || codePoint < ' ') {
            return false;
        }
        insert(String.valueOf(codePoint));
        return true;
    }

    private void insert(final String s) {
        if (s == null || s.isEmpty()) {
            return;
        }
        if (!editing) {
            beginEdit();
        }
        deleteSelection();
        final String next = draft.substring(0, caret) + s + draft.substring(caret);
        if (next.length() > maxLength) {
            return;
        }
        if (filter != null && !filter.test(next)) {
            return;
        }
        draft = next;
        caret += s.length();
        changed();
    }

    private boolean deleteSelection() {
        if (anchor < 0 || anchor == caret) {
            return false;
        }
        final int from = Math.min(anchor, caret);
        final int to = Math.max(anchor, caret);
        draft = draft.substring(0, from) + draft.substring(to);
        caret = from;
        anchor = -1;
        changed();
        return true;
    }

    private String selected() {
        if (anchor < 0) {
            return "";
        }
        final int from = Math.min(anchor, caret);
        final int to = Math.max(anchor, caret);
        return draft.substring(from, to);
    }

    private void changed() {
        caret = Math.max(0, Math.min(draft.length(), caret));
        if (onChanged != null) {
            onChanged.accept(draft);
        }
        if (commitOnType) {
            commitValue(draft, false);
        }
    }

    private static String clipboard() {
        try {
            return Minecraft.getInstance().keyboardHandler.getClipboard();
        } catch (final Throwable t) {
            return "";
        }
    }

    private static void setClipboard(final String value) {
        try {
            Minecraft.getInstance().keyboardHandler.setClipboard(value == null ? "" : value);
        } catch (final Throwable ignored) {
            // 剪贴板不可用时忽略
        }
    }

    /** 鼠标位置对应的字符下标（粗略：按字符累加宽度）。 */
    private int caretAt(final UiContext ctx, final double mx) {
        final String t = text();
        final int textLeft = x + 3;
        int wAcc = 0;
        for (int i = 0; i < t.length(); i++) {
            final int cw = Math.round(ctx.font.width(String.valueOf(t.charAt(i))) * ctx.theme.fontScale);
            if (mx < textLeft + wAcc + cw / 2.0) {
                return i;
            }
            wAcc += cw;
        }
        return t.length();
    }

    // ============ 绘制 ============

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final UiTheme th = ctx.theme;
        final boolean focusedNow = ctx.root != null && ctx.root.focused == this;
        int bg = 0xF01A2070;
        if (hover.value() > 0.001 && !focusedNow) {
            bg = Anim.lerpColor(bg, th.panelLight, hover.f() * 0.7f);
        }
        UiDraw.panel(g, x, y, w, h, bg, focusedNow ? th.accentHover : th.borderSoft);

        final String shownText = text();
        final int textLeft = x + 3;
        final int textW = w - 6;
        final int ty = textY(ctx, y, h);
        final String shown = shownText.isEmpty() && placeholder != null && !focusedNow
                ? placeholder.get() : shownText;
        final int color = shownText.isEmpty() && placeholder != null && !focusedNow
                ? th.textDisabled : th.text;

        UiDraw.scissor(g, x + 1, y + 1, w - 2, h - 2);
        // 保证光标可见的横向偏移
        final int caretPx = Math.round(ctx.font.width(shownText.substring(0, Math.min(caret, shownText.length())))
                * th.fontScale);
        final int shift = Math.max(0, caretPx - textW + 2);
        // 选区高亮
        if (editing && anchor >= 0 && anchor != caret) {
            final int from = Math.min(anchor, caret);
            final int to = Math.max(anchor, caret);
            final int fx = textLeft + Math.round(ctx.font.width(shownText.substring(0, from)) * th.fontScale) - shift;
            final int tx = textLeft + Math.round(ctx.font.width(shownText.substring(0, to)) * th.fontScale) - shift;
            UiDraw.rect(g, fx, y + 2, tx - fx, h - 4, 0x884070E0);
        }
        UiDraw.text(g, ctx.font, shown, textLeft - shift, ty, color, th.fontScale, false);
        if (focusedNow) {
            final long elapsed = (System.nanoTime() - blinkStart) / 1_000_000L;
            if (elapsed % 1000L < 620L) {
                UiDraw.rect(g, textLeft - shift + caretPx, y + 2, 1, h - 4, th.text);
            }
        }
        UiDraw.unscissor(g);
    }
}
