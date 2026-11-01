package com.ovo.sablestopnow.sketch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 从**现有特征**重建拓扑引用 ✓（确定性：同样的几何 + 同样的来源 ⇒ 同一个 id）。
 *
 * <h2>为什么可以"两端各自重建"而不需要网络包</h2>
 * 引用的身份由 {@link TopoRef#dedupKey()} 决定（几何量化 + contributors 排序）。
 * 客户端和服务端看到的 `Solid` / `Sketch` 是同一份权威数据 ⇒ 两边各自调用本类，
 * 得到的是**同一套 id**。于是：
 * <ul>
 *   <li>服务端用它落盘（{@code TopoData}）✓</li>
 *   <li>客户端用它填 UI / 做依赖检查（{@code TopoStore}）✓</li>
 *   <li>**不需要**新增网络包，也不会出现两端 id 不一致 ✓</li>
 * </ul>
 *
 * <p>纯逻辑、无游戏依赖（离线可测 ✓）。
 */
public final class TopoRebuild {

    private TopoRebuild() {
    }

    /**
     * 扫一遍实体集合，重建所有拓扑引用 ✓
     *
     * @return 圆角特征 id → 它依赖的棱引用 id 列表（与 {@link TopoData#filletEdges()} 同构）
     */
    public static java.util.Map<UUID, List<UUID>> collect(
            final Collection<Solid> solids,
            final java.util.function.Consumer<TopoRef> sink) {
        final java.util.Map<UUID, List<UUID>> out = new java.util.HashMap<>();
        if (solids == null || sink == null) {
            return out;
        }
        final List<TopoRef> existing = new ArrayList<>();
        // 先收集"同 body 的切割"作为共同来源（一条棱可以同时依赖多个特征 ✓）
        final java.util.Map<UUID, List<UUID>> cutsByBody = new java.util.HashMap<>();
        for (final Solid s : solids) {
            if (s.shape() instanceof Solid.Shape.Cut) {
                cutsByBody.computeIfAbsent(s.body(), k -> new ArrayList<>()).add(s.id());
            }
        }
        for (final Solid s : solids) {
            if (!(s.shape() instanceof final Solid.Shape.Fillet f)) {
                continue;
            }
            final List<UUID> contributors = new ArrayList<>(
                    cutsByBody.getOrDefault(s.body(), List.of()));
            final List<UUID> edgeIds = new ArrayList<>();
            for (final Solid.Shape.EdgeRef ref : f.edges()) {
                if (ref.solidId() != null && !contributors.contains(ref.solidId())) {
                    contributors.add(ref.solidId());
                }
                final double[] geom = {ref.ax(), ref.ay(), ref.az(),
                    ref.bx(), ref.by(), ref.bz(),
                    ref.n1x(), ref.n1y(), ref.n1z(),
                    ref.n2x(), ref.n2y(), ref.n2z()};
                final TopoRef edge = TopoRefs.ensure(existing, r -> {
                    existing.add(r);
                    sink.accept(r);
                }, TopoRef.Kind.EDGE, s.body(), contributors, geom);
                if (!existing.contains(edge)) {
                    existing.add(edge);
                }
                edgeIds.add(edge.id());
            }
            out.put(s.id(), edgeIds);
        }
        return out;
    }
}
