// 临时探针：定位 wasm 里 panic（unreachable trap）发生在哪一步。
// 由空程序开始逐步加约束，找出第一个崩掉的输入。

import com.ovo.sablestopnow.sketch.SketchSolver;
import com.ovo.sablestopnow.sketch.SketchSolver.Program;
import com.ovo.sablestopnow.sketch.SketchSolver.Result;

import java.io.FileInputStream;

public final class SketchProbe {
    private SketchProbe() {
    }

    private static void step(final String label, final Runnable body) {
        System.out.println("--- " + label);
        try {
            body.run();
            System.out.println("    OK");
        } catch (final Throwable t) {
            System.out.println("    !! " + t.getClass().getSimpleName() + ": " + t.getMessage());
            throw new RuntimeException("停在: " + label, t);
        }
    }

    public static void main(final String[] args) throws Exception {
        final SketchSolver[] holder = new SketchSolver[1];
        step("加载 + ABI/init", () -> {
            try (FileInputStream in = new FileInputStream(args[0])) {
                holder[0] = SketchSolver.fromStream(in);
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        });
        final SketchSolver s = holder[0];

        step("空程序（无约束）", () -> {
            final double[] v = {1.5, -2.25};
            final Result r = s.solve(v, new int[0], new double[0]);
            System.out.println("    v=" + v[0] + "," + v[1] + " ok=" + r.ok());
        });

        step("单个 FIXED", () -> {
            final double[] v = {5.0};
            final Program p = new Program().fixed(0, 2.0);
            final Result r = s.solve(v, p.toOps(), p.toConsts());
            System.out.println("    v=" + v[0] + " ok=" + r.ok());
        });

        step("FIXED x2 + HORIZONTAL + DISTANCE（4 变量）", () -> {
            final double[] v = {0.0, 0.0, 0.1, 0.1};
            final Program p = new Program()
                    .fixed(0, 0.0).fixed(1, 0.0)
                    .horizontal(0, 1, 2, 3)
                    .distance(0, 1, 2, 3, 4.0);
            final Result r = s.solve(v, p.toOps(), p.toConsts());
            System.out.println("    v=" + v[0] + "," + v[1] + "," + v[2] + "," + v[3] + " ok=" + r.ok());
        });

        System.out.println("PROBE=DONE");
    }
}
