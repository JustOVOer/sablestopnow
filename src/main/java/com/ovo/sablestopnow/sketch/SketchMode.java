package com.ovo.sablestopnow.sketch;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 草图模式的总控：把「拾取基准面 → 建草图 → 画图元 → 退出」这条链串起来。
 *
 * <h2>为什么不直接写在 GUI 里</h2>
 * 这一层要同时面对三件互相牵扯的事：射线的落点、会话的状态、以及退出时要提交什么。
 * 塞进 UI 类里就没法脱离游戏验证了 —— 而"点一下到底该建面还是该画线"正是最容易写错、
 * 又最难在游戏里复现的部分。
 *
 * <p>所以这里<b>只认 JOML 的向量</b>（射线起点/方向、包围盒），不引用任何 Minecraft 类，
 * 于是可以用 {@code tools/SketchModelCheck.java} 拿合成射线把整条链跑一遍。
 * 客户端那层只负责：把鼠标变成射线、把 {@link #planes()} 和 {@link #hoveredPlane()}
 * 交给渲染器、把 {@link #finish()} 的结果发给服务端。
 *
 * <h2>坐标约定</h2>
 * 所有向量都是**子结构 plot 局部系**（和包围盒、草图平面同一套）。客户端拿到世界射线后
 * 要先换算到局部再进来。
 */
public final class SketchMode {

    /** 在选面阶段，鼠标离平面的落点超出板面多少算没命中 —— 这个判断在 DatumPlane 里做。 */
    private final SketchSession session = new SketchSession();

    /**
     * 求解器。可为 null —— 为 null 时只画不求解。
     *
     * <p>刻意做成**注入**而不是在构造里直接 {@code SketchSolver.bundled()}：后者要读资源、
     * 只有客户端能跑，那样整个 {@code SketchMode} 就没法离线验证了。
     * 客户端注入 {@code bundled()}，离线自检注入从文件加载的那个。
     */
    @Nullable
    private SketchSolver solver;
    /** 最近一次求解结果；UI 据此提示"完全定义 / 欠定义 / 冲突"。 */
    @Nullable
    private SketchSolver.Result lastSolve;
    /** 最近一次求解失败的原因（求解器拒绝输入时）。 */
    @Nullable
    private String lastSolveError;

    /**
     * 拾取容差（平面内距离，格）。
     *
     * <p>由客户端每帧按相机距离设定 —— 固定容差在拉远视角时会变得几乎点不中、
     * 拉近时又会误选一片。<b>屏幕上的可点范围应当恒定</b>，所以容差必须跟着距离走。
     */
    private double pickTolerance = 0.5;

    /** 设置拾取容差（客户端按相机距离算好传进来）。 */
    public void setPickTolerance(final double value) {
        this.pickTolerance = Math.max(1.0e-4, value);
    }

    public double pickTolerance() {
        return pickTolerance;
    }

    /** 注入求解器（客户端在进入草图前调用）。 */
    public void setSolver(@Nullable final SketchSolver value) {
        this.solver = value;
    }

    @Nullable
    public SketchSolver.Result lastSolve() {
        return lastSolve;
    }

    @Nullable
    public String lastSolveError() {
        return lastSolveError;
    }

    private List<SketchDatumPlanes.DatumPlane> planes = List.of();
    @Nullable
    private SketchDatumPlanes.DatumPlane hovered;
    /** 鼠标当前吸附到的关键点（编辑阶段）。 */
    @Nullable
    private SketchSession.KeyPoint snapAt;
    /** 鼠标当前指向的图元（编辑阶段）。悬停高亮 + 端点标识用。 */
    @Nullable
    private Sketch.Entity hoveredEntity;
    /** 预览落点（编辑阶段）：真按下去会用的那个点，已含吸附与推理。 */
    @Nullable
    private double[] previewUv;
    /** 预览的推理方向：1 = 水平，2 = 竖直，0 = 无。渲染推理虚线用。 */
    private double previewOrtho;

    /** 进入草图模式（选定结构之后）。 */
    public void begin(final UUID body, final Vector3dc bboxMin, final Vector3dc bboxMax) {
        this.planes = SketchDatumPlanes.of(bboxMin, bboxMax);
        this.hovered = null;
        this.hoveredEntity = null;
        this.previewUv = null;
        this.snapAt = null;
        this.session.beginPlaneChoice(body);
    }

    public boolean active() {
        return session.stage() != SketchSession.Stage.IDLE;
    }

    public SketchSession.Stage stage() {
        return session.stage();
    }

    public SketchSession session() {
        return session;
    }

    /** 待选的三个基准面（渲染高亮用）。选完面之后清空。 */
    public List<SketchDatumPlanes.DatumPlane> planes() {
        return planes;
    }

    /** 当前鼠标下的基准面；没有则 null。 */
    @Nullable
    public SketchDatumPlanes.DatumPlane hoveredPlane() {
        return hovered;
    }

    /** 正在编辑的草图（{@link SketchSession.Stage#EDITING} 时非空）。 */
    @Nullable
    public Sketch working() {
        return session.working();
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    /**
     * 鼠标移动：选面阶段更新高亮；编辑阶段更新落点预览。
     *
     * <p>编辑阶段这里**不改草图**，只是让 UI 能画"从上一个点到鼠标"的橡皮筋 ——
     * 预览和真画必须分开，否则鼠标一动就会往草图里塞垃圾图元。
     */
    public void hover(final Vector3dc rayOrigin, final Vector3dc rayDir) {
        if (session.stage() == SketchSession.Stage.CHOOSING_PLANE) {
            final SketchDatumPlanes.Hit hit = SketchDatumPlanes.pick(planes, rayOrigin, rayDir);
            hovered = hit == null ? null : hit.plane();
            return;
        }
        // 编辑阶段：记下鼠标下的关键点，UI 据此在光标旁提示"端点 / 中点 / 圆心"。
        // 预览与真画必须用**同一次**吸附判断，否则会出现"提示说吸到中点了、画出来却没吸"。
        final SketchPlane plane = session.working() == null ? null : session.working().plane();
        final Vector3d uv = plane == null ? null : plane.rayIntersect(rayOrigin, rayDir);
        if (uv == null) {
            snapAt = null;
            hoveredEntity = null;
            previewUv = null;
            return;
        }
        snapAt = session.pickKeyPoint(uv.x, uv.y, pickTolerance);
        if (snapAt == null) {
            // ★ **MC 网格吸附** ✓（用户要求：该面的方块网格"顶点与网格线"都可吸附 ✓）
            //   顺序与 SolidWorks 一致 ✓：先几何吸附（端点/中点/圆心 ✓ 上面已试 ✓）
            //   拿不到才落到网格 ✓ ⇒ 不会把几何点抢走 ✗。
            //   uv 的单位就是方块 ✓（子结构局部系 ✓）⇒ 整数格 = MC 方块格 ✓✓
            // ⚠ 网格吸附的容差**必须夹住** ✗✓：离最近整数格点最远只有 0.5 格 ✓
            //   ⇒ 若直接用拾取容差（跟相机距离走 ✓ 可能 >0.5 ✓）就会**到处都吸**✗
            //      —— 网格变成磁铁、画不出非整数的尺寸 ✓（我那条第"远离格点不提示"的对偶断言
            //      当场抓到过这个 ✗ ⇒ 现在夹到 1/3 格 ✓ 留出自由带 ✓）
            // ★ 用户实测反馈："吸附的范围要小一点，不要影响正常操作" ✓
            //   1/3 格（≈0.33）太贪 ✗：想画非整数尺寸时指针稍微一偏就被拉走 ✓
            //   收到 **1/6 格（≈0.167）** ✓ —— 仍然好瞄（格点附近有 1/3 格的"甜区"直径）✓
            //   但明显不再抢走自由摆放 ✓
            final double gridTol = Math.min(pickTolerance, 1.0 / 6.0);
            final double gu = Math.round(uv.x);
            final double gv = Math.round(uv.y);
            final double du = Math.abs(uv.x - gu);
            final double dv = Math.abs(uv.y - gv);
            if (du <= gridTol && dv <= gridTol) {
                snapAt = new SketchSession.KeyPoint(gu, gv, SketchSession.KeyKind.GRID, null, -1, -1);
            } else if (du <= gridTol) {
                // 只吸附到**格线**（另一轴保持自由 ⇒ 可以沿格线滑动 ✓）
                snapAt = new SketchSession.KeyPoint(gu, uv.y, SketchSession.KeyKind.GRID, null, -1, -1);
            } else if (dv <= gridTol) {
                snapAt = new SketchSession.KeyPoint(uv.x, gv, SketchSession.KeyKind.GRID, null, -1, -1);
            }
        }
        // 鼠标下的图元：**任何工具**都要高亮，不只是 SELECT。
        // SolidWorks 的手感是"指到哪就亮到哪"，玩家靠高亮确认自己会对齐到哪条线；
        // 只在 SELECT 下高亮的话，画线时完全没有反馈。
        hoveredEntity = session.pickEntity(uv.x, uv.y, pickTolerance);
        // ★ 用户建议：**将要选到点时，用现有的吸附框提示** ✓
        //   做法：悬停到的图元是点时，把 snapAt 指向它 ⇒ 渲染器那套
        //   "关键点小方框 + 名称提示"立刻复用 ✓ 玩家一眼看出"这次选的是点、不是直线" ✓
        if (hoveredEntity != null && hoveredEntity.kind() == Sketch.EntityKind.POINT) {
            final int[] pv = hoveredEntity.vars();
            snapAt = new SketchSession.KeyPoint(
                    session.working().variable(pv[0]), session.working().variable(pv[1]),
                    SketchSession.KeyKind.ENDPOINT, hoveredEntity, pv[0], pv[1]);
        }
        // 预览落点 = 真按下去会用的那个点。**必须和 click() 走同一条路径**：
        // 先吸关键点，再（画直线时）做水平/竖直推理。少了任何一步，
        // 预览都在骗人 —— 玩家看到预览落在端点左边的斜线上，松手却是一条贴着端点的平线。
        double pu = snapAt == null ? uv.x : snapAt.u();
        double pv = snapAt == null ? uv.y : snapAt.v();
        previewOrtho = 0.0;
        final List<double[]> pending = session.pendingPoints();
        if (session.tool() == SketchSession.Tool.LINE && pending.size() == 1) {
            final double[] a = pending.get(0);
            final double[] o = SketchSession.inferOrtho(a[0], a[1], pu, pv);
            pu = o[0];
            pv = o[1];
            // 记下推理方向（1=水平 2=竖直），渲染器据此画**推理虚线**
            // （SolidWorks 那种"沿这个方向延伸出去"的长虚线）
            previewOrtho = o[2];
        }
        previewUv = new double[] {pu, pv};
    }

    /** 当前预览落点（平面内坐标）；不在编辑阶段或没有落点时为 null。 */
    @Nullable
    public double[] previewUv() {
        return previewUv;
    }

    /**
     * 当前预览正在推理的方向：1 = 水平，2 = 竖直，0 = 没有推理。
     *
     * <p>渲染器用它画**推理虚线** —— 玩家看到"沿这个方向延伸出去"的长虚线，
     * 才知道自己正被吸到某个方向上（只有一条截短的橡皮筋是看不出来的）。
     */
    public double previewOrtho() {
        return previewOrtho;
    }

    /** 鼠标当前吸附到的关键点；没有则 null。UI 用它显示吸附提示。 */
    @Nullable
    public SketchSession.KeyPoint snapAt() {
        return snapAt;
    }

    /** 鼠标指向的图元；没有则 null。UI 用它做悬停高亮 + 端点标识。 */
    @Nullable
    public Sketch.Entity hoveredEntity() {
        return hoveredEntity;
    }

    /** 不带 Ctrl 的点击（单选语义）。保留这个重载，免得所有调用方都要多传一个 false。 */
    public boolean click(final Vector3dc rayOrigin, final Vector3dc rayDir) {
        return click(rayOrigin, rayDir, false);
    }

    /**
     * 鼠标左键。
     *
     * @param additive Ctrl 是否按下 —— 按下时点图元是**增删**选择集（多选），否则是单选替换。
     *                 Ctrl 状态由客户端传进来：{@code SketchMode} 刻意不依赖 Minecraft，
     *                 读不了键盘。
     * @return 是否消费了这次点击（消费了就别再让下游的拾取逻辑处理它）
     */
    public boolean click(final Vector3dc rayOrigin, final Vector3dc rayDir,
                         final boolean additive) {
        switch (session.stage()) {
            case CHOOSING_PLANE -> {
                final SketchDatumPlanes.Hit hit = SketchDatumPlanes.pick(planes, rayOrigin, rayDir);
                if (hit == null) {
                    return false; // 点空了 —— 让这次点击落到别处（比如转视角），不要吞掉
                }
                session.choosePlane(hit.plane().plane(), "Sketch1");
                planes = List.of();   // 选完面就不再显示那三块板
                hovered = null;
                return true;
            }
            case EDITING -> {
                final SketchPlane plane = session.working() == null
                        ? null : session.working().plane();
                if (plane == null) {
                    return false;
                }
                final Vector3d uv = plane.rayIntersect(rayOrigin, rayDir);
                if (uv == null) {
                    // 射线与草图平面平行或在背后：这次的点击没有意义。
                    // 仍然消费掉 —— 在草图环境里，点到空处不该顺手把视角也转了。
                    return true;
                }
                // SELECT 工具：拾取鼠标下的图元（加约束/标尺寸的目标）。
                // 其余工具：先吸附到关键点，再交给绘制。
                if (session.tool() == SketchSession.Tool.SELECT) {
                    Sketch.Entity hit = session.pickEntity(uv.x, uv.y, pickTolerance);
                    // ★ 需求 ③：点在**圆心**附近 ⇒ 自动在圆心放一个点图元并选中它 ✓
                    //   （圆心不是图元 ⇒ 原来点圆心什么也选不到 ✓ 用户实测"点圆心无反应"✗）
                    if (hit == null || hit.kind() != Sketch.EntityKind.POINT) {
                        // ★ 需求 ③（推广版）：光标落在**任意关键点**附近（端点/中点/圆心）⇒
                        //   在那里生成（或复用）一个**点图元**并选中它 ✓
                        //   原来只处理圆心 ⇒ 直线的端点/中点永远选不到点 ✓ 用户实测"只能选到线"✗
                        final Sketch.Entity keyPt = session.pointEntityAt(
                                uv.x, uv.y, pickTolerance);
                        if (keyPt != null) {
                            hit = keyPt;
                        }
                    }
                    if (additive) {
                        // Ctrl：增删。点空处**不动**选择集 —— 否则手一抖就把辛苦选好的一堆清空了。
                        if (hit != null) {
                            session.toggleEntity(hit);
                        }
                    } else {
                        session.selectEntity(hit);
                    }
                    return true;
                }
                // ⚠ 画之前先吸附：鼠标落在端点/中点/圆心附近时，落点直接吸到那个点上。
                // 这是仿 SolidWorks 手感的关键一层 —— 不吸的话，玩家"看起来接上了"的两条线
                // 实际差零点几格，之后一加约束就散开。
                final double[] snapped = session.snap(uv.x, uv.y, pickTolerance);
                session.click(snapped[0], snapped[1]);
                // 画完一个图元就求解一次：自动约束只是"画的时候吸附了一下"，
                // 真正让几何满足约束的是求解器。不在这里解，约束就只是躺在数据里没生效。
                if (session.pendingPoints().isEmpty()) {
                    solve();
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** 编辑一张已有的草图（从设计树点进来）。 */
    public void edit(final Sketch sketch) {
        session.edit(sketch);
        planes = List.of();
        hovered = null;
        hoveredEntity = null;
        previewUv = null;
        snapAt = null;
    }

    /**
     * 选定一个**已有方块面**作为草图平面（选面阶段的另一条入口）。
     *
     * <p>与点基准板并列：玩家既可以点那三块板，也可以直接点结构上的某个面。
     * 两条路最终都落到 {@link SketchSession#choosePlane}，所以后面的编辑流程完全一样。
     *
     * <p>为什么单独一个方法而不是让 {@link #click} 兼管：面拾取要的是一组
     * 已经定好的几何（面的中心与外法线），而 {@code click} 手上只有一条射线。
     * 把「射线 → 命中哪个面」留在客户端（那是 MC 的 raycast 该干的事），
     * 这里只接收结果 —— 这样这条路径也能离线测。
     *
     * @param faceCenter 面中心的 plot 局部坐标
     * @param normal     面的外法线（plot 局部，不必归一化）
     * @return 是否成功进入编辑（不在选面阶段则返回 false）
     */
    public boolean chooseFace(final Vector3dc faceCenter, final Vector3dc normal) {
        if (session.stage() != SketchSession.Stage.CHOOSING_PLANE) {
            return false;
        }
        // 原点取面中心：草图的 (0,0) 就落在玩家点的那块面的正中间
        session.choosePlane(SketchPlane.onFace(faceCenter, normal), "Sketch1");
        planes = List.of();
        hovered = null;
        return true;
    }

    /**
     * 求解当前草图。
     *
     * <p>没有注入求解器时是空操作（离线做纯几何检查时不需要它）。
     *
     * <p>**不抛异常**：求解失败（过约束、病态输入）在交互式草图里是常态，
     * 应该由 UI 提示而不是把游戏搞崩 —— 结果与错误原因都留在
     * {@link #lastSolve()} / {@link #lastSolveError()} 里给 UI 读。
     *
     * @return 求解结果；没在编辑或没有求解器时返回 null
     */
    @Nullable
    public SketchSolver.Result solve() {
        if (session.stage() != SketchSession.Stage.EDITING || session.working() == null) {
            return null;
        }
        if (solver == null) {
            return null;
        }
        try {
            lastSolve = session.working().solve(solver);
            lastSolveError = null;
        } catch (final RuntimeException e) {
            // 求解器拒绝了输入（变量下标越界、约束程序截断……）：记下来，界面提示，不崩
            lastSolve = null;
            lastSolveError = e.getMessage();
        }
        return lastSolve;
    }

    /**
     * 给当前选中的图元标尺寸，并**立刻求解**。
     *
     * <p>标完必须解：尺寸是约束，不解的话玩家输了个 50、图形纹丝不动，
     * 会以为"标了没生效"。求解放在这里而不是让 UI 自己调，是为了保证
     * "改尺寸 → 几何跟着变"这一条在任何调用路径上都成立。
     *
     * @return 是否成功（没选中、或选中的是点）
     */
    public boolean dimension(final double value) {
        if (!session.dimensionSelected(value)) {
            return false;
        }
        solve();
        return true;
    }

    /**
     * 改当前选中图元上那条尺寸的值，并立刻求解。
     *
     * @return 是否成功
     */
    public boolean retypeDimension(final double value) {
        if (!session.retypeSelectedDimension(value)) {
            return false;
        }
        solve();
        return true;
    }

    /**
     * 给当前选中图元加一条几何约束并立刻求解。
     *
     * <p>与 {@link #dimension} 同理：求解放在门面里，保证"加了约束 → 几何跟着变"
     * 在任何调用路径上都成立，不依赖 UI 记得去调。
     *
     * @return 是否加上（约束与图元种类不匹配时返回 false，UI 据此提示）
     */
    public boolean constrain(final int kind) {
        if (!session.constrainSelected(kind)) {
            return false;
        }
        solve();
        return true;
    }

    /** 给两个图元加「等长」并求解。 */
    public boolean equalLength(final Sketch.Entity other) {
        if (!session.equalLengthSelected(other)) {
            return false;
        }
        solve();
        return true;
    }

    /** 鼠标所在位置在草图平面上的落点（预览用）；拿不到返回 null。 */
    @Nullable
    public Vector3d planePoint(final Vector3dc rayOrigin, final Vector3dc rayDir) {        if (session.stage() != SketchSession.Stage.EDITING || session.working() == null) {
            return null;
        }
        return session.working().plane().rayIntersect(rayOrigin, rayDir);
    }

    public void setTool(final SketchSession.Tool tool) {
        session.setTool(tool);
        // 换工具就丢掉悬停高亮：留在屏幕上会让人以为那条线被选中了
        hoveredEntity = null;
        previewUv = null;
        snapAt = null;
    }

    // ------------------------------------------------------------------
    // 退出
    // ------------------------------------------------------------------

    /**
     * 完成并退出。返回需要提交给服务端的草图。
     *
     * <p>返回 null 表示当时并不在编辑状态（重复点"完成"之类的），调用方据此决定要不要发包。
     */
    @Nullable
    public Sketch finish() {
        final Sketch out = session.finish();
        planes = List.of();
        hovered = null;
        hoveredEntity = null;
        previewUv = null;
        snapAt = null;
        return out;
    }

    /** 放弃退出（不提交）。 */
    public void cancel() {
        session.abort();
        planes = List.of();
        hovered = null;
        hoveredEntity = null;
        previewUv = null;
        snapAt = null;
    }

    /** 给渲染器用的：当前正在编辑的草图 id（没有则 null）。 */
    @Nullable
    public UUID editingId() {
        final Sketch working = session.working();
        return working == null ? null : working.id();
    }

    /** 选面阶段所有板的四个角（渲染那三块矩形板用），plane 内部顺序：(−u,−v) (u,−v) (u,v) (−u,v)。 */
    public static List<Vector3d> corners(final SketchDatumPlanes.DatumPlane datum) {
        final SketchPlane p = datum.plane();
        final List<Vector3d> out = new ArrayList<>(4);
        out.add(p.toLocal(-datum.halfU(), -datum.halfV()));
        out.add(p.toLocal(datum.halfU(), -datum.halfV()));
        out.add(p.toLocal(datum.halfU(), datum.halfV()));
        out.add(p.toLocal(-datum.halfU(), datum.halfV()));
        return out;
    }
}
