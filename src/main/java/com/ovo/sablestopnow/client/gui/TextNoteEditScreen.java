package com.ovo.sablestopnow.client.gui;

import com.ovo.sablestopnow.sketch.Sketch;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * **文字图元的编辑框** ✓（item 3）。
 *
 * <p>SolidWorks 的注释编辑器：**多行文本**（回车换行 ✓）+ 颜色 + 缩放 + 旋转 ✓
 *
 * <p>为什么用原版 {@link Screen}：需要一个真正的多行文本区（回车要能换行），
 * 而 {@link EditBox} 把回车吃掉了 ✗ ⇒ 自己维护 {@code lines} 列表并接管按键 ✓
 * 这是一个**模态**编辑器（打开时暂停世界），与 SolidWorks 的"编辑注释"一致 ✓
 */
public final class TextNoteEditScreen extends Screen {

    /** 保存回调：交回新的文本/颜色/缩放/旋转 ✓ */
    public interface Sink {
        void save(String text, int argb, double scale, double rotationDeg);

        void delete();
    }

    private final Screen parent;
    private final Sink sink;
    private final List<StringBuilder> lines = new ArrayList<>();
    private int caretLine;

    private EditBox hexField;
    private EditBox scaleField;
    private EditBox rotField;

    private int argb;
    private double scale;
    private double rotationDeg;

    public TextNoteEditScreen(final Screen parent, final Sketch.TextNote note, final Sink sink) {
        super(Component.translatable("gui.sablestopnow.text.title"));
        this.parent = parent;
        this.sink = sink;
        this.argb = note.argb();
        this.scale = note.scale();
        this.rotationDeg = note.rotationDeg();
        final String[] parts = note.text() == null ? new String[] {""} : note.text().split("\n", -1);
        for (final String p : parts) {
            this.lines.add(new StringBuilder(p));
        }
        if (this.lines.isEmpty()) {
            this.lines.add(new StringBuilder());
        }
        this.caretLine = this.lines.size() - 1;
    }

    @Override
    protected void init() {
        final int left = this.width / 2 - 150;
        final int y = this.height - 62;
        hexField = new EditBox(this.font, left, y, 64, 18,
                Component.translatable("gui.sablestopnow.color.hex"));
        hexField.setMaxLength(8);
        hexField.setValue(String.format("%08X", argb));
        hexField.setResponder(t -> {
            final int parsed = parseArgb(t);
            if (parsed != 0) {
                argb = parsed;
            }
        });
        addRenderableWidget(hexField);

        scaleField = new EditBox(this.font, left + 72, y, 44, 18,
                Component.translatable("gui.sablestopnow.text.scale"));
        scaleField.setMaxLength(6);
        scaleField.setValue(String.format(java.util.Locale.ROOT, "%.2f", scale));
        scaleField.setResponder(t -> {
            try {
                scale = Math.max(0.25, Math.min(8.0, Double.parseDouble(t.trim())));
            } catch (final NumberFormatException ignored) {
                // 输入中途（"-"/""）不算错 ✓
            }
        });
        addRenderableWidget(scaleField);

        rotField = new EditBox(this.font, left + 124, y, 44, 18,
                Component.translatable("gui.sablestopnow.text.rotation"));
        rotField.setMaxLength(7);
        rotField.setValue(String.format(java.util.Locale.ROOT, "%.1f", rotationDeg));
        rotField.setResponder(t -> {
            try {
                rotationDeg = Double.parseDouble(t.trim());
            } catch (final NumberFormatException ignored) {
                // 同上 ✓
            }
        });
        addRenderableWidget(rotField);

        addRenderableWidget(Button.builder(Component.translatable("gui.sablestopnow.text.ok"),
                        b -> this.commit())
                .bounds(left + 176, y, 52, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.sablestopnow.text.delete"),
                        b -> {
                            sink.delete();
                            this.onClose();
                        })
                .bounds(left + 232, y, 52, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.sablestopnow.text.cancel"),
                        b -> this.onClose())
                .bounds(left + 288, y, 48, 20).build());
    }

    @Override
    public void render(final GuiGraphics g, final int mouseX, final int mouseY, final float partial) {
        this.renderBackground(g, mouseX, mouseY, partial);
        g.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);
        final int left = this.width / 2 - 150;
        final int top = 34;
        // 文本区（多行）✓
        g.fill(left - 4, top - 4, left + 304, top + 9 * Math.max(4, lines.size()) + 4, 0x80000000);
        for (int i = 0; i < lines.size(); i++) {
            final String text = lines.get(i).toString();
            g.drawString(this.font, text + (i == caretLine ? "_" : ""), left, top + i * 9,
                    argb == 0 ? 0xFFFFFF : argb, false);
        }
        g.drawString(this.font, Component.translatable("gui.sablestopnow.text.hint"), left,
                top + 9 * lines.size() + 8, 0x8FA0B8, false);
        super.render(g, mouseX, mouseY, partial);
    }

    @Override
    public boolean charTyped(final char codePoint, final int modifiers) {
        if (this.getFocused() instanceof EditBox) {
            return super.charTyped(codePoint, modifiers);
        }
        if (codePoint >= 32) {
            lines.get(caretLine).append(codePoint);
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(final int key, final int scan, final int mods) {
        if (this.getFocused() instanceof EditBox) {
            return super.keyPressed(key, scan, mods);
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            // ★ 回车换行 ✓（item 3 明确要求"允许回车换行"）
            lines.add(caretLine + 1, new StringBuilder());
            caretLine++;
            return true;
        }
        if (key == GLFW.GLFW_KEY_BACKSPACE) {
            final StringBuilder cur = lines.get(caretLine);
            if (cur.length() > 0) {
                cur.deleteCharAt(cur.length() - 1);
            } else if (caretLine > 0) {
                caretLine--;
                lines.remove(caretLine + 1);
            }
            return true;
        }
        if (key == GLFW.GLFW_KEY_UP && caretLine > 0) {
            caretLine--;
            return true;
        }
        if (key == GLFW.GLFW_KEY_DOWN && caretLine < lines.size() - 1) {
            caretLine++;
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    private void commit() {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(lines.get(i));
        }
        sink.save(sb.toString(), argb, scale, rotationDeg);
        this.onClose();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    /** "AARRGGBB"/"RRGGBB" ⇒ argb；非法返回 0 ✓ */
    private static int parseArgb(final String text) {
        if (text == null) {
            return 0;
        }
        String s = text.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() == 6) {
            s = "FF" + s;
        }
        if (s.length() != 8 || !s.matches("[0-9a-fA-F]{8}")) {
            return 0;
        }
        return (int) Long.parseLong(s, 16);
    }
}
