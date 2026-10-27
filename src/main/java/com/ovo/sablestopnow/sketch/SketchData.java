package com.ovo.sablestopnow.sketch;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 每个维度一份的「草图」存档。
 *
 * <p>与 {@link com.ovo.sablestopnow.force.ForcePointData} 同一模式：按维度
 * {@code DataStorage} 存，运行期的求解状态不落盘（变量数组就是解）。
 * 坏记录逐条跳过，不让一条烂数据把整份存档读不出来。
 *
 * <h2>存的是什么</h2>
 * <ul>
 *   <li><b>平面</b>：原点 + 两个轴，全在<b>子结构 plot 局部系</b>里 —— 所以结构动了草图跟着动。</li>
 *   <li><b>变量</b>：一串 {@code double}，点的坐标和圆的半径。求解后的解就在这里面。</li>
 *   <li><b>图元</b>：种类 + 变量下标（不存坐标，见 {@link Sketch}）。</li>
 *   <li><b>约束</b>：种类 + 操作数下标 + 常量。</li>
 * </ul>
 *
 * <h2>为什么图元只存下标也要落盘</h2>
 * 下标是「这张草图内部」的编码，跟变量数组一起才有意义。既然变量要存，
 * 下标就必须一起存 —— 否则重开存档时无法知道哪几个变量构成一条线。
 */
public class SketchData extends SavedData {

    public static final String ID = "sablestopnow_sketches";

    private final Map<UUID, Sketch> sketches = new LinkedHashMap<>();

    public SketchData() {
    }

    public static SketchData get(final ServerLevel level) {
        return level.getChunkSource().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(SketchData::new, SketchData::load, null),
                SketchData.ID);
    }

    public Map<UUID, Sketch> all() {
        return this.sketches;
    }

    public void put(final Sketch sketch) {
        this.sketches.put(sketch.id(), sketch);
        this.setDirty(true);
    }

    public boolean remove(final UUID id) {
        final boolean removed = this.sketches.remove(id) != null;
        if (removed) {
            this.setDirty(true);
        }
        return removed;
    }

    /** 某个结构上的全部草图（设计树按结构分组）。 */
    public List<Sketch> ofBody(final UUID body) {
        final List<Sketch> out = new ArrayList<>();
        for (final Sketch sketch : this.sketches.values()) {
            if (sketch.body().equals(body)) {
                out.add(sketch);
            }
        }
        return out;
    }

    /**
     * 结构消失时清掉它的草图。返回清掉的数量。
     *
     * <p>⚠ 调用方必须确认结构<b>真的</b>没了 —— 参考施力点那边的教训：
     * Sable 的 {@code getSubLevel} 返回 null 也可能只是区块没加载，
     * 「一次查不到就删」会在退出世界时把玩家的东西删光。
     */
    public int dropOfBody(final UUID body) {
        final int before = this.sketches.size();
        this.sketches.values().removeIf(sketch -> sketch.body().equals(body));
        final int removed = before - this.sketches.size();
        if (removed > 0) {
            this.setDirty(true);
        }
        return removed;
    }

    // ============ 序列化 ============

    @Override
    public CompoundTag save(final CompoundTag tag, final HolderLookup.Provider registries) {
        final ListTag list = new ListTag();
        for (final Sketch sketch : this.sketches.values()) {
            list.add(writeSketch(sketch));
        }
        tag.put("sketches", list);
        return tag;
    }

    /**
     * 把一张草图写成 NBT。
     *
     * <p>⚠ <b>public</b> 是刻意的：网络同步也用这一个方法，不另写一份。
     * 同一份数据有两个序列化器，迟早会漂移成「存档读得回、联机读不回」（或反过来）。
     */
    public static CompoundTag writeSketch(final Sketch sketch) {
        final CompoundTag entry = new CompoundTag();
        entry.put("id", NbtUtils.createUUID(sketch.id()));
        entry.put("body", NbtUtils.createUUID(sketch.body()));
        entry.putString("name", sketch.name());

        final SketchPlane plane = sketch.plane();
        final CompoundTag planeTag = new CompoundTag();
        planeTag.putDouble("ox", plane.origin().x);
        planeTag.putDouble("oy", plane.origin().y);
        planeTag.putDouble("oz", plane.origin().z);
        planeTag.putDouble("ux", plane.axisU().x);
        planeTag.putDouble("uy", plane.axisU().y);
        planeTag.putDouble("uz", plane.axisU().z);
        planeTag.putDouble("vx", plane.axisV().x);
        planeTag.putDouble("vy", plane.axisV().y);
        planeTag.putDouble("vz", plane.axisV().z);
        entry.put("plane", planeTag);

        // ⚠ 原版 NBT 没有 double 数组（只有 byte/int/long 数组），所以变量只能装进
        // ListTag of TAG_DOUBLE。别去找 putDoubleArray —— 它不存在。
        entry.put("vars", writeDoubles(sketch.variables()));

        final ListTag entities = new ListTag();
        for (final Sketch.Entity e : sketch.entities()) {
            final CompoundTag et = new CompoundTag();
            et.putString("kind", e.kind().name());
            et.putIntArray("vars", e.vars());
            // 需求 ④：圆弧方向位 ✓（只写 true ⇒ 旧存档没有这个键时按 false 读 ✓ 向后兼容 ✓）
            if (e.cw()) {
                et.putBoolean("cw", true);
            }
            entities.add(et);
        }
        entry.put("entities", entities);

        // ★ 辅助线标记 ✓（需求 ④ 顺带修：**以前根本没存** ✗ ⇒ 存档重进后虚线变实线 ✓）
        //   用"kind + 全部变量下标"的字符串键 ✓（共享变量的圆弧辅助线才不会撞车 ✓）
        final ListTag construction = new ListTag();
        for (final String key : sketch.constructionKeys()) {
            construction.add(net.minecraft.nbt.StringTag.valueOf(key));
        }
        if (!construction.isEmpty()) {
            entry.put("construction", construction);
        }
        // ★ 参考几何（从模型面带进来的线 ✓ 不可移动、可被约束）✓
        final ListTag references = new ListTag();
        for (final String key : sketch.referenceKeys()) {
            references.add(net.minecraft.nbt.StringTag.valueOf(key));
        }
        if (!references.isEmpty()) {
            entry.put("reference", references);
        }

        // ★ 文字图元（item 3）✓
        final ListTag texts = new ListTag();
        for (final Sketch.TextNote note : sketch.texts()) {
            final CompoundTag nt = new CompoundTag();
            nt.putDouble("u", note.u());
            nt.putDouble("v", note.v());
            nt.putDouble("w", note.w());
            nt.putDouble("h", note.h());
            nt.putString("text", note.text());
            nt.putInt("argb", note.argb());
            nt.putDouble("scale", note.scale());
            nt.putDouble("rot", note.rotationDeg());
            texts.add(nt);
        }
        if (!texts.isEmpty()) {
            entry.put("texts", texts);
        }

        final ListTag constraints = new ListTag();
        for (final Sketch.Constraint c : sketch.constraints()) {
            final CompoundTag ct = new CompoundTag();
            ct.putInt("kind", c.kind());
            ct.putIntArray("operands", c.operands());
            ct.putDouble("value", c.value());
            constraints.add(ct);
        }
        entry.put("constraints", constraints);
        return entry;
    }

    public static SketchData load(final CompoundTag tag, final HolderLookup.Provider registries) {
        final SketchData data = new SketchData();
        final ListTag list = tag.getList("sketches", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            final Sketch sketch = readSketch(list.getCompound(i));
            if (sketch != null) {
                data.sketches.put(sketch.id(), sketch);
            }
        }
        return data;
    }

    /**
     * 读一条记录。任何异常都返回 null（跳过这条），**不要**让它把整份存档带崩 ——
     * 玩家宁可少一张草图，也不愿意整个世界读不出来。
     *
     * <p>同样是 <b>public</b>：网络同步走这一个方法，见 {@link #writeSketch}。
     */
    public static Sketch readSketch(final CompoundTag entry) {
        try {
            final UUID id = NbtUtils.loadUUID(entry.get("id"));
            final UUID body = NbtUtils.loadUUID(entry.get("body"));
            if (id == null || body == null) {
                return null;
            }
            final CompoundTag p = entry.getCompound("plane");
            final SketchPlane plane = new SketchPlane(
                    new Vector3d(p.getDouble("ox"), p.getDouble("oy"), p.getDouble("oz")),
                    new Vector3d(p.getDouble("ux"), p.getDouble("uy"), p.getDouble("uz")),
                    new Vector3d(p.getDouble("vx"), p.getDouble("vy"), p.getDouble("vz")));

            final Sketch sketch = new Sketch(id, body, entry.getString("name"), plane);

            final List<Sketch.Entity> entities = new ArrayList<>();
            final ListTag entityList = entry.getList("entities", Tag.TAG_COMPOUND);
            for (int i = 0; i < entityList.size(); i++) {
                final CompoundTag et = entityList.getCompound(i);
                entities.add(new Sketch.Entity(
                        Sketch.EntityKind.valueOf(et.getString("kind")),
                        et.getIntArray("vars"),
                        et.getBoolean("cw"))); // 缺键 ⇒ false ✓ 旧存档照常读 ✓
            }

            final List<Sketch.Constraint> constraints = new ArrayList<>();
            final ListTag constraintList = entry.getList("constraints", Tag.TAG_COMPOUND);
            for (int i = 0; i < constraintList.size(); i++) {
                final CompoundTag ct = constraintList.getCompound(i);
                constraints.add(new Sketch.Constraint(
                        ct.getInt("kind"), ct.getIntArray("operands"), ct.getDouble("value")));
            }

            sketch.restore(readDoubles(entry, "vars"), entities, constraints);
            // ★ 辅助线标记 ✓（旧存档没有这个键 ⇒ 空 ✓ 向后兼容 ✓）
            for (final Tag tag : entry.getList("reference", Tag.TAG_STRING)) {
                sketch.addReferenceKey(tag.getAsString());
            }
            // ★ 文字图元（item 3）✓
            for (final Tag tag : entry.getList("texts", Tag.TAG_COMPOUND)) {
                final CompoundTag nt = (CompoundTag) tag;
                sketch.addText(new Sketch.TextNote(
                        nt.getDouble("u"), nt.getDouble("v"), nt.getDouble("w"), nt.getDouble("h"),
                        nt.getString("text"), nt.getInt("argb"),
                        nt.contains("scale") ? nt.getDouble("scale") : 1.0,
                        nt.contains("rot") ? nt.getDouble("rot") : 0.0));
            }
            final ListTag consList = entry.getList("construction", Tag.TAG_STRING);
            for (int i = 0; i < consList.size(); i++) {
                sketch.addConstructionKey(consList.getString(i));
            }
            // ★ 需求 ④ 迁移：旧存档里的圆弧**没有两条辅助线** ⇒ 自动补上 ✓
            //   （幂等 ⇒ 新存档里已经有那两条线时什么也不做 ✓）
            sketch.ensureAllArcHandles();
            return sketch;
        } catch (final Exception e) {
            return null;
        }
    }

    /** {@code double[]} → ListTag of TAG_DOUBLE（原版 NBT 没有 double 数组）。 */
    private static ListTag writeDoubles(final double[] values) {
        final ListTag list = new ListTag();
        for (final double v : values) {
            list.add(net.minecraft.nbt.DoubleTag.valueOf(v));
        }
        return list;
    }

    private static double[] readDoubles(final CompoundTag tag, final String key) {
        final ListTag list = tag.getList(key, Tag.TAG_DOUBLE);
        final double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.getDouble(i);
        }
        return out;
    }
}
