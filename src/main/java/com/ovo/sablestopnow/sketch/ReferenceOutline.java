package com.ovo.sablestopnow.sketch;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 从**网格边**里重建"真正的直线"参考线 ✓（bug 11 的系统性修法）。
 *
 * <h2>为什么不能用"转角阈值"切分</h2>
 * 我们这一代的精度下，一条弧被离散成很多段，**相邻两段的转角很小**（可能不到 1°），
 * 用"转角 > 2° 就断开"根本切不开 ⇒ 整条弧被当成一大段；再加上**直线与弧相切**时
 * 转角同样是 0 ⇒ 相切处也切不开。所以"转角"这个信号在本问题里**不可用** ✓
 *
 * <h2>真正可靠的判据：点到弦的距离</h2>
 * 一段"真正的直线"上的所有原始点，到首尾连线的**垂直距离 ≈ 0**（浮点误差级）；
 * 而任何弧上的一段，中间点一定会**离弦有距离**（弓高）。
 * 于是：
 * <ol>
 *   <li>把端点**吸附到 1e-3 网格**后配对成链（避免浮点抖动导致连不上）</li>
 *   <li>对每条链做 Douglas–Peucker 简化（tol 取很小，比如 4e-3）</li>
 *   <li>逐个简化后的线段，回查它覆盖的原始点：**最大偏离 ≤ 1e-3 才保留**</li>
 *   <li>弧段（含相切后的弧）会因偏离超限被整体丢弃 ✓</li>
 * </ol>
 *
 * <p>纯计算、无游戏依赖（本包被离线编译 ✓）。
 */
public final class ReferenceOutline {

    private ReferenceOutline() {
    }

    /** 端点吸附精度（格）。1e-3 足够稳，又远小于任何有意义的几何尺寸 ✓ */
    private static final double SNAP = 1.0e-3;

    /** 判"是不是直线"的容差（格）：真实直线的点偏差在浮点量级，取 1e-3 很宽松 ✓ */
    private static final double STRAIGHT_TOL = 1.0e-3;

    /** Douglas–Peucker 的初始容差（只用来减少点数，不影响最终判定）✓ */
    private static final double DP_TOL = 4.0e-3;

    /** 太短的"直线"没有参考价值，直接丢弃（避免一堆碎片）✓ */
    private static final double MIN_LEN = 0.05;

    /** 最多返回多少条（双保险，防极端数据）✓ */
    private static final int MAX_RUNS = 64;

    /**
     * @param segs 2D 线段数组，每项 {@code {u1, v1, u2, v2}}
     * @return 只包含**真实直线**的线段（已合并，首尾即为直线端点）
     */
    public static List<double[]> straightRuns(final List<double[]> segs) {
        final List<double[]> out = new ArrayList<>();
        if (segs == null || segs.isEmpty()) {
            return out;
        }
        // ① 吸附端点 → 用 long key 配对
        final Map<Long, List<Integer>> atPoint = new HashMap<>();
        final List<long[]> keys = new ArrayList<>(segs.size());
        for (int i = 0; i < segs.size(); i++) {
            final double[] s = segs.get(i);
            if (s == null || s.length < 4) {
                keys.add(null);
                continue;
            }
            final long a = key(s[0], s[1]);
            final long b = key(s[2], s[3]);
            keys.add(new long[] {a, b});
            atPoint.computeIfAbsent(a, k -> new ArrayList<>()).add(i);
            atPoint.computeIfAbsent(b, k -> new ArrayList<>()).add(i);
        }
        final boolean[] used = new boolean[segs.size()];
        // ② 走链
        for (int seed = 0; seed < segs.size(); seed++) {
            if (used[seed] || keys.get(seed) == null) {
                continue;
            }
            used[seed] = true;
            final double[] s0 = segs.get(seed);
            // 链的坐标序列（偶数个：u,v,u,v...）
            final ArrayDeque<Double> chain = new ArrayDeque<>();
            chain.addLast(s0[0]);
            chain.addLast(s0[1]);
            chain.addLast(s0[2]);
            chain.addLast(s0[3]);
            long headKey = keys.get(seed)[0];
            long tailKey = keys.get(seed)[1];
            boolean extended = true;
            while (extended) {
                extended = false;
                // 向尾部延伸
                for (final int k : atPoint.getOrDefault(tailKey, List.of())) {
                    if (used[k] || keys.get(k) == null) {
                        continue;
                    }
                    final long a = keys.get(k)[0];
                    final long b = keys.get(k)[1];
                    final double[] s = segs.get(k);
                    if (a == tailKey) {
                        chain.addLast(s[2]);
                        chain.addLast(s[3]);
                        tailKey = b;
                    } else if (b == tailKey) {
                        chain.addLast(s[0]);
                        chain.addLast(s[1]);
                        tailKey = a;
                    } else {
                        continue;
                    }
                    used[k] = true;
                    extended = true;
                    break;
                }
                if (extended) {
                    continue;
                }
                // 向头部延伸
                for (final int k : atPoint.getOrDefault(headKey, List.of())) {
                    if (used[k] || keys.get(k) == null) {
                        continue;
                    }
                    final long a = keys.get(k)[0];
                    final long b = keys.get(k)[1];
                    final double[] s = segs.get(k);
                    if (a == headKey) {
                        chain.addFirst(s[1]);
                        chain.addFirst(s[0]);
                        headKey = b;
                    } else if (b == headKey) {
                        chain.addFirst(s[3]);
                        chain.addFirst(s[2]);
                        headKey = a;
                    } else {
                        continue;
                    }
                    used[k] = true;
                    extended = true;
                    break;
                }
                if (chain.size() > 8192) {
                    break;
                }
            }
            // ③ 链 → 点数组
            final int n = chain.size() / 2;
            if (n < 2) {
                continue;
            }
            final double[] pu = new double[n];
            final double[] pv = new double[n];
            int idx = 0;
            for (final double d : chain) {
                if ((idx & 1) == 0) {
                    pu[idx >> 1] = d;
                } else {
                    pv[idx >> 1] = d;
                }
                idx++;
            }
            collectStraight(pu, pv, out);
            if (out.size() >= MAX_RUNS) {
                break;
            }
        }
        if (out.size() > MAX_RUNS) {
            return new ArrayList<>(out.subList(0, MAX_RUNS));
        }
        return out;
    }

    /** 对一条链：DP 简化 + 逐段回查偏离，只留真直线 ✓ */
    private static void collectStraight(final double[] pu, final double[] pv,
                                        final List<double[]> out) {
        final int n = pu.length;
        final List<Integer> keep = new ArrayList<>();
        keep.add(0);
        dp(pu, pv, 0, n - 1, DP_TOL, keep);
        keep.add(n - 1);
        keep.sort(Integer::compareTo);
        for (int i = 0; i + 1 < keep.size(); i++) {
            final int a = keep.get(i);
            final int b = keep.get(i + 1);
            if (b <= a) {
                continue;
            }
            final double len = Math.hypot(pu[b] - pu[a], pv[b] - pv[a]);
            if (len < MIN_LEN) {
                continue;
            }
            if (maxDeviation(pu, pv, a, b) > STRAIGHT_TOL) {
                continue; // 这一段是弧（或相切过渡）⇒ 丢弃 ✓
            }
            out.add(new double[] {pu[a], pv[a], pu[b], pv[b]});
            if (out.size() >= MAX_RUNS) {
                return;
            }
        }
    }

    /** Douglas–Peucker（递归，迭代深度受链长限制）✓ */
    private static void dp(final double[] pu, final double[] pv,
                           final int a, final int b, final double tol,
                           final List<Integer> keep) {
        if (b <= a + 1) {
            return;
        }
        double best = -1.0;
        int bestIdx = -1;
        for (int i = a + 1; i < b; i++) {
            final double d = pointLineDistance(pu[i], pv[i], pu[a], pv[a], pu[b], pv[b]);
            if (d > best) {
                best = d;
                bestIdx = i;
            }
        }
        if (best > tol && bestIdx > 0) {
            keep.add(bestIdx);
            dp(pu, pv, a, bestIdx, tol, keep);
            dp(pu, pv, bestIdx, b, tol, keep);
        }
    }

    /** 区间 a..b 内所有点到弦 ab 的**最大垂直距离** ✓ */
    private static double maxDeviation(final double[] pu, final double[] pv,
                                       final int a, final int b) {
        double max = 0.0;
        for (int i = a + 1; i < b; i++) {
            max = Math.max(max, pointLineDistance(pu[i], pv[i], pu[a], pv[a], pu[b], pv[b]));
        }
        return max;
    }

    /** 点到直线的垂直距离（退化时退化为点距）✓ */
    private static double pointLineDistance(final double px, final double py,
                                            final double ax, final double ay,
                                            final double bx, final double by) {
        final double dx = bx - ax;
        final double dy = by - ay;
        final double len2 = dx * dx + dy * dy;
        if (len2 < 1.0e-18) {
            return Math.hypot(px - ax, py - ay);
        }
        return Math.abs((px - ax) * dy - (py - ay) * dx) / Math.sqrt(len2);
    }

    private static long key(final double u, final double v) {
        final long ku = Math.round(u / SNAP);
        final long kv = Math.round(v / SNAP);
        return (ku << 32) ^ (kv & 0xFFFFFFFFL);
    }
}
