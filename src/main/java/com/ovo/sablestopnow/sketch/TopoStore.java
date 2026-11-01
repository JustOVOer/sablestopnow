package com.ovo.sablestopnow.sketch;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 拓扑引用的**会话登记表**（客户端与服务端共用同一套逻辑）✓
 *
 * <p>为什么先做会话级：拓扑引用是"可以从几何**重新推导**出来的派生数据"，
 * 第一步先把它变成有身份、能参与依赖的对象；持久化（{@code TopoData SavedData}）
 * 放到第二步，这样不会一次改动跨太多层 ✓
 *
 * <p>纯数据、无游戏依赖（离线可测 ✓）。
 */
public final class TopoStore {

    private TopoStore() {
    }

    private static final List<TopoRef> REFS = new ArrayList<>();

    /** 圆角特征 id → 它依赖的棱引用 id ✓（依赖链 圆角 → 棱） */
    private static final java.util.Map<UUID, List<UUID>> FILLET_EDGES =
            new java.util.HashMap<>();

    public static Collection<TopoRef> all() {
        return REFS;
    }

    public static void clear() {
        REFS.clear();
        FILLET_EDGES.clear();
    }

    /** 登记一条棱引用（去重）✓ */
    public static TopoRef ensureEdge(final UUID bodyId, final List<UUID> contributors,
                                     final double[] a, final double[] b,
                                     final double[] n1, final double[] n2) {
        final double[] geom = {a[0], a[1], a[2], b[0], b[1], b[2],
            n1[0], n1[1], n1[2], n2[0], n2[1], n2[2]};
        return TopoRefs.ensure(REFS, REFS::add, TopoRef.Kind.EDGE, bodyId, contributors, geom);
    }

    /** 登记一个面引用 ✓ */
    public static TopoRef ensureFace(final UUID bodyId, final List<UUID> contributors,
                                     final double[] center, final double[] normal,
                                     final double area) {
        return TopoRefs.ensure(REFS, REFS::add, TopoRef.Kind.FACE, bodyId, contributors,
                new double[] {center[0], center[1], center[2],
                    normal[0], normal[1], normal[2], area});
    }

    /** 把圆角特征和它的棱引用绑定 ✓（依赖链：圆角 → 棱） */
    public static void bindFilletEdge(final UUID filletId, final UUID edgeRefId) {
        if (filletId == null || edgeRefId == null) {
            return;
        }
        FILLET_EDGES.computeIfAbsent(filletId, k -> new ArrayList<>()).add(edgeRefId);
    }

    /** 圆角依赖的棱引用 ✓ */
    public static List<UUID> edgesOfFillet(final UUID filletId) {
        return FILLET_EDGES.getOrDefault(filletId, List.of());
    }

    /** 哪些引用依赖某特征（用于删除/重排时提示）✓ */
    public static List<TopoRef> dependingOn(final UUID featureId) {
        return TopoRefs.dependingOn(REFS, featureId);
    }
}
