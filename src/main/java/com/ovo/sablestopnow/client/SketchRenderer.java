package com.ovo.sablestopnow.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.sketch.Sketch;
import com.ovo.sablestopnow.sketch.SketchClientState;
import com.ovo.sablestopnow.sketch.SketchDof;
import com.ovo.sablestopnow.sketch.SketchSession;
import com.ovo.sablestopnow.sketch.Solid;
import com.ovo.sablestopnow.sketch.SolidClientState;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 在世界里画草图。
 *
 * <h2>坐标怎么来的</h2>
 * 草图存的是**平面内 (u,v)**，平面活在**子结构 plot 局部系**里，所以：
 * {@code (u,v) -> SketchPlane.toLocal -> pose.transformPosition -> 世界 -> 减相机位置}。
 * 最后一步是必须的：MC 的渲染栈在这个阶段用的就是「世界坐标 - 相机坐标」，
 * 这与 {@link SubLevelOutlineRenderer} 画方块线框时完全一致。
 *
 * <h2>两个必须记住的坑（都真踩过）</h2>
 * <ol>
 *   <li><b>{@code entity.vars()} 是变量下标，不是坐标值。</b>
 *       取值必须过 {@code sketch.variable(...)}。直接把下标当坐标用，每条线段都会变成
 *       {@code (n,n+1) -> (n+2,n+3)}，也就是**全都落在 v = u + 1 这条对角线上** ——
 *       表现就是"直线和矩形都成了同一条直线上的线段"。</li>
 *   <li><b>不能用原版 {@code RenderType.lines()}。</b>
 *       自己的类型带 {@code VIEW_OFFSET_Z_LAYERING}（贴在方块面上的草图不会和那个面
 *       z-fighting）并且线宽可调（原版细线在远处几乎看不见）。</li>
 * </ol>
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT)
public final class SketchRenderer {

    /** 圆/圆弧**圆心**的常驻标记色 ✓（与小点区分开 ✓ 见 C-2 用户要求）。 */
    private static final float[] CENTER_COLOR = {0.50f, 0.70f, 1.00f, 1.00f};
    /**
     * 辅助线（构造几何）的颜色 ✓ —— 比实体线暗一档、偏蓝灰 ✓
     * （需求 ①：辅助线必须画成**虚线** ✓ 用户实测"仍然没有画成虚线"✗）
     */
    private static final float[] CONSTRUCTION_COLOR = {0.62f, 0.66f, 0.78f, 1.00f};

    /** 圆的折线段数。草图阶段够用；精确圆弧是拉伸阶段的事。 */
    private static final int CIRCLE_SEGMENTS = 48;

    /** 关键点标识方框的半边长（平面内格数）。 */
    private static final double MARKER_HALF = 0.09;

    /** 普通草图线的颜色（淡蓝，和选择标记区分开）。 */
    private static final float[] IDLE_COLOR = {0.42f, 0.62f, 0.95f};
    /** 正在编辑的那张草图的颜色（亮青，一眼能认出在编辑哪个）。 */
    private static final float[] EDITING_COLOR = {0.25f, 0.95f, 0.85f};
    /** 被选中的那个图元的颜色（亮橙，与编辑色、预览色都能区分）。 */
    private static final float[] SELECTED_COLOR = {1.0f, 0.55f, 0.15f};
    /** 鼠标**指到**的图元的颜色（亮白）。选中是橙色、悬停是白色，两者一眼分得开。 */
    private static final float[] HOVER_COLOR = {1.0f, 1.0f, 1.0f};
    /** 悬停图元的关键点标识（端点/中点/圆心）的颜色。 */
    private static final float[] MARKER_COLOR = {1.0f, 0.95f, 0.45f};
    /** 基准板的颜色（灰蓝，比草图的线暗，不抢视线）。 */
    private static final float[] DATUM_COLOR = {0.45f, 0.50f, 0.62f};
    /** 鼠标下那块基准板的颜色（亮黄，明确告诉玩家"点下去就是它"）。 */
    private static final float[] DATUM_HOVER_COLOR = {0.98f, 0.82f, 0.30f};
    /** 橡皮筋预览线的颜色。 */
    private static final float[] PREVIEW_COLOR = {0.95f, 0.85f, 0.35f};
    /**
     * 推理虚线颜色（淡青）。
     *
     * <p>与橡皮筋的黄色区分开：橡皮筋是"将画出来的东西"，推理线是"参考方向"，
     * 两者同色会让人以为那整条长线也会被画出来。
     */
    private static final float[] INFERENCE_COLOR = {0.45f, 0.85f, 1.0f};
    /**
     * 拉伸实体的线框颜色（暖紫）。
     *
     * <p>刻意选一个和草图（青）、基准板（灰蓝）、预览（黄）、悬停（白）都不撞的颜色：
     * 实体是"已经做出来的东西"，必须一眼和正在画的东西区分开。
     */
    private static final float[] SOLID_COLOR = {0.78f, 0.45f, 0.95f};
    /**
     * **完全定义**的图元颜色（暗灰绿）。
     *
     * <p>⚠ SolidWorks 用黑色，是因为它的背景是白的。这里是暗色世界，
     * 照搬黑色会直接看不见 —— 要的是"在视觉上退到后面"这个语义。
     */
    private static final float[] FULLY_DEFINED_COLOR = {0.42f, 0.52f, 0.46f};
    /** **欠定义**的图元颜色（亮蓝）—— 还没被约束钉住，需要玩家注意。 */
    private static final float[] UNDER_DEFINED_COLOR = {0.30f, 0.62f, 1.0f};
    /**
     * 约束**互相矛盾**时的颜色（红）。
     *
     * <p>这时自由度分析会给出误导结果，所以统一标红而不是照常标"完全/欠定义"——
     * 宁可说"我不知道"，也不要标一个假的"已完全定义"。
     */
    private static final float[] CONFLICT_COLOR = {1.0f, 0.32f, 0.30f};

    /** 正在编辑的草图 id；由草图 UI 设置。 */
    @Nullable
    private static UUID editingId;
    /** 正在编辑时鼠标落点的预览位置（平面内坐标）。 */
    @Nullable
    private static double[] previewPoint;

    /**
     * 旋转拉伸**选中的轴**，渲染用。
     *
     * <p>为什么要有它：左栏的轴列表只能给出坐标（{@code (3.0,0.0)→(5.0,4.0)}），
     * 玩家根本认不出那是屏幕上哪条线。把轴画成一条贯穿的高亮线是唯一能说清的办法。
     *
     * <p>⚠ 用 record 而不是 {@code Object[]}（UUID 和 Double 混装）：后者取值全靠
     * 下标加强制转换，写错一个下标不会编译报错，只会在运行时抛 ClassCastException。
     */
    private record AxisHighlight(UUID body, double au, double av, double du, double dv) {
    }

    @Nullable
    private static AxisHighlight revolveAxis;

    private SketchRenderer() {
    }

    /** UI 进入/退出草图编辑时调用。 */
    public static void setEditing(@Nullable final UUID id) {
        editingId = id;
        previewPoint = null;
    }

    /** UI 选中一条旋转轴时调用（平面内坐标：点 + 方向）。 */
    public static void setRevolveAxis(@Nullable final UUID bodyId, @Nullable final double[] axis) {
        revolveAxis = (bodyId == null || axis == null || axis.length < 4)
                ? null
                : new AxisHighlight(bodyId, axis[0], axis[1], axis[2], axis[3]);
    }

    public static void clearRevolveAxis() {
        revolveAxis = null;
    }

    /** 鼠标在草图平面上的落点（渲染橡皮筋用）。 */
    public static void setPreviewPoint(final double u, final double v) {
        previewPoint = new double[] {u, v};
    }

    public static void clearPreviewPoint() {
        previewPoint = null;
    }

    @SubscribeEvent
    public static void onRenderLevel(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final Level level = mc.level;
        if (level == null) {
            return;
        }
        final List<Sketch> sketches = SketchClientState.current(level);
        // 实体也在这里画：它和草图共用同一套「plot 局部 → 相机相对世界」变换和同一个
        // RenderType/批次。另起一个渲染器就要把变换再写一份（一定会漂移），
        // 还要各自 endBatch（同一个 buffer 被两队各自 flush 会互相截断）。
        final List<Solid.Placed> solids = SolidClientState.placed(level);
        final boolean sketching = SketchClient.active();
        // 不能因为「没有草图」就提前返回：选面阶段恰恰是一张草图都还没有的时候，
        // 那三块基准板必须画出来，否则玩家看不到该点哪里。
        if (sketches.isEmpty() && !sketching && solids.isEmpty()) {
            return;
        }
        if (!(SubLevelContainer.getContainer(level) instanceof final ClientSubLevelContainer container)) {
            return;
        }

        // 一个结构可能挂多张草图，先把 id -> 子结构 建一次表，避免每张草图都线性找一遍
        final Map<UUID, ClientSubLevel> byId = new HashMap<>();
        for (final ClientSubLevel sub : container.getAllSubLevels()) {
            byId.put(sub.getUniqueId(), sub);
        }

        final Camera camera = event.getCamera();
        final Vec3 cameraPos = camera.getPosition();
        final PoseStack poseStack = event.getPoseStack();
        final PoseStack.Pose pose = poseStack.last();
        // 体素碰撞体实验的**可见参照**（用户定的规矩：测试物体必须有描边）。
        // 装置本身是纯碰撞体、不可渲染，所以在这里给它画线框。
        drawVoxelDebugOutline(byId, pose, cameraPos);
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        final VertexConsumer vc = buffers.getBuffer(ModRenderTypes.SKETCH_LINES);

        int drawn = 0;
        int textured = 0;
        int debugLines = 0;
        // ⚠ **新增的两条管线必须各有独立的缓冲源，不能从 buffers 里再取 RenderType。**
        // 曾经在这里从同一个 buffers 取了 SKETCH_LINES / SOLID_TEXTURED / LINES_NO_DEPTH 三种，
        // 结果**客户端直接崩**：`BufferBuilder.ensureBuilding` 抛 "Not building!" ——
        // 从 BufferSource 取一个新 RenderType 会让之前那个 builder 结束掉，
        // 于是上面那个 vc 在第一次写顶点时就失效了（崩在 line(...) 里）。
        // 独立的 immediate 源互不影响，且各自 endBatch 一次，边界清楚。
        final MultiBufferSource.BufferSource texBuf =
                MultiBufferSource.immediate(new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 16));
        final VertexConsumer vcTex = texBuf.getBuffer(ModRenderTypes.SOLID_TEXTURED);
        // F3 调试屏打开时，额外把**碰撞盒**描出来（无深度，免得被方块挡住）。
        // 碰撞盒是看不见的，而"手感不对"十有八九是盒子与几何不一致 —— 没有可视化只能靠猜。
        final boolean debugBoxes = mc.getDebugOverlay().showDebugScreen();
        final MultiBufferSource.BufferSource dbgBuf = debugBoxes
                ? MultiBufferSource.immediate(new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 16))
                : null;
        final VertexConsumer vcDebug = dbgBuf == null
                ? null : dbgBuf.getBuffer(ModRenderTypes.LINES_NO_DEPTH);
        // 拉伸实体：从**轮廓**生成的棱柱线框（不是碰撞用的盒分解 —— 那会让圆看起来像一堆方块）。
        for (final Solid.Placed placed : solids) {
            final ClientSubLevel ssub = byId.get(placed.solid().body());
            if (ssub == null) {
                continue; // 结构还没同步过来 —— 跳过，不要当成"结构没了"
            }
            final Pose3dc sPose = ssub.renderPose();
            // 贴图：先按方块 id 取三张 sprite 子矩形（带缓存），取不到就只画线框。
            final com.ovo.sablestopnow.sketch.SolidAppearance look = placed.solid().appearance();
            final SolidTextureCache.Faces faces = look.hasTexture()
                    ? SolidTextureCache.resolve(look.blockId()) : null;
            if (faces != null && !placed.mesh().isEmpty() && placed.planeNormal() != null) {
                // ⚠ **REPEAT 贴图路线**下每个实体可能是**不同的**贴图（不同方块），
                //   所以各自用自己的 immediate 源与 RenderType ——
                //   共用同一个 BufferSource 去取不同 RenderType 会**结束掉前一个 builder**
                //   （此处曾导致客户端崩在 `BufferBuilder.ensureBuilding: Not building!`）。
                final net.minecraft.resources.ResourceLocation repeatTex =
                        com.ovo.sablestopnow.client.RepeatTextureCache.of(look.blockId());
                if (repeatTex != null) {
                    final MultiBufferSource.BufferSource own =
                            MultiBufferSource.immediate(new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 16));
                    final VertexConsumer vcRepeat = own.getBuffer(
                            net.minecraft.client.renderer.RenderType.entityCutoutNoCull(repeatTex));
                    textured += drawTexturedSolid(vcRepeat, pose, cameraPos, sPose, placed, faces,
                            look.blocksPerTile(), repeatTex);
                    own.endBatch();
                } else {
                    textured += drawTexturedSolid(vcTex, pose, cameraPos, sPose, placed, faces,
                            look.blocksPerTile(), null);
                }
            }
            if (vcDebug != null) {
                debugLines += drawCollisionBoxes(vcDebug, pose, cameraPos, sPose,
                        placed.solid(), sketches, DEBUG_BOX_COLOR);
            }
            // ⚠ **有贴图时就不画线框**（用户 2026-10 的要求：方便观察贴图是否成功）。
            //   线框压在贴图上会让人看不清"到底贴上没有、贴得对不对"。
            //   调试用的 F3 碰撞盒是另一条独立管线（LINES_NO_DEPTH），不受影响 ——
            //   需要看形状时按 F3 仍然能看到。
            if (!placed.solid().appearance().hasTexture()) {
                for (final Vector3d[] seg : placed.edges()) {
                    line(vc, pose, world(cameraPos, sPose, seg[0]),
                            world(cameraPos, sPose, seg[1]), SOLID_COLOR);
                    drawn++;
                }
            }
        }
        // 两条独立管线各自收尾（它们用的是自己的 immediate 源，不会和上面的 buffers 互相影响）
        texBuf.endBatch();
        if (dbgBuf != null) {
            dbgBuf.endBatch();
        }
        if (sketching) {
            drawn += drawDatumPlanes(vc, pose, cameraPos, byId);
        }
        // ⚠ 正在编辑的那张草图是**独立对象**（SketchMode.working），**不在 SketchClientState 里** ——
        // 它要等「完成 -> 发服务端 -> 同步回来」才会进客户端镜像。
        // 不在这里单独画一次的话，编辑期间画的图元根本不显示，
        // 表现为「画的线要退出草图才出现」（用户实测反馈）。
        final Sketch working = sketching ? SketchClient.mode().working() : null;
        if (working != null) {
            final ClientSubLevel wsub = byId.get(working.body());
            if (wsub != null) {
                // ★ 需求 ②：**常驻背景网格** ✓ 必须先画（在图元下面）✓
                //   独立缓冲源：从同一个 BufferSource 取第二个 RenderType 会结束掉前一个 builder ✓
                final MultiBufferSource.BufferSource gridBuf =
                        MultiBufferSource.immediate(
                                new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 16));
                final VertexConsumer vcGrid = gridBuf.getBuffer(ModRenderTypes.SKETCH_GRID);
                drawSketchGrid(vcGrid, pose, cameraPos, wsub.renderPose(), working);
                gridBuf.endBatch();
                drawn += drawSketch(vc, pose, cameraPos, wsub.renderPose(), working, EDITING_COLOR);
                // 旋转轴的高亮画在草图之后：它是"参考"，被图元压住就白画了
                drawn += drawRevolveAxis(vc, pose, cameraPos, wsub.renderPose(), working);
                // 吸附生效时在**吸附到的那个关键点**上打标记。
                // 只高亮被指的图元是不够的：玩家把光标放在离端点 0.1 格的地方，
                // 预览会跳到端点上，但他看不出"为什么线头跑到那儿去了"。
                final SketchSession.KeyPoint snap = SketchClient.mode().snapAt();
                if (snap != null) {
                    drawn += marker(vc, pose, cameraPos, wsub.renderPose(), working, snap.u(), snap.v());
                }
                if (previewPoint != null) {
                    drawPreview(vc, pose, cameraPos, wsub.renderPose(), working);
                }
            }
        }
        for (final Sketch sketch : sketches) {
            // 同一张草图的"工作副本"已经在上面画过了，别再画一遍（会重影）
            if (working != null && sketch.id().equals(working.id())) {
                continue;
            }
            final ClientSubLevel sub = byId.get(sketch.body());
            if (sub == null) {
                // 结构还没同步过来（或已经没了）——跳过，不要在这里把它当作"结构已删除"
                continue;
            }
            final boolean editing = editingId != null && editingId.equals(sketch.id());
            final float[] color = editing ? EDITING_COLOR : IDLE_COLOR;
            drawn += drawSketch(vc, pose, cameraPos, sub.renderPose(), sketch, color);
            if (editing && previewPoint != null) {
                drawPreview(vc, pose, cameraPos, sub.renderPose(), sketch);
            }
        }
        if (drawn > 0) {
            // 自建 RenderType 不在 bufferSource 的 fixedBuffers 里，必须自己 endBatch，
            // 否则这批线要等下一批同类绘制才出得来。
            buffers.endBatch(ModRenderTypes.SKETCH_LINES);
        }
    }

    /** 画三块基准板（选面阶段）。返回画出的线段数。 */
    private static int drawDatumPlanes(final VertexConsumer vc, final PoseStack.Pose pose,
                                       final Vec3 cameraPos,
                                       final Map<UUID, ClientSubLevel> byId) {
        final var mode = SketchClient.mode();
        final UUID body = mode.session().body();
        final ClientSubLevel sub = body == null ? null : byId.get(body);
        if (sub == null) {
            // 给"看不到板"排查用：它区分「渲染根本没跑」与「跑了但找不到结构」，
            // 光看屏幕这两种情况一模一样。
            diagPlanes(body, false, 0, 0);
            return 0;
        }
        final Pose3dc renderPose = sub.renderPose();
        final var hovered = mode.hoveredPlane();
        int lines = 0;
        for (final var datum : mode.planes()) {
            final boolean isHovered = hovered != null && hovered.plane().samePlaneAs(datum.plane());
            final float[] color = isHovered ? DATUM_HOVER_COLOR : DATUM_COLOR;
            // 四个角按顺序连成矩形；最后一条把第四个角接回第一个角形成闭合框
            final var corners = com.ovo.sablestopnow.sketch.SketchMode.corners(datum);
            for (int i = 0; i < corners.size(); i++) {
                final Vector3d a = corners.get(i);
                final Vector3d b = corners.get((i + 1) % corners.size());
                line(vc, pose, world(cameraPos, renderPose, a), world(cameraPos, renderPose, b), color);
                lines++;
            }
        }
        diagPlanes(body, true, mode.planes().size(), lines);
        return lines;
    }

    private static long lastPlaneDiagMs;

    /** 旋转轴高亮延伸的半长（平面内格数）。要够长才有"这是一条轴"的感觉。 */
    private static final double AXIS_HALF = 20.0;

    /**
     * 把选中的旋转轴画成一条**贯穿**的高亮线。
     *
     * <p>⚠ 必须"贯穿"而不是只画出那一小段线段：轴的作用是"绕它转"，
     * 只画线段的话玩家分不清转的是这条线段还是它所在的直线（其实整条直线等价）。
     * 画穿过去还顺便说明了"轴可以延伸到轮廓之外"。
     *
     * @return 画出的线段数（诊断用）
     */
    private static int drawRevolveAxis(final VertexConsumer vc, final PoseStack.Pose pose,
                                       final Vec3 cameraPos, final Pose3dc renderPose,
                                       final Sketch working) {
        final AxisHighlight axis = revolveAxis;
        if (axis == null || !working.body().equals(axis.body())) {
            return 0;
        }
        final double au = axis.au();
        final double av = axis.av();
        final double du = axis.du();
        final double dv = axis.dv();
        final double len = Math.hypot(du, dv);
        if (len < 1.0e-9) {
            return 0;
        }
        // 归一化后再延伸：不然方向写得长一点轴就长了，与玩家意图无关
        final double nu = du / len;
        final double nv = dv / len;
        // 玩家点的那一小段本身也画出来（实色），延伸部分淡一些 —— 一眼看出"轴经过这条线"
        line(vc, pose,
                world(cameraPos, renderPose, working, au - nu * AXIS_HALF, av - nv * AXIS_HALF),
                world(cameraPos, renderPose, working, au, av), AXIS_COLOR);
        line(vc, pose,
                world(cameraPos, renderPose, working, au, av),
                world(cameraPos, renderPose, working, au + nu * AXIS_HALF, av + nv * AXIS_HALF),
                AXIS_COLOR);
        return 2;
    }

    /** 旋转轴的高亮色（亮橙红，和选中/悬停/预览都不撞）。 */
    private static final float[] AXIS_COLOR = {1.0f, 0.45f, 0.10f};

    /** 基准板渲染诊断（每秒最多一行）。 */
    private static void diagPlanes(final UUID body, final boolean found, final int plates,
                                   final int lines) {
        final long now = System.currentTimeMillis();
        if (now - lastPlaneDiagMs < 1000L) {
            return;
        }
        lastPlaneDiagMs = now;
        SablestopNow.LOGGER.info("[sketch-diag] render planes: body={} found={} plates={} lines={}",
                body == null ? "null" : body.toString().substring(0, 8), found, plates, lines);
    }

    /** plot 局部点 -> 相机相对的世界坐标。 */
    private static Vec3 world(final Vec3 cameraPos, final Pose3dc renderPose, final Vector3d local) {
        final Vector3d worldPos = renderPose.transformPosition(local, new Vector3d());
        return new Vec3(worldPos.x - cameraPos.x, worldPos.y - cameraPos.y, worldPos.z - cameraPos.z);
    }

    /** 平面内 (u,v) -> 相机相对的世界坐标。 */
    private static Vec3 world(final Vec3 cameraPos, final Pose3dc renderPose,
                              final Sketch sketch, final double u, final double v) {
        return world(cameraPos, renderPose, sketch.plane().toLocal(u, v));
    }

    /**
     * 画一个实体的**带贴图三角面**，返回画出的三角形数。
     *
     * <p>⚠ **法线必须经过 {@code renderPose} 的旋转**：网格里的法线是 **plot 局部**的，
     * 而子结构是可以在世界里任意朝向的。不转的话，光照方向会随结构转向而错乱
     *（同一面在结构转到某个角度后突然变暗），而且这种错只在某些朝向下才看得出来。
     */
    private static int drawTexturedSolid(final VertexConsumer vc, final PoseStack.Pose pose,
                                         final Vec3 cameraPos, final Pose3dc renderPose,
                                         final com.ovo.sablestopnow.sketch.Solid.Placed placed,
                                         final SolidTextureCache.Faces faces,
                                         final double blocksPerTile,
                                         @org.jetbrains.annotations.Nullable
                                         final net.minecraft.resources.ResourceLocation repeatTex) {
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh mesh;
        // ⚠ **两条路线二选一**：
        //   ① **REPEAT 贴图路线**（正解，repeatTex != null）：贴图是拷出来的独立纹理、
        //      GL 环绕 = REPEAT，UV 可以自由超过 1 —— **不做 frac、不折子矩形、不切几何**。
        //      回绕由 GL 在采样时完成 → 插值始终在连续 UV 空间 →
        //      不会出现"角点各自回绕、插值出垃圾（大面积单色/三角块）"。
        //   ② 图集路线（回退）：拿不到 REPEAT 贴图时（动画贴图 / 拷贝失败）走老路。
        if (repeatTex != null) {
            mesh = com.ovo.sablestopnow.sketch.SolidUv.applyTiled(placed.mesh(), blocksPerTile,
                    placed.solid().isRevolution() || placed.solid().extrusion() != null);
        } else {
            mesh = com.ovo.sablestopnow.sketch.SolidUv.applyForRender(placed.mesh(),
                    placed.planeNormal(), blocksPerTile, faces.capUp(), faces.capDown(),
                    faces.side(),
                    placed.solid().isRevolution() || placed.solid().extrusion() != null);
        }
        if (mesh.isEmpty()) {
            return 0;
        }
        final java.util.List<com.ovo.sablestopnow.sketch.SolidMesh.Vertex> verts = mesh.vertices();
        final int[] idx = mesh.indices();
        final org.joml.Quaterniondc rot = renderPose.orientation();
        // ⚠⚠ **每 4 个顶点一个面**，不是 3 个。
        //   贴图用的 RenderType（entityCutoutNoCull）是 **QUADS 模式**，顶点流按 4 个一组消费；
        //   按 3 个喂会**错位**，表现是"每个四边形只有一个三角形被填上"（用户实测）。
        //   applyTriplanar 已经把所有面（四边形与封盖三角形）都展开成四顶点一组。
        for (int i = 0; i + 3 < idx.length; i += 4) {
            for (int k = 0; k < 4; k++) {
                final com.ovo.sablestopnow.sketch.SolidMesh.Vertex v = verts.get(idx[i + k]);
                final Vec3 p = world(cameraPos, renderPose, v.position());
                final Vector3d n = rot.transform(new Vector3d(v.normal()), new Vector3d());
                vc.addVertex(pose, (float) p.x, (float) p.y, (float) p.z)
                        .setColor(255, 255, 255, 255)
                        .setUv((float) v.u(), (float) v.v())
                        .setOverlay(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                        .setLight(FULL_BRIGHT)
                        .setNormal(pose, (float) n.x, (float) n.y, (float) n.z);
            }
        }
        return idx.length / 4;
    }

    /**
     * 光照值：先用**满亮**。
     *
     * <p>⚠ 这是个已知的简化（贴图不受世界光照影响，看起来比周围方块亮一点）。
     * 换真实光照要按实体的世界位置查 {@code LevelRenderer.getLightColor} ——
     * 但那是**每个顶点一次**的查询，代价不小，值得单独一步做（并且要配缓存）。
     * 先让它"能看见"，再让它"融进环境"。
     */
    private static final int FULL_BRIGHT = 0xF000F0;

    /**
     * F3 调试屏打开时，把每个实体的**碰撞盒**描边画出来。
     *
     * <h2>为什么值得单独做</h2>
     * 碰撞盒是**看不见的**，而"手感不对"这类问题（阻滞、卡住、擦不过去）
     * 十有八九是盒子与几何不一致造成的。没有可视化就只能靠猜 ——
     * 而"靠猜"此前已经让我们在物理那条线上绕了很多弯路。
     *
     * <p>画的是 **OBB**（`Solid.orientedBoxes`），不是轴对齐的盒分解 ——
     * 曲面现在用的是斜盒子，画轴对齐的会**指错地方**（看起来"盒子没错"，
     * 而真正的碰撞体是另一批）。这正是"同一份派生数据被用在第二个用途"时必须核对的地方。
     *
     * <p>用**无深度**的线：碰撞盒常常与结构自己的方块重叠，带深度测试的话大部分会被挡住，
     * 那这条调试可视化就白做了。
     *
     * @return 画出的线段数（诊断用）
     */
    private static int drawCollisionBoxes(final VertexConsumer vc, final PoseStack.Pose pose,
                                          final Vec3 cameraPos, final Pose3dc renderPose,
                                          final Solid solid, final List<Sketch> sketches,
                                          final float[] color) {
        final Sketch sketch = solid.resolve(sketches);
        final List<com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox> boxes =
                solid.orientedBoxes(sketch);
        int lines = 0;
        for (final com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox box : boxes) {
            // 8 个角：中心 + 朝向 × (±hx, ±hy, ±hz)
            final Vec3[] corners = new Vec3[8];
            int n = 0;
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sy = -1; sy <= 1; sy += 2) {
                    for (int sz = -1; sz <= 1; sz += 2) {
                        final Vector3d local = new Vector3d(
                                sx * box.halfExtents().x, sy * box.halfExtents().y,
                                sz * box.halfExtents().z);
                        box.rotation().transform(local);
                        corners[n++] = world(cameraPos, renderPose,
                                local.add(box.centre(), new Vector3d()));
                    }
                }
            }
            // 12 条棱：角点下标按 (sx,sy,sz) 的三位二进制编码，差一位的两个角相连
            for (int i = 0; i < 8; i++) {
                for (int bit = 1; bit <= 4; bit <<= 1) {
                    final int j = i ^ bit;
                    if (j > i) {
                        line(vc, pose, corners[i], corners[j], color);
                        lines++;
                    }
                }
            }
        }
        return lines;
    }

    /** 调试用：碰撞盒描边的颜色（亮绿，和草图青/实体紫/轴橙都不撞）。 */
    private static final float[] DEBUG_BOX_COLOR = {0.25f, 1.0f, 0.35f};

    /** 画一张草图的全部图元，返回画出的线段数（诊断用）。 */
    private static int drawSketch(final VertexConsumer vc, final PoseStack.Pose pose,
                                  final Vec3 cameraPos, final Pose3dc renderPose,
                                  final Sketch sketch, final float[] color) {
        int lines = 0;
        // 被选中的图元用另一个颜色画，否则"选中了但看不出选中了"。
        // ⚠ 要按**整个选择集**判断，不能只比"是不是那一个" —— 多选之后
        // 只有第一个会变色，玩家会以为 Ctrl 没生效。
        final boolean isWorking = editingId != null && editingId.equals(sketch.id());
        final var session = isWorking ? SketchClient.mode().session() : null;
        // 鼠标指到的图元：高亮它，并在它的关键点（端点/中点/圆心）上打标识。
        // 这两种反馈是玩家"确认自己对到哪里"的唯一依据 —— 没有它，画线时完全是盲的。
        final Sketch.Entity hovered = isWorking ? SketchClient.mode().hoveredEntity() : null;
        // 约束状态着色（只在**正在编辑**的那张草图上做）：
        // 完全定义 = 暗淡（视觉上退到后面），欠定义 = 亮蓝，约束冲突 = 红（不敢标颜色时说真话）。
        // ⚠ SolidWorks 用黑色表示完全定义是因为它背景是白的；这里是暗色世界，
        // 照搬黑色会直接看不见 —— 要的是"退到后面"这个语义，不是那个色号。
        boolean[] dofFree = null;
        boolean conflicted = false;
        if (session != null && session.working() != null) {
            final Sketch live = session.working();
            dofFree = SketchDof.freeVariablesOf(live);
            // 残差明显不为 0 = 约束互相矛盾。这时自由度分析会给出误导结果，
            // 宁可统一标成"有冲突"也不要照常标颜色骗人。
            conflicted = SketchDof.maxResidual(live) > 1.0e-6;
        }
        for (final Sketch.Entity entity : sketch.entities()) {
            if (sketch.isConstruction(entity)) { continue; } // ★ 辅助线交给屏幕空间**虚线**画 ✓ 否则实线叠虚线看起来还是实线 ✗
            final int[] v = entity.vars();
            final boolean selected = session != null && session.isSelected(entity);
            final float[] entityColor;
            if (selected) {
                entityColor = SELECTED_COLOR;
            } else if (entity.equals(hovered)) {
                // ★ 需求 ⑦：**将要选到的是"点"** ⇒ 直接**橙色**高亮 ✓
                //   （橙色本来就是"选中"色 ⇒ 玩家一眼看出"这次会选到点、不是它所在的线"✓）
                entityColor = hovered.kind() == Sketch.EntityKind.POINT
                        ? SELECTED_COLOR : HOVER_COLOR;
            } else if (dofFree == null) {
                entityColor = color;
            } else if (conflicted) {
                entityColor = CONFLICT_COLOR;
            } else if (SketchDof.isFullyDefined(dofFree, entity)) {
                entityColor = FULLY_DEFINED_COLOR;
            } else {
                entityColor = UNDER_DEFINED_COLOR;
            }
            switch (entity.kind()) {
                case LINE -> {
                    // 必须用 sketch.variable(...) 把下标换成坐标值（见类注释的坑 1）
                    line(vc, pose,
                            world(cameraPos, renderPose, sketch,
                                    sketch.variable(v[0]), sketch.variable(v[1])),
                            world(cameraPos, renderPose, sketch,
                                    sketch.variable(v[2]), sketch.variable(v[3])), entityColor);
                    lines++;
                }
                case POINT -> {
                    // 点画成一个小十字（单纯一个点是看不见的）
                    final Vec3 c = world(cameraPos, renderPose, sketch,
                            sketch.variable(v[0]), sketch.variable(v[1]));
                    // ★ 画大一点 ✓（原来是 0.06 ⇒ 12cm 的小十字 ✓ 太难点中 ✓ 用户反馈 ✓）
                    final double t = 0.10;
                    line(vc, pose, c.add(-t, 0, 0), c.add(t, 0, 0), entityColor);
                    line(vc, pose, c.add(0, -t, 0), c.add(0, t, 0), entityColor);
                    lines += 2;
                }
                case CIRCLE -> {
                    // ★ 用户要求：**圆心常驻显示** ✓（"圆/圆弧的圆心应当常驻显示，不然不好找" ✓）
                    //   画法与小点一致（小十字 ✓），但用**另一种颜色**避免和真正的点混淆 ✓
                    final Vec3 centerC = world(cameraPos, renderPose, sketch,
                            sketch.variable(v[0]), sketch.variable(v[1]));
                    final double ct = 0.07;
                    line(vc, pose, centerC.add(-ct, 0, 0), centerC.add(ct, 0, 0), CENTER_COLOR);
                    line(vc, pose, centerC.add(0, -ct, 0), centerC.add(0, ct, 0), CENTER_COLOR);
                    lines += 2;
                    lines += drawCircle(vc, pose, cameraPos, renderPose, sketch, v, entityColor);
                }
                case ARC -> {
                    // ★ 圆心常驻显示 ✓（同圆 ✓ 用户要求 ✓）
                    final Vec3 centerA = world(cameraPos, renderPose, sketch,
                            sketch.variable(v[0]), sketch.variable(v[1]));
                    final double at = 0.07;
                    line(vc, pose, centerA.add(-at, 0, 0), centerA.add(at, 0, 0), CENTER_COLOR);
                    line(vc, pose, centerA.add(0, -at, 0), centerA.add(0, at, 0), CENTER_COLOR);
                    lines += 2;
                    // 弧用折线近似。段数按扫角缩放：一小段弧给它 48 段纯属浪费。
                    // ⚠ 扫角**现算**（弧存的是圆心 + 起点 + 终点 ✓ 半径/角度不是变量 ✓）
                    final double sweep = sketch.arcSweep(entity);
                    // ★ 修实测"劣弧仍然没有" ✓✓
                    //   劣弧规则生效后，扫角可能是**负数**（表示顺时针走短的那段 ✓）
                    //   但段数用的是 `ceil(sweep / 2π)` ✗ ⇒ 负数被 `max(4, …)` 兜底成 **4 段** ✗
                    //   ⇒ 画出来的"劣弧"是一条只有 4 段的粗折线，看着不像弧 ✓
                    //   ⇒ 段数按**绝对值**算 ✓
                    final int segments = Math.max(4,
                            (int) Math.ceil(CIRCLE_SEGMENTS * Math.abs(sweep) / (2.0 * Math.PI)));
                    Vec3 prev = null;
                    for (int i = 0; i <= segments; i++) {
                        final double[] p = sketch.arcPoint(entity, (double) i / segments);
                        if (p == null) {
                            break;
                        }
                        final Vec3 cur = world(cameraPos, renderPose, sketch, p[0], p[1]);
                        if (prev != null) {
                            line(vc, pose, prev, cur, entityColor);
                            lines++;
                        }
                        prev = cur;
                    }
                }
                case BEZIER -> {
                    // ★ 需求 ⑤：三次贝塞尔 ⇒ 采样成折线画 ✓（48 段足够平滑 ✓）
                    final int bezSegs = 48;
                    Vec3 prevB = null;
                    for (int i = 0; i <= bezSegs; i++) {
                        final double[] bp = sketch.bezierPoint(entity, (double) i / bezSegs);
                        if (bp == null) {
                            break;
                        }
                        final Vec3 curB = world(cameraPos, renderPose, sketch, bp[0], bp[1]);
                        if (prevB != null) {
                            line(vc, pose, prevB, curB, entityColor);
                            lines++;
                        }
                        prevB = curB;
                    }
                }
                default -> {
                    // 将来加了新图元种类，这里不画但也不崩
                }
            }
            // 悬停的图元额外标出关键点。已选中的标识由橙色表达，不必再叠一层。
            if (entity.equals(hovered) && !(session != null && session.isSelected(entity))) {
                lines += drawKeyMarkers(vc, pose, cameraPos, renderPose, sketch, entity);
            }
        }
        // ★ 需求 ①：辅助线在**世界渲染**里画成虚线 ✓
        //   以前这里是 `continue` 跳过、全靠 HUD 那层屏幕空间虚线 ✗
        //   ⇒ 那层没画（投影失败/被裁/被别的层盖住）时，辅助线就"看不见"或"看着是实线" ✓
        //   ⇒ 现在改在世界空间画虚线段 ✓ 与实体线同一套投影 ⇒ 必然对齐 ✓
        lines += drawConstruction(vc, pose, cameraPos, renderPose, sketch);
        return lines;
    }

    /**
     * 画所有**辅助线**（构造几何）为虚线 ✓（需求 ①）。
     *
     * <p>直线按平面长度切段（0.10 格实 + 0.07 格空）；圆/弧**隔段画**（视觉上就是虚线环）✓
     * 只用现有 `line(...)` ⇒ 不引入新的渲染类型，也不影响实体线的绘制路径 ✓
     */
    private static int drawConstruction(final VertexConsumer vc, final PoseStack.Pose pose,
                                        final Vec3 cameraPos, final Pose3dc renderPose,
                                        final Sketch sketch) {
        int drawn = 0;
        for (final Sketch.Entity entity : sketch.entities()) {
            if (!sketch.isConstruction(entity)) {
                continue;
            }
            final int[] v = entity.vars();
            switch (entity.kind()) {
                case LINE -> {
                    final double x0 = sketch.variable(v[0]);
                    final double y0 = sketch.variable(v[1]);
                    final double x1 = sketch.variable(v[2]);
                    final double y1 = sketch.variable(v[3]);
                    final double dx = x1 - x0;
                    final double dy = y1 - y0;
                    final double len = Math.hypot(dx, dy);
                    if (len < 1.0e-9) {
                        continue;
                    }
                    final double ux = dx / len;
                    final double uy = dy / len;
                    // ★ 修实测"辅助线还是实线" ✗ —— 原来虚线段只有 0.10 格（≈几像素），
                    //   空隙 0.07 格 ⇒ 肉眼基本连成一条实线 ✓
                    //   ⇒ 改成与推理虚线同一档（0.35 实 / 0.25 空）✓
                    final double dash = 0.35;
                    final double gap = 0.25;
                    double at = 0.0;
                    while (at < len) {
                        final double end = Math.min(len, at + dash);
                        line(vc, pose,
                                world(cameraPos, renderPose, sketch,
                                        x0 + ux * at, y0 + uy * at),
                                world(cameraPos, renderPose, sketch,
                                        x0 + ux * end, y0 + uy * end),
                                CONSTRUCTION_COLOR);
                        drawn++;
                        at = end + gap;
                    }
                }
                case CIRCLE -> {
                    final double cx = sketch.variable(v[0]);
                    final double cy = sketch.variable(v[1]);
                    final double r = Math.abs(sketch.variable(v[2]));
                    if (r < 1.0e-9) {
                        continue;
                    }
                    for (int i = 0; i < CIRCLE_SEGMENTS; i += 2) {
                        final double a0 = 2.0 * Math.PI * i / CIRCLE_SEGMENTS;
                        final double a1 = 2.0 * Math.PI * (i + 1) / CIRCLE_SEGMENTS;
                        line(vc, pose,
                                world(cameraPos, renderPose, sketch,
                                        cx + r * Math.cos(a0), cy + r * Math.sin(a0)),
                                world(cameraPos, renderPose, sketch,
                                        cx + r * Math.cos(a1), cy + r * Math.sin(a1)),
                                CONSTRUCTION_COLOR);
                        drawn++;
                    }
                }
                case ARC -> {
                    final double sweep = sketch.arcSweep(entity);
                    final int segs = Math.max(4,
                            (int) Math.ceil(CIRCLE_SEGMENTS * Math.abs(sweep) / (2.0 * Math.PI)));
                    for (int i = 0; i < segs; i += 2) {
                        final double[] p0 = sketch.arcPoint(entity, (double) i / segs);
                        final double[] p1 = sketch.arcPoint(entity, (double) (i + 1) / segs);
                        if (p0 == null || p1 == null) {
                            continue;
                        }
                        line(vc, pose,
                                world(cameraPos, renderPose, sketch, p0[0], p0[1]),
                                world(cameraPos, renderPose, sketch, p1[0], p1[1]),
                                CONSTRUCTION_COLOR);
                        drawn++;
                    }
                }
                default -> {
                    // 辅助的"点"不画虚线（点没有线可画）✓ 悬停时仍有关键点标识 ✓
                }
            }
        }
        return drawn;
    }

    /**
     * 给悬停中的图元标出**关键点**（直线：两端点 + 中点；圆/点：圆心或本身）。
     *
     * <p>标识画成**平面内的小方框**而不是世界坐标的十字：草图平面可以朝向任意方向，
     * 用世界轴画出来的"十字"只有正对着看才像十字，斜着看是一条线。
     */
    private static int drawKeyMarkers(final VertexConsumer vc, final PoseStack.Pose pose,
                                      final Vec3 cameraPos, final Pose3dc renderPose,
                                      final Sketch sketch, final Sketch.Entity entity) {
        final int[] v = entity.vars();
        int lines = 0;
        switch (entity.kind()) {
            case LINE -> {
                lines += marker(vc, pose, cameraPos, renderPose, sketch,
                        sketch.variable(v[0]), sketch.variable(v[1]));
                lines += marker(vc, pose, cameraPos, renderPose, sketch,
                        sketch.variable(v[2]), sketch.variable(v[3]));
                lines += marker(vc, pose, cameraPos, renderPose, sketch,
                        (sketch.variable(v[0]) + sketch.variable(v[2])) / 2.0,
                        (sketch.variable(v[1]) + sketch.variable(v[3])) / 2.0);
            }
            case CIRCLE, POINT -> lines += marker(vc, pose, cameraPos, renderPose, sketch,
                    sketch.variable(v[0]), sketch.variable(v[1]));
            default -> {
            }
        }
        return lines;
    }

    /** 平面内一个边长为 {@code 2*MARKER_HALF} 的方框。返回画出的线段数（4）。 */
    private static int marker(final VertexConsumer vc, final PoseStack.Pose pose,
                             final Vec3 cameraPos, final Pose3dc renderPose,
                             final Sketch sketch, final double u, final double v) {
        final double[][] corners = {
                {u - MARKER_HALF, v - MARKER_HALF},
                {u + MARKER_HALF, v - MARKER_HALF},
                {u + MARKER_HALF, v + MARKER_HALF},
                {u - MARKER_HALF, v + MARKER_HALF},
        };
        for (int i = 0; i < 4; i++) {
            line(vc, pose,
                    world(cameraPos, renderPose, sketch, corners[i][0], corners[i][1]),
                    world(cameraPos, renderPose, sketch, corners[(i + 1) % 4][0],
                            corners[(i + 1) % 4][1]),
                    MARKER_COLOR);
        }
        return 4;
    }

    /** 圆用折线近似。 */
    private static int drawCircle(final VertexConsumer vc, final PoseStack.Pose pose,
                                  final Vec3 cameraPos, final Pose3dc renderPose,
                                  final Sketch sketch, final int[] v, final float[] color) {
        final double cx = sketch.variable(v[0]);
        final double cy = sketch.variable(v[1]);
        final double r = Math.abs(sketch.variable(v[2]));
        if (r < 1.0e-6) {
            return 0;
        }
        Vec3 prev = null;
        Vec3 first = null;
        for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
            final double a = 2.0 * Math.PI * i / CIRCLE_SEGMENTS;
            final Vec3 p = world(cameraPos, renderPose, sketch,
                    cx + r * Math.cos(a), cy + r * Math.sin(a));
            if (prev != null) {
                line(vc, pose, prev, p, color);
            } else {
                first = p;
            }
            prev = p;
        }
        // 闭口（最后一个点理论上等于第一个，但浮点误差会留一条缝）
        if (first != null && prev != null) {
            line(vc, pose, prev, first, color);
        }
        return CIRCLE_SEGMENTS + 1;
    }

    /**
     * 画当前工具的**形状预览**（橡皮筋）。
     *
     * <p>⚠ 预览必须**长得像将要生成的东西**：矩形预览就该是个矩形、圆预览就该是个圆。
     * 原来一律画一条直线（从上一个点到鼠标），玩家无法预判自己会画出什么 ——
     * 实测反馈就是「矩形和圆形的预览不能用一条直线」。
     *
     * <p>没有待画图元时什么都不画：橡皮筋的语义是"这一个图元还差一个点"。
     */
    private static void drawPreview(final VertexConsumer vc, final PoseStack.Pose pose,
                                    final Vec3 cameraPos, final Pose3dc renderPose,
                                    final Sketch sketch) {
        if (previewPoint == null) {
            return;
        }
        final var session = SketchClient.mode().session();
        final var pending = session.pendingPoints();
        if (pending.isEmpty()) {
            return;
        }
        final double[] a = pending.get(pending.size() - 1);
        final double bu = previewPoint[0];
        final double bv = previewPoint[1];
        switch (session.tool()) {
            case RECTANGLE -> {
                // 与按下时同一套取角规则（min/max），否则预览和实际画出来的会不一样
                final double u0 = Math.min(a[0], bu);
                final double u1 = Math.max(a[0], bu);
                final double v0 = Math.min(a[1], bv);
                final double v1 = Math.max(a[1], bv);
                previewLine(vc, pose, cameraPos, renderPose, sketch, u0, v0, u1, v0);
                previewLine(vc, pose, cameraPos, renderPose, sketch, u1, v0, u1, v1);
                previewLine(vc, pose, cameraPos, renderPose, sketch, u1, v1, u0, v1);
                previewLine(vc, pose, cameraPos, renderPose, sketch, u0, v1, u0, v0);
            }
            case CIRCLE -> {
                // 圆心 = 上一个点，半径 = 到鼠标的距离（与 addCircleFromTwoPoints 一致）
                final double r = Math.hypot(bu - a[0], bv - a[1]);
                Vec3 prev = null;
                for (int i = 0; i <= CIRCLE_SEGMENTS; i++) {
                    final double ang = 2.0 * Math.PI * i / CIRCLE_SEGMENTS;
                    final Vec3 p = world(cameraPos, renderPose, sketch,
                            a[0] + r * Math.cos(ang), a[1] + r * Math.sin(ang));
                    if (prev != null) {
                        line(vc, pose, prev, p, PREVIEW_COLOR);
                    }
                    prev = p;
                }
            }
            case ARC -> {
                // 圆心 → 起点 → 终点。前两次点击之后才谈得上弧。
                final double[] centre = pending.get(0);
                if (pending.size() < 2) {
                    // 只有圆心：光标在定**起点**（也就定了半径）→ 画一条半径线
                    previewLine(vc, pose, cameraPos, renderPose, sketch,
                            centre[0], centre[1], bu, bv);
                } else {
                    final double[] start = pending.get(1);
                    final double r = Math.hypot(start[0] - centre[0], start[1] - centre[1]);
                    if (r > 1.0e-9) {
                        final double a0 = Math.atan2(start[1] - centre[1], start[0] - centre[0]);
                        final double a1 = Math.atan2(bv - centre[1], bu - centre[0]);
                        // ⚠ 必须用与 Sketch.addArc **同一个** sweep 规则（永远从 a0 逆时针扫到 a1）。
                        // 各写一份的话，预览画的是劣弧、松手出来的是优弧 —— 又是"预览骗人"。
                        final double sweep = Sketch.sweep(a0, a1);
                        final int segs = Math.max(4,
                                (int) Math.ceil(CIRCLE_SEGMENTS * sweep / (2.0 * Math.PI)));
                        Vec3 prev = null;
                        for (int i = 0; i <= segs; i++) {
                            final double ang = a0 + sweep * i / segs;
                            final Vec3 p = world(cameraPos, renderPose, sketch,
                                    centre[0] + r * Math.cos(ang), centre[1] + r * Math.sin(ang));
                            if (prev != null) {
                                line(vc, pose, prev, p, PREVIEW_COLOR);
                            }
                            prev = p;
                        }
                        // 再把"圆心 → 光标"的半径方向画出来：终点只用来定**角度**、不看距离，
                        // 不画这条线玩家会以为终点的距离也参与定义。
                        previewLine(vc, pose, cameraPos, renderPose, sketch,
                                centre[0], centre[1], bu, bv);
                    }
                }
            }
            case BEZIER -> {
                // ★ 需求 ⑤：四次点击 P0 → C1 → P3 → C2 的实时预览 ✓
                //   落下第 3 点（P3）之后就要能看到**曲线** ✓（用户明确要求 ✓）
                final double[] p0 = pending.get(0);
                if (pending.size() == 1) {
                    // 只有 P0：光标在定手柄1的终点 C1
                    previewLine(vc, pose, cameraPos, renderPose, sketch, p0[0], p0[1], bu, bv);
                } else {
                    final double[] c1 = pending.get(1);
                    previewLine(vc, pose, cameraPos, renderPose, sketch, p0[0], p0[1], c1[0], c1[1]);
                    if (pending.size() == 2) {
                        // 已有 P0、C1：光标在定曲线的起点 P3 ⇒ 先画一条引导线
                        previewLine(vc, pose, cameraPos, renderPose, sketch, c1[0], c1[1], bu, bv);
                    } else {
                        // 已有 P0、C1、P3：光标在定 C2 ⇒ **画曲线预览** ✓ + 手柄2预览 ✓
                        final double[] p3 = pending.get(2);
                        previewLine(vc, pose, cameraPos, renderPose, sketch, p3[0], p3[1], bu, bv);
                        Vec3 prev = null;
                        final int segs = 40;
                        for (int i = 0; i <= segs; i++) {
                            final double t = (double) i / segs;
                            final double mt = 1.0 - t;
                            final double f0 = mt * mt * mt;
                            final double f1 = 3.0 * mt * mt * t;
                            final double f2 = 3.0 * mt * t * t;
                            final double f3 = t * t * t;
                            final double uu = f0 * p0[0] + f1 * c1[0] + f2 * bu + f3 * p3[0];
                            final double vv = f0 * p0[1] + f1 * c1[1] + f2 * bv + f3 * p3[1];
                            final Vec3 p = world(cameraPos, renderPose, sketch, uu, vv);
                            if (prev != null) {
                                line(vc, pose, prev, p, PREVIEW_COLOR);
                            }
                            prev = p;
                        }
                    }
                }
            }
            default -> {
                // LINE / SELECT：先画**推理虚线**，再画橡皮筋。
                // SolidWorks 的推理线：吸附到水平/竖直时，沿该方向画一条穿过落点的长虚线，
                // 让玩家看出"我正在被吸到这个方向上"。只有一条截短的橡皮筋是看不出来的。
                drawInferenceLine(vc, pose, cameraPos, renderPose, sketch, a[0], a[1], bu, bv);
                previewLine(vc, pose, cameraPos, renderPose, sketch, a[0], a[1], bu, bv);
            }
        }
    }

    private static void previewLine(final VertexConsumer vc, final PoseStack.Pose pose,
                                    final Vec3 cameraPos, final Pose3dc renderPose,
                                    final Sketch sketch, final double u0, final double v0,
                                    final double u1, final double v1) {
        line(vc, pose, world(cameraPos, renderPose, sketch, u0, v0),
                world(cameraPos, renderPose, sketch, u1, v1), PREVIEW_COLOR);
    }

    /** 推理线延伸的半长（平面内格数）。够长才有"延伸到远处"的感觉。 */
    private static final double INFERENCE_HALF = 24.0;
    /** 虚线每段实线的长度与间隔（平面内格数）。 */
    private static final double DASH_ON = 0.35;
    private static final double DASH_OFF = 0.25;

    /**
     * 画推理虚线：当前正在推理水平/竖直时，沿那个方向画一条穿过落点的**长虚线**。
     *
     * <p>方向来自 {@link SketchMode#previewOrtho()} —— 与真正落笔用的是**同一次**推理判断，
     * 所以虚线指的方向和最后画出来的线一定一致（不会出现"虚线说水平、画出来是斜的"）。
     */
    private static void drawInferenceLine(final VertexConsumer vc, final PoseStack.Pose pose,
                                          final Vec3 cameraPos, final Pose3dc renderPose,
                                          final Sketch sketch, final double au, final double av,
                                          final double bu, final double bv) {
        final double ortho = SketchClient.mode().previewOrtho();
        if (ortho != 1.0 && ortho != 2.0) {
            return; // 没有推理方向（斜线）
        }
        // 水平推理 → 沿 u 延伸（v 锁定在落点的 v）；竖直推理 → 沿 v 延伸
        final double du = ortho == 1.0 ? 1.0 : 0.0;
        final double dv = ortho == 2.0 ? 1.0 : 0.0;
        final double cu = bu;
        final double cv = bv;
        // 用等间距短段画虚线，不引入新的 RenderType（线宽/深度设置都沿用 SKETCH_LINES）
        final double step = DASH_ON + DASH_OFF;
        final int dashes = (int) (INFERENCE_HALF / step);
        for (int i = -dashes; i < dashes; i++) {
            final double s0 = i * step;
            final double s1 = s0 + DASH_ON;
            line(vc, pose,
                    world(cameraPos, renderPose, sketch, cu + du * s0, cv + dv * s0),
                    world(cameraPos, renderPose, sketch, cu + du * s1, cv + dv * s1),
                    INFERENCE_COLOR);
        }
    }

    // ==================================================================
    // 体素碰撞体实验的**可见参照**
    // ==================================================================

    /** 要画实验描边的结构；null = 不画。 */
    @Nullable
    private static UUID voxelDebugBody;

    /** 让实验描边显示在这个结构上（用户定的规矩：**测试物体必须有描边**）。 */
    public static void showVoxelDebugOutline(@Nullable final UUID body) {
        voxelDebugBody = body;
    }

    /** 实验描边的颜色（青绿，与紫色草图线、绿色碰撞盒都区分得开）。 */
    private static final float[] VOXEL_DEBUG_COLOR = {0.20f, 0.95f, 0.85f};

    /**
     * 把「体素碰撞体实验」的**隐形装置**画成线框，好让玩家知道它在哪、能对着它撞。
     *
     * <p>⚠⚠ **位置必须和服务端用同一套计算**，否则"描边在这儿、碰撞在那儿"，比不画还糟。
     * 服务端（{@code VoxelColliderTestService}）的取法是：
     * plot 包围盒的**中心 x/z**、**顶面 maxY + 2**。这里一个字都不许改。
     *
     * <p>形状也照抄实验：原点处一个**单位立方体**，x+2 处一个 **16 级台阶**（每级 1/16 高）。
     */
    private static void drawVoxelDebugOutline(final Map<UUID, ClientSubLevel> byId,
                                              final PoseStack.Pose pose, final Vec3 cameraPos) {
        final UUID body = voxelDebugBody;
        if (body == null) {
            return;
        }
        final ClientSubLevel sub = byId.get(body);
        if (sub == null) {
            return;
        }
        final dev.ryanhcode.sable.companion.math.Pose3dc renderPose = sub.renderPose();
        // ⚠⚠ 与服务端 VoxelColliderTestService **完全一致**：
        //   **不能用 `getPlot().getBoundingBox()`** —— 那是 plot 分配区域，不是结构实际范围；
        //   用它算出来的位置与结构无关（描边会落在地上）。
        //   结构真实范围是 `boundingBox()`，而它是**世界系**，必须逆变换回 plot 局部。
        final var wb = sub.boundingBox();
        final Vector3d a3 = renderPose.transformPositionInverse(
                new Vector3d(wb.minX(), wb.minY(), wb.minZ()), new Vector3d());
        final Vector3d b3 = renderPose.transformPositionInverse(
                new Vector3d(wb.maxX(), wb.maxY(), wb.maxZ()), new Vector3d());
        final Vector3d lo = a3.min(b3, new Vector3d());
        final Vector3d hi = a3.max(b3, new Vector3d());
        final double cx = (lo.x + hi.x) * 0.5;
        final double cz = (lo.z + hi.z) * 0.5;
        final double topY = hi.y + 2.0;
        // 独立缓冲源：从同一个 BufferSource 取第二种 RenderType 会让前一个 builder 结束
        // （我崩过：BufferBuilder.ensureBuilding 抛 "Not building!"）
        final MultiBufferSource.BufferSource dbg =
                MultiBufferSource.immediate(new com.mojang.blaze3d.vertex.ByteBufferBuilder(1 << 16));
        final VertexConsumer vc = dbg.getBuffer(ModRenderTypes.LINES_NO_DEPTH);
        // ① 原点处的单位立方体（对照组）
        boxOutline(vc, pose, cameraPos, renderPose, cx, topY, cz, 1.0, 1.0, 1.0);
        // ② x+2 处的 16 级台阶：每级一个 1/16 高的薄盒，沿 x 递升
        final int steps = 16;
        for (int i = 0; i < steps; i++) {
            final double w = 1.0 / steps;
            final double h = (double) (i + 1) / steps;
            boxOutline(vc, pose, cameraPos, renderPose,
                    cx + 2.0 + (i + 0.5) * w, topY + h * 0.5, cz + 0.5,
                    w, h, 1.0);
        }
        // ③ **凸包对照组**（x+3 处，半径 1.5、高 3 的正棱柱）
        //
        // ⚠ 服务端（VoxelColliderTestService）把凸包放在 `poseX + 3.0`，而 poseX 是
        //   "结构中心相对父体质心"的 x —— 换回 plot 局部就是 `useX + 3.0`，而客户端的
        //   `cx` 就等于服务端的 `useX`（同一套 boundingBox + renderPose 计算）。
        //   所以这里画在 `cx + 3.0` 就和服务端请求的位置一致。
        //
        // ⚠⚠ **但"请求的位置"和"碰撞体实际出现的位置"是否一致，正是这次实验要验的东西** ——
        //   因为挂到刚体上的碰撞体坐标是**刚体局部**系，而刚体局部原点是质心还是结构原点
        //   我还没有实证。如果你看到描边在这里、碰撞却在别处（或根本碰不到），那说明
        //   需要减掉质心偏移 —— 这就是第一次调试要拿到的结论。
        boxOutline(vc, pose, cameraPos, renderPose, cx + 3.0, topY, cz, 3.0, 3.0, 3.0);
        dbg.endBatch();
    }

    /** 画一个 plot 局部的长方体线框（参数是**全尺寸**）。 */
    private static void boxOutline(final VertexConsumer vc, final PoseStack.Pose pose,
                                   final Vec3 cameraPos,
                                   final dev.ryanhcode.sable.companion.math.Pose3dc renderPose,
                                   final double cx, final double cy, final double cz,
                                   final double sx, final double sy, final double sz) {
        final Vec3[] c = new Vec3[8];
        int n = 0;
        // ⚠ 枚举顺序必须与下面 edges 的下标约定一致：
        //   位0 = x、位1 = y、位2 = z（-1 在前）
        for (int i = -1; i <= 1; i += 2) {
            for (int j = -1; j <= 1; j += 2) {
                for (int k = -1; k <= 1; k += 2) {
                    c[n++] = world(cameraPos, renderPose, new Vector3d(
                            cx + i * sx * 0.5, cy + j * sy * 0.5, cz + k * sz * 0.5));
                }
            }
        }
        final int[][] edges = {
                {0, 1}, {2, 3}, {4, 5}, {6, 7},   // 沿 z 的四条
                {0, 2}, {1, 3}, {4, 6}, {5, 7},   // 沿 y 的四条
                {0, 4}, {1, 5}, {2, 6}, {3, 7}};  // 沿 x 的四条
        for (final int[] e : edges) {
            line(vc, pose, c[e[0]], c[e[1]], VOXEL_DEBUG_COLOR);
        }
    }

    private static void line(final VertexConsumer vc, final PoseStack.Pose pose,
                             final Vec3 a, final Vec3 b, final float[] color) {
        vc.addVertex(pose, (float) a.x, (float) a.y, (float) a.z)
                .setColor(color[0], color[1], color[2], 1.0f)
                .setNormal(pose, 0.0f, 1.0f, 0.0f);
        vc.addVertex(pose, (float) b.x, (float) b.y, (float) b.z)
                .setColor(color[0], color[1], color[2], 1.0f)
                .setNormal(pose, 0.0f, 1.0f, 0.0f);
    }

    // ================= 草图背景网格（需求 ②） =================

    /** 网格半宽（格）：±40 ⇒ 80×80 格，足够罩住一个面上的草图 ✓ */
    private static final int GRID_HALF = 40;
    /** 网格线半宽（格）：0.02 格 ≈ 2cm，细且稳定（不依赖 GL 线宽）✓ */
    private static final double GRID_LINE_HALF = 0.02;
    /** 淡灰 #C8C8C8 ✓ */
    private static final float GRID_GREY = 0.784f;

    /**
     * 画**草图常驻背景网格** ✓（需求 ②）。
     *
     * <p>规格（用户指定）：间距 **1 格**；网格线淡灰 70% 不透明、细线；
     * 背景同一淡灰 20% 不透明。范围 ±40 格，以草图原点 (0,0) 为中心 ✓
     * <p>用 **QUADS 细长条**画线（与 BOXES 同思路）⇒ 线宽跨平台一致 ✓
     */
    private static void drawSketchGrid(final VertexConsumer vc, final PoseStack.Pose pose,
                                       final Vec3 cameraPos, final Pose3dc renderPose,
                                       final Sketch sketch) {
        final double h = GRID_HALF;
        // ① 背景填充（20%）
        quad(vc, pose,
                world(cameraPos, renderPose, sketch, -h, -h),
                world(cameraPos, renderPose, sketch, h, -h),
                world(cameraPos, renderPose, sketch, h, h),
                world(cameraPos, renderPose, sketch, -h, h),
                GRID_GREY, 0.20f);
        // ② 网格线（70%）：竖线（沿 V 走）+ 横线（沿 U 走）
        final double w = GRID_LINE_HALF;
        for (int i = -GRID_HALF; i <= GRID_HALF; i++) {
            final double c = i;
            // 竖线：u = c
            quad(vc, pose,
                    world(cameraPos, renderPose, sketch, c - w, -h),
                    world(cameraPos, renderPose, sketch, c + w, -h),
                    world(cameraPos, renderPose, sketch, c + w, h),
                    world(cameraPos, renderPose, sketch, c - w, h),
                    GRID_GREY, 0.70f);
            // 横线：v = c
            quad(vc, pose,
                    world(cameraPos, renderPose, sketch, -h, c - w),
                    world(cameraPos, renderPose, sketch, h, c - w),
                    world(cameraPos, renderPose, sketch, h, c + w),
                    world(cameraPos, renderPose, sketch, -h, c + w),
                    GRID_GREY, 0.70f);
        }
    }

    /** 一个带 alpha 的四边形 ✓（顶点格式 POSITION_COLOR ✓ 给 {@code SKETCH_GRID} 用）。 */
    private static void quad(final VertexConsumer vc, final PoseStack.Pose pose,
                            final Vec3 a, final Vec3 b, final Vec3 c, final Vec3 d,
                            final float grey, final float alpha) {
        putVertex(vc, pose, a, grey, alpha);
        putVertex(vc, pose, b, grey, alpha);
        putVertex(vc, pose, c, grey, alpha);
        putVertex(vc, pose, d, grey, alpha);
    }

    private static void putVertex(final VertexConsumer vc, final PoseStack.Pose pose,
                                  final Vec3 p, final float grey, final float alpha) {
        vc.addVertex(pose, (float) p.x, (float) p.y, (float) p.z)
                .setColor(grey, grey, grey, alpha);
    }
}
