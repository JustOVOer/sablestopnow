package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 大类入口块（GUI1.png 里的「图标 + 文字」）：图标渲染的是游戏内物品，整块可点。
 *
 * <p><b>为什么出现动画放在控件里：</b>三个入口是<b>依次</b>出现的（后一个比前一个慢一点，
 * 于是自然错开），而位移、淡入淡出必须同步 —— 位置由父布局按 {@link #progress()} 摆，
 * 透明度走同一个值。把这两件事都挂在控件上，界面就不必维护「第几个、动画到哪了」的平行数组。
 */
public class IconTile extends Widget {

    /** 图标（用 {@link UiDraw#item} 渲染，取不到物品时给空栈即可）。 */
    public Supplier<ItemStack> icon = () -> ItemStack.EMPTY;
    @Nullable
    public Supplier<Component> label;
    @Nullable
    public Supplier<Component> subtitle;
    /** 点击回调（在鼠标松开、仍指着本块时触发）。 */
    @Nullable
    public Runnable action;

    public int iconSize = 18;
    public int padLeft = 5;
    public int textGap = 6;
    /** 图标底衬（让透明背景的物品图标也看得清）。 */
    public int iconBg = 0x60000000;

    /** 0 → 1 的出现进度；位移与淡入共用一个值。 */
    public final Anim appear = Anim.of(0.0).speed(6.0);
    private final Anim hover = Anim.of(0.0).speed(18.0);

    public IconTile(final Supplier<ItemStack> icon, @Nullable final Supplier<Component> label,
                    @Nullable final Supplier<Component> subtitle) {
        this.icon = icon;
        this.label = label;
        this.subtitle = subtitle;
        this.prefW = FILL;
        this.prefH = AUTO;
        appear.to(1.0);
    }

    /** 收敛速度越小出现得越晚：界面按序号给不同的值就得到「依次出现」。 */
    public IconTile appearSpeed(final double perSecond) {
        appear.speed(perSecond);
        return this;
    }

    public IconTile action(final Runnable listener) {
        this.action = listener;
        return this;
    }

    /** 缓出后的出现进度（0..1）：父布局按它算横坐标。 */
    public float progress() {
        return Anim.easeOut(appear.progress());
    }

    /** 图标相对原始 16px 的缩放倍率（大类详情页拿它当「飞出去」的起点）。 */
    public float iconScale() {
        return iconSize / 16.0f;
    }

    /**
     * 两行文字块的上沿（绝对坐标）：把 label 与 subtitle 按当前行高叠成一个整块再竖直居中。
     *
     * <p><b>为什么要单独一个方法：</b>行高是随界面字号缩放变的（1.0 → 9px、0.75 → 7px），
     * 两行的间距必须由同一个行高推出来。原来把第二行写死成 {@code h/2 - 1}，第一行下沿正好压到
     * 第二行上沿，两种字号下都重叠 1px；交给这个方法后，渲染和（将来的）命中/测量都只有一份公式。
     */
    public int textTop(final UiContext ctx) {
        return y + (h - ctx.lineH() * 2) / 2;
    }

    public int iconCenterX() {
        return x + padLeft + iconSize / 2;
    }

    public int iconCenterY() {
        return y + h / 2;
    }

    @Override
    public void measure(final UiContext ctx) {
        if (prefW == AUTO) {
            int textW = 0;
            if (label != null) {
                textW = Math.max(textW, ctx.textW(label.get().getString()));
            }
            if (subtitle != null) {
                textW = Math.max(textW, ctx.textW(subtitle.get().getString()));
            }
            w = padLeft + iconSize + (textW > 0 ? textGap + textW : 0) + 4;
        }
        if (prefH == AUTO) {
            h = Math.max(iconSize, ctx.lineH() * 2 + 4);
        }
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        appear.tick(ctx.animations, dt);
        hover.to(ctx.isHovered(this) && enabled ? 1.0 : 0.0).tick(ctx.animations, dt);
    }

    @Override
    public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
        return true; // 整块都是命中区，别让点击穿到底下的背景
    }

    @Override
    public void mouseUp(final UiContext ctx, final double mx, final double my, final int button) {
        if (enabled && button == 0 && action != null && hit(mx, my)) {
            action.run();
        }
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final float a = appear.progress();
        if (a <= 0.01f) {
            return;
        }
        final UiTheme th = ctx.theme;
        final float hv = (float) hover.value();
        final int bg = Anim.lerpColor(th.panelDark, th.accent, hv * 0.85f);
        UiDraw.rect(g, x, y, w, h, UiDraw.fade(bg, a * (0.7f + 0.3f * hv)));
        if (hv > 0.01f) {
            UiDraw.rect(g, x, y, w, 1, UiDraw.fade(th.accentHover, a * hv));
        }

        final int iy = y + (h - iconSize) / 2;
        UiDraw.rect(g, x + padLeft, iy, iconSize, iconSize, UiDraw.fade(iconBg, a));
        UiDraw.item(g, icon.get(), x + padLeft, iy, iconSize);

        final int tx = x + padLeft + iconSize + textGap;
        final int maxW = Math.max(8, x + w - 4 - tx);
        // 两行文字按「自己的行高」堆成一整块再竖直居中（间距公式只有 textTop 一份）
        final int lineH = ctx.lineH();
        final int top = textTop(ctx);
        if (label != null) {
            UiDraw.text(g, ctx.font, UiDraw.ellipsize(ctx.font, label.get().getString(), maxW, th.fontScale),
                    tx, top, UiDraw.fade(th.text, a), th.fontScale, true);
        }
        if (subtitle != null) {
            UiDraw.text(g, ctx.font, UiDraw.ellipsize(ctx.font, subtitle.get().getString(), maxW, th.fontScale),
                    tx, top + lineH, UiDraw.fade(th.textDim, a), th.fontScale, false);
        }
    }
}
