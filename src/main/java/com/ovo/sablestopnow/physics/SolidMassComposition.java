package com.ovo.sablestopnow.physics;

import java.util.List;

/**
 * **把多个实体（草图固体）的质量属性合成到它们所在刚体上** ✓
 *
 * <h2>为什么必须有它</h2>
 *
 * 实测（用自建 native 的 {@code bodyState} 读出来的 ✓）：一个"φ12 大圆柱 + 顶面一个球"
 * 的结构，刚体总质量只有 **0.5000** ✗ —— 就是那个**方块**的质量 ✓
 * 因为 Sable 的质量跟踪器**只数方块** ✗，而我们的实体不是方块 ✓
 *
 * 体素路靠给每个形状带 {@code volume} 让 Rapier 算质量 ✓，
 * 我们的凸包原本是 {@code density(0)} ✗ → **实体一点质量都不贡献** ✗✗
 * → 又大又轻 → 任何接触冲量都把它打得乱飞 ✓ 这就是"弹得停不下来"的根因 ✓
 *
 * <h2>为什么合成要讲平行轴定理</h2>
 *
 * 只加质量是不够的 ✗ —— 惯量还围绕着**各自**的质心 ✗。
 * 要得到"绕**总**质心"的惯量，每个部分都要按**平行轴定理**平移：
 *
 * <pre>
 *   M   = Σ mᵢ
 *   C   = (Σ mᵢ·cᵢ) / M
 *   I   = Σ [ Iᵢ + mᵢ·(|dᵢ|²·E − dᵢ·dᵢᵀ) ]     dᵢ = cᵢ − C
 * </pre>
 *
 * 其中 {@code E} 是单位阵。第二项就是平行轴项 ✓
 *
 * <h2>为什么单独一个类</h2>
 *
 * 这是**纯数学** ✓ —— 不碰 Minecraft、不碰 Rapier ✓ 所以能被离线断言覆盖 ✓
 * （质量/惯量这种数错了很难在游戏里看出来 ✗ 只能靠断言 ✓）
 */
public final class SolidMassComposition {

    private SolidMassComposition() {
    }

    /**
     * 一个部分（跟踪器，或某个实体）的质量属性。
     *
     * @param mass 质量（> 0）
     * @param cx,cy,cz 它的质心（**刚体局部**坐标）
     * @param ixx,iyy,izz 绕**自身质心**的惯量对角项
     * @param ixy,ixz,iyz 绕自身质心的惯量非对角项
     */
    public record Part(double mass,
                       double cx, double cy, double cz,
                       double ixx, double iyy, double izz,
                       double ixy, double ixz, double iyz) {
    }

    /** 合成结果（绕**总质心**的惯量）。 */
    public record Result(double mass,
                         double cx, double cy, double cz,
                         double ixx, double iyy, double izz,
                         double ixy, double ixz, double iyz) {
    }

    /**
     * 把一个**长方体**近似成一个部分（质量按体积×密度给 ✓）。
     *
     * <p>为什么可以用长方体近似：我们只想让"质量量级"和"惯量量级"正确 ✓
     * 而实体的精确惯量需要数值积分 ✗ —— 收益远小于风险 ✓
     * 长方体的惯量是闭式的：{@code Ixx = m/12·(sy²+sz²)} ✓
     */
    public static Part box(final double mass, final double cx, final double cy, final double cz,
                           final double sx, final double sy, final double sz) {
        final double k = mass / 12.0;
        return new Part(mass, cx, cy, cz,
                k * (sy * sy + sz * sz),
                k * (sx * sx + sz * sz),
                k * (sx * sx + sy * sy),
                0.0, 0.0, 0.0);
    }

    /**
     * 合成：把 {@code parts}（含跟踪器自己那一份 ✓）按质量加权、并用平行轴定理算总惯量。
     *
     * <p>列表为空、或总质量非正时返回 {@code null} ✓（调用方原样透传 ✓ 不要乱改 ✗）
     */
    public static Result combine(final List<Part> parts) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        double m = 0.0;
        double sx = 0.0;
        double sy = 0.0;
        double sz = 0.0;
        for (final Part p : parts) {
            if (p.mass() <= 0.0) {
                continue; // 质量为 0 的部分对惯量没有贡献 ✓
            }
            m += p.mass();
            sx += p.mass() * p.cx();
            sy += p.mass() * p.cy();
            sz += p.mass() * p.cz();
        }
        if (m <= 0.0) {
            return null;
        }
        // ============================================================================
        // ★ 坐标系防线 —— 这一段就是为下面那个真实事故加的 ✓
        //
        // 事故（实测 ✓）：调用方把一个 **plot 绝对**的质心（tracker，~2.048e7 ✗）和几个
        // **刚体局部**的部分（我们的实体，±6 ✓）一起传了进来 ✗。于是平行轴项里
        //   d ≈ 2e7  →  d² ≈ 4e14  →  m·d² ≈ 1.7e17
        // 转动惯量被放大 1e17 倍 ✗，刚体的角响应被完全冻住 ✓
        // 症状只是"任意时候都难以旋转、弹跳一次只转 1~2 度"✗ —— **没有任何报错** ✗✗
        // 为此花了整整一个会话才定位 ✓。
        //
        // 所以：**在任何跨系计算之前，先断言所有部分在同一套系里** ✓。
        // 判据：任意两个部分的质心距离，不应该超过局部量的量级 ✓
        //（局部尺寸最多几十格 ✓ 而混入 plot 绝对量会让距离直接到 1e7 量级 ✓）。
        // ============================================================================
        double refX = Double.NaN;
        double refY = Double.NaN;
        double refZ = Double.NaN;
        for (final Part p : parts) {
            if (p.mass() <= 0.0) {
                continue;
            }
            if (Double.isNaN(refX)) {
                refX = p.cx();
                refY = p.cy();
                refZ = p.cz();
                continue;
            }
            final double sep = Math.sqrt((p.cx() - refX) * (p.cx() - refX)
                    + (p.cy() - refY) * (p.cy() - refY)
                    + (p.cz() - refZ) * (p.cz() - refZ));
            if (sep > Check.LOCAL_MAX) {
                throw new Check.FrameViolation("SolidMassComposition.combine 收到【不同坐标系】的"
                        + "部分：一个质心在 (" + refX + "," + refY + "," + refZ + ")，另一个在 ("
                        + p.cx() + "," + p.cy() + "," + p.cz() + ")，相距 " + sep
                        + " —— 这会让平行轴项爆炸（曾导致转动惯量放大 1e17 倍 ✗）。"
                        + "注意 massTracker.getCenterOfMass() 返回的是 **plot 绝对**坐标，"
                        + "而刚体坐标系的原点【就是质心】，所以跟踪器在局部系里应为 (0,0,0) ✓");
            }
        }
        final double cx = sx / m;
        final double cy = sy / m;
        final double cz = sz / m;

        double ixx = 0.0;
        double iyy = 0.0;
        double izz = 0.0;
        double ixy = 0.0;
        double ixz = 0.0;
        double iyz = 0.0;
        for (final Part p : parts) {
            if (p.mass() <= 0.0) {
                continue;
            }
            // 平行轴项：m·(|d|²E − d·dᵀ)
            final double dx = p.cx() - cx;
            final double dy = p.cy() - cy;
            final double dz = p.cz() - cz;
            final double d2 = dx * dx + dy * dy + dz * dz;
            ixx += p.ixx() + p.mass() * (d2 - dx * dx);
            iyy += p.iyy() + p.mass() * (d2 - dy * dy);
            izz += p.izz() + p.mass() * (d2 - dz * dz);
            // ⚠ 非对角项是 −m·dᵢ·dⱼ（惯性积的定义里带负号 ✓ 而平行轴项正好抵消了这个负号 ✓）
            ixy += p.ixy() - p.mass() * dx * dy;
            ixz += p.ixz() - p.mass() * dx * dz;
            iyz += p.iyz() - p.mass() * dy * dz;
        }
        return new Result(m, cx, cy, cz, ixx, iyy, izz, ixy, ixz, iyz);
    }

    /**
     * 把 {@link Result} 包成 Sable 的 {@code MassData} ✓
     *
     * <p>为什么要包：这样就能**直接喂给 {@code ScaledMass.upload}** ✓
     * —— 缩放的逻辑仍然只有那一处 ✓ 不复制粘贴 ✗（项目规则：一个概念一处定义 ✓）
     */
    public record Composed(Result r) implements dev.ryanhcode.sable.api.physics.mass.MassData {

        @Override
        public double getMass() {
            return r.mass();
        }

        @Override
        public double getInverseMass() {
            return r.mass() > 0.0 ? 1.0 / r.mass() : 0.0;
        }

        @Override
        public org.joml.Matrix3dc getInertiaTensor() {
            // 行优先：惯量张量是对称的 ✓
            return new org.joml.Matrix3d(
                    r.ixx(), r.ixy(), r.ixz(),
                    r.ixy(), r.iyy(), r.iyz(),
                    r.ixz(), r.iyz(), r.izz());
        }

        @Override
        public org.joml.Matrix3dc getInverseInertiaTensor() {
            return getInertiaTensor().invert(new org.joml.Matrix3d());
        }

        @Override
        public org.joml.Vector3dc getCenterOfMass() {
            return new org.joml.Vector3d(r.cx(), r.cy(), r.cz());
        }
    }
}
