package com.ovo.sablestopnow.client.model;

import com.ovo.sablestopnow.client.MateTargeting;
import com.ovo.sablestopnow.client.gui.lib.Ui;
import com.ovo.sablestopnow.client.gui.lib.UiContext;
import com.ovo.sablestopnow.client.gui.lib.UiDraw;
import com.ovo.sablestopnow.client.gui.lib.UiVector;
import com.ovo.sablestopnow.mate.MateFrames;
import com.ovo.sablestopnow.mate.MateRef;
import com.ovo.sablestopnow.mate.RefKind;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * 选择标记的<b>屏幕空间</b>呈现（SolidWorks 式）。
 *
 * <h2>为什么要搬到屏幕上</h2>
 * 原来「鼠标下是什么」直接高亮在模型上：玩家看得到结果，却<b>看不到可点范围</b> ——
 * 角点的判定区是面内归一化的一个小方块，投到屏幕上有时只有两三像素，鼠标到底该停在哪里全靠猜
 * （用户实测反馈「很难选择到模型的顶点」）。
 *
 * <p>SolidWorks 的做法是：鼠标靠近某个角时，先在屏幕上画出那个角的小方块（黄），
 * 鼠标移到方块上它变橙，这时点击才选中。可点范围因此变成「看得见的一个小方块」，
 * 而不是「隐形的一小块三维区域」。这里照同一套逻辑实现：
 * <ul>
 *   <li>候选参考用现成的就近判定得到（{@link MateTargeting}），不额外增加判定规则；</li>
 *   <li>候选几何投影到屏幕画标记：点=小方块、棱=线段、面=四边形轮廓；</li>
 *   <li>光标落在标记上时标记变橙，此时点击才被接受（见 {@link #cursorOnMarker}）；</li>
 *   <li>已经选中的参考仍然画在<b>模型</b>上（青色），与「选中后在模型上渲染」一致。</li>
 * </ul>
 */
public final class ModelPicks {

    /** 点标记的半边长（GUI 像素）：整体 9x9，够大又不会盖住方块。 */
    public static final int POINT_HALF = 4;
    /** 光标离「点」标记多近算停在上面（GUI 像素）。SolidWorks 里就是小方块本身的大小。 */
    public static final double POINT_GRAB = 6.0;
    /**
     * 光标离「棱」标记多近算停在上面（GUI 像素）。
     *
     * <p>比点宽：棱的候选区是按面内比例判的，镜头拉近时同一块区域在屏幕上会变得很大，
     * 若也按 6 像素判，玩家离棱十几像素就会被拒（看着已经在棱上却点不中）。
     */
    public static final double LINE_GRAB = 12.0;
    /** 「面」标记：光标在轮廓内就算命中，另外给轮廓外这一点余量。 */
    public static final double FACE_MARGIN = 8.0;

    private static final int COLOR_IDLE = 0xFFFFE24A;
    private static final int COLOR_ACTIVE = 0xFFFF9A1F;
    private static final int COLOR_EDGE = 0xE0202020;

    /** 标记线条的线宽（GUI 像素）。工程界面不需要 1px 的硬边线。 */
    private static final float STROKE = 1.5f;
    /** 面标记的填充透明度（很淡：只用来提示「是哪一面」）。 */
    private static final float FACE_FILL_ALPHA = 0.14f;

    private ModelPicks() {
    }

    /** 标记形状：点 / 线段 / 多边形（屏幕坐标，GUI 像素）。 */
    public enum Shape {
        POINT, SEGMENT, POLYGON
    }

    /** 屏幕标记：{@code xs/ys} 是投影后的顶点（点=1 个、线段=2 个、面=4 个）。 */
    public record Marker(Shape shape, double[] xs, double[] ys) {
    }

    /** 当前悬停参考的屏幕标记；没有候选或都在相机背后时返回 null。 */
    @Nullable
    public static Marker marker(final Minecraft mc) {
        final MateRef ref = ModelMode.hoveredRef();
        if (ref == null || ref.body() == null) {
            return null;
        }
        final SubLevel sub = MateTargeting.byId(ref.body());
        if (sub == null) {
            return null;
        }
        final Pose3dc pose = MateTargeting.poseOf(sub);
        final MateFrames.Resolved resolved = MateFrames.resolveLocal(sub, ref);
        final AABB box = ref.boxOrDefault();

        if (ref.kind() == RefKind.FACE && ref.face() != null) {
            final double[] xs = new double[4];
            final double[] ys = new double[4];
            for (int i = 0; i < 4; i++) {
                final Vector3d local = MateRef.faceCorner(ref.block(), ref.face(), i, box);
                if (!project(pose, local, mc, xs, ys, i)) {
                    return null;
                }
            }
            return new Marker(Shape.POLYGON, xs, ys);
        }
        if ((ref.kind() == RefKind.EDGE || ref.kind() == RefKind.EDGE_CENTER) && ref.face() != null) {
            final Vector3d dir = MateRef.faceEdgeDirection(ref.face(), ref.feature());
            final double length = MateRef.extentOf(box, dir);
            final Vector3d centre = resolved.point();
            final Vector3d a = new Vector3d(dir).mul(-length * 0.5).add(centre);
            final Vector3d b = new Vector3d(dir).mul(length * 0.5).add(centre);
            final double[] xs = new double[2];
            final double[] ys = new double[2];
            if (!project(pose, a, mc, xs, ys, 0) || !project(pose, b, mc, xs, ys, 1)) {
                return null;
            }
            return new Marker(Shape.SEGMENT, xs, ys);
        }
        // 其余都是「一个点」：角点、面心、棱心、结构中心/主轴/基准面
        final double[] xs = new double[1];
        final double[] ys = new double[1];
        if (!project(pose, resolved.point(), mc, xs, ys, 0)) {
            return null;
        }
        return new Marker(Shape.POINT, xs, ys);
    }

    private static boolean project(final Pose3dc pose, final Vector3d local, final Minecraft mc,
                                   final double[] xs, final double[] ys, final int index) {
        final Vector3d world = pose.transformPosition(new Vector3d(local), new Vector3d());
        final double[] screen = ModelCamera.projectToScreen(new Vec3(world.x, world.y, world.z), mc);
        if (screen == null) {
            return false;
        }
        xs[index] = screen[0];
        ys[index] = screen[1];
        return true;
    }

    /** 光标此刻是否停在标记上（没标记就不拦）。 */
    public static boolean cursorOnMarker(final Minecraft mc) {
        final Marker marker = marker(mc);
        if (marker == null) {
            return true;
        }
        final double mx = Ui.mouseXd();
        final double my = Ui.mouseYd();
        return switch (marker.shape()) {
            case POINT -> Math.hypot(mx - marker.xs()[0], my - marker.ys()[0]) <= POINT_GRAB;
            case SEGMENT -> distanceToSegment(mx, my, marker.xs()[0], marker.ys()[0],
                    marker.xs()[1], marker.ys()[1]) <= LINE_GRAB;
            case POLYGON -> insidePolygon(mx, my, marker.xs(), marker.ys())
                    || nearOutline(mx, my, marker.xs(), marker.ys(), FACE_MARGIN);
        };
    }

    /** 是否贴近多边形轮廓（面上取点要求光标在轮廓内，这里只是给边缘一点余量）。 */
    private static boolean nearOutline(final double px, final double py,
                                       final double[] xs, final double[] ys, final double margin) {
        for (int i = 0; i < xs.length; i++) {
            final int j = (i + 1) % xs.length;
            if (distanceToSegment(px, py, xs[i], ys[i], xs[j], ys[j]) <= margin) {
                return true;
            }
        }
        return false;
    }

    /** 在屏幕上画出标记（黄 = 候选，橙 = 光标已停在上面）。 */
    public static void render(final UiContext ctx, final GuiGraphics g) {
        final Minecraft mc = Minecraft.getInstance();
        final Marker marker = marker(mc);
        if (marker == null) {
            return;
        }
        final boolean active = cursorOnMarker(mc);
        final int color = active ? COLOR_ACTIVE : COLOR_IDLE;
        switch (marker.shape()) {
            case POINT -> dot(g, marker.xs()[0], marker.ys()[0], color);
            case SEGMENT -> {
                final double x0 = marker.xs()[0];
                final double y0 = marker.ys()[0];
                final double x1 = marker.xs()[1];
                final double y1 = marker.ys()[1];
                // 深色描边 + 彩色芯：任何底色上都读得出来（工程图里的「双线」画法）
                UiVector.line(g, x0, y0, x1, y1, STROKE + 1.6f, COLOR_EDGE);
                UiVector.line(g, x0, y0, x1, y1, STROKE, color);
                // 两端各点一个小方块：端点是「角点」的位置，给玩家一个参照
                dot(g, x0, y0, color);
                dot(g, x1, y1, color);
            }
            case POLYGON -> {
                // 面：极淡的填充（能看出是哪一面）+ 抗锯齿轮廓
                UiVector.polygonFillCapped(g, marker.xs(), marker.ys(), UiDraw.fade(color, FACE_FILL_ALPHA));
                UiVector.polyline(g, marker.xs(), marker.ys(), STROKE + 1.6f, COLOR_EDGE, true);
                UiVector.polyline(g, marker.xs(), marker.ys(), STROKE, color, true);
                for (int i = 0; i < 4; i++) {
                    corner(g, marker.xs()[i], marker.ys()[i], color);
                }
            }
        }
        diag(mc, marker, active);
    }

    /** 一个实心小方块（带深色描边，任何底色上都看得清）。 */
    private static void dot(final GuiGraphics g, final double cx, final double cy, final int color) {
        UiVector.snapPoint(g, cx, cy, POINT_HALF, color, COLOR_EDGE);
    }

    /** 角点的短直角标记（面轮廓的四个角，SolidWorks 的捕捉反馈）。 */
    private static void corner(final GuiGraphics g, final double cx, final double cy, final int color) {
        final double arm = 4.0;
        UiVector.line(g, cx - arm, cy, cx, cy, STROKE, color);
        UiVector.line(g, cx, cy - arm, cx, cy, STROKE, color);
    }

    private static double distanceToSegment(final double px, final double py,
                                            final double x0, final double y0,
                                            final double x1, final double y1) {
        final double dx = x1 - x0;
        final double dy = y1 - y0;
        final double lenSq = dx * dx + dy * dy;
        if (lenSq < 1.0e-6) {
            return Math.hypot(px - x0, py - y0);
        }
        final double t = Math.max(0.0, Math.min(1.0, ((px - x0) * dx + (py - y0) * dy) / lenSq));
        return Math.hypot(px - (x0 + t * dx), py - (y0 + t * dy));
    }

    /** 射线法判点是否在多边形内（面标记的四边形恒为凸，够用）。 */
    private static boolean insidePolygon(final double px, final double py,
                                         final double[] xs, final double[] ys) {
        boolean inside = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            if ((ys[i] > py) != (ys[j] > py)
                    && px < (xs[j] - xs[i]) * (py - ys[i]) / (ys[j] - ys[i]) + xs[i]) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** 供 HUD 提示：当前候选是什么（「点/棱/面」）。 */
    public static String shapeKey(final RefKind kind) {        return switch (kind) {
            case VERTEX -> "model.pick.vertex";
            case EDGE, EDGE_CENTER -> "model.pick.edge";
            case FACE, FACE_CENTER -> "model.pick.face";
            default -> "model.pick.body";
        };
    }

    /** 面的法线方向（用于提示「正视此面」会朝哪边看）。 */
    @Nullable
    public static Direction faceOf(final MateRef ref) {
        return ref.face();
    }

    // ============ 临时诊断（定位「标记与模型有偏移」，确认无偏移后删） ============

    private static long lastDiagMs;
    @Nullable
    private static String lastDiagKey;

    /**
     * 每秒最多一条：把「标记落在屏幕哪儿」「两种投影各算到哪儿」「光标在哪」一起打出来。
     *
     * <p>一行就足以区分四种完全不同的偏移：
     * <ul>
     *   <li>{@code frame=false} —— 没抓到本帧矩阵，退回了会过期的解析投影；</li>
     *   <li>{@code stale} 大 —— 矩阵（画面上用的相机）与解析（当帧相机）不一致，
     *       说明相机的推进时机又跑到渲染之后去了；</li>
     *   <li>{@code cursorDist} 大 —— 投影与拾取不是同一套变换（「看着在光标下却点不中」）；</li>
     *   <li>三者都正常但标记仍明显不贴模型 —— 几何锚点本身算错（看 {@code box} 是不是整格）。</li>
     * </ul>
     */
    private static void diag(final Minecraft mc, final Marker marker, final boolean active) {
        final MateRef ref = ModelMode.hoveredRef();
        if (ref == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - lastDiagMs < 1000L) {
            return;
        }
        lastDiagMs = now;
        final Vec3 world = worldOfFirstVertex(ref);
        final String key = ref.kind() + "|" + ref.block() + "|" + ref.face() + "|" + ref.feature()
                + "|" + ref.boxOrDefault();
        if (key.equals(lastDiagKey)) {
            return;
        }
        lastDiagKey = key;

        final int w = mc.getWindow().getGuiScaledWidth();
        final int h = mc.getWindow().getGuiScaledHeight();
        final double[] exact = world == null ? null : ModelCamera.projectToScreen(world, mc);
        final double[] analytic = world == null ? null : ModelCamera.projectAnalyticForDiag(world, mc, w, h);
        final double stale = exact == null || analytic == null
                ? -1.0 : Math.hypot(exact[0] - analytic[0], exact[1] - analytic[1]);
        final double cursorDist = Math.hypot(marker.xs()[0] - Ui.mouseXd(), marker.ys()[0] - Ui.mouseYd());

        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[model-diag] frame={} useMatrix={} shape={} ref={} active={} "
                        + "screen={} analytic={} stale={}px cursor={} cursorDist={}px anchor={}",
                ModelCamera.frameValid(), ModelCamera.usingMatrices(), marker.shape(), key, active,
                fmt2(marker.xs()[0], marker.ys()[0]),
                analytic == null ? "n/a" : fmt2(analytic[0], analytic[1]),
                stale < 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.2f", stale),
                fmt2(Ui.mouseXd(), Ui.mouseYd()),
                String.format(java.util.Locale.ROOT, "%.2f", cursorDist),
                world == null ? "n/a" : fmt3(world));
    }

    private static String fmt2(final double a, final double b) {
        return String.format(java.util.Locale.ROOT, "(%.2f,%.2f)", a, b);
    }

    private static String fmt3(final Vec3 v) {
        return String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", v.x, v.y, v.z);
    }

    /** 标记第一个顶点对应的世界点（诊断用）。 */
    @Nullable
    private static Vec3 worldOfFirstVertex(final MateRef ref) {
        if (ref.body() == null) {
            return null;
        }
        final SubLevel sub = MateTargeting.byId(ref.body());
        if (sub == null) {
            return null;
        }
        final Pose3dc pose = MateTargeting.poseOf(sub);
        final AABB box = ref.boxOrDefault();
        final Vector3d local;
        if (ref.kind() == RefKind.FACE && ref.face() != null) {
            local = MateRef.faceCorner(ref.block(), ref.face(), 0, box);
        } else {
            local = MateFrames.resolveLocal(sub, ref).point();
        }
        final Vector3d world = pose.transformPosition(new Vector3d(local), new Vector3d());
        return new Vec3(world.x, world.y, world.z);
    }
}
