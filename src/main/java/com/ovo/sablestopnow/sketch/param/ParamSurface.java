package com.ovo.sablestopnow.sketch.param;

/**
 * **统一参数曲面**：所有曲面都只用三件事描述自己。
 *
 * <pre>
 *   evaluate(u,v) → 3D 点
 *   project(3D点) → (u,v)
 *   normal(u,v)   → 法线
 * </pre>
 *
 * <h2>为什么要这个抽象（用户 2026-10 的要求）</h2>
 * 用户的原话是"想要一个统一的获得 UV 的接口，再用一套统一的贴图方案，这样扩展性高也不易有 bug"。
 * 本接口就是那个"统一"：
 * <ul>
 *   <li><b>扩展性</b>：新增一种曲面 = 实现这 5 个方法 + 声明它偏好的映射模式
 *       （{@link #preferredMapping()}）。**渲染、碰撞、贴图管线一行都不用改**；</li>
 *   <li><b>不易有 bug</b>：UV 只有一个出口（{@link UvMapping#uv}），
 *       而 UV 的**来源**被限制成"曲面的自然参数"——见下面两条铁律。</li>
 * </ul>
 *
 * <h2>两条铁律（都是踩过坑换来的）</h2>
 * <b>① 渲染**不许**调 {@link #project}。</b>
 * 渲染的顶点是 {@code evaluate(u,v)} 生成的，**生成时就知道 (u,v)**，直接带着走即可。
 * {@code project} 在**轴线/极点上是多值的**（r=0 时角度任意），反算还有数值误差。
 * 它的正当用途是**碰撞体素化 / 拾取**——那些场景确实需要"给空间点求参数"。
 *
 * <p><b>② 参数是 (u,v) 的函数，不是位置的函数。</b>
 * 以前 {code Revolve.mesh} 里拿"**每条棱自己的半径**"去算角度方向的 UV，
 * 导致相邻两条棱（两个环）的 v 比例不同、贴图网格对不上，
 * 表现为"**离基准面越远畸变越大**"（用户实测反馈）。参数必须是**全局一致**的量。
 */
public interface ParamSurface {

    /** {@code (u,v) → 3D 点}，写入并返回 {@code out}（长度 3）。 */
    double[] evaluate(double u, double v, double[] out);

    /**
     * {@code 3D 点 → (u,v)}，写入 {@code out}（长度 2）。
     *
     * <p>⚠ **只用于碰撞/拾取**，不要用在渲染路径上（见接口注释铁律①）。
     */
    double[] project(double[] p, double[] out);

    /** {@code (u,v) → 单位法线}，写入 {@code out}（长度 3）。 */
    double[] normal(double u, double v, double[] out);

    /** u 的下界。 */
    double uMin();

    /** u 的上界。 */
    double uMax();

    /** v 的下界。 */
    double vMin();

    /** v 的上界。 */
    double vMax();

    /**
     * 这张面**自己知道**该怎么贴图。
     *
     * <p>这是"扩展性"的落点：调用方永远不问"你是什么形状"，
     * 只问"你偏好哪种映射"，于是**渲染里没有任何按形状分的分支**。
     */
    UvMapping.Mode preferredMapping();

    /**
     * 自然参数是否已经是**弧长量纲（方块）**。
     *
     * <p>是的话 {@link UvMapping.Mode#SURFACE} 直接除以每格方块数即可，尺度天然均匀；
     * 不是的话（例如纯角度）需要调用方给一个参考尺度。
     * 我们四种曲面的答案都是"是" —— 这正是"尺度统一"的来源。
     */
    default boolean paramsAreBlockScaled() {
        return true;
    }

    /** 一张面的坐标架（映射模式要用）。 */
    record Frame(double[] origin, double[] axisU, double[] axisV, double[] axisW) {

        public static Frame of(final double[] origin, final double[] u, final double[] v,
                               final double[] w) {
            return new Frame(origin, u, v, w);
        }
    }
}
