package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 复选框（带标签），勾选过程有缩放动画。
 *
 * <p>勾选状态同样不在控件里：{@link #model} 是唯一真相，控件只读它、点击时发命令改它。
 * 需要「从别处算出来」的只读勾选态（例如「所有子项都选中」）用 {@link Prop#derived} 派生一个，
 * 不要塞一份自己的布尔字段 —— 那又会变成第二份真相。
 */
public class Checkbox extends Widget {

    /** 唯一真相；null 表示纯显示。 */
    @Nullable
    public Prop<Boolean> model;
    /** 只读投影（未绑定时使用，兼容旧写法）。 */
    @Nullable
    public Supplier<Boolean> state;
    /** 点击时发出的命令（通常是「把这层意图告诉上层」）。 */
    @Nullable
    public Consumer<Boolean> onChange;
    @Nullable
    public Supplier<Component> label;
    /** 归属命令名。 */
    @Nullable
    public String command;
    public int box = 9;
    public int gap = 3;

    public final Anim check = Anim.of(0.0).speed(18.0);
    public final Anim hover = Anim.of(0.0).speed(20.0);

    @Nullable
    private Component tooltipText;

    /** 绑定到一份布尔状态。 */
    public Checkbox(final Prop<Boolean> model, final Supplier<Component> label) {
        this.model = model;
        this.label = label;
        this.command = model.key();
        Cmd.declare(model.key());
        this.prefW = AUTO;
        this.prefH = AUTO;
    }

    public Checkbox(final Prop<Boolean> model, final String labelKey) {
        this(model, () -> Component.translatable(labelKey));
    }

    /** 只读显示（不可勾）。 */
    public Checkbox(final Supplier<Boolean> state, final Supplier<Component> label) {
        this.state = state;
        this.label = label;
        this.prefW = AUTO;
        this.prefH = AUTO;
    }

    /** 兼容：外部提供一个只读投影 + 一个变更通知。 */
    public Checkbox(final Supplier<Boolean> state, @Nullable final Consumer<Boolean> onChange,
                    final Supplier<Component> label) {
        this.state = state;
        this.onChange = onChange;
        this.label = label;
        this.prefW = AUTO;
        this.prefH = AUTO;
    }

    public Checkbox(final Supplier<Boolean> state, @Nullable final Consumer<Boolean> onChange,
                    final String labelKey) {
        this(state, onChange, () -> Component.translatable(labelKey));
    }

    public Checkbox command(final String name) {
        this.command = name;
        Cmd.declare(name);
        return this;
    }

    public Checkbox tooltip(final String key) {
        this.tooltipText = Component.translatable(key);
        return this;
    }

    @Override
    public Component tooltip() {
        return tooltipText;
    }

    /** 当前是否勾选（只读投影）。 */
    public boolean value() {
        if (model != null) {
            return Boolean.TRUE.equals(model.get());
        }
        return state != null && Boolean.TRUE.equals(state.get());
    }

    /** 是否可勾（没有变更入口时是只读的）。 */
    public boolean interactive() {
        return model != null || onChange != null;
    }

    @Override
    public void measure(final UiContext ctx) {
        final int textW = label == null ? 0 : ctx.textW(label.get().getString());
        w = box + (textW > 0 ? gap + textW : 0);
        h = Math.max(box, ctx.lineH());
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        check.to(value() ? 1.0 : 0.0).tick(ctx.animations, dt);
        hover.to(ctx.isHovered(this) && enabled ? 1.0 : 0.0).tick(ctx.animations, dt);
    }

    @Override
    public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
        if (enabled && button == 0 && interactive()) {
            final boolean next = !value();
            Cmd.run(command == null ? "checkbox.toggle" : command, () -> {
                if (model != null) {
                    model.set(next);
                }
                if (onChange != null) {
                    onChange.accept(next);
                }
            });
        }
        return true;
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final UiTheme th = ctx.theme;
        final int by = y + (h - box) / 2;
        int bg = 0xF01A2070;
        if (hover.value() > 0.001) {
            bg = Anim.lerpColor(bg, th.panelLight, hover.f());
        }
        UiVector.roundRect(g, x, by, box, box, 1.5f, bg);
        UiVector.roundRectOutline(g, x, by, box, box, 1.5f, 1.0f, th.border);
        if (check.value() > 0.01) {
            final int inner = box - 4;
            final double k = check.value();
            final double side = Math.max(1.0, inner * k);
            final double cx = x + box / 2.0 - side / 2.0;
            final double cy = by + box / 2.0 - side / 2.0;
            UiVector.roundRect(g, (float) cx, (float) cy, (float) side, (float) side, 1.0f, th.ok);
        }
        if (label != null) {
            final String text = label.get().getString();
            final int textColor = !enabled ? th.textDisabled
                    : (interactive() ? th.text : th.textDim);
            UiDraw.text(g, ctx.font, UiDraw.ellipsize(ctx.font, text, w - box - gap, th.fontScale),
                    x + box + gap, textY(ctx, y, h), textColor, th.fontScale, false);
        }
    }
}
