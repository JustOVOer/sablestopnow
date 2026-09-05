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

        RenderType renderType = alwaysVisible ? ModRenderTypes.LINES_NO_DEPTH : ModRenderTypes.LINES;
        VertexConsumer vertexConsumer = bufferSource.getBuffer(renderType);

        int totalEdges = 0;
        for (ClientSubLevel subLevel : subLevels) {
            if (subLevel.isRemoved()) continue;
            if (focusedSubLevelId != null && !subLevel.getUniqueId().equals(focusedSubLevelId)) {
                continue;
            }

            Vector3dc centerJoml = subLevel.renderPose().position();
            Vec3 center = new Vec3(centerJoml.x(), centerJoml.y(), centerJoml.z());
            if (center.distanceTo(cameraPos) > MAX_RENDER_DISTANCE) continue;

            int uuidHash = subLevel.getUniqueId().hashCode();
            float r = ((uuidHash >> 16) & 0xFF) / 255f;
            float g = ((uuidHash >> 8) & 0xFF) / 255f;
            float b = (uuidHash & 0xFF) / 255f;

            float scale = alwaysVisible ? (0.98f + (Math.abs(uuidHash) & 0xFF) / 25500.0f) : 1.0f;

            Pose3dc renderPose = subLevel.renderPose();

            // 绘制坐标轴（动态缩放）
            if (renderAxis) {
                drawAxis(poseStack, vertexConsumer, camera, renderPose, axisAngleDeg);
            }

            // 绘制边框
            var plot = subLevel.getPlot();
            for (var holder : plot.getLoadedChunks()) {
                var chunk = holder.getChunk();
                if (chunk == null) continue;
                var chunkPos = chunk.getPos();
                int minX = chunkPos.getMinBlockX();
                int minZ = chunkPos.getMinBlockZ();

                LevelChunkSection[] sections = chunk.getSections();
                for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                    LevelChunkSection section = sections[sectionIndex];
                    if (section == null || section.hasOnlyAir()) continue;
                    int sectionY = chunk.getSectionYFromSectionIndex(sectionIndex);
                    int minY = sectionY << 4;

                    for (int dx = 0; dx < 16; dx++) {
                        for (int dy = 0; dy < 16; dy++) {
                            for (int dz = 0; dz < 16; dz++) {
                                if (section.getBlockState(dx, dy, dz).isAir()) continue;
                                BlockPos pos = new BlockPos(minX + dx, minY + dy, minZ + dz);
                                if (isSurface(level, pos)) {
                                    if (onlyContour) {
                                        totalEdges += drawBlockOutlineContour(poseStack, vertexConsumer, camera, renderPose, pos, r, g, b, scale, level, subLevel);
                                    } else {
                                        totalEdges += drawBlockOutlineFull(poseStack, vertexConsumer, camera, renderPose, pos, r, g, b, scale);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        SablestopNow.LOGGER.trace("Drew {} edges", totalEdges);

        if (totalEdges > 0 || renderAxis) {
            bufferSource.endBatch(renderType);
        }
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

        float half = 0.5f * scale;
        AABB aabb = new AABB(-half, -half, -half, half, half, half);
        LevelRenderer.renderLineBox(poseStack, vertexConsumer, aabb, r, g, b, 1.0f);

        poseStack.popPose();
        return 12;
    }

    private static int drawBlockOutlineContour(PoseStack poseStack, VertexConsumer vertexConsumer, Camera camera,
                                               Pose3dc renderPose, BlockPos pos, float r, float g, float b,
                                               float scale, Level level, ClientSubLevel subLevel) {
        Vec3 posCenter = pos.getCenter();
        Vector3dc transformedPosJoml = renderPose.transformPosition(new org.joml.Vector3d(posCenter.x, posCenter.y, posCenter.z));
        Vec3 transformedPos = new Vec3(transformedPosJoml.x(), transformedPosJoml.y(), transformedPosJoml.z());
        Vec3 relative = transformedPos.subtract(camera.getPosition());

        poseStack.pushPose();
        poseStack.translate(relative.x, relative.y, relative.z);
        poseStack.mulPose(new Quaternionf(renderPose.orientation()));

        float half = 0.5f * scale;

        float[][] corners = {
                {-half, -half, -half},
                { half, -half, -half},
                { half, -half,  half},
                {-half, -half,  half},
                {-half,  half, -half},
                { half,  half, -half},
                { half,  half,  half},
                {-half,  half,  half}
        };

        int[][] faceCorners = {
                {0, 1, 2, 3},
                {4, 5, 6, 7},
                {0, 1, 5, 4},
                {3, 2, 6, 7},
                {0, 3, 7, 4},
                {1, 2, 6, 5}
        };

        BlockPos[] neighborOffsets = {
                pos.below(), pos.above(), pos.north(), pos.south(), pos.west(), pos.east()
        };

        int edgeCount = 0;
        PoseStack.Pose pose = poseStack.last();

        for (int i = 0; i < 6; i++) {
            Direction faceDir = Direction.values()[i];
            if (level.getBlockState(neighborOffsets[i]).isAir()) {
                int[] face = faceCorners[i];
                int[][] edges = {
                        {face[0], face[1]},
                        {face[1], face[2]},
                        {face[2], face[3]},
                        {face[3], face[0]}
                };
                for (int[] edge : edges) {
                    float[] p1 = corners[edge[0]];
                    float[] p2 = corners[edge[1]];
                    float ex = p2[0] - p1[0];
                    float ey = p2[1] - p1[1];
                    float ez = p2[2] - p1[2];
                    Direction.Axis edgeAxis = null;
                    if (Math.abs(ex) > 0.001f) edgeAxis = Direction.Axis.X;
                    else if (Math.abs(ey) > 0.001f) edgeAxis = Direction.Axis.Y;
                    else if (Math.abs(ez) > 0.001f) edgeAxis = Direction.Axis.Z;

                    boolean skip = false;
                    if (edgeAxis != null) {
                        Direction.Axis perpAxis = null;
                        if (faceDir.getAxis() == Direction.Axis.Y) {
                            if (edgeAxis == Direction.Axis.X) perpAxis = Direction.Axis.Z;
                            else if (edgeAxis == Direction.Axis.Z) perpAxis = Direction.Axis.X;
                        } else if (faceDir.getAxis() == Direction.Axis.X) {
                            if (edgeAxis == Direction.Axis.Y) perpAxis = Direction.Axis.Z;
                            else if (edgeAxis == Direction.Axis.Z) perpAxis = Direction.Axis.Y;
                        } else { // Z
                            if (edgeAxis == Direction.Axis.X) perpAxis = Direction.Axis.Y;
                            else if (edgeAxis == Direction.Axis.Y) perpAxis = Direction.Axis.X;
                        }

                        if (perpAxis != null) {
                            Direction dirPos = Direction.fromAxisAndDirection(perpAxis, Direction.AxisDirection.POSITIVE);
                            Direction dirNeg = Direction.fromAxisAndDirection(perpAxis, Direction.AxisDirection.NEGATIVE);

                            BlockPos neighborPos1 = pos.relative(dirPos);
                            BlockPos neighborPos2 = pos.relative(dirNeg);

                            boolean hasNeighbor1 = !level.getBlockState(neighborPos1).isAir() && Sable.HELPER.getContaining(level, neighborPos1) == subLevel;
                            boolean hasNeighbor2 = !level.getBlockState(neighborPos2).isAir() && Sable.HELPER.getContaining(level, neighborPos2) == subLevel;
                            boolean neighbor1Exposed = level.getBlockState(neighborPos1.relative(faceDir)).isAir();
                            boolean neighbor2Exposed = level.getBlockState(neighborPos2.relative(faceDir)).isAir();

                            if (hasNeighbor1 && hasNeighbor2 && neighbor1Exposed && neighbor2Exposed) {
                                skip = true;
                            }
                        }
                    }

                    if (!skip) {
                        addLine(vertexConsumer, pose, p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], r, g, b);
                        edgeCount++;
                    }
                }
            }
        }

        poseStack.popPose();
        return edgeCount;
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
     * 供其它渲染器复用：以“只描轮廓（暴露棱线，不画平坦表面的内部接缝）”的风格为整座物理体描边。
     * 顶点写入调用方提供的 VertexConsumer（需 POSITION_COLOR_NORMAL 线框 RenderType）。
     */
    public static void drawSubLevelContourOutline(final PoseStack poseStack, final VertexConsumer vc, final Camera camera,
                                                  final ClientSubLevel subLevel, final float r, final float g, final float b) {
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
                            drawBlockOutlineContour(poseStack, vc, camera, renderPose, pos, r, g, b, 1.0f, level, subLevel);
                        }
                    }
                }
            }
        }
    }
}