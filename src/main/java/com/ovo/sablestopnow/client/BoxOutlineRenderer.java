package com.ovo.sablestopnow.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * 「细长长方体盒」边框绘制工具。
 *
 * <p>把传统 GL 线（{@code VertexFormat.Mode.LINES}，线宽受驱动/平台限制、粗细无法可靠控制）
 * 换成由若干细长方体拼成的盒式边框 —— 与 Create 蓝图/强力胶选框同一视觉语言：
 * 粗细可控、拐角严丝合缝、可用半透明色。
 *
 * <p>顶点只写 position + color，必须配套 {@link ModRenderTypes#BOXES} /
 * {@link ModRenderTypes#BOXES_NO_DEPTH}（POSITION_COLOR + QUADS）使用。
 */
public final class BoxOutlineRenderer {

    /**
     * 立方体 8 个角的符号坐标（乘上半边长即为实际局部坐标）。
     * 顺序与原实现一致：底面 0,1,2,3 逆时针，顶面 4,5,6,7 同序。
     */
    public static final float[][] CUBE_CORNERS = {
            {-1, -1, -1}, {1, -1, -1}, {1, -1, 1}, {-1, -1, 1},
            {-1, 1, -1}, {1, 1, -1}, {1, 1, 1}, {-1, 1, 1}
    };

    /** 6 个面的 4 个角，索引与 {@code Direction.values()} 一致（DOWN, UP, NORTH, SOUTH, WEST, EAST）。 */
    public static final int[][] CUBE_FACES = {
            {0, 1, 2, 3},
            {4, 5, 6, 7},
            {0, 1, 5, 4},
            {3, 2, 6, 7},
            {0, 3, 7, 4},
            {1, 2, 6, 5}
    };

    /** 12 条棱的角点对（编号即去重用的位序号）。 */
    public static final int[][] EDGE_ENDS = {
            {0, 1}, {1, 2}, {2, 3}, {3, 0},   // 底面
            {4, 5}, {5, 6}, {6, 7}, {7, 4},   // 顶面
            {0, 4}, {1, 5}, {2, 6}, {3, 7}    // 竖直
    };

    private static final int[][] EDGE_BIT = new int[8][8];

    static {
        for (int i = 0; i < EDGE_ENDS.length; i++) {
            final int a = EDGE_ENDS[i][0];
            final int b = EDGE_ENDS[i][1];
            EDGE_BIT[a][b] = 1 << i;
            EDGE_BIT[b][a] = 1 << i;
        }
    }

    private BoxOutlineRenderer() {
    }

    /** 立方体角点索引 a 与 b 之间那条棱的位掩码（用于同一方块内去重，避免共面棱被画两次）。 */
    public static int edgeBit(final int cornerA, final int cornerB) {
        return EDGE_BIT[cornerA][cornerB];
    }

    /**
     * 画一条边：以 (x1,y1,z1)-(x2,y2,z2) 为长轴，截面为 {@code thickness × thickness} 的长方体。
     * 两端各向外多伸半个厚度，使相邻两条棱在角点处正好接合、不留缺口也不会互相穿插。
     * 要求该边轴对齐（12 条棱都满足）。
     */
    public static void addEdge(final PoseStack.Pose pose, final VertexConsumer vc,
                               final double x1, final double y1, final double z1,
                               final double x2, final double y2, final double z2,
                               final float thickness, final float r, final float g, final float b, final float a) {
        final double h = thickness * 0.5;
        addSolidBox(pose, vc,
                Math.min(x1, x2) - h, Math.min(y1, y2) - h, Math.min(z1, z2) - h,
                Math.max(x1, x2) + h, Math.max(y1, y2) + h, Math.max(z1, z2) + h,
                r, g, b, a);
    }

    /** 由 min/max 构成的轴对齐盒的 12 条棱（每条棱都是一个细长方体）。 */
    public static void addWireBox(final PoseStack.Pose pose, final VertexConsumer vc,
                                  final double minX, final double minY, final double minZ,
                                  final double maxX, final double maxY, final double maxZ,
                                  final float thickness, final float r, final float g, final float b, final float a) {
        // 底面 4 条
        addEdge(pose, vc, minX, minY, minZ, maxX, minY, minZ, thickness, r, g, b, a);
        addEdge(pose, vc, maxX, minY, minZ, maxX, minY, maxZ, thickness, r, g, b, a);
        addEdge(pose, vc, maxX, minY, maxZ, minX, minY, maxZ, thickness, r, g, b, a);
        addEdge(pose, vc, minX, minY, maxZ, minX, minY, minZ, thickness, r, g, b, a);
        // 顶面 4 条
        addEdge(pose, vc, minX, maxY, minZ, maxX, maxY, minZ, thickness, r, g, b, a);
        addEdge(pose, vc, maxX, maxY, minZ, maxX, maxY, maxZ, thickness, r, g, b, a);
        addEdge(pose, vc, maxX, maxY, maxZ, minX, maxY, maxZ, thickness, r, g, b, a);
        addEdge(pose, vc, minX, maxY, maxZ, minX, maxY, minZ, thickness, r, g, b, a);
        // 竖直 4 条
        addEdge(pose, vc, minX, minY, minZ, minX, maxY, minZ, thickness, r, g, b, a);
        addEdge(pose, vc, maxX, minY, minZ, maxX, maxY, minZ, thickness, r, g, b, a);
        addEdge(pose, vc, maxX, minY, maxZ, maxX, maxY, maxZ, thickness, r, g, b, a);
        addEdge(pose, vc, minX, minY, maxZ, minX, maxY, maxZ, thickness, r, g, b, a);
    }

    /** 局部坐标系下、以原点为中心、半边长为 half 的立方体的某条棱。 */
    public static void addCubeEdge(final PoseStack.Pose pose, final VertexConsumer vc,
                                   final float half, final int cornerA, final int cornerB,
                                   final float thickness, final float r, final float g, final float b, final float a) {
        final float[] p1 = CUBE_CORNERS[cornerA];
        final float[] p2 = CUBE_CORNERS[cornerB];
        addEdge(pose, vc, p1[0] * half, p1[1] * half, p1[2] * half,
                p2[0] * half, p2[1] * half, p2[2] * half, thickness, r, g, b, a);
    }

    // ============ 实心长方体（6 个面） ============
    private static void addSolidBox(final PoseStack.Pose pose, final VertexConsumer vc,
                                    final double minX, final double minY, final double minZ,
                                    final double maxX, final double maxY, final double maxZ,
                                    final float r, final float g, final float b, final float a) {
        final float x0 = (float) minX;
        final float y0 = (float) minY;
        final float z0 = (float) minZ;
        final float x1 = (float) maxX;
        final float y1 = (float) maxY;
        final float z1 = (float) maxZ;

        // -X / +X
        quad(pose, vc, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, r, g, b, a);
        quad(pose, vc, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, r, g, b, a);
        // -Y / +Y
        quad(pose, vc, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a);
        quad(pose, vc, x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, r, g, b, a);
        // -Z / +Z
        quad(pose, vc, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a);
        quad(pose, vc, x1, y0, z1, x1, y1, z1, x0, y1, z1, x0, y0, z1, r, g, b, a);
    }

    private static void quad(final PoseStack.Pose pose, final VertexConsumer vc,
                             final float ax, final float ay, final float az,
                             final float bx, final float by, final float bz,
                             final float cx, final float cy, final float cz,
                             final float dx, final float dy, final float dz,
                             final float r, final float g, final float b, final float a) {
        vc.addVertex(pose, ax, ay, az).setColor(r, g, b, a);
        vc.addVertex(pose, bx, by, bz).setColor(r, g, b, a);
        vc.addVertex(pose, cx, cy, cz).setColor(r, g, b, a);
        vc.addVertex(pose, dx, dy, dz).setColor(r, g, b, a);
    }
}
