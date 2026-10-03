package com.ovo.sablestopnow.sketch.param;

import java.util.HashMap;
import java.util.Map;

/**
 * **全项目唯一的 UV 出口**。
 *
 * <p>渲染、碰撞、任何需要贴图坐标的地方，都只调 {@link #uv}。这样：
 * <ul>
 *   <li><b>扩展性</b>：曲面自己声明偏好模式（{@link ParamSurface#preferredMapping()}），
 *       渲染里**没有任何按形状分的分支**；</li>
 *   <li><b>不易有 bug</b>：UV 只有一处产生，且默认模式（{@code SURFACE}）直接取曲面的
 *       **自然参数** —— 那些参数是"全局一致的弧长量"，不会出现"每条棱各算一套"的错位。</li>
 * </ul>
 *
 * <h2>和上一版（图集路线）的根本区别</h2>
 * 图集路线必须把 UV 折进子矩形，于是要么在顶点阶段做 {@code frac}（**错**：三角形内部
 * 会在两个贴图周期之间插值 → 垃圾），要么切割几何（缝还在、顶点爆炸）。
 * 现在走的是 **REPEAT 独立纹理**：UV 可以自由超过 1，**回绕由 GL 在采样时完成**，
 * 所以这里只需"除以每格方块数"，**没有 frac、没有子矩形、没有切割**。
 */
public final class UvMapping {

    private UvMapping() {
    }

    /** 映射模式。曲面自己声明偏好哪一种，见 {@link ParamSurface#preferredMapping()}。 */
    public enum Mode {
        /** **默认**：直接用曲面的自然参数（已是方块量纲的弧长）→ 尺度天然统一。 */
        SURFACE,
        /** 沿平面投影（面内等距，适合平面/直壁）。 */
        PLANAR,
        /** 圆柱：绕轴角度弧长 × 轴向距离。 */
        CYLINDRICAL,
        /** 球面：方位角弧长 × 极角弧长（极点必然有奇点，是拓扑限制）。 */
        SPHERICAL,
        /** 盒式/三平面：按解析法线主轴选投影平面（兜底，主轴交界有硬缝）。 */
        BOX,
    }

    /** 复用输出数组，避免每帧在热路径上分配。 */
    private static final ThreadLocal<double[]> SCRATCH = ThreadLocal.withInitial(() -> new double[3]);

    /**
     * **唯一入口**：算出一个顶点的贴图坐标（方块单位，可超过 1 —— REPEAT 纹理直接平铺）。
     *
     * @param mode         映射模式（通常传 {@code surface.preferredMapping()}）
     * @param surface      曲面（{@code SURFACE} 模式要用它的参数）
     * @param frame        坐标架（{@code PLANAR/CYLINDRICAL/SPHERICAL/BOX} 要用）
     * @param u            曲面参数 u
     * @param v            曲面参数 v
     * @param point        该参数处的三维点
     * @param normal       该参数处的**解析法线**（{@code BOX} 要用；传 null 则退回 u/v）
     * @param blocksPerTile 一张贴图铺几个方块
     * @param out          输出（长度 ≥ 2）
     */
    public static double[] uv(final Mode mode, final ParamSurface surface, final ParamSurface.Frame frame,
                              final double u, final double v, final double[] point,
                              final double[] normal, final double blocksPerTile,
                              final double[] out) {
        final double per = blocksPerTile <= 0.0 || !Double.isFinite(blocksPerTile)
                ? 1.0 : blocksPerTile;
        switch (mode) {
            case SURFACE -> {
                // 自然参数本来就是方块量纲的弧长 → 只差一个尺度（**唯一实现**在 surfaceUv）
                surfaceUv(u, v, per, out);
            }
            case PLANAR -> {
                final double[] d = sub(point, frame.origin());
                out[0] = dot(d, frame.axisU()) / per;
                out[1] = dot(d, frame.axisV()) / per;
            }
            case CYLINDRICAL -> {
                final double[] d = sub(point, frame.origin());
                final double a = dot(d, frame.axisU());
                final double b = dot(d, frame.axisV());
                final double radius = Math.max(1.0e-9, Math.hypot(a, b));
                out[0] = Math.atan2(b, a) * radius / per;
                out[1] = dot(d, frame.axisW()) / per;
            }
            case SPHERICAL -> {
                final double[] d = sub(point, frame.origin());
                final double r = Math.max(1.0e-9, norm(d));
                final double polar = Math.acos(Math.max(-1.0, Math.min(1.0, d[2] / r)));
                final double azim = Math.atan2(d[1], d[0]);
                out[0] = azim * Math.sin(polar) * r / per;
                out[1] = polar * r / per;
            }
            case BOX -> {
                // 用**解析法线**的主轴（不是三角面片的几何法线）——避免
                // "同一个四边形两个三角形选了不同主轴"那种错位（我踩过）
                final double[] d = sub(point, frame.origin());
                final double[] n = normal == null ? new double[]{0, 0, 1} : normal;
                final double ax = Math.abs(dot(n, frame.axisU()));
                final double ay = Math.abs(dot(n, frame.axisV()));
                final double az = Math.abs(dot(n, frame.axisW()));
                if (az >= ax && az >= ay) {
                    out[0] = dot(d, frame.axisU()) / per;
                    out[1] = dot(d, frame.axisV()) / per;
                } else if (ax >= ay) {
                    out[0] = dot(d, frame.axisV()) / per;
                    out[1] = dot(d, frame.axisW()) / per;
                } else {
                    out[0] = dot(d, frame.axisU()) / per;
                    out[1] = dot(d, frame.axisW()) / per;
                }
            }
            default -> throw new IllegalStateException("未知映射模式: " + mode);
        }
        return out;
    }

    /** 便捷重载：用曲面自己偏好的模式。 */
    public static double[] uv(final ParamSurface surface, final ParamSurface.Frame frame,
                              final double u, final double v, final double[] point,
                              final double[] normal, final double blocksPerTile,
                              final double[] out) {
        return uv(surface.preferredMapping(), surface, frame, u, v, point, normal,
                blocksPerTile, out);
    }

    /**
     * **网格路径的换算**：只做"自然参数 → 贴图坐标"。
     *
     * <p>网格生成时（{@code Revolve.mesh} / {@code Extrude.mesh}）就已经把顶点的
     * {@code (u,v)} 写成**方块量纲的弧长**了，所以这里只差一个尺度。
     * 单独留这个函数，是为了让"SURFACE 模式的公式"**全项目只有一份实现**
     * （{@code SolidUv} 调它，{@link #uv} 也调它）—— 唯一定义，改一处即全局生效。
     */
    public static void surfaceUv(final double u, final double v, final double blocksPerTile,
                                 final double[] out) {
        final double per = blocksPerTile <= 0.0 || !Double.isFinite(blocksPerTile)
                ? 1.0 : blocksPerTile;
        out[0] = u / per;
        out[1] = v / per;
    }

    // ---------------------------------------------------------------- 畸变度量（给断言用）

    /**
     * **尺度不均匀度**：在曲面上采样，比较"曲面真实距离"与"贴图坐标距离"的比值，
     * 返回 {@code max/min}（1.0 = 处处一样大）。
     *
     * <p>这是把"贴图会不会被拉变形"从口头争论变成数字的手段。断言里用它来钉住
     * "旋转面的参数改成全局参考半径之后，不均匀度显著下降"。
     */
    public static double distortion(final ParamSurface s, final Mode mode,
                                    final ParamSurface.Frame frame, final double per,
                                    final int samples) {
        final int n = Math.max(8, samples);
        final double du = (s.uMax() - s.uMin()) / n;
        final double dv = (s.vMax() - s.vMin()) / n;
        final java.util.List<Double> scales = new java.util.ArrayList<>();
        final double[] ua = new double[2];
        final double[] ub = new double[2];
        final double[] nrm = new double[3];
        boolean degenerate = false;
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j <= n; j++) {
                final double u = s.uMin() + (s.uMax() - s.uMin()) * i / n;
                final double v = s.vMin() + (s.vMax() - s.vMin()) * j / n;
                s.normal(u, v, nrm);
                final double u2 = Math.min(s.uMax(), u + du);
                final double v2 = Math.min(s.vMax(), v + dv);
                if (u2 > u) {
                    final double[] p0 = s.evaluate(u, v, new double[3]);
                    final double[] p1 = s.evaluate(u2, v, new double[3]);
                    uv(mode, s, frame, u, v, p0, nrm, per, ua);
                    uv(mode, s, frame, u2, v, p1, nrm, per, ub);
                    final double sd = dist(p0, p1);
                    final double td = Math.hypot(ua[0] - ub[0], ua[1] - ub[1]);
                    if (sd > 1.0e-9) {
                        if (td <= 1.0e-12) {
                            degenerate = true;
                        } else {
                            scales.add(sd / td);
                        }
                    }
                }
                if (v2 > v) {
                    final double[] p0 = s.evaluate(u, v, new double[3]);
                    final double[] p1 = s.evaluate(u, v2, new double[3]);
                    uv(mode, s, frame, u, v, p0, nrm, per, ua);
                    uv(mode, s, frame, u, v2, p1, nrm, per, ub);
                    final double sd = dist(p0, p1);
                    final double td = Math.hypot(ua[0] - ub[0], ua[1] - ub[1]);
                    if (sd > 1.0e-9) {
                        if (td <= 1.0e-12) {
                            degenerate = true;
                        } else {
                            scales.add(sd / td);
                        }
                    }
                }
            }
        }
        if (degenerate || scales.isEmpty()) {
            return Double.POSITIVE_INFINITY;
        }
        // 接缝（角度 ±π 处的跳变）按**离群值**剔除：它是拓扑限制，不是畸变
        final java.util.List<Double> sorted = new java.util.ArrayList<>(scales);
        java.util.Collections.sort(sorted);
        final double median = sorted.get(sorted.size() / 2);
        double min = Double.MAX_VALUE;
        double max = 0.0;
        for (final double sc : scales) {
            if (median > 0.0 && (sc > median * 10.0 || sc < median / 10.0)) {
                continue;
            }
            min = Math.min(min, sc);
            max = Math.max(max, sc);
        }
        return min == Double.MAX_VALUE || min <= 0.0 ? Double.POSITIVE_INFINITY : max / min;
    }

    private static double[] sub(final double[] a, final double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double dot(final double[] a, final double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double norm(final double[] a) {
        return Math.sqrt(dot(a, a));
    }

    private static double dist(final double[] a, final double[] b) {
        return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1])
                + (a[2] - b[2]) * (a[2] - b[2]));
    }

    /** 缓存表（若将来需要在同一张面上反复求参数→贴图坐标的换算，挂在这里）。 */
    private static final Map<String, Object> CACHE = new HashMap<>();
}
