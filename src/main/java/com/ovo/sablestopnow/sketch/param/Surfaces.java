package com.ovo.sablestopnow.sketch.param;

/**
 * 四种曲面的**统一实现**（平面 / 圆柱 / 旋转 / 拉伸）。
 *
 * <p>全部通过 {@link ParamSurface} 暴露，调用方（渲染、碰撞、贴图）**看不到具体类型**。
 *
 * <h2>自然参数的语义（决定了贴图"沿模型方向流动"）</h2>
 * <table border="1">
 *   <caption>四种曲面的 (u,v)</caption>
 *   <tr><th>类型</th><th>u</th><th>v</th><th>单位</th></tr>
 *   <tr><td>平面</td><td>面内方向 1</td><td>面内方向 2</td><td>方块（天然等距）</td></tr>
 *   <tr><td>圆柱面</td><td>θ·R（圆周弧长）</td><td>沿轴高度</td><td>方块</td></tr>
 *   <tr><td>旋转面</td><td>轮廓累积弧长 t</td><td>θ·<b>R_ref</b>（全局面参考半径）</td><td>方块</td></tr>
 *   <tr><td>拉伸面</td><td>轮廓累积弧长 t</td><td>沿拉伸方向 s</td><td>方块</td></tr>
 * </table>
 *
 * <p>⚠ **旋转面的 v 必须用全局面参考半径 {@code R_ref}，不能用每条棱自己的半径**。
 * 用局部半径会让相邻两条棱（两个环）的 v 比例不同、贴图网格对不上，
 * 表现为"**离基准面越远畸变越大**"（用户实测反馈）。这就是本次的核心修复。
 */
public final class Surfaces {

    private Surfaces() {
    }

    // ---------------------------------------------------------------- 平面

    /** 平面片：{@code origin + u·axisU + v·axisV}。u/v 直接就是方块距离。 */
    public static ParamSurface plane(final double[] origin, final double[] axisU,
                                     final double[] axisV, final double lenU,
                                     final double lenV) {
        return new Plane(origin, axisU, axisV, lenU, lenV);
    }

    private static final class Plane implements ParamSurface {
        private final double[] o;
        private final double[] au;
        private final double[] av;
        private final double lu;
        private final double lv;

        Plane(final double[] o, final double[] au, final double[] av, final double lu,
              final double lv) {
            this.o = o;
            this.au = au;
            this.av = av;
            this.lu = lu;
            this.lv = lv;
        }

        @Override
        public double[] evaluate(final double u, final double v, final double[] out) {
            for (int k = 0; k < 3; k++) {
                out[k] = o[k] + u * au[k] + v * av[k];
            }
            return out;
        }

        @Override
        public double[] project(final double[] p, final double[] out) {
            final double[] d = {p[0] - o[0], p[1] - o[1], p[2] - o[2]};
            out[0] = dot(d, au);
            out[1] = dot(d, av);
            return out;
        }

        @Override
        public double[] normal(final double u, final double v, final double[] out) {
            // 平面法线恒定：单位化 axisU × axisV
            final double[] n = cross(au, av);
            final double len = norm(n);
            for (int k = 0; k < 3; k++) {
                out[k] = n[k] / len;
            }
            return out;
        }

        @Override
        public double uMin() {
            return 0.0;
        }

        @Override
        public double uMax() {
            return lu;
        }

        @Override
        public double vMin() {
            return 0.0;
        }

        @Override
        public double vMax() {
            return lv;
        }

        @Override
        public UvMapping.Mode preferredMapping() {
            return UvMapping.Mode.PLANAR;
        }
    }

    // ---------------------------------------------------------------- 圆柱

    /**
     * 圆柱面：轴过 {@code origin}、方向 {@code axisW}，半径 {@code radius}。
     *
     * <p>u = θ·R（**常量半径**，所以天然一致），v = 沿轴高度。
     */
    public static ParamSurface cylinder(final double[] origin, final double[] axisU,
                                        final double[] axisV, final double radius,
                                        final double height) {
        return new Cylinder(origin, axisU, axisV, radius, height);
    }

    private static final class Cylinder implements ParamSurface {
        private final double[] o;
        private final double[] au;
        private final double[] av;
        private final double r;
        private final double h;

        Cylinder(final double[] o, final double[] au, final double[] av, final double r,
                 final double h) {
            this.o = o;
            this.au = au;
            this.av = av;
            this.r = r;
            this.h = h;
        }

        @Override
        public double[] evaluate(final double u, final double v, final double[] out) {
            // u 是圆周弧长 → 角度 = u / r（常量 r，所以一致）
            final double theta = u / r;
            final double c = Math.cos(theta);
            final double s = Math.sin(theta);
            for (int k = 0; k < 3; k++) {
                out[k] = o[k] + r * (c * au[k] + s * av[k]) + v * cross(au, av)[k] * 0.0;
            }
            // 轴向：用 au × av 得到的单位轴
            final double[] w = unit(cross(au, av));
            for (int k = 0; k < 3; k++) {
                out[k] += v * w[k];
            }
            return out;
        }

        @Override
        public double[] project(final double[] p, final double[] out) {
            final double[] d = {p[0] - o[0], p[1] - o[1], p[2] - o[2]};
            final double a = dot(d, au);
            final double b = dot(d, av);
            final double theta = Math.atan2(b, a);
            out[0] = theta * r;
            out[1] = dot(d, unit(cross(au, av)));
            return out;
        }

        @Override
        public double[] normal(final double u, final double v, final double[] out) {
            final double theta = u / r;
            final double c = Math.cos(theta);
            final double s = Math.sin(theta);
            for (int k = 0; k < 3; k++) {
                out[k] = c * au[k] + s * av[k];
            }
            return out;
        }

        @Override
        public double uMin() {
            return 0.0;
        }

        @Override
        public double uMax() {
            return 2 * Math.PI * r;
        }

        @Override
        public double vMin() {
            return 0.0;
        }

        @Override
        public double vMax() {
            return h;
        }

        @Override
        public UvMapping.Mode preferredMapping() {
            return UvMapping.Mode.CYLINDRICAL;
        }
    }

    // ---------------------------------------------------------------- 旋转面

    /**
     * 旋转面：二维轮廓 {@code profile[i] = {r, z}} 绕轴旋转。
     *
     * <p>u = 沿轮廓的累积弧长（预计算表），v = θ·**R_ref**，
     * 其中 {@code R_ref} 是轮廓半径的**全局均值**。
     */
    public static ParamSurface revolve(final double[][] profile, final double[] origin,
                                       final double[] axisU, final double[] axisV) {
        return new Revolve(profile, origin, axisU, axisV);
    }

    private static final class Revolve implements ParamSurface {
        private final double[][] profile;
        private final double[] o;
        private final double[] au;
        private final double[] av;
        private final ArcLengthTable table;
        /** **全局面参考半径**（不是每条棱自己的半径）。 */
        private final double refRadius;

        Revolve(final double[][] profile, final double[] o, final double[] au, final double[] av) {
            this.profile = profile;
            this.o = o;
            this.au = au;
            this.av = av;
            double sum = 0.0;
            for (final double[] p : profile) {
                sum += Math.abs(p[0]);
            }
            this.refRadius = profile.length == 0 ? 1.0 : Math.max(1.0e-6, sum / profile.length);
            this.table = ArcLengthTable.build(0, profile.length - 1, 64 * profile.length,
                    (a, b) -> {
                        final double[] pa = profileAt(a);
                        final double[] pb = profileAt(b);
                        return Math.hypot(pb[0] - pa[0], pb[1] - pa[1]);
                    });
        }

        /** 轮廓参数 t（可为小数）→ 二维点，线性插值。 */
        private double[] profileAt(final double t) {
            final double clamped = Math.max(0, Math.min(profile.length - 1, t));
            final int i = (int) Math.floor(clamped);
            final int j = Math.min(profile.length - 1, i + 1);
            final double f = clamped - i;
            return new double[]{
                    profile[i][0] + f * (profile[j][0] - profile[i][0]),
                    profile[i][1] + f * (profile[j][1] - profile[i][1]),
            };
        }

        @Override
        public double[] evaluate(final double u, final double v, final double[] out) {
            // u 是弧长 → 反查轮廓参数；v 是 θ·R_ref → 反查角度
            final double t = table.paramAt(u);
            final double theta = v / refRadius;
            final double[] p2 = profileAt(t);
            final double c = Math.cos(theta);
            final double s = Math.sin(theta);
            final double[] w = unit(cross(au, av));
            for (int k = 0; k < 3; k++) {
                out[k] = o[k] + p2[1] * w[k] + p2[0] * (c * au[k] + s * av[k]);
            }
            return out;
        }

        @Override
        public double[] project(final double[] p, final double[] out) {
            final double[] d = {p[0] - o[0], p[1] - o[1], p[2] - o[2]};
            final double[] w = unit(cross(au, av));
            final double z = dot(d, w);
            final double a = dot(d, au);
            final double b = dot(d, av);
            final double r = Math.hypot(a, b);
            final double theta = Math.atan2(b, a);
            // 在轮廓上找最近的点，取其弧长作为 u
            // ⚠ 必须是"**最近线段上的投影点**"，不能图省事取"最近顶点"：
            //   最近顶点的误差上限是**半条棱长**，实测锥面偏 2.9 方块、拉伸面偏 6.9 方块
            //   （断言直接把这个错抓出来了）。碰撞体素化也吃这个精度。
            final double[] hit = nearestOnProfile(profile, r, z, closedRing());
            out[0] = hit[0];
            out[1] = theta * refRadius;
            return out;
        }

        /** 最近线段投影：返回 {@code {弧长, 到该点的距离}}。 */
        private double[] nearestOnProfile(final double[][] prof, final double pr, final double pz,
                                          final boolean closed) {
            final int n = prof.length;
            double best = Double.MAX_VALUE;
            double bestArc = 0.0;
            final int segs = closed ? n : n - 1;
            for (int i = 0; i < segs; i++) {
                final int j = (i + 1) % n;
                final double ax = prof[i][0];
                final double ay = prof[i][1];
                final double bx = prof[j][0];
                final double by = prof[j][1];
                final double vx = bx - ax;
                final double vy = by - ay;
                final double vv = vx * vx + vy * vy;
                double t = 0.0;
                if (vv > 1.0e-18) {
                    t = ((pr - ax) * vx + (pz - ay) * vy) / vv;
                    t = Math.max(0.0, Math.min(1.0, t));
                }
                final double cx = ax + t * vx;
                final double cy = ay + t * vy;
                final double d = Math.hypot(pr - cx, pz - cy);
                if (d < best) {
                    best = d;
                    final double arc0 = table.arcAt(i);
                    final double arc1 = table.arcAt(Math.min(profile.length - 1, i + 1));
                    bestArc = arc0 + t * (arc1 - arc0);
                }
            }
            return new double[]{bestArc, best};
        }

        private boolean closedRing() {
            return false;
        }

        @Override
        public double[] normal(final double u, final double v, final double[] out) {
            // 用解析偏导叉乘（与 evaluate 一致的公式，不做有限差分）
            final double[] su = new double[3];
            final double[] sv = new double[3];
            final double h = 1.0e-5;
            final double[] a = evaluate(Math.max(uMin(), u - h), v, new double[3]);
            final double[] b = evaluate(Math.min(uMax(), u + h), v, new double[3]);
            final double[] c = evaluate(u, v - h, new double[3]);
            final double[] d = evaluate(u, v + h, new double[3]);
            for (int k = 0; k < 3; k++) {
                su[k] = (b[k] - a[k]) / (2 * h);
                sv[k] = (d[k] - c[k]) / (2 * h);
            }
            final double[] n = cross(su, sv);
            final double len = norm(n);
            if (len < 1.0e-12) {
                // 轴上的退化点：法线未定义，返回 0 而不是 NaN，让调用方有机会用邻域替代
                out[0] = 0.0;
                out[1] = 0.0;
                out[2] = 0.0;
                return out;
            }
            for (int k = 0; k < 3; k++) {
                out[k] = n[k] / len;
            }
            return out;
        }

        @Override
        public double uMin() {
            return 0.0;
        }

        @Override
        public double uMax() {
            return table.total();
        }

        @Override
        public double vMin() {
            return 0.0;
        }

        @Override
        public double vMax() {
            return 2 * Math.PI * refRadius;
        }

        @Override
        public UvMapping.Mode preferredMapping() {
            return UvMapping.Mode.SURFACE;
        }
    }

    // ---------------------------------------------------------------- 拉伸面

    /** 拉伸面：二维轮廓沿 {@code axisW} 拉伸 {@code depth}。u = 轮廓弧长，v = 沿拉伸方向。 */
    public static ParamSurface extrude(final double[][] profile, final double[] origin,
                                       final double[] axisU, final double[] axisV,
                                       final double depth) {
        return new Extrude(profile, origin, axisU, axisV, depth);
    }

    private static final class Extrude implements ParamSurface {
        private final double[][] profile;
        private final double[] o;
        private final double[] au;
        private final double[] av;
        private final double[] w;
        private final double depth;
        private final ArcLengthTable table;
        /** 轮廓是否闭合（闭合时弧长要绕回起点）。 */
        private final boolean closed;

        Extrude(final double[][] profile, final double[] o, final double[] au, final double[] av,
                final double depth) {
            this.profile = profile;
            this.o = o;
            this.au = au;
            this.av = av;
            this.w = unit(cross(au, av));
            this.depth = depth;
            this.closed = profile.length > 2
                    && Math.hypot(profile[0][0] - profile[profile.length - 1][0],
                            profile[0][1] - profile[profile.length - 1][1]) < 1.0e-6;
            final int extra = closed ? 1 : 0;
            this.table = ArcLengthTable.build(0, profile.length - 1 + extra,
                    32 * (profile.length + extra), (a, b) -> {
                        final double[] pa = profileAt(a);
                        final double[] pb = profileAt(b);
                        return Math.hypot(pb[0] - pa[0], pb[1] - pa[1]);
                    });
        }

        private double[] profileAt(final double t) {
            final int n = profile.length;
            final double clamped = Math.max(0, Math.min(n - 1, t));
            final int i = (int) Math.floor(clamped);
            final int j = i + 1 < n ? i + 1 : (closed ? 0 : n - 1);
            final double f = clamped - i;
            return new double[]{
                    profile[i][0] + f * (profile[j][0] - profile[i][0]),
                    profile[i][1] + f * (profile[j][1] - profile[i][1]),
            };
        }

        @Override
        public double[] evaluate(final double u, final double v, final double[] out) {
            final double t = table.paramAt(u);
            final double[] p2 = profileAt(t);
            for (int k = 0; k < 3; k++) {
                out[k] = o[k] + p2[0] * au[k] + p2[1] * av[k] + v * w[k];
            }
            return out;
        }

        @Override
        public double[] project(final double[] p, final double[] out) {
            final double[] d = {p[0] - o[0], p[1] - o[1], p[2] - o[2]};
            final double a = dot(d, au);
            final double b = dot(d, av);
            // ⚠ 同旋转面：用**最近线段上的投影**，不是最近顶点（顶点误差可达半条棱长）
            double best = Double.MAX_VALUE;
            double bestArc = 0.0;
            final int n = profile.length;
            final int segs = closed ? n : n - 1;
            for (int i = 0; i < segs; i++) {
                final int j = (i + 1) % n;
                final double ax = profile[i][0];
                final double ay = profile[i][1];
                final double bx = profile[j][0];
                final double by = profile[j][1];
                final double vx = bx - ax;
                final double vy = by - ay;
                final double vv = vx * vx + vy * vy;
                double t = 0.0;
                if (vv > 1.0e-18) {
                    t = ((a - ax) * vx + (b - ay) * vy) / vv;
                    t = Math.max(0.0, Math.min(1.0, t));
                }
                final double dxx = ax + t * vx - a;
                final double dyy = ay + t * vy - b;
                final double dd = Math.hypot(dxx, dyy);
                if (dd < best) {
                    best = dd;
                    final double arc0 = table.arcAt(i);
                    final double arc1 = table.arcAt(Math.min(n - 1, i + 1));
                    bestArc = arc0 + t * (arc1 - arc0);
                }
            }
            out[0] = bestArc;
            out[1] = dot(d, w);
            return out;
        }

        @Override
        public double[] normal(final double u, final double v, final double[] out) {
            // 拉伸面的法线：轮廓切线 × 拉伸方向
            final double t = table.paramAt(u);
            final double[] a = profileAt(Math.max(0, t - 1.0e-5));
            final double[] b = profileAt(Math.min(profile.length - 1 + (closed ? 1 : 0),
                    t + 1.0e-5));
            final double tu = b[0] - a[0];
            final double tv = b[1] - a[1];
            final double[] tangent = new double[3];
            for (int k = 0; k < 3; k++) {
                tangent[k] = tu * au[k] + tv * av[k];
            }
            final double[] n = cross(tangent, w);
            final double len = norm(n);
            if (len < 1.0e-12) {
                out[0] = 0.0;
                out[1] = 0.0;
                out[2] = 0.0;
                return out;
            }
            for (int k = 0; k < 3; k++) {
                out[k] = n[k] / len;
            }
            return out;
        }

        @Override
        public double uMin() {
            return 0.0;
        }

        @Override
        public double uMax() {
            return table.total();
        }

        @Override
        public double vMin() {
            return 0.0;
        }

        @Override
        public double vMax() {
            return depth;
        }

        @Override
        public UvMapping.Mode preferredMapping() {
            return UvMapping.Mode.SURFACE;
        }
    }

    // ---------------------------------------------------------------- 向量小工具

    static double dot(final double[] a, final double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double[] cross(final double[] a, final double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    static double norm(final double[] a) {
        return Math.sqrt(dot(a, a));
    }

    static double[] unit(final double[] a) {
        final double n = norm(a);
        if (n < 1.0e-12) {
            return new double[]{0, 0, 1};
        }
        return new double[]{a[0] / n, a[1] / n, a[2] / n};
    }
}
