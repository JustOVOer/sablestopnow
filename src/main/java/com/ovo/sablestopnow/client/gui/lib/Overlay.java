package com.ovo.sablestopnow.client.gui.lib;

import org.jetbrains.annotations.Nullable;

/**
 * 弹出层的统一入口。
 *
 * <h2>为什么不能各控件自己挂</h2>
 * 「浮在别人上面的东西」有下拉列表、补全列表、右键菜单、提示气泡四种，它们必须共享同一套
 * <b>栈</b>语义：后开的在上面、点外面关掉、Esc 关掉、同一个宿主再开一次要替换而不是叠罗汉。
 * 以前每个控件各自去动 {@code UiRoot.popups}，于是出现了「选完信号下拉菜单不消失」
 * 「Esc 关的是提示不是菜单」这类互相踩踏。现在只留这一个入口，规则集中在这里。
 */
public final class Overlay {

    private Overlay() {
    }

    /** 打开一个弹出层（{@code owner} 是发起它的控件，用于「同一个宿主只留一个」）。 */
    public static void open(final UiContext ctx, final Widget owner, final Widget panel) {
        if (ctx == null || ctx.root == null || panel == null) {
            return;
        }
        ctx.root.openPopup(panel, owner);
    }

    /** 关掉某个宿主的弹出层。 */
    public static void close(final UiContext ctx, final Widget owner) {
        if (ctx != null && ctx.root != null) {
            ctx.root.closePopup(owner);
        }
    }

    /** 关掉所有弹出层（切页、重建面板时用）。 */
    public static void closeAll(final UiContext ctx) {
        if (ctx != null && ctx.root != null) {
            ctx.root.closePopup();
            while (ctx.root.hasPopup()) {
                ctx.root.closePopup();
            }
        }
    }

    public static boolean isOpen(final UiContext ctx, final Widget owner) {
        return ctx != null && ctx.root != null && ctx.root.hasPopup(owner);
    }

    public static boolean anyOpen(final UiContext ctx) {
        return ctx != null && ctx.root != null && ctx.root.hasPopup();
    }

    public static int depth(final UiContext ctx) {
        return ctx == null || ctx.root == null ? 0 : ctx.root.popups.size();
    }

    @Nullable
    public static Widget top(final UiContext ctx) {
        if (ctx == null || ctx.root == null || ctx.root.popups.isEmpty()) {
            return null;
        }
        return ctx.root.popups.get(ctx.root.popups.size() - 1);
    }

    /**
     * 在锚点下方弹出（放不下就翻到上方）。
     *
     * <p>翻转让所有下拉/右键菜单共用同一条规则，不会出现「屏幕底部一点菜单就跑到屏幕外」。
     */
    public static void openBelow(final UiContext ctx, final Widget owner, final MenuList menu,
                                 final int anchorX, final int anchorY, final int anchorH) {
        final int listH = menu.height();
        final int py = anchorY + anchorH + 1 + listH > ctx.screenH
                ? Math.max(0, anchorY - listH - 1)
                : anchorY + anchorH + 1;
        final int px = Math.min(anchorX, Math.max(0, ctx.screenW - menu.fixedWidth - 1));
        menu.openAt(px, py);
        open(ctx, owner, menu);
    }
}
