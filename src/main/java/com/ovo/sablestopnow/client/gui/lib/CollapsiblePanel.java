package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 可折叠面板：展开时是正常面板，收起后只留一条窄条，左上/内侧有一个明确的<b>收起/展开按钮</b>。
 *
 * <h2>为什么尺寸要按屏幕比例算</h2>
 * 固定像素宽度在小窗口 + 低 GUI 缩放（例如 854x480 且 scale=2，实际只有 427x240 个 GUI 单位）下
 * 会吃掉大半屏：左栏 152 + 右栏 138 = 290/427 ≈ 68%，3D 视口只剩一条缝
 * （用户实测反馈「模型视图有严重遮挡」）。所以默认按屏幕宽度取比例并夹在 [min, max] 之间。
 *
 * <h2>为什么必须有可见按钮</h2>
 * 只靠「点窄条」不直观（用户实测反馈「应当有缩回、展开按键」）。这里始终在面板内侧画一个
 * 小方块 + 箭头：展开时箭头朝外（收起），收起时箭头朝内（拉出来）。
 */
public class CollapsiblePanel extends Panel {

    /** 展开进度：0 = 完全收起，1 = 完全展开。 */
    public final Anim open = Anim.of(1.0).speed(13.0);
    public boolean expanded = true;

    /** 展开时的固定尺寸（{@link #expandedFraction} &gt; 0 时不用它）。 */
    public int expandedSize = 150;
    /** 收起后留下的窄条尺寸。 */
    public int collapsedSize = 14;
    /** true = 停靠在上/下边（折叠高度）；false = 停靠在左/右边（折叠宽度）。 */
    public final boolean vertical;

    /** &gt; 0 时按屏幕宽度（或高度）的这个比例算展开尺寸 —— 小窗口下才不会被面板吃掉大半屏。 */
    public float expandedFraction;
    public int minSize = 88;
    public int maxSize = 190;

    /** 内容在展开进度低于它时就整体隐藏（避免半开状态下露出一半控件）。 */
    public float contentCutoff = 0.45f;

    /** 收起/展开按钮的边长。 */
    public int buttonSize = 11;

    public CollapsiblePanel(final boolean vertical) {
        this.vertical = vertical;
        this.prefW = vertical ? FILL : expandedSize;
        this.prefH = vertical ? expandedSize : FILL;
    }

    /** 固定展开尺寸（链式）。 */
    public CollapsiblePanel size(final int expanded) {
        this.expandedSize = expanded;
        this.expandedFraction = 0.0f;
        this.prefW = this.vertical ? FILL : expanded;
        this.prefH = this.vertical ? expanded : FILL;
        return this;
    }

    /** 按屏幕比例自适应展开尺寸（链式）。 */
    public CollapsiblePanel fraction(final float fraction, final int min, final int max) {
        this.expandedFraction = fraction;
        this.minSize = min;
        this.maxSize = max;
        return this;
    }

    public CollapsiblePanel collapsed(final int size) {
        this.collapsedSize = size;
        return this;
    }

    /** 本帧“希望”占的展开尺寸：固定值或屏幕比例夹紧后的值。 */
    private int wantedSize(final UiContext ctx) {
        if (this.expandedFraction <= 0.0f) {
            return this.expandedSize;
        }
        final int base = this.vertical ? ctx.screenH : ctx.screenW;
        return Math.max(this.minSize, Math.min(this.maxSize, Math.round(base * this.expandedFraction)));
    }

    /** 当前应该占的尺寸（按动画插值）。 */
    private int animatedSize(final UiContext ctx) {
        final int want = wantedSize(ctx);
        final double t = this.open.value();
        return (int) Math.round(this.collapsedSize + (want - this.collapsedSize) * t);
    }

    @Override
    public int wantW(final UiContext ctx) {
        return this.vertical ? super.wantW(ctx) : animatedSize(ctx);
    }

    @Override
    public int wantH(final UiContext ctx) {
        return this.vertical ? animatedSize(ctx) : super.wantH(ctx);
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        this.open.to(this.expanded ? 1.0 : 0.0).tick(ctx.animations, dt);
        // 内容整体显隐：半开时露一半控件比「整齐地晚一点出现」难看得多
        final boolean showContent = this.open.value() > this.contentCutoff;
        for (final Widget child : this.children) {
            child.visible = showContent;
        }
        super.update(ctx, dt);
    }

    /** 收起/展开按钮的矩形：始终贴在「朝屏幕中间那一侧」。 */
    private int[] buttonRect() {        if (this.vertical) {
            return new int[]{this.x + 2, this.y + 2, this.buttonSize, this.buttonSize};
        }
        final boolean dockedRight = this.dock == Layouts.Dock.RIGHT;
        final int bx = dockedRight ? this.x + 2 : this.x + this.w - this.buttonSize - 2;
        return new int[]{bx, this.y + 2, this.buttonSize, this.buttonSize};
    }

    private boolean inButton(final double mx, final double my) {
        final int[] r = buttonRect();
        return mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3];
    }

    @Override
    public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
        if (button == 0 && this.hit(mx, my) && (inButton(mx, my) || this.open.value() <= this.contentCutoff)) {
            this.expanded = !this.expanded;
            return true;
        }
        return super.mouseDown(ctx, mx, my, button);
    }

    @Override
    public Widget hitTest(final double mx, final double my) {
        if (!this.hit(mx, my)) {
            return null;
        }
        // 收起时整条都是把手；展开时只有那个按钮是把手，其余交给内容控件
        if (inButton(mx, my) || this.open.value() <= this.contentCutoff) {
            return this;
        }
        return super.hitTest(mx, my);
    }

    @Override
    protected int titleInsetX() {
        // 侧边栏的收起按钮在「朝屏幕中间」那一侧：右侧停靠时按钮在左端，会盖住标题的第一个字
        if (this.vertical) {
            return 0;
        }
        return this.dock == Layouts.Dock.RIGHT ? this.buttonSize + 4 : 0;
    }

    @Override
    public Component tooltip() {
        return this.expanded
                ? Component.translatable("gui.sablestopnow.panel.collapse")
                : Component.translatable("gui.sablestopnow.panel.expand");
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        super.render(ctx, g);
        drawButton(ctx, g);
    }

    /** 收起/展开按钮：小方块 + 尖括号（展开时指向屏幕外缘=收起，收起时指向内侧=拉出来）。 */
    private void drawButton(final UiContext ctx, final GuiGraphics g) {
        final int[] r = buttonRect();
        final boolean hovered = ctx.isHovered(this);
        UiDraw.rect(g, r[0], r[1], r[2], r[3], hovered ? ctx.theme.accentHover : ctx.theme.accent);
        UiDraw.rectOutline(g, r[0], r[1], r[2], r[3], ctx.theme.border, 1);
        final int arrow = ctx.theme.text;
        if (this.vertical) {
            // 上/下停靠：用上下三角表示
            UiDraw.triangle(g, r[0] + r[2] / 2 - 2, r[1] + r[3] / 2 - 3, 5, 5, this.expanded, arrow);
            return;
        }
        final boolean dockedRight = this.dock == Layouts.Dock.RIGHT;
        final boolean pointRight = dockedRight ? !this.expanded : this.expanded;
        chevron(g, r[0] + r[2] / 2 - 2, r[1] + r[3] / 2 - 3, 5, pointRight, arrow);
    }

    /** 用两条斜线拼一个尖括号（比三角更容易看清方向）。 */
    private static void chevron(final GuiGraphics g, final int x, final int y, final int size,
                                final boolean pointRight, final int argb) {
        for (int i = 0; i < size; i++) {
            final int dx = pointRight ? i : size - 1 - i;
            UiDraw.rect(g, x + dx, y + i, 1, 1, argb);
            UiDraw.rect(g, x + dx, y + size * 2 - 2 - i, 1, 1, argb);
        }
    }

    /** 供调用方在别处触发折叠。 */
    public void toggle() {
        this.expanded = !this.expanded;
    }

    @Nullable
    public static CollapsiblePanel side(final Layouts.Dock dock, final float fraction,
                                        final int min, final int max) {
        final CollapsiblePanel panel = new CollapsiblePanel(false);
        panel.dock = dock;
        return panel.fraction(fraction, min, max);
    }
}
