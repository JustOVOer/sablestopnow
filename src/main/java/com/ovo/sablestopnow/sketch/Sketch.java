package com.ovo.sablestopnow.sketch;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 一张草图：一个平面 + 一串标量变量 + 图元 + 约束。
 *
 * <h2>变量是所有东西的底座</h2>
 * 草图里每一个"能动的东西"都是变量：点的 x/y、圆的半径。图元和约束**只存变量下标**，
 * 不存自己的坐标副本 —— 否则求解器改完坐标，图元里那份旧值就成了第二个真相，
 * 表现就是"解完了但线没动"。
 *
 * <p>求解时把 {@link #variables()} 交给 {@link SketchSolver#solve}，
 * 它<b>就地</b>覆写成解，然后所有图元自动是新位置（因为它们只认下标）。
 *
 * <h2>与三维的关系</h2>
 * 草图自己只知道 {@code (u, v)}。要画到世界里，用 {@link SketchPlane#toLocal} 换算；
 * 要拾取，用 {@link SketchPlane#toPlane}。求解器永远看不到三维。
 *
 * <p>⚠ 这个类**刻意只依赖 JOML + {@link SketchSolver}**，不引用任何 Minecraft 类。
 */
public final class Sketch {

    /** 图元种类。 */
    public enum EntityKind {
        /** 自由点：2 个变量 (x, y)。 */
        POINT(2),
        /** 线段：4 个变量 (ax, ay, bx, by)。 */
        LINE(4),
        /** 圆：3 个变量 (cx, cy, r)。 */
        CIRCLE(3),
        /**
         * 圆弧：6 个变量 (cx, cy, sx, sy, ex, ey) —— **圆心 + 起点 + 终点**。
         *
         * <p>半径与起止角**不存**，按需从这六个坐标算出来（{@link Sketch#arcRadius} /
         * {@link Sketch#arcStart} / {@link Sketch#arcEnd}）：
         * <ul>
         *   <li>{@code r  = hypot(sx − cx, sy − cy)}（恒非负）</li>
         *   <li>{@code a0 = atan2(sy − cy, sx − cx)}</li>
         *   <li>{@code a1 = atan2(ey − cy, ex − cx)}</li>
         * </ul>
         *
         * <p>端点因此是**独立变量**（不再是"由圆心/半径/两个角推出来的从属量"）：
         * "弧端点和别的几何重合"终于能落成真约束，求解器侧的
         * {@code ARC_RADIUS / ARC_LENGTH / ARC_ANGLE / POINT_ARC_COINCIDENT}
         * 也正是按这 6 个操作数定义的。
         *
         * <p>⚠ 圆弧从起点沿**逆时针**扫到终点（见 {@link Sketch#sweep}），扫过的角度可能大于 π
         * （不像"两点定弧"有歧义）。这就是选"圆心+起点+终点"输入法的好处：
         * 表示唯一，不必靠"走哪边"来消歧义。
         */
        ARC(6);

        private final int varCount;

        EntityKind(final int varCount) {
            this.varCount = varCount;
        }

        public int varCount() {
            return varCount;
        }
    }

    /**
     * 一个图元：种类 + 它在变量数组里占用的下标。
     *
     * <p>不存坐标。要坐标就问 {@link Sketch#variable(int)}。
     */
    public record Entity(EntityKind kind, int[] vars) {
        public Entity {
            if (vars.length != kind.varCount()) {
                throw new IllegalArgumentException(kind + " 需要 " + kind.varCount()
                        + " 个变量，给了 " + vars.length);
            }
            vars = vars.clone();
        }

        /** 图元某个变量的当前值。 */
        public double value(final Sketch sketch, final int slot) {
            return sketch.variable(vars[slot]);
        }

        @Override
        public int[] vars() {
            return vars.clone();
        }
    }

    /**
     * 一条约束：种类 + 操作数（变量下标）+ 常量。
     *
     * <p>编码与 {@link SketchSolver} 的约束程序一一对应，所以 {@link #toOps()} 只是拼接。
     */
    public record Constraint(int kind, int[] operands, double value) {
        public Constraint {
            operands = operands.clone();
        }

        @Override
        public int[] operands() {
            return operands.clone();
        }
    }

    private final UUID id;
    private final UUID body;
    private String name;
    private SketchPlane plane;
    private final List<Double> variables = new ArrayList<>();
    private final List<Entity> entities = new ArrayList<>();
    private final List<Constraint> constraints = new ArrayList<>();

    public Sketch(final UUID id, final UUID body, final String name, final SketchPlane plane) {
        this.id = id;
        this.body = body;
        this.name = name;
        this.plane = plane;
    }

    public UUID id() {
        return id;
    }

    /** 草图所在的结构（Sable 子结构 id）。 */
    public UUID body() {
        return body;
    }

    public String name() {
        return name;
    }

    public void rename(final String value) {
        this.name = value;
    }

    public SketchPlane plane() {
        return plane;
    }

    public void setPlane(final SketchPlane value) {
        this.plane = value;
    }

    public List<Entity> entities() {
        return Collections.unmodifiableList(entities);
    }

    public List<Constraint> constraints() {
        return Collections.unmodifiableList(constraints);
    }

    // ------------------------------------------------------------------
    // variables
    // ------------------------------------------------------------------

    public int variableCount() {
        return variables.size();
    }

    /**
     * **仍被图元引用**的变量下标（升序、去重）。
     *
     * <p>⚠ 为什么需要它：{@link #removeEntity} 故意**保留**被删图元的变量
     * （图元只存下标，回收会让后面所有图元的下标整体错位）。
     * 于是变量数组里会出现"没人引用"的孤儿 —— 自由度分析必须把它们**排除**，
     * 否则删掉一条线之后"还需要 N 个约束"永远减不下去（用户实测反馈）。
     */
    public int[] usedVariables() {
        final boolean[] used = new boolean[variables.size()];
        for (final Entity e : entities) {
            for (final int v : e.vars()) {
                if (v >= 0 && v < used.length) {
                    used[v] = true;
                }
            }
        }
        int count = 0;
        for (final boolean b : used) {
            if (b) {
                count++;
            }
        }
        final int[] out = new int[count];
        int i = 0;
        for (int v = 0; v < used.length; v++) {
            if (used[v]) {
                out[i++] = v;
            }
        }
        return out;
    }

    public double variable(final int index) {
        return variables.get(index);
    }

    public void setVariable(final int index, final double value) {
        variables.set(index, value);
    }

    /** 当前变量快照。求解后把解写回来用 {@link #setVariables}。 */
    public double[] variables() {
        final double[] out = new double[variables.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = variables.get(i);
        }
        return out;
    }

    public void setVariables(final double[] values) {
        if (values.length != variables.size()) {
            throw new IllegalArgumentException("变量个数不符：草图有 " + variables.size()
                    + " 个，给了 " + values.length);
        }
        for (int i = 0; i < values.length; i++) {
            variables.set(i, values[i]);
        }
    }

    // ------------------------------------------------------------------
    // geometry
    // ------------------------------------------------------------------

    /**
     * 用一个图元占用接下来 {@code count} 个变量，初值由 {@code initial} 给出。
     *
     * <p>这是唯一会给变量数组追加内容的地方 —— 集中在一处，下标才不会错位。
     */
    private Entity addEntity(final EntityKind kind, final double... initial) {
        if (initial.length != kind.varCount()) {
            throw new IllegalArgumentException("初值个数不符");
        }
        final int base = variables.size();
        final int[] vars = new int[kind.varCount()];
        for (int i = 0; i < vars.length; i++) {
            vars[i] = base + i;
            variables.add(initial[i]);
        }
        final Entity entity = new Entity(kind, vars);
        entities.add(entity);
        return entity;
    }

    /** 加一条线段，坐标是平面内的 {@code (u, v)}。 */
    public Entity addLine(final double ax, final double ay, final double bx, final double by) {
        return addEntity(EntityKind.LINE, ax, ay, bx, by);
    }

    /** 加一个自由点。 */
    public Entity addPoint(final double x, final double y) {
        return addEntity(EntityKind.POINT, x, y);
    }

    /** 加一个圆，{@code (cx, cy)} 是圆心、{@code r} 是半径。 */
    public Entity addCircle(final double cx, final double cy, final double r) {
        return addEntity(EntityKind.CIRCLE, cx, cy, r);
    }

    /**
     * 加一段圆弧：圆心 {@code (cx,cy)}、半径 {@code r}，从 {@code a0} **逆时针**扫到 {@code a1}（弧度）。
     *
     * <p>⚠ 这个重载是**输入法**，不是存储布局：变量里存的是算出来的**起点/终点坐标**
     * （{@code cx cy sx sy ex ey}），半径与角度随用随算 —— 见 {@link EntityKind#ARC}。
     * 画弧时玩家点的就是"圆心 → 起点 → 终点"，所以这个入口保留着。
     *
     * <p>{@code a1} 会自动规范化到 {@code a0} 之后的一圈之内（见 {@link #sweep}）：
     * 玩家点终点时算出来的角度可能比起点小（跨过 0 度），不规范化的话
     * 圆弧会反着画、扫过 300 多度。半径取绝对值（半径恒非负）。
     */
    public Entity addArc(final double cx, final double cy, final double r,
                         final double a0, final double a1) {
        final double radius = Math.abs(r);
        final double end = a0 + sweep(a0, a1); // 终点角规范化到 a0 之后的一圈之内
        return addArcPoints(cx, cy,
                cx + radius * Math.cos(a0), cy + radius * Math.sin(a0),
                cx + radius * Math.cos(end), cy + radius * Math.sin(end));
    }

    /**
     * 直接按**三个点**造一段圆弧：圆心、起点、终点（就是那 6 个变量本身）。
     *
     * <p>不做任何规范化/投影：三个点是什么就存什么。调用方负责给出"落在同一个圆上"的
     * 起点与终点（否则半径按起点算、终点只提供方向，画出来会和存的终点有偏差）。
     */
    public Entity addArcPoints(final double cx, final double cy,
                              final double sx, final double sy,
                              final double ex, final double ey) {
        return addEntity(EntityKind.ARC, cx, cy, sx, sy, ex, ey);
    }

    /**
     * 从 {@code a0} 逆时针扫到 {@code a1} 的**正角度**，范围 {@code (0, 2π]}。
     *
     * <p>返回 2π 而不是 0：起止角相同在圆弧语义下应当是"整圈"而不是"零度"。
     */
    public static double sweep(final double a0, final double a1) {
        double d = (a1 - a0) % (2.0 * Math.PI);
        if (d <= 0.0) {
            d += 2.0 * Math.PI;
        }
        return d;
    }

    // ------------------------------------------------------------------
    // 圆弧的**计算型**访问器（半径/起止角不存变量，一律现算）
    // ------------------------------------------------------------------

    /**
     * 圆弧半径 {@code hypot(sx − cx, sy − cy)} —— **恒非负**（不比 {@code Math.abs} 差，
     * 因为 hypot 本身就不可能是负的）。
     *
     * <p>取的是"圆心 → **起点**"的距离；终点只提供**方向**（{@link #arcEnd}）。
     * 不是圆弧时返回 0（调用方本来就该先判 {@link #kind()}）。
     */
    public double arcRadius(final Entity arc) {
        if (arc.kind() != EntityKind.ARC) {
            return 0.0;
        }
        final int[] v = arc.vars();
        return Math.hypot(variable(v[2]) - variable(v[0]), variable(v[3]) - variable(v[1]));
    }

    /** 圆弧起始角 {@code atan2(sy − cy, sx − cx)}（弧度）；不是圆弧时返回 0。 */
    public double arcStart(final Entity arc) {
        if (arc.kind() != EntityKind.ARC) {
            return 0.0;
        }
        final int[] v = arc.vars();
        return Math.atan2(variable(v[3]) - variable(v[1]), variable(v[2]) - variable(v[0]));
    }

    /** 圆弧终止角 {@code atan2(ey − cy, ex − cx)}（弧度）；不是圆弧时返回 0。 */
    public double arcEnd(final Entity arc) {
        if (arc.kind() != EntityKind.ARC) {
            return 0.0;
        }
        final int[] v = arc.vars();
        return Math.atan2(variable(v[5]) - variable(v[1]), variable(v[4]) - variable(v[0]));
    }

    /**
     * 圆弧从起点**逆时针**扫到终点的正角度，范围 {@code (0, 2π]}；不是圆弧时返回 0。
     *
     * <p>⚠ 一律走 {@link #sweep}：这就是 {@link #addArc} 当初写进去的那条规则，
     * 渲染 / 采样 / 拾取必须共用它，各写一份就会出现"预览是劣弧、松手是优弧"。
     */
    public double arcSweep(final Entity arc) {
        if (arc.kind() != EntityKind.ARC) {
            return 0.0;
        }
        // ★ 用户要求：**圆弧只画劣弧** ✓（"圆弧只画劣弧" 实测反馈 ✓）
        //   `sweep(...)` 返回逆时针正角 (0, 2π] ✗ —— 起点/终点顺序一反过来就是**优弧** ✓
        //   ⇒ 超过 π 就换成 `ccw − 2π`（负角 = 顺时针走**短的那一段** ✓）。
        //   渲染 / 采样 / 拾取共用这个函数（类注释里就写着必须共用 ✓）⇒ 三处自动一致 ✓。
        final double ccw = sweep(arcStart(arc), arcEnd(arc));
        // ⚠ 例外：起点与终点**几乎重合**时是有意义的"整圈"（ccw ≈ 2π）✓
        //   —— 离线断言已经在守这条（"起点终点相同的圆弧 ⇒ 扫角约 2π" ✓），
        //   一刀切换成负角会把它砍成 0 ✗（我第一版就是这样，门禁立刻报红 ✓）。
        final boolean full = ccw >= 2.0 * Math.PI - 1.0e-9;
        return (ccw > Math.PI && !full) ? ccw - 2.0 * Math.PI : ccw;
    }

    /**
     * 圆弧上参数 {@code t ∈ [0,1]} 处的点。
     *
     * @return {@code {u, v}}；传进来的不是圆弧则返回 null
     */
    @Nullable
    public double[] arcPoint(final Entity arc, final double t) {
        if (arc.kind() != EntityKind.ARC) {
            return null;
        }
        final int[] v = arc.vars();
        final double cx = variable(v[0]);
        final double cy = variable(v[1]);
        final double r = arcRadius(arc);
        final double a0 = arcStart(arc);
        final double a = a0 + sweep(a0, arcEnd(arc)) * t;
        return new double[] {cx + r * Math.cos(a), cy + r * Math.sin(a)};
    }

    /**
     * 圆弧的两个端点 {@code {起点, 终点}}；不是圆弧则返回 null。
     *
     * <p>直接给**存起来的那 4 个变量**（端点就是变量 ✓ 不做 cos/sin 往返）：
     * 往返会引入 1e-16 级别的误差，而轮廓焊接（{@code SketchProfiles.findLoops}）
     * 和吸附都按坐标相等认连接 —— 没必要在这里抖一下。
     */
    @Nullable
    public double[][] arcEndpoints(final Entity arc) {
        if (arc.kind() != EntityKind.ARC) {
            return null;
        }
        final int[] v = arc.vars();
        return new double[][] {
                {variable(v[2]), variable(v[3])},
                {variable(v[4]), variable(v[5])},
        };
    }

    /** 矩形 = 4 条线段首尾相接（返回顺序：下、右、上、左）。 */
    public List<Entity> addRectangle(final double x, final double y, final double w, final double h) {
        final double x1 = x + w;
        final double y1 = y + h;
        final List<Entity> out = new ArrayList<>(4);
        out.add(addLine(x, y, x1, y));
        out.add(addLine(x1, y, x1, y1));
        out.add(addLine(x1, y1, x, y1));
        out.add(addLine(x, y1, x, y));
        return out;
    }

    /**
     * 删掉一个图元。
     *
     * <p>变量**故意留着不删**：图元只存变量下标，删中间一个图元的变量会让后面所有图元的下标整体前移，
     * 而约束里存的也是下标 —— 那样的"顺手清理"会把整张草图的拓扑搅乱。
     * 留着变量只是浪费几个 double，代价远小于下标错位。
     *
     * <p>⚠ 但**引用这些变量的约束必须一起删**：约束是"把几何钉住"的东西，
     * 图元没了它还在，求解器就会去约束一堆已经不存在的几何 ——
     * 轻则把剩下的图元推到奇怪的位置，重则整个求解失败（表现为"删一条线，其它全歪了"）。
     */
    public boolean removeEntity(final Entity entity) {
        if (!this.entities.remove(entity)) {
            return false;
        }
        final java.util.Set<Integer> gone = new java.util.HashSet<>();
        for (final int v : entity.vars()) {
            gone.add(v);
        }
        // 只要引用了被删图元的**任何一个**变量，这条约束就失去意义了 ——
        // 包括跨图元的重合约束（另一端还在，但"和它重合"这件事已经不成立）。
        this.constraints.removeIf(c -> {
            for (final int v : c.operands()) {
                if (gone.contains(v)) {
                    return true;
                }
            }
            return false;
        });
        return true;
    }

    /**
     * 从存档恢复：直接灌入变量、图元与约束，**不走** {@code addLine} 那套构造路径。
     *
     * <p>为什么需要单独一个入口：{@code addLine} 是「追加变量 + 记下标」，
     * 而存档里下标已经是定好的，再走一遍追加会整体错位。
     *
     * <p>会做一次一致性校验：图元引用的下标必须都在变量范围内。
     * 坏存档宁可当场拒绝，也不要留一张"下标越界"的草图到运行期才炸。
     */
    public void restore(final double[] vars, final List<Entity> restoredEntities,
                        final List<Constraint> restoredConstraints) {
        for (final Entity e : restoredEntities) {
            for (final int v : e.vars()) {
                if (v < 0 || v >= vars.length) {
                    throw new IllegalArgumentException("坏存档：图元引用了越界变量 " + v
                            + "（共 " + vars.length + " 个变量）");
                }
            }
        }
        for (final Constraint c : restoredConstraints) {
            for (final int v : c.operands()) {
                if (v < 0 || v >= vars.length) {
                    throw new IllegalArgumentException("坏存档：约束引用了越界变量 " + v
                            + "（共 " + vars.length + " 个变量）");
                }
            }
        }
        variables.clear();
        for (final double d : vars) {
            variables.add(d);
        }
        entities.clear();
        entities.addAll(restoredEntities);
        constraints.clear();
        constraints.addAll(restoredConstraints);
    }

    // ------------------------------------------------------------------
    // constraints
    // ------------------------------------------------------------------

    /** 加一条约束。操作数是变量下标。 */
    // ================= 辅助线（construction geometry ✓ SolidWorks 的构造几何 ✓）=============

    /**
     * 被标记为**辅助线**的图元 ✓（用"第一个变量下标"当身份 ✓）。
     *
     * <p>为什么不给 {@link Entity} 加字段 ✗✓：它是 record ✓ 加字段要改**所有**构造点 ✗
     * 而变量下标在一条草图里**唯一标识一个图元** ✓（每个图元的变量是连续分配的 ✓）
     * ⇒ 用 {@code Set<Integer>} 记身份最省事 ✓ 也不动序列化格式 ✓✓。
     *
     * <p>语义（与 SolidWorks 一致 ✓）：辅助线只作**参考/约束基准** ✓
     * **不参与轮廓** ✗ ⇒ 拉伸/旋转不会把它算进面里 ✓（{@code SketchProfiles} 那边要跳过它 ✓）。
     */
    private final java.util.Set<Integer> construction = new java.util.HashSet<>();

    /** 这条图元是不是辅助线 ✓。 */
    public boolean isConstruction(final Entity entity) {
        return entity != null && this.construction.contains(entity.vars()[0]);
    }

    /** 切换辅助线标记 ✓（UI 上的"构造几何"开关 ✓）。 */
    public boolean toggleConstruction(final Entity entity) {
        if (entity == null) {
            return false;
        }
        final int key = entity.vars()[0];
        if (!this.construction.remove(key)) {
            this.construction.add(key);
            return true;
        }
        return false;
    }

    /** 全部辅助线 ✓（渲染时画虚线/暗色 ✓ 轮廓计算时跳过 ✓）。 */
    public java.util.List<Entity> constructionEntities() {
        final java.util.List<Entity> out = new java.util.ArrayList<>();
        for (final Entity e : entities()) {
            if (this.construction.contains(e.vars()[0])) {
                out.add(e);
            }
        }
        return out;
    }

    public Constraint addConstraint(final int kind, final double value, final int... operands) {
        final Constraint c = new Constraint(kind, operands, value);
        constraints.add(c);
        return c;
    }

    public boolean removeConstraint(final Constraint constraint) {
        return constraints.remove(constraint);
    }

    /**
     * 原位替换一条约束。
     *
     * <p>存在的理由：{@code Constraint} 是不可变 record，改一个尺寸的值只能换一个新对象；
     * 而「删掉旧的再 add 新的」会让它排到列表末尾 —— {@link #toOps()} 与 {@link #toConsts()}
     * 是**按同一顺序**生成的，顺序一变两边都得跟着变，很容易只改一边。
     * 原位替换让顺序保持不变。
     */
    /**
     * **删掉一条约束** ✓（用户要求：改值框的「删除」= 删除该尺寸及其约束 ✓）。
     *
     * <p>只动约束表 ✓：几何（变量）留在原地 ✓ —— 这与 SolidWorks 一致
     * （删掉尺寸后图形不会跳回去，只是重新变成欠约束 ✓）。
     */
    public void removeConstraint(final int index) {
        if (index < 0 || index >= constraints.size()) {
            return;
        }
        constraints.remove(index);
    }

    public void replaceConstraint(final int index, final Constraint constraint) {
        constraints.set(index, constraint);
    }

    /** 在指定位置插入约束（配合 {@link #replaceConstraint} 使用）。 */
    public void insertConstraint(final int index, final Constraint constraint) {
        constraints.add(index, constraint);
    }

    /**
     * 把整张草图打成求解器要的约束程序。
     *
     * <p>顺序必须与 {@link #toConsts()} 一致（常量按约束出现顺序取用），
     * 所以这两个方法总是一起用，别单独调用。
     */
    public int[] toOps() {
        final List<Integer> ops = new ArrayList<>();
        for (final Constraint c : constraints) {
            // ★★ **线线距离在模型层也要自己组合** ✓✓（用户实测："能调、反应正确，但尺寸标红"✗）
            //
            // 之前我只把组合写进了 `SketchSolver.Program.parallelLinesDistance(...)`（那是**构造器**路径 ✗），
            // 而真实草图是把**约束的 kind 原样**写进程序 ⇒ 求解器拿到的仍是 kind 14（上游那条
            // 会解偏的组合 ✓ 离线复现过：目标 8 解成 0.667 ✗）⇒ 实测值永远对不上设定值 ⇒ 标红 ✓。
            //
            // 这里展开成**两条基本约束**：
            //   ① PARALLEL(l0, l1)（不吃常量 ✓）
            //   ② POINT_LINE_DISTANCE(l0.p0 → l1)（吃 **1** 个常量 ✓ 与 kind 14 原本的常量个数一致 ✓
            //      ⇒ `toConsts()` **不需要改** ✓ 两边仍然对齐 ✓）
            if (c.kind() == SketchSolver.PARALLEL_LINES_DISTANCE && c.operands().length >= 8) {
                final int[] v = c.operands();
                ops.add(SketchSolver.PARALLEL);
                for (final int operand : v) {
                    ops.add(operand);
                }
                ops.add(SketchSolver.POINT_LINE_DISTANCE);
                ops.add(v[0]);
                ops.add(v[1]);
                ops.add(v[4]);
                ops.add(v[5]);
                ops.add(v[6]);
                ops.add(v[7]);
                continue;
            }
            ops.add(c.kind());
            for (final int operand : c.operands()) {
                ops.add(operand);
            }
        }
        final int[] out = new int[ops.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = ops.get(i);
        }
        return out;
    }

    /** 约束常量，按约束出现顺序 —— **只为吃常量的约束产出**（见下面的说明）。 */
    public double[] toConsts() {
        final List<Double> out = new ArrayList<>();
        for (final Constraint c : constraints) {
            // ⚠⚠ 只对 takesConstant 的约束产出值！
            // 原生侧是按「这个约束吃不吃常量」逐个取的，如果我们给 HORIZONTAL 这类
            // 也塞一个值，后面的常量就会整体错位一格 —— 症状是"解成功了但尺寸不对"。
            // 这个坑真的踩过：矩形草图的宽塌成 0、高变成 3。
            if (SketchSolver.takesConstant(c.kind())) {
                out.add(c.value());
            }
        }
        final double[] result = new double[out.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = out.get(i);
        }
        return result;
    }

    /**
     * 求解并把解写回变量。
     *
     * <p>注意 {@code -7}（结构性无解）不会抛异常，而是以 {@code satisfied=false} 的形式
     * 从 {@link SketchSolver.Result} 返回 —— 交互式草图里"过约束"是玩家随时会遇到的状态，
     * 应该由 UI 提示，而不是炸掉。
     */
    public SketchSolver.Result solve(final SketchSolver solver) {
        final double[] vars = variables();
        // 先存一份：求解是**就地**覆写 vars 的，塌了要能回滚
        this.preSolve = vars.clone();
        final double extentBefore = extent(vars);
        final SketchSolver.Result result = solver.solve(vars, toOps(), toConsts());
        // ⚠ 不许解塌。
        //
        // 欠约束的草图有无穷多解，其中**包含退化解**：比如"四条边两两水平竖直 + 四角重合"
        // 也允许一个高度为 0 的扁平矩形。求解器从玩家画出的形状出发时通常不会跳到那里，
        // 但一旦跳过去，玩家看到的就是"矩形变成一条线"——而数据、约束、UI 全都正常，
        // 极难排查。
        //
        // 这里的守卫很朴素：如果解出来的图形**范围缩水到几乎为零**，而解之前是有范围的，
        // 那就判定为退化解，**保留原几何**并把结果标成不满足。
        // 宁可让玩家看到"约束没生效"，也不要让图形凭空塌掉。
        final double extentAfter = extent(vars);
        if (extentBefore > 1.0e-6 && extentAfter < extentBefore * 1.0e-3) {
            setVariables(this.preSolve);
            return new SketchSolver.Result(false, result.converged(), result.iterations(),
                    Math.max(1, result.unsatisfied()), this.preSolve);
        }
        setVariables(vars);
        return result;
    }

    /** 解之前保存的一份变量（解塌时回滚用）。 */
    private double[] preSolve = new double[0];

    /** 变量数组的"范围"：包围盒对角线长度。用来判断有没有解塌。 */
    private static double extent(final double[] vars) {
        if (vars.length < 2) {
            return 0.0;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        // 变量是成对的 (u, v) —— 变量个数永远是偶数（图元都按点/圆心+半径分配）
        for (int i = 0; i + 1 < vars.length; i += 2) {
            minX = Math.min(minX, vars[i]);
            maxX = Math.max(maxX, vars[i]);
            minY = Math.min(minY, vars[i + 1]);
            maxY = Math.max(maxY, vars[i + 1]);
        }
        return Math.hypot(maxX - minX, maxY - minY);
    }

    // ------------------------------------------------------------------
    // 3D bridge
    // ------------------------------------------------------------------

    /** 一个图元的变量值换算成平面内坐标。 */
    public Vector3d planePoint(final int varX, final int varY) {
        return new Vector3d(variable(varX), variable(varY), 0.0);
    }

    /** 一个图元换算到 plot 局部系（渲染/拾取用）。 */
    public Vector3d toLocal(final int varX, final int varY) {
        return plane.toLocal(variable(varX), variable(varY));
    }

    /** 把 plot 局部系的一个点投影回草图的平面内坐标（拾取用）。 */
    public Vector3d toPlane(final Vector3dc local) {
        return plane.toPlane(local, new Vector3d());
    }
}
