package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 抗锯齿的屏幕空间矢量绘制（工程制图用）。
 *
 * <h2>为什么不直接用 {@link UiDraw#line}</h2>
 * {@code UiDraw.line} 是 Bresenham：整数坐标、1 像素、台阶明显。模型模式的选择标记是
 * 「玩家盯着看、还要拿它当准星」的图元，1 像素硬边在那里的观感就是用户说的
 * <b>「过于像素化过于粗糙」</b>。
 *
 * <h2>怎么做到抗锯齿</h2>
 * 不依赖 MSAA、不依赖浮点顶点：这里自己算<b>覆盖率</b>（coverage 0..1），按覆盖率乘 alpha
 * 逐像素写。代价是每个覆盖到的像素一次 {@code fill}，所以做了两件事压成本：
 * <ul>
 *   <li>覆盖率量化成 {@link #LEVELS} 档，一条扫描线上<b>同档的连续像素合并成一次 fill</b>
 *       —— 一条直线通常每行只有两三次 fill；</li>
 *   <li>只遍历图元的包围盒，不遍历整屏。</li>
 * </ul>
 *
 * <p>颜色一律 {@code ARGB}；线宽、半径都是 GUI 像素（与 {@link GuiGraphics} 同一坐标系）。
 */
public final class UiVector {

    /** 覆盖率量化档数。档数越多越平滑，fill 次数也越多。24 档在 1 像素级看不出台阶。 */
    private static final int LEVELS = 24;

    /** 填充面积上限（像素）：超过就只画轮廓，避免贴脸的大面把帧率吃掉。 */
    private static final int FILL_AREA_LIMIT = 60000;

    private UiVector() {
    }

    // ============ 基础：按覆盖率写一条扫描线 ============

    /**
     * 把一行覆盖率写成若干个 fill（同档合并）。
     *
     * @param cov 从 {@code x0} 开始的覆盖率，长度即该行的像素数
     */
    private static void scanline(final GuiGraphics g, final int y, final int x0,
                                 final float[] cov, final int n, final int argb) {
        int i = 0;
        while (i < n) {
            final int level = quant(cov[i]);
            if (level == 0) {
                i++;
                continue;
            }
            int j = i + 1;
            while (j < n && quant(cov[j]) == level) {
                j++;
            }
            UiDraw.rect(g, x0 + i, y, j - i, 1, alphaScale(argb, level / (float) LEVELS));
            i = j;
        }
    }

    private static int quant(final float coverage) {
        final float c = coverage <= 0f ? 0f : (coverage >= 1f ? 1f : coverage);
        return Math.round(c * LEVELS);
    }

    private static int alphaScale(final int argb, final float mul) {
        final int a = Math.round(((argb >>> 24) & 0xFF) * mul);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    // ============ 直线 ============

    /**
     * 抗锯齿线段（带圆头）。
     *
     * <p>实现上是「胶囊 = 四边形 + 两个圆」，而不是「逐行扫整条线的包围盒」：
     * 后者对一条横跨屏幕的斜线要扫 400×240 个像素，而胶囊化之后每行只扫它自己那一小段跨度。
     * 标记会被画满整个屏幕高度，这个差别是 100 倍的量级。
     */
    public static void line(final GuiGraphics g, final double x0, final double y0,
                            final double x1, final double y1, final float width, final int argb) {
        if ((argb >>> 24) == 0 || width <= 0f) {
            return;
        }
        final double half = width * 0.5;
        final double dx = x1 - x0;
        final double dy = y1 - y0;
        final double len = Math.hypot(dx, dy);
        if (len < 1.0e-6) {
            disc(g, x0, y0, half, argb);
            return;
        }
        // 垂直于线段的方向，向两侧各推 half → 胶囊的主体
        final double nx = -dy / len * half;
        final double ny = dx / len * half;
        polygonFill(g, new double[] {x0 + nx, x1 + nx, x1 - nx, x0 - nx},
                new double[] {y0 + ny, y1 + ny, y1 - ny, y0 - ny}, argb);
        // 圆头：两端的半圆。半径很小，代价可以忽略
        disc(g, x0, y0, half, argb);
        disc(g, x1, y1, half, argb);
    }

    /** 折线；{@code closed} 为真时首尾相接。 */
    /**
     * **虚线** ✓（吸附提示 / 辅助线都用它 ✓ 用户要求 ✓）。
     *
     * <p>为什么放进库 ✗✓：虚线是"这是参考/提示、不是实体"的**通用视觉语言** ✓
     * 每个调用点各写一遍"分成几段"迟早不一致 ✗ ⇒ 库统一 ✓（短标签、折行那些也是这个道理 ✓）。
     *
     * @param dash 线段长（像素 ✓）@param gap 间隔长（像素 ✓）
     */
    // ================= 约束小字形 ✓（SolidWorks 的"图元旁的几何关系图标"✓）=================

    /** 字形种类 ✓（与 SketchSolver 的约束类型解耦 ✓ 免得库依赖求解器 ✗）。 */
    public static final int GLYPH_HORIZONTAL = 1;
    public static final int GLYPH_VERTICAL = 2;
    public static final int GLYPH_PARALLEL = 3;
    public static final int GLYPH_PERPENDICULAR = 4;
    public static final int GLYPH_EQUAL = 5;
    public static final int GLYPH_MIDPOINT = 6;
    public static final int GLYPH_COINCIDENT = 7;
    public static final int GLYPH_FIXED = 8;
    public static final int GLYPH_GRID = 9;

    /**
     * 在 {@code (cx, cy)} 处画一个**约束小字形** ✓（大小约 9px ✓）。
     *
     * <p>为什么放库里 ✗✓：字形是"同一套视觉语言"✓ —— 尺寸、关系、辅助线要看起来是一家人 ✓
     * 每个地方各画一套迟早风格漂移 ✗（短标签/折行/虚线都是这个道理 ✓）。
     *
     * @param kind 上面的 GLYPH_* 常量 ✓
     */
    public static void constraintGlyph(final GuiGraphics g, final double cx, final double cy,
                                       final int kind, final int argb) {
        final double r = 4.5;
        final float w = 1.4f;
        switch (kind) {
            case GLYPH_HORIZONTAL -> line(g, cx - r, cy, cx + r, cy, w, argb);
            case GLYPH_VERTICAL -> line(g, cx, cy - r, cx, cy + r, w, argb);
            case GLYPH_PARALLEL -> {
                line(g, cx - r, cy + r, cx + r, cy - r, w, argb);
                line(g, cx - r + 2.5, cy + r, cx + r + 2.5, cy - r, w, argb);
            }
            case GLYPH_PERPENDICULAR -> {
                line(g, cx - r, cy - r, cx - r, cy + r, w, argb);
                line(g, cx - r, cy + r, cx + r, cy + r, w, argb);
            }
            case GLYPH_EQUAL -> {
                line(g, cx - r, cy - 1.8, cx + r, cy - 1.8, w, argb);
                line(g, cx - r, cy + 1.8, cx + r, cy + 1.8, w, argb);
            }
            case GLYPH_MIDPOINT -> {
                line(g, cx - r, cy - r, cx - r, cy + r, w, argb);
                line(g, cx - r, cy, cx + r, cy, w, argb);
            }
            case GLYPH_COINCIDENT -> disc(g, cx, cy, 2.4, argb);
            case GLYPH_FIXED -> {
                roundRectOutline(g, (float) (cx - 3.5), (float) (cy - 3.5), 7f, 7f, 1.5f, 1.4f, argb);
            }
            case GLYPH_GRID -> {
                line(g, cx - r, cy, cx + r, cy, w, argb);
                line(g, cx, cy - r, cx, cy + r, w, argb);
                disc(g, cx, cy, 1.2, argb);
            }
            default -> {
                // 不认识的种类什么都不画 ✓（宁可少画，不画错的 ✓）
            }
        }
    }

    public static void dashedLine(final GuiGraphics g, final double x0, final double y0,
                                  final double x1, final double y1, final float width,
                                  final int argb, final double dash, final double gap) {
        final double dx = x1 - x0;
        final double dy = y1 - y0;
        final double len = Math.hypot(dx, dy);
        if (len < 1.0e-6 || dash <= 0.0) {
            return;
        }
        final double ux = dx / len;
        final double uy = dy / len;
        double at = 0.0;
        while (at < len) {
            final double end = Math.min(len, at + dash);
            line(g, x0 + ux * at, y0 + uy * at, x0 + ux * end, y0 + uy * end, width, argb);
            at = end + gap;
        }
    }
    public static void polyline(final GuiGraphics g, final double[] xs, final double[] ys,
                                final float width, final int argb, final boolean closed) {
        if (xs == null || xs.length < 2) {
            return;
        }
        final int last = closed ? xs.length : xs.length - 1;
        for (int i = 0; i < last; i++) {
            final int j = (i + 1) % xs.length;
            line(g, xs[i], ys[i], xs[j], ys[j], width, argb);
        }
    }

    // ============ 圆 / 圆角 ============

    /** 抗锯齿实心圆。 */
    public static void disc(final GuiGraphics g, final double cx, final double cy, final double r, final int argb) {
        if (r <= 0.0 || (argb >>> 24) == 0) {
            return;
        }
        final int minX = (int) Math.floor(cx - r - 1.0);
        final int maxX = (int) Math.ceil(cx + r + 1.0);
        final int minY = (int) Math.floor(cy - r - 1.0);
        final int maxY = (int) Math.ceil(cy + r + 1.0);
        final int n = maxX - minX + 1;
        final float[] cov = new float[n];
        for (int py = minY; py <= maxY; py++) {
            java.util.Arrays.fill(cov, 0f);
            boolean any = false;
            for (int px = minX; px <= maxX; px++) {
                final double d = Math.hypot(px + 0.5 - cx, py + 0.5 - cy);
                final double c = r + 0.5 - d;
                if (c > 0.0) {
                    cov[px - minX] = (float) Math.min(1.0, c);
                    any = true;
                }
            }
            if (any) {
                scanline(g, py, minX, cov, n, argb);
            }
        }
    }

    /** 抗锯齿圆环（{@code width} 是环的粗细）。 */
    public static void ring(final GuiGraphics g, final double cx, final double cy, final double r,
                            final double width, final int argb) {
        if (r <= 0.0 || width <= 0.0 || (argb >>> 24) == 0) {
            return;
        }
        final double outer = r + width * 0.5;
        final double inner = Math.max(0.0, r - width * 0.5);
        final int minX = (int) Math.floor(cx - outer - 1.0);
        final int maxX = (int) Math.ceil(cx + outer + 1.0);
        final int minY = (int) Math.floor(cy - outer - 1.0);
        final int maxY = (int) Math.ceil(cy + outer + 1.0);
        final int n = maxX - minX + 1;
        final float[] cov = new float[n];
        for (int py = minY; py <= maxY; py++) {
            java.util.Arrays.fill(cov, 0f);
            boolean any = false;
            for (int px = minX; px <= maxX; px++) {
                final double d = Math.hypot(px + 0.5 - cx, py + 0.5 - cy);
                final double signed = Math.max(d - outer, inner - d);
                final double c = signed + 0.5;
                if (c > 0.0) {
                    cov[px - minX] = (float) Math.min(1.0, c);
                    any = true;
                }
            }
            if (any) {
                scanline(g, py, minX, cov, n, argb);
            }
        }
    }

    /** 抗锯齿实心圆角矩形。 */
    public static void roundRect(final GuiGraphics g, final float x, final float y,
                                 final float w, final float h, final float r, final int argb) {
        if (w <= 0f || h <= 0f || (argb >>> 24) == 0) {
            return;
        }
        final float rr = Math.max(0f, Math.min(r, Math.min(w, h) * 0.5f));
        final int minX = (int) Math.floor(x - 1.0f);
        final int maxX = (int) Math.ceil(x + w + 1.0f);
        final int minY = (int) Math.floor(y - 1.0f);
        final int maxY = (int) Math.ceil(y + h + 1.0f);
        final int n = maxX - minX + 1;
        final float[] cov = new float[n];
        for (int py = minY; py <= maxY; py++) {
            java.util.Arrays.fill(cov, 0f);
            boolean any = false;
            for (int px = minX; px <= maxX; px++) {
                final double sd = roundRectSdf(px + 0.5, py + 0.5, x, y, w, h, rr);
                final double c = 0.5 - sd;
                if (c > 0.0) {
                    cov[px - minX] = (float) Math.min(1.0, c);
                    any = true;
                }
            }
            if (any) {
                scanline(g, py, minX, cov, n, argb);
            }
        }
    }

    /** 抗锯齿圆角矩形描边。 */
    public static void roundRectOutline(final GuiGraphics g, final float x, final float y,
                                        final float w, final float h, final float r,
                                        final float width, final int argb) {
        if (w <= 0f || h <= 0f || width <= 0f || (argb >>> 24) == 0) {
            return;
        }
        final float rr = Math.max(0f, Math.min(r, Math.min(w, h) * 0.5f));
        final int minX = (int) Math.floor(x - width - 1.0f);
        final int maxX = (int) Math.ceil(x + w + width + 1.0f);
        final int minY = (int) Math.floor(y - width - 1.0f);
        final int maxY = (int) Math.ceil(y + h + width + 1.0f);
        final int n = maxX - minX + 1;
        final float[] cov = new float[n];
        for (int py = minY; py <= maxY; py++) {
            java.util.Arrays.fill(cov, 0f);
            boolean any = false;
            for (int px = minX; px <= maxX; px++) {
                final double sd = roundRectSdf(px + 0.5, py + 0.5, x, y, w, h, rr);
                final double c = width * 0.5 - Math.abs(sd) + 0.5;
                if (c > 0.0) {
                    cov[px - minX] = (float) Math.min(1.0, c);
                    any = true;
                }
            }
            if (any) {
                scanline(g, py, minX, cov, n, argb);
            }
        }
    }

    /** 圆角矩形的有符号距离（内部为负）。 */
    private static double roundRectSdf(final double px, final double py,
                                       final float x, final float y, final float w, final float h,
                                       final float r) {
        final double cx = x + w * 0.5;
        final double cy = y + h * 0.5;
        final double qx = Math.abs(px - cx) - (w * 0.5 - r);
        final double qy = Math.abs(py - cy) - (h * 0.5 - r);
        final double outside = Math.hypot(Math.max(qx, 0.0), Math.max(qy, 0.0));
        return outside + Math.min(Math.max(qx, qy), 0.0) - r;
    }

    // ============ 多边形 ============

    /** 抗锯齿实心多边形（凸/凹都行，用扫描线 + 4 行超采样）。 */
    public static void polygonFill(final GuiGraphics g, final double[] xs, final double[] ys, final int argb) {
        if (xs == null || xs.length < 3 || (argb >>> 24) == 0) {
            return;
        }
        double minYd = Double.MAX_VALUE;
        double maxYd = -Double.MAX_VALUE;
        double minXd = Double.MAX_VALUE;
        double maxXd = -Double.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            minYd = Math.min(minYd, ys[i]);
            maxYd = Math.max(maxYd, ys[i]);
            minXd = Math.min(minXd, xs[i]);
            maxXd = Math.max(maxXd, xs[i]);
        }
        final int minX = (int) Math.floor(minXd) - 1;
        final int maxX = (int) Math.ceil(maxXd) + 1;
        final int minY = (int) Math.floor(minYd);
        final int maxY = (int) Math.ceil(maxYd);
        final int n = maxX - minX + 1;
        if (n <= 0) {
            return;
        }
        final int subsamples = 4;
        final float[] cov = new float[n];
        final double[] xcs = new double[16];
        for (int py = minY; py <= maxY; py++) {
            java.util.Arrays.fill(cov, 0f);
            boolean any = false;
            for (int s = 0; s < subsamples; s++) {
                final double sy = py + (s + 0.5) / subsamples;
                int count = 0;
                for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                    final double yi = ys[i];
                    final double yj = ys[j];
                    if ((yi > sy) == (yj > sy)) {
                        continue;
                    }
                    if (count < xcs.length) {
                        xcs[count++] = xs[i] + (sy - yi) / (yj - yi) * (xs[j] - xs[i]);
                    }
                }
                if (count < 2) {
                    continue;
                }
                java.util.Arrays.sort(xcs, 0, count);
                for (int k = 0; k + 1 < count; k += 2) {
                    final double xa = xcs[k];
                    final double xb = xcs[k + 1];
                    if (xb <= xa) {
                        continue;
                    }
                    final int ia = (int) Math.floor(xa);
                    final int ib = (int) Math.ceil(xb) - 1;
                    final float weight = 1.0f / subsamples;
                    for (int px = ia; px <= ib; px++) {
                        if (px < minX || px > maxX) {
                            continue;
                        }
                        final double left = Math.max(xa, px);
                        final double right = Math.min(xb, px + 1.0);
                        final double cover = Math.max(0.0, right - left);
                        if (cover > 0.0) {
                            cov[px - minX] += (float) cover * weight;
                            any = true;
                        }
                    }
                }
            }
            if (any) {
                scanline(g, py, minX, cov, n, argb);
            }
        }
    }

    /**
     * 带上限的实心多边形：包围盒面积超过 {@link #FILL_AREA_LIMIT} 就什么都不画。
     *
     * <p>给「面的淡淡填充」这类用法：镜头贴到面上时那个多边形会铺满整屏，
     * 逐像素填充的代价与面积成正比，而此时「是哪一面」本来就一眼可辨，填充没有信息量。
     * 线段胶囊<b>不能</b>用这个（斜线的包围盒很大但每行跨度很窄，代价与长度成正比，不该被拦）。
     */
    public static void polygonFillCapped(final GuiGraphics g, final double[] xs, final double[] ys, final int argb) {
        if (xs == null || xs.length < 3 || (argb >>> 24) == 0) {
            return;
        }
        double minYd = Double.MAX_VALUE;
        double maxYd = -Double.MAX_VALUE;
        double minXd = Double.MAX_VALUE;
        double maxXd = -Double.MAX_VALUE;
        for (int i = 0; i < xs.length; i++) {
            minYd = Math.min(minYd, ys[i]);
            maxYd = Math.max(maxYd, ys[i]);
            minXd = Math.min(minXd, xs[i]);
            maxXd = Math.max(maxXd, xs[i]);
        }
        if ((maxXd - minXd + 2.0) * (maxYd - minYd + 2.0) > FILL_AREA_LIMIT) {
            return;
        }
        polygonFill(g, xs, ys, argb);
    }

    /** 工程标记用的十字准星。 */    public static void cross(final GuiGraphics g, final double cx, final double cy,
                             final double arm, final float width, final int argb) {
        line(g, cx - arm, cy, cx + arm, cy, width, argb);
        line(g, cx, cy - arm, cx, cy + arm, width, argb);
    }

    /** 捕捉点方块（SolidWorks 的角点标记）：实心方块 + 深色描边。 */
    public static void snapPoint(final GuiGraphics g, final double cx, final double cy,
                                 final double half, final int fill, final int edge) {
        roundRect(g, (float) (cx - half - 1.0), (float) (cy - half - 1.0),
                (float) (half * 2 + 2), (float) (half * 2 + 2), 1.0f, edge);
        roundRect(g, (float) (cx - half), (float) (cy - half),
                (float) (half * 2), (float) (half * 2), 0.5f, fill);
    }
}
