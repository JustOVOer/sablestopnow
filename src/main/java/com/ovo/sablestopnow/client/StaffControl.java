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
        DRAG("sablestopnow.control.mode.drag", "sablestopnow.control.mode.drag.desc"),
        /**
         * 配合模式（Y 进入/退出）。
         *
         * <p>与其它模式<b>同级</b>：状态在 {@code MateClientState}，画面由
         * {@code MateSidebarRenderer} 画成 HUD 覆盖层（常驻右侧边栏），输入同样走
         * {@code StaffEnhanceClientHandler.handleMouse/handleScroll}。
         * 它<b>不是一个 Screen</b> —— 用 Screen 的话任何 {@code setScreen(其它界面)}
         * 都会把常驻边栏顶掉（实测按 Y 后 18ms 内被顶掉两次）。
         */
        MATE("sablestopnow.control.mode.mate", "sablestopnow.control.mode.mate.desc");

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

    /**
     * 普通模式：第一个必须是「锁定切换」（对应原版左键锁定）。
     * 后面依次是视角锁定（原长按 C）、快照 K、回退 R、所有权 O、无碰撞 V、缩放 X、Ctrl+O。
     */
    private static final List<Fn> NORMAL = List.of(
            Fn.LOCK, Fn.VIEW_LOCK, Fn.SNAPSHOT, Fn.RESTORE, Fn.OWNERSHIP, Fn.NO_COLLISION, Fn.SCALE,
            Fn.OPEN_CONFIG);

    /** 多选模式：原来的 Z 键功能 + 清空队列。 */
    private static final List<Fn> MULTI = List.of(Fn.REGION, Fn.CLEAR);

    /**
     * 整组拖拽模式：第一个同样是「锁定切换」，其后是归中 C、无碰撞 V、快照 K、回退 R、所有权 O、缩放 X。
     */
    private static final List<Fn> DRAG = List.of(
            Fn.LOCK, Fn.CENTER, Fn.NO_COLLISION, Fn.SNAPSHOT, Fn.RESTORE, Fn.OWNERSHIP, Fn.SCALE);

    /**
     * 配合模式：功能由右栏与鼠标按键承担，这里只列「锁回鼠标」这一项 —— 它是配合模式里
     * 唯一需要用键盘模拟的开关（鼠标释放后要靠它回去）。
     */
    private static final List<Fn> MATE = List.of(Fn.LOCK, Fn.NO_COLLISION, Fn.SNAPSHOT, Fn.OWNERSHIP);

    private StaffControl() {
    }

    public static List<Fn> functionsFor(final Mode mode) {
        return switch (mode) {
            case NORMAL -> NORMAL;
            case MULTI -> MULTI;
            case DRAG -> DRAG;
            case MATE -> MATE;
        };
    }
}
