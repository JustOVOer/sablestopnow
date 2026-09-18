package com.ovo.sablestopnow.mate;

import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
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
     * @param limitAxis       需要「钉在某个非零值」的轴（{@link MateType#DISTANCE}/{@link MateType#TANGENT}），可为 null
     * @param limitValue      该轴的目标值
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
            double limitValue) {
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
        return switch (ref.kind()) {
            case VERTEX -> new Resolved(
                    MateRef.faceCorner(ref.block(), ref.face(), ref.feature()), null);
            case EDGE -> new Resolved(
                    MateRef.faceEdgeMidpoint(ref.block(), ref.face(), ref.feature()),
                    MateRef.faceEdgeDirection(ref.face(), ref.feature()));
            case FACE -> new Resolved(
                    MateRef.faceCenter(ref.block(), ref.face()),
                    new Vector3d(ref.face().getStepX(), ref.face().getStepY(), ref.face().getStepZ()));
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
        return solve(subA.logicalPose(), boundsOf(subA), subB.logicalPose(), boundsOf(subB),
                type, refA, refB, value, flip, true);
    }

    /**
     * 只算关节坐标系与锁定轴，<b>不吸附</b>（按两侧当前位姿原样建立）。
     *
     * <p>用于关节失效后的重建：约束是相对<b>参考点</b>表达的（锁轴归零 / 距离钉在 v），
     * 定义上不需要重新吸附；而重新吸附会在结构被外力推歪后把它突然拽回去，反而更糟。
     */
    public static Plan frames(final SubLevel subA, final SubLevel subB, final MateType type,
                              final MateRef refA, final MateRef refB,
                              final double value, final boolean flip) throws Unsolvable {
        return solve(subA.logicalPose(), boundsOf(subA), subB.logicalPose(), boundsOf(subB),
                type, refA, refB, value, flip, false);
    }

    /**
     * 一对结构上的「约束账本」：记录每条被占用的自由度轴及其目标值，用来判定配合冲突。
     *
     * <p>做成纯数据 + 纯逻辑（不碰 {@code MateData} / 世界）是为了能离线验证 ——
     * 冲突判定直接决定「如果冲突则不配合」这条需求，值得有自己的回归测试。
     *
     * <p><b>判据只有一条：同一条自由度轴被钉到不同的值</b>（锁定 ⇒ 0，limit ⇒ v）。
     *
     * <p>为什么不是「占用同一条轴就算冲突」：重合 + 平行这种组合是<b>冗余但一致</b>的
     * （两条都要求该轴为 0），拦下来只会妨碍正常建模。
     *
     * <p>为什么也不是「占用轴数超过 3」：账本按轴去重，每种方向最多只有 3 条轴，
     * 这个上限<b>永远不可能被突破</b>——写成判据是死代码。真正的矛盾一定表现为「同轴不同值」。
     */
    public static final class Budget {

        private static final double EPSILON = 1.0e-6;

        private final Map<ConstraintJointAxis, Double> targets = new EnumMap<>(ConstraintJointAxis.class);

        /**
         * 并入一条配合的占用。
         *
         * @return false 表示与已有内容冲突（同一条轴被钉到不同的值）
         */
        public boolean add(final MateType type, final MateRef a, final MateRef b, final double value) {
            for (final ConstraintJointAxis axis : lockedAxes(type, a, b)) {
                if (this.conflicts(axis, 0.0)) {
                    return false;
                }
            }
            final ConstraintJointAxis limit = limitAxisOf(type);
            return limit == null || !this.conflicts(limit, value);
        }

        private boolean conflicts(final ConstraintJointAxis axis, final double value) {
            final Double previous = this.targets.putIfAbsent(axis, value);
            return previous != null && Math.abs(previous - value) > EPSILON;
        }

        /** 已占用的轴数（供测试断言「这套组合到底占了几个自由度」）。 */
        public int size() {
            return this.targets.size();
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
        final Vector3d uTarget = snap ? orientationTarget(type, uA, uB, value, flip) : null;
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

        // ---- 3) 平移：只消掉被约束方向上的误差（最小移动）----
        final ConstraintJointAxis offsetAxis = offsetAxis(type);
        final Vector3d axisDir = mateAxisDirection(type, uA, uB);
        final double limitValue = value;

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
                case CONCENTRIC -> {
                    if (axisDir != null) {
                        final Vector3d d = new Vector3d(qB2).sub(qA);
                        final Vector3d perp = new Vector3d(d).fma(-d.dot(axisDir), axisDir);
                        delta.set(perp).negate();                    // 只消掉垂直于轴线的偏移
                    }
                }
                case DISTANCE, TANGENT -> {
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
                case PARALLEL, PERPENDICULAR, ANGLE -> {
                    // 纯方向约束：位置不动
                }
            }
        }

        final Vector3d newPosition = new Vector3d(poseB.position()).add(delta);
        // LOCK 明确不动；其余类型只要有平移或转动就吸附
        final boolean moved = delta.lengthSquared() > 1e-12
                || newOrientation.dot(new Quaterniond(poseB.orientation())) < 0.9999999;
        final boolean moveB = type != MateType.LOCK && moved;

        // ---- 4) 装配关节坐标系 ----
        // W：世界系下的共同基座。W.z = 配合轴，W.x 优先取 B 侧方向（垂直配合靠这一点成立）。
        final Quaterniond world = buildWorldFrame(type, uA, uB, uTarget, poseA.orientation());

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

        return new Plan(
                moveB, newPosition, newOrientation,
                posA, orientationA, posB, orientationB,
                lockedAxes(type, ra, rb),
                offsetAxis,
                limitValue);
    }

    // ============ 各部分 ============

    /** B 侧方向要转到的目标方向；null 表示不改朝向。 */
    @Nullable
    private static Vector3d orientationTarget(final MateType type, @Nullable final Vector3d uA,
                                              @Nullable final Vector3d uB, final double value,
                                              final boolean flip) {
        if (uA == null) {
            return null;
        }
        return switch (type) {
            case PARALLEL, CONCENTRIC, TANGENT -> new Vector3d(uA).mul(flip ? -1 : 1);
            case COINCIDENT -> uB == null ? null : new Vector3d(uA).mul(flip ? -1 : 1);
            case PERPENDICULAR -> {
                if (uB == null) {
                    yield null;
                }
                // 取 B 方向在垂直于 A 方向平面上的分量（最小转动）
                final Vector3d proj = new Vector3d(uB).fma(-uB.dot(uA), uA);
                yield proj.lengthSquared() < 1e-8 ? anyPerpendicular(uA) : proj.normalize();
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
            case DISTANCE, LOCK -> null;
        };
    }

    /** 平移约束所沿的方向；null 表示点—点（沿连线）。 */
    @Nullable
    private static Vector3d mateAxisDirection(final MateType type, @Nullable final Vector3d uA,
                                              @Nullable final Vector3d uB) {
        return switch (type) {
            case PARALLEL, PERPENDICULAR, ANGLE, LOCK -> null;
            case CONCENTRIC, TANGENT, DISTANCE, COINCIDENT -> {
                if (uA != null) {
                    yield new Vector3d(uA);
                }
                yield uB != null ? new Vector3d(uB) : null;
            }
        };
    }

    /**
     * 需要被「钉在非零值」的轴。
     *
     * <p>只有 {@link MateType#DISTANCE}/{@link MateType#TANGENT} 需要：它们的锁定轴语义（归零）表达不了
     * 「保持 3 格间距」，必须改用 {@code setLimit(axis, v, v)} 把偏移钉在目标值上。
     * 其余类型的目标偏移都是 0，直接锁死即可。
     */
    @Nullable
    private static ConstraintJointAxis offsetAxis(final MateType type) {
        return switch (type) {
            case DISTANCE, TANGENT -> ConstraintJointAxis.LINEAR_Z;
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
        return lockedAxes(type, a.hasDirection(), b.hasDirection());
    }

    /** 需要锁死的轴集合。 */
    private static Set<ConstraintJointAxis> lockedAxes(final MateType type, final Resolved ra, final Resolved rb) {
        return lockedAxes(type, ra.direction() != null, rb.direction() != null);
    }

    /** 需要锁死的轴集合。 */
    private static Set<ConstraintJointAxis> lockedAxes(final MateType type, final boolean aHasDir,
                                                       final boolean bHasDir) {
        final EnumSet<ConstraintJointAxis> set = EnumSet.noneOf(ConstraintJointAxis.class);
        switch (type) {
            case LOCK -> java.util.Collections.addAll(set, ConstraintJointAxis.ALL);
            case COINCIDENT -> {
                if (!aHasDir && !bHasDir) {
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
            case CONCENTRIC -> {
                // 轴线共线：垂直轴线的平移 + 轴线的两个倾角锁掉；沿轴滑动与绕轴自转自由
                set.add(ConstraintJointAxis.LINEAR_X);
                set.add(ConstraintJointAxis.LINEAR_Y);
                set.add(ConstraintJointAxis.ANGULAR_X);
                set.add(ConstraintJointAxis.ANGULAR_Y);
            }
            case TANGENT -> {
                set.add(ConstraintJointAxis.ANGULAR_X);
                set.add(ConstraintJointAxis.ANGULAR_Y);
            }
            case DISTANCE -> {
                if (!aHasDir && !bHasDir) {
                    set.add(ConstraintJointAxis.LINEAR_X);
                    set.add(ConstraintJointAxis.LINEAR_Y);
                    set.add(ConstraintJointAxis.LINEAR_Z);
                }
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
    private static Quaterniond buildWorldFrame(final MateType type, @Nullable final Vector3d uA,
                                               @Nullable final Vector3d uB, @Nullable final Vector3d uTarget,
                                               final Quaterniondc orientationA) {
        final Vector3d z;
        if (uA != null) {
            z = new Vector3d(uA);
        } else if (uB != null) {
            z = new Vector3d(uB);
        } else {
            z = orientationA.transform(new Vector3d(0, 0, 1));
        }

        // 优先的 X：垂直配合必须用 B 的方向（这样 W.x ⟂ W.z 才等价于「两方向垂直」）
        Vector3d prefX = null;
        if (uTarget != null) {
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
