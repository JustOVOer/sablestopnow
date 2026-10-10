import com.ovo.sablestopnow.physics.Check;
import com.ovo.sablestopnow.physics.SolidMassComposition;

import java.util.List;
import java.util.Locale;

/**
 * **质量合成的坐标系回归断言**（纯 JDK ✓ 不依赖 Minecraft ✓）。
 *
 * <h2>为什么要有它</h2>
 *
 * 实机事故（2026-10-03 ✓，崩溃报告 `crash-2026-10-03_16.23.42-server.txt` ✓）：
 * 用户做了一根**半径 3 / 长 12 / 壁厚 0.5 的圆管**，把它**合并/锁定**之后，
 * `RapierPhysicsPipelineMixin.composeWithSolids` 把一个 **plot 绝对**的质心
 * （`massTracker.getCenterOfMass()` ≈ 2.048e7 ✗）和一个**刚体局部**的实体部分
 * （±6 ✓）一起送进了平行轴定理 ⇒
 * <ul>
 *   <li>合出来的**质心落在 2e7 处** ✗、惯量按 **1e17** 量级算 ✗
 *       ⇒ 症状是「质量手感不足」✗ +「竖直落地插进地下 9 格」✗；</li>
 *   <li>而 {@link SolidMassComposition} 里的坐标系守卫**当场抛异常** ✓
 *       ⇒ `Sable ticking physics` **崩服 + 客户端断线** ✗。</li>
 * </ul>
 *
 * <p>⇒ 本检查用**崩溃报告里的原始数字**把两件事钉死：
 * <ol>
 *   <li>旧写法（plot 绝对 ✗）**必须抛** {@link Check.FrameViolation} ✓
 *       —— 断言守卫是活的 ✓（否则这个 bug 会再次静默地算错 ✗）；</li>
 *   <li>新写法（`(0,0,0)` ✓）**必须不抛**、质量守恒 ✓、**惯量必须是"几十"的量级** ✓
 *       而不是 1e17 ✗✓。</li>
 * </ol>
 *
 * <p>⚠ 这是"同一个坐标系错误在本项目里出现过两次"的那一类（见 `docs/dev-run-frames.md` 事故 4 ✓）。
 * 该文档的教训是：**修好一处不等于修好这一类** ✗ ⇒ 所以这里把**数字**钉住 ✓，
 * 以后任何人再动 `combine` 的调用点，这条断言会立刻说话 ✓。
 *
 * <p>用法：{@code java MassFrameCheck}；通过时最后一行 {@code MASS_FRAME_CHECK=OK} ✓。
 */
public final class MassFrameCheck {

    /** 崩溃报告里的数字（原样抄下来 ✓ 不做"近似" ✗）。 */
    private static final double PLOT_COM_X = 2.04810325E7;
    private static final double PLOT_COM_Y = 127.99999999999997;
    private static final double PLOT_COM_Z = 2.04974165E7;
    private static final double SOLID_X = 0.05000527948141098;
    private static final double SOLID_Y = 6.500000000000057;
    private static final double SOLID_Z = -0.03647540509700775;

    private static int passed;
    private static int failed;

    private MassFrameCheck() {
    }

    public static void main(final String[] args) {
        // ---------- ① 旧写法：必须被守卫拦住 ✓ ----------
        final boolean threw = throwsFrameViolation(List.of(
                part(0.5, PLOT_COM_X, PLOT_COM_Y, PLOT_COM_Z),
                part(105.0, SOLID_X, SOLID_Y, SOLID_Z)));
        check("旧写法（tracker 用 plot 绝对 ~2.048e7 ✗）必须抛 FrameViolation ✓"
                + " —— 否则这个 bug 会再次静默算错 ✗", threw);

        // ---------- ② 新写法：必须通过，且数字必须正常 ✓ ----------
        final SolidMassComposition.Result ok = tryCombine(List.of(
                part(0.5, 0.0, 0.0, 0.0),
                part(105.0, SOLID_X, SOLID_Y, SOLID_Z)));
        check("新写法（tracker 用 (0,0,0) ✓）必须不抛异常 ✓", ok != null);
        if (ok == null) {
            summary();
            return;
        }
        check(String.format(Locale.ROOT, "质量守恒：0.5 + 105.0 = %.3f ✓", ok.mass()),
                Math.abs(ok.mass() - 105.5) < 1.0e-9);
        final double comLen = Math.hypot(ok.cx(), Math.hypot(ok.cy(), ok.cz()));
        check(String.format(Locale.ROOT,
                        "合出来的质心必须落在【局部】范围（实测 |c| = %.4f < 100 ✓）", comLen),
                comLen < 100.0);
        final double iMax = Math.max(Math.abs(ok.ixx()),
                Math.max(Math.abs(ok.iyy()), Math.abs(ok.izz())));
        check(String.format(Locale.ROOT,
                        "惯量必须是【几十】的量级（实测 max|I| = %.1f，坏值会是 1e17 ✗）", iMax),
                iMax > 0.0 && iMax < 1.0e4);
        summary();
    }

    /** 一个部分（质量 + 质心 + 惯量；惯量用小球近似 ✓ 它的值不影响坐标系的判定 ✓）。 */
    private static SolidMassComposition.Part part(final double mass,
                                                  final double x, final double y, final double z) {
        final double i = Math.max(1.0e-6, 0.4 * mass);
        return new SolidMassComposition.Part(mass, x, y, z, i, i, i, 0.0, 0.0, 0.0);
    }

    private static SolidMassComposition.Result tryCombine(
            final List<SolidMassComposition.Part> parts) {
        try {
            return SolidMassComposition.combine(parts);
        } catch (final RuntimeException e) {
            System.out.println("       combine 抛了：" + e.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean throwsFrameViolation(final List<SolidMassComposition.Part> parts) {
        try {
            SolidMassComposition.combine(parts);
            return false;
        } catch (final Check.FrameViolation e) {
            return true;
        } catch (final RuntimeException e) {
            System.out.println("       抛的不是 FrameViolation，而是 " + e.getClass().getName());
            return false;
        }
    }

    private static void check(final String what, final boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok   " + what);
        } else {
            failed++;
            System.out.println("FAIL   " + what);
        }
    }

    private static void summary() {
        System.out.println();
        if (failed == 0) {
            System.out.println("MASS_FRAME_CHECK=OK  (" + passed + " 项)");
        } else {
            System.out.println("MASS_FRAME_CHECK=FAIL  (" + failed + " 项失败 / 共 "
                    + (passed + failed) + " 项)");
            System.exit(1);
        }
    }
}
