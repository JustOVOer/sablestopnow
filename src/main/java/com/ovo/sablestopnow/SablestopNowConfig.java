package com.ovo.sablestopnow;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

public class SablestopNowConfig {
    public static final ModConfigSpec SPEC;
    public static final Config INSTANCE;

    static {
        final Pair<Config, ModConfigSpec> specPair = new ModConfigSpec.Builder()
                .configure(Config::new);
        SPEC = specPair.getRight();
        INSTANCE = specPair.getLeft();
    }

    public static void save() {
        SPEC.save();
    }

    /** 配置未加载完（游戏启动早期）时读取会抛 IllegalStateException；此时一律按默认关闭处理。 */
    public static boolean isStaffEnhanceEnabled() {
        try {
            return INSTANCE.enableStaffEnhance.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * 新控制逻辑是否开启（默认开启）：Ctrl 切多选、滚轮切功能、左键应用功能。
     *
     * <p>关闭时回到原来的键位（Z/V/O/K/R/C/X）。配置未加载时按默认 true 处理 —— 与配置文件里的默认值保持一致。</p>
     */
    public static boolean isNewControlScheme() {
        try {
            return INSTANCE.newControlScheme.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** 右上角「瞄准结构信息面板」是否显示（默认开）。配置未加载时按默认 true 处理。 */
    public static boolean isShowBodyInfo() {
        try {
            return INSTANCE.showBodyInfo.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    public static double rotateSensitivity() {
        try {
            return INSTANCE.staffRotateSensitivity.get();
        } catch (IllegalStateException e) {
            return 0.35;
        }
    }

    public static double scrollSensitivity() {
        try {
            return INSTANCE.staffScrollSensitivity.get();
        } catch (IllegalStateException e) {
            return 0.6;
        }
    }

    public static double centerPullSpeed() {
        try {
            return INSTANCE.staffCenterPullSpeed.get();
        } catch (IllegalStateException e) {
            return 0.06;
        }
    }

    public static boolean isGhostReal() {
        try {
            return INSTANCE.ghostReal.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /** 描边（细长长方体盒）的粗细，单位=格。 */
    public static float outlineThickness() {
        try {
            return INSTANCE.outlineThickness.get().floatValue();
        } catch (IllegalStateException e) {
            return 0.05f;
        }
    }

    /** 手杖选中/悬停轮廓的粗细，单位=格。 */
    public static float staffOutlineThickness() {
        try {
            return INSTANCE.staffOutlineThickness.get().floatValue();
        } catch (IllegalStateException e) {
            return 0.06f;
        }
    }

    /** 悬停对象属于当前选中队列时，整组轮廓加粗的倍率。 */
    public static float staffOutlineBoldScale() {
        try {
            return INSTANCE.staffOutlineBoldScale.get().floatValue();
        } catch (IllegalStateException e) {
            return 2.2f;
        }
    }

    /** 缩放：每一格滚轮的倍率增量（0.08 = 每格 ×1.08）。 */
    public static double scaleSensitivity() {
        try {
            return INSTANCE.staffScaleSensitivity.get();
        } catch (IllegalStateException e) {
            return 0.08;
        }
    }

    public static double scaleMin() {
        try {
            return INSTANCE.staffScaleMin.get();
        } catch (IllegalStateException e) {
            return 0.2;
        }
    }

    public static double scaleMax() {
        try {
            return INSTANCE.staffScaleMax.get();
        } catch (IllegalStateException e) {
            return 8.0;
        }
    }

    /** 彩蛋 Superliminal 的最大放置距离（格）。 */
    public static double superliminalMaxDistance() {
        try {
            return INSTANCE.superliminalMaxDistance.get();
        } catch (IllegalStateException e) {
            return 64.0;
        }
    }

    /** 正在被拖拽的物理结构是否对「拖拽它的那个玩家」不再碰撞。 */
    public static boolean isDraggedNoPlayerCollision() {
        try {
            return INSTANCE.draggedNoPlayerCollision.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    /** 被缩放的物理结构是否对所有玩家不再碰撞（物理碰撞体不随缩放变化，避免把玩家吸住）。 */
    public static boolean isScaledNoPlayerCollision() {
        try {
            return INSTANCE.scaledNoPlayerCollision.get();
        } catch (IllegalStateException e) {
            return true;
        }
    }

    public static boolean isSpeedLimitEnabled() {
        try {
            return INSTANCE.speedLimitEnabled.get();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public static double speedLimitThreshold() {
        try {
            return INSTANCE.speedLimitThreshold.get();
        } catch (IllegalStateException e) {
            return 15.0;
        }
    }

    public static class Config {
        // 移除 enableForceLimiter
        public final ModConfigSpec.DoubleValue forceThreshold;
        public final ModConfigSpec.ConfigValue<List<? extends String>> excludedGroups;
        public final ModConfigSpec.IntValue maxRecords;
        public final ModConfigSpec.BooleanValue autoPauseOnFilter;
        public final ModConfigSpec.BooleanValue filterExcessiveForce;  // 新增
        public final ModConfigSpec.BooleanValue lockNewSubLevels;
        public final ModConfigSpec.BooleanValue disablePlacementCollisionCheck;
        public final ModConfigSpec.BooleanValue requireConfirmationBeforeSplit;
        public final ModConfigSpec.BooleanValue renderSubLevelOutlines;
        public final ModConfigSpec.BooleanValue outlineAlwaysVisible;
        public final ModConfigSpec.BooleanValue outlineOnlyContour;
        public final ModConfigSpec.BooleanValue outlineOnlyFocused;
        public final ModConfigSpec.BooleanValue renderAxis;
        public final ModConfigSpec.DoubleValue axisAngleDegrees;
        public final ModConfigSpec.DoubleValue outlineThickness;
        public final ModConfigSpec.BooleanValue enableStaffEnhance;
        public final ModConfigSpec.BooleanValue newControlScheme;
        public final ModConfigSpec.BooleanValue showBodyInfo;
        public final ModConfigSpec.DoubleValue staffOutlineThickness;
        public final ModConfigSpec.DoubleValue staffOutlineBoldScale;
        public final ModConfigSpec.DoubleValue staffScaleSensitivity;
        public final ModConfigSpec.DoubleValue staffScaleMin;
        public final ModConfigSpec.DoubleValue staffScaleMax;
        public final ModConfigSpec.DoubleValue superliminalMaxDistance;
        public final ModConfigSpec.BooleanValue draggedNoPlayerCollision;
        public final ModConfigSpec.BooleanValue scaledNoPlayerCollision;
        public final ModConfigSpec.DoubleValue staffRotateSensitivity;
        public final ModConfigSpec.DoubleValue staffScrollSensitivity;
        public final ModConfigSpec.DoubleValue staffCenterPullSpeed;
        public final ModConfigSpec.BooleanValue ghostReal;
        public final ModConfigSpec.BooleanValue speedLimitEnabled;
        public final ModConfigSpec.DoubleValue speedLimitThreshold;
        Config(ModConfigSpec.Builder builder) {
            builder.comment("Sable Force Limiter Configuration")
                    .push("force_limiter");
            renderAxis = builder
                    .comment("Render an axis indicator at the center of each sub-level.")
                    .translation("config.sablestopnow.render_axis")
                    .define("render_axis", false);

            axisAngleDegrees = builder
                    .comment("Angle in degrees that the axis arm subtends at the camera.")
                    .translation("config.sablestopnow.axis_angle_degrees")
                    .defineInRange("axis_angle_degrees", 5.0, 1.0, 20.0);
            outlineThickness = builder
                    .comment("Thickness (in blocks) of the box-style outline used by 'outline_only_contour' mode. Default 0.05.")
                    .translation("config.sablestopnow.outline_thickness")
                    .defineInRange("outline_thickness", 0.05, 0.005, 0.5);
            outlineOnlyFocused = builder
                    .comment("If true, only draw outlines for the sub-level the player is looking at.")
                    .translation("config.sablestopnow.outline_only_focused")
                    .define("outline_only_focused", false);
            outlineOnlyContour = builder
                    .comment("If true, only draw edges that are exposed to air (the contour of the sub-level), otherwise draw full block outlines.")
                    .translation("config.sablestopnow.outline_only_contour")
                    .define("outline_only_contour", false);
            outlineAlwaysVisible = builder
                    .comment("Make block outlines always visible (ignore depth test)")
                    .translation("config.sablestopnow.outline_always_visible")
                    .define("outline_always_visible", true);
            renderSubLevelOutlines = builder
                    .comment("Render colored outlines for each block on the surface of sub-levels")
                    .translation("config.sablestopnow.render_sub_level_outlines")
                    .define("render_sub_level_outlines", false);
            requireConfirmationBeforeSplit = builder
                    .comment("Require player confirmation before splitting a sub-level.")
                    .translation("config.sablestopnow.force_limiter.require_confirmation_before_split")
                    .define("require_confirmation_before_split", false);
            filterExcessiveForce = builder
                    .comment("Enable filtering of excessive forces (forces exceeding threshold).")
                    .define("filter_excessive_force", false);
            disablePlacementCollisionCheck = builder
                    .comment("Disable placement collision check with other sub-levels (allow placing blocks inside other sub-levels).")
                    .translation("config.sablestopnow.force_limiter.disable_placement_collision_check")
                    .define("disable_placement_collision_check", false);
            forceThreshold = builder
                    .comment("Maximum force magnitude (in Sable units). Forces exceeding this value will be discarded.")
                    .defineInRange("threshold", 1000.0, 0.0, Double.MAX_VALUE);

            excludedGroups = builder
                    .comment("List of force group IDs to EXCLUDE from limiting. Forces from these groups will be ignored. " +
                            "Example: [\"sable:gravity\"] to allow gravity forces to pass through.")
                    .defineList("excluded_groups",
                            List.of(),
                            obj -> obj instanceof String);

            maxRecords = builder
                    .comment("Maximum number of force records to keep in memory for display. Older records will be removed.")
                    .defineInRange("max_records", 1000, 10, 10000);

            autoPauseOnFilter = builder
                    .comment("If true, when a force is filtered, automatically execute '/sable paused true' and reset this setting to false.")
                    .define("auto_pause_on_filter", false);
            lockNewSubLevels = builder
                    .comment("Lock newly created sub-levels using a fixed constraint.")
                    .translation("config.sablestopnow.force_limiter.lock_new_sub_levels")
                    .define("lock_new_sub_levels", false);
            speedLimitEnabled = builder
                    .comment("Auto-lock a physics body when its speed exceeds the threshold (dragged bodies are exempt).")
                    .define("speed_limit_enabled", false);
            speedLimitThreshold = builder
                    .comment("Speed limit in blocks/second for speed_limit_enabled.")
                    .defineInRange("speed_limit_threshold", 15.0, 0.1, 1000.0);
            builder.pop();

            // ============ Physics Staff Enhance ============
            builder.comment("Physics Staff enhancements (multi-select, box select, collision toggle, group move)")
                    .push("staff_enhance");
            enableStaffEnhance = builder
                    .comment("Master switch for all physics staff enhancement features (Ctrl multi-select, right-click select, Alt+scroll penetration, Z box select, V collision toggle, group move). When false, the staff behaves exactly as Simulated/Aeronautics' original.")
                    .translation("config.sablestopnow.staff_enhance.enable_staff_enhance")
                    .define("enable_staff_enhance", false);
            newControlScheme = builder
                    .comment("New control scheme (on by default): Ctrl = multi-select, mouse wheel = switch function, "
                            + "left click = apply the selected function. When false, the original key bindings "
                            + "(Z/V/O/K/R/C/X) are used instead.")
                    .translation("config.sablestopnow.staff_enhance.new_control_scheme")
                    .define("new_control_scheme", true);
            showBodyInfo = builder
                    .comment("Show the aiming body info panel at the top right while holding the physics staff "
                            + "(owner, speed, mass, scale, collision, snapshot).")
                    .translation("config.sablestopnow.staff_enhance.show_body_info")
                    .define("show_body_info", true);
            staffOutlineThickness = builder
                    .comment("Thickness (in blocks) of the box-style outline drawn for selected/hovered physics bodies. Default 0.06.")
                    .translation("config.sablestopnow.staff_enhance.staff_outline_thickness")
                    .defineInRange("staff_outline_thickness", 0.06, 0.005, 0.5);
            staffOutlineBoldScale = builder
                    .comment("When the hovered body belongs to the current selection, the whole selection's outline is drawn this many times thicker. Default 2.2.")
                    .translation("config.sablestopnow.staff_enhance.staff_outline_bold_scale")
                    .defineInRange("staff_outline_bold_scale", 2.2, 1.0, 8.0);
            staffScaleSensitivity = builder
                    .comment("Scale step per scroll notch while holding the scale key (X). 0.08 = x1.08 per notch.")
                    .translation("config.sablestopnow.staff_enhance.scale_sensitivity")
                    .defineInRange("scale_sensitivity", 0.08, 0.005, 0.5);
            staffScaleMin = builder
                    .comment("Minimum scale factor for the X + scroll scaling feature.")
                    .translation("config.sablestopnow.staff_enhance.scale_min")
                    .defineInRange("scale_min", 0.2, 0.05, 1.0);
            staffScaleMax = builder
                    .comment("Maximum scale factor for the X + scroll scaling feature.")
                    .translation("config.sablestopnow.staff_enhance.scale_max")
                    .defineInRange("scale_max", 8.0, 1.0, 50.0);
            superliminalMaxDistance = builder
                    .comment("Easter egg (Superliminal): max distance at which a dragged body is placed on the surface you look at.")
                    .translation("config.sablestopnow.staff_enhance.superliminal_max_distance")
                    .defineInRange("superliminal_max_distance", 64.0, 8.0, 256.0);
            draggedNoPlayerCollision = builder
                    .comment("While a physics body is being dragged (by the staff or by this mod's group control), the player dragging it no longer collides with it. Only the dragging player is affected.")
                    .translation("config.sablestopnow.staff_enhance.dragged_no_player_collision")
                    .define("dragged_no_player_collision", true);
            scaledNoPlayerCollision = builder
                    .comment("A scaled physics body no longer collides with ANY player. Off by default now that scaling rebuilds the rapier collider (the body collides with players at its real size); turn this on only if you want scaled bodies to be walk-through.")
                    .translation("config.sablestopnow.staff_enhance.scaled_no_player_collision")
                    .define("scaled_no_player_collision", false);
            staffRotateSensitivity = builder
                    .comment("Group rotation sensitivity (TAB + mouse). Default 0.35.")
                    .defineInRange("rotate_sensitivity", 0.35, 0.001, 10.0);
            staffScrollSensitivity = builder
                    .comment("Scroll sensitivity for group distance / penetration. Default 0.6.")
                    .defineInRange("scroll_sensitivity", 0.6, 0.001, 10.0);
            staffCenterPullSpeed = builder
                    .comment("How fast the centroid eases to the view center while holding the center-pull key (0..1 per tick). Default 0.06.")
                    .defineInRange("center_pull_speed", 0.06, 0.0001, 1.0);
            ghostReal = builder
                    .comment("EXPERIMENTAL: when true, the V no-collision marker really stops the marked body from colliding with OTHER Sable bodies (not terrain/players). Implemented with transient no-effect joints (contacts_enabled=false) to nearby bodies, removed when they move far away. Default false.")
                    .define("ghost_real", false);
            builder.pop();
        }
    }
}