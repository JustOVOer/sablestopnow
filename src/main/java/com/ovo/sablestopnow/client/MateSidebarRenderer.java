package com.ovo.sablestopnow.client;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.client.gui.MateTreeScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.jetbrains.annotations.Nullable;

/**
 * 配合模式右侧边栏的绘制入口 —— 和 {@link StaffControlHud} / {@link StaffEnhanceHud} 一样，
 * 走 {@code RenderGuiEvent.Post}，是<b>HUD 覆盖层</b>而不是 Screen。
 *
 * <h2>为什么必须是覆盖层</h2>
 * 边栏是「常驻」的：进入配合模式就一直显示，直到退出。而 {@code Screen} 天生是<b>屏幕栈上的独占界面</b>——
 * 实测把它做成 Screen 后，按 Y 打开会在 18ms 内被 {@code removed()} 两次：不是我们的代码关的
 * （没走 onClose、没走 keyPressed、没有异常），而是 mod 生态里别处调用了 {@code setScreen(其它界面)}
 * 把它顶掉了。HUD 覆盖层不占 screen 栈，谁也顶不掉它。
 *
 * <p>附带的好处：没有 Screen 就不再有「开屏期间原版不更新 KeyMapping」的限制，
 * Y / Tab 可以回到最普通的 {@code KeyMapping} 上升沿判定，不必再按按键码比对、也不用闩锁和静默期。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT)
public final class MateSidebarRenderer {

    private MateSidebarRenderer() {
    }

    @SubscribeEvent
    public static void onRenderGui(final RenderGuiEvent.Post event) {
        final MateTreeScreen panel = MateClientState.sidebar();
        if (panel == null) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) {
            // 别的界面（暂停菜单、设置等）开着时不画：那是它们的场子
            return;
        }
        final GuiGraphics graphics = event.getGuiGraphics();
        panel.prepareForOverlay(mc, graphics.guiWidth(), graphics.guiHeight());
        panel.render(graphics, scaledMouseX(mc, graphics), scaledMouseY(mc, graphics), 0.0f);
    }

    /** 光标在 GUI 缩放坐标系下的 X（与原版 {@code Gui} 的算法一致）。 */
    public static int scaledMouseX(final Minecraft mc, @Nullable final GuiGraphics graphics) {
        final int guiWidth = graphics != null ? graphics.guiWidth() : mc.getWindow().getGuiScaledWidth();
        return (int) (mc.mouseHandler.xpos() * guiWidth / (double) mc.getWindow().getScreenWidth());
    }

    /** 光标在 GUI 缩放坐标系下的 Y。 */
    public static int scaledMouseY(final Minecraft mc, @Nullable final GuiGraphics graphics) {
        final int guiHeight = graphics != null ? graphics.guiHeight() : mc.getWindow().getGuiScaledHeight();
        return (int) (mc.mouseHandler.ypos() * guiHeight / (double) mc.getWindow().getScreenHeight());
    }
}
