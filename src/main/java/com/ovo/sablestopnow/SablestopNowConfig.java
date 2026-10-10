package com.ovo.sablestopnow;

import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.bus.api.SubscribeEvent;
import org.apache.commons.lang3.tuple.Pair;

import java.util.List;

/**
 * 模组配置。除了各个功能开关，还负责把「界面」相关的两项同步给自研 GUI 库。
 *
 * <p>为什么界面开关放在这里、而不是界面自己持有：这两项是要写进 toml 的配置，
 * 而 GUI 库必须在任何界面打开之前就知道动画开关与字号，所以由配置加载/重载事件统一推给库。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID)
public class SablestopNowConfig {
    public static final ModConfigSpec SPEC;
    public static final Config INSTANCE;

    static {
        final Pair<Config, ModConfigSpec> specPair = new ModConfigSpec.Builder()
                .configure(Config::new);
        SPEC = specPair.getRight();
        INSTANCE = specPair.getLeft();
    }

    /**
     * 配置加载/重载（含首次进游戏、游戏内改 toml 后 F3+T）之后，把界面相关项推给 GUI 库。
     *
     * <p>只认自己的那份 spec：本事件对每个模组、每种配置类型都会触发，不筛一下会把别的配置的加载
     * 也算作自己的重载。
     */
    @SubscribeEvent
    public static void onConfigLoaded(final ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            syncUiSettings();
        }
    }

    /**
     * 把「界面动画总开关 / 界面字号缩放」写入 GUI 库。
     *
     * <p>服务端没有 GUI 库的渲染上下文，直接跳过；配置尚未加载时（启动早期）读值会抛异常，
     * 此时保留库里的默认值即可 —— 反正界面要等玩家进游戏才可能打开。
     */
    public static void syncUiSettings() {
        if (!net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            return;
        }
        boolean animations = true;
        float fontScale = 1.0f;
        try {
            animations = INSTANCE.uiAnimations.get();
            fontScale = INSTANCE.uiFontScale.get().floatValue();
            com.ovo.sablestopnow.client.gui.lib.UiSettings.textShrinkMin = INSTANCE.uiTextShrinkMin.get().floatValue();

        } catch (final IllegalStateException ignored) {
            // 配置未加载：用库的默认值
        }
        try {
            com.ovo.sablestopnow.client.gui.lib.UiSettings.apply(animations, fontScale);
        } catch (final Throwable ignored) {
            // 客户端类不可用（理论上进不来）时不该拖垮配置加载
        }
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

    /**
     * 凸包每个面往里收的格数（配置 {@code collision.hull_inset} ✓ 默认 0.90 ✓ 0.0 = 关掉 ✓）。
     *
     * <p>读不到配置（游戏启动早期、配置尚未加载）时用默认值 ✓ —— 这样凸包在任何时候都是同一套几何 ✓，
     * 不会因为"配置还没读完"而挂出一个尺寸不一样的碰撞体 ✗。
     */
    public static double hullInset() {
        try {
            return INSTANCE.hullInset.get();
        } catch (IllegalStateException e) {
            return 0.5;
        }
    }

    /**
     * 凸分解的**块数上限**（配置 {@code collision.hull_max_pieces} ✓ 默认 24 ✓）。
     *
     * <p>每个凸块在物理侧就是**一个碰撞体** ✗ ⇒ 块数直接进求解器 ✓。
     * 分解器会在这个预算内**自动降低曲面采样**来压块数 ✓（见 {@code ConvexDecomposition} ✓）；
     * 压不进预算就退回"单个凸包" ✓（= 本功能之前的行为 ✓）。
     *
     * <p>⚠ 设成 **1** 等价于**关掉凸分解** ✓ —— 排查"是不是分解引起的"时最省事 ✓。
     */
    public static int hullMaxPieces() {
        try {
            return INSTANCE.hullMaxPieces.get();
        } catch (IllegalStateException e) {
            return 24;
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
        public final ModConfigSpec.DoubleValue hullInset;
        public final ModConfigSpec.IntValue hullMaxPieces;
        public final ModConfigSpec.BooleanValue uiAnimations;
        public final ModConfigSpec.DoubleValue uiFontScale;
    /** 文字自动缩字号的下限（0.6~1.0，默认 0.80）—— 见 UiSettings#textShrinkMin。 */
    public final ModConfigSpec.DoubleValue uiTextShrinkMin;

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

            // ============ 凸包碰撞（曲面实体）============
            //
            // 曲面实体走体素路时挂的是【凸包】碰撞体（原生库提供）。Sable 那侧的体素接触面
            // 会系统性地落在真实表面之外约 1 格（逐物理步实测 0.98 ✓ 而且每个面都一样 ✓），
            // 于是把凸包往里收同样的量来对齐"碰撞"与"画面"。
            //
            // 做成配置项：这个量对着的是实机上的视觉重合度，只能一边看一边调 ——
            // 以前写死在 Java 里，试一个数就要重编一遍原生库（约 5 分钟 ✗），现在是改 toml ✓。
            builder.comment("Convex-hull collision for curved (voxel-path) solids.",
                            "曲面实体的凸包碰撞：每个面往里收多少格。")
                    .push("collision");
            hullInset = builder
                    .comment("Inset applied to EVERY face of the convex hull, in BLOCKS.",
                            "Why: Sable's terrain contacts land systematically OUTSIDE the real surface",
                            "(measured 0.98 block, same on every face), so the hull is shrunk by the",
                            "same amount to make the collision line up with what is drawn.",
                            "The inset is applied per AXIS, so every face moves by this exact distance.",
                            "A radial inset was tried first and only pulled the sides of a cylinder in,",
                            "leaving its top and bottom caps untouched (measured in game).",
                            "0.0 disables the compensation entirely.",
                            "At least 5% of each half-extent always survives, so no value can collapse",
                            "the hull into a degenerate shape.",
                            "",
                            "凸包每个面往里收的格数（单位=格）",
                            "为什么：Sable 的地形接触面系统性地落在真实表面之外（实测 0.98 格、每个面都一样），",
                            "把凸包往里收同样的量，碰撞就与画面重合了。",
                            "按【轴】收而不是按半径收：半径收法只让圆柱侧面内缩、上下底面几乎不动（实机验证）。",
                            "0.0 = 完全不收。每轴至少保留 5% 的半宽，避免任何取值把凸包收成退化形状。")
                    .translation("config.sablestopnow.collision.hull_inset")
                    .defineInRange("hull_inset", 0.5, 0.0, 4.0);
            hullMaxPieces = builder
                    .comment("Maximum number of convex pieces a NON-CONVEX solid may be split into.",
                            "Why: collision shapes must be convex, so a shape with a hole (a washer, a",
                            "window frame) or a concave shape (an L) is split into several convex",
                            "pieces. Each piece is one collider on the body, so this number is a",
                            "physics cost; the decomposer automatically coarsens the curve sampling to",
                            "stay inside this budget and falls back to a single hull if it cannot.",
                            "1 disables convex decomposition entirely (the old behaviour: holes get",
                            "filled in).",
                            "",
                            "非凸实体最多拆成几个凸块（每个凸块 = 一个碰撞体，所以这个数是物理开销）。",
                            "带孔（垫圈 / 窗框）或凹形（L 形）的形状会被拆成若干凸块；分解器会自动降低",
                            "曲面采样以压进这个预算，压不进就退回单个凸包。",
                            "设为 1 = 完全关闭凸分解（回到旧行为，孔会被填掉）。")
                    .translation("config.sablestopnow.collision.hull_max_pieces")
                    .defineInRange("hull_max_pieces", 24, 1, 64);
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

            // ============ 界面（本模组自己的设置界面）============
            builder.comment("Client-side settings GUI (Aeronautics: Tweaks & Toolkit).")
                    .push("ui");
            uiAnimations = builder
                    .comment("Master switch for every animation in the settings GUI (panel reveal, easing, fades).",
                            "界面动画总开关：关闭后所有缓动直接到位，适合低配机器或觉得动效晃眼的玩家。")
                    .translation("config.sablestopnow.ui.ui_animations")
                    .define("ui_animations", true);
            uiFontScale = builder
                    .comment("Text scale inside the settings GUI (0.5 ~ 1.0). Smaller values fit more rows on screen.",
                            "设置界面的文字缩放（0.5~1.0）：调小可以一屏塞下更多设置项。")
                    .translation("config.sablestopnow.ui.ui_font_scale")
                    .defineInRange("ui_font_scale", 1.0, 0.5, 1.0);
        uiTextShrinkMin = builder
            .comment("Lower bound for automatic text shrinking (0.6 ~ 1.0, default 0.80).",
                "文字自动缩字号的下限（0.6~1.0，默认 0.80）。")
            .translation("config.sablestopnow.ui.ui_text_shrink_min")
            .defineInRange("ui_text_shrink_min", 0.80, 0.6, 1.0);

            builder.pop();
        }
    }
}