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
                        () -> {
                            cutMode = false;   // 普通拉伸 ⇒ 关掉切割 ✓
                            startExtrude();
                        }).tooltipKey("model.tool.extrude.hint"));
                // item 10: pattern entry
                row.add(shortButton("model.tool.pattern", "model.tool.pattern.short",
                        () -> {
                            cutMode = false;
                            com.ovo.sablestopnow.client.model.PatternTool.reset();
                            startTool(ModelTools.PATTERN);
                        }).tooltipKey("model.tool.pattern.hint"));
                // item 9: mirror entry
                row.add(shortButton("model.tool.mirror", "model.tool.mirror.short",
                        () -> {
                            cutMode = false;
                            mirrorSources.clear();
                            mirrorPlaneOrigin = null;
                            mirrorPlaneSegs = null;
                            mirrorPlaneNormal = null;
                            startTool(ModelTools.MIRROR);
                        }).tooltipKey("model.tool.mirror.hint"));
                // ★ item 8：**圆角**入口 ✓（上边栏）
                row.add(shortButton("model.tool.fillet", "model.tool.fillet.short",
                        () -> {
                            cutMode = false;
                            startTool(ModelTools.FILLET);
                            refreshLeftPanel();
                        }).tooltipKey("model.tool.fillet.hint"));
                // ★ item 7：**新建基准面**入口 ✓（上边栏）
                row.add(shortButton("model.tool.plane", "model.tool.plane.short",
                        () -> {
                            cutMode = false;
                            startTool(ModelTools.PLANE);
                            refreshLeftPanel();
                        }).tooltipKey("model.tool.plane.hint"));
                // ★ 需求 ⑥：**切割台体**独立入口 ✓（与"拉伸台体"平级 ✓ 不是藏在面板里的开关 ✓）
                row.add(shortButton("model.tool.cut", "model.tool.cut.short",
                        () -> {
                            cutMode = true;    // 进来就是切割模式 ✓
                            startExtrude();
                        }).tooltipKey("model.tool.cut.hint"));
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

        // ★ 需求 ⑥：**切割台体**开关 ✓（拉伸出一个体，给同一 body 内与它相交的实体打洞 ✓）
        //   参考 SolidWorks 的"拉伸-切除"：同一个深度/方式面板，只是结果不做材料而是做减法 ✓
        final var cutBtn = new com.ovo.sablestopnow.client.gui.lib.Button(
                Component.translatable(cutMode ? "model.extrude.cut_on" : "model.extrude.cut"),
                () -> {
                    cutMode = !cutMode;
                    refreshLeftPanel();
                });
        cutBtn.prefW = com.ovo.sablestopnow.client.gui.lib.Widget.FILL;
        cutBtn.toggled = cutMode;
        body.add(cutBtn);

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

        body.add(new Button(applyLabel("model.extrude.create"),
                () -> createExtrude()).width(Widget.FILL));
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

        body.add(new Button(applyLabel("model.revolve.create"),
                () -> createRevolve()).width(Widget.FILL));
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
                    .withBlocksPerTile(textureScale.get())
                    // ★ connected 以前**从来没被保存过**（复选框只是改了一个 UI 待定值）
                    .withConnected(Boolean.TRUE.equals(textureConnected.get())));
            refreshLeftPanel();
        }).width(Widget.FILL));
          // item 11: connected texture toggle (needs block ctm data)
          body.add(new com.ovo.sablestopnow.client.gui.lib.Checkbox(
                  textureConnected, "model.texture.connected"));

        if (look.hasTexture()) {
            body.add(new Button(Component.translatable("model.texture.clear"),
                    () -> saveAppearance(solid,
                            com.ovo.sablestopnow.sketch.SolidAppearance.EMPTY))
                    .width(Widget.FILL));
        }
        body.add(new Button(Component.translatable("model.texture.close"),
                () -> closeTextureEditor(false)).width(Widget.FILL));
    }

    /**
     * 关闭贴图编辑区 ✓
     *
     * @param apply true = 先把待定参数（平铺比例 / 连接纹理）写回实体（“完成”用）；
     *              false = 直接关闭、丢弃待定值（“关闭”/“取消”用）
     */
    private static void closeTextureEditor(final boolean apply) {
        final com.ovo.sablestopnow.sketch.Solid solid = resolveSolid(textureSolidId);
        if (apply && solid != null) {
            saveAppearance(solid, solid.appearance()
                    .withBlocksPerTile(textureScale.get())
                    .withConnected(Boolean.TRUE.equals(textureConnected.get())));
        }
        textureSolidId = null;
        textureBlockQuery.set("");
        textureSearchApplied.set("");
        lastLeftSignature = null;
        refreshLeftPanel();
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
        // ★ 用户要求：**左栏只显示选中图元的约束/尺寸** ✓（"左侧约束和尺寸建议只显示选中的图元的"）
        //   做法：把"选中图元用到的变量下标"收集起来，只保留**引用了其中任一变量**的尺寸 ✓
        //   （没有选中任何图元时照旧全部显示 ✓ 不然空草图会看起来"什么都没标"✗）
        final var existingAll = com.ovo.sablestopnow.sketch.SketchMeasure.existingDimensions(live);
        final java.util.Set<Integer> pickedVars = new java.util.HashSet<>();
        final var pickedEntities = session.selection();
        for (final var pe : pickedEntities) {
            for (final int pv : pe.vars()) {
                pickedVars.add(pv);
            }
        }
        final java.util.List<com.ovo.sablestopnow.sketch.SketchMeasure.Dimension> existing;
        if (pickedVars.isEmpty()) {
            existing = existingAll;
        } else {
            final java.util.List<com.ovo.sablestopnow.sketch.SketchMeasure.Dimension> filtered =
                    new java.util.ArrayList<>();
            for (final var dd : existingAll) {
                boolean hit = false;
                final int ci = dd.constraintIndex();
                if (ci >= 0 && ci < live.constraints().size()) {
                    for (final int ov : live.constraints().get(ci).operands()) {
                        if (pickedVars.contains(ov)) {
                            hit = true;
                        }
                    }
                }
                if (hit) {
                    filtered.add(dd);
                }
            }
            existing = filtered;
        }
        final var refs = com.ovo.sablestopnow.sketch.SketchMeasure.referenceDimensions(live);
        // ★ 需求 3（参考 SolidWorks）：**多选时只列出"和选中图元相关"的可用约束** ✓
        //   原来把整张草图的建议关系全列出来 ✗（选什么都看到同一堆 ✗）
        //   ⇒ 先算"选中图元用到的变量"，再只保留**引用了其中任一变量**的关系 ✓
        //   （与下面尺寸过滤同一套口径 ✓）
        final java.util.Set<Integer> relPickVars = new java.util.HashSet<>();
        for (final var rpe : session.selection()) {
            for (final int rpv : rpe.vars()) {
                relPickVars.add(rpv);
            }
        }
        final java.util.List<com.ovo.sablestopnow.sketch.SketchMeasure.ImpliedRelation> relations;
        if (relPickVars.isEmpty()) {
            // ★ 修实测"可补约束在无选中图元时显示全部，占很多空间" ✗✓
            //   ⇒ **没选图元就一条都不显示** ✓（要加约束必须先选图元 ✓ 与 SolidWorks 一致 ✓）
            relations = java.util.List.of();
        } else {
            final java.util.List<com.ovo.sablestopnow.sketch.SketchMeasure.ImpliedRelation> rf =
                    new java.util.ArrayList<>();
            for (final var rel0 : com.ovo.sablestopnow.sketch.SketchMeasure
                    .impliedRelations(live, 1.0)) {
                // ★ 修实测"相切选项出现许多个" ✗✓
                //   原来用的是"**任一**变量命中就保留" ⇒ 只要选了一条线，所有"该线↔任意圆/弧"
                //   的相切建议**全都列出来** ✗（所以看到一堆 ✓）
                //   ⇒ 改成"**该关系的全部变量都在选中图元里**"才保留 ✓
                //     （相切关系 = 线的 4 个 + 圆的 3 个 ⇒ 必须把线和圆都选中才出现 ✓）
                boolean allPicked = true;
                for (final int rv0 : rel0.vars()) {
                    if (!relPickVars.contains(rv0)) {
                        allPicked = false;
                        break;
                    }
                }
                if (allPicked) {
                    rf.add(rel0);
                }
            }
            relations = rf;
        }
        // ★ **不要**因为"现在还没有任何尺寸"就整块不建 ✗✓（用户实测反馈：
        //   "草图界面不显示智能尺寸按键，只有选择图元后才显示" ✗）
        //   原因就是这里原先直接 return ⇒ 全新草图（无尺寸/无参考/无关系）连**入口按钮**都没有 ✓
        //   ⇒ 现在照常建区块（列表为空时下面自然什么都不多显示 ✓），入口**始终可见** ✓。
        //   注：下面 existing/refs/relations 为空时各自的分支都不会加东西 ✓，所以没有副作用 ✓。
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
        // ★★ **几何关系一键补**（相切 / 水平 / 竖直 …）✓✓
        //   用户实测："选择直线和圆弧后仍然没有相切按钮" ✗
        //   根因：`relations` 在上面**算出来了却从来没被渲染成按钮** ✗（这条分支是补的 ✓）
        //   ⇒ 现在把每条可补关系渲染成一键按钮 ✓ 点了真的落约束 + 求解 + 刷新 ✓
        if (!relations.isEmpty()) {
            body.add(new Label(Component.translatable("model.dim.relations")).color(0xFF8FA0D8));
            final com.ovo.sablestopnow.client.gui.lib.Container relRow =
                    new com.ovo.sablestopnow.client.gui.lib.Container();
            relRow.layout = Layouts.hboxWrap(0, 4, 2);
            relRow.prefW = com.ovo.sablestopnow.client.gui.lib.Widget.FILL;
            relRow.prefH = com.ovo.sablestopnow.client.gui.lib.Widget.AUTO;
            for (final var rel : relations) {
                relRow.add(new Button(Component.translatable(rel.labelKey()), () -> {
                    // ★ 修实测"点任意一个后草图变红" ✗✓
                    //   原因：这条关系是**无条件建议**的（哪怕现在并不相切 ✓）
                    //   ⇒ 加上去后求解器无法满足 ⇒ 整张草图标红 ✗
                    //   处理：求解后若不满足就**回滚掉这条约束** ✓ 并提示 ✓
                    final int before = live.constraints().size();
                    live.addConstraint(rel.solverKind(), 0.0, rel.vars());
                    com.ovo.sablestopnow.client.SketchClient.mode().solve();
                    final var solved = com.ovo.sablestopnow.client.SketchClient.mode().lastSolve();
                    if (solved == null || !solved.ok()) {
                        if (live.constraints().size() > before) {
                            live.removeConstraint(live.constraints().size() - 1);
                        }
                        com.ovo.sablestopnow.client.model.ModelMode.prompt("model.dim.unsatisfied");
                        com.ovo.sablestopnow.client.SketchClient.mode().solve();
                    }
                    refreshLeftPanel();
                }));
            }
            body.add(relRow);
        }
        // ★ **辅助线**开关 ✓（SolidWorks 的 Construction Geometry ✓ 用户要求 ✓）
        //   作用对象 = 当前**选中的图元** ✓（选中一条线再点 ⇒ 它变辅助线/变回实体 ✓）
        //   外观 ✓ 世界渲染画实线 + 我在 SketchHud 叠一层**虚线** ✓（用户确认：辅助线就是虚线 ✓）
        //   ⚠ 教训 ✓：上一轮我删「吸附即约束」按键时**把它一起删了** ✗（删除区间要按行核对 ✓）
        // ★ **尺寸链**开关 ✓（需求 (3) 的最后一条 ✓ 与"辅助线"同一套写法 ✓）
        final var chainBtn = new Button(Component.translatable("model.dim.chain"), () -> {
            session.setChainMode(!session.chainMode());
            refreshLeftPanel();
        });
        chainBtn.toggled = session.chainMode();
        body.add(chainBtn);
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
            // ★ 需求 1：约束也**带编号** ✓（"平行1、平行2"这种 ✓ 编号 = 约束下标 +1 ✓ 与尺寸同一口径 ✓）
            final String relName = "#" + (index + 1) + " "
                    + Component.translatable(nameKey).getString();
            row.add(new Label(Component.literal(relName)).color(
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
            // ★ 角度存的是**有向角** ⇒ 实测可能为 −θ ⇒ 按**绝对值**比较 ✓（否则会误报标红）
            final boolean off = Math.abs(Math.abs(d.measured()) - Math.abs(d.value())) > 1.0e-4;
            // ★ 用户要求：约束/尺寸**要编号** ✓（"左侧约束和尺寸建议…并进行编号" ✓）
            //   编号用**约束下标 +1**（与画布上双击改值的对象一致 ✓ 稳定不随排序跳 ✓）
            final String num = "#" + (d.constraintIndex() + 1) + " ";
            // ★ 水平/竖直尺寸存有符号差值 ⇒ 左栏也按**绝对值**显示 ✓
            final String text = off
                    ? String.format(java.util.Locale.ROOT, "%s%s %.3f  (实测 %.3f)", num,
                            Component.translatable(d.labelKey()).getString(),
                            Math.abs(d.value()), Math.abs(d.measured()))
                    : String.format(java.util.Locale.ROOT, "%s%s %.3f", num,
                            Component.translatable(d.labelKey()).getString(),
                            Math.abs(d.value()));
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
    private static boolean createRevolve() {
        final com.ovo.sablestopnow.sketch.Sketch source = currentExtrudeSource();
        if (source == null) {
            ModelMode.prompt("model.prompt.extrude_pick_sketch");
            return false;
        }
        if (revolveAxis == null) {
            ModelMode.prompt("model.revolve.no_axis");
            return false;
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
        // ★ 把"本会话刚创建过、还没同步回来"的序号也算上 ✓（修"编号永远是 1"✗）
        index = Math.max(index, LOCAL_SOLID_INDEX.getOrDefault(source.body(), 0) + 1);
        // ★ 修实测"旋转创建后没有模型、草图却被吃掉"✗：
        //   旋转需要**闭合轮廓**；轮廓不闭合时引擎会产出空几何 ⇒ 以前会静默建一个看不见的体 ✗
        //   ⇒ 先算一遍线框，空的就**直接提示**、不建体、不吃草图 ✓
        final int edgeCount = com.ovo.sablestopnow.sketch.Revolve.edges(source, params).size();
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[revolve] index={} angle={} edges={} axis=({},{},{},{})",
                index, String.format(java.util.Locale.ROOT, "%.1f", params.angleDeg()),
                edgeCount, revolveAxis[0], revolveAxis[1], revolveAxis[2], revolveAxis[3]);
        if (edgeCount == 0) {
            ModelMode.prompt("model.revolve.empty_profile");
            return false;
        }
        // ★ 编辑已有旋转 ⇒ 原地更新（同 id / 序号 / 草图）✓
        if (commitShape(new com.ovo.sablestopnow.sketch.Solid.Shape.Revolution(params))) {
            com.ovo.sablestopnow.client.model.FeaturePreview.clear();
            lastPreviewSig = "";
            return true;
        }
        com.ovo.sablestopnow.network.SolidNetworking.sendSave(
                com.ovo.sablestopnow.sketch.Solid.createRevolution(
                        source.body(), source.id(), index, params));
        com.ovo.sablestopnow.client.model.FeaturePreview.clear(); // 已提交 ⇒ 释放预览 ✓
        lastPreviewSig = "";
        ModelMode.prompt("model.prompt.extrude_created");
        refreshLeftPanel();
        return true;
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
            // 可观测性 ✓（放模组侧：SketchSession 被离线 harness 单独编译 ✗ 不能依赖 SablestopNow ✓）
            com.ovo.sablestopnow.SablestopNow.LOGGER.info("[sketch] tool={}", value);
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

    // ================= 「随指针实时联动」的轻量同步 ✓（需求 (4) ✓）=================

    /** 当前左栏显示的候选按钮 ✓（面板重建时刷新 ✓ 每帧只改它们的 toggled ✓）。 */
    private static final java.util.List<Button> SMART_CAND_BTNS = new java.util.ArrayList<>();

    /**
     * 每帧把候选按钮的高亮同步到"当前预览候选" ✓。
     *
     * <p>为什么不把预览放进 leftPanelSignature ✗✓：那会让**每帧重建整栏** ✗
     * 而文件里已有实测教训 —— 重建会把正在打字的输入框换成新实例、丢焦点 ✗（见签名那段注释 ✓）。
     * 所以这里只改 toggled 标志 ✓ 不碰控件实例 ✓ 不碰焦点 ✓。
     */
    public static void syncSmartCandidateToggles() {
        if (SMART_CAND_BTNS.isEmpty()) {
            return;
        }
        final var session = com.ovo.sablestopnow.client.SketchClient.mode().session();
        final var live = session == null ? null : session.working();
        if (live == null || session.selection().isEmpty()) {
            return; // 没草图/没选择 ⇒ 保持原样 ✓
        }
        final var smart = com.ovo.sablestopnow.sketch.SketchMeasure
                .smartDimension(live, session.selection());
        if (smart == null) {
            return;
        }
        final var cands = com.ovo.sablestopnow.sketch.SketchMeasure.variants(live, smart);
        final var shown = com.ovo.sablestopnow.sketch.SketchMeasure.preview(
                live, session.selection(), session.hoverU(), session.hoverV(),
                session.lockedVariant());
        if (shown == null || cands.size() != SMART_CAND_BTNS.size()) {
            return; // 候选集变了 ⇒ 等下一次面板重建 ✓（不在这里动结构 ✗）
        }
        for (int i = 0; i < cands.size(); i++) {
            final var c = cands.get(i);
            final boolean same = c.solverKind() == shown.solverKind()
                    && Math.abs(c.value() - shown.value()) < 1.0e-9;
            SMART_CAND_BTNS.get(i).toggled = same;
        }
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

        // ★ 记住当前这棵树 ✓：草图里选中图元后要**反查**对应节点并高亮 ✓
        //   （用户要求"草图和右侧树状图联动" ✓ —— 树 → 图形区已做 ✓，这是反方向 ✓）
        final TreeView view = new TreeView();
        activeTree = view;
        view.prefH = Widget.FILL;
        view.rowH = 12;
        view.indent = 7;
        view.rowBuilder = ModelUi::buildRow;
        view.onSelect = item -> {
            // ⚠ 树上的四种选择是**互斥**的。以前只有「耦合」之间互清，施力点的选择会一直留着，
            // 于是左栏被钉死在施力点编辑器上：点模型、点配合都不换内容
            //（用户实测反馈「左侧栏未跟随树状图的选择而变化」）。
            // ★ 用户要求：树里点**草图** ⇒ 图形区**高亮该草图** ✓（与"点模型/配合"同一套互斥选择 ✓）
            //   复用渲染器现成的"正在编辑的草图 ⇒ 高亮"通道 ✓（SketchClient 进入编辑时用的也是它 ✓）
            if (item.data instanceof com.ovo.sablestopnow.sketch.Sketch pickedSketch) {
                // ★ 需求 ⑦：**拉伸/切割工具下**点草图 = 选它当**拉伸源** ✓
                //   （以前一律进入草图编辑 ⇒ 拉伸时根本选不到草图 ✗ 用户实测反馈 ✓）
                if (com.ovo.sablestopnow.client.model.ModelTools.EXTRUDE.equals(
                        com.ovo.sablestopnow.client.model.ModelMode.tool())) {
                    extrudeSource = pickedSketch.id();
                    refreshLeftPanel();
                    return;
                }
                // ★ 用户实测："现在无法从右侧树状图点击进入草图" ✗
                //   原来这里只做了**高亮**（setEditing ✓）⇒ 看着像"选中了"却不进编辑 ✗
                //   ⇒ 改成走**真正的入口** `SketchClient.edit(sketch)` ✓（左栏的"编辑"按钮用的就是它 ✓）
                com.ovo.sablestopnow.client.SketchClient.edit(pickedSketch);
                // ★★ 修实测"右树点草图后左栏仍无变化" ✓✓ —— **真因就在这里** ✓
                //   草图面板其实一直在左栏里（见 buildDimensionPanel 的调用 ✓），但整块是被
                //   **外层"当前功能"分支**包住的 ✗ ⇒ 只调 `SketchClient.edit(...)` 时功能还是
                //   原来的（拉伸/配合…）⇒ 草图块永远不会被渲染 ✓
                //   ⇒ 点树里的草图 = **同时把功能切到草图** ✓
                com.ovo.sablestopnow.client.model.ModelMode.setTool("sketch");
                // ★ 诊断（实测"右树点草图后左栏仍无变化"✗）：一行区分四种可能 ✓
                //   active=是否进草图 / stage / leftBody=左栏容器是否为 null / sig=重建指纹是否变了
                com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                        "[tree-edit] active={} stage={} leftBody={} sigChanged={}",
                        com.ovo.sablestopnow.client.SketchClient.active(),
                        com.ovo.sablestopnow.client.SketchClient.mode().stage(),
                        leftBody != null,
                        !leftPanelSignature().equals(lastLeftSignature));
                refreshLeftPanel();
                com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                        "[tree-edit] after refresh: sigChanged={}",
                        !leftPanelSignature().equals(lastLeftSignature));
                return;
            }
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
                  // ★ 顺序调整/删除：**必须像 texture: 一样在这里判** ✓
                  //   （之前写成外层 else-if，先被"实体"分支吃掉 ⇒ 永远轮不到 ⇒ 点了没反应）
                  if (item.key.startsWith("feat:")) {
                      final String[] fparts = item.key.split(":");
                      com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                              "[tree] action key={} parts={} idx={}",
                              item.key, fparts.length, treeSolid.index());
                      if (fparts.length >= 4) {
                          featureAction(fparts[1], treeSolid);
                      }
                      return;
                  }
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
                selectedSolidId = solid.id();
                  if (ModelTools.PATTERN.equals(ModelMode.tool())) {
                      com.ovo.sablestopnow.client.model.PatternTool.toggleSource(solid);
                      refreshLeftPanel();
                      return;
                  }
                  if (ModelTools.MIRROR.equals(ModelMode.tool())) {
                      mirrorToggleSource(solid);
                      return;
                  }
                // 点拉伸实体：只把它的结构选中（左栏的拉伸页会把可删的实体列出来）。
                // 不做"点一下就删"—— 删除是不可逆的，必须走左栏那个明确的按钮。
                ModelForceUi.select(null);
                ModelCouplingUi.clearSelection();
                MateClientState.setHighlightedMate(null);
                  // ★ item 7：点**基准面**行 ⇒ 直接以它为基准面开草图 ✓
                  if (solid.shape() instanceof final com.ovo.sablestopnow.sketch.Solid.Shape.Plane pl) {
                      final double[] o = pl.offsetOrigin();
                      if (com.ovo.sablestopnow.client.SketchClient.choosePlaneAt(
                              solid.body(),
                              new org.joml.Vector3d(o[0], o[1], o[2]),
                              new org.joml.Vector3d(pl.nx(), pl.ny(), pl.nz()))) {
                          ModelMode.setTool(ModelTools.SKETCH);
                          ModelMode.selectModel(solid.body());
                          refreshLeftPanel();
                          return;
                      }
                  }
                  // ★ 点特征行**不再**进入编辑（右栏行点击不够可靠，用户要求只保留“编辑”子行）。
                  //   以前这里有一段“预填参数但不记编辑目标”的半成品 ⇒ 点“创建”会
                  //   **新建一个重复特征**，是“左栏逻辑混乱”的来源之一。
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
        // ★ 修实测"新拉伸在游戏里看得见、树里却没有"✗
        //   树的刷新是靠**内容指纹**去重的；强制刷新时若指纹没变就会跳过 setRoots ✗
        //   ⇒ 这里先清掉指纹，保证下一次一定重建 ✓
        lastTreeSignature = null;
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
                    // ★ 用户要求：树默认**全部折叠** ✓（要点开才展开）
                    root.expanded = false;
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
        // ★ 诊断（"树里没有实体行"✓）：把这次真正交给视图的根/一级子节点打出来
        final StringBuilder shape = new StringBuilder();
        for (final TreeView.TreeItem r : roots) {
            shape.append('[').append(r.children.size()).append(']').append(r.label.getString());
        }
        final String treeShape = shape.toString();
        if (!treeShape.equals(lastTreeShape)) {
            lastTreeShape = treeShape;
            com.ovo.sablestopnow.SablestopNow.LOGGER.info("[tree-shape] {}", treeShape);
        }
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
    // ===== item 7/8/10：左栏内联参数（待定值，按"创建"才落地）✓ =====
    private static final com.ovo.sablestopnow.client.gui.lib.Prop<Double> planeDistance =
            com.ovo.sablestopnow.client.gui.lib.Prop.of("feature.plane.distance", 0.5);
    private static final String CMD_PLANE_DISTANCE = "feature.plane.distance";
    private static final com.ovo.sablestopnow.client.gui.lib.Prop<Double> filletRadius =
            com.ovo.sablestopnow.client.gui.lib.Prop.of("feature.fillet.radius", 0.25);
    private static final String CMD_FILLET_RADIUS = "feature.fillet.radius";
    private static final com.ovo.sablestopnow.client.gui.lib.Prop<Double> patternN1 =
            com.ovo.sablestopnow.client.gui.lib.Prop.of("feature.pattern.n1", 2.0);
    private static final String CMD_PATTERN_N1 = "feature.pattern.n1";
    private static final com.ovo.sablestopnow.client.gui.lib.Prop<Double> patternS1 =
            com.ovo.sablestopnow.client.gui.lib.Prop.of("feature.pattern.s1", 1.0);
    private static final String CMD_PATTERN_S1 = "feature.pattern.s1";
    private static final com.ovo.sablestopnow.client.gui.lib.Prop<Double> patternN2 =
            com.ovo.sablestopnow.client.gui.lib.Prop.of("feature.pattern.n2", 1.0);
    private static final String CMD_PATTERN_N2 = "feature.pattern.n2";
    private static final com.ovo.sablestopnow.client.gui.lib.Prop<Double> patternS2 =
            com.ovo.sablestopnow.client.gui.lib.Prop.of("feature.pattern.s2", 0.0);
    private static final String CMD_PATTERN_S2 = "feature.pattern.s2";

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
    private static com.ovo.sablestopnow.sketch.Extrude.Mode extrudeMode =            com.ovo.sablestopnow.sketch.Extrude.Mode.ONE_SIDED;

    /**
     * **切割模式** ✓（需求 ⑥）：开启后"确定"建出来的是**切割体**而不是材料 ✓
     * （同一 body 内与它相交的实体被挖掉 ✓ 完全贯穿 = 深度拉大 ✓）
     */
    private static boolean cutMode;

    /**
     * **本会话内已创建过的实体序号** ✓（按 body 记）。
     *
     * <p>为什么需要 ✗✓：序号是"服务端镜像里的 max+1"算出来的 ✓
     * 但刚创建的实体要等**服务端广播回来**才进镜像 ⇒ 连着建两个时
     * 第二个又会算出同一个序号 ⇒ **重名/覆盖** ✓（实测"编号永远是 1、新实体不出现"✗）
     */
    private static final java.util.Map<java.util.UUID, Integer> LOCAL_SOLID_INDEX =
            new java.util.HashMap<>();

    /** 当前是在配"拉伸"还是"旋转"。 */
    private static boolean revolveMode;

    /** 旋转面板是否在等"点选草图线作轴"（item 1）✓ */
    public static boolean revolveAxisPicking() {
        return revolveMode
                && com.ovo.sablestopnow.client.model.ModelTools.EXTRUDE.equals(ModelMode.tool());
    }

    /**
     * 用草图里的**一条线**作为旋转轴 ✓（item 1：不用按键，直接点线）。
     *
     * <p>轴点 = 线段起点，方向 = 终点 − 起点 ✓；辅助线/参考线也接受（作轴最常见）✓
     */
    public static boolean setRevolveAxisFromLine(final com.ovo.sablestopnow.sketch.Sketch sketch,
                                                 final com.ovo.sablestopnow.sketch.Sketch.Entity line) {
        if (sketch == null || line == null
                || line.kind() != com.ovo.sablestopnow.sketch.Sketch.EntityKind.LINE) {
            return false;
        }
        final int[] v = line.vars();
        if (v.length < 4) {
            return false;
        }
        final double au = sketch.variable(v[0]);
        final double av = sketch.variable(v[1]);
        final double du = sketch.variable(v[2]) - au;
        final double dv = sketch.variable(v[3]) - av;
        if (Math.hypot(du, dv) < 1.0e-6) {
            return false;
        }
        revolveAxis = new double[] {au, av, du, dv};
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[revolve] axis from line a=({},{}) d=({},{}) cons={} ref={}",
                String.format(java.util.Locale.ROOT, "%.3f", au),
                String.format(java.util.Locale.ROOT, "%.3f", av),
                String.format(java.util.Locale.ROOT, "%.3f", du),
                String.format(java.util.Locale.ROOT, "%.3f", dv),
                sketch.isConstruction(line), sketch.isReference(line));
        refreshLeftPanel();
        return true;
    }
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

    /**
     * 正在**编辑已有特征**的那个特征 id（null = 新建）。
     *
     * <p>★ 为什么必须有：树上点了“编辑”之后，左栏预填的是这个特征的参数，主按钮必须
     * **原地更新同一个特征**（同 id / 序号 / 草图 / 外观）。没有这个标记时，
     * 面板里的“创建”会新建一个**重复特征**（用户实测“逻辑混乱”）。
     *
     * <p>必须进 {@link #leftPanelSignature()}：否则面板内容与“更新/创建”文案都不刷新。
     */
    @Nullable
    private static java.util.UUID editingSolidId;

    /** 贴图平铺比例：几个方块铺一次。 */
    /** item 11：连接纹理开关（面板内的待定值，应用时才写进实体）✓ */
    private static final com.ovo.sablestopnow.client.gui.lib.Prop<Boolean> textureConnected =
            com.ovo.sablestopnow.client.gui.lib.Prop.of("solid.texture.connected", false);

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
    /** 预览签名的上一份（内容没变就不重算 ✓ item 5） */
    private static String lastPreviewSig = "";

    /**
     * **item 5：模型操作的黄色线框预览** ✓
     *
     * <p>进入拉伸/切割/旋转操作后，每 tick 检查一次"当前参数"算出的线框；
     * 参数没变就直接返回（签名去重）；退出操作 / 没有源草图 / 已提交 ⇒ 清空预览 ✓
     * <p>⚠ 输入框回车 = 只更新**预览**（参数对象本来就只在"确认"时才提交 ✓）
     */
    public static void tickFeaturePreview() {
        // ★★ bug ② 的根因就在这里（读代码确认，不是猜）：
        //   `tickFeaturePreview` **每帧**都会执行；当工具不是"拉伸"时，它会
        //   `FeaturePreview.clear()` 然后 return ⇒ 圆角/镜像/阵列的预览
        //   只在设置它的那一帧可见，下一帧就被清掉 ⇒ "瞬间出现然后消失"✓
        //   （之前我加过"这四个工具每帧重建预览"的分支，但后来被后续整块替换冲掉了）
        final String toolNow = ModelMode.tool();
        if (ModelTools.PLANE.equals(toolNow) || ModelTools.FILLET.equals(toolNow)
                || ModelTools.MIRROR.equals(toolNow) || ModelTools.PATTERN.equals(toolNow)) {
            // 每帧**重建**：无论谁清掉都能立刻恢复 ✓
            if (ModelTools.FILLET.equals(toolNow)) {
                updateFilletPreview(filletRadius.get());
            } else if (ModelTools.PLANE.equals(toolNow)) {
                updatePlanePreview(planeDistance.get());
            } else if (ModelTools.MIRROR.equals(toolNow)) {
                updateMirrorPreview();
            } else {
                PatternTool.preview((int) (double) patternN1.get(), patternS1.get(),
                        (int) (double) patternN2.get(), patternS2.get());
            }
            return;
        }
        final boolean modeling = ModelTools.EXTRUDE.equals(ModelMode.tool());
        final com.ovo.sablestopnow.sketch.Sketch source = modeling ? currentExtrudeSource() : null;
        if (!modeling || source == null) {
            if (com.ovo.sablestopnow.client.model.FeaturePreview.active()) {
                com.ovo.sablestopnow.client.model.FeaturePreview.clear();
                lastPreviewSig = "";
                com.ovo.sablestopnow.SablestopNow.LOGGER.info("[preview] cleared (tool/source)");
            }
            return;
        }
        final double d1 = extrudeDepth.get();
        final double d2 = extrudeBackDepth.get();
        final String sig = source.id() + "|" + revolveMode + "|" + cutMode + "|" + extrudeMode
                + "|" + String.format(java.util.Locale.ROOT, "%.4f", d1) + "|"
                + String.format(java.util.Locale.ROOT, "%.4f", d2) + "|"
                + String.format(java.util.Locale.ROOT, "%.2f", revolveAngle.get()) + "|"
                + (revolveAxis == null ? "-" : java.util.Arrays.toString(revolveAxis));
        if (sig.equals(lastPreviewSig)) {
            return;
        }
        lastPreviewSig = sig;
        java.util.List<org.joml.Vector3d[]> edges;
        if (revolveMode) {
            if (revolveAxis == null) {
                com.ovo.sablestopnow.client.model.FeaturePreview.clear();
                return;
            }
            final com.ovo.sablestopnow.sketch.Revolve.Params rp =
                    new com.ovo.sablestopnow.sketch.Revolve.Params(
                            revolveAxis[0], revolveAxis[1], revolveAxis[2], revolveAxis[3],
                            revolveAngle.get(), revolveDirection);
            edges = com.ovo.sablestopnow.sketch.Revolve.edges(source, rp);
        } else {
            final com.ovo.sablestopnow.sketch.Extrude.Params ep =
                    buildExtrudeParams(d1, d2);
            edges = com.ovo.sablestopnow.sketch.Extrude.prismEdges(source, ep);
        }
        final java.util.List<com.ovo.sablestopnow.client.model.FeaturePreview.Seg> segs =
                new java.util.ArrayList<>(edges.size());
        for (final org.joml.Vector3d[] e : edges) {
            segs.add(new com.ovo.sablestopnow.client.model.FeaturePreview.Seg(e[0], e[1]));
        }
        com.ovo.sablestopnow.client.model.FeaturePreview.set(source.body(),
                source.plane() == null ? null : source.plane().normal(), segs);
        com.ovo.sablestopnow.SablestopNow.LOGGER.info("[preview] segs={} cut={} revolve={}",
                segs.size(), cutMode, revolveMode);
    }

    /**
     * 特征行动作：**↑ 上移 / ↓ 下移 / 删除** ✓（item 4 / 6.5）。
     *
     * <p>顺序调整 = 交换序号 + **依赖校验**；违反就拒绝并提示，树保持原样 ✓
     */
    /** 树行悬停 ⇒ 让渲染侧高亮对应特征 ✓（item 6） */
    public static void syncFeatureHover() {
        // hoveredKey 现在会在 RowWidget.update 里赋值 ⇒ 直接用它（行高/展开变化都自动正确）
        // ★ **按坐标直接算**（不依赖渲染帧顺序）：mouseXd/mouseYd 是 UI 设计空间坐标，
        //   行矩形由布局每帧写入 ⇒ 展开/收起/滚动都自动正确 ✓
        com.ovo.sablestopnow.client.gui.lib.Ui.refreshMouse();
        final String key = tree == null ? null
                : tree.hoverAt(com.ovo.sablestopnow.client.gui.lib.Ui.mouseXd(),
                        com.ovo.sablestopnow.client.gui.lib.Ui.mouseYd());
        // 悬停诊断：只在变化时打一行（用户要求"对 hover 做日志"）
        if (key == null ? lastHoverLogged != null
                : !key.equals(lastHoverLogged)) {
            lastHoverLogged = key;
            com.ovo.sablestopnow.SablestopNow.LOGGER.info("[hover] key={}", key);
        }
        if (key == null || !key.startsWith("solid:")) {
            com.ovo.sablestopnow.client.model.FeatureHighlight.clear();
            return;
        }
        final String[] parts = key.split(":");
        if (parts.length < 3) {
            com.ovo.sablestopnow.client.model.FeatureHighlight.clear();
            return;
        }
        try {
            com.ovo.sablestopnow.client.model.FeatureHighlight.set(java.util.UUID.fromString(parts[2]));
        } catch (final IllegalArgumentException e) {
            com.ovo.sablestopnow.client.model.FeatureHighlight.clear();
        }
    }

    /** 圆角工具：当前拾取到的**所有**棱段（直线棱 1 段；曲线棱 = 整条链，多段同 group）✓ */
    private static final java.util.List<FilletPick> filletPicks = new java.util.ArrayList<>();
    private static int filletPickSeq;
    private static java.util.UUID filletSourceSolid;

    /** 一次拾取的单位：{group, 段, 参考法线 n1, 参考法线 n2, 凸凹(1凸/0凹/-1未知)} ✓ */
    private record FilletPick(int group, double[] seg,
                              org.joml.Vector3d n1, org.joml.Vector3d n2, int convex) {
    }

    /** 圆角工具：点到一条棱 ⇒ 记录并弹半径输入框（带黄色预览）✓ */
    // ================= item 9: mirror =================

    private static final java.util.List<java.util.UUID> mirrorSources = new java.util.ArrayList<>();
    private static org.joml.Vector3d mirrorPlaneOrigin;
    private static org.joml.Vector3d mirrorPlaneNormal;
    private static java.util.UUID mirrorBody;
    /**
     * 选中作镜像面的**那张面的真实轮廓**（局部坐标，每段 6 个数）✓
     *
     * <p>为什么单独存：`Shape.Plane.corners()` 造的只是"以命中点为中心的方框"，
     * 用户实际想看到的是**自己点中的那张面**。真命中面时用
     * {@code hit.face().segments()}（真实边界）；只有方块面兜底（没有面数据）或
     * 选的是**基准面特征**时才退回方框/基准面方片。
     */
    @Nullable
    private static java.util.List<double[]> mirrorPlaneSegs;

    /** Mirror tool: first face sets the mirror plane; a second face click with sources creates it. */
    public static boolean mirrorPickPlane(
            final com.ovo.sablestopnow.client.model.FeatureHover.Hit hit) {
        // ★ 容错：判级可能是棱/点，但只要**面是平面**就接受
        if (hit == null || hit.face() == null || !hit.face().planar()) {
            final var mc0 = net.minecraft.client.Minecraft.getInstance();
            final BoxPick.Hit bh = BoxPick.nearest(mc0);
            if (bh == null || mc0.level == null) {
                ModelMode.prompt("model.mirror.pick_plane");
                return false;
            }
            final double[] loc = BoxPick.toLocal(mc0.level, bh.body(), bh.worldPoint(),
                    bh.worldNormal());
            if (loc == null) {
                ModelMode.prompt("model.mirror.pick_plane");
                return false;
            }
            if (mirrorPlaneOrigin != null && !mirrorSources.isEmpty()) {
                return createMirrorFeature();
            }
            mirrorPlaneOrigin = new org.joml.Vector3d(loc[0], loc[1], loc[2]);
            mirrorPlaneNormal = new org.joml.Vector3d(loc[3], loc[4], loc[5]).normalize();
            mirrorBody = bh.body();
            // 方块面兜底：没有面的真实轮廓 ⇒ 用方框（updateMirrorPreview 里生成）
            mirrorPlaneSegs = null;
            updateMirrorPreview();
            lastLeftSignature = null;
            refreshLeftPanel();
            com.ovo.sablestopnow.SablestopNow.LOGGER.info("[mirror] plane via BOX fallback body={}",
                    bh.body());
            return true;
        }
        if (mirrorPlaneOrigin != null && !mirrorSources.isEmpty()) {
            return createMirrorFeature();
        }
        mirrorPlaneOrigin = new org.joml.Vector3d(hit.localCenter());
        mirrorPlaneNormal = new org.joml.Vector3d(hit.localNormal()).normalize();
        mirrorBody = hit.body();
        // ★ 用**真实的面轮廓**做参考高亮（局部坐标，渲染侧再按 renderPose 变换）✓
        mirrorPlaneSegs = hit.face() == null ? null
                : new java.util.ArrayList<>(hit.face().segments());
        updateMirrorPreview();
        forceRefreshLeft();
        return true;
    }

    /** Mirror tool: clicking a feature row toggles it as a mirror source. */
    public static void mirrorToggleSource(final com.ovo.sablestopnow.sketch.Solid solid) {
        if (solid == null || solid.isPlane() || solid.isFillet() || solid.isMirror()) {
            ModelMode.prompt("model.mirror.no_sources");
            return;
        }
        if (!mirrorSources.remove(solid.id())) {
            mirrorSources.add(solid.id());
        }
        mirrorBody = solid.body();
        updateMirrorPreview();
        forceRefreshLeft();
    }

    /** Preview: mirrored wireframe of the selected sources, in preview yellow. */
    private static void updateMirrorPreview() {
        // ★★ bug 3 修复：参考面（青色）与"镜像副本（黄色）"**分开两条路**。
        //   以前 sources 为空就把整个预览清空 ⇒ 用户按"先选面、再选源"的顺序操作时，
        //   选完镜像面**每帧都被清掉**，看起来就是"根本没有高亮选中的参考面"。
        if (mirrorPlaneOrigin == null || mirrorPlaneNormal == null) {
            com.ovo.sablestopnow.client.model.FeaturePreview.clear();
            return;
        }
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.world.level.Level level = mc == null ? null : mc.level;
        if (level == null) {
            return;
        }
        final double[] o = {mirrorPlaneOrigin.x, mirrorPlaneOrigin.y, mirrorPlaneOrigin.z};
        final double[] n = {mirrorPlaneNormal.x, mirrorPlaneNormal.y, mirrorPlaneNormal.z};
        // ① 参考几何：优先画**真实的面轮廓**；没有（方块面兜底）就画方框 ✓
        final java.util.List<com.ovo.sablestopnow.client.model.FeaturePreview.Seg> refs =
                new java.util.ArrayList<>();
        if (mirrorPlaneSegs != null && !mirrorPlaneSegs.isEmpty()) {
            for (final double[] s : mirrorPlaneSegs) {
                refs.add(new com.ovo.sablestopnow.client.model.FeaturePreview.Seg(
                        new org.joml.Vector3d(s[0], s[1], s[2]),
                        new org.joml.Vector3d(s[3], s[4], s[5])));
            }
        } else {
            final com.ovo.sablestopnow.sketch.Solid.Shape.Plane tmp =
                    new com.ovo.sablestopnow.sketch.Solid.Shape.Plane(
                            mirrorPlaneOrigin.x, mirrorPlaneOrigin.y, mirrorPlaneOrigin.z,
                            mirrorPlaneNormal.x, mirrorPlaneNormal.y, mirrorPlaneNormal.z,
                            0.0, null, -1);
            final java.util.List<org.joml.Vector3d> cs = tmp.corners(1.2);
            for (int i = 0; i < 4; i++) {
                refs.add(new com.ovo.sablestopnow.client.model.FeaturePreview.Seg(
                        cs.get(i), cs.get((i + 1) % 4)));
            }
        }
        // ② 产物预览：镜像副本（源为空时为空表，但**不影响**上面的参考几何）✓
        final java.util.List<com.ovo.sablestopnow.client.model.FeaturePreview.Seg> segs =
                new java.util.ArrayList<>();
        if (!mirrorSources.isEmpty()) {
            final var sketches = com.ovo.sablestopnow.sketch.SketchClientState.current(level);
            for (final com.ovo.sablestopnow.sketch.Solid src
                    : com.ovo.sablestopnow.sketch.SolidClientState.ofBody(level, mirrorBody)) {
                if (!mirrorSources.contains(src.id())) {
                    continue;
                }
                final com.ovo.sablestopnow.sketch.Sketch sk = src.resolve(sketches);
                if (sk == null) {
                    continue;
                }
                for (final org.joml.Vector3d[] e : src.edges(sk)) {
                    segs.add(new com.ovo.sablestopnow.client.model.FeaturePreview.Seg(
                            com.ovo.sablestopnow.sketch.Solid.reflectPoint(e[0], o, n),
                            com.ovo.sablestopnow.sketch.Solid.reflectPoint(e[1], o, n)));
                }
            }
        }
        // 顺序固定：set() 会清掉上一轮参考几何，所以最后再 setRefs ✓
        com.ovo.sablestopnow.client.model.FeaturePreview.set(mirrorBody, mirrorPlaneNormal, segs);
        com.ovo.sablestopnow.client.model.FeaturePreview.setRefs(mirrorBody, refs);
    }

    /** Creates the mirror feature. */
    /** 特征在左栏列表里的短标签 ✓（例如"圆角3/拉伸1"） */
    private static String solidShortLabel(final com.ovo.sablestopnow.sketch.Solid sd) {
        // ★ 用**永久名字**（创建时分配，移动不改变）✓ 排序由树里的行位置体现 ✓
        return com.ovo.sablestopnow.client.model.FeatureNames.of(sd, true);
    }

    private static String solidShortLabelOld(final com.ovo.sablestopnow.sketch.Solid sd) {
        final String kind;
        if (sd.isPattern()) {
            kind = "model.pattern.item";
        } else if (sd.isMirror()) {
            kind = "model.mirror.item";
        } else if (sd.isFillet()) {
            kind = "model.fillet.item";
        } else if (sd.shape() instanceof com.ovo.sablestopnow.sketch.Solid.Shape.Cut) {
            kind = "model.cut.item";
        } else {
            kind = "model.extrude.item";
        }
        return Component.translatable(kind, sd.index()).getString();
    }

    private static boolean createMirrorFeature() {
        syncMirrorSourcesFromProps();
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.world.level.Level level = mc == null ? null : mc.level;
        if (level == null || mirrorPlaneOrigin == null || mirrorSources.isEmpty()) {
            ModelMode.prompt("model.mirror.no_sources");
            return false;
        }
        int index = 1;
        for (final com.ovo.sablestopnow.sketch.Solid s
                : com.ovo.sablestopnow.sketch.SolidClientState.ofBody(level, mirrorBody)) {
            index = Math.max(index, s.index() + 1);
        }
        index = Math.max(index, LOCAL_SOLID_INDEX.getOrDefault(mirrorBody, 0) + 1);
        // ★ 编辑已有镜像 ⇒ **原地更新**（平面/源换掉，id/序号/草图/外观不变）✓
        if (commitShape(new com.ovo.sablestopnow.sketch.Solid.Shape.Mirror(
                mirrorPlaneOrigin.x, mirrorPlaneOrigin.y, mirrorPlaneOrigin.z,
                mirrorPlaneNormal.x, mirrorPlaneNormal.y, mirrorPlaneNormal.z,
                new java.util.ArrayList<>(mirrorSources)))) {
            com.ovo.sablestopnow.client.model.FeaturePreview.clear();
            mirrorSources.clear();
            mirrorPlaneOrigin = null;
            mirrorPlaneSegs = null;
            mirrorPlaneNormal = null;
            return true;
        }
        final java.util.UUID mirrorSketchId = sketchFor(
                mirrorSources.isEmpty() ? null : mirrorSources.get(0), mirrorBody);
        if (mirrorSketchId == null) {
            ModelMode.prompt("model.order.missing_sketch");
            return false;
        }
        final com.ovo.sablestopnow.sketch.Solid mirror =
                com.ovo.sablestopnow.sketch.Solid.createMirror(mirrorBody,
                        mirrorSketchId, index,
                        mirrorPlaneOrigin.x, mirrorPlaneOrigin.y, mirrorPlaneOrigin.z,
                        mirrorPlaneNormal.x, mirrorPlaneNormal.y, mirrorPlaneNormal.z,
                        new java.util.ArrayList<>(mirrorSources));
        com.ovo.sablestopnow.network.SolidNetworking.sendSave(mirror);
        LOCAL_SOLID_INDEX.put(mirrorBody, mirror.index());
        com.ovo.sablestopnow.client.model.FeaturePreview.clear();
        mirrorSources.clear();
        mirrorPlaneOrigin = null;
        mirrorPlaneSegs = null;
        mirrorPlaneNormal = null;
        com.ovo.sablestopnow.SablestopNow.LOGGER.info("[mirror] created idx={}", mirror.index());
        lastTreeSignature = null;
        refreshTreeNow();
        ModelMode.setTool(ModelTools.NONE);
        return true;
    }

    /**
     * 拾取一批棱到"已选"里 ✓（曲线棱会把整条弧的碎段一起加进来）
     *
     * <p>一个圆角特征只作用于**一个构件**：若这次点中的棱属于另一个体，
     * 先把之前的选中作废，避免把两个体的棱写进同一个特征 ✓
     */
    private static boolean filletSwitchBody(final java.util.UUID body) {
        if (filletSourceSolid != null && body != null && !filletSourceSolid.equals(body)) {
            filletPicks.clear();
            filletPickSeq = 0;
            filletSolidId = null;
            com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                    "[fillet] body changed {} -> {} ⇒ 清空已选",
                    shortId8(filletSourceSolid), shortId8(body));
        }
        return true;
    }

    public static boolean filletPickEdge(
            final com.ovo.sablestopnow.client.model.FeatureHover.Hit hit) {
        if (hit == null) {
            // ★ 兜底：盒子拾取 + 最近盒棱（保证"指哪条选哪条"不会完全失效）
            final var mc0 = net.minecraft.client.Minecraft.getInstance();
            final BoxPick.Hit bh = BoxPick.nearest(mc0);
            if (bh == null || mc0.level == null) {
                ModelMode.prompt("model.fillet.pick_edge");
                return false;
            }
            final double[] loc = BoxPick.toLocal(mc0.level, bh.body(), bh.worldPoint(),
                    bh.worldNormal());
            if (loc == null) {
                ModelMode.prompt("model.fillet.pick_edge");
                return false;
            }
            final double[] seg = BoxPick.nearestEdgeLocal(bh.localBox(),
                    new org.joml.Vector3d(loc[0], loc[1], loc[2]));
            if (seg == null) {
                ModelMode.prompt("model.fillet.pick_edge");
                return false;
            }
            // ★ 坐标系诊断（用户怀疑 Sable 的坐标换算）：拾取到的点是否**真的落在**该体的
          //   碰撞盒里？如果不在，说明 hit 给的是世界坐标而 placeAll 用的是 plot 局部坐标
          //   ⇒ 楔块会被造到 2e7 那么远、与材料零交集 ⇒ "创建后毫无变化"。
          {
              boolean inside = false;
              final net.minecraft.client.Minecraft mcS = net.minecraft.client.Minecraft.getInstance();
              final net.minecraft.world.level.Level lvS = mcS == null ? null : mcS.level;
              if (lvS != null && hit.body() != null) {
                  final org.joml.Vector3d pm = new org.joml.Vector3d(
                          (seg[0] + seg[3]) * 0.5, (seg[1] + seg[4]) * 0.5,
                          (seg[2] + seg[5]) * 0.5);
                  for (final com.ovo.sablestopnow.sketch.Solid.Placed pl
                          : com.ovo.sablestopnow.sketch.SolidClientState.placed(lvS)) {
                      if (!pl.solid().body().equals(hit.body())) {
                          continue;
                      }
                      for (final double[] b : pl.boxes()) {
                          if (pm.x > b[0] - 0.05 && pm.x < b[3] + 0.05
                                  && pm.y > b[1] - 0.05 && pm.y < b[4] + 0.05
                                  && pm.z > b[2] - 0.05 && pm.z < b[5] + 0.05) {
                              inside = true;
                              break;
                          }
                      }
                      if (inside) {
                          break;
                      }
                  }
              }
              com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                      "[fillet] spacecheck mid=({},{},{}) insideBodyBoxes={} body={}",
                      String.format(java.util.Locale.ROOT, "%.2f", (seg[0] + seg[3]) * 0.5),
                      String.format(java.util.Locale.ROOT, "%.2f", (seg[1] + seg[4]) * 0.5),
                      String.format(java.util.Locale.ROOT, "%.2f", (seg[2] + seg[5]) * 0.5),
                      inside, hit.body() == null ? "?" : hit.body().toString().substring(0, 8));
          }
            filletSwitchBody(bh.body());
            filletSourceSolid = bh.body();
            filletSolidId = null;
            // 盒棱沿某个世界轴 ⇒ **另外两个轴之一**当参考面法线；
            // 到底切还是补由求值侧的四象限探针实测，这里不需要把符号猜对 ✓
            final org.joml.Vector3d d = new org.joml.Vector3d(
                    seg[3] - seg[0], seg[4] - seg[1], seg[5] - seg[2]);
            final org.joml.Vector3d fn = new org.joml.Vector3d(0, 1, 0);
            if (d.lengthSquared() > 1.0e-12) {
                d.normalize();
                final double ax = Math.abs(d.x);
                final double ay = Math.abs(d.y);
                final double az = Math.abs(d.z);
                final int edgeAxis = ax >= ay && ax >= az ? 0 : (ay >= az ? 1 : 2);
                fn.zero();
                fn.setComponent((edgeAxis + 1) % 3, 1.0);
            }
            filletTogglePicks(java.util.List.of(seg), fn, null, -1, filletRadius.get());
            updateFilletPreview(filletRadius.get());
            lastLeftSignature = null;
            refreshLeftPanel();
            com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                    "[fillet] edge via BOX fallback body={} picks={} groups={}",
                    bh.body(), filletPicks.size(), filletGroupCount());
            return true;
        }
        // ★ 容错：拾取可能把"棱"判成"面"（容差随距离变化）。这种情况下退一步：
        //   取这个面里**离命中点最近的那条棱** ✓（用户只要"指哪条棱倒哪条"，不关心判级细节）
        int idx = hit.index();
        if (hit.kind() != com.ovo.sablestopnow.client.model.FeatureHover.Kind.EDGE) {
            if (hit.face() == null || hit.face().segments().isEmpty()) {
                ModelMode.prompt("model.fillet.pick_edge");
                return false;
            }
            // 参考点：**射线与面的实际交点**（以前用面原点 ⇒ 会选到很远的棱）
            final org.joml.Vector3d p = new org.joml.Vector3d(hit.localHit());
            double best = Double.MAX_VALUE;
            int bestIdx = -1;
            final java.util.List<double[]> segs = hit.face().segments();
            for (int i = 0; i < segs.size(); i++) {
                final double[] sg = segs.get(i);
                final org.joml.Vector3d a = new org.joml.Vector3d(sg[0], sg[1], sg[2]);
                final org.joml.Vector3d b = new org.joml.Vector3d(sg[3], sg[4], sg[5]);
                final org.joml.Vector3d ab = new org.joml.Vector3d(b).sub(a);
                final double len2 = ab.lengthSquared();
                double t = len2 < 1.0e-12 ? 0.0 : new org.joml.Vector3d(p).sub(a).dot(ab) / len2;
                t = Math.max(0.0, Math.min(1.0, t));
                final double d = new org.joml.Vector3d(a).fma(t, ab).distance(p);
                if (d < best) {
                    best = d;
                    bestIdx = i;
                }
            }
            if (best > 0.35) {
                ModelMode.prompt("model.fillet.pick_edge");
                com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                        "[fillet] reject: nearest edge dist={} (too far)", best);
                return false;
            }
            idx = bestIdx;
            com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                    "[fillet] hover was {} -> nearest edge idx={} dist={}", hit.kind(), idx, best);
        }
        if (idx < 0 || idx >= hit.face().segments().size()) {
            ModelMode.prompt("model.fillet.pick_edge");
            return false;
        }
        final java.util.List<double[]> allSegs = hit.face().segments();
        // ★ 曲线棱：点中弧上任意一段 ⇒ 把**同一条弧**的碎段按端点首尾相接合起来 ✓
        //   判据与"只保留直线棱"（ReferenceOutline）完全同源 ⇒ 直线棱只会取到自己那一段。
        final boolean[] curve = com.ovo.sablestopnow.sketch.ReferenceOutline.curveFlags(allSegs);
        final java.util.List<double[]> picked = idx < curve.length && curve[idx]
                ? com.ovo.sablestopnow.sketch.EdgeChains.chainAt(allSegs, curve, idx)
                : java.util.List.of(copySeg(allSegs.get(idx)));
        filletSwitchBody(hit.body());
        filletSourceSolid = hit.body();
        filletSolidId = hit.solidId();
        final boolean isCurve = idx < curve.length && curve[idx];
        // 参考面法线：直线棱用**网格里的两个真实面法线**（支持非 90° 棱）；
        // 曲线链用**链所在平面的法线**（盖面），每段的第二法线 = dir × 盖面 ✓
        org.joml.Vector3d faceN1 = null;
        org.joml.Vector3d faceN2 = null;
        int convexKnown = -1;
        if (!isCurve) {
            final net.minecraft.client.Minecraft mc2 =
                    net.minecraft.client.Minecraft.getInstance();
            final net.minecraft.world.level.Level lv2 = mc2 == null ? null : mc2.level;
            final com.ovo.sablestopnow.sketch.Solid srcS = lv2 == null ? null
                    : com.ovo.sablestopnow.sketch.SolidClientState.byId(lv2, filletSolidId);
            if (srcS != null) {
                final com.ovo.sablestopnow.sketch.Sketch sk2 = srcS.resolve(
                        com.ovo.sablestopnow.sketch.SketchClientState.current(lv2));
                if (sk2 != null) {
                    final com.ovo.sablestopnow.sketch.SolidMesh.Mesh m2 = srcS.mesh(sk2);
                    final double[] seg0 = picked.get(0);
                    final org.joml.Vector3d sa0 =
                            new org.joml.Vector3d(seg0[0], seg0[1], seg0[2]);
                    final org.joml.Vector3d sb0 =
                            new org.joml.Vector3d(seg0[3], seg0[4], seg0[5]);
                    // ★ 网格直接给"两个相邻面 + 凸凹" ⇒ 楔块方向可以解析算出，不靠探针 ✓
                    final double[] info =
                            com.ovo.sablestopnow.sketch.FilletCutter.edgeInfo(m2, sa0, sb0);
                    if (info != null) {
                        faceN1 = new org.joml.Vector3d(info[0], info[1], info[2]);
                        faceN2 = new org.joml.Vector3d(info[3], info[4], info[5]);
                        convexKnown = info[6] > 0.5 ? 1 : 0;
                    } else {
                        final org.joml.Vector3d[] fn =
                                com.ovo.sablestopnow.sketch.FilletCutter.faceNormals(m2, sa0, sb0);
                        if (fn != null) {
                            faceN1 = fn[0];
                            faceN2 = fn[1];
                        }
                    }
                }
            }
        }
        final org.joml.Vector3d basisNormal = isCurve
                ? com.ovo.sablestopnow.sketch.EdgeChains.planeNormal(picked,
                        new org.joml.Vector3d(hit.localNormal()))
                : (faceN1 != null ? faceN1 : new org.joml.Vector3d(hit.localNormal()));
        filletTogglePicks(picked, basisNormal, isCurve ? null : faceN2, convexKnown,
                filletRadius.get());
        updateFilletPreview(filletRadius.get());
        lastLeftSignature = null;
        refreshLeftPanel();
        final org.joml.Vector3d showN2 = filletPicks.isEmpty() ? basisNormal
                : filletPicks.get(filletPicks.size() - 1).n2();
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[fillet] edge picked body={} curve={} segs={} picks={} groups={}"
                        + " basis={} convex={} n1={} n2={} phiDeg={}",
                filletSourceSolid, isCurve, allSegs.size(), picked.size(),
                filletPicks.size(), filletGroupCount(),
                isCurve ? "plane" : (convexKnown >= 0 ? "mesh-edgeInfo"
                        : (faceN1 != null ? "mesh-faces" : "hit-normal")),
                convexKnown, basisNormal, showN2, String.format(java.util.Locale.ROOT, "%.1f",
                        Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0,
                                basisNormal.dot(showN2 == null ? basisNormal : showN2)))))));
        return true;
    }

    /** 复制一段（统一成 {x1,y1,z1,x2,y2,z2}）✓ */
    private static double[] copySeg(final double[] s) {
        return new double[] {s[0], s[1], s[2], s[3], s[4], s[5]};
    }

    /** 两段是否**同一段几何**（端点顺序无关，按 1e-3 量化）✓ */
    private static boolean sameSeg(final double[] a, final double[] b) {
        return (near3(a, 0, b, 0) && near3(a, 3, b, 3))
                || (near3(a, 0, b, 3) && near3(a, 3, b, 0));
    }

    private static boolean near3(final double[] a, final int ia, final double[] b, final int ib) {
        return Math.abs(a[ia] - b[ib]) < 1.0e-3
                && Math.abs(a[ia + 1] - b[ib + 1]) < 1.0e-3
                && Math.abs(a[ia + 2] - b[ib + 2]) < 1.0e-3;
    }

    /** 两段在指定端点是否重合 ✓（段端点：0 = 起点，1 = 终点） */
    private static boolean segPointEq(final double[] a, final int endA,
                                      final double[] b, final int endB) {
        return near3(a, endA == 1 ? 3 : 0, b, endB == 1 ? 3 : 0);
    }

    private static boolean filletHasPick(final double[] seg) {
        for (final FilletPick p : filletPicks) {
            if (sameSeg(p.seg(), seg)) {
                return true;
            }
        }
        return false;
    }

    private static void filletRemovePick(final double[] seg) {
        filletPicks.removeIf(p -> sameSeg(p.seg(), seg));
    }

    /** 已选中的"拾取组"个数（直线棱每组 1 段，曲线棱每组 = 整条链）✓ */
    private static int filletGroupCount() {
        final java.util.HashSet<Integer> g = new java.util.HashSet<>();
        for (final FilletPick p : filletPicks) {
            g.add(p.group());
        }
        return g.size();
    }

    /**
     * **加/减选**一批棱段 ✓（同一个 group 表示"一次点选"，曲线链整体取消）
     *
     * <p>再次点中已选的一批 ⇒ 全部取消；否则把重复的去掉、整批作为一个新组加进去 ✓
     */
    private static void filletTogglePicks(final java.util.List<double[]> segs,
                                          final org.joml.Vector3d basisNormal,
                                          final org.joml.Vector3d secondNormal,
                                          final int convex, final double radius) {
        if (segs == null || segs.isEmpty()) {
            return;
        }
        boolean allPicked = true;
        for (final double[] s : segs) {
            if (!filletHasPick(s)) {
                allPicked = false;
                break;
            }
        }
        if (allPicked) {
            for (final double[] s : segs) {
                filletRemovePick(s);
            }
            return;
        }
        final int group = ++filletPickSeq;
        for (final double[] s : segs) {
            filletRemovePick(s);
            final org.joml.Vector3d[] basis = filletBasis(s, basisNormal, secondNormal);
            filletPicks.add(new FilletPick(group, copySeg(s), basis[0], basis[1], convex));
        }
    }

    /**
     * 一段棱的**正交参考基**（都 ⟂ 棱）✓
     * 一段棱的参考基（都 ⟂ 棱）✓
     *
     * <p>n1 = 第一个真实面法线投影；n2 = **第二个真实面法线**投影。
     * ★ 以前 n2 硬用 {@code dir × n1}：那只有在**两个面互相垂直**时才等于第二个面法线，
     * 非 90° 棱（斜面/倒角）会把楔块**转一个角度**（实测"切割体被向外旋转 45°"）⇒
     * 现在优先用从网格里找到的真实面法线，只有拿不到时才退回 dir × n1。
     */
    private static org.joml.Vector3d[] filletBasis(final double[] seg,
                                                   final org.joml.Vector3d basisNormal,
                                                   final org.joml.Vector3d secondNormal) {
        final org.joml.Vector3d d = new org.joml.Vector3d(
                seg[3] - seg[0], seg[4] - seg[1], seg[5] - seg[2]);
        if (d.lengthSquared() < 1.0e-12) {
            return new org.joml.Vector3d[] {new org.joml.Vector3d(0, 1, 0),
                new org.joml.Vector3d(1, 0, 0)};
        }
        d.normalize();
        final org.joml.Vector3d n1 = basisNormal == null
                ? new org.joml.Vector3d(0, 1, 0) : new org.joml.Vector3d(basisNormal);
        n1.fma(-n1.dot(d), d);
        if (n1.lengthSquared() < 1.0e-9) {
            n1.set(0, 1, 0);
            if (Math.abs(d.y) > 0.9) {
                n1.set(1, 0, 0);
            }
            n1.fma(-n1.dot(d), d);
        }
        n1.normalize();
        org.joml.Vector3d n2 = null;
        if (secondNormal != null && secondNormal.lengthSquared() > 1.0e-12) {
            n2 = new org.joml.Vector3d(secondNormal);
            n2.fma(-n2.dot(d), d);
            // ★ 只投影到 ⟂ 棱的平面里，**不做**对 n1 的正交化：
            //   非 90° 棱的两个面法线本来就不垂直，正交化会改掉夹角 ⇒ 楔块又转回去 ✗
            if (n2.lengthSquared() < 1.0e-9) {
                n2 = null;
            } else {
                n2.normalize();
            }
        }
        if (n2 == null) {
            n2 = new org.joml.Vector3d(d).cross(n1).normalize();
        }
        return new org.joml.Vector3d[] {n1, n2};
    }

    /** 预览：把所有已选棱段的**实际楔块体线框**画出来（与求值同一套象限判定）✓ */
    private static void updateFilletPreview(final double radius) {
        if (filletSourceSolid == null || filletPicks.isEmpty()) {
            com.ovo.sablestopnow.client.model.FeaturePreview.clear();
            lastFilletPreviewSig = "";
            return;
        }
        // ★ 缓存：选中几十段曲线棱时，每帧重建楔块线框会明显掉帧 ⇒ 只在"半径/选中"变了才重建
        final StringBuilder sigBuilder = new StringBuilder(64);
        sigBuilder.append(radius).append('|').append(filletPicks.size()).append('|');
        for (final FilletPick pick : filletPicks) {
            sigBuilder.append(Math.round(pick.seg()[0] * 1000)).append(',')
                    .append(Math.round(pick.seg()[1] * 1000)).append(',')
                    .append(Math.round(pick.seg()[2] * 1000)).append(';');
        }
        final String sig = sigBuilder.toString();
        if (sig.equals(lastFilletPreviewSig)) {
            return;
        }
        lastFilletPreviewSig = sig;
        final java.util.List<org.joml.Vector3d[]> segsRaw = new java.util.ArrayList<>();
        final java.util.Set<String> seenEdge = new java.util.HashSet<>();
        int cut = 0;
        int fill = 0;
        int skipped = 0;
        // ★ 按"一次点选"分组：直线棱 = 1 段；曲线棱 = 整条弧 ⇒ **一个扫掠体**
        //   逐段画棱柱会在弧的关节处留空隙（用户实测"每个小圆角之间有间隙"）
        final java.util.List<java.util.List<FilletPick>> gl = new java.util.ArrayList<>();
        {
            final java.util.LinkedHashMap<Integer, java.util.List<FilletPick>> groups =
                    new java.util.LinkedHashMap<>();
            for (final FilletPick pick : filletPicks) {
                groups.computeIfAbsent(pick.group(), k -> new java.util.ArrayList<>()).add(pick);
            }
            gl.addAll(groups.values());
        }
        final int gn = gl.size();
        final int[] gq = new int[gn];
        final double[][] gqd = new double[gn][];
        // ① 逐组分类（中间段探针）
        for (int i = 0; i < gn; i++) {
            final java.util.List<FilletPick> g = gl.get(i);
            final FilletPick mid = g.get((g.size() - 1) / 2);
            final org.joml.Vector3d mn1 = mid.n1() == null
                    ? new org.joml.Vector3d(0, 1, 0) : mid.n1();
            final org.joml.Vector3d mn2 = mid.n2() == null
                    ? new org.joml.Vector3d(1, 0, 0) : mid.n2();
            final double[] qd = new double[4];
            gqd[i] = qd;
            if (mid.convex() >= 0) {
                // ★ 网格已给出精确凸凹（直线棱）⇒ 与求值侧同一解析解 ✓
                final int cv = mid.convex();
                qd[0] = cv == 1 ? -1.0 : 1.0;
                qd[1] = qd[0];
                qd[2] = cv == 1 ? 0.0 : 1.0;
                qd[3] = 0.0;
                gq[i] = cv == 1 ? 1 : 3;
            } else {
                gq[i] = com.ovo.sablestopnow.sketch.Solid.filletQuadrant(
                        pt -> insideAnyBox(pt),
                        new org.joml.Vector3d(mid.seg()[0], mid.seg()[1], mid.seg()[2]),
                        new org.joml.Vector3d(mid.seg()[3], mid.seg()[4], mid.seg()[5]),
                        mn1, mn2, radius, qd);
            }
            // ★ 与求值一致：探针路径下首段结论必须与中间段一致，否则视为不可判定 ✓
            if (mid.convex() < 0 && (gq[i] == 1 || gq[i] == 3)) {
                final FilletPick f0 = g.get(0);
                final double[] qdF = new double[4];
                final int qF = com.ovo.sablestopnow.sketch.Solid.filletQuadrant(
                        pt -> insideAnyBox(pt),
                        new org.joml.Vector3d(f0.seg()[0], f0.seg()[1], f0.seg()[2]),
                        new org.joml.Vector3d(f0.seg()[3], f0.seg()[4], f0.seg()[5]),
                        f0.n1() == null ? mn1 : f0.n1(),
                        f0.n2() == null ? mn2 : f0.n2(), radius, qdF);
                if ((qF == 1 || qF == 3) && qF != gq[i]) {
                    gq[i] = 0; // 不可判定
                }
            }
            if (System.currentTimeMillis() - lastFilletPreviewLog > 2000L) {
                com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                        "[fillet] group segs={} quads={}/4 pattern={} a={}",
                        g.size(), gq[i], (int) qd[3], g.get(0).seg());
            }
        }
        // ② 凸/凹过渡：与**相反分类**的组共顶点 ⇒ 该端半径收小（与求值一致）✓
        final boolean[] tStart = new boolean[gn];
        final boolean[] tEnd = new boolean[gn];
        for (int i = 0; i < gn; i++) {
            for (int j = 0; j < gn; j++) {
                if (i == j || (gq[i] != 1 && gq[i] != 3)
                        || (gq[j] != 1 && gq[j] != 3)
                        || ((gq[j] == 3) == (gq[i] == 3))) {
                    continue;
                }
                final double[] aS = gl.get(i).get(0).seg();
                final double[] aE = gl.get(i).get(gl.get(i).size() - 1).seg();
                final double[] bS = gl.get(j).get(0).seg();
                final double[] bE = gl.get(j).get(gl.get(j).size() - 1).seg();
                if (segPointEq(aS, 0, bS, 0) || segPointEq(aS, 0, bE, 3)) {
                    tStart[i] = true;
                }
                if (segPointEq(aE, 3, bS, 0) || segPointEq(aE, 3, bE, 3)) {
                    tEnd[i] = true;
                }
            }
        }
        // ③ 造线框
        for (int i = 0; i < gn; i++) {
            final java.util.List<FilletPick> g = gl.get(i);
            final int quads = gq[i];
            if (quads != 1 && quads != 3) {
                skipped++;
                continue;
            }
            if (quads == 3) {
                fill++;
            } else {
                cut++;
            }
            final FilletPick first = g.get(0);
            final org.joml.Vector3d n1 = first.n1() == null
                    ? new org.joml.Vector3d(0, 1, 0) : first.n1();
            final org.joml.Vector3d n2 = first.n2() == null
                    ? new org.joml.Vector3d(1, 0, 0) : first.n2();
            final double[] qd = gqd[i];
            // ★ 凸/凹共顶点：**不让位**（与求值侧完全一致）——两条圆角都跑到公共顶点，
            //   端盖各自与对面的那张面共面 ⇒ 平顺跑出，中间不留空 ✓
            final boolean cc = tStart[i] || tEnd[i];
            java.util.List<org.joml.Vector3d> pts =
                    new java.util.ArrayList<>(g.size() + 1);
            pts.add(new org.joml.Vector3d(
                    first.seg()[0], first.seg()[1], first.seg()[2]));
            for (final FilletPick pick : g) {
                pts.add(new org.joml.Vector3d(
                        pick.seg()[3], pick.seg()[4], pick.seg()[5]));
            }
            final java.util.List<org.joml.Vector3d[]> tris;
            if (g.size() > 1 || cc) {
                // ★ 与求值侧**同一套** q2 归一：q2 是相对存档里的 n2，而扫掠的第二轴是
                //   (tan×cap) ⇒ 两者反向时取负，否则预览的楔块会落到另一个象限 ✓
                final int q1i = (int) Math.round(qd[0]);
                final int q2i = com.ovo.sablestopnow.sketch.FilletCutter.sweepQ2(
                        n1, n2, new org.joml.Vector3d(
                                first.seg()[3] - first.seg()[0],
                                first.seg()[4] - first.seg()[1],
                                first.seg()[5] - first.seg()[2]),
                        (int) Math.round(qd[1]));
                // ★ runout 与求值侧同一套：cc 端半径收到 ~8%，并且在离顶点 tl 处
                //   **插分段点**（否则直线棱整条都成锥形 ⇒ "整个圆角都是斜的"）✓
                final com.ovo.sablestopnow.sketch.FilletCutter.Runout runout =
                        com.ovo.sablestopnow.sketch.FilletCutter.runoutProfile(
                                pts, radius, tStart[i], tEnd[i]);
                tris = com.ovo.sablestopnow.sketch.FilletCutter.sweep(
                        runout.points, n1, runout.radii, q1i, q2i);
            } else {
                final org.joml.Vector3d m1 = new org.joml.Vector3d(n1).mul(-qd[0]);
                final org.joml.Vector3d m2 = new org.joml.Vector3d(n2).mul(-qd[1]);
                tris = com.ovo.sablestopnow.sketch.FilletCutter.prism(
                        new org.joml.Vector3d(
                                first.seg()[0], first.seg()[1], first.seg()[2]),
                        new org.joml.Vector3d(
                                first.seg()[3], first.seg()[4], first.seg()[5]),
                        m1, m2, radius);
            }
            if (tris == null) {
                continue;
            }
            for (final org.joml.Vector3d[] t : tris) {
                for (int k = 0; k < 3; k++) {
                    final org.joml.Vector3d p = t[k];
                    final org.joml.Vector3d q = t[(k + 1) % 3];
                    final String ka = Math.round(p.x * 1e4) + "," + Math.round(p.y * 1e4)
                            + "," + Math.round(p.z * 1e4);
                    final String kb = Math.round(q.x * 1e4) + "," + Math.round(q.y * 1e4)
                            + "," + Math.round(q.z * 1e4);
                    final String key = ka.compareTo(kb) <= 0 ? ka + "|" + kb : kb + "|" + ka;
                    if (seenEdge.add(key)) {
                        segsRaw.add(new org.joml.Vector3d[] {
                            new org.joml.Vector3d(p), new org.joml.Vector3d(q)});
                    }
                }
            }
        }
        final java.util.List<com.ovo.sablestopnow.client.model.FeaturePreview.Seg> segs =
                new java.util.ArrayList<>(segsRaw.size() + filletPicks.size());
        // ★ 选中的棱本身也画出来（曲线棱 = 整条折线，所以能看清"一次点选了一整条弧"）
        for (final FilletPick pick : filletPicks) {
            segs.add(new com.ovo.sablestopnow.client.model.FeaturePreview.Seg(
                    new org.joml.Vector3d(pick.seg()[0], pick.seg()[1], pick.seg()[2]),
                    new org.joml.Vector3d(pick.seg()[3], pick.seg()[4], pick.seg()[5])));
        }
        for (final org.joml.Vector3d[] e : segsRaw) {
            segs.add(new com.ovo.sablestopnow.client.model.FeaturePreview.Seg(e[0], e[1]));
        }
        final FilletPick first = filletPicks.get(0);
        final org.joml.Vector3d fn1 = first.n1() == null
                ? new org.joml.Vector3d(0, 1, 0) : first.n1();
        final org.joml.Vector3d fn2 = first.n2() == null
                ? new org.joml.Vector3d(1, 0, 0) : first.n2();
        com.ovo.sablestopnow.client.model.FeaturePreview.set(filletSourceSolid,
                new org.joml.Vector3d(fn1).cross(fn2), segs);
        if (System.currentTimeMillis() - lastFilletPreviewLog > 2000L) {
            lastFilletPreviewLog = System.currentTimeMillis();
            com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                    "[fillet] preview picks={} groups={} cut={} fill={} skip={} segs={} r={}",
                    filletPicks.size(), filletGroupCount(), cut, fill, skipped,
                    segs.size(), radius);
        }
    }

    private static boolean insideAnyBox(final org.joml.Vector3dc p) {
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.world.level.Level level = mc == null ? null : mc.level;
        final java.util.UUID body = filletSourceSolid;
        if (level == null || body == null) {
            return false;
        }
        for (final com.ovo.sablestopnow.sketch.Solid.Placed pl
                : com.ovo.sablestopnow.sketch.SolidClientState.placed(level)) {
            if (!pl.solid().body().equals(body)) {
                continue;
            }
            for (final double[] b : pl.boxes()) {
                final double eps = 1.0e-4;
                if (p.x() > b[0] - eps && p.x() < b[3] + eps
                        && p.y() > b[1] - eps && p.y() < b[4] + eps
                        && p.z() > b[2] - eps && p.z() < b[5] + eps) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 真正创建圆角特征 ✓ */
    /** 日志用短 id ✓ */
    private static String shortId8(final java.util.UUID id) {
        return id == null ? "-" : id.toString().substring(0, 8);
    }

    private static boolean createFilletFeature(final double radius) {
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.world.level.Level level = mc == null ? null : mc.level;
        if (level == null || filletPicks.isEmpty() || filletSourceSolid == null) {
            return false;
        }
        // ★ 坐标系核对（创建必经之路，保证一定打印）
        {
            final FilletPick p0 = filletPicks.get(0);
            final org.joml.Vector3d midS = new org.joml.Vector3d(
                    (p0.seg()[0] + p0.seg()[3]) * 0.5,
                    (p0.seg()[1] + p0.seg()[4]) * 0.5,
                    (p0.seg()[2] + p0.seg()[5]) * 0.5);
            boolean insideS = false;
            double bestS = Double.MAX_VALUE;
            for (final com.ovo.sablestopnow.sketch.Solid.Placed pl
                    : com.ovo.sablestopnow.sketch.SolidClientState.placed(level)) {
                if (filletSourceSolid == null || !pl.solid().body().equals(filletSourceSolid)) {
                    continue;
                }
                for (final double[] b : pl.boxes()) {
                    final double dx = Math.max(Math.max(b[0] - midS.x, 0), midS.x - b[3]);
                    final double dy = Math.max(Math.max(b[1] - midS.y, 0), midS.y - b[4]);
                    final double dz = Math.max(Math.max(b[2] - midS.z, 0), midS.z - b[5]);
                    bestS = Math.min(bestS, Math.sqrt(dx * dx + dy * dy + dz * dz));
                    if (dx <= 0 && dy <= 0 && dz <= 0) {
                        insideS = true;
                    }
                }
            }
            com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                    "[fillet] spacecheck body={} mid=({},{},{}) inside={} dist={}",
                    filletSourceSolid == null ? "?"
                            : filletSourceSolid.toString().substring(0, 8),
                    String.format(java.util.Locale.ROOT, "%.2f", midS.x),
                    String.format(java.util.Locale.ROOT, "%.2f", midS.y),
                    String.format(java.util.Locale.ROOT, "%.2f", midS.z),
                    insideS, String.format(java.util.Locale.ROOT, "%.3f", bestS));
        }
        double edgeLen = 0.0;
        for (final FilletPick pick : filletPicks) {
            final double dx = pick.seg()[3] - pick.seg()[0];
            final double dy = pick.seg()[4] - pick.seg()[1];
            final double dz = pick.seg()[5] - pick.seg()[2];
            edgeLen += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[fillet] create picks={} groups={} totalLen={} r={}",
                filletPicks.size(), filletGroupCount(),
                String.format(java.util.Locale.ROOT, "%.3f", edgeLen), radius);
        if (edgeLen < 1.0e-3) {
            // 退化棱：硬建一个"什么都不切"的特征只会让玩家以为功能坏了 ✓ 直接说清楚
            ModelMode.prompt("model.fillet.degenerate");
            return false;
        }
        int index = 1;
        for (final com.ovo.sablestopnow.sketch.Solid s
                : com.ovo.sablestopnow.sketch.SolidClientState.ofBody(level, filletSourceSolid)) {
            index = Math.max(index, s.index() + 1);
        }
        index = Math.max(index, LOCAL_SOLID_INDEX.getOrDefault(filletSourceSolid, 0) + 1);
        // ★ **一次点选 = 一个特征引用**：直线棱 1 段；曲线棱整条弧 = 1 个带折线的
        //   EdgeRef（curve 字段）⇒ 弧在特征系统里是**一条棱**，不是几十条碎棱 ✓
        final java.util.LinkedHashMap<Integer, java.util.List<FilletPick>> groups =
                new java.util.LinkedHashMap<>();
        for (final FilletPick pick : filletPicks) {
            groups.computeIfAbsent(pick.group(), k -> new java.util.ArrayList<>()).add(pick);
        }
        final java.util.List<com.ovo.sablestopnow.sketch.Solid.Shape.EdgeRef> refs =
                new java.util.ArrayList<>(groups.size());
        for (final java.util.List<FilletPick> g : groups.values()) {
            final FilletPick first = g.get(0);
            final org.joml.Vector3d n1 = first.n1() == null
                    ? new org.joml.Vector3d(0, 0, 0) : first.n1();
            final org.joml.Vector3d n2 = first.n2() == null
                    ? new org.joml.Vector3d(0, 0, 0) : first.n2();
            // ★ 引用归属：
            //   ① 镜像/阵列产物上拾取 ⇒ 必须指向**镜像/阵列特征**（求值在变换后的空间做）
            //   ② 拾取到切割体/基准面/圆角这类非材料实体 ⇒ 改用 owning 材料实体
            final org.joml.Vector3d gMid = new org.joml.Vector3d(
                    (first.seg()[0] + first.seg()[3]) * 0.5,
                    (first.seg()[1] + first.seg()[4]) * 0.5,
                    (first.seg()[2] + first.seg()[5]) * 0.5);
            java.util.UUID ownerId = filletSolidId;
            final com.ovo.sablestopnow.sketch.Solid ownerSolid = ownerId == null
                    ? null : com.ovo.sablestopnow.sketch.SolidClientState.byId(level, ownerId);
            final boolean ownerUsable = ownerSolid != null
                    && !(ownerSolid.shape()
                            instanceof com.ovo.sablestopnow.sketch.Solid.Shape.Cut)
                    && !ownerSolid.isPlane() && !ownerSolid.isFillet();
            if (!ownerUsable) {
                final java.util.UUID pip = pipSolid(filletSourceSolid, gMid);
                if (pip != null) {
                    ownerId = pip;
                }
            }
            if (g.size() == 1) {
                refs.add(new com.ovo.sablestopnow.sketch.Solid.Shape.EdgeRef(
                        ownerId,
                        first.seg()[0], first.seg()[1], first.seg()[2],
                        first.seg()[3], first.seg()[4], first.seg()[5],
                        n1.x, n1.y, n1.z, n2.x, n2.y, n2.z));
                continue;
            }
            // 曲线棱：折线 = 首段起点 + 每段终点（段已按链顺序定向）
            final double[] curve = new double[g.size() * 3 + 3];
            int kk = 0;
            curve[kk++] = first.seg()[0];
            curve[kk++] = first.seg()[1];
            curve[kk++] = first.seg()[2];
            for (final FilletPick pick : g) {
                curve[kk++] = pick.seg()[3];
                curve[kk++] = pick.seg()[4];
                curve[kk++] = pick.seg()[5];
            }
            refs.add(new com.ovo.sablestopnow.sketch.Solid.Shape.EdgeRef(
                    ownerId,
                    curve[0], curve[1], curve[2],
                    curve[kk - 3], curve[kk - 2], curve[kk - 1],
                    n1.x, n1.y, n1.z, n2.x, n2.y, n2.z, curve));
        }
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[fillet-step] EdgeRefs body={} count={} first=({},{},{})-({},{},{}) r={}",
                shortId8(filletSourceSolid), refs.size(),
                String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f",
                        refs.get(0).ax(), refs.get(0).ay(), refs.get(0).az()),
                String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f",
                        refs.get(0).bx(), refs.get(0).by(), refs.get(0).bz()), radius);
        // ★ 编辑已有圆角 ⇒ **原地更新**（半径/棱集换掉，id/序号/草图/外观不变）✓
        if (commitShape(new com.ovo.sablestopnow.sketch.Solid.Shape.Fillet(radius, refs))) {
            com.ovo.sablestopnow.client.model.FeaturePreview.clear();
            filletPicks.clear();
            filletPickSeq = 0;
            filletSolidId = null;
            filletSourceSolid = null;
            return true;
        }
        final java.util.UUID filletSketchId = sketchFor(filletSolidId, filletSourceSolid);
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[fillet-step] sketchId={} sourceSolid={}", shortId8(filletSketchId),
                shortId8(filletSourceSolid));
        if (filletSketchId == null) {
            ModelMode.prompt("model.order.missing_sketch");
            return false;
        }
        final com.ovo.sablestopnow.sketch.Solid fillet =
                com.ovo.sablestopnow.sketch.Solid.createFillet(filletSourceSolid,
                        filletSketchId, index, radius, refs);
        com.ovo.sablestopnow.network.SolidNetworking.sendSave(fillet);
        LOCAL_SOLID_INDEX.put(filletSourceSolid, fillet.index());
        com.ovo.sablestopnow.client.model.FeaturePreview.clear();
        // ★ 清空拾取状态：否则第二次进工具就还留着"已选棱 + 创建按钮"（用户实测）✓
        filletPicks.clear();
        filletPickSeq = 0;
        filletSolidId = null;
        filletSourceSolid = null;
        com.ovo.sablestopnow.SablestopNow.LOGGER.info("[fillet] created idx={} radius={} edges={}",
                fillet.index(), radius, refs.size());
        lastTreeSignature = null;
        refreshTreeNow();
        ModelMode.setTool(ModelTools.NONE);
        return true;
    }

    /**
     * **点击时按需重算一次拾取** ✓：`FeatureHover` 是渲染帧更新的，点击恰好落在
     * "还没更新"的帧就会拿到 null（用户实测"点棱/点面没反应"）。这里主动刷一次 ✓
     */
    public static com.ovo.sablestopnow.client.model.FeatureHover.Hit pickOnDemand() {
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return null;
        }
        com.ovo.sablestopnow.client.model.FeatureHover.update(mc);
        return com.ovo.sablestopnow.client.model.FeatureHover.get();
    }

    /**
     * 由**实体 id** 找它引用的草图 ✓（新特征必须挂在真实草图上，否则树的分组会跳过它 ✗
     * —— 这也解释了"创建了但右边栏没有"）
     */
    private static java.util.UUID sketchIdOfSolid(final java.util.UUID solidId) {
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.world.level.Level level = mc == null ? null : mc.level;
        if (level == null || solidId == null) {
            return null;
        }
        for (final com.ovo.sablestopnow.sketch.Solid.Placed pl
                : com.ovo.sablestopnow.sketch.SolidClientState.placed(level)) {
            if (pl.solid().id().equals(solidId)) {
                return pl.solid().sketchId();
            }
        }
        return null;
    }

    /**
     * 该体**任意实体的草图** ✓（兜底）：
     * 新特征必须挂真实草图，否则 Solid 构造会因为 sketchId=null 直接抛 ✗
     */
    private static java.util.UUID anySketchIdOfBody(final java.util.UUID body) {
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.world.level.Level level = mc == null ? null : mc.level;
        if (level == null || body == null) {
            return null;
        }
        for (final com.ovo.sablestopnow.sketch.Solid sd
                : com.ovo.sablestopnow.sketch.SolidClientState.ofBody(level, body)) {
            if (sd.sketchId() != null) {
                return sd.sketchId();
            }
        }
        return null;
    }

    /** 先按实体找草图，找不到再按体兜底 ✓ */
    private static java.util.UUID sketchFor(final java.util.UUID solidId, final java.util.UUID body) {
        final java.util.UUID bySolid = sketchIdOfSolid(solidId);
        return bySolid != null ? bySolid : anySketchIdOfBody(body);
    }

    /**
     * 由 body + 局部点反查**实体 id** ✓（盒兜底拾取时拿不到实体 id）
     *
     * <p>没有它的话 `EdgeRef.solidId` 只能填 body ⇒ `placeAll` 里 `byId.get(...)` 找不到源实体
     * ⇒ 圆角永远算不出切割体（这正是"圆角创建了但模型没变化"的真因）✗
     */
    private static java.util.UUID pipSolid(final java.util.UUID body,
                                           final org.joml.Vector3dc p) {
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.world.level.Level level = mc == null ? null : mc.level;
        if (level == null || body == null || p == null) {
            return null;
        }
        final com.ovo.sablestopnow.sketch.Sketch sk =
                com.ovo.sablestopnow.sketch.SketchClientState.current(level).stream()
                        .findFirst().orElse(null);
        java.util.UUID best = null;
        double bestD = Double.MAX_VALUE;
        for (final com.ovo.sablestopnow.sketch.Solid sd
                : com.ovo.sablestopnow.sketch.SolidClientState.ofBody(level, body)) {
            if (sd.isPlane() || sd.isFillet() || sd.isMirror() || sd.isPattern()
                    || sd.shape() instanceof com.ovo.sablestopnow.sketch.Solid.Shape.Cut) {
                continue;
            }
            final com.ovo.sablestopnow.sketch.Sketch own = sd.resolve(
                    com.ovo.sablestopnow.sketch.SketchClientState.current(level));
            if (own == null) {
                continue;
            }
            for (final double[] b : sd.boxes(own)) {
                final double dx = Math.max(0, Math.max(b[0] - p.x(), p.x() - b[3]));
                final double dy = Math.max(0, Math.max(b[1] - p.y(), p.y() - b[4]));
                final double dz = Math.max(0, Math.max(b[2] - p.z(), p.z() - b[5]));
                final double d = dx * dx + dy * dy + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = sd.id();
                }
            }
        }
        return best;
    }

    /** 基准面工具当前拾取到的面：中心（局部）+ 单位法线 ✓ */
    private static double[] planeFaceOrigin;
    private static double[] planeFaceNormal;
    private static java.util.UUID planeSourceSolid;
    /** 拾取到的那条棱/那个面**属于哪个实体**（用于把新特征挂到它的真实草图上）✓ */
    private static java.util.UUID planeSolidId;
    /** 当前在树里选中的实体（左栏据此显示"特征操作"：上移/下移/删除/贴图）✓ */
    private static java.util.UUID selectedSolidId;
    private static long lastFilletPreviewLog;
    /** 预览缓存签名（半径 + 已选段）⇒ 每帧重算几十段楔块会卡，只在变化时重建 ✓ */
    private static String lastFilletPreviewSig = "";
    private static String lastHoverLogged;
    private static String lastTreeItemsSig;
    private static long lastPlanePreviewLog;

    /** 当前在树里选中的特征 id ✓（渲染侧用于"选中也高亮"） */
    public static java.util.UUID selectedSolid() {
        return selectedSolidId;
    }
    /** 镜像源复选框的状态（按实体 id）✓ 面板重建也不丢 */
    /** 右栏"特征按钮"登记表：按鼠标位置判悬停（不依赖 TreeView 的 hoveredKey）✓ */
    private record FeatButton(com.ovo.sablestopnow.client.gui.lib.Widget widget,
                              java.util.UUID solidId) {
    }

    private static final java.util.List<FeatButton> FEAT_BUTTONS = new java.util.ArrayList<>();

    private static final java.util.Map<java.util.UUID,
            com.ovo.sablestopnow.client.gui.lib.Prop<Boolean>> PATTERN_SRC_PROPS =
            new java.util.HashMap<>();

    private static final java.util.Map<java.util.UUID,
            com.ovo.sablestopnow.client.gui.lib.Prop<Boolean>> MIRROR_SRC_PROPS =
            new java.util.HashMap<>();

    /**
     * 从复选框的 Prop **反推**镜像源集合 ✓
     *
     * <p>为什么不只靠 `onChange`：复选框的回调经由命令框架，时序不完全可控；
     * 只要 Prop 变了就一定能读到 ⇒ 以 Prop 为唯一真相最稳 ✓
     */
    private static void syncMirrorSourcesFromProps() {
        MIRROR_SRC_PROPS.forEach((id, prop) -> {
            if (Boolean.TRUE.equals(prop.get())) {
                if (!mirrorSources.contains(id)) {
                    mirrorSources.add(id);
                }
            } else {
                mirrorSources.remove(id);
            }
        });
    }

    private static com.ovo.sablestopnow.client.gui.lib.Prop<Boolean> mirrorSourceProp(
            final java.util.UUID id) {
        return MIRROR_SRC_PROPS.computeIfAbsent(id, k ->
                com.ovo.sablestopnow.client.gui.lib.Prop.of(
                        "mirror.src." + k.toString().substring(0, 8),
                        mirrorSources.contains(k)));
    }
    private static java.util.UUID filletSolidId;
    private static int planeSourceFace = -1;

    /** 基准面工具：点到一个模型面 ⇒ 记下来并打开"距离"输入框（带实时预览）✓ */
    public static boolean planePickFace(
            final com.ovo.sablestopnow.client.model.FeatureHover.Hit hit) {
        if (hit == null || hit.face() == null || !hit.face().planar()) {
            // ★ 兜底：用**盒子**射线拾取（碰撞与渲染的共同底座 ⇒ 永远能命中）
            final var mc0 = net.minecraft.client.Minecraft.getInstance();
            final BoxPick.Hit bh = BoxPick.nearest(mc0);
            if (bh == null || mc0.level == null) {
                ModelMode.prompt("model.plane.pick_face");
                return false;
            }
            final double[] loc = BoxPick.toLocal(mc0.level, bh.body(), bh.worldPoint(),
                    bh.worldNormal());
            if (loc == null) {
                ModelMode.prompt("model.plane.pick_face");
                return false;
            }
            planeFaceOrigin = new double[] {loc[0], loc[1], loc[2]};
            planeFaceNormal = new double[] {loc[3], loc[4], loc[5]};
            planeSourceSolid = bh.body();
            planeSolidId = null; // 盒子兜底没有实体 id ⇒ 用该体任意草图
            planeSourceFace = -1;
            updatePlanePreview(planeDistance.get());
            lastLeftSignature = null;
            refreshLeftPanel();
            com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                    "[plane] picked via BOX fallback body={} origin={}", bh.body(),
                    java.util.Arrays.toString(planeFaceOrigin));
            return true;
        }
        planeFaceOrigin = new double[] {hit.localCenter().x, hit.localCenter().y,
                hit.localCenter().z};
        planeFaceNormal = new double[] {hit.localNormal().x, hit.localNormal().y,
                hit.localNormal().z};
        planeSourceSolid = hit.body();
        planeSolidId = hit.solidId();
        planeSourceFace = hit.face().id();
        updatePlanePreview(planeDistance.get());
        // ★ 选完面**只刷新左栏**：参数与"创建"按钮都在左栏（用户要求"进去就有完整菜单"）✓
        lastLeftSignature = null;
        refreshLeftPanel();
        com.ovo.sablestopnow.SablestopNow.LOGGER.info("[plane] face picked body={} face={}",
                planeSourceSolid, planeSourceFace);
        return true;
    }

    /** 预览：把"偏移后的平面"画成一个黄色方框 ✓（与渲染共用 Plane.corners ✓） */
    private static void updatePlanePreview(final double distance) {
        if (planeFaceOrigin == null || planeFaceNormal == null) {
            return;
        }
        final com.ovo.sablestopnow.sketch.Solid.Shape.Plane tmp =
                new com.ovo.sablestopnow.sketch.Solid.Shape.Plane(
                        planeFaceOrigin[0], planeFaceOrigin[1], planeFaceOrigin[2],
                        planeFaceNormal[0], planeFaceNormal[1], planeFaceNormal[2],
                        distance, planeSolidId, planeSourceFace);
        final java.util.List<org.joml.Vector3d> cs = tmp.corners(1.0);
        final java.util.List<com.ovo.sablestopnow.client.model.FeaturePreview.Seg> segs =
                new java.util.ArrayList<>(4);
        for (int i = 0; i < 4; i++) {
            segs.add(new com.ovo.sablestopnow.client.model.FeaturePreview.Seg(
                    cs.get(i), cs.get((i + 1) % 4)));
        }
        com.ovo.sablestopnow.client.model.FeaturePreview.set(planeSourceSolid,
                new org.joml.Vector3d(planeFaceNormal[0], planeFaceNormal[1], planeFaceNormal[2]),
                segs);
        if (System.currentTimeMillis() - lastPlanePreviewLog > 2000L) {
            lastPlanePreviewLog = System.currentTimeMillis();
            com.ovo.sablestopnow.SablestopNow.LOGGER.info("[plane] preview segs=4 dist={}",
                    distance);
        }
    }

    /** 真正创建基准面特征 ✓（与其它特征同级、受序号/依赖约束） */
    private static boolean createPlaneFeature(final double distance) {
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.world.level.Level level = mc == null ? null : mc.level;
        if (level == null || planeFaceOrigin == null || planeFaceNormal == null
                || planeSourceSolid == null) {
            return false;
        }
        int index = 1;
        for (final com.ovo.sablestopnow.sketch.Solid s
                : com.ovo.sablestopnow.sketch.SolidClientState.ofBody(level, planeSourceSolid)) {
            index = Math.max(index, s.index() + 1);
        }
        index = Math.max(index, LOCAL_SOLID_INDEX.getOrDefault(planeSourceSolid, 0) + 1);
        // ★ 编辑已有基准面 ⇒ **原地更新**（偏移/来源换掉，id/序号/草图/外观不变）✓
        if (commitShape(new com.ovo.sablestopnow.sketch.Solid.Shape.Plane(
                planeFaceOrigin[0], planeFaceOrigin[1], planeFaceOrigin[2],
                planeFaceNormal[0], planeFaceNormal[1], planeFaceNormal[2],
                distance, planeSourceSolid, planeSourceFace))) {
            com.ovo.sablestopnow.client.model.FeaturePreview.clear();
            planeFaceOrigin = null;
            planeFaceNormal = null;
            planeSolidId = null;
            planeSourceSolid = null;
            planeSourceFace = -1;
            return true;
        }
        final java.util.UUID planeSketchId = sketchFor(planeSolidId, planeSourceSolid);
        if (planeSketchId == null) {
            ModelMode.prompt("model.order.missing_sketch");
            return false;
        }
        final com.ovo.sablestopnow.sketch.Solid plane =
                com.ovo.sablestopnow.sketch.Solid.createPlane(planeSourceSolid,
                        planeSketchId, index,
                        planeFaceOrigin[0], planeFaceOrigin[1], planeFaceOrigin[2],
                        planeFaceNormal[0], planeFaceNormal[1], planeFaceNormal[2],
                        distance, planeSourceSolid, planeSourceFace);
        com.ovo.sablestopnow.network.SolidNetworking.sendSave(plane);
        LOCAL_SOLID_INDEX.put(planeSourceSolid, plane.index());
        com.ovo.sablestopnow.client.model.FeaturePreview.clear();
        planeFaceOrigin = null;
        planeFaceNormal = null;
        planeSolidId = null;
        planeSourceSolid = null;
        planeSourceFace = -1;
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[plane] created idx={} dist={} face={}", plane.index(), distance, planeSourceFace);
        lastTreeSignature = null;
        refreshTreeNow();
        ModelMode.setTool(ModelTools.NONE);
        return true;
    }

    /**
     * 由"模式 + 待定深度"构造 {@code Extrude.Params} ✓。
     *
     * <p>**负深度 = 反向**：单侧模式下负数改用 {@code ONE_SIDED_BACK}；
     * 双向/对称模式下负号只表示"填错了符号"，一律取绝对值 ✓
     */
    private static com.ovo.sablestopnow.sketch.Extrude.Params buildExtrudeParams(
            final double depth1, final double depth2) {
        final double d1 = Math.max(0.1, Math.abs(depth1));
        final double d2 = Math.abs(depth2);
        final boolean back = depth1 < 0.0;
        return switch (extrudeMode) {
            case TWO_SIDED -> com.ovo.sablestopnow.sketch.Extrude.Params.twoSided(d1, d2);
            case SYMMETRIC -> com.ovo.sablestopnow.sketch.Extrude.Params.symmetric(d1);
            case MID_PLANE -> new com.ovo.sablestopnow.sketch.Extrude.Params(
                    com.ovo.sablestopnow.sketch.Extrude.Mode.MID_PLANE, d1, 0.0);
            case ONE_SIDED_BACK -> new com.ovo.sablestopnow.sketch.Extrude.Params(
                    com.ovo.sablestopnow.sketch.Extrude.Mode.ONE_SIDED_BACK, d1, 0.0);
            default -> back
                    ? new com.ovo.sablestopnow.sketch.Extrude.Params(
                            com.ovo.sablestopnow.sketch.Extrude.Mode.ONE_SIDED_BACK, d1, 0.0)
                    : com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(d1);
        };
    }

    private static void featureAction(final String action,
                                      final com.ovo.sablestopnow.sketch.Solid solid) {
        final net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        final net.minecraft.world.level.Level level = mc == null ? null : mc.level;
        if (level == null || solid == null) {
            return;
        }
        // ★ 树上“编辑”子行：把该特征的全部参数预填进左栏，并进入**原地更新**模式 ✓
        if ("edit".equals(action)) {
            beginFeatureEdit(solid);
            return;
        }
        if ("del".equals(action)) {
            com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                    "[feature] DELETE request id={} idx={} body={}",
                    solid.id(), solid.index(), solid.body());
            com.ovo.sablestopnow.network.SolidNetworking.sendRemove(solid.id());
            com.ovo.sablestopnow.client.model.FeaturePreview.clear();
            com.ovo.sablestopnow.SablestopNow.LOGGER.info("[feature] delete solid idx={}",
                    solid.index());
            lastTreeSignature = null;
            refreshTreeNow();
            return;
        }
        final int dir = "up".equals(action) ? -1 : 1;
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[feature] MOVE request action={} id={} idx={} count={}",
                action, solid.id(), solid.index(), 0);
        final java.util.List<com.ovo.sablestopnow.sketch.Solid> solids =
                com.ovo.sablestopnow.sketch.SolidClientState.ofBody(level, solid.body());
        final java.util.List<java.util.UUID> sketchIds = new java.util.ArrayList<>();
        for (final com.ovo.sablestopnow.sketch.Sketch sk
                : com.ovo.sablestopnow.sketch.SketchClientState.current(level)) {
            sketchIds.add(sk.id());
        }
        final java.util.List<com.ovo.sablestopnow.sketch.Solid> moved =
                com.ovo.sablestopnow.sketch.FeatureDeps.moved(solids, solid.id(), dir, sketchIds);
        if (moved == null) {
            ModelMode.prompt("model.order.rejected");
            com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                    "[feature] order rejected solid={} dir={}", solid.id(), dir);
            return;
        }
        for (final com.ovo.sablestopnow.sketch.Solid s2 : moved) {
            for (final com.ovo.sablestopnow.sketch.Solid old : solids) {
                if (old.id().equals(s2.id()) && old.index() != s2.index()) {
                    com.ovo.sablestopnow.network.SolidNetworking.sendSave(s2);
                    com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                            "[feature] reorder {} : {} -> {}", s2.id(), old.index(), s2.index());
                }
            }
        }
        lastTreeSignature = null;
        refreshTreeNow();
    }

    /** 主按钮文案：编辑已有特征时显示“更新”，否则保持原来的“创建”文案 ✓ */
    private static Component applyLabel(final String createKey) {
        return Component.translatable(
                editingSolidId != null ? "model.feature.update" : createKey);
    }

    /**
     * 开始**编辑一个已有特征**（右树“编辑”子行）✓
     *
     * <p>把该特征的全部参数预填进左栏，并记住 {@link #editingSolidId} ⇒
     * 面板主按钮与底部“完成”都会**原地更新**它（id / 序号 / 草图 / 外观都不变），
     * 不会像以前那样新建一个重复特征。
     */
    private static void beginFeatureEdit(final com.ovo.sablestopnow.sketch.Solid solid) {
        if (solid == null || solid.shape() == null) {
            return;
        }
        // 左栏是**单槽编辑器**：先把别的编辑态清掉（与树里各种选择互斥的既有纪律一致）
        ModelForceUi.select(null);
        ModelCouplingUi.clearSelection();
        MateClientState.setHighlightedMate(null);
        textureSolidId = null;
        final com.ovo.sablestopnow.sketch.Solid.Shape shape = solid.shape();
        selectedSolidId = solid.id();
        ModelMode.selectModel(solid.body());
        boolean ok = true;
        if (shape instanceof final com.ovo.sablestopnow.sketch.Solid.Shape.Extrusion e) {
            ModelMode.setTool(ModelTools.EXTRUDE);
            revolveMode = false;
            cutMode = false;
            extrudeSource = solid.sketchId();
            extrudeDepth.set(e.params().depth1());
            extrudeBackDepth.set(e.params().depth2());
            extrudeMode = e.params().mode();
        } else if (shape instanceof final com.ovo.sablestopnow.sketch.Solid.Shape.Cut c) {
            ModelMode.setTool(ModelTools.EXTRUDE);
            revolveMode = false;
            cutMode = true;
            extrudeSource = solid.sketchId();
            extrudeDepth.set(c.params().depth1());
            extrudeBackDepth.set(c.params().depth2());
            extrudeMode = c.params().mode();
        } else if (shape instanceof final com.ovo.sablestopnow.sketch.Solid.Shape.Revolution r) {
            ModelMode.setTool(ModelTools.EXTRUDE);
            revolveMode = true;
            cutMode = false;
            extrudeSource = solid.sketchId();
            final com.ovo.sablestopnow.sketch.Revolve.Params p = r.params();
            revolveAxis = new double[] {p.axisU(), p.axisV(), p.dirU(), p.dirV()};
            revolveAngle.set(p.angleDeg());
            revolveDirection = p.direction();
        } else if (shape instanceof final com.ovo.sablestopnow.sketch.Solid.Shape.Fillet f) {
            ModelMode.setTool(ModelTools.FILLET);
            filletSourceSolid = solid.body();
            filletSolidId = null;
            filletRadius.set(f.radius());
            prefillFilletPicks(f.edges());
        } else if (shape instanceof final com.ovo.sablestopnow.sketch.Solid.Shape.Mirror m) {
            ModelMode.setTool(ModelTools.MIRROR);
            mirrorBody = solid.body();
            mirrorSources.clear();
            mirrorSources.addAll(m.sources());
            mirrorPlaneOrigin = new org.joml.Vector3d(m.ox(), m.oy(), m.oz());
            mirrorPlaneNormal = new org.joml.Vector3d(m.nx(), m.ny(), m.nz());
        } else if (shape instanceof final com.ovo.sablestopnow.sketch.Solid.Shape.Pattern p) {
            ModelMode.setTool(ModelTools.PATTERN);
            PatternTool.prefill(solid.body(), p.dirs(), p.sources());
            if (!p.dirs().isEmpty()) {
                patternN1.set((double) p.dirs().get(0).count());
                patternS1.set(p.dirs().get(0).spacing());
            }
            if (p.dirs().size() >= 2) {
                patternN2.set((double) p.dirs().get(1).count());
                patternS2.set(p.dirs().get(1).spacing());
            }
        } else if (shape instanceof final com.ovo.sablestopnow.sketch.Solid.Shape.Plane pl) {
            ModelMode.setTool(ModelTools.PLANE);
            planeSourceSolid = solid.body();
            planeSourceFace = pl.sourceFace();
            planeFaceOrigin = new double[] {pl.ox(), pl.oy(), pl.oz()};
            planeFaceNormal = new double[] {pl.nx(), pl.ny(), pl.nz()};
            planeDistance.set(Math.abs(pl.distance()));
        } else {
            ok = false;
        }
        if (!ok) {
            editingSolidId = null;
            ModelMode.prompt("model.feature.not_editable");
            return;
        }
        editingSolidId = solid.id();
        lastLeftSignature = null;
        refreshLeftPanel();
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[feature-edit] begin id={} idx={} shape={}",
                solid.id(), solid.index(), shape.getClass().getSimpleName());
    }

    /**
     * 把存档里的 `EdgeRef` **反构**成拾取项 ✓（编辑圆角时预填左栏的棱列表）
     *
     * <p>一个 `EdgeRef` = 一条棱（直线 1 段；曲线棱是整条弧的折线）⇒
     * 同一个 ref 的段共用**一个 group**，和当初“一次点选 = 一个特征引用”的规则一致。
     * 凸凹标记存不进 `EdgeRef`，这里给 −1（未知），求值时由网格解析判定 —— 与新建路径一致 ✓
     */
    private static void prefillFilletPicks(
            final java.util.List<com.ovo.sablestopnow.sketch.Solid.Shape.EdgeRef> refs) {
        filletPicks.clear();
        filletPickSeq = 0;
        if (refs == null) {
            return;
        }
        for (final com.ovo.sablestopnow.sketch.Solid.Shape.EdgeRef ref : refs) {
            final int g = filletPickSeq++;
            final org.joml.Vector3d n1 = new org.joml.Vector3d(
                    ref.n1x(), ref.n1y(), ref.n1z());
            final org.joml.Vector3d n2 = new org.joml.Vector3d(
                    ref.n2x(), ref.n2y(), ref.n2z());
            final java.util.List<double[]> segs = ref.isCurve()
                    ? ref.curveSegs() : java.util.List.of();
            if (!segs.isEmpty()) {
                for (final double[] s : segs) {
                    filletPicks.add(new FilletPick(g, copySeg(s), n1, n2, -1));
                }
            } else {
                filletPicks.add(new FilletPick(g, new double[] {ref.ax(), ref.ay(), ref.az(),
                        ref.bx(), ref.by(), ref.bz()}, n1, n2, -1));
            }
        }
    }

    /** 把当前左栏参数**应用到正在编辑的特征**（面板主按钮与“完成”共用同一套动作）✓ */
    private static boolean applyLeftEditor() {
        final String tool = ModelMode.tool();
        if (ModelTools.EXTRUDE.equals(tool)) {
            return revolveMode ? createRevolve() : createExtrude();
        }
        if (ModelTools.FILLET.equals(tool)) {
            return createFilletFeature(filletRadius.get());
        }
        if (ModelTools.MIRROR.equals(tool)) {
            return createMirrorFeature();
        }
        if (ModelTools.PATTERN.equals(tool)) {
            return createPatternFeature((int) (double) patternN1.get(), patternS1.get(),
                    (int) (double) patternN2.get(), patternS2.get());
        }
        if (ModelTools.PLANE.equals(tool)) {
            return createPlaneFeature(planeDistance.get());
        }
        return false;
    }

    /**
     * 把形状落到**正在编辑的特征**上 ✓（同 id / 序号 / 草图 / 外观，只换形状）
     *
     * <p>服务端 `SaveSolidPayload` 是**按 id upsert**（`SolidData.put`），
     * 调序/贴图已经在用同一条链路，所以不需要新协议。
     *
     * @return true = 已经作为“更新”提交（调用方**不要**再去新建）
     */
    private static boolean commitShape(final com.ovo.sablestopnow.sketch.Solid.Shape next) {
        final java.util.UUID id = editingSolidId;
        if (id == null || next == null) {
            return false;
        }
        final com.ovo.sablestopnow.sketch.Solid editing = resolveSolid(id);
        if (editing == null) {
            editingSolidId = null;
            return false;
        }
        final com.ovo.sablestopnow.sketch.Solid updated = editing.withShape(next);
        com.ovo.sablestopnow.network.SolidNetworking.sendSave(updated);
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[feature-edit] update id={} idx={} shape={}",
                updated.id(), updated.index(), next.getClass().getSimpleName());
        editingSolidId = null;
        // ★ 更新完就退出工具：主按钮若还在，再点一下会**新建一个重复特征**
        ModelMode.setTool(ModelTools.NONE);
        ModelMode.clearPicks();
        lastLeftSignature = null;
        refreshTreeNow();
        refreshLeftPanel();
        ModelMode.prompt("model.feature.updated");
        return true;
    }

    /**
     * 阵列主按钮：编辑态 ⇒ 原地更新；否则走原来的新建 ✓
     */
    private static boolean createPatternFeature(final int n1, final double s1,
                                                final int n2, final double s2) {
        final com.ovo.sablestopnow.sketch.Solid.Shape.Pattern next =
                PatternTool.buildShape(n1, s1, n2, s2);
        if (next == null) {
            ModelMode.prompt("model.pattern.no_sources");
            return false;
        }
        if (commitShape(next)) {
            return true;
        }
        PatternTool.create(n1, s1, n2, s2);
        return true;
    }

    private static boolean createExtrude() {
        final com.ovo.sablestopnow.sketch.Sketch source = currentExtrudeSource();
        if (source == null) {
            ModelMode.prompt("model.prompt.extrude_pick_sketch");
            return false;
        }
        final double d1 = extrudeDepth.get();
        final double d2 = extrudeBackDepth.get();
        final com.ovo.sablestopnow.sketch.Extrude.Params params =
                buildExtrudeParams(d1, d2);
        // 先确认真的能拉出东西：轮廓不闭合 / 全是退化轮廓时给出提示，
        // 而不是建一个永远画不出来的空实体。
        if (com.ovo.sablestopnow.sketch.Extrude.boxesOf(source, params).isEmpty()) {
            ModelMode.prompt("model.prompt.extrude_no_profile");
            return false;
        }
        // ★ 编辑已有特征 ⇒ **原地更新**同一个特征（id/序号/草图/外观不变）✓
        final com.ovo.sablestopnow.sketch.Solid.Shape shape = cutMode
                ? new com.ovo.sablestopnow.sketch.Solid.Shape.Cut(params)
                : new com.ovo.sablestopnow.sketch.Solid.Shape.Extrusion(params);
        if (commitShape(shape)) {
            return true;
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
        final com.ovo.sablestopnow.sketch.Solid solid = cutMode
                ? com.ovo.sablestopnow.sketch.Solid.createCut(
                        source.body(), source.id(), index, params)
                : com.ovo.sablestopnow.sketch.Solid.create(
                        source.body(), source.id(), index, params);
        com.ovo.sablestopnow.network.SolidNetworking.sendSave(solid);
        LOCAL_SOLID_INDEX.put(source.body(), solid.index());  // ★ 记住这个 body 用到的序号 ✓
        // ★ 立刻重建右侧树 ✓（不强制的话要等"指纹变化"才刷，玩家会觉得"新拉伸没出现"✗）
        refreshTreeNow();
        ModelMode.prompt("model.prompt.extrude_created");
        refreshLeftPanel();
        return true;
    }

    /** 左栏内容的输入：功能、已选元素、已选类型、当前模型、选中的施力点/耦合。 */
    private static String leftPanelSignature() {
        final StringBuilder sb = new StringBuilder(ModelMode.tool());
        // ★★ 修实测"进草图时左栏无变化" ✓✓
        //   指纹里**没有草图状态** ✗ ⇒ 进出草图、切换草图、约束数变化都不会重建左栏 ✓
        //   （左栏内容是按 active()/stage 分支的 ✓ 但指纹不变 ⇒ 分支永远不重跑 ✗）
        sb.append("#sk:").append(com.ovo.sablestopnow.client.SketchClient.active() ? "1" : "0");
        final var skSess = com.ovo.sablestopnow.client.SketchClient.mode().session();
        sb.append('#').append(skSess == null || skSess.working() == null
                ? "-" : skSess.working().id().toString());
        sb.append('#').append(skSess == null || skSess.working() == null
                ? -1 : skSess.working().constraints().size());
        sb.append('#').append(skSess == null ? "-" : skSess.stage().name());
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
        // ★ 需求 ⑥：切割开关也进指纹 ✓（否则点它左栏不重建 ⇒ 看起来"没反应"✗）
        sb.append('#').append(cutMode);
        // ⚠ 旋转那套也要进指纹：切到旋转会换掉整组控件（轴列表/角度/旋向），
        // 选了另一条轴要高亮，旋向要能看出选中态。不进指纹这些全是"点了没反应"。
        sb.append('#').append(revolveMode);
        sb.append('#').append(revolveDirection);
        sb.append('#').append(revolveAxis == null ? "-"
                : (revolveAxis[0] + "," + revolveAxis[1]));
        // ⚠ 贴图编辑区也要进指纹：点了「贴图」要展开编辑区、改了主手方块要刷新"当前贴图"那一行。
        // 不进就是"点了没反应"（这个坑在本文件里已经踩过好几次）。
        sb.append('#').append(textureSolidId);
        // ★ 正在编辑的特征也必须进指纹：它决定主按钮显示“更新”还是“创建”，
        //   不进指纹就会出现“面板还写着创建、点下去却更新”的错乱。
        sb.append('#').append(editingSolidId);
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

    /** 上次打印的"树两侧数量"诊断（变化才打 ✓）。 */
    private static String lastTreeDiag = "";

    /** 上次打印的"树形状"诊断（变化才打 ✓）。 */
    private static String lastTreeShape = "";

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
            // ★ 重建时把展开动画**直接吸附到目标值** ✓
            //   否则新 TreeItem 的 open 动画从 1.0 重新出发 ⇒ 每次重建都"抽一下"
            //   （用户实测："点击结构/创建特征时右侧树状图抽搐一次"）✓
            item.open.snap(item.expanded ? 1.0 : 0.0);
            applyRememberedExpansion(item.children);
        }
    }

    /** 当前这棵树 ✓（为"草图选中 ⇒ 树里高亮"提供反查入口 ✓ 与 syncSmartCandidateToggles 同一套路 ✓）。 */
    @Nullable
    private static TreeView activeTree;

    /**
     * **草图 → 树**的高亮同步 ✓（每帧调用 ✓ 只改 highlight/selected ✓ 不重建树 ✗）。
     *
     * <p>为什么不能靠"改变签名触发重建" ✗✓：草图选择的身份进签名会让面板**每帧重建**，
     * 输入焦点会被反复夺走（改值框里就没法打字了 ✓ 这个坑踩过 ✓）。
     * 所以走"每帧轻量改动已有控件"的路子 ✓ —— 与智能尺寸候选开关是同一招 ✓。
     */
    public static void syncSketchTreeHighlight() {
        final TreeView tree = activeTree;
        if (tree == null) {
            return;
        }
        // ⚠ 用两层判空 ✗✓：active() 为真时 session() 通常非 null ✓，但**不保证**（进入/退出的那一帧）✓
        //   —— 每帧调用的小函数绝不能因为一次竞态就把渲染线程打崩 ✓
        final var sess = com.ovo.sablestopnow.client.SketchClient.active()
                ? com.ovo.sablestopnow.client.SketchClient.mode().session() : null;
        final var sketch = sess == null ? null : sess.working();
        final java.util.UUID wanted = sketch == null ? null : sketch.id();
        for (final TreeView.TreeItem root : tree.roots()) {
            markSketchRow(root, wanted);
        }
    }

    private static void markSketchRow(final TreeView.TreeItem item, final java.util.UUID wanted) {
        boolean want = false;
        if (wanted != null && item.data instanceof com.ovo.sablestopnow.sketch.Sketch s) {
            want = wanted.equals(s.id());
        }
        item.highlight = want;
        for (final TreeView.TreeItem child : item.children) {
            markSketchRow(child, wanted);
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
        // ★ 需求：草图也加序号 ✓（按 id 排序 ⇒ 序号稳定，不随"被用掉/被删"重排 ✓）
        final java.util.List<UUID> sketchOrder = new java.util.ArrayList<>(byId.keySet());
        sketchOrder.sort(java.util.Comparator.comparing(UUID::toString));
        for (final UUID freeId : grouping.freeSketches()) {
            final com.ovo.sablestopnow.sketch.Sketch sketch = byId.get(freeId);
            if (sketch == null) {
                continue;
            }
            // ⚠ key 里必须带所属结构：TreeView 的行是**按 key 复用**的，
            // 同一个 key 在两个结构下出现会撞车（配合那边踩过：第二个位置拿到第一个的控件）。
            final TreeView.TreeItem row = new TreeView.TreeItem(
                    "sketch:" + bodyId + ":" + sketch.id())
                    .label(Component.literal("#"
                            + (sketchOrder.indexOf(sketch.id()) + 1) + " "
                            + sketch.name()));
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
        // ★ 诊断（实测"创建后草图消失、新拉伸不出现"✓）：把两侧数量记下来
        //   solids=0 & sketches 少一张 ⇒ 是**数据没同步回来**（服务端没接受/广播丢）
        //   solids>0 ⇒ 数据在，问题在树/渲染
        final String treeDiag = (bodyId == null ? "?" : bodyId.toString().substring(0, 8))
                + " solids=" + currentSolidsOf(bodyId).size()
                + " sketches=" + currentSketchesOf(bodyId).size();
        if (!treeDiag.equals(lastTreeDiag)) {
            lastTreeDiag = treeDiag;
            com.ovo.sablestopnow.SablestopNow.LOGGER.info("[tree] {}", treeDiag);
        }
        final TreeView.TreeItem node = new TreeView.TreeItem("solids:" + bodyId)
                .label(Component.translatable("model.node.solids"));
        // 按序号排序再建行：数据源是 map 顺序，交换序号后行位置不变，看起来像"没反应"
        final java.util.List<com.ovo.sablestopnow.sketch.Solid> solids =
                new java.util.ArrayList<>(currentSolidsOf(bodyId));
        solids.sort(java.util.Comparator.comparingInt(
                com.ovo.sablestopnow.sketch.Solid::index));
        // ★ 拓扑引用：客户端用**同一份确定性逻辑**重建（与服务端 id 一致）✓
        {
            com.ovo.sablestopnow.sketch.TopoStore.clear();
            com.ovo.sablestopnow.sketch.TopoRebuild.collect(
                    currentSolidsOf(bodyId),
                    ref -> com.ovo.sablestopnow.sketch.TopoStore.ensureEdge(
                            ref.bodyId(), ref.contributors(),
                            new double[] {ref.geom()[0], ref.geom()[1], ref.geom()[2]},
                            new double[] {ref.geom()[3], ref.geom()[4], ref.geom()[5]},
                            new double[] {ref.geom()[6], ref.geom()[7], ref.geom()[8]},
                            new double[] {ref.geom()[9], ref.geom()[10], ref.geom()[11]}));
        }
        // ★ 诊断：右栏到底收到了哪些实体（"新建的拉伸没进列表"靠这行定位）
        {
            final StringBuilder sb = new StringBuilder();
            for (final com.ovo.sablestopnow.sketch.Solid sd : solids) {
                sb.append(sd.index()).append(':')
                        .append(sd.id().toString().substring(0, 6)).append(' ');
            }
            final String sig2 = bodyId + "|" + sb;
            if (!sig2.equals(lastTreeItemsSig)) {
                lastTreeItemsSig = sig2;
                com.ovo.sablestopnow.SablestopNow.LOGGER.info("[tree-items] body={} n={} {}",
                        bodyId == null ? "?" : bodyId.toString().substring(0, 8),
                        solids.size(), sb.toString().trim());
            }
        }
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
            // ★ 需求：切割在树里要叫**切割** ✓（以前一律显示"拉伸"✗）
            // ★ 树里显示**永久名字**（拉伸1/切割2/圆角3…）✓
            //   之前用 index 造型号 ⇒ 一交换/删除，后面所有行的名字都变（用户实测）✗
            final Component label = Component.literal(
                    com.ovo.sablestopnow.client.model.FeatureNames.of(solid, true));
            final TreeView.TreeItem row = new TreeView.TreeItem(
                    "solid:" + bodyId + ":" + solid.id()).label(label);
            row.data = solid;
            // **贴图入口做在树上**（用户 2026-10 的要求）。
            // 以前它只藏在"拉伸工具"面板里，所以用户根本找不到 ——
            // 特征节点上就该有它自己的编辑入口。
            // ★ item 4：**顺序调整**（↑/↓）+ 删除（item 6.5）—— 以子行形式出现（树只支持 child 行）
            // ★ 用户要求：**每个特征都要有自己的“编辑”入口**，点了在左栏编辑参数 ✓
            //   （以前只有“拉伸/旋转”点行会半途预填，而且没有记住编辑目标 ⇒ 会新建重复特征）
            final TreeView.TreeItem editRow = new TreeView.TreeItem(
                    "feat:edit:" + bodyId + ":" + solid.id())
                    .label(Component.translatable("model.feature.edit"));
            editRow.data = solid;
            row.child(editRow);
            final TreeView.TreeItem upRow = new TreeView.TreeItem(
                    "feat:up:" + bodyId + ":" + solid.id())
                    .label(Component.translatable("model.order.up"));
            upRow.data = solid;
            row.child(upRow);
            final TreeView.TreeItem downRow = new TreeView.TreeItem(
                    "feat:down:" + bodyId + ":" + solid.id())
                    .label(Component.translatable("model.order.down"));
            downRow.data = solid;
            row.child(downRow);
            final TreeView.TreeItem delRow = new TreeView.TreeItem(
                    "feat:del:" + bodyId + ":" + solid.id())
                    .label(Component.translatable("model.order.delete"));
            delRow.data = solid;
            row.child(delRow);
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
    /**
     * **强制**重建左栏 ✓：`refreshLeftPanel()` 有"签名没变就早退"的优化，
     * 而特征工具拾取后只改了私有状态（选中了哪条棱/哪个面、选了几个源），签名不会变 ⇒
     * 面板不刷新 ⇒ 看起来"点了没反应"（镜像/阵列实测就是这样）✗
     */
    public static void forceRefreshLeft() {
        lastLeftSignature = null;
        refreshLeftPanel();
    }

    /**
     * 左栏当前是否“正在编辑某个东西” ✓ —— **唯一判据**。
     *
     * <p>以前“面板显示什么”和“完成按钮能不能点”是两套手工维护的判据，
     * 贴图面板（可以在无工具状态下从树上打开）漏在第二套之外 ⇒
     * 面板明明在显示，底部“完成”却是灰的，只能靠面板内部的按钮关掉
     *（用户实测“贴图只能用应用贴图来关闭”）。现在两处都只认这一个函数。
     */
    private static boolean leftEditing() {
        return !ModelTools.NONE.equals(ModelMode.tool())
                || ModelForceUi.selectedId() != null
                || ModelCouplingUi.hasSelection()
                || MateClientState.highlightedMate() != null
                || textureSolidId != null
                || editingSolidId != null;
    }

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

          // ===== item 7/8/9/10：四个特征的**左栏完整面板** ✓ =====
          //   要点：一进入工具，左栏就把「要你做什么 / 已经选了哪些 / 参数 / 创建」全部摆出来 ✓
          //   （用户实测："应该一进入就在左侧显示完整的菜单，然后有专门的地方显示选了哪些东西，还有调整参数"）
          // ★ 选中某个特征 ⇒ 左栏给"特征操作"（上移/下移/删除/贴图）✓
          //   不依赖右栏树子行是否展开（用户实测："上移下移无反应"，多半是子行没展开/点不到）✓
          final boolean featureToolNow = ModelTools.PLANE.equals(tool)
                  || ModelTools.FILLET.equals(tool) || ModelTools.MIRROR.equals(tool)
                  || ModelTools.PATTERN.equals(tool);
          if (!featureToolNow && selectedSolidId != null && editingSolidId == null) {
              final net.minecraft.client.Minecraft mc0 =
                      net.minecraft.client.Minecraft.getInstance();
              final net.minecraft.world.level.Level lv0 = mc0 == null ? null : mc0.level;
              com.ovo.sablestopnow.sketch.Solid sel = null;
              if (lv0 != null) {
                  for (final com.ovo.sablestopnow.sketch.Solid sd
                          : com.ovo.sablestopnow.sketch.SolidClientState.in(
                                  lv0.dimension().location())) {
                      if (sd.id().equals(selectedSolidId)) {
                          sel = sd;
                          break;
                      }
                  }
              }
              if (sel != null) {
                  final com.ovo.sablestopnow.sketch.Solid target = sel;
                  body.add(new Label(Component.literal(solidShortLabel(target)))
                          .color(0xFFFFFFFF));
                  body.add(new com.ovo.sablestopnow.client.gui.lib.Separator());
                  body.add(new Button(Component.translatable("model.order.up"), () -> {
                      featureAction("up", target);
                      forceRefreshLeft();
                  }).width(Widget.FILL));
                  body.add(new Button(Component.translatable("model.order.down"), () -> {
                      featureAction("down", target);
                      forceRefreshLeft();
                  }).width(Widget.FILL));
                  body.add(new Button(Component.translatable("model.texture.open"), () -> {
                      textureSolidId = target.id();
                      textureScale.set(target.appearance().blocksPerTile());
                      textureConnected.set(target.appearance().connected());
                      forceRefreshLeft();
                  }).width(Widget.FILL));
                  body.add(new Button(Component.translatable("model.order.delete"), () -> {
                      featureAction("del", target);
                      selectedSolidId = null;
                      forceRefreshLeft();
                  }).width(Widget.FILL));
                  return;
              }
          }
          if (ModelTools.PLANE.equals(tool)) {              body.add(new Label(Component.translatable("model.tool.plane.hint")).color(0xFFCFD8FF));
              body.add(new com.ovo.sablestopnow.client.gui.lib.Separator());
              body.add(new Label(planeFaceOrigin == null
                      ? Component.translatable("model.plane.step1")
                      : Component.translatable("model.plane.picked",
                              String.format(java.util.Locale.ROOT, "%.2f, %.2f, %.2f",
                                      planeFaceOrigin[0], planeFaceOrigin[1], planeFaceOrigin[2])))
                      .color(0xFFB9C0EE));
              com.ovo.sablestopnow.client.gui.lib.Cmd.declare(CMD_PLANE_DISTANCE);
              final com.ovo.sablestopnow.client.gui.lib.UiField planeDist =
                      com.ovo.sablestopnow.client.gui.lib.UiField.number(
                              "model.plane.distance", CMD_PLANE_DISTANCE, planeDistance,
                              -com.ovo.sablestopnow.sketch.Extrude.MAX_DEPTH,
                              com.ovo.sablestopnow.sketch.Extrude.MAX_DEPTH, 0.25);
              planeDist.format(v -> String.format(java.util.Locale.ROOT, "%.2f", v));
              planeDist.commit(v -> {
                  planeDistance.set(v);
                  updatePlanePreview(v);
              });
              body.add(planeDist);
              if (planeFaceOrigin != null) {
                  body.add(new Button(applyLabel("model.plane.create"), () -> {
                      createPlaneFeature(planeDistance.get());
                      lastLeftSignature = null;
                      refreshLeftPanel();
                  }).width(Widget.FILL));
                  body.add(new Button(Component.translatable("model.plane.cancel"), () -> {
                      planeFaceOrigin = null;
                      planeFaceNormal = null;
                      planeSourceSolid = null;
                      com.ovo.sablestopnow.client.model.FeaturePreview.clear();
                      lastLeftSignature = null;
                      refreshLeftPanel();
                  }).width(Widget.FILL));
              }
              return;
          }
          if (ModelTools.FILLET.equals(tool)) {
              body.add(new Label(Component.translatable("model.tool.fillet.hint")).color(0xFFCFD8FF));
              body.add(new com.ovo.sablestopnow.client.gui.lib.Separator());
              body.add(new Label(filletPicks.isEmpty()
                      ? Component.translatable("model.fillet.step1")
                      : Component.translatable("model.fillet.picked_multi",
                              filletPicks.size(), filletGroupCount()))
                      .color(0xFFB9C0EE));
              com.ovo.sablestopnow.client.gui.lib.Cmd.declare(CMD_FILLET_RADIUS);
              final com.ovo.sablestopnow.client.gui.lib.UiField radiusField =
                      com.ovo.sablestopnow.client.gui.lib.UiField.number(
                              "model.fillet.radius", CMD_FILLET_RADIUS, filletRadius,
                              0.05, 8.0, 0.05);
              radiusField.format(v -> String.format(java.util.Locale.ROOT, "%.2f", v));
              radiusField.commit(v -> {
                  filletRadius.set(v);
                  updateFilletPreview(v);
              });
              body.add(radiusField);
              if (!filletPicks.isEmpty()) {
                  body.add(new Button(applyLabel("model.fillet.create"), () -> {
                      createFilletFeature(filletRadius.get());
                      lastLeftSignature = null;
                      refreshLeftPanel();
                  }).width(Widget.FILL));
                  body.add(new Button(Component.translatable("model.fillet.clear"), () -> {
                      filletPicks.clear();
                      filletPickSeq = 0;
                      filletSourceSolid = null;
                      com.ovo.sablestopnow.client.model.FeaturePreview.clear();
                      lastLeftSignature = null;
                      refreshLeftPanel();
                  }).width(Widget.FILL));
              }
              return;
          }
          if (ModelTools.MIRROR.equals(tool)) {
              body.add(new Label(Component.translatable("model.tool.mirror.hint")).color(0xFFCFD8FF));
              body.add(new com.ovo.sablestopnow.client.gui.lib.Separator());
              body.add(new Label(mirrorPlaneOrigin == null
                      ? Component.translatable("model.mirror.step1")
                      : Component.translatable("model.mirror.plane_ok")).color(0xFFB9C0EE));
                body.add(new Label(Component.translatable("model.mirror.sources",
                        mirrorSources.size())).color(0xFFFFFFFF));
              // ★ **源特征列表**（复选框，绑定 Prop）✓
              //   为什么不用按钮：按钮要在回调里重建面板，而"重建发生在按下/抬起之间"会让
              //   后续点击落到已经销毁的控件上 ⇒ 用户实测"只能选到第一个"✗
              //   复选框自己保存状态、不触发重建 ✓
              body.add(new com.ovo.sablestopnow.client.gui.lib.Separator());
              // ★ 创建按钮放在**列表上方** ✓（源特征多时列表很长，按钮会被挤出可视区 ⇒ 用户"看不到创建键"）
              body.add(new Button(Component.translatable("model.mirror.confirm"), () -> {
                  // ★ 按钮**始终存在** ✓（以前要"选了面 + 勾了源"才出现 ⇒ 用户以为没有按钮）
                  if (mirrorPlaneOrigin == null || mirrorPlaneNormal == null) {
                      ModelMode.prompt("model.mirror.pick_plane");
                  } else if (mirrorSources.isEmpty()) {
                      ModelMode.prompt("model.mirror.no_sources");
                  } else {
                      createMirrorFeature();
                  }
                  forceRefreshLeft();
              }).width(Widget.FILL));
              body.add(new Button(Component.translatable("model.mirror.clear"), () -> {
                  mirrorSources.clear();
                  MIRROR_SRC_PROPS.forEach((k, prop) -> prop.set(false));
                  mirrorPlaneOrigin = null;
                  mirrorPlaneSegs = null;
                  mirrorPlaneNormal = null;
                  com.ovo.sablestopnow.client.model.FeaturePreview.clear();
                  forceRefreshLeft();
              }).width(Widget.FILL));
              body.add(new Label(Component.translatable("model.mirror.pick_sources_hint"))
                      .color(0xFF8FA0D8));
              {
                  final net.minecraft.client.Minecraft mc0 =
                          net.minecraft.client.Minecraft.getInstance();
                  final net.minecraft.world.level.Level lv = mc0 == null ? null : mc0.level;
                  final java.util.UUID b0 = mirrorBody != null ? mirrorBody
                          : ModelMode.selectedModel();
                  if (lv != null && b0 != null) {
                      for (final com.ovo.sablestopnow.sketch.Solid sd
                              : com.ovo.sablestopnow.sketch.SolidClientState.ofBody(lv, b0)) {
                          if (sd.isPlane() || sd.isFillet() || sd.isMirror() || sd.isPattern()) {
                              continue; // 这些不是"材料特征"，镜像它们没有意义 ✓
                          }
                          final com.ovo.sablestopnow.client.gui.lib.Prop<Boolean> prop =
                                  mirrorSourceProp(sd.id());
                          final com.ovo.sablestopnow.client.gui.lib.Checkbox cb =
                                  new com.ovo.sablestopnow.client.gui.lib.Checkbox(prop,
                                          () -> Component.literal(solidShortLabel(sd)));
                          cb.onChange = on -> {
                              if (on) {
                                  if (!mirrorSources.contains(sd.id())) {
                                      mirrorSources.add(sd.id());
                                  }
                              } else {
                                  mirrorSources.remove(sd.id());
                              }
                              mirrorBody = sd.body();
                              updateMirrorPreview();
                          };
                          body.add(cb);
                      }
                  }
              }
              // ★ 已有**基准面特征**可直接作镜像面 ✓
              // ⚠ 必须**只列当前结构自己的基准面**：以前遍历的是整个维度的实体
              //   ⇒ 别的结构上的基准面也会出现在列表里（序号还会重名，出现两个"基准面5"），
              //   点下去把 mirrorBody 改成另一条结构 ⇒ 选了没用（用户实测）。
              {
                  final net.minecraft.client.Minecraft mc0 =
                          net.minecraft.client.Minecraft.getInstance();
                  final net.minecraft.world.level.Level lv = mc0 == null ? null : mc0.level;
                  final java.util.UUID planeBody = mirrorBody != null ? mirrorBody
                          : ModelMode.selectedModel();
                  if (lv != null && planeBody != null) {
                      for (final com.ovo.sablestopnow.sketch.Solid sd
                              : com.ovo.sablestopnow.sketch.SolidClientState
                                      .ofBody(lv, planeBody)) {
                          if (!(sd.shape()
                                  instanceof final com.ovo.sablestopnow.sketch.Solid.Shape.Plane pl)) {
                              continue;
                          }
                          final double[] o = pl.offsetOrigin();
                          // ★ 基准面特征作镜像面：参考高亮画**这块基准面本身**
                          //   （四角 = shape 的方片，再平移到 offsetOrigin）✓
                          final double dxo = o[0] - pl.ox();
                          final double dyo = o[1] - pl.oy();
                          final double dzo = o[2] - pl.oz();
                          final java.util.List<double[]> planeRefSegs =
                                  new java.util.ArrayList<>(4);
                          final java.util.List<org.joml.Vector3d> pcs = pl.corners(1.2);
                          for (int pi = 0; pi < 4; pi++) {
                              final org.joml.Vector3d pa = pcs.get(pi);
                              final org.joml.Vector3d pb = pcs.get((pi + 1) % 4);
                              planeRefSegs.add(new double[] {
                                  pa.x + dxo, pa.y + dyo, pa.z + dzo,
                                  pb.x + dxo, pb.y + dyo, pb.z + dzo});
                          }
                          body.add(new Button(Component.translatable("model.mirror.use_plane",
                                  sd.index()), () -> {
                              mirrorPlaneOrigin = new org.joml.Vector3d(o[0], o[1], o[2]);
                              mirrorPlaneNormal = new org.joml.Vector3d(pl.nx(), pl.ny(), pl.nz())
                                      .normalize();
                              mirrorBody = sd.body();
                              mirrorPlaneSegs = planeRefSegs;
                              updateMirrorPreview();
                              forceRefreshLeft();
                          }).width(Widget.FILL));
                      }
                  }
              }
              if (mirrorPlaneOrigin != null && !mirrorSources.isEmpty()) {
                  body.add(new Button(Component.translatable("model.mirror.confirm"), () -> {
                      createMirrorFeature();
                      lastLeftSignature = null;
                      refreshLeftPanel();
                  }).width(Widget.FILL));
              }
              body.add(new Button(Component.translatable("model.mirror.clear"), () -> {
                  mirrorSources.clear();
                  mirrorPlaneOrigin = null;
                  mirrorPlaneSegs = null;
                  mirrorPlaneNormal = null;
                  com.ovo.sablestopnow.client.model.FeaturePreview.clear();
                  lastLeftSignature = null;
                  refreshLeftPanel();
              }).width(Widget.FILL));
              return;
          }
          if (ModelTools.PATTERN.equals(tool)) {
              body.add(new Label(Component.translatable("model.tool.pattern.hint")).color(0xFFCFD8FF));
              body.add(new com.ovo.sablestopnow.client.gui.lib.Separator());
              body.add(new Label(Component.translatable("model.pattern.state",
                      com.ovo.sablestopnow.client.model.PatternTool.dirCount(),
                      com.ovo.sablestopnow.client.model.PatternTool.sourceCount()))
                      .color(0xFFB9C0EE));

                // ★ 源特征列表也放左栏 ✓（不依赖右栏树的点选）
                body.add(new com.ovo.sablestopnow.client.gui.lib.Separator());
              // ★ 参数与创建按钮放在**列表上方** ✓（同上：列表会把它们挤出可视区）
              com.ovo.sablestopnow.client.gui.lib.Cmd.declare(CMD_PATTERN_N1);
              com.ovo.sablestopnow.client.gui.lib.Cmd.declare(CMD_PATTERN_S1);
              com.ovo.sablestopnow.client.gui.lib.Cmd.declare(CMD_PATTERN_N2);
              com.ovo.sablestopnow.client.gui.lib.Cmd.declare(CMD_PATTERN_S2);
              body.add(com.ovo.sablestopnow.client.gui.lib.UiField.integer(
                      "model.pattern.n1", CMD_PATTERN_N1, patternN1, 1, 64));
              body.add(com.ovo.sablestopnow.client.gui.lib.UiField.number(
                      "model.pattern.s1", CMD_PATTERN_S1, patternS1, -32.0, 32.0, 0.25));
              body.add(com.ovo.sablestopnow.client.gui.lib.UiField.integer(
                      "model.pattern.n2", CMD_PATTERN_N2, patternN2, 1, 64));
              body.add(com.ovo.sablestopnow.client.gui.lib.UiField.number(
                      "model.pattern.s2", CMD_PATTERN_S2, patternS2, -32.0, 32.0, 0.25));
              body.add(new Button(applyLabel("model.pattern.create"), () -> {
                  createPatternFeature((int) (double) patternN1.get(), patternS1.get(),
                          (int) (double) patternN2.get(), patternS2.get());
                  forceRefreshLeft();
              }).width(Widget.FILL));
              body.add(new Button(Component.translatable("model.pattern.clear"), () -> {
                  PatternTool.reset();
                  forceRefreshLeft();
              }).width(Widget.FILL));
                body.add(new Label(Component.translatable("model.pattern.pick_sources_hint"))
                        .color(0xFF8FA0D8));
                {
                    final net.minecraft.client.Minecraft mc0 =
                            net.minecraft.client.Minecraft.getInstance();
                    final net.minecraft.world.level.Level lv = mc0 == null ? null : mc0.level;
                    final java.util.UUID b0 = com.ovo.sablestopnow.client.model.PatternTool
                            .bodyId() != null
                                    ? com.ovo.sablestopnow.client.model.PatternTool.bodyId()
                                    : ModelMode.selectedModel();
                    if (lv != null && b0 != null) {
                        for (final com.ovo.sablestopnow.sketch.Solid sd
                                : com.ovo.sablestopnow.sketch.SolidClientState.ofBody(lv, b0)) {
                            if (sd.isPlane() || sd.isFillet() || sd.isMirror() || sd.isPattern()) {
                                continue;
                            }
                            final com.ovo.sablestopnow.client.gui.lib.Prop<Boolean> prop =
                                    PATTERN_SRC_PROPS.computeIfAbsent(sd.id(), k ->
                                            com.ovo.sablestopnow.client.gui.lib.Prop.of(
                                                    "pattern.src." + k.toString().substring(0, 8),
                                                    com.ovo.sablestopnow.client.model.PatternTool
                                                            .hasSource(k)));
                            final com.ovo.sablestopnow.client.gui.lib.Checkbox cb =
                                    new com.ovo.sablestopnow.client.gui.lib.Checkbox(prop,
                                            () -> Component.literal(solidShortLabel(sd)));
                            cb.onChange = on -> com.ovo.sablestopnow.client.model.PatternTool
                                    .setSource(sd.id(), on, sd.body());
                            body.add(cb);
                        }
                    }
                }
              if (com.ovo.sablestopnow.client.model.PatternTool.dirCount() > 0
                      && com.ovo.sablestopnow.client.model.PatternTool.sourceCount() > 0) {
                  body.add(new Button(applyLabel("model.pattern.create"), () -> {
                      createPatternFeature((int) (double) patternN1.get(),
                              (double) patternS1.get(),
                              (int) (double) patternN2.get(), (double) patternS2.get());
                      lastLeftSignature = null;
                      refreshLeftPanel();
                  }).width(Widget.FILL));
              }
              body.add(new Button(Component.translatable("model.pattern.clear"), () -> {
                  PatternTool.reset();
                  lastLeftSignature = null;
                  refreshLeftPanel();
              }).width(Widget.FILL));
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
                  // ★ 左栏直接列出**已有的基准面特征** ⇒ 点一下就开草图 ✓
                  {
                      final net.minecraft.client.Minecraft mc0 =
                              net.minecraft.client.Minecraft.getInstance();
                      final net.minecraft.world.level.Level lv = mc0 == null ? null : mc0.level;
                      if (lv != null) {
                          for (final com.ovo.sablestopnow.sketch.Solid sd
                                  : com.ovo.sablestopnow.sketch.SolidClientState.in(
                                          lv.dimension().location())) {
                              if (!(sd.shape() instanceof final
                                      com.ovo.sablestopnow.sketch.Solid.Shape.Plane pl)) {
                                  continue;
                              }
                              final double[] o = pl.offsetOrigin();
                              body.add(new Button(Component.translatable(
                                      "model.sketch.use_plane", sd.index()), () -> {
                                  com.ovo.sablestopnow.client.SketchClient.choosePlaneAt(
                                          sd.body(), new org.joml.Vector3d(o[0], o[1], o[2]),
                                          new org.joml.Vector3d(pl.nx(), pl.ny(), pl.nz()));
                                  forceRefreshLeft();
                              }).width(Widget.FILL));
                          }
                      }
                  }
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
                        com.ovo.sablestopnow.sketch.SketchSession.Tool.ARC, session));
                // ★ 需求 ⑤：**贝塞尔**（四次点击 P0 → C1 → P3 → C2 ✓ 两个手柄是辅助线 ✓）
                row.add(sketchToolButton("model.sketch.tool.bezier",
                        com.ovo.sablestopnow.sketch.SketchSession.Tool.BEZIER, session));
                  // ★ item 3：**文字**图元（两次点击拉矩形 ⇒ 弹出多行编辑框）✓
                  row.add(sketchToolButton("model.sketch.tool.text",
                          com.ovo.sablestopnow.sketch.SketchSession.Tool.TEXT, session));
                // ★ **辅助线** ✓：按用户要求放在**图元按键**里 ✓ 且是**直接绘制**模式 ✓（不是转换 ✗）
                //   开启后用直线工具画出来的图元**直接就是辅助线** ✓（虚线外观 ✓ 不计入轮廓/拉伸 ✓）
                final var constructionTool = new com.ovo.sablestopnow.client.gui.lib.Button(
                        Component.translatable("model.sketch.construction"), () -> {
                            session.setConstructionDraw(!session.constructionDraw());
                            com.ovo.sablestopnow.client.model.ModelMode.prompt(
                                    session.constructionDraw()
                                            ? "model.sketch.construction_draw_on"
                                            : "model.sketch.construction_draw_off");
                            refreshLeftPanel();
                        });
                constructionTool.prefW = 26;
                constructionTool.toggled = session.constructionDraw();
                row.add(constructionTool);
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
                        SMART_CAND_BTNS.clear(); // ★ 面板重建 ⇒ 候选按钮表也重建 ✓（防悬空引用 ✗）
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
                                SMART_CAND_BTNS.add(cb); // ★ 让"随指针实时联动"能轻量同步它 ✓ 不重建整栏 ✓
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
                                    textureConnected.set(solid.appearance().connected());
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
            // ★ 判据统一走 leftEditing()：贴图面板、特征编辑态也算“在编辑”，
            //   否则面板在显示、按钮却是灰的（用户实测）。
            completeButton.enabled = leftEditing();
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

        // ① 贴图编辑区：**完成 = 应用待定参数并关闭** ✓
        //   （以前这里没有这个分支：面板在显示、按钮却是灰的，只能点面板内部的“关闭”）
        if (textureSolidId != null) {
            closeTextureEditor(true);
            ModelMode.prompt("model.texture.done");
            return;
        }

        // ② 正在编辑**已有特征**：完成 = 应用并关闭（与面板主按钮同一套动作）✓
        if (editingSolidId != null) {
            if (!applyLeftEditor()) {
                return; // 参数不完整/退化 ⇒ 提示已经给出，保持编辑态
            }
            ModelMode.setTool(ModelTools.NONE);
            ModelMode.clearPicks();
            afterEdit();
            return;
        }

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
        // ★ 取消也必须关掉贴图编辑区与特征编辑态 ——
        //   以前这两项不在取消范围内：贴图面板点“取消”之后仍然挂着（用户实测）。
        textureSolidId = null;
        textureBlockQuery.set("");
        textureSearchApplied.set("");
        editingSolidId = null;
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
        // ★ 进任何工具都先退出“编辑已有特征”的上下文：
        //   否则在编辑拉伸A时点“圆角”，新建的圆角会被当成“更新拉伸A”（灾难性串味）。
        editingSolidId = null;
        ModelCouplingUi.clearSelection();
        ModelForceUi.select(null);
        MateClientState.setHighlightedMate(null);
        if (ModelTools.MATE.equals(toolId)) {
            // 进入配合流程 = 打开创建页：草稿复位到默认类型
            MateEditUi.beginCreate(null);
        }
          // ★ 关键：进这四个特征工具前先退出草图模式，否则 onLeftPress 会先走"草图分支"
          //   工具分支永远轮不到 ⇒ "点棱/点面毫无反应"（用户实测）✓
          if (ModelTools.PLANE.equals(toolId) || ModelTools.FILLET.equals(toolId)
                  || ModelTools.MIRROR.equals(toolId) || ModelTools.PATTERN.equals(toolId)) {
              // ★ 进工具即重置上次的拾取状态（用户实测："第二次进入就已经有创建按钮"）
              planeFaceOrigin = null;
              planeFaceNormal = null;
              planeSolidId = null;
              filletPicks.clear();
              filletPickSeq = 0;
              filletSolidId = null;
              filletSourceSolid = null;
              mirrorSources.clear();
              mirrorPlaneOrigin = null;
              mirrorPlaneSegs = null;
              mirrorPlaneNormal = null;
              com.ovo.sablestopnow.client.model.FeaturePreview.clear();
              // ★ 默认把该体的**全部材料特征**勾上 ⇒ "创建镜像"按钮一定出现 ✓
              //   （之前源列表为空时按钮根本不显示，用户以为"没有创建按键"✗）
              final net.minecraft.client.Minecraft mc0 =
                      net.minecraft.client.Minecraft.getInstance();
              final net.minecraft.world.level.Level lv0 = mc0 == null ? null : mc0.level;
              final java.util.UUID b0 = ModelMode.selectedModel();
              if (lv0 != null && b0 != null && ModelTools.MIRROR.equals(toolId)) {
                  mirrorBody = b0;
                  for (final com.ovo.sablestopnow.sketch.Solid sd
                          : com.ovo.sablestopnow.sketch.SolidClientState.ofBody(lv0, b0)) {
                      // ★ 圆角也可以被镜像（与切割同等）✓
                      if (sd.isPlane() || sd.isMirror() || sd.isPattern()) {
                          continue;
                      }
                      mirrorSources.add(sd.id());
                      mirrorSourceProp(sd.id()).set(true);
                  }
              }
              if (lv0 != null && b0 != null && ModelTools.PATTERN.equals(toolId)) {
                  PatternTool.prefill(b0, com.ovo.sablestopnow.sketch.SolidClientState.ofBody(lv0, b0));
              }
              if (com.ovo.sablestopnow.client.SketchClient.active()) {
                  com.ovo.sablestopnow.client.SketchClient.finish();
                  com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                          "[tool] exited sketch mode before entering {}", toolId);
              }
          }
          // ★ 同时清掉贴图编辑：它会让左栏永远停在贴图页（工具面板就看不见了）
          textureSolidId = null;
          textureBlockQuery.set("");
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
    /**
     * 是否真的点在一个**控件**上（而不是图层根容器）✓
     *
     * <p>为什么需要它：模型视图的几个图层（top/tree/left/hud）的**根容器铺满全屏且 consume=true**，
     * 于是"点在模型上空地"也会被 `Ui.handleMouse` 吞掉 ⇒ `ModelInput` 永远收不到点击
     *（实测：`[ui-diag] ... model.top=root(consume=true) ...`，且完全没有 `[mouse] leftPress`）✗
     * 判据改成"命中到非根控件"才让 UI 先处理 ✓
     */
    public static boolean pointerOverWidget() {
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
