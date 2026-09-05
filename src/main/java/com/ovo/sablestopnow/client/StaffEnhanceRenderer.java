package com.ovo.sablestopnow.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ovo.sablestopnow.SablestopNow;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.Collection;
import java.util.UUID;

/**
 * 物理手杖增强 —— 世界渲染：选中/悬停物理体轮廓描边（只描暴露棱线，沿用既有 contour 风格）、
 * 统一颜色图标、框选预览立方体。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT)
public final class StaffEnhanceRenderer {

    private static final int MAX_RENDER_DISTANCE = 96;
    private static final int RING_SEGMENTS = 12;

    /** 选中描边/图标统一颜色（青色，易辨识、不随物理体变化）。 */
    private static final float SEL_R = 0.15f;
    private static final float SEL_G = 0.95f;
    private static final float SEL_B = 1.0f;
    /** 悬停（将选中）高亮颜色（亮白）。 */
    private static final float HOV_R = 1.0f;
    private static final float HOV_G = 1.0f;
    private static final float HOV_B = 1.0f;

    private StaffEnhanceRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        if (!StaffEnhanceClientHandler.isEnabled()) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final Level level = mc.level;
        if (level == null || mc.player == null) {
            return;
        }
        final Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }

        // 需要画的状态：多选模式 / 选中 / 悬停 / 框选 / 无碰撞标记
        final boolean any = StaffEnhanceClientHandler.isMultiSelect()
                || !StaffEnhanceClientHandler.getSelected().isEmpty()
                || StaffEnhanceClientHandler.getHoverBody() != null
                || StaffEnhanceClientHandler.getBoxStep() != 0
                || !StaffEnhanceClientHandler.getNoCollision().isEmpty();
        if (!any) {
            return;
        }

        final PoseStack poseStack = event.getPoseStack();
        final MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
        final RenderType renderType = ModRenderTypes.LINES_NO_DEPTH;
        final VertexConsumer vc = bufferSource.getBuffer(renderType);
        final Vec3 cameraPos = camera.getPosition();

        boolean drew = false;
        final SubLevelContainer container = SubLevelContainer.getContainer(level);

        // ---- 选中物理体：轮廓描边（contour 风格）+ 图标 ----
        final Collection<UUID> selIds = StaffEnhanceClientHandler.selectedSnapshot();
        if (!selIds.isEmpty() && container != null) {
            for (final UUID id : selIds) {
                final ClientSubLevel clientSub = resolve(container, id, cameraPos);
                if (clientSub == null) {
                    continue;
                }
                SubLevelOutlineRenderer.drawSubLevelContourOutline(poseStack, vc, camera, clientSub, SEL_R, SEL_G, SEL_B);
                drawSelectionIcon(poseStack, vc, camera, renderCenter(clientSub, cameraPos, 1.1), SEL_R, SEL_G, SEL_B);
                drew = true;
            }
        }

        // ---- 悬停（多选模式下右键将选中）：仅亮白轮廓，不显示选中标识 ----
        final UUID hoverId = StaffEnhanceClientHandler.getHoverBody();
        if (hoverId != null && container != null && !StaffEnhanceClientHandler.getSelected().contains(hoverId)) {
            final ClientSubLevel clientSub = resolve(container, hoverId, cameraPos);
            if (clientSub != null) {
                SubLevelOutlineRenderer.drawSubLevelContourOutline(poseStack, vc, camera, clientSub, HOV_R, HOV_G, HOV_B);
                drew = true;
            }
        }

        // ---- 无碰撞标记物理体：提示图标（橙色方块环，画在原点下方） ----
        final Collection<UUID> noColIds = StaffEnhanceClientHandler.getNoCollision();
        if (!noColIds.isEmpty() && container != null) {
            for (final UUID id : noColIds) {
                final ClientSubLevel clientSub = resolve(container, id, cameraPos);
                if (clientSub == null) {
                    continue;
                }
                drawNoCollisionIcon(poseStack, vc, camera, renderCenter(clientSub, cameraPos, -0.9));
                drew = true;
            }
        }

        // ---- 框选：角点标记 + 实时预览 AABB（第二角点来自每 tick 缓存的准星格点） ----
        if (StaffEnhanceClientHandler.getBoxStep() != 0) {
            final BlockPos first = StaffEnhanceClientHandler.getBoxFirst();
            final BlockPos second = StaffEnhanceClientHandler.getBoxSecond() != null
                    ? StaffEnhanceClientHandler.getBoxSecond()
                    : StaffEnhanceClientHandler.getBoxPreview();
            if (first != null) {
                drawCornerMarker(poseStack, vc, camera, cameraPos, first, SEL_R, SEL_G, SEL_B);
            }
            if (first != null && second != null) {
                final int minX = Math.min(first.getX(), second.getX());
                final int minY = Math.min(first.getY(), second.getY());
                final int minZ = Math.min(first.getZ(), second.getZ());
                final int maxX = Math.max(first.getX(), second.getX());
                final int maxY = Math.max(first.getY(), second.getY());
                final int maxZ = Math.max(first.getZ(), second.getZ());
                drawBoxEdges(poseStack, vc, cameraPos,
                        minX, minY, minZ, maxX + 1.0, maxY + 1.0, maxZ + 1.0,
                        SEL_R, SEL_G, SEL_B);
                drew = true;
            }
        }

        if (drew) {
            bufferSource.endBatch(renderType);
        }
    }

    /** 取到未移除且在渲染距离内的 ClientSubLevel，否则 null。 */
    private static ClientSubLevel resolve(final SubLevelContainer container, final UUID id, final Vec3 cameraPos) {
        final SubLevel sub = container.getSubLevel(id);
        if (!(sub instanceof final ClientSubLevel clientSub) || sub.isRemoved()) {
            return null;
        }
        final Pose3dc renderPose = clientSub.renderPose();
        final Vec3 center = new Vec3(renderPose.position().x(), renderPose.position().y(), renderPose.position().z());
        if (center.distanceToSqr(cameraPos) > MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE) {
            return null;
        }
        return clientSub;
    }

    private static Vec3 renderCenter(final ClientSubLevel sub, final Vec3 cameraPos, final double offsetY) {
        final Pose3dc renderPose = sub.renderPose();
        return new Vec3(renderPose.position().x(), renderPose.position().y() + offsetY, renderPose.position().z());
    }

    // ============ billboard 线条图标 ============
    private static void drawSelectionIcon(final PoseStack poseStack, final VertexConsumer vc, final Camera camera,
                                          final Vec3 worldPos, final float r, final float g, final float b) {
        final Vec3 relative = worldPos.subtract(camera.getPosition());
        poseStack.pushPose();
        poseStack.translate(relative.x, relative.y, relative.z);
        poseStack.mulPose(camera.rotation());

        final PoseStack.Pose pose = poseStack.last();
        final float s = 0.28f;
        for (int i = 0; i < RING_SEGMENTS; i++) {
            final double a1 = Math.toRadians(360.0 * i / RING_SEGMENTS);
            final double a2 = Math.toRadians(360.0 * (i + 1) / RING_SEGMENTS);
            addLocalLine(vc, pose, (float) Math.cos(a1) * s, (float) Math.sin(a1) * s, (float) Math.cos(a2) * s, (float) Math.sin(a2) * s, r, g, b);
        }
        final float h = 0.10f;
        addLocalLine(vc, pose, -h, 0, h, 0, r, g, b);
        addLocalLine(vc, pose, 0, -h, 0, h, r, g, b);
        poseStack.popPose();
    }

    private static void drawNoCollisionIcon(final PoseStack poseStack, final VertexConsumer vc, final Camera camera,
                                            final Vec3 worldPos) {
        final Vec3 relative = worldPos.subtract(camera.getPosition());
        poseStack.pushPose();
        poseStack.translate(relative.x, relative.y, relative.z);
        poseStack.mulPose(camera.rotation());
        final PoseStack.Pose pose = poseStack.last();
        final float s = 0.28f;
        final float r = 1.0f;
        final float g = 0.55f;
        final float b = 0.15f;
        // 橙色方块环 + 对角斜杠（区别于选中的圆环十字）
        addLocalLine(vc, pose, -s, -s, s, -s, r, g, b);
        addLocalLine(vc, pose, s, -s, s, s, r, g, b);
        addLocalLine(vc, pose, s, s, -s, s, r, g, b);
        addLocalLine(vc, pose, -s, s, -s, -s, r, g, b);
        addLocalLine(vc, pose, -s, -s, s, s, r, g, b);
        addLocalLine(vc, pose, s, -s, -s, s, r, g, b);
        poseStack.popPose();
    }

    private static void drawCornerMarker(final PoseStack poseStack, final VertexConsumer vc, final Camera camera,
                                         final Vec3 cameraPos, final BlockPos corner,
                                         final float r, final float g, final float b) {
        final Vec3 center = Vec3.atCenterOf(corner);
        final Vec3 relative = center.subtract(cameraPos);
        poseStack.pushPose();
        poseStack.translate(relative.x, relative.y, relative.z);
        poseStack.mulPose(camera.rotation());
        final PoseStack.Pose pose = poseStack.last();
        final float h = 0.25f;
        addLocalLine(vc, pose, -h, 0, h, 0, r, g, b);
        addLocalLine(vc, pose, 0, -h, 0, h, r, g, b);
        poseStack.popPose();
    }

    // ============ 世界 AABB 棱线 ============
    private static void drawBoxEdges(final PoseStack poseStack, final VertexConsumer vc, final Vec3 cameraPos,
                                     final double minX, final double minY, final double minZ,
                                     final double maxX, final double maxY, final double maxZ,
                                     final float r, final float g, final float b) {
        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        final PoseStack.Pose pose = poseStack.last();

        final double[] xs = {minX, maxX};
        final double[] ys = {minY, maxY};
        final double[] zs = {minZ, maxZ};
        for (final double x : xs) {
            for (int zi = 0; zi < 2; zi++) {
                addWorldLine(vc, pose, x, minY, zs[zi], x, maxY, zs[zi], r, g, b);
            }
            for (int yi = 0; yi < 2; yi++) {
                addWorldLine(vc, pose, x, ys[yi], minZ, x, ys[yi], maxZ, r, g, b);
            }
        }
        for (final double z : zs) {
            for (int yi = 0; yi < 2; yi++) {
                addWorldLine(vc, pose, minX, ys[yi], z, maxX, ys[yi], z, r, g, b);
            }
        }
        poseStack.popPose();
    }

    // ============ 顶点助手 ============
    private static void addLocalLine(final VertexConsumer vc, final PoseStack.Pose pose,
                                     final float x1, final float y1, final float x2, final float y2,
                                     final float r, final float g, final float b) {
        vc.addVertex(pose, x1, y1, 0.0f).setColor(r, g, b, 1.0f).setNormal(pose, 0.0f, 0.0f, 1.0f);
        vc.addVertex(pose, x2, y2, 0.0f).setColor(r, g, b, 1.0f).setNormal(pose, 0.0f, 0.0f, 1.0f);
    }

    private static void addWorldLine(final VertexConsumer vc, final PoseStack.Pose pose,
                                     final double x1, final double y1, final double z1,
                                     final double x2, final double y2, final double z2,
                                     final float r, final float g, final float b) {
        vc.addVertex(pose, (float) x1, (float) y1, (float) z1).setColor(r, g, b, 1.0f).setNormal(pose, 0.0f, 1.0f, 0.0f);
        vc.addVertex(pose, (float) x2, (float) y2, (float) z2).setColor(r, g, b, 1.0f).setNormal(pose, 0.0f, 1.0f, 0.0f);
    }
}
