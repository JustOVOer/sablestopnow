package com.ovo.sablestopnow.network;

import com.ovo.sablestopnow.SablestopNow;
import foundry.veil.api.network.VeilPacketManager;
import foundry.veil.api.network.handler.PacketContext;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 「体素碰撞体」实验的触发包（客户端 → 服务端）。
 *
 * <h2>为什么必须走包，不能纯客户端做</h2>
 * 物理在**服务端**（{@code RapierPhysicsPipeline} 挂在 {@code ServerLevel} 上），
 * 而验证按钮在 debug 标签页里（客户端 GUI）。所以"点一下按钮"必须变成一次服务端调用。
 *
 * <p>⚠ 这是**实验**，不是生产路径：它只在选中的子结构上挂一个方块大小的装置，
 * 用来回答"体素碰撞体这条路到底通不通"。通了再接生产。
 */
public final class VoxelColliderTestNetworking {

    private static final VeilPacketManager MANAGER =
            VeilPacketManager.create(SablestopNow.MOD_ID, "voxel_collider_test");

    private VoxelColliderTestNetworking() {
    }

    public static void init() {
        MANAGER.registerServerbound(VoxelColliderTestPayload.TYPE, VoxelColliderTestPayload.CODEC,
                (payload, context) -> payload.handle(context));
    }

    /** 客户端：在当前选中的结构上跑一次体素碰撞体实验。 */
    public static void send(final UUID body) {
        VeilPacketManager.server().sendPacket(new VoxelColliderTestPayload(body));
    }

    /** 客户端 → 服务端：只带一个"在哪个结构上做"。 */
    public record VoxelColliderTestPayload(UUID body) implements CustomPacketPayload {

        public static final Type<VoxelColliderTestPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID,
                        "voxel_collider_test"));

        public static final StreamCodec<RegistryFriendlyByteBuf, VoxelColliderTestPayload> CODEC =
                StreamCodec.composite(
                        UUIDUtil.STREAM_CODEC, VoxelColliderTestPayload::body,
                        VoxelColliderTestPayload::new);

        private void handle(final PacketContext context) {
            if (!(context.player() instanceof final ServerPlayer player)
                    || !(context.level() instanceof final ServerLevel level)) {
                return;
            }
            com.ovo.sablestopnow.server.VoxelColliderTestService.run(level, this.body, player);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
