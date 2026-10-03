package com.ovo.sablestopnow.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.ovo.sablestopnow.SablestopNow;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 物理手杖增强 —— 全部键位（Minecraft {@link KeyMapping}，可在「选项 → 按键控制」里改）。
 *
 * <p>为什么用 KeyMapping 而不是 config 里的 GLFW 键码：用户要求“所有按键均可配置”，
 * KeyMapping 是原版唯一的可改键途径，并且天然做到“开 GUI 时不触发”（屏幕打开时
 * {@code KeyboardHandler} 不会更新 KeyMapping 状态）。
 *
 * <p>鼠标侧（左/右/中键、滚轮）仍由 {@code MouseHandlerStaffEnhanceMixin} 在 HEAD 拦截并 cancel，
 * 因为多选模式必须真正“吞掉”航空学的手杖交互。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class StaffKeyMappings {

    /** 自定义按键分类（翻译键：key.categories.<modid>）。 */
    public static final String CATEGORY = "key.categories.sablestopnow";

    /** 进入/退出多选模式（默认 Ctrl）。 */
    public static final KeyMapping MULTI_SELECT = create("multi_select", GLFW.GLFW_KEY_LEFT_CONTROL);
    /** 区域选择（默认 Z）。 */
    public static final KeyMapping REGION_SELECT = create("region_select", GLFW.GLFW_KEY_Z);
    /** 整组无碰撞切换（默认 V）。 */
    public static final KeyMapping COLLISION_TOGGLE = create("collision_toggle", GLFW.GLFW_KEY_V);
    /** 整组归中 / 视角锁定（默认 C，长按）。 */
    public static final KeyMapping CENTER_PULL = create("center_pull", GLFW.GLFW_KEY_C);
    /** 所有权设置/取消（默认 O）。 */
    public static final KeyMapping OWNERSHIP = create("ownership", GLFW.GLFW_KEY_O);
    /** 创建/取消快照（默认 K）。 */
    public static final KeyMapping SNAPSHOT = create("snapshot", GLFW.GLFW_KEY_K);
    /** 回退到快照（默认 R）。 */
    public static final KeyMapping RESTORE = create("restore", GLFW.GLFW_KEY_R);
    /** 缩放物理结构（默认 X，按住 + 滚轮）。 */
    public static final KeyMapping SCALE = create("scale", GLFW.GLFW_KEY_X);
    /** 穿透层数修饰键（默认 Alt，配合滚轮使用）。 */
    public static final KeyMapping PENETRATION_MODIFIER = create("penetration_modifier", GLFW.GLFW_KEY_LEFT_ALT);
    /** 进入/退出配合模式（默认 Y）；配合模式下按住 Ctrl 可切到结构级参考、滚轮循环。 */
    public static final KeyMapping MATE_MODE = create("mate_mode", GLFW.GLFW_KEY_Y);
    /** 打开配合界面（默认 M）。 */
    public static final KeyMapping MATE_TREE = create("mate_tree", GLFW.GLFW_KEY_M);
    /**
     * 配合模式下释放/锁回鼠标（默认 Tab）。
     *
     * <p>配合模式的边栏是<b>常驻</b>的，进入时鼠标保持锁定（准星可转、右键可选取）；
     * 想点边栏上的按钮就得先把鼠标放出来。这个键在边栏里也有一个对应按钮（放出来之后才点得到）。
     */
    public static final KeyMapping MATE_MOUSE = create("mate_mouse", GLFW.GLFW_KEY_TAB);
    /** 打开模组设置界面（默认 Ctrl+O，与所有权键 O 不冲突）。 */
    public static final KeyMapping OPEN_CONFIG = new KeyMapping("key.sablestopnow.open_config",
            KeyConflictContext.UNIVERSAL,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            CATEGORY);

    /**
     * GUI 自检报告（默认 F9，调试用）。
     *
     * <p>把当前所有界面的控件树、状态读写、命令派发情况打到日志并写成 {@code run/ui-lint.txt}。
     * 有它之后，「按钮写了没接动作」「两个控件抢同一块矩形」「状态写了没人读」这类问题
     * 在实机测试之前就能看见，而不是等玩家截图反馈。
     */
    public static final KeyMapping UI_LINT = create("ui_lint", GLFW.GLFW_KEY_F9);

    // ---- 模型模式：相机平移（六向）----
    //
    // 为什么不用 Q/E 做上下：**Q 是原版的「丢弃物品」**，按下去会把手里的东西扔了。
    // 上下改用方向键上/下，六项全部是可以改键的 KeyMapping ——
    // 模型模式下玩家被冻结、不会用 WASD 走路，所以与移动键同名没有副作用，
    // 但玩家如果觉得冲突（比如装了别的模组），可以在「选项 → 按键控制」里改。
    public static final KeyMapping MODEL_FORWARD = create("model_forward", GLFW.GLFW_KEY_W);
    public static final KeyMapping MODEL_BACK = create("model_back", GLFW.GLFW_KEY_S);
    public static final KeyMapping MODEL_LEFT = create("model_left", GLFW.GLFW_KEY_A);
    public static final KeyMapping MODEL_RIGHT = create("model_right", GLFW.GLFW_KEY_D);
    /** 上升（默认方向键上）。 */
    public static final KeyMapping MODEL_UP = create("model_up", GLFW.GLFW_KEY_UP);
    /** 下降（默认方向键下）。 */
    public static final KeyMapping MODEL_DOWN = create("model_down", GLFW.GLFW_KEY_DOWN);

    private static final List<KeyMapping> ALL = List.of(
            MULTI_SELECT, REGION_SELECT, COLLISION_TOGGLE, CENTER_PULL,
            OWNERSHIP, SNAPSHOT, RESTORE, SCALE, PENETRATION_MODIFIER, OPEN_CONFIG,
            MATE_MODE, MATE_TREE, MATE_MOUSE, UI_LINT,
            MODEL_FORWARD, MODEL_BACK, MODEL_LEFT, MODEL_RIGHT, MODEL_UP, MODEL_DOWN);

    private StaffKeyMappings() {
    }

    private static KeyMapping create(final String name, final int defaultKey) {
        return new KeyMapping("key.sablestopnow." + name,
                KeyConflictContext.IN_GAME,
                KeyModifier.NONE,
                InputConstants.Type.KEYSYM,
                defaultKey,
                CATEGORY);
    }

    @SubscribeEvent
    public static void onRegisterKeyMappings(final RegisterKeyMappingsEvent event) {
        ALL.forEach(event::register);
    }

    /** 是否按住“穿透层数修饰键”。默认是左 Alt，此时右 Alt 也一并接受（避免玩家按错边）。 */
    public static boolean isPenetrationModifierDown() {
        if (PENETRATION_MODIFIER.isDown()) {
            return true;
        }
        if (PENETRATION_MODIFIER.isDefault()
                && PENETRATION_MODIFIER.getKey().getType() == InputConstants.Type.KEYSYM
                && PENETRATION_MODIFIER.getKey().getValue() == GLFW.GLFW_KEY_LEFT_ALT) {
            final Minecraft mc = Minecraft.getInstance();
            return mc.getWindow() != null
                    && InputConstants.isKeyDown(mc.getWindow().getWindow(), GLFW.GLFW_KEY_RIGHT_ALT);
        }
        return false;
    }
}
