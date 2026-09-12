package com.ovo.sablestopnow;

/**
 * 玩家选中/所有权的描边调色板。
 *
 * <p>颜色索引由服务端分配（见 {@code server.StaffSelectionRegistry}），保证同一时间在线的玩家
 * 拿到互不相同的颜色（人数超过调色板长度才会回绕）。
 */
public final class StaffColors {

    /** 12 种高辨识度颜色（RGB）。 */
    public static final int[] PALETTE = {
            0x26F2FF, // 青
            0xFF6B3D, // 橙红
            0x8CFF3D, // 黄绿
            0xFFD23D, // 金黄
            0xC08CFF, // 紫
            0x3D7BFF, // 蓝
            0xFF3D9E, // 品红
            0x3DFFB8, // 青绿
            0xFFF23D, // 亮黄
            0xFF8CB0, // 粉
            0x9EB4FF, // 淡蓝
            0xD9FF3D  // 黄绿二
    };

    private StaffColors() {
    }

    public static int size() {
        return PALETTE.length;
    }

    public static int color(final int index) {
        return PALETTE[Math.floorMod(index, PALETTE.length)];
    }

    public static float red(final int index) {
        return ((color(index) >> 16) & 0xFF) / 255.0f;
    }

    public static float green(final int index) {
        return ((color(index) >> 8) & 0xFF) / 255.0f;
    }

    public static float blue(final int index) {
        return (color(index) & 0xFF) / 255.0f;
    }
}
