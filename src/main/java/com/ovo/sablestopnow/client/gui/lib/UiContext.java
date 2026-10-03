package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

/**
 * 一帧的上下文：主题、字体、鼠标位置、动画开关、帧间隔。
 *
 * <p>每帧复用同一个实例（避免每帧分配），控件在 {@code render}/{@code update} 里读取。
 */
public final class UiContext {

    public UiTheme theme = UiTheme.current();
    public Font font;
    public UiRoot root;
    /** 当前鼠标悬停的控件（由 {@link UiRoot} 维护，这里只做快照方便控件查询）。 */
    public Widget hovered;
    public double dt = 1.0 / 60.0;
    public double mouseX;
    public double mouseY;
    public boolean animations = true;
    public int screenW;
    public int screenH;

    public UiContext() {
        this.font = Minecraft.getInstance().font;
    }

    public void begin(final UiRoot root, final double dt, final double mouseX, final double mouseY,
                      final int screenW, final int screenH) {
        this.theme = UiTheme.current();
        this.font = Minecraft.getInstance().font;
        this.root = root;
        this.dt = dt;
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.animations = UiSettings.animations;
        this.screenW = screenW;
        this.screenH = screenH;
        this.hovered = root.hovered;
    }

    /** 该控件当前是否被悬停。 */
    public boolean isHovered(final Widget widget) {
        return root != null && root.hovered == widget;
    }

    /** 推进一个动画值。 */
    public double tick(final Anim anim) {
        return anim.tick(animations, dt);
    }

    public int textW(final String s) {
        return theme.textW(font, s);
    }

    public int lineH() {
        return theme.lineH();
    }
}
