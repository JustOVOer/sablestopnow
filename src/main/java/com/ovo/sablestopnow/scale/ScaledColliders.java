package com.ovo.sablestopnow.scale;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

import com.ovo.sablestopnow.mixin.scale.Rapier3DAccessor;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderData;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;

/**
 * Gives <b>scaled</b> sub-levels a matching rapier terrain collider by <b>resampling the voxel lattice</b>.
 *
 * <p>Native rapier owns the voxel body geometry (chunks of packed per-cell collider ids uploaded via
 * {@code Rapier3D.addChunk}) and exposes no shape-scale API &mdash; but the collider <em>entries</em> those cells
 * reference are plain Java-defined box lists, and the native body transform is the <em>unscaled</em> pose
 * ({@code world = pos + R·(cell − centerOfMass)}). So placing every block's collision boxes at
 * {@code rotPoint + S·(blockPos − rotPoint)} makes the native transform land them exactly on the scaled hull:
 * {@code pos + R·S·(blockPos − rotPoint)}. Boxes are fragmented at cell boundaries (each fragment stays inside its
 * cell, which native provably supports), and fragments are deduplicated into cached collider entries &mdash; for
 * clean scales like &times;0.5 or &times;2 the whole hull collapses to a handful of entries.</p>
 *
 * <p>{@code RapierPhysicsPipelineMixin} cancels Sable's stock chunk uploads for managed (scale &ne; 1) sub-levels
 * and routes them here as dirty marks; {@link #flushAll} runs once per server tick and rebuilds dirty bodies with
 * a full remove-and-reupload sync (plots are vehicle-sized, so a full rebuild is cheap). Returning to scale 1
 * removes the resampled sections and replays Sable's stock uploads.</p>
 *
 * <p>Mass and inertia are scaled to match by {@link ScaledMass} (each entry's buoyancy {@code volume} here is the
 * scaled fragment volume, so weight and displacement stay consistent and vehicles keep their waterline).
 * Known approximations: impact block-callbacks (e.g. explosive blocks detonating on collision) are not wired on
 * scaled bodies (the contact→block mapping would need un-resampling).</p>
 *
 * <p>中文摘要：Sable 的 rapier 刚体没有缩放 API，但碰撞体条目（每格的盒子列表）是纯 Java 数据，而刚体变换用的是
 * <b>未缩放</b>的位姿。于是把每个方块的碰撞盒按 {@code rotPoint + S·(blockPos − rotPoint)} 重新放进体素晶格，
 * 原生变换就能把它们精确落在缩放后的外壳上。质量/惯量由 {@link ScaledMass} 同步。</p>
 */
public final class ScaledColliders {

    private static final Logger LOGGER = LogUtils.getLogger();
    /** Safety valve: skip resampling for absurd cell counts (huge vehicle × large scale). */
    private static final long MAX_CELLS = 2_000_000L;
    private static final int QUANT = 32; // box coords quantized to 1/32 for entry deduplication

    private static final Map<ServerLevel, LevelState> LEVELS = new WeakHashMap<>();
    private static final Map<EntryKey, RapierVoxelColliderData> ENTRY_CACHE = new HashMap<>();

    private ScaledColliders() {}

    private static final class LevelState {
        final RapierPhysicsPipeline pipeline;
        final Set<UUID> dirty = new LinkedHashSet<>();
        final Map<UUID, LongSet> uploadedSections = new HashMap<>();
        /** Per managed body: the cells Sable's water drag hits, and the strength it should hit them with. */
        final Map<UUID, ScaledDrag.Profile> dragProfiles = new HashMap<>();
        /** Rotation point each managed body's lattice was last resampled around. */
        final Map<UUID, Vector3d> rebuildAnchor = new HashMap<>();
        /** Plot sections Sable believes are in the native scene but aren't (add cancelled, or dropped by us). */
        final LongSet suppressedStock = new LongOpenHashSet();

        LevelState(final RapierPhysicsPipeline pipeline) {
            this.pipeline = pipeline;
        }
    }

    public static synchronized void register(final ServerLevel level, final RapierPhysicsPipeline pipeline) {
        LEVELS.put(level, new LevelState(pipeline));
    }

    /** True when this sub-level's stock voxel uploads should be suppressed (we own its collider). */
    public static boolean isManaged(final ServerSubLevel subLevel) {
        final Vector3dc scale = subLevel.logicalPose().scale();
        return scale.x() != 1.0 || scale.y() != 1.0 || scale.z() != 1.0;
    }

    /**
     * This body's water-drag correction, or null when it needs none (unscaled lattice, or over
     * {@link ScaledDrag}'s budget). The cells it carries are exactly the ones Sable's buoyancy pass can reach:
     * {@code insert_block_octree} keeps a cell out of the body octree when its neighbourhood is {@code INTERIOR}.
     */
    public static synchronized ScaledDrag.Profile dragProfile(final ServerSubLevel subLevel) {
        final LevelState state = LEVELS.get(subLevel.getLevel());
        return state == null ? null : state.dragProfiles.get(subLevel.getUniqueId());
    }

    public static synchronized void markDirty(final ServerSubLevel subLevel) {
        final LevelState state = LEVELS.get(subLevel.getLevel());
        if (state != null)
            state.dirty.add(subLevel.getUniqueId());
    }

    /** A stock section add was cancelled - remember so the matching stock removal is cancelled too. */
    public static synchronized void onStockAddSuppressed(final ServerLevel level, final long sectionKey) {
        final LevelState state = LEVELS.get(level);
        if (state != null)
            state.suppressedStock.add(sectionKey);
    }

    /** @return true (consuming the mark) when this section isn't in the native scene and the removal must be cancelled. */
    public static synchronized boolean consumeSuppressedStock(final ServerLevel level, final long sectionKey) {
        final LevelState state = LEVELS.get(level);
        return state != null && state.suppressedStock.remove(sectionKey);
    }

    /**
     * Called from the scale command; also covers the return to scale 1 (cleanup + stock replay). Rebuilds
     * synchronously - the command then immediately re-uploads mass properties and bounds via
     * {@code onStatsChanged}, and doing the lattice in the same breath keeps collider, bounds and mass consistent
     * within the tick (the deferred flush would leave the old-scale lattice under the new-scale mass for the rest
     * of it). Falls back to a tick-end retry when the rebuild fails.
     *
     * <p>中文：缩放命令改完位姿后调用；scale 回到 1 也走这里（清理 + 把碰撞体交还 Sable）。同步重建，
     * 随后命令会立刻 {@code onStatsChanged} 重传质量与包围盒，保证同一 tick 内三者一致。</p>
     */
    public static synchronized void onScaleChanged(final ServerSubLevel subLevel) {
        final LevelState state = LEVELS.get(subLevel.getLevel());
        if (state == null || subLevel.isRemoved())
            return;
        try {
            rebuild(state, subLevel);
            state.dirty.remove(subLevel.getUniqueId());
            // The lattice pivots on the rotation point; a scale change moved every anchor, so re-aim this body's
            // joints onto the fresh geometry before the next physics step (see ScaledConstraints.reaim).
            ScaledConstraints.reaim(subLevel);
        } catch (final Throwable t) {
            LOGGER.error("[SableStopNow] failed to rebuild scaled collider for {}", subLevel, t);
            state.dirty.add(subLevel.getUniqueId());
        }
    }

    /** Remove our native chunks before the body itself goes away. */
    public static synchronized void onBodyRemoved(final ServerSubLevel subLevel) {
        final LevelState state = LEVELS.get(subLevel.getLevel());
        if (state == null)
            return;
        state.dirty.remove(subLevel.getUniqueId());
        state.rebuildAnchor.remove(subLevel.getUniqueId());
        state.dragProfiles.remove(subLevel.getUniqueId());
        ScaledConstraints.forget(subLevel.getUniqueId());
        final LongSet uploaded = state.uploadedSections.remove(subLevel.getUniqueId());
        if (uploaded != null)
            removeSections(state, uploaded);
    }

    /**
     * Rebuilds the resampled lattice immediately when the rotation point moved (a mass change re-anchored the
     * pose). Called from inside {@code onStatsChanged} - i.e. synchronously with the CoM teleport and BEFORE the
     * physics step runs. Waiting for the tick-end flush left the native colliders offset by {@code R·(I−S)·Δ} for
     * the rest of the tick, and the contact solver turned that into a visible impulse ("twitch") on every block
     * place/break. No-op when the anchor hasn't moved, so per-tick contraption CoM churn stays cheap.
     */
    public static synchronized void rebuildIfRotationPointMoved(final ServerSubLevel subLevel) {
        final LevelState state = LEVELS.get(subLevel.getLevel());
        if (state == null || subLevel.isRemoved())
            return;
        final Vector3d anchor = state.rebuildAnchor.get(subLevel.getUniqueId());
        if (anchor != null && anchor.distanceSquared(subLevel.logicalPose().rotationPoint()) < 1.0E-8)
            return;
        try {
            rebuild(state, subLevel);
            state.dirty.remove(subLevel.getUniqueId());
            // The rotation point just moved (a block was added/removed), so the resampled geometry shifted under
            // every joint anchored to this body. Re-aim them onto it now - this is the fix for a constrained scaled
            // contraption drifting further apart with each block placed (drift = (1−S)·Δ(rotationPoint)).
            ScaledConstraints.reaim(subLevel);
        } catch (final Throwable t) {
            LOGGER.error("[SableStopNow] failed to rebuild scaled collider for {}", subLevel, t);
        }
    }

    public static synchronized void flushAll() {
        for (final Map.Entry<ServerLevel, LevelState> entry : LEVELS.entrySet()) {
            final LevelState state = entry.getValue();
            if (state.dirty.isEmpty())
                continue;
            final SubLevelContainer container = SubLevelContainer.getContainer(entry.getKey());
            for (final UUID uuid : state.dirty.toArray(UUID[]::new)) {
                final SubLevel subLevel = container == null ? null : container.getSubLevel(uuid);
                if (subLevel instanceof ServerSubLevel serverSubLevel && !serverSubLevel.isRemoved()) {
                    try {
                        rebuild(state, serverSubLevel);
                    } catch (final Throwable t) {
                        LOGGER.error("[SableStopNow] failed to rebuild scaled collider for {}", serverSubLevel, t);
                    }
                } else {
                    state.dragProfiles.remove(uuid);
                    final LongSet uploaded = state.uploadedSections.remove(uuid);
                    if (uploaded != null)
                        removeSections(state, uploaded);
                }
            }
            state.dirty.clear();
        }
    }

    // ------------------------------------------------------------------------------------------

    private static void rebuild(final LevelState state, final ServerSubLevel subLevel) {
        final UUID uuid = subLevel.getUniqueId();
        final long handle = ((ScaledColliderPipeline) (Object) state.pipeline).sablestopnow$sceneHandle();
        final int bodyId = Rapier3D.getID(subLevel);
        final boolean managed = isManaged(subLevel);
        final LongSet previouslyUploaded = state.uploadedSections.get(uuid);
        final boolean firstTransition = managed && previouslyUploaded == null;

        if (!managed) {
            // Back to scale 1: hand the collider back to Sable by replaying its stock uploads.
            if (previouslyUploaded != null) {
                removeSections(state, previouslyUploaded);
                state.uploadedSections.remove(uuid);
            }
            state.rebuildAnchor.remove(uuid);
            state.dragProfiles.remove(uuid);
            replayStockSections(state, subLevel);
            return;
        }

        // Resample BEFORE touching the native scene: when it bails over the cell budget, whatever collider is
        // currently uploaded (the stock one on a first transition) must stay - removing first left the body
        // with no terrain collider at all.
        final Resampled resampled = resample(subLevel);
        if (resampled == null)
            return; // over budget - vehicle keeps its current collider
        final Long2ObjectMap<int[]> sections = resampled.sections();

        // Entering managed state: the plot's stock voxel chunks are still in the native scene - drop them.
        if (firstTransition)
            removeStockSections(state, subLevel);

        // Drop our previous upload wholesale (full sync keeps the bookkeeping trivial).
        if (previouslyUploaded != null) {
            removeSections(state, previouslyUploaded);
            state.uploadedSections.remove(uuid);
        }

        final LongSet uploaded = new LongOpenHashSet(sections.size());
        for (final Long2ObjectMap.Entry<int[]> section : sections.long2ObjectEntrySet()) {
            final long key = section.getLongKey();
            Rapier3DAccessor.sablestopnow$addChunk(handle, SectionPos.x(key), SectionPos.y(key), SectionPos.z(key), section.getValue(), false, bodyId);
            uploaded.add(key);
        }
        state.uploadedSections.put(uuid, uploaded);
        state.rebuildAnchor.put(uuid, new Vector3d(subLevel.logicalPose().rotationPoint()));
        state.dragProfiles.put(uuid, ScaledDrag.profile(subLevel, resampled.surfaceCells(), resampled.unscaledSurface()));
        applyScaledLocalBounds(handle, bodyId, subLevel);
    }

    private static void removeSections(final LevelState state, final LongSet sections) {
        final long handle = ((ScaledColliderPipeline) (Object) state.pipeline).sablestopnow$sceneHandle();
        for (final long key : sections)
            Rapier3DAccessor.sablestopnow$removeChunk(handle, SectionPos.x(key), SectionPos.y(key), SectionPos.z(key), false);
    }

    /** Drop the plot's stock sections from the native scene, marking them suppressed so Sable's own removal is skipped. */
    private static void removeStockSections(final LevelState state, final ServerSubLevel subLevel) {
        final long handle = ((ScaledColliderPipeline) (Object) state.pipeline).sablestopnow$sceneHandle();
        forEachLoadedSection(subLevel, (chunk, sectionIndex) -> {
            final int y = chunk.getSectionYFromSectionIndex(sectionIndex);
            final long key = SectionPos.asLong(chunk.getPos().x, y, chunk.getPos().z);
            if (state.suppressedStock.add(key)) // not already suppressed -> it IS in the native scene
                Rapier3DAccessor.sablestopnow$removeChunk(handle, chunk.getPos().x, y, chunk.getPos().z, false);
        });
    }

    /** Hand the collider back to Sable: replay its stock uploads for every loaded plot section. */
    private static void replayStockSections(final LevelState state, final ServerSubLevel subLevel) {
        forEachLoadedSection(subLevel, (chunk, sectionIndex) -> {
            final int y = chunk.getSectionYFromSectionIndex(sectionIndex);
            state.suppressedStock.remove(SectionPos.asLong(chunk.getPos().x, y, chunk.getPos().z));
            final LevelChunkSection section = chunk.getSections()[sectionIndex];
            state.pipeline.handleChunkSectionAddition(section, chunk.getPos().x, y, chunk.getPos().z, true);
        });
    }

    private interface SectionVisitor {
        void visit(LevelChunk chunk, int sectionIndex);
    }

    private static void forEachLoadedSection(final ServerSubLevel subLevel, final SectionVisitor visitor) {
        final ServerLevelPlot plot = subLevel.getPlot();
        for (final PlotChunkHolder holder : plot.getLoadedChunks()) {
            final LevelChunk chunk = holder.getChunk();
            for (int i = 0; i < chunk.getSections().length; i++)
                visitor.visit(chunk, i);
        }
    }

    /**
     * The scaled plot-local cell bounds this mod hands Sable's {@code setLocalBounds}: {@code {minX, minY, minZ,
     * maxX, maxY, maxZ}}. Native uses them for more than a bounding box - they size the body octree and anchor its
     * index space ({@code find_collision_pairs} reports leaves as {@code node.min + local_bounds_min}) - so they
     * have to describe the resampled lattice, not the unscaled plot.
     */
    public static int[] scaledLocalBounds(final ServerSubLevel subLevel) {
        final BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        final Vector3dc rp = subLevel.logicalPose().rotationPoint();
        final Vector3dc s = subLevel.logicalPose().scale();
        final double minX = rp.x() + s.x() * (bounds.minX() - rp.x()), maxX = rp.x() + s.x() * (bounds.maxX() + 1 - rp.x());
        final double minY = rp.y() + s.y() * (bounds.minY() - rp.y()), maxY = rp.y() + s.y() * (bounds.maxY() + 1 - rp.y());
        final double minZ = rp.z() + s.z() * (bounds.minZ() - rp.z()), maxZ = rp.z() + s.z() * (bounds.maxZ() + 1 - rp.z());
        return new int[]{
            (int) Math.floor(Math.min(minX, maxX)), (int) Math.floor(Math.min(minY, maxY)), (int) Math.floor(Math.min(minZ, maxZ)),
            (int) Math.ceil(Math.max(maxX, minX)), (int) Math.ceil(Math.max(maxY, minY)), (int) Math.ceil(Math.max(maxZ, minZ))};
    }

    public static void applyScaledLocalBounds(final long handle, final int bodyId, final ServerSubLevel subLevel) {
        final int[] bounds = scaledLocalBounds(subLevel);
        Rapier3DAccessor.sablestopnow$setLocalBounds(handle, bodyId,
            bounds[0], bounds[1], bounds[2], bounds[3], bounds[4], bounds[5]);
    }

    // ------------------------------------------------------------------------------------------

    /** Per-cell accumulator for scaled box fragments. */
    private static final class CellBuilder {
        double[] boxes = new double[6 * 4];
        int boxCount;
        double totalVolume;
        double dominantVolume;
        float friction = 1.0F;
        float restitution;

        void add(final double minX, final double minY, final double minZ, final double maxX, final double maxY, final double maxZ,
                 final float boxFriction, final float boxRestitution) {
            if (this.boxCount * 6 == this.boxes.length)
                this.boxes = Arrays.copyOf(this.boxes, this.boxes.length * 2);
            final int base = this.boxCount * 6;
            this.boxes[base] = minX;
            this.boxes[base + 1] = minY;
            this.boxes[base + 2] = minZ;
            this.boxes[base + 3] = maxX;
            this.boxes[base + 4] = maxY;
            this.boxes[base + 5] = maxZ;
            this.boxCount++;
            final double volume = (maxX - minX) * (maxY - minY) * (maxZ - minZ);
            this.totalVolume += volume;
            if (volume > this.dominantVolume) {
                this.dominantVolume = volume;
                this.friction = boxFriction;
                this.restitution = boxRestitution;
            }
        }

        boolean full() {
            return this.totalVolume >= 0.999;
        }
    }

    /**
     * The scaled hull's native upload (section key → packed voxel data), plus what {@link ScaledDrag} needs to fix
     * its water drag: the lattice's surface cells and the surface cell count the same hull has at scale 1.
     */
    private record Resampled(Long2ObjectMap<int[]> sections, long[] surfaceCells, int unscaledSurface) {}

    /** @return the resampled hull, or null when over the cell budget. */
    private static Resampled resample(final ServerSubLevel subLevel) {
        final ServerLevel level = subLevel.getLevel();
        final BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        final Vector3dc rp = subLevel.logicalPose().rotationPoint();
        final Vector3dc s = subLevel.logicalPose().scale();

        final long sourceCells = (long) (bounds.maxX() - bounds.minX() + 1) * (bounds.maxY() - bounds.minY() + 1) * (bounds.maxZ() - bounds.minZ() + 1);
        if (sourceCells * Math.max(1.0, s.x() * s.y() * s.z()) > MAX_CELLS) {
            LOGGER.warn("[SableStopNow] {} is too large to resample its collider at scale {} - keeping the unscaled collider", subLevel, s);
            return null;
        }

        final Long2ObjectMap<CellBuilder> cells = new Long2ObjectOpenHashMap<>();
        // The unscaled block grid, kept so we can count the surface cells this hull would have at scale 1 - the
        // reference drag ScaledDrag scales onto (it cannot be read back off the scaled lattice once the hull is
        // small enough that its cell count stops following the S² area law).
        final LongSet solidBlocks = new LongOpenHashSet();
        final LongSet fullBlocks = new LongOpenHashSet();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
            for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
                    cursor.set(x, y, z);
                    final var blockState = level.getBlockState(cursor);
                    if (blockState.isAir())
                        continue;
                    final VoxelShape shape = blockState.getCollisionShape(level, cursor);
                    if (shape.isEmpty())
                        continue;
                    final float friction = (float) PhysicsBlockPropertyHelper.getFriction(blockState);
                    final float restitution = (float) PhysicsBlockPropertyHelper.getRestitution(blockState);
                    double blockVolume = 0.0;
                    for (final AABB box : shape.toAabbs()) {
                        blockVolume += (box.maxX - box.minX) * (box.maxY - box.minY) * (box.maxZ - box.minZ);
                        fragment(cells,
                            rp.x() + s.x() * (x + box.minX - rp.x()), rp.y() + s.y() * (y + box.minY - rp.y()), rp.z() + s.z() * (z + box.minZ - rp.z()),
                            rp.x() + s.x() * (x + box.maxX - rp.x()), rp.y() + s.y() * (y + box.maxY - rp.y()), rp.z() + s.z() * (z + box.maxZ - rp.z()),
                            friction, restitution);
                    }
                    final long blockKey = BlockPos.asLong(x, y, z);
                    solidBlocks.add(blockKey);
                    if (blockVolume >= 0.999)
                        fullBlocks.add(blockKey);
                }
            }
        }

        // Pack cells into per-section arrays; enclosed full cells become INTERIOR like stock, the rest CORNER
        // (all faces active - resampled cells have no reliable level-side neighbourhood to consult).
        final Long2ObjectMap<int[]> sections = new Long2ObjectOpenHashMap<>();
        final LongList surface = new LongArrayList();
        for (final Long2ObjectMap.Entry<CellBuilder> entry : cells.long2ObjectEntrySet()) {
            final long cellKey = entry.getLongKey();
            final CellBuilder cell = entry.getValue();
            final int cellX = BlockPos.getX(cellKey), cellY = BlockPos.getY(cellKey), cellZ = BlockPos.getZ(cellKey);
            final boolean interior = cell.full() && isEnclosed(cells, cellX, cellY, cellZ);
            final int neighborhood = interior ? 4 /* INTERIOR */ : 3 /* CORNER */;
            final int collider = getOrCreateEntry(cell).handle();
            final int packed = neighborhood | collider + 1 << 16;
            final long sectionKey = SectionPos.asLong(cellX >> 4, cellY >> 4, cellZ >> 4);
            final int[] data = sections.computeIfAbsent(sectionKey, key -> new int[4096]);
            data[(cellX & 15) + ((cellZ & 15) << 4) + ((cellY & 15) << 8)] = packed;
            // Native keeps INTERIOR cells out of the body octree, so only these ever see water (see ScaledDrag).
            if (!interior)
                surface.add(cellKey);
        }

        return new Resampled(sections, surface.toLongArray(), unscaledSurface(solidBlocks, fullBlocks));
    }

    /**
     * How many cells of this hull would reach Sable's buoyancy loop at scale 1: every solid block except the ones
     * a stock upload would mark {@code INTERIOR}. Same enclosure rule as the scaled lattice below, one cell per
     * block instead of per fragment.
     */
    private static int unscaledSurface(final LongSet solidBlocks, final LongSet fullBlocks) {
        int surface = 0;
        for (final long key : solidBlocks) {
            final int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
            if (!fullBlocks.contains(key) || !isEnclosedBlock(fullBlocks, x, y, z))
                surface++;
        }
        return surface;
    }

    private static boolean isEnclosedBlock(final LongSet fullBlocks, final int x, final int y, final int z) {
        return fullBlocks.contains(BlockPos.asLong(x - 1, y, z)) && fullBlocks.contains(BlockPos.asLong(x + 1, y, z))
            && fullBlocks.contains(BlockPos.asLong(x, y - 1, z)) && fullBlocks.contains(BlockPos.asLong(x, y + 1, z))
            && fullBlocks.contains(BlockPos.asLong(x, y, z - 1)) && fullBlocks.contains(BlockPos.asLong(x, y, z + 1));
    }

    private static boolean isEnclosed(final Long2ObjectMap<CellBuilder> cells, final int x, final int y, final int z) {
        return isFull(cells, x - 1, y, z) && isFull(cells, x + 1, y, z)
            && isFull(cells, x, y - 1, z) && isFull(cells, x, y + 1, z)
            && isFull(cells, x, y, z - 1) && isFull(cells, x, y, z + 1);
    }

    private static boolean isFull(final Long2ObjectMap<CellBuilder> cells, final int x, final int y, final int z) {
        final CellBuilder cell = cells.get(BlockPos.asLong(x, y, z));
        return cell != null && cell.full();
    }

    /** Splits a scaled plot-space box at integer cell boundaries and stores the cell-local fragments. */
    private static void fragment(final Long2ObjectMap<CellBuilder> cells,
                                 final double minX, final double minY, final double minZ,
                                 final double maxX, final double maxY, final double maxZ,
                                 final float friction, final float restitution) {
        final double loX = Math.min(minX, maxX), hiX = Math.max(minX, maxX);
        final double loY = Math.min(minY, maxY), hiY = Math.max(minY, maxY);
        final double loZ = Math.min(minZ, maxZ), hiZ = Math.max(minZ, maxZ);
        final int cellMinX = (int) Math.floor(loX), cellMaxX = (int) Math.ceil(hiX) - 1;
        final int cellMinY = (int) Math.floor(loY), cellMaxY = (int) Math.ceil(hiY) - 1;
        final int cellMinZ = (int) Math.floor(loZ), cellMaxZ = (int) Math.ceil(hiZ) - 1;
        for (int cy = cellMinY; cy <= cellMaxY; cy++) {
            final double fMinY = Math.max(loY, cy) - cy, fMaxY = Math.min(hiY, cy + 1.0) - cy;
            if (fMaxY - fMinY < 1.0E-4)
                continue;
            for (int cz = cellMinZ; cz <= cellMaxZ; cz++) {
                final double fMinZ = Math.max(loZ, cz) - cz, fMaxZ = Math.min(hiZ, cz + 1.0) - cz;
                if (fMaxZ - fMinZ < 1.0E-4)
                    continue;
                for (int cx = cellMinX; cx <= cellMaxX; cx++) {
                    final double fMinX = Math.max(loX, cx) - cx, fMaxX = Math.min(hiX, cx + 1.0) - cx;
                    if (fMaxX - fMinX < 1.0E-4)
                        continue;
                    cells.computeIfAbsent(BlockPos.asLong(cx, cy, cz), key -> new CellBuilder())
                        .add(fMinX, fMinY, fMinZ, fMaxX, fMaxY, fMaxZ, friction, restitution);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    /** Cache key: the cell's quantized fragment list + surface properties. */
    private static final class EntryKey {
        private final short[] data;
        private final int hash;

        EntryKey(final short[] data) {
            this.data = data;
            this.hash = Arrays.hashCode(data);
        }

        @Override
        public boolean equals(final Object obj) {
            return obj instanceof EntryKey other && Arrays.equals(this.data, other.data);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }

    private static synchronized RapierVoxelColliderData getOrCreateEntry(final CellBuilder cell) {
        final short[] key = new short[cell.boxCount * 6 + 2];
        for (int i = 0; i < cell.boxCount * 6; i++)
            key[i] = (short) Math.round(cell.boxes[i] * QUANT);
        key[cell.boxCount * 6] = (short) Math.round(cell.friction * 1000.0F);
        key[cell.boxCount * 6 + 1] = (short) Math.round(cell.restitution * 1000.0F);
        return ENTRY_CACHE.computeIfAbsent(new EntryKey(key), entryKey -> {
            final RapierVoxelColliderData entry = Rapier3D.createVoxelColliderEntry(cell.friction, cell.totalVolume, cell.restitution, false, null);
            for (int i = 0; i < cell.boxCount; i++) {
                final int base = i * 6;
                entry.addBox(
                    new Vector3d(entryKey.data[base] / (double) QUANT, entryKey.data[base + 1] / (double) QUANT, entryKey.data[base + 2] / (double) QUANT),
                    new Vector3d(entryKey.data[base + 3] / (double) QUANT, entryKey.data[base + 4] / (double) QUANT, entryKey.data[base + 5] / (double) QUANT));
            }
            return entry;
        });
    }
}
