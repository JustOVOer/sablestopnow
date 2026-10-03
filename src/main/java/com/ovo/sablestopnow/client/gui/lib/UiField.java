package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 表单字段：**标签 + 控件**的组合，且把「不联动」这件事从根上消掉。
 *
 * <h2>它解决的正是那个 bug</h2>
 * 用户实测反馈过「设置施力点时滑动条和输入框没有联动」。原因不是少写了一次同步，
 * 而是当时<b>滑条和输入框各存了一份值</b>：滑条改自己那份、输入框改自己那份，
 * 中间没有任何东西保证它们相等。这里两者绑的是<b>同一个</b> {@link Prop}：
 * <ul>
 *   <li>滑条拖 → {@code value} 变 → 输入框读的是 {@code fmt(value)} → 立刻跟着变；</li>
 *   <li>输入框打字 → 解析成功后写 {@code value} → 滑条读的是 {@code value} → 立刻跟着滑；</li>
 *   <li>服务端回声写 {@code value} → 两边同时跟上。</li>
 * </ul>
 * 三处都只认一个真相，所以「忘了同步」这种失败模式不存在，也不需要测试去覆盖。
 *
 * <h2>两个方向各自什么时候发命令</h2>
 * 拖动/打字只改<b>本地状态</b>（界面立刻跟手），真的「提交」才发命令
 * （滑条松手、输入框回车或失焦）。这样既跟手又不会一帧一个网络包。
 */
public class UiField extends Container {

    public final Prop<Double> value;
    @Nullable
    public final Label label;
    public final Slider slider;
    public final TextField box;
    /** 控件行（滑条 + 输入框）。 */
    public final Container row;

    /** 提交（发包/持久化）通知。 */
    @Nullable
    public Consumer<Double> onCommit;
    /** 本地改动通知（预览渲染之类）。 */
    @Nullable
    public Consumer<Double> onPreview;
    /** 归属命令名前缀。 */
    public final String command;

    public double min;
    public double max;
    public double step;
    public Function<Double, String> formatter = v -> String.format("%.2f", v);

    private static final int BOX_W = 46;
    /** 控件行的高度（滑条与输入框同高）。 */
    private static final int ROW_H = 12;

    private UiField(final String command, final Prop<Double> value, final double min, final double max,
                    final double step, @Nullable final String labelKey) {
        this.command = command;
        this.value = value;
        this.min = min;
        this.max = max;
        this.step = step;
        this.prefW = FILL;
        this.prefH = AUTO;
        this.layout = Layouts.vbox(0, 2);

        this.label = labelKey == null ? null : new Label(Component.translatable(labelKey));

        // ① 滑条：读同一个 Prop；松手才发命令
        this.slider = new Slider(value, min, max, step);
        slider.prefW = FILL;
        slider.prefH = 12;
        slider.showValue = false;
        slider.command((command == null ? "field" : command) + ".drag");
        slider.commit(v -> commit(v));
        // 拖动过程中的每一次本地改值也算「刚动过」：否则周期性拉取会把滑条从手底下拽走。
        slider.onPreview = v -> {
            if (onPreview != null) {
                onPreview.accept(v);
            }
        };

        // ② 输入框：显示的是「Prop 的格式化投影」，不是第二份状态
        this.box = new TextField();
        box.prefW = BOX_W;
        box.prefH = 12;
        box.project(() -> formatter.apply(value.get()));
        box.command((command == null ? "field" : command) + ".text");
        box.filter = this::accepts;
        box.selectAllOnEdit = true;
        box.changed(text -> {
            final Double parsed = parse(text);
            if (parsed != null) {
                // 边打字边预览：滑条同步跟手，但不发包
                value.set(clamp(parsed));
                if (onPreview != null) {
                    Cmd.run((command == null ? "field" : command) + ".preview",
                            () -> onPreview.accept(value.get()));
                }
            }
        });
        box.commit(text -> {
            final Double parsed = parse(text);
            if (parsed != null) {
                value.set(clamp(parsed));
                commit(value.get());
            }
            // 解析失败：不发命令；下一次重绘时输入框会回到真值投影（等于自动撤销）
        });

        final Container controls = new Container();
        controls.layout = Layouts.hbox(0, 3);
        controls.prefW = FILL;
        // ⚠ 高度必须给死，不能留 AUTO。
        // Container 默认的 measure 是空的，而 prefH=AUTO 时布局器正是靠 measure 问「你要多高」——
        // 拿到的是上一帧的 h（首帧为 0），于是整行塌陷成 0 高：滑条和输入框既画不出来、也点不到。
        // 用户实测反馈的「滑动条无法拖动」就是这个：按下落在 UiField 自己身上（日志里
        // `model.left=UiField`），因为 0 像素高的孩子永远不会被 hitTest 命中。
        controls.prefH = ROW_H;
        controls.add(slider);
        controls.add(box);
        this.row = controls;

        if (label != null) {
            add(label);
        }
        add(row);
    }
    // ============ 工厂 ============

    public static UiField number(final String command, final Prop<Double> value,
                                 final double min, final double max, final double step) {
        return new UiField(command, value, min, max, step, null);
    }

    public static UiField number(final String labelKey, final String command, final Prop<Double> value,
                                 final double min, final double max, final double step) {
        return new UiField(command, value, min, max, step, labelKey);
    }

    public static UiField integer(final String labelKey, final String command, final Prop<Double> value,
                                  final int min, final int max) {
        final UiField field = new UiField(command, value, min, max, 1.0, labelKey);
        field.format(v -> String.format("%.0f", v));
        return field;
    }

    // ============ 配置 ============

    public UiField format(final Function<Double, String> fmt) {
        this.formatter = fmt;
        return this;
    }

    public UiField commit(final Consumer<Double> listener) {
        this.onCommit = listener;
        return this;
    }

    public UiField preview(final Consumer<Double> listener) {
        this.onPreview = listener;
        return this;
    }

    public UiField width(final int sliderWidth) {
        slider.prefW = sliderWidth;
        return this;
    }

    public UiField boxWidth(final int width) {
        box.prefW = width;
        return this;
    }

    /** 只显示输入框（不要滑条）——区间很宽、滑条没意义的字段用。 */
    public UiField boxOnly() {
        slider.visible = false;
        return this;
    }

    public UiField id(final String widgetId) {
        super.id(widgetId);
        return this;
    }

    // ============ 内部 ============

    /**
     * 高度必须自己算。
     *
     * <p>{@code prefH = AUTO} 时布局器会调 {@code measure} 问「你要多高」，
     * 而 {@link Container} 默认的 {@code measure} 是空的 —— 那会返回上一帧的 {@code h}，
     * 内容变了高度却不跟着变（多一行就压到下面的控件上）。这里按 vbox(0,2) 的规则如实算。
     */
    @Override
    public void measure(final UiContext ctx) {
        int height = 0;
        if (label != null && label.visible) {
            height += label.wantH(ctx) + 2;
        }
        if (row.visible) {
            height += ROW_H;
        }
        h = height;
    }

    private void commit(final double v) {
        if (onCommit != null) {
            Cmd.run(command == null ? "field.commit" : command, () -> onCommit.accept(v));
        }
    }

    private double clamp(final double v) {
        double out = v;
        if (step > 0.0) {
            out = Math.round(out / step) * step;
        }
        return Math.max(min, Math.min(max, out));
    }

    @Nullable
    private Double parse(final String text) {
        if (text == null) {
            return null;
        }
        final String trimmed = text.trim().replace("N", "").trim();
        if (trimmed.isEmpty() || trimmed.equals("-") || trimmed.equals(".")) {
            return null;
        }
        try {
            return Double.parseDouble(trimmed);
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    /** 允许的字符（编辑过程中也放行，避免打字打到一半被拒）。 */
    private boolean accepts(final String text) {
        return text.isEmpty() || text.matches("-?\\d*(\\.\\d*)?\\s*N?");
    }
}
