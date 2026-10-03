package com.ovo.sablestopnow.client.gui.lib;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * GUI 库的绘制原语。所有界面只通过这里画东西，换主题/换渲染方式时只改这一处。
 *
 * <p>坐标一律是「GUI 缩放坐标系」（和 {@link GuiGraphics} 一致）；文本一律按
 * {@link UiTheme#fontScale} 缩放绘制，因此高度是 {@link UiTheme#lineH()} 而不是 9。
 */
public final class UiDraw {

    private UiDraw() {
    }

    // ============ 矩形 ============

    public static void rect(final GuiGraphics g, final int x, final int y, final int w, final int h, final int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) {
            return;
        }
        g.fill(x, y, x + w, y + h, argb);
    }

    /**
     * 按倍率缩放 alpha 通道（淡入淡出）。
     *
     * <p>库里的出现/消失动画都走 {@link Anim}，而 {@link Anim} 只给一个 0..1 的值，
     * 颜色得由控件自己乘上去；每个控件都手撸一遍位运算是重复且容易写错的事，所以放这里。
     */
    public static int fade(final int argb, final float mul) {
        if (mul >= 0.999f) {
            return argb;
        }
        final int a = Math.round(((argb >>> 24) & 0xFF) * Math.max(0.0f, Math.min(1.0f, mul)));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    public static void rectOutline(final GuiGraphics g, final int x, final int y, final int w, final int h,
                                   final int argb, final int thickness) {
        if (w <= 0 || h <= 0 || thickness <= 0) {
            return;
        }
        rect(g, x, y, w, thickness, argb);
        rect(g, x, y + h - thickness, w, thickness, argb);
        rect(g, x, y + thickness, thickness, h - thickness * 2, argb);
        rect(g, x + w - thickness, y + thickness, thickness, h - thickness * 2, argb);
    }

    /** 竖向渐变（自上而下）。用小条带手绘，避免依赖不同版本签名不一致的 fillGradient。 */
    public static void gradientV(final GuiGraphics g, final int x, final int y, final int w, final int h,
                                 final int top, final int bottom) {
        if (w <= 0 || h <= 0) {
            return;
        }
        final int steps = Math.min(h, 48);
        for (int i = 0; i < steps; i++) {
            final int y0 = y + h * i / steps;
            final int y1 = y + h * (i + 1) / steps;
            rect(g, x, y0, w, Math.max(1, y1 - y0), Anim.lerpColor(top, bottom, (i + 0.5f) / steps));
        }
    }

    /** 横向渐变（自左而右）。 */
    public static void gradientH(final GuiGraphics g, final int x, final int y, final int w, final int h,
                                 final int left, final int right) {
        if (w <= 0 || h <= 0) {
            return;
        }
        final int steps = Math.min(w, 48);
        for (int i = 0; i < steps; i++) {
            final int x0 = x + w * i / steps;
            final int x1 = x + w * (i + 1) / steps;
            rect(g, x0, y, Math.max(1, x1 - x0), h, Anim.lerpColor(left, right, (i + 0.5f) / steps));
        }
    }

    /** 面板：底色 + 边框 + 上沿高光。 */
    public static void panel(final GuiGraphics g, final int x, final int y, final int w, final int h,
                             final int bg, final int border) {
        rect(g, x, y, w, h, bg);
        rectOutline(g, x, y, w, h, border, 1);
    }

    /** 分组块（浅底 + 左侧强调条）。 */
    public static void group(final GuiGraphics g, final int x, final int y, final int w, final int h,
                             final int bg, final int accent) {
        rect(g, x, y, w, h, bg);
        rect(g, x, y, 2, h, accent);
    }

    /**
     * 右端斜切的标签（模式横幅）。
     *
     * <p>为什么进原语：HUD 的模式横幅右端不是直角，斜边一眼就能看出「这一条是标题」，而矩形拼不出
     * 斜边；「斜切标签」是这套界面的形状语言（模型模式的标题条、将来的分组标签都要用同一把尺子），
     * 所以几何只能有一个来源 —— 摆控件的和画背景的若各算一遍，差一像素就会露出底下的世界。
     *
     * <p>斜切方向：顶边最长、底边收在 {@code w} 上（与参考草图一致）。
     *
     * @param fill 主底色，{@code line} 是上下两条压边（做出「带子」的厚度感），{@code edge} 是描边
     */
    public static void slantRight(final GuiGraphics g, final int x, final int y, final int w, final int h,
                                  final int slant, final int fill, final int line, final int edge) {
        if (w <= 0 || h <= 0) {
            return;
        }
        final int sl = Math.max(0, slant);
        final int span = Math.max(1, h - 1);
        for (int j = 0; j < h; j++) {
            // 第 j 行右端多出来的宽度：顶边最多，到底边收齐
            final int extra = sl * (h - 1 - j) / span;
            rect(g, x, y + j, w + extra, 1, (j == 0 || j == h - 1) ? line : fill);
        }
        rect(g, x, y, w + sl, 1, edge);
        rect(g, x, y + h - 1, w, 1, edge);
        rect(g, x, y, 1, h, edge);
        for (int j = 0; j < h; j++) {
            final int extra = sl * (h - 1 - j) / span;
            rect(g, x + w + extra - 1, y + j, 1, 1, edge);
        }
    }

    /**
     * 左侧带三角凸起的面板（描述/说明用，凸起指向它解释的那一行）。
     *
     * <p>为什么进原语：凸起是「这块文字属于哪一行」的唯一提示，矩形画不出来；而且描述面板以后还要
     * 长在别的地方（悬浮说明、模型模式的参数提示），形状不能只存在于 HUD 里。
     */
    public static void callout(final GuiGraphics g, final int x, final int y, final int w, final int h,
                               final int tipW, final int fill, final int edge) {
        if (w <= 0 || h <= 0) {
            return;
        }
        // 尖朝左：从尖端向右逐行张开，形成实心三角
        final int tip = Math.max(0, tipW);
        final int cy = y + h / 2;
        for (int i = 0; i < tip; i++) {
            rect(g, x - tip + i, cy - i, 1, i * 2 + 1, fill);
        }
        panel(g, x, y, w, h, fill, edge);
    }

    /** 实心右向三角（HUD 选框右侧的「▶」指向标记；{@link #triangle} 只能朝上/下）。 */
    public static void triangleRight(final GuiGraphics g, final int x, final int y, final int w, final int h,
                                     final int argb) {
        if (w <= 0 || h <= 0 || (argb >>> 24) == 0) {
            return;
        }
        final int cy = y + h / 2;
        for (int i = 0; i < w; i++) {
            final int half = Math.round((float) h / 2.0f * (w - i) / w);
            if (half <= 0) {
                continue;
            }
            rect(g, x + i, cy - half, 1, half * 2, argb);
        }
    }

    /**
     * 沿矩形周长跑一圈的流光（头亮、尾渐隐到透明）。
     *
     * <p>为什么进原语：「左键应用了这项功能」的反馈就是这条光在选框上走一圈，它的实现是
     * 「周长参数化 + 从头部到尾部的二次衰减」，与具体是什么框无关；放进原语后，列表选中、
     * 按钮确认这类「刚刚生效」的提示都能复用同一种视觉语言，而不是各界面各画一套。
     *
     * <p>路径从左上角出发，顺时针：上边 → 右边 → 下边 → 左边（贴着描边外侧 1px，看起来像光晕）。
     *
     * @param phase 0..1，光条头部在周长上的位置
     * @param tail  尾巴长度（像素），越大拖得越长
     * @param color 头部颜色（不透明），尾巴由 {@code alpha} 与本方法内部的衰减共同决定
     */
    public static void streakRect(final GuiGraphics g, final int x, final int y, final int w, final int h,
                                  final float phase, final int tail, final int color, final float alpha) {
        if (w <= 0 || h <= 0 || alpha <= 0.01f || (color >>> 24) == 0) {
            return;
        }
        final int perimeter = 2 * (w + h);
        if (perimeter <= 0) {
            return;
        }
        final int len = Math.max(6, Math.min(tail, perimeter));
        for (int i = 0; i < len; i++) {
            float t = phase * perimeter - i;
            t = ((t % perimeter) + perimeter) % perimeter;
            final int px;
            final int py;
            if (t < w) {
                px = x + (int) t;
                py = y;
            } else if (t < w + h) {
                px = x + w;
                py = y + (int) (t - w);
            } else if (t < 2 * w + h) {
                px = x + w - (int) (t - w - h);
                py = y + h;
            } else {
                px = x;
                py = y + h - (int) (t - 2 * w - h);
            }
            final float fade = 1.0f - (float) i / len;
            rect(g, px, py, 1, 1, fade(color, alpha * fade * fade));
        }
    }

    // ============ 裁剪 ============

    public static void scissor(final GuiGraphics g, final int x, final int y, final int w, final int h) {
        g.enableScissor(x, y, x + Math.max(0, w), y + Math.max(0, h));
    }

    public static void unscissor(final GuiGraphics g) {
        g.disableScissor();
    }

    // ============ 文本 ============

    public static void text(final GuiGraphics g, final Font font, final String s, final int x, final int y,
                            final int argb, final float scale, final boolean shadow) {
        if (s == null || s.isEmpty()) {
            return;
        }
        final var pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0f);
        pose.scale(scale, scale, 1.0f);
        g.drawString(font, s, 0, 0, argb, shadow);
        pose.popPose();
    }

    public static void text(final GuiGraphics g, final Font font, final Component c, final int x, final int y,
                            final int argb, final float scale, final boolean shadow) {
        text(g, font, c.getString(), x, y, argb, scale, shadow);
    }

    public static void textRight(final GuiGraphics g, final Font font, final String s, final int right, final int y,
                                 final int argb, final float scale, final boolean shadow) {
        text(g, font, s, right - Math.round(font.width(s) * scale), y, argb, scale, shadow);
    }

    public static void textCentered(final GuiGraphics g, final Font font, final String s, final int cx, final int y,
                                    final int argb, final float scale, final boolean shadow) {
        text(g, font, s, cx - Math.round(font.width(s) * scale / 2.0f), y, argb, scale, shadow);
    }

    /** 尾部省略的裁剪文本。 */
    public static String ellipsize(final Font font, final String s, final int maxWidth, final float scale) {
        if (s == null) {
            return "";
        }
        if (font.width(s) * scale <= maxWidth) {
            return s;
        }
        final String dots = "…";
        final int dotsW = Math.round(font.width(dots) * scale);
        int w = 0;
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            final int cw = font.width(String.valueOf(s.charAt(i)));
            if (Math.round((w + cw) * scale) + dotsW > maxWidth) {
                break;
            }
            sb.append(s.charAt(i));
            w += cw;
        }
        return sb.append(dots).toString();
    }

    /** 从头裁剪（保留尾部），用于输入框滚动。 */
    public static String tailClip(final Font font, final String s, final int maxWidth, final float scale) {
        if (s == null) {
            return "";
        }
        int start = 0;
        while (start < s.length() && font.width(s.substring(start)) * scale > maxWidth) {
            start++;
        }
        return s.substring(start);
    }

    // ============ 线与图标 ============

    /** 1px 细线（Bresenham，用矩形拼）。 */
    public static void line(final GuiGraphics g, final int x0, final int y0, final int x1, final int y1, final int argb) {
        int x = x0;
        int y = y0;
        final int dx = Math.abs(x1 - x0);
        final int dy = -Math.abs(y1 - y0);
        final int sx = x0 < x1 ? 1 : -1;
        final int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int guard = 0;
        while (guard++ < 4096) {
            rect(g, x, y, 1, 1, argb);
            if (x == x1 && y == y1) {
                break;
            }
            final int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
    }

    /** 用两个三角面拼出的实心三角（下拉箭头、树状图展开箭头等）。 */
    public static void triangle(final GuiGraphics g, final int x, final int y, final int w, final int h,
                                final boolean pointDown, final int argb) {
        for (int i = 0; i < h; i++) {
            final int half = pointDown ? Math.round((float) w / 2.0f * (h - i) / h) : Math.round((float) w / 2.0f * i / h);
            if (half <= 0) {
                continue;
            }
            final int cx = x + w / 2;
            rect(g, cx - half, y + i, half * 2, 1, argb);
        }
    }

    /**
     * “&gt;”形折线在高度 y 处的左边界 x。
     *
     * <p>设置界面右侧的蓝色色块不是矩形：左边缘在竖直方向折成“&gt;”（中间最靠右）。
     * 设置项要沿这条折线排布、滚动时跟着折线左右移动，所以<b>折线的几何必须只有一个来源</b>：
     * 画色块的和摆控件的各算一遍，差一两像素就会露出背景，还会随窗口尺寸放大。
     * 因此把它和 {@link #chevronBand} 一起放在绘制原语里共用。
     */
    public static int chevronX(final int left, final int top, final int bottom, final int depth, final int y) {
        final int span = Math.max(1, bottom - top);
        final float t = Math.max(0.0f, Math.min(1.0f, (y - top) / (float) span));
        // 三角波 0 → 1 → 0：顶端与底端贴在 left 上，正中折进 depth
        final float bump = t < 0.5f ? t * 2.0f : (1.0f - t) * 2.0f;
        return left + Math.round(bump * depth);
    }

    /**
     * 左边界是 {@link #chevronX} 折线的色块，右边界由 revealRight 给出（出现动画就是让它从 left 长到 right）。
     *
     * <p>按 2px 一段横着铺，而不是逐像素：折线看起来已经足够平滑，调用次数少两个数量级。
     */
    public static void chevronBand(final GuiGraphics g, final int left, final int top, final int bottom,
                                   final int depth, final int revealRight, final int fill, final int edge) {
        if (bottom <= top) {
            return;
        }
        for (int y = top; y < bottom; y += 2) {
            final int segment = Math.min(2, bottom - y);
            final int x = chevronX(left, top, bottom, depth, y);
            if (revealRight <= x) {
                continue;
            }
            rect(g, x, y, revealRight - x, segment, fill);
            // 折线一侧压一条浅色边：色块靠这条边才能看出是“折”的（参考图里也是这么画的）
            rect(g, x, y, Math.min(2, revealRight - x), segment, edge);
        }
    }

    /** 圆环描边（大类详情页放大图标背后的装饰圈）。按角度打点，不依赖任何贴图。 */
    public static void ring(final GuiGraphics g, final int cx, final int cy, final int radius, final int thickness,
                            final int argb) {
        if (radius <= 1 || (argb >>> 24) == 0) {
            return;
        }
        final int steps = Math.max(32, radius * 6);
        final int t = Math.max(1, thickness);
        for (int i = 0; i < steps; i++) {
            final double a = i * 2.0 * Math.PI / steps;
            final double cos = Math.cos(a);
            final double sin = Math.sin(a);
            for (int k = 0; k < t; k++) {
                rect(g, cx + (int) Math.round(cos * (radius - k)), cy + (int) Math.round(sin * (radius - k)), 1, 1, argb);
            }
        }
    }

    /** 复选框对勾。 */
    public static void check(final GuiGraphics g, final int x, final int y, final int w, final int h, final int argb) {
        final int t = Math.max(1, h / 5);
        for (int i = 0; i < h; i++) {
            final int px = x + w * i / h;
            rect(g, px, y + i, t, t, argb);
            rect(g, px, y + h - 1 - i, t, t, argb);
        }
    }

    /** 贴图图标（原始 16x16 尺寸按 size 缩放，颜色可乘算）。 */
    public static void icon(final GuiGraphics g, final ResourceLocation texture, final int x, final int y,
                            final int size, final int tint) {
        applyTint(tint);
        g.blit(texture, x, y, size, size, 0.0f, 0.0f, 16, 16, 16, 16);
        clearTint();
    }

    /**
     * **图集精灵**（方块贴图就是这种）按 size 画出来。
     *
     * <p>⚠ 与上面 {@link #icon} 的区别很关键：{@code icon} 画的是**独立贴图文件**
     * （UV 就是 0..16）；而方块贴图是**图集里的一小块**，UV 在整张图集上。
     * 用 {@code icon} 去画方块贴图会画出图集左上角那 16×16 —— 看起来像"随便一个贴图"，
     * 极难查。所以方块预览一律走这个函数。
     *
     * <p>用 {@link GuiGraphics#blitSprite}：它自己会按图集的分辨率换算 UV，不用我们手算。
     */
    public static void sprite(final GuiGraphics g, final net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
                              final int x, final int y, final int size, final int tint) {
        if (sprite == null) {
            return;
        }
        applyTint(tint);
        // ⚠ 正确重载是 `blit(x, y, blitOffset, w, h, TextureAtlasSprite)` —— **不带图集位置**。
        //   我先后猜了三种带 ResourceLocation 的写法，全错；用 javap 列出 GuiGraphics 的
        //   全部 blit* 才定死。签名这种东西不该猜。
        g.blit(x, y, 0, size, size, sprite);
        clearTint();
    }

    /** 同 {@link #sprite}，不叠色。 */
    public static void sprite(final GuiGraphics g, final net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
                              final int x, final int y, final int size) {
        sprite(g, sprite, x, y, size, 0xFFFFFFFF);
    }

    /** 原版物品图标（含 16x16 缩放到 size 的近似）。 */
    public static void item(final GuiGraphics g, final ItemStack stack, final int x, final int y, final int size) {
        if (stack.isEmpty()) {
            return;
        }
        final float s = size / 16.0f;
        final var pose = g.pose();
        pose.pushPose();
        pose.translate(x, y, 0.0f);
        pose.scale(s, s, 1.0f);
        g.renderItem(stack, 0, 0);
        pose.popPose();
    }

    private static void applyTint(final int argb) {
        if (argb == 0xFFFFFFFF) {
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
            return;
        }
        final float a = ((argb >>> 24) & 0xFF) / 255.0f;
        final float r = ((argb >> 16) & 0xFF) / 255.0f;
        final float gg = ((argb >> 8) & 0xFF) / 255.0f;
        final float b = (argb & 0xFF) / 255.0f;
        RenderSystem.setShaderColor(r, gg, b, a);
    }

    private static void clearTint() {
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
