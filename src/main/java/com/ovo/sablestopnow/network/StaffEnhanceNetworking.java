package com.ovo.sablestopnow.network;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.client.StaffEnhanceClientHandler;
import com.ovo.sablestopnow.server.StaffEnhanceServer;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import foundry.veil.api.network.VeilPacketManager;
import foundry.veil.api.network.handler.PacketContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;

/**
 * 物理手杖增强 —— 自建 Veil 网络通道与载荷（模式照抄 Simulated/SimPacketManager）。
 *
 * <p>C2S：开始/停止整组拖拽（服务端负责焊接/解焊）、切换无碰撞标记；
 * S2C：同步各维度无碰撞标记集（供客户端画提示图标）。
 */
public final class StaffEnhanceNetworking {

    private static final VeilPacketManager MANAGER = VeilPacketManager.create(SablestopNow.MOD_ID, "0.1");

    // ---- 小型 codec（ByteBuf 级手写，不依赖 FriendlyByteBuf 扩展方法） ----
    static final StreamCodec<ByteBuf, UUID> UUID_CODEC = StreamCodec.of(
            (buf, uuid) -> {
                buf.writeLong(uuid.getMostSignificantBits());
                buf.writeLong(uuid.getLeastSignificantBits());
            },
            buf -> new UUID(buf.readLong(), buf.readLong()));

    static final StreamCodec<ByteBuf, List<UUID>> UUID_LIST_CODEC = StreamCodec.of(
            (buf, list) -> {
                buf.writeInt(list.size());
                for (final UUID uuid : list) {
                    UUID_CODEC.encode(buf, uuid);
                }
            },
            buf -> {
                final int n = buf.readInt();
                final List<UUID> out = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    out.add(UUID_CODEC.decode(buf));
                }
                return out;
            });

    /** 维度按 "namespace:path" UTF-8 长度前缀编码。 */
    static final StreamCodec<ByteBuf, ResourceLocation> DIMENSION_CODEC = StreamCodec.of(
            (buf, rl) -> {
                final byte[] bytes = rl.toString().getBytes(StandardCharsets.UTF_8);
                buf.writeInt(bytes.length);
                buf.writeBytes(bytes);
            },
            buf -> {
                final int n = buf.readInt();
                final byte[] bytes = new byte[n];
                buf.readBytes(bytes);
                return ResourceLocation.parse(new String(bytes, StandardCharsets.UTF_8));
            });

    static final StreamCodec<ByteBuf, Vector3d> VECTOR3D_CODEC = StreamCodec.of(
            (buf, v) -> {
                buf.writeDouble(v.x);
                buf.writeDouble(v.y);
                buf.writeDouble(v.z);
            },
            buf -> new Vector3d(buf.readDouble(), buf.readDouble(), buf.readDouble()));

    static final StreamCodec<ByteBuf, Quaterniond> QUATERNIOND_CODEC = StreamCodec.of(
            (buf, q) -> {
                buf.writeDouble(q.x);
                buf.writeDouble(q.y);
                buf.writeDouble(q.z);
                buf.writeDouble(q.w);
            },
            buf -> new Quaterniond(buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble()));

    private StaffEnhanceNetworking() {
    }

    /** 在 @Mod 构造器调用（两物理端都会执行，与 Simulated.init() 一致）。 */
    public static void init() {
        MANAGER.registerServerbound(StartGroupPayload.TYPE, StartGroupPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(StopGroupPayload.TYPE, StopGroupPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(MoveGroupPayload.TYPE, MoveGroupPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(SetLocksPayload.TYPE, SetLocksPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(ToggleNoCollisionPayload.TYPE, ToggleNoCollisionPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerClientbound(SyncNoCollisionPayload.TYPE, SyncNoCollisionPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerClientbound(SyncLocksPayload.TYPE, SyncLocksPayload.CODEC, (payload, context) -> payload.handle(context));
    }

    // ============ C2S：开始整组拖拽 ============
    public static final class StartGroupPayload implements CustomPacketPayload {
        public static final Type<StartGroupPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_group_start"));
        public static final StreamCodec<ByteBuf, StartGroupPayload> CODEC = StreamCodec.composite(
                UUID_CODEC, payload -> payload.leader,
                UUID_LIST_CODEC, payload -> payload.members,
                StartGroupPayload::new);

        private final UUID leader;
        private final List<UUID> members;

        public StartGroupPayload(final UUID leader, final Collection<UUID> members) {
            this.leader = leader;
            this.members = new ArrayList<>(members);
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
            StaffEnhanceServer.startGroupDrag(level, player.getUUID(), this.leader, this.members);
        }
    }

    // ============ C2S：结束整组拖拽 ============
    public static final class StopGroupPayload implements CustomPacketPayload {
        public static final Type<StopGroupPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_group_stop"));
        public static final StreamCodec<ByteBuf, StopGroupPayload> CODEC = StreamCodec.composite(
                UUID_CODEC, payload -> payload.leader,
                StopGroupPayload::new);

        private final UUID leader;

        public StopGroupPayload(final UUID leader) {
            this.leader = leader;
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
            StaffEnhanceServer.stopGroupDrag(level, player.getUUID());
        }
    }

    // ============ C2S：逐 tick 组中心目标 + 累计旋转 ============
    public static final class MoveGroupPayload implements CustomPacketPayload {
        public static final Type<MoveGroupPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_group_move"));
        public static final StreamCodec<ByteBuf, MoveGroupPayload> CODEC = StreamCodec.composite(
                VECTOR3D_CODEC, payload -> payload.centerGoal,
                QUATERNIOND_CODEC, payload -> payload.rot,
                MoveGroupPayload::new);

        private final Vector3d centerGoal;
        private final Quaterniond rot;

        public MoveGroupPayload(final Vector3dc centerGoal, final Quaterniond rot) {
            this.centerGoal = new Vector3d(centerGoal);
            this.rot = new Quaterniond(rot);
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
            StaffEnhanceServer.moveGroup(player.getUUID(), this.centerGoal, this.rot);
        }
    }

    // ============ C2S：对一组物理体设置锁定/解锁（航空学 FixedConstraint） ============
    public static final class SetLocksPayload implements CustomPacketPayload {
        public static final Type<SetLocksPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_set_locks"));
        public static final StreamCodec<ByteBuf, SetLocksPayload> CODEC = StreamCodec.composite(
                StreamCodec.of((buf, b) -> buf.writeBoolean(b), ByteBuf::readBoolean), payload -> payload.lock,
                UUID_LIST_CODEC, payload -> payload.subLevels,
                SetLocksPayload::new);

        private final boolean lock;
        private final List<UUID> subLevels;

        public SetLocksPayload(final boolean lock, final Collection<UUID> subLevels) {
            this.lock = lock;
            this.subLevels = new ArrayList<>(subLevels);
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
            StaffEnhanceServer.setLocks(level, this.lock, this.subLevels);
        }
    }

    // ============ C2S：切换无碰撞标记 ============
    public static final class ToggleNoCollisionPayload implements CustomPacketPayload {
        public static final Type<ToggleNoCollisionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_toggle_no_collision"));
        public static final StreamCodec<ByteBuf, ToggleNoCollisionPayload> CODEC = StreamCodec.composite(
                UUID_CODEC, payload -> payload.subLevel,
                ToggleNoCollisionPayload::new);

        private final UUID subLevel;

        public ToggleNoCollisionPayload(final UUID subLevel) {
            this.subLevel = subLevel;
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
            StaffEnhanceServer.toggleNoCollision(level, this.subLevel);
        }
    }

    // ============ S2C：同步已锁定物理体集（航空学 FixedConstraint 状态，供左键切换判断） ============
    public static final class SyncLocksPayload implements CustomPacketPayload {
        public static final Type<SyncLocksPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_sync_locks"));
        public static final StreamCodec<ByteBuf, SyncLocksPayload> CODEC = StreamCodec.composite(
                DIMENSION_CODEC, payload -> payload.dimension,
                UUID_LIST_CODEC, payload -> payload.locks,
                SyncLocksPayload::new);

        private final ResourceLocation dimension;
        private final List<UUID> locks;

        public SyncLocksPayload(final ResourceKey<Level> dimension, final Collection<UUID> locks) {
            this(dimension.location(), locks);
        }

        public SyncLocksPayload(final ResourceLocation dimension, final Collection<UUID> locks) {
            this.dimension = dimension;
            this.locks = new ArrayList<>(locks);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            StaffEnhanceClientHandler.setStaffLocks(this.dimension, this.locks);
        }
    }

    // ============ S2C：同步无碰撞集 ============
    public static final class SyncNoCollisionPayload implements CustomPacketPayload {
        public static final Type<SyncNoCollisionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_sync_no_collision"));
        public static final StreamCodec<ByteBuf, SyncNoCollisionPayload> CODEC = StreamCodec.composite(
                DIMENSION_CODEC, payload -> payload.dimension,
                UUID_LIST_CODEC, payload -> payload.noCollision,
                SyncNoCollisionPayload::new);

        private final ResourceLocation dimension;
        private final List<UUID> noCollision;

        public SyncNoCollisionPayload(final ResourceKey<Level> dimension, final Collection<UUID> noCollision) {
            this(dimension.location(), noCollision);
        }

        public SyncNoCollisionPayload(final ResourceLocation dimension, final Collection<UUID> noCollision) {
            this.dimension = dimension;
            this.noCollision = new ArrayList<>(noCollision);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            StaffEnhanceClientHandler.setNoCollision(this.dimension, this.noCollision);
        }
    }
}
