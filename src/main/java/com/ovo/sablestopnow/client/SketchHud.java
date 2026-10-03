package com.ovo.sablestopnow.client;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.client.gui.lib.Container;
import com.ovo.sablestopnow.client.gui.lib.Label;
import com.ovo.sablestopnow.client.gui.lib.Layouts;
import com.ovo.sablestopnow.client.gui.lib.Ui;
import com.ovo.sablestopnow.client.gui.lib.UiLayer;
import com.ovo.sablestopnow.sketch.SketchSession;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 草图环境的**光标旁提示**（推理吸附的种类：端点 / 中点 / 圆心）。
 *
 * <h2>为什么必须跟着光标，而不是放左栏</h2>
 * 左栏是靠 {@code leftPanelSignature()} 指纹变化才重建的，而吸附目标**每帧都在变**。
 * 把提示放左栏只有两个结果：要么它永远显示陈旧值（骗人），要么把吸附目标塞进指纹里，
 * 于是鼠标每动一下整个左栏就重建一次（正在输入的尺寸框会被掀掉）。
 * 跟随光标的即时反馈本来就不属于侧栏 —— 本类就是它的正确归属。
 *
 * <p>实现上是**一层常驻的纯展示 {@link UiLayer}**：图层只在进出草图时开关，
 * 文本用活取值供应商（每帧读当前吸附目标），可见性用 {@code alpha} 控制。
 * 这样避免了「吸附一出现就重建图层」在容差边界上反复开关。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT)
public final class SketchHud {

    private static final String LAYER = "sketch.snap.hud";
    /** 光标右下方的偏移，免得提示压在准星上。 */
    private static final int OFFSET_X = 12;
    private static final int OFFSET_Y = 10;

    /** 图层里那一行提示。{@code alpha} 只在 {@link Label} 上，所以必须拿着具体引用，
     *  不能遍历 {@code children} 当 {@code Widget} 用。 */
    @Nullable
    private static Label snapLabel;

    private SketchHud() {
    }

    @SubscribeEvent
    public static void onRenderGui(final RenderGuiEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        if (!SketchClient.active() || mc.player == null || mc.level == null) {
            Ui.close(LAYER);
            snapLabel = null;
            return;
        }
        // 尺寸标签先画：它是"贴在世界里的"，跟光标无关，不该被下面的提前 return 跳过
        drawDimensionLabels(event.getGuiGraphics(), mc);

        UiLayer layer = Ui.layer(LAYER);
        if (layer == null) {
            layer = Ui.open(UiLayer.of(LAYER));
            // 纯展示：绝不能吞掉鼠标/键盘，也不占 HUD 避让位
            layer.hud = true;
            layer.consumeInput = false;
            layer.consumeKeys = false;
            layer.reserveSpace = false;
            layer.closeOnEscape = false;
            layer.root.layout = Layouts.absolute();
            // 活取值：内容跟着当前吸附目标走，不需要重建图层
            snapLabel = new Label(SketchHud::snapText).color(0xFFFFCC55);
            layer.root.add(snapLabel);
        }

        // 吸附目标没了就靠 alpha 隐掉，而不是关掉图层（容差边界上会反复开关）
        final boolean visible = SketchClient.mode().snapAt() != null;
        if (snapLabel != null) {
            snapLabel.alpha = visible ? 1.0f : 0.0f;
        }
        if (!visible) {
            return;
        }
        final Container root = layer.root;
        root.padding(clamp(guiX(mc) + OFFSET_X, 0, mc.getWindow().getGuiScaledWidth() - root.w - 2),
                clamp(guiY(mc) + OFFSET_Y, 0, mc.getWindow().getGuiScaledHeight() - root.h - 2),
                0, 0);
    }

    /**
     * 把尺寸标签**画在几何旁边**（SolidWorks 的尺寸就是贴在图元边上的）。
     *
     * <h2>三段各自的做法</h2>
     * <ol>
     *   <li>纯逻辑给出尺寸与**标签锚点**（平面内 u,v）—— 见 {@code SketchMeasure.Dimension}；</li>
     *   <li>锚点经 平面 → plot 局部 → 世界（子结构 renderPose），再用
     *       {@link com.ovo.sablestopnow.client.model.ModelCamera#projectToScreen} 投影到屏幕；</li>
     *   <li>文字走 GUI 库的 {@code UiDraw.text}（不能直接 {@code drawString} —— 那会破坏
     *       "所有文字都过 GUI 库"这条门禁，也会漏掉缩放与阴影的一致性）。</li>
     * </ol>
     *
     * <p>⚠ **投影在相机背后时要丢掉**：{@code projectToScreen} 对背后的点会给出
     * 一个"翻过来"的坐标（除以负数 w），画出来就是屏幕上乱飘的文字。
     *
     * <p>颜色语义：已有尺寸正常色；**实测与常数不一致**用警示色（求解器还没算平）；
     * 参考尺寸灰显（还没被约束）。
     */
    private static void drawDimensionLabels(final net.minecraft.client.gui.GuiGraphics g,
                                            final Minecraft mc) {
        final com.ovo.sablestopnow.sketch.SketchSession session =
                SketchClient.mode().session();
        final com.ovo.sablestopnow.sketch.Sketch working = session == null ? null
                : session.working();
        if (working == null) {
            return;
        }
        // 找到这张草图所属的子结构（拿它的渲染位姿把平面坐标变成世界坐标）
        dev.ryanhcode.sable.sublevel.ClientSubLevel sub = null;
        if (dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level)
                instanceof final dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer c) {
            for (final dev.ryanhcode.sable.sublevel.ClientSubLevel s : c.getAllSubLevels()) {
                if (s.getUniqueId().equals(working.body())) {
                    sub = s;
                }
            }
        }
        if (sub == null) {
            return;
        }
        final org.joml.Vector3d tmp = new org.joml.Vector3d();
        int shown = 0;
        // 参考尺寸先画（在下面），已有尺寸后画（盖在上面）—— 已有的是"更硬"的信息
        final java.util.List<com.ovo.sablestopnow.sketch.SketchMeasure.Dimension> all =
                new java.util.ArrayList<>();
        all.addAll(com.ovo.sablestopnow.sketch.SketchMeasure.referenceDimensions(working));
        all.addAll(com.ovo.sablestopnow.sketch.SketchMeasure.existingDimensions(working));
        for (final var d : all) {
            final org.joml.Vector3d local =
                    working.plane().toLocal(d.anchorU(), d.anchorV());
            final org.joml.Vector3d world = sub.renderPose().transformPosition(local, tmp);
            final double[] screen = com.ovo.sablestopnow.client.model.ModelCamera
                    .projectToScreen(new net.minecraft.world.phys.Vec3(world.x, world.y, world.z), mc);
            if (screen == null || screen.length < 3 || screen[2] <= 0.0) {
                continue; // 在相机背后 / 投影失败 → 不画（否则文字会在屏幕上乱飘）
            }
            final String text = String.format(java.util.Locale.ROOT, "%.3f", d.value());
            final int argb;
            if (!d.driving()) {
                argb = 0xFF8C93A8;                       // 参考尺寸：灰
            } else if (Math.abs(d.measured() - d.value()) > 1.0e-4) {
                argb = 0xFFFF8A5C;                       // 实测 ≠ 常数：求解器还没算平
            } else {
                argb = 0xFFE6E6E6;                       // 正常
            }
            com.ovo.sablestopnow.client.gui.lib.UiDraw.text(g, mc.font, text,
                    (int) screen[0], (int) screen[1], argb, 1.0f, true);
            shown++;
            if (shown >= 48) {
                break; // 上限：约束很多时别把屏幕糊满，也别把 GUI 批次撑爆
            }
        }
    }

    /** 提示文本的活取值。没有吸附目标时给空串 —— 反正此时 alpha 已经把它隐掉了。 */
    private static Component snapText() {
        if (!SketchClient.active()) {
            return Component.empty();
        }
        final SketchSession.KeyPoint kp = SketchClient.mode().snapAt();
        return kp == null ? Component.empty()
                : Component.translatable("model.sketch.snap.hint",
                        Component.translatable(kp.kind().labelKey()));
    }

    private static int guiX(final Minecraft mc) {
        return (int) (mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth()
                / Math.max(1, mc.getWindow().getWidth()));
    }

    private static int guiY(final Minecraft mc) {
        return (int) (mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight()
                / Math.max(1, mc.getWindow().getHeight()));
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }
}
