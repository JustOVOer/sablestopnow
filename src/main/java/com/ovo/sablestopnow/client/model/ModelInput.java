package com.ovo.sablestopnow.client.model;

import com.ovo.sablestopnow.client.MateTargeting;
import com.ovo.sablestopnow.client.StaffKeyMappings;
import com.ovo.sablestopnow.client.gui.lib.Ui;
import com.ovo.sablestopnow.mate.MateRef;
import com.ovo.sablestopnow.network.MateNetworking;
import dev.ryanhcode.sable.sublevel.SubLevel;
import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.lwjgl.glfw.GLFW;

import java.util.UUID;

/**
 * 模型模式的鼠标：SolidWorks 那套「相机操作 + 选择 + 拖动」。
 *
 * <ul>
 *   <li><b>中键拖动</b>：相机绕模型旋转；</li>
 *   <li><b>Ctrl + 中键</b>：平移；</li>
 *   <li><b>Shift + 中键</b>：缩放（上下拖）；</li>
 *   <li><b>滚轮</b>：缩放，以光标附近为中心；</li>
 *   <li><b>左键</b>：选择点/棱/面（有功能在跑时＝加入选择），<b>Shift + 左键</b>撤销上一个；</li>
 *   <li><b>左键拖动</b>（没在跑功能时）：把模型拖到新位置 —— 解锁 → 缓慢移动 → 锁定；</li>
 *   <li><b>右键</b>：弹出右键菜单。</li>
 * </ul>
 *
 * <p>按键事件由 {@code MouseHandler} 的 mixin 转发进来（覆盖层不在屏幕栈上），
 * 拖动这类「持续量」则在每帧 tick 里轮询 —— 自由光标下没有可用的相对位移事件。
 */
public final class ModelInput {

    private static final int BUTTON_LEFT = 0;
    private static final int BUTTON_RIGHT = 1;
    private static final int BUTTON_MIDDLE = 2;
    /** 超过这么多像素才算「拖动」而不是「点击」。 */
    private static final double DRAG_THRESHOLD = 3.0;
    /** 拖动时「一个像素 = 多少格」，按相机距离缩放，手感与缩放级别无关。 */
    private static final double DRAG_SCALE = 0.0035;

    private static boolean draggingCamera;
    private static double lastX;
    private static double lastY;
    private static boolean haveLast;

    /** 按下但还没越过拖动阈值的候选模型。 */
    @Nullable
    private static UUID pendingDragModel;
    /** 正在被拖动的模型。 */
    @Nullable
    private static UUID draggingModel;
    /** 拖动累积像素（用于阈值判定）。 */
    private static double accumulated;
    /** 客户端的拖动目标点（绝对世界坐标，每帧发给服务端）。 */
    private static final Vector3d dragTarget = new Vector3d();

    private ModelInput() {
    }

    /** Tab：在「自由光标」与「锁定准星」之间切换（锁定后中键轨道仍然可用）。 */
    public static void toggleCursor() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.mouseHandler == null) {
            return;
        }
        if (mc.mouseHandler.isMouseGrabbed()) {
            ModelMode.setFreeCursor(true);
            mc.mouseHandler.releaseMouse();
            ModelMode.prompt("model.prompt.cursor_free");
        } else {
            ModelMode.setFreeCursor(false);
            mc.mouseHandler.grabMouse();
            ModelMode.prompt("model.prompt.cursor_locked");
        }
    }

    // ============ 每帧 ============

    public static void tick(final Minecraft mc) {
        if (mc.getWindow() == null || mc.mouseHandler == null) {
            return;
        }
        final long window = mc.getWindow().getWindow();
        tickSketchHotkeys(window);
        com.ovo.sablestopnow.client.model.ModelUi.tickFeaturePreview(); // item 5 线框预览
        com.ovo.sablestopnow.client.model.ModelUi.syncFeatureHover();     // item 6 树行悬停高亮
        final double x = mc.mouseHandler.xpos();
        final double y = mc.mouseHandler.ypos();
        if (!haveLast) {
            lastX = x;
            lastY = y;
            haveLast = true;
            return;
        }
        final double dx = x - lastX;
        final double dy = y - lastY;
        lastX = x;
        lastY = y;

        tickCameraDrag(window, mc, dx, dy);
        tickKeyboardMove(window, mc);
        tickModelDrag(window, mc, dx, dy);
        tickSketchRightClick(window, mc);
        tickDimLabelDrag(window, mc);
    }

    /** 正在拖动的尺寸标签（约束下标 ✓ -1 = 没在拖 ✓）。 */
    private static int draggingDimLabel = -1;

    /** 拖动尺寸标签：按住期间把指针的平面 uv 写成标签覆盖位置 ✓（松手结束 ✓）。 */
    private static void tickDimLabelDrag(final long window, final Minecraft mc) {
        final boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT)
                == GLFW.GLFW_PRESS;
        if (!down) {
            draggingDimLabel = -1;
            return;
        }
        if (draggingDimLabel < 0 || !com.ovo.sablestopnow.client.SketchClient.active()) {
            return;
        }
        final double[] uv = com.ovo.sablestopnow.client.SketchClient.cursorUv();
        if (uv != null) {
            com.ovo.sablestopnow.client.SketchHud.setLabelOverride(
                    draggingDimLabel, uv[0], uv[1]);
        }
    }

    /** 右键是否按着（用来取**上升沿** ⇒ 按住不会连发 ✓）。 */
    private static boolean sketchRightDown;

    /**
     * 草图里的**右键**：循环锁定智能尺寸的对齐方式 ✓（上升沿触发 ✓）。
     *
     * <p>为什么在这里轮询而不是收事件 ✗：本类其余输入（左键拖、中键转、WASD）都是轮询 GLFW ✓
     * ⇒ 跟着同一套写法最不容易漏状态 ✓（混用两种风格迟早出现"按下没抬起"✗）。
     */
    private static void tickSketchRightClick(final long window, final Minecraft mc) {
        final boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                == GLFW.GLFW_PRESS;
        if (down && !sketchRightDown) {
            sketchRightDown = true;
            final String toolNow = ModelMode.tool();
        final boolean featureTool = ModelTools.PLANE.equals(toolNow)
                || ModelTools.FILLET.equals(toolNow) || ModelTools.MIRROR.equals(toolNow)
                || ModelTools.PATTERN.equals(toolNow);
        if (!featureTool && com.ovo.sablestopnow.client.SketchClient.active()) {

                com.ovo.sablestopnow.client.SketchClient.rightClick();
            }
        } else if (!down) {
            sketchRightDown = false;
        }
    }

    /** WASD/QE 的移动速度系数（格/秒，再乘以当前距离）。 */
    private static final double KEY_MOVE_SPEED = 1.1;

    /**
     * WASD + 方向键上下 平移相机。
     *
     * <p>三点约定：
     * <ul>
     *   <li><b>水平移动用视线的水平分量</b>，不吃俯仰角 —— 俯视时按 W 应该「往前」而不是「往下」；</li>
     *   <li><b>无惯性</b>：目标点与目标值一起改（{@link ModelCamera#offsetTargetImmediate}），
     *       松手即停，不会滑一段；</li>
     *   <li><b>幅度随缩放变化</b>：速度正比于当前距离，拉近之后自然变慢，拉远之后自然变快。</li>
     * </ul>
     *
     * <p>⚠ 上下用<b>方向键</b>而不是 Q/E：Q 是原版的「丢弃物品」，按下去会把手里的东西扔了
     * （用户实测反馈「上下改用方向键上下防止冲突」）。六个键都是可改键的 KeyMapping。
     *
     * <p>有输入框在编辑时不响应（否则往频道里打 "wasd" 会把镜头开走）。
     */
    private static void tickKeyboardMove(final long window, final Minecraft mc) {
        if (mc.screen != null || ModelUi.focusedTextField()) {
            return;
        }
        double forward = 0.0;
        double strafe = 0.0;
        double lift = 0.0;
        if (StaffKeyMappings.MODEL_FORWARD.isDown()) {
            forward += 1.0;
        }
        if (StaffKeyMappings.MODEL_BACK.isDown()) {
            forward -= 1.0;
        }
        if (StaffKeyMappings.MODEL_RIGHT.isDown()) {
            strafe += 1.0;
        }
        if (StaffKeyMappings.MODEL_LEFT.isDown()) {
            strafe -= 1.0;
        }
        if (StaffKeyMappings.MODEL_UP.isDown()) {
            lift += 1.0;
        }
        if (StaffKeyMappings.MODEL_DOWN.isDown()) {
            lift -= 1.0;
        }
        if (forward == 0.0 && strafe == 0.0 && lift == 0.0) {
            return;
        }
        final Vec3 look = ModelCamera.lookVector();
        final Vec3 flat = new Vec3(look.x, 0.0, look.z);
        // 正上/正下方俯视时水平分量退化：这时「前后」没有意义，改用世界轴兜底
        final Vec3 ahead = flat.lengthSqr() < 1.0e-6 ? new Vec3(0, 0, 1) : flat.normalize();
        final Vec3 right = new Vec3(-ahead.z, 0.0, ahead.x).normalize();
        final double step = KEY_MOVE_SPEED * Math.max(2.0, ModelCamera.distance())
                * Math.min(0.1, com.ovo.sablestopnow.client.gui.lib.Ui.frameDelta());
        ModelCamera.offsetTargetImmediate(new Vec3(
                ahead.x * forward * step + right.x * strafe * step,
                lift * step,
                ahead.z * forward * step + right.z * strafe * step));
    }

    /**
     * 草图热键（item 12）：**Ctrl+Z 撤销**、**Ctrl+Y / Shift+Ctrl+Z 重做** ✓
     *
     * <p>用"上升沿"触发（按住不会连发）✓；只在草图编辑中生效 ✓。
     */
    private static void tickSketchHotkeys(final long window) {
        final boolean ctrl = isDown(window, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL);
        final boolean shift = isDown(window, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT);
        final boolean z = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_Z) == GLFW.GLFW_PRESS;
        final boolean y = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_Y) == GLFW.GLFW_PRESS;
        final boolean undoPressed = ctrl && z && !shift;
        final boolean redoPressed = ctrl && (y || (z && shift));
        if (undoPressed && !lastUndoDown && com.ovo.sablestopnow.client.SketchClient.active()) {
            com.ovo.sablestopnow.client.SketchClient.undo();
        }
        if (redoPressed && !lastRedoDown && com.ovo.sablestopnow.client.SketchClient.active()) {
            com.ovo.sablestopnow.client.SketchClient.redo();
        }
        lastUndoDown = undoPressed;
        lastRedoDown = redoPressed;
    }

    private static boolean lastUndoDown;
    private static boolean lastRedoDown;

    private static void tickCameraDrag(final long window, final Minecraft mc, final double dx, final double dy) {
        final boolean middle = GLFW.glfwGetMouseButton(window, BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
        if (!middle) {
            draggingCamera = false;
            return;
        }
        if (dx == 0.0 && dy == 0.0) {
            return;
        }
        final boolean ctrl = isDown(window, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL);
        final boolean shift = isDown(window, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT);
        draggingCamera = true;
        if (ctrl) {
            ModelCamera.pan(dx, dy);
        } else if (shift) {
            ModelCamera.zoom(-dy * 0.05, false, mc);
        } else {
            ModelCamera.orbit(dx, dy);
        }
    }

    /**
     * 左键拖动模型。
     *
     * <p>发的是<b>绝对目标点</b>：客户端只负责把「这一帧拖了多少像素」换算成世界位移并累加，
     * 服务端负责限速搬运 —— 与配合的搬运（{@code MateRegistry.startSnap}）同一条纪律，
     * 所以拖动是缓慢移动而不是瞬移，不会灌出大量动量。
     */
    private static void tickModelDrag(final long window, final Minecraft mc, final double dx, final double dy) {
        final boolean left = GLFW.glfwGetMouseButton(window, BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (!left || ModelUi.pointerOverPanel()) {
            endModelDrag();
            pendingDragModel = null;
            accumulated = 0.0;
            return;
        }
        if (draggingModel == null) {
            if (pendingDragModel == null) {
                return;
            }
            accumulated += Math.abs(dx) + Math.abs(dy);
            if (accumulated < DRAG_THRESHOLD) {
                return;
            }
            startModelDrag(pendingDragModel);
            pendingDragModel = null;
        }
        if (dx == 0.0 && dy == 0.0) {
            return;
        }
        final Vec3 look = ModelCamera.lookVector();
        final Vec3 right = new Vec3(-look.z, 0.0, look.x).normalize();
        final Vec3 up = right.cross(look).normalize();
        final double scale = DRAG_SCALE * Math.max(2.0, ModelCamera.distance());
        dragTarget.add(
                right.x * dx * scale + up.x * -dy * scale,
                right.y * dx * scale + up.y * -dy * scale,
                right.z * dx * scale + up.z * -dy * scale);
        VeilPacketManager.server().sendPacket(new MateNetworking.ModelMovePayload(
                draggingModel, dragTarget.x, dragTarget.y, dragTarget.z, false));
    }

    private static void startModelDrag(final UUID body) {
        final SubLevel sub = MateTargeting.byId(body);
        if (sub == null) {
            return;
        }
        final var pos = sub.logicalPose().position();
        dragTarget.set(pos.x(), pos.y(), pos.z());
        draggingModel = body;
        ModelMode.prompt("model.prompt.dragging");
    }

    private static void endModelDrag() {
        if (draggingModel == null) {
            return;
        }
        VeilPacketManager.server().sendPacket(new MateNetworking.ModelMovePayload(
                draggingModel, dragTarget.x, dragTarget.y, dragTarget.z, true));
        draggingModel = null;
    }

    public static boolean isDraggingModel() {
        return draggingModel != null;
    }

    public static boolean isDraggingCamera() {
        return draggingCamera;
    }

    private static boolean isDown(final long window, final int keyA, final int keyB) {
        return GLFW.glfwGetKey(window, keyA) == GLFW.GLFW_PRESS || GLFW.glfwGetKey(window, keyB) == GLFW.GLFW_PRESS;
    }

    // ============ 事件 ============

    /** @return true 表示吞掉这次鼠标按键 */
    public static boolean handleMouse(final int button, final int action, final int modifiers) {
        if (!ModelMode.isOn()) {
            return false;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        if (action == GLFW.GLFW_RELEASE) {
            if (button == BUTTON_LEFT) {
                endModelDrag();
                pendingDragModel = null;
                accumulated = 0.0;
            }
            return button == BUTTON_LEFT || button == BUTTON_MIDDLE;
        }
        if (action != GLFW.GLFW_PRESS) {
            return true;
        }
        // 面板上的点击由 GUI 库先处理；库没命中却点在面板矩形里，也不要穿透到世界
        if (button == BUTTON_LEFT && ModelUi.pointerOverPanel()) {
            return true;
        }

        final boolean shift = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        switch (button) {
            case BUTTON_LEFT -> {
                if (shift) {
                    ModelMode.undoPick();
                    return true;
                }
                onLeftPress(mc);
                return true;
            }
            case BUTTON_RIGHT -> {
                ModelUi.openContextMenu(mc, Ui.mouseX(), Ui.mouseY());
                return true;
            }
            case BUTTON_MIDDLE -> {
                draggingCamera = true;
                return true;
            }
            default -> {
                return true;
            }
        }
    }

    /** @return true 表示吞掉这次滚轮 */
    public static boolean handleScroll(final double delta) {
        if (!ModelMode.isOn()) {
            return false;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        if (ModelUi.pointerOverPanel()) {
            return false;
        }
        ModelCamera.zoom(delta, true, mc);
        return true;
    }

    /** @return true 表示吞掉这次按键 */
    public static boolean handleKey(final int keyCode, final int scanCode, final int action, final int modifiers) {
        if (!ModelMode.isOn() || action != GLFW.GLFW_PRESS) {
            return false;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || ModelUi.focusedTextField()) {
            return false;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_SPACE -> {
                ModelUi.openViewPopup();
                return true;
            }
            case GLFW.GLFW_KEY_DELETE -> {
                // 草图里选中了约束 ⇒ Delete 删掉它 ✓（SolidWorks 一致 ✓）
                if (com.ovo.sablestopnow.client.SketchClient.active()
                        && com.ovo.sablestopnow.client.SketchClient.mode().session() != null
                        && com.ovo.sablestopnow.client.SketchClient.mode().session()
                                .deleteSelectedConstraint()) {
                    com.ovo.sablestopnow.client.SketchClient.mode().solve();
                    ModelMode.prompt("model.sketch.constraint_deleted");
                    return true;
                }
                ModelUi.deleteSelectedMate();
                return true;
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (ModelUi.hasPopup()) {
                    ModelUi.closePopup();
                } else if (!ModelTools.NONE.equals(ModelMode.tool())) {
                    ModelUi.cancelTool();
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ============ 选择 ============

    // ================= 双击已放置尺寸 ⇒ 改值 ✓ ==========================================

    private static long lastDimClickMs;
    private static double lastDimClickX = Double.NaN;
    private static double lastDimClickY = Double.NaN;

    /**
     * 双击**已放置尺寸** ⇒ 打开改值浮层 ✓。
     *
     * <p>命中走 {@code SketchHud.pickLabel(...)} ✓ —— 它登记的屏幕位置正是**画标签时**算出来的 ✓
     * ⇒ "看到的"与"点得到的"必然一致 ✓（本项目已经栽过好几次"两处各算一次"的坑 ✓）。
     *
     * <p>从动/参考尺寸**不可改** ✗（SolidWorks 里参考尺寸是只读的 ✓ 灰 + 括号就是这个意思 ✓）。
     */
    private static boolean tryDimensionEdit(final Minecraft mc) {
        final double mx = dimGuiX(mc);
        final double my = dimGuiY(mc);
        final long now = System.currentTimeMillis();
        final boolean doubleClick = now - lastDimClickMs <= 250L
                && Math.abs(mx - lastDimClickX) <= 4.0
                && Math.abs(my - lastDimClickY) <= 4.0;
        lastDimClickMs = now;
        lastDimClickX = mx;
        lastDimClickY = my;
        if (!doubleClick) {
            return false;
        }
        final var hit = com.ovo.sablestopnow.client.SketchHud.pickLabel(mx, my, 14.0);
        if (hit == null || hit.constraintIndex() < 0 || !hit.driving()) {
            return false; // 参考尺寸只读 ✓
        }
        final var session = com.ovo.sablestopnow.client.SketchClient.mode().session();
        final var work = session == null ? null : session.working();
        if (work == null) {
            return false;
        }
        for (final var d : com.ovo.sablestopnow.sketch.SketchMeasure.existingDimensions(work)) {
            if (d.constraintIndex() == hit.constraintIndex()) {
                com.ovo.sablestopnow.client.SketchHud.openEdit(
                        hit.constraintIndex(), d.labelKey(), d.value());
                lastDimClickMs = 0L; // 三连击不再重复开 ✓
                return true;
            }
        }
        return false;
    }

    private static double dimGuiX(final Minecraft mc) {
        return mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth()
                / Math.max(1, mc.getWindow().getWidth());
    }

    private static double dimGuiY(final Minecraft mc) {
        return mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight()
                / Math.max(1, mc.getWindow().getHeight());
    }

    private static void onLeftPress(final Minecraft mc) {
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[mouse] leftPress tool={} hover={} sketchActive={} editing={}",
                ModelMode.tool(),
                com.ovo.sablestopnow.client.model.FeatureHover.get() == null ? "null"
                        : com.ovo.sablestopnow.client.model.FeatureHover.get().kind(),
                com.ovo.sablestopnow.client.SketchClient.active(),
                com.ovo.sablestopnow.client.SketchClient.editing());
        // ⚠ 草图模式必须**最先**处理：草图里点的是基准面/草图平面，不是模型表面，
        // 所以不能走下面那套「先要求 hover 到某个面」的拾取。放在最前面还有一个原因 ——
        // 在草图环境里点到空处也不该顺手把视角转了或把选择清了。
        if (com.ovo.sablestopnow.client.SketchClient.active()) {
            // ★ 双击**已放置尺寸** ⇒ 改值浮层 ✓（SolidWorks：双击尺寸数字弹框改值 ⇒ 几何实时驱动 ✓）
            if (tryDimensionEdit(mc)) {
                return;
            }            // ★ 点在**约束小图标**上 ⇒ 选中该约束 ✓（再按 Delete 删掉 ✓ SolidWorks 的做法 ✓）
            if (com.ovo.sablestopnow.client.SketchClient.mode().session() != null) {
                final var g = com.ovo.sablestopnow.client.SketchHud.pickGlyph(
                        dimGuiX(mc), dimGuiY(mc), 10.0);
                if (g != null) {
                    com.ovo.sablestopnow.client.SketchClient.mode().session()
                            .selectConstraint(g.constraintIndex());
                    ModelMode.prompt("model.sketch.constraint_selected");
                    return; // 消费掉 ✓ 不要顺手清选择/放置 ✗
                }
                com.ovo.sablestopnow.client.SketchClient.mode().session().selectConstraint(-1);
            }            // ★ 按在**已有尺寸标签**上 ⇒ 这次按下是"拖标签"✓ 不是"放置新尺寸"✗
            // ★ 需求 2（用户实测）："让所有尺寸可以拖动改变在草图上的显示位置" ✗
            //   原来**只在智能尺寸工具下**才允许抓标签 ✗ ⇒ 平时用"选择"模式根本拖不动 ✓
            //   ⇒ 放开：**选择 / 智能尺寸 两种工具**都能抓标签拖动 ✓
            if (com.ovo.sablestopnow.client.SketchClient.mode().session() != null
                    && (com.ovo.sablestopnow.client.SketchClient.mode().session().tool()
                            == com.ovo.sablestopnow.sketch.SketchSession.Tool.DIMENSION
                            || com.ovo.sablestopnow.client.SketchClient.mode().session().tool()
                            == com.ovo.sablestopnow.sketch.SketchSession.Tool.SELECT)) {
                final var grab = com.ovo.sablestopnow.client.SketchHud.pickLabel(
                        dimGuiX(mc), dimGuiY(mc), 14.0);
                if (grab != null && grab.constraintIndex() >= 0 && grab.driving()) {
                    draggingDimLabel = grab.constraintIndex();
                    return; // 交给 tick 里的拖动处理 ✓ 不放置新尺寸 ✓
                }
            }
            // ⚠⚠ 顺序很关键：**先试方块面，再试基准板**。
            //
            // 反过来（先试板）的话，面这条路**永远轮不到** —— 三块基准板是按结构包围盒
            // 生成的，正好把结构整个包在中间；鼠标指向结构时几乎必然先命中某块板。
            // 实测症状就是"只能选到三个基准面，选不到模型上的面"。
            // SolidWorks 也是这个顺序：指到面上高亮面，指到空白处才显示基准面。
            // item 10: pattern tool - click edges to add directions
            if (com.ovo.sablestopnow.client.model.ModelTools.PATTERN.equals(ModelMode.tool())) {
                  com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                          "[pick] tool=pattern featureHover={} boxHover={}",
                          com.ovo.sablestopnow.client.model.FeatureHover.get() == null
                                  ? "null" : com.ovo.sablestopnow.client.model.FeatureHover.get().kind(),
                          ModelMode.hoveredRef() == null ? "null"
                                  : ModelMode.hoveredRef().describe());
                final com.ovo.sablestopnow.client.model.FeatureHover.Hit pf2 =
                        com.ovo.sablestopnow.client.model.ModelUi.pickOnDemand();
                if (pf2 != null) {
                    com.ovo.sablestopnow.client.model.PatternTool.pickEdge(pf2);
                } else {
                    ModelMode.prompt("model.pattern.pick_edge");
                }
                return;
            }
            // item 9: mirror tool - click a face to set / confirm the mirror plane
            if (com.ovo.sablestopnow.client.model.ModelTools.MIRROR.equals(ModelMode.tool())) {
                  com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                          "[pick] tool=mirror featureHover={} boxHover={}",
                          com.ovo.sablestopnow.client.model.FeatureHover.get() == null
                                  ? "null" : com.ovo.sablestopnow.client.model.FeatureHover.get().kind(),
                          ModelMode.hoveredRef() == null ? "null"
                                  : ModelMode.hoveredRef().describe());
                final com.ovo.sablestopnow.client.model.FeatureHover.Hit mf =
                        com.ovo.sablestopnow.client.model.ModelUi.pickOnDemand();
                if (mf != null) {
                    com.ovo.sablestopnow.client.model.ModelUi.mirrorPickPlane(mf);
                } else {
                    ModelMode.prompt("model.mirror.pick_plane");
                }
                return;
            }
            // ★ item 8：**圆角工具**下点棱 ⇒ 记录棱并弹"半径"输入框 ✓
            if (com.ovo.sablestopnow.client.model.ModelTools.FILLET.equals(ModelMode.tool())) {
                  com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                          "[pick] tool=fillet featureHover={} boxHover={}",
                          com.ovo.sablestopnow.client.model.FeatureHover.get() == null
                                  ? "null" : com.ovo.sablestopnow.client.model.FeatureHover.get().kind(),
                          ModelMode.hoveredRef() == null ? "null"
                                  : ModelMode.hoveredRef().describe());
                final com.ovo.sablestopnow.client.model.FeatureHover.Hit hf =
                        com.ovo.sablestopnow.client.model.ModelUi.pickOnDemand();
                if (hf != null) {
                    com.ovo.sablestopnow.client.model.ModelUi.filletPickEdge(hf);
                } else {
                    ModelMode.prompt("model.fillet.pick_edge");
                }
                return;
            }
            // ★ item 7：**基准面工具**下点面 ⇒ 记录面并弹"距离"输入框 ✓
            if (com.ovo.sablestopnow.client.model.ModelTools.PLANE.equals(ModelMode.tool())) {
                  com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                          "[pick] tool=plane featureHover={} boxHover={}",
                          com.ovo.sablestopnow.client.model.FeatureHover.get() == null
                                  ? "null" : com.ovo.sablestopnow.client.model.FeatureHover.get().kind(),
                          ModelMode.hoveredRef() == null ? "null"
                                  : ModelMode.hoveredRef().describe());
                final com.ovo.sablestopnow.client.model.FeatureHover.Hit pf =
                        com.ovo.sablestopnow.client.model.ModelUi.pickOnDemand();
                if (pf != null) {
                    com.ovo.sablestopnow.client.model.ModelUi.planePickFace(pf);
                } else {
                    ModelMode.prompt("model.plane.pick_face");
                }
                return;
            }
            // ★ 需求：**以模型的一个面作草图基准面** ✓ —— 优先级最高
            //   （模型面 > 方块面 > 基准板 ✓ 与"面优先于板"的既有纪律一致）
            final com.ovo.sablestopnow.client.model.FeatureHover.Hit ff =
                    com.ovo.sablestopnow.client.model.ModelUi.pickOnDemand();
            if (ff != null
                    && ff.kind() == com.ovo.sablestopnow.client.model.FeatureHover.Kind.FACE
                    && com.ovo.sablestopnow.client.SketchClient.chooseModelFace(ff)) {
                ModelUi.refreshLeftPanel();
                return;
            }
            final MateTargeting.BoxHit face = ModelMode.hovered();
            if (face != null && com.ovo.sablestopnow.client.SketchClient.chooseFace(face)) {
                ModelUi.refreshLeftPanel();
                return;
            }
            if (com.ovo.sablestopnow.client.SketchClient.click(mc)) {
                // 选中基准面之后必须重建左栏：状态已从「选面」变成「编辑中」，
                // 不刷新的话左栏还停在"点击一个基准面开始画草图"（实测反馈过）。
                ModelUi.refreshLeftPanel();
            }
            return;
        }
        final MateTargeting.BoxHit hit = ModelMode.hovered();
        final MateRef ref = ModelMode.hoveredRef();
        ModelMode.diagClick(hit == null ? "miss (hovered=null)" : "hovered=" + hit.body().getUniqueId()
                + " ref=" + (ref == null ? "null" : ref.describe()) + " tool=" + ModelMode.tool());
        if (hit == null || ref == null) {
            // 点空白：清掉选择
            ModelMode.selectModel(null);
            ModelMode.clearPicks();
            ModelUi.refreshLeftPanel();
            return;
        }
        ModelMode.selectModel(hit.body().getUniqueId());
        if (ModelTools.NORMAL_TO.equals(ModelMode.tool())) {
            // 正视此面是「一点即生效」的功能，不需要点完成
            ModelUi.normalTo(ref);
            ModelMode.setTool(ModelTools.NONE);
            ModelUi.refreshLeftPanel();
            return;
        }
        if (ModelTools.FORCE.equals(ModelMode.tool())) {            // 施力点放置：点击处即受力点，力方向 = 命中面的法线（服务端按同一套几何建点）
            final net.minecraft.world.phys.Vec3 base = hit.baseHit();
            final org.joml.Vector3d local = new org.joml.Vector3d(base.x, base.y, base.z);
            // enabled=false：新建的点必须是「关着」的。玩家把频道/力量配好、自己勾「开启」才生效，
            // 否则「频道上已经有信号」时一落点模型就飞走（用户实测反馈）。
            com.ovo.sablestopnow.network.ForceNetworking.sendCreate(hit.body().getUniqueId(),
                    hit.baseBlock(), hit.baseFace(), local,
                    com.ovo.sablestopnow.force.ForcePoint.DEFAULT_MAGNITUDE, false);
            ModelForceUi.onPlaced();
            ModelMode.prompt("model.prompt.force_placed");
            return;
        }
        if (ModelTools.NONE.equals(ModelMode.tool())) {
            // 没在跑功能：这次按下可能是「拖动模型」的开始
            pendingDragModel = hit.body().getUniqueId();
            accumulated = 0.0;
            ModelUi.refreshLeftPanel();
            return;
        }
        final int max = ModelTools.maxRefs(ModelMode.tool());
        if (max > 0 && ModelMode.picked().size() >= max) {
            ModelMode.prompt("model.prompt.refs_full", max);
            return;
        }
        // SolidWorks 式的确认：候选标记画在屏幕上，光标必须**停在标记上**再点击才算选中。
        // 标记本身就是可点范围的可视化（点=小方块、棱=线段、面=轮廓），
        // 所以「看着在哪里就点哪里」成立，不需要再猜三维判定区。
        if (!ModelPicks.cursorOnMarker(mc)) {
            ModelMode.prompt("model.prompt.aim_marker");
            return;
        }
        ModelMode.pick(ref);
        ModelUi.refreshLeftPanel();
    }
}
