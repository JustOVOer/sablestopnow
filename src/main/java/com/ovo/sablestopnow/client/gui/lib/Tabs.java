package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 标签栏（SW 式功能分组）：选中块会从当前位置滑到新位置。
 *
 * <p>选中项是 {@link #model}（一份 {@link Prop}），不是控件字段 —— 切页后重建控件时
 * 不会回到第一页，别的模块也能读「现在是哪一页」而不用去问控件。
 */
public class Tabs extends Widget {

    public static class Tab {
        public final String id;
        public Component label;
        @Nullable
        public ResourceLocation icon;
        public boolean enabled = true;
        /** 窄屏时的短标签（见 {@link Layouts#hboxFit(int, int)}、{@link #setCompact(boolean)}）。 */
        @Nullable
        public Component compactLabel;
        /** 悬停提示。 */
        @Nullable
        public Component tooltip;

        public Tab(final String id, final Component label) {
            this.id = id;
            this.label = label;
        }

        public Tab(final String id, final String labelKey) {
            this(id, Component.translatable(labelKey));
        }

        public Tab icon(@Nullable final ResourceLocation texture) {
            this.icon = texture;
            return this;
        }

        public Tab compact(@Nullable final Component value) {
            this.compactLabel = value;
            return this;
        }

        public Tab compact(final String translationKey) {
            return compact(Component.translatable(translationKey));
        }

        public Tab tooltip(final String translationKey) {
            this.tooltip = Component.translatable(translationKey);
            return this;
        }
    }

    public final List<Tab> tabs = new ArrayList<>();
    /** 唯一真相：当前选中的标签 id。 */
    public Prop<String> model;
    /** 切页通知（不是改值）。 */
    @Nullable
    public Consumer<String> onChange;
    public int tabGap = 1;
    public int padX = 7;
    /** 当前是否处于紧凑模式（由 {@link Layouts#hboxFit(int, int)} 设置）。 */
    public boolean compact;

    /** 标签栏自己排版，所以「压窄」必须真的换短标签，否则内部仍按长标签铺、照样溢出。 */
    @Override
    public void setCompact(final boolean value) {
        this.compact = value;
    }

    public final Anim indicatorX = Anim.of(0.0).speed(18.0);
    public final Anim indicatorW = Anim.of(0.0).speed(18.0);
    public final Anim hoverX = Anim.of(0.0).speed(24.0);
    public final Anim hoverW = Anim.of(0.0).speed(24.0);
    private boolean indicatorInit;
    private int hoverIndex = -1;

    public Tabs(final Prop<String> model) {
        this.model = model;
        this.prefH = 15;
        this.prefW = AUTO;
        Cmd.declare(model.key());
    }

    public Tabs add(final Tab tab) {
        tabs.add(tab);
        return this;
    }

    /** 外部设置选中项。 */
    public Tabs select(final String id) {
        if (model != null) {
            Cmd.run(model.key(), () -> model.set(id));
        }
        return this;
    }

    public String selected() {
        return model == null ? null : model.get();
    }

    @Override
    public void measure(final UiContext ctx) {
        int total = 0;
        for (final Tab tab : tabs) {
            total += tabW(ctx, tab) + tabGap;
        }
        w = Math.max(minW, total);
        h = Math.max(minH, prefH >= 0 ? prefH : 15);
    }

    private int tabW(final UiContext ctx, final Tab tab) {
        return ctx.textW(labelOf(tab)) + padX * 2 + (tab.icon != null ? 11 : 0);
    }

    private String labelOf(final Tab tab) {
        if (compact && tab.compactLabel != null) {
            return tab.compactLabel.getString();
        }
        return tab.label.getString();
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        int cursor = x;
        int hoverAt = -1;
        int hoverPos = 0;
        int hoverWidth = 0;
        for (int i = 0; i < tabs.size(); i++) {
            final Tab tab = tabs.get(i);
            final int tw = tabW(ctx, tab);
            if (tab.id.equals(selected())) {
                if (!indicatorInit) {
                    indicatorX.snap(cursor);
                    indicatorW.snap(tw);
                    indicatorInit = true;
                } else {
                    indicatorX.to(cursor);
                    indicatorW.to(tw);
                }
            }
            if (ctx.isHovered(this) && ctx.mouseX >= cursor && ctx.mouseX < cursor + tw
                    && tab.enabled && !tab.id.equals(selected())) {
                hoverAt = i;
                hoverPos = cursor;
                hoverWidth = tw;
            }
            cursor += tw + tabGap;
        }
        if (hoverAt >= 0) {
            if (hoverIndex < 0) {
                hoverX.snap(hoverPos);
                hoverW.snap(hoverWidth);
            } else {
                hoverX.to(hoverPos);
                hoverW.to(hoverWidth);
            }
        }
        hoverIndex = hoverAt;
        indicatorX.tick(ctx.animations, dt);
        indicatorW.tick(ctx.animations, dt);
        hoverX.tick(ctx.animations, dt);
        hoverW.tick(ctx.animations, dt);
    }

    @Override
    public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
        int cursor = x;
        for (final Tab tab : tabs) {
            final int tw = tabW(ctx, tab);
            if (mx >= cursor && mx < cursor + tw) {
                if (tab.enabled && !tab.id.equals(selected())) {
                    Cmd.run(model == null ? "tabs.select" : model.key(), () -> {
                        if (model != null) {
                            model.set(tab.id);
                        }
                    });
                    if (onChange != null) {
                        onChange.accept(tab.id);
                    }
                }
                return true;
            }
            cursor += tw + tabGap;
        }
        return true;
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final UiTheme th = ctx.theme;
        UiDraw.rect(g, x, y, w, h, 0xC8181E66);
        if (hoverIndex >= 0 && hoverW.value() > 0.5) {
            UiDraw.rect(g, (int) Math.round(hoverX.value()), y, Math.max(1, (int) Math.round(hoverW.value())),
                    h - 2, 0x55353FA8);
        }
        if (indicatorInit) {
            final int ix = (int) Math.round(indicatorX.value());
            final int iw = Math.max(1, (int) Math.round(indicatorW.value()));
            // 选中页：底色 + 底部一支强调条（SW 的页签就是这个形状）
            UiDraw.rect(g, ix, y, iw, h - 2, th.accent);
            UiVector.roundRect(g, ix + 1, y + h - 2.5f, iw - 2, 2.0f, 1.0f, th.accentHover);
        }
        int cursor = x;
        for (final Tab tab : tabs) {
            final int tw = tabW(ctx, tab);
            final boolean active = tab.id.equals(selected());
            int tx = cursor + padX;
            if (tab.icon != null) {
                UiDraw.icon(g, tab.icon, tx, y + (h - 8) / 2, 8, 0xFFFFFFFF);
                tx += 11;
            }
            UiDraw.text(g, ctx.font, labelOf(tab), tx, textY(ctx, y, h - 2),
                    active ? th.text : (tab.enabled ? th.textDim : th.textDisabled), th.fontScale, false);
            cursor += tw + tabGap;
        }
    }
}
