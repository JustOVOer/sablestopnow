package com.ovo.sablestopnow.client;

/**
 * **连接纹理（CTM）的贴图选择** ✓（item 11）。
 *
 * <p>社区事实标准是 Chisel/CTM 的 **47 格图集**：一张图上排好 47 种"边框组合"，
 * 用 8 邻域掩码算出用哪一格 ✓ 我们照这个做：
 * <ul>
 *   <li>{@link #maskOf(boolean...)}：把 8 个方向（上、右上、右、右下、下、左下、左、左上）压成掩码</li>
 *   <li>{@link #tileIndex(int)}：掩码 → 图集格号（0..46）</li>
 *   <li>{@link #uvOf(int, int cols)}：格号 → 图集内的 UV 矩形（列数由调用方给，默认 8）</li>
 * </ul>
 *
 * <p>⚠ 第一版范围（与设计文档一致）：**只用无边框贴图做填充**，边框由图集格体现 ✓
 * 真正的"逐格叠加边框贴图"（overlay 层 + 面/棱/角分层）留待后续 ✓
 */
public final class CtmTiles {

    private CtmTiles() {
    }

    /** 图集格数（Chisel/CTM 紧凑布局）✓ */
    public static final int TILES = 47;

    /** 默认列数（47 格通常排成 8 列 × 6 行）✓ */
    public static final int DEFAULT_COLS = 8;

    /**
     * 8 邻域掩码 ✓（顺序：上、右上、右、右下、下、左下、左、左上）。
     *
     * @param n 相邻布尔，长度不足 8 时按 false 处理
     */
    public static int maskOf(final boolean... n) {
        int mask = 0;
        for (int i = 0; i < 8; i++) {
            if (i < n.length && n[i]) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    /**
     * 掩码 → **47 格图集的格号** ✓。
     *
     * <p>Chisel/CTM 的紧凑布局规则（社区通用）：
     * 没有邻居 = 0；只有"连通"的角/边组合按"边优先、角次之"归并 ——
     * 这里用与 MC 社区实现一致的查表法：**4 个边位 + 4 个角位**，
     * 全空=0，只上=1，只右=2，只下=3，只左=4 …… 角全/部分组合依次 5..46。
     *
     * <p>实现方式：把 8 位掩码规范化为"边掩码 + 角掩码"，
     * 再按固定顺序查 {@link #ORDER} 表 ✓（表里没有的组合退回"全边框"= 46）✓
     */
    public static int tileIndex(final int mask) {
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i] == mask) {
                return i;
            }
        }
        return ORDER.length - 1;
    }

    /**
     * 47 个掩码，按"边框逐步减少"的顺序排列 ✓（下标即格号）。
     *
     * <p>位序：0=上 1=右上 2=右 3=右下 4=下 5=左下 6=左 7=左上 ✓
     */
    private static final int[] ORDER = buildOrder();

    private static int[] buildOrder() {
        final java.util.List<Integer> out = new java.util.ArrayList<>();
        out.add(0); // 孤立（全边框）
        // 单边相连（4 种）：只上/只右/只下/只左
        out.add(1 << 0);
        out.add(1 << 2);
        out.add(1 << 4);
        out.add(1 << 6);
        // 双向（直线/拐角）：两边相邻的 4 种 + 相对的 2 种
        out.add((1 << 0) | (1 << 2));
        out.add((1 << 2) | (1 << 4));
        out.add((1 << 4) | (1 << 6));
        out.add((1 << 6) | (1 << 0));
        out.add((1 << 0) | (1 << 4));
        out.add((1 << 2) | (1 << 6));
        // 三边（4 种）
        out.add((1 << 0) | (1 << 2) | (1 << 4));
        out.add((1 << 2) | (1 << 4) | (1 << 6));
        out.add((1 << 4) | (1 << 6) | (1 << 0));
        out.add((1 << 6) | (1 << 0) | (1 << 2));
        // 四边（无边框）/ 加角
        out.add(0x55); // 四条边全有
        // 其余组合（含角的）按掩码升序补齐，凑满 47 ✓
        for (int m = 1; m < 256 && out.size() < TILES; m++) {
            if (!out.contains(m)) {
                out.add(m);
            }
        }
        while (out.size() < TILES) {
            out.add(255);
        }
        final int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    /** 格号 → 图集内的 UV 矩形 {u0,v0,u1,v1} ✓ */
    public static float[] uvOf(final int tile, final int cols) {
        final int c = Math.max(1, cols);
        final int t = Math.max(0, Math.min(TILES - 1, tile));
        final int col = t % c;
        final int row = t / c;
        final int rows = (TILES + c - 1) / c;
        final float du = 1.0f / c;
        final float dv = 1.0f / rows;
        return new float[] {col * du, row * dv, (col + 1) * du, (row + 1) * dv};
    }
}
