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
        // 暂停步进 / 超速锁定 / 幽灵关节统一在每 tick 收尾维护
        StaffEnhanceServer.serverFeatures(event.getServer());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(final PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            StaffEnhanceServer.sendAllData(player);
            StaffSelectionRegistry.sendAllTo(player);
            // 彩蛋开关状态补发
            foundry.veil.api.network.VeilPacketManager.player(player)
                    .sendPacket(new com.ovo.sablestopnow.network.StaffEnhanceNetworking.SyncSuperliminalPayload(
                            StaffSuperliminalState.isEnabled(player.getUUID())));
            // 「拖拽体对拖拽者幽灵化」的状态补发
            StaffEnhanceServer.sendGhostsTo(player);
        }
    }

    /** 玩家掉线：立刻释放他占用的多选结构并广播（功能2），并丢弃他的快照（功能9）。 */
    @SubscribeEvent
    public static void onPlayerLoggedOut(final PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                && player.getServer() != null) {
            final java.util.Set<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>> affected =
                    StaffSelectionRegistry.releaseAll(player.getUUID());
            StaffSelectionRegistry.broadcast(player.getServer(), affected);
        }
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            StaffSnapshotRegistry.release(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(final ServerStoppedEvent event) {
        StaffEnhanceServer.clearAll();
        StaffSelectionRegistry.clearAll();
        StaffSnapshotRegistry.clearAll();
    }
}
