package com.ovo.sablestopnow.client.gui.lib;

import com.ovo.sablestopnow.SablestopNow;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 库的绘制入口：在 HUD 之后统一画所有覆盖层。
 *
 * <p>用 {@code RenderGuiEvent.Post} 而不是 Screen：覆盖层不占屏幕栈，别的模组
 * {@code setScreen} 顶不掉它，也不暂停游戏（配合边栏的历史教训）。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT)
public final class UiRenderHook {

    private UiRenderHook() {
    }

    @SubscribeEvent
    public static void onRenderGui(final RenderGuiEvent.Post event) {
        try {
            Ui.render(event.getGuiGraphics());
        } catch (final Throwable t) {
            SablestopNow.LOGGER.error("UI library render failed", t);
        }
    }
}
