package com.ovo.sablestopnow.client.gui.lib;

/**
 * 一个带缓动的动画值：每帧朝目标指数收敛，天然做到「由快变慢」。
 *
 * <p>{@link #speed} 是收敛速度（约等于每秒收敛到多少比例）；{@link #setSpeed} 越大越快。
 * 全局动画开关关闭时，{@link Ui} 会让所有动画直接跳到目标值（见 {@link #tick(boolean, double)}）。
 */
public final class Anim {

    private double value;
    private double target;
    private double speed = 12.0;

    public Anim(final double initial) {
        this.value = initial;
        this.target = initial;
    }

    public static Anim of(final double initial) {
        return new Anim(initial);
    }

    public double value() {
        return value;
    }

    public float f() {
        return (float) value;
    }

    public double target() {
        return target;
    }

    public boolean settled() {
        return Math.abs(target - value) < 1.0e-4;
    }

    public Anim speed(final double perSecond) {
        this.speed = perSecond;
        return this;
    }

    /** 设定目标（带动画）。 */
    public Anim to(final double newTarget) {
        this.target = newTarget;
        return this;
    }

    /** 立即跳到目标。 */
    public Anim snap(final double v) {
        this.value = v;
        this.target = v;
        return this;
    }

    /** 把当前值当作目标（例如滚动面板拖拽后）。 */
    public Anim hold() {
        this.target = this.value;
        return this;
    }

    /**
     * 推进一帧。
     *
     * @param animations 全局动画开关；false 时直接跳到目标
     * @param dt         帧间隔（秒）
     */
    public double tick(final boolean animations, final double dt) {
        if (!animations) {
            value = target;
            return value;
        }
        final double k = 1.0 - Math.exp(-speed * Math.max(0.0, Math.min(0.25, dt)));
        value += (target - value) * k;
        if (Math.abs(target - value) < 1.0e-4) {
            value = target;
        }
        return value;
    }

    /** 0..1 的进度：把 value 当作 0..1 的插值量并夹紧。 */
    public float progress() {
        return (float) Math.max(0.0, Math.min(1.0, value));
    }

    /** 三次缓出，用于「由快变慢」的位移。 */
    public static float easeOut(final float t) {
        final float c = Math.max(0.0f, Math.min(1.0f, t));
        return 1.0f - (1.0f - c) * (1.0f - c) * (1.0f - c);
    }

    /** 三次缓入。 */
    public static float easeIn(final float t) {
        final float c = Math.max(0.0f, Math.min(1.0f, t));
        return c * c * c;
    }

    /** 缓入缓出。 */
    public static float easeInOut(final float t) {
        final float c = Math.max(0.0f, Math.min(1.0f, t));
        return c < 0.5f ? 4.0f * c * c * c : 1.0f - (float) Math.pow(-2.0f * c + 2.0f, 3.0) / 2.0f;
    }

    public static float lerp(final float a, final float b, final float t) {
        return a + (b - a) * t;
    }

    public static int lerpColor(final int a, final int b, final float t) {
        final float c = Math.max(0.0f, Math.min(1.0f, t));
        final int aa = (a >>> 24) & 0xFF;
        final int ar = (a >> 16) & 0xFF;
        final int ag = (a >> 8) & 0xFF;
        final int ab = a & 0xFF;
        final int ba = (b >>> 24) & 0xFF;
        final int br = (b >> 16) & 0xFF;
        final int bg = (b >> 8) & 0xFF;
        final int bb = b & 0xFF;
        final int ra = Math.round(aa + (ba - aa) * c);
        final int rr = Math.round(ar + (br - ar) * c);
        final int rg = Math.round(ag + (bg - ag) * c);
        final int rb = Math.round(ab + (bb - ab) * c);
        return (ra << 24) | (rr << 16) | (rg << 8) | rb;
    }
}
