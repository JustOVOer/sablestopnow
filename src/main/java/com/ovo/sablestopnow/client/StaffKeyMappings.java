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
    /** 打开模组设置界面（默认 Ctrl+O，与所有权键 O 不冲突）。 */
    public static final KeyMapping OPEN_CONFIG = new KeyMapping("key.sablestopnow.open_config",
            KeyConflictContext.UNIVERSAL,
            KeyModifier.CONTROL,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            CATEGORY);

    private static final List<KeyMapping> ALL = List.of(
            MULTI_SELECT, REGION_SELECT, COLLISION_TOGGLE, CENTER_PULL,
            OWNERSHIP, SNAPSHOT, RESTORE, SCALE, PENETRATION_MODIFIER, OPEN_CONFIG);

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
