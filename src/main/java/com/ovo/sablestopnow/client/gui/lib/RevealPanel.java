package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 带「长出来」动画的色块面板：设置界面的左侧蓝栏与右侧设置区都是它。
 *
 * <p><b>为什么要有这个控件：</b>要求里左侧色块要<b>从上到下</b>出现、右侧色块要<b>从左往右</b>出现，
 * 且右侧色块的左边界还是折线。这三件事本质都是「先画哪一块矩形」，若放在界面里各写一遍，
 * 就变成每个界面都得自己处理裁剪、边界高光，还得和设置项的坐标对齐；收进控件后，
 * 界面只需要设一个 {@link #reveal} 的目标值（离开时设回 0 即反向播放）。
 *
 * <p>折线几何不在这里另算一份，直接用 {@link UiDraw#chevronX}：画色块的和摆设置项的必须是同一个函数。
 */
public class RevealPanel extends Container {

    /** 展开方向。 */
    public enum Dir {
        /** 自上而下（左侧大类栏）。 */
        TOP_DOWN,
        /** 自左而右（右侧设置区）。 */
        LEFT_RIGHT
    }

    public Dir dir = Dir.TOP_DOWN;
    /** 0 → 1 的展开进度；界面设目标值，离开界面时设回 0 就自动反向。 */
    public final Anim reveal = Anim.of(0.0).speed(6.0);
    /**
     * 底色与折线侧的高光边。
     *
     * <p>按用户反馈（蓝色整体要更深、偏「普蓝」）比最初的取色再压深一档，并靠向 {@link UiTheme}
     * 里同名的那组普蓝（{@code #141B58}）：色块比面板底（{@code panelDark} {@code #111648}）
     * 浅一档，入口块的「暗底 + 亮图标」层次才不会被压平。
     * 只改这里的默认值，不动 {@link UiTheme} —— 主题是全局的，改它会连带影响模型模式。
     */
    public int fill = 0xF0141B58;
    public int edge = 0xFF6B7CA8;
    public int edgeW = 2;
    /** 左边界是否画成 “&gt;” 折线（右侧设置区要，左侧大类栏不要）。 */
    public boolean chevron;
    /** 折线在水平方向折进去的深度（像素）。 */
    public int chevronDepth = 44;
    /**
     * 是否把子控件裁在「已经长出来」的范围内。
     *
     * <p>右侧设置区必须开：色块是从左往右长出来的，若不做这个裁剪，设置项会在色块还没盖到它
     * 之前就画在灰底上，出现动画会穿帮。
     */
    public boolean clipToReveal = true;

    public RevealPanel() {
        this.prefW = FILL;
        this.prefH = FILL;
        this.clip = true; // 子控件永远不越出面板本身（命中判定也跟着收窄）
        reveal.to(1.0);
    }

    /**
     * 折线在本面板高度 absoluteY（绝对坐标）处的左边界 x。
     *
     * <p>界面摆设置项时用它，而不是自己按常量重算：面板挪了位置或折线深度改了，两边也会一起变。
     */
    public int edgeXAt(final int absoluteY) {
        return chevron ? UiDraw.chevronX(x, y, y + h, chevronDepth, absoluteY) : x;
    }

    // ============ 逐帧推进 ============

    /**
     * 推进展开动画。
     *
     * <p><b>为什么必须有这个方法（实机白屏的根因）：</b>{@link Container#update} 只负责把
     * {@code update} 转给子控件，控件自己身上的动画值不在任何链路上。少了这一句，{@link #reveal}
     * 会永远停在初值 0，而 {@link #render} 开头就是 {@code p <= 0.001f} 的提前返回 —— 于是色块自己
     * 不画、后面的 {@code renderChildren} 也一并被跳过，整个界面只剩背景模糊，一个像素都不出。
     *
     * <p>更隐蔽的是：两个设置界面都拿「上一个动画的进度」当闸门编排出场顺序
     * （色块 → 标题 → 图标 → 设置项），{@code reveal} 卡在 0 时闸门永远不会打开，
     * 后续所有动画一起死锁在第一步 —— 这正是「界面完全空白」而不是「只少了色块」的原因。
     *
     * <p>顺序与 {@link FormRow}/{@link IconTile} 一致：先推进自己的动画，再轮到子控件；
     * 动画总开关交给 {@link Anim#tick(boolean, double)} 处理，界面不必自己写分支。
     */
    @Override
    public void update(final UiContext ctx, final double dt) {
        reveal.tick(ctx.animations, dt);
        super.update(ctx, dt);
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final float p = Anim.easeOut(reveal.progress());
        if (p <= 0.001f) {
            return;
        }
        final int right = dir == Dir.LEFT_RIGHT ? x + Math.round(w * p) : x + w;
        final int bottom = dir == Dir.TOP_DOWN ? y + Math.round(h * p) : y + h;
        if (right <= x || bottom <= y) {
            return;
        }
        if (chevron) {
            // 折线色块按整屏高度铺：折线形状是屏幕级的，不能跟着“长出来的那一段”变形
            UiDraw.chevronBand(g, x, y, y + h, chevronDepth, right, fill, edge);
        } else {
            UiDraw.rect(g, x, y, right - x, bottom - y, fill);
            // 外侧压一条高光边，色块才有厚度（GUI1.png 的左侧蓝栏就是这么画的）
            UiDraw.rect(g, Math.max(x, right - edgeW), y, Math.min(edgeW, right - x), bottom - y, edge);
        }
        if (clipToReveal) {
            UiDraw.scissor(g, x, y, right - x, bottom - y);
        }
        renderChildren(ctx, g);
        if (clipToReveal) {
            UiDraw.unscissor(g);
        }
    }
}
