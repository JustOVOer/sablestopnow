package com.ovo.sablestopnow.sketch.nurbs;

/**
 * NURBS 的**基函数层**（Cox–de Boor 递推）。
 *
 * <h2>为什么自己写，而不是引第三方</h2>
 * 调研结论（见 {@code docs/nurbs-unification-design.md}）：
 * <ul>
 *   <li>成熟的 NURBS 内核（**OCCT / Open CASCADE**、**SISL**）是 **C/C++**，协议是
 *       **LGPL-2.1**（OCCT 带一个例外，但**例外只覆盖头文件**，不覆盖库本身）——
 *       链进 MIT 的 mod 里就要承担**允许重新链接**的义务，而且体积是几百 MB，
 *       对 Minecraft mod 完全不合适；</li>
 *   <li>Java 侧没有可用的 NURBS **曲面** 库：{@code jgeom} 老旧且**协议本身有争议**
 *       （Xj3D 邮件列表里有专门的 "Licensing Issue with jgeom" 讨论），
 *       {@code curvesapi} 是 Apache-2.0 但只做**二维 Bézier 折线**（给表格用的），
 *       不是 NURBS 曲面。</li>
 * </ul>
 * 而**我们需要的 NURBS 只是"有理 B 样条求值"这一小块**（任意曲面支持是后续的事），
 * 算法是公开的教科书内容（The NURBS Book, Piegl &amp; Tiller），自己实现最干净：
 * 纯 Java、无协议负担、无原生依赖，而且**可以离线断言**。
 *
 * <p>⚠ 本类只依赖 JDK（数组 + double），**不依赖 JOML、不依赖 Minecraft**，
 * 所以 {@code tools/NurbsCheck.java} 能在没有 Minecraft 的环境下直接跑。
 */
public final class NurbsBasis {

    private NurbsBasis() {
    }

    /**
     * 找到 {@code u} 所在的节点区间下标（NURBS Book A2.1）。
     *
     * <p>返回值 {@code span} 满足 {@code knots[span] <= u < knots[span+1]}，
     * 且 {@code degree <= span <= n}（n = 控制点数 − 1）。
     *
     * <p>⚠ 这里用的是**二分查找**而不是线性扫描：节点向量在自适应细分里会被反复求值，
     * 线性扫描在细分密的时候是 O(控制点数) 每次。同时注意**边界特判**——
     * 二分对 {@code u == knots[n+1]}（右端点）会越界，必须显式返回 n。
     */
    public static int span(final int degree, final double[] knots, final double u) {
        final int n = knots.length - degree - 2; // 最大控制点下标
        if (u >= knots[n + 1]) {
            // 右端：回退到**最后一个非退化区间**
            int s = n;
            while (s > degree && knots[s] == knots[s + 1]) {
                s--;
            }
            return s;
        }
        if (u <= knots[degree]) {
            // ⚠⚠ 左端：前进到**第一个非退化区间**。
            //   教科书（NURBS Book A2.1）这里直接 `return p`，在**夹紧端点**上会返回一个
            //   **零长度区间**（如 degree=1、节点 {0,0,0,…}、u=0 时返回 1，而 [U1,U2)=[0,0) 是空的），
            //   接着 basisFuns 里所有分母都是 0 → 基函数**全为 0** → 导数为 0 → **法线退化**。
            //   我实测到的现象就是：圆柱面在 u=0、v=0、v=1 上法线长度为 0（其余点全对）。
            //   边界是最容易出错的地方，而"端点的法线"恰恰是网格缝合、圆角、倒角的常用位置。
            int s = degree;
            while (s < n && knots[s] == knots[s + 1]) {
                s++;
            }
            return s;
        }
        int low = degree;
        int high = n + 1;
        int mid = (low + high) / 2;
        while (u < knots[mid] || u >= knots[mid + 1]) {
            if (u < knots[mid]) {
                high = mid;
            } else {
                low = mid;
            }
            mid = (low + high) / 2;
        }
        return mid;
    }

    /**
     * 计算该区间上非零的 {@code degree+1} 个基函数值（NURBS Book A2.2）。
     *
     * @param out 长度至少 {@code degree+1}，写入 {@code out[0..degree]}；
     *            其中 {@code out[j]} 对应基函数 {@code N_{span-degree+j, degree}}
     */
    public static void basisFuns(final int span, final double u, final int degree,
                                 final double[] knots, final double[] out) {
        final double[] left = new double[degree + 1];
        final double[] right = new double[degree + 1];
        out[0] = 1.0;
        for (int j = 1; j <= degree; j++) {
            left[j] = u - knots[span + 1 - j];
            right[j] = knots[span + j] - u;
            double saved = 0.0;
            for (int r = 0; r < j; r++) {
                final double denom = right[r + 1] + left[j - r];
                // ⚠ 除以 0 只在**重节点**处出现（denom == 0），此时该项的基函数本就该是 0。
                //   不特判会得到 NaN 并污染整条曲线 —— 而端点处全是重节点，必踩。
                final double temp = denom == 0.0 ? 0.0 : out[r] / denom;
                out[r] = saved + right[r + 1] * temp;
                saved = left[j - r] * temp;
            }
            out[j] = saved;
        }
    }

    /** 便捷重载：自己分配返回数组。 */
    public static double[] basisFuns(final int span, final double u, final int degree,
                                     final double[] knots) {
        final double[] out = new double[degree + 1];
        basisFuns(span, u, degree, knots, out);
        return out;
    }

    /**
     * 基函数的一阶导数（NURBS Book A2.3 的简化：只求一阶）。
     *
     * <p>法线要用它。写通用高阶导数的代码量远大于收益，而**我们只需要一阶**。
     *
     * @param out 长度至少 {@code degree+1}
     */
    public static void basisDerivs1(final int span, final double u, final int degree,
                                    final double[] knots, final double[] out) {
        // ⚠ 我第一版按 NURBS Book A2.3 的 ndu 三角手写，结果与中心差分差 1.3e-5
        //   （远大于浮点误差）—— 说明索引推错了。改成**定义式直接算**，不绕 A2.3：
        //
        //     d/du N_{i,p} = p/(u_{i+p} − u_i) · N_{i,p−1}(u)
        //                  − p/(u_{i+p+1} − u_{i+1}) · N_{i+1,p−1}(u)
        //
        //   N_{·,p−1} 直接在**同一节点向量的 p−1 次**上求值即可（p−1 次的非零基函数
        //   只有 p 个，用一个 span 就能覆盖我们要的全部下标）。
        final int lower = Math.max(0, degree - 1);
        final int spanLow = span(lower, knots, u);
        final double[] blo = new double[lower + 1];
        if (lower == 0) {
            blo[0] = 1.0;
        } else {
            basisFuns(spanLow, u, lower, knots, blo);
        }
        final int firstLow = spanLow - lower; // blo[j] = N_{firstLow+j, lower}
        for (int j = 0; j <= degree; j++) {
            final int i = span - degree + j;
            double sum = 0.0;
            final double den1 = knots[i + degree] - knots[i];
            if (den1 != 0.0) {
                sum += degree * lowerBasis(blo, firstLow, i) / den1;
            }
            final double den2 = knots[i + degree + 1] - knots[i + 1];
            if (den2 != 0.0) {
                sum -= degree * lowerBasis(blo, firstLow, i + 1) / den2;
            }
            out[j] = sum;
        }
    }

    /** 取 p−1 次基函数表里的 {@code N_{k, lower}}；不在表里（必为 0）就返回 0。 */
    private static double lowerBasis(final double[] blo, final int firstLow, final int k) {
        final int idx = k - firstLow;
        return idx >= 0 && idx < blo.length ? blo[idx] : 0.0;
    }

    /**
     * **夹紧**（clamped）均匀节点向量：{@code p+1} 个 0、内部节点、{@code p+1} 个 1。
     *
     * <p>夹紧是必须的：只有夹紧的 NURBS 才**过首末控制点**（曲线端点 = 控制点），
     * 我们的曲面都是"从草图那条边开始"的，靠的就是这个性质。
     *
     * @param ctrlCount 控制点个数（= 节点数 − degree − 1）
     */
    public static double[] clampedKnots(final int degree, final int ctrlCount) {
        final int knots = ctrlCount + degree + 1;
        final double[] out = new double[knots];
        final int inner = ctrlCount - degree - 1;
        for (int i = 0; i < knots; i++) {
            if (i <= degree) {
                out[i] = 0.0;
            } else if (i >= ctrlCount) {
                out[i] = 1.0;
            } else {
                out[i] = (double) (i - degree) / (inner + 1);
            }
        }
        return out;
    }

    /** 把定义域 {@code [0,1]} 上的节点向量重标到 {@code [a,b]}（几何长度用得上）。 */
    public static double[] remapKnots(final double[] knots, final double a, final double b) {
        final double[] out = new double[knots.length];
        for (int i = 0; i < knots.length; i++) {
            out[i] = a + (b - a) * knots[i];
        }
        return out;
    }
}
