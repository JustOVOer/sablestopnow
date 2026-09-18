package com.ovo.sablestopnow.client;

import com.ovo.sablestopnow.mate.Mate;
import com.ovo.sablestopnow.mate.MateRef;
import com.ovo.sablestopnow.network.MateNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 配合系统的客户端状态：配合模式开关、服务端同步来的配合表、当前待选的两端参考、
 * 以及右键候选（用于「渲染右键将会选择到的点/棱/面」的预览高亮）。
 *
 * <p>服务端是权威：待选列表以 {@link MateFeedbackPayload} 回执为准，
 * 本地只有 {@link #previewTarget} 是纯客户端算出来给渲染用的。
 */
public final class MateClientState {

    /** 维度 -> 该维度的配合。 */
    private static final Map<ResourceLocation, List<Mate>> MATES = new HashMap<>();

    private static boolean inMode;
    private static List<MateRef> pending = List.of();

    /** 纯客户端：当前视线将要选中的那一个参考（预览高亮用）。 */
    @Nullable
    private static MateRef previewTarget;
    /** 纯客户端：预览命中点（世界里画准星标记用）。 */
    @Nullable
    private static org.joml.Vector3d previewPoint;
    /** 纯客户端：预览命中的方向（棱/面才有）。 */
    @Nullable
    private static org.joml.Vector3d previewDirection;

    /** 成配后要自动展开定位到的配合 id（GUI 打开后清掉）。 */
    @Nullable
    private static UUID focusMate;

    /** 常驻边栏实例；null = 未显示。刻意不放进 screen 栈（见 {@link #syncPanel}）。 */
    @Nullable
    private static com.ovo.sablestopnow.client.gui.MateTreeScreen sidebar;

    /** 结构级参考的滚轮循环序号（0=中心，1..3=主轴 X/Y/Z，4..6=基准面 X/Y/Z）。 */
    private static int bodyRefCycle;

    /**
     * 鼠标是否处于「锁定」状态（准星可转、右键可选取）。
     *
     * <p>配合模式的边栏常驻，默认锁定鼠标 —— 这样玩家能一边看边栏一边用准星选参考；
     * 要操作边栏上的按钮时按 Tab（或点边栏上的按钮）把鼠标放出来。
     */
    private static boolean cursorLocked = true;

    /** 进入配合模式时客户端记一下当前手杖状态，退出/死亡时用来兜底复位。 */
    private static int lastFeedbackTick;

    private MateClientState() {
    }

    // ============ 模式 ============

    public static boolean inMode() {
        return inMode;
    }

    /**
     * 切换配合模式，并同步「常驻边栏」与鼠标锁定。
     *
     * <p>离开时必须把鼠标交还给游戏（{@code setScreen(null)} 由原版负责 grabMouse）。
     */
    public static void setMode(final boolean enable) {
        inMode = enable;
        if (!enable) {
            pending = List.of();
            clearPreview();
            cursorLocked = true;
        }
        syncPanel();
    }

    /**
     * 让常驻边栏与配合模式保持一致。
     *
     * <p>⚠ <b>这里刻意不调用 {@code Minecraft.setScreen}。</b>
     * 实测把边栏做成 Screen 后，按 Y 打开它会在 18ms 内被 {@code removed()} 两次
     * —— 是我们的代码关的（没走 onClose、没走 keyPressed、没有异常），而是 mod 生态里
     * 别处调用了 {@code setScreen(其它界面)}，把我们从 screen 栈上顶掉了。
     *
     * <p>常驻边栏的正确形态是「HUD 覆盖层」：只画，不占 screen 栈，谁也顶不掉它。
     * 绘制由 {@link com.ovo.sablestopnow.client.MateSidebarRenderer} 在 {@code RenderGuiEvent.Post} 驱动，
     * 输入沿用既有的 {@code StaffEnhanceClientHandler.handleMouse/handleScroll}
     * —— 与本模组既有的 NORMAL / MULTI / DRAG 三个模式完全一致。
     */
    private static void syncPanel() {
        if (inMode) {
            if (sidebar == null) {
                sidebar = new com.ovo.sablestopnow.client.gui.MateTreeScreen(null, consumeFocusMate());
            }
        } else {
            sidebar = null;
            cursorLocked = true;
        }
    }

    /** 当前常驻边栏实例；null = 未显示。由 HUD 渲染事件读取。 */
    @Nullable
    public static com.ovo.sablestopnow.client.gui.MateTreeScreen sidebar() {
        return sidebar;
    }

    /**
     * 配合边栏当前占用的屏幕宽度（像素）；不在配合模式、或边栏还没测过尺寸时返回 0。
     *
     * <p>给别的 HUD 用：右上角的「物理结构详情」面板默认贴右边缘，配合模式一开就会压在常驻边栏上。
     * 让它按这个值整体左移，两套界面才不打架。
     */
    public static int sidebarWidth() {
        final com.ovo.sablestopnow.client.gui.MateTreeScreen panel = sidebar;
        return panel == null ? 0 : panel.overlayWidth();
    }

    // ============ 鼠标锁定 ============

    public static boolean cursorLocked() {
        return cursorLocked;
    }

    /** 切换鼠标锁定；返回切换后是否处于锁定。 */
    public static boolean toggleCursorLock() {
        applyCursorLock(!cursorLocked);
        return cursorLocked;
    }

    public static void applyCursorLock(final boolean locked) {
        cursorLocked = locked;
        final Minecraft mc = Minecraft.getInstance();
        if (mc.mouseHandler == null) {
            return;
        }
        if (locked) {
            mc.mouseHandler.grabMouse();
        } else {
            mc.mouseHandler.releaseMouse();
        }
    }

    /**
     * 退出配合模式（边栏的 Esc / Y 都走这里）。
     *
     * <p>只改本地状态不够：服务端也存着「谁在配合模式」以及他的待选，所以要发包。
     */
    public static void requestExitMode() {
        final Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            setMode(false);
            foundry.veil.api.network.VeilPacketManager.server()
                    .sendPacket(new MateNetworking.MateModePayload(false));
        });
    }

    // ============ 同步来的数据 ============

    public static void setMates(final ResourceLocation dimension, final List<Mate> mates) {
        MATES.put(dimension, new ArrayList<>(mates));
        // 配合可能已经被删除，聚焦目标失效就丢掉
        if (focusMate != null && mates.stream().noneMatch(m -> m.id().equals(focusMate))) {
            focusMate = null;
        }
    }

    /** 当前维度的全部配合。 */
    public static List<Mate> currentMates() {
        final var level = Minecraft.getInstance().level;
        if (level == null) {
            return List.of();
        }
        return MATES.getOrDefault(level.dimension().location(), List.of());
    }

    public static List<Mate> allMates() {
        final List<Mate> out = new ArrayList<>();
        MATES.values().forEach(out::addAll);
        return out;
    }

    /** 某个物理结构牵扯到的配合。 */
    public static List<Mate> matesOf(final UUID body) {
        final List<Mate> out = new ArrayList<>();
        for (final Mate mate : currentMates()) {
            if (mate.touches(body)) {
                out.add(mate);
            }
        }
        return out;
    }

    /** 清空（断开连接/切维度时）。 */
    public static void clearAll() {
        MATES.clear();
        pending = List.of();
        inMode = false;
        focusMate = null;
        sidebar = null;
        clearPreview();
    }

    // ============ 待选 ============

    public static List<MateRef> pending() {
        return pending;
    }

    public static int pendingCount() {
        return pending.size();
    }

    // ============ 预览候选 ============

    @Nullable
    public static MateRef previewTarget() {
        return previewTarget;
    }

    @Nullable
    public static org.joml.Vector3d previewPoint() {
        return previewPoint;
    }

    @Nullable
    public static org.joml.Vector3d previewDirection() {
        return previewDirection;
    }

    public static void setPreview(@Nullable final MateRef ref, @Nullable final org.joml.Vector3d point,
                                  @Nullable final org.joml.Vector3d direction) {
        previewTarget = ref;
        previewPoint = point;
        previewDirection = direction;
    }

    public static void clearPreview() {
        previewTarget = null;
        previewPoint = null;
        previewDirection = null;
    }

    // ============ 结构级参考循环 ============

    public static int bodyRefCycle() {
        return bodyRefCycle;
    }

    /** 滚轮在「中心 / 主轴 X,Y,Z / 基准面 X,Y,Z」之间循环。 */
    public static void cycleBodyRef(final int delta) {
        bodyRefCycle = Math.floorMod(bodyRefCycle + delta, MateTargeting.BODY_REF_COUNT);
    }

    public static void resetBodyRefCycle() {
        bodyRefCycle = 0;
    }

    // ============ 自动展开定位 ============

    @Nullable
    public static UUID consumeFocusMate() {
        final UUID out = focusMate;
        focusMate = null;
        return out;
    }

    @Nullable
    public static UUID peekFocusMate() {
        return focusMate;
    }

    // ============ 服务端回执 ============

    public static void onFeedback(final MateNetworking.MateFeedbackPayload.Status status,
                                  final String message, @Nullable final UUID mateId,
                                  final List<MateRef> newPending, final boolean openGui) {
        lastFeedbackTick = net.minecraft.client.Minecraft.getInstance().gui.getGuiTicks();
        switch (status) {
            case MODE_ON -> {
                setMode(true);
                // 进入模式必须给可见反馈：否则玩家按了 Y、又恰好没看向任何结构，
                // 屏幕上什么都不会变，根本分不清是「没进入」还是「进入了但没目标」。
                notifyPlayer(Component.translatable("mate.prompt.mode_on"));
            }
            case MODE_OFF -> {
                setMode(false);
                notifyPlayer(Component.translatable("mate.prompt.mode_off"));
            }
            case PENDING -> {
                pending = List.copyOf(newPending);
                notifyPlayer(Component.translatable("mate.prompt.pending", newPending.size()));
            }
            case CREATED -> {
                pending = List.of();
                clearPreview();
                focusMate = mateId;
                if (openGui) {
                    focusSidebar(mateId);
                }
            }
            case REJECTED -> {
                pending = List.copyOf(newPending);
                notifyPlayer(Component.translatable("mate.rejected.prefix")
                        .append(Component.translatable(message == null || message.isEmpty()
                                ? "mate.error.internal" : message)));
            }
            case UPDATED -> {
                // 改值/改类型成功：不弹界面，保持玩家原来的操作节奏
            }
        }
    }

    private static void notifyPlayer(final Component message) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            // 用 action bar（true）：配合模式的提示不该刷满聊天框
            mc.player.displayClientMessage(message, true);
        }
    }

    /**
     * 让常驻边栏展开并滚动到刚建立的这条配合。
     *
     * <p>边栏是常驻的，所以这里只是「定位」，不是「打开界面」——不需要换屏，也就不会再出现
     * 被别的界面顶掉的问题。
     */
    private static void focusSidebar(@Nullable final UUID mateId) {
        final com.ovo.sablestopnow.client.gui.MateTreeScreen panel = sidebar;
        if (panel != null && mateId != null) {
            panel.focusOn(mateId);
        }
    }

    public static int lastFeedbackTick() {
        return lastFeedbackTick;
    }
}
