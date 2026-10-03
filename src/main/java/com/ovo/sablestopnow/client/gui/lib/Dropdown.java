package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 下拉选择框。
 *
 * <p>选中值放在 {@link #model} 里（不是控件字段）：换页重建控件时选项不会丢，
 * 也不会出现「下拉显示 A、业务逻辑以为是 B」。
 */
public class Dropdown<T> extends Widget {

    /** 一个选项。 */
    public static class Option<T> {
        public final T value;
        public final Component label;
        @Nullable
        public ResourceLocation icon;

        public Option(final T value, final Component label) {
            this.value = value;
            this.label = label;
        }

        public Option(final T value, final String labelKey) {
            this(value, Component.translatable(labelKey));
        }

        public Option<T> icon(@Nullable final ResourceLocation texture) {
            this.icon = texture;
            return this;
        }
    }

    public final List<Option<T>> options = new ArrayList<>();
    /** 唯一真相。 */
    public Prop<T> model;
    @Nullable
    public Consumer<T> onChange;
    /** 归属命令名。 */
    @Nullable
    public String command;

    public final Anim hover = Anim.of(0.0).speed(20.0);
    public final Anim openAnim = Anim.of(0.0).speed(16.0);

    public Dropdown(final int width, final Prop<T> model) {
        this.model = model;
        this.command = model.key();
        this.prefW = width;
        this.prefH = 12;
        Cmd.declare(model.key());
    }

    public Dropdown<T> option(final Option<T> option) {
        options.add(option);
        return this;
    }

    public Dropdown<T> option(final T value, final String labelKey) {
        return option(new Option<>(value, labelKey));
    }

    /** 直接改值（不触发 onChange：这是「外部设置」，不是「玩家选择」）。 */
    public Dropdown<T> select(final T newValue) {
        if (model != null) {
            Cmd.run(command == null ? "dropdown.select" : command, () -> model.set(newValue));
        }
        return this;
    }

    public Dropdown<T> onChange(final Consumer<T> listener) {
        this.onChange = listener;
        return this;
    }

    /** 当前值。 */
    public T value() {
        return model == null ? null : model.get();
    }

    @Nullable
    public Option<T> current() {
        final T v = value();
        for (final Option<T> option : options) {
            if (option.value == null ? v == null : option.value.equals(v)) {
                return option;
            }
        }
        return null;
    }

    @Override
    public void measure(final UiContext ctx) {
        if (prefW == AUTO) {
            int max = 40;
            for (final Option<T> option : options) {
                max = Math.max(max, ctx.textW(option.label.getString()) + 14);
            }
            w = max;
        }
        if (prefH == AUTO) {
            h = ctx.lineH() + 5;
        }
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        hover.to(ctx.isHovered(this) && enabled ? 1.0 : 0.0).tick(ctx.animations, dt);
        openAnim.to(Overlay.isOpen(ctx, this) ? 1.0 : 0.0).tick(ctx.animations, dt);
    }

    @Override
    public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
        if (!enabled) {
            return true;
        }
        if (button == 0) {
            togglePopup(ctx);
        }
        return true;
    }

    private void togglePopup(final UiContext ctx) {
        if (Overlay.isOpen(ctx, this)) {
            Overlay.close(ctx, this);
            return;
        }
        final MenuList list = new MenuList(Math.max(w, 70));
        final T currentValue = value();
        for (final Option<T> option : options) {
            final boolean isCurrent = option.value == null
                    ? currentValue == null : option.value.equals(currentValue);
            list.add(new MenuList.Entry(option.label, () -> {
                if (model != null) {
                    Cmd.run(command == null ? "dropdown.pick" : command, () -> model.set(option.value));
                }
                if (onChange != null) {
                    onChange.accept(option.value);
                }
            }).icon(option.icon).checked(isCurrent));
        }
        Overlay.openBelow(ctx, this, list, x, y, h);
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final UiTheme th = ctx.theme;
        int bg = 0xF01A2070;
        if (hover.value() > 0.001) {
            bg = Anim.lerpColor(bg, th.panelLight, hover.f());
        }
        UiDraw.panel(g, x, y, w, h, bg, th.borderSoft);
        final Option<T> current = current();
        int tx = x + 3;
        if (current != null && current.icon != null) {
            UiDraw.icon(g, current.icon, tx, y + (h - 8) / 2, 8, 0xFFFFFFFF);
            tx += 10;
        }
        final String text = current == null ? "" : current.label.getString();
        UiDraw.text(g, ctx.font, UiDraw.ellipsize(ctx.font, text, w - (tx - x) - 10, th.fontScale),
                tx, textY(ctx, y, h), enabled ? th.text : th.textDisabled, th.fontScale, false);
        // 箭头：展开时上下翻转（工程界面的折线箭头，而不是原版的实心三角）
        final double ax = x + w - 7.0;
        final double ay = y + h / 2.0 + (openAnim.progress() - 0.5) * 1.6;
        final double flip = 1.0 - openAnim.progress() * 2.0;
        UiVector.line(g, ax - 2.5, ay - 1.2 * flip, ax, ay + 1.2 * flip, 1.5f, th.textDim);
        UiVector.line(g, ax, ay + 1.2 * flip, ax + 2.5, ay - 1.2 * flip, 1.5f, th.textDim);
    }
}
