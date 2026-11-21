package com.ovo.sablestopnow.client.model;

import com.ovo.sablestopnow.sketch.Solid;
import com.ovo.sablestopnow.sketch.SolidClientState;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.List;
import java.util.UUID;

/**
 * **基准面特征的鼠标拾取** ✓
 *
 * <p>基准面不是材料实体（`placeAll` 直接跳过它）⇒ `FeatureHover` 的
 * 三角面拾取永远打不到它。但用户要求：
 * <ul>
 *   <li>创建草图时可以直接点基准面方框当草图平面；</li>
 *   <li>镜像时可以直接点基准面方框当镜像面；</li>
 *   <li>鼠标移上去要有高亮。</li>
 * </ul>
 *
 * <p>所以单独对 `Solid.Shape.Plane.corners(...)` 的**方片**做射线求交，
 * 命中结果同时给"点击选择"和"渲染高亮"用（同一份数据，不会看到的和点到的不一致）。
 */
public final class PlanePick {

    private PlanePick() {
    }

    public record Hit(UUID body, UUID solidId, Solid.Shape.Plane plane,
                      Vector3d localHit, double distance) {
    }

    @Nullable
    private static Hit hovered;

    @Nullable
    public static Hit hovered() {
        return hovered;
    }

    public static void clear() {
        hovered = null;
    }

    @Nullable
    public static UUID hoveredSolidId() {
        return hovered == null ? null : hovered.solidId();
    }

    /** 每帧刷新悬停（只应在 ModelMode 开启时调用）。 */
    public static void update(final Minecraft mc) {
        hovered = pickNow(mc);
    }

    /** 按当前鼠标射线重新求交（点击时也用它，避免"渲染帧还没更新"）。 */
    @Nullable
    public static Hit pickNow(final Minecraft mc) {
        if (mc == null || mc.level == null) {
            return null;
        }
        final Level level = mc.level;
        final Vec3 from = ModelCamera.rayOrigin(mc);
        final Vec3 dir = ModelCamera.rayDirection(mc);
        if (from == null || dir == null) {
            return null;
        }
        Hit best = null;
        double bestT = Double.MAX_VALUE;
        for (final Solid sd : SolidClientState.in(level.dimension().location())) {
            if (!(sd.shape() instanceof final Solid.Shape.Plane plane)) {
                continue;
            }
            final ClientSubLevel sub = BoxPick.sub(level, sd.body());
            if (sub == null || sub.renderPose() == null) {
                continue;
            }
            final Pose3dc pose = sub.renderPose();
            final Vector3d localFrom = pose.transformPositionInverse(
                    new Vector3d(from.x, from.y, from.z), new Vector3d());
            final Vector3d localFar = pose.transformPositionInverse(
                    new Vector3d(from.x + dir.x * 512.0,
                            from.y + dir.y * 512.0, from.z + dir.z * 512.0),
                    new Vector3d());
            final Vector3d localDir = localFar.sub(localFrom).normalize();
            final double[] o = plane.offsetOrigin();
            final Vector3d origin = new Vector3d(o[0], o[1], o[2]);
            final Vector3d normal = new Vector3d(plane.nx(), plane.ny(), plane.nz()).normalize();
            final double denom = localDir.dot(normal);
            if (Math.abs(denom) < 1.0e-6) {
                continue;
            }
            final double t = new Vector3d(origin).sub(localFrom).dot(normal) / denom;
            if (t <= 0.0 || t >= bestT) {
                continue;
            }
            final Vector3d hit = new Vector3d(localFrom).fma(t, localDir);
            // 命中点必须落在方片内（方片半宽 1.0；留一点容差方便点）
            final List<Vector3d> cs = plane.corners(1.0);
            final Vector3d u = new Vector3d(cs.get(1)).sub(cs.get(0)).normalize();
            final Vector3d v = new Vector3d(cs.get(3)).sub(cs.get(0)).normalize();
            final Vector3d rel = new Vector3d(hit).sub(cs.get(0));
            final double du = rel.dot(u);
            final double dv = rel.dot(v);
            if (du < -0.08 || du > 2.08 || dv < -0.08 || dv > 2.08) {
                continue;
            }
            bestT = t;
            best = new Hit(sd.body(), sd.id(), plane, hit, t);
        }
        return best;
    }
}
