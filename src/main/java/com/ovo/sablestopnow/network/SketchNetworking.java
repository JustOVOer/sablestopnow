package com.ovo.sablestopnow.network;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.sketch.Sketch;
import com.ovo.sablestopnow.sketch.SketchClientState;
import com.ovo.sablestopnow.sketch.SketchData;

import foundry.veil.api.network.VeilPacketManager;
import foundry.veil.api.network.handler.PacketContext;
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
 * 草图的网络：客户端提交/删除，服务端校验后广播全量。
 *
 * <p>结构照抄 {@link ForceNetworking}：一个 {@code VeilPacketManager} 子通道、
 * C2S 命令 + S2C 全量同步。草图的数量是玩家手画的个位数到几十，全量包小得可以忽略，
 * 换来的是「客户端永远不会漂移」。
 *
 * <h2>只用一个序列化器</h2>
 * 包体直接传 {@link CompoundTag}，读写都调 {@link SketchData#writeSketch} /
 * {@link SketchData#readSketch} —— 与存档<b>同一份实现</b>。
 * 如果网络另写一份，迟早漂移成「存档读得回、联机读不回」这种最难受的 bug。
 *
 * <h2>为什么整张草图一起发</h2>
 * 不做「加一条线」「改一个约束」这种细粒度增量：草图的权威表示就是
 * 「变量数组 + 图元 + 约束」这个整体，求解又是全局的（改一条约束会让所有变量动），
 * 所以任何一次编辑本来就等于整张草图变了。细粒度包只会带来状态机复杂度，没有收益。
 */
public final class SketchNetworking {

    private static final VeilPacketManager MANAGER =
            VeilPacketManager.create(SablestopNow.MOD_ID, "sketch");

    private SketchNetworking() {
    }

    public static void init() {
        MANAGER.registerServerbound(SaveSketchPayload.TYPE, SaveSketchPayload.CODEC,
                (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(RemoveSketchPayload.TYPE, RemoveSketchPayload.CODEC,
                (payload, context) -> payload.handle(context));
        MANAGER.registerClientbound(SyncSketchesPayload.TYPE, SyncSketchesPayload.CODEC,
                (payload, context) -> payload.handle(context));
    }

    // ============ 客户端 → 服务端 ============

    /**
     * 提交一张草图（新建或覆盖）。
     *
     * <p>服务端会**重新解码**再存：客户端的字节流不可信，解码失败就整包丢弃。
     */
    public record SaveSketchPayload(CompoundTag sketch) implements CustomPacketPayload {

        public static final Type<SaveSketchPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "sketch_save"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SaveSketchPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.COMPOUND_TAG, SaveSketchPayload::sketch,
                        SaveSketchPayload::new);

        private void handle(final PacketContext context) {
            // Veil 的 PacketContext#player() 返回的是 Player，需要自己收窄到 ServerPlayer。
            if (!(context.player() instanceof final ServerPlayer player)
                    || !(context.level() instanceof final ServerLevel level)) {
                return;
            }
            // 解码失败（坏包/旧客户端）就静默丢弃，不要写进存档
            final Sketch sketch = SketchData.readSketch(this.sketch);
            if (sketch == null) {
                return;
            }
            final SketchData data = SketchData.get(level);
            final Sketch existing = data.all().get(sketch.id());
            // 已有的草图不允许改「所属结构」—— 否则可以把它挪到别人/别的结构上
            if (existing != null && !existing.body().equals(sketch.body())) {
                return;
            }
            data.put(sketch);
            broadcast(level);
            // ⚠ 草图改了 → 引用它的实体的几何也变了（盒分解是**现算**的），
            // 所以碰撞必须跟着重建。漏了这一步就会出现"改了草图，碰撞还是旧的"。
            com.ovo.sablestopnow.server.SolidCollisionService.rebuild(level, sketch.body());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 删掉一张草图。 */
    public record RemoveSketchPayload(UUID sketchId) implements CustomPacketPayload {

        public static final Type<RemoveSketchPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "sketch_remove"));

        public static final StreamCodec<RegistryFriendlyByteBuf, RemoveSketchPayload> CODEC =
                StreamCodec.composite(
                        net.minecraft.core.UUIDUtil.STREAM_CODEC, RemoveSketchPayload::sketchId,
                        RemoveSketchPayload::new);

        private void handle(final PacketContext context) {
            if (!(context.level() instanceof final ServerLevel level)) {
                return;
            }
            // ⚠ 先记下这张草图属于哪个结构 —— 删掉之后就查不到了，而重建要用它
            final Sketch gone = SketchData.get(level).all().get(this.sketchId);
            final java.util.UUID body = gone == null ? null : gone.body();
            if (SketchData.get(level).remove(this.sketchId)) {
                // 连带清掉引用这张草图的拉伸实体：实体几何完全来自草图，
                // 草图没了它就是一块永远画不出来也碰不到的东西（见 SolidData#dropOfSketch）。
                final int dropped = com.ovo.sablestopnow.sketch.SolidData.get(level)
                        .dropOfSketch(this.sketchId);
                broadcast(level);
                if (dropped > 0) {
                    SolidNetworking.broadcast(level);
                }
                // 实体没了 → 碰撞也要跟着清掉（否则碰撞会留在空气里）
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

    /**
     * 某个维度的全部草图。
     *
     * <p>整份 {@code SketchData} 的 NBT 一起发，客户端调 {@link SketchData#load} 解析 ——
     * 依旧是「存档怎么存，网络就怎么传」。
     */
    public record SyncSketchesPayload(ResourceLocation dimension, CompoundTag data)
            implements CustomPacketPayload {

        public static final Type<SyncSketchesPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "sketch_sync"));

        public static final StreamCodec<RegistryFriendlyByteBuf, SyncSketchesPayload> CODEC =
                StreamCodec.composite(
                        ResourceLocation.STREAM_CODEC, SyncSketchesPayload::dimension,
                        ByteBufCodecs.COMPOUND_TAG, SyncSketchesPayload::data,
                        SyncSketchesPayload::new);

        private void handle(final PacketContext context) {
            SketchClientState.accept(this.dimension, this.data);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // ============ 发送 ============

    /**
     * 客户端提交一张草图（新建或覆盖）。
     *
     * <p>编码走 {@link SketchData#writeSketch} —— 与存档同一份实现，见类注释。
     */
    public static void sendSave(final Sketch sketch) {
        VeilPacketManager.server().sendPacket(new SaveSketchPayload(SketchData.writeSketch(sketch)));
    }

    /** 客户端请求删除一张草图。 */
    public static void sendRemove(final UUID sketchId) {
        VeilPacketManager.server().sendPacket(new RemoveSketchPayload(sketchId));
    }

    private static SyncSketchesPayload snapshot(final ServerLevel level) {
        return new SyncSketchesPayload(level.dimension().location(),
                SketchData.get(level).save(new CompoundTag(), level.registryAccess()));
    }

    /** 把某个维度的草图广播给该维度的所有人。 */
    public static void broadcast(final ServerLevel level) {
        final SyncSketchesPayload payload = snapshot(level);
        for (final ServerPlayer player : level.players()) {
            VeilPacketManager.player(player).sendPacket(payload);
        }
    }

    /** 玩家登录时把各维度的草图都发给他。 */
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

    /** 把某张草图的变化推给一个维度的所有人（内部编辑用，走的是同一条广播）。 */
    public static void push(final ServerLevel level) {
        broadcast(level);
    }
}
