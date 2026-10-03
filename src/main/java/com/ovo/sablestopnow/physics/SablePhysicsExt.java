package com.ovo.sablestopnow.physics;

import java.util.ArrayList;
import java.util.List;

/**
 * 我们自己给 Sable 原生库加的**凸包碰撞**入口。
 *
 * <h2>为什么需要它</h2>
 *
 * Sable 的碰撞形状只有两类：体素碰撞体（逐方块 AABB 并集）和方块自身的 {@code VoxelShape}。
 * 两者都只能用**轴对齐盒子**表达几何，所以曲面必须打"楼梯"。实测（{@code depends/collision-bench}，
 * φ12×高3 圆柱**侧面落地**）：
 *
 * <table border="1">
 *   <tr><th>表示</th><th>ms/步</th><th>接触对</th><th>陷地</th><th>弹跳</th></tr>
 *   <tr><td>体素楼梯（现状）</td><td>8.561</td><td>12593</td><td>0.063</td><td>9 次</td></tr>
 *   <tr><td><b>凸包（本类）</b></td><td><b>0.008</b></td><td><b>1</b></td><td><b>0.001</b></td><td><b>1 次</b></td></tr>
 * </table>
 *
 * <h2>原生实现</h2>
 *
 * 在 {@code depends/sable-main/.../rapier/src/convex.rs}（**新增一个文件 + 一行 mod 声明**），
 * 复用 Sable **已有的 mount 机制**：目标刚体就是 {@code createKinematicContraption} 里用的那份
 * {@code sable_data.rigid_bodies}，所以调用方只需要它**已经有的** id。
 *
 * <h2>带孔的结构</h2>
 *
 * **凸包表示不了洞** —— 环面、管道这类结构必须做**凸分解**，然后**分多次调用**
 * {@link #attachConvex}（每块一个凸包）。{@code parry3d} 自带
 * {@code convex_decomposition}，将来可以直接在原生侧接上。
 */
public final class SablePhysicsExt {

    /**
     * 把那个原生库**显式注册到我们自己的类加载器**。
     *
     * <p>⚠⚠ **这里不能去加载 Sable 解压目录（{@code .sable/natives}）里那份** ——
     * 那是**原版**：Sable 每次启动都会用它自己 jar 里的 LZ4 包覆盖那个目录 ✗
     * （实测：覆盖后的文件 SHA256 与原始版**完全相同**，我们换进去的 1.63 MB 那份也没了 ✗）。
     *
     * <p>真正带凸包的是**我们自己编的那份**，由 {@link NativeLibrary} 从本模组 jar 里抽出来加载；
     * 而 Sable 自己的加载已经被 {@code Rapier3DNativeOverrideMixin} 在 HEAD 处**取消**掉了 ✓
     * —— 这样进程里只有**一份** Rapier 实例（两份 = 两个互不接触的物理世界 ✗）。
     */
    static {
        NativeLibrary.load();
    }

    private SablePhysicsExt() {
    }

    /**
     * 把一个凸包碰撞体挂到 {@code mountId} 对应的刚体上。
     *
     * @param scene        物理场景句柄（{@code Rapier3D.getSceneHandle(level)}）
     * @param mountId      目标刚体 id（子结构用 {@code Rapier3D.getID(sub)}；装置用它的装置 id）
     * @param points       扁平的 xyz 三元组，**刚体局部坐标**（相对父体质心）
     * @param friction     摩擦系数
     * @param borderRadius **圆角半径**；{@code > 0} 时用圆角凸包 ✓
     *                     —— 普通凸包是有限面数的多面体 ✗，停在**棱**上时接触法线跳变 ✓
     *                     表现为"浅陷但弹" ✓（实测圆柱面、球面都会 ✓）。
     *                     膨胀一个半径后棱变圆角、法线连续 ✓ 弹跳消失 ✓✓
     *                     取小值（如 1/32 方块 ✓ = 0.03125）—— 远小于碰撞精度 1/16 ✓ 看不出 ✓
     * @return 碰撞体句柄（{@code < 0} 表示失败）；交给 {@link #detachConvex} 移除
     */
    public static native long attachConvex(long scene, int mountId, double[] points,
                                           double friction, double borderRadius,
                                           double density);

    /** 移除 {@link #attachConvex} 返回的那个碰撞体。{@code packed < 0} 时不做任何事。 */
    public static native void detachConvex(long scene, long packed);

    /**
     * **诊断**：读该刚体在 Rapier 里的**真实状态**。
     *
     * <p>返回 {@code [质量, 位置x,y,z, 线速度x,y,z, 角速度x,y,z, 碰撞体数]}（长度 11）；
     * 刚体不存在时返回全 0。
     *
     * <p>⚠ **为什么不直接在原生侧打日志** ✗：我试过用 Rust 的 {@code log::warn!} ✓
     * 结果**一行都没进游戏日志** ✗ —— Sable 自己配了 {@code fern} 日志器 ✓
     * 它按**模块路径**过滤 ✗，我们的 target 被滤掉了 ✓
     * **教训：不要依赖别人的日志器** ✓ 把数字**返回**给调用方，用我们自己的 LOGGER 打 ✓✓
     *
     * <p>为什么要这个诊断：用户线索是"圆柱+球放在**只有一个方块**的物理结构上"✗ ——
     * 如果质量 ≈ 1.0 ✗ 而碰撞体有好几个 ✓，就证实了"**实体没有把质量贡献给刚体**"✓
     * （体素路给每个形状带了 {@code volume} 所以有质量 ✓，而凸包是 {@code density(0)} ✗）
     */
    public static native double[] bodyState(long scene, int mountId);

    /**
     * Strategy A: create a **dynamic body that owns the convex hull** (Rapier computes mass
     * and inertia from shape + density, so they are consistent by construction), registered
     * under {@code id} so it can be welded with addFixedConstraint.
     *
     * Bench (depends/collision-bench, 20Hz, edge contact):
     *   composed onto structure body -> rest height 0.50 instead of 1.50, sinks 1.0 block (bug)
     *   own body + fixed joint       -> rest height 1.499, separation 0.000, stable at 40:1
     *
     * @param points flat xyz triples, **relative to the body origin** (our hull points already
     *               are, since they are expressed in the structure COM frame and we place the
     *               new body at that same origin) - so pass pose (0,0,0, 0,0,0,1)
     * @param density mass = hull volume x density (use SOLID_DENSITY)
     * @return the body id (>= 0) or -1 on failure
     */
    public static native long createConvexBody(long scene, int id, double[] points,
                                               double density, double[] pose);

    /**
     * 生成一个**正棱柱**（圆柱的多边形近似）的凸包顶点。
     *
     * <p>{@code segments} 由**弦高容差**决定：误差 = {@code r·(1-cos(π/n))}。
     * 半径 6 时 n=24 → 0.051（优于 1/16），n=32 → 0.029。见 {@link #segmentsFor}。
     *
     * @param radius    半径
     * @param height    高（沿 y，居中于 0）
     * @param segments  侧面段数
     * @param centreY   棱柱中心的 y（刚体局部坐标）
     */
    public static double[] cylinderHull(final double radius, final double height,
                                        final int segments, final double centreY) {
        final List<Double> pts = new ArrayList<>(segments * 2 * 3);
        final double half = height * 0.5;
        for (int i = 0; i < segments; i++) {
            final double a = 2.0 * Math.PI * i / segments;
            final double x = radius * Math.cos(a);
            final double z = radius * Math.sin(a);
            for (final double y : new double[]{-half, half}) {
                pts.add(x);
                pts.add(centreY + y);
                pts.add(z);
            }
        }
        final double[] out = new double[pts.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = pts.get(i);
        }
        return out;
    }

    /**
     * 按弦高容差求最少段数：{@code r·(1-cos(π/n)) <= tolerance}。
     *
     * <p>这就是渲染细分用的同一套判据 —— 几何容差只应有一处定义。
     */
    public static int segmentsFor(final double radius, final double tolerance) {
        if (radius <= 0.0 || tolerance <= 0.0 || tolerance >= radius) {
            return 8;
        }
        // 1 - cos(π/n) <= tol/r  →  n >= π / acos(1 - tol/r)
        final double n = Math.PI / Math.acos(Math.max(-1.0, 1.0 - tolerance / radius));
        return Math.max(8, (int) Math.ceil(n));
    }
}
