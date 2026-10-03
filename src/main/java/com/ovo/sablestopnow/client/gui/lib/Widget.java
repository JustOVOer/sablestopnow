package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 库内所有控件的基类（保留模式）。
 *
 * <p>控件是一个对象、不是一帧一帧的绘制调用：它的状态（悬停、按下、展开、动画值）挂在字段上，
 * 每帧由 {@link UiRoot} 统一推进（{@link #update}）与绘制（{@link #render}）。
 * 位置尺寸是「绝对 GUI 坐标」，由父容器的布局器（{@link Layouts}）在每帧布局阶段写入。
 *
 * <p>尺寸约定：{@link #prefW}/{@link #prefH} 为 {@link #AUTO} 时由 {@link #measure} 决定，
 * 为 {@link #FILL} 时由布局器撑满可用空间，其余为固定像素。
 */
public abstract class Widget {

    /** 由控件自己量出来。 */
    public static final int AUTO = -1;
    /** 撑满父容器可用空间。 */
    public static final int FILL = -2;

    /** 绝对坐标与尺寸（布局阶段写入）。 */
    public int x;
    public int y;
    public int w;
    public int h;

    public int prefW = FILL;
    public int prefH = AUTO;
    public int minW;
    public int minH;
    /** 外边距（左 上 右 下）。 */
    public int marginLeft;
    public int marginTop;
    public int marginRight;
    public int marginBottom;
    /** DockLayout 用：停靠边。 */
    @Nullable
    public Layouts.Dock dock;
    /** DockLayout 用：是否作为填充剩余空间的那个。 */
    public boolean fill;

    public boolean visible = true;
    public boolean enabled = true;
    /** 悬停时是否显示手型/高亮（子类可覆盖）。 */
    public boolean hoverable = true;
    /**
     * 纯显示控件：从命中测试里摘出去。
     *
     * <p>HUD 覆盖层虽然 {@code consumeInput=false}（库的输入派发不会给它事件），但别的界面会直接
     * 遍历所有可见层做 hitTest（例如模型模式的 {@code ModelUi.pointerOverPanel}）。HUD 一旦被算成
     * 「鼠标压在某块面板上」，模型模式的左键就不再去点世界了 —— 显示层必须能声明自己不存在命中体积。
     */
    public boolean pointerTransparent;

    public Container parent;
    /** 可选的标识（调试 / 查找用）。 */
    @Nullable
    public String id;

    // 裁剪矩形（由父容器在布局时写入；默认无裁剪）
    //
    // ⚠ 默认值必须让「右边界」与「下边界」算出来是<b>正数</b>：
    // 之前写的是 clipX = Integer.MIN_VALUE/4、clipW = Integer.MAX_VALUE/4，而
    // inClip 判的是 clipX + clipW = (-536870912) + 536870911 = **-1** ——
    // 于是任何没被父容器显式裁剪过的控件（根节点就是）永远命中失败，
    // 结果是「所有界面的所有按钮都没反应」（用户实测反馈的模型视图/设置界面按键全无响应）。
    // 这里给一个足够大、且加减不会溢出的对称范围。
    static final int CLIP_MIN = -1000000;
    static final int CLIP_SIZE = 2000000;
    int clipX = CLIP_MIN;
    int clipY = CLIP_MIN;
    int clipW = CLIP_SIZE;
    int clipH = CLIP_SIZE;

    // ============ 尺寸 ============

    /** 内容尺寸计算（仅当 prefW/prefH 为 AUTO 时被调用）。 */
    public void measure(final UiContext ctx) {
    }

    public int wantW(final UiContext ctx) {
        if (prefW >= 0) {
            return prefW;
        }
        if (prefW == AUTO) {
            measure(ctx);
            return Math.max(minW, w);
        }
        return Math.max(minW, 0);
    }

    public int wantH(final UiContext ctx) {
        if (prefH >= 0) {
            return prefH;
        }
        if (prefH == AUTO) {
            measure(ctx);
            return Math.max(minH, h);
        }
        return Math.max(minH, 0);
    }

    public Widget size(final int width, final int height) {
        this.prefW = width;
        this.prefH = height;
        return this;
    }

    public Widget width(final int width) {
        this.prefW = width;
        return this;
    }

    public Widget height(final int height) {
        this.prefH = height;
        return this;
    }

    public Widget margin(final int all) {
        return margin(all, all, all, all);
    }

    public Widget margin(final int l, final int t, final int r, final int b) {
        this.marginLeft = l;
        this.marginTop = t;
        this.marginRight = r;
        this.marginBottom = b;
        return this;
    }

    public Widget dock(final Layouts.Dock d) {
        this.dock = d;
        return this;
    }

    public Widget fill() {
        this.fill = true;
        return this;
    }

    /**
     * 空间不足时的紧凑模式开关。
     *
     * <p>由 {@link Layouts#hboxFit(int, int)} 在「完整内容放不下」时整排打开；默认无效果，
     * 有短标签的控件（{@link Button}）覆盖它。这样布局器不必知道具体控件类型。
     */
    public void setCompact(final boolean value) {
    }

    public Widget id(final String value) {
        this.id = value;
        return this;
    }

    // ============ 生命周期 ============

    /** 每帧推进（动画、悬停状态）。 */
    public void update(final UiContext ctx, final double dt) {
    }

    /** 绘制自己（子类不要在这里绘制子控件，那是 {@link Container} 的事）。 */
    public abstract void render(UiContext ctx, GuiGraphics g);

    // ============ 命中 ============

    public boolean contains(final double mx, final double my) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 是否在裁剪区内。 */
    public boolean inClip(final double mx, final double my) {
        return mx >= clipX && mx < clipX + clipW && my >= clipY && my < clipY + clipH;
    }

    public boolean hit(final double mx, final double my) {
        return visible && !pointerTransparent && contains(mx, my) && inClip(mx, my);
    }

    /** 命中最深的可见控件。 */
    @Nullable
    public Widget hitTest(final double mx, final double my) {
        return hit(mx, my) ? this : null;
    }

    /**
     * 把祖先裁剪区传下来（求交）。
     *
     * <p>⚠ 必须是「两边界的交」，不能写成 {@code clipW = min(clipW, cw)}：裁剪矩形是
     * (原点, 尺寸)，当下传的区间与已有区间<b>起点不同</b>时，直接取尺寸最小值会让右边界
     * 越过祖先的右边界 —— 于是子控件在祖先裁剪区之外仍然可以命中（画不出来却点得到），
     * 表现为「面板边缘外一点点也能点到里面的控件」。
     */
    void applyClip(final int cx, final int cy, final int cw, final int ch) {
        final int x0 = Math.max(clipX, cx);
        final int y0 = Math.max(clipY, cy);
        final int x1 = Math.min(clipX + clipW, cx + cw);
        final int y1 = Math.min(clipY + clipH, cy + ch);
        clipX = x0;
        clipY = y0;
        clipW = Math.max(0, x1 - x0);
        clipH = Math.max(0, y1 - y0);
    }

    void resetClip() {
        clipX = CLIP_MIN;
        clipY = CLIP_MIN;
        clipW = CLIP_SIZE;
        clipH = CLIP_SIZE;
    }

    // ============ 事件 ============

    /** 返回 true 表示「吞掉」这次事件。 */
    public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
        return false;
    }

    public void mouseUp(final UiContext ctx, final double mx, final double my, final int button) {
    }

    public void mouseMove(final UiContext ctx, final double mx, final double my) {
    }

    public void mouseDrag(final UiContext ctx, final double mx, final double my, final int button,
                          final double dx, final double dy) {
    }

    public boolean mouseScroll(final UiContext ctx, final double mx, final double my, final double delta) {
        return false;
    }

    public boolean keyPressed(final UiContext ctx, final int keyCode, final int scanCode, final int modifiers) {
        return false;
    }

    public boolean charTyped(final UiContext ctx, final char codePoint, final int modifiers) {
        return false;
    }

    public boolean focusable() {
        return false;
    }

    public void onFocusGained(final UiContext ctx) {
    }

    public void onFocusLost(final UiContext ctx) {
    }

    /** 悬停提示；null 表示没有。 */
    @Nullable
    public Component tooltip() {
        return null;
    }

    /** 是否把子控件裁在自己的矩形内。 */
    public boolean clipsChildren() {
        return false;
    }

    /** 收集需要绘制在最上层的弹出控件（下拉列表、右键菜单）。 */
    public void collectPopups(final List<Widget> out) {
    }

    /** 遍历所有子控件（默认没有）。 */
    public void forEachChild(final java.util.function.Consumer<Widget> visitor) {
    }

    // ============ 便捷 ============

    protected static Font font(final UiContext ctx) {
        return ctx.font;
    }

    protected static UiTheme theme(final UiContext ctx) {
        return ctx.theme;
    }

    /** 行内文字基线偏移，让文本在高度 h 的矩形里垂直居中。 */
    protected int textY(final UiContext ctx, final int top, final int height) {
        return top + (height - ctx.theme.lineH()) / 2;
    }
}
