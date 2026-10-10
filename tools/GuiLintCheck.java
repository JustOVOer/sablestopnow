package com.ovo.sablestopnow.tools;

import com.ovo.sablestopnow.client.gui.lib.OverlapCheck;
import com.ovo.sablestopnow.client.gui.lib.TextFit;
import com.ovo.sablestopnow.client.gui.lib.UiLint;

/**
 * Offline runner for the GUI self-check harness.
 *
 * <p>Why it exists: {@link UiLint#selfTest()} builds synthetic widget trees with known defects and
 * requires every check to fire. A checker that always reports "no problems" looks exactly like a
 * clean bill of health, so the checker itself needs a test. Running it here means a broken checker
 * is caught by {@code tools/verify-all.ps1} instead of during an in-game test round.
 *
 * <p>It never touches {@code Minecraft.getInstance()}: the trees are assembled by hand with explicit
 * rects, so no font, no renderer and no client bootstrap are needed.
 */
public final class GuiLintCheck {

    private GuiLintCheck() {
    }

    public static void main(final String[] args) {
        int exit = 0;
        try {
            final UiLint.SelfTest result = UiLint.selfTest();
            if (result.pass()) {
                System.out.println("GUI_LINT=OK checks=" + result.checks());
            } else {
                System.out.println("GUI_LINT=FAILED checks=" + result.checks()
                        + " failures=" + result.failures().size());
                for (final String failure : result.failures()) {
                    System.out.println("  " + failure);
                }
                exit = 1;
            }
            // P3c：文本可用性检查器**自身**的测试 ✓（字体无关 ⇒ 离线可跑 ✓）。
            //   真正量字宽那次在客户端启动时跑（那里才有 Font ✓ 见 TextFit 的类注释 ✓）。
            // P3c / 遮盖：控件重叠检测器**自身**的测试 ✓（纯几何 ⇒ 离线可跑 ✓）。
            //   真正扫活控件树那次在客户端启动时跑（那里才有真实布局后的矩形 ✓ 见 OverlapCheck 的类注释 ✓）。
            final OverlapCheck.Result overlap = OverlapCheck.selfTest();
            if (overlap.pass()) {
                System.out.println("GUI_LINT_OVERLAP=OK checks=" + overlap.checks());
            } else {
                System.out.println("GUI_LINT_OVERLAP=FAILED checks=" + overlap.checks()
                        + " failures=" + overlap.issues().size());
                for (final UiLint.Issue issue : overlap.issues()) {
                    System.out.println("  " + issue.severity() + " " + issue.area() + " "
                            + issue.widget() + ": " + issue.detail());
                }
                exit = 1;
            }
            final TextFit.Result textFit = TextFit.selfTest();
            if (textFit.pass()) {
                System.out.println("GUI_LINT_TEXTFIT=OK checks=" + textFit.checks());
            } else {
                System.out.println("GUI_LINT_TEXTFIT=FAILED checks=" + textFit.checks()
                        + " failures=" + textFit.issues().size());
                for (final UiLint.Issue issue : textFit.issues()) {
                    System.out.println("  " + issue.severity() + " " + issue.area() + " "
                            + issue.widget() + ": " + issue.detail());
                }
                exit = 1;
            }        } catch (final Throwable t) {
            System.out.println("GUI_LINT=ERROR " + t);
            t.printStackTrace(System.out);
            exit = 1;
        }
        System.out.println("GUI_LINT_EXIT=" + exit);
        System.exit(exit);
    }
}
