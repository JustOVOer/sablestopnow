package com.ovo.sablestopnow.scale;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3dc;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;

/**
 * State this mod stamps onto every rapier constraint handle so it can keep the joint aligned with a <b>scaled</b>
 * body as that body's rotation point moves. Rapier's own handle keeps only the native joint id; we hold:
 *
 * <ul>
 *   <li>the two bodies the joint spans (for reading their live scale in the motor/anchor maths), and</li>
 *   <li>the joint's <em>original</em> frames &mdash; each side's unscaled plot-local anchor and frame orientation,
 *       as first supplied to {@code addConstraint} &mdash; so {@link #sablestopnow$reaim()} can re-derive the correct
 *       scaled anchor from the body's <em>current</em> pose whenever it changes.</li>
 * </ul>
 *
 * <p>⚠ 移植状态：接口本身已按参考实现原样移植，但<b>填充注册表</b>的关节侧 mixin
 * （参考实现的 {@code RapierConstraintScaleMixin} / {@code RapierConstraintHandleScaleMixin} /
 * {@code RapierGenericConstraintFrameScaleMixin}）不在本次移植范围内，所以
 * {@link ScaledConstraints} 的注册表目前始终为空、{@code reaim} 实际不做事。见交付报告。</p>
 *
 * @see ScaledConstraints
 */
public interface ScaledConstraint {

    void sablestopnow$setBodies(@Nullable ServerSubLevel bodyA, @Nullable ServerSubLevel bodyB);

    @Nullable
    ServerSubLevel sablestopnow$bodyA();

    @Nullable
    ServerSubLevel sablestopnow$bodyB();

    /**
     * Records the joint's original (unscaled plot-local) anchors and frame orientations, exactly as they were
     * handed to {@code addConstraint}. A {@code null} orientation means "don't re-aim this side's orientation"
     * (used when a side's frame basis can't be reconstructed); its anchor position is still tracked.
     */
    void sablestopnow$rememberFrames(Vector3dc pos1, @Nullable Quaterniondc orientation1,
                                     Vector3dc pos2, @Nullable Quaterniondc orientation2);

    /**
     * Re-maps both anchors onto the bodies' current scaled geometry ({@link ScaledConstraints#anchor}) and writes
     * the joint's native frames, cancelling the drift a rotation-point move would otherwise open between the joint
     * and the resampled collider. No-op until {@link #sablestopnow$rememberFrames} has run, or if the native joint
     * is already gone.
     */
    void sablestopnow$reaim();
}
