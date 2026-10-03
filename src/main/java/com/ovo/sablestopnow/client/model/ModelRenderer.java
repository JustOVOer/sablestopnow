package com.ovo.sablestopnow.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.client.BoxOutlineRenderer;
import com.ovo.sablestopnow.client.MateTargeting;
import com.ovo.sablestopnow.client.ModRenderTypes;
import com.ovo.sablestopnow.client.SubLevelOutlineRenderer;
import com.ovo.sablestopnow.mate.MateFrames;
import com.ovo.sablestopnow.mate.MateRef;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * 模型模式的世界渲染：每个模型一圈细线描边 + 鼠标下那个点/棱/面的高亮。
 *
 * <p>阶段与配合预览一致（{@code AFTER_BLOCK_ENTITIES}）：这个阶段在 {@code renderLevel} 内部，
 * {@code poseStack} 还是世界系、还没有被相机变换吃掉 —— 画世界里的东西必须在这里，
 * 否则会画到屏幕上一个莫名其妙的位置（这个坑之前踩过）。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT)
public final class ModelRenderer {

    /** 模型细线描边的线宽（格）。 */
    private static final float MODEL_THICKNESS = 0.012f;
    private static final float SELECTED_THICKNESS = 0.022f;
    private static final float HOVER_THICKNESS = 0.028f;
    /** 施力点标记的线宽（比选中态细一点，免得一堆点糊成一片）。 */
    private static final float MARKER_THICKNESS = 0.02f;
    private static final double MAX_DISTANCE = 96.0;

    private ModelRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (!ModelMode.isOn() || mc.level == null || mc.player == null) {
            return;
        }
        try {
            // ⚠ 相机平滑<b>不在这里</b>推进：它必须在 Camera.setup 之前（见 ModelCamera.tickOnce /
            // ModelCameraMixin）。在这里推进会让「画面用的相机」比「投影用的相机」慢一帧，
            // 一转视角标记就整体偏出去。
            ModelInput.tick(mc);
            // 记下**本帧真正用过的**投影/视图矩阵：屏幕空间的标记与拾取射线都按它们算，
            // 于是「画在哪」和「点在哪」永远是同一套变换（见 ModelCamera.projectToScreen）。
            ModelCamera.captureFrame(event.getProjectionMatrix(), event.getCamera());
            // 每帧刷新：鼠标下的参考 + 界面的周期刷新
            ModelMode.refreshHover(mc);
            ModelUi.tickRefresh();
            draw(mc, event.getPoseStack());
        } catch (final Throwable t) {
            SablestopNow.LOGGER.error("model renderer failed", t);
        }
    }

    private static void draw(final Minecraft mc, final PoseStack poseStack) {
        final Camera camera = mc.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }
        final Vec3 cameraPos = camera.getPosition();
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        // ⚠ 用**带深度测试**的那种盒型。以前用的是 BOXES_NO_DEPTH，于是所有描边都穿透方块画出来：
        // 远处的模型、结构内部每一格的棱全都叠在一起，画面糊成一片（用户实测反馈
        // 「模型描边不要穿透显示这样太乱了」）。透视该看到的线才画，才像工程视图。
        final RenderType type = ModRenderTypes.BOXES;
        final VertexConsumer vc = buffers.getBuffer(type);
        final ClientSubLevelContainer container = (ClientSubLevelContainer) SubLevelContainer.getContainer(mc.level);
        if (container == null) {
            return;
        }
        final java.util.UUID selected = ModelMode.selectedModel();
        for (final ClientSubLevel sub : container.getAllSubLevels()) {
            if (sub.isRemoved() || ModelProperties.hidden(sub.getUniqueId())) {
                continue;
            }
            final Vector3dc center = sub.renderPose().position();
            if (new Vec3(center.x(), center.y(), center.z()).distanceTo(cameraPos) > MAX_DISTANCE) {
                continue;
            }
            final boolean isSelected = sub.getUniqueId().equals(selected);
            if (isSelected) {
                SubLevelOutlineRenderer.drawSubLevelContourOutline(poseStack, vc, camera, sub,
                        1.0f, 0.72f, 0.25f, SELECTED_THICKNESS);
            } else {
                SubLevelOutlineRenderer.drawSubLevelContourOutline(poseStack, vc, camera, sub,
                        0.55f, 0.62f, 0.85f, MODEL_THICKNESS);
            }
        }
        // ⚠ 悬停（候选）不再画在模型上：改成屏幕空间的选择标记（见 ModelPicks / LAYER_PICKS）。
        // 原因：高亮在模型上时玩家看得到「结果」，却看不到「可点范围」——
        // 角点的判定区投到屏幕有时只有两三像素，鼠标该停在哪里全靠猜（用户实测反馈）。
        // 已选中的参考仍然画在模型上（青色），这就是「选中后再在模型上渲染」。
        drawPicked(poseStack, vc, camera);
        drawForcePoints(poseStack, vc, camera);
        buffers.endBatch(type);
    }

    /**
     * 已选参考的高亮（青色）。
     *
     * <p>没有这一层反馈，玩家点完面之后画面毫无变化 —— 只能靠左栏那行「已选 N」判断，
     * 体感就是「点了没反应」（用户实测反馈）。已选与鼠标悬停用不同颜色区分：
     * 悬停=黄，已选=青。
     */
    private static void drawPicked(final PoseStack poseStack, final VertexConsumer vc, final Camera camera) {
        final java.util.List<MateRef> picked = ModelMode.picked();
        if (picked.isEmpty()) {
            return;
        }
        final Vec3 cameraPos = camera.getPosition();
        for (final MateRef ref : picked) {
            final SubLevel sub = MateTargeting.byId(ref.body());
            if (sub == null) {
                continue;
            }
            drawRefMark(poseStack, vc, camera, sub, ref, 0.35f, 1.0f, 0.95f, HOVER_THICKNESS);
        }
    }

    /** 给一个参考画标记（与悬停高亮同一套画法，只是颜色/粗细可调）。 */
    private static void drawRefMark(final PoseStack poseStack, final VertexConsumer vc, final Camera camera,
                                    final SubLevel sub, final MateRef ref,
                                    final float r, final float g, final float b, final float thickness) {
        final Pose3dc pose = MateTargeting.poseOf(sub);
        final Vector3dc scale = pose.scale();
        final Vec3 cameraPos = camera.getPosition();
        final MateFrames.Resolved resolved = MateFrames.resolveLocal(sub, ref);
        final Vector3d anchor = MateFrames.worldPoint(pose, resolved);
        final AABB box = ref.boxOrDefault();
        final Vector3d point = resolved.point();

        poseStack.pushPose();
        poseStack.translate(anchor.x - cameraPos.x, anchor.y - cameraPos.y, anchor.z - cameraPos.z);
        poseStack.mulPose(new Quaternionf(pose.orientation()));
        final PoseStack.Pose last = poseStack.last();
        switch (ref.kind()) {
            case FACE, FACE_CENTER -> {
                final Vector3d[] corners = new Vector3d[4];
                for (int i = 0; i < 4; i++) {
                    corners[i] = MateRef.faceCorner(ref.block(), ref.face(), i, box);
                }
                // ⚠ 角点顺序必须是 0→1→3→2：faceCorner 的下标是「bit0 = u 正负、bit1 = v 正负」，
                // 直接 0→1→2→3 连接会把 1→2 画成对角线，看起来就是「一条穿过面中心、连着对边中点的线」。
                final int[] order = {0, 1, 3, 2};
                for (int i = 0; i < 4; i++) {
                    final Vector3d p1 = corners[order[i]];
                    final Vector3d p2 = corners[order[(i + 1) & 3]];
                    BoxOutlineRenderer.addEdge(last, vc,
                            (p1.x - point.x) * scale.x(), (p1.y - point.y) * scale.y(),
                            (p1.z - point.z) * scale.z(),
                            (p2.x - point.x) * scale.x(), (p2.y - point.y) * scale.y(),
                            (p2.z - point.z) * scale.z(),
                            thickness, r, g, b, 1.0f);
                }
            }
            case EDGE, EDGE_CENTER -> {
                final Vector3d dir = MateRef.faceEdgeDirection(ref.face(), ref.feature());
                final double length = MateRef.extentOf(box, dir);
                final Vector3d a = new Vector3d(dir).mul(-length * 0.5);
                final Vector3d c = new Vector3d(dir).mul(length * 0.5);
                BoxOutlineRenderer.addEdge(last, vc,
                        a.x * scale.x(), a.y * scale.y(), a.z * scale.z(),
                        c.x * scale.x(), c.y * scale.y(), c.z * scale.z(),
                        thickness, r, g, b, 1.0f);
            }
            default -> {
                final double h = 0.06;
                BoxOutlineRenderer.addWireBox(last, vc,
                        -h * scale.x(), -h * scale.y(), -h * scale.z(),
                        h * scale.x(), h * scale.y(), h * scale.z(),
                        thickness, r, g, b, 1.0f);
            }
        }
        poseStack.popPose();
    }

    /**
     * 可控施力点的渲染：每个点一个小方块 + 一条沿法线的箭头；放置模式下再画一个落点预览。
     *
     * <p>颜色即状态：<b>绿</b>=已启用且频道配好、<b>琥珀</b>=启用了但频道没配好（放了也不会动，
     * 这是最容易让人以为「坏了」的情况，所以刻意用显眼的颜色）、<b>灰</b>=未启用、<b>黄</b>=落点预览。
     */
    private static void drawForcePoints(final PoseStack poseStack, final VertexConsumer vc,
                                        final Camera camera) {
        final boolean placing = ModelTools.FORCE.equals(ModelMode.tool());
        if (!placing && com.ovo.sablestopnow.force.ForceClientState.current().isEmpty()) {
            return;
        }
        final Vec3 cameraPos = camera.getPosition();
        final ClientSubLevelContainer container = (ClientSubLevelContainer) SubLevelContainer.getContainer(
                Minecraft.getInstance().level);
        if (container == null) {
            return;
        }
        for (final ClientSubLevel sub : container.getAllSubLevels()) {
            if (sub.isRemoved() || ModelProperties.hidden(sub.getUniqueId())) {
                continue;
            }
            final java.util.List<com.ovo.sablestopnow.force.ForcePoint> points =
                    com.ovo.sablestopnow.force.ForceClientState.ofBody(sub.getUniqueId());
            if (points.isEmpty()) {
                continue;
            }
            final Pose3dc pose = MateTargeting.poseOf(sub);
            final Vector3dc scale = pose.scale();
            for (final com.ovo.sablestopnow.force.ForcePoint point : points) {
                final boolean selected = point.id().equals(ModelForceUi.selectedId());
                final float r;
                final float g;
                final float b;
                if (!point.enabled()) {
                    r = 0.65f;
                    g = 0.65f;
                    b = 0.70f;
                } else if (!point.channelConfigured()) {
                    r = 1.00f;
                    g = 0.72f;
                    b = 0.20f;
                } else {
                    r = 0.35f;
                    g = 1.00f;
                    b = 0.50f;
                }
                // 与高亮同理：以该点自身为原点重新定位，否则 2e7 量级的 plot 坐标会把标记的精度吃光
                final Vector3d anchor = MateFrames.worldPoint(pose,
                        new MateFrames.Resolved(new Vector3d(point.localPoint()), null));
                poseStack.pushPose();
                poseStack.translate(anchor.x - cameraPos.x, anchor.y - cameraPos.y, anchor.z - cameraPos.z);
                poseStack.mulPose(new Quaternionf(pose.orientation()));
                final PoseStack.Pose last = poseStack.last();
                final double half = 0.045;
                BoxOutlineRenderer.addWireBox(last, vc,
                        -half * scale.x(), -half * scale.y(), -half * scale.z(),
                        half * scale.x(), half * scale.y(), half * scale.z(),
                        selected ? HOVER_THICKNESS : MARKER_THICKNESS, r, g, b, 1.0f);
                final double length = 0.6;
                BoxOutlineRenderer.addEdge(last, vc, 0, 0, 0,
                        point.localNormal().x * length, point.localNormal().y * length,
                        point.localNormal().z * length,
                        selected ? HOVER_THICKNESS : MARKER_THICKNESS, r, g, b, 1.0f);
                poseStack.popPose();
            }
        }

        // 落点预览：跟着鼠标在模型表面上走，点击才会真的建点（同样按锚点重新定位）
        final MateTargeting.BoxHit hit = ModelMode.hovered();
        if (placing && hit != null) {
            final Pose3dc pose = MateTargeting.poseOf(hit.body());
            final Vector3dc scale = pose.scale();
            final Vec3 base = hit.baseHit();
            final Vector3d localPoint = new Vector3d(base.x, base.y, base.z);
            final Vector3d normal = new Vector3d(hit.baseFace().getStepX(), hit.baseFace().getStepY(),
                    hit.baseFace().getStepZ());
            final Vector3d anchor = MateFrames.worldPoint(pose, new MateFrames.Resolved(localPoint, null));
            poseStack.pushPose();
            poseStack.translate(anchor.x - cameraPos.x, anchor.y - cameraPos.y, anchor.z - cameraPos.z);
            poseStack.mulPose(new Quaternionf(pose.orientation()));
            final PoseStack.Pose last = poseStack.last();
            final double half = 0.045;
            BoxOutlineRenderer.addWireBox(last, vc,
                    -half * scale.x(), -half * scale.y(), -half * scale.z(),
                    half * scale.x(), half * scale.y(), half * scale.z(),
                    HOVER_THICKNESS, 1.0f, 0.95f, 0.25f, 1.0f);
            BoxOutlineRenderer.addEdge(last, vc, 0, 0, 0,
                    normal.x * 0.6, normal.y * 0.6, normal.z * 0.6,
                    HOVER_THICKNESS, 1.0f, 0.95f, 0.25f, 1.0f);
            poseStack.popPose();
        }
    }

    /** 一个施力点的标记：受力点处一个小方块，外加一条沿法线的箭头。 */
    private static void drawForceMarker(final PoseStack.Pose pose, final VertexConsumer vc,
                                        final Vector3d localPoint, final Vector3d localNormal,
                                        final Vector3dc scale, final float thickness,
                                        final float r, final float g, final float b) {
        final double half = 0.045;
        final double x = localPoint.x * scale.x();
        final double y = localPoint.y * scale.y();
        final double z = localPoint.z * scale.z();
        BoxOutlineRenderer.addWireBox(pose, vc, x - half, y - half, z - half, x + half, y + half, z + half,
                thickness, r, g, b, 1.0f);
        final double length = 0.6;
        BoxOutlineRenderer.addEdge(pose, vc, x, y, z,
                x + localNormal.x * length, y + localNormal.y * length, z + localNormal.z * length,
                thickness, r, g, b, 1.0f);
    }

    /** 鼠标下的参考高亮：面=画那个面的四条棱，棱=加粗那条棱，点=一个小方块。 */
    private static void drawHover(final PoseStack poseStack, final VertexConsumer vc, final Camera camera) {
        final MateTargeting.BoxHit hit = ModelMode.hovered();
        final MateRef ref = ModelMode.hoveredRef();
        if (hit == null || ref == null) {
            return;
        }
        final SubLevel sub = hit.body();
        final Pose3dc pose = MateTargeting.poseOf(sub);
        final Vector3dc scale = pose.scale();
        final Vec3 cameraPos = camera.getPosition();

        final MateFrames.Resolved resolved = MateFrames.resolveLocal(sub, ref);
        // ⚠ 必须以「高亮点」为原点重新定位，而不是把 2×10⁷ 量级的 plot 局部坐标直接丢给顶点缓冲：
        // plot 坐标是 ~2e7，而高亮尺寸是 0.02~0.1 格 —— float 只有 24 位有效位，
        // 2e7 处的精度已经是几个格，加减 0.05 会被完全吃掉（高亮要么消失要么跑到很远）。
        // 现有的方块描边之所以正常，正是因为它按「每个方块中心」重新定位（见 SubLevelOutlineRenderer）。
        final Vector3d anchor = MateFrames.worldPoint(pose, resolved);
        poseStack.pushPose();
        poseStack.translate(anchor.x - cameraPos.x, anchor.y - cameraPos.y, anchor.z - cameraPos.z);
        poseStack.mulPose(new Quaternionf(pose.orientation()));
        final PoseStack.Pose last = poseStack.last();

        final float r = 1.0f;
        final float g = 0.85f;
        final float b = 0.20f;
        final AABB box = hit.baseBox();
        final Vector3d point = resolved.point();

        switch (ref.kind()) {
            case FACE, FACE_CENTER -> {
                final Vector3d[] corners = new Vector3d[4];
                for (int i = 0; i < 4; i++) {
                    corners[i] = MateRef.faceCorner(hit.baseBlock(), hit.baseFace(), i, box);
                }
                // ⚠ 角点顺序必须是 0→1→3→2：faceCorner 的下标是「bit0 = u 正负、bit1 = v 正负」，
                // 直接 0→1→2→3 连接会把 1→2 画成对角线，看起来就是「一条穿过面中心、连着对边中点的线」。
                final int[] order = {0, 1, 3, 2};
                for (int i = 0; i < 4; i++) {
                    final Vector3d p1 = corners[order[i]];
                    final Vector3d p2 = corners[order[(i + 1) & 3]];
                    // 相对锚点（都在原点附近，精度足够）
                    BoxOutlineRenderer.addEdge(last, vc,
                            rel(p1.x - point.x, scale.x()), rel(p1.y - point.y, scale.y()),
                            rel(p1.z - point.z, scale.z()),
                            rel(p2.x - point.x, scale.x()), rel(p2.y - point.y, scale.y()),
                            rel(p2.z - point.z, scale.z()),
                            HOVER_THICKNESS, r, g, b, 1.0f);
                }
            }
            case EDGE, EDGE_CENTER -> {
                final Vector3d dir = MateRef.faceEdgeDirection(hit.baseFace(), ref.feature());
                final double length = MateRef.extentOf(box, dir);
                final Vector3d a = new Vector3d(dir).mul(-length * 0.5);
                final Vector3d c = new Vector3d(dir).mul(length * 0.5);
                BoxOutlineRenderer.addEdge(last, vc,
                        rel(a.x, scale.x()), rel(a.y, scale.y()), rel(a.z, scale.z()),
                        rel(c.x, scale.x()), rel(c.y, scale.y()), rel(c.z, scale.z()),
                        HOVER_THICKNESS, r, g, b, 1.0f);
            }
            default -> {
                final double h = 0.05;
                BoxOutlineRenderer.addWireBox(last, vc,
                        -h * scale.x(), -h * scale.y(), -h * scale.z(),
                        h * scale.x(), h * scale.y(), h * scale.z(),
                        HOVER_THICKNESS, r, g, b, 1.0f);
            }
        }
        poseStack.popPose();
    }

    /** 相对坐标 × 缩放（锚点已把 pose 平移吃掉，这里只处理旋转后的比例）。 */
    private static double rel(final double value, final double scale) {
        return value * scale;
    }
}
