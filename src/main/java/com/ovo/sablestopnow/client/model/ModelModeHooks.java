package com.ovo.sablestopnow.client.model;

import com.ovo.sablestopnow.SablestopNow;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 模型模式的每 tick 驱动（键位、玩家冻结、相机平滑）。
 *
 * <p>单独一个订阅者而不是塞进 {@code StaffEnhanceClientEvents}：模型模式是一个独立的功能面，
 * 出问题时能一眼看出是谁在 tick。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT)
public final class ModelModeHooks {

    private ModelModeHooks() {
    }

    @SubscribeEvent
    public static void onClientTick(final ClientTickEvent.Post event) {
        try {
            ModelMode.tick();
        } catch (final Throwable t) {
            SablestopNow.LOGGER.error("model mode tick failed", t);
            ModelMode.reset();
        }
    }
}
