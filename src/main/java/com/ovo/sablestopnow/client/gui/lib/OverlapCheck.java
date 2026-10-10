package com.ovo.sablestopnow.client.gui.lib;

import java.util.ArrayList;
import java.util.List;

/**
 * **控件重叠检测**（"文字遮盖"的另一半 ✓ / docs/gui-统一.md）。
 *
 * <h2>为什么在库里、而且和 TextFit 分开</h2>
 * "放不下"（{@link TextFit}）和"**互相压住**"是两种不同的病 ✓：
 * <ul>
 *   <li>前者：一个标签的宽度不够 ⇒ 文字被省略/裁掉 ✗；</li>
 *   <li>后者：两个控件占同一块屏幕 ⇒ **谁也看不清** ✗（例如草图界面把五种图元按钮塞进一行，
 *       每个都被压到只剩几个像素 ✓ = 用户报的"遮盖"✓）。</li>
 * </ul>
 * 两者都是**库应该保证的事** ✓ —— 写界面的人不该靠"记得留够宽度"✗。
 *
 * <p>本类只做**纯几何**判断 ✓（不依赖字体 ✓ 不依赖 Minecraft 类型 ✗）
 * ⇒ 因此可以离线单测 ✓，并由 {@link #selfTest()} 用"必须被抓到"的合成用例自证 ✓。
 *
 * <h2>判据</h2>
 * 任意两个控件若**相交面积超过容差** ⇒ 报错 ✓。容差默认 {@link #DEFAULT_TOLERANCE}
 * （1 px ✓）：相邻控件边界相接（{@code a.x + a.w == b.x}）**不算**重叠 ✓ —— 那是正常布局 ✓。
 */
public final class OverlapCheck {

    /** 相交面积容差（px²）：小于等于它视为"边界相接"✓ 不算遮盖 ✗。 */
    public static final int DEFAULT_TOLERANCE = 1;

    /** 屏幕上的一个矩形（GUI px ✓）；{@code where} 用于出问题点名 ✓。 */
    public record Box(String where, int x, int y, int w, int h) {
        public boolean valid() {
            return this.w > 0 && this.h > 0;
        }
    }

    private OverlapCheck() {
    }

    /**
     * 检查一组矩形是否互相遮盖 ✓。
     *
     * @param boxes 控件矩形（顺序任意 ✓）
     * @return 问题列表（空 = 没有遮盖 ✓）；用 {@link UiLint.Issue} 的形状便于和既有报告合并 ✓
     */
    public static List<UiLint.Issue> check(final List<Box> boxes) {
        return check(boxes, DEFAULT_TOLERANCE);
    }

    public static List<UiLint.Issue> check(final List<Box> boxes, final int tolerance) {
        final List<UiLint.Issue> issues = new ArrayList<>();
        if (boxes == null || boxes.size() < 2) {
            return issues;
        }
        for (int i = 0; i < boxes.size(); i++) {
            final Box a = boxes.get(i);
            if (a == null || !a.valid()) {
                continue;
            }
            for (int j = i + 1; j < boxes.size(); j++) {
                final Box b = boxes.get(j);
                if (b == null || !b.valid()) {
                    continue;
                }
                final int ox = Math.min(a.x() + a.w(), b.x() + b.w()) - Math.max(a.x(), b.x());
                final int oy = Math.min(a.y() + a.h(), b.y() + b.h()) - Math.max(a.y(), b.y());
                if (ox > 0 && oy > 0 && ox * oy > tolerance) {
                    issues.add(new UiLint.Issue("WARN", "overlap", a.where() + " + " + b.where(),
                            "两个控件互相遮盖 " + (ox * oy) + " px²（重叠 " + ox + "x" + oy
                                    + "）⇒ 至少一个的文字会看不清 ✗（考虑换行/缩字号/短标签/改布局 ✓）"));
                }
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
     * **检查器自己的测试** ✓（含必须被抓到的合成缺陷 ✗ —— 理由同 {@code UiLintCheck}：
     * 一个永远报"没问题"的检查器，与"真的没问题"无法区分 ✓）。
     */
    public static Result selfTest() {
        final List<Box> boxes = new ArrayList<>();
        int checks = 0;

        // ① 并排相接（边界正好挨着）⇒ 不算重叠 ✓
        boxes.add(new Box("case1 left", 0, 0, 10, 10));
        boxes.add(new Box("case1 right", 10, 0, 10, 10));
        // ② 完全分开 ⇒ 不算重叠 ✓
        boxes.add(new Box("case2 apart", 100, 100, 10, 10));
        // ③ 明显互相压住 ⇒ **必须报** ✗（模拟"五个按钮挤一行"✓）
        boxes.add(new Box("case3 MUST-FAIL a", 20, 0, 10, 10));
        boxes.add(new Box("case3 MUST-FAIL b", 25, 0, 10, 10));
        // ④ 一像素点接触（面积 0）⇒ 不算重叠 ✓
        boxes.add(new Box("case4 corner a", 200, 200, 10, 10));
        boxes.add(new Box("case4 corner b", 210, 210, 10, 10));

        final List<UiLint.Issue> issues = check(boxes);
        checks += boxes.size();
        final List<UiLint.Issue> failures = new ArrayList<>();
        for (final UiLint.Issue issue : issues) {
            if (issue.widget() != null && issue.widget().contains("MUST-FAIL")) {
                checks++;
            } else {
                failures.add(issue);
            }
        }
        final long mustFail = issues.stream()
                .filter(i -> i.widget() != null && i.widget().contains("MUST-FAIL"))
                .count();
        if (mustFail != 1) {
            failures.add(new UiLint.Issue("ERROR", "overlap", "self-test",
                    "合成遮盖未被抓到：期望 1 个 MUST-FAIL，实际 " + mustFail + " 个 ✗"));
        }
        return new Result(checks, failures);
    }
}
