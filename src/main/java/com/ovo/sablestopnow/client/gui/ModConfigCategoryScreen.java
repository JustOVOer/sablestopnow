package com.ovo.sablestopnow.client.gui;

import com.ovo.sablestopnow.SablestopNow;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 模组设置界面 —— 大类详情（对应 newConfigGUI/GUI2.png）。
 *
 * <p>动画：大类图标从大类界面里的位置<b>移动并放大</b>到左侧，且随屏幕大小自适应占据左侧空位；
 * 上下滚动时图标会跟着<b>旋转</b>；右侧蓝色色块<b>从左往右</b>出现；设置项<b>从右往左</b>依次出现，
 * 横坐标沿蓝色色块的<b>折线（“&gt;”形）</b>移动。离开（ESC）时全部反向。
 *
 * <p>窄窗口自适应：当「标签 + 控件」一行放不下时自动切换成<b>两行式</b>（标签一行、控件一行），
 * 避免标签和滑条/开关重叠。
 */
public class ModConfigCategoryScreen extends Screen {

    private static final int ROW_SINGLE = 24;
    private static final int ROW_DOUBLE = 40;
    private static final int CONTROL_W = 120;
    private static final int CHEVRON_DEPTH = 44;
    /** 滚动时大图标的旋转系数（度/像素），绕视线轴在屏幕平面内旋转。 */
    private static final float ICON_ROTATION_PER_PIXEL = 1.8f;

    private final Screen parent;
    private final ModConfigCategory category;
    private final List<ConfigOption> options;
    private final int iconFromX;
    private final int iconFromY;

    private Screen next;
    /** ⚠ 不能用 next == null 当“没在关闭”的哨兵（父界面可能为 null）。 */
    private boolean closing;
    private boolean pendingSwitch;
    private long lastFrame;

    private float panelAnim;
    private float iconAnim;
    private final float[] rowAnim;
    private float scrollTarget;
    private float scrollAnim;
    private float iconRotation;

    private boolean twoLine;
    private int rowHeight = ROW_SINGLE;

    private int draggingRow = -1;
    private int hoveredRow = -1;

    @Nullable
    private EditBox textBox;
    private int editingRow = -1;

    public ModConfigCategoryScreen(final Screen parent, final ModConfigCategory category,
                                   final int iconFromX, final int iconFromY) {
        super(category.title());
        this.parent = parent;
        this.category = category;
        this.options = category.options();
        this.rowAnim = new float[this.options.size()];
        this.iconFromX = iconFromX;
        this.iconFromY = iconFromY;
    }

    // ============ 布局 ============
    @Override
    protected void init() {
        // 同 ModConfigScreen：界面实例可能被复用（上一轮关闭状态残留会让它立刻再次关闭 → 闪烁）
        if (this.closing) {
            this.closing = false;
            this.pendingSwitch = false;
            this.next = null;
            this.panelAnim = 0.0f;
            this.iconAnim = 0.0f;
            java.util.Arrays.fill(this.rowAnim, 0.0f);
            this.lastFrame = 0L;
        }
        relayout();
    }

    /** 根据当前窗口宽度决定单行/两行布局（两行 = 标签一行、控件一行，避免重叠）。 */
    private void relayout() {
        final int minAvail = this.width - 14 - (bandLeft() + CHEVRON_DEPTH + 12);
        int need = 0;
        for (final ConfigOption option : this.options) {
            need = Math.max(need, this.font.width(Component.translatable(option.labelKey())) + CONTROL_W + 20);
        }
        this.twoLine = need > minAvail;
        this.rowHeight = this.twoLine ? ROW_DOUBLE : ROW_SINGLE;
        this.scrollTarget = Math.clamp(this.scrollTarget, 0.0f, maxScroll());
    }

    private int bandLeft() {
        return (int) (this.width * 0.34f);
    }

    /** 折线轨迹：蓝色色块左边缘在高度 y 处的 x（“>”形，中间最靠右）。 */
    private int shapeX(final float y) {
        final float t = Math.clamp(y / Math.max(1.0f, this.height), 0.0f, 1.0f);
        final float bump = (t < 0.5f ? t * 2.0f : (1.0f - t) * 2.0f);
        return bandLeft() + (int) (bump * CHEVRON_DEPTH);
    }

    private int rowTop() {
        return 46;
    }

    private int rowY(final int index) {
        return (int) (rowTop() + index * this.rowHeight - this.scrollAnim);
    }

    private float maxScroll() {
        final float content = this.options.size() * (float) this.rowHeight + 16;
        final float view = Math.max(0, this.height - rowTop() - 24);
        return Math.max(0.0f, content - view);
    }

    private int rowLeft(final int y) {
        return shapeX(y + this.rowHeight * 0.5f) + 12;
    }

    private int rowRight() {
        return this.width - 14;
    }

    private int controlX(final int y) {
        return this.twoLine ? rowLeft(y) + 8 : rowRight() - CONTROL_W - 6;
    }

    private int controlW(final int y) {
        return this.twoLine ? Math.max(60, rowRight() - (rowLeft(y) + 8)) : CONTROL_W;
    }

    private int controlY(final int y) {
        return this.twoLine ? y + 22 : y + 7;
    }

    private float iconScale() {
        // 图标按屏幕自适应：占据左侧空位宽度的一部分，同时不超过高度的 28%
        final float left = Math.max(60, bandLeft());
        final float byWidth = left * 0.46f / 16.0f;
        final float byHeight = this.height * 0.28f / 16.0f;
        return Math.max(2.0f, Math.min(byWidth, byHeight));
    }

    private void beginClose(@Nullable final Screen target) {
        if (!this.closing) {
            this.closing = true;
            this.next = target;
        }
    }

    @Override
    public void onClose() {
        commitTextBox();
        beginClose(this.parent);
    }

    // ============ 动画 ============
    private void updateAnims() {
        final long now = Util.getMillis();
        float dt = this.lastFrame == 0 ? 0.016f : (now - this.lastFrame) / 1000.0f;
        this.lastFrame = now;
        dt = Math.clamp(dt, 0.0f, 0.1f);

        final boolean closing = this.closing;
        this.panelAnim = Ease.approach(this.panelAnim, closing ? 0.0f : 1.0f, closing ? 9.0f : 6.0f, dt);
        this.iconAnim = Ease.approach(this.iconAnim, closing ? 0.0f : 1.0f, closing ? 10.0f : 5.5f, dt);
        for (int i = 0; i < this.rowAnim.length; i++) {
            final float speed = (closing ? 13.0f : 6.0f) + i * 0.9f;
            this.rowAnim[i] = Ease.approach(this.rowAnim[i], closing ? 0.0f : 1.0f, speed, dt);
        }
        this.scrollAnim = Ease.approach(this.scrollAnim, this.scrollTarget, 12.0f, dt);
        // 大图标随滚动旋转（绕竖直轴，滚动停止后缓动停下、停留在当前角度）
        this.iconRotation = Ease.approach(this.iconRotation, this.scrollAnim * ICON_ROTATION_PER_PIXEL, 10.0f, dt);

        if (closing && this.panelAnim < 0.02f && this.iconAnim < 0.02f && allRowsHidden()) {
            this.pendingSwitch = true;
        }
    }

    private boolean allRowsHidden() {
        for (final float value : this.rowAnim) {
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
        final Integer hovered = rowAt(mouseX, mouseY);
        this.hoveredRow = hovered == null ? -1 : hovered;

        drawBand(graphics);
        drawIcon(graphics);
        drawRows(graphics, mouseX, mouseY);
        drawFooter(graphics);

        if (this.textBox != null) {
            this.textBox.render(graphics, mouseX, mouseY, partialTick);
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

    /** 右侧蓝色色块：宽度从左往右长出来，左边缘是折线。 */
    private void drawBand(final GuiGraphics graphics) {
        if (this.panelAnim <= 0.01f) {
            return;
        }
        final int right = (int) Ease.slide(this.bandLeft(), this.width, this.panelAnim);
        for (int y = 0; y < this.height; y += 2) {
            final int x = shapeX(y);
            if (right <= x) {
                continue;
            }
            graphics.fill(x, y, right, Math.min(this.height, y + 2), Ease.PANEL);
            // 蓝灰描边
            graphics.fill(x, y, Math.min(right, x + 2), Math.min(this.height, y + 2), Ease.EDGE);
        }
    }

    /** 大类图标：从大类界面里的位置移动并放大到左侧空位中心，随屏幕缩放，并在滚动时旋转。 */
    private void drawIcon(final GuiGraphics graphics) {
        final float anim = Ease.outCubic(this.iconAnim);
        if (anim <= 0.01f) {
            return;
        }
        final float scale = iconScale();
        final float toX = Math.max(6.0f, bandLeft() * 0.5f - 8.0f * scale);
        final float toY = this.height * 0.38f - 8.0f * scale;
        final float x = Ease.slide(this.iconFromX, toX, anim);
        final float y = Ease.slide(this.iconFromY, toY, anim);
        final float size = Ease.slide(1.35f, scale, anim);

        final ItemStack icon = this.category.icon();
        graphics.pose().pushPose();
        graphics.pose().translate(x + 8.0f * size, y + 8.0f * size, 0.0f);
        // 在屏幕平面内旋转（绕视线轴）
        graphics.pose().mulPose(new Quaternionf().rotateZ((float) Math.toRadians(this.iconRotation)));
        graphics.pose().translate(-8.0f * size, -8.0f * size, 0.0f);
        graphics.pose().scale(size, size, 1.0f);
        if (!icon.isEmpty()) {
            graphics.renderItem(icon, 0, 0);
        } else {
            graphics.fill(0, 0, 16, 16, Ease.fade(Ease.BUTTON, anim));
        }
        graphics.pose().popPose();

        // 图标下方的标题（跟随图标位置）
        final int titleY = (int) (y + 16.0f * size + 6.0f);
        final Component title = this.category.title();
        final int titleX = (int) Math.max(6.0f, bandLeft() * 0.5f - this.font.width(title) * 0.5f);
        graphics.drawString(this.font, title, titleX, titleY, Ease.fade(Ease.TEXT, anim), true);
        final Component subtitle = this.category.subtitle();
        final int subX = (int) Math.max(6.0f, bandLeft() * 0.5f - this.font.width(subtitle) * 0.5f);
        graphics.drawString(this.font, subtitle, subX, titleY + 11, Ease.fade(Ease.TEXT_DIM, anim), false);
    }

    private void drawRows(final GuiGraphics graphics, final int mouseX, final int mouseY) {
        final int scissorTop = Math.max(0, rowTop() - 4);
        final int scissorBottom = Math.max(scissorTop, this.height - 20);
        final boolean scissored = scissorBottom > scissorTop;
        if (scissored) {
            graphics.enableScissor(0, scissorTop, this.width, scissorBottom);
        }
        for (int i = 0; i < this.options.size(); i++) {
            final float anim = Ease.outCubic(this.rowAnim[i]);
            if (anim <= 0.01f) {
                continue;
            }
            final ConfigOption option = this.options.get(i);
            final int y = rowY(i);
            if (y > this.height || y + this.rowHeight < rowTop() - this.rowHeight) {
                continue;
            }
            final int x = rowLeft(y) + (int) ((1.0f - anim) * 70);
            final int right = rowRight();
            final boolean hovered = this.hoveredRow == i;

            graphics.fill(x, y, right, y + this.rowHeight - 4, Ease.fade(hovered ? 0x40FFFFFF : 0x28000000, anim));
            graphics.drawString(this.font, Component.translatable(option.labelKey()),
                    x + 6, y + 6, Ease.fade(Ease.TEXT, anim), true);

            drawValue(graphics, option, i, x, y, right, anim, hovered);
        }
        if (scissored) {
            graphics.disableScissor();
        }
    }

    private void drawValue(final GuiGraphics graphics, final ConfigOption option, final int index,
                           final int x, final int y, final int right, final float anim, final boolean hovered) {
        final int cx = controlX(y);
        final int cw = controlW(y);
        final int cy = controlY(y);
        // 两行式时数值显示在标签行右端，避免和滑条抢位置
        final int valueTextX = right - 6;
        switch (option.kind()) {
            case BOOL -> {
                final boolean on = option.getBool();
                graphics.fill(cx, cy - 3, cx + 34, cy + 9, Ease.fade(on ? Ease.BUTTON_ON : 0xFF2A3A5C, anim));
                final int knobX = on ? cx + 24 : cx + 2;
                graphics.fill(knobX, cy - 1, knobX + 10, cy + 7, Ease.fade(0xFFFFFFFF, anim));
                graphics.drawString(this.font, Component.translatable(on
                                ? "gui.sablestopnow.value.on" : "gui.sablestopnow.value.off"),
                        cx + 40, cy - 2, Ease.fade(Ease.TEXT_DIM, anim), false);
            }
            case NUMBER -> {
                if (this.twoLine) {
                    final String value = option.displayValue();
                    graphics.drawString(this.font, value, valueTextX - this.font.width(value), y + 6,
                            Ease.fade(Ease.TEXT_DIM, anim), false);
                } else {
                    final String value = option.displayValue();
                    graphics.drawString(this.font, value, cx - this.font.width(value) - 6, cy - 2,
                            Ease.fade(Ease.TEXT_DIM, anim), false);
                }
                graphics.fill(cx, cy, cx + cw, cy + 4, Ease.fade(0xFF1B2740, anim));
                final int fill = (int) (cw * option.getFraction());
                graphics.fill(cx, cy, cx + fill, cy + 4, Ease.fade(Ease.BUTTON, anim));
                graphics.fill(cx + fill - 1, cy - 3, cx + fill + 1, cy + 7, Ease.fade(Ease.BUTTON_ON, anim));
            }
            case STRING_LIST -> {
                final String text = option.getText();
                final String shown = this.font.plainSubstrByWidth(text.isEmpty()
                        ? Component.translatable("gui.sablestopnow.value.empty").getString() : text, Math.max(40, cw - 8));
                graphics.fill(cx, cy - 4, cx + cw, cy + 10, Ease.fade(0x30000000, anim));
                graphics.fill(cx, cy + 9, cx + cw, cy + 10, Ease.fade(Ease.EDGE, anim));
                graphics.drawString(this.font, shown, cx + 4, cy - 2,
                        Ease.fade(hovered ? Ease.TEXT : Ease.TEXT_DIM, anim), false);
            }
            case ACTION -> {
                graphics.fill(cx, cy - 4, cx + Math.min(cw, 130), cy + 12,
                        Ease.fade(hovered ? Ease.BUTTON_HOVER : Ease.BUTTON, anim));
                graphics.drawCenteredString(this.font, Component.translatable(option.labelKey() + ".button"),
                        cx + Math.min(cw, 130) / 2, cy + 1, Ease.fade(Ease.TEXT, anim));
            }
        }
    }

    private void drawFooter(final GuiGraphics graphics) {
        final int descY = this.height - 16;
        if (this.hoveredRow >= 0 && this.hoveredRow < this.options.size()) {
            final ConfigOption option = this.options.get(this.hoveredRow);
            final String desc = this.font.plainSubstrByWidth(
                    Component.translatable(option.descKey()).getString(), Math.max(40, this.width - 16));
            graphics.drawString(this.font, desc, 8, descY, 0xFFDCE5F5, true);
        }
        graphics.drawString(this.font, Component.translatable("gui.sablestopnow.back"), 8, this.height - 28,
                0xFFA9BBD8, false);
    }

    // ============ 交互 ============
    @Nullable
    private Integer rowAt(final int mouseX, final int mouseY) {
        for (int i = 0; i < this.options.size(); i++) {
            final int y = rowY(i);
            final int x = rowLeft(y);
            if (mouseY >= y && mouseY <= y + this.rowHeight - 4 && mouseX >= x && mouseX <= rowRight()) {
                return i;
            }
        }
        return null;
    }

    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (this.textBox != null) {
            if (this.textBox.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
            commitTextBox();
            return true;
        }
        if (this.closing) {
            return true;
        }
        final Integer index = rowAt((int) mouseX, (int) mouseY);
        if (index == null) {
            return super.mouseClicked(mouseX, mouseY, button);
        }
        final ConfigOption option = this.options.get(index);
        switch (option.kind()) {
            case BOOL -> {
                option.setBool(!option.getBool());
                save();
            }
            case NUMBER -> {
                this.draggingRow = index;
                applySlider(option, index, mouseX);
            }
            case STRING_LIST -> openTextBox(index, option);
            case ACTION -> option.runAction();
        }
        return true;
    }

    @Override
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button, final double dragX, final double dragY) {
        if (this.draggingRow >= 0 && this.draggingRow < this.options.size()) {
            applySlider(this.options.get(this.draggingRow), this.draggingRow, mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        if (this.draggingRow >= 0) {
            this.draggingRow = -1;
            save();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void applySlider(final ConfigOption option, final int index, final double mouseX) {
        final int y = rowY(index);
        final int cx = controlX(y);
        final int cw = Math.max(1, controlW(y));
        option.setFraction((mouseX - cx) / cw);
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double scrollX, final double scrollY) {
        this.scrollTarget = Math.clamp(this.scrollTarget - (float) scrollY * 20.0f, 0.0f, maxScroll());
        return true;
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (this.textBox != null) {
            if (keyCode == 257 || keyCode == 335) { // Enter
                commitTextBox();
                return true;
            }
            if (keyCode == 256) { // Esc
                closeTextBox();
                return true;
            }
            return this.textBox.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(final char codePoint, final int modifiers) {
        if (this.textBox != null) {
            return this.textBox.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    // ============ 字符串列表编辑 ============
    private void openTextBox(final int index, final ConfigOption option) {
        closeTextBox();
        this.editingRow = index;
        final int y = rowY(index);
        final int cx = controlX(y);
        final int cw = controlW(y);
        this.textBox = new EditBox(this.font, cx + 2, controlY(y) - 4, Math.max(40, cw - 4), 14,
                Component.translatable(option.labelKey()));
        this.textBox.setMaxLength(512);
        this.textBox.setValue(option.getText());
        this.textBox.setFocused(true);
        this.setFocused(this.textBox);
    }

    private void commitTextBox() {
        if (this.textBox == null) {
            return;
        }
        final int index = this.editingRow;
        final String value = this.textBox.getValue();
        closeTextBox();
        if (index >= 0 && index < this.options.size()) {
            this.options.get(index).setText(value);
            save();
        }
    }

    private void closeTextBox() {
        if (this.textBox != null) {
            this.removeWidget(this.textBox);
            this.textBox = null;
            this.editingRow = -1;
        }
    }

    private void save() {
        SablestopNow.saveConfig();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
