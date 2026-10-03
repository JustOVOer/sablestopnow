package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 可折叠分组（树状图里的「配合 / 属性 / 施力点」小节用它）。 */
public class SectionPane extends Container {

    public final Container header = new Container();
    public final Container content = new Container();
    public final Anim open = Anim.of(1.0).speed(15.0);
    public boolean expanded = true;
    public Component title;
    public int headerH = 13;
    public int gap = 1;

    private final Anim hover = Anim.of(0.0).speed(22.0);

    public SectionPane(final Component title) {
        this.title = title;
        this.prefW = FILL;
        this.prefH = AUTO;
        this.layout = (parent, ctx) -> {
        };
        header.layout = Layouts.hbox(0, 2);
        header.prefW = FILL;
        header.prefH = headerH;
        content.layout = Layouts.vbox(1, 1);
        content.prefW = FILL;
        add(header);
        add(content);
    }

    public SectionPane(final String translationKey) {
        this(Component.translatable(translationKey));
    }

    /** 折叠内容的自然高度。 */
    public static int naturalHeight(final UiContext ctx, final Container container) {
        int total = container.padTop + container.padBottom;
        final int gapCount = Math.max(0, container.children.size() - 1);
        total += gapCount * 1;
        for (final Widget child : container.children) {
            if (child.visible) {
                total += child.wantH(ctx);
            }
        }
        return total;
    }

    @Override
    public void measure(final UiContext ctx) {
        final int body = naturalHeight(ctx, content);
        h = headerH + gap + Math.round(body * open.f());
        w = prefW >= 0 ? prefW : 120;
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        open.to(expanded ? 1.0 : 0.0).tick(ctx.animations, dt);
        hover.to(ctx.isHovered(this) ? 1.0 : 0.0).tick(ctx.animations, dt);
        for (final Widget child : content.children) {
            if (content.visible) {
                child.update(ctx, dt);
            }
        }
        for (final Widget child : header.children) {
            child.update(ctx, dt);
        }
    }

    @Override
    public void arrange(final UiContext ctx) {
        header.x = x;
        header.y = y;
        header.w = w;
        header.h = headerH;
        header.resetClip();
        header.applyClip(clipX, clipY, clipW, clipH);
        header.arrange(ctx);

        final int natural = naturalHeight(ctx, content);
        final int shown = Math.round(natural * open.f());
        content.x = x;
        content.y = y + headerH + gap;
        content.w = w;
        content.h = shown;
        content.visible = shown > 0;
        content.resetClip();
        content.applyClip(clipX, clipY, clipW, clipH);
        content.applyClip(x, y, w, headerH + gap + shown);
        if (content.visible) {
            content.padTop = 1;
            content.arrange(ctx);
        }
    }

    /**
     * 折叠头必须由 SectionPane 自己接住点击。
     *
     * <p>{@code header} 是个空容器：按默认逻辑命中的是它，而容器不处理鼠标事件，
     * 点击会被 {@link UiRoot} 当成「已消耗」而不了了之 —— 折叠就永远点不动。
     * 所以标题栏范围内直接把命中结果换成自己（正文区仍走默认逻辑，好让里面的控件照常收事件）。
     */
    @Override
    public Widget hitTest(final double mx, final double my) {
        if (mx >= x && mx < x + w && my >= y && my < y + headerH) {
            return this;
        }
        return super.hitTest(mx, my);
    }

    @Override
    public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
        if (button == 0 && my >= y && my < y + headerH) {
            expanded = !expanded;
            return true;
        }
        return false;
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final UiTheme th = ctx.theme;
        UiDraw.rect(g, x, y, w, headerH, hover.value() > 0.5 ? th.panelLight : 0xC81E2678);
        final int cx = x + 3;
        final int cy = y + headerH / 2;
        if (open.value() > 0.5) {
            UiDraw.triangle(g, cx, cy - 2, 5, 4, true, th.textDim);
        } else {
            UiDraw.triangle(g, cx + 1, cy - 2, 4, 5, false, th.textDim);
            UiDraw.triangle(g, cx + 1, cy + 2, 4, 5, true, th.textDim);
        }
        UiDraw.text(g, ctx.font, title.getString(), x + 11, textY(ctx, y, headerH), th.text, th.fontScale, false);
        header.renderChildren(ctx, g);
        if (content.visible) {
            UiDraw.scissor(g, content.x, content.y, content.w, content.h);
            content.renderChildren(ctx, g);
            UiDraw.unscissor(g);
        }
    }
}
