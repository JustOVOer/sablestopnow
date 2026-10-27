package com.ovo.sablestopnow.sketch;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;

/**
 * **圆角的几何近似** ✓（item 8）。
 *
 * <p>我们的实体模型是**盒子分解**的（渲染与碰撞都吃盒子）✓ 所以圆角也做成盒子：
 * 在凸棱处把"棱角方块减去四分之一圆"的那块挖掉 —— 用 **N 级阶梯盒子**近似四分之一圆柱，
 * 阶梯越细越像圆角（默认 6 级 ≈ 肉眼可接受，比直角显著改善）✓
 *
 * <p>为什么不做真圆柱面 ✗：碰撞只吃盒子，渲染走 CSG（三角汤）——
 * 真圆柱面要么在碰撞侧再近似一次（两套几何，必然漂移），要么彻底换 B-rep（远超本轮范围）。
 * 阶梯盒子**渲染和碰撞天然一致**，且沿棱方向和真实圆角一样是连续的 ✓
 *
 * <p>范围（第一版）：**轴对齐的棱**（世界轴 ±X/±Y/±Z）才支持 ✓ —— 我们的拉伸体绝大多数棱都是这种；
 * 斜棱返回空（特性保持"未生效"，UI 会提示）✓
 */
public final class FilletGeom {

    private FilletGeom() {
    }

    /** 阶梯级数（越大越圆）✓ */
    public static final int STEPS = 6;

    /**
     * 给一条棱算"要挖掉"的盒子 ✓（**局部坐标**，AABB）。
     *
     * @param a       棱的端点（局部）
     * @param b       棱的另一端
     * @param n1      相邻面 1 的**外法线**
     * @param n2      相邻面 2 的外法线
     * @param radius  圆角半径（>0）
     * @param convex  true = 凸棱（挖角）；false = 凹棱（第一版不处理，返回空）
     * @return 盒子列表 {@code {x0,y0,z0,x1,y1,z1}}；不支持时返回空
     */
    public static List<double[]> boxesFor(final Vector3dc a, final Vector3dc b,
                                          final Vector3dc n1, final Vector3dc n2,
                                          final double radius, final boolean convex) {
        final List<double[]> out = new ArrayList<>();
        if (!convex || radius <= 1.0e-6) {
            return out;
        }
        final Vector3d dir = new Vector3d(b).sub(a);
        final double len = dir.length();
        if (len < 1.0e-6) {
            return out;
        }
        dir.div(len);
        // 必须是轴对齐的棱 + 两个法线也轴对齐，才做（否则几何不是 AABB，跳过 ✓）
        if (axisOf(dir) < 0 || axisOf(n1) < 0 || axisOf(n2) < 0) {
            return out;
        }
        final int ae = axisOf(dir);
        final int a1 = axisOf(n1);
        final int a2 = axisOf(n2);
        if (a1 == a2 || a1 == ae || a2 == ae) {
            return out;
        }
        // 棱沿 ae，两个法线沿 a1/a2 ⇒ 在 (a1,a2) 平面里是直角 ✓
        final double[] origin = {a.x(), a.y(), a.z()};
        final double sign1 = component(n1, a1) >= 0 ? 1.0 : -1.0;
        final double sign2 = component(n2, a2) >= 0 ? 1.0 : -1.0;
        final double edgeMin = Math.min(component(a, ae), component(b, ae));
        final double edgeMax = Math.max(component(a, ae), component(b, ae));
        // 阶梯：t 从 0（棱角）到 radius（内侧）
        for (int i = 0; i < STEPS; i++) {
            final double t0 = radius * i / STEPS;
            final double t1 = radius * (i + 1) / STEPS;
            // 这一步要挖到的"外侧深度"：s = r - sqrt(r^2 - (r - t)^2)
            final double tMid = (t0 + t1) * 0.5;
            final double inner = Math.max(0.0, radius - tMid);
            final double s = radius - Math.sqrt(Math.max(0.0, radius * radius - inner * inner));
            if (s <= 1.0e-9) {
                continue;
            }
            final double[] lo = origin.clone();
            final double[] hi = origin.clone();
            lo[ae] = edgeMin;
            hi[ae] = edgeMax;
            // a1 方向：从 (r - t1) 到 r（外侧）；a2 方向：从 (r - s) 到 r
            lo[a1] = component(a, a1) + sign1 * (radius - t1);
            hi[a1] = component(a, a1) + sign1 * radius;
            lo[a2] = component(a, a2) + sign2 * (radius - s);
            hi[a2] = component(a, a2) + sign2 * radius;
            final double[] box = {
                Math.min(lo[0], hi[0]), Math.min(lo[1], hi[1]), Math.min(lo[2], hi[2]),
                Math.max(lo[0], hi[0]), Math.max(lo[1], hi[1]), Math.max(lo[2], hi[2]),
            };
            if (box[3] - box[0] > 1.0e-6 && box[4] - box[1] > 1.0e-6 && box[5] - box[2] > 1.0e-6) {
                out.add(box);
            }
        }
        return out;
    }

    /** 向量是否轴对齐 ⇒ 返回轴下标（0/1/2），否则 -1 ✓ */
    private static int axisOf(final Vector3dc v) {
        final double ax = Math.abs(v.x());
        final double ay = Math.abs(v.y());
        final double az = Math.abs(v.z());
        final double max = Math.max(ax, Math.max(ay, az));
        final double sum = ax + ay + az;
        if (max < 1.0e-9 || Math.abs(sum - max) > 1.0e-6) {
            return -1;
        }
        return ax >= max - 1.0e-9 ? 0 : (ay >= max - 1.0e-9 ? 1 : 2);
    }

    private static double component(final Vector3dc v, final int axis) {
        return axis == 0 ? v.x() : (axis == 1 ? v.y() : v.z());
    }
}
