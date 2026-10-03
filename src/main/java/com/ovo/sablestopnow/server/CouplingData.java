package com.ovo.sablestopnow.server;

import com.ovo.sablestopnow.mate.MateRef;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 每个世界一份的「耦合」存档：比例组与齿轮。
 *
 * <p>为什么不塞进 {@link MateData}：{@link com.ovo.sablestopnow.mate.Mate} 是「两端 (a, b) + 数值」的二元结构，
 * 而比例组是「一个驱动面 + 若干从动面（各有自己的 k）」，齿轮是「四条轴 + 齿数比」——
 * 硬塞进二元结构只会让标准配合的代码到处长分支。它们本来就是不同的东西（见
 * {@code docs/advanced-mates-design.md}：标准配合是虚拟刚体夹具，耦合是每子步的运动学辅助），
 * 存档分开、运行期也分开，是更省事也更不容易出错的做法。
 *
 * <p>与配合一样：这里只存<b>描述</b>，运行期状态（测量到的驱动量、上一子步的角度…）不落盘。
 */
public class CouplingData extends SavedData {

    public static final String ID = "sablestopnow_couplings";

    /** 比例组的一个从动成员：参考 + 它的比例 k。 */
    public record RatioMember(MateRef ref, double ratio) {
    }

    /**
     * 比例组：{@code d_i = k_i · d_1}，其中 {@code d_1} 是驱动面 ↔ 第一个成员的测量距离。
     *
     * @param driver 基准（驱动器）参考 —— 玩家最后选择的那个面
     */
    public record RatioGroup(UUID id, MateRef driver, List<RatioMember> members,
                             UUID owner, @Nullable String ownerName) {

        public RatioGroup withMembers(final List<RatioMember> newMembers) {
            return new RatioGroup(this.id, this.driver, List.copyOf(newMembers), this.owner, this.ownerName);
        }

        public boolean touches(final UUID body) {
            if (this.driver.body().equals(body)) {
                return true;
            }
            for (final RatioMember member : this.members) {
                if (member.ref().body().equals(body)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 齿轮耦合：按顺序的四条轴 + 手填齿数比。
     *
     * <p>语义见 {@link com.ovo.sablestopnow.mate.GearRule}：齿轮轴是自转轴，固定轴是机身/世界参考轴，
     * {@code ratio = ± z_A / z_B}（{@code reversed} 取负）。
     */
    public record GearCoupling(UUID id, MateRef fixedA, MateRef gearA, MateRef fixedB, MateRef gearB,
                               int teethA, int teethB, boolean reversed,
                               UUID owner, @Nullable String ownerName) {

        public boolean touches(final UUID body) {
            return this.gearA.body().equals(body) || this.gearB.body().equals(body)
                    || this.fixedA.body().equals(body) || this.fixedB.body().equals(body);
        }

        /** 传动比（非法齿数返回 null，由调用方提示玩家，不产生 NaN）。 */
        @Nullable
        public Double ratio() {
            return com.ovo.sablestopnow.mate.GearRule.ratio(this.teethA, this.teethB, this.reversed);
        }
    }

    private final Map<UUID, RatioGroup> ratios = new LinkedHashMap<>();
    private final Map<UUID, GearCoupling> gears = new LinkedHashMap<>();

    public CouplingData() {
    }

    public static CouplingData get(final ServerLevel level) {
        return level.getChunkSource().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(CouplingData::new, CouplingData::load, null),
                CouplingData.ID);
    }

    // ============ 读写 ============

    public Map<UUID, RatioGroup> ratios() {
        return this.ratios;
    }

    public Map<UUID, GearCoupling> gears() {
        return this.gears;
    }

    public void putRatio(final RatioGroup group) {
        this.ratios.put(group.id(), group);
        this.setDirty(true);
    }

    public void putGear(final GearCoupling coupling) {
        this.gears.put(coupling.id(), coupling);
        this.setDirty(true);
    }

    public boolean removeRatio(final UUID id) {
        final boolean removed = this.ratios.remove(id) != null;
        if (removed) {
            this.setDirty(true);
        }
        return removed;
    }

    public boolean removeGear(final UUID id) {
        final boolean removed = this.gears.remove(id) != null;
        if (removed) {
            this.setDirty(true);
        }
        return removed;
    }

    /** 某个结构牵扯到的全部耦合（树状图挂载用）。 */
    public List<Object> ofBody(final UUID body) {
        final List<Object> out = new ArrayList<>();
        for (final RatioGroup group : this.ratios.values()) {
            if (group.touches(body)) {
                out.add(group);
            }
        }
        for (final GearCoupling coupling : this.gears.values()) {
            if (coupling.touches(body)) {
                out.add(coupling);
            }
        }
        return out;
    }

    // ============ 序列化 ============

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        final ListTag ratioList = new ListTag();
        for (final RatioGroup group : this.ratios.values()) {
            final CompoundTag entry = new CompoundTag();
            entry.put("id", NbtUtils.createUUID(group.id()));
            entry.put("driver", MateData.writeRef(group.driver()));
            entry.put("owner", NbtUtils.createUUID(group.owner()));
            if (group.ownerName() != null) {
                entry.putString("ownerName", group.ownerName());
            }
            final ListTag members = new ListTag();
            for (final RatioMember member : group.members()) {
                final CompoundTag memberTag = new CompoundTag();
                memberTag.put("ref", MateData.writeRef(member.ref()));
                memberTag.putDouble("k", member.ratio());
                members.add(memberTag);
            }
            entry.put("members", members);
            ratioList.add(entry);
        }
        tag.put("ratios", ratioList);

        final ListTag gearList = new ListTag();
        for (final GearCoupling coupling : this.gears.values()) {
            final CompoundTag entry = new CompoundTag();
            entry.put("id", NbtUtils.createUUID(coupling.id()));
            entry.put("fixedA", MateData.writeRef(coupling.fixedA()));
            entry.put("gearA", MateData.writeRef(coupling.gearA()));
            entry.put("fixedB", MateData.writeRef(coupling.fixedB()));
            entry.put("gearB", MateData.writeRef(coupling.gearB()));
            entry.putInt("teethA", coupling.teethA());
            entry.putInt("teethB", coupling.teethB());
            entry.putBoolean("reversed", coupling.reversed());
            entry.put("owner", NbtUtils.createUUID(coupling.owner()));
            if (coupling.ownerName() != null) {
                entry.putString("ownerName", coupling.ownerName());
            }
            gearList.add(entry);
        }
        tag.put("gears", gearList);
        return tag;
    }

    public static CouplingData load(final CompoundTag tag, final HolderLookup.Provider registries) {
        final CouplingData data = new CouplingData();

        final ListTag ratioList = tag.getList("ratios", Tag.TAG_COMPOUND);
        for (int i = 0; i < ratioList.size(); i++) {
            final CompoundTag entry = ratioList.getCompound(i);
            final MateRef driver = MateData.readRef(entry.getCompound("driver"));
            if (driver == null) {
                continue;
            }
            final List<RatioMember> members = new ArrayList<>();
            final ListTag memberList = entry.getList("members", Tag.TAG_COMPOUND);
            for (int j = 0; j < memberList.size(); j++) {
                final CompoundTag memberTag = memberList.getCompound(j);
                final MateRef ref = MateData.readRef(memberTag.getCompound("ref"));
                if (ref == null) {
                    continue;
                }
                members.add(new RatioMember(ref, memberTag.getDouble("k")));
            }
            if (members.isEmpty()) {
                continue;
            }
            data.ratios.put(NbtUtils.loadUUID(entry.get("id")),
                    new RatioGroup(NbtUtils.loadUUID(entry.get("id")), driver, members,
                            NbtUtils.loadUUID(entry.get("owner")),
                            entry.contains("ownerName") ? entry.getString("ownerName") : null));
        }

        final ListTag gearList = tag.getList("gears", Tag.TAG_COMPOUND);
        for (int i = 0; i < gearList.size(); i++) {
            final CompoundTag entry = gearList.getCompound(i);
            final MateRef fixedA = MateData.readRef(entry.getCompound("fixedA"));
            final MateRef gearA = MateData.readRef(entry.getCompound("gearA"));
            final MateRef fixedB = MateData.readRef(entry.getCompound("fixedB"));
            final MateRef gearB = MateData.readRef(entry.getCompound("gearB"));
            if (fixedA == null || gearA == null || fixedB == null || gearB == null) {
                continue;
            }
            data.gears.put(NbtUtils.loadUUID(entry.get("id")),
                    new GearCoupling(NbtUtils.loadUUID(entry.get("id")), fixedA, gearA, fixedB, gearB,
                            entry.getInt("teethA"), entry.getInt("teethB"), entry.getBoolean("reversed"),
                            NbtUtils.loadUUID(entry.get("owner")),
                            entry.contains("ownerName") ? entry.getString("ownerName") : null));
        }
        return data;
    }
}
