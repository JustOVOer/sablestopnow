package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 滚动容器：内容超出高度时可滚动（滚轮 / 拖拽滑块），带平滑动画。
 *
 * <p>实现方式是把子控件的 {@code innerY()} 整体上移，因此布局器（vbox 等）算出来的位置
 * 天然带滚动偏移，命中判定也自动跟着走。
 */
public class ScrollPane extends Container {

    public double scroll;
    public double target;
    /** 滚轮一格滚多少像素。 */
    public double step = 18.0;
    public int barW = 3;
    /** 内容总高度（由布局算出）。 */
    public double contentHeight;
    public boolean smooth = true;
    /** 是否显示滚动条。 */
    public boolean showBar = true;

    private boolean draggingBar;
    private double dragOffset;

    public ScrollPane() {
        this.clip = true;
        this.prefW = FILL;
        this.prefH = FILL;
        this.layout = Layouts.vbox(0, 1);
    }

    @Override
    public int innerY() {
        return (int) Math.round(super.innerY() - scroll);
    }

    public double maxScroll() {
        return Math.max(0.0, contentHeight - (h - padTop - padBottom));
    }

    @Override
    public void arrange(final UiContext ctx) {
        super.arrange(ctx);
        double bottom = 0;
        for (final Widget child : children) {
            if (child.visible) {
                bottom = Math.max(bottom, child.y + child.h + scroll);
            }
        }
        contentHeight = Math.max(0, bottom + padBottom - (y + padTop));
        final double max = maxScroll();
        target = Math.max(0.0, Math.min(max, target));
        if (scroll > max) {
            scroll = max;
        }
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        super.update(ctx, dt);
        if (smooth) {
            final double k = 1.0 - Math.exp(-16.0 * Math.min(0.25, ctx.dt));
            scroll += (target - scroll) * (ctx.animations ? k : 1.0);
        } else {
            scroll = target;
        }
        final double max = maxScroll();
        if (scroll > max) {
            scroll = max;
            target = max;
        }
        if (scroll < 0) {
            scroll = 0;
            target = 0;
        }
    }

    @Override
    public boolean mouseScroll(final UiContext ctx, final double mx, final double my, final double delta) {
        if (maxScroll() <= 0.5) {
            return false;
        }
        target -= delta * step;
        target = Math.max(0.0, Math.min(maxScroll(), target));
        return true;
    }

    /** 直接把内容滚到底 / 顶。 */
    public void scrollTo(final double value) {
        target = Math.max(0.0, Math.min(maxScroll(), value));
    }

    public void scrollToTop() {
        scroll = 0;
        target = 0;
    }

    /** 让某个子控件进入可视区。 */
    public void ensureVisible(final Widget child) {
        if (child == null) {
            return;
        }
        final double top = child.y - (y + padTop) + scroll;
        final double bottom = top + child.h;
        final double view = h - padTop - padBottom;
        if (top < scroll) {
            scrollTo(top);
        } else if (bottom > scroll + view) {
            scrollTo(bottom - view);
        }
    }

    @Override
    public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
        if (barW > 0 && showBar && maxScroll() > 0.5 && mx >= x + w - barW - 1 && mx <= x + w) {
            draggingBar = true;
            final double track = h - padTop - padBottom;
            final double barH = Math.max(8.0, track * (track / contentHeight));
            final double barY = y + padTop + (track - barH) * (scroll / Math.max(1.0, maxScroll()));
            if (my >= barY && my <= barY + barH) {
                dragOffset = my - barY;
            } else {
                dragOffset = barH / 2.0;
            }
            return true;
        }
        return super.mouseDown(ctx, mx, my, button);
    }

    @Override
    public void mouseDrag(final UiContext ctx, final double mx, final double my, final int button,
                          final double dx, final double dy) {
        if (draggingBar) {
            final double track = h - padTop - padBottom;
            final double barH = Math.max(8.0, track * (track / contentHeight));
            final double rel = (my - dragOffset - (y + padTop)) / Math.max(1.0, track - barH);
            target = Math.max(0.0, Math.min(maxScroll(), rel * maxScroll()));
            scroll = target;
            return;
        }
        super.mouseDrag(ctx, mx, my, button, dx, dy);
    }

    @Override
    public void mouseUp(final UiContext ctx, final double mx, final double my, final int button) {
        draggingBar = false;
        super.mouseUp(ctx, mx, my, button);
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        if (bg != 0) {
            UiDraw.rect(g, x, y, w, h, bg);
        }
        if (outline != 0) {
            UiDraw.rectOutline(g, x, y, w, h, outline, 1);
        }
        renderChildren(ctx, g);
        if (showBar && maxScroll() > 0.5) {
            final double track = h - padTop - padBottom;
            final double barH = Math.max(8.0, track * (track / contentHeight));
            final double barY = y + padTop + (track - barH) * (scroll / Math.max(1.0, maxScroll()));
            UiDraw.rect(g, x + w - barW, y + padTop, barW, (int) track, 0x33000000);
            UiDraw.rect(g, x + w - barW, (int) Math.round(barY), barW, (int) Math.round(barH), ctx.theme.scrollbar);
        }
    }
}
