package com.ovo.sablestopnow.server;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.util.SableNBTUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 物理手杖增强 —— 物理结构快照（功能9，按玩家独立，运行期内存）。
 *
 * <p>K：把当前选中队列整体存一份快照；再按 K 取消。R：把快照整体回退。
 * 快照保存的是 Sable 自己的序列化结果（<b>方块 + 位姿 + 速度 + 名称</b>），
 * 回退 = 先按 REMOVED 移除当前结构（Sable 的移除不会把方块掉到地上），
 * 再用 {@link SubLevelSerializer#fullyLoad} 以同一个 UUID 重建，并把速度按快照原值写回，
 * 因此位置/朝向/方块/速度都与创建快照那一刻一致。
 */
public final class StaffSnapshotRegistry {

    /** 一份快照：所在维度 + 物理结构 UUID -> Sable 序列化数据。 */
    public record Snapshot(ResourceKey<Level> dimension, Map<UUID, SubLevelData> data) {
    }

    private static final Map<UUID, Snapshot> SNAPSHOTS = new HashMap<>();

    private StaffSnapshotRegistry() {
    }

    @Nullable
    public static synchronized Snapshot get(final UUID player) {
        return SNAPSHOTS.get(player);
    }

    public static synchronized List<UUID> idsOf(final UUID player) {
        final Snapshot snapshot = SNAPSHOTS.get(player);
        return snapshot == null ? List.of() : new ArrayList<>(snapshot.data().keySet());
    }

    /** 创建（或覆盖）该玩家的快照；返回成功记录的结构数。 */
    public static synchronized int create(final ServerLevel level, final UUID player, final Collection<UUID> ids) {
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return 0;
        }
        final Map<UUID, SubLevelData> data = new LinkedHashMap<>();
        for (final UUID id : ids) {
            final ServerSubLevel sub = (ServerSubLevel) container.getSubLevel(id);
            if (sub == null || sub.isRemoved()) {
                continue;
            }
            try {
                data.put(id, SubLevelSerializer.toData(sub, List.of()));
            } catch (final Exception e) {
                // 单个结构序列化失败不影响其余
            }
        }
        if (data.isEmpty()) {
            return 0;
        }
        SNAPSHOTS.put(player, new Snapshot(level.dimension(), data));
        return data.size();
    }

    /** 取消该玩家的快照；返回是否确实取消了。 */
    public static synchronized boolean clear(final UUID player) {
        return SNAPSHOTS.remove(player) != null;
    }

    public static synchronized void release(final UUID player) {
        SNAPSHOTS.remove(player);
    }

    public static synchronized void clearAll() {
        SNAPSHOTS.clear();
    }

    /** 回退结果：成功数 / 失败数。 */
    public record RestoreResult(int restored, int failed) {
    }

    /**
     * 把该玩家的快照整体回退。必须在快照所在的维度里调用（维度不符直接失败）。
     */
    public static RestoreResult restore(final ServerLevel level, final UUID player) {
        final Snapshot snapshot;
        synchronized (StaffSnapshotRegistry.class) {
            snapshot = SNAPSHOTS.get(player);
        }
        if (snapshot == null || !snapshot.dimension().equals(level.dimension())) {
            return new RestoreResult(0, 0);
        }
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return new RestoreResult(0, 0);
        }

        int restored = 0;
        int failed = 0;
        for (final Map.Entry<UUID, SubLevelData> entry : snapshot.data().entrySet()) {
            if (restoreOne(level, container, entry.getKey(), entry.getValue())) {
                restored++;
            } else {
                failed++;
            }
        }
        return new RestoreResult(restored, failed);
    }

    private static boolean restoreOne(final ServerLevel level, final ServerSubLevelContainer container,
                                      final UUID id, final SubLevelData data) {
        try {
            // 1) 移除当前结构（同一 UUID），腾出 plot 并清掉现在的方块/位姿
            final ServerSubLevel existing = (ServerSubLevel) container.getSubLevel(id);
            if (existing != null && !existing.isRemoved()) {
                container.removeSubLevel(existing, SubLevelRemovalReason.REMOVED);
            }
            // 2) 用快照数据以同一 UUID 重建
            final ServerSubLevel restored = SubLevelSerializer.fullyLoad(level, data);
            if (restored == null) {
                return false;
            }
            // 3) fullyLoad 会按 VELOCITY_RETAINED_ON_LOAD 衰减速度，这里按快照原值精确写回
            applySnapshotVelocity(level, restored, data);
            return true;
        } catch (final Exception e) {
            return false;
        }
    }

    private static void applySnapshotVelocity(final ServerLevel level, final ServerSubLevel sub, final SubLevelData data) {
        final var tag = data.fullTag();
        final var pipeline = SubLevelContainer.getContainer(level).physicsSystem().getPipeline();
        final RigidBodyHandle handle = RigidBodyHandle.of(sub);
        if (handle == null) {
            return;
        }
        pipeline.resetVelocity(sub);
        if (tag.contains("linear_velocity") || tag.contains("angular_velocity")) {
            final org.joml.Vector3d linear = tag.contains("linear_velocity")
                    ? SableNBTUtils.readVector3d(tag.getCompound("linear_velocity")) : new org.joml.Vector3d();
            final org.joml.Vector3d angular = tag.contains("angular_velocity")
                    ? SableNBTUtils.readVector3d(tag.getCompound("angular_velocity")) : new org.joml.Vector3d();
            pipeline.addLinearAndAngularVelocity(sub, linear, angular);
        }
    }
}
