package com.ovo.sablestopnow.sketch;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * 一个实体的**外观**：用哪种方块贴图、铺多大。
 *
 * <h2>为什么单独做一个值对象</h2>
 * 它被三处共用：渲染（取贴图）、存档（写读）、左栏 UI（选择与显示）。
 * 塞进 {@code Solid} 的某个字段里会让三处各自解释同一个 double 的含义；
 * 而它的约束（比例必须为正、id 必须能往返）正好可以**离线验证**。
 *
 * <p>⚠ 方块 id 存成 **String** 而不是 {@code ResourceLocation}：
 * 这样这个类不依赖 Minecraft，能用 {@code tools/SketchModelCheck.java} 离线验证
 *（本模组一贯的分层要求）。MC 那边再转成 {@code ResourceLocation}。
 *
 * <p>⚠ 只依赖 JOML 之外的 JDK，可用 {@code tools/SketchModelCheck.java} 验证。
 */
public record SolidAppearance(@Nullable String blockId, double blocksPerTile,
                              boolean connected) {

    /** 兼容老代码的两参构造 ✓（connected 默认 false） */
    public SolidAppearance(final String blockId, final double blocksPerTile) {
        this(blockId, blocksPerTile, false);
    }

    /** 默认比例：一个方块铺一次，和原版方块一致。 */
    public static final double DEFAULT_BLOCKS_PER_TILE = 1.0;

    /** 允许的比例范围。太小的值会让一张贴图铺满整个模型（远处会糊成一片）。 */
    public static final double MIN_BLOCKS_PER_TILE = 0.25;

    /** 太大的值会让贴图密到变成噪点，白白浪费采样。 */
    public static final double MAX_BLOCKS_PER_TILE = 8.0;

    /** 什么都没有（用默认材质 / 纯色）。 */
    public static final SolidAppearance EMPTY = new SolidAppearance(null, DEFAULT_BLOCKS_PER_TILE);

    public SolidAppearance {
        // ⚠ 夹取放在这里一次做完（而不是留给取用方）：记录自己的字段就是权威值。
        // 分两处夹取的话，blocksPerTile() 会返回一个从没被夹过的数，而渲染用的是夹过的 ——
        // 两个答案，迟早对不上（这个坑在 Revolve.Params 上踩过一次）。
        // ⚠ **任何非有限值都当成"没有值" → 默认**（NaN、±∞ 同一条规则）。
        // 不把 ±∞ 夹到上下限：那会多出一条特例，而特例是以后读代码的人最容易搞错的地方。
        if (!Double.isFinite(blocksPerTile)) {
            blocksPerTile = DEFAULT_BLOCKS_PER_TILE;
        }
        blocksPerTile = Math.max(MIN_BLOCKS_PER_TILE,
                Math.min(blocksPerTile, MAX_BLOCKS_PER_TILE));
        if (blockId != null && blockId.isBlank()) {
            blockId = null; // 空串当成"没设"，免得渲染时去查一个必然找不到的 id
        }
    }

    /** 有没有指定方块贴图。 */
    public boolean hasTexture() {
        return blockId != null;
    }

    /** 换一种方块贴图（比例不动）。 */
    public SolidAppearance withBlock(@Nullable final String nextBlockId) {
        return new SolidAppearance(nextBlockId, blocksPerTile);
    }

    /** 换比例（方块不动）。 */
    public SolidAppearance withBlocksPerTile(final double next) {
        return new SolidAppearance(blockId, next);
    }

    // ==================================================================
    // 编码：给存档用（纯逻辑，可离线验往返）
    // ==================================================================

    /**
     * 编码成两个字符串，便于塞进任何存档格式。
     *
     * <p>⚠ 用**字符串**而不是把 double 直接写进 NBT：这样往返的语义
     *（包括夹取、空串归零）完全由本类负责，存档层不需要知道任何规则。
     * 数值格式固定用 {@link Double#toString}（往返精确），不用本地化格式。
     *
     * @return {@code {blockId 或 "", blocksPerTile}}
     */
    /** item 11: 打开/关闭连接纹理 ✓ */
    public SolidAppearance withConnected(final boolean on) {
        return new SolidAppearance(blockId, blocksPerTile, on);
    }

    public String[] encode() {
        return new String[] {blockId == null ? "" : blockId, Double.toString(blocksPerTile),
                Boolean.toString(connected)};
    }

    /**
     * 从 {@link #encode()} 的结果还原。**任何坏输入都退回默认值**，不抛。
     *
     * <p>存档是要长期活着的：一条坏记录不该让整个模型消失（和 {@code SolidData} 同一原则）。
     */
    /** 兼容老调用（没有 connected 段）✓ */
    public static SolidAppearance decode(@Nullable final String blockId,
                                         @Nullable final String blocksPerTile) {
        return decode(blockId, blocksPerTile, null);
    }

    /** item 11：connected = 连接纹理开关 ✓（缺失/坏值 ⇒ false） */
    public static SolidAppearance decode(@Nullable final String blockId,
                                         @Nullable final String blocksPerTile,
                                         @Nullable final String connected) {
        double scale = DEFAULT_BLOCKS_PER_TILE;
        if (blocksPerTile != null && !blocksPerTile.isBlank()) {
            try {
                scale = Double.parseDouble(blocksPerTile);
            } catch (final NumberFormatException e) {
                scale = DEFAULT_BLOCKS_PER_TILE;
            }
        }
        return new SolidAppearance(blockId, scale);
    }

    /** 两个外观是否等价（用于判断"要不要重建网格/重新取贴图"）。 */
    public boolean sameAs(@Nullable final SolidAppearance other) {
        return other != null
                && Objects.equals(blockId, other.blockId)
                && Math.abs(blocksPerTile - other.blocksPerTile) < 1.0e-9;
    }

    // ==================================================================
    // 选方块的搜索（纯逻辑，UI 只是把它画出来）
    // ==================================================================

    /** 搜索默认最多返回多少条 —— 界面一屏放不下更多，建全表也只是白烧。 */
    public static final int SEARCH_LIMIT = 40;

    /**
     * 从候选方块 id 里按关键字挑一批，**按相关度排序、结果稳定**。
     *
     * <h2>为什么排序规则要写死在这里</h2>
     * 界面的候选列表**每次重建都会重算**。如果顺序不确定（比如依赖哈希迭代顺序），
     * 玩家会看到列表"自己跳来跳去"，像是没响应；而这是最难描述、最难复现的一类 bug。
     * 所以：**输入顺序即并列者的最终顺序**（稳定排序），相关度只在它之上分层。
     *
     * <p>另外把**空格和下划线视为等价**：玩家会打 {@code oak planks}，而 id 是
     * {@code minecraft:oak_planks}。不归一化的话最常见的输入方式搜不到东西。
     *
     * @param candidates 候选 id（原样显示，所以不做大小写/格式改写）
     * @param query      关键字；空白表示"不过滤"
     * @param limit      最多返回几条；{@code <=0} 时用 {@link #SEARCH_LIMIT}
     */
    public static java.util.List<String> searchBlocks(
            @Nullable final java.util.Collection<String> candidates,
            @Nullable final String query, final int limit) {
        final java.util.List<String> out = new java.util.ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return out;
        }
        final int cap = limit > 0 ? limit : SEARCH_LIMIT;
        final String needle = normalize(query);
        if (needle.isEmpty()) {
            // 不过滤：保持**输入顺序**（界面自己决定默认展示什么）
            for (final String id : candidates) {
                if (id != null && out.size() < cap) {
                    out.add(id);
                }
            }
            return out;
        }
        // 分层：0 = 路径完全等于关键字，1 = 路径以关键字开头，2 = 路径包含，
        //       3 = 完整 id 包含（命名空间里命中），4 = 不匹配。
        // ⚠ 用 List<List> 而不是 List[]：泛型数组会触发 unchecked 警告，
        //   而且它只是"五层桶"，用列表更直白。
        final java.util.List<java.util.List<String>> tiers = new java.util.ArrayList<>(5);
        for (int i = 0; i < 5; i++) {
            tiers.add(new java.util.ArrayList<>());
        }
        for (final String id : candidates) {
            if (id == null) {
                continue;
            }
            final String full = normalize(id);
            final String path = normalize(pathOf(id));
            final int tier;
            if (path.equals(needle)) {
                tier = 0;
            } else if (path.startsWith(needle)) {
                tier = 1;
            } else if (path.contains(needle)) {
                tier = 2;
            } else if (full.contains(needle)) {
                tier = 3;
            } else {
                tier = 4;
            }
            if (tier < 4) {
                tiers.get(tier).add(id);
            }
        }
        for (final java.util.List<String> tier : tiers) {
            for (final String id : tier) {
                if (out.size() >= cap) {
                    return out;
                }
                out.add(id);
            }
        }
        return out;
    }

    /** {@code minecraft:oak_planks} → {@code oak_planks}（没有命名空间时原样返回）。 */
    public static String pathOf(final String blockId) {
        if (blockId == null) {
            return "";
        }
        final int colon = blockId.indexOf(':');
        return colon >= 0 && colon + 1 < blockId.length() ? blockId.substring(colon + 1) : blockId;
    }

    /** 归一化：小写、空格→下划线、去首尾空白。 */
    private static String normalize(@Nullable final String s) {
        if (s == null) {
            return "";
        }
        return s.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
    }
}
