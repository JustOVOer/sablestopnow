package com.ovo.sablestopnow.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.SablestopNowConfig;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Quaternionf;
import org.joml.Vector3dc;
import com.ovo.sablestopnow.client.ModRenderTypes;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@EventBusSubscriber(modid = "sablestopnow", value = Dist.CLIENT)
public class SubLevelOutlineRenderer {

    private static final int MAX_RENDER_DISTANCE = 64;
    private static boolean loggedConfig = false;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;

        boolean enabled = SablestopNowConfig.INSTANCE.renderSubLevelOutlines.get();
        if (!loggedConfig) {
            SablestopNow.LOGGER.info("renderSubLevelOutlines = {}, outlineOnlyContour = {}, outlineOnlyFocused = {}, renderAxis = {}",
                    enabled, SablestopNowConfig.INSTANCE.outlineOnlyContour.get(),
                    SablestopNowConfig.INSTANCE.outlineOnlyFocused.get(),
                    SablestopNowConfig.INSTANCE.renderAxis.get());
            loggedConfig = true;
        }
        if (!enabled) return;

        // 碰撞箱缓存按帧清一次：同一个方块会被它周围最多 36 个面问到，
        // 不清缓存会跨帧无限增长，每帧清一次则刚好覆盖一帧内的重复查询。
        clearBoxCache();

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return;

        ClientSubLevelContainer container = (ClientSubLevelContainer) SubLevelContainer.getContainer(level);
        if (container == null) return;

        Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) return;

        Vec3 cameraPos = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        Collection<ClientSubLevel> subLevels = container.getAllSubLevels();
        if (subLevels.isEmpty()) return;

        boolean alwaysVisible = SablestopNowConfig.INSTANCE.outlineAlwaysVisible.get();
        boolean onlyContour = SablestopNowConfig.INSTANCE.outlineOnlyContour.get();
        boolean onlyFocused = SablestopNowConfig.INSTANCE.outlineOnlyFocused.get();
        boolean renderAxis = SablestopNowConfig.INSTANCE.renderAxis.get();
        float axisAngleDeg = SablestopNowConfig.INSTANCE.axisAngleDegrees.get().floatValue();
        float outlineThickness = SablestopNowConfig.outlineThickness();

        UUID focusedSubLevelId = null;
        if (onlyFocused) {
            if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
                BlockPos blockPos = ((BlockHitResult) mc.hitResult).getBlockPos();
                SubLevel subLevel = Sable.HELPER.getContaining(level, blockPos);
                if (subLevel != null) {
                    focusedSubLevelId = subLevel.getUniqueId();
                }
            }
            if (focusedSubLevelId == null) {
                SablestopNow.LOGGER.trace("No focused sub-level, skipping outlines");
                return;
            }
        }

        // ⚠ 自建 RenderType 不在 bufferSource 的 fixedBuffers 里，会共用同一个 shared buffer：
        // 一旦再 getBuffer(另一个自建类型)，前一个 BufferBuilder 会被立刻 endBatch，此后继续写顶点
        // 就是 IllegalStateException("Not building!")。因此「盒式边框」和「GL 线（坐标轴）」必须分段画，
        // 中间先 endBatch。
        final RenderType lineType = alwaysVisible ? ModRenderTypes.LINES_NO_DEPTH : ModRenderTypes.LINES;
        final RenderType boxType = alwaysVisible ? ModRenderTypes.BOXES_NO_DEPTH : ModRenderTypes.BOXES;

        // 先筛出本帧要画的物理体（已移除 / 非注视目标 / 超距离直接排除）
        final java.util.List<ClientSubLevel> visible = new java.util.ArrayList<>();
        for (final ClientSubLevel subLevel : subLevels) {
            if (subLevel.isRemoved()) {
                continue;
            }
            if (focusedSubLevelId != null && !subLevel.getUniqueId().equals(focusedSubLevelId)) {
                continue;
            }
            final Vector3dc centerJoml = subLevel.renderPose().position();
            final Vec3 center = new Vec3(centerJoml.x(), centerJoml.y(), centerJoml.z());
            if (center.distanceTo(cameraPos) > MAX_RENDER_DISTANCE) {
                continue;
            }
            visible.add(subLevel);
        }

        int totalEdges = 0;
        if (onlyContour) {
            // 第一段：细长长方体盒（方块轮廓）
            final VertexConsumer boxConsumer = bufferSource.getBuffer(boxType);
            for (final ClientSubLevel subLevel : visible) {
                totalEdges += drawSubLevelContourBlocks(poseStack, boxConsumer, camera, level, subLevel, alwaysVisible, outlineThickness);
            }
            if (totalEdges > 0) {
                bufferSource.endBatch(boxType);
            }
            // 第二段：坐标轴（GL 线）
            if (renderAxis) {
                final VertexConsumer axisConsumer = bufferSource.getBuffer(lineType);
                for (final ClientSubLevel subLevel : visible) {
                    drawAxis(poseStack, axisConsumer, camera, subLevel.renderPose(), axisAngleDeg);
                }
                bufferSource.endBatch(lineType);
            }
        } else {
            // 整块线框模式：方块与坐标轴同为 GL 线，可以共用一个 consumer
            final VertexConsumer lineConsumer = bufferSource.getBuffer(lineType);
            for (final ClientSubLevel subLevel : visible) {
                totalEdges += drawSubLevelFullBlocks(poseStack, lineConsumer, camera, level, subLevel, alwaysVisible);
            }
            if (renderAxis) {
                for (final ClientSubLevel subLevel : visible) {
                    drawAxis(poseStack, lineConsumer, camera, subLevel.renderPose(), axisAngleDeg);
                }
            }
            if (totalEdges > 0 || renderAxis) {
                bufferSource.endBatch(lineType);
            }
        }

        SablestopNow.LOGGER.trace("Drew {} edges", totalEdges);
    }

    /** 单个物理体的「仅描边边缘」盒式边框（颜色/缩放按 uuid 稳定生成）；返回画的棱数。 */
    private static int drawSubLevelContourBlocks(final PoseStack poseStack, final VertexConsumer vc, final Camera camera,
                                                 final Level level, final ClientSubLevel subLevel,
                                                 final boolean alwaysVisible, final float thickness) {
        final int uuidHash = subLevel.getUniqueId().hashCode();
        final float r = ((uuidHash >> 16) & 0xFF) / 255f;
        final float g = ((uuidHash >> 8) & 0xFF) / 255f;
        final float b = (uuidHash & 0xFF) / 255f;
        final float scale = alwaysVisible ? (0.98f + (Math.abs(uuidHash) & 0xFF) / 25500.0f) : 1.0f;
        final Pose3dc renderPose = subLevel.renderPose();

        int edges = 0;
        for (final var holder : subLevel.getPlot().getLoadedChunks()) {
            final var chunk = holder.getChunk();
            if (chunk == null) {
                continue;
            }
            final var chunkPos = chunk.getPos();
            final int minX = chunkPos.getMinBlockX();
            final int minZ = chunkPos.getMinBlockZ();
            final LevelChunkSection[] sections = chunk.getSections();
            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                final LevelChunkSection section = sections[sectionIndex];
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }
                final int minY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
                for (int dx = 0; dx < 16; dx++) {
                    for (int dy = 0; dy < 16; dy++) {
                        for (int dz = 0; dz < 16; dz++) {
                            if (section.getBlockState(dx, dy, dz).isAir()) {
                                continue;
                            }
                            final BlockPos pos = new BlockPos(minX + dx, minY + dy, minZ + dz);
                            if (isSurface(level, pos)) {
                                edges += drawBlockOutlineContour(poseStack, vc, camera, renderPose, pos, r, g, b, scale, thickness, level, subLevel);
                            }
                        }
                    }
                }
            }
        }
        return edges;
    }

    /** 单个物理体的整块线框描边；返回画的棱数。 */
    private static int drawSubLevelFullBlocks(final PoseStack poseStack, final VertexConsumer vc, final Camera camera,
                                              final Level level, final ClientSubLevel subLevel, final boolean alwaysVisible) {
        final int uuidHash = subLevel.getUniqueId().hashCode();
        final float r = ((uuidHash >> 16) & 0xFF) / 255f;
        final float g = ((uuidHash >> 8) & 0xFF) / 255f;
        final float b = (uuidHash & 0xFF) / 255f;
        final float scale = alwaysVisible ? (0.98f + (Math.abs(uuidHash) & 0xFF) / 25500.0f) : 1.0f;
        final Pose3dc renderPose = subLevel.renderPose();

        int edges = 0;
        for (final var holder : subLevel.getPlot().getLoadedChunks()) {
            final var chunk = holder.getChunk();
            if (chunk == null) {
                continue;
            }
            final var chunkPos = chunk.getPos();
            final int minX = chunkPos.getMinBlockX();
            final int minZ = chunkPos.getMinBlockZ();
            final LevelChunkSection[] sections = chunk.getSections();
            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                final LevelChunkSection section = sections[sectionIndex];
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }
                final int minY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
                for (int dx = 0; dx < 16; dx++) {
                    for (int dy = 0; dy < 16; dy++) {
                        for (int dz = 0; dz < 16; dz++) {
                            if (section.getBlockState(dx, dy, dz).isAir()) {
                                continue;
                            }
                            final BlockPos pos = new BlockPos(minX + dx, minY + dy, minZ + dz);
                            if (isSurface(level, pos)) {
                                edges += drawBlockOutlineFull(poseStack, vc, camera, renderPose, pos, r, g, b, scale);
                            }
                        }
                    }
                }
            }
        }
        return edges;
    }

    private static int drawBlockOutlineFull(PoseStack poseStack, VertexConsumer vertexConsumer, Camera camera,
                                            Pose3dc renderPose, BlockPos pos, float r, float g, float b, float scale) {
        Vec3 posCenter = pos.getCenter();
        Vector3dc transformedPosJoml = renderPose.transformPosition(new org.joml.Vector3d(posCenter.x, posCenter.y, posCenter.z));
        Vec3 transformedPos = new Vec3(transformedPosJoml.x(), transformedPosJoml.y(), transformedPosJoml.z());
        Vec3 relative = transformedPos.subtract(camera.getPosition());

        poseStack.pushPose();
        poseStack.translate(relative.x, relative.y, relative.z);
        poseStack.mulPose(new Quaternionf(renderPose.orientation()));

        // 方块位置已被 transformPosition 乘过 pose.scale，盒子本身也必须同样乘上 scale（否则整块线框模式同样会错位）。
        // 逐轴取 scale：物理体目前只会等比缩放，但按轴写才和逻辑变换 pos + R·S·(p − rotPoint) 完全一致，
        // 将来支持非等比缩放时也不会画歪（旧实现只乘 scale().x()）。
        final Vector3dc poseScale = renderPose.scale();
        final float halfX = (float) (0.5 * scale * poseScale.x());
        final float halfY = (float) (0.5 * scale * poseScale.y());
        final float halfZ = (float) (0.5 * scale * poseScale.z());
        AABB aabb = new AABB(-halfX, -halfY, -halfZ, halfX, halfY, halfZ);
        LevelRenderer.renderLineBox(poseStack, vertexConsumer, aabb, r, g, b, 1.0f);

        poseStack.popPose();
        return 12;
    }

    /**
     * 只描「暴露在空气中的棱线」（轮廓），<b>按方块的碰撞箱</b>取形状。
     *
     * <h2>为什么从「整格」改成「碰撞箱」</h2>
     * 以前无论方块长什么样都按 1×1×1 的整格描边，于是台阶、楼梯、栅栏、半砖这类方块
     * 描出来的框和它实际的物理形状对不上（用户实测反馈「模型描边要按碰撞箱描」）。
     * 现在取 {@code state.getCollisionShape(level, pos)} 的每一个子盒，逐个盒子判断六个面
     * 是否被邻居的碰撞箱挡住，再描没被挡住的那些面的棱。
     *
     * <p>碰撞箱为空的方块（火把、花草这类没有碰撞的装饰）退回它的视觉形状；
     * 两者都空时退回整格，避免「有的方块整个不参与描边」这种更难解释的表现。
     *
     * <p>「共面接缝不画」这条规则保留（否则平坦表面会被内部网格线切碎），
     * 但判定改成按<b>碰撞箱平面</b>比：邻居在同一平面上也有暴露面时才跳过这条棱。
     */
    private static int drawBlockOutlineContour(PoseStack poseStack, VertexConsumer vertexConsumer, Camera camera,
                                               Pose3dc renderPose, BlockPos pos, float r, float g, float b,
                                               float scale, float thickness, Level level, ClientSubLevel subLevel) {
        final List<AABB> boxes = collisionBoxes(level, pos);
        if (boxes.isEmpty()) {
            return 0;
        }
        diagBlock();
        Vec3 posCenter = pos.getCenter();
        Vector3dc transformedPosJoml = renderPose.transformPosition(new org.joml.Vector3d(posCenter.x, posCenter.y, posCenter.z));
        Vec3 transformedPos = new Vec3(transformedPosJoml.x(), transformedPosJoml.y(), transformedPosJoml.z());
        Vec3 relative = transformedPos.subtract(camera.getPosition());

        poseStack.pushPose();
        poseStack.translate(relative.x, relative.y, relative.z);
        poseStack.mulPose(new Quaternionf(renderPose.orientation()));

        // ⚠ 物理体整体缩放时，方块位置会被 renderPose.transformPosition 乘上 scale，
        // 但方块盒子本身必须同样乘上 scale，否则描边之间会出现空隙。
        final Vector3dc poseScale = renderPose.scale();
        final float sx = (float) (scale * poseScale.x());
        final float sy = (float) (scale * poseScale.y());
        final float sz = (float) (scale * poseScale.z());
        PoseStack.Pose pose = poseStack.last();

        int edgeCount = 0;
        // 去重键按「棱的<b>实际位置</b>」算，不能再用整格的角点下标：
        // 一个台阶的两个子盒可能共用同一个角点下标但位置完全不同（会把该画的棱吃掉）。
        final java.util.HashSet<Long> drawn = new java.util.HashSet<>();
        for (final AABB box : boxes) {
            for (final Direction faceDir : Direction.values()) {
                if (coveredByNeighbour(level, pos, box, faceDir)) {
                    continue;
                }
                // 该面的 4 条棱：轴对齐盒的每个面用两个垂直轴的 ± 组合表示
                final Direction.Axis a = faceDir.getAxis();
                final Direction.Axis[] pq = perpendicularAxes(a);
                final Direction.Axis p = pq[0];
                final Direction.Axis q = pq[1];
                final double plane = faceDir.getAxisDirection() == Direction.AxisDirection.POSITIVE
                        ? box.max(a) : box.min(a);
                for (int i = 0; i < 4; i++) {
                    // 面的四个角：(p 低/高) × (q 低/高)
                    final double[] c1 = {0, 0, 0};
                    final double[] c2 = {0, 0, 0};
                    final int e = i;
                    // 棱 i 的两端：固定一个轴，另一个轴走一遍
                    final boolean pFixed = (e & 1) == 0;
                    final boolean pHigh = (e & 2) != 0;
                    put(c1, a, plane);
                    put(c2, a, plane);
                    if (pFixed) {
                        put(c1, p, pHigh ? box.max(p) : box.min(p));
                        put(c2, p, pHigh ? box.max(p) : box.min(p));
                        put(c1, q, box.min(q));
                        put(c2, q, box.max(q));
                    } else {
                        put(c1, q, pHigh ? box.max(q) : box.min(q));
                        put(c2, q, pHigh ? box.max(q) : box.min(q));
                        put(c1, p, box.min(p));
                        put(c2, p, box.max(p));
                    }
                    final long key = edgeKey(c1, c2);
                    if (!drawn.add(key)) {
                        continue;
                    }
                    if (isInteriorSeamShape(level, subLevel, pos, box, faceDir, c1, c2)) {
                        DIAG_CULLED++;
                        continue;
                    }
                    BoxOutlineRenderer.addEdge(pose, vertexConsumer,
                            (float) ((c1[0] - 0.5) * sx), (float) ((c1[1] - 0.5) * sy), (float) ((c1[2] - 0.5) * sz),
                            (float) ((c2[0] - 0.5) * sx), (float) ((c2[1] - 0.5) * sy), (float) ((c2[2] - 0.5) * sz),
                            thickness, r, g, b, 1.0f);
                    DIAG_EDGES++;
                    edgeCount++;
                }
            }
        }
        poseStack.popPose();
        return edgeCount;
    }

    private static void put(final double[] v, final Direction.Axis axis, final double value) {
        switch (axis) {
            case X -> v[0] = value;
            case Y -> v[1] = value;
            case Z -> v[2] = value;
        }
    }

    private static Direction.Axis[] perpendicularAxes(final Direction.Axis axis) {
        return switch (axis) {
            case X -> new Direction.Axis[] {Direction.Axis.Y, Direction.Axis.Z};
            case Y -> new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Z};
            case Z -> new Direction.Axis[] {Direction.Axis.X, Direction.Axis.Y};
        };
    }

    /** 棱的位置键（方块局部 1/16 精度，端点排序后打包）。 */
    private static long edgeKey(final double[] a, final double[] b) {
        final int ea = quant(a);
        final int eb = quant(b);
        final int lo = Math.min(ea, eb);
        final int hi = Math.max(ea, eb);
        return ((long) lo << 32) | (hi & 0xFFFFFFFFL);
    }

    private static int quant(final double[] v) {
        final int x = (int) Math.round(v[0] * 16.0) & 0x3FF;
        final int y = (int) Math.round(v[1] * 16.0) & 0x3FF;
        final int z = (int) Math.round(v[2] * 16.0) & 0x3FF;
        return (x << 20) | (y << 10) | z;
    }

    /**
     * 方块的碰撞箱子盒（方块局部 0..1）；空碰撞箱退回视觉形状，再空则退回整格。
     *
     * <h2>TODO / 已知取舍</h2>
     * <ul>
     *   <li><b>性能未实测</b>：一次描边会对每个表面方块问 6 次邻居的碰撞箱；大结构（上千方块）
     *       上的开销还没在实机上量过。已经加了「一帧内按 BlockPos 缓存」把重复查询压掉，
     *       但如果实机掉帧，第一个要看的就是这里。</li>
     *   <li><b>预筛选仍是「有空气邻居」</b>：完全被非空气包围、但碰撞箱没填满整格的方块
     *       （例如一堵内部全是半砖的墙）不会被扫到。刻意的：那种方块的面本来也被邻居挡住了。</li>
     *   <li>没有碰撞箱也没有视觉形状的方块（火把、花草）按整格描边，宁可多画也不要整块消失。</li>
     * </ul>
     */
    private static List<AABB> collisionBoxes(final Level level, final BlockPos pos) {
        final List<AABB> cached = BOX_CACHE.get(pos);
        if (cached != null) {
            return cached;
        }
        final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            shape = state.getShape(level, pos);
        }
        final List<AABB> result = shape.isEmpty()
                ? List.of(new AABB(0, 0, 0, 1, 1, 1))
                : shape.toAabbs();
        BOX_CACHE.put(pos.immutable(), result);
        return result;
    }

    /** 一帧内的碰撞箱缓存（渲染是单线程的，不需要并发容器）。 */
    private static final java.util.HashMap<BlockPos, List<AABB>> BOX_CACHE = new java.util.HashMap<>();

    /** 每帧/每次描边开始时清空缓存。 */
    private static void clearBoxCache() {
        if (!BOX_CACHE.isEmpty()) {
            BOX_CACHE.clear();
        }
    }

    /** 这个子盒在 {@code d} 方向上的面是否被邻居的碰撞箱完全挡住。 */
    private static boolean coveredByNeighbour(final Level level, final BlockPos pos,
                                              final AABB box, final Direction d) {
        final BlockPos neighbour = pos.relative(d);
        if (level.getBlockState(neighbour).isAir()) {
            return false;
        }
        final List<AABB> boxes = collisionBoxes(level, neighbour);
        for (final AABB n : boxes) {
            if (OutlineGeometry.coversFace(n, box, d)) {
                return true;
            }
        }
        return false;
    }

    private static final double EPS = OutlineGeometry.EPS;

    // ---- 临时诊断（确认「平坦表面被网格线切碎」不再出现后删）----
    // 关键数字是 culled：平坦表面上每两个共面方块之间的接缝都应当被剔除，
    // 所以 culled 应当与 edges 同量级。culled=0 就说明剔除规则又写死了（用户实测「糊成一片」的成因）。
    private static long DIAG_BLOCKS;
    private static long DIAG_EDGES;
    private static long DIAG_CULLED;
    private static long DIAG_LAST_MS;

    static void diagBlock() {
        DIAG_BLOCKS++;
    }

    static void diagFlush(final String label) {
        final long now = System.currentTimeMillis();
        if (now - DIAG_LAST_MS < 2000L || DIAG_BLOCKS == 0) {
            return;
        }
        DIAG_LAST_MS = now;
        SablestopNow.LOGGER.info("[model-diag] outline[{}] blocks={} edges={} culled={}",
                label, DIAG_BLOCKS, DIAG_EDGES, DIAG_CULLED);
        DIAG_BLOCKS = 0;
        DIAG_EDGES = 0;
        DIAG_CULLED = 0;
    }

    /**
     * 这条棱是否为「两个共面表面之间的内部接缝」：跨过这条棱的邻居属于同一物理体、
     * 且它在同一平面上也有暴露面 —— 此时两个表面共面连续，这条棱不该画。
     */
    private static boolean isInteriorSeamShape(final Level level, final ClientSubLevel subLevel,
                                               final BlockPos pos, final AABB box, final Direction faceDir,
                                               final double[] c1, final double[] c2) {
        final Direction.Axis a = faceDir.getAxis();
        final Direction.Axis[] pq = perpendicularAxes(a);
        // 棱的方向轴：两端点坐标不同的那一条；跨出棱的方向就是另一条
        final Direction.Axis edgeAxis = Math.abs(c1[0] - c2[0]) > EPS ? Direction.Axis.X
                : (Math.abs(c1[1] - c2[1]) > EPS ? Direction.Axis.Y : Direction.Axis.Z);
        final Direction.Axis crossAxis = edgeAxis == pq[0] ? pq[1] : pq[0];
        final double mid = (axisValue(c1, crossAxis) + axisValue(c2, crossAxis)) * 0.5;
        // 棱在跨出方向上必须贴着盒子的边（在盒子内部就不是接缝了）
        if (Math.abs(mid - box.min(crossAxis)) > EPS && Math.abs(mid - box.max(crossAxis)) > EPS) {
            return false;
        }
        final Direction dir = Direction.fromAxisAndDirection(crossAxis,
                mid > box.min(crossAxis) + EPS ? Direction.AxisDirection.POSITIVE
                        : Direction.AxisDirection.NEGATIVE);
        final BlockPos neighbour = pos.relative(dir);
        if (level.getBlockState(neighbour).isAir()) {
            return false;
        }
        if (Sable.HELPER.getContaining(level, neighbour) != subLevel) {
            return false;
        }
        // ⚠ 邻居是沿 crossAxis 平移过去的，**面法线方向的坐标不会变**：
        // 我们顶面在 y=1，东边那个方块的顶面同样在 y=1（世界系里共面）。
        // 这里一度写成「ourPlane ∓ 1」（把方块偏移也算到了面法线上），于是这个判定<b>永远不成立</b> ——
        // 平坦表面上的内部接缝一条都没被剔除，整块平面被网格线切碎，看起来就是「糊成一片」。
        for (final AABB n : collisionBoxes(level, neighbour)) {
            if (!OutlineGeometry.coplanarFace(box, n, faceDir)) {
                continue;
            }
            if (!coveredByNeighbour(level, neighbour, n, faceDir)) {
                return true;
            }
        }
        return false;
    }

    private static double axisValue(final double[] v, final Direction.Axis axis) {
        return switch (axis) {
            case X -> v[0];
            case Y -> v[1];
            case Z -> v[2];
        };
    }

    /**
     * 该棱是否为「两个共面表面之间的内部接缝」：跨过这条棱的邻居属于同一物理体、且它对应的那个面
     * 同样暴露在空气中 —— 此时两个表面共面连续，这条棱不该画。
     */
    private static boolean isInteriorSeam(Level level, BlockPos pos, ClientSubLevel subLevel, Direction faceDir,
                                          int cornerA, int cornerB) {
        float[] p1 = BoxOutlineRenderer.CUBE_CORNERS[cornerA];
        float[] p2 = BoxOutlineRenderer.CUBE_CORNERS[cornerB];
        double mx = (p1[0] + p2[0]) * 0.5;
        double my = (p1[1] + p2[1]) * 0.5;
        double mz = (p1[2] + p2[2]) * 0.5;

        Direction.Axis perpAxis = null;
        double sign = 0.0;
        for (Direction.Axis axis : Direction.Axis.values()) {
            if (axis == faceDir.getAxis()) {
                continue;
            }
            // 角点用 ±1 表示；边的中点在其中一条轴的坐标为 0（那条轴是边的方向），
            // 在另一条轴上为 ±1（那条轴就是跨出这条棱的方向）。
            double v = switch (axis) {
                case X -> mx;
                case Y -> my;
                case Z -> mz;
            };
            if (Math.abs(v) > 0.5) {
                perpAxis = axis;
                sign = v;
                break;
            }
        }
        if (perpAxis == null) {
            return false;
        }

        Direction dir = Direction.fromAxisAndDirection(perpAxis,
                sign > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE);
        BlockPos neighbor = pos.relative(dir);
        if (level.getBlockState(neighbor).isAir()) {
            return false;
        }
        if (Sable.HELPER.getContaining(level, neighbor) != subLevel) {
            return false;
        }
        return level.getBlockState(neighbor.relative(faceDir)).isAir();
    }

    private static void drawAxis(PoseStack poseStack, VertexConsumer vertexConsumer, Camera camera, Pose3dc renderPose, float angleDeg) {
        Vector3dc centerJoml = renderPose.position();
        Vec3 centerWorld = new Vec3(centerJoml.x(), centerJoml.y(), centerJoml.z());
        double dist = centerWorld.distanceTo(camera.getPosition());
        if (dist < 0.01) return;
        double angleRad = Math.toRadians(angleDeg);
        float actualLen = (float) (dist * Math.tan(angleRad));
        actualLen = Math.max(0.1f, Math.min(actualLen, 10.0f));
        Vec3 relative = centerWorld.subtract(camera.getPosition());
        poseStack.pushPose();
        poseStack.translate(relative.x, relative.y, relative.z);
        poseStack.mulPose(new Quaternionf(renderPose.orientation()));
        PoseStack.Pose pose = poseStack.last();
        addLine(vertexConsumer, pose, 0, 0, 0, actualLen, 0, 0, 1, 0, 0);
        addLine(vertexConsumer, pose, 0, 0, 0, 0, actualLen, 0, 0, 1, 0);
        addLine(vertexConsumer, pose, 0, 0, 0, 0, 0, actualLen, 0, 0, 1);
        poseStack.popPose();
    }

    private static void addLine(VertexConsumer vertexConsumer, PoseStack.Pose pose, float x1, float y1, float z1, float x2, float y2, float z2, float r, float g, float b) {
        vertexConsumer.addVertex(pose, x1, y1, z1).setColor(r, g, b, 1.0f).setNormal(pose, 0.0f, 0.0f, 1.0f);
        vertexConsumer.addVertex(pose, x2, y2, z2).setColor(r, g, b, 1.0f).setNormal(pose, 0.0f, 0.0f, 1.0f);
    }

    private static boolean isSurface(Level level, BlockPos pos) {
        return level.getBlockState(pos.above()).isAir() ||
                level.getBlockState(pos.below()).isAir() ||
                level.getBlockState(pos.north()).isAir() ||
                level.getBlockState(pos.south()).isAir() ||
                level.getBlockState(pos.west()).isAir() ||
                level.getBlockState(pos.east()).isAir();
    }

    /**
     * 供其它渲染器复用：以“只描轮廓（暴露棱线，不画平坦表面的内部接缝）”的风格为整座物理体描边，
     * 边框用细长长方体盒绘制（粗细取 config 的 staff_outline_thickness）。
     * 顶点写入调用方提供的 VertexConsumer（必须是 {@link ModRenderTypes#BOXES} /
     * {@link ModRenderTypes#BOXES_NO_DEPTH} 这类 POSITION_COLOR+QUADS 类型）。
     */
    public static void drawSubLevelContourOutline(final PoseStack poseStack, final VertexConsumer vc, final Camera camera,
                                                  final ClientSubLevel subLevel, final float r, final float g, final float b) {
        drawSubLevelContourOutline(poseStack, vc, camera, subLevel, r, g, b, SablestopNowConfig.staffOutlineThickness());
    }

    /** 同上，可指定边框粗细（细长长方体盒的截面边长，单位=格）。 */
    public static void drawSubLevelContourOutline(final PoseStack poseStack, final VertexConsumer vc, final Camera camera,
                                                  final ClientSubLevel subLevel, final float r, final float g, final float b,
                                                  final float thickness) {
        // 一次描边内部会反复问同一个邻居方块的碰撞箱（每个方块最多 6 个面 × 每个面 4 条棱），
        // 这里清一次缓存即可覆盖整个结构的重复查询。
        clearBoxCache();
        diagFlush("contour");
        final Level level = subLevel.getLevel();
        final Pose3dc renderPose = subLevel.renderPose();
        final var plot = subLevel.getPlot();
        for (final var holder : plot.getLoadedChunks()) {
            final var chunk = holder.getChunk();
            if (chunk == null) {
                continue;
            }
            final var chunkPos = chunk.getPos();
            final int minX = chunkPos.getMinBlockX();
            final int minZ = chunkPos.getMinBlockZ();
            final LevelChunkSection[] sections = chunk.getSections();
            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                final LevelChunkSection section = sections[sectionIndex];
                if (section == null || section.hasOnlyAir()) {
                    continue;
                }
                final int minY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
                for (int dx = 0; dx < 16; dx++) {
                    for (int dy = 0; dy < 16; dy++) {
                        for (int dz = 0; dz < 16; dz++) {
                            if (section.getBlockState(dx, dy, dz).isAir()) {
                                continue;
                            }
                            final BlockPos pos = new BlockPos(minX + dx, minY + dy, minZ + dz);
                            if (!isSurface(level, pos)) {
                                continue;
                            }
                            drawBlockOutlineContour(poseStack, vc, camera, renderPose, pos, r, g, b, 1.0f, thickness, level, subLevel);
                        }
                    }
                }
            }
        }
    }
}