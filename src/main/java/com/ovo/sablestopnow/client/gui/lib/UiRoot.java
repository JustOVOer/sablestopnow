package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个界面层的控件树根：负责布局、逐帧推进、事件派发、焦点/拖拽/弹出层/提示气泡。
 */
public class UiRoot extends Container {

    /** 当前焦点控件（输入框等）。 */
    @Nullable
    public Widget focused;
    /** 鼠标下方的控件。 */
    @Nullable
    public Widget hovered;
    /** 按下时命中的控件（用于区分「点击」与「拖拽」）。 */
    @Nullable
    public Widget pressedWidget;
    /** 正在接收拖拽的控件（按下时捕获，松开前一直给它）。 */
    @Nullable
    public Widget dragTarget;

    /** 弹出层（下拉列表、右键菜单），永远画在最上面；后开的在上。 */
    public final List<Widget> popups = new ArrayList<>();
    /** 与 {@link #popups} 一一对应的所有者（用于「同一个控件再开一次就换掉旧的」）。 */
    private final List<Widget> popupOwners = new ArrayList<>();
    /** 栈顶弹出层的所有者（兼容旧字段）。 */
    @Nullable
    public Widget popupOwner;

    /** 全部控件都没命中时的兜底处理（返回 true 表示吞掉）。 */
    @Nullable
    public BackgroundHandler background;

    /** 全屏遮罩色（0 = 不遮）。 */
    public int dimColor;

    private double lastMouseX;
    private double lastMouseY;
    private int dragButton = -1;

    public interface BackgroundHandler {
        boolean handle(UiContext ctx, double mx, double my, int button, int action);
    }

    public UiRoot() {
        this.prefW = FILL;
        this.prefH = FILL;
    }

    // ============ 生命周期 ============

    public void resize(final int width, final int height) {
        this.x = 0;
        this.y = 0;
        this.w = width;
        this.h = height;
    }

    public void tick(final UiContext ctx, final double dt) {
        arrange(ctx);
        for (final Widget popup : popups) {
            if (popup instanceof Container container) {
                container.arrange(ctx);
            }
        }
        updateTree(ctx, dt);
        if (hovered != null && (!hovered.visible || !ancestorsVisible(hovered))) {
            hovered = null;
        }
        lintLayout();
    }

    /**
     * 布局之后立刻查一遍「可见但 0 像素」的控件。
     *
     * <p>这类错误的表现离根因很远（「滑动条拖不动」的根因是内部一行的 prefH 留了 AUTO），
     * 而查出来的代价是一次几微秒的树遍历 —— 每帧查一次比等玩家截图便宜得多。
     * 报过一次就不再重复（{@link UiLint#checkCollapsed}）。
     */
    private void lintLayout() {
        for (final Widget child : children) {
            lintLayout(child);
        }
    }

    private static void lintLayout(final Widget widget) {
        UiLint.checkCollapsed(widget);
        if (widget instanceof Container container) {
            for (final Widget child : container.children) {
                lintLayout(child);
            }
        }
    }

    private void updateTree(final UiContext ctx, final double dt) {
        for (final Widget child : children) {
            if (child.visible) {
                child.update(ctx, dt);
            }
        }
        for (final Widget popup : popups) {
            if (popup.visible) {
                popup.update(ctx, dt);
            }
        }
    }

    private static boolean ancestorsVisible(final Widget widget) {
        for (Widget p = widget.parent; p != null; p = p.parent) {
            if (!p.visible) {
                return false;
            }
        }
        return true;
    }

    // ============ 绘制 ============

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        if (dimColor != 0) {
            UiDraw.rect(g, 0, 0, ctx.screenW, ctx.screenH, dimColor);
        }
        super.render(ctx, g);
        for (final Widget popup : popups) {
            if (popup.visible) {
                popup.render(ctx, g);
            }
        }
        renderTooltip(ctx, g);
    }

    private void renderTooltip(final UiContext ctx, final GuiGraphics g) {
        final Widget target = hovered;
        if (target == null) {
            return;
        }
        final Component tip = target.tooltip();
        if (tip == null) {
            return;
        }
        final String text = tip.getString();
        if (text.isEmpty()) {
            return;
        }
        final int w = ctx.textW(text) + 6;
        final int h = ctx.lineH() + 4;
        int tx = (int) ctx.mouseX + 8;
        int ty = (int) ctx.mouseY + 8;
        tx = Math.max(0, Math.min(ctx.screenW - w, tx));
        ty = Math.max(0, Math.min(ctx.screenH - h, ty));
        UiDraw.rect(g, tx, ty, w, h, 0xF0121240);
        UiDraw.rectOutline(g, tx, ty, w, h, ctx.theme.border, 1);
        UiDraw.text(g, ctx.font, text, tx + 3, ty + 2, ctx.theme.text, ctx.theme.fontScale, false);
    }

    // ============ 弹出层 ============

    /**
     * 打开一个弹出层。
     *
     * <p>同一个所有者再开一次会<b>替换</b>它自己那个（下拉列表反复展开/收起时不会越堆越多），
     * 别人的弹出层保持不动（嵌套菜单才有可能出现两层）。
     */
    public void openPopup(final Widget popup, final Widget owner) {
        closePopup(owner);
        popup.parent = this;
        popups.add(popup);
        popupOwners.add(owner);
        popupOwner = owner;
    }

    /** 关掉栈顶的弹出层。 */
    public void closePopup() {
        if (popups.isEmpty()) {
            return;
        }
        popups.remove(popups.size() - 1).parent = null;
        popupOwners.remove(popupOwners.size() - 1);
        popupOwner = popupOwners.isEmpty() ? null : popupOwners.get(popupOwners.size() - 1);
    }

    /** 关掉属于某个所有者的弹出层（不存在时什么都不做）。 */
    public void closePopup(@Nullable final Widget owner) {
        for (int i = popups.size() - 1; i >= 0; i--) {
            if (popupOwners.get(i) == owner) {
                popups.remove(i).parent = null;
                popupOwners.remove(i);
            }
        }
        popupOwner = popupOwners.isEmpty() ? null : popupOwners.get(popupOwners.size() - 1);
    }

    /** 某个所有者的弹出层是否开着。 */
    public boolean hasPopup(@Nullable final Widget owner) {
        for (final Widget o : popupOwners) {
            if (o == owner) {
                return true;
            }
        }
        return false;
    }

    public boolean hasPopup() {
        return !popups.isEmpty();
    }

    /** 顶层弹出层的所有者列表（自检/调试用）。 */
    public List<Widget> popupOwners() {
        return new ArrayList<>(popupOwners);
    }

    // ============ 焦点 ============

    public void setFocus(@Nullable final Widget widget, final UiContext ctx) {
        if (focused == widget) {
            return;
        }
        final Widget old = focused;
        focused = widget;
        if (old != null) {
            old.onFocusLost(ctx);
        }
        if (widget != null) {
            widget.onFocusGained(ctx);
        }
    }

    public void clearFocus(final UiContext ctx) {
        setFocus(null, ctx);
    }

    // ============ 事件 ============

    public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
        lastMouseX = mx;
        lastMouseY = my;
        if (!popups.isEmpty()) {
            final Widget popup = popups.get(popups.size() - 1);
            final Widget hit = popup.hitTest(mx, my);
            if (hit != null) {
                if (hit.focusable()) {
                    setFocus(hit, ctx);
                }
                dragTarget = hit;
                dragButton = button;
                hit.mouseDown(ctx, mx, my, button);
                return true;
            }
            if (button == 0) {
                closePopup();
                return true;
            }
        }
        final Widget hit = hitTest(mx, my);
        // ⚠ 命中「根节点自己」不算按在控件上。以前这里照样把 pressedWidget/dragTarget 记成根节点，
        // 于是**每一层**界面都会记下一次按下；松开时从上往下派发，最上面那层拿着根节点就把
        // 释放事件吞掉了，真正按下按钮的那一层永远收不到 mouseUp ——
        // 表现就是用户实测的「所有按键点下去会变亮、但一点反应都没有」（Button 的 action 在
        // mouseUp 里触发）。所以：没按在控件上就什么都不记，让释放事件继续往下传。
        final Widget target = pressTarget(ctx, hit, mx, my, button);
        pressedWidget = target;
        dragButton = button;
        dragTarget = target;
        if (target == null) {
            if (focused != null && (hit == null || !insideFocused(mx, my))) {
                clearFocus(ctx);
            }
            if (hit != null && hit != this) {
                // 命中了本层的控件（比如某行里的标签），但没有任何人认领这次按下：
                // 仍然算本层吞掉它，别让点击穿到世界或下层界面 —— 这是「面板吸收点击」的既有行为。
                // 但**不记 dragTarget**：没认领就没有拖拽对象，松开时要让事件继续往下传
                // （记成控件才是之前「按钮收不到松开」的根源）。
                hovered = hit;
                return true;
            }
            final boolean handled = background != null && background.handle(ctx, mx, my, button, 0);
            if (handled) {
                // 背景自己处理了按下，那这次释放也归它，别漏给下面的层
                dragTarget = this;
                pressedWidget = this;
            }
            return handled;
        }
        if (target.focusable()) {
            setFocus(target, ctx);
        } else if (focused != null && !isDescendant(focused, target)) {
            clearFocus(ctx);
        }
        hovered = target;
        return true;
    }

    /**
     * 从命中控件往上冒泡，找到第一个「愿意处理这次按下」的控件。
     *
     * <p>为什么必须冒泡：行/分组这类控件自己是<b>容器</b>，点击落在它内部的标签上，
     * {@code hitTest} 返回的是那个标签（一个纯显示控件，什么都不处理），
     * 于是写在容器上的点击逻辑永远不会被调到 —— 树状图里「点一行选中它」、
     * 分组的点击折叠全都是这种结构，一起失灵（用户实测：树里的配合点不中）。
     * 与滚轮冒泡同一套规则：子控件先有机会，没人要才交给父容器。
     *
     * @return 认领这次按下的控件；没人认领返回 null
     */
    @Nullable
    private Widget pressTarget(final UiContext ctx, @Nullable final Widget hit,
                               final double mx, final double my, final int button) {
        if (hit == null || hit == this) {
            return null;
        }
        for (Widget widget = hit; widget != null && widget != this; widget = widget.parent) {
            if (widget.mouseDown(ctx, mx, my, button)) {
                return widget;
            }
        }
        return null;
    }

    public boolean mouseReleased(final UiContext ctx, final double mx, final double my, final int button) {
        lastMouseX = mx;
        lastMouseY = my;
        final Widget target = dragTarget;
        dragTarget = null;
        dragButton = -1;
        pressedWidget = null;
        if (target != null) {
            target.mouseUp(ctx, mx, my, button);
            return true;
        }
        return false;
    }

    public void mouseMoved(final UiContext ctx, final double mx, final double my) {
        final double dx = mx - lastMouseX;
        final double dy = my - lastMouseY;
        lastMouseX = mx;
        lastMouseY = my;
        if (dragTarget != null && (Math.abs(dx) > 0.0 || Math.abs(dy) > 0.0)) {
            dragTarget.mouseDrag(ctx, mx, my, dragButton, dx, dy);
            return;
        }
        final Widget hit = popups.isEmpty() ? hitTest(mx, my) : topPopupHit(mx, my);
        if (hit != hovered) {
            hovered = hit;
        }
        if (hit != null) {
            hit.mouseMove(ctx, mx, my);
        }
    }

    @Nullable
    private Widget topPopupHit(final double mx, final double my) {
        for (int i = popups.size() - 1; i >= 0; i--) {
            final Widget hit = popups.get(i).hitTest(mx, my);
            if (hit != null) {
                return hit;
            }
        }
        return hitTest(mx, my);
    }

    public boolean mouseScroll(final UiContext ctx, final double mx, final double my, final double delta) {
        final Widget hit = popups.isEmpty() ? hitTest(mx, my) : topPopupHit(mx, my);
        if (hit == null || hit == this) {
            return false;
        }
        // 从命中控件往上冒泡：标签本身不滚，但它所属的滚动容器要滚。
        // 必须止步于 this —— 层根节点的 parent 就是 UiRoot，若走到 UiRoot 会再次进入本方法、
        // 再次命中的还是同一个控件，形成无限递归（StackOverflowError）。
        for (Widget widget = hit; widget != null && widget != this; widget = widget.parent) {
            if (widget.mouseScroll(ctx, mx, my, delta)) {
                return true;
            }
        }
        return false;
    }

    public boolean keyPressed(final UiContext ctx, final int keyCode, final int scanCode, final int modifiers) {
        if (focused != null) {
            return focused.keyPressed(ctx, keyCode, scanCode, modifiers);
        }
        return false;
    }

    public boolean charTyped(final UiContext ctx, final char codePoint, final int modifiers) {
        if (focused != null) {
            return focused.charTyped(ctx, codePoint, modifiers);
        }
        return false;
    }

    private boolean insideFocused(final double mx, final double my) {
        return focused != null && focused.hit(mx, my);
    }

    private static boolean isDescendant(@Nullable final Widget candidate, final Widget ancestor) {
        for (Widget p = candidate; p != null; p = p.parent) {
            if (p == ancestor) {
                return true;
            }
        }
        return false;
    }
}
