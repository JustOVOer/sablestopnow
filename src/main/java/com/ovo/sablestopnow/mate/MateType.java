package com.ovo.sablestopnow.mate;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 配合类型 —— 对应 {@code cp/description.txt} 的「📐 标准配合」一节。
 *
 * <p>每种类型声明三件事：
 * <ol>
 *   <li>{@link #valueKind()} —— 是否需要玩家给一个数值（距离/角度），以及单位与取值范围；</li>
 *   <li>{@link #requiresDirection()} —— 是否要求两侧参考都带方向（方向类配合）；</li>
 *   <li>几何语义本身在 {@link MateFrames} 里实现（锁定哪几个自由度 + 如何吸附）。</li>
 * </ol>
 *
 * <p>「高级配合」（轮廓中心/对称/宽度/路径/线性耦合/限制）留待标准配合验证通过后再补，
 * 见 {@code docs/mate-system-design.md}。
 */
public enum MateType {

    /** 重合：使所选的面/边线/基准面/顶点共享同一个无限基准面或空间点。 */
    COINCIDENT("coincident", ValueKind.NONE, false),
    /** 平行：使所选项目保持等间距（方向相同）。 */
    PARALLEL("parallel", ValueKind.NONE, true),
    /** 垂直：将所选项以 90° 角放置。 */
    PERPENDICULAR("perpendicular", ValueKind.NONE, true),
    /** 相切：使所选项目相切（至少要有一侧是圆柱面/圆锥面/球面）。 */
    TANGENT("tangent", ValueKind.DISTANCE, true),
    /** 同心：使所选项共享同一中心线（轴线）。 */
    CONCENTRIC("concentric", ValueKind.NONE, true),
    /** 锁定：保持两个零部件之间的当前相对位置和方向不变。 */
    LOCK("lock", ValueKind.NONE, false),
    /** 距离：使所选项之间保持指定的距离。 */
    DISTANCE("distance", ValueKind.DISTANCE, false),
    /** 角度：使所选项之间保持指定的角度。 */
    ANGLE("angle", ValueKind.ANGLE, true);

    /** 数值类型。 */
    public enum ValueKind {
        /** 不需要数值。 */
        NONE,
        /** 需要距离（方块，可负）。 */
        DISTANCE,
        /** 需要角度（度，0..180）。 */
        ANGLE
    }

    private final String id;
    private final ValueKind valueKind;
    private final boolean requiresDirection;

    MateType(final String id, final ValueKind valueKind, final boolean requiresDirection) {
        this.id = id;
        this.valueKind = valueKind;
        this.requiresDirection = requiresDirection;
    }

    /** 翻译键后缀：{@code mate.type.<id>}；描述为 {@code mate.type.<id>.desc}。 */
    public String id() {
        return this.id;
    }

    public ValueKind valueKind() {
        return this.valueKind;
    }

    /** 是否要求两侧参考都带方向才能成立。 */
    public boolean requiresDirection() {
        return this.requiresDirection;
    }

    public boolean needsValue() {
        return this.valueKind != ValueKind.NONE;
    }

    /** 默认数值（距离=0，角度=90°）。 */
    public double defaultValue() {
        return this.valueKind == ValueKind.ANGLE ? 90.0 : 0.0;
    }

    /** 数值下限。 */
    public double minValue() {
        return switch (this.valueKind) {
            case NONE -> 0.0;
            case DISTANCE -> -64.0;
            case ANGLE -> 0.0;
        };
    }

    /** 数值上限。 */
    public double maxValue() {
        return switch (this.valueKind) {
            case NONE -> 0.0;
            case DISTANCE -> 64.0;
            case ANGLE -> 180.0;
        };
    }

    /**
     * 「配合对齐」（cp/description.txt 末尾）：同向对齐 / 反向对齐。
     *
     * <p>只对<b>解算器真的会用 {@code flip}</b> 的类型返回 true —— 这个标志同时决定
     * 「GUI 是否显示切换按钮」和「{@code updateFlip} 是否接受修改」，一旦与解算器不一致，
     * 要么给出一个点了没反应的按钮（垂直），要么让玩家根本改不了（重合）。
     *
     * <ul>
     *   <li>{@link #COINCIDENT}/{@link #PARALLEL}/{@link #TANGENT}/{@link #CONCENTRIC}：方向有意义，支持；</li>
     *   <li>{@link #PERPENDICULAR}：垂直是「两方向正交」，把 B 的方向取反不改变正交性 ⇒ <b>翻转没有意义</b>，
     *       所以这里必须是 false（曾经错标成 true，界面上多了一个无效按钮）；</li>
     *   <li>{@link #ANGLE}：夹角由数值决定，翻转没有意义；</li>
     *   <li>{@link #DISTANCE}/{@link #LOCK}：不约束方向朝向。</li>
     * </ul>
     *
     * <p>注意这只是「类型层面」的资格；重合还要两侧参考都带方向才有意义，
     * 所以调用方应该用 {@code Mate#supportsAlignment()}（它把参考也考虑进去）。
     */
    public boolean supportsAlignment() {
        return switch (this) {
            case COINCIDENT, PARALLEL, TANGENT, CONCENTRIC -> true;
            default -> false;
        };
    }

    @Nullable
    public static MateType byId(final String id) {
        for (final MateType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return null;
    }

    /** 供 GUI 排序/显示用的稳定短名。 */
    public String shortName() {
        return this.id.toUpperCase(Locale.ROOT);
    }
}
