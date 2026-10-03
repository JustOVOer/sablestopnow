package com.ovo.sablestopnow.sketch.nurbs;

/**
 * **有理 B 样条曲线**（NURBS curve）—— 我们的草图轮廓在 NURBS 下的统一表示。
 *
 * <p>定义（Piegl &amp; Tiller, eq. 4.1）：
 * <pre>
 *   C(u) = Σ N_{i,p}(u) · w_i · P_i  /  Σ N_{i,p}(u) · w_i
 * </pre>
 * 齐次形式 {@code Cw(u) = Σ N_{i,p}(u) · (w_i P_i, w_i)}，再除以权重分量 —— 这也是
 * 求导最方便的形式（商法则），见 {@link #derivative}。
 *
 * <p>⚠ **权不是可有可无的装饰**：圆弧/圆**必须**用有理形式（权 = 1, cos(Δφ/2), …）
 * 才能精确表示；非有理 B 样条只能逼近圆。我们现在的"圆"是拿折线拼的，
 * 换成 NURBS 之后**圆是真的圆**，这是统一的第一个收益。
 */
public final class NurbsCurve {

    /** 次数 p。 */
    public final int degree;
    /** 节点向量，长度 = 控制点数 + degree + 1。 */
    public final double[] knots;
    /** 控制点，长度 = knots.length − degree − 1，每个长度 3。 */
    public final double[][] control;
    /** 权重，长度与控制点相同。 */
    public final double[] weights;

    public NurbsCurve(final int degree, final double[] knots, final double[][] control,
                      final double[] weights) {
        this.degree = degree;
        this.knots = knots;
        this.control = control;
        this.weights = weights;
        if (control.length != knots.length - degree - 1) {
            throw new IllegalArgumentException("控制点数与节点向量长度不匹配");
        }
        if (weights.length != control.length) {
            throw new IllegalArgumentException("权重个数与控制点数不匹配");
        }
    }

    /** 控制点个数。 */
    public int controlCount() {
        return control.length;
    }

    /** 定义域下界。 */
    public double uMin() {
        return knots[degree];
    }

    /** 定义域上界。 */
    public double uMax() {
        return knots[knots.length - degree - 1];
    }

    /** 求值，写入并返回 {@code out}（长度 3）。 */
    public double[] evaluate(final double u, final double[] out) {
        final int span = NurbsBasis.span(degree, knots, u);
        final double[] n = NurbsBasis.basisFuns(span, u, degree, knots);
        double wx = 0.0;
        double wy = 0.0;
        double wz = 0.0;
        double wsum = 0.0;
        for (int j = 0; j <= degree; j++) {
            final int i = span - degree + j;
            final double bw = n[j] * weights[i];
            wx += bw * control[i][0];
            wy += bw * control[i][1];
            wz += bw * control[i][2];
            wsum += bw;
        }
        // ⚠ 权重和理论上恒 > 0（基函数非负且和为 1、权为正），但不做保护的话
        //   一旦有人塞进 0 权就会得到 NaN 并静默传播到整个网格。
        if (wsum == 0.0) {
            out[0] = control[0][0];
            out[1] = control[0][1];
            out[2] = control[0][2];
            return out;
        }
        out[0] = wx / wsum;
        out[1] = wy / wsum;
        out[2] = wz / wsum;
        return out;
    }

    /** 便捷重载。 */
    public double[] evaluate(final double u) {
        return evaluate(u, new double[3]);
    }

    /**
     * 一阶导数 {@code C'(u)}（商法则作用在齐次形式上）。
     *
     * <p>商法则：{@code C = A/w} ⇒ {@code C' = (A' − w'·C) / w}。
     */
    public double[] derivative(final double u, final double[] out) {
        final int span = NurbsBasis.span(degree, knots, u);
        final double[] n = NurbsBasis.basisFuns(span, u, degree, knots);
        final double[] d = new double[degree + 1];
        NurbsBasis.basisDerivs1(span, u, degree, knots, d);
        double ax = 0.0;
        double ay = 0.0;
        double az = 0.0;
        double w = 0.0;
        double dax = 0.0;
        double day = 0.0;
        double daz = 0.0;
        double dw = 0.0;
        for (int j = 0; j <= degree; j++) {
            final int i = span - degree + j;
            final double bw = n[j] * weights[i];
            final double dbw = d[j] * weights[i];
            ax += bw * control[i][0];
            ay += bw * control[i][1];
            az += bw * control[i][2];
            w += bw;
            dax += dbw * control[i][0];
            day += dbw * control[i][1];
            daz += dbw * control[i][2];
            dw += dbw;
        }
        if (w == 0.0) {
            out[0] = 0.0;
            out[1] = 0.0;
            out[2] = 0.0;
            return out;
        }
        final double cx = ax / w;
        final double cy = ay / w;
        final double cz = az / w;
        out[0] = (dax - dw * cx) / w;
        out[1] = (day - dw * cy) / w;
        out[2] = (daz - dw * cz) / w;
        return out;
    }

    /** 便捷重载。 */
    public double[] derivative(final double u) {
        return derivative(u, new double[3]);
    }

    /**
     * 按**弦高容差**自适应采样：返回一串 {@code u}，使相邻采样点之间的折线与曲线的
     * 最大偏差不超过 {@code tol}。
     *
     * <p>做法是**递归二分 + 弦高判据**（不是按固定步数）：中点处曲线到弦的距离超过容差
     * 就继续二分。这是接现有渲染/碰撞管线的关键一步 —— 两边都吃三角网格。
     *
     * <p>⚠ 必须有**递归深度上限**：曲率极大或容差极小时会无限二分。
     */
    public double[] adaptiveSamples(final double tol, final int maxDepth) {
        final java.util.List<Double> us = new java.util.ArrayList<>();
        us.add(uMin());
        subdivide(uMin(), uMax(), tol, maxDepth, us, 0);
        us.add(uMax());
        final double[] out = new double[us.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = us.get(i);
        }
        return out;
    }

    private void subdivide(final double u0, final double u1, final double tol, final int maxDepth,
                           final java.util.List<Double> us, final int depth) {
        final double um = 0.5 * (u0 + u1);
        final double[] p0 = evaluate(u0);
        final double[] p1 = evaluate(u1);
        final double[] pm = evaluate(um);
        // 弦高：中点实际位置到「弦」这条线段的距离
        final double cx = p1[0] - p0[0];
        final double cy = p1[1] - p0[1];
        final double cz = p1[2] - p0[2];
        final double len = Math.sqrt(cx * cx + cy * cy + cz * cz);
        double dev;
        if (len < 1.0e-15) {
            dev = Math.sqrt((pm[0] - p0[0]) * (pm[0] - p0[0]) + (pm[1] - p0[1]) * (pm[1] - p0[1])
                    + (pm[2] - p0[2]) * (pm[2] - p0[2]));
        } else {
            final double mx = pm[0] - p0[0];
            final double my = pm[1] - p0[1];
            final double mz = pm[2] - p0[2];
            final double crossX = cy * mz - cz * my;
            final double crossY = cz * mx - cx * mz;
            final double crossZ = cx * my - cy * mx;
            dev = Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ) / len;
        }
        if (dev <= tol || depth >= maxDepth) {
            return;
        }
        subdivide(u0, um, tol, maxDepth, us, depth + 1);
        us.add(um);
        subdivide(um, u1, tol, maxDepth, us, depth + 1);
    }
}
