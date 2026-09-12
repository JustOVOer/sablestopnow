package com.ovo.sablestopnow.client.gui;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 配置界面里的一行设置项，直接绑定 {@link ModConfigSpec} 的值（读写同一份 sablestopnow-common.toml）。
 */
public final class ConfigOption {

    public enum Kind {
        /** 开关。 */
        BOOL,
        /** 数值（滑条，编辑时吸附到 step）。 */
        NUMBER,
        /** 字符串列表（逗号分隔输入框）。 */
        STRING_LIST,
        /** 纯按钮（例如“打开按键设置”）。 */
        ACTION
    }

    private final Kind kind;
    private final String labelKey;
    private final String descKey;
    private final Supplier<Object> getter;
    private final Consumer<Object> setter;
    private final double min;
    private final double max;
    private final double step;
    private final boolean integer;
    private final Runnable action;

    private ConfigOption(final Kind kind, final String labelKey, final String descKey,
                         final Supplier<Object> getter, final Consumer<Object> setter,
                         final double min, final double max, final double step, final boolean integer,
                         final Runnable action) {
        this.kind = kind;
        this.labelKey = labelKey;
        this.descKey = descKey;
        this.getter = getter;
        this.setter = setter;
        this.min = min;
        this.max = max;
        this.step = step;
        this.integer = integer;
        this.action = action;
    }

    public static ConfigOption bool(final String name, final ModConfigSpec.BooleanValue value) {
        return new ConfigOption(Kind.BOOL, label(name), desc(name),
                value::get, v -> value.set((Boolean) v), 0, 0, 0, false, null);
    }

    public static ConfigOption number(final String name, final ModConfigSpec.DoubleValue value,
                                      final double min, final double max, final double step) {
        return new ConfigOption(Kind.NUMBER, label(name), desc(name),
                value::get, v -> value.set((Double) v), min, max, step, false, null);
    }

    public static ConfigOption number(final String name, final ModConfigSpec.IntValue value,
                                      final double min, final double max) {
        return new ConfigOption(Kind.NUMBER, label(name), desc(name),
                value::get, v -> value.set((int) Math.round((Double) v)), min, max, 1.0, true, null);
    }

    public static ConfigOption stringList(final String name,
                                          final ModConfigSpec.ConfigValue<List<? extends String>> value) {
        return new ConfigOption(Kind.STRING_LIST, label(name), desc(name),
                value::get, v -> value.set((List<String>) v), 0, 0, 0, false, null);
    }

    public static ConfigOption action(final String name, final Runnable action) {
        return new ConfigOption(Kind.ACTION, label(name), desc(name), () -> null, v -> {
        }, 0, 0, 0, false, action);
    }

    private static String label(final String name) {
        return "gui.sablestopnow.opt." + name;
    }

    private static String desc(final String name) {
        return "gui.sablestopnow.opt." + name + ".desc";
    }

    // ---- 供界面读取 ----

    public Kind kind() {
        return this.kind;
    }

    public String labelKey() {
        return this.labelKey;
    }

    public String descKey() {
        return this.descKey;
    }

    public boolean integer() {
        return this.integer;
    }

    public double min() {
        return this.min;
    }

    public double max() {
        return this.max;
    }

    public double step() {
        return this.step;
    }

    public boolean getBool() {
        return Boolean.TRUE.equals(this.getter.get());
    }

    public double getNumber() {
        final Object raw = this.getter.get();
        return raw instanceof final Number n ? n.doubleValue() : 0.0;
    }

    /** 0..1 的滑条位置。 */
    public double getFraction() {
        final double span = this.max - this.min;
        return span <= 0 ? 0 : Math.clamp((getNumber() - this.min) / span, 0.0, 1.0);
    }

    public String getText() {
        final Object raw = this.getter.get();
        if (raw instanceof final List<?> list) {
            final List<String> parts = new ArrayList<>();
            for (final Object o : list) {
                parts.add(String.valueOf(o));
            }
            return String.join(", ", parts);
        }
        return String.valueOf(raw);
    }

    public void setBool(final boolean value) {
        this.setter.accept(value);
    }

    public void setFraction(final double fraction) {
        final double span = this.max - this.min;
        double value = this.min + span * Math.clamp(fraction, 0.0, 1.0);
        if (this.step > 0) {
            value = this.min + Math.round((value - this.min) / this.step) * this.step;
        }
        this.setter.accept(this.integer ? (double) (int) Math.round(value) : value);
    }

    public void setText(final String text) {
        final List<String> out = new ArrayList<>();
        for (final String part : text.split(",")) {
            final String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        this.setter.accept(out);
    }

    public void runAction() {
        if (this.action != null) {
            this.action.run();
        }
    }

    /** 数值显示文本。 */
    public String displayValue() {
        final double value = getNumber();
        if (this.integer) {
            return String.valueOf((int) Math.round(value));
        }
        if (Math.abs(value) >= 100) {
            return String.format("%.0f", value);
        }
        return String.format("%.3f", value);
    }
}
