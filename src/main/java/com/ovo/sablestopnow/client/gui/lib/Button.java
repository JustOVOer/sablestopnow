package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 按钮：文字 / 图标 / 两者兼有。
 *
 * <h2>点击反馈</h2>
 * 按下瞬间有一道浅蓝从<b>左到右刷过去</b>，字体在被刷过的部分同步转成深色；
 * 松开后约 0.2 秒淡出。全部由 {@link #sweepClock} 一个时间量推出来
 * （颜色、字色、边缘高光都是它的投影），所以不会出现「底色刷到一半、字色还停在上一帧」。
 *
 * <p>时间用秒而不是指数收敛：扫过的速度是<b>匀速</b>的，反馈才有「刷」的手感，
 * 而 {@link Anim} 的指数收敛在起点快、终点慢，看着像渐变而不是扫过。
 */
public class Button extends Widget {

    /** 扫过一次的时长（秒）。 */
    private static final double SWEEP_SECONDS = 0.16;
    /** 松开后淡出的时长（秒）。 */
    private static final double FADE_SECONDS = 0.20;

    public Supplier<Component> label;
    @Nullable
    public ResourceLocation icon;
    public int iconSize = 10;
    @Nullable
    public Runnable action;
    /** 右键回调（可选）。 */
    @Nullable
    public Runnable secondaryAction;
    /** 归属命令名（自检/日志用）。 */
    @Nullable
    public String command;

    public int baseColor = 0;
    public int hoverColor = 0;
    public int pressColor = 0;
    public int textColor = 0;
    public int borderColor = 0;
    /** 选中态（切换按钮）。 */
    public boolean toggled;
    public int toggledColor = 0;

    public final Anim hover = Anim.of(0.0).speed(20.0);

    /**
     * 点击扫光的时钟（秒）。
     *
     * <p>{@code <0} = 空闲；{@code [0, SWEEP_SECONDS]} = 正在从左到右扫；
     * {@code (SWEEP_SECONDS, SWEEP+FADE]} = 已经铺满、正在淡出。
     */
    private double sweepClock = -1.0;
    private boolean held;

    /** 图标与文字之间的间距。 */
    public int gap = 3;
    public Layouts.Dock iconSide = Layouts.Dock.LEFT;

    public Button(final Component label, final Runnable action) {
        this.label = () -> label;
        this.action = action;
        this.prefH = 12;
        this.prefW = AUTO;
    }

    public Button(final String translationKey, final Runnable action) {
        this(Component.translatable(translationKey), action);
    }

    public Button(final Supplier<Component> label, final Runnable action) {
        this.label = label;
        this.action = action;
        this.prefH = 12;
        this.prefW = AUTO;
    }

    public Button icon(@Nullable final ResourceLocation texture) {
        this.icon = texture;
        return this;
    }

    /** 命令名：让「点了没反应」在日志里能对上号。 */
    public Button command(final String name) {
        this.command = name;
        Cmd.declare(name);
        return this;
    }

    /**
     * 短标签：屏幕太窄、完整标签放不下时改用（见 {@link Layouts#hboxFit(int, int)}）。
     *
     * <p>本项目标签是「中文 | English」双语的，小窗口下整排按钮必然溢出；短标签通常只留中文
     * （英文语言文件里则只留英文），必要信息仍由 tooltip 兜住。
     */
    @Nullable
    public Component compactLabel;
    /** 当前是否处于紧凑模式（由布局器设置）。 */
    public boolean compact;

    public Button compact(@Nullable final Component value) {
        this.compactLabel = value;
        return this;
    }

    public Button compact(final String translationKey) {
        return compact(Component.translatable(translationKey));
    }

    @Override
    public void setCompact(final boolean value) {
        this.compact = value;
    }

    public Button tooltipKey(final String key) {
        this.tooltipText = Component.translatable(key);
        return this;
    }

    @Nullable
    private Component tooltipText;

    @Override
    public Component tooltip() {
        return tooltipText;
    }

    public Button colors(final int base, final int hover, final int text) {
        this.baseColor = base;
        this.hoverColor = hover;
        this.textColor = text;
        return this;
    }

    public String raw() {
        if (label == null) {
            return "";
        }
        if (compact && compactLabel != null) {
            return compactLabel.getString();
        }
        return label.get().getString();
    }

    // ============ 扫光 ============

    /** 0..1：已经刷过去的宽度比例。 */
    public float sweepProgress() {
        if (sweepClock < 0.0) {
            return 0.0f;
        }
        if (sweepClock <= SWEEP_SECONDS) {
            return (float) Math.min(1.0, sweepClock / SWEEP_SECONDS);
        }
        return 1.0f;
    }

    /** 0..1：整体不透明度（扫的时候是 1，松开后淡出）。 */
    public float sweepAlpha() {
        if (sweepClock < 0.0) {
            return 0.0f;
        }
        if (sweepClock <= SWEEP_SECONDS) {
            return 1.0f;
        }
        return (float) Math.max(0.0, 1.0 - (sweepClock - SWEEP_SECONDS) / FADE_SECONDS);
    }

    /** 正在扫/正在淡出。 */
    public boolean sweeping() {
        return sweepClock >= 0.0;
    }

    /** 触发一次扫光（HUD 里「刚刚生效」的提示也复用它）。 */
    public void pulse() {
        sweepClock = 0.0;
    }

    @Override
    public void measure(final UiContext ctx) {
        int width = 0;
        if (icon != null) {
            width += iconSize;
        }
        if (!raw().isEmpty()) {
            width += (icon != null ? gap : 0) + ctx.textW(raw());
        }
        w = Math.max(minW, width + ctx.theme.padX * 2 + 2);
        h = Math.max(minH, prefH >= 0 ? prefH : ctx.lineH() + ctx.theme.padY * 2);
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        hover.to(ctx.isHovered(this) && enabled ? 1.0 : 0.0).tick(ctx.animations, dt);
        if (sweepClock >= 0.0) {
            sweepClock += ctx.animations ? dt : SWEEP_SECONDS + FADE_SECONDS + 1.0;
            if (sweepClock > SWEEP_SECONDS + FADE_SECONDS) {
                sweepClock = -1.0;
            }
        }
    }

    @Override
    public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
        if (!enabled) {
            return true;
        }
        if (button == 0) {
            held = true;
            sweepClock = 0.0;
        } else if (button == 1 && secondaryAction != null) {
            Cmd.run((command == null ? "button" : command) + ".secondary", secondaryAction);
        }
        return true;
    }

    @Override
    public void mouseUp(final UiContext ctx, final double mx, final double my, final int button) {
        final boolean wasPressed = held;
        held = false;
        if (!enabled || button != 0) {
            return;
        }
        if (sweepClock >= 0.0 && sweepClock < SWEEP_SECONDS) {
            // 松手时若还没刷完，让它先刷完再淡出（否则会看到「刷到一半就没了」）
            sweepClock = SWEEP_SECONDS;
        }
        final boolean inside = hit(mx, my);
        if (wasPressed && inside && action != null) {
            Cmd.run(command == null ? ("button." + raw()) : command, action);
        }
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final UiTheme th = ctx.theme;
        int bg = baseColor == 0 ? th.panelLight : baseColor;
        if (toggled) {
            bg = toggledColor == 0 ? th.accent : toggledColor;
        }
        if (hover.value() > 0.001 && !sweeping()) {
            bg = Anim.lerpColor(bg, hoverColor == 0 ? th.accentHover : hoverColor, hover.f());
        }
        UiDraw.panel(g, x, y, w, h, bg, borderColor == 0 ? th.border : borderColor);

        // ---- 从左到右的浅蓝扫光 ----
        final float progress = sweepProgress();
        final float alpha = sweepAlpha();
        if (progress > 0.0f && alpha > 0.01f) {
            final int swept = Math.round(w * progress);
            UiDraw.rect(g, x, y, swept, h, UiDraw.fade(th.sweep, alpha));
            // 前缘一道更亮的细边：扫过的动作才看得出「刷」的方向
            if (progress < 1.0f && swept > 0) {
                UiVector.line(g, x + swept - 0.5, y + 0.5, x + swept - 0.5, y + h - 0.5,
                        1.5f, UiDraw.fade(0xFFFFFFFF, alpha * 0.75f));
            }
            // 扫光期间覆盖边框，避免浅蓝底 + 深蓝框显得脏
            UiDraw.rectOutline(g, x, y, w, h, UiDraw.fade(th.sweepEdge, alpha), 1);
        }

        final String text = raw();
        int contentW = (icon != null ? iconSize : 0) + (!text.isEmpty() && icon != null ? gap : 0)
                + ctx.textW(text);
        int cx = x + (w - contentW) / 2;
        final int ty = textY(ctx, y, h);
        final int normalText = !enabled ? th.textDisabled : (textColor == 0 ? th.text : textColor);
        if (icon != null) {
            UiDraw.icon(g, icon, cx, y + (h - iconSize) / 2, iconSize, 0xFFFFFFFF);
            cx += iconSize + (text.isEmpty() ? 0 : gap);
        }
        if (!text.isEmpty()) {
            final String shown = UiDraw.ellipsize(ctx.font, text, w - ctx.theme.padX * 2, th.fontScale);
            UiDraw.text(g, ctx.font, shown, cx, ty, normalText, th.fontScale, false);
            // 被刷过的那一段：把字重画成深色（用裁剪切出「左侧已刷」的区域）。
            // 同一次绘制里算出来，所以底色和字色永远在同一条分界线上。
            if (progress > 0.0f && alpha > 0.01f) {
                final int swept = Math.round(w * progress);
                UiDraw.scissor(g, x, y, swept, h);
                UiDraw.text(g, ctx.font, shown, cx, ty,
                        UiDraw.fade(th.sweepText, alpha), th.fontScale, false);
                UiDraw.unscissor(g);
            }
        }
    }
}
