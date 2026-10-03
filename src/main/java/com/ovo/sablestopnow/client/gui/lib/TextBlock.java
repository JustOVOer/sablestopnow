package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 自动换行的文本块。
 *
 * <p>为什么 {@link Label} 不够：Label 只按列宽裁剪（放不下就省略号）。HUD 底部的按键提示与右侧
 * 描述面板必须真的折行 —— 一句话几十个字符，截断后玩家只看得到前半句。
 *
 * <p>折行要按「缩放后的像素宽度」算（库里的字号是 0.75 倍），这件事和字号缩放一样属于库的职责：
 * 让每个界面自己拿 {@code font.width} 除以缩放，迟早会有人忘，然后文字就会溢出面板。
 *
 * <p>换行结果带缓存：同一段文字每帧重算会持续分配，而 HUD 是每帧都画的。
 */
public class TextBlock extends Widget {

    public Supplier<Component> text;
    /** 覆盖颜色（0 = 主题正文色）。 */
    public int color;
    /** 覆盖字号（0 = 主题的 {@link UiTheme#fontScale}）。 */
    public float scale;
    public boolean shadow;
    /** 额外透明度（0..1）：出现/消失动画由它乘到颜色上。 */
    public float alpha = 1.0f;
    /** 行距（像素，叠加在行高之上）。 */
    public int lineGap = 1;

    private final List<String> lines = new ArrayList<>();
    private String cacheText = "\u0000";
    private int cacheWidth = -1;
    private float cacheScale = -1.0f;

    public TextBlock(final Component text, final int maxWidth) {
        this.text = () -> text;
        this.prefW = maxWidth;
        this.prefH = AUTO;
    }

    public TextBlock(final String translationKey, final int maxWidth) {
        this(Component.translatable(translationKey), maxWidth);
    }

    public TextBlock(final Supplier<Component> text, final int maxWidth) {
        this.text = () -> text.get() == null ? Component.empty() : text.get();
        this.prefW = maxWidth;
        this.prefH = AUTO;
    }

    public TextBlock color(final int argb) {
        this.color = argb;
        return this;
    }

    public TextBlock shadow(final boolean value) {
        this.shadow = value;
        return this;
    }

    public TextBlock lineGap(final int value) {
        this.lineGap = value;
        return this;
    }

    private float scaleOf(final UiContext ctx) {
        return scale > 0.0f ? scale : ctx.theme.fontScale;
    }

    /** 折行后的每一行（已本地化）。 */
    public List<String> lines(final UiContext ctx) {
        final String raw = text.get().getString();
        final float s = scaleOf(ctx);
        final int max = prefW == AUTO || prefW == FILL ? Math.max(1, w) : prefW;
        if (raw.equals(cacheText) && max == cacheWidth && Math.abs(s - cacheScale) < 1.0e-4f) {
            return lines;
        }
        cacheText = raw;
        cacheWidth = max;
        cacheScale = s;
        lines.clear();
        if (raw.isEmpty()) {
            return lines;
        }
        // font.split 按「未缩放」的像素宽度切行，所以要把库里的缩放折算回去。
        // 走 StringSplitter 而不是 Font.split：后者返回 FormattedCharSequence（取不出字符串），
        // 而这里缓存的是纯文本行。
        final net.minecraft.network.chat.FormattedText asText = Component.literal(raw);
        for (final net.minecraft.network.chat.FormattedText line
                : ctx.font.getSplitter().splitLines(asText, Math.max(1, Math.round(max / s)),
                        net.minecraft.network.chat.Style.EMPTY)) {
            lines.add(line.getString());
        }
        return lines;
    }

    /** 一行占的高度。 */
    public int lineH(final UiContext ctx) {
        return Math.max(7, Math.round(ctx.theme.baseLine * scaleOf(ctx))) + lineGap;
    }

    @Override
    public void measure(final UiContext ctx) {
        final int count = lines(ctx).size();
        h = Math.max(minH, count == 0 ? 0 : count * lineH(ctx) - lineGap);
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final int c = UiDraw.fade(color == 0 ? ctx.theme.text : color, alpha);
        if ((c >>> 24) == 0) {
            // 完全透明时连折行都不用算（滑出动画里每帧都会走到这里）
            return;
        }
        final float s = scaleOf(ctx);
        final int step = lineH(ctx);
        int ty = y;
        for (final String line : lines(ctx)) {
            UiDraw.text(g, ctx.font, line, x, ty, c, s, shadow);
            ty += step;
        }
    }
}
