package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 需要「独占屏幕」语义的界面（配置界面：背景模糊、暂停游戏、Esc 返回）用这个适配器。
 *
 * <p>它本身只是一个壳：所有控件都是库里的 {@link Widget}，绘制/输入全部转交给 {@link UiRoot}。
 * 模型模式下的面板不要用它，用 {@link UiLayer} 覆盖层（不占屏幕栈、不暂停游戏）。
 */
public class UiScreen extends Screen {

    public final UiRoot root = new UiRoot();
    protected final UiContext ctx = new UiContext();

    /** 关闭后返回的界面。 */
    @Nullable
    protected Screen parent;
    /** 是否画原版的模糊背景。 */
    public boolean useBlurBackground = true;

    private long lastNanos;

    public UiScreen(final Component title, @Nullable final Screen parent) {
        super(title);
        this.parent = parent;
    }

    /** 子类在这里搭控件树。 */
    protected void build() {
    }

    @Override
    protected void init() {
        root.resize(width, height);
        build();
    }

    @Override
    public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        if (useBlurBackground) {
            renderBackground(graphics, mouseX, mouseY, partialTick);
        } else {
            graphics.fill(0, 0, width, height, 0x88000000);
        }
        final double dt = delta();
        ctx.begin(root, dt, mouseX, mouseY, width, height);
        root.tick(ctx, dt);
        root.mouseMoved(ctx, mouseX, mouseY);
        root.render(ctx, graphics);
    }

    private double delta() {
        final long now = System.nanoTime();
        if (lastNanos == 0L) {
            lastNanos = now;
            return 1.0 / 60.0;
        }
        final double dt = (now - lastNanos) / 1.0e9;
        lastNanos = now;
        return Math.max(0.0, Math.min(0.1, dt));
    }

    private double lastMouseX;
    private double lastMouseY;

    private void begin(final double mx, final double my) {
        // ⚠ 必须用事件给的真实坐标：Ui.mouseXd() 只在 Ui.render 里更新，
        // 而开着 Screen 时 Ui.render 会提前返回 —— 用它会一直派发到陈旧的鼠标位置，
        // 表现就是「设置界面里点哪都没反应」。
        this.lastMouseX = mx;
        this.lastMouseY = my;
        ctx.begin(root, 1.0 / 60.0, mx, my, width, height);
    }

    /** 键盘事件没有鼠标坐标，用最后一次已知位置（键盘处理不看坐标，只是保持上下文一致）。 */
    private void begin() {
        ctx.begin(root, 1.0 / 60.0, this.lastMouseX, this.lastMouseY, width, height);
    }

    @Override
    public boolean mouseClicked(final double mx, final double my, final int button) {
        begin(mx, my);
        return root.mouseDown(ctx, mx, my, button);
    }

    @Override
    public boolean mouseReleased(final double mx, final double my, final int button) {
        begin(mx, my);
        return root.mouseReleased(ctx, mx, my, button);
    }

    @Override
    public void mouseMoved(final double mx, final double my) {
        begin(mx, my);
        root.mouseMoved(ctx, mx, my);
    }

    @Override
    public boolean mouseDragged(final double mx, final double my, final int button, final double dragX,
                                final double dragY) {
        begin(mx, my);
        root.mouseMoved(ctx, mx, my);
        return true;
    }

    @Override
    public boolean mouseScrolled(final double mx, final double my, final double scrollX, final double scrollY) {
        begin(mx, my);
        return root.mouseScroll(ctx, mx, my, scrollY);
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        begin();
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && root.focused == null && !root.hasPopup()) {
            onClose();
            return true;
        }
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE && root.hasPopup()) {
            root.closePopup();
            return true;
        }
        return root.keyPressed(ctx, keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(final char codePoint, final int modifiers) {
        begin();
        return root.charTyped(ctx, codePoint, modifiers);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
