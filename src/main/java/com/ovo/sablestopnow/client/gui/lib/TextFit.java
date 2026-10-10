package com.ovo.sablestopnow.client.gui.lib;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 文本可用性检查（P3c / docs/gui-统一.md）。
 *
 * <h2>为什么单独一个类、而且"字体无关"</h2>
 * 用户的要求是：**从 640×480 到更大分辨率，文字都不能出现"位置不够而……"** ✓。
 * 这类问题以前门禁抓不到 ✗ —— 因为"{@code UiLint}"是**字体无关**的（合成树 + 显式矩形 ✓），
 * 而"放不下"这件事必须**量字宽** ✗。
 *
 * <p>解法：把"量字宽"做成**参数**（{@link ToIntFunction}）✓ ——
 * <ul>
 *   <li>离线：传一个**假度量**（例如"每字符 6 px"）✓ ⇒ 检查逻辑本身可被单测 ✓；</li>
 *   <li>游戏内：传 {@code s -> font.width(s)} ✓ ⇒ 量的是真字宽 ✓。</li>
 * </ul>
 * 于是同一套判据在两条路径上都跑 ✓，而库本身**不依赖 Minecraft 字体** ✓。
 *
 * <h2>判据（硬规则）</h2>
 * 一份文本只要"放不下"（{@code measured > available}），就**必须**已经被处理 ✓：
 * {@code handled == true} 表示该标签的溢出策略是 {@code WRAP} 或 {@code ELLIPSIZE} ✓。
 * "<b>放不下 + 没处理</b>"（{@code CLIP} / 什么都不做）就是**用户看到的那类 bug** ✗ ⇒ 报错 ✓。
 */
public final class TextFit {

    /**
     * 一份要放下的文本。
     *
     * @param where     出问题时要能指出位置 ✓（例如 "642x480 gs1 zh_cn / ASSEMBLER / hull_max_pieces 标签"）
     * @param text      文本内容 ✓（最长语言取最长串 ✓）
     * @param available 可用宽度（GUI px ✓）
     * @param handled   是否已声明溢出策略（WRAP / ELLIPSIZE ✓）
     */
    public record Entry(String where, String text, int available, boolean handled) {
    }

    /**
     * **"没放下"的唯一判据** ✓✓（全项目只此一处 ✗ 避免两套判据漂移 ✓）。
     *
     * <p>为什么必须唯一 ✗✓：这条判据有两种输入 ——
     * <ul>
     *   <li>离线/矩阵：我们**自己量**字宽 ⇒ 用 {@link #notFitted(int, int, boolean)} ✓；</li>
     *   <li>活控件树：{@link Label} 渲染时**已经知道**结果 ⇒ 用 {@link #isUnhandled(Label.Fit)} ✓。</li>
     * </ul>
     * 两者必须回答同一个问题 ✓（"文字是不是没放下"✓）⇒ 判据写在这里 ✓ 调用方只查询 ✓。
     */
    public static boolean notFitted(final int measured, final int available, final boolean handled) {
        if (available <= 0) {
            return true; // 没有位置可放 ✓ 一律算"没放下"✗
        }
        return measured > available && !handled;
    }

    /** 由 {@link Label} 的实测结果判断 ✓（ELLIPSIZED / CLIPPED = 没放下 ✗）。 */
    public static boolean isUnhandled(final Label.Fit fit) {
        return fit == Label.Fit.ELLIPSIZED || fit == Label.Fit.CLIPPED;
    }
    private TextFit() {
    }

    /**
     * 按 {@code measure}（字宽函数 ✓）检查每条文本 ✓。
     *
     * @return 问题列表（空 = 全过 ✓）；用 {@link UiLint.Issue} 的形状，便于和既有报告合并 ✓
     */
    public static List<UiLint.Issue> check(final ToIntFunction<String> measure, final List<Entry> entries) {
        final List<UiLint.Issue> issues = new ArrayList<>();
        if (measure == null || entries == null) {
            return issues;
        }
        for (final Entry e : entries) {
            if (e == null || e.text() == null || e.text().isEmpty()) {
                continue;
            }
            if (e.available() <= 0) {
                issues.add(new UiLint.Issue("ERROR", "textfit", e.where(),
                        "可用宽度为 0 或负数 ⇒ 这条文本没有位置可放（" + e.available() + "）"));
                continue;
            }
            final int measured = measure.applyAsInt(e.text());
            if (notFitted(measured, e.available(), e.handled())) {
                issues.add(new UiLint.Issue("ERROR", "textfit", e.where(),
                        "放不下且未声明溢出策略：需要 " + measured + " px，只有 " + e.available()
                                + " px（应改成 WRAP 或 ELLIPSIZE ✓）"));
            }
        }
        return issues;
    }

    /** 检查结果（与 {@code UiLint.selfTest()} 同形状 ✓）。 */
    public record Result(int checks, List<UiLint.Issue> issues) {
        public boolean pass() {
            return this.issues.isEmpty();
        }
    }

    /**
     * **检查器自己的测试** ✓ —— 合成用例，其中必须包含"应当被抓到"的缺陷 ✗。
     *
     * <p>理由与 {@code UiLintCheck} 的注释一致 ✓：一个永远报"没问题"的检查器，
     * 与"真的没问题"无法区分 ✓ ⇒ 检查器本身必须被测试 ✓。
     */
    public static Result selfTest() {
        final ToIntFunction<String> fake = s -> s.length() * 6; // 假度量：每字符 6px ✓
        final List<Entry> entries = new ArrayList<>();
        int checks = 0;

        // ① 放得下 ⇒ 不报 ✓
        entries.add(new Entry("case1 fits", "abc", 6 * 3, false));
        // ② 放不下但已处理（WRAP/ELLIPSIZE）⇒ 不报 ✓
        entries.add(new Entry("case2 handled", "abcdefghij", 6 * 4, true));
        // ③ 放不下且未处理 ⇒ **必须报** ✗
        entries.add(new Entry("case3 MUST-FAIL", "abcdefghij", 6 * 4, false));
        // ④ 可用宽度为 0 ⇒ **必须报** ✗
        entries.add(new Entry("case4 MUST-FAIL", "abc", 0, true));

        final List<UiLint.Issue> issues = check(fake, entries);
        checks += entries.size();
        final List<UiLint.Issue> failures = new ArrayList<>();
        for (final UiLint.Issue i : issues) {
            if (i.widget() != null && i.widget().contains("MUST-FAIL")) {
                checks++;
            } else {
                failures.add(i);
            }
        }
        final long mustFailSeen = issues.stream()
                .filter(i -> i.widget() != null && i.widget().contains("MUST-FAIL"))
                .count();
        if (mustFailSeen != 2) {
            failures.add(new UiLint.Issue("ERROR", "textfit", "self-test",
                    "合成缺陷未被全部抓到：期望 2 个 MUST-FAIL，实际 " + mustFailSeen + " 个 ✗"));
        }
        return new Result(checks, failures);
    }
}
