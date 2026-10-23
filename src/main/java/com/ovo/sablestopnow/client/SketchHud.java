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

    /** 尺寸绘制诊断节流 ✓（每秒最多一行 ✓ 用来定位"尺寸画不出来"✗）。 */
    private static long dimDrawDiagMs;
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

    /** 当前改值框对应的尺寸类型键 ✓（用来把"直径"的输入换算回半径 ✓）。 */
    @Nullable
    private static String editLabelKey;

    /** 打开改值浮层 ✓（由 ModelInput 双击命中后调用 ✓）。 */
    /** 关掉改值浮层 ✓（退出草图 / 取消草图时必须调用 ✓ 否则输入会被我们自己挡住 ✓）。 */
    public static void closeEdit() {
    // ★ 诊断 ✓：万一"手杖逻辑又被航空学覆盖"✗ ⇒ 这行会告诉我们是不是有层没关干净 ✓
    com.ovo.sablestopnow.SablestopNow.LOGGER.info(
            "[sketch] closeEdit: anyOpen={} blocksWorldInput={} top={}",
            com.ovo.sablestopnow.client.gui.lib.Ui.anyOpen(),
            com.ovo.sablestopnow.client.gui.lib.Ui.blocksWorldInput(),
            com.ovo.sablestopnow.client.gui.lib.Ui.top() == null ? "none"
                    : com.ovo.sablestopnow.client.gui.lib.Ui.top().name);
        Ui.close(EDIT_LAYER);
        editConstraint = -1;
    }

    public static void openEdit(final int constraintIndex, final String labelKey,
                                final double rawValue) {
        Ui.close(EDIT_LAYER); // 先关旧的 ✓ 重建最省心 ✓（不残留上一次的状态 ✗）
        editConstraint = constraintIndex;
        editLabelKey = labelKey; // ★ 记住类型键 ✓（"直径"的输入要换算回半径 ✓）
        // ★ 修实测"modify 框和显示的角度不一致" ✓✓
        //   画面上角度已经取绝对值显示了（0…180 ✓），但改值框预填的还是**原始有向值**（可能是负数）✗
        //   ⇒ 在这里统一取绝对值 ✓（所有调用点一次覆盖 ✓ 与标签口径一致 ✓）
        final double value = (labelKey != null && labelKey.contains("angle"))
                ? Math.abs(rawValue) : rawValue;
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
        // ★ 修"改值框跑到屏幕左上角"✗：图层根**必须先有屏幕尺寸** ✓
        //   之前 clamp 的上界用 layer.root.w/h ✗ 而它们恒为 0 ⇒ 坐标被夹到 (6,6) ⇒ 左上角 ✗✓
        layer.size(net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledWidth(),
                net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight());
        layer.root.layout = Layouts.absolute();
        // ★★ 真因 ✓✓：`Layouts.absolute()` **不读子控件的 x/y** ✗ —— 它把每个孩子直接按
        //   "父内容区原点 + 外边距" 摆放：
        //       child.x = parent.innerX() + child.marginLeft;
        //       child.y = parent.innerY() + child.marginTop;
        //   ⇒ 我原来写 box.x/box.y **完全无效** ✗，浮层就落在图层左上角（= 屏幕左上角 ✓）
        //     —— 这正是用户两次反馈"改值框跑到左上角"的原因 ✓（上次我误判成"图层没有尺寸"✗）
        //   ⇒ 正确做法：把位置写进 **marginLeft / marginTop** ✓（absolute() 唯一认的字段 ✓）。
        box.marginLeft = clamp((int) com.ovo.sablestopnow.client.gui.lib.Ui.mouseXd() + 14, 6,
                Math.max(6, layer.root.w - box.minW - 6));
        box.marginTop = clamp((int) com.ovo.sablestopnow.client.gui.lib.Ui.mouseYd() + 10, 6,
                Math.max(6, layer.root.h - box.minH - 6));
        // 保留 x/y 赋值无害（别的布局可能用得到 ✓），但位置以 margin 为准 ✓
        box.x = box.marginLeft;
        box.y = box.marginTop;
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
        // ★ 需求 5：**确定类按键悬停底色 = 草绿** ✓（"确定和删除键…确定改草绿，删除/取消改红色" ✓）
        final var okBtn = new com.ovo.sablestopnow.client.gui.lib.Button(
                Component.translatable("model.dim.edit_ok"), () -> field.endEdit(true));
        okBtn.sweepColor = 0xFF6B8E23; // 点击扫光 = 草绿 ✓（用户要的是**点击后刷过去**的颜色）
        row.add(okBtn);
        // ★ 用户要求 ✓：第二个键从「取消」改成「**删除**」——
        //   删除 = 删掉这个尺寸及其约束（几何留在原地 ✓ 只是重新变欠约束 ✓ 与 SolidWorks 一致 ✓）
        final var delBtn = new com.ovo.sablestopnow.client.gui.lib.Button(
                Component.translatable("model.dim.edit_delete"), () -> {
                    final int idx = editConstraint;
                    editConstraint = -1;
                    editLabelKey = null;
                    if (idx >= 0) {
                        com.ovo.sablestopnow.client.SketchClient.deleteDimension(idx);
                    }
                    Ui.close(EDIT_LAYER);
                });
        delBtn.sweepColor = 0xFFB03030; // 点击扫光 = 红 ✓
        row.add(delBtn);
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
        // ★ 「直径」的输入是**显示值(2r)** ✓ ⇒ 写约束前必须换算回**半径(r)** ✓
        //   否则会把 2r 当半径 ⇒ 圆弧若带重合约束就**无解** ✗（用户实测："直径始终无法求解"✓）
        final double solverValue = editLabelKey != null && editLabelKey.contains("diameter")
                ? value * 0.5 : value;
        editLabelKey = null;
        com.ovo.sablestopnow.client.SketchClient.applyDimensionValue(index, solverValue);
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
            Ui.close(EDIT_LAYER); // ★ 草图不在时必须关掉**改值浮层** ✓
            //   为什么关键 ✗✓：该层 consumeInput=true ⇒ 不关的话我们自己的输入处理会以为"有界面开着"
            //   ⇒ 主动让位给航空学（手杖逻辑被覆盖 ✗✓）而原版 Minecraft 不认这个层 ⇒ 滚轮照样切物品栏 ✓
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
        // ★ 需求 (4)：左栏候选按钮随**指针预览**实时联动 ✓（只改 toggled ✓ 不重建 ✓）
        com.ovo.sablestopnow.client.model.ModelUi.syncSmartCandidateToggles();
        // ★ 草图 → 树 的高亮同步 ✓（用户要求"草图和右侧树状图联动" ✓ 反方向 ✓）
        com.ovo.sablestopnow.client.model.ModelUi.syncSketchTreeHighlight();
        // ★ 需求 1：悬停在图元上时，鼠标**右上方**小字显示它的名称+编号（如"直线3"✓）
        drawHoverName(event.getGuiGraphics(), mc);
        // ★ 需求 ⑥：**原点 + 坐标轴箭头**（SolidWorks 风格 ✓ 常驻显示 ✓）
        drawOriginAxes(event.getGuiGraphics(), mc);
        // ★ 诊断 ✓：把"上次吸附→约束的判定"打出来（只在变化时打 ✓ 不刷屏 ✓）
        //   用户实测"圆弧端点固定仍未做"✗ ⇒ 这行会告诉我们：有没有吸到弧端点、px/py 是不是有效变量 ✓
        final String snapDiag = SketchSession.lastSnapDiag;
        if (!snapDiag.equals(lastSnapDiagLogged)) {
            lastSnapDiagLogged = snapDiag;
            com.ovo.sablestopnow.SablestopNow.LOGGER.info("[sketch-snap] {}", snapDiag);
        }
        // ★ 画线落点诊断 ✓（实测"短直线变零长"✗）：打出原始两点与长度 ✓
        final String lineDiag = SketchSession.lastLineDiag;
        if (!lineDiag.equals(lastLineDiagLogged)) {
            lastLineDiagLogged = lineDiag;
            com.ovo.sablestopnow.SablestopNow.LOGGER.info("[sketch-line] {}", lineDiag);
        }
        // ★ 画弧诊断 ✓（实测"劣弧方向不对"✗）：三次点击 + 角度差 + 判定出的 cw ✓
        final String arcDiag = SketchSession.lastArcDiag;
        if (!arcDiag.equals(lastArcDiagLogged)) {
            lastArcDiagLogged = arcDiag;
            com.ovo.sablestopnow.SablestopNow.LOGGER.info("[sketch-arc] {}", arcDiag);
        }
        // ★ 贝塞尔诊断 ✓（实测"预览正常、落下变线段"✗）
        final String bezDiag = SketchSession.lastBezierDiag;
        if (!bezDiag.equals(lastBezierDiagLogged)) {
            lastBezierDiagLogged = bezDiag;
            com.ovo.sablestopnow.SablestopNow.LOGGER.info("[sketch-bezier] {}", bezDiag);
        }
        // 辅助线用**虚线**画一遍 ✓（SolidWorks 的构造几何就是虚线 ✓ 创建方式与直线一致 ✓）
        // 不参与轮廓/拉伸 ✓（SketchProfiles ×2 + Extrude ×1 已跳过 ✓）
        // ★ 需求 ①：辅助线的虚线改在**世界渲染层**画（`SketchRenderer.drawConstruction`）✓
        //   ⇒ 这里**不再叠一层 HUD 虚线** ✗ —— 两层叠在同一位置会互相补齐缝隙，
        //     看起来反而又变成实线 ✓（这正是用户实测"仍然没有画成虚线"的原因之一 ✓）
        // drawConstructionOverlay(event.getGuiGraphics(), mc);

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
        // ★ 诊断 ✓：一次分清"列表为空"✗ / "子结构为空"✗ / "投影失败"✗（三者处置完全不同 ✓）
        final long nowMs = System.currentTimeMillis();
        if (nowMs - dimDrawDiagMs >= 1000L) {
            dimDrawDiagMs = nowMs;
            com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                    "[sketch-dim-draw] list={} sub=ok firstAnchor=({},{})",
                    all.size(),
                    all.isEmpty() ? 0.0 : all.get(0).anchorU(),
                    all.isEmpty() ? 0.0 : all.get(0).anchorV());
        }
        for (final var d : all) {
            final double[] luv = labelUv(d); // ★ 画与命中共用这个位置 ✓（拖过就用拖到的 ✓）
            final org.joml.Vector3d local =
                    working.plane().toLocal(luv[0], luv[1]);
            final org.joml.Vector3d world = sub.renderPose().transformPosition(local, tmp);
            final double[] screen = com.ovo.sablestopnow.client.model.ModelCamera
                    .projectToScreen(new net.minecraft.world.phys.Vec3(world.x, world.y, world.z), mc);
            if (screen == null || screen.length < 2) { // ★ 只要 2 个分量 ✓（projectToScreen 返回 {x,y} ✗ 无深度 ✓；
                continue; // 在相机背后 / 投影失败 → 不画（否则文字会在屏幕上乱飘）
            }
            // ★ 需求 ⑤：**角度尺寸走独立画法** ✓（用户：不能和线性尺寸一样 ✓）
            //   依据 SolidWorks（docs/智能尺寸-design.md §6.3 ✓）：
            //   尺寸线是**圆弧**（圆心 = 两线交点 ✓）、箭头沿弧**切向** ✓、
            //   **尺寸界线就是构成角度的两个图元本身** ✓。
            //   ⚠ 操作数要从**约束**取（`Dimension` 记录里没有 vars ✗ 只有 constraintIndex ✓）
            // ★ 需求 ⑤：**角度尺寸走独立画法** ✓（用户：不能与线性尺寸同一套 ✓）
            //
            // ⚠ 踩坑记录 ✗✓：这里最初用了 `lineArgb`，但它在本行**之后**才定义 ⇒
            //   javac 报的是"找不到符号"，读起来像"方法不存在"，很容易被带偏 ✗
            //   ⇒ 颜色**就地算**（按驱动/从动 ✓ 与设计文档的配色表一致 ✓）。
            if (d.labelKey() != null && d.labelKey().contains("angle")
                    && d.constraintIndex() >= 0
                    && d.constraintIndex() < working.constraints().size()) {
                final int[] av = working.constraints().get(d.constraintIndex()).operands();
                final int angArgb = d.driving() ? 0xFFE6E6E6 : 0xFF8C93A8;
                drawAngularDimension(g, working, sub, mc, tmp, av, screen, angArgb,
                        d.value(), d.constraintIndex(), d.driving());
                continue; // 不再走下面那套线性画法 ✓
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
                    // ★ 与参考图 1/2 一致 ✓：尺寸线与文字要**偏在实体外侧** ✓
                    //   原来标签取图元中点 ✗ ⇒ 尺寸线正好压在实体线上 ⇒ 数字盖住线、箭头贴着线 ✗
                    //   做法 ✓：沿**垂直于尺寸方向**偏 18px ✓（screen 被下面的平移数学复用 ✓
                    //   所以文字与尺寸线会**一起**偏 ✓；双击命中也走 textX/textY ✓ 三者始终一致 ✓）
                    // ★ 需求 ③：标签偏移方向**随尺寸类型** ✓
                    //   · 距离（对齐）⇒ 垂直于参考连线（原样式 ✓）
                    //   · 水平尺寸 ⇒ **竖直**方向偏移（尺寸线水平 ✓ 界线竖直 ✓）
                    //   · 竖直尺寸 ⇒ **水平**方向偏移（尺寸线竖直 ✓ 界线水平 ✓）
                    final String lkType = d.labelKey() == null ? "" : d.labelKey();
                    final boolean dimHoriz = lkType.contains("horizontal");
                    final boolean dimVert = lkType.contains("vertical");
                    double offX;
                    double offY;
                    if (dimHoriz) {
                        offX = 0.0;
                        offY = -1.0;
                    } else if (dimVert) {
                        offX = -1.0;
                        offY = 0.0;
                    } else {
                        final double px = sb[1] - sa[1];
                        final double py = -(sb[0] - sa[0]);
                        final double pl = Math.hypot(px, py);
                        offX = pl < 1.0e-6 ? 0.0 : px / pl;
                        offY = pl < 1.0e-6 ? -1.0 : py / pl;
                    }
                    // ⚠ 只对"**没被手动拖过**"的标签生效 ✓：拖过的以玩家拖到的位置为准 ✓（否则会再偏 18px ✗）
                    if (!LABEL_OVERRIDE.containsKey(d.constraintIndex())) {
                        screen[0] += offX * 18.0;
                        screen[1] += offY * 18.0;
                    }
                    final double mx = (sa[0] + sb[0]) * 0.5;
                    final double my = (sa[1] + sb[1]) * 0.5;
                    final double ox = screen[0] - mx;
                    final double oy = screen[1] - my;
                    // ★ 需求 ③：尺寸线的**方向**随类型 ✓
                    //   距离 ⇒ 沿参考点连线平移（原样式 ✓）
                    //   水平 ⇒ **水平**线（y 取标签所在的那条横线 ✓）⇒ 界线自然竖直 ✓
                    //   竖直 ⇒ **竖直**线（x 取标签所在的那条竖线 ✓）⇒ 界线自然水平 ✓
                    final double ax;
                    final double ay;
                    final double bx;
                    final double by;
                    if (dimHoriz) {
                        ax = sa[0];
                        ay = screen[1];
                        bx = sb[0];
                        by = screen[1];
                    } else if (dimVert) {
                        ax = screen[0];
                        ay = sa[1];
                        bx = screen[0];
                        by = sb[1];
                    } else {
                        ax = sa[0] + ox;
                        ay = sa[1] + oy;
                        bx = sb[0] + ox;
                        by = sb[1] + oy;
                    }
                    final int lineArgb = d.driving() ? 0xCCE6E6E6 : 0x998C93A8;
                    // 界线：从参考点拉到尺寸线两端（留一点缝，SW 也这样 ✓）
                    // ★ 界线按参考图画 ✓：与几何**留小缝**（4px ✓）· **超出**尺寸线一小段（6px ✓）
                    //   （参考图 1/3/4 都明显超出 ✓ 我原来"贴到尺寸线就停"✗）
                    //   ⚠ 线线距离**不画界线** ✓（参考图 2 ✓ 两条线本身就是基准 ✓ 画了反而乱 ✗）
                    // ★ 需求 ③ 的圆弧专门情形 ✓（用户："标圆弧直径时界线没有和圆弧相切" ✗）
                    //   SolidWorks 的直径/半径尺寸是**尺寸线穿过圆心、箭头直接落在圆周上** ✓
                    //   ⇒ **不画界线** ✓（画了反而与圆周斜交 ⇒ 就是用户看到的问题 ✓）
                    //   同理线线距离也不画 ✓（两条线自身即基准 ✓ 参考图 2 ✓）
                    // ★ 用户实测反馈："圆和圆弧的尺寸的界线没有显示" ✗ ——
                    //   我上一轮把半径/直径的界线也一起关掉了 ✗ ⇒ 现在**只对线线距离**不画 ✓
                    //   （线线距离两条线本身就是基准 ✓ 参考图 2 ✓）
                    final String lk = d.labelKey() == null ? "" : d.labelKey();
                    final boolean skipExt = lk.contains("line_line");
                    if (!skipExt) {
                        // ★ 需求 ③：界线必须**垂直于图元**（用户："界线没有垂直于图元"✗）
                        //   原实现是"从测量点拉到尺寸线的**端点**"✗ ⇒ 斜线 ⇒ 看着不垂直 ✓
                        //   ⇒ 正确做法：把测量点**垂直投影**到尺寸线上，从测量点引到投影点 ✓
                        //     几何端留 4px 缝 ✓ 尺寸线端超出 6px ✓（与预览、参考图同一套规则 ✓）
                        final double dlx = bx - ax;
                        final double dly = by - ay;
                        final double dlen2 = dlx * dlx + dly * dly;
                        if (dlen2 > 1.0e-6) {
                            for (final double[] pt : new double[][] {sa, sb}) {
                                final double t = ((pt[0] - ax) * dlx + (pt[1] - ay) * dly) / dlen2;
                                final double fx = ax + t * dlx;
                                final double fy = ay + t * dly;
                                final double vx = fx - pt[0];
                                final double vy = fy - pt[1];
                                final double vl = Math.hypot(vx, vy);
                                if (vl > 6.0) {
                                    final double nnx = vx / vl;
                                    final double nny = vy / vl;
                                    extLine(g, pt[0] + nnx * 4.0, pt[1] + nny * 4.0,
                                            fx + nnx * 6.0, fy + nny * 6.0, lineArgb);
                                }
                            }
                        }
                    }
                    // 尺寸线本体 + 两端箭头（用统一库的矢量绘制 ✓ 不手写 fill ✗）
                    // ★ 箭头改成**开放式 V 形** ✓（参考图 1/2/3/4 全是这种 ✓ 我原来画的是实心圆点 ✗）
                    dimArrow(g, ax, ay, bx, by, lineArgb);
                    dimArrow(g, bx, by, ax, ay, lineArgb);
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
            } else if (Math.abs(Math.abs(d.measured()) - Math.abs(d.value())) > 1.0e-4) {
                // ★ 角度是有向角（求解器口径）⇒ 实测可能是 −θ ⇒ **按绝对值比较** ✓
                //   否则"设 60 显示 60 却标红"这种误报会一直存在 ✓
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
        // ★ 需求 (4)「悬停可标注实体时**光标变化**」✓
        //   做法 ✓：在**指针本体位置**画一个标注光标**准星** ✓（CAD 里的尺寸光标 ✓）
        //   为什么不动系统指针 ✗：GLFW 指针由 Minecraft 每帧接管 ✓ 强改会闪烁甚至消失 ✗
        // ★ 需求 (4) ✓：预览也**沿用已放置尺寸的画法** ✓（界线 + 尺寸线 + V 形箭头 ✓ 不是只有文字 ✗）
        //   实现 ✓：与别的覆盖层同一套路 —— 找子结构 ✓ 用 endpointsOfVars 推参考点 ✓ 画半透明尺寸 ✓
        dev.ryanhcode.sable.sublevel.ClientSubLevel psub = null;
        if (mc.level != null
                && dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level)
                instanceof final dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer pc) {
            for (final dev.ryanhcode.sable.sublevel.ClientSubLevel s : pc.getAllSubLevels()) {
                if (s.getUniqueId().equals(working.body())) {
                    psub = s;
                }
            }
        }
        // ★ 用户实测："角度尺寸的预览和最终在草图上的显示不一致，预览还是线段尺寸的样式" ✗
        //   ⇒ 角度预览也走**同一套**角度画法（弧线 + 切向箭头 + 两线自身作界线 ✓）
        //   标签位置就用指针 ⇒ 弧半径随指针变化 ⇒ **松手落下的就是看到的** ✓
        if (shown.labelKey() != null && shown.labelKey().contains("angle")
                && shown.vars() != null && shown.vars().length >= 8 && psub != null) {
            final org.joml.Vector3d angTmp = new org.joml.Vector3d();
            drawAngularDimension(g, working, psub, mc, angTmp, shown.vars(),
                    new double[] {guiX(mc) + OFFSET_X, guiY(mc) + OFFSET_Y},
                    session.lockedVariant() >= 0 ? 0xEEFFCC55 : 0x99FFCC55,
                    shown.value(), -1, true);
            return; // 不再走下面的线性预览 ✓
        }
        final double[] pends = com.ovo.sablestopnow.sketch.SketchMeasure
                .endpointsOfVars(working, shown.solverKind(), shown.vars());
        if (psub != null && pends != null) {
            final org.joml.Vector3d ptmp = new org.joml.Vector3d();
            final double[] psa = project(working, psub, pends[0], pends[1], mc, ptmp);
            final double[] psb = project(working, psub, pends[2], pends[3], mc, ptmp);
            if (psa != null && psb != null) {
                final int pArgb = session.lockedVariant() >= 0 ? 0xEEFFCC55 : 0x99FFCC55;
                // ★ 用户要求：**预览要和落下去的尺寸长得一模一样** ✓
                //   （原话："用白色线段加中间断开的箭头显示的方式" ✓）
                //   ⇒ 不再"拉一条线到指针" ✗，改用**已放置尺寸**那套画法：
                //     ① 尺寸线两段、**中间在标签处断开** ✓ ② 两端开放式 V 形箭头 ✓
                //     ③ 两端各一小段界线（沿尺寸线方向略超出 ✓ 与已放置尺寸同一观感 ✓）
                final double ddx = psb[0] - psa[0];
                final double ddy = psb[1] - psa[1];
                final double dlen = Math.hypot(ddx, ddy);
                if (dlen > 1.0e-6) {
                    final double ux = ddx / dlen;
                    final double uy = ddy / dlen;
                    final double gap = 19.0; // 与 drawDimensionLabels 同一档 ✓
                    // ★ 需求 ②：**预览要跟着鼠标走** ✓（用户："没有跟着鼠标" ✗）
                    //   做法与 SolidWorks 一致 ✓：把整条尺寸线沿**垂直于自身**的方向平移到指针处 ✓，
                    //   断口（文字位置）则跟着指针**在尺寸线方向上的投影** ✓
                    //   ⇒ 指针往哪拖，尺寸线就摆到哪、文字跟到哪 ✓ 松手落下的位置就是看到的那个 ✓
                    final double curX = guiX(mc) + OFFSET_X;
                    final double curY = guiY(mc) + OFFSET_Y;
                    final double midBaseX = (psa[0] + psb[0]) * 0.5;
                    final double midBaseY = (psa[1] + psb[1]) * 0.5;
                    final double relX = curX - midBaseX;
                    final double relY = curY - midBaseY;
                    // 沿尺寸线方向的投影（决定断口/文字位置 ✓）与垂直方向的偏移（决定尺寸线摆哪 ✓）
                    final double along = relX * ux + relY * uy;
                    final double perpX = relX - along * ux;
                    final double perpY = relY - along * uy;
                    final double offX = perpX;
                    final double offY = perpY;
                    // 平移后的两端与断口 ✓
                    // ★ 需求 ③：预览也要**按类型摆正** ✓（与落下后的画法完全一致 ✓）
                    //   水平 ⇒ 尺寸线水平、过指针；竖直 ⇒ 尺寸线竖直、过指针 ✓
                    final String plk = shown.labelKey() == null ? "" : shown.labelKey();
                    final boolean pHoriz = plk.contains("horizontal");
                    final boolean pVert = !pHoriz && plk.contains("vertical");
                    final double paX;
                    final double paY;
                    final double pbX;
                    final double pbY;
                    final double midX;
                    final double midY;
                    if (pHoriz) {
                        paX = psa[0];
                        paY = curY;
                        pbX = psb[0];
                        pbY = curY;
                        midX = curX;
                        midY = curY;
                    } else if (pVert) {
                        paX = curX;
                        paY = psa[1];
                        pbX = curX;
                        pbY = psb[1];
                        midX = curX;
                        midY = curY;
                    } else {
                        paX = psa[0] + offX;
                        paY = psa[1] + offY;
                        pbX = psb[0] + offX;
                        pbY = psb[1] + offY;
                        midX = midBaseX + offX + ux * along;
                        midY = midBaseY + offY + uy * along;
                    }
                    com.ovo.sablestopnow.client.gui.lib.UiVector.line(g,
                            paX, paY, midX - ux * gap * 0.5, midY - uy * gap * 0.5,
                            1.0f, pArgb);
                    com.ovo.sablestopnow.client.gui.lib.UiVector.line(g,
                            midX + ux * gap * 0.5, midY + uy * gap * 0.5, pbX, pbY,
                            1.0f, pArgb);
                    dimArrow(g, paX, paY, pbX, pbY, pArgb);
                    dimArrow(g, pbX, pbY, paX, paY, pArgb);
                    // ★ 需求 ③：界线要**垂直于图元**（= 沿"测量点 → 尺寸线"的方向引出 ✓）
                    //   原实现把界线画在**尺寸线方向上**（3px→9px ✗）⇒ 看起来是"顺着线"而非"引出" ✗
                    //   现在：从测量点出发、垂直于尺寸线走到平移后的尺寸线 ✓
                    //   几何端留 4px 缝 ✓ 尺寸线端超出 6px ✓（与已放置尺寸同一套规则 ✓）
                    // ★ 需求 ③：界线从"测量点"垂直引到**尺寸线**上 ✓
                    //   （与已放置尺寸同一套规则：几何端 4px 缝、尺寸线端超出 6px ✓）
                    //   ⚠ 不能再用 offX/offY 当法向 ✗ —— 那个是"参考连线的垂向"，
                    //     水平/竖直尺寸下它与尺寸线不垂直 ⇒ 界线会斜 ✗
                    final double dlx2 = pbX - paX;
                    final double dly2 = pbY - paY;
                    final double dlen22 = dlx2 * dlx2 + dly2 * dly2;
                    if (dlen22 > 1.0e-6) {
                        for (final double[] pt : new double[][] {
                                {psa[0], psa[1]}, {psb[0], psb[1]}}) {
                            final double t = ((pt[0] - paX) * dlx2 + (pt[1] - paY) * dly2) / dlen22;
                            final double fx = paX + t * dlx2;
                            final double fy = paY + t * dly2;
                            final double vx2 = fx - pt[0];
                            final double vy2 = fy - pt[1];
                            final double vl2 = Math.hypot(vx2, vy2);
                            if (vl2 > 6.0) {
                                final double nnx2 = vx2 / vl2;
                                final double nny2 = vy2 / vl2;
                                extLine(g, pt[0] + nnx2 * 4.0, pt[1] + nny2 * 4.0,
                                        fx + nnx2 * 6.0, fy + nny2 * 6.0, pArgb);
                            }
                        }
                    }
                }
            }
        }

        final int curX = guiX(mc);
        final int curY = guiY(mc);
        final int curArgb = session.lockedVariant() >= 0 ? 0xEEFFCC55 : 0xAAFFCC55;
        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, curX + 3, curY - 9, curX + 3, curY - 3,
                1.4f, curArgb);
        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, curX + 3, curY + 3, curX + 3, curY + 9,
                1.4f, curArgb);
        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, curX - 9, curY + 3, curX - 3, curY + 3,
                1.4f, curArgb);
        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, curX + 3, curY + 3, curX + 9, curY + 3,
                1.4f, curArgb);
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
    /**
     * **悬停名称提示** ✓（需求 1："鼠标移到图元上时在右上方小字显示名称" ✓）。
     *
     * <p>名称 = 图元种类 + **同类序号**（从 1 开始 ✓ 例如"直线3"、"圆弧1"）✓
     * 序号按图元在草图里的顺序数同类 ⇒ 与左栏的编号口径一致、稳定不跳 ✓。
     */
    /**
     * **原点 + 坐标轴箭头** ✓（需求 ⑥，参考 SolidWorks 的草图原点 ✓）。
     *
     * <p>画在**屏幕空间** ✓（复用主循环同一条投影 ✓ ⇒ 看到的与可吸附的位置一致 ✓）。
     * 原点用白色小方框 ✓；U 轴红、V 轴绿（与 SolidWorks 的 X 红 / Y 绿一致 ✓）。
     */
    private static void drawOriginAxes(final net.minecraft.client.gui.GuiGraphics g,
                                       final Minecraft mc) {
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
        final double[] o = projectUv(work, sub, mc, tmp, 0.0, 0.0);
        if (o == null) {
            return;
        }
        // 原点：小方框 ✓（白 ✓ 与普通点区分 ✓）
        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, o[0] - 4, o[1] - 4, o[0] + 4, o[1] - 4,
                1.2f, 0xFFFFFFFF);
        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, o[0] + 4, o[1] - 4, o[0] + 4, o[1] + 4,
                1.2f, 0xFFFFFFFF);
        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, o[0] + 4, o[1] + 4, o[0] - 4, o[1] + 4,
                1.2f, 0xFFFFFFFF);
        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, o[0] - 4, o[1] + 4, o[0] - 4, o[1] - 4,
                1.2f, 0xFFFFFFFF);
        // U 轴（红 ✓）/ V 轴（绿 ✓）：短线 + 两笔箭头 ✓
        arrow(g, work, sub, mc, tmp, o, 0.45, 0.0, 0xFFFF5555);
        arrow(g, work, sub, mc, tmp, o, 0.0, 0.45, 0xFF55FF55);
    }

    /** 从原点画一支带箭头的轴 ✓（屏幕空间 ✓）。 */
    private static void arrow(final net.minecraft.client.gui.GuiGraphics g,
                              final com.ovo.sablestopnow.sketch.Sketch work,
                              final dev.ryanhcode.sable.sublevel.ClientSubLevel sub,
                              final Minecraft mc, final org.joml.Vector3d tmp,
                              final double[] o, final double du, final double dv, final int argb) {
        final double[] tip = projectUv(work, sub, mc, tmp, du, dv);
        if (tip == null) {
            return;
        }
        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, o[0], o[1], tip[0], tip[1], 1.6f, argb);
        final double dx = tip[0] - o[0];
        final double dy = tip[1] - o[1];
        final double len = Math.hypot(dx, dy);
        if (len < 1.0e-6) {
            return;
        }
        final double ux = dx / len;
        final double uy = dy / len;
        // 箭头两笔 ✓（沿反方向各偏 30° ✓）
        for (final double sgn : new double[] {1.0, -1.0}) {
            final double ax = ux * Math.cos(0.5) - uy * Math.sin(0.5) * sgn;
            final double ay = ux * Math.sin(0.5) * sgn + uy * Math.cos(0.5);
            com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, tip[0], tip[1],
                    tip[0] - ax * 7.0, tip[1] - ay * 7.0, 1.6f, argb);
        }
    }

    /** 上次已打印的吸附诊断 ✓（只在变化时打日志 ✓ 免得刷屏 ✓）。 */
    private static String lastSnapDiagLogged = "";
    private static String lastLineDiagLogged = "";
    private static String lastArcDiagLogged = "";
    private static String lastBezierDiagLogged = "";

    private static void drawHoverName(final net.minecraft.client.gui.GuiGraphics g,
                                      final Minecraft mc) {
        if (!SketchClient.active() || mc.level == null) {
            return;
        }
        final SketchSession session = SketchClient.mode().session();
        final var work = session == null ? null : session.working();
        if (work == null) {
            return;
        }
        final var hovered = session.pickEntity(session.hoverU(), session.hoverV(),
                SketchClient.mode().pickTolerance());
        if (hovered == null) {
            return;
        }
        int index = 0;
        int same = 0;
        for (final var e : work.entities()) {
            index++;
            if (e.kind() == hovered.kind()) {
                same++;
            }
            if (e == hovered) {
                break;
            }
        }
        final String base = switch (hovered.kind()) {
            case LINE -> "直线";
            case CIRCLE -> "圆";
            case ARC -> "圆弧";
            case POINT -> "点";
            case BEZIER -> "贝塞尔";
        };
        final String label = base + same;
        final int tx = (int) com.ovo.sablestopnow.client.gui.lib.Ui.mouseXd() + 14;
        final int ty = (int) com.ovo.sablestopnow.client.gui.lib.Ui.mouseYd() - 20;
        com.ovo.sablestopnow.client.gui.lib.UiDraw.text(g, mc.font, label,
                tx, ty, 0xFFDDDDDD, 1.0f, true);
    }

    /** 平面 uv → 屏幕 ✓（角度尺寸画法自用 ✓ 与主循环同一条投影链 ✓）。 */
    @Nullable
    private static double[] projectUv(final com.ovo.sablestopnow.sketch.Sketch work,
                                      final dev.ryanhcode.sable.sublevel.ClientSubLevel sub,
                                      final Minecraft mc, final org.joml.Vector3d tmp,
                                      final double u, final double v) {
        final org.joml.Vector3d local = work.plane().toLocal(u, v);
        final org.joml.Vector3d world = sub.renderPose().transformPosition(local, tmp);
        final double[] s = com.ovo.sablestopnow.client.model.ModelCamera.projectToScreen(
                new net.minecraft.world.phys.Vec3(world.x, world.y, world.z), mc);
        return s == null || s.length < 2 ? null : s;
    }

    /**
     * **角度尺寸**的画法 ✓（对齐 SolidWorks ✓ 见 docs/智能尺寸-design.md §6.3）。
     *
     * <p>尺寸线是一段**圆弧**（圆心 = 构成角度的两条线的交点 ✓）；
     * 箭头画在弧两端、方向沿**切向** ✓；**尺寸界线就是那两条线本身**（延伸出弧一点 ✓）。
     * 弧半径取"交点到标签的距离" ✓ ⇒ 天然满足"预览跟随鼠标 / 位置可拖动" ✓。
     *
     * @param v 约束操作数：两条线各 4 个（a0x a0y a1x a1y b0x b0y b1x b1y ✓）
     */
    private static void drawAngularDimension(final net.minecraft.client.gui.GuiGraphics g,
                                             final com.ovo.sablestopnow.sketch.Sketch work,
                                             final dev.ryanhcode.sable.sublevel.ClientSubLevel sub,
                                             final Minecraft mc, final org.joml.Vector3d tmp,
                                             final int[] v, final double[] labelScreen,
                                             final int argb, final double value,
                                             final int hitIndex, final boolean driving) {
        if (v == null || v.length < 8) {
            return;
        }
        final double[] la = projectUv(work, sub, mc, tmp, work.variable(v[0]), work.variable(v[1]));
        final double[] lb = projectUv(work, sub, mc, tmp, work.variable(v[2]), work.variable(v[3]));
        final double[] ma = projectUv(work, sub, mc, tmp, work.variable(v[4]), work.variable(v[5]));
        final double[] mb = projectUv(work, sub, mc, tmp, work.variable(v[6]), work.variable(v[7]));
        if (la == null || lb == null || ma == null || mb == null) {
            return;
        }
        final double d1x = lb[0] - la[0];
        final double d1y = lb[1] - la[1];
        final double d2x = mb[0] - ma[0];
        final double d2y = mb[1] - ma[1];
        final double den = d1x * d2y - d1y * d2x;
        double ox;
        double oy;
        if (Math.abs(den) < 1.0e-6) {
            // 平行 ⇒ 没有尖点：退化为两条线中点的中点 ✓（宁可画得朴素 ✗ 不画错 ✓）
            ox = ((la[0] + lb[0]) + (ma[0] + mb[0])) * 0.25;
            oy = ((la[1] + lb[1]) + (ma[1] + mb[1])) * 0.25;
        } else {
            final double t = ((ma[0] - la[0]) * d2y - (ma[1] - la[1]) * d2x) / den;
            ox = la[0] + d1x * t;
            oy = la[1] + d1y * t;
        }
        // 弧半径 = 交点到标签的距离 ✓（于是拖标签 / 动鼠标 ⇒ 弧半径跟着变 ✓）
        final double r = Math.max(18.0, Math.min(
                Math.hypot(labelScreen[0] - ox, labelScreen[1] - oy), 400.0));
        // 取**远离交点**的那一端定方向 ✓（线可能从交点往外画 ✓）
        double a1 = Math.atan2(la[1] - oy, la[0] - ox);
        if (Math.hypot(lb[0] - ox, lb[1] - oy) > Math.hypot(la[0] - ox, la[1] - oy)) {
            a1 = Math.atan2(lb[1] - oy, lb[0] - ox);
        }
        double a2 = Math.atan2(ma[1] - oy, ma[0] - ox);
        if (Math.hypot(mb[0] - ox, mb[1] - oy) > Math.hypot(ma[0] - ox, ma[1] - oy)) {
            a2 = Math.atan2(mb[1] - oy, mb[0] - ox);
        }
        double sweep = a2 - a1;
        while (sweep > Math.PI) {
            sweep -= 2.0 * Math.PI;
        }
        while (sweep < -Math.PI) {
            sweep += 2.0 * Math.PI;
        }
        // 界线 = 两条线**自身**（延伸出弧外 10px ✓ 与 SolirdWorks 一致 ✓）
        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, ox, oy,
                ox + Math.cos(a1) * (r + 10.0), oy + Math.sin(a1) * (r + 10.0), 1.0f, argb);
        com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, ox, oy,
                ox + Math.cos(a2) * (r + 10.0), oy + Math.sin(a2) * (r + 10.0), 1.0f, argb);
        // 弧线：折线逼近（24 段 ✓ 看不出是折线 ✓）
        final int steps = 24;
        double px = ox + Math.cos(a1) * r;
        double py = oy + Math.sin(a1) * r;
        for (int i = 1; i <= steps; i++) {
            final double a = a1 + sweep * i / steps;
            final double nx = ox + Math.cos(a) * r;
            final double ny = oy + Math.sin(a) * r;
            com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, px, py, nx, ny, 1.0f, argb);
            px = nx;
            py = ny;
        }
        // ★ 角度**数值文字** ✓（用户实测："箭头对了，但是不显示角度" ✗ ——
        //   我这条独立画法当时只画了弧和箭头、**忘了画文字** ✓）
        //   位置取弧的中间方向、比弧再外一点 ✓ 与 SolidWorks 一致 ✓
        final double midA = a1 + sweep * 0.5;
        // ★ 修实测"角度尺寸得到的角度是负数" ✓✓
        //   约束里存的是**有向角**（−180…180 ✓ 与求解器同口径 ✓ 这正是设定值不再差 180° 的原因 ✓）
        //   但**显示**不该出现负号 ⇒ 画面与改值框都取绝对值（0…180 ✓ 与 SolidWorks 一致 ✓）
        final String angText = Component.translatable("model.dim.angle").getString()
                + String.format(java.util.Locale.ROOT, " %.2f°", Math.abs(value));
        final double angTx = ox + Math.cos(midA) * (r + 12.0);
        final double angTy = oy + Math.sin(midA) * (r + 12.0);
        com.ovo.sablestopnow.client.gui.lib.UiDraw.text(g, Minecraft.getInstance().font, angText,
                (int) angTx, (int) angTy, argb, 1.0f, true);
        // ★ 登记"可双击改值"的命中区 ✓（与线性尺寸同一套：**画与命中共用同一个位置** ✓
        //   否则会出现"看到的位置点不中" ✗ —— 这类问题用户实测反馈过 ✓）
        // 预览（hitIndex < 0）不登记命中区 ✓ —— 预览还没落约束，点了也没有对象可改 ✓
        if (hitIndex >= 0) {
            LABEL_HITS.add(new LabelHit(hitIndex, driving, angTx, angTy));
        }
        // 箭头：沿**切向**（弧两端各一个 ✓ 不是线性尺寸那种 V 形 ✓）
        final double dir = sweep >= 0.0 ? 1.0 : -1.0;
        for (final double aEnd : new double[] {a1, a2}) {
            final double ex = ox + Math.cos(aEnd) * r;
            final double ey = oy + Math.sin(aEnd) * r;
            final double tx = -Math.sin(aEnd) * dir * 12.0;
            final double ty = Math.cos(aEnd) * dir * 12.0;
            final double inx = Math.cos(aEnd) * 3.5;
            final double iny = Math.sin(aEnd) * 3.5;
            com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, ex, ey,
                    ex + tx + inx, ey + ty + iny, 1.2f, argb);
            com.ovo.sablestopnow.client.gui.lib.UiVector.line(g, ex, ey,
                    ex + tx - inx, ey + ty - iny, 1.2f, argb);
        }
    }

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
        // ★ 用户实测反馈："网格吸附的线会挡住 GUI" ✗✓
        //   根因就是这里的半长 32 格 ✗：投影后横跨整个画面，必然压在左右面板上 ✓
        //   ⇒ 改成 **近处引导线**（±4 格 = 全长 8 格）✓：
        //     贴着吸附点给方向指引 ✓ 又永远不会伸到画面边缘的面板里 ✓。
        final double half = 4.0; // 半长（方块 ✓）近处引导 ✓ 不跨屏 ✓
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
        if (screen == null || screen.length < 2) { // ★ 只要 2 个分量 ✓（projectToScreen 返回 {x,y} ✗ 无深度 ✓；
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
                                 final int argb) {
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

