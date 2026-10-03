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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * 配合模式的客户端拾取：把准星射线落在物理结构上的那一格，分类成
 * <b>角点 / 棱心 / 棱 / 面心 / 面</b> 五类之一，
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
    private static final double EDGE_ZONE = 0.375;

    /**
     * 「瞄准角点」的捕捉半径（面内归一化坐标，整格 = 1）。
     *
     * <p>⚠ 以前判的是「两个面内坐标都 &gt; 0.375」——那是一个 0.125 × 0.125 的小方块，
     * 只占整个面的 1.6%，玩家几乎不可能把射线稳稳打进这么小的区域，
     * 用户实测反馈「很难选择到模型的顶点」。
     *
     * <p>现在按<b>到最近角点的距离</b>判：半径 0.28 的扇形区域约占整面 25%，
     * 瞄准任意一个角都轻松命中，同时不侵占面心区（面心到角点距离 0.707）。
     */
    private static final double CORNER_RADIUS = 0.28;

    /** 面内两个方向都小于它就算「瞄准面心」。 */
    private static final double CENTER_ZONE = 0.1875;

    /** 沿棱方向距棱中点小于它就算「瞄准棱心」。取 0.14 = 只占整条棱中间 28%，
     * 这样「瞄着棱身点」拿到的是<b>棱</b>而不是棱心（用户实测反馈「想选棱却选到中点」）。 */
    private static final double MID_ZONE = 0.14;

    /** 结构级参考的可循环数量：中心(1) + 主轴(3) + 基准面(3)。 */
    public static final int BODY_REF_COUNT = 7;

    /** 一次命中：物理结构 + plot 局部格点 + plot 局部命中面 + plot 局部精确命中点。 */
    public record Hit(SubLevel body, BlockPos baseBlock, Direction baseFace, Vec3 baseHit) {
    }

    /**
     * 一次「碰撞箱级」命中：比 {@link Hit} 多带一个<b>被命中的子碰撞盒</b>（方块局部 0..1 坐标）。
     *
     * <p>模型模式的点/棱/面全部以它为准：台阶、楼梯、栅栏这类一个方块有多个（或非整格）碰撞盒时，
     * 选中的是那一个盒子自己的角点/棱/面。
     */
    public record BoxHit(SubLevel body, BlockPos baseBlock, Direction baseFace, Vec3 baseHit, AABB baseBox) {
    }

    /** 方块碰撞盒列表（碰撞箱为空时回退轮廓箱：火把、花这类没有碰撞但看得见的方块）。 */
    public static java.util.List<AABB> boxesOf(final SubLevel body, final BlockPos pos) {
        final Level level = body.getLevel();
        if (level == null) {
            return java.util.List.of();
        }
        final net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return java.util.List.of();
        }
        net.minecraft.world.phys.shapes.VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            shape = state.getShape(level, pos);
        }
        return shape.isEmpty() ? java.util.List.of() : shape.toAabbs();
    }

    /** 从一组子盒里挑出「射线命中点所在」的那个（都不包含时取最近的）。 */
    public static AABB pickBox(final java.util.List<AABB> boxes, final Vec3 localHit) {
        AABB best = null;
        double bestDistance = Double.MAX_VALUE;
        for (final AABB box : boxes) {
            if (box.inflate(1.0e-3).contains(localHit)) {
                return box;
            }
            final double dx = localHit.x - Math.max(box.minX, Math.min(box.maxX, localHit.x));
            final double dy = localHit.y - Math.max(box.minY, Math.min(box.maxY, localHit.y));
            final double dz = localHit.z - Math.max(box.minZ, Math.min(box.maxZ, localHit.z));
            final double distance = dx * dx + dy * dy + dz * dz;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = box;
            }
        }
        return best == null ? MateRef.FULL_BOX : best;
    }

    /**
     * 从任意视线（模型模式的自由相机）做一次位姿感知的方块射线，并且<b>只认物理结构</b>。
     *
     * <p>主世界的方块会在射线更近处挡路，所以命中主世界时把起点推到命中点之后再射一次。
     */
    @Nullable
    public static BoxHit raycastFrom(final Vec3 from, final Vec3 direction, final double range) {
        Vec3 origin = from;
        double left = range;
        for (int attempt = 0; attempt < 12 && left > 0.05; attempt++) {
            final BlockHitResult hit = clipRaw(origin, origin.add(direction.scale(left)));
            if (hit == null || hit.getType() == HitResult.Type.MISS) {
                return null;
            }
            final SubLevel body = Sable.HELPER.getContainingClient(hit.getLocation());
            if (body != null && !body.isRemoved()) {
                // 被隐藏的模型不可选（看不见的东西不该被选中，否则会出现「点空白却选到东西」）
                if (com.ovo.sablestopnow.client.model.ModelProperties.hidden(body.getUniqueId())) {
                    final Vec3 skip = hit.getLocation().add(direction.scale(0.01));
                    left -= skip.distanceTo(origin);
                    origin = skip;
                    continue;
                }
                return toBoxHit(body, hit);
            }
            // 打到了主世界：跳过这一段继续往前找结构
            final Vec3 next = hit.getLocation().add(direction.scale(0.01));
            left -= next.distanceTo(origin);
            origin = next;
        }
        return null;
    }

    /** 把一次方块命中补全成碰撞箱级命中。 */
    public static BoxHit toBoxHit(final SubLevel body, final BlockHitResult hit) {
        final Direction face = hit.getDirection();
        final double eps = 1.0e-4;
        final BlockPos block = BlockPos.containing(hit.getLocation().subtract(
                face.getStepX() * eps, face.getStepY() * eps, face.getStepZ() * eps));
        final Vec3 local = hit.getLocation().subtract(block.getX(), block.getY(), block.getZ());
        final AABB box = pickBox(boxesOf(body, block), local);
        return new BoxHit(body, block, face, hit.getLocation(), box);
    }

    /**
     * 一次位姿感知的方块射线（不看是不是结构，调用方自己判断）。
     *
     * <p>与现有 {@link #clip} 的区别：碰撞判定用 OUTLINE（看得见就能选），起点可以是任意位置。
     */
    @Nullable
    private static BlockHitResult clipRaw(final Vec3 from, final Vec3 to) {
        final Level level = Minecraft.getInstance().level;
        if (!(level instanceof final LevelPoseProviderExtension extension)) {
            return null;
        }
        final ClipContext context = new ClipContext(from, to, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, CollisionContext.empty());
        ((ClipContextExtension) (Object) context).sable$setIgnoredSubLevel(null);
        extension.sable$pushPoseSupplier(x -> ((ClientSubLevel) x).renderPose());
        try {
            return level.clip(context);
        } finally {
            extension.sable$popPoseSupplier();
        }
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
        final Direction face = hit.getDirection();
        // 命中点正好落在方块的边界平面上，而 BlockPos.containing（= floor）对 max 侧平面会进位：
        // up/east/south 面的命中点 y=130.000 会被算成 y=130 的那个「空气」方块，参考点于是整体
        // 偏了一格（预览会画到相邻一格上）。沿法线反向退一丁点再取整，就能拿回真正被命中的实体方块。
        final double eps = 1.0e-4;
        final BlockPos block = BlockPos.containing(hit.getLocation().subtract(
                face.getStepX() * eps, face.getStepY() * eps, face.getStepZ() * eps));
        return new Hit(body, block, face, hit.getLocation());
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
     * 方块级候选：按命中点在面内的位置就近判定为
     * <b>角点 / 棱心 / 棱 / 面心 / 面</b> 五类之一。
     *
     * <p>判定顺序（先特例后一般，五个区域互不重叠）：
     * <ol>
     *   <li>到最近角点的距离小于 {@link #CORNER_RADIUS} → <b>角点</b>（{@link RefKind#VERTEX}）；</li>
     *   <li>有一个贴近边界（即在某条棱上）：
     *       <ul>
     *         <li>沿该棱方向又贴近棱中点 → <b>棱心</b>（{@link RefKind#EDGE_CENTER}，纯点）；</li>
     *         <li>否则 → <b>棱</b>（{@link RefKind#EDGE}，带方向）；</li>
     *       </ul></li>
     *   <li>两个面内坐标都贴近 0（面中心）→ <b>面心</b>（{@link RefKind#FACE_CENTER}，纯点）；</li>
     *   <li>其余 → <b>面</b>（{@link RefKind#FACE}，带法线）。</li>
     * </ol>
     *
     * <p>面心/棱心刻意做成「纯点」（见 {@link RefKind#FACE_CENTER} 的说明）：它们只适合做点—点
     * 重合/距离这类位置配合，方向请直接选面或棱本身。
     *
     * @param body 目标结构（用于取 UUID）
     */
    public static MateRef blockCandidate(final SubLevel body, final Hit hit) {
        return classify(body, hit.baseBlock(), hit.baseFace(), hit.baseHit(), MateRef.FULL_BOX, true);
    }

    /** 碰撞箱级候选（模型模式用）：判定与 {@link #blockCandidate} 相同，但按被命中子盒的尺寸归一化。 */
    public static MateRef boxCandidate(final SubLevel body, final BoxHit hit) {
        return classify(body, hit.baseBlock(), hit.baseFace(), hit.baseHit(), hit.baseBox(),
                hit.baseBox().equals(MateRef.FULL_BOX));
    }

    /**
     * 五类就近判定的共同实现。
     *
     * @param fullCube 命中盒是否就是整格（整格的归一化坐标就是 1:1，不必除以尺寸）
     */
    private static MateRef classify(final SubLevel body, final BlockPos block, final Direction face,
                                    final Vec3 hitPoint, final AABB box, final boolean fullCube) {
        final Vector3d center = MateRef.faceCenter(block, face, box);
        final Vector3d u = MateRef.faceU(face);
        final Vector3d v = MateRef.faceV(face);

        final Vector3d offset = new Vector3d(hitPoint.x, hitPoint.y, hitPoint.z).sub(center);
        final double sizeU = fullCube ? 1.0 : Math.max(1.0e-4, MateRef.extentOf(box, u));
        final double sizeV = fullCube ? 1.0 : Math.max(1.0e-4, MateRef.extentOf(box, v));
        final double du = offset.dot(u) / sizeU;
        final double dv = offset.dot(v) / sizeV;
        final AABB stored = fullCube ? null : box;

        // 角点：按到最近角点的距离判（见 CORNER_RADIUS 的说明）。
        // 角点序号 bit0 = u 正负，bit1 = v 正负（与 MateRef.faceCorner 的约定一致）
        final double cornerDist = Math.hypot(0.5 - Math.abs(du), 0.5 - Math.abs(dv));
        if (cornerDist < CORNER_RADIUS) {
            final int index = (du > 0 ? 1 : 0) | (dv > 0 ? 2 : 0);
            return MateRef.block(body.getUniqueId(), RefKind.VERTEX, block, face, index, stored);
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
            // edge0/edge2 沿 u 方向延伸，edge1/edge3 沿 v 方向延伸
            final double along = (best == 0 || best == 2) ? du : dv;
            final RefKind kind = Math.abs(along) < MID_ZONE ? RefKind.EDGE_CENTER : RefKind.EDGE;
            return MateRef.block(body.getUniqueId(), kind, block, face, best, stored);
        }
        if (Math.max(Math.abs(du), Math.abs(dv)) < CENTER_ZONE) {
            return MateRef.block(body.getUniqueId(), RefKind.FACE_CENTER, block, face, -1, stored);
        }
        return MateRef.block(body.getUniqueId(), RefKind.FACE, block, face, -1, stored);
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
        return worldAxis(body, local);
    }

    /**
     * 把一个 plot 局部方向旋到世界系（只取旋转，不取平移/缩放）。
     *
     * <p>给「纯点」参考的预览用：面心/棱心在解算里刻意不带方向（见 {@link RefKind#FACE_CENTER}），
     * 但画出来时仍需要面内两轴 / 棱方向来摆那个十字与短棱柱。
     */
    @Nullable
    public static Vector3d worldAxis(final SubLevel body, final Vector3d local) {
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
