package com.ovo.sablestopnow.network;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.sketch.Sketch;
import com.ovo.sablestopnow.sketch.SketchData;
import com.ovo.sablestopnow.sketch.Solid;
import com.ovo.sablestopnow.sketch.SolidClientState;
import com.ovo.sablestopnow.sketch.SolidData;

import foundry.veil.api.network.VeilPacketManager;
import foundry.veil.api.network.handler.PacketContext;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 拉伸实体的网络：客户端提交/删除，服务端校验后广播全量。
 *
 * <p>结构照抄 {@link SketchNetworking}（它又照抄 {@code ForceNetworking}）：
 * 一个 {@code VeilPacketManager} 子通道、C2S 命令 + S2C 全量同步、
 * 包体直接传 {@link CompoundTag} 并复用存档的序列化器。
 *
 * <h2>服务端必须校验的三件事</h2>
 * <ol>
 *   <li><b>解码得出来</b>（坏包 / 旧客户端直接丢）；</li>
 *   <li><b>引用的草图确实存在，且属于同一个结构</b> —— 否则可以拿别人的草图拉出一个实体，
 *       或者伪造一个 {@code body} 把实体挂到不属于自己的结构上；</li>
 *   <li><b>已有实体的"所属结构 + 引用草图"不许改</b> —— 只允许改深度/方式。
 *       不然一次提交就能把实体挪到别的结构上。</li>
 * </ol>
 */
public final class SolidNetworking {

    private static final VeilPacketManager MANAGER =
            VeilPacketManager.create(SablestopNow.MOD_ID, "solid");

    private SolidNetworking() {
    }

    public static void init() {
        MANAGER.registerServerbound(SaveSolidPayload.TYPE, SaveSolidPayload.CODEC,
                (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(RemoveSolidPayload.TYPE, RemoveSolidPayload.CODEC,
                (payload, context) -> payload.handle(context));
        MANAGER.registerClientbound(SyncSolidsPayload.TYPE, SyncSolidsPayload.CODEC,
                (payload, context) -> payload.handle(context));
    }

    // ============ 客户端 → 服务端 ============

    /** 提交一个实体（新建或改深度）。服务端会**重新解码**再存：客户端的字节流不可信。 */
    public record SaveSolidPayload(CompoundTag solid) implements CustomPacketPayload {

        public static final Type<SaveSolidPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "solid_save"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SaveSolidPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.COMPOUND_TAG, SaveSolidPayload::solid,
                        SaveSolidPayload::new);

        private void handle(final PacketContext context) {
            if (!(context.player() instanceof final ServerPlayer player)
                    || !(context.level() instanceof final ServerLevel level)) {
                return;
            }
            final Solid solid = SolidData.readSolid(this.solid);
            if (solid == null) {
                return;
            }
            final SolidData data = SolidData.get(level);
            final Solid existing = data.all().get(solid.id());
            // 已有实体：只允许改参数，不许换结构、不许换引用的草图
            if (existing != null && (!existing.body().equals(solid.body())
                    || !existing.sketchId().equals(solid.sketchId()))) {
                return;
            }
            // 引用的草图必须存在，且与本实体属于同一个结构
            final Sketch sketch = SketchData.get(level).all().get(solid.sketchId());
            if (sketch == null || !sketch.body().equals(solid.body())) {
                return;
            }
            data.put(solid);
            broadcast(level);
            // ⚠ 存档变了必须**重建碰撞**，否则物理还停在旧形状上 ——
            // 玩家会看到"模型变了但碰撞没变"，这是最难查的一类不一致。
            com.ovo.sablestopnow.server.SolidCollisionService.rebuild(level, solid.body());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 删掉一个实体。 */
    public record RemoveSolidPayload(UUID solidId) implements CustomPacketPayload {

        public static final Type<RemoveSolidPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "solid_remove"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RemoveSolidPayload> CODEC =
                StreamCodec.composite(
                        UUIDUtil.STREAM_CODEC, RemoveSolidPayload::solidId,
                        RemoveSolidPayload::new);

        private void handle(final PacketContext context) {
            if (!(context.level() instanceof final ServerLevel level)) {
                return;
            }
            // ⚠ 先记下它属于哪个结构 —— 删掉之后就查不到了，而重建需要这个 id
            final SolidData data = SolidData.get(level);
            final Solid existing = data.all().get(this.solidId);
            final java.util.UUID body = existing == null ? null : existing.body();
            if (data.remove(this.solidId)) {
                broadcast(level);
                if (body != null) {
                    com.ovo.sablestopnow.server.SolidCollisionService.rebuild(level, body);
                }
            }
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ============ 服务端 → 客户端 ============

    /** 某个维度的全部实体（整份 {@code SolidData} 的 NBT）。 */
    public record SyncSolidsPayload(ResourceLocation dimension, CompoundTag data)
            implements CustomPacketPayload {

        public static final Type<SyncSolidsPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "solid_sync"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SyncSolidsPayload> CODEC =
                StreamCodec.composite(
                        ResourceLocation.STREAM_CODEC, SyncSolidsPayload::dimension,
                        ByteBufCodecs.COMPOUND_TAG, SyncSolidsPayload::data,
                        SyncSolidsPayload::new);

        private void handle(final PacketContext context) {
            SolidClientState.accept(this.dimension, this.data);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ============ 发送 ============

    /** 客户端提交一个实体（新建或改深度）。编码走 {@link SolidData#writeSolid}。 */
    public static void sendSave(final Solid solid) {
        VeilPacketManager.server().sendPacket(new SaveSolidPayload(SolidData.writeSolid(solid)));
    }

    /** 客户端请求删除一个实体。 */
    public static void sendRemove(final UUID solidId) {
        VeilPacketManager.server().sendPacket(new RemoveSolidPayload(solidId));
    }

    private static SyncSolidsPayload snapshot(final ServerLevel level) {
        return new SyncSolidsPayload(level.dimension().location(),
                SolidData.get(level).save(new CompoundTag(), level.registryAccess()));
    }

    /** 把某个维度的实体广播给该维度的所有人。 */
    public static void broadcast(final ServerLevel level) {
        final SyncSolidsPayload payload = snapshot(level);
        for (final ServerPlayer player : level.players()) {
            VeilPacketManager.player(player).sendPacket(payload);
        }
    }

    /** 玩家登录时把各维度的实体都发给他。 */
    public static void sendAllTo(final ServerPlayer player) {
        for (final ServerLevel level : player.server.getAllLevels()) {
            VeilPacketManager.player(player).sendPacket(snapshot(level));
        }
    }

    /** 玩家换维度时把当前维度的发一遍。 */
    public static void sendCurrentTo(final ServerPlayer player) {
        if (player.level() instanceof final ServerLevel level) {
            VeilPacketManager.player(player).sendPacket(snapshot(level));
        }
    }
}
