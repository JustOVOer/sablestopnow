package com.ovo.sablestopnow.client;

import com.ovo.sablestopnow.client.model.ModelCamera;
import com.ovo.sablestopnow.network.SketchNetworking;
import com.ovo.sablestopnow.sketch.Sketch;
import com.ovo.sablestopnow.sketch.SketchMode;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * 把 {@link SketchMode} 接到 Minecraft 上：鼠标 → 射线 → 局部坐标 → 模式。
 *
 * <h2>为什么单独一层</h2>
 * {@link SketchMode} 刻意只认 JOML 向量，好处是能脱离游戏整体验证；代价是总得有人
 * 把「鼠标在哪」翻译成「plot 局部系里的一条射线」。那一层翻译就是这里 ——
 * 全是 MC/Sable 的胶水，没有逻辑可测，所以薄薄一层。
 *
 * <h2>世界 → 局部：两个变换不一样</h2>
 * 子结构的正向变换是 {@code world = position + R · S · (local − rotationPoint)}，所以：
 * <ul>
 *   <li><b>点</b>用 {@code pose.transformPositionInverse}（Sable 自己提供，含 rotationPoint）；</li>
 *   <li><b>方向</b>不能用它 —— 方向没有平移，而且法线用的是逆转置（{@code S·R⁻¹}），
 *       方向要的是 {@code S⁻¹·R⁻¹}。所以这里显式写 {@code R⁻¹·dir} 再逐轴除以 scale。
 *       结构没被缩放时两者恰好相同，被缩放时用错就会"越缩放越偏"，
 *       和描边那边踩过的 scale 坑是同一类问题。</li>
 * </ul>
 */
public final class SketchClient {

    private static final SketchMode MODE = new SketchMode();

    private SketchClient() {
    }

    public static SketchMode mode() {
        return MODE;
    }

    /** 撤销（Ctrl+Z）✓ item 12 */
    public static boolean undo() {
        if (!MODE.active() || MODE.session() == null) {
            return false;
        }
        final boolean ok = MODE.session().undo();
        if (ok) {
            MODE.solve();
            com.ovo.sablestopnow.SablestopNow.LOGGER.info("[sketch-undo] {}",
                    MODE.session().undoDiag());
        }
        return ok;
    }

    /** 重做（Ctrl+Y / Shift+Ctrl+Z）✓ item 12 */
    public static boolean redo() {
        if (!MODE.active() || MODE.session() == null) {
            return false;
        }
        final boolean ok = MODE.session().redo();
        if (ok) {
            MODE.solve();
            com.ovo.sablestopnow.SablestopNow.LOGGER.info("[sketch-redo] {}",
                    MODE.session().undoDiag());
        }
        return ok;
    }

    static {
        // ★ item 1：旋转面板等轴时，"点草图里的一条线"即设为旋转轴 ✓
        com.ovo.sablestopnow.sketch.SketchMode.setLinePickHook((sketch, line) ->
                com.ovo.sablestopnow.client.model.ModelUi.revolveAxisPicking()
                        && com.ovo.sablestopnow.client.model.ModelUi
                                .setRevolveAxisFromLine(sketch, line));
    }

    public static boolean active() {
        return MODE.active();
    }

    /**
     * **正在编辑草图**（而不是"只是进了草图模式、还在选基准面"）✓
     *
     * <p>为什么要区分 ✗✓：进草图模式后会先让玩家选基准面；那时**还没进入编辑**。
     * 之前渲染用的是 {@link #active()} ⇒ 一进模式就把其它模型虚化（10%），
     * 而且模型面的悬停拾取被关掉 ⇒ "未选基准面就虚化、且选不到面" ✗（用户实测）
     * ⇒ 虚化、网格、草图置顶、模型面拾取**都以"真的在编辑"为准** ✓
     */
    public static boolean editing() {
        return MODE.active() && MODE.session() != null
                && MODE.session().stage() == com.ovo.sablestopnow.sketch.SketchSession.Stage.EDITING;
    }

    /**
     * 进入草图模式：用该结构的局部包围盒生成三个基准面。
     *
     * @return 是否成功进入（结构还没同步过来时返回 false）
     */
    public static boolean begin(final UUID bodyId) {
        final ClientSubLevel sub = find(bodyId);
        if (sub == null) {
            return false;
        }
        final BoundingBox3dc bb = sub.boundingBox();
        final Pose3dc pose = sub.renderPose();
        // 求解器是原生库（EZPZ），第一次用到时加载；加载失败也要能进草图环境
        // （只是"画了不求解"），不能因为一个 DLL 问题把整个功能堵死。
        try {
            MODE.setSolver(com.ovo.sablestopnow.sketch.SketchSolver.bundled());
        } catch (final Throwable t) {
            com.ovo.sablestopnow.SablestopNow.LOGGER.warn(
                    "[sablestopnow] 草图求解器加载失败，草图将只画不求解", t);
        }
        // ⚠⚠ `boundingBox()` 是**世界系**，而草图平面与射线都在 **plot 局部系**。
        //
        // 判据：Sable 自己在 `ServerSubLevel.tick()` 里拿 `boundingBox().minY()` 去比
        // `SableConfig.SUB_LEVEL_REMOVE_MIN/MAX`（那是世界的 Y 上下限，-64/320 量级），
        // 而且实测打印结构位置得到 worldY≈-58.9 —— 正好贴着下限。所以它是世界系。
        //
        // 之前直接把世界系的 min/max 当成局部系喂给 SketchDatumPlanes，
        // 结果是：① 基准板被 transformPosition 又按位姿搬了一次，画到结构外很远的地方
        // （表现为"什么都看不到"）；② 射线换算到局部（≈2e7 量级）永远打不到那些板
        // （表现为"点了没反应"）。两个症状是一回事。
        final Vector3d localMin = pose.transformPositionInverse(
                new Vector3d(bb.minX(), bb.minY(), bb.minZ()), new Vector3d());
        final Vector3d localMax = pose.transformPositionInverse(
                new Vector3d(bb.maxX(), bb.maxY(), bb.maxZ()), new Vector3d());
        // 结构被旋转/缩放时，世界 AABB 的两个角逆变换后不再是局部的 min/max；
        // 这里按逐轴取小/取大兜一下（等比缩放且无旋转时与原值完全一致）。
        final Vector3d lo = localMin.min(localMax, new Vector3d());
        final Vector3d hi = localMin.max(localMax, new Vector3d());
        MODE.begin(bodyId, lo, hi);
        SketchRenderer.setEditing(null);
        diag(bodyId, sub, bb, pose, lo, hi);
        return true;
    }

    /**
     * 一次性诊断：把「包围盒中心（局部）」与「世界→局部的往返」并排打出来。
     *
     * <p>基准板画歪时，需要一眼分清是**三种可能里的哪一种**，否则只能在游戏里反复猜：
     * <ol>
     *   <li>{@code boundingBox()} 本身不是 plot 局部系（那 center 就是错的）；</li>
     *   <li>位姿或 scale 不对；</li>
     *   <li>我用的逆变换不对（{@code transformPositionInverse} 的语义与正变换不互逆）。</li>
     * </ol>
     *
     * <p>第 3 条可以用**自校验**判掉：把 center 正变换到世界、再逆变换回局部，
     * {@code roundTripErr} 应当≈0。它不为 0 就说明第 3 条成立，与前面的渲染无关 ——
     * 这正是当初查施力点时最有效的手法（先把"哪一环错"用数字钉死，而不是继续猜）。
     */
    private static void diag(final UUID bodyId, final ClientSubLevel sub, final BoundingBox3dc bb,
                             final Pose3dc pose, final Vector3d localMin, final Vector3d localMax) {
        final Vector3d worldCenter = new Vector3d(
                (bb.minX() + bb.maxX()) * 0.5,
                (bb.minY() + bb.maxY()) * 0.5,
                (bb.minZ() + bb.maxZ()) * 0.5);
        final Vector3d localCenter = new Vector3d(
                (localMin.x + localMax.x) * 0.5,
                (localMin.y + localMax.y) * 0.5,
                (localMin.z + localMax.z) * 0.5);
        // 自校验：局部中心 → 世界 → 回局部，应当原地不动
        final Vector3d world = pose.transformPosition(new Vector3d(localCenter), new Vector3d());
        final Vector3d back = pose.transformPositionInverse(world, new Vector3d());
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[sketch-diag] body={} bboxWorld=({},{},{})..({},{},{}) pose.pos={} scale={} "
                        + "localBox=({},{},{})..({},{},{}) localCenter={} roundTripErr={}",
                bodyId.toString().substring(0, 8),
                bb.minX(), bb.minY(), bb.minZ(), bb.maxX(), bb.maxY(), bb.maxZ(),
                pose.position(), pose.scale(),
                localMin.x, localMin.y, localMin.z, localMax.x, localMax.y, localMax.z,
                localCenter, localCenter.distance(back));
    }

    /** 鼠标移动：更新高亮 / 落点预览。 */
    public static void hover(final Minecraft mc) {
        if (!MODE.active()) {
            return;
        }
        final UUID body = MODE.session().body();
        final ClientSubLevel sub = body == null ? null : find(body);
        if (sub == null) {
            return;
        }
        final Vec3 from = ModelCamera.position();
        final Vec3 dir = ModelCamera.rayDirection(mc);
        final Pose3dc pose = sub.renderPose();
        final Vector3d localFrom = toLocalPoint(pose, from);
        final Vector3d localDir = toLocalDir(pose, dir);
        // 拾取容差跟着相机距离走：屏幕上的可点范围恒定，拉远不会点不中、拉近不会误选一片。
        final var posePos = pose.position();
        final double distance = Math.sqrt(
                Math.pow(from.x - posePos.x(), 2)
                        + Math.pow(from.y - posePos.y(), 2)
                        + Math.pow(from.z - posePos.z(), 2));
        MODE.setPickTolerance(Math.max(0.12, distance * 0.02));
        MODE.hover(localFrom, localDir);

        // 编辑阶段顺便把鼠标落点交给渲染器画橡皮筋。
        // ⚠ 用 MODE.previewUv() 而不是这里的原始 uv：预览要显示的是**真按下去会用的点**，
        // 已经过关键点吸附与水平/竖直推理。喂原始 uv 的话，预览会落在光标处、
        // 松手却吸到别处 —— 看起来就是"预览在骗人"。
        final Vector3d uv = MODE.planePoint(localFrom, localDir);
        lastCursorUv = uv; // 拖动尺寸要用"指针在平面上的位置" ✓ 与命中同一套坐标 ✓
        final double[] preview = MODE.previewUv();
        // ★ 把"真按下去会用的点"推进会话 ✓（与上面注释同一口径 ✓ 不用原始 uv ✗）
        //   ⇒ 智能尺寸的 preview(...) 靠它做**方位自动捕捉**（平行/水平/竖直 ✓）
        //     左栏面板也读同一处状态 ⇒ 三处联动 ✓ 不会各算一套 ✗
        if (preview != null) {
            MODE.session().setHover(preview[0], preview[1]);
        } else if (uv != null) {
            MODE.session().setHover(uv.x, uv.y);
        }
        if (preview == null) {
            SketchRenderer.clearPreviewPoint();
        } else {
            SketchRenderer.setPreviewPoint(preview[0], preview[1]);
        }
        diagUv(uv, localFrom, localDir);
    }

    /** 指针在草图平面上的 uv ✓（每帧由 {@link #hover} 更新 ✓ 拖动标签用 ✓）。 */
    @Nullable
    private static Vector3d lastCursorUv;

    /** 指针的平面 uv ✓；还没算出来时返回 null ✓。 */
    @Nullable
    public static double[] cursorUv() {
        final Vector3d uv = lastCursorUv;
        return uv == null ? null : new double[] {uv.x, uv.y};
    }

    private static long lastUvDiagMs;
    private static double lastU;
    private static double lastV;

    /**
     * 落点诊断（每秒最多一行，且 u/v 都几乎没变时不打）。
     *
     * <p>排查「直线/矩形塌成一条线」用：那组现象合起来只有一个解释 ——
     * **平面坐标里有一个分量取不到变化**（v 恒定 → 所有线共线、矩形高度为 0），
     * 而圆只依赖圆心与半径、所以看着正常。这行直接把 u/v 印出来，一看便知。
     */
    private static void diagUv(@Nullable final Vector3d uv, final Vector3d localFrom,
                               final Vector3d localDir) {
        if (uv == null) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - lastUvDiagMs < 1000L) {
            return;
        }
        if (Math.abs(uv.x - lastU) < 1.0e-6 && Math.abs(uv.y - lastV) < 1.0e-6) {
            return;
        }
        lastUvDiagMs = now;
        lastU = uv.x;
        lastV = uv.y;
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[sketch-diag] cursor uv=({}, {})  rayLocalFrom={} rayLocalDir={}",
                String.format(java.util.Locale.ROOT, "%.4f", uv.x),
                String.format(java.util.Locale.ROOT, "%.4f", uv.y),
                localFrom, localDir);
    }

    /**
     * 左键。
     *
     * @return true 表示这次点击被草图模式消费了（调用方不要再往下走拾取/拖拽）
     */
    /**
     * **双击改值**：改一条**已有**约束的值并立刻求解 ✓（= SolidWorks 的"输入新值 ⇒ 模型实时驱动更新"✓）。
     *
     * <p>为什么按 {@code constraintIndex} 改 ✗✓：双击命中的是屏幕上那个标签 ✓
     * 而标签来自 {@code SketchMeasure.Dimension} ✓ 它自带所对应的约束下标 ✓✓
     * ⇒ 改的就是"玩家看到的那一条"✓ 不存在"点 A 改了 B"✗。
     *
     * <p>只**改值**不换类型 ✓（类型要换 ⇒ 用面板的候选按钮或右键锁定 ✓ 语义分开 ✓）。
     *
     * @return true = 确实改了并求解了 ✓
     */
    /**
     * **求解诊断** ✓：一处写、多点位调用 ✓（用户要求：要写日志就一次写足 ✓ 别反复重测 ✗）。
     *
     * <p>打印 约束数 / 是否满足 / 未满足数 / 迭代数 / 求解异常 —— 定位"无法求解"必需的全部信息 ✓。
     */
    private static void logSolve(final String tag, final int kind, final double value,
                                  final int varCount) {
        final var r = MODE.lastSolve();
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[sketch-solve] {}: kind={} value={} vars={} ok={} satisfied={} unsatisfied={} iters={} err={}",
                tag, kind, String.format(java.util.Locale.ROOT, "%.4f", value), varCount,
                r != null && r.ok(), r != null && r.satisfied(),
                r == null ? -1 : r.unsatisfied(), r == null ? -1 : r.iterations(),
                MODE.lastSolveError() == null ? "-" : MODE.lastSolveError());
    }

    /**
     * **删除一个尺寸（及其约束）** ✓ —— 改值框上「删除」按钮走这里 ✓。
     *
     * <p>用户要求："设置尺寸的 modify 框应该是确定和删除键，删除就是删除这个尺寸和相关约束" ✓。
     * 几何保持在原地（只去掉约束 ✓ 与 SolidWorks 一致 ✓），随后再解一次以刷新显示 ✓。
     */
    public static boolean deleteDimension(final int constraintIndex) {
        final var session = MODE.session();
        final var work = session == null ? null : session.working();
        if (work == null || constraintIndex < 0 || constraintIndex >= work.constraints().size()) {
            return false;
        }
        work.removeConstraint(constraintIndex);
        MODE.solve();
        logSolve("delete-after", 0, 0.0, 0);
        com.ovo.sablestopnow.client.model.ModelMode.prompt("model.dim.deleted");
        return true;
    }

    public static boolean applyDimensionValue(final int constraintIndex, final double value) {
        final var session = MODE.session();
        if (session == null) {
            return false;
        }
        final var work = session.working();
        if (work == null || constraintIndex < 0
                || constraintIndex >= work.constraints().size()) {
            return false;
        }
        final var c = work.constraints().get(constraintIndex);
        work.replaceConstraint(constraintIndex,
                new com.ovo.sablestopnow.sketch.Sketch.Constraint(c.kind(), c.operands(), value));
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[sketch-solve] edit: idx={} kind={} old={} new={}",
                constraintIndex, c.kind(),
                String.format(java.util.Locale.ROOT, "%.4f", c.value()),
                String.format(java.util.Locale.ROOT, "%.4f", value));
        MODE.solve();
        logSolve("edit-after", c.kind(), value,
                c.operands() == null ? -1 : c.operands().length);
        // ★ 明确反馈 ✓：改值后若求解器**没满足**（或触发塌陷回滚 ✓）⇒ 提示 ✓
        //   为什么必须提示 ✗✓：否则就是用户实测的"确定后却没有改变"✗ —— 玩家分不清"没生效"和"做不到"✗
        final var solved = MODE.lastSolve();
        if (solved == null || !solved.ok()) {
            com.ovo.sablestopnow.client.model.ModelMode.prompt("model.dim.unsatisfied");
            return false;
        }
        return true;
    }
    /**
     * **右键**：循环锁定对齐方式 ✓（SolidWorks 的"右键锁定当前对齐/尺寸类型"✓）。
     *
     * <p>顺序：候选 0 → 候选 1 → … → **取消锁定**（回到自动捕捉 ✓）→ 候选 0 …
     * 锁定时画面上已有锁定环 ✓（SketchHud 里 ✓ 读的是同一个 {@code lockedVariant} ✓）。
     *
     * @return true = 这次右键被草图消费了（调用方不要再当成"取消/返回"✗）
     */
    public static boolean rightClick() {
        if (!MODE.active() || MODE.session() == null
                || MODE.session().tool() != com.ovo.sablestopnow.sketch.SketchSession.Tool.DIMENSION) {
            return false;
        }
        final var session = MODE.session();
        final var work = session.working();
        final var picked = session.selection();
        if (work == null || picked.isEmpty() || picked.size() > 2) {
            return false;
        }
        final var dim = com.ovo.sablestopnow.sketch.SketchMeasure.smartDimension(work, picked);
        if (dim == null) {
            return false;
        }
        final int count = com.ovo.sablestopnow.sketch.SketchMeasure.variants(work, dim).size();
        if (count <= 1) {
            return true; // 只有一种候选 ⇒ 右键无事可做，但仍消费掉 ✓ 免得被当成取消 ✗
        }
        int next = session.lockedVariant() + 1;
        if (next >= count) {
            next = -1; // 绕过一圈 ⇒ 回到自动捕捉 ✓
        }
        session.lockVariant(next);
        com.ovo.sablestopnow.client.model.ModelMode.prompt(next < 0
                ? "model.smart.unlocked" : "model.smart.locked");
        return true;
    }

    private static long lastClickMs;
    private static double lastClickX = Double.NaN;
    private static double lastClickY = Double.NaN;

    public static boolean click(final Minecraft mc) {
        if (!MODE.active()) {
            return false;
        }
        // ★★ 修实测"零长直线 / 手柄长度 0 / 贝塞尔变直线"的**共同根因** ✓✓
        //   现象：一次点击被**处理两次**（同一位置、极短时间内的第二次到达）
        //   ⇒ 每个点被落两次 ⇒ 手柄长度 0、贝塞尔控制点与端点重合（看着变直线）✓
        //   ⇒ 这里做**去重**：70ms 内、3 像素内视为同一次点击，直接吞掉 ✓
        final double mxNow = mc.mouseHandler.xpos();
        final double myNow = mc.mouseHandler.ypos();
        final long msNow = System.currentTimeMillis();
        if (msNow - lastClickMs < 70L
                && Math.hypot(mxNow - lastClickX, myNow - lastClickY) < 3.0) {
            return true; // 重复到达 ⇒ 丢掉，但**消费掉**（不要让它变成别的操作）✓
        }
        lastClickMs = msNow;
        lastClickX = mxNow;
        lastClickY = myNow;
        final UUID body = MODE.session().body();
        final ClientSubLevel sub = body == null ? null : find(body);
        if (sub == null) {
            return false;
        }
        final Vec3 from = ModelCamera.position();
        final Vec3 dir = ModelCamera.rayDirection(mc);
        final Pose3dc pose = sub.renderPose();
        final Vector3d localFrom = toLocalPoint(pose, from);
        final Vector3d localDir = toLocalDir(pose, dir);
        // ⚠ 诊断必须**无条件**打，而且要记下点击**之前**的阶段。
        // 之前我是"点击之后阶段仍是 CHOOSING_PLANE 才打"——于是成功的那次反而没有日志，
        // 我因此白白查了好几轮。失败的日志能定位失败，成功的日志才能确认成功。
        final var stageBefore = MODE.stage();
        // ★ 需求 ⑦：点击落在**草图原点（基准点）**附近 ⇒ 本次落点吸到原点 ✓
        //   ⚠ 这个锚点（stageBefore）在 click() 里**唯一** ✓ —— 上一版我用
        //     `final Vector3d localDir = toLocalDir(...)` 当锚点，它在两个方法里都有 ✗
        //     结果插了两份、清理时又把边界算错 ⇒ 只能回滚重来 ✓ 记住这个坑 ✓。
        final var skSess0 = MODE.session();
        if (skSess0 != null
                && skSess0.stage() == com.ovo.sablestopnow.sketch.SketchSession.Stage.EDITING) {
            final Vector3d uvAtOrigin = MODE.planePoint(localFrom, localDir);
            if (uvAtOrigin != null && Math.hypot(uvAtOrigin.x, uvAtOrigin.y) <= 0.12) {
                skSess0.forceSnapOrigin();
            }
        }
        // ★ 开启"画线时终点也落吸附约束" ✓（客户端专属 ⇒ 离线用例不受影响 ✓）
        if (MODE.session() != null) {
            MODE.session().setSnapLineEnd(true);
            // ★ 需求 ④：客户端开启"画弧时生成两条辅助线"✓（离线用例不受影响 ✓）
            MODE.session().setArcHandles(true);
            // ★ 需求 ⑤：客户端开启"画贝塞尔时生成两条手柄辅助线"✓
            MODE.session().setBezierHandles(true);
        }
        // Ctrl 按下 = 多选增删。读键盘只能在客户端这一层做（SketchMode 不依赖 Minecraft）。
        // ★ 智能尺寸工具：左键 = 在指针处**放置**当前预览到的候选 ✓（SolidWorks 的左键放置 ✓）
        //   必须在这里拦 ✗ —— SketchMode 只管画图元、不知道"工具"这回事 ✓。
        //   放置走 dimensionWith ✓ ⇒ 自带**过定义拦截**（同几何同种约束 ⇒ 改值而非新建 ✓✓）
        if (MODE.session().tool() == com.ovo.sablestopnow.sketch.SketchSession.Tool.DIMENSION) {
            final var session = MODE.session();
            final var work = session.working();
            final var picked = session.selection();
            // ★ 修实测根因 ✓：智能尺寸下**第一次点图元要能选中它** ✓
            //   （以前这里直接去"放置"✗ ⇒ 选择恒为空 ⇒ 预览永远不出现 ✗✓）
            //   SolidWorks 的做法 ✓：点实体 ⇒ 选中并开始预览 ✓；已有选择时再点 ⇒ 放置 ✓。
            final Vector3d uvPick = MODE.planePoint(localFrom, localDir);
            final var hit = uvPick == null ? null
                    : session.pickEntity(uvPick.x, uvPick.y, MODE.pickTolerance());
            com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                    "[sketch-dim] click: hit={} selection={} entities={} constraints={}",
                    hit == null ? "none" : hit.kind(), picked.size(),
                    work == null ? -1 : work.entities().size(),
                    work == null ? -1 : work.constraints().size());
            // ★ SolidWorks 语义 ✓（我前两版各错一次 ✗ 这是修正版 ✓）：
            //     点**图元** ⇒ 加入选择（最多两个 ✓）  点**空白** ⇒ 用当前选择**放置** ✓
            //   为什么必须这样 ✗✓：若"点第二个图元"就直接放置 ⇒ 永远选不中第二个 ✗
            //     ⇒ 两图元尺寸（线线距离 / 两线夹角 ✓ 需求 (3) ✓）走不通 ✗
            // ★ 用户要求：**智能尺寸要能吃到"圆心/端点"这类图元上的点** ✓✓
            //   用户实测："先选择线段，再点击圆心，此时理应设置圆心到线段距离，然而设置的是线段长度" ✗
            //   原因 ✓：点击只认**图元**，圆心不是图元 ⇒ "点空白"被当成"放置"，于是把选择里的线标了长度 ✗。
            //   做法 ✓：命中图元之前先看**当前吸附点**（悬停时已经算好 ⇒ 复用 ✓ 不新增 API ✓）
            //     —— 选中的是一条线、且吸附到圆心/端点 ⇒ 直接落"点到直线的距离" ✓
            //     （值取**当前实测** ⇒ 落下去是零改动 ✓ 玩家随后双击改值 ✓）
            final var snapKp = MODE.snapAt();
            if (hit == null && picked.size() == 1 && work != null && snapKp != null
                    && (snapKp.kind() == com.ovo.sablestopnow.sketch.SketchSession.KeyKind.CENTER
                            || snapKp.kind() == com.ovo.sablestopnow.sketch.SketchSession.KeyKind.ENDPOINT)
                    && snapKp.px() >= 0 && snapKp.py() >= 0
                    && picked.get(0).kind() == com.ovo.sablestopnow.sketch.Sketch.EntityKind.LINE) {
                final int[] lv = picked.get(0).vars();
                final double axp = work.variable(lv[0]);
                final double ayp = work.variable(lv[1]);
                final double bxp = work.variable(lv[2]);
                final double byp = work.variable(lv[3]);
                final double lenp = Math.hypot(bxp - axp, byp - ayp);
                if (lenp > 1.0e-9) {
                    final double pxp = work.variable(snapKp.px());
                    final double pyp = work.variable(snapKp.py());
                    final double distp = Math.abs((byp - ayp) * (pxp - axp)
                            - (bxp - axp) * (pyp - ayp)) / lenp;
                    final int[] pvars = {snapKp.px(), snapKp.py(), lv[0], lv[1], lv[2], lv[3]};
                    final boolean pOk = session.dimensionWith(
                            com.ovo.sablestopnow.sketch.SketchSolver.POINT_LINE_DISTANCE, distp, pvars);
                    com.ovo.sablestopnow.client.model.ModelMode.prompt(pOk
                            ? "model.measure.added" : "model.measure.not_lockable");
                    if (pOk) {
                        session.clearSelection();
                        MODE.solve();
                        final int pci = session.dimensionIndexOf(
                                com.ovo.sablestopnow.sketch.SketchSolver.POINT_LINE_DISTANCE, pvars);
                        if (pci >= 0) {
                            com.ovo.sablestopnow.client.SketchHud.openEdit(
                                    pci, "model.dim.distance", distp);
                        }
                    }
                    return true;
                }
            }
            // ★ 需求：**基准点（原点）等虚拟关键点也要能选成点** ✓
            //   实测"先选线、再点基准点 ⇒ 想标点到线距离却选不上"✗
            //   原因：原点不是图元 ⇒ pickEntity 命中不了 ⇒ 被当成"点空白放置"✗
            //   ⇒ 没命中图元时，先在关键点处生成/复用一个点图元并选中 ✓
            //     之后点空白即落"点 ↔ 线 距离" ✓（圆心/端点仍走上面那条快速路径 ✓）
            if (hit == null && work != null) {
                final var keyPt = session.pointEntityAt(
                        session.hoverU(), session.hoverV(), MODE.pickTolerance());
                if (keyPt != null && !session.isSelected(keyPt)) {
                    if (picked.size() >= 2) {
                        session.clearSelection();
                    }
                    session.toggleEntity(keyPt);
                    return true;
                }
            }
            if (hit != null) {
                if (picked.size() >= 2) {
                    session.clearSelection(); // 已选两个还想选 ⇒ 从这一个重新开始 ✓ 不至于卡住 ✓
                }
                session.toggleEntity(hit);
                return true; // 只负责选中 ✓ 不放置 ✗
            }
            if (work != null && !picked.isEmpty() && picked.size() <= 2) {
                final var cand = com.ovo.sablestopnow.sketch.SketchMeasure.preview(
                        work, picked, session.hoverU(), session.hoverV(), session.lockedVariant());
                if (cand != null) {
                    com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                            "[sketch-solve] place: kind={} value={} vars={} label={}",
                            cand.solverKind(),
                            String.format(java.util.Locale.ROOT, "%.4f", cand.value()),
                            cand.vars() == null ? -1 : cand.vars().length, cand.labelKey());
                    final boolean placed = session.dimensionWith(
                            cand.solverKind(), com.ovo.sablestopnow.sketch.SketchMeasure.solverValue(cand),
                                    cand.vars()); // ★ 直径候选以**半径**落约束 ✓（否则圆弧无解 ✗）
                    MODE.solve();
                    logSolve("place-after", cand.solverKind(), cand.value(),
                            cand.vars() == null ? -1 : cand.vars().length);
                    com.ovo.sablestopnow.client.model.ModelMode.prompt(placed
                            ? "model.measure.added" : "model.measure.not_lockable");
                    if (placed) {
                        // 放完就清 ✓ —— 但**尺寸链**模式下要**保留选择** ✓ 让下一个图元加到同一基准上 ✓（需求 (3) ✓）
                        if (!session.chainMode()) {
                            session.clearSelection();
                        }
                        // ★ 立刻在**鼠标旁**弹出改值小框 ✓（SolidWorks 的 Modify 框 ✓ 用户要求 ✓）
                        //   下标必须用"同 kind 同操作数"找出来的**真实下标** ✓
                        //   （原来用 size()-1 ✗：过定义拦截走**替换**时约束数量不变 ⇒ 会指到别的约束 ✗
                        //     表现正是用户实测的"线线距离确定后几何没有改变"✗）
                        final int ci = session.dimensionIndexOf(cand.solverKind(), cand.vars());
                        if (ci >= 0) {
                            SketchHud.openEdit(ci, cand.labelKey(), cand.value()); // 改值框回填**显示值**（直径时是 2r ✓）
                        }
                }
            }
            }
            return true; // 消费掉 ✓ 不让草图模式再把它当成"落点"✗
        }
        final boolean additive = net.minecraft.client.gui.screens.Screen.hasControlDown();
        final boolean consumed = MODE.click(localFrom, localDir, additive);
        diagPlaneClick(stageBefore, consumed, MODE.stage(), MODE.planes().size());
        if (!consumed) {
            return false;
        }
        // 刚建好草图 / 正在编辑，把当前草图 id 同步给渲染器（高亮用）
        SketchRenderer.setEditing(MODE.editingId());
        diagEntity();
        return true;
    }

    /** 点击诊断：记录点击**前后**的阶段与实际有没有被消费（无条件打）。 */
    private static void diagPlaneClick(final Object stageBefore, final boolean consumed,
                                       final Object stageAfter, final int plates) {
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[sketch-diag] click: stage {} -> {} consumed={} plates={}",
                stageBefore, stageAfter, consumed, plates);
    }

    /** 最近一次完成图元后的变量值（诊断「线/矩形塌成一条线」）。 */
    private static void diagEntity() {
        final com.ovo.sablestopnow.sketch.Sketch k = MODE.working();
        if (k == null || k.entities().isEmpty()) {
            return;
        }
        final var e = k.entities().get(k.entities().size() - 1);
        final StringBuilder sb = new StringBuilder();
        for (final int v : e.vars()) {
            sb.append(String.format(java.util.Locale.ROOT, "%.4f ", k.variable(v)));
        }
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[sketch-diag] entity #{} kind={} vars=[{}]  solved={}",
                k.entities().size(), e.kind(), sb.toString().trim(),
                MODE.lastSolve() == null ? "n/a"
                        : (MODE.lastSolve().ok() ? "ok" : "unsatisfied="
                                + MODE.lastSolve().unsatisfied()));
    }

    /**
     * 从设计树点进一张已有草图继续编辑。
     *
     * <p>客户端手上就是完整的一份（全量同步带回了变量/图元/约束），所以不必像施力点那样
     * 先向服务端拉一次 —— 直接进编辑。改完点「完成草图」时整张发回去。
     */
    public static void edit(final Sketch sketch) {
        MODE.edit(sketch);
        SketchRenderer.setEditing(sketch.id());
        // ★ 用户要求：**从右侧栏进入草图时也要自动正视** ✓
        //   （以前只有"点模型面建草图"那条路会正视 ⇒ 从树里进去是斜着看 ✗）
        if (sketch != null && sketch.plane() != null) {
            alignCameraTo(sketch.plane().normal());
        }
    }

    /**
     * 用鼠标下那个**方块面**作为草图平面。
     *
     * <p>⚠ 坐标已经是 plot 局部系 —— {@code MateTargeting.BoxHit} 的 {@code baseBlock}
     * 实测就是局部坐标（日志里是 {@code BlockPos{x=20481032,...}}，2e7 量级），
     * 不需要再做任何世界↔局部的换算。这一点很容易搞反：它看着像"世界里的方块坐标"，
     * 其实是 plot 局部。换算两次就会得到一个偏移两千万格的平面。
     *
     * @return 是否真的建了草图
     */
    public static boolean chooseFace(final MateTargeting.BoxHit hit) {
        final net.minecraft.core.BlockPos block = hit.baseBlock();
        final net.minecraft.core.Direction face = hit.baseFace();
        if (block == null || face == null) {
            return false;
        }
        // 面的中心 = 方块中心 + 半个法线；法线直接用方块面的轴向量
        final Vector3d center = new Vector3d(
                block.getX() + 0.5 + face.getStepX() * 0.5,
                block.getY() + 0.5 + face.getStepY() * 0.5,
                block.getZ() + 0.5 + face.getStepZ() * 0.5);
        final Vector3d normal = new Vector3d(face.getStepX(), face.getStepY(), face.getStepZ());
        if (!MODE.chooseFace(center, normal)) {
            return false;
        }
        SketchRenderer.setEditing(MODE.editingId());
        alignCameraTo(normal);
        return true;
    }

    /**
     * **把相机正视到某个法线方向** ✓（"正视于"）。
     *
     * <p>从 {@link #chooseFace} 抽出来，供"以模型面为基准面"复用 ✓
     */
    public static void alignCameraTo(final org.joml.Vector3dc normal) {
        // ★ **进入草稿自动正视该面** ✓（用户要求 ✓ / SolidWorks 的"正视于"✓）
        //   视线方向 = 面法线的反向（从外面正对看进去 ✓）⇒ 反解 yaw/pitch ✓
        //   Minecraft 约定：look = (−sin(yaw)·cos(pitch), −sin(pitch), cos(yaw)·cos(pitch))
        //   ⇒ pitch = asin(−look.y) ✓  yaw = atan2(−look.x, look.z) ✓
        final double lx = -normal.x();
        final double ly = -normal.y();
        final double lz = -normal.z();
        final double pitchDeg = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, -ly))));
        // ★ 需求 ⑧（用户："先正视和这个面垂直的面，再正视这个面"✓）：**滚转必须唯一确定** ✓
        //   我们这的相机只有 yaw/pitch（up = 世界竖直投影 ✓）⇒ 朝向完全由面法线决定 ✓
        //   但原来的 ±Y 分支写的是"**yaw 保持原样**" ✗ ⇒ 顶着面看下去时，
        //   网格会**跟着玩家原来的朝向转** ✓ ⇒ 这正是"网格不横平竖直"的来源 ✓
        //   ⇒ 改成 **yaw = 0**：屏幕右 = +X、屏幕上 = −Z ⇒ 网格与世界轴对齐 ✓
        //     （等价于用户说的"先侧看一个与它垂直的面、再正视它"⇒ 滚转被唯一确定 ✓）
        final double yawDeg = lx * lx + lz * lz < 1.0e-9
                ? 0.0
                : Math.toDegrees(Math.atan2(-lx, lz));
        com.ovo.sablestopnow.client.model.ModelCamera.alignTo((float) yawDeg, (float) pitchDeg);
        // ★ **自证式修正** ✓：不依赖"我对 yaw/pitch 符号的记忆"✗（真机上已出现 plates=0 的疑点 ✗）
        //   做法 ✓：设置完读回真实视线方向 ✓，若它与面法线**同向**（说明相机朝着背面 ✗）
        //        就把 yaw 转 180°、pitch 取反再设一次 ✓ ⇒ 无论约定如何，结果都必然是"正对着面看"✓
        final var look = com.ovo.sablestopnow.client.model.ModelCamera.lookVector();
        final double dot = look.x * normal.x() + look.y * normal.y() + look.z * normal.z();
        if (dot > 0.0) {
            com.ovo.sablestopnow.client.model.ModelCamera.alignTo(
                    (float) (yawDeg + 180.0), (float) -pitchDeg);
            com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                    "[sketch] face-on corrected: dot={} ⇒ flipped",
                    String.format(java.util.Locale.ROOT, "%.3f", dot));
        }
    }

    /**
     * **以模型的一个面作为草图基准面** ✓（需求：草图能够用模型上的面做基准面，
     * 并把该平面的直线作为常驻、不可移动的参考线）。
     *
     * @return true = 这次点击被消费了
     */
    public static boolean chooseModelFace(
            final com.ovo.sablestopnow.client.model.FeatureHover.Hit hit) {
        // 只有**平面**能当基准面 ✓（曲面可以高亮，但不能建草图 ✓）
        if (hit == null || hit.kind() != com.ovo.sablestopnow.client.model.FeatureHover.Kind.FACE
                || !hit.face().planar()) {
            return false;
        }
        if (!MODE.chooseFace(hit.localCenter(), hit.localNormal())) {
            return false;
        }
        SketchRenderer.setEditing(MODE.editingId());
        alignCameraTo(hit.localNormal());
        // ★ 该面的直线 ⇒ 参考几何（常驻、不可移动、可被约束）✓
        final int refs = MODE.session() == null ? 0 : MODE.session().addReferenceLines(
                hit.face().segments());
        MODE.solve();
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[sketch-face] body={} normal=({},{},{}) segs={} refs+={}",
                hit.body() == null ? "?" : hit.body().toString().substring(0, 8),
                String.format(java.util.Locale.ROOT, "%.2f", hit.localNormal().x),
                String.format(java.util.Locale.ROOT, "%.2f", hit.localNormal().y),
                String.format(java.util.Locale.ROOT, "%.2f", hit.localNormal().z),
                hit.face().segments().size(), refs);
        return true;
    }

    /** 完成并提交给服务端；没在编辑就什么都不做。 */
    public static void finish() {
        com.ovo.sablestopnow.client.SketchHud.closeEdit(); // ★ 退出草图 ⇒ 关掉改值浮层 ✓（否则输入被挡住 ✓）
        final Sketch sketch = MODE.finish();
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[sketch] exit: anyOpen={} blocksWorldInput={} active={} stage={} tool={}",
                com.ovo.sablestopnow.client.gui.lib.Ui.anyOpen(),
                com.ovo.sablestopnow.client.gui.lib.Ui.blocksWorldInput(),
                com.ovo.sablestopnow.client.SketchClient.active(), MODE.stage(),
                MODE.session() == null ? "none" : MODE.session().tool());
        SketchRenderer.setEditing(null);
        if (sketch != null) {
            SketchNetworking.sendSave(sketch);
        }
    }

    /** 放弃（不提交）。 */
    public static void cancel() {
        com.ovo.sablestopnow.client.SketchHud.closeEdit(); // ★ 取消草图 ⇒ 关掉改值浮层 ✓（否则输入被挡住 ✓）
        // ★★ 修实测"草图的尺寸和约束似乎不会保存" ✓✓
        //   原来 Esc/取消 = `MODE.cancel()` **丢掉整张草图的编辑** ✗
        //   而保存只在 `finish()` 里发一次 ⇒ 玩家按 Esc 退出时，刚做的尺寸/约束全没了 ✓
        //   ⇒ 现在**取消也保存**（有内容就存 ✓ 宁可多存也不丢工作 ✓）
        final var beforeCancel = MODE.session() == null ? null : MODE.session().working();
        final boolean hasWork = beforeCancel != null && !beforeCancel.constraints().isEmpty();
        if (hasWork) {
            com.ovo.sablestopnow.network.SketchNetworking.sendSave(beforeCancel);
        }
        MODE.cancel();
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[sketch] exit: anyOpen={} blocksWorldInput={} active={} stage={} tool={}",
                com.ovo.sablestopnow.client.gui.lib.Ui.anyOpen(),
                com.ovo.sablestopnow.client.gui.lib.Ui.blocksWorldInput(),
                com.ovo.sablestopnow.client.SketchClient.active(), MODE.stage(),
                MODE.session() == null ? "none" : MODE.session().tool());
        SketchRenderer.setEditing(null);
    }

    // ------------------------------------------------------------------

    @Nullable
    private static ClientSubLevel find(final UUID bodyId) {
        final Level level = Minecraft.getInstance().level;
        if (level == null) {
            return null;
        }
        if (!(SubLevelContainer.getContainer(level) instanceof final ClientSubLevelContainer container)) {
            return null;
        }
        for (final ClientSubLevel sub : container.getAllSubLevels()) {
            if (sub.getUniqueId().equals(bodyId)) {
                return sub;
            }
        }
        return null;
    }

    private static Vector3d toLocalPoint(final Pose3dc pose, final Vec3 world) {
        return pose.transformPositionInverse(
                new Vector3d(world.x, world.y, world.z), new Vector3d());
    }

    /**
     * 世界方向 → 局部方向：{@code S⁻¹ · R⁻¹ · dir}。
     *
     * <p>⚠ 不要用 {@code transformNormalInverse}：那是给法线用的（含转置），
     * 只在 scale 各轴相等时和这里等价。
     */
    private static Vector3d toLocalDir(final Pose3dc pose, final Vec3 world) {
        // Quaterniondc 的 conjugate 需要传入目标对象（不是无参的）
        final org.joml.Quaterniond inverse = pose.orientation()
                .conjugate(new org.joml.Quaterniond());
        final Vector3d out = inverse.transform(new Vector3d(world.x, world.y, world.z),
                new Vector3d());
        final var scale = pose.scale();
        out.x /= scale.x();
        out.y /= scale.y();
        out.z /= scale.z();
        return out;
    }
}
