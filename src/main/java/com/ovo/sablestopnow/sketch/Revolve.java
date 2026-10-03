package com.ovo.sablestopnow.sketch;

import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 旋转拉伸：把草图轮廓绕**平面内的一条轴**转一个角度，得到回转体。
 *
 * <h2>轴在草图平面内</h2>
 * 轴用一个平面内的点 A 加一个平面内的方向 d 表示（都由 {@code (u,v)} 给出）。
 * 于是每个轮廓点 P 可以分解成：
 * <ul>
 *   <li><b>沿轴分量</b> {@code t = (P−A)·d} —— 旋转时不变，决定它在轴上的位置；</li>
 *   <li><b>垂距</b> {@code r = |P−A−t·d|} —— 旋转时**保持不变**，就是它画出的圆的半径。</li>
 * </ul>
 * 所以回转体上任意一点 = {@code 轴上的点 + 半径 r 的圆}。{@code r = 0} 的点在轴上，**转不动**。
 *
 * <h2>为什么输出的是"棱边"而不是盒子</h2>
 * 回转体的表面（除了顶底）**不是轴对齐的** —— 拿轴对齐盒子去套会套出很多层，
 * 而用**旋转过的盒子**（OBB）才贴得住。渲染这边先给出精确棱边；
 * 碰撞那边要用 OBB 分解，是单独一步（见 {@code SolidCollision}）。
 *
 * <p>⚠ 只依赖 JOML，可用 {@code tools/SketchModelCheck.java} 验证。
 */
public final class Revolve {

    /** 一整圈用多少段近似（渲染精度）。 */
    public static final int FULL_TURN_SEGMENTS = 48;

    /** 允许的最小/最大角度（度）。0 度转不出东西；上限是一整圈。 */
    public static final double MIN_ANGLE = 0.1;
    public static final double MAX_ANGLE = 360.0;

    /**
     * 旋转参数。
     *
     * @param axisU     轴上的一个点（平面内 u）
     * @param axisV     轴上的一个点（平面内 v）
     * @param dirU      轴方向（平面内 u，会归一化）
     * @param dirV      轴方向（平面内 v，会归一化）
     * @param angleDeg  旋转角度（度），取绝对值后夹到 {@code [MIN_ANGLE, MAX_ANGLE]}
     * @param direction 旋向：{@code +1} 或 {@code -1}。
     *                  <b>方向是有意义的</b> —— 同一个轮廓往两边转出来的是镜像的两个体。
     */
    public record Params(double axisU, double axisV, double dirU, double dirV,
                         double angleDeg, int direction) {

        public Params {
            if (!Double.isFinite(axisU) || !Double.isFinite(axisV)
                    || !Double.isFinite(dirU) || !Double.isFinite(dirV)) {
                throw new IllegalArgumentException("旋转轴参数必须是有限数");
            }
            if (Math.hypot(dirU, dirV) < 1.0e-9) {
                throw new IllegalArgumentException("旋转轴方向不能是零向量");
            }
            if (direction != 1 && direction != -1) {
                throw new IllegalArgumentException("旋向只能是 +1 或 -1");
            }
            if (!Double.isFinite(angleDeg)) {
                throw new IllegalArgumentException("旋转角度必须是有限数");
            }
            // ⚠ 夹取要在这里一次做完（而不是留给 signedRadians）：
            // 记录自己的字段就是权威值。分两处夹取的话，angleDeg() 会返回一个
            // 从没被夹过的数（0 度），而实际用的是 0.1 —— 两个答案，迟早对不上。
            angleDeg = Math.max(MIN_ANGLE, Math.min(Math.abs(angleDeg), MAX_ANGLE));
        }

        /** 有符号旋转角（弧度）——已经含了旋向。 */
        public double signedRadians() {
            return Math.toRadians(angleDeg) * direction;
        }

        /** 是不是转满一整圈。整圈时首尾重合，**不能**在接缝处画重复的面/棱。 */
        public boolean fullTurn() {
            return angleDeg >= MAX_ANGLE - 1.0e-9;
        }
    }

    private Revolve() {
    }

    /**
     * 轮廓点 {@code (u,v)} 转到角度 {@code phi}（弧度）之后的世界（plot 局部）坐标。
     *
     * <p>⚠ {@code phi} 是**绝对角度**，{@link Params#direction()} **不参与**这里 ——
     * 轴由 params 决定、角度由调用方给。旋向是调用方的事：
     * {@link #edges} 就是先取 {@code signedRadians()}（已含旋向）再按它分段。
     * 这样 {@code rotated} 本身没有隐藏状态，测试和调用都好推。
     */
    public static Vector3d rotated(final SketchPlane plane, final Params params,
                                   final double u, final double v, final double phi) {
        final double[] axis = axisBase(plane, params, u, v);
        // axis[0..2] = 轴上的基点（3D），axis[3] = 半径，axis[4..6] = phi=0 时的垂直方向
        final Vector3d perp0 = new Vector3d(axis[4], axis[5], axis[6]);
        final Vector3d dir = axisDirection(plane, params);
        final Vector3d perp = new Vector3d(dir).cross(perp0, new Vector3d());
        final double c = Math.cos(phi);
        final double s = Math.sin(phi);
        // 绕轴旋转：perp(phi) = perp0·cos + (dir × perp0)·sin
        return new Vector3d(axis[0], axis[1], axis[2])
                .add(perp0.mul(c, new Vector3d()))
                .add(perp.mul(s, new Vector3d()));
    }

    /** 轴方向的 3D 向量（已归一化）。 */
    public static Vector3d axisDirection(final SketchPlane plane, final Params params) {
        final double len = Math.hypot(params.dirU(), params.dirV());
        final Vector3d d = plane.axisU().mul(params.dirU() / len, new Vector3d())
                .add(plane.axisV().mul(params.dirV() / len, new Vector3d()));
        return d.normalize();
    }

    /** 半径：轮廓点到轴的垂距（旋转半径）。用于判断"这个点在不在轴上"（r=0 转不动）。 */
    public static double radius(final Params params, final double u, final double v) {
        final double len = Math.hypot(params.dirU(), params.dirV());
        final double du = params.dirU() / len;
        final double dv = params.dirV() / len;
        // 垂距 = |叉积(d, P−A)| = |du·wv − dv·wu|
        return Math.abs(du * (v - params.axisV()) - dv * (u - params.axisU()));
    }

    /** 轮廓点在轴上的投影（平面内 {@code {u, v}}）。 */
    private static double[] axisPointOn(final Params params, final double u, final double v) {
        final double len = Math.hypot(params.dirU(), params.dirV());
        final double du = params.dirU() / len;
        final double dv = params.dirV() / len;
        final double wu = u - params.axisU();
        final double wv = v - params.axisV();
        final double t = wu * du + wv * dv;
        return new double[] {params.axisU() + t * du, params.axisV() + t * dv};
    }

    /**
     * 内部用的中间量：
     * {@code [0..2]} 轴上的基点（3D）、{@code [3]} 半径、{@code [4..6]} phi=0 时的垂直方向。
     */
    private static double[] axisBase(final SketchPlane plane, final Params params,
                                     final double u, final double v) {
        final double[] foot = axisPointOn(params, u, v);
        final Vector3d onAxis = plane.toLocal(foot[0], foot[1]);
        final Vector3d point = plane.toLocal(u, v);
        final Vector3d perp = point.sub(onAxis, new Vector3d());
        return new double[] {onAxis.x, onAxis.y, onAxis.z, perp.length(),
                perp.x, perp.y, perp.z};
    }

    /**
     * 回转体的**棱边线框**。
     *
     * <p>两类棱：
     * <ol>
     *   <li><b>轮廓拷贝</b>：每个角度位置上，轮廓本身的那一圈（外圈 + 每个孔）；</li>
     *   <li><b>顶点轨迹</b>：每个轮廓顶点绕轴画出的那段圆弧。</li>
     * </ol>
     * 只画第一类的话，回转体看起来像一堆互不相连的圆环；
     * 只画第二类则看不出截面形状。
     *
     * @return 每项是两个端点的 {@code {a, b}}（plot 局部系）；没有可回转区域时返回空表
     */
    public static List<Vector3d[]> edges(final Sketch sketch, final Params params) {
        final List<Vector3d[]> out = new ArrayList<>();
        final double total = params.signedRadians();
        final int steps = stepsFor(params);
        for (final SketchProfiles.Region region
                : SketchProfiles.nest(SketchProfiles.findLoops(sketch))) {
            if (!Extrude.extrudable(region)) {
                continue;
            }
            final List<List<double[]>> rings = new ArrayList<>();
            rings.add(region.outer().points());
            for (final SketchProfiles.Profile hole : region.holes()) {
                rings.add(hole.points());
            }
            for (final List<double[]> ring : rings) {
                final int n = ring.size();
                // 1) 轮廓拷贝 + 2) 顶点轨迹
                for (int i = 0; i < n; i++) {
                    final double[] p = ring.get(i);
                    final double[] q = ring.get((i + 1) % n);
                    for (int k = 0; k < steps; k++) {
                        final double phi0 = total * k / steps;
                        final double phi1 = total * (k + 1) / steps;
                        // 轮廓拷贝：同一角度上 p→q
                        out.add(new Vector3d[] {
                                rotated(sketch.plane(), params, p[0], p[1], phi0),
                                rotated(sketch.plane(), params, q[0], q[1], phi0)});
                        // 顶点轨迹：同一个顶点从 phi0 到 phi1
                        out.add(new Vector3d[] {
                                rotated(sketch.plane(), params, p[0], p[1], phi0),
                                rotated(sketch.plane(), params, p[0], p[1], phi1)});
                    }
                    // 整圈时首尾相接：补最后一段轮廓拷贝到 phi=total 的位置
                    if (params.fullTurn()) {
                        out.add(new Vector3d[] {
                                rotated(sketch.plane(), params, p[0], p[1], total),
                                rotated(sketch.plane(), params, q[0], q[1], total)});
                    }
                }
            }
        }
        return out;
    }

    /** 按角度缩放的分段数：一小段旋转不需要 48 段。 */
    public static int stepsFor(final Params params) {
        final double fraction = Math.abs(params.angleDeg()) / MAX_ANGLE;
        return Math.max(2, (int) Math.ceil(FULL_TURN_SEGMENTS * fraction));
    }

    // ==================================================================
    // 碰撞：把回转体分解成**有朝向的盒子**（OBB）
    // ==================================================================

    /**
     * 碰撞分解成 OBB。
     *
     * <h2>为什么必须用 OBB</h2>
     * 回转体的表面是**斜的** —— 轴对齐盒子只能一层层去套，既套不准又要几百个。
     * 而 `BoxPhysicsObject` 的位姿里**带朝向**，所以可以给每个盒子一个精确的旋转。
     *
     * <h2>做法：柱坐标分格</h2>
     * <ol>
     *   <li>把轮廓环变换到 <b>(沿轴 t, 距轴 r)</b> 坐标（这是个刚体变换，面积/拓扑都不变）；</li>
     *   <li>在那个坐标里跑**已有的扫描线盒分解**（在 t-r 平面上它是精确的，还复用了同一份代码）；</li>
     *   <li>每个 (t,r) 盒 × 每个角度楔 → 一个 OBB：局部三轴取
     *       {@code (轴方向 D, 径向 radial(φm), 切向 tangential(φm))}，
     *       半长取 {@code (Δt/2, |Δr|/2, |r_mid|·|Δφ|/2)}（切向那一维就是那段圆弧的弦长）。</li>
     * </ol>
     *
     * <p>⚠ **r 保留符号**是刻意的：负的 r 表示轮廓在轴的另一侧。
     * 整圈时它扫出的是同一圈（只是相位差 π），部分角度时它只在那一侧扫 ——
     * 两种情形用带符号的 r 都自然正确，不需要分情况折叠。
     *
     * @param angularSegments 一整圈分多少个角度楔（碰撞用粗一点，见 {@code Sampling}）
     * @return 每个 OBB（plot 局部系）；没有可回转区域时返回空表
     */
    public static List<SolidCollision.OrientedBox> collisionBoxes(
            final Sketch sketch, final Params params, final int angularSegments) {
        final List<SolidCollision.OrientedBox> out = new ArrayList<>();
        if (angularSegments < 1) {
            return out;
        }
        final SketchPlane plane = sketch.plane();
        final Vector3d axisPoint = plane.toLocal(params.axisU(), params.axisV());
        final Vector3d dir = axisDirection(plane, params);
        // 平面内的"垂直方向"：轴向 × 平面法线。它就是 r 的正方向在 3D 里的样子。
        final Vector3d planeNormal = plane.normal();
        final Vector3d inPlanePerp = new Vector3d(dir).cross(planeNormal, new Vector3d());
        if (inPlanePerp.length() < 1.0e-9) {
            return out; // 轴与平面法线平行？不可能，但别让后面除零
        }
        inPlanePerp.normalize();

        for (final SketchProfiles.Region region
                : SketchProfiles.nest(SketchProfiles.findLoops(sketch))) {
            if (!Extrude.extrudable(region)) {
                continue;
            }
            final List<List<double[]>> rings = new ArrayList<>();
            rings.add(toAxisFrame(region.outer().points(), params));
            for (final SketchProfiles.Profile hole : region.holes()) {
                rings.add(toAxisFrame(hole.points(), params));
            }
            // 退化环可能在变换后只剩两个点（轴正好穿过它），略过
            final List<SketchProfiles.Region> trRegions = buildTrRegions(rings);
            for (final SketchProfiles.Region tr : trRegions) {
                for (final double[] box : SketchProfiles.toBoxes(tr)) {
                    emitWedges(out, box, params, axisPoint, dir, inPlanePerp, planeNormal,
                            angularSegments);
                }
            }
        }
        return out;
    }

    /** 把环上的点从 (u,v) 变换到 (沿轴 t, 距轴 r)。 */
    private static List<double[]> toAxisFrame(final List<double[]> ring, final Params params) {
        final double len = Math.hypot(params.dirU(), params.dirV());
        final double du = params.dirU() / len;
        final double dv = params.dirV() / len;
        // 平面内与轴垂直的方向（右手侧）
        final double pu = -dv;
        final double pv = du;
        final List<double[]> out = new ArrayList<>(ring.size());
        for (final double[] p : ring) {
            final double wu = p[0] - params.axisU();
            final double wv = p[1] - params.axisV();
            out.add(new double[] {wu * du + wv * dv, wu * pu + wv * pv});
        }
        return out;
    }

    /** 把变换后的环重新组成区域（外圈 + 孔）。 */
    private static List<SketchProfiles.Region> buildTrRegions(final List<List<double[]>> rings) {
        final List<SketchProfiles.Profile> profiles = new ArrayList<>();
        for (final List<double[]> ring : rings) {
            if (ring.size() >= 3) {
                profiles.add(new SketchProfiles.Profile(ring, SketchProfiles.signedArea(ring)));
            }
        }
        return SketchProfiles.nest(profiles);
    }

    /** 把一个 (t,r) 平面盒按角度切成若干个 OBB。 */
    private static void emitWedges(final List<SolidCollision.OrientedBox> out, final double[] trBox,
                                   final Params params, final Vector3d axisPoint,
                                   final Vector3d dir, final Vector3d inPlanePerp,
                                   final Vector3d planeNormal, final int angularSegments) {
        final double tMin = trBox[0];
        final double tMax = trBox[2];
        final double rMin = trBox[1];
        final double rMax = trBox[3];
        final double halfT = (tMax - tMin) * 0.5;
        if (halfT <= 1.0e-9) {
            return;
        }
        final double tMid = (tMin + tMax) * 0.5;
        final double total = params.signedRadians();
        final int wedges = Math.max(1, (int) Math.round(
                angularSegments * Math.abs(total) / (2.0 * Math.PI)));
        final double dPhi = Math.abs(total) / wedges;
        // ==============================================================
        // ⚠ **内切修正**（用户实测报的"盒子超出线框"的根因）
        // ==============================================================
        // 楔块盒子的外角在极坐标里是 (r_max, ±w)，它到轴的距离是
        //     sqrt(r_max² + w²)  ——  **恒大于 r_max**
        // 也就是说：只要切向有宽度，盒子就一定会戳出曲面。这不是参数没调好，是必然的。
        //
        // **判据只有一条**：盒子上**每一个点**到轴的距离都要 ≤ 轮廓外径 a。
        // 盒子径向覆盖 [b, r1]（b = 靠轴那一面，r1 = 远离轴那一面），切向 ±w：
        //     外角 sqrt(r1² + w²) ≤ a      ← 决定 r1
        //     内角 sqrt(b²  + w²) ≤ a      ← 决定 w 的上限！
        // 于是
        //     w  ≤ sqrt(a² − b²)        （**这一条才是紧的**，薄盒子时它很小）
        //     r1 = sqrt(a² − w²)
        //
        // ⚠ 我上一版只按 `r1 = a·cos(Δφ/2)`、`w = a·sin(Δφ/2)` 收外面，漏了内角那一条：
        //   贴着最外缘的**薄**盒子，内侧面本身就在 7.4，比"修正后的外面"(7.245)还靠外，
        //   于是内侧面成了极值，角点仍然算到 7.73 > 外径 7.5。
        //
        // ⚠ **内面（b）不要动**：把它往轴里挪会让最近点凹进中间的孔，
        //   那也是"探出表面"（我踩过一次，内面侵入孔 0.23 格）。
        final double sinHalf = Math.sin(dPhi * 0.5);
        final double rInner = Math.abs(rMin) <= Math.abs(rMax) ? rMin : rMax;
        final double rOuter = Math.abs(rMin) <= Math.abs(rMax) ? rMax : rMin;
        final double aAbs = Math.abs(rOuter);
        final double bAbs = Math.abs(rInner);
        // 切向半长：想要的是外径处的弦长，但必须被"内角不越界"卡住
        final double wWanted = aAbs * sinHalf;
        final double wLimit = Math.sqrt(Math.max(0.0, aAbs * aAbs - bAbs * bAbs));
        final double tangentialHalf = Math.min(wWanted, wLimit * 0.95);
        if (tangentialHalf <= 1.0e-9) {
            return; // 这个 (t,r) 盒太薄，装不下任何有宽度的楔子 —— 跳过
        }
        // 外面收到 sqrt(a²−w²)，再留一丝余量（0.999）避免"正好贴住"被判成越界
        final double rOutAbs = Math.sqrt(Math.max(0.0, aAbs * aAbs - tangentialHalf
                * tangentialHalf)) * 0.999;
        if (rOutAbs <= bAbs) {
            return; // 收完比内面还靠里 —— 退化，跳过（代价是半径大的一侧留缝，不是外凸）
        }
        // 保号：r 在跨越转轴时是带符号的（负号表示轮廓在轴的另一侧）
        final double rIn0 = rInner;
        final double rOut1 = rOuter >= 0.0 ? rOutAbs : -rOutAbs;
        final double rMid = (rIn0 + rOut1) * 0.5;
        final double halfR = Math.abs(rOut1 - rIn0) * 0.5;
        if (halfR <= 1.0e-9) {
            return;
        }
        for (int k = 0; k < wedges; k++) {
            final double phiMid = total * (k + 0.5) / wedges;
            // 径向 / 切向：把 (inPlanePerp, planeNormal) 这一对绕轴转 phiMid
            final double c = Math.cos(phiMid);
            final double s = Math.sin(phiMid);
            final Vector3d radial = inPlanePerp.mul(c, new Vector3d())
                    .add(planeNormal.mul(s, new Vector3d()));
            final Vector3d tangential = inPlanePerp.mul(-s, new Vector3d())
                    .add(planeNormal.mul(c, new Vector3d()));
            // 中心：轴上 tMid 处 + 径向 rMid
            final Vector3d centre = dir.mul(tMid, new Vector3d()).add(axisPoint)
                    .add(radial.mul(rMid, new Vector3d()));
            if (tangentialHalf <= 1.0e-9) {
                continue;
            }
            // 局部三轴：x→轴、y→径向、z→切向。用这三列拼出旋转。
            //
            // ⚠⚠ **切向那一列必须取反**，否则这个 frame 是**左手系**（行列式 −1）：
            //     dir × radial = −tangential   →   x × y = −z
            // 而 `setFromNormalized` 要的是**右手旋转矩阵**，喂给它一个反射会得到一个
            // **根本不是旋转的四元数** —— 表现是盒子的朝向完全错位（实测：局部 x 轴与
            // 旋转轴的点积 = 0.0000，切向那 1.94 的半长漏进径向，于是"角点"算到 8.19 > 外径 7.5）。
            // 取反后 x × y = z ✓ 右手系。
            //
            // 盒子沿 z 的半长是**对称**的，所以取反一列**不改变盒子的几何形状**，
            // 纯粹是把朝向修正回一个合法的旋转。这是"内切"能成立的前提。
            final Vector3d tangentialRight = tangential.negate(new Vector3d());
            final org.joml.Matrix3d frame = new org.joml.Matrix3d()
                    .setColumn(0, dir)
                    .setColumn(1, radial)
                    .setColumn(2, tangentialRight);
            final Quaterniond rotation = new Quaterniond().setFromNormalized(frame);
            out.add(new SolidCollision.OrientedBox(centre,
                    new Vector3d(halfT, halfR, Math.max(tangentialHalf, 1.0e-6)), rotation));
        }
    }

    // ==================================================================
    // 三角网格（贴图用）
    // ==================================================================

    /**
     * 回转体的**三角网格**（plot 局部系）。
     *
     * <h2>法线怎么来</h2>
     * 回转面上任意一点的法线，等于**轮廓在该点的平面法线绕轴转过来**。
     * 所以外圈每条边的平面法线（朝材料外）一转，就是那一圈曲面的法线 —— 不用做法线平均。
     *
     * <h2>绕序怎么定</h2>
     * 按"轮廓方向 × 旋转方向"推出来的绕序对外圈是**朝内**的（叉积算一遍就知道）。
     * 与其在这里推孔的顺逆、正负角度，不如**算出来再定**：
     * 先按固定顺序铺一个四边形，算出它的几何法线，和**期望法线**（上面那条）比；
     * 反了就交换中间两个索引。这是局部的、可证明正确的修正，
     * 而且有体积/法线断言兜底（见 {@code SolidMesh.signedVolume}）。
     *
     * @return 网格；没有可回转区域时返回空网格
     */
    public static SolidMesh.Mesh mesh(final Sketch sketch, final Params params) {
        final List<SolidMesh.Vertex> verts = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();
        final SketchPlane plane = sketch.plane();
        final double total = params.signedRadians();
        final int steps = stepsFor(params);
        final boolean full = params.fullTurn();

        for (final SketchProfiles.Region region
                : SketchProfiles.nest(SketchProfiles.findLoops(
                        sketch, SketchProfiles.DEFAULT_WELD_TOLERANCE,
                        SketchProfiles.Sampling.RENDER))) {
            if (!Extrude.extrudable(region)) {
                continue;
            }
            // 外圈：材料在环内，平面法线朝环外
            emitRevolveRing(verts, indices, plane, params, region.outer().points(),
                    true, total, steps);
            // 孔：材料在环外，平面法线要**反**过来（朝孔内）
            for (final SketchProfiles.Profile hole : region.holes()) {
                emitRevolveRing(verts, indices, plane, params, hole.points(),
                        false, total, steps);
            }
            // 只转部分角度时，两端各有一个盖面；整圈时首尾相接，没有盖子。
            // ⚠ 极性要按 total 的符号定：角度为**负**时"角度增大方向"反了，
            // 起点盖和终点盖的外法线方向也要跟着换，否则两个盖子都会朝里（背面剔除后就看不见）。
            if (!full) {
                final double s = total >= 0.0 ? 1.0 : -1.0;
                emitRevolveCap(verts, indices, plane, params, region, 0.0, -s);
                emitRevolveCap(verts, indices, plane, params, region, total, s);
            }
        }
        return new SolidMesh.Mesh(List.copyOf(verts), toIntArray(indices));
    }

    /**
     * 一个环的旋转侧面。
     *
     * @param outward true = 外圈（平面法线朝环外）；false = 孔（朝孔内）
     */
    private static void emitRevolveRing(final List<SolidMesh.Vertex> verts,
                                        final List<Integer> indices, final SketchPlane plane,
                                        final Params params, final List<double[]> ring,
                                        final boolean outward, final double total,
                                        final int steps) {
        final int n = ring.size();
        if (n < 3) {
            return;
        }
        // 环重心：只用来判断"哪边是朝材料外"
        double cu = 0.0;
        double cv = 0.0;
        for (final double[] p : ring) {
            cu += p[0];
            cv += p[1];
        }
        cu /= n;
        cv /= n;
        // 沿轮廓的累积弧长 —— 拿它当贴图的 u，贴图就不会被拉伸
        final double[] run = new double[n + 1];
        for (int i = 0; i < n; i++) {
            final double[] p = ring.get(i);
            final double[] q = ring.get((i + 1) % n);
            run[i + 1] = run[i] + Math.hypot(q[0] - p[0], q[1] - p[1]);
        }
        // ⚠⚠ **角度方向的尺度必须用"整个面的参考半径"，不能用每条棱自己的半径。**
        //
        //   原来这里写的是 `rMid = radius(..., 这条棱的中点)`，于是**相邻两条棱的 v 比例不同**：
        //   两条棱各自乘自己的半径，两个环的贴图网格就对不上，而且半径差越大错位越大 ——
        //   用户实测的表现正是"**旋转拉伸处离基准面越远畸变越大**"。
        //
        //   参考半径取轮廓半径的**全局均值**：v = θ·R_ref 在全曲面上一致，
        //   于是参数连续、误差不累积。锥面上仍有**固有**畸变（不可展曲面的数学下界），
        //   但那是可预测的，不会随离基准面的距离增长。
        double refSum = 0.0;
        int refCount = 0;
        for (final double[] rp : ring) {
            final double rr = radius(params, rp[0], rp[1]);
            if (rr > 1.0e-6) {
                refSum += rr;
                refCount++;
            }
        }
        final double refRadius = refCount == 0 ? 1.0 : refSum / refCount;
        for (int i = 0; i < n; i++) {
            final double[] p = ring.get(i);
            final double[] q = ring.get((i + 1) % n);
            final double edge = Math.hypot(q[0] - p[0], q[1] - p[1]);
            if (edge < 1.0e-9) {
                continue;
            }
            final double tu = (q[0] - p[0]) / edge;
            final double tv = (q[1] - p[1]) / edge;
            // 边的平面法线（垂直于边），先指离重心，再按环的类型决定是否反向
            double nu = tv;
            double nv = -tu;
            final double midU = (p[0] + q[0]) * 0.5;
            final double midV = (p[1] + q[1]) * 0.5;
            final boolean awayFromCentre = (midU - cu) * nu + (midV - cv) * nv >= 0.0;
            if (awayFromCentre != outward) {
                nu = -nu;
                nv = -nv;
            }
            for (int k = 0; k < steps; k++) {
                final double phi0 = total * k / steps;
                final double phi1 = total * (k + 1) / steps;
                final double phiMid = (phi0 + phi1) * 0.5;
                final Vector3d p0 = rotated(plane, params, p[0], p[1], phi0);
                final Vector3d p1 = rotated(plane, params, p[0], p[1], phi1);
                final Vector3d q1 = rotated(plane, params, q[0], q[1], phi1);
                final Vector3d q0 = rotated(plane, params, q[0], q[1], phi0);
                final Vector3d want = rotateDir(plane, params, nu, nv, phiMid).normalize();
                // 贴图：u 沿轮廓弧长；v 沿旋转方向（用中点的旋转半径换算成弧长，
                // 这样不同半径处的纹理密度一致，而不是靠角度）
                // ⚠⚠ **圆周方向的 v 必须累积**（用户 2026-10 反馈"圆柱面问题仍然未解决"）。
                //   以前是 v0 = 0、v1 = rMid·|Δφ| —— 那只是"这一段的增量"，
                //   于是**每一段都从 0 开始**，圆周上重复显示同一小块贴图，**绕不起来**。
                //   改成绝对弧长 rMid·|φ|，v 就随转角单调增长（整圈约 rMid·2π），
                //   贴图才会**沿着圆柱面平铺**。
                final double rMid = radius(params, midU, midV);
                // 用**全局面参考半径**（见上面 refRadius 的说明），不是 rMid
                final double v0 = refRadius * Math.abs(phi0);
                final double v1 = refRadius * Math.abs(phi1);
                emitQuad(verts, indices, p0, p1, q1, q0, want,
                        run[i], run[i + 1], v0, v1);
            }
        }
    }

    /**
     * 部分旋转的一端盖面。
     *
     * @param phi      这一端所在的角度
     * @param polarity 外法线相对"角度增大方向"的符号：起点盖是 −1、终点盖是 +1
     *                 （角度为负时起点/终点的角色互换，由调用方按 total 的符号决定）
     */
    private static void emitRevolveCap(final List<SolidMesh.Vertex> verts,
                                       final List<Integer> indices, final SketchPlane plane,
                                       final Params params, final SketchProfiles.Region region,
                                       final double phi, final double polarity) {
        // 外法线 = ±切向。切向用**旋转速度**来算：绕轴转的点，速度 = D × (P − 轴上点)。
        // 这是精确的，而且不用担心轮廓在哪一侧、半径符号如何。
        final Vector3d axisPoint = plane.toLocal(params.axisU(), params.axisV());
        final Vector3d dir = axisDirection(plane, params);
        final double[] sample = region.outer().points().get(0);
        final Vector3d onCap = rotated(plane, params, sample[0], sample[1], phi);
        final Vector3d radial = onCap.sub(axisPoint, new Vector3d());
        final Vector3d tangential = new Vector3d(dir).cross(radial, new Vector3d());
        final Vector3d normal;
        if (tangential.lengthSquared() < 1.0e-18) {
            // 采样点正好在轴上（半径 0）→ 切向退化。换个采样点再试一次。
            normal = capNormalFallback(plane, params, region, phi, polarity);
        } else {
            normal = tangential.normalize().mul(polarity);
        }
        for (final double[][] tri : SketchProfiles.triangulate(region)) {
            final Vector3d[] c = new Vector3d[3];
            for (int k = 0; k < 3; k++) {
                c[k] = rotated(plane, params, tri[k][0], tri[k][1], phi);
            }
            // UV 用平面坐标（和拉伸盖面一致，于是同一张方块的顶面在此处也是同一个朝向）
            addTri(verts, indices, c[0], c[1], c[2], normal,
                    tri[0][0], tri[0][1], tri[1][0], tri[1][1], tri[2][0], tri[2][1]);
        }
    }

    /** 采样点在轴上时的兜底法线：用平面法线（盖子"差不多"垂直于平面）。 */
    private static Vector3d capNormalFallback(final SketchPlane plane, final Params params,
                                              final SketchProfiles.Region region,
                                              final double phi, final double polarity) {
        for (final double[] p : region.outer().points()) {
            final Vector3d onCap = rotated(plane, params, p[0], p[1], phi);
            final Vector3d radial = onCap.sub(plane.toLocal(params.axisU(), params.axisV()),
                    new Vector3d());
            if (radial.lengthSquared() > 1.0e-12) {
                final Vector3d dir = axisDirection(plane, params);
                return new Vector3d(dir).cross(radial, new Vector3d()).normalize().mul(polarity);
            }
        }
        return new Vector3d(plane.normal()).mul(polarity);
    }

    /** 按"期望法线"决定绕序地铺一个四边形（拆成两个三角形）。 */
    private static void emitQuad(final List<SolidMesh.Vertex> verts,
                                 final List<Integer> indices, final Vector3d p0,
                                 final Vector3d p1, final Vector3d q1, final Vector3d q0,
                                 final Vector3d want, final double u0, final double u1,
                                 final double v0, final double v1) {
        // 先按 (p0,p1,q1,q0) 铺，算几何法线，反了就**逆序**。
        //
        // ⚠ 逆序必须是真的逆序：(p0,p1,q1,q0) 反过来是 **(p0,q0,q1,p1)**。
        //   曾经写成 (p0,q1,p1,q0) —— 那是沿对角线交叉的**蝴蝶结**四边形：
        //   它的两个三角形方向相反，在体积和里**精确抵消**。
        //   症状极具迷惑性：三角形数、包围盒、顶点半径**全都正常**，只有体积恰好是 0。
        Vector3d geo = new Vector3d(p1).sub(p0).cross(new Vector3d(q1).sub(p1));
        final boolean flip = geo.dot(want) < 0.0;
        final Vector3d[] order = flip
                ? new Vector3d[] {p0, q0, q1, p1}
                : new Vector3d[] {p0, p1, q1, q0};
        // UV 跟着顶点走：(p0,p1,q1,q0) ↔ (u0v0, u0v1, u1v1, u1v0)
        final double[][] uv = flip
                ? new double[][] {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}}
                : new double[][] {{u0, v0}, {u0, v1}, {u1, v1}, {u1, v0}};
        final int base = verts.size();
        for (int i = 0; i < 4; i++) {
            verts.add(new SolidMesh.Vertex(order[i], new Vector3d(want), uv[i][0], uv[i][1]));
        }
        indices.add(base);
        indices.add(base + 1);
        indices.add(base + 2);
        indices.add(base);
        indices.add(base + 2);
        indices.add(base + 3);
    }

    /** 加一个三角形（法线给死，UV 逐个给）。 */
    private static void addTri(final List<SolidMesh.Vertex> verts, final List<Integer> indices,
                               final Vector3d a, final Vector3d b, final Vector3d c,
                               final Vector3d normal, final double au, final double av,
                               final double bu, final double bv, final double cu,
                               final double cv) {
        final Vector3d geo = new Vector3d(b).sub(a).cross(new Vector3d(c).sub(b));
        final boolean flip = geo.dot(normal) < 0.0;
        final Vector3d[] pts = flip ? new Vector3d[] {a, c, b} : new Vector3d[] {a, b, c};
        final double[][] uvs = flip
                ? new double[][] {{au, av}, {cu, cv}, {bu, bv}}
                : new double[][] {{au, av}, {bu, bv}, {cu, cv}};
        final int base = verts.size();
        for (int i = 0; i < 3; i++) {
            verts.add(new SolidMesh.Vertex(pts[i], new Vector3d(normal), uvs[i][0], uvs[i][1]));
        }
        indices.add(base);
        indices.add(base + 1);
        indices.add(base + 2);
    }

    /** 把平面内的一个**方向**绕轴转 phi（方向只转不平移，和 {@link #rotated} 不同）。 */
    public static Vector3d rotateDir(final SketchPlane plane, final Params params,
                                     final double du, final double dv, final double phi) {
        final Vector3d v = plane.axisU().mul(du, new Vector3d())
                .add(plane.axisV().mul(dv, new Vector3d()));
        final Vector3d d = axisDirection(plane, params);
        // ⚠ JOML 的签名是 rotationAxis(angle, x, y, z) —— 角度在第一个
        return new org.joml.Quaterniond().rotationAxis(phi, d.x, d.y, d.z)
                .transform(v, new Vector3d());
    }

    private static int[] toIntArray(final List<Integer> list) {
        final int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    /** 回转体所有顶点的包围盒 {@code {minX,minY,minZ,maxX,maxY,maxZ}}；空则 null。 */    public static double[] bounds(final List<Vector3d[]> edges) {
        if (edges.isEmpty()) {
            return null;
        }
        final double[] out = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (final Vector3d[] seg : edges) {
            for (final Vector3d p : seg) {
                out[0] = Math.min(out[0], p.x);
                out[1] = Math.min(out[1], p.y);
                out[2] = Math.min(out[2], p.z);
                out[3] = Math.max(out[3], p.x);
                out[4] = Math.max(out[4], p.y);
                out[5] = Math.max(out[5], p.z);
            }
        }
        return out;
    }
}
