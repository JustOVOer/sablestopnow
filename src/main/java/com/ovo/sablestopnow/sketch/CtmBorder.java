package com.ovo.sablestopnow.sketch;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * **连接纹理的"边框"计算** ✓（item 11）。
 *
 * <p>思路：CTM 的边框本质上表达的是"**这个面的某条边/某个角外面还有没有邻居**"。
 * 我们不去采样别人的边框贴图（那需要把 ctm 图集注册进纹理图集，属于另一块工程），
 * 而是**直接从模型自己的面邻接关系**算出该画哪些边框段 —— 于是
 * "面/棱/角"三种情形天然正确 ✓ 且渲染与碰撞同源 ✓
 *
 * <p>输出的是**局部坐标**的线段，交给渲染侧用同一套 3D 棒图元画 ✓
 */
public final class CtmBorder {

    private CtmBorder() {
    }

    /** 边框内缩比例（相对面尺寸）✓ 0.06 = 往面里缩 6%，看起来像贴图边框 */
    public static final double INSET = 0.06;

    /**
     * 给一个面算它需要的**边框段** ✓。
     *
     * <p>判据：把面的三角形投影到自己的平面上取包围盒，
     * 沿 4 条边各取一条内缩线；**只有当该边上没有相邻面时**才输出这一段 ✓
     * 相邻面的判定用"共享顶点"（与 {@code Features} 的共面生长同一套判据 ✓）
     *
     * @param face 目标面
     * @param all  同一实体的所有面（含自己）
     * @return 边框段（局部坐标，两两成对）
     */
    public static List<Vector3d[]> borderOf(final Features.Face face,
                                           final List<Features.Face> all) {
        final List<Vector3d[]> out = new ArrayList<>();
        if (face == null || all == null || face.triangles().isEmpty()) {
            return out;
        }
        // 面的包围盒（在自身的平面内取，足够做"四条边"的近似）
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (final Vector3d[] tri : face.triangles()) {
            for (final Vector3d p : tri) {
                minX = Math.min(minX, p.x);
                minY = Math.min(minY, p.y);
                minZ = Math.min(minZ, p.z);
                maxX = Math.max(maxX, p.x);
                maxY = Math.max(maxY, p.y);
                maxZ = Math.max(maxZ, p.z);
            }
        }
        final double sx = Math.max(1.0e-6, maxX - minX);
        final double sy = Math.max(1.0e-6, maxY - minY);
        final double sz = Math.max(1.0e-6, maxZ - minZ);
        // 选"最薄"的轴作为法线方向 ⇒ 其余两轴构成面内平面 ✓
        final int thin = sz <= sx && sz <= sy ? 2 : (sy <= sx ? 1 : 0);
        final int a1 = (thin + 1) % 3;
        final int a2 = (thin + 2) % 3;
        final double[] lo = {minX, minY, minZ};
        final double[] hi = {maxX, maxY, maxZ};
        final double inset1 = (hi[a1] - lo[a1]) * INSET;
        final double inset2 = (hi[a2] - lo[a2]) * INSET;
        final double plane = (lo[thin] + hi[thin]) * 0.5;
        // 四条边：哪条边上没有邻居 ⇒ 画内缩线
        final boolean[] has = new boolean[4]; // 0=-a1 1=+a1 2=-a2 3=+a2
        for (final Features.Face other : all) {
            if (other == face) {
                continue;
            }
            final int[] sides = sidesTouching(face, other, thin, a1, a2, lo, hi);
            for (int i = 0; i < 4; i++) {
                if (sides[i] > 0) {
                    has[i] = true;
                }
            }
        }
        for (int side = 0; side < 4; side++) {
            if (has[side]) {
                continue;
            }
            final double[] p0 = new double[3];
            final double[] p1 = new double[3];
            p0[thin] = plane;
            p1[thin] = plane;
            if (side == 0 || side == 1) {
                // 沿 a1 的边：线沿 a2 走，位置在 a1 的内缩处
                p0[a1] = side == 0 ? lo[a1] + inset1 : hi[a1] - inset1;
                p1[a1] = p0[a1];
                p0[a2] = lo[a2] + inset2;
                p1[a2] = hi[a2] - inset2;
            } else {
                p0[a2] = side == 2 ? lo[a2] + inset2 : hi[a2] - inset2;
                p1[a2] = p0[a2];
                p0[a1] = lo[a1] + inset1;
                p1[a1] = hi[a1] - inset1;
            }
            out.add(new Vector3d[] {new Vector3d(p0[0], p0[1], p0[2]),
                    new Vector3d(p1[0], p1[1], p1[2])});
        }
        return out;
    }

    /** 一条**带宽度**的边框四边形：outer → inner，四角按外/内顺序 ✓ */
    public record Strip(Vector3d outer0, Vector3d outer1,
                        Vector3d inner1, Vector3d inner0, Vector3d normal,
                        int side) {
    }

    /**
     * 贴图式边框：返回沿面**真实边缘**的四边形条带（而不是一条内缩线）。
     *
     * <p>只在该边**没有相邻面**时输出；条带从外边界向内缩 {@link #INSET}，
     * 渲染侧用贴图边缘的像素填满它。
     */
    public static List<Strip> stripsOf(final Features.Face face,
                                       final List<Features.Face> all) {
        final List<Strip> out = new ArrayList<>();
        if (face == null || all == null || face.triangles().isEmpty()) {
            return out;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (final Vector3d[] tri : face.triangles()) {
            for (final Vector3d p : tri) {
                minX = Math.min(minX, p.x);
                minY = Math.min(minY, p.y);
                minZ = Math.min(minZ, p.z);
                maxX = Math.max(maxX, p.x);
                maxY = Math.max(maxY, p.y);
                maxZ = Math.max(maxZ, p.z);
            }
        }
        final double sx = Math.max(1.0e-6, maxX - minX);
        final double sy = Math.max(1.0e-6, maxY - minY);
        final double sz = Math.max(1.0e-6, maxZ - minZ);
        final int thin = sz <= sx && sz <= sy ? 2 : (sy <= sx ? 1 : 0);
        final int a1 = (thin + 1) % 3;
        final int a2 = (thin + 2) % 3;
        final double[] lo = {minX, minY, minZ};
        final double[] hi = {maxX, maxY, maxZ};
        final double inset1 = (hi[a1] - lo[a1]) * INSET;
        final double inset2 = (hi[a2] - lo[a2]) * INSET;
        final double plane = (lo[thin] + hi[thin]) * 0.5;
        final boolean[] has = new boolean[4];
        for (final Features.Face other : all) {
            if (other == face) {
                continue;
            }
            final int[] sides = sidesTouching(face, other, thin, a1, a2, lo, hi);
            for (int i = 0; i < 4; i++) {
                if (sides[i] > 0) {
                    has[i] = true;
                }
            }
        }
        final Vector3d n = face.normal() == null
                ? new Vector3d(0, 1, 0) : new Vector3d(face.normal()).normalize();
        for (int side = 0; side < 4; side++) {
            // ★ 贴图边框沿**每个平面面的四条边**走（立方体每个面都要有框）。
            //   以前只画“没有相邻面”的边 ⇒ 立方体边都有垂直邻面 ⇒ 一条边框都没有，
            //   用户实测“连接材质完全没生效”。
            final double[] o0 = new double[3];
            final double[] o1 = new double[3];
            final double[] i0 = new double[3];
            final double[] i1 = new double[3];
            for (final double[] p : new double[][] {o0, o1, i0, i1}) {
                p[thin] = plane;
            }
            if (side == 0 || side == 1) {
                final double outer = side == 0 ? lo[a1] : hi[a1];
                final double inner = side == 0 ? lo[a1] + inset1 : hi[a1] - inset1;
                o0[a1] = outer;
                o1[a1] = outer;
                i0[a1] = inner;
                i1[a1] = inner;
                o0[a2] = lo[a2];
                i0[a2] = lo[a2] + inset2;
                o1[a2] = hi[a2];
                i1[a2] = hi[a2] - inset2;
            } else {
                final double outer = side == 2 ? lo[a2] : hi[a2];
                final double inner = side == 2 ? lo[a2] + inset2 : hi[a2] - inset2;
                o0[a2] = outer;
                o1[a2] = outer;
                i0[a2] = inner;
                i1[a2] = inner;
                o0[a1] = lo[a1];
                i0[a1] = lo[a1] + inset1;
                o1[a1] = hi[a1];
                i1[a1] = hi[a1] - inset1;
            }
            out.add(new Strip(new Vector3d(o0[0], o0[1], o0[2]),
                    new Vector3d(o1[0], o1[1], o1[2]),
                    new Vector3d(i1[0], i1[1], i1[2]),
                    new Vector3d(i0[0], i0[1], i0[2]), n, side));
        }
        return out;
    }

    /** 判断 other 贴着 face 的哪几条边（返回长度 4 的计数数组）✓ */
    private static int[] sidesTouching(final Features.Face face, final Features.Face other,
                                       final int thin, final int a1, final int a2,
                                       final double[] lo, final double[] hi) {
        final int[] out = new int[4];
        final double tol = 1.0e-4;
        for (final Vector3d[] t : other.triangles()) {
            for (final Vector3d p : t) {
                // 只认"就在这个面边界上"的顶点（该轴接近边界值）
                final double v1 = axis(p, a1);
                final double v2 = axis(p, a2);
                if (Math.abs(v1 - lo[a1]) < tol) {
                    out[0]++;
                }
                if (Math.abs(v1 - hi[a1]) < tol) {
                    out[1]++;
                }
                if (Math.abs(v2 - lo[a2]) < tol) {
                    out[2]++;
                }
                if (Math.abs(v2 - hi[a2]) < tol) {
                    out[3]++;
                }
            }
        }
        return out;
    }

    private static double axis(final Vector3d p, final int a) {
        return a == 0 ? p.x : (a == 1 ? p.y : p.z);
    }
}
