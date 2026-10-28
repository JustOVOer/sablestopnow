package com.ovo.sablestopnow.client.model;

import com.ovo.sablestopnow.sketch.Features;
import com.ovo.sablestopnow.sketch.Solid;
import com.ovo.sablestopnow.sketch.SolidClientState;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * **模型视图下的"面 / 棱 / 点"悬停拾取** ✓（需求：鼠标能选到面、棱、点，并都有预览高亮）。
 *
 * <p>拾取顺序按"越具体越优先"：**点 → 棱 → 面** ✓
 * （都基于**同一个**最近命中面：先找到面，再判断光标离这个面的顶点/棱有多近 ✓）
 *
 * <p>命中的几何会**预先换算到世界坐标**并存下来 ⇒ 渲染侧只要 `world - cameraPos` 就能画 ✓
 * （不用在渲染线程里再拿一遍 pose，也就不会和实体的 pose 漂移 ✓）
 */
public final class FeatureHover {

    private FeatureHover() {
    }

    /** 当前悬停到的东西。 */
    public enum Kind {
        /** 什么都没指到 ✓ */
        NONE,
        /** 一个面 ✓（可作草图基准面） */
        FACE,
        /** 一条棱 ✓ */
        EDGE,
        /** 一个顶点 ✓ */
        VERTEX,
    }

    /**
     * 一次命中的结果 ✓。
     *
     * @param localCenter 面质心（**实体局部坐标** ✓ 建基准面用）
     * @param localU      面内**最长直线边**的单位方向（**局部** ✓ 基准面的 axisU）
     * @param face        命中的面（含局部三角形的边界/棱/顶点）
     * @param index       命中 EDGE/VERTEX 时的序号（相对该面），FACE 时为 -1
     * @param worldTris   面的三角形（世界坐标，渲染填充用）
     * @param worldSegs   面的边界直线段（世界坐标）
     * @param worldVerts  面的顶点（世界坐标）
     */
    public record Hit(UUID body, UUID solidId, Features.Face face, Kind kind, int index,
                      Vector3d localHit, Vector3d localCenter, Vector3d localNormal, Vector3d localU,
                      List<Vec3[]> worldTris, List<Vec3[]> worldSegs, List<Vec3> worldVerts,
                      double distance) {
    }

    @Nullable
    private static Hit hit;

    @Nullable
    public static Hit get() {
        return hit;
    }

    public static void clear() {
        hit = null;
    }

    /** 每帧更新（开销：每个实体几百~几千三角形，够快 ✓；不建特征图就立即返回 ✓）。 */
    public static void update(final Minecraft mc) {
        hit = null;
        if (mc == null || mc.level == null) {
            return;
        }
        final Level level = mc.level;
        if (!(SubLevelContainer.getContainer(level) instanceof final ClientSubLevelContainer container)) {
            return;
        }
        final List<Solid.Placed> placed = SolidClientState.placed(level);
        if (placed.isEmpty()) {
            return;
        }
        final Vec3 from = ModelCamera.position();
        final Vec3 dir = ModelCamera.rayDirection(mc);
        if (from == null || dir == null) {
            return;
        }

        double bestT = Double.MAX_VALUE;
        Hit best = null;
        for (final ClientSubLevel sub : container.getAllSubLevels()) {
            final Pose3dc pose = sub.renderPose();
            if (pose == null) {
                continue;
            }
            final UUID bodyId = sub.getUniqueId();
            final Vector3d localFrom = pose.transformPositionInverse(
                    new Vector3d(from.x, from.y, from.z), new Vector3d());
            final Vector3d farWorld = new Vector3d(from.x + dir.x * 512.0,
                    from.y + dir.y * 512.0, from.z + dir.z * 512.0);
            final Vector3d localFar = pose.transformPositionInverse(farWorld, new Vector3d());
            final Vector3d localDir = localFar.sub(localFrom).normalize();
            for (final Solid.Placed p : placed) {
                if (!bodyId.equals(p.solid().body())) {
                    continue;
                }
                final Features.Graph graph = Features.of(p);
                for (final Features.Face face : graph.faces()) {
                    // ★ 曲面也要能**整体高亮** ✓（用户实测"曲面没有整体高亮"✗）
                    //   能不能当基准面由 chooseModelFace 再判 ✓（那里只认 planar ✓）
                    for (final Vector3d[] tri : face.triangles()) {
                        final double t = rayTriangle(localFrom, localDir, tri[0], tri[1], tri[2]);
                        if (t > 0 && t < bestT) {
                            bestT = t;
                            best = makeHit(bodyId, p.solid().id(), face, pose, localFrom, localDir, from, t);
                        }
                    }
                }
            }
        }
        hit = best;
    }

    /** 命中后的分类（点 → 棱 → 面）与世界坐标缓存 ✓。 */
    private static Hit makeHit(final UUID bodyId, final UUID solidId, final Features.Face face, final Pose3dc pose,
                               final Vector3d localFrom, final Vector3d localDir, final Vec3 from,
                               final double t) {
        final Vector3d localHit = localFrom.fma(t, localDir, new Vector3d());
        final double dist = Math.sqrt(Math.pow(from.x - pose.position().x(), 2)
                + Math.pow(from.y - pose.position().y(), 2)
                + Math.pow(from.z - pose.position().z(), 2));
        final double vertexTol = Math.max(0.05, dist * 0.015);
        final double edgeTol = Math.max(0.035, dist * 0.010);

        // 顶点优先 ✓
        int vertIndex = -1;
        double bestVert = vertexTol;
        final List<Vector3d> localVerts = new ArrayList<>();
        for (final double[] seg : face.segments()) {
            localVerts.add(new Vector3d(seg[0], seg[1], seg[2]));
            localVerts.add(new Vector3d(seg[3], seg[4], seg[5]));
        }
        for (int i = 0; i < localVerts.size(); i++) {
            final double d = localVerts.get(i).distance(localHit);
            if (d < bestVert) {
                bestVert = d;
                vertIndex = i;
            }
        }
        int edgeIndex = -1;
        double bestEdge = edgeTol;
        for (int i = 0; i < face.segments().size(); i++) {
            final double[] s = face.segments().get(i);
            final double d = distToSeg(localHit, s);
            if (d < bestEdge) {
                bestEdge = d;
                edgeIndex = i;
            }
        }
        final Kind kind = vertIndex >= 0 ? Kind.VERTEX : (edgeIndex >= 0 ? Kind.EDGE : Kind.FACE);

        // 世界坐标缓存（渲染直接用 ✓）
        final List<Vec3[]> worldTris = new ArrayList<>(face.triangles().size());
        for (final Vector3d[] tri : face.triangles()) {
            worldTris.add(new Vec3[] {toWorld(pose, tri[0]), toWorld(pose, tri[1]),
                    toWorld(pose, tri[2])});
        }
        final List<Vec3[]> worldSegs = new ArrayList<>(face.segments().size());
        for (final double[] s : face.segments()) {
            worldSegs.add(new Vec3[] {
                    toWorld(pose, new Vector3d(s[0], s[1], s[2])),
                    toWorld(pose, new Vector3d(s[3], s[4], s[5]))});
        }
        final List<Vec3> worldVerts = new ArrayList<>(localVerts.size());
        for (final Vector3d v : localVerts) {
            worldVerts.add(toWorld(pose, v));
        }

        // 基准面的 axisU：面内**最长**的直线边方向 ✓（让参考线是水平/竖直的 ✓）
        final Vector3d axisU = new Vector3d(1, 0, 0);
        double longest = 0;
        for (final double[] s : face.segments()) {
            final Vector3d d = new Vector3d(s[3] - s[0], s[4] - s[1], s[5] - s[2]);
            final double len = d.length();
            if (len > longest) {
                longest = len;
                axisU.set(d).normalize();
            }
        }
        final Vector3d normal = new Vector3d(face.normal());
        // 保证 u ⊥ n（数值上更稳）✓
        if (Math.abs(axisU.dot(normal)) > 1.0e-6) {
            axisU.fma(-axisU.dot(normal), normal).normalize();
        }
        return new Hit(bodyId, solidId, face, kind, kind == Kind.VERTEX ? vertIndex : edgeIndex,
                new Vector3d(localHit), new Vector3d(face.origin()), normal, axisU,
                worldTris, worldSegs, worldVerts, dist);
    }

    private static Vec3 toWorld(final Pose3dc pose, final Vector3d local) {
        final Vector3d w = pose.transformPosition(local, new Vector3d());
        return new Vec3(w.x, w.y, w.z);
    }

    /** 点到线段距离 ✓。 */
    private static double distToSeg(final Vector3d p, final double[] s) {
        final Vector3d a = new Vector3d(s[0], s[1], s[2]);
        final Vector3d b = new Vector3d(s[3], s[4], s[5]);
        final Vector3d ab = new Vector3d(b).sub(a);
        final double len2 = ab.lengthSquared();
        if (len2 < 1.0e-12) {
            return p.distance(a);
        }
        final double u = Math.max(0, Math.min(1, new Vector3d(p).sub(a).dot(ab) / len2));
        return p.distance(new Vector3d(a).fma(u, ab));
    }

    /** Möller–Trumbore（返回射线参数 t，未命中返回 -1 ✓）。 */
    private static double rayTriangle(final Vector3d o, final Vector3d d,
                                      final Vector3d a, final Vector3d b, final Vector3d c) {
        final Vector3d e1 = new Vector3d(b).sub(a);
        final Vector3d e2 = new Vector3d(c).sub(a);
        final Vector3d pv = new Vector3d(d).cross(e2);
        final double det = e1.dot(pv);
        if (Math.abs(det) < 1.0e-9) {
            return -1;
        }
        final double inv = 1.0 / det;
        final Vector3d tv = new Vector3d(o).sub(a);
        final double u = tv.dot(pv) * inv;
        if (u < -1.0e-6 || u > 1 + 1.0e-6) {
            return -1;
        }
        final Vector3d qv = new Vector3d(tv).cross(e1);
        final double v = new Vector3d(d).dot(qv) * inv;
        if (v < -1.0e-6 || u + v > 1 + 1.0e-6) {
            return -1;
        }
        return new Vector3d(e2).dot(qv) * inv;
    }
}
