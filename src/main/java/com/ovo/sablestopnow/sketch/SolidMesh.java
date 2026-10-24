package com.ovo.sablestopnow.sketch;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * 把拉伸实体装配成**三角形网格**（纯逻辑，不含任何 Minecraft 类型）。
 *
 * <h2>为什么单独一层</h2>
 * 渲染的那一步其实只有两件事：算出顶点、灌进 {@code VertexConsumer}。
 * 前者是纯几何（可以离线用断言钉住绕序和法线），后者才是 MC 的事。
 * 混在一起写的话，"面朝里/顶点绕反了"这类错误只能靠盯着游戏画面猜。
 *
 * <h2>法线是 plot 局部系的</h2>
 * ⚠ 渲染时位置要过 {@code renderPose}，**法线还要额外过它的旋转**
 * （只转位置不转法线，光照方向就是错的，表现为明暗乱跳）。
 * 这一层只负责给出"局部系里正确的法线"。
 *
 * <h2>UV 单位是"格"</h2>
 * UV 按 plot 局部单位累计（1 单位 = 1 格），**不做取整也不归一化**：
 * 贴图要平铺还是拉伸由渲染层决定（取小数部分 → 平铺）。
 * 这样顶层/侧面共用同一套 UV 语义，接缝处连续。
 *
 * <p>⚠ 只依赖 JOML，可用 {@code tools/SketchModelCheck.java} 验证。
 */
public final class SolidMesh {

    /** 一个顶点：局部位置 + 局部法线 + 贴图坐标（单位 = 格）。 */
    public record Vertex(Vector3d position, Vector3d normal, double u, double v) {
    }

    /** 三角形网格。{@code indices} 每 3 个一组，绕序为**逆时针朝外**。 */
    public record Mesh(List<Vertex> vertices, int[] indices) {

        public int triangleCount() {
            return indices.length / 3;
        }

        public boolean isEmpty() {
            return indices.length == 0;
        }
    }

    /**
     * 方块的**六个朝向**。
     *
     * <p>⚠ 这里刻意**不复用 Minecraft 的 {@code Direction}**：那样这个类就依赖 MC，
     * 回退规则也就没法离线验证了。MC 那边做一个 6 个分支的映射即可（很薄）。
     */
    public enum BlockFace {
        UP, DOWN, NORTH, SOUTH, EAST, WEST;

        /** 对面（贴图回退链的第二站）。 */
        public BlockFace opposite() {
            return switch (this) {
                case UP -> DOWN;
                case DOWN -> UP;
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case EAST -> WEST;
                case WEST -> EAST;
            };
        }

        /** 是不是水平四面之一（侧面优先从这里挑）。 */
        public boolean horizontal() {
            return this != UP && this != DOWN;
        }
    }

    /** 面类别 → **首选**的方块朝向。 */
    public static BlockFace preferredFace(final FaceKind kind) {
        return switch (kind) {
            case CAP_UP -> BlockFace.UP;
            case CAP_DOWN -> BlockFace.DOWN;
            // 侧面优先北面：原版方块的水平四面贴图通常一样，挑一个固定的即可 ——
            // 挑"随机的那个"会让同一块木头不同面纹理不一样，看着像 bug。
            case SIDE -> BlockFace.NORTH;
        };
    }

    /**
     * 从方块模型**实际拥有**的面里挑一个来取样。
     *
     * <h2>为什么需要回退链</h2>
     * 方块模型不一定有全部 6 个面：自定义模型常常只写 {@code north}，
     * 或者只写 {@code up}/{@code down}（薄板类方块）。
     * 直接按"首选"去查会拿到 {@code null} 贴图 → 那个面画成空白或崩。
     *
     * <p>回退顺序：**首选 → 它的对面 → 其余按枚举顺序**。最后这档保证"只要有面就能画出来"，
     * 而不是"没有首选就什么都不贴"。
     *
     * <p>⚠ 顺序固定（枚举顺序）而不是"随便挑一个"：同一块模型的不同面必须挑到**同一个**结果，
     * 否则同一面墙上会出现两种纹理。
     *
     * @param available 方块模型实际提供的面；null 或空表示"一个都没有"
     * @return 挑中的面；一个都没有时返回 {@code null}
     */
    public static BlockFace pickAvailable(final FaceKind kind,
                                          @Nullable final java.util.Set<BlockFace> available) {
        if (available == null || available.isEmpty()) {
            return null;
        }
        final BlockFace preferred = preferredFace(kind);
        if (available.contains(preferred)) {
            return preferred;
        }
        final BlockFace opposite = preferred.opposite();
        if (available.contains(opposite)) {
            return opposite;
        }
        // 兜底：按枚举顺序取第一个有的 —— 顺序固定，结果可复现
        for (final BlockFace face : BlockFace.values()) {
            if (available.contains(face)) {
                return face;
            }
        }
        return null;
    }

    private SolidMesh() {
    }

    /**
     * 网格的**有符号体积**（散度定理：{@code V = Σ (v0 × v1) · v2 / 6}）。
     *
     * <h2>为什么这个数字这么有用</h2>
     * 它一次性把三件事绑在一起：
     * <ul>
     *   <li><b>位置</b>对不对（顶点算错，体积就错）；</li>
     *   <li><b>绕序</b>对不对（有符号体积会因为整体绕序反了而变号）；</li>
     *   <li><b>闭合性</b>对不对（少了盖面/多了面，体积都会偏）。</li>
     * </ul>
     * 所以拿它跟"解析体积"对一下，比逐个顶点断言有效得多 ——
     * 逐点断言只看得到你想得到的那种错，而体积会把没想到的错一起暴露出来。
     *
     * @return 体积；绕序朝外时为正
     */
    public static double signedVolume(final Mesh mesh) {
        if (mesh == null || mesh.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        final int[] idx = mesh.indices();
        for (int i = 0; i + 2 < idx.length; i += 3) {
            final Vector3d a = mesh.vertices().get(idx[i]).position();
            final Vector3d b = mesh.vertices().get(idx[i + 1]).position();
            final Vector3d c = mesh.vertices().get(idx[i + 2]).position();
            final Vector3d cross = new Vector3d(a).cross(b);
            sum += cross.dot(c);
        }
        return sum / 6.0;
    }

    /** 网格顶点位置的包围盒 {@code {minX,minY,minZ,maxX,maxY,maxZ}}；空网格返回 null。 */
    @Nullable
    public static double[] bounds(final Mesh mesh) {
        if (mesh == null || mesh.isEmpty()) {
            return null;
        }
        final double[] out = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (final Vertex v : mesh.vertices()) {
            out[0] = Math.min(out[0], v.position().x);
            out[1] = Math.min(out[1], v.position().y);
            out[2] = Math.min(out[2], v.position().z);
            out[3] = Math.max(out[3], v.position().x);
            out[4] = Math.max(out[4], v.position().y);
            out[5] = Math.max(out[5], v.position().z);
        }
        return out;
    }

    /** 每个三角形的**绕序朝外**是否与给定中心一致（点不在面上时用它判朝向）。 */
    public static boolean outwardWinding(final Mesh mesh, final Vector3d inside) {
        if (mesh == null || mesh.isEmpty()) {
            return true;
        }
        final int[] idx = mesh.indices();
        for (int i = 0; i + 2 < idx.length; i += 3) {
            final Vector3d a = mesh.vertices().get(idx[i]).position();
            final Vector3d b = mesh.vertices().get(idx[i + 1]).position();
            final Vector3d c = mesh.vertices().get(idx[i + 2]).position();
            final Vector3d faceNormal = new Vector3d(b).sub(a).cross(new Vector3d(c).sub(b));
            if (faceNormal.lengthSquared() < 1.0e-18) {
                continue; // 退化三角形，跳过
            }
            // 从内部指向三角形重心的方向应当与面法线同侧
            final Vector3d centroid = new Vector3d(a).add(b).add(c).mul(1.0 / 3.0);
            final Vector3d out = centroid.sub(inside, new Vector3d());
            if (out.lengthSquared() < 1.0e-18) {
                continue;
            }
            if (faceNormal.dot(out) < 0.0) {
                return false;
            }
        }
        return true;
    }

    /** 面的**类别**：拿它去方块的方块模型里挑对应那一面的贴图。 */
    public enum FaceKind {
        /** 盖面，朝拉伸正向（方块模型的 {@code up}）。 */
        CAP_UP,
        /** 盖面，朝反向（方块模型的 {@code down}）。 */
        CAP_DOWN,
        /** 侧面（方块模型里四面水平面之一）。 */
        SIDE
    }

    /**
     * 判断一个面的类别。
     *
     * <h2>为什么要按类别挑贴图</h2>
     * 方块模型有 6 个面（上下 + 四面水平），它们的贴图**常常不一样**
     *（原木：顶面是年轮、侧面是树皮；草方块：顶面绿、侧面带草边）。
     * 所以贴我们的模型时必须按面朝向去挑，否则木头会长成"四面年轮"。
     *
     * <p>判据用**平面法线**：与它同向 = 盖面朝正向、反向 = 盖面朝反向、
     * 垂直 = 侧面。阈值取 0.9 而不是 1.0 —— 浮点误差不该让一个盖面被判成侧面。
     *
     * @param normal      面法线（plot 局部，单位向量）
     * @param planeNormal 草图平面法线（即拉伸方向，单位向量）
     */
    public static FaceKind faceKind(final Vector3d normal, final Vector3d planeNormal) {
        final double d = normal.dot(planeNormal);
        if (d > 0.9) {
            return FaceKind.CAP_UP;
        }
        if (d < -0.9) {
            return FaceKind.CAP_DOWN;
        }
        return FaceKind.SIDE;
    }

    /**
     * 生成实体的网格。
     *
     * @return 网格；草图没有可拉伸区域时返回空网格（不是 null，调用方少一个分支）
     */
    /** 一组盒子 ⇒ 封闭网格 ✓（离线用例与"由模型生成渲染"共用 ✓）。 */
    public static Mesh fromBoxes(final java.util.List<double[]> boxes) {
        final java.util.List<Vertex> vs = new java.util.ArrayList<>();
        final java.util.List<Integer> idx = new java.util.ArrayList<>();
        if (boxes == null) {
            return new Mesh(vs, new int[0]);
        }
        for (final double[] b : boxes) {
            if (b == null || b.length < 6
                    || b[3] - b[0] < 1.0e-9 || b[4] - b[1] < 1.0e-9 || b[5] - b[2] < 1.0e-9) {
                continue;
            }
            for (int axis = 0; axis < 3; axis++) {
                for (int side = 0; side < 2; side++) {
                    final int a1 = (axis + 1) % 3;
                    final int a2 = (axis + 2) % 3;
                    final double plane = side == 0 ? b[axis] : b[axis + 3];
                    final double[] n = new double[3];
                    n[axis] = side == 0 ? -1.0 : 1.0;
                    final int base = vs.size();
                    // ★ 缠绕方向必须与法线一致（CSG 靠**三角形绕序**定平面朝向 ✓）
                    //   (a1,a2) 取标准逆时针 ⇒ 法线是 +axis ⇒ 只有 max 侧可以用它 ✓
                    //   min 侧要**反序** ✓（写反了会让整个实体变成"内外翻转" ⇒ 布尔结果为空 ✗）
                    final int[][] order = side == 0
                            ? new int[][] {{0, 0}, {0, 1}, {1, 1}, {1, 0}}
                            : new int[][] {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
                    for (final int[] o : order) {
                        final double[] pos = new double[3];
                        pos[axis] = plane;
                        pos[a1] = o[0] == 0 ? b[a1] : b[a1 + 3];
                        pos[a2] = o[1] == 0 ? b[a2] : b[a2 + 3];
                        vs.add(new Vertex(new Vector3d(pos[0], pos[1], pos[2]),
                                new Vector3d(n[0], n[1], n[2]), pos[a1], pos[a2]));
                    }
                    idx.add(base);
                    idx.add(base + 1);
                    idx.add(base + 2);
                    idx.add(base);
                    idx.add(base + 2);
                    idx.add(base + 3);
                }
            }
        }
        final int[] arr = new int[idx.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = idx.get(i);
        }
        return new Mesh(vs, arr);
    }

    public static Mesh build(final Sketch sketch, final Extrude.Params params) {
        final List<Vertex> vertices = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();
        final SketchPlane plane = sketch.plane();

        // 侧面：每个四边形拆两个三角形
        for (final Extrude.Quad quad : Extrude.prismSideFaces(sketch, params)) {
            final Vector3d[] c = quad.corners();
            final double[][] uv = quad.uv();
            final int base = vertices.size();
            for (int i = 0; i < 4; i++) {
                vertices.add(new Vertex(c[i], quad.normal(), uv[i][0], uv[i][1]));
            }
            indices.add(base);
            indices.add(base + 1);
            indices.add(base + 2);
            indices.add(base);
            indices.add(base + 2);
            indices.add(base + 3);
        }

        // 顶/底面：三角化之后各自成面。底面法线朝 −n，所以**绕序要反过来**，
        // 否则底面会变成背面、从外面看不见（经典"从下面看是空的"）。
        final double[] range = params.depthRange();
        final Vector3d n = plane.normal();
        final Vector3d up = new Vector3d(n);
        final Vector3d down = n.negate(new Vector3d());
        for (final SketchProfiles.Region region
                : SketchProfiles.nest(SketchProfiles.findLoops(sketch))) {
            if (!Extrude.extrudable(region)) {
                continue;
            }
            for (final double[][] tri : SketchProfiles.triangulate(region)) {
                capTriangle(vertices, indices, plane, tri, range[0], down, true);
                capTriangle(vertices, indices, plane, tri, range[1], up, false);
            }
        }
        return new Mesh(List.copyOf(vertices), toArray(indices));
    }

    /** 一个盖面三角形。{@code reverse=true} 时反转绕序（底面用）。 */
    private static void capTriangle(final List<Vertex> vertices, final List<Integer> indices,
                                    final SketchPlane plane, final double[][] tri,
                                    final double depth, final Vector3d normal,
                                    final boolean reverse) {
        final int base = vertices.size();
        final int[] order = reverse ? new int[] {2, 1, 0} : new int[] {0, 1, 2};
        for (final int k : order) {
            final Vector3d p = Extrude.onPlane(plane, tri[k], depth);
            vertices.add(new Vertex(p, normal, tri[k][0], tri[k][1]));
        }
        indices.add(base);
        indices.add(base + 1);
        indices.add(base + 2);
    }

    private static int[] toArray(final List<Integer> list) {
        final int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }
}
