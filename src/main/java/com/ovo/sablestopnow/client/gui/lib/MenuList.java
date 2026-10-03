package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 垂直菜单列表：下拉菜单、补全列表、右键菜单都用它。
 *
 * <p>作为弹出层使用时由调用方定位（{@link #openAt}），根节点只负责把它排在子控件之上。
 */
public class MenuList extends Container {

    /** 菜单项。 */
    public static class Entry {
        public Component label;
        @Nullable
        public ResourceLocation icon;
        /**
         * **图集精灵**（方块贴图预览用）。
         *
         * <p>⚠ 与 {@link #icon} 不是一回事：{@code icon} 是**独立贴图文件**（UV 0..16），
         * 而方块贴图在**图集**里。用 {@code icon} 画方块贴图会画出图集左上角那 16×16。
         * 两者都有时优先画 sprite。
         */
        @Nullable
        public net.minecraft.client.renderer.texture.TextureAtlasSprite sprite;
        public boolean enabled = true;
        /** 前面是否画勾（当前选中项）。 */
        public boolean checked;
        @Nullable
        public String shortcut;
        @Nullable
        public Runnable action;
        /** 点击后是否关闭菜单。 */
        public boolean closeOnClick = true;
        /** 是否为分隔条（忽略其它字段）。 */
        public boolean separator;
        /** 行首强调色（0 = 用主题色）。 */
        public int accent;

        public Entry(final Component label, @Nullable final Runnable action) {
            this.label = label;
            this.action = action;
        }

        public static Entry of(final String labelKey, final Runnable action) {
            return new Entry(Component.translatable(labelKey), action);
        }

        public static Entry separator() {
            final Entry e = new Entry(Component.empty(), null);
            e.separator = true;
            e.enabled = false;
            return e;
        }

        public Entry icon(@Nullable final ResourceLocation texture) {
            this.icon = texture;
            return this;
        }

        /** 带**图集精灵**（方块贴图预览）。见 {@link #sprite} 字段上的警告。 */
        public Entry sprite(
                @Nullable final net.minecraft.client.renderer.texture.TextureAtlasSprite value) {
            this.sprite = value;
            return this;
        }

        public Entry checked(final boolean value) {
            this.checked = value;
            return this;
        }

        public Entry enabled(final boolean value) {
            this.enabled = value;
            return this;
        }

        public Entry shortcut(final String value) {
            this.shortcut = value;
            return this;
        }

        public Entry accent(final int argb) {
            this.accent = argb;
            return this;
        }
    }

    public final List<Entry> entries = new ArrayList<>();
    public int entryH = 12;
    public int itemGap = 1;
    /** 列表宽度（0 = 自动）。 */
    public int fixedWidth;

    public MenuList(final int width) {
        this.fixedWidth = width;
        this.prefW = width > 0 ? width : AUTO;
        this.prefH = AUTO;
        this.layout = Layouts.vbox(2, itemGap);
        this.padLeft = 2;
        this.padRight = 2;
        this.padTop = 2;
        this.padBottom = 2;
    }

    public MenuList add(final Entry entry) {
        entries.add(entry);
        children.clear();
        return this;
    }

    public MenuList addAll(final List<Entry> list) {
        entries.addAll(list);
        children.clear();
        return this;
    }

    public void clearEntries() {
        entries.clear();
        children.clear();
    }

    /** 弹出层的定位。 */
    public MenuList openAt(final int px, final int py) {
        this.x = px;
        this.y = py;
        this.w = fixedWidth > 0 ? fixedWidth : 90;
        this.h = height();
        rebuild();
        return this;
    }

    /** 列表总高度（弹出定位用；与 {@link #openAt} 用同一份计算，避免两处各算一遍差一像素）。 */
    public int height() {
        return Math.max(1, entries.size() * (entryH + itemGap) + padTop + padBottom);
    }

    private void rebuild() {
        children.clear();
        for (final Entry entry : entries) {
            final MenuRow row = new MenuRow(entry, this);
            row.parent = this;
            children.add(row);
        }
    }

    @Override
    public void measure(final UiContext ctx) {
        if (fixedWidth <= 0) {
            int max = 40;
            for (final Entry e : entries) {
                max = Math.max(max, ctx.textW(e.label.getString()) + 22 + (e.shortcut != null ? ctx.textW(e.shortcut) : 0));
            }
            w = max + padLeft + padRight;
        } else {
            w = fixedWidth;
        }
        h = height();
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        UiDraw.rect(g, x + 1, y + 2, w, h, 0x66000000);
        UiDraw.panel(g, x, y, w, h, 0xF2141A5C, ctx.theme.border);
        renderChildren(ctx, g);
    }

    /** 一行的点击处理由 {@link MenuRow} 负责。 */
    static class MenuRow extends Widget {
        private final Entry entry;
        private final MenuList owner;
        private final Anim hover = Anim.of(0.0).speed(22.0);

        MenuRow(final Entry entry, final MenuList owner) {
            this.entry = entry;
            this.owner = owner;
            this.prefW = FILL;
            this.prefH = owner.entryH;
        }

        @Override
        public void update(final UiContext ctx, final double dt) {
            hover.to(ctx.isHovered(this) && entry.enabled ? 1.0 : 0.0).tick(ctx.animations, dt);
        }

        @Override
        public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
            if (!entry.enabled || button != 0) {
                return true;
            }
            if (entry.action != null) {
                entry.action.run();
            }
            if (entry.closeOnClick && ctx.root != null) {
                ctx.root.closePopup();
            }
            return true;
        }

        @Override
        public void render(final UiContext ctx, final GuiGraphics g) {
            final UiTheme th = ctx.theme;
            if (entry.separator) {
                UiDraw.rect(g, x, y + h / 2, w, 1, th.borderSoft);
                return;
            }
            if (hover.value() > 0.001) {
                UiDraw.rect(g, x, y, w, h, Anim.lerpColor(0x00000000, th.accent, hover.f() * 0.85f));
            }
            final int accent = entry.accent != 0 ? entry.accent : th.accentHover;
            if (entry.checked) {
                UiDraw.rect(g, x, y, 2, h, accent);
            }
            int tx = x + 4;
            // ⚠ 优先画**图集精灵**（方块贴图是图集里的一小块）。
            //   绝不能用下面的 icon() 去画它 —— 那会画出图集左上角的 16×16，
            //   看起来像"随便一个贴图"，极难查。见 UiDraw.sprite 的注释。
            if (entry.sprite != null) {
                UiDraw.sprite(g, entry.sprite, tx, y + (h - 8) / 2, 8, 0xFFFFFFFF);
                tx += 10;
            } else if (entry.icon != null) {
                UiDraw.icon(g, entry.icon, tx, y + (h - 8) / 2, 8, 0xFFFFFFFF);
                tx += 10;
            }
            if (entry.checked) {
                UiDraw.check(g, tx, y + 2, h - 4, h - 4, th.ok);
                tx += h - 3;
            }
            final int shortcutW = entry.shortcut == null ? 0 : ctx.textW(entry.shortcut) + 4;
            final int color = entry.enabled ? th.text : th.textDisabled;
            UiDraw.text(g, ctx.font, UiDraw.ellipsize(ctx.font, entry.label.getString(),
                            w - (tx - x) - shortcutW - 4, th.fontScale),
                    tx, textY(ctx, y, h), color, th.fontScale, false);
            if (entry.shortcut != null) {
                UiDraw.textRight(g, ctx.font, entry.shortcut, x + w - 4, textY(ctx, y, h), th.textDim,
                        th.fontScale, false);
            }
        }
    }
}
