package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.function.Supplier;

/** 文本标签。支持静态或动态（{@link Supplier}）内容。 */
public class Label extends Widget {

    public enum Align {
        LEFT, CENTER, RIGHT
    }

    public Supplier<Component> text;
    /** 覆盖颜色（0 = 用主题正文色）。 */
    public int color;
    public boolean shadow;
    public Align align = Align.LEFT;
    /** 是否按列宽裁剪（默认裁剪）。 */
    public boolean clipText = true;
    /**
     * 覆盖字号缩放（0 = 用主题的 {@link UiTheme#fontScale}）。
     *
     * <p>界面上需要比正文大得多的字（开局的大标题），而主题缩放是给所有控件统一用的，
     * 不能为了一个标题把整屏都放大，所以留这个「只影响自己」的口子。
     */
    public float scale;
    /** 额外透明度（0..1）；出现/消失动画由它乘到颜色上。 */
    public float alpha = 1.0f;

    public Label(final Component text) {
        this.text = () -> text;
        this.prefW = AUTO;
        this.prefH = AUTO;
    }

    public Label(final String translationKey) {
        this(Component.translatable(translationKey));
    }

    public Label(final Supplier<Component> text) {
        this.text = text;
        this.prefW = AUTO;
        this.prefH = AUTO;
    }

    public Label color(final int argb) {
        this.color = argb;
        return this;
    }

    public Label align(final Align value) {
        this.align = value;
        return this;
    }

    public Label shadow(final boolean value) {
        this.shadow = value;
        return this;
    }

    public Label scale(final float value) {
        this.scale = value;
        return this;
    }

    public String raw() {
        return text.get().getString();
    }

    private float scaleOf(final UiContext ctx) {
        return scale > 0.0f ? scale : ctx.theme.fontScale;
    }

    @Override
    public void measure(final UiContext ctx) {
        final float s = scaleOf(ctx);
        w = Math.round(ctx.font.width(raw()) * s);
        h = Math.max(1, Math.round(ctx.theme.baseLine * s));
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final String s = raw();
        final int c = UiDraw.fade(color == 0 ? ctx.theme.text : color, alpha);
        final float sc = scaleOf(ctx);
        final String shown = clipText ? UiDraw.ellipsize(ctx.font, s, w, sc) : s;
        // 文字在行内竖直居中：字号是覆盖值时不能再按主题行高算（会偏上）
        final int ty = y + (h - Math.round(ctx.theme.baseLine * sc)) / 2;
        switch (align) {
            case CENTER -> UiDraw.textCentered(g, ctx.font, shown, x + w / 2, ty, c, sc, shadow);
            case RIGHT -> UiDraw.textRight(g, ctx.font, shown, x + w, ty, c, sc, shadow);
            default -> UiDraw.text(g, ctx.font, shown, x, ty, c, sc, shadow);
        }
    }
}
