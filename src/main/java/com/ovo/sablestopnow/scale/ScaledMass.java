package com.ovo.sablestopnow.scale;

import org.joml.Matrix3dc;
import org.joml.Vector3dc;

import com.ovo.sablestopnow.mixin.scale.Rapier3DAccessor;
import dev.ryanhcode.sable.api.physics.mass.MassData;

/**
 * Gives scaled sub-levels the <b>correct weight</b>. Sable's {@code MassTracker} sums per-block masses over the
 * unscaled voxel grid and {@code RapierPhysicsPipeline.onStatsChanged} uploads that straight to the native body,
 * so a &times;0.5 dinghy pressed down on the water like the full-size boat (and sank, since the resampled collider
 * only displaces S&sup3; of fluid). This helper re-derives the mass properties of the <em>scaled</em> block
 * distribution and uploads those instead ({@code RapierPhysicsPipelineMixin} routes the upload here).
 *
 * <p>Blocks keep their material density, so scaling positions by {@code S = diag(sx, sy, sz)} about the centre of
 * mass scales every block mass by the volume factor {@code k = sx·sy·sz}:</p>
 *
 * <ul>
 *   <li><b>mass</b>: {@code m' = k·m} (&times;S&sup3; for uniform scale);</li>
 *   <li><b>centre of mass</b>: unchanged &mdash; the pose scales around the rotation point, which Sable re-anchors
 *       to the CoM right before every stats upload, and a distribution scaled about its own CoM keeps its CoM
 *       (also why the native {@code setCenterOfMass} call above the mass upload needs no correction);</li>
 *   <li><b>inertia</b>: {@code I = Σ m(|r|²δ − rrᵀ)} does not transform component-wise, but the second-moment
 *       matrix {@code M = Σ m·rrᵀ = tr(I)/2·δ − I} does: {@code M'_ab = k·s_a·s_b·M_ab}. Converting, scaling and
 *       converting back ({@code I' = tr(M')·δ − M'}) is exact; for uniform scale it collapses to
 *       {@code I' = s⁵·I} (mass &times;s&sup3;, lever arms &times;s&sup2;).</li>
 * </ul>
 *
 * <p>With mass &times;S&sup3; and the resampled collider's buoyancy volumes already &times;S&sup3;
 * (see {@link ScaledColliders}), a vehicle floats at the same waterline at every scale.</p>
 */
public final class ScaledMass {

    private ScaledMass() {}

    /** Uploads the mass properties of {@code massData}'s block distribution as if scaled by {@code scale}. */
    public static void upload(final long handle, final int bodyId, final MassData massData, final Vector3dc scale) {
        final double sx = scale.x(), sy = scale.y(), sz = scale.z();
        final double k = sx * sy * sz;

        final Matrix3dc i = massData.getInertiaTensor();
        final double halfTrace = (i.m00() + i.m11() + i.m22()) * 0.5;
        // M = tr(I)/2·δ − I, scaled per entry (inertia tensors are symmetric, so m01 covers m10 etc.)
        final double m00 = k * sx * sx * (halfTrace - i.m00());
        final double m11 = k * sy * sy * (halfTrace - i.m11());
        final double m22 = k * sz * sz * (halfTrace - i.m22());
        final double m01 = k * sx * sy * -i.m01();
        final double m02 = k * sx * sz * -i.m02();
        final double m12 = k * sy * sz * -i.m12();
        // I' = tr(M')·δ − M'
        final double trace = m00 + m11 + m22;

        final Vector3dc com = massData.getCenterOfMass();
        Rapier3DAccessor.sablestopnow$setMassProperties(handle, bodyId, k * massData.getMass(),
            new double[]{com.x(), com.y(), com.z()},
            new double[]{
                trace - m00, -m01, -m02,
                -m01, trace - m11, -m12,
                -m02, -m12, trace - m22});
    }
}
