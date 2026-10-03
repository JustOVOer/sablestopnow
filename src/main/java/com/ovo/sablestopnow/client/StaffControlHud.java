package com.ovo.sablestopnow.client;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.SablestopNowConfig;
import com.ovo.sablestopnow.client.gui.lib.Layouts;
import com.ovo.sablestopnow.client.gui.lib.Ui;
import com.ovo.sablestopnow.client.gui.lib.UiDock;
import com.ovo.sablestopnow.client.gui.lib.UiLayer;
import com.ovo.sablestopnow.client.hud.BodyInfoHudView;
import com.ovo.sablestopnow.client.hud.StaffControlHudView;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 新控制逻辑 HUD 的接线与生命周期。
 *
 * <p>画面本身全部改由自研 GUI 库画：左上「模式 + 功能清单 + 选框 + 描述 + 提示」是一个
 * {@link UiLayer}（{@link StaffControlHudView}），右上「瞄准结构信息」是另一个
 * （{@link BodyInfoHudView}）。这里只做三件事：判断该不该显示、开关层、把
 * {@code StaffEnhanceClientHandler} 的四个通知转发给界面。
 *
 * <p><b>为什么是覆盖层而不是自己画</b>：旧实现每帧在 {@code RenderGuiEvent} 里用
 * {@code GuiGraphics} 裸画（fill / drawString 一大堆），字号、配色、避让都各写一套。
 * 改成库的控件之后：配色统一取 {@link com.ovo.sablestopnow.client.gui.lib.UiTheme}（与配置界面同源）、
 * 文字统一 0.75 缩放、动画统一走 {@link com.ovo.sablestopnow.client.gui.lib.Anim}
 * （受配置里的「界面动画」总开关控制）、位置统一交给 {@link UiDock} 避让。
 *
 * <p><b>它是纯显示</b>：两个层都关掉 {@code consumeInput}/{@code consumeKeys}，并且对命中测试透明，
 * 输入依旧完全由 {@code StaffEnhanceClientHandler} 负责 —— 否则滚轮换功能、左键应用功能都会被吞掉。
 *
 * <p><b>显示条件（与旧实现一致）</b>：增强总开关开、手杖在手上、没有别的 Screen 开着；
 * 左上角另外要求「新控制逻辑」开启且不在配合模式（配合模式有自己的一套界面，避免两套模式横幅叠在一起）。
 * 另外，被模型模式的边栏压住时整块会自己淡出（见 {@link StaffControlHudView} 的避让）。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT)
public final class StaffControlHud {

    private static final String LAYER_CONTROL = "staff.control.hud";
    private static final String LAYER_INFO = "staff.body.info";
    private static final int MARGIN = 6;

    private static UiLayer controlLayer;
    private static StaffControlHudView controlView;
    private static UiLayer infoLayer;

    private StaffControlHud() {
    }

    // ------------------------------------------------------------------
    // 由 StaffEnhanceClientHandler 调用的触发器（公共入口签名保持不变）
    // ------------------------------------------------------------------

    public static void notifyModeChanged() {
        view().onModeChanged();
    }

    public static void notifySelectionChanged() {
        view().onSelectionChanged();
    }

    public static void notifyApplied(final boolean sustained) {
        view().onApplied(sustained);
    }

    public static void notifySustainedEnded() {
        view().onSustainedEnded();
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderGui(final RenderGuiEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.screen != null
                || !SablestopNowConfig.isStaffEnhanceEnabled()
                || !StaffEnhanceClientHandler.isEnabled()
                || !PhysicsStaffItem.isHolding(player)) {
            hideAll();
            return;
        }
        // 配合模式（MATE）仍然不吃这份左上角 HUD：它的模式与功能由配合那一套界面承担
        final boolean mate = StaffEnhanceClientHandler.newControlMode() == StaffControl.Mode.MATE;
        setOpen(controlLayer(), SablestopNowConfig.isNewControlScheme() && !mate);
        setOpen(infoLayer(), SablestopNowConfig.isShowBodyInfo());
    }

    /**
     * 开关一个层。
     *
     * <p>关闭时只是把它从库里摘掉，界面实例仍然留着 —— 这样「切物品栏再切回手杖」不会把动画状态
     * （模式滑出的进度、光条相位）重置成初值。
     */
    private static void setOpen(final UiLayer layer, final boolean open) {
        if (open) {
            if (Ui.layer(layer.name) == null) {
                Ui.open(layer);
            }
        } else {
            Ui.close(layer);
        }
    }

    private static void hideAll() {
        if (controlLayer != null) {
            Ui.close(controlLayer);
        }
        if (infoLayer != null) {
            Ui.close(infoLayer);
        }
    }

    private static StaffControlHudView view() {
        controlLayer();
        return controlView;
    }

    private static UiLayer controlLayer() {
        if (controlLayer == null) {
            controlLayer = displayLayer(LAYER_CONTROL, UiDock.Corner.TOP_LEFT);
            controlView = new StaffControlHudView();
            controlLayer.root.add(controlView);
        }
        return controlLayer;
    }

    private static UiLayer infoLayer() {
        if (infoLayer == null) {
            infoLayer = displayLayer(LAYER_INFO, UiDock.Corner.TOP_RIGHT);
            infoLayer.root.add(new BodyInfoHudView());
        }
        return infoLayer;
    }

    /**
     * 造一个「只看不摸」的覆盖层。
     *
     * <p>{@code consumeInput}/{@code consumeKeys} 必须关：输入仍由 {@code StaffEnhanceClientHandler}
     * 负责，HUD 一旦吞掉鼠标或键盘，滚轮换功能、左键应用、Ctrl 切多选就全废了。
     * {@code pointerTransparent} 也必须开：模型模式会遍历所有可见层做命中测试来判断
     * 「鼠标是不是压在面板上」，显示层不该参与这件事。
     */
    private static UiLayer displayLayer(final String name, final UiDock.Corner corner) {
        final UiLayer layer = UiLayer.of(name).size(0, 0);
        layer.consumeInput = false;
        layer.consumeKeys = false;
        layer.reserveSpace = false;
        layer.hud = true;
        layer.closeOnEscape = false;
        layer.root.pointerTransparent = true;
        layer.root.layout = Layouts.hud(corner, MARGIN);
        return layer;
    }
}
