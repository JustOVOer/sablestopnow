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
        Config(ModConfigSpec.Builder builder) {
            builder.comment("Sable Force Limiter Configuration")
                    .push("force_limiter");
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
        }
    }
}