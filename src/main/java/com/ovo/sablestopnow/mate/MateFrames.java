package com.ovo.sablestopnow.mate;

import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.world.phys.AABB;
import dev.ryanhcode.sable.sublevel.SubLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 配合的几何求解：把一条 {@link Mate} 变成「B 的吸附位姿 + 一个 6 轴关节的坐标系与锁定轴集合」。
 *
 * <h2>为什么是「锁定轴 + 先吸附」</h2>
 * Sable 的 {@code GenericConstraintConfiguration} 表达的是一个 rapier 通用关节：它把
 * <b>frame1（A 局部）</b> 与世界中的 <b>frame2（B 局部）</b> 拉到重合，{@code lockedAxes} 决定哪些自由度
 * 被消掉（{@code LINEAR_*} 是位置，{@code ANGULAR_*} 是姿态）。而「锁定某个轴」的语义是
 * <b>该轴上的相对偏移归零</b>——所以它只能表达「等于装配时那个状态」，不能表达「距离 3 格」或「夹角 90°」。
 *
 * <p>因此本系统的做法与 SolidWorks 一致：<b>先把 B 吸附到位</b>（让参考几何正好满足配合的定义），
 * 再以零误差建立关节。距离/相切这类「非零偏移」用 {@code setLimit(axis, v, v)} 而不用 lock。
 *
 * <h2>坐标系怎么装配</h2>
 * 关节坐标系的两个 frame 必须<b>在吸附后的姿态下就已经重合</b>（否则一建出来就会互相拉扯）。
 * 于是先在<b>世界系</b>里装配一个正交基 {@code W}：
 * <ul>
 *   <li>{@code W.z} = 配合轴（A 侧方向，或 A 的姿态）—— 所有方向的表达都以它为准；</li>
 *   <li>{@code W.x} = 优先取 B 侧方向在垂直于 {@code W.z} 平面上的分量。</li>
 * </ul>
 * 然后取 {@code M1 = R_A⁻¹·W}、{@code M2 = R_B⁻¹·W}，两者在世界系里必然都等于 {@code W} → 零误差。
 *
 * <p>锁定轴就落在这个 W 上，于是语义变得非常直观：
 * <ul>
 *   <li>锁 {@code ANGULAR_X,Y} ⇒ 两侧基座只能绕共同 Z 相对转动 ⇒ <b>Z 上放的方向保持不动</b>。
 *       平行配合把「两方向」都放在 Z 上即可保持平行；</li>
 *   <li>垂直配合的关键：把 <b>B 侧方向放在 M2 的 X 轴上</b>（即 {@code W.x} = B 的方向）。
 *       锁 {@code ANGULAR_X,Y} ⇒ {@code W.z} 两侧相等且正交于 {@code W.x} ⇒ B 的方向恒垂直于 A 的方向。
 *       这是「90°」无法用锁定轴直接表达、但可以靠换轴位绕过去的绕法。</li>
 * </ul>
 *
 * <h2>为什么参数是 Pose + BoundingBox 而不是 SubLevel</h2>
 * 求解本身只依赖两件事：两侧的<b>位姿</b>和两侧的<b>包围盒</b>。把它们做成入参而不是去要 {@code SubLevel}，
 * 一来结构级参考（中心/主轴/基准面）本来就是从包围盒现算的，二来这套推导是整个功能里最容易出错的部分，
 * 解耦之后可以在纯 JVM 里直接验证「两个 frame 在世界系里是否真的重合」这条核心不变量
 * （见 {@code tools/MateFramesCheck.java}）。
 */
public final class MateFrames {

    private MateFrames() {
    }

    /** 参考在<b>所属结构 plot 局部</b>空间下解出的点与方向。 */
    public record Resolved(Vector3d point, @Nullable Vector3d direction) {
    }

    /**
     * 求解结果。
     *
     * @param moveB           是否需要吸附（LOCK 不需要，它保留当前相对位姿）
     * @param newPositionB    B 吸附后的世界位置（{@code logicalPose().position()} 的目标值）
     * @param newOrientationB B 吸附后的世界朝向
     * @param posA            frame1 原点（A 的 plot 局部）
     * @param orientationA    frame1 朝向（A 的 plot 局部）
     * @param posB            frame2 原点（B 的 plot 局部）
     * @param orientationB    frame2 朝向（B 的 plot 局部）
     * @param locked          需要锁死的轴
     * @param limitAxis       该配合「用数值占用」的轴（{@link MateType#DISTANCE}/{@link MateType#LIMIT}），可为 null。
     *                        距离的数值已经做进 frame 几何（{@link #POSITION_VALUE_IN_FRAME}），
     *                        限制则交给求解器的区间约束（{@code setLimit(axis, min, max)}）
     * @param limitValue      该轴的数值 / 区间下限
     * @param limitMax        区间上限（非区间类型等于 {@link #limitValue}）
     */
    public record Plan(
            boolean moveB,
            Vector3d newPositionB,
            Quaterniond newOrientationB,
            Vector3d posA,
            Quaterniond orientationA,
            Vector3d posB,
            Quaterniond orientationB,
            Set<ConstraintJointAxis> locked,
            @Nullable ConstraintJointAxis limitAxis,
            double limitValue,
            double limitMax) {

        /** 该配合是否使用「区间」而不是精确值。 */
        public boolean isRange() {
            return this.limitMax > this.limitValue + 1.0e-9;
        }
    }

    /** 求解失败的原因（用于给玩家提示）。 */
    public static final class Unsolvable extends Exception {
        public Unsolvable(final String message) {
            super(message);
        }
    }

    // ============ 参考解算 ============

    /**
     * 把一个参考解到所属结构的 plot 局部空间。
     *
     * @param bounds 所属结构的 plot 包围盒（结构级参考从它派生）
     */
    public static Resolved resolveLocal(final BoundingBox3ic bounds, final MateRef ref) {
        final AABB box = ref.boxOrDefault();
        return switch (ref.kind()) {
            case VERTEX -> new Resolved(
                    MateRef.faceCorner(ref.block(), ref.face(), ref.feature(), box), null);
            case EDGE -> new Resolved(
                    MateRef.faceEdgeMidpoint(ref.block(), ref.face(), ref.feature(), box),
                    MateRef.faceEdgeDirection(ref.face(), ref.feature()));
            case FACE -> new Resolved(
                    MateRef.faceCenter(ref.block(), ref.face(), box),
                    new Vector3d(ref.face().getStepX(), ref.face().getStepY(), ref.face().getStepZ()));
            // 面心/棱心：同一个锚点，但**不含方向**（纯点参考，只参与位置类配合）。
            // 必须与 RefKind.hasDirection() 一致，否则过约束检测会算错角向自由度。
            case FACE_CENTER -> new Resolved(MateRef.faceCenter(ref.block(), ref.face(), box), null);
            case EDGE_CENTER -> new Resolved(
                    MateRef.faceEdgeMidpoint(ref.block(), ref.face(), ref.feature(), box), null);
            case BODY_CENTER -> new Resolved(plotCenter(bounds), null);
            case BODY_AXIS -> new Resolved(plotCenter(bounds), unitAxis(ref.axis()));
            case BODY_PLANE -> new Resolved(plotCenter(bounds), unitAxis(ref.axis()));
        };
    }

    /** 便利重载：直接从结构取包围盒。 */
    public static Resolved resolveLocal(final SubLevel sub, final MateRef ref) {
        return resolveLocal(boundsOf(sub), ref);
    }

    private static Vector3d unitAxis(final int axis) {
        return switch (axis) {
            case 0 -> new Vector3d(1, 0, 0);
            case 1 -> new Vector3d(0, 1, 0);
            default -> new Vector3d(0, 0, 1);
        };
    }

    /** 结构包围盒中心（plot 局部，方块占 [min, max] 闭区间）。 */
    public static Vector3d plotCenter(final BoundingBox3ic bounds) {
        return new Vector3d(
                (bounds.minX() + bounds.maxX() + 1) * 0.5,
                (bounds.minY() + bounds.maxY() + 1) * 0.5,
                (bounds.minZ() + bounds.maxZ() + 1) * 0.5);
    }

    /** 便利重载：直接从结构取中心。 */
    public static Vector3d plotCenter(final SubLevel sub) {
        return plotCenter(boundsOf(sub));
    }

    /** 结构包围盒（plot 局部）。公开版：服务端的冲突判定要在任意位姿上复算测量值。 */
    public static BoundingBox3ic bounds(final SubLevel sub) {
        return boundsOf(sub);
    }

    private static BoundingBox3ic boundsOf(final SubLevel sub) {
        return sub.getPlot().getBoundingBox();
    }

    /** 参考点在世界系的位置。 */
    public static Vector3d worldPoint(final Pose3dc pose, final Resolved resolved) {
        return pose.transformPosition(resolved.point(), new Vector3d());
    }

    /** 参考方向在世界系的单位向量；无方向返回 null。 */
    @Nullable
    public static Vector3d worldDirection(final Pose3dc pose, final Resolved resolved) {
        if (resolved.direction() == null) {
            return null;
        }
        final Vector3d out = pose.orientation().transform(new Vector3d(resolved.direction()));
        if (out.lengthSquared() < 1e-12) {
            return null;
        }
        return out.normalize();
    }

    /** 便利重载。 */
    public static Vector3d worldPoint(final SubLevel sub, final Resolved resolved) {
        return worldPoint(sub.logicalPose(), resolved);
    }

    /** 便利重载。 */
    @Nullable
    public static Vector3d worldDirection(final SubLevel sub, final Resolved resolved) {
        return worldDirection(sub.logicalPose(), resolved);
    }

    // ============ 对外入口（SubLevel 便利重载） ============

    /**
     * 求解一条配合，并给出「把 B 吸附到位」的位姿。<b>只在创建配合时用</b>。
     *
     * @param subA 参考 A 所在结构（保持不动）
     * @param subB 参考 B 所在结构（被吸附）
     */
    public static Plan solve(final SubLevel subA, final SubLevel subB, final MateType type,
                             final MateRef refA, final MateRef refB,
                             final double value, final boolean flip) throws Unsolvable {
        return solve(subA, subB, type, refA, refB, value, value, flip);
    }

    /** 带区间上限的重载（只有 {@link MateType#LIMIT} 用得上第二个数值）。 */
    public static Plan solve(final SubLevel subA, final SubLevel subB, final MateType type,
                             final MateRef refA, final MateRef refB,
                             final double value, final double value2,
                             final boolean flip) throws Unsolvable {
        return solve(subA.logicalPose(), boundsOf(subA), subB.logicalPose(), boundsOf(subB),
                type, refA, refB, value, value2, flip, true);
    }

    /**
     * 只算关节坐标系与锁定轴，<b>不吸附</b>（按两侧当前位姿原样建立）。
     *
     * <p>用于关节失效后的重建：约束是相对<b>参考点</b>表达的（锁轴归零 / 距离钉在 v），
     * 定义上不需要重新吸附；而重新吸附会在结构被外力推歪后把它突然拽回去，反而更糟。
     */
    public static Plan frames(final SubLevel subA, final SubLevel subB, final MateType type,
                              final MateRef refA, final MateRef refB,
                              final double value, final double value2,
                              final boolean flip) throws Unsolvable {
        return solve(subA.logicalPose(), boundsOf(subA), subB.logicalPose(), boundsOf(subB),
                type, refA, refB, value, value2, flip, false);
    }

    public static Plan frames(final SubLevel subA, final SubLevel subB, final MateType type,
                              final MateRef refA, final MateRef refB,
                              final double value, final boolean flip) throws Unsolvable {
        return frames(subA, subB, type, refA, refB, value, value, flip);
    }

    /** 与 {@link #frames(SubLevel, SubLevel, MateType, MateRef, MateRef, double, boolean)} 同样的便利重载。 */
    public static Plan frames(final Pose3dc poseA, final BoundingBox3ic boundsA,
                              final Pose3dc poseB, final BoundingBox3ic boundsB,
                              final MateType type, final MateRef refA, final MateRef refB,
                              final double value, final boolean flip) throws Unsolvable {
        return solve(poseA, boundsA, poseB, boundsB, type, refA, refB, value, value, flip, false);
    }

    /** 带区间上限的便利重载（「限制」用得上）。 */
    public static Plan frames(final Pose3dc poseA, final BoundingBox3ic boundsA,
                              final Pose3dc poseB, final BoundingBox3ic boundsB,
                              final MateType type, final MateRef refA, final MateRef refB,
                              final double value, final double value2, final boolean flip) throws Unsolvable {
        return solve(poseA, boundsA, poseB, boundsB, type, refA, refB, value, value2, flip, false);
    }

    /**
     * 一对结构上的「约束账本」：记录每条被占用的自由度轴及其目标值，用来判定配合冲突。
     *
     * <p>做成纯数据 + 纯逻辑（不碰 {@code MateData} / 世界）是为了能离线验证 ——
     * 冲突判定直接决定「如果冲突则不配合」这条需求，值得有自己的回归测试。
     *
     * <p><b>判据只有一条：同一条自由度轴被钉到不一致的值/区间</b>
     * （锁定 ⇒ [0,0]，距离 ⇒ [v,v]，限制 ⇒ [min,max]）。两条区间只要<b>相交</b>就不算冲突。
     *
     * <p>为什么不是「占用同一条轴就算冲突」：重合 + 平行这种组合是<b>冗余但一致</b>的
     * （两条都要求该轴为 0），拦下来只会妨碍正常建模。
     *
     * <p>为什么也不是「占用轴数超过 3」：账本按轴去重，每种方向最多只有 3 条轴，
     * 这个上限<b>永远不可能被突破</b>——写成判据是死代码。真正的矛盾一定表现为「同轴不一致」。
     */
    public static final class Budget {

        private static final double EPSILON = 1.0e-6;

        /** 每条被占用的轴 → 允许的取值区间 [lo, hi]（锁定/精确值就是退化区间）。 */
        private final Map<ConstraintJointAxis, double[]> ranges = new EnumMap<>(ConstraintJointAxis.class);

        /**
         * 并入一条配合的占用（单值版本；区间类型请用带 {@code value2} 的重载）。
         *
         * @return false 表示与已有内容冲突
         */
        public boolean add(final MateType type, final MateRef a, final MateRef b, final double value) {
            return add(type, a, b, value, type.needsSecondValue() ? type.defaultValue2() : value);
        }

        /** 并入一条配合的占用（区间版本）。 */
        public boolean add(final MateType type, final MateRef a, final MateRef b,
                           final double value, final double value2) {
            for (final ConstraintJointAxis axis : lockedAxes(type, a, b)) {
                if (this.conflicts(axis, 0.0, 0.0)) {
                    return false;
                }
            }
            final ConstraintJointAxis limit = limitAxisOf(type);
            if (limit == null) {
                return true;
            }
            return !type.needsSecondValue()
                    ? !this.conflicts(limit, value, value)
                    : !this.conflicts(limit, Math.min(value, value2), Math.max(value, value2));
        }

        /** 与已有区间是否矛盾：两条区间必须相交。 */
        private boolean conflicts(final ConstraintJointAxis axis, final double lo, final double hi) {
            final double[] previous = this.ranges.putIfAbsent(axis, new double[] { lo, hi });
            return previous != null && (previous[1] < lo - EPSILON || previous[0] > hi + EPSILON);
        }

        /** 已占用的轴数（供测试断言「这套组合到底占了几个自由度」）。 */
        public int size() {
            return this.ranges.size();
        }
    }

    // ============ 冲突判定（几何一致性） ============

    /** 冲突判定的位置容差（格）。比求解精度宽松：只拦「明显做不到」，不拦数值漂移。 */
    public static final double CONFLICT_POSITION_TOLERANCE = 0.05;
    /** 冲突判定的角度容差（度）。 */
    public static final double CONFLICT_ANGLE_TOLERANCE = 3.0;

    /**
     * 在给定的一对位姿下，这条配合是否<b>几何上成立</b>；不成立时返回原因（诊断用），成立返回 null。
     *
     * <h2>为什么光有自由度账本不够</h2>
     * {@link Budget} 只能发现「同一条轴被钉到不一致的数值」。它发现不了下面这类矛盾：
     * 先建了点点配合（锁死三个平移自由度），再给「在这个约束下根本合不到一起」的两个面建面面重合
     * ——账本看两条都要求 LINEAR_Z = 0，判定为「冗余但一致」，于是两个关节都被建出来，
     * 一个要把 B 拉到点上、另一个要把 B 转到面上，互相拉扯 ⇒ 模型持续抽搐（用户实测反馈）。
     *
     * <p>这里补的就是那一半：把 B 摆到新配合要求的位姿上，逐条检查已有配合的<b>几何残差</b>。
     * 判据与关节真正锁的东西一一对应（面面重合锁法向间隙 + 法线平行，点点锁三点距离……），
     * 所以不会出现「账本说成立、关节却在打架」的错位。
     */
    @Nullable
    public static String unsatisfiedReason(final MateType type, final MateRef refA, final MateRef refB,
                                           final double value, final double value2,
                                           final Pose3dc poseA, final BoundingBox3ic boundsA,
                                           final Pose3dc poseB, final BoundingBox3ic boundsB) {
        final Resolved ra = resolveLocal(boundsA, refA);
        final Resolved rb = resolveLocal(boundsB, refB);
        final Vector3d pa = worldPoint(poseA, ra);
        final Vector3d pb = worldPoint(poseB, rb);
        final Vector3d da = ra.direction() == null ? null : worldDirection(poseA, ra);
        final Vector3d db = rb.direction() == null ? null : worldDirection(poseB, rb);
        final double lo = Math.min(value, value2);
        final double hi = Math.max(value, value2);

        switch (type) {
            case LOCK -> {
                // 「保持当前相对位姿」：由调用方比较候选位姿与当前位姿（这里没有「当前」可比）
                return null;
            }
            case COINCIDENT -> {
                if (da == null && db == null) {
                    // 点—点：三个平移自由度全锁 ⇒ 两个锚点必须重合
                    return pa.distance(pb) <= CONFLICT_POSITION_TOLERANCE
                            ? null : "point gap " + pa.distance(pb);
                }
                if (da != null && db != null) {
                    // 面—面 / 基准面—基准面：法向间隙 0 + 法线平行（面内平移与自转自由）
                    final Vector3d n = new Vector3d(da).normalize();
                    final double gap = Math.abs(new Vector3d(pb).sub(pa).dot(n));
                    if (gap > CONFLICT_POSITION_TOLERANCE) {
                        return "face gap " + gap;
                    }
                    final double align = Math.abs(n.dot(new Vector3d(db).normalize()));
                    return align >= 1.0 - 1.0e-3 ? null : "face normal " + align;
                }
                // 点落在面/线上：只有法向间隙被锁
                final Vector3d n = new Vector3d(da != null ? da : db).normalize();
                final double gap = Math.abs(new Vector3d(pb).sub(pa).dot(n));
                return gap <= CONFLICT_POSITION_TOLERANCE ? null : "offset " + gap;
            }
            case PARALLEL -> {
                if (da == null || db == null) {
                    return null;
                }
                return Math.abs(new Vector3d(da).normalize().dot(new Vector3d(db).normalize()))
                        >= 1.0 - 1.0e-3 ? null : "not parallel";
            }
            case PERPENDICULAR -> {
                if (da == null || db == null) {
                    return null;
                }
                return Math.abs(new Vector3d(da).normalize().dot(new Vector3d(db).normalize()))
                        <= 1.0e-3 ? null : "not perpendicular";
            }
            case ANGLE -> {
                if (da == null || db == null) {
                    return null;
                }
                final double dot = Math.max(-1.0, Math.min(1.0,
                        new Vector3d(da).normalize().dot(new Vector3d(db).normalize())));
                final double degrees = Math.toDegrees(Math.acos(dot));
                return Math.abs(degrees - value) <= CONFLICT_ANGLE_TOLERANCE
                        ? null : "angle " + degrees;
            }
            case DISTANCE -> {
                final double d = measureDistance(poseA, boundsA, refA, poseB, boundsB, refB);
                return Math.abs(d - value) <= CONFLICT_POSITION_TOLERANCE ? null : "distance " + d;
            }
            case LIMIT -> {
                final double d = measureDistance(poseA, boundsA, refA, poseB, boundsB, refB);
                return d >= lo - CONFLICT_POSITION_TOLERANCE && d <= hi + CONFLICT_POSITION_TOLERANCE
                        ? null : "limit " + d;
            }
            default -> {
                return null;
            }
        }
    }

    // ============ 对齐方向的自动选取 ============

    /**
     * 自动选取「同向 / 反向对齐」，返回 true 表示反向。
     *
     * <p>「选满两个自动成配」时玩家<b>没法事先声明</b>要对齐还是反向，而面法线是指向材料<b>外部</b>的，
     * 所以硬编码一个方向会让结构莫名其妙翻 180°——比如选 A 的顶面（法线 +Y）与 B 的底面（法线 −Y）
     * 本该「把 B 叠上去」，同向对齐却会把 B 翻转过来倒扣。
     *
     * <p>判据只有一条：看两侧方向<b>当前</b>是同向还是反向，取不需要转动的那一个。
     * 于是吸附退化成纯平移，结果最符合直觉；玩家之后可以在配合界面里切换。
     */
    public static boolean suggestFlip(final SubLevel subA, final SubLevel subB, final MateType type,
                                      final MateRef refA, final MateRef refB) {
        if (!type.supportsAlignment()) {
            return false;
        }
        return suggestFlip(worldDirection(subA.logicalPose(), resolveLocal(subA, refA)),
                worldDirection(subB.logicalPose(), resolveLocal(subB, refB)));
    }

    /**
     * 判据本体（拆出来是为了能离线验证）：两侧方向<b>当前反向</b> ⇒ 采用反向对齐。
     *
     * <p>任一侧没有方向（顶点/中心）时退回同向——反正解算器在没有方向时也不会转。
     */
    public static boolean suggestFlip(@Nullable final Vector3dc uA, @Nullable final Vector3dc uB) {
        if (uA == null || uB == null) {
            return false;
        }
        return uA.dot(uB) < 0.0;
    }

    // ============ 主求解 ============

    /**
     * 求解一条配合（纯几何，不碰世界对象）。
     *
     * @param poseA   A 的位姿（保持不动）
     * @param boundsA A 的 plot 包围盒
     * @param poseB   B 的位姿（{@code snap} 为真时会被吸附）
     * @param boundsB B 的 plot 包围盒
     * @param snap    是否计算吸附位姿；false 表示只算关节坐标系
     */
    public static Plan solve(final Pose3dc poseA, final BoundingBox3ic boundsA,
                             final Pose3dc poseB, final BoundingBox3ic boundsB,
                             final MateType type, final MateRef refA, final MateRef refB,
                             final double value, final boolean flip,
                             final boolean snap) throws Unsolvable {
        return solve(poseA, boundsA, poseB, boundsB, type, refA, refB, value, value, flip, snap);
    }

    /**
     * 求解一条配合（纯几何，不碰世界对象）。
     *
     * @param poseA   A 的位姿（保持不动）
     * @param boundsA A 的 plot 包围盒
     * @param poseB   B 的位姿（{@code snap} 为真时会被吸附）
     * @param boundsB B 的 plot 包围盒
     * @param value   距离/角度；区间类型时是下限
     * @param value2  区间类型的上限（其余类型忽略）
     * @param snap    是否计算吸附位姿；false 表示只算关节坐标系
     */
    public static Plan solve(final Pose3dc poseA, final BoundingBox3ic boundsA,
                             final Pose3dc poseB, final BoundingBox3ic boundsB,
                             final MateType type, final MateRef refA, final MateRef refB,
                             final double value, final double value2, final boolean flip,
                             final boolean snap) throws Unsolvable {
        if (refA.sameBody(refB)) {
            throw new Unsolvable("mate.error.same_body");
        }
        if (!refA.supports(type) || !refB.supports(type)) {
            throw new Unsolvable("mate.error.needs_direction");
        }

        final Resolved ra = resolveLocal(boundsA, refA);
        final Resolved rb = resolveLocal(boundsB, refB);

        final Vector3d qA = worldPoint(poseA, ra);
        final Vector3d qB = worldPoint(poseB, rb);
        final Vector3d uA = worldDirection(poseA, ra);
        final Vector3d uB = worldDirection(poseB, rb);

        // ---- 1) 姿态：把 B 侧方向转到目标方向 ----
        // 方向目标**不再看 snap**。
        //
        // 原来只在吸附时才算目标方向，重建（关节失效后按当前位姿重算）时给 null —— 于是
        // {@code buildWorldFrame} 退化成「拿 B 当前的方向当基准」，锁死 AX/AY 等于把**当前**这个
        // 方向（可能已经被推歪、或者是旧的角度值）当成目标钉住。表现就是玩家反馈的
        // 「角度配合约束不够强」：改数值不生效、被撞一下之后角度就永久错了。
        // 方向本来就是这些配合的定义的一部分，重建时必须重新约束到目标上。
        final Vector3d uTarget = orientationTarget(type, uA, uB, value, flip, refA, refB);
        Quaterniond newOrientation = new Quaterniond(poseB.orientation());
        if (uTarget != null && uB != null) {
            final Quaterniond fix = new Quaterniond().rotationTo(uB, uTarget);
            newOrientation = fix.mul(newOrientation).normalize();
        }

        // ---- 2) 旋转后 B 侧参考点的世界位置（位置暂时不变）----
        final Pose3d rotatedB = new Pose3d(
                new Vector3d(poseB.position()), newOrientation,
                new Vector3d(poseB.rotationPoint()), new Vector3d(poseB.scale()));
        final Vector3d qB2 = rotatedB.transformPosition(new Vector3d(rb.point()), new Vector3d());
        // 旋转之后 B 侧的方向：面在 B 侧时测量轴必须用它（见下面的 line—面 分支）
        final Vector3d uB2 = worldDirection(rotatedB, rb);

        // ---- 3) 平移：消掉被约束方向上的误差（最小移动）----
        final ConstraintJointAxis offsetAxis = offsetAxis(type);
        final boolean lineA = isLine(refA);
        final boolean lineB = isLine(refB);
        final boolean planeA = isPlane(refA);
        final boolean planeB = isPlane(refB);
        final boolean linePair = lineA && lineB;
        final boolean lineWithPlane = lineA != lineB && (lineA || lineB) && (planeA || planeB);
        final boolean lineInvolved = lineA || lineB;
        final boolean ranged = type.needsSecondValue();
        final double rangeMin = Math.min(value, value2);
        final double rangeMax = Math.max(value, value2);

        // 关节坐标系的 x 轴（优先方向）：棱参与的配合取**棱方向**。
        // 这样 z（测量轴/垂直方向）与 x（棱方向）正交，y = z × x 就是「另一条垂直方向」——
        // 棱之间的偏移有两个垂直分量，只锁一个的话结构仍然能被拉开（玩家反馈「极易被拉开」）。
        final Vector3d frameX = lineInvolved ? (lineA ? uA : uB) : null;

        // z 轴（配合轴 / 距离的测量轴）：
        //   线—线 → 垂直于棱的方向（两条棱的公垂）；线—面 → **面的法线**；
        //   线—点 → 点到棱的垂直方向；面—面/面—点 → 面的法线；点—点 → 两点当前连线。
        // 总之**绝不**用棱方向当 z（那等于让两条棱对滑，垂直偏移根本没被约束）。
        final Vector3d mateAxis;
        final boolean lineMate = lineInvolved
                && (type == MateType.COINCIDENT || type == MateType.DISTANCE || type == MateType.LIMIT);
        if (lineMate) {
            if (linePair) {
                mateAxis = perpendicularBetween(qA, qB2, uA);
            } else if (lineWithPlane) {
                // 线—面：沿面法线量（线落在面内 / 到面的距离），不是沿线量。
                // ⚠ 面在 B 侧时必须用**旋转之后**的法线：上面刚把 B 转进面内，
                // 用转之前的法线当测量轴会让吸附落在错的平面上（差一整个角度）。
                mateAxis = planeA ? uA : uB2;
            } else {
                mateAxis = perpendicularBetween(qA, qB2, lineA ? uA : uB);
            }
        } else if (type == MateType.DISTANCE || type == MateType.LIMIT) {
            mateAxis = distanceAxis(refA, refB, uA, uB, qA, qB2);
        } else {
            mateAxis = mateAxisDirection(type, uA, uB);
        }
        final Vector3d distanceAxis = (type == MateType.DISTANCE || type == MateType.LIMIT) ? mateAxis : null;
        final Vector3d axisDir = mateAxis;
        final double limitValue = ranged ? rangeMin : value;

        // 该配合锁定的轴（要先知道它们，才能把吸附目标算成「关节坐标系里被锁的分量全为零」）
        final Set<ConstraintJointAxis> joints = lockedAxes(type, refA, refB);

        final Vector3d delta = new Vector3d();
        if (snap) {
            switch (type) {
                case LOCK -> {
                    // 完全不动：保留当前相对位姿
                }
                case COINCIDENT -> {
                    if (axisDir == null) {
                        delta.set(qA).sub(qB2);                      // 点—点：直接重合
                    } else {
                        final double gap = new Vector3d(qB2).sub(qA).dot(axisDir);
                        delta.set(axisDir).mul(-gap);                // 点到面上 / 面到面：只消掉法向间隙
                    }
                }
                case DISTANCE -> {
                    if (axisDir != null) {
                        final double gap = new Vector3d(qB2).sub(qA).dot(axisDir);
                        delta.set(axisDir).mul(limitValue - gap);
                    } else {
                        // 点—点：沿当前连线放到指定距离
                        final Vector3d d = new Vector3d(qB2).sub(qA);
                        final double len = d.length();
                        if (len < 1e-6) {
                            throw new Unsolvable("mate.error.degenerate");
                        }
                        delta.set(d).mul((limitValue - len) / len);
                    }
                }
                case LIMIT -> {
                    // 限制：把当前偏移**夹进区间**（已经在区间内就一点都不动）
                    if (axisDir != null) {
                        final double gap = new Vector3d(qB2).sub(qA).dot(axisDir);
                        final double target = Math.clamp(gap, rangeMin, rangeMax);
                        delta.set(axisDir).mul(target - gap);
                    }
                }
                case PARALLEL, PERPENDICULAR, ANGLE -> {
                    // 纯方向约束：位置不动
                }
            }
        }

        // ---- 3b) 把「另一条被锁定的垂直方向」上的偏移也一次消掉 ----
        //
        // 关节坐标系的 y = z × x：线线/线点配合里 y 也是被锁的一条（见 lockedAxes）。
        // 吸附时只消 z 方向、把 y 方向留着的话，关节一建好就带着误差 —— 表现就是玩家看到的
        // 「先抽一下、被拉回去、抽几次之后弹飞」。既然要锁，就把初始误差一次算干净。
        if (snap && axisDir != null && frameX != null && joints.contains(ConstraintJointAxis.LINEAR_Y)) {
            final Vector3d yAxis = new Vector3d(axisDir).cross(frameX).normalize();
            final double offY = new Vector3d(qB2).sub(qA).dot(yAxis);
            delta.fma(-offY, yAxis);
        }

        final Vector3d newPosition = new Vector3d(poseB.position()).add(delta);
        // LOCK 明确不动；其余类型只要有平移或转动就吸附
        final boolean moved = delta.lengthSquared() > 1e-12
                || newOrientation.dot(new Quaterniond(poseB.orientation())) < 0.9999999;
        final boolean moveB = type != MateType.LOCK && moved;

        // ---- 4) 装配关节坐标系 ----
        // W：世界系下的共同基座。W.z = 配合轴（测量轴），W.x 优先取棱方向/目标方向。
        final Vector3d frameZ = lineInvolved ? axisDir : distanceAxis;
        final Quaterniond world = buildWorldFrame(type, frameZ, frameX, uA, uB, uTarget, poseA.orientation());

        final Vector3d posA = new Vector3d(ra.point());
        final Vector3d posB = new Vector3d(rb.point());

        if (type == MateType.LOCK) {
            // 全锁 6 轴时两个 frame 原点必须已经重合，否则关节会把两个参考点拉到一起、
            // 破坏「保持当前相对位置」的语义。这里把两侧原点都取在两点中点。
            final Vector3d mid = new Vector3d(qA).add(qB).mul(0.5);
            posA.set(poseA.transformPositionInverse(new Vector3d(mid), new Vector3d()));
            posB.set(poseB.transformPositionInverse(new Vector3d(mid), new Vector3d()));
        }

        final Quaterniond orientationA = new Quaterniond(poseA.orientation()).conjugate().mul(world).normalize();
        final Quaterniond orientationB = new Quaterniond(newOrientation).conjugate().mul(world).normalize();

        // ---- 5) 数值：精确值做进几何（硬锁），区间交给求解器的区间约束 ----
        //
        // 玩家给的思路（虚拟物体传力）：线线距离 = 两条棱分别与一块虚拟板子的两条棱做线线重合，
        // 板子宽度就是距离。等价地：**把 v 直接做进 frame2 的原点**，两条 frame 重合就代表
        // 参考点相距 v —— 于是距离也是一条「锁死」的轴（一根长度 v 的硬杆）。
        //
        // 「限制」是**区间**，做不进几何（几何只能是精确值），改用 setLimit(axis, min, max)：
        // 区间并不自相矛盾（min < max，而且这条轴不锁），所以 solver 里不会出现 lock/limit 互拉。
        final EnumSet<ConstraintJointAxis> jointAxes = EnumSet.noneOf(ConstraintJointAxis.class);
        jointAxes.addAll(joints);
        if (offsetAxis != null && !ranged) {
            // 世界 z 在 B 局部系里的方向（orientationB 把关节系的轴映射到 B 的局部系）
            final Vector3d localZ = orientationB.transform(new Vector3d(0, 0, 1));
            posB.fma(-limitValue, localZ);
            jointAxes.add(offsetAxis);
        }

        return new Plan(
                moveB, newPosition, newOrientation,
                posA, orientationA, posB, orientationB,
                jointAxes,
                // 区间类型把这条轴交给 setLimit；精确值类型已经做进几何，这里为 null
                ranged ? offsetAxis : null,
                limitValue,
                ranged ? rangeMax : limitValue);
    }

    // ============ 各部分 ============

    /** B 侧方向要转到的目标方向；null 表示不改朝向。 */
    @Nullable
    private static Vector3d orientationTarget(final MateType type, @Nullable final Vector3d uA,
                                              @Nullable final Vector3d uB, final double value,
                                              final boolean flip, final MateRef refA, final MateRef refB) {
        if (uA == null) {
            return null;
        }
        // 「一线一面」：这时候要的不是「B 的方向对齐 A 的方向」，而是**线落在面内**
        // （线的方向 ⟂ 面的法线）。用「把 B 的方向投影到垂直于 A 的方向上」实现最小转动。
        final boolean oneLineOnePlane = isLine(refA) != isLine(refB)
                && (isLine(refA) || isLine(refB)) && (isPlane(refA) || isPlane(refB));
        return switch (type) {
            case PARALLEL -> new Vector3d(uA).mul(flip ? -1 : 1);
            case COINCIDENT -> {
                if (uB == null) {
                    yield null;
                }
                if (oneLineOnePlane) {
                    yield projectOff(uB, uA);      // 线—面：线落在面内（或面法线 ⟂ 线）
                }
                yield new Vector3d(uA).mul(flip ? -1 : 1);   // 面—面 / 线—线：平行（可反向）
            }
            // 距离 / 限制：面—面、线—线时保持平行（测量轴/间距才稳定，玩家也要求「距离也要平行」）；
            // 线—面时把线转进面内（垂直于测量法线）；点参与的场合不动朝向。
            case DISTANCE, LIMIT -> {
                if (uB == null) {
                    yield null;
                }
                if (oneLineOnePlane) {
                    yield projectOff(uB, uA);
                }
                if (isPlane(refA) && isPlane(refB) || isLine(refA) && isLine(refB)) {
                    yield new Vector3d(uA).mul(flip ? -1 : 1);
                }
                yield null;
            }
            case PERPENDICULAR -> {
                if (uB == null) {
                    yield null;
                }
                if (oneLineOnePlane) {
                    // 线 ⟂ 面：线的方向要**对齐**面法线（不是躺在面里 —— 那是重合的语义）。
                    yield new Vector3d(uA).mul(flip ? -1 : 1);
                }
                // 面 ⟂ 面 / 线 ⟂ 线：取 B 方向在垂直于 A 方向平面上的分量（最小转动）
                final Vector3d proj = projectOff(uB, uA);
                // 反转：把 B 绕配合轴再转 180°（这就是玩家要的「翻个面」）
                yield flip ? proj.negate() : proj;
            }
            case ANGLE -> {
                if (uB == null) {
                    yield null;
                }
                // 在 uA 与 uB 张成的平面内，把 uA 转 value 度 —— 即 B 的最终方向
                Vector3d n = new Vector3d(uA).cross(uB);
                if (n.lengthSquared() < 1e-8) {
                    n = anyPerpendicular(uA);
                }
                n.normalize();
                yield new Quaterniond()
                        .fromAxisAngleRad(n, Math.toRadians(Math.clamp(value, 0.0, 180.0)))
                        .transform(new Vector3d(uA));
            }
            case LOCK -> null;
        };
    }

    /** 把 {@code dir} 投影到垂直于 {@code axis} 的平面上并单位化（退化时任取一个垂直方向）。 */
    private static Vector3d projectOff(final Vector3d dir, final Vector3d axis) {
        final Vector3d proj = new Vector3d(dir).fma(-dir.dot(axis), axis);
        return proj.lengthSquared() < 1e-8 ? anyPerpendicular(axis) : proj.normalize();
    }

    /** 平移约束所沿的方向；null 表示点—点（沿连线）。 */
    @Nullable
    private static Vector3d mateAxisDirection(final MateType type, @Nullable final Vector3d uA,
                                              @Nullable final Vector3d uB) {
        return switch (type) {
            case PARALLEL, PERPENDICULAR, ANGLE, LOCK, DISTANCE, LIMIT -> null;
            case COINCIDENT -> {
                if (uA != null) {
                    yield new Vector3d(uA);
                }
                yield uB != null ? new Vector3d(uB) : null;
            }
        };
    }

    /** 该参考是不是「线」（棱 / 结构主轴）：距离要量**垂直于线**的方向，不是沿线方向。 */
    private static boolean isLine(final MateRef ref) {
        return ref.kind() == RefKind.EDGE || ref.kind() == RefKind.BODY_AXIS;
    }

    /** 该参考是不是「面」（方块面 / 结构基准面）。 */
    private static boolean isPlane(final MateRef ref) {
        return ref.kind() == RefKind.FACE || ref.kind() == RefKind.BODY_PLANE;
    }

    /**
     * 距离配合的测量轴。
     *
     * <p>这是「虚空动能」最容易出问题的地方：轴选错就会和角向锁定互相矛盾 —— 解算器一边沿轴拉、
     * 一边拧，能量只进不出，结构就相互旋转着飞出去。规则：
     * <ul>
     *   <li>面—面：A 的法线（面间距就是法线方向的距离）；</li>
     *   <li>棱—棱 / 棱—点：<b>垂直于棱</b>的方向（两条平行线的距离是垂直距离，不是沿线距离）；</li>
     *   <li>面—棱：面的法线（棱到面的距离沿法线量）；</li>
     *   <li>其余（含点）：保有方向那一侧的方向；点—点返回 null（由关节自身的 z 决定）。</li>
     * </ul>
     */
    @Nullable
    private static Vector3d distanceAxis(final MateRef refA, final MateRef refB,
                                         @Nullable final Vector3d uA, @Nullable final Vector3d uB,
                                         final Vector3d qA, final Vector3d qB) {
        final boolean lineA = isLine(refA);
        final boolean lineB = isLine(refB);
        if (lineA && lineB) {
            return perpendicularBetween(qA, qB, uA != null ? uA : uB);
        }
        if (lineA) {
            return isPlane(refB) && uB != null ? new Vector3d(uB) : perpendicularBetween(qA, qB, uA);
        }
        if (lineB) {
            return isPlane(refA) && uA != null ? new Vector3d(uA) : perpendicularBetween(qA, qB, uB);
        }
        if (uA != null) {
            return new Vector3d(uA);
        }
        if (uB != null) {
            return new Vector3d(uB);
        }
        // 两个纯点：测量轴取「两点当前连线」。
        // ⚠ 不能返回 null 让关节自己拿 A 的局部 Z 当轴 —— 那样「吸附」是沿两点连线、而 limit 是
        // 沿 A 的 Z，两者不是同一条轴，关节一建好就要把结构硬掰过去（又一份虚空动能）。
        return perpendicularBetween(qA, qB, null);
    }

    /** 从 {@code from} 指向 {@code to}、且垂直于 {@code dir} 的单位方向（两条线的公垂方向）。 */
    private static Vector3d perpendicularBetween(final Vector3d from, final Vector3d to,
                                                 @Nullable final Vector3d dir) {        final Vector3d d = new Vector3d(to).sub(from);
        if (dir == null) {
            return d.lengthSquared() < 1e-12 ? new Vector3d(0, 1, 0) : d.normalize();
        }
        final Vector3d perp = new Vector3d(d).fma(-d.dot(dir), dir);
        // 两点已经落在同一条线上：垂直于该线的方向任取一个（此时距离约束本来就是 0 附近）
        return perp.lengthSquared() < 1e-8 ? anyPerpendicular(dir) : perp.normalize();
    }

    // ============ 测量（耦合类配合的驱动量） ============

    /**
     * 两个参考之间的<b>测量轴</b>（世界系单位向量）。
     *
     * <p>与 {@link #solve} 里用的是同一条规则（面—面取法线、线—线取公垂、其余按保有方向的一侧），
     * 所以「耦合测出来的量」与「标准配合钉住的量」永远在同一根轴上 —— 否则会出现
     * 「比例沿 X 量、距离沿 Y 钉」这种自相矛盾，正是以前虚空动能的来源。
     */
    @Nullable
    public static Vector3d measureAxis(final Pose3dc poseA, final BoundingBox3ic boundsA, final MateRef refA,
                                       final Pose3dc poseB, final BoundingBox3ic boundsB, final MateRef refB) {
        final Resolved ra = resolveLocal(boundsA, refA);
        final Resolved rb = resolveLocal(boundsB, refB);
        return distanceAxis(refA, refB,
                worldDirection(poseA, ra), worldDirection(poseB, rb),
                worldPoint(poseA, ra), worldPoint(poseB, rb));
    }

    /**
     * 两个参考之间的<b>有向距离</b>：把两点连线投影到测量轴上，沿轴正方向为正。
     *
     * <p>比例配合每个物理子步都要用它现算驱动量 {@code d_1}（见
     * {@code docs/advanced-mates-design.md}），齿轮用它算轴间距。
     */
    public static double measureDistance(final Pose3dc poseA, final BoundingBox3ic boundsA, final MateRef refA,
                                         final Pose3dc poseB, final BoundingBox3ic boundsB, final MateRef refB) {
        final Resolved ra = resolveLocal(boundsA, refA);
        final Resolved rb = resolveLocal(boundsB, refB);
        final Vector3d qA = worldPoint(poseA, ra);
        final Vector3d qB = worldPoint(poseB, rb);
        final Vector3d axis = distanceAxis(refA, refB,
                worldDirection(poseA, ra), worldDirection(poseB, rb), qA, qB);
        if (axis == null) {
            return 0.0;
        }
        return new Vector3d(qB).sub(qA).dot(axis);
    }

    /**
     * 需要被「钉在某个值 / 区间」的轴。
     *
     * <p>{@link MateType#DISTANCE}（精确值，做进几何）与 {@link MateType#LIMIT}（区间，交给 setLimit）
     * 都占用 LINEAR_Z；其余类型的目标偏移都是 0，直接锁死即可。
     */
    @Nullable
    private static ConstraintJointAxis offsetAxis(final MateType type) {
        return switch (type) {
            case DISTANCE, LIMIT -> ConstraintJointAxis.LINEAR_Z;
            default -> null;
        };
    }

    /** 该类型用 {@code setLimit} 钉住的轴（没有则 null）。冲突检测要用它一起算自由度。 */
    @Nullable
    public static ConstraintJointAxis limitAxisOf(final MateType type) {
        return offsetAxis(type);
    }

    /**
     * 该配合在这两个参考上实际<b>占用</b>的轴 = 锁定的轴 ∪ 用 limit 钉住的轴。
     *
     * <p>为什么必须把 limit 轴也算进来：{@code setLimit(LINEAR_Z, v, v)} 和「锁定 LINEAR_Z」对同一条
     * 自由度的作用是同样的——都把它定死了，只是一个定在 0、一个定在 v。冲突检测如果只看 {@code lockedAxes}，
     * 就会漏掉「重合（把法向锁成 0）＋ 距离（把同一法向钉成 3）」这种直接矛盾的组合，
     * 于是玩家会得到两个互相打架的关节。
     */
    public static Set<ConstraintJointAxis> consumedAxes(final MateType type, final MateRef a, final MateRef b) {
        final Set<ConstraintJointAxis> out = EnumSet.noneOf(ConstraintJointAxis.class);
        out.addAll(lockedAxes(type, a, b));
        final ConstraintJointAxis limit = offsetAxis(type);
        if (limit != null) {
            out.add(limit);
        }
        return out;
    }

    /**
     * 该配合类型在这两个参考上会锁死哪些轴（<b>公开</b>：服务端的冲突检测要用它做自由度预算，
     * 不需要解开结构位姿，只看参考有没有方向）。
     */
    public static Set<ConstraintJointAxis> lockedAxes(final MateType type, final MateRef a, final MateRef b) {
        return lockedAxes(type, a.hasDirection(), b.hasDirection(), isPlane(a), isPlane(b),
                isLine(a), isLine(b));
    }

    /** 需要锁死的轴集合。 */
    private static Set<ConstraintJointAxis> lockedAxes(final MateType type, final Resolved ra, final Resolved rb) {
        return lockedAxes(type, ra.direction() != null, rb.direction() != null);
    }

    /** 需要锁死的轴集合（只看有没有方向；面/线特例见下面重载）。 */
    private static Set<ConstraintJointAxis> lockedAxes(final MateType type, final boolean aHasDir,
                                                       final boolean bHasDir) {
        return lockedAxes(type, aHasDir, bHasDir, false, false, false, false);
    }

    /**
     * 需要锁死的轴集合。
     *
     * <p><b>铁律：{@code offsetAxis(type)}（用数值/区间占用的那条轴）绝不能同时出现在这里。</b>
     * 一条自由度既被锁成 0、又被钉成 v 或限制在 [min,max]，两种约束会互相拉扯 —— 这就是「虚空动能」，
     * 玩家的感受是结构自己转起来、越转越快最后被甩飞。检查器里有一条通用断言遍历
     * 所有「类型 × 参考类型」组合来钉住这条铁律。
     *
     * <p>棱参与的配合要特别小心：关节坐标系的 x 轴是<b>棱方向</b>、z 轴是<b>垂直于棱</b>的方向。
     * 于是：
     * <ul>
     *   <li>保持两条棱平行 = 只允许绕 x（棱方向）自转 ⇒ 锁 {@code ANGULAR_Y}/{@code ANGULAR_Z}；</li>
     *   <li>{@code LINEAR_Z} 锁的是「垂直于棱的偏移」——线线重合靠它才真正生效。</li>
     * </ul>
     */
    private static Set<ConstraintJointAxis> lockedAxes(final MateType type, final boolean aHasDir,
                                                       final boolean bHasDir,
                                                       final boolean aIsPlane, final boolean bIsPlane,
                                                       final boolean aIsLine, final boolean bIsLine) {
        final EnumSet<ConstraintJointAxis> set = EnumSet.noneOf(ConstraintJointAxis.class);
        final boolean linePair = aIsLine && bIsLine;
        final boolean oneLineOnePlane = (aIsLine && bIsPlane) || (aIsPlane && bIsLine);
        switch (type) {
            case LOCK -> java.util.Collections.addAll(set, ConstraintJointAxis.ALL);
            case COINCIDENT -> {
                if (linePair) {
                    // 线—线重合 = **两根棱被一根硬杆串起来**：关节坐标系 x = 棱方向、z = 垂直于棱的方向。
                    // 两条棱共线要求「两条垂直方向上的偏移都为 0」⇒ 锁 LINEAR_Y **和** LINEAR_Z；
                    // 保持平行 ⇒ 只允许绕 x（棱方向）自转 ⇒ 锁 ANGULAR_Y/ANGULAR_Z。
                    // 少锁 LINEAR_Y 就是玩家看到的「用手杖一拉就分开」。
                    set.add(ConstraintJointAxis.LINEAR_Y);
                    set.add(ConstraintJointAxis.LINEAR_Z);
                    set.add(ConstraintJointAxis.ANGULAR_Y);
                    set.add(ConstraintJointAxis.ANGULAR_Z);
                } else if (oneLineOnePlane) {
                    // 线—面重合：线落在面内 ⇒ 中点法向偏移 0 + 线 ⟂ 面法线（两个倾角锁住），
                    // 只留绕法线自转与沿线滑动。
                    set.add(ConstraintJointAxis.LINEAR_Z);
                    set.add(ConstraintJointAxis.ANGULAR_X);
                    set.add(ConstraintJointAxis.ANGULAR_Y);
                } else if (!aHasDir && !bHasDir) {
                    // 点—点：位置完全合一，姿态自由
                    set.add(ConstraintJointAxis.LINEAR_X);
                    set.add(ConstraintJointAxis.LINEAR_Y);
                    set.add(ConstraintJointAxis.LINEAR_Z);
                } else if (aHasDir && bHasDir) {
                    // 面—面 / 基准面—基准面：共面 + 法线平行（面内平移与自转自由）
                    set.add(ConstraintJointAxis.LINEAR_Z);
                    set.add(ConstraintJointAxis.ANGULAR_X);
                    set.add(ConstraintJointAxis.ANGULAR_Y);
                } else {
                    // 点落在面上：只消掉法向间隙
                    set.add(ConstraintJointAxis.LINEAR_Z);
                }
            }
            case PARALLEL, PERPENDICULAR, ANGLE -> {
                set.add(ConstraintJointAxis.ANGULAR_X);
                set.add(ConstraintJointAxis.ANGULAR_Y);
            }
            case DISTANCE, LIMIT -> {
                if (linePair) {
                    // 线—线距离 = 一根**垂直于两条棱的硬杆**：x = 棱方向（可沿棱滑动）、
                    // z = 垂直方向（距离做进几何 / 限制交给区间约束）、y = 另一条垂直方向 ⇒ 锁 LINEAR_Y，
                    // 否则「距离」会沿 y 漂走（几何上真实的垂直距离跟着变）。
                    // 保持平行 ⇒ 锁 ANGULAR_Y/ANGULAR_Z（留绕棱自转）。
                    // ⚠ 不能锁 ANGULAR_X/LINEAR_X：x 就是棱方向，锁它等于禁止滑移/自转，会和数值轴抢自由度。
                    set.add(ConstraintJointAxis.LINEAR_Y);
                    set.add(ConstraintJointAxis.ANGULAR_Y);
                    set.add(ConstraintJointAxis.ANGULAR_Z);
                } else if (aIsLine || bIsLine) {
                    // 线—点：把点用一根垂直于棱的硬杆挑住（另一个垂直方向锁死，沿棱可滑）
                    if (!aHasDir || !bHasDir) {
                        set.add(ConstraintJointAxis.LINEAR_Y);
                    } else {
                        // 线—面：间距沿面法线量，同时保持线 ⟂ 法线（线落在面内方向）
                        set.add(ConstraintJointAxis.ANGULAR_X);
                        set.add(ConstraintJointAxis.ANGULAR_Y);
                    }
                } else if (aIsPlane && bIsPlane) {
                    // 面—面：除了把间距钉在 v 上，还要保持平行 —— 否则法线可以自由旋转，
                    // 间距是在一条会转的轴上量的，约束基本拉不住。
                    set.add(ConstraintJointAxis.ANGULAR_X);
                    set.add(ConstraintJointAxis.ANGULAR_Y);
                } else if (!aHasDir && !bHasDir) {
                    // 点—点：横向两条锁死，第三条留给 limit 钉在 v 上。
                    // ⚠ 这里<b>不能</b>再锁 LINEAR_Z —— 那就和 distance 的 limit 直接矛盾（虚空动能）。
                    set.add(ConstraintJointAxis.LINEAR_X);
                    set.add(ConstraintJointAxis.LINEAR_Y);
                }
                // 面—点：只钉法向间距，面内两个方向保持自由
            }
        }
        return set;
    }

    // ============ 正交基工具 ============

    /**
     * 装配世界系共同基座 W。
     *
     * <p>{@code W.z} 是配合轴：方向类配合取 A 侧方向；没有方向的（锁定、点—点）取 A 的当前朝向，
     * 这样 {@code M1} 退化成单位阵、{@code M2 = R_B⁻¹R_A}，正好「保持当前相对位姿」。
     */
    private static Quaterniond buildWorldFrame(final MateType type, @Nullable final Vector3d frameZ,
                                               @Nullable final Vector3d frameX,
                                               @Nullable final Vector3d uA,
                                               @Nullable final Vector3d uB, @Nullable final Vector3d uTarget,
                                               final Quaterniondc orientationA) {
        final Vector3d z;
        if (frameZ != null) {
            z = new Vector3d(frameZ);       // 距离/线线配合：测量轴（面=法线；线=垂直于线的方向）
        } else if (uA != null) {
            z = new Vector3d(uA);
        } else if (uB != null) {
            z = new Vector3d(uB);
        } else {
            z = orientationA.transform(new Vector3d(0, 0, 1));
        }

        // 优先的 X：显式指定（棱方向）> 目标方向（垂直配合）> B 的方向
        Vector3d prefX = null;
        if (frameX != null) {
            prefX = new Vector3d(frameX);
        } else if (uTarget != null) {
            prefX = new Vector3d(uTarget);
        } else if (uB != null) {
            prefX = new Vector3d(uB);
        }

        return basisFromZ(z, prefX);
    }

    /** 以 {@code z} 为第三轴、尽量贴近 {@code prefX} 的正交基。 */
    public static Quaterniond basisFromZ(final Vector3dc z, @Nullable final Vector3dc prefX) {
        final Vector3d zz = new Vector3d(z);
        if (zz.lengthSquared() < 1e-12) {
            zz.set(0, 0, 1);
        }
        zz.normalize();

        Vector3d xx = new Vector3d();
        if (prefX != null) {
            xx.set(prefX).fma(-new Vector3d(prefX).dot(zz), zz);
        }
        if (xx.lengthSquared() < 1e-8) {
            xx.set(anyPerpendicular(zz));
        }
        xx.normalize();

        final Vector3d yy = new Vector3d(zz).cross(xx).normalize();
        // 重新正交化 X，消除浮点误差
        xx.set(yy).cross(zz).normalize();

        final Matrix3d m = new Matrix3d()
                .setColumn(0, xx)
                .setColumn(1, yy)
                .setColumn(2, zz);
        return new Quaterniond().setFromNormalized(m).normalize();
    }

    /** 任取一个与 {@code v} 垂直的单位向量。 */
    public static Vector3d anyPerpendicular(final Vector3dc v) {
        final Vector3d n = new Vector3d(v).normalize();
        final Vector3d alt = Math.abs(n.x()) < 0.9 ? new Vector3d(1, 0, 0) : new Vector3d(0, 1, 0);
        final Vector3d out = alt.fma(-alt.dot(n), n);
        if (out.lengthSquared() < 1e-8) {
            return new Vector3d(0, 0, 1);
        }
        return out.normalize();
    }
}
