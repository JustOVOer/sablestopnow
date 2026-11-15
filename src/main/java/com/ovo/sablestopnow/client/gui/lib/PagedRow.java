package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * **分页按键行**：一排竖向长方形按键，放不下时左右出现 {@code <} {@code >} 翻页。
 *
 * <p>用户要求（本轮）：特征按键改成竖向长方形（上动图、下文字）；一排排不下就用
 * {@code <}/{@code >} 换页，并且换页要有动画。
 *
 * <p>动画做法：翻页时把整页按键的 x 坐标整体偏移一个“页宽”，之后每帧向 0 收敛
 * （指数收敛，约 0.2 秒），布局每帧重新算 x ⇒ 能看到新一页从侧面滑入。
 * 箭头本身固定不动。
 */
public class PagedRow extends Container {

    private final int itemW;
    private final int itemH;
    private final int gap;
    private final int arrowW;
    private final List<Widget> items = new ArrayList<>();

    private final Button prev;
    private final Button next;

    private int page;
    private int lastPages = 1;
    private double slideOffset;
    @Nullable
    private IntConsumer onPageChanged;

    public PagedRow(final int itemW, final int itemH, final int gap, final int arrowW) {
        this.itemW = itemW;
        this.itemH = itemH;
        this.gap = gap;
        this.arrowW = arrowW;
        this.prefW = FILL;
        this.prefH = itemH;
        this.layout = this::arrange;

        this.prev = new Button(Component.literal("<"), () -> turn(-1));
        this.prev.size(arrowW, itemH);
        this.next = new Button(Component.literal(">"), () -> turn(1));
        this.next.size(arrowW, itemH);
        add(prev, next);
        prev.visible = false;
        next.visible = false;
    }

    /** 添加一个按键（保持顺序）✓。 */
    public PagedRow addItem(final Widget widget) {
        items.add(widget);
        add(widget);
        return this;
    }

    public PagedRow page(final int value) {
        this.page = Math.max(0, value);
        return this;
    }

    public int page() {
        return page;
    }

    public PagedRow onPageChanged(@Nullable final IntConsumer listener) {
        this.onPageChanged = listener;
        return this;
    }

    private void turn(final int dir) {
        final int target = page + dir;
        if (target < 0 || target >= lastPages) {
            // 到头了：仍给一点“顶回来”的反馈，但不翻页
            slideOffset = -dir * 6;
            return;
        }
        page = target;
        // 新一页从侧面滑入：下一页从右边进，上一页从左边进
        slideOffset = dir * (itemW + gap) * 2.0;
        if (onPageChanged != null) {
            onPageChanged.accept(page);
        }
    }

    private void arrange(final Container parent, final UiContext ctx) {
        final int avail = Math.max(1, parent.innerW());
        final int per = Math.max(1, (avail - 2 * (arrowW + gap)) / (itemW + gap));
        final int pages = Math.max(1, (items.size() + per - 1) / per);
        lastPages = pages;
        if (page >= pages) {
            page = pages - 1;
        }
        if (page < 0) {
            page = 0;
        }
        final boolean arrows = pages > 1;
        prev.visible = arrows;
        next.visible = arrows;

        final int count = Math.min(per, items.size() - page * per);
        // 用户要求：不需要分页时所有按键**靠左**；分页时也从左箭头后开始靠左排。
        final int startX = arrows
                ? parent.innerX() + arrowW + gap
                : parent.innerX();
        final int y = parent.innerY() + Math.max(0, (parent.innerH() - itemH) / 2);
        final int shift = (int) Math.round(slideOffset);

        for (int i = 0; i < items.size(); i++) {
            final Widget widget = items.get(i);
            final boolean show = i >= page * per && i < page * per + per;
            widget.visible = show;
            if (!show) {
                continue;
            }
            final int slot = i - page * per;
            widget.x = startX + slot * (itemW + gap) + shift;
            widget.y = y;
            widget.w = itemW;
            widget.h = itemH;
        }

        if (arrows) {
            prev.x = parent.innerX();
            prev.y = y;
            prev.w = arrowW;
            prev.h = itemH;
            next.x = parent.innerX() + avail - arrowW;
            next.y = y;
            next.w = arrowW;
            next.h = itemH;
        }
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        if (Math.abs(slideOffset) > 0.5) {
            slideOffset *= Math.max(0.0, 1.0 - Math.min(0.5, ctx.dt) * 18.0);
            if (Math.abs(slideOffset) <= 0.5) {
                slideOffset = 0.0;
            }
        }
        super.update(ctx, dt);
    }
}
