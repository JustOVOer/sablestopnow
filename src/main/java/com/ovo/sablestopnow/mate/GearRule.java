package com.ovo.sablestopnow.mate;

import net.minecraft.util.Mth;

/**
 * 齿轮配合的纯数学部分（同样不碰世界，可在离线 checker 里钉死）。
 *
 * <h2>语义（已与用户确认）</h2>
 * 按顺序选 4 条线段：<b>固定轴A、齿轮轴A、固定轴B、齿轮轴B</b>。
 * <ul>
 *   <li><b>齿轮轴</b> = 该齿轮的自转轴；</li>
 *   <li><b>固定轴</b> = 该齿轮所在机身/世界的参考轴（定零位与反向）；</li>
 *   <li><b>传动比由玩家手填齿数比</b> {@code z_A : z_B}（四条线只用来确定轴的位置与朝向），
 *       另有「反转」复选框。</li>
 * </ul>
 *
 * <pre>
 *   ratio = ± z_A / z_B                      （反转 ⇒ 取负）
 *   θ_B − θ_B⁰ = −ratio · (θ_A − θ_A⁰)
 * </pre>
 *
 * <h2>为什么约束的是「增量」而不是「位姿」</h2>
 * 齿轮啮合约束的是<b>角速度比</b>：两轴各自转到哪里不重要，重要的是「A 转了多少，B 就必须反向转
 * ratio 倍」。这正是虚拟刚体夹具表达不了的东西（夹具只能表达固定的相对位姿），
 * 所以齿轮配合在实现上同样走「每子步的运动学辅助」那条路。
 */
public final class GearRule {

    /** 齿数上限（防手滑输入天文数字）。 */
    public static final int MAX_TEETH = 4096;

    private GearRule() {
    }

    /**
     * 传动比。
     *
     * @param teethA   A 的齿数（&gt; 0）
     * @param teethB   B 的齿数（&gt; 0）
     * @param reversed 「反转」复选框
     * @return {@code ± z_A / z_B}；参数非法时返回 null（由调用方给出提示，不产生 NaN）
     */
    public static Double ratio(final int teethA, final int teethB, final boolean reversed) {
        if (!validTeeth(teethA) || !validTeeth(teethB)) {
            return null;
        }
        final double r = (double) teethA / (double) teethB;
        return reversed ? -r : r;
    }

    public static boolean validTeeth(final int teeth) {
        return teeth > 0 && teeth <= MAX_TEETH;
    }

    /**
     * 从动轴的目标角。
     *
     * @param startB  B 的初始角（度）
     * @param deltaA  A 相对初始角的增量（度）
     * @param ratio   {@link #ratio(int, int, boolean)} 的结果
     */
    public static double followerAngle(final double startB, final double deltaA, final double ratio) {
        return startB - ratio * deltaA;
    }

    /**
     * 从 {@code from} 转到 {@code to} 的最短角差（度，落在 (-180, 180]）。
     *
     * <p>必要性：角度是周期量。若直接沿长路径插值，跨过 ±180° 时会看到结构「绕了一整圈」，
     * 在物理上表现为一次巨大的角冲量（正是要避免的那种能量注入）。所有角差读取都必须走这里。
     */
    public static double shortestDelta(final double from, final double to) {
        return Mth.wrapDegrees(to - from);
    }

    /** 角增量（度）→ 弧度。物理 API 通常要弧度，转换只在这一个地方做，避免各处符号/单位写错。 */
    public static double toRadians(final double degrees) {
        return Math.toRadians(degrees);
    }

    /** 弧度 → 度。 */
    public static double toDegrees(final double radians) {
        return Math.toDegrees(radians);
    }

    /**
     * 相对某个参考系的绕轴自转角（度）。
     *
     * <p>做法：把「机身参考方向」（固定轴方向）与「齿轮标记方向」（齿轮轴方向）都投影到
     * 垂直于齿轮轴的平面上，取两者夹角。齿轮轴与固定轴平行时这个角是退化的（投影长度≈0），
     * 此时返回 null —— 调用方应当提示玩家「固定轴与齿轮轴不能平行」，而不是给一个抖动值。
     *
     * @param gearAxis        齿轮轴方向（单位向量）
     * @param fixedAxis       固定轴方向（单位向量）
     * @param referenceInPlane 机身上一个与齿轮轴垂直的参考方向（单位向量）
     */
    public static Double spinAngle(final double[] gearAxis, final double[] fixedAxis,
                                   final double[] referenceInPlane) {
        final double[] projected = projectPerpendicular(fixedAxis, gearAxis);
        if (projected == null) {
            return null;
        }
        final double[] reference = projectPerpendicular(referenceInPlane, gearAxis);
        if (reference == null) {
            return null;
        }
        // atan2(|a×b| 的有符号分量, a·b)：单纯用 acos 会丢失象限，转半圈就分不清正负
        final double dot = projected[0] * reference[0] + projected[1] * reference[1] + projected[2] * reference[2];
        final double[] cross = {
                projected[1] * reference[2] - projected[2] * reference[1],
                projected[2] * reference[0] - projected[0] * reference[2],
                projected[0] * reference[1] - projected[1] * reference[0],
        };
        final double signed = cross[0] * gearAxis[0] + cross[1] * gearAxis[1] + cross[2] * gearAxis[2];
        return Math.toDegrees(Math.atan2(signed, dot));
    }

    /** 把向量投影到垂直于 {@code axis} 的平面上；退化（结果接近 0）时返回 null。 */
    private static double[] projectPerpendicular(final double[] v, final double[] axis) {
        if (v == null || axis == null) {
            return null;
        }
        final double dot = v[0] * axis[0] + v[1] * axis[1] + v[2] * axis[2];
        final double[] out = {
                v[0] - dot * axis[0],
                v[1] - dot * axis[1],
                v[2] - dot * axis[2],
        };
        final double lengthSq = out[0] * out[0] + out[1] * out[1] + out[2] * out[2];
        if (lengthSq < 1.0e-8) {
            return null;
        }
        final double length = Math.sqrt(lengthSq);
        out[0] /= length;
        out[1] /= length;
        out[2] /= length;
        return out;
    }
}
