package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 一行设置项：左边名称、右边控件（开关 / 滑条 / 输入框 / 按钮）。
 *
 * <p><b>名称为什么由本控件自己画，而不是塞一个 {@link Label}：</b>整行都要能点、能悬停高亮 ——
 * 开关行点名字也该切换。若名称是个子控件，命中的就是标签本身，行级交互只能靠「往上找父控件」这种
 * 间接办法；自己画则本行天然就是命中结果，悬停判断也只需看鼠标下方控件的祖先链
 * （{@link #rowHovered}），说明文字（{@link #description}）也有了唯一归属。
 */
public class FormRow extends Container {

    public Supplier<Component> label;
    /** 设置项说明；界面用它显示在底部提示条上（也用于悬停提示）。 */
    @Nullable
    public Supplier<Component> description;
    /** 点行内空白处（而非控件）时的动作：开关行用它做「整行可点」。 */
    @Nullable
    public Runnable onClick;

    /** 0 → 1 的出现进度：父布局读它算横坐标，渲染读它算透明度。 */
    public final Anim appear = Anim.of(0.0).speed(6.0);
    public int labelGap = 6;

    /** 本行当前是否被悬停（含行内控件被悬停）。 */
    public boolean rowHovered;

    private final Anim hover = Anim.of(0.0).speed(20.0);

    public FormRow(final Supplier<Component> label, @Nullable final Widget control) {
        this.label = label;
        this.prefW = FILL;
        this.prefH = 16;
        if (control != null) {
            add(control);
        }
        appear.to(1.0);
    }

    public FormRow appearSpeed(final double perSecond) {
        appear.speed(perSecond);
        return this;
    }

    public FormRow description(final Supplier<Component> value) {
        this.description = value;
        return this;
    }

    public FormRow rowClick(final Runnable listener) {
        this.onClick = listener;
        return this;
    }

    /** 缓出后的出现进度（0..1）。 */
    public float progress() {
        return Anim.easeOut(appear.progress());
    }

    @Override
    public void measure(final UiContext ctx) {
        if (prefH == AUTO) {
            h = ctx.lineH() + 6;
        }
    }

    @Override
    public void arrange(final UiContext ctx) {
        // 只摆自己的控件：靠右、竖直居中；名称占剩下的宽度（渲染时按可用宽度省略）
        for (final Widget child : children) {
            final int cw = child.wantW(ctx);
            final int ch = Math.min(h, child.wantH(ctx));
            child.x = x + w - child.marginRight - cw;
            child.y = y + (h - ch) / 2;
            child.w = Math.max(0, cw);
            child.h = Math.max(0, ch);
            child.resetClip();
            child.applyClip(clipX, clipY, clipW, clipH);
            if (child instanceof Container container) {
                container.arrange(ctx);
            }
        }
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        appear.tick(ctx.animations, dt);
        rowHovered = hoveredThrough(ctx);
        hover.to(rowHovered && enabled ? 1.0 : 0.0).tick(ctx.animations, dt);
        super.update(ctx, dt);
    }

    /** 鼠标下的控件是不是自己或自己的子孙：这样悬停行内控件时整行也会亮。 */
    private boolean hoveredThrough(final UiContext ctx) {
        for (Widget widget = ctx.hovered; widget != null; widget = widget.parent) {
            if (widget == this) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
        if (enabled && button == 0 && onClick != null) {
            onClick.run();
        }
        return true;
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final float a = appear.progress();
        if (a <= 0.01f) {
            return;
        }
        final UiTheme th = ctx.theme;
        final float hv = (float) hover.value();
        // 悬停时整行提亮一档：设置项多的时候，玩家需要一眼看出鼠标在哪一行
        UiDraw.rect(g, x, y, w, h, UiDraw.fade(Anim.lerpColor(0x14FFFFFF, 0x3AFFFFFF, hv), a));

        int controlLeft = x + w;
        for (final Widget child : children) {
            controlLeft = Math.min(controlLeft, child.x);
        }
        final int maxW = Math.max(8, controlLeft - labelGap - (x + 4));
        UiDraw.text(g, ctx.font,
                UiDraw.ellipsize(ctx.font, label.get().getString(), maxW, th.fontScale),
                x + 4, textY(ctx, y, h),
                UiDraw.fade(hv > 0.5f ? th.text : th.textDim, a), th.fontScale, false);

        renderChildren(ctx, g);
    }
}
