package com.ovo.sablestopnow.client.model;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * **树行悬停时高亮的特征** ✓（item 6）。
 *
 * <p>只存一个实体 id；渲染侧据此把该实体的线框用高亮色再画一遍 ✓
 * （不修改实体本身、也不改选择集 ⇒ 悬停是"看一眼"，不是"选中"✓）
 */
public final class FeatureHighlight {

    private FeatureHighlight() {
    }

    @Nullable
    private static UUID solidId;

    public static void set(@Nullable final UUID id) {
        solidId = id;
    }

    public static void clear() {
        solidId = null;
    }

    @Nullable
    public static UUID id() {
        return solidId;
    }

    public static boolean matches(final UUID candidate) {
        return solidId != null && solidId.equals(candidate);
    }
}
