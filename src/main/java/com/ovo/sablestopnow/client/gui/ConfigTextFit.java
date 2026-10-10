package com.ovo.sablestopnow.client.gui;

import com.ovo.sablestopnow.client.gui.lib.TextFit;

import java.util.ArrayList;
import java.util.List;

/**
 * 把配置界面的**文字与可用宽度**展开成 {@link TextFit.Entry} 列表（P3c-5 / docs/gui-统一.md）。
 *
 * <h2>为什么需要它</h2>
 * {@link TextFit} 是**字体无关**的检查器 ✓（度量由调用方注入 ✓）⇒ 它能被离线单测 ✓；
 * 但"这段文字在这个宽度下放得下吗"必须有**真实字宽** ✗ ⇒ 只能在客户端里量 ✓
 * （离线 lint 按设计是 font-free ✓ 这也是这类问题以前从没被门禁抓到 ✗ 的原因 ✓）。
 *
 * <p>本类只负责"**喂什么数据**" ✓：从声明式数据（{@link ModConfigCategory} 的标题/副标题 ✓、
 * {@link UiSpec} 的分组 ✓、{@link ConfigOption} 的标签 ✓）派生条目 ✓，宽度取自布局常量的
 * **保守估计** ✓（宁可报得早一点 ✓ 也不要漏 ✗）。真正的测量由调用方注入 ✓。
 *
 * <h2>用法</h2>
 * <pre>
 *   final var entries = ConfigTextFit.entriesFor(width, height, guiScale);
 *   final var issues  = TextFit.check(s -&gt; font.width(s), entries);   // 游戏内
 *   // 离线（没有 Font）：TextFit.check(s -&gt; s.length() * 6, entries) 也能跑逻辑 ✓
 * </pre>
 *
 * <p>⚠ 语言：只量**当前语言** ✓ —— 检查在每次启动都会跑 ✓，换语言后下次启动即覆盖另一种 ✓。
 */
public final class ConfigTextFit {

    private ConfigTextFit() {
    }

    /**
     * 左栏宽度（与 {@code ModSettingsScreen} 的算法一致 ✓：{@code clamp(width * 0.30, 92, 170)} ✓）。
     */
    public static int panelWidth(final int screenWidth) {
        return Math.max(92, Math.min(170, Math.round(screenWidth * 0.30f)));
    }

    /**
     * 左栏一行文字可用宽度 ✓（扣掉行左缩进与右侧留白 ✓）。
     */
    public static int tileTextWidth(final int screenWidth) {
        final int panelW = panelWidth(screenWidth);
        final int rowX = Math.max(8, Math.round(panelW * 0.14f));
        return Math.max(1, panelW - rowX - 8);
    }

    /**
     * 右侧设置项标签可用宽度 ✓：整宽 − 折线深度 − 控件列宽 − 两侧留白 ✓（保守 ✓）。
     */
    public static int rowLabelWidth(final int screenWidth) {
        return Math.max(1, screenWidth - 44 - 120 - 24);
    }

    /**
     * 为给定屏幕尺寸展开条目 ✓。
     *
     * <p>{@code guiScale} 目前只用于给 {@code where} 打标记 ✓（MC 自己会把 GUI px 换算好 ✓）；
     * 保留它是为了报告里能说清"是哪一档分辨率/缩放"✓。
     */
    public static List<TextFit.Entry> entriesFor(final int screenWidth, final int screenHeight,
                                                 final int guiScale) {
        final List<TextFit.Entry> out = new ArrayList<>();
        final String tag = screenWidth + "x" + screenHeight + " gs" + guiScale;
        final int tileW = tileTextWidth(screenWidth);
        final int rowW = rowLabelWidth(screenWidth);

        for (final ModConfigCategory category : ModConfigCategory.values()) {
            // 左栏大类：标题与副标题 ✓（标签声明了溢出策略 ⇒ handled = true ✓）
            out.add(new TextFit.Entry(tag + " / left / " + category.id() + " title",
                    category.title().getString(), tileW, true));
            out.add(new TextFit.Entry(tag + " / left / " + category.id() + " subtitle",
                    category.subtitle().getString(), tileW, true));

            // 分组标题（顺序与显示无关 ✓ 这里只为量宽度 ✓）
            for (final UiSpec.Group group : UiSpec.groups(category.options(), List.of())) {
                out.add(new TextFit.Entry(tag + " / section / " + category.id() + " " + group.titleKey(),
                        net.minecraft.network.chat.Component.translatable(group.titleKey()).getString(),
                        rowW, true));
                for (final ConfigOption option : group.options()) {
                    // 设置项标签 + 描述 ✓（描述在提示里显示 ✓ 同样不能放不下 ✗）
                    out.add(new TextFit.Entry(tag + " / row / " + category.id() + " " + option.name(),
                            net.minecraft.network.chat.Component.translatable(option.labelKey()).getString(),
                            rowW, true));
                    out.add(new TextFit.Entry(tag + " / row.desc / " + category.id() + " " + option.name(),
                            net.minecraft.network.chat.Component.translatable(option.descKey()).getString(),
                            rowW, true));
                }
            }
        }
        return out;
    }
}
