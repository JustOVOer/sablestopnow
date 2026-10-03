package com.ovo.sablestopnow.client.gui;

import com.ovo.sablestopnow.SablestopNowConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * 配置界面的三个大类（对应 newConfigGUI 的 GUI1.png）：
 * <ul>
 *   <li>{@link #ASSEMBLER} 物理组装器 —— Sable 本体（力过滤 / 自动暂停 / 超速锁定 / 组装相关）；</li>
 *   <li>{@link #STAFF} 物理手杖 —— staff_enhance 段 + 按键设置入口；</li>
 *   <li>{@link #GOGGLES} 工程师护目镜 —— 显示 / 描边 / 坐标轴 / HUD，以及界面自身的两项。</li>
 * </ul>
 */
public enum ModConfigCategory {

    ASSEMBLER("assembler", "simulated:physics_assembler"),
    STAFF("staff", "simulated:creative_physics_staff"),
    GOGGLES("goggles", "create:goggles", "minecraft:spyglass");

    /** 任何图标都取不到时的最后兜底（保证界面上一定有东西可看）。 */
    private static final String FALLBACK_ITEM = "minecraft:redstone_torch";

    private final String id;
    private final String[] iconItemIds;

    ModConfigCategory(final String id, final String... iconItemIds) {
        this.id = id;
        this.iconItemIds = iconItemIds;
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

    /**
     * 图标（渲染游戏内物品）。
     *
     * <p>按注册名依次尝试：首选是 Sable / Simulated 的对应物品（本模组一定装了它们），
     * 之后的候选是「同一意象的原版物品」—— 例如护目镜首选 {@code create:goggles}，
     * 但 Create 不是本模组的依赖，没装时退到 {@code minecraft:spyglass}（同样是「看」的意象），
     * 而不是直接跳到一个和分类毫无关系的物品。全都不存在才用红石火把兜底。
     */
    public ItemStack icon() {
        for (final String candidateId : this.iconItemIds) {
            final ItemStack stack = stackOf(candidateId);
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        return stackOf(FALLBACK_ITEM);
    }

    /** 按注册名取物品；取不到（依赖缺失、注册名拼错）时返回空栈，由调用方决定退到哪一个候选。 */
    private static ItemStack stackOf(final String itemId) {
        try {
            final ResourceLocation location = ResourceLocation.tryParse(itemId);
            if (location == null) {
                return ItemStack.EMPTY;
            }
            return BuiltInRegistries.ITEM.getOptional(location)
                    .filter(item -> item != Items.AIR)
                    .map(ItemStack::new)
                    .orElse(ItemStack.EMPTY);
        } catch (final Exception ignored) {
            // 依赖模组缺失等情况：交给下一个候选
            return ItemStack.EMPTY;
        }
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
                    ConfigOption.bool("new_control_scheme", config.newControlScheme),
                    ConfigOption.bool("show_body_info", config.showBodyInfo),
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
                    ConfigOption.number("axis_angle_degrees", config.axisAngleDegrees, 1.0, 20.0, 0.5),
                    // 界面自身的两项：改完立刻同步给 GUI 库，玩家不用退出界面就能看到效果
                    ConfigOption.bool("ui_animations", config.uiAnimations)
                            .withChange(SablestopNowConfig::syncUiSettings),
                    ConfigOption.number("ui_font_scale", config.uiFontScale, 0.5, 1.0, 0.01)
                            .withChange(SablestopNowConfig::syncUiSettings));
        };
    }
}
