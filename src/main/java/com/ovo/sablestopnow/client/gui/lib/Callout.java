package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 带左侧三角凸起的信息面板（说明「这块文字属于哪一行」）。
 *
 * <p>{@code tipW = 0} 时退化成普通面板，所以「瞄准结构信息」这类不指向任何行的面板也用它，
 * 免得界面上同时存在两种几乎一样的面板控件。
 *
 * <p>默认裁掉超出自己的孩子：描述文字的滑出/滑入就是在这个矩形里做的，不裁的话它会滑到
 * 左边的功能列表上，看起来像画错了。
 */
public class Callout extends Container {

    /** 左侧凸起宽度（0 = 普通矩形面板）。 */
    public int tipW = 5;
    /** 覆盖底色（0 = 主题面板色）。 */
    public int fill;
    /** 覆盖描边色（0 = 主题边框色）。 */
    public int edge;
    public float alpha = 1.0f;

    public Callout() {
        this.prefW = AUTO;
        this.prefH = AUTO;
        this.clip = true;
    }

    @Override
    public void measure(final UiContext ctx) {
        // 子控件是用绝对布局 + 外边距摆的，所以尺寸是它们的「包围盒」而不是高度之和：
        // 提示面板是靠每行累加的上边距排下来的，求和会把同一段距离算好几遍（7 行能算出 200 多像素高）。
        int contentW = 0;
        int contentH = 0;
        for (final Widget child : children) {
            contentW = Math.max(contentW, child.marginLeft + child.wantW(ctx) + child.marginRight);
            contentH = Math.max(contentH, child.marginTop + child.wantH(ctx) + child.marginBottom);
        }
        if (prefW == AUTO) {
            w = Math.max(minW, contentW + padLeft + padRight);
        }
        if (prefH == AUTO) {
            h = Math.max(minH, contentH + padTop + padBottom);
        }
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final UiTheme th = ctx.theme;
        UiDraw.callout(g, x, y, w, h, tipW,
                UiDraw.fade(fill == 0 ? th.panel : fill, alpha),
                UiDraw.fade(edge == 0 ? th.border : edge, alpha));
        renderChildren(ctx, g);
    }
}
