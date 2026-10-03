import com.ovo.sablestopnow.sketch.Extrude;
import com.ovo.sablestopnow.sketch.Revolve;
import com.ovo.sablestopnow.sketch.Sketch;
import com.ovo.sablestopnow.sketch.SketchPlane;
import com.ovo.sablestopnow.sketch.SketchProfiles;
import com.ovo.sablestopnow.sketch.Solid;
import com.ovo.sablestopnow.sketch.SolidCollision;
import com.ovo.sablestopnow.sketch.SolidMesh;
import org.joml.Vector3d;

import java.util.Locale;
import java.util.UUID;

/**
 * **碰撞方案的开销对照表**（离线，不依赖 Minecraft）。
 *
 * <p>用户的问题：盒子方案 vs 体素方案谁便宜？盒子能不能改成"用很薄的盒子拼出曲面"
 * 而不是填充内部？
 *
 * <p>先说清**成本模型**（这决定了怎么比较才有意义）：
 * <ul>
 *   <li>**盒子方案**：一个盒子 = **一个刚体 + 一条固定约束**。约束是**弹簧**，
 *       每个盒子每步都要进求解器迭代 → 开销 ≈ O(盒子数 × 迭代次数)。
 *       所以盒子数是**主导项**，而且是线性进求解器的。</li>
 *   <li>**体素方案**（{@code addVoxelColliderBox}）：**一个碰撞体喂 N 个盒子**，
 *       没有额外刚体、没有约束、不进求解器 → 开销主要在一次"这个体素集 vs 世界"的
 *       碰撞检测上（Sable 自己的子结构碰撞走的就是这条路）。
 *       所以它**不随 N 线性进求解器**，只影响碰撞检测的宽度。</li>
 * </ul>
 *
 * <p>⚠ 但两者**不能随便换算**：体素碰撞体只吃**轴对齐** bounds
 * （`[minX,minY,minZ,maxX,maxY,maxZ]`），"很薄的斜盒子"喂不进去。
 * 这是选型的硬约束，比性能数字更关键。
 *
 * <p>于是本表真正要回答的是：**每种分解各要多少个盒子**。
 */
public final class CollisionCostReport {

    private CollisionCostReport() {
    }

    /** 造一个半径为 1 的球（上半圆绕直径转 360°）。 */
    private static Sketch unitSphere() {
        final Sketch k = new Sketch(UUID.randomUUID(), UUID.randomUUID(), "Sphere",
                SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        k.addArc(0.0, 0.0, 1.0, 0.0, Math.PI);
        k.addLine(-1.0, 0.0, 1.0, 0.0);
        return k;
    }

    /** 造一个圆环：离轴 6、半径 1.5 的圆整圈旋转。 */
    private static Sketch torus() {
        final Sketch k = new Sketch(UUID.randomUUID(), UUID.randomUUID(), "Torus",
                SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()));
        // ⚠ 转轴是 **u 轴**（dir=(1,0)），所以"离轴距离"是 |v| ——
        //   圆心必须沿 **v** 偏移才是圆环；放在 (6,0) 的话 v=0，圆心就在轴上，
        //   转出来是**球**（我第一次就是这么错的，span 3.0 露了馅）。
        final Sketch.Entity circle = k.addCircle(0.0, 6.0, 1.5);
        // 只是为了让"用了哪个图元"显式；addCircle 已经建好了
        assert circle != null;
        return k;
    }

    private static Revolve.Params fullTurn() {
        return new Revolve.Params(0.0, 0.0, 1.0, 0.0, 360.0, 1);
    }

    private static void report(final String name, final Sketch sketch) {
        final Revolve.Params params = fullTurn();
        final SolidMesh.Mesh mesh = Revolve.mesh(sketch, params);
        final int tris = mesh.triangleCount();
        final double[] b = SolidMesh.bounds(mesh);
        final double span = b == null ? 0.0
                : Math.max(b[3] - b[0], Math.max(b[4] - b[1], b[5] - b[2]));
        System.out.println("=== " + name + " ===  三角数=" + tris
                + String.format(Locale.ROOT, "  尺寸=%.2f", span));

        // ① 分楔 OBB（当前回转体生产路径）：覆盖 (t,r) 区域 = 近似**体积**
        final var wedges = Revolve.collisionBoxes(sketch, params,
                Solid.COLLISION_REVOLVE_WEDGES);
        System.out.println(String.format(Locale.ROOT,
                "  ① 分楔 OBB(体积)     盒子=%4d   每个都是刚体+弹簧约束", wedges.size()));

        // ② 体素填充：射线奇偶判里外 = **填满体积**
        final var fill = SolidCollision.voxelBoxes(mesh, Solid.COLLISION_TARGET_CELL,
                Solid.COLLISION_MAX_BOXES, Solid.COLLISION_MIN_RES, Solid.COLLISION_MAX_RES);
        System.out.println(String.format(Locale.ROOT,
                "  ② 体素填充(体积)     盒子=%4d   格边长=%.3f 降过分辨率=%s",
                fill.boxes().size(), fill.cell(), fill.coarsened()));

        // ③ 体素壳：只标"表面经过的格" = **只做曲面**
        final var shell = SolidCollision.surfaceBoxes(mesh, Solid.COLLISION_TARGET_CELL,
                Solid.COLLISION_MAX_BOXES, Solid.COLLISION_MIN_RES, Solid.COLLISION_MAX_RES);
        System.out.println(String.format(Locale.ROOT,
                "  ③ 体素壳(只做面)     盒子=%4d   格边长=%.3f 降过分辨率=%s",
                shell.boxes().size(), shell.cell(), shell.coarsened()));

        // ④ 薄面盒子：一个网格四边形 ≈ 一个薄盒子（曲面的"面片"数，与曲率挂钩）
        //    这里用"每个三角面片一个"做**上界估计**：真实实现会按法线聚类合并，
        //    数量介于"三角数/2"和这个之间。
        System.out.println(String.format(Locale.ROOT,
                "  ④ 薄面盒子(每面片1个) 盒子≈%4d   ← 上界；按法线聚类合并后会更少",
                tris / 2));

        // ⑤ **体素碰撞体 @ 1/16**：这是现在要走的路。盒子数多，但**没有刚体、没有约束、不进求解器**，
        //    所以盒子数在这里不是同一个量纲的开销。
        for (final int subdiv : new int[] {8, 16}) {
            final var blocks = SolidCollision.voxelizeToBlocks(mesh, subdiv, 4096);
            int totalBoxes = 0;
            int maxPerBlock = 0;
            for (final var blk : blocks) {
                totalBoxes += blk.boxes().size();
                maxPerBlock = Math.max(maxPerBlock, blk.boxes().size());
            }
            // **合并效率**：占用的子格数 vs 合并后的盒子数。用来判断还有没有优化空间 ——
            // 效率已经很高的话，再改合并算法也是白费；效率低才值得动。
            int rawCells = SolidCollision.occupiedSubCellCount(mesh, subdiv);
            System.out.println(String.format(Locale.ROOT,
                    "  ⑤ 体素碰撞体 1/%-2d     方块格=%4d  子盒合计=%5d  单格最多=%3d"
                            + "  占用子格=%6d  合并效率=%.0f%%",
                    subdiv, blocks.size(), totalBoxes, maxPerBlock, rawCells,
                    rawCells == 0 ? 0.0 : 100.0 * (1.0 - (double) totalBoxes / rawCells)));
        }
        System.out.println();
    }

    public static void main(final String[] args) {
        report("半径 1 的球", unitSphere());
        report("圆环（离轴 6、半径 1.5）", torus());

        System.out.println("--- 成本模型 ---");
        System.out.println("盒子方案: 一个盒子 = 一个刚体 + 一条**弹簧**约束，进求解器迭代 → 随盒子数**线性**");
        System.out.println("体素方案: 一个碰撞体喂 N 个盒子，无刚体/无约束/不进求解器 → 只影响碰撞检测宽度");
        System.out.println("⚠ 但体素碰撞体只吃**轴对齐** bounds，斜的薄盒子喂不进去（选型硬约束）");
    }
}
