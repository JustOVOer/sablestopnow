package com.ovo.sablestopnow.client.model;

import com.ovo.sablestopnow.sketch.Solid;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * **特征的永久名字** ✓。
 *
 * <p>问题：树里显示的是"圆角 + 序号"，而序号会随上移/下移变化 ⇒ 每次移动所有标签都跟着变
 * （用户实测："上移后 10 变成 11、11 变成 3"）。
 *
 * <p>做法：**第一次见到某个特征时**按类型分配一个稳定编号（圆角1、切割2…），
 * 之后无论怎么排序/移动，名字都不变；排序只体现在树里的**行位置**上 ✓
 *
 * <p>限制（明确说清）：这份映射是会话内的。要跨存档保留，需要给 `Solid` 加一个 name 字段
 * 并写进 NBT —— 那是下一步；当前先保证"一次游戏内移动不改变名字"。
 */
public final class FeatureNames {

    private FeatureNames() {
    }

    /** 实体 id → 永久名字 ✓ */
    private static final Map<UUID, String> NAMES = new HashMap<>();

    /** 每种类型的下一个编号 ✓ */
    private static final Map<String, Integer> NEXT = new HashMap<>();

    /** 取（必要时分配）某个特征的名字 ✓ */
    public static String of(final Solid solid) {
        if (solid == null) {
            return "";
        }
        final String cached = NAMES.get(solid.id());
        if (cached != null) {
            return cached;
        }
        final String kind = kindOf(solid);
        final int n = NEXT.merge(kind, 1, Integer::sum);
        final String name = kind + n;
        NAMES.put(solid.id(), name);
        return name;
    }

    /** 这个名字对应的类型（用于分配编号）✓ */
    private static String kindOf(final Solid solid) {
        if (solid.isPattern()) {
            return "阵列";
        }
        if (solid.isMirror()) {
            return "镜像";
        }
        if (solid.isFillet()) {
            return "圆角";
        }
        if (solid.isPlane()) {
            return "基准面";
        }
        if (solid.shape() instanceof Solid.Shape.Cut) {
            return "切割";
        }
        if (solid.isRevolution()) {
            return "旋转";
        }
        return "拉伸";
    }

    /**
     * 首次进入世界时**按当前顺序**预分配名字 ✓（否则名字会随"先看到谁"而变）
     */
    public static void seed(final Level level) {
        if (level == null || !NAMES.isEmpty()) {
            return;
        }
        final java.util.List<Solid> all = new java.util.ArrayList<>(
                com.ovo.sablestopnow.sketch.SolidClientState.in(level.dimension().location()));
        all.sort(java.util.Comparator.comparingInt(Solid::index)
                .thenComparing(s -> s.id().toString()));
        for (final Solid s : all) {
            of(s);
        }
    }

    /** 清空（换世界/重载时）✓ */
    public static void clear() {
        NAMES.clear();
        NEXT.clear();
    }

    /** 便捷：world 可用时先 seed ✓ */
    public static String of(final Solid solid, final boolean unusedSeed) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.level != null) {
            seed(mc.level);
        }
        return of(solid);
    }
}
