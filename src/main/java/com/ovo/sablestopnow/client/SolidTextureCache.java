package com.ovo.sablestopnow.client;

import com.ovo.sablestopnow.sketch.SolidMesh;
import com.ovo.sablestopnow.sketch.SolidUv;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 把"一个方块 id"解析成**三个面各自的贴图子矩形**，并缓存。
 *
 * <h2>为什么要缓存</h2>
 * 取贴图要经过「id → BlockState → BakedModel → getQuads → sprite」，
 * 而 {@code getQuads} 每次都要重算模型四边形 —— **每帧对每个实体都做一遍是不可接受的**。
 * 方块模型在资源重载前不会变，所以按 id 缓存是安全的。
 *
 * <p>⚠ 缓存必须在**资源重载时清空**：重载会换掉整套 atlas 与模型，
 * 旧 {@code TextureAtlasSprite} 里的 UV 会指向已经不存在的图集位置。
 * 见 {@link #invalidate()}。
 *
 * <p>⚠ 只在**客户端渲染线程**上调用：{@code Minecraft.getInstance().getBlockRenderer()}
 * 不是线程安全的。
 */
public final class SolidTextureCache {

    /**
     * 一个方块在三类面上的贴图。
     *
     * @param capUp   盖面朝正向用的贴图
     * @param capDown 盖面朝反向用的贴图
     * @param side    侧面用的贴图
     */
    public record Faces(SolidUv.SpriteRect capUp, SolidUv.SpriteRect capDown,
                        SolidUv.SpriteRect side, TextureAtlasSprite particle) {
    }

    /**
     * 解析结果缓存。
     *
     * <p>用 {@link LinkedHashMap} + 手写 LRU 上限：玩家可能翻很多方块，
     * 无上限缓存会一直涨；而方块模型本身占用不小。
     */
    private static final int MAX_ENTRIES = 256;
    private static final Map<String, Faces> CACHE =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(final Map.Entry<String, Faces> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private SolidTextureCache() {
    }

    /** 资源重载时调用 —— 旧的 sprite 已经指向失效的图集位置。 */
    public static void invalidate() {
        CACHE.clear();
    }

    /**
     * 解析一个方块 id。任何一步失败都返回 {@code null}（调用方退回线框）。
     *
     * <p>⚠ 失败**不抛异常**：存档里的 id 可能来自已经卸载的模组，
     * 那种情况下该实体画不出来是可以接受的，但整个渲染崩掉不行。
     */
    @Nullable
    public static Faces resolve(@Nullable final String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return null;
        }
        final Faces cached = CACHE.get(blockId);
        if (cached != null) {
            return cached;
        }
        final Faces built = build(blockId);
        if (built != null) {
            CACHE.put(blockId, built);
        }
        return built;
    }

    /**
     * **预览用**：这个方块的代表性精灵（下拉菜单里画那一格贴图）。
     *
     * <p>为什么单独一个方法：渲染实体要的是 {@link SolidUv.SpriteRect}（自己算 UV 的矩形），
     * 而 GUI 的 {@code blit} 要的是 {@code TextureAtlasSprite} 本身
     * —— 两者不能互相替代，所以这里把**精灵对象**也暴露出来。
     *
     * <p>取不到（方块 id 无效、模型没加载）时返回 null，GUI 那边就只画文字。
     */
    @Nullable
    public static net.minecraft.client.renderer.texture.TextureAtlasSprite previewSprite(
            @Nullable final String blockId) {
        if (blockId == null || blockId.isBlank()) {
            return null;
        }
        try {
            final Minecraft mc = Minecraft.getInstance();
            // 解析方式与 build() 保持一致（同一个注册表、同一个默认状态）
            final ResourceLocation id = ResourceLocation.tryParse(blockId);
            if (id == null || !net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .containsKey(id)) {
                return null;
            }
            final net.minecraft.world.level.block.Block block =
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
            final net.minecraft.world.level.block.state.BlockState state =
                    block.defaultBlockState();
            final net.minecraft.client.resources.model.BakedModel model =
                    mc.getBlockRenderer().getBlockModel(state);
            if (model == null) {
                return null;
            }
            return model.getParticleIcon();
        } catch (final Throwable t) {
            return null; // 预览失败不该把 GUI 弄崩
        }
    }

    @Nullable
    private static Faces build(final String blockId) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return null;
        }
        final ResourceLocation id = ResourceLocation.tryParse(blockId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return null; // 认不出的 id（模组卸载过 / 打错字）→ 交给调用方画线框
        }
        final Block block = BuiltInRegistries.BLOCK.get(id);
        final BlockState state = block.defaultBlockState();
        final BakedModel model = mc.getBlockRenderer().getBlockModel(state);

        // 方块模型**实际提供**了哪些面 —— 自定义模型常常只写一部分
        final Map<SolidMesh.BlockFace, TextureAtlasSprite> byFace =
                new EnumMap<>(SolidMesh.BlockFace.class);
        final Set<SolidMesh.BlockFace> available =
                EnumSet.noneOf(SolidMesh.BlockFace.class);
        final RandomSource random = RandomSource.create(42L);
        for (final Direction dir : Direction.values()) {
            final SolidMesh.BlockFace face = toBlockFace(dir);
            if (face == null) {
                continue;
            }
            final var quads = model.getQuads(state, dir, random);
            if (!quads.isEmpty()) {
                byFace.put(face, quads.get(0).getSprite());
                available.add(face);
            }
        }
        final TextureAtlasSprite particle = model.getParticleIcon();
        final TextureAtlasSprite capUp = pick(byFace, available,
                SolidMesh.FaceKind.CAP_UP, particle);
        final TextureAtlasSprite capDown = pick(byFace, available,
                SolidMesh.FaceKind.CAP_DOWN, particle);
        final TextureAtlasSprite side = pick(byFace, available,
                SolidMesh.FaceKind.SIDE, particle);
        if (capUp == null || capDown == null || side == null) {
            return null;
        }
        return new Faces(rect(capUp), rect(capDown), rect(side), particle);
    }

    /**
     * 按面类别挑一张贴图，走 {@link SolidMesh#pickAvailable} 的**回退链**
     *（首选 → 对面 → 其余按固定顺序），最后才退到粒子图标。
     */
    @Nullable
    private static TextureAtlasSprite pick(
            final Map<SolidMesh.BlockFace, TextureAtlasSprite> byFace,
            final Set<SolidMesh.BlockFace> available, final SolidMesh.FaceKind kind,
            @Nullable final TextureAtlasSprite fallback) {
        final SolidMesh.BlockFace face = SolidMesh.pickAvailable(kind, available);
        if (face != null) {
            final TextureAtlasSprite sprite = byFace.get(face);
            if (sprite != null) {
                return sprite;
            }
        }
        // 兜底：粒子图标。方块模型即使一个面都没写，粒子图标通常也有 ——
        // 总比画成空白好（"有个东西"远好过"什么都没有"）。
        return fallback;
    }

    private static SolidUv.SpriteRect rect(final TextureAtlasSprite sprite) {
        return new SolidUv.SpriteRect(sprite.getU0(), sprite.getU1(),
                sprite.getV0(), sprite.getV1());
    }

    /** Minecraft 的 {@code Direction} → 我们的纯逻辑枚举（只有这 6 个，不会有 null 分支）。 */
    @Nullable
    public static SolidMesh.BlockFace toBlockFace(final Direction dir) {
        return switch (dir) {
            case UP -> SolidMesh.BlockFace.UP;
            case DOWN -> SolidMesh.BlockFace.DOWN;
            case NORTH -> SolidMesh.BlockFace.NORTH;
            case SOUTH -> SolidMesh.BlockFace.SOUTH;
            case EAST -> SolidMesh.BlockFace.EAST;
            case WEST -> SolidMesh.BlockFace.WEST;
        };
    }
}
