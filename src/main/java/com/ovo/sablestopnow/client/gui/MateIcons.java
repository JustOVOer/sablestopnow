package com.ovo.sablestopnow.client.gui;

import com.ovo.sablestopnow.mate.MateType;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 配合类型的矢量图标 —— 纯代码绘制，不用贴图、不用物品。
 *
 * <p>为什么不用贴图/物品图标：配合类型有 8 种，做 8 张 16x16 贴图既要额外资源、又要考虑资源包覆盖，
 * 而物品栏里也找不到 8 个语义能对上的物品（「相切」「同心」根本没有对应的原版物品）。
 * 所以这里直接在 {@code size}×{@code size} 的方框里用 {@link GuiGraphics#fill} 画线：
 * <b>所有坐标都以 0..1 的归一化值给出</b>，再按方框尺寸缩放到像素，因此同一个图形在 12px 的
 * 列表行和 64px 的标题栏里都能画，且不需要为每个尺寸单独调参。
 *
 * <p>斜线不做抗锯齿，而是用「逐点填充」的 Bresenham 式循环把 1px 的小方块铺满整条线
 * （{@link #line}）—— 这样即使缩放到只有十几个像素，线条也不会出现断点或空洞。
 *
 * <p>颜色只用调用方传入的 {@code color}（ARGB），其 alpha 通道原样交给 {@code fill}，
 * 因此调用方可以用 {@link Ease#fade} 让图标跟着行的淡入淡出一起变化。
 */
public final class MateIcons {

    /** 小于这个尺寸时笔画会互相吃掉，干脆不画。 */
    private static final int MIN_SIZE = 3;

    private MateIcons() {
    }

    /**
     * 在 (x,y) 起、边长 size 的方框内画出该配合类型的示意图形。
     *
     * @param graphics 绘制目标
     * @param type     配合类型（null 时不画）
     * @param x        方框左上角 x
     * @param y        方框左上角 y
     * @param size     方框边长（像素）
     * @param color    笔画颜色（ARGB，alpha 会被尊重）
     */
    public static void draw(final GuiGraphics graphics, final MateType type,
                            final int x, final int y, final int size, final int color) {
        if (graphics == null || type == null || size < MIN_SIZE) {
            return;
        }
        final int stroke = Math.max(1, Math.round(size / 8.0f));
        switch (type) {
            case COINCIDENT -> coincident(graphics, x, y, size, color, stroke);
            case PARALLEL -> parallel(graphics, x, y, size, color, stroke);
            case PERPENDICULAR -> perpendicular(graphics, x, y, size, color, stroke);
            case TANGENT -> tangent(graphics, x, y, size, color, stroke);
            case CONCENTRIC -> concentric(graphics, x, y, size, color, stroke);
            case LOCK -> lock(graphics, x, y, size, color, stroke);
            case DISTANCE -> distance(graphics, x, y, size, color, stroke);
            case ANGLE -> angle(graphics, x, y, size, color, stroke);
        }
    }

    // ============ 基础图形 ============

    /** 归一化坐标 → 像素坐标。 */
    private static int p(final int origin, final int size, final float t) {
        return origin + Math.round(t * size);
    }

    /** 用逐点填充画一条任意方向的细线（避免斜线出现断点）。 */
    private static void line(final GuiGraphics graphics, final int x0, final int y0, final int x1, final int y1,
                             final int color, final int thickness) {
        final int dx = x1 - x0;
        final int dy = y1 - y0;
        final int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps <= 0) {
            graphics.fill(x0, y0, x0 + thickness, y0 + thickness, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            final float t = i / (float) steps;
            final int px = x0 + Math.round(dx * t);
            final int py = y0 + Math.round(dy * t);
            graphics.fill(px, py, px + thickness, py + thickness, color);
        }
    }

    /** 空心矩形（四条边）。 */
    private static void frame(final GuiGraphics graphics, final int x0, final int y0, final int x1, final int y1,
                              final int color, final int thickness) {
        graphics.fill(x0, y0, x1, y0 + thickness, color);
        graphics.fill(x0, y1 - thickness, x1, y1, color);
        graphics.fill(x0, y0, x0 + thickness, y1, color);
        graphics.fill(x1 - thickness, y0, x1, y1, color);
    }

    /** 整圆（0..2π）。 */
    private static void circle(final GuiGraphics graphics, final int cx, final int cy, final int r,
                               final int color, final int thickness) {
        arc(graphics, cx, cy, r, 0.0f, (float) (Math.PI * 2.0), color, thickness);
    }

    /** 圆弧：角度用弧度，屏幕坐标系（y 向下，正角=顺时针）。 */
    private static void arc(final GuiGraphics graphics, final int cx, final int cy, final int r,
                            final float start, final float end, final int color, final int thickness) {
        if (r < 1) {
            return;
        }
        final int steps = Math.max(12, (int) (Math.abs(end - start) * r * 1.8f));
        for (int i = 0; i <= steps; i++) {
            final float a = start + (end - start) * (i / (float) steps);
            final int px = cx + Math.round((float) Math.cos(a) * r);
            final int py = cy + Math.round((float) Math.sin(a) * r);
            graphics.fill(px, py, px + thickness, py + thickness, color);
        }
    }

    // ============ 8 种配合 ============

    /** 重合：两个小方块错位叠在一起，中心一个实心点表示「贴上了」。 */
    private static void coincident(final GuiGraphics graphics, final int x, final int y, final int s,
                                   final int color, final int th) {
        frame(graphics, p(x, s, 0.04f), p(y, s, 0.04f), p(x, s, 0.60f), p(y, s, 0.60f), color, th);
        frame(graphics, p(x, s, 0.40f), p(y, s, 0.40f), p(x, s, 0.96f), p(y, s, 0.96f), color, th);
        final int cx = p(x, s, 0.50f);
        final int cy = p(y, s, 0.50f);
        graphics.fill(cx - th, cy - th, cx + th, cy + th, color);
    }

    /** 平行：两条同斜率的斜杠。 */
    private static void parallel(final GuiGraphics graphics, final int x, final int y, final int s,
                                 final int color, final int th) {
        line(graphics, p(x, s, 0.12f), p(y, s, 0.84f), p(x, s, 0.54f), p(y, s, 0.16f), color, th);
        line(graphics, p(x, s, 0.46f), p(y, s, 0.84f), p(x, s, 0.88f), p(y, s, 0.16f), color, th);
    }

    /** 垂直：一个直角（L 形），拐角处再画个小方框强调 90°。 */
    private static void perpendicular(final GuiGraphics graphics, final int x, final int y, final int s,
                                      final int color, final int th) {
        final int vx = p(x, s, 0.16f);
        final int vy = p(y, s, 0.84f);
        line(graphics, vx, vy, p(x, s, 0.90f), vy, color, th);
        line(graphics, vx, vy, vx, p(y, s, 0.14f), color, th);
        final int q = Math.max(2, Math.round(s * 0.18f));
        frame(graphics, vx, vy - q, vx + q, vy, color, 1);
    }

    /** 相切：一个圆 + 一条贴在其顶点上的水平线。 */
    private static void tangent(final GuiGraphics graphics, final int x, final int y, final int s,
                                final int color, final int th) {
        final int cx = p(x, s, 0.40f);
        final int cy = p(y, s, 0.60f);
        final int r = Math.max(2, Math.round(s * 0.30f));
        circle(graphics, cx, cy, r, color, th);
        line(graphics, p(x, s, 0.04f), cy - r, p(x, s, 0.96f), cy - r, color, th);
    }

    /** 同心：一大一小两个同心圆。 */
    private static void concentric(final GuiGraphics graphics, final int x, final int y, final int s,
                                   final int color, final int th) {
        final int cx = p(x, s, 0.50f);
        final int cy = p(y, s, 0.50f);
        circle(graphics, cx, cy, Math.max(3, Math.round(s * 0.44f)), color, th);
        circle(graphics, cx, cy, Math.max(2, Math.round(s * 0.20f)), color, th);
    }

    /** 锁定：锁体方框 + 上半圆的锁梁。 */
    private static void lock(final GuiGraphics graphics, final int x, final int y, final int s,
                             final int color, final int th) {
        final int bx0 = p(x, s, 0.22f);
        final int by0 = p(y, s, 0.46f);
        final int bx1 = p(x, s, 0.78f);
        final int by1 = p(y, s, 0.92f);
        frame(graphics, bx0, by0, bx1, by1, color, th);
        final int r = Math.max(2, Math.round(s * 0.21f));
        arc(graphics, p(x, s, 0.50f), by0, r, (float) Math.PI, (float) (Math.PI * 2.0), color, th);
    }

    /** 距离：两条短竖线之间的双向箭头。 */
    private static void distance(final GuiGraphics graphics, final int x, final int y, final int s,
                                 final int color, final int th) {
        final int left = p(x, s, 0.10f);
        final int right = p(x, s, 0.90f);
        final int top = p(y, s, 0.24f);
        final int bottom = p(y, s, 0.76f);
        final int mid = p(y, s, 0.50f);
        line(graphics, left, top, left, bottom, color, th);
        line(graphics, right, top, right, bottom, color, th);
        line(graphics, left, mid, right, mid, color, th);
        final int head = Math.max(3, Math.round(s * 0.17f));
        line(graphics, left, mid, left + head, mid - head, color, th);
        line(graphics, left, mid, left + head, mid + head, color, th);
        line(graphics, right, mid, right - head, mid - head, color, th);
        line(graphics, right, mid, right - head, mid + head, color, th);
    }

    /** 角度：一个公共顶点伸出两条线，中间夹一段小圆弧。 */
    private static void angle(final GuiGraphics graphics, final int x, final int y, final int s,
                              final int color, final int th) {
        final int vx = p(x, s, 0.14f);
        final int vy = p(y, s, 0.86f);
        line(graphics, vx, vy, p(x, s, 0.92f), vy, color, th);
        line(graphics, vx, vy, p(x, s, 0.84f), p(y, s, 0.16f), color, th);
        final int r = Math.max(3, Math.round(s * 0.34f));
        arc(graphics, vx, vy, r, (float) (-Math.PI / 4.0), 0.0f, color, th);
    }
}
