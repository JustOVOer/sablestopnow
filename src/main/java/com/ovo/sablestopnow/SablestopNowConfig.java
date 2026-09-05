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

    // ---- 键位/灵敏度访问器（未加载时回退默认值，避免启动早期崩） ----
    private static final int DEF_KEY_MULTI = 341; // GLFW_KEY_LEFT_CONTROL
    private static final int DEF_KEY_BOX = 90;    // Z
    private static final int DEF_KEY_COLLISION = 86; // V
    private static final int DEF_KEY_CENTER_PULL = 67; // C

    public static int keyMultiSelect() {
        try {
            return INSTANCE.staffKeyMultiSelect.get();
        } catch (IllegalStateException e) {
            return DEF_KEY_MULTI;
        }
    }

    public static int keyBoxSelect() {
        try {
            return INSTANCE.staffKeyBoxSelect.get();
        } catch (IllegalStateException e) {
            return DEF_KEY_BOX;
        }
    }

    public static int keyCollisionToggle() {
        try {
            return INSTANCE.staffKeyCollisionToggle.get();
        } catch (IllegalStateException e) {
            return DEF_KEY_COLLISION;
        }
    }

    public static int keyCenterPull() {
        try {
            return INSTANCE.staffKeyCenterPull.get();
        } catch (IllegalStateException e) {
            return DEF_KEY_CENTER_PULL;
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
        public final ModConfigSpec.BooleanValue enableStaffEnhance;
        public final ModConfigSpec.IntValue staffKeyMultiSelect;
        public final ModConfigSpec.IntValue staffKeyBoxSelect;
        public final ModConfigSpec.IntValue staffKeyCollisionToggle;
        public final ModConfigSpec.IntValue staffKeyCenterPull;
        public final ModConfigSpec.DoubleValue staffRotateSensitivity;
        public final ModConfigSpec.DoubleValue staffScrollSensitivity;
        public final ModConfigSpec.DoubleValue staffCenterPullSpeed;
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
            builder.pop();

            // ============ Physics Staff Enhance ============
            builder.comment("Physics Staff enhancements (multi-select, box select, collision toggle, group move)")
                    .push("staff_enhance");
            enableStaffEnhance = builder
                    .comment("Master switch for all physics staff enhancement features (Ctrl multi-select, right-click select, Alt+scroll penetration, Z box select, V collision toggle, group move). When false, the staff behaves exactly as Simulated/Aeronautics' original.")
                    .translation("config.sablestopnow.staff_enhance.enable_staff_enhance")
                    .define("enable_staff_enhance", false);
            staffKeyMultiSelect = builder
                    .comment("GLFW key code for toggling multi-select mode. Default 341 = LEFT_CONTROL.")
                    .defineInRange("key_multi_select", DEF_KEY_MULTI, 0, 1024);
            staffKeyBoxSelect = builder
                    .comment("GLFW key code for Z box-select. Default 90 = Z.")
                    .defineInRange("key_box_select", DEF_KEY_BOX, 0, 1024);
            staffKeyCollisionToggle = builder
                    .comment("GLFW key code for V no-collision toggle. Default 86 = V.")
                    .defineInRange("key_collision_toggle", DEF_KEY_COLLISION, 0, 1024);
            staffKeyCenterPull = builder
                    .comment("GLFW key code for slowly pulling the group centroid to view center during group control. Default 67 = C.")
                    .defineInRange("key_center_pull", DEF_KEY_CENTER_PULL, 0, 1024);
            staffRotateSensitivity = builder
                    .comment("Group rotation sensitivity (TAB + mouse). Default 0.35.")
                    .defineInRange("rotate_sensitivity", 0.35, 0.001, 10.0);
            staffScrollSensitivity = builder
                    .comment("Scroll sensitivity for group distance / penetration. Default 0.6.")
                    .defineInRange("scroll_sensitivity", 0.6, 0.001, 10.0);
            staffCenterPullSpeed = builder
                    .comment("How fast the centroid eases to the view center while holding the center-pull key (0..1 per tick). Default 0.06.")
                    .defineInRange("center_pull_speed", 0.06, 0.0001, 1.0);
            builder.pop();
        }
    }
}