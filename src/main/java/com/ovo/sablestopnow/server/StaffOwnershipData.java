package com.ovo.sablestopnow.server;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 每个世界一份的「物理结构所有权」存档（功能3）。
 *
 * <p>规则：按 O 把当前选中队列设为/取消自己的所有权；已属于他人的结构不能被别人选中（多选/区域选择都会被拒），
 * 也不能被别人改所有权。所有权落盘持久化；OP 可以用 {@code /sablesn owner ...} 强制清除/转移。
 */
public class StaffOwnershipData extends SavedData {
    public static final String ID = "sablestopnow_ownership";

    /** 物理结构 -> 所有者。 */
    private final Map<UUID, UUID> owners = new HashMap<>();
    /** 所有者 UUID -> 最后一次写入时的玩家名（离线也能显示）。 */
    private final Map<UUID, String> ownerNames = new HashMap<>();

    public StaffOwnershipData() {
    }

    public static StaffOwnershipData get(final ServerLevel level) {
        return level.getChunkSource().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(StaffOwnershipData::new, StaffOwnershipData::load, null),
                StaffOwnershipData.ID);
    }

    private static StaffOwnershipData load(final CompoundTag tag, final HolderLookup.Provider provider) {
        final StaffOwnershipData data = new StaffOwnershipData();
        final ListTag list = tag.getList(ID, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            final CompoundTag entry = list.getCompound(i);
            final UUID subLevel = NbtUtils.loadUUID(entry.get("sub"));
            final UUID owner = NbtUtils.loadUUID(entry.get("owner"));
            data.owners.put(subLevel, owner);
            if (entry.contains("name")) {
                data.ownerNames.put(owner, entry.getString("name"));
            }
        }
        return data;
    }

    @Nullable
    public UUID ownerOf(final UUID subLevel) {
        return this.owners.get(subLevel);
    }

    public boolean isOwnedByOther(final UUID subLevel, final UUID player) {
        final UUID owner = this.owners.get(subLevel);
        return owner != null && !owner.equals(player);
    }

    @Nullable
    public String ownerNameOf(final UUID subLevel) {
        final UUID owner = this.owners.get(subLevel);
        if (owner == null) {
            return null;
        }
        return this.ownerNames.getOrDefault(owner, owner.toString());
    }

    /** 设置/清除所有权（只允许在“无主”或“本人所有”时设置；OP 命令走 force 路径）。 */
    public void setOwner(final UUID subLevel, @Nullable final UUID owner) {
        if (owner == null) {
            this.owners.remove(subLevel);
        } else {
            this.owners.put(subLevel, owner);
        }
        this.setDirty(true);
    }

    public void setOwner(final ServerPlayer owner, final UUID subLevel) {
        this.owners.put(subLevel, owner.getUUID());
        this.ownerNames.put(owner.getUUID(), owner.getGameProfile().getName());
        this.setDirty(true);
    }

    /** 清除某玩家的全部所有权；返回被清除的数量。 */
    public int clearOwner(final UUID owner) {
        final int before = this.owners.size();
        this.owners.entrySet().removeIf(e -> e.getValue().equals(owner));
        this.setDirty(true);
        return before - this.owners.size();
    }

    public Map<UUID, UUID> allOwners() {
        return this.owners;
    }

    @Override
    public @NotNull CompoundTag save(final CompoundTag tag, final HolderLookup.@NotNull Provider provider) {
        final ListTag list = new ListTag();
        for (final Map.Entry<UUID, UUID> entry : this.owners.entrySet()) {
            final CompoundTag entryTag = new CompoundTag();
            entryTag.put("sub", NbtUtils.createUUID(entry.getKey()));
            entryTag.put("owner", NbtUtils.createUUID(entry.getValue()));
            final String name = this.ownerNames.get(entry.getValue());
            if (name != null) {
                entryTag.putString("name", name);
            }
            list.add(entryTag);
        }
        tag.put(ID, list);
        return tag;
    }
}
