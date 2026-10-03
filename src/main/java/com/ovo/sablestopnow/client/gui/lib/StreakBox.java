package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 高亮选框：底色 + 描边 + 右侧指向三角（「▶」）+ 沿周长跑一圈的流光。
 *
 * <p>为什么做成控件：HUD 的选中行要同时满足三件事 —— 「滚轮换功能时蓝框滑过去」、
 * 「左键应用后有一条蓝灰光条沿框转一圈」、「Z/X 这类持续型功能在结束前光条一直转」。
 * 前两件是控件自己的动画状态，第三件是「什么时候停」的外部信号；把它们散在界面里算，
 * 界面代码就得同时管位置插值和相位推进，很容易和别的动画互相覆盖。控件化之后，
 * 界面只负责摆位置与调用 {@link #apply(boolean)} / {@link #stopLoop()}。
 */
public class StreakBox extends Widget {

    /** 覆盖底色（0 = 主题的选中色）。 */
    public int fill;
    /** 覆盖描边色（0 = 主题边框色）。 */
    public int edge;
    /** 流光头部颜色（0 = 主题钢蓝提亮）。 */
    public int streakColor;
    /** 右侧指向三角（说明「这一行就是当前项」）。 */
    public boolean arrow = true;
    public int arrowW = 4;
    public float alpha = 1.0f;

    /** 流光整体不透明度：出现/消失都走缓动。 */
    public final Anim glow = Anim.of(0.0).speed(18.0);
    /** 光条转速（每秒圈数）。 */
    public double lapsPerSecond = 0.85;

    private boolean running;
    private boolean loop;
    private double phase;

    public StreakBox() {
        this.prefW = FILL;
        this.prefH = AUTO;
    }

    /**
     * 应用了一项功能：从头开始跑。
     *
     * @param sustained 持续型功能（缩放/区域选择）：光条会一直转，直到 {@link #stopLoop()}
     */
    public void apply(final boolean sustained) {
        this.running = true;
        this.loop = sustained;
        // 单次反馈要快（0.5 秒一圈才像「刚刚生效」），持续型功能慢一倍才不晃眼
        this.lapsPerSecond = sustained ? 0.85 : 2.0;
        this.phase = 0.0;
        this.glow.to(1.0);
    }

    /** 持续型功能结束：把当前这一圈跑完再收，不半路截断（截断会看到光条突然消失）。 */
    public void stopLoop() {
        this.loop = false;
    }

    /** 立刻收起（例如整块界面换内容时，旧的光条不该跟到新卡片上）。 */
    public void cancel() {
        this.running = false;
        this.loop = false;
        this.phase = 0.0;
        this.glow.to(0.0);
    }

    public boolean running() {
        return running;
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        if (!ctx.animations) {
            // 全局动画关掉时不做转圈：光条本身是「刚刚做了一件事」的动画反馈，没有动画就没有它。
            running = false;
            loop = false;
            phase = 0.0;
            glow.to(0.0);
        } else if (running) {
            phase += dt * lapsPerSecond;
            if (phase >= 1.0) {
                if (loop) {
                    phase -= 1.0;
                } else {
                    // 跑满一圈：停在上边沿并淡出（相位归零，尾巴正好压在头部附近）
                    phase = 0.0;
                    running = false;
                    glow.to(0.0);
                }
            }
        }
        glow.tick(ctx.animations, dt);
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final UiTheme th = ctx.theme;
        UiDraw.rect(g, x, y, w, h, UiDraw.fade(fill == 0 ? th.select : fill, alpha));
        final int line = UiDraw.fade(edge == 0 ? th.border : edge, alpha);
        UiDraw.rectOutline(g, x, y, w, h, line, 1);
        if (arrow) {
            UiDraw.triangleRight(g, x + w + 1, y + (h - 5) / 2, arrowW, 5, line);
        }
        if (running && glow.value() > 0.01) {
            final int head = streakColor == 0
                    ? Anim.lerpColor(th.steel, 0xFFFFFFFF, 0.55f) : streakColor;
            UiDraw.streakRect(g, x, y, w, h, (float) phase, Math.max(10, (w + h) / 2),
                    UiDraw.fade(head, alpha), (float) glow.value());
        }
    }
}
