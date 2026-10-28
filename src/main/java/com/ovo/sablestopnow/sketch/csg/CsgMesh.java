/*
 * CsgMesh.java —— 把我们的渲染网格（SolidMesh）与 CSG 多边形互相转换
 *
 * 本文件里"CSG 部分的结构"来自 csg.js 的 Java 移植（见 {@link Csg} 的版权声明）；
 * 本文件自身的代码（网格互转、坐标变换、锐边提取）是**我们自己写的**，同样以 MIT 发布。
 */
package com.ovo.sablestopnow.sketch.csg;

import com.ovo.sablestopnow.sketch.SketchPlane;
import com.ovo.sablestopnow.sketch.SolidMesh;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Vector3d;
import org.joml.Vector3dc;

/** {@link SolidMesh.Mesh} ⇄ {@link Csg.Polygon} 的适配层（含坐标变换与锐边提取）。 */
public final class CsgMesh {

    private CsgMesh() {
    }

    /** 顶点属性变换（不同草图平面之间换算用；null = 原样）。 */
    public interface Xform {
        /** 位置变换 ✓（局部坐标 → 局部坐标）。 */
        double[] pos(double x, double y, double z);

        /** 法线变换 ✓（只转方向，不平移）。 */
        double[] normal(double x, double y, double z);
    }

    /**
     * 由**切割/目标的草图平面**构造一个坐标变换 ✓。
     *
     * <p>我们的网格顶点是"平面局部坐标" {@code (u, v, depth)}（depth 沿平面法线）✓
     * ⇒ 从 {@code from} 到 {@code to} 的换算是：
     * {@code 世界 = from.toLocal(u,v) + n_from·d}，再 {@code (u',v') = to.toPlane(世界)}、{@code d' = to.signedDistance(世界)} ✓
     */
    public static Xform between(final SketchPlane from, final SketchPlane to) {
        if (from == null || to == null) {
            return null;
        }
        final Vector3d fromNormal = from.normal();
        final Vector3d toU = to.axisU();
        final Vector3d toV = to.axisV();
        final Vector3d toNormal = to.normal();
        final Vector3d tmp = new Vector3d();
        return new Xform() {
            @Override
            public double[] pos(final double x, final double y, final double z) {
                final Vector3d local = from.toLocal(x, y).fma(z, fromNormal, new Vector3d());
                to.toPlane(local, tmp);
                return new double[] {tmp.x, tmp.y, to.signedDistance(local)};
            }

            @Override
            public double[] normal(final double x, final double y, final double z) {
                // 方向向量只需要换基（不平移）✓：N·u'、N·v'、N·n' ✓
                final double nx = x * toU.x + y * toU.y + z * toU.z;
                final double ny = x * toV.x + y * toV.y + z * toV.z;
                final double nz = x * toNormal.x + y * toNormal.y + z * toNormal.z;
                return new double[] {nx, ny, nz};
            }
        };
    }

    /** 网格 → CSG 多边形 ✓（三角形；退化三角形直接跳过 ✓）。 */
    public static List<Csg.Polygon> fromMesh(final SolidMesh.Mesh mesh, final Xform xf) {
        final List<Csg.Polygon> out = new ArrayList<>();
        if (mesh == null || mesh.isEmpty()) {
            return out;
        }
        final List<SolidMesh.Vertex> vs = mesh.vertices();
        final int[] idx = mesh.indices();
        for (int i = 0; i + 2 < idx.length; i += 3) {
            final Csg.Vertex[] tri = new Csg.Vertex[3];
            for (int k = 0; k < 3; k++) {
                final SolidMesh.Vertex v = vs.get(idx[i + k]);
                double[] p = {v.position().x, v.position().y, v.position().z};
                double[] n = {v.normal().x, v.normal().y, v.normal().z};
                if (xf != null) {
                    p = xf.pos(p[0], p[1], p[2]);
                    n = xf.normal(n[0], n[1], n[2]);
                }
                tri[k] = new Csg.Vertex(
                        new Csg.V3(p[0], p[1], p[2]),
                        new Csg.V3(n[0], n[1], n[2]),
                        new Csg.V3(v.u(), v.v(), 0.0));
            }
            final Csg.Plane plane = Csg.Plane.fromPoints(
                    tri[0].pos(), tri[1].pos(), tri[2].pos());
            if (plane == null) {
                continue; // 退化三角形：没有平面 ⇒ 跳过 ✓
            }
            final List<Csg.Vertex> verts = new ArrayList<>(3);
            verts.add(tri[0]);
            verts.add(tri[1]);
            verts.add(tri[2]);
            out.add(new Csg.Polygon(verts, plane));
        }
        return out;
    }

    /** CSG 多边形 → 网格 ✓（扇形三角化；UV 与法线沿用 CSG 顶点 ✓ 贴图不丢 ✓）。 */
    public static SolidMesh.Mesh toMesh(final List<Csg.Polygon> polys) {
        final List<SolidMesh.Vertex> vs = new ArrayList<>();
        final List<Integer> idx = new ArrayList<>();
        if (polys == null) {
            return new SolidMesh.Mesh(vs, new int[0]);
        }
        for (final Csg.Polygon p : polys) {
            final List<Csg.Vertex> verts = p.vertices;
            if (verts.size() < 3) {
                continue;
            }
            final int base = vs.size();
            // ★ 法线取**多边形平面的法线**（而不是顶点里存的法线）：
            //   减法会把切割体的多边形翻面，顶点法线若没跟着翻就会"从里面朝外"
            //   ⇒ 光照算出全黑（用户实测："圆角处是黑色的没有贴图"）✗
            final Vector3d pn = p.plane == null ? null
                    : new Vector3d(p.plane.normal.x(), p.plane.normal.y(), p.plane.normal.z())
                            .normalize();
            for (final Csg.Vertex v : verts) {
                final Vector3d n = pn == null
                        ? new Vector3d(v.normal().x(), v.normal().y(), v.normal().z())
                        : pn;
                vs.add(new SolidMesh.Vertex(
                        new Vector3d(v.pos().x(), v.pos().y(), v.pos().z()),
                        new Vector3d(n), v.uv().x(), v.uv().y()));
            }
            for (int k = 1; k + 1 < verts.size(); k++) {
                idx.add(base);
                idx.add(base + k);
                idx.add(base + k + 1);
            }
        }
        final int[] arr = new int[idx.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = idx.get(i);
        }
        return new SolidMesh.Mesh(vs, arr);
    }

    /**
     * 从 CSG 结果里提取**要画的棱** ✓（边界边 + 锐边）。
     *
     * <p>为什么必须这么做 ✗✓：切割之后原来的"棱柱轮廓"已经不成立了 ✗
     * 如果还画旧轮廓，玩家会看到"线框是完整的、实体是有洞的" ✓（实测过这种不一致 ✓）
     * <p>做法：把每条边按端点量化后归档，统计相邻面法线夹角；
     * 只有 1 个面（边界）或夹角大于阈值（锐边）的边才画 ✓
     */
    public static List<Vector3d[]> sharpEdges(final List<Csg.Polygon> polys,
                                              final double angleDeg) {
        final Map<String, List<double[]>> edges = new HashMap<>();
        if (polys == null) {
            return new ArrayList<>();
        }
        final double cosLimit = Math.cos(Math.toRadians(angleDeg));
        for (final Csg.Polygon p : polys) {
            final List<Csg.Vertex> vs = p.vertices;
            for (int i = 0; i < vs.size(); i++) {
                final Csg.V3 a = vs.get(i).pos();
                final Csg.V3 b = vs.get((i + 1) % vs.size()).pos();
                final String ka = key(a);
                final String kb = key(b);
                final String key = ka.compareTo(kb) <= 0 ? ka + "|" + kb : kb + "|" + ka;
                edges.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new double[] {p.plane.normal.x(), p.plane.normal.y(),
                                p.plane.normal.z()});
            }
        }
        final List<Vector3d[]> out = new ArrayList<>();
        for (final Map.Entry<String, List<double[]>> e : edges.entrySet()) {
            final List<double[]> normals = e.getValue();
            boolean keep = normals.size() == 1; // 边界边 ✓
            if (!keep && normals.size() >= 2) {
                final double[] n0 = normals.get(0);
                final double[] n1 = normals.get(1);
                final double d = n0[0] * n1[0] + n0[1] * n1[1] + n0[2] * n1[2];
                keep = d < cosLimit; // 锐边 ✓
            }
            if (!keep) {
                continue;
            }
            final String[] parts = e.getKey().split("\\|");
            out.add(new Vector3d[] {unkey(parts[0]), unkey(parts[1])});
        }
        return out;
    }

    private static String key(final Csg.V3 p) {
        return Math.round(p.x() * 10000.0) + "," + Math.round(p.y() * 10000.0) + ","
                + Math.round(p.z() * 10000.0);
    }

    private static Vector3d unkey(final String s) {
        final String[] q = s.split(",");
        return new Vector3d(Double.parseDouble(q[0]) / 10000.0,
                Double.parseDouble(q[1]) / 10000.0,
                Double.parseDouble(q[2]) / 10000.0);
    }

    /** 结果的包围盒（离线断言用）✓。 */
    public static double[] bounds(final List<Csg.Polygon> polys) {
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (final Csg.Polygon p : polys) {
            for (final Csg.Vertex v : p.vertices) {
                final Vector3dc q = new Vector3d(v.pos().x(), v.pos().y(), v.pos().z());
                minX = Math.min(minX, q.x());
                minY = Math.min(minY, q.y());
                minZ = Math.min(minZ, q.z());
                maxX = Math.max(maxX, q.x());
                maxY = Math.max(maxY, q.y());
                maxZ = Math.max(maxZ, q.z());
            }
        }
        return new double[] {minX, minY, minZ, maxX, maxY, maxZ};
    }
}
