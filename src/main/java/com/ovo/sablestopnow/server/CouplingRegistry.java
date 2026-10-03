package com.ovo.sablestopnow.server;

import com.ovo.sablestopnow.mate.GearRule;
import com.ovo.sablestopnow.mate.MateFrames;
import com.ovo.sablestopnow.mate.MateRef;
import com.ovo.sablestopnow.mate.RatioRule;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 耦合类配合（比例 / 齿轮）的运行期：每个物理子步测一次驱动量，算出从动结构的目标位姿，
 * 交给 {@link KinematicAssist} 限速执行。
 *
 * <h2>比例</h2>
 * <pre>
 *   d_1     = 基准面 ↔ 第一个从动面 的有向距离（{@link MateFrames#measureDistance}）
 *   d_i目标 = k_i · d_1
 *   修正    = 沿该对的测量轴把从动结构平移 (d_i目标 − d_i当前)
 * </pre>
 * 基准结构本身不受约束 —— 用户要的就是「基准面自由移动，其余按 k 联动」。
 *
 * <h2>齿轮</h2>
 * <pre>
 *   θ_A  = A 绕齿轮轴A、相对固定轴A 的自转角（{@link GearRule#spinAngle}，带符号）
 *   θ_B目标 = θ_B⁰ − ratio · (θ_A − θ_A⁰)
 *   修正  = 绕齿轮轴B 把 B 转到该角（只改这一个自由度，平移一概不碰）
 * </pre>
 *
 * <h2>为什么每子步都重新测量</h2>
 * 因为这类耦合约束的是<b>关系</b>而不是位姿：驱动器由物理/玩家自由决定，从动件只能每步跟随。
 * 这也意味着它必须容忍「玩家手动掰了一下基准」——下一子步就会把从动件拉回比例关系，
 * 而不是像刚体夹具那样硬顶。
 */
public final class CouplingRegistry {

    /** 比例的从动平移速度（格/秒）。 */
    /**
     * 比例从动结构的跟随速度（格/秒）。
     *
     * <p>⚠ 原来是 6.0，实测「联动有严重滞后感」：从动结构是按 `目标 = 当前 + 剩余误差` 每子步刷新的，
     * 而执行端 {@link KinematicAssist} 限速 6 格/秒 —— 基准拖快一点，从动就永远追不上，
     * 看着像「慢半拍」。
     *
     * <p>比例配合的语义本来就是<b>刚性传动</b>（位置 = 基准位移 × k），不是「慢慢跟过去」。
     * 而且执行端每个子步都清零速度，所以高跟随速度不会攒出动量 —— 这里给到 60（≈ 3 格/游戏刻），
     * 正常拖动与下落都跟得住。
     */
    private static final double RATIO_SPEED = 60.0;
    /** 齿轮的从动角速度（度/秒）。 */
    private static final double GEAR_ANGULAR_SPEED = 240.0;

    /** 比例组的运行期状态（不需要落盘：下一子步会重新测量）。 */
    private static final class RatioState {
        boolean initialized;
    }

    /** 齿轮耦合的运行期状态：零位与「已经转过的总角度」（用于跨圈累积）。 */
    private static final class GearState {
        boolean initialized;
        double startA;
        double startB;
        /** 累积的总转角（θ_A 连续化，避免 ±180° 处跳变）。 */
        double unwrappedA;
        double lastRawA;
    }

    private static final Map<UUID, RatioState> RATIO_STATE = new HashMap<>();
    private static final Map<UUID, GearState> GEAR_STATE = new HashMap<>();

    private CouplingRegistry() {
    }

    public static void clear() {
        RATIO_STATE.clear();
        GEAR_STATE.clear();
    }

    public static void tick(final ServerSubLevelContainer container, final ServerLevel level, final double timeStep) {
        final CouplingData data = CouplingData.get(level);
        if (data.ratios().isEmpty() && data.gears().isEmpty()) {
            if (!RATIO_STATE.isEmpty() || !GEAR_STATE.isEmpty()) {
                clear();
            }
            return;
        }
        tickRatios(container, level, data);
        tickGears(container, level, data);
    }

    // ============ 比例 ============

    private static void tickRatios(final ServerSubLevelContainer container, final ServerLevel level,
                                   final CouplingData data) {
        final List<UUID> dead = new ArrayList<>();
        for (final Map.Entry<UUID, CouplingData.RatioGroup> entry : data.ratios().entrySet()) {
            final CouplingData.RatioGroup group = entry.getValue();
            if (group.members().isEmpty()) {
                dead.add(entry.getKey());
                continue;
            }
            final ServerSubLevel base = (ServerSubLevel) container.getSubLevel(group.driver().body());
            if (base == null || base.isRemoved()) {
                dead.add(entry.getKey());
                continue;
            }
            final CouplingData.RatioMember driverMember = group.members().get(0);
            final ServerSubLevel first = (ServerSubLevel) container.getSubLevel(driverMember.ref().body());
            if (first == null || first.isRemoved()) {
                dead.add(entry.getKey());
                continue;
            }
            // 驱动量：基准面与第一个从动面之间的有向距离
            final double driver = MateFrames.measureDistance(base.logicalPose(), bounds(base), group.driver(),
                    first.logicalPose(), bounds(first), driverMember.ref());
            RATIO_STATE.computeIfAbsent(entry.getKey(), id -> new RatioState()).initialized = true;

            for (int i = 1; i < group.members().size(); i++) {
                final CouplingData.RatioMember member = group.members().get(i);
                final ServerSubLevel sub = (ServerSubLevel) container.getSubLevel(member.ref().body());
                if (sub == null || sub.isRemoved()) {
                    continue;
                }
                final double target = RatioRule.target(member.ratio(), driver);
                final double current = MateFrames.measureDistance(base.logicalPose(), bounds(base), group.driver(),
                        sub.logicalPose(), bounds(sub), member.ref());
                final double correction = target - current;
                if (!Double.isFinite(correction) || Math.abs(correction) < 1.0e-4) {
                    continue;
                }
                final Vector3d axis = MateFrames.measureAxis(base.logicalPose(), bounds(base), group.driver(),
                        sub.logicalPose(), bounds(sub), member.ref());
                if (axis == null) {
                    continue;
                }
                final Vector3dc position = sub.logicalPose().position();
                final Vector3d targetPosition = axis.mul(correction).add(position.x(), position.y(), position.z());
                // 只给位置、不给朝向：比例配合不碰转动，免得把结构顺手拧一下
                KinematicAssist.request(level, member.ref().body(), targetPosition, null, RATIO_SPEED, 0.0);
            }
        }
        for (final UUID id : dead) {
            data.removeRatio(id);
            RATIO_STATE.remove(id);
            releaseMembers(container, data, id);
        }
    }

    private static void releaseMembers(final ServerSubLevelContainer container, final CouplingData data,
                                       final UUID groupId) {
        // 组没了：通知执行端停手（结构可能还在被搬）
        for (final CouplingData.RatioGroup group : data.ratios().values()) {
            if (group.id().equals(groupId)) {
                return;
            }
        }
        KinematicAssist.release(groupId);
    }

    // ============ 齿轮 ============

    private static void tickGears(final ServerSubLevelContainer container, final ServerLevel level,
                                  final CouplingData data) {
        final List<UUID> dead = new ArrayList<>();
        for (final Map.Entry<UUID, CouplingData.GearCoupling> entry : data.gears().entrySet()) {
            final CouplingData.GearCoupling coupling = entry.getValue();
            final Double ratio = coupling.ratio();
            if (ratio == null) {
                // 齿数非法：不产生任何运动（而不是拿 NaN 去转结构）
                continue;
            }
            final ServerSubLevel a = (ServerSubLevel) container.getSubLevel(coupling.gearA().body());
            final ServerSubLevel b = (ServerSubLevel) container.getSubLevel(coupling.gearB().body());
            if (a == null || b == null || a.isRemoved() || b.isRemoved()) {
                dead.add(entry.getKey());
                continue;
            }
            final Double spinA = spinAngle(a, coupling.gearA(), coupling.fixedA());
            final Double spinB = spinAngle(b, coupling.gearB(), coupling.fixedB());
            if (spinA == null || spinB == null) {
                // 齿轮轴与固定轴平行（投影退化）：提示过一次就停手，不给抖动值
                continue;
            }
            final GearState state = GEAR_STATE.computeIfAbsent(entry.getKey(), id -> new GearState());
            if (!state.initialized) {
                state.initialized = true;
                state.startA = spinA;
                state.startB = spinB;
                state.unwrappedA = spinA;
                state.lastRawA = spinA;
                continue;
            }
            // 把 θ_A 连续化：每子步加最短角差，于是跨 ±180°、转好几圈都不会跳
            state.unwrappedA += GearRule.shortestDelta(state.lastRawA, spinA);
            state.lastRawA = spinA;

            final double targetSpinB = GearRule.followerAngle(state.startB, state.unwrappedA - state.startA, ratio);
            final double deltaB = GearRule.shortestDelta(spinB, targetSpinB);
            if (!Double.isFinite(deltaB) || Math.abs(deltaB) < 0.02) {
                continue;
            }
            final Vector3d axisB = axisOf(b, coupling.gearB());
            if (axisB == null) {
                continue;
            }
            final Vector3dc position = b.logicalPose().position();
            final Quaterniond target = new Quaterniond(b.logicalPose().orientation())
                    .rotateAxis(GearRule.toRadians(deltaB), axisB);
            KinematicAssist.request(level, coupling.gearB().body(),
                    new Vector3d(position), target, 0.0, GEAR_ANGULAR_SPEED);
        }
        for (final UUID id : dead) {
            data.removeGear(id);
            GEAR_STATE.remove(id);
        }
    }

    /** 某个结构绕它自己的齿轮轴、相对固定轴的自转角（度）。退化时返回 null。 */
    @Nullable
    private static Double spinAngle(final ServerSubLevel sub, final MateRef gearRef, final MateRef fixedRef) {
        final MateFrames.Resolved gear = MateFrames.resolveLocal(bounds(sub), gearRef);
        final MateFrames.Resolved fixed = MateFrames.resolveLocal(bounds(sub), fixedRef);
        if (gear.direction() == null || fixed.direction() == null) {
            return null;
        }
        final Quaterniond orientation = new Quaterniond(sub.logicalPose().orientation());
        final Vector3d gearAxis = orientation.transform(new Vector3d(gear.direction()));
        final Vector3d fixedAxis = orientation.transform(new Vector3d(fixed.direction()));
        // 机身标记：由「局部齿轮轴」确定性派生出来的一个垂直方向，随结构一起转，
        // 于是它相对固定轴的角度就是自转角
        final Vector3d markerLocal = MateFrames.anyPerpendicular(gear.direction());
        final Vector3d marker = orientation.transform(markerLocal);
        return GearRule.spinAngle(toArray(gearAxis), toArray(fixedAxis), toArray(marker));
    }

    /** 齿轮轴在世界系的方向（单位向量）。 */
    @Nullable
    private static Vector3d axisOf(final ServerSubLevel sub, final MateRef gearRef) {
        final MateFrames.Resolved gear = MateFrames.resolveLocal(bounds(sub), gearRef);
        if (gear.direction() == null) {
            return null;
        }
        return new Quaterniond(sub.logicalPose().orientation()).transform(new Vector3d(gear.direction()));
    }

    private static double[] toArray(final Vector3dc v) {
        return new double[]{v.x(), v.y(), v.z()};
    }

    private static dev.ryanhcode.sable.companion.math.BoundingBox3ic bounds(final ServerSubLevel sub) {
        return sub.getPlot().getBoundingBox();
    }

    // ============ 创建 / 修改 / 删除（供网络层调用） ============

    /**
     * 建一个比例组：参考顺序 = 从动面…、最后一个为基准面。
     *
     * <p>各 k 由<b>服务端算出</b>（用当前世界的实测初始距离），而不是客户端传：
     * 客户端与服务端对「初始距离」的认知一旦有偏差，k 就会整体错一个比例，
     * 而那是玩家很难自己发现的错误。初始驱动距离为 0 时直接拒绝（{@link RatioRule#ratios}）。
     */
    public static boolean createRatio(final ServerLevel level, final UUID owner, final String ownerName,
                                      final List<MateRef> refs) {
        if (refs == null || refs.size() < 3) {
            return false;
        }
        final MateRef driver = refs.get(refs.size() - 1);
        final List<MateRef> memberRefs = refs.subList(0, refs.size() - 1);
        final ServerSubLevelContainer container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer
                .getContainer(level);
        if (container == null) {
            return false;
        }
        final double[] initial = new double[memberRefs.size()];
        for (int i = 0; i < memberRefs.size(); i++) {
            final Double measured = measure(container, driver, memberRefs.get(i));
            if (measured == null) {
                return false;
            }
            initial[i] = measured;
        }
        final double[] k = RatioRule.ratios(initial);
        if (k == null) {
            return false;
        }
        final List<CouplingData.RatioMember> members = new ArrayList<>(memberRefs.size());
        for (int i = 0; i < memberRefs.size(); i++) {
            members.add(new CouplingData.RatioMember(memberRefs.get(i), k[i]));
        }
        CouplingData.get(level).putRatio(new CouplingData.RatioGroup(UUID.randomUUID(), driver, members,
                owner, ownerName));
        com.ovo.sablestopnow.network.CouplingNetworking.broadcast(level);
        return true;
    }

    /** 建一个齿轮耦合：参考顺序 = 固定轴A、齿轮轴A、固定轴B、齿轮轴B。 */
    public static boolean createGear(final ServerLevel level, final UUID owner, final String ownerName,
                                     final List<MateRef> refs, final int teethA, final int teethB,
                                     final boolean reversed) {
        if (refs == null || refs.size() != 4 || GearRule.ratio(teethA, teethB, reversed) == null) {
            return false;
        }
        CouplingData.get(level).putGear(new CouplingData.GearCoupling(UUID.randomUUID(),
                refs.get(0), refs.get(1), refs.get(2), refs.get(3), teethA, teethB, reversed, owner, ownerName));
        com.ovo.sablestopnow.network.CouplingNetworking.broadcast(level);
        return true;
    }

    /** 改某个从动面的 k（左栏数字框）。 */
    public static boolean setRatio(final ServerLevel level, final UUID groupId, final int memberIndex,
                                   final double k) {
        if (groupId == null || !RatioRule.isValidRatio(k)) {
            return false;
        }
        final CouplingData data = CouplingData.get(level);
        final CouplingData.RatioGroup group = data.ratios().get(groupId);
        if (group == null || memberIndex < 0 || memberIndex >= group.members().size()) {
            return false;
        }
        final List<CouplingData.RatioMember> members = new ArrayList<>(group.members());
        members.set(memberIndex, new CouplingData.RatioMember(members.get(memberIndex).ref(), k));
        data.putRatio(group.withMembers(members));
        com.ovo.sablestopnow.network.CouplingNetworking.broadcast(level);
        return true;
    }

    /** 改齿数比 / 反转。 */
    public static boolean setGear(final ServerLevel level, final UUID groupId, final int teethA,
                                  final int teethB, final boolean reversed) {
        if (groupId == null || GearRule.ratio(teethA, teethB, reversed) == null) {
            return false;
        }
        final CouplingData data = CouplingData.get(level);
        final CouplingData.GearCoupling coupling = data.gears().get(groupId);
        if (coupling == null) {
            return false;
        }
        data.putGear(new CouplingData.GearCoupling(coupling.id(), coupling.fixedA(), coupling.gearA(),
                coupling.fixedB(), coupling.gearB(), teethA, teethB, reversed,
                coupling.owner(), coupling.ownerName()));
        com.ovo.sablestopnow.network.CouplingNetworking.broadcast(level);
        return true;
    }

    /** 删除一个耦合；同时通知执行端停手，免得留下一个继续搬的辅助。 */
    public static boolean remove(final ServerLevel level, final UUID groupId, final boolean gear) {
        if (groupId == null) {
            return false;
        }
        final CouplingData data = CouplingData.get(level);
        // 先取出旧记录：删除后就没法知道该通知哪些结构停手了
        final CouplingData.RatioGroup oldRatio = gear ? null : data.ratios().get(groupId);
        final CouplingData.GearCoupling oldGear = gear ? data.gears().get(groupId) : null;
        final boolean removed = gear ? data.removeGear(groupId) : data.removeRatio(groupId);
        if (removed) {
            if (gear) {
                GEAR_STATE.remove(groupId);
                if (oldGear != null) {
                    KinematicAssist.release(oldGear.gearA().body());
                    KinematicAssist.release(oldGear.gearB().body());
                }
            } else {
                RATIO_STATE.remove(groupId);
                if (oldRatio != null) {
                    // 每个从动结构都要放掉，否则执行端的辅助会一直跑到超时（100 tick）
                    for (final CouplingData.RatioMember member : oldRatio.members()) {
                        KinematicAssist.release(member.ref().body());
                    }
                }
            }
            com.ovo.sablestopnow.network.CouplingNetworking.broadcast(level);
        }
        return removed;
    }

    /** 用服务端的世界状态测量两个参考的有向距离；结构缺失返回 null。 */
    @Nullable
    private static Double measure(final ServerSubLevelContainer container, final MateRef refA, final MateRef refB) {
        final ServerSubLevel a = (ServerSubLevel) container.getSubLevel(refA.body());
        final ServerSubLevel b = (ServerSubLevel) container.getSubLevel(refB.body());
        if (a == null || b == null || a.isRemoved() || b.isRemoved()) {
            return null;
        }
        return MateFrames.measureDistance(a.logicalPose(), bounds(a), refA, b.logicalPose(), bounds(b), refB);
    }
}
