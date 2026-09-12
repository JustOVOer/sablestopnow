package com.ovo.sablestopnow.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.SablestopNowConfig;
import com.ovo.sablestopnow.StaffColors;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 物理手杖增强 —— 世界渲染：
 * <ul>
 *   <li>选中/悬停物理体的轮廓描边（细长长方体盒，粗细可配）；</li>
 *   <li>选中/无碰撞的 billboard 线条图标；</li>
 *   <li>Z 框选预览盒（细长长方体盒 + 逐帧缓动、出现/消失淡入淡出）。</li>
 * </ul>
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT)
public final class StaffEnhanceRenderer {

    private static final int MAX_RENDER_DISTANCE = 96;
    /** 其他玩家的选择只在 64 格内渲染（功能2 的要求）。 */
    private static final int OTHER_SELECTION_DISTANCE = 64;
    private static final int RING_SEGMENTS = 12;

    /** 悬停（将选中）高亮颜色（亮白）。 */
    private static final float HOV_R = 1.0f;
    private static final float HOV_G = 1.0f;
    private static final float HOV_B = 1.0f;
    /** 区域选择中“将会被选中”的预览颜色（黄绿）。 */
    private static final float CAND_R = 0.65f;
    private static final float CAND_G = 1.0f;
    private static final float CAND_B = 0.25f;

    // ---- Z 框选预览盒的缓动状态（世界坐标 min/max，单位=格） ----
    private static final double[] BOX_MIN = new double[3];
    private static final double[] BOX_MAX = new double[3];
    private static boolean boxInit;
    private static float boxAppear;

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

        // 先推进框选预览盒的缓动（即使其它内容都不画，也要让淡出跑完）
        final boolean drawBox = updateBoxAnimation(level);

        // 需要画的状态：多选模式 / 选中 / 悬停 / 区域选择 / 无碰撞标记 / 他人的选择
        final Set<UUID> regionCandidates = StaffEnhanceClientHandler.getRegionCandidates();
        final Map<UUID, Set<UUID>> otherSelections = StaffEnhanceClientHandler.getOtherSelections();
        final boolean any = StaffEnhanceClientHandler.isMultiSelect()
                || !StaffEnhanceClientHandler.getSelected().isEmpty()
                || StaffEnhanceClientHandler.getHoverBody() != null
                || !StaffEnhanceClientHandler.getNoCollision().isEmpty()
                || !regionCandidates.isEmpty()
                || !otherSelections.isEmpty()
                || !StaffEnhanceClientHandler.getSnapshotIds().isEmpty()
                || StaffEnhanceClientHandler.hasAnyScaled()
                || drawBox;
        if (!any) {
            return;
        }

        final PoseStack poseStack = event.getPoseStack();
        final MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        final float outlineThickness = SablestopNowConfig.staffOutlineThickness();
        final Vec3 cameraPos = camera.getPosition();
        final SubLevelContainer container = SubLevelContainer.getContainer(level);

        // 我自己的颜色由服务端分配（功能2：不同玩家颜色不同）
        final int myColor = StaffEnhanceClientHandler.getMyColorIndex();
        final float selR = StaffColors.red(myColor);
        final float selG = StaffColors.green(myColor);
        final float selB = StaffColors.blue(myColor);

        // 悬停（手杖“可能选中”的那个）——若它属于当前选中队列，就整组加粗（功能6）
        final UUID hoverId = StaffEnhanceClientHandler.getHoverBody();
        final Collection<UUID> selIds = StaffEnhanceClientHandler.selectedSnapshot();
        final Collection<UUID> noColIds = StaffEnhanceClientHandler.getNoCollision();
        final boolean boldGroup = hoverId != null && selIds.contains(hoverId);
        final float selectedThickness = boldGroup
                ? outlineThickness * SablestopNowConfig.staffOutlineBoldScale()
                : outlineThickness;

        // 本帧要画的物理体先解析一次（盒/线两段共用，避免重复 raycast/查表）
        final List<ClientSubLevel> selectedSubs = resolveAll(container, selIds, cameraPos, MAX_RENDER_DISTANCE);
        final ClientSubLevel hoverSub = hoverId != null && !selIds.contains(hoverId)
                ? resolve(container, hoverId, cameraPos, MAX_RENDER_DISTANCE)
                : null;
        final List<ClientSubLevel> noColSubs = resolveAll(container, noColIds, cameraPos, MAX_RENDER_DISTANCE);
        final List<ClientSubLevel> candidateSubs = resolveAll(container, regionCandidates, cameraPos, MAX_RENDER_DISTANCE);
        final List<ClientSubLevel> snapshotSubs = resolveAll(container, StaffEnhanceClientHandler.getSnapshotIds(), cameraPos, MAX_RENDER_DISTANCE);

        // 其他玩家的选择（同维度由 S2C 保证；这里再按 64 格过滤）
        final List<ClientSubLevel> otherSubs = new java.util.ArrayList<>();
        final List<Integer> otherColors = new java.util.ArrayList<>();
        if (container != null) {
            for (final Map.Entry<UUID, Set<UUID>> entry : otherSelections.entrySet()) {
                final int colorIndex = StaffEnhanceClientHandler.colorIndexOf(entry.getKey());
                for (final UUID id : entry.getValue()) {
                    final ClientSubLevel sub = resolve(container, id, cameraPos, OTHER_SELECTION_DISTANCE);
                    if (sub != null) {
                        otherSubs.add(sub);
                        otherColors.add(colorIndex);
                    }
                }
            }
        }

        // ⚠ 自建 RenderType 共用 bufferSource 的 shared buffer：同时持有两种自建类型的 consumer 时，
        // 后取的那个会把前一个 BufferBuilder endBatch 掉（继续写入 = IllegalStateException("Not building!")）。
        // 因此分成两段：先画完所有盒式边框并 endBatch，再取线 consumer 画图标/角点十字。
        final boolean needBoxes = !selectedSubs.isEmpty() || hoverSub != null || !candidateSubs.isEmpty()
                || !otherSubs.isEmpty() || drawBox;
        if (needBoxes) {
            final VertexConsumer boxVc = bufferSource.getBuffer(ModRenderTypes.BOXES_NO_DEPTH);
            // 他人的选择（略细，便于和自己的区分）
            for (int i = 0; i < otherSubs.size(); i++) {
                final int colorIndex = otherColors.get(i);
                SubLevelOutlineRenderer.drawSubLevelContourOutline(poseStack, boxVc, camera, otherSubs.get(i),
                        StaffColors.red(colorIndex), StaffColors.green(colorIndex), StaffColors.blue(colorIndex),
                        outlineThickness * 0.75f);
            }
            for (final ClientSubLevel sub : selectedSubs) {
                SubLevelOutlineRenderer.drawSubLevelContourOutline(poseStack, boxVc, camera, sub, selR, selG, selB, selectedThickness);
            }
            if (hoverSub != null) {
                SubLevelOutlineRenderer.drawSubLevelContourOutline(poseStack, boxVc, camera, hoverSub, HOV_R, HOV_G, HOV_B, outlineThickness);
            }
            // 区域选择中“将会被选中”的预览（黄绿）
            for (final ClientSubLevel sub : candidateSubs) {
                SubLevelOutlineRenderer.drawSubLevelContourOutline(poseStack, boxVc, camera, sub, CAND_R, CAND_G, CAND_B, outlineThickness);
            }
            // 区域选择预览盒（缓动）。世界坐标 → 需要先平移 −相机位置（与方块描边不同，这里不是相机相对坐标）
            if (drawBox) {
                final float appear = easeOut(boxAppear);
                poseStack.pushPose();
                poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
                BoxOutlineRenderer.addWireBox(poseStack.last(), boxVc,
                        BOX_MIN[0], BOX_MIN[1], BOX_MIN[2], BOX_MAX[0], BOX_MAX[1], BOX_MAX[2],
                        Math.max(0.004f, outlineThickness * appear), selR, selG, selB, Math.min(1.0f, appear + 0.2f));
                poseStack.popPose();
            }
            bufferSource.endBatch(ModRenderTypes.BOXES_NO_DEPTH);
        }

        // ---- 第二段：GL 线段（选中图标 / 无碰撞图标 / 角点十字）----
        final BlockPos regionFirst = StaffEnhanceClientHandler.getRegionFirst();
        final boolean needLines = !selectedSubs.isEmpty() || !noColSubs.isEmpty() || !snapshotSubs.isEmpty()
                || (drawBox && regionFirst != null);
        if (needLines) {
            final VertexConsumer lineVc = bufferSource.getBuffer(ModRenderTypes.LINES_NO_DEPTH);
            for (final ClientSubLevel sub : selectedSubs) {
                drawSelectionIcon(poseStack, lineVc, camera, renderCenter(sub, cameraPos, 1.1), selR, selG, selB);
            }
            // 已创建快照的物理结构：上方一个「相机」图标（功能9）
            for (final ClientSubLevel sub : snapshotSubs) {
                drawSnapshotIcon(poseStack, lineVc, camera, renderCenter(sub, cameraPos, 1.75));
            }
            for (final ClientSubLevel sub : noColSubs) {
                drawNoCollisionIcon(poseStack, lineVc, camera, renderCenter(sub, cameraPos, -0.9));
            }
            if (drawBox && regionFirst != null) {
                drawCornerMarker(poseStack, lineVc, camera, cameraPos, regionFirst, selR, selG, selB);
            }
            bufferSource.endBatch(ModRenderTypes.LINES_NO_DEPTH);
        }
    }

    /** 批量解析物理体（已移除 / 超渲染距离 / 非 ClientSubLevel 的直接丢弃）。 */
    private static List<ClientSubLevel> resolveAll(final SubLevelContainer container, final Collection<UUID> ids,
                                                   final Vec3 cameraPos, final double maxDistance) {
        final List<ClientSubLevel> out = new java.util.ArrayList<>();
        if (container == null) {
            return out;
        }
        for (final UUID id : ids) {
            final ClientSubLevel sub = resolve(container, id, cameraPos, maxDistance);
            if (sub != null) {
                out.add(sub);
            }
        }
        return out;
    }

    // ============ Z 框选预览盒的缓动 ============
    /**
     * 计算目标盒并推进缓动。返回本帧是否要绘制（出现中/可见/淡出中）。
     * 第一个角点已定、第二个角点还没定（或还没轮到实时预览）时，目标盒就是角点所在的**单方块**。
     */
    private static boolean updateBoxAnimation(final Level level) {
        final double[] targetMin = new double[3];
        final double[] targetMax = new double[3];
        final boolean hasTarget = computeBoxTarget(level, targetMin, targetMax);

        if (!hasTarget) {
            boxAppear = Math.max(0.0f, boxAppear - 0.10f);
            if (boxAppear <= 0.0f) {
                boxInit = false;
                return false;
            }
            return true;
        }

        if (!boxInit) {
            boxInit = true;
            boxAppear = 0.0f;
            for (int i = 0; i < 3; i++) {
                final double center = (targetMin[i] + targetMax[i]) * 0.5;
                BOX_MIN[i] = center;
                BOX_MAX[i] = center;
            }
        }
        boxAppear = Math.min(1.0f, boxAppear + 0.07f);
        for (int i = 0; i < 3; i++) {
            BOX_MIN[i] += (targetMin[i] - BOX_MIN[i]) * 0.35;
            BOX_MAX[i] += (targetMax[i] - BOX_MAX[i]) * 0.35;
        }
        return true;
    }

    /** 目标盒（含端点 +1，使方块格覆盖完整）。没有正在进行的区域选择时返回 false。 */
    private static boolean computeBoxTarget(final Level level, final double[] outMin, final double[] outMax) {
        if (StaffEnhanceClientHandler.getRegionStep() == 0) {
            return false;
        }
        final BlockPos first = StaffEnhanceClientHandler.getRegionFirst();
        final BlockPos cursor = StaffEnhanceClientHandler.getRegionCursor();
        if (first == null) {
            // 还没确定第一个角点：预览一个方块大小的框，指示第一个选择点的位置
            if (cursor == null) {
                return false;
            }
            fillBlockBox(cursor, outMin, outMax);
            return true;
        }
        final BlockPos second = StaffEnhanceClientHandler.getRegionSecond() != null
                ? StaffEnhanceClientHandler.getRegionSecond()
                : cursor;
        if (second == null) {
            fillBlockBox(first, outMin, outMax);
            return true;
        }
        outMin[0] = Math.min(first.getX(), second.getX());
        outMin[1] = Math.min(first.getY(), second.getY());
        outMin[2] = Math.min(first.getZ(), second.getZ());
        outMax[0] = Math.max(first.getX(), second.getX()) + 1.0;
        outMax[1] = Math.max(first.getY(), second.getY()) + 1.0;
        outMax[2] = Math.max(first.getZ(), second.getZ()) + 1.0;
        return true;
    }

    private static void fillBlockBox(final BlockPos block, final double[] outMin, final double[] outMax) {
        outMin[0] = block.getX();
        outMin[1] = block.getY();
        outMin[2] = block.getZ();
        outMax[0] = block.getX() + 1.0;
        outMax[1] = block.getY() + 1.0;
        outMax[2] = block.getZ() + 1.0;
    }

    /** 由快变慢的缓出曲线。 */
    private static float easeOut(final float t) {
        final float inv = 1.0f - Math.clamp(t, 0.0f, 1.0f);
        return 1.0f - inv * inv * inv;
    }

    /** 取到未移除且在给定距离内的 ClientSubLevel，否则 null。 */
    private static ClientSubLevel resolve(final SubLevelContainer container, final UUID id, final Vec3 cameraPos,
                                          final double maxDistance) {
        final SubLevel sub = container.getSubLevel(id);
        if (!(sub instanceof final ClientSubLevel clientSub) || sub.isRemoved()) {
            return null;
        }
        final Pose3dc renderPose = clientSub.renderPose();
        final Vec3 center = new Vec3(renderPose.position().x(), renderPose.position().y(), renderPose.position().z());
        if (center.distanceToSqr(cameraPos) > maxDistance * maxDistance) {
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

    /** 已创建快照的提示图标（浅蓝色「相机」：方块 + 中间圆点），画在物理体上方。 */
    private static void drawSnapshotIcon(final PoseStack poseStack, final VertexConsumer vc, final Camera camera,
                                         final Vec3 worldPos) {
        final Vec3 relative = worldPos.subtract(camera.getPosition());
        poseStack.pushPose();
        poseStack.translate(relative.x, relative.y, relative.z);
        poseStack.mulPose(camera.rotation());
        final PoseStack.Pose pose = poseStack.last();
        final float s = 0.24f;
        final float r = 0.72f;
        final float g = 0.88f;
        final float b = 1.0f;
        // 外框
        addLocalLine(vc, pose, -s, -s, s, -s, r, g, b);
        addLocalLine(vc, pose, s, -s, s, s, r, g, b);
        addLocalLine(vc, pose, s, s, -s, s, r, g, b);
        addLocalLine(vc, pose, -s, s, -s, -s, r, g, b);
        // 中心圆点（十字 + 小环）
        final float d = 0.08f;
        addLocalLine(vc, pose, -d, 0, d, 0, r, g, b);
        addLocalLine(vc, pose, 0, -d, 0, d, r, g, b);
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

    // ============ 顶点助手（线段，POSITION_COLOR_NORMAL） ============
    private static void addLocalLine(final VertexConsumer vc, final PoseStack.Pose pose,
                                     final float x1, final float y1, final float x2, final float y2,
                                     final float r, final float g, final float b) {
        vc.addVertex(pose, x1, y1, 0.0f).setColor(r, g, b, 1.0f).setNormal(pose, 0.0f, 0.0f, 1.0f);
        vc.addVertex(pose, x2, y2, 0.0f).setColor(r, g, b, 1.0f).setNormal(pose, 0.0f, 0.0f, 1.0f);
    }
}
