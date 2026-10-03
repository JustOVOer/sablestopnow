package com.ovo.sablestopnow.mixin.scale;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

import com.ovo.sablestopnow.scale.ScaledColliderPipeline;
import com.ovo.sablestopnow.scale.ScaledColliders;
import com.ovo.sablestopnow.scale.ScaledDrag;
import com.ovo.sablestopnow.scale.ScaledMass;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;

/**
 * Routes the voxel-collider uploads of <b>scaled</b> sub-levels through {@link ScaledColliders} (see its doc for
 * the resampling scheme). Stock chunk uploads/removals/block updates for a managed plot are cancelled and turned
 * into dirty marks; the manager rebuilds the body's native chunks once per server tick. Suppressed-section
 * bookkeeping keeps native add/remove calls exactly paired even across scale transitions and plot unloads.
 * {@code onStatsChanged}'s local-bounds refresh is redirected to the scaled bounds for managed bodies, and its
 * mass-properties upload to the scale-corrected values (see {@link ScaledMass}).
 */
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public abstract class RapierPhysicsPipelineMixin implements ScaledColliderPipeline {

    @Shadow
    @Final
    private ServerLevel level;

    /** The bodies actually in the native scene - the only ones safe to hand a body id to (see {@link ScaledDrag}). */
    @Shadow
    @Final
    private Int2ObjectMap<ServerSubLevel> activeSubLevels;

    @Shadow
    protected abstract long getSceneHandle();

    @Override
    public ServerLevel sablestopnow$level() {
        return this.level;
    }

    @Override
    public long sablestopnow$sceneHandle() {
        return this.getSceneHandle();
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void sablestopnow$register(final ServerLevel level, final CallbackInfo ci) {
        ScaledColliders.register(level, (RapierPhysicsPipeline) (Object) this);
    }

    /**
     * {@code prePhysicsTicks} is the {@code Rapier3D.tick} call that runs Sable's buoyancy pass, so by TAIL the
     * native water forces for this tick are on the bodies and the substep loop has not started integrating them
     * yet - the one window where the drag correction can land (see {@link ScaledDrag}).
     */
    @Inject(method = "prePhysicsTicks", at = @At("TAIL"))
    private void sablestopnow$correctWaterDrag(final CallbackInfo ci) {
        ScaledDrag.correctAll((RapierPhysicsPipeline) (Object) this, this.level, this.getSceneHandle(),
            this.activeSubLevels.values());
    }

    @Inject(method = "handleChunkSectionAddition", at = @At("HEAD"), cancellable = true)
    private void sablestopnow$interceptSectionAdd(final LevelChunkSection section, final int x, final int y, final int z,
                                                  final boolean uploadDataIfGlobal, final CallbackInfo ci) {
        final ServerSubLevel subLevel = this.sablestopnow$managedPlotSubLevel(x, z);
        if (subLevel != null) {
            ScaledColliders.onStockAddSuppressed(this.level, SectionPos.asLong(x, y, z));
            ScaledColliders.markDirty(subLevel);
            ci.cancel();
        }
    }

    @Inject(method = "handleChunkSectionRemoval", at = @At("HEAD"), cancellable = true)
    private void sablestopnow$interceptSectionRemove(final int x, final int y, final int z, final CallbackInfo ci) {
        // The section never made it into (or was already dropped from) the native scene - keep add/remove paired.
        if (ScaledColliders.consumeSuppressedStock(this.level, SectionPos.asLong(x, y, z))) {
            final ServerSubLevel subLevel = this.sablestopnow$plotSubLevel(x, z);
            if (subLevel != null)
                ScaledColliders.markDirty(subLevel);
            ci.cancel();
        }
    }

    @Inject(method = "handleBlockChange", at = @At("HEAD"), cancellable = true)
    private void sablestopnow$interceptBlockChange(final SectionPos sectionPos, final LevelChunkSection chunk,
                                                   final int x, final int y, final int z,
                                                   final BlockState oldState, final BlockState newState, final CallbackInfo ci) {
        final ServerSubLevel subLevel = this.sablestopnow$managedPlotSubLevel(sectionPos.x(), sectionPos.z());
        if (subLevel != null) {
            ScaledColliders.markDirty(subLevel);
            ci.cancel();
        }
    }

    @Inject(method = "add(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/companion/math/Pose3dc;)V", at = @At("TAIL"))
    private void sablestopnow$onBodyAdded(final ServerSubLevel subLevel, final dev.ryanhcode.sable.companion.math.Pose3dc pose, final CallbackInfo ci) {
        if (ScaledColliders.isManaged(subLevel))
            ScaledColliders.markDirty(subLevel); // sub-level loaded from disk already scaled
    }

    @Inject(method = "remove(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;)V", at = @At("HEAD"))
    private void sablestopnow$onBodyRemoved(final ServerSubLevel subLevel, final CallbackInfo ci) {
        ScaledColliders.onBodyRemoved(subLevel);
    }

    /**
     * All mass uploads funnel through this one call: body creation ({@code add} calls {@code onStatsChanged}),
     * block place/break ({@code MergedMassTracker.uploadData}) and our scale command (which calls
     * {@code onStatsChanged} directly, since nothing else re-uploads on a scale-only change).
     */
    @Redirect(
        method = "onStatsChanged",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;setMassPropertiesFrom(JILdev/ryanhcode/sable/api/physics/mass/MassData;)V"))
    private void sablestopnow$scaledMassProperties(final long handle, final int bodyId, final MassData massData,
                                                   final ServerSubLevel subLevel) {
        // Compose our solids' mass/inertia into Sable's tracker value.
        //
        // WHY HERE: this redirect is the SINGLE place where Sable uploads mass (its own doc
        // says every mass upload funnels through this call). Adding mass anywhere else gets
        // overwritten by this very call - measured: total mass stayed 0.5000 (one block).
        final MassData composed = composeWithSolids(handle, bodyId, massData, subLevel);
        if (ScaledColliders.isManaged(subLevel))
            ScaledMass.upload(handle, bodyId, composed, subLevel.logicalPose().scale());
        else
            Rapier3DAccessor.sablestopnow$setMassPropertiesFrom(handle, bodyId, composed);
    }

    /**
     * Tracker value + every solid's mass part, combined with the parallel axis theorem.
     * Returns the tracker value unchanged when there is nothing to add.
     */
    private static MassData composeWithSolids(final long handle, final int bodyId,
                                              final MassData tracker,
                                              final ServerSubLevel subLevel) {
        final java.util.List<com.ovo.sablestopnow.physics.SolidMassComposition.Part> parts =
                com.ovo.sablestopnow.server.VoxelCollisionService.partsForBody(bodyId);
        if (parts.isEmpty() || tracker == null || tracker.isInvalid()) {
            return tracker;
        }
        final org.joml.Vector3dc c = tracker.getCenterOfMass();
        final org.joml.Matrix3dc i = tracker.getInertiaTensor();
        final java.util.List<com.ovo.sablestopnow.physics.SolidMassComposition.Part> all =
                new java.util.ArrayList<>(parts.size() + 1);
        all.add(new com.ovo.sablestopnow.physics.SolidMassComposition.Part(
                tracker.getMass(), c.x(), c.y(), c.z(),
                i.m00(), i.m11(), i.m22(), i.m01(), i.m02(), i.m12()));
        all.addAll(parts);
        final com.ovo.sablestopnow.physics.SolidMassComposition.Result r =
                com.ovo.sablestopnow.physics.SolidMassComposition.combine(all);
        if (r == null) {
            return tracker;
        }
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[sablestopnow] mass composed: tracker={} + solids={} -> total={}",
                String.format(java.util.Locale.ROOT, "%.3f", tracker.getMass()),
                parts.size(), String.format(java.util.Locale.ROOT, "%.3f", r.mass()));
        return new com.ovo.sablestopnow.physics.SolidMassComposition.Composed(r);
    }

    @Redirect(
        method = "onStatsChanged",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;setLocalBounds(JIIIIIII)V"))
    private void sablestopnow$scaledLocalBounds(final long handle, final int bodyId,
                                                final int minX, final int minY, final int minZ,
                                                final int maxX, final int maxY, final int maxZ,
                                                final ServerSubLevel subLevel) {
        if (ScaledColliders.isManaged(subLevel)) {
            ScaledColliders.applyScaledLocalBounds(handle, bodyId, subLevel);
            // onStatsChanged runs right after a CoM change re-anchored the rotation point (and teleported the
            // body). The resampled lattice pivots on the rotation point, so rebuild it NOW - before the physics
            // step - or the offset collider gets a solver impulse (the block place/break "twitch").
            ScaledColliders.rebuildIfRotationPointMoved(subLevel);
        } else {
            Rapier3DAccessor.sablestopnow$setLocalBounds(handle, bodyId, minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private ServerSubLevel sablestopnow$managedPlotSubLevel(final int chunkX, final int chunkZ) {
        final ServerSubLevel subLevel = this.sablestopnow$plotSubLevel(chunkX, chunkZ);
        return subLevel != null && ScaledColliders.isManaged(subLevel) ? subLevel : null;
    }

    private ServerSubLevel sablestopnow$plotSubLevel(final int chunkX, final int chunkZ) {
        final SubLevelContainer container = SubLevelContainer.getContainer(this.level);
        if (container == null)
            return null;
        final LevelPlot plot = container.getPlot(chunkX, chunkZ);
        return plot != null && plot.getSubLevel() instanceof ServerSubLevel subLevel && !subLevel.isRemoved() ? subLevel : null;
    }
}
