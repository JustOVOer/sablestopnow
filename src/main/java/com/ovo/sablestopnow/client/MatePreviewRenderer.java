package com.ovo.sablestopnow.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.mate.MateRef;
import com.ovo.sablestopnow.mate.RefKind;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.List;

/**
 * 配合模式的世界内可视化：<b>右键将会选中的那个点/棱/面</b>，加上已经确认的两端参考。
 *
 * <h2>为什么每帧现算候选</h2>
 * 「渲染右键将会选择到的几何」要求预览与真正提交的参考<b>必须逐帧一致</b>，否则玩家会看到 A 选中了 B。
 * 因此候选不缓存在 tick 里，而是每帧调用一次 {@link MateTargeting#raycast()} 并写回
 * {@link MateClientState}（右键时直接复用这一份，不再重新求交）。
 *
 * <h2>画法</h2>
 * 顶点全部算成<b>世界坐标</b>再减相机位置，因此支持被旋转/缩放的物理结构（棱和面在局部空间里是
 * 轴对齐的，但在世界里不是，用不了轴对齐的 {@code BoxOutlineRenderer.addEdge}）。
 * 面用半透明四边形（{@code BOXES_NO_DEPTH}）填充 + 描边，棱与轴用线段（{@code LINES_NO_DEPTH}）。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT)
public final class MatePreviewRenderer {

    /** 预览候选（右键将选中）：亮青色。 */
    private static final float[] CANDIDATE = { 0.35f, 0.95f, 1.0f };
    /** 已确认的待选端：亮绿色。 */
    private static final float[] CONFIRMED = { 0.45f, 1.0f, 0.45f };
    /** 结构级参考：琥珀色。 */
    private static final float[] BODY_REF = { 1.0f, 0.78f, 0.25f };

    private MatePreviewRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(final RenderLevelStageEvent event) {
        // AFTER_LEVEL 是整帧世界渲染的最后一站。
        //
        // 以前挂 AFTER_ENTITIES：预览用的虽然是 NO_DEPTH 材质（不测深度），但「不测深度」只保证
        // 自己不被别人挡住，挡不住「后画的东西盖住自己」。Sable 的结构描边正是在实体之后才画的，
        // 于是选中棱时描边会直接压掉预览。放到最后画，任何后画的描边都不存在了。
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (!PhysicsStaffItem.isHolding(mc.player)) {
            MateClientState.clearPreview();
            return;
        }

        // 每帧刷新候选（预览与提交必须一致）
        updateCandidate(mc);

        final List<MateRef> pending = MateClientState.pending();
        final MateRef preview = MateClientState.previewTarget();
        if (preview == null && pending.isEmpty()) {
            return;
        }

        final Vec3 cameraPos = event.getCamera().getPosition();
        final PoseStack poseStack = event.getPoseStack();
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);

        // 1) 线：结构级参考的轴/中心标记 + 所有棱
        final VertexConsumer lines = buffers.getBuffer(ModRenderTypes.LINES_NO_DEPTH);
        for (final MateRef ref : pending) {
            drawRef(poseStack, lines, null, ref, CONFIRMED, false);
        }
        if (preview != null) {
            final float[] color = preview.kind().isBlockLevel() ? CANDIDATE : BODY_REF;
            drawRef(poseStack, lines, null, preview, color, true);
        }
        buffers.endBatch(ModRenderTypes.LINES_NO_DEPTH);

        // 2) 面：半透明填充
        final VertexConsumer boxes = buffers.getBuffer(ModRenderTypes.BOXES_NO_DEPTH);
        for (final MateRef ref : pending) {
            drawFace(poseStack, boxes, ref, CONFIRMED);
        }
        if (preview != null) {
            drawFace(poseStack, boxes, preview, preview.kind().isBlockLevel() ? CANDIDATE : BODY_REF);
        }
        buffers.endBatch(ModRenderTypes.BOXES_NO_DEPTH);

        poseStack.popPose();
    }

    // ============ 候选刷新 ============

    private static void updateCandidate(final Minecraft mc) {
        if (!MateClientState.inMode()) {
            MateClientState.clearPreview();
            return;
        }
        final MateTargeting.Hit hit = MateTargeting.raycast();
        if (hit == null) {
            MateClientState.clearPreview();
            return;
        }
        // 按住结构参考修饰键（复用多选键 Ctrl）→ 循环到结构级参考
        final MateRef ref = StaffKeyMappings.MULTI_SELECT.isDown()
                ? MateTargeting.bodyCandidate(hit.body(), MateClientState.bodyRefCycle())
                : MateTargeting.blockCandidate(hit.body(), hit);

        // 已经选过同一个结构就不能再选，避免预览出一个必然被拒的候选
        for (final MateRef chosen : MateClientState.pending()) {
            if (chosen.sameBody(ref)) {
                MateClientState.clearPreview();
                return;
            }
        }

        final Vector3d point = MateTargeting.worldPoint(hit.body(), ref);
        final Vector3d direction = MateTargeting.worldDirection(hit.body(), ref);
        MateClientState.setPreview(ref, point, direction);
    }

    // ============ 绘制 ============

    /** 线框部分：角点方块 / 棱线 / 面描边 / 结构中心十字 / 结构轴长线。 */
    private static void drawRef(final PoseStack poseStack, final VertexConsumer vc,
                                @Nullable final SubLevel body, final MateRef ref,
                                final float[] color, final boolean bold) {
        final SubLevel target = body != null ? body : MateTargeting.byId(ref.body());
        if (target == null) {
            return;
        }
        final float thickness = bold ? 0.035f : 0.02f;
        final PoseStack.Pose pose = poseStack.last();

        switch (ref.kind()) {
            case VERTEX -> {
                final Vector3d p = MateTargeting.worldPoint(target, ref);
                wireCube(pose, vc, p, 0.09, thickness, color);
            }
            case EDGE -> {
                final Vector3d mid = MateTargeting.worldPoint(target, ref);
                final Vector3d dir = MateTargeting.worldDirection(target, ref);
                if (dir == null) {
                    return;
                }
                final Vector3d a = new Vector3d(mid).fma(-0.5, dir);
                final Vector3d b = new Vector3d(mid).fma(0.5, dir);
                segment(pose, vc, a, b, color);
                // 两端加个小方块，强调这是一条“棱”
                wireCube(pose, vc, a, 0.05, thickness, color);
                wireCube(pose, vc, b, 0.05, thickness, color);
            }
            case FACE -> {
                // 面的描边交给面填充那一趟，这里只画法线箭头起点
                final Vector3d center = MateTargeting.worldPoint(target, ref);
                wireCube(pose, vc, center, 0.05, thickness, color);
            }
            case BODY_CENTER -> {
                final Vector3d center = MateTargeting.worldCenter(target);
                wireCube(pose, vc, center, 0.16, thickness, color);
            }
            case BODY_AXIS -> {
                final Vector3d center = MateTargeting.worldCenter(target);
                final Vector3d dir = MateTargeting.worldDirection(target, ref);
                if (dir == null) {
                    return;
                }
                final double half = axisHalfLength(target);
                final Vector3d a = new Vector3d(center).fma(-half, dir);
                final Vector3d b = new Vector3d(center).fma(half, dir);
                segment(pose, vc, a, b, color);
                wireCube(pose, vc, center, 0.10, thickness, color);
            }
            case BODY_PLANE -> {
                // 面的填充/描边在 drawFace 里
            }
        }
    }

    /** 面填充（含描边）。 */
    private static void drawFace(final PoseStack poseStack, final VertexConsumer vc,
                                 final MateRef ref, final float[] color) {
        final SubLevel target = MateTargeting.byId(ref.body());
        if (target == null) {
            return;
        }
        final PoseStack.Pose pose = poseStack.last();
        switch (ref.kind()) {
            case FACE -> {
                final BlockPos block = ref.block();
                final Direction face = ref.face();
                if (block == null || face == null) {
                    return;
                }
                final Vector3d c0 = toWorld(target, MateRef.faceCorner(block, face, 0));
                final Vector3d c1 = toWorld(target, MateRef.faceCorner(block, face, 1));
                final Vector3d c2 = toWorld(target, MateRef.faceCorner(block, face, 2));
                final Vector3d c3 = toWorld(target, MateRef.faceCorner(block, face, 3));
                quad(pose, vc, c0, c1, c2, c3, color, 0.28f);
            }
            case BODY_PLANE -> {
                final Vector3d center = MateTargeting.worldCenter(target);
                final Vector3d normal = MateTargeting.worldDirection(target, ref);
                if (normal == null) {
                    return;
                }
                final Vector3d u = com.ovo.sablestopnow.mate.MateFrames.anyPerpendicular(normal);
                final Vector3d v = new Vector3d(normal).cross(u).normalize();
                final double r = planeHalfExtent(target) + 0.5;
                final Vector3d p0 = new Vector3d(center).fma(-r, u).fma(-r, v);
                final Vector3d p1 = new Vector3d(center).fma(r, u).fma(-r, v);
                final Vector3d p2 = new Vector3d(center).fma(r, u).fma(r, v);
                final Vector3d p3 = new Vector3d(center).fma(-r, u).fma(r, v);
                quad(pose, vc, p0, p1, p2, p3, color, 0.16f);
            }
            default -> {
                // 其它参考没有面
            }
        }
    }

    // ============ 几何助手 ============

    private static Vector3d toWorld(final SubLevel body, final Vector3d local) {
        return MateTargeting.poseOf(body).transformPosition(new Vector3d(local), new Vector3d());
    }

    private static double axisHalfLength(final SubLevel body) {
        final BoundingBox3ic bb = body.getPlot().getBoundingBox();
        final double w = bb.maxX() - bb.minX() + 1;
        final double h = bb.maxY() - bb.minY() + 1;
        final double l = bb.maxZ() - bb.minZ() + 1;
        return Math.max(w, Math.max(h, l)) * 0.5 + 1.0;
    }

    private static double planeHalfExtent(final SubLevel body) {
        final BoundingBox3ic bb = body.getPlot().getBoundingBox();
        final double w = bb.maxX() - bb.minX() + 1;
        final double h = bb.maxY() - bb.minY() + 1;
        final double l = bb.maxZ() - bb.minZ() + 1;
        return Math.max(w, Math.max(h, l)) * 0.5;
    }

    private static void segment(final PoseStack.Pose pose, final VertexConsumer vc,
                                final Vector3d a, final Vector3d b, final float[] color) {
        vc.addVertex(pose, (float) a.x, (float) a.y, (float) a.z)
                .setColor(color[0], color[1], color[2], 1.0f)
                .setNormal(pose, 0.0f, 1.0f, 0.0f);
        vc.addVertex(pose, (float) b.x, (float) b.y, (float) b.z)
                .setColor(color[0], color[1], color[2], 1.0f)
                .setNormal(pose, 0.0f, 1.0f, 0.0f);
    }

    /** 一个轴对齐的小立方体线框（角点/中心标记用）。 */
    private static void wireCube(final PoseStack.Pose pose, final VertexConsumer vc,
                                 final Vector3d center, final double half, final float thickness,
                                 final float[] color) {
        final double cx = center.x;
        final double cy = center.y;
        final double cz = center.z;
        org.joml.Vector3d[] c = {
                new org.joml.Vector3d(cx - half, cy - half, cz - half),
                new org.joml.Vector3d(cx + half, cy - half, cz - half),
                new org.joml.Vector3d(cx + half, cy - half, cz + half),
                new org.joml.Vector3d(cx - half, cy - half, cz + half),
                new org.joml.Vector3d(cx - half, cy + half, cz - half),
                new org.joml.Vector3d(cx + half, cy + half, cz - half),
                new org.joml.Vector3d(cx + half, cy + half, cz + half),
                new org.joml.Vector3d(cx - half, cy + half, cz + half)
        };
        final int[][] edges = {
                { 0, 1 }, { 1, 2 }, { 2, 3 }, { 3, 0 },
                { 4, 5 }, { 5, 6 }, { 6, 7 }, { 7, 4 },
                { 0, 4 }, { 1, 5 }, { 2, 6 }, { 3, 7 }
        };
        for (final int[] e : edges) {
            segment(pose, vc, c[e[0]], c[e[1]], color);
        }
    }

    private static void quad(final PoseStack.Pose pose, final VertexConsumer vc,
                             final Vector3d a, final Vector3d b, final Vector3d c, final Vector3d d,
                             final float[] color, final float alpha) {
        vc.addVertex(pose, (float) a.x, (float) a.y, (float) a.z).setColor(color[0], color[1], color[2], alpha);
        vc.addVertex(pose, (float) b.x, (float) b.y, (float) b.z).setColor(color[0], color[1], color[2], alpha);
        vc.addVertex(pose, (float) c.x, (float) c.y, (float) c.z).setColor(color[0], color[1], color[2], alpha);
        vc.addVertex(pose, (float) d.x, (float) d.y, (float) d.z).setColor(color[0], color[1], color[2], alpha);
    }
}
