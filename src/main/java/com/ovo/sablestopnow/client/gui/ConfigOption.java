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
    private final String name;
    private final String labelKey;
    private final String descKey;
    private final Supplier<Object> getter;
    private final Consumer<Object> setter;
    private final double min;
    private final double max;
    private final double step;
    private final boolean integer;
    private final Runnable action;
    /**
     * 写入配置之后要顺带做的事（例如界面开关要立刻同步给 GUI 库）。
     *
     * <p>界面只在「库里通用的控件」上调 {@code setXxx}，没法知道某一项还有副作用；
     * 让选项自己带一个回调，界面就不需要为个别配置项写 if。
     */
    private Runnable onChange;
    /**
     * 这个选项属于哪个分组（lang 键，例如 {@code gui.sablestopnow.section.hull}）✓。
     *
     * <p><b>为什么放在选项自己身上（P1 / docs/gui-统一.md）：</b>
     * 以前"分组归属"写在 {@code ModSettingsCategoryScreen.SECTION_PLAN} 里 ✗ ——
     * 那是**手写的第二份清单**，只用**字符串名**和选项对应 ✓ ⇒
     * 漏写一个名字**不会编译报错** ✗，那个选项就被兜底塞进 {@code SECTION_OTHER} ✗
     * ⇒ 左栏凭空多出一个常驻「其它」分组 ✓（真实发生过的 bug ✓）。
     *
     * <p>把归属写在选项旁边 ⇒ 两者**不可能不同步** ✓（同一个表达式里 ✓）；
     * 将来若把 {@code name} 从字符串改成常量/枚举 ✓，就能做到**编译期**不可能漂移 ✓。
     */
    private String section;

    private ConfigOption(final Kind kind, final String name, final String labelKey, final String descKey,
                         final Supplier<Object> getter, final Consumer<Object> setter,
                         final double min, final double max, final double step, final boolean integer,
                         final Runnable action) {
        this.kind = kind;
        this.name = name;
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

    /** 追加一个「写完之后」的回调，返回自身便于链式书写。 */
    public ConfigOption withChange(final Runnable listener) {
        this.onChange = listener;
        return this;
    }

    public Runnable onChange() {
        return this.onChange;
    }

    /**
     * 声明这个选项属于哪个分组（lang 键）✓ —— 见 {@link #section} 字段上的说明 ✓。
     * 返回自身便于链式书写，和 {@link #withChange} 一致 ✓。
     */
    public ConfigOption section(final String sectionKey) {
        this.section = sectionKey;
        return this;
    }

    /** 所属分组的 lang 键；{@code null} = 还没声明（门禁会报 ✗）。 */
    public String section() {
        return this.section;
    }

    public static ConfigOption bool(final String name, final ModConfigSpec.BooleanValue value) {
        return new ConfigOption(Kind.BOOL, name, label(name), desc(name),
                value::get, v -> value.set((Boolean) v), 0, 0, 0, false, null);
    }

    public static ConfigOption number(final String name, final ModConfigSpec.DoubleValue value,
                                      final double min, final double max, final double step) {
        return new ConfigOption(Kind.NUMBER, name, label(name), desc(name),
                value::get, v -> value.set((Double) v), min, max, step, false, null);
    }

    public static ConfigOption number(final String name, final ModConfigSpec.IntValue value,
                                      final double min, final double max) {
        return new ConfigOption(Kind.NUMBER, name, label(name), desc(name),
                value::get, v -> value.set((int) Math.round((Double) v)), min, max, 1.0, true, null);
    }

    public static ConfigOption stringList(final String name,
                                          final ModConfigSpec.ConfigValue<List<? extends String>> value) {
        return new ConfigOption(Kind.STRING_LIST, name, label(name), desc(name),
                value::get, v -> value.set((List<String>) v), 0, 0, 0, false, null);
    }

    public static ConfigOption action(final String name, final Runnable action) {
        return new ConfigOption(Kind.ACTION, name, label(name), desc(name), () -> null, v -> {
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

    /** 选项名（与配置文件里的键一致）：分组、id、按名取选项都靠它。 */
    public String name() {
        return this.name;
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

    /**
     * 直接写入数值（滑条用）。
     *
     * <p>滑条自己算出来的就是「范围内的数值」，若还要界面换算成 0..1 的比例再让选项换回来，
     * 一来一回会引入一次多余的取整，拖动时末位数字会跳。
     */
    public void setNumber(final double value) {
        double clamped = Math.clamp(value, this.min, this.max);
        if (this.step > 0) {
            clamped = this.min + Math.round((clamped - this.min) / this.step) * this.step;
        }
        this.setter.accept(this.integer ? (double) (int) Math.round(clamped) : clamped);
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
        return displayValue(getNumber());
    }

    /** 数值显示文本（滑条每帧拿当前拖动值调它，避免显示比滑块慢一帧）。 */
    public String displayValue(final double value) {
        if (this.integer) {
            return String.valueOf((int) Math.round(value));
        }
        if (Math.abs(value) >= 100) {
            return String.format("%.0f", value);
        }
        return String.format("%.3f", value);
    }
}
