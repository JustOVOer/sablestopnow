package com.ovo.sablestopnow.mate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
 * <p>结构级参考用 {@code axis}（0=X, 1=Y, 2=Z）描述方向；中心点用 -1。
 * 结构级参考从 {@code plot.getBoundingBox()} 现算，因此结构被编辑后依然有效。
 */
public record MateRef(
        UUID body,
        RefKind kind,
        @Nullable BlockPos block,
        @Nullable Direction face,
        int feature,
        int axis) {

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

    /** 面心（plot 局部）。 */
    public static Vector3d faceCenter(final BlockPos block, final Direction face) {
        return new Vector3d(
                block.getX() + 0.5 + face.getStepX() * 0.5,
                block.getY() + 0.5 + face.getStepY() * 0.5,
                block.getZ() + 0.5 + face.getStepZ() * 0.5);
    }

    /** 面内第 {@code index}（0..3）个角点（plot 局部）。 */
    public static Vector3d faceCorner(final BlockPos block, final Direction face, final int index) {
        final Vector3d center = faceCenter(block, face);
        final Vector3d u = faceU(face);
        final Vector3d v = faceV(face);
        final double su = (index & 1) == 0 ? -0.5 : 0.5;
        final double sv = (index & 2) == 0 ? -0.5 : 0.5;
        return center.fma(su, u).fma(sv, v);
    }

    /** 面内第 {@code index}（0..3）条棱的中点（plot 局部）。 */
    public static Vector3d faceEdgeMidpoint(final BlockPos block, final Direction face, final int index) {
        final Vector3d a = faceCorner(block, face, index);
        final Vector3d b = faceCorner(block, face, (index + 1) & 3);
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

    /** 方块级参考。 */
    public static MateRef block(final UUID body, final RefKind kind, final BlockPos block,
                               final Direction face, final int feature) {
        return new MateRef(body, kind, block.immutable(), face, feature, -1);
    }

    /** 结构包围盒中心。 */
    public static MateRef center(final UUID body) {
        return new MateRef(body, RefKind.BODY_CENTER, null, null, -1, -1);
    }

    /** 结构包围盒主轴（axis: 0=X, 1=Y, 2=Z）。 */
    public static MateRef axis(final UUID body, final int axis) {
        return new MateRef(body, RefKind.BODY_AXIS, null, null, -1, axis);
    }

    /** 结构包围盒基准面（axis 为其法线轴）。 */
    public static MateRef plane(final UUID body, final int axis) {
        return new MateRef(body, RefKind.BODY_PLANE, null, null, -1, axis);
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
            case FACE -> "face@" + this.block + "/" + (this.face == null ? "?" : this.face.getName());
            case BODY_CENTER -> "center";
            case BODY_AXIS -> "axis" + this.axis;
            case BODY_PLANE -> "plane" + this.axis;
        };
    }
}
