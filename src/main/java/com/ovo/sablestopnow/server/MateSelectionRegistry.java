package com.ovo.sablestopnow.server;

import com.ovo.sablestopnow.mate.MateRef;
import com.ovo.sablestopnow.mate.MateType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 配合模式下「已经点了几端参考」的服务端权威状态。
 *
 * <p>每名玩家一条待选列表（最多两端）。点满两端就自动尝试成配 ——
 * 具体的配合类型由 {@link #inferType} 从两个参考的几何推断（与 SolidWorks 一样，选两个面默认就是重合），
 * 玩家之后可以在树形界面里改类型/数值。
 */
public final class MateSelectionRegistry {

    /** 玩家 -> 已选参考（0..2 个）。 */
    private static final Map<UUID, List<MateRef>> PENDING = new HashMap<>();

    /** 玩家 -> 是否处于配合模式。 */
    private static final Map<UUID, Boolean> MODE = new HashMap<>();

    private MateSelectionRegistry() {
    }

    // ============ 模式 ============

    public static synchronized boolean inMode(final UUID player) {
        return MODE.getOrDefault(player, false);
    }

    /** 进入/退出配合模式；退出时清空待选。返回是否处于模式中。 */
    public static synchronized boolean setMode(final UUID player, final boolean enable) {
        if (enable) {
            MODE.put(player, true);
            PENDING.remove(player);
        } else {
            MODE.remove(player);
            PENDING.remove(player);
        }
        return enable;
    }

    public static synchronized void release(final UUID player) {
        MODE.remove(player);
        PENDING.remove(player);
    }

    // ============ 待选 ============

    public static synchronized List<MateRef> pending(final UUID player) {
        final List<MateRef> list = PENDING.get(player);
        return list == null ? List.of() : List.copyOf(list);
    }

    /**
     * 追加一端参考。返回追加后是否已满两端。
     *
     * <p>同一个体的两端会被拒绝（返回 -1 之外的语义由调用方处理）；这里只负责收集。
     */
    public static synchronized int add(final UUID player, final MateRef ref) {
        final List<MateRef> list = PENDING.computeIfAbsent(player, k -> new ArrayList<>(2));
        if (list.size() >= 2) {
            list.clear();
        }
        list.add(ref);
        return list.size();
    }

    /** 取消最后选定的一端；返回剩余数量。 */
    public static synchronized int undo(final UUID player) {
        final List<MateRef> list = PENDING.get(player);
        if (list == null || list.isEmpty()) {
            return 0;
        }
        list.remove(list.size() - 1);
        return list.size();
    }

    public static synchronized void clear(final UUID player) {
        PENDING.remove(player);
    }

    // ============ 类型推断 ============

    /**
     * 从两端参考的几何自动推断配合类型 —— 对应「选够两个自动开始配合」。
     *
     * <p>规则刻意保守（推断错了玩家可以在界面里改）：
     * <ul>
     *   <li>两端都是「轴线类」（棱 / 结构主轴）→ <b>同心</b>：这是唯一一个两者都像轴时更合理的默认；</li>
     *   <li>其余情况 → <b>重合</b>：点—点重合、点落在面上、面—面共面，都是 SolidWorks 里选两个几何后的默认动作。</li>
     * </ul>
     */
    public static MateType inferType(final MateRef a, final MateRef b) {
        if (isAxisLike(a) && isAxisLike(b)) {
            return MateType.CONCENTRIC;
        }
        return MateType.COINCIDENT;
    }

    private static boolean isAxisLike(final MateRef ref) {
        return switch (ref.kind()) {
            case EDGE, BODY_AXIS -> true;
            default -> false;
        };
    }

    /** 当前待选（便于在提示里显示「已选 1/2」）。 */
    @Nullable
    public static synchronized MateRef last(final UUID player) {
        final List<MateRef> list = PENDING.get(player);
        return list == null || list.isEmpty() ? null : list.get(list.size() - 1);
    }
}
