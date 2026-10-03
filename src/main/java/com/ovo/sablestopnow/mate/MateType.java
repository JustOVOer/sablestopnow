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
    /** 锁定：保持两个零部件之间的当前相对位置和方向不变。 */
    LOCK("lock", ValueKind.NONE, false),
    /** 距离：使所选项之间保持指定的距离。 */
    DISTANCE("distance", ValueKind.DISTANCE, false),
    /**
     * 角度：使所选项之间保持指定的角度。
     *
     * <p><b>没有「相切」与「同心」</b>：那两个在 SolidWorks 里靠圆柱面/圆锥面/球面才有意义，
     * 而 Minecraft 的物理结构是方块拼的，没有真正的回转面 —— 摆出来只会得到一个永远对不上的
     * 候选类型（选了也没法满足），所以直接不做。需要「两根轴对齐」时用<b>平行</b>代替。
     */
    ANGLE("angle", ValueKind.ANGLE, true),
    /**
     * 限制：允许在指定的<b>距离区间</b>内活动（cp/description.txt 的「限制」）。
     *
     * <p>与距离的区别只在数值模型：距离是「钉在 v 上」（数值做进关节几何，硬锁）；
     * 限制是「钉在 [min, max] 上」——这条轴<b>不锁</b>，交给求解器的区间约束
     * （{@code setLimit(axis, min, max)}，min &lt; max）。这是 limit 的正当用法：
     * 同一条轴既锁又限才是自相矛盾（虚空动能），区间则不会。
     */
    LIMIT("limit", ValueKind.DISTANCE_RANGE, false);

    /** 数值类型。 */
    public enum ValueKind {
        /** 不需要数值。 */
        NONE,
        /** 需要距离（方块，可负）。 */
        DISTANCE,
        /** 需要距离<b>区间</b>（最小值 + 最大值，见 {@link #LIMIT}）。 */
        DISTANCE_RANGE,
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

    /** 是否需要<b>第二个</b>数值（区间类型：最小值 + 最大值）。 */
    public boolean needsSecondValue() {
        return this.valueKind == ValueKind.DISTANCE_RANGE;
    }

    /** 默认数值（距离=0，角度=90°，限制下限=0）。 */
    public double defaultValue() {
        return switch (this.valueKind) {
            case ANGLE -> 90.0;
            case DISTANCE_RANGE -> 0.0;
            default -> 0.0;
        };
    }

    /** 默认的第二个数值（只有区间类型用得上：默认上限）。 */
    public double defaultValue2() {
        return switch (this.valueKind) {
            case DISTANCE_RANGE -> 2.0;
            case ANGLE -> 180.0;
            default -> 0.0;
        };
    }

    /** 数值下限。 */
    public double minValue() {
        return switch (this.valueKind) {
            case NONE -> 0.0;
            case DISTANCE -> -64.0;
            case DISTANCE_RANGE -> -64.0;
            case ANGLE -> 0.0;
        };
    }

    /** 数值上限。 */
    public double maxValue() {
        return switch (this.valueKind) {
            case NONE -> 0.0;
            case DISTANCE -> 64.0;
            case DISTANCE_RANGE -> 64.0;
            case ANGLE -> 180.0;
        };
    }

    /**
     * 「配合对齐 / 反转」（cp/description.txt 末尾 + 玩家要求）。
     *
     * <p>勾上「反转」= 把 B 绕配合面内的轴转 180°（法线取反）—— 就是说，勾选框能让玩家把
     * 其中一个结构「翻个面」再贴上去。这里列出<b>解算器真的会用到 {@code flip}</b> 的类型：
     *
     * <ul>
     *   <li>{@link #COINCIDENT}/{@link #PARALLEL}/{@link #DISTANCE}/{@link #PERPENDICULAR}：支持；</li>
     *   <li>{@link #ANGLE}：夹角由数值决定，翻转没有意义；</li>
     *   <li>{@link #LOCK}：不约束方向朝向。</li>
     * </ul>
     *
     * <p>注意这只是「类型层面」的资格；重合/距离还要两侧参考都带方向才有意义，
     * 所以调用方应该用 {@code Mate#supportsAlignment()}（它把参考也考虑进去）。
     */
    public boolean supportsAlignment() {
        return switch (this) {
            case COINCIDENT, PARALLEL, PERPENDICULAR, DISTANCE, LIMIT -> true;
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
