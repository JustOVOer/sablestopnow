package com.ovo.sablestopnow.client.gui.lib;

import com.ovo.sablestopnow.client.ModRenderTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI 库的入口：管理所有覆盖层（{@link UiLayer}），统一处理绘制与输入。
 *
 * <p>绘制由 {@code RenderGuiEvent.Post} 驱动（{@link UiRenderHook}），输入由既有的
 * MouseHandler / KeyboardHandler 两个 mixin 转发。库里所有界面都只通过这里进门，
 * 上层代码不要再直接 {@code setScreen}（配置界面除外，它需要背景模糊与暂停语义，
 * 走 {@link UiScreen}）。
 */
public final class Ui {

    private static final List<UiLayer> LAYERS = new ArrayList<>();
    private static final UiContext CTX = new UiContext();

    private static long lastFrameNanos;
    /** 本帧的帧间隔（由 {@link #updateFrameDelta()} 每帧算一次，其它地方只读）。 */
    private static double frameDelta = 1.0 / 60.0;
    private static double mouseX;
    private static double mouseY;
    private static boolean mouseInitialized;

    /** 最近一帧登记「已占用」的矩形（HUD 避让用）。 */
    private static final List<int[]> RESERVED = new ArrayList<>();

    private Ui() {
    }

    // ============ 层管理 ============

    public static UiLayer open(final UiLayer layer) {
        final UiLayer existing = layer(layer.name);
        if (existing != null) {
            existing.visible = true;
            existing.closeRequested = false;
            toFront(existing);
            return existing;
        }
        LAYERS.add(layer);
        // 出现动画必须在这里起跑：Anim 自己不推进（见 render 里 CTX.tick(layer.appear)）
        layer.appear.snap(0.0).to(1.0);
        mouseInitialized = false;
        return layer;
    }

    public static UiLayer open(final String name) {
        return open(UiLayer.of(name));
    }

    public static void close(final UiLayer layer) {
        if (layer == null) {
            return;
        }
        LAYERS.remove(layer);
        if (layer.onClose != null) {
            layer.onClose.run();
        }
    }

    public static void close(final String name) {
        close(layer(name));
    }

    public static void closeAll() {
        final List<UiLayer> copy = new ArrayList<>(LAYERS);
        for (final UiLayer layer : copy) {
            close(layer);
        }
    }

    public static void toFront(final UiLayer layer) {
        if (LAYERS.remove(layer)) {
            LAYERS.add(layer);
        }
    }

    @Nullable
    public static UiLayer layer(final String name) {
        for (final UiLayer layer : LAYERS) {
            if (layer.name.equals(name)) {
                return layer;
            }
        }
        return null;
    }

    public static boolean isOpen(final String name) {
        final UiLayer layer = layer(name);
        return layer != null && layer.visible;
    }

    public static List<UiLayer> layers() {
        return LAYERS;
    }

    public static boolean anyOpen() {
        for (final UiLayer layer : LAYERS) {
            if (layer.visible) {
                return true;
            }
        }
        return false;
    }

    /** 是否有点开着的层会吞掉世界点击。 */
    public static boolean blocksWorldInput() {
        for (final UiLayer layer : LAYERS) {
            if (layer.visible && layer.consumeInput && !layer.hud) {
                return true;
            }
        }
        return false;
    }

    /** 已占用的屏幕矩形（HUD 避让用；坐标系与 GUI 一致）。 */
    public static List<int[]> reservedRects() {
        return RESERVED;
    }

    @Nullable
    public static UiLayer top() {
        for (int i = LAYERS.size() - 1; i >= 0; i--) {
            final UiLayer layer = LAYERS.get(i);
            if (layer.visible) {
                return layer;
            }
        }
        return null;
    }

    // ============ 鼠标坐标 ============

    public static int mouseX() {
        return (int) mouseX;
    }

    public static int mouseY() {
        return (int) mouseY;
    }

    public static double mouseXd() {
        return mouseX;
    }

    public static double mouseYd() {
        return mouseY;
    }

    private static void updateMouse() {
        final Minecraft mc = Minecraft.getInstance();
        final var window = mc.getWindow();
        if (window == null || mc.mouseHandler == null) {
            return;
        }
        final int guiW = mc.getWindow().getGuiScaledWidth();
        final int guiH = mc.getWindow().getGuiScaledHeight();
        mouseX = mc.mouseHandler.xpos() * guiW / (double) window.getScreenWidth();
        mouseY = mc.mouseHandler.ypos() * guiH / (double) window.getScreenHeight();
    }

    // ============ 绘制 ============

    public static void render(final GuiGraphics graphics) {
        final Minecraft mc = Minecraft.getInstance();
        final int guiW = graphics.guiWidth();
        final int guiH = graphics.guiHeight();
        if (mc.player == null || mc.level == null) {
            return;
        }
        // 别的界面开着时（暂停菜单、设置屏）不画我们的覆盖层，避免互相遮挡
        if (mc.screen != null && !(mc.screen instanceof UiScreen)) {
            return;
        }
        updateMouse();
        updateFrameDelta();
        final double dt = frameDelta();

        // 先按当前层集合走一帧；只有真的关掉了层才需要再走一遍（否则动画会被 dt 反复推进）
        for (int pass = 0; pass < 2; pass++) {
            for (final UiLayer layer : new ArrayList<>(LAYERS)) {
                layer.resize(guiW, guiH);
                CTX.begin(layer.root, dt, mouseX, mouseY, guiW, guiH);
                layer.root.tick(CTX, dt);
            }
            boolean closed = false;
            for (final UiLayer layer : new ArrayList<>(LAYERS)) {
                if (layer.closeRequested) {
                    close(layer);
                    closed = true;
                }
            }
            if (!closed) {
                break;
            }
        }

        if (!mouseInitialized) {
            for (final UiLayer layer : LAYERS) {
                layer.root.mouseMoved(CTX, mouseX, mouseY);
            }
            mouseInitialized = true;
        }
        for (final UiLayer layer : LAYERS) {
            CTX.begin(layer.root, dt, mouseX, mouseY, guiW, guiH);
            layer.root.mouseMoved(CTX, mouseX, mouseY);
        }

        // 已占用矩形的清空必须放在「绘制之前」：HUD 是在上面那个 tick 阶段问 UiDock 要位置的，
        // 若在这里之前清空，HUD 每帧看到的占用表都是空的，避让等于没做。放在这里，tick 阶段读到的
        // 就是上一帧真正画出来的面板（一层界面的位置一帧之内不会跳，够用）。
        RESERVED.clear();
        for (final UiLayer layer : LAYERS) {
            if (!layer.visible) {
                continue;
            }
            CTX.begin(layer.root, dt, mouseX, mouseY, guiW, guiH);
            // ⚠ 必须在这里 tick 层自己的出现动画：Anim 自己不会推进，
            // 漏了这一步 layer.appear 永远停在 0（同一类漏 tick 的 bug 在 RevealPanel 上刚出过一次：
            // 那条导致 Ctrl+O 的设置界面整片空白）。
            CTX.tick(layer.appear);
            final float slide = 1.0f - layer.appear.progress();
            final var pose = graphics.pose();
            final boolean shifted = slide > 0.001f && (layer.slideFromX != 0 || layer.slideFromY != 0);
            if (shifted) {
                pose.pushPose();
                pose.translate(layer.slideFromX * slide, layer.slideFromY * slide, 0.0f);
            }
            layer.root.render(CTX, graphics);
            if (shifted) {
                pose.popPose();
            }
            if (layer.reserveSpace) {
                collectReserved(layer.root);
            }
        }

        // 重置着色器颜色：库的绘制不该影响后续 HUD
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }

    /**
     * 登记一层真正占用的屏幕矩形。
     *
     * <p>必须按<b>顶层控件</b>登记，不能登记 {@link UiRoot} 自己：根节点铺满整屏（它就是屏幕），
     * 拿它当「已占用」等于宣告整屏都被占了，任何 HUD 都避不开 —— 避让逻辑会永远挪不动。
     * 没有子控件的层退回登记根矩形。
     */
    private static void collectReserved(final UiRoot root) {
        boolean any = false;
        for (final Widget child : root.children) {
            if (!child.visible || child.w <= 0 || child.h <= 0) {
                continue;
            }
            RESERVED.add(new int[]{child.x, child.y, child.w, child.h});
            any = true;
        }
        if (!any) {
            RESERVED.add(new int[]{root.x, root.y, root.w, root.h});
        }
    }

    /**
     * 每帧算一次帧间隔（在 {@link #render} 里调用一次）。
     *
     * <p>⚠ 刻意拆成「算一次 + 到处只读」：以前 {@link #frameDelta()} 自己会重置计时器，
     * 而它被渲染、相机、模型模式各调一次 —— 同一帧里第二个调用者拿到的 dt 几乎为 0，
     * 缓动就会一顿一顿的（用户实测反馈「中键旋转有强烈阻滞感」）。
     */
    public static void updateFrameDelta() {
        final long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            frameDelta = 1.0 / 60.0;
            return;
        }
        final double dt = (now - lastFrameNanos) / 1.0e9;
        lastFrameNanos = now;
        frameDelta = Math.max(0.0, Math.min(0.1, dt));
    }

    /** 纯读取：本帧的帧间隔（不会推进计时器）。 */
    public static double frameDelta() {
        return frameDelta;
    }

    // ============ 输入 ============

    /** @return true 表示吞掉这次鼠标按键事件 */
    public static boolean handleMouse(final int button, final int action, final int modifiers) {
        if (!inputAllowed()) {
            return false;
        }
        updateMouse();
        final int guiW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        final int guiH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        boolean consumed = false;
        if (action == GLFW.GLFW_PRESS) {
            diagPress(button, mouseX, mouseY);
        }
        for (int i = LAYERS.size() - 1; i >= 0; i--) {
            final UiLayer layer = LAYERS.get(i);
            if (!layer.visible || !layer.consumeInput) {
                continue;
            }
            CTX.begin(layer.root, lastDt(), mouseX, mouseY, guiW, guiH);
            if (action == GLFW.GLFW_PRESS) {
                if (layer.root.mouseDown(CTX, mouseX, mouseY, button)) {
                    consumed = true;
                    break;
                }
            } else if (action == GLFW.GLFW_RELEASE) {
                if (layer.root.mouseReleased(CTX, mouseX, mouseY, button)) {
                    consumed = true;
                    break;
                }
            }
        }
        return consumed;
    }

    // ============ 临时输入诊断（定位「按键没反应」，问题解决后删） ============

    private static long lastInputDiagMs;

    /**
     * 记录一次鼠标按下落到了哪个控件、有没有被吞掉。
     *
     * <p>用来区分两种完全不同的「按键没反应」：①点击压根没送到控件（命中/图层问题）；
     * ②送到了、动作也跑了，但界面上看不出变化。没有这条日志只能靠猜。
     */
    private static void diagPress(final int button, final double mx, final double my) {
        final long now = System.currentTimeMillis();
        if (now - lastInputDiagMs < 250L) {
            return;
        }
        lastInputDiagMs = now;
        final StringBuilder sb = new StringBuilder();
        for (final UiLayer layer : LAYERS) {
            if (!layer.visible) {
                continue;
            }
            final Widget hit = layer.root.hitTest(mx, my);
            sb.append(layer.name).append('=')
                    .append(hit == null ? "null" : (hit == layer.root ? "root" : hit.getClass().getSimpleName()))
                    .append("(consume=").append(layer.consumeInput).append(") ");
        }
        com.ovo.sablestopnow.SablestopNow.LOGGER.info("[ui-diag] press button={} at ({},{}) -> {}",
                button, (int) mx, (int) my, sb.toString().trim());
    }

    private static long lastActionDiagMs;

    /**
     * 记录一次按钮动作：松开事件送到了按钮没有、有没有可执行的动作。
     *
     * <p>「点了没反应」有三种完全不同的成因 —— ①释放事件没送到按钮（层间派发问题）、
     * ②送到了但指针已经移出按钮、③动作跑了但没有可见效果。画面上三者一模一样，
     * 这条日志把前两种与第三种区分开。
     */
    static void diagAction(final String label, final boolean inside, final boolean hasAction) {
        final long now = System.currentTimeMillis();
        if (now - lastActionDiagMs < 200L) {
            return;
        }
        lastActionDiagMs = now;
        com.ovo.sablestopnow.SablestopNow.LOGGER.info("[ui-diag] mouseUp \"{}\" inside={} hasAction={}",
                label, inside, hasAction);
    }

    public static boolean handleScroll(final double delta) {
        if (!inputAllowed()) {
            return false;
        }
        updateMouse();
        final int guiW = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        final int guiH = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        for (int i = LAYERS.size() - 1; i >= 0; i--) {
            final UiLayer layer = LAYERS.get(i);
            if (!layer.visible || !layer.consumeInput) {
                continue;
            }
            CTX.begin(layer.root, lastDt(), mouseX, mouseY, guiW, guiH);
            if (layer.root.mouseScroll(CTX, mouseX, mouseY, delta)) {
                return true;
            }
        }
        return false;
    }

    /** 键盘按下/重复；返回 true 表示吞掉。 */
    public static boolean handleKey(final int keyCode, final int scanCode, final int action, final int modifiers) {
        if (!inputAllowed()) {
            return false;
        }
        final Minecraft mc = Minecraft.getInstance();
        final int guiW = mc.getWindow().getGuiScaledWidth();
        final int guiH = mc.getWindow().getGuiScaledHeight();
        for (int i = LAYERS.size() - 1; i >= 0; i--) {
            final UiLayer layer = LAYERS.get(i);
            if (!layer.visible || !layer.consumeKeys) {
                continue;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS && layer.closeOnEscape
                    && layer.root.focused == null && !layer.root.hasPopup()) {
                layer.requestClose();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS && layer.root.hasPopup()) {
                layer.root.closePopup();
                return true;
            }
            CTX.begin(layer.root, lastDt(), mouseX, mouseY, guiW, guiH);
            if (layer.root.keyPressed(CTX, keyCode, scanCode, modifiers)) {
                return true;
            }
            // ⚠⚠ **有输入框在编辑时，任何按键都要吞掉**（非 Escape）。
            //   原来只吞"输入框自己处理了的键"，而 TextField 只在**控制键**（退格/方向键…）上
            //   返回 true；普通字母（e、i…）是通过 `charTyped` 送进来的，`keyPressed` 返回 false
            //   → 事件继续往下传给原版 → **打字时按 e 就开了背包**（用户实测反馈）。
            //   所以这里补一条：焦点在文本输入上时，按键一律消费，字符仍由 charTyped 正常送入。
            if (action == GLFW.GLFW_PRESS && layer.root.focused instanceof TextField
                    && keyCode != GLFW.GLFW_KEY_ESCAPE) {
                return true;
            }
        }
        return false;
    }

    public static boolean handleChar(final char codePoint, final int modifiers) {
        if (!inputAllowed()) {
            return false;
        }
        final Minecraft mc = Minecraft.getInstance();
        final int guiW = mc.getWindow().getGuiScaledWidth();
        final int guiH = mc.getWindow().getGuiScaledHeight();
        for (int i = LAYERS.size() - 1; i >= 0; i--) {
            final UiLayer layer = LAYERS.get(i);
            if (!layer.visible || !layer.consumeKeys) {
                continue;
            }
            CTX.begin(layer.root, lastDt(), mouseX, mouseY, guiW, guiH);
            if (layer.root.charTyped(CTX, codePoint, modifiers)) {
                return true;
            }
        }
        return false;
    }

    private static boolean inputAllowed() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        return anyOpen();
    }

    private static double lastDt() {
        return 1.0 / 60.0;
    }
}
