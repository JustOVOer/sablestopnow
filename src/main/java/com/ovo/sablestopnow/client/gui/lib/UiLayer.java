package com.ovo.sablestopnow.client.gui.lib;

import org.jetbrains.annotations.Nullable;

/**
 * 一个界面层：不占 {@code Screen} 栈的覆盖层（模型模式面板、HUD、常驻边栏都用它）。
 *
 * <p>为什么不做成 Screen：配合边栏的历史教训 —— Screen 是屏幕栈上的独占界面，
 * 会被别的模组 {@code setScreen} 顶掉（实测 18ms 内被 removed 两次）。
 * 覆盖层只画不占栈，谁也顶不掉，而且不暂停游戏、KeyMapping 照常更新。
 */
public class UiLayer {

    /** 唯一名字（同一名字重复 open 时复用）。 */
    public final String name;
    public final UiRoot root = new UiRoot();

    /** 是否绘制（关闭动画期间也保持 true，交给内部动画处理）。 */
    public boolean visible = true;
    /** 是否吞掉落在自己身上的鼠标事件（覆盖层默认吞）。 */
    public boolean consumeInput = true;
    /** 是否吞噬按键（有焦点控件时）。 */
    public boolean consumeKeys = true;
    /** 按 Esc 是否关闭本层。 */
    public boolean closeOnEscape = true;
    /** 遮罩色（0 = 无）。 */
    public int dimColor;
    /** 是否把自己的矩形登记为「已占用」，供 HUD 避让。 */
    public boolean reserveSpace = true;
    /** 是否参与 HUD 避让计算（HUD 自己的层不参与）。 */
    public boolean hud = false;
    /** 关闭回调。 */
    @Nullable
    public Runnable onClose;

    /** 从右侧/左侧滑入的动画进度。 */
    public final Anim appear = Anim.of(0.0).speed(11.0);

    /**
     * 出现动画的位移方向（渲染时按 {@code (1 - appearProgress)} 乘上去）。
     *
     * <p>例如左侧栏设 {@code slideFromX = -160}：出现时从屏幕左边滑进来。
     * 只有渲染会用到它 —— 命中判定不跟着偏，是因为整个出现动画不到 0.4 秒，
     * 为它做一套「带偏移的命中」不值得（手指还没抬起来动画就结束了）。
     */
    public int slideFromX;
    public int slideFromY;
    /** 关闭请求（由 Esc / 关闭按钮设置）。 */
    public boolean closeRequested;

    public UiLayer(final String name) {
        this.name = name;
    }

    public UiLayer size(final int w, final int h) {
        root.resize(w, h);
        return this;
    }

    public static UiLayer of(final String name) {
        return new UiLayer(name);
    }

    public void resize(final int w, final int h) {
        root.resize(w, h);
    }

    /** 出现动画：0 = 完全收起，1 = 就位。 */
    public float appearProgress() {
        return appear.progress();
    }

    public void requestClose() {
        this.closeRequested = true;
    }
}
