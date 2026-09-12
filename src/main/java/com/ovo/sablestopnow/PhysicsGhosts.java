package com.ovo.sablestopnow;

import java.util.Map;
import java.util.UUID;

/**
 * 「正在被拖拽的物理结构 → 拖拽它的玩家」的全局快照（双端共用，无客户端依赖）。
 *
 * <p>用途：{@code SubLevelEntityCollisionGhostMixin} 在 Sable 的实体↔物理结构碰撞漏斗里，
 * 把这些结构对<b>正在拖拽它的那个玩家</b>过滤掉，实现「拖拽中的结构不再碰撞拖拽者」。
 *
 * <p>⚠ 玩家的真实碰撞在**客户端**计算（Sable 里 ServerPlayer 分支只是早退的“假地面”），
 * 所以这份状态必须双端都有：服务端每 tick 权威计算并广播，客户端收到后写入同一份注册表。
 *
 * <p>用 volatile 的不可变快照，避免碰撞（客户端渲染线程）与服务端 tick 线程之间的可见性/并发问题。
 */
public final class PhysicsGhosts {

    private static volatile Map<UUID, UUID> draggers = Map.of();
    /** 对所有玩家都幽灵化的结构（缩放中的结构：它的物理碰撞体不随缩放变化，不幽灵化会把玩家“吸住”）。 */
    private static volatile java.util.Set<UUID> globalGhosts = java.util.Set.of();

    private PhysicsGhosts() {
    }

    public static boolean isEmpty() {
        return draggers.isEmpty() && globalGhosts.isEmpty();
    }

    /** 该实体是否应当无视这个物理结构。 */
    public static boolean ignores(final UUID subLevel, final UUID entity) {
        if (globalGhosts.contains(subLevel)) {
            return true;
        }
        final UUID dragger = draggers.get(subLevel);
        return dragger != null && dragger.equals(entity);
    }

    public static void set(final Map<UUID, UUID> map) {
        draggers = map.isEmpty() ? Map.of() : Map.copyOf(map);
    }

    public static void setGlobal(final java.util.Collection<UUID> ids) {
        globalGhosts = ids.isEmpty() ? java.util.Set.of() : java.util.Set.copyOf(ids);
    }

    public static void clear() {
        draggers = Map.of();
        globalGhosts = java.util.Set.of();
    }
}
