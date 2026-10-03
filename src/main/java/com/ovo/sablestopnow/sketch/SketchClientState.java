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
 * 客户端侧的草图镜像：服务端权威，这里只做投影。
 *
 * <p>与 {@code ForceClientState} 同一模式：服务端每次改动都广播该维度的全量，
 * 客户端整体替换。**不做本地乐观更新** —— 草图是求解结果，本地猜一个再被服务端纠正
 * 会出现"线闪一下又跳回去"，比慢半拍难看得多。
 *
 * <p>按维度分别存：玩家跨维度时不能还显示上一个维度的草图。
 */
public final class SketchClientState {

    private static final Map<ResourceLocation, List<Sketch>> BY_DIMENSION = new HashMap<>();

    private SketchClientState() {
    }

    /** 收到一个维度的全量同步：整份替换。 */
    public static void accept(final ResourceLocation dimension, final CompoundTag data) {
        // 用与服务端**同一个**解码器（SketchData.load），坏记录逐条跳过。
        final SketchData parsed = SketchData.load(data, null);
        BY_DIMENSION.put(dimension, List.copyOf(parsed.all().values()));
    }

    public static void clear() {
        BY_DIMENSION.clear();
    }

    /** 某个维度的全部草图。 */
    public static List<Sketch> in(final ResourceLocation dimension) {
        return BY_DIMENSION.getOrDefault(dimension, List.of());
    }

    /** 当前维度的全部草图（渲染与设计树用）。 */
    public static List<Sketch> current(final Level level) {
        return in(level.dimension().location());
    }

    /**
     * 某个维度里属于某个结构的草图。
     *
     * <p>维度键版本才是主体（{@code Level} 那个只是糖），这样状态本身不依赖 {@code Level}，
     * 离线自检不需要造一个世界出来。
     */
    public static List<Sketch> ofBody(final ResourceLocation dimension, final UUID body) {
        final List<Sketch> out = new ArrayList<>();
        for (final Sketch sketch : in(dimension)) {
            if (sketch.body().equals(body)) {
                out.add(sketch);
            }
        }
        return out;
    }

    public static List<Sketch> ofBody(final Level level, final UUID body) {
        return ofBody(level.dimension().location(), body);
    }

    /** 按 id 找一张草图（编辑时用）。 */
    public static Sketch byId(final ResourceLocation dimension, final UUID id) {
        for (final Sketch sketch : in(dimension)) {
            if (sketch.id().equals(id)) {
                return sketch;
            }
        }
        return null;
    }

    public static Sketch byId(final Level level, final UUID id) {
        return byId(level.dimension().location(), id);
    }

    /** 所有维度里的草图总数（自检/日志用）。 */
    public static int total() {
        int n = 0;
        for (final List<Sketch> list : BY_DIMENSION.values()) {
            n += list.size();
        }
        return n;
    }

    /** 只读快照，测试用。 */
    public static Map<ResourceLocation, List<Sketch>> snapshot() {
        return Collections.unmodifiableMap(BY_DIMENSION);
    }
}
