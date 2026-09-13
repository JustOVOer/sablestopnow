package com.ovo.sablestopnow.scale;

import java.util.Collection;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import com.ovo.sablestopnow.mixin.scale.Rapier3DAccessor;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

/**
 * Gives <b>scaled</b> sub-levels the right amount of <b>water drag</b> &mdash; the one thing standing between a
 * shrunken vehicle and the void.
 *
 * <p>Sable's native buoyancy pass ({@code rapier/src/buoyancy.rs}, run once per tick from {@code Rapier3D.tick})
 * walks every body octree cell overlapping a water cell and applies two forces:</p>
 *
 * <ul>
 *   <li><b>float</b>: {@code +10.5 · V_overlap · voxel_collider_data.volume} &mdash; scaled by the entry's
 *       <em>material volume</em>, which {@link ScaledColliders} already builds at {@code k = sx·sy·sz}, so buoyancy
 *       tracks {@link ScaledMass}'s {@code k·m} and the waterline holds at every scale;</li>
 *   <li><b>drag</b>: {@code −1.7 · V_overlap · 1.0 · v} &mdash; {@code strength} is <b>hardcoded to 1.0</b>, so drag
 *       counts <em>cells</em> and never learns the vehicle got smaller.</li>
 * </ul>
 *
 * <p>Only <em>surface</em> cells reach that loop ({@code insert_block_octree} keeps {@code INTERIOR} out of the body
 * octree), so the native drag coefficient follows the scaled hull's <b>area</b> while its mass follows the hull's
 * <b>volume</b>. Shrinking a vehicle therefore drives the ratio that actually moves it, {@code c/m}, up as
 * {@code 1/S} &mdash; and the discretisation floor makes the tail worse still, because a sub-block hull always
 * straddles at least one cell no matter how small it gets while its mass keeps falling as {@code S³}.</p>
 *
 * <p>That is fatal because the drag force is <b>explicit</b>: {@code compute_buoyancy} evaluates it once per tick
 * from the tick's opening velocity, and the substep loop re-uses the frozen force, so across the 50 ms tick the body
 * integrates {@code v' = v·(1 − c·0.05/m)}. Past {@code c/m > 40} that flips the velocity's sign and <em>grows</em>
 * it every tick: the vehicle jitters (the straddled-cell count wobbles as it moves and rotates), then leaves on a
 * geometric ramp &mdash; sideways, or down through the void. Unscaled vehicles sit at {@code c/m ≈ 1.7}, a 23&times;
 * margin; measured against Sable's own numbers a hollow cube hull crosses 40 at about {@code ×0.1}. Water only:
 * drag is the pipeline's one explicit velocity-proportional force, while contacts go through rapier's implicit
 * solver and stay stable at any mass.</p>
 *
 * <p>The correction re-dispatches each drag sample at the {@code strength} the native should have used,</p>
 *
 * <pre>    strength = k · surfaceCells(scale 1) / surfaceCells(scale S)</pre>
 *
 * <p>i.e. whatever makes the drag coefficient land on {@code k·c₀} the way the mass lands on {@code k·m₀}, leaving
 * {@code c/m = c₀/m₀} at <em>every</em> scale &mdash; stable by construction, since scale 1 is. Reading the counts
 * rather than assuming the {@code S²} area law is what keeps it honest once the hull goes sub-block and the cell
 * count stops following that law. {@code strength} lives in the native and cannot be passed in, so this class
 * re-walks the same samples and applies the difference, {@code +1.7 · V_overlap · (1−strength) · v · 0.05}, as an
 * impulse; both terms are constant across the tick, so cancelling the excess up front lands exactly the {@code Δv}
 * the native would have produced with the smaller coefficient.</p>
 *
 * <p><b>The other reading:</b> Sable's area law is the physical one &mdash; a real dinghy <em>is</em> draggier per
 * tonne than a real ship. Pinning {@code strength} at 1 here would keep that and leave only the divergence to fix
 * (by capping {@code c·0.05/m} at 1). This mod's contract is a scaled vehicle that handles like the vehicle it was,
 * so it corrects; flipping that is a one-line change to {@link #profile}.</p>
 */
public final class ScaledDrag {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Sable's drag constant ({@code do_drag}: {@code -velo * 1.7 * volume * strength}). */
    private static final double DRAG = 1.7;
    /** {@code compute_buoyancy} runs once per {@code Rapier3D.tick}; the substeps re-use the force it leaves. */
    private static final double TICK_SECONDS = 0.05;
    /** {@code buoyancy.rs}: bodies whose local-bounds extents sum below this sample 8 sub-probes per cell, not one. */
    private static final int COMPLEX_EXTENT_SUM = 10;
    /** Drag is proportional to velocity, so below this the correction is noise (and the body is likely asleep). */
    private static final double MIN_SPEED_SQ = 1.0E-10;
    /**
     * Cells per body we will re-walk each tick. Only the stability role is load-bearing and it needs {@code c/m}
     * near 40 &mdash; which takes a hull shrunk to a handful of submerged cells, nowhere near this cap. Bodies over
     * it are deep in the bulk regime, where uncorrected drag is merely too strong rather than divergent.
     */
    private static final int MAX_CELLS = 8192;

    private ScaledDrag() {}

    /** The surface cells of a managed body's lattice, and the drag strength Sable's {@code do_drag} should use. */
    public record Profile(long[] cells, double strength) {}

    /**
     * Builds the drag profile for a freshly resampled body, or null when it needs no correction.
     *
     * @param cells           the resampled lattice's surface cells - what the native drag loop can reach
     * @param unscaledSurface the same hull's surface cell count at scale 1, i.e. the cell count Sable's own drag
     *                        would have counted, which is the reference {@code c₀} we are scaling onto
     */
    static Profile profile(final ServerSubLevel subLevel, final long[] cells, final int unscaledSurface) {
        if (cells.length == 0 || unscaledSurface == 0)
            return null;
        if (cells.length > MAX_CELLS) {
            LOGGER.debug("[SableStopNow] {} has {} surface cells - skipping the water-drag correction (bulk regime: "
                + "drag is over-strong there, but nowhere near divergent)", subLevel, cells.length);
            return null;
        }
        final Vector3dc scale = subLevel.logicalPose().scale();
        final double k = scale.x() * scale.y() * scale.z();
        // Mass went to k·m₀ (ScaledMass), so drag has to go to k·c₀ for the vehicle to keep its handling - and
        // c is 1.7 per surface cell, so the strength that gets there is k·(cells at 1)/(cells now).
        final double strength = Math.min(1.0, k * unscaledSurface / cells.length);
        return strength >= 1.0 ? null : new Profile(cells, strength);
    }

    /**
     * Cancels the excess native water drag on every managed body. Runs after {@code Rapier3D.tick}.
     *
     * <p>{@code bodies} must be the pipeline's own active set rather than the container's sub-level list: the native
     * calls below resolve the body id with an {@code unwrap()}, so a sub-level that is registered but not yet added
     * to the scene would panic the server rather than throw.</p>
     */
    public static void correctAll(final RapierPhysicsPipeline pipeline, final ServerLevel level, final long handle,
                                  final Collection<ServerSubLevel> bodies) {
        if (bodies.isEmpty())
            return;
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null)
            return;

        FluidProbe fluid = null;
        final Pose3d pose = new Pose3d();
        final Vector3d linear = new Vector3d(), angular = new Vector3d();

        for (final ServerSubLevel subLevel : bodies) {
            if (subLevel.isRemoved() || !ScaledColliders.isManaged(subLevel))
                continue;
            final Profile profile = ScaledColliders.dragProfile(subLevel);
            if (profile == null)
                continue;

            pipeline.getLinearVelocity(subLevel, linear);
            pipeline.getAngularVelocity(subLevel, angular);
            if (linear.lengthSquared() < MIN_SPEED_SQ && angular.lengthSquared() < MIN_SPEED_SQ)
                continue;

            final Vector3dc com = subLevel.getMassTracker().getCenterOfMass();
            if (com == null)
                continue;

            if (fluid == null)
                fluid = new FluidProbe(level, container);
            pipeline.readPose(subLevel, pose);
            correct(handle, fluid, subLevel, profile, pose, com, linear, angular);
        }
    }

    private static void correct(final long handle, final FluidProbe fluid, final ServerSubLevel subLevel,
                                final Profile profile, final Pose3d pose, final Vector3dc com,
                                final Vector3dc linear, final Vector3dc angular) {
        final int bodyId = Rapier3D.getID(subLevel);
        // Sable's body transform is (translation = world centre of mass, rotation), so local points are CoM-relative.
        final Vector3dc origin = pose.position();
        final Quaterniondc rotation = pose.orientation();
        final Quaterniond inverse = new Quaterniond(rotation).conjugate();

        final int[] bounds = ScaledColliders.scaledLocalBounds(subLevel);
        final boolean complex = (bounds[3] - bounds[0]) + (bounds[4] - bounds[1]) + (bounds[5] - bounds[2]) < COMPLEX_EXTENT_SUM;
        // Mirror buoyancy.rs: one probe of half-extent 0.5 on the cell centre, or 8 of 0.25 on its octants. The
        // union is the same cube, but the sub-probes carry their own lever arms and their own sampled velocity.
        final double size = complex ? 0.25 : 0.5;
        final int probes = complex ? 8 : 1;
        final double gain = DRAG * (1.0 - profile.strength()) * TICK_SECONDS;

        final Vector3d local = new Vector3d(), world = new Vector3d(), velocity = new Vector3d(), impulse = new Vector3d();

        for (final long cell : profile.cells()) {
            final double cx = BlockPos.getX(cell) + 0.5 - com.x();
            final double cy = BlockPos.getY(cell) + 0.5 - com.y();
            final double cz = BlockPos.getZ(cell) + 0.5 - com.z();

            for (int probe = 0; probe < probes; probe++) {
                if (complex)
                    local.set(cx + ((probe & 1) * 2 - 1) * 0.25,
                        cy + (((probe >> 1) & 1) * 2 - 1) * 0.25,
                        cz + (((probe >> 2) & 1) * 2 - 1) * 0.25);
                else
                    local.set(cx, cy, cz);

                rotation.transform(local, world).add(origin);
                final double overlap = fluid.submergedVolume(world, size);
                if (overlap <= 0.0)
                    continue;

                // do_drag applied -1.7·V·1.0·v here; we want -1.7·V·strength·v, so hand back the difference.
                angular.cross(world.x() - origin.x(), world.y() - origin.y(), world.z() - origin.z(), velocity).add(linear);
                velocity.mul(gain * overlap, impulse);
                inverse.transform(impulse); // applyForce takes a body-frame point and a body-frame force

                Rapier3DAccessor.sablestopnow$applyForce(handle, bodyId,
                    local.x(), local.y(), local.z(), impulse.x(), impulse.y(), impulse.z(), false);
            }
        }
    }

    /**
     * Water lookups restricted to what the native drag loop can see: Sable feeds only <b>global</b> chunk uploads
     * into the liquid octree ({@code Rapier3D.addChunk}'s {@code global} branch), so plot blocks are never water to
     * it, and an unloaded chunk holds nothing at all - hence {@code getChunkNow} over a loading read.
     */
    private static final class FluidProbe {

        private final ServerLevel level;
        private final ServerSubLevelContainer container;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        private LevelChunk cached;
        private int cachedX = Integer.MIN_VALUE, cachedZ = Integer.MIN_VALUE;

        FluidProbe(final ServerLevel level, final ServerSubLevelContainer container) {
            this.level = level;
            this.container = container;
        }

        /** Volume of the {@code 2·size} cube centred on {@code centre} that sits in water. */
        double submergedVolume(final Vector3dc centre, final double size) {
            final double loX = centre.x() - size, hiX = centre.x() + size;
            final double loY = centre.y() - size, hiY = centre.y() + size;
            final double loZ = centre.z() - size, hiZ = centre.z() + size;
            double volume = 0.0;
            for (int y = (int) Math.floor(loY); y <= (int) Math.ceil(hiY) - 1; y++) {
                final double dy = Math.min(hiY, y + 1.0) - Math.max(loY, y);
                if (dy <= 0.0)
                    continue;
                for (int z = (int) Math.floor(loZ); z <= (int) Math.ceil(hiZ) - 1; z++) {
                    final double dz = Math.min(hiZ, z + 1.0) - Math.max(loZ, z);
                    if (dz <= 0.0)
                        continue;
                    for (int x = (int) Math.floor(loX); x <= (int) Math.ceil(hiX) - 1; x++) {
                        final double dx = Math.min(hiX, x + 1.0) - Math.max(loX, x);
                        if (dx > 0.0 && this.isLiquid(x, y, z))
                            volume += dx * dy * dz;
                    }
                }
            }
            return volume;
        }

        private boolean isLiquid(final int x, final int y, final int z) {
            final int chunkX = SectionPos.blockToSectionCoord(x), chunkZ = SectionPos.blockToSectionCoord(z);
            if (chunkX != this.cachedX || chunkZ != this.cachedZ) {
                this.cachedX = chunkX;
                this.cachedZ = chunkZ;
                this.cached = this.container.getPlot(chunkX, chunkZ) != null
                    ? null // a plot column: its blocks go to the owning body, never to the level's liquid octree
                    : this.level.getChunkSource().getChunkNow(chunkX, chunkZ);
            }
            return this.cached != null
                && VoxelNeighborhoodState.isLiquid(this.cached.getBlockState(this.cursor.set(x, y, z)));
        }
    }
}
