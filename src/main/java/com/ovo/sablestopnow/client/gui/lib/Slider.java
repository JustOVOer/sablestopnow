package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 数值滑条。
 *
 * <h2>状态在哪儿</h2>
 * 值<b>不在控件里</b>，在 {@link #model}（{@link Prop}）里。控件每帧读它、拖动时写它，
 * 于是同一个 {@code model} 绑在输入框上时，两边天然同步 ——
 * 「滑条和输入框联动」不再是需要人去维护的同步逻辑，而是同一个真相的两个投影。
 *
 * <p>写入走「先改本地状态、再发命令」：拖动过程中界面立即跟手（乐观更新），
 * 松手才把命令发出去（{@link #onCommit}），不会一帧一个网络包。服务端回声写回同一个
 * Prop 时值相等则不会二次广播，所以不存在来回打架。
 */
public class Slider extends Widget {

    /** 唯一真相。 */
    public Prop<Double> model;
    public double min;
    public double max;
    public double step;
    /** 拖动结束/滚轮后发出命令（这里是「通知」，不是直接改状态）。 */
    @Nullable
    public Consumer<Double> onCommit;
    /**
     * 每次<b>本地</b>改值都通知一次（拖动过程中也发）。
     *
     * <p>用途是「我刚刚动过它」这件事本身：服务端回声会周期性地把权威值拉回来，
     * 若不知道玩家正在拖，那个拉取会把滑条从手底下拽走。
     */
    @Nullable
    public Consumer<Double> onPreview;
    public Function<Double, String> formatter = v -> String.format("%.2f", v);

    /** 是否显示数值文本。 */
    public boolean showValue = true;
    public int valueW = 26;
    /** 归属命令名（自检/日志用）。 */
    @Nullable
    public String command;

    private boolean dragging;
    private double dragStartValue;
    public final Anim hover = Anim.of(0.0).speed(20.0);
    public final Anim press = Anim.of(0.0).speed(26.0);

    public Slider(final Prop<Double> model, final double min, final double max, final double step) {
        this.model = model;
        this.min = min;
        this.max = max;
        this.step = step;
        this.prefW = AUTO;
        this.prefH = 12;
    }

    public Slider commit(final Consumer<Double> listener) {
        this.onCommit = listener;
        return this;
    }

    public Slider format(final Function<Double, String> fmt) {
        this.formatter = fmt;
        return this;
    }

    public Slider command(final String name) {
        this.command = name;
        return this;
    }

    /** 当前值（只读投影）。 */
    public double value() {
        return model == null ? min : model.get();
    }

    /** 是否正在被拖动（输入框据此决定要不要覆盖显示）。 */
    public boolean dragging() {
        return dragging;
    }

    @Override
    public void measure(final UiContext ctx) {
        if (prefW == AUTO) {
            w = 90;
        }
        h = Math.max(minH, prefH >= 0 ? prefH : 12);
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        hover.to(ctx.isHovered(this) && enabled ? 1.0 : 0.0).tick(ctx.animations, dt);
        press.to(dragging ? 1.0 : 0.0).tick(ctx.animations, dt);
    }

    private int trackW() {
        return Math.max(6, w - (showValue ? valueW + 3 : 0));
    }

    private double snap(final double raw) {
        double v = raw;
        if (step > 0.0) {
            v = Math.round(v / step) * step;
        }
        return Math.max(min, Math.min(max, v));
    }

    private void applyFromMouse(final double mx) {
        final double t = Math.max(0.0, Math.min(1.0, (mx - x) / trackW()));
        setLocal(snap(min + t * (max - min)));
    }

    private void setLocal(final double v) {
        if (model != null) {
            model.set(v);
        }
        if (onPreview != null) {
            onPreview.accept(v);
        }
    }

    /** 提交（松手/滚轮）：把命令发出去。 */
    private void commit() {
        if (onCommit != null) {
            final double v = value();
            Cmd.run(command == null ? "slider.commit" : command, () -> onCommit.accept(v));
        }
    }

    @Override
    public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
        if (!enabled || button != 0) {
            return true;
        }
        dragging = true;
        dragStartValue = value();
        applyFromMouse(mx);
        return true;
    }

    @Override
    public void mouseDrag(final UiContext ctx, final double mx, final double my, final int button,
                          final double dx, final double dy) {
        if (dragging) {
            applyFromMouse(mx);
        }
    }

    @Override
    public void mouseUp(final UiContext ctx, final double mx, final double my, final int button) {
        if (dragging) {
            dragging = false;
            if (Math.abs(value() - dragStartValue) > 1.0e-9) {
                commit();
            }
        }
    }

    @Override
    public boolean mouseScroll(final UiContext ctx, final double mx, final double my, final double delta) {
        if (!enabled) {
            return false;
        }
        final double s = step > 0 ? step : Math.max(0.01, (max - min) / 50.0);
        setLocal(snap(value() + Math.signum(delta) * s));
        commit();
        return true;
    }

    /** 键盘微调（左栏里用方向键），命中时由父容器转发。 */
    public void nudge(final double dir) {
        final double s = step > 0 ? step : Math.max(0.01, (max - min) / 50.0);
        setLocal(snap(value() + Math.signum(dir) * s));
        commit();
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final UiTheme th = ctx.theme;
        final int tw = trackW();
        final int ty = y + h / 2 - 2;
        UiDraw.rect(g, x, ty, tw, 4, 0xF01A2070);
        UiDraw.rectOutline(g, x, ty, tw, 4, th.borderSoft, 1);
        final double v = value();
        final double t = max > min ? (v - min) / (max - min) : 0.0;
        final float fill = (float) Math.max(0.0, Math.min(1.0, t));
        final int fillW = Math.round(tw * fill);
        final int barColor = dragging ? th.accentHover
                : (hover.value() > 0.5 ? th.accentHover : th.accent);
        UiDraw.rect(g, x, ty, fillW, 4, barColor);
        // 滑块：抗锯齿的圆角小方，拖动中略微放大（工程界面的手感）
        final double knobX = x + fillW;
        final double knobW = dragging ? 4.0 : 3.0;
        UiVector.roundRect(g, (float) (knobX - knobW / 2.0), y + 1.5f, (float) knobW, h - 3.0f,
                1.0f, th.text);
        if (showValue) {
            UiDraw.textRight(g, ctx.font, formatter.apply(v), x + w, textY(ctx, y, h),
                    dragging ? th.text : th.textDim, th.fontScale, false);
        }
    }
}
