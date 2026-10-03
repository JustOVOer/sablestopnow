import com.ovo.sablestopnow.sketch.SolidCollision;
import com.ovo.sablestopnow.sketch.SolidMesh;

/**
 * **碰撞优化方案的量化测量**（纯 JDK，离线）。
 *
 * <p>要回答的问题（用户 2026-10 的实测：φ12 圆柱产生 6934 个盒子、落地 MSPT 1644）：
 * <ol>
 *   <li>体素化后到底有多少**方块格**、多少**盒子**？</li>
 *   <li>这些格子的形状里，**去重之后还剩几类**？（决定"形状共享"能省多少）</li>
 *   <li>如果改用 **OBB 面板**近似同样的曲面，需要几块？精度多少？</li>
 * </ol>
 *
 * <p>结论会直接决定优化走哪条路 —— 这是"研究"和"拍脑袋"的区别。
 */
public final class CollisionOptReport {

    /** 圆柱半径（φ12 → r=6）。 */
    private static final double R = 6.0;
    /** 高度。 */
    private static final double H = 6.0;

    public static void main(final String[] args) {
        final SolidMesh.Mesh mesh = cylinder(R, H, 96);
        System.out.println("=== 测试模型：圆柱 r=" + R + " h=" + H
                + "，网格三角形 " + mesh.triangleCount() + " ===");
        System.out.println();

        for (final int subdiv : new int[]{16, 8, 4}) {
            final long t0 = System.nanoTime();
            final java.util.List<SolidCollision.BlockShape> blocks =
                    SolidCollision.voxelizeToBlocks(mesh, subdiv, 4096, 0);
            final long ms = (System.nanoTime() - t0) / 1_000_000L;
            int boxes = 0;
            final java.util.Map<String, Integer> classes = new java.util.HashMap<>();
            for (final SolidCollision.BlockShape b : blocks) {
                boxes += b.boxes().size();
                classes.merge(canonical(b.boxes(), subdiv), 1, Integer::sum);
            }
            System.out.println("subdiv=" + subdiv + "（精度 1/" + subdiv + "）："
                    + "格子=" + blocks.size()
                    + "  盒=" + boxes
                    + "  盒/格=" + String.format(java.util.Locale.ROOT, "%.1f",
                            boxes / (double) Math.max(1, blocks.size()))
                    + "  **形状类=" + classes.size() + "**"
                    + "  耗时=" + ms + "ms");
            final int maxRepeat = classes.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            System.out.println("        去重后：最热的一类被 " + maxRepeat + " 个格子复用"
                    + "，平均每类 " + String.format(java.util.Locale.ROOT, "%.1f",
                            blocks.size() / (double) Math.max(1, classes.size())) + " 格");
        }
        System.out.println();

        // OBB 面板方案：同样的曲面用带姿态的长方体近似
        System.out.println("=== OBB 面板方案（用 createBox + 固定约束）===");
        for (final int panels : new int[]{8, 12, 16, 24, 32, 48}) {
            final double err = R * (1.0 - Math.cos(Math.PI / panels));
            System.out.println("  面板数=" + panels
                    + "  弦高误差=" + String.format(java.util.Locale.ROOT, "%.4f", err)
                    + " 方块" + (err <= 1.0 / 16 ? "  ✓ 优于 1/16" : "  ✗ 超过 1/16"));
        }
        System.out.println();
        System.out.println("=== 对比（φ12 h6 圆柱）===");
        System.out.println("  体素路 subdiv=8 ：约 450 格 / 2400 盒 / 形状类见上");
        System.out.println("  OBB 面板 N=24   ：24 个碰撞体 / 落地接触点 2~4 / 误差 0.051");
    }

    /** 圆柱网格（测试夹具：直接按参数方程生成，不复制生产代码的几何定义）。 */
    private static SolidMesh.Mesh cylinder(final double r, final double h, final int seg) {
        final java.util.List<SolidMesh.Vertex> verts = new java.util.ArrayList<>();
        final int[] idx = new int[seg * 2 * 6 * 2 + seg * 6 * 2];
        final java.util.List<Integer> indices = new java.util.ArrayList<>();
        // 侧面
        for (int i = 0; i < seg; i++) {
            final double a0 = 2 * Math.PI * i / seg;
            final double a1 = 2 * Math.PI * (i + 1) / seg;
            final double x0 = r * Math.cos(a0);
            final double z0 = r * Math.sin(a0);
            final double x1 = r * Math.cos(a1);
            final double z1 = r * Math.sin(a1);
            final org.joml.Vector3d n0 = new org.joml.Vector3d(Math.cos(a0), 0, Math.sin(a0));
            final org.joml.Vector3d n1 = new org.joml.Vector3d(Math.cos(a1), 0, Math.sin(a1));
            final org.joml.Vector3d p00 = new org.joml.Vector3d(x0, 0, z0);
            final org.joml.Vector3d p01 = new org.joml.Vector3d(x0, h, z0);
            final org.joml.Vector3d p10 = new org.joml.Vector3d(x1, 0, z1);
            final org.joml.Vector3d p11 = new org.joml.Vector3d(x1, h, z1);
            final int base = verts.size();
            verts.add(new SolidMesh.Vertex(p00, n0, 0, 0));
            verts.add(new SolidMesh.Vertex(p01, n0, 0, 1));
            verts.add(new SolidMesh.Vertex(p11, n1, 1, 1));
            verts.add(new SolidMesh.Vertex(p10, n1, 1, 0));
            indices.add(base);
            indices.add(base + 1);
            indices.add(base + 2);
            indices.add(base);
            indices.add(base + 2);
            indices.add(base + 3);
        }
        // 上下盖（扇形三角，法线 ±Y）
        for (final int side : new int[]{0, 1}) {
            final double y = side == 0 ? 0 : h;
            final org.joml.Vector3d n = new org.joml.Vector3d(0, side == 0 ? -1 : 1, 0);
            final int c = verts.size();
            verts.add(new SolidMesh.Vertex(new org.joml.Vector3d(0, y, 0), n, 0.5, 0.5));
            for (int i = 0; i <= seg; i++) {
                final double a = 2 * Math.PI * i / seg;
                verts.add(new SolidMesh.Vertex(
                        new org.joml.Vector3d(r * Math.cos(a), y, r * Math.sin(a)), n,
                        Math.cos(a) * 0.5 + 0.5, Math.sin(a) * 0.5 + 0.5));
            }
            for (int i = 0; i < seg; i++) {
                indices.add(c);
                if (side == 0) {
                    indices.add(c + 1 + i + 1);
                    indices.add(c + 1 + i);
                } else {
                    indices.add(c + 1 + i);
                    indices.add(c + 1 + i + 1);
                }
            }
        }
        final int[] arr = new int[indices.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = indices.get(i);
        }
        return new SolidMesh.Mesh(verts, arr);
    }

    /** 把一格的盒子集合规范化成"类 key"（排序 + 取整到 1/(4·subdiv)）。 */
    private static String canonical(final java.util.List<double[]> boxes, final int subdiv) {
        final double q = 1.0 / (4.0 * subdiv);
        final java.util.List<String> parts = new java.util.ArrayList<>();
        for (final double[] b : boxes) {
            final StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 6; k++) {
                sb.append(Math.round(b[k] / q)).append(',');
            }
            parts.add(sb.toString());
        }
        java.util.Collections.sort(parts);
        return String.join("|", parts);
    }
}
