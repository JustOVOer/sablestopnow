package com.ovo.sablestopnow.network;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.coupling.CouplingClientState;
import com.ovo.sablestopnow.mate.MateRef;
import com.ovo.sablestopnow.server.CouplingData;
import com.ovo.sablestopnow.server.CouplingRegistry;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import foundry.veil.api.network.VeilPacketManager;
import foundry.veil.api.network.handler.PacketContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 耦合类配合（比例 / 齿轮）的网络：C2S 建组与改参数，S2C 全量同步。
 *
 * <p>结构与 {@link MateNetworking} / {@link ForceNetworking} 一致：独立子通道 + 手写 codec。
 * 参考的编解码复用 {@link MateNetworking#MATE_REF_CODEC}（含碰撞盒子盒字段），
 * 于是「方块级参考」在配合与耦合里永远是同一种表达。
 */
public final class CouplingNetworking {

    private static final VeilPacketManager MANAGER = VeilPacketManager.create(SablestopNow.MOD_ID, "coupling");

    private CouplingNetworking() {
    }

    public static void init() {
        MANAGER.registerServerbound(CouplingCommandPayload.TYPE, CouplingCommandPayload.CODEC,
                (payload, context) -> payload.handle(context));
        MANAGER.registerClientbound(SyncCouplingsPayload.TYPE, SyncCouplingsPayload.CODEC,
                (payload, context) -> payload.handle(context));
    }

    // ============ C2S ============

    public enum Action {
        /** 按已选参考建比例组（最后一个是基准）。 */
        CREATE_RATIO,
        /** 按顺序四条轴建齿轮耦合。 */
        CREATE_GEAR,
        /** 改某个从动面的 k。 */
        SET_RATIO,
        /** 改齿数比 / 反转。 */
        SET_GEAR,
        REMOVE
    }

    public static final class CouplingCommandPayload implements CustomPacketPayload {

        public static final Type<CouplingCommandPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "coupling_command"));

        public static final StreamCodec<ByteBuf, CouplingCommandPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeByte(payload.action.ordinal());
                    writeUuid(buf, payload.groupId);
                    buf.writeInt(payload.refs.size());
                    for (final MateRef ref : payload.refs) {
                        MateNetworking.MATE_REF_CODEC.encode(buf, ref);
                    }
                    buf.writeInt(payload.memberIndex);
                    buf.writeDouble(payload.value);
                    buf.writeInt(payload.teethA);
                    buf.writeInt(payload.teethB);
                    buf.writeBoolean(payload.reversed);
                    buf.writeBoolean(payload.gear);
                },
                buf -> {
                    final Action action = Action.values()[buf.readByte()];
                    final UUID groupId = readUuid(buf);
                    final int count = buf.readInt();
                    final List<MateRef> refs = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        refs.add(MateNetworking.MATE_REF_CODEC.decode(buf));
                    }
                    final int memberIndex = buf.readInt();
                    final double value = buf.readDouble();
                    final int teethA = buf.readInt();
                    final int teethB = buf.readInt();
                    final boolean reversed = buf.readBoolean();
                    final boolean gear = buf.readBoolean();
                    return new CouplingCommandPayload(action, groupId, refs, memberIndex, value,
                            teethA, teethB, reversed, gear);
                });

        private final Action action;
        @org.jetbrains.annotations.Nullable
        private final UUID groupId;
        private final List<MateRef> refs;
        private final int memberIndex;
        private final double value;
        private final int teethA;
        private final int teethB;
        private final boolean reversed;
        private final boolean gear;

        public CouplingCommandPayload(final Action action, @org.jetbrains.annotations.Nullable final UUID groupId,
                                      final List<MateRef> refs, final int memberIndex, final double value,
                                      final int teethA, final int teethB, final boolean reversed,
                                      final boolean gear) {
            this.action = action;
            this.groupId = groupId;
            this.refs = refs;
            this.memberIndex = memberIndex;
            this.value = value;
            this.teethA = teethA;
            this.teethB = teethB;
            this.reversed = reversed;
            this.gear = gear;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            final Player player = context.player();
            if (!PhysicsStaffItem.isHolding(player)) {
                context.disconnect(Component.literal("Invalid packet"));
                return;
            }
            final ServerLevel level = (ServerLevel) context.level();
            // groupId 可空（CREATE 两个动作本来就没有 id）：其余动作拿到 null 说明包不完整，直接忽略
            switch (this.action) {
                case CREATE_RATIO -> CouplingRegistry.createRatio(level, player.getUUID(),
                        player.getGameProfile().getName(), this.refs);
                case CREATE_GEAR -> CouplingRegistry.createGear(level, player.getUUID(),
                        player.getGameProfile().getName(), this.refs, this.teethA, this.teethB, this.reversed);
                case SET_RATIO -> {
                    if (this.groupId != null) {
                        CouplingRegistry.setRatio(level, this.groupId, this.memberIndex, this.value);
                    }
                }
                case SET_GEAR -> {
                    if (this.groupId != null) {
                        CouplingRegistry.setGear(level, this.groupId, this.teethA, this.teethB, this.reversed);
                    }
                }
                case REMOVE -> {
                    if (this.groupId != null) {
                        CouplingRegistry.remove(level, this.groupId, this.gear);
                    }
                }
            }
        }
    }

    // ============ S2C ============

    public static final class SyncCouplingsPayload implements CustomPacketPayload {

        public static final Type<SyncCouplingsPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "coupling_sync"));

        public static final StreamCodec<ByteBuf, SyncCouplingsPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    ResourceLocation.STREAM_CODEC.encode(buf, payload.dimension);
                    buf.writeInt(payload.ratios.size());
                    for (final CouplingData.RatioGroup group : payload.ratios) {
                        writeUuid(buf, group.id());
                        writeUuid(buf, group.owner());
                        MateNetworking.MATE_REF_CODEC.encode(buf, group.driver());
                        buf.writeInt(group.members().size());
                        for (final CouplingData.RatioMember member : group.members()) {
                            MateNetworking.MATE_REF_CODEC.encode(buf, member.ref());
                            buf.writeDouble(member.ratio());
                        }
                    }
                    buf.writeInt(payload.gears.size());
                    for (final CouplingData.GearCoupling coupling : payload.gears) {
                        writeUuid(buf, coupling.id());
                        writeUuid(buf, coupling.owner());
                        MateNetworking.MATE_REF_CODEC.encode(buf, coupling.fixedA());
                        MateNetworking.MATE_REF_CODEC.encode(buf, coupling.gearA());
                        MateNetworking.MATE_REF_CODEC.encode(buf, coupling.fixedB());
                        MateNetworking.MATE_REF_CODEC.encode(buf, coupling.gearB());
                        buf.writeInt(coupling.teethA());
                        buf.writeInt(coupling.teethB());
                        buf.writeBoolean(coupling.reversed());
                    }
                },
                buf -> {
                    final ResourceLocation dimension = ResourceLocation.STREAM_CODEC.decode(buf);
                    final int ratioCount = buf.readInt();
                    final List<CouplingData.RatioGroup> ratios = new ArrayList<>(ratioCount);
                    for (int i = 0; i < ratioCount; i++) {
                        final UUID id = readUuid(buf);
                        final UUID owner = readUuid(buf);
                        final MateRef driver = MateNetworking.MATE_REF_CODEC.decode(buf);
                        final int members = buf.readInt();
                        final List<CouplingData.RatioMember> list = new ArrayList<>(members);
                        for (int j = 0; j < members; j++) {
                            list.add(new CouplingData.RatioMember(MateNetworking.MATE_REF_CODEC.decode(buf),
                                    buf.readDouble()));
                        }
                        ratios.add(new CouplingData.RatioGroup(id, driver, list, owner, null));
                    }
                    final int gearCount = buf.readInt();
                    final List<CouplingData.GearCoupling> gears = new ArrayList<>(gearCount);
                    for (int i = 0; i < gearCount; i++) {
                        final UUID id = readUuid(buf);
                        final UUID owner = readUuid(buf);
                        final MateRef fixedA = MateNetworking.MATE_REF_CODEC.decode(buf);
                        final MateRef gearA = MateNetworking.MATE_REF_CODEC.decode(buf);
                        final MateRef fixedB = MateNetworking.MATE_REF_CODEC.decode(buf);
                        final MateRef gearB = MateNetworking.MATE_REF_CODEC.decode(buf);
                        gears.add(new CouplingData.GearCoupling(id, fixedA, gearA, fixedB, gearB,
                                buf.readInt(), buf.readInt(), buf.readBoolean(), owner, null));
                    }
                    return new SyncCouplingsPayload(dimension, ratios, gears);
                });

        private final ResourceLocation dimension;
        private final List<CouplingData.RatioGroup> ratios;
        private final List<CouplingData.GearCoupling> gears;

        public SyncCouplingsPayload(final ResourceLocation dimension,
                                    final List<CouplingData.RatioGroup> ratios,
                                    final List<CouplingData.GearCoupling> gears) {
            this.dimension = dimension;
            this.ratios = ratios;
            this.gears = gears;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            CouplingClientState.set(this.dimension, this.ratios, this.gears);
        }
    }

    // ============ 发送 ============

    public static void broadcast(final ServerLevel level) {
        final CouplingData data = CouplingData.get(level);
        final SyncCouplingsPayload payload = new SyncCouplingsPayload(level.dimension().location(),
                new ArrayList<>(data.ratios().values()), new ArrayList<>(data.gears().values()));
        for (final ServerPlayer player : level.players()) {
            VeilPacketManager.player(player).sendPacket(payload);
        }
    }

    public static void sendAllTo(final ServerPlayer player) {
        for (final ServerLevel level : player.server.getAllLevels()) {
            final CouplingData data = CouplingData.get(level);
            VeilPacketManager.player(player).sendPacket(new SyncCouplingsPayload(level.dimension().location(),
                    new ArrayList<>(data.ratios().values()), new ArrayList<>(data.gears().values())));
        }
    }

    public static void send(final CouplingCommandPayload payload) {
        VeilPacketManager.server().sendPacket(payload);
    }

    public static CouplingCommandPayload createRatio(final List<MateRef> refs) {
        return new CouplingCommandPayload(Action.CREATE_RATIO, null, refs, -1, 0.0, 0, 0, false, false);
    }

    public static CouplingCommandPayload createGear(final List<MateRef> refs, final int teethA,
                                                    final int teethB, final boolean reversed) {
        return new CouplingCommandPayload(Action.CREATE_GEAR, null, refs, -1, 0.0, teethA, teethB, reversed, true);
    }

    public static CouplingCommandPayload setRatio(final UUID groupId, final int memberIndex, final double k) {
        return new CouplingCommandPayload(Action.SET_RATIO, groupId, List.of(), memberIndex, k, 0, 0, false, false);
    }

    public static CouplingCommandPayload setGear(final UUID groupId, final int teethA, final int teethB,
                                                 final boolean reversed) {
        return new CouplingCommandPayload(Action.SET_GEAR, groupId, List.of(), -1, 0.0, teethA, teethB,
                reversed, true);
    }

    public static CouplingCommandPayload remove(final UUID groupId, final boolean gear) {
        return new CouplingCommandPayload(Action.REMOVE, groupId, List.of(), -1, 0.0, 0, 0, false, gear);
    }

    // ============ 小工具 ============

    /**
     * 写一个<b>可空</b>的 UUID。
     *
     * <p>⚠ 以前这里直接 {@code uuid.getMostSignificantBits()}，而 {@code CREATE_RATIO} / {@code CREATE_GEAR}
     * 的 {@code groupId} 本来就是 null（组还没建出来，哪来的 id）。于是编码阶段抛 NPE →
     * {@code EncoderException} → <b>整条连接被断开</b>，玩家直接被踢回服务器列表
     * （用户实测反馈「选择比例配合完成后会退出到服务器页面」）。
     *
     * <p>做法与 {@code MateNetworking} 一致：先写一个存在位。协议两边必须同时改，
     * 只改一边会让后面所有字段错位。
     */
    private static void writeUuid(final ByteBuf buf, @org.jetbrains.annotations.Nullable final UUID uuid) {
        buf.writeBoolean(uuid != null);
        if (uuid != null) {
            buf.writeLong(uuid.getMostSignificantBits());
            buf.writeLong(uuid.getLeastSignificantBits());
        }
    }

    @org.jetbrains.annotations.Nullable
    private static UUID readUuid(final ByteBuf buf) {
        return buf.readBoolean() ? new UUID(buf.readLong(), buf.readLong()) : null;
    }
}
