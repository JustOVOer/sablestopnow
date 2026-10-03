package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/** 普通面板：底色 + 边框 + 可选标题栏。 */
public class Panel extends Container {

    @Nullable
    public Component title;
    public int titleH = 14;
    public boolean drawTitle = true;

    public Panel() {
        this.prefW = FILL;
        this.prefH = FILL;
        this.bg = 0xE6141A5C;
        this.outline = 0;
    }

    public Panel title(final Component value) {
        this.title = value;
        return this;
    }

    public Panel title(final String translationKey) {
        this.title = Component.translatable(translationKey);
        return this;
    }

    public boolean hasTitle() {
        return drawTitle && title != null;
    }

    /**
     * 标题需要往右让开的宽度。
     *
     * <p>可折叠面板收起按钮画在面板内侧（{@link CollapsiblePanel}），右侧停靠时会压在标题上
     * —— 用户实测截图里右边栏标题显示成「状图 | Tree」（「树」被按钮盖住）。子类覆盖本方法避让。
     */
    protected int titleInsetX() {
        return 0;
    }

    @Override
    public int innerY() {
        return super.innerY() + (hasTitle() ? titleH : 0);
    }

    @Override
    public int innerH() {
        return Math.max(0, super.innerH() - (hasTitle() ? titleH : 0));
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final UiTheme th = ctx.theme;
        UiDraw.rect(g, x + 1, y + 2, w, h, 0x66000000);
        UiDraw.panel(g, x, y, w, h, bg == 0 ? th.panel : bg, outline == 0 ? th.border : outline);
        if (hasTitle()) {
            UiDraw.gradientV(g, x + 1, y + 1, w - 2, titleH - 1, th.header, 0xFF2A33A0);
            UiDraw.text(g, ctx.font, title.getString(), x + ctx.theme.padX + titleInsetX(),
                    textY(ctx, y, titleH), th.text, th.fontScale, false);
        }
        renderChildren(ctx, g);
    }
}
