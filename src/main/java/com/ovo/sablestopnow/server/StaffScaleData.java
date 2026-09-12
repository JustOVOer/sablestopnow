package com.ovo.sablestopnow.server;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 物理结构缩放（视觉 + 实体碰撞；Sable 的 rapier 刚体不支持缩放，见 docs/staff-enhance-design.md §8.12）。
 *
 * <p>两个职责：
 * <ol>
 *   <li><b>活跃缩放会话</b>：按住 X 开始（先把选中的结构统一回 1.0 倍），滚轮给一个绝对倍率 f，
 *       每个成员被驱动到 {@code centroid0 + (pos0 - centroid0) * f}，自身缩放为 {@code f}
 *       —— 组内形状（相对质心的布局）保持不变；</li>
 *   <li><b>缩放持久化</b>：Sable 自己的序列化不写 scale，退出/重载会丢；这里自己存一份并定期回灌。</li>
 * </ol>
 */
public final class StaffScaleData extends net.minecraft.world.level.saveddata.SavedData {
    public static final String ID = "sablestopnow_scale";

    /** 物理结构 -> 缩放倍率（只记录 ≠1 的，用于跨存档恢复）。 */
    private final Map<UUID, Float> scales = new HashMap<>();

    public StaffScaleData() {
    }

    public static StaffScaleData get(final ServerLevel level) {
        return level.getChunkSource().getDataStorage().computeIfAbsent(
                new net.minecraft.world.level.saveddata.SavedData.Factory<>(StaffScaleData::new, StaffScaleData::load, null),
                StaffScaleData.ID);
    }

    private static StaffScaleData load(final CompoundTag tag, final HolderLookup.Provider provider) {
        final StaffScaleData data = new StaffScaleData();
        final ListTag list = tag.getList(ID, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag entry = list.getCompound(i);
            data.scales.put(NbtUtils.loadUUID(entry.get("sub")), entry.getFloat("scale"));
        }
        return data;
    }

    @Override
    public @NotNull CompoundTag save(final CompoundTag tag, final HolderLookup.@NotNull Provider provider) {
        final ListTag list = new ListTag();
        for (final Map.Entry<UUID, Float> entry : this.scales.entrySet()) {
            final CompoundTag entryTag = new CompoundTag();
            entryTag.put("sub", NbtUtils.createUUID(entry.getKey()));
            entryTag.putFloat("scale", entry.getValue());
            list.add(entryTag);
        }
        tag.put(ID, list);
        return tag;
    }

    public float scaleOf(final UUID subLevel) {
        return this.scales.getOrDefault(subLevel, 1.0f);
    }

    public void setScale(final UUID subLevel, final float scale) {
        if (Math.abs(scale - 1.0f) < 1.0e-3f) {
            this.scales.remove(subLevel);
        } else {
            this.scales.put(subLevel, scale);
        }
        this.setDirty(true);
    }

    public Map<UUID, Float> allScales() {
        return this.scales;
    }

    // ==================================================================
    //  活跃缩放会话
    // ==================================================================

    private static final Map<UUID, ScaleSession> SESSIONS = new HashMap<>();

    /** 一个成员的初始状态（会话开始时记录）。 */
    private static final class Member {
        private final ServerSubLevel sub;
        private final Vector3d pos0 = new Vector3d();
        private final Quaterniond orientation = new Quaterniond();
        /** 会话开始时是否处于航空学锁定状态（缩放前会临时解锁，结束时还原）。 */
        private final boolean wasLocked;

        private Member(final ServerSubLevel sub, final boolean wasLocked) {
            this.sub = sub;
            this.pos0.set(sub.logicalPose().position());
            this.orientation.set(sub.logicalPose().orientation());
            this.wasLocked = wasLocked;
        }
    }

    private static final class ScaleSession {
        private final ServerLevel level;
        private final List<Member> members = new ArrayList<>();
        private final Vector3d centroid0 = new Vector3d();
        /** 最近一次下发的目标位置（用于诊断是否被物理/约束拉回）。 */
        private final Map<UUID, Vector3d> lastTargets = new LinkedHashMap<>();

        private ScaleSession(final ServerLevel level) {
            this.level = level;
        }
    }

    /**
     * 开始缩放会话：把选中的结构统一回 1.0 倍并记录基线。
     *
     * @return 参与的结构数
     */
    public static int begin(final ServerLevel level, final UUID player, final Collection<UUID> ids) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return 0;
        }
        final StaffScaleData data = get(level);
        final var handler = dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler.get(level);
        final ScaleSession session = new ScaleSession(level);
        for (final UUID id : ids) {
            final ServerSubLevel sub = (ServerSubLevel) container.getSubLevel(id);
            if (sub == null || sub.isRemoved()) {
                continue;
            }
            // 锁定状态（航空学固定约束）会把 teleport 拉回去，缩放期间必须先解锁，结束时还原
            final boolean locked = handler != null && handler.isLocked(sub);
            if (locked) {
                handler.toggleLock(id);
            }
            // 统一到未缩放状态
            sub.logicalPose().scale().set(1.0, 1.0, 1.0);
            data.setScale(id, 1.0f);
            session.members.add(new Member(sub, locked));
        }
        if (session.members.isEmpty()) {
            return 0;
        }
        final Vector3d centroid = new Vector3d();
        for (final Member member : session.members) {
            centroid.add(member.pos0);
        }
        centroid.div(session.members.size());
        session.centroid0.set(centroid);
        SESSIONS.put(player, session);
        int lockedCount = 0;
        for (final Member member : session.members) {
            if (member.wasLocked) {
                lockedCount++;
            }
        }
        com.ovo.sablestopnow.SablestopNow.LOGGER.info("[staff] scale begin: {} members ({} were locked -> temporarily unlocked)",
                session.members.size(), lockedCount);
        broadcastScales(level);
        return session.members.size();
    }

    /** 把活跃会话驱动到倍率 factor（成员自身缩放 + 相对质心布局一起缩放）。 */
    public static void update(final ServerLevel level, final UUID player, final float factor) {
        apply(level, player, factor, null);
    }

    /**
     * 彩蛋 Superliminal：按倍率缩放，并把整组放到指定的世界中心（绝对定位）。
     *
     * <p>必须走这条路而不是继续用整组拖拽马达 —— 马达会把成员驱动到“原始相对位姿”，
     * 和缩放会话里的“相对偏移 × 倍率”互相打架，表现就是结构乱飘。
     */
    public static void place(final ServerLevel level, final UUID player, final float factor, final Vector3dc center) {
        apply(level, player, factor, center);
    }

    private static void apply(final ServerLevel level, final UUID player, final float factor,
                              @Nullable final Vector3dc centerOverride) {
        final ScaleSession session = SESSIONS.get(player);
        if (session == null || session.level != level) {
            return;
        }
        final StaffScaleData data = get(level);
        final var pipeline = SubLevelContainer.getContainer(level).physicsSystem().getPipeline();
        final var handler = dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler.get(level);
        for (final Member member : session.members) {
            final ServerSubLevel sub = member.sub;
            if (sub.isRemoved()) {
                continue;
            }
            // 缩放期间被别的系统（例如超速锁）锁住的话要解锁，否则 teleport 会被约束拉回去
            if (handler != null && handler.isLocked(sub)) {
                handler.toggleLock(sub.getUniqueId());
            }
            // 位置：相对初始质心按倍率外扩/内收；有 centerOverride 时以它为组中心（彩蛋用）
            final Vector3d target = new Vector3d(member.pos0).sub(session.centroid0).mul(factor).add(session.centroid0);
            if (centerOverride != null) {
                target.set(member.pos0).sub(session.centroid0).mul(factor).add(centerOverride);
            }
            sub.logicalPose().position().set(target);
            sub.logicalPose().scale().set(factor, factor, factor);
            final RigidBodyHandle handle = RigidBodyHandle.of(sub);
            if (handle != null) {
                handle.teleport(target, member.orientation);
            }
            data.setScale(sub.getUniqueId(), factor);
            session.lastTargets.put(sub.getUniqueId(), new Vector3d(target));
        }
        broadcastScales(level);
    }

    /** 诊断：活跃会话成员是否被物理/约束拉回去了（每 20 tick 由 reapply 调用）。 */
    private static void diagnoseDrift(final ServerLevel level) {
        for (final ScaleSession session : SESSIONS.values()) {
            if (session.level != level) {
                continue;
            }
            for (final Member member : session.members) {
                final Vector3d target = session.lastTargets.get(member.sub.getUniqueId());
                if (target == null || member.sub.isRemoved()) {
                    continue;
                }
                final Vector3dc now = member.sub.logicalPose().position();
                final double drift = now.distance(target);
                if (drift > 0.5) {
                    com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                            "[staff] scale drift on {}: target=({}, {}, {}) now=({}, {}, {}) drift={}",
                            member.sub.getUniqueId(),
                            String.format("%.2f", target.x), String.format("%.2f", target.y), String.format("%.2f", target.z),
                            String.format("%.2f", now.x()), String.format("%.2f", now.y()), String.format("%.2f", now.z()),
                            String.format("%.2f", drift));
                    session.lastTargets.put(member.sub.getUniqueId(), new Vector3d(now));
                }
            }
        }
    }

    /** 结束会话（保留当前结果，并还原会话前处于锁定状态的成员）。 */
    public static void end(final ServerLevel level, final UUID player) {
        final ScaleSession session = SESSIONS.remove(player);
        if (session == null) {
            return;
        }
        final var handler = dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler.get(level);
        if (handler != null) {
            for (final Member member : session.members) {
                if (member.wasLocked && !member.sub.isRemoved() && !handler.isLocked(member.sub)) {
                    handler.toggleLock(member.sub.getUniqueId());
                }
            }
        }
        get(level).setDirty(true);
        broadcastScales(level);
    }

    public static void releaseAll(final UUID player) {
        SESSIONS.remove(player);
    }

    public static void clearSessions() {
        SESSIONS.clear();
    }

    /** 是否正在缩放（用于 HUD/互斥判断）。 */
    public static boolean isScaling(final UUID player) {
        return SESSIONS.containsKey(player);
    }

    /** 本维度「正在被缩放」或「已经缩放（倍率≠1）」的所有结构 id（供超速锁豁免等）。 */
    public static java.util.Set<UUID> activeOrScaledIds(final ServerLevel level) {
        final java.util.Set<UUID> out = new java.util.HashSet<>(get(level).allScales().keySet());
        for (final ScaleSession session : SESSIONS.values()) {
            if (session.level == level) {
                for (final Member member : session.members) {
                    out.add(member.sub.getUniqueId());
                }
            }
        }
        return out;
    }

    /** 当前会话的成员（供 Superliminal 之类的驱动读取）。 */
    public static List<ServerSubLevel> sessionMembers(final UUID player) {
        final ScaleSession session = SESSIONS.get(player);
        if (session == null) {
            return List.of();
        }
        final List<ServerSubLevel> out = new ArrayList<>();
        for (final Member member : session.members) {
            if (!member.sub.isRemoved()) {
                out.add(member.sub);
            }
        }
        return out;
    }

    /** 会话中每个成员的初始世界位置（与 {@link #sessionMembers} 同序）。 */
    public static Map<UUID, Vector3d> sessionBaselines(final UUID player) {
        final ScaleSession session = SESSIONS.get(player);
        final Map<UUID, Vector3d> out = new LinkedHashMap<>();
        if (session == null) {
            return out;
        }
        for (final Member member : session.members) {
            out.put(member.sub.getUniqueId(), new Vector3d(member.pos0));
        }
        return out;
    }

    public static Vector3d sessionCentroid(final UUID player) {
        final ScaleSession session = SESSIONS.get(player);
        return session == null ? null : new Vector3d(session.centroid0);
    }

    /**
     * 把存档里的缩放值回灌到已加载的物理结构上（Sable 自己的序列化不写 scale，重载后会丢）。
     * 每 N tick 调一次即可。
     */
    public static void reapply(final ServerLevel level) {
        diagnoseDrift(level);
        final StaffScaleData data = get(level);
        if (data.allScales().isEmpty()) {
            return;
        }
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        boolean changed = false;
        for (final Map.Entry<UUID, Float> entry : new ArrayList<>(data.allScales().entrySet())) {
            final ServerSubLevel sub = (ServerSubLevel) container.getSubLevel(entry.getKey());
            if (sub == null || sub.isRemoved()) {
                continue;
            }
            // 正在被玩家缩放的结构不插手
            final UUID id = entry.getKey();
            boolean active = false;
            for (final ScaleSession session : SESSIONS.values()) {
                if (session.level == level && session.members.stream().anyMatch(m -> m.sub.getUniqueId().equals(id))) {
                    active = true;
                    break;
                }
            }
            if (active) {
                continue;
            }
            final float target = entry.getValue();
            if (Math.abs(sub.logicalPose().scale().x() - target) > 1.0e-3) {
                sub.logicalPose().scale().set(target, target, target);
                changed = true;
            }
        }
        if (changed) {
            broadcastScales(level);
        }
    }

    /** 把该维度的缩放表广播给所有客户端（Sable 的位姿同步不含 scale）。 */
    public static void broadcastScales(final ServerLevel level) {
        final List<com.ovo.sablestopnow.network.StaffEnhanceNetworking.ScaleEntry> entries = scaleEntries(level);
        foundry.veil.api.network.VeilPacketManager.all(level.getServer())
                .sendPacket(new com.ovo.sablestopnow.network.StaffEnhanceNetworking.SyncScalesPayload(
                        level.dimension().location(), entries));
    }

    public static List<com.ovo.sablestopnow.network.StaffEnhanceNetworking.ScaleEntry> scaleEntries(final ServerLevel level) {
        final List<com.ovo.sablestopnow.network.StaffEnhanceNetworking.ScaleEntry> out = new ArrayList<>();
        for (final Map.Entry<UUID, Float> entry : get(level).allScales().entrySet()) {
            out.add(new com.ovo.sablestopnow.network.StaffEnhanceNetworking.ScaleEntry(entry.getKey(), entry.getValue()));
        }
        return out;
    }
}
