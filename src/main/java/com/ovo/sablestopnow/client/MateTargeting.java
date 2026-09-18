package com.ovo.sablestopnow.client;

import com.ovo.sablestopnow.mate.MateFrames;
import com.ovo.sablestopnow.mate.MateRef;
import com.ovo.sablestopnow.mate.RefKind;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * 配合模式的客户端拾取：把准星射线落在物理结构上的那一格，分类成<b>角点 / 棱 / 面</b>，
 * 或者在按住结构参考修饰键时给出<b>结构级</b>参考（中心 / 主轴 / 基准面）。
 *
 * <h2>坐标空间</h2>
 * 走的是与 {@code StaffEnhanceClientHandler.clipOnce} 同一条路：Sable 重写了 {@code level.clip}
 * 并接受一个「位姿提供器」，因此命中点在 <b>plot 局部（base）坐标</b>，命中面也是 plot 局部的面。
 * 这正好就是 {@link MateRef} 要存的东西 —— 不需要再做一次世界↔局部的来回换算，
 * 也不会因为结构旋转/缩放而选错格子。
 *
 * <h2>为什么是「就近分类」而不是切换模式</h2>
 * cp/description.txt 里 SolidWorks 的点/棱/面是三类不同实体。这里不做「先选类型再点」，
 * 而是按命中点在面内的位置自动就近判定（面内归一化坐标 |u|、|v| 都接近 0.5 就是角点，
 * 只有一个接近 0.5 就是棱，否则是面），配合预览高亮做到「看到什么就选中什么」。
 */
public final class MateTargeting {

    private MateTargeting() {
    }

    /** 射线能打多远（格）。 */
    public static final double RAY_RANGE = 64.0;

    /** 面内归一化坐标超过它就算「贴近棱」。面内坐标范围是 [-0.5, 0.5]。 */
    private static final double EDGE_ZONE = 0.4375;

    /** 两个方向都超过它就算「贴近角点」。 */
    private static final double CORNER_ZONE = 0.375;

    /** 结构级参考的可循环数量：中心(1) + 主轴(3) + 基准面(3)。 */
    public static final int BODY_REF_COUNT = 7;

    /** 一次命中：物理结构 + plot 局部格点 + plot 局部命中面 + plot 局部精确命中点。 */
    public record Hit(SubLevel body, BlockPos baseBlock, Direction baseFace, Vec3 baseHit) {
    }

    // ============ 射线 ============

    /** 准星射线命中的物理结构；没打到物理结构返回 null。 */
    @Nullable
    public static Hit raycast() {
        final Minecraft mc = Minecraft.getInstance();
        final LocalPlayer player = mc.player;
        if (player == null || player.level() == null) {
            return null;
        }
        final Vec3 eye = player.getEyePosition(1.0f);
        final Vec3 dir = player.getLookAngle();
        final BlockHitResult hit = clip(player, eye, eye.add(dir.scale(RAY_RANGE)));
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return null;
        }
        final SubLevel body = Sable.HELPER.getContainingClient(hit.getLocation());
        if (body == null || body.isRemoved()) {
            return null;
        }
        return new Hit(body, BlockPos.containing(hit.getLocation()), hit.getDirection(), hit.getLocation());
    }

    /**
     * 一次位姿感知的方块射线。与 {@code level.clip} 的区别只在「推入 Sable 的位姿提供器」，
     * 这样射线是在物理结构<b>当前渲染位姿</b>下求交的。
     */
    @Nullable
    private static BlockHitResult clip(final LocalPlayer player, final Vec3 from, final Vec3 to) {
        final Level level = player.level();
        if (!(level instanceof final LevelPoseProviderExtension extension)) {
            return null;
        }
        final ClipContext context = new ClipContext(from, to, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, CollisionContext.of(player));
        // 明确不忽略任何物理体：配合模式要点的就是眼前这个
        ((ClipContextExtension) (Object) context).sable$setIgnoredSubLevel(null);
        extension.sable$pushPoseSupplier(x -> ((ClientSubLevel) x).renderPose());
        try {
            return level.clip(context);
        } finally {
            extension.sable$popPoseSupplier();
        }
    }

    // ============ 候选参考 ============

    /**
     * 方块级候选：按命中点在面内的位置就近判定为角点 / 棱 / 面。
     *
     * @param body 目标结构（用于取 UUID）
     */
    public static MateRef blockCandidate(final SubLevel body, final Hit hit) {
        final BlockPos block = hit.baseBlock();
        final Direction face = hit.baseFace();
        final Vector3d center = MateRef.faceCenter(block, face);
        final Vector3d u = MateRef.faceU(face);
        final Vector3d v = MateRef.faceV(face);

        final Vector3d offset = new Vector3d(hit.baseHit().x, hit.baseHit().y, hit.baseHit().z).sub(center);
        final double du = offset.dot(u);
        final double dv = offset.dot(v);

        if (Math.min(Math.abs(du), Math.abs(dv)) > CORNER_ZONE) {
            // 角点：序号 bit0 = u 正负，bit1 = v 正负（与 MateRef.faceCorner 的约定一致）
            final int index = (du > 0 ? 1 : 0) | (dv > 0 ? 2 : 0);
            return MateRef.block(body.getUniqueId(), RefKind.VERTEX, block, face, index);
        }
        if (Math.max(Math.abs(du), Math.abs(dv)) > EDGE_ZONE) {
            // 棱：edge0 在 v=-0.5 侧，edge1 在 u=+0.5 侧，edge2 在 v=+0.5 侧，edge3 在 u=-0.5 侧
            final double[] closeness = { -dv, du, dv, -du };
            int best = 0;
            for (int i = 1; i < 4; i++) {
                if (closeness[i] > closeness[best]) {
                    best = i;
                }
            }
            return MateRef.block(body.getUniqueId(), RefKind.EDGE, block, face, best);
        }
        return MateRef.block(body.getUniqueId(), RefKind.FACE, block, face, -1);
    }

    /**
     * 结构级候选（按修饰键 + 滚轮循环）。
     *
     * @param cycle 0=中心，1..3=主轴 X/Y/Z，4..6=基准面 X/Y/Z
     */
    public static MateRef bodyCandidate(final SubLevel body, final int cycle) {
        final int index = Math.floorMod(cycle, BODY_REF_COUNT);
        return switch (index) {
            case 0 -> MateRef.center(body.getUniqueId());
            case 1, 2, 3 -> MateRef.axis(body.getUniqueId(), index - 1);
            default -> MateRef.plane(body.getUniqueId(), index - 4);
        };
    }

    /** 结构级参考的翻译键后缀（用于 HUD 提示当前循环到哪一项）。 */
    public static String bodyRefKey(final int cycle) {
        final int index = Math.floorMod(cycle, BODY_REF_COUNT);
        return switch (index) {
            case 0 -> "mate.body_ref.center";
            case 1, 2, 3 -> "mate.body_ref.axis";
            default -> "mate.body_ref.plane";
        };
    }

    // ============ 世界坐标换算（预览渲染用） ============

    /** 渲染位姿：客户端用 renderPose（与 clip 保持一致），否则退回逻辑位姿。 */
    public static Pose3dc poseOf(final SubLevel body) {
        if (body instanceof final ClientSubLevel client) {
            return client.renderPose();
        }
        return body.logicalPose();
    }

    /** 参考点在世界系的位置（预览用）。 */
    public static Vector3d worldPoint(final SubLevel body, final MateRef ref) {
        return poseOf(body).transformPosition(MateFrames.resolveLocal(body, ref).point(), new Vector3d());
    }

    /** 参考方向在世界系的单位向量（无方向返回 null）。 */
    @Nullable
    public static Vector3d worldDirection(final SubLevel body, final MateRef ref) {
        final Vector3d local = MateFrames.resolveLocal(body, ref).direction();
        if (local == null) {
            return null;
        }
        final Vector3d out = poseOf(body).orientation().transform(new Vector3d(local));
        return out.lengthSquared() < 1e-12 ? null : out.normalize();
    }

    /** 结构包围盒中心的世界位置。 */
    public static Vector3d worldCenter(final SubLevel body) {
        return poseOf(body).transformPosition(MateFrames.plotCenter(body), new Vector3d());
    }

    /** 按 UUID 在当前客户端维度里找一个结构。 */
    @Nullable
    public static SubLevel byId(final java.util.UUID id) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        final var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level);
        if (container == null) {
            return null;
        }
        final SubLevel sub = container.getSubLevel(id);
        return sub == null || sub.isRemoved() ? null : sub;
    }
}
