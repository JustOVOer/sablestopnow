package com.ovo.sablestopnow.network;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.force.ForcePoint;
import com.ovo.sablestopnow.force.ForcePointData;
import com.ovo.sablestopnow.server.ForcePointRegistry;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import foundry.veil.api.network.VeilPacketManager;
import foundry.veil.api.network.handler.PacketContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 可控施力点的网络：客户端改属性/放置，服务端广播全量。
 *
 * <p>结构照抄 {@link MateNetworking}：一个 {@code VeilPacketManager} 子通道、C2S 命令 + S2C 全量同步。
 * 之所以也走「全量同步」而不是增量：施力点的数量是玩家手放的个位数级，
 * 全量包小得可以忽略，换来的是「客户端永远不会漂移」。
 */
public final class ForceNetworking {

    private static final VeilPacketManager MANAGER = VeilPacketManager.create(SablestopNow.MOD_ID, "force");

    private ForceNetworking() {
    }

    public static void init() {
        MANAGER.registerServerbound(ForceCommandPayload.TYPE, ForceCommandPayload.CODEC,
                (payload, context) -> payload.handle(context));
        MANAGER.registerClientbound(SyncForcePointsPayload.TYPE, SyncForcePointsPayload.CODEC,
                (payload, context) -> payload.handle(context));
    }

    // ============ 客户端 → 服务端 ============

    public enum Action {
        /** 在模型表面新建一个施力点。 */
        CREATE,
        /** 改力大小 / 频道 / 启用位。 */
        UPDATE,
        REMOVE
    }

    public static final class ForceCommandPayload implements CustomPacketPayload {

        public static final Type<ForceCommandPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "force_command"));

        public static final StreamCodec<ByteBuf, ForceCommandPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeByte(payload.action.ordinal());
                    writeUuid(buf, payload.pointId);
                    writeUuid(buf, payload.body);
                    buf.writeDouble(payload.px);
                    buf.writeDouble(payload.py);
                    buf.writeDouble(payload.pz);
                    buf.writeByte(payload.face == null ? -1 : payload.face.get3DDataValue());
                    buf.writeInt(payload.block == null ? 0 : payload.block.getX());
                    buf.writeInt(payload.block == null ? 0 : payload.block.getY());
                    buf.writeInt(payload.block == null ? 0 : payload.block.getZ());
                    buf.writeDouble(payload.magnitude);
                    writeString(buf, payload.channelItem);
                    writeString(buf, payload.channelItem2);
                    writeString(buf, payload.channelName);
                    buf.writeByte(payload.enabled == null ? 2 : (payload.enabled ? 1 : 0));
                },
                buf -> {
                    final Action action = Action.values()[buf.readByte()];
                    final UUID pointId = readUuid(buf);
                    final UUID body = readUuid(buf);
                    final double px = buf.readDouble();
                    final double py = buf.readDouble();
                    final double pz = buf.readDouble();
                    final int faceId = buf.readByte();
                    final Direction face = faceId < 0 ? null : Direction.from3DDataValue(faceId);
                    final BlockPos block = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
                    final double magnitude = buf.readDouble();
                    final String channelItem = readString(buf);
                    final String channelItem2 = readString(buf);
                    final String channelName = readString(buf);
                    final int enabledFlag = buf.readByte();
                    final Boolean enabled = enabledFlag == 2 ? null : enabledFlag == 1;
                    return new ForceCommandPayload(action, pointId, body, px, py, pz, face, block,
                            magnitude, channelItem, channelItem2, channelName, enabled);
                });

        private final Action action;
        @Nullable
        private final UUID pointId;
        @Nullable
        private final UUID body;
        private final double px;
        private final double py;
        private final double pz;
        @Nullable
        private final Direction face;
        @Nullable
        private final BlockPos block;
        private final double magnitude;
        @Nullable
        private final String channelItem;
        @Nullable
        private final String channelItem2;
        @Nullable
        private final String channelName;
        @Nullable
        private final Boolean enabled;

        public ForceCommandPayload(final Action action, @Nullable final UUID pointId, @Nullable final UUID body,
                                   final double px, final double py, final double pz,
                                   @Nullable final Direction face, @Nullable final BlockPos block,
                                   final double magnitude, @Nullable final String channelItem,
                                   @Nullable final String channelItem2,
                                   @Nullable final String channelName, @Nullable final Boolean enabled) {
            this.action = action;
            this.pointId = pointId;
            this.body = body;
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.face = face;
            this.block = block;
            this.magnitude = magnitude;
            this.channelItem = channelItem;
            this.channelItem2 = channelItem2;
            this.channelName = channelName;
            this.enabled = enabled;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            final Player player = context.player();
            // 与其它功能同一道闸：手里没有物理手杖就当成非法包
            if (!PhysicsStaffItem.isHolding(player)) {
                context.disconnect(Component.literal("Invalid packet"));
                return;
            }
            final ServerLevel level = (ServerLevel) context.level();
            switch (this.action) {
                case CREATE -> {
                    if (this.body == null || this.face == null || this.block == null) {
                        return;
                    }
                    ForcePointRegistry.create(level, this.body, this.block, this.face,
                            new Vector3d(this.px, this.py, this.pz), this.magnitude, this.enabled == null || this.enabled);
                }
                case UPDATE -> {
                    if (this.pointId == null) {
                        return;
                    }
                    // NaN = 「这一项不改」（包体里 magnitude 是非空的 double，没法用 null 表达）
                    final Double magnitude = Double.isNaN(this.magnitude) ? null : this.magnitude;
                    ForcePointRegistry.update(level, this.pointId, magnitude,
                            this.channelItem, this.channelItem2, this.channelName, this.enabled);
                }
                case REMOVE -> {
                    if (this.pointId != null) {
                        ForcePointRegistry.remove(level, this.pointId);
                    }
                }
            }
        }
    }

    /** 客户端发包的便利入口。 */
    public static void sendCommand(final Action action, @Nullable final UUID pointId,
                                   @Nullable final UUID body, final double px, final double py, final double pz,
                                   final double nxUnused, final double nyUnused, final double nzUnused,
                                   final double magnitude, @Nullable final String channelItem,
                                   @Nullable final String channelItem2,
                                   @Nullable final String channelName, @Nullable final Boolean enabled) {
        VeilPacketManager.server().sendPacket(new ForceCommandPayload(action, pointId, body,
                px, py, pz, null, null, magnitude, channelItem, channelItem2, channelName, enabled));
    }

    /**
     * 在模型表面新建（服务端据此算局部点与法线）。
     *
     * <p>⚠ {@code enabled} 传 false 是<b>默认</b>行为：新建的点必须先让玩家在左栏把频道/力量配好、
     * 自己勾「开启」才生效。以前建出来就是开启的，于是「已经往某个频道发了信号 → 新建点 → 一选那个频道
     * 模型立刻飞走」（用户实测反馈）。
     */
    public static void sendCreate(final UUID body, final BlockPos block, final Direction face,
                                  final Vector3d localPoint, final double magnitude,
                                  final boolean enabled) {
        VeilPacketManager.server().sendPacket(new ForceCommandPayload(Action.CREATE, null, body,
                localPoint.x, localPoint.y, localPoint.z, face, block, magnitude,
                null, null, null, enabled));
    }

    // ============ 服务端 → 客户端 ============

    public static final class SyncForcePointsPayload implements CustomPacketPayload {

        public static final Type<SyncForcePointsPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "force_sync"));

        public static final StreamCodec<ByteBuf, SyncForcePointsPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    ResourceLocation.STREAM_CODEC.encode(buf, payload.dimension);
                    buf.writeInt(payload.points.size());
                    for (final ForcePoint point : payload.points) {
                        writePoint(buf, point);
                    }
                },
                buf -> {
                    final ResourceLocation dimension = ResourceLocation.STREAM_CODEC.decode(buf);
                    final int size = buf.readInt();
                    final List<ForcePoint> points = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        points.add(readPoint(buf));
                    }
                    return new SyncForcePointsPayload(dimension, points);
                });

        private final ResourceLocation dimension;
        private final List<ForcePoint> points;

        public SyncForcePointsPayload(final ResourceLocation dimension, final List<ForcePoint> points) {
            this.dimension = dimension;
            this.points = points;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            com.ovo.sablestopnow.force.ForceClientState.setPoints(this.dimension, this.points);
        }
    }

    public static void broadcast(final ServerLevel level) {
        final SyncForcePointsPayload payload = new SyncForcePointsPayload(level.dimension().location(),
                new ArrayList<>(ForcePointData.get(level).all().values()));
        for (final ServerPlayer player : level.players()) {
            VeilPacketManager.player(player).sendPacket(payload);
        }
    }

    /** 玩家登录时把各维度的施力点发给他（GUI 需要）。 */
    public static void sendAllTo(final ServerPlayer player) {
        for (final ServerLevel level : player.server.getAllLevels()) {
            VeilPacketManager.player(player).sendPacket(new SyncForcePointsPayload(
                    level.dimension().location(), new ArrayList<>(ForcePointData.get(level).all().values())));
        }
    }

    /** 玩家换维度时把当前维度的发一遍。 */
    public static void sendCurrentTo(final ServerPlayer player) {
        final Level level = player.level();
        if (level instanceof final ServerLevel serverLevel) {
            broadcast(serverLevel);
        }
    }

    // ============ 小工具 ============

    private static void writeUuid(final ByteBuf buf, @Nullable final UUID uuid) {
        buf.writeBoolean(uuid != null);
        if (uuid != null) {
            buf.writeLong(uuid.getMostSignificantBits());
            buf.writeLong(uuid.getLeastSignificantBits());
        }
    }

    @Nullable
    private static UUID readUuid(final ByteBuf buf) {
        return buf.readBoolean() ? new UUID(buf.readLong(), buf.readLong()) : null;
    }

    private static void writeString(final ByteBuf buf, @Nullable final String value) {
        buf.writeBoolean(value != null);
        if (value != null) {
            ByteBufCodecs.STRING_UTF8.encode(buf, value);
        }
    }

    @Nullable
    private static String readString(final ByteBuf buf) {
        return buf.readBoolean() ? ByteBufCodecs.STRING_UTF8.decode(buf) : null;
    }

    private static void writePoint(final ByteBuf buf, final ForcePoint point) {
        buf.writeLong(point.id().getMostSignificantBits());
        buf.writeLong(point.id().getLeastSignificantBits());
        buf.writeLong(point.body().getMostSignificantBits());
        buf.writeLong(point.body().getLeastSignificantBits());
        buf.writeDouble(point.localPoint().x);
        buf.writeDouble(point.localPoint().y);
        buf.writeDouble(point.localPoint().z);
        buf.writeDouble(point.localNormal().x);
        buf.writeDouble(point.localNormal().y);
        buf.writeDouble(point.localNormal().z);
        buf.writeDouble(point.magnitude());
        writeString(buf, point.channelItemId());
        writeString(buf, point.channelItemId2());
        writeString(buf, point.channelName());
        buf.writeBoolean(point.enabled());
    }

    private static ForcePoint readPoint(final ByteBuf buf) {
        return new ForcePoint(
                new UUID(buf.readLong(), buf.readLong()),
                new UUID(buf.readLong(), buf.readLong()),
                new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble()),
                buf.readDouble(),
                readString(buf),
                readString(buf),
                readString(buf),
                buf.readBoolean());
    }
}
