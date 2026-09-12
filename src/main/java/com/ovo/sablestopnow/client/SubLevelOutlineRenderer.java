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

        // 方块位置已被 transformPosition 乘过 pose.scale，盒子本身也要跟着乘（否则整块线框模式同样会错位）
        float half = 0.5f * scale * (float) renderPose.scale().x();
        AABB aabb = new AABB(-half, -half, -half, half, half, half);
        LevelRenderer.renderLineBox(poseStack, vertexConsumer, aabb, r, g, b, 1.0f);

        poseStack.popPose();
        return 12;
    }

    /**
     * 只描「暴露在空气中的棱线」（轮廓）。改用细长长方体盒绘制（{@link ModRenderTypes#BOXES}）。
     *
     * <p>共面接缝判定：一条棱只可能跨过一条轴（垂直于面法线的那两条轴里，中点带 ±half 偏移的那条）。
     * 旧实现要求「这条棱两侧的两个邻居都存在且都暴露」，于是平坦表面最外圈方块的那条内部接缝
     * 因为一侧邻居是空气而不再被跳过 → 表现为「边缘 + 向内一层的棱」同时被描边。这里改为只看
     * 跨过这条棱的那唯一一个邻居。
     */
    private static int drawBlockOutlineContour(PoseStack poseStack, VertexConsumer vertexConsumer, Camera camera,
                                               Pose3dc renderPose, BlockPos pos, float r, float g, float b,
                                               float scale, float thickness, Level level, ClientSubLevel subLevel) {
        Vec3 posCenter = pos.getCenter();
        Vector3dc transformedPosJoml = renderPose.transformPosition(new org.joml.Vector3d(posCenter.x, posCenter.y, posCenter.z));
        Vec3 transformedPos = new Vec3(transformedPosJoml.x(), transformedPosJoml.y(), transformedPosJoml.z());
        Vec3 relative = transformedPos.subtract(camera.getPosition());

        poseStack.pushPose();
        poseStack.translate(relative.x, relative.y, relative.z);
        poseStack.mulPose(new Quaternionf(renderPose.orientation()));

        float half = 0.5f * scale;
        // ⚠ 物理体整体缩放时，方块位置会被 renderPose.transformPosition 乘上 scale，
        // 但方块盒子本身必须同样乘上 scale，否则描边之间会出现空隙。
        half *= (float) renderPose.scale().x();
        PoseStack.Pose pose = poseStack.last();

        int edgeCount = 0;
        int drawnEdges = 0;
        for (int i = 0; i < 6; i++) {
            Direction faceDir = Direction.values()[i];
            if (!level.getBlockState(pos.relative(faceDir)).isAir()) {
                continue;
            }
            int[] face = BoxOutlineRenderer.CUBE_FACES[i];
            for (int k = 0; k < 4; k++) {
                int cornerA = face[k];
                int cornerB = face[(k + 1) & 3];
                int bit = BoxOutlineRenderer.edgeBit(cornerA, cornerB);
                if ((drawnEdges & bit) != 0) {
                    continue; // 相邻的暴露面已经画过这条棱
                }
                if (isInteriorSeam(level, pos, subLevel, faceDir, cornerA, cornerB)) {
                    continue;
                }
                drawnEdges |= bit;
                BoxOutlineRenderer.addCubeEdge(pose, vertexConsumer, half, cornerA, cornerB, thickness, r, g, b, 1.0f);
                edgeCount++;
            }
        }

        poseStack.popPose();
        return edgeCount;
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