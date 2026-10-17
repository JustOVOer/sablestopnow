package com.ovo.sablestopnow.sketch;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 草图编辑会话：一次"从选基准面到退出草图"的完整状态机。
 *
 * <h2>为什么单独一个类</h2>
 * 编辑草图这件事有一串互相牵制的状态：在哪一步（选面 / 编辑中）、目标结构是谁、
 * 正在编辑哪张草图、当前是什么工具、工具已经收到了几个点。
 * 把这些散在 GUI 代码里，就会变成「切换工具时忘了清掉半个矩形」这类 bug 的温床
 * （本项目在配合系统上已经踩过一次：取消工具没清干净选择）。集中成一个状态机之后，
 * 每个操作都是一个明确的状态迁移，可以脱离游戏整体测。
 *
 * <h2>自动约束</h2>
 * 仿 SolidWorks 的手感：画线时如果方向接近水平/竖直就**吸附并自动加约束**，
 * 画矩形时自动补齐四条边、四个重合、两组水平竖直。这样玩家随手画的东西
 * 就已经是"规整"的，而不是靠事后手动加十几个约束。
 *
 * <p>⚠ 这个类不引用任何 Minecraft 类，坐标一律是平面内的 {@code (u, v)}，
 * 因此可以用 {@code tools/SketchModelCheck.java} 整体验证。
 */
public final class SketchSession {

    /** 会话处于哪一步。 */
    public enum Stage {
        /** 没在编辑。 */
        IDLE,
        /** 已选定结构，正在等玩家挑一个基准面或方块面。 */
        CHOOSING_PLANE,
        /** 正在编辑某张草图。 */
        EDITING
    }

    /** 绘制工具。 */
    public enum Tool {
        /** 智能尺寸（对齐 SolidWorks 的 Smart Dimension ✓）。ABI v4 起可落成真约束 ✓。 */
        DIMENSION,
        /** 只选择，不画东西。 */
        SELECT,
        /** 直线：两次点击。 */
        LINE,
        /** 矩形：两次点击（对角）。 */
        RECTANGLE,
        /** 圆：两次点击（圆心 + 半径上一点）。 */
        CIRCLE,
        /**
         * 圆弧：**三次点击**（圆心 → 起点 → 终点）。
         *
         * <p>选这个输入法（而不是三点定弧）是为了**表示唯一**：
         * 圆心+起终点直接给出「从 a0 逆时针扫到 a1」，不必靠"走哪边"消歧义。
         */
        ARC
    }

    /**
     * 自动吸附的角度阈值（度）。
     *
     * <p>3° 是手感与"别乱吸"之间的折中：手指抖一下画出来的线也就偏 1~2°，
     * 而真想画斜线的人不会只斜 3°。
     */
    private static final double SNAP_DEGREES = 3.0;

    private Stage stage = Stage.IDLE;
    private UUID body;
    private Sketch working;
    private Tool tool = Tool.SELECT;
    /** 当前工具已经点下的点，每项是 {@code {u, v}}。 */
    private final List<double[]> pending = new ArrayList<>();

    /** 当前选中的图元（加约束/标尺寸的目标）；没有则 null。 */
    @Nullable
    private Sketch.Entity selectedEntity;

    /**
     * 智能尺寸工具：**锁定的候选序号** ✓（-1 = 没锁 ⇒ 跟随"离指针最近"的候选自动捕捉 ✓）。
     *
     * <p>SolidWorks 里右键可以锁定对齐方式（平行/水平/竖直 ✓）✓ 锁定后移动指针方位不再变 ✓
     * ⇒ 数据上就是一个"锁定索引"✓ 由 UI 写入 ✓ 由预览读取 ✓。
     */
    private int lockedVariant = -1;

    /** 智能尺寸工具：当前指针位置（草图 uv ✓ 由 UI 每帧推入 ✓ 供预览与捕捉用 ✓）。 */
    private double hoverU;
    private double hoverV;

    /** 起点吸附到谁了（画完图元时据此加约束）。 */
    @Nullable
    private KeyPoint pendingSnap;

    public Stage stage() {
        return stage;
    }

    public UUID body() {
        return body;
    }

    /** 正在编辑的草图（{@link Stage#EDITING} 时非空）。 */
    public Sketch working() {
        return working;
    }

    /** 锁定的候选序号；-1 表示未锁定（跟随自动捕捉）✓。 */
    public int lockedVariant() {
        return this.lockedVariant;
    }

    /** 锁定到某个候选（右键锁定 ✓）；传 -1 取消锁定 ✓。 */
    public void lockVariant(final int index) {
        this.lockedVariant = index;
    }

    /** 取消锁定（换选择/换工具时调用 ✓）。 */
    public void clearVariantLock() {
        this.lockedVariant = -1;
    }

    public double hoverU() {
        return this.hoverU;
    }

    public double hoverV() {
        return this.hoverV;
    }

    /** 由 UI 每帧推入指针的草图坐标 ✓。 */
    public void setHover(final double u, final double v) {
        this.hoverU = u;
        this.hoverV = v;
    }

    /**
     * **通用落约束**：按"智能尺寸推出来的那个候选"落一条约束 ✓（含过定义拦截 ✓）。
     *
     * <h2>为什么要有这个通用入口 ✗✓</h2>
     * 以前的 {@code dimensionEntity}/{@code dimensionBetween} 只会落 {@code DISTANCE} 与 {@code RADIUS} ✗
     * ⇒ ABI v4 新增的十几种（点线距 / 线线距 / 水平竖直 / 夹角 / 弧 … ✓）**没有落地的路** ✗。
     * 有了它 ⇒ 面板只需把"当前候选"交给它 ✓ 不必为每种尺寸写一条分支 ✓✓。
     *
     * <h2>过定义拦截 ✓（SolidWorks 的"过定义警告"✓）</h2>
     * 同几何 + 同种约束已存在 ⇒ **不新建** ✗ 而是**改掉那一条的值** ✓✓（= 编辑已有尺寸 ✓）。
     * 依据 ✓：离线实测发现上游会把"同几何同种约束"的不同取值合流成一条 ✗
     * ⇒ 再塞一条会被**静默忽略** ✗ ⇒ 玩家以为标上了 ✓ 所以必须我们拦 ✓。
     *
     * @param kind  {@link SketchSolver} 的约束类型 ✓
     * @param value 目标值 ✓（角度类单位为**度** ✓）
     * @param vars  该约束的变量表 ✓（与 shim 的 kind 表一致 ✓）
     * @return true = 落成了（新建或改值 ✓）；false = 没动（不可表达 / 参数不对 ✓）
     */
    /**
     * 找到"同 kind 同操作数"的约束下标 ✓；没有则 -1 ✓。
     *
     * <p>为什么需要它 ✗✓：`dimensionWith` 在**过定义拦截**时会**替换**已有约束（数量不增 ✓），
     * 调用方若用"constraints().size()-1"当新约束下标 ⇒ 会指到**别的**约束上 ✗
     * ⇒ 表现就是"改值确定后几何没有改变"（用户实测 ✓）。
     */
    public int dimensionIndexOf(final int kind, final int[] vars) {
        if (this.working == null || vars == null) {
            return -1;
        }
        final java.util.List<Sketch.Constraint> list = this.working.constraints();
        for (int i = 0; i < list.size(); i++) {
            final Sketch.Constraint c = list.get(i);
            if (c.kind() == kind && sameOperands(c.operands(), vars)) {
                return i;
            }
        }
        return -1;
    }

    public boolean dimensionWith(final int kind, final double value, final int[] vars) {
        if (this.working == null || vars == null || vars.length == 0 || kind == 0) {
            return false;
        }
        // ① 已有同几何同种约束 ⇒ 改它的值（编辑已有尺寸 ✓ 而不是新建 ✗）
        for (int i = 0; i < this.working.constraints().size(); i++) {
            final Sketch.Constraint c = this.working.constraints().get(i);
            if (c.kind() != kind || c.operands().length != vars.length) {
                continue;
            }
            if (sameOperands(c.operands(), vars)) {
                // 注意 ✓ replaceConstraint 返回 void ✗ 而 solve() 不在 Sketch 上 ✗
                // ⇒ 这里只改值 ✓ 求解交给上层（UI 落完后统一 mode.solve() ✓ 与既有流程一致 ✓）
                this.working.replaceConstraint(i, new Sketch.Constraint(kind, vars.clone(), value));
                return true;
            }
        }
        // ② 没有 ⇒ 新建 ✓
        this.working.addConstraint(kind, value, vars.clone());
        return true;
    }

    /** 两组变量是不是同一组（顺序无关 ✓）。 */
    private static boolean sameOperands(final int[] a, final int[] b) {
        if (a.length != b.length) {
            return false;
        }
        final boolean[] used = new boolean[b.length];
        for (final int x : a) {
            boolean found = false;
            for (int i = 0; i < b.length; i++) {
                if (!used[i] && b[i] == x) {
                    used[i] = true;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /**
     * **从动（参考）尺寸模式** ✓（SolidWorks：驱动尺寸可以勾成"从动"✓ 只显示、不驱动几何 ✓）。
     *
     * <p>语义落地 ✓：开启时"标注"**不落约束** ✗；若该测量**已经有**约束 ⇒ **删掉它** ✓
     * ⇒ 于是它退成 {@code referenceDimensions} 里的参考尺寸 ✓ 灰 + 括号 ✓✓（不需要新数据结构 ✓）。
     */
    private boolean referenceMode;

    /**
     * **吸附即约束** ✓（用户要求 ✓ 对齐 SolidWorks 的"自动添加约束"✓ 默认开 ✓ 可关 ✗）。
     *
     * <p>关掉之后吸附仍然只是"落点对齐"✓ 但不写约束 ✓ —— 想画自由尺寸时会需要 ✓。
     */
    private boolean snapConstraints = true;

    /**
     * **尺寸链**模式 ✓（SolidWorks 的 Baseline 尺寸 ✓ 需求 (3) 的一条 ✓）。
     *
     * <p>语义 ✓：放置一条尺寸后**不清空选择** ✓ ⇒ 下一个被点的图元会**加到同一个基准上** ✓
     * ⇒ 于是"基准线 → 后面每个图元"依次成链 ✓（点空白放置 ✓ 点图元加选 ✓ 与已有手势完全一致 ✓）。
     */
    private boolean chainMode;

    /**
     * **辅助线绘制模式** ✓（用户要求 ✓ 直接绘制辅助线，而不是把已有图元"转"成辅助线 ✗）。
     *
     * <p>开启后，**下一个画出来的图元直接就是辅助线** ✓（与普通直线同一个工具 ✓ 只是标记不同 ✓）。
     */
    private boolean constructionDraw;

    public boolean constructionDraw() {
        return this.constructionDraw;
    }

    public void setConstructionDraw(final boolean value) {
        this.constructionDraw = value;
    }

    /**
     * 若开着**辅助线绘制模式** ⇒ 把**刚创建的图元**直接标记为辅助线 ✓
     *（用户要求：直接绘制辅助线 ✓ 不是把已有图元转过去 ✗）。
     */
    private void markConstructionIfDrawing() {
        if (!this.constructionDraw || this.working == null) {
            return;
        }
        final java.util.List<Sketch.Entity> all = this.working.entities();
        if (!all.isEmpty()) {
            this.working.toggleConstruction(all.get(all.size() - 1));
        }
    }

    public boolean chainMode() {
        return this.chainMode;
    }

    public void setChainMode(final boolean value) {
        this.chainMode = value;
    }

    /**
     * 当前**选中的约束**下标 ✓（-1 = 没选中 ✓）—— 用户点图元旁的小图标选中它 ✓ 再删掉 ✓
     * （SolidWorks：点几何关系图标 ⇒ 高亮 ⇒ Delete 删除 ✓）。
     */
    private int selectedConstraint = -1;

    public int selectedConstraint() {
        return this.selectedConstraint;
    }

    public void selectConstraint(final int index) {
        this.selectedConstraint = index;
    }

    /** 删掉当前选中的约束 ✓；返回是否真的删了 ✓。 */
    public boolean deleteSelectedConstraint() {
        if (this.working == null || this.selectedConstraint < 0
                || this.selectedConstraint >= this.working.constraints().size()) {
            this.selectedConstraint = -1;
            return false;
        }
        final Sketch.Constraint c = this.working.constraints().get(this.selectedConstraint);
        this.selectedConstraint = -1;
        return this.working.removeConstraint(c);
    }

    public boolean snapConstraints() {
        return this.snapConstraints;
    }

    public void setSnapConstraints(final boolean value) {
        this.snapConstraints = value;
    }

    public boolean referenceMode() {
        return this.referenceMode;
    }

    public void setReferenceMode(final boolean value) {
        this.referenceMode = value;
    }

    public Tool tool() {
        return tool;
    }

    /** 已点下的点（渲染预览用）。 */
    public List<double[]> pendingPoints() {
        return List.copyOf(pending);
    }

    public boolean isEditing() {
        return stage == Stage.EDITING;
    }

    // ------------------------------------------------------------------
    // 状态迁移
    // ------------------------------------------------------------------

    /** 选定结构，开始挑基准面。会清掉上一次的编辑状态。 */
    public void beginPlaneChoice(final UUID body) {
        this.body = body;
        this.working = null;
        this.tool = Tool.SELECT;
        this.clearVariantLock();
        this.pending.clear();
        this.stage = Stage.CHOOSING_PLANE;
    }

    /**
     * 挑好了平面：新建一张草图并进入编辑。
     *
     * @return 新建的草图
     */
    public Sketch choosePlane(final SketchPlane plane, final String name) {
        if (stage != Stage.CHOOSING_PLANE || body == null) {
            throw new IllegalStateException("还没选定结构，不能挑基准面");
        }
        // ★ 原点取平面自己的原点 ✓ —— 但**必须对到方块网格的交点**上 ✗✓
        //
        // 原实现直接用 plane.origin()：方块面的原点是**面中心** ✗
        // ⇒ 相对方块网格在 u、v 两个方向都差 **0.5 格**（用户实测反馈：
        //    "网格在平面的两个方向上都偏离了 0.5 格" ✓）—— 吸附目标是整数格点 ✓
        //    于是"画出来的东西"和"看得见的网格"整体错半格 ✓。
        // 修法 ✓：把原点沿基准面的两个轴各自**移掉小数部分** ✓
        //    ⇒ 草图的 (0,0) 落在网格交点上 ✓（轴对齐的面 u/v 就是世界轴 ✓ 有效 ✓）。
        //    ⚠ 只影响**新建**草图 ✓：已保存的草图保留自己的原点 ✓ 不会被搬动 ✓。
        final Vector3d rawOrigin = new Vector3d(plane.origin());
        final double ou = rawOrigin.dot(plane.axisU());
        final double ov = rawOrigin.dot(plane.axisV());
        final Vector3d origin = rawOrigin
                .fma(-(ou - Math.round(ou)), plane.axisU(), new Vector3d())
                .fma(-(ov - Math.round(ov)), plane.axisV(), new Vector3d());
        this.working = new Sketch(UUID.randomUUID(), body, name,
                new SketchPlane(origin, plane.axisU(), plane.axisV()));
        this.tool = Tool.SELECT;
        this.clearVariantLock();
        this.pending.clear();
        this.stage = Stage.EDITING;
        return this.working;
    }

    /** 编辑一张已有的草图。 */
    public void edit(final Sketch sketch) {
        this.body = sketch.body();
        this.working = sketch;
        this.tool = Tool.SELECT;
        this.clearVariantLock();
        this.pending.clear();
        this.stage = Stage.EDITING;
    }

    public void setTool(final Tool value) {
        this.tool = value;
        // 换工具必须丢掉上一半的输入，否则会出现"用直线的第一个点点出一个矩形"
        this.pending.clear();
        // 选中也要清掉：换了工具之后，那个"选中"已经不是当前操作的目标了，
        // 留着会让「加约束」作用到一个玩家以为已经取消的对象上。
        // ⚠ 必须清**整个选择集**，不能只置空 selectedEntity —— 那会留下一个
        // "集合里还有东西、但 selectedEntity 是 null"的分裂状态。
        clearSelection();
    }

    // ------------------------------------------------------------------
    // 标注尺寸（SolidWorks 的「智能尺寸」）
    // ------------------------------------------------------------------

    /**
     * 给一个图元标注尺寸。
     *
     * <ul>
     *   <li>{@code LINE} —— 两端点距离（{@code DISTANCE}）</li>
     *   <li>{@code CIRCLE} —— 半径（{@code RADIUS}）</li>
     *   <li>{@code POINT} —— 标不了（点没有长度/半径），返回 null</li>
     * </ul>
     *
     * <p>尺寸也**只是约束**（带常量的那种），不另立一套数据 —— 这样求解器只认一种东西，
     * {@code toOps}/{@code toConsts} 也不用为"尺寸"再分一条支路。
     *
     * @return 新加的尺寸约束；不在编辑状态或该图元标不了尺寸时返回 null
     */
    @Nullable
    public Sketch.Constraint addDimension(final Sketch.Entity entity, final double value) {
        if (working == null) {
            return null;
        }
        final int[] v = entity.vars();
        return switch (entity.kind()) {
            case LINE -> working.addConstraint(SketchSolver.DISTANCE, value, v[0], v[1], v[2], v[3]);
            case CIRCLE -> working.addConstraint(SketchSolver.RADIUS, value, v[0], v[1], v[2]);
            // 圆弧没有"半径变量"了（半径 = hypot(起点 − 圆心) ✓ 见 Sketch.EntityKind.ARC ✓）
            // ⇒ 用求解器按 6 个操作数定义的 ARC_RADIUS（kind 19 ✓）✓
            case ARC -> working.addConstraint(SketchSolver.ARC_RADIUS, value,
                    v[0], v[1], v[2], v[3], v[4], v[5]);
            case POINT -> null;
        };
    }

    /**
     * 把**测量出来的距离**落成一条真约束（左栏"标注"按钮走这条路）。
     *
     * <h2>⚠ 只有"两点之间"的距离能用 DISTANCE 表达</h2>
     * {@code SketchSolver.DISTANCE} 的定义是 4 个操作数 = **两个点的坐标**。所以能落的只有：
     * <ul>
     *   <li>点 ↔ 点</li>
     *   <li>点 ↔ 圆/弧的**圆心**（圆心就是个点）</li>
     * </ul>
     * **表达不了的**：平行线间距、点到线的垂距、两圆表面间隙、不平行线段的最近点距、任意两条线之间。
     * 这些硬塞一个 DISTANCE 会变成"两条线的**端点**之间的距离"，**语义完全不同** ——
     * 等于把一个假的约束锁进草图，比不加还糟。
     *
     * @return 真的落上了才返回 true；表达不了时返回 false（UI 据此告诉玩家"只能测量"）
     */
    public boolean dimensionBetween(final Sketch.Entity a, final Sketch.Entity b,
                                    final double value) {
        if (working == null || a == null || b == null || a.equals(b)) {
            return false;
        }
        final int[] pa = centreVars(a);
        final int[] pb = centreVars(b);
        if (pa == null || pb == null) {
            return false; // 有一边不是"一个点"（比如线段没有单一代表点）
        }
        working.addConstraint(SketchSolver.DISTANCE, value, pa[0], pa[1], pb[0], pb[1]);
        return true;
    }

    /**
     * 给**一个**图元标注尺寸（智能尺寸最常见的一步：点一条线标长度、点一个圆标半径）。
     *
     * <ul>
     *   <li>直线 → 它两个端点之间的 {@code DISTANCE}（**恰好等于它的长度**，不是近似）</li>
     *   <li>圆 → {@code RADIUS}（kind 8，半径是个变量）；弧 → {@code ARC_RADIUS}
     *       （kind 19，半径由圆心+起点算出来，没有半径变量）</li>
     *   <li>点 → 返回 false（点没有"尺寸"，它的位置由别的尺寸决定）</li>
     * </ul>
     *
     * <p>⚠ 圆在 SolidWorks 里默认标**直径**，但我们的求解器只有 {@code RADIUS} ——
     * 所以这里标半径。要显示直径是显示层的事（乘 2），**不能让约束语义和显示不一致**。
     *
     * @return 真的落上了才返回 true
     */
    public boolean dimensionEntity(@Nullable final Sketch.Entity entity, final double value) {
        if (working == null || entity == null || !Double.isFinite(value)) {
            return false;
        }
        final int[] v = entity.vars();
        switch (entity.kind()) {
            case LINE -> {
                if (v.length < 4) {
                    return false;
                }
                working.addConstraint(SketchSolver.DISTANCE, Math.abs(value),
                        v[0], v[1], v[2], v[3]);
                return true;
            }
            case CIRCLE -> {
                if (v.length < 3) {
                    return false;
                }
                working.addConstraint(SketchSolver.RADIUS, Math.abs(value), v[0], v[1], v[2]);
                return true;
            }
            case ARC -> {
                // 弧的"尺寸"仍然是半径 ✓ 但约束种类不同：弧没有半径变量 ✓
                // ⇒ ARC_RADIUS（kind 19 ✓ 操作数 = 圆心 xy + 起点 xy + 终点 xy ✓）
                if (v.length < 6) {
                    return false;
                }
                working.addConstraint(SketchSolver.ARC_RADIUS, Math.abs(value),
                        v[0], v[1], v[2], v[3], v[4], v[5]);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * 补一条**推测出来**的几何关系（`SketchMeasure.impliedRelations` 的结果）。
     *
     * <p>⚠ 加之前要**再查一遍是否已经存在同种约束**：推测是上一次刷新时算的，
     * 玩家可能在之后又加过一条。重复加同种约束会让求解器报冗余（我们的检测会把它标红），
     * 玩家会以为是自己操作错了。
     */
    public boolean applyRelation(final int kind, final int[] operands) {
        if (working == null || operands == null) {
            return false;
        }
        for (final Sketch.Constraint c : working.constraints()) {
            if (c.kind() != kind) {
                continue;
            }
            final int[] ops = c.operands();
            if (ops.length == operands.length && java.util.Arrays.equals(ops, operands)) {
                return false; // 已经有一条一模一样的
            }
        }
        working.addConstraint(kind, 0.0, operands);
        return true;
    }

    /**
     * 图元如果是"一个点"，返回它的两个变量下标；否则 null。
     *
     * <p>点是自己；圆和弧用**圆心**；线段的两个端点地位对等，没有单一代表点，所以返回 null。
     */
    @Nullable
    private static int[] centreVars(final Sketch.Entity entity) {
        final int[] v = entity.vars();
        return switch (entity.kind()) {
            case POINT, CIRCLE, ARC -> new int[] {v[0], v[1]};
            case LINE -> null;
        };
    }

    /** 某个图元上已有的尺寸（UI 显示用）。 */
    public List<Sketch.Constraint> dimensionsOf(final Sketch.Entity entity) {
        final List<Sketch.Constraint> out = new ArrayList<>();
        if (working == null) {
            return out;
        }
        final int first = entity.vars()[0];
        for (final Sketch.Constraint c : working.constraints()) {
            // 只认吃常量的那些（= 尺寸）。按"操作数里含该图元的第一个变量"认领：
            // 图元的变量是连续分配的，首变量对上就一定是它。
            if (!SketchSolver.takesConstant(c.kind())) {
                continue;
            }
            for (final int operand : c.operands()) {
                if (operand == first) {
                    out.add(c);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 改一个已有尺寸的值（双击尺寸数字改大小）。
     *
     * <p>{@code Constraint} 是 record（不可变），所以是**原位替换**。
     * 原位很关键：删掉再追加会让这条约束排到列表末尾，于是约束顺序变了 ——
     * 而 {@code toOps} 与 {@code toConsts} 是**按同一个顺序**生成的，
     * 一个换位置两个都得换，很容易只改一边。
     *
     * @return 是否找到了这个尺寸
     */
    public boolean setDimensionValue(final Sketch.Constraint target, final double value) {
        if (working == null || !SketchSolver.takesConstant(target.kind())) {
            return false;
        }
        final List<Sketch.Constraint> list = working.constraints();
        for (int i = 0; i < list.size(); i++) {
            final Sketch.Constraint c = list.get(i);
            if (c.kind() == target.kind()
                    && java.util.Arrays.equals(c.operands(), target.operands())) {
                working.replaceConstraint(i, new Sketch.Constraint(c.kind(), c.operands(), value));
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 拾取
    // ------------------------------------------------------------------

    /**
     * 按平面内坐标找最近的图元（给「加约束 / 标尺寸」选目标用）。
     *
     * <p>容差是**平面内距离**（格）。三种图元的"距离"定义不同：
     * <ul>
     *   <li>{@code POINT} —— 到点的距离</li>
     *   <li>{@code LINE} —— 到**线段**的距离（不是到无限直线的距离，
     *       否则在延长线上很远处也会把它选出来）</li>
     *   <li>{@code CIRCLE} —— 到**圆周**的距离（{@code |dist(圆心) − r|}），
     *       圆内部不算命中 —— 与 SolidWorks 一致，点空白处不该选中圆</li>
     * </ul>
     *
     * @return 最近的图元；容差内没有则返回 null
     */
    @Nullable
    public Sketch.Entity pickEntity(final double u, final double v, final double tolerance) {
        if (working == null) {
            return null;
        }
        Sketch.Entity best = null;
        double bestDistance = tolerance;
        for (final Sketch.Entity entity : working.entities()) {
            final int[] e = entity.vars();
            final double distance = switch (entity.kind()) {
                // ★ 点比线/圆**更难点中** ✓（它只有一个位置 ✓ 没有"沿线"的方向可利用 ✓）
                //   ⇒ 给它更大的命中容差 ✓（用户反馈："点也要能像线一样被选中" ✓）
                case POINT -> Math.hypot(u - working.variable(e[0]), v - working.variable(e[1]))
                        * 0.75;
                case LINE -> distanceToSegment(u, v,
                        working.variable(e[0]), working.variable(e[1]),
                        working.variable(e[2]), working.variable(e[3]));
                case CIRCLE -> Math.abs(Math.hypot(u - working.variable(e[0]),
                        v - working.variable(e[1])) - Math.abs(working.variable(e[2])));
                // 圆弧：不能像圆那样只看"到圆周的距离" —— 那样点在弧的**反面延长线**上也会被选中。
                // 取"到这个弧的折线近似"的距离（折线段数够密，玩家看不出它是折线）。
                case ARC -> distanceToArc(u, v, working, entity);
            };
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    /**
     * 点到圆弧的距离：取这个弧的**折线近似**上的最近距离。
     *
     * <p>⚠ 不能照搬圆那套"到圆周的距离"：圆的整圈都是实体，而圆弧只有一段 ——
     * 用圆周距离的话，点在弧的**反面延长线**上也会被判为"点中了"。
     */
    private static double distanceToArc(final double px, final double py, final Sketch sketch,
                                        final Sketch.Entity arc) {
        final int segments = 24;
        double best = Double.MAX_VALUE;
        double[] prev = sketch.arcPoint(arc, 0.0);
        for (int i = 1; i <= segments; i++) {
            final double[] cur = sketch.arcPoint(arc, (double) i / segments);
            if (prev == null || cur == null) {
                return Double.MAX_VALUE;
            }
            best = Math.min(best, distanceToSegment(px, py, prev[0], prev[1], cur[0], cur[1]));
            prev = cur;
        }
        return best;
    }

    /** 点到线段的距离。 */
    private static double distanceToSegment(final double px, final double py,
                                            final double ax, final double ay,
                                            final double bx, final double by) {
        final double dx = bx - ax;
        final double dy = by - ay;
        final double lenSq = dx * dx + dy * dy;
        if (lenSq < 1.0e-18) {
            return Math.hypot(px - ax, py - ay); // 退化成点
        }
        // 把参数夹到 [0,1] —— 这就是"线段"与"无限直线"的区别
        double t = ((px - ax) * dx + (py - ay) * dy) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    /**
     * 选择集（SolidWorks 式 Ctrl 多选）。
     *
     * <p>⚠ **这里才是真相**：{@link #selectedEntity} 只是它的第一个元素，
     * 是单选时代留下的兼容访问器。两者只在 {@link #selectEntity} / {@link #toggleEntity} /
     * {@link #clearSelection} / {@link #removeSelected} 这四个入口里一起改 ——
     * 别在别处单独动其中一个，那会让"选中的到底是哪些"出现两个答案。
     */
    private final java.util.List<Sketch.Entity> selection = new java.util.ArrayList<>();

    /** 当前选中的图元（单选时代的目标；等于选择集的第一个）。 */
    @Nullable
    public Sketch.Entity selectedEntity() {
        return selectedEntity;
    }

    /** 选择集（只读快照）。顺序 = 玩家点选的先后顺序。 */
    public java.util.List<Sketch.Entity> selection() {
        return java.util.List.copyOf(selection);
    }

    /** 某个图元是否被选中。渲染高亮用。 */
    public boolean isSelected(@Nullable final Sketch.Entity entity) {
        return entity != null && selection.contains(entity);
    }

    public int selectionSize() {
        return selection.size();
    }

    /** 单选：**替换**整个选择集（普通左键点一下的语义）。传 null 清空。 */
    public void selectEntity(@Nullable final Sketch.Entity entity) {
        selection.clear();
        if (entity != null) {
            selection.add(entity);
        }
        selectedEntity = entity;
    }

    /**
     * Ctrl 点击：在集合里**增删**（已选中就取消，没选中就加入）。
     *
     * <p>这是多选唯一的入口 —— 普通点击仍然是单选替换，
     * 否则玩家点一下想选一条线，结果把之前的全留着，加约束时才发现选了一堆。
     */
    public void toggleEntity(@Nullable final Sketch.Entity entity) {
        if (entity == null) {
            return;
        }
        if (!selection.remove(entity)) {
            selection.add(entity);
        }
        selectedEntity = selection.isEmpty() ? null : selection.get(0);
    }

    /** 清空选择。切工具、退出编辑时用。 */
    public void clearSelection() {
        selection.clear();
        selectedEntity = null;
    }

    // ==================================================================
    // 镜像
    // ==================================================================

    /**
     * 把选中的图元关于一条**轴**镜像，返回新建的图元数。
     *
     * <h2>为什么镜像要新建变量，而不是共享变量</h2>
     * 共享变量的两个图元会**永远一起动**，看起来像"镜像了但改一边另一边也变"——
     * 那是"对称约束"的语义，不是"镜像一份"的语义。
     * SolidWorks 的镜像默认也是生成独立几何（要联动得另外加对称关系）。
     * 所以这里**一律新建变量**：镜像的结果是一份**独立的**副本。
     *
     * <h2>圆弧要特别处理</h2>
     * 反射会**反转朝向**：原来的起点、终点镜像之后，从新起点逆时针走到新终点的那一段
     * 正好是**补弧**。所以新圆弧的起点取**原终点**的镜像、终点取**原起点**的镜像
     * （交叉换）——只换一个的话圆弧会画成反的那一段。
     * 端点是独立变量（{@code sx sy ex ey}）⇒ 直接反射两个端点即可，不必再绕角度算。
     *
     * @param axisEntity 作为镜像轴的图元（必须是一条**直线**）；null 或不是直线时返回 0
     * @return 新建的图元数
     */
    public int mirrorSelection(@Nullable final Sketch.Entity axisEntity) {
        if (axisEntity == null
                || axisEntity.kind() != Sketch.EntityKind.LINE
                || selection.isEmpty()) {
            return 0;
        }
        final int[] av = axisEntity.vars();
        final double ax = working.variable(av[0]);
        final double ay = working.variable(av[1]);
        final double bx = working.variable(av[2]);
        final double by = working.variable(av[3]);
        final double dx = bx - ax;
        final double dy = by - ay;
        final double len2 = dx * dx + dy * dy;
        if (len2 < 1.0e-18) {
            return 0; // 轴退化成一点
        }
        final java.util.List<Sketch.Entity> toMirror = new java.util.ArrayList<>(selection);
        int created = 0;
        for (final Sketch.Entity e : toMirror) {
            // ⚠ 不要镜像轴自己：那会生成一条和轴重合的线，看着像"什么都没发生"
            if (e == axisEntity) {
                continue;
            }
            switch (e.kind()) {
                case LINE -> {
                    final int[] v = e.vars();
                    final double[] p = reflect(working.variable(v[0]), working.variable(v[1]),
                            ax, ay, dx, dy, len2);
                    final double[] q = reflect(working.variable(v[2]), working.variable(v[3]),
                            ax, ay, dx, dy, len2);
                    working.addLine(p[0], p[1], q[0], q[1]);
                    markConstructionIfDrawing(); // ★ 直接绘制辅助线 ✓（用户要求 ✓）
                    created++;
                }
                case CIRCLE -> {
                    final int[] v = e.vars();
                    final double[] c = reflect(working.variable(v[0]), working.variable(v[1]),
                            ax, ay, dx, dy, len2);
                    final double r = Math.abs(working.variable(v[2]));
                    working.addCircle(c[0], c[1], r);
                    markConstructionIfDrawing(); // ★ 直接绘制辅助线 ✓（用户要求 ✓）
                    created++;
                }
                case ARC -> {
                    final int[] v = e.vars();
                    final double[] c = reflect(working.variable(v[0]), working.variable(v[1]),
                            ax, ay, dx, dy, len2);
                    // 交叉换：新起点 ← 原**终点**的镜像，新终点 ← 原**起点**的镜像
                    // （反射反转朝向，不换的话画出来的是补弧）
                    final double[] ns = reflect(working.variable(v[4]), working.variable(v[5]),
                            ax, ay, dx, dy, len2);
                    final double[] ne = reflect(working.variable(v[2]), working.variable(v[3]),
                            ax, ay, dx, dy, len2);
                    working.addArcPoints(c[0], c[1], ns[0], ns[1], ne[0], ne[1]);
                    markConstructionIfDrawing(); // ★ 直接绘制辅助线 ✓（用户要求 ✓）
                    created++;
                }
                case POINT -> {
                    final int[] v = e.vars();
                    final double[] p = reflect(working.variable(v[0]), working.variable(v[1]),
                            ax, ay, dx, dy, len2);
                    working.addPoint(p[0], p[1]);
                    markConstructionIfDrawing(); // ★ 直接绘制辅助线 ✓（用户要求 ✓）
                    created++;
                }
                default -> {
                    // 以后加了新图元类型，这里会**静默漏掉** —— 但那比抛异常好：
                    // 镜像一部分总好过整个操作失败。真正的护栏是断言（新类型要补进来）。
                }
            }
        }
        return created;
    }

    /**
     * 点关于直线（过 A、方向 d）的反射。
     *
     * <p>{@code P' = A + 2·((P−A)·d̂)d̂ − (P−A)}：把 P−A 分解成沿轴与垂直轴两部分，
     * 垂直的那部分取反。
     */
    private static double[] reflect(final double px, final double py,
                                    final double ax, final double ay,
                                    final double dx, final double dy, final double len2) {
        final double wx = px - ax;
        final double wy = py - ay;
        final double t = (wx * dx + wy * dy) / len2;
        final double projX = ax + t * dx;
        final double projY = ay + t * dy;
        // 反射点 = 投影点 + (投影点 − 原点)
        return new double[] {2.0 * projX - px, 2.0 * projY - py};
    }

    /**
     * 删掉当前选中的图元。
     *
     * <p>删完**清空选择**并把吸附目标一并丢掉：选择指向的那个对象已经不在草图里了，
     * 留着它会让左栏显示一个"幽灵选中"，然后下一个约束操作作用在一个不存在的图元上。
     *
     * @return 真的删掉了才返回 true（没选中时返回 false，UI 据此给提示而不是静默无反应）
     */
    public boolean removeSelected() {
        if (selection.isEmpty() || working == null) {
            return false;
        }
        // 多选时**一起删**。逐个删而不是"删完直接清空"：Sketch.removeEntity 会连带
        // 删掉引用该图元的约束，跨图元的重合约束在删第一个时就已经没了，
        // 删第二个时不会重复处理 —— 顺序无关，结果是确定的。
        boolean any = false;
        for (final Sketch.Entity entity : java.util.List.copyOf(selection)) {
            if (working.removeEntity(entity)) {
                any = true;
            }
        }
        if (any) {
            clearSelection();
            pendingSnap = null;
        }
        return any;
    }

    /**
     * 删掉某个图元（树/渲染里点中它时用）。
     *
     * @return 真的删掉了才返回 true
     */
    public boolean removeEntity(final Sketch.Entity entity) {
        if (entity == null || working == null) {
            return false;
        }
        final boolean removed = working.removeEntity(entity);
        if (removed && selection.remove(entity)) {
            selectedEntity = selection.isEmpty() ? null : selection.get(0);
            pendingSnap = null;
        }
        return removed;
    }

    // ------------------------------------------------------------------
    // 关键点拾取（端点/中点/圆心）—— 端点拾取与推理吸附的共同底座
    // ------------------------------------------------------------------

    /** 关键点的种类。吸附提示要靠它告诉玩家"吸到了什么"。 */
    public enum KeyKind {
        /** 线段端点、圆弧端点、独立点。 */
        ENDPOINT("model.sketch.snap.endpoint"),
        /** 线段中点。 */
        MIDPOINT("model.sketch.snap.midpoint"),
        /** 圆心。 */
        CENTER("model.sketch.snap.center"),
        /** 该面的 Minecraft 方块网格（顶点 / 格线 ✓ 用户要求 ✓）。 */
        GRID("model.sketch.snap.grid");

        private final String labelKey;

        KeyKind(final String labelKey) {
            this.labelKey = labelKey;
        }

        /** 提示用的 lang key。 */
        public String labelKey() {
            return labelKey;
        }
    }

    /**
     * 平面内的一个关键点。
     *
     * <p>{@code owner}/{@code px}/{@code py} 是给**吸附升级成约束**用的：
     * 光把落点吸到端点上只是"这一次画得准"，求解器一动就散了；
     * 要真正连在一起，必须加一条把新点绑到老点上的约束。
     * 所以关键点必须知道自己是"谁身上的哪个变量"。
     *
     * <ul>
     *   <li>{@code ENDPOINT} / {@code CENTER} —— {@code px,py} 是该点在草图里的坐标变量下标，
     *       可以加 {@code COINCIDENT}。</li>
     *   <li>{@code MIDPOINT} —— 中点**没有自己的变量**（它是两端的平均），
     *       所以 {@code px,py} 是 -1，要加的是 {@code MIDPOINT(线, 新点)}。</li>
     * </ul>
     */
    public record KeyPoint(double u, double v, KeyKind kind,
                           @Nullable Sketch.Entity owner, int px, int py) {
    }

    /**
     * 找容差内最近的关键点。
     *
     * <p>「关键点」= 端点 / 中点 / 圆心。这是仿 SolidWorks 手感的**底座**：
     * 画线时吸到端点就能自动重合，吸到中点就能自动约束在中点，
     * 而这些都建立在"先能找到离鼠标最近的关键点"之上。
     *
     * <p>同类之间取最近的；跨类不做优先级 —— 距离说了算。硬要给端点更高优先级
     * 反而会让"我明明指着中点却被吸到附近的端点"。
     *
     * @return 容差内最近的关键点；没有则 null
     */
    @Nullable
    public KeyPoint pickKeyPoint(final double u, final double v, final double tolerance) {
        if (working == null) {
            return null;
        }
        KeyPoint best = null;
        double bestDistance = tolerance;
        for (final Sketch.Entity entity : working.entities()) {
            final int[] e = entity.vars();
            switch (entity.kind()) {
                case POINT -> {
                    final KeyPoint kp = new KeyPoint(working.variable(e[0]), working.variable(e[1]),
                            KeyKind.ENDPOINT, entity, e[0], e[1]);
                    final double d = Math.hypot(u - kp.u(), v - kp.v());
                    if (d <= bestDistance) {
                        bestDistance = d;
                        best = kp;
                    }
                }
                case LINE -> {
                    final double ax = working.variable(e[0]);
                    final double ay = working.variable(e[1]);
                    final double bx = working.variable(e[2]);
                    final double by = working.variable(e[3]);
                    // 两个端点（各自有自己的坐标变量，可以加重合约束）
                    final double[][] ends = {{ax, ay}, {bx, by}};
                    final int[][] endVars = {{e[0], e[1]}, {e[2], e[3]}};
                    for (int i = 0; i < 2; i++) {
                        final double d = Math.hypot(u - ends[i][0], v - ends[i][1]);
                        if (d <= bestDistance) {
                            bestDistance = d;
                            best = new KeyPoint(ends[i][0], ends[i][1], KeyKind.ENDPOINT,
                                    entity, endVars[i][0], endVars[i][1]);
                        }
                    }
                    // 中点：没有自己的变量，记 -1（要加的是 MIDPOINT 约束）
                    final double mx = (ax + bx) * 0.5;
                    final double my = (ay + by) * 0.5;
                    final double dm = Math.hypot(u - mx, v - my);
                    if (dm <= bestDistance) {
                        bestDistance = dm;
                        best = new KeyPoint(mx, my, KeyKind.MIDPOINT, entity, -1, -1);
                    }
                }
                case CIRCLE -> {
                    final KeyPoint kp = new KeyPoint(working.variable(e[0]), working.variable(e[1]),
                            KeyKind.CENTER, entity, e[0], e[1]);
                    // ★ 用户要求：**圆心/端点这类"图元上的点"吸附时应当优先** ✓
                    //   做法：距离打个 0.8 折扣 ⇒ 与"线/弧上的普通位置"打平时点会赢 ✓
                    //   （与 `pickEntity` 里"点优先"的处理是同一思路 ✓）
                    final double d = Math.hypot(u - kp.u(), v - kp.v()) * 0.8;
                    if (d <= bestDistance) {
                        bestDistance = d;
                        best = kp;
                    }
                }
                case ARC -> {
                    // 圆心：有自己的变量 → 能加真正的 COINCIDENT（"同心"就是这么表达的）
                    final double cx = working.variable(e[0]);
                    final double cy = working.variable(e[1]);
                    // 圆心同样**优先** ✓（0.8 折扣 ✓ 与圆的圆心一致 ✓）
                    final double dc = Math.hypot(u - cx, v - cy) * 0.8;
                    if (dc <= bestDistance) {
                        bestDistance = dc;
                        best = new KeyPoint(cx, cy, KeyKind.CENTER, entity, e[0], e[1]);
                    }
                    // 两个端点：**也是独立变量** ✓（sx sy ex ey ✓ 见 Sketch.EntityKind.ARC ✓）
                    // ⇒ 吸附之后能落成**真正的** COINCIDENT（constrainToSnap 的 px() >= 0 分支 ✓），
                    // "吸附即约束"从此对弧端点同样成立 —— 以前端点是从属量（由圆心/半径/两个角推出），
                    // 没有可挂的变量，只能吸位置不产生约束（用户实测报过"吸附即约束没生效"✓）。
                    final double[][] ends = working.arcEndpoints(entity);
                    final int[][] endVars = {{e[2], e[3]}, {e[4], e[5]}};
                    if (ends != null) {
                        for (int i = 0; i < ends.length; i++) {
                            final double d = Math.hypot(u - ends[i][0], v - ends[i][1]);
                            if (d <= bestDistance) {
                                bestDistance = d;
                                best = new KeyPoint(ends[i][0], ends[i][1], KeyKind.ENDPOINT,
                                        entity, endVars[i][0], endVars[i][1]);
                            }
                        }
                    }
                }
                default -> {
                }
            }
        }
        return best;
    }

    /**
     * 把落点吸附到最近的关键点；没有可吸的就原样返回。
     *
     * <p>顺带**记下吸到了谁**（{link #pendingSnap}）：图元画完时要拿它加一条真正的约束。
     * 只吸坐标不加约束的话，求解器一动两条线就散了 —— 玩家会看到"明明是接上的却分开了"。
     *
     * @return 吸附后的点（{@code {u, v, kindIndex}}，kindIndex 为 -1 表示没吸到）
     */
    public double[] snap(final double u, final double v, final double tolerance) {
        final KeyPoint kp = pickKeyPoint(u, v, tolerance);
        // ⚠ 只在**第一个点**时记下吸附目标。第二次点击也会调 snap，
        // 若无条件覆盖，就变成"用终点的吸附去约束起点"，约束加错地方。
        if (pending.isEmpty()) {
            this.pendingSnap = kp;
        }
        return kp == null
                ? new double[] {u, v, -1.0}
                : new double[] {kp.u(), kp.v(), kp.kind().ordinal()};
    }

    /**
     * 相对锚点 {@code (au,av)} 做**水平/竖直推理吸附**。
     *
     * <p>抽成静态纯函数是刻意的：预览和真正画线**必须共用这一个实现**。
     * 各写一份的结果就是"预览是一条斜线、松手后变成平线"——
     * 玩家会以为程序在骗他。这个函数没有副作用，悬停时随便调。
     *
     * @return {@code {u, v, flag}}，flag：1=吸成水平，2=吸成竖直，0=没吸
     */
    public static double[] inferOrtho(final double au, final double av,
                                      final double u, final double v) {
        final double dx = u - au;
        final double dy = v - av;
        if (Math.hypot(dx, dy) < 1.0e-9) {
            return new double[] {u, v, 0.0}; // 两点重合，谈不上方向
        }
        final double angle = Math.toDegrees(Math.atan2(Math.abs(dy), Math.abs(dx)));
        if (angle <= SNAP_DEGREES) {
            return new double[] {u, av, 1.0};
        }
        if (angle >= 90.0 - SNAP_DEGREES) {
            return new double[] {au, v, 2.0};
        }
        return new double[] {u, v, 0.0};
    }

    /** 画图元时，把"起点吸到了谁"变成一条真正的约束。 */
    private void constrainToSnap(final int[] newPointVars) {
        final KeyPoint snapped = this.pendingSnap;
        this.pendingSnap = null;
        if (snapped == null || working == null || !this.snapConstraints) {
            return;
        }
        // ★ **网格吸附也要变成约束** ✓（用户要求"吸附即约束"✓）
        //   格点不是图元 ⇒ 没有 owner ✓（以前就在这里被 return 掉了 ✗✓）
        //   语义 ✓：吸到竖向格线 ⇒ 固定 u ✓（v 自由 ⇒ 还能沿线滑动 ✓ 与吸附行为一致 ✓）；
        //           吸到横向格线 ⇒ 固定 v ✓；吸到格点 ⇒ 两个都固定 ✓。
        if (snapped.kind() == KeyKind.GRID) {
            final double ru = Math.round(snapped.u());
            final double rv = Math.round(snapped.v());
            if (Math.abs(snapped.u() - ru) < 1.0e-6) {
                working.addConstraint(SketchSolver.FIXED, ru, newPointVars[0]);
            }
            if (Math.abs(snapped.v() - rv) < 1.0e-6) {
                working.addConstraint(SketchSolver.FIXED, rv, newPointVars[1]);
            }
            return;
        }
        if (snapped.owner() == null) {
            return;
        }
        // 已经属于同一个图元就不加（自己和自己重合是退化约束）
        if (working.entities().contains(snapped.owner())
                && snapped.px() >= 0 && newPointVars[0] == snapped.px()) {
            return;
        }
        if (snapped.kind() == KeyKind.MIDPOINT) {
            // 中点没有自己的变量 —— 要表达"这个点落在那条线的中点上"，用 MIDPOINT 约束
            final int[] line = snapped.owner().vars();
            working.addConstraint(SketchSolver.MIDPOINT, 0.0,
                    line[0], line[1], line[2], line[3], newPointVars[0], newPointVars[1]);
        } else if (snapped.px() >= 0) {
            working.addConstraint(SketchSolver.COINCIDENT, 0.0,
                    newPointVars[0], newPointVars[1], snapped.px(), snapped.py());
        }
    }

    /**
     * 给**当前选中的图元**标注尺寸（左栏「标尺寸」按钮走这条路）。
     *
     * @return 是否成功；没选中、或选中的是点（点没有可标的尺寸）时返回 false
     */
    public boolean dimensionSelected(final double value) {
        if (selectedEntity == null) {
            return false;
        }
        return addDimension(selectedEntity, value) != null;
    }

    /**
     * 改当前选中图元上那条尺寸的值（双击尺寸改数值的模型层）。
     *
     * @return 是否成功；选中图元上没有尺寸时返回 false
     */
    public boolean retypeSelectedDimension(final double value) {
        if (selectedEntity == null) {
            return false;
        }
        for (final Sketch.Constraint c : dimensionsOf(selectedEntity)) {
            return setDimensionValue(c, value);
        }
        return false;
    }

    /**
     * 给**选中的每个图元**加一条几何约束（左栏「水平 / 竖直 / 固定」按钮走这条路）。
     *
     * <p>会先校验"这种约束配不配得上这个图元"：水平/竖直只对线段有意义，
     * 对圆加水平是**没有定义**的。宁可返回 false 让 UI 提示，也不要塞一条
     * 求解器看不懂的约束进去 —— 那会变成"点了没反应"或者更糟的"解出乱七八糟的形状"。
     *
     * <p>多选时**逐个筛**：线段加水平、圆跳过。只要有一条加上了就返回 true
     * （SolidWorks 也是这个行为：混选时约束作用在能接受的元素上）。
     *
     * @return 是否至少加上了一条
     */
    public boolean constrainSelected(final int kind) {
        if (selection.isEmpty() || working == null) {
            return false;
        }
        // ⚠ 水平/竖直/固定是**对单个图元**的约束，不是"元素之间的关系"。
        // 多选时逐条各加一次，既是把同一个约束加两遍（对两条不平行线毫无意义），
        // 也会让玩家以为自己在表达"这两条线的关系"（用户实测反馈）。
        // 多选请走 makeEqual / makeConcentric 这类关系型入口。
        if (selection.size() > 1
                && (kind == SketchSolver.HORIZONTAL || kind == SketchSolver.VERTICAL)) {
            return false;
        }
        boolean any = false;
        for (final Sketch.Entity entity : selection) {
            if (applyConstraint(entity, kind)) {
                any = true;
            }
        }
        return any;
    }

    /**
     * 多选加「等长」：选中的**线段**两两等长（以第一条为基准，逐个加 {@code EQUAL_LEN}）。
     *
     * <p>这才是"元素之间的关系"。⚠ 少于两条线段时返回 false，让 UI 提示而不是静默无反应。
     *
     * @return 是否至少加上了一条
     */
    public boolean makeEqual() {
        if (working == null) {
            return false;
        }
        final List<Sketch.Entity> lines = new ArrayList<>();
        for (final Sketch.Entity e : selection) {
            if (e.kind() == Sketch.EntityKind.LINE) {
                lines.add(e);
            }
        }
        if (lines.size() < 2) {
            return false;
        }
        final int[] base = lines.get(0).vars();
        for (int i = 1; i < lines.size(); i++) {
            final int[] v = lines.get(i).vars();
            working.addConstraint(SketchSolver.EQUAL_LEN, 0.0,
                    base[0], base[1], base[2], base[3], v[0], v[1], v[2], v[3]);
        }
        return true;
    }

    /**
     * 多选加「同心」：选中的**圆/弧**圆心重合（以第一个为基准，逐个加 {@code COINCIDENT}）。
     *
     * <p>用现有的 {@code COINCIDENT} 就够了 —— 圆心本身就是两个变量，
     * 所以"同心"不需要新造一种约束（这也是为什么它属于"现在就能做"的那一类）。
     *
     * @return 是否至少加上了一条
     */
    public boolean makeConcentric() {
        if (working == null) {
            return false;
        }
        final List<Sketch.Entity> rounds = new ArrayList<>();
        for (final Sketch.Entity e : selection) {
            if (e.kind() == Sketch.EntityKind.CIRCLE || e.kind() == Sketch.EntityKind.ARC) {
                rounds.add(e);
            }
        }
        if (rounds.size() < 2) {
            return false;
        }
        final int[] base = rounds.get(0).vars();
        for (int i = 1; i < rounds.size(); i++) {
            final int[] v = rounds.get(i).vars();
            working.addConstraint(SketchSolver.COINCIDENT, 0.0, base[0], base[1], v[0], v[1]);
        }
        return true;
    }

    /** 给单个图元加约束；不适用则返回 false。 */
    private boolean applyConstraint(final Sketch.Entity entity, final int kind) {
        final int[] v = entity.vars();
        switch (kind) {
            case SketchSolver.HORIZONTAL, SketchSolver.VERTICAL -> {
                if (entity.kind() != Sketch.EntityKind.LINE) {
                    return false;
                }
                working.addConstraint(kind, 0.0, v[0], v[1], v[2], v[3]);
                return true;
            }
            case SketchSolver.FIXED -> {
                // 固定：把该图元的每个坐标变量都钉在当前值上（点/线/圆都能固定）
                for (final int index : v) {
                    working.addConstraint(SketchSolver.FIXED, working.variable(index), index);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 给**两个选中图元**加「等长」（只有两条线段才有意义）。 */
    public boolean equalLengthSelected(final Sketch.Entity other) {
        if (selectedEntity == null || working == null
                || selectedEntity.kind() != Sketch.EntityKind.LINE
                || other.kind() != Sketch.EntityKind.LINE) {
            return false;
        }
        // ⚠ 自己和自己等长是**平凡约束**（永远成立），加进去只会干扰求解器。
        // 用"变量集合是否重叠"判断而不是 equals：同一张草图里两个 Entity 记录
        // 只要下标一样就是同一条线，而它们未必是同一个对象。
        final int[] a = selectedEntity.vars();
        final int[] b = other.vars();
        for (final int x : a) {
            for (final int y : b) {
                if (x == y) {
                    return false;
                }
            }
        }
        working.addConstraint(SketchSolver.EQUAL_LEN, 0.0,
                a[0], a[1], a[2], a[3], b[0], b[1], b[2], b[3]);
        return true;
    }

    /** 取消当前工具的进行中状态（Esc）。返回是否真的取消了什么。 */
    public boolean cancelPending() {
        final boolean had = !pending.isEmpty();
        pending.clear();
        if (tool != Tool.SELECT) {
            tool = Tool.SELECT;
        }
        return had;
    }

    /**
     * 结束编辑，返回需要提交给服务端的草图。
     *
     * @return 编辑好的草图；不在编辑状态时返回 null
     */
    public Sketch finish() {
        final Sketch out = working;
        working = null;
        body = null;
        tool = Tool.SELECT;
        pending.clear();
        stage = Stage.IDLE;
        return out;
    }

    /** 放弃编辑（不提交）。 */
    public void abort() {
        working = null;
        body = null;
        tool = Tool.SELECT;
        pending.clear();
        stage = Stage.IDLE;
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    /**
     * 在平面内坐标 {@code (u, v)} 处点一下。
     *
     * @return 是否刚刚完成了一个图元（完成时 pending 会被清空，方便连续画）
     */
    public boolean click(final double u, final double v) {
        if (stage != Stage.EDITING || working == null) {
            return false;
        }
        switch (tool) {
            case LINE -> {
                pending.add(new double[] {u, v});
                if (pending.size() == 2) {
                    addSnappedLine(pending.get(0), pending.get(1));
                    pending.clear();
                    return true;
                }
            }
            case RECTANGLE -> {
                pending.add(new double[] {u, v});
                if (pending.size() == 2) {
                    addConstrainedRectangle(pending.get(0), pending.get(1));
                    pending.clear();
                    return true;
                }
            }
            case CIRCLE -> {
                pending.add(new double[] {u, v});
                if (pending.size() == 2) {
                    addCircleFromTwoPoints(pending.get(0), pending.get(1));
                    pending.clear();
                    return true;
                }
            }
            case ARC -> {
                pending.add(new double[] {u, v});
                if (pending.size() == 3) {
                    addArcFromThreePoints(pending.get(0), pending.get(1), pending.get(2));
                    pending.clear();
                    return true;
                }
            }
            default -> {
                return false;
            }
        }
        return false;
    }

    /**
     * 画一条线；方向接近水平/竖直就吸附并自动加约束。
     *
     * <p>吸附是**改初值**（让线当场就是直的），约束是**加进草图**（让求解器也认这件事）。
     * 只做前者会在下一次求解时被弹回去，只做后者会先歪一下再跳正。
     */
    private void addSnappedLine(final double[] a, final double[] b) {
        double ax = a[0];
        double ay = a[1];
        double bx = b[0];
        double by = b[1];

        if (Math.hypot(bx - ax, by - ay) < 1.0e-9) {
            return; // 点在同一处，画不出线段
        }
        // 水平/竖直推理吸附：与预览共用 inferOrtho，两边永远一致
        final double[] ortho = inferOrtho(ax, ay, bx, by);
        bx = ortho[0];
        by = ortho[1];
        final boolean horizontal = ortho[2] == 1.0;
        final boolean vertical = ortho[2] == 2.0;

        final Sketch.Entity line = working.addLine(ax, ay, bx, by);
        final int[] v = line.vars();
        // 起点吸到了老几何上 → 补一条真约束（只吸坐标的话求解器一动就散了）
        constrainToSnap(new int[] {v[0], v[1]});
        if (horizontal) {
            working.addConstraint(SketchSolver.HORIZONTAL, 0.0, v[0], v[1], v[2], v[3]);
        } else if (vertical) {
            working.addConstraint(SketchSolver.VERTICAL, 0.0, v[0], v[1], v[2], v[3]);
        }
    }

    /**
     * 画矩形：四条边 + 四个重合 + 两组水平竖直。
     *
     * <p>这四个角必须用**重合约束**连起来，不能只靠"坐标刚好相等"——
     * 求解器一跑就会把四条独立的边推散（本项目在离线自检里真的踩过这个：
     * 16 个变量只有 8 个约束，宽直接塌成 0）。
     */
    private void addConstrainedRectangle(final double[] a, final double[] b) {
        final double x0 = Math.min(a[0], b[0]);
        final double x1 = Math.max(a[0], b[0]);
        final double y0 = Math.min(a[1], b[1]);
        final double y1 = Math.max(a[1], b[1]);
        if (x1 - x0 < 1.0e-9 || y1 - y0 < 1.0e-9) {
            return; // 退化成一个点/一条线
        }

        final Sketch.Entity bottom = working.addLine(x0, y0, x1, y0);
        final Sketch.Entity right = working.addLine(x1, y0, x1, y1);
        final Sketch.Entity top = working.addLine(x1, y1, x0, y1);
        final Sketch.Entity left = working.addLine(x0, y1, x0, y0);
        final int[] bt = bottom.vars();
        final int[] rt = right.vars();
        final int[] tp = top.vars();
        final int[] lf = left.vars();

        working.addConstraint(SketchSolver.HORIZONTAL, 0.0, bt[0], bt[1], bt[2], bt[3]);
        working.addConstraint(SketchSolver.VERTICAL, 0.0, rt[0], rt[1], rt[2], rt[3]);
        working.addConstraint(SketchSolver.HORIZONTAL, 0.0, tp[0], tp[1], tp[2], tp[3]);
        working.addConstraint(SketchSolver.VERTICAL, 0.0, lf[0], lf[1], lf[2], lf[3]);
        working.addConstraint(SketchSolver.COINCIDENT, 0.0, bt[2], bt[3], rt[0], rt[1]);
        working.addConstraint(SketchSolver.COINCIDENT, 0.0, rt[2], rt[3], tp[0], tp[1]);
        working.addConstraint(SketchSolver.COINCIDENT, 0.0, tp[2], tp[3], lf[0], lf[1]);
        working.addConstraint(SketchSolver.COINCIDENT, 0.0, lf[2], lf[3], bt[0], bt[1]);
    }

    /** 圆：第一次点是圆心，第二次点决定半径（圆心到该点的距离）。 */
    /**
     * 由「圆心 → 起点 → 终点」三次点击造一段圆弧。
     *
     * <p>半径与起始角由**起点**决定，终止角由**终点**决定（终点到圆心的距离被忽略 ——
     * 玩家点终点时只是在"指方向"）。半径为零就什么都造不出来，直接放弃。
     */
    private void addArcFromThreePoints(final double[] center, final double[] start,
                                       final double[] end) {
        final double r = Math.hypot(start[0] - center[0], start[1] - center[1]);
        if (r < 1.0e-9) {
            return; // 起点压在圆心上，定不出半径
        }
        final double a0 = Math.atan2(start[1] - center[1], start[0] - center[0]);
        final double a1 = Math.atan2(end[1] - center[1], end[0] - center[0]);
        final Sketch.Entity arc = working.addArc(center[0], center[1], r, a0, a1);
        // 圆心吸到了老几何上 → 补一条重合（"同心"就是这么表达的，和圆一样）
        final int[] v = arc.vars();
        constrainToSnap(new int[] {v[0], v[1]});
    }

    /**
     * 画圆：圆心 + 半径上一点。
     *
     * <p>半径取点到圆心的距离（取绝对值是因为半径变量可能被求解器解成负数）。
     */
    private void addCircleFromTwoPoints(final double[] center, final double[] rim) {
        final double r = Math.hypot(rim[0] - center[0], rim[1] - center[1]);
        if (r < 1.0e-9) {
            return;
        }
        final Sketch.Entity circle = working.addCircle(center[0], center[1], r);
        // 圆心吸到了老几何上 → 补一条重合（"同心"就是这么表达的）
        final int[] v = circle.vars();
        constrainToSnap(new int[] {v[0], v[1]});
    }
}
