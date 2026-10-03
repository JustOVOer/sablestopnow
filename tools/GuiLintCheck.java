package com.ovo.sablestopnow.tools;

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
        } catch (final Throwable t) {
            System.out.println("GUI_LINT=ERROR " + t);
            t.printStackTrace(System.out);
            exit = 1;
        }
        System.out.println("GUI_LINT_EXIT=" + exit);
        System.exit(exit);
    }
}
