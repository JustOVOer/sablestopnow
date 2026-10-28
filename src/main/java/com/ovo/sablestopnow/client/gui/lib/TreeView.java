package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 树状图：展开/收起带动画，行可自由塞控件（复选框、按钮、输入框）。
 *
 * <p>行的内容由 {@link RowBuilder} 现场搭；结构变化时调 {@link #refresh()}。
 * 关键点：<b>行控件按 key 复用</b>，所以刷新数据不会打断正在播放的动画，
 * 也不会把行里输入框的内容清掉 —— 行里的控件因此应当用 supplier 读实时数据。
 */
public class TreeView extends Container {

    /** 树节点。 */
    public static class TreeItem {
        public final String key;
        @Nullable
        public Object data;
        public final List<TreeItem> children = new ArrayList<>();
        @Nullable
        public TreeItem parent;
        public boolean expanded = true;
        public boolean selectable = true;
        public boolean highlight;
        public Component label = Component.empty();
        public int depth;
        /** 展开动画：0 = 收起，1 = 展开。 */
        public final Anim open = Anim.of(1.0).speed(15.0);
        /** 选中高亮动画。 */
        public final Anim selectAnim = Anim.of(0.0).speed(20.0);

        public TreeItem(final String key) {
            this.key = key;
        }

        public TreeItem label(final Component value) {
            this.label = value;
            return this;
        }

        public TreeItem label(final String translationKey) {
            this.label = Component.translatable(translationKey);
            return this;
        }

        public TreeItem child(final TreeItem child) {
            child.parent = this;
            children.add(child);
            // ★ 用户要求：树**默认全折叠** ✓（字段默认是 true ✗ 于是每次打开都是全展开 ✗）
            //   只有用户手动展开过的节点才保持展开 ✓（在会话内 ✓）
            child.expanded = false;
            return this;
        }

        public boolean leaf() {
            return children.isEmpty();
        }
    }

    /** 行内容构建器。 */
    public interface RowBuilder {
        void build(TreeItem item, Container row);
    }

    private final List<TreeItem> roots = new ArrayList<>();
    @Nullable
    public RowBuilder rowBuilder;
    @Nullable
    public Consumer<TreeItem> onSelect;
    /** 右键点击行。 */
    @Nullable
    public Consumer<TreeItem> onSecondary;
    @Nullable
    public TreeItem selected;

    public int rowH = 13;
    public int indent = 8;
    public int scrollbarW = 3;
    public boolean showRoot = true;

    public double scroll;
    public double scrollTarget;
    private double contentHeight;

    private static final class Row {
        TreeItem item;
        RowWidget widget;
        double y;
        double h;
        double open;
    }

    private final Map<String, Row> rowMap = new HashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private boolean dirty = true;

    public TreeView() {
        this.clip = true;
        this.prefW = FILL;
        this.prefH = FILL;
        // 行位置由 TreeView 自己算，容器布局器不参与
        this.layout = (parent, ctx) -> {
        };
    }

    // ============ 结构 ============

    public TreeItem addRoot(final TreeItem item) {
        roots.add(item);
        refresh();
        return item;
    }

    public void setRoots(final List<TreeItem> items) {
        roots.clear();
        roots.addAll(items);
        refresh();
    }

    public List<TreeItem> roots() {
        return roots;
    }

    /** 当前铺出来的行数（含被折叠祖先压成 0 高度的那些）。诊断用。 */
    public int rowCount() {
        // 诊断必须反映「现在这一帧」的行数：dirty 时先补一次重建，
        // 否则调用方拿到的可能是建树前/重建前的旧数字（曾导致日志显示 rows=34 而屏幕空白）。
        if (dirty) {
            rebuild();
        }
        return rows.size();
    }

    public void clear() {
        roots.clear();
        refresh();
    }

    public void refresh() {
        dirty = true;
    }

    public void expandAll(final boolean expanded) {
        for (final TreeItem root : roots) {
            setExpandedRecursive(root, expanded);
        }
    }

    private static void setExpandedRecursive(final TreeItem item, final boolean expanded) {
        item.expanded = expanded;
        for (final TreeItem child : item.children) {
            setExpandedRecursive(child, expanded);
        }
    }

    // ============ 行构建 ============

    private void rebuild() {
        rows.clear();
        // 行的控件集合完全由 rows 决定，重建时先清空 —— 否则旧行会留在 children 里继续画/抢命中
        children.clear();
        final List<String> alive = new ArrayList<>();
        for (final TreeItem root : roots) {
            collect(root, 0, alive);
        }
        rowMap.keySet().removeIf(key -> !alive.contains(key));
        dirty = false;
    }

    private void collect(final TreeItem item, final int depth, final List<String> alive) {
        item.depth = depth;
        alive.add(item.key);
        Row row = rowMap.get(item.key);
        if (row == null) {
            row = new Row();
            row.item = item;
            row.widget = new RowWidget(this, item);
            row.widget.parent = this;
            if (rowBuilder != null) {
                rowBuilder.build(item, row.widget);
            }
            rowMap.put(item.key, row);
        } else {
            // 复用旧行控件：必须把 item 换成新的那个 ——
            // 展开状态、动画值都挂在 item 上，留着旧实例会出现「点了箭头不动」
            // （箭头里持有的是旧 item，而 update 读的是 row.item）。
            row.item = item;
            row.widget.item = item;
        }
        // ⚠ 必须登记进 children：不然 Container 的渲染与命中都看不到这些行
        //（这正是「树有数据却一片空白、且点不动」的原因）。
        children.add(row.widget);
        rows.add(row);
        for (final TreeItem child : item.children) {
            collect(child, depth + 1, alive);
        }
    }

    /** 让某个节点滚入可视区。 */
    public void ensureVisible(final TreeItem item) {
        for (final Row row : rows) {
            if (row.item == item) {
                final double top = row.y - (y + padTop);
                final double bottom = top + row.h;
                final double view = h - padTop - padBottom;
                if (top < scroll) {
                    scrollTarget = top;
                } else if (bottom > scroll + view) {
                    scrollTarget = bottom - view;
                }
                return;
            }
        }
    }

    public void selectItem(@Nullable final TreeItem item) {
        this.selected = item;
        if (item != null) {
            ensureVisible(item);
        }
    }

    @Nullable
    public TreeItem findItem(final String key) {
        for (final Row row : rows) {
            if (row.item.key.equals(key)) {
                return row.item;
            }
        }
        return null;
    }

    // ============ 布局 ============

    @Override
    public void arrange(final UiContext ctx) {
        if (dirty) {
            rebuild();
        }
        double cursor = y + padTop - scroll;
        for (final Row row : rows) {
            // ⚠ 只乘「祖先」的展开度，**不乘自己的**：
            // 一个节点收起时，它自己的行必须留着（那是点回去的地方），要收掉的是它的子孙。
            // 早先写成「含自己」，于是默认收起的所有模型行高度都变成 0 —— 整棵树看起来是空的。
            double factor = 1.0;
            for (TreeItem p = row.item.parent; p != null; p = p.parent) {
                factor *= p.open.value();
            }
            row.open = factor;
            row.h = rowH * factor;
            row.y = cursor + (rowH - row.h) / 2.0;
            cursor += row.h;
        }
        contentHeight = Math.max(0, cursor - (y + padTop - scroll) + padBottom);
        final double max = Math.max(0, contentHeight - (h - padTop - padBottom));
        scrollTarget = Math.max(0, Math.min(max, scrollTarget));
        if (scroll > max) {
            scroll = max;
        }
        for (final Row row : rows) {
            final RowWidget widget = row.widget;
            widget.x = x + padLeft;
            widget.y = (int) Math.round(row.y);
            widget.w = Math.max(0, innerW() - scrollbarW);
            widget.h = Math.max(0, (int) Math.round(row.h));
            widget.visible = row.open > 0.02 && row.h > 0.5;
            widget.rowOpen = (float) Math.max(0.0, Math.min(1.0, row.open));
            widget.resetClip();
            widget.applyClip(clipX, clipY, clipW, clipH);
            widget.applyClip(x, y, w, h);
            widget.arrange(ctx);
        }
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        for (final Row row : rows) {
            row.item.open.to(row.item.expanded ? 1.0 : 0.0).tick(ctx.animations, dt);
            row.item.selectAnim.to(row.item == selected || row.item.highlight ? 1.0 : 0.0)
                    .tick(ctx.animations, dt);
        }
        final double k = 1.0 - Math.exp(-16.0 * Math.min(0.25, ctx.dt));
        scroll += (scrollTarget - scroll) * (ctx.animations ? k : 1.0);
        for (final Row row : rows) {
            if (row.widget.visible) {
                row.widget.update(ctx, dt);
            }
        }
    }

    @Override
    public boolean mouseScroll(final UiContext ctx, final double mx, final double my, final double delta) {
        final double max = Math.max(0, contentHeight - (h - padTop - padBottom));
        if (max <= 0.5) {
            return false;
        }
        scrollTarget = Math.max(0, Math.min(max, scrollTarget - delta * 18.0));
        return true;
    }

    /** 当前帧鼠标悬停行的 key ✓（null = 没有悬停）—— 供"树行悬停高亮对应特征"用 ✓ */
    private String hoveredKey;

    public String hoveredKey() {
        return hoveredKey;
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        hoveredKey = null; // 每帧重算 ✓
        if (bg != 0) {
            UiDraw.rect(g, x, y, w, h, bg);
        }
        if (outline != 0) {
            UiDraw.rectOutline(g, x, y, w, h, outline, 1);
        }
        renderChildren(ctx, g);
        final double max = Math.max(0, contentHeight - (h - padTop - padBottom));
        if (max > 0.5) {
            final double track = h - padTop - padBottom;
            final double barH = Math.max(8.0, track * (track / contentHeight));
            final double barY = y + padTop + (track - barH) * (scroll / max);
            UiDraw.rect(g, x + w - scrollbarW, (int) Math.round(barY), scrollbarW, (int) Math.round(barH),
                    ctx.theme.scrollbar);
        }
    }

    // ============ 行控件 ============

    /** 一行：缩进 + 展开箭头 + 行内容（由 {@link RowBuilder} 决定）。 */
    public class RowWidget extends Container {
        /** 当前节点；重建时会被换成新的那个（见 {@link #collect}）。 */
        public TreeItem item;
        private final Anim hover = Anim.of(0.0).speed(22.0);
        /** 祖先展开程度（渲染时按它淡出）。 */
        public float rowOpen = 1.0f;
        private final Chevron chevron;

        RowWidget(@Nullable final TreeView ignored, final TreeItem item) {
            this.item = item;
            this.layout = Layouts.hbox(0, 1);
            this.clip = true;
            final Widget spacer = new Widget() {
                @Override
                public void render(final UiContext ctx, final GuiGraphics g) {
                }
            };
            spacer.prefW = Math.max(0, item.depth * indent);
            spacer.prefH = FILL;
            add(spacer);
            chevron = new Chevron();
            chevron.prefW = 8;
            chevron.prefH = FILL;
            add(chevron);
            chevron.visible = !item.leaf();
        }

        @Override
        public void update(final UiContext ctx, final double dt) {
            // ★ 记录**悬停行的 key** ✓（之前只有"每帧清空"，从没赋值 ⇒ 外部永远拿不到）
            if (ctx.isHovered(this) && item.selectable) {
                hoveredKey = item.key;
            }
            hover.to(ctx.isHovered(this) && item.selectable ? 1.0 : 0.0).tick(ctx.animations, dt);
            super.update(ctx, dt);
        }

        @Override
        public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
            if (button == 1) {
                if (onSecondary != null) {
                    onSecondary.accept(item);
                }
                return true;
            }
            if (item.selectable) {
                selected = item;
                if (onSelect != null) {
                    onSelect.accept(item);
                }
            }
            return true;
        }

        @Override
        public void render(final UiContext ctx, final GuiGraphics g) {
            final UiTheme th = ctx.theme;
            final float alpha = rowOpen;
            final boolean isSelected = item.selectAnim.value() > 0.01;
            if (isSelected) {
                UiDraw.rect(g, x, y, w, h, Anim.lerpColor(0x00000000, th.select, item.selectAnim.f()));
            } else if (hover.value() > 0.001) {
                UiDraw.rect(g, x, y, w, h, Anim.lerpColor(0x00000000, th.panelLight, hover.f() * 0.6f));
            }
            if (item.highlight) {
                UiDraw.rect(g, x, y, 2, h, th.accentHover);
            }
            renderChildren(ctx, g);
        }

        /** 展开箭头。 */
        private class Chevron extends Widget {
            Chevron() {
                this.hoverable = true;
            }

            @Override
            public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
                if (button == 0) {
                    // 用 RowWidget.item（每次重建会被换成新实例），而不是构造时捕获的旧 item
                    RowWidget.this.item.expanded = !RowWidget.this.item.expanded;
                }
                return true;
            }

            @Override
            public void render(final UiContext ctx, final GuiGraphics g) {
                final int cx = x + w / 2;
                final int cy = y + h / 2;
                final int color = ctx.theme.textDim;
                // ★ 用户要求：展开用 "V"、收起用 ">" ✓（原来是画三角形点 ✗ —— 看不清方向 ✓）
                //   统一用字符更直白 ✓ 也更容易和别处图例保持一致 ✓
                if (RowWidget.this.item.open.value() > 0.5) {
                    UiDraw.text(g, net.minecraft.client.Minecraft.getInstance().font, "V",
                            cx - 3, cy - 4, color, 1.0f, false);
                } else {
                    UiDraw.text(g, net.minecraft.client.Minecraft.getInstance().font, ">",
                            cx - 3, cy - 4, color, 1.0f, false);
                }
            }
        }
    }
}
