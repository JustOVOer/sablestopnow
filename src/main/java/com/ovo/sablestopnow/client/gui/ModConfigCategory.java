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
    GOGGLES("goggles", "create:goggles", "minecraft:spyglass"),

    /** **调色** ✓（item 2）：图标用**随机染料** ✅ */
    COLORS("colors", randomDye());

    /** 随机挑一种染料 id ✓（枚举常量初始化期调用 ⇒ 不依赖别处的静态字段）。 */
    private static String randomDye() {
        final String[] dyes = {
            "white_dye", "orange_dye", "magenta_dye", "light_blue_dye", "yellow_dye",
            "lime_dye", "pink_dye", "gray_dye", "light_gray_dye", "cyan_dye", "purple_dye",
            "blue_dye", "brown_dye", "green_dye", "red_dye", "black_dye",
        };
        return "minecraft:" + dyes[(int) (Math.random() * dyes.length)];
    }

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
                    ConfigOption.bool("filter_excessive_force", config.filterExcessiveForce).section("gui.sablestopnow.section.force"),
                    ConfigOption.number("threshold", config.forceThreshold, 0.0, 10000.0, 10.0).section("gui.sablestopnow.section.force"),
                    ConfigOption.stringList("excluded_groups", config.excludedGroups).section("gui.sablestopnow.section.force"),
                    ConfigOption.number("max_records", config.maxRecords, 10.0, 10000.0).section("gui.sablestopnow.section.force"),
                    ConfigOption.bool("auto_pause_on_filter", config.autoPauseOnFilter).section("gui.sablestopnow.section.force"),
                    ConfigOption.bool("lock_new_sub_levels", config.lockNewSubLevels).section("gui.sablestopnow.section.assembly"),
                    ConfigOption.bool("disable_placement_collision_check", config.disablePlacementCollisionCheck).section("gui.sablestopnow.section.assembly"),
                    ConfigOption.bool("require_confirmation_before_split", config.requireConfirmationBeforeSplit).section("gui.sablestopnow.section.assembly"),
                    ConfigOption.bool("speed_limit_enabled", config.speedLimitEnabled).section("gui.sablestopnow.section.speed"),
                    ConfigOption.number("speed_limit_threshold", config.speedLimitThreshold, 0.1, 200.0, 0.5).section("gui.sablestopnow.section.speed"),
                    // 凸包内凹量（格）：曲面实体碰撞体的每个面往里收多少 —— 调"碰撞与画面是否重合"
                    ConfigOption.number("hull_inset", config.hullInset, 0.0, 2.0, 0.01).section("gui.sablestopnow.section.hull"),
                    // 凸分解块数预算：非凸/带孔的形状最多拆几块（1 = 关闭分解 ✓）
                    ConfigOption.number("hull_max_pieces", config.hullMaxPieces, 1.0, 64.0).section("gui.sablestopnow.section.hull"));
            case STAFF -> List.of(
                    ConfigOption.bool("enable_staff_enhance", config.enableStaffEnhance).section("gui.sablestopnow.section.staff_core"),
                    ConfigOption.bool("new_control_scheme", config.newControlScheme).section("gui.sablestopnow.section.staff_core"),
                    ConfigOption.bool("show_body_info", config.showBodyInfo).section("gui.sablestopnow.section.staff_core"),
                    ConfigOption.number("staff_outline_thickness", config.staffOutlineThickness, 0.005, 0.5, 0.005).section("gui.sablestopnow.section.staff_outline"),
                    ConfigOption.number("staff_outline_bold_scale", config.staffOutlineBoldScale, 1.0, 8.0, 0.1).section("gui.sablestopnow.section.staff_outline"),
                    ConfigOption.number("rotate_sensitivity", config.staffRotateSensitivity, 0.001, 10.0, 0.05).section("gui.sablestopnow.section.staff_control"),
                    ConfigOption.number("scroll_sensitivity", config.staffScrollSensitivity, 0.001, 10.0, 0.05).section("gui.sablestopnow.section.staff_control"),
                    ConfigOption.number("center_pull_speed", config.staffCenterPullSpeed, 0.0001, 1.0, 0.005).section("gui.sablestopnow.section.staff_control"),
                    ConfigOption.number("scale_sensitivity", config.staffScaleSensitivity, 0.005, 0.5, 0.005).section("gui.sablestopnow.section.staff_scale"),
                    ConfigOption.number("scale_min", config.staffScaleMin, 0.05, 1.0, 0.05).section("gui.sablestopnow.section.staff_scale"),
                    ConfigOption.number("scale_max", config.staffScaleMax, 1.0, 50.0, 0.5).section("gui.sablestopnow.section.staff_scale"),
                    ConfigOption.number("superliminal_max_distance", config.superliminalMaxDistance, 8.0, 256.0, 4.0).section("gui.sablestopnow.section.staff_scale"),
                    ConfigOption.bool("dragged_no_player_collision", config.draggedNoPlayerCollision).section("gui.sablestopnow.section.staff_collision"),
                    ConfigOption.bool("scaled_no_player_collision", config.scaledNoPlayerCollision).section("gui.sablestopnow.section.staff_collision"),
                    ConfigOption.bool("ghost_real", config.ghostReal).section("gui.sablestopnow.section.staff_collision"),
                    ConfigOption.action("keybinds", () -> Minecraft.getInstance().setScreen(
                            new KeyBindsScreen(Minecraft.getInstance().screen, Minecraft.getInstance().options))).section("gui.sablestopnow.section.staff_keys"));
            case COLORS -> List.of(
                    // 一个入口按钮：打开调色板（HSV 滑条 + 十六进制 + 恢复默认）✓
                    ConfigOption.action("color_palette", () -> {
                        final net.minecraft.client.Minecraft mc =
                                net.minecraft.client.Minecraft.getInstance();
                        mc.setScreen(new ColorPaletteScreen(mc.screen));
                    }).section("gui.sablestopnow.section.colors"));
            case GOGGLES -> List.of(
                    ConfigOption.bool("render_sub_level_outlines", config.renderSubLevelOutlines).section("gui.sablestopnow.section.outline"),
                    ConfigOption.bool("outline_always_visible", config.outlineAlwaysVisible).section("gui.sablestopnow.section.outline"),
                    ConfigOption.bool("outline_only_contour", config.outlineOnlyContour).section("gui.sablestopnow.section.outline"),
                    ConfigOption.bool("outline_only_focused", config.outlineOnlyFocused).section("gui.sablestopnow.section.outline"),
                    ConfigOption.number("outline_thickness", config.outlineThickness, 0.005, 0.5, 0.005).section("gui.sablestopnow.section.outline"),
                    ConfigOption.bool("render_axis", config.renderAxis).section("gui.sablestopnow.section.axis"),
                    ConfigOption.number("axis_angle_degrees", config.axisAngleDegrees, 1.0, 20.0, 0.5).section("gui.sablestopnow.section.axis"),
                    // 界面自身的两项：改完立刻同步给 GUI 库，玩家不用退出界面就能看到效果
                    ConfigOption.bool("ui_animations", config.uiAnimations).section("gui.sablestopnow.section.ui")
                            .withChange(SablestopNowConfig::syncUiSettings),
                    // 自动缩字号下限（0.6~1.0 ✓ 与 ui_font_scale 同组 ✓ 属于"界面"这一组 ✓）
                    ConfigOption.number("ui_text_shrink_min", config.uiTextShrinkMin, 0.6, 1.0, 0.01)
                            .section("gui.sablestopnow.section.ui"),
                    ConfigOption.number("ui_font_scale", config.uiFontScale, 0.5, 1.0, 0.01).section("gui.sablestopnow.section.ui")
                            .withChange(SablestopNowConfig::syncUiSettings));
        };
    }
}
