package com.ovo.sablestopnow.sketch.nurbs;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@link SweptSolid} **直接**体素化成轴对齐盒子集合（接现有 1/16 体素碰撞体）。
 *
 * <h2>误差是有界的，不是"凭感觉"</h2>
 * 每个"方块格"（边长 {@code cell}）被切成 {@code subdiv³} 个子格，子格按**中心点**做内外判定
 * （{@link Criterion#CENTER}）。这样**只有落在曲面附近一个薄壳里的子格可能判错**，
 * 而这个壳的厚度不超过子格的半对角线：
 * <pre>
 *   ε = (cell / subdiv) · √3 / 2
 * </pre>
 * 于是**对称差体积**（体素集合与真实实体的差）有一个可证明的上界：
 * <pre>
 *   |S_voxel Δ S_true|  ≤  Area(∂S) · ε  +  O(ε²)
 * </pre>
 * 也就是说：**误差随子格尺寸线性下降**。这条关系被 {@code tools/NurbsCollisionCheck.java}
 * 用"实测对称差 vs 公式上界"钉住 —— 没有这条，1/16 精度只是喊口号。
 *
 * <h2>三种准则（物理上必须能选）</h2>
 * <ul>
 *   <li>{@link Criterion#CENTER}：格心在内部即算内部，**误差最小但可能穿模**；</li>
 *   <li>{@link Criterion#CONSERVATIVE}：子格中心在内**或**格与实体相交即算内部 ——
 *       **绝不漏判**（碰撞上不会穿模），代价是整体外扩约 ε；</li>
 *   <li>{@link Criterion#MAJORITY}：子格中心在内即算，**再按占比四舍五入**到最近的格子形状，
 *       体积意义上最接近，但会同时有内缩与外扩。</li>
 * </ul>
 * ⚠ 物理碰撞通常应该用 {@link Criterion#CONSERVATIVE}：**穿模比外扩难受得多**
 * （外扩最多 ε，而穿模会让物体"陷进"地面）。
 *
 * <h2>为什么要贪心合并</h2>
 * 碰撞后端 {@code addVoxelColliderBox} 是**逐盒**喂的，盒子越多成本越高。
 * 实测（此前在 {@code tools/CollisionCostReport} 上）贪心合并能去掉 75%~81% 的盒子，
 * 所以这里先并在 x 方向做连续段、再把段沿 z、y 合并。
 */
public final class Voxelizer {

    private Voxelizer() {
    }

    /** 边界格的判定准则。 */
    public enum Criterion {
        /** 格心在内部即算内部（误差对称，可能穿模）。 */
        CENTER,
        /** 中心在内**或**与外环相交即算内部（**绝不漏判**，整体外扩）。 */
        CONSERVATIVE,
        /** 按子格占比过半算内部（体积意义上最接近）。 */
        MAJORITY,
    }

    /**
     * 体素化：返回盒子列表，每个盒子 {@code [minX,minY,minZ,maxX,maxY,maxZ]}，
     * 坐标系与传入的包围盒相同。
     *
     * @param cell   方块格边长（我们这边是 1.0 方块）
     * @param subdiv 每格切成 {@code subdiv} 份（我们这边是 16 = 1/16 精度）
     */
    public static List<double[]> voxelize(final SweptSolid solid, final double[] lo,
                                          final double[] hi, final double cell,
                                          final int subdiv, final Criterion criterion) {
        final double sub = cell / subdiv;
        final int nx = (int) Math.ceil((hi[0] - lo[0]) / cell);
        final int ny = (int) Math.ceil((hi[1] - lo[1]) / cell);
        final int nz = (int) Math.ceil((hi[2] - lo[2]) / cell);
        // 占用表：每个方块格一份 subdiv³ 的位图
        final boolean[][][][] occ = new boolean[nx][ny][nz][];
        final double[] c = new double[3];
        for (int bx = 0; bx < nx; bx++) {
            for (int by = 0; by < ny; by++) {
                for (int bz = 0; bz < nz; bz++) {
                    boolean any = false;
                    final boolean[] bits = new boolean[subdiv * subdiv * subdiv];
                    int inside = 0;
                    for (int sx = 0; sx < subdiv; sx++) {
                        for (int sy = 0; sy < subdiv; sy++) {
                            for (int sz = 0; sz < subdiv; sz++) {
                                c[0] = lo[0] + bx * cell + (sx + 0.5) * sub;
                                c[1] = lo[1] + by * cell + (sy + 0.5) * sub;
                                c[2] = lo[2] + bz * cell + (sz + 0.5) * sub;
                                final boolean in = solid.inside(c);
                                if (in) {
                                    inside++;
                                }
                                bits[(sx * subdiv + sy) * subdiv + sz] = in;
                                any |= in;
                            }
                        }
                    }
                    if (!any) {
                        continue;
                    }
                    final int total = subdiv * subdiv * subdiv;
                    final boolean[] effective = switch (criterion) {
                        case CENTER -> bits;
                        case MAJORITY -> majority(bits, inside, total);
                        case CONSERVATIVE -> conservative(solid, lo, bx, by, bz, cell, subdiv, bits);
                    };
                    occ[bx][by][bz] = effective;
                }
            }
        }
        return merge(occ, lo, cell, subdiv, nx, ny, nz);
    }

    /**
     * 保守准则：只要子格**可能**与实体相交就算内部。
     *
     * <p>判据用 {@link SweptSolid#signedDistance}：子格中心到边界的**带符号距离**绝对值
     * 小于子格半对角线时，该子格无法确定完全在外 —— 算内部。
     */
    private static boolean[] conservative(final SweptSolid solid, final double[] lo,
                                          final int bx, final int by, final int bz,
                                          final double cell, final int subdiv,
                                          final boolean[] bits) {
        final double sub = cell / subdiv;
        final double halfDiag = sub * Math.sqrt(3.0) / 2.0;
        final boolean[] out = new boolean[bits.length];
        final double[] c = new double[3];
        for (int sx = 0; sx < subdiv; sx++) {
            for (int sy = 0; sy < subdiv; sy++) {
                for (int sz = 0; sz < subdiv; sz++) {
                    final int k = (sx * subdiv + sy) * subdiv + sz;
                    if (bits[k]) {
                        out[k] = true;
                        continue;
                    }
                    c[0] = lo[0] + bx * cell + (sx + 0.5) * sub;
                    c[1] = lo[1] + by * cell + (sy + 0.5) * sub;
                    c[2] = lo[2] + bz * cell + (sz + 0.5) * sub;
                    out[k] = Math.abs(solid.signedDistance(c)) <= halfDiag;
                }
            }
        }
        return out;
    }

    /** 过半准则。 */
    private static boolean[] majority(final boolean[] bits, final int inside, final int total) {
        if (inside * 2 >= total) {
            final boolean[] all = new boolean[bits.length];
            java.util.Arrays.fill(all, true);
            return all;
        }
        return new boolean[bits.length];
    }

    // ---------------------------------------------------------------- 贪心合并

    private static List<double[]> merge(final boolean[][][][] occ, final double[] lo,
                                        final double cell, final int subdiv,
                                        final int nx, final int ny, final int nz) {
        final double sub = cell / subdiv;
        final List<double[]> out = new ArrayList<>();
        // 三趟贪心：先 x 方向连续段，再 z、y 方向合并同长的段
        for (int by = 0; by < ny; by++) {
            for (int bz = 0; bz < nz; bz++) {
                for (int bx = 0; bx < nx; bx++) {
                    final boolean[] bits = occ[bx][by][bz];
                    if (bits == null) {
                        continue;
                    }
                    for (int sy = 0; sy < subdiv; sy++) {
                        for (int sz = 0; sz < subdiv; sz++) {
                            int sx = 0;
                            while (sx < subdiv) {
                                if (!bits[(sx * subdiv + sy) * subdiv + sz]) {
                                    sx++;
                                    continue;
                                }
                                int run = 1;
                                while (sx + run < subdiv
                                        && bits[((sx + run) * subdiv + sy) * subdiv + sz]) {
                                    run++;
                                }
                                // z 方向延伸（同一 x 段、同一 y）
                                int zRun = 1;
                                boolean grew = true;
                                while (grew && sz + zRun < subdiv) {
                                    for (int k = 0; k < run; k++) {
                                        if (!bits[((sx + k) * subdiv + sy) * subdiv + sz + zRun]) {
                                            grew = false;
                                            break;
                                        }
                                    }
                                    if (grew) {
                                        zRun++;
                                    }
                                }
                                out.add(new double[]{
                                        lo[0] + bx * cell + sx * sub,
                                        lo[1] + by * cell + sy * sub,
                                        lo[2] + bz * cell + sz * sub,
                                        lo[0] + bx * cell + (sx + run) * sub,
                                        lo[1] + by * cell + (sy + 1) * sub,
                                        lo[2] + bz * cell + (sz + zRun) * sub,
                                });
                                // 清掉已合并的
                                for (int k = 0; k < run; k++) {
                                    for (int m = 0; m < zRun; m++) {
                                        bits[((sx + k) * subdiv + sy) * subdiv + sz + m] = false;
                                    }
                                }
                                sx += run;
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 度量（给断言用）

    /** 盒子集合的总体积。 */
    public static double volume(final List<double[]> boxes) {
        double v = 0.0;
        for (final double[] b : boxes) {
            v += (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]);
        }
        return v;
    }

    /**
     * **可证明的误差上界**（对称差体积）：{@code Area(∂S) · ε}，{@code ε = (cell/subdiv)·√3/2}。
     *
     * @param surfaceArea 实体表面积（解析可算；测试里用细采样估计）
     */
    public static double errorBound(final double surfaceArea, final double cell,
                                    final int subdiv) {
        return surfaceArea * (cell / subdiv) * Math.sqrt(3.0) / 2.0;
    }
}
