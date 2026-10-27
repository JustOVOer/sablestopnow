package com.ovo.sablestopnow.client.model;

/** 模型模式的功能（上边栏标签页里的按钮）。 */
public final class ModelTools {

    /** 没有在跑功能（纯浏览/选中）。 */
    public static final String NONE = "none";
    /** 配合。 */
    public static final String MATE = "mate";
    /** 正视此面。 */
    public static final String NORMAL_TO = "normal_to";
    /** 比例配合（选择 2 个以上面）。 */
    public static final String RATIO = "ratio";
    /** 齿轮配合（按顺序选 4 条线段）。 */
    public static final String GEAR = "gear";
    /** 放置可控施力点。 */
    public static final String FORCE = "force";
    /**
     * 草图模式（SolidWorks 式建模的第一层）。
     *
     * <p>它和其它工具不同：其它工具都是「选几个参考 → 点完成」，
     * 草图是进入一个<b>持续编辑的环境</b>（有自己的一套绘制工具、自己的输入路由）。
     * 所以它的 {@link #minRefs} 是 1（只需要先选中一个结构），
     * 真正的交互全部由 {@code SketchMode} 接管。
     */
    public static final String SKETCH = "sketch";
    /**
     * 拉伸（把一张草图的闭合轮廓变成实体）。
     *
     * <p>与草图一样是"先选一个结构"类工具。它存在的原因是
     * <b>树里点草图节点默认是进编辑环境</b>，没有"选中但未编辑"的状态，
     * 所以深度输入框没有地方可挂 —— 只能单开一个工具，让点草图变成"选为拉伸源"。
     */
    public static final String EXTRUDE = "extrude";

    /** **基准面特征**（item 7）：点一个模型面 + 设距离 ⇒ 新建基准面 ✓ */
    public static final String PLANE = "plane";

    /** **圆角特征**（item 8）：点一条棱 + 设半径 ⇒ 新建圆角 ✓ */
    public static final String FILLET = "fillet";

    /** 上边栏的标签页分组。 */
    public static final String TAB_MATE = "mate";
    public static final String TAB_MODEL = "model";
    public static final String TAB_VIEW = "view";
    /**
     * **验证类功能**的统一入口。
     *
     * <p>用户的规定：以后所有验证/调试用的功能**一律挂在这个标签页下面**，
     * 不许散落到别的页里。理由是这些功能是"造个东西看看对不对"，
     * 混进正常建模流程会让正式功能变得难找、也容易被误当成可用功能。
     */
    public static final String TAB_DEBUG = "debug";

    private ModelTools() {
    }

    public static String titleKey(final String tool) {
        return switch (tool) {
            case MATE -> "model.tool.mate";
            case NORMAL_TO -> "model.tool.normal_to";
            case RATIO -> "model.tool.ratio";
            case GEAR -> "model.tool.gear";
            case FORCE -> "model.tool.force";
            case SKETCH -> "model.tool.sketch";
            case EXTRUDE -> "model.tool.extrude";
            default -> "model.tool.none";
        };
    }

    public static String hintKey(final String tool) {
        return switch (tool) {
            case MATE -> "model.tool.mate.hint";
            case NORMAL_TO -> "model.tool.normal_to.hint";
            case RATIO -> "model.tool.ratio.hint";
            case GEAR -> "model.tool.gear.hint";
            case FORCE -> "model.tool.force.hint";
            case SKETCH -> "model.tool.sketch.hint";
            case EXTRUDE -> "model.tool.extrude.hint";
            default -> "model.tool.none.hint";
        };
    }

    /** 该功能需要的参考数量下限。 */
    public static int minRefs(final String tool) {
        return switch (tool) {
            case MATE -> 2;
            case RATIO -> 3;
            case GEAR -> 4;
            case FORCE, NORMAL_TO, SKETCH -> 1;
            case EXTRUDE -> 1;
            default -> 0;
        };
    }

    /** 该功能需要的参考数量上限（-1 = 不限）。 */
    public static int maxRefs(final String tool) {
        return switch (tool) {
            case MATE, GEAR, NORMAL_TO, FORCE, SKETCH, EXTRUDE -> minRefs(tool);
            case RATIO -> -1;
            default -> 0;
        };
    }

    /** 是否属于「配合」这一类（决定左侧面板显示配合类型表）。 */
    public static boolean isMate(final String tool) {
        return MATE.equals(tool) || RATIO.equals(tool) || GEAR.equals(tool);
    }
}
