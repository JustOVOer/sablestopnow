package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;

/** 分隔线（水平或垂直）。 */
public class Separator extends Widget {

    public boolean vertical;
    public int color;

    public Separator() {
        this(false);
    }

    public Separator(final boolean vertical) {
        this.vertical = vertical;
        this.color = 0;
        if (vertical) {
            this.prefW = 1;
            this.prefH = FILL;
        } else {
            this.prefW = FILL;
            this.prefH = 1;
        }
    }

    public Separator color(final int argb) {
        this.color = argb;
        return this;
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        UiDraw.rect(g, x, y, Math.max(1, w), Math.max(1, h), color == 0 ? ctx.theme.borderSoft : color);
    }
}
