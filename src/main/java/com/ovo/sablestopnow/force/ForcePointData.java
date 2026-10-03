package com.ovo.sablestopnow.force;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 每个世界一份的「可控施力点」存档。
 *
 * <p>与 {@link com.ovo.sablestopnow.server.MateData} 同一模式（按维度 `DataStorage` 存描述，
 * 运行期状态不落盘）。坏数据逐条跳过，不让一条烂记录把整份存档读不出来。
 */
public class ForcePointData extends SavedData {

    public static final String ID = "sablestopnow_force_points";

    private final Map<UUID, ForcePoint> points = new LinkedHashMap<>();

    public ForcePointData() {
    }

    public static ForcePointData get(final ServerLevel level) {
        return level.getChunkSource().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ForcePointData::new, ForcePointData::load, null),
                ForcePointData.ID);
    }

    public Map<UUID, ForcePoint> all() {
        return this.points;
    }

    public void put(final ForcePoint point) {
        this.points.put(point.id(), point);
        this.setDirty(true);
    }

    public boolean remove(final UUID id) {
        final boolean removed = this.points.remove(id) != null;
        if (removed) {
            this.setDirty(true);
        }
        return removed;
    }

    /** 某个结构上的全部施力点（树状图分支与子步施力都用它）。 */
    public List<ForcePoint> ofBody(final UUID body) {
        final List<ForcePoint> out = new ArrayList<>();
        for (final ForcePoint point : this.points.values()) {
            if (point.body().equals(body)) {
                out.add(point);
            }
        }
        return out;
    }

    /** 结构消失时清掉它的施力点。返回清掉的数量。 */
    public int dropOfBody(final UUID body) {
        final boolean removed = this.points.values().removeIf(point -> point.body().equals(body));
        if (removed) {
            this.setDirty(true);
        }
        return removed ? 1 : 0;
    }

    // ============ 序列化 ============

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        final ListTag list = new ListTag();
        for (final ForcePoint point : this.points.values()) {
            final CompoundTag entry = new CompoundTag();
            entry.put("id", NbtUtils.createUUID(point.id()));
            entry.put("body", NbtUtils.createUUID(point.body()));
            entry.putDouble("px", point.localPoint().x);
            entry.putDouble("py", point.localPoint().y);
            entry.putDouble("pz", point.localPoint().z);
            entry.putDouble("nx", point.localNormal().x);
            entry.putDouble("ny", point.localNormal().y);
            entry.putDouble("nz", point.localNormal().z);
            entry.putDouble("magnitude", point.magnitude());
            if (point.channelItemId() != null) {
                entry.putString("channelItem", point.channelItemId());
            }
            if (point.channelItemId2() != null) {
                entry.putString("channelItem2", point.channelItemId2());
            }
            if (point.channelName() != null) {
                entry.putString("channelName", point.channelName());
            }
            entry.putBoolean("enabled", point.enabled());
            list.add(entry);
        }
        tag.put("points", list);
        return tag;
    }

    public static ForcePointData load(final CompoundTag tag, final HolderLookup.Provider registries) {
        final ForcePointData data = new ForcePointData();
        final ListTag list = tag.getList("points", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            final ForcePoint point = readPoint(list.getCompound(i));
            if (point != null) {
                data.points.put(point.id(), point);
            }
        }
        return data;
    }

    @Nullable
    private static ForcePoint readPoint(final CompoundTag entry) {
        try {
            final Vector3d normal = new Vector3d(entry.getDouble("nx"), entry.getDouble("ny"),
                    entry.getDouble("nz"));
            if (normal.lengthSquared() < 1.0e-9) {
                // 法线为零的施力点没有意义（力没有方向），直接丢弃而不是留一个永远不生效的点
                return null;
            }
            return new ForcePoint(
                    NbtUtils.loadUUID(entry.get("id")),
                    NbtUtils.loadUUID(entry.get("body")),
                    new Vector3d(entry.getDouble("px"), entry.getDouble("py"), entry.getDouble("pz")),
                    normal.normalize(),
                    ForcePoint.clampMagnitude(entry.getDouble("magnitude")),
                    entry.contains("channelItem") ? entry.getString("channelItem") : null,
                    entry.contains("channelItem2") ? entry.getString("channelItem2") : null,
                    entry.contains("channelName") ? entry.getString("channelName") : null,
                    !entry.contains("enabled") || entry.getBoolean("enabled"));
        } catch (final Exception e) {
            return null;
        }
    }
}
