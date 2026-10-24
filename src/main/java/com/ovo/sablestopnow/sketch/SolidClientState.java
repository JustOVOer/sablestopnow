package com.ovo.sablestopnow.sketch;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端侧的**实体**镜像：服务端权威，这里只做投影。
 *
 * <p>与 {@link SketchClientState} 同一模式：服务端每次改动广播该维度全量、客户端整体替换、
 * 不做本地乐观更新、按维度分别存。
 *
 * <p>⚠ 镜像里只有「身份 + 引用 + 参数」—— 盒子要拿 {@link SolidClientState#placedIn}
 * 把实体和**草图镜像**拼起来现算。渲染与碰撞都用那一份结果。
 */
public final class SolidClientState {

    private static final Map<ResourceLocation, List<Solid>> BY_DIMENSION = new HashMap<>();

    private SolidClientState() {
    }

    /** 收到一个维度的全量同步：整份替换。 */
    public static void accept(final ResourceLocation dimension, final CompoundTag data) {
        // 用与服务端**同一个**解码器（SolidData.load），坏记录逐条跳过。
        final SolidData parsed = SolidData.load(data, null);
        BY_DIMENSION.put(dimension, List.copyOf(parsed.all().values()));
    }

    public static void clear() {
        BY_DIMENSION.clear();
    }

    /** 某个维度的全部实体。 */
    public static List<Solid> in(final ResourceLocation dimension) {
        return BY_DIMENSION.getOrDefault(dimension, List.of());
    }

    /** 当前维度的全部实体。 */
    public static List<Solid> current(final Level level) {
        return in(level.dimension().location());
    }

    /** 某个维度里属于某个结构的实体（设计树按结构分组）。 */
    public static List<Solid> ofBody(final ResourceLocation dimension, final UUID body) {
        final List<Solid> out = new ArrayList<>();
        for (final Solid solid : in(dimension)) {
            if (solid.body().equals(body)) {
                out.add(solid);
            }
        }
        return out;
    }

    public static List<Solid> ofBody(final Level level, final UUID body) {
        return ofBody(level.dimension().location(), body);
    }

    /**
     * 把实体和草图镜像拼起来，现算出「实体 + 它的盒子」。
     *
     * <p>⚠ 这里必须用**同一个维度**的草图，而且解析不到的草图要被跳过 ——
     * 草图可能还没同步过来。渲染和碰撞都走这一个入口，免得两处各拼一次还算得不一样。
     */
    /** 上次算 placed 时的内容签名 + 结果（**每帧重算会卡死** ⇒ 必须缓存 ✓）。 */
    private static String placedSig = "";
    private static List<Solid.Placed> placedCache = List.of();

    /**
     * 实体的"已放置"几何（盒子/线框/网格）✓ —— **带缓存**。
     *
     * <p>★ 修实测"含贝塞尔的切割创建后大量卡顿"✗：
     * 这个方法被**渲染每帧**调用，而它内部会跑 {@link Solid#placeAll}（切割还要跑 CSG BSP）
     * ⇒ 每帧一次布尔运算 ⇒ 直接卡成幻灯片 ✗
     * ⇒ 按"内容签名"（实体 id/序号/hash + 草图变量与约束数）缓存 ✓ 只在真的变了才重算 ✓
     */
    public static List<Solid.Placed> placed(final ResourceLocation dimension) {
        final List<Solid> solids = in(dimension);
        final List<Sketch> sketches = SketchClientState.in(dimension);
        final StringBuilder sb = new StringBuilder(64);
        for (final Solid s : solids) {
            sb.append(s.id()).append(':').append(s.index()).append(':')
                    .append(s.shape().hashCode()).append(';');
        }
        sb.append('|');
        for (final Sketch k : sketches) {
            sb.append(k.id()).append(':')
                    .append(java.util.Arrays.hashCode(k.variables())).append(':')
                    .append(k.constraints().size()).append(':')
                    .append(k.entities().size()).append(';');
        }
        final String sig = sb.toString();
        if (sig.equals(placedSig)) {
            return placedCache;
        }
        placedSig = sig;
        placedCache = Solid.placeAll(solids, sketches);
        return placedCache;
    }

    public static List<Solid.Placed> placed(final Level level) {
        return placed(level.dimension().location());
    }

    /** 按 id 找一个实体（编辑/删除时用）。 */
    public static Solid byId(final ResourceLocation dimension, final UUID id) {
        for (final Solid solid : in(dimension)) {
            if (solid.id().equals(id)) {
                return solid;
            }
        }
        return null;
    }

    public static Solid byId(final Level level, final UUID id) {
        return byId(level.dimension().location(), id);
    }

    /** 所有维度里的实体总数（自检/日志用）。 */
    public static int total() {
        int n = 0;
        for (final List<Solid> list : BY_DIMENSION.values()) {
            n += list.size();
        }
        return n;
    }

    /** 只读快照，测试用。 */
    public static Map<ResourceLocation, List<Solid>> snapshot() {
        return Collections.unmodifiableMap(BY_DIMENSION);
    }
}
