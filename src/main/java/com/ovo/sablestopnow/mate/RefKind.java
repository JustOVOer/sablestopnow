package com.ovo.sablestopnow.mate;

/**
 * 配合参考的几何类型。
 *
 * <p>分两大类：
 * <ul>
 *   <li><b>方块级</b>（{@link #VERTEX}/{@link #EDGE}/{@link #FACE}）——玩家在配合模式下用视线选取，
 *       锚点落在具体方块的角点/棱/面上（对应 cp/description.txt 里 SolidWorks 的「顶点/边线/面」）。</li>
 *   <li><b>结构级</b>（{@link #BODY_CENTER}/{@link #BODY_AXIS}/{@link #BODY_PLANE}）——从物理结构的
 *       包围盒派生，不依赖具体方块，因此结构被增删方块后依然有效（对应 SolidWorks 的原点/基准轴/基准面）。</li>
 * </ul>
 *
 * <p>{@link #hasDirection()} 决定该参考能否参与「平行/垂直/同心/角度」这类<b>方向类</b>配合；
 * 纯点参考（顶点/中心）只能参与位置类配合。
 */
public enum RefKind {

    /**
     * 方块角点。{@code feature} = 该面上 0..3 的角点序号。
     *
     * <p>⚠ <b>没有方向</b>：顶点解出来就是一个空间点（见 {@code MateFrames.resolveLocal}）。
     * 这里曾经错标成 {@code true}，后果是 {@link MateRef#supports} 会放行「拿顶点做平行配合」，
     * 而过约束检测会按错误的方向集计数（把顶点—顶点重合算成锁了 2 个角向轴），
     * 于是同一对结构上再加一条配合就会被误判为过约束而拒绝。两处都依赖这个标志，必须与解算一致。
     */
    VERTEX(true, false),
    /** 方块棱。{@code feature} = 该面上 0..3 的棱序号。 */
    EDGE(true, true),
    /** 方块面（取面心，法线为方向）。 */
    FACE(true, true),
    /** 结构包围盒中心（纯点）。 */
    BODY_CENTER(false, false),
    /** 结构包围盒中心 + 主轴方向（轴，可作圆柱/旋转轴）。 */
    BODY_AXIS(false, true),
    /** 结构包围盒中心 + 主轴法线（基准面）。 */
    BODY_PLANE(false, true);

    private final boolean blockLevel;
    private final boolean hasDirection;

    RefKind(final boolean blockLevel, final boolean hasDirection) {
        this.blockLevel = blockLevel;
        this.hasDirection = hasDirection;
    }

    /** 是否为玩家视线选取的方块级参考（需要 {@code block}/{@code face} 字段有效）。 */
    public boolean isBlockLevel() {
        return this.blockLevel;
    }

    /** 是否自带方向（法线/棱方向/主轴）。 */
    public boolean hasDirection() {
        return this.hasDirection;
    }

    /** 该参考是否是一个「纯点」（用于位置类配合的分支判断）。 */
    public boolean isPoint() {
        return this == VERTEX || this == BODY_CENTER;
    }

    /** 是否需要 {@code axis} 字段（仅结构级轴向参考）。 */
    public boolean usesAxis() {
        return this == BODY_AXIS || this == BODY_PLANE;
    }

    /** 翻译键后缀：{@code mate.ref.<id>}。 */
    public String id() {
        return this.name().toLowerCase(java.util.Locale.ROOT);
    }
}
