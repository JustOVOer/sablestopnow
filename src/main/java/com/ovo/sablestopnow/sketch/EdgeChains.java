package com.ovo.sablestopnow.sketch;

import java.util.ArrayList;
import java.util.List;

/**
 * **曲线棱**：把一条弧/样条的离散步（"碎棱"）按端点首尾相接合成一条链 ✓
 *
 * <h2>为什么需要它</h2>
 * 模型的面轮廓里，圆弧被离散成很多短段（实测 0.100~0.126 格）。用户点中其中任意一段，
 * 想选中的是**整条弧**，而不是 0.1 格的小段：
 * <ul>
 *   <li>{@link ReferenceOutline#curveFlags(List)} 已经能把"曲线碎段"与"真直边"分开</li>
 *   <li>本类只在**曲线碎段**之间生长，并且在**平滑连接**（转角 &lt; {@code TURN_MAX}）处才接上
 *       ⇒ 两条弧在尖角相接时不会被错误地并成一条</li>
 * </ul>
 *
 * <h2>坐标系</h2> 全程用实体**局部坐标**的线段（{@code {x1,y1,z1,x2,y2,z2}}），与
 * {@code Features}/{@link Solid.Shape.EdgeRef} 一致；纯计算、无游戏依赖 ✓
 */
public final class EdgeChains {

    private EdgeChains() {
    }

    /** 一条链最多拼多少段（防御性上限）✓ */
    public static final int MAX_CHAIN = 256;

    private static final double JOIN_TOL = ReferenceOutline.JOIN_TOL;
    private static final double TURN_MAX = ReferenceOutline.TURN_MAX;

    // ---- 诊断 ----
    public static int lastChain;

    /**
     * 从第 {@code idx} 段出发，把平滑相连的曲线碎段聚成一条链 ✓
     *
     * @param segs  面轮廓的所有线段
     * @param curve {@link ReferenceOutline#curveFlags(List)} 的结果（同一份判定）
     * @param idx   被点中的段
     * @return **已定向**的段（相邻段首尾相接，可直接当折线画/逐段建楔块）；
     *         点中的不是曲线碎段时只返回它自己
     */
    public static List<double[]> chainAt(final List<double[]> segs, final boolean[] curve,
                                         final int idx) {
        final List<double[]> out = new ArrayList<>();
        lastChain = 0;
        if (segs == null || idx < 0 || idx >= segs.size()) {
            return out;
        }
        if (curve == null || idx >= curve.length || !curve[idx]) {
            out.add(copy(segs.get(idx)));
            lastChain = 1;
            return out;
        }
        final int n = segs.size();
        final boolean[] used = new boolean[n];
        used[idx] = true;
        out.add(copy(segs.get(idx)));
        // 向前（当前链的**终点**继续接）
        grow(segs, curve, used, out, true);
        // 向后（当前链的**起点**往前接）
        grow(segs, curve, used, out, false);
        lastChain = out.size();
        return out;
    }

    /** 链首尾相接的折线点 ✓（段已由 {@link #chainAt} 定向） */
    public static List<double[]> points(final List<double[]> chain) {
        final List<double[]> pts = new ArrayList<>();
        if (chain == null) {
            return pts;
        }
        for (final double[] s : chain) {
            if (s == null || s.length < 6) {
                continue;
            }
            if (pts.isEmpty()) {
                pts.add(new double[] {s[0], s[1], s[2]});
            }
            pts.add(new double[] {s[3], s[4], s[5]});
        }
        return pts;
    }

    /**
     * 链所在平面的法线 ✓（Newell 法；曲线棱在挤出体上一定是平面链）
     *
     * @param fallback 参考方向（命中面的法线）：用它的正负号把结果定向到同一侧
     * @return 单位法线；退化时返回 fallback（或 +Y）
     */
    public static org.joml.Vector3d planeNormal(final List<double[]> chain,
                                                final org.joml.Vector3d fallback) {
        final org.joml.Vector3d fb = fallback == null
                ? new org.joml.Vector3d(0, 1, 0) : new org.joml.Vector3d(fallback);
        final List<double[]> pts = points(chain);
        if (pts.size() < 3) {
            return fb;
        }
        double nx = 0.0;
        double ny = 0.0;
        double nz = 0.0;
        for (int i = 0; i < pts.size(); i++) {
            final double[] p = pts.get(i);
            final double[] q = pts.get((i + 1) % pts.size());
            nx += (p[1] - q[1]) * (p[2] + q[2]);
            ny += (p[2] - q[2]) * (p[0] + q[0]);
            nz += (p[0] - q[0]) * (p[1] + q[1]);
        }
        final double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(l > 1.0e-12)) {
            return fb;
        }
        final org.joml.Vector3d n = new org.joml.Vector3d(nx / l, ny / l, nz / l);
        if (n.dot(fb) < 0.0) {
            n.negate();
        }
        return n;
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /** 从链的首端/末端继续接曲线碎段 ✓ */
    private static void grow(final List<double[]> segs, final boolean[] curve,
                             final boolean[] used, final List<double[]> out,
                             final boolean forward) {
        while (out.size() < MAX_CHAIN) {
            final double[] ref = forward ? out.get(out.size() - 1) : out.get(0);
            // 当前链的"待接点"：向前 = 最后一段的终点；向后 = 第一段的起点
            final int refEnd = forward ? 1 : 0;
            final double[] join = forward
                    ? new double[] {ref[3], ref[4], ref[5]}
                    : new double[] {ref[0], ref[1], ref[2]};
            int best = -1;
            int bestEnd = -1;
            double bestSin = Double.MAX_VALUE;
            for (int j = 0; j < segs.size(); j++) {
                if (used[j] || j >= curve.length || !curve[j]) {
                    continue;
                }
                final double[] s = segs.get(j);
                if (s == null || s.length < 6) {
                    continue;
                }
                for (int end = 0; end < 2; end++) {
                    final int k = end == 0 ? 0 : 3;
                    if (Math.abs(s[k] - join[0]) > JOIN_TOL
                            || Math.abs(s[k + 1] - join[1]) > JOIN_TOL
                            || Math.abs(s[k + 2] - join[2]) > JOIN_TOL) {
                        continue;
                    }
                    // 转角：参考段在待接端，候选段在它的连接端
                    final double sin = ReferenceOutline.sinBetween(ref, refEnd, s, end);
                    if (sin <= TURN_MAX && sin < bestSin) {
                        bestSin = sin;
                        best = j;
                        bestEnd = end;
                    }
                }
            }
            if (best < 0) {
                return;
            }
            used[best] = true;
            // 定向：让候选段在"待接点"与链相连 ⇒ 向前时它应从待接点出发；向后时它应结束于待接点
            final double[] pick = copy(segs.get(best));
            final boolean reversed = forward ? bestEnd == 1 : bestEnd == 0;
            final double[] oriented = reversed ? reverse(pick) : pick;
            if (forward) {
                out.add(oriented);
            } else {
                out.add(0, oriented);
            }
        }
    }

    private static double[] copy(final double[] s) {
        return new double[] {s[0], s[1], s[2], s[3], s[4], s[5]};
    }

    private static double[] reverse(final double[] s) {
        return new double[] {s[3], s[4], s[5], s[0], s[1], s[2]};
    }
}
