package com.ovo.sablestopnow.client.gui;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.SablestopNowConfig;
import com.ovo.sablestopnow.client.gui.lib.Button;
import com.ovo.sablestopnow.client.gui.lib.Checkbox;
import com.ovo.sablestopnow.client.gui.lib.Container;
import com.ovo.sablestopnow.client.gui.lib.Dropdown;
import com.ovo.sablestopnow.client.gui.lib.FormRow;
import com.ovo.sablestopnow.client.gui.lib.IconHero;
import com.ovo.sablestopnow.client.gui.lib.Label;
import com.ovo.sablestopnow.client.gui.lib.Layouts;
import com.ovo.sablestopnow.client.gui.lib.Prop;
import com.ovo.sablestopnow.client.gui.lib.RevealPanel;
import com.ovo.sablestopnow.client.gui.lib.ScrollPane;
import com.ovo.sablestopnow.client.gui.lib.SectionPane;
import com.ovo.sablestopnow.client.gui.lib.Slider;
import com.ovo.sablestopnow.client.gui.lib.TextField;
import com.ovo.sablestopnow.client.gui.lib.UiScreen;
import com.ovo.sablestopnow.client.gui.lib.UiSettings;
import com.ovo.sablestopnow.client.gui.lib.UiTheme;
import com.ovo.sablestopnow.client.gui.lib.Widget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 模组设置界面 —— 某个大类的设置项（对应 newConfigGUI/GUI2.png），用自研 GUI 库重写。
 *
 * <p>动画顺序（要求原话）：大类图标<b>移动并放大</b>到左侧 → 右侧蓝色色块<b>从左往右</b>出现
 * → 设置项<b>从右往左</b>依次出现；按 Esc 离开时全部反向，播完再切回大类界面。
 *
 * <p><b>设置项为什么沿折线走：</b>右侧色块的左边界是「&gt;」形折线，设置项的横坐标取
 * 「它自己中线高度」上的折线位置（{@link RevealPanel#edgeXAt}），于是滚动时整列设置项是<b>沿着折线滑动</b>的。
 * 分组块内部的每一行还要再按行自己的高度对一次（{@link #alignRowsToChevron()}），
 * 否则块里靠上的行会压到折线左边的灰底上。
 *
 * <p>所有控件都来自 GUI 库（{@link Checkbox}、{@link Slider}、{@link Dropdown}、{@link TextField}、
 * {@link Button}、{@link SectionPane}、{@link ScrollPane}），本类只做布局与动画编排，不直接调 {@code GuiGraphics}。
 */
public class ModSettingsCategoryScreen extends UiScreen {

    private static final int CHEVRON_DEPTH = 44;
    private static final int ROW_H = 16;
    private static final int ROW_GAP = 2;
    /** 设置项相对折线再右移一点：正好贴着斜边会看不清。 */
    private static final int ROW_INSET = 10;
    /** 设置项从右往左滑入的起始偏移。 */
    private static final int SLIDE_IN = 70;
    /** 控件列宽（滑条 / 输入框）。 */
    private static final int CONTROL_W = 120;
    /** 没有归入任何分组的配置项的去处。 */
    private static final String SECTION_OTHER = "gui.sablestopnow.section.other";

    /** 一大类里的一个分组：标题 + 该组包含的配置项名称（名称与 {@link ModConfigCategory} 里写的一致）。 */
    private record Section(String titleKey, List<String> optionNames) {
    }

    /**
     * 分组方案。
     *
     * <p>大类详情页一屏放不下所有设置项，按「玩家想干什么」分组比按 toml 段落分组更好找。
     * 分组只影响显示顺序 —— 配置项本身仍来自 {@link ModConfigCategory#options()}，
     * 所以不会有「界面上有、配置文件里没有」的假选项；反过来漏掉的分组由 {@link #buildSections()} 兜底。
     */
    private static final Map<ModConfigCategory, List<Section>> SECTION_PLAN = Map.of(
            ModConfigCategory.ASSEMBLER, List.of(
                    new Section("gui.sablestopnow.section.force", List.of(
                            "filter_excessive_force", "threshold", "excluded_groups", "max_records",
                            "auto_pause_on_filter")),
                    new Section("gui.sablestopnow.section.assembly", List.of(
                            "lock_new_sub_levels", "disable_placement_collision_check",
                            "require_confirmation_before_split")),
                    new Section("gui.sablestopnow.section.speed", List.of(
                            "speed_limit_enabled", "speed_limit_threshold"))),
            ModConfigCategory.STAFF, List.of(
                    new Section("gui.sablestopnow.section.staff_core", List.of(
                            "enable_staff_enhance", "new_control_scheme", "show_body_info")),
                    new Section("gui.sablestopnow.section.staff_outline", List.of(
                            "staff_outline_thickness", "staff_outline_bold_scale")),
                    new Section("gui.sablestopnow.section.staff_control", List.of(
                            "rotate_sensitivity", "scroll_sensitivity", "center_pull_speed")),
                    new Section("gui.sablestopnow.section.staff_scale", List.of(
                            "scale_sensitivity", "scale_min", "scale_max", "superliminal_max_distance")),
                    new Section("gui.sablestopnow.section.staff_collision", List.of(
                            "dragged_no_player_collision", "scaled_no_player_collision", "ghost_real")),
                    new Section("gui.sablestopnow.section.staff_keys", List.of("keybinds"))),
            ModConfigCategory.GOGGLES, List.of(
                    new Section("gui.sablestopnow.section.outline", List.of(
                            "render_sub_level_outlines", "outline_always_visible", "outline_only_contour",
                            "outline_only_focused", "outline_thickness")),
                    new Section("gui.sablestopnow.section.axis", List.of(
                            "render_axis", "axis_angle_degrees")),
                    new Section("gui.sablestopnow.section.ui", List.of(
                            "ui_animations", "ui_font_scale"))));

    private final ModConfigCategory category;
    private final List<ConfigOption> options;
    /** 大类界面上被点的那个图标的中心与缩放：大图标从这里「移动并放大」过来。 */
    private final float iconFromX;
    private final float iconFromY;
    private final float iconFromScale;

    private final IconHero hero = new IconHero();
    private final RevealPanel band = new RevealPanel();
    private final ScrollPane scroll = new ScrollPane();
    private final List<FormRow> rows = new ArrayList<>();
    private final List<SectionPane> sections = new ArrayList<>();

    private boolean closing;
    @Nullable
    private Screen next;
    private boolean switchPending;
    /** 有配置项被改动、但还没写盘。 */
    private boolean pendingSave;
    private long lastNanos;
    private int bandLeft;

    public ModSettingsCategoryScreen(final ModSettingsScreen parentScreen, final ModConfigCategory category,
                                     final int iconFromX, final int iconFromY, final float iconFromScale) {
        super(category.title(), parentScreen);
        this.category = category;
        this.options = category.options();
        this.iconFromX = iconFromX;
        this.iconFromY = iconFromY;
        this.iconFromScale = iconFromScale;
    }

    // ============ 布局 ============

    @Override
    protected void build() {
        root.closePopup();
        root.clear();
        closing = false;
        next = null;
        switchPending = false;
        pendingSave = false;
        rows.clear();
        sections.clear();

        // 界面打开时再同步一次界面库开关：玩家手改 toml 之后不必重启（加载/重载事件里也会推一次）
        SablestopNowConfig.syncUiSettings();

        bandLeft = Math.max(56, Math.round(width * 0.34f));

        hero.icon = category::icon;
        hero.fromX = iconFromX;
        hero.fromY = iconFromY;
        hero.fromScale = iconFromScale;
        hero.toX = bandLeft * 0.5f;
        hero.toY = height * 0.38f;
        // 大图标按窗口自适应：占左侧空位的一半宽，同时不超过高度的四分之一左右
        hero.toScale = Math.max(2.5f, Math.min(6.5f, height * 0.22f / 16.0f));
        hero.grow.speed(5.5).to(1.0);
        root.add(hero);

        band.dir = RevealPanel.Dir.LEFT_RIGHT;
        band.chevron = true;
        band.chevronDepth = CHEVRON_DEPTH;
        band.clipToReveal = true;
        band.reveal.speed(6.0).to(1.0);
        band.prefW = Math.max(40, width - bandLeft);
        band.prefH = Widget.FILL;
        band.marginLeft = bandLeft; // 绝对布局：x = 父内容区左边 + marginLeft
        band.layout = Layouts.dock(4, 3);
        root.add(band);

        // 先建好分组（头部要拿分组列表做跳转下拉）
        final List<String> sectionKeys = buildSections();
        buildScroll();

        band.add(buildHeader());
        band.add(buildFooter(sectionKeys));
        band.add(scroll);
    }

    /** 按 {@link #SECTION_PLAN} 把配置项分组，建好 {@link SectionPane} 并挂进滚动容器；返回分组标题键。 */
    private List<String> buildSections() {
        final Map<String, ConfigOption> byName = new LinkedHashMap<>();
        for (final ConfigOption option : options) {
            byName.put(option.name(), option);
        }

        final List<String> keys = new ArrayList<>();
        final Map<String, List<ConfigOption>> grouped = new LinkedHashMap<>();
        for (final Section section : SECTION_PLAN.getOrDefault(category, List.of())) {
            final List<ConfigOption> list = new ArrayList<>();
            for (final String name : section.optionNames()) {
                final ConfigOption option = byName.remove(name);
                if (option != null) {
                    list.add(option);
                }
            }
            if (!list.isEmpty()) {
                keys.add(section.titleKey());
                grouped.put(section.titleKey(), list);
            }
        }
        // 兜底：没写进任何分组的配置项也必须有地方去。要求是「一个不落」，
        // 以后加了配置项忘了补分组，界面上会多出一个「其他」，而不是把它默默吞掉。
        if (!byName.isEmpty()) {
            keys.add(SECTION_OTHER);
            grouped.put(SECTION_OTHER, new ArrayList<>(byName.values()));
        }

        int index = 0;
        for (final String key : keys) {
            final SectionPane pane = new SectionPane(key);
            pane.id(key);
            for (final ConfigOption option : grouped.get(key)) {
                final FormRow row = rowFor(option);
                // 出现时依次错开：越靠后的行收敛越慢，于是「从右往左依次出现」
                row.appearSpeed(6.0 + index * 0.9);
                rows.add(row);
                pane.content.add(row);
                index++;
            }
            sections.add(pane);
            scroll.add(pane);
        }
        return keys;
    }

    /** 滚动容器：内容沿色块折线排布（位置算法只在这里，渲染仍是控件自己的事）。 */
    private void buildScroll() {
        scroll.prefW = Widget.FILL;
        scroll.prefH = Widget.FILL;
        scroll.clip = true;
        // 每一块的横坐标取「它自己中线高度」上的折线位置 ⇒ 滚动时整列设置项沿着折线滑动
        scroll.layout = (parent, ctx) -> {
            int cursor = parent.innerY();
            for (final Widget child : parent.children) {
                if (!child.visible) {
                    continue;
                }
                final int childH = child.wantH(ctx);
                final int left = band.edgeXAt(cursor + childH / 2) + ROW_INSET;
                child.x = left;
                child.y = cursor;
                child.w = Math.max(40, parent.innerX() + parent.innerW() - left - 6);
                child.h = childH;
                cursor += childH + ROW_GAP;
            }
        };
    }

    /**
     * 顶栏：大类标题 + 返回按钮。
     *
     * <p>返回按钮用短标签（「返回 | Back」）：设置区本来就窄，写整句「ESC 返回 | ESC to go back」
     * 会把标题挤没；Esc 本来也能返回，按钮只是给鼠标玩家的第二条路。
     */
    private Container buildHeader() {
        final Container header = new Container();
        header.layout = Layouts.hbox(0, 3);
        header.prefW = Widget.FILL;
        header.prefH = 16;
        header.dock = Layouts.Dock.TOP;

        final Label title = new Label((Supplier<Component>) category::title);
        title.shadow(true);
        title.prefW = Widget.FILL; // 占满标题与返回按钮之间的空档
        header.add(title);

        final Button back = new Button("gui.sablestopnow.back.short", this::onClose);
        back.margin(0, 2, 0, 0); // 栏高 16、按钮高 12：压 2px 让它竖直居中
        header.add(back);
        return header;
    }

    /**
     * 底栏：分组跳转 + 当前设置项的说明。
     *
     * <p>说明常驻底栏而不是用悬停气泡：气泡要等半秒才出来、还会盖住别的设置项；
     * 底栏一行随鼠标即时更换，扫读一整列设置项时更省事。
     */
    private Container buildFooter(final List<String> sectionKeys) {
        final Container footer = new Container();
        footer.layout = Layouts.hbox(0, 3);
        footer.prefW = Widget.FILL;
        footer.prefH = 16;
        footer.dock = Layouts.Dock.BOTTOM;

        // 设置项一多，光靠滚轮找分组很烦：给一个分组跳转列表
        final Dropdown<String> jump = new Dropdown<>(112, Prop.of("cfg.jump", ""));
        jump.margin(0, 2, 0, 0); // 与返回按钮同理：栏高 16、控件高 12
        for (final String key : sectionKeys) {
            jump.option(key, key);
        }
        if (!sectionKeys.isEmpty()) {
            jump.select(sectionKeys.get(0));
        }
        jump.id("section_jump");
        jump.onChange(key -> {
            for (final SectionPane pane : sections) {
                if (key.equals(pane.id)) {
                    scrollTo(pane);
                }
            }
        });
        footer.add(jump);

        final Label hint = new Label((Supplier<Component>) () -> {
            for (final FormRow row : rows) {
                if (row.rowHovered && shown(row) && row.description != null) {
                    return row.description.get();
                }
            }
            return Component.translatable("gui.sablestopnow.hint.none");
        });
        hint.color(UiTheme.current().textDim);
        hint.prefW = Widget.FILL; // 占满余下宽度（文字过长时按列宽省略）
        footer.add(hint);
        return footer;
    }

    /**
     * 数值设置项的状态。
     *
     * <p>配置值活在 {@link SablestopNowConfig} 里，界面控件不能直接持有一份副本（那就又是双份真相）。
     * 这里为每个设置项取（或创建）一个具名 {@link Prop}：滑条读它、写它，
     * 由<b>一条</b>订阅把变化落回配置。订阅只挂一次（{@code NUMBER_BOUND}），
     * 否则设置界面来回开关几次就会挂上好几条一样的回调。
     */
    private Prop<Double> numberProp(final ConfigOption option) {
        final Prop<Double> prop = Prop.of("cfg.number." + option.name(), option.getNumber());
        // 每次重建都把配置的当前值拉平：Prop 对相等的值不广播，所以这一步不会自激
        prop.set(option.getNumber());
        if (NUMBER_BOUND.add(option.name())) {
            prop.subscribe(v -> change(option, () -> option.setNumber(v)));
        }
        return prop;
    }

    private static final java.util.Set<String> NUMBER_BOUND = new java.util.HashSet<>();

    /** 建一行设置项：控件的种类由 {@link ConfigOption.Kind} 决定，界面只负责选库里的控件。 */
    private FormRow rowFor(final ConfigOption option) {        final FormRow row;
        switch (option.kind()) {
            case BOOL -> {
                // 复选框不给标签：名称由 FormRow 画在左边，于是整行都能点、整行都会亮
                final Checkbox box = new Checkbox(option::getBool,
                        value -> change(option, () -> option.setBool(value)), (Supplier<Component>) null);
                box.id(option.name());
                row = new FormRow(() -> Component.translatable(option.labelKey()), box);
                row.rowClick(() -> change(option, () -> option.setBool(!option.getBool())));
            }
            case NUMBER -> {
                final Prop<Double> model = numberProp(option);
                final Slider slider = new Slider(model, option.min(), option.max(), option.step());
                slider.width(CONTROL_W);
                // 显示文本用滑条当前值算：若读配置值会永远慢一帧，拖动时看着像卡住
                slider.format(value -> option.displayValue(value));
                slider.id(option.name());
                row = new FormRow(() -> Component.translatable(option.labelKey()), slider);
            }
            case STRING_LIST -> {
                final TextField field = new TextField(option.getText());
                field.width(CONTROL_W + 24);
                field.placeholder("gui.sablestopnow.value.empty");
                field.commit(text -> change(option, () -> option.setText(text)));
                field.id(option.name());
                row = new FormRow(() -> Component.translatable(option.labelKey()), field);
            }
            case ACTION -> {
                final Button button = new Button(() -> Component.translatable(option.labelKey() + ".button"),
                        option::runAction);
                button.id(option.name());
                row = new FormRow(() -> Component.translatable(option.labelKey()), button);
            }
            default -> row = new FormRow(() -> Component.translatable(option.labelKey()), null);
        }
        row.id("opt_" + option.name());
        row.description(() -> Component.translatable(option.descKey()));
        row.prefH = ROW_H;
        return row;
    }

    /**
     * 分组块内部的每一行按自己的高度再对一次折线，并叠加「从右往左滑入」的偏移。
     *
     * <p>分组块是「一整块」摆的（横坐标按块的中线取），块里几十像素高的行若不再对齐，
     * 靠近折线的行就会压到折线左边的灰底上。滑入偏移也走 {@code marginLeft}：
     * 行是分组内容区（vbox）摆的，只有外边距这条路径既能让库的布局器照常算宽度，
     * 又不用和布局抢 x 坐标。取上一帧的 y 即可 —— 差一帧在这个速度下看不出来。
     */
    private void alignRowsToChevron() {
        for (final FormRow row : rows) {
            final Container content = row.parent;
            if (content == null) {
                continue;
            }
            final int fold = band.edgeXAt(row.y + row.h / 2) + ROW_INSET;
            final int slide = Math.round((1.0f - row.progress()) * SLIDE_IN);
            row.marginLeft = Math.max(0, fold - content.innerX()) + slide;
        }
    }

    /** 把某个分组滚到可视区顶端（分组跳转用）。 */
    private void scrollTo(final Widget target) {
        scroll.scrollTo(Math.max(0.0, target.y - scroll.y - scroll.padTop + scroll.scroll));
    }

    /**
     * 控件自己与全部祖先都可见。
     *
     * <p>折叠起来的分组里的行不再参与布局与命中，但它的「上一次被悬停」状态还留在字段上；
     * 底栏说明要用它筛一下，否则刚折叠掉分组时，说明会停在那行上不动。
     */
    private static boolean shown(final Widget widget) {
        for (Widget w = widget; w != null; w = w.parent) {
            if (!w.visible) {
                return false;
            }
        }
        return true;
    }

    // ============ 配置写入 ============

    /**
     * 写配置项，并记一笔「待落盘」。
     *
     * <p>不在回调里立刻存档：滑条拖动每帧都会回调，那样每帧写一次文件。
     * 真正落盘交给 {@link #updateAnims()}，在鼠标松开之后统一做一次。
     */
    private void change(final ConfigOption option, final Runnable mutation) {
        mutation.run();
        if (option.onChange() != null) {
            // 例：界面动画开关要立刻推给 GUI 库，玩家不用退出界面就能看到效果
            option.onChange().run();
        }
        pendingSave = true;
    }

    // ============ 每帧动画 ============

    private double frameDelta() {
        final long now = System.nanoTime();
        final double dt = lastNanos == 0L ? 1.0 / 60.0 : (now - lastNanos) / 1.0e9;
        lastNanos = now;
        return Math.max(0.0, Math.min(0.1, dt));
    }

    private void updateAnims() {
        final double dt = frameDelta();
        final boolean instant = !UiSettings.animations;
        alignRowsToChevron();

        // 顺序：色块先从左往右铺开，设置项等它铺过大半再依次从右往左出现。
        // 闸门用色块自己的进度，而不是写死延时 —— 关掉动画时就直接全部到位。
        final double bandProgress = instant ? 1.0 : band.reveal.value();
        for (final FormRow row : rows) {
            row.appear.to(!closing && bandProgress > 0.5 ? 1.0 : 0.0);
        }

        if (pendingSave && root.dragTarget == null && root.pressedWidget == null) {
            pendingSave = false;
            SablestopNow.saveConfig();
        }

        if (closing && band.reveal.settled() && hero.grow.settled() && rowsHidden()) {
            switchPending = true;
        }
    }

    private boolean rowsHidden() {
        for (final FormRow row : rows) {
            if (!row.appear.settled()) {
                return false;
            }
        }
        return true;
    }

    // ============ 渲染与切换 ============

    @Override
    public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        updateAnims();
        super.render(graphics, mouseX, mouseY, partialTick);
        if (switchPending) {
            switchPending = false;
            if (minecraft != null) {
                final Screen target = next;
                // 不能在渲染中途换屏：交给下一 tick
                minecraft.execute(() -> minecraft.setScreen(target));
            }
        }
    }

    // ============ 离开 ============

    @Override
    public void onClose() {
        beginLeave(parent);
    }

    private void beginLeave(@Nullable final Screen target) {
        if (closing) {
            return;
        }
        closing = true;
        next = target;
        if (pendingSave) {
            pendingSave = false;
            SablestopNow.saveConfig();
        }
        // 反向：先收色块与图标，设置项同时往右退回；反向用更快的收敛，避免玩家等
        band.reveal.speed(9.0).to(0.0);
        hero.grow.speed(9.0).to(0.0);
        // 依次出现的逆过程：最后出现的那行收得最快（先走）
        final int last = Math.max(0, rows.size() - 1);
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).appear.speed(12.0 + (last - i) * 0.9).to(0.0);
        }
    }
}
