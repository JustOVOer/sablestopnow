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

    /**
     * 上一帧画过的**尺寸标签屏幕位置** ✓。
     *
     * <p>为什么要缓存 ✗✓：屏幕上"每个尺寸标签在哪"只有**画的时候**知道 ✓（要投影 ✓）。
     * 双击改值需要做命中测试 ✓ 如果那边再算一遍投影 ⇒ 迟早出现"看到的和点到的不是一个地方"✗
     * （本项目已经栽过好几次"两处各算一次"的坑 ✓）。所以：**一处投影 ✓ 两处使用** ✓ 每帧覆盖 ✓。
     */
    public record LabelHit(int constraintIndex, boolean driving, double x, double y) {
    }

    private static final java.util.List<LabelHit> LABEL_HITS = new java.util.ArrayList<>();

    /**
     * 用户**拖动**出来的标签位置覆盖 ✓（约束下标 → 平面 uv ✓）。
     *
     * <p>为什么不写回数据结构 ✗✓：锚点本来是由 {@code SketchMeasure} **算出来**的 ✓（跟着几何走 ✓）
     * 玩家拖的只是"显示在哪"✓ —— 属于**视图状态** ✗ 不是几何 ✗
     * ⇒ 存 UI 侧最合适 ✓（也不污染存档/网络协议 ✓）。
     */
    // ================= 约束小图标（图元旁 ✓ SolidWorks 的几何关系图标 ✓）===================

    /** 一个约束字形的屏幕位置 ✓（供"点选约束"用 ✓ 与尺寸标签同一套做法 ✓）。 */
    public record GlyphHit(int constraintIndex, int glyphKind, double x, double y) {
    }

    private static final java.util.List<GlyphHit> GLYPH_HITS = new java.util.ArrayList<>();

    /** 命中测试：屏幕坐标附近的约束字形 ✓。 */
    @Nullable
    public static GlyphHit pickGlyph(final double mx, final double my, final double tol) {
        GlyphHit best = null;
        double bestD = tol * tol;
        for (final GlyphHit h : GLYPH_HITS) {
            final double dx = h.x() - mx;
            final double dy = h.y() - my;
            final double d = dx * dx + dy * dy;
            if (d <= bestD) {
                bestD = d;
                best = h;
            }
        }
        return best;
    }

    private static final java.util.Map<Integer, double[]> LABEL_OVERRIDE =
            new java.util.HashMap<>();

    /** 拖动时写入标签覆盖位置 ✓（平面 uv ✓）。 */
    public static void setLabelOverride(final int constraintIndex, final double u, final double v) {
        if (constraintIndex < 0) {
            return;
        }
        LABEL_OVERRIDE.put(constraintIndex, new double[] {u, v});
    }

    /** 当前应当显示的位置 ✓（有覆盖用覆盖 ✓ 否则用算出来的锚点 ✓）。 */
    private static double[] labelUv(final com.ovo.sablestopnow.sketch.SketchMeasure.Dimension d) {
        final double[] ov = LABEL_OVERRIDE.get(d.constraintIndex());
        return ov != null ? ov : new double[] {d.anchorU(), d.anchorV()};
    }

    // ================= 双击改值浮层 ✓（SolidWorks：双击尺寸数字 ⇒ 弹框改值 ⇒ 实时驱动 ✓）=======

    /** 浮层的层名 ✓（具名层 ⇒ 任何地方都能按名字开关 ✓ 不需要 UiContext ✓ 见 ModelInput ✓）。 */
    private static final String EDIT_LAYER = "sketch.dim.edit";

    /** 正在编辑的约束下标（-1 = 没在编辑 ✓）。 */
    private static int editConstraint = -1;

    /** 打开改值浮层 ✓（由 ModelInput 双击命中后调用 ✓）。 */
    public static void openEdit(final int constraintIndex, final String labelKey,
                                final double value) {
        Ui.close(EDIT_LAYER); // 先关旧的 ✓ 重建最省心 ✓（不残留上一次的状态 ✗）
        editConstraint = constraintIndex;
        final UiLayer layer = Ui.open(UiLayer.of(EDIT_LAYER));
        layer.hud = true;
        layer.consumeInput = true;   // 要能收键盘 ✓ 否则输入不进文本框 ✗
        layer.consumeKeys = true;
        layer.reserveSpace = false;
        layer.closeOnEscape = true;
        final com.ovo.sablestopnow.client.gui.lib.Callout box =
                new com.ovo.sablestopnow.client.gui.lib.Callout();
        // ★ 修实测告警 ✓：[ui-lint] 布局塌陷 Callout#null 104x0 ✗
        //   原因 ✓：Callout 的 AUTO 尺寸要靠 measure() ✓ 而这条链在某些时机拿不到 ⇒ 高塌成 0 ✗
        //   ⇒ 用 Callout **自带**的 minW/minH（它的 measure 里就是 max(minW, 内容) ✓）
        //     自动变大 ✓ 但**永不塌成 0** ✓✓（比写死尺寸好：内容多了还能自己长 ✓）
        box.id = "sketch.dim.edit.box";
        box.minW = 180;
        box.minH = 62;
        box.layout = Layouts.vbox(10, 4);
        // ★ 贴到**鼠标旁** ✓（用户要求：落下尺寸时应在鼠标旁边弹窗改长度 ✓ 别塞进左栏 ✗）
        //   实现 ✓：图层根用绝对布局 ⇒ 控件自己带 x/y ✓（Widget.x/y + Layouts.absolute ✓）
        layer.root.layout = Layouts.absolute();
        box.x = clamp((int) com.ovo.sablestopnow.client.gui.lib.Ui.mouseXd() + 14, 6,
                Math.max(6, layer.root.w - box.minW - 6));
        box.y = clamp((int) com.ovo.sablestopnow.client.gui.lib.Ui.mouseYd() + 10, 6,
                Math.max(6, layer.root.h - box.minH - 6));
        box.add(new Label(Component.translatable(labelKey)).color(0xFFFFCC55));
        // 数值输入 ✓：numeric() + 打开就全选 ✓（照 ModelCouplingUi 的既有用法 ✓）
        final com.ovo.sablestopnow.client.gui.lib.TextField field =
                new com.ovo.sablestopnow.client.gui.lib.TextField(
                        String.format(java.util.Locale.ROOT, "%.3f", value));
        field.numeric();
        field.selectAllOnEdit = true;
        field.commit(SketchHud::commitEdit);
        box.add(field);
        // 按钮行 ✓（统一库 ✓ 窄了自动折行 ✓ 文字自动短标签 ✓ ⇒ 不会遮盖 ✓）
        final Container row = new Container();
        row.layout = Layouts.hboxWrap(0, 4, 2);
        row.prefW = com.ovo.sablestopnow.client.gui.lib.Widget.FILL;
        row.prefH = com.ovo.sablestopnow.client.gui.lib.Widget.AUTO;
        row.add(new com.ovo.sablestopnow.client.gui.lib.Button(Component.translatable("model.dim.edit_ok"), () -> field.endEdit(true)));
        row.add(new com.ovo.sablestopnow.client.gui.lib.Button(Component.translatable("model.dim.edit_cancel"), () -> Ui.close(EDIT_LAYER)));
        box.add(row);
        layer.root.add(box);
    }

    /** 提交改值 ✓：解析 ⇒ 改那条已有约束 ⇒ 求解 ⇒ 关层 ✓（= 实时驱动几何 ✓）。 */
    private static void commitEdit(final String text) {
        final int index = editConstraint;
        editConstraint = -1;
        if (index < 0) {
            Ui.close(EDIT_LAYER);
            return;
        }
        final double value;
        try {
            value = Double.parseDouble(text.trim());
        } catch (final NumberFormatException bad) {
            return; // 解析不了就留在框里让玩家改 ✓ 不关层 ✗
        }
        com.ovo.sablestopnow.client.SketchClient.applyDimensionValue(index, value);
        Ui.close(EDIT_LAYER);
    }

    /** 命中测试：屏幕坐标附近的尺寸标签 ✓（{@code tol} 是像素容差 ✓）。 */
    @Nullable
    public static LabelHit pickLabel(final double mx, final double my, final double tol) {
        LabelHit best = null;
        double bestD = tol * tol;
        for (final LabelHit h : LABEL_HITS) {
            final double dx = h.x() - mx;
            final double dy = h.y() - my;
            final double d = dx * dx + dy * dy;
            if (d <= bestD) {
                bestD = d;
                best = h;
            }
        }
        return best;
    }

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
        // 智能尺寸的**实时预览**（半透明 ✓ 跟着指针 ✓ 自动捕捉方位 ✓ 锁定态画环 ✓）
        drawSmartPreview(event.getGuiGraphics(), mc);
        // 吸附到网格时**把那条格线画出来**（虚线 ✓ 用户要求 ✓）
        drawSnapOverlay(event.getGuiGraphics(), mc);
        // 图元旁的**约束小图标** ✓（SolidWorks 的几何关系图标 ✓ 用户要求 ✓）
        drawConstraintGlyphs(event.getGuiGraphics(), mc);
        // 辅助线用**虚线**画一遍 ✓（SolidWorks 的构造几何就是虚线 ✓ 创建方式与直线一致 ✓）
        // 不参与轮廓/拉伸 ✓（SketchProfiles ×2 + Extrude ×1 已跳过 ✓）
        drawConstructionOverlay(event.getGuiGraphics(), mc);

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
        LABEL_HITS.clear(); // 每帧重建 ✓ 相机一动就跟着更新 ⇒ 不会点到旧位置 ✗
        // 参考尺寸先画（在下面），已有尺寸后画（盖在上面）—— 已有的是"更硬"的信息
        final java.util.List<com.ovo.sablestopnow.sketch.SketchMeasure.Dimension> all =
                new java.util.ArrayList<>();
        all.addAll(com.ovo.sablestopnow.sketch.SketchMeasure.referenceDimensions(working));
        all.addAll(com.ovo.sablestopnow.sketch.SketchMeasure.existingDimensions(working));
        for (final var d : all) {
            final double[] luv = labelUv(d); // ★ 画与命中共用这个位置 ✓（拖过就用拖到的 ✓）
            final org.joml.Vector3d local =
                    working.plane().toLocal(luv[0], luv[1]);
            final org.joml.Vector3d world = sub.renderPose().transformPosition(local, tmp);
            final double[] screen = com.ovo.sablestopnow.client.model.ModelCamera
                    .projectToScreen(new net.minecraft.world.phys.Vec3(world.x, world.y, world.z), mc);
            if (screen == null || screen.length < 3 || screen[2] <= 0.0) {
                continue; // 在相机背后 / 投影失败 → 不画（否则文字会在屏幕上乱飘）
            }
            // ---- 尺寸线（SolidWorks 的画法：线**穿过标签**，两端有界线与箭头 ✓）----
            // 参考点按需推导 ✓（推导不出就只画文字 ✓ 宁可少画不画错 ✓）
            // ★ 参考图 3/4 ✓：尺寸线**放不下文字**时 ⇒ 文字移到线**外侧** ✓（默认沿线的方向 ✓）
            boolean shortLine = false;
            double lineDirX = 1.0;
            double lineDirY = 0.0;

            final double[] ends = com.ovo.sablestopnow.sketch.SketchMeasure
                    .endpointsOf(working, d);
            if (ends != null) {
                final double[] sa = project(working, sub, ends[0], ends[1], mc, tmp);
                final double[] sb = project(working, sub, ends[2], ends[3], mc, tmp);
                if (sa != null && sb != null) {
                    // 尺寸线 = 参考点连线**平移到标签所在的那一侧** ✓（于是标签正好落在线中 ✓）
                    final double mx = (sa[0] + sb[0]) * 0.5;
                    final double my = (sa[1] + sb[1]) * 0.5;
                    final double ox = screen[0] - mx;
                    final double oy = screen[1] - my;
                    final double ax = sa[0] + ox;
                    final double ay = sa[1] + oy;
                    final double bx = sb[0] + ox;
                    final double by = sb[1] + oy;
                    final int lineArgb = d.driving() ? 0xCCE6E6E6 : 0x998C93A8;
                    // 界线：从参考点拉到尺寸线两端（留一点缝，SW 也这样 ✓）
                    // ★ 界线按参考图画 ✓：与几何**留小缝**（4px ✓）· **超出**尺寸线一小段（6px ✓）
                    //   （参考图 1/3/4 都明显超出 ✓ 我原来"贴到尺寸线就停"✗）
                    //   ⚠ 线线距离**不画界线** ✓（参考图 2 ✓ 两条线本身就是基准 ✓ 画了反而乱 ✗）
                    final boolean skipExt = d.labelKey() != null && d.labelKey().contains("line_line");
                    if (!skipExt) {
                        extLine(g, sa[0], sa[1], ax, ay, lineArgb);
                        extLine(g, sb[0], sb[1], bx, by, lineArgb);
                    }
                    // 尺寸线本体 + 两端箭头（用统一库的矢量绘制 ✓ 不手写 fill ✗）
                    // ★ 箭头改成**开放式 V 形** ✓（参考图 1/2/3/4 全是这种 ✓ 我原来画的是实心圆点 ✗）
                    dimArrow(g, ax, ay, bx, by, lineArgb, true);
                    dimArrow(g, bx, by, ax, ay, lineArgb, true);
                    // ★ 尺寸线**在文字处断开** ✓（参考图 1 最清楚：左段 + 文字 + 右段 ✓ 我原来线穿过文字 ✗）
                    //   做法 ✓：量出文字半宽 ⇒ 把尺寸线画成两段 ✓ 中间留白给文字 ✓
                    final double segLen = Math.hypot(bx - ax, by - ay);
                    if (segLen > 44.0) { // ≈ 数字文字宽（"5.000" 约 30px ✓）× 1.4 ⇒ 能放下才断开 ✓
                        final double ux = (bx - ax) / segLen;
                        final double uy = (by - ay) / segLen;
                        final double mx2 = (ax + bx) * 0.5;
                        final double my2 = (ay + by) * 0.5;
                        // 用文字实际宽度决定断口 ✓（按标签在线的哪一侧不影响断口 ✓）
                        final double half = 19.0; // 三位小数（如 "5.000"）的半个文字宽 + 余量 ✓（此处 text 还没声明 ✓ 所以用等价常量 ✓）
                        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, ax, ay,
                                mx2 - ux * half, my2 - uy * half, 1.5f, lineArgb);
                        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g,
                                mx2 + ux * half, my2 + uy * half, bx, by, 1.5f, lineArgb);
                    } else {
                        // 空间太窄 ⇒ 整条画出来 ✓（文字会落在旁边 ✓ 与参考图 3/4 的"外侧文字"一致 ✓）
                        shortLine = true;
                        if (segLen > 1.0e-6) { lineDirX = (bx - ax) / segLen; lineDirY = (by - ay) / segLen; }
                        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, ax, ay, bx, by, 1.5f, lineArgb);
                    }
                }
            }
            // 从动/参考尺寸 ⇒ **灰 + 括号** ✓（SolidWorks 的表示法 ✓ 见任务 (5) ✓）
            final String number = String.format(java.util.Locale.ROOT, "%.3f", d.value());
            final String text = d.driving() ? number : "(" + number + ")";
            final int argb;
            if (!d.driving()) {
                argb = 0xFF8C93A8;                       // 参考尺寸：灰
            } else if (Math.abs(d.measured() - d.value()) > 1.0e-4) {
                argb = 0xFFFF8A5C;                       // 实测 ≠ 常数：求解器还没算平
            } else {
                argb = 0xFFE6E6E6;                       // 正常
            }
            // 文字实际落点 ✓（画与双击命中共用 ✓ —— 否则"看到的位置点不中"✗）
            final double textX = screen[0] + (shortLine ? lineDirX * 24.0 : 0.0);
            final double textY = screen[1] + (shortLine ? lineDirY * 24.0 : 0.0);
            com.ovo.sablestopnow.client.gui.lib.UiDraw.text(g, mc.font, text,
                    (int) textX, (int) textY, argb, 1.0f, true);
            // 登记屏幕位置 ✓ 供双击命中（一处投影 ✓ 两处使用 ✓）
            LABEL_HITS.add(new LabelHit(d.constraintIndex(), d.driving(), textX, textY));
            shown++;
            if (shown >= 48) {
                break; // 上限：约束很多时别把屏幕糊满，也别把 GUI 批次撑爆
            }
        }
    }

    /**
     * 智能尺寸的**实时预览** ✓（SolidWorks："选中实体后移动指针，尺寸标注实时预览并自动捕捉方位"✓）。
     *
     * <ul>
     *   <li>在**指针旁**画当前候选项的"类型 + 数值"✓ 整体**半透明**✓（预览不该盖住几何 ✓）</li>
     *   <li>方位由 {@code SketchMeasure.preview(...)} 决定 ✓（数据层已按指针相对中点的横/纵/斜向
     *       自动捕捉 平行·水平·竖直 ✓）这里只显示 ✓ 不重复判断 ✗</li>
     *   <li>已**锁定**（右键 ✓ {@code lockedVariant() >= 0}）⇒ 指针旁画一圈**锁定环**✓</li>
     * </ul>
     *
     * <p>文字一律走 {@code UiDraw.text} ✓（不能直接 {@code drawString} —— 会破坏
     * "所有文字都过 GUI 库"这条门禁 ✓ 与上面尺寸标签处保持一致 ✓）。
     */
    private static void drawSmartPreview(final net.minecraft.client.gui.GuiGraphics g,
                                         final Minecraft mc) {
        if (!SketchClient.active() || mc.player == null) {
            return;
        }
        final SketchSession session = SketchClient.mode().session();
        if (session == null || session.tool() != SketchSession.Tool.DIMENSION) {
            return; // 只在智能尺寸工具激活时预览 ✓
        }
        final var working = session.working();
        final var picked = session.selection();
        if (working == null || picked.isEmpty() || picked.size() > 2) {
            return;
        }
        final var shown = com.ovo.sablestopnow.sketch.SketchMeasure.preview(
                working, picked, session.hoverU(), session.hoverV(), session.lockedVariant());
        if (shown == null) {
            return;
        }
        final int px = guiX(mc) + OFFSET_X;
        final int py = guiY(mc) + OFFSET_Y + 12; // 让开吸附提示那一行 ✓ 免得两者叠字 ✗
        final String text = Component.translatable(shown.labelKey()).getString()
                + String.format(java.util.Locale.ROOT, " %.3f", shown.value());
        // ★ 光标旁的**尺寸图标** ✓（SolidWorks：工具激活后光标附加尺寸标注图标 ✓）
        //   画法：一条尺寸线 + 两端箭头 + 两条界线 ✓ = 一眼认得出是"标注"✓ 全用统一库矢量绘制 ✓
        final int iconArgb = 0xCCFFCC55;
        final int ix = px - 16;
        final int iy = py + 4;
        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, ix, iy - 4, ix, iy + 4, 1.0f, iconArgb);
        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, ix + 10, iy - 4, ix + 10, iy + 4,
                1.0f, iconArgb);
        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, ix, iy, ix + 10, iy, 1.0f, iconArgb);
        com.ovo.sablestopnow.client.gui.lib.UiVector.disc(g, ix, iy, 1.5, iconArgb);
        com.ovo.sablestopnow.client.gui.lib.UiVector.disc(g, ix + 10, iy, 1.5, iconArgb);
        final boolean locked = session.lockedVariant() >= 0;
        final int argb = locked ? 0xEEFFCC55 : 0x99FFCC55; // 半透明预览 ✓ 锁定后更实 ✓
        com.ovo.sablestopnow.client.gui.lib.UiDraw.text(g, mc.font, text, px, py, argb, 1.0f, true);
        if (locked) {
            com.ovo.sablestopnow.client.gui.lib.UiVector.disc(g, px - 7, py + 4, 3, 0xEEFFCC55);
        }
    }

    /**
     * 在每个图元旁画它的**约束小图标** ✓（用户要求 ✓ 参考 SolidWorks 的几何关系图标 ✓）。
     *
     * <p>只画**关系**（水平/竖直/平行/垂直/等长/中点/重合/固定 ✓）✗ ——
     * **尺寸**（距离/半径/角度 ✓）已经是"尺寸线 + 数值"了 ✓ 再画图标是重复 ✗。
     *
     * <p>位置 ✓：图元的**代表点**（线取中点 ✓ 点取自身 ✓ 圆/弧取圆心 ✓）投影到屏幕后偏 8px ✓；
     * 同一图元上的多个约束沿 x 方向依次错开 ✓ 免得叠在一起 ✗。
     */
    private static void drawConstraintGlyphs(final net.minecraft.client.gui.GuiGraphics g,
                                             final Minecraft mc) {
        GLYPH_HITS.clear(); // 每帧重建 ✓ 相机一动就更新 ✓
        if (!SketchClient.active() || mc.level == null) {
            return;
        }
        final SketchSession session = SketchClient.mode().session();
        final var work = session == null ? null : session.working();
        if (work == null) {
            return;
        }
        dev.ryanhcode.sable.sublevel.ClientSubLevel sub = null;
        if (dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level)
                instanceof final dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer c) {
            for (final dev.ryanhcode.sable.sublevel.ClientSubLevel s : c.getAllSubLevels()) {
                if (s.getUniqueId().equals(work.body())) {
                    sub = s;
                }
            }
        }
        if (sub == null) {
            return;
        }
        final org.joml.Vector3d tmp = new org.joml.Vector3d();
        final java.util.Map<Integer, Integer> perEntity = new java.util.HashMap<>();
        final java.util.List<com.ovo.sablestopnow.sketch.Sketch.Constraint> cons = work.constraints();
        for (int i = 0; i < cons.size(); i++) {
            final int glyph = glyphOf(cons.get(i).kind());
            if (glyph == 0) {
                continue; // 尺寸类不画图标 ✓
            }
            final int[] ops = cons.get(i).operands();
            if (ops.length == 0) {
                continue;
            }
            final com.ovo.sablestopnow.sketch.Sketch.Entity owner = entityOwning(work, ops[0]);
            if (owner == null) {
                continue;
            }
            final double[] uv = anchorUv(work, owner);
            final double[] screen = project(work, sub, uv[0], uv[1], mc, tmp);
            if (screen == null) {
                continue;
            }
            final int slot = perEntity.merge(anchorKey(owner), 1, Integer::sum) - 1;
            final double gx = screen[0] + 8.0 + slot * 11.0;
            final double gy = screen[1] - 8.0;
            final boolean picked = session.selectedConstraint() == i;
            com.ovo.sablestopnow.client.gui.lib.UiVector.constraintGlyph(g, gx, gy, glyph,
                    picked ? 0xFFFFCC55 : 0xDD7FE3FF);
            if (picked) {
                // 选中态 ✓：套一个圈（用户要求"可选中"✓ 必须看得出选了谁 ✓）
                com.ovo.sablestopnow.client.gui.lib.UiVector.ring(g, gx, gy, 7.0, 1.5, 0xFFFFCC55);
            }
            GLYPH_HITS.add(new GlyphHit(i, glyph, gx, gy));
        }
    }

    /** 求解器约束类型 → 字形常量 ✓；**尺寸类**返回 0（不画图标 ✓）。 */
    private static int glyphOf(final int kind) {
        return switch (kind) {
            case com.ovo.sablestopnow.sketch.SketchSolver.HORIZONTAL ->
                    com.ovo.sablestopnow.client.gui.lib.UiVector.GLYPH_HORIZONTAL;
            case com.ovo.sablestopnow.sketch.SketchSolver.VERTICAL ->
                    com.ovo.sablestopnow.client.gui.lib.UiVector.GLYPH_VERTICAL;
            case com.ovo.sablestopnow.sketch.SketchSolver.PARALLEL ->
                    com.ovo.sablestopnow.client.gui.lib.UiVector.GLYPH_PARALLEL;
            case com.ovo.sablestopnow.sketch.SketchSolver.PERPENDICULAR ->
                    com.ovo.sablestopnow.client.gui.lib.UiVector.GLYPH_PERPENDICULAR;
            case com.ovo.sablestopnow.sketch.SketchSolver.EQUAL_LEN ->
                    com.ovo.sablestopnow.client.gui.lib.UiVector.GLYPH_EQUAL;
            case com.ovo.sablestopnow.sketch.SketchSolver.MIDPOINT ->
                    com.ovo.sablestopnow.client.gui.lib.UiVector.GLYPH_MIDPOINT;
            case com.ovo.sablestopnow.sketch.SketchSolver.COINCIDENT ->
                    com.ovo.sablestopnow.client.gui.lib.UiVector.GLYPH_COINCIDENT;
            case com.ovo.sablestopnow.sketch.SketchSolver.FIXED ->
                    com.ovo.sablestopnow.client.gui.lib.UiVector.GLYPH_FIXED;
            default -> 0;
        };
    }

    /** 哪个图元拥有这个变量下标 ✓（扫一遍就好 ✓ 图元数量很小 ✓）。 */
    @Nullable
    private static com.ovo.sablestopnow.sketch.Sketch.Entity entityOwning(
            final com.ovo.sablestopnow.sketch.Sketch work, final int varIndex) {
        for (final com.ovo.sablestopnow.sketch.Sketch.Entity e : work.entities()) {
            for (final int v : e.vars()) {
                if (v == varIndex) {
                    return e;
                }
            }
        }
        return null;
    }

    /** 图元的代表点（平面 uv ✓）：线取中点 ✓ 点取自身 ✓ 圆/弧取圆心 ✓。 */
    private static double[] anchorUv(final com.ovo.sablestopnow.sketch.Sketch work,
                                     final com.ovo.sablestopnow.sketch.Sketch.Entity e) {
        final int[] v = e.vars();
        return switch (e.kind()) {
            case LINE -> new double[] {
                    (work.variable(v[0]) + work.variable(v[2])) * 0.5,
                    (work.variable(v[1]) + work.variable(v[3])) * 0.5};
            default -> new double[] {work.variable(v[0]), work.variable(v[1])};
        };
    }

    /** 用来给同一图元上的多个字形排序（图元身份 ✓ 用首变量下标 ✓）。 */
    private static int anchorKey(final com.ovo.sablestopnow.sketch.Sketch.Entity e) {
        return e.vars()[0];
    }
    /**
     * 把**吸附到的那条格线**画出来 ✓（虚线 ✓ 用户要求 "吸附提示用虚线" ✓）。
     *
     * <p>只画"穿过吸附点、沿被吸附的那一轴"的一段 ✓（不是无限长 ✓）——
     * 长度取当前子结构在该轴上的尺寸 ✓ 够用且不会画到世界外面去 ✓。
     */
    private static void drawSnapOverlay(final net.minecraft.client.gui.GuiGraphics g,
                                        final Minecraft mc) {
        if (!SketchClient.active() || mc.level == null) {
            return;
        }
        final SketchSession session = SketchClient.mode().session();
        final SketchSession.KeyPoint kp = SketchClient.mode().snapAt();
        if (session == null || kp == null || kp.kind() != SketchSession.KeyKind.GRID) {
            return;
        }
        final var working = session.working();
        if (working == null) {
            return;
        }
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
        final boolean uInt = Math.abs(kp.u() - Math.round(kp.u())) < 1.0e-6;
        final boolean vInt = Math.abs(kp.v() - Math.round(kp.v())) < 1.0e-6;
        final double half = 32.0; // 半长（方块 ✓）
        final org.joml.Vector3d tmp = new org.joml.Vector3d();
        final int argb = 0xCC66E0FF;
        if (uInt && !vInt) {
            drawDashBetween(g, working, sub, mc, tmp, kp.u(), kp.v() - half, kp.u(), kp.v() + half, argb);
        } else if (!uInt && vInt) {
            drawDashBetween(g, working, sub, mc, tmp,
                    kp.u() - half, kp.v(), kp.u() + half, kp.v(), argb);
        }
        // 格点：不画线 ✓（点本身就是提示 ✓ 提示文字里也写了坐标 ✓）
    }

    /**
     * 把**辅助线**用虚线描一遍 ✓（用户要求 ✓）。
     *
     * <p>为什么画在**屏幕空间**而不是世界空间 ✗✓：世界那边（SketchRenderer）画的是实线 ✓
     * 改它要动顶点流 ✓ 而这层虚线只是"标注这是参考几何"的视觉语言 ✓ 屏幕空间足够 ✓
     * 且能直接复用同一套投影（一处投影、两处使用 ✓ 不会"看到的和世界里的对不上"✗）。
     */
    private static void drawConstructionOverlay(final net.minecraft.client.gui.GuiGraphics g,
                                                final Minecraft mc) {
        if (!SketchClient.active() || mc.level == null) {
            return;
        }
        final SketchSession session = SketchClient.mode().session();
        final var work = session == null ? null : session.working();
        if (work == null) {
            return;
        }
        final var list = work.constructionEntities();
        if (list.isEmpty()) {
            return;
        }
        dev.ryanhcode.sable.sublevel.ClientSubLevel sub = null;
        if (dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level)
                instanceof final dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer c) {
            for (final dev.ryanhcode.sable.sublevel.ClientSubLevel s : c.getAllSubLevels()) {
                if (s.getUniqueId().equals(work.body())) {
                    sub = s;
                }
            }
        }
        if (sub == null) {
            return;
        }
        final org.joml.Vector3d tmp = new org.joml.Vector3d();
        for (final var e : list) {
            final int[] v = e.vars();
            final int argb = 0xCC8C93A8; // 暗灰 ✓ 与"从动/参考"同一语义色 ✓
            switch (e.kind()) {
                case LINE -> drawDashBetween(g, work, sub, mc, tmp,
                        work.variable(v[0]), work.variable(v[1]),
                        work.variable(v[2]), work.variable(v[3]), argb);
                case CIRCLE -> {
                    // 圆：用 24 段折线描一圈虚线 ✓（够圆也不重 ✓）
                    final double cx = work.variable(v[0]);
                    final double cy = work.variable(v[1]);
                    final double r = Math.abs(work.variable(v[2]));
                    for (int i = 0; i < 24; i++) {
                        final double a0 = i * Math.PI * 2.0 / 24.0;
                        final double a1 = (i + 1) * Math.PI * 2.0 / 24.0;
                        if (i % 2 == 0) {
                            drawDashBetween(g, work, sub, mc, tmp,
                                    cx + Math.cos(a0) * r, cy + Math.sin(a0) * r,
                                    cx + Math.cos(a1) * r, cy + Math.sin(a1) * r, argb);
                        }
                    }
                }
                default -> {
                    // 点/弧暂不描 ✓（点没有"线"可画 ✓ 弧的虚线等有需要再加 ✓ 宁可少画不画错 ✓）
                }
            }
        }
    }

    /** 把平面上的两点投影后连成**虚线** ✓（投影失败就不画 ✓）。 */
    private static void drawDashBetween(final net.minecraft.client.gui.GuiGraphics g,
                                        final com.ovo.sablestopnow.sketch.Sketch sketch,
                                        final dev.ryanhcode.sable.sublevel.ClientSubLevel sub,
                                        final Minecraft mc, final org.joml.Vector3d tmp,
                                        final double u0, final double v0, final double u1,
                                        final double v1, final int argb) {
        final double[] a = project(sketch, sub, u0, v0, mc, tmp);
        final double[] b = project(sketch, sub, u1, v1, mc, tmp);
        if (a == null || b == null) {
            return;
        }
        com.ovo.sablestopnow.client.gui.lib.UiVector.dashedLine(
                g, a[0], a[1], b[0], b[1], 1.5f, argb, 6.0, 4.0);
    }

    /** 提示文本的活取值。没有吸附目标时给空串 —— 反正此时 alpha 已经把它隐掉了。 */
    private static Component snapText() {
        if (!SketchClient.active()) {
            return Component.empty();
        }
        final SketchSession.KeyPoint kp = SketchClient.mode().snapAt();
        if (kp == null) {
            return Component.empty();
        }
        // ★ 网格吸附要**说明吸到了哪条线** ✓（用户反馈 1 ✓）
        //   判据 ✓（由吸附结果的坐标反推 ✓ 不需要额外状态 ✓）：
        //     两个坐标都是整数 ⇒ 吸到**格点** ✓
        //     只有 u 是整数   ⇒ 吸到**竖向格线** u = k ✓（另一轴自由 ⇒ 可沿线滑动 ✓）
        //     否则            ⇒ 吸到**横向格线** v = k ✓
        if (kp.kind() == SketchSession.KeyKind.GRID) {
            final boolean uInt = Math.abs(kp.u() - Math.round(kp.u())) < 1.0e-6;
            final boolean vInt = Math.abs(kp.v() - Math.round(kp.v())) < 1.0e-6;
            if (uInt && vInt) {
                return Component.translatable("model.sketch.snap.grid_point",
                        String.format(java.util.Locale.ROOT, "(%d, %d)",
                                Math.round(kp.u()), Math.round(kp.v())));
            }
            if (uInt) {
                return Component.translatable("model.sketch.snap.grid_line_u",
                        String.format(java.util.Locale.ROOT, "%d", Math.round(kp.u())));
            }
            return Component.translatable("model.sketch.snap.grid_line_v",
                    String.format(java.util.Locale.ROOT, "%d", Math.round(kp.v())));
        }
        return Component.translatable("model.sketch.snap.hint",
                Component.translatable(kp.kind().labelKey()));
    }

    /** 平面 uv → 屏幕（相机背后返回 null ✓ 不画）。与上面循环里用的是同一条链路 ✓。 */
    private static double[] project(final com.ovo.sablestopnow.sketch.Sketch sketch,
                                    final dev.ryanhcode.sable.sublevel.ClientSubLevel sub,
                                    final double u, final double v, final Minecraft mc,
                                    final org.joml.Vector3d tmp) {
        final org.joml.Vector3d local = sketch.plane().toLocal(u, v);
        final org.joml.Vector3d world = sub.renderPose().transformPosition(local, tmp);
        final double[] screen = com.ovo.sablestopnow.client.model.ModelCamera
                .projectToScreen(new net.minecraft.world.phys.Vec3(world.x, world.y, world.z), mc);
        if (screen == null || screen.length < 3 || screen[2] <= 0.0) {
            return null;
        }
        return screen;
    }
    private static int guiX(final Minecraft mc) {
        return (int) (mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth()
                / Math.max(1, mc.getWindow().getWidth()));
    }

    private static int guiY(final Minecraft mc) {
        return (int) (mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight()
                / Math.max(1, mc.getWindow().getHeight()));
    }

    /**
     * 画一个**开放式箭头** ✓（参考图里的样式：两条短线成 V 形 ✓ 不是实心圆点 ✗）。
     *
     * @param tipX/tipY 箭尖 ✓ @param fromX/fromY 尺寸线的另一端（用来定方向 ✓）
     */
    /**
     * 画一条**界线** ✓（SolidWorks 样式：起点离几何留 4px 缝 ✓ 终点超出尺寸线 6px ✓）。
     */
    private static void extLine(final net.minecraft.client.gui.GuiGraphics g,
                                final double gx, final double gy,
                                final double dx2, final double dy2, final int argb) {
        final double dx = dx2 - gx;
        final double dy = dy2 - gy;
        final double len = Math.hypot(dx, dy);
        if (len < 1.0e-6) {
            return;
        }
        final double ux = dx / len;
        final double uy = dy / len;
        final double gap = 4.0;    // 与几何留缝 ✓
        final double over = 6.0;   // 超出尺寸线 ✓
        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g,
                gx + ux * gap, gy + uy * gap,
                dx2 + ux * over, dy2 + uy * over, 1.0f, argb);
    }

    private static void dimArrow(final net.minecraft.client.gui.GuiGraphics g,
                                 final double tipX, final double tipY,
                                 final double fromX, final double fromY,
                                 final int argb, final boolean unusedFilled) {
        final double dx = tipX - fromX;
        final double dy = tipY - fromY;
        final double len = Math.hypot(dx, dy);
        if (len < 1.0e-6) {
            return;
        }
        final double ux = dx / len;
        final double uy = dy / len;
        final double size = 7.0;   // 箭羽长度 ✓（参考图约 6~8px ✓）
        final double spread = 0.42; // 张角 ✓
        final double px = -uy;
        final double py = ux;
        final double bx1 = tipX - ux * size + px * size * spread;
        final double by1 = tipY - uy * size + py * size * spread;
        final double bx2 = tipX - ux * size - px * size * spread;
        final double by2 = tipY - uy * size - py * size * spread;
        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, tipX, tipY, bx1, by1, 1.4f, argb);
        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, tipX, tipY, bx2, by2, 1.4f, argb);
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(Math.max(min, max), value));
    }
}

