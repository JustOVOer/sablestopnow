package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/**
 * 按钮：文字 / 图标 / 两者兼有。
 *
 * <h2>点击反馈</h2>
 * 按下瞬间有一道浅蓝从<b>左到右刷过去</b>，字体在被刷过的部分同步转成深色；
 * 松开后约 0.2 秒淡出。全部由 {@link #sweepClock} 一个时间量推出来
 * （颜色、字色、边缘高光都是它的投影），所以不会出现「底色刷到一半、字色还停在上一帧」。
 *
 * <p>时间用秒而不是指数收敛：扫过的速度是<b>匀速</b>的，反馈才有「刷」的手感，
 * 而 {@link Anim} 的指数收敛在起点快、终点慢，看着像渐变而不是扫过。
 */
public class Button extends Widget {

    /** 扫过一次的时长（秒）。 */
    private static final double SWEEP_SECONDS = 0.16;
    /** 松开后淡出的时长（秒）。 */
    private static final double FADE_SECONDS = 0.20;

    public Supplier<Component> label;
    @Nullable
    public ResourceLocation icon;
    public int iconSize = 10;
    /**
     * **动图序列**（逐帧独立贴图 ✓）。
     *
     * <p>用户要求（本轮 ④）：按键要能"插入动图序列" ✓。
     * 帧按 {@link #iconFps} 循环播放；非空时优先于静态 {@link #icon} ✓。
     * 每帧贴图按 16×16 基准缩放绘制（与 {@link UiDraw#icon} 同一约定 ✓）。
     */
    public java.util.List<ResourceLocation> iconFrames = java.util.List.of();
    /** 动图播放速度（帧/秒）✓。 */
    public double iconFps = 8.0;
    /**
     * **横向 sprite sheet**（单张贴图里一帧挨一帧 ✓）；非空时优先于 {@link #icon} ✓。
     *
     * <p>与 {@link #iconFrames} 二选一：序列是"多张独立 png"，sheet 是"一张长图"。
     * 后者的好处是只需一个资源文件、不会因为帧数多而散落一堆 png ✓。
     */
    @Nullable
    public ResourceLocation iconSheet;
    public int sheetFrameW;
    public int sheetFrameH;
    public int sheetFrameCount;
    /** 动图当前帧时钟（秒）✓。 */
    private double iconClock;
    @Nullable
    public Runnable action;
    /** 右键回调（可选）。 */
    @Nullable
    public Runnable secondaryAction;
    /** 归属命令名（自检/日志用）。 */
    @Nullable
    public String command;

    public int baseColor = 0;
    public int hoverColor = 0;
    public int pressColor = 0;
    public int textColor = 0;
    public int borderColor = 0;
    /** 选中态（切换按钮）。 */
    public boolean toggled;
    public int toggledColor = 0;

    /**
     * **点击扫光**的颜色 ✓（0 = 用主题默认的浅蓝 ✓）。
     *
     * <p>用户要求（实测反馈）："点确定这类按键时，背景刷过去的颜色改草绿；删除/取消改红色" ✓
     * —— 注意他说的是**点下去刷过去的那道色**（sweep ✓），不是悬停底色 ✗。
     */
    public int sweepColor = 0;

    public final Anim hover = Anim.of(0.0).speed(20.0);

    /**
     * 点击扫光的时钟（秒）。
     *
     * <p>{@code <0} = 空闲；{@code [0, SWEEP_SECONDS]} = 正在从左到右扫；
     * {@code (SWEEP_SECONDS, SWEEP+FADE]} = 已经铺满、正在淡出。
     */
    private double sweepClock = -1.0;
    private boolean held;

    /** 图标与文字之间的间距。 */
    public int gap = 3;
    public Layouts.Dock iconSide = Layouts.Dock.LEFT;

    public Button(final Component label, final Runnable action) {
        this.label = () -> label;
        this.action = action;
        this.prefH = 12;
        this.prefW = AUTO;
    }

    public Button(final String translationKey, final Runnable action) {
        this(Component.translatable(translationKey), action);
    }

    public Button(final Supplier<Component> label, final Runnable action) {
        this.label = label;
        this.action = action;
        this.prefH = 12;
        this.prefW = AUTO;
    }

    public Button icon(@Nullable final ResourceLocation texture) {
        this.icon = texture;
        return this;
    }

    /** 图标边长（链式 ✓）。 */
    public Button iconSize(final int px) {
        this.iconSize = Math.max(1, px);
        return this;
    }

    /**
     * **自定义按键尺寸**（用户要求 ④ ✓）。
     *
     * <p>不写的话：宽按内容量、高 12；写 {@code -1}（{@link #AUTO}）恢复自动。
     * 与 {@link Widget#size(int,int)} 完全同一语义，只是返回 {@link Button} 方便链式调用 ✓。
     */
    @Override
    public Button size(final int width, final int height) {
        super.size(width, height);
        return this;
    }

    /**
     * **插入动图序列**（多张独立贴图 ✓ 用户要求 ④）。
     *
     * @param frames 帧贴图（顺序即播放顺序；空表 = 关闭动图 ✓）
     * @param fps    帧率（帧/秒；≤0 时保留原值 ✓）
     */
    public Button frames(final java.util.List<ResourceLocation> frames, final double fps) {
        this.iconFrames = frames == null ? java.util.List.of() : java.util.List.copyOf(frames);
        if (fps > 0.0) {
            this.iconFps = fps;
        }
        this.iconClock = 0.0;
        return this;
    }

    /** 插入动图序列（可变参数版 ✓）。 */
    public Button frames(final double fps, final ResourceLocation... frames) {
        return frames(java.util.Arrays.asList(frames), fps);
    }

    /**
     * **插入 sprite sheet 动图**（单张横向长图 ✓ 用户要求 ④）。
     *
     * @param texture    长图（帧从左到右排）
     * @param frameW     单帧宽（像素）
     * @param frameH     单帧高（像素）
     * @param frameCount 帧数（≤0 = 关闭 ✓）
     * @param fps        帧率（帧/秒；≤0 时保留原值 ✓）
     */
    public Button sheet(final ResourceLocation texture, final int frameW, final int frameH,
                        final int frameCount, final double fps) {
        this.iconSheet = texture;
        this.sheetFrameW = frameW;
        this.sheetFrameH = frameH;
        this.sheetFrameCount = Math.max(0, frameCount);
        if (fps > 0.0) {
            this.iconFps = fps;
        }
        this.iconClock = 0.0;
        return this;
    }

    /** 是否有任何图标（静态 / 序列 / sheet）✓。 */
    public boolean hasIcon() {
        return icon != null || !iconFrames.isEmpty() || (iconSheet != null && sheetFrameCount > 0);
    }

    /** 当前应显示的动图帧下标 ✓。 */
    private int iconFrameIndex(final int count) {
        if (count <= 1) {
            return 0;
        }
        final int idx = (int) Math.floor(iconClock * Math.max(0.1, iconFps));
        return Math.floorMod(idx, count);
    }

    /** 命令名：让「点了没反应」在日志里能对上号。 */
    public Button command(final String name) {
        this.command = name;
        Cmd.declare(name);
        return this;
    }

    /**
     * 短标签：屏幕太窄、完整标签放不下时改用（见 {@link Layouts#hboxFit(int, int)}）。
     *
     * <p>本项目标签是「中文 | English」双语的，小窗口下整排按钮必然溢出；短标签通常只留中文
     * （英文语言文件里则只留英文），必要信息仍由 tooltip 兜住。
     */
    @Nullable
    public Component compactLabel;
    /** 当前是否处于紧凑模式（由布局器设置）。 */
    public boolean compact;

    public Button compact(@Nullable final Component value) {
        this.compactLabel = value;
        return this;
    }

    public Button compact(final String translationKey) {
        return compact(Component.translatable(translationKey));
    }

    @Override
    public void setCompact(final boolean value) {
        this.compact = value;
    }

    public Button tooltipKey(final String key) {
        this.tooltipText = Component.translatable(key);
        return this;
    }

    @Nullable
    /**
     * 自动短标签 ✓：若当前文案是可翻译的且存在 {@code <key>.short} ⇒ 返回它 ✓ 否则 {@code null} ✓。
     *
     * <p>为什么在库里做 ✗✓：{@link #compact(Component)} 需要**每个调用点**自己记得传短标签 ✗，
     * 一旦忘了，窄容器里就只能省略号/被压扁 ✗（五种图元按钮挤一行就是实例 ✓）。
     * 做成库行为后 ⇒ 只要 lang 里加了 {@code <key>.short}，全部按钮**自动**受益 ✓ 调用点零改动 ✗✓。
     */
    private String autoShortLabel() {
        final Component component = label.get();
        if (!(component.getContents()
                instanceof net.minecraft.network.chat.contents.TranslatableContents translatable)) {
            return null;
        }
        final String shortKey = translatable.getKey() + ".short";
        final String value = Component.translatable(shortKey).getString();
        if (value == null || value.isEmpty() || value.equals(shortKey)) {
            return null; // 键不存在 ✓
        }
        return value;
    }
    private Component tooltipText;

    @Override
    public Component tooltip() {
        return tooltipText;
    }

    public Button colors(final int base, final int hover, final int text) {
        this.baseColor = base;
        this.hoverColor = hover;
        this.textColor = text;
        return this;
    }

    public String raw() {
        if (label == null) {
            return "";
        }
        if (compact) {
            if (compactLabel != null) {
                return compactLabel.getString();
            }
            // ★ 库级**自动短标签** ✓✓（新要求：不该让写界面的人记得传 compactLabel ✗）
            //   与 Label 完全同一套约定 ✓：可翻译文案的键是 <key> ⇒ 试 <key>.short ✓
            //   不存在就用原文 ✓（未解析的键会原样返回 ⇒ 一比就知道 ✓）。
            final String auto = autoShortLabel();
            if (auto != null) {
                return auto;
            }
        }
        return label.get().getString();
    }

    // ============ 扫光 ============

    /** 0..1：已经刷过去的宽度比例。 */
    public float sweepProgress() {
        if (sweepClock < 0.0) {
            return 0.0f;
        }
        if (sweepClock <= SWEEP_SECONDS) {
            return (float) Math.min(1.0, sweepClock / SWEEP_SECONDS);
        }
        return 1.0f;
    }

    /** 0..1：整体不透明度（扫的时候是 1，松开后淡出）。 */
    public float sweepAlpha() {
        if (sweepClock < 0.0) {
            return 0.0f;
        }
        if (sweepClock <= SWEEP_SECONDS) {
            return 1.0f;
        }
        return (float) Math.max(0.0, 1.0 - (sweepClock - SWEEP_SECONDS) / FADE_SECONDS);
    }

    /**
     * **按按钮文字自动**决定扫光色 ✓（用户要求：确定类 = 草绿、删除/取消类 = 红 ✓）。
     *
     * <p>为什么按文字而不是逐个调用点设 ✗✓：第一次我只给"改值浮层"的两个按钮设了颜色 ✗
     * ⇒ 左栏/其它面板的确定、删除仍然是主题浅蓝 ⇒ 用户看到的就是"配色完全没生效" ✓
     * 这里按**显示文字**统一判定（中英都认 ✓），所有面板一次生效 ✓。
     */
    private int autoSweepColor() {
        if (sweepColor != 0) {
            return sweepColor;
        }
        final String t = label == null ? "" : label.get().getString();
        if (t.contains("删除") || t.contains("取消") || t.contains("移除") || t.contains("清空")
                || t.toLowerCase(java.util.Locale.ROOT).contains("delete")
                || t.toLowerCase(java.util.Locale.ROOT).contains("cancel")
                || t.toLowerCase(java.util.Locale.ROOT).contains("remove")) {
            return 0xFFB03030; // 红 ✓
        }
        if (t.contains("确定") || t.contains("应用") || t.contains("保存") || t.contains("添加")
                || t.contains("完成") || t.toLowerCase(java.util.Locale.ROOT).contains("ok")
                || t.toLowerCase(java.util.Locale.ROOT).contains("apply")
                || t.toLowerCase(java.util.Locale.ROOT).contains("save")
                || t.toLowerCase(java.util.Locale.ROOT).contains("confirm")) {
            return 0xFF6B8E23; // 草绿 ✓
        }
        return 0; // 其它保持主题色 ✓
    }

    /** 正在扫/正在淡出。 */
    public boolean sweeping() {
        return sweepClock >= 0.0;
    }

    /** 触发一次扫光（HUD 里「刚刚生效」的提示也复用它）。 */
    public void pulse() {
        sweepClock = 0.0;
    }

    @Override
    public void measure(final UiContext ctx) {
        int width = 0;
        if (hasIcon()) {
            width += iconSize;
        }
        if (!raw().isEmpty()) {
            width += (hasIcon() ? gap : 0) + ctx.textW(raw());
        }
        w = Math.max(minW, width + ctx.theme.padX * 2 + 2);
        h = Math.max(minH, prefH >= 0 ? prefH : ctx.lineH() + ctx.theme.padY * 2);
    }

    @Override
    public void update(final UiContext ctx, final double dt) {
        // 动图只在"有图 + 允许动画"时走时钟（尊重"减少动画"设置 ✓）
        if (hasIcon() && ctx.animations) {
            iconClock += Math.max(0.0, dt);
        }
        hover.to(ctx.isHovered(this) && enabled ? 1.0 : 0.0).tick(ctx.animations, dt);
        if (sweepClock >= 0.0) {
            sweepClock += ctx.animations ? dt : SWEEP_SECONDS + FADE_SECONDS + 1.0;
            if (sweepClock > SWEEP_SECONDS + FADE_SECONDS) {
                sweepClock = -1.0;
            }
        }
    }

    @Override
    public boolean mouseDown(final UiContext ctx, final double mx, final double my, final int button) {
        if (!enabled) {
            return true;
        }
        if (button == 0) {
            held = true;
            sweepClock = 0.0;
        } else if (button == 1 && secondaryAction != null) {
            Cmd.run((command == null ? "button" : command) + ".secondary", secondaryAction);
        }
        return true;
    }

    @Override
    public void mouseUp(final UiContext ctx, final double mx, final double my, final int button) {
        final boolean wasPressed = held;
        held = false;
        if (!enabled || button != 0) {
            return;
        }
        if (sweepClock >= 0.0 && sweepClock < SWEEP_SECONDS) {
            // 松手时若还没刷完，让它先刷完再淡出（否则会看到「刷到一半就没了」）
            sweepClock = SWEEP_SECONDS;
        }
        final boolean inside = hit(mx, my);
        if (wasPressed && inside && action != null) {
            Cmd.run(command == null ? ("button." + raw()) : command, action);
        }
    }

    @Override
    public void render(final UiContext ctx, final GuiGraphics g) {
        final UiTheme th = ctx.theme;
        int bg = baseColor == 0 ? th.panelLight : baseColor;
        if (toggled) {
            bg = toggledColor == 0 ? th.accent : toggledColor;
        }
        if (hover.value() > 0.001 && !sweeping()) {
            bg = Anim.lerpColor(bg, hoverColor == 0 ? th.accentHover : hoverColor, hover.f());
        }
        UiDraw.panel(g, x, y, w, h, bg, borderColor == 0 ? th.border : borderColor);

        // ---- 从左到右的浅蓝扫光 ----
        final float progress = sweepProgress();
        final float alpha = sweepAlpha();
        if (progress > 0.0f && alpha > 0.01f) {
            final int swept = Math.round(w * progress);
            final int sweepArgb = autoSweepColor();
            UiDraw.rect(g, x, y, swept, h,
                    UiDraw.fade(sweepArgb == 0 ? th.sweep : sweepArgb, alpha));
            // 前缘一道更亮的细边：扫过的动作才看得出「刷」的方向
            if (progress < 1.0f && swept > 0) {
                UiVector.line(g, x + swept - 0.5, y + 0.5, x + swept - 0.5, y + h - 0.5,
                        1.5f, UiDraw.fade(0xFFFFFFFF, alpha * 0.75f));
            }
            // 扫光期间覆盖边框，避免浅蓝底 + 深蓝框显得脏
            UiDraw.rectOutline(g, x, y, w, h,
                    UiDraw.fade(sweepArgb == 0 ? th.sweepEdge : sweepArgb, alpha), 1);
        }

        final String text = raw();
        int contentW = (hasIcon() ? iconSize : 0) + (!text.isEmpty() && hasIcon() ? gap : 0)
                + ctx.textW(text);
        int cx = x + (w - contentW) / 2;
        final int ty = textY(ctx, y, h);
        final int normalText = !enabled ? th.textDisabled : (textColor == 0 ? th.text : textColor);
        if (hasIcon()) {
            final int iy = y + (h - iconSize) / 2;
            if (!iconFrames.isEmpty()) {
                // 多张独立贴图（每帧 16×16 基准 ✓ 与 UiDraw.icon 同一约定）
                final int idx = iconFrameIndex(iconFrames.size());
                UiDraw.iconFrame(g, iconFrames.get(idx), cx, iy, iconSize,
                        0.0f, 0.0f, 16, 16, 16, 16, 0xFFFFFFFF);
            } else if (iconSheet != null && sheetFrameCount > 0
                    && sheetFrameW > 0 && sheetFrameH > 0) {
                // 单张横向 sheet：u 随帧号平移 ✓（贴图宽 = 帧宽 × 帧数）
                final int idx = iconFrameIndex(sheetFrameCount);
                UiDraw.iconFrame(g, iconSheet, cx, iy, iconSize,
                        (float) (idx * sheetFrameW), 0.0f,
                        sheetFrameW, sheetFrameH,
                        sheetFrameW * sheetFrameCount, sheetFrameH, 0xFFFFFFFF);
            } else if (icon != null) {
                UiDraw.icon(g, icon, cx, iy, iconSize, 0xFFFFFFFF);
            }
            cx += iconSize + (text.isEmpty() ? 0 : gap);
        }
        if (!text.isEmpty()) {
            final String shown = UiDraw.ellipsize(ctx.font, text, w - ctx.theme.padX * 2, th.fontScale);
            UiDraw.text(g, ctx.font, shown, cx, ty, normalText, th.fontScale, false);
            // 被刷过的那一段：把字重画成深色（用裁剪切出「左侧已刷」的区域）。
            // 同一次绘制里算出来，所以底色和字色永远在同一条分界线上。
            if (progress > 0.0f && alpha > 0.01f) {
                final int swept = Math.round(w * progress);
                UiDraw.scissor(g, x, y, swept, h);
                UiDraw.text(g, ctx.font, shown, cx, ty,
                        UiDraw.fade(th.sweepText, alpha), th.fontScale, false);
                UiDraw.unscissor(g);
            }
        }
    }
}
