package com.ovo.sablestopnow.client;

import java.util.List;

/**
 * 新控制逻辑的「模式 / 功能」模型（纯数据，不含状态）。
 *
 * <p>新逻辑（config {@code [staff_enhance] new_control_scheme}，默认开启）：
 * <b>Ctrl</b> 切换多选、<b>滚轮</b>切换功能、<b>左键</b>应用当前功能。
 * 每个模式有自己的一份功能清单，HUD 会把整份清单列出来（离当前项越远越虚化）。</p>
 */
public final class StaffControl {

    /** 当前所处的模式（由运行时状态推导，不是独立开关）。 */
    public enum Mode {
        /** 普通模式：未多选、未整组拖拽。 */
        NORMAL("sablestopnow.control.mode.normal", "sablestopnow.control.mode.normal.desc"),
        /** 多选模式（Ctrl）。 */
        MULTI("sablestopnow.control.mode.multi", "sablestopnow.control.mode.multi.desc"),
        /** 整组拖拽模式（退出多选后右键队列成员进入）。 */
        DRAG("sablestopnow.control.mode.drag", "sablestopnow.control.mode.drag.desc");

        public final String nameKey;
        public final String descKey;

        Mode(final String nameKey, final String descKey) {
            this.nameKey = nameKey;
            this.descKey = descKey;
        }
    }

    /** 可选功能。{@code wheelKey} 是「该功能激活后滚轮做什么」的提示文案。 */
    public enum Fn {
        VIEW_LOCK("sablestopnow.control.fn.view_lock", "sablestopnow.control.fn.view_lock.desc", null),
        CENTER("sablestopnow.control.fn.center", "sablestopnow.control.fn.center.desc", null),
        LOCK("sablestopnow.control.fn.lock", "sablestopnow.control.fn.lock.desc", null),
        NO_COLLISION("sablestopnow.control.fn.no_collision", "sablestopnow.control.fn.no_collision.desc", null),
        SNAPSHOT("sablestopnow.control.fn.snapshot", "sablestopnow.control.fn.snapshot.desc", null),
        RESTORE("sablestopnow.control.fn.restore", "sablestopnow.control.fn.restore.desc", null),
        OWNERSHIP("sablestopnow.control.fn.ownership", "sablestopnow.control.fn.ownership.desc", null),
        SCALE("sablestopnow.control.fn.scale", "sablestopnow.control.fn.scale.desc",
                "sablestopnow.control.wheel.scale"),
        OPEN_CONFIG("sablestopnow.control.fn.open_config", "sablestopnow.control.fn.open_config.desc", null),
        REGION("sablestopnow.control.fn.region", "sablestopnow.control.fn.region.desc",
                "sablestopnow.control.wheel.region"),
        CLEAR("sablestopnow.control.fn.clear", "sablestopnow.control.fn.clear.desc", null);

        public final String nameKey;
        public final String descKey;
        /** 该功能激活后滚轮的用途（null = 不占用滚轮）。 */
        public final String wheelKey;

        Fn(final String nameKey, final String descKey, final String wheelKey) {
            this.nameKey = nameKey;
            this.descKey = descKey;
            this.wheelKey = wheelKey;
        }

        /** 持续型功能（激活后要保持光条旋转，直到动作结束）。 */
        public boolean sustained() {
            return this == SCALE || this == REGION;
        }
    }

    /** 普通模式：原来的 K R O V X、左键锁定、Ctrl+O；另外把「视角锁定」（原长按 C）也做成按住型功能。 */
    private static final List<Fn> NORMAL = List.of(
            Fn.VIEW_LOCK, Fn.LOCK, Fn.SNAPSHOT, Fn.RESTORE, Fn.OWNERSHIP, Fn.NO_COLLISION, Fn.SCALE, Fn.OPEN_CONFIG);

    /** 多选模式：原来的 Z 键功能 + 清空队列。 */
    private static final List<Fn> MULTI = List.of(Fn.REGION, Fn.CLEAR);

    /** 整组拖拽模式：原来的 K R C O V X + 左键锁定。 */
    private static final List<Fn> DRAG = List.of(
            Fn.CENTER, Fn.LOCK, Fn.NO_COLLISION, Fn.SNAPSHOT, Fn.RESTORE, Fn.OWNERSHIP, Fn.SCALE);

    private StaffControl() {
    }

    public static List<Fn> functionsFor(final Mode mode) {
        return switch (mode) {
            case NORMAL -> NORMAL;
            case MULTI -> MULTI;
            case DRAG -> DRAG;
        };
    }
}
