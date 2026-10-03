package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;

import java.util.function.Supplier;

/**
 * 大类详情页左上角的「放大后的图标」（GUI2.png）：从大类界面里被点的那一块<b>移动并放大</b>过来，
 * 背后套一圈装饰圆环；离开界面时把 {@link #grow} 设回 0 就原路缩回去。
 *
 * <p><b>为什么把起点交给它自己插值：</b>父布局每帧都会重写控件的 x/y，要是在界面里插值坐标再塞给控件，
 * 就得和布局抢同一份数据。这里让控件自己铺满整屏（布局只管给它整屏矩形），
 * 实际画在哪儿由 {@link #fromX}/{@link #toX} 和动画值算，界面完全不用碰坐标。
 */
public class IconHero extends Widget {

    public Supplier<ItemStack> icon = () -> ItemStack.EMPTY;

    /** 起点：大类界面里那个图标的中心与缩放。 */
    public float fromX;
    public float fromY;
    public float fromScale = 1.0f;
    /** 终点：本界面上目标位置的中心与缩放，由界面按窗口尺寸算。 */
    public float toX;
    public float toY;
    public float toScale = 5.0f;

    public boolean ring = true;
    public int ringThickness = 2;
    /** 装饰圈的钢蓝；跟着色块一起压深一档（比色块亮，才能从深蓝背景里「托」出图标）。 */
    public int ringColor = 0x706B84C0;

    /** 0 → 1：位置、缩放、圆环、透明度全跟着它走。 */
    public final Anim grow = Anim.of(0.0).speed(5.5);

    public IconHero() {
        this.prefW = FILL;
        this.prefH = FILL;
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        grow.tick(ctx.animations, dt);
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final float p = Anim.easeOut(grow.progress());
        if (p <= 0.01f) {
            return;
        }
        final float cx = Anim.lerp(fromX, toX, p);
        final float cy = Anim.lerp(fromY, toY, p);
        final float scale = Anim.lerp(fromScale, toScale, p);
        final int size = Math.max(4, Math.round(16.0f * scale));
        if (ring) {
            // 圆环比图标略大一圈：参考图里圆是「托」着图标的
            UiDraw.ring(g, Math.round(cx), Math.round(cy), Math.round(size * 0.78f), ringThickness,
                    UiDraw.fade(ringColor, p));
        }
        UiDraw.item(g, icon.get(), Math.round(cx - size / 2.0f), Math.round(cy - size / 2.0f), size);
    }
}
