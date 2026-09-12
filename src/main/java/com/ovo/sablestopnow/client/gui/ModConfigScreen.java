package com.ovo.sablestopnow.client.gui;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 模组设置界面 —— 大类选择（对应 newConfigGUI/GUI1.png）。
 *
 * <p>动画：背景模糊 → 左侧蓝色色块<b>从上到下</b>出现 → 标题<b>从左到右</b>出现 →
 * 三个大类依次（错开）从左滑入；离开时全部反向（先淡出，再切换界面）。
 * 全部用 {@link Ease#approach} 指数收敛，即“由快变慢”。
 */
public class ModConfigScreen extends Screen {

    private static final ModConfigCategory[] CATEGORIES = ModConfigCategory.values();

    @Nullable
    private final Screen parent;
    private Screen next;
    /** ⚠ 不能用 next == null 当“没在关闭”的哨兵：Ctrl+O 打开时 parent 就是 null（关闭=回游戏）。 */
    private boolean closing;
    private boolean pendingSwitch;
    private long lastFrame;

    /** 1=完全展开，0=完全收起（离开时反向）。 */
    private float panelAnim;
    private float titleAnim;
    private final float[] itemAnim = new float[CATEGORIES.length];

    public ModConfigScreen(@Nullable final Screen parent) {
        super(Component.translatable("gui.sablestopnow.title"));
        this.parent = parent;
    }

    // ============ 布局 ============
    @Override
    protected void init() {
        // 从大类详情页返回时，这个界面实例是被复用的：上一轮关闭后 closing=true、各动画值都是 0，
        // 若不重置，重新显示的第一帧就会立刻满足“关闭动画播完”，于是每帧再切一次界面 → 闪烁且进不去。
        if (this.closing) {
            this.closing = false;
            this.pendingSwitch = false;
            this.next = null;
            this.panelAnim = 0.0f;
            this.titleAnim = 0.0f;
            java.util.Arrays.fill(this.itemAnim, 0.0f);
            this.lastFrame = 0L;
        }
    }

    private int panelWidth() {
        return Math.clamp((int) (this.width * 0.30f), 92, 170);
    }

    private int rowX() {
        return Math.max(8, (int) (panelWidth() * 0.14f));
    }

    private int rowY(final int index) {
        return (int) (this.height * 0.44f) + index * 30;
    }

    private void beginClose(@Nullable final Screen target) {
        if (!this.closing) {
            this.closing = true;
            this.next = target;
        }
    }

    @Override
    public void onClose() {
        beginClose(this.parent);
    }

    // ============ 每帧动画 ============
    private float updateAnims() {
        final long now = Util.getMillis();
        float dt = this.lastFrame == 0 ? 0.016f : (now - this.lastFrame) / 1000.0f;
        this.lastFrame = now;
        dt = Math.clamp(dt, 0.0f, 0.1f);

        final boolean closing = this.closing;
        this.panelAnim = Ease.approach(this.panelAnim, closing ? 0.0f : 1.0f, closing ? 9.0f : 6.0f, dt);
        this.titleAnim = Ease.approach(this.titleAnim, closing ? 0.0f : 1.0f, closing ? 11.0f : 7.0f, dt);
        for (int i = 0; i < this.itemAnim.length; i++) {
            // 依次出现：后面的行速度略慢 → 天然错开
            final float speed = (closing ? 12.0f : 5.0f) + i * 1.1f;
            this.itemAnim[i] = Ease.approach(this.itemAnim[i], closing ? 0.0f : 1.0f, speed, dt);
        }

        if (closing && this.panelAnim < 0.02f && this.titleAnim < 0.02f && allItemsHidden()) {
            this.pendingSwitch = true;
        }
        return dt;
    }

    private boolean allItemsHidden() {
        for (final float value : this.itemAnim) {
            if (value > 0.02f) {
                return false;
            }
        }
        return true;
    }

    // ============ 渲染 ============
    @Override
    public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        updateAnims();

        final int panelW = panelWidth();

        // 左侧蓝色色块：从上到下展开
        final int panelH = (int) (this.height * Ease.outCubic(this.panelAnim));
        if (panelH > 0) {
            graphics.fill(0, 0, panelW, panelH, Ease.PANEL);
            graphics.fill(panelW - 3, 0, panelW, panelH, Ease.EDGE);
            graphics.fill(0, panelH - 2, panelW, panelH, Ease.PANEL_DARK);
        }

        // 标题：从左到右滑入
        final int titleX = (int) Ease.slide(-panelW * 0.9f, panelW * 0.10f, this.titleAnim);
        final int titleY = (int) (this.height * 0.14f);
        graphics.pose().pushPose();
        graphics.pose().translate(titleX, titleY, 0);
        graphics.pose().scale(1.7f, 1.7f, 1.0f);
        graphics.drawString(this.font, Component.translatable("gui.sablestopnow.title.line1"),
                0, 0, Ease.fade(Ease.TEXT, this.titleAnim), true);
        graphics.pose().popPose();
        graphics.pose().pushPose();
        graphics.pose().translate(titleX, titleY + 16, 0);
        graphics.pose().scale(0.95f, 0.95f, 1.0f);
        graphics.drawString(this.font, Component.translatable("gui.sablestopnow.title.line2"),
                0, 0, Ease.fade(Ease.TEXT_DIM, this.titleAnim), true);
        graphics.pose().popPose();

        // 三个大类
        for (int i = 0; i < CATEGORIES.length; i++) {
            drawCategory(graphics, CATEGORIES[i], i, mouseX, mouseY, panelW);
        }

        // 动画播完后延迟一 tick 再切换界面（避免在 render 中途换屏）
        if (this.pendingSwitch) {
            this.pendingSwitch = false;
            if (this.minecraft != null) {
                final Screen target = this.next;
                this.minecraft.execute(() -> this.minecraft.setScreen(target));
            }
        }
    }

    private void drawCategory(final GuiGraphics graphics, final ModConfigCategory category, final int index,
                              final int mouseX, final int mouseY, final int panelW) {
        final float anim = Ease.outCubic(this.itemAnim[index]);
        if (anim <= 0.01f) {
            return;
        }
        final int x = (int) Ease.slide(-panelW * 0.7f, rowX(), anim);
        final int y = rowY(index);
        final int rowW = panelW - rowX() - 6;
        final int rowH = 26;

        final boolean hovered = mouseX >= x && mouseX <= x + rowW && mouseY >= y && mouseY <= y + rowH;
        final int bg = Ease.fade(hovered ? Ease.BUTTON : Ease.PANEL_DARK, anim * (hovered ? 0.95f : 0.75f));
        graphics.fill(x, y, x + rowW, y + rowH, bg);
        if (hovered) {
            graphics.fill(x, y, x + rowW, y + 1, Ease.fade(Ease.BUTTON_ON, anim));
        }

        // 图标：渲染游戏内物品（16x16 → 放大 1.35 倍）
        final ItemStack icon = category.icon();
        graphics.pose().pushPose();
        graphics.pose().translate(x + 4, y + 5, 0);
        graphics.pose().scale(1.35f, 1.35f, 1.0f);
        if (!icon.isEmpty()) {
            graphics.renderItem(icon, 0, 0);
        } else {
            graphics.fill(0, 0, 16, 16, Ease.fade(0xFF6D8BFF, anim));
        }
        graphics.pose().popPose();

        graphics.drawString(this.font, category.title(), x + 28, y + 5, Ease.fade(Ease.TEXT, anim), true);
        graphics.drawString(this.font, category.subtitle(), x + 28, y + 15, Ease.fade(Ease.TEXT_DIM, anim), false);
    }

    // ============ 交互 ============
    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (this.closing) {
            return true;
        }
        final int panelW = panelWidth();
        final int rowW = panelW - rowX() - 6;
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int x = rowX();
            final int y = rowY(i);
            if (mouseX >= x && mouseX <= x + rowW && mouseY >= y && mouseY <= y + 26) {
                // 记录图标当前位置，交给大类界面做“图标移动并放大”的衔接动画
                beginClose(new ModConfigCategoryScreen(this, CATEGORIES[i], x + 4, y + 5));
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
