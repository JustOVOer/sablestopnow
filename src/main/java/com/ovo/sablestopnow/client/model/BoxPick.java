package com.ovo.sablestopnow.client.model;

import com.ovo.sablestopnow.sketch.Solid;
import com.ovo.sablestopnow.sketch.SolidClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3i;
import org.joml.Vector3f;

import java.util.List;
import java.util.UUID;

/**
 * **按盒子做的兜底射线拾取** ✓。
 *
 * <p>为什么需要它 ✗✓：特征工具（基准面/圆角/镜像/阵列）要求"点模型上的面或棱"，
 * 而 {@code FeatureHover} 依赖三角面判级（点→棱→面），在曲面/远距离/容差边缘会返回 null 或判级抖动，
 * 于是"点上去没有任何反应"（用户反复实测）。盒子是**碰撞与渲染的共同底座**，
 * 射线与盒子的交点永远存在 ⇒ 用它兜底可以保证"指哪选哪"不会失效 ✓
 *
 * <p>输出：命中点（世界）、命中面的**外法线**（世界）、体 id、命中的那条盒子。
 */
public final class BoxPick {

    private BoxPick() {
    }

    /** 一次盒子命中 ✓ */
    public record Hit(UUID body, Vector3dc worldPoint, Vector3dc worldNormal,
                      double[] localBox, int axis, boolean positive) {
    }

    /**
     * 从相机发一条射线，取**最近的盒子面**命中 ✓。
     *
     * @return 没命中返回 null
     */
    public static Hit nearest(final Minecraft mc) {
        if (mc == null || mc.level == null) {
            return null;
        }
        final Level level = mc.level;
        final var container = net.minecraft.client.Minecraft.getInstance().level;
        final List<Solid.Placed> placed = SolidClientState.placed(level);
        if (placed.isEmpty()) {
            return null;
        }
        final var from = ModelCamera.position();
        final var dir = ModelCamera.rayDirection(mc);
        if (from == null || dir == null) {
            return null;
        }
        final java.util.Map<UUID, dev.ryanhcode.sable.sublevel.ClientSubLevel> byId =
                new java.util.HashMap<>();
        if (dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level)
                instanceof final dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer c) {
            for (final dev.ryanhcode.sable.sublevel.ClientSubLevel sub
                    : c.getAllSubLevels()) {
                byId.put(sub.getUniqueId(), sub);
            }
        }
        Hit best = null;
        double bestT = Double.MAX_VALUE;
        for (final Solid.Placed p : placed) {
            final dev.ryanhcode.sable.sublevel.ClientSubLevel sub =
                    byId.get(p.solid().body());
            if (sub == null || sub.renderPose() == null) {
                continue;
            }
            final var pose = sub.renderPose();
            final Vector3d localFrom = pose.transformPositionInverse(
                    new Vector3d(from.x, from.y, from.z), new Vector3d());
            final Vector3d localFar = pose.transformPositionInverse(
                    new Vector3d(from.x + dir.x * 512.0, from.y + dir.y * 512.0,
                            from.z + dir.z * 512.0), new Vector3d());
            final Vector3d localDir = localFar.sub(localFrom).normalize();
            for (final double[] b : p.boxes()) {
                final double[] t = rayBox(localFrom, localDir, b);
                if (t == null || t[0] <= 0 || t[0] >= bestT) {
                    continue;
                }
                bestT = t[0];
                final int axis = (int) t[1];
                final boolean positive = t[2] > 0;
                final Vector3d localPoint = localFrom.fma(t[0], localDir, new Vector3d());
                final Vector3d localNormal = new Vector3d();
                localNormal.setComponent(axis, positive ? 1 : -1);
                // 法线：局部 -> 世界（用位置差求方向，避免依赖 transformNormal 的 API 差异）
                final Vector3d o0 = pose.transformPosition(new Vector3d(0, 0, 0), new Vector3d());
                final Vector3d n1 = pose.transformPosition(new Vector3d(localNormal),
                        new Vector3d()).sub(o0).normalize();
                final Vector3d wp = pose.transformPosition(new Vector3d(localPoint),
                        new Vector3d());
                best = new Hit(p.solid().body(), wp, n1, b, axis, positive);
            }
        }
        return best;
    }

    /**
     * 射线与轴对齐盒求交 ✓
     *
     * @return {t, axis, sign}：axis=命中的轴（0/1/2），sign>0 表示命中正侧；不命中返回 null
     */
    public static double[] rayBox(final Vector3dc from, final Vector3dc dir, final double[] b) {
        double tmin = -Double.MAX_VALUE;
        double tmax = Double.MAX_VALUE;
        int axis = -1;
        double sign = 0;
        for (int i = 0; i < 3; i++) {
            final double o = i == 0 ? from.x() : (i == 1 ? from.y() : from.z());
            final double d = i == 0 ? dir.x() : (i == 1 ? dir.y() : dir.z());
            final double lo = b[i];
            final double hi = b[i + 3];
            if (Math.abs(d) < 1.0e-12) {
                if (o < lo || o > hi) {
                    return null;
                }
                continue;
            }
            double t0 = (lo - o) / d;
            double t1 = (hi - o) / d;
            double s = -1;
            if (t0 > t1) {
                final double tmp = t0;
                t0 = t1;
                t1 = tmp;
                s = 1;
            } else {
                s = -1;
            }
            if (t0 > tmin) {
                tmin = t0;
                axis = i;
                sign = s;
            }
            tmax = Math.min(tmax, t1);
            if (tmin > tmax) {
                return null;
            }
        }
        if (axis < 0) {
            return null;
        }
        return new double[] {tmin, axis, sign};
    }

    /** 盒子里离某点**最近的一条棱**（局部坐标）✓ 返回 {ax,ay,az,bx,by,bz}；失败 null */
    public static double[] nearestEdgeLocal(final double[] b, final Vector3dc p) {
        double best = Double.MAX_VALUE;
        double[] bestSeg = null;
        for (int axis = 0; axis < 3; axis++) {
            for (int s1 = 0; s1 < 2; s1++) {
                for (int s2 = 0; s2 < 2; s2++) {
                    final double[] a = new double[3];
                    final double[] e = new double[3];
                    a[axis] = b[axis];
                    e[axis] = b[axis + 3];
                    final int o1 = (axis + 1) % 3;
                    final int o2 = (axis + 2) % 3;
                    a[o1] = s1 == 0 ? b[o1] : b[o1 + 3];
                    e[o1] = a[o1];
                    a[o2] = s2 == 0 ? b[o2] : b[o2 + 3];
                    e[o2] = a[o2];
                    final org.joml.Vector3d av = new org.joml.Vector3d(a[0], a[1], a[2]);
                    final org.joml.Vector3d ab = new org.joml.Vector3d(e[0], e[1], e[2]).sub(av);
                    final double len2 = ab.lengthSquared();
                    double t = len2 < 1.0e-12 ? 0.0
                            : new org.joml.Vector3d(p).sub(av).dot(ab) / len2;
                    t = Math.max(0.0, Math.min(1.0, t));
                    final double d = new org.joml.Vector3d(av).fma(t, ab).distance(p);
                    if (d < best) {
                        best = d;
                        bestSeg = new double[] {a[0], a[1], a[2], e[0], e[1], e[2]};
                    }
                }
            }
        }
        return bestSeg;
    }

    /** 把一个世界坐标点/法线转成该体的**局部**坐标 ✓ 返回 {ox,oy,oz,nx,ny,nz}；失败 null */
    public static double[] toLocal(final net.minecraft.world.level.Level level,
                                   final UUID body, final Vector3dc worldPoint,
                                   final Vector3dc worldNormal) {
        final var sub = sub(level, body);
        if (sub == null || sub.renderPose() == null) {
            return null;
        }
        final var pose = sub.renderPose();
        final org.joml.Vector3d lp = pose.transformPositionInverse(
                new org.joml.Vector3d(worldPoint), new org.joml.Vector3d());
        final org.joml.Vector3d o0 = pose.transformPositionInverse(new org.joml.Vector3d(0, 0, 0),
                new org.joml.Vector3d());
        final org.joml.Vector3d ln = pose.transformPositionInverse(
                        new org.joml.Vector3d(worldPoint).add(worldNormal), new org.joml.Vector3d())
                .sub(lp).normalize();
        return new double[] {lp.x, lp.y, lp.z, ln.x, ln.y, ln.z};
    }

    /** 取某个体的渲染姿态 ✓ */
    public static dev.ryanhcode.sable.sublevel.ClientSubLevel sub(
            final net.minecraft.world.level.Level level, final UUID body) {
        if (level == null) {
            return null;
        }
        if (dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level)
                instanceof final dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer c) {
            for (final dev.ryanhcode.sable.sublevel.ClientSubLevel sub : c.getAllSubLevels()) {
                if (sub.getUniqueId().equals(body)) {
                    return sub;
                }
            }
        }
        return null;
    }
}
