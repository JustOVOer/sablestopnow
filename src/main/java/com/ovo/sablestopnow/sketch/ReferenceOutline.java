package com.ovo.sablestopnow.sketch;

import java.util.ArrayList;
import java.util.List;

/**
 * 从**网格边**里重建"真正的直线"参考线 ✓（bug 11 的系统性修法）。
 *
 * <h2>为什么不能用"转角阈值"切分</h2>
 * 网格精度下一条弧的相邻段转角可能远小于 1°，且**直线与弧相切**处转角为 0 ⇒ 切不开 ✗
 *
 * <h2>为什么不能"端点四舍五入配对"</h2>
 * 端点坐标落在吸附网格边界两侧时（如 +0.0005 与 -0.0005）会被分到不同格子 ⇒ 链被切断，
 * 每段自成一链 ⇒ 每段都"恰好是直线" ⇒ 又变回"每段一条线"✗（实测 refs+=64 撞上限）
 *
 * <h2>真正可靠的判据</h2>
 * <ol>
 *   <li>端点用**距离容差**配对（1e-3 内视为同一点，按实际距离而不是舍入格子）</li>
 *   <li>连成链后做 Douglas–Peucker（tol 1e-3）</li>
 *   <li>逐个简化段回查原始点：到弦的**最大垂直距离 ≤ 5e-5** 才算直线 ✓
 *       （真实直线的偏差在浮点噪声量级；任何弧的弓高都远大于 5e-5）</li>
 *   <li>弧段（含相切过渡）整段丢弃；长度 ≥0.1；最多 24 条</li>
 * </ol>
 *
 * <p>纯计算、无游戏依赖（本包被离线编译 ✓）。
 */
public final class ReferenceOutline {

    private ReferenceOutline() {
    }

    /** 端点视为同一点的容差（格）✓ */
    private static final double JOIN_TOL = 2.0e-3;

    /** Douglas–Peucker 初始容差 ✓ */
    private static final double DP_TOL = 1.0e-3;

    /** 判"直线"的最大允许偏离（格）：真实直线只有浮点噪声，弧的弓高远大于此 ✓ */
    private static final double STRAIGHT_TOL = 5.0e-5;

    /** 太短没有参考价值 ✓ */
    private static final double MIN_LEN = 0.10;

    /** 上限（双保险）✓ */
    private static final int MAX_RUNS = 24;

    // ---- 诊断（供客户端日志）----
    public static int lastSegments;
    public static int lastChains;
    public static int lastKept;

    /**
     * @param segs 2D 线段，每项 {@code {u1, v1, u2, v2}}
     * @return 只含真实直线的线段（首尾即直线端点）
     */
    public static List<double[]> straightRuns(final List<double[]> segs) {
        final List<double[]> out = new ArrayList<>();
        lastSegments = segs == null ? 0 : segs.size();
        lastChains = 0;
        lastKept = 0;
        if (segs == null || segs.isEmpty()) {
            return out;
        }
        final int n = segs.size();
        final boolean[] used = new boolean[n];
        for (int seed = 0; seed < n; seed++) {
            if (used[seed]) {
                continue;
            }
            final double[] s0 = segs.get(seed);
            if (s0 == null || s0.length < 4) {
                used[seed] = true;
                continue;
            }
            used[seed] = true;
            final List<double[]> pts = new ArrayList<>();
            pts.add(new double[] {s0[0], s0[1]});
            pts.add(new double[] {s0[2], s0[3]});
            boolean extended = true;
            while (extended) {
                extended = false;
                // 先接尾部，再接头部（每轮都从头扫描，n 很小，够快）
                for (int k = 0; k < n && !extended; k++) {
                    if (used[k]) {
                        continue;
                    }
                    final double[] s = segs.get(k);
                    if (s == null || s.length < 4) {
                        used[k] = true;
                        continue;
                    }
                    final double[] tail = pts.get(pts.size() - 1);
                    if (same(tail[0], tail[1], s[0], s[1])) {
                        pts.add(new double[] {s[2], s[3]});
                        used[k] = true;
                        extended = true;
                    } else if (same(tail[0], tail[1], s[2], s[3])) {
                        pts.add(new double[] {s[0], s[1]});
                        used[k] = true;
                        extended = true;
                    }
                }
                for (int k = 0; k < n && !extended; k++) {
                    if (used[k]) {
                        continue;
                    }
                    final double[] s = segs.get(k);
                    if (s == null || s.length < 4) {
                        used[k] = true;
                        continue;
                    }
                    final double[] head = pts.get(0);
                    if (same(head[0], head[1], s[0], s[1])) {
                        pts.add(0, new double[] {s[2], s[3]});
                        used[k] = true;
                        extended = true;
                    } else if (same(head[0], head[1], s[2], s[3])) {
                        pts.add(0, new double[] {s[0], s[1]});
                        used[k] = true;
                        extended = true;
                    }
                }
                if (pts.size() > 16384) {
                    break;
                }
            }
            lastChains++;
            final int m = pts.size();
            final double[] pu = new double[m];
            final double[] pv = new double[m];
            for (int i = 0; i < m; i++) {
                pu[i] = pts.get(i)[0];
                pv[i] = pts.get(i)[1];
            }
            collectStraight(pu, pv, out);
            if (out.size() >= MAX_RUNS) {
                break;
            }
        }
        lastKept = out.size();
        return out.size() > MAX_RUNS ? new ArrayList<>(out.subList(0, MAX_RUNS)) : out;
    }

    private static boolean same(final double ax, final double ay,
                                final double bx, final double by) {
        return Math.abs(ax - bx) <= JOIN_TOL && Math.abs(ay - by) <= JOIN_TOL;
    }

    private static void collectStraight(final double[] pu, final double[] pv,
                                        final List<double[]> out) {
        final int n = pu.length;
        if (n < 2) {
            return;
        }
        final List<Integer> keep = new ArrayList<>();
        keep.add(0);
        dp(pu, pv, 0, n - 1, DP_TOL, keep);
        keep.add(n - 1);
        keep.sort(Integer::compareTo);
        for (int i = 0; i + 1 < keep.size(); i++) {
            final int a = keep.get(i);
            int b = keep.get(i + 1);
            if (b <= a) {
                continue;
            }
            // 允许把相邻的共线简化段再合并（否则真实长直边被切碎）
            while (i + 2 < keep.size()) {
                final int c = keep.get(i + 2);
                if (maxDeviation(pu, pv, a, c) <= STRAIGHT_TOL) {
                    b = c;
                    keep.remove(i + 1);
                } else {
                    break;
                }
            }
            final double len = Math.hypot(pu[b] - pu[a], pv[b] - pv[a]);
            if (len < MIN_LEN) {
                continue;
            }
            if (maxDeviation(pu, pv, a, b) > STRAIGHT_TOL) {
                continue; // 弧/相切过渡 ⇒ 丢弃 ✓
            }
            out.add(new double[] {pu[a], pv[a], pu[b], pv[b]});
            if (out.size() >= MAX_RUNS) {
                return;
            }
        }
    }

    private static void dp(final double[] pu, final double[] pv,
                           final int a, final int b, final double tol,
                           final List<Integer> keep) {
        if (b <= a + 1) {
            return;
        }
        double best = -1.0;
        int bestIdx = -1;
        for (int i = a + 1; i < b; i++) {
            final double d = dist(pu[i], pv[i], pu[a], pv[a], pu[b], pv[b]);
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

    private static double maxDeviation(final double[] pu, final double[] pv,
                                       final int a, final int b) {
        double max = 0.0;
        for (int i = a + 1; i < b; i++) {
            max = Math.max(max, dist(pu[i], pv[i], pu[a], pv[a], pu[b], pv[b]));
        }
        return max;
    }

    private static double dist(final double px, final double py,
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
}
