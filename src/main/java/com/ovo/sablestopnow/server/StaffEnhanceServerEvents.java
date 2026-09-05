package com.ovo.sablestopnow.server;

import com.ovo.sablestopnow.SablestopNow;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 服务端事件接线：整组拖拽运行期清理、玩家加入时同步无碰撞状态、服务器停止时清空。
 * 每物理子步的马达驱动由 SablestopNow 构造里通过 SableEventPlatform.INSTANCE.onPhysicsTick 注册
 * （跨平台 API，见 Simulated.init() 同款做法）。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID)
public final class StaffEnhanceServerEvents {

    private StaffEnhanceServerEvents() {
    }

    @SubscribeEvent
    public static void onServerTick(final ServerTickEvent.Post event) {
        StaffEnhanceServer.serverTick();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(final PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            StaffEnhanceServer.sendAllData(player);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(final ServerStoppedEvent event) {
        StaffEnhanceServer.clearAll();
    }
}
