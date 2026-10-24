/*
 * CSG.java —— BSP 构造实体几何（并/差/交）
 *
 * 本文件是 **csg.js** 的 Java 移植（保留算法与结构，未做功能删减）。
 *
 * ---------------------------------------------------------------------------
 * Copyright (c) 2011 Evan Wallace (http://madebyevan.com/)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 * ---------------------------------------------------------------------------
 *
 * <p>移植约定（与 csg.js 保持一致，便于对照排查）：
 * <ul>
 *   <li>{@link Vertex} 携带 position / normal / uv 三组属性 ⇒ 贴图不会因为布尔而丢 ✓</li>
 *   <li>{@link Plane#splitPolygon} 的共面/前后分类用 {@link #EPSILON} 判定 ✓</li>
 *   <li>{@link Node} 是 BSP 树：先 {@code build} 再 {@code clipTo} ✓</li>
 * </ul>
 *
 * <p>⚠ 已知边界（与 csg.js 相同）：浮点 BSP 不是精确谓词 ⇒ 输入**不封闭**
 * 或**极端共面/退化三角形**时可能出现漏面。调用方要先保证输入是封闭网格。
 */
package com.ovo.sablestopnow.sketch.csg;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** BSP CSG（csg.js 的 Java 移植）。 */
public final class Csg {

    private Csg() {
    }

    /** 平面判定容差（与 csg.js 的 1e-5 同量级）。 */
    public static final double EPSILON = 1.0e-5;

    /** 三个双精度分量（pos / normal / uv 共用）。 */
    public record V3(double x, double y, double z) {
    }

    /** 一个带有位置、法线、UV 的顶点 ✓（UV 必须带 ⇒ 贴图才不丢 ✓）。 */
    public record Vertex(V3 pos, V3 normal, V3 uv) {

        public Vertex interpolate(final Vertex other, final double t) {
            return new Vertex(
                    lerp(pos, other.pos, t),
                    lerp(normal, other.normal, t),
                    lerp(uv, other.uv, t));
        }

        public Vertex flip() {
            return new Vertex(pos, new V3(-normal.x(), -normal.y(), -normal.z()), uv);
        }

        private static V3 lerp(final V3 a, final V3 b, final double t) {
            return new V3(a.x() + (b.x() - a.x()) * t,
                    a.y() + (b.y() - a.y()) * t,
                    a.z() + (b.z() - a.z()) * t);
        }
    }

    /** 平面：{@code normal · p = w} ✓。 */
    public static final class Plane {
        public final V3 normal;
        public final double w;

        public Plane(final V3 normal, final double w) {
            this.normal = normal;
            this.w = w;
        }

        /** 由三点定平面（退化时返回 null ⇒ 调用方跳过这个三角形 ✓）。 */
        public static Plane fromPoints(final V3 a, final V3 b, final V3 c) {
            final V3 ab = sub(b, a);
            final V3 ac = sub(c, a);
            final V3 n = cross(ab, ac);
            final double len = Math.sqrt(n.x() * n.x() + n.y() * n.y() + n.z() * n.z());
            if (len < 1.0e-12) {
                return null;
            }
            final V3 un = new V3(n.x() / len, n.y() / len, n.z() / len);
            return new Plane(un, dot(un, a));
        }

        public Plane flipped() {
            return new Plane(new V3(-normal.x(), -normal.y(), -normal.z()), -w);
        }

        /**
         * 把多边形按本平面切成 共面前 / 共面后 / 前 / 后 四类 ✓（csg.js 同名方法）。
         */
        public void splitPolygon(final Polygon poly, final List<Polygon> coplanarFront,
                                 final List<Polygon> coplanarBack,
                                 final List<Polygon> front, final List<Polygon> back) {
            final int COPLANAR = 0;
            final int FRONT = 1;
            final int BACK = 2;
            final int SPANNING = 3;

            int polygonType = 0;
            final int[] types = new int[poly.vertices.size()];
            for (int i = 0; i < poly.vertices.size(); i++) {
                final double t = dot(normal, poly.vertices.get(i).pos()) - w;
                final int type = t < -EPSILON ? BACK : (t > EPSILON ? FRONT : COPLANAR);
                polygonType |= type;
                types[i] = type;
            }

            switch (polygonType) {
                case COPLANAR -> {
                    if (dot(normal, poly.plane.normal) > 0) {
                        coplanarFront.add(poly);
                    } else {
                        coplanarBack.add(poly);
                    }
                }
                case FRONT -> front.add(poly);
                case BACK -> back.add(poly);
                default -> {
                    final List<Vertex> f = new ArrayList<>();
                    final List<Vertex> b = new ArrayList<>();
                    final int n = poly.vertices.size();
                    for (int i = 0; i < n; i++) {
                        final int j = (i + 1) % n;
                        final int ti = types[i];
                        final int tj = types[j];
                        final Vertex vi = poly.vertices.get(i);
                        final Vertex vj = poly.vertices.get(j);
                        if (ti != BACK) {
                            f.add(vi);
                        }
                        if (ti != FRONT) {
                            b.add(vi);
                        }
                        if ((ti | tj) == SPANNING) {
                            final double t = (w - dot(normal, vi.pos()))
                                    / dot(normal, sub(vj.pos(), vi.pos()));
                            final Vertex v = vi.interpolate(vj, t);
                            f.add(v);
                            b.add(v);
                        }
                    }
                    if (f.size() >= 3) {
                        front.add(new Polygon(f, poly.plane));
                    }
                    if (b.size() >= 3) {
                        back.add(new Polygon(b, poly.plane));
                    }
                }
            }
        }
    }

    /** 一个凸多边形（三角形或切出来的多边形）✓。 */
    public static final class Polygon {
        public final List<Vertex> vertices;
        public final Plane plane;

        public Polygon(final List<Vertex> vertices, final Plane plane) {
            this.vertices = Collections.unmodifiableList(new ArrayList<>(vertices));
            this.plane = plane;
        }

        public Polygon flipped() {
            final List<Vertex> out = new ArrayList<>(vertices.size());
            for (int i = vertices.size() - 1; i >= 0; i--) {
                out.add(vertices.get(i).flip());
            }
            return new Polygon(out, plane.flipped());
        }
    }

    /** BSP 节点（csg.js 的 Node）✓。 */
    public static final class Node {
        private Plane plane;
        private Node front;
        private Node back;
        private final List<Polygon> polygons = new ArrayList<>();

        public Node() {
        }

        public Node(final List<Polygon> polys) {
            build(polys);
        }

        public void invert() {
            for (int i = 0; i < polygons.size(); i++) {
                polygons.set(i, polygons.get(i).flipped());
            }
            if (plane != null) {
                plane = plane.flipped();
            }
            if (front != null) {
                front.invert();
            }
            if (back != null) {
                back.invert();
            }
            final Node tmp = front;
            front = back;
            back = tmp;
        }

        /** 去掉本树里被 {@code bsp} 剪掉的部分 ✓。 */
        public List<Polygon> clipPolygons(final List<Polygon> polys) {
            if (plane == null) {
                return new ArrayList<>(polys);
            }
            List<Polygon> f = new ArrayList<>();
            List<Polygon> b = new ArrayList<>();
            for (final Polygon p : polys) {
                plane.splitPolygon(p, f, b, f, b);
            }
            if (front != null) {
                f = front.clipPolygons(f);
            }
            // ⚠ 移植关键点（很容易写错 ✓）：csg.js 在**没有 back 子树**时把 back 侧的多边形
            //   **直接丢弃** ✓ —— 因为"平面背后没有实体"就意味着那些多边形在实体之外 ✓
            //   我第一版写成了 `back == null ? b : ...`（保留 b）⇒ 布尔结果永远为空 ✗
            b = back == null ? new ArrayList<>() : back.clipPolygons(b);
            f.addAll(b);
            return f;
        }

        public void clipTo(final Node bsp) {
            polygons.clear();
            polygons.addAll(bsp.clipPolygons(polygons));
            if (front != null) {
                front.clipTo(bsp);
            }
            if (back != null) {
                back.clipTo(bsp);
            }
        }

        public List<Polygon> allPolygons() {
            final List<Polygon> out = new ArrayList<>(polygons);
            if (front != null) {
                out.addAll(front.allPolygons());
            }
            if (back != null) {
                out.addAll(back.allPolygons());
            }
            return out;
        }

        public void build(final List<Polygon> polys) {
            if (polys.isEmpty()) {
                return;
            }
            if (plane == null) {
                plane = polys.get(0).plane;
            }
            final List<Polygon> f = new ArrayList<>();
            final List<Polygon> b = new ArrayList<>();
            for (final Polygon p : polys) {
                plane.splitPolygon(p, polygons, polygons, f, b);
            }
            if (!f.isEmpty()) {
                if (front == null) {
                    front = new Node();
                }
                front.build(f);
            }
            if (!b.isEmpty()) {
                if (back == null) {
                    back = new Node();
                }
                back.build(b);
            }
        }
    }

    // =================== 三个布尔运算（csg.js 的 CSG.union/subtract/intersect）===================

    public static List<Polygon> union(final List<Polygon> a, final List<Polygon> b) {
        final Node aNode = new Node(a);
        final Node bNode = new Node(b);
        aNode.clipTo(bNode);
        bNode.clipTo(aNode);
        bNode.invert();
        bNode.clipTo(aNode);
        bNode.invert();
        aNode.build(bNode.allPolygons());
        return aNode.allPolygons();
    }

    public static List<Polygon> subtract(final List<Polygon> a, final List<Polygon> b) {
        final Node aNode = new Node(a);
        final Node bNode = new Node(b);
        aNode.invert();
        aNode.clipTo(bNode);
        bNode.clipTo(aNode);
        bNode.invert();
        bNode.clipTo(aNode);
        bNode.invert();
        aNode.build(bNode.allPolygons());
        aNode.invert();
        return aNode.allPolygons();
    }

    public static List<Polygon> intersect(final List<Polygon> a, final List<Polygon> b) {
        final Node aNode = new Node(a);
        final Node bNode = new Node(b);
        aNode.invert();
        bNode.clipTo(aNode);
        bNode.invert();
        aNode.clipTo(bNode);
        bNode.clipTo(aNode);
        aNode.build(bNode.allPolygons());
        aNode.invert();
        return aNode.allPolygons();
    }

    // =================== 小工具 ===================

    static V3 sub(final V3 a, final V3 b) {
        return new V3(a.x() - b.x(), a.y() - b.y(), a.z() - b.z());
    }

    static V3 cross(final V3 a, final V3 b) {
        return new V3(a.y() * b.z() - a.z() * b.y(),
                a.z() * b.x() - a.x() * b.z(),
                a.x() * b.y() - a.y() * b.x());
    }

    static double dot(final V3 a, final V3 b) {
        return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
    }
}
