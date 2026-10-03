package com.ovo.sablestopnow.client;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * 模型描边的纯几何判定（不碰 {@code Level}、不碰渲染，因此可以在离线 harness 里直接验证）。
 *
 * <h2>为什么单独抽出来</h2>
 * 「哪些棱该画」这套规则出过一次<b>静默失效</b>：内部接缝的判定里把方块偏移也算到了面法线上
 * （写成「我们顶面 y=1，邻居的顶面在它的局部坐标里是 y=0」），于是判据永远不成立、
 * 一条接缝都没被剔除，整个平坦表面被网格线切碎 —— 用户实测描述成「糊成一片」。
 *
 * <p>这类错误的共同点是：<b>看起来在工作</b>（照样画出线来了），所以只能靠断言钉住。
 * 把判据抽成纯函数之后，{@code tools/OutlineCheck.java} 就能在没有游戏的情况下验证它。
 */
public final class OutlineGeometry {

    /** 坐标比较容差（格）。 */
    public static final double EPS = 1.0e-4;

    private OutlineGeometry() {
    }

    /** 面法线轴之外的另外两条轴。 */
    public static Direction.Axis[] perpendicularAxes(final Direction.Axis axis) {
        return switch (axis) {
            case X -> new Direction.Axis[] {Direction.Axis.Y, Direction.Axis.Z};
            case Y -> new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z};
            case Z -> new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Y};
        };
    }

    /**
     * 两个盒子在 {@code direction} 方向上的面是否<b>共面</b>。
     *
     * <p>判据只有一条：面法线方向上的坐标相同。⚠ 相邻方块是沿<b>面内</b>的某条轴平移过去的，
     * 面法线方向的坐标<b>不会变</b>（我的顶面在 y=1，东边那个方块的顶面同样在 y=1）。
     * 把方块偏移（±1）算进面法线坐标，判据就永远不成立。
     */
    public static boolean coplanarFace(final AABB box, final AABB neighbour,
                                       final Direction direction) {
        final Direction.Axis a = direction.getAxis();
        final boolean positive = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        final double ourPlane = positive ? box.max(a) : box.min(a);
        final double otherPlane = positive ? neighbour.max(a) : neighbour.min(a);
        return Math.abs(otherPlane - ourPlane) <= EPS;
    }

    /**
     * 邻居的这个盒子是否<b>完全挡住</b> {@code box} 在 {@code direction} 方向上的那一面。
     *
     * <p>两件事都要满足：
     * <ol>
     *   <li><b>贴到同一个平面</b>——邻居里靠里的那个盒子不该挡住我们的面
     *       （顶面在 y=1，上面那个方块底部在 y=0.5 时并没有挡住它）；</li>
     *   <li>在面内的两条轴上<b>覆盖满</b>我们的面。</li>
     * </ol>
     */
    public static boolean coversFace(final AABB neighbour, final AABB box, final Direction direction) {
        final Direction.Axis a = direction.getAxis();
        // 我们的面在邻居局部坐标里的位置：正方向的面 → 邻居的 0；负方向的面 → 邻居的 1
        final double plane = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 0.0 : 1.0;
        if (neighbour.min(a) > plane + EPS || neighbour.max(a) < plane - EPS) {
            return false;
        }
        final Direction.Axis[] pq = perpendicularAxes(a);
        final Direction.Axis p = pq[0];
        final Direction.Axis q = pq[1];
        return neighbour.min(p) <= box.min(p) + EPS && neighbour.max(p) >= box.max(p) - EPS
                && neighbour.min(q) <= box.min(q) + EPS && neighbour.max(q) >= box.max(q) - EPS;
    }
}
