package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 右端斜切的标签（HUD 的模式横幅、标题条）。
 *
 * <p>容器而不是原语，因为「斜切」只是背景的形状：文字、将来可能的图标都是子控件，
 * 由库的布局器摆放。宽度会自己算上斜切多出来的那一块，否则右边的尖角会把文字挤出去。
 */
public class SlantTag extends Container {

    /** 斜切宽度（像素）。 */
    public int slant = 5;
    /** 覆盖主底色（0 = 主题面板色）。 */
    public int fill;
    /** 覆盖上下压边色（0 = 主题标题栏色）。 */
    public int line;
    /** 覆盖描边色（0 = 主题边框色）。 */
    public int edge;
    public float alpha = 1.0f;

    public SlantTag() {
        this.prefW = AUTO;
        this.prefH = AUTO;
    }

    @Override
    public void measure(final UiContext ctx) {
        // 与 Callout 同样的口径：子控件用绝对布局摆，尺寸取包围盒，不能把外边距累加
        int contentW = 0;
        int contentH = 0;
        for (final Widget child : children) {
            contentW = Math.max(contentW, child.marginLeft + child.wantW(ctx) + child.marginRight);
            contentH = Math.max(contentH, child.marginTop + child.wantH(ctx) + child.marginBottom);
        }
        if (prefW == AUTO) {
            w = Math.max(minW, contentW + padLeft + padRight + slant);
        }
        if (prefH == AUTO) {
            h = Math.max(minH, contentH + padTop + padBottom);
        }
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final UiTheme th = ctx.theme;
        UiDraw.slantRight(g, x, y, w - slant, h, slant,
                UiDraw.fade(fill == 0 ? th.panel : fill, alpha),
                UiDraw.fade(line == 0 ? th.header : line, alpha),
                UiDraw.fade(edge == 0 ? th.border : edge, alpha));
        renderChildren(ctx, g);
    }
}
