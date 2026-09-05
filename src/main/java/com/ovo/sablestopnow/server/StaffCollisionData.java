package com.ovo.sablestopnow.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 每个世界一份的“无碰撞标记”存档状态（占位功能：仅记录状态供视觉图标/提示使用。
 * Sable 暂不支持运行时把物理体真正幽灵化，见 docs/staff-enhance-design.md）。
 * 存储与读取方式照抄 Simulated/PhysicsStaffServerHandler(SavedData)。
 */
public class StaffCollisionData extends SavedData {
    public static final String ID = "sablestopnow_no_collision";

    private final Set<UUID> marked = new LinkedHashSet<>();

    public StaffCollisionData() {
    }

    public static StaffCollisionData get(final ServerLevel level) {
        return level.getChunkSource().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(StaffCollisionData::new, StaffCollisionData::load, null),
                StaffCollisionData.ID);
    }

    private static StaffCollisionData load(final CompoundTag tag, final HolderLookup.Provider provider) {
        final StaffCollisionData data = new StaffCollisionData();
        final ListTag list = tag.getList(ID, Tag.TAG_INT_ARRAY);
        for (int i = 0; i < list.size(); i++) {
            data.marked.add(NbtUtils.loadUUID(list.get(i)));
        }
        return data;
    }

    /** @return true = 切换后处于“无碰撞”状态 */
    public boolean toggle(final UUID subLevel) {
        final boolean on = !this.marked.remove(subLevel); // remove 成功=原在集合 → 现在关闭
        if (on) {
            this.marked.add(subLevel);
        }
        this.setDirty(true);
        return on;
    }

    public Set<UUID> getMarked() {
        return this.marked;
    }

    @Override
    public @NotNull CompoundTag save(final CompoundTag tag, final HolderLookup.@NotNull Provider provider) {
        final ListTag list = new ListTag();
        this.marked.forEach(uuid -> list.add(NbtUtils.createUUID(uuid)));
        tag.put(ID, list);
        return tag;
    }
}
