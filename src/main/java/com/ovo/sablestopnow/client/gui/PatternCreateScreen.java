package com.ovo.sablestopnow.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * **阵列的次数/间距**输入 ✓（item 10）。
 *
 * <p>两个方向各 4 个数字：次数（1–64）与间距（可负 = 反向）✓
 * 改动即时预览（回调），"创建"才真正落地 ✓（与其它特征操作同一纪律）
 */
public final class PatternCreateScreen extends Screen {

    /** 回调：preview(次数1, 间距1, 次数2, 间距2) / create(...) / cancel() */
    public interface Sink {
        void preview(int n1, double s1, int n2, double s2);

        void create(int n1, double s1, int n2, double s2);

        void cancel();
    }

    private final Screen parent;
    private final Sink sink;
    private final int dirs;
    private final int n1Init;
    private final int n2Init;
    private final double s1Init;
    private final double s2Init;

    private EditBox fN1;
    private EditBox fS1;
    private EditBox fN2;
    private EditBox fS2;

    public PatternCreateScreen(final Screen parent, final int dirs, final int n1, final double s1,
                               final int n2, final double s2, final Sink sink) {
        super(Component.translatable("gui.sablestopnow.pattern.title"));
        this.parent = parent;
        this.sink = sink;
        this.dirs = dirs;
        this.n1Init = n1;
        this.s1Init = s1;
        this.n2Init = n2;
        this.s2Init = s2;
    }

    @Override
    protected void init() {
        final int cx = this.width / 2;
        final int y = this.height / 2 - 24;
        fN1 = numberField(cx - 110, y, String.valueOf(n1Init));
        fS1 = numberField(cx - 50, y, String.format(java.util.Locale.ROOT, "%.2f", s1Init));
        fN2 = numberField(cx + 20, y, String.valueOf(n2Init));
        fS2 = numberField(cx + 80, y, String.format(java.util.Locale.ROOT, "%.2f", s2Init));
        addRenderableWidget(Button.builder(Component.translatable("gui.sablestopnow.pattern.ok"),
                        b -> {
                            final double[] v = values();
                            sink.create((int) v[0], v[1], (int) v[2], v[3]);
                            this.onClose();
                        })
                .bounds(cx - 60, y + 34, 56, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.sablestopnow.pattern.cancel"),
                        b -> {
                            sink.cancel();
                            this.onClose();
                        })
                .bounds(cx + 4, y + 34, 56, 20).build());
    }

    private EditBox numberField(final int x, final int y, final String value) {
        final EditBox f = new EditBox(this.font, x, y, 54, 18,
                Component.translatable("gui.sablestopnow.pattern.value"));
        f.setMaxLength(8);
        f.setValue(value);
        f.setResponder(t -> {
            final double[] v = values();
            sink.preview((int) v[0], v[1], (int) v[2], v[3]);
        });
        addRenderableWidget(f);
        return f;
    }

    /** {n1, s1, n2, s2}（解析失败用默认）✓ */
    private double[] values() {
        int n1 = n1Init;
        int n2 = n2Init;
        double s1 = s1Init;
        double s2 = s2Init;
        try {
            n1 = Math.max(1, Math.min(64, Integer.parseInt(fN1.getValue().trim())));
        } catch (final RuntimeException ignored) {
        }
        try {
            n2 = Math.max(1, Math.min(64, Integer.parseInt(fN2.getValue().trim())));
        } catch (final RuntimeException ignored) {
        }
        try {
            s1 = Double.parseDouble(fS1.getValue().trim());
        } catch (final RuntimeException ignored) {
        }
        try {
            s2 = Double.parseDouble(fS2.getValue().trim());
        } catch (final RuntimeException ignored) {
        }
        return new double[] {n1, s1, n2, s2};
    }

    @Override
    public void render(final GuiGraphics g, final int mouseX, final int mouseY, final float partial) {
        this.renderBackground(g, mouseX, mouseY, partial);
        final int cx = this.width / 2;
        g.drawCenteredString(this.font, this.title, cx, this.height / 2 - 52, 0xFFFFFF);
        final int y = this.height / 2 - 36;
        g.drawString(this.font, Component.translatable("gui.sablestopnow.pattern.dir1"),
                cx - 112, y - 10, 0xA8B8C8, false);
        g.drawString(this.font, Component.translatable("gui.sablestopnow.pattern.dir2"),
                cx + 18, y - 10, dirs >= 2 ? 0xA8B8C8 : 0x55606C, false);
        g.drawString(this.font, Component.translatable("gui.sablestopnow.pattern.n"),
                cx - 110, y - 2, 0x8894A4, false);
        g.drawString(this.font, Component.translatable("gui.sablestopnow.pattern.s"),
                cx - 50, y - 2, 0x8894A4, false);
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}
