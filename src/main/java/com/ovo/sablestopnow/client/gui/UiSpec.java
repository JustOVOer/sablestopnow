package com.ovo.sablestopnow.client.gui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置界面的**界面规格**（P2 / docs/gui-统一.md）。
 *
 * <h2>它解决什么</h2>
 * P1 已经把"选项属于哪个分组"搬到了选项自己身上（{@link ConfigOption#section()} ✓），
 * 但**归组这件事**原先还写在屏幕类里 ✗ —— 屏幕类同时负责"数据 → 结构"和"结构 → 控件"两件事 ✗。
 * 本类把第一件事抽出来 ✓：屏幕类只交规格 ✓，之后"规格 → 控件"的循环由屏幕统一处理 ✓。
 *
 * <h2>不变量（由 tools/GuiSpecCheck.ps1 覆盖 ✓）</h2>
 * <ul>
 *   <li>每个选项都必须声明 {@code section()} ✓，否则会落进 {@link #SECTION_OTHER} ✗；</li>
 *   <li>某个组只要被选项用到，就必须出现在顺序表里 ✓（否则显示顺序会**静默**变化 ✗）；</li>
 *   <li>顺序表里的组必须真的被用到 ✓（不允许幽灵组 ✗）。</li>
 * </ul>
 */
public final class UiSpec {

    /** 没有声明分组的选项的兜底分组（lang 键）——**正常情况下应当恒为空** ✓。 */
    public static final String SECTION_OTHER = "gui.sablestopnow.section.other";

    /**
     * 一个分组：标题 lang 键 + 该组的选项（列表顺序即显示顺序 ✓）。
     */
    public record Group(String titleKey, List<ConfigOption> options) {
    }

    private UiSpec() {
    }

    /**
     * 按 {@link ConfigOption#section()} 归组 ✓。
     *
     * <p>组的先后：先按 {@code orderKeys} ✓（顺序是显示**偏好** ✓ 不是数据 ✗），
     * {@code orderKeys} 里没写到的组再按**首次出现**顺序追加 ✓
     * —— "忘了把新组写进顺序表"由门禁报错 ✓，而不是静默改变显示顺序 ✗。
     *
     * <p>真的没声明 {@code section()} 的选项才进 {@link #SECTION_OTHER} ✓（应当恒为 0 ✓）。
     *
     * @param options   该大类下的全部选项（顺序 = {@code options()} 里的顺序 ✓）
     * @param orderKeys 分组顺序表（来自 {@code SECTION_PLAN} 的键序列 ✓）
     */
    public static List<Group> groups(final List<ConfigOption> options, final List<String> orderKeys) {
        final Map<String, List<ConfigOption>> byKey = new LinkedHashMap<>();
        final List<ConfigOption> undeclared = new ArrayList<>();
        for (final ConfigOption option : options) {
            final String key = option.section();
            if (key == null) {
                undeclared.add(option);
                continue;
            }
            byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(option);
        }

        final List<String> keys = new ArrayList<>();
        for (final String key : orderKeys) {
            if (byKey.containsKey(key) && !keys.contains(key)) {
                keys.add(key);
            }
        }
        for (final String key : byKey.keySet()) {
            if (!keys.contains(key)) {
                keys.add(key);
            }
        }

        final List<Group> out = new ArrayList<>(keys.size() + 1);
        for (final String key : keys) {
            out.add(new Group(key, List.copyOf(byKey.get(key))));
        }
        if (!undeclared.isEmpty()) {
            out.add(new Group(SECTION_OTHER, List.copyOf(undeclared)));
        }
        return out;
    }
}
