package com.ovo.sablestopnow.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.mate.Mate;
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
 * 配合模式的世界内可视化：<b>右键将会选中的那个点/棱/面</b>、已经确认的两端参考，
 * 以及<b>右侧树里当前选中那条配合的两端参考</b>。
 *
 * <h2>为什么每帧现算候选</h2>
 * 「渲染右键将会选择到的几何」要求预览与真正提交的参考<b>必须逐帧一致</b>，否则玩家会看到 A 选中了 B。
 * 因此候选不缓存在 tick 里，而是每帧调用一次 {@link MateTargeting#raycast()} 并写回
 * {@link MateClientState}（右键时直接复用这一份，不再重新求交）。
 *
 * <h2>为什么点/棱用「实心棱柱」而不是 GL 线段</h2>
 * GL 线宽受驱动限制（很多驱动把 {@code glLineWidth > 1} 忽略掉，1px 的线在远处根本看不见），
 * 而且线材质与 Sable 的结构描边是两种材质，先后顺序不可控。现在：
 * <ul>
 *   <li>顶点 = 一个实心小立方体；棱 = 一根<b>四棱柱</b>（4 个四边形）；结构轴同理。</li>
 *   <li>全部用 {@code BOXES_NO_DEPTH}（QUADS、不测深度、只写颜色），并且整帧绘制挂在
 *       {@code AFTER_BLOCK_ENTITIES}（所有世界几何、含 Sable 描边之后），因此<b>一定盖在物理结构
 *       与其描边之上</b>。<b>不能用 {@code AFTER_LEVEL}</b>：那个阶段 NeoForge 传给事件的位姿栈是
 *       空栈（相机旋转丢失），预览会被甩到画面角落。</li>
 *   <li>面仍然用半透明四边形填充，先画面、再画棱柱，保证棱压住面。</li>
 * </ul>
 *
 * <h2>坐标</h2>
 * 顶点全部用 {@link MateTargeting#worldPoint}/{@link MateTargeting#worldDirection} 算成世界坐标
 * 再减相机位置（支持被旋转/缩放的物理结构）。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT)
public final class MatePreviewRenderer {

    /** 预览候选（右键将选中）：亮青色。 */
    private static final float[] CANDIDATE = { 0.35f, 0.95f, 1.0f };
    /** 已确认的待选端：亮绿色。 */
    private static final float[] CONFIRMED = { 0.45f, 1.0f, 0.45f };
    /** 结构级参考：琥珀色。 */
    private static final float[] BODY_REF = { 1.0f, 0.78f, 0.25f };
    /** 树里选中那条配合的两端：亮黄。 */
    private static final float[] SELECTED_MATE = { 1.0f, 0.92f, 0.35f };

    /** 棱/轴的粗细（半宽，格）。要足够粗，才能压过物理结构那圈 0.05 半宽的轮廓条。 */
    private static final double EDGE_HALF_WIDTH = 0.060;
    private static final double EDGE_HALF_WIDTH_BOLD = 0.095;
    /** 点类候选（角点 / 面心 / 棱心）那个实心立方体的半边长；远远就能看见，又不会挡住整格。 */
    private static final double POINT_HALF = 0.160;
    /** 面的填充不透明度与边框粗细。 */
    private static final float FACE_ALPHA = 0.40f;
    private static final double FACE_BORDER_HALF_WIDTH = 0.055;

    private MatePreviewRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(final RenderLevelStageEvent event) {
        // 阶段必须挑「相机变换还在全局 model-view 栈里」的那个。
        //
        // 坑：AFTER_LEVEL 看着最晚最安全，但它是在 GameRenderer 里、LevelRenderer.renderLevel
        // **返回之后**才派发的，而 renderLevel 结尾已经把 `matrix4fstack.popMatrix()` +
        // `applyModelViewMatrix()` 做掉了 ⇒ 相机变换没了；而且那一站传给事件的 poseStack 还是
        // null（NeoForge 会替换成一个空 PoseStack，见 ClientHooks.dispatchRenderStage）。于是预览
        // 会带着正确世界坐标、却在一套「没有相机旋转」的矩阵下被投影 —— 这就是「预览完全不在
        // 物理结构上、总甩到画面角落」的根因。
        //
        // renderLevel 内部派发的阶段（AFTER_SKY / AFTER_ENTITIES / …）则满足两个条件：
        //   · 全局 model-view 栈里已经有 frustumMatrix（相机旋转）；
        //   · 事件给的 poseStack 是**空栈**（单位矩阵），世界几何的写法就是「世界坐标 + translate(-cameraPos)」，
        //     本文件与 SubLevelOutlineRenderer / StaffEnhanceRenderer 用的都是这套。
        // 在这些阶段里 AFTER_BLOCK_ENTITIES 最合适：它在所有世界几何（含 Sable 的描边）之后，
        // 且两种图形设置下都在主渲染目标上（AFTER_PARTICLES 在 Fabulous 下会落到 particlesTarget）。
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
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
        final Mate highlighted = MateClientState.highlightedMate();
        if (preview == null && pending.isEmpty() && highlighted == null) {
            return;
        }

        final Vec3 cameraPos = event.getCamera().getPosition();
        final PoseStack poseStack = event.getPoseStack();
        if (poseStack == null) {
            return;
        }

        poseStack.pushPose();
        poseStack.translate(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        final PoseStack.Pose pose = poseStack.last();

        // 统一走 BOXES_NO_DEPTH（QUADS）：面先画、棱柱后画 ⇒ 棱一定压住面与结构描边。
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        final VertexConsumer quads = buffers.getBuffer(ModRenderTypes.BOXES_NO_DEPTH);

        // 1) 面（半透明填充 + 粗边框）
        if (highlighted != null) {
            drawFace(pose, quads, highlighted.a(), SELECTED_MATE, FACE_ALPHA);
            drawFace(pose, quads, highlighted.b(), SELECTED_MATE, FACE_ALPHA);
        }
        for (final MateRef ref : pending) {
            drawFace(pose, quads, ref, CONFIRMED, FACE_ALPHA);
        }
        if (preview != null) {
            drawFace(pose, quads, preview, preview.kind().isBlockLevel() ? CANDIDATE : BODY_REF, FACE_ALPHA);
        }

        // 2) 点/棱/结构基准（实心粗几何，压在最上面）
        if (highlighted != null) {
            drawRef(pose, quads, highlighted.a(), SELECTED_MATE, true);
            drawRef(pose, quads, highlighted.b(), SELECTED_MATE, true);
        }
        for (final MateRef ref : pending) {
            drawRef(pose, quads, ref, CONFIRMED, false);
        }
        if (preview != null) {
            drawRef(pose, quads, preview, preview.kind().isBlockLevel() ? CANDIDATE : BODY_REF, true);
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
        logDiagnostics(hit, ref, point);
    }

    /**
     * 诊断预览坐标（临时）：只在「候选参考变化」时打一条 INFO。
     *
     * <p>判据：射线命中点<b>必须落在「眼睛 → 视线」这条射线上</b>。因此这里直接打印
     * {@code eye / look / |world−eye| / 与视线夹角}：夹角接近 0 说明世界换算是对的，
     * 夹角大说明 base→world 的换算（或用的位姿）有问题。</p>
     */
    private static void logDiagnostics(final MateTargeting.Hit hit, final MateRef ref, final Vector3d world) {
        final String key = ref.describe() + "@" + hit.body().getUniqueId();
        if (key.equals(lastDiagKey)) {
            return;
        }
        lastDiagKey = key;
        final var pose = MateTargeting.poseOf(hit.body());
        final Vector3d local = com.ovo.sablestopnow.mate.MateFrames.resolveLocal(hit.body(), ref).point();
        final net.minecraft.client.player.LocalPlayer player = Minecraft.getInstance().player;
        String ray = "";
        if (player != null) {
            final Vec3 eye = player.getEyePosition(1.0f);
            final Vec3 look = player.getLookAngle();
            final double dx = world.x - eye.x;
            final double dy = world.y - eye.y;
            final double dz = world.z - eye.z;
            final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // 夹角（度）：命中点应当正好在这条射线上
            final double dot = (dx * look.x + dy * look.y + dz * look.z) / Math.max(1.0e-6, len);
            final double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
            ray = String.format(java.util.Locale.ROOT,
                    " | eye=(%.2f, %.2f, %.2f) look=(%.3f, %.3f, %.3f) dist=%.2f rayAngle=%.1fdeg",
                    eye.x, eye.y, eye.z, look.x, look.y, look.z, len, angle);
        }
        SablestopNow.LOGGER.info(
                "[mate-preview] ref={} baseHit=({}, {}, {}) baseBlock={} baseFace={} "
                        + "| pose.pos=({}, {}, {}) rotPoint=({}, {}, {}) scale=({}, {}, {}) "
                        + "| local=({}, {}, {}) world=({}, {}, {}){}",
                ref.describe(),
                fmt(hit.baseHit().x), fmt(hit.baseHit().y), fmt(hit.baseHit().z),
                hit.baseBlock(), hit.baseFace(),
                fmt(pose.position().x()), fmt(pose.position().y()), fmt(pose.position().z()),
                fmt(pose.rotationPoint().x()), fmt(pose.rotationPoint().y()), fmt(pose.rotationPoint().z()),
                fmt(pose.scale().x()), fmt(pose.scale().y()), fmt(pose.scale().z()),
                fmt(local.x), fmt(local.y), fmt(local.z),
                fmt(world.x), fmt(world.y), fmt(world.z), ray);
    }

    @Nullable private static String lastDiagKey;

    private static String fmt(final double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    // ============ 绘制 ============

    /** 点/棱/结构基准：全部用实心几何（立方体 + 四棱柱）。 */
    private static void drawRef(final PoseStack.Pose pose, final VertexConsumer vc,
                                final MateRef ref, final float[] color, final boolean bold) {
        final SubLevel target = MateTargeting.byId(ref.body());
        if (target == null) {
            return;
        }
        final double hw = bold ? EDGE_HALF_WIDTH_BOLD : EDGE_HALF_WIDTH;

        switch (ref.kind()) {
            // 三类「点」（角点 / 面心 / 棱心）都只画**一个实心立方体**：点就该长成点。
            // 以前角点还套一个三轴十字、面心套面内十字、棱心套一段短棱柱，画面上反而看不出
            // 「这个候选到底是点还是棱」—— 现在只有棱是棱柱、面是四边形，其余一律方块。
            case VERTEX, FACE_CENTER, EDGE_CENTER ->
                    solidCube(pose, vc, MateTargeting.worldPoint(target, ref), POINT_HALF, color, 0.95f);
            case EDGE -> {
                final Vector3d mid = MateTargeting.worldPoint(target, ref);
                final Vector3d dir = MateTargeting.worldDirection(target, ref);
                if (dir == null) {
                    return;
                }
                final Vector3d a = new Vector3d(mid).fma(-0.5, dir);
                final Vector3d b = new Vector3d(mid).fma(0.5, dir);
                prism(pose, vc, a, b, hw, color, 0.95f);
            }
            case FACE -> {
                // 面本身由 drawFace 填充；这里只把面心标出来
                solidCube(pose, vc, MateTargeting.worldPoint(target, ref), POINT_HALF * 0.7, color, 0.9f);
            }
            case BODY_CENTER -> solidCube(pose, vc, MateTargeting.worldCenter(target), 0.12, color, 0.9f);
            case BODY_AXIS -> {
                final Vector3d center = MateTargeting.worldCenter(target);
                final Vector3d dir = MateTargeting.worldDirection(target, ref);
                if (dir == null) {
                    return;
                }
                final double half = axisHalfLength(target);
                final Vector3d a = new Vector3d(center).fma(-half, dir);
                final Vector3d b = new Vector3d(center).fma(half, dir);
                prism(pose, vc, a, b, hw * 1.2, color, 0.85f);
                solidCube(pose, vc, center, 0.10, color, 0.9f);
            }
            case BODY_PLANE -> {
                // 面在 drawFace 里，这里补一个小的法线指示段
                final Vector3d center = MateTargeting.worldCenter(target);
                final Vector3d normal = MateTargeting.worldDirection(target, ref);
                if (normal == null) {
                    return;
                }
                final Vector3d tip = new Vector3d(center).fma(1.2, normal);
                prism(pose, vc, center, tip, hw * 1.1, color, 0.8f);
            }
        }
    }

    /** 面填充（半透明）。 */
    private static void drawFace(final PoseStack.Pose pose, final VertexConsumer vc,
                                 final MateRef ref, final float[] color, final float alpha) {
        if (ref.kind() != RefKind.FACE && ref.kind() != RefKind.BODY_PLANE) {
            return;
        }
        final SubLevel target = MateTargeting.byId(ref.body());
        if (target == null) {
            return;
        }
        if (ref.kind() == RefKind.FACE) {
            final BlockPos block = ref.block();
            final Direction face = ref.face();
            if (block == null || face == null) {
                return;
            }
            final Vector3d c0 = MateTargeting.worldPoint(target, MateRef.block(ref.body(), RefKind.VERTEX, block, face, 0));
            final Vector3d c1 = MateTargeting.worldPoint(target, MateRef.block(ref.body(), RefKind.VERTEX, block, face, 1));
            final Vector3d c2 = MateTargeting.worldPoint(target, MateRef.block(ref.body(), RefKind.VERTEX, block, face, 2));
            final Vector3d c3 = MateTargeting.worldPoint(target, MateRef.block(ref.body(), RefKind.VERTEX, block, face, 3));
            quad(pose, vc, c0, c1, c2, c3, color, alpha);
            // 粗边框：面的四条边也画成棱柱，否则半透明填充在结构上一眼看不出来
            prism(pose, vc, c0, c1, FACE_BORDER_HALF_WIDTH, color, Math.min(1.0f, alpha + 0.5f));
            prism(pose, vc, c1, c2, FACE_BORDER_HALF_WIDTH, color, Math.min(1.0f, alpha + 0.5f));
            prism(pose, vc, c2, c3, FACE_BORDER_HALF_WIDTH, color, Math.min(1.0f, alpha + 0.5f));
            prism(pose, vc, c3, c0, FACE_BORDER_HALF_WIDTH, color, Math.min(1.0f, alpha + 0.5f));
            return;
        }
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
        quad(pose, vc, p0, p1, p2, p3, color, alpha);
    }

    // ============ 几何助手 ============

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

    /**
     * 一根「粗线」：以 a→b 为轴的四棱柱（4 个四边形，无端盖）。
     * 不依赖 GL 线宽，任何视角下都足够粗，而且用 QUADS 材质可以和面共用一趟绘制。
     */
    private static void prism(final PoseStack.Pose pose, final VertexConsumer vc,
                              final Vector3d a, final Vector3d b, final double halfWidth,
                              final float[] color, final float alpha) {
        final Vector3d dir = new Vector3d(b).sub(a);
        if (dir.lengthSquared() < 1e-9) {
            return;
        }
        dir.normalize();
        final Vector3d u = com.ovo.sablestopnow.mate.MateFrames.anyPerpendicular(dir).normalize();
        final Vector3d v = new Vector3d(dir).cross(u).normalize();

        final Vector3d au = new Vector3d(u).mul(halfWidth);
        final Vector3d av = new Vector3d(v).mul(halfWidth);
        final Vector3d[] p = new Vector3d[4];
        final Vector3d[] q = new Vector3d[4];
        for (int i = 0; i < 4; i++) {
            final double su = (i == 0 || i == 3) ? -1 : 1;
            final double sv = (i == 0 || i == 1) ? -1 : 1;
            p[i] = new Vector3d(a).fma(su, au).fma(sv, av);
            q[i] = new Vector3d(b).fma(su, au).fma(sv, av);
        }
        for (int i = 0; i < 4; i++) {
            final int j = (i + 1) & 3;
            quad(pose, vc, p[i], p[j], q[j], q[i], color, alpha);
        }
    }

    /** 实心小立方体（6 个四边形），用于角点/中心标记。 */
    private static void solidCube(final PoseStack.Pose pose, final VertexConsumer vc,
                                  final Vector3d center, final double half,
                                  final float[] color, final float alpha) {
        final double x0 = center.x - half;
        final double y0 = center.y - half;
        final double z0 = center.z - half;
        final double x1 = center.x + half;
        final double y1 = center.y + half;
        final double z1 = center.z + half;
        final Vector3d a = new Vector3d(x0, y0, z0);
        final Vector3d b = new Vector3d(x1, y0, z0);
        final Vector3d c = new Vector3d(x1, y0, z1);
        final Vector3d d = new Vector3d(x0, y0, z1);
        final Vector3d e = new Vector3d(x0, y1, z0);
        final Vector3d f = new Vector3d(x1, y1, z0);
        final Vector3d g = new Vector3d(x1, y1, z1);
        final Vector3d h = new Vector3d(x0, y1, z1);
        quad(pose, vc, a, b, c, d, color, alpha);   // down
        quad(pose, vc, e, f, g, h, color, alpha);   // up
        quad(pose, vc, a, b, f, e, color, alpha);   // north
        quad(pose, vc, c, d, h, g, color, alpha);   // south
        quad(pose, vc, b, c, g, f, color, alpha);   // east
        quad(pose, vc, d, a, e, h, color, alpha);   // west
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
