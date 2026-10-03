package com.ovo.sablestopnow.client.hud;

import com.ovo.sablestopnow.client.gui.lib.UiContext;
import com.ovo.sablestopnow.client.gui.lib.UiDraw;
import com.ovo.sablestopnow.client.gui.lib.Widget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 「动态多行文本」控件：给 HUD 用的一小块文本列表（内容每帧由 {@link Supplier} 现算）。
 *
 * <h2>为什么不直接用一堆 {@code Label}</h2>
 * 目标内容是<b>每帧都可能变、行数也会变</b>的（模式 / 穿透层数 / 已选数量 / 框选角点…）。
 * 用保留模式的 Label 去表达，就得每帧增删控件、还要处理控件复用与动画，
 * 反而比「一个控件负责一批行」更复杂 —— 而对 HUD 这种没有交互的纯展示来说，
 * 每帧重新算一遍文本本来就是最自然的做法。所以这里保留控件身份（可以定位、可以受库统一管理、可以用 UiDraw 画），
 * 但内容按帧重算。
 *
 * <h2>仍然只走库</h2>
 * 绘制一律经过 {@link UiDraw}（文本按 {@code theme.fontScale} 缩放），
 * 因此行高比原版矮、配色与其它界面一致，也不会在界面代码里出现裸 {@code GuiGraphics} 绘制。
 */
public class HudLinesWidget extends Widget {

    /** 一行：文本 + 颜色。 */
    public record Line(Component text, int color) {
    }

    private final Supplier<List<Line>> source;
    private final List<Line> lines = new ArrayList<>();

    public boolean background = true;
    public int backgroundArgb = 0x66000000;
    public int lineGap = 1;

    public HudLinesWidget(final Supplier<List<Line>> source) {
        this.source = source;
        this.prefW = AUTO;
        this.prefH = AUTO;
    }

    /** 当前行数（0 = 没有内容，控件会自己隐藏）。 */
    public int lineCount() {
        return this.lines.size();
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        this.lines.clear();
        final List<Line> fresh = this.source.get();
        if (fresh != null) {
            this.lines.addAll(fresh);
        }
        // 没内容就不占地方（HUD 的行是动态的，空着留一块半透明底板会很怪）
        this.visible = !this.lines.isEmpty();
    }

    @Override
    public void measure(final UiContext ctx) {
        int width = 0;
        for (final Line line : this.lines) {
            width = Math.max(width, ctx.textW(line.text().getString()));
        }
        this.w = width + 6;
        this.h = this.lines.size() * (ctx.lineH() + this.lineGap);
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        if (this.lines.isEmpty()) {
            return;
        }
        if (this.background) {
            UiDraw.rect(g, this.x, this.y, this.w, this.h, this.backgroundArgb);
        }
        int y = this.y + 1;
        for (final Line line : this.lines) {
            UiDraw.text(g, ctx.font, line.text().getString(), this.x + 3, y, line.color(),
                    ctx.theme.fontScale, false);
            y += ctx.lineH() + this.lineGap;
        }
    }
}
