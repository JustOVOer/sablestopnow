package com.ovo.sablestopnow.sketch;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 每个维度一份的**拓扑引用**存档 ✓（与 {@link SketchData} / SolidData 同一模式）。
 *
 * <p>存两类东西：
 * <ol>
 *   <li>{@code refs}：点 / 棱 / 面 引用本身（含 <b>contributors 多来源依赖</b>）</li>
 *   <li>{@code filletEdges}：圆角特征 → 它依赖的棱引用（依赖链：圆角 → 棱）</li>
 * </ol>
 *
 * <p>为什么必须落盘：引用是"特征之间的契约"。圆角必须能在重开存档后仍然知道
 * 自己依赖的是哪条棱、那条棱由哪些特征决定，否则移动/删除特征时无法判定失效。
 *
 * <p>⚠ 本类会被离线 harness 单独编译 ⇒ **不能**引用 mod 主类（LOGGER 等）✓
 */
public class TopoData extends SavedData {

    public static final String ID = "sablestopnow_topology";

    private final Map<UUID, TopoRef> refs = new LinkedHashMap<>();
    private final Map<UUID, List<UUID>> filletEdges = new LinkedHashMap<>();

    public TopoData() {
    }

    public static TopoData get(final ServerLevel level) {
        return level.getChunkSource().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(TopoData::new, TopoData::load, null), TopoData.ID);
    }

    public Map<UUID, TopoRef> refs() {
        return this.refs;
    }

    public Map<UUID, List<UUID>> filletEdges() {
        return this.filletEdges;
    }

    /** 登记/覆盖一个引用 ✓ */
    public void put(final TopoRef ref) {
        if (ref == null || ref.id() == null) {
            return;
        }
        this.refs.put(ref.id(), ref);
        this.setDirty(true);
    }

    public TopoRef byId(final UUID id) {
        return id == null ? null : this.refs.get(id);
    }

    /** 绑定"圆角 → 棱引用" ✓ */
    public void bindFillet(final UUID filletId, final List<UUID> edgeIds) {
        if (filletId == null || edgeIds == null || edgeIds.isEmpty()) {
            return;
        }
        final List<UUID> list = this.filletEdges.computeIfAbsent(filletId, k -> new ArrayList<>());
        for (final UUID e : edgeIds) {
            if (e != null && !list.contains(e)) {
                list.add(e);
            }
        }
        this.setDirty(true);
    }

    /** 删除某特征时，把所有 contributors 里含它的引用清掉（引用失效）✓ */
    public int purgeContributor(final UUID featureId) {
        if (featureId == null) {
            return 0;
        }
        int removed = 0;
        final List<UUID> dead = new ArrayList<>();
        for (final Map.Entry<UUID, TopoRef> en : this.refs.entrySet()) {
            if (en.getValue().contributors().contains(featureId)) {
                dead.add(en.getKey());
            }
        }
        for (final UUID id : dead) {
            this.refs.remove(id);
            removed++;
        }
        if (removed > 0) {
            this.setDirty(true);
        }
        return removed;
    }

    // ==================== 落盘 ====================

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        final ListTag refList = new ListTag();
        for (final TopoRef ref : this.refs.values()) {
            final CompoundTag t = new CompoundTag();
            t.putUUID("id", ref.id());
            t.putString("kind", ref.kind().name());
            if (ref.bodyId() != null) {
                t.putUUID("body", ref.bodyId());
            }
            // ⚠ 没有 putDoubleArray：用 ListTag of TAG_DOUBLE ✓（同 SketchData 的注释）
            final ListTag gs = new ListTag();
            for (final double v : ref.geom()) {
                gs.add(net.minecraft.nbt.DoubleTag.valueOf(v));
            }
            t.put("geom", gs);
            final ListTag cs = new ListTag();
            for (final UUID c : ref.contributors()) {
                cs.add(NbtUtils.createUUID(c));
            }
            t.put("contrib", cs);
            refList.add(t);
        }
        tag.put("refs", refList);

        final ListTag binds = new ListTag();
        for (final Map.Entry<UUID, List<UUID>> en : this.filletEdges.entrySet()) {
            final CompoundTag t = new CompoundTag();
            t.putUUID("fillet", en.getKey());
            final ListTag es = new ListTag();
            for (final UUID e : en.getValue()) {
                es.add(NbtUtils.createUUID(e));
            }
            t.put("edges", es);
            binds.add(t);
        }
        tag.put("filletEdges", binds);
        return tag;
    }

    public static TopoData load(final CompoundTag tag, final HolderLookup.Provider registries) {
        final TopoData data = new TopoData();
        final ListTag refList = tag.getList("refs", Tag.TAG_COMPOUND);
        for (int i = 0; i < refList.size(); i++) {
            final CompoundTag t = refList.getCompound(i);
            try {
                final UUID id = t.getUUID("id");
                final TopoRef.Kind kind = TopoRef.Kind.valueOf(t.getString("kind"));
                final UUID body = t.contains("body") ? t.getUUID("body") : null;
                final ListTag gs = t.getList("geom", Tag.TAG_DOUBLE);
                final double[] geom = new double[gs.size()];
                for (int k = 0; k < gs.size(); k++) {
                    geom[k] = ((net.minecraft.nbt.DoubleTag) gs.get(k)).getAsDouble();
                }
                final List<UUID> contributors = new ArrayList<>();
                final ListTag cs = t.getList("contrib", Tag.TAG_INT_ARRAY);
                for (int k = 0; k < cs.size(); k++) {
                    contributors.add(NbtUtils.loadUUID(cs.get(k)));
                }
                data.refs.put(id, new TopoRef(id, kind, body, contributors, geom));
            } catch (final RuntimeException ignored) {
                // 单条烂数据不影响整份存档 ✓
            }
        }
        final ListTag binds = tag.getList("filletEdges", Tag.TAG_COMPOUND);
        for (int i = 0; i < binds.size(); i++) {
            final CompoundTag t = binds.getCompound(i);
            try {
                final UUID fillet = t.getUUID("fillet");
                final List<UUID> edges = new ArrayList<>();
                final ListTag es = t.getList("edges", Tag.TAG_INT_ARRAY);
                for (int k = 0; k < es.size(); k++) {
                    edges.add(NbtUtils.loadUUID(es.get(k)));
                }
                data.filletEdges.put(fillet, edges);
            } catch (final RuntimeException ignored) {
                // 同上 ✓
            }
        }
        return data;
    }
}
