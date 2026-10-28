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
        final List<double[]> section = section(radius);
        // 绕序：让 (n1 × n2) 与 dir 同向，保证外向
        final double sgn = new Vector3d(n1).cross(n2).dot(dir) >= 0 ? 1.0 : -1.0;
        final int n = section.size();
        final List<Vector3d> bottom = new ArrayList<>();
        final List<Vector3d> top = new ArrayList<>();
        for (final double[] q : section) {
            bottom.add(new Vector3d(s0).fma(q[0], n1).fma(q[1], n2));
            top.add(new Vector3d(s1).fma(q[0], n1).fma(q[1], n2));
        }
        // 侧面
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
