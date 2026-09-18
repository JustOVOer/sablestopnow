package com.ovo.sablestopnow.client;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.network.MateNetworking;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 配合模式的输入。
 *
 * <p>配合模式是本模组控制体系里的<b>一等模式</b>，与普通 / 多选 / 整组拖拽完全同级：
 * 状态由 {@link MateClientState} 持有，画面由 {@link MateSidebarRenderer} 作为 HUD 覆盖层绘制，
 * 输入走与其它模式相同的两条既有通道 —— {@code StaffEnhanceClientHandler.handleMouse/handleScroll}
 * 与 {@code KeyMapping}。
 *
 * <p>键位：<b>Y</b> 进入/退出配合模式，<b>Tab</b> 释放/锁回鼠标（两者都可在原版按键设置里改）。
 * 模式内：<b>右键</b>选取预览中的那一端参考，<b>Shift+右键</b>撤销上一端，
 * <b>按住 Ctrl</b> 切到结构级参考并由<b>滚轮</b>循环。
 *
 * <p>按键判定用 {@link StaffEnhanceClientHandler#justPressed}（全模组统一的上升沿判定），
 * 而不是裸 {@code consumeClick()}：原版在 GLFW 的 REPEAT 事件里也会调用 {@code KeyMapping.click()}，
 * 长按会反复触发。
 */
public final class MateModeInput {

    private static final int MOUSE_RIGHT = 1;
    private static final int MOUSE_LEFT = 0;

    /**
     * 「手杖不在手上」连续多少 tick 才真的退出配合模式。
     *
     * <p>去抖只是保险：{@code PhysicsStaffItem.isHolding} 本身是纯主手/副手检查、不会抖动，
     * 但换手/丢包这类中间态多等几 tick 没有任何代价。
     */
    private static final int STAFF_LOST_TICKS = 10;

    private static int staffMissingTicks;

    private MateModeInput() {
    }

    // ============ 每 tick ============

    public static void tick() {
        // 这三个键必须每 tick 都轮询：justPressed 靠「上一 tick 是否按下」判上升沿，
        // 中途跳过一次就会把长按误判成新的一次按下。
        final boolean mateKey = StaffEnhanceClientHandler.justPressed(StaffKeyMappings.MATE_MODE);
        final boolean treeKey = StaffEnhanceClientHandler.justPressed(StaffKeyMappings.MATE_TREE);
        final boolean mouseKey = StaffEnhanceClientHandler.justPressed(StaffKeyMappings.MATE_MOUSE);

        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (MateClientState.inMode()) {
                MateClientState.setMode(false);
            }
            return;
        }

        if (!PhysicsStaffItem.isHolding(mc.player)) {
            staffMissingTicks++;
            if (MateClientState.inMode() && staffMissingTicks >= STAFF_LOST_TICKS) {
                SablestopNow.LOGGER.info("[mate] staff gone for {} ticks -> exit mate mode", staffMissingTicks);
                setMode(false);
            }
            return;
        }
        staffMissingTicks = 0;

        if (mateKey) {
            setMode(!MateClientState.inMode());
            return;
        }
        // M 是「进入配合模式」的别名：树形界面已经变成常驻边栏，不再是独立弹窗
        if (treeKey && !MateClientState.inMode()) {
            setMode(true);
            return;
        }
        if (mouseKey && MateClientState.inMode()) {
            MateClientState.toggleCursorLock();
        }
    }

    private static void setMode(final boolean enable) {
        MateClientState.setMode(enable);
        MateClientState.resetBodyRefCycle();
        if (!enable) {
            MateClientState.clearPreview();
        }
        VeilPacketManager.server().sendPacket(new MateNetworking.MateModePayload(enable));
    }

    // ============ 鼠标 ============

    /** @return true 表示吞掉这次鼠标事件（不让原版/其它处理器看到） */
    public static boolean handleMouse(final int button, final int action, final int modifiers) {
        if (!MateClientState.inMode()) {
            return false;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            // 别的界面开着（暂停菜单等）时不插手
            return false;
        }

        final com.ovo.sablestopnow.client.gui.MateTreeScreen panel = MateClientState.sidebar();
        final int mx = MateSidebarRenderer.scaledMouseX(mc, null);
        final int my = MateSidebarRenderer.scaledMouseY(mc, null);

        if (!MateClientState.cursorLocked()) {
            // 鼠标已释放：全部点击交给边栏，并且一律吞掉，免得顺手打到方块/实体
            if (action == GLFW.GLFW_PRESS && button == MOUSE_LEFT && panel != null
                    && panel.containsPanel(mx, my)) {
                panel.handleClick(mx, my);
            }
            return true;
        }

        // 鼠标锁定：准星可转、右键选取
        if (action != GLFW.GLFW_PRESS) {
            return true;
        }
        if (button != MOUSE_RIGHT) {
            // 左键/中键在配合模式下禁用
            return true;
        }
        if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
            VeilPacketManager.server().sendPacket(MateNetworking.MatePickPayload.undo());
            return true;
        }
        final com.ovo.sablestopnow.mate.MateRef candidate = MateClientState.previewTarget();
        if (candidate == null) {
            prompt("mate.prompt.no_target");
            return true;
        }
        VeilPacketManager.server().sendPacket(MateNetworking.MatePickPayload.pick(candidate));
        return true;
    }

    public static boolean handleScroll(final double deltaY) {
        if (!MateClientState.inMode()) {
            return false;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return false;
        }
        final com.ovo.sablestopnow.client.gui.MateTreeScreen panel = MateClientState.sidebar();

        if (!MateClientState.cursorLocked()) {
            // 鼠标已释放：滚轮滚边栏列表
            if (panel != null && panel.containsPanel(MateSidebarRenderer.scaledMouseX(mc, null),
                    MateSidebarRenderer.scaledMouseY(mc, null))) {
                panel.scrollBy(deltaY);
            }
            return true;
        }

        // 鼠标锁定：按住结构参考修饰键时用滚轮切「中心/轴/面」，其余情况吞掉（不切物品栏）
        if (StaffKeyMappings.MULTI_SELECT.isDown()) {
            MateClientState.cycleBodyRef(deltaY > 0 ? 1 : -1);
        }
        return true;
    }

    private static void prompt(final String key, final Object... args) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.translatable(key, args), true);
        }
    }
}
