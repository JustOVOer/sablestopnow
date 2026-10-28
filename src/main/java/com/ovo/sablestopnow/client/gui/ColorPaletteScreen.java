package com.ovo.sablestopnow.client.gui;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.SablestopNowConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;
import java.util.function.Function;

/**
 * **通用调色板** ✓（item 2）。
 *
 * <p>每项一行：名称 + 色块 + **R / G / B 三条滑条（0–255，就是玩家熟悉的 RGB）** + 十六进制 + 复位 ✓
 *
 * <p>⚠ 三个用户实测反馈的坑，全部在这里定点修掉：
 * <ol>
 *   <li><b>滑条乱跑</b>：以前在滑条回调里回写滑条自己的值（还绕过十六进制输入框的 responder），
 *       拖动时值被反复重置。现在**滑条 → 颜色 → 输入框文字**是单向的；
 *       回写输入框时用 {@link #suppress} 抑制 responder，**绝不回写滑条** ✓</li>
 *   <li><b>退出后界面紊乱（凭空多一行退出键）</b>：以前退出时把**同一个**设置页实例再 setScreen 一次，
 *       而设置页布局是"一次性构建"的，复用实例会留下上一代控件。现在退出时**新建**一个干净的分类页 ✓</li>
 *   <li><b>按钮没字</b>：全部走本模组语言键，不再依赖父界面残留控件 ✓</li>
 * </ol>
 */
public final class ColorPaletteScreen extends Screen {

    private record Item(String key, Component label, String defaultValue,
                        Function<SablestopNowConfig.Config, ModConfigSpec.ConfigValue<String>> getter) {
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
    private final RgbSlider[][] sliders = new RgbSlider[ITEMS.size()][3];

    /** 每行的当前颜色（0xRRGGBB）—— 唯一真相 ✓ */
    private final int[] rgb = new int[ITEMS.size()];

    /** 回写输入框时置位，抑制 responder 递归 ✓ */
    private boolean suppress;

    public ColorPaletteScreen(final Screen parent) {
        super(Component.translatable("gui.sablestopnow.color.title"));
        this.parent = parent;
    }

    // ================= 布局 =================

    private int left() {
        return Math.max(8, this.width / 2 - 186);
    }

    private int rowY(final int i) {
        return 42 + i * 21;
    }

    @Override
    protected void init() {
        final int labelW = 78;
        final int swatchW = 14;
        final int sliderW = 48;
        final int hexW = 68;
        final String[] names = {"R", "G", "B"};
        for (int row = 0; row < ITEMS.size(); row++) {
            final int i = row;
            rgb[i] = parseRgb(read(ITEMS.get(i)));
            final int sx = left() + labelW + swatchW + 6;
            final int y = rowY(i);
            for (int ch = 0; ch < 3; ch++) {
                final int idx = i;
                final int c = ch;
                sliders[i][ch] = addRenderableWidget(new RgbSlider(
                        sx + ch * (sliderW + 2), y, sliderW, channelOf(rgb[i], ch), names[ch],
                        v -> onChannel(idx, c, v)));
            }
            final EditBox hex = new EditBox(this.font,
                    sx + 3 * (sliderW + 2) + 4, y + 1, hexW, 18,
                    Component.translatable("gui.sablestopnow.color.hex"));
            hex.setMaxLength(6);
            hex.setValue(String.format("%06X", rgb[i] & 0xFFFFFF));
            hex.setResponder(text -> {
                if (suppress) {
                    return;
                }
                final int parsed = parseRgb(text);
                if (parsed >= 0) {
                    rgb[i] = parsed;
                    write(ITEMS.get(i), argbOf(rgb[i]));
                    syncSliders(i);
                }
            });
            hexFields[i] = addRenderableWidget(hex);
            addRenderableWidget(Button.builder(
                            Component.translatable("gui.sablestopnow.color.reset"),
                            b -> resetRow(i))
                    .bounds(sx + 3 * (sliderW + 2) + 4 + hexW + 4, y, 38, 20).build());
        }
        final int bottom = rowY(ITEMS.size()) + 4;
        addRenderableWidget(Button.builder(Component.translatable("gui.sablestopnow.color.done"),
                        b -> this.onClose())
                .bounds(this.width / 2 - 50, bottom, 100, 20).build());
    }

    @Override
    public void render(final GuiGraphics g, final int mouseX, final int mouseY, final float partial) {
        // 只铺一层暗色：不调 renderBackground ⇒ 不会把底下的设置页糊成一片 ✓
        g.fill(0, 0, this.width, this.height, 0xFF101418); // 不透明：否则底下的模糊会透上来
        g.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFF);
        // ⚠ 顺序：控件先画（super.render），自定义文字/色块**最后**画 ——
        //   否则会被本模组 GUI 库的模糊层盖住（用户截图："左侧文字和预览是糊的"）
        super.render(g, mouseX, mouseY, partial);
        final int labelW = 78;
        for (int i = 0; i < ITEMS.size(); i++) {
            final int y = rowY(i);
            g.drawString(this.font, ITEMS.get(i).label(), left(), y + 6, 0xFFFFFFFF, false);
            final int argb = 0xFF000000 | (rgb[i] & 0xFFFFFF);
            g.fill(left() + labelW, y + 1, left() + labelW + 14, y + 19, argb);
            g.renderOutline(left() + labelW, y + 1, 14, 18, 0xFF39424C);
        }
        g.drawCenteredString(this.font, this.title, this.width / 2, 18, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        if (this.minecraft == null) {
            return;
        }
        // ★ 退出时**新建**一个干净的分类页：复用旧实例会让布局残留上一代控件（"多出一行退出键"）✓
        // ★ 回**新建的**设置首页（parent 传 null）：既不会留下上一代控件，
        //   也不会出现"ESC 退出后又弹回调色页"的循环（用户实测）✓
        this.minecraft.setScreen(new ModSettingsScreen(null));
    }

    // ================= 读写 =================

    private void onChannel(final int idx, final int channel, final double value) {
        final int v = (int) Math.max(0, Math.min(255, Math.round(value)));
        rgb[idx] = withChannel(rgb[idx], channel, v);
        write(ITEMS.get(idx), argbOf(rgb[idx]));
        suppress = true;
        try {
            if (hexFields[idx] != null) {
                final String text = String.format("%06X", rgb[idx] & 0xFFFFFF);
                if (!text.equalsIgnoreCase(hexFields[idx].getValue())) {
                    hexFields[idx].setValue(text);
                }
            }
        } finally {
            suppress = false;
        }
    }

    private void resetRow(final int idx) {
        final String def = ITEMS.get(idx).defaultValue();
        rgb[idx] = parseRgb(def);
        write(ITEMS.get(idx), def);
        syncSliders(idx);
        suppress = true;
        try {
            if (hexFields[idx] != null) {
                hexFields[idx].setValue(String.format("%06X", rgb[idx] & 0xFFFFFF));
            }
        } finally {
            suppress = false;
        }
    }

    private void syncSliders(final int idx) {
        for (int ch = 0; ch < 3; ch++) {
            if (sliders[idx][ch] != null) {
                sliders[idx][ch].setFromModel(channelOf(rgb[idx], ch));
            }
        }
    }

    private static String read(final Item item) {
        try {
            return item.getter().apply(SablestopNowConfig.INSTANCE).get();
        } catch (final RuntimeException e) {
            return item.defaultValue();
        }
    }

    private static void write(final Item item, final String argbHex) {
        try {
            item.getter().apply(SablestopNowConfig.INSTANCE).set(argbHex);
            SablestopNowConfig.SPEC.save();
        } catch (final RuntimeException e) {
            SablestopNow.LOGGER.warn("[color] cannot save {} = {}", item.key(), argbHex, e);
        }
    }

    /** 保留固定 alpha，只写 RGB ✓ */
    private static String argbOf(final int rgb) {
        return String.format("FF%06X", rgb & 0xFFFFFF);
    }

    private static int channelOf(final int rgb, final int ch) {
        return (rgb >> (16 - ch * 8)) & 0xFF;
    }

    private static int withChannel(final int rgb, final int ch, final int value) {
        final int shift = 16 - ch * 8;
        return (rgb & ~(0xFF << shift)) | ((value & 0xFF) << shift);
    }

    /** "RRGGBB"/"AARRGGBB"/"#RRGGBB" ⇒ 0xRRGGBB；非法 -1 ✓ */
    private static int parseRgb(final String hex) {
        if (hex == null) {
            return -1;
        }
        String s = hex.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() == 8) {
            s = s.substring(2);
        }
        if (s.length() != 6 || !s.matches("[0-9a-fA-F]{6}")) {
            return -1;
        }
        return (int) Long.parseLong(s, 16);
    }

    /** R/G/B 通道滑条：显示"名称 + 数值"，只在拖动时向外发，**不因外部变化重置拖动态** ✓ */
    private static final class RgbSlider extends AbstractSliderButton {

        private final String caption;
        private final java.util.function.DoubleConsumer onChange;

        private RgbSlider(final int x, final int y, final int w, final int initial,
                          final String caption, final java.util.function.DoubleConsumer onChange) {
            super(x, y, w, 20, Component.empty(), initial / 255.0);
            this.caption = caption;
            this.onChange = onChange;
            this.updateMessage();
        }

        /** 外部（复位 / 十六进制）改值：只更新显示，不回调 ✓ */
        private void setFromModel(final int value) {
            this.value = Math.max(0, Math.min(1, value / 255.0));
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal(this.caption + " " + Math.round(this.value * 255.0)));
        }

        @Override
        protected void applyValue() {
            this.onChange.accept(this.value * 255.0);
        }
    }
}
