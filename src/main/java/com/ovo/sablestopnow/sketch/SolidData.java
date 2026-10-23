package com.ovo.sablestopnow.sketch;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 每个维度一份的「拉伸实体」存档。
 *
 * <p>与 {@link SketchData} 同一模式：按维度 {@code DataStorage} 存、坏记录逐条跳过、
 * 序列化方法 {@code public} 供网络同步复用（同一份数据只留一个序列化器，
 * 免得漂移成"存档读得回、联机读不回"）。
 *
 * <h2>存的是什么</h2>
 * 只有<b>身份 + 引用 + 参数</b>：{@code id / body / sketchId / 序号 / 拉伸方式与深度}。
 * <b>盒子不落盘</b> —— 它是「草图轮廓 × 深度」的派生结果，存下来必然和草图漂移
 * （玩家改了草图，实体还是旧盒子，渲染和碰撞还会各说各话）。见 {@link Solid}。
 */
public class SolidData extends SavedData {

    public static final String ID = "sablestopnow_solids";

    private final Map<UUID, Solid> solids = new LinkedHashMap<>();

    public SolidData() {
    }

    public static SolidData get(final ServerLevel level) {
        return level.getChunkSource().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SolidData::new, SolidData::load, null),
                SolidData.ID);
    }

    public Map<UUID, Solid> all() {
        return this.solids;
    }

    public void put(final Solid solid) {
        this.solids.put(solid.id(), solid);
        this.setDirty(true);
    }

    public boolean remove(final UUID id) {
        final boolean removed = this.solids.remove(id) != null;
        if (removed) {
            this.setDirty(true);
        }
        return removed;
    }

    /** 某个结构上的全部实体（设计树按结构分组）。 */
    public List<Solid> ofBody(final UUID body) {
        final List<Solid> out = new ArrayList<>();
        for (final Solid solid : this.solids.values()) {
            if (solid.body().equals(body)) {
                out.add(solid);
            }
        }
        return out;
    }

    /** 同一结构上下一个可用序号（{@code max+1}，不是数量 +1 —— 删了中间的不能撞名）。 */
    public int nextIndex(final UUID body) {
        return Solid.nextIndex(this.solids.values(), body);
    }

    /**
     * 对账：**哪些 body 上的实体该清掉了**（纯逻辑，可离线验证）。
     *
     * <h2>为什么要把它抽成函数</h2>
     * {@link #dropOfBody} 的注释里写着警告 ——「一次查不到就删」会在退出世界时
     * 把玩家的东西删光。但那是**写给调用方看的**，而调用方是每个新加的清理路径。
     * 把守卫固化在这里，就没有"某条路径忘了记"的可能。
     *
     * <h2>⚠ 关键守卫：{@code alive} 为空时返回空</h2>
     * 容器还没加载好（刚进世界 / 维度刚加载）时，我们看到的"活着的子结构"是**空的**。
     * 那时若照常对账，就会把**存档里所有实体全删掉** —— 而且是**永久**的。
     * 所以"一个都没看到"一律当成"还没准备好"，而不是"全都没了"。
     * 这个方向的不确定性是不对称的：漏清一个只是留个幽灵，
     * 错清一次是**玩家数据没了**。
     *
     * @param referenced 存档里出现过实体的 body id
     * @param alive      当前真的存在的 body id
     * @return 该清掉的 body id（可能为空；调用方再拿去 {@link #dropOfBody}）
     */
    public static Set<UUID> staleBodies(final Collection<UUID> referenced,
                                        final Collection<UUID> alive) {
        if (referenced == null || referenced.isEmpty()) {
            return Set.of();
        }
        if (alive == null || alive.isEmpty()) {
            // 还没准备好，什么都别动。见上面的"不对称"说明。
            return Set.of();
        }
        final Set<UUID> out = new LinkedHashSet<>();
        for (final UUID body : referenced) {
            if (body != null && !alive.contains(body)) {
                out.add(body);
            }
        }
        return out;
    }

    /**
     * 结构消失时清掉它的实体。返回清掉的数量。
     *
     * <p>⚠ 调用方必须确认结构<b>真的</b>没了 —— Sable 的 {@code getSubLevel} 返回 null
     * 也可能只是区块没加载，「一次查不到就删」会在退出世界时把玩家的东西删光。
     * <b>用 {@link #staleBodies} 来算"该清哪些"，不要自己写这个判断。</b>
     */
    public int dropOfBody(final UUID body) {
        final int before = this.solids.size();
        this.solids.values().removeIf(solid -> solid.body().equals(body));
        final int removed = before - this.solids.size();
        if (removed > 0) {
            this.setDirty(true);
        }
        return removed;
    }

    /**
     * 草图被删时连带清掉引用它的实体。返回清掉的数量。
     *
     * <p>刻意做成"连带删除"而不是"留着当孤儿"：实体几何完全来自草图，
     * 草图没了它就是一块永远画不出来、也碰不到的东西，留在设计树里只会让人困惑。
     */
    public int dropOfSketch(final UUID sketchId) {
        final int before = this.solids.size();
        this.solids.values().removeIf(solid -> solid.sketchId().equals(sketchId));
        final int removed = before - this.solids.size();
        if (removed > 0) {
            this.setDirty(true);
        }
        return removed;
    }

    // ============ 序列化 ============

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        final ListTag list = new ListTag();
        for (final Solid solid : this.solids.values()) {
            list.add(writeSolid(solid));
        }
        tag.put("solids", list);
        return tag;
    }

    /**
     * 把一个实体写成 NBT。
     *
     * <p>⚠ <b>public</b> 是刻意的：网络同步用同一个方法，不另写一份（见类注释）。
     */
    public static CompoundTag writeSolid(final Solid solid) {
        final CompoundTag entry = new CompoundTag();
        entry.put("id", NbtUtils.createUUID(solid.id()));
        entry.put("body", NbtUtils.createUUID(solid.body()));
        entry.put("sketch", NbtUtils.createUUID(solid.sketchId()));
        entry.putInt("index", solid.index());
        // 成形方式存**名字**不存序号：将来在枚举中间插一个值，序号会整体错位，
        // 存档里所有实体的成形方式就悄悄变了。名字错位只会读失败（会被跳过），不会读成错的。
        //
        // ⚠ 兼容旧存档：老记录没有 "shape" 字段，只有拉伸那套（"mode"/"d1"/"d2"）。
        // 读的时候缺字段 → 当成拉伸，这样已有存档不会因为这次改动读不出来。
        switch (solid.shape()) {
            case Solid.Shape.Extrusion e -> {
                entry.putString("shape", "extrusion");
                entry.putString("mode", e.params().mode().name());
                entry.putDouble("d1", e.params().depth1());
                entry.putDouble("d2", e.params().depth2());
            }
            case Solid.Shape.Revolution r -> {
                entry.putString("shape", "revolution");
                // 旋转轴：平面内的点 + 方向 + 角度 + 旋向
                entry.putDouble("au", r.params().axisU());
                entry.putDouble("av", r.params().axisV());
                entry.putDouble("du", r.params().dirU());
                entry.putDouble("dv", r.params().dirV());
                entry.putDouble("angle", r.params().angleDeg());
                entry.putInt("direction", r.params().direction());
            }
            case Solid.Shape.Cut c -> {
                // ★ 需求 ⑥：切割体 ✓（参数与拉伸相同：mode/d1/d2 ✓）
                entry.putString("shape", "cut");
                entry.putString("mode", c.params().mode().name());
                entry.putDouble("d1", c.params().depth1());
                entry.putDouble("d2", c.params().depth2());
            }
        }
        // 外观（贴图）：用 SolidAppearance 自己的编码，本层不解释任何规则
        final String[] look = solid.appearance().encode();
        entry.putString("lookBlock", look[0]);
        entry.putString("lookScale", look[1]);
        return entry;
    }

    public static SolidData load(final CompoundTag tag, final HolderLookup.Provider registries) {
        final SolidData data = new SolidData();
        final ListTag list = tag.getList("solids", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            final Solid solid = readSolid(list.getCompound(i));
            if (solid != null) {
                data.solids.put(solid.id(), solid);
            }
        }
        return data;
    }

    /**
     * 读一条记录。任何异常都返回 null（跳过这条），**不要**让它把整份存档带崩。
     *
     * <p>同样是 <b>public</b>：网络同步走这一个方法，见 {@link #writeSolid}。
     */
    public static Solid readSolid(final CompoundTag entry) {
        try {
            final UUID id = NbtUtils.loadUUID(entry.get("id"));
            final UUID body = NbtUtils.loadUUID(entry.get("body"));
            final UUID sketch = NbtUtils.loadUUID(entry.get("sketch"));
            if (id == null || body == null || sketch == null) {
                return null;
            }
            // ⚠ 缺 "shape" = 旧存档（只有拉伸）→ 当拉伸读。
            // 不做这个兜底的话，升级之后玩家所有旧实体都会"读不出来"（被跳过），
            // 表现就是"我的模型全没了"。
            final String shape = entry.contains("shape") ? entry.getString("shape") : "extrusion";
            final Solid.Shape parsed = switch (shape) {
                case "revolution" -> new Solid.Shape.Revolution(new Revolve.Params(
                        entry.getDouble("au"), entry.getDouble("av"),
                        entry.getDouble("du"), entry.getDouble("dv"),
                        entry.getDouble("angle"), entry.getInt("direction")));
                // ★ 需求 ⑥：切割体 ✓
                case "cut" -> new Solid.Shape.Cut(new Extrude.Params(
                        Extrude.Mode.valueOf(entry.getString("mode")),
                        entry.getDouble("d1"), entry.getDouble("d2")));
                default -> new Solid.Shape.Extrusion(new Extrude.Params(
                        Extrude.Mode.valueOf(entry.getString("mode")),
                        entry.getDouble("d1"), entry.getDouble("d2")));
            };
            // ⚠ 缺 "look*" = 旧存档（那时还没有贴图）→ 用默认外观。
            // 和 "shape" 一样的兜底原则：不兜底的话升级后旧模型会整条读不出来。
            final com.ovo.sablestopnow.sketch.SolidAppearance look =
                    com.ovo.sablestopnow.sketch.SolidAppearance.decode(
                            entry.contains("lookBlock") ? entry.getString("lookBlock") : null,
                            entry.contains("lookScale") ? entry.getString("lookScale") : null);
            return new Solid(id, body, sketch, entry.getInt("index"), parsed, look);
        } catch (final Exception e) {
            // 深度非法（<=0）、模式名字不认识、轴方向为零、字段缺失 —— 一律跳过这一条
            return null;
        }
    }
}
