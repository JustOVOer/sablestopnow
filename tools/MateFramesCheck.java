package com.ovo.sablestopnow.tools;

import com.ovo.sablestopnow.mate.Mate;
import com.ovo.sablestopnow.mate.MateFrames;
import com.ovo.sablestopnow.mate.MateRef;
import com.ovo.sablestopnow.mate.MateType;
import com.ovo.sablestopnow.mate.RefKind;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.EnumSet;
import java.util.UUID;

/**
 * 配合几何求解的离线验证器（不参与构建，手动运行）。
 *
 * <p>{@code MateFrames} 的全部推导都建立在一条核心不变量上：<b>吸附之后，两侧关节 frame 在世界系里必须
 * 精确重合</b>（位置为零、姿态为单位旋转）。一旦不成立，关节一建出来就带误差，两个结构会互相拉扯。
 * 这个验证器把该不变量，以及每种配合类型的<b>几何语义</b>（法线平行 / 垂直 / 夹角 / 间距 / 轴线共线）
 * 逐条断言一遍，全部在纯 JVM 里跑，不需要启动游戏。
 *
 * <h2>怎么跑</h2>
 * <pre>
 * javac -cp &lt;project classes&gt;;&lt;neoforge-merged&gt;;&lt;sable jars&gt;;&lt;joml&gt; -d &lt;out&gt; tools/MateFramesCheck.java
 * java  -cp &lt;out&gt;;&lt;same classpath&gt; com.ovo.sablestopnow.tools.MateFramesCheck
 * </pre>
 * 退出码非 0 表示有断言失败。脚本见 {@code tools/run-mate-check.ps1}。
 */
public final class MateFramesCheck {

    private static int failures;
    private static int checks;

    private MateFramesCheck() {
    }

    // ============ 夹具 ============

    /** 一个测试结构：位姿 + plot 包围盒。包围盒统一取 [-1..1]^3 的 3x3x3 方块。 */
    private record Body(Pose3d pose, BoundingBox3ic bounds) {
    }

    private static Body body(final double x, final double y, final double z,
                             final Quaterniond orientation, final Vector3d scale) {
        final Pose3d pose = new Pose3d(
                new Vector3d(x, y, z), new Quaterniond(orientation),
                new Vector3d(0, 0, 0), new Vector3d(scale));
        return new Body(pose, new BoundingBox3i(-1, -1, -1, 1, 1, 1));
    }

    private static Body body(final double x, final double y, final double z) {
        return body(x, y, z, new Quaterniond(), new Vector3d(1, 1, 1));
    }

    /** 因为夹具里 rotationPoint 固定为原点，吸附后的 B 位姿可以直接构造出来。 */
    private static Pose3d snapped(final Body original, final MateFrames.Plan plan) {
        return new Pose3d(
                new Vector3d(plan.newPositionB()), new Quaterniond(plan.newOrientationB()),
                new Vector3d(original.pose().rotationPoint()), new Vector3d(original.pose().scale()));
    }

    private static MateRef face(final UUID body, final int bx, final int by, final int bz, final Direction dir) {
        return MateRef.block(body, RefKind.FACE, new BlockPos(bx, by, bz), dir, -1);
    }

    private static MateRef vertex(final UUID body, final int bx, final int by, final int bz,
                                  final Direction dir, final int corner) {
        return MateRef.block(body, RefKind.VERTEX, new BlockPos(bx, by, bz), dir, corner);
    }

    private static MateRef edge(final UUID body, final int bx, final int by, final int bz,
                                final Direction dir, final int which) {
        return MateRef.block(body, RefKind.EDGE, new BlockPos(bx, by, bz), dir, which);
    }

    // ============ 断言 ============

    private static void near(final String what, final double actual, final double expected, final double tol) {
        checks++;
        if (Math.abs(actual - expected) > tol) {
            failures++;
            System.out.printf("  FAIL %-46s actual=%.6f expected=%.6f (tol %.1e)%n", what, actual, expected, tol);
        }
    }

    private static void nearVec(final String what, final Vector3d actual, final Vector3d expected, final double tol) {
        checks++;
        final double d = actual.distance(expected);
        if (d > tol) {
            failures++;
            System.out.printf("  FAIL %-46s actual=%s expected=%s (d=%.2e)%n", what, fmt(actual), fmt(expected), d);
        }
    }

    private static void ok(final String what, final boolean condition) {
        checks++;
        if (!condition) {
            failures++;
            System.out.printf("  FAIL %s%n", what);
        }
    }

    private static String fmt(final Vector3d v) {
        return String.format("(%.4f,%.4f,%.4f)", v.x, v.y, v.z);
    }

    // ============ 核心不变量 ============

    /**
     * 关节的正确性判据。
     *
     * <p><b>注意不要断言「两个 frame 在世界系里位置/姿态完全重合」</b>——那只在 6 轴全锁时才成立。
     * 比如平行配合只锁了角向，两端的参考点本来就允许错开；距离配合的两个 frame 还差着 <i>value</i>。
     * rapier 通用关节真正的语义是<b>每个被锁定轴上的关节坐标必须为零</b>，非锁定轴自由。所以这里：
     * <ol>
     *   <li>把 frame2 相对 frame1 的偏移投到 frame1 的基上，锁定的线性轴分量必须为 0，
     *       limit 轴分量必须等于目标值；</li>
     *   <li>相对旋转的旋转矢量在锁定角向轴上的分量必须为 0；</li>
     *   <li>另外确认 frame 的朝向确实是正交归一的（防止 basisFromZ 退化）。</li>
     * </ol>
     */
    private static void checkJoint(final String label, final Body a, final Body b,
                                   final MateFrames.Plan plan) {
        final Pose3d poseB2 = snapped(b, plan);

        final Vector3d p1 = a.pose().transformPosition(new Vector3d(plan.posA()), new Vector3d());
        final Vector3d p2 = poseB2.transformPosition(new Vector3d(plan.posB()), new Vector3d());

        final Matrix3d m1 = new Matrix3d().rotation(new Quaterniond(a.pose().orientation()).mul(plan.orientationA()));
        final Matrix3d m2 = new Matrix3d().rotation(new Quaterniond(poseB2.orientation()).mul(plan.orientationB()));

        // (3) frame 基必须正交归一
        near(label + " frame1 basis orthonormal", orthonormalityError(m1), 0.0, 1e-9);
        near(label + " frame2 basis orthonormal", orthonormalityError(m2), 0.0, 1e-9);

        // (1) 线性轴
        final Vector3d local = new Matrix3d(m1).transpose().transform(new Vector3d(p2).sub(p1));
        final double[] comp = { local.x, local.y, local.z };
        for (final ConstraintJointAxis axis : ConstraintJointAxis.LINEAR) {
            if (axis == plan.limitAxis()) {
                if (plan.isRange()) {
                    // 区间类型：偏移只要落在 [min, max] 里就算满足（这正是「限制」的语义）
                    ok(label + " limit " + axis + " within [" + plan.limitValue() + ", "
                                    + plan.limitMax() + "]",
                            comp[axis.ordinal()] >= plan.limitValue() - 1e-6
                                    && comp[axis.ordinal()] <= plan.limitMax() + 1e-6);
                } else {
                    near(label + " limit " + axis + " == " + plan.limitValue(),
                            comp[axis.ordinal()], plan.limitValue(), 1e-6);
                }
            } else if (plan.locked().contains(axis)) {
                near(label + " locked " + axis + " == 0", comp[axis.ordinal()], 0.0, 1e-6);
            }
        }

        // (2) 角向轴：相对旋转的旋转矢量在锁定轴上的分量必须为 0
        final Matrix3d rel = new Matrix3d(m1).transpose().mul(m2);
        final Quaterniond relQ = new Quaterniond().setFromNormalized(rel);
        final double angle = 2.0 * Math.acos(Math.clamp(Math.abs(relQ.w), -1.0, 1.0));
        final Vector3d rotVec = new Vector3d();
        if (angle > 1e-9) {
            final double s = Math.sin(angle * 0.5);
            if (Math.abs(s) > 1e-9) {
                rotVec.set(relQ.x, relQ.y, relQ.z).mul(angle / s);
            }
        }
        final double[] rv = { rotVec.x, rotVec.y, rotVec.z };
        for (final ConstraintJointAxis axis : ConstraintJointAxis.ANGULAR) {
            if (plan.locked().contains(axis)) {
                near(label + " locked " + axis + " == 0", rv[axis.ordinal() - 3], 0.0, 1e-6);
            }
        }
    }

    /** 正交基偏离单位阵的最大元素误差。 */
    private static double orthonormalityError(final Matrix3d m) {
        final Matrix3d shouldBeIdentity = new Matrix3d(m).transpose().mul(m);
        double worst = 0;
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 3; r++) {
                worst = Math.max(worst, Math.abs(shouldBeIdentity.get(c, r) - (c == r ? 1.0 : 0.0)));
            }
        }
        return worst;
    }

    /** 6 轴全锁时两个 frame 才必须完全重合。 */
    private static void checkFramesCoincide(final String label, final Body a, final Body b,
                                            final MateFrames.Plan plan) {
        final Pose3d poseB2 = snapped(b, plan);
        final Vector3d p1 = a.pose().transformPosition(new Vector3d(plan.posA()), new Vector3d());
        final Vector3d p2 = poseB2.transformPosition(new Vector3d(plan.posB()), new Vector3d());
        nearVec(label + " frames coincide (position)", p2, p1, 1e-6);
        final Matrix3d m1 = new Matrix3d().rotation(new Quaterniond(a.pose().orientation()).mul(plan.orientationA()));
        final Matrix3d m2 = new Matrix3d().rotation(new Quaterniond(poseB2.orientation()).mul(plan.orientationB()));
        near(label + " frames coincide (orientation)",
                new Matrix3d(m1).transpose().mul(m2).equals(new Matrix3d(), 1e-9) ? 0.0 : 1.0, 0.0, 1e-9);
    }

    // ============ 用例 ============

    public static void main(final String[] args) {
        try {
            coincidentFaces();
            coincidentVertices();
            parallelFaces();
            perpendicularFaces();
            angleFaces();
            distanceFaces();
            concentricAxes();
            lockHoldsStill();
            rotatedBody();
            scaledBody();
            edgeDirectionGeometry();
            tangentFaces();
            concentricEdges();
            flipTurnsBody180();
            pointOnPlane();
            mixedBodyAndFace();
            distanceNegative();
            angleOneEighty();
            parallelFlipped();
            extremeRotation();
            lockedSetShapes();
            autoAlignment();
            alignmentSupportFlags();
            conflictBudget();
            geometricConflict();
            persistenceRoundTrip();
            metadataConsistency();
            noSelfConflictingJoints();
            lineLineRod();
            perpendicularLineOnPlane();
            limitRange();
        } catch (final Throwable t) {
            System.out.println("HARNESS ERROR: " + t);
            t.printStackTrace(System.out);
            failures++;
        }

        System.out.printf("%n=== %d checks, %d failures ===%n", checks, failures);
        if (failures == 0) {
            System.out.println("ALL PASS");
        }
        System.exit(failures == 0 ? 0 : 1);
    }

    private static final double TOL = 1e-6;

    /** 面—面重合（同向对齐）：B 的 DOWN 面贴到 A 的 UP 面。 */
    private static void coincidentFaces() throws Exception {
        System.out.println("[coincident face-face, anti-aligned]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 0, 0);
        final MateRef fa = face(idA, 0, 0, 1, Direction.UP);    // 顶面：法线 +Y，面心 y = 1.0
        final MateRef fb = face(idB, 0, 0, -1, Direction.DOWN); // 底面：法线 -Y，面心 y = -1.0

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.COINCIDENT, fa, fb, 0.0, true, true);

        // A 的 UP 面在 y = 1.0（方块 (0,0,1) 顶面）；B 的 DOWN 面局部 y = 0.0
        // → B.position.y 从 0 变成 1.0 才能共面
        near("B moved so its down-face meets A's up-face", plan.newPositionB().y, 1.0, TOL);
        near("B x unchanged (in-plane slide is free)", plan.newPositionB().x, 10.0, TOL);
        near("B z unchanged", plan.newPositionB().z, 0.0, TOL);

        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d uB = MateFrames.worldDirection(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), fb));
        near("normals anti-parallel (dot == -1)", uA.dot(uB), -1.0, TOL);

        ok("linear Z locked", plan.locked().contains(ConstraintJointAxis.LINEAR_Z));
        ok("angular X,Y locked", plan.locked().contains(ConstraintJointAxis.ANGULAR_X)
                && plan.locked().contains(ConstraintJointAxis.ANGULAR_Y));
        ok("no limit axis", plan.limitAxis() == null);
        checkJoint("coincident-face", a, b, plan);
    }

    /** 顶点—顶点重合。 */
    private static void coincidentVertices() throws Exception {
        System.out.println("[coincident vertex-vertex]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 3, 0);
        final MateRef va = vertex(idA, 0, 0, 1, Direction.UP, 1);   // (1.0, 1.0, 1.0)
        final MateRef vb = vertex(idB, 0, 0, -1, Direction.DOWN, 0); // (-1.0, -1.0, 1.0)

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.COINCIDENT, va, vb, 0.0, false, true);

        final Vector3d qA = MateFrames.worldPoint(a.pose(), MateFrames.resolveLocal(a.bounds(), va));
        final Vector3d qB = MateFrames.worldPoint(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), vb));
        nearVec("vertices coincide", qB, qA, TOL);
        ok("all 3 linear locked", plan.locked().contains(ConstraintJointAxis.LINEAR_X)
                && plan.locked().contains(ConstraintJointAxis.LINEAR_Y)
                && plan.locked().contains(ConstraintJointAxis.LINEAR_Z));
        ok("no angular locked", !plan.locked().contains(ConstraintJointAxis.ANGULAR_X));
        checkJoint("coincident-vertex", a, b, plan);
    }

    /** 平行：两个 +Y 法线保持平行，位置不动。 */
    private static void parallelFaces() throws Exception {
        System.out.println("[parallel]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 0, 0);
        final MateRef fa = face(idA, 0, 0, 1, Direction.UP);
        final MateRef fb = face(idB, 0, 0, 1, Direction.UP);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.PARALLEL, fa, fb, 0.0, false, true);

        nearVec("B did not move", plan.newPositionB(), new Vector3d(10, 0, 0), TOL);
        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d uB = MateFrames.worldDirection(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), fb));
        near("normals parallel (dot == 1)", uA.dot(uB), 1.0, TOL);
        ok("only angular locked", !plan.locked().contains(ConstraintJointAxis.LINEAR_Z)
                && plan.locked().contains(ConstraintJointAxis.ANGULAR_X));
        checkJoint("parallel", a, b, plan);
    }

    /** 垂直：把 B 的 +Y 法线转到与 A 的 +Y 法线成 90°。 */
    private static void perpendicularFaces() throws Exception {
        System.out.println("[perpendicular]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 0, 0);
        final MateRef fa = face(idA, 0, 0, 1, Direction.UP);
        final MateRef fb = face(idB, 0, 0, 1, Direction.UP);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.PERPENDICULAR, fa, fb, 0.0, false, true);

        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d uB = MateFrames.worldDirection(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), fb));
        near("normals perpendicular (dot == 0)", uA.dot(uB), 0.0, 1e-6);
        // 关键：垂直是靠「把 B 的方向放到 frame2 的 X 轴上」实现的，验证 frame2 的 X 确实等于 B 的方向
        final Pose3d poseB2 = snapped(b, plan);
        final Vector3d frameX = new Quaterniond(poseB2.orientation()).mul(plan.orientationB())
                .transform(new Vector3d(1, 0, 0));
        nearVec("frame2 X axis == B direction", frameX, uB, 1e-6);
        checkJoint("perpendicular", a, b, plan);
    }

    /** 角度：两个原本平行的 +Y 法线，要求 45°。 */
    private static void angleFaces() throws Exception {
        System.out.println("[angle 45 deg]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 0, 0);
        final MateRef fa = face(idA, 0, 0, 1, Direction.UP);
        final MateRef fb = face(idB, 0, 0, 1, Direction.UP);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.ANGLE, fa, fb, 45.0, false, true);

        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d uB = MateFrames.worldDirection(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), fb));
        near("angle == 45 deg", Math.toDegrees(Math.acos(Math.clamp(uA.dot(uB), -1.0, 1.0))), 45.0, 1e-4);
        checkJoint("angle", a, b, plan);

        // 重建路径（frames，不吸附位置）也必须**要求把朝向转回目标角度**：
        // 否则关节会把当前（歪掉的）朝向当成目标锁住，改数值等于永久失效。
        final MateFrames.Plan rebuild = MateFrames.frames(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.ANGLE, fa, fb, 45.0, false);
        final org.joml.Quaterniond currentB = new org.joml.Quaterniond(b.pose().orientation());
        ok("rebuild asks B to rotate to the target angle",
                Math.abs(currentB.dot(rebuild.newOrientationB())) < 0.99999);
        final Vector3d rebuiltDir = rebuild.newOrientationB()
                .transform(new Vector3d(MateFrames.worldDirection(b.pose(),
                        MateFrames.resolveLocal(b.bounds(), fb))));
        near("the rotation it asks for lands exactly on 45 deg",
                Math.toDegrees(Math.acos(Math.clamp(uA.dot(rebuiltDir), -1.0, 1.0))), 45.0, 1e-3);
    }

    /** 距离：两个 +Y 面之间保持 3 格。 */
    private static void distanceFaces() throws Exception {
        System.out.println("[distance 3]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 0, 0);
        final MateRef fa = face(idA, 0, 0, 1, Direction.UP);
        final MateRef fb = face(idB, 0, 0, 1, Direction.UP);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.DISTANCE, fa, fb, 3.0, false, true);

        final Vector3d qA = MateFrames.worldPoint(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d qB = MateFrames.worldPoint(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), fb));
        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        near("gap along normal == 3", new Vector3d(qB).sub(qA).dot(uA), 3.0, TOL);
        // 数值已经做进关节坐标系（= 一根长度 3 的虚拟硬杆），关节里不再有 limit：
        // LZ 变成硬锁，两条 frame 重合即代表参考点相距 3。
        ok("距离不再用 setLimit（数值做进几何）", plan.limitAxis() == null);
        ok("LINEAR_Z 作为硬锁参与", plan.locked().contains(ConstraintJointAxis.LINEAR_Z));
        checkJoint("distance", a, b, plan);
    }

    /** 平行（结构主轴）：两个方向主轴必须转成同向，位置不动。 */
    private static void concentricAxes() throws Exception {
        System.out.println("[parallel axes]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 0, 0);
        final MateRef axA = MateRef.axis(idA, 1);
        final MateRef axB = MateRef.axis(idB, 1);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.PARALLEL, axA, axB, 0.0, false, true);

        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), axA));
        final Vector3d uB = MateFrames.worldDirection(snapped(b, plan),
                MateFrames.resolveLocal(b.bounds(), axB));
        near("axes parallel after solve", Math.abs(uA.dot(uB)), 1.0, TOL);
        ok("位置不动（纯方向约束）", plan.newPositionB().distance(b.pose().position()) < TOL);
        ok("ANGULAR_X,Y locked", plan.locked().contains(ConstraintJointAxis.ANGULAR_X)
                && plan.locked().contains(ConstraintJointAxis.ANGULAR_Y));
        ok("free spin (ANGULAR_Z not locked)", !plan.locked().contains(ConstraintJointAxis.ANGULAR_Z));
        checkJoint("parallel-axes", a, b, plan);
    }

    /** 锁定：必须一动不动，且 6 轴全锁（否则会把两个参考点拉到一起）。 */
    private static void lockHoldsStill() throws Exception {
        System.out.println("[lock]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 3, 0);
        final MateRef va = vertex(idA, 0, 0, 1, Direction.UP, 1);
        final MateRef vb = vertex(idB, 0, 0, -1, Direction.DOWN, 0);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.LOCK, va, vb, 0.0, false, true);

        ok("did not move", !plan.moveB());
        nearVec("position unchanged", plan.newPositionB(), new Vector3d(10, 3, 0), TOL);
        near("orientation unchanged",
                1.0 - Math.abs(new Quaterniond(plan.newOrientationB()).dot(new Quaterniond())), 0.0, TOL);
        ok("all 6 axes locked", plan.locked().size() == 6);
        checkJoint("lock", a, b, plan);
        // 只有全锁 6 轴时，两个 frame 才必须完全重合
        checkFramesCoincide("lock", a, b, plan);
    }

    /** A 被任意旋转后，坐标系装配必须依然成立（考验 R_A⁻¹W 的推导）。 */
    private static void rotatedBody() throws Exception {
        System.out.println("[rotated A]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Quaterniond rot = new Quaterniond().fromAxisAngleDeg(new Vector3d(0.3, 0.7, 0.5).normalize(), 37.0);
        final Body a = body(2, 5, -3, rot, new Vector3d(1, 1, 1));
        final Body b = body(14, 1, 2, new Quaterniond(), new Vector3d(1, 1, 1));
        final MateRef fa = face(idA, 0, 0, 1, Direction.UP);
        final MateRef fb = face(idB, 0, 0, -1, Direction.DOWN);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.COINCIDENT, fa, fb, 0.0, true, true);

        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d uB = MateFrames.worldDirection(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), fb));
        near("normals anti-parallel after rotation", uA.dot(uB), -1.0, 1e-6);
        checkJoint("rotated", a, b, plan);
    }

    /** 缩放的结构（scale != 1）：吸附位置必须按缩放后的几何算。 */
    private static void scaledBody() throws Exception {
        System.out.println("[scaled B]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 0, 0, new Quaterniond(), new Vector3d(0.5, 0.5, 0.5));
        final MateRef fa = face(idA, 0, 0, 1, Direction.UP);
        final MateRef fb = face(idB, 0, 0, -1, Direction.DOWN);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.COINCIDENT, fa, fb, 0.0, true, true);

        // A 顶面 y=1.0；B 的参考点局部 y = 0.0，缩放不改变它的 y 分量 → B.position.y 仍应为 1.0
        near("B scaled snap position", plan.newPositionB().y, 1.0, 1e-6);
        checkJoint("scaled", a, b, plan);
    }

    /** 面内角点/棱的序号约定必须与面基一致（选取分类依赖它）。 */
    private static void edgeDirectionGeometry() {
        System.out.println("[face corner / edge geometry]");
        final BlockPos block = new BlockPos(0, 0, 0);
        final Direction up = Direction.UP;

        // 面心 = 方块原点 + 0.5 + 0.5*法线 = (0.5, 1.0, 0.5)；UP 面 u=X, v=Z
        // 角点 = 面心 ± 0.5u ± 0.5v，序号 bit0 = u 正负, bit1 = v 正负
        nearVec("corner0", MateRef.faceCorner(block, up, 0), new Vector3d(0.0, 1.0, 0.0), TOL);
        nearVec("corner1", MateRef.faceCorner(block, up, 1), new Vector3d(1.0, 1.0, 0.0), TOL);
        nearVec("corner2", MateRef.faceCorner(block, up, 2), new Vector3d(0.0, 1.0, 1.0), TOL);
        nearVec("corner3", MateRef.faceCorner(block, up, 3), new Vector3d(1.0, 1.0, 1.0), TOL);

        // 棱 0 连接 corner0-corner1 → 方向 +X；棱 1 → +Z；棱 2 → -X；棱 3 → -Z
        nearVec("edge0 dir", MateRef.faceEdgeDirection(up, 0), new Vector3d(1, 0, 0), TOL);
        nearVec("edge1 dir", MateRef.faceEdgeDirection(up, 1), new Vector3d(0, 0, 1), TOL);
        nearVec("edge2 dir", MateRef.faceEdgeDirection(up, 2), new Vector3d(-1, 0, 0), TOL);
        nearVec("edge3 dir", MateRef.faceEdgeDirection(up, 3), new Vector3d(0, 0, -1), TOL);
        // 棱中点必须落在「那条棱」上，且序号与 faceEdgeDirection / MateTargeting.classify 一致：
        // edge0 在 v=-0.5 侧、edge1 在 u=+0.5 侧、edge2 在 v=+0.5 侧、edge3 在 u=-0.5 侧。
        // 这条断言是「选到面中线」的回归保护：以前用 (i, i+1) 连角点，棱 1/2 取到对角线，
        // 中点恰好等于面心 —— 玩家瞄准棱，锚点却跑到面中间。
        nearVec("edge0 midpoint", MateRef.faceEdgeMidpoint(block, up, 0), new Vector3d(0.5, 1.0, 0.0), TOL);
        nearVec("edge1 midpoint", MateRef.faceEdgeMidpoint(block, up, 1), new Vector3d(1.0, 1.0, 0.5), TOL);
        nearVec("edge2 midpoint", MateRef.faceEdgeMidpoint(block, up, 2), new Vector3d(0.5, 1.0, 1.0), TOL);
        nearVec("edge3 midpoint", MateRef.faceEdgeMidpoint(block, up, 3), new Vector3d(0.0, 1.0, 0.5), TOL);

        // 每个面：四条棱的中点都必须在面内、离面心 0.5、且**不等于面心**
        for (final Direction dir : Direction.values()) {
            final Vector3d fc = MateRef.faceCenter(block, dir);
            final Vector3d n = new Vector3d(dir.getStepX(), dir.getStepY(), dir.getStepZ());
            for (int i = 0; i < 4; i++) {
                final Vector3d m = MateRef.faceEdgeMidpoint(block, dir, i);
                ok("edge midpoint " + dir + "#" + i + " not collapsed to face center",
                        m.distance(fc) > 0.4);
                ok("edge midpoint " + dir + "#" + i + " on face plane",
                        Math.abs(m.sub(fc, new Vector3d()).dot(n)) < TOL);
                ok("edge midpoint " + dir + "#" + i + " at half extent from center",
                        Math.abs(m.distance(fc) - 0.5) < TOL);
            }
        }

        // 每个面的四个角点都必须在包围盒角上（不越界、不塌陷）
        for (final Direction dir : Direction.values()) {
            for (int i = 0; i < 4; i++) {
                final Vector3d c = MateRef.faceCorner(block, dir, i);
                ok("corner " + dir + "#" + i + " within block cube",
                        c.x >= -0.5 - TOL && c.x <= 1.5 + TOL
                                && c.y >= -0.5 - TOL && c.y <= 1.5 + TOL
                                && c.z >= -0.5 - TOL && c.z <= 1.5 + TOL);
            }
        }

        // 正交基必须右手、正交、单位
        final Quaterniond basis = MateFrames.basisFromZ(new Vector3d(0, 1, 0), new Vector3d(1, 0, 0));
        final Vector3d bx = basis.transform(new Vector3d(1, 0, 0));
        final Vector3d by = basis.transform(new Vector3d(0, 1, 0));
        final Vector3d bz = basis.transform(new Vector3d(0, 0, 1));
        near("basis orthonormal xy", bx.dot(by), 0.0, TOL);
        near("basis orthonormal xz", bx.dot(bz), 0.0, TOL);
        near("basis orthonormal yz", by.dot(bz), 0.0, TOL);
        nearVec("basis Z == requested", bz, new Vector3d(0, 1, 0), TOL);
        nearVec("basis X == preferred", bx, new Vector3d(1, 0, 0), TOL);
        nearVec("basis right-handed", new Vector3d(bx).cross(by), bz, TOL);
    }

    // ============ 追加覆盖（第 3 轮扩充） ============

    /** 距离：方向对齐（勾反转时）+ 沿法线钉在给定间距。 */
    private static void tangentFaces() throws Exception {
        System.out.println("[distance 1.5]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 0, 0);
        final MateRef fa = face(idA, 0, 0, 1, Direction.UP);
        final MateRef fb = face(idB, 0, 0, 1, Direction.UP);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.DISTANCE, fa, fb, 1.5, false, true);

        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d qA = MateFrames.worldPoint(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d qB = MateFrames.worldPoint(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), fb));
        near("distance gap == 1.5", new Vector3d(qB).sub(qA).dot(uA), 1.5, TOL);
        ok("distance gap is carried by the frame geometry (no setLimit)", plan.limitAxis() == null);
        checkJoint("distance", a, b, plan);
    }

    /** 平行用「棱」而不是结构主轴：棱自带方向，走的是同一条路径但参考是方块级的。 */
    private static void concentricEdges() throws Exception {
        System.out.println("[parallel edge-edge]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 5, 0);
        // UP 面的棱 0：方向 +X，中点 (0.5, 1.0, 0.0)
        final MateRef ea = edge(idA, 0, 0, 1, Direction.UP, 0);
        final MateRef eb = edge(idB, 0, 0, 1, Direction.UP, 0);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.PARALLEL, ea, eb, 0.0, false, true);

        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), ea));
        final Vector3d uB = MateFrames.worldDirection(snapped(b, plan),
                MateFrames.resolveLocal(b.bounds(), eb));
        near("edge directions parallel", Math.abs(uA.dot(uB)), 1.0, TOL);
        ok("位置不动", plan.newPositionB().distance(b.pose().position()) < TOL);
        checkJoint("parallel-edge", a, b, plan);
    }

    /** 反转（勾选框）：把 B 绕配合面内的轴翻 180° —— 垂直/距离也必须真的动起来。 */
    private static void flipTurnsBody180() throws Exception {
        System.out.println("[flip 180 deg]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 0, 0);
        final MateRef fa = face(idA, 0, 0, 1, Direction.UP);
        final MateRef fb = face(idB, 0, 0, 1, Direction.UP);

        // 距离 + 反转：B 的法线（UP）应被翻成 DOWN
        final MateFrames.Plan distFlip = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.DISTANCE, fa, fb, 2.0, true, true);
        final Vector3d uB = MateFrames.worldDirection(snapped(b, distFlip),
                MateFrames.resolveLocal(b.bounds(), fb));
        ok("DISTANCE + 反转 → B 换成反向", uB.y() < -0.99);
        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d qA = MateFrames.worldPoint(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d qB = MateFrames.worldPoint(snapped(b, distFlip),
                MateFrames.resolveLocal(b.bounds(), fb));
        near("DISTANCE + 反转 → 间距仍是 2.0（沿反转后的法线量）",
                new Vector3d(qB).sub(qA).dot(uA), 2.0, TOL);
        // 距离不勾反转：B 的法线对齐到 +uA（间距沿一条稳定的轴量，不然约束拉不住）
        final MateFrames.Plan distPlain = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.DISTANCE, fa, fb, 2.0, false, true);
        final Vector3d uBPlain = MateFrames.worldDirection(snapped(b, distPlain),
                MateFrames.resolveLocal(b.bounds(), fb));
        ok("DISTANCE 不勾反转 → B 法线对齐 uA（保持平行，间距才稳）", uBPlain.y() > 0.99);

        // 垂直 + 反转：B 的方向应改成最小转动目标的反向
        final MateRef eb = edge(idB, 0, 0, 1, Direction.UP, 1);   // 棱 1：方向 +Z
        final MateFrames.Plan perpPlain = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.PERPENDICULAR, fa, eb, 0.0, false, true);
        final MateFrames.Plan perpFlip = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.PERPENDICULAR, fa, eb, 0.0, true, true);
        final Vector3d pPlain = MateFrames.worldDirection(snapped(b, perpPlain),
                MateFrames.resolveLocal(b.bounds(), eb));
        final Vector3d pFlip = MateFrames.worldDirection(snapped(b, perpFlip),
                MateFrames.resolveLocal(b.bounds(), eb));
        near("垂直：反转前后方向正好相反", pPlain.dot(pFlip), -1.0, TOL);
        // 面 ⟂ 棱：棱的方向要对齐面法线（这正是「垂直」的几何含义）
        near("垂直 + 反转 → 棱仍对齐面法线（只是取了反）", Math.abs(pFlip.dot(uA)), 1.0, 1e-3);
    }

    /** 点落在面上：只该消掉法向间隙，面内两个方向保持自由。 */
    private static void pointOnPlane() throws Exception {
        System.out.println("[coincident vertex-on-face]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 4, 0);
        final MateRef fa = face(idA, 0, 0, 1, Direction.UP);          // A 的平面：y = 1.0
        final MateRef vb = vertex(idB, 0, 0, 1, Direction.UP, 0);    // B 的角点 (0.0, 1.0, 1.0)

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.COINCIDENT, fa, vb, 0.0, false, true);

        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d qA = MateFrames.worldPoint(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d qB = MateFrames.worldPoint(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), vb));
        near("vertex lies on the plane", new Vector3d(qB).sub(qA).dot(uA), 0.0, TOL);
        ok("only one linear axis locked", plan.locked().size() == 1
                && plan.locked().contains(ConstraintJointAxis.LINEAR_Z));
        checkJoint("vertex-on-face", a, b, plan);
    }

    /** 结构级基准面 配 方块面：两类参考混用。 */
    private static void mixedBodyAndFace() throws Exception {
        System.out.println("[coincident body-plane vs block-face]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 0, 0);
        final MateRef planeA = MateRef.plane(idA, 1);
        final MateRef fb = face(idB, 0, 0, 1, Direction.UP);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.COINCIDENT, planeA, fb, 0.0, false, true);

        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), planeA));
        final Vector3d qA = MateFrames.worldPoint(a.pose(), MateFrames.resolveLocal(a.bounds(), planeA));
        final Vector3d qB = MateFrames.worldPoint(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), fb));
        near("faces coplanar", new Vector3d(qB).sub(qA).dot(uA), 0.0, TOL);
        final Vector3d uB = MateFrames.worldDirection(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), fb));
        near("normals parallel", uB.dot(uA), 1.0, TOL);
        checkJoint("body-plane-vs-face", a, b, plan);
    }

    /** 负距离：允许反向偏移（cp/description.txt 只说要保持距离，没说必须是正的）。 */
    private static void distanceNegative() throws Exception {
        System.out.println("[distance -1.5]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 0, 0);
        final MateRef fa = face(idA, 0, 0, 1, Direction.UP);
        final MateRef fb = face(idB, 0, 0, 1, Direction.UP);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.DISTANCE, fa, fb, -1.5, false, true);

        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d qA = MateFrames.worldPoint(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d qB = MateFrames.worldPoint(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), fb));
        near("gap == -1.5", new Vector3d(qB).sub(qA).dot(uA), -1.5, TOL);
        checkJoint("distance-negative", a, b, plan);
    }

    /** 180°：方向完全反向，且这是 rotationTo 的退化分支（两向量反平行）。 */
    private static void angleOneEighty() throws Exception {
        System.out.println("[angle 180 deg]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 0, 0);
        final MateRef fa = face(idA, 0, 0, 1, Direction.UP);
        final MateRef fb = face(idB, 0, 0, 1, Direction.UP);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.ANGLE, fa, fb, 180.0, false, true);

        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d uB = MateFrames.worldDirection(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), fb));
        near("angle == 180 deg", Math.toDegrees(Math.acos(Math.clamp(uA.dot(uB), -1.0, 1.0))), 180.0, 1e-3);
        // 帧必须仍然是正交归一的（180° 旋转最容易让基退化）
        checkJoint("angle-180", a, b, plan);
    }

    /** 平行 + 反向对齐 ⇒ 法线反平行，且仍然只锁角向。 */
    private static void parallelFlipped() throws Exception {
        System.out.println("[parallel, anti-aligned]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 0, 0);
        final MateRef fa = face(idA, 0, 0, 1, Direction.UP);
        final MateRef fb = face(idB, 0, 0, 1, Direction.UP);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.PARALLEL, fa, fb, 0.0, true, true);

        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d uB = MateFrames.worldDirection(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), fb));
        near("normals anti-parallel", uA.dot(uB), -1.0, 1e-6);
        ok("still only angular locked", !plan.locked().contains(ConstraintJointAxis.LINEAR_Z));
        checkJoint("parallel-flipped", a, b, plan);
    }

    /** 接近 180° 的极端旋转：R_A⁻¹W 的推导在这种姿态下最容易出数值问题。 */
    private static void extremeRotation() throws Exception {
        System.out.println("[rotated A by 179 deg]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Quaterniond rot = new Quaterniond()
                .fromAxisAngleDeg(new Vector3d(0.2, 0.9, 0.3).normalize(), 179.0);
        final Body a = body(-7, 12, 5, rot, new Vector3d(1, 1, 1));
        final Body b = body(20, -4, 9, new Quaterniond(), new Vector3d(1, 1, 1));
        final MateRef fa = face(idA, 0, 0, 1, Direction.UP);
        final MateRef fb = face(idB, 0, 0, -1, Direction.DOWN);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.COINCIDENT, fa, fb, 0.0, true, true);

        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d uB = MateFrames.worldDirection(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), fb));
        near("normals anti-parallel at 179 deg", uA.dot(uB), -1.0, 1e-6);
        checkJoint("extreme-rotation", a, b, plan);
    }

    /**
     * 自由度映射的回归表。
     *
     * <p>锁定哪些轴是「配合语义」的唯一实现，改了任何一条都会静默改变配合行为（比如把距离配合
     * 变成刚性焊接）。所以这里把每种类型 + 参考形态的期望集合逐条钉死。
     */
    private static void lockedSetShapes() {
        System.out.println("[locked axis set per type]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();

        // 无方向的参考：顶点
        final MateRef ptA = vertex(idA, 0, 0, 1, Direction.UP, 0);
        final MateRef ptB = vertex(idB, 0, 0, 1, Direction.UP, 0);
        // 有方向的参考：面
        final MateRef fcA = face(idA, 0, 0, 1, Direction.UP);
        final MateRef fcB = face(idB, 0, 0, 1, Direction.UP);
        // 线参考：棱
        final MateRef eaA = edge(idA, 0, 0, 1, Direction.UP, 0);
        final MateRef eaB = edge(idB, 0, 0, 1, Direction.UP, 0);

        final EnumSet<ConstraintJointAxis> all = EnumSet.allOf(ConstraintJointAxis.class);

        expectSet("COINCIDENT point-point", MateType.COINCIDENT, ptA, ptB,
                EnumSet.of(ConstraintJointAxis.LINEAR_X, ConstraintJointAxis.LINEAR_Y,
                        ConstraintJointAxis.LINEAR_Z));
        expectSet("COINCIDENT point-face", MateType.COINCIDENT, ptA, fcB,
                EnumSet.of(ConstraintJointAxis.LINEAR_Z));
        expectSet("COINCIDENT face-face", MateType.COINCIDENT, fcA, fcB,
                EnumSet.of(ConstraintJointAxis.LINEAR_Z, ConstraintJointAxis.ANGULAR_X,
                        ConstraintJointAxis.ANGULAR_Y));
        expectSet("PARALLEL", MateType.PARALLEL, fcA, fcB,
                EnumSet.of(ConstraintJointAxis.ANGULAR_X, ConstraintJointAxis.ANGULAR_Y));
        expectSet("PERPENDICULAR", MateType.PERPENDICULAR, fcA, fcB,
                EnumSet.of(ConstraintJointAxis.ANGULAR_X, ConstraintJointAxis.ANGULAR_Y));
        expectSet("ANGLE", MateType.ANGLE, fcA, fcB,
                EnumSet.of(ConstraintJointAxis.ANGULAR_X, ConstraintJointAxis.ANGULAR_Y));
        expectSet("TANGENT→DISTANCE face-face", MateType.DISTANCE, fcA, fcB,
                EnumSet.of(ConstraintJointAxis.ANGULAR_X, ConstraintJointAxis.ANGULAR_Y));
        expectSet("LOCK", MateType.LOCK, ptA, ptB, all);
        expectSet("DISTANCE point-point", MateType.DISTANCE, ptA, ptB,
                EnumSet.of(ConstraintJointAxis.LINEAR_X, ConstraintJointAxis.LINEAR_Y));
        expectSet("DISTANCE edge-edge (rod ⟂ to the edges, kept parallel, slide/spin free)",
                MateType.DISTANCE, eaA, eaB,
                EnumSet.of(ConstraintJointAxis.LINEAR_Y, ConstraintJointAxis.ANGULAR_Y,
                        ConstraintJointAxis.ANGULAR_Z));
        expectSet("COINCIDENT edge-edge (collinear rod: both ⟂ offsets locked, slide/spin free)",
                MateType.COINCIDENT, eaA, eaB,
                EnumSet.of(ConstraintJointAxis.LINEAR_Y, ConstraintJointAxis.LINEAR_Z,
                        ConstraintJointAxis.ANGULAR_Y, ConstraintJointAxis.ANGULAR_Z));
        expectSet("DISTANCE face-face (limited + kept parallel)", MateType.DISTANCE, fcA, fcB,
                EnumSet.of(ConstraintJointAxis.ANGULAR_X, ConstraintJointAxis.ANGULAR_Y));
    }

    private static void expectSet(final String label, final MateType type,
                                  final MateRef a, final MateRef b,
                                  final EnumSet<ConstraintJointAxis> expected) {
        final java.util.Set<ConstraintJointAxis> actual = MateFrames.lockedAxes(type, a, b);
        checks++;
        if (!actual.equals(expected)) {
            failures++;
            System.out.printf("  FAIL %-46s actual=%s expected=%s%n", label, actual, expected);
        }
    }

    /**
     * 自动对齐方向的选取规则。
     *
     * <p>「选满两个自动成配」时玩家无法事先声明方向，所以规则必须挑「不需要转动」的那一个；
     * 否则面法线朝外会导致结构被莫名翻 180°（选顶面 + 底面本该是叠上去，同向对齐却会倒扣）。
     */
    private static void autoAlignment() throws Exception {
        System.out.println("[auto alignment rule]");
        ok("opposed directions -> anti-aligned",
                MateFrames.suggestFlip(new Vector3d(0, 1, 0), new Vector3d(0, -1, 0)));
        ok("same directions -> aligned",
                !MateFrames.suggestFlip(new Vector3d(0, 1, 0), new Vector3d(0, 1, 0)));
        ok("perpendicular -> aligned (no preference either way)",
                !MateFrames.suggestFlip(new Vector3d(0, 1, 0), new Vector3d(1, 0, 0)));
        ok("missing direction -> aligned",
                !MateFrames.suggestFlip(null, new Vector3d(0, 1, 0)));

        // 规则挑出的方向必须真的让吸附「零转动」：A 顶面 + B 底面（法线相反）
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 7, 3);
        final MateRef fa = face(idA, 0, 0, 1, Direction.UP);     // 法线 +Y
        final MateRef fb = face(idB, 0, 0, -1, Direction.DOWN); // 法线 -Y

        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d uB = MateFrames.worldDirection(b.pose(), MateFrames.resolveLocal(b.bounds(), fb));
        final boolean flip = MateFrames.suggestFlip(uA, uB);
        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.COINCIDENT, fa, fb, 0.0, flip, true);
        near("auto flip leaves B's orientation untouched",
                1.0 - Math.abs(new Quaterniond(plan.newOrientationB()).dot(new Quaterniond())), 0.0, 1e-9);
        near("auto flip only translated B (x)", plan.newPositionB().x, 10.0, TOL);
        near("auto flip only translated B (z)", plan.newPositionB().z, 3.0, TOL);
        checkJoint("auto-alignment", a, b, plan);
    }

    /**
     * 「界面是否显示对齐按钮 / updateFlip 是否接受修改」必须与解算器是否真的用 {@code flip} 一致。
     *
     * <p>曾经两头都不一致：重合（解算器会用）被标成不支持 ⇒ 玩家改不了最常见的配合；
     * 垂直（解算器不用）被标成支持 ⇒ 界面上有个点了没反应的按钮。
     */
    private static void alignmentSupportFlags() {
        System.out.println("[alignment support flags]");
        ok("COINCIDENT supports alignment (solver honours flip)", MateType.COINCIDENT.supportsAlignment());
        ok("PARALLEL supports alignment", MateType.PARALLEL.supportsAlignment());
        ok("DISTANCE supports alignment (勾反转能把 B 翻 180°)", MateType.DISTANCE.supportsAlignment());
        ok("PERPENDICULAR supports alignment (翻转会取反最小转动目标)",
                MateType.PERPENDICULAR.supportsAlignment());
        ok("ANGLE does NOT (the value fixes the direction)", !MateType.ANGLE.supportsAlignment());
        ok("LOCK does NOT", !MateType.LOCK.supportsAlignment());

        // Mate 级：重合需要两侧都带方向才有朝向可谈
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final MateRef ptA = vertex(idA, 0, 0, 1, Direction.UP, 0);
        final MateRef ptB = vertex(idB, 0, 0, 1, Direction.UP, 0);
        final MateRef fcA = face(idA, 0, 0, 1, Direction.UP);
        final MateRef fcB = face(idB, 0, 0, 1, Direction.UP);
        final UUID owner = UUID.randomUUID();

        ok("vertex-vertex coincident has NO alignment toggle",
                !mateOf(MateType.COINCIDENT, ptA, ptB, owner).supportsAlignment());
        ok("face-face coincident HAS an alignment toggle",
                mateOf(MateType.COINCIDENT, fcA, fcB, owner).supportsAlignment());
        ok("face-face parallel HAS an alignment toggle",
                mateOf(MateType.PARALLEL, fcA, fcB, owner).supportsAlignment());
        ok("face-face perpendicular HAS an alignment toggle now that flip negates the target",
                mateOf(MateType.PERPENDICULAR, fcA, fcB, owner).supportsAlignment());
        ok("face-face distance HAS an alignment toggle",
                mateOf(MateType.DISTANCE, fcA, fcB, owner).supportsAlignment());
        // 点参考没有朝向可谈 ⇒ 距离也拿不到反转勾选框
        ok("point-point distance has NO alignment toggle",
                !mateOf(MateType.DISTANCE, ptA, ptB, owner).supportsAlignment());
    }

    private static com.ovo.sablestopnow.mate.Mate mateOf(final MateType type, final MateRef a,
                                                        final MateRef b, final UUID owner) {
        return new com.ovo.sablestopnow.mate.Mate(UUID.randomUUID(), type, a, b, 0.0, 0.0, false, null, owner, "t");
    }

    /**
     * 配合冲突账本 —— 「如果冲突则不配合」这条需求的直接回归测试。
     *
     * <p>关键是那句容易写错的判据：<b>冲突 = 同一条自由度轴被钉到不同的值</b>，
     * 而不是「占用了同一条轴」。只看占用会漏掉「重合把法向锁成 0 + 距离把同一条法向钉成 3」
     * （两条各占 1 个线性自由度，加起来才 2，计数上完全合法，但两个关节会互相拉扯），
     * 又会误伤「重合 + 平行」这种冗余但一致的组合。
     */
    private static void conflictBudget() {
        System.out.println("[conflict budget]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final MateRef fcA = face(idA, 0, 0, 1, Direction.UP);
        final MateRef fcB = face(idB, 0, 0, 1, Direction.UP);

        // 1) 重合（锁 LZ + AX,AY）之后再加距离 3（把 LZ 钉到 3）→ 必须冲突
        MateFrames.Budget a = new MateFrames.Budget();
        ok("coincident alone is fine", a.add(MateType.COINCIDENT, fcA, fcB, 0.0));
        ok("coincident + distance 3 -> conflict (same axis, different value)",
                !a.add(MateType.DISTANCE, fcA, fcB, 3.0));

        // 2) 同样的组合但距离 = 0：冗余但一致，应当放行
        MateFrames.Budget b = new MateFrames.Budget();
        ok("coincident accepted", b.add(MateType.COINCIDENT, fcA, fcB, 0.0));
        ok("coincident + distance 0 -> allowed (consistent)", b.add(MateType.DISTANCE, fcA, fcB, 0.0));

        // 3) 重合 + 平行：平行只重复占用 AX,AY 且目标同为 0 → 放行
        MateFrames.Budget c = new MateFrames.Budget();
        ok("coincident accepted again", c.add(MateType.COINCIDENT, fcA, fcB, 0.0));
        ok("coincident + parallel -> allowed (redundant but consistent)",
                c.add(MateType.PARALLEL, fcA, fcB, 0.0));

        // 4) 面—面重合 + 距离 0：都在 LZ 上、目标值都是 0 → 放行，且去重后只占 3 条轴
        MateFrames.Budget d = new MateFrames.Budget();
        ok("coincident accepted again (d)", d.add(MateType.COINCIDENT, fcA, fcB, 0.0));
        ok("coincident + distance 0 allowed (same value on the same axis)",
                d.add(MateType.DISTANCE, fcA, fcB, 0.0));
        ok("they occupy exactly 3 distinct axes", d.size() == 3);

        // 5) 距离 3 之后再加距离 5 → 同轴不同值，冲突
        MateFrames.Budget e = new MateFrames.Budget();
        ok("distance 3 accepted", e.add(MateType.DISTANCE, fcA, fcB, 3.0));
        ok("distance 3 + distance 5 -> conflict", !e.add(MateType.DISTANCE, fcA, fcB, 5.0));
    }

    /**
     * 几何冲突判定：把 B 摆到新配合要求的位姿上，已有配合是否还成立。
     *
     * <p>这一条直接决定「冲突的配合要不要建出来」。自由度账本（{@link MateFrames.Budget}）只能发现
     * 「同一条轴被钉到不同数值」，发现不了用户实测的那种矛盾：先建点点配合（锁死三个平移自由度），
     * 再给「在这个约束下根本合不到一起」的两个面建面面重合 —— 账本认为两条都是一致的（都要求
     * LINEAR_Z=0），两个关节却互相拉扯，模型持续抽搐。
     */
    private static void geometricConflict() throws Exception {
        System.out.println("[geometric conflict: existing mates must survive the new snap]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(4, 2, 1);

        // 点点配合：两侧都用面心（无方向）⇒ 锁死 LINEAR_X/Y/Z
        final MateRef pointA = point(idA, 0, 0, 1, Direction.UP);
        final MateRef pointB = point(idB, 0, 0, 1, Direction.UP);

        final MateFrames.Plan pointSnap = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.COINCIDENT, pointA, pointB, 0.0, 0.0, false, true);
        final Pose3d afterPoints = snapped(b, pointSnap);
        ok("point mate is satisfied at its own snap pose",
                MateFrames.unsatisfiedReason(MateType.COINCIDENT, pointA, pointB, 0.0, 0.0,
                        a.pose(), a.bounds(), afterPoints, b.bounds()) == null);
        ok("point mate is NOT satisfied if B moves 0.5 away",
                MateFrames.unsatisfiedReason(MateType.COINCIDENT, pointA, pointB, 0.0, 0.0,
                        a.pose(), a.bounds(),
                        new Pose3d(new Vector3d(afterPoints.position()).add(0, 0.5, 0),
                                new Quaterniond(afterPoints.orientation()),
                                new Vector3d(afterPoints.rotationPoint()),
                                new Vector3d(afterPoints.scale())),
                        b.bounds()) != null);

        // 再给「隔着两个方块的另一对面」建面面重合：吸附会要求 B 到另一个位姿
        final MateRef faceA = face(idA, 2, 0, 1, Direction.UP);
        final MateRef faceB = face(idB, 0, 0, 0, Direction.DOWN);
        final MateFrames.Plan faceSnap = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.COINCIDENT, faceA, faceB, 0.0, 0.0, false, true);
        final Pose3d afterFaces = snapped(b, faceSnap);
        ok("the two snaps really are different poses",
                pointSnap.newPositionB().distance(faceSnap.newPositionB()) > 1.0e-6);
        ok("point mate is violated by the face snap -> must be rejected",
                MateFrames.unsatisfiedReason(MateType.COINCIDENT, pointA, pointB, 0.0, 0.0,
                        a.pose(), a.bounds(), afterFaces, b.bounds()) != null);
        // 而面面重合在自己的吸附位姿上当然是成立的
        ok("face-face mate is satisfied at its own snap pose",
                MateFrames.unsatisfiedReason(MateType.COINCIDENT, faceA, faceB, 0.0, 0.0,
                        a.pose(), a.bounds(), afterFaces, b.bounds()) == null);

        // 冗余但一致：同一对面上的「重合 + 平行」在吸附位姿下都成立 → 不能误判为冲突
        final Body c = body(0, 0, 0);
        final Body d = body(4, 2, 1);
        final MateRef up = face(idA, 0, 0, 1, Direction.UP);
        final MateRef down = face(idB, 0, 0, 1, Direction.DOWN);
        final MateFrames.Plan snap = MateFrames.solve(c.pose(), c.bounds(), d.pose(), d.bounds(),
                MateType.COINCIDENT, up, down, 0.0, 0.0, false, true);
        final Pose3d afterSnap = snapped(d, snap);
        ok("coincident faces satisfy coincident",
                MateFrames.unsatisfiedReason(MateType.COINCIDENT, up, down, 0.0, 0.0,
                        c.pose(), c.bounds(), afterSnap, d.bounds()) == null);
        ok("coincident faces also satisfy parallel (redundant but consistent)",
                MateFrames.unsatisfiedReason(MateType.PARALLEL, up, down, 0.0, 0.0,
                        c.pose(), c.bounds(), afterSnap, d.bounds()) == null);
        // 距离钉成 3 时，重合面上的法向间隙是 0 ⇒ 冲突
        ok("coincident faces violate distance 3",
                MateFrames.unsatisfiedReason(MateType.DISTANCE, up, down, 3.0, 3.0,
                        c.pose(), c.bounds(), afterSnap, d.bounds()) != null);
        // LOCK 由调用方按位姿判断，这里保持「不表态」
        ok("LOCK leaves the verdict to the caller",
                MateFrames.unsatisfiedReason(MateType.LOCK, up, down, 0.0, 0.0,
                        c.pose(), c.bounds(), afterSnap, d.bounds()) == null);
    }

    private static MateRef point(final UUID body, final int bx, final int by, final int bz,
                                 final Direction dir) {
        return MateRef.block(body, RefKind.FACE_CENTER, new BlockPos(bx, by, bz), dir, -1);
    }

    /**
     * 落盘往返：{@code MateData.writeMate} → NBT → {@code readMate} 必须还原出<b>完全相同</b>的一条配合。
     *
     * <p>「落盘持久化」是明确需求，而这条路径上有好几处容易出错的地方：方块级参考的 block/face 字段、
     * 结构级参考的 axis 字段、可空的 name/ownerName、以及浮点 value。
     * 一旦这里丢了信息，玩家的配合会在重进世界后悄悄变样 —— 而且不会报任何错。
     *
     * <p>用反射调用私有的 writeMate/readMate：它们是纯静态函数（不需要 ServerLevel），
     * 所以可以在纯 JVM 里验证，不必为了测试放宽生产代码的可见性。
     */
    private static void persistenceRoundTrip() throws Exception {
        System.out.println("[persistence round-trip]");
        final Class<?> dataClass = Class.forName("com.ovo.sablestopnow.server.MateData");
        final java.lang.reflect.Method write = dataClass.getDeclaredMethod("writeMate",
                com.ovo.sablestopnow.mate.Mate.class);
        final java.lang.reflect.Method read = dataClass.getDeclaredMethod("readMate",
                net.minecraft.nbt.CompoundTag.class);
        write.setAccessible(true);
        read.setAccessible(true);

        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final UUID owner = UUID.randomUUID();

        // 覆盖全部参考形态：五种方块级 + 三种结构级
        final MateRef[] refs = {
                face(idA, 3, -2, 7, Direction.NORTH),
                edge(idA, -5, 1, 2, Direction.EAST, 2),
                vertex(idA, 0, 0, 0, Direction.DOWN, 3),
                MateRef.block(idA, RefKind.FACE_CENTER, new BlockPos(3, -2, 7), Direction.NORTH, -1),
                MateRef.block(idA, RefKind.EDGE_CENTER, new BlockPos(-5, 1, 2), Direction.EAST, 2),
                MateRef.center(idA),
                MateRef.axis(idA, 2),
                MateRef.plane(idA, 0),
        };

        int cases = 0;
        for (final MateType type : MateType.values()) {
            for (int r = 0; r < refs.length; r++) {
                final MateRef refA = refs[r];
                // 另一端换一个形态，顺便验证两端字段互相不串
                final MateRef refB = r % 2 == 0
                        ? face(idB, 1, 2, 3, Direction.UP)
                        : MateRef.axis(idB, 1);
                final double value = switch (type.valueKind()) {
                    case ANGLE -> 45.0;
                    case DISTANCE -> -1.5;
                    case DISTANCE_RANGE -> -1.5;
                    case NONE -> 0.0;
                };
                final double value2 = type.needsSecondValue() ? 3.5 : value;
                final Mate original = new Mate(UUID.randomUUID(), type, refA, refB, value, value2,
                        r % 3 == 0, r % 2 == 0 ? "自定义名" : null, owner, r % 2 == 0 ? "Dev" : null);

                final net.minecraft.nbt.CompoundTag tag =
                        (net.minecraft.nbt.CompoundTag) write.invoke(null, original);
                final com.ovo.sablestopnow.mate.Mate restored =
                        (com.ovo.sablestopnow.mate.Mate) read.invoke(null, tag);

                checks++;
                cases++;
                if (restored == null) {
                    failures++;
                    System.out.printf("  FAIL %-12s ref#%d -> readMate returned null%n", type.id(), r);
                } else if (!restored.equals(original)) {
                    failures++;
                    System.out.printf("  FAIL %-12s ref#%d%n    expected %s%n    actual   %s%n",
                            type.id(), r, original, restored);
                }
            }
        }
        System.out.println("  (round-tripped " + cases + " mate shapes across all 7 types x 8 reference kinds)");
    }

    /**
     * 元数据与实际行为的一致性。
     *
     * <p>这里有一条是<b>专门为已经踩过的坑</b>写的：{@code RefKind.hasDirection()} 必须与
     * {@code MateFrames.resolveLocal} 实际解出来的方向一致。之前 VERTEX 被标成「有方向」，
     * 而解算器给的是无方向的点 —— 于是「拿顶点做方向类配合」的校验放行、过约束的自由度预算也算错。
     * 这类「声明的元数据与真实行为不符」的 bug 不会崩、只会静默做错事，只能靠断言钉死。
     */
    private static void metadataConsistency() {
        System.out.println("[metadata consistency]");
        final UUID idA = UUID.randomUUID();
        final BoundingBox3i bounds = new BoundingBox3i(-1, -1, -1, 1, 1, 1);
        final BlockPos block = new BlockPos(0, 0, 0);

        // 1) 每种参考形态：声明「有方向」必须等于解算真的给出方向
        final java.util.Map<RefKind, MateRef> samples = new java.util.LinkedHashMap<>();
        samples.put(RefKind.VERTEX, MateRef.block(idA, RefKind.VERTEX, block, Direction.UP, 0));
        samples.put(RefKind.EDGE, MateRef.block(idA, RefKind.EDGE, block, Direction.UP, 0));
        samples.put(RefKind.FACE, MateRef.block(idA, RefKind.FACE, block, Direction.UP, -1));
        samples.put(RefKind.FACE_CENTER, MateRef.block(idA, RefKind.FACE_CENTER, block, Direction.UP, -1));
        samples.put(RefKind.EDGE_CENTER, MateRef.block(idA, RefKind.EDGE_CENTER, block, Direction.UP, 0));
        samples.put(RefKind.BODY_CENTER, MateRef.center(idA));
        samples.put(RefKind.BODY_AXIS, MateRef.axis(idA, 1));
        samples.put(RefKind.BODY_PLANE, MateRef.plane(idA, 1));
        for (final java.util.Map.Entry<RefKind, MateRef> e : samples.entrySet()) {
            final MateFrames.Resolved resolved = MateFrames.resolveLocal(bounds, e.getValue());
            ok("declared hasDirection matches resolveLocal for " + e.getKey(),
                    e.getKey().hasDirection() == (resolved.direction() != null));
            ok("well-formed: " + e.getKey(), e.getValue().isWellFormed());
        }

        // 2) 无方向的参考不得通过方向类配合的校验
        final MateRef vertex = samples.get(RefKind.VERTEX);
        final MateRef faceRef = samples.get(RefKind.FACE);
        for (final MateType t : MateType.values()) {
            final boolean expectVertexOk = !t.requiresDirection();
            ok("vertex " + (expectVertexOk ? "allowed" : "rejected") + " for " + t.id(),
                    vertex.supports(t) == expectVertexOk);
            ok("face allowed for " + t.id(), faceRef.supports(t));
        }

        // 3) 自动推断类型（选满两端时的默认行为）
        final MateRef edge = samples.get(RefKind.EDGE);
        final MateRef axis = samples.get(RefKind.BODY_AXIS);

        // 2b) 面心/棱心是「纯点」：位置类可用、方向类被拒，锚点必须与面/棱一致
        final MateRef faceCenter = samples.get(RefKind.FACE_CENTER);
        final MateRef edgeCenter = samples.get(RefKind.EDGE_CENTER);
        for (final MateType t : MateType.values()) {
            final String want = t.requiresDirection() ? "rejected" : "allowed";
            ok("face centre " + want + " for " + t.id(), faceCenter.supports(t) == !t.requiresDirection());
            ok("edge centre " + want + " for " + t.id(), edgeCenter.supports(t) == !t.requiresDirection());
        }
        ok("face centre anchor == face centre point",
                MateFrames.resolveLocal(bounds, faceCenter).point()
                        .equals(MateFrames.resolveLocal(bounds, faceRef).point(), 1.0e-12));
        ok("edge centre anchor == edge midpoint",
                MateFrames.resolveLocal(bounds, edgeCenter).point()
                        .equals(MateFrames.resolveLocal(bounds, edge).point(), 1.0e-12));
        ok("face centre + vertex -> COINCIDENT",
                infer(faceCenter, vertex) == MateType.COINCIDENT);
        ok("edge centre + edge centre -> COINCIDENT",
                infer(edgeCenter, edgeCenter) == MateType.COINCIDENT);
        ok("edge+edge -> PARALLEL",
                infer(edge, edge) == MateType.PARALLEL);
        ok("axis+axis -> PARALLEL",
                infer(axis, axis) == MateType.PARALLEL);
        ok("edge+axis -> PARALLEL",
                infer(edge, axis) == MateType.PARALLEL);
        ok("face+face -> COINCIDENT",
                infer(faceRef, faceRef) == MateType.COINCIDENT);
        ok("vertex+vertex -> COINCIDENT",
                infer(vertex, vertex) == MateType.COINCIDENT);
        ok("edge+face -> COINCIDENT",
                infer(edge, faceRef) == MateType.COINCIDENT);
        // 推断出来的类型必须对这两端真的可用，否则「自动成配」会必然失败
        for (final MateRef[] pair : new MateRef[][] { { edge, edge }, { axis, axis },
                { faceRef, faceRef }, { vertex, vertex }, { edge, faceRef } }) {
            final MateType inferred = infer(pair[0], pair[1]);
            ok("inferred " + inferred.id() + " is usable on both ends",
                    pair[0].supports(inferred) && pair[1].supports(inferred));
        }

        // 4) 数值元数据自洽
        for (final MateType t : MateType.values()) {
            ok(t.id() + ": needsValue matches valueKind", t.needsValue() == (t.valueKind() != MateType.ValueKind.NONE));
            ok(t.id() + ": default within [min,max]",
                    t.defaultValue() >= t.minValue() && t.defaultValue() <= t.maxValue());
            ok(t.id() + ": byId round-trips", MateType.byId(t.id()) == t);
            if (t.needsSecondValue()) {
                ok(t.id() + ": needsSecondValue 只可能是区间类型（DISTANCE_RANGE）",
                        t.valueKind() == MateType.ValueKind.DISTANCE_RANGE);
                ok(t.id() + ": default2 within [min,max]",
                        t.defaultValue2() >= t.minValue() && t.defaultValue2() <= t.maxValue());
                ok(t.id() + ": 默认区间不是退化的点", t.defaultValue2() > t.defaultValue());
            }
        }

        // 5) 方块级参考字段缺失时必须判为不合法（避免落到读 null 的路径上）
        ok("block-level without block is not well-formed",
                !new MateRef(idA, RefKind.FACE, null, Direction.UP, -1, -1, null).isWellFormed());
        ok("block-level without face is not well-formed",
                !new MateRef(idA, RefKind.VERTEX, block, null, 0, -1, null).isWellFormed());

        // 6) 碰撞盒子盒：点/棱/面必须按子盒取，而不是按整格
        onSubBoxRefs();
        // 7) 高级配合（比例 / 齿轮）的纯数学部分
        onAdvancedMateRules();
        // 8) 耦合存档的往返（这段代码在游戏里跑一次才看得出来，离线必须钉死）
        onCouplingPersistence();
    }

    /**
     * 比例组 / 齿轮耦合的 NBT 往返。
     *
     * <p>为什么值得单独测：存档是「写进去读不出来」这类 bug 的重灾区，而它在游戏里的表现是
     * 「重启服务器后配合莫名消失」——玩家很难把它和存档序列化联系起来。
     * 这里用真实的 {@code save/load} 路径（不是自己再造一份格式）跑一遍，
     * 并特意带上<b>碰撞箱子盒</b>字段，确认子盒参考也能完整回来。
     */
    private static void onCouplingPersistence() {
        System.out.println("[coupling persistence]");
        final UUID owner = UUID.randomUUID();
        final UUID bodyA = UUID.randomUUID();
        final UUID bodyB = UUID.randomUUID();
        final UUID driver = UUID.randomUUID();

        // 台阶下半：带子盒的参考（确认 box 字段能过 NBT）
        final net.minecraft.world.phys.AABB slab = new net.minecraft.world.phys.AABB(0, 0, 0, 1, 0.5, 1);
        final MateRef driverRef = new MateRef(driver, RefKind.FACE, new BlockPos(1, 2, 3), Direction.UP, -1, -1,
                null);
        final MateRef memberRef = MateRef.block(bodyA, RefKind.FACE, new BlockPos(4, 5, 6), Direction.NORTH, -1,
                slab);

        final com.ovo.sablestopnow.server.CouplingData data = new com.ovo.sablestopnow.server.CouplingData();
        final UUID ratioId = UUID.randomUUID();
        data.putRatio(new com.ovo.sablestopnow.server.CouplingData.RatioGroup(ratioId, driverRef,
                java.util.List.of(new com.ovo.sablestopnow.server.CouplingData.RatioMember(memberRef, 2.5)),
                owner, "tester"));

        final UUID gearId = UUID.randomUUID();
        final MateRef fixedA = MateRef.block(bodyA, RefKind.EDGE, new BlockPos(0, 0, 0), Direction.UP, 1);
        final MateRef gearA = MateRef.block(bodyA, RefKind.EDGE, new BlockPos(0, 0, 0), Direction.UP, 2);
        final MateRef fixedB = MateRef.block(bodyB, RefKind.EDGE, new BlockPos(0, 0, 0), Direction.UP, 1);
        final MateRef gearB = MateRef.block(bodyB, RefKind.EDGE, new BlockPos(0, 0, 0), Direction.UP, 2);
        data.putGear(new com.ovo.sablestopnow.server.CouplingData.GearCoupling(gearId, fixedA, gearA, fixedB, gearB,
                17, 43, true, owner, "tester"));

        final net.minecraft.nbt.CompoundTag tag = data.save(new net.minecraft.nbt.CompoundTag(), null);
        final com.ovo.sablestopnow.server.CouplingData back =
                com.ovo.sablestopnow.server.CouplingData.load(tag, null);

        ok("coupling: ratio group survives a save/load round trip", back.ratios().size() == 1);
        final com.ovo.sablestopnow.server.CouplingData.RatioGroup group = back.ratios().get(ratioId);
        ok("coupling: ratio id and owner survive", group != null && group.owner().equals(owner)
                && "tester".equals(group.ownerName()));
        ok("coupling: driver reference survives", group != null && group.driver().block() != null
                && group.driver().block().getX() == 1 && group.driver().face() == Direction.UP);
        ok("coupling: member k survives", group != null && group.members().size() == 1
                && Math.abs(group.members().get(0).ratio() - 2.5) < 1e-9);
        ok("coupling: collision sub-box survives the round trip", group != null
                && group.members().get(0).ref().box() != null
                && Math.abs(group.members().get(0).ref().box().maxY - 0.5) < 1e-9);

        ok("coupling: gear coupling survives a save/load round trip", back.gears().size() == 1);
        final com.ovo.sablestopnow.server.CouplingData.GearCoupling gear = back.gears().get(gearId);
        ok("coupling: teeth and reverse survive", gear != null && gear.teethA() == 17 && gear.teethB() == 43
                && gear.reversed());
        ok("coupling: gear ratio recomputes after load", gear != null && gear.ratio() != null
                && Math.abs(gear.ratio() + 17.0 / 43.0) < 1e-12);
        ok("coupling: all four axes survive",
                gear != null && gear.fixedA().feature() == 1 && gear.gearA().feature() == 2
                        && gear.fixedB().feature() == 1 && gear.gearB().feature() == 2);
    }

    /**
     * 比例与齿轮的数学规则。
     *
     * <p>这两类配合不能做成虚拟刚体夹具（比例是连续变化的相对位姿、齿轮约束的是角速度比），
     * 所以它们的实现走「每个物理子步做一次运动学辅助」。这里钉死的是那次辅助里
     * <b>可离线验证</b>的那一半：算什么。世界那一半（怎么施加到物理体上）由实现处的注释与实测负责。
     */
    private static void onAdvancedMateRules() {
        System.out.println("[advanced mate rules]");

        // ---- 比例 ----
        final double[] k = com.ovo.sablestopnow.mate.RatioRule.ratios(new double[]{2.0, 4.0, 6.0});
        ok("ratio: ratios are k_i = d_i/d_1", k != null
                && Math.abs(k[0] - 1.0) < 1e-12 && Math.abs(k[1] - 2.0) < 1e-12 && Math.abs(k[2] - 3.0) < 1e-12);
        final double[] targets = com.ovo.sablestopnow.mate.RatioRule.targets(k, 5.0);
        ok("ratio: targets scale with the driver", Math.abs(targets[0] - 5.0) < 1e-12
                && Math.abs(targets[1] - 10.0) < 1e-12 && Math.abs(targets[2] - 15.0) < 1e-12);
        boolean invariant = true;
        for (double driver = -7.5; driver <= 7.5; driver += 0.5) {
            final double[] t = com.ovo.sablestopnow.mate.RatioRule.targets(k, driver);
            for (int i = 0; i < t.length; i++) {
                if (Math.abs(t[i] - k[i] * driver) > 1e-9 || !Double.isFinite(t[i])) {
                    invariant = false;
                }
            }
        }
        ok("ratio: d_i = k_i * d_1 for every driver", invariant);
        ok("ratio: zero initial driver is rejected (no inf/NaN)",
                com.ovo.sablestopnow.mate.RatioRule.ratios(new double[]{0.0, 1.0}) == null
                        && com.ovo.sablestopnow.mate.RatioRule.ratios(new double[]{0.0}) == null);
        ok("ratio: hand-typed k is validated",
                com.ovo.sablestopnow.mate.RatioRule.isValidRatio(-3.0)
                        && !com.ovo.sablestopnow.mate.RatioRule.isValidRatio(Double.NaN)
                        && !com.ovo.sablestopnow.mate.RatioRule.isValidRatio(Double.POSITIVE_INFINITY)
                        && !com.ovo.sablestopnow.mate.RatioRule.isValidRatio(65.0));

        // ---- 齿轮 ----
        final Double half = com.ovo.sablestopnow.mate.GearRule.ratio(20, 40, false);
        final Double halfRev = com.ovo.sablestopnow.mate.GearRule.ratio(20, 40, true);
        ok("gear: ratio = z_A / z_B", half != null && Math.abs(half - 0.5) < 1e-12);
        ok("gear: reverse negates the ratio", halfRev != null && Math.abs(halfRev + 0.5) < 1e-12);
        ok("gear: illegal teeth are rejected (no NaN)",
                com.ovo.sablestopnow.mate.GearRule.ratio(0, 10, false) == null
                        && com.ovo.sablestopnow.mate.GearRule.ratio(10, -3, false) == null
                        && com.ovo.sablestopnow.mate.GearRule.ratio(10, 999999, false) == null);
        boolean gearIdentity = true;
        for (double deltaA = -360.0; deltaA <= 360.0; deltaA += 15.0) {
            final double deltaB = com.ovo.sablestopnow.mate.GearRule.followerAngle(12.0, deltaA, half) - 12.0;
            if (Math.abs(deltaB + half * deltaA) > 1e-9) {
                gearIdentity = false;
            }
        }
        ok("gear: d_theta_B = -ratio * d_theta_A", gearIdentity);
        ok("gear: shortest angular path", Math.abs(com.ovo.sablestopnow.mate.GearRule.shortestDelta(170.0, -170.0) - 20.0) < 1e-9
                && Math.abs(com.ovo.sablestopnow.mate.GearRule.shortestDelta(-170.0, 170.0) + 20.0) < 1e-9
                && Math.abs(com.ovo.sablestopnow.mate.GearRule.shortestDelta(0.0, 350.0) + 10.0) < 1e-9);
        final Double spin = com.ovo.sablestopnow.mate.GearRule.spinAngle(
                new double[]{0, 1, 0}, new double[]{1, 0, 0}, new double[]{0, 0, 1});
        ok("gear: spin angle uses the signed quadrant", spin != null && Math.abs(spin + 90.0) < 1e-9);
        ok("gear: parallel fixed axis is degenerate (null, not jitter)",
                com.ovo.sablestopnow.mate.GearRule.spinAngle(
                        new double[]{0, 1, 0}, new double[]{0, 1, 0}, new double[]{1, 0, 0}) == null);

        // ---- 限速辅助：不得瞬移，角度必须走最短路径（两者都会直接变成能量注入） ----
        ok("assist: linear step is rate limited",
                Math.abs(com.ovo.sablestopnow.mate.AssistMath.approach(0.0, 10.0, 0.25) - 0.25) < 1e-12
                        && Math.abs(com.ovo.sablestopnow.mate.AssistMath.approach(0.0, -10.0, 0.25) + 0.25) < 1e-12);
        ok("assist: already-close target snaps exactly (no jitter)",
                Math.abs(com.ovo.sablestopnow.mate.AssistMath.approach(4.9, 5.0, 0.25) - 5.0) < 1e-12);
        ok("assist: maxStep never exceeds the requested distance",
                Math.abs(com.ovo.sablestopnow.mate.AssistMath.approach(1.0, 1.1, 5.0) - 1.1) < 1e-12);
        ok("assist: angle takes the short way around",
                Math.abs(com.ovo.sablestopnow.mate.AssistMath.approachAngle(170.0, -170.0, 5.0) - 175.0) < 1e-9
                        && Math.abs(com.ovo.sablestopnow.mate.AssistMath.approachAngle(-170.0, 170.0, 5.0) + 175.0) < 1e-9);
        ok("assist: angle step is rate limited",
                Math.abs(com.ovo.sablestopnow.mate.AssistMath.approachAngle(0.0, 100.0, 12.0) - 12.0) < 1e-9);
        ok("assist: maxStep is speed * dt and dt is capped",
                Math.abs(com.ovo.sablestopnow.mate.AssistMath.maxStep(6.0, 0.05) - 0.3) < 1e-12
                        && Math.abs(com.ovo.sablestopnow.mate.AssistMath.maxStep(6.0, 5.0) - 0.3) < 1e-12
                        && Math.abs(com.ovo.sablestopnow.mate.AssistMath.maxStep(-3.0, 0.05)) < 1e-12);
        ok("assist: arrival tests use the short way for angles",
                com.ovo.sablestopnow.mate.AssistMath.arrivedAngle(179.0, -179.0, 3.0)
                        && !com.ovo.sablestopnow.mate.AssistMath.arrivedAngle(179.0, 100.0, 3.0));

        // ---- 测量：比例/齿轮的驱动量必须与标准配合钉住的量在同一根轴上 ----
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body ma = body(0, 0, 0);
        final Body mb = body(3, 0, 0);
        final MateRef faceEast = MateRef.block(idA, RefKind.FACE, new BlockPos(0, 0, 0), Direction.EAST, -1);
        final MateRef faceWest = MateRef.block(idB, RefKind.FACE, new BlockPos(0, 0, 0), Direction.WEST, -1);
        final double measured = MateFrames.measureDistance(ma.pose(), ma.bounds(), faceEast,
                mb.pose(), mb.bounds(), faceWest);
        ok("measure: face-to-face distance is off the two face planes",
                Math.abs(measured - 2.0) < 1e-9);
        final Vector3d measuredAxis = MateFrames.measureAxis(ma.pose(), ma.bounds(), faceEast,
                mb.pose(), mb.bounds(), faceWest);
        ok("measure: face axis is A's normal",
                measuredAxis != null && Math.abs(measuredAxis.x - 1.0) < 1e-9
                        && Math.abs(measuredAxis.y) < 1e-9 && Math.abs(measuredAxis.z) < 1e-9);

        // 线—线：测量轴必须垂直于两条线（沿线量间距 = 让两条棱对滑，是错的）
        final MateRef lineA = MateRef.block(idA, RefKind.EDGE, new BlockPos(0, 0, 0), Direction.UP, 0);
        final MateRef lineB = MateRef.block(idB, RefKind.EDGE, new BlockPos(0, 0, 0), Direction.UP, 0);
        final Body mbLine = body(0, 3, 0);
        final Vector3d lineAxis = MateFrames.measureAxis(ma.pose(), ma.bounds(), lineA,
                mbLine.pose(), mbLine.bounds(), lineB);
        final Vector3d lineDir = MateFrames.worldDirection(ma.pose(),
                MateFrames.resolveLocal(ma.bounds(), lineA));
        ok("measure: line-line axis is perpendicular to the line",
                lineAxis != null && lineDir != null && Math.abs(lineAxis.dot(lineDir)) < 1e-9);
        ok("measure: line-line distance is the perpendicular one",
                Math.abs(MateFrames.measureDistance(ma.pose(), ma.bounds(), lineA,
                        mbLine.pose(), mbLine.bounds(), lineB) - 3.0) < 1e-9);

        // ---- 可控施力点：力的大小必须夹紧、方向必须随结构朝向旋转 ----
        ok("force: magnitude is clamped into 0..1000",
                Math.abs(com.ovo.sablestopnow.force.ForcePoint.clampMagnitude(-5.0)) < 1e-12
                        && Math.abs(com.ovo.sablestopnow.force.ForcePoint.clampMagnitude(5000.0) - 1000.0) < 1e-12
                        && Math.abs(com.ovo.sablestopnow.force.ForcePoint.clampMagnitude(250.0) - 250.0) < 1e-12
                        && Math.abs(com.ovo.sablestopnow.force.ForcePoint.clampMagnitude(Double.NaN)
                        - com.ovo.sablestopnow.force.ForcePoint.DEFAULT_MAGNITUDE) < 1e-12);

        final com.ovo.sablestopnow.force.ForcePoint fp = com.ovo.sablestopnow.force.ForcePoint.onFace(
                idA, new BlockPos(4, 5, 6), Direction.EAST, new Vector3d(5.0, 5.5, 6.5), 120.0);
        ok("force: face normal becomes the force axis",
                Math.abs(fp.localNormal().x - 1.0) < 1e-12 && Math.abs(fp.localNormal().y) < 1e-12
                        && Math.abs(fp.localNormal().z) < 1e-12);
        ok("force: magnitude survives construction", Math.abs(fp.magnitude() - 120.0) < 1e-12);

        // 未旋转：方向 = 局部法线；力矢量长度 = 牛顿数
        final Pose3d identity = new Pose3d(new Vector3d(10, 20, 30), new Quaterniond(),
                new Vector3d(), new Vector3d(1, 1, 1));
        final Vector3d flatForce = fp.worldForce(identity);
        ok("force: world force keeps the magnitude in newtons",
                flatForce != null && Math.abs(flatForce.length() - 120.0) < 1e-9);
        final Vector3d flatPoint = fp.worldPoint(identity);
        ok("force: world point follows the pose translation",
                Math.abs(flatPoint.x - 15.0) < 1e-9 && Math.abs(flatPoint.y - 25.5) < 1e-9
                        && Math.abs(flatPoint.z - 36.5) < 1e-9);

        // 绕 Y 轴 +90°：法线 (1,0,0) 必须跟着结构转到 (0,0,-1)，否则「力贴在表面上」就成了一句空话
        final Pose3d turned = new Pose3d(new Vector3d(0, 0, 0), new Quaterniond().rotateY(Math.PI / 2.0),
                new Vector3d(), new Vector3d(1, 1, 1));
        final Vector3d turnedForce = fp.worldForce(turned);
        ok("force: direction rotates with the structure",
                turnedForce != null && Math.abs(turnedForce.x) < 1e-9
                        && Math.abs(turnedForce.y) < 1e-9 && Math.abs(turnedForce.z + 120.0) < 1e-9);

        // 频道：非法/空注册名必须判为「没配好」，不能默默当成空气物品
        ok("force: empty channel is not configured",
                !fp.withChannel(null, null, null).channelConfigured()
                        && !fp.channelConfigured()
                        && !fp.withChannel("", null, "x").channelConfigured()
                        && !fp.withChannel("no such id!!", null, null).channelConfigured());

        // 两个频率槽：第二槽留空时**保持为空**（服务端据此退回第一槽），两段注册名各存各的。
        // ⚠ 这里只断言「存了什么」：解析成 ItemStack 要查物品注册表，而离线验证器没有引导注册表，
        // 一旦调用就会 NPE 把整个检查器打断（曾经因此只剩 484 条断言）。
        final com.ovo.sablestopnow.force.ForcePoint twoSlots =
                fp.withChannel("minecraft:redstone", "create:brass_sheet", null);
        ok("force: two-slot channel keeps both ids",
                "minecraft:redstone".equals(twoSlots.channelItemId())
                        && "create:brass_sheet".equals(twoSlots.channelItemId2()));
        final com.ovo.sablestopnow.force.ForcePoint oneSlot =
                fp.withChannel("minecraft:redstone", null, null);
        ok("force: empty second slot stays empty (service falls back to slot 1)",
                oneSlot.channelItemId2() == null);
        ok("force: channel label shows the pair",
                twoSlots.channelLabel().contains("minecraft:redstone")
                        && twoSlots.channelLabel().contains("create:brass_sheet"));
        ok("force: identical slots collapse in the label",
                !fp.withChannel("minecraft:redstone", "", null).channelLabel().contains("+"));
        // withChannel 是**原样写入**的 setter：null 就是清空。合并（只改名字时保住两个槽）
        // 由 ForcePointRegistry.update 负责，那里会先用旧值填 null 再调它。
        ok("force: withChannel writes raw values (registry merges)",
                twoSlots.withChannel(null, null, "ch1").channelItemId2() == null
                        && twoSlots.withChannel(twoSlots.channelItemId(), twoSlots.channelItemId2(), "ch1")
                                .channelItemId2().equals("create:brass_sheet"));
    }

    /**
     * 碰撞箱基准：一个方块（比如台阶）有两个碰撞盒时，参考必须落在<b>被命中的那个子盒</b>上。
     *
     * <p>这是「点/棱/面以方块碰撞箱为准」这条需求的回归防线：整格与子盒必须解出不同的点，
     * 且子盒的面心必须落在子盒自己的面上。
     */
    private static void onSubBoxRefs() {
        System.out.println("[collision-box refs]");
        final UUID body = UUID.randomUUID();
        final BlockPos pos = new BlockPos(10, 20, 30);
        final BoundingBox3i bounds = new BoundingBox3i(-1, -1, -1, 1, 1, 1);
        // 台阶下半：y ∈ [0, 0.5]，占满 x/z
        final net.minecraft.world.phys.AABB lower = new net.minecraft.world.phys.AABB(0.0, 0.0, 0.0, 1.0, 0.5, 1.0);
        final MateRef fullFace = MateRef.block(body, RefKind.FACE, pos, Direction.UP, -1);
        final MateRef subFace = MateRef.block(body, RefKind.FACE, pos, Direction.UP, -1, lower);
        final Vector3d fullPoint = MateFrames.resolveLocal(bounds, fullFace).point();
        final Vector3d subPoint = MateFrames.resolveLocal(bounds, subFace).point();
        ok("full-cube up-face stands on y=21", Math.abs(fullPoint.y - 21.0) < 1e-9);
        ok("slab up-face stands on y=20.5", Math.abs(subPoint.y - 20.5) < 1e-9);
        ok("sub-box face differs from full cube", Math.abs(fullPoint.y - subPoint.y) > 0.4);
        ok("sub-box face center is centered in x/z",
                Math.abs(subPoint.x - 10.5) < 1e-9 && Math.abs(subPoint.z - 30.5) < 1e-9);

        final MateRef subCorner = MateRef.block(body, RefKind.VERTEX, pos, Direction.UP, 1, lower);
        final Vector3d corner = MateFrames.resolveLocal(bounds, subCorner).point();
        // index 1 = u 正、v 负；UP 面的 u=x、v=z，于是角点在 (11.0, 20.5, 30.0)
        ok("sub-box corner keeps x/z on the box edge",
                Math.abs(corner.x - 11.0) < 1e-9 && Math.abs(corner.z - 30.0) < 1e-9);

        final MateRef subEdge = MateRef.block(body, RefKind.EDGE, pos, Direction.UP, 0, lower);
        final MateFrames.Resolved edge = MateFrames.resolveLocal(bounds, subEdge);
        ok("sub-box edge has a direction", edge.direction() != null);
        ok("sub-box edge midpoint sits on the box face", Math.abs(edge.point().y - 20.5) < 1e-9);
    }

    /**
     * 通用反「虚空动能」断言：遍历 <b>所有配合类型 × 所有参考类型组合</b>，检查关节本身不自相矛盾。
     *
     * <p>背景：玩家反馈「线线距离会让两个结构相互旋转、攒速度、最后甩飞」。根因就是关节自相矛盾 ——
     * 解算器一边沿某条轴拉、一边把它锁死（或一边锁角向、一边又要求几何对齐），
     * 能量只进不出。这类 bug 不会崩、只会在游戏里越转越快，所以必须用断言钉死：
     * <ul>
     *   <li>用 {@code setLimit} 钉住的轴<b>不能</b>同时出现在锁定轴里（直接对拉）；</li>
     *   <li>锁定的轴数不能超过 6；</li>
     *   <li>距离配合必须有测量轴：面用面法线、棱用「垂直于棱」的方向 —— 沿线量间距等于让两条棱对滑。</li>
     * </ul>
     */
    private static void noSelfConflictingJoints() throws Exception {
        System.out.println("[no self-conflicting joints]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(7, 3, 2);
        final Direction[] dirs = { Direction.UP, Direction.NORTH, Direction.EAST };

        int combos = 0;
        for (final MateType type : MateType.values()) {
            for (final Direction da : dirs) {
                for (final Direction db : dirs) {
                    // 覆盖五类方块级参考（点/棱/棱心/面/面心）
                    final MateRef[][] refPairs = {
                            { face(idA, 0, 0, 0, da), face(idB, 0, 0, 0, db) },
                            { edge(idA, 0, 0, 0, da, 0), edge(idB, 0, 0, 0, db, 1) },
                            { face(idA, 0, 0, 0, da), edge(idB, 0, 0, 0, db, 0) },
                            { edge(idA, 0, 0, 0, da, 0), face(idB, 0, 0, 0, db) },
                            { vertex(idA, 0, 0, 0, da, 0), vertex(idB, 0, 0, 0, db, 3) },
                            { MateRef.block(idA, RefKind.FACE_CENTER, new BlockPos(0, 0, 0), da, -1),
                              MateRef.block(idB, RefKind.FACE_CENTER, new BlockPos(0, 0, 0), db, -1) },
                            { MateRef.block(idA, RefKind.EDGE_CENTER, new BlockPos(0, 0, 0), da, 0),
                              MateRef.block(idB, RefKind.EDGE_CENTER, new BlockPos(0, 0, 0), db, 0) },
                            { face(idA, 0, 0, 0, da), vertex(idB, 0, 0, 0, db, 0) },
                            { edge(idA, 0, 0, 0, da, 0), vertex(idB, 0, 0, 0, db, 0) },
                    };
                    for (final MateRef[] pair : refPairs) {
                        final MateRef ra = pair[0];
                        final MateRef rb = pair[1];
                        combos++;
                        final String label = type.id() + " " + ra.kind().id() + "+" + rb.kind().id()
                                + " " + da + "/" + db;

                        final java.util.Set<ConstraintJointAxis> locked = MateFrames.lockedAxes(type, ra, rb);
                        final ConstraintJointAxis limit = MateFrames.limitAxisOf(type);
                        ok(label + ": limit axis is not also locked",
                                limit == null || !locked.contains(limit));
                        ok(label + ": at most 6 locked axes", locked.size() <= 6);
                        // 方向类的参考不足时必须直接判为「不适用」，而不是给一个拧巴的关节
                        if (!ra.supports(type) || !rb.supports(type)) {
                            continue;
                        }
                        final double v1 = type.needsValue()
                                ? Math.max(0.5, type.defaultValue() + 1.0) : type.defaultValue();
                        final double v2 = type.needsSecondValue() ? v1 + 2.0 : v1;
                        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                                type, ra, rb, v1, v2, false, true);
                        checkJoint(label, a, b, plan);
                        // 精确值类型的数值全部做进关节几何（硬锁），只有「限制」这种区间才交给 setLimit
                        ok(label + ": setLimit 只允许出现在区间类型上",
                                plan.limitAxis() == null || type.needsSecondValue());
                        if (plan.limitAxis() != null) {
                            ok(label + ": 区间不是退化的点（min < max）", plan.isRange());
                            ok(label + ": 被区间占用的轴没有同时被锁",
                                    !plan.locked().contains(plan.limitAxis()));
                        }
                        if (type == MateType.DISTANCE || type == MateType.LIMIT) {
                            // 只有当「两侧都含线」（线—线 / 线—点）时，测量轴才必须垂直于线；
                            // 面—线用的是面法线，本来就不必垂直于棱。
                            final boolean lineA = ra.kind() == RefKind.EDGE || ra.kind() == RefKind.BODY_AXIS;
                            final boolean lineB = rb.kind() == RefKind.EDGE || rb.kind() == RefKind.BODY_AXIS;
                            final boolean linePair = lineA && lineB;
                            final boolean lineWithPoint = (lineA && !rb.hasDirection())
                                    || (lineB && !ra.hasDirection());
                            if (linePair || lineWithPoint) {
                                final Vector3d axis = plan.orientationA().transform(new Vector3d(0, 0, 1));
                                final MateRef lineRef = lineA ? ra : rb;
                                final BoundingBox3ic lineBounds = lineA ? a.bounds() : b.bounds();
                                final Pose3dc linePose = lineA ? a.pose() : b.pose();
                                final Vector3d dir = MateFrames.worldDirection(linePose,
                                        MateFrames.resolveLocal(lineBounds, lineRef));
                                if (dir != null) {
                                    ok(label + ": distance axis ⟂ 线", Math.abs(axis.dot(dir)) < 0.05);
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.println("  (" + combos + " 类型 × 参考类型组合全部检查过)");
    }

    /**
     * 「线线 = 一根虚拟硬杆」的语义测试。
     *
     * <p>玩家反馈：线线重合会被「极易拉开」（沿另一条垂直方向）并抽动弹走。根因是
     * 两条棱共线要**两条垂直方向**的偏移都为 0，只锁一条等于少了一个自由度；
     * 而且吸附时只消了其中一条，关节一建好就带着误差 ⇒ 先抽一下、再被拉回去、几次之后弹飞。
     * 这里就把「共线 / 硬杆长度」直接量出来钉死。
     */
    private static void lineLineRod() throws Exception {
        System.out.println("[line-line rod]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        // B 故意放歪一点：沿两个垂直方向都有偏移，且棱方向不平行
        final Body b = body(6, 2, -3, new Quaterniond()
                .fromAxisAngleDeg(new Vector3d(0.3, 0.2, 0.9).normalize(), 35.0), new Vector3d(1, 1, 1));
        final MateRef ea = edge(idA, 0, 0, 1, Direction.UP, 0);   // 方向 +X
        final MateRef eb = edge(idB, 0, 0, 1, Direction.EAST, 0);

        // ---- 重合：必须共线（两条垂直方向偏移都为 0）----
        final MateFrames.Plan coincide = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.COINCIDENT, ea, eb, 0.0, false, true);
        final Pose3d snappedB = snapped(b, coincide);
        final Vector3d pA = MateFrames.worldPoint(a.pose(), MateFrames.resolveLocal(a.bounds(), ea));
        final Vector3d pB = MateFrames.worldPoint(snappedB, MateFrames.resolveLocal(b.bounds(), eb));
        final Vector3d dirA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), ea));
        final Vector3d dirB = MateFrames.worldDirection(snappedB, MateFrames.resolveLocal(b.bounds(), eb));
        final Vector3d offset = new Vector3d(pB).sub(pA);
        final Vector3d perp = new Vector3d(offset).fma(-offset.dot(dirA), dirA);
        near("线线重合 → 两条棱共线（垂直偏移 0）", perp.length(), 0.0, TOL);
        near("线线重合 → 方向平行", Math.abs(dirA.dot(dirB)), 1.0, TOL);
        ok("线线重合 → 沿棱滑动自由", !coincide.locked().contains(ConstraintJointAxis.LINEAR_X));
        ok("线线重合 → 绕棱自转自由", !coincide.locked().contains(ConstraintJointAxis.ANGULAR_X));
        checkJoint("line-line coincident", a, b, coincide);

        // ---- 距离：一根垂直于两条棱的硬杆，长度 = v；另一条垂直方向必须为 0 ----
        final MateFrames.Plan distance = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.DISTANCE, ea, eb, 2.5, false, true);
        final Pose3d snappedD = snapped(b, distance);
        final Vector3d qB = MateFrames.worldPoint(snappedD, MateFrames.resolveLocal(b.bounds(), eb));
        final Vector3d dirB2 = MateFrames.worldDirection(snappedD, MateFrames.resolveLocal(b.bounds(), eb));
        final Vector3d off2 = new Vector3d(qB).sub(pA);
        final Vector3d perp2 = new Vector3d(off2).fma(-off2.dot(dirA), dirA);
        near("线线距离 → 垂直距离 == 2.5", perp2.length(), 2.5, TOL);
        near("线线距离 → 保持平行", Math.abs(dirA.dot(dirB2)), 1.0, TOL);
        ok("线线距离 → 另一条垂直方向也锁住（否则距离会漂）",
                distance.locked().contains(ConstraintJointAxis.LINEAR_Y)
                        && !distance.locked().contains(ConstraintJointAxis.LINEAR_X));
        checkJoint("line-line distance", a, b, distance);

        // ---- 线—面重合：A 的棱要落在 B 的面内（棱中点贴面 + 棱 ⟂ 面法线）----
        final MateRef lineRef = edge(idA, 0, 0, 1, Direction.UP, 0);
        final MateRef planeRef = face(idB, 0, 0, 1, Direction.UP);
        final MateFrames.Plan lineOnPlane = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.COINCIDENT, lineRef, planeRef, 0.0, false, true);
        final Pose3d snappedL = snapped(b, lineOnPlane);
        final Vector3d qLineA = MateFrames.worldPoint(a.pose(), MateFrames.resolveLocal(a.bounds(), lineRef));
        final Vector3d qPlane = MateFrames.worldPoint(snappedL, MateFrames.resolveLocal(b.bounds(), planeRef));
        final Vector3d nPlane = MateFrames.worldDirection(snappedL, MateFrames.resolveLocal(b.bounds(), planeRef));
        final Vector3d dirLine = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), lineRef));
        near("线—面重合 → 棱中点落在面内", new Vector3d(qLineA).sub(qPlane).dot(nPlane), 0.0, TOL);
        near("线—面重合 → 棱 ⟂ 面法线（棱躺在面里）", Math.abs(dirLine.dot(nPlane)), 0.0, TOL);
        checkJoint("line-on-plane", a, b, lineOnPlane);
    }

    /** 线 ⟂ 面：线的方向必须对齐面法线（以前错算成「躺在面里」，那是重合的语义）。 */
    private static void perpendicularLineOnPlane() throws Exception {
        System.out.println("[perpendicular line vs plane]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(6, 2, -3);
        final MateRef planeRef = face(idA, 0, 0, 1, Direction.UP);
        final MateRef lineRef = edge(idB, 0, 0, 1, Direction.NORTH, 0);   // 方向 +X

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.PERPENDICULAR, planeRef, lineRef, 0.0, false, true);
        final Pose3d snappedB = snapped(b, plan);
        final Vector3d n = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), planeRef));
        final Vector3d d = MateFrames.worldDirection(snappedB, MateFrames.resolveLocal(b.bounds(), lineRef));
        near("线 ⟂ 面 → 线方向对齐面法线", Math.abs(n.dot(d)), 1.0, 1e-4);
        checkJoint("perpendicular line-plane", a, b, plan);
    }

    /** 「限制」= 距离区间：轴不锁、区间传给求解器、初始吸附只把偏移夹进区间。 */
    private static void limitRange() throws Exception {
        System.out.println("[limit range]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(6, 2, -3);
        final MateRef fa = face(idA, 0, 0, 1, Direction.UP);
        final MateRef fb = face(idB, 0, 0, 1, Direction.UP);

        final MateFrames.Plan plan = MateFrames.frames(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.LIMIT, fa, fb, -1.0, 2.0, false);
        ok("限制：limitAxis = LINEAR_Z", plan.limitAxis() == ConstraintJointAxis.LINEAR_Z);
        near("限制：区间下限", plan.limitValue(), -1.0, 1e-9);
        near("限制：区间上限", plan.limitMax(), 2.0, 1e-9);
        ok("限制：这条轴不锁（否则和区间自相矛盾）",
                !plan.locked().contains(ConstraintJointAxis.LINEAR_Z));
        ok("限制：面—面仍然保持平行", plan.locked().contains(ConstraintJointAxis.ANGULAR_X)
                && plan.locked().contains(ConstraintJointAxis.ANGULAR_Y));
        checkJoint("limit", a, b, plan);

        // 吸附：偏移在区间内 → 一动不动；超出 → 只夹到最近的端点
        final Vector3d qA = MateFrames.worldPoint(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d qB = MateFrames.worldPoint(b.pose(), MateFrames.resolveLocal(b.bounds(), fb));
        final Vector3d n = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final double gap = new Vector3d(qB).sub(qA).dot(n);

        final MateFrames.Plan inside = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.LIMIT, fa, fb, gap - 0.5, gap + 0.5, false, true);
        ok("限制：偏移已在区间内 → 不平移", !inside.moveB()
                || inside.newPositionB().distance(b.pose().position()) < 1e-9);

        final MateFrames.Plan below = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.LIMIT, fa, fb, gap + 1.0, gap + 3.0, false, true);
        final Pose3d moved = snapped(b, below);
        final Vector3d qB2 = MateFrames.worldPoint(moved, MateFrames.resolveLocal(b.bounds(), fb));
        near("限制：偏移低于区间 → 夹到下限", new Vector3d(qB2).sub(qA).dot(n), gap + 1.0, 1e-6);
    }

    /** 走生产代码的自动推断，而不是在测试里复刻规则。 */
    private static MateType infer(final MateRef a, final MateRef b) {
        return com.ovo.sablestopnow.server.MateSelectionRegistry.inferType(a, b);
    }
}
