package com.ovo.sablestopnow.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * **新建基准面**的输入框 ✓（item 7）：距离可负，改动即时预览（由调用方的回调刷新黄色线框）。
 *
 * <p>说明（与设计文档的一处**有意偏差**）：文档写的是"左侧距离输入"，
 * 这里做成**模态小框**——因为基准面创建是一个"一次输入 + 确定"的动作，
 * 用模态能避免左栏状态机再长一个分支，也更容易保证"取消 ⇒ 预览消失"（GUI 自查项 8）✓
 */
public final class PlaneCreateScreen extends Screen {

    /** 距离变化（实时预览）与最终确认 ✓ */
    public interface Sink {
        void preview(double distance);

        void create(double distance);

        void cancel();
    }

    private final Screen parent;
    private final Sink sink;
    private EditBox field;
    private double distance;

    public PlaneCreateScreen(final Screen parent, final double initial, final Sink sink) {
        super(Component.translatable("gui.sablestopnow.plane.title"));
        this.parent = parent;
        this.sink = sink;
        this.distance = initial;
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        final int y = this.height / 2 - 10;
        field = new EditBox(this.font, cx - 60, y, 120, 18,
                Component.translatable("gui.sablestopnow.plane.distance"));
        field.setMaxLength(8);
        field.setValue(String.format(java.util.Locale.ROOT, "%.2f", distance));
        field.setResponder(text -> {
            try {
                distance = Double.parseDouble(text.trim());
                sink.preview(distance); // 实时预览 ✓
            } catch (final NumberFormatException ignored) {
                // 输入中途（"-"/""）不算错 ✓
            }
        });
        addRenderableWidget(field);
        addRenderableWidget(Button.builder(Component.translatable("gui.sablestopnow.plane.ok"),
                        b -> {
                            sink.create(distance);
                            this.onClose();
                        })
                .bounds(cx - 60, y + 26, 56, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.sablestopnow.plane.cancel"),
                        b -> {
                            sink.cancel();
                            this.onClose();
                        })
                .bounds(cx + 4, y + 26, 56, 20).build());
    }

    @Override
    public void render(final GuiGraphics g, final int mouseX, final int mouseY, final float partial) {
        this.renderBackground(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 34, 0xFFFFFF);
        g.drawString(this.font, Component.translatable("gui.sablestopnow.plane.distance"),
                this.width / 2 - 60, this.height / 2 - 22, 0xA8B8C8, false);
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}
