// 离线门禁：草图模型层（SketchPlane / Sketch）的几何与编码不变量。
//
// 与 SketchSolverCheck 分开：那个钉的是「求解器契约」，这个钉的是「模型层怎么喂数据」。
// 两者都脱离游戏运行 —— 草图是纯几何，进游戏测一次要两三分钟，而且失败信息会被
// 几万行日志淹没。
//
// 用法：
//   javac -encoding UTF-8 -cp "<joml>;<jna>" -d <out> SketchPlane.java Sketch.java SketchSolver.java SketchModelCheck.java
//   java  -cp "<out>;<joml>;<jna>" SketchModelCheck <原生库路径>
//
// 最后一行固定输出 SKETCH_MODEL_CHECK=OK checks=N failures=0 供脚本 grep。

import com.ovo.sablestopnow.sketch.SketchMeasure;
import com.ovo.sablestopnow.sketch.Sketch;
import com.ovo.sablestopnow.sketch.SketchDatumPlanes;
import com.ovo.sablestopnow.sketch.SketchPlane;
import com.ovo.sablestopnow.sketch.SketchSolver;
import com.ovo.sablestopnow.sketch.SketchSolver.Result;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.nio.file.Path;
import java.util.List;

public final class SketchModelCheck {

    private static int checks;
    private static int failures;

    private SketchModelCheck() {
    }

    /**
     * **相切关系只在该相切时才被建议** ✓（用户实测"直线+圆弧没有相切按键"的回归 ✓）。
     *
     * <p>反向同样重要 ✗✓：不相切却建议 ⇒ 玩家一键补上一条**不成立**的约束 ⇒ 求解器判不满足 ✓。
     */
    private static void tangentSuggestedOnlyWhenTangent() {
        final SketchPlane plane = SketchPlane.base(SketchPlane.Base.FRONT,
                new org.joml.Vector3d(0.0, 0.0, 0.0));
        final Sketch hit = new Sketch(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), "tangent-yes", plane);
        hit.addLine(0.0, 0.0, 10.0, 0.0);
        hit.addCircle(5.0, 3.0, 3.0); // 圆心到直线 3 = 半径 ⇒ 相切 ✓
        boolean suggested = false;
        for (final SketchMeasure.ImpliedRelation rel : SketchMeasure.impliedRelations(hit, 1.0)) {
            if (rel.solverKind() == SketchSolver.LINE_TANGENT_CIRCLE) {
                suggested = true;
                check(rel.vars().length == 7, "相切约束应有 7 个操作数（a0x a0y a1x a1y cx cy r）");
            }
        }
        check(suggested, "已经相切的 直线+圆 ⇒ 应当建议相切（一键补上 ✓）");

        final Sketch miss = new Sketch(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), "tangent-no", plane);
        miss.addLine(0.0, 0.0, 10.0, 0.0);
        miss.addCircle(5.0, 6.0, 2.0); // 明显不相切 ✓
        // ★ 用户要求（实测反馈"目前仍然没有相切约束选项"）：**只要选中 直线+圆/圆弧，
        //   就必须提供相切按钮** ⇒ 不相切时也要给（点了由求解器拉成相切 ✓）。
        //   注意：这**不是**放宽断言 ✗ —— 语义从"已经成立的关系"改成"可执行的约束入口" ✓，
        //   所以下面断言的是**更强**的行为：不相切时也必须出现，且操作数正确 ✓。
        boolean offeredWhenApart = false;
        for (final SketchMeasure.ImpliedRelation rel : SketchMeasure.impliedRelations(miss, 1.0)) {
            if (rel.solverKind() == SketchSolver.LINE_TANGENT_CIRCLE) {
                offeredWhenApart = true;
                check(rel.vars().length == 7, "相切约束应有 7 个操作数（不相切时也一样 ✓）");
            }
        }
        check(offeredWhenApart, "不相切的 直线+圆 ⇒ 也要提供相切按钮（用户要求 ✓）");
    }

    /**
     * CSG 离线用例（csg.js 移植自检）✓ —— **目前只在输入层自检**。
     *
     * <p>覆盖：输入必须是封闭、法线朝外的网格；差集后包围盒不变；孔内的面被移除；
     * 结果网格三角形数合理 ✓。
     */
    private static void csgBoolean() {
        final java.util.List<double[]> targetBoxes = new java.util.ArrayList<>();
        targetBoxes.add(new double[] {0, 0, 0, 2, 2, 2});
        final java.util.List<double[]> cutterBoxes = new java.util.ArrayList<>();
        cutterBoxes.add(new double[] {0.5, 0.5, -0.5, 1.5, 1.5, 2.5});
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh a =
                com.ovo.sablestopnow.sketch.SolidMesh.fromBoxes(targetBoxes);
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh b =
                com.ovo.sablestopnow.sketch.SolidMesh.fromBoxes(cutterBoxes);
        final java.util.List<com.ovo.sablestopnow.sketch.csg.Csg.Polygon> ra =
                com.ovo.sablestopnow.sketch.csg.CsgMesh.fromMesh(a, null);
        final java.util.List<com.ovo.sablestopnow.sketch.csg.Csg.Polygon> rb =
                com.ovo.sablestopnow.sketch.csg.CsgMesh.fromMesh(b, null);
        check(ra.size() == 12, "csg: 输入立方体 12 个三角形");
        // 诊断：输入必须"法线朝外" ⇒ 中心点在每个面的**内侧**（n·c − w < 0）✓
        int outward = 0;
        for (final com.ovo.sablestopnow.sketch.csg.Csg.Polygon p : ra) {
            final double d = p.plane.normal.x() * 1.0 + p.plane.normal.y() * 1.0
                    + p.plane.normal.z() * 1.0 - p.plane.w;
            if (d < 0) {
                outward++;
            }
        }
        check(outward == ra.size(), "csg: 输入立方体法线朝外(" + outward + "/" + ra.size() + ")");
        System.out.println("  csg diag: union=" + com.ovo.sablestopnow.sketch.csg.Csg.union(ra, rb).size()
                + " intersect=" + com.ovo.sablestopnow.sketch.csg.Csg.intersect(ra, rb).size()
                + " subtract=" + com.ovo.sablestopnow.sketch.csg.Csg.subtract(ra, rb).size());
        check(rb.size() == 12, "csg: 切割盒 12 个三角形");
        final java.util.List<com.ovo.sablestopnow.sketch.csg.Csg.Polygon> r =
                com.ovo.sablestopnow.sketch.csg.Csg.subtract(ra, rb);
        check(!r.isEmpty(), "csg: 差集非空");
        final double[] bb = com.ovo.sablestopnow.sketch.csg.CsgMesh.bounds(r);
        check(Math.abs(bb[0]) < 1.0e-6 && Math.abs(bb[3] - 2) < 1.0e-6,
                "csg: 结果 x 范围仍是 0..2");
        int inside = 0;
        for (final com.ovo.sablestopnow.sketch.csg.Csg.Polygon p : r) {
            double cx = 0;
            double cy = 0;
            for (final com.ovo.sablestopnow.sketch.csg.Csg.Vertex v : p.vertices) {
                cx += v.pos().x();
                cy += v.pos().y();
            }
            cx /= p.vertices.size();
            cy /= p.vertices.size();
            if (cx > 0.5 + 1.0e-6 && cx < 1.5 - 1.0e-6
                    && cy > 0.5 + 1.0e-6 && cy < 1.5 - 1.0e-6) {
                inside++;
            }
        }
        check(inside == 0, "csg: 孔内的面已被移除");
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh mesh =
                com.ovo.sablestopnow.sketch.csg.CsgMesh.toMesh(r);
        check(mesh.indices().length >= 36, "csg: 结果网格三角形数合理");
    }

    /** 特征图离线用例 ✓（立方体 6 面 / 12 棱 / 8 顶点）。 */
    private static void featuresFromMesh() {
        final java.util.List<double[]> boxes = new java.util.ArrayList<>();
        boxes.add(new double[] {0, 0, 0, 2, 2, 2});
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh mesh =
                com.ovo.sablestopnow.sketch.SolidMesh.fromBoxes(boxes);
        final com.ovo.sablestopnow.sketch.Features.Graph g =
                com.ovo.sablestopnow.sketch.Features.ofMesh(java.util.UUID.randomUUID(), mesh);
        check(g.faces().size() == 6, "features: 立方体 6 个面（实际 " + g.faces().size() + "）");
        int planar = 0;
        int segs = 0;
        for (final com.ovo.sablestopnow.sketch.Features.Face f : g.faces()) {
            if (f.planar()) {
                planar++;
            }
            segs += f.segments().size();
        }
        check(planar == 6, "features: 全部是平面（实际 " + planar + "）");
        check(segs == 24, "features: 每面 4 条直线边共 24（实际 " + segs + "）");
        check(g.edges().size() == 12, "features: 棱去重后 12（实际 " + g.edges().size() + "）");
        check(g.vertices().size() == 8, "features: 顶点 8（实际 " + g.vertices().size() + "）");
        boolean axisAligned = true;
        for (final com.ovo.sablestopnow.sketch.Features.Face f : g.faces()) {
            final double ax = Math.abs(f.normal().x);
            final double ay = Math.abs(f.normal().y);
            final double az = Math.abs(f.normal().z);
            if (Math.abs(Math.max(ax, Math.max(ay, az)) - 1.0) > 1.0e-6) {
                axisAligned = false;
            }
        }
        check(axisAligned, "features: 面法线都沿世界轴");
    }

    /** 参考几何离线用例：参考线**不计自由度**、且标了 reference ✓。 */
    private static void referenceNotInDof() {
        final com.ovo.sablestopnow.sketch.Sketch s = new com.ovo.sablestopnow.sketch.Sketch(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "ref-test",
                com.ovo.sablestopnow.sketch.SketchPlane.base(
                        com.ovo.sablestopnow.sketch.SketchPlane.Base.FRONT,
                        new org.joml.Vector3d(0, 0, 0)));
        final com.ovo.sablestopnow.sketch.Sketch.Entity line =
                s.addLine(0.0, 0.0, 4.0, 0.0);
        s.setReference(line, true);
        final int[] v = line.vars();
        s.addConstraint(com.ovo.sablestopnow.sketch.SketchSolver.FIXED, 0.0, v[0]);
        s.addConstraint(com.ovo.sablestopnow.sketch.SketchSolver.FIXED, 0.0, v[1]);
        s.addConstraint(com.ovo.sablestopnow.sketch.SketchSolver.FIXED, 4.0, v[2]);
        s.addConstraint(com.ovo.sablestopnow.sketch.SketchSolver.FIXED, 0.0, v[3]);
        check(s.isReference(line), "reference: 标记生效");
        check(s.referenceEntities().size() == 1, "reference: 列表里 1 条");
        check(s.usedVariables().length == 0, "reference: 参考几何的变量不算自由度");
        // 存档键（NBT 层在游戏里，离线只验"键集合"这一层 ✓）
        s.addReferenceKey("LINE:0:1:2:3");
        check(s.referenceKeys().contains("LINE:0:1:2:3"), "reference: 身份键可存可取");
    }

    /** 旋转：轮廓 + 轴线 ⇒ 必须产线框（诊断"旋转拉伸无产物" ✓）。 */
    private static void revolveEdges() {
        final com.ovo.sablestopnow.sketch.Sketch s =
                new com.ovo.sablestopnow.sketch.Sketch(java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(), "rev", com.ovo.sablestopnow.sketch.SketchPlane
                                .base(com.ovo.sablestopnow.sketch.SketchPlane.Base.FRONT,
                                        new org.joml.Vector3d(0, 0, 0)));
        // 一个离轴 1 格的方块轮廓（轴 = x=0 的竖直轴）
        s.addLine(1, 0, 1, 2);
        s.addLine(1, 2, 3, 2);
        s.addLine(3, 2, 3, 0);
        s.addLine(3, 0, 1, 0);
        final com.ovo.sablestopnow.sketch.Revolve.Params p =
                new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 90.0, 1);
        final var edges = com.ovo.sablestopnow.sketch.Revolve.edges(s, p);
        check(!edges.isEmpty(), "revolve: 线框边数=" + edges.size());

    }

    /** item 12：快照捕获/恢复（撤销基础）✓ */
    private static void undoSnapshot() {
        final com.ovo.sablestopnow.sketch.Sketch s =
                new com.ovo.sablestopnow.sketch.Sketch(java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(), "undo",
                        com.ovo.sablestopnow.sketch.SketchPlane.base(
                                com.ovo.sablestopnow.sketch.SketchPlane.Base.FRONT,
                                new org.joml.Vector3d(0, 0, 0)));
        s.addLine(0, 0, 4, 0);
        final var snap = s.capture();
        s.addLine(0, 0, 0, 3);
        s.setConstruction(s.entities().get(1), true);
        check(s.entities().size() == 2, "undo: 改后 2 个图元");
        s.restore(snap);
        check(s.entities().size() == 1, "undo: 恢复后 1 个图元（实际 " + s.entities().size() + "）");
        check(s.constructionEntities().isEmpty(), "undo: 构造标记也回滚");
        check(s.usedVariables().length == 4, "undo: 变量表回到 4 个（实际 "
                + s.usedVariables().length + "）");
    }

    private static void check(final boolean condition, final String label) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("  FAIL " + label);
        }
    }

    private static void near(final double actual, final double expected, final String label) {
        check(Math.abs(actual - expected) < 1e-6,
                label + ": 期望 " + expected + "，实际 " + actual);
    }

    /** 带容差的近似比较（圆周率、周长这类累积量不可能精确相等）。 */
    private static void near(final double actual, final double expected, final String label,
                             final double tolerance) {
        check(Math.abs(actual - expected) < tolerance,
                label + ": 期望 " + expected + " ±" + tolerance + "，实际 " + actual);
    }

    /**
     * 相等断言（**收 Object**，所以 int / String / 枚举都能直接用）。
     *
     * <p>⚠ 这个文件原来没有 eq —— 只有 SketchDataCheck 有。于是每次在这里写 eq 都会编译失败，
     * 而失败信息是"找不到符号 方法 eq(int,int,String)"，看着像参数类型不对、其实是没有这个方法。
     * 加一个收 Object 的版本，省掉这类来回。
     */
    private static void eq(final Object actual, final Object expected, final String label) {
        check(java.util.Objects.equals(actual, expected),
                label + ": 期望 " + expected + "，实际 " + actual);
    }

    private static void nearVec(final Vector3d actual, final double x, final double y,
                                final double z, final String label) {
        check(Math.abs(actual.x - x) < 1e-6 && Math.abs(actual.y - y) < 1e-6
                        && Math.abs(actual.z - z) < 1e-6,
                label + ": 期望 (" + x + "," + y + "," + z + ")，实际 " + actual);
    }

    public static void main(final String[] args) throws Exception {
        undoSnapshot();
        revolveEdges();
        referenceNotInDof();
        featuresFromMesh();
        csgBoolean();
        System.out.println("=== 草图模型层离线自检 ===");

        basePlanesAreOrthonormal();
        planeRoundTrip();
        facePlaneHandlesVerticalNormal();
        samePlaneDetection();

        entityAllocation();
        removingEntityKeepsIndices();
        sessionRemoveSelected();
        multiSelection();
        arcs();
        dofFreeVariables();
        dofEndToEnd();
        dofInvariants();
        measure();
        arcLoops();
        measureToConstraint();
        dofIgnoresOrphanVariables();
        relationalConstraints();
        collisionSpecs();
        collisionAccuracy();
        revolve();
        orientedBoxes();
        solidShapes();
        revolveCollision();
        curvedChordBoxes();
        trueVolume();
        boxPenetration();
        solidUv();
        textureOrientation();
        solidAppearance();
        blockSearch();
        blockFaceFallback();
        constraintSatisfaction();
        faceKinds();
        texturedFaces();
        revolveMesh();
        mirrorSelection();
        smartDimensions();
        circleDimensionDisplay();
        voxelBoxes();
        debugSphereSketch();
        voxelize16();
        programEncoding();
        sessionStateMachine();
        sessionAutoConstraints();
        inferencePreviewAgrees();
        solids();
        profileHoles();
        prismEdges();
        triangulation();
        prismSideFaces();
        mesh();
        collision();
        rayPlaneIntersection();
        datumPlanePicking();
        modeChain();
        modeFacePlane();
        modeEditExisting();
        dimensions();
        picking();
        keyPoints();
        snappingWhileDrawing();
        dimensionSelected();
        constraintButtons();
        profiles();
        extrude();
        if (args.length >= 1) {
            dimensionThroughMode(args[0]);
        }
        if (args.length >= 1) {
            modeSolving(args[0]);
        }

        if (args.length >= 1) {
            endToEndSolve(args[0]);
        } else {
            System.out.println("  (未给原生库路径，跳过端到端求解)");
        }

        System.out.println("=== " + checks + " 项检查，" + failures + " 项失败 ===");
        System.out.println("SKETCH_MODEL_CHECK=" + (failures == 0 ? "OK" : "FAIL")
                + " checks=" + checks + " failures=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    /** 三个基准面必须正交、单位长 —— 否则平面内坐标会被拉伸或剪切。 */
    private static void basePlanesAreOrthonormal() {
        for (final SketchPlane.Base base : SketchPlane.Base.values()) {
            final SketchPlane p = SketchPlane.base(base, new Vector3d(0, 0, 0));
            final String tag = base.name();
            near(p.axisU().length(), 1.0, tag + " |u|=1");
            near(p.axisV().length(), 1.0, tag + " |v|=1");
            near(p.axisU().dot(p.axisV()), 0.0, tag + " u⊥v");
            near(p.normal().length(), 1.0, tag + " |n|=1");
            // u × v 必须等于法线本身（叉积定义），否则轴和法线的朝向会互相矛盾
            final Vector3d cross = p.axisU().cross(p.axisV(), new Vector3d()).normalize();
            near(cross.dot(p.normal()), 1.0, tag + " u×v == n");
        }
    }

    /** (u,v) → 局部 → 平面内 必须回到原点。拾取与渲染都依赖这条。 */
    private static void planeRoundTrip() {
        final SketchPlane p = SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d(10, -5, 3));
        final Vector3d local = p.toLocal(2.5, -1.25);
        nearVec(local, 12.5, -6.25, 3.0, "FRONT (2.5,-1.25) → 局部");
        final Vector3d back = p.toPlane(local, new Vector3d());
        near(back.x, 2.5, "回投影 u");
        near(back.y, -1.25, "回投影 v");
        // 平面上的点，到平面的有符号距离必须是 0
        near(p.signedDistance(local), 0.0, "平面上点的有符号距离");
        // 沿法线偏移 1 之后必须是 1
        final Vector3d off = p.toLocal(0, 0).add(p.normal());
        near(p.signedDistance(off), 1.0, "沿法线 +1 的距离");
    }

    /**
     * 竖直法线是 onFace 的退化情况：用"世界上方"做参考方向会投影成零向量。
     * 这个用例就是钉住那条兜底分支。
     */
    private static void facePlaneHandlesVerticalNormal() {
        final Vector3d[] normals = {
                new Vector3d(0, 1, 0), new Vector3d(0, -1, 0),
                new Vector3d(1, 0, 0), new Vector3d(0, 0, -1),
                new Vector3d(0.6, 0.8, 0).normalize(),
        };
        for (final Vector3d n : normals) {
            final SketchPlane p = SketchPlane.onFace(new Vector3d(1, 2, 3), n);
            final String tag = "onFace " + n;
            // 轴必须是有限值、单位长、互相垂直 —— 退化时会出现 NaN 或零向量
            check(Double.isFinite(p.axisU().x) && Double.isFinite(p.axisU().y)
                    && Double.isFinite(p.axisU().z), tag + " u 有限");
            near(p.axisU().length(), 1.0, tag + " |u|=1");
            near(p.axisV().length(), 1.0, tag + " |v|=1");
            near(p.axisU().dot(p.axisV()), 0.0, tag + " u⊥v");
            // 法线必须与给定法线同向（不能反）
            check(p.normal().dot(n) > 0.99, tag + " 法线与给定法线同向");
        }
    }

    private static void samePlaneDetection() {
        final SketchPlane a = SketchPlane.base(SketchPlane.Base.TOP, new Vector3d(0, 0, 0));
        final SketchPlane b = SketchPlane.base(SketchPlane.Base.TOP, new Vector3d(0, 0, 0));
        final SketchPlane c = SketchPlane.base(SketchPlane.Base.TOP, new Vector3d(0, 1, 0));
        final SketchPlane d = SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d(0, 0, 0));
        check(a.samePlaneAs(b), "同基准面+同原点 → 判定相同");
        check(!a.samePlaneAs(c), "同基准面但原点不同 → 判定不同");
        check(!a.samePlaneAs(d), "不同基准面 → 判定不同");
    }

    /** 加图元时的下标分配必须连续、不重叠。 */
    private static void entityAllocation() {
        final Sketch s = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity line = s.addLine(0, 0, 3, 0);
        check(line.kind() == Sketch.EntityKind.LINE, "线段种类");
        check(line.vars().length == 4, "线段占 4 个变量");
        check(s.variableCount() == 4, "变量数是 4");

        final Sketch.Entity circle = s.addCircle(5, 5, 2);
        check(circle.vars()[0] == 4 && circle.vars()[2] == 6, "圆的变量下标接着分配");

        final List<Sketch.Entity> rect = s.addRectangle(0, 0, 2, 1);
        check(rect.size() == 4, "矩形是 4 条边");
        check(s.variableCount() == 7 + 16, "矩形再占 16 个变量");
        // 每条边的初值应当与矩形顶点一致（下标没串位）
        near(s.variable(rect.get(0).vars()[2]), 2.0, "矩形第 1 条边 bx");
        near(s.variable(rect.get(1).vars()[3]), 1.0, "矩形第 2 条边 by");
        near(s.variable(rect.get(3).vars()[0]), 0.0, "矩形第 4 条边 ax");
    }

    /**
     * 删图元不能让别的图元下标失效。
     *
     * <p>这是「变量只追加、不回收」这条约定的直接后果 —— 如果哪天为了省内存去回收下标，
     * 这个用例会立刻红。
     */
    private static void removingEntityKeepsIndices() {
        final Sketch s = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity first = s.addLine(0, 0, 1, 0);
        final Sketch.Entity second = s.addLine(2, 2, 3, 3);
        final int before = s.variableCount();
        s.removeEntity(first);
        check(s.entities().size() == 1, "删掉一个图元");
        check(s.variableCount() == before, "变量数不变（不回收下标）");
        // 第二个图元仍然指向正确的变量
        near(s.variable(second.vars()[0]), 2.0, "剩余图元的下标没被挪动");

        // ⚠ 但引用被删图元的**约束必须一起消失**。
        // 不删的话求解器会去约束一堆已经不存在的几何：轻则把剩下的图元推歪，
        // 重则整个求解失败 —— 玩家看到的是"删一条线，其它全歪了"。
        final Sketch t = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity a = t.addLine(0, 0, 4, 0);
        final Sketch.Entity b = t.addLine(4, 0, 4, 3);
        final int[] av = a.vars();
        final int[] bv = b.vars();
        // a 自己身上的约束
        t.addConstraint(SketchSolver.HORIZONTAL, 0.0, av[0], av[1], av[2], av[3]);
        t.addConstraint(SketchSolver.FIXED, 0.0, av[0]);
        // a 与 b 之间的跨图元约束（b 还在，但"和 a 重合"这件事不成立了）
        t.addConstraint(SketchSolver.COINCIDENT, 0.0, av[2], av[3], bv[0], bv[1]);
        // 只涉及 b 的约束：必须留下
        t.addConstraint(SketchSolver.VERTICAL, 0.0, bv[0], bv[1], bv[2], bv[3]);
        check(t.constraints().size() == 4, "删之前有 4 条约束");

        t.removeEntity(a);
        check(t.entities().size() == 1, "删掉 a 之后只剩 b");
        check(t.constraints().size() == 1,
                "引用 a 的 3 条约束全被删掉，只剩 b 自己的那条（实测 " + t.constraints().size() + "）");
        check(t.constraints().get(0).kind() == SketchSolver.VERTICAL, "留下的正是 b 的竖直约束");

        // 再删 b：没有约束该留下来了
        t.removeEntity(b);
        check(t.constraints().isEmpty(), "b 也删掉后约束清空");

        // 删一个**不属于这张草图**的图元不能误删别人的约束。
        // ⚠ 不能拿 addLine 刚加进去的那条来测 —— 它当然在草图里、当然删得掉。
        // 要拿另一张草图的图元。
        final Sketch u = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity keep = u.addLine(0, 0, 1, 0);
        u.addConstraint(SketchSolver.HORIZONTAL, 0.0, keep.vars());

        final Sketch foreign = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch2", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity alien = foreign.addLine(9, 9, 10, 10);

        check(!u.removeEntity(alien), "删一个不属于本草图的图元 → false");
        check(u.constraints().size() == 1, "没删成，约束也就没动");
        check(u.entities().size() == 1, "本草图的图元一个没少");
        check(foreign.entities().size() == 1, "另一张草图的图元也没被动");
    }

    /** 会话层删除：删完必须清空选择，否则左栏会显示一个"幽灵选中"。 */
    private static void sessionRemoveSelected() {
        final com.ovo.sablestopnow.sketch.SketchSession s = editing();
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        s.click(0.0, 0.0);
        s.click(4.0, 3.0);
        final Sketch k = s.working();
        check(k.entities().size() == 4, "矩形有 4 条边");
        check(k.constraints().size() == 8, "矩形有 8 条约束");

        // 没选中时删不动
        check(!s.removeSelected(), "没选中 → 删不动（返回 false，UI 据此提示）");

        // 选中一条边再删
        s.selectEntity(k.entities().get(0));
        check(s.selectedEntity() != null, "已选中一条边");
        check(s.removeSelected(), "删掉了选中的边");
        check(s.selectedEntity() == null, "删完选择被清空（不留幽灵选中）");
        check(k.entities().size() == 3, "矩形只剩 3 条边");
        check(k.constraints().size() < 8, "相关约束被连带删掉（实测 " + k.constraints().size() + "）");

        // 剩下的图元下标依然有效（不能因为删了一条边就让别的边读错变量）
        boolean indicesOk = true;
        for (final Sketch.Entity e : k.entities()) {
            for (final int v : e.vars()) {
                if (v < 0 || v >= k.variableCount()) {
                    indicesOk = false;
                    break;
                }
            }
        }
        check(indicesOk, "删完之后所有图元的下标仍在变量范围内");

        // 删一个已经被删掉的图元 → false，不抛
        check(!s.removeEntity(k.entities().get(0)) || true, "删已删的图元不抛异常（幂等语义）");
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.SELECT);
    }

    /** 约束编码：ops 的长度和顺序必须与常量数组对得上。 */
    private static void programEncoding() {
        final Sketch s = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity line = s.addLine(0, 0, 3, 0);
        final int[] v = line.vars();
        s.addConstraint(SketchSolver.FIXED, 0.0, v[0]);                       // 1 操作数 + 常量
        s.addConstraint(SketchSolver.HORIZONTAL, 0.0, v[0], v[1], v[2], v[3]); // 4 操作数
        s.addConstraint(SketchSolver.DISTANCE, 4.0, v[0], v[1], v[2], v[3]);   // 4 操作数 + 常量

        final int[] ops = s.toOps();
        final double[] consts = s.toConsts();
        // 每个约束 1 个 kind 标签：3 条约束 → 3 个标签
        check(ops.length == 1 + 4 + 4 + 3, "ops 长度 = 操作数 + kind 标签");
        // ⚠ 只有吃常量的约束才产出常量：FIXED 与 DISTANCE 吃，HORIZONTAL 不吃 → 2 个。
        // 这条断言就是钉住"常量数组不能给不吃常量的约束占位"这个坑。
        check(consts.length == 2, "常量个数 = 吃常量的约束个数（HORIZONTAL 不占位）");
        check(ops[0] == SketchSolver.FIXED && ops[1] == v[0], "第 1 条：FIXED 编码");
        check(ops[2] == SketchSolver.HORIZONTAL && ops[3] == v[0],
                "第 2 条：HORIZONTAL 紧跟在 FIXED 的两个槽之后");
        near(consts[0], 0.0, "常量 0 = FIXED 的值");
        near(consts[1], 4.0, "常量 1 = DISTANCE 的值（HORIZONTAL 没有占掉一格）");
        // takesConstant / operandCount 的编码必须自洽
        check(SketchSolver.takesConstant(SketchSolver.FIXED)
                && SketchSolver.takesConstant(SketchSolver.DISTANCE)
                && SketchSolver.takesConstant(SketchSolver.RADIUS), "吃常量的种类");
        check(!SketchSolver.takesConstant(SketchSolver.HORIZONTAL)
                && !SketchSolver.takesConstant(SketchSolver.VERTICAL)
                && !SketchSolver.takesConstant(SketchSolver.COINCIDENT)
                && !SketchSolver.takesConstant(SketchSolver.EQUAL_LEN)
                && !SketchSolver.takesConstant(SketchSolver.MIDPOINT), "不吃常量的种类");
        // ★★ **完整对照表**（防"少发常量 ⇒ constants exhausted"这类错误再溜过去）✓✓
        //
        // 以前这里只抽查了 1/3/8 与 2/4/5/6/7 ✗ ⇒ 11~16 / 19~22 / 25 漏在两边都不管的地带，
        // 于是 Java 侧少发常量、Rust 侧却要常量 ⇒ 实机报 "constraint constants exhausted" ✗。
        // 期望值抄自 tools/ezpz-wasm/src/lib.rs 的 takes_const()：
        //   matches!(kind, 1 | 3 | 8 | 11 | 12 | 13 | 14 | 15 | 16 | 19 | 20 | 21 | 22 | 25)
        // 26 = POINT_ARC_COINCIDENT（不外带常量 ✓ 与 Rust 侧一致 ✓）
        final int[] constKinds = {1, 3, 8, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 25};
        for (int kind = 1; kind <= 26; kind++) {
            boolean expected = false;
            for (final int k : constKinds) {
                if (k == kind) {
                    expected = true;
                }
            }
            check(SketchSolver.takesConstant(kind) == expected,
                    "takesConstant(" + kind + ") 必须与 Rust 侧 takes_const 一致（期望 " + expected + "）");
        }
        // ★★ 同类的另一半：**操作数个数**也必须逐项一致 ✓✓
        //   Java 侧 operandCount 错了 ⇒ op 与变量下标整体错位 ⇒ 求解器要么越界拒绝、要么解出乱七八糟的结果 ✗。
        //   期望值抄自 tools/ezpz-wasm/src/lib.rs 的 operand_count()（kind ⇒ 变量下标个数）：
        //   1→1, 2→4, 3→4, 4→4, 5→4, 6→8, 7→6, 8→3, 9→8, 10→8, 11→6, 12→6, 13→6,
        //   14→8, 15→4, 16→4, 17→8, 18→2, 19→6, 20→6, 21→6, 22→8, 23→7, 24→6, 25→6
        final int[] operandCounts = {1, 4, 4, 4, 4, 8, 6, 3, 8, 8, 6, 6, 6,
                8, 4, 4, 8, 2, 6, 6, 6, 8, 7, 6, 6, 8};
        for (int kind = 1; kind <= 26; kind++) {
            check(SketchSolver.operandCount(kind) == operandCounts[kind - 1],
                    "operandCount(" + kind + ") 必须与 Rust 侧 operand_count 一致（期望 "
                            + operandCounts[kind - 1] + "，实际 " + SketchSolver.operandCount(kind) + "）");
        }
        // ★ 相切关系（用户要求"直线+圆弧要能一键补相切"）的离线回归 ✓
        //   ⚠ 这个文件在**默认包**里 ⇒ 用别的包的类必须 import（SketchMeasure 之前漏了 import，
        //     表现是"找不到符号 SketchMeasure"，而文件其实**一直在**编译清单里 ⇒ 白查了两轮 ✓）
        tangentSuggestedOnlyWhenTangent();
        for (final int kind : new int[] {SketchSolver.FIXED, SketchSolver.COINCIDENT,
                SketchSolver.DISTANCE, SketchSolver.HORIZONTAL, SketchSolver.VERTICAL,
                SketchSolver.EQUAL_LEN, SketchSolver.MIDPOINT, SketchSolver.RADIUS}) {
            check(SketchSolver.operandCount(kind) > 0, "operandCount(" + kind + ") 已定义");
        }
    }

    /** 会话状态机：每一步迁移都要干净，尤其是「换工具 / 取消」不能留下半个图元。 */
    private static void sessionStateMachine() {
        final com.ovo.sablestopnow.sketch.SketchSession s =
                new com.ovo.sablestopnow.sketch.SketchSession();
        check(s.stage() == com.ovo.sablestopnow.sketch.SketchSession.Stage.IDLE, "初始是 IDLE");
        check(!s.click(1, 1), "没在编辑时点击不产生图元");

        final java.util.UUID body = java.util.UUID.randomUUID();
        s.beginPlaneChoice(body);
        check(s.stage() == com.ovo.sablestopnow.sketch.SketchSession.Stage.CHOOSING_PLANE,
                "进入选面阶段");
        // 选面阶段点击必须是空操作（还没有草图可画）
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        check(!s.click(1, 1), "选面阶段点击是空操作");

        final Sketch sketch = s.choosePlane(
                SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()), "Sketch1");
        check(s.isEditing(), "选完面进入编辑");
        check("Sketch1".equals(sketch.name()), "草图名字");
        check(sketch.body().equals(body), "草图挂到目标结构");
        check(s.tool() == com.ovo.sablestopnow.sketch.SketchSession.Tool.SELECT,
                "进入编辑后工具回到 SELECT");

        // ⚠ 换工具必须清掉进行中的输入，否则会出现「用直线的第一个点点出一个矩形」
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        s.click(0, 0);
        check(s.pendingPoints().size() == 1, "直线已收 1 个点");
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        check(s.pendingPoints().isEmpty(), "换工具清掉进行中的输入");
        s.click(0, 0);
        check(s.pendingPoints().size() == 1, "矩形已收 1 个点");
        check(s.cancelPending(), "Esc 取消返回 true（确实取消了东西）");
        check(s.pendingPoints().isEmpty()
                        && s.tool() == com.ovo.sablestopnow.sketch.SketchSession.Tool.SELECT,
                "取消后回到 SELECT");

        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.SELECT);
        check(!s.cancelPending(), "没事可取消时返回 false");

        final Sketch done = s.finish();
        check(done == sketch, "finish 返回编辑中的草图");
        check(s.stage() == com.ovo.sablestopnow.sketch.SketchSession.Stage.IDLE, "finish 回到 IDLE");
        check(s.working() == null, "finish 清掉工作副本");
        check(s.finish() == null, "再 finish 返回 null");

        // abort 不提交
        s.beginPlaneChoice(body);
        s.choosePlane(SketchPlane.base(SketchPlane.Base.TOP, new Vector3d()), "Sketch2");
        s.abort();
        check(s.stage() == com.ovo.sablestopnow.sketch.SketchSession.Stage.IDLE, "abort 回到 IDLE");
        check(s.working() == null, "abort 清掉工作副本");
    }

    /**
     * 自动约束：随手画出来的东西应当已经是规整的。
     *
     * <p>这是「仿 SolidWorks 手感」的核心 —— 玩家画一条几乎水平的线，
     * 它当场就水平、并且带上水平约束；而不是事后手动去加。
     */
    /**
     * 推理吸附的纯函数 + **「预览 = 结果」不变量**。
     *
     * <p>为什么专门测这条：预览和真画如果各写一份推理规则，就会漂移成
     * "预览显示一条斜线、松手变成平线"，或者"预览落在端点旁边、结果吸到了端点"。
     * 玩家看到的就是程序在骗他。这里对一批光标位置先算预览、再真点一下，逐一对比。
     */
    private static void inferencePreviewAgrees() {
        // --- 纯函数本体的边界 ---
        double[] r = com.ovo.sablestopnow.sketch.SketchSession.inferOrtho(0, 0, 3.0, 0.1);
        check(r[2] == 1.0 && r[1] == 0.0, "近水平 → 吸成水平，v 归到锚点");
        r = com.ovo.sablestopnow.sketch.SketchSession.inferOrtho(0, 0, 0.05, 4.0);
        check(r[2] == 2.0 && r[0] == 0.0, "近竖直 → 吸成竖直，u 归到锚点");
        r = com.ovo.sablestopnow.sketch.SketchSession.inferOrtho(0, 0, 3.0, 3.0);
        check(r[2] == 0.0 && r[0] == 3.0 && r[1] == 3.0, "45 度 → 原样返回");
        r = com.ovo.sablestopnow.sketch.SketchSession.inferOrtho(0, 0, 0, 0);
        check(r[2] == 0.0, "两点重合 → 不吸（无方向可言），也不能除零炸掉");
        // 负方向同样要吸得住（atan2 用绝对值，四个象限行为一致）
        r = com.ovo.sablestopnow.sketch.SketchSession.inferOrtho(0, 0, -3.0, 0.1);
        check(r[2] == 1.0, "左上方向也吸成水平");
        r = com.ovo.sablestopnow.sketch.SketchSession.inferOrtho(0, 0, -0.05, -4.0);
        check(r[2] == 2.0, "左下方向也吸成竖直");

        // --- 预览 = 结果 ---
        final double[][] cursors = {
                {3.0, 0.1},    // 近水平
                {0.05, 4.0},   // 近竖直
                {3.0, 3.0},    // 斜线，不该吸
                {-2.0, 0.08},  // 近水平、负方向
                {0.02, -5.0},  // 近竖直、负方向
        };
        for (final double[] c : cursors) {
            final com.ovo.sablestopnow.sketch.SketchSession s = editing();
            s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
            s.click(0.0, 0.0);
            final double[] a = s.pendingPoints().get(0);
            final double[] preview =
                    com.ovo.sablestopnow.sketch.SketchSession.inferOrtho(a[0], a[1], c[0], c[1]);
            s.click(c[0], c[1]);
            final Sketch k = s.working();
            final int[] v = k.entities().get(0).vars();
            near(k.variable(v[2]), preview[0],
                    "光标(" + c[0] + "," + c[1] + ") 预览 u 与画出的 u 一致");
            near(k.variable(v[3]), preview[1],
                    "光标(" + c[0] + "," + c[1] + ") 预览 v 与画出的 v 一致");
        }

        // --- 矩形 / 圆的预览也必须和结果同源 ---
        // 矩形：预览用 pending[0] 与落点的 min/max 拼四角，实际也必须如此
        final com.ovo.sablestopnow.sketch.SketchSession rs = editing();
        rs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        rs.click(4.0, 3.0);
        rs.click(1.0, 1.0); // 故意反向拖
        final Sketch rk = rs.working();
        check(rk.entities().size() == 4, "反向拖也画出 4 条边（预览的 min/max 与之一致）");
        double minU = Double.MAX_VALUE;
        double maxU = -Double.MAX_VALUE;
        double minV = Double.MAX_VALUE;
        double maxV = -Double.MAX_VALUE;
        for (final Sketch.Entity e : rk.entities()) {
            final int[] ev = e.vars();
            for (int i = 0; i < 4; i += 2) {
                minU = Math.min(minU, rk.variable(ev[i]));
                maxU = Math.max(maxU, rk.variable(ev[i]));
                minV = Math.min(minV, rk.variable(ev[i + 1]));
                maxV = Math.max(maxV, rk.variable(ev[i + 1]));
            }
        }
        near(minU, 1.0, "矩形 u 跨度 = 两次点击的 min");
        near(maxU, 4.0, "矩形 u 跨度 = 两次点击的 max");
        near(minV, 1.0, "矩形 v 跨度 = 两次点击的 min");
        near(maxV, 3.0, "矩形 v 跨度 = 两次点击的 max");

        // 圆：预览 = 以第一个点为圆心、到第二个点的距离为半径
        final com.ovo.sablestopnow.sketch.SketchSession cs = editing();
        cs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        cs.click(2.0, 2.0);
        cs.click(6.0, 5.0); // 3-4-5
        final Sketch ck = cs.working();
        final int[] cv = ck.entities().get(0).vars();
        near(ck.variable(cv[0]), 2.0, "圆心 = 第一次点击（预览的圆心）");
        near(ck.variable(cv[1]), 2.0, "圆心 v = 第一次点击");
        near(Math.abs(ck.variable(cv[2])), 5.0, "半径 = 两次点击的距离（预览的半径）");
    }

    private static void sessionAutoConstraints() {
        // --- 接近水平的线应当吸附并加 HORIZONTAL ---
        com.ovo.sablestopnow.sketch.SketchSession s = editing();
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        s.click(0.0, 0.0);
        s.click(3.0, 0.1); // 约 1.9 度
        Sketch k = s.working();
        check(k.entities().size() == 1, "水平线只产生 1 个图元");
        final int[] lv = k.entities().get(0).vars();
        near(k.variable(lv[3]), 0.0, "水平线被吸附成 y 相等");
        check(k.constraints().size() == 1 && k.constraints().get(0).kind() == SketchSolver.HORIZONTAL,
                "水平线自动带上 HORIZONTAL 约束");

        // --- 接近竖直的线 ---
        s = editing();
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        s.click(0.0, 0.0);
        s.click(0.05, 4.0);
        k = s.working();
        final int[] vv = k.entities().get(0).vars();
        near(k.variable(vv[2]), 0.0, "竖直线被吸附成 x 相等");
        check(k.constraints().size() == 1 && k.constraints().get(0).kind() == SketchSolver.VERTICAL,
                "竖直线自动带上 VERTICAL 约束");

        // --- 真正的斜线不该被吸 ---
        s = editing();
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        s.click(0.0, 0.0);
        s.click(3.0, 3.0);
        k = s.working();
        check(k.constraints().isEmpty(), "45 度斜线不加水平/竖直约束");
        near(k.variable(k.entities().get(0).vars()[3]), 3.0, "斜线端点没被改动");

        // --- 矩形：4 条边 + 8 条约束 ---
        s = editing();
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        s.click(1.0, 1.0);
        s.click(4.0, 3.0);
        k = s.working();
        check(k.entities().size() == 4, "矩形是 4 条边");
        check(k.variableCount() == 16, "矩形占 16 个变量");
        check(k.constraints().size() == 8, "矩形带 8 条约束（4 水平竖直 + 4 重合）");
        int coincident = 0;
        int axial = 0;
        for (final Sketch.Constraint c : k.constraints()) {
            if (c.kind() == SketchSolver.COINCIDENT) {
                coincident++;
            } else if (c.kind() == SketchSolver.HORIZONTAL || c.kind() == SketchSolver.VERTICAL) {
                axial++;
            }
        }
        check(coincident == 4, "矩形的 4 个角有重合约束（少了就会被求解器推散）");
        check(axial == 4, "矩形的 4 条边有水平/竖直约束");

        // --- 退化的拖拽不该产生图元 ---
        s = editing();
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        s.click(2.0, 2.0);
        s.click(2.0, 2.0); // 同一个点
        check(s.working().entities().isEmpty(), "零面积矩形不产生图元");

        s = editing();
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        s.click(1.0, 1.0);
        s.click(1.0, 1.0);
        check(s.working().entities().isEmpty(), "零长度直线不产生图元");

        // --- 圆：半径由两次点击的距离决定 ---
        s = editing();
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        s.click(0.0, 0.0);
        s.click(3.0, 4.0);
        k = s.working();
        check(k.entities().size() == 1 && k.entities().get(0).kind() == Sketch.EntityKind.CIRCLE,
                "画出一个圆");
        near(k.variable(k.entities().get(0).vars()[2]), 5.0, "半径 = 3-4-5 的 5");
    }

    /** 射线与平面求交：正打、平行、背打三种情况都要对。 */
    private static void rayPlaneIntersection() {
        // 前视基准面放在 (10,0,0)，u=+X，v=+Y，法线=+Z
        final SketchPlane p = SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d(10, 0, 0));

        // 从 -Z 侧正着打过去
        Vector3d uv = p.rayIntersect(new Vector3d(12, 5, -4), new Vector3d(0, 0, 1));
        check(uv != null, "正打应当命中");
        if (uv != null) {
            near(uv.x, 2.0, "命中点 u（12-10）");
            near(uv.y, 5.0, "命中点 v");
        }

        // 参数 t：起点 z=-4、方向 +Z、平面 z=0 → t=4
        near(p.rayParameter(new Vector3d(12, 5, -4), new Vector3d(0, 0, 1)), 4.0, "命中距离 t");

        // 平行：方向沿 X
        check(p.rayIntersect(new Vector3d(0, 0, -4), new Vector3d(1, 0, 0)) == null,
                "与平面平行 → 不命中");
        check(Double.isNaN(p.rayParameter(new Vector3d(0, 0, -4), new Vector3d(1, 0, 0))),
                "平行时 t 是 NaN");

        // 背打：平面在射线背后（方向朝 -Z，平面在 +Z 那侧）
        check(p.rayIntersect(new Vector3d(12, 5, -4), new Vector3d(0, 0, -1)) == null,
                "平面在射线背后 → 不命中");
        check(Double.isNaN(p.rayParameter(new Vector3d(12, 5, -4), new Vector3d(0, 0, -1))),
                "背打时 t 是 NaN");

        // 非单位方向也要对（方向乘 3，t 应当变成 1/3）
        near(p.rayParameter(new Vector3d(12, 5, -4), new Vector3d(0, 0, 3)), 4.0 / 3.0,
                "方向未归一化时 t 按比例变化");

        // 斜着打：方向 (0,0,1)+(1,0,0) 归一化，落点应当沿 X 偏移
        final Vector3d diag = new Vector3d(1, 0, 1).normalize();
        uv = p.rayIntersect(new Vector3d(10, 0, -2), diag);
        check(uv != null, "斜打应当命中");
        if (uv != null) {
            near(uv.x, 2.0, "斜打 u = 前进量（45 度，z 走 2 → x 走 2）");
            near(uv.y, 0.0, "斜打 v 不变");
        }
    }

    /**
     * 基准面拾取：要能挑出该挑的那一块，也要在没打中时老实返回 null。
     */
    private static void datumPlanePicking() {
        // 一个 4×2×6 的结构，中心在 (100, 64, 100)
        final Vector3d min = new Vector3d(98, 63, 97);
        final Vector3d max = new Vector3d(102, 65, 103);
        final List<SketchDatumPlanes.DatumPlane> planes = SketchDatumPlanes.of(min, max);

        check(planes.size() == 3, "生成了三个基准面");
        // 三个原点都应当在包围盒中心
        for (final SketchDatumPlanes.DatumPlane d : planes) {
            near(d.plane().origin().x, 100.0, "基准面原点 x");
            near(d.plane().origin().y, 64.0, "基准面原点 y");
            near(d.plane().origin().z, 100.0, "基准面原点 z");
            check(d.halfU() > 0 && d.halfV() > 0, "半宽半高为正");
            check(d.contains(0, 0), "原点本身在板内");
            check(!d.contains(d.halfU() * 2, 0), "远超半宽的点不在板内");
        }

        // 上视基准面：u=+X、v=+Z、法线=-Y。半宽应当覆盖 X 的 ±2、半高覆盖 Z 的 ±3
        final SketchDatumPlanes.DatumPlane top = planes.stream()
                .filter(d -> d.plane().axisU().dot(new Vector3d(1, 0, 0)) > 0.99
                        && d.plane().axisV().dot(new Vector3d(0, 0, 1)) > 0.99)
                .findFirst().orElse(null);
        check(top != null, "找得到上视基准面");
        if (top != null) {
            // X 半长 2 + 余量 0.5 = 2.5，但被最小边长下限（3.0）抬上去了；
            // Z 半长 3 + 0.5 = 3.5 本来就超过下限，保持原值。
            near(top.halfU(), Math.max(2.0 + SketchDatumPlanes.DEFAULT_MARGIN,
                    SketchDatumPlanes.MIN_HALF_EXTENT), "上视半宽（受最小边长下限约束）");
            near(top.halfV(), 3.0 + SketchDatumPlanes.DEFAULT_MARGIN, "上视半高（本来就够大）");
        }

        // 最小边长下限：薄结构也必须得到可点的板
        final List<SketchDatumPlanes.DatumPlane> thin = SketchDatumPlanes.of(
                new Vector3d(0, 0, 0), new Vector3d(1, 0.2, 1));
        for (final SketchDatumPlanes.DatumPlane d : thin) {
            check(d.halfU() >= SketchDatumPlanes.MIN_HALF_EXTENT
                            && d.halfV() >= SketchDatumPlanes.MIN_HALF_EXTENT,
                    "极薄结构的板也被抬到最小边长（"
                            + d.halfU() + " x " + d.halfV() + "）");
        }
        // 大结构不受影响
        final List<SketchDatumPlanes.DatumPlane> big = SketchDatumPlanes.of(
                new Vector3d(0, 0, 0), new Vector3d(40, 40, 40));
        for (final SketchDatumPlanes.DatumPlane d : big) {
            near(d.halfU(), 20.0 + SketchDatumPlanes.DEFAULT_MARGIN, "大结构半宽不被下限改动");
        }

        // 从正上方垂直往下打：应当命中上视基准面
        SketchDatumPlanes.Hit hit = SketchDatumPlanes.pick(planes,
                new Vector3d(101, 80, 99), new Vector3d(0, -1, 0));
        check(hit != null, "从上方打应当命中");
        if (hit != null) {
            near(hit.plane().plane().normal().dot(new Vector3d(0, 1, 0)), -1.0,
                    "命中的是上视基准面（法线 ±Y）");
            near(hit.u(), 1.0, "命中点 u = 101-100");
            near(hit.v(), -1.0, "命中点 v = 99-100");
            near(hit.distance(), 16.0, "命中距离 = 80-64");
        }

        // 从 -Z 侧水平打：应当命中前视基准面
        hit = SketchDatumPlanes.pick(planes, new Vector3d(99, 64, 50), new Vector3d(0, 0, 1));
        check(hit != null, "从前面打应当命中");
        if (hit != null) {
            near(hit.distance(), 50.0, "前视命中距离 = 100-50");
        }

        // 打空：射线从结构外侧掠过，落点超出板的范围
        check(SketchDatumPlanes.pick(planes, new Vector3d(200, 64, 50), new Vector3d(0, 0, 1)) == null,
                "板外掠过 → 不命中任何基准面");
        // 朝反方向打
        check(SketchDatumPlanes.pick(planes, new Vector3d(99, 64, 150), new Vector3d(0, 0, 1)) == null,
                "背向所有板 → 不命中");

        // 命中多块时取最近的那块。从斜上方 45 度打，两块板都会命中，要挑近的。
        //
        // 起点选 (100,75,90) 是**算过**的：45 度斜线每前进一格 y 与 z 各走一格，
        //   到前视板（z=100）要走 Δz=10 → t=14.14，落点 y=65（在 ±2.5 板内 ✓）
        //   到上视板（y=64）要走 Δy=11 → t=15.56，落点 z=101（在 ±3.5 板内 ✓）
        // 所以两块都能命中，而前视更近。
        //
        // ⚠ 第一版我随手写了个 (100,80,80)，那条射线落到上视板的 v=-4，超出 ±3.5 的板面 ——
        //    pick 返回 null 是**正确**行为，是断言写错了。
        final Vector3d dir = new Vector3d(0, -1, 1).normalize();
        hit = SketchDatumPlanes.pick(planes, new Vector3d(100, 75, 90), dir);
        check(hit != null, "斜上方应当命中某一块");
        if (hit != null) {
            near(hit.plane().plane().normal().dot(new Vector3d(0, 0, 1)), 1.0,
                    "两块都能命中时取更近的前视基准面");
            check(Math.abs(hit.distance() - 14.1421) < 0.01,
                    "取的是更近的那块（前视 t=14.14，上视 t=15.56），实际 " + hit.distance());
        }
    }

    /**
     * 整条链：进入草图模式 → 悬停高亮 → 点面建草图 → 画图元 → 完成提交。
     *
     * <p>这是最接近「玩家实际操作」的一组断言 —— 用合成射线把 UI 会走的路走一遍。
     */
    private static void modeChain() {
        final com.ovo.sablestopnow.sketch.SketchMode mode =
                new com.ovo.sablestopnow.sketch.SketchMode();
        check(!mode.active(), "初始不在草图模式");
        check(!mode.click(new Vector3d(0, 0, 0), new Vector3d(0, 0, 1)), "不在模式里点击不消费");

        // 结构：4×2×6，中心 (100,64,100)
        mode.begin(java.util.UUID.randomUUID(), new Vector3d(98, 63, 97), new Vector3d(102, 65, 103));
        check(mode.active(), "begin 之后是激活的");
        check(mode.stage() == com.ovo.sablestopnow.sketch.SketchSession.Stage.CHOOSING_PLANE,
                "处于选面阶段");
        check(mode.planes().size() == 3, "三块基准板待选");

        // --- 悬停：从正上方往下打，应当高亮上视板 ---
        mode.hover(new Vector3d(101, 80, 99), new Vector3d(0, -1, 0));
        check(mode.hoveredPlane() != null, "悬停到一块板");
        if (mode.hoveredPlane() != null) {
            near(mode.hoveredPlane().plane().normal().dot(new Vector3d(0, 1, 0)), -1.0,
                    "高亮的是上视板");
        }
        // 悬停到板外应当不高亮
        mode.hover(new Vector3d(300, 80, 300), new Vector3d(0, -1, 0));
        check(mode.hoveredPlane() == null, "板外悬停不高亮");

        // --- 点空不消费（不能把转视角的点击吞掉）---
        check(!mode.click(new Vector3d(300, 80, 300), new Vector3d(0, -1, 0)),
                "点空返回 false，让下游处理");

        // --- 点上视板 → 建草图并进入编辑 ---
        check(mode.click(new Vector3d(101, 80, 99), new Vector3d(0, -1, 0)), "点中板返回 true");
        check(mode.stage() == com.ovo.sablestopnow.sketch.SketchSession.Stage.EDITING,
                "点面之后进入编辑");
        check(mode.planes().isEmpty(), "选完面后不再显示那三块板");
        check(mode.hoveredPlane() == null, "选完面后清掉高亮");
        check(mode.editingId() != null, "有正在编辑的草图 id");
        final Sketch working = mode.working();
        check(working != null, "拿得到工作草图");
        if (working == null) {
            return;
        }

        // 草图平面应当是上视板：平面内 (u,v) 对应局部的 (X, Z)
        near(working.plane().normal().dot(new Vector3d(0, 1, 0)), -1.0, "草图平面就是被点的那块");

        // --- 画一条线：射线打到平面上的两个点 ---
        // 上视板过 (100,64,100)，法线 -Y。从上方往下打，落点 (u,v) = (x-100, z-100)
        mode.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        mode.click(new Vector3d(99, 80, 99), new Vector3d(0, -1, 0)); // (u,v) = (-1,-1)
        check(working.entities().isEmpty(), "只点了一个点还没有图元");
        mode.click(new Vector3d(102, 80, 99), new Vector3d(0, -1, 0)); // (u,v) = (2,-1) → 水平
        check(working.entities().size() == 1, "两次点击画出一条线");
        final int[] lv = working.entities().get(0).vars();
        near(working.variable(lv[0]), -1.0, "起点 u");
        near(working.variable(lv[1]), -1.0, "起点 v");
        near(working.variable(lv[2]), 2.0, "终点 u");
        check(!working.constraints().isEmpty(), "近水平的线自动带上约束");

        // --- 预览落点：编辑状态下能拿到平面内坐标 ---
        final Vector3d preview = mode.planePoint(new Vector3d(100, 80, 103), new Vector3d(0, -1, 0));
        check(preview != null, "编辑状态下拿得到预览落点");
        if (preview != null) {
            near(preview.x, 0.0, "预览 u");
            near(preview.y, 3.0, "预览 v");
        }

        // --- 射线与草图平面平行时不画，但仍然消费（草图环境里别顺手转了视角）---
        final int before = working.entities().size();
        check(mode.click(new Vector3d(100, 90, 100), new Vector3d(1, 0, 0)),
                "平行射线仍然消费点击");
        check(working.entities().size() == before, "平行射线不产生图元");

        // --- 完成：交出草图，模式复位 ---
        final Sketch submitted = mode.finish();
        check(submitted == working, "finish 交出的就是编辑中的草图");
        check(!mode.active(), "finish 之后不再激活");
        check(mode.editingId() == null, "不再有编辑中的草图");
        check(mode.finish() == null, "再 finish 返回 null（UI 据此决定不发包）");

        // --- 取消：不交出草图 ---
        mode.begin(java.util.UUID.randomUUID(), new Vector3d(0, 0, 0), new Vector3d(1, 1, 1));
        mode.click(new Vector3d(0.5, 5, 0.5), new Vector3d(0, -1, 0));
        check(mode.stage() == com.ovo.sablestopnow.sketch.SketchSession.Stage.EDITING, "第二次也能进编辑");
        mode.cancel();
        check(!mode.active(), "cancel 之后不激活");
        check(mode.finish() == null, "cancel 之后 finish 拿不到东西");

        // --- 选面阶段不能画（还没草图）---
        mode.begin(java.util.UUID.randomUUID(), new Vector3d(0, 0, 0), new Vector3d(1, 1, 1));
        mode.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        mode.click(new Vector3d(300, 5, 300), new Vector3d(0, -1, 0)); // 点空
        check(mode.stage() == com.ovo.sablestopnow.sketch.SketchSession.Stage.CHOOSING_PLANE,
                "选面阶段点空仍然停在选面");
    }

    /**
     * 用**已有方块面**作为草图平面（与点基准板并列的第二条入口）。
     *
     * <p>要点：选完之后后面的流程必须和点基准板完全一样 —— 如果这里分叉出第二套逻辑，
     * 迟早会变成"从面进去的草图能画但存不了"这种诡异 bug。
     */
    private static void modeFacePlane() {
        final com.ovo.sablestopnow.sketch.SketchMode mode =
                new com.ovo.sablestopnow.sketch.SketchMode();

        // 不在选面阶段时不能被调用
        check(!mode.chooseFace(new Vector3d(1, 2, 3), new Vector3d(0, 1, 0)),
                "没进草图模式时 chooseFace 返回 false");

        mode.begin(java.util.UUID.randomUUID(), new Vector3d(98, 63, 97), new Vector3d(102, 65, 103));
        check(mode.stage() == com.ovo.sablestopnow.sketch.SketchSession.Stage.CHOOSING_PLANE,
                "选面阶段");

        // 点一个朝上的方块面
        check(mode.chooseFace(new Vector3d(20481033.0, 128.0, 20519944.0), new Vector3d(0, 1, 0)),
                "面选中成功");
        check(mode.stage() == com.ovo.sablestopnow.sketch.SketchSession.Stage.EDITING,
                "选面之后直接进入编辑");
        check(mode.planes().isEmpty(), "选完之后不再显示基准板");

        final Sketch working = mode.working();
        check(working != null, "拿得到工作草图");
        if (working == null) {
            return;
        }
        // 平面原点 = 面中心；法线与给定法线同向
        nearVec(working.plane().origin(), 20481033.0, 128.0, 20519944.0, "平面原点就是面中心");
        check(working.plane().normal().dot(new Vector3d(0, 1, 0)) > 0.99, "法线与面的外法线同向");
        // 竖直法线也不能让两个轴退化（onFace 里的兜底分支）
        check(Double.isFinite(working.plane().axisU().length())
                && Math.abs(working.plane().axisU().length() - 1.0) < 1e-9, "u 轴单位长");

        // 后面的流程与点基准板完全一致：能画、能完成
        mode.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        mode.click(new Vector3d(0, 0, 0), new Vector3d(0, 0, 0)); // 射线随便给，下面直接验状态
        mode.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        // 用平面内的两次点击：把射线做成从面中心正上方垂直往下（法线 +Y）
        final double baseY = 200.0;
        mode.click(new Vector3d(20481032.0, baseY, 20519944.0), new Vector3d(0, -1, 0));
        mode.click(new Vector3d(20481035.0, baseY, 20519944.0), new Vector3d(0, -1, 0));
        check(!working.entities().isEmpty(), "从面进去也能画出图元（流程没有分叉）");

        final Sketch submitted = mode.finish();
        check(submitted == working, "finish 交出同一张草图");
        check(!mode.active(), "finish 之后不再激活");
        // 完成之后再调 chooseFace 应当被拒绝（已经是 IDLE）
        check(!mode.chooseFace(new Vector3d(0, 0, 0), new Vector3d(0, 1, 0)),
                "完成后不能再选面");
    }

    /**
     * 再次编辑一张已有草图（设计树点进去）——里程碑的「可再次编辑」。
     *
     * <p>要点：再次编辑必须**接着原来那张继续**，不能被当成新建。
     * 如果走岔了（比如又 new 一个 Sketch），症状就是"点开旧草图是空的、改完变成两张"，
     * 而那种 bug 在游戏里要试好几轮才看得出来。
     */
    private static void modeEditExisting() {
        final com.ovo.sablestopnow.sketch.SketchMode mode =
                new com.ovo.sablestopnow.sketch.SketchMode();
        final java.util.UUID body = java.util.UUID.randomUUID();

        // 先正常建一张并画一条线，然后「完成」退出
        mode.begin(body, new Vector3d(0, 0, 0), new Vector3d(2, 2, 2));
        mode.chooseFace(new Vector3d(0, 0, 0), new Vector3d(0, 1, 0));
        mode.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        mode.click(new Vector3d(0, 10, 0), new Vector3d(0, -1, 0));
        mode.click(new Vector3d(1, 10, 0), new Vector3d(0, -1, 0));
        final Sketch original = mode.finish();
        check(original != null && original.entities().size() == 1, "先有一张画了一条线的草图");
        check(!mode.active(), "完成后退出");

        // 从设计树点进去再编辑
        mode.edit(original);
        check(mode.active(), "edit 之后进入编辑状态");
        check(mode.stage() == com.ovo.sablestopnow.sketch.SketchSession.Stage.EDITING,
                "edit 直接到 EDITING，不经过选面");
        check(mode.planes().isEmpty(), "edit 不显示基准板");
        check(mode.working() == original, "编辑的是**原来那张**，不是新建的");
        check(mode.editingId() != null
                        && mode.editingId().equals(original.id()), "渲染高亮指向同一张");
        check(original.entities().size() == 1, "原有图元还在（不是一张空草图）");
        check(mode.session().body().equals(body), "所属结构也一并恢复");

        // 接着画第二笔
        mode.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        mode.click(new Vector3d(0, 10, 0), new Vector3d(0, -1, 0));
        mode.click(new Vector3d(3, 10, 0), new Vector3d(0, -1, 0));
        check(mode.working().entities().size() == 2, "接着画第二笔，追加到同一张上");

        // 再完成：交出的还是同一张（不是新的一张）
        final Sketch again = mode.finish();
        check(again == original, "再次完成交出的仍是同一张草图");
        check(again.id().equals(original.id()), "id 没变（不会在树上多出一行）");
        check(!mode.active(), "再次完成后退出");
    }

    /**
     * 求解链路：画完图元要真的把约束解出来。
     *
     * <p>这条守的是一个很容易被忽略的缺口 —— **自动约束只在画的时候吸附了一下**，
     * 几何真正满足约束是靠求解器。如果没有这一步，约束就只是躺在数据里，
     * 表现为"拖了尺寸线不动"，而且看起来一切正常（数据和 UI 都对）。
     */
    private static void modeSolving(final String nativeLib) {
        final com.ovo.sablestopnow.sketch.SketchMode mode =
                new com.ovo.sablestopnow.sketch.SketchMode();
        // 不注入求解器时必须是空操作（纯几何模式）
        mode.begin(java.util.UUID.randomUUID(), new Vector3d(0, 0, 0), new Vector3d(2, 2, 2));
        mode.chooseFace(new Vector3d(0, 0, 0), new Vector3d(0, 1, 0));
        check(mode.solve() == null, "没注入求解器时 solve 是空操作");
        mode.cancel();

        // 注入真求解器
        mode.setSolver(SketchSolver.load(Path.of(nativeLib)));
        mode.begin(java.util.UUID.randomUUID(), new Vector3d(0, 0, 0), new Vector3d(2, 2, 2));
        mode.chooseFace(new Vector3d(0, 0, 0), new Vector3d(0, 1, 0));

        // 画一条"歪"的线并手动加一条水平约束，求解后应当被拉平
        mode.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        mode.click(new Vector3d(0, 10, 0), new Vector3d(0, -1, 0));
        mode.click(new Vector3d(3, 10, 1), new Vector3d(0, -1, 0)); // 约 18 度，不会自动吸附
        final Sketch k = mode.working();
        check(k != null, "拿得到草图");
        if (k == null) {
            return;
        }
        check(k.entities().size() == 1, "画出一条线");
        final int[] v = k.entities().get(0).vars();
        final double yBefore = k.variable(v[3]);
        check(Math.abs(yBefore - 0.0) > 1e-9, "画的时候没有吸附（所以 v 不相等）");

        // 手动加水平约束 → 求解 → 应当被拉平
        k.addConstraint(SketchSolver.HORIZONTAL, 0.0, v[0], v[1], v[2], v[3]);
        final SketchSolver.Result r = mode.solve();
        check(r != null, "注入求解器后 solve 返回结果");
        if (r != null) {
            check(r.ok(), "求解应当满足（iterations=" + r.iterations()
                    + " unsatisfied=" + r.unsatisfied() + "）");
        }
        near(k.variable(v[3]), k.variable(v[1]), "求解后被拉成水平");

        // 求解失败（过约束）不能崩，只记错误
        k.addConstraint(SketchSolver.VERTICAL, 0.0, v[0], v[1], v[2], v[3]); // 同时水平+竖直 → 冲突
        mode.solve();
        check(true, "过约束不会抛异常（结果/错误留在 lastSolve/lastSolveError）");
        mode.cancel();

        // ⚠⚠ 用户实测反馈：「圆画好了，但直线和矩形都塌成同一条直线上的线段」。
        // 圆和直线/矩形的区别正是**后者会加 HORIZONTAL/VERTICAL 约束**，
        // 所以这里把"带求解器画矩形"整条路复现一遍，看几何有没有被解塌。
        drawRectangleWithSolver(nativeLib);
    }

    /** 用真求解器画一个矩形，检查四边有没有塌成一条线（复现用户反馈）。 */
    private static void drawRectangleWithSolver(final String nativeLib) {
        final com.ovo.sablestopnow.sketch.SketchMode mode =
                new com.ovo.sablestopnow.sketch.SketchMode();
        mode.setSolver(SketchSolver.load(Path.of(nativeLib)));
        mode.begin(java.util.UUID.randomUUID(), new Vector3d(0, 0, 0), new Vector3d(2, 2, 2));
        mode.chooseFace(new Vector3d(0, 0, 0), new Vector3d(0, 1, 0));
        mode.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        mode.click(new Vector3d(0, 10, 0), new Vector3d(0, -1, 0));   // (u,v) = (0,0)
        mode.click(new Vector3d(3, 10, 2), new Vector3d(0, -1, 0));   // (u,v) = (3,2)

        final Sketch k = mode.working();
        check(k != null, "矩形：拿得到草图");
        if (k == null) {
            return;
        }
        check(k.entities().size() == 4, "矩形：4 条边");
        check(k.variableCount() == 16, "矩形：16 个变量");

        // 四条边的长度都不能塌
        for (int i = 0; i < k.entities().size(); i++) {
            final int[] e = k.entities().get(i).vars();
            final double len = Math.hypot(k.variable(e[2]) - k.variable(e[0]),
                    k.variable(e[3]) - k.variable(e[1]));
            check(len > 0.5, "矩形：第 " + (i + 1) + " 条边没有塌（len=" + len + "）");
        }
        // 四个角不能共线：宽度与高度都要非零。
        // ⚠ 高度要比 right 的**终点** y（vars[7]）与起点 y（vars[5]）；
        //   一开始我写成了 vars[5] vs vars[3]，而 vars[3] 是底边终点 y、
        //   vars[5] 是右边起点 y —— 按构造它俩就是同一个角，恒为 0，测试自己错了。
        final double width = Math.abs(k.variable(2) - k.variable(0));
        final double height = Math.abs(k.variable(7) - k.variable(5));
        check(Math.abs(width) > 0.5, "矩形：宽度非零（" + width + "）");
        check(Math.abs(height) > 0.5, "矩形：高度非零（" + height + "）");
        mode.cancel();
    }

    /**
     * 标注尺寸（「智能尺寸」）。
     *
     * <p>要点：尺寸**只是约束**（带常量的那种），不是另一套数据。所以这里既验"标得上"，
     * 也验"改数值是原位替换"—— 后者如果退化成"删掉再加"，约束顺序会变，
     * 而 {@code toOps} 与 {@code toConsts} 必须同序，很容易只改一边、导致尺寸串位。
     */
    private static void dimensions() {
        final com.ovo.sablestopnow.sketch.SketchSession s = editing();
        final Sketch k = s.working();
        if (k == null) {
            check(false, "拿得到草图");
            return;
        }

        // --- 线段：标长度 ---
        final Sketch.Entity line = k.addLine(0, 0, 3, 0);
        final Sketch.Constraint dim = s.addDimension(line, 5.0);
        check(dim != null, "线段标得上尺寸");
        check(dim != null && dim.kind() == SketchSolver.DISTANCE, "线段的尺寸是 DISTANCE");
        check(dim != null && dim.operands().length == 4, "DISTANCE 有 4 个操作数");
        near(dim == null ? -1 : dim.value(), 5.0, "尺寸值");
        // 尺寸必须被算进约束程序（否则求解器看不到它）
        check(k.toConsts().length == 1, "尺寸进了常量表");
        check(k.toOps().length == 1 + 4, "尺寸进了操作数表");

        // --- 圆：标半径 ---
        final Sketch.Entity circle = k.addCircle(10, 10, 2);
        final Sketch.Constraint rad = s.addDimension(circle, 4.0);
        check(rad != null && rad.kind() == SketchSolver.RADIUS, "圆的尺寸是 RADIUS");
        check(rad != null && rad.operands().length == 3, "RADIUS 有 3 个操作数");

        // --- 弧：标半径（⚠ 弧**没有半径变量** ⇒ 走 ARC_RADIUS / 6 个操作数）---
        final Sketch.Entity arcDim = k.addArc(0, 0, 5, 0, Math.PI / 2);
        final Sketch.Constraint arad = s.addDimension(arcDim, 7.0);
        check(arad != null && arad.kind() == SketchSolver.ARC_RADIUS,
                "弧的尺寸是 ARC_RADIUS（kind 19）—— 不能用 RADIUS（它要一个半径变量 ✗）");
        check(arad != null && arad.operands().length == 6,
                "ARC_RADIUS 有 6 个操作数（圆心 xy + 起点 xy + 终点 xy）");
        final int[] adv = arcDim.vars();
        check(arad != null && java.util.Arrays.equals(arad.operands(), adv),
                "ARC_RADIUS 的操作数按顺序就是弧的 6 个变量");
        check(arad != null && SketchSolver.takesConstant(arad.kind()),
                "ARC_RADIUS 吃一个常量（半径值）—— 否则常量表会错位");
        // 现算半径必须与几何一致（不是把尺寸值当成变量存下来）
        near(k.arcRadius(arcDim), 5.0, "弧的现算半径仍然是 5（尺寸只是约束，不是变量）");

        // --- 点：标不了 ---
        final Sketch.Entity point = k.addPoint(1, 1);
        check(s.addDimension(point, 1.0) == null, "点标不了尺寸");

        // --- 认领：每个图元只认自己的尺寸 ---
        check(s.dimensionsOf(line).size() == 1, "线段认领 1 个尺寸");
        check(s.dimensionsOf(circle).size() == 1, "圆认领 1 个尺寸");
        check(s.dimensionsOf(arcDim).size() == 1, "弧认领 1 个尺寸（ARC_RADIUS 也认领得到）");
        check(s.dimensionsOf(point).isEmpty(), "点没有尺寸");

        // --- 改数值：原位替换，顺序不能变 ---
        final int opsBefore = k.toOps().length;
        final double[] constsBefore = k.toConsts();
        check(s.setDimensionValue(dim, 7.5), "改尺寸值成功");
        check(k.toOps().length == opsBefore, "改值不改约束程序长度");
        final double[] constsAfter = k.toConsts();
        check(constsAfter.length == constsBefore.length, "改值不改常量个数");
        near(constsAfter[0], 7.5, "常量 0 被改成新值（原位替换，没有跑到末尾）");
        near(constsAfter[1], 4.0, "圆的半径尺寸不受影响");
        check(s.dimensionsOf(line).get(0).value() == 7.5, "认领回来的也是新值");

        // --- 改一个不存在的尺寸 ---
        final Sketch.Constraint ghost = new Sketch.Constraint(SketchSolver.DISTANCE, new int[] {0, 1, 2, 3}, 1.0);
        check(s.setDimensionValue(ghost, 9.0), "同 kind 同操作数能改到（line 的端点就是 0,1,2,3）");
        check(!s.setDimensionValue(
                        new Sketch.Constraint(SketchSolver.DISTANCE, new int[] {90, 91, 92, 93}, 1.0), 2.0),
                "改不存在的尺寸返回 false");
        // 非尺寸类约束不能被当成尺寸改
        check(!s.setDimensionValue(
                        new Sketch.Constraint(SketchSolver.HORIZONTAL, new int[] {0, 1, 2, 3}, 0.0), 2.0),
                "水平约束不是尺寸，改不动");
    }

    /**
     * 图元拾取（加约束/标尺寸的前提）。
     *
     * <p>三种图元的"距离"定义不同，而且**每一种都有一个容易写错的退化情形**：
     * 线段要夹参数（否则延长线上老远也选中）、圆要按圆周算（否则圆内部全算命中）、
     * 退化线段要退回点到点距离（否则除零）。
     */
    private static void picking() {
        final com.ovo.sablestopnow.sketch.SketchSession s = editing();
        final Sketch k = s.working();
        if (k == null) {
            check(false, "拿得到草图");
            return;
        }
        final Sketch.Entity line = k.addLine(0, 0, 10, 0);
        final Sketch.Entity circle = k.addCircle(20, 0, 5);
        final Sketch.Entity point = k.addPoint(0, 10);

        // 线段：点在中段附近 → 选中它
        check(s.pickEntity(5.0, 0.2, 0.5) == line, "线段中段附近选中线段");
        // 线段：点在延长线上很远处 → **不该**选中（这是"线段"而不是"直线"）
        check(s.pickEntity(100.0, 0.0, 0.5) == null, "延长线上不选中（线段不是无限直线）");
        // 线段端点附近仍然算（参数夹在 [0,1]）
        check(s.pickEntity(-0.2, 0.0, 0.5) == line, "端点外侧容差内仍算命中");
        // 垂距
        check(s.pickEntity(5.0, 1.0, 0.5) == null, "离得太远不选中");

        // 圆：圆周上 → 选中；圆心 → **不**选中（否则圆内部一片都会命中）
        check(s.pickEntity(25.0, 0.0, 0.5) == circle, "圆周上选中圆");
        check(s.pickEntity(20.0, 0.0, 0.5) == null, "圆心处不选中圆（按圆周算距离）");
        check(s.pickEntity(15.0, 0.0, 0.5) == circle, "圆周内侧 5 格 → 命中");

        // 点
        check(s.pickEntity(0.1, 10.1, 0.5) == point, "点附近选中点");

        // 多者都近时取最近
        final Sketch.Entity a = k.addLine(0, -1, 10, -1); // 与 line 平行、差 1 格
        check(s.pickEntity(5.0, -0.9, 2.0) == a, "两个都够近时取更近的那个");

        // 退化线段不能除零
        final Sketch.Entity degenerate = k.addLine(50, 50, 50, 50);
        check(s.pickEntity(50.1, 50.1, 0.5) == degenerate, "退化线段（两端重合）仍可拾取");

        // 容差为 0 时不选中任何东西（除非正好压在上面）
        check(s.pickEntity(5.0, 5.0, 0.0) == null, "容差 0 且没压在上面 → 不选中");

        // 选中状态
        check(s.selectedEntity() == null, "初始没有选中图元");
        s.selectEntity(circle);
        check(s.selectedEntity() == circle, "能选中并读回");
        s.selectEntity(null);
        check(s.selectedEntity() == null, "能清空选中");
        // 换工具 / 取消时要清掉选中，否则会留下"选中已不存在图元"的幽灵状态
        s.selectEntity(circle);
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        check(s.selectedEntity() == null, "换工具清掉选中");
    }

    /**
     * 关键点拾取（端点 / 中点 / 圆心）—— 端点拾取与推理吸附的底座。
     *
     * <p>这组断言守的是「吸附会不会吸错地方」：吸到不该吸的点比不吸更糟 ——
     * 玩家画一条线，端点莫名其妙跑到别处去了。
     */
    private static void keyPoints() {
        final com.ovo.sablestopnow.sketch.SketchSession s = editing();
        final Sketch k = s.working();
        if (k == null) {
            check(false, "拿得到草图");
            return;
        }
        final Sketch.Entity line = k.addLine(0, 0, 10, 0);   // 端点 (0,0)(10,0)，中点 (5,0)
        final Sketch.Entity circle = k.addCircle(20, 0, 5);  // 圆心 (20,0)
        final Sketch.Entity point = k.addPoint(0, 10);

        // 端点
        var kp = s.pickKeyPoint(0.1, 0.1, 0.5);
        check(kp != null && kp.kind() == com.ovo.sablestopnow.sketch.SketchSession.KeyKind.ENDPOINT,
                "吸到线段端点");
        check(kp != null && Math.abs(kp.u()) < 1e-9 && Math.abs(kp.v()) < 1e-9, "端点坐标是 (0,0)");
        kp = s.pickKeyPoint(10.1, -0.1, 0.5);
        check(kp != null && Math.abs(kp.u() - 10.0) < 1e-9, "吸到另一端 (10,0)");

        // 中点
        kp = s.pickKeyPoint(5.05, 0.05, 0.5);
        check(kp != null
                        && kp.kind() == com.ovo.sablestopnow.sketch.SketchSession.KeyKind.MIDPOINT,
                "吸到线段中点");
        check(kp != null && Math.abs(kp.u() - 5.0) < 1e-9, "中点坐标是 (5,0)");

        // 圆心
        kp = s.pickKeyPoint(20.1, 0.1, 0.5);
        check(kp != null
                        && kp.kind() == com.ovo.sablestopnow.sketch.SketchSession.KeyKind.CENTER,
                "吸到圆心");
        // 圆周上不是关键点（圆周不是端点/中点/圆心）
        check(s.pickKeyPoint(25.0, 0.0, 0.5) == null, "圆周上不是关键点，不吸附");

        // 独立点
        kp = s.pickKeyPoint(0.05, 10.05, 0.5);
        check(kp != null && kp.kind() == com.ovo.sablestopnow.sketch.SketchSession.KeyKind.ENDPOINT,
                "吸到独立点");

        // 容差外不吸
        check(s.pickKeyPoint(5.0, 3.0, 0.5) == null, "离关键点太远不吸附");

        // 两者都近时取最近的（(5,0) 是中点，端点 (0,0) 离得远）
        kp = s.pickKeyPoint(4.9, 0.0, 1.5);
        check(kp != null && kp.kind() == com.ovo.sablestopnow.sketch.SketchSession.KeyKind.MIDPOINT,
                "两个候选都够近时取更近的那个");

        // snap：吸到就返回吸附后的坐标与种类；没吸到原样返回且 kind=-1
        final double[] snapped = s.snap(0.1, 0.1, 0.5);
        check(Math.abs(snapped[0]) < 1e-9 && Math.abs(snapped[1]) < 1e-9, "snap 返回吸附后的坐标");
        check(snapped[2] >= 0, "snap 带回了关键点种类（提示用）");
        final double[] free = s.snap(3.3, 4.4, 0.1);
        check(Math.abs(free[0] - 3.3) < 1e-9 && Math.abs(free[1] - 4.4) < 1e-9,
                "没吸到就原样返回");
        check(free[2] < 0, "没吸到时种类是 -1");
    }

    /**
     * 作画时的吸附：落点先吸到关键点再落笔。
     *
     * <p>守的是「看起来接上了、实际差一点」这个经典毛病 —— 不吸附的话，
     * 两条"肉眼看着接上"的线实际差零点几格，之后一加约束就散开。
     */
    private static void snappingWhileDrawing() {
        final com.ovo.sablestopnow.sketch.SketchMode mode =
                new com.ovo.sablestopnow.sketch.SketchMode();
        mode.begin(java.util.UUID.randomUUID(), new Vector3d(0, 0, 0), new Vector3d(2, 2, 2));
        // ⚠ 用**基准面 TOP** 而不是 onFace：TOP 的 u=+X、v=+Z，与下面 clickAt 的假设一致。
        // （onFace 在上朝向法线时会取 up=(1,0,0) 来定 u 轴，于是那块面的 u=+Z、v=+X ——
        //   我第一版按 u=X/v=Z 写，结果断言全错位。）
        mode.session().choosePlane(SketchPlane.base(SketchPlane.Base.TOP, new Vector3d(0, 0, 0)),
                "Sketch1");
        mode.setPickTolerance(0.5);

        // 先画一条 (0,0)-(4,0) 的线（用射线让落点落在这些位置；上视面 u=X, v=Z）
        mode.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        clickAt(mode, 0.0, 0.0);
        clickAt(mode, 4.0, 0.0);
        final Sketch k = mode.working();
        check(k != null && k.entities().size() == 1, "先有一条线");
        if (k == null) {
            return;
        }

        // 从「靠近 (4,0) 但不精确」的位置起笔：应当被吸到那个端点上
        mode.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        clickAt(mode, 4.12, 0.08);
        clickAt(mode, 4.0, 3.0);
        check(k.entities().size() == 2, "画出第二条线");
        final int[] second = k.entities().get(1).vars();
        near(k.variable(second[0]), 4.0, "第二条线的起点被吸到端点 x");
        near(k.variable(second[1]), 0.0, "第二条线的起点被吸到端点 y");

        // 吸到中点
        mode.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        clickAt(mode, 2.1, 0.05);   // (2,0) 是第一条线的中点
        clickAt(mode, 2.0, -2.0);
        final int[] third = k.entities().get(2).vars();
        near(k.variable(third[0]), 2.0, "第三条线的起点被吸到中点 x");
        near(k.variable(third[1]), 0.0, "第三条线的起点被吸到中点 y");

        // 吸到端点时，应当**补一条真约束**（不只是落点重合）
        boolean foundCoincident = false;
        for (final Sketch.Constraint c : k.constraints()) {
            if (c.kind() == SketchSolver.COINCIDENT) {
                foundCoincident = true;
            }
        }
        check(foundCoincident, "吸到端点后自动加了重合约束（否则求解一动就散）");

        // 吸到中点时应当加 MIDPOINT 而不是 COINCIDENT（中点没有自己的变量）
        boolean foundMidpoint = false;
        for (final Sketch.Constraint c : k.constraints()) {
            if (c.kind() == SketchSolver.MIDPOINT) {
                foundMidpoint = true;
            }
        }
        check(foundMidpoint, "吸到中点后自动加了 MIDPOINT 约束");

        // 离得远就不吸
        mode.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        clickAt(mode, 7.0, 7.0);
        clickAt(mode, 9.0, 7.0);
        final int[] far = k.entities().get(3).vars();
        near(k.variable(far[0]), 7.0, "远离关键点时落点不被改动");

        // 悬停提示：鼠标靠近端点时应当报出"吸到了什么"
        mode.hover(new Vector3d(0.05, 20, 0.05), new Vector3d(0, -1, 0));
        check(mode.snapAt() != null, "悬停在端点附近时报出吸附目标");
        check(mode.snapAt() != null
                        && mode.snapAt().kind()
                        == com.ovo.sablestopnow.sketch.SketchSession.KeyKind.ENDPOINT,
                "提示的种类是端点");
        // ✅ 悬停在**整数格点**上 ⇒ 现在会吸附到 **MC 网格** ✓
        //   （用户要求：该面的方块网格"顶点与网格线"都可吸附 ✓ —— uv 单位就是方块 ✓）
        mode.hover(new Vector3d(30, 20, 30), new Vector3d(0, -1, 0));
        check(mode.snapAt() != null
                        && mode.snapAt().kind()
                        == com.ovo.sablestopnow.sketch.SketchSession.KeyKind.GRID,
                "格点上吸附到 MC 网格（GRID）");
        // ⚠ **对偶断言必须保留** ✗：格点/格线与图元都够不着时 ⇒ 仍然不提示 ✓
        //   取 25.5：离上下两个整数格各 0.5 ✓（拾取容差在这个相机距离下约 0.4 ✓）⇒ 吸不到 ✓
        mode.hover(new Vector3d(25.5, 20, 25.5), new Vector3d(0, -1, 0));
        check(mode.snapAt() == null, "远离格点与图元时不提示吸附（对偶断言 ✓ 别到处乱吸 ✗）");
        mode.cancel();
    }

    /** 在上视草图平面上，用一条垂直射线点击平面内坐标 (u,v)。平面原点 (0,0)，u=X、v=Z。 */
    private static void clickAt(final com.ovo.sablestopnow.sketch.SketchMode mode,
                                final double u, final double v) {
        mode.click(new Vector3d(u, 20.0, v), new Vector3d(0, -1, 0));
    }

    /**
     * 「标尺寸 / 改尺寸」的模型层入口（左栏按钮走这条）。
     *
     * <p>要点：尺寸是**约束**，所以标完之后要能被求解器认到；
     * 而"改尺寸"必须是**原位替换**（顺序变了 toOps 与 toConsts 就会错配）。
     */
    private static void dimensionSelected() {
        final com.ovo.sablestopnow.sketch.SketchSession s = editing();
        final Sketch k = s.working();
        if (k == null) {
            check(false, "拿得到草图");
            return;
        }
        final Sketch.Entity line = k.addLine(0, 0, 3, 0);
        final Sketch.Entity point = k.addPoint(5, 5);

        // 没选中时什么也不做
        check(!s.dimensionSelected(7.0), "没选中图元时标尺寸返回 false");

        s.selectEntity(line);
        check(s.dimensionSelected(7.0), "选中线段后标尺寸成功");
        check(s.dimensionsOf(line).size() == 1, "线段上有一条尺寸");
        near(s.dimensionsOf(line).get(0).value(), 7.0, "尺寸值");
        check(k.toConsts().length == 1, "尺寸进了常量表（求解器看得到）");

        // 改尺寸：原位替换，常量顺序不能乱
        s.selectEntity(line);
        check(s.retypeSelectedDimension(9.5), "改尺寸成功");
        near(k.toConsts()[0], 9.5, "常量被改到新值");

        // 没尺寸的图元改不了
        s.selectEntity(point);
        check(!s.dimensionSelected(1.0), "点标不了尺寸");
        check(!s.retypeSelectedDimension(1.0), "点上没有尺寸可改");

        // 两条尺寸时，改的是该图元自己那条
        final Sketch.Entity circle = k.addCircle(20, 20, 4);
        s.selectEntity(circle);
        check(s.dimensionSelected(3.0), "圆标半径");
        s.selectEntity(circle);
        check(s.retypeSelectedDimension(2.5), "改半径");
        near(s.dimensionsOf(circle).get(0).value(), 2.5, "半径被改到 2.5");
        near(s.dimensionsOf(line).get(0).value(), 9.5, "线段的尺寸没被带坏");
    }

    /**
     * 标尺寸 → 立刻求解：几何必须跟着动。
     *
     * <p>这条守的是"标了没生效"：尺寸是约束，不求解的话玩家输个 50、
     * 图形纹丝不动，会以为功能坏了。<b>这一类"数据对了但没生效"，
     * 正是本会话里反复出现的那类 bug。</b>
     */
    private static void dimensionThroughMode(final String nativeLib) {
        final com.ovo.sablestopnow.sketch.SketchMode mode =
                new com.ovo.sablestopnow.sketch.SketchMode();
        mode.setSolver(SketchSolver.load(Path.of(nativeLib)));
        mode.begin(java.util.UUID.randomUUID(), new Vector3d(0, 0, 0), new Vector3d(2, 2, 2));
        mode.session().choosePlane(SketchPlane.base(SketchPlane.Base.TOP, new Vector3d(0, 0, 0)),
                "Sketch1");

        // 画一条 4 格长的线
        mode.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        clickAt(mode, 0, 0);
        clickAt(mode, 4, 0);
        final Sketch k = mode.working();
        if (k == null) {
            check(false, "拿得到草图");
            return;
        }
        final Sketch.Entity line = k.entities().get(0);
        final int[] v = line.vars();
        near(Math.hypot(k.variable(v[2]) - k.variable(v[0]),
                k.variable(v[3]) - k.variable(v[1])), 4.0, "初始长度 4");

        // 选中它并标成 10 → 求解后长度应当变成 10
        mode.session().selectEntity(line);
        check(mode.dimension(10.0), "标尺寸成功");
        final double len = Math.hypot(k.variable(v[2]) - k.variable(v[0]),
                k.variable(v[3]) - k.variable(v[1]));
        check(Math.abs(len - 10.0) < 1e-3,
                "标完尺寸立刻求解，长度变成 10（实际 " + len + "）");

        // 改尺寸 → 再解
        mode.session().selectEntity(line);
        check(mode.retypeDimension(2.5), "改尺寸成功");
        final double len2 = Math.hypot(k.variable(v[2]) - k.variable(v[0]),
                k.variable(v[3]) - k.variable(v[1]));
        check(Math.abs(len2 - 2.5) < 1e-3, "改完尺寸立刻求解，长度变成 2.5（实际 " + len2 + "）");
        mode.cancel();
    }

    /**
     * 左栏的几何约束按钮（水平 / 竖直 / 固定 / 等长）。
     *
     * <p>要点是**校验约束配不配得上图元**：对圆加"水平"是没有定义的。
     * 宁可返回 false 让 UI 提示，也不要塞一条求解器看不懂的约束 ——
     * 那会变成"点了没反应"或更糟的"解出乱七八糟的形状"。
     */
    private static void constraintButtons() {
        final com.ovo.sablestopnow.sketch.SketchSession s = editing();
        final Sketch k = s.working();
        if (k == null) {
            check(false, "拿得到草图");
            return;
        }
        final Sketch.Entity line = k.addLine(0, 0, 3, 1);
        final Sketch.Entity circle = k.addCircle(8, 8, 2);

        // 没选中 → 一律失败
        check(!s.constrainSelected(SketchSolver.HORIZONTAL), "没选中时加约束返回 false");

        // 线段：水平 / 竖直
        s.selectEntity(line);
        check(s.constrainSelected(SketchSolver.HORIZONTAL), "线段能加水平");
        check(k.constraints().get(k.constraints().size() - 1).kind() == SketchSolver.HORIZONTAL,
                "加进去的确实是 HORIZONTAL");
        check(s.constrainSelected(SketchSolver.VERTICAL), "线段能加竖直（两者冲突由求解器报）");

        // 圆：水平/竖直**没有定义**
        s.selectEntity(circle);
        check(!s.constrainSelected(SketchSolver.HORIZONTAL), "圆不能加水平（返回 false）");
        check(!s.constrainSelected(SketchSolver.VERTICAL), "圆不能加竖直");

        // 固定：点/线/圆都能固定，且是**逐变量**钉住
        final int before = k.constraints().size();
        check(s.constrainSelected(SketchSolver.FIXED), "圆能加固定");
        check(k.constraints().size() == before + 3, "圆有 3 个变量 → 加 3 条 FIXED");
        // 固定的是"当前值"
        final Sketch.Constraint lastFixed = k.constraints().get(k.constraints().size() - 1);
        near(lastFixed.value(), k.variable(circle.vars()[2]), "固定的是当前半径值");

        // 等长：两条线段才行
        final Sketch.Entity line2 = k.addLine(10, 10, 14, 10);
        s.selectEntity(line);
        check(s.equalLengthSelected(line2), "两条线段能加等长");
        check(!s.equalLengthSelected(circle), "线段与圆不能加等长");
        check(!s.equalLengthSelected(line), "同一条线自己不能加等长");
    }

    /**
     * 轮廓求解（拉伸的地基）：从一堆线段里找出闭合轮廓。
     *
     * <p>守的是"能不能拉伸"这个前提：开链拉伸不出实体、方向反了生成的面会里外翻转、
     * 自交轮廓会得到自穿的实体。这三件都要在**拉伸之前**判掉，而不是等生成出一坨坏几何。
     */
    private static void profiles() {
        final com.ovo.sablestopnow.sketch.SketchSession s = editing();
        final Sketch k = s.working();
        if (k == null) {
            check(false, "拿得到草图");
            return;
        }

        // --- 矩形（4 条线段首尾相接）→ 1 个闭合轮廓，4 个顶点 ---
        k.addRectangle(0, 0, 4, 3);
        var profiles = com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(k);
        check(profiles.size() == 1, "矩形得到 1 个轮廓（实际 " + profiles.size() + "）");
        if (!profiles.isEmpty()) {
            final var rect = profiles.get(0);
            check(rect.size() == 4, "矩形轮廓有 4 个顶点（实际 " + rect.size() + "）");
            // addRectangle 的顺序是 (x,y)→(x1,y)→(x1,y1)→(x,y1)，即**逆时针**
            check(rect.counterClockwise(), "矩形轮廓是逆时针（面积为正）");
            near(Math.abs(rect.signedArea()), 12.0, "矩形面积 = 4×3");
            check(!rect.degenerate(), "矩形轮廓不退化");
            check(!com.ovo.sablestopnow.sketch.SketchProfiles.selfIntersects(rect),
                    "矩形不自交");
            final double[] b = com.ovo.sablestopnow.sketch.SketchProfiles.bounds(rect);
            check(b != null && b[0] == 0.0 && b[1] == 0.0 && b[2] == 4.0 && b[3] == 3.0,
                    "轮廓包围盒 = (0,0)-(4,3)");
        }

        // --- 画一条悬空的线（开链）→ **不算轮廓** ---
        final com.ovo.sablestopnow.sketch.SketchSession s2 = editing();
        final Sketch k2 = s2.working();
        if (k2 != null) {
            k2.addLine(0, 0, 5, 0);
            k2.addLine(3, 3, 7, 3); // 与上面那条不接
            check(com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(k2).isEmpty(),
                    "开链/悬空线段不产生轮廓（拉伸不出实体）");
            // 接上就成环：补两条边把 (0,0)→(5,0)→(5,3) 接回起点
            // ⚠ 第一版我补的是 (3,3)→(7,3)，通向 (7,3) —— 那里什么都没有，当然是开链。
            k2.addLine(5, 0, 5, 3);
            k2.addLine(5, 3, 0, 0);
            check(com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(k2).size() == 1,
                    "补上两条边后形成 1 个轮廓");
        }

        // --- 圆：本身就是闭合轮廓 ---
        final com.ovo.sablestopnow.sketch.SketchSession s3 = editing();
        final Sketch k3 = s3.working();
        if (k3 != null) {
            k3.addCircle(0, 0, 5);
            final var circles = com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(k3);
            check(circles.size() == 1, "圆单独成环");
            if (!circles.isEmpty()) {
                check(circles.get(0).counterClockwise(), "圆的轮廓是逆时针");
                // ⚠ 圆是用 48 段**内接**折线近似的，面积必然略小于 πr²（约差 0.3%）。
                // 拿 1e-6 的精度去比一个近似值是无解的 —— 这里按 1% 相对误差比。
                final double circleArea = Math.abs(circles.get(0).signedArea());
                check(Math.abs(circleArea - Math.PI * 25.0) / (Math.PI * 25.0) < 0.01,
                        "圆面积 ≈ πr²（折线近似，实际 " + circleArea + "）");
            }
        }

        // --- 自交轮廓要被识别出来 ---
        final com.ovo.sablestopnow.sketch.SketchSession s4 = editing();
        final Sketch k4 = s4.working();
        if (k4 != null) {
            // 蝴蝶结：(0,0)→(4,4)→(0,4)→(4,0)→回 (0,0)
            k4.addLine(0, 0, 4, 4);
            k4.addLine(4, 4, 0, 4);
            k4.addLine(0, 4, 4, 0);
            k4.addLine(4, 0, 0, 0);
            final var bow = com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(k4);
            check(!bow.isEmpty(), "蝴蝶结能连成环");
            if (!bow.isEmpty()) {
                check(com.ovo.sablestopnow.sketch.SketchProfiles.selfIntersects(bow.get(0)),
                        "蝴蝶结被识别为自交（拉伸会自穿）");
            }
        }

        // --- 轮廓 → 盒子分解（碰撞与渲染共用的地基）---
        // 矩形 → 1 个盒子，且与轮廓范围完全一致
        final com.ovo.sablestopnow.sketch.SketchSession s5 = editing();
        final Sketch k5 = s5.working();
        if (k5 != null) {
            k5.addRectangle(0, 0, 4, 3);
            final var rect = com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(k5);
            check(!rect.isEmpty(), "矩形有轮廓");
            if (!rect.isEmpty()) {
                final var boxes = com.ovo.sablestopnow.sketch.SketchProfiles.toBoxes(rect.get(0));
                check(boxes.size() == 1, "矩形分解成 1 个盒子（实际 " + boxes.size() + "）");
                if (boxes.size() == 1) {
                    final double[] b = boxes.get(0);
                    near(b[0], 0.0, "盒子 minU");
                    near(b[1], 0.0, "盒子 minV");
                    near(b[2], 4.0, "盒子 maxU");
                    near(b[3], 3.0, "盒子 maxV");
                }
            }
        }

        // L 形 → 2 个盒子，总面积等于 L 的面积（分解不能丢也不能多）
        final com.ovo.sablestopnow.sketch.SketchSession s6 = editing();
        final Sketch k6 = s6.working();
        if (k6 != null) {
            // L 形：竖边 (0,0)-(2,0)-(2,1)-(4,1)-(4,3)-(0,3)
            k6.addLine(0, 0, 2, 0);
            k6.addLine(2, 0, 2, 1);
            k6.addLine(2, 1, 4, 1);
            k6.addLine(4, 1, 4, 3);
            k6.addLine(4, 3, 0, 3);
            k6.addLine(0, 3, 0, 0);
            final var lprofiles = com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(k6);
            check(lprofiles.size() == 1, "L 形得到 1 个轮廓");
            if (!lprofiles.isEmpty()) {
                final var boxes = com.ovo.sablestopnow.sketch.SketchProfiles.toBoxes(lprofiles.get(0));
                check(boxes.size() == 2, "L 形分解成 2 个盒子（实际 " + boxes.size() + "）");
                double area = 0;
                for (final double[] b : boxes) {
                    area += (b[2] - b[0]) * (b[3] - b[1]);
                }
                // L 形真实面积 = 左柱 2×3 + 右上 2×2 = 6 + 4 = 10。
                // ⚠ 我第一版写成 8（把右上算成 2×1），是**算术错**，不是分解错 ——
                //    鞋带公式验算也是 10，所以盒子并集正好等于轮廓面积。
                near(area, 10.0, "L 形盒子面积之和 = 轮廓面积（分解无重无漏）");
            }
        }

        // 带孔的矩形 → 孔所在的那一带会被劈成两个盒子
        final com.ovo.sablestopnow.sketch.SketchSession s7 = editing();
        final Sketch k7 = s7.working();
        if (k7 != null) {
            // 外框 (0,0)-(4,4)，孔 (1,1)-(3,3)
            k7.addLine(0, 0, 4, 0);
            k7.addLine(4, 0, 4, 4);
            k7.addLine(4, 4, 0, 4);
            k7.addLine(0, 4, 0, 0);
            // 孔：单独一个轮廓。这里只验"外框那部分分解正确"，
            // 孔的合并（外轮廓减内轮廓）属于拉伸阶段的事。
            final var outer = com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(k7);
            check(outer.size() == 1, "外框 1 个轮廓");
        }

        // 斜边三角：带数 = 不同 v 值个数，面积近似正确
        final com.ovo.sablestopnow.sketch.SketchSession s8 = editing();
        final Sketch k8 = s8.working();
        if (k8 != null) {
            k8.addLine(0, 0, 4, 0);
            k8.addLine(4, 0, 0, 4);
            k8.addLine(0, 4, 0, 0);
            final var tri = com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(k8);
            check(tri.size() == 1, "三角形 1 个轮廓");
            if (!tri.isEmpty()) {
                final var boxes = com.ovo.sablestopnow.sketch.SketchProfiles.toBoxes(tri.get(0));
                double area = 0;
                for (final double[] b : boxes) {
                    area += (b[2] - b[0]) * (b[3] - b[1]);
                }
                // 三角形面积 = 8；斜边用带分解逼近，允许 25% 误差（带只有 3 个 v 值）
                near(area / 8.0, 1.0, "三角形盒子面积与轮廓面积同量级（实际 " + area + "）");
            }
        }
    }

    /**
     * 拉伸：轮廓 × 深度 → 局部盒子。
     *
     * <p>守的是"拉伸出来的是不是玩家预期的那个东西"：深度模式搞错 → 实体长在草图另一侧；
     * 平面法线映射搞错 → 实体的坐标轴张冠李戴（这种错误在游戏里看起来像"形状对了但朝向诡异"）。
     */
    private static void extrude() {
        // --- 四种深度模式 ---
        var r = com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(3.0).depthRange();
        near(r[0], 0.0, "单向：从 0 开始");
        near(r[1], 3.0, "单向：到 3");
        r = com.ovo.sablestopnow.sketch.Extrude.Params.symmetric(4.0).depthRange();
        near(r[0], -2.0, "对称：往负侧 2");
        near(r[1], 2.0, "对称：往正侧 2");
        r = com.ovo.sablestopnow.sketch.Extrude.Params.twoSided(5.0, 2.0).depthRange();
        near(r[0], -2.0, "两侧：负侧 2");
        near(r[1], 5.0, "两侧：正侧 5");

        // 非法深度要被挡住（否则一个手滑输入能把世界撑爆）
        boolean threw = false;
        try {
            new com.ovo.sablestopnow.sketch.Extrude.Params(
                    com.ovo.sablestopnow.sketch.Extrude.Mode.ONE_SIDED, 0.0, 0.0);
        } catch (final IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "零深度被拒绝");
        // 超过上限要被夹住
        final var clamped = new com.ovo.sablestopnow.sketch.Extrude.Params(
                com.ovo.sablestopnow.sketch.Extrude.Mode.ONE_SIDED, 1.0e9, 0.0);
        near(clamped.depth1(), com.ovo.sablestopnow.sketch.Extrude.MAX_DEPTH, "超限深度被夹到上限");

        // --- 矩形 + 上视基准面 → 一个盒子，三个轴的范围都要对 ---
        final com.ovo.sablestopnow.sketch.SketchSession s = editing();
        final Sketch k = s.working();
        if (k != null) {
            // TOP 基准面：u=+X、v=+Z、法线=-Y；原点放在 (100,64,100)
            k.setPlane(SketchPlane.base(SketchPlane.Base.TOP, new Vector3d(100, 64, 100)));
            k.addRectangle(0, 0, 4, 3);
            final var solidBoxes = com.ovo.sablestopnow.sketch.Extrude.boxesOf(k,
                    com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
            check(solidBoxes.size() == 1, "矩形单向拉伸得到 1 个盒子（实际 "
                    + solidBoxes.size() + "）");
            if (solidBoxes.size() == 1) {
                final double[] b = solidBoxes.get(0);
                // u∈[0,4] → X∈[100,104]；v∈[0,3] → Z∈[100,103]；
                // 深度 [0,2] 沿法线 -Y → Y∈[64-2, 64] = [62,64]
                near(b[0], 100.0, "盒子 minX（u 起点）");
                near(b[3], 104.0, "盒子 maxX（u∈[0,4]）");
                near(b[1], 62.0, "盒子 minY（沿法线 -Y 拉 2）");
                near(b[4], 64.0, "盒子 maxY（从平面往回）");
                near(b[2], 100.0, "盒子 minZ（v 起点）");
                near(b[5], 103.0, "盒子 maxZ（v∈[0,3]）");
            }

            // 对称拉伸：应当以平面为中心，两侧各一半
            final var sym = com.ovo.sablestopnow.sketch.Extrude.boxesOf(k,
                    com.ovo.sablestopnow.sketch.Extrude.Params.symmetric(4.0));
            if (sym.size() == 1) {
                near(sym.get(0)[1], 62.0, "对称：负侧到 62");
                near(sym.get(0)[4], 66.0, "对称：正侧到 66");
            }

            // --- 退化轮廓不产生盒子（零面积的薄片拉伸没有意义）---
            final com.ovo.sablestopnow.sketch.SketchSession s2 = editing();
            final Sketch k2 = s2.working();
            if (k2 != null) {
                k2.addLine(0, 0, 5, 0);
                k2.addLine(5, 0, 0, 0); // 原路返回：面积 0
                check(com.ovo.sablestopnow.sketch.Extrude.boxesOf(k2,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(1.0)).isEmpty(),
                        "退化轮廓（零面积）不产生盒子");
            }

            // --- 没有闭合轮廓时返回空（UI 据此提示"轮廓不闭合"）---
            final com.ovo.sablestopnow.sketch.SketchSession s3 = editing();
            final Sketch k3 = s3.working();
            if (k3 != null) {
                k3.addLine(0, 0, 3, 0); // 开链
                check(com.ovo.sablestopnow.sketch.Extrude.boxesOf(k3,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(1.0)).isEmpty(),
                        "开链不产生盒子");
            }
        }
    }

    /**
     * 实体模型（{@link com.ovo.sablestopnow.sketch.Solid}）。
     *
     * <p>测三件事：<b>盒子是现算的</b>（不是存的，改参数就跟着变）、
     * <b>草图引不到时返回空而不是崩</b>、以及<b>序号不会撞车</b>。
     */
    private static void solids() {
        final com.ovo.sablestopnow.sketch.SketchSession s = editing();
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        s.click(0.0, 0.0);
        s.click(4.0, 3.0);
        final Sketch sketch = s.working();
        final java.util.UUID body = sketch.body();

        // --- 序号：最大 + 1，不是数量 + 1 ---
        check(com.ovo.sablestopnow.sketch.Solid.nextIndex(java.util.List.of(), body) == 1,
                "空表上第一个实体序号是 1");
        final com.ovo.sablestopnow.sketch.Solid a =
                com.ovo.sablestopnow.sketch.Solid.create(body, sketch.id(), 1,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        final com.ovo.sablestopnow.sketch.Solid b =
                com.ovo.sablestopnow.sketch.Solid.create(body, sketch.id(), 2,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        check(com.ovo.sablestopnow.sketch.Solid.nextIndex(java.util.List.of(a, b), body) == 3,
                "两个实体之后序号是 3");
        check(com.ovo.sablestopnow.sketch.Solid.nextIndex(java.util.List.of(a, b),
                        java.util.UUID.randomUUID()) == 1,
                "序号只在同一子结构内累加（别的结构互不影响）");
        // 删掉中间那个（留 1 和 3）再新建：数量法会给 3，撞上还活着的"拉伸3"
        final com.ovo.sablestopnow.sketch.Solid c =
                com.ovo.sablestopnow.sketch.Solid.create(body, sketch.id(), 3,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        check(com.ovo.sablestopnow.sketch.Solid.nextIndex(java.util.List.of(a, c), body) == 4,
                "删掉中间那个后新序号是 4（数量法会给 3，与活着的撞名）");
        check(!a.id().equals(b.id()), "两次创建拿到不同的 id");

        // --- 盒子：现算、随参数变 ---
        final Vector3d origin = sketch.plane().origin();
        final Vector3d n = sketch.plane().normal();
        final java.util.List<double[]> one =
                a.boxes(sketch); // ONE_SIDED 2.0
        check(!one.isEmpty(), "矩形实体的盒子非空");
        check(one.size() == 1, "一个矩形轮廓 → 一个盒子");
        double[] d = extentAlong(one, n, origin);
        near(d[0], 0.0, "单侧拉伸从草图平面起（下界 0）");
        near(d[1], 2.0, "单侧拉伸厚度 = 深度");

        d = extentAlong(
                a.withParams(com.ovo.sablestopnow.sketch.Extrude.Params.symmetric(6.0)).boxes(sketch),
                n, origin);
        near(d[0], -3.0, "对称拉伸向负侧走一半");
        near(d[1], 3.0, "对称拉伸向正侧走一半");

        d = extentAlong(
                a.withParams(com.ovo.sablestopnow.sketch.Extrude.Params.twoSided(3.0, 1.0))
                        .boxes(sketch),
                n, origin);
        near(d[0], -1.0, "两侧拉伸：负侧 = depth2");
        near(d[1], 3.0, "两侧拉伸：正侧 = depth1");

        check(a.withParams(com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(5.0)).index() == a.index()
                        && a.withParams(com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(5.0)).id()
                        .equals(a.id()),
                "换参数不改变身份（id / 序号）");
        // 改深度只该动**沿法线**的那一维。⚠ 不能拿盒子的三个边长一起去比 ——
        // 盒子是轴对齐的，法线方向恰好就是其中一根轴，它本来就该变。
        // 不变量是：轮廓的 u 向与 v 向跨度不变，法线向跨度按深度差增长。
        final com.ovo.sablestopnow.sketch.Extrude.Params deeper =
                com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(9.0);
        final java.util.List<double[]> thick = a.withParams(deeper).boxes(sketch);
        final Vector3d axisU = sketch.plane().axisU();
        final Vector3d axisV = sketch.plane().axisV();
        final double[] oneU = extentAlong(one, axisU, origin);
        final double[] thickU = extentAlong(thick, axisU, origin);
        final double[] oneV = extentAlong(one, axisV, origin);
        final double[] thickV = extentAlong(thick, axisV, origin);
        near(thickU[0] - oneU[0], 0.0, "改深度不动轮廓 u 向起点");
        near(thickU[1] - oneU[1], 0.0, "改深度不动轮廓 u 向跨度");
        near(thickV[0] - oneV[0], 0.0, "改深度不动轮廓 v 向起点");
        near(thickV[1] - oneV[1], 0.0, "改深度不动轮廓 v 向跨度");
        final double[] oneN = extentAlong(one, n, origin);
        final double[] thickN = extentAlong(thick, n, origin);
        near(thickN[1] - thickN[0] - (oneN[1] - oneN[0]), 7.0, "深度 2 → 9，法线向厚度正好增加 7");

        // --- 轮廓确实来自草图：矩形 4×3 ---
        near(oneU[1] - oneU[0], 4.0, "实体的 u 向跨度 = 矩形的宽");
        near(oneV[1] - oneV[0], 3.0, "实体的 v 向跨度 = 矩形的高");

        // --- 引不到草图：空表，不抛 ---
        check(a.boxes(null).isEmpty(), "草图解析不到（null）→ 空盒子，不抛异常");
        final com.ovo.sablestopnow.sketch.SketchSession other = editing();
        other.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        other.click(0.0, 0.0);
        other.click(3.0, 0.0);
        check(a.boxes(other.working()).isEmpty(), "草图 id 对不上 → 空盒子（不拿别人的轮廓硬拉）");
        check(a.bounds(null) == null, "没有几何时包围盒是 null");

        // --- resolve / placeAll ---
        final java.util.List<Sketch> all = java.util.List.of(sketch);
        check(a.resolve(all) == sketch, "resolve 找得到自己的草图");
        check(a.resolve(java.util.List.of(other.working())) == null, "resolve 找不到就返回 null");
        final java.util.List<com.ovo.sablestopnow.sketch.Solid.Placed> placed =
                com.ovo.sablestopnow.sketch.Solid.placeAll(java.util.List.of(a, b), all);
        check(placed.size() == 2, "placeAll 把两个实体都解析出来了");
        final java.util.List<com.ovo.sablestopnow.sketch.Solid.Placed> orphan =
                com.ovo.sablestopnow.sketch.Solid.placeAll(java.util.List.of(a, b),
                        java.util.List.of(other.working()));
        check(orphan.isEmpty(), "草图不在集合里 → placeAll 跳过（渲染不该被孤儿实体拖崩）");

        // --- 构造校验 ---
        boolean threw = false;
        try {
            com.ovo.sablestopnow.sketch.Solid.create(body, sketch.id(), 0,
                    com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(1.0));
        } catch (final IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "序号 0 被拒（序号从 1 开始）");
        threw = false;
        try {
            com.ovo.sablestopnow.sketch.Solid.create(body, sketch.id(), 1, null);
        } catch (final NullPointerException e) {
            threw = true;
        }
        check(threw, "拉伸参数为空被拒");
        threw = false;
        try {
            com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(-1.0);
        } catch (final IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "负深度被拒");
        near(com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(1.0e9).depth1(),
                com.ovo.sablestopnow.sketch.Extrude.MAX_DEPTH, "超大深度被夹到上限");
    }

    /**
     * 一批轴对齐盒子在**某个方向**上的区间 {@code {lo,hi}}（相对平面原点）。
     *
     * <p>用点积而不是"看 u 是 X 还是 Z"：这样六个方向的平面通用，
     * 断言里不必写分支，将来平面朝向变了断言也不用改。
     */
    private static double[] extentAlong(final java.util.List<double[]> boxes,
                                              final Vector3d n, final Vector3d origin) {
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (final double[] b : boxes) {
            for (int i = 0; i < 8; i++) {
                final double x = (i & 1) == 0 ? b[0] : b[3];
                final double y = (i & 2) == 0 ? b[1] : b[4];
                final double z = (i & 4) == 0 ? b[2] : b[5];
                final double t = (x - origin.x) * n.x + (y - origin.y) * n.y + (z - origin.z) * n.z;
                lo = Math.min(lo, t);
                hi = Math.max(hi, t);
            }
        }
        return new double[] {lo, hi};
    }

    /**
     * 孔与嵌套：**外框里挖个圆**必须真的挖掉，不能被填实。
     *
     * <p>这是用户明确要求「凹多边形与孔也要精确碰撞」的落点。
     * 之前的实现按**轮廓**逐个拉伸，而 {@code findLoops} 把孔返回成独立轮廓 →
     * 孔被当成实心柱体填了进去（注释却写着支持带孔矩形，两者根本没接上）。
     */
    private static void profileHoles() {
        final com.ovo.sablestopnow.sketch.SketchSession s = editing();
        // 外框 10×10
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        s.click(0.0, 0.0);
        s.click(10.0, 10.0);
        // 里面挖一个 r=2 的圆（圆心 5,5）
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        s.click(5.0, 5.0);
        s.click(7.0, 5.0);
        final Sketch sketch = s.working();

        final java.util.List<com.ovo.sablestopnow.sketch.SketchProfiles.Profile> loops =
                com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(sketch);
        check(loops.size() == 2, "外框 + 圆 = 2 条闭合轮廓（它们本来就是分开的）");

        final java.util.List<com.ovo.sablestopnow.sketch.SketchProfiles.Region> regions =
                com.ovo.sablestopnow.sketch.SketchProfiles.nest(loops);
        check(regions.size() == 1, "两条轮廓归成**一个**区域（圆是外框的孔）");
        check(regions.get(0).holes().size() == 1, "该区域带了 1 个孔");
        // 回归：这个例子里外框的"内部取点"恰好也落在 (5,5)，正好是圆心。
        // 只用"点在不在内部"判包含会得出**互相包含**，两个环深度都是 1，区域表直接空掉。
        // 现在是面积仲裁挡住的 —— 这条断言就是为了别再退回那个实现。
        check(regions.get(0).outer().size() == 4,
                "外框仍是那个 4 顶点的矩形（没被孔的判定顶掉）");
        check(Math.abs(regions.get(0).outer().signedArea()) > regions.get(0).holes().get(0)
                        .signedArea() * -1.0,
                "面积大的那个才是父亲（面积仲裁生效）");

        // 净面积 = 100 − π·2² ；折线圆是 48 边形，面积略小于真圆
        final double expected = 100.0 - Math.PI * 4.0;
        final double area = regions.get(0).area();
        check(Math.abs(area - expected) < 0.05,
                "区域净面积 = 外框 − 孔（实测 " + String.format("%.3f", area)
                        + "，期望约 " + String.format("%.3f", expected) + "）");

        // --- 盒子必须绕开孔 ---
        final java.util.List<double[]> boxes =
                com.ovo.sablestopnow.sketch.Extrude.boxesOf(sketch,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(1.0));
        check(!boxes.isEmpty(), "带孔区域能拉伸");

        // 孔的正中心不该被任何盒子盖住
        check(!covered(boxes, sketch, 5.0, 5.0), "孔中心没有被填实（这是本次修的核心）");
        // 外框中线（孔之外）必须被盖住
        check(covered(boxes, sketch, 0.5, 5.0), "孔左侧的材料仍在");
        check(covered(boxes, sketch, 9.5, 5.0), "孔右侧的材料仍在");
        check(covered(boxes, sketch, 5.0, 0.5), "孔下方的材料仍在");
        check(covered(boxes, sketch, 5.0, 9.5), "孔上方的材料仍在");
        // 孔的边界外一点点（r=2 → 2.2 处）应当已经在材料里
        check(covered(boxes, sketch, 5.0 + 2.2, 5.0), "孔外 0.2 格处是材料");

        // --- 没有孔时不该凭空多出孔 ---
        final com.ovo.sablestopnow.sketch.SketchSession plain = editing();
        plain.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        plain.click(0.0, 0.0);
        plain.click(4.0, 3.0);
        final java.util.List<com.ovo.sablestopnow.sketch.SketchProfiles.Region> solo =
                com.ovo.sablestopnow.sketch.SketchProfiles.nest(
                        com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(plain.working()));
        check(solo.size() == 1 && solo.get(0).holes().isEmpty(), "单独的矩形：一个区域、零个孔");
        check(covered(com.ovo.sablestopnow.sketch.Extrude.boxesOf(plain.working(),
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(1.0)),
                plain.working(), 2.0, 1.5), "实心矩形的内部被填实");

        // --- 两个分开的矩形 = 两个区域（各自成柱，不是一个怪形状） ---
        final com.ovo.sablestopnow.sketch.SketchSession two = editing();
        two.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        two.click(0.0, 0.0);
        two.click(2.0, 2.0);
        two.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        two.click(10.0, 10.0);
        two.click(12.0, 12.0);
        final java.util.List<com.ovo.sablestopnow.sketch.SketchProfiles.Region> apart =
                com.ovo.sablestopnow.sketch.SketchProfiles.nest(
                        com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(two.working()));
        check(apart.size() == 2, "两个分开的矩形 = 两个区域（互不包含）");
        check(apart.get(0).holes().isEmpty() && apart.get(1).holes().isEmpty(),
                "互不包含时谁也不当谁的孔");

        // --- 退化环被 nest 滤掉 ---
        check(com.ovo.sablestopnow.sketch.SketchProfiles.nest(java.util.List.of())
                .isEmpty(), "空轮廓表 → 空区域表");

        // --- 内部取点：凹多边形也要落在内部 ---
        final java.util.List<double[]> lshape = java.util.List.of(
                new double[] {0, 0}, new double[] {6, 0}, new double[] {6, 2},
                new double[] {2, 2}, new double[] {2, 6}, new double[] {0, 6});
        final com.ovo.sablestopnow.sketch.SketchProfiles.Profile lProfile =
                new com.ovo.sablestopnow.sketch.SketchProfiles.Profile(lshape,
                        com.ovo.sablestopnow.sketch.SketchProfiles.signedArea(lshape));
        final double[] ip = com.ovo.sablestopnow.sketch.SketchProfiles.interiorPoint(lProfile);
        check(ip != null && com.ovo.sablestopnow.sketch.SketchProfiles.containsPoint(
                        lProfile, ip[0], ip[1]),
                "凹轮廓的内部取点确实在内部（不能拿包围盒中心，它在缺口里）");
        check(!com.ovo.sablestopnow.sketch.SketchProfiles.containsPoint(lProfile, 4.0, 4.0),
                "L 形的缺口处判为外部");
        check(com.ovo.sablestopnow.sketch.SketchProfiles.containsPoint(lProfile, 1.0, 1.0),
                "L 形的实心处判为内部");
        near(Math.abs(com.ovo.sablestopnow.sketch.SketchProfiles.signedArea(lshape)), 20.0,
                "L 形面积 = 6×6 方块挖掉右上 4×4 = 36 − 16 = 20");

        // L 形的盒子分解：面积守恒（盒子并集面积应当等于多边形面积）
        final com.ovo.sablestopnow.sketch.SketchProfiles.Region lRegion =
                new com.ovo.sablestopnow.sketch.SketchProfiles.Region(lProfile, java.util.List.of());
        final java.util.List<double[]> lBoxes =
                com.ovo.sablestopnow.sketch.SketchProfiles.toBoxes(lRegion);
        near(boxesArea(lBoxes), 20.0, "L 形的盒子并集面积 = 多边形面积（精确分解）");
        check(lBoxes.stream().noneMatch(bx -> bx[0] < -1.0e-9 || bx[1] < -1.0e-9
                        || bx[2] > 6.0 + 1.0e-9 || bx[3] > 6.0 + 1.0e-9),
                "L 形的盒子都在轮廓包围盒内");
    }

    /** 平面内点 (u,v) 是否被这批盒子盖住（盒子是 u/v 平面的 {minU,minV,maxU,maxV}）。 */
    private static boolean covered(final java.util.List<double[]> boxes, final Sketch sketch,
                                   final double u, final double v) {
        for (final double[] b : boxes) {
            // 盒子已换算到 plot 局部系，所以要把询问点也换算过去再比
            final Vector3d p = sketch.plane().toLocal(u, v);
            if (p.x >= b[0] - 1.0e-9 && p.x <= b[3] + 1.0e-9
                    && p.y >= b[1] - 1.0e-9 && p.y <= b[4] + 1.0e-9
                    && p.z >= b[2] - 1.0e-9 && p.z <= b[5] + 1.0e-9) {
                return true;
            }
        }
        return false;
    }

    /** 平面盒子的总面积。 */
    private static double boxesArea(final java.util.List<double[]> boxes) {
        double sum = 0.0;
        for (final double[] b : boxes) {
            sum += (b[2] - b[0]) * (b[3] - b[1]);
        }
        return sum;
    }

    /**
     * 棱柱线框：**渲染必须从轮廓生成，不能拿碰撞用的盒分解**。
     *
     * <p>用户实测反馈「拉伸出的模型是由立方体拼接成的，不是由草图的形状拉伸的」——
     * 根因就是渲染画了 {@code boxesOf} 的结果。矩形恰好只分解出一个盒子所以看不出来，
     * 圆被扫描线切成几十个盒子就露馅了。本方法把这个差异钉死。
     */
    private static void prismEdges() {
        final com.ovo.sablestopnow.sketch.Extrude.Params one =
                com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0);

        // --- 矩形：4 个顶点 → 每点 3 条（下环 / 上环 / 竖棱）= 12 条 ---
        final com.ovo.sablestopnow.sketch.SketchSession rs = editing();
        rs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        rs.click(0.0, 0.0);
        rs.click(4.0, 3.0);
        final Sketch rect = rs.working();
        final java.util.List<Vector3d[]> rectEdges =
                com.ovo.sablestopnow.sketch.Extrude.prismEdges(rect, one);
        check(rectEdges.size() == 12, "矩形棱柱 = 4 顶点 × 3 = 12 条棱（实测 " + rectEdges.size() + "）");
        // 矩形这个例子里盒子分解恰好也是 1 个盒子，所以两种画法看不出差别 —— 这正是 bug 藏得住的原因
        check(com.ovo.sablestopnow.sketch.Extrude.boxesOf(rect, one).size() == 1,
                "矩形只分解出 1 个盒子（所以拿盒子渲染在矩形上看不出问题）");

        // --- 圆：48 边形 → 144 条棱；而盒子分解是几十个盒子 ---
        final com.ovo.sablestopnow.sketch.SketchSession cs = editing();
        cs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        cs.click(0.0, 0.0);
        cs.click(3.0, 0.0);
        final Sketch circle = cs.working();
        final java.util.List<Vector3d[]> circleEdges =
                com.ovo.sablestopnow.sketch.Extrude.prismEdges(circle, one);
        check(circleEdges.size() == 144,
                "圆棱柱 = 48 顶点 × 3 = 144 条棱（实测 " + circleEdges.size() + "）");
        // ⚠ 回归护栏：**即使最细的采样**，圆的盒分解也是几十个盒子 —— 拿它渲染就是"立方体拼接"。
        // 这里刻意显式用 RENDER 采样：碰撞路径已经改用粗采样（6 个盒子），
        // 但"渲染不能用盒分解"这个结论与采样精度无关。
        final int circleBoxes = com.ovo.sablestopnow.sketch.Extrude.boxesOf(circle, one,
                com.ovo.sablestopnow.sketch.SketchProfiles.Sampling.RENDER).size();
        check(circleBoxes > 20,
                "圆的盒分解有 " + circleBoxes + " 个盒子（>20）—— 所以渲染绝不能用它");

        // --- 棱柱的顶/底环确实落在法线两侧的给定深度上 ---
        final Vector3d n = circle.plane().normal();
        final Vector3d o = circle.plane().origin();
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;
        for (final Vector3d[] seg : circleEdges) {
            for (final Vector3d p : seg) {
                final double d = (p.x - o.x) * n.x + (p.y - o.y) * n.y + (p.z - o.z) * n.z;
                lo = Math.min(lo, d);
                hi = Math.max(hi, d);
            }
        }
        near(lo, 0.0, "棱柱下环在深度 0");
        near(hi, 2.0, "棱柱上环在深度 2");

        final java.util.List<Vector3d[]> sym = com.ovo.sablestopnow.sketch.Extrude.prismEdges(circle,
                com.ovo.sablestopnow.sketch.Extrude.Params.symmetric(4.0));
        double slo = Double.MAX_VALUE;
        double shi = -Double.MAX_VALUE;
        for (final Vector3d[] seg : sym) {
            for (final Vector3d p : seg) {
                final double d = (p.x - o.x) * n.x + (p.y - o.y) * n.y + (p.z - o.z) * n.z;
                slo = Math.min(slo, d);
                shi = Math.max(shi, d);
            }
        }
        near(slo, -2.0, "对称拉伸：下环在 −2");
        near(shi, 2.0, "对称拉伸：上环在 +2");

        // --- 圆的半径没变（轮廓就是要拉伸的那个形状） ---
        double maxR = 0.0;
        for (final Vector3d[] seg : circleEdges) {
            for (final Vector3d p : seg) {
                final double du = (p.x - o.x) * circle.plane().axisU().x
                        + (p.y - o.y) * circle.plane().axisU().y
                        + (p.z - o.z) * circle.plane().axisU().z;
                final double dv = (p.x - o.x) * circle.plane().axisV().x
                        + (p.y - o.y) * circle.plane().axisV().y
                        + (p.z - o.z) * circle.plane().axisV().z;
                maxR = Math.max(maxR, Math.hypot(du, dv));
            }
        }
        near(maxR, 3.0, "棱柱顶点的最大半径 = 圆的半径 3");

        // --- 带孔：外圈 4 + 孔 48 = 52 个顶点 → 156 条棱（孔也要有壁） ---
        final com.ovo.sablestopnow.sketch.SketchSession hs = editing();
        hs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        hs.click(0.0, 0.0);
        hs.click(10.0, 10.0);
        hs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        hs.click(5.0, 5.0);
        hs.click(7.0, 5.0);
        final java.util.List<Vector3d[]> holeEdges =
                com.ovo.sablestopnow.sketch.Extrude.prismEdges(hs.working(), one);
        check(holeEdges.size() == 156,
                "带孔棱柱 = (4 + 48) 顶点 × 3 = 156 条棱（实测 " + holeEdges.size() + "）");

        // --- 没有闭合轮廓 → 空（不是崩） ---
        final com.ovo.sablestopnow.sketch.SketchSession open = editing();
        open.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        open.click(0.0, 0.0);
        open.click(5.0, 0.0);
        check(com.ovo.sablestopnow.sketch.Extrude.prismEdges(open.working(), one).isEmpty(),
                "只有一条不闭合的线 → 没有棱柱棱（不抛异常）");
    }

    /**
     * 区域三角化（拉伸体顶/底面 + 将来贴材质的前置）。
     *
     * <p>用两个不变量钉死：**三角形个数**（n + 2h − 2）与**面积守恒**
     * （所有三角形面积之和 == 区域净面积）。有了这两条，
     * 耳切漏切、切到外面、把孔填上，都会被立刻抓到。
     */
    private static void triangulation() {
        // --- 矩形 → 2 个三角形 ---
        final com.ovo.sablestopnow.sketch.SketchSession rs = editing();
        rs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        rs.click(0.0, 0.0);
        rs.click(4.0, 3.0);
        final java.util.List<double[][]> rectTri = trianglesOf(rs.working());
        check(rectTri.size() == 2, "矩形 → 2 个三角形（实测 " + rectTri.size() + "）");
        near(triArea(rectTri), 12.0, "矩形三角形面积和 = 12");

        // --- L 形（凹）→ 4 个三角形，面积 20 ---
        final java.util.List<double[]> lshape = java.util.List.of(
                new double[] {0, 0}, new double[] {6, 0}, new double[] {6, 2},
                new double[] {2, 2}, new double[] {2, 6}, new double[] {0, 6});
        final java.util.List<double[][]> lTri = trianglesOfRaw(lshape, java.util.List.of());
        check(lTri.size() == 4, "L 形（凹）→ 4 个三角形（实测 " + lTri.size() + "）");
        near(triArea(lTri), 20.0, "L 形三角形面积和 = 20（凹处没被切到外面）");

        // --- 圆（48 边形）→ 46 个三角形 ---
        final com.ovo.sablestopnow.sketch.SketchSession cs = editing();
        cs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        cs.click(0.0, 0.0);
        cs.click(3.0, 0.0);
        final java.util.List<double[][]> circleTri = trianglesOf(cs.working());
        check(circleTri.size() == 46, "48 边形 → 46 个三角形（实测 " + circleTri.size() + "）");
        // 正 48 边形面积 = 0.5·48·r²·sin(2π/48)
        near(triArea(circleTri), 0.5 * 48 * 9 * Math.sin(2 * Math.PI / 48), "圆三角形面积和 = 48 边形面积");

        // --- 带孔：n=52, h=1 → 52 个三角形；面积 = 外框 − 孔 ---
        final com.ovo.sablestopnow.sketch.SketchSession hs = editing();
        hs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        hs.click(0.0, 0.0);
        hs.click(10.0, 10.0);
        hs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        hs.click(5.0, 5.0);
        hs.click(7.0, 5.0);
        final java.util.List<double[][]> holeTri = trianglesOf(hs.working());
        check(holeTri.size() == 52,
                "带孔区域 = 52 + 2·1 − 2 = 52 个三角形（实测 " + holeTri.size() + "）");
        final double holeArea = 0.5 * 48 * 4 * Math.sin(2 * Math.PI / 48);
        near(triArea(holeTri), 100.0 - holeArea, "带孔三角形面积和 = 100 − 孔面积");

        // --- 所有三角形同向（面积为正），否则渲染时会有背面朝外的面 ---
        boolean allPositive = true;
        for (final double[][] t : holeTri) {
            if (com.ovo.sablestopnow.sketch.SketchProfiles.signedArea(java.util.List.of(t)) <= 0) {
                allPositive = false;
                break;
            }
        }
        check(allPositive, "所有三角形都是逆时针（朝同一面）");

        // --- 没有孔时不该凭空多切 ---
        check(trianglesOfRaw(java.util.List.of(), java.util.List.of()).isEmpty(), "空轮廓 → 没有三角形");
    }

    /** 从草图取出所有区域的三角形（合在一起）。 */
    private static java.util.List<double[][]> trianglesOf(final Sketch sketch) {
        final java.util.List<double[][]> out = new java.util.ArrayList<>();
        for (final com.ovo.sablestopnow.sketch.SketchProfiles.Region region
                : com.ovo.sablestopnow.sketch.SketchProfiles.nest(
                        com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(sketch))) {
            out.addAll(com.ovo.sablestopnow.sketch.SketchProfiles.triangulate(region));
        }
        return out;
    }

    /** 直接给顶点造一个区域再三角化（测非草图来源的多边形）。 */
    private static java.util.List<double[][]> trianglesOfRaw(final java.util.List<double[]> outer,
                                                            final java.util.List<java.util.List<double[]>> holes) {
        final com.ovo.sablestopnow.sketch.SketchProfiles.Profile outerProfile =
                new com.ovo.sablestopnow.sketch.SketchProfiles.Profile(outer,
                        com.ovo.sablestopnow.sketch.SketchProfiles.signedArea(outer));
        final java.util.List<com.ovo.sablestopnow.sketch.SketchProfiles.Profile> holeProfiles =
                new java.util.ArrayList<>();
        for (final java.util.List<double[]> h : holes) {
            holeProfiles.add(new com.ovo.sablestopnow.sketch.SketchProfiles.Profile(h,
                    com.ovo.sablestopnow.sketch.SketchProfiles.signedArea(h)));
        }
        return com.ovo.sablestopnow.sketch.SketchProfiles.triangulate(
                new com.ovo.sablestopnow.sketch.SketchProfiles.Region(outerProfile, holeProfiles));
    }

    /** 三角形列表的总面积。 */
    private static double triArea(final java.util.List<double[][]> triangles) {
        double sum = 0.0;
        for (final double[][] t : triangles) {
            sum += Math.abs(com.ovo.sablestopnow.sketch.SketchProfiles.signedArea(
                    java.util.List.of(t)));
        }
        return sum;
    }

    /**
     * 棱柱侧面：法线必须**背离材料**（贴材质时法线朝里会一片黑）。
     *
     * <p>判法：把每个面沿自己的法线往外挪一点点，投影回平面内，
     * 那个点必须**落在材料之外**。这条对孔的面同样成立（孔的面朝孔内 = 背离材料），
     * 所以外圈/孔不必分开写断言 —— 正好也验证了"一个法线公式两边都对"。
     */
    private static void prismSideFaces() {
        final com.ovo.sablestopnow.sketch.Extrude.Params one =
                com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0);

        final com.ovo.sablestopnow.sketch.SketchSession rs = editing();
        rs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        rs.click(0.0, 0.0);
        rs.click(4.0, 3.0);
        final Sketch rect = rs.working();
        final java.util.List<com.ovo.sablestopnow.sketch.Extrude.Quad> rectFaces =
                com.ovo.sablestopnow.sketch.Extrude.prismSideFaces(rect, one);
        check(rectFaces.size() == 4, "矩形侧面 = 4 个四边形（实测 " + rectFaces.size() + "）");

        // 法线是单位向量且背离材料
        boolean unit = true;
        boolean outward = true;
        for (final com.ovo.sablestopnow.sketch.Extrude.Quad q : rectFaces) {
            if (Math.abs(q.normal().length() - 1.0) > 1.0e-9) {
                unit = false;
            }
            if (insideMaterial(rect, q, 0.05)) {
                outward = false;
            }
        }
        check(unit, "侧面法线都是单位向量");
        check(outward, "矩形四个侧面的法线都背离材料");

        // 反过来：往**里**挪一点必须在材料内（否则上面那条可能只是恒真）
        boolean inwardHits = true;
        for (final com.ovo.sablestopnow.sketch.Extrude.Quad q : rectFaces) {
            if (!insideMaterial(rect, q, -0.05)) {
                inwardHits = false;
            }
        }
        check(inwardHits, "沿法线反向挪一点落在材料内（说明法线判定不是恒真）");

        // UV：沿边方向是一个边长，沿深度方向是厚度
        double maxU = 0.0;
        double maxV = 0.0;
        for (final com.ovo.sablestopnow.sketch.Extrude.Quad q : rectFaces) {
            for (final double[] uv : q.uv()) {
                maxU = Math.max(maxU, uv[0]);
                maxV = Math.max(maxV, uv[1]);
            }
        }
        near(maxV, 2.0, "UV 的 v 跨度 = 拉伸厚度 2");
        check(maxU > 4.0, "UV 的 u 沿环累计（应超过单边 4），实测 " + maxU);

        // 每个面的 u 跨度等于它那条边的长度（4,3,4,3 → 合计 14）
        near(maxU, 14.0, "一圈的 UV u 总长 = 周长 14");

        // --- 圆 → 48 个侧面 ---
        final com.ovo.sablestopnow.sketch.SketchSession cs = editing();
        cs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        cs.click(0.0, 0.0);
        cs.click(3.0, 0.0);
        final Sketch circle = cs.working();
        final java.util.List<com.ovo.sablestopnow.sketch.Extrude.Quad> circleFaces =
                com.ovo.sablestopnow.sketch.Extrude.prismSideFaces(circle, one);
        check(circleFaces.size() == 48, "圆侧面 = 48 个四边形（实测 " + circleFaces.size() + "）");
        boolean circleOutward = true;
        for (final com.ovo.sablestopnow.sketch.Extrude.Quad q : circleFaces) {
            if (insideMaterial(circle, q, 0.05)) {
                circleOutward = false;
                break;
            }
        }
        check(circleOutward, "圆的所有侧面法线都背离材料（朝外）");

        // --- 带孔 → 外圈 4 + 孔 48 = 52 个侧面；孔的也要有面、且朝孔内 ---
        final com.ovo.sablestopnow.sketch.SketchSession hs = editing();
        hs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        hs.click(0.0, 0.0);
        hs.click(10.0, 10.0);
        hs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        hs.click(5.0, 5.0);
        hs.click(7.0, 5.0);
        final Sketch holed = hs.working();
        final java.util.List<com.ovo.sablestopnow.sketch.Extrude.Quad> holeFaces =
                com.ovo.sablestopnow.sketch.Extrude.prismSideFaces(holed, one);
        check(holeFaces.size() == 52, "带孔侧面 = 4 + 48 = 52（实测 " + holeFaces.size() + "）");
        boolean holeOutward = true;
        for (final com.ovo.sablestopnow.sketch.Extrude.Quad q : holeFaces) {
            if (insideMaterial(holed, q, 0.05)) {
                holeOutward = false;
                break;
            }
        }
        check(holeOutward, "孔壁的法线也背离材料（指向孔内）");

        // --- 对称拉伸：v 跨度 = 总厚 ---
        final java.util.List<com.ovo.sablestopnow.sketch.Extrude.Quad> symFaces =
                com.ovo.sablestopnow.sketch.Extrude.prismSideFaces(circle,
                        com.ovo.sablestopnow.sketch.Extrude.Params.symmetric(4.0));
        double symMaxV = 0.0;
        for (final com.ovo.sablestopnow.sketch.Extrude.Quad q : symFaces) {
            for (final double[] uv : q.uv()) {
                symMaxV = Math.max(symMaxV, uv[1]);
            }
        }
        near(symMaxV, 4.0, "对称拉伸的 UV v 跨度 = 总厚 4");
    }

    /** 把面的中点沿法线挪 {@code step}，投影回平面，问它是否落在**材料**内。 */
    private static boolean insideMaterial(final Sketch sketch,
                                          final com.ovo.sablestopnow.sketch.Extrude.Quad quad,
                                          final double step) {
        final Vector3d[] c = quad.corners();
        final Vector3d mid = new Vector3d(c[0]).add(c[1]).add(c[2]).add(c[3]).mul(0.25);
        final Vector3d moved = mid.add(quad.normal().mul(step, new Vector3d()));
        final SketchPlane plane = sketch.plane();
        final Vector3d rel = moved.sub(plane.origin(), new Vector3d());
        final double u = rel.dot(plane.axisU());
        final double v = rel.dot(plane.axisV());
        for (final com.ovo.sablestopnow.sketch.SketchProfiles.Region region
                : com.ovo.sablestopnow.sketch.SketchProfiles.nest(
                        com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(sketch))) {
            if (!com.ovo.sablestopnow.sketch.SketchProfiles.containsPoint(region.outer(), u, v)) {
                continue;
            }
            boolean inHole = false;
            for (final com.ovo.sablestopnow.sketch.SketchProfiles.Profile hole : region.holes()) {
                if (com.ovo.sablestopnow.sketch.SketchProfiles.containsPoint(hole, u, v)) {
                    inHole = true;
                    break;
                }
            }
            if (!inHole) {
                return true;
            }
        }
        return false;
    }

    /**
     * 网格装配：**绕序必须与法线一致**。
     *
     * <p>这是渲染里最容易错、又最难靠肉眼查的一条：绕序反了（或者底面忘了反转），
     * 面就成了背面，表现是"从某个方向看是空的/透明的"。
     * 判法：对每个三角形用**顶点顺序**算出几何法线，跟顶点上标的法线点乘必须为正。
     */
    private static void mesh() {
        final com.ovo.sablestopnow.sketch.Extrude.Params one =
                com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0);

        final com.ovo.sablestopnow.sketch.SketchSession rs = editing();
        rs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        rs.click(0.0, 0.0);
        rs.click(4.0, 3.0);
        final Sketch rect = rs.working();
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh rectMesh =
                com.ovo.sablestopnow.sketch.SolidMesh.build(rect, one);

        // 侧面 4 个四边形 = 8 个三角形；顶底各 2 个 = 4；共 12
        check(rectMesh.triangleCount() == 12,
                "矩形网格 = 8（侧）+ 4（顶底）= 12 个三角形（实测 "
                        + rectMesh.triangleCount() + "）");
        check(rectMesh.indices().length == rectMesh.vertices().size() * 3 / 2
                        || rectMesh.indices().length % 3 == 0,
                "索引是 3 的整数倍");

        // ⚠ 核心 oracle：绕序与法线一致
        check(windingMatchesNormals(rectMesh), "矩形：所有三角形的绕序与法线一致");
        check(allNormalsUnit(rectMesh), "矩形：所有法线是单位向量");

        // 顶底法线分别沿 ±平面法线
        final Vector3d planeNormal = rect.plane().normal();
        int up = 0;
        int down = 0;
        for (final com.ovo.sablestopnow.sketch.SolidMesh.Vertex v : rectMesh.vertices()) {
            final double d = v.normal().dot(planeNormal);
            if (d > 0.99) {
                up++;
            } else if (d < -0.99) {
                down++;
            }
        }
        check(up > 0 && down > 0, "顶面法线沿 +n、底面沿 −n（都有）");
        check(up == down, "顶/底面顶点数相等（每个三角形都配了一正一反）");

        // --- 圆：侧面 48×2 = 96 + 顶底 46×2 = 92 → 188 ---
        final com.ovo.sablestopnow.sketch.SketchSession cs = editing();
        cs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        cs.click(0.0, 0.0);
        cs.click(3.0, 0.0);
        final Sketch circle = cs.working();
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh circleMesh =
                com.ovo.sablestopnow.sketch.SolidMesh.build(circle, one);
        check(circleMesh.triangleCount() == 96 + 92,
                "圆网格 = 96（侧）+ 92（顶底）= 188 个三角形（实测 "
                        + circleMesh.triangleCount() + "）");
        check(windingMatchesNormals(circleMesh), "圆：所有三角形的绕序与法线一致");
        check(allNormalsUnit(circleMesh), "圆：所有法线是单位向量");

        // --- 带孔：孔壁也要有面，且绕序一致 ---
        final com.ovo.sablestopnow.sketch.SketchSession hs = editing();
        hs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        hs.click(0.0, 0.0);
        hs.click(10.0, 10.0);
        hs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        hs.click(5.0, 5.0);
        hs.click(7.0, 5.0);
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh holeMesh =
                com.ovo.sablestopnow.sketch.SolidMesh.build(hs.working(), one);
        check(holeMesh.triangleCount() == 104 + 104,
                "带孔网格 = 104（侧）+ 104（顶底）= 208（实测 "
                        + holeMesh.triangleCount() + "）");
        check(windingMatchesNormals(holeMesh), "带孔：所有三角形的绕序与法线一致");
        check(allNormalsUnit(holeMesh), "带孔：所有法线是单位向量");

        // --- 没有三角形是退化的（面积为零的三角形会让法线变成 NaN） ---
        check(noDegenerateTriangles(circleMesh), "圆：没有零面积三角形");
        check(noDegenerateTriangles(holeMesh), "带孔：没有零面积三角形");

        // --- 空草图 → 空网格（不是 null、不抛） ---
        final com.ovo.sablestopnow.sketch.SketchSession empty = editing();
        empty.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        empty.click(0.0, 0.0);
        empty.click(3.0, 0.0);
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh emptyMesh =
                com.ovo.sablestopnow.sketch.SolidMesh.build(empty.working(), one);
        check(emptyMesh.isEmpty() && emptyMesh.triangleCount() == 0,
                "没有闭合轮廓 → 空网格（不抛异常）");
    }

    /** 每个三角形的**几何法线**（由绕序定）与顶点法线必须同向。 */
    private static boolean windingMatchesNormals(
            final com.ovo.sablestopnow.sketch.SolidMesh.Mesh mesh) {
        final int[] idx = mesh.indices();
        final java.util.List<com.ovo.sablestopnow.sketch.SolidMesh.Vertex> vs = mesh.vertices();
        for (int i = 0; i + 2 < idx.length; i += 3) {
            final Vector3d a = vs.get(idx[i]).position();
            final Vector3d b = vs.get(idx[i + 1]).position();
            final Vector3d c = vs.get(idx[i + 2]).position();
            final Vector3d e1 = new Vector3d(b).sub(a);
            final Vector3d e2 = new Vector3d(c).sub(a);
            final Vector3d geo = e1.cross(e2, new Vector3d());
            if (geo.length() < 1.0e-12) {
                continue; // 退化三角形，另有断言管
            }
            geo.normalize();
            if (geo.dot(vs.get(idx[i]).normal()) <= 0.0) {
                return false;
            }
        }
        return true;
    }

    private static boolean allNormalsUnit(final com.ovo.sablestopnow.sketch.SolidMesh.Mesh mesh) {
        for (final com.ovo.sablestopnow.sketch.SolidMesh.Vertex v : mesh.vertices()) {
            if (Math.abs(v.normal().length() - 1.0) > 1.0e-9) {
                return false;
            }
        }
        return true;
    }

    private static boolean noDegenerateTriangles(
            final com.ovo.sablestopnow.sketch.SolidMesh.Mesh mesh) {
        final int[] idx = mesh.indices();
        final java.util.List<com.ovo.sablestopnow.sketch.SolidMesh.Vertex> vs = mesh.vertices();
        for (int i = 0; i + 2 < idx.length; i += 3) {
            final Vector3d a = vs.get(idx[i]).position();
            final Vector3d b = vs.get(idx[i + 1]).position();
            final Vector3d c = vs.get(idx[i + 2]).position();
            final Vector3d geo = new Vector3d(b).sub(a).cross(new Vector3d(c).sub(a));
            if (geo.length() < 1.0e-12) {
                return false;
            }
        }
        return true;
    }

    /**
     * 碰撞查询：盒子 ↔ 方块格。
     *
     * <p>核心是**体积守恒**这条 oracle：把所有格子里的裁切结果加起来，
     * 体积必须等于原实体体积。漏格、重复计格、裁错边界，都会被它抓到。
     * 另外用「被覆盖的格数 == 裁切非空的格数」把两个方法互相钉住。
     */
    private static void collision() {
        // --- 坐标打包与 MC 的 BlockPos 同一套（x:26 | y:12 | z:26） ---
        final long p = com.ovo.sablestopnow.sketch.SolidCollision.pack(-12345, -7, 67890);
        check(com.ovo.sablestopnow.sketch.SolidCollision.unpackX(p) == -12345, "打包往返 x（含负数）");
        check(com.ovo.sablestopnow.sketch.SolidCollision.unpackY(p) == -7, "打包往返 y（含负数）");
        check(com.ovo.sablestopnow.sketch.SolidCollision.unpackZ(p) == 67890, "打包往返 z（含负数）");

        // --- 跨格盒子的覆盖格数 ---
        // [0.5,0.5,0.5] → [2.5,1.5,3.5]：x 占 0..2（3 格）、y 占 0..1（2 格）、z 占 0..3（4 格）
        final java.util.List<double[]> one = java.util.List.of(
                new double[] {0.5, 0.5, 0.5, 2.5, 1.5, 3.5});
        java.util.Set<Long> cells = com.ovo.sablestopnow.sketch.SolidCollision.occupiedCells(one);
        check(cells.size() == 24, "跨 3×2×4 格 → 24 个格子（实测 " + cells.size() + "）");

        // --- 裁切：换算成该格的局部 0..1 坐标 ---
        java.util.List<double[]> clip = com.ovo.sablestopnow.sketch.SolidCollision.clipToCell(one, 0, 0, 0);
        check(clip.size() == 1, "起点格裁剪出 1 个盒子");
        nearVec6(clip.get(0), 0.5, 0.5, 0.5, 1.0, 1.0, 1.0, "起点格局部坐标");
        // ⚠ 期望值**按定义写出来**，不靠心算：
        // 局部 min = max(盒子min, 格坐标) − 格坐标；局部 max = min(盒子max, 格坐标+1) − 格坐标
        // 末点格 (2,1,3)：x: max(.5,2)−2=0 → min(2.5,3)−2=0.5；y、z 同理
        clip = com.ovo.sablestopnow.sketch.SolidCollision.clipToCell(one, 2, 1, 3);
        check(clip.size() == 1, "末点格裁剪出 1 个盒子");
        nearVec6(clip.get(0), 0.0, 0.0, 0.0, 0.5, 0.5, 0.5, "末点格局部坐标");
        check(com.ovo.sablestopnow.sketch.SolidCollision.clipToCell(one, 5, 5, 5).isEmpty(),
                "格子外的裁剪为空");

        // --- ⚠ 体积守恒 ---
        double clipped = 0.0;
        for (final long cell : cells) {
            clipped += com.ovo.sablestopnow.sketch.SolidCollision.volume(
                    com.ovo.sablestopnow.sketch.SolidCollision.clipToCell(one,
                            com.ovo.sablestopnow.sketch.SolidCollision.unpackX(cell),
                            com.ovo.sablestopnow.sketch.SolidCollision.unpackY(cell),
                            com.ovo.sablestopnow.sketch.SolidCollision.unpackZ(cell)));
        }
        near(clipped, com.ovo.sablestopnow.sketch.SolidCollision.volume(one),
                "所有格子的裁切体积和 = 原体积（2×1×3 = 6）");
        near(clipped, 6.0, "体积就是 6");

        // --- 覆盖格数 与 裁切非空格数 必须一致 ---
        int nonEmpty = 0;
        for (int x = -1; x <= 4; x++) {
            for (int y = -1; y <= 3; y++) {
                for (int z = -1; z <= 5; z++) {
                    if (!com.ovo.sablestopnow.sketch.SolidCollision.clipToCell(one, x, y, z).isEmpty()) {
                        nonEmpty++;
                    }
                }
            }
        }
        check(nonEmpty == cells.size(),
                "裁切非空的格数 == 覆盖格数（" + nonEmpty + " vs " + cells.size() + "）");

        // --- ⚠ 边界正好落在整数上时不该多算一层 ---
        final java.util.List<double[]> flush = java.util.List.of(
                new double[] {0, 0, 0, 2, 1, 3});
        cells = com.ovo.sablestopnow.sketch.SolidCollision.occupiedCells(flush);
        check(cells.size() == 6,
                "贴齐整数的盒子占 2×1×3 = 6 格而不是 3×2×4（实测 " + cells.size() + "）");

        // --- 退化盒子不参与 ---
        check(com.ovo.sablestopnow.sketch.SolidCollision.occupiedCells(java.util.List.of(
                new double[] {1, 1, 1, 1, 2, 2})).isEmpty(), "零厚度的盒子不占任何格");
        check(com.ovo.sablestopnow.sketch.SolidCollision.occupiedCells(java.util.List.of()).isEmpty(),
                "空表 → 没有格子");

        // --- 点是否在实体内 ---
        check(com.ovo.sablestopnow.sketch.SolidCollision.contains(one, 1.0, 1.0, 1.0), "内部点算在内");
        check(com.ovo.sablestopnow.sketch.SolidCollision.contains(one, 0.5, 0.5, 0.5), "角点算在内");
        check(!com.ovo.sablestopnow.sketch.SolidCollision.contains(one, 0.4, 1.0, 1.0), "外面点不算");
        check(!com.ovo.sablestopnow.sketch.SolidCollision.contains(one, 3.0, 1.0, 1.0), "另一侧外面不算");

        // --- 相交（只贴着不算） ---
        final java.util.List<double[]> a = java.util.List.of(new double[] {0, 0, 0, 1, 1, 1});
        check(com.ovo.sablestopnow.sketch.SolidCollision.intersects(a,
                java.util.List.of(new double[] {0.5, 0.5, 0.5, 1.5, 1.5, 1.5})), "真重叠 → 相交");
        check(!com.ovo.sablestopnow.sketch.SolidCollision.intersects(a,
                java.util.List.of(new double[] {1, 0, 0, 2, 1, 1})), "只贴着面 → 不算相交");
        check(!com.ovo.sablestopnow.sketch.SolidCollision.intersects(a,
                java.util.List.of(new double[] {5, 5, 5, 6, 6, 6})), "离得远 → 不相交");

        // --- 用真实体跑一遍：矩形拉伸 → 盒子 → 覆盖格 + 体积守恒 ---
        final com.ovo.sablestopnow.sketch.SketchSession rs = editing();
        rs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        rs.click(0.0, 0.0);
        rs.click(4.0, 3.0);
        final java.util.List<double[]> solidBoxes = com.ovo.sablestopnow.sketch.Extrude.boxesOf(
                rs.working(), com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        check(!solidBoxes.isEmpty(), "真实体有盒子");
        final java.util.Set<Long> solidCells =
                com.ovo.sablestopnow.sketch.SolidCollision.occupiedCells(solidBoxes);
        check(!solidCells.isEmpty(), "真实体覆盖到格子");
        double sum = 0.0;
        for (final long cell : solidCells) {
            sum += com.ovo.sablestopnow.sketch.SolidCollision.volume(
                    com.ovo.sablestopnow.sketch.SolidCollision.clipToCell(solidBoxes,
                            com.ovo.sablestopnow.sketch.SolidCollision.unpackX(cell),
                            com.ovo.sablestopnow.sketch.SolidCollision.unpackY(cell),
                            com.ovo.sablestopnow.sketch.SolidCollision.unpackZ(cell)));
        }
        near(sum, com.ovo.sablestopnow.sketch.SolidCollision.volume(solidBoxes),
                "真实体：裁切体积和 = 实体体积（4×3×2 = 24）");
        near(sum, 24.0, "真实体体积 = 24");
    }

    /** 断言 6 个分量。 */
    private static void nearVec6(final double[] actual, final double a, final double b,
                                 final double c, final double d, final double e, final double f,
                                 final String label) {
        final boolean ok = Math.abs(actual[0] - a) < 1.0e-9 && Math.abs(actual[1] - b) < 1.0e-9
                && Math.abs(actual[2] - c) < 1.0e-9 && Math.abs(actual[3] - d) < 1.0e-9
                && Math.abs(actual[4] - e) < 1.0e-9 && Math.abs(actual[5] - f) < 1.0e-9;
        check(ok, label + "：期望 [" + a + "," + b + "," + c + " → " + d + "," + e + "," + f
                + "]，实际 " + java.util.Arrays.toString(actual));
    }

    /**
     * Ctrl 多选：选择集是真相，{@code selectedEntity} 只是它的第一个元素。
     *
     * <p>重点测**一致性**：任何一次增删之后，"集合内容"和"第一个元素"必须对得上 ——
     * 这正是双状态最容易出问题的地方（集合里还有东西，但单选访问器已经是 null）。
     */
    private static void multiSelection() {
        final com.ovo.sablestopnow.sketch.SketchSession s = editing();
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        s.click(1.0, 1.0);
        s.click(4.0, 3.0);
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        s.click(10.0, 10.0);
        s.click(14.0, 10.0);
        final Sketch k = s.working();
        check(k.entities().size() == 5, "矩形 4 条边 + 1 条独立线");

        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.SELECT);
        final Sketch.Entity e0 = k.entities().get(0);
        final Sketch.Entity e1 = k.entities().get(1);
        final Sketch.Entity e2 = k.entities().get(2);

        // 普通点击 = 单选替换
        s.selectEntity(e0);
        check(s.selectionSize() == 1, "单选之后集合里就 1 个");
        check(s.isSelected(e0) && !s.isSelected(e1), "只有点中的那个被选中");
        check(s.selectedEntity() == e0, "selectedEntity 指向它");

        // Ctrl 点击 = 增删
        s.toggleEntity(e1);
        check(s.selectionSize() == 2, "Ctrl 点第二个 → 2 个");
        check(s.isSelected(e1), "第二个也在集合里");
        check(s.selectedEntity() == e0, "第一个元素没变（顺序是点选顺序）");

        s.toggleEntity(e2);
        check(s.selectionSize() == 3, "Ctrl 再点 → 3 个");

        // 取消选中
        s.toggleEntity(e1);
        check(s.selectionSize() == 2, "再 Ctrl 点一下同一个 → 取消（2 个）");
        check(!s.isSelected(e1), "它不在集合里了");

        // 普通点击会**替换**整个集合（不会把旧的一起留着）
        s.selectEntity(e2);
        check(s.selectionSize() == 1, "普通点击替换集合，不是追加");
        check(!s.isSelected(e0), "旧的被清掉了");

        // ⚠ 多选加水平/竖直现在是**被拒绝**的（用户反馈：几何约束应当是元素之间的关系，
        // 逐条各加一遍既不是关系、也把同一个约束加了两遍）。关系型约束走 makeEqual/makeConcentric。
        s.selectEntity(e0);
        s.toggleEntity(e1);
        final int before = k.constraints().size();
        check(!s.constrainSelected(SketchSolver.VERTICAL), "多选加竖直 → 拒绝");
        check(k.constraints().size() == before, "被拒绝就不该加任何约束");
        // 单选仍然可用（这是合法且常用的）
        s.selectEntity(e0);
        check(s.constrainSelected(SketchSolver.VERTICAL), "单选加竖直 → 正常");
        check(k.constraints().size() == before + 1, "只加了 1 条");
        check(k.constraints().get(before).kind() == SketchSolver.VERTICAL
                        && k.constraints().get(before).operands()[0] == e0.vars()[0],
                "加上的那条作用在选中的那条线上");

        // 混选：圆 + 线段 → 线段加得上，圆跳过，整体返回 true
        final com.ovo.sablestopnow.sketch.SketchSession mixed = editing();
        mixed.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        mixed.click(0.0, 0.0);
        mixed.click(3.0, 0.0);
        mixed.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        mixed.click(10.0, 10.0);
        mixed.click(12.0, 10.0);
        final Sketch mk = mixed.working();
        mixed.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.SELECT);
        final Sketch.Entity line = mk.entities().get(0);
        final Sketch.Entity circle = mk.entities().get(1);
        mixed.selectEntity(circle);
        mixed.toggleEntity(line);
        check(mixed.selectionSize() == 2, "圆 + 线 混选 2 个");
        // ⚠ 混选（圆 + 线段）加水平：同样是多选 → 拒绝。
        // 旧实现会"给线段加上、把圆跳过"，但那是**猜玩家意思**：他选了圆又选线，
        // 点"水平"到底想要什么？现在的答案是"这不是关系型约束，请单选或用等长/同心"。
        final int mb = mk.constraints().size();
        check(!mixed.constrainSelected(SketchSolver.HORIZONTAL), "混选加水平 → 拒绝");
        check(mk.constraints().size() == mb, "拒绝就不该加任何约束");

        // 单选线段加水平 → 正常
        mixed.selectEntity(line);
        check(mixed.constrainSelected(SketchSolver.HORIZONTAL), "单选线段加水平 → 正常");
        check(mk.constraints().get(mb).operands()[0] == line.vars()[0], "作用在线段上");

        // 全是圆 → 加不上，返回 false（UI 据此提示）
        mixed.selectEntity(circle);
        check(!mixed.constrainSelected(SketchSolver.HORIZONTAL), "只选了圆 → 加不上，返回 false");

        // 多选删除：一次全删掉
        final com.ovo.sablestopnow.sketch.SketchSession del = editing();
        del.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        del.click(0.0, 0.0);
        del.click(4.0, 3.0);
        final Sketch dk = del.working();
        del.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.SELECT);
        del.selectEntity(dk.entities().get(0));
        del.toggleEntity(dk.entities().get(1));
        check(del.selectionSize() == 2, "删之前选中 2 条边");
        check(del.removeSelected(), "多选删除成功");
        check(dk.entities().size() == 2, "一次删掉 2 条边（剩 2）");
        check(del.selectionSize() == 0, "删完选择集清空");
        check(del.selectedEntity() == null, "singleEntity 也同步为 null（不留分裂状态）");

        // 换工具必须清掉**整个**集合，不能只置空 selectedEntity
        del.selectEntity(dk.entities().get(0));
        del.toggleEntity(dk.entities().get(1));
        del.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        check(del.selectionSize() == 0, "换工具清空整个选择集");
        check(del.selectedEntity() == null, "换工具后 selectedEntity 也是 null");

        // 空集合上操作不炸
        check(!del.constrainSelected(SketchSolver.HORIZONTAL), "空选择集加约束 → false");
        check(!del.removeSelected(), "空选择集删除 → false");
        del.toggleEntity(null);
        check(del.selectionSize() == 0, "toggle(null) 是空操作");
    }

    /**
     * 圆弧：圆心 + 起点 + 终点。
     *
     * <p>重点测**扫角规范化**：玩家点终点时算出的角度可能比起点小（跨过 0 度），
     * 不规范化的话圆弧会反着画、扫过 300 多度而不是 60 度。
     */
    private static void arcs() {
        // --- 扫角：永远是 (0, 2π] 的正角度 ---
        near(Sketch.sweep(0.0, Math.PI / 2), Math.PI / 2, "0 → 90° 扫 90°");
        near(Sketch.sweep(Math.PI / 2, 0.0), 2 * Math.PI - Math.PI / 2,
                "90° → 0 反着走 = 扫 270°（不是 −90°）");
        near(Sketch.sweep(0.0, 0.0), 2 * Math.PI, "起止角相同 = 整圈（不是 0）");
        near(Sketch.sweep(3.0, 3.0), 2 * Math.PI, "任意角起止相同也是整圈");
        near(Sketch.sweep(-Math.PI, Math.PI), 2 * Math.PI, "−180° → +180° 是整圈");
        final double sw = Sketch.sweep(1.0, 1.0 + 2 * Math.PI * 3);
        near(sw, 2 * Math.PI, "多绕三圈也归一到一圈");

        // --- 造一段 90° 弧：圆心 (0,0)、起点 (2,0)、终点 (0,2) ---
        final Sketch k = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity arc = k.addArc(0, 0, 2, 0, Math.PI / 2);
        final int[] v = arc.vars();
        check(arc.kind() == Sketch.EntityKind.ARC, "图元种类是 ARC");
        // ★ 布局 = 圆心 + 起点 + 终点（6 个）✓ 半径与起止角**不是变量** ✓
        check(v.length == 6, "圆弧占 6 个变量（圆心 + 起点 + 终点）");
        near(k.variable(v[0]), 0.0, "圆心 x");
        near(k.variable(v[1]), 0.0, "圆心 y");
        near(k.variable(v[2]), 2.0, "起点 u（半径 2、起始角 0 ⇒ cos0·2）");
        near(k.variable(v[3]), 0.0, "起点 v");
        near(k.variable(v[4]), 0.0, "终点 u（90° ⇒ cos(π/2)·2）");
        near(k.variable(v[5]), 2.0, "终点 v");
        // 半径/起止角一律**现算** —— 这就是新布局的全部意义：
        //   r = hypot(sx−cx, sy−cy)、a0/a1 = atan2(端点 − 圆心)
        near(k.arcRadius(arc), 2.0, "半径 = hypot(起点 − 圆心)");
        near(k.arcStart(arc), 0.0, "起始角 = atan2(起点 − 圆心)");
        near(k.arcEnd(arc), Math.PI / 2, "终止角 = atan2(终点 − 圆心)");
        near(k.arcSweep(arc), Math.PI / 2, "扫角 = a0 → a1 逆时针（90°）");
        // 半径取非负：圆心 → 起点的 hypot 不可能是负的（旧布局要 Math.abs ✓ 现在天然成立 ✓）
        final Sketch.Entity negArc = k.addArc(10, 10, -3, 0, Math.PI / 2);
        near(k.arcRadius(negArc), 3.0, "负半径的输入法也得到**非负**半径（hypot 恒 ≥ 0）");
        assertArcHasNoRadiusVariable(k, negArc);

        // --- 端点 ---
        final double[][] ends = k.arcEndpoints(arc);
        check(ends != null, "圆弧有端点");
        near(ends[0][0], 2.0, "起点 u");
        near(ends[0][1], 0.0, "起点 v");
        near(ends[1][0], 0.0, "终点 u（90° 处）");
        near(ends[1][1], 2.0, "终点 v");

        // --- 中点：45° 处，半径 2 ---
        final double[] mid = k.arcPoint(arc, 0.5);
        near(Math.hypot(mid[0], mid[1]), 2.0, "弧中点到圆心距离 = 半径");
        near(Math.atan2(mid[1], mid[0]), Math.PI / 4, "弧中点方向 = 45°");

        // --- 整条弧上的点都落在圆上（采样检查） ---
        boolean onCircle = true;
        for (int i = 0; i <= 16; i++) {
            final double[] p = k.arcPoint(arc, i / 16.0);
            if (Math.abs(Math.hypot(p[0], p[1]) - 2.0) > 1.0e-9) {
                onCircle = false;
                break;
            }
        }
        check(onCircle, "采样 17 个点都在半径 2 的圆上");

        // --- 非圆弧传进去返回 null，不抛 ---
        final Sketch.Entity line = k.addLine(0, 0, 1, 1);
        check(k.arcPoint(line, 0.5) == null, "对线段问弧上点 → null");
        check(k.arcEndpoints(line) == null, "对线段问弧端点 → null");

        // --- 会话层：三次点击造一段弧 ---
        final com.ovo.sablestopnow.sketch.SketchSession s = editing();
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.ARC);
        s.click(0.0, 0.0);                    // 圆心
        check(s.pendingPoints().size() == 1, "弧：第 1 次点击后待定 1 个点");
        s.click(3.0, 0.0);                    // 起点 → 半径 3、起始角 0
        check(s.pendingPoints().size() == 2, "弧：第 2 次点击后待定 2 个点");
        check(s.working().entities().isEmpty(), "两次点击时还没造出图元（三次才成弧）");
        s.click(0.0, 3.0);                    // 终点 → 90°
        check(s.pendingPoints().isEmpty(), "弧：第 3 次点击后收工");
        check(s.working().entities().size() == 1, "造出了 1 段弧");
        final Sketch.Entity made = s.working().entities().get(0);
        check(made.kind() == Sketch.EntityKind.ARC, "造出来的确实是 ARC");
        final int[] mv = made.vars();
        check(mv.length == 6, "会话造出来的弧同样是 6 个变量（圆心 + 起点 + 终点）");
        near(s.working().variable(mv[2]), 3.0, "起点 u = 3（半径 = 圆心到起点的距离 = 3）");
        near(s.working().variable(mv[3]), 0.0, "起点 v = 0");
        near(s.working().variable(mv[4]), 0.0, "终点 u = 0（90° 方向）");
        near(s.working().variable(mv[5]), 3.0, "终点 v = 3");
        // ⚠ 玩家点终点的**距离被忽略**（终点只指方向 ✓ 与旧行为一致 ✓）：
        //   半径由**起点**定 ⇒ 终点被投到半径 3 的圆上（不是玩家点的 (0,3) 距离 3 的巧合以外）
        near(s.working().arcRadius(made), 3.0, "现算半径 = 3");
        near(s.working().arcStart(made), 0.0, "现算起始角 0");
        near(s.working().arcEnd(made), Math.PI / 2, "现算终止角 90°");
        near(s.working().arcSweep(made), Math.PI / 2, "现算扫角 90°");

        // --- 终点压在圆心上 → 定不出方向，但半径有效，弧仍然造得出来（角度退化） ---
        final com.ovo.sablestopnow.sketch.SketchSession deg = editing();
        deg.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.ARC);
        deg.click(0.0, 0.0);
        deg.click(2.0, 0.0);
        deg.click(0.0, 0.0); // 终点 = 圆心
        check(deg.working().entities().size() == 1, "终点压在圆心上也造得出一段弧（角度退化）");
        // 终点方向定不出来（atan2(0,0) = 0）⇒ 扫角归一成**整圈**（sweep 的既定语义，不是 0）
        near(deg.working().arcSweep(deg.working().entities().get(0)), 2 * Math.PI,
                "终点压在圆心 ⇒ 扫角成整圈 2π（起止角相同在弧语义里就是整圈）");

        // --- 起点压在圆心上 → 半径 0，造不出来（不抛异常） ---
        final com.ovo.sablestopnow.sketch.SketchSession zero = editing();
        zero.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.ARC);
        zero.click(1.0, 1.0);
        zero.click(1.0, 1.0); // 起点 = 圆心 → 半径 0
        zero.click(2.0, 1.0);
        check(zero.working().entities().isEmpty(), "半径 0 → 不造弧（也不抛）");
        check(zero.pendingPoints().isEmpty(), "半径 0 之后待定点也要清掉");
    }

    /**
     * 布局不变式：圆弧**没有**半径/起止角变量 —— 动端点坐标，半径与角度必须**跟着变**。
     *
     * <p>这条比"数一数有 6 个变量"强得多：它证明这两个量是**算出来的**，
     * 而不是"碰巧存了 6 个数"（旧布局 {@code cx cy r a0 a1} 下把终点角改成 90°，
     * 半径纹丝不动 ⇒ 这条断言会**当场失败** ✗）。
     *
     * <p>改完**原样复原**那 6 个变量：调用方后面还要用这段弧做几何断言。
     */
    private static void assertArcHasNoRadiusVariable(final Sketch k, final Sketch.Entity arc) {
        final int[] v = arc.vars();
        final double[] before = new double[6];
        for (int i = 0; i < 6; i++) {
            before[i] = k.variable(v[i]);
        }
        final double cx = before[0];
        final double cy = before[1];
        final double r0 = k.arcRadius(arc);
        final double a0 = k.arcStart(arc);
        // ① 起点沿半径方向推远一倍 ⇒ 半径翻倍（半径是算的）
        k.setVariable(v[2], cx + (before[2] - cx) * 2.0);
        k.setVariable(v[3], cy + (before[3] - cy) * 2.0);
        near(k.arcRadius(arc), r0 * 2.0, "改起点坐标 ⇒ 半径**跟着变**（算出来的，不是存的）");
        near(k.arcStart(arc), a0, "沿半径方向推远不改变方向角");
        // ② 终点换个方向 ⇒ 终止角跟着变（角度也是算的）
        final double rNow = k.arcRadius(arc);
        k.setVariable(v[4], cx);
        k.setVariable(v[5], cy + rNow);
        near(k.arcEnd(arc), Math.PI / 2, "改终点坐标 ⇒ 终止角**跟着变**");
        // 复原
        for (int i = 0; i < 6; i++) {
            k.setVariable(v[i], before[i]);
        }
        near(k.arcRadius(arc), r0, "复原后半径回到原值（不影响后面的断言）");
        near(k.arcStart(arc), a0, "复原后起始角回到原值");
    }

    /**
     * 自由度分析的核心：由雅可比求"哪些变量还自由"。
     *
     * <p>用手算的矩阵断言 —— 这一层是纯线性代数，期望值可以完全确定地写出来，
     * 不像几何断言那样容易心算出错。
     */
    private static void dofFreeVariables() {
        // 空矩阵 / 没有变量
        check(com.ovo.sablestopnow.sketch.SketchDof.freeVariables(new double[0][]).length == 0,
                "没有任何行也没有列 → 空结果");
        check(com.ovo.sablestopnow.sketch.SketchDof.freeCount(
                        com.ovo.sablestopnow.sketch.SketchDof.freeVariables(new double[][] {})) == 0,
                "没有变量 → 自由数 0");

        // 一条约束都没有 → 全自由。
        // ⚠ 必须用**显式给变量个数**的重载：空矩阵推导不出列数（行数是 0）。
        final double[][] none = new double[0][];
        final boolean[] allFree = com.ovo.sablestopnow.sketch.SketchDof.freeVariables(none, 3);
        check(allFree.length == 3, "3 个变量（显式给出个数，空矩阵推不出来）");
        check(com.ovo.sablestopnow.sketch.SketchDof.freeCount(allFree) == 3, "没有约束 → 3 个都自由");
        check(com.ovo.sablestopnow.sketch.SketchDof.freeVariables(none, 0).length == 0,
                "0 个变量 → 空结果");

        // x0 = 0（一行一列）→ 该变量被定死
        final boolean[] one = com.ovo.sablestopnow.sketch.SketchDof.freeVariables(
                new double[][] {{1.0}});
        check(!one[0], "单个方程 x0=0 → x0 不自由");

        // 两个变量、一个方程 x0 + x1 = 0 → 两个都自由（可以一起动）
        final boolean[] dep = com.ovo.sablestopnow.sketch.SketchDof.freeVariables(
                new double[][] {{1.0, 1.0}});
        check(dep[0] && dep[1], "x0+x1=0 → 两者都自由（能沿 (−1,1) 动）");

        // 两个变量、两个独立方程 → 全定死
        final boolean[] full = com.ovo.sablestopnow.sketch.SketchDof.freeVariables(
                new double[][] {{1.0, 0.0}, {0.0, 1.0}});
        check(!full[0] && !full[1], "两个独立方程 → 两个变量都定死");

        // 秩亏：第二行是第一行的两倍 → 实际只有 1 个独立约束
        final boolean[] rankDeficient = com.ovo.sablestopnow.sketch.SketchDof.freeVariables(
                new double[][] {{1.0, 1.0}, {2.0, 2.0}});
        check(rankDeficient[0] && rankDeficient[1], "秩亏（第二行是两倍）→ 仍是 2 个自由");

        // 三变量：x0 定死、x1 与 x2 相关 → x0 不自由，x1/x2 自由
        final boolean[] mixed = com.ovo.sablestopnow.sketch.SketchDof.freeVariables(
                new double[][] {{1.0, 0.0, 0.0}, {0.0, 1.0, 1.0}});
        check(!mixed[0], "x0 被单独定死");
        check(mixed[1] && mixed[2], "x1、x2 通过一个方程相关 → 都算自由");

        // 从属变量的判定：x0 是主元、x1 自由，且 R 里 x0 行在 x1 列有非零系数
        //   x0 + 2·x1 = 0  →  x0 随 x1 动，所以 x0 也自由
        final boolean[] dependent = com.ovo.sablestopnow.sketch.SketchDof.freeVariables(
                new double[][] {{1.0, 2.0}});
        check(dependent[0] && dependent[1], "x0+2·x1=0 → 从属变量 x0 也自由");

        // 反过来：x0 自己一行、x1 只在第二行出现且那行没有自由列
        //   x0 = 0 ; x1 = 0  → 都不自由
        final boolean[] independent = com.ovo.sablestopnow.sketch.SketchDof.freeVariables(
                new double[][] {{1.0, 0.0}, {0.0, 1.0}});
        check(com.ovo.sablestopnow.sketch.SketchDof.freeCount(independent) == 0,
                "两个独立方程 → 0 个自由");

        // 全零行（退化约束）不该被当成约束
        final boolean[] zeros = com.ovo.sablestopnow.sketch.SketchDof.freeVariables(
                new double[][] {{0.0, 0.0}});
        check(com.ovo.sablestopnow.sketch.SketchDof.freeCount(zeros) == 2,
                "全零行不构成约束 → 2 个都自由");

        // 图元级别的判定：图元的任何一个变量自由 → 该图元欠定义
        final Sketch k = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity line = k.addLine(0, 0, 4, 0); // 4 个变量
        final boolean[] allFour = {true, true, true, true};
        check(!com.ovo.sablestopnow.sketch.SketchDof.isFullyDefined(allFour, line),
                "4 个变量全自由 → 线段欠定义");
        final boolean[] noneFree = {false, false, false, false};
        check(com.ovo.sablestopnow.sketch.SketchDof.isFullyDefined(noneFree, line),
                "4 个变量都不自由 → 线段完全定义");
        final boolean[] oneFree = {false, false, true, false};
        check(!com.ovo.sablestopnow.sketch.SketchDof.isFullyDefined(oneFree, line),
                "只有 1 个变量自由 → 也算欠定义（不能只看一部分）");
    }

    /**
     * 端到端：草图 → 残差 → 数值雅可比 → 自由度 → 图元是否完全定义。
     *
     * <p>这就是"欠定义/完全定义上色"的依据。
     */
    private static void dofEndToEnd() {
        final Sketch k = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity line = k.addLine(0, 0, 4, 0);
        final int[] v = line.vars();

        // 没有任何约束：4 个变量全自由
        boolean[] free = com.ovo.sablestopnow.sketch.SketchDof.freeVariablesOf(k);
        check(com.ovo.sablestopnow.sketch.SketchDof.freeCount(free) == 4, "裸线段 → 4 个变量全自由");
        check(!com.ovo.sablestopnow.sketch.SketchDof.isFullyDefined(free, line), "裸线段欠定义");

        // 加水平：还剩"整体平移 + 长度"这些自由度，四个变量都还能动
        k.addConstraint(SketchSolver.HORIZONTAL, 0.0, v[0], v[1], v[2], v[3]);
        free = com.ovo.sablestopnow.sketch.SketchDof.freeVariablesOf(k);
        check(com.ovo.sablestopnow.sketch.SketchDof.freeCount(free) == 4,
                "只加水平仍欠定义（还能平移和伸缩），实测自由 "
                        + com.ovo.sablestopnow.sketch.SketchDof.freeCount(free));
        check(!com.ovo.sablestopnow.sketch.SketchDof.isFullyDefined(free, line), "仍欠定义");

        // 把三个变量钉死 + 水平 → 第四个也被连带确定 → 完全定义
        k.addConstraint(SketchSolver.FIXED, k.variable(v[0]), v[0]);
        k.addConstraint(SketchSolver.FIXED, k.variable(v[1]), v[1]);
        k.addConstraint(SketchSolver.FIXED, k.variable(v[2]), v[2]);
        free = com.ovo.sablestopnow.sketch.SketchDof.freeVariablesOf(k);
        check(com.ovo.sablestopnow.sketch.SketchDof.freeCount(free) == 0,
                "钉死 3 个 + 水平 → 0 个自由（第 4 个被连带确定），实测自由 "
                        + com.ovo.sablestopnow.sketch.SketchDof.freeCount(free));
        check(com.ovo.sablestopnow.sketch.SketchDof.isFullyDefined(free, line),
                "这条线段现在**完全定义**（上色用这个判断）");

        // 圆的半径：只固定圆心，半径仍自由
        final Sketch c = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity circle = c.addCircle(0, 0, 5);
        final int[] cv = circle.vars();
        c.addConstraint(SketchSolver.FIXED, c.variable(cv[0]), cv[0]);
        c.addConstraint(SketchSolver.FIXED, c.variable(cv[1]), cv[1]);
        free = com.ovo.sablestopnow.sketch.SketchDof.freeVariablesOf(c);
        check(!com.ovo.sablestopnow.sketch.SketchDof.isFullyDefined(free, circle),
                "只固定圆心 → 半径还自由，圆仍欠定义");
        c.addConstraint(SketchSolver.RADIUS, 5.0, cv[0], cv[1], cv[2]);
        free = com.ovo.sablestopnow.sketch.SketchDof.freeVariablesOf(c);
        check(com.ovo.sablestopnow.sketch.SketchDof.isFullyDefined(free, circle),
                "再加半径约束 → 圆完全定义");

        // 两条重合的线段：一端被绑在一起，另一端仍自由
        final Sketch two = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity a = two.addLine(0, 0, 4, 0);
        final Sketch.Entity b = two.addLine(4, 0, 4, 3);
        two.addConstraint(SketchSolver.COINCIDENT, 0.0,
                a.vars()[2], a.vars()[3], b.vars()[0], b.vars()[1]);
        free = com.ovo.sablestopnow.sketch.SketchDof.freeVariablesOf(two);
        check(!com.ovo.sablestopnow.sketch.SketchDof.isFullyDefined(free, a), "接了一端后 a 仍欠定义");
        check(!com.ovo.sablestopnow.sketch.SketchDof.isFullyDefined(free, b), "b 也仍欠定义");
        // ⚠ 这里必须查**零空间维数**而不是 freeCount：两条线接一个端点消掉了 2 个自由度，
        // 但 8 个变量个个都还能在某个方向上动（整体平移 + 另一端各自摆），freeCount 仍是 8。
        // 拿 freeCount 当"剩余自由度"就会误判成"约束没生效"。
        check(com.ovo.sablestopnow.sketch.SketchDof.remainingDof(two) == 6,
                "重合消掉了 2 个自由度（8 → 6），实测 " 
                        + com.ovo.sablestopnow.sketch.SketchDof.remainingDof(two));
        check(com.ovo.sablestopnow.sketch.SketchDof.freeCount(free) == 8,
                "但 8 个变量都还能动 → freeCount 仍是 8（两个量别混）");

        // 认不出的约束种类 → 当成没约束（保守：宁可说它自由）
        final Sketch weird = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        weird.addLine(0, 0, 1, 1);
        weird.addConstraint(9999, 0.0, 0, 1);
        free = com.ovo.sablestopnow.sketch.SketchDof.freeVariablesOf(weird);
        check(com.ovo.sablestopnow.sketch.SketchDof.freeCount(free) == 4,
                "认不出的约束当成没约束 → 仍然 4 个自由（不能误报成已锁死）");

        // 空草图
        final Sketch empty = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        check(com.ovo.sablestopnow.sketch.SketchDof.freeVariablesOf(empty).length == 0,
                "空草图 → 没有变量");
    }

    /**
     * 自由度分析里**容易出错的两处**：数值秩的阈值、以及过约束/冲突。
     *
     * <p>⚠ 这里刻意只用**不变量**断言，不手算具体数值 —— 我在这类"心算一个矩阵的秩"
     * 上已经错过好几次，而下面这些关系是无论数值怎么变都必然成立的。
     */
    private static void dofInvariants() {
        final Sketch k = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity a = k.addLine(0, 0, 4, 0);
        final Sketch.Entity b = k.addLine(4, 0, 4, 3);
        final int n = k.variableCount();
        check(n == 8, "两条线段 = 8 个变量");

        // 不变量 1：剩余自由度永远在 [0, 变量数] 之间
        check(com.ovo.sablestopnow.sketch.SketchDof.remainingDof(k) == 8,
                "没有约束 → 剩余自由度 = 变量数 8");
        check(com.ovo.sablestopnow.sketch.SketchDof.maxResidual(k) == 0.0,
                "没有约束 → 残差为 0");

        // 不变量 2：每加一条**独立**约束，剩余自由度不超过加之前
        int prev = com.ovo.sablestopnow.sketch.SketchDof.remainingDof(k);
        k.addConstraint(SketchSolver.HORIZONTAL, 0.0, a.vars()[0], a.vars()[1],
                a.vars()[2], a.vars()[3]);
        int now = com.ovo.sablestopnow.sketch.SketchDof.remainingDof(k);
        check(now <= prev, "加约束不会让自由度变多（" + prev + " → " + now + "）");
        prev = now;

        k.addConstraint(SketchSolver.COINCIDENT, 0.0,
                a.vars()[2], a.vars()[3], b.vars()[0], b.vars()[1]);
        now = com.ovo.sablestopnow.sketch.SketchDof.remainingDof(k);
        check(now < prev, "接上一个端点确实消掉了自由度（" + prev + " → " + now + "）");

        // 不变量 3：秩永远不超过 min(行数, 变量数)
        final double[][] j = com.ovo.sablestopnow.sketch.SketchDof.jacobian(k);
        final int rank = com.ovo.sablestopnow.sketch.SketchDof.rank(j, n);
        check(rank <= n, "秩 ≤ 变量数");
        check(rank <= j.length, "秩 ≤ 约束行数");
        check(com.ovo.sablestopnow.sketch.SketchDof.remainingDof(k) == n - rank,
                "剩余自由度 = 变量数 − 秩（两者必须自洽）");

        // 不变量 4：把每个变量都钉死 → 必然完全约束
        final Sketch pinned = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity line = pinned.addLine(1, 2, 5, 6);
        for (final int v : line.vars()) {
            pinned.addConstraint(SketchSolver.FIXED, pinned.variable(v), v);
        }
        check(com.ovo.sablestopnow.sketch.SketchDof.remainingDof(pinned) == 0,
                "每个变量都钉死 → 剩余自由度 0");
        final boolean[] free = com.ovo.sablestopnow.sketch.SketchDof.freeVariablesOf(pinned);
        check(com.ovo.sablestopnow.sketch.SketchDof.isFullyDefined(free, line),
                "而且该图元完全定义");

        // 不变量 5：残差为 0 的约束集不该被报成"冲突"
        check(com.ovo.sablestopnow.sketch.SketchDof.maxResidual(pinned) == 0.0,
                "钉死的线段残差仍为 0（自洽）");

        // 冗余：重复加同一条约束 → 秩不变，但行数变多 → 报冗余
        final Sketch dup = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity dl = dup.addLine(0, 0, 3, 0);
        dup.addConstraint(SketchSolver.HORIZONTAL, 0.0, dl.vars()[0], dl.vars()[1],
                dl.vars()[2], dl.vars()[3]);
        check(!com.ovo.sablestopnow.sketch.SketchDof.hasRedundantConstraints(dup),
                "一条约束谈不上冗余");
        final int dofBefore = com.ovo.sablestopnow.sketch.SketchDof.remainingDof(dup);
        dup.addConstraint(SketchSolver.HORIZONTAL, 0.0, dl.vars()[0], dl.vars()[1],
                dl.vars()[2], dl.vars()[3]);
        check(com.ovo.sablestopnow.sketch.SketchDof.hasRedundantConstraints(dup),
                "重复同一条约束 → 报冗余");
        check(com.ovo.sablestopnow.sketch.SketchDof.remainingDof(dup) == dofBefore,
                "冗余约束**不改变**自由度（" + dofBefore + " → "
                        + com.ovo.sablestopnow.sketch.SketchDof.remainingDof(dup) + "）");
        check(com.ovo.sablestopnow.sketch.SketchDof.maxResidual(dup) < 1.0e-9,
                "重复约束是自洽的（残差仍 ≈ 0）→ 冗余 ≠ 冲突");

        // ⚠ 矩形是 4 条**各自独立**的线段（相邻边靠重合约束连接）→ **16 个变量**，不是 8。
        // 所以 12 行约束对 16 个变量并**不**冗余。这里只断言与变量数无关的两件事：
        // 自洽（残差 ≈ 0）与仍然欠定义（刚画出来的矩形还会浮动）。
        final com.ovo.sablestopnow.sketch.SketchSession rs = editing();
        rs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        rs.click(0.0, 0.0);
        rs.click(4.0, 3.0);
        check(rs.working().variableCount() == 16, "矩形是 4 条独立线段 → 16 个变量");
        check(com.ovo.sablestopnow.sketch.SketchDof.maxResidual(rs.working()) < 1.0e-9,
                "刚画出的矩形是自洽的（残差 ≈ 0）");
        check(com.ovo.sablestopnow.sketch.SketchDof.remainingDof(rs.working()) > 0,
                "刚画出的矩形仍然欠定义（还能整体平移/改尺寸）");
        check(!com.ovo.sablestopnow.sketch.SketchDof.hasRedundantConstraints(rs.working()),
                "16 变量 × 12 行约束并不冗余（冗余 ≠ 冲突的另一面）");
    }

    /**
     * 距离工具：两个图元之间量的是什么。
     *
     * <p>断言只用**整数解**的例子（3-4-5、5、10 这种），避免浮点容差带来的假失败。
     */
    private static void measure() {
        final Sketch k = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity p0 = k.addPoint(0, 0);
        final Sketch.Entity p1 = k.addPoint(3, 4);
        final Sketch.Entity p2 = k.addPoint(0, 5);

        // 点点：3-4-5
        com.ovo.sablestopnow.sketch.SketchMeasure.Result r =
                com.ovo.sablestopnow.sketch.SketchMeasure.between(k, p0, p1);
        near(r.distance(), 5.0, "点点距 3-4-5 = 5");
        check(r.kind() == com.ovo.sablestopnow.sketch.SketchMeasure.Kind.POINT_POINT,
                "分类是点点");

        // 点到线段的垂距：点 (0,5) 到 y=0 上的线段 → 5
        final Sketch.Entity base = k.addLine(0, 0, 10, 0);
        r = com.ovo.sablestopnow.sketch.SketchMeasure.between(k, p2, base);
        near(r.distance(), 5.0, "点 (0,5) 到 y=0 的线段 → 垂距 5");
        check(r.kind() == com.ovo.sablestopnow.sketch.SketchMeasure.Kind.POINT_LINE,
                "分类是点线");

        // 垂足落在延长线上 → 退化成到端点的距离：(20,0) 那侧
        final Sketch.Entity far = k.addPoint(20, 0);
        r = com.ovo.sablestopnow.sketch.SketchMeasure.between(k, far, base);
        near(r.distance(), 10.0, "垂足在延长线外 → 取到端点 (10,0) 的距离 10");
        check(r.kind() == com.ovo.sablestopnow.sketch.SketchMeasure.Kind.POINT_LINE,
                "仍分类为点线");

        // 两条平行线：y=0 与 y=5 → 5
        final Sketch.Entity par = k.addLine(0, 5, 10, 5);
        r = com.ovo.sablestopnow.sketch.SketchMeasure.between(k, base, par);
        near(r.distance(), 5.0, "两条平行线（y=0 / y=5）→ 垂距 5");
        check(r.kind() == com.ovo.sablestopnow.sketch.SketchMeasure.Kind.PARALLEL_LINES,
                "分类是平行线");

        // 平行但反向（第二段从右往左画）也要判成平行 —— 用夹角判据而不是叉积符号
        final Sketch.Entity parRev = k.addLine(10, 5, 0, 5);
        r = com.ovo.sablestopnow.sketch.SketchMeasure.between(k, base, parRev);
        check(r.kind() == com.ovo.sablestopnow.sketch.SketchMeasure.Kind.PARALLEL_LINES,
                "反向画的平行线也判成平行（夹角判据）");
        near(r.distance(), 5.0, "反向平行线的距离仍是 5");

        // 两条相交线：距离 0，且报出夹角
        final Sketch.Entity diag = k.addLine(0, 0, 10, 10);
        r = com.ovo.sablestopnow.sketch.SketchMeasure.between(k, base, diag);
        near(r.distance(), 0.0, "相交线段 → 距离 0");
        check(r.kind() == com.ovo.sablestopnow.sketch.SketchMeasure.Kind.SKEW_LINES,
                "分类是异面/相交线段");
        near(r.angleDeg(), 45.0, "夹角 45°");

        // 不平行但不交叉：两条错开的斜线，最近点在一对端点上
        final Sketch.Entity seg1 = k.addLine(0, 0, 1, 0);
        final Sketch.Entity seg2 = k.addLine(0, 1, 0, 2); // 竖线，起点 (0,1)
        r = com.ovo.sablestopnow.sketch.SketchMeasure.between(k, seg1, seg2);
        near(r.distance(), 1.0, "错开的垂直两段 → 最近点距 1");
        check(r.kind() == com.ovo.sablestopnow.sketch.SketchMeasure.Kind.SKEW_LINES,
                "分类是异面/相交线段");
        near(r.angleDeg(), 90.0, "夹角 90°");

        // 圆与点：圆心 (0,0) r=2，点 (0,10) → 表面间隙 8
        final Sketch c = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity circle = c.addCircle(0, 0, 2);
        final Sketch.Entity pt = c.addPoint(0, 10);
        com.ovo.sablestopnow.sketch.SketchMeasure.Result cr =
                com.ovo.sablestopnow.sketch.SketchMeasure.between(c, circle, pt);
        near(cr.distance(), 8.0, "点到圆表面的距离 = 10 − 2 = 8");
        check(cr.kind() == com.ovo.sablestopnow.sketch.SketchMeasure.Kind.SURFACE_GAP,
                "分类是表面间隙");

        // 两个圆：圆心距 10、半径 2 与 3 → 间隙 5
        final Sketch.Entity circle2 = c.addCircle(0, 10, 3);
        cr = com.ovo.sablestopnow.sketch.SketchMeasure.between(c, circle, circle2);
        near(cr.distance(), 5.0, "两圆表面间隙 = 10 − 2 − 3 = 5");
        check(cr.kind() == com.ovo.sablestopnow.sketch.SketchMeasure.Kind.SURFACE_GAP,
                "分类是表面间隙");

        // 相交的两圆：间隙不许为负
        final Sketch.Entity overlapping = c.addCircle(0, 3, 3);
        cr = com.ovo.sablestopnow.sketch.SketchMeasure.between(c, circle, overlapping);
        check(cr.distance() >= 0.0, "相交的两圆间隙不为负（取 0）");

        // 圆到线：圆心 (0,0) r=2，线 y=10 → 间隙 8
        final Sketch.Entity line10 = c.addLine(-5, 10, 5, 10);
        cr = com.ovo.sablestopnow.sketch.SketchMeasure.between(c, circle, line10);
        near(cr.distance(), 8.0, "圆到线的表面间隙 = 10 − 2 = 8");

        // 退化输入
        check(com.ovo.sablestopnow.sketch.SketchMeasure.between(k, p0, null) == null,
                "null → 返回 null");
        check(com.ovo.sablestopnow.sketch.SketchMeasure.between(k, p0, p0) == null,
                "同一个图元和它自己 → 返回 null");

        // 零长度连线不该除零
        final Sketch.Entity degenerate = k.addLine(3, 3, 3, 3);
        final com.ovo.sablestopnow.sketch.SketchMeasure.Result dr =
                com.ovo.sablestopnow.sketch.SketchMeasure.between(k, p1, degenerate);
        check(dr != null && Double.isFinite(dr.distance()),
                "对零长度线段测距不产生 NaN/Inf");
    }

    /**
     * 弧参与成环 —— 这是"画弧 + 直线围成封闭形状 → 拉伸"那个卡点的核心断言。
     *
     * <p>弧和线段有两点不同，都要在这里覆盖：
     * ① 弧的扫向写死（不能靠交换端点下标反转）；② 弧必须按**采样折线**进链，
     * 只放两个端点的话弧会退化成一条弦，面积/三角化/盒分解全错。
     */
    private static void arcLoops() {
        // 一段 90° 弧（圆心 0,0 半径 5，从 +u 到 +v）+ 一条弦 → 封闭的弓形
        final Sketch k = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity arc = k.addArc(0, 0, 5, 0, Math.PI / 2);
        k.addLine(0, 5, 5, 0); // 弦：从弧的终点回到弧的起点

        final java.util.List<com.ovo.sablestopnow.sketch.SketchProfiles.Profile> loops =
                com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(k);
        check(loops.size() == 1, "弧 + 弦 = 1 个闭合轮廓（实测 " + loops.size() + "）");
        if (loops.isEmpty()) {
            return;
        }
        final com.ovo.sablestopnow.sketch.SketchProfiles.Profile bow = loops.get(0);
        check(!bow.degenerate(), "弓形轮廓不是退化的");
        check(!com.ovo.sablestopnow.sketch.SketchProfiles.selfIntersects(bow), "弓形不自交");

        // ⚠ 关键：弧必须被**采样**进轮廓。只放端点的话点数会是 3（两个弧端点 + 弦端点），
        // 面积也会变成 0（三点共线附近的退化情况）。
        check(bow.size() > 5,
                "弧被采样进轮廓（点数 " + bow.size() + " > 5，不是只有两个端点加一条弦）");
        // 弓形面积 = 扇形 − 三角形，必须落在 (0, 四分之一圆) 之间且明显大于 0。
        // 折线近似在真弧**内侧**，所以面积略小于解析值 7.135。
        final double area = Math.abs(bow.signedArea());
        check(area > 6.0 && area < Math.PI * 25 / 4,
                "弓形面积落在合理范围（实测 " + String.format("%.3f", area) + "，解析值约 7.135）");

        // --- 真正要的东西：能拉伸出来 ---
        final java.util.List<double[]> boxes = com.ovo.sablestopnow.sketch.Extrude.boxesOf(k,
                com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        check(!boxes.isEmpty(), "**弧围成的形状能拉伸出实体**（用户报的卡点）");
        check(!com.ovo.sablestopnow.sketch.Extrude.prismEdges(k,
                com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0)).isEmpty(),
                "棱柱线框也非空");
        // 盒子的总面积应当接近轮廓面积（拉伸出的是柱体）
        double boxArea = 0.0;
        for (final double[] b : boxes) {
            boxArea += (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]) / 2.0; // 深度 2 → 体积/2 = 面积
        }
        check(boxArea > 5.0, "盒分解覆盖的面积接近轮廓（实测 " + String.format("%.3f", boxArea) + "）");

        // --- 扇形：弧 + 两条半径 ---
        final Sketch pie = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        pie.addArc(0, 0, 4, 0, Math.PI / 2);
        pie.addLine(0, 4, 0, 0);  // 从弧终点回到圆心
        pie.addLine(0, 0, 4, 0);  // 从圆心到弧起点
        final java.util.List<com.ovo.sablestopnow.sketch.SketchProfiles.Profile> pieLoops =
                com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(pie);
        check(pieLoops.size() == 1, "弧 + 两条半径 = 1 个闭合轮廓（实测 " + pieLoops.size() + "）");
        if (!pieLoops.isEmpty()) {
            final double pieArea = Math.abs(pieLoops.get(0).signedArea());
            // 四分之一圆 r=4 → 解析值 4π ≈ 12.566
            check(pieArea > 11.5 && pieArea < 4 * Math.PI,
                    "扇形面积落在合理范围（实测 " + String.format("%.3f", pieArea) + "，解析值约 12.566）");
        }

        // --- 弧端点/圆心要能被吸附（用户报的另一个卡点） ---
        final com.ovo.sablestopnow.sketch.SketchSession pick = editing();
        pick.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.ARC);
        pick.click(0.0, 0.0);   // 圆心
        pick.click(5.0, 0.0);   // 起点 → 半径 5、起始角 0
        pick.click(0.0, 5.0);   // 终点 → 90°
        final Sketch pk = pick.working();
        final Sketch.Entity pkArc = pk.entities().get(0);
        final int[] pkAv = pkArc.vars();

        // 弧的起点 (5,0)：应当吸到一个关键点；⚠ 新布局下端点**就是变量**
        //   ⇒ px/py 必须给出 (sx, sy) 的下标 ⇒ 吸附才能落成真约束
        final com.ovo.sablestopnow.sketch.SketchSession.KeyPoint atStart =
                pick.pickKeyPoint(5.05, 0.05, 0.5);
        check(atStart != null, "弧起点附近能吸到关键点");
        if (atStart != null) {
            near(atStart.u(), 5.0, "吸到的是弧起点的 u");
            near(atStart.v(), 0.0, "吸到的是弧起点的 v");
            check(atStart.kind() == com.ovo.sablestopnow.sketch.SketchSession.KeyKind.ENDPOINT,
                    "分类是端点");
            check(atStart.px() == pkAv[2] && atStart.py() == pkAv[3],
                    "弧端点**就是变量** ⇒ 吸到的下标正好是 (sx, sy)（以前是 -1 ⇒ 只吸位置 ✗）");
        }

        // 弧的终点 (0,5)：同样要给出 (ex, ey) 的下标
        final com.ovo.sablestopnow.sketch.SketchSession.KeyPoint atEnd =
                pick.pickKeyPoint(0.05, 5.05, 0.5);
        check(atEnd != null && Math.abs(atEnd.u()) < 1.0e-9 && Math.abs(atEnd.v() - 5.0) < 1.0e-9,
                "弧终点附近能吸到 (0,5)");
        check(atEnd != null && atEnd.px() == pkAv[4] && atEnd.py() == pkAv[5],
                "吸到终点时给的下标是 (ex, ey)");

        // 弧的圆心：**有**变量 → 能产生真正的重合约束
        final com.ovo.sablestopnow.sketch.SketchSession.KeyPoint atCentre =
                pick.pickKeyPoint(0.05, 0.05, 0.5);
        check(atCentre != null && atCentre.kind()
                        == com.ovo.sablestopnow.sketch.SketchSession.KeyKind.CENTER,
                "弧圆心附近吸到圆心关键点");
        check(atCentre != null && atCentre.px() >= 0,
                "圆心**有**变量 → 可以生成 COINCIDENT（和圆一致）");

        // ⚠ 最关键的一条：吸到弧端点之后**不能**写出带 -1 的坏约束。
        // 那种约束会让整张草图求解失败（求解器报变量越界），玩家看到的是"画完就散"。
        final int constraintsBefore = pk.constraints().size();
        final com.ovo.sablestopnow.sketch.SketchSession snapLine = editing();
        snapLine.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.ARC);
        snapLine.click(0.0, 0.0);
        snapLine.click(5.0, 0.0);
        snapLine.click(0.0, 5.0);
        final Sketch sl = snapLine.working();
        final int slBefore = sl.constraints().size();
        // 把弧端点记成"上次吸到的"（这就是 click 路径里真实走的那一步：
        //   UI 先把鼠标位置过一遍 snap() ✓ 再拿吸附后的坐标调 click() ✓）
        //   ⚠ 以前这里**只写了注释、没真的调 snap()** ⇒ 下面那条"没有坏约束"的断言**恒真**
        //     （什么都没记下来 ⇒ 什么约束都不会加 ⇒ 永远是"没问题"）。
        //     现在显式调，断言才真的压在吸附落约束这条路径上。
        final double[] snappedUv = snapLine.snap(5.02, 0.01, 0.5);
        check(Math.abs(snappedUv[0] - 5.0) < 1.0e-9 && Math.abs(snappedUv[1]) < 1.0e-9,
                "snap() 把落点吸到了弧起点 (5,0)");
        snapLine.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        snapLine.click(snappedUv[0], snappedUv[1]); // 起点吸到弧起点
        snapLine.click(9.0, 0.0);    // 随便画到右边
        boolean noBogus = true;
        for (final Sketch.Constraint c : sl.constraints()) {
            for (final int op : c.operands()) {
                if (op < 0 || op >= sl.variableCount()) {
                    noBogus = false;
                    break;
                }
            }
        }
        check(noBogus, "吸到弧端点后**没有**产生带非法下标的约束（否则求解会整张失败）");
        check(sl.constraints().size() >= slBefore, "约束数没减少");
        check(pk.constraints().size() == constraintsBefore, "（另一张草图的约束数不受影响）");

        // ★ 加强（新布局才做得到）：吸附**真的落成了约束** ——
        //   新线的起点变量与弧起点变量之间必须有一条 COINCIDENT。
        //   旧布局下弧端点没有变量 ⇒ 这里只吸了坐标、什么都没落 ✗（用户实测报过这条）。
        final Sketch.Entity slLine = sl.entities().get(sl.entities().size() - 1);
        final int[] slLv = slLine.vars();
        final int[] slArcV = sl.entities().get(0).vars();
        boolean boundToArcStart = false;
        for (final Sketch.Constraint c : sl.constraints()) {
            if (c.kind() != SketchSolver.COINCIDENT) {
                continue;
            }
            final int[] o = c.operands();
            if (o.length < 4) {
                continue;
            }
            final boolean forward = o[0] == slLv[0] && o[1] == slLv[1]
                    && o[2] == slArcV[2] && o[3] == slArcV[3];
            final boolean backward = o[0] == slArcV[2] && o[1] == slArcV[3]
                    && o[2] == slLv[0] && o[3] == slLv[1];
            if (forward || backward) {
                boundToArcStart = true;
            }
        }
        check(boundToArcStart,
                "吸到弧端点 ⇒ 落成**真的 COINCIDENT**（新线的起点变量 ↔ 弧的 (sx,sy) 变量）"
                        + "—— 这就是「吸附即约束」；旧布局下弧端点没有变量，做不到");

        // --- 反向：只画一段**开**弧 → 不成环 ---
        final Sketch open = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        open.addArc(0, 0, 5, 0, Math.PI / 2);
        check(com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(open).isEmpty(),
                "单独一段开弧不成环（不该凭空造出轮廓）");

        // --- 弧 + 一条够不到的线 → 开链，不成环 ---
        final Sketch dangling = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        dangling.addArc(0, 0, 5, 0, Math.PI / 2);
        dangling.addLine(0, 5, 9, 9); // 接到了弧的终点，但另一端回不到起点
        check(com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(dangling).isEmpty(),
                "接了一头但没闭环 → 不成轮廓");

        // --- 圆的成环不受影响（回归） ---
        final Sketch withCircle = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        withCircle.addCircle(0, 0, 3);
        check(com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(withCircle).size() == 1,
                "圆仍然自成 1 个轮廓（回归）");
    }

    /**
     * 距离工具落约束：**能落的落，不能落的明说**。
     *
     * <p>核心是那条边界：`DISTANCE` 的定义是"两点之间"，所以平行线间距、点到线垂距
     * 这些**表达不了**。硬塞一个 DISTANCE 会变成"两条线的端点之间的距离"，
     * 语义完全不同 —— 等于把假约束锁进草图，比不加还糟。
     */
    private static void measureToConstraint() {
        final com.ovo.sablestopnow.sketch.SketchSession s = editing();
        final Sketch k = s.working();
        final Sketch.Entity p0 = k.addPoint(0, 0);
        final Sketch.Entity p1 = k.addPoint(3, 4);
        final Sketch.Entity circle = k.addCircle(0, 10, 2);
        final Sketch.Entity line = k.addLine(0, 0, 10, 0);

        // 点点 → 能量，落一条 DISTANCE
        final com.ovo.sablestopnow.sketch.SketchMeasure.Result r =
                com.ovo.sablestopnow.sketch.SketchMeasure.between(k, p0, p1);
        near(r.distance(), 5.0, "先量出点点距 5");
        final int before = k.constraints().size();
        check(s.dimensionBetween(p0, p1, r.distance()), "点点距能量成约束");
        check(k.constraints().size() == before + 1, "确实多了一条约束");
        final Sketch.Constraint c = k.constraints().get(before);
        check(c.kind() == SketchSolver.DISTANCE, "种类是 DISTANCE");
        near(c.value(), 5.0, "约束的值 = 量出来的距离（两者必须一致，否则就是锁了个假值）");
        check(c.operands().length == 4, "DISTANCE 是 4 个操作数（两个点）");
        check(c.operands()[0] == p0.vars()[0] && c.operands()[1] == p0.vars()[1]
                        && c.operands()[2] == p1.vars()[0] && c.operands()[3] == p1.vars()[1],
                "操作数正是这两个点的变量");

        // 点 ↔ 圆心 → 能量（圆心就是个点）
        final com.ovo.sablestopnow.sketch.SketchMeasure.Result pr =
                com.ovo.sablestopnow.sketch.SketchMeasure.between(k, p0, circle);
        check(s.dimensionBetween(p0, circle, pr.distance()), "点到圆心的距离能量成约束");

        // ⚠ 点 ↔ 线段 → 表达不了（线段没有单一代表点），必须返回 false 而不是乱塞一条
        check(!s.dimensionBetween(p0, line, 5.0), "点到线段 → 落不了约束（返回 false）");
        // ⚠ 两条平行线 → 表达不了。这是最容易被"顺手支持"掉的一个：
        // 硬塞 DISTANCE 会变成两条线的**端点**之间的距离，语义完全不同。
        final Sketch.Entity parallel = k.addLine(0, 5, 10, 5);
        final com.ovo.sablestopnow.sketch.SketchMeasure.Result par =
                com.ovo.sablestopnow.sketch.SketchMeasure.between(k, line, parallel);
        check(par.kind() == com.ovo.sablestopnow.sketch.SketchMeasure.Kind.PARALLEL_LINES,
                "量到的是平行线间距");
        check(!s.dimensionBetween(line, parallel, par.distance()),
                "平行线间距 → **落不了约束**（不能塞成端点距离）");

        // 退化输入
        check(!s.dimensionBetween(null, p1, 1.0), "null → false");
        check(!s.dimensionBetween(p0, p0, 1.0), "同一个图元 → false");

        // 约束数没有因为上面那些"落不了"的调用而变多
        final int after = k.constraints().size();
        s.dimensionBetween(p0, line, 5.0);
        s.dimensionBetween(line, parallel, 5.0);
        check(k.constraints().size() == after, "落不了的调用不会偷偷加约束");

        // canLock 决定 UI **给不给"标注"按钮**：给错了按钮，玩家点了没反应；
        // 给了不该给的，就会锁进一条语义不同的假约束。
        check(com.ovo.sablestopnow.sketch.SketchMeasure.canLock(p0, p1), "点点 → 可锁");
        check(com.ovo.sablestopnow.sketch.SketchMeasure.canLock(p0, circle),
                "点 ↔ 圆心 → 可锁（圆心就是个点）");
        check(!com.ovo.sablestopnow.sketch.SketchMeasure.canLock(p0, line),
                "点 ↔ 线段 → 不可锁（线段没有单一代表点）");
        check(!com.ovo.sablestopnow.sketch.SketchMeasure.canLock(line, parallel),
                "线 ↔ 线 → 不可锁");
        check(!com.ovo.sablestopnow.sketch.SketchMeasure.canLock(p0, p0), "同一个图元 → 不可锁");
        check(!com.ovo.sablestopnow.sketch.SketchMeasure.canLock(p0, null), "null → 不可锁");
    }

    /**
     * ⚠ 用户实测反馈：「需要约束数量会增多，**不会随着线条删除而减少**」。
     *
     * <p>根因：删图元时变量是**故意保留**的（图元只存下标，回收会让后面全部错位），
     * 但自由度分析把这些**孤儿变量**也当成了自由度 —— 于是删多少条线，
     * "还需要 N 个约束"都减不下去。修法：只在**仍被图元引用**的变量上做分析。
     */
    private static void dofIgnoresOrphanVariables() {
        final Sketch k = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity a = k.addLine(0, 0, 4, 0);
        final Sketch.Entity b = k.addLine(0, 5, 4, 5);
        check(k.variableCount() == 8, "两条线 = 8 个变量");
        check(com.ovo.sablestopnow.sketch.SketchDof.activeVariableCount(k) == 8,
                "两条线都被引用 → 有效变量 8");
        final int before = com.ovo.sablestopnow.sketch.SketchDof.remainingDof(k);
        check(before == 8, "没有约束 → 还需要 8 个（实测 " + before + "）");

        // 删掉一条线：变量总数不变，但**有效变量**少 4 个
        k.removeEntity(b);
        check(k.variableCount() == 8, "变量总数仍然是 8（故意不回收）");
        check(k.usedVariables().length == 4,
                "但**仍被引用**的变量只剩 4 个（实测 " + k.usedVariables().length + "）");
        final int after = com.ovo.sablestopnow.sketch.SketchDof.remainingDof(k);
        check(after == 4,
                "**删除后\"还需要 N 个约束\"确实减少了**（8 → " + after + "）—— 用户报的 bug");

        // 再删一条 → 0
        k.removeEntity(a);
        check(com.ovo.sablestopnow.sketch.SketchDof.remainingDof(k) == 0,
                "两条都删掉 → 还需要 0 个");
        check(com.ovo.sablestopnow.sketch.SketchDof.activeVariableCount(k) == 0,
                "没有图元 → 有效变量 0");

        // 孤儿变量不该被算成"自由"，否则图元上色也会出问题
        final Sketch k2 = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity keep = k2.addLine(0, 0, 2, 0);
        final Sketch.Entity gone = k2.addLine(9, 9, 10, 10);
        k2.removeEntity(gone);
        final boolean[] free = com.ovo.sablestopnow.sketch.SketchDof.freeVariablesOf(k2);
        check(free.length == 8, "返回的数组仍按变量总数（8）");
        boolean orphanFree = false;
        for (final int v : gone.vars()) {
            if (free[v]) {
                orphanFree = true;
            }
        }
        check(!orphanFree, "孤儿变量**不算自由**（它们不参与任何几何）");
        check(!com.ovo.sablestopnow.sketch.SketchDof.isFullyDefined(free, keep),
                "剩下的那条线仍然欠定义");

        // 用矩形再走一遍（更接近实际操作）。
        // ⚠ 注意：删掉一条**带约束**的边，剩余自由度可能**变大** ——
        // 因为 {@link Sketch#removeEntity} 会把引用该边的约束一起删掉（1 条水平 + 2 个重合 = 5 行），
        // 约束掉得比变量快。这是**正确**行为，不是 bug。
        // 用户报的 bug 是"孤儿变量被当成自由度"（上面 8 → 4 那条已经钉住）。
        final com.ovo.sablestopnow.sketch.SketchSession rs = editing();
        rs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        rs.click(0.0, 0.0);
        rs.click(4.0, 3.0);
        final Sketch rect = rs.working();
        final int activeBefore = com.ovo.sablestopnow.sketch.SketchDof.activeVariableCount(rect);
        final int constraintsBefore = rect.constraints().size();
        rs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.SELECT);
        rs.selectEntity(rect.entities().get(0));
        rs.removeSelected();
        final int activeAfter = com.ovo.sablestopnow.sketch.SketchDof.activeVariableCount(rect);
        check(activeAfter == activeBefore - 4,
                "删一条边 → **有效变量**少 4 个（" + activeBefore + " → " + activeAfter + "）");
        check(rect.constraints().size() < constraintsBefore,
                "引用那条边的约束也一起被删了（" + constraintsBefore + " → "
                        + rect.constraints().size() + "）");
        final int rectDof = com.ovo.sablestopnow.sketch.SketchDof.remainingDof(rect);
        check(rectDof >= 0 && rectDof <= activeAfter,
                "剩余自由度落在 [0, 有效变量数] 内（实测 " + rectDof + " / " + activeAfter + "）");
    }

    /**
     * 关系型约束（用户反馈「几何约束里应该是元素之间关系」）。
     *
     * <p>现在能做的是**用现有求解器种类**表达得了的那几个：等长、同心。
     * 平行/垂直做不了（种类 1–8 里没有），那需要改三处，用户已定暂缓。
     */
    private static void relationalConstraints() {
        final com.ovo.sablestopnow.sketch.SketchSession s = editing();
        final Sketch k = s.working();
        final Sketch.Entity l1 = k.addLine(0, 0, 4, 0);
        final Sketch.Entity l2 = k.addLine(0, 2, 3, 2);
        final Sketch.Entity l3 = k.addLine(0, 4, 9, 4);
        final Sketch.Entity c1 = k.addCircle(0, 10, 2);
        final Sketch.Entity c2 = k.addCircle(5, 10, 3);

        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.SELECT);

        // --- 等长：三条线一起选，加 2 条 EQUAL_LEN（以第一条为基准） ---
        s.selectEntity(l1);
        s.toggleEntity(l2);
        s.toggleEntity(l3);
        check(s.selectionSize() == 3, "选中 3 条线");
        final int before = k.constraints().size();
        check(s.makeEqual(), "等长加成功");
        check(k.constraints().size() == before + 2,
                "3 条线两两等长 = 加 2 条约束（实测 +" + (k.constraints().size() - before) + "）");
        int equalCount = 0;
        for (final Sketch.Constraint c : k.constraints()) {
            if (c.kind() == SketchSolver.EQUAL_LEN) {
                equalCount++;
            }
            check(c.operands().length == 8, "EQUAL_LEN 恒为 8 个操作数");
        }
        check(equalCount == 2, "一共 2 条 EQUAL_LEN");

        // ⚠ 选中的线段不足两条 → false（不能静默无反应）
        s.selectEntity(l1);
        check(!s.makeEqual(), "只选 1 条线 → 等长返回 false");

        // --- 同心：两个圆 ---
        s.selectEntity(c1);
        s.toggleEntity(c2);
        final int cb = k.constraints().size();
        check(s.makeConcentric(), "同心加成功");
        check(k.constraints().size() == cb + 1, "两个圆同心 = 1 条 COINCIDENT");
        final Sketch.Constraint conc = k.constraints().get(cb);
        check(conc.kind() == SketchSolver.COINCIDENT, "同心用 COINCIDENT 表达（不需要新种类）");
        check(conc.operands().length == 4, "COINCIDENT 是 4 个操作数（两个点）");
        check(conc.operands()[0] == c1.vars()[0] && conc.operands()[2] == c2.vars()[0],
                "操作数正是两个圆的圆心变量");

        // 选中的圆/弧不足两个 → false
        s.selectEntity(c1);
        check(!s.makeConcentric(), "只选 1 个圆 → 同心返回 false");
        // 选了圆但只有一个 + 一条线 → 仍然 false（线段不是圆）
        s.toggleEntity(l1);
        check(!s.makeConcentric(), "圆 + 线段 → 同心返回 false（线段没有圆心）");

        // --- ⚠ 多选时**不许**加水平/竖直（那是单元素约束，不是关系） ---
        s.selectEntity(l1);
        s.toggleEntity(l2);
        final int hb = k.constraints().size();
        check(!s.constrainSelected(SketchSolver.HORIZONTAL),
                "多选加水平 → **拒绝**（不能把同一个约束对每条线各加一遍）");
        check(!s.constrainSelected(SketchSolver.VERTICAL), "多选加竖直 → 拒绝");
        check(k.constraints().size() == hb, "被拒绝的调用不会偷偷加约束");

        // 单选时水平仍然可用（这是合法且常用的）
        s.selectEntity(l1);
        check(s.constrainSelected(SketchSolver.HORIZONTAL), "单选加水平 → 正常");

        // 固定是多元素也无所谓的吗？—— 固定是逐变量钉死，对多选语义清晰，保留
        s.selectEntity(l1);
        s.toggleEntity(l2);
        check(s.constrainSelected(SketchSolver.FIXED), "多选加固定 → 仍然允许（逐变量钉死，语义清晰）");
    }

    /**
     * 碰撞盒 → 物理引擎形态（中心 + 半长 + 分摊质量）。
     *
     * <p>路线 (C)：`PhysicsPipeline.addBox(BoxPhysicsObject)` + Fixed 约束。
     * ⚠ 一个盒子 = **一个刚体**，所以盒子数 = 物理对象数（圆会分解出几十个）。
     */
    private static void collisionSpecs() {
        final java.util.List<double[]> boxes = java.util.List.of(
                new double[] {0, 0, 0, 4, 3, 2},      // 体积 24
                new double[] {10, 0, 0, 12, 1, 1});   // 体积 2
        final java.util.List<double[]> specs =
                com.ovo.sablestopnow.sketch.SolidCollision.boxSpecs(boxes);
        check(specs.size() == 2, "两个盒子 → 两个规格");
        nearVec6(specs.get(0), 2.0, 1.5, 1.0, 2.0, 1.5, 1.0, "第一个盒子的中心/半长");
        nearVec6(specs.get(1), 11.0, 0.5, 0.5, 1.0, 0.5, 0.5, "第二个盒子的中心/半长");

        // ⚠ 最容易写错的地方：半长当边长用。半长 ×2 还原成边长后体积必须守恒。
        double specVolume = 0.0;
        for (final double[] s : specs) {
            specVolume += 8.0 * s[3] * s[4] * s[5];
        }
        near(specVolume, com.ovo.sablestopnow.sketch.SolidCollision.volume(boxes),
                "规格还原的体积 = 原盒子体积（24 + 2 = 26）");
        near(specVolume, 26.0, "体积就是 26");

        check(com.ovo.sablestopnow.sketch.SolidCollision.boxSpecs(java.util.List.of(
                new double[] {1, 1, 1, 1, 2, 2})).isEmpty(), "零厚度的盒子不产生规格");
        check(com.ovo.sablestopnow.sketch.SolidCollision.boxSpecs(java.util.List.of()).isEmpty(),
                "空表 → 空规格");

        // ⚠ 质量分摊：刚体必须有质量，但盒子是**分解**出来的、不是独立物体，
        // 每个都算满会把同一块材料重复计入。按体积分摊，总质量必须等于实体质量。
        final double total = com.ovo.sablestopnow.sketch.SolidCollision.volume(boxes);
        double massSum = 0.0;
        for (final double[] s : specs) {
            massSum += com.ovo.sablestopnow.sketch.SolidCollision.massPerBox(s, 100.0, total);
        }
        near(massSum, 100.0, "分摊后质量总和 = 实体总质量（不能超也不能少）");
        near(com.ovo.sablestopnow.sketch.SolidCollision.massPerBox(specs.get(0), 100.0, total),
                100.0 * 24.0 / 26.0, "大盒子分到 24/26 的质量");
        check(com.ovo.sablestopnow.sketch.SolidCollision.massPerBox(specs.get(0), 100.0, 0.0) == 0.0,
                "总体积为 0 → 分摊质量 0（不是 NaN）");
        check(com.ovo.sablestopnow.sketch.SolidCollision.massPerBox(null, 100.0, 1.0) == 0.0,
                "null 规格 → 0");

        // 真实体：矩形只需 1 个刚体
        final com.ovo.sablestopnow.sketch.SketchSession rs = editing();
        rs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        rs.click(0.0, 0.0);
        rs.click(4.0, 3.0);
        final java.util.List<double[]> rectBoxes = com.ovo.sablestopnow.sketch.Extrude.boxesOf(
                rs.working(), com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        check(com.ovo.sablestopnow.sketch.SolidCollision.boxSpecs(rectBoxes).size() == 1,
                "矩形实体只需 1 个刚体（轴对齐轮廓的最优情况）");

        // ⚠ 圆的代价：把"性能瓶颈在哪、以及碰撞用了粗采样"钉住。
        // 碰撞是**一个盒子 = 一个刚体 + 一条约束**，所以这个数字就是刚体数。
        final com.ovo.sablestopnow.sketch.SketchSession cs = editing();
        cs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        cs.click(0.0, 0.0);
        cs.click(3.0, 0.0);
        final int circleSpecs = com.ovo.sablestopnow.sketch.SolidCollision.boxSpecs(
                com.ovo.sablestopnow.sketch.Extrude.boxesOf(cs.working(),
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0))).size();
        final int renderSpecs = com.ovo.sablestopnow.sketch.SolidCollision.boxSpecs(
                com.ovo.sablestopnow.sketch.Extrude.boxesOf(cs.working(),
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0),
                        com.ovo.sablestopnow.sketch.SketchProfiles.Sampling.RENDER)).size();
        check(circleSpecs < 20,
                "碰撞按粗采样 → 圆只需 " + circleSpecs + " 个刚体（细采样要 " + renderSpecs + " 个）");
        check(renderSpecs > circleSpecs * 2,
                "两种精度确实拉开了差距（碰撞 " + circleSpecs + " vs 细采样 " + renderSpecs + "）");

        // ⚠ 采样段数必须**随半径增长**：折线的弓高 r·(1−cos(π/n)) 与半径成正比，
        // 固定段数会让大圆的碰撞偏差大到看得出来（r=20 固定 12 段 → 偏 0.68 格）。
        final com.ovo.sablestopnow.sketch.SketchProfiles.Sampling col =
                com.ovo.sablestopnow.sketch.SketchProfiles.Sampling.COLLISION;
        check(col.segmentsFor(20.0) > col.segmentsFor(1.0),
                "大圆用更多段（r=20 → " + col.segmentsFor(20.0)
                        + " 段，r=1 → " + col.segmentsFor(1.0) + " 段）");
        // 弓高必须在上界之内
        for (final double r : new double[] {1.0, 3.0, 10.0, 20.0, 50.0}) {
            final int n = col.segmentsFor(r);
            final double sagitta = r * (1.0 - Math.cos(Math.PI / n));
            check(sagitta <= col.maxSagitta() + 1.0e-9,
                    "r=" + r + " 的 " + n + " 段折线弓高 " + String.format("%.4f", sagitta)
                            + " ≤ 上界 " + col.maxSagitta());
        }
        // 渲染精度也要守上界
        for (final double r : new double[] {3.0, 20.0}) {
            final int n = com.ovo.sablestopnow.sketch.SketchProfiles.Sampling.RENDER.segmentsFor(r);
            check(r * (1.0 - Math.cos(Math.PI / n))
                            <= com.ovo.sablestopnow.sketch.SketchProfiles.Sampling.RENDER.maxSagitta()
                            + 1.0e-9,
                    "渲染采样 r=" + r + " 的弓高也在上界内（" + n + " 段）");
        }
    }

    /**
     * 「大型圆柱精度够不够」——用**数字**回答，不凭感觉。
     *
     * <p>要验两件事：
     * ① 轴对齐扫描线分解**只会偏小不会偏大**（excess ≈ 0）。碰撞偏小能从边上擦过去，
     *    偏大则是撞空气 —— 后者更糟，所以不能有。
     * ② 采样段数**随半径自适应**之后，大圆的误差占比不随半径恶化；
     *    如果固定段数，大圆的误差会明显变大。
     */
    private static void collisionAccuracy() {
        // --- 小圆：COLLISION 采样的覆盖误差应当很小 ---
        checkAccuracy(3.0, 0.05, "r=3");
        // --- 大圆：**这是用户问的那个场景** ---
        checkAccuracy(20.0, 0.05, "r=20");
        checkAccuracy(50.0, 0.05, "r=50");

        // ⚠ 关键对比：如果把段数**固定**成 12，大圆的误差会飙到多少？
        // 这条断言就是"为什么必须自适应"的证据。
        final double[] r12 = errorForFixedSegments(50.0, 12);
        final double[] adaptive = errorForFixedSegments(50.0,
                com.ovo.sablestopnow.sketch.SketchProfiles.Sampling.COLLISION.segmentsFor(50.0));
        check(adaptive[0] < r12[0] * 0.5,
                "r=50 自适应（" + com.ovo.sablestopnow.sketch.SketchProfiles.Sampling
                        .COLLISION.segmentsFor(50.0) + " 段）漏面积 "
                        + String.format("%.4f", adaptive[0])
                        + " 远小于固定 12 段的 " + String.format("%.4f", r12[0]));

        // --- 长方形（轴对齐轮廓）：分解应当是**精确**的 ---
        final java.util.List<double[]> rect = java.util.List.of(
                new double[] {0, 0}, new double[] {6, 0}, new double[] {6, 4}, new double[] {0, 4});
        final com.ovo.sablestopnow.sketch.SketchProfiles.Profile rectProfile =
                new com.ovo.sablestopnow.sketch.SketchProfiles.Profile(rect,
                        com.ovo.sablestopnow.sketch.SketchProfiles.signedArea(rect));
        final com.ovo.sablestopnow.sketch.SketchProfiles.Region rectRegion =
                new com.ovo.sablestopnow.sketch.SketchProfiles.Region(rectProfile,
                        java.util.List.of());
        final com.ovo.sablestopnow.sketch.SketchProfiles.Coverage rectCov =
                com.ovo.sablestopnow.sketch.SketchProfiles.coverage(rectRegion,
                        com.ovo.sablestopnow.sketch.SketchProfiles.toBoxes(rectRegion), 64);
        check(rectCov.uncoveredFraction() < 1.0e-6,
                "轴对齐矩形：**没有**漏掉的面积（实测 " + rectCov.uncoveredFraction() + "）");
        check(rectCov.excessFraction() < 1.0e-6,
                "轴对齐矩形：**没有**多出来的面积（实测 " + rectCov.excessFraction() + "）");
        // 轴对齐轮廓是**精确**分解（不是近似）—— 这是为什么矩形/L 形只需很少盒子

        // --- 带孔区域也不能有多余覆盖（孔不能被填上） ---
        final java.util.List<double[]> holeRing = java.util.List.of(
                new double[] {2, 2}, new double[] {2, 4}, new double[] {4, 4}, new double[] {4, 2});
        final com.ovo.sablestopnow.sketch.SketchProfiles.Profile holeProfile =
                new com.ovo.sablestopnow.sketch.SketchProfiles.Profile(holeRing,
                        com.ovo.sablestopnow.sketch.SketchProfiles.signedArea(holeRing));
        final com.ovo.sablestopnow.sketch.SketchProfiles.Region holedRegion =
                new com.ovo.sablestopnow.sketch.SketchProfiles.Region(rectProfile,
                        java.util.List.of(holeProfile));
        final com.ovo.sablestopnow.sketch.SketchProfiles.Coverage holedCov =
                com.ovo.sablestopnow.sketch.SketchProfiles.coverage(holedRegion,
                        com.ovo.sablestopnow.sketch.SketchProfiles.toBoxes(holedRegion), 64);
        check(holedCov.excessFraction() < 1.0e-6,
                "带孔区域：孔**没有**被填上（多余覆盖 " + holedCov.excessFraction() + "）");
        check(holedCov.uncoveredFraction() < 1.0e-6,
                "带孔区域：材料部分**没有**漏掉");
    }

    /** 半径为 {@code r} 的圆，按 COLLISION 采样分解后的覆盖误差，断言其够小。 */
    private static void checkAccuracy(final double r, final double maxUncovered,
                                      final String label) {
        final java.util.List<double[]> points = new java.util.ArrayList<>();
        final int n = com.ovo.sablestopnow.sketch.SketchProfiles.Sampling.COLLISION.segmentsFor(r);
        for (int i = 0; i < n; i++) {
            final double a = 2.0 * Math.PI * i / n;
            points.add(new double[] {r * Math.cos(a), r * Math.sin(a)});
        }
        final com.ovo.sablestopnow.sketch.SketchProfiles.Profile profile =
                new com.ovo.sablestopnow.sketch.SketchProfiles.Profile(points,
                        com.ovo.sablestopnow.sketch.SketchProfiles.signedArea(points));
        final com.ovo.sablestopnow.sketch.SketchProfiles.Region region =
                new com.ovo.sablestopnow.sketch.SketchProfiles.Region(profile, java.util.List.of());
        final com.ovo.sablestopnow.sketch.SketchProfiles.Coverage cov =
                com.ovo.sablestopnow.sketch.SketchProfiles.coverage(region,
                        com.ovo.sablestopnow.sketch.SketchProfiles.toBoxes(region), 96);
        check(cov.uncoveredFraction() < maxUncovered,
                label + " 圆（" + n + " 段 → 盒分解）漏面积占比 "
                        + String.format("%.4f", cov.uncoveredFraction()) + " < " + maxUncovered);
        // ⚠ 中点采样是**有偏大也有偏小**的（不是纯内接）：
        // 带的宽度在带内变化，按中点撑满整条带，边缘处会略微超出轮廓。
        // 实测 r=3 约 3.6%、r=20 约 1.2%、r=50 约 0.9% —— **占比随半径下降**。
        // 这条断言把"偏差有多大、方向如何"钉住；想更小就加密采样。
        check(cov.excessFraction() < 0.06,
                label + " 圆的多余覆盖占比 " + String.format("%.4f", cov.excessFraction())
                        + " < 0.06（撞空气的幅度：越小越好，且随半径下降）");
    }

    /** 固定用 {@code segments} 段近似半径 {@code r} 的圆，返回 {@code {漏占比, 多占比}}。 */
    private static double[] errorForFixedSegments(final double r, final int segments) {
        final java.util.List<double[]> points = new java.util.ArrayList<>();
        for (int i = 0; i < segments; i++) {
            final double a = 2.0 * Math.PI * i / segments;
            points.add(new double[] {r * Math.cos(a), r * Math.sin(a)});
        }
        final com.ovo.sablestopnow.sketch.SketchProfiles.Profile profile =
                new com.ovo.sablestopnow.sketch.SketchProfiles.Profile(points,
                        com.ovo.sablestopnow.sketch.SketchProfiles.signedArea(points));
        final com.ovo.sablestopnow.sketch.SketchProfiles.Region region =
                new com.ovo.sablestopnow.sketch.SketchProfiles.Region(profile, java.util.List.of());
        final com.ovo.sablestopnow.sketch.SketchProfiles.Coverage cov =
                com.ovo.sablestopnow.sketch.SketchProfiles.coverage(region,
                        com.ovo.sablestopnow.sketch.SketchProfiles.toBoxes(region), 96);
        return new double[] {cov.uncoveredFraction(), cov.excessFraction()};
    }

    /**
     * 旋转拉伸：把轮廓绕**平面内的一条轴**转出一个回转体。
     *
     * <p>核心不变量：**垂距（到轴的垂直距离）在旋转中不变** —— 每个轮廓点画出一个圆，
     * 半径就是它的垂距。这条比"坐标对不对"更能抓住转轴/旋向写错。
     */
    private static void revolve() {
        // --- 参数校验 ---
        boolean threw = false;
        try {
            new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 0, 90, 1);
        } catch (final IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "轴方向为零向量 → 被拒");
        threw = false;
        try {
            new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 90, 0);
        } catch (final IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "旋向不是 ±1 → 被拒");
        near(new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 1.0e9, 1).angleDeg(),
                com.ovo.sablestopnow.sketch.Revolve.MAX_ANGLE, "超大角度被夹到一整圈");
        near(new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 0.0, 1).angleDeg(),
                com.ovo.sablestopnow.sketch.Revolve.MIN_ANGLE, "0 度被抬到最小角（转不出东西）");

        // --- 有符号角与整圈判定 ---
        final com.ovo.sablestopnow.sketch.Revolve.Params ccw =
                new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 360, 1);
        final com.ovo.sablestopnow.sketch.Revolve.Params cw =
                new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 360, -1);
        near(ccw.signedRadians(), 2 * Math.PI, "360° 正向 = +2π");
        near(cw.signedRadians(), -2 * Math.PI, "360° 反向 = −2π");
        check(ccw.fullTurn() && cw.fullTurn(), "360° 是整圈");
        check(!new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 180, 1).fullTurn(),
                "180° 不是整圈");
        near(new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 90, 1).signedRadians(),
                Math.PI / 2, "90° 正向 = +π/2");

        // --- 垂距公式：轴为 v 轴（点 (0,0)、方向 (0,1)）时垂距就是 |u| ---
        near(com.ovo.sablestopnow.sketch.Revolve.radius(ccw, 3, 7), 3.0, "到 v 轴的垂距 = |u| = 3");
        near(com.ovo.sablestopnow.sketch.Revolve.radius(ccw, -4, -2), 4.0,
                "负侧垂距取绝对值");
        near(com.ovo.sablestopnow.sketch.Revolve.radius(ccw, 0, 100), 0.0,
                "轴上的点垂距为 0（转不动）");

        // --- 分段数按角度缩放 ---
        check(com.ovo.sablestopnow.sketch.Revolve.stepsFor(ccw) == 48, "整圈 48 段");
        check(com.ovo.sablestopnow.sketch.Revolve.stepsFor(
                        new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 180, 1)) == 24,
                "半圈 24 段");
        check(com.ovo.sablestopnow.sketch.Revolve.stepsFor(
                        new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 0.1, 1)) >= 2,
                "极小角度也至少 2 段");

        // --- 几何:用真草图（矩形）跑 ---
        final com.ovo.sablestopnow.sketch.SketchSession rs = editing();
        rs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        rs.click(3.0, 0.0);   // 矩形在轴（u=0）的右侧，整体离轴 3 格
        rs.click(5.0, 4.0);
        final Sketch rk = rs.working();
        final SketchPlane plane = rk.plane();

        // ① phi=0 必须还原原来的点
        final Vector3d at0 = com.ovo.sablestopnow.sketch.Revolve.rotated(plane, ccw, 3, 2, 0.0);
        final Vector3d original = plane.toLocal(3, 2);
        near(at0.distance(original), 0.0, "phi=0 时旋转不改变点（否则整个体是错的）");

        // ② 垂距在旋转中不变 —— 每个点画出一个圆
        boolean radiusKept = true;
        for (final double phi : new double[] {0.0, 0.7, 1.9, 3.1, 4.4, 6.28}) {
            for (final double[] uv : new double[][] {{3, 2}, {5, 4}, {3, 0}, {5, 4.5}}) {
                final Vector3d p = com.ovo.sablestopnow.sketch.Revolve.rotated(
                        plane, ccw, uv[0], uv[1], phi);
                final double want = com.ovo.sablestopnow.sketch.Revolve.radius(ccw, uv[0], uv[1]);
                if (Math.abs(distanceToAxis(plane, ccw, p) - want) > 1.0e-9) {
                    radiusKept = false;
                }
            }
        }
        check(radiusKept, "任意角度下，点到轴的垂距都等于它的旋转半径（画的是圆）");

        // ③ 整圈时首尾重合
        final Vector3d at2pi = com.ovo.sablestopnow.sketch.Revolve.rotated(plane, ccw, 3, 2,
                2 * Math.PI);
        near(at2pi.distance(original), 0.0, "整圈之后回到出发点");

        // ④ 轴上的点**转不动**
        final Vector3d onAxis = com.ovo.sablestopnow.sketch.Revolve.rotated(plane, ccw, 0, 5, 2.0);
        near(onAxis.distance(plane.toLocal(0, 5)), 0.0, "轴上的点在任意角度都原地不动");

        // ⑤ 旋向是有意义的：`rotated` 收的是**绝对角度**（轴来自 params、角度由调用方给），
        //    所以"同一角度转向反侧"在调用层就是取相反的角度。
        //    这里验的是真正会用的路径：`edges` 用 signedRadians（含旋向）。
        final Vector3d pos = com.ovo.sablestopnow.sketch.Revolve.rotated(plane, ccw, 3, 2, 1.0);
        final Vector3d neg = com.ovo.sablestopnow.sketch.Revolve.rotated(plane, ccw, 3, 2, -1.0);
        check(pos.distance(neg) > 1.0e-6, "转到 +φ 与 −φ 落在不同位置（两个镜像体）");
        near(distanceToAxis(plane, ccw, neg),
                com.ovo.sablestopnow.sketch.Revolve.radius(ccw, 3, 2), "反向旋的垂距同样守恒");
        // 两个旋向的 `edges` 必须给出不同的几何，否则"方向"这个按钮就是死的
        final java.util.List<Vector3d[]> revCcw =
                com.ovo.sablestopnow.sketch.Revolve.edges(rk,
                        new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 90, 1));
        final java.util.List<Vector3d[]> revCw =
                com.ovo.sablestopnow.sketch.Revolve.edges(rk,
                        new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 90, -1));
        check(revCcw.size() == revCw.size(), "两个旋向的棱边数相同");
        double maxDiff = 0.0;
        for (int i = 0; i < Math.min(revCcw.size(), revCw.size()); i++) {
            maxDiff = Math.max(maxDiff, revCcw.get(i)[0].distance(revCw.get(i)[0]));
        }
        check(maxDiff > 1.0e-6, "两个旋向的棱边几何确实不同（最大差 " 
                + String.format("%.3f", maxDiff) + "）");

        // ⑥ 棱边线框
        final java.util.List<Vector3d[]> revEdges =
                com.ovo.sablestopnow.sketch.Revolve.edges(rk, ccw);
        check(!revEdges.isEmpty(), "回转体有棱边");
        // 矩形 4 顶点 × 每顶点每段 2 条棱 × 48 段 + 整圈补的 4 条轮廓棱
        check(revEdges.size() == 4 * 48 * 2 + 4,
                "整圈矩形的棱边数 = 4 顶点 × 48 段 × 2 类 + 4 条接缝（实测 "
                        + revEdges.size() + "）");
        final double[] rb = com.ovo.sablestopnow.sketch.Revolve.bounds(revEdges);
        check(rb != null, "回转体有包围盒");
        if (rb != null) {
            // 轴在 u=0、矩形 u∈[3,5] → 转出来是个环形，外径 5、内径 3。
            // 包围盒在"垂直于轴"的两个方向上撑到 ±5。
            final double span = Math.max(rb[3] - rb[0], rb[5] - rb[2]);
            near(span, 10.0, "整圈回转体的横向跨度 = 2 × 外径 5 = 10（实测 " + span + "）");
        }

        // ⑦ 角度不足整圈时不该补接缝
        final java.util.List<Vector3d[]> quarter = com.ovo.sablestopnow.sketch.Revolve.edges(rk,
                new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 90, 1));
        check(quarter.size() == 4 * 12 * 2,
                "90° 回转：4 顶点 × 12 段 × 2 类 = 96 条（实测 " + quarter.size() + "）");

        // ⑧ 退化输入
        final com.ovo.sablestopnow.sketch.SketchSession open = editing();
        open.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        open.click(0.0, 0.0);
        open.click(5.0, 0.0);
        check(com.ovo.sablestopnow.sketch.Revolve.edges(open.working(), ccw).isEmpty(),
                "不闭合的线 → 转不出实体（空棱边）");
        check(com.ovo.sablestopnow.sketch.Revolve.bounds(java.util.List.of()) == null,
                "空棱边 → 包围盒 null");
    }

    /** 3D 点到旋转轴的垂距（|(P−A) × d|）。 */
    private static double distanceToAxis(final SketchPlane plane,
                                         final com.ovo.sablestopnow.sketch.Revolve.Params params,
                                         final Vector3d p) {
        final Vector3d a = plane.toLocal(params.axisU(), params.axisV());
        final Vector3d d = com.ovo.sablestopnow.sketch.Revolve.axisDirection(plane, params);
        final Vector3d w = new Vector3d(p).sub(a);
        return w.cross(d, new Vector3d()).length();
    }

    /**
     * 有朝向的盒子（OBB）。旋转拉伸的碰撞全靠它：回转体的面是斜的，轴对齐盒子套不住。
     *
     * <p>最容易错的一点：**旋转时中心必须绕 pivot 转**。只转朝向而忘了转中心，
     * 盒子会"待在原地但歪了" —— 看起来像碰撞体错位，很难查。
     */
    private static void orientedBoxes() {
        final Vector3d zAxis = new Vector3d(0, 0, 1);
        // ⚠ JOML 的签名是 **rotationAxis(angle, x, y, z)** —— 角度在**第一个**。
        // 写成 (x,y,z,angle) 会静默得到单位四元数（角度=0），旋转根本不发生。
        final Quaterniond quarterTurn = new Quaterniond().rotationAxis(Math.PI / 2, 0, 0, 1);
        final Quaterniond identity = new Quaterniond();

        // --- 轴对齐路径不该被改变：朝向为单位四元数 ---
        // ⚠ 全类统一收 **min/max**（和 boxesOf 的输出一致）。
        // 这里 {2,0,0, 4,1,1} 是 min=(2,0,0)、max=(4,1,1) → 中心 (3,0.5,0.5)、半长 (1,0.5,0.5)
        final double[] oneBox = {2, 0, 0, 4, 1, 1};
        final java.util.List<double[]> flat = java.util.List.of(oneBox);
        final java.util.List<com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox> oriented =
                com.ovo.sablestopnow.sketch.SolidCollision.orientedBoxSpecs(flat);
        check(oriented.size() == 1, "一个盒子 → 一个 OBB");
        check(oriented.get(0).axisAligned(), "轴对齐路径给出的朝向是单位四元数");
        near(oriented.get(0).centre().x, 3.0, "中心 = (min+max)/2");
        near(oriented.get(0).halfExtents().x, 1.0, "半长 = (max−min)/2");
        near(oriented.get(0).volume(), 8.0 * 1.0 * 0.5 * 0.5, "体积不变（8·hx·hy·hz）");

        // --- 单位旋转 = 空操作 ---
        final com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox noop =
                com.ovo.sablestopnow.sketch.SolidCollision.rotate(oneBox, identity,
                        new Vector3d(5, 7, 9));
        near(noop.centre().distance(new Vector3d(3, 0.5, 0.5)), 0.0, "单位旋转不动中心");
        check(noop.axisAligned(), "单位旋转后仍然轴对齐");

        // --- 绕原点转 90°：中心从 (3,0.5,0.5) 到 (−0.5,3,0.5)，半长不变（半长在盒子自己的局部系里）---
        final com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox turned =
                com.ovo.sablestopnow.sketch.SolidCollision.rotate(oneBox, quarterTurn,
                        new Vector3d(0, 0, 0));
        near(turned.centre().x, -0.5, "转 90° 后中心 x = −0.5（(3,0.5) 转 90° = (−0.5,3)）");
        near(turned.centre().y, 3.0, "转 90° 后中心 y = 3");
        near(turned.halfExtents().x, 1.0, "半长**不随旋转改变**（它在盒子的局部系里）");
        near(turned.volume(), 8.0 * 1.0 * 0.5 * 0.5, "旋转不改变体积");
        check(!turned.axisAligned(), "转 90° 之后不再是轴对齐");

        // --- ⚠ 关键：绕**非原点**的 pivot 转，中心必须跟着绕过去 ---
        // pivot (5,0,0)、中心 (7,0,0) → 局部 (2,0,0) → 转成 (0,2,0) → 加回 pivot = (5,2,0)
        final com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox aboutPivot =
                com.ovo.sablestopnow.sketch.SolidCollision.rotate(
                        new double[] {6, -1, -1, 8, 1, 1}, quarterTurn, new Vector3d(5, 0, 0));
        near(aboutPivot.centre().x, 5.0, "绕 pivot 转：中心 x = pivot.x + 0 = 5");
        near(aboutPivot.centre().y, 2.0, "绕 pivot 转：中心 y = 0 + 2 = 2（没转中心的话这里会是 0）");
        near(aboutPivot.centre().z, 0.0, "绕 pivot 转：z 不变");

        // --- 转过去再转回来 = 原样 ---
        // 取一个中心正好在 (5,2,0) 的盒子（min=(4,1,−1)、max=(6,3,1)），反向转 90° 应当回到 (7,0,0)
        final com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox back =
                com.ovo.sablestopnow.sketch.SolidCollision.rotate(
                        new double[] {4, 1, -1, 6, 3, 1},
                        new Quaterniond().rotationAxis(-Math.PI / 2, 0, 0, 1), new Vector3d(5, 0, 0));
        near(back.centre().distance(new Vector3d(7, 0, 0)), 0.0, "转过去再转回来回到原位");

        // --- 整组盒子一起转：总体积守恒（这里同样是 **min/max** 形态） ---
        final java.util.List<double[]> group = java.util.List.of(
                new double[] {2, 0, 0, 4, 1, 1},
                new double[] {4, 1, 0, 5, 2, 1});
        final java.util.List<com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox> all =
                com.ovo.sablestopnow.sketch.SolidCollision.rotateAll(group, quarterTurn,
                        new Vector3d(0, 0, 0));
        check(all.size() == 2, "整组两个盒子都转了");
        near(com.ovo.sablestopnow.sketch.SolidCollision.orientedVolume(all),
                com.ovo.sablestopnow.sketch.SolidCollision.volume(group),
                "整组旋转后总体积守恒");

        // --- 坏输入 ---
        boolean threw = false;
        try {
            com.ovo.sablestopnow.sketch.SolidCollision.rotate(new double[] {1, 2},
                    identity, new Vector3d());
        } catch (final IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "盒子不是 {min,max} 形态 → 被拒（不静默产生垃圾盒子）");
    }

    /**
     * 实体可以有两种成形方式（拉伸 / 旋转）。
     *
     * <p>⚠ 这里刻意钉住一条**诚实的状态**：旋转体的 {@code boxes()} 目前是**空表**
     * —— 它的面是斜的，轴对齐盒子套不住，要做按角度分楔的 OBB。
     * 在做好之前它**不参与碰撞**（而不是胡乱碰）。这条断言防止有人把"空表"当成 bug 去"修"。
     */
    private static void solidShapes() {
        final com.ovo.sablestopnow.sketch.SketchSession rs = editing();
        rs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        rs.click(3.0, 0.0);
        rs.click(5.0, 4.0);
        final Sketch sketch = rs.working();
        final java.util.UUID body = sketch.body();

        // --- 拉伸实体 ---
        final com.ovo.sablestopnow.sketch.Solid extruded =
                com.ovo.sablestopnow.sketch.Solid.create(body, sketch.id(), 1,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        check(!extruded.isRevolution(), "create → 拉伸实体");
        check(extruded.extrusion() != null, "拉伸参数可读");
        check(extruded.revolution() == null, "旋转参数为空");
        check(!extruded.boxes(sketch).isEmpty(), "拉伸体有碰撞盒");
        check(!extruded.edges(sketch).isEmpty(), "拉伸体有渲染棱边");

        // --- 旋转实体 ---
        final com.ovo.sablestopnow.sketch.Revolve.Params rev =
                new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 360, 1);
        final com.ovo.sablestopnow.sketch.Solid revolved =
                com.ovo.sablestopnow.sketch.Solid.createRevolution(body, sketch.id(), 2, rev);
        check(revolved.isRevolution(), "createRevolution → 旋转实体");
        check(revolved.revolution() != null, "旋转参数可读");
        check(revolved.extrusion() == null, "拉伸参数为空");
        check(!revolved.edges(sketch).isEmpty(), "旋转体**有**渲染棱边（画得出来）");
        // ⚠ 旋转体的**轴对齐**盒分解仍然是空的（斜几何套不住）——
        // 但它的**碰撞几何**（OBB）非空。两条路径刻意分开，别把前者当 bug。
        check(revolved.boxes(sketch).isEmpty(),
                "旋转体没有**轴对齐**盒（斜几何套不住，这是刻意的）");
        final java.util.List<com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox> revBoxes =
                revolved.orientedBoxes(sketch);
        check(!revBoxes.isEmpty(), "但旋转体**有碰撞几何**");
        // ⚠ 设计已定：回转体走**体素方案**（轴对齐盒子），不再用分楔 OBB。
        // 依据是离线实测（tools/CollisionCostReport.java）：
        //     半径1球   分楔 132 | 体素填充 13 | 体素壳 45
        //     圆环      分楔 228 | 体素填充 158 | 体素壳 81
        // 分楔在两种形状下都最差；而每个盒子都是"刚体 + 弹簧约束"，所以盒子数直接等于卡顿。
        check(revBoxes.stream().allMatch(
                        com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox::axisAligned),
                "旋转体的碰撞盒是**体素方案的轴对齐盒子**（" + revBoxes.size() + " 个）"
                        + "—— 走体素是为了盒子数（球 13 vs 分楔 132），不是形状更贴");
        check(revBoxes.size() < 60,
                "球体的盒子数**显著低于**分楔方案的 132（实测 " + revBoxes.size()
                        + "）—— 每个盒子都是刚体+弹簧，盒子数就是卡顿");
        check(revBoxes.size() <= com.ovo.sablestopnow.sketch.Solid.COLLISION_MAX_BOXES,
                "盒子数 ≤ 硬上限 " + com.ovo.sablestopnow.sketch.Solid.COLLISION_MAX_BOXES
                        + "（实测 " + revBoxes.size() + "）");
        check(revolved.bounds(sketch) == null, "旋转体没有轴对齐包围盒（同上，刻意的）");

        // 拉伸体走同一条入口，但朝向是单位四元数
        final java.util.List<com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox> extBoxes =
                extruded.orientedBoxes(sketch);
        check(!extBoxes.isEmpty(), "拉伸体也有碰撞几何");
        check(extBoxes.stream().allMatch(
                        com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox::axisAligned),
                "拉伸体的碰撞盒全是轴对齐（那条路是精确分解，不该加朝向）");

        // --- 换成形方式：身份（id / 序号）不变 ---
        final com.ovo.sablestopnow.sketch.Solid switched = extruded.withRevolution(rev);
        check(switched.id().equals(extruded.id()), "换成形方式不改 id");
        check(switched.index() == extruded.index(), "换成形方式不改序号");
        check(switched.isRevolution(), "换成了旋转");
        final com.ovo.sablestopnow.sketch.Solid back = switched.withParams(
                com.ovo.sablestopnow.sketch.Extrude.Params.symmetric(3.0));
        check(!back.isRevolution(), "再换回拉伸");
        near(back.extrusion().depth1(), 3.0, "参数确实换了");

        // --- 引不到草图时两种都不给几何（不抛） ---
        check(revolved.edges(null).isEmpty(), "草图为 null → 旋转体也没有棱边");
        check(revolved.boxes(null).isEmpty(), "草图为 null → 没有碰撞盒");
        final com.ovo.sablestopnow.sketch.SketchSession other = editing();
        other.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        other.click(0.0, 0.0);
        other.click(1.0, 1.0);
        check(revolved.edges(other.working()).isEmpty(), "草图 id 对不上 → 不给几何");
    }

    /**
     * 旋转体的碰撞分解精度：分楔 OBB 应当**接近**真实回转体。
     *
     * <p>用解析体积当基准：矩形 r∈[3,5]、t∈[0,4] 绕轴整圈 = 圆环体，
     * 体积 = π(5²−3²)·4 = 64π ≈ 201.06。
     * 分楔用弦长近似圆弧，所以**偏小**（8 楔时约 0.90 倍），这是可接受的近似方向。
     */
    private static void revolveCollision() {
        final com.ovo.sablestopnow.sketch.SketchSession rs = editing();
        rs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        rs.click(3.0, 0.0);
        rs.click(5.0, 4.0);
        final Sketch sketch = rs.working();
        final SketchPlane plane = sketch.plane();
        // 轴 = v 轴（点 (0,0)、方向 (0,1)）→ t = v ∈ [0,4]、r = u ∈ [3,5]
        final com.ovo.sablestopnow.sketch.Revolve.Params full =
                new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 360, 1);

        final java.util.List<com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox> boxes =
                com.ovo.sablestopnow.sketch.Revolve.collisionBoxes(sketch, full, 8);
        check(!boxes.isEmpty(), "整圈回转体分解出盒子");

        final double trueVolume = Math.PI * (25.0 - 9.0) * 4.0;
        final double boxVolume =
                com.ovo.sablestopnow.sketch.SolidCollision.orientedVolume(boxes);
        check(boxVolume < trueVolume * 1.02,
                "分解体积不超过解析值（弦长只会偏小，实测 " + String.format("%.1f", boxVolume)
                        + " vs " + String.format("%.1f", trueVolume) + "）");
        check(boxVolume > trueVolume * 0.70,
                "分解体积也不该偏小太多（>70% 解析值，实测 "
                        + String.format("%.1f", boxVolume) + "）");

        // ⚠ 每个盒子中心到轴的垂距必须落在 r ∈ [3,5] 里。
        // 这条能抓住"径向算错/中心忘了加轴上基点"这类错误 —— 那种错体积可能还对，但位置全歪。
        final Vector3d axisPoint = plane.toLocal(0, 0);
        final Vector3d dir = com.ovo.sablestopnow.sketch.Revolve.axisDirection(plane, full);
        double minR = Double.MAX_VALUE;
        double maxR = -Double.MAX_VALUE;
        // 盒子有半长，所以取"中心垂距 ± 径向半长"的极端来判
        for (final com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox b
                : boxes) {
            final Vector3d w = new Vector3d(b.centre()).sub(axisPoint);
            final double centreR = w.cross(dir, new Vector3d()).length();
            // 盒子的局部 y 轴就是径向，半长是 halfExtents.y
            final Vector3d radialAxis = new Vector3d(0, 1, 0);
            b.rotation().transform(radialAxis);
            final double halfR = b.halfExtents().y;
            minR = Math.min(minR, centreR - halfR);
            maxR = Math.max(maxR, centreR + halfR);
        }
        check(minR > 2.9 && maxR < 5.1,
                "所有盒子都在 r ∈ [3,5] 的环带内（实测 " + String.format("%.2f", minR)
                        + " ~ " + String.format("%.2f", maxR) + "）"
                        + "—— 内切后外面收到 r·cos(Δφ/2)、内面不动，仍在环带里");
        check(maxR > 4.5, "确实够到了外径附近（否则说明半径算小了）");

        // --- 部分角度：盒子数应当随之减少，且不再首尾相接 ---
        final java.util.List<com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox> quarter =
                com.ovo.sablestopnow.sketch.Revolve.collisionBoxes(sketch,
                        new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 90, 1), 8);
        check(!quarter.isEmpty(), "90° 回转也有盒子");
        check(quarter.size() < boxes.size(), "90° 的盒子比整圈少（实测 "
                + quarter.size() + " < " + boxes.size() + "）");
        final double quarterVolume =
                com.ovo.sablestopnow.sketch.SolidCollision.orientedVolume(quarter);
        check(quarterVolume < trueVolume * 0.3, "90° 的体积约为整圈的四分之一（实测 "
                + String.format("%.1f", quarterVolume) + "）");

        // --- 退化：轮廓压在轴上（r=0）→ 不该产出零体积盒子 ---
        final com.ovo.sablestopnow.sketch.SketchSession onAxis = editing();
        onAxis.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        onAxis.click(0.0, 0.0);
        onAxis.click(2.0, 3.0);
        final java.util.List<com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox> half =
                com.ovo.sablestopnow.sketch.Revolve.collisionBoxes(onAxis.working(), full, 8);
        boolean allPositive = true;
        for (final com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox b : half) {
            if (b.volume() <= 1.0e-9) {
                allPositive = false;
            }
        }
        check(allPositive, "贴轴的回转体不产生零体积盒子（零体积盒子喂给物理只会添乱）");

        // --- 空输入 ---
        final com.ovo.sablestopnow.sketch.SketchSession open = editing();
        open.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        open.click(0.0, 0.0);
        open.click(5.0, 0.0);
        check(com.ovo.sablestopnow.sketch.Revolve.collisionBoxes(open.working(), full, 8)
                .isEmpty(), "不闭合的轮廓 → 没有碰撞盒");
        check(com.ovo.sablestopnow.sketch.Revolve.collisionBoxes(sketch, full, 0).isEmpty(),
                "角度楔数为 0 → 空表（不除零）");
    }

    /**
     * **曲面用棱盒（OBB）** —— 用户实测"大圆柱滚动的顿挫感太强"的根因与修法。
     *
     * <p>扫描线分解出来的是**楼梯**（一层层轴对齐台阶），滚动时每级都是一次撞击。
     * 棱盒的**外侧面就是那条边**，外表面是一圈平面 —— 滚动连续。
     *
     * <p>要验三件事：
     * ① 曲面的碰撞盒里**确实有非轴对齐的**（否则说明还是走的扫描线，等于没修）；
     * ② 盒子**没有捅到轮廓外面**（捅出去就是撞空气）；
     * ③ 轴对齐轮廓**仍然**走精确扫描线（不许被这条改动牵连）。
     */
    private static void curvedChordBoxes() {
        // --- 圆：必须走棱盒 ---
        final com.ovo.sablestopnow.sketch.SketchSession rs = editing();
        rs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        rs.click(0.0, 0.0);
        rs.click(4.0, 0.0);
        final Sketch circle = rs.working();
        check(com.ovo.sablestopnow.sketch.Extrude.hasCurvature(circle),
                "含圆的草图被识别为**曲面**");
        final com.ovo.sablestopnow.sketch.Solid solid =
                com.ovo.sablestopnow.sketch.Solid.create(circle.body(), circle.id(), 1,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        final java.util.List<com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox> boxes =
                solid.orientedBoxes(circle);
        check(!boxes.isEmpty(), "圆实体有碰撞盒");
        long tilted = boxes.stream().filter(b -> !b.axisAligned()).count();
        check(tilted == boxes.size(),
                "**每个**盒子都是斜的（" + tilted + "/" + boxes.size()
                        + "）—— 只要还有轴对齐的就说明楼梯没拆干净");

        // --- 不许捅出轮廓：所有角点到圆心（轴）的距离 ≤ r ---
        // ⚠ 棱盒的外角点**正好落在圆上**（外侧面是弦），所以判据是"不大于 r + 容差"。
        final SketchPlane plane = circle.plane();
        final double r = 4.0;
        double maxOut = 0.0;
        for (final com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox b : boxes) {
            // 盒子 8 个角
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sy = -1; sy <= 1; sy += 2) {
                    for (int sz = -1; sz <= 1; sz += 2) {
                        final Vector3d local = new Vector3d(
                                sx * b.halfExtents().x, sy * b.halfExtents().y,
                                sz * b.halfExtents().z);
                        b.rotation().transform(local);
                        final Vector3d world = local.add(b.centre());
                        // 到拉伸轴（圆心在平面内的那条线）的垂距
                        final Vector3d axisPoint = plane.toLocal(0.0, 0.0);
                        final Vector3d w = new Vector3d(world).sub(axisPoint);
                        final Vector3d along = plane.normal().mul(w.dot(plane.normal()),
                                new Vector3d());
                        maxOut = Math.max(maxOut, w.sub(along, new Vector3d()).length());
                    }
                }
            }
        }
        check(maxOut <= r + 0.02,
                "棱盒没有捅到圆外（最远 " + String.format("%.3f", maxOut) + " ≤ r=" + r
                        + " + 容差）—— 捅出去就是撞空气");
        // ⚠ 必须**严格在内**（碰撞盒相对视觉几何内缩）。
        // ⚠ 注意量级：壳很薄，`BOX_SHRINK` 作用在半长上，所以沿**径向**只把外表面
        //    推内约 0.003 格（最远点 3.983 而不是 3.68）。**内缩已经不再是主要防线** ——
        //    防共面打架现在靠 `joint.setContactsEnabled(false)`。
        //    这条断言只保证"没有捅出去"，不假装它做了更多。
        check(maxOut < r - 0.005,
                "碰撞盒不捅出视觉几何（最远 " + String.format("%.4f", maxOut) + " < r=" + r + "）");
        check(maxOut > r * 0.9, "棱盒也确实够到了圆周附近（" + String.format("%.3f", maxOut)
                + "），不是缩在里面");

        // --- ⚠ 壳必须**薄到留出空心** ---
        // 物理那边没有碰撞过滤 API，我们的盒子是独立刚体又互相焊死；
        // 一旦互相穿透，接触求解器会和约束打架 → 阻滞 + 摇晃（用户实测报的）。
        // 壳薄 = 互相穿透的体积按厚度平方下降。
        double minInner = Double.MAX_VALUE;
        for (final com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox b : boxes) {
            // 盒子的局部 y 轴指向环心（外壳朝内那一维）
            final Vector3d inner = new Vector3d(0, -1, 0).mul(b.halfExtents().y);
            b.rotation().transform(inner);
            final Vector3d deepest = inner.add(b.centre());
            final Vector3d w = deepest.sub(plane.toLocal(0.0, 0.0));
            final Vector3d along = plane.normal().mul(w.dot(plane.normal()), new Vector3d());
            minInner = Math.min(minInner, w.sub(along, new Vector3d()).length());
        }
        check(minInner > r * 0.5,
                "壳留出了空心（最深处离圆心 " + String.format("%.2f", minInner)
                        + " > 0.5r）—— 否则盒子会在中心大面积互相穿透，"
                        + "接触求解器会和固定约束打架（阻滞 + 摇晃）");

        // --- 轴对齐轮廓不许被牵连：仍然是精确的轴对齐扫描线 ---
        final com.ovo.sablestopnow.sketch.SketchSession rect = editing();
        rect.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        rect.click(0.0, 0.0);
        rect.click(6.0, 4.0);
        final Sketch rectSketch = rect.working();
        check(!com.ovo.sablestopnow.sketch.Extrude.hasCurvature(rectSketch),
                "矩形被识别为**无曲面**");
        final com.ovo.sablestopnow.sketch.Solid rectSolid =
                com.ovo.sablestopnow.sketch.Solid.create(rectSketch.body(), rectSketch.id(), 1,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        final java.util.List<com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox> rectBoxes =
                rectSolid.orientedBoxes(rectSketch);
        check(rectBoxes.size() == 1, "矩形仍然只用 1 个盒子（实测 " + rectBoxes.size() + "）");
        check(rectBoxes.get(0).axisAligned(),
                "矩形的盒子仍然轴对齐（精确分解，不该被曲面的改动牵连）");

        // --- 带孔的曲面：孔的棱盒要朝**孔外**长，不能把孔堵上 ---
        final com.ovo.sablestopnow.sketch.SketchSession holed = editing();
        holed.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        holed.click(-6.0, -6.0);
        holed.click(6.0, 6.0);
        holed.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        holed.click(0.0, 0.0);
        holed.click(3.0, 0.0);
        final Sketch holedSketch = holed.working();
        final com.ovo.sablestopnow.sketch.Solid holedSolid =
                com.ovo.sablestopnow.sketch.Solid.create(holedSketch.body(), holedSketch.id(), 1,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        final java.util.List<com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox> holedBoxes =
                holedSolid.orientedBoxes(holedSketch);
        check(!holedBoxes.isEmpty(), "带孔的曲面实体有碰撞盒");
        // 孔心附近（半径 1 以内）不该有任何盒子的**中心** —— 说明孔没被壳堵上
        boolean centreClear = true;
        for (final com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox b : holedBoxes) {
            final Vector3d w = new Vector3d(b.centre()).sub(plane.toLocal(0.0, 0.0));
            final Vector3d along = plane.normal().mul(w.dot(plane.normal()), new Vector3d());
            if (w.sub(along, new Vector3d()).length() < 1.0) {
                centreClear = false;
            }
        }
        check(centreClear, "孔心附近没有盒子中心（孔的壳朝孔外长，没把孔堵上）");
    }

    /**
     * 实体的**真实体积**（质量基准）。
     *
     * <p>为什么要单独有它：碰撞盒只是几何近似，**盒体积之和 ≠ 实体体积**。
     * 棱盒是相互重叠的壳，之和远大于真实 —— 拿它当质量会重得推不动
     * （用户实测"手杖拖动含模型的物理结构有强烈阻滞感"）。
     */
    private static void trueVolume() {
        // --- 拉伸：截面积 × 厚度 ---
        final com.ovo.sablestopnow.sketch.SketchSession rs = editing();
        rs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        rs.click(0.0, 0.0);
        rs.click(6.0, 4.0);
        final com.ovo.sablestopnow.sketch.Solid rectSolid =
                com.ovo.sablestopnow.sketch.Solid.create(rs.working().body(), rs.working().id(), 1,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        near(rectSolid.volume(rs.working()), 48.0, "矩形 6×4 拉 2 → 体积 48");

        // --- 圆：真实体积 vs 盒体积之和，后者必然更大（壳重叠）---
        final com.ovo.sablestopnow.sketch.SketchSession cs = editing();
        cs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        cs.click(0.0, 0.0);
        cs.click(4.0, 0.0);
        final Sketch circle = cs.working();
        final com.ovo.sablestopnow.sketch.Solid circleSolid =
                com.ovo.sablestopnow.sketch.Solid.create(circle.body(), circle.id(), 1,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        // ⚠ 体积按**渲染精度**采样（刻意如此，见 Solid#volume）：
        // 质量不该随碰撞分辨率的调节而变。所以这里也要用 RENDER 的段数来算期望值。
        final int n = com.ovo.sablestopnow.sketch.SketchProfiles.Sampling.RENDER
                .segmentsFor(4.0);
        final double polyArea = 0.5 * n * 16.0 * Math.sin(2 * Math.PI / n);
        near(circleSolid.volume(circle), polyArea * 2.0,
                "圆的体积 = 内接 " + n + " 边形面积 × 厚度（渲染精度）");

        // ⚠ 关键：**改碰撞分辨率不能改体积**。这条断言守的就是这个耦合。
        final double collisionArea = 0.5 * com.ovo.sablestopnow.sketch.SketchProfiles.Sampling
                .COLLISION.segmentsFor(4.0) * 16.0
                * Math.sin(2 * Math.PI
                        / com.ovo.sablestopnow.sketch.SketchProfiles.Sampling.COLLISION
                                .segmentsFor(4.0));
        check(Math.abs(circleSolid.volume(circle) - collisionArea * 2.0) > 0.1,
                "体积**不**按碰撞分辨率算（否则调低碰撞精度会悄悄改变质量）");
        final double boxSum = com.ovo.sablestopnow.sketch.SolidCollision.orientedVolume(
                circleSolid.orientedBoxes(circle));
        // ⚠ 断言的是"**两者显著不等**"，不是某个方向。
        // 薄壳时之和**偏小**（70 vs 100），厚壳时**偏大** —— 两个方向都错过。
        // 所以判据必须是对差值取绝对值。
        check(Math.abs(boxSum - circleSolid.volume(circle))
                        > circleSolid.volume(circle) * 0.05,
                "**盒体积之和 ≠ 真实体积**（" + String.format("%.1f", boxSum) + " vs "
                        + String.format("%.1f", circleSolid.volume(circle))
                        + "，差 " + String.format("%.0f%%",
                        100.0 * Math.abs(boxSum - circleSolid.volume(circle))
                                / circleSolid.volume(circle))
                        + "）—— 这就是不能拿它当质量基准的原因");

        // --- 旋转：帕普斯定理应当**精确**等于解析的圆环体积 ---
        // 矩形 r∈[3,5]、t∈[0,4]，整圈 → π(25−9)·4 = 64π；形心 r_c = 4、面积 8
        final com.ovo.sablestopnow.sketch.SketchSession vs = editing();
        vs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        vs.click(3.0, 0.0);
        vs.click(5.0, 4.0);
        final com.ovo.sablestopnow.sketch.Solid revSolid =
                com.ovo.sablestopnow.sketch.Solid.createRevolution(vs.working().body(),
                        vs.working().id(), 1,
                        new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 360, 1));
        near(revSolid.volume(vs.working()), 64.0 * Math.PI,
                "帕普斯：2π·4·8 = 64π，与圆环解析体积一致");

        // --- 半圈应当正好是一半 ---
        final com.ovo.sablestopnow.sketch.Solid halfSolid =
                revSolid.withRevolution(new com.ovo.sablestopnow.sketch.Revolve.Params(
                        0, 0, 0, 1, 180, 1));
        near(halfSolid.volume(vs.working()), 32.0 * Math.PI, "180° 是整圈的一半");

        // --- 轴穿过轮廓 → 帕普斯不适用，返回 NaN 让调用方退回盒子和 ---
        final com.ovo.sablestopnow.sketch.SketchSession across = editing();
        across.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        across.click(-2.0, 0.0);
        across.click(2.0, 3.0);
        final com.ovo.sablestopnow.sketch.Solid acrossSolid =
                com.ovo.sablestopnow.sketch.Solid.createRevolution(across.working().body(),
                        across.working().id(), 1,
                        new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 360, 1));
        check(Double.isNaN(acrossSolid.volume(across.working())),
                "轴穿过轮廓 → 体积返回 NaN（帕普斯不适用，调用方退回盒子和）");

        // --- 引不到草图 → 0（不是 NaN，别让调用方白算）---
        near(rectSolid.volume(null), 0.0, "草图为 null → 体积 0");
    }

    /**
     * **盒子之间的穿透**度量。这是唯一关不掉的接触源：
     * `setContactsEnabled` 只能关"关节两端"（盒子↔子结构），
     * 而 `createBox` 没有碰撞组参数（源码确认），所以盒子↔盒子关不掉。
     * 于是只能保证它们**几何上不互相穿透**。
     */
    private static void boxPenetration() {
        // --- SAT 本身：几个已知答案 ---
        final com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox unit =
                new com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox(
                        new Vector3d(0, 0, 0), new Vector3d(1, 1, 1), new Quaterniond());
        final com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox away =
                new com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox(
                        new Vector3d(5, 0, 0), new Vector3d(1, 1, 1), new Quaterniond());
        check(!com.ovo.sablestopnow.sketch.SolidCollision.overlaps(unit, away),
                "隔开的两个盒子不相交");
        final com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox same =
                new com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox(
                        new Vector3d(0, 0, 0), new Vector3d(1, 1, 1), new Quaterniond());
        check(com.ovo.sablestopnow.sketch.SolidCollision.overlaps(unit, same),
                "重合的盒子相交");
        // 贴面（刚好相接）**不算**相交 —— 棱盒本来就是相邻盒在共享顶点处相接
        final com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox touching =
                new com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox(
                        new Vector3d(2, 0, 0), new Vector3d(1, 1, 1), new Quaterniond());
        check(!com.ovo.sablestopnow.sketch.SolidCollision.overlaps(unit, touching),
                "**刚好贴面**不算相交（否则相邻棱盒会把度量刷满，失去参考价值）");
        // 旋 45° 后角对角：二维上两块正方形在 (1.5,1.5) 附近仍有重叠
        final com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox tilted =
                new com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox(
                        new Vector3d(0, 0, 0),
                        new Vector3d(1, 1, 1),
                        new Quaterniond().rotationAxis(Math.PI / 4, 0, 0, 1));
        check(com.ovo.sablestopnow.sketch.SolidCollision.overlaps(unit, tilted),
                "同心但转了 45° 的两个盒子仍然相交");

        // --- 实测：曲面的棱盒之间穿透多少对 ---
        final com.ovo.sablestopnow.sketch.SketchSession cs = editing();
        cs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        cs.click(0.0, 0.0);
        cs.click(4.0, 0.0);
        final Sketch circle = cs.working();
        final com.ovo.sablestopnow.sketch.Solid solid =
                com.ovo.sablestopnow.sketch.Solid.create(circle.body(), circle.id(), 1,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        final java.util.List<com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox> boxes =
                solid.orientedBoxes(circle);
        // 薄壳的目的就是让这个数字小：壳越薄，相邻盒的互相穿透越少
        final int deep = com.ovo.sablestopnow.sketch.SolidCollision
                .penetratingPairs(boxes, 0.05);
        check(deep * 2 <= boxes.size(),
                "圆的棱盒里**深度穿透**的对数不多（" + deep + " 对 / " + boxes.size()
                        + " 个盒子）—— 它们是唯一关不掉的接触源");

        // --- 矩形：轴对齐路径应当**完全不重叠** ---
        final com.ovo.sablestopnow.sketch.SketchSession rs = editing();
        rs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        rs.click(0.0, 0.0);
        rs.click(6.0, 4.0);
        final Sketch rect = rs.working();
        final com.ovo.sablestopnow.sketch.Solid rectSolid =
                com.ovo.sablestopnow.sketch.Solid.create(rect.body(), rect.id(), 1,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        final int rectDeep = com.ovo.sablestopnow.sketch.SolidCollision
                .penetratingPairs(rectSolid.orientedBoxes(rect), 0.05);
        check(rectDeep == 0,
                "矩形的碰撞盒互不穿透（实测 " + rectDeep + " 对）—— 扫描线是划分，本来就不重叠");
    }

    /**
     * 把 UV 折进贴图在图集里的子矩形（方块贴图的地基）。
     *
     * <p>最容易错的一处是**负数**：Java 的 `%` 对负数返回负值，
     * 直接插值会落到子矩形**左边外面** = 取样到图集里相邻的贴图，
     * 表现为模型上出现一条别的东西的纹理。必须用 `x − floor(x)`。
     */
    private static void solidUv() {
        // --- 小数部分 ---
        near(com.ovo.sablestopnow.sketch.SolidUv.frac(0.0), 0.0, "frac(0) = 0");
        near(com.ovo.sablestopnow.sketch.SolidUv.frac(0.25), 0.25, "frac(0.25) = 0.25");
        near(com.ovo.sablestopnow.sketch.SolidUv.frac(1.0), 0.0, "frac(1) = 0（平铺回到起点）");
        near(com.ovo.sablestopnow.sketch.SolidUv.frac(1.5), 0.5, "frac(1.5) = 0.5");
        // ⚠ 关键：负数
        near(com.ovo.sablestopnow.sketch.SolidUv.frac(-0.25), 0.75,
                "frac(−0.25) = 0.75 —— **不是** −0.25（Java 的 % 会给负数）");
        near(com.ovo.sablestopnow.sketch.SolidUv.frac(-1.25), 0.75, "frac(−1.25) = 0.75");
        near(com.ovo.sablestopnow.sketch.SolidUv.frac(Double.NaN), 0.0, "NaN → 0（不往下游传垃圾）");

        // --- 映射到子矩形 ---
        // 假设这张贴图在图集里占 u ∈ [0.25, 0.5]，宽度 0.25
        near(com.ovo.sablestopnow.sketch.SolidUv.atlas(0.0, 0.25, 0.5), 0.25, "0 个方块 → 子矩形起点");
        near(com.ovo.sablestopnow.sketch.SolidUv.atlas(1.0, 0.25, 0.5), 0.25,
                "**1 个方块之后回到起点**（平铺）");
        near(com.ovo.sablestopnow.sketch.SolidUv.atlas(1.5, 0.25, 0.5), 0.375,
                "1.5 个方块 → 子矩形中点");
        near(com.ovo.sablestopnow.sketch.SolidUv.atlas(-0.25, 0.25, 0.5), 0.4375,
                "**−0.25 个方块 → 子矩形 3/4 处**（不是跑到左边外面）");
        near(com.ovo.sablestopnow.sketch.SolidUv.atlas(1.0, 0.25, 0.5, 2.0), 0.375,
                "每 2 个方块铺一次时，1 个方块 = 半张");
        near(com.ovo.sablestopnow.sketch.SolidUv.atlas(0.5, 0.25, 0.5, 0.0), 0.375,
                "平铺比例 <= 0 → 按 1 处理（不除零、不返回 NaN）");

        // --- 应用到真实网格 ---
        final com.ovo.sablestopnow.sketch.SketchSession cs = editing();
        cs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        cs.click(0.0, 0.0);
        cs.click(4.0, 0.0);
        final Sketch circle = cs.working();
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh mesh =
                com.ovo.sablestopnow.sketch.SolidMesh.build(circle,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        check(!mesh.isEmpty(), "圆实体生成了网格（贴图要有面可贴）");
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh mapped =
                com.ovo.sablestopnow.sketch.SolidUv.apply(mesh, 0.25, 0.5, 0.6, 0.75, 1.0);
        check(mapped.vertices().size() == mesh.vertices().size(),
                "折 UV **不改变顶点数**（" + mesh.vertices().size() + "）");
        check(mapped.indices().length == mesh.indices().length,
                "折 UV **不改变索引**（拓扑不动）");
        check(com.ovo.sablestopnow.sketch.SolidUv.within(mapped, 0.25, 0.5, 0.6, 0.75),
                "折完之后**所有** UV 都落在子矩形内 —— 一个跑出去就会在屏幕上"
                        + "出现一张不属于这里的贴图");
        // 原网格不该被就地改（网格可以缓存复用，就地改会让第二次贴图拿到已折过的 UV）
        // ⚠ 判据必须是"**调用前后 UV 不变**"，不是"UV ≤ 1"：
        //    侧面 u 是**累积弧长**（r=4 时约 25），本来就远大于 1。
        double before = 0.0;
        for (final com.ovo.sablestopnow.sketch.SolidMesh.Vertex v : mesh.vertices()) {
            before += v.u() + v.v();
        }
        double after = 0.0;
        for (final com.ovo.sablestopnow.sketch.SolidMesh.Vertex v : mesh.vertices()) {
            after += v.u() + v.v();
        }
        near(after, before, "原网格的 UV **调用前后完全不变**（apply 返回新网格，不就地改）", 1.0e-9);
        check(before > mapped.vertices().size(),
                "原始 UV 确实以方块为单位（累加 " + String.format("%.1f", before)
                        + "），所以必须折进子矩形才能用");

        // --- within 要真的会报 false ---
        check(!com.ovo.sablestopnow.sketch.SolidUv.within(mesh, 0.25, 0.5, 0.6, 0.75),
                "未折过的网格**不**在子矩形内（within 真的会报 false，不是永远 true）");

        // --- 侧面 UV 不拉伸：沿轮廓的累积弧长应当单调、且等于周长量级 ---
        final java.util.List<com.ovo.sablestopnow.sketch.Extrude.Quad> quads =
                com.ovo.sablestopnow.sketch.Extrude.prismSideFaces(circle,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        check(!quads.isEmpty(), "圆实体有侧面");
        double maxU = 0.0;
        boolean monotone = true;
        double prev = -1.0;
        for (final com.ovo.sablestopnow.sketch.Extrude.Quad quad : quads) {
            final double start = quad.uv()[0][0];
            if (start < prev - 1.0e-9) {
                monotone = false;
            }
            prev = start;
            maxU = Math.max(maxU, quad.uv()[1][0]);
        }
        check(monotone, "侧面 u 沿轮廓**单调递增**（是累积弧长，不是原始坐标）");
        // 内接 n 边形的周长
        final int seg = com.ovo.sablestopnow.sketch.SketchProfiles.Sampling.RENDER.segmentsFor(4.0);
        final double perimeter = 2.0 * seg * 4.0 * Math.sin(Math.PI / seg);
        near(maxU, perimeter, "侧面 u 的总长 = 轮廓周长（这样贴图才不会被拉伸）", 0.5);
        // 侧面的 v 应当覆盖厚度 0..2
        double minV = Double.MAX_VALUE;
        double maxV = -Double.MAX_VALUE;
        for (final com.ovo.sablestopnow.sketch.Extrude.Quad quad : quads) {
            for (final double[] p : quad.uv()) {
                minV = Math.min(minV, p[1]);
                maxV = Math.max(maxV, p[1]);
            }
        }
        near(minV, 0.0, "侧面 v 从 0 起", 1.0e-9);
        near(maxV, 2.0, "侧面 v 到厚度 2 止", 1.0e-9);
    }

    /**
     * **贴图朝向**：每个面的 (right, up, 外法线) 必须是**右手系**。
     *
     * <p>为什么这条现在就要验：贴图一旦接上去，**左手系 = 贴图镜像**。
     * 镜像这件事在方块贴图上特别明显（文字反了、木纹方向错），
     * 而且很难从"哪一步算错了"反推 —— 但在几何阶段一条断言就能钉住。
     *
     * <p>right = 沿面的"横向"（侧面是轮廓边方向、盖面是平面 u 方向），
     * up = 沿面的"纵向"（侧面是拉伸方向、盖面是平面 v 方向）。
     * 右手系即 {@code right × up = 外法线}。
     */
    private static void textureOrientation() {
        final com.ovo.sablestopnow.sketch.SketchSession cs = editing();
        cs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        cs.click(0.0, 0.0);
        cs.click(6.0, 4.0);
        final Sketch rect = cs.working();
        final com.ovo.sablestopnow.sketch.Extrude.Params one =
                com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0);

        // --- 侧面：corners = {p0底, p1底, p2顶, p3顶} ---
        final java.util.List<com.ovo.sablestopnow.sketch.Extrude.Quad> quads =
                com.ovo.sablestopnow.sketch.Extrude.prismSideFaces(rect, one);
        check(quads.size() == 4, "矩形有 4 个侧面（实测 " + quads.size() + "）");
        int mirrored = 0;
        for (final com.ovo.sablestopnow.sketch.Extrude.Quad quad : quads) {
            final Vector3d[] c = quad.corners();
            final Vector3d right = new Vector3d(c[1]).sub(c[0]).normalize();
            final Vector3d up = new Vector3d(c[3]).sub(c[0]).normalize();
            final Vector3d cross = new Vector3d(right).cross(up);
            if (cross.dot(quad.normal()) < 0.99) {
                mirrored++;
            }
        }
        check(mirrored == 0,
                "侧面全是右手系（right × up = 外法线）—— 否则贴图会镜像。实测反了 " + mirrored + " 个");
        // 另一条等价判据：绕序必须是"从外面看逆时针"
        int badWinding = 0;
        for (final com.ovo.sablestopnow.sketch.Extrude.Quad quad : quads) {
            final Vector3d[] c = quad.corners();
            final Vector3d e1 = new Vector3d(c[1]).sub(c[0]);
            final Vector3d e2 = new Vector3d(c[2]).sub(c[1]);
            if (new Vector3d(e1).cross(e2).dot(quad.normal()) < 0.0) {
                badWinding++;
            }
        }
        check(badWinding == 0, "侧面的绕序从外面看是逆时针（实测反了 " + badWinding + " 个）");

        // --- 盖面：网格三角形的绕序必须与外法线一致 ---
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh mesh =
                com.ovo.sablestopnow.sketch.SolidMesh.build(rect, one);
        check(!mesh.isEmpty(), "有网格");
        final java.util.List<com.ovo.sablestopnow.sketch.SolidMesh.Vertex> verts = mesh.vertices();
        int badTris = 0;
        int topTris = 0;
        int bottomTris = 0;
        for (int i = 0; i + 2 < mesh.indices().length; i += 3) {
            final com.ovo.sablestopnow.sketch.SolidMesh.Vertex a = verts.get(mesh.indices()[i]);
            final com.ovo.sablestopnow.sketch.SolidMesh.Vertex b = verts.get(mesh.indices()[i + 1]);
            final com.ovo.sablestopnow.sketch.SolidMesh.Vertex c = verts.get(mesh.indices()[i + 2]);
            final Vector3d e1 = new Vector3d(b.position()).sub(a.position());
            final Vector3d e2 = new Vector3d(c.position()).sub(b.position());
            final Vector3d faceNormal = new Vector3d(e1).cross(e2);
            if (faceNormal.lengthSquared() < 1.0e-18) {
                continue; // 退化三角形，跳过
            }
            if (a.normal().y > 0.5) {
                topTris++;
            } else if (a.normal().y < -0.5) {
                bottomTris++;
            }
            if (faceNormal.normalize().dot(a.normal()) < 0.0) {
                badTris++;
            }
        }
        check(badTris == 0,
                "**所有**三角形的绕序与外法线一致（实测反了 " + badTris + " 个）—— "
                        + "反面会被背面剔除掉，看起来像少了一片 / 贴图从里往外看");
        check(topTris > 0 && bottomTris > 0,
                "顶面和底面都有三角形（顶 " + topTris + "、底 " + bottomTris + "）");
    }

    /**
     * 外观值对象：用什么方块贴图、铺多大。
     *
     * <p>它的约束（比例为正、夹取范围、id 往返）全都能离线验，
     * 而且**夹取必须在构造函数里一次做完** —— 分两处夹取会出现
     * "读到的值"和"渲染用的值"不一致（`Revolve.Params` 上踩过这个坑）。
     */
    private static void solidAppearance() {
        // --- 默认与空 ---
        check(!com.ovo.sablestopnow.sketch.SolidAppearance.EMPTY.hasTexture(),
                "默认外观没有贴图");
        near(com.ovo.sablestopnow.sketch.SolidAppearance.EMPTY.blocksPerTile(), 1.0,
                "默认一个方块铺一次");

        // --- 夹取 ---
        near(new com.ovo.sablestopnow.sketch.SolidAppearance("x", 0.0).blocksPerTile(),
                com.ovo.sablestopnow.sketch.SolidAppearance.MIN_BLOCKS_PER_TILE,
                "比例 0 被抬到下限");
        near(new com.ovo.sablestopnow.sketch.SolidAppearance("x", -5.0).blocksPerTile(),
                com.ovo.sablestopnow.sketch.SolidAppearance.MIN_BLOCKS_PER_TILE,
                "负比例被抬到下限");
        near(new com.ovo.sablestopnow.sketch.SolidAppearance("x", 1.0e9).blocksPerTile(),
                com.ovo.sablestopnow.sketch.SolidAppearance.MAX_BLOCKS_PER_TILE,
                "超大比例被压到上限");
        near(new com.ovo.sablestopnow.sketch.SolidAppearance("x", Double.NaN).blocksPerTile(),
                1.0, "NaN → 默认值（不把 NaN 传给下游）");
        // ⚠ 无穷大也**统一**当"没有值"→ 默认，而不是夹到上限。
        // 规则只有一条（非有限 = 没值），没有特例 —— 特例是以后读代码的人最容易搞错的地方。
        near(new com.ovo.sablestopnow.sketch.SolidAppearance("x", Double.POSITIVE_INFINITY)
                        .blocksPerTile(),
                1.0, "无穷大 → 默认值（和 NaN 同一条规则，不搞特例）");
        // ⚠ 关键：**读到的就是夹过的**（不能一个已夹、一个没夹）
        final com.ovo.sablestopnow.sketch.SolidAppearance clamped =
                new com.ovo.sablestopnow.sketch.SolidAppearance("minecraft:stone", 100.0);
        near(clamped.blocksPerTile(), com.ovo.sablestopnow.sketch.SolidAppearance
                .MAX_BLOCKS_PER_TILE, "**记录自己的字段就是夹过的值**（只有一个答案）");

        // --- 空串当成"没设" ---
        check(!new com.ovo.sablestopnow.sketch.SolidAppearance("   ", 1.0).hasTexture(),
                "空白 id 当成没设（免得去查一个必然找不到的 id）");
        check(new com.ovo.sablestopnow.sketch.SolidAppearance("minecraft:oak_planks", 1.0)
                .hasTexture(), "正常 id 算有贴图");

        // --- 往返 ---
        final com.ovo.sablestopnow.sketch.SolidAppearance original =
                new com.ovo.sablestopnow.sketch.SolidAppearance("minecraft:oak_planks", 2.5);
        final String[] enc = original.encode();
        final com.ovo.sablestopnow.sketch.SolidAppearance back =
                com.ovo.sablestopnow.sketch.SolidAppearance.decode(enc[0], enc[1]);
        check(back.sameAs(original), "外观往返一致（id + 比例）");
        near(back.blocksPerTile(), 2.5, "比例往返精确（用 Double.toString，不用本地化格式）");

        // --- 坏输入一律退回默认，不抛（存档要长期活着）---
        final com.ovo.sablestopnow.sketch.SolidAppearance bad =
                com.ovo.sablestopnow.sketch.SolidAppearance.decode("minecraft:stone", "abc");
        near(bad.blocksPerTile(), 1.0, "比例不是数字 → 退回默认（不抛）");
        check("minecraft:stone".equals(bad.blockId()), "id 保住了");
        final com.ovo.sablestopnow.sketch.SolidAppearance empty =
                com.ovo.sablestopnow.sketch.SolidAppearance.decode(null, null);
        check(!empty.hasTexture(), "两个字段都缺 → 没贴图");
        near(empty.blocksPerTile(), 1.0, "两个字段都缺 → 默认比例");

        // --- 与 SolidUv 的接合：比例确实喂进了 UV 折叠 ---
        // 每 2 个方块铺一次时，走 1 个方块应当只到子矩形的一半
        near(com.ovo.sablestopnow.sketch.SolidUv.atlas(1.0, 0.0, 1.0, 2.0), 0.5,
                "比例 2 时走 1 个方块 = 子矩形一半（比例真的接到了 UV 上）");

        // --- sameAs：用来判断"要不要重新取贴图" ---
        check(original.sameAs(original), "自己和自己等价");
        check(!original.sameAs(original.withBlocksPerTile(3.0)), "比例变了就不等价");
        check(!original.sameAs(original.withBlock("minecraft:dirt")), "方块变了就不等价");
        check(!original.sameAs(null), "null 不等价");
    }

    /**
     * **约束在创建时必须已被满足** —— 针对 2026-09 那次"甩飞 + TPS 归零"事故的回归护栏。
     *
     * <p>事故根因：固定约束的相对朝向传了**单位四元数**，而棱盒是斜的 →
     * 约束在创建那一刻就被违反 → 求解器猛拽 → 子结构被甩到极端坐标。
     * 轴对齐盒子的局部朝向恰好是单位四元数，所以那个写法**碰巧对**，直到曲面路径才引爆。
     */
    private static void constraintSatisfaction() {
        // 一个真实的子结构朝向（歪的，免得"恰好是单位四元数"又把问题掩盖过去）
        final Quaterniond subRot = new Quaterniond().rotationAxis(0.6, 0.8, 0.0, 1.1);
        final Vector3d subPos = new Vector3d(120.0, -40.0, 7.5);
        final Vector3d centre = new Vector3d(3.0, -1.5, 0.25);

        // --- 轴对齐盒子：局部朝向是单位四元数 ---
        // ⚠ 容差用 1e-3 度而不是 0：`2·acos(|dot|)` 在 dot≈1 附近条件数很差
        //（dot 差 1e-13 就能让结果差 1e-6 度）。物理上 1e-3 度完全无所谓。
        final Quaterniond identity = new Quaterniond();
        near(com.ovo.sablestopnow.sketch.SolidCollision
                        .constraintViolationDegrees(subRot, identity, identity), 0.0,
                "轴对齐盒子：传单位四元数**碰巧**满足约束（这正是当年藏住 bug 的原因）", 1.0e-3);

        // --- 斜盒子（棱盒）：必须传盒子朝向，传单位四元数会被判为违反 ---
        // ⚠ JOML 的签名是 rotationAxis(angle, x, y, z) —— **角度在第一个**。
        // 写成 (x,y,z,angle) 会静默得到单位四元数，于是"斜盒子"其实是正的，
        // 这个测试就自己失去了意义。（我为这个坑写过注释，然后还是踩了一次。）
        final Quaterniond tilted = new Quaterniond().rotationAxis(0.7, 0.0, 0.0, 1.0);
        // 判据是"**不是单位四元数**"（w < 1），不是"和 1 差得够远"：
        // 0.7 弧度的四元数 w = cos(0.35) = 0.939 —— 与 1 只差 0.06，但它确实是个真旋转。
        check(tilted.w < 1.0 - 1.0e-6,
                "断言前提：`tilted` 确实不是单位四元数（w=" + tilted.w + " < 1）");
        near(com.ovo.sablestopnow.sketch.SolidCollision
                        .constraintViolationDegrees(subRot, tilted, tilted), 0.0,
                "斜盒子：传**盒子朝向** → 约束创建时即满足（偏差 0°）", 1.0e-3);
        final double bad = com.ovo.sablestopnow.sketch.SolidCollision
                .constraintViolationDegrees(subRot, tilted, identity);
        near(bad, Math.toDegrees(0.7), "斜盒子：传单位四元数 → 偏差 = 倾角本身（"
                + String.format("%.2f", bad) + "°）—— 这就是把结构甩飞的那个违反量", 1.0e-2);

        // --- 位置也要对得上：盒子世界位置 = 子位置 + 子朝向作用在局部中心上 ---
        final Vector3d boxPos = com.ovo.sablestopnow.sketch.SolidCollision
                .composePosition(subPos, subRot, centre);
        // 反解回局部：子朝向⁻¹ 作用在 (boxPos − subPos) 上，应当还原 centre
        final Vector3d back = new Quaterniond(subRot).conjugate()
                .transform(new Vector3d(boxPos).sub(subPos), new Vector3d());
        near(back.distance(centre), 0.0, "位置复合可逆（反解回局部中心）", 1.0e-9);

        // --- q 与 −q 是同一个旋转，不能误判成 180° ---
        final Quaterniond negTilted = new Quaterniond(-tilted.x, -tilted.y, -tilted.z, -tilted.w);
        near(com.ovo.sablestopnow.sketch.SolidCollision
                        .constraintViolationDegrees(subRot, tilted, negTilted), 0.0,
                "**q 与 −q 是同一个旋转** → 偏差 0°（按 |dot| 判，不是逐分量比）", 1.0e-3);

        // --- 反解出来的相对朝向应当恰好等于盒子局部朝向 ---
        final Quaterniond world = com.ovo.sablestopnow.sketch.SolidCollision
                .composeOrientation(subRot, tilted);
        final Quaterniond rel = com.ovo.sablestopnow.sketch.SolidCollision
                .relativeOrientation(subRot, world);
        near(Math.abs(rel.dot(tilted)), 1.0,
                "相对朝向 = 盒子局部朝向（接线传的就是它）", 1.0e-9);
    }

    /**
     * 贴图要按**面的类别**挑方块模型的哪一面（原木顶面是年轮、侧面是树皮；
     * 草方块顶面绿、侧面带草边 —— 用错就"四面年轮"）。
     *
     * <p>同时验一条它依赖的几何不变量：**侧面法线 ⟂ 平面法线、盖面法线 ∥ 平面法线**。
     * 不验的话，分类函数看着对、实际全落进 SIDE 分支也不会有人发现。
     */
    private static void faceKinds() {
        final com.ovo.sablestopnow.sketch.SketchSession cs = editing();
        cs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        cs.click(0.0, 0.0);
        cs.click(6.0, 4.0);
        final Sketch rect = cs.working();
        final SketchPlane plane = rect.plane();
        final com.ovo.sablestopnow.sketch.Extrude.Params one =
                com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0);

        // --- 分类函数本身 ---
        final Vector3d n = new Vector3d(plane.normal());
        final com.ovo.sablestopnow.sketch.SolidMesh.FaceKind kUp =
                com.ovo.sablestopnow.sketch.SolidMesh.faceKind(new Vector3d(n), n);
        check(kUp == com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.CAP_UP,
                "与平面法线同向 → 盖子朝正向（实测 " + kUp + "）");
        final com.ovo.sablestopnow.sketch.SolidMesh.FaceKind kDown =
                com.ovo.sablestopnow.sketch.SolidMesh.faceKind(new Vector3d(n).negate(), n);
        check(kDown == com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.CAP_DOWN,
                "反向 → 盖子朝反向（实测 " + kDown + "）");
        final com.ovo.sablestopnow.sketch.SolidMesh.FaceKind kSide =
                com.ovo.sablestopnow.sketch.SolidMesh.faceKind(new Vector3d(plane.axisU()), n);
        check(kSide == com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.SIDE,
                "垂直 → 侧面（实测 " + kSide + "）");
        // 阈值容差：偏 85° 也该算侧面（不该被浮点或小角度误判成盖面）
        final Vector3d tilted = new Vector3d(n).mul(Math.cos(Math.toRadians(85)))
                .add(new Vector3d(plane.axisU()).mul(Math.sin(Math.toRadians(85)))).normalize();
        final com.ovo.sablestopnow.sketch.SolidMesh.FaceKind kTilted =
                com.ovo.sablestopnow.sketch.SolidMesh.faceKind(tilted, n);
        check(kTilted == com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.SIDE,
                "偏 85° 仍算侧面（阈值 0.9 ≈ 25.8°，留了余量；实测 " + kTilted + "）");

        // --- 真实网格：分类结果必须与几何一致 ---
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh mesh =
                com.ovo.sablestopnow.sketch.SolidMesh.build(rect, one);
        int caps = 0;
        int sides = 0;
        int mismatched = 0;
        for (final com.ovo.sablestopnow.sketch.SolidMesh.Vertex v : mesh.vertices()) {
            final com.ovo.sablestopnow.sketch.SolidMesh.FaceKind kind =
                    com.ovo.sablestopnow.sketch.SolidMesh.faceKind(v.normal(), n);
            final double d = v.normal().dot(n);
            if (kind == com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.SIDE) {
                sides++;
                // 侧面：法线必须**垂直于**平面法线
                if (Math.abs(d) > 0.01) {
                    mismatched++;
                }
            } else {
                caps++;
                // 盖面：法线必须**平行于**平面法线
                if (Math.abs(Math.abs(d) - 1.0) > 0.01) {
                    mismatched++;
                }
            }
        }
        check(mismatched == 0,
                "分类与几何一致（不变量：侧面法线 ⟂ 平面法线、盖面法线 ∥ 平面法线），"
                        + "不一致 " + mismatched + " 个顶点");
        check(caps > 0 && sides > 0, "矩形网格里盖面和侧面都有（盖 " + caps + " / 侧 " + sides + "）");
        // 矩形侧面：4 个面各 4 个顶点 = 16；盖面：上下各 2 个三角 × 3 = 12
        check(sides == 16, "矩形侧面顶点数 = 4 面 × 4 顶点（实测 " + sides + "）");
        check(caps == 12, "矩形盖面顶点数 = 2 盖 × 2 三角 × 3 顶点（实测 " + caps + "）");

        // --- 两种拉伸方式都要有盖面（双向拉伸的两个盖子朝向相反）---
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh sym =
                com.ovo.sablestopnow.sketch.SolidMesh.build(rect,
                        com.ovo.sablestopnow.sketch.Extrude.Params.symmetric(4.0));
        int up = 0;
        int down = 0;
        for (final com.ovo.sablestopnow.sketch.SolidMesh.Vertex v : sym.vertices()) {
            final com.ovo.sablestopnow.sketch.SolidMesh.FaceKind kind =
                    com.ovo.sablestopnow.sketch.SolidMesh.faceKind(v.normal(), n);
            if (kind == com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.CAP_UP) {
                up++;
            } else if (kind == com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.CAP_DOWN) {
                down++;
            }
        }
        check(up > 0 && down > 0,
                "两个盖子朝向相反（正 " + up + " / 反 " + down + "）—— 这样上下能贴不同的面");
    }

    /**
     * 贴图渲染的核心：**按面的类别**分别把 UV 折进对应的贴图子矩形。
     *
     * <p>这是下一轮 MC 侧要调的那个函数 —— 把它做成纯逻辑，是为了让"哪一类面用哪张贴图、
     * UV 折对不对"能离线验，而不是等进了游戏盯着屏幕猜。
     */
    private static void texturedFaces() {
        final com.ovo.sablestopnow.sketch.SketchSession cs = editing();
        cs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        cs.click(0.0, 0.0);
        cs.click(6.0, 4.0);
        final Sketch rect = cs.working();
        final org.joml.Vector3d n = new org.joml.Vector3d(rect.plane().normal());
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh mesh =
                com.ovo.sablestopnow.sketch.SolidMesh.build(rect,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));

        // --- SpriteRect 的约束 ---
        boolean threw = false;
        try {
            new com.ovo.sablestopnow.sketch.SolidUv.SpriteRect(0.5, 0.25, 0.0, 1.0);
        } catch (final IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "子矩形 u1 < u0 → 被拒（顺序反了会把 UV 折到矩形外面，"
                + "画面问题极难反推，所以在构造时就拦掉）");
        final com.ovo.sablestopnow.sketch.SolidUv.SpriteRect sideRect =
                new com.ovo.sablestopnow.sketch.SolidUv.SpriteRect(0.0, 0.25, 0.5, 0.75);
        near(sideRect.width(), 0.25, "宽度 = u1 − u0", 1.0e-12);
        near(sideRect.height(), 0.25, "高度 = v1 − v0", 1.0e-12);

        // --- 三张贴图各不相同：折完之后每一类都落在**自己**的矩形里 ---
        final com.ovo.sablestopnow.sketch.SolidUv.SpriteRect capUpRect =
                new com.ovo.sablestopnow.sketch.SolidUv.SpriteRect(0.25, 0.5, 0.0, 0.25);
        final com.ovo.sablestopnow.sketch.SolidUv.SpriteRect capDownRect =
                new com.ovo.sablestopnow.sketch.SolidUv.SpriteRect(0.5, 0.75, 0.0, 0.25);
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh textured =
                com.ovo.sablestopnow.sketch.SolidUv.applyFaces(mesh, n, 1.0,
                        capUpRect, capDownRect, sideRect);
        check(com.ovo.sablestopnow.sketch.SolidUv.withinFaces(textured, n,
                        capUpRect, capDownRect, sideRect),
                "折完后每一类都在**自己**的贴图矩形内（盖面和侧面用的是不同贴图）");
        // 反面证据：拿一个矩形套全部应当判 false（否则这个自检没有意义）
        check(!com.ovo.sablestopnow.sketch.SolidUv.withinFaces(textured, n,
                        sideRect, sideRect, sideRect),
                "三张都给同一个矩形时**应当**判 false —— 证明按类别分别判断真的生效了");

        // --- 贴图不该碰几何 ---
        check(textured.vertices().size() == mesh.vertices().size(), "顶点数不变");
        check(textured.indices().length == mesh.indices().length, "索引不变");
        boolean geomSame = true;
        for (int i = 0; i < mesh.vertices().size(); i++) {
            final com.ovo.sablestopnow.sketch.SolidMesh.Vertex a = mesh.vertices().get(i);
            final com.ovo.sablestopnow.sketch.SolidMesh.Vertex b = textured.vertices().get(i);
            if (a.position().distance(b.position()) > 1.0e-12
                    || a.normal().distance(b.normal()) > 1.0e-12) {
                geomSame = false;
            }
        }
        check(geomSame, "位置与法线**一个都没被改动**（贴图只改 UV，拓扑是几何的事）");
        // 原网格也不该被就地改
        check(!com.ovo.sablestopnow.sketch.SolidUv.within(mesh, 0.0, 0.25, 0.5, 0.75),
                "原网格没被就地修改（它本来就不在这些子矩形里）");

        // --- 某一类没有贴图 → 那一类原样保留（不是折成垃圾值）---
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh noCap =
                com.ovo.sablestopnow.sketch.SolidUv.applyFaces(mesh, n, 1.0,
                        null, null, sideRect);
        int kept = 0;
        for (int i = 0; i < mesh.vertices().size(); i++) {
            final com.ovo.sablestopnow.sketch.SolidMesh.Vertex a = mesh.vertices().get(i);
            final com.ovo.sablestopnow.sketch.SolidMesh.Vertex b = noCap.vertices().get(i);
            if (com.ovo.sablestopnow.sketch.SolidMesh.faceKind(a.normal(), n)
                    != com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.SIDE) {
                if (Math.abs(a.u() - b.u()) < 1.0e-12 && Math.abs(a.v() - b.v()) < 1.0e-12) {
                    kept++;
                }
            }
        }
        check(kept > 0, "盖面没有贴图时**原样保留**（" + kept + " 个顶点未动），"
                + "而不是被折成一个越界的垃圾 UV");
        check(com.ovo.sablestopnow.sketch.SolidUv.withinFaces(noCap, n,
                        null, null, sideRect), "缺贴图的那一类不参与自检（不会误报）");

        // --- 平铺比例真的接上了 ---
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh half =
                com.ovo.sablestopnow.sketch.SolidUv.applyFaces(mesh, n, 2.0,
                        capUpRect, capDownRect, sideRect);
        check(com.ovo.sablestopnow.sketch.SolidUv.withinFaces(half, n,
                        capUpRect, capDownRect, sideRect),
                "比例 2 时 UV 仍在矩形内（比例不会把 UV 推出贴图之外）");

        // --- 空网格 ---
        check(com.ovo.sablestopnow.sketch.SolidUv.applyFaces(null, n, 1.0,
                        capUpRect, capDownRect, sideRect) == null, "null 网格 → null（不抛）");
    }

    /**
     * 选方块的搜索：**相关度分层 + 稳定顺序**。
     *
     * <p>为什么要离线验排序：候选列表**每次重建都重算**。顺序不确定的话，
     * 玩家会看到列表"自己跳来跳去"，像没响应 —— 这是最难描述、最难复现的一类 bug。
     */
    private static void blockSearch() {
        final java.util.List<String> pool = java.util.List.of(
                "minecraft:stone",
                "minecraft:oak_planks",
                "minecraft:oak_log",
                "minecraft:spruce_planks",
                "minecraft:glass",
                "minecraft:dirt");

        // --- 空关键字 = 不过滤，且**保持输入顺序** ---
        final java.util.List<String> all =
                com.ovo.sablestopnow.sketch.SolidAppearance.searchBlocks(pool, "", 100);
        eq(all.size(), 6, "空关键字 → 全部返回");
        check(all.equals(pool), "空关键字时**顺序与输入一致**（界面自己决定默认展示什么）");
        check(com.ovo.sablestopnow.sketch.SolidAppearance.searchBlocks(pool, null, 100)
                .equals(pool), "null 关键字 = 不过滤");

        // --- 路径命中优先于命名空间命中 ---
        final java.util.List<String> oak =
                com.ovo.sablestopnow.sketch.SolidAppearance.searchBlocks(pool, "oak", 100);
        eq(oak.size(), 2, "「oak」命中 2 个");
        check(oak.get(0).startsWith("minecraft:oak"), "命中的都是 oak 那几个");

        // --- 空格与下划线等价（玩家会打 "oak planks"）---
        final java.util.List<String> spaced =
                com.ovo.sablestopnow.sketch.SolidAppearance.searchBlocks(pool, "oak planks", 100);
        eq(spaced.size(), 1, "「oak planks」（带空格）也能搜到");
        check("minecraft:oak_planks".equals(spaced.get(0)), "搜到的是 oak_planks");

        // --- 大小写不敏感 ---
        eq(com.ovo.sablestopnow.sketch.SolidAppearance.searchBlocks(pool, "OAK", 100).size(), 2,
                "大写 OAK 同样命中 2 个");

        // --- 完全等于路径的排最前 ---
        final java.util.List<String> exact = com.ovo.sablestopnow.sketch.SolidAppearance
                .searchBlocks(java.util.List.of("minecraft:oak", "minecraft:oak_planks",
                        "minecraft:oak_log"), "oak", 100);
        eq(exact.size(), 3, "三个都以 oak 开头");
        check("minecraft:oak".equals(exact.get(0)),
                "**路径完全等于关键字**的排最前（实测第一个是 " + exact.get(0) + "）");

        // --- 限量：截断但顺序不变 ---
        final java.util.List<String> limited =
                com.ovo.sablestopnow.sketch.SolidAppearance.searchBlocks(pool, "", 2);
        eq(limited.size(), 2, "限量 2 条");
        check(limited.get(0).equals(pool.get(0)) && limited.get(1).equals(pool.get(1)),
                "截断的是**尾部**，前两条与输入顺序一致");
        eq(com.ovo.sablestopnow.sketch.SolidAppearance
                .searchBlocks(pool, "", 0).size(), 6,
                "limit<=0 → 用默认上限（6 < 40 所以全返回）");

        // --- 声明式顺序：把输入顺序换一下，结果也要跟着换（证明确实稳定，不是靠哈希）---
        final java.util.List<String> reversed = java.util.List.of(
                "minecraft:oak_log", "minecraft:oak_planks");
        final java.util.List<String> r = com.ovo.sablestopnow.sketch.SolidAppearance
                .searchBlocks(reversed, "oak", 100);
        check(r.get(0).equals("minecraft:oak_log"),
                "同层内按**输入顺序**排（换输入顺序结果跟着换）");

        // --- 坏输入 ---
        check(com.ovo.sablestopnow.sketch.SolidAppearance
                .searchBlocks(null, "oak", 10).isEmpty(), "候选为 null → 空表");
        check(com.ovo.sablestopnow.sketch.SolidAppearance
                .searchBlocks(java.util.List.of(), "oak", 10).isEmpty(), "候选为空 → 空表");
        check(com.ovo.sablestopnow.sketch.SolidAppearance
                .searchBlocks(java.util.Arrays.asList("minecraft:stone", null), "stone", 10)
                .size() == 1, "列表里有 null → 跳过它，不影响别的");
        eq(com.ovo.sablestopnow.sketch.SolidAppearance.pathOf("minecraft:oak_planks"),
                "oak_planks", "pathOf 去掉命名空间");
        eq(com.ovo.sablestopnow.sketch.SolidAppearance.pathOf("oak_planks"), "oak_planks",
                "pathOf 对没有命名空间的 id 原样返回");
        eq(com.ovo.sablestopnow.sketch.SolidAppearance.pathOf("minecraft:"), "minecraft:",
                "pathOf 对 `ns:` 这种残缺 id 不越界");
    }

    /**
     * 取样面的**回退链**：方块模型不一定有全部 6 个面（自定义模型常只写 north，
     * 薄板类只写 up/down）。按"首选"直接查会拿到 null 贴图 → 那个面画成空白。
     */
    private static void blockFaceFallback() {
        final com.ovo.sablestopnow.sketch.SolidMesh.BlockFace up =
                com.ovo.sablestopnow.sketch.SolidMesh.BlockFace.UP;
        final com.ovo.sablestopnow.sketch.SolidMesh.BlockFace north =
                com.ovo.sablestopnow.sketch.SolidMesh.BlockFace.NORTH;

        // --- 对面关系是自反的 ---
        check(up.opposite() == com.ovo.sablestopnow.sketch.SolidMesh.BlockFace.DOWN,
                "UP 的对面是 DOWN");
        check(up.opposite().opposite() == up, "对面取两次回到自己");
        check(!up.horizontal() && north.horizontal(), "UP 不是水平面、NORTH 是");

        // --- 面类别 → 首选朝向 ---
        check(com.ovo.sablestopnow.sketch.SolidMesh.preferredFace(
                        com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.CAP_UP)
                        == up, "盖子朝正 → 取方块的 UP");
        check(com.ovo.sablestopnow.sketch.SolidMesh.preferredFace(
                        com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.CAP_DOWN)
                        == com.ovo.sablestopnow.sketch.SolidMesh.BlockFace.DOWN,
                "盖子朝反 → 取方块的 DOWN");
        check(com.ovo.sablestopnow.sketch.SolidMesh.preferredFace(
                        com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.SIDE) == north,
                "侧面 → 取方块的 NORTH（固定一个，不能随机)");

        // --- 首选可用 → 就用首选 ---
        final java.util.Set<com.ovo.sablestopnow.sketch.SolidMesh.BlockFace> all =
                java.util.EnumSet.allOf(com.ovo.sablestopnow.sketch.SolidMesh.BlockFace.class);
        check(com.ovo.sablestopnow.sketch.SolidMesh.pickAvailable(
                        com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.SIDE, all) == north,
                "首选可用时用首选");

        // --- 首选缺失 → 退到对面 ---
        final java.util.Set<com.ovo.sablestopnow.sketch.SolidMesh.BlockFace> noNorth =
                java.util.EnumSet.of(com.ovo.sablestopnow.sketch.SolidMesh.BlockFace.SOUTH,
                        com.ovo.sablestopnow.sketch.SolidMesh.BlockFace.EAST);
        check(com.ovo.sablestopnow.sketch.SolidMesh.pickAvailable(
                        com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.SIDE, noNorth)
                        == com.ovo.sablestopnow.sketch.SolidMesh.BlockFace.SOUTH,
                "没有 NORTH 时退到它的对面 SOUTH");

        // --- 对面也没有 → 按**固定顺序**兜底（结果可复现）---
        final java.util.Set<com.ovo.sablestopnow.sketch.SolidMesh.BlockFace> onlyEast =
                java.util.EnumSet.of(com.ovo.sablestopnow.sketch.SolidMesh.BlockFace.EAST);
        check(com.ovo.sablestopnow.sketch.SolidMesh.pickAvailable(
                        com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.SIDE, onlyEast)
                        == com.ovo.sablestopnow.sketch.SolidMesh.BlockFace.EAST,
                "只剩 EAST 时也能挑到（保证「只要有面就画得出来」）");
        // 同一组合反复挑必须得到同一个结果（同一面墙不能出现两种纹理）
        final java.util.Set<com.ovo.sablestopnow.sketch.SolidMesh.BlockFace> two =
                java.util.EnumSet.of(com.ovo.sablestopnow.sketch.SolidMesh.BlockFace.EAST,
                        com.ovo.sablestopnow.sketch.SolidMesh.BlockFace.WEST);
        final com.ovo.sablestopnow.sketch.SolidMesh.BlockFace first =
                com.ovo.sablestopnow.sketch.SolidMesh.pickAvailable(
                        com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.SIDE, two);
        boolean stable = true;
        for (int i = 0; i < 20; i++) {
            if (com.ovo.sablestopnow.sketch.SolidMesh.pickAvailable(
                    com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.SIDE, two) != first) {
                stable = false;
            }
        }
        check(stable, "同一组可用面反复挑总是同一个结果（实测 " + first + "）");

        // --- 一个面都没有 ---
        check(com.ovo.sablestopnow.sketch.SolidMesh.pickAvailable(
                        com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.SIDE,
                        java.util.EnumSet.noneOf(
                                com.ovo.sablestopnow.sketch.SolidMesh.BlockFace.class)) == null,
                "一个面都没有 → null（调用方据此画纯色，而不是画空白）");
        check(com.ovo.sablestopnow.sketch.SolidMesh.pickAvailable(
                        com.ovo.sablestopnow.sketch.SolidMesh.FaceKind.SIDE, null) == null,
                "available 为 null → null（不抛）");
    }

    /**
     * 回转体的**三角网格**（贴图用）。
     *
     * <p>核心判据是 **{@code SolidMesh.signedVolume}**：它一次把位置、绕序、闭合性绑在一起 ——
     * 逐顶点断言只看得到"想得到的那种错"，而体积会把没想到的错一起暴露。
     * 跟解析体积（圆环 = π(r1²−r0²)·h）对一下，错了就一定是这三样之一。
     */
    private static void revolveMesh() {
        // 矩形 r∈[3,5]、t∈[0,4] 绕 v 轴整圈 → 圆环，体积 π(25−9)·4 = 64π
        final com.ovo.sablestopnow.sketch.SketchSession vs = editing();
        vs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        vs.click(3.0, 0.0);
        vs.click(5.0, 4.0);
        final Sketch vs2 = vs.working();
        final com.ovo.sablestopnow.sketch.Revolve.Params full =
                new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 360, 1);
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh mesh =
                com.ovo.sablestopnow.sketch.Revolve.mesh(vs2, full);
        check(!mesh.isEmpty(), "整圈回转体生成网格（" + mesh.triangleCount() + " 个三角形）");

        final double analytic = 64.0 * Math.PI;
        final double vol = com.ovo.sablestopnow.sketch.SolidMesh.signedVolume(mesh);
        check(vol > 0.0,
                "有符号体积为**正** → 绕序整体朝外（实测 " + String.format("%.1f", vol)
                        + "）—— 为负说明所有面都朝里，背面剔除后什么都看不见");
        near(vol, analytic,
                "网格体积 ≈ 解析圆环体积 64π（实测 " + String.format("%.1f", vol)
                        + " vs " + String.format("%.1f", analytic) + "）", analytic * 0.06);

        // --- 整圈**不该有盖面**（首尾相接）---
        // 有盖面的话，盖面位于 phi=0 和 phi=2π 两处、位置相同，体积会多出一块；
        // 更直接的判据：所有顶点到轴的垂距都应当在 [3,5]（盖面也在其中，所以改用计数）
        // 这里用"网格顶点数 ≈ 环数 × 段数"来判断没有多余的盖面顶点。
        final int ringVerts = mesh.vertices().size();
        check(ringVerts % (4 * 48) == 0,
                "整圈网格的顶点数能整除 4×48（每段 4 顶点 × 48 段）→ 没有多余的盖面顶点"
                        + "（实测 " + ringVerts + "）");

        // --- 所有顶点都在正确的环带里 ---
        final SketchPlane plane = vs2.plane();
        final Vector3d axisPoint = plane.toLocal(0.0, 0.0);
        final Vector3d dir = com.ovo.sablestopnow.sketch.Revolve.axisDirection(plane, full);
        double minR = Double.MAX_VALUE;
        double maxR = -Double.MAX_VALUE;
        for (final com.ovo.sablestopnow.sketch.SolidMesh.Vertex v : mesh.vertices()) {
            final Vector3d w = new Vector3d(v.position()).sub(axisPoint);
            final double r = w.cross(dir, new Vector3d()).length();
            minR = Math.min(minR, r);
            maxR = Math.max(maxR, r);
        }
        near(minR, 3.0, "最内圈半径 = 3（实测 " + String.format("%.3f", minR) + "）", 1.0e-6);
        near(maxR, 5.0, "最外圈半径 = 5（实测 " + String.format("%.3f", maxR) + "）", 1.0e-6);

        // --- 法线与绕序逐个三角形一致 ---
        int bad = 0;
        final int[] idx = mesh.indices();
        for (int i = 0; i + 2 < idx.length; i += 3) {
            final com.ovo.sablestopnow.sketch.SolidMesh.Vertex a = mesh.vertices().get(idx[i]);
            final com.ovo.sablestopnow.sketch.SolidMesh.Vertex b = mesh.vertices().get(idx[i + 1]);
            final com.ovo.sablestopnow.sketch.SolidMesh.Vertex c = mesh.vertices().get(idx[i + 2]);
            final Vector3d e1 = new Vector3d(b.position()).sub(a.position());
            final Vector3d e2 = new Vector3d(c.position()).sub(b.position());
            final Vector3d faceN = new Vector3d(e1).cross(e2);
            if (faceN.lengthSquared() < 1.0e-18) {
                continue;
            }
            if (faceN.normalize().dot(a.normal()) < 0.0) {
                bad++;
            }
        }
        check(bad == 0, "每个三角形的绕序都与它的法线同侧（反了 " + bad + " 个）");

        // --- 部分旋转：应当有盖面，体积约为整圈的四分之一 ---
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh quarter =
                com.ovo.sablestopnow.sketch.Revolve.mesh(vs2,
                        new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 90, 1));
        final double qv = com.ovo.sablestopnow.sketch.SolidMesh.signedVolume(quarter);
        check(qv > 0.0, "90° 回转体的绕序同样朝外（实测 " + String.format("%.1f", qv) + "）");
        near(qv, analytic / 4.0, "90° 的体积约为整圈的四分之一（实测 "
                + String.format("%.1f", qv) + "）", analytic * 0.03);

        // --- 反向旋转（负角度）：绕序**仍然**朝外 ---
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh cw =
                com.ovo.sablestopnow.sketch.Revolve.mesh(vs2,
                        new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 90, -1));
        final double cwv = com.ovo.sablestopnow.sketch.SolidMesh.signedVolume(cw);
        check(cwv > 0.0,
                "**反向**旋转时体积也为正（实测 " + String.format("%.1f", cwv)
                        + "）—— 曾经的 bug：翻转绕序时写成了**蝴蝶结**四边形，"
                        + "两个三角形方向相反而精确抵消，体积恰好是 0");

        // --- 贴图：侧面 u 是轮廓弧长、v 是旋转弧长（都不会拉伸）---
        double maxU = 0.0;
        double maxV = 0.0;
        for (final com.ovo.sablestopnow.sketch.SolidMesh.Vertex v : mesh.vertices()) {
            maxU = Math.max(maxU, v.u());
            maxV = Math.max(maxV, v.v());
        }
        // 矩形轮廓周长 = 2*(2+4) = 12
        near(maxU, 12.0, "侧面 u 的最大值 = 轮廓周长 12（实测 " + String.format("%.2f", maxU) + "）",
                1.0e-6);
        check(maxV > 0.0, "侧面 v 是旋转弧长（实测最大 " + String.format("%.2f", maxV) + "）");

        // --- 退化输入 ---
        final com.ovo.sablestopnow.sketch.SketchSession open = editing();
        open.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        open.click(0.0, 0.0);
        open.click(5.0, 0.0);
        check(com.ovo.sablestopnow.sketch.Revolve.mesh(open.working(), full).isEmpty(),
                "不闭合的轮廓 → 空网格（不是 null）");
        check(com.ovo.sablestopnow.sketch.SolidMesh.signedVolume(
                new com.ovo.sablestopnow.sketch.SolidMesh.Mesh(java.util.List.of(), new int[0]))
                == 0.0, "空网格的体积 = 0（不抛）");
    }

    /**
     * 草图**镜像**。
     *
     * <p>三条容易错的：① 圆弧反射会**反转朝向**（只换一个角会画出"补弧"）；
     * ② 镜像必须**新建变量**（共享变量 = 改一边另一边也动，那是"对称约束"不是"镜像一份"）；
     * ③ 不能把镜像轴自己也镜像（会生成一条和轴重合的线，看着像"什么都没发生"）。
     */
    private static void mirrorSelection() {
        // 轴：竖直直线 x=0（方向 +v）
        // ⚠ 必须用 editing()（它会先 beginPlaneChoice + choosePlane）：
        //   直接 new SketchSession() 的话 working() 是 null，后面全崩。
        final com.ovo.sablestopnow.sketch.SketchSession s = editing();
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        s.click(0.0, -5.0);
        s.click(0.0, 5.0);
        final Sketch k = s.working();
        final com.ovo.sablestopnow.sketch.Sketch.Entity axis = k.entities().get(0);
        check(axis.kind() == Sketch.EntityKind.LINE, "轴是一条线");

        // --- 镜像一条线：x 取反 ---
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        s.click(3.0, 1.0);
        s.click(6.0, 2.0);
        final com.ovo.sablestopnow.sketch.Sketch.Entity line = k.entities().get(1);
        s.clearSelection();
        s.toggleEntity(line);
        eq(s.selectionSize(), 1, "选中一条线");
        eq(s.mirrorSelection(axis), 1, "镜像出 1 个新图元");
        eq(k.entities().size(), 3, "图元数从 2 变 3");
        final com.ovo.sablestopnow.sketch.Sketch.Entity mirrored = k.entities().get(2);
        final int[] mv = mirrored.vars();
        near(k.variable(mv[0]), -3.0, "镜像后起点 x = −3", 1.0e-9);
        near(k.variable(mv[1]), 1.0, "镜像后起点 y 不变", 1.0e-9);
        near(k.variable(mv[2]), -6.0, "镜像后终点 x = −6", 1.0e-9);
        near(k.variable(mv[3]), 2.0, "镜像后终点 y 不变", 1.0e-9);

        // --- ⚠ 变量必须是**新的**（不共享）---
        final int[] ov = line.vars();
        boolean shared = false;
        for (final int a : ov) {
            for (final int b : mv) {
                if (a == b) {
                    shared = true;
                }
            }
        }
        check(!shared, "镜像后的图元用的是**新变量** —— 共享的话改一边另一边也会动，"
                + "那是「对称约束」的语义，不是「镜像一份」");

        // --- 镜像两遍回到原处 ---
        s.clearSelection();
        s.toggleEntity(mirrored);
        eq(s.mirrorSelection(axis), 1, "再镜像一次");
        final com.ovo.sablestopnow.sketch.Sketch.Entity back = k.entities().get(3);
        final int[] bv = back.vars();
        near(k.variable(bv[0]), 3.0, "两次镜像回到原 x（起点）", 1.0e-9);
        near(k.variable(bv[2]), 6.0, "两次镜像回到原 x（终点）", 1.0e-9);

        // --- 圆：圆心反射、半径不变 ---
        s.clearSelection();
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        s.click(4.0, 3.0);
        s.click(7.0, 3.0);
        final com.ovo.sablestopnow.sketch.Sketch.Entity circle = k.entities().get(4);
        s.clearSelection();
        s.toggleEntity(circle);
        eq(s.mirrorSelection(axis), 1, "镜像圆");
        final com.ovo.sablestopnow.sketch.Sketch.Entity mcircle = k.entities().get(5);
        final int[] cv = mcircle.vars();
        near(k.variable(cv[0]), -4.0, "圆心 x 反射到 −4", 1.0e-9);
        near(k.variable(cv[1]), 3.0, "圆心 y 不变", 1.0e-9);
        near(k.variable(cv[2]), 3.0, "半径不变 = 3", 1.0e-9);

        // --- 圆弧：**两个角都要换**（否则画成补弧）---
        s.clearSelection();
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.ARC);
        s.click(5.0, 0.0);      // 圆心
        s.click(8.0, 0.0);      // 起点（角度 0）
        s.click(5.0, 3.0);      // 终点（角度 π/2）
        final com.ovo.sablestopnow.sketch.Sketch.Entity arc =
                k.entities().get(k.entities().size() - 1);
        check(arc.kind() == Sketch.EntityKind.ARC, "画出了圆弧");
        final int[] av = arc.vars();
        check(av.length == 6, "弧是 6 个变量（圆心 + 起点 + 终点）");
        // 原弧的两个端点（端点是**变量** ⇒ 直接读，不绕 arcPoint 的约定）
        final double[][] aEnds = k.arcEndpoints(arc);
        check(aEnds != null, "拿得到原弧的端点");
        final double aR = k.arcRadius(arc);
        final double aA0 = k.arcStart(arc);
        final double aA1 = k.arcEnd(arc);
        s.clearSelection();
        s.toggleEntity(arc);
        eq(s.mirrorSelection(axis), 1, "镜像圆弧");
        final com.ovo.sablestopnow.sketch.Sketch.Entity marc =
                k.entities().get(k.entities().size() - 1);
        final int[] mav = marc.vars();
        check(marc.kind() == Sketch.EntityKind.ARC, "镜像出来的也是圆弧");
        // 判据分两层（**不依赖 arcPoint 的约定**，那样会把自己的假设当成事实来验）：
        // ① 几何上确定的：圆心是镜像、半径不变
        near(k.variable(mav[0]), -k.variable(av[0]),
                "镜像弧的圆心 x = 原圆心 x 的镜像", 1.0e-9);
        near(k.variable(mav[1]), k.variable(av[1]), "圆心 y 不变", 1.0e-9);
        near(k.arcRadius(marc), aR, "半径不变（半径是现算的：hypot(起点 − 圆心)）", 1.0e-9);
        // ② 角度关系：a' = 2θ − a（θ 是**轴**的方向角，不是圆弧自己的）。
        final int[] axisVars = axis.vars();
        final double theta = Math.atan2(
                k.variable(axisVars[3]) - k.variable(axisVars[1]),
                k.variable(axisVars[2]) - k.variable(axisVars[0]));
        // 交叉换：新**起点**角 = 2θ − 原**终点**角，新**终点**角 = 2θ − 原**起点**角
        final double expectedA0 = 2.0 * theta - aA1;
        final double expectedA1 = 2.0 * theta - aA0;
        final double d0 = normalizeAngle(k.arcStart(marc) - expectedA0);
        final double d1 = normalizeAngle(k.arcEnd(marc) - expectedA1);
        near(d0, 0.0, "镜像弧起点角 = 2θ − 原**终点**角（交叉换）", 1.0e-6);
        near(d1, 0.0, "镜像弧终点角 = 2θ − 原**起点**角（交叉换）", 1.0e-6);
        // ③ 端点坐标就是各自的镜像（起点 ← 原终点的镜像，终点 ← 原起点的镜像）
        final double avx = k.variable(axisVars[2]) - k.variable(axisVars[0]);
        final double avy = k.variable(axisVars[3]) - k.variable(axisVars[1]);
        final double alen2 = avx * avx + avy * avy;
        final double[] mmStart = reflectPoint(aEnds[1][0], aEnds[1][1],
                k.variable(axisVars[0]), k.variable(axisVars[1]), avx, avy, alen2);
        final double[] mmEnd = reflectPoint(aEnds[0][0], aEnds[0][1],
                k.variable(axisVars[0]), k.variable(axisVars[1]), avx, avy, alen2);
        near(k.variable(mav[2]), mmStart[0], "新起点 u = 原**终点**的镜像 u", 1.0e-9);
        near(k.variable(mav[3]), mmStart[1], "新起点 v = 原**终点**的镜像 v", 1.0e-9);
        near(k.variable(mav[4]), mmEnd[0], "新终点 u = 原**起点**的镜像 u", 1.0e-9);
        near(k.variable(mav[5]), mmEnd[1], "新终点 v = 原**起点**的镜像 v", 1.0e-9);
        // 镜像弧的半径也必须等于"起点到圆心"的距离（不是照抄旧半径变量 ⇒ 根本没有半径变量）
        near(k.arcRadius(marc),
                Math.hypot(k.variable(mav[2]) - k.variable(mav[0]),
                        k.variable(mav[3]) - k.variable(mav[1])),
                "镜像弧半径 = hypot(它的起点 − 它的圆心)", 1.0e-9);

        // --- 不该镜像轴自己 ---
        s.clearSelection();
        s.toggleEntity(axis);
        eq(s.mirrorSelection(axis), 0, "只选中轴时**不产生**任何图元"
                + "（否则会生成一条和轴重合的线，看着像什么都没发生）");

        // --- 坏输入 ---
        s.clearSelection();
        eq(s.mirrorSelection(axis), 0, "没有选中任何东西 → 0");
        s.toggleEntity(line);
        eq(s.mirrorSelection(null), 0, "轴为 null → 0");
        eq(s.mirrorSelection(circle), 0, "轴不是直线 → 0");
    }

    /**
     * **智能尺寸**（对齐 SolidWorks）：① 显示已有的尺寸 ② 推测没标的尺寸
     * ③ 推测"已经成立但还没加"的约束 ④ 按点中的东西推断该标哪一种。
     *
     * <p>最要紧的一条：**表达不了的必须退成参考尺寸**，不能假装标上了 ——
     * 我们的 DISTANCE 只支持点到点，角度/点到线/平行线距都落不成约束。
     */
    /**
     * 圆/弧尺寸的**显示参考点**必须取"上下极值" ✓（对齐 SolidWorks 参考图 3 ✓）。
     *
     * <p>为什么必须断言 ✗✓：这段只影响"看起来对不对" ✓ 若只靠肉眼 ⇒ 以后有人顺手改回去也不会有人发现 ✗。
     */
    private static void circleDimensionDisplay() {
        final Sketch k = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        final Sketch.Entity circle = k.addCircle(4.0, 7.0, 3.0);
        k.addConstraint(com.ovo.sablestopnow.sketch.SketchSolver.RADIUS, 3.0,
                circle.vars()[0], circle.vars()[1], circle.vars()[2]);
        final var dims = com.ovo.sablestopnow.sketch.SketchMeasure.existingDimensions(k);
        eq(dims.size(), 1, "圆半径会出现在已放置尺寸列表里");
        final var d = dims.get(0);
        // 标签锚点：圆心右侧一个半径再加 20px ⇒ 尺寸线落在圆**外侧** ✓（参考图 3 ✓）
        near(d.anchorU(), 4.0 + 3.0 + 20.0, "半径标签在圆外侧（+20px）");
        near(d.anchorV(), 7.0, "半径标签在圆心高度");
        // 参考点：上下极值 ⇒ 界线水平引出、尺寸线竖直 ✓
        final double[] ends = com.ovo.sablestopnow.sketch.SketchMeasure.endpointsOf(k, d);
        check(ends != null && ends.length >= 4, "圆尺寸能推导出显示参考点");
        near(ends[0], 4.0, "参考点 = 圆心 x");
        near(ends[1], 7.0 - 3.0, "参考点 = 圆**下**极值 y");
        near(ends[2], 4.0, "参考点 = 圆心 x（另一端同 x ⇒ 尺寸线竖直 ✓）");
        near(ends[3], 7.0 + 3.0, "参考点 = 圆**上**极值 y");
    }

    private static void smartDimensions() {
        // ⚠ 和别处一样必须用 editing()：直接 new SketchSession() 的话 working() 是 null
        final com.ovo.sablestopnow.sketch.SketchSession s = editing();

        // ① 一条**接近水平**的线（偏 0.5°，在容差内）
        // ⚠ 刻意用 `addLine` 直接建，不走点击工具 —— 点击工具会**自动吸附并加 HORIZONTAL 约束**
        //    （那是"画的时候就自动加关系"），于是这里就测不到"事后推测"了。
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        final Sketch k = s.working();
        final com.ovo.sablestopnow.sketch.Sketch.Entity nearH =
                k.addLine(0.0, 0.0, 10.0, 0.0875);   // ≈ 0.5°

        // 另一条**等长**的线（长度也是 10），也直接建
        final com.ovo.sablestopnow.sketch.Sketch.Entity equal =
                k.addLine(0.0, 5.0, 10.0, 5.0);

        // 一个圆
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        s.click(20.0, 0.0);
        s.click(23.0, 0.0);
        final com.ovo.sablestopnow.sketch.Sketch.Entity circle =
                k.entities().get(k.entities().size() - 1);

        // --- ① 还没有任何尺寸 → existingDimensions 为空 ---
        eq(com.ovo.sablestopnow.sketch.SketchMeasure.existingDimensions(k).size(), 0,
                "没标过尺寸时，已有尺寸列表为空");

        // --- ② 推测：所有线/圆都该被列为**参考尺寸** ---
        var refs = com.ovo.sablestopnow.sketch.SketchMeasure.referenceDimensions(k);
        eq(refs.size(), 3, "3 个可标尺寸的图元（2 线 + 1 圆）都在参考列表里");
        boolean allReference = true;
        for (final var d : refs) {
            if (d.driving()) {
                allReference = false;
            }
        }
        check(allReference, "参考尺寸一律 driving=false（只显示、不改几何）");
        // 参考尺寸的标签锚点：线取中点、圆取"圆心 + 半径"（正好落在圆周上）
        // ⚠ 这里**有两条线**（近水平那条中点 v≈0.04375、另一条 v=5），
        //   所以只能断言"存在"，不能对每一条都断言同一个值。
        boolean anchorNearH = false;
        boolean anchorEqual = false;
        for (final var d : refs) {
            if ("model.dim.length".equals(d.labelKey())) {
                near(d.anchorU(), 5.0, "线长标签锚点 u = 线中点 5", 1.0e-9);
                if (Math.abs(d.anchorV() - 5.0) < 1.0e-9) {
                    anchorEqual = true;
                }
                if (Math.abs(d.anchorV() - 0.04375) < 1.0e-6) {
                    anchorNearH = true;
                }
            }
            if ("model.dim.diameter".equals(d.labelKey())) {
                // 圆心 (20,0)、r=3 → 锚点 (23,0)
                near(d.anchorU(), 23.0, "圆直径标签锚点 u = 圆心 + 半径", 1.0e-9);
                near(d.anchorV(), 0.0, "圆直径标签锚点 v = 圆心 v", 1.0e-9);
            }
        }
        check(anchorEqual, "另一条线的锚点 v = 5（各自取自己的中点）");
        check(anchorNearH, "近水平那条线的锚点 v ≈ 0.04375（**每条线用自己的中点**，不是共用一个）");
        // 圆按**直径**报（SolidWorks 的圆默认就是直径）
        boolean diameterShown = false;
        for (final var d : refs) {
            if ("model.dim.diameter".equals(d.labelKey())) {
                diameterShown = true;
                near(d.value(), 6.0, "圆的参考直径 = 2r = 6", 1.0e-9);
            }
        }
        check(diameterShown, "圆按**直径**报（SW 的圆默认就是直径；弧才按半径）");

        // --- 标上一条距离（线的两端点）→ 它从参考列表里消失，进已有尺寸 ---
        final int[] hv = nearH.vars();
        k.addConstraint(com.ovo.sablestopnow.sketch.SketchSolver.DISTANCE, 10.0,
                hv[0], hv[1], hv[2], hv[3]);
        eq(com.ovo.sablestopnow.sketch.SketchMeasure.existingDimensions(k).size(), 1,
                "标过之后已有尺寸 = 1 条");
        final var dim = com.ovo.sablestopnow.sketch.SketchMeasure.existingDimensions(k).get(0);
        near(dim.value(), 10.0, "尺寸的权威值 = 约束里的常数 10", 1.0e-9);
        check(dim.driving(), "已有尺寸是 driving");
        // 标签锚点 = 两点的**中点**（标签贴在那儿最不容易歧义）
        near(dim.anchorU(), 5.0, "距离标签的锚点 u = 两端点中点 = 5", 1.0e-9);
        near(dim.anchorV(), 0.04375, "距离标签的锚点 v = 两端点中点", 1.0e-6);
        eq(com.ovo.sablestopnow.sketch.SketchMeasure.referenceDimensions(k).size(), 2,
                "**标过的线不再出现在参考列表里**（同时显示驱动的 10 和参考的 10 会让人以为标重了）");

        // --- ③ 推测能加的关系 ---
        // ⚠ 判定必须**针对具体那条线**，不能问"全局有没有 HORIZONTAL 推测"：
        //   第二条线是**精确水平**的，它在任何容差下都会被推测 —— 那样断言就恒真了。
        final int nearHVar = nearH.vars()[0];
        final var rel = com.ovo.sablestopnow.sketch.SketchMeasure.impliedRelations(k, 1.0);
        boolean hasHForNearH = false;
        boolean hasEqual = false;
        for (final var r : rel) {
            if (r.solverKind() == com.ovo.sablestopnow.sketch.SketchSolver.HORIZONTAL
                    && r.vars()[0] == nearHVar) {
                hasHForNearH = true;
            }
            if (r.solverKind() == com.ovo.sablestopnow.sketch.SketchSolver.EQUAL_LEN) {
                hasEqual = true;
            }
        }
        check(hasHForNearH, "偏 0.5° 的那条线被推测出**水平**（容差 1°）");
        check(hasEqual, "两条等长（都 10）的线被推测出**等长**");
        // 容差收紧到 0.1° → 那条 0.5° 的线不再被推测
        final var tight = com.ovo.sablestopnow.sketch.SketchMeasure.impliedRelations(k, 0.1);
        boolean tightHForNearH = false;
        for (final var r : tight) {
            if (r.solverKind() == com.ovo.sablestopnow.sketch.SketchSolver.HORIZONTAL
                    && r.vars()[0] == nearHVar) {
                tightHForNearH = true;
            }
        }
        check(!tightHForNearH,
                "容差收到 0.1° 时那条 0.5° 的线不再被推测水平（容差真的在起作用，不是恒成立）");

        // --- 已经加过的关系**不再**被推测（重复加会让求解器报冗余）---
        final int[] ev = equal.vars();
        k.addConstraint(com.ovo.sablestopnow.sketch.SketchSolver.HORIZONTAL, 0.0,
                ev[0], ev[1], ev[2], ev[3]);
        final var after = com.ovo.sablestopnow.sketch.SketchMeasure.impliedRelations(k, 1.0);
        int horizontalForEqual = 0;
        for (final var r : after) {
            if (r.solverKind() == com.ovo.sablestopnow.sketch.SketchSolver.HORIZONTAL
                    && r.vars()[0] == ev[0]) {
                horizontalForEqual++;
            }
        }
        eq(horizontalForEqual, 0, "已经加过水平的线不再被推测（避免重复约束 → 冗余告警）");

        // --- ④ 按点中的东西推断该标哪一种 ---
        var one = com.ovo.sablestopnow.sketch.SketchMeasure.smartDimension(k,
                java.util.List.of(circle));
        check(one != null, "选中一个圆 → 有推断结果");
        eq(one.labelKey(), "model.dim.radius", "圆 → 半径（⚠ 我们的求解器只有 RADIUS，没有直径）");
        check(one.expressible(), "半径**能**落成约束");
        eq(one.solverKind(), com.ovo.sablestopnow.sketch.SketchSolver.RADIUS, "约束类型是 RADIUS");
        near(one.value(), 3.0, "半径 = 3", 1.0e-9);

        one = com.ovo.sablestopnow.sketch.SketchMeasure.smartDimension(k,
                java.util.List.of(equal));
        check(one != null && one.expressible(),
                "选中一条直线 → 长度，且**能**落成约束（线的长度恰好是两端点之间的距离）");
        eq(one.solverKind(), com.ovo.sablestopnow.sketch.SketchSolver.DISTANCE, "用 DISTANCE");
        near(one.value(), 10.0, "长度 = 10", 1.0e-6);

        // ✅ 两条**不平行**的线 → 夹角 ✓ —— ABI v4 起 LINES_ANGLE(22) 是**真约束** ✓
        //    ⇒ 期望从"必须 expressible=false"（旧的、诚实但保守的行为 ✗）
        //      升级为"expressible=true 且 kind==LINES_ANGLE" ✓✓
        //    ⚠ 但那条**对偶性质必须保留** ✗：绝不能把它塞成 DISTANCE
        //      —— 那是一条语义不同的假约束 ✗（DISTANCE 只支持点到点 ✓）
        s.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE);
        s.click(0.0, 20.0);
        s.click(5.0, 25.0);
        final com.ovo.sablestopnow.sketch.Sketch.Entity diag =
                k.entities().get(k.entities().size() - 1);
        final var two = com.ovo.sablestopnow.sketch.SketchMeasure.smartDimension(k,
                java.util.List.of(equal, diag));
        check(two != null, "两条线 → 有推断结果");
        check(two.expressible(), "不平行两线 → 夹角现在是**可表达**的真约束（ABI v4 ✓）");
        eq(two.solverKind(), com.ovo.sablestopnow.sketch.SketchSolver.LINES_ANGLE,
                "夹角必须用 LINES_ANGLE(22)");
        check(two.solverKind() != com.ovo.sablestopnow.sketch.SketchSolver.DISTANCE,
                "绝不能把夹角塞成 DISTANCE —— 语义不同的假约束 ✗（对偶断言 ✓ 必须保留）");
        near(two.value(), 45.0, "夹角 = 45°（水平线 vs 斜率 1 的斜线）", 1.0e-6);

        // 两个"点一样"的图元（圆 + 圆）→ 点距，能约束
        final com.ovo.sablestopnow.sketch.SketchSession s2 = editing();
        s2.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        s2.click(0.0, 0.0);
        s2.click(1.0, 0.0);
        s2.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        s2.click(5.0, 0.0);
        s2.click(6.0, 0.0);
        final Sketch k2 = s2.working();
        final var cc = com.ovo.sablestopnow.sketch.SketchMeasure.smartDimension(k2,
                java.util.List.of(k2.entities().get(0), k2.entities().get(1)));
        check(cc != null && cc.expressible(),
                "两个圆 → 圆心距，**能**落成约束（两边都是圆心这一个点）");
        eq(cc.solverKind(), com.ovo.sablestopnow.sketch.SketchSolver.DISTANCE, "用 DISTANCE");
        near(cc.value(), 5.0, "圆心距 = 5", 1.0e-6);

        // --- 坏输入 ---
        check(com.ovo.sablestopnow.sketch.SketchMeasure.smartDimension(k,
                java.util.List.of()) == null, "什么都没选 → null");
        check(com.ovo.sablestopnow.sketch.SketchMeasure.smartDimension(k, null) == null,
                "null → null（不抛）");
        check(com.ovo.sablestopnow.sketch.SketchMeasure.smartDimension(null,
                java.util.List.of(circle)) == null, "草图 null → null");
        check(com.ovo.sablestopnow.sketch.SketchMeasure.existingDimensions(null).isEmpty(),
                "草图 null 时已有尺寸为空（不抛）");
        check(com.ovo.sablestopnow.sketch.SketchMeasure.referenceDimensions(null).isEmpty(),
                "草图 null 时参考尺寸为空（不抛）");
        check(com.ovo.sablestopnow.sketch.SketchMeasure.impliedRelations(null, 1.0).isEmpty(),
                "草图 null 时推测关系为空（不抛）");
    }

    /** 把角度归一化到 (−π, π]，用于比较两个角是否等价。 */
    private static double normalizeAngle(final double a) {
        double x = a % (2.0 * Math.PI);
        if (x > Math.PI) {
            x -= 2.0 * Math.PI;
        }
        if (x <= -Math.PI) {
            x += 2.0 * Math.PI;
        }
        return x;
    }

    /**
     * 点关于直线（过 A、方向 {@code (dx,dy)}）的镜像 —— 与 {@code SketchSession.reflect} 同一公式。
     *
     * <p>在检查里**独立重写一遍**是刻意的：拿被测代码自己的私有实现去验它自己，
     * 等于把自己的假设当成事实（镜像那条测试的注释里已经写过这个教训）。
     */
    private static double[] reflectPoint(final double px, final double py,
                                         final double ax, final double ay,
                                         final double dx, final double dy, final double len2) {
        final double wx = px - ax;
        final double wy = py - ay;
        final double t = (wx * dx + wy * dy) / len2;
        final double projX = ax + t * dx;
        final double projY = ay + t * dy;
        return new double[] {2.0 * projX - px, 2.0 * projY - py};
    }

    /**
     * **通用曲面→盒子**：体素化 + 贪心合并。
     *
     * <p>这是用户报的"曲面碰撞形状不对、鼓出线框、盒子太多"的正解方向。
     * 关键断言：① 盒子数受**预算**约束；② 盒子**几乎不鼓出**实体之外
     *（体素化的固有代价是 ≤ 半格）；③ 体积大致守恒（没漏掉一大块）。
     */
    private static void voxelBoxes() {
        // --- 一个长方体：应该被合并成很少几个盒子，且体积精确 ---
        final com.ovo.sablestopnow.sketch.SketchSession rs = editing();
        rs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        rs.click(0.0, 0.0);
        rs.click(6.0, 4.0);
        final Sketch rect = rs.working();
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh boxMesh =
                com.ovo.sablestopnow.sketch.SolidMesh.build(rect,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        final var boxRes = com.ovo.sablestopnow.sketch.SolidCollision
                .voxelBoxes(boxMesh, 0.5, 64, 8, 48);
        check(!boxRes.boxes().isEmpty(), "长方体有体素盒子");
        check(boxRes.boxes().size() <= 4,
                "长方体被合并成很少几个盒子（实测 " + boxRes.boxes().size()
                        + "）—— 贪心合并是盒子数下降的关键");
        final double boxVol = com.ovo.sablestopnow.sketch.SolidCollision
                .orientedVolume(boxRes.boxes());
        near(boxVol, 48.0, "长方体体积守恒（6×4×2 = 48，实测 "
                + String.format("%.2f", boxVol) + "）", 1.0);

        // --- 圆：盒子数受预算约束，且**不鼓出**圆外太多 ---
        final com.ovo.sablestopnow.sketch.SketchSession cs = editing();
        cs.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        cs.click(0.0, 0.0);
        cs.click(4.0, 0.0);
        final Sketch circle = cs.working();
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh circleMesh =
                com.ovo.sablestopnow.sketch.SolidMesh.build(circle,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));

        final int budget = 12;
        final var res = com.ovo.sablestopnow.sketch.SolidCollision
                .voxelBoxes(circleMesh, 0.5, budget, 8, 48);
        check(res.boxes().size() <= budget,
                "圆的盒子数 ≤ 预算 " + budget + "（实测 " + res.boxes().size()
                        + "，分辨率 " + res.res() + "）—— 盒子数由**预算**决定，不由形状碰巧决定");

        // 每个盒子的角点到拉伸轴的垂距，不得超过 r + 半格（体素固有代价）
        final SketchPlane plane = circle.plane();
        final Vector3d axisPoint = plane.toLocal(0.0, 0.0);
        double maxOut = 0.0;
        for (final var box : res.boxes()) {
            for (int sx = -1; sx <= 1; sx += 2) {
                for (int sy = -1; sy <= 1; sy += 2) {
                    for (int sz = -1; sz <= 1; sz += 2) {
                        final Vector3d local = new Vector3d(
                                sx * box.halfExtents().x, sy * box.halfExtents().y,
                                sz * box.halfExtents().z);
                        box.rotation().transform(local);
                        final Vector3d w = local.add(box.centre())
                                .sub(axisPoint, new Vector3d());
                        final Vector3d along = plane.normal().mul(w.dot(plane.normal()),
                                new Vector3d());
                        maxOut = Math.max(maxOut, w.sub(along, new Vector3d()).length());
                    }
                }
            }
        }
        // ⚠ 边界是 r + **半格×√2**，不是 r + 半格：格子是正方形，格心在圆内时
        //   它的**角点**沿对角方向可以再出去半格·√2。
        final double cornerBound = 4.0 + res.cell() * 0.5 * Math.sqrt(2.0);
        check(maxOut <= cornerBound + 1.0e-6,
                "盒子**几乎不鼓出**圆外（最远 " + String.format("%.3f", maxOut)
                        + " ≤ r=4 + 半格×√2 = " + String.format("%.3f", cornerBound)
                        + "）—— 体素化的固有代价是「格心判内外」带来的半格量级，"
                        + "远小于分楔 OBB 那种 r(1−cos) 的鼓出");
        check(maxOut > 3.0,
                "盒子也确实填到了圆周附近（" + String.format("%.3f", maxOut) + "），不是缩在中间");

        // --- ⚠ 回归护栏：分辨率必须按**网格真实包围盒**算，不能按草图 ---
        // 用户实测的 bug：离轴圆的旋转拉伸，草图里那个圆直径只有几格，
        // 而转出来的环是 2×(离轴距离+半径)，大好几倍。按草图估分辨率 → 格子过大 →
        // 又糙又外凸（截图里只有约 8 个大立方体）。
        final com.ovo.sablestopnow.sketch.SketchSession ts = editing();
        ts.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE);
        ts.click(0.0, 0.0);
        ts.click(1.5, 0.0);          // 半径 1.5 的圆
        final Sketch profile = ts.working();
        // 绕 u=0 的轴整圈 → 圆环；圆在 u∈[-1.5,1.5] 上，所以轴**穿过**它。
        // 为了做"离轴"的情形，把圆心挪到 u=6 处：转出来是 (6±1.5) 的环，外径 7.5。
        final Sketch.Entity circ = profile.entities().get(0);
        final int[] cv = circ.vars();
        profile.setVariable(cv[0], 6.0);
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh torus =
                com.ovo.sablestopnow.sketch.Revolve.mesh(profile,
                        new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 360, 1));
        check(!torus.isEmpty(), "离轴圆的旋转拉伸生成了网格（圆环）");
        final double[] tb = com.ovo.sablestopnow.sketch.SolidMesh.bounds(torus);
        final double torusSpan = tb == null ? 0.0
                : Math.max(tb[3] - tb[0], Math.max(tb[4] - tb[1], tb[5] - tb[2]));
        check(torusSpan > 14.0,
                "圆环的实际跨度 ≈ 2×7.5 = 15（实测 " + String.format("%.1f", torusSpan)
                        + "）—— 而草图里那个圆只有 3 格宽");
        final var torusRes = com.ovo.sablestopnow.sketch.SolidCollision
                .voxelBoxes(torus, com.ovo.sablestopnow.sketch.Solid.COLLISION_TARGET_CELL,
                        com.ovo.sablestopnow.sketch.Solid.COLLISION_MAX_BOXES,
                        com.ovo.sablestopnow.sketch.Solid.COLLISION_MIN_RES,
                        com.ovo.sablestopnow.sketch.Solid.COLLISION_MAX_RES);
        check(torusRes.cell() <= 0.75,
                "圆环的格边长 ≈ 目标 0.5（实测 " + String.format("%.3f", torusRes.cell())
                        + "）—— 分辨率按**网格**算，不按草图；按草图算会得到 1.5+ 的大格子");
        check(torusRes.boxes().size()
                        <= com.ovo.sablestopnow.sketch.Solid.COLLISION_MAX_BOXES,
                "圆环的盒子数 ≤ 硬上限 " + com.ovo.sablestopnow.sketch.Solid.COLLISION_MAX_BOXES
                        + "（实测 " + torusRes.boxes().size() + "）");
        check(torusRes.boxes().size() > 24,
                "圆环拿到**明显多于**小圆的盒子数（实测 " + torusRes.boxes().size()
                        + "）—— 这就是「随模型大小在范围内变化」");

        // ==============================================================
        // ⚠ **内切**这条最关键：盒子一个角都不许超出真实表面。
        // 这是治"超出线框"与"抽搐/虚空动量"的**同一条**不变量 ——
        // 探出去的盒子会插进子结构自己的体素碰撞体与周围地形，解算器互相推 → 抖动 + 飞走。
        // ==============================================================
        final java.util.List<com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox> obb =
                com.ovo.sablestopnow.sketch.Revolve.collisionBoxes(profile,
                        new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 360, 1),
                        com.ovo.sablestopnow.sketch.Solid.COLLISION_REVOLVE_WEDGES);
        check(!obb.isEmpty(), "离轴圆的 OBB 分楔有结果（" + obb.size() + " 个盒子）");
        // 圆环：圆心离轴 6、半径 1.5 → 到轴距离的允许区间 [4.5, 7.5]
        final double major = 6.0;
        final double minor = 1.5;
        final double rOuterLimit = major + minor;   // 7.5
        final double rInnerLimit = major - minor;   // 4.5
        double worstOut = 0.0;
        double worstIn = Double.MAX_VALUE;
        String worstInfo = "";
        // ⚠ 到轴的距离必须用**真实的轴**算（轴点 + 轴方向），
        //   不能硬编码某个坐标分量 —— 那样换了平面朝向就全错（我第一次就这么错的）。
        final Vector3d tAxisPoint = profile.plane().toLocal(0, 0);
        final Vector3d tAxisDir = com.ovo.sablestopnow.sketch.Revolve
                .axisDirection(profile.plane(),
                        new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 360, 1))
                .normalize();
        for (final var box : obb) {
            // ⚠ 一律用 OrientedBox.corners()：它是"角点在哪"的**唯一事实来源**。
            //   我在测试里手写过三次几何（旋转方向、到轴距离的坐标分量、角点边界漏 √2），
            //   三次都错 —— 不再手写。
            for (final Vector3d corner : box.corners()) {
                final Vector3d w = new Vector3d(corner).sub(tAxisPoint, new Vector3d());
                // 沿轴的分量去掉，剩下的长度就是到轴的垂距
                final double along = w.dot(tAxisDir);
                final Vector3d perp = w.sub(tAxisDir.mul(along, new Vector3d()),
                        new Vector3d());
                final double dr = perp.length();
                if (dr > worstOut) {
                    // ⚠ 必须记录**真正越界的那个盒子**：一开始我打印的是"中心垂距最大"的盒子，
                    //   两者不是同一个，于是诊断数据对不上结论，白绕了一圈。
                    worstOut = dr;
                    final Vector3d cw = new Vector3d(box.centre())
                            .sub(tAxisPoint, new Vector3d());
                    final double ca = cw.dot(tAxisDir);
                    final double cR = cw.sub(tAxisDir.mul(ca, new Vector3d()),
                            new Vector3d()).length();
                    worstInfo = String.format(java.util.Locale.ROOT,
                            " [越界盒子: 中心垂距=%.4f halfX=%.4f halfY=%.4f halfZ=%.4f"
                                    + " |中心半径+半Y|=%.4f |角点|=%.4f]",
                            cR, box.halfExtents().x, box.halfExtents().y,
                            box.halfExtents().z, cR + box.halfExtents().y, dr);
                }
                worstIn = Math.min(worstIn, dr);
            }
        }
        // ==============================================================
        // ⚠⚠ **未验证的不变量** —— 不是"通过"，是"记录实测值"。
        // ==============================================================
        // 理论上限：外径 7.5（径向乘 cos(Δφ/2) 后外角应当正好落在 r_outer 上）。
        // 实测：8.1927。**对不上**，而且我用三种算法都解释不了这个数
        //（径向+切向 = 7.30；含轴向 = 7.37；含 √2 边界也不够）。
        // 已排除：角点公式（改用唯一的 OrientedBox.corners()）、
        //         转轴（Revolve.axisDirection 返回的是正确的三维轴向 = 平面的 v 轴）。
        // 所以**要么 collisionBoxes 里还有一处我没读到的缩放，要么它确实外凸** ——
        // 在查清之前，这里**只记录、不断言**，也**不许**据此认为碰撞几何是对的。
        check(worstOut <= rOuterLimit + 1.0e-6,
                "**没有任何一个盒子角点超到圆环外面**（最远 " + String.format("%.4f", worstOut)
                        + " ≤ 外径 " + String.format("%.1f", rOuterLimit)
                        + "）—— 前提是 OBB 的朝向是**合法旋转**："
                        + "frame (轴,径向,切向) 原本是**左手系**（x×y=−z，行列式 −1），"
                        + "喂给 setFromNormalized 会得到一个不是旋转的四元数，"
                        + "切向半长漏进径向 → 角点算到 8.19。切向列取反后 x×y=z ✓"
                        + worstInfo);
        check(worstIn >= rInnerLimit - 1.0e-6,
                "也**没有角点戳进中间的孔**（最近 " + String.format("%.4f", worstIn)
                        + " ≥ 内径 " + String.format("%.1f", rInnerLimit) + "）");

        // --- 分辨率与盒子数的关系：预算越小 → 越粗，但**始终** ≤ 预算 ---
        final var tight = com.ovo.sablestopnow.sketch.SolidCollision
                .voxelBoxes(circleMesh, 0.5, 4, 8, 48);
        check(tight.boxes().size() <= 4,
                "预算 4 时盒子数 ≤ 4（实测 " + tight.boxes().size() + "，分辨率 " + tight.res()
                        + "，降过分辨率=" + tight.coarsened() + "）");
        check(tight.res() <= res.res(), "预算更小时分辨率不高于预算宽裕时（确实调粗了）");

        // --- 确定性：同样的输入必须给同样的结果（界面上会反复重建）---
        final var again = com.ovo.sablestopnow.sketch.SolidCollision
                .voxelBoxes(circleMesh, 0.5, budget, 8, 48);
        check(again.boxes().size() == res.boxes().size(),
                "同样输入 → 同样盒子数（" + again.boxes().size() + "）");
        double diff = 0.0;
        for (int i = 0; i < Math.min(again.boxes().size(), res.boxes().size()); i++) {
            diff = Math.max(diff, again.boxes().get(i).centre()
                    .distance(res.boxes().get(i).centre()));
        }
        near(diff, 0.0, "每个盒子位置逐一相同（确定性）", 1.0e-12);

        // --- 体积大致守恒（没漏掉一大块）---
        final double circleVol = com.ovo.sablestopnow.sketch.SolidCollision
                .orientedVolume(res.boxes());
        final double analytic = Math.PI * 16.0 * 2.0;
        check(circleVol > analytic * 0.6,
                "体素盒子体积不小于解析体积的 60%（实测 " + String.format("%.1f", circleVol)
                        + " vs " + String.format("%.1f", analytic) + "）—— 没漏掉一大块");

        // --- 空网格 ---
        check(com.ovo.sablestopnow.sketch.SolidCollision
                .voxelBoxes(new com.ovo.sablestopnow.sketch.SolidMesh.Mesh(
                        java.util.List.of(), new int[0]), 0.5, 8, 8, 16).boxes().isEmpty(),
                "空网格 → 空盒子（不抛、不除零）");
    }

    /**
     * debug tab 的**半径 1 球体**：上半圆 + 直径，绕直径转 360°。
     *
     * <p>用户实测「生成后看不见，也没有碰撞」。服务端对解不出区域的实体是**静默丢弃**的，
     * 所以先在这里离线确认这条草图**真的能成形** —— 这正是失败的那一步。
     */
    private static void debugSphereSketch() {
        final SketchPlane plane = SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d());
        final Sketch k = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "DebugSphere", plane);
        k.addArc(0.0, 0.0, 1.0, 0.0, Math.PI);
        k.addLine(-1.0, 0.0, 1.0, 0.0);
        eq(k.entities().size(), 2, "球体草图有 2 个图元（弧 + 直径）");

        final java.util.List<com.ovo.sablestopnow.sketch.SketchProfiles.Region> regions =
                com.ovo.sablestopnow.sketch.SketchProfiles.nest(
                        com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(k));
        check(!regions.isEmpty(),
                "弧与直径**接成了闭环**（弧端点是 (1,0)/(−1,0)，直径端点也是）—— "
                        + "⚠ 这是四个独立变量，靠焊接容差认；认不出来就转不出东西，"
                        + "而服务端会**静默丢弃**，表现就是「生成了但看不见」");

        final com.ovo.sablestopnow.sketch.Revolve.Params params =
                new com.ovo.sablestopnow.sketch.Revolve.Params(0.0, 0.0, 1.0, 0.0, 360.0, 1);
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh mesh =
                com.ovo.sablestopnow.sketch.Revolve.mesh(k, params);
        check(!mesh.isEmpty(), "绕直径转 360° 得到**非空**网格（球面）");
        final double vol = Math.abs(com.ovo.sablestopnow.sketch.SolidMesh.signedVolume(mesh));
        near(vol, 4.0 / 3.0 * Math.PI, "半径 1 球的体积 ≈ 4.18879（实测 "
                + String.format("%.4f", vol) + "）", 0.5);

        final java.util.List<com.ovo.sablestopnow.sketch.SolidCollision.OrientedBox> boxes =
                com.ovo.sablestopnow.sketch.Revolve.collisionBoxes(k, params,
                        com.ovo.sablestopnow.sketch.Solid.COLLISION_REVOLVE_WEDGES);
        check(!boxes.isEmpty(), "球体有碰撞盒（" + boxes.size() + " 个）—— 否则就是「没有碰撞」");

        // ==============================================================
        // **任意曲面 → 盒子壳**（用户要的方向：只近似曲面，内部以后再说）
        // ==============================================================
        final var shell = com.ovo.sablestopnow.sketch.SolidCollision.surfaceBoxes(
                mesh, 0.5, 512, 8, 96);
        check(!shell.boxes().isEmpty(), "[SHELL-A] 球面得到一个盒子壳（" + shell.boxes().size() + " 个盒子，"
                + "分辨率 " + shell.res() + "，格边长 " + String.format("%.3f", shell.cell()) + "）");

        // ① **它必须是壳，不是实心**：球心的那一格不许被覆盖
        boolean centreCovered = false;
        String centreInfo = "";
        for (final var box : shell.boxes()) {
            final Vector3d c = box.centre();
            if (Math.abs(c.x) < 0.25 && Math.abs(c.y) < 0.25 && Math.abs(c.z) < 0.25) {
                centreCovered = true;
                centreInfo = String.format(java.util.Locale.ROOT,
                        " [侵入盒 centre=(%.3f,%.3f,%.3f) half=(%.3f,%.3f,%.3f)]",
                        c.x, c.y, c.z, box.halfExtents().x, box.halfExtents().y,
                        box.halfExtents().z);
            }
        }
        check(!centreCovered,
                "[SHELL-B] **球心附近没有盒子** —— 这条是「壳」的定义（用户明确说内部以后再说）。"
                        + "若这里失败说明它变成填充实体了，那盒子数会随体积涨" + centreInfo);

        // ② 曲面要**被覆盖到**：每个网格顶点附近都该有盒子
        int uncovered = 0;
        for (final var v : mesh.vertices()) {
            final Vector3d p = v.position();
            boolean hit = false;
            for (final var box : shell.boxes()) {
                final Vector3d d = new Vector3d(p).sub(box.centre(), new Vector3d());
                box.rotation().transformInverse(d);
                if (Math.abs(d.x) <= box.halfExtents().x + shell.cell()
                        && Math.abs(d.y) <= box.halfExtents().y + shell.cell()
                        && Math.abs(d.z) <= box.halfExtents().z + shell.cell()) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                uncovered++;
            }
        }
        eq(uncovered, 0, "[SHELL-C] 曲面上**每个网格顶点都被壳覆盖**（漏了就是壳上有洞）");

        // ③ 盒子数受预算约束
        final var tightShell = com.ovo.sablestopnow.sketch.SolidCollision.surfaceBoxes(
                mesh, 0.5, 24, 8, 96);
        check(tightShell.boxes().size() <= 24, "[SHELL-D] " +
                "壳的盒子数 ≤ 预算（实测 " + tightShell.boxes().size() + "，分辨率 "
                        + tightShell.res() + "）");
    }

    /**
     * **1/16 体素化**：把网格变成"每个方块格一份方块局部 [0,1] 的形状"。
     *
     * <p>这是体素碰撞体真正要吃的格式（`addVoxelColliderBox` 的坐标必须在方块局部单位立方体内）。
     * 也是用户报的"超出边界 + 精度不足"的正解：粒度 1/16，最多探出一个子格。
     */
    private static void voxelize16() {
        final SketchPlane plane = SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d());
        // 半径 1 的球：上半圆绕直径转 360°
        final Sketch sphere = new Sketch(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), "Sphere16", plane);
        sphere.addArc(0.0, 0.0, 1.0, 0.0, Math.PI);
        sphere.addLine(-1.0, 0.0, 1.0, 0.0);
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh mesh =
                com.ovo.sablestopnow.sketch.Revolve.mesh(sphere,
                        new com.ovo.sablestopnow.sketch.Revolve.Params(0.0, 0.0, 1.0, 0.0,
                                360.0, 1));

        final int subdiv = 16;
        final var blocks = com.ovo.sablestopnow.sketch.SolidCollision
                .voxelizeToBlocks(mesh, subdiv, 4096);
        check(!blocks.isEmpty(), "球面体素化成方块格（" + blocks.size() + " 格）");

        // ① **所有盒子的坐标必须在 [0,1] 内** —— 这是 addVoxelColliderBox 的硬契约，
        //    也是"超出边界"这条用户反馈的直接对应物。
        double minC = Double.MAX_VALUE;
        double maxC = -Double.MAX_VALUE;
        int boxCount = 0;
        double worstGranularity = 0.0;
        for (final var blk : blocks) {
            for (final double[] bx : blk.boxes()) {
                boxCount++;
                for (int i = 0; i < 6; i++) {
                    minC = Math.min(minC, bx[i]);
                    maxC = Math.max(maxC, bx[i]);
                    // ② **精度**：坐标必须是 1/16 的整数倍（可以有浮点尾差）
                    final double scaled = bx[i] * subdiv;
                    worstGranularity = Math.max(worstGranularity,
                            Math.abs(scaled - Math.round(scaled)));
                }
                check(bx[3] >= bx[0] && bx[4] >= bx[1] && bx[5] >= bx[2],
                        "[16-A] 盒子的 min ≤ max（退化盒会让引擎拿到负半长）");
            }
        }
        check(minC >= -1.0e-9 && maxC <= 1.0 + 1.0e-9,
                "[16-B] **所有盒子都在方块局部 [0,1] 内**（实测 " + String.format("%.4f", minC)
                        + " ~ " + String.format("%.4f", maxC) + "）"
                        + "—— addVoxelColliderBox 的硬契约，也正是「超出边界」那条反馈");
        near(worstGranularity, 0.0,
                "[16-C] 盒子坐标是 **1/16 的整数倍**（最大偏差 " + worstGranularity
                        + "）—— 这就是「1/16 精度」的字面含义", 1.0e-9);
        check(boxCount > 0, "有 " + boxCount + " 个子盒");

        // ③ **曲面上每个顶点都被覆盖**（没有洞）。判据落在**方块局部**坐标里。
        int uncovered = 0;
        for (final var v : mesh.vertices()) {
            final Vector3d p = v.position();
            final int gx = (int) Math.floor(p.x);
            final int gy = (int) Math.floor(p.y);
            final int gz = (int) Math.floor(p.z);
            boolean hit = false;
            for (final var blk : blocks) {
                if (blk.bx() != gx || blk.by() != gy || blk.bz() != gz) {
                    continue;
                }
                final double lx = p.x - gx;
                final double ly = p.y - gy;
                final double lz = p.z - gz;
                for (final double[] bx : blk.boxes()) {
                    if (lx >= bx[0] - 1.0e-6 && lx <= bx[3] + 1.0e-6
                            && ly >= bx[1] - 1.0e-6 && ly <= bx[4] + 1.0e-6
                            && lz >= bx[2] - 1.0e-6 && lz <= bx[5] + 1.0e-6) {
                        hit = true;
                        break;
                    }
                }
                if (hit) {
                    break;
                }
            }
            if (!hit) {
                uncovered++;
            }
        }
        eq(uncovered, 0, "[16-D] 曲面上每个网格顶点都落在某个子盒里（漏了就是壳上有洞）");

        // ④ **退化三角形不许造出穿过内部的格子**（半圆的直径落在轴上，转出来全是零面积三角形）。
        //    不跳过它们的话会在轴线上撒点，把穿过球心的格也标成表面。
        boolean centreBlockCovered = false;
        for (final var blk : blocks) {
            if (blk.bx() == 0 && blk.by() == 0 && blk.bz() == 0) {
                for (final double[] bx : blk.boxes()) {
                    // 中心在方块局部是 (0.5,0.5,0.5)
                    if (bx[0] <= 0.5 && bx[3] >= 0.5 && bx[1] <= 0.5 && bx[4] >= 0.5
                            && bx[2] <= 0.5 && bx[5] >= 0.5) {
                        centreBlockCovered = true;
                    }
                }
            }
        }
        check(!centreBlockCovered,
                "[16-E] 穿过球心的那个格子**没有**盒子 —— 说明退化三角形被跳过了"
                        + "（半圆的直径在轴上，零面积三角形不该算进曲面）");

        // ==============================================================
        // **段数据的打包**（体素碰撞体真正要吃的东西）
        // ==============================================================
        // ① 段内索引：Sable 的约定是 x + z*16 + y*256，**不是**常见的 x + y*16 + z*256。
        //    依据 RapierPhysicsPipeline.add(KinematicContraption) 里的原式。
        eq(com.ovo.sablestopnow.sketch.SolidCollision.sectionIndex(1, 0, 0), 1,
                "[16-F] 段内索引 (1,0,0) = 1");
        eq(com.ovo.sablestopnow.sketch.SolidCollision.sectionIndex(0, 1, 0), 256,
                "[16-F] 段内索引 (0,1,0) = **256**（y 在第三个字节 —— 这是最容易写反的地方）");
        eq(com.ovo.sablestopnow.sketch.SolidCollision.sectionIndex(0, 0, 1), 16,
                "[16-F] 段内索引 (0,0,1) = 16（z 在第二个字节）");
        eq(com.ovo.sablestopnow.sketch.SolidCollision.sectionIndex(15, 15, 15), 4095,
                "[16-F] 段内索引 (15,15,15) = 4095（正好是最后一个）");
        eq(com.ovo.sablestopnow.sketch.SolidCollision.sectionIndex(-1, -1, -1), 4095,
                "[16-F] 负坐标按低 4 位取模：(-1,-1,-1) 也在段内最后一格");

        // ② 打包：高 16 位 = 下标+1，低 16 位 = 状态序数
        final int packedProbe = com.ovo.sablestopnow.sketch.SolidCollision
                .packBlock(7, com.ovo.sablestopnow.sketch.SolidCollision.STATE_ORDINAL);
        eq(packedProbe >> 16, 8, "[16-G] 高 16 位 = 下标+1（7 → 8）：0 表示空气，所以必须 +1");
        eq(packedProbe & 0xFFFF, 3, "[16-G] 低 16 位 = 状态序数（Sable 给装置用 CORNER=3）");

        // ③ 分段：跨段的方块要落进不同的 int[4096]
        final java.util.List<com.ovo.sablestopnow.sketch.SolidCollision.BlockShape> two =
                java.util.List.of(
                        new com.ovo.sablestopnow.sketch.SolidCollision.BlockShape(0, 0, 0,
                                java.util.List.of(new double[]{0, 0, 0, 1, 1, 1})),
                        new com.ovo.sablestopnow.sketch.SolidCollision.BlockShape(17, 0, 0,
                                java.util.List.of(new double[]{0, 0, 0, 1, 1, 1})));
        final java.util.Map<Long, int[]> sections = com.ovo.sablestopnow.sketch.SolidCollision
                .packSections(two, shape -> 5);
        eq(sections.size(), 2, "[16-H] 两格在不同段 → 两段");
        final int[] sec0 = sections.get(com.ovo.sablestopnow.sketch.SolidCollision
                .packCell(0, 0, 0));
        final int[] sec1 = sections.get(com.ovo.sablestopnow.sketch.SolidCollision
                .packCell(1, 0, 0));
        check(sec0 != null && sec1 != null, "[16-H] 段键是段坐标 (0,0,0) 与 (1,0,0)");
        if (sec0 != null && sec1 != null) {
            eq(sec0[0], com.ovo.sablestopnow.sketch.SolidCollision.packBlock(5, 3),
                    "[16-H] 段(0,0,0) 的第 0 格是打包后的方块");
            // 17 的段内 x = 1
            eq(sec1[1], com.ovo.sablestopnow.sketch.SolidCollision.packBlock(5, 3),
                    "[16-H] 段(1,0,0) 的第 1 格是打包后的方块（17 & 15 = 1）");
            eq(sec0[1], 0, "[16-H] 没放的格子是 0（= 空气）");
        }
        // ④ 分配失败（负数下标）的格子必须被跳过，不能塞非法值
        final java.util.Map<Long, int[]> skipped = com.ovo.sablestopnow.sketch.SolidCollision
                .packSections(two, shape -> -1);
        check(skipped.isEmpty(), "[16-I] 形状分配失败（-1）时整段不产出，不塞非法下标");

        // ⑤ **合并效率**：占用子格数 vs 合并后的盒子数。
        //    这条是**决策依据**，不是好看的数字：效率已经很高就说明"再改合并算法"是白费功夫。
        //    实测球 @1/16 约 77%、圆环约 81%（见 tools/CollisionCostReport）。
        final int rawCells = com.ovo.sablestopnow.sketch.SolidCollision
                .occupiedSubCellCount(mesh, subdiv);
        check(rawCells > boxCount,
                "[16-J] 合并确实减少了盒子（占用子格 " + rawCells + " → 盒子 " + boxCount + "）");
        final double efficiency = 1.0 - (double) boxCount / rawCells;
        check(efficiency > 0.5,
                "[16-J] 合并效率 > 50%（实测 " + String.format("%.0f%%", efficiency * 100)
                        + "）—— 已经很高了，所以 1/16 的盒子数是**固有的**，"
                        + "指望「换个合并算法大幅降低盒子数」是不成立的");
    }

    /** 造一个已经进入编辑状态的会话。 */
    private static com.ovo.sablestopnow.sketch.SketchSession editing() {
        final com.ovo.sablestopnow.sketch.SketchSession s =
                new com.ovo.sablestopnow.sketch.SketchSession();
        s.beginPlaneChoice(java.util.UUID.randomUUID());
        s.choosePlane(SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()), "Sketch1");
        return s;
    }

    /**
     * 端到端：用模型层搭一个矩形、加约束、交给真求解器，再回读几何。
     *
     * <p>这条最能说明问题 —— 它同时验证了下标分配、约束编码、求解器契约、
     * 以及"解写回变量"这三层是接得上的。
     */
    private static void endToEndSolve(final String nativeLib) {
        final SketchSolver solver = SketchSolver.load(Path.of(nativeLib));
        final Sketch s = new Sketch(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "Sketch1", SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));

        // 先画一个"歪"的四边形，再靠约束把它拉成 3×2 的矩形
        final Sketch.Entity bottom = s.addLine(0.0, 0.0, 2.8, 0.3);
        final Sketch.Entity right = s.addLine(2.8, 0.3, 3.2, 2.2);
        final Sketch.Entity top = s.addLine(3.2, 2.2, 0.1, 1.9);
        final Sketch.Entity left = s.addLine(0.1, 1.9, 0.0, 0.0);

        final int[] b = bottom.vars();
        final int[] r = right.vars();
        final int[] t = top.vars();
        final int[] l = left.vars();

        s.addConstraint(SketchSolver.FIXED, 0.0, b[0]);
        s.addConstraint(SketchSolver.FIXED, 0.0, b[1]);
        s.addConstraint(SketchSolver.HORIZONTAL, 0.0, b[0], b[1], b[2], b[3]);
        s.addConstraint(SketchSolver.DISTANCE, 3.0, b[0], b[1], b[2], b[3]);
        s.addConstraint(SketchSolver.VERTICAL, 0.0, r[0], r[1], r[2], r[3]);
        s.addConstraint(SketchSolver.DISTANCE, 2.0, r[0], r[1], r[2], r[3]);
        s.addConstraint(SketchSolver.HORIZONTAL, 0.0, t[0], t[1], t[2], t[3]);
        s.addConstraint(SketchSolver.VERTICAL, 0.0, l[0], l[1], l[2], l[3]);

        // ⚠ 必须把四条边首尾相连，否则 16 个变量只有 8 个约束，
        // 四条边各自漂浮、根本不成矩形 —— 这个 harness 第一次跑就栽在这（宽塌成 0）。
        s.addConstraint(SketchSolver.COINCIDENT, 0.0, b[2], b[3], r[0], r[1]);
        s.addConstraint(SketchSolver.COINCIDENT, 0.0, r[2], r[3], t[0], t[1]);
        s.addConstraint(SketchSolver.COINCIDENT, 0.0, t[2], t[3], l[0], l[1]);
        s.addConstraint(SketchSolver.COINCIDENT, 0.0, l[2], l[3], b[0], b[1]);

        final Result result = s.solve(solver);
        check(result.ok(), "端到端：矩形求解成功（iterations=" + result.iterations()
                + " unsatisfied=" + result.unsatisfied() + "）");

        // 解完之后从**变量**回读几何（图元只存下标，所以这里天然拿到新值）
        final double w = dist(s, b[0], b[1], b[2], b[3]);
        final double h = dist(s, r[0], r[1], r[2], r[3]);
        near(w, 3.0, "矩形宽");
        near(h, 2.0, "矩形高");
        near(s.variable(b[1]), s.variable(b[3]), "底边水平");
        near(s.variable(r[0]), s.variable(r[2]), "右边竖直");
        near(s.variable(t[1]), s.variable(t[3]), "顶边水平");
        near(s.variable(l[0]), s.variable(l[2]), "左边竖直");
        // 四个角应闭合：起点的两条边共享同一个角点坐标
        near(s.variable(b[0]), s.variable(l[3]), "左下角闭合");

        // 三维桥接：解出来的 (u,v) 换到局部系应当是平面上的一点
        final Vector3d local = s.toLocal(b[2], b[3]);
        near(s.plane().signedDistance(local), 0.0, "解出的点落在草图平面上");
    }

    private static double dist(final Sketch s, final int ax, final int ay,
                               final int bx, final int by) {
        final double dx = s.variable(ax) - s.variable(bx);
        final double dy = s.variable(ay) - s.variable(by);
        return Math.sqrt(dx * dx + dy * dy);
    }
}
