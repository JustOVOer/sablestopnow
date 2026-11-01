package com.ovo.sablestopnow.sketch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 拓扑引用的**去重与登记**（纯逻辑，可离线测试 ✓）。
 *
 * <p>存储由调用方注入（{@code Collection} + {@code Consumer}），这样：
 * <ul>
 *   <li>离线 harness 可以直接用 {@code ArrayList} 测去重/多来源 ✓</li>
 *   <li>客户端/服务端用各自的容器（会话缓存 / SavedData）✓</li>
 * </ul>
 *
 * <p>纯计算、无游戏依赖（本包被离线单独编译 ✓）。
 */
public final class TopoRefs {

    private TopoRefs() {
    }

    /**
     * 取得（必要时创建）一个拓扑引用 ✓
     *
     * @param existing 现有引用表（用于去重）
     * @param sink     新建时的落地回调（调用方决定存哪）
     */
    public static TopoRef ensure(final Collection<TopoRef> existing,
                                 final java.util.function.Consumer<TopoRef> sink,
                                 final TopoRef.Kind kind,
                                 final UUID bodyId,
                                 final List<UUID> contributors,
                                 final double[] geom) {
        final TopoRef probe = new TopoRef(UUID.randomUUID(), kind, bodyId, contributors, geom);
        final String key = probe.dedupKey();
        if (existing != null) {
            for (final TopoRef ref : existing) {
                if (ref.dedupKey().equals(key)) {
                    return ref; // 同一个几何 + 同一组来源 ⇒ 复用 ✓
                }
            }
        }
        if (sink != null) {
            sink.accept(probe);
        }
        return probe;
    }

    /** 把引用表建成 去重键 → 引用 的索引 ✓ */
    public static Map<String, TopoRef> index(final Collection<TopoRef> refs) {
        final Map<String, TopoRef> map = new HashMap<>();
        if (refs != null) {
            for (final TopoRef r : refs) {
                map.putIfAbsent(r.dedupKey(), r);
            }
        }
        return map;
    }

    /**
     * 某特征被删除/移动时，哪些引用会失效 ✓
     *
     * <p>这就是"棱同时依赖多个特征"的价值：只要 contributors 里有它，就算失效 ✓
     */
    public static List<TopoRef> dependingOn(final Collection<TopoRef> refs, final UUID featureId) {
        final List<TopoRef> out = new ArrayList<>();
        if (refs == null || featureId == null) {
            return out;
        }
        for (final TopoRef r : refs) {
            if (r.contributors().contains(featureId)) {
                out.add(r);
            }
        }
        return out;
    }
}
