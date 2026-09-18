package com.ovo.sablestopnow.server;

import com.ovo.sablestopnow.mate.Mate;
import com.ovo.sablestopnow.mate.MateRef;
import com.ovo.sablestopnow.mate.MateType;
import com.ovo.sablestopnow.mate.RefKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 每个世界一份的「配合」存档。
 *
 * <p>与 {@link StaffOwnershipData} 同一模式：按维度存 {@code level.getChunkSource().getDataStorage()}。
 * 存的只是<b>描述</b>（类型 + 两端参考），关节本身每次进入世界时由 {@code MateRegistry} 重建 ——
 * rapier 的关节句柄是运行期对象，不能序列化。
 */
public class MateData extends SavedData {

    public static final String ID = "sablestopnow_mates";

    /** 配合 id -> 配合。 */
    private final Map<UUID, Mate> mates = new LinkedHashMap<>();

    public MateData() {
    }

    public static MateData get(final ServerLevel level) {
        return level.getChunkSource().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(MateData::new, MateData::load, null),
                MateData.ID);
    }

    public Map<UUID, Mate> all() {
        return this.mates;
    }

    public void put(final Mate mate) {
        this.mates.put(mate.id(), mate);
        this.setDirty(true);
    }

    public void remove(final UUID mateId) {
        if (this.mates.remove(mateId) != null) {
            this.setDirty(true);
        }
    }

    public void clear() {
        if (!this.mates.isEmpty()) {
            this.mates.clear();
            this.setDirty(true);
        }
    }

    /** 丢弃所有牵扯到某个物理结构的配合（结构被移除时调用）。 */
    public int removeAllMentioning(final UUID body) {
        final int before = this.mates.size();
        this.mates.entrySet().removeIf(e -> e.getValue().touches(body));
        final int removed = before - this.mates.size();
        if (removed > 0) {
            this.setDirty(true);
        }
        return removed;
    }

    // ============ NBT ============

    private static MateData load(final CompoundTag tag, final HolderLookup.Provider provider) {
        final MateData data = new MateData();
        final ListTag list = tag.getList(ID, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            final Mate mate = readMate(list.getCompound(i));
            if (mate != null) {
                data.mates.put(mate.id(), mate);
            }
        }
        return data;
    }

    @Override
    public @NotNull CompoundTag save(final CompoundTag tag, final HolderLookup.@NotNull Provider provider) {
        final ListTag list = new ListTag();
        for (final Mate mate : this.mates.values()) {
            list.add(writeMate(mate));
        }
        tag.put(ID, list);
        return tag;
    }

    private static CompoundTag writeMate(final Mate mate) {
        final CompoundTag out = new CompoundTag();
        out.put("id", NbtUtils.createUUID(mate.id()));
        out.putString("type", mate.type().id());
        out.putDouble("value", mate.value());
        out.putBoolean("flip", mate.flip());
        if (mate.name() != null) {
            out.putString("name", mate.name());
        }
        out.put("owner", NbtUtils.createUUID(mate.owner()));
        if (mate.ownerName() != null) {
            out.putString("ownerName", mate.ownerName());
        }
        out.put("a", writeRef(mate.a()));
        out.put("b", writeRef(mate.b()));
        return out;
    }

    private static CompoundTag writeRef(final MateRef ref) {
        final CompoundTag out = new CompoundTag();
        out.put("body", NbtUtils.createUUID(ref.body()));
        out.putString("kind", ref.kind().name());
        if (ref.block() != null) {
            out.putInt("bx", ref.block().getX());
            out.putInt("by", ref.block().getY());
            out.putInt("bz", ref.block().getZ());
        }
        if (ref.face() != null) {
            out.putInt("face", ref.face().get3DDataValue());
        }
        out.putInt("feature", ref.feature());
        out.putInt("axis", ref.axis());
        return out;
    }

    @Nullable
    private static Mate readMate(final CompoundTag tag) {
        try {
            final MateType type = MateType.byId(tag.getString("type"));
            if (type == null) {
                return null;
            }
            final MateRef a = readRef(tag.getCompound("a"));
            final MateRef b = readRef(tag.getCompound("b"));
            if (a == null || b == null || !a.isWellFormed() || !b.isWellFormed()) {
                return null;
            }
            return new Mate(
                    NbtUtils.loadUUID(tag.get("id")),
                    type, a, b,
                    tag.getDouble("value"),
                    tag.getBoolean("flip"),
                    tag.contains("name") ? tag.getString("name") : null,
                    NbtUtils.loadUUID(tag.get("owner")),
                    tag.contains("ownerName") ? tag.getString("ownerName") : null);
        } catch (final Exception e) {
            // 单条损坏不应让整个存档读不出来
            return null;
        }
    }

    @Nullable
    private static MateRef readRef(final CompoundTag tag) {
        try {
            final RefKind kind = RefKind.valueOf(tag.getString("kind"));
            final UUID body = NbtUtils.loadUUID(tag.get("body"));
            final int feature = tag.getInt("feature");
            final int axis = tag.getInt("axis");
            if (!kind.isBlockLevel()) {
                return new MateRef(body, kind, null, null, -1, axis);
            }
            final BlockPos block = new BlockPos(tag.getInt("bx"), tag.getInt("by"), tag.getInt("bz"));
            final Direction face = Direction.from3DDataValue(tag.getInt("face"));
            return new MateRef(body, kind, block, face, feature, -1);
        } catch (final Exception e) {
            return null;
        }
    }

    /** 供命令/GUI 列出的快照（避免外部直接改内部 map）。 */
    public List<Mate> snapshot() {
        return new ArrayList<>(this.mates.values());
    }
}
