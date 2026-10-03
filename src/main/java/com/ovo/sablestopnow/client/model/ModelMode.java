package com.ovo.sablestopnow.client.model;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.client.MateClientState;
import com.ovo.sablestopnow.client.MateTargeting;
import com.ovo.sablestopnow.client.StaffEnhanceClientHandler;
import com.ovo.sablestopnow.client.StaffKeyMappings;
import com.ovo.sablestopnow.mate.MateRef;
import com.ovo.sablestopnow.network.MateNetworking;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 模型模式：把每一个 Sable 物理结构当成 SolidWorks 里的一个「模型」来看待与操作。
 *
 * <h2>与旧配合模式的关系</h2>
 * 旧配合模式（Y 键）里的「Tab 释放鼠标」被独立成了这个模式：进入即释放光标、接管相机，
 * 配合只是模式内的一个功能（见 {@link ModelUi}）。所以 Y 键现在是模型模式的总开关，
 * 而 {@link MateClientState} 仍然负责配合数据的持有与同步。
 *
 * <h2>为什么冻结玩家</h2>
 * 相机是绕模型轨道转的（{@link ModelCamera}），玩家在模型模式下不移动、不攻击、不交互，
 * 否则会出现「人在走、视角却在看模型」的撕裂感。冻结用「置零输入 + 关重力 + 锁位置」三件套，
 * 退出时原样恢复（不需要恢复视角：相机只是被临时改写，玩家自己的 yaw/pitch 从没动过）。
 */
public final class ModelMode {

    /** 当前是否处于模型模式。 */
    private static boolean on;
    /** 进入时的玩家位置（冻结用）。 */
    @Nullable
    private static Vec3 frozenPos;
    private static boolean previousNoGravity;

    /** 当前选中的模型（结构 UUID）。 */
    @Nullable
    private static UUID selectedModel;
    /** 本帧鼠标下的参考（预览用）。 */
    @Nullable
    private static MateTargeting.BoxHit hovered;
    @Nullable
    private static MateRef hoveredRef;
    /** 本次功能里已选的参考（配合需要 2 个以上）。 */
    private static final List<MateRef> picked = new ArrayList<>();
    /** 当前功能 id（见 {@link ModelTools}）。 */
    private static String tool = ModelTools.NONE;

    private static int staffMissingTicks;

    /**
     * 模型模式下「光标是否应当保持自由」。
     *
     * <p>为什么要记这个意图：打开任意 Screen（暂停菜单、Ctrl+O 设置）再关掉时，
     * 原版 {@code Minecraft.setScreen(null)} 会无条件把鼠标重新抓回去，
     * 于是模型模式里那套「自由光标点侧栏」的操作会突然失效，玩家得再按一次 Tab。
     * 记住意图之后，{@link #tick()} 每 tick 自愈一次：只要没开 Screen、又该是自由光标，
     * 就把它放出来（而玩家自己按 Tab 锁定的情况不会被破坏）。
     */
    private static boolean freeCursor = true;

    public static boolean freeCursor() {
        return freeCursor;
    }

    public static void setFreeCursor(final boolean value) {
        freeCursor = value;
    }

    private ModelMode() {
    }

    // ============ 开关 ============

    public static boolean isOn() {
        return on;
    }

    public static void toggle() {
        setOn(!on);
    }

    public static void setOn(final boolean value) {
        if (on == value) {
            return;
        }
        on = value;
        final Minecraft mc = Minecraft.getInstance();
        // 服务端要靠 MateModePayload 判断「这个玩家正在配合模式里」，否则 picking 包会被拒
        MateClientState.setMode(value);
        MateClientState.resetBodyRefCycle();
        // 退出世界时 tick 还会走一次 setOn(false)（player == null），此时连接已断，
        // 直接发包会抛 IllegalStateException 刷红字。没有连接就只做本地状态收尾。
        if (mc.getConnection() != null) {
            VeilPacketManager.server().sendPacket(new MateNetworking.MateModePayload(value));
        }
        if (value) {
            // 进入：释放光标（自由光标才能点树状图/左栏），相机切到轨道相机
            if (mc.mouseHandler != null) {
                mc.mouseHandler.releaseMouse();
            }
            ModelCamera.reset(mc);
            freeze(mc);
            picked.clear();
            hovered = null;
            hoveredRef = null;
            tool = ModelTools.NONE;
            prompt("model.prompt.enter");
            freeCursor = true;
            ModelUi.openPanels();
        } else {
            unfreeze(mc);
            if (mc.mouseHandler != null) {
                mc.mouseHandler.grabMouse();
            }
            picked.clear();
            hovered = null;
            hoveredRef = null;
            tool = ModelTools.NONE;
            ModelUi.closePanels();
            prompt("model.prompt.exit");
        }
    }

    // ============ 每 tick ============

    public static void tick() {
        final Minecraft mc = Minecraft.getInstance();
        final LocalPlayer player = mc.player;

        // 这三个键必须每 tick 都轮询：justPressed 靠「上一 tick 是否按下」判上升沿，
        // 中途跳过一次就会把长按误判成新的一次按下（哪怕当前还没进模型模式）。
        final boolean modeKey = StaffEnhanceClientHandler.justPressed(StaffKeyMappings.MATE_MODE);
        final boolean treeKey = StaffEnhanceClientHandler.justPressed(StaffKeyMappings.MATE_TREE);
        final boolean cursorKey = StaffEnhanceClientHandler.justPressed(StaffKeyMappings.MATE_MOUSE);

        if (player == null) {
            if (on) {
                setOn(false);
            }
            return;
        }

        // 手杖不在手上超过 10 tick 自动退出（与旧配合模式同样的去抖）
        if (!PhysicsStaffItem.isHolding(player)) {
            if (on && ++staffMissingTicks >= 10) {
                setOn(false);
            }
            return;
        }
        staffMissingTicks = 0;

        // 键位：Y 开关模型模式；M 打开/关闭右侧树状图；Tab 切换自由光标
        if (modeKey) {
            if (!on) {
                // 需求：模型视图只能从「普通模式」进入（多选/整组拖拽进行中不允许切进来，
                // 否则那套模式的选中队列、拖拽目标会和新界面互相打架）
                final com.ovo.sablestopnow.client.StaffControl.Mode current =
                        StaffEnhanceClientHandler.newControlMode();
                if (current != com.ovo.sablestopnow.client.StaffControl.Mode.NORMAL) {
                    ModelUi.closePanels();
                    prompt("model.prompt.need_normal");
                    return;
                }
            }
            setOn(!on);
            return;
        }
        if (!on) {
            return;
        }
        if (treeKey) {
            ModelUi.toggleTree();
        }
        if (cursorKey) {
            ModelInput.toggleCursor();
        }

        freeze(mc);
        healCursor(mc);
        // 相机平滑与鼠标拖动**按帧**推进（见 ModelRenderer）：客户端 tick 只有 20Hz，
        // 在 20Hz 上采样鼠标位移再插值，手感一定是「一顿一顿」的。
    }

    /** 关掉任意 Screen 之后把「该自由的光标」放回来（见 {@link #freeCursor} 的说明）。 */
    private static void healCursor(final Minecraft mc) {
        if (mc.mouseHandler == null || mc.screen != null) {
            return;
        }
        if (freeCursor && mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.releaseMouse();
        } else if (!freeCursor && !mc.mouseHandler.isMouseGrabbed()) {
            mc.mouseHandler.grabMouse();
        }
    }

    /** 把玩家钉在原地。 */
    private static void freeze(final Minecraft mc) {
        final LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        if (frozenPos == null) {
            frozenPos = player.position();
            previousNoGravity = player.isNoGravity();
        }
        player.setNoGravity(true);
        player.setDeltaMovement(Vec3.ZERO);
        player.setPos(frozenPos.x, frozenPos.y, frozenPos.z);
        player.input.forwardImpulse = 0.0f;
        player.input.leftImpulse = 0.0f;
        player.input.jumping = false;
        player.input.shiftKeyDown = false;
        player.setSprinting(false);
    }

    private static void unfreeze(final Minecraft mc) {
        final LocalPlayer player = mc.player;
        if (player != null && frozenPos != null) {
            player.setNoGravity(previousNoGravity);
            player.setDeltaMovement(Vec3.ZERO);
        }
        frozenPos = null;
    }

    // ============ 选中状态 ============

    @Nullable
    public static UUID selectedModel() {
        return selectedModel;
    }

    public static void selectModel(@Nullable final UUID id) {
        // ⚠ 选中模型**不动相机**。
        // 以前这里会 ModelCamera.focus(id)：在世界里点一下模型，镜头就被重新定距（缩放级别变了），
        // 用户实测反馈「任何时候点击模型时不要缩放移动镜头」。
        // 想「把选中的模型摆到视野正中」有单独的动作（视图页的「居中」/ 右键菜单），
        // 而且那个动作也不改距离。
        selectedModel = id;
    }

    @Nullable
    public static MateTargeting.BoxHit hovered() {
        return hovered;
    }

    @Nullable
    public static MateRef hoveredRef() {
        return hoveredRef;
    }

    public static void setHovered(@Nullable final MateTargeting.BoxHit hit, @Nullable final MateRef ref) {
        hovered = hit;
        hoveredRef = ref;
    }

    public static List<MateRef> picked() {
        return picked;
    }

    public static String tool() {
        return tool;
    }

    public static void setTool(final String toolId) {
        tool = toolId == null ? ModelTools.NONE : toolId;
        // 诊断：工具切换必须能被证实（用户反馈「点了功能键没反应」，而日志里世界点击全是 tool=none，
        // 到底是按钮没跑到、还是切了又被重置，只有这行能分辨）
        SablestopNow.LOGGER.info("[model-diag] tool -> {} (picks cleared={})", tool, picked.size());
    }

    /** 选中一个参考（配合等功能用）。 */
    public static void pick(final MateRef ref) {
        if (ref == null) {
            return;
        }
        picked.add(ref);
        // 旧配合链路：服务端是权威，待选列表以回执为准
        VeilPacketManager.server().sendPacket(MateNetworking.MatePickPayload.pick(ref));
    }

    public static void undoPick() {
        if (picked.isEmpty()) {
            return;
        }
        picked.remove(picked.size() - 1);
        VeilPacketManager.server().sendPacket(MateNetworking.MatePickPayload.undo());
    }

    public static void clearPicks() {
        picked.clear();
    }

    /** 客户端算出的、当前鼠标下的候选参考（每帧由 {@link ModelInput} 刷新）。 */
    public static void refreshHover(final Minecraft mc) {
        if (!on || mc.level == null) {
            hovered = null;
            hoveredRef = null;
            return;
        }
        if (mc.screen != null) {
            return;
        }
        // 草图模式自己处理悬停（选面高亮 / 落点预览）。但**不提前 return** ——
        // 还要继续做下面那套面拾取，因为玩家也可以用「结构上的某个方块面」当草图平面
        // （选面阶段点空时就会走面这条路）。提前 return 会让 hovered 恒为 null，
        // 于是"贴着面建草图"永远选不中任何东西。
        if (com.ovo.sablestopnow.client.SketchClient.active()) {
            com.ovo.sablestopnow.client.SketchClient.hover(mc);
        }
        if (ModelUi.pointerOverPanel()) {
            hovered = null;
            hoveredRef = null;
            return;
        }
        final Vec3 from = ModelCamera.position();
        final Vec3 dir = ModelCamera.rayDirection(mc);
        final MateTargeting.BoxHit hit = MateTargeting.raycastFrom(from, dir, 128.0);
        diagHover(mc, from, dir, hit);
        if (hit == null) {
            hovered = null;
            hoveredRef = null;
            return;
        }
        hovered = hit;
        hoveredRef = MateTargeting.boxCandidate(hit.body(), hit);
    }

    // ============ 临时诊断（定位「选不中 / 按键没反应」用，问题解决后删） ============

    @Nullable
    private static String lastHoverState;
    private static long lastDiagMs;

    private static void diagHover(final Minecraft mc, final Vec3 from, final Vec3 dir,
                                  @Nullable final MateTargeting.BoxHit hit) {
        final String state = hit == null
                ? "MISS"
                : "HIT " + shortId(hit.body().getUniqueId()) + "@" + hit.baseBlock() + "/" + hit.baseFace();
        if (state.equals(lastHoverState)) {
            return;
        }
        final long now = System.currentTimeMillis();
        if (now - lastDiagMs < 700L) {
            return;
        }
        lastDiagMs = now;
        lastHoverState = state;
        SablestopNow.LOGGER.info(
                "[model-diag] hover={} cam=({},{},{}) yaw={} pitch={} dist={} ray=({},{},{}) "
                        + "mouse=({},{}) gui={}x{} overPanel={} tree[{}]",
                state,
                fmt(from.x), fmt(from.y), fmt(from.z),
                fmt(ModelCamera.yaw()), fmt(ModelCamera.pitch()), fmt(ModelCamera.distance()),
                fmt(dir.x), fmt(dir.y), fmt(dir.z),
                com.ovo.sablestopnow.client.gui.lib.Ui.mouseX(),
                com.ovo.sablestopnow.client.gui.lib.Ui.mouseY(),
                mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight(),
                ModelUi.pointerOverPanel(),
                ModelUi.treeStats());
    }

    /** 左键点击的结果（由 {@link ModelInput} 调用）。 */
    public static void diagClick(final String what) {
        SablestopNow.LOGGER.info("[model-diag] click -> {}", what);
    }

    private static String fmt(final double value) {
        return String.format("%.2f", value);
    }

    private static String shortId(final java.util.UUID id) {
        return id.toString().substring(0, 8);
    }

    public static void prompt(final String key, final Object... args) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(key, args), true);
        }
    }

    /** 断线/切维度时的兜底复位。 */
    public static void reset() {
        if (on) {
            final Minecraft mc = Minecraft.getInstance();
            unfreeze(mc);
            if (mc.mouseHandler != null && !mc.mouseHandler.isMouseGrabbed()) {
                mc.mouseHandler.grabMouse();
            }
            ModelUi.closePanels();
        }
        on = false;
        frozenPos = null;
        selectedModel = null;
        hovered = null;
        hoveredRef = null;
        picked.clear();
        tool = ModelTools.NONE;
        freeCursor = true;
        ModelCamera.forget();
        SablestopNow.LOGGER.debug("model mode reset");
    }
}
