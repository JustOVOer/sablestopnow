package com.ovo.sablestopnow.sketch;

import java.util.ArrayList;
import java.util.List;

/**
 * 从**网格边界边**里挑出"真正的直线"，丢掉曲线（弧/样条）的离散段 ✓（bug 11 的最终方案）。
 *
 * <h2>问题本质</h2>
 * `Features` 给出的边界边已经做过共线合并 ⇒ 列表里的每一段只有两种身份：
 * <ol>
 *   <li>**真正的直边**：一整条（内部没有顶点），两端是"角"（转角很大）</li>
 *   <li>**曲线的离散步**：一串长度相近的短段（实测 0.100~0.126），相邻段之间转角**很小**</li>
 * </ol>
 *
 * <h2>判据（不依赖"转角阈值"切分，也不依赖把整圈连成一条链）</h2>
 * 对每一段，看它**两端**各自的转角（与该端最共线邻居的夹角正弦）：
 * <ul>
 *   <li>两端都是"小转角"（1e-5 ~ 0.5）⇒ 处在曲线**内部** ⇒ 丢弃</li>
 *   <li>否则先保留，再做**长度一致性生长**：若它一端的小转角邻居已被判为曲线，
 *       且两者**长度接近**（±35%）⇒ 同一条曲线的离散步 ⇒ 丢弃。
 *       这能吃掉"曲线两端"那两段，而**长直边**因长度差异不会被误吃 ✓</li>
 * </ul>
 *
 * <p>连"直线与弧相切"也安全：相切处转角≈0，但直边长度与弧步长明显不同 ⇒ 不被吃掉 ✓
 *
 * <p>纯计算、无游戏依赖（本包被离线编译 ✓）。
 */
public final class ReferenceOutline {

    private ReferenceOutline() {
    }

    /** 端点视为同一点的容差（格）✓ */
    private static final double JOIN_TOL = 2.0e-3;

    /** 视为"可见转角"的最小正弦值（真实共线只有 1e-9 量级）✓ */
    private static final double TURN_MIN = 1.0e-5;

    /** 超过它就当"角"而不是"曲线步"（同一曲线的相邻步远小于它）✓ */
    private static final double TURN_MAX = 0.5;

    /** 曲线步之间的长度相似度 ✓ */
    private static final double LEN_RATIO = 0.35;

    /** 最短保留长度 ✓ */
    private static final double MIN_LEN = 0.05;

    private static final int MAX_RUNS = 64;

    // ---- 诊断（供客户端日志）----
    public static int lastSegments;
    public static int lastChains;
    public static int lastKept;

    /**
     * @param segs 线段，每项 {@code {x1,y1,z1,x2,y2,z2}}（3D，与 {@code Features} 一致）
     * @return 仅包含真实直边的线段（同样的 3D 形式）
     */
    public static List<double[]> straightEdges(final List<double[]> segs) {
        final List<double[]> out = new ArrayList<>();
        lastSegments = segs == null ? 0 : segs.size();
        lastKept = 0;
        lastChains = 0;
        if (segs == null || segs.isEmpty()) {
            return out;
        }
        final int n = segs.size();
        final double[] len = new double[n];
        for (int i = 0; i < n; i++) {
            final double[] s = segs.get(i);
            len[i] = (s == null || s.length < 6) ? 0.0
                    : Math.sqrt(sq(s[3] - s[0]) + sq(s[4] - s[1]) + sq(s[5] - s[2]));
        }
        // 端点邻接（2*i / 2*i+1 表示第 i 段的两端）
        final List<List<Integer>> atEnd = new ArrayList<>(n * 2);
        for (int i = 0; i < n * 2; i++) {
            atEnd.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            if (len[i] <= 0.0) {
                continue;
            }
            for (int j = i + 1; j < n; j++) {
                if (len[j] <= 0.0) {
                    continue;
                }
                for (int a = 0; a < 2; a++) {
                    for (int b = 0; b < 2; b++) {
                        if (close(segs.get(i), a, segs.get(j), b)) {
                            atEnd.get(2 * i + a).add(2 * j + b);
                            atEnd.get(2 * j + b).add(2 * i + a);
                        }
                    }
                }
            }
        }
        // 每段两端的最小转角
        final double[] turnAt = new double[n * 2];
        java.util.Arrays.fill(turnAt, Double.NaN);
        for (int i = 0; i < n; i++) {
            for (int a = 0; a < 2; a++) {
                double best = Double.NaN;
                for (final int other : atEnd.get(2 * i + a)) {
                    final double sin = Math.abs(sinBetween(segs.get(i), a,
                            segs.get(other / 2), other % 2));
                    if (Double.isNaN(best) || sin < best) {
                        best = sin;
                    }
                }
                turnAt[2 * i + a] = best;
            }
        }
        // 曲线内部：两端都是小转角
        final boolean[] curve = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (len[i] <= 0.0) {
                continue;
            }
            curve[i] = visibleSmall(turnAt[2 * i]) && visibleSmall(turnAt[2 * i + 1]);
        }
        // 生长：与已判曲线的邻居"长度接近"且小转角相连 ⇒ 同一条曲线
        boolean grew = true;
        while (grew) {
            grew = false;
            for (int i = 0; i < n; i++) {
                if (curve[i] || len[i] <= 0.0) {
                    continue;
                }
                for (int a = 0; a < 2; a++) {
                    if (!visibleSmall(turnAt[2 * i + a])) {
                        continue;
                    }
                    for (final int other : atEnd.get(2 * i + a)) {
                        final int j = other / 2;
                        if (!curve[j] || len[j] <= 0.0) {
                            continue;
                        }
                        final double ratio = len[i] / len[j];
                        if (ratio > 1.0 - LEN_RATIO && ratio < 1.0 + LEN_RATIO) {
                            curve[i] = true;
                            grew = true;
                            break;
                        }
                    }
                    if (curve[i]) {
                        break;
                    }
                }
            }
        }
        for (int i = 0; i < n; i++) {
            if (curve[i] || len[i] < MIN_LEN) {
                continue;
            }
            final double[] s = segs.get(i);
            out.add(new double[] {s[0], s[1], s[2], s[3], s[4], s[5]});
            if (out.size() >= MAX_RUNS) {
                break;
            }
        }
        lastChains = out.size();
        lastKept = out.size();
        return out;
    }

    private static boolean visibleSmall(final double sin) {
        return !Double.isNaN(sin) && sin > TURN_MIN && sin < TURN_MAX;
    }

    private static boolean close(final double[] a, final int endA,
                                 final double[] b, final int endB) {
        final int ia = endA == 0 ? 0 : 3;
        final int ib = endB == 0 ? 0 : 3;
        return Math.abs(a[ia] - b[ib]) <= JOIN_TOL
                && Math.abs(a[ia + 1] - b[ib + 1]) <= JOIN_TOL
                && Math.abs(a[ia + 2] - b[ib + 2]) <= JOIN_TOL;
    }

    /** 段 s 在端点 a 处的**向外**方向 ✓ */
    private static double[] dir(final double[] s, final int a) {
        final int from = a == 0 ? 0 : 3;
        final int to = a == 0 ? 3 : 0;
        return new double[] {s[to] - s[from], s[to + 1] - s[from + 1], s[to + 2] - s[from + 2]};
    }

    /** 两段在公共端点处的方向夹角正弦绝对值 ✓ */
    private static double sinBetween(final double[] a, final int endA,
                                     final double[] b, final int endB) {
        final double[] da = dir(a, endA);
        final double[] db = dir(b, endB);
        final double[] cr = {da[1] * db[2] - da[2] * db[1],
                da[2] * db[0] - da[0] * db[2],
                da[0] * db[1] - da[1] * db[0]};
        final double la = Math.sqrt(sq(da[0]) + sq(da[1]) + sq(da[2]));
        final double lb = Math.sqrt(sq(db[0]) + sq(db[1]) + sq(db[2]));
        final double lc = Math.sqrt(sq(cr[0]) + sq(cr[1]) + sq(cr[2]));
        if (la < 1.0e-9 || lb < 1.0e-9) {
            return 0.0;
        }
        return lc / (la * lb);
    }

    private static double sq(final double v) {
        return v * v;
    }
}
