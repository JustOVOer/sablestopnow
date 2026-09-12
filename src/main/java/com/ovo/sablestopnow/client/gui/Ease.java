package com.ovo.sablestopnow.client.gui;

/**
 * 配置界面的动画工具。
 *
 * <p>要求（newConfigGUI/description.txt）：所有元素变化都要「由快变慢」的缓动、出现消失用淡入淡出。
 * 因此这里统一用<b>指数收敛</b>（每帧把剩余距离按 1-e^(-speed·dt) 消掉）——它的速度随时间自然衰减，
 * 就是标准的「由快变慢」，而且与帧率无关。
 */
public final class Ease {

    private Ease() {
    }

    /** 指数收敛到目标值。 */
    public static float approach(final float current, final float target, final float speed, final float dt) {
        if (dt <= 0.0f) {
            return current;
        }
        return current + (target - current) * (1.0f - (float) Math.exp(-speed * dt));
    }

    /** 三次缓出（0..1）。 */
    public static float outCubic(final float t) {
        final float c = Math.clamp(t, 0.0f, 1.0f);
        return 1.0f - (1.0f - c) * (1.0f - c) * (1.0f - c);
    }

    /** 用缓出曲线在 start/end 之间插值。 */
    public static float slide(final float start, final float end, final float progress) {
        return start + (end - start) * outCubic(progress);
    }

    /** 按倍率缩放 alpha 通道（淡入淡出）。 */
    public static int fade(final int argb, final float mul) {
        final int a = (int) (((argb >>> 24) & 0xFF) * Math.clamp(mul, 0.0f, 1.0f));
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** 线性插值两个 ARGB。 */
    public static int mix(final int from, final int to, final float t) {
        final float c = Math.clamp(t, 0.0f, 1.0f);
        final int a = (int) (((from >>> 24) & 0xFF) + ((((to >>> 24) & 0xFF)) - ((from >>> 24) & 0xFF)) * c);
        final int r = (int) (((from >> 16) & 0xFF) + ((((to >> 16) & 0xFF)) - ((from >> 16) & 0xFF)) * c);
        final int g = (int) (((from >> 8) & 0xFF) + ((((to >> 8) & 0xFF)) - ((from >> 8) & 0xFF)) * c);
        final int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * c);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    // ---- 与 Create 设置界面同风格的蓝色色板（普蓝 + 蓝灰描边） ----
    public static final int PANEL = 0xF0162B52;        // 主色块：普蓝
    public static final int PANEL_DARK = 0xF00E1D3A;   // 更深一档
    public static final int EDGE = 0xFF8296B8;         // 色块描边：蓝灰
    public static final int BUTTON = 0xFF2B4A85;
    public static final int BUTTON_HOVER = 0xFF3E63AD;
    public static final int BUTTON_ON = 0xFF57B6FF;
    public static final int TEXT = 0xFFFFFFFF;
    public static final int TEXT_DIM = 0xFFC3D0E6;
    public static final int SHADOW = 0x80000000;
}
