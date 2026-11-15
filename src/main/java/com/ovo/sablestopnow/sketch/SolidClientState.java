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
     * **按物理结构分组的二级缓存**。
     *
     * <p>为什么需要它：{@link Solid#placeAll} 一次处理**整个维度**的实体，
     * 而放置/克隆一个特征只会改变**一个结构**。以前随便加一个特征都会把
     * 所有结构的切割/圆角 CSG 全部重跑一遍 —— 实测一个 24 实体的复杂结构会让
     * 创建克隆卡住好几秒（用户反馈"克隆创建出现长时间卡顿"）。
     *
     * <p>现在先按 body 分组、各自算签名：只有内容真的变了的 body 才重算，
     * 其它 body 直接复用上一次的 Placed（Placed 实例复用也让 Features 的缓存继续有效）。
     */
    private static final Map<String, BodyCache> BODY_CACHE = new HashMap<>();

    private record BodyCache(String sig, List<Solid.Placed> placed) {
    }

    public static List<Solid.Placed> placed(final ResourceLocation dimension) {
        final List<Solid> solids = in(dimension);
        final List<Sketch> sketches = SketchClientState.in(dimension);
        // 草图签名：变量/约束/图元数量（与旧实现一致）
        final StringBuilder sketchSig = new StringBuilder();
        for (final Sketch k : sketches) {
            sketchSig.append(k.id()).append(':')
                    .append(java.util.Arrays.hashCode(k.variables())).append(':')
                    .append(k.constraints().size()).append(':')
                    .append(k.entities().size()).append(';');
        }
        final StringBuilder sb = new StringBuilder(64);
        for (final Solid s : solids) {
            // ★ 修实测"点选材质后没有生效"✗：签名必须包含**外观/贴图**（整条 Solid 的哈希）
            //   只算 shape ⇒ 换贴图时签名不变 ⇒ 缓存命中旧 Placed（旧外观）⇒ 看起来"没加材质"✗
            sb.append(s.id()).append(':').append(s.index()).append(':')
                    .append(s.shape().hashCode()).append(':').append(s.hashCode()).append(';');
        }
        final String sig = sb.append('|').append(sketchSig).toString();
        if (sig.equals(placedSig)) {
            return placedCache;
        }
        placedSig = sig;

        // ★ 跨 body 的引用（镜像/阵列/克隆的源、圆角棱、基准面源）会让"按 body 分头求值"
        //   看不到别的 body ⇒ 这种情况退回整维度一次算（正确优先，慢一点也只在改模型时）。
        if (hasCrossBodyRefs(solids)) {
            BODY_CACHE.clear();
            placedCache = List.copyOf(Solid.placeAll(solids, sketches));
            return placedCache;
        }

        // 按 body 分组（保持首次出现顺序）
        final java.util.LinkedHashMap<UUID, List<Solid>> groups = new java.util.LinkedHashMap<>();
        for (final Solid s : solids) {
            groups.computeIfAbsent(s.body(), k -> new ArrayList<>()).add(s);
        }
        final Map<String, BodyCache> next = new HashMap<>();
        final List<Solid.Placed> out = new ArrayList<>(solids.size());
        for (final Map.Entry<UUID, List<Solid>> e : groups.entrySet()) {
            final String bodySig = bodySignature(e.getValue(), sketchSig);
            final String key = dimension + "#" + e.getKey();
            final BodyCache cached = BODY_CACHE.get(key);
            final List<Solid.Placed> part;
            if (cached != null && cached.sig().equals(bodySig)) {
                part = cached.placed();
            } else {
                final long t0 = System.nanoTime();
                part = List.copyOf(Solid.placeAll(e.getValue(), sketches));
                final long ms = (System.nanoTime() - t0) / 1_000_000L;
                if (ms >= 100L) {
                    Solid.trace("perf: placed body=" + e.getKey()
                            + " solids=" + e.getValue().size() + " ms=" + ms);
                }
            }
            next.put(key, new BodyCache(bodySig, part));
            out.addAll(part);
        }
        BODY_CACHE.clear();
        BODY_CACHE.putAll(next);
        placedCache = List.copyOf(out);
        return placedCache;
    }

    /** 一个 body 的内容签名（实体 + 全局草图签名）。 */
    private static String bodySignature(final List<Solid> bodySolids, final StringBuilder sketchSig) {
        final StringBuilder sb = new StringBuilder(64);
        for (final Solid s : bodySolids) {
            sb.append(s.id()).append(':').append(s.index()).append(':')
                    .append(s.shape().hashCode()).append(':').append(s.hashCode()).append(';');
        }
        return sb.append('|').append(sketchSig).toString();
    }

    /** 有没有跨 body 的特征引用（有就退回整维度求值）。 */
    private static boolean hasCrossBodyRefs(final List<Solid> solids) {
        final Map<UUID, UUID> bodyOf = new HashMap<>();
        for (final Solid s : solids) {
            bodyOf.put(s.id(), s.body());
        }
        for (final Solid s : solids) {
            final UUID own = s.body();
            if (s.shape() instanceof final Solid.Shape.Fillet f) {
                for (final Solid.Shape.EdgeRef ref : f.edges()) {
                    if (ref.solidId() != null && !java.util.Objects.equals(bodyOf.get(ref.solidId()), own)) {
                        return true;
                    }
                }
            }
            if (s.shape() instanceof final Solid.Shape.Mirror m) {
                if (m.planeSolidId() != null
                        && !java.util.Objects.equals(bodyOf.get(m.planeSolidId()), own)) {
                    return true;
                }
                for (final UUID src : m.sources()) {
                    if (!java.util.Objects.equals(bodyOf.get(src), own)) {
                        return true;
                    }
                }
            }
            if (s.shape() instanceof final Solid.Shape.Pattern p) {
                for (final UUID src : p.sources()) {
                    if (!java.util.Objects.equals(bodyOf.get(src), own)) {
                        return true;
                    }
                }
            }
            if (s.shape() instanceof final Solid.Shape.Clone c) {
                for (final UUID src : c.sources()) {
                    if (!java.util.Objects.equals(bodyOf.get(src), own)) {
                        return true;
                    }
                }
            }
            if (s.shape() instanceof final Solid.Shape.Plane p
                    && p.sourceSolid() != null
                    && !java.util.Objects.equals(bodyOf.get(p.sourceSolid()), own)) {
                return true;
            }
        }
        return false;
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
