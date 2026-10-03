package com.ovo.sablestopnow.sketch.nurbs;

/**
 * **有理 B 样条曲面**（NURBS surface）—— 我们所有曲面的统一表示。
 *
 * <p>定义（Piegl &amp; Tiller, eq. 4.4）：
 * <pre>
 *   S(u,v) = ΣΣ N_{i,p}(u)·N_{j,q}(v)·w_ij·P_ij  /  ΣΣ N_{i,p}(u)·N_{j,q}(v)·w_ij
 * </pre>
 *
 * <h2>这个类要解决的现实问题</h2>
 * 我们现在有**四套**互不相干的曲面表示：拉伸的轴对齐盒子、旋转的分楔 OBB、
 * 渲染用的三角网格（{@code SolidMesh}）、以及碰撞用的体素格。
 * 它们各自算各自的法线、各自的采样，于是**同一张面在不同管线里形状不一致**
 * （贴图错位、碰撞与画面不符，都是这个后果）。
 * NURBS 面是**唯一定义**：渲染细分、碰撞体素化、UV、法线，全部从它派生。
 *
 * <p>⚠ 法线用**解析偏导**（{@link #partials}）而不是从三角面几何反推：
 * 后者在细分很粗时会看到"面片状"的法线（每个三角形一个法线）。
 */
public final class NurbsSurface {

    /** u 方向次数。 */
    public final int degreeU;
    /** v 方向次数。 */
    public final int degreeV;
    /** u 方向节点向量。 */
    public final double[] knotsU;
    /** v 方向节点向量。 */
    public final double[] knotsV;
    /** 控制网：{@code control[i][j]} 是 (u=i, v=j) 的控制点，长度 3。 */
    public final double[][][] control;
    /** 权重：{@code weights[i][j]}。 */
    public final double[][] weights;

    public NurbsSurface(final int degreeU, final int degreeV,
                        final double[] knotsU, final double[] knotsV,
                        final double[][][] control, final double[][] weights) {
        this.degreeU = degreeU;
        this.degreeV = degreeV;
        this.knotsU = knotsU;
        this.knotsV = knotsV;
        this.control = control;
        this.weights = weights;
        final int nu = knotsU.length - degreeU - 1;
        final int nv = knotsV.length - degreeV - 1;
        if (control.length != nu || weights.length != nu) {
            throw new IllegalArgumentException("u 方向控制点/权重个数与节点向量不匹配");
        }
        for (int i = 0; i < nu; i++) {
            if (control[i].length != nv || weights[i].length != nv) {
                throw new IllegalArgumentException("v 方向控制点/权重个数与节点向量不匹配");
            }
        }
    }

    /** 求值，写入并返回 {@code out}（长度 3）。 */
    public double[] evaluate(final double u, final double v, final double[] out) {
        final int su = NurbsBasis.span(degreeU, knotsU, u);
        final int sv = NurbsBasis.span(degreeV, knotsV, v);
        final double[] nu = NurbsBasis.basisFuns(su, u, degreeU, knotsU);
        final double[] nv = NurbsBasis.basisFuns(sv, v, degreeV, knotsV);
        double x = 0.0;
        double y = 0.0;
        double z = 0.0;
        double w = 0.0;
        for (int a = 0; a <= degreeU; a++) {
            final int i = su - degreeU + a;
            for (int b = 0; b <= degreeV; b++) {
                final int j = sv - degreeV + b;
                final double bw = nu[a] * nv[b] * weights[i][j];
                x += bw * control[i][j][0];
                y += bw * control[i][j][1];
                z += bw * control[i][j][2];
                w += bw;
            }
        }
        if (w == 0.0) {
            out[0] = control[0][0][0];
            out[1] = control[0][0][1];
            out[2] = control[0][0][2];
            return out;
        }
        out[0] = x / w;
        out[1] = y / w;
        out[2] = z / w;
        return out;
    }

    /** 便捷重载。 */
    public double[] evaluate(final double u, final double v) {
        return evaluate(u, v, new double[3]);
    }

    /**
     * 一阶偏导 {@code (S_u, S_v)}，写入 {@code outU}/{@code outV}。
     *
     * <p>用商法则作用在齐次形式上，理由同 {@link NurbsCurve#derivative}。
     */
    public void partials(final double u, final double v,
                         final double[] outU, final double[] outV) {
        final int su = NurbsBasis.span(degreeU, knotsU, u);
        final int sv = NurbsBasis.span(degreeV, knotsV, v);
        final double[] nu = NurbsBasis.basisFuns(su, u, degreeU, knotsU);
        final double[] du = new double[degreeU + 1];
        NurbsBasis.basisDerivs1(su, u, degreeU, knotsU, du);
        final double[] nv = NurbsBasis.basisFuns(sv, v, degreeV, knotsV);
        final double[] dv = new double[degreeV + 1];
        NurbsBasis.basisDerivs1(sv, v, degreeV, knotsV, dv);

        // 三个齐次累加器：S、∂S/∂u、∂S/∂v（各 4 个分量 x,y,z,w），最后统一用商法则。
        // ⚠ 名字不能叫 su/sv —— 那两个已经是"区间下标"了（下面循环要用）。
        final double[] acc = new double[4];
        final double[] accU = new double[4];
        final double[] accV = new double[4];
        for (int a = 0; a <= degreeU; a++) {
            final int i = su - degreeU + a;
            for (int b = 0; b <= degreeV; b++) {
                final int j = sv - degreeV + b;
                final double wt = weights[i][j];
                final double b00 = nu[a] * nv[b] * wt;
                final double b10 = du[a] * nv[b] * wt;
                final double b01 = nu[a] * dv[b] * wt;
                for (int k = 0; k < 3; k++) {
                    acc[k] += b00 * control[i][j][k];
                    accU[k] += b10 * control[i][j][k];
                    accV[k] += b01 * control[i][j][k];
                }
                acc[3] += b00;
                accU[3] += b10;
                accV[3] += b01;
            }
        }
        quotient(acc, accU, outU);
        quotient(acc, accV, outV);
    }

    /** 商法则：{@code (A/w)' = (A' − w'·(A/w)) / w}。 */
    private static void quotient(final double[] a, final double[] da, final double[] out) {
        final double w = a[3];
        if (w == 0.0) {
            out[0] = 0.0;
            out[1] = 0.0;
            out[2] = 0.0;
            return;
        }
        for (int k = 0; k < 3; k++) {
            final double c = a[k] / w;
            out[k] = (da[k] - da[3] * c) / w;
        }
    }

    /**
     * 单位法线 {@code S_u × S_v}（按 u/v 的取向决定朝向）。
     *
     * <p>⚠ **这是 NURBS 方案的一个直接收益**：法线是解析的，细分多粗都不影响朝向；
     * 而我们现在的三角网格法线在每个面片上是常数，贴图与光照都会看到面片感。
     */
    public double[] normal(final double u, final double v, final double[] out) {
        final double[] su = new double[3];
        final double[] sv = new double[3];
        partials(u, v, su, sv);
        final double nx = su[1] * sv[2] - su[2] * sv[1];
        final double ny = su[2] * sv[0] - su[0] * sv[2];
        final double nz = su[0] * sv[1] - su[1] * sv[0];
        final double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1.0e-15) {
            // 退化点（例如旋转轴上的极点）：法线未定义。返回 0 而不是 NaN，
            // 让调用方有机会用邻域法线替代。
            out[0] = 0.0;
            out[1] = 0.0;
            out[2] = 0.0;
            return out;
        }
        out[0] = nx / len;
        out[1] = ny / len;
        out[2] = nz / len;
        return out;
    }

    /** 提取 u = 常数 的那条**等参曲线**（用于等参线显示/碰撞采样）。 */
    public NurbsCurve isocurveU(final double u) {
        final int nu = knotsU.length - degreeU - 1;
        final double[][] pts = new double[nu][3];
        final double[] w = new double[nu];
        for (int i = 0; i < nu; i++) {
            final int su = NurbsBasis.span(degreeU, knotsU, u);
            final double[] b = NurbsBasis.basisFuns(su, u, degreeU, knotsU);
            double x = 0.0;
            double y = 0.0;
            double z = 0.0;
            double ws = 0.0;
            for (int a = 0; a <= degreeU; a++) {
                final int k = su - degreeU + a;
                final double bw = b[a] * weights[k][i];
                x += bw * control[k][i][0];
                y += bw * control[k][i][1];
                z += bw * control[k][i][2];
                ws += bw;
            }
            pts[i][0] = ws == 0.0 ? 0.0 : x / ws;
            pts[i][1] = ws == 0.0 ? 0.0 : y / ws;
            pts[i][2] = ws == 0.0 ? 0.0 : z / ws;
            w[i] = 1.0;
        }
        return new NurbsCurve(degreeV, knotsV.clone(), pts, w);
    }
}
