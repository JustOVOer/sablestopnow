package com.ovo.sablestopnow.client.gui;

import com.ovo.sablestopnow.SablestopNowConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * 配置界面的三个大类（对应 newConfigGUI 的 GUI1.png）：
 * <ul>
 *   <li>{@link #ASSEMBLER} 物理组装器 —— Sable 本体（力过滤 / 自动暂停 / 超速锁定 / 组装相关）；</li>
 *   <li>{@link #STAFF} 物理手杖 —— staff_enhance 段 + 按键设置入口；</li>
 *   <li>{@link #GOGGLES} 工程师护目镜 —— 显示 / 描边 / 坐标轴 / HUD。</li>
 * </ul>
 */
public enum ModConfigCategory {

    ASSEMBLER("assembler", "simulated:physics_assembler"),
    STAFF("staff", "simulated:creative_physics_staff"),
    GOGGLES("goggles", "create:goggles");

    private final String id;
    private final String iconItemId;

    ModConfigCategory(final String id, final String iconItemId) {
        this.id = id;
        this.iconItemId = iconItemId;
    }

    public String id() {
        return this.id;
    }

    public Component title() {
        return Component.translatable("gui.sablestopnow.category." + this.id);
    }

    public Component subtitle() {
        return Component.translatable("gui.sablestopnow.category." + this.id + ".desc");
    }

    /** 图标（渲染游戏内物品；取不到时返回空栈，由界面画占位方块）。 */
    public ItemStack icon() {
        try {
            final ResourceLocation location = ResourceLocation.tryParse(this.iconItemId);
            if (location != null) {
                final Item item = BuiltInRegistries.ITEM.get(location);
                if (item != null && item != Items.AIR) {
                    return new ItemStack(item);
                }
            }
        } catch (final Exception ignored) {
            // 依赖模组缺失等情况：退化为占位图标
        }
        return ItemStack.EMPTY;
    }

    /** 该大类下的设置项。 */
    public List<ConfigOption> options() {
        final SablestopNowConfig.Config config = SablestopNowConfig.INSTANCE;
        return switch (this) {
            case ASSEMBLER -> List.of(
                    ConfigOption.bool("filter_excessive_force", config.filterExcessiveForce),
                    ConfigOption.number("threshold", config.forceThreshold, 0.0, 10000.0, 10.0),
                    ConfigOption.stringList("excluded_groups", config.excludedGroups),
                    ConfigOption.number("max_records", config.maxRecords, 10.0, 10000.0),
                    ConfigOption.bool("auto_pause_on_filter", config.autoPauseOnFilter),
                    ConfigOption.bool("lock_new_sub_levels", config.lockNewSubLevels),
                    ConfigOption.bool("disable_placement_collision_check", config.disablePlacementCollisionCheck),
                    ConfigOption.bool("require_confirmation_before_split", config.requireConfirmationBeforeSplit),
                    ConfigOption.bool("speed_limit_enabled", config.speedLimitEnabled),
                    ConfigOption.number("speed_limit_threshold", config.speedLimitThreshold, 0.1, 200.0, 0.5));
            case STAFF -> List.of(
                    ConfigOption.bool("enable_staff_enhance", config.enableStaffEnhance),
                    ConfigOption.number("staff_outline_thickness", config.staffOutlineThickness, 0.005, 0.5, 0.005),
                    ConfigOption.number("staff_outline_bold_scale", config.staffOutlineBoldScale, 1.0, 8.0, 0.1),
                    ConfigOption.number("rotate_sensitivity", config.staffRotateSensitivity, 0.001, 10.0, 0.05),
                    ConfigOption.number("scroll_sensitivity", config.staffScrollSensitivity, 0.001, 10.0, 0.05),
                    ConfigOption.number("center_pull_speed", config.staffCenterPullSpeed, 0.0001, 1.0, 0.005),
                    ConfigOption.number("scale_sensitivity", config.staffScaleSensitivity, 0.005, 0.5, 0.005),
                    ConfigOption.number("scale_min", config.staffScaleMin, 0.05, 1.0, 0.05),
                    ConfigOption.number("scale_max", config.staffScaleMax, 1.0, 50.0, 0.5),
                    ConfigOption.number("superliminal_max_distance", config.superliminalMaxDistance, 8.0, 256.0, 4.0),
                    ConfigOption.bool("dragged_no_player_collision", config.draggedNoPlayerCollision),
                    ConfigOption.bool("scaled_no_player_collision", config.scaledNoPlayerCollision),
                    ConfigOption.bool("ghost_real", config.ghostReal),
                    ConfigOption.action("keybinds", () -> Minecraft.getInstance().setScreen(
                            new KeyBindsScreen(Minecraft.getInstance().screen, Minecraft.getInstance().options))));
            case GOGGLES -> List.of(
                    ConfigOption.bool("render_sub_level_outlines", config.renderSubLevelOutlines),
                    ConfigOption.bool("outline_always_visible", config.outlineAlwaysVisible),
                    ConfigOption.bool("outline_only_contour", config.outlineOnlyContour),
                    ConfigOption.bool("outline_only_focused", config.outlineOnlyFocused),
                    ConfigOption.number("outline_thickness", config.outlineThickness, 0.005, 0.5, 0.005),
                    ConfigOption.bool("render_axis", config.renderAxis),
                    ConfigOption.number("axis_angle_degrees", config.axisAngleDegrees, 1.0, 20.0, 0.5));
        };
    }
}
