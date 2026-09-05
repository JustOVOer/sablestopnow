package com.ovo.sablestopnow.client;

import com.ovo.sablestopnow.SablestopNow;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 客户端事件接线：每 tick 驱动 {@link StaffEnhanceClientHandler} 状态清理。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT)
public final class StaffEnhanceClientEvents {

    private StaffEnhanceClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(final ClientTickEvent.Post event) {
        StaffEnhanceClientHandler.tick();
    }
}
