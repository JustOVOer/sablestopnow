package com.ovo.sablestopnow.client.gui.lib;

/**
 * 布局器。所有布局都是「按父容器内容区摆放子控件」的纯函数，每帧调用。
 */
public final class Layouts {

    private Layouts() {
    }

    /** 停靠边。 */
    public enum Dock {
        TOP, BOTTOM, LEFT, RIGHT, CENTER
    }

    public interface Layout {
        void arrange(Container parent, UiContext ctx);
    }

    // ============ 绝对 ============

    /** 子控件保留自己设定的 x/y（相对父容器内容区）。 */
    public static Layout absolute() {
        return (parent, ctx) -> {
            for (final Widget child : parent.children) {
                if (!child.visible) {
                    continue;
                }
                final int cw = child.prefW == Widget.FILL ? parent.innerW() : child.wantW(ctx);
                final int ch = child.prefH == Widget.FILL ? parent.innerH() : child.wantH(ctx);
                child.x = parent.innerX() + child.marginLeft;
                child.y = parent.innerY() + child.marginTop;
                child.w = Math.max(0, cw);
                child.h = Math.max(0, ch);
            }
        };
    }

    // ============ 竖排 / 横排 ============

    public static Layout vbox() {
        return vbox(0, 2);
    }

    public static Layout vbox(final int pad, final int gap) {
        return (parent, ctx) -> {
            final int width = parent.innerW() - pad * 2;
            int fixed = 0;
            int fillCount = 0;
            int visible = 0;
            for (final Widget child : parent.children) {
                if (!child.visible) {
                    continue;
                }
                visible++;
                if (child.prefH == Widget.FILL) {
                    fillCount++;
                } else {
                    fixed += child.wantH(ctx) + child.marginTop + child.marginBottom;
                }
            }
            final int free = Math.max(0, parent.innerH() - pad * 2 - fixed - gap * Math.max(0, visible - 1));
            final int each = fillCount > 0 ? free / fillCount : 0;
            int y = parent.innerY() + pad;
            int rowIndex = 0;
            for (final Widget child : parent.children) {
                if (!child.visible) {
                    continue;
                }
                final int ch = child.prefH == Widget.FILL ? each : child.wantH(ctx);
                final int cw = child.prefW == Widget.FILL || child.prefW == Widget.AUTO
                        ? width - child.marginLeft - child.marginRight : child.wantW(ctx);
                child.x = parent.innerX() + pad + child.marginLeft;
                child.y = y + child.marginTop;
                child.w = Math.max(0, cw);
                child.h = Math.max(0, ch);
                y += child.marginTop + ch + child.marginBottom + gap;
            }
        };
    }

    public static Layout hbox() {
        return hbox(0, 2);
    }

    public static Layout hbox(final int pad, final int gap) {
        return (parent, ctx) -> {
            final int height = parent.innerH() - pad * 2;
            int fixed = 0;
            int fillCount = 0;
            int visible = 0;
            for (final Widget child : parent.children) {
                if (!child.visible) {
                    continue;
                }
                visible++;
                if (child.prefW == Widget.FILL) {
                    fillCount++;
                } else {
                    fixed += child.wantW(ctx) + child.marginLeft + child.marginRight;
                }
            }
            final int free = Math.max(0, parent.innerW() - pad * 2 - fixed - gap * Math.max(0, visible - 1));
            final int each = fillCount > 0 ? free / fillCount : 0;
            int x = parent.innerX() + pad;
            for (final Widget child : parent.children) {
                if (!child.visible) {
                    continue;
                }
                final int cw = child.prefW == Widget.FILL ? each : child.wantW(ctx);
                final int ch = child.prefH == Widget.FILL || child.prefH == Widget.AUTO
                        ? height - child.marginTop - child.marginBottom : child.wantH(ctx);
                child.x = x + child.marginLeft;
                child.y = parent.innerY() + pad + child.marginTop;
                child.w = Math.max(0, cw);
                child.h = Math.max(0, ch);
                x += child.marginLeft + cw + child.marginRight + gap;
            }
        };
    }

    /**
     * 横排，但<b>保证不超出可用宽度</b>：先按完整标签量一遍，放不下就让控件整排换短标签
     * （见 {@link Widget#setCompact(boolean)}）；仍然放不下就按比例压缩每个控件的宽度
     * （文字由控件自己画省略号，至少不会再有控件被挤出屏幕）。
     *
     * <p>判定必须<b>先复位成长标签再量</b>：否则上一帧切短后这一帧量出来「放得下」，
     * 又切回长标签，如此来回抖动。
     */
    public static Layout hboxFit(final int pad, final int gap) {
        return (parent, ctx) -> {
            int visible = 0;
            int natural = 0;
            for (final Widget child : parent.children) {
                if (!child.visible) {
                    continue;
                }
                visible++;
                child.setCompact(false);
                natural += child.wantW(ctx) + child.marginLeft + child.marginRight;
            }
            natural += gap * Math.max(0, visible - 1);
            final int avail = Math.max(0, parent.innerW() - pad * 2);
            if (natural > avail) {
                for (final Widget child : parent.children) {
                    child.setCompact(true);
                }
            }
            // 第三档：短标签还是放不下（按钮特别多时）就等比压窄。
            int compacted = 0;
            for (final Widget child : parent.children) {
                if (child.visible) {
                    compacted += child.wantW(ctx) + child.marginLeft + child.marginRight;
                }
            }
            compacted += gap * Math.max(0, visible - 1);
            if (compacted > avail && compacted > 0) {
                final double scale = (double) avail / (double) compacted;
                final int[] saved = new int[parent.children.size()];
                for (int i = 0; i < parent.children.size(); i++) {
                    final Widget child = parent.children.get(i);
                    saved[i] = child.prefW;
                    // FILL 的子控件不能在这里改成固定宽度：wantW 对 FILL 返回 0，
                    // 会被压成 1 像素（撑满型的控件在「压窄」这一档里应当保持撑满）。
                    if (child.visible && child.prefW != Widget.FILL) {
                        final int base = child.prefW == Widget.AUTO ? child.wantW(ctx) : child.prefW;
                        child.prefW = Math.max(1, (int) Math.floor((base + child.marginLeft
                                + child.marginRight) * scale) - child.marginLeft - child.marginRight);
                    }
                }
                hbox(pad, gap).arrange(parent, ctx);
                for (int i = 0; i < parent.children.size(); i++) {
                    parent.children.get(i).prefW = saved[i];
                }
                return;
            }
            hbox(pad, gap).arrange(parent, ctx);
        };
    }

    // ============ 停靠 ============
    /**
     * 停靠布局：按加入顺序依次从四条边切走空间，剩下的是中心区。
     *
     * <p>上/左边栏、状态栏、树状图的经典布局；带 {@code fill} 标记的子控件平分剩余空间。
     */
    /**
     * **自动换行的水平排列** ✓（新要求：排成一行就难免遮盖 ✗ ⇒ 由库折行 ✓）。
     *
     * <p>与 {@link #hbox} 的区别：一行放不下时**折到下一行** ✓，
     * 而不是把每个控件压到 0 宽 ✗（那正是"五种图元挤一行 ⇒ 看不见/互相遮盖"✗ 的直接原因 ✓）。
     *
     * <p>并且**布局自己把容器高度算好** ✓（写回 {@code parent.prefH}）⇒
     * 写界面的人不需要知道"这一行会不会折、折几行"✗✓ —— 这就是"库负责、调用点零负担" ✓。
     *
     * @param pad    上下左右内边距 ✓
     * @param gap    同一行内的间距 ✓
     * @param rowGap 行与行之间的间距 ✓
     */
    public static Layout hboxWrap(final int pad, final int gap, final int rowGap) {
        return (parent, ctx) -> {
            final int avail = Math.max(1, parent.innerW() - pad * 2);
            final java.util.List<java.util.List<Widget>> rows = new java.util.ArrayList<>();
            java.util.List<Widget> row = new java.util.ArrayList<>();
            int used = 0;
            for (final Widget child : parent.children) {
                if (!child.visible) {
                    continue;
                }
                final int need = child.marginLeft + child.wantW(ctx) + child.marginRight;
                final int withGap = row.isEmpty() ? need : need + gap;
                if (!row.isEmpty() && used + withGap > avail) {
                    rows.add(row);
                    row = new java.util.ArrayList<>();
                    used = 0;
                    row.add(child);
                    used = need;
                    continue;
                }
                row.add(child);
                used += withGap;
            }
            if (!row.isEmpty()) {
                rows.add(row);
            }
            final int rowCount = Math.max(1, rows.size());
            // ★★ 行高由【孩子的需求】决定 ✓✓，**不是**"容器高度 ÷ 行数" ✗。
            //   前者保证：两行永远不会互相压住 ✓（实测事故：容器 13px、两行 ⇒ 每行 5px ✗，
            //   而按钮自己要 12px ✗ ⇒ 第一行 y=119..131 与第二行 y=122 重叠 9px ✓ —— 见 F9 报告 ✓）。
            //   若孩子总高超过容器 ⇒ 让内容【溢出】（由父裁剪 ✓）而不是重叠 ✓✓
            //   —— "绝不重叠"是库的硬保证 ✓；"放不放得下"由上层决定 ✓（放不下会被 UiLint 点名 ✓）。
            int naturalH = 0;
            final int[] rowHeights = new int[rows.size()];
            for (int ri = 0; ri < rows.size(); ri++) {
                int hWant = 1;
                for (final Widget child : rows.get(ri)) {
                    hWant = Math.max(hWant, child.marginTop + child.wantH(ctx) + child.marginBottom);
                }
                rowHeights[ri] = hWant;
                naturalH += hWant;
            }
            naturalH += rowGap * (rowCount - 1);
            // ★ 高度夹取 ✓✓（修"位置不对"✗）：
            //   已实测：vbox 用 child.wantH(ctx) 定高度（Layouts L70 ✓），而它在本行自己的布局【之前】跑
            //   ⇒ 它拿到的是**上一帧**的 prefH ✗ ⇒ 两行内容会被画到容器之外（视觉上就是"位置不对"✗）。
            //   这里按【分配到的可用高】等比压缩各行高 ⇒ 内容**始终留在容器内** ✓ 且绝不重叠 ✓；
            //   同时仍把自然高度写回 prefH ✓ ⇒ 父布局能长高时会自动变好 ✓。
            final int availH = Math.max(1, parent.innerH() - pad * 2);
            if (naturalH > availH) {
                final int gaps = rowGap * (rowCount - 1);
                final int forRows = Math.max(rowCount, availH - gaps);
                final double k = (double) forRows / (double) Math.max(1, naturalH - gaps);
                for (int ri = 0; ri < rowHeights.length; ri++) {
                    rowHeights[ri] = Math.max(1, (int) Math.floor(rowHeights[ri] * k));
                }
            }
            final int wanted = pad * 2 + naturalH;
            // 把"我需要多高"写回父容器 ✓（AUTO 才写 ✓ 不覆盖调用方的显式设置 ✗）
            if (parent.prefH == Widget.AUTO) {
                parent.prefH = wanted;
            }
            int y = parent.innerY() + pad;
            int rowIndex = 0;
            for (final java.util.List<Widget> line : rows) {
                int fixed = 0;
                int fills = 0;
                for (final Widget child : line) {
                    if (child.prefW == Widget.FILL) {
                        fills++;
                    } else {
                        fixed += child.wantW(ctx) + child.marginLeft + child.marginRight;
                    }
                }
                final int free = Math.max(0, avail - fixed - gap * Math.max(0, line.size() - 1));
                final int each = fills > 0 ? free / fills : 0;
                int x = parent.innerX() + pad;
                for (final Widget child : line) {
                    final int cw = child.prefW == Widget.FILL ? each : child.wantW(ctx);
                    final int ch = child.prefH == Widget.FILL || child.prefH == Widget.AUTO
                            ? rowHeights[rowIndex] - child.marginTop - child.marginBottom : child.wantH(ctx);
                    // ★ 分配宽 < 自然宽 ⇒ 让控件进入"紧凑"形态 ✓✓
                    //   （Button 紧凑时会**自动**用 <key>.short ✓ ⇒ "圆弧（圆心/起点/终点）" 在 26px 里
                    //    自动变成 1 字的「弧」✓；以前只有 hboxFit 会调 setCompact ✗ 换成 hboxWrap 后就没人调了 ✗✓）
                    child.setCompact(child.prefW != Widget.FILL && cw < child.wantW(ctx));
                    child.x = x + child.marginLeft;
                    child.y = y + child.marginTop;
                    child.w = Math.max(0, cw);
                    child.h = Math.max(0, ch);
                    x += child.marginLeft + cw + child.marginRight + gap;
                }
                y += rowHeights[rowIndex] + rowGap;
                rowIndex++;
            }
        };
    }
    public static Layout dock() {
        return dock(0, 0);
    }

    public static Layout dock(final int pad, final int gap) {
        return (parent, ctx) -> {
            int left = parent.innerX() + pad;
            int top = parent.innerY() + pad;
            int right = parent.innerX() + parent.innerW() - pad;
            int bottom = parent.innerY() + parent.innerH() - pad;
            final java.util.List<Widget> fillers = new java.util.ArrayList<>();
            for (final Widget child : parent.children) {
                if (!child.visible) {
                    continue;
                }
                final Dock d = child.dock;
                if (d == null || d == Dock.CENTER) {
                    fillers.add(child);
                    continue;
                }
                switch (d) {
                    case TOP -> {
                        final int hgt = child.wantH(ctx);
                        child.x = left + child.marginLeft;
                        child.y = top + child.marginTop;
                        child.w = Math.max(0, right - left - child.marginLeft - child.marginRight);
                        child.h = Math.max(0, hgt);
                        top += hgt + gap + child.marginTop + child.marginBottom;
                    }
                    case BOTTOM -> {
                        final int hgt = child.wantH(ctx);
                        child.h = Math.max(0, hgt);
                        child.x = left + child.marginLeft;
                        child.y = bottom - hgt - child.marginBottom;
                        child.w = Math.max(0, right - left - child.marginLeft - child.marginRight);
                        bottom -= hgt + gap + child.marginTop + child.marginBottom;
                    }
                    case LEFT -> {
                        final int wid = child.wantW(ctx);
                        child.x = left + child.marginLeft;
                        child.y = top + child.marginTop;
                        child.w = Math.max(0, wid);
                        child.h = Math.max(0, bottom - top - child.marginTop - child.marginBottom);
                        left += wid + gap + child.marginLeft + child.marginRight;
                    }
                    case RIGHT -> {
                        final int wid = child.wantW(ctx);
                        child.w = Math.max(0, wid);
                        child.x = right - wid - child.marginRight;
                        child.y = top + child.marginTop;
                        child.h = Math.max(0, bottom - top - child.marginTop - child.marginBottom);
                        right -= wid + gap + child.marginLeft + child.marginRight;
                    }
                    default -> fillers.add(child);
                }
            }
            if (fillers.isEmpty()) {
                return;
            }
            final int eachH = Math.max(0, (bottom - top - gap * (fillers.size() - 1)) / fillers.size());
            for (int i = 0; i < fillers.size(); i++) {
                final Widget child = fillers.get(i);
                if (child.prefW == Widget.AUTO) {
                    child.measure(ctx);
                }
                child.x = left + child.marginLeft;
                child.y = top + i * (eachH + gap) + child.marginTop;
                child.w = Math.max(0, right - left - child.marginLeft - child.marginRight);
                child.h = Math.max(0, eachH - child.marginTop - child.marginBottom);
            }
        };
    }

    // ============ HUD ============

    /**
     * HUD 布局：把子控件摆到某个角，并自动避开别的覆盖层已经占用的矩形。
     *
     * <p>为什么需要它：HUD 与模型模式/配置界面是两套互相独立的界面，硬贴在左上角一定会互相压
     * （模型模式的左边栏一开就盖住模式横幅）。避让结果由 {@link UiDock#place} 给出，这里的职责只是
     * 「每帧把位置写回去」，所以 HUD 控件自己不用知道屏幕尺寸，也不用知道别人占了哪儿。
     *
     * <p>约定：HUD 层只放一个整块（多块会各自避让、彼此重叠）；要放多块就多开一层。
     */
    public static Layout hud(final UiDock.Corner corner, final int margin) {
        return (parent, ctx) -> {
            for (final Widget child : parent.children) {
                if (!child.visible) {
                    continue;
                }
                final int w = child.prefW == Widget.FILL ? parent.innerW() : child.wantW(ctx);
                final int h = child.prefH == Widget.FILL ? parent.innerH() : child.wantH(ctx);
                final int[] pos = UiDock.place(w, h, corner, margin);
                child.x = pos[0];
                child.y = pos[1];
                child.w = Math.max(0, w);
                child.h = Math.max(0, h);
            }
        };
    }

    // ============ 网格 ============

    public static Layout grid(final int cols, final int gap) {
        return (parent, ctx) -> {
            final int columns = Math.max(1, cols);
            final int cw = Math.max(0, (parent.innerW() - gap * (columns - 1)) / columns);
            int x = parent.innerX();
            int y = parent.innerY();
            int rowH = 0;
            int col = 0;
            for (final Widget child : parent.children) {
                if (!child.visible) {
                    continue;
                }
                final int ch = child.prefH == Widget.FILL ? 0 : child.wantH(ctx);
                child.x = x;
                child.y = y;
                child.w = child.prefW == Widget.FILL ? cw : Math.min(cw, child.wantW(ctx));
                child.h = ch;
                rowH = Math.max(rowH, ch);
                col++;
                if (col >= columns) {
                    col = 0;
                    x = parent.innerX();
                    y += rowH + gap;
                    rowH = 0;
                } else {
                    x += cw + gap;
                }
            }
        };
    }

    // ============ 层叠 ============

    /** 所有子控件都铺满父容器（层叠）。 */
    public static Layout stack() {
        return (parent, ctx) -> {
            for (final Widget child : parent.children) {
                child.x = parent.innerX();
                child.y = parent.innerY();
                child.w = parent.innerW();
                child.h = parent.innerH();
            }
        };
    }
}
