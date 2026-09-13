package com.ovo.sablestopnow.scale;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

/**
 * Makes rapier <b>constraints</b> (Create stickers &amp; super glue, Simulated merging glue / swivel bearings /
 * handles / ropes / docking connectors / the physics staff) line up with, and drive, a <b>scaled</b> sub-level.
 *
 * <p>Two independent corrections, both keyed off the same fact: {@link ScaledColliders} resamples a scaled body's
 * geometry onto {@code rotPoint + S·(blockPos − rotPoint)} because the native body transform carries no scale
 * ({@code world = pos + R·(local − centerOfMass)}).</p>
 *
 * <ul>
 *   <li><b>Anchors</b> ({@link #anchor}). Callers pass constraint anchors in <em>unscaled plot-local block</em>
 *       coordinates (Sable's {@code validateAnchors} enforces {@code plot.contains(pos)}), but in a scaled body the
 *       block that anchor means now physically sits at {@code rotPoint + S·(pos − rotPoint)}. Passing the raw
 *       anchor therefore pinned the joint to a phantom point offset by {@code (1−S)·(pos − rotPoint)} - a sticker
 *       on a shrunken vehicle grabbed thin air and yanked the hull. We map every anchor through the same transform
 *       the collider resampler uses, so the joint lands on the block you actually stuck.</li>
 *   <li><b>Motor gains</b> ({@link #motorFactor}). Motorised joints size their PD gains against the
 *       <em>unscaled</em> mass tracker (Simulated's swivel bearing: {@code kP = stiffness · unscaledInertia}) or
 *       against fixed constants (its handle: 240/30, cap 120 N). The body they actually drive has mass {@code k·m}
 *       and inertia {@code S⁵·I}, so at {@code S<1} a bearing tuned for {@code I} overpowers an {@code S⁵·I} body
 *       and oscillates - the rotary twin of the wheel bounce. Scaling gains and the force cap by {@code k} on
 *       linear axes and {@code S⁴} on angular ones puts the motor back in proportion to the load it holds,
 *       matching {@link ScaledMass}'s impulse rule.</li>
 * </ul>
 *
 * <p>Uniform scale only. Anchors are mapped with the rotation point <em>current at creation</em>; a later centre-of-
 * mass shift (block place/break) re-anchors {@code rotPoint} and rebuilds the collider, but cannot move an already
 * baked native joint frame, so a constrained scaled vehicle that is heavily rebuilt can drift by
 * {@code S·Δ(rotPoint)} until the joint is remade (stickers remake theirs on re-attach).</p>
 *
 * <p>⚠ 移植状态：注册表（{@link #register}）的调用方是关节侧 mixin，本次未移植，因此
 * {@link #reaim} 目前是空转（对无缩放的普通关节没有影响）。{@link #anchor} / {@link #motorFactor} 是纯函数，
 * 已可直接使用。</p>
 */
public final class ScaledConstraints {

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Which joints touch each managed body, so a rotation-point move can re-aim exactly those. Keyed by body UUID;
     * the joint handles are held weakly, so a joint the physics layer drops falls out of the registry on its own
     * (and {@link #forget} clears a whole body when it is removed). Guarded by this class's monitor.
     */
    private static final Map<UUID, Set<ScaledConstraint>> CONSTRAINTS_BY_BODY = new HashMap<>();

    private ScaledConstraints() {}

    /** Records that {@code constraint} spans these bodies, so {@link #reaim} can find it when either one moves. */
    public static synchronized void register(final ScaledConstraint constraint,
                                             @Nullable final ServerSubLevel bodyA, @Nullable final ServerSubLevel bodyB) {
        if (bodyA != null)
            weakSet(bodyA.getUniqueId()).add(constraint);
        if (bodyB != null && bodyB != bodyA)
            weakSet(bodyB.getUniqueId()).add(constraint);
    }

    private static Set<ScaledConstraint> weakSet(final UUID uuid) {
        return CONSTRAINTS_BY_BODY.computeIfAbsent(uuid, key -> Collections.newSetFromMap(new WeakHashMap<>()));
    }

    /**
     * Re-aims every joint attached to {@code body} onto its current scaled geometry. Called right after the body's
     * resampled collider is rebuilt for a rotation-point or scale change (see {@link ScaledColliders}); without it
     * the joint frame stays frozen at the anchor it had when the joint was created, and the attached part drifts by
     * {@code (1−S)·Δ(rotationPoint)} - visibly further with every block added to a scaled contraption.
     */
    public static synchronized void reaim(final ServerSubLevel body) {
        final Set<ScaledConstraint> set = CONSTRAINTS_BY_BODY.get(body.getUniqueId());
        if (set == null || set.isEmpty())
            return;
        for (final ScaledConstraint constraint : set.toArray(new ScaledConstraint[0])) {
            try {
                constraint.sablestopnow$reaim();
            } catch (final Throwable t) {
                LOGGER.error("[SableStopNow] failed to re-aim a constraint on {}", body, t);
            }
        }
    }

    /** Drops a removed body's joints from the registry. */
    public static synchronized void forget(final UUID body) {
        CONSTRAINTS_BY_BODY.remove(body);
    }

    /**
     * The local frame orientation a revolute (rotary) joint gets for a hinge along {@code axis}: the minimal
     * rotation taking rapier's reference axis (local +X) onto it. A uniform scale leaves {@code axis} unchanged, so
     * this is stable across rescales and re-aims - only the anchor position needs re-mapping.
     */
    public static Quaterniondc frameFromAxis(final Vector3dc axis) {
        return new Quaterniond().rotationTo(1.0, 0.0, 0.0, axis.x(), axis.y(), axis.z());
    }

    /** @return the sub-level whose live scale governs this joint: the scaled body, preferring {@code bodyA}. */
    @Nullable
    public static ServerSubLevel scaleSource(@Nullable final ServerSubLevel bodyA, @Nullable final ServerSubLevel bodyB) {
        if (bodyA != null && ScaledColliders.isManaged(bodyA))
            return bodyA;
        if (bodyB != null && ScaledColliders.isManaged(bodyB))
            return bodyB;
        return null;
    }

    /**
     * Maps a constraint anchor from unscaled plot-local space onto {@code body}'s resampled (scaled) geometry:
     * {@code rotPoint + S·(pos − rotPoint)}. Unscaled bodies, and anchors against the static world
     * ({@code body == null}, i.e. already world-space), pass through untouched.
     */
    public static Vector3dc anchor(final Vector3dc pos, @Nullable final PhysicsPipelineBody body) {
        if (!(body instanceof ServerSubLevel subLevel) || !ScaledColliders.isManaged(subLevel))
            return pos;
        final Pose3dc pose = subLevel.logicalPose();
        final Vector3dc rp = pose.rotationPoint(), s = pose.scale();
        return new Vector3d(
            rp.x() + s.x() * (pos.x() - rp.x()),
            rp.y() + s.y() * (pos.y() - rp.y()),
            rp.z() + s.z() * (pos.z() - rp.z()));
    }

    /**
     * Gain/force factor for a motor on {@code axis} ({@code ConstraintJointAxis.ordinal()}: 0-2 linear, 3-5
     * angular) driving {@code source}: {@code k} for linear (a force, against mass {@code k·m}), {@code S⁴} for
     * angular (a torque, and every torque on the resampled hull carries the real lever {@code S·r}). {@code 1} when
     * nothing is scaled.
     *
     * <p>Targets are untouched (angles and the linear motors' 0), so the factor lands on {@code kP}, {@code kD} and
     * the force/torque cap alike. Against inertia {@code S⁵·I} that leaves a shrunk joint <em>stiffer and better
     * damped</em> ({@code ω_n} and {@code ζ} both {@code ∝ S^-½}) - the safe direction, and the one that matches
     * gravity's own {@code S⁴} moment, so a bearing that held its load at scale 1 still holds it.</p>
     */
    public static double motorFactor(@Nullable final ServerSubLevel source, final int axis) {
        if (source == null)
            return 1.0;
        final Vector3dc s = source.logicalPose().scale();
        final double k = s.x() * s.y() * s.z();
        return axis < 3 ? k : k * s.x(); // uniform scale: S⁴ = k·S
    }
}
