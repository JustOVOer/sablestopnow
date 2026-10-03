package com.ovo.sablestopnow.client.gui.lib;

/**
 * 库的全局开关。由模组配置（设置 → 工程师护目镜 → 界面）写入。
 */
public final class UiSettings {

    /** 全局「界面动画」开关；关闭后所有缓动立即到位。 */
    public static boolean animations = true;

    /** 字体缩放（0.5 ~ 1.0），由配置写入。默认 1.0：原版是点阵字体，非整数缩放会发虚。 */
    public static float fontScale = 1.0f;

    /** 调试：在界面上画出控件边界。 */
    public static boolean debugBounds = false;

    private UiSettings() {
    }

    /**
     * 由模组配置推入这两项设置（配置加载/重载、以及玩家在设置界面里改动时各调一次）。
     *
     * <p>字号写两份不是冗余：{@link #fontScale} 是「配置想让界面变成多大」，
     * 而真正参与每帧测量与绘制的是 {@link UiTheme#fontScale}（主题可以整体替换）。
     * 只写前者的话，玩家调了滑条却什么都看不到。
     */
    public static void apply(final boolean animationsEnabled, final float scale) {
        animations = animationsEnabled;
        fontScale = Math.max(0.5f, Math.min(1.0f, scale));
        UiTheme.current().fontScale = fontScale;
    }
}
