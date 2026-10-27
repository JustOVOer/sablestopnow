package com.ovo.sablestopnow.client.gui;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.SablestopNowConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * **通用调色板** ✓（item 2）。
 *
 * <p>8 项颜色，每项：色块预览 + **H/S/V 三条滑条** + **十六进制输入框** + **恢复默认** ✓
 * 改动立刻写进配置并保存 ⇒ 渲染侧每帧读配置，**即时生效** ✓
 *
 * <p>用原版控件（Slider/EditBox/Button）实现：调色是纯 2D 表单，
 * 没必要引入自研 UI 库的层/动画体系（少一层就少一类层叠 bug ✓ 见 GUI 自查清单）。
 */
public final class ColorPaletteScreen extends Screen {

    /** 一项可调颜色 ✓ */
    private record Item(String key, Component label, String defaultValue,
                        java.util.function.Function<SablestopNowConfig.Config,
                                net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<String>> getter) {
    }

    private static final List<Item> ITEMS = List.of(
            new Item("sketch_line", Component.translatable("gui.sablestopnow.color.sketch_line"),
                    "FF3FF2D9", c -> c.colorSketchLine),
            new Item("fully_defined", Component.translatable("gui.sablestopnow.color.fully_defined"),
                    "FF59FF52", c -> c.colorFullyDefined),
            new Item("selected", Component.translatable("gui.sablestopnow.color.selected"),
                    "FFFF8C26", c -> c.colorSelected),
            new Item("hover", Component.translatable("gui.sablestopnow.color.hover"),
                    "FFFFFFFF", c -> c.colorHover),
            new Item("reference", Component.translatable("gui.sablestopnow.color.reference"),
                    "FF9EA8C7", c -> c.colorReference),
            new Item("dimension", Component.translatable("gui.sablestopnow.color.dimension"),
                    "FFFFFFFF", c -> c.colorDimension),
            new Item("grid", Component.translatable("gui.sablestopnow.color.grid"),
                    "FFC8C8C8", c -> c.colorGrid),
            new Item("preview", Component.translatable("gui.sablestopnow.color.preview"),
                    "FFFFD400", c -> c.colorPreview));

    private final Screen parent;
    private final EditBox[] hexFields = new EditBox[ITEMS.size()];
    private final ChannelSlider[] hue = new ChannelSlider[ITEMS.size()];
    private final ChannelSlider[] sat = new ChannelSlider[ITEMS.size()];
    private final ChannelSlider[] val = new ChannelSlider[ITEMS.size()];

    /** 每行的当前颜色（0xRRGGBB，不含 alpha）✓ —— 作为滑条/输入的单一真相。 */
    private final int[] rgb = new int[ITEMS.size()];

    public ColorPaletteScreen(final Screen parent) {
        super(Component.translatable("gui.sablestopnow.color.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        final int rowH = 22;
        final int top = 40;
        final int left = Math.max(10, this.width / 2 - 170);
        final int labelW = 74;
        final int swatchW = 14;
        final int sliderW = 52;
        final int hexW = 66;
        for (int i = 0; i < ITEMS.size(); i++) {
            final int idx = i;
            final int y = top + i * rowH;
            rgb[i] = parseRgb(read(ITEMS.get(i)));
            final int sx = left + labelW + swatchW + 6;
            hue[i] = addRenderableWidget(new ChannelSlider(sx, y, sliderW, "H", 0, 360,
                    hueOf(rgb[i]), v -> setChannel(idx, 0, v)));
            sat[i] = addRenderableWidget(new ChannelSlider(sx + sliderW + 2, y, sliderW, "S", 0, 100,
                    satOf(rgb[i]), v -> setChannel(idx, 1, v)));
            val[i] = addRenderableWidget(new ChannelSlider(sx + (sliderW + 2) * 2, y, sliderW, "V", 0, 100,
                    valOf(rgb[i]), v -> setChannel(idx, 2, v)));
            final EditBox hex = new EditBox(this.font,
                    sx + (sliderW + 2) * 3 + 4, y + 1, hexW, 18,
                    Component.translatable("gui.sablestopnow.color.hex"));
            hex.setMaxLength(8);
            hex.setValue(String.format("%06X", rgb[i] & 0xFFFFFF));
            hex.setResponder(text -> applyHex(idx, text));
            hexFields[i] = addRenderableWidget(hex);
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.sablestopnow.color.reset"),
                            b -> resetRow(idx))
                    .bounds(sx + (sliderW + 2) * 3 + 4 + hexW + 4, y, 34, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.sablestopnow.color.done"),
                b -> this.onClose()).bounds(this.width / 2 - 50, top + ITEMS.size() * rowH + 10,
                100, 20).build());
    }

    @Override
    public void render(final GuiGraphics g, final int mouseX, final int mouseY, final float partial) {
        this.renderBackground(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
        final int left = Math.max(10, this.width / 2 - 170);
        final int labelW = 74;
        final int rowH = 22;
        final int top = 40;
        for (int i = 0; i < ITEMS.size(); i++) {
            final int y = top + i * rowH;
            g.drawString(this.font, ITEMS.get(i).label(), left, y + 6, 0xE6E6E6);
            // 色块预览 ✓（完整 ARGB，含 alpha：让玩家看到实际不透明度）
            final int argb = 0xFF000000 | (rgb[i] & 0xFFFFFF);
            g.fill(left + labelW, y + 1, left + labelW + 14, y + 19, argb);
            g.renderOutline(left + labelW, y + 1, 14, 18, 0xFF303840);
        }
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    // ===================== 读写 =====================

    private static String read(final Item item) {
        try {
            return item.getter().apply(SablestopNowConfig.INSTANCE).get();
        } catch (final RuntimeException e) {
            return item.defaultValue();
        }
    }

    private static void write(final Item item, final String hex) {
        try {
            item.getter().apply(SablestopNowConfig.INSTANCE).set(hex);
            SablestopNowConfig.SPEC.save();
        } catch (final RuntimeException e) {
            SablestopNow.LOGGER.warn("[color] cannot save {} = {}", item.key(), hex, e);
        }
    }

    private void setChannel(final int idx, final int channel, final double value) {
        final double h = hueOf(rgb[idx]);
        final double s = satOf(rgb[idx]);
        final double v = valOf(rgb[idx]);
        final double nh = channel == 0 ? value : h;
        final double ns = channel == 1 ? value / 100.0 : s;
        final double nv = channel == 2 ? value / 100.0 : v;
        rgb[idx] = hsvToRgb(nh, ns, nv);
        final String hex = String.format("FF%06X", rgb[idx] & 0xFFFFFF);
        write(ITEMS.get(idx), hex);
        final EditBox field = hexFields[idx];
        if (field != null) {
            field.setValue(String.format("%06X", rgb[idx] & 0xFFFFFF));
        }
    }

    private void applyHex(final int idx, final String text) {
        final int parsed = parseRgb("FF" + text);
        if (parsed < 0) {
            return; // 还没输完 / 非法 ⇒ 不动 ✓
        }
        rgb[idx] = parsed;
        write(ITEMS.get(idx), String.format("FF%06X", parsed & 0xFFFFFF));
        refreshSliders(idx);
    }

    private void resetRow(final int idx) {
        final String def = ITEMS.get(idx).defaultValue();
        rgb[idx] = parseRgb(def);
        write(ITEMS.get(idx), def);
        final EditBox field = hexFields[idx];
        if (field != null) {
            field.setValue(String.format("%06X", rgb[idx] & 0xFFFFFF));
        }
        refreshSliders(idx);
    }

    private void refreshSliders(final int idx) {
        if (hue[idx] != null) {
            hue[idx].setValue(hueOf(rgb[idx]));
            sat[idx].setValue(satOf(rgb[idx]));
            val[idx].setValue(valOf(rgb[idx]));
        }
    }

    // ===================== 颜色工具 =====================

    /** "AARRGGBB" / "RRGGBB" ⇒ 0xRRGGBB（失败 -1）。 */
    private static int parseRgb(final String hex) {
        if (hex == null) {
            return -1;
        }
        String s = hex.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() == 8) {
            s = s.substring(2); // 丢掉 alpha
        }
        if (s.length() != 6 || !s.matches("[0-9a-fA-F]{6}")) {
            return -1;
        }
        return (int) Long.parseLong(s, 16);
    }

    private static double hueOf(final int rgb) {
        final double[] hsv = rgbToHsv(rgb);
        return hsv[0];
    }

    private static double satOf(final int rgb) {
        return rgbToHsv(rgb)[1] * 100.0;
    }

    private static double valOf(final int rgb) {
        return rgbToHsv(rgb)[2] * 100.0;
    }

    /** 0xRRGGBB ⇒ {h(0..360), s(0..1), v(0..1)} ✓ */
    private static double[] rgbToHsv(final int rgb) {
        final double r = ((rgb >> 16) & 0xFF) / 255.0;
        final double g = ((rgb >> 8) & 0xFF) / 255.0;
        final double b = (rgb & 0xFF) / 255.0;
        final double max = Math.max(r, Math.max(g, b));
        final double min = Math.min(r, Math.min(g, b));
        final double d = max - min;
        double h = 0;
        if (d > 1.0e-9) {
            if (max == r) {
                h = 60 * (((g - b) / d) % 6);
            } else if (max == g) {
                h = 60 * ((b - r) / d + 2);
            } else {
                h = 60 * ((r - g) / d + 4);
            }
        }
        if (h < 0) {
            h += 360;
        }
        final double s = max < 1.0e-9 ? 0 : d / max;
        return new double[] {h, s, max};
    }

    /** {h(0..360), s(0..1), v(0..1)} ⇒ 0xRRGGBB ✓ */
    private static int hsvToRgb(final double hIn, final double s, final double v) {
        final double h = ((hIn % 360.0) + 360.0) % 360.0;
        final double c = v * s;
        final double x = c * (1 - Math.abs(((h / 60.0) % 2) - 1));
        final double m = v - c;
        double r;
        double g;
        double b;
        if (h < 60) {
            r = c;
            g = x;
            b = 0;
        } else if (h < 120) {
            r = x;
            g = c;
            b = 0;
        } else if (h < 180) {
            r = 0;
            g = c;
            b = x;
        } else if (h < 240) {
            r = 0;
            g = x;
            b = c;
        } else if (h < 300) {
            r = x;
            g = 0;
            b = c;
        } else {
            r = c;
            g = 0;
            b = x;
        }
        final int ri = (int) Math.round((r + m) * 255.0);
        final int gi = (int) Math.round((g + m) * 255.0);
        final int bi = (int) Math.round((b + m) * 255.0);
        return ((ri & 0xFF) << 16) | ((gi & 0xFF) << 8) | (bi & 0xFF);
    }

    /** 一个"通道"滑条：显示 名称+数值，回调值 ✓ */
    private static final class ChannelSlider extends AbstractSliderButton {

        private final String caption;
        private final double min;
        private final double max;
        private final java.util.function.DoubleConsumer onChange;

        private ChannelSlider(final int x, final int y, final int w, final String caption,
                              final double min, final double max, final double initial,
                              final java.util.function.DoubleConsumer onChange) {
            super(x, y, w, 20, Component.empty(),
                    (initial - min) / Math.max(1.0e-9, max - min));
            this.caption = caption;
            this.min = min;
            this.max = max;
            this.onChange = onChange;
            this.updateMessage();
        }

        private double realValue() {
            return this.min + this.value * (this.max - this.min);
        }

        private void setValue(final double real) {
            this.value = (real - this.min) / Math.max(1.0e-9, this.max - this.min);
            this.value = Math.max(0, Math.min(1, this.value));
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(this.caption + " "
                    + Math.round(this.realValue())));
        }

        @Override
        protected void applyValue() {
            this.onChange.accept(this.realValue());
        }
    }
}
