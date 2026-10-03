package com.ovo.sablestopnow.server;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.force.ForcePoint;
import com.ovo.sablestopnow.force.ForcePointData;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 可控施力点的服务端执行：每个物理子步往受力点施加一次冲量。
 *
 * <h2>为什么是「冲量 = 力 × dt」</h2>
 * Sable 的 {@code RigidBodyHandle} 只接受冲量（{@code applyImpulseAtPoint} / {@code ForceTotal}），
 * 没有「持续力」这种入口 —— 而物理子步本身就是按固定 dt 推进的，所以「每子步施加 力×dt 的冲量」
 * 与「持续施加该力」在数值上完全等价。这也让我们可以顺理成章地用
 * {@link ForceTotal#applyImpulseAtPoint(ServerSubLevel, Vector3dc, Vector3dc)}
 * 把力加在<b>点上</b>：冲量的作用点偏离质心时，求解器会自动产生力矩
 * （推一个模型的一角，它会转起来，而不是平地平移）。
 *
 * <h2>频道</h2>
 * 「机械动力无线红石」的频道就是 Create 的红石链接频率：一对 {@code ItemStack} 槽位。
 * 玩家在左栏只填一个物品（+ 可选自定义名），我们把它同时放进两个槽位 ——
 * 这样与「两个槽放同一个物品」的常规接法一致，玩家那边不需要额外约定。
 * 通电判定直接问 Create 的网络：{@code networksIn(level)} 里该频率的网络成员
 * 是否有 {@code getTransmittedStrength() > 0} 的。
 */
public final class ForcePointService {

    /** 一处调试/诊断信息用的计数器（用 /sablestopnow 的诊断看）。 */
    private static int lastApplied;

    /** 见 {@link #tick} 顶部的说明：施力点暂时停用（用户已把它标为待办）。 */
    private static final boolean DISABLED_FOR_NOW = true;

    // ---- 诊断：跳过（锁定）与封顶（交付量闸门）各自的计数 ----
    private static int skippedLocked;
    /** 质量数据无效（质量为 0/负/NaN）而拒绝出力的次数 —— 见下面 isInvalid 的说明。 */
    private static int skippedNoMass;
    private static int cappedBudget;
    private static long diagLastLogMs;
    /** 最近一次实际用到的原始力臂长度（格）。正常结构应当远小于 8。 */
    private static double lastArm = -1.0;
    /**
     * 「如果传世界受力点」会得到的力臂长度（格）—— 纯诊断，不参与计算。
     *
     * <p>把它和 {@link #lastArm} 并排打出来，下一次实测日志就能自证坐标系：
     * 局部臂应该在 1 格量级，世界臂应该在 2×10⁷ 量级。两者差两千万倍，
     * 正是前几轮「1N 飞出 1e8 m/s」的全部来源。
     */
    private static double lastArmWorld = -1.0;
    /** 最近一次交付的冲量长度（N·s），诊断用。 */
    private static double lastImpulse = -1.0;
    /** 一次 applyImpulseAtPoint 前后的瞬间线速度（诊断用）。 */
    private static double lastVBefore = -1.0;
    private static double lastVAfter = -1.0;
    /** 力臂长度上限（格）：超过它一定是坐标系不一致，夹住以免求解器发散。 */
    private static final double MAX_TORQUE_ARM = 8.0;

    /** 每个结构在当前游戏刻内已经交付的冲量（N·s）。 */
    private static final Map<UUID, double[]> BUDGET = new HashMap<>();
    private static long budgetTick = Long.MIN_VALUE;

    /** 施力点连续多少次扫描查不到结构才会被清理（见 {@link #prune}）。 */
    private static final int PRUNE_MISSES_REQUIRED = 10;
    /** 每个施力点连续「查不到结构」的次数。 */
    private static final Map<UUID, Integer> PRUNE_MISSES = new HashMap<>();

    // ==================== 临时实验：单发脉冲 ====================
    //
    // 已排除：冲量过量（capped=0）、锁定堆积（skipped-locked=0 时照样飞）、
    // 坐标系不一致（arm=0.62 正常、armW=2.89e7 是错的那个）、质量为零（mass=1.5 kg 真实值）、
    // 法线没归一化（ForcePoint 存的就是面法线轴向量）。
    //
    // 剩下的判据只能来自「从静止打一发」：1.5 kg 的刚体吃 0.005 N·s 的冲量，
    // Δv 应该是 0.0033 m/s。而实测每秒累积到 1.9e6 m/s，说明每次交付都被放大了 ~6e8 倍。
    // 每子步都打（200 次/秒）会把「第一发」淹没掉 —— 所以这里只在「开启」的上升沿打一发，
    // 然后静默 3 秒，让日志里出现干净的 before -> after。
    //
    // 结论拿到后把 PULSE_TEST 改回 false（或整段删掉）。
    private static final boolean PULSE_TEST = false;
    /** 等待打脉冲的施力点（「开启」上升沿置入）。 */
    private static final Set<UUID> PULSE_PENDING = new java.util.HashSet<>();
    /** 脉冲后的静默截止（游戏刻）。 */
    private static final Map<UUID, Long> PULSE_COOLDOWN = new HashMap<>();
    private static final Map<UUID, Boolean> LAST_ENABLED = new HashMap<>();
    private static double lastShotBefore = -1.0;
    private static double lastShotAfter = -1.0;
    private static double lastShotImpulse = -1.0;
    /** 单发脉冲实验：这一发实际造成的 Δv（向量差，与起始速度无关）。 */
    private static double lastShotDelta = -1.0;
    /** 单发脉冲实验：当前用的是第几种「点/方向」约定（0=局部 1=世界 2=相对质心偏移）。 */
    private static int lastShotVariant = -1;
    private static int shotVariant;

    /** 取（必要时重置）某个结构本刻的额度槽。 */
    private static double[] budgetSlot(final UUID body, final long gameTime) {
        if (gameTime != budgetTick) {
            budgetTick = gameTime;
            BUDGET.clear();
        }
        return BUDGET.computeIfAbsent(body, id -> new double[1]);
    }

    /**
     * 每 3 秒打一次「有施力点的那些结构」的位姿与速度。
     *
     * <p>为什么必须打速度和位置：用户报告「**1N 也飞走**」——力的大小无关，说明不是力点把它推走的。
     * 这一行能直接分清两种完全不同的现象：
     * <ul>
     *   <li>速度很大（几十以上）→ 确实被施了力/冲量；</li>
     *   <li>速度接近 0 但位置突然跑远 → 是 <b>teleport</b>（配合吸附 / 耦合辅助 / 拖动搬运），与力点无关。</li>
     * </ul>
     * 没有这组数就只能靠猜，而我已经猜错两轮了。
     */
    private static void diag(final ServerSubLevelContainer container, final ServerLevel level) {
        final long now = System.currentTimeMillis();
        // ⚠ 有施力真正落地时改成 1 秒一次。
        // 3 秒一次的采样已经让我误判三轮了：上一次测试里连「施加的那一刻」都没被采到。
        // 施加的瞬间与它之后的速度才是判据，所以频率必须压过「飞走」发生的时间尺度。
        final long interval = lastApplied > 0 || skippedLocked > 0 ? 1000L : 3000L;
        if (now - diagLastLogMs < interval) {
            return;
        }
        diagLastLogMs = now;
        final StringBuilder sb = new StringBuilder();
        for (final ForcePoint point : ForcePointData.get(level).all().values()) {
            final ServerSubLevel sub = (ServerSubLevel) container.getSubLevel(point.body());
            if (sub == null || sub.isRemoved()) {
                continue;
            }
            final var pos = sub.logicalPose().position();
            final double speed = sub.latestLinearVelocity == null ? 0.0 : sub.latestLinearVelocity.length();
            final Vector3dc com = sub.getMassTracker() == null ? null : sub.getMassTracker().getCenterOfMass();
            // ⚠ 质量在这里读，而不是在 tick 的施加路径里读。
            // 原因是施加路径有一堆提前 continue（未开启 / 已锁定 / 没通电 / 质量无效），
            // 只要结构是锁定的就永远走不到读质量那一步 —— 上一轮实测就是这个下场：
            // 连续十几行 mass=-1.000，白跑一轮。
            // 质量是**结构**的属性，不是施力点的属性，所以在这里无条件打出来。
            final MassData md = sub.getMassTracker();
            final double mass = md == null ? -1.0 : md.getMass();
            final double inverseMass = md == null ? -1.0 : md.getInverseMass();
            sb.append(String.format(java.util.Locale.ROOT,
                    " [%s en=%s mag=%.0f local=(%.0f,%.0f,%.0f) com=%s arm=%.2f armW=%.3g"
                            + " mass=%.4g invM=%.4g shot[var=%d J=%.4g dv=%.4g] %.4g->%.4g worldY=%.1f v=%.2f]",
                    sub.getUniqueId().toString().substring(0, 8), point.enabled(), point.magnitude(),
                    point.localPoint().x, point.localPoint().y, point.localPoint().z,
                    com == null ? "?" : String.format(java.util.Locale.ROOT, "(%.0f,%.0f,%.0f)",
                            com.x(), com.y(), com.z()),
                    lastArm, lastArmWorld,
                    mass, inverseMass, lastShotVariant, lastShotImpulse, lastShotDelta,
                    lastShotBefore, lastShotAfter,
                    pos.y(), speed));
        }
        if (sb.length() == 0) {
            return;
        }
        SablestopNow.LOGGER.info("[model-diag] force: applied={} skipped-locked={} skipped-nomass={} capped={} |{}",
                lastApplied, skippedLocked, skippedNoMass, cappedBudget, sb);
        skippedLocked = 0;
        skippedNoMass = 0;
        cappedBudget = 0;
    }

    private ForcePointService() {
    }

    public static int lastAppliedCount() {
        return lastApplied;
    }

    /** 物理子步入口（挂在 {@link MateRegistry#physicsTick} 上）。 */
    public static void tick(final ServerSubLevelContainer container, final ServerLevel level, final double timeStep) {
        // ⚠⚠ 施力点目前**停用**（用户明确说"暂时跳过，标记为待办"）。
        //
        // 停用不是因为放弃，而是因为它现在**只会帮倒忙**：
        //
        //   1. Sable 直接拒收我们的力，日志里每子步刷一行
        //      `Force from group sable:magnetic_force (magnitude: 2.9e7) exceeds threshold, discarding!`
        //      —— 幅度 2.9e7 正是 **plot 局部坐标的量级**（结构质心在 2.05e7）。
        //      也就是说 `QueuedForceGroup.applyAndRecordPointForce(force, point)` 这条路径
        //      同样把**受力点的模长折进了力的幅度**，与之前实测出的
        //      `Δv = |point| / m` 是同一个根因。力被丢弃，所以"完全不动"。
        //   2. 每子步都被拒 + 每子步都刷日志 → 一局测试下来日志 9.6 MB（正常约 0.9 MB），
        //      既拖慢测试也把别的诊断淹没。
        //
        // 修的时候从这里开始：先搞清那个入口到底期望哪个坐标系/单位，
        // 再决定传力还是传冲量、传局部点还是世界点。详细记录见 .dshwolf/STATUS.md。
        if (DISABLED_FOR_NOW) {
            return;
        }
        final ForcePointData data = ForcePointData.get(level);
        if (data.all().isEmpty()) {
            lastApplied = 0;
            return;
        }
        final double dt = timeStep <= 0.0 ? 0.05 : Math.min(timeStep, 0.05);

        // 先按结构归并：同一个结构的多个施力点累加到同一个 ForceTotal，最后只 apply 一次
        Map<UUID, ForceTotal> totals = null;
        int applied = 0;
        // 锁定状态按结构查一次就够（一个结构上可能有好几个施力点）
        final Map<UUID, Boolean> locked = new HashMap<>();
        for (final ForcePoint point : data.all().values()) {
            // 单发脉冲实验：只在「开启」的上升沿放一发，其余时间静默（见 PULSE_TEST 说明）。
            final boolean wasEnabled = LAST_ENABLED.getOrDefault(point.id(), Boolean.FALSE);
            LAST_ENABLED.put(point.id(), point.enabled());
            if (point.enabled() && !wasEnabled) {
                PULSE_PENDING.add(point.id());
            }
            if (!point.enabled()) {
                PULSE_COOLDOWN.remove(point.id());
                continue;
            }
            if (PULSE_TEST) {
                final long now = level.getGameTime();
                final Long until = PULSE_COOLDOWN.get(point.id());
                if (!PULSE_PENDING.contains(point.id())) {
                    // 不在待发集合里就静默 —— 但静默期结束后重新排一发，方便反复观察
                    if (until != null && now >= until) {
                        PULSE_PENDING.add(point.id());
                        PULSE_COOLDOWN.remove(point.id());
                    } else {
                        continue;
                    }
                }
            }
            // ⚠ 锁定的结构**不接受**施力点。
            //
            // 「锁定」的语义就是它不动；把一个冲量交给运动学体，它不会当场变成速度，
            // 而是堆在求解器/运动学体里，等锁定一解除就<b>一次性释放</b> ——
            // 表现就是用户实测反馈的「锁定模型的情况下，200N 的力让模型瞬间飞走」。
            // 这里直接跳过，既符合锁定的语义，也把那份攒起来的冲量堵在源头。
            if (locked.computeIfAbsent(point.body(), id -> ModelMoveService.isLocked(level, id))) {
                skippedLocked++;
                continue;
            }
            final Vec3Holder world = worldOf(container, point);
            if (world == null) {
                continue;
            }
            // ⚠ 质量的读取已经挪到 diag() 里无条件打印（见那里的说明），
            // 因为这条路径前面有「未开启 / 已锁定 / 没通电」多个提前 continue，
            // 在这里读会导致锁定结构永远打不出质量。
            if (!channelPowered(level, point)) {
                continue;
            }
            // ⚠⚠ 质量为 0（或负/NaN）的刚体**不能**接受冲量。
            //
            // `MassData.isInvalid()` 的实现就是 `getMass() <= 0`（javap 反编译确认），
            // 也就是说 Sable 自己认为这种刚体的质量数据不可用。
            // 往这种刚体上塞冲量，求解器会算 Δv = 冲量 / 0 → 直接发散：
            // 实测「冲量 0.005 N·s → 速度 1.93×10⁶ m/s」，反推等效质量约 2.6×10⁻⁹ kg。
            // 这也解释了三个一直对不上的现象：
            //   ① 0 N 完全没事（不给冲量就不发散）；
            //   ② 1 N 和 200 N 打出来的速度几乎一样（发散，不是线性）；
            //   ③ 换 API 没用（ForceTotal 与 applyImpulseAtPoint 结果同量级）。
            // 与其交出一个必然发散的值，不如不出力并把原因记下来。
            final MassData tracker = world.sub().getMassTracker();
            if (tracker == null || tracker.isInvalid()) {
                skippedNoMass++;
                continue;
            }
            // 冲量 = 力(N) × dt：效力与「持续施力」完全等价。
            // 交付量另有一道与子步率无关的闸（见下），子步算对时它永不触发。
            final double wanted = point.magnitude() * dt;
            final double[] slot = budgetSlot(point.body(), level.getGameTime());
            final double perTick = point.magnitude() / 20.0;
            final double allowed = Math.min(wanted, Math.max(0.0, perTick - slot[0]));
            if (allowed <= 1.0e-9) {
                cappedBudget++;
                continue;
            }
            if (allowed < wanted - 1.0e-9) {
                cappedBudget++;
            }
            slot[0] += allowed;
            final ForceTotal total = (totals == null ? (totals = new HashMap<>()) : totals)
                    .computeIfAbsent(point.body(), id -> new ForceTotal());

            // ⚠⚠ 坐标系：Sable 的 `ForceTotal` 用的是**结构 plot 局部系**，不是世界系。
            //
            // 它的实现是 `localForce += impulse; localTorque += (point − mass.getCenterOfMass()) × impulse`
            // （字段名就是 localForce / localTorque，由 Sable 在 apply 时按位姿转成世界系）。
            // 而 plot 局部坐标是 ~2.048e7 量级、世界坐标是 ~30 量级 ——
            // 以前这里传的是**世界**受力点，于是力臂成了 |30 − 2.048e7| ≈ 2e7，
            // 力矩被放大两千万倍：结构瞬间获得 ~1e8 m/s 的速度并被 Sable 按「Y 越界」删除。
            // （用户实测反馈：飞走 + 速度显示 104757680.3 m/s）
            //
            // 所以这里两侧都用局部量：`localPoint` 与 `localNormal` 本来就是建点时按 plot 局部存下来的，
            // 不需要（也不应该）先转到世界系。
            final Vector3d localImpulse = new Vector3d(point.localNormal()).mul(allowed);
            // ⚠⚠ 受力点：**必须把力臂夹住**。
            //
            // Sable 的 applyImpulseAtPoint 是 `localTorque += (point − mass.getCenterOfMass()) × impulse`。
            // 只要 point 与质心不在同一个坐标系里，力臂就是 plot 局部量级（~2×10⁷），
            // 力矩被放大两千万倍 → 求解器直接发散 → 速度饱和在 ~1×10⁸ m/s（用户实测 103104685.1）。
            // 而且因为它是**发散**而不是线性的，1N 与 200N 打出来的速度几乎一样 ——
            // 这正是「0N 不飞、1N 就飞、加大到 200N 还是同一个数」的原因。
            //
            // 与其继续猜 getCenterOfMass() 在哪个系里，这里直接把力臂**按长度夹紧**：
            // 无论两边的坐标系是否一致，力矩都不可能超过「8 格力臂」对应的量级，
            // 发散在物理上被杜绝；正常结构（力臂本来就远小于 8 格）手感完全不变。
            final Vector3dc centerOfMass = world.sub().getMassTracker().getCenterOfMass();
            final Vector3d arm = new Vector3d(point.localPoint()).sub(centerOfMass);
            final double rawArm = arm.length();
            if (rawArm > MAX_TORQUE_ARM) {
                arm.mul(MAX_TORQUE_ARM / rawArm);
            }
            arm.add(centerOfMass);
            lastArm = rawArm;
            // 诊断：算出「若传世界受力点」的力臂，和局部臂并排打出来（见 lastArmWorld 注释）。
            lastArmWorld = new Vector3d(world.point()).sub(centerOfMass).length();
            lastImpulse = allowed;
            // ⚠⚠ 不要再走 `ForceTotal` + `applyForcesAndReset`。
            //
            // 实机数据（施力点诊断行）：
            //   关着：applied=0 arm=-1.00   worldY=-58.9    v=0.00
            //   开着：applied=1 arm= 0.67   worldY=64064.9  v=2541156.47   ← 1N！
            // 力臂 0.67 格（正常），受力点与质心同一个坐标系，可 1N 却打出 2.5e6 m/s。
            // 说明 `ForceTotal` 不是「把冲量交给刚体」的入口 —— 它是 Sable **内部**
            // 队列力系统的累加器（`QueuedForceGroup` 那条链），自己塞进去的那点量
            // 会被它按另一套尺度消费掉，放大好几个数量级。
            //
            // `RigidBodyHandle` 自己有 rapier 风格的直接入口：
            // `applyImpulseAtPoint(impulse, point)` —— **两个参数都是世界系**。
            // 用它就没有中间层，给多少就是多少。
            final RigidBodyHandle direct = RigidBodyHandle.of(world.sub());
            if (direct != null) {
                // ⚠⚠ 参数必须是 **plot 局部系**，不是世界系。
                //
                // 判据来自诊断行本身：`com=(20481033,128,20519945)` 与
                // `local=(20481032,128,20519944)` **在同一个坐标系里、只差 1 格**
                // （力臂实测 0.60）。也就是说 Sable 的物理空间就是 plot 局部系 ——
                // 世界系那一套（~30 量级）在这里反而是「异世界坐标」。
                //
                // 【实测结论：坐标系是对的，但它不是飞走的全部原因】
                // 改成本地系之后：arm=0.60（正常）imp=0.005（正常），
                // 可 v 仍然是 1.93e6 m/s。所以真正的问题在 Δv = 冲量/质量 的**质量**上，
                // 见下面的 mass 诊断。
                // 交付前后各读一次速度：Δv 直接给出「等效质量」，比任何推断都硬。
                final Vector3dc vBefore = direct.getLinearVelocity(new Vector3d());
                lastVBefore = vBefore == null ? -1.0 : vBefore.length();
                // ⚠⚠ 单发脉冲实验：轮流试三种「点/方向」约定，一轮就能定谁对。
                //
                // 实测：从静止打**一发** 0.005 N·s，速度直接到 1.933e7 m/s
                // （1.5 kg 按 Δv=J/m 应该是 0.0033）。放大倍数 5.8e9 恰好等于
                // |localPoint| / J = 2.9e7 / 0.005 —— 说明**受力点的模长漏进了速度**。
                // 那就只剩「该传哪个点」这一个变量了，于是三种约定各打一发：
                //   0 = plot 局部的点 + 局部法线（当前实现）
                //   1 = 世界系的点 + 世界系方向
                //   2 = 相对质心的偏移（点 − 质心）+ 局部法线
                // 哪一发打出 ~0.0033 就是对的；打出来还是 1e7 就说明三种都不对，
                // 必须改走 Sable 自己的 QueuedForceGroup。
                // ⚠⚠ 受力点用「相对质心的偏移」，并按实测定律修正冲量。
                //
                // 三种约定各打一发，实测（mag=1N，m=1.5kg，J=0.005）：
                //   var=0 局部点  |p|≈2.9e7  dv=1.933e7
                //   var=1 世界点  |p|≈96     dv=64.14
                //   var=2 相对质心 |p|≈1.07   dv=0.7159
                // 三组都精确满足  Δv = F × |point| / m ：
                //   1×2.9e7/1.5 = 1.93e7 ✓   1×96/1.5 = 63.4 ✓   1×1.07/1.5 = 0.713 ✓
                //
                // ⚠ 这个式子里 **dt 被约掉了** —— 因为冲量 J = F×dt，而结果正比于 J×|point|/(m×dt)。
                // 这正是「0N 没事、1N 飞走、加到 200N 速度还是同一个数」的原因：
                // 真正决定速度的是 |point|，力的大小根本进不去。
                //
                // 物理上我们要的是 Δv = F×dt/m。实测给的是 Δv = F×|point|/m，
                // 所以把冲量乘上 dt/|point| 即可对消：
                //   (F×dt/|point|) × |point| / m = F×dt/m ✓
                final Vector3d armVec = new Vector3d(point.localPoint()).sub(centerOfMass);
                final double armLen = armVec.length();
                final double correction = armLen < 1.0e-6 ? 1.0 : dt / armLen;
                final Vector3d shotImpulse =
                        new Vector3d(point.localNormal()).mul(allowed * correction);
                final Vector3d shotPoint = armVec;
                lastShotVariant = 9;
                // ⚠⚠ 改用 Sable 自己的力队列，不再手工调 applyImpulseAtPoint。
                //
                // 理由（用户一句「如果 Sable 已经提供了 API 那为什么不用呢」是对的）：
                // 手调底层冲量实测出一条 Δv = F×|point|/m 的怪律（dt 被约掉），
                // 说明那个入口的语义/单位根本不是我们以为的那样，靠反推修正属于硬凑。
                //
                // Sable 的公开链路（javap 反编译确认）：
                //   ServerSubLevel.getOrCreateQueuedForceGroup(ForceGroup) → QueuedForceGroup
                //   QueuedForceGroup.applyAndRecordPointForce(force, point)
                //       内部 = ForceTotal.applyImpulseAtPoint(massTracker, force, point)
                //              + recordPointForce(point, force)（后者校验 point 非退化）
                //   ServerSubLevel.applyQueuedForces(system, handle, dt)   ← Sable 每子步自己消费并乘 dt
                //
                // 所以这里**传力（N），不要乘 dt** —— dt 由 Sable 的字面 applyQueuedForces 负责。
                // 受力点与力的方向都用 plot 局部量：ForceTotal 内部是
                // `localForce += f; localTorque += (point − massTracker.getCenterOfMass()) × f`，
                // 而 getCenterOfMass() 实测就是 plot 局部坐标（≈2.05e7 量级），两边必须同系。
                final QueuedForceGroup queue = world.sub()
                        .getOrCreateQueuedForceGroup(ForceGroups.MAGNETIC_FORCE.get());
                if (queue != null) {
                    queue.applyAndRecordPointForce(
                            new Vector3d(point.localNormal()).mul(point.magnitude()),
                            new Vector3d(point.localPoint()));
                }
                final Vector3dc vAfter = direct.getLinearVelocity(new Vector3d());
                lastVAfter = vAfter == null ? -1.0 : vAfter.length();
                // ⚠ Δv 必须按**向量差**算，不能拿两次速度的长度相减：
                // 第一发之后刚体已经在飞了，第 2、3 发不是从静止开始，
                // 用长度相减会因为方向不一致而得出错的增量，白白毁掉后两组数据。
                lastShotDelta = vAfter == null || vBefore == null
                        ? -1.0 : new Vector3d(vAfter).sub(vBefore).length();
                // ⚠⚠ 立刻打日志，**不能**等 1 Hz 的诊断行。
                //
                // 实机教训：1N 一发就能把刚体打到 ~1e7 m/s，它在两个 tick 内就越过 Y 边界
                // 被 Sable 删掉（日志里就是「施力点 dcfe668c 暂时查不到结构 3e9e8c8a」），
                // 而诊断行 1 秒才采一次 —— 采样点全部落在「刚体已经没了」之后，
                // 于是 shot 的三个数一次都没打出来，白跑两轮。
                SablestopNow.LOGGER.info("[model-diag] queue force={} arm={} body={} vNow={}",
                        point.magnitude(), armLen,
                        world.sub().getUniqueId().toString().substring(0, 8),
                        world.sub().latestLinearVelocity == null ? -1.0
                                : world.sub().latestLinearVelocity.length());
                // ⚠ 量完就把速度按回去，让刚体活下来把 var=1、var=2 也跑完。
                // 否则第一发之后刚体立刻被删除，三种约定永远只能测到第一种。
                if (PULSE_TEST) {
                    try {
                        final Vector3dc wAfter = direct.getAngularVelocity(new Vector3d());
                        if (vAfter != null) {
                            direct.addLinearAndAngularVelocity(new Vector3d(vAfter).negate(),
                                    wAfter == null ? new Vector3d() : new Vector3d(wAfter).negate());
                        }
                    } catch (final Throwable t) {
                        SablestopNow.LOGGER.info("[model-diag] shot 复位速度失败：{}", t.toString());
                    }
                }
                lastShotBefore = lastVBefore;
                lastShotAfter = lastVAfter;
                lastShotImpulse = allowed;
                if (PULSE_TEST) {
                    // 打完就静默 1 秒：三种约定在 ~3 秒内全部打完，
                    // 赶在刚体被 Sable 按「Y 越界」删掉之前拿齐数据。
                    PULSE_PENDING.remove(point.id());
                    PULSE_COOLDOWN.put(point.id(), level.getGameTime() + 20L);
                }
            }
            applied++;
        }
        diag(container, level);

        // ⚠ 这一段（攒 ForceTotal → `applyForcesAndReset`）已经**不再调用**。
        // `applyForcesAndReset` 是 Sable 队列力系统（QueuedForceGroup）的提交入口，
        // 自己从外面调它等于把那条链再喂一次 —— 实机表现就是 1N 打出 2.5e6 m/s。
        // 现在力走 `RigidBodyHandle.applyImpulseAtPoint(世界冲量, 世界受力点)`（见上面的说明）。
        lastApplied = applied;
    }

    /** 世界系的受力点与方向（结构没了返回 null）。 */
    private static Vec3Holder worldOf(final ServerSubLevelContainer container, final ForcePoint point) {
        final ServerSubLevel sub = (ServerSubLevel) container.getSubLevel(point.body());
        if (sub == null || sub.isRemoved()) {
            return null;
        }
        final var pose = sub.logicalPose();
        final Vector3dc direction = point.worldDirection(pose);
        if (direction == null || point.magnitude() <= 0.0) {
            return null;
        }
        return new Vec3Holder(sub, new Vector3d(direction), point.worldPoint(pose));
    }

    private record Vec3Holder(ServerSubLevel sub, Vector3d direction, Vector3d point) {
    }

    // ============ 频道通电判定 ============

    /**
     * 该施力点的无线红石频道当前是否通电。
     *
     * <p><b>为什么要反射构造 {@code Couple}：</b>Create 的网络查询键是 catnip 的
     * {@code Couple<Frequency>}，而本项目对 Create 是 {@code transitive = false} 的本地 jar
     * （见 build.gradle：刻意不引入它的传递依赖），catnip 不在编译类路径上。
     * 直接写类型会让编译期就失败；反射只多几行，却把这个依赖留在运行期（运行期 Create 一定会带上 catnip）。
     *
     * <p>整个方法包在 try/catch 里：Create 不在或版本对不上时不能把物理子步带崩 ——
     * 静默判为「没通电」并只记一条日志，是最不容易让人困惑的失败方式。
     */
    private static boolean channelPowered(final ServerLevel level, final ForcePoint point) {
        final ItemStack stack = point.channelStack();
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            if (!ensureReflection()) {
                return false;
            }
            // Create 的红石链接频率是**两个槽**（Couple<Frequency>）：两个物品一起构成频率对，
            // 与红石链接方块上两个格子放的东西一一对应。第二槽留空时用第一个槽的物品，
            // 也就是「同一个物品放两个槽」这种最常见的接法。
            final Object frequency = com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency
                    .of(stack);
            final ItemStack stack2 = point.channelStack2();
            final Object frequency2 = stack2 == null || stack2.isEmpty()
                    ? frequency
                    : com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.Frequency.of(stack2);
            final Object couple = COUPLE_CREATE.invoke(null, frequency, frequency2);
            if (couple == null) {
                return false;
            }
            final Object networks = NETWORKS_IN.invoke(com.simibubi.create.Create.REDSTONE_LINK_NETWORK_HANDLER, level);
            if (!(networks instanceof Map<?, ?> map)) {
                return false;
            }
            final Object members = map.get(couple);
            if (!(members instanceof Set<?> set)) {
                return false;
            }
            for (final Object member : set) {
                if (member instanceof com.simibubi.create.content.redstone.link.IRedstoneLinkable linkable
                        && linkable.isAlive() && linkable.getTransmittedStrength() > 0) {
                    return true;
                }
            }
            return false;
        } catch (final Throwable t) {
            if (!warnedAboutCreate) {
                warnedAboutCreate = true;
                SablestopNow.LOGGER.warn("[sablestopnow] Create redstone link lookup failed; "
                        + "force points will stay off", t);
            }
            return false;
        }
    }

    @Nullable
    private static java.lang.reflect.Method COUPLE_CREATE;
    @Nullable
    private static java.lang.reflect.Method NETWORKS_IN;
    private static boolean reflectionReady;
    private static boolean warnedAboutCreate;

    /** 惰性准备两个反射句柄；Create/catnip 缺失时返回 false（只记一次日志）。 */
    private static boolean ensureReflection() {
        if (reflectionReady) {
            return COUPLE_CREATE != null && NETWORKS_IN != null;
        }
        reflectionReady = true;
        try {
            final Class<?> couple = Class.forName("net.createmod.catnip.data.Couple");
            COUPLE_CREATE = couple.getMethod("create", Object.class, Object.class);
            NETWORKS_IN = com.simibubi.create.content.redstone.link.RedstoneLinkNetworkHandler.class
                    .getMethod("networksIn", net.minecraft.world.level.LevelAccessor.class);
        } catch (final Throwable t) {
            SablestopNow.LOGGER.info("[sablestopnow] redstone link API unavailable ({}); "
                    + "force points will stay off", t.toString());
        }
        return COUPLE_CREATE != null && NETWORKS_IN != null;
    }

    /**
     * 结构消失时清掉它的施力点。
     *
     * <h2>⚠ 为什么不能「一次查不到就删」</h2>
     * 原来的实现是「{@code container.getSubLevel(body)} 返回 null 就删掉这条施力点」。
     * 但 Sable 的 {@code getSubLevel} 返回 null <b>并不等于结构没了</b>：
     * 区块未加载、物理系统正在装卸、世界保存/退出时容器被拆掉，都会短暂查不到。
     * 而清理是每 20 tick 无条件跑一遍的 —— 于是「退出世界」这一类瞬时状态
     * 会把玩家所有的施力点一次性删光，再被保存成空表。
     *
     * <p>实机证据：一次测试里服务端诊断连续 3 次打印出施力点
     * {@code [d067f864 en=false mag=1 …]}，随后玩家退出；退出后存档
     * {@code sablestopnow_force_points.dat} 解出来是
     * {@code TAG_List "points" count=0} —— 一条不剩。
     *
     * <p>所以现在要求「连续 {@link #PRUNE_MISSES_REQUIRED} 次扫描都查不到」才认定结构真的没了，
     * 并且每一次清理都留下日志（谁被删、为什么），不再静默丢数据。
     */
    public static void prune(final ServerSubLevelContainer container, final ServerLevel level) {
        final ForcePointData data = ForcePointData.get(level);
        if (data.all().isEmpty()) {
            PRUNE_MISSES.clear();
            return;
        }
        final java.util.List<UUID> dead = new java.util.ArrayList<>();
        for (final ForcePoint point : data.all().values()) {
            final ServerSubLevel sub;
            try {
                sub = (ServerSubLevel) container.getSubLevel(point.body());
            } catch (final Throwable t) {
                // 容器本身处于半拆卸状态时查询可能抛异常 —— 这同样不是「结构没了」
                continue;
            }
            if (sub != null && !sub.isRemoved()) {
                PRUNE_MISSES.remove(point.id());
                continue;
            }
            final int misses = PRUNE_MISSES.merge(point.id(), 1, Integer::sum);
            // 第一次查不到就记一条：这正是不该删的那种「瞬时查不到」，必须看得见。
            if (misses == 1) {
                SablestopNow.LOGGER.info("[sablestopnow] 施力点 {} 暂时查不到结构 {}（{}）——"
                                + "先记一次，连续 {} 次都查不到才清理",
                        point.id().toString().substring(0, 8), point.body().toString().substring(0, 8),
                        sub == null ? "sub-level 为 null" : "sub-level 已标记移除",
                        PRUNE_MISSES_REQUIRED);
            }
            if (misses >= PRUNE_MISSES_REQUIRED) {
                dead.add(point.id());
            }
        }
        for (final UUID id : dead) {
            PRUNE_MISSES.remove(id);
            data.remove(id);
            SablestopNow.LOGGER.info("[sablestopnow] 施力点 {} 清理：连续 {} 次扫描都找不到它的结构",
                    id.toString().substring(0, 8), PRUNE_MISSES_REQUIRED);
        }
        PRUNE_MISSES.keySet().removeIf(id -> !data.all().containsKey(id));
    }
}
