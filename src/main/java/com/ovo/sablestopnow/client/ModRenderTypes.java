package com.ovo.sablestopnow.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

import java.util.OptionalDouble;

public class ModRenderTypes {
    // 正常深度测试，线宽 2.0
    public static final RenderType LINES = RenderType.create(
            "sablestopnow_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            1536,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(2.0)))
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .createCompositeState(false)
    );

    // 无深度测试，线宽 2.0
    public static final RenderType LINES_NO_DEPTH = RenderType.create(
            "sablestopnow_lines_no_depth",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            1536,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(2.0)))
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
                    .createCompositeState(false)
    );

    /**
     * 草图专用：线宽 4.0。
     *
     * <p>不用共用的 {@link #LINES}（2.0）—— 那个是描边用的，加粗会改变描边的观感。
     * 草图的线在方块世界里本来就细，2.0 拉远一点几乎看不见（用户实测反馈「线太细」）。
     */
    public static final RenderType SKETCH_LINES = RenderType.create(
            "sablestopnow_sketch_lines",
            DefaultVertexFormat.POSITION_COLOR_NORMAL,
            VertexFormat.Mode.LINES,
            1536,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
                    .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(4.0)))
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .createCompositeState(false)
    );

    // 「细长长方体盒」边框：用 QUADS 画实心细长方条代替 GL 线（线宽受驱动限制且跨平台不一致）。
    // 顶点格式沿用 Create/catnip 风格的做法：POSITION_COLOR + 原版 position_color 着色器。
    public static final RenderType BOXES = boxType("sablestopnow_boxes", false);
    public static final RenderType BOXES_NO_DEPTH = boxType("sablestopnow_boxes_no_depth", true);

    /**
     * 草图**背景网格** ✓（需求 ②）：半透明填充 + 半透明细线。
     *
     * <p>为什么用 QUADS 而不是 LINES ✗✓：线宽在驱动间不一致（这也是本类里 BOXES 存在的原因），
     * 网格线要求"细且均匀" ⇒ 用**细长方条**画，跨平台观感一致 ✓
     * <p>{@code COLOR_WRITE}（不写深度）⇒ 网格不会挡住它后面的东西，也不会被后来的图元"切"出缝 ✓
     */
    public static final RenderType SKETCH_GRID = RenderType.create(
            "sablestopnow_sketch_grid",
            DefaultVertexFormat.POSITION_COLOR,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .createCompositeState(false)
    );

    /**
     * 实体**贴图**：方块图集 + alpha 裁剪。
     *
     * <p>用原版的 {@code entityCutoutNoCull} 而不是自己拼 CompositeState：
     * 方块贴图要的就是"图集 + 镂空"这套状态（树叶/玻璃/栅栏的镂空跟着贴图走），
     * 而且它走实体光照管线，顶点里带的 light/overlay 才有意义。
     * 自己拼一套极易漏掉某段状态（比如 alpha 测试），表现为"玻璃画成实心"。
     *
     * <p>⚠ 顶点格式是 {@code NEW_ENTITY} —— 写顶点时必须带上
     * {@code setColor / setUv / setOverlay / setLight / setNormal}，缺一个都会读出垃圾。
     */
    public static final RenderType SOLID_TEXTURED =
            RenderType.entityCutoutNoCull(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS);

    private static RenderType boxType(final String name, final boolean noDepth) {
        return RenderType.create(
                name,
                DefaultVertexFormat.POSITION_COLOR,
                VertexFormat.Mode.QUADS,
                1536,
                false,
                true,
                RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                        .setLayeringState(RenderStateShard.VIEW_OFFSET_Z_LAYERING)
                        .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                        .setOutputState(RenderStateShard.ITEM_ENTITY_TARGET)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setDepthTestState(noDepth ? RenderStateShard.NO_DEPTH_TEST : RenderStateShard.LEQUAL_DEPTH_TEST)
                        .createCompositeState(false)
        );
    }
}