package com.ovo.sablestopnow.sketch.nurbs;

/**
 * **扫掠实体**：二维轮廓 + 扫掠规则（拉伸 / 回转）—— 我们全部实体的统一表达。
 *
 * <h2>为什么这个抽象是"直接体素化"的关键</h2>
 * 用户的要求是"从 NURBS 曲面**直接**得到 1/16 碰撞体素，不要先三角化再体素化"。
 * 要做到这一点，就必须有一个**不依赖离散网格的内外判定**。
 *
 * <p>我们现在的实体全部形如"**二维闭合轮廓（可带孔）** 沿某个方向扫出去"：
 * <ul>
 *   <li><b>拉伸</b>：轮廓沿平面法线平移一段（深度区间 {@code [d0,d1]}）；</li>
 *   <li><b>回转</b>：轮廓绕平面内一条轴旋转一段角度。</li>
 * </ul>
 * 于是三维点的内外判定可以**精确**化成两步：
 * <pre>
 *   3D 点  →（逆映射）→  (轮廓平面内的二维点 a, 扫掠参数 b)  →  在轮廓内 && b 在区间内
 * </pre>
 * 逆映射对这两种扫掠都是**闭式**的（拉伸是投影，回转是取半径与轴向坐标）。
 *
 * <h2>与"射线法 / 绕数"的取舍</h2>
 * 通用的闭合 NURBS 体做内外判定要用**射线求交 + 计数**（奇偶规则）。那条路：
 * <ul>
 *   <li>要解"射线与 NURBS 曲面求交"——本身是个迭代求根问题；</li>
 *   <li>射线擦过切点/边界时**奇偶会抖**，需要容差与重试；</li>
 *   <li>我们的实体根本不需要它（轮廓在二维上已经是良定义的区域）。</li>
 * </ul>
 * 所以这里选**解析逆映射**：没有迭代、没有奇偶抖动、还能给出精确的"到边界的距离"
 * （这对"边界格怎么定"直接有用，见 {@link Voxelizer}）。
 *
 * <p>⚠ 这是**有意的范围限制**：本类只覆盖"轮廓 + 扫掠"这一类实体（也就是我们现有的全部实体）。
 * 将来若引入真正的自由曲面（P5），需要在这之上再加通用的射线法判定。
 */
public final class SweptSolid {

    /** 扫掠方式。 */
    public enum Kind {
        /** 沿平面法线平移。 */
        EXTRUDE,
        /** 绕平面内一条轴旋转。 */
        REVOLVE,
    }

    private final Kind kind;
    /** 轮廓平面内的二维环：第 0 个是外环，其余是孔。每个环是点列（闭合，首尾不重复）。 */
    private final double[][][] rings;
    /** 轮廓平面的原点。 */
    private final double[] origin;
    /** 轮廓平面的两个正交基（单位）。 */
    private final double[] axisU;
    private final double[] axisV;
    /** 轮廓平面的法线（= 拉伸方向），单位。 */
    private final double[] planeNormal;
    /** 拉伸：深度区间；回转：角度区间（弧度）。 */
    private final double[] range;
    /** 回转用的轴（世界方向，单位）：位于轮廓平面内，过 origin。 */
    private final double[] revolveAxis;

    private SweptSolid(final Kind kind, final double[][][] rings, final double[] origin,
                       final double[] axisU, final double[] axisV, final double[] planeNormal,
                       final double[] range, final double[] revolveAxis) {
        this.kind = kind;
        this.rings = rings;
        this.origin = origin;
        this.axisU = axisU;
        this.axisV = axisV;
        this.planeNormal = planeNormal;
        this.range = range;
        this.revolveAxis = revolveAxis;
    }

    /** 拉伸实体。 */
    public static SweptSolid extrude(final double[][][] rings, final double[] origin,
                                     final double[] axisU, final double[] axisV,
                                     final double depth0, final double depth1) {
        final double[] n = cross(axisU, axisV);
        final double len = norm(n);
        final double[] nn = {n[0] / len, n[1] / len, n[2] / len};
        return new SweptSolid(Kind.EXTRUDE, rings, origin, axisU, axisV, nn,
                new double[]{depth0, depth1}, null);
    }

    /** 回转实体（角度用弧度，{@code angle0} 可正可负）。 */
    public static SweptSolid revolve(final double[][][] rings, final double[] origin,
                                     final double[] axisU, final double[] axisV,
                                     final double[] revolveAxis, final double angle0,
                                     final double angle1) {
        final double[] n = cross(axisU, axisV);
        final double len = norm(n);
        final double[] nn = {n[0] / len, n[1] / len, n[2] / len};
        final double al = norm(revolveAxis);
        final double[] ra = {revolveAxis[0] / al, revolveAxis[1] / al, revolveAxis[2] / al};
        return new SweptSolid(Kind.REVOLVE, rings, origin, axisU, axisV, nn,
                new double[]{angle0, angle1}, ra);
    }

    public Kind kind() {
        return kind;
    }

    /**
     * 三维点的**内外判定**（解析）。
     *
     * <p>先逆映射到（轮廓二维点，扫掠参数），再分别判定。
     */
    public boolean inside(final double[] p) {
        final double[] d = {p[0] - origin[0], p[1] - origin[1], p[2] - origin[2]};
        final double a = dot(d, axisU);
        final double b = dot(d, axisV);
        final double c = dot(d, planeNormal);
        if (kind == Kind.EXTRUDE) {
            if (c < range[0] || c > range[1]) {
                return false;
            }
            return insideRings(a, b);
        }
        // 回转：轮廓平面内的"径向"是沿 axisU 的分量（revolveAxis 取 axisV 方向时也成立，
        // 因为轮廓二维点本身就定义在这个平面里），轴向分量沿 revolveAxis 分解。
        final double axial = dot(d, revolveAxis);
        // 半径 = 到回转轴的垂距（在轮廓平面内取与 revolveAxis 垂直的分量）
        final double alongRevolve = dot(d, revolveAxis);
        final double radialLen = Math.sqrt(Math.max(0.0,
                dot(d, d) - alongRevolve * alongRevolve));
        // 角度：以 revolveAxis 为 z、axisU 为 0 方向
        final double uComp = dot(d, axisU);
        final double nComp = dot(d, planeNormal);
        final double phi = Math.atan2(nComp, uComp);
        if (!angleInRange(phi)) {
            return false;
        }
        // 轮廓点：径向坐标 = 到轴的距离，轴向坐标 = 沿轴的分量
        // ⚠ 符号：轮廓的 u 分量可能是负的（轴两侧），这里用带符号的径向分量
        final double sign = Math.signum(uComp) == 0.0 ? 1.0 : Math.signum(uComp);
        final double rSigned = sign * radialLen;
        return insideRings(rSigned, axial);
        // 说明：axial 与 alongRevolve 相同，保留两个名字是为了让公式与文字对应
    }

    private boolean angleInRange(final double phi) {
        final double a0 = range[0];
        final double a1 = range[1];
        // 归一化到与 [a0,a1] 同区间
        double x = phi;
        while (x < Math.min(a0, a1) - Math.PI) {
            x += 2 * Math.PI;
        }
        while (x > Math.max(a0, a1) + Math.PI) {
            x -= 2 * Math.PI;
        }
        return x >= Math.min(a0, a1) && x <= Math.max(a0, a1);
    }

    /** 二维点是否在轮廓内（外环内且不在任何孔内）。 */
    public boolean insideRings(final double a, final double b) {
        if (!pointInRing(rings[0], a, b)) {
            return false;
        }
        for (int i = 1; i < rings.length; i++) {
            if (pointInRing(rings[i], a, b)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 奇偶规则（crossing number）判定二维点是否在环内。
     *
     * <p>⚠ 用**严格半开**的边判据（{@code y0 <= b < y1} 这类），否则顶点处会被算两次；
     * 这是射线法最经典的重复计数 bug。
     */
    public static boolean pointInRing(final double[][] ring, final double a, final double b) {
        boolean in = false;
        final int n = ring.length;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            final double ai = ring[i][0];
            final double bi = ring[i][1];
            final double aj = ring[j][0];
            final double bj = ring[j][1];
            if ((bi > b) != (bj > b)) {
                final double t = (b - bi) / (bj - bi);
                if (a < ai + t * (aj - ai)) {
                    in = !in;
                }
            }
        }
        return in;
    }

    /**
     * **到边界的带符号距离**（正的表示在内部）。回转/拉伸都支持。
     *
     * <p>为什么需要它：体素化的**边界格**必须有一个"这个格有多深"的度量，
     * 否则只能靠格心 0/1 判定 —— 那正是以前"体素盒探出边界"的来源。
     * 有了它就能实现"保守 vs 精确"的**可控**取舍（见 {@link Voxelizer#Criterion}）。
     *
     * <p>实现用二维轮廓到环的距离，再与扫掠方向的边界距离取最小值。
     */
    public double signedDistance(final double[] p) {
        final double[] d = {p[0] - origin[0], p[1] - origin[1], p[2] - origin[2]};
        final double a = dot(d, axisU);
        final double b = dot(d, axisV);
        final double c = dot(d, planeNormal);
        final boolean inRings = insideRings(a, b);
        double dist2 = distanceToRings(rings, a, b);
        if (!inRings) {
            dist2 = -dist2;
        }
        // 扫掠方向的边界距离
        double sweepDist;
        if (kind == Kind.EXTRUDE) {
            sweepDist = Math.min(c - range[0], range[1] - c);
        } else {
            final double along = dot(d, revolveAxis);
            final double uComp = dot(d, axisU);
            final double nComp = dot(d, planeNormal);
            final double phi = Math.atan2(nComp, uComp);
            final double a0 = Math.min(range[0], range[1]);
            final double a1 = Math.max(range[0], range[1]);
            double x = phi;
            while (x < a0 - Math.PI) {
                x += 2 * Math.PI;
            }
            while (x > a1 + Math.PI) {
                x -= 2 * Math.PI;
            }
            // 角向距离换算成弧长（用当前半径）
            final double radial = Math.sqrt(Math.max(0.0, dot(d, d) - along * along));
            sweepDist = Math.min(x - a0, a1 - x) * Math.max(radial, 1.0e-9);
        }
        return Math.min(dist2, sweepDist);
    }

    /** 二维点到所有环的最短距离。 */
    private static double distanceToRings(final double[][][] rings, final double a, final double b) {
        double best = Double.MAX_VALUE;
        for (final double[][] ring : rings) {
            final int n = ring.length;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                best = Math.min(best, pointSegmentDistance(a, b, ring[j][0], ring[j][1],
                        ring[i][0], ring[i][1]));
            }
        }
        return best == Double.MAX_VALUE ? 0.0 : best;
    }

    private static double pointSegmentDistance(final double px, final double py,
                                               final double ax, final double ay,
                                               final double bx, final double by) {
        final double vx = bx - ax;
        final double vy = by - ay;
        final double wx = px - ax;
        final double wy = py - ay;
        final double vv = vx * vx + vy * vy;
        if (vv < 1.0e-18) {
            return Math.hypot(wx, wy);
        }
        double t = (wx * vx + wy * vy) / vv;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(wx - t * vx, wy - t * vy);
    }

    // ---------------------------------------------------------------- 向量小工具

    private static double dot(final double[] a, final double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(final double[] a, final double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static double norm(final double[] a) {
        return Math.sqrt(dot(a, a));
    }
}
