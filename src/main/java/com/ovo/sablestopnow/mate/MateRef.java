package com.ovo.sablestopnow.mate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * 一个配合参考（配合的一端）。
 *
 * <p>存的是<b>描述</b>（哪个体、哪种几何、锚在哪个方块的哪个面/角/棱），而不是算好的世界坐标 ——
 * 因为物理结构会动、会被增删方块，世界坐标每次都从 {@code SubLevel.logicalPose()} 现算。
 *
 * <p>方块级参考用 {@code block}（<b>plot 局部</b>方块坐标，与 Sable 的 {@code validateAnchors} 同一空间）
 * 加 {@code face}（命中面）描述；{@code feature} 是面内索引：
 * <ul>
 *   <li>{@link RefKind#VERTEX}：0..3 = 面内角点；</li>
 *   <li>{@link RefKind#EDGE}：0..3 = 面内棱；</li>
 *   <li>{@link RefKind#FACE}：-1（面本身）。</li>
 * </ul>
 *
 * <p><b>几何基准是方块碰撞箱</b>：{@code box} 是「命中方块的本地方盒」（方块局部 0..1 坐标），
 * 台阶/楼梯这类一个方块有多个碰撞盒时，点/棱/面都取<b>那个被命中的子盒</b>，而不是整格。
 * {@code box == null} 表示整格（{@link #FULL_BOX}），这也是旧存档与结构级参考的取值。
 *
 * <p>结构级参考用 {@code axis}（0=X, 1=Y, 2=Z）描述方向；中心点用 -1。
 * 结构级参考从 {@code plot.getBoundingBox()} 现算，因此结构被编辑后依然有效。
 */
public record MateRef(
        UUID body,
        RefKind kind,
        @Nullable BlockPos block,
        @Nullable Direction face,
        int feature,
        int axis,
        @Nullable AABB box) {

    /** 整格（方块局部 0..1）。 */
    public static final AABB FULL_BOX = new AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0);

    // ============ 面内局部基 ============

    /** 面内第一基向量（u）。 */
    public static Vector3d faceU(final Direction face) {
        return switch (face.getAxis()) {
            case X -> new Vector3d(0, 0, 1);
            case Y -> new Vector3d(1, 0, 0);
            case Z -> new Vector3d(1, 0, 0);
        };
    }

    /** 面内第二基向量（v），与 u 正交。 */
    public static Vector3d faceV(final Direction face) {
        return switch (face.getAxis()) {
            case X -> new Vector3d(0, 1, 0);
            case Y -> new Vector3d(0, 0, 1);
            case Z -> new Vector3d(0, 1, 0);
        };
    }

    /** 盒在某个单位方向上的尺度（只对轴对齐方向有意义）。 */
    public static double extentOf(final AABB box, final Vector3d dir) {
        return Math.abs(dir.x) * box.getXsize() + Math.abs(dir.y) * box.getYsize()
                + Math.abs(dir.z) * box.getZsize();
    }

    private static double extent(final AABB box, final Vector3d dir) {
        return extentOf(box, dir);
    }

    /** 面心（plot 局部）。 */
    public static Vector3d faceCenter(final BlockPos block, final Direction face) {
        return faceCenter(block, face, FULL_BOX);
    }

    /** 面心（plot 局部）：把盒的该面摆到命中面所在的平面上。 */
    public static Vector3d faceCenter(final BlockPos block, final Direction face, final AABB box) {
        return new Vector3d(
                block.getX() + axisCenter(box.minX, box.maxX, face.getStepX()),
                block.getY() + axisCenter(box.minY, box.maxY, face.getStepY()),
                block.getZ() + axisCenter(box.minZ, box.maxZ, face.getStepZ()));
    }

    /** 面法线轴上取「面所在的坐标」，其余轴取盒中心。 */
    private static double axisCenter(final double min, final double max, final int step) {
        if (step > 0) {
            return max;
        }
        if (step < 0) {
            return min;
        }
        return (min + max) * 0.5;
    }

    /** 面内第 {@code index}（0..3）个角点（plot 局部）。 */
    public static Vector3d faceCorner(final BlockPos block, final Direction face, final int index) {
        return faceCorner(block, face, index, FULL_BOX);
    }

    /** 面内第 {@code index}（0..3）个角点（plot 局部），按碰撞盒子盒取尺寸。 */
    public static Vector3d faceCorner(final BlockPos block, final Direction face, final int index, final AABB box) {
        final Vector3d center = faceCenter(block, face, box);
        final Vector3d u = faceU(face);
        final Vector3d v = faceV(face);
        final double su = ((index & 1) == 0 ? -0.5 : 0.5) * extent(box, u);
        final double sv = ((index & 2) == 0 ? -0.5 : 0.5) * extent(box, v);
        return center.fma(su, u).fma(sv, v);
    }

    /** 面内第 {@code index}（0..3）条棱的中点（plot 局部）。 */
    public static Vector3d faceEdgeMidpoint(final BlockPos block, final Direction face, final int index) {
        return faceEdgeMidpoint(block, face, index, FULL_BOX);
    }

    /** 面内第 {@code index}（0..3）条棱的中点（plot 局部），按碰撞盒子盒取尺寸。 */
    public static Vector3d faceEdgeMidpoint(final BlockPos block, final Direction face, final int index,
                                            final AABB box) {
        // ⚠ 角点序号不是「沿着面转一圈」的顺序：bit0 = u 正负、bit1 = v 正负，
        // 所以绕面的正确顺序是 0→1→3→2（见 faceCorner）。
        // 以前这里是 (index, index+1)，于是棱 1 = corner1–corner2、棱 2 = corner2–corner3 都取到了
        // **对角线**，而对角线的中点恰好是面心 —— 玩家瞄准一条棱，锚点却落在面正中间，
        // 高亮画出来就是「一条穿过面中心、连着对边的线」（用户实测反复反馈的「选到面中线」）。
        // 棱序号与 faceEdgeDirection 一致：0=+u, 1=+v, 2=-u, 3=-v。
        final int[] ring = {0, 1, 3, 2};
        final Vector3d a = faceCorner(block, face, ring[index & 3], box);
        final Vector3d b = faceCorner(block, face, ring[(index + 1) & 3], box);
        return a.add(b).mul(0.5);
    }

    /** 面内第 {@code index}（0..3）条棱的方向（单位向量，plot 局部）。 */
    public static Vector3d faceEdgeDirection(final Direction face, final int index) {
        final Vector3d u = faceU(face);
        final Vector3d v = faceV(face);
        return switch (index & 3) {
            case 0 -> new Vector3d(u);
            case 1 -> new Vector3d(v);
            case 2 -> new Vector3d(u).negate();
            default -> new Vector3d(v).negate();
        };
    }

    // ============ 工厂 ============

    /** 方块级参考（整格）。 */
    public static MateRef block(final UUID body, final RefKind kind, final BlockPos block,
                               final Direction face, final int feature) {
        return block(body, kind, block, face, feature, null);
    }

    /** 方块级参考（指定碰撞盒子盒；{@code box == null} 表示整格）。 */
    public static MateRef block(final UUID body, final RefKind kind, final BlockPos block,
                               final Direction face, final int feature, @Nullable final AABB box) {
        return new MateRef(body, kind, block.immutable(), face, feature, -1, box);
    }

    /** 结构包围盒中心。 */
    public static MateRef center(final UUID body) {
        return new MateRef(body, RefKind.BODY_CENTER, null, null, -1, -1, null);
    }

    /** 结构包围盒主轴（axis: 0=X, 1=Y, 2=Z）。 */
    public static MateRef axis(final UUID body, final int axis) {
        return new MateRef(body, RefKind.BODY_AXIS, null, null, -1, axis, null);
    }

    /** 结构包围盒基准面（axis 为其法线轴）。 */
    public static MateRef plane(final UUID body, final int axis) {
        return new MateRef(body, RefKind.BODY_PLANE, null, null, -1, axis, null);
    }

    /** 该参考使用的盒子（null → 整格）。 */
    public AABB boxOrDefault() {
        return this.box == null ? FULL_BOX : this.box;
    }

    // ============ 校验 ============

    /** 描述本身是否自洽（不依赖具体世界状态）。 */
    public boolean isWellFormed() {
        if (this.kind.isBlockLevel()) {
            return this.block != null && this.face != null;
        }
        return true;
    }

    /** 是否拥有可用的方向（结构级轴向参考与方块级带方向的参考）。 */
    public boolean hasDirection() {
        return this.kind.hasDirection();
    }

    /** 该参考能否参与 {@code type}：方向类配合要求两侧都带方向。 */
    public boolean supports(final MateType type) {
        if (!this.isWellFormed()) {
            return false;
        }
        return !type.requiresDirection() || this.hasDirection();
    }

    /** 是否与另一个参考指向同一个体（同体不能配合）。 */
    public boolean sameBody(final MateRef other) {
        return this.body.equals(other.body);
    }

    /** GUI/日志用的简短描述。 */
    public String describe() {
        return switch (this.kind) {
            case VERTEX -> "vertex@" + this.block + "#" + this.feature;
            case EDGE -> "edge@" + this.block + "#" + this.feature;
            case EDGE_CENTER -> "edgeCenter@" + this.block + "#" + this.feature;
            case FACE -> "face@" + this.block + "/" + (this.face == null ? "?" : this.face.getName());
            case FACE_CENTER -> "faceCenter@" + this.block + "/"
                    + (this.face == null ? "?" : this.face.getName());
            case BODY_CENTER -> "center";
            case BODY_AXIS -> "axis" + this.axis;
            case BODY_PLANE -> "plane" + this.axis;
        };
    }
}
