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
                near(label + " limit " + axis + " == " + plan.limitValue(),
                        comp[axis.ordinal()], plan.limitValue(), 1e-6);
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
            persistenceRoundTrip();
            metadataConsistency();
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
        ok("limit axis is LINEAR_Z", plan.limitAxis() == ConstraintJointAxis.LINEAR_Z);
        ok("LINEAR_Z not locked (it is limited instead)",
                !plan.locked().contains(ConstraintJointAxis.LINEAR_Z));
        checkJoint("distance", a, b, plan);
    }

    /** 同心：两条 Y 主轴共线；沿轴滑动与绕轴自转必须自由。 */
    private static void concentricAxes() throws Exception {
        System.out.println("[concentric]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 0, 0);
        final MateRef axA = MateRef.axis(idA, 1);
        final MateRef axB = MateRef.axis(idB, 1);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.CONCENTRIC, axA, axB, 0.0, false, true);

        final Vector3d cA = MateFrames.worldPoint(a.pose(), MateFrames.resolveLocal(a.bounds(), axA));
        final Vector3d cB = MateFrames.worldPoint(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), axB));
        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), axA));
        final Vector3d d = new Vector3d(cB).sub(cA);
        final Vector3d perp = new Vector3d(d).fma(-d.dot(uA), uA);
        near("perpendicular offset == 0", perp.length(), 0.0, TOL);
        ok("free along axis (LINEAR_Z not locked)", !plan.locked().contains(ConstraintJointAxis.LINEAR_Z));
        ok("free spin (ANGULAR_Z not locked)", !plan.locked().contains(ConstraintJointAxis.ANGULAR_Z));
        ok("ANGULAR_X,Y locked", plan.locked().contains(ConstraintJointAxis.ANGULAR_X)
                && plan.locked().contains(ConstraintJointAxis.ANGULAR_Y));
        checkJoint("concentric", a, b, plan);
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
        nearVec("edge0 midpoint", MateRef.faceEdgeMidpoint(block, up, 0), new Vector3d(0.5, 1.0, 0.0), TOL);

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

    /** 相切：方向对齐 + 沿法线钉在给定间距（与距离同机制，但类型/数值语义不同）。 */
    private static void tangentFaces() throws Exception {
        System.out.println("[tangent 1.5]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 0, 0);
        final MateRef fa = face(idA, 0, 0, 1, Direction.UP);
        final MateRef fb = face(idB, 0, 0, 1, Direction.UP);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.TANGENT, fa, fb, 1.5, false, true);

        final Vector3d uA = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d qA = MateFrames.worldPoint(a.pose(), MateFrames.resolveLocal(a.bounds(), fa));
        final Vector3d qB = MateFrames.worldPoint(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), fb));
        near("tangent gap == 1.5", new Vector3d(qB).sub(qA).dot(uA), 1.5, TOL);
        ok("tangent limit axis is LINEAR_Z", plan.limitAxis() == ConstraintJointAxis.LINEAR_Z);
        ok("tangent angular X,Y locked", plan.locked().contains(ConstraintJointAxis.ANGULAR_X)
                && plan.locked().contains(ConstraintJointAxis.ANGULAR_Y));
        checkJoint("tangent", a, b, plan);
    }

    /** 同心用「棱」而不是结构主轴：棱自带方向，走的是同一条路径但参考是方块级的。 */
    private static void concentricEdges() throws Exception {
        System.out.println("[concentric edge-edge]");
        final UUID idA = UUID.randomUUID();
        final UUID idB = UUID.randomUUID();
        final Body a = body(0, 0, 0);
        final Body b = body(10, 5, 0);
        // UP 面的棱 0：方向 +X，中点 (0.5, 1.0, 0.0)
        final MateRef ea = edge(idA, 0, 0, 1, Direction.UP, 0);
        final MateRef eb = edge(idB, 0, 0, 1, Direction.UP, 0);

        final MateFrames.Plan plan = MateFrames.solve(a.pose(), a.bounds(), b.pose(), b.bounds(),
                MateType.CONCENTRIC, ea, eb, 0.0, false, true);

        final Vector3d axis = MateFrames.worldDirection(a.pose(), MateFrames.resolveLocal(a.bounds(), ea));
        final Vector3d pA = MateFrames.worldPoint(a.pose(), MateFrames.resolveLocal(a.bounds(), ea));
        final Vector3d pB = MateFrames.worldPoint(snapped(b, plan), MateFrames.resolveLocal(b.bounds(), eb));
        final Vector3d rel = new Vector3d(pB).sub(pA);
        final Vector3d perp = new Vector3d(rel).fma(-rel.dot(axis), axis);
        near("edge axes collinear (perpendicular offset 0)", perp.length(), 0.0, TOL);
        near("B slid along the axis only (x kept)", new Vector3d(pB).sub(pA).dot(axis), 10.0, TOL);
        checkJoint("concentric-edge", a, b, plan);
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
        expectSet("TANGENT", MateType.TANGENT, fcA, fcB,
                EnumSet.of(ConstraintJointAxis.ANGULAR_X, ConstraintJointAxis.ANGULAR_Y));
        expectSet("CONCENTRIC", MateType.CONCENTRIC, fcA, fcB,
                EnumSet.of(ConstraintJointAxis.LINEAR_X, ConstraintJointAxis.LINEAR_Y,
                        ConstraintJointAxis.ANGULAR_X, ConstraintJointAxis.ANGULAR_Y));
        expectSet("LOCK", MateType.LOCK, ptA, ptB, all);
        expectSet("DISTANCE point-point", MateType.DISTANCE, ptA, ptB,
                EnumSet.of(ConstraintJointAxis.LINEAR_X, ConstraintJointAxis.LINEAR_Y,
                        ConstraintJointAxis.LINEAR_Z));
        expectSet("DISTANCE face-face (limited, not locked)", MateType.DISTANCE, fcA, fcB,
                EnumSet.noneOf(ConstraintJointAxis.class));
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
        ok("TANGENT supports alignment", MateType.TANGENT.supportsAlignment());
        ok("CONCENTRIC supports alignment", MateType.CONCENTRIC.supportsAlignment());
        ok("PERPENDICULAR does NOT (flipping cannot change perpendicularity)",
                !MateType.PERPENDICULAR.supportsAlignment());
        ok("ANGLE does NOT (the value fixes the direction)", !MateType.ANGLE.supportsAlignment());
        ok("DISTANCE does NOT", !MateType.DISTANCE.supportsAlignment());
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
        ok("face-face perpendicular has NO alignment toggle",
                !mateOf(MateType.PERPENDICULAR, fcA, fcB, owner).supportsAlignment());
    }

    private static com.ovo.sablestopnow.mate.Mate mateOf(final MateType type, final MateRef a,
                                                        final MateRef b, final UUID owner) {
        return new com.ovo.sablestopnow.mate.Mate(UUID.randomUUID(), type, a, b, 0.0, false, null, owner, "t");
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

        // 4) 同心（LX,LY,AX,AY）+ 面—面重合（LZ,AX,AY）：一致，共占 5 条轴。
        //    这也确认了「占用轴数超限」不可能是判据 —— 每种方向最多 3 条轴，去重后永远超不过 3。
        MateFrames.Budget d = new MateFrames.Budget();
        ok("concentric accepted", d.add(MateType.CONCENTRIC, fcA, fcB, 0.0));
        ok("concentric + coincident allowed (concentric's AX,AY coincide at 0)",
                d.add(MateType.COINCIDENT, fcA, fcB, 0.0));
        ok("they occupy exactly 5 distinct axes", d.size() == 5);

        // 5) 距离 3 之后再加距离 5 → 同轴不同值，冲突
        MateFrames.Budget e = new MateFrames.Budget();
        ok("distance 3 accepted", e.add(MateType.DISTANCE, fcA, fcB, 3.0));
        ok("distance 3 + distance 5 -> conflict", !e.add(MateType.DISTANCE, fcA, fcB, 5.0));

        // 6) 距离 3 + 相切 3 → 同轴同值，放行（语义上冗余但一致）
        MateFrames.Budget f = new MateFrames.Budget();
        ok("distance 3 accepted again", f.add(MateType.DISTANCE, fcA, fcB, 3.0));
        ok("distance 3 + tangent 3 -> allowed (same value)", f.add(MateType.TANGENT, fcA, fcB, 3.0));
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

        // 覆盖全部参考形态：三种方块级 + 三种结构级
        final MateRef[] refs = {
                face(idA, 3, -2, 7, Direction.NORTH),
                edge(idA, -5, 1, 2, Direction.EAST, 2),
                vertex(idA, 0, 0, 0, Direction.DOWN, 3),
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
                    case NONE -> 0.0;
                };
                final Mate original = new Mate(UUID.randomUUID(), type, refA, refB, value,
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
        System.out.println("  (round-tripped " + cases + " mate shapes across all 8 types x 6 reference kinds)");
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
        ok("edge+edge -> CONCENTRIC",
                infer(edge, edge) == MateType.CONCENTRIC);
        ok("axis+axis -> CONCENTRIC",
                infer(axis, axis) == MateType.CONCENTRIC);
        ok("edge+axis -> CONCENTRIC",
                infer(edge, axis) == MateType.CONCENTRIC);
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
        }

        // 5) 方块级参考字段缺失时必须判为不合法（避免落到读 null 的路径上）
        ok("block-level without block is not well-formed",
                !new MateRef(idA, RefKind.FACE, null, Direction.UP, -1, -1).isWellFormed());
        ok("block-level without face is not well-formed",
                !new MateRef(idA, RefKind.VERTEX, block, null, 0, -1).isWellFormed());
    }

    /** 走生产代码的自动推断，而不是在测试里复刻规则。 */
    private static MateType infer(final MateRef a, final MateRef b) {
        return com.ovo.sablestopnow.server.MateSelectionRegistry.inferType(a, b);
    }
}
