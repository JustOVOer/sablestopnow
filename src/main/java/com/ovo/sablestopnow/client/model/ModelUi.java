package com.ovo.sablestopnow.client.model;

import com.ovo.sablestopnow.client.MateClientState;
import com.ovo.sablestopnow.client.MateTargeting;
import com.ovo.sablestopnow.client.gui.lib.Anim;
import com.ovo.sablestopnow.client.gui.lib.Button;
import com.ovo.sablestopnow.client.gui.lib.Checkbox;
import com.ovo.sablestopnow.client.gui.lib.Container;
import com.ovo.sablestopnow.client.gui.lib.Label;
import com.ovo.sablestopnow.client.gui.lib.Layouts;
import com.ovo.sablestopnow.client.gui.lib.MenuList;
import com.ovo.sablestopnow.client.gui.lib.Panel;
import com.ovo.sablestopnow.client.gui.lib.SectionPane;
import com.ovo.sablestopnow.client.gui.lib.Tabs;
import com.ovo.sablestopnow.client.gui.lib.TreeView;
import com.ovo.sablestopnow.client.gui.lib.Ui;
import com.ovo.sablestopnow.client.gui.lib.UiContext;
import com.ovo.sablestopnow.client.gui.lib.UiDraw;
import com.ovo.sablestopnow.client.gui.lib.UiLayer;
import com.ovo.sablestopnow.client.gui.lib.Widget;
import com.ovo.sablestopnow.mate.Mate;
import com.ovo.sablestopnow.mate.MateRef;
import com.ovo.sablestopnow.mate.MateType;
import com.ovo.sablestopnow.network.MateNetworking;
import com.ovo.sablestopnow.network.StaffEnhanceNetworking;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 模型模式的界面：上边栏（功能标签页）、左边栏（编辑区）、右边栏（树状图）。
 *
 * <p>全部只通过 GUI 库搭建（{@code client/gui/lib}）：三个面板各是一个不占屏幕栈的覆盖层
 * （{@link UiLayer}），因此模型模式可以一边看世界一边点界面。层级与避让由库负责，
 * 面板之间不会互相遮挡（上边栏占顶部、左右边栏各占一边，中间的 3D 视口留给世界）。
 */
public final class ModelUi {

    public static final String LAYER_TOP = "model.top";
    public static final String LAYER_LEFT = "model.left";
    public static final String LAYER_TREE = "model.tree";
    /** 选择标记层（屏幕空间的点/棱/面候选标记）。 */
    public static final String LAYER_PICKS = "model.picks";
    public static final String LAYER_HUD = "model.hud";

    private static final int TOP_H = 36;
    private static final int TREE_W = 138;
    private static final int LEFT_W = 152;

    /** 当前功能类型标签页（状态而不是字段：切页后重建上边栏不会回到第一页）。 */
    private static final com.ovo.sablestopnow.client.gui.lib.Prop<String> TAB =
            com.ovo.sablestopnow.client.gui.lib.Prop.of("model.tab", ModelTools.TAB_MATE);
    private static boolean treeVisible = true;

    @Nullable
    private static TreeView tree;
    @Nullable
    private static Container leftBody;
    @Nullable
    private static Label leftTitle;
    @Nullable
    private static Button completeButton;

    @Nullable
    private static MateType chosenType;
    @Nullable
    private static List<MateType> applicable;

    private static int refreshCountdown;

    /** 左右边栏实例（用于让上边栏只占两栏之间的宽度，以及读取它们的当前宽度）。 */
    @Nullable
    private static com.ovo.sablestopnow.client.gui.lib.CollapsiblePanel leftPanelRef;
    @Nullable
    private static com.ovo.sablestopnow.client.gui.lib.CollapsiblePanel treePanelRef;

    private ModelUi() {
    }

    // ============ 打开 / 关闭 ============

    public static void openPanels() {
        if (!ModelMode.isOn()) {
            return;
        }
        // 选择标记层先开：它是 HUD 层里最底下的一层，面板会画在它上面（层顺序 = 注册顺序）
        buildPicks();
        buildTop();
        buildTree();
        buildLeft();
        buildHud();
        refreshLeftPanel();
    }

    public static void closePanels() {
        Ui.close(LAYER_TOP);
        Ui.close(LAYER_LEFT);
        Ui.close(LAYER_TREE);
        Ui.close(LAYER_HUD);
        Ui.close(LAYER_PICKS);
        tree = null;
        leftBody = null;
    }

    /**
     * 选择标记层：把「鼠标下是什么」画在<b>屏幕上</b>（SolidWorks 式），而不是直接高亮模型。
     *
     * <p>这样玩家看得见可点范围（点=小方块、棱=线段、面=轮廓），也看得见「光标已经停在标记上」
     * （黄色 → 橙色）。选中之后才回到模型上渲染（青色），见 {@code ModelRenderer.drawPicked}。
     */
    private static void buildPicks() {
        UiLayer layer = Ui.layer(LAYER_PICKS);
        if (layer == null) {
            layer = Ui.open(UiLayer.of(LAYER_PICKS).size(0, 0));
        }
        layer.root.clear();
        layer.root.layout = Layouts.stack();
        layer.hud = true;
        layer.consumeInput = false;
        layer.consumeKeys = false;
        layer.closeOnEscape = false;
        layer.reserveSpace = false;
        layer.slideFromX = 0;
        layer.slideFromY = 0;

        final Widget overlay = new Widget() {
            @Override
            public void render(final UiContext ctx, final GuiGraphics g) {
                ModelPicks.render(ctx, g);
            }
        };
        overlay.pointerTransparent = true;
        layer.root.add(overlay);
        layer.visible = true;
    }

    public static void toggleTree() {
        treeVisible = !treeVisible;
        final UiLayer layer = Ui.layer(LAYER_TREE);
        if (layer != null) {
            layer.visible = treeVisible;
        }
    }

    /** 每帧（由 {@link ModelRenderer} 驱动）：周期刷新树与左栏，捡起服务端同步来的变化。 */
    public static void tickRefresh() {
        if (!ModelMode.isOn()) {
            return;
        }
        if (Ui.layer(LAYER_TOP) == null) {
            openPanels();
            return;
        }
        if (--refreshCountdown > 0) {
            return;
        }
        refreshCountdown = 12;
        layoutSidesBelowTop();
        refreshTree();
        ModelForceUi.tickAutoSelect();
        // 把服务端的权威值拉进编辑器状态（控件每帧读状态，所以这里不需要重建左栏）
        ModelForceUi.tickSync();
        // 配合编辑页同理：服务端是权威，改了会被拒绝的场合界面会自动退回
        MateEditUi.tickSync();
        refreshLeftPanel();
    }

    // ============ 上边栏 ============

    private static void buildTop() {
        UiLayer layer = Ui.layer(LAYER_TOP);
        if (layer == null) {
            layer = Ui.open(UiLayer.of(LAYER_TOP).size(0, 0));
        }
        layer.root.clear();
        layer.root.layout = Layouts.dock(4, 4);
        layer.hud = true;
        // 从上边滑入
        layer.slideFromX = 0;
        layer.slideFromY = -TOP_H;

        final Panel bar = new Panel();
        bar.drawTitle = false;
        bar.dock = Layouts.Dock.TOP;
        bar.prefH = TOP_H;
        bar.layout = Layouts.vbox(2, 2);

        // ---- 上行：当前功能类型下的具体功能按钮 ----
        // 为什么和标签页分成两行：挤在一行的时候两者视觉同级，玩家分不清
        // 「这是切页」还是「这是执行」，实测反馈里也确实点错过。
        // 分开之后语义固定：上行永远是「做什么」，下行永远是「在哪一页」。
        bar.add(toolButtons());

        // ---- 下行：功能类型标签页 ----
        final Tabs tabs = new Tabs(TAB);
        tabs.prefH = 15;
        tabs.add(new Tabs.Tab(ModelTools.TAB_MATE, Component.translatable("model.tab.mate"))
                .compact("model.tab.mate.short"));
        tabs.add(new Tabs.Tab(ModelTools.TAB_MODEL, Component.translatable("model.tab.model"))
                .compact("model.tab.model.short"));
        tabs.add(new Tabs.Tab(ModelTools.TAB_VIEW, Component.translatable("model.tab.view"))
                .compact("model.tab.view.short"));
        // 验证类功能统一入口（用户规定：一律挂这里）
        tabs.add(new Tabs.Tab(ModelTools.TAB_DEBUG, Component.translatable("model.tab.debug"))
                .compact("model.tab.debug.short"));
        tabs.id("model.tabs");
        tabs.onChange = id -> {
            // 切到「配合」标签页就直接进入配合流程 —— 标签页与工具按钮同名时，
            // 玩家几乎一定会点标签页（实测反馈「点了配合再点就没反应」，日志里 tool=none 印证了这点）。
            if (ModelTools.TAB_MATE.equals(id)) {
                ModelMode.setTool(ModelTools.MATE);
                ModelMode.clearPicks();
            } else if (ModelTools.isMate(ModelMode.tool())) {
                ModelMode.setTool(ModelTools.NONE);
                ModelMode.clearPicks();
            }
            buildTop();
            refreshLeftPanel();
        };
        bar.add(tabs);
        layer.root.add(bar);
        layer.visible = true;
    }

    /** 当前标签页下的功能按钮。 */
    private static Container toolButtons() {
        final Container row = new Container();
        // 中英双语标签在 427×240 个 GUI 单位下必然超出屏幕（实测最右边的「齿轮配合」被切掉）。
        // hboxFit 会先按长标签量：放不下就整排换短标签（Language 文件里的 *.short），
        // 还放不下才等比压窄 —— 保证不会有按钮跑到屏幕外面去。
        row.layout = Layouts.hboxWrap(2, 2, 2);
        row.prefW = Widget.FILL;
        row.prefH = 15;
        switch (TAB.get()) {
            case ModelTools.TAB_MATE -> {
                row.add(toolButton("model.tool.mate", "model.tool.mate.short",
                        ModelTools.MATE, "model.tool.mate.hint"));
                row.add(toolButton("model.tool.ratio", "model.tool.ratio.short",
                        ModelTools.RATIO, "model.tool.ratio.hint"));
                row.add(toolButton("model.tool.gear", "model.tool.gear.short",
                        ModelTools.GEAR, "model.tool.gear.hint"));
            }
            case ModelTools.TAB_MODEL -> {
                row.add(shortButton("model.tool.normal_to", "model.tool.normal_to.short",
                        () -> startTool(ModelTools.NORMAL_TO)).tooltipKey("model.tool.normal_to.hint"));
                row.add(shortButton("model.tool.force", "model.tool.force.short",
                        () -> startTool(ModelTools.FORCE)).tooltipKey("model.tool.force.hint"));
                // 草图：进入一个持续编辑的环境，选择结构与平面之后由 SketchClient 接管交互。
                row.add(shortButton("model.tool.sketch", "model.tool.sketch.short",
                        ModelUi::startSketch).tooltipKey("model.tool.sketch.hint"));
                // 拉伸：先选结构，再在树里点一张**已保存**的草图作为拉伸源。
                row.add(shortButton("model.tool.extrude", "model.tool.extrude.short",
                        ModelUi::startExtrude).tooltipKey("model.tool.extrude.hint"));
                row.add(shortButton("model.tool.hide", "model.tool.hide.short", ModelUi::toggleHide));
                row.add(shortButton("model.tool.delete_mate", "model.tool.delete_mate.short",
                        ModelUi::deleteSelectedMate));
            }
            case ModelTools.TAB_DEBUG -> {
                // 验证类功能的统一入口（用户规定：一律挂这里）
                row.add(shortButton("model.debug.sphere", "model.debug.sphere.short",
                        ModelUi::spawnDebugSphere)
                        .tooltipKey("model.debug.sphere.hint"));
                row.add(shortButton("model.debug.voxel", "model.debug.voxel.short",
                        ModelUi::runVoxelColliderTest)
                        .tooltipKey("model.debug.voxel.hint"));
                // Test-object markers. The traced CSV otherwise mixes every hull body in the
                // scene, which makes it impossible to tell which row belongs to the cylinder
                // under investigation. Marking pins the two objects the player cares about.
                row.add(shortButton("model.debug.mark_cylinder", "model.debug.mark_cylinder.short",
                        ModelUi::markTestCylinder)
                        .tooltipKey("model.debug.mark_cylinder.hint"));
                row.add(shortButton("model.debug.mark_structure", "model.debug.mark_structure.short",
                        ModelUi::markTestStructure)
                        .tooltipKey("model.debug.mark_structure.hint"));
            }
            default -> {
                row.add(shortButton("model.view.front", "model.view.front.short",
                        () -> ModelCamera.setPreset(ModelCamera.Preset.FRONT)));
                row.add(shortButton("model.view.back", "model.view.back.short",
                        () -> ModelCamera.setPreset(ModelCamera.Preset.BACK)));
                row.add(shortButton("model.view.left", "model.view.left.short",
                        () -> ModelCamera.setPreset(ModelCamera.Preset.LEFT)));
                row.add(shortButton("model.view.right", "model.view.right.short",
                        () -> ModelCamera.setPreset(ModelCamera.Preset.RIGHT)));
                row.add(shortButton("model.view.top", "model.view.top.short",
                        () -> ModelCamera.setPreset(ModelCamera.Preset.TOP)));
                row.add(shortButton("model.view.iso", "model.view.iso.short",
                        () -> ModelCamera.setPreset(ModelCamera.Preset.ISO)));
                row.add(shortButton("model.view.fit", "model.view.fit.short", ModelUi::fitView));
                // 「居中」= 只把选中的模型摆到视野正中，**不改距离**。
                // 选中模型本身已经不会动相机了（见 ModelMode.selectModel），所以这个动作必须显式给。
                row.add(shortButton("model.view.center", "model.view.center.short",
                        ModelUi::centerSelectedModel));
            }
        }
        return row;
    }

    /** 带短标签的按钮：窄屏下由 {@link Layouts#hboxFit(int, int)} 决定是否换成短标签。 */
    private static Button shortButton(final String labelKey, final String shortKey, final Runnable action) {
        return new Button(Component.translatable(labelKey), action)
                .compact(Component.translatable(shortKey));
    }

    private static Button toolButton(final String labelKey, final String shortKey,
                                     final String toolId, final String hintKey) {
        final Button button = shortButton(labelKey, shortKey, () -> startTool(toolId));
        button.tooltipKey(hintKey);
        button.toggled = toolId.equals(ModelMode.tool());
        return button;
    }

    /**
     * 草图环境里的几何约束按钮。
     *
     * <p>校验交给模型层（圆不能加水平/竖直），这里只负责把失败翻译成玩家看得懂的提示 ——
     * 静默无反应是最糟的结果：玩家会以为按钮坏了。
     */
    private static Button constraintButton(final String labelKey, final int kind) {
        final Button button = new Button(Component.translatable(labelKey), () -> {
            final var mode = com.ovo.sablestopnow.client.SketchClient.mode();
            if (mode.session().selectedEntity() == null) {
                ModelMode.prompt("model.sketch.constraint.need_select");
            } else if (mode.constrain(kind)) {
                ModelMode.prompt("model.sketch.constraint.added");
            } else {
                ModelMode.prompt("model.sketch.constraint.need_line");
            }
            refreshLeftPanel();
        });
        button.prefW = Widget.FILL;
        return button;
    }

    /**
     * 拉伸那一套控件（方式 + 深度 + 反向深度 + 创建）。
     *
     * <p>拆成方法是因为左栏里"拉伸"和"旋转"两套参数完全不同，
     * 全塞在一个 if/else 里会变成一坨谁也读不懂的嵌套。
     */
    private static void buildExtrudeControls(final com.ovo.sablestopnow.client.gui.lib.Container body) {
        final var modeRow = new com.ovo.sablestopnow.client.gui.lib.Container();
        modeRow.layout = com.ovo.sablestopnow.client.gui.lib.Layouts.hbox(0, 3);
        modeRow.prefH = 13;
        modeRow.add(extrudeModeButton("model.extrude.mode.one_sided",
                com.ovo.sablestopnow.sketch.Extrude.Mode.ONE_SIDED));
        modeRow.add(extrudeModeButton("model.extrude.mode.two_sided",
                com.ovo.sablestopnow.sketch.Extrude.Mode.TWO_SIDED));
        modeRow.add(extrudeModeButton("model.extrude.mode.symmetric",
                com.ovo.sablestopnow.sketch.Extrude.Mode.SYMMETRIC));
        body.add(modeRow);

        com.ovo.sablestopnow.client.gui.lib.Cmd.declare(CMD_EXTRUDE_DEPTH);
        final String depthKey = extrudeMode == com.ovo.sablestopnow.sketch.Extrude.Mode.SYMMETRIC
                ? "model.extrude.total_depth" : "model.extrude.depth";
        final com.ovo.sablestopnow.client.gui.lib.UiField depthField =
                com.ovo.sablestopnow.client.gui.lib.UiField.number(
                        depthKey, CMD_EXTRUDE_DEPTH, extrudeDepth,
                        0.1, com.ovo.sablestopnow.sketch.Extrude.MAX_DEPTH, 1.0);
        depthField.format(v -> String.format(java.util.Locale.ROOT, "%.1f", v));
        depthField.commit(v -> {
            // ⚠⚠ **回车只改"待定值"，绝不建实体。**
            //   用户原则（2026-10）："任意页面都应当只有在点击**确定**才在游戏内作出更改。"
            //   以前这里直接调 createExtrude()，于是"调整参数 → 回车"就把拉伸建出来了 ✗
            extrudeDepth.set(v);
        });
        body.add(depthField);

        // 只有"两侧"才需要第二个深度（反向那一侧）
        if (extrudeMode == com.ovo.sablestopnow.sketch.Extrude.Mode.TWO_SIDED) {
            com.ovo.sablestopnow.client.gui.lib.Cmd.declare(CMD_EXTRUDE_BACK_DEPTH);
            final com.ovo.sablestopnow.client.gui.lib.UiField backField =
                    com.ovo.sablestopnow.client.gui.lib.UiField.number(
                            "model.extrude.back_depth", CMD_EXTRUDE_BACK_DEPTH,
                            extrudeBackDepth, 0.0,
                            com.ovo.sablestopnow.sketch.Extrude.MAX_DEPTH, 1.0);
            backField.format(v -> String.format(java.util.Locale.ROOT, "%.1f", v));
            backField.commit(v -> {
                extrudeBackDepth.set(v); // 同上：只改待定值
            });
            body.add(backField);
        }

        body.add(new Button(Component.translatable("model.extrude.create"),
                ModelUi::createExtrude).width(Widget.FILL));
        body.add(reselectButton());
    }

    /**
     * 旋转那一套控件（选轴 + 角度 + 旋向 + 创建）。
     *
     * <p>轴的来源：**从这张草图里挑一条线段**（这和 SolidWorks 一样 —— 轴是草图里的一条构造线）。
     * 挑的时候把它的两个端点**抄成坐标**存下来，而不是记"第几条线"：
     * 图元是 record、没有跨同步的稳定身份，记下标的话草图一动就指错了。
     */
    private static void buildRevolveControls(final com.ovo.sablestopnow.client.gui.lib.Container body,
                                             final com.ovo.sablestopnow.sketch.Sketch source) {
        body.add(new Label(Component.translatable("model.revolve.pick_axis")).color(0xFF8FA0D8));
        int lineNo = 0;
        boolean any = false;
        for (final com.ovo.sablestopnow.sketch.Sketch.Entity e : source.entities()) {
            if (e.kind() != com.ovo.sablestopnow.sketch.Sketch.EntityKind.LINE) {
                continue;
            }
            any = true;
            lineNo++;
            final int[] v = e.vars();
            final double au = source.variable(v[0]);
            final double av = source.variable(v[1]);
            final double bu = source.variable(v[2]);
            final double bv = source.variable(v[3]);
            final boolean picked = revolveAxis != null
                    && Math.abs(revolveAxis[0] - au) < 1.0e-9
                    && Math.abs(revolveAxis[1] - av) < 1.0e-9;
            final String text = String.format(java.util.Locale.ROOT,
                    "(%s) (%.1f,%.1f)→(%.1f,%.1f)", lineNo, au, av, bu, bv);
            body.add(new Button(
                    picked ? Component.literal(text).withStyle(net.minecraft.ChatFormatting.GOLD)
                            : Component.literal(text),
                    () -> {
                        // 抄成"点 + 方向"，方向归一化留给 Revolve.Params 做
                        revolveAxis = new double[] {au, av, bu - au, bv - av};
                        // 同步给渲染器 → 在 3D 里把这条轴画成贯穿的高亮线。
                        // 只靠左栏的坐标文字，玩家认不出那是屏幕上哪条线。
                        com.ovo.sablestopnow.client.SketchRenderer.setRevolveAxis(
                                source.body(), revolveAxis);
                        refreshLeftPanel();
                    }).width(Widget.FILL));
        }
        if (!any) {
            body.add(new Label(Component.translatable("model.revolve.no_axis")).color(0xFFFF5A4D));
        }

        // 旋向：同一个轮廓往两边转出的是**镜像**的两个体，所以这是个真正的选择项
        final var dirRow = new com.ovo.sablestopnow.client.gui.lib.Container();
        dirRow.layout = com.ovo.sablestopnow.client.gui.lib.Layouts.hbox(0, 3);
        dirRow.prefH = 13;
        dirRow.add(revolveDirButton("model.revolve.dir_positive", 1));
        dirRow.add(revolveDirButton("model.revolve.dir_negative", -1));
        body.add(dirRow);

        com.ovo.sablestopnow.client.gui.lib.Cmd.declare(CMD_REVOLVE_ANGLE);
        final com.ovo.sablestopnow.client.gui.lib.UiField angleField =
                com.ovo.sablestopnow.client.gui.lib.UiField.number(
                        "model.revolve.angle", CMD_REVOLVE_ANGLE, revolveAngle,
                        com.ovo.sablestopnow.sketch.Revolve.MIN_ANGLE,
                        com.ovo.sablestopnow.sketch.Revolve.MAX_ANGLE, 15.0);
        angleField.format(v -> String.format(java.util.Locale.ROOT, "%.0f°", v));
        angleField.commit(v -> {
            // 同上：回车只改待定值，"创建"按钮才建实体
            revolveAngle.set(v);
        });
        body.add(angleField);

        body.add(new Button(Component.translatable("model.revolve.create"),
                ModelUi::createRevolve).width(Widget.FILL));
        body.add(reselectButton());
    }

    /**
     * 实体**贴图**编辑区。
     *
     * <h2>为什么不做搜索框，而是"用主手方块"</h2>
     * 面板里唯一的输入控件 {@code UiField} 是**数值滑块**，没有文本框；
     * 而为了一次选贴图去写一个文本输入控件（光标、退格、IME、剪贴板）不划算。
     * 更重要的是：**手里拿着方块点一下就用它**在 Minecraft 里本来就更自然
     *（和"手持方块右键放置"是同一套心智）。
     *
     * <p>纯逻辑的 {@code SolidAppearance.searchBlocks} 已经写好并验过 20 条断言，
     * 以后要加搜索框时直接用，不必重写筛选规则。
     */
    private static void buildTextureEditor(final com.ovo.sablestopnow.client.gui.lib.Container body,
                                          final com.ovo.sablestopnow.sketch.Solid solid) {
        body.add(new com.ovo.sablestopnow.client.gui.lib.Separator());
        body.add(new Label(Component.translatable("model.texture.title", solid.index()))
                .color(0xFFCFD8FF));
        final com.ovo.sablestopnow.sketch.SolidAppearance look = solid.appearance();
        body.add(new Label(look.hasTexture()
                ? Component.translatable("model.texture.current", look.blockId())
                : Component.translatable("model.texture.none")).color(0xFF8FA0D8));

        body.add(new Button(Component.translatable("model.texture.from_hand"),
                () -> applyHandBlock(solid)).width(Widget.FILL));

        // ---- 搜索框 + **静态候选列表**（不走 Overlay，也不依赖"实时打字"）----
        // ⚠ 我在这里连错三轮，最后改成**确定性设计**，把不确定的环节全部去掉：
        //   ① 候选**不再**用弹出层（Overlay）—— 它会被左栏裁剪区切掉（"下拉永远不弹"）；
        //   ② 候选**不再**依赖"活取值 + 边打字边更新"—— 实测六行**完全没有显示**，
        //      说明查询串根本没拿到，而那套设计一旦查询为空就什么都不显示，无法区分原因；
        //   ③ 现在：**回车提交 → 把查询固化进一个独立状态 → 强制重建面板 → 列表按它静态生成**。
        //      列表是建面板时算出来的一次性数据，**显示不出来只有一种可能**：真的没有匹配。
        body.add(new Label(Component.translatable("model.texture.search")).color(0xFF8FA0D8));
        final com.ovo.sablestopnow.client.gui.lib.TextField search =
                com.ovo.sablestopnow.client.gui.lib.TextField.of("")
                        .bind(textureBlockQuery)
                        .placeholder("model.texture.search.hint")
                        .commit(query -> {
                            // 回车：固化查询词并重建 —— 候选列表随之生成
                            textureSearchApplied.set(query == null ? "" : query.trim());
                            lastLeftSignature = null;
                            refreshLeftPanel();
                        });
        search.prefW = Widget.FILL;
        search.prefH = 12;
        body.add(search);

        final java.util.List<String> hits = com.ovo.sablestopnow.sketch.SolidAppearance
                .searchBlocks(allBlockIds(), textureSearchApplied.get(), TEXTURE_CANDIDATE_ROWS);
        if (!textureSearchApplied.get().isBlank() && hits.isEmpty()) {
            body.add(new Label(Component.translatable("model.texture.no_match"))
                    .color(0xFFFF8A5C));
        }
        for (final String hit : hits) {
            body.add(new Button(Component.literal(hit), () -> {
                saveAppearance(solid, solid.appearance().withBlock(hit));
                textureSearchApplied.set("");
                textureBlockQuery.set("");
                lastLeftSignature = null;
                refreshLeftPanel();
            }).width(Widget.FILL));
        }

        // **当前贴图显示区**（用户 2026-10 的要求）。
        // 为什么必须有：现在下拉开不出来时，玩家**没有任何办法确认当前到底贴了什么** ——
        // 输入框一回车就清空、下拉看不见，等于闭着眼睛操作。这里把"当前生效的贴图"
        // 连同**贴图预览**摆出来，至少让状态可见。
        // 用单行 MenuList 而不是 Label：菜单行支持**图集精灵**（Label 只能写字）。
        if (look.hasTexture()) {
            final com.ovo.sablestopnow.client.gui.lib.MenuList current =
                    new com.ovo.sablestopnow.client.gui.lib.MenuList(Widget.FILL);
            final var sprite = com.ovo.sablestopnow.client.SolidTextureCache
                    .previewSprite(look.blockId());
            current.add(new com.ovo.sablestopnow.client.gui.lib.MenuList.Entry(
                    Component.literal(look.blockId()), null).sprite(sprite).checked(true));
            body.add(current);
        } else {
            body.add(new Label(Component.translatable("model.texture.none")).color(0xFF8FA0D8));
        }

        com.ovo.sablestopnow.client.gui.lib.Cmd.declare(CMD_TEXTURE_SCALE);
        final com.ovo.sablestopnow.client.gui.lib.UiField scaleField =
                com.ovo.sablestopnow.client.gui.lib.UiField.number(
                        "model.texture.scale", CMD_TEXTURE_SCALE, textureScale,
                        com.ovo.sablestopnow.sketch.SolidAppearance.MIN_BLOCKS_PER_TILE,
                        com.ovo.sablestopnow.sketch.SolidAppearance.MAX_BLOCKS_PER_TILE, 0.25);
        scaleField.format(v -> String.format(java.util.Locale.ROOT, "%.2f 方块/次", v));
        scaleField.commit(v -> {
            // 同上：回车只改待定值；下面的"应用"按钮才发保存
            textureScale.set(v);
        });
        body.add(scaleField);
        // **应用**：把待定的贴图参数真正写到实体上（用户原则：只有"确定"才作出更改）
        body.add(new Button(Component.translatable("model.texture.apply"), () -> {
            saveAppearance(solid, solid.appearance()
                    .withBlocksPerTile(textureScale.get()));
            refreshLeftPanel();
        }).width(Widget.FILL));

        if (look.hasTexture()) {
            body.add(new Button(Component.translatable("model.texture.clear"),
                    () -> saveAppearance(solid,
                            com.ovo.sablestopnow.sketch.SolidAppearance.EMPTY))
                    .width(Widget.FILL));
        }
        body.add(new Button(Component.translatable("model.texture.close"), () -> {
            // ⚠ **关闭必须强制重建**：`refreshLeftPanel()` 开头有"签名没变就早退"的优化，
            //   而关闭时签名恰好可能被判成"没变" → 什么都不重建 → **面板关不掉**
            //   （用户实测："texture 面板仍然无法被关闭或者顶掉"）。
            //   关闭是明确的用户动作，不该被这个优化挡住，所以把上次签名清掉强制重建。
            textureSolidId = null;
            textureBlockQuery.set("");
            lastLeftSignature = null;
            refreshLeftPanel();
        }).width(Widget.FILL));
    }

    /**
     * 把玩家**主手**拿着的方块设成贴图。
     *
     * <p>⚠ 主手不是方块（空手 / 拿工具）时**明确提示**，而不是静默什么都不做 ——
     * 静默失败会让玩家以为按钮坏了。
     */
    private static void applyHandBlock(final com.ovo.sablestopnow.sketch.Solid solid) {
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.world.entity.player.Player player = mc == null ? null : mc.player;
        if (player == null) {
            return;
        }
        final net.minecraft.world.item.ItemStack held = player.getMainHandItem();
        if (!(held.getItem() instanceof final net.minecraft.world.item.BlockItem blockItem)) {
            ModelMode.prompt("model.texture.not_a_block");
            return;
        }
        final net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
        if (id == null) {
            ModelMode.prompt("model.texture.not_a_block");
            return;
        }
        saveAppearance(solid, solid.appearance().withBlock(id.toString()));
    }

    /** 改外观并提交。外观改动**不影响几何**，但服务端要存下来、客户端要重画。 */
    private static void saveAppearance(final com.ovo.sablestopnow.sketch.Solid solid,
                                       final com.ovo.sablestopnow.sketch.SolidAppearance next) {
        if (next.sameAs(solid.appearance())) {
            return; // 没变就别发包（滑块拖动会触发很多次 commit）
        }
        com.ovo.sablestopnow.network.SolidNetworking.sendSave(solid.withAppearance(next));
        refreshLeftPanel();
    }

    /**
     * **尺寸显示区**：已有的尺寸 + 还没标的参考尺寸 + 可以补上的几何关系。
     *
     * <h2>这才是"智能尺寸"的另一半</h2>
     * 只给一个"标注"按钮的话，玩家得**自己记住**哪些标过、哪些没标、哪些关系还差 ——
     * SolidWorks 之所以好用，是因为它把这些**摆出来**：标过的显示数值，
     * 没标的显示当前值（参考/从动尺寸），能补的关系直接给一键。
     *
     * <p>⚠ 已有尺寸要显示**实测值**而不只是约束里的常数：两者不一致说明求解器还没算平
     *（欠约束或约束矛盾）。只显示常数会让玩家看到"标着 50 而线其实是 47"。
     */
    private static void buildDimensionPanel(final com.ovo.sablestopnow.client.gui.lib.Container body,
                                           final com.ovo.sablestopnow.sketch.SketchSession session,
                                           final com.ovo.sablestopnow.sketch.Sketch live) {
        final var existing = com.ovo.sablestopnow.sketch.SketchMeasure.existingDimensions(live);
        final var refs = com.ovo.sablestopnow.sketch.SketchMeasure.referenceDimensions(live);
        final var relations = com.ovo.sablestopnow.sketch.SketchMeasure.impliedRelations(live, 1.0);
        if (existing.isEmpty() && refs.isEmpty() && relations.isEmpty()) {
            return;
        }
        body.add(new com.ovo.sablestopnow.client.gui.lib.Separator());
        body.add(new Label(Component.translatable("model.dim.title")).color(0xFF8FA0D8));        // ★ **显眼入口** ✓：工具行折成两行后「智能尺寸」可能在第二行不好找 ✗（用户实测反馈）
        //   这里再给一个**整行**按钮 ✓（面板行是 FILL 宽 ✓ 一眼就能看到 ✓）
        //   两个入口改的是同一个状态（session.tool）✓ ⇒ 不会各说一套 ✗。
        final var openSmart = new Button(Component.translatable("model.smart.open"), () -> {
            com.ovo.sablestopnow.client.SketchClient.mode().setTool(
                    com.ovo.sablestopnow.sketch.SketchSession.Tool.DIMENSION);
            refreshLeftPanel();
        });
        openSmart.toggled = session.tool()
                == com.ovo.sablestopnow.sketch.SketchSession.Tool.DIMENSION;
        body.add(openSmart);
        //   只列**关系**（水平/竖直/平行/垂直/等长/中点/重合/固定 ✓）✗——
        //   尺寸已经在上面那份列表里了 ✓ 两边都列会让人以为标重了 ✗。
        //   用统一库的 Button + hboxWrap ✓ ⇒ 窄了自动折行 ✓ 文字自动短标签 ✓ 不会遮盖 ✓
        for (int ci = 0; ci < live.constraints().size(); ci++) {
            final var c = live.constraints().get(ci);
            final String nameKey = relationNameKey(c.kind());
            if (nameKey == null) {
                continue; // 尺寸类 ✓ 上面已经列过 ✓
            }
            final int index = ci;
            final var row = new com.ovo.sablestopnow.client.gui.lib.Container();
            row.layout = com.ovo.sablestopnow.client.gui.lib.Layouts.hboxWrap(0, 4, 2);
            row.prefW = com.ovo.sablestopnow.client.gui.lib.Widget.FILL;
            row.prefH = com.ovo.sablestopnow.client.gui.lib.Widget.AUTO;
            row.add(new Label(Component.translatable(nameKey)).color(
                    session.selectedConstraint() == index ? 0xFFFFCC55 : 0xFFB9C4E8));
            row.add(new Button(Component.translatable("model.sketch.constraint_delete"), () -> {
                session.selectConstraint(index);
                if (session.deleteSelectedConstraint()) {
                    com.ovo.sablestopnow.client.SketchClient.mode().solve();
                    refreshLeftPanel();
                }
            }));
            body.add(row);
        }
        for (final var d : existing) {
            // 实测 ≠ 常数 → 求解器还没算平，用警示色并同时给出两个数
            final boolean off = Math.abs(d.measured() - d.value()) > 1.0e-4;
            final String text = off
                    ? String.format(java.util.Locale.ROOT, "%s %.3f  (实测 %.3f)",
                            Component.translatable(d.labelKey()).getString(), d.value(),
                            d.measured())
                    : String.format(java.util.Locale.ROOT, "%s %.3f",
                            Component.translatable(d.labelKey()).getString(), d.value());
            body.add(new Label(Component.literal(text))
                    .color(off ? 0xFFFF8A5C : 0xFFE6E6E6));
        }
        for (final var d : refs) {
            // 参考尺寸：灰显，明说"未约束"
            body.add(new Label(Component.translatable("model.dim.reference",
                    Component.translatable(d.labelKey()),
                    String.format(java.util.Locale.ROOT, "%.3f", d.value())))
                    .color(0xFF8C93A8));
        }
        for (final var r : relations) {
            body.add(new Button(Component.translatable("model.relation.add",
                    Component.translatable(r.labelKey())), () -> {
                        // ⚠ 求解在 mode 上（和测量区那边一致），session 上没有 solve
                        if (session.applyRelation(r.solverKind(), r.vars())) {
                            com.ovo.sablestopnow.client.SketchClient.mode().solve();
                        }
                        refreshLeftPanel();
                    }).width(Widget.FILL));
        }
    }

    /** "重选草图"按钮（两种成形方式共用）。 */
    private static Button reselectButton() {
        final Button button = new Button(Component.translatable("model.extrude.reselect"), () -> {
            extrudeSource = null;
            com.ovo.sablestopnow.client.SketchRenderer.clearRevolveAxis();
            refreshLeftPanel();
        });
        // ⚠ 用 prefW 而不是 .width(...)：后者返回的是基类型 Widget，不是 Button
        button.prefW = Widget.FILL;
        return button;
    }

    /**
     * 用当前选中的草图 + 轴 + 角度建一个**旋转**实体。
     *
     * <p>⚠ 旋转体目前**不参与碰撞**（斜几何要用 OBB，还没做）——所以这里不检查"能不能拉出东西"，
     * 只检查轴选没选。
     */
    private static void createRevolve() {
        final com.ovo.sablestopnow.sketch.Sketch source = currentExtrudeSource();
        if (source == null) {
            ModelMode.prompt("model.prompt.extrude_pick_sketch");
            return;
        }
        if (revolveAxis == null) {
            ModelMode.prompt("model.revolve.no_axis");
            return;
        }
        final com.ovo.sablestopnow.sketch.Revolve.Params params =
                new com.ovo.sablestopnow.sketch.Revolve.Params(
                        revolveAxis[0], revolveAxis[1], revolveAxis[2], revolveAxis[3],
                        revolveAngle.get(), revolveDirection);
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.world.level.Level level = mc == null ? null : mc.level;
        int index = 1;
        if (level != null) {
            for (final com.ovo.sablestopnow.sketch.Solid s
                    : com.ovo.sablestopnow.sketch.SolidClientState.ofBody(level, source.body())) {
                index = Math.max(index, s.index() + 1);
            }
        }
        com.ovo.sablestopnow.network.SolidNetworking.sendSave(
                com.ovo.sablestopnow.sketch.Solid.createRevolution(
                        source.body(), source.id(), index, params));
        ModelMode.prompt("model.prompt.extrude_created");
        refreshLeftPanel();
    }

    /** 拉伸/旋转的切换按钮。 */
    private static Button shapeButton(final String labelKey, final boolean revolve) {
        final boolean active = revolveMode == revolve;
        final Button button = new Button(
                active ? Component.translatable(labelKey).withStyle(net.minecraft.ChatFormatting.GOLD)
                        : Component.translatable(labelKey),
                () -> {
                    revolveMode = revolve;
                    // ⚠ 切回拉伸 / 丢掉草图时要把 3D 里的轴高亮一起清掉，
                    // 否则屏幕上会留着一条"已经没有意义的参考线"。
                    if (!revolve) {
                        com.ovo.sablestopnow.client.SketchRenderer.clearRevolveAxis();
                    }
                    refreshLeftPanel();
                });
        button.prefW = Widget.FILL;
        return button;
    }

    /** 旋向按钮（+1 / −1）。 */
    private static Button revolveDirButton(final String labelKey, final int direction) {
        final boolean active = revolveDirection == direction;
        final Button button = new Button(
                active ? Component.translatable(labelKey).withStyle(net.minecraft.ChatFormatting.GOLD)
                        : Component.translatable(labelKey),
                () -> {
                    revolveDirection = direction;
                    refreshLeftPanel();
                });
        button.prefW = Widget.FILL;
        return button;
    }

    /**
     * 拉伸方式按钮（单侧 / 两侧 / 对称）。
     *
     * <p>与草图工具按钮同一套做法：**高亮状态在建的时候现读一次**，不订阅 ——
     * 订阅写在建面板里的话，左栏每重建一次就多挂一条回调。
     */
    private static Button extrudeModeButton(final String labelKey,
                                            final com.ovo.sablestopnow.sketch.Extrude.Mode mode) {
        final boolean active = extrudeMode == mode;
        final Button button = new Button(
                active ? Component.translatable(labelKey).withStyle(net.minecraft.ChatFormatting.GOLD)
                        : Component.translatable(labelKey),
                () -> {
                    extrudeMode = mode;
                    refreshLeftPanel();
                });
        button.prefW = Widget.FILL;
        return button;
    }

    /**
     * **关系型**约束按钮（多选时用）。
     *
     * <p>与 {@link #constraintButton} 的区别：那边的"成功/失败该提示什么"是固定的，
     * 这边的失败原因取决于**选中的是什么**（等长要两条线、同心要两个圆），
     * 所以把提示交给调用方，避免这里瞎猜。
     *
     * @param action 返回 true = 加上了；false = 没加上（调用方已经给过提示）
     */
    private static Button relationalButton(final String labelKey,
                                           final java.util.function.BooleanSupplier action) {
        final Button button = new Button(Component.translatable(labelKey), () -> {
            if (action.getAsBoolean()) {
                ModelMode.prompt("model.sketch.constraint.added");
            }
            refreshLeftPanel();
        });
        button.prefW = Widget.FILL;
        return button;
    }

    /**
     * 草图环境里的绘制工具按钮。
     *
     * <p>高亮状态在**建的时候现读一次**（{@code toggled}），不订阅 —— 见调用处的说明。
     */
    private static Button sketchToolButton(final String labelKey,
                                           final com.ovo.sablestopnow.sketch.SketchSession.Tool value,
                                           final com.ovo.sablestopnow.sketch.SketchSession session) {
        final Button button = new Button(Component.translatable(labelKey), () -> {
            com.ovo.sablestopnow.client.SketchClient.mode().setTool(value);
            refreshLeftPanel();
        });
        // ⚠ 不能用 FILL ✗：hboxFit 的比例缩放会跳过 FILL 子控件（源码 L164 ✓）
        //   ⇒ 用确定宽度，让"放不下就缩"的机制真正作用到它 ✓（配合 Label 的自动短标签/缩字号 ✓）
        button.prefW = 26;
        button.toggled = session.tool() == value;
        return button;
    }

    /** 求解器约束类型 → 名称 lang key ✓；**尺寸类**返回 null（列表里不重复列 ✓）。 */
    private static String relationNameKey(final int kind) {
        return switch (kind) {
            case com.ovo.sablestopnow.sketch.SketchSolver.HORIZONTAL -> "model.relation.horizontal";
            case com.ovo.sablestopnow.sketch.SketchSolver.VERTICAL -> "model.relation.vertical";
            case com.ovo.sablestopnow.sketch.SketchSolver.PARALLEL -> "model.relation.parallel";
            case com.ovo.sablestopnow.sketch.SketchSolver.PERPENDICULAR -> "model.relation.perpendicular";
            case com.ovo.sablestopnow.sketch.SketchSolver.EQUAL_LEN -> "model.relation.equal_len";
            case com.ovo.sablestopnow.sketch.SketchSolver.MIDPOINT -> "model.relation.midpoint";
            case com.ovo.sablestopnow.sketch.SketchSolver.COINCIDENT -> "model.relation.coincident";
            case com.ovo.sablestopnow.sketch.SketchSolver.FIXED -> "model.relation.fixed";
            default -> null;
        };
    }

    private static void fitView() {
        final UUID id = ModelMode.selectedModel();
        if (id != null) {
            ModelCamera.focus(id);
        } else {
            ModelCamera.zoomTo(16.0);
        }
    }

    /** 只把选中的模型摆到视野正中，**不动距离**（与「适配」的区别就在这儿）。 */
    /**
     * debug tab: mark / unmark the currently selected model as the TEST CYLINDER.
     *
     * Clicking the same object twice unmarks it (the button acts as a toggle), and the
     * current mark is printed to the log and returned by TestMarks.hudLine() so the top bar
     * can show it - the player can then confirm at a glance that the right object is marked.
     */
    public static void markTestCylinder() {
        final java.util.UUID id = ModelMode.selectedModel();
        if (id == null) {
            com.ovo.sablestopnow.SablestopNow.LOGGER.warn("[test-mark] 没有选中任何实体 —— 先选中要测试的圆柱实体");
            return;
        }
        final boolean on = com.ovo.sablestopnow.client.model.TestMarks.markCylinder(id, -1);
        com.ovo.sablestopnow.SablestopNow.LOGGER.warn("[test-mark] 测试圆柱 {} : {}", on ? "已标记" : "已取消", id);
    }

    /**
     * debug tab: mark / unmark the currently selected model as the TEST PHYSICS STRUCTURE.
     *
     * Same toggle semantics as markTestCylinder. The structure is what gets placed on or
     * under the cylinder, so both ends of the contact are pinned and the trace can be read
     * without guessing which row is which.
     */
    public static void markTestStructure() {
        final java.util.UUID id = ModelMode.selectedModel();
        if (id == null) {
            com.ovo.sablestopnow.SablestopNow.LOGGER.warn("[test-mark] 没有选中任何对象 —— 先选中要测试的物理结构");
            return;
        }
        final boolean on = com.ovo.sablestopnow.client.model.TestMarks.markStructure(id, -1);
        com.ovo.sablestopnow.SablestopNow.LOGGER.warn("[test-mark] 测试物理结构 {} : {}", on ? "已标记" : "已取消", id);
    }
    public static void centerSelectedModel() {
        final UUID id = ModelMode.selectedModel();
        if (id == null) {
            ModelMode.prompt("model.prompt.no_model");
            return;
        }
        if (!ModelCamera.centerOn(id)) {
            ModelMode.prompt("model.prompt.no_model");
        }
    }

    // ============ 右边栏：树状图 ============

    private static void buildTree() {
        UiLayer layer = Ui.layer(LAYER_TREE);
        if (layer == null) {
            layer = Ui.open(UiLayer.of(LAYER_TREE).size(0, 0));
        }
        layer.root.clear();
        layer.root.layout = Layouts.dock(4, 4);
        layer.hud = true;

        final com.ovo.sablestopnow.client.gui.lib.CollapsiblePanel panel =
                new com.ovo.sablestopnow.client.gui.lib.CollapsiblePanel(false);
        // 从右边滑入
        layer.slideFromX = 150;
        layer.slideFromY = 0;
        treePanelRef = panel;
        panel.title(Component.translatable("model.tree.title"));
        panel.dock = Layouts.Dock.RIGHT;
        // 按屏幕比例自适应（小窗口下固定像素宽度会吃掉大半屏，见 CollapsiblePanel 注释）
        panel.fraction(0.24f, 92, 150);
        panel.layout = Layouts.vbox(3, 3);

        final TreeView view = new TreeView();
        view.prefH = Widget.FILL;
        view.rowH = 12;
        view.indent = 7;
        view.rowBuilder = ModelUi::buildRow;
        view.onSelect = item -> {
            // ⚠ 树上的四种选择是**互斥**的。以前只有「耦合」之间互清，施力点的选择会一直留着，
            // 于是左栏被钉死在施力点编辑器上：点模型、点配合都不换内容
            //（用户实测反馈「左侧栏未跟随树状图的选择而变化」）。
            if (item.data instanceof UUID bodyId) {
                // ⚠ **贴图面板必须是"可被别的选择顶掉"的**。以前它有最高优先级且没人清它，
                //   于是"在贴图面板里点草图，左栏还是贴图"（用户实测反馈）。
                //   树上的五种选择本来就是互斥的，贴图也算其中一种。
                textureSolidId = null;
                textureBlockQuery.set("");
                ModelCouplingUi.clearSelection();
                ModelForceUi.select(null);
                MateClientState.setHighlightedMate(null);
                ModelMode.selectModel(bodyId);
                // 树上的「＋ 添加施力点」：直接进入放置流程
                if (item.key.startsWith("force_add:")) {
                    startTool(ModelTools.FORCE);
                    return;
                }
            } else if (item.data instanceof Mate mate) {
                textureSolidId = null;
                textureBlockQuery.set("");
                ModelCouplingUi.clearSelection();
                ModelForceUi.select(null);
                MateClientState.setHighlightedMate(mate);
            } else if (item.data instanceof com.ovo.sablestopnow.force.ForcePoint point) {
                ModelCouplingUi.clearSelection();
                MateClientState.setHighlightedMate(null);
                ModelForceUi.select(point.id());
                return;
            } else if (item.data instanceof com.ovo.sablestopnow.sketch.Solid treeSolid) {
                // 树上的「贴图…」行：打开贴图编辑面板（搜索框 + 带预览的下拉）
                if (item.key.startsWith("texture:")) {
                    textureSolidId = treeSolid.id();
                    textureBlockQuery.set(treeSolid.appearance().blockId() == null ? ""
                            : treeSolid.appearance().blockId());
                    textureScale.set(treeSolid.appearance().blocksPerTile());
                    // 立刻**强制**重建：不靠"下一次周期性刷新"，也不被签名早退挡住
                    lastLeftSignature = null;
                    refreshLeftPanel();
                }
                ModelCouplingUi.clearSelection();
                ModelForceUi.select(null);
                MateClientState.setHighlightedMate(null);
            } else if (item.data instanceof com.ovo.sablestopnow.sketch.Sketch sketch) {
                // 选草图（无论是"选为拉伸源"还是"进编辑"）都要让贴图面板让位
                textureSolidId = null;
                textureBlockQuery.set("");
                // 拉伸工具下点草图 = **选为拉伸源**，不是进编辑环境。
                // 这是这个工具存在的唯一理由：草图默认的点击行为是"编辑"，
                // 所以没有"选中但未编辑"的状态可以挂深度输入框。
                if (ModelTools.EXTRUDE.equals(ModelMode.tool())) {
                    selectExtrudeSource(sketch);
                    return;
                }
                // 点树里的草图 = 进入它的编辑环境（里程碑的「可再次编辑」）
                ModelForceUi.select(null);
                ModelCouplingUi.clearSelection();
                MateClientState.setHighlightedMate(null);
                com.ovo.sablestopnow.client.SketchClient.edit(sketch);
                ModelMode.setTool(ModelTools.SKETCH);
                ModelMode.selectModel(sketch.body());
                refreshLeftPanel();
                return;
            } else if (item.data instanceof com.ovo.sablestopnow.sketch.Solid solid) {
                // 点拉伸实体：只把它的结构选中（左栏的拉伸页会把可删的实体列出来）。
                // 不做"点一下就删"—— 删除是不可逆的，必须走左栏那个明确的按钮。
                ModelForceUi.select(null);
                ModelCouplingUi.clearSelection();
                MateClientState.setHighlightedMate(null);
                ModelMode.selectModel(solid.body());
                refreshLeftPanel();
                return;
            } else if (item.data instanceof com.ovo.sablestopnow.server.CouplingData.RatioGroup group) {
                ModelForceUi.select(null);
                MateClientState.setHighlightedMate(null);
                ModelCouplingUi.selectRatio(group.id());
                return;
            } else if (item.data instanceof com.ovo.sablestopnow.server.CouplingData.GearCoupling gear) {
                ModelForceUi.select(null);
                MateClientState.setHighlightedMate(null);
                ModelCouplingUi.selectGear(gear.id());
                return;
            }
            refreshLeftPanel();
        };
        view.onSecondary = item -> {
            if (item.data instanceof Mate mate) {
                MateClientState.setHighlightedMate(mate);
                refreshLeftPanel();
            }
        };
        tree = view;
        panel.add(view);

        final Container footer = new Container();
        footer.layout = Layouts.hbox(0, 2);
        footer.prefH = 13;
        footer.add(new Button(Component.translatable("model.tree.refresh"), () -> {
            refreshTree();
        }).width(40));
        footer.add(new Button(Component.translatable("model.tree.add_force"),
                () -> startTool(ModelTools.FORCE)).width(Widget.FILL));
        panel.add(footer);

        layer.root.add(panel);
        layer.visible = treeVisible;
        // ⚠ 树是新建的、空的；但内容指纹和上次一样，refreshTree 会直接 return 留下空树
        //（用户实测：「再进模型模式右边栏一片空白」）。清掉指纹强制铺一次。
        lastTreeSignature = null;
        refreshTree(true);
    }

    /** 立刻重建树（外部数据变化时用，例如刚建好一条配合）。 */
    public static void refreshTreeNow() {
        refreshTree(true);
    }

    /**
     * 让左右栏从「上边栏下方」开始。
     *
     * <p>三个边栏各在自己的层里（这样能各自折叠/开关），层间互不知情：不处理的话左右栏会顶到
     * 屏幕上沿、和上边栏叠在一起。用户要求「左右边栏减少高度、把上方让给上边栏」，所以这里给
     * 它们的层留出上边栏的高度。
     */
    private static void layoutSidesBelowTop() {
        final int top = TOP_H + 8;
        final UiLayer left = Ui.layer(LAYER_LEFT);
        if (left != null) {
            left.root.padding(4, top, 4, 4);
        }
        final UiLayer treeLayer = Ui.layer(LAYER_TREE);
        if (treeLayer != null) {
            treeLayer.root.padding(4, top, 4, 4);
        }
    }

    /** 树：模型 → [配合 / 属性 / 可控施力点]。 */
    private static void refreshTree() {
        refreshTree(false);
    }

    /**
     * 重建树。
     *
     * @param force true = 无视签名强制重建（数据确实变了时用）
     */
    private static void refreshTree(final boolean force) {
        final TreeView view = tree;
        if (view == null) {
            return;
        }
        // 刷新是「重建整棵树」：TreeItem 是新建的，展开状态会丢。
        // 不记住的话，玩家刚展开一个节点，12 帧后就会被收起（而且展开动画还会重播一次），
        // 表现出来就是「树自己在抖」。所以每次重建前先记下、建完再按 key 还原。
        rememberExpansion(view.roots());

        final Minecraft mc = Minecraft.getInstance();
        final List<TreeView.TreeItem> roots = new ArrayList<>();
        if (mc.level != null) {
            final ClientSubLevelContainer container = (ClientSubLevelContainer) SubLevelContainer.getContainer(mc.level);
            if (container != null) {
                final UUID selected = ModelMode.selectedModel();
                for (final ClientSubLevel sub : container.getAllSubLevels()) {
                    if (sub.isRemoved()) {
                        continue;
                    }
                    final UUID id = sub.getUniqueId();
                    // ⚠ 隐藏的模型**也要留在树里**：隐藏开关本身就在这棵树的下挂属性里，
                    // 一旦从这里过滤掉，玩家就再也点不到那个勾选框，只能靠「隐藏/显示」按钮
                    // 碰运气（用户实测反馈「选择隐藏模型时模型从树状图里消失」）。
                    final boolean hidden = ModelProperties.hidden(id);
                    final TreeView.TreeItem root = new TreeView.TreeItem("model:" + id)
                            .label(hidden
                                    ? Component.literal(shortId(id) + "  ")
                                            .append(Component.translatable("model.tree.hidden_tag"))
                                    : Component.literal(shortId(id)));
                    root.data = id;
                    root.highlight = id.equals(selected);
                    // 默认展开（玩家手动收起过才会被 applyRememberedExpansion 覆盖）
                    root.expanded = true;
                    root.child(mateBranch(id));
                    root.child(sketchBranch(id));
                    root.child(solidsBranch(id));
                    root.child(propertyBranch(id));
                    root.child(forceBranch(id));
                    roots.add(root);
                }
            }
        }
        applyRememberedExpansion(roots);
        // 数据没变就不要再 setRoots：重建会换掉 TreeItem（连同它的展开动画 Anim），
        // 于是「玩家刚收起 → 12 帧后重建又展开 → 动画再收」表现为反复抽动（用户实测反馈）。
        // 只有内容真的变了（或调用方强制）才重建。
        final String signature = signatureOf(roots);
        // 空树永远不能「因为指纹没变」而跳过：面板重建后 view 是新的、roots 是空的，
        // 指纹却和上一轮相同 —— 那样右边栏就永久空白（用户实测反馈）。
        if (!force && !view.roots().isEmpty() && signature.equals(lastTreeSignature)) {
            return;
        }
        lastTreeSignature = signature;
        view.setRoots(roots);
        lastRootCount = roots.size();
        lastRowCount = view.rowCount();
        // 一个模型都没有时给一句提示，而不是留一片空白 ——
        // 「树是空的」既可能是没数据，也可能是没画出来，玩家分不清，我们也一样（要靠日志）。
        if (roots.isEmpty()) {
            final TreeView.TreeItem placeholder = new TreeView.TreeItem("empty")
                    .label(Component.translatable("model.tree.empty"));
            placeholder.selectable = false;
            view.setRoots(List.of(placeholder));
        }
    }

    private static int lastRootCount;
    private static int lastRowCount;
    /** 左栏内容指纹：只有这些输入变化时才重建（见 {@link #refreshLeftPanel()}）。 */
    @Nullable
    private static String lastLeftSignature;

    /** 草图尺寸输入框的当前值（静态：左栏重建时不能丢）。 */
    private static final com.ovo.sablestopnow.client.gui.lib.Prop<Double> sketchDimension =
            com.ovo.sablestopnow.client.gui.lib.Prop.of("sketch.dimension", 10.0);
    /** 尺寸输入框的命令名。 */
    private static final String CMD_SKETCH_DIM = "sketch.dimension";

    /** 拉伸深度输入框的当前值（静态：左栏重建时不能丢）。 */
    private static final com.ovo.sablestopnow.client.gui.lib.Prop<Double> extrudeDepth =
            com.ovo.sablestopnow.client.gui.lib.Prop.of("extrude.depth", 2.0);
    /** 拉伸深度输入框的命令名。 */
    private static final String CMD_EXTRUDE_DEPTH = "extrude.depth";
    /** 第二侧深度（仅"两侧"用）。与主深度一样放静态 Prop，左栏重建不能丢值。 */
    private static final com.ovo.sablestopnow.client.gui.lib.Prop<Double> extrudeBackDepth =
            com.ovo.sablestopnow.client.gui.lib.Prop.of("extrude.depth2", 2.0);
    private static final String CMD_EXTRUDE_BACK_DEPTH = "extrude.depth2";
    /**
     * 当前拉伸方式。默认单侧。
     *
     * <p>⚠ 必须进 {@code leftPanelSignature()} —— 不进的话切了方式左栏不重建，
     * 深度输入框的数量不会变（"两侧"要多一个反向深度框），看起来就是"点了没反应"。
     */
    private static com.ovo.sablestopnow.sketch.Extrude.Mode extrudeMode =
            com.ovo.sablestopnow.sketch.Extrude.Mode.ONE_SIDED;

    /** 当前是在配"拉伸"还是"旋转"。 */
    private static boolean revolveMode;
    /**
     * 选中的旋转轴：{@code {axisU, axisV, dirU, dirV}}（**坐标**，不是图元引用）。
     *
     * <p>⚠ 存坐标而不是"第几条线"：图元是 record、没有跨同步的稳定身份，
     * 记下标的话草图一改就指到别的线上去了。
     */
    @Nullable
    private static double[] revolveAxis;
    /** 旋转角度（度）。与其它输入框一样放静态 Prop，左栏重建不能丢值。 */
    private static final com.ovo.sablestopnow.client.gui.lib.Prop<Double> revolveAngle =
            com.ovo.sablestopnow.client.gui.lib.Prop.of("revolve.angle", 360.0);
    private static final String CMD_REVOLVE_ANGLE = "revolve.angle";
    /** 旋向：+1 / −1。 */
    private static int revolveDirection = 1;

    /**
     * 正在编辑**贴图**的那个实体（树里点过它的「贴图」按钮）。
     *
     * <p>⚠ 必须进 {@code leftPanelSignature()} —— 不进的话点了「贴图」左栏不重建，
     * 贴图编辑区不会出现，表现为"点了没反应"。
     */
    @Nullable
    private static java.util.UUID textureSolidId;

    /** 贴图平铺比例：几个方块铺一次。 */
    private static final com.ovo.sablestopnow.client.gui.lib.Prop<Double> textureScale =
            com.ovo.sablestopnow.client.gui.lib.Prop.of("solid.texture.scale", 1.0);
    private static final String CMD_TEXTURE_SCALE = "solid.texture.scale";

    /**
     * 贴图搜索框里的**查询文本**（可输入 + 实时补全的候选来源）。
     *
     * <p>和 {@link #textureSolidId} 一样必须进 {@code leftPanelSignature()} ——
     * 否则打字时面板不会刷新，看起来像输入框坏了。
     */
    private static final com.ovo.sablestopnow.client.gui.lib.Prop<String> textureBlockQuery =
            com.ovo.sablestopnow.client.gui.lib.Prop.of("solid.texture.query", "");
    private static final String CMD_TEXTURE_BLOCK = "solid.texture.block";

    /** 内联候选显示几行（面板内的按钮行，不走弹出层）。 */
    private static final int TEXTURE_CANDIDATE_ROWS = 6;

    /**
     * **已提交**的查询词（回车那一刻固化下来），用于静态生成候选列表。
     *
     * <p>为什么和 {@link #textureBlockQuery} 分开：输入框里的文本是"正在编辑的内容"，
     * 而候选列表要的是"上一次提交的内容"。混用会导致"边打字边重建面板"
     * （把正在打字的输入框换掉），或者"候选跟着没提交的文本乱变"。
     */
    private static final com.ovo.sablestopnow.client.gui.lib.Prop<String> textureSearchApplied =
            com.ovo.sablestopnow.client.gui.lib.Prop.of("solid.texture.applied", "");

    /**
     * 当前查询下的第 {@code index} 个候选（注册名）；没有就返回 null。
     *
     * <p>**每次调用都重算**：候选行的按钮标签是活取值，所以要跟着"当前查询"走，
     * 不能在建面板时算一次就定死（那样打字时列表不会变）。
     */
    @Nullable
    private static String textureCandidate(final int index) {
        final String q = textureBlockQuery.get() == null ? "" : textureBlockQuery.get().trim();
        if (q.isEmpty() || index < 0) {
            return null;
        }
        final java.util.List<String> hits = com.ovo.sablestopnow.sketch.SolidAppearance
                .searchBlocks(allBlockIds(), q, TEXTURE_CANDIDATE_ROWS);
        return index < hits.size() ? hits.get(index) : null;
    }

    /** 全部方块 id（搜索候选），只算一次。 */
    @Nullable
    private static java.util.List<String> allBlockIdsCache;

    /**
     * 全部方块 id，按字典序排好。
     *
     * <p>⚠ 只算一次：候选列表**每帧**都会被 {@code ComboBox} 投影一遍，
     * 在这里现遍历注册表（几千个方块 × 每帧）是白白烧 CPU。
     */
    private static java.util.List<String> allBlockIds() {
        if (allBlockIdsCache == null) {
            final java.util.List<String> ids = new java.util.ArrayList<>();
            for (final net.minecraft.resources.ResourceLocation id
                    : net.minecraft.core.registries.BuiltInRegistries.BLOCK.keySet()) {
                ids.add(id.toString());
            }
            ids.sort(String::compareTo);
            allBlockIdsCache = ids;
        }
        return allBlockIdsCache;
    }
    /**
     * 当前选中的**拉伸源草图** id。
     *
     * <p>必须是"已保存"的草图 —— 正在编辑的那张还没进 {@code SketchData}，
     * 服务端 {@code SaveSolidPayload} 会以"引用的草图不存在"为由拒掉。
     */
    @Nullable
    private static java.util.UUID extrudeSource;

    /**
     * **验证用**：在当前选中的结构上造一个**半径 1 的球体**（带描边）。
     *
     * <h2>为什么用"旋转半圆"而不是新写一个球体图元</h2>
     * 球就是**上半圆绕它的直径转 360°** —— 用现有的草图 + 旋转成形就能得到，
     * 于是它天然带着我们已经验证过的三样东西：**网格描边**（紫线框）、
     * **碰撞盒**（F3 可见）、以及**贴图**。新写一个球体图元只会多出一条没被验证的代码路径。
     *
     * <p>⚠ 这也是"验证类功能"该有的样子：**用正式功能的组合去造测试对象**，
     * 而不是给测试单开一条捷径 —— 捷径恰恰会绕开你想验证的那段代码。
     *
     * <p>失败时一律**明说原因**（没选结构 / 没有世界），不给一个点了没反应的按钮。
     */
    private static void spawnDebugSphere() {
        final java.util.UUID body = ModelMode.selectedModel();
        if (body == null) {
            ModelMode.prompt("model.debug.need_body");
            return;
        }
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.world.level.Level level = mc == null ? null : mc.level;
        if (level == null) {
            return;
        }
        // 序号取 max+1（不是数量+1）—— 删掉中间那个再建时，数量法会撞上还活着的同名实体
        int index = 1;
        for (final com.ovo.sablestopnow.sketch.Solid s
                : com.ovo.sablestopnow.sketch.SolidClientState.ofBody(level, body)) {
            index = Math.max(index, s.index() + 1);
        }
        // ⚠⚠ **平面原点必须是 plot 局部系里的结构位置，不能图省事用 (0,0,0)。**
        //   plot 局部坐标是 2e7 量级，结构与原点差得极远 —— 球会被造在虚空里，
        //   表现就是用户实测的「生成后看不见，也没有碰撞」。
        //   仓库里已经记录过同一个坑（SketchClient 的注释）：
        //   把世界系当局部系用会让基准板"画到结构外很远的地方（表现为什么都看不到）"。
        //   这里反过来也一样：拿对坐标系才行。
        //   做法与 SketchClient.begin 完全一致：世界包围盒 → 位姿逆变换 → 逐轴取 min/max。
        dev.ryanhcode.sable.sublevel.ClientSubLevel sub = null;
        if (dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level)
                instanceof final dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer c) {
            for (final dev.ryanhcode.sable.sublevel.ClientSubLevel s : c.getAllSubLevels()) {
                if (s.getUniqueId().equals(body)) {
                    sub = s;
                }
            }
        }
        if (sub == null) {
            ModelMode.prompt("model.debug.need_body");
            return;
        }
        final dev.ryanhcode.sable.companion.math.BoundingBox3dc bb = sub.boundingBox();
        final dev.ryanhcode.sable.companion.math.Pose3dc pose = sub.renderPose();
        final org.joml.Vector3d worldMin =
                new org.joml.Vector3d(bb.minX(), bb.minY(), bb.minZ());
        final org.joml.Vector3d worldMax =
                new org.joml.Vector3d(bb.maxX(), bb.maxY(), bb.maxZ());
        final org.joml.Vector3d a = pose.transformPositionInverse(worldMin,
                new org.joml.Vector3d());
        final org.joml.Vector3d b2 = pose.transformPositionInverse(worldMax,
                new org.joml.Vector3d());
        final org.joml.Vector3d lo = a.min(b2, new org.joml.Vector3d());
        final org.joml.Vector3d hi = a.max(b2, new org.joml.Vector3d());
        // 放在包围盒中心**上方**：埋进方块里同样会"看不见"
        final org.joml.Vector3d origin = lo.add(hi, new org.joml.Vector3d()).mul(0.5);
        origin.y = hi.y + 2.0;
        final com.ovo.sablestopnow.sketch.SketchPlane plane =
                com.ovo.sablestopnow.sketch.SketchPlane.base(
                        com.ovo.sablestopnow.sketch.SketchPlane.Base.FRONT, origin);
        final com.ovo.sablestopnow.sketch.Sketch sketch =
                new com.ovo.sablestopnow.sketch.Sketch(java.util.UUID.randomUUID(), body,
                        "DebugSphere", plane);
        // 上半圆（0 → π）+ 直径：闭环构成半圆区域，直径本身同时充当回转轴
        sketch.addArc(0.0, 0.0, 1.0, 0.0, Math.PI);
        sketch.addLine(-1.0, 0.0, 1.0, 0.0);
        // ==============================================================
        // ⚠ **发出去之前先本地预检** —— 用渲染器将来会用的**同一个计算**。
        // ==============================================================
        // 服务端对"草图找不到 / 解不出区域"的实体是**静默丢弃**的，客户端什么提示都没有，
        // 表现就是"生成了但看不见、也没碰撞"（用户实测报的）。静默失败最难查，
        // 所以在这里先自己算一遍：区域解不出来、或旋转出来的网格是空的，就**当场说明原因**。
        final com.ovo.sablestopnow.sketch.Revolve.Params params =
                new com.ovo.sablestopnow.sketch.Revolve.Params(0.0, 0.0, 1.0, 0.0, 360.0, 1);
        final java.util.List<com.ovo.sablestopnow.sketch.SketchProfiles.Region> regions =
                com.ovo.sablestopnow.sketch.SketchProfiles.nest(
                        com.ovo.sablestopnow.sketch.SketchProfiles.findLoops(sketch));
        if (regions.isEmpty()) {
            // 弧和直径是**四个独立变量**，端点只是几何上重合 —— 闭环靠焊接容差认出来。
            // 认不出来就说明这俩端点没接上，那样转出来的东西是空的。
            ModelMode.prompt("model.debug.no_region");
            return;
        }
        final com.ovo.sablestopnow.sketch.SolidMesh.Mesh probeMesh =
                com.ovo.sablestopnow.sketch.Revolve.mesh(sketch, params);
        if (probeMesh.isEmpty()) {
            ModelMode.prompt("model.debug.no_mesh");
            return;
        }
        com.ovo.sablestopnow.network.SketchNetworking.sendSave(sketch);
        com.ovo.sablestopnow.network.SolidNetworking.sendSave(
                com.ovo.sablestopnow.sketch.Solid.createRevolution(body, sketch.id(), index,
                        params));
        ModelMode.prompt("model.debug.sphere_done");
        refreshLeftPanel();
    }

    /**
     * **验证用**：跑一次「体素碰撞体」实验（服务端执行）。
     *
     * <p>物理在服务端，所以这里只负责把"在哪个结构上做"发过去；真正的实验在
     * {@link com.ovo.sablestopnow.server.VoxelColliderTestService}。
     *
     * <p>⚠⚠ **同时**在本机造一个可见的球体（带紫色描边），位置与装置对齐。
     * 用户定的规矩：「**加测试方块一定要有描边**」——
     * 装置本身是**纯碰撞体、不会被渲染**，看不见的东西根本没法验证。
     * 所以必须配一个可见参照：看着球体的描边去撞隐形碰撞体，
     * 才能判断"隐形碰撞体是否与可见形状吻合"。
     */
    private static void runVoxelColliderTest() {
        final java.util.UUID body = ModelMode.selectedModel();
        if (body == null) {
            ModelMode.prompt("model.debug.need_body");
            return;
        }
        // 先造可见参照（客户端），再发实验请求（服务端）—— 两者位置对齐
        spawnDebugSphere();
        // ⚠ 用户定的规矩：**测试物体必须有描边**。装置本身不可渲染，
        //   所以在这里打开它的线框（位置与服务端同一套计算）。
        com.ovo.sablestopnow.client.SketchRenderer.showVoxelDebugOutline(body);
        com.ovo.sablestopnow.network.VoxelColliderTestNetworking.send(body);
        ModelMode.prompt("model.debug.voxel_sent");
    }

    /** 进入拉伸工具：先选一个结构，再在树里点一张草图作为拉伸源。 */
    public static void startExtrude() {        if (ModelMode.selectedModel() == null) {
            ModelMode.prompt("model.prompt.extrude_pick_body");
            return;
        }
        extrudeSource = null;
        ModelMode.setTool(ModelTools.EXTRUDE);
        ModelMode.prompt("model.prompt.extrude_pick_sketch");
        refreshLeftPanel();
    }

    /** 选为拉伸源。只接受**已保存**的草图（树里的草图都是已保存的）。 */
    public static void selectExtrudeSource(final com.ovo.sablestopnow.sketch.Sketch sketch) {
        extrudeSource = sketch.id();
        ModelMode.selectModel(sketch.body());
        refreshLeftPanel();
    }

    /** 当前拉伸源；没选或那张草图已经不在了就返回 null。 */
    @Nullable
    private static com.ovo.sablestopnow.sketch.Sketch currentExtrudeSource() {
        if (extrudeSource == null) {
            return null;
        }
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.world.level.Level level = mc == null ? null : mc.level;
        if (level == null) {
            return null;
        }
        return com.ovo.sablestopnow.sketch.SketchClientState.byId(level, extrudeSource);
    }

    /**
     * 用当前选中的草图 + 拉伸方式/深度建一个实体并提交。
     *
     * <p>序号取 {@code nextIndex}（max+1），不是数量 +1 —— 删掉中间那个再建时，
     * 数量法会撞上还活着的同名实体。
     *
     * <p>⚠ 单侧 / 两侧 / 对称三种方式在**模型层早就支持了**（{@code Extrude.Params}），
     * 这里只是把它接到 UI 上 —— 之前只建 {@code oneSided}，等于那两种能力一直够不着。
     */
    private static void createExtrude() {
        final com.ovo.sablestopnow.sketch.Sketch source = currentExtrudeSource();
        if (source == null) {
            ModelMode.prompt("model.prompt.extrude_pick_sketch");
            return;
        }
        final double d1 = extrudeDepth.get();
        final double d2 = extrudeBackDepth.get();
        final com.ovo.sablestopnow.sketch.Extrude.Params params =
                switch (extrudeMode) {
                    case TWO_SIDED -> com.ovo.sablestopnow.sketch.Extrude.Params.twoSided(d1, d2);
                    case SYMMETRIC -> com.ovo.sablestopnow.sketch.Extrude.Params.symmetric(d1);
                    default -> com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(d1);
                };
        // 先确认真的能拉出东西：轮廓不闭合 / 全是退化轮廓时给出提示，
        // 而不是建一个永远画不出来的空实体。
        if (com.ovo.sablestopnow.sketch.Extrude.boxesOf(source, params).isEmpty()) {
            ModelMode.prompt("model.prompt.extrude_no_profile");
            return;
        }
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.world.level.Level level = mc == null ? null : mc.level;
        int index = 1;
        if (level != null) {
            for (final com.ovo.sablestopnow.sketch.Solid s
                    : com.ovo.sablestopnow.sketch.SolidClientState.ofBody(level, source.body())) {
                index = Math.max(index, s.index() + 1);
            }
        }
        final com.ovo.sablestopnow.sketch.Solid solid = com.ovo.sablestopnow.sketch.Solid.create(
                source.body(), source.id(), index, params);
        com.ovo.sablestopnow.network.SolidNetworking.sendSave(solid);
        ModelMode.prompt("model.prompt.extrude_created");
        refreshLeftPanel();
    }

    /** 左栏内容的输入：功能、已选元素、已选类型、当前模型、选中的施力点/耦合。 */
    private static String leftPanelSignature() {
        final StringBuilder sb = new StringBuilder(ModelMode.tool());
        sb.append('#').append(ModelMode.selectedModel());
        sb.append('#').append(chosenType == null ? "-" : chosenType.id());
        for (final MateRef ref : ModelMode.picked()) {
            sb.append('|').append(ref.describe());
        }
        sb.append('#').append(ModelForceUi.selectedId());
        sb.append('#').append(ModelCouplingUi.selectedId());
        // 树上选中的那条配合也要进指纹：否则点了配合行，左栏内容不会重建，
        // 看起来就是「点了没反应」（用户实测反馈「树里的配合无法选中」）。
        final Mate highlighted = MateClientState.highlightedMate();
        sb.append('#').append(highlighted == null ? "-" : highlighted.id());
        // 配合的类型与数值也在指纹里：类型一变，编辑页要换掉整组输入框（距离 / 区间 / 角度）。
        // 而且服务端**拒绝**改动时不会改这些值 —— 指纹不变就不重建，等于 UI 自然地保持了原值。
        if (highlighted != null) {
            sb.append('#').append(highlighted.type().id());
            sb.append('#').append(highlighted.value());
            sb.append('#').append(highlighted.value2());
            sb.append('#').append(highlighted.flip());
            sb.append('#').append(highlighted.name());
        }
        // 创建流程的草稿类型：换类型同样要换输入框
        sb.append('#').append(MateEditUi.draftTypeId());
        // ⚠ 草图阶段必须进指纹。`setTool(SKETCH)` 发生在**点基准面之前**，
        // 所以点完面之后 tool 没变 —— 指纹不变就不重建左栏，界面会一直停在
        // "点击一个基准面开始画草图"上，看起来就是"选完面没反应"（用户实测反馈）。
        // 选中的图元同理：点中一个元素要能看出选中态。
        final var sketchSession = com.ovo.sablestopnow.client.SketchClient.mode().session();
        sb.append('#').append(sketchSession.stage());
        // ⚠ 选择集和选择**大小**都要进指纹：只算第一个元素的话，
        // Ctrl 加/减第二个元素时指纹不变 → 左栏不重建 → 玩家看不到选择数量变化，
        // 会以为 Ctrl 多选没生效。
        final var selection = sketchSession.selection();
        sb.append('#').append(selection.size());
        for (final var picked : selection) {
            sb.append('|').append(System.identityHashCode(picked));
        }
        // ⚠ 拉伸源也必须进指纹：不进去的话，在树里点了另一张草图，左栏内容不会重建 ——
        // 看起来就是"点了没反应"。这个坑在草图阶段已经踩过一次（见上面那段）。
        sb.append('#').append(extrudeSource);
        // ⚠ 拉伸方式也要进指纹：切方式时"两侧"要多出一个反向深度输入框，
        // 指纹不变就不重建 → 框不会出现 → 看起来就是"点了没反应"。
        sb.append('#').append(extrudeMode);
        // ⚠ 旋转那套也要进指纹：切到旋转会换掉整组控件（轴列表/角度/旋向），
        // 选了另一条轴要高亮，旋向要能看出选中态。不进指纹这些全是"点了没反应"。
        sb.append('#').append(revolveMode);
        sb.append('#').append(revolveDirection);
        sb.append('#').append(revolveAxis == null ? "-"
                : (revolveAxis[0] + "," + revolveAxis[1]));
        // ⚠ 贴图编辑区也要进指纹：点了「贴图」要展开编辑区、改了主手方块要刷新"当前贴图"那一行。
        // 不进就是"点了没反应"（这个坑在本文件里已经踩过好几次）。
        sb.append('#').append(textureSolidId);
        // ⚠⚠ **搜索框的文本绝对不能进签名。**
        //   我加过一次，代价是"下拉框永远不弹出、也点不了"（用户实测）：
        //   签名一变整栏就重建 → 正在打字的 ComboBox/TextField 每个字都被换成新实例
        //   → 焦点丢失、候选列表永远关着。
        //   文本活在 Prop 里本来就是实时的，**面板不需要因为打字而重建**。
        // ⚠ 约束数也要进指纹：加了约束之后，"还需要 N 个约束"和测量结果都得刷新。
        // 不加的话，点"标注"看不到任何变化 —— 又是一次"数据对了但没生效"。
        final var workingSketch = com.ovo.sablestopnow.client.SketchClient.mode().working();
        sb.append('#').append(workingSketch == null ? "-" : workingSketch.constraints().size());
        return sb.toString();
    }
    /** 上次建树时的内容签名：内容没变就不重建（重建会重置展开动画，表现为反复抽动）。 */
    @Nullable
    private static String lastTreeSignature;

    /** 树内容的指纹：所有节点的 key 拼起来，加选中模型。 */
    private static String signatureOf(final List<TreeView.TreeItem> roots) {
        final StringBuilder sb = new StringBuilder();
        sb.append(ModelMode.selectedModel());
        for (final TreeView.TreeItem item : roots) {
            appendKeys(sb, item);
        }
        return sb.toString();
    }

    private static void appendKeys(final StringBuilder sb, final TreeView.TreeItem item) {
        sb.append('|').append(item.key);
        for (final TreeView.TreeItem child : item.children) {
            appendKeys(sb, child);
        }
    }

    /** 诊断用：树的规模（roots=模型数，rows=当前真正铺出来的行数）。 */
    public static String treeStats() {
        // ⚠ 必须读**活的**树，不能读上次建树时记下的数字：树被重建过之后那些数字还是旧的，
        // 于是日志里一直显示 roots=4 rows=34，而屏幕上一片空白（这个「日志说得好好的、
        // 画面是空的」正是排查空树时最大的干扰）。
        final TreeView view = tree;
        if (view == null) {
            return "roots=0 rows=0 view=null";
        }
        return "roots=" + view.roots().size() + " rows=" + view.rowCount()
                + " box=" + view.x + "," + view.y + " " + view.w + "x" + view.h
                + " scroll=" + (int) view.scroll;
    }

    /** 每个节点的展开状态（key → 是否展开），跨树重建保留。 */
    private static final Map<String, Boolean> TREE_EXPANDED = new HashMap<>();

    private static void rememberExpansion(final List<TreeView.TreeItem> items) {
        for (final TreeView.TreeItem item : items) {
            TREE_EXPANDED.put(item.key, item.expanded);
            rememberExpansion(item.children);
        }
    }

    private static void applyRememberedExpansion(final List<TreeView.TreeItem> items) {
        for (final TreeView.TreeItem item : items) {
            final Boolean remembered = TREE_EXPANDED.get(item.key);
            if (remembered != null) {
                item.expanded = remembered;
            }
            applyRememberedExpansion(item.children);
        }
    }

    private static TreeView.TreeItem mateBranch(final UUID bodyId) {
        final TreeView.TreeItem node = new TreeView.TreeItem("mates:" + bodyId)
                .label(Component.translatable("model.node.mates"));
        for (final Mate mate : MateClientState.matesOf(bodyId)) {
            // ⚠ key 里必须带上 bodyId。同一条配合会**同时挂在它牵扯的两个模型下面**，
            // 只用 "mate:"+id 的话两个 TreeItem 撞同一个 key，而 TreeView 的行是**按 key 复用**的
            // （rowMap），于是第二个位置拿到的是第一个位置的控件 —— 表现就是
            // 「一个模型下面正常显示这条配合，另一个只有空位不显示」（用户实测反馈）。
            final TreeView.TreeItem row = new TreeView.TreeItem("mate:" + bodyId + ":" + mate.id())
                    .label(mateLabel(mate));
            row.data = mate;
            node.child(row);
        }
        // 耦合类配合（比例 / 齿轮）与标准配合并列挂在同一分支下
        for (final Object coupling : com.ovo.sablestopnow.coupling.CouplingClientState.ofBody(bodyId)) {
            final TreeView.TreeItem row;
            if (coupling instanceof com.ovo.sablestopnow.server.CouplingData.RatioGroup group) {
                row = new TreeView.TreeItem("ratio:" + bodyId + ":" + group.id())
                        .label(Component.literal("比例 | Ratio (" + group.members().size() + ")"));
            } else if (coupling instanceof com.ovo.sablestopnow.server.CouplingData.GearCoupling gear) {
                row = new TreeView.TreeItem("gear:" + bodyId + ":" + gear.id())
                        .label(Component.literal("齿轮 | Gear " + gear.teethA() + ":" + gear.teethB()
                                + (gear.reversed() ? " R" : "")));
            } else {
                continue;
            }
            row.data = coupling;
            node.child(row);
        }
        return node;
    }

    private static TreeView.TreeItem propertyBranch(final UUID bodyId) {
        final TreeView.TreeItem node = new TreeView.TreeItem("props:" + bodyId)
                .label(Component.translatable("model.node.properties"));
        node.child(property("hide:" + bodyId, "model.prop.hidden",
                () -> ModelProperties.hidden(bodyId), v -> ModelProperties.setHidden(bodyId, v)));
        node.child(property("collide:" + bodyId, "model.prop.collide",
                () -> !ModelProperties.noCollision(bodyId), v -> ModelProperties.setNoCollision(bodyId, !v)));
        node.child(property("lock:" + bodyId, "model.prop.locked",
                () -> ModelProperties.locked(bodyId), v -> ModelProperties.setLocked(bodyId, v)));
        return node;
    }

    private static TreeView.TreeItem property(final String key, final String labelKey,
                                              final java.util.function.Supplier<Boolean> getter,
                                              final java.util.function.Consumer<Boolean> setter) {
        final TreeView.TreeItem item = new TreeView.TreeItem(key).label(Component.translatable(labelKey));
        item.data = new PropertyToggle(getter, setter);
        return item;
    }

    private static TreeView.TreeItem forceBranch(final UUID bodyId) {
        final TreeView.TreeItem node = new TreeView.TreeItem("forces:" + bodyId)
                .label(Component.translatable("model.node.forces"));
        // 该模型上已有的施力点（每个一行，选中即在左栏编辑）
        for (final com.ovo.sablestopnow.force.ForcePoint point
                : com.ovo.sablestopnow.force.ForceClientState.ofBody(bodyId)) {
            final TreeView.TreeItem row = new TreeView.TreeItem("force:" + point.id())
                    .label(Component.literal(ModelUi.shortId(point.id())));
            row.data = point;
            node.child(row);
        }
        final TreeView.TreeItem add = new TreeView.TreeItem("force_add:" + bodyId)
                .label(Component.translatable("model.node.add_force"));
        add.data = bodyId;
        node.child(add);
        return node;
    }

    /**
     * 草图分支：**只列没被特征用掉的草图**（SolidWorks 的 *unabsorbed sketch*）。
     *
     * <p>被用掉的草图吸收到对应特征下面（见 {@link #solidsBranch}）——
     * 这样"这张草图被谁用、改它会影响谁"在树里直接看得见，不必靠猜。
     *
     * <p>草图的权威数据在服务端（{@code SketchClientState} 只是投影），但**不需要**像施力点
     * 那样做「选中即拉取」—— 全量同步本来就带回了变量、图元与约束，
     * 客户端手上就是完整的一份，点进去直接编辑即可。
     */
    private static TreeView.TreeItem sketchBranch(final UUID bodyId) {
        final TreeView.TreeItem node = new TreeView.TreeItem("sketches:" + bodyId)
                .label(Component.translatable("model.node.sketches"));
        final java.util.List<com.ovo.sablestopnow.sketch.Sketch> sketches =
                currentSketchesOf(bodyId);
        final java.util.Map<UUID, com.ovo.sablestopnow.sketch.Sketch> byId =
                new java.util.LinkedHashMap<>();
        for (final com.ovo.sablestopnow.sketch.Sketch s : sketches) {
            byId.put(s.id(), s);
        }
        final com.ovo.sablestopnow.sketch.Solid.TreeGrouping grouping =
                com.ovo.sablestopnow.sketch.Solid.groupSketches(
                        currentSolidsOf(bodyId), byId.keySet());
        for (final UUID freeId : grouping.freeSketches()) {
            final com.ovo.sablestopnow.sketch.Sketch sketch = byId.get(freeId);
            if (sketch == null) {
                continue;
            }
            // ⚠ key 里必须带所属结构：TreeView 的行是**按 key 复用**的，
            // 同一个 key 在两个结构下出现会撞车（配合那边踩过：第二个位置拿到第一个的控件）。
            final TreeView.TreeItem row = new TreeView.TreeItem(
                    "sketch:" + bodyId + ":" + sketch.id())
                    .label(Component.literal(sketch.name()));
            row.data = sketch;
            node.child(row);
        }
        // 全都被用掉了 → 顶层是空的。给一行说明，免得看起来像"草图丢了"
        if (sketches.isEmpty() || grouping.freeSketches().isEmpty()) {
            final TreeView.TreeItem hint = new TreeView.TreeItem("sketches_hint:" + bodyId)
                    .label(Component.translatable("model.sketch.all_consumed"));
            node.child(hint);
        }
        return node;
    }

    /** 当前维度里属于某个结构的草图；没有客户端世界时返回空表。 */
    private static java.util.List<com.ovo.sablestopnow.sketch.Sketch> currentSketchesOf(
            final UUID bodyId) {
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.world.level.Level level = mc == null ? null : mc.level;
        if (level == null) {
            return java.util.List.of();
        }
        return com.ovo.sablestopnow.sketch.SketchClientState.ofBody(level, bodyId);
    }

    /** 当前维度里属于某个结构的拉伸实体；没有客户端世界时返回空表。 */
    private static java.util.List<com.ovo.sablestopnow.sketch.Solid> currentSolidsOf(
            final UUID bodyId) {
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.world.level.Level level = mc == null ? null : mc.level;
        if (level == null) {
            return java.util.List.of();
        }
        return com.ovo.sablestopnow.sketch.SolidClientState.ofBody(level, bodyId);
    }

    /**
     * 设计树里的「拉伸 / 旋转」分支：挂在对应结构下。
     *
     * <p>⚠ key 里必须带所属结构 —— {@code TreeView} 的行是**按 key 复用**的，
     * 同一个 key 出现在两个结构下会撞车（草图那边已经因此踩过：第二个位置拿到第一个的控件）。
     *
     * <p>**被吸收的草图挂在特征下面**（SolidWorks 的 FeatureManager 约定）：
     * 草图一旦被某个特征用掉就收进该特征下，展开特征才看得到；
     * 没被用掉的草图留在 {@link #sketchBranch} 顶层。
     * 分组规则在 {@code Solid.groupSketches}（纯逻辑，有离线断言）。
     */
    private static TreeView.TreeItem solidsBranch(final UUID bodyId) {
        final TreeView.TreeItem node = new TreeView.TreeItem("solids:" + bodyId)
                .label(Component.translatable("model.node.solids"));
        final java.util.List<com.ovo.sablestopnow.sketch.Solid> solids = currentSolidsOf(bodyId);
        final java.util.List<com.ovo.sablestopnow.sketch.Sketch> sketches =
                currentSketchesOf(bodyId);
        // 草图表：id → 草图，用来把"被吸收的草图 id"还原成可点开的行
        final java.util.Map<UUID, com.ovo.sablestopnow.sketch.Sketch> byId =
                new java.util.LinkedHashMap<>();
        for (final com.ovo.sablestopnow.sketch.Sketch s : sketches) {
            byId.put(s.id(), s);
        }
        final com.ovo.sablestopnow.sketch.Solid.TreeGrouping grouping =
                com.ovo.sablestopnow.sketch.Solid.groupSketches(solids, byId.keySet());

        for (final com.ovo.sablestopnow.sketch.Solid solid : solids) {
            final boolean revolve = solid.isRevolution();
            final Component label = revolve
                    ? Component.translatable("model.revolve.item", solid.index())
                    : Component.translatable("model.extrude.item", solid.index());
            final TreeView.TreeItem row = new TreeView.TreeItem(
                    "solid:" + bodyId + ":" + solid.id()).label(label);
            row.data = solid;
            // **贴图入口做在树上**（用户 2026-10 的要求）。
            // 以前它只藏在"拉伸工具"面板里，所以用户根本找不到 ——
            // 特征节点上就该有它自己的编辑入口。
            final TreeView.TreeItem texRow = new TreeView.TreeItem(
                    "texture:" + bodyId + ":" + solid.id())
                    .label(Component.translatable("model.texture.open"));
            texRow.data = solid;
            row.child(texRow);
            // 被吸收的草图：展开这个特征才看得到
            for (final UUID sketchId : grouping.absorbed()
                    .getOrDefault(solid.id(), java.util.List.of())) {
                final com.ovo.sablestopnow.sketch.Sketch owned = byId.get(sketchId);
                if (owned == null) {
                    continue;
                }
                // ⚠ key 用「特征 id + 草图 id」：一张草图可能挂在**多个**特征下，
                // 只按草图 id 做 key 的话两处会同时展开/折叠（TreeView 按 key 复用）。
                final TreeView.TreeItem child = new TreeView.TreeItem(
                        "consumed:" + bodyId + ":" + solid.id() + ":" + sketchId);
                // 被多个特征使用时把数量写进标签：改这张草图会**连带改变所有引用它的特征**
                //（几何是现算的）。不说清的话玩家改完发现别处也变了，会以为是 bug。
                final int consumers = grouping.consumerCount(sketchId);
                child.label(consumers > 1
                        ? Component.translatable("model.sketch.shared", owned.name(), consumers)
                        : Component.literal(owned.name()));
                child.data = owned;
                row.child(child);
            }
            node.child(row);
        }
        return node;
    }

    /** 行内容：标签 + 可选控件（属性行的复选框、施力点的摘要在这里长出来）。 */
    private static void buildRow(final TreeView.TreeItem item, final Container row) {
        // ⚠ 行按 key 复用：TreeView.collect() 会把 RowWidget.item 换成**新的** TreeItem，
        // 而这里的 lambda 只会被调用一次 —— 捕获参数 item 就等于永远读旧对象，
        // 于是「施力点力量改了、树里还是旧数字」「属性改名后行文字不变」。
        // 一律通过 RowWidget.item 读当前节点。
        final TreeView.RowWidget live = row instanceof TreeView.RowWidget rw ? rw : null;
        final java.util.function.Supplier<TreeView.TreeItem> current =
                live == null ? () -> item : () -> live.item;

        if (item.data instanceof PropertyToggle toggle) {
            final Checkbox box = new Checkbox(toggle.getter, value -> {
                toggle.setter.accept(value);
                refreshLeftPanel();
            }, () -> current.get().label);
            box.prefW = Widget.FILL;
            row.add(box);
            return;
        }
        if (item.data instanceof com.ovo.sablestopnow.force.ForcePoint) {
            ModelForceUi.buildRow(row, () -> current.get().data
                    instanceof com.ovo.sablestopnow.force.ForcePoint point ? point : null);
            return;
        }
        final Label label = new Label(() -> current.get().label);
        label.prefW = Widget.FILL;
        row.add(label);
    }

    // ============ 左边栏：编辑区 ============

    private static void buildLeft() {
        UiLayer layer = Ui.layer(LAYER_LEFT);
        if (layer == null) {
            layer = Ui.open(UiLayer.of(LAYER_LEFT).size(0, 0));
        }
        layer.root.clear();
        layer.root.layout = Layouts.dock(4, 4);
        layer.hud = true;

        final com.ovo.sablestopnow.client.gui.lib.CollapsiblePanel panel =
                new com.ovo.sablestopnow.client.gui.lib.CollapsiblePanel(false);
        leftPanelRef = panel;
        // 从左边滑入
        layer.slideFromX = -170;
        layer.slideFromY = 0;
        panel.dock = Layouts.Dock.LEFT;
        panel.fraction(0.26f, 96, 160);
        panel.drawTitle = false;
        panel.layout = Layouts.vbox(3, 3);

        final Label title = new Label(Component.translatable("model.left.title"));
        title.prefH = 11;
        panel.add(title);

        // ⚠ 内容区必须是滚动容器：施力点编辑器（大小滑条 + 频道输入 + 开关 + 说明）叠起来比
        // 面板高，用普通容器时它会直接顶出去，把底部的「完成 / 取消」盖住（用户实测反馈
        // 「内容过多没有滑动条，挡住确认键」）。ScrollPane 自带滚轮 + 拖拽滚动条 + 裁剪。
        final com.ovo.sablestopnow.client.gui.lib.ScrollPane body =
                new com.ovo.sablestopnow.client.gui.lib.ScrollPane();
        body.prefH = Widget.FILL;
        panel.add(body);
        leftBody = body;

        final Container buttons = new Container();
        buttons.layout = Layouts.hbox(0, 3);
        buttons.prefH = 13;
        final Button complete = new Button(Component.translatable("gui.sablestopnow.mate.done"), ModelUi::completeTool);
        complete.prefW = Widget.FILL;
        final Button cancel = new Button(Component.translatable("gui.sablestopnow.mate.cancel"), ModelUi::cancelTool);
        cancel.prefW = Widget.FILL;
        buttons.add(complete, cancel);
        panel.add(buttons);
        completeButton = complete;

        // ⚠ 左栏内容是新建的空容器，但内容指纹可能和上次一样 —— refreshLeftPanel 会直接 return，
        // 留下一个空左栏（与右边树的空树是同一个坑）。清掉指纹强制铺一次。
        lastLeftSignature = null;
        refreshLeftPanel();

        layer.root.add(panel);
        layer.visible = true;
    }

    /**
     * 进入草图模式。
     *
     * <p>和其它工具不同，草图不需要先在树上选面 —— 只要选中一个<b>结构</b>，
     * 三个基准面由该结构的包围盒现算（{@code SketchClient.begin}），
     * 然后进入「选面」状态等玩家点其中一块板。
     */
    public static void startSketch() {
        final java.util.UUID body = ModelMode.selectedModel();
        if (body == null) {
            ModelMode.prompt("model.prompt.sketch_pick_body");
            return;
        }
        if (!com.ovo.sablestopnow.client.SketchClient.begin(body)) {
            // 结构还没同步到客户端（刚放置/刚进世界）—— 不要静默失败，不然玩家会以为按钮坏了
            ModelMode.prompt("model.prompt.sketch_no_body");
            return;
        }
        ModelMode.setTool(ModelTools.SKETCH);
        ModelMode.prompt("model.prompt.sketch_pick_plane");
        refreshLeftPanel();
    }

    /**
     * 按 id 找客户端已知的实体；找不到返回 null。
     *
     * <p>贴图面板要能**在任意工具下**打开，所以不能只从"当前拉伸源的实体列表"里找。
     */
    @Nullable
    private static com.ovo.sablestopnow.sketch.Solid resolveSolid(
            @Nullable final java.util.UUID solidId) {
        if (solidId == null) {
            return null;
        }
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.world.level.Level level = mc == null ? null : mc.level;
        if (level == null) {
            return null;
        }
        return com.ovo.sablestopnow.sketch.SolidClientState.byId(level, solidId);
    }

    /**
     * 把玩家的输入**解析成一个合法的方块 id**（回车直接应用时用）。
     *
     * <p>玩家只会输 `oak`、`stone` 这种片段，而注册表里是 `minecraft:oak_planks`。
     * 直接把原文当 id 存进去，渲染时解析失败 → **看起来"回车没反应"**。
     * 所以一律走 {@code searchBlocks} 取**第一条匹配**（相关性最高的那个）。
     *
     * @return 解析出的 id；匹配不到返回 null
     */
    @Nullable
    private static String resolveBlockId(@Nullable final String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        final String q = query.trim();
        // 已经是完整 id（带命名空间且注册表里有）就直接用，别再兜一圈
        if (q.indexOf(':') > 0
                && net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(
                        net.minecraft.resources.ResourceLocation.tryParse(q))) {
            return q;
        }
        final java.util.List<String> hits = com.ovo.sablestopnow.sketch.SolidAppearance
                .searchBlocks(allBlockIds(), q, 1);
        return hits.isEmpty() ? null : hits.get(0);
    }

    /** 按当前功能与选择重建左栏内容。 */
    public static void refreshLeftPanel() {
        final Container body = leftBody;
        if (body == null) {
            return;
        }
        // ⚠ 只在「内容真的会变」时才重建：以前每 12 帧无条件重建一次，
        // 会把玩家正在点的按钮换掉 —— 表现为配合类型按钮「闪烁且点不动」
        //（按下时控件还在，松开时已经换成新实例，Button 的 mouseUp 落空）。
        final String signature = leftPanelSignature();
        if (signature.equals(lastLeftSignature)) {
            return;
        }
        lastLeftSignature = signature;
        body.clear();
        final String tool = ModelMode.tool();

        // 顶部：当前功能 + 已选数量
        body.add(new Label(() -> Component.translatable(ModelTools.titleKey(ModelMode.tool()))).shadow(true));
        body.add(new Label(() -> Component.translatable("model.left.picked", ModelMode.picked().size())).color(0xFFB9C0EE));
        final int need = ModelTools.minRefs(tool);
        if (need > 0) {
            body.add(new Label(() -> Component.translatable("model.left.need", need)).color(0xFF8FA0D8));
        }
        body.add(new com.ovo.sablestopnow.client.gui.lib.Separator());

        // ⚠⚠ **贴图面板优先**：从树上的「贴图…」进来时，**不管当前是什么工具**都直接显示它。
        //   以前 `buildTextureEditor` 只在"拉伸工具"面板里被调用，
        //   所以点树上的「贴图…」只设了 textureSolidId 却没有任何东西渲染它 —— 表现为"点了没反应"
        //   （用户实测反馈）。入口在树上，就必须能在任何工具下打开。
        final com.ovo.sablestopnow.sketch.Solid editingTexture = resolveSolid(textureSolidId);
        if (editingTexture != null) {
            buildTextureEditor(body, editingTexture);
            return;
        }

        if (ModelTools.MATE.equals(tool)) {
            final List<MateRef> picked = ModelMode.picked();
            if (picked.size() < 2) {
                body.add(new Label(Component.translatable("model.left.pick_more")));
            } else {
                // 创建页 = 编辑页：同一套控件，这里改的是一份草稿，点「完成」才真正建出来
                MateEditUi.buildEditor(body, null);
            }
        } else if (ModelTools.FORCE.equals(tool) || ModelForceUi.selectedId() != null) {
            body.add(new Label(Component.translatable(ModelTools.hintKey(ModelTools.FORCE))));
            body.add(new com.ovo.sablestopnow.client.gui.lib.Separator());
            ModelForceUi.buildEditor(body);
        } else if (ModelCouplingUi.hasSelection()) {
            ModelCouplingUi.buildEditor(body);
        } else if (ModelTools.RATIO.equals(tool) || ModelTools.GEAR.equals(tool)) {
            final int have = ModelMode.picked().size();
            body.add(new Label(Component.translatable(ModelTools.hintKey(tool))));
            // 注意：三元里混 String 与 int 在 javac 的目标类型推断下会报一个很误导的语法错误，
            // 所以这里先把「需要几个」拼成字符串再进 Label。
            final int maxRefs = ModelTools.maxRefs(tool);
            final String needLabel = maxRefs < 0
                    ? "\u2265" + ModelTools.minRefs(tool) : String.valueOf(maxRefs);
            body.add(new Label(Component.literal(have + " / " + needLabel)));
            if (ModelTools.RATIO.equals(tool)) {
                body.add(new Label(Component.translatable("model.ratio.order")).color(0xFF8FA0D8));
            } else {
                body.add(new Label(Component.translatable("model.gear.order")).color(0xFF8FA0D8));
                body.add(new Label(Component.translatable("model.gear.default_teeth")).color(0xFF8FA0D8));
            }
        } else if (ModelTools.SKETCH.equals(tool)) {
            // 草图环境：这里不是「选参考」而是「画图元」，所以内容完全是另一套。
            body.add(new Label(Component.translatable("model.tool.sketch.hint")).color(0xFF8FA0D8));
            body.add(new com.ovo.sablestopnow.client.gui.lib.Separator());
            final var session = com.ovo.sablestopnow.client.SketchClient.mode().session();
            if (session.stage() == com.ovo.sablestopnow.sketch.SketchSession.Stage.CHOOSING_PLANE) {
                body.add(new Label(Component.translatable("model.prompt.sketch_pick_plane"))
                        .color(0xFFCFD8FF));
            } else {
                body.add(new Label(Component.translatable("model.sketch.tool.line")).color(0xFF8FA0D8));
                // ⚠ 工具按钮必须**每次重建都重新读一遍当前工具**来设置高亮，
                // 而不是订阅：订阅写在建面板里的话，左栏每重建一次就多挂一条回调，
                // 勾一下会发出 N 个包（配合编辑器那边踩过这个坑）。
                final var row = new com.ovo.sablestopnow.client.gui.lib.Container();
                // ⚠ 这里以前是普通 hbox ✗ + 子控件 FILL ✗ ⇒ free=0 时每个按钮分到 0 宽 ✗✓
                //   （五种图元挤一行 ⇒ "文字遮盖/看不见" ✓）。
                //   hboxFit：放不下先让控件 setCompact ✓ 再按比例缩 ✓ 且 Math.max(1, …) 保证永不 0 ✓
                // 折行版 ✓：五种图元一行塞不下时**自动折到第二行** ✓ 而不是压到 0 宽 ✗；
                // 高度由布局自己写回（prefH = AUTO ✓）⇒ 这里不需要知道会折几行 ✓
                row.layout = com.ovo.sablestopnow.client.gui.lib.Layouts.hboxWrap(0, 3, 2);
                // ★ 明确给**两行**高度 ✓：6 个 26px 按钮在窄左栏里必然折行 ✓
                //   而自动换行只写 prefH、父 vbox 不一定当帧重读 ⇒ 第二行会被压掉 ✗（F9 报告里只看到 4 个 ✗）
                //   ⇒ 这里写死两行高（比依赖时序稳 ✓ 一行时略空一点 ✓ 可接受 ✓）
                row.prefH = 28;
                row.add(sketchToolButton("model.sketch.tool.select",
                        com.ovo.sablestopnow.sketch.SketchSession.Tool.SELECT, session));
                row.add(sketchToolButton("model.sketch.tool.line",
                        com.ovo.sablestopnow.sketch.SketchSession.Tool.LINE, session));
                row.add(sketchToolButton("model.sketch.tool.rectangle",
                        com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE, session));
                row.add(sketchToolButton("model.sketch.tool.circle",
                        com.ovo.sablestopnow.sketch.SketchSession.Tool.CIRCLE, session));
                // 圆弧：三次点击（圆心 → 起点 → 终点）
                row.add(sketchToolButton("model.sketch.tool.arc",
                        com.ovo.sablestopnow.sketch.SketchSession.Tool.ARC, session));                // ★ **智能尺寸**（SolidWorks 的 Smart Dimension ✓）—— 用户反馈"没有入口"✗
                //   它是第 6 个工具按钮 ✓ 与另外五个同一套写法 ✓
                //   （`hboxWrap` 会自动折行 ✓ 文字会自动短标签 ✓ ⇒ 加第 6 个也不会遮盖 ✓）
                row.add(sketchToolButton("model.sketch.tool.dimension",
                        com.ovo.sablestopnow.sketch.SketchSession.Tool.DIMENSION, session));
                body.add(row);
                body.add(new com.ovo.sablestopnow.client.gui.lib.Separator());
                // 几何约束：作用在**当前选中的图元**上（左栏「选择」工具点中的那个）。
                // 校验在模型层（圆不能加水平/竖直），失败时给提示而不是静默无反应。
                body.add(new Label(Component.translatable("model.sketch.constraint"))
                        .color(0xFF8FA0D8));
                final var constraintRow = new com.ovo.sablestopnow.client.gui.lib.Container();
                constraintRow.layout = com.ovo.sablestopnow.client.gui.lib.Layouts.hbox(0, 3);
                constraintRow.prefH = 13;
                // ⚠ 单选 vs 多选给**不同性质**的约束：
                // 水平/竖直/固定是**对单个图元**的；多选时它们表达不了"元素之间的关系"，
                // 逐条各加一遍还会把同一个约束加两遍（用户实测反馈）。
                // 多选改给关系型：等长 / 同心（这两个用现有求解器种类就能表达）。
                if (session.selectionSize() <= 1) {
                    constraintRow.add(constraintButton("model.sketch.constraint.horizontal",
                            com.ovo.sablestopnow.sketch.SketchSolver.HORIZONTAL));
                    constraintRow.add(constraintButton("model.sketch.constraint.vertical",
                            com.ovo.sablestopnow.sketch.SketchSolver.VERTICAL));
                    constraintRow.add(constraintButton("model.sketch.constraint.fixed",
                            com.ovo.sablestopnow.sketch.SketchSolver.FIXED));
                } else {
                    constraintRow.add(relationalButton("model.sketch.constraint.equal",
                            () -> {
                                final var m = com.ovo.sablestopnow.client.SketchClient.mode();
                                if (!m.session().makeEqual()) {
                                    ModelMode.prompt("model.sketch.constraint.need_two_lines");
                                    return false;
                                }
                                m.solve();
                                return true;
                            }));
                    constraintRow.add(relationalButton("model.sketch.constraint.concentric",
                            () -> {
                                final var m = com.ovo.sablestopnow.client.SketchClient.mode();
                                if (!m.session().makeConcentric()) {
                                    ModelMode.prompt("model.sketch.constraint.need_two_rounds");
                                    return false;
                                }
                                m.solve();
                                return true;
                            }));
                }
                body.add(constraintRow);
                // 尺寸：给**选中的图元**标长度（线段）或半径（圆）。
                // 数值放在静态 Prop 里，左栏每重建一次都读同一个值 ——
                // 否则勾一下/切一下工具，输入框里的数就被重置了。
                com.ovo.sablestopnow.client.gui.lib.Cmd.declare(CMD_SKETCH_DIM);
                final com.ovo.sablestopnow.client.gui.lib.UiField dimField =
                        com.ovo.sablestopnow.client.gui.lib.UiField.number(
                                "model.sketch.dimension", CMD_SKETCH_DIM, sketchDimension,
                                0.1, 1000.0, 1.0);
                dimField.format(v -> String.format(java.util.Locale.ROOT, "%.1f", v));
                dimField.commit(v -> {
                    final var mode = com.ovo.sablestopnow.client.SketchClient.mode();
                    if (mode.session().selectedEntity() == null) {
                        ModelMode.prompt("model.sketch.constraint.need_select");
                    } else if (mode.dimension(v)) {
                        ModelMode.prompt("model.sketch.constraint.added");
                    } else {
                        ModelMode.prompt("model.sketch.constraint.need_line");
                    }
                    refreshLeftPanel();
                });
                body.add(dimField);
                // 删除**选中的图元**（不是整张草图）。必须先选中才可用 ——
                // 没选中时给提示而不是静默无反应（静默失败是最容易被当成"按钮坏了"的）。
                body.add(new Button(Component.translatable("model.sketch.delete_entity"), () -> {
                    final var mode = com.ovo.sablestopnow.client.SketchClient.mode();
                    if (mode.session().removeSelected()) {
                        // 删完必须**重新求解**：约束跟着图元一起没了，
                        // 剩下的几何在新的约束集下应当重新收敛，否则会停在一个"上一步的解"上。
                        mode.solve();
                        ModelMode.prompt("model.sketch.deleted");
                    } else {
                        ModelMode.prompt("model.sketch.constraint.need_select");
                    }
                    refreshLeftPanel();
                }).width(Widget.FILL));
                // ---- 测量（仿 SolidWorks Measure）：Ctrl 选中两个图元才出现 ----
                final var live = com.ovo.sablestopnow.client.SketchClient.mode().working();
                if (live != null) {
                    // "还需要 N 个约束"：0 = 完全定义。这是 SolidWorks 底部那行提示。
                    final int dof = com.ovo.sablestopnow.sketch.SketchDof.remainingDof(live);
                    if (dof == 0) {
                        body.add(new Label(Component.translatable("model.sketch.fully_defined"))
                                .color(0xFF7FD8A0));
                    } else {
                        body.add(new Label(Component.translatable("model.sketch.dof_left", dof))
                                .color(0xFFCFD8FF));
                    }
                    // 约束互相矛盾时，自由度数字是不可信的 —— 明说，别让玩家照着它去加约束。
                    if (com.ovo.sablestopnow.sketch.SketchDof.maxResidual(live) > 1.0e-6) {
                        body.add(new Label(Component.translatable("model.sketch.dof_conflict"))
                                .color(0xFFFF5A4D));
                    }
                }
                final var picked = session.selection();
                // ---- 智能尺寸：1 个或 2 个图元都能标（对齐 SolidWorks 的 Smart Dimension）----
                // 以前这里只在**选中两个**才出现，所以"点一条线标长度"这个最常见的操作做不到。
                if (live != null && !picked.isEmpty() && picked.size() <= 2) {
                    final var smart = com.ovo.sablestopnow.sketch.SketchMeasure
                            .smartDimension(live, picked);
                    if (smart != null) {
                        // ★ 实时预览 ✓：按**指针方位**自动捕捉候选（右键锁定优先 ✓）
                        //   候选来自 SketchMeasure.variants（平行/水平/竖直 或 半径/直径 ✓）
                        final var cands = com.ovo.sablestopnow.sketch.SketchMeasure.variants(live, smart);
                        final var shown = com.ovo.sablestopnow.sketch.SketchMeasure.preview(
                                live, picked, session.hoverU(), session.hoverV(),
                                session.lockedVariant());
                        final var use = shown != null ? shown
                                : (cands.isEmpty() ? null : cands.get(0));
                        body.add(new com.ovo.sablestopnow.client.gui.lib.Separator());
                        body.add(new Label(Component.translatable("model.smart.result",
                                Component.translatable(smart.labelKey()),
                                String.format(java.util.Locale.ROOT, "%.3f",
                                        use != null ? use.value() : smart.value())))
                                .color(0xFFFFCC55));
                        if (cands.size() > 1) {
                            // 临时切换/锁定候选 ✓（SolidWorks：默认平行尺寸，可切换水平/竖直 ✓）
                            // 用统一 GUI 库的 Button + hboxWrap ✓ ⇒ 窄了自动折行 ✓ 文字自动短标签 ✓
                            final var crow = new com.ovo.sablestopnow.client.gui.lib.Container();
                            crow.layout = com.ovo.sablestopnow.client.gui.lib.Layouts.hboxWrap(0, 3, 2);
                            crow.prefW = com.ovo.sablestopnow.client.gui.lib.Widget.FILL;
                            // ★ 明确给**两行**高度 ✓：6 个 26px 按钮在窄左栏里必然折行 ✓
                            //   而自动换行只写 prefH、父 vbox 不一定当帧重读 ⇒ 第二行会被压掉 ✗（F9 报告里只看到 4 个 ✗）
                            //   ⇒ 这里写死两行高（比依赖时序稳 ✓ 一行时略空一点 ✓ 可接受 ✓）
                            row.prefH = com.ovo.sablestopnow.client.gui.lib.Widget.AUTO;
                            for (int ci = 0; ci < cands.size(); ci++) {
                                final int index = ci;
                                final var cb = new Button(
                                        Component.translatable(cands.get(ci).labelKey()), () -> {
                                            // 再点一次 = 取消锁定 ✓（回到自动捕捉 ✓）
                                            session.lockVariant(session.lockedVariant() == index
                                                    ? -1 : index);
                                        });
                                cb.toggled = session.lockedVariant() == ci;
                                crow.add(cb);
                            }
                            body.add(crow);
                        }
                        // ★ 从动/驱动开关 ✓（SolidWorks：驱动尺寸可切换为从动 = 只显示不驱动 ✓）
                        final var refBtn = new Button(Component.translatable("model.dim.reference"),
                                () -> session.setReferenceMode(!session.referenceMode()));
                        refBtn.toggled = session.referenceMode();
                        body.add(refBtn);
                        if (smart.expressible()) {
                            body.add(new Button(Component.translatable("model.measure.dimension"),
                                    () -> {
                                        final var mode = com.ovo.sablestopnow.client.SketchClient.mode();
                                        final var work = mode.working();
                                        final var sel = mode.session().selection();
                                        if (work == null || sel.isEmpty()) {
                                            return;
                                        }
                                        // ⚠ 用**智能尺寸算出来的那个值**，不要在这里重算一遍：
                                        // 两处各算一次迟早会不一致（而且显示的和标下去的必须同一个数）。
                                        final var again = com.ovo.sablestopnow.sketch.SketchMeasure
                                                .smartDimension(work, sel);
                                        if (again == null || !again.expressible()) {
                                            return;
                                        }
                                        // ★ 走**通用落约束** ✓：用"当前候选"的 kind 与值 ✓
                                        //   ⇒ 夹角/线线距/点线距/水平竖直 全部一次打通 ✓
                                        //   并且自带**过定义拦截** ✓（同几何同种约束 ⇒ 改值而非新建 ✓）
                                        final var now = com.ovo.sablestopnow.sketch.SketchMeasure
                                                .preview(work, sel, mode.session().hoverU(),
                                                        mode.session().hoverV(),
                                                        mode.session().lockedVariant());
                                        final var cand = now != null ? now
                                                : (com.ovo.sablestopnow.sketch.SketchMeasure
                                                        .variants(work, again).isEmpty() ? null
                                                        : com.ovo.sablestopnow.sketch.SketchMeasure
                                                                .variants(work, again).get(0));
                                        // 从动模式 ✓：不落约束 ✗，而且把已有约束删掉 ⇒ 退成参考尺寸 ✓（灰 + 括号 ✓）
                                        if (mode.session().referenceMode()) {
                                            final var already = com.ovo.sablestopnow.sketch.SketchMeasure
                                                    .alreadyDimensioned(work, again);
                                            final boolean removed = already != null
                                                    && work.removeConstraint(already);
                                            mode.solve();
                                            com.ovo.sablestopnow.client.model.ModelMode.prompt(
                                                    removed ? "model.dim.made_reference"
                                                            : "model.dim.already_reference");
                                            return;
                                        }
                                        final boolean ok = cand != null && mode.session()
                                                .dimensionWith(cand.solverKind(), cand.value(),
                                                        cand.vars());
                                        if (ok) {
                                            mode.solve();
                                            ModelMode.prompt("model.measure.added");
                                        }
                                        refreshLeftPanel();
                                    }).width(Widget.FILL));
                        } else {
                            body.add(new Label(Component.translatable("model.measure.not_lockable"))
                                    .color(0xFFB9C0EE));
                        }
                    }
                }
                // ---- 尺寸显示 + 可补的关系 ----
                if (live != null) {
                    buildDimensionPanel(body, session, live);
                }
                body.add(new com.ovo.sablestopnow.client.gui.lib.Separator());
                // ⚠ 吸附提示**不在这里**。左栏是靠 leftPanelSignature() 变化才重建的，
                // 而吸附目标每帧都在变 —— 放在这里只会显示"上次重建那一刻"的陈旧值，
                // 也就是一条会骗人的提示。它现在由 SketchHud 跟在光标旁画。
                body.add(new Button(Component.translatable("model.sketch.done"), () -> {
                    ModelUi.completeTool();
                }).width(Widget.FILL));
                body.add(new Button(Component.translatable("model.sketch.cancel"), () -> {
                    com.ovo.sablestopnow.client.SketchClient.cancel();
                    ModelUi.cancelTool();
                }).width(Widget.FILL));
                // 删除整张草图（只对已经存在服务端的草稿有意义；新建未提交的用"放弃"即可）
                final var editingSketch = com.ovo.sablestopnow.client.SketchClient.mode().working();
                if (editingSketch != null) {
                    body.add(new Button(Component.translatable("model.sketch.delete"), () -> {
                        com.ovo.sablestopnow.network.SketchNetworking.sendRemove(editingSketch.id());
                        com.ovo.sablestopnow.client.SketchClient.cancel();
                        ModelUi.cancelTool();
                    }).width(Widget.FILL));
                }
            }
        } else if (ModelTools.EXTRUDE.equals(tool)) {
            body.add(new Label(Component.translatable("model.tool.extrude.hint")).color(0xFF8FA0D8));
            body.add(new com.ovo.sablestopnow.client.gui.lib.Separator());
            final UUID extrudeBody = ModelMode.selectedModel();
            final com.ovo.sablestopnow.sketch.Sketch source = currentExtrudeSource();
            if (source == null) {
                body.add(new Label(Component.translatable("model.prompt.extrude_pick_sketch"))
                        .color(0xFFCFD8FF));
                // 除了在树里点，也直接列出来 —— 树可能要展开好几层才看得到草图
                if (extrudeBody != null) {
                    for (final com.ovo.sablestopnow.sketch.Sketch candidate
                            : currentSketchesOf(extrudeBody)) {
                        body.add(new Button(Component.literal(candidate.name()),
                                () -> selectExtrudeSource(candidate)).width(Widget.FILL));
                    }
                }
            } else {
                body.add(new Label(Component.translatable("model.extrude.source", source.name()))
                        .color(0xFFCFD8FF));
                // 成形方式：拉伸 / 旋转。两套参数完全不同，所以整段控件按它分支。
                final var shapeRow = new com.ovo.sablestopnow.client.gui.lib.Container();
                shapeRow.layout = com.ovo.sablestopnow.client.gui.lib.Layouts.hbox(0, 3);
                shapeRow.prefH = 13;
                shapeRow.add(shapeButton("model.extrude.shape.extrusion", false));
                shapeRow.add(shapeButton("model.extrude.shape.revolution", true));
                body.add(shapeRow);

                if (revolveMode) {
                    buildRevolveControls(body, source);
                } else {
                    buildExtrudeControls(body);
                }
            }
            // 该结构上已有的实体：列出来 + 可删。
            // 放在这里而不是塞进树行里：树行很窄，而且行是按 key 复用的，加控件容易出问题。
            if (extrudeBody != null) {
                final java.util.List<com.ovo.sablestopnow.sketch.Solid> existing =
                        currentSolidsOf(extrudeBody);
                if (!existing.isEmpty()) {
                    body.add(new com.ovo.sablestopnow.client.gui.lib.Separator());
                    body.add(new Label(Component.translatable("model.extrude.existing"))
                            .color(0xFF8FA0D8));
                    for (final com.ovo.sablestopnow.sketch.Solid solid : existing) {
                        final var row = new com.ovo.sablestopnow.client.gui.lib.Container();
                        // ⚠ 这里以前是普通 hbox ✗ + 子控件 FILL ✗ ⇒ free=0 时每个按钮分到 0 宽 ✗✓
                //   （五种图元挤一行 ⇒ "文字遮盖/看不见" ✓）。
                //   hboxFit：放不下先让控件 setCompact ✓ 再按比例缩 ✓ 且 Math.max(1, …) 保证永不 0 ✓
                row.layout = com.ovo.sablestopnow.client.gui.lib.Layouts.hboxWrap(0, 3, 2);
                        // ★ 明确给**两行**高度 ✓：6 个 26px 按钮在窄左栏里必然折行 ✓
                        //   而自动换行只写 prefH、父 vbox 不一定当帧重读 ⇒ 第二行会被压掉 ✗（F9 报告里只看到 4 个 ✗）
                        //   ⇒ 这里写死两行高（比依赖时序稳 ✓ 一行时略空一点 ✓ 可接受 ✓）
                        row.prefH = com.ovo.sablestopnow.client.gui.lib.Widget.AUTO;
                        // ⚠ 原来这一行**点一下就是删除**，按钮标签却只是「拉伸N」——
                        // 一个看起来像"选中"的按钮实际会删掉东西，是个真实的误操作陷阱。
                        // 现在拆成两个明确的按钮。
                        final var texBtn = new Button(
                                Component.translatable("model.texture.open"),
                                () -> {
                                    textureSolidId = solid.id();
                                    textureScale.set(solid.appearance().blocksPerTile());
                                    refreshLeftPanel();
                                });
                        texBtn.prefW = Widget.FILL;
                        row.add(texBtn);
                        final var delBtn = new Button(
                                Component.translatable("model.extrude.delete"),
                                () -> {
                                    if (solid.id().equals(textureSolidId)) {
                                        textureSolidId = null;
                                    }
                                    com.ovo.sablestopnow.network.SolidNetworking.sendRemove(solid.id());
                                    refreshLeftPanel();
                                });
                        delBtn.prefW = Widget.FILL;
                        row.add(delBtn);
                        body.add(row);
                        // 正在编辑这个实体的贴图 → 紧跟其后展开编辑区
                        if (solid.id().equals(textureSolidId)) {
                            buildTextureEditor(body, solid);
                        }
                    }
                }
            }
        } else if (ModelTools.NORMAL_TO.equals(tool)) {
            body.add(new Label(Component.translatable("model.left.normal_to")));
        } else if (MateClientState.highlightedMate() != null) {
            // 树里点了一条配合：同一个编辑页（与创建页完全一致）
            MateEditUi.buildEditor(body, MateClientState.highlightedMate());
        } else {
            final UUID model = ModelMode.selectedModel();
            if (model == null) {
                body.add(new Label(Component.translatable("model.left.no_model")));
            } else {
                body.add(new Label(Component.literal(shortId(model))).shadow(true));
                body.add(new Label(Component.translatable("model.left.mate_count",
                        MateClientState.matesOf(model).size())));
                body.add(new Label(Component.translatable("model.left.hint_select")));
            }
        }

        if (completeButton != null) {
            // ⚠ 判据是「有没有正在编辑的东西」，不是「当前是不是某个工具」。
            // 从树里点进一个施力点/耦合组编辑时 tool 是 none，但「完成」显然应该可用 ——
            // 以前这里只认 tool，于是编辑施力点时按钮是灰的（用户实测反馈
            // 「编辑施力点时无法点击完成，创建施力点时却可以」）。
            final boolean editing = !ModelTools.NONE.equals(tool)
                    || ModelForceUi.selectedId() != null
                    || ModelCouplingUi.hasSelection()
                    || MateClientState.highlightedMate() != null;
            completeButton.enabled = editing;
        }
    }

    private static void chooseType(final MateType type) {
        chosenType = type;
        refreshLeftPanel();
    }

    // ============ 完成 / 取消 ============

    /**
     * 「完成」：结束当前这次操作，并保留已经产生的结果。
     *
     * <h2>为什么「编辑已有对象」必须排在最前面</h2>
     * 下面每一条都按 {@link ModelMode#tool()} 分派，而从<b>树里点进某个施力点</b>去编辑时
     * {@code tool} 是 {@code none}（玩家并没有在「放置」）—— 于是「完成」落到最后那行
     * {@code setTool(NONE)}，什么都没干，左栏也仍然停在编辑器上。
     * 用户实测反馈「编辑施力点时无法点击完成，创建施力点时却可以」就是这条。
     *
     * <p>所以判据改成「<b>有没有正在编辑的东西</b>」而不是「当前是哪个工具」：
     * 只要左栏在编辑某个对象，「完成」就结束编辑。工具与选择是两条正交的线，不该互相决定对方能不能结束。
     */
    public static void completeTool() {
        final String tool = ModelMode.tool();

        // 草图环境：「完成」= 提交整张草图给服务端并退出编辑。
        // 放在最前面：草图有自己的一整套状态，不该落到下面「选参考」的那套逻辑里。
        if (ModelTools.SKETCH.equals(tool)) {
            com.ovo.sablestopnow.client.SketchClient.finish();
            ModelMode.setTool(ModelTools.NONE);
            ModelMode.prompt("model.prompt.sketch_done");
            refreshLeftPanel();
            return;
        }

        // 正在编辑施力点（放置模式下也一样：先落点、再调参、再完成）
        if (ModelTools.FORCE.equals(tool) || ModelForceUi.selectedId() != null) {
            ModelForceUi.select(null);
            ModelMode.clearPicks();
            ModelMode.setTool(ModelTools.NONE);
            ModelMode.prompt("model.prompt.force_done");
            afterEdit();
            return;
        }
        // 正在编辑比例/齿轮组（同样可能是从树里点进来的，tool == none）
        if (ModelCouplingUi.hasSelection() && !ModelTools.RATIO.equals(tool)
                && !ModelTools.GEAR.equals(tool)) {
            ModelCouplingUi.clearSelection();
            ModelMode.prompt("model.prompt.coupling_done");
            afterEdit();
            return;
        }

        // 正在编辑一条已有配合（从树里点进来的，tool == none）
        if (MateClientState.highlightedMate() != null && !ModelTools.MATE.equals(tool)) {
            MateClientState.setHighlightedMate(null);
            ModelMode.prompt("model.prompt.mate_done");
            afterEdit();
            return;
        }
        if (ModelTools.NORMAL_TO.equals(tool)) {
            final MateRef ref = ModelMode.hoveredRef();
            if (ref != null) {
                normalTo(ref);
            }
            return;
        }
        if (ModelTools.MATE.equals(tool)) {
            if (ModelMode.picked().size() < ModelTools.minRefs(tool)) {
                ModelMode.prompt("model.prompt.refs_more", ModelTools.minRefs(tool));
                return;
            }
            // 创建页填好的整套草稿（类型/数值/区间/翻转/名字）随命令一起发出去
            final MateType type = MateEditUi.currentType();
            if (type == null || !MateEditUi.sendCreate()) {
                ModelMode.prompt("model.prompt.choose_type");
                return;
            }
            ModelMode.prompt("mate.prompt.created", Component.translatable("mate.type." + type.id()));
            ModelMode.clearPicks();
            ModelMode.setTool(ModelTools.NONE);
            afterEdit();
            return;
        }
        if (ModelTools.RATIO.equals(tool)) {
            if (ModelMode.picked().size() < ModelTools.minRefs(tool)) {
                ModelMode.prompt("model.prompt.refs_more", ModelTools.minRefs(tool));
                return;
            }
            com.ovo.sablestopnow.coupling.CouplingClientState.requestCreateRatio(ModelMode.picked());
            ModelMode.prompt("model.prompt.ratio_created");
            ModelMode.clearPicks();
            ModelMode.setTool(ModelTools.NONE);
            afterEdit();
            return;
        }
        if (ModelTools.GEAR.equals(tool)) {
            if (ModelMode.picked().size() != ModelTools.maxRefs(tool)) {
                ModelMode.prompt("model.prompt.refs_more", ModelTools.maxRefs(tool));
                return;
            }
            // 默认 20:40（比值 0.5）——齿数比的手填界面在下一轮补上，先让链路可用
            com.ovo.sablestopnow.coupling.CouplingClientState.requestCreateGear(ModelMode.picked(),
                    20, 40, false);
            ModelMode.prompt("model.prompt.gear_created");
            ModelMode.clearPicks();
            ModelMode.setTool(ModelTools.NONE);
            afterEdit();
            return;
        }

        // 什么都没在编辑：也给一句话，别让按钮看起来是坏的
        ModelMode.clearPicks();
        ModelMode.setTool(ModelTools.NONE);
        ModelMode.prompt("model.prompt.nothing_to_finish");
        afterEdit();
    }

    /** 「取消」：放弃当前操作，把选择和工具都清干净。
     *
     * <p>与「完成」对称：不管是「正在放置」还是「从树里点进来编辑」，
     * 取消都必须回到干净状态。以前它只清工具，施力点/耦合的选中会留着，
     * 于是左栏看起来「取消失败了」。 */
    public static void cancelTool() {
        // 草图有自己的一套状态（选面/编辑中），取消时要一并复位，否则会留下一个
        // 「工具已经退出、但 SketchMode 还以为在编辑」的幽灵状态。
        com.ovo.sablestopnow.client.SketchClient.cancel();
        ModelForceUi.select(null);
        ModelCouplingUi.clearSelection();
        MateClientState.setHighlightedMate(null);
        ModelMode.setTool(ModelTools.NONE);
        ModelMode.clearPicks();
        chosenType = null;
        afterEdit();
    }

    /** 编辑结束后的统一收尾：上栏与左栏各重建一次，并关掉挂着的弹层。 */
    private static void afterEdit() {
        buildTop();
        refreshLeftPanel();
    }

    public static void startTool(final String toolId) {
        ModelCouplingUi.clearSelection();
        ModelForceUi.select(null);
        MateClientState.setHighlightedMate(null);
        if (ModelTools.MATE.equals(toolId)) {
            // 进入配合流程 = 打开创建页：草稿复位到默认类型
            MateEditUi.beginCreate(null);
        }
        ModelMode.setTool(toolId);
        ModelMode.clearPicks();
        chosenType = null;
        afterEdit();
    }

    /**
     * 正视此面：相机站到该面的法线外侧，正对着这个面看回来。
     *
     * <p>⚠ 视线方向必须与法线<b>相反</b>。相机位置是 {@code target - 视线 · 距离}
     * （见 {@code ModelCamera.position()}）：只有让视线朝着面，相机才落在法线外侧、看到正面。
     * 早先直接拿法线当视线（yaw 少取了一次反号），相机被放到了面的背后，
     * 用户实测反馈「正视此面时总是正视选择面的背面」。
     *
     * <p>yaw/pitch 是 Minecraft 约定：
     * {@code 视线 = (-sin(yaw)cos(pitch), -sin(pitch), cos(yaw)cos(pitch))}（与 {@code ModelCamera.viewVector} 一致）。
     * 反解 {@code 视线 = -n} 得：{@code yaw = atan2(n.x, -n.z)}、{@code pitch = asin(n.y)}。
     */
    public static void normalTo(final MateRef ref) {
        if (ref.face() == null) {
            return;
        }
        final dev.ryanhcode.sable.sublevel.SubLevel body = MateTargeting.byId(ref.body());
        if (body == null) {
            return;
        }
        final org.joml.Vector3d world = MateTargeting.worldDirection(body, ref);
        if (world == null) {
            return;
        }
        final double yaw = Math.toDegrees(Math.atan2(world.x, -world.z));
        final double pitch = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, world.y))));
        ModelCamera.alignTo((float) yaw, (float) pitch);
        ModelMode.prompt("model.prompt.normal_to");
    }

    // ============ 上下文菜单 / 视图弹窗 ============

    public static void openContextMenu(final Minecraft mc, final int x, final int y) {
        final UiLayer layer = Ui.layer(LAYER_TREE);
        if (layer == null) {
            return;
        }
        final MenuList menu = new MenuList(110);
        menu.add(MenuList.Entry.of("model.menu.hide", ModelUi::toggleHide));
        menu.add(MenuList.Entry.of("model.menu.delete_mate", ModelUi::deleteSelectedMate));
        menu.add(MenuList.Entry.separator());
        menu.add(MenuList.Entry.of("model.view.front", () -> ModelCamera.setPreset(ModelCamera.Preset.FRONT)));
        menu.add(MenuList.Entry.of("model.view.iso", () -> ModelCamera.setPreset(ModelCamera.Preset.ISO)));
        menu.add(MenuList.Entry.of("model.menu.fit", ModelUi::fitView));
        menu.add(MenuList.Entry.of("model.menu.center", ModelUi::centerSelectedModel));
        menu.openAt(x, y);
        layer.root.openPopup(menu, null);
        Ui.toFront(layer);
    }

    public static void openViewPopup() {
        final UiLayer layer = Ui.layer(LAYER_TOP);
        if (layer == null) {
            return;
        }
        final MenuList menu = new MenuList(120);
        menu.add(MenuList.Entry.of("model.view.front", () -> ModelCamera.setPreset(ModelCamera.Preset.FRONT)));
        menu.add(MenuList.Entry.of("model.view.back", () -> ModelCamera.setPreset(ModelCamera.Preset.BACK)));
        menu.add(MenuList.Entry.of("model.view.left", () -> ModelCamera.setPreset(ModelCamera.Preset.LEFT)));
        menu.add(MenuList.Entry.of("model.view.right", () -> ModelCamera.setPreset(ModelCamera.Preset.RIGHT)));
        menu.add(MenuList.Entry.of("model.view.top", () -> ModelCamera.setPreset(ModelCamera.Preset.TOP)));
        menu.add(MenuList.Entry.of("model.view.bottom", () -> ModelCamera.setPreset(ModelCamera.Preset.BOTTOM)));
        menu.add(MenuList.Entry.of("model.view.iso", () -> ModelCamera.setPreset(ModelCamera.Preset.ISO)));
        final int width = 120;
        menu.openAt((net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledWidth() - width) / 2,
                Math.max(4, net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScaledHeight() / 2 - 60));
        layer.root.openPopup(menu, null);
        Ui.toFront(layer);
    }

    public static boolean hasPopup() {
        for (final UiLayer layer : Ui.layers()) {
            if (layer.root.hasPopup()) {
                return true;
            }
        }
        return false;
    }

    public static void closePopup() {
        for (final UiLayer layer : Ui.layers()) {
            layer.root.closePopup();
        }
    }

    // ============ 查询 / 工具 ============

    /** 鼠标是否落在任一模型模式面板上（决定要不要把点击交给世界）。 */
    public static boolean pointerOverPanel() {
        for (final UiLayer layer : Ui.layers()) {
            if (!layer.visible) {
                continue;
            }
            final Widget hit = layer.root.hitTest(Ui.mouseXd(), Ui.mouseYd());
            if (hit != null && hit != layer.root) {
                return true;
            }
        }
        return false;
    }

    /** 是否有输入框正在编辑（此时键盘不归模型模式管）。 */
    public static boolean focusedTextField() {
        for (final UiLayer layer : Ui.layers()) {
            if (layer.root.focused instanceof com.ovo.sablestopnow.client.gui.lib.TextField) {
                return true;
            }
        }
        return false;
    }

    public static void deleteSelectedMate() {
        // 选中的是耦合时删耦合，选中的是配合时删配合 —— 同一个键，看当前选的是什么
        if (ModelCouplingUi.hasSelection()) {
            ModelCouplingUi.deleteSelected();
            return;
        }
        final Mate mate = MateClientState.highlightedMate();
        if (mate == null) {
            ModelMode.prompt("model.prompt.no_mate");
            return;
        }
        VeilPacketManager.server().sendPacket(MateNetworking.MateCommandPayload.remove(mate.id()));
        MateClientState.setHighlightedMateId(null);
        refreshTree();
        refreshLeftPanel();
    }

    private static void toggleHide() {
        final UUID id = ModelMode.selectedModel();
        if (id == null) {
            ModelMode.prompt("model.prompt.no_model");
            return;
        }
        ModelProperties.setHidden(id, !ModelProperties.hidden(id));
        refreshTree();
    }

    private static Component mateLabel(final Mate mate) {
        if (mate.name() != null && !mate.name().isEmpty()) {
            return Component.literal(mate.name());
        }
        return Component.translatable("mate.type." + mate.type().id());
    }

    public static String shortId(final UUID id) {
        return id.toString().substring(0, 8);
    }

    /** 树里属性行的数据载体。 */
    private record PropertyToggle(java.util.function.Supplier<Boolean> getter,
                                  java.util.function.Consumer<Boolean> setter) {
    }

    // ============ HUD：当前功能提示（避开上边栏） ============

    private static void buildHud() {
        UiLayer layer = Ui.layer(LAYER_HUD);
        if (layer == null) {
            layer = Ui.open(UiLayer.of(LAYER_HUD).size(0, 0));
        }
        layer.root.clear();
        layer.root.layout = Layouts.dock(4, 4);
        // 让出上边栏的高度：提示条不能压在上边栏上（库里的避让是给 HUD 用的，这里直接留白更省事）
        layer.root.padding(0, TOP_H + 6, 0, 0);
        layer.hud = true;
        // 与上边栏一起从上边滑入（比它稍慢一点，看起来是「跟在后面」）
        layer.slideFromY = -14;

        final Container box = new Container();
        box.dock = Layouts.Dock.TOP;
        box.prefH = 13;
        box.bg = 0x88141A5C;
        box.layout = Layouts.hbox(3, 4);
        box.add(new Label(() -> Component.translatable(ModelTools.hintKey(ModelMode.tool()))).color(0xFFCFD8FF));
        layer.root.add(box);
        layer.visible = true;
    }

    /** 让某个 HUD 元素避开面板：返回安全位置。 */
    public static int[] hudSlot(final int w, final int h, final com.ovo.sablestopnow.client.gui.lib.UiDock.Corner corner) {
        return com.ovo.sablestopnow.client.gui.lib.UiDock.place(w, h, corner, 4);
    }
}
