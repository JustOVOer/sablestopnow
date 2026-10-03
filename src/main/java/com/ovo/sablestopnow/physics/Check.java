package com.ovo.sablestopnow.physics;

import org.joml.Vector3dc;

/**
 * **坐标系防线** —— 用来防止"把某个量当成另一套坐标系的"这类错误。
 *
 * <h2>为什么要专门写一个类</h2>
 *
 * Sable 的实现**到处涉及不同的坐标系** ✓，而"用错系"是本项目**最高频、最难察觉**的一类 bug ✗：
 * 它在数值上不报错 ✓、类型上也看不出来 ✓，只表现为"手感不对"这类间接症状 ✗。
 *
 * <p>本项目已经实际发生过（全部由 {@code docs/dev-run-frames.md} 归档 ✓）：
 * <ul>
 *   <li>把 {@code massTracker.getCenterOfMass()}（<b>plot 绝对，~2.048e7</b> ✗）当成刚体局部坐标 ✓
 *       → 平行轴项里 {@code d ≈ 2e7} → 转动惯量被放大 <b>1e17 倍</b> ✗✗
 *       → 症状是"任意时候都难以旋转，一次弹跳只转 1~2 度"✓ <b>完全没有报错</b> ✗</li>
 *   <li>把质心当成"刚体位姿"传给 {@code createConvexBody} ✗ → 凸包跑到 754 万格外 ✓</li>
 *   <li>{@code ForcePointService} 里把世界系受力点送进 plot 局部的 {@code ForceTotal} ✗
 *       → 力矩被放大两千万倍 → 结构瞬间获得 ~1e8 m/s ✓</li>
 * </ul>
 *
 * <h2>本项目的坐标系事实（实测得出 ✓ 不要凭直觉 ✗）</h2>
 *
 * <table border="1">
 *   <caption>各来源属于哪套系</caption>
 *   <tr><th>量</th><th>系</th><th>量级</th></tr>
 *   <tr><td>{@code Rapier3D.getPose(level, id, out)}</td><td><b>世界</b> ✓</td><td>~10²–10³</td></tr>
 *   <tr><td>{@code massTracker.getCenterOfMass()}</td><td><b>plot 绝对</b> ✗</td><td><b>~2.048e7</b></td></tr>
 *   <tr><td>{@code massTracker.getInertiaTensor()}</td><td>绕自身质心 ✓</td><td>~10²–10⁴</td></tr>
 *   <tr><td>喂给 {@code attachConvex} 的 {@code points}</td><td><b>刚体局部</b> ✓</td><td>±数十</td></tr>
 *   <tr><td>Rapier 内部 collider 坐标</td><td><b>刚体局部，原点=质心</b> ✓</td><td>±数十</td></tr>
 *   <tr><td>{@code ForceTotal} 的 localForce/localTorque</td><td><b>plot 局部</b> ✓</td><td>—</td></tr>
 *   <tr><td>装置 {@code setLocalBounds}</td><td><b>方块单位 0..16</b> ✓</td><td>—</td></tr>
 *   <tr><td>Sable {@code collider.rs} 的 {@code WORLD_SIZE}</td><td>±3e7 ✓</td><td>—</td></tr>
 * </table>
 *
 * <p><b>最重要的一条</b>：Rapier 里**刚体坐标系的原点就是质心** ✓
 * （{@code marten/algo.rs}：collider 坐标 = {@code local − center_of_mass}；
 * {@code marten/collider.rs}：{@code local_com = Vec3::ZERO} ✓）。
 * 所以"跟踪器在<b>刚体局部系</b>里的质心"**恒为 (0,0,0)** ✓ ——
 * 拿它的 plot 绝对坐标去参与任何局部计算都是错的 ✗。
 *
 * <h2>用法</h2>
 *
 * 在**每个跨系边界**调一次断言 ✓（成本只有几次比较 ✓ 但能当场抓出用错系 ✗）：
 * <pre>
 *   Check.local(d, "parallel-axis arm");        // 局部量必须"小" ✓
 *   Check.plot(com, "tracker centre of mass");  // plot 绝对量必须"大" ✓
 *   Check.world(poseX, "body pose x");          // 世界量必须中等 ✓
 * </pre>
 *
 * <p>断言失败抛 {@link FrameViolation} ✓ —— 它继承 {@link RuntimeException} ✓
 * 所以**在开发期立刻可见** ✓，而在生产里也只是一个异常而不是静默的错误物理 ✗
 * （静默的错误物理才是最坏的 ✓ 它花了我们整整一个会话才找到 ✗）。
 */
public final class Check {

    private Check() {
    }

    /**
     * "局部量"的上限 ✓。局部量（刚体局部 / 装置方块单位）不会超过几千 ✓；
     * 而 plot 绝对量是 2e7 起步 ✓ —— 两者差 4 个数量级 ✓ 所以阈值取 1e4 很安全 ✓
     */
    public static final double LOCAL_MAX = 1.0e4;

    /** "plot 绝对量"的下限 ✓。plot 绝对坐标的绝对值恒在 1e6 以上 ✓ */
    public static final double PLOT_MIN = 1.0e6;

    /** "世界量"的合理上限 ✓。世界坐标不会到 1e6 ✓（否则就是 plot 绝对 ✗）*/
    public static final double WORLD_MAX = 1.0e6;

    /** 用错了坐标系。 */
    public static final class FrameViolation extends RuntimeException {
        public FrameViolation(final String message) {
            super(message);
        }
    }

    /** 断言这是一个**刚体局部 / 方块单位**量 ✓（|v| 必须小 ✓）。 */
    public static double local(final double v, final String what) {
        if (!Double.isFinite(v) || Math.abs(v) > LOCAL_MAX) {
            throw new FrameViolation(what + " 不是局部量：|" + v + "| > " + LOCAL_MAX
                    + " —— 很可能混进了 plot 绝对坐标（getCenterOfMass 返回的就是它 ✗）");
        }
        return v;
    }

    /** 断言这是一个**局部向量** ✓（三个分量都要小 ✓）。 */
    public static Vector3dc local(final Vector3dc v, final String what) {
        local(v.x(), what + ".x");
        local(v.y(), what + ".y");
        local(v.z(), what + ".z");
        return v;
    }

    /** 断言这是一个 **plot 绝对**量 ✓（|v| 必须大 ✓）。 */
    public static double plot(final double v, final String what) {
        if (!Double.isFinite(v) || Math.abs(v) < PLOT_MIN) {
            throw new FrameViolation(what + " 不像 plot 绝对量：|" + v + "| < " + PLOT_MIN
                    + " —— plot 绝对坐标应在 1e6 以上 ✓");
        }
        return v;
    }

    /** 断言这三个分量**同属一套系** ✓ —— 这是最容易出错的一步（混系 ✓）。 */
    public static void sameFrame(final Vector3dc a, final String nameA,
                                 final Vector3dc b, final String nameB) {
        final boolean aLocal = looksLocal(a);
        final boolean bLocal = looksLocal(b);
        final boolean aPlot = looksPlot(a);
        final boolean bPlot = looksPlot(b);
        if ((aLocal && bPlot) || (aPlot && bLocal)) {
            throw new FrameViolation("坐标系混用：" + nameA + " = (" + a.x() + "," + a.y() + ","
                    + a.z() + ") 与 " + nameB + " = (" + b.x() + "," + b.y() + "," + b.z()
                    + ") 明显不在同一套系 ✓ 先统一再相减 ✗");
        }
    }

    /** 看起来像局部量（三个分量都小 ✓）。 */
    public static boolean looksLocal(final Vector3dc v) {
        return Math.abs(v.x()) <= LOCAL_MAX && Math.abs(v.y()) <= LOCAL_MAX
                && Math.abs(v.z()) <= LOCAL_MAX;
    }

    /** 看起来像 plot 绝对量（至少一个分量 ≥ PLOT_MIN ✓）。 */
    public static boolean looksPlot(final Vector3dc v) {
        return Math.abs(v.x()) >= PLOT_MIN || Math.abs(v.y()) >= PLOT_MIN
                || Math.abs(v.z()) >= PLOT_MIN;
    }
}
