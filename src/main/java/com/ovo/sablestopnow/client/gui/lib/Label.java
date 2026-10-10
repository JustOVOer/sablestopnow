package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;
import java.util.function.Supplier;

/**
 * 文本标签。支持静态或动态（{@link Supplier}）内容。
 *
 * <h2>文字放不下时怎么办（P3 / docs/gui-统一.md）</h2>
 * 硬规则：**文字永不裁切** ✗ —— 放不下时按 {@link Overflow} 处理，优先级固定
 * <b>WRAP → ELLIPSIZE → CLIP</b>：
 * <ul>
 *   <li>{@link Overflow#WRAP} 换行（首选 ✓）：{@link #prefW} 是**确定正数**时，
 *       {@link #measure} 会按字宽拆行并把高度设为「行数 × 行高」✓ ⇒ 父控件据此排布 ✓；</li>
 *   <li>{@link Overflow#ELLIPSIZE} 省略号（次选 ✓，**默认** —— 与历史行为一致 ✓ 零回归 ✓）；</li>
 *   <li>{@link Overflow#CLIP} 硬裁（最后手段 ✗）。</li>
 * </ul>
 *
 * <p><b>已知限制（如实标注 ✗）：</b>{@code prefW = FILL}（宽度由父决定）的标签，换行只能在
 * {@link #render} 里按最终的 {@code w} 做 ✓，而**高度仍按一行**计 ✗ ⇒ 多出来的行会被父控件的
 * 裁剪区切掉 ✗。需要"行随文字变高"的地方，请给 Label 一个明确的 {@code prefW} ✓
 * （或放进会自己量高的容器：{@link SectionPane} 之类 ✓）。这一条留给后续继续收口 ✓。
 */
public class Label extends Widget {

    public enum Align {
        LEFT, CENTER, RIGHT
    }

    /** 放不下时的处理方式 ✓（默认与历史行为一致 ⇒ 零回归 ✓）。 */
    public enum Overflow {
        /** 换行（首选 ✓）。 */
        WRAP,
        /** 省略号（次选 ✓ 默认 ✓）。 */
        ELLIPSIZE,
        /** 硬裁（最后手段 ✗）。 */
        CLIP
    }

    public Supplier<Component> text;
    /** 覆盖颜色（0 = 用主题正文色）。 */
    public int color;
    public boolean shadow;
    public Align align = Align.LEFT;
    /**
     * 是否按列宽裁剪（默认裁剪）。
     *
     * <p>⚠ P3 起请用 {@link #overflow} ✓ —— 这个布尔只能表达"省略号 / 不管"✗，表达不了"换行"✓。
     * 保留只为兼容既有调用点 ✓：{@code clipText == false} 等价于 {@link Overflow#CLIP} ✓；
     * {@code clipText == true}（默认）等价于 {@link Overflow#ELLIPSIZE} ✓。
     */
    @Deprecated
    public boolean clipText = true;
    /** 放不下时的处理方式 ✓（P3 ✓ 默认 {@link Overflow#ELLIPSIZE} ✓）。 */
    public Overflow overflow = Overflow.ELLIPSIZE;
    /**
     * 覆盖字号缩放（0 = 用主题的 {@link UiTheme#fontScale}）。
     *
     * <p>界面上需要比正文大得多的字（开局的大标题），而主题缩放是给所有控件统一用的，
     * 不能为了一个标题把整屏都放大，所以留这个「只影响自己」的口子。
     */
    public float scale;
    /** 额外透明度（0..1）；出现/消失动画由它乘到颜色上。 */
    public float alpha = 1.0f;

    /**
     * 最近一次渲染时，这段文字**实际**是怎么放下的 ✓。
     *
     * <p>为什么由 {@link Label} 自己记 ✗✓：只有它知道"自动短标签/自动缩字号"有没有救回来 ✓。
     * 校验器（{@code UiLint} 的活树遍历 ✓）据此**全覆盖**地报"这里文字没放下"，不需要重算一遍 ✗。
     */
    public enum Fit {
        /** 原文放得下 ✓。 */
        FIT,
        /** 用了 {@code <key>.short} 短标签 ✓。 */
        SHORT,
        /** 自动缩了字号 ✓。 */
        SHRUNK,
        /** 换行放下了 ✓。 */
        WRAPPED,
        /** 只能省略号 —— **没放下** ✗（校验器要报的就是这一种 ✓）。 */
        ELLIPSIZED,
        /** 声明了 CLIP 且放不下 ⇒ 硬裁 ✗（同样要报 ✓）。 */
        CLIPPED
    }

    private Fit lastFit = Fit.FIT;

    /** 最近一次渲染的文字放置结果 ✓（见 {@link Fit} ✓ 供全覆盖校验用 ✓）。 */
    public Fit lastFit() {
        return this.lastFit;
    }
    /**
     * 自动缩字号的下限 ✓ —— 取库设置 {@link UiSettings#textShrinkMin} ✓
     *（默认 0.80 ✓ 可由配置调整 ✓；不再是写死的常量 ✗）。
     */
    private static float autoShrinkMin() {
        return Math.max(0.5f, Math.min(1.0f, UiSettings.textShrinkMin));
    }

    /** 这段文本在当前缩放下放得下吗 ✓（库的判断只有这一处 ✓）。 */
    private boolean fits(final UiContext ctx, final String s, final int available, final float scale) {
        // ⚠ 空文字**永远算放得下** ✓：否则"框宽 0 + 空串"会被判成"没放下"✗
        //   （实测：SketchHud 的吸附提示在未吸附时就是空串 ✓ 报告里那条 0px ✗ 正是它 ✓）。
        if (s == null || s.isEmpty()) {
            return true;
        }
        return available > 0 && ctx.font.width(s) * scale <= available + 0.5f;
    }

    /**
     * 若这条文本是<em>可翻译</em>且存在 {@code <key>.short} ✓ ⇒ 返回短标签 ✓；否则 {@code null} ✓。
     *
     * <p>这样"窄了就换简称"成为**库的行为** ✓ —— 写界面的人不需要知道 ✓（统一化 ✓）。
     * 判定存在性不依赖额外 API ✓：未解析的翻译键会原样返回键名 ✓ 一比就知道 ✓。
     */
    private String shortVariant(final UiContext ctx) {
        final Component component = text.get();
        // ⚠ 1.21.1 里没有 TranslatableComponent ✗ —— 翻译键在 TranslatableContents 上 ✓
        //   （我第一版按旧版类名写，编译失败过一次 ✓ 这里按实际 API 走 ✓）
        if (!(component.getContents()
                instanceof net.minecraft.network.chat.contents.TranslatableContents translatable)) {
            return null;
        }
        final String shortKey = translatable.getKey() + ".short";
        final String value = Component.translatable(shortKey).getString();
        if (value == null || value.isEmpty() || value.equals(shortKey)) {
            return null; // 键不存在 ✓（未解析的键会原样返回 ✓）
        }
        return value;
    }
    /** 上一次测量算出的换行结果 ✓（{@link #measure} 与 {@link #render} 共用同一次拆分 ✓）。 */
    private List<FormattedCharSequence> lines;

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

    /** 声明放不下时的处理方式 ✓（P3 ✓）。 */
    public Label overflow(final Overflow value) {
        this.overflow = value;
        return this;
    }

    public String raw() {
        return text.get().getString();
    }

    private float scaleOf(final UiContext ctx) {
        return scale > 0.0f ? scale : ctx.theme.fontScale;
    }

    /** 兼容旧的 {@code clipText} 开关 ✓：它只在 overflow 仍是默认值时才起作用 ✓。 */
    private Overflow effectiveOverflow() {
        if (!clipText && overflow == Overflow.ELLIPSIZE) {
            return Overflow.CLIP;
        }
        return overflow;
    }

    @Override
    public void measure(final UiContext ctx) {
        final float s = scaleOf(ctx);
        w = Math.round(ctx.font.width(raw()) * s);
        h = Math.max(1, Math.round(ctx.theme.baseLine * s));
        lines = null;
        // 只有"父给了确定宽度"时才可能在此拆分（FILL/AUTO 要等布局后才有可用宽度 ✗ ⇒ 见类注释的限制 ✓）
        if (effectiveOverflow() == Overflow.WRAP && prefW > 0) {
            lines = ctx.font.split(text.get(), Math.max(1, Math.round(prefW / s)));
            w = prefW;
            h = Math.max(1, lines.size() * Math.round(ctx.theme.baseLine * s));
        }
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final int c = UiDraw.fade(color == 0 ? ctx.theme.text : color, alpha);
        final float sc = scaleOf(ctx);
        final int lineH = Math.max(1, Math.round(ctx.theme.baseLine * sc));

        if (effectiveOverflow() == Overflow.WRAP) {
            this.lastFit = Fit.WRAPPED;
            // 宽度由父决定时测量阶段拿不到可用宽度 ✗ ⇒ 渲染期按最终的 w 再拆一次 ✓
            if (lines == null) {
                lines = ctx.font.split(text.get(), Math.max(1, Math.round(w / sc)));
            }
            int ly = y;
            for (final FormattedCharSequence line : lines) {
                if (ly + lineH > y + h && ly > y) {
                    break; // 只画放得下的行 ✓（至少第一行完整 ✓ 绝不半个字 ✗）
                }
                drawLine(g, ctx, line, ly, c, sc);
                ly += lineH;
            }
            return;
        }

        // ---- 库级自动回退（统一化 ✓ 调用点无需任何配合 ✓）------------------------------
        // 硬规则：**不许把有用信息裁成"…"** ✗。顺序固定：
        //   ① 原文放得下 ⇒ 原样 ✓
        //   ② 放不下 ⇒ 该 lang 键若有 <key>.short ⇒ 用短标签 ✓（项目既有约定 ✓
        //      TranslatableComponent 自带 getKey() ⇒ 调用点零改动 ✓）
        //   ③ 还不够 ⇒ 自动缩字号（下限 autoShrinkMin() ✓）⇒ 连 lang 条目都不需要 ✓
        //   ④ 仍不够 ⇒ WRAP / ELLIPSIZE（由 overflow 决定 ✓）
        final String s = raw();
        String shown = s;
        float drawScale = sc;
        this.lastFit = Fit.FIT;
        if (effectiveOverflow() != Overflow.CLIP && !fits(ctx, s, w, sc)) {
            final String shortText = shortVariant(ctx);
            if (shortText != null && fits(ctx, shortText, w, sc)) {
                shown = shortText;
                this.lastFit = Fit.SHORT;
            } else {
                final String candidate = shortText != null ? shortText : s;
                final float need = ctx.font.width(candidate) <= 0
                        ? sc
                        : w / (float) ctx.font.width(candidate);
                final float target = Math.max(autoShrinkMin(), need);
                if (target < sc && fits(ctx, candidate, w, target)) {
                    shown = candidate;
                    drawScale = target;
                    this.lastFit = Fit.SHRUNK;
                } else if (target < sc) {
                    shown = candidate;
                    drawScale = Math.max(autoShrinkMin(), Math.min(sc, sc * autoShrinkMin() / sc));
                } else {
                    this.lastFit = Fit.ELLIPSIZED;
                    shown = UiDraw.ellipsize(ctx.font, s, w, sc);
                }
            }
        } else if (effectiveOverflow() == Overflow.CLIP) {
            shown = s;
            if (!fits(ctx, s, w, sc)) {
                this.lastFit = Fit.CLIPPED;
            }
        }
// 文字在行内竖直居中：字号是覆盖值时不能再按主题行高算（会偏上）
        final int lineH2 = Math.max(1, Math.round(ctx.theme.baseLine * drawScale));
        final int ty = y + (h - lineH2) / 2;
        switch (align) {
            case CENTER -> UiDraw.textCentered(g, ctx.font, shown, x + w / 2, ty, c, drawScale, shadow);
            case RIGHT -> UiDraw.textRight(g, ctx.font, shown, x + w, ty, c, drawScale, shadow);
            default -> UiDraw.text(g, ctx.font, shown, x, ty, c, drawScale, shadow);
        }
    }

    /** 画一行已拆好的文本（对齐语义与单行路径完全一致 ✓）。 */
    private void drawLine(final GuiGraphics g, final UiContext ctx, final FormattedCharSequence line,
                          final int lineY, final int color, final float scale) {
        switch (align) {
            case CENTER -> UiDraw.textCentered(g, ctx.font, line, x + w / 2, lineY, color, scale, shadow);
            case RIGHT -> UiDraw.textRight(g, ctx.font, line, x + w, lineY, color, scale, shadow);
            default -> UiDraw.text(g, ctx.font, line, x, lineY, color, scale, shadow);
        }
    }
}