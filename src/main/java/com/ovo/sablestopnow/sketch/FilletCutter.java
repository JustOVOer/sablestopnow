package com.ovo.sablestopnow.sketch;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;

/**
 * **通用圆角切割体** ✓（item 8 的"通用方案"）。
 *
 * <p>第一版用"阶梯盒"近似四分之一圆柱，但那只对**轴对齐的棱**有效（世界 X/Y/Z）；
 * 实际模型的棱大量是斜的 ⇒ 用户实测"圆角创建了但模型没变化"。
 *
 * <p>这一版改成：沿棱方向扫出一个**真实的多边形截面棱柱**（截面 = 棱角方块减去圆弧后的区域，
 * 用 N 段折线近似圆弧），再交给 CSG 做减法 ⇒ **任意朝向的棱**都能倒圆 ✓
 * 碰撞侧仍沿用阶梯盒（轴对齐时精确；斜棱第一版只改外观，会在日志里注明）。
 */
public final class FilletCutter {

    private FilletCutter() {
    }

    /** 圆弧分段数（越大越圆）✓ */
    public static final int ARC_SEGMENTS = 8;

    /**
     * 造一个圆角切割体 ✓（世界/局部坐标与调用方一致）。
     *
     * @param a      棱起点
     * @param b      棱终点
     * @param normal 棱相邻的一个面法线（会被投影到垂直于棱的方向）
     * @param other  另一个相邻面法线
     * @param radius 半径
     * @return 三角面（每 3 个顶点一组）的顶点列表；失败返回空
     */
    public static List<Vector3d[]> prism(final Vector3dc a, final Vector3dc b,
                                         final Vector3dc normal, final Vector3dc other,
                                         final double radius) {
        final List<Vector3d[]> out = new ArrayList<>();
        final double[] frame = frame(a, b, normal, other, radius);
        if (frame == null) {
            return out;
        }
        final Vector3d dir = new Vector3d(b).sub(a);
        final double len = dir.length();
        dir.div(len);
        final Vector3d n1 = new Vector3d(frame[3], frame[4], frame[5]);
        final Vector3d n2 = new Vector3d(frame[6], frame[7], frame[8]);
        final Vector3d p0 = new Vector3d(frame[0], frame[1], frame[2]);
        final double ext = Math.max(2.0e-3, len * 0.004);
        final Vector3d s0 = new Vector3d(p0).fma(-ext, dir);
        final Vector3d s1 = new Vector3d(p0).fma(len + ext, dir);
        // 截面点（(u,v) = (n1,n2) 二维坐标）：角点 (0,0) → 弧 → (0,-r)
        final List<Vector3d> section3 = sectionOffsets(n1, n2, radius);
        if (section3.isEmpty()) {
            return out;
        }
        // 绕序：让 (n1 × n2) 与 dir 同向，保证外向
        final double sgn = new Vector3d(n1).cross(n2).dot(dir) >= 0 ? 1.0 : -1.0;
        final int n = section3.size();
        final List<Vector3d> bottom = new ArrayList<>(n);
        final List<Vector3d> top = new ArrayList<>(n);
        for (final Vector3d off : section3) {
            bottom.add(new Vector3d(s0).add(off));
            top.add(new Vector3d(s1).add(off));
        }
        for (int i = 0; i < n; i++) {
            final int j = (i + 1) % n;
            if (sgn > 0) {
                out.add(new Vector3d[] {bottom.get(i), top.get(j), bottom.get(j)});
                out.add(new Vector3d[] {bottom.get(i), top.get(i), top.get(j)});
            } else {
                out.add(new Vector3d[] {bottom.get(i), bottom.get(j), top.get(j)});
                out.add(new Vector3d[] {bottom.get(i), top.get(j), top.get(i)});
            }
        }
        // 两端封口（扇形；截面是凸的）
        for (int i = 1; i + 1 < n; i++) {
            if (sgn > 0) {
                out.add(new Vector3d[] {bottom.get(0), bottom.get(i), bottom.get(i + 1)});
                out.add(new Vector3d[] {top.get(0), top.get(i + 1), top.get(i)});
            } else {
                out.add(new Vector3d[] {bottom.get(0), bottom.get(i + 1), bottom.get(i)});
                out.add(new Vector3d[] {top.get(0), top.get(i), top.get(i + 1)});
            }
        }
        return out;
    }

    /**
     * **任意夹角的真实内切圆截面** ✓（旧版固定四分之一圆，只对垂直两面相切）
     *
     * <p>u=-n1, v=-n2 指向材料侧；φ=acos(u·v)；圆心在角平分线上、距两面都是 r；
     * 90° 时与旧实现逐点相同 ⇒ 完全兼容 ✓
     */
    public static List<Vector3d> sectionOffsets(final Vector3dc normal,
                                                final Vector3dc other,
                                                final double radius) {
        final List<Vector3d> pts = new ArrayList<>();
        if (radius <= 1.0e-6) {
            return pts;
        }
        final Vector3d u = new Vector3d(normal).normalize().negate();
        final Vector3d v = new Vector3d(other).normalize().negate();
        double c = Math.max(-1.0, Math.min(1.0, u.dot(v)));
        final double phi = Math.acos(c);
        final double sh = Math.sin(phi * 0.5);
        final Vector3d m = new Vector3d(u).add(v);
        if (sh < 1.0e-6 || m.lengthSquared() < 1.0e-12) {
            return pts;
        }
        m.normalize();
        final Vector3d center = new Vector3d(m).mul(radius / sh);
        final Vector3d t1 = new Vector3d(center).fma(-radius, u);
        final Vector3d e1 = new Vector3d(u);
        final Vector3d e2 = new Vector3d(v).fma(-v.dot(e1), e1);
        if (e2.lengthSquared() < 1.0e-12) {
            return pts;
        }
        e2.normalize();
        final Vector3d r1 = new Vector3d(t1).sub(center);
        final double a1 = Math.atan2(r1.dot(e2), r1.dot(e1));
        final double sweep = Math.PI - phi;
        pts.add(new Vector3d());
        pts.add(t1);
        final int seg = Math.max(2, ARC_SEGMENTS);
        for (int k2 = 1; k2 <= seg; k2++) {
            final double ang = a1 + sweep * k2 / seg;
            pts.add(new Vector3d(center)
                    .fma(radius * Math.cos(ang), e1)
                    .fma(radius * Math.sin(ang), e2));
        }
        return pts;
    }

    /** 截面（2D，单位：格）：角点 (0,0) + 从 (-r,0) 到 (0,-r) 的圆弧 ✓ 逆时针 */
    public static List<double[]> section(final double radius) {
        final List<double[]> pts = new ArrayList<>();
        pts.add(new double[] {0.0, 0.0}); // 棱角
        for (int i = 0; i <= ARC_SEGMENTS; i++) {
            final double ang = Math.PI * 0.5 * i / ARC_SEGMENTS;
            pts.add(new double[] {-radius + radius * Math.cos(ang),
                    -radius + radius * Math.sin(ang)});
        }
        return pts;
    }

    /**
     * 建一组"棱坐标框架" ✓ 返回 {px,py,pz, n1x,n1y,n1z, n2x,n2y,n2z}；
     * 失败返回 null。n1/n2 会被投影/正交化到垂直于棱的平面 ✓
     */
    public static double[] frame(final Vector3dc a, final Vector3dc b, final Vector3dc normal,
                                 final Vector3dc other, final double radius) {
        if (radius <= 1.0e-6) {
            return null;
        }
        final Vector3d dir = new Vector3d(b).sub(a);
        final double len = dir.length();
        if (len < 1.0e-6) {
            return null;
        }
        dir.div(len);
        final Vector3d n1 = new Vector3d(normal).fma(-new Vector3d(normal).dot(dir), dir);
        if (n1.lengthSquared() < 1.0e-9) {
            return null;
        }
        n1.normalize();
        final Vector3d n2 = new Vector3d(other).fma(-new Vector3d(other).dot(dir), dir);
        n2.fma(-n2.dot(n1), n1);
        if (n2.lengthSquared() < 1.0e-9) {
            return null;
        }
        n2.normalize();
        return new double[] {a.x(), a.y(), a.z(),
                n1.x, n1.y, n1.z, n2.x, n2.y, n2.z};
    }

    /**
     * runout 的**链 + 半径表** ✓（半径收小必须只占端点附近 r 的一小段）
     *
     * <h2>为什么不能只给"原始点"配半径（实测事故）</h2>
     * 直线棱只有 2 个点 ⇒ 半径表只有 [rMin, r] 两项 ⇒ 扫掠体是**整条棱的锥体**，
     * 用户看到的就是"整个圆角都是斜的"。必须在**离端点 tl 处插一个分段点**，
     * 让"锥形收尾"只占 tl 那一小段，其余整段仍是等半径圆角 ✓
     *
     * <p>tl = min(r, 50% 链长)：正常就是 r 的范围（用户要求）；棱太短时才退化成整条锥形。
     * 闭合链没有端点，不做 runout。
     */
    public static final class Runout {
        public final List<Vector3d> points;
        public final double[] radii;
        public final double taperLen;

        Runout(final List<Vector3d> points, final double[] radii, final double taperLen) {
            this.points = points;
            this.radii = radii;
            this.taperLen = taperLen;
        }
    }

    /**
     * 生成 runout 的链与半径表（见类注释）✓
     *
     * @param pts        原始链点
     * @param r          全半径
     * @param taperStart 起点做 runout（起点 = 凸/凹公共顶点）
     * @param taperEnd   终点做 runout
     */
    public static Runout runoutProfile(final List<Vector3d> pts, final double r,
                                       final boolean taperStart, final boolean taperEnd) {
        final List<Vector3d> base = new ArrayList<>();
        if (pts != null) {
            for (final Vector3d p : pts) {
                if (base.isEmpty()
                        || base.get(base.size() - 1).distanceSquared(p) > 1.0e-12) {
                    base.add(new Vector3d(p));
                }
            }
        }
        if (base.size() < 2) {
            final double[] rr = new double[base.size()];
            java.util.Arrays.fill(rr, r);
            return new Runout(base, rr, 0.0);
        }
        final boolean closed = base.size() > 2
                && base.get(0).distanceSquared(base.get(base.size() - 1)) < 1.0e-10;
        if (closed || (!taperStart && !taperEnd)) {
            final double[] rr = new double[base.size()];
            java.util.Arrays.fill(rr, r);
            return new Runout(base, rr, 0.0);
        }
        final double total = polyLength(base);
        if (total < 1.0e-9) {
            final double[] rr = new double[base.size()];
            java.util.Arrays.fill(rr, r);
            return new Runout(base, rr, 0.0);
        }
        final double tl = Math.min(r, 0.5 * total);
        final double rMin = Math.max(0.01, 0.08 * r);
        List<Vector3d> out = base;
        // ★ 关键：把"全半径开始的位置"插成真正的点，锥形才不会占满整条棱
        if (taperStart) {
            out = insertAtDistance(out, tl);
        }
        if (taperEnd && total - tl > tl + 1.0e-9) {
            out = insertAtDistance(out, total - tl);
        }
        final double[] cum = new double[out.size()];
        for (int i = 1; i < out.size(); i++) {
            cum[i] = cum[i - 1] + out.get(i).distance(out.get(i - 1));
        }
        final double tot = cum[cum.length - 1];
        final double[] radii = new double[out.size()];
        for (int i = 0; i < out.size(); i++) {
            double radius = r;
            if (taperStart) {
                radius = Math.min(radius, runoutRadius(cum[i], tl, r, rMin));
            }
            if (taperEnd) {
                radius = Math.min(radius, runoutRadius(tot - cum[i], tl, r, rMin));
            }
            radii[i] = radius;
        }
        return new Runout(out, radii, tl);
    }

    /** 只要半径表（原始点上的值；测试/诊断用，实际求值请用 {@link #runoutProfile}）✓ */
    public static double[] radiusProfile(final List<Vector3d> pts, final double r,
                                         final boolean taperStart, final boolean taperEnd) {
        return runoutProfile(pts, r, taperStart, taperEnd).radii;
    }

    /** 在距起点 d 的弧长处插一个点（已存在则原样返回）✓ */
    private static List<Vector3d> insertAtDistance(final List<Vector3d> pts, final double d) {
        if (pts == null || pts.size() < 2 || d <= 1.0e-9) {
            return pts;
        }
        final List<Vector3d> res = new ArrayList<>(pts.size() + 1);
        double acc = 0.0;
        boolean inserted = false;
        for (int i = 0; i < pts.size(); i++) {
            if (i > 0) {
                final Vector3d a = pts.get(i - 1);
                final Vector3d b = pts.get(i);
                final double seg = a.distance(b);
                if (!inserted && seg > 1.0e-12 && acc + seg > d + 1.0e-9) {
                    final double t = Math.max(0.0, Math.min(1.0, (d - acc) / seg));
                    res.add(new Vector3d(a).lerp(b, t));
                    inserted = true;
                }
                acc += seg;
            }
            res.add(pts.get(i));
        }
        return res;
    }

    /** 折线总长 ✓ */
    private static double polyLength(final List<Vector3d> pts) {
        double total = 0.0;
        for (int i = 1; i < pts.size(); i++) {
            total += pts.get(i).distance(pts.get(i - 1));
        }
        return total;
    }

    /** runout：距离端点 d 处的半径（d ≥ tl ⇒ 全半径）✓ */
    private static double runoutRadius(final double d, final double tl,
                                       final double r, final double rMin) {
        if (d >= tl) {
            return r;
        }
        return rMin + (r - rMin) * Math.max(0.0, d / tl);
    }

    /**
     * 变半径扫掠体的**理论体积** ✓ = (1−π/4)·∫r² ds
     * （每段把 r² 当线性变化积分 ⇒ {@code (a²+ab+b²)/3·len}）
     */
    public static double sweptVolume(final List<Vector3d> pts, final double[] radii) {
        if (pts == null || radii == null || pts.size() < 2
                || radii.length != pts.size()) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 1; i < pts.size(); i++) {
            final double a = radii[i - 1];
            final double b = radii[i];
            sum += (a * a + a * b + b * b) / 3.0 * pts.get(i).distance(pts.get(i - 1));
        }
        return sum * (1.0 - Math.PI / 4.0);
    }

    /**
     * 把象限符号从**存档 n2 基**换算到**扫掠第二轴基**（{@code tan×cap}）✓
     *
     * <p>扫掠体的第二轴是 {@code side = tan×cap}，它和存档里的 n2 只差一个符号
     * （取决于棱的方向和网格三角形的遍历顺序）。不换算就会把楔块建到**另一个象限**
     * ⇒ 切掉相邻那一行材料（实测）。预览和求值必须共用这一个函数 ✓
     *
     * @param cap     盖面法线（= 参考法线 n1，单位向量）
     * @param n2      存档里的第二个面法线
     * @param tangent 棱/链首段方向（不必归一）
     * @param q2      相对 n2 的象限符号（±1）
     * @return 相对 {@code tan×cap} 的象限符号
     */
    public static int sweepQ2(final Vector3dc cap, final Vector3dc n2,
                              final Vector3dc tangent, final int q2) {
        final Vector3d side = new Vector3d(tangent).cross(cap);
        if (side.lengthSquared() < 1.0e-12) {
            return q2;
        }
        return side.dot(n2) < 0.0 ? -q2 : q2;
    }

    /**
     * 沿一条折线**扫出圆角楔块** ✓（曲线棱专用：整条弧一个整体，不是逐段棱柱）
     *
     * <p>逐段棱柱在弧形上会在相邻段之间留下**楔形空隙**（用户实测"每个小圆角之间有间隙"）；
     * 扫掠体在关节处用**角平分面**（miter）连接 ⇒ 整条弧是连续的一根圆角 ✓
     *
     * @param points 折线点（首尾相接；闭合链把最后一个点写成第一个点即可）
     * @param cap    盖面法线（单位向量，参考法线 n1）
     * @param radius 半径
     * @param q1     ±1：楔块沿 cap 的哪一侧延伸
     * @param q2     ±1：楔块沿 (T×cap) 的哪一侧延伸
     * @return 三角面列表；退化时返回空
     */
    public static List<Vector3d[]> sweep(final List<Vector3d> points, final Vector3dc cap,
                                         final double radius, final int q1, final int q2) {
        return sweep(points, cap, radius, radius, q1, q2);
    }

    /**
     * 扫掠体（**半径可以沿链线性变化**）✓
     *
     * @param r0 起点半径
     * @param r1 终点半径（闭合链忽略，用平均值）
     */
    public static List<Vector3d[]> sweep(final List<Vector3d> points, final Vector3dc cap,
                                         final double r0, final double r1,
                                         final int q1, final int q2) {
        if (points == null || points.size() < 2 || r0 <= 1.0e-6 || r1 <= 1.0e-6) {
            return new ArrayList<>();
        }
        // 沿原始折线的累计长度 ⇒ 线性变半径 ✓
        final double[] cum = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            cum[i] = cum[i - 1] + points.get(i).distance(points.get(i - 1));
        }
        final double total = cum[cum.length - 1];
        final double[] radii = new double[points.size()];
        for (int i = 0; i < radii.length; i++) {
            radii[i] = total < 1.0e-9 ? (r0 + r1) * 0.5
                    : r0 + (r1 - r0) * (cum[i] / total);
        }
        return sweep(points, cap, radii, q1, q2);
    }

    /**
     * 扫掠体（**每个关节一个半径**）✓ —— 凸/凹相交处的 **fillet runout**：
     * 半径沿链收到很小 ⇒ 圆角面平滑地"跑出"到相邻面上，既不留台阶也不留缝。
     *
     * @param radii 与 {@code points} 一一对应的半径（半径必须 > 0）
     */
    public static List<Vector3d[]> sweep(final List<Vector3d> points, final Vector3dc cap,
                                         final double[] radii, final int q1, final int q2) {
        final List<Vector3d[]> out = new ArrayList<>();
        if (points == null || points.size() < 2 || radii == null
                || radii.length != points.size()) {
            return out;
        }
        final Vector3d n = new Vector3d(cap);
        if (n.lengthSquared() < 1.0e-9) {
            return out;
        }
        n.normalize();
        // 去掉连续重复点（半径同步去掉）
        final List<Vector3d> pts = new ArrayList<>();
        final List<Double> rs = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            final Vector3d p = points.get(i);
            if (radii[i] <= 1.0e-6) {
                return out;
            }
            if (!pts.isEmpty() && pts.get(pts.size() - 1).distanceSquared(p) < 1.0e-12) {
                continue;
            }
            pts.add(new Vector3d(p));
            rs.add(radii[i]);
        }
        if (pts.size() < 2) {
            return out;
        }
        final boolean closed = pts.size() > 2
                && pts.get(0).distanceSquared(pts.get(pts.size() - 1)) < 1.0e-10;
        if (closed) {
            pts.remove(pts.size() - 1);
            rs.remove(rs.size() - 1);
        }
        final int m = pts.size();
        if (m < 2) {
            return out;
        }
        // 每个关节的切线（内部用前后点的角平分方向）
        final Vector3d[] tan = new Vector3d[m];
        for (int i = 0; i < m; i++) {
            final boolean first = i == 0;
            final boolean last = i == m - 1;
            final Vector3d prev = closed ? pts.get((i - 1 + m) % m) : (first ? pts.get(0) : pts.get(i - 1));
            final Vector3d next = closed ? pts.get((i + 1) % m) : (last ? pts.get(m - 1) : pts.get(i + 1));
            final Vector3d t = new Vector3d(next).sub(prev);
            if (t.lengthSquared() < 1.0e-12) {
                // 端点退化成单段方向
                if (first) {
                    t.set(new Vector3d(pts.get(1)).sub(pts.get(0)));
                } else {
                    t.set(new Vector3d(pts.get(m - 1)).sub(pts.get(m - 2)));
                }
            }
            if (t.lengthSquared() < 1.0e-12) {
                return out;
            }
            tan[i] = t.normalize();
        }
        // 每个关节的截面（角平分面内），点数必须一致
        final List<List<Vector3d>> sections = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            final Vector3d side = new Vector3d(tan[i]).cross(n);
            if (side.lengthSquared() < 1.0e-9) {
                return out;
            }
            side.normalize();
            final Vector3d e1 = new Vector3d(n).mul(q1);
            final Vector3d e2 = side.mul(q2);
            final List<Vector3d> sec = sectionOffsets(
                    new Vector3d(e1).negate(), new Vector3d(e2).negate(), rs.get(i));
            if (sec.size() < 3) {
                return out;
            }
            final List<Vector3d> placed = new ArrayList<>(sec.size());
            for (final Vector3d off : sec) {
                placed.add(new Vector3d(pts.get(i)).add(off));
            }
            sections.add(placed);
        }
        final int k = sections.get(0).size();
        for (int i = 0; i < m; i++) {
            for (final List<Vector3d> s : sections) {
                if (s.size() != k) {
                    return out;
                }
            }
        }
        final int rings = closed ? m : m - 1;
        // ★★ 管壁绕序必须**朝外**（实测事故：端盖绕序写反 ⇒ 整个楔块的有向体积只剩
        //    1/3（AL/3）。`polysVolume` 看着"偏小但像那么回事"，而 CSG 拿到一个
        //    "一半面朝里"的封闭壳 ⇒ BSP 内外判错 ⇒ **减掉整行材料**、贴图崩坏。
        //    它拓扑上仍是闭合的（每条边正好两个面）⇒ `isClosed` 拦不住。
        //    实测：同一条棱 prism=0.024215 / 旧 sweep=0.008008（正好 1/3）。
        //    截面在 (e1,e2) 里绕序固定 ⇒ 管壁朝外 ⇔ (e1×e2)·路径方向 > 0；
        //    q1=-q2 时手性会翻过来，所以这里**按几何判**，不能硬编码。
        final Vector3d e1v = new Vector3d(n).mul(q1);
        final Vector3d e2v = new Vector3d(tan[0]).cross(n).normalize().mul(q2);
        final boolean tubeOut = new Vector3d(e1v).cross(e2v).dot(tan[0]) >= 0;
        for (int i = 0; i < rings; i++) {
            final List<Vector3d> a = sections.get(i);
            final List<Vector3d> b = sections.get((i + 1) % m);
            for (int j = 0; j < k; j++) {
                final int j2 = (j + 1) % k;
                if (tubeOut) {
                    out.add(new Vector3d[] {a.get(j), b.get(j), b.get(j2)});
                    out.add(new Vector3d[] {a.get(j), b.get(j2), a.get(j2)});
                } else {
                    out.add(new Vector3d[] {a.get(j), b.get(j2), b.get(j)});
                    out.add(new Vector3d[] {a.get(j), a.get(j2), b.get(j2)});
                }
            }
        }
        if (!closed) {
            // 两端封口（扇形；截面关于角点星形 ⇒ 用 0 号点扇开）
            // 绕序**由几何决定**（算出扇形法线与"朝外方向"比对），不靠起点/终点的
            // 正序反序硬编码：上面已经把管壁强制朝外，所以这里外部法线就是
            // 起点 −tan、终点 +tan ✓
            addCap(out, sections.get(0), new Vector3d(tan[0]).negate());
            addCap(out, sections.get(m - 1), new Vector3d(tan[m - 1]));
        }
        return out;
    }

    /**
     * 用扇形给扫掠体封口，**绕序自动朝外** ✓
     *
     * @param out     三角面输出
     * @param s       截面点（从角点开始、按截面顺序）
     * @param outward 该端盖的**朝外**方向（起点 −路径方向、终点 +路径方向）
     */
    private static void addCap(final List<Vector3d[]> out, final List<Vector3d> s,
                               final Vector3d outward) {
        if (s == null || s.size() < 3) {
            return;
        }
        final int k = s.size();
        Vector3d nrm = null;
        for (int j = 1; j + 1 < k && nrm == null; j++) {
            final Vector3d c = new Vector3d(s.get(j)).sub(s.get(0))
                    .cross(new Vector3d(s.get(j + 1)).sub(s.get(0)));
            if (c.lengthSquared() > 1.0e-18) {
                nrm = c;
            }
        }
        if (nrm == null) {
            return;
        }
        final boolean fwd = nrm.dot(outward) >= 0.0;
        for (int j = 1; j + 1 < k; j++) {
            if (fwd) {
                out.add(new Vector3d[] {s.get(0), s.get(j), s.get(j + 1)});
            } else {
                out.add(new Vector3d[] {s.get(0), s.get(j + 1), s.get(j)});
            }
        }
    }

    /**
     * 把折线两端各**缩进**一段弧长 ✓（SolidWorks 的 setback/让位做法）
     *
     * <p>凸/凹圆角在一个顶点相交时，让两条圆角都**停在离顶点 setback 距离处**，
     * 中间留出一段不处理的原棱 —— 这样不会产生锥形的极小半径端（会退化、弄坏 CSG），
     * 也不需要两个方向相反的圆角面在顶点处硬接。
     *
     * @param startInset 起点缩进（格）；0 = 不缩
     * @param endInset   终点缩进（格）
     * @return 缩进后的折线；缩完不足两点时返回空表（调用方跳过该棱）
     */
    public static List<Vector3d> trimPolyline(final List<Vector3d> pts,
                                              final double startInset, final double endInset) {
        final List<Vector3d> out = new ArrayList<>();
        if (pts == null || pts.size() < 2) {
            return out;
        }
        if (startInset <= 1.0e-9 && endInset <= 1.0e-9) {
            for (final Vector3d p : pts) {
                out.add(new Vector3d(p));
            }
            return out;
        }
        final int n = pts.size();
        final double[] cum = new double[n];
        for (int i = 1; i < n; i++) {
            cum[i] = cum[i - 1] + pts.get(i).distance(pts.get(i - 1));
        }
        final double total = cum[n - 1];
        if (total <= 1.0e-9) {
            return out;
        }
        final double s = Math.max(0.0, startInset);
        final double e = Math.max(0.0, endInset);
        if (s + e >= total * 0.8) {
            return out;
        }
        out.add(pointAt(pts, cum, s));
        for (int i = 0; i < n; i++) {
            if (cum[i] > s + 1.0e-9 && cum[i] < total - e - 1.0e-9) {
                out.add(new Vector3d(pts.get(i)));
            }
        }
        out.add(pointAt(pts, cum, total - e));
        return out;
    }

    /** 折线上的点（按累计弧长）✓ */
    private static Vector3d pointAt(final List<Vector3d> pts, final double[] cum,
                                    final double dist) {
        if (dist <= 0.0) {
            return new Vector3d(pts.get(0));
        }
        final int n = pts.size();
        if (dist >= cum[n - 1]) {
            return new Vector3d(pts.get(n - 1));
        }
        for (int i = 1; i < n; i++) {
            if (dist <= cum[i]) {
                final double seg = cum[i] - cum[i - 1];
                final double t = seg <= 1.0e-12 ? 0.0 : (dist - cum[i - 1]) / seg;
                return new Vector3d(pts.get(i - 1)).lerp(pts.get(i), t);
            }
        }
        return new Vector3d(pts.get(n - 1));
    }

    /**
     * **预览用**：圆角的两条切线与圆弧折线 ✓（与 {@link #section} 同一套数学，保证预览不骗人）
     */
    public static List<Vector3d[]> outline(final Vector3dc a, final Vector3dc b,
                                           final Vector3dc normal, final Vector3dc other,
                                           final double radius) {
        final List<Vector3d[]> out = new ArrayList<>();
        final double[] fr = frame(a, b, normal, other, radius);
        if (fr == null) {
            return out;
        }
        final Vector3d p0 = new Vector3d(fr[0], fr[1], fr[2]);
        final Vector3d n1 = new Vector3d(fr[3], fr[4], fr[5]);
        final Vector3d n2 = new Vector3d(fr[6], fr[7], fr[8]);
        final List<double[]> sec = section(radius);
        Vector3d prev = null;
        for (final double[] q : sec) {
            final Vector3d p = new Vector3d(p0).fma(q[0], n1).fma(q[1], n2);
            if (prev != null) {
                out.add(new Vector3d[] {new Vector3d(prev), new Vector3d(p)});
            }
            prev = p;
        }
        return out;
    }

    /**
     * **从网格精确判定一条直线棱**：相邻两个面法线 + 凸/凹 ✓
     *
     * <p>为什么不用探针：探针只能说"某个方向有没有材料"，无法区分"材料属于哪个面"，
     * 一旦盒子里混进别的实体/薄壁，象限就会选错（实测把楔块建到错的二面角上）。
     * 这里直接用网格：
     * <ul>
     *   <li>找出"两个顶点都落在这条棱上"的三角形 ⇒ 相邻面</li>
     *   <li>取互相最不平行的两个面 f1,f2</li>
     *   <li>凸凹判据：另一个面里**不在棱上**的那个顶点，落在 f1 平面的哪一侧
     *       —— 在材料侧（{@code f1·(w2−a) < 0}）⇒ 凸棱；在空侧 ⇒ 凹棱 ✓</li>
     * </ul>
     *
     * @return {@code {f1x,f1y,f1z, f2x,f2y,f2z, convex?1:0}}；找不到返回 null
     */
    public static double[] edgeInfo(final SolidMesh.Mesh mesh, final Vector3dc a,
                                    final Vector3dc b) {
        if (mesh == null || mesh.isEmpty() || a == null || b == null) {
            return null;
        }
        final int[] idx = mesh.indices();
        final java.util.List<Vector3d> ns = new ArrayList<>();
        final java.util.List<Vector3d> far = new ArrayList<>();
        for (int i = 0; i + 2 < idx.length; i += 3) {
            final Vector3d v0 = mesh.vertices().get(idx[i]).position();
            final Vector3d v1 = mesh.vertices().get(idx[i + 1]).position();
            final Vector3d v2 = mesh.vertices().get(idx[i + 2]).position();
            final boolean o0 = onEdge(v0, a, b, 1.0e-3);
            final boolean o1 = onEdge(v1, a, b, 1.0e-3);
            final boolean o2 = onEdge(v2, a, b, 1.0e-3);
            if ((o0 ? 1 : 0) + (o1 ? 1 : 0) + (o2 ? 1 : 0) != 2) {
                continue;
            }
            final Vector3d n = new Vector3d(v1).sub(v0)
                    .cross(new Vector3d(v2).sub(v0));
            if (n.lengthSquared() < 1.0e-14) {
                continue;
            }
            n.normalize();
            boolean dup = false;
            for (final Vector3d o : ns) {
                if (Math.abs(o.dot(n)) > 0.999) {
                    dup = true;
                    break;
                }
            }
            if (dup) {
                continue;
            }
            ns.add(n);
            far.add(!o0 ? v0 : (!o1 ? v1 : v2));
            if (ns.size() >= 4) {
                break;
            }
        }
        if (ns.size() < 2) {
            return null;
        }
        int bi = -1;
        int bj = -1;
        double best = -2.0;
        for (int i = 0; i < ns.size(); i++) {
            for (int j = i + 1; j < ns.size(); j++) {
                final double d = Math.abs(ns.get(i).dot(ns.get(j)));
                if (d < 0.95 && d > best) {
                    best = d;
                    bi = i;
                    bj = j;
                }
            }
        }
        if (bi < 0) {
            return null;
        }
        final Vector3d f1 = ns.get(bi);
        final Vector3d f2 = ns.get(bj);
        final Vector3d w2 = far.get(bj);
        final boolean convex = f1.dot(new Vector3d(w2).sub(a)) < 0.0;
        return new double[] {f1.x, f1.y, f1.z, f2.x, f2.y, f2.z, convex ? 1.0 : 0.0};
    }

    /**
     * 从网格里找出与某条棱相邻的**两个面法线** ✓
     *
     * @return {n1, n2}；找不到返回 null
     */
    public static Vector3d[] faceNormals(final SolidMesh.Mesh mesh, final Vector3dc a,
                                         final Vector3dc b) {
        if (mesh == null || mesh.isEmpty()) {
            return null;
        }
        final double tol = 1.0e-3;
        final List<Vector3d> found = new ArrayList<>();
        final int[] idx = mesh.indices();
        for (int i = 0; i + 2 < idx.length; i += 3) {
            final Vector3d v0 = mesh.vertices().get(idx[i]).position();
            final Vector3d v1 = mesh.vertices().get(idx[i + 1]).position();
            final Vector3d v2 = mesh.vertices().get(idx[i + 2]).position();
            if (!(onEdge(v0, a, b, tol) && onEdge(v1, a, b, tol))
                    && !(onEdge(v1, a, b, tol) && onEdge(v2, a, b, tol))
                    && !(onEdge(v0, a, b, tol) && onEdge(v2, a, b, tol))) {
                continue;
            }
            final Vector3d n = new Vector3d(v1).sub(v0).cross(new Vector3d(v2).sub(v0));
            if (n.lengthSquared() < 1.0e-12) {
                continue;
            }
            n.normalize();
            boolean dup = false;
            for (final Vector3d f : found) {
                if (Math.abs(f.dot(n)) > 0.999) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                found.add(n);
            }
            if (found.size() >= 6) {
                break;
            }
        }
        if (found.size() < 2) {
            return null;
        }
        // 取夹角最接近 90° 的一对
        double best = -2;
        Vector3d b1 = null;
        Vector3d b2 = null;
        for (int i = 0; i < found.size(); i++) {
            for (int j = i + 1; j < found.size(); j++) {
                final double d = found.get(i).dot(found.get(j));
                if (Math.abs(d) < 0.99 && d > best) {
                    best = d;
                    b1 = found.get(i);
                    b2 = found.get(j);
                }
            }
        }
        return b1 == null ? null : new Vector3d[] {b1, b2};
    }

    private static boolean onEdge(final Vector3dc p, final Vector3dc a, final Vector3dc b,
                                  final double tol) {
        final Vector3d ab = new Vector3d(b).sub(a);
        final double len2 = ab.lengthSquared();
        if (len2 < 1.0e-12) {
            return false;
        }
        final double t = new Vector3d(p).sub(a).dot(ab) / len2;
        if (t < -0.01 || t > 1.01) {
            return false;
        }
        final Vector3d proj = new Vector3d(a).fma(Math.max(0, Math.min(1, t)), ab);
        return proj.distance(p) <= Math.max(tol, 1.0e-3);
    }
}
