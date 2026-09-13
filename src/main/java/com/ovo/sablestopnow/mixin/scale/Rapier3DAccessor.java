package com.ovo.sablestopnow.mixin.scale;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;

/** Static invokers for the package-private chunk/bounds/mass natives the scaled-collider resampler uploads through. */
@Mixin(value = Rapier3D.class, remap = false)
public interface Rapier3DAccessor {

    @Invoker(value = "addChunk", remap = false)
    static void sablestopnow$addChunk(final long handle, final int x, final int y, final int z,
                                      final int[] data, final boolean global, final int bodyId) {
        throw new AssertionError();
    }

    @Invoker(value = "removeChunk", remap = false)
    static void sablestopnow$removeChunk(final long handle, final int x, final int y, final int z, final boolean global) {
        throw new AssertionError();
    }

    @Invoker(value = "setLocalBounds", remap = false)
    static void sablestopnow$setLocalBounds(final long handle, final int bodyId,
                                            final int minX, final int minY, final int minZ,
                                            final int maxX, final int maxY, final int maxZ) {
        throw new AssertionError();
    }

    /** The raw native behind {@code setMassPropertiesFrom}; inertia tensor is 9 doubles, row by row. */
    @Invoker(value = "setMassProperties", remap = false)
    static void sablestopnow$setMassProperties(final long handle, final int bodyId, final double mass,
                                               final double[] centerOfMass, final double[] inertiaTensor) {
        throw new AssertionError();
    }

    @Invoker(value = "setMassPropertiesFrom", remap = false)
    static void sablestopnow$setMassPropertiesFrom(final long handle, final int bodyId, final MassData massData) {
        throw new AssertionError();
    }

    /** Force + torque on a body (body frame); re-dispatched with scale-corrected magnitudes. */
    @Invoker(value = "applyForceAndTorque", remap = false)
    static void sablestopnow$applyForceAndTorque(final long handle, final int bodyId,
                                                 final double fx, final double fy, final double fz,
                                                 final double tx, final double ty, final double tz, final boolean wakeUp) {
        throw new AssertionError();
    }

    /** Force at a body-frame offset from the centre of mass (native derives the torque); used by {@code ScaledDrag}. */
    @Invoker(value = "applyForce", remap = false)
    static void sablestopnow$applyForce(final long handle, final int bodyId,
                                        final double px, final double py, final double pz,
                                        final double fx, final double fy, final double fz, final boolean wakeUp) {
        throw new AssertionError();
    }

    /**
     * Re-aims one side ({@code side} 0 = body1, 1 = body2) of a live joint: sets its local anchor position and
     * frame orientation. Used to keep a scaled body's joints on the resampled geometry after the rotation point
     * moves (see {@link com.ovo.sablestopnow.scale.ScaledConstraints}). Preserves the native joint, so its motor,
     * limits and locked axes survive untouched.
     */
    @Invoker(value = "setConstraintFrame", remap = false)
    static void sablestopnow$setConstraintFrame(final long sceneHandle, final long handle, final int side,
                                                final double localPosX, final double localPosY, final double localPosZ,
                                                final double localOrientationX, final double localOrientationY,
                                                final double localOrientationZ, final double localOrientationW) {
        throw new AssertionError();
    }

    /** True while the native joint behind this handle is still in the scene (guards re-aim against removed joints). */
    @Invoker(value = "isConstraintValid", remap = false)
    static boolean sablestopnow$isConstraintValid(final long sceneHandle, final long handle) {
        throw new AssertionError();
    }
}
