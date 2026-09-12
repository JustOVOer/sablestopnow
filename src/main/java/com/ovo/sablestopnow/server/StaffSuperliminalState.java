package com.ovo.sablestopnow.server;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 彩蛋：Superliminal 模式（按玩家开关，运行期内存）。
 *
 * <p>开启后，该玩家整组拖拽时：客户端每 tick 把物体放到「视线射线命中的平面/物理结构」上
 * （有最大距离限制，未命中则放最大距离处），并按「当前距离 / 抓取时距离」等比缩放，
 * 使物体在屏幕上的视觉大小保持不变（超阈限空间那套透视错觉）。
 */
public final class StaffSuperliminalState {

    private static final Set<UUID> ENABLED = new LinkedHashSet<>();

    private StaffSuperliminalState() {
    }

    /** @return 切换后是否开启 */
    public static boolean toggle(final UUID player) {
        if (!ENABLED.remove(player)) {
            ENABLED.add(player);
            return true;
        }
        return false;
    }

    public static boolean isEnabled(final UUID player) {
        return ENABLED.contains(player);
    }

    public static void release(final UUID player) {
        ENABLED.remove(player);
    }

    public static void clear() {
        ENABLED.clear();
    }
}
