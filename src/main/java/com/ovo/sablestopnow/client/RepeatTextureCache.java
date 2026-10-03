package com.ovo.sablestopnow.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.Map;

/**
 * **独立 REPEAT 贴图缓存**：把图集里的某张方块贴图拷出来，注册成一张**自己的、可平铺**的纹理。
 *
 * <h2>为什么必须这么做（这是贴图问题的根本解法）</h2>
 * 方块贴图打在**图集**里，UV 一超过 [0,1] 就采到相邻的别的贴图，所以**不能靠纹理自身的 repeat 平铺**。
 * 项目此前只能把 UV 折进该贴图在图集里的子矩形（对 UV 取小数 `frac`），而那带来一个**必然的错误**：
 *
 * <blockquote>
 * `frac` 必须发生在 **fragment 采样前的最后一刻**；做在顶点/CPU 阶段时，
 * 三角形内部会在**两个不同的贴图周期之间做线性插值** → 结果必然是垃圾
 * （用户实测："四个角的 `frac` 各自回绕、插值出垃圾"、"大面积单色 / 三角块"）。
 * </blockquote>
 *
 * <p>把贴图拷成一张带 **REPEAT** 环绕的独立纹理之后，`frac` **整个消失**：
 * UV 可以自由超过 1，平铺、无缝，而且 mipmap 更干净（图集的 mip 会混进邻块）。
 *
 * <h2>取图路径（不是"猜 PNG 文件名"）</h2>
 * 从**精灵内容**取原图：{@code TextureAtlasSprite.contents().getOriginalImage()}。
 * 这样对"模型引用别的 mod 的贴图""拼接过的精灵"都成立，不需要反查模型 JSON。
 *
 * <h2>⚠ 已知边界（诚实登记）</h2>
 * <ul>
 *   <li><b>动画贴图</b>（水/岩浆/火）：精灵每帧都在变，这里拷的是静态原图 → 结果是静止的。
 *       这类方块应回退到图集路线（见 {@link #isAnimated}）。</li>
 *   <li>每种方块一张 GL 纹理。方块贴图 16×16，内存可忽略；
 *       代价是**打断批处理**（同一模型用到几种贴图就几次 draw call）。</li>
 * </ul>
 */
public final class RepeatTextureCache {

    private RepeatTextureCache() {
    }

    private static final Map<String, ResourceLocation> CACHE = new HashMap<>();

    /**
     * **失败也要记**（负缓存）。
     *
     * <p>⚠ 漏了它会变成性能黑洞：{@link #of} 在渲染循环里**每帧每个实体**都会被调用，
     * 而失败的原因（动画贴图、拷贝失败）是**稳定**的 —— 不记住就意味着每帧都重新解析
     * 方块模型、遍历动画帧。用户报的"MSPT 十几万"里有我这一份责任。
     */
    private static final java.util.Set<String> FAILED = new java.util.HashSet<>();

    /**
     * 取得（必要时创建）某方块贴图的 REPEAT 版本。
     *
     * @return 可直接给 {@code RenderType.entityCutoutNoCull(...)} 用的贴图位置；失败返回 null
     */
    @Nullable
    public static ResourceLocation of(@Nullable final String blockId) {
        if (blockId == null || blockId.isBlank() || FAILED.contains(blockId)) {
            return null;
        }
        final ResourceLocation cached = CACHE.get(blockId);
        if (cached != null) {
            return cached;
        }
        try {
            final Minecraft mc = Minecraft.getInstance();
            final TextureAtlasSprite sprite = SolidTextureCache.previewSprite(blockId);
            if (sprite == null) {
                FAILED.add(blockId);
                return null;
            }
            if (isAnimated(sprite)) {
                // 动画贴图拷成静态图会"冻住"，不如让调用方回退到图集路线
                FAILED.add(blockId);
                return null;
            }
            final NativeImage src = sprite.contents().getOriginalImage();
            if (src == null) {
                FAILED.add(blockId);
                return null;
            }
            final int w = src.getWidth();
            final int h = src.getHeight();
            // ⚠ 必须**拷贝**：DynamicTexture 会接管并最终 close 掉这张图，
            //   而 src 属于精灵、是共享的 —— 直接传进去会把图集里的图关掉（灾难性）。
            final NativeImage copy = new NativeImage(src.format(), w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    copy.setPixelRGBA(x, y, src.getPixelRGBA(x, y));
                }
            }
            final DynamicTexture tex = new DynamicTexture(copy);
            tex.upload();
            final ResourceLocation loc = mc.getTextureManager().register(
                    "sablestopnow_repeat/" + blockId.replace(':', '_').replace('/', '_'), tex);
            // ⚠ **显式设 REPEAT**：不想依赖 DynamicTexture 内部要不要写 clamp。
            //   研究报告指出 `.mcmeta` 的 clamp 在 1.21.1 是否生效"文档自相矛盾"，
            //   所以这里不靠任何间接行为，直接设 GL 参数。
            GlStateManager._bindTexture(tex.getId());
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            // 16 像素 = 1 方块：用 NEAREST，避免线性过滤在贴图接缝处混出软边
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._bindTexture(0);
            CACHE.put(blockId, loc);
            return loc;
        } catch (final Throwable t) {
            // 失败就返回 null，让调用方回退到图集路线 —— 贴图做不出来不该把渲染弄崩
            FAILED.add(blockId);
            return null;
        }
    }

    /** 这个方块的贴图是不是动画（有多个唯一帧）。 */
    private static boolean isAnimated(final TextureAtlasSprite sprite) {
        try {
            return sprite.contents().getUniqueFrames().count() > 1;
        } catch (final Throwable t) {
            return false;
        }
    }

    /** 资源重载时清空（GL 纹理已被释放，缓存必须跟着失效）。 */
    public static void clear() {
        CACHE.clear();
        FAILED.clear();
    }
}
