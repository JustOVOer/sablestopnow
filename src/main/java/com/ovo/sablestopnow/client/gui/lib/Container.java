package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 容器：一组子控件 + 一个布局器 + 可选的背景与裁剪。
 *
 * <p>布局每帧重算（控件数量很小，代价可以忽略），这样动画改尺寸时不需要手动失效。
 */
public class Container extends Widget {

    public final List<Widget> children = new ArrayList<>();
    /** 布局器；默认绝对定位（子控件自带坐标）。 */
    public Layouts.Layout layout = Layouts.absolute();

    public int padLeft;
    public int padTop;
    public int padRight;
    public int padBottom;

    /** 是否把子控件裁在自己的矩形内。 */
    public boolean clip;
    /** 是否画背景/边框（0 = 不画）。 */
    public int bg;
    public int outline;

    public Container() {
        this.prefW = FILL;
        this.prefH = FILL;
    }

    // ============ 结构 ============

    public Container add(final Widget... widgets) {
        for (final Widget widget : widgets) {
            if (widget == null) {
                continue;
            }
            widget.parent = this;
            children.add(widget);
        }
        return this;
    }

    public Container add(final int index, final Widget widget) {
        widget.parent = this;
        children.add(index, widget);
        return this;
    }

    public void remove(final Widget widget) {
        children.remove(widget);
        if (widget != null) {
            widget.parent = null;
        }
    }

    public void clear() {
        for (final Widget child : children) {
            child.parent = null;
        }
        children.clear();
    }

    public Container padding(final int all) {
        return padding(all, all, all, all);
    }

    public Container padding(final int l, final int t, final int r, final int b) {
        this.padLeft = l;
        this.padTop = t;
        this.padRight = r;
        this.padBottom = b;
        return this;
    }

    public Container layout(final Layouts.Layout value) {
        this.layout = value;
        return this;
    }

    public Container clip(final boolean value) {
        this.clip = value;
        return this;
    }

    public Container bg(final int argb) {
        this.bg = argb;
        return this;
    }

    public Container outline(final int argb) {
        this.outline = argb;
        return this;
    }

    // ============ 内容区 ============

    public int innerX() {
        return x + padLeft;
    }

    public int innerY() {
        return y + padTop;
    }

    public int innerW() {
        return Math.max(0, w - padLeft - padRight);
    }

    public int innerH() {
        return Math.max(0, h - padTop - padBottom);
    }

    // ============ 布局 ============

    /** 递归布局：先摆自己人，再让子容器摆它们的人。 */
    public void arrange(final UiContext ctx) {
        layout.arrange(this, ctx);
        for (final Widget child : children) {
            child.resetClip();
            child.applyClip(clipX, clipY, clipW, clipH);
            if (clip) {
                child.applyClip(x, y, w, h);
            }
            if (child instanceof Container container) {
                container.arrange(ctx);
            }
        }
    }

    // ============ 逐帧推进 ============

    @Override
    public void update(final UiContext ctx, final double dt) {
        for (final Widget child : children) {
            if (child.visible) {
                child.update(ctx, dt);
            }
        }
    }

    // ============ 绘制 ============

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        if (bg != 0) {
            UiDraw.rect(g, x, y, w, h, bg);
        }
        if (outline != 0) {
            UiDraw.rectOutline(g, x, y, w, h, outline, 1);
        }
        renderChildren(ctx, g);
    }

    protected void renderChildren(final UiContext ctx, final GuiGraphics g) {
        final boolean scissor = clip;
        if (scissor) {
            UiDraw.scissor(g, x, y, w, h);
        }
        for (final Widget child : children) {
            if (!child.visible) {
                continue;
            }
            child.render(ctx, g);
        }
        if (scissor) {
            UiDraw.unscissor(g);
        }
    }

    // ============ 命中 ============

    @Override
    public Widget hitTest(final double mx, final double my) {
        if (!hit(mx, my)) {
            return null;
        }
        for (int i = children.size() - 1; i >= 0; i--) {
            final Widget child = children.get(i);
            if (!child.visible) {
                continue;
            }
            final Widget found = child.hitTest(mx, my);
            if (found != null) {
                return found;
            }
        }
        return this;
    }

    @Override
    public void collectPopups(final List<Widget> out) {
        for (final Widget child : children) {
            if (child.visible) {
                child.collectPopups(out);
            }
        }
    }

    @Override
    public void forEachChild(final Consumer<Widget> visitor) {
        for (final Widget child : children) {
            visitor.accept(child);
            child.forEachChild(visitor);
        }
    }

    @Override
    public boolean clipsChildren() {
        return clip;
    }

    /** 所有（含间接）子控件。 */
    public List<Widget> descendants() {
        final List<Widget> out = new ArrayList<>();
        forEachChild(out::add);
        return out;
    }

    @Nullable
    public Widget find(final String widgetId) {
        for (final Widget child : descendants()) {
            if (widgetId.equals(child.id)) {
                return child;
            }
        }
        return null;
    }
}
