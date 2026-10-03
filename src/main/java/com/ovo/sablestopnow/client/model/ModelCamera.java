package com.ovo.sablestopnow.client.model;

import com.ovo.sablestopnow.client.MateTargeting;
import com.ovo.sablestopnow.mate.MateFrames;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector4f;

import java.util.UUID;

/**
 * 模型模式的轨道相机：相机绕一个目标点转，几何不动。
 *
 * <p>操作与 SolidWorks 对齐（见 {@link ModelInput}）：中键拖动旋转、Ctrl+中键平移、
 * Shift+中键 / 滚轮缩放（以光标附近为中心）、空格弹视图定向。
 *
 * <p>相机朝角用的是 Minecraft 自己的 yaw/pitch 约定（yaw=0 朝 +Z，pitch 向下为正），
 * 因为这个值最终要交给 {@code Camera.setRotation}——自己发明一套约定只会多一次换算和一堆符号错误。
 * 位置由「目标点 - 视线 * 距离」反推，于是「缩放 = 改距离」，透视关系天然正确。
 */
public final class ModelCamera {

    /** 视图定向预设（SW 的前/后/左/右/上/下/等轴测）。 */
    public enum Preset {
        FRONT(0.0f, 0.0f),
        BACK(180.0f, 0.0f),
        LEFT(90.0f, 0.0f),
        RIGHT(-90.0f, 0.0f),
        TOP(0.0f, -89.0f),
        BOTTOM(0.0f, 89.0f),
        ISO(-45.0f, 30.0f);

        public final float yaw;
        public final float pitch;

        Preset(final float yaw, final float pitch) {
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private static final double MIN_DISTANCE = 0.8;
    private static final double MAX_DISTANCE = 128.0;
    private static final double ORBIT_SPEED = 0.35;
    private static final double PAN_SPEED = 0.0028;
    private static final double SMOOTH = 14.0;

    private static final Vector3d target = new Vector3d();
    private static final Vector3d targetGoal = new Vector3d();
    private static double distance = 14.0;
    private static double distanceGoal = 14.0;
    private static float yaw;
    private static float yawGoal;
    private static float pitch = 28.0f;
    private static float pitchGoal = 28.0f;
    private static boolean initialized;

    private ModelCamera() {
    }

    // ============ 初始化 / 目标 ============

    public static boolean initialized() {
        return initialized;
    }

    /** 进入模型模式时把相机摆到玩家身后（保留玩家当前视角方向，手感连续）。 */
    public static void reset(final Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        final Vec3 eye = mc.player.getEyePosition(1.0f);
        final Vec3 look = mc.player.getLookAngle();
        target.set(eye.x + look.x * 6.0, eye.y + look.y * 6.0, eye.z + look.z * 6.0);
        targetGoal.set(target);
        yaw = mc.player.getYRot();
        yawGoal = yaw;
        pitch = mc.player.getXRot();
        pitchGoal = pitch;
        distance = 14.0;
        distanceGoal = 14.0;
        initialized = true;
    }

    /** 把相机目标移到某个模型上，<b>并按包围盒重新定距</b>（「适配」用）。 */
    public static void focus(@Nullable final UUID bodyId) {
        if (bodyId == null) {
            return;
        }
        final SubLevel sub = MateTargeting.byId(bodyId);
        if (sub == null) {
            return;
        }
        final Vector3d center = MateFrames.worldPoint(MateTargeting.poseOf(sub),
                MateFrames.resolveLocal(sub, com.ovo.sablestopnow.mate.MateRef.center(bodyId)));
        targetGoal.set(center);
        final var bounds = sub.getPlot().getBoundingBox();
        final double radius = Math.max(2.0, 0.5 * Math.max(
                Math.max(bounds.maxX() - bounds.minX(), bounds.maxY() - bounds.minY()),
                bounds.maxZ() - bounds.minZ()));
        distanceGoal = Mth.clamp(radius * 3.0, 6.0, 64.0);
    }

    /**
     * 把某个模型摆到视野正中，<b>不动距离</b>（「移到视觉中心」用）。
     *
     * <p>与 {@link #focus} 的区别正是玩家要的那一条：选中模型不该改动缩放级别，
     * 只该把视线对准它。而且这里是<b>立即</b>到位 —— 相机移动没有惯性。
     *
     * @return 是否找到了这个模型
     */
    public static boolean centerOn(@Nullable final UUID bodyId) {
        if (bodyId == null) {
            return false;
        }
        final SubLevel sub = MateTargeting.byId(bodyId);
        if (sub == null) {
            return false;
        }
        final Vector3d center = MateFrames.worldPoint(MateTargeting.poseOf(sub),
                MateFrames.resolveLocal(sub, com.ovo.sablestopnow.mate.MateRef.center(bodyId)));
        target.set(center);
        targetGoal.set(center);
        return true;
    }

    /**
     * 立即平移相机（世界系位移）。
     *
     * <p>目标点与目标值<b>一起</b>动，所以不会留下缓动尾巴 —— WASD 要的是「按住就走、松手就停」，
     * 而不是松手之后再滑一段。这一点与中键旋转/滚轮缩放不同：那两个仍然保留缓动，
     * 因为它们更接近「连续调节」而不是「直接位移」。
     */
    public static void offsetTargetImmediate(final Vec3 delta) {
        target.add(delta.x, delta.y, delta.z);
        targetGoal.add(delta.x, delta.y, delta.z);
    }

    /** 相机目标跟随某个模型（拖动时用）。 */
    public static void followTarget(final Vector3d worldPoint) {
        targetGoal.set(worldPoint);
    }

    public static Vector3d target() {
        return new Vector3d(target);
    }

    public static void setTargetImmediate(final Vector3d point) {
        target.set(point);
        targetGoal.set(point);
    }

    // ============ 每帧 ============

    /** 上一次推进的时间戳（同一帧里 {@code Camera.setup} 可能被调用多次）。 */
    private static long lastTickNanos;

    /**
     * 每帧推进一次（由 {@code Camera.setup} 的 HEAD 调用）。
     *
     * <h2>为什么挪到 Camera.setup 里</h2>
     * 以前在 {@code RenderLevelStageEvent.AFTER_BLOCK_ENTITIES} 里推进，那是<b>相机已经摆好之后</b>——
     * 于是本帧画面用的是上一帧的相机状态，而屏幕投影用的是本帧的状态，两者差一帧。
     * 相机静止时看不出来，一转动就是几十像素的错位（用户实测「标记有接近 1/4 个方块的偏移」，
     * 日志里 {@code analyticDelta} 在 30~197 像素之间就是这个差）。
     *
     * <p>挪到 {@code Camera.setup} 之后，「画面上用的相机」「投影用的相机」变成同一个状态，
     * 而且 {@link #captureFrame} 抓到的矩阵也来自同一状态 —— 三者在原理上不可能再对不齐。
     *
     * <p>1ms 的间隔保护：正常帧远长于 1ms，而同一帧内重复调用 {@code setup} 会在 1ms 内发生。
     */
    public static void tickOnce(final Minecraft mc) {
        final long now = System.nanoTime();
        if (now - lastTickNanos < 1_000_000L) {
            return;
        }
        lastTickNanos = now;
        tick(mc);
    }

    public static void tick(final Minecraft mc) {
        if (!initialized) {
            reset(mc);
            return;
        }
        final double dt = Math.min(0.1, com.ovo.sablestopnow.client.gui.lib.Ui.frameDelta());
        final double k = 1.0 - Math.exp(-SMOOTH * dt);
        final boolean animate = com.ovo.sablestopnow.client.gui.lib.UiSettings.animations;
        final double kk = animate ? k : 1.0;
        target.lerp(targetGoal, kk);
        distance += (distanceGoal - distance) * kk;
        yaw += Mth.wrapDegrees(yawGoal - yaw) * (float) kk;
        pitch += (pitchGoal - pitch) * (float) kk;
    }

    // ============ 操作 ============

    public static void orbit(final double dx, final double dy) {
        // 方向与 SolidWorks 一致：往右拖 = 模型跟着手往右转 = 相机绕到左边（yaw 增大）。
        // 早先写成 yaw 减小，用户实测反馈「中键旋转反了」。
        yawGoal = (float) (yawGoal + dx * ORBIT_SPEED);
        pitchGoal = Mth.clamp((float) (pitchGoal + dy * ORBIT_SPEED), -89.5f, 89.5f);
    }

    public static void pan(final double dx, final double dy) {
        final Vec3 look = lookVector();
        final Vec3 right = rightVector(look);
        final Vec3 up = upVector(look, right);
        final double scale = PAN_SPEED * Math.max(2.0, distance);
        targetGoal.add(-dx * scale * right.x, -dx * scale * right.y, -dx * scale * right.z);
        targetGoal.add(dy * scale * up.x, dy * scale * up.y, dy * scale * up.z);
    }

    /**
     * 滚轮缩放。
     *
     * <p><b>只改距离，不动目标点。</b>早先按「以光标为中心」把目标点沿射线搬来搬去：
     * 连续滚动时那个位移会不断累积，于是「缩小时突然缩得很远、而且再也拉不回来」
     * （用户实测反馈）。缩放的语义本来就只是「相机离目标多远」，改距离既直观又不会跑飞。
     * 步长也调细了一档（0.12），一格滚轮大约 ±12%。
     */
    public static void zoom(final double delta, final boolean atCursor, final Minecraft mc) {
        final double factor = Math.exp(-delta * 0.12);
        distanceGoal = Mth.clamp(distanceGoal * factor, MIN_DISTANCE, MAX_DISTANCE);
    }

    public static void setPreset(final Preset preset) {
        final Vec3 view = viewVector(preset.yaw, preset.pitch);
        // 预设方向下把相机摆到目标的对侧：yaw/pitch 直接采用预设值即可
        yawGoal = preset.yaw;
        pitchGoal = preset.pitch;
        // 触一下视线，避免编译器把 view 当未使用（同时保留后续可能的方向微调）
        if (view.lengthSqr() < 0.5) {
            yawGoal = 0.0f;
        }
    }

    public static void zoomTo(final double value) {
        distanceGoal = Mth.clamp(value, MIN_DISTANCE, MAX_DISTANCE);
    }

    /** 直接把相机转到给定朝向（「正视此面」用），带缓动。 */
    public static void alignTo(final float yawDeg, final float pitchDeg) {
        yawGoal = yawDeg;
        pitchGoal = Mth.clamp(pitchDeg, -89.5f, 89.5f);
    }

    /** 忘记当前状态：退出模型模式/断线时调用，下次进入重新按玩家视角摆位。 */
    public static void forget() {
        initialized = false;
        frameValid = false;
        useMatrices = false;
        lastTickNanos = 0L;
        frameStamp++;
        verdictStamp = -1L;
    }

    // ============ 查询 ============

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    public static double distance() {
        return distance;
    }

    /** 相机在世界系的位置。 */
    public static Vec3 position() {
        final Vec3 look = lookVector();
        return new Vec3(target.x - look.x * distance, target.y - look.y * distance, target.z - look.z * distance);
    }

    /** 相机视线方向。 */
    public static Vec3 lookVector() {
        return viewVector(yaw, pitch);
    }

    /**
     * 光标射线方向（世界系单位向量）。
     *
     * <p>与 {@link #projectToScreen} <b>互为逆运算</b>：用的是同一对矩阵、同一个逆矩阵。
     * 以前两边各算一套（一个走 look/right/up + options.fov，一个走渲染管线），
     * 只要有一点不一致就会出现「看着点在光标下、却点不中」。
     */
    public static Vec3 rayDirection(final Minecraft mc) {
        final int w = mc.getWindow().getGuiScaledWidth();
        final int h = mc.getWindow().getGuiScaledHeight();
        ensureVerdict(mc);
        if (w > 0 && h > 0 && useMatrices) {
            final double ndcX = 2.0 * com.ovo.sablestopnow.client.gui.lib.Ui.mouseXd() / w - 1.0;
            final double ndcY = 1.0 - 2.0 * com.ovo.sablestopnow.client.gui.lib.Ui.mouseYd() / h;
            final Vector4f near = FRAME_INVERSE.transform(new Vector4f((float) ndcX, (float) ndcY, -1.0f, 1.0f));
            final Vector4f far = FRAME_INVERSE.transform(new Vector4f((float) ndcX, (float) ndcY, 1.0f, 1.0f));
            if (Math.abs(near.w) > 1.0e-6f && Math.abs(far.w) > 1.0e-6f) {
                final double nx = near.x / near.w;
                final double ny = near.y / near.w;
                final double nz = near.z / near.w;
                final double fx = far.x / far.w;
                final double fy = far.y / far.w;
                final double fz = far.z / far.w;
                final double dx = fx - nx;
                final double dy = fy - ny;
                final double dz = fz - nz;
                final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (len > 1.0e-9) {
                    return new Vec3(dx / len, dy / len, dz / len);
                }
            }
        }
        return rayDirectionAnalytic(mc, w, h);
    }

    /** 解析射线（没有抓到矩阵时的兜底）。 */
    private static Vec3 rayDirectionAnalytic(final Minecraft mc, final int w, final int h) {
        final Vec3 look = lookVector();
        final Vec3 right = rightVector(look);
        final Vec3 up = upVector(look, right);
        // 1.21.1 的 GameRenderer 没有公开 getFov，直接用设置里的基础 FOV：
        // 模型模式下玩家被冻结、不会疾跑/开镜，取基础值不会有可见偏差。
        final double fov = mc.options.fov().get();
        final double tanHalf = Math.tan(Math.toRadians(fov) * 0.5);
        if (w <= 0 || h <= 0) {
            return look;
        }
        final double ndcX = 2.0 * com.ovo.sablestopnow.client.gui.lib.Ui.mouseXd() / w - 1.0;
        final double ndcY = 1.0 - 2.0 * com.ovo.sablestopnow.client.gui.lib.Ui.mouseYd() / h;
        final double aspect = (double) w / h;
        final double sx = ndcX * tanHalf * aspect;
        final double sy = ndcY * tanHalf;
        return new Vec3(
                look.x + right.x * sx + up.x * sy,
                look.y + right.y * sx + up.y * sy,
                look.z + right.z * sx + up.z * sy).normalize();
    }

    /**
     * 世界点 → GUI 屏幕坐标（{x, y}），在相机背后/太近时返回 null。
     *
     * <h2>两条路径互相校验</h2>
     * 屏幕投影有两条独立算法（矩阵路径 / 解析路径，见 {@link #ensureVerdict}）。
     * 每帧拿相机正前方的目标点当探针跑一遍，一致就用矩阵路径（与 GPU 同源、更精确），
     * 不一致就用解析路径（与写进相机的位姿同源）。因此「哪一条对」不需要猜。
     *
     * <p>{@link #rayDirection(Minecraft)} 走<b>同一个裁决</b>，所以「画在哪」和「点在哪」
     * 永远是同一套变换 —— 这是「看着在光标下却点不中」的根治办法。
     */
    @Nullable
    public static double[] projectToScreen(final Vec3 world, final Minecraft mc) {
        final int w = mc.getWindow().getGuiScaledWidth();
        final int h = mc.getWindow().getGuiScaledHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        ensureVerdict(mc);
        if (useMatrices) {
            final double[] viaMatrix = projectViaMatrix(world, w, h);
            if (viaMatrix != null) {
                return viaMatrix;
            }
        }
        return projectAnalytic(world, mc, w, h);
    }

    /** 解析投影（矩阵路径不可用或与它分歧时的兜底）。 */
    @Nullable
    private static double[] projectAnalytic(final Vec3 world, final Minecraft mc, final int w, final int h) {
        final Vec3 look = lookVector();
        final Vec3 right = rightVector(look);
        final Vec3 up = upVector(look, right);
        final Vec3 pos = position();
        final Vec3 rel = world.subtract(pos);
        final double z = rel.dot(look);
        if (z <= 0.05) {
            return null;
        }
        final double x = rel.dot(right);
        final double y = rel.dot(up);
        final double fov = mc.options.fov().get();
        final double tanHalf = Math.tan(Math.toRadians(fov) * 0.5);
        final double aspect = (double) w / h;
        final double ndcX = (x / z) / (tanHalf * aspect);
        final double ndcY = (y / z) / tanHalf;
        return new double[] {(ndcX + 1.0) * 0.5 * w, (1.0 - ndcY) * 0.5 * h};
    }

    // ============ 本帧的真实矩阵 ============

    /** 本帧投影矩阵（由 {@link #captureFrame} 写入）。 */
    private static final Matrix4f FRAME_PROJECTION = new Matrix4f();
    /** 本帧视图矩阵（由 {@link #captureFrame} 写入）。 */
    private static final Matrix4f FRAME_VIEW = new Matrix4f();
    /** {@code projection * view}；投影与射线共用，保证互为逆运算。 */
    private static final Matrix4f FRAME_VIEW_PROJECTION = new Matrix4f();
    private static final Matrix4f FRAME_INVERSE = new Matrix4f();
    private static boolean frameValid;

    // ---- 两条投影路径的一致性裁决 ----
    private static long frameStamp;
    private static long verdictStamp = -1L;
    /** 本帧是否采用矩阵路径。见 {@link #ensureVerdict}。 */
    private static boolean useMatrices;

    /** 本帧是否在用「事件给的投影矩阵 + 自己按相机搭的视图矩阵」。 */
    public static boolean usingMatrices() {
        return useMatrices && frameValid;
    }

    /**
     * 两条独立路径的<b>交叉校验</b>，每个新帧第一次用到投影时算一次。
     *
     * <h2>为什么不能只信一条</h2>
     * 屏幕投影有两条互相独立的算法：
     * <ol>
     *   <li><b>矩阵路径</b>：{@code 事件给的投影矩阵 × 自己按 Camera 搭的视图矩阵}。
     *       它与 GPU 用的完全同源，但「事件给的那个矩阵到底是什么」依赖框架的实现细节；
     *       实测日志里 {@code getModelViewMatrix()} 算出来的位置与光标差了 100 像素以上，所以它不可全信。</li>
     *   <li><b>解析路径</b>：{@code 相机位姿 + options.fov()}。它与 {@code ModelCameraMixin}
     *       真正写进相机的值同源，但依赖「实际 FOV == options.fov()」这条假设。</li>
     * </ol>
     * 两条都可能错，但错的原因完全不同。这里拿<b>相机正前方的目标点</b>当探针，
     * 两条路径都算一遍：一致就用矩阵路径（更精确），不一致就用解析路径并把分歧记下来。
     * 于是「哪一条对」不再需要我猜。
     */
    private static void ensureVerdict(final Minecraft mc) {
        if (verdictStamp == frameStamp) {
            return;
        }
        verdictStamp = frameStamp;
        useMatrices = false;
        if (!frameValid) {
            return;
        }
        final int w = mc.getWindow().getGuiScaledWidth();
        final int h = mc.getWindow().getGuiScaledHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        // 探针：相机轨道中心，永远在相机正前方（投影必然有效）
        final Vec3 probe = new Vec3(target.x, target.y, target.z);
        final double[] viaMatrix = projectViaMatrix(probe, w, h);
        final double[] viaAnalytic = projectAnalytic(probe, mc, w, h);
        if (viaMatrix != null && viaAnalytic != null) {
            useMatrices = Math.hypot(viaMatrix[0] - viaAnalytic[0], viaMatrix[1] - viaAnalytic[1]) <= 2.0;
        }
    }

    /** 矩阵路径的投影（不含一致性裁决）。 */
    @Nullable
    private static double[] projectViaMatrix(final Vec3 world, final int w, final int h) {
        if (!frameValid) {
            return null;
        }
        final Vector4f clip = new Vector4f((float) world.x, (float) world.y, (float) world.z, 1.0f);
        FRAME_VIEW_PROJECTION.transform(clip);
        if (clip.w <= 1.0e-4f) {
            return null;
        }
        final double ndcX = clip.x / clip.w;
        final double ndcY = clip.y / clip.w;
        return new double[] {(ndcX + 1.0) * 0.5 * w, (1.0 - ndcY) * 0.5 * h};
    }

    /**
     * 记录本帧真正用过的矩阵（在 {@code RenderLevelStageEvent} 里每帧调一次）。
     *
     * <p>投影矩阵直接取事件给的（那是渲染真正用的那一个）。<b>视图矩阵是自己按相机现搭的</b>，
     * 不用事件里的 {@code getModelViewMatrix()}：实机日志显示用它算出来的像素位置和光标差
     * 100 像素以上（`stale` 一直是几十到一百多），说明那一个不是我以为的「世界→相机」矩阵。
     * 自己按 {@code Camera} 搭只有一种写法，而它正是 {@code LevelRenderer.prepareCullFrustum}
     * 里的那一行，不会再有歧义。
     *
     * <p>必须<b>拷贝</b>投影矩阵：事件给的是渲染管线里的实例，下一帧会被就地改掉。
     */
    public static void captureFrame(final Matrix4f projection, final Camera camera) {
        if (projection == null || camera == null) {
            frameValid = false;
            return;
        }
        FRAME_PROJECTION.set(projection);
        final Vec3 camPos = camera.getPosition();
        // world → camera：先平移 -camPos，再乘相机朝向的共轭（与 MC 的 prepareCullFrustum 完全一致）
        FRAME_VIEW.identity()
                .rotation(camera.rotation().conjugate(new Quaternionf()))
                .translate((float) -camPos.x, (float) -camPos.y, (float) -camPos.z);
        FRAME_VIEW_PROJECTION.set(FRAME_PROJECTION).mul(FRAME_VIEW);
        final Matrix4f inverse = FRAME_VIEW_PROJECTION.invert(new Matrix4f());
        // 退化矩阵（极端窗口尺寸等）时退回解析投影，而不是画出满屏乱线
        if (inverse.isFinite()) {
            FRAME_INVERSE.set(inverse);
            frameValid = true;
        } else {
            frameValid = false;
        }
        // 新的一帧：本帧的投影路径裁决要重新做一次
        frameStamp++;
    }

    /** 是否已经拿到本帧矩阵（自检/诊断用）。 */
    public static boolean frameValid() {
        return frameValid;
    }

    /** 解析投影与真实矩阵投影之间的像素差（诊断用：有偏差就说明两条路径不一致）。 */
    public static double projectionDelta(final Vec3 world, final Minecraft mc) {
        final int w = mc.getWindow().getGuiScaledWidth();
        final int h = mc.getWindow().getGuiScaledHeight();
        final double[] exact = projectViaMatrix(world, w, h);
        final double[] analytic = projectAnalytic(world, mc, w, h);
        if (exact == null || analytic == null) {
            return -1.0;
        }
        return Math.hypot(exact[0] - analytic[0], exact[1] - analytic[1]);
    }

    /**
     * 只给诊断用的解析投影。
     *
     * <p>它和 {@link #projectToScreen} 走的是两条独立的路径，两者给出的像素位置之差
     * 就是「画面上用的相机」与「当帧相机状态」之间的差距。正常情况下（相机推进在
     * {@code Camera.setup} 之前）这个差值应当接近 0。
     */
    @Nullable
    public static double[] projectAnalyticForDiag(final Vec3 world, final Minecraft mc,
                                                  final int w, final int h) {
        return projectAnalytic(world, mc, w, h);
    }

    /** 本帧视图矩阵（只读，供需要自己变换的调用方使用）。 */
    public static Matrix4f viewMatrix() {
        return new Matrix4f(FRAME_VIEW);
    }

    /** 光标射线在距相机 {@code at} 处的世界点。 */
    @Nullable
    public static Vec3 rayPoint(final Minecraft mc, final double at) {        final Vec3 dir = rayDirection(mc);
        final Vec3 pos = position();
        return pos.add(dir.scale(at));
    }

    // ============ 基向量 ============

    private static Vec3 viewVector(final float yawDeg, final float pitchDeg) {
        final float f = pitchDeg * (float) (Math.PI / 180.0);
        final float g = -yawDeg * (float) (Math.PI / 180.0);
        final float cosG = Mth.cos(g);
        final float sinG = Mth.sin(g);
        final float cosF = Mth.cos(f);
        final float sinF = Mth.sin(f);
        return new Vec3(sinG * cosF, -sinF, cosG * cosF);
    }

    private static Vec3 rightVector(final Vec3 look) {
        final Vec3 right = new Vec3(-look.z, 0.0, look.x);
        return right.lengthSqr() < 1.0e-8 ? new Vec3(1.0, 0.0, 0.0) : right.normalize();
    }

    private static Vec3 upVector(final Vec3 look, final Vec3 right) {
        return right.cross(look).normalize();
    }
}
