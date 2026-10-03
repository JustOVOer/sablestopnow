package com.ovo.sablestopnow.sketch;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * 草图平面：一个原点 + 两个正交单位轴。
 *
 * <h2>坐标系</h2>
 * 平面本身活在**子结构的 plot 局部系**里（和 {@code MateRef} / 施力点同一套），
 * 这样结构移动、旋转、缩放时，草图和它一起动，不需要每帧重算。
 *
 * <p>平面内的二维坐标记作 {@code (u, v)}：{@code u} 沿 {@link #axisU()}，
 * {@code v} 沿 {@link #axisV()}，两者都在原点处。约束求解器只认这两个数，
 * 它完全不知道三维世界的存在 —— 这是整个设计里最重要的一条边界。
 *
 * <h2>为什么存轴而不是法线</h2>
 * 手性/朝向由两个轴唯一确定（法线 = u × v），存法线反而要多一次「挑一个垂直方向」
 * 的任意选择，那个任意选择会让同一个基准面在不同代码路径里得到不同的 u 轴，
 * 表现就是「同一个草图重开之后线全mirror了」。
 *
 * <p>⚠ 这个类**刻意只依赖 JOML**，不引用任何 Minecraft 类，因此可以用
 * {@code tools/SketchModelCheck.java} 脱离游戏直接验证。
 */
public record SketchPlane(Vector3d origin, Vector3d axisU, Vector3d axisV) {

    /** 三个基准面相对子结构局部系的方向（与 SolidWorks 的前/上/右对应）。 */
    public enum Base {
        /**
         * 前视基准面：从 -Z 看向 +Z。u = +X，v = +Y。
         */
        FRONT(new Vector3d(1, 0, 0), new Vector3d(0, 1, 0)),
        /**
         * 上视基准面：从 +Y 往下看。u = +X，v = +Z（屏幕上的"上"是 -Z 方向的反面，
         * 这里取 +Z 是为了让 u × v = -Y 指向观察者）。
         */
        TOP(new Vector3d(1, 0, 0), new Vector3d(0, 0, 1)),
        /**
         * 右视基准面：从 +X 看向 -X。u = +Z，v = +Y。
         */
        RIGHT(new Vector3d(0, 0, 1), new Vector3d(0, 1, 0));

        private final Vector3d u;
        private final Vector3d v;

        Base(final Vector3d u, final Vector3d v) {
            this.u = u;
            this.v = v;
        }

        public Vector3d axisU() {
            return new Vector3d(u);
        }

        public Vector3d axisV() {
            return new Vector3d(v);
        }
    }

    public SketchPlane {
        origin = new Vector3d(origin);
        axisU = new Vector3d(axisU).normalize();
        axisV = new Vector3d(axisV).normalize();
    }

    /** 以某个局部点为原点的基准面。 */
    public static SketchPlane base(final Base base, final Vector3dc origin) {
        return new SketchPlane(new Vector3d(origin), base.axisU(), base.axisV());
    }

    /**
     * 贴在一个方块面上的草图平面。
     *
     * <p>取一个与面法线不平行的参考方向来定 u 轴：优先用"世界上方"投影到面内，
     * 这样贴地板的草图 u/v 与俯视图一致；法线本身竖直时改用 +X，
     * 否则投影会退化成零向量、轴就定不下来了。
     *
     * @param faceCenter 面的中心（plot 局部系）
     * @param normal     面的外法线（会被归一化）
     */
    public static SketchPlane onFace(final Vector3dc faceCenter, final Vector3dc normal) {
        final Vector3d n = new Vector3d(normal).normalize();
        final Vector3d up = Math.abs(n.y) > 0.9 ? new Vector3d(1, 0, 0) : new Vector3d(0, 1, 0);
        // u = up × n（面内、水平），v = n × u（面内、"上"）
        final Vector3d u = up.cross(n, new Vector3d()).normalize();
        final Vector3d v = n.cross(u, new Vector3d()).normalize();
        return new SketchPlane(new Vector3d(faceCenter), u, v);
    }

    /** 平面法线（单位向量，= u × v）。 */
    public Vector3d normal() {
        return axisU.cross(axisV, new Vector3d()).normalize();
    }

    /** 平面内坐标 → plot 局部坐标。 */
    public Vector3d toLocal(final double u, final double v) {
        return axisU.mul(u, new Vector3d()).fma(v, axisV, new Vector3d()).add(origin);
    }

    /**
     * plot 局部坐标 → 平面内坐标（正交投影）。
     *
     * <p>传出的 {@code out} 是 {@code (u, v)}，用 {@code Vector3d} 装只是省一个类型；
     * {@code z} 分量恒为 0。
     */
    public Vector3d toPlane(final Vector3dc local, final Vector3d out) {
        final Vector3d d = new Vector3d(local).sub(origin);
        return out.set(d.dot(axisU), d.dot(axisV), 0.0);
    }

    /** 点到平面的有符号距离（沿法线）。拾取时用来判断"这个点是否在这个平面上"。 */
    public double signedDistance(final Vector3dc local) {
        return new Vector3d(local).sub(origin).dot(normal());
    }

    /**
     * 射线与平面求交的参数 {@code t}（交点 = {@code rayOrigin + rayDir · t}）。
     *
     * <p>返回 {@code NaN} 表示**没有可用的交点**，有两种情况：
     * <ul>
     *   <li>射线与平面平行（分母接近 0）——数学上无交点；</li>
     *   <li>交点在射线**背后**（{@code t < 0}）——数学上有交点，但玩家看不到它，
     *       当成"打不到"才符合直觉。否则鼠标朝反方向也能画出线来。</li>
     * </ul>
     *
     * <p>{@code rayDir} 不必是单位向量，但必须是单位向量时 {@code t} 才等于距离。
     */
    public double rayParameter(final Vector3dc rayOrigin, final Vector3dc rayDir) {
        final Vector3d n = normal();
        final double denom = new Vector3d(rayDir).dot(n);
        // 平行判据用相对阈值：法线是单位长，所以 denom 就是夹角余弦，1e-9 约等于"完全平行"
        if (Math.abs(denom) < 1.0e-9) {
            return Double.NaN;
        }
        final double t = new Vector3d(origin).sub(rayOrigin).dot(n) / denom;
        if (t < 0.0) {
            return Double.NaN;
        }
        return t;
    }

    /** 射线与平面的交点换算成平面内 (u,v)；打不到返回 null。 */
    @Nullable
    public Vector3d rayIntersect(final Vector3dc rayOrigin, final Vector3dc rayDir) {
        final double t = rayParameter(rayOrigin, rayDir);
        if (Double.isNaN(t)) {
            return null;
        }
        final Vector3d hit = new Vector3d(rayDir).mul(t).add(rayOrigin);
        return toPlane(hit, new Vector3d());
    }

    /** 两个平面是否重合到可以认为是同一个草图的承载面。 */
    public boolean samePlaneAs(final SketchPlane other) {
        if (origin.distance(other.origin) > 1.0e-6) {
            return false;
        }
        return axisU.dot(other.axisU) > 1.0 - 1.0e-9 && axisV.dot(other.axisV) > 1.0 - 1.0e-9;
    }
}
