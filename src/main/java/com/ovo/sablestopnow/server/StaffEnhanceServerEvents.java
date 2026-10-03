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
        // MSPT 观测：**只在超阈值时记一条**（带限流），正常情况一条都不打。
        // 这是我们判断"卡顿是不是物理造成的"的唯一客观依据 —— 以前只能靠用户报"十几万"。
        com.ovo.sablestopnow.server.PhysicsDiag.tick(event.getServer(),
                event.getServer().getAverageTickTimeNanos());
        StaffEnhanceServer.serverTick();
        // Strategy-A watch: log both bodies' Y (see VoxelCollisionService.logABodies)
        if (event.getServer().getTickCount() % 20 == 0) {
            com.ovo.sablestopnow.server.VoxelCollisionService.logABodies(event.getServer().overworld());
        }
        // 暂停步进 / 超速锁定 / 幽灵关节统一在每 tick 收尾维护
        StaffEnhanceServer.serverFeatures(event.getServer());
        // 施力点的清理是「结构没了 → 记录也该没」：每秒扫一次就够（施力点是个位数级），
        // 不必塞进每物理子步的热路径里。
        if (event.getServer().getTickCount() % 20 == 0) {
            for (final net.minecraft.server.level.ServerLevel level : event.getServer().getAllLevels()) {
                if (com.ovo.sablestopnow.force.ForcePointData.get(level).all().isEmpty()) {
                    continue;
                }
                final var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
                if (container instanceof final dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer server) {
                    ForcePointService.prune(server, level);
                }
            }
        }
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
            // 配合系统：补发所有维度的配合表（客户端据此画树形界面）
            com.ovo.sablestopnow.network.MateNetworking.sendAllTo(player);
            // 可控施力点与耦合（比例/齿轮）同样是「服务端权威 + 全量同步」：
            // 不补发的话，重启或重连之后客户端的树里会空着，直到玩家手动改一次才出现 ——
            // 那会被当成「存档丢了」。
            com.ovo.sablestopnow.network.ForceNetworking.sendAllTo(player);
            com.ovo.sablestopnow.network.CouplingNetworking.sendAllTo(player);
            // 草图同理：不补发的话，重连之后设计树里那一栏会空着，直到玩家手动改一次才出现。
            com.ovo.sablestopnow.network.SketchNetworking.sendAllTo(player);
            // 拉伸实体同理。实体**引用**草图，所以必须在草图之后发 ——
            // 客户端要拿到草图才能把实体的盒子算出来。
            com.ovo.sablestopnow.network.SolidNetworking.sendAllTo(player);
        }
    }

    /**
     * 换维度时补发该维度的施力点/耦合。
     *
     * <p>配合表是「一次补发所有维度」，而施力点与耦合按维度存，
     * 玩家跨维度后如果不补发，客户端就还在显示上一个维度的东西。
     */
    @SubscribeEvent
    public static void onPlayerChangedDimension(final PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof final net.minecraft.server.level.ServerPlayer player) {
            com.ovo.sablestopnow.network.ForceNetworking.sendCurrentTo(player);
            com.ovo.sablestopnow.network.CouplingNetworking.broadcast((net.minecraft.server.level.ServerLevel)
                    player.level());
            com.ovo.sablestopnow.network.SketchNetworking.sendCurrentTo(player);
            com.ovo.sablestopnow.network.SolidNetworking.sendCurrentTo(player);
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
            // 配合模式是「持杖时的临时状态」，掉线即清，避免留下选了半截的待选
            MateSelectionRegistry.release(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(final ServerStoppedEvent event) {
        com.ovo.sablestopnow.server.HullTrace.close();
        StaffEnhanceServer.clearAll();
        StaffSelectionRegistry.clearAll();
        StaffSnapshotRegistry.clearAll();
        MateRegistry.clearAll();
        CouplingRegistry.clear();
        ModelMoveService.clear();
        // 实体碰撞的刚体：服务器停了就挨个维度拆掉。
        // ⚠ 不拆的话静态表里会留下已经失效的 handle，重开一个存档时
        // 下一次重建会拿着旧 handle 去 remove，后果不可预期。
        for (final net.minecraft.server.level.ServerLevel level
                : event.getServer().getAllLevels()) {
            SolidCollisionService.clearDimension(level);
        }
    }
}
