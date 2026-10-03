package com.ovo.sablestopnow.client.gui.lib;

/**
 * HUD 避让：把 HUD 元素放到不会被别的面板压住的位置。
 *
 * <p>库里所有覆盖层在绘制时会把矩形登记到 {@link Ui#reservedRects()}，HUD 用自己的
 * {@link Widget#prefW}/{@link Widget#prefH} 问 {@link #place} 要一个空位即可。
 */
public final class UiDock {

    public enum Corner {
        TOP_LEFT, TOP_CENTER, TOP_RIGHT,
        BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
    }

    private UiDock() {
    }

    /** 返回 {x, y}。 */
    public static int[] place(final int w, final int h, final Corner corner, final int margin) {
        final int screenW = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledWidth();
        final int screenH = net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int x;
        int y;
        switch (corner) {
            case TOP_CENTER -> {
                x = (screenW - w) / 2;
                y = margin;
            }
            case TOP_RIGHT -> {
                x = screenW - w - margin;
                y = margin;
            }
            case BOTTOM_LEFT -> {
                x = margin;
                y = screenH - h - margin;
            }
            case BOTTOM_CENTER -> {
                x = (screenW - w) / 2;
                y = screenH - h - margin;
            }
            case BOTTOM_RIGHT -> {
                x = screenW - w - margin;
                y = screenH - h - margin;
            }
            default -> {
                x = margin;
                y = margin;
            }
        }
        // 与已登记的面板重叠时，先沿「远离该边」的方向让位；竖直方向让不开（例如整条侧栏从顶铺到底）
        // 就改往屏幕内侧让。只让不查会得到一个跑到屏幕外的坐标，HUD 反而更难看。
        for (int attempt = 0; attempt < 8; attempt++) {
            final int[] hit = overlapping(x, y, w, h);
            if (hit == null) {
                break;
            }
            switch (corner) {
                case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> y = hit[1] + hit[3] + 2;
                default -> y = Math.max(margin, hit[1] - h - 2);
            }
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            final int[] hit = overlapping(x, y, w, h);
            if (hit == null) {
                break;
            }
            switch (corner) {
                case TOP_RIGHT, BOTTOM_RIGHT -> x = hit[0] - w - 2;
                case TOP_LEFT, BOTTOM_LEFT -> x = hit[0] + hit[2] + 2;
                default -> {
                    return new int[]{x, y};
                }
            }
        }
        return new int[]{x, y};
    }

    /** 该矩形是否与已登记的占用矩形相交。 */
    public static boolean overlapsReserved(final int x, final int y, final int w, final int h) {
        return overlapping(x, y, w, h) != null;
    }

    /**
     * 矩形是否完整落在屏幕内。
     *
     * <p>避让推到最后可能把 HUD 推出屏幕（左上角被左边栏从顶铺到底时，往下让就直接出界）。
     * 这种情况下正确的做法是「这一帧不画」——被别的面板压住也好过贴在屏幕外面。
     */
    public static boolean fitsOnScreen(final int x, final int y, final int w, final int h) {
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.getWindow() == null) {
            return true;
        }
        final int screenW = mc.getWindow().getGuiScaledWidth();
        final int screenH = mc.getWindow().getGuiScaledHeight();
        return x >= 0 && y >= 0 && x + w <= screenW && y + h <= screenH;
    }

    private static int[] overlapping(final int x, final int y, final int w, final int h) {
        for (final int[] rect : Ui.reservedRects()) {
            final boolean overlap = x < rect[0] + rect[2] && x + w > rect[0]
                    && y < rect[1] + rect[3] && y + h > rect[1];
            if (overlap) {
                return rect;
            }
        }
        return null;
    }
}
