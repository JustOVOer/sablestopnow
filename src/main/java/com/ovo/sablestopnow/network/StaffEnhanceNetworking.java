package com.ovo.sablestopnow.network;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.client.StaffEnhanceClientHandler;
import com.ovo.sablestopnow.server.StaffEnhanceServer;
import com.ovo.sablestopnow.server.StaffOwnershipData;
import com.ovo.sablestopnow.server.StaffScaleData;
import com.ovo.sablestopnow.server.StaffSelectionRegistry;
import com.ovo.sablestopnow.server.StaffSnapshotRegistry;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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

    /** UTF-8 长度前缀字符串。 */
    static final StreamCodec<ByteBuf, String> STRING_CODEC = StreamCodec.of(
            (buf, s) -> {
                final byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                buf.writeInt(bytes.length);
                buf.writeBytes(bytes);
            },
            buf -> {
                final int n = buf.readInt();
                final byte[] bytes = new byte[n];
                buf.readBytes(bytes);
                return new String(bytes, StandardCharsets.UTF_8);
            });

    /** 一条「某玩家当前选中的物理结构集合」。 */
    public record SelectionEntry(UUID player, int colorIndex, List<UUID> ids) {
    }

    static final StreamCodec<ByteBuf, SelectionEntry> SELECTION_ENTRY_CODEC = StreamCodec.of(
            (buf, e) -> {
                UUID_CODEC.encode(buf, e.player());
                buf.writeInt(e.colorIndex());
                UUID_LIST_CODEC.encode(buf, e.ids());
            },
            buf -> new SelectionEntry(UUID_CODEC.decode(buf), buf.readInt(), UUID_LIST_CODEC.decode(buf)));

    static final StreamCodec<ByteBuf, List<SelectionEntry>> SELECTION_ENTRY_LIST_CODEC = StreamCodec.of(
            (buf, list) -> {
                buf.writeInt(list.size());
                for (final SelectionEntry entry : list) {
                    SELECTION_ENTRY_CODEC.encode(buf, entry);
                }
            },
            buf -> {
                final int n = buf.readInt();
                final List<SelectionEntry> out = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    out.add(SELECTION_ENTRY_CODEC.decode(buf));
                }
                return out;
            });

    /** 一条「某物理结构的所有者」。 */
    public record OwnershipEntry(UUID subLevel, UUID owner, String ownerName) {
    }

    static final StreamCodec<ByteBuf, OwnershipEntry> OWNERSHIP_ENTRY_CODEC = StreamCodec.of(
            (buf, e) -> {
                UUID_CODEC.encode(buf, e.subLevel());
                UUID_CODEC.encode(buf, e.owner());
                STRING_CODEC.encode(buf, e.ownerName());
            },
            buf -> new OwnershipEntry(UUID_CODEC.decode(buf), UUID_CODEC.decode(buf), STRING_CODEC.decode(buf)));

    static final StreamCodec<ByteBuf, List<OwnershipEntry>> OWNERSHIP_LIST_CODEC = StreamCodec.of(
            (buf, list) -> {
                buf.writeInt(list.size());
                for (final OwnershipEntry entry : list) {
                    OWNERSHIP_ENTRY_CODEC.encode(buf, entry);
                }
            },
            buf -> {
                final int n = buf.readInt();
                final List<OwnershipEntry> out = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    out.add(OWNERSHIP_ENTRY_CODEC.decode(buf));
                }
                return out;
            });

    private StaffEnhanceNetworking() {
    }

    /** 在 @Mod 构造器调用（两物理端都会执行，与 Simulated.init() 一致）。 */
    public static void init() {
        MANAGER.registerServerbound(StartGroupPayload.TYPE, StartGroupPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(StopGroupPayload.TYPE, StopGroupPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(MoveGroupPayload.TYPE, MoveGroupPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(SetLocksPayload.TYPE, SetLocksPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(ToggleNoCollisionPayload.TYPE, ToggleNoCollisionPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(SetNoCollisionPayload.TYPE, SetNoCollisionPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerClientbound(SyncNoCollisionPayload.TYPE, SyncNoCollisionPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerClientbound(SyncLocksPayload.TYPE, SyncLocksPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(SelectBodiesPayload.TYPE, SelectBodiesPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(ClearSelectionPayload.TYPE, ClearSelectionPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(SetOwnershipPayload.TYPE, SetOwnershipPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(SnapshotPayload.TYPE, SnapshotPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(RestoreSnapshotPayload.TYPE, RestoreSnapshotPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(BeginScalePayload.TYPE, BeginScalePayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(UpdateScalePayload.TYPE, UpdateScalePayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(SuperliminalPlacePayload.TYPE, SuperliminalPlacePayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(EndScalePayload.TYPE, EndScalePayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerClientbound(SyncSnapshotPayload.TYPE, SyncSnapshotPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerClientbound(SyncSelectionsPayload.TYPE, SyncSelectionsPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerClientbound(SyncOwnershipPayload.TYPE, SyncOwnershipPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerClientbound(SyncSuperliminalPayload.TYPE, SyncSuperliminalPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerClientbound(SyncActiveGhostsPayload.TYPE, SyncActiveGhostsPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerClientbound(SyncScalesPayload.TYPE, SyncScalesPayload.CODEC, (payload, context) -> payload.handle(context));
        MANAGER.registerClientbound(SelectionDeniedPayload.TYPE, SelectionDeniedPayload.CODEC, (payload, context) -> payload.handle(context));
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

    // ============ C2S：对一组物理体设置无碰撞标记（服务端幂等） ============
    public static final class SetNoCollisionPayload implements CustomPacketPayload {
        public static final Type<SetNoCollisionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_set_no_collision"));
        public static final StreamCodec<ByteBuf, SetNoCollisionPayload> CODEC = StreamCodec.composite(
                StreamCodec.of((buf, b) -> buf.writeBoolean(b), ByteBuf::readBoolean), payload -> payload.mark,
                UUID_LIST_CODEC, payload -> payload.subLevels,
                SetNoCollisionPayload::new);

        private final boolean mark;
        private final List<UUID> subLevels;

        public SetNoCollisionPayload(final boolean mark, final Collection<UUID> subLevels) {
            this.mark = mark;
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
            StaffEnhanceServer.setNoCollision(level, this.mark, this.subLevels);
        }
    }

    // ============ C2S：加入/移出多选队列（服务端做独占校验） ============
    public static final class SelectBodiesPayload implements CustomPacketPayload {
        public static final Type<SelectBodiesPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_select_bodies"));
        public static final StreamCodec<ByteBuf, SelectBodiesPayload> CODEC = StreamCodec.composite(
                StreamCodec.of((buf, b) -> buf.writeBoolean(b), ByteBuf::readBoolean), payload -> payload.add,
                UUID_LIST_CODEC, payload -> payload.ids,
                SelectBodiesPayload::new);

        private final boolean add;
        private final List<UUID> ids;

        public SelectBodiesPayload(final boolean add, final Collection<UUID> ids) {
            this.add = add;
            this.ids = new ArrayList<>(ids);
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
            final ResourceKey<Level> dimension = level.dimension();
            if (this.add) {
                final StaffSelectionRegistry.Result result = StaffSelectionRegistry.add(level, player.getUUID(), this.ids);
                if (!result.denied().isEmpty() && player instanceof final ServerPlayer serverPlayer) {
                    final java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>(result.denied().values());
                    VeilPacketManager.player(serverPlayer).sendPacket(new SelectionDeniedPayload(
                            new ArrayList<>(result.denied().keySet()), String.join(", ", names)));
                }
            } else {
                StaffSelectionRegistry.remove(dimension, player.getUUID(), this.ids);
            }
            StaffSelectionRegistry.broadcast(level.getServer(), dimension);
        }
    }

    // ============ C2S：清空自己的多选队列（释放全部占用） ============
    public static final class ClearSelectionPayload implements CustomPacketPayload {
        public static final Type<ClearSelectionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_clear_selection"));
        // ⚠ 不能用 StreamCodec.unit(new ...)：那个 codec 只接受“创建时捕获的同一个实例”，
        // 每次 new 一个再发就会 "Can't encode ... expected ..." 直接把连接打挂。用空编解码即可。
        public static final StreamCodec<ByteBuf, ClearSelectionPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                },
                buf -> new ClearSelectionPayload());

        public ClearSelectionPayload() {
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            final Player player = context.player();
            final ServerLevel level = (ServerLevel) context.level();
            StaffSelectionRegistry.clear(level.dimension(), player.getUUID());
            StaffSelectionRegistry.broadcast(level.getServer(), level.dimension());
        }
    }

    // ============ S2C：同步各玩家当前选中的物理结构（用于渲染他人的选择与独占提示） ============
    public static final class SyncSelectionsPayload implements CustomPacketPayload {
        public static final Type<SyncSelectionsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_sync_selections"));
        public static final StreamCodec<ByteBuf, SyncSelectionsPayload> CODEC = StreamCodec.composite(
                DIMENSION_CODEC, payload -> payload.dimension,
                SELECTION_ENTRY_LIST_CODEC, payload -> payload.entries,
                SyncSelectionsPayload::new);

        private final ResourceLocation dimension;
        private final List<SelectionEntry> entries;

        public SyncSelectionsPayload(final ResourceLocation dimension, final List<SelectionEntry> entries) {
            this.dimension = dimension;
            this.entries = new ArrayList<>(entries);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            StaffEnhanceClientHandler.setSelections(this.dimension, this.entries);
        }
    }

    // ============ S2C：本次选中被拒绝（结构已被他人占用） ============
    public static final class SelectionDeniedPayload implements CustomPacketPayload {
        public static final Type<SelectionDeniedPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_selection_denied"));
        public static final StreamCodec<ByteBuf, SelectionDeniedPayload> CODEC = StreamCodec.composite(
                UUID_LIST_CODEC, payload -> payload.denied,
                STRING_CODEC, payload -> payload.owners,
                SelectionDeniedPayload::new);

        private final List<UUID> denied;
        private final String owners;

        public SelectionDeniedPayload(final List<UUID> denied, final String owners) {
            this.denied = new ArrayList<>(denied);
            this.owners = owners;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            StaffEnhanceClientHandler.onSelectionDenied(this.denied, this.owners);
        }
    }

    // ============ C2S：设置/取消选中队列的所有权（功能3） ============
    public static final class SetOwnershipPayload implements CustomPacketPayload {
        public static final Type<SetOwnershipPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_set_ownership"));
        public static final StreamCodec<ByteBuf, SetOwnershipPayload> CODEC = StreamCodec.composite(
                StreamCodec.of((buf, b) -> buf.writeBoolean(b), ByteBuf::readBoolean), payload -> payload.own,
                UUID_LIST_CODEC, payload -> payload.ids,
                SetOwnershipPayload::new);

        private final boolean own;
        private final List<UUID> ids;

        public SetOwnershipPayload(final boolean own, final Collection<UUID> ids) {
            this.own = own;
            this.ids = new ArrayList<>(ids);
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
            final StaffOwnershipData data = StaffOwnershipData.get(level);
            final Map<UUID, String> denied = new java.util.LinkedHashMap<>();
            for (final UUID id : this.ids) {
                if (data.isOwnedByOther(id, player.getUUID())) {
                    denied.put(id, data.ownerNameOf(id));
                    continue;
                }
                if (this.own && player instanceof final ServerPlayer serverPlayer) {
                    data.setOwner(serverPlayer, id);
                } else {
                    data.setOwner(id, null);
                }
            }
            StaffEnhanceServer.broadcastOwnership(level);
            if (!denied.isEmpty() && player instanceof final ServerPlayer serverPlayer) {
                final StringBuilder owners = new StringBuilder();
                for (final String name : new java.util.LinkedHashSet<>(denied.values())) {
                    if (owners.length() > 0) {
                        owners.append(", ");
                    }
                    owners.append(name);
                }
                VeilPacketManager.player(serverPlayer).sendPacket(new SelectionDeniedPayload(
                        new ArrayList<>(denied.keySet()), owners.toString()));
            }
        }
    }

    // ============ S2C：同步所有权（用于 HUD 显示所有者 + 本地判断能否设置） ============
    public static final class SyncOwnershipPayload implements CustomPacketPayload {
        public static final Type<SyncOwnershipPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_sync_ownership"));
        public static final StreamCodec<ByteBuf, SyncOwnershipPayload> CODEC = StreamCodec.composite(
                DIMENSION_CODEC, payload -> payload.dimension,
                OWNERSHIP_LIST_CODEC, payload -> payload.entries,
                SyncOwnershipPayload::new);

        private final ResourceLocation dimension;
        private final List<OwnershipEntry> entries;

        public SyncOwnershipPayload(final ResourceLocation dimension, final List<OwnershipEntry> entries) {
            this.dimension = dimension;
            this.entries = new ArrayList<>(entries);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            StaffEnhanceClientHandler.setOwnerships(this.dimension, this.entries);
        }
    }

    // ============ C2S：创建/取消快照（功能9） ============
    public static final class SnapshotPayload implements CustomPacketPayload {
        public static final Type<SnapshotPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_snapshot"));
        public static final StreamCodec<ByteBuf, SnapshotPayload> CODEC = StreamCodec.composite(
                StreamCodec.of((buf, b) -> buf.writeBoolean(b), ByteBuf::readBoolean), payload -> payload.create,
                UUID_LIST_CODEC, payload -> payload.ids,
                SnapshotPayload::new);

        private final boolean create;
        private final List<UUID> ids;

        public SnapshotPayload(final boolean create, final Collection<UUID> ids) {
            this.create = create;
            this.ids = new ArrayList<>(ids);
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
            if (this.create) {
                final int count = StaffSnapshotRegistry.create(level, player.getUUID(), this.ids);
                if (count == 0) {
                    player.displayClientMessage(Component.translatable("sablestopnow.staff.snapshot_failed"), false);
                } else {
                    player.displayClientMessage(Component.translatable("sablestopnow.staff.snapshot_created", count), false);
                }
            } else {
                StaffSnapshotRegistry.clear(player.getUUID());
                player.displayClientMessage(Component.translatable("sablestopnow.staff.snapshot_cleared"), false);
            }
            StaffEnhanceServer.sendSnapshotState(player);
        }
    }

    // ============ C2S：回退到快照（功能9） ============
    public static final class RestoreSnapshotPayload implements CustomPacketPayload {
        public static final Type<RestoreSnapshotPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_restore_snapshot"));
        /** 同上：不能使用 {@code StreamCodec.unit}，否则每次新建实例都无法编码。 */
        public static final StreamCodec<ByteBuf, RestoreSnapshotPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                },
                buf -> new RestoreSnapshotPayload());

        public RestoreSnapshotPayload() {
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
            // 回退会移除并重建物理结构，先停掉该玩家自己的整组马达会话，避免驱动到已失效的物理体
            StaffEnhanceServer.stopGroupDrag(level, player.getUUID());
            final StaffSnapshotRegistry.RestoreResult result = StaffSnapshotRegistry.restore(level, player.getUUID());
            if (result.restored() == 0 && result.failed() == 0) {
                player.displayClientMessage(Component.translatable("sablestopnow.staff.restore_none"), false);
            } else {
                player.displayClientMessage(Component.translatable("sablestopnow.staff.restore_done",
                        result.restored(), result.failed()), false);
            }
        }
    }

    // ============ S2C：同步“我有哪些结构存了快照”（用于图标提示） ============
    public static final class SyncSnapshotPayload implements CustomPacketPayload {
        public static final Type<SyncSnapshotPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_sync_snapshot"));
        public static final StreamCodec<ByteBuf, SyncSnapshotPayload> CODEC = StreamCodec.composite(
                DIMENSION_CODEC, payload -> payload.dimension,
                UUID_LIST_CODEC, payload -> payload.ids,
                SyncSnapshotPayload::new);

        private final ResourceLocation dimension;
        private final List<UUID> ids;

        public SyncSnapshotPayload(final ResourceLocation dimension, final Collection<UUID> ids) {
            this.dimension = dimension;
            this.ids = new ArrayList<>(ids);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            StaffEnhanceClientHandler.setSnapshotIds(this.dimension, this.ids);
        }
    }

    // ============ C2S：缩放物理结构（X + 滚轮） ============
    /** 开始缩放：服务端把选中的结构统一回 1.0 倍并记录基线（相对质心布局）。 */
    public static final class BeginScalePayload implements CustomPacketPayload {
        public static final Type<BeginScalePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_scale_begin"));
        public static final StreamCodec<ByteBuf, BeginScalePayload> CODEC = StreamCodec.composite(
                UUID_LIST_CODEC, payload -> payload.ids,
                BeginScalePayload::new);

        private final List<UUID> ids;

        public BeginScalePayload(final Collection<UUID> ids) {
            this.ids = new ArrayList<>(ids);
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
            final int count = StaffScaleData.begin(level, player.getUUID(), this.ids);
            if (count == 0) {
                player.displayClientMessage(Component.translatable("sablestopnow.staff.scale_none"), false);
            }
        }
    }

    /** 缩放中：绝对倍率。 */
    public static final class UpdateScalePayload implements CustomPacketPayload {
        public static final Type<UpdateScalePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_scale_update"));
        public static final StreamCodec<ByteBuf, UpdateScalePayload> CODEC = StreamCodec.composite(
                StreamCodec.of((buf, f) -> buf.writeFloat(f), ByteBuf::readFloat), payload -> payload.factor,
                UpdateScalePayload::new);

        private final float factor;

        public UpdateScalePayload(final float factor) {
            this.factor = factor;
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
            StaffScaleData.update((ServerLevel) context.level(), player.getUUID(), this.factor);
        }
    }

    /** 彩蛋用：缩放并把整组绝对定位到给定世界中心（避免和整组拖拽马达互相打架）。 */
    public static final class SuperliminalPlacePayload implements CustomPacketPayload {
        public static final Type<SuperliminalPlacePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_superliminal_place"));
        public static final StreamCodec<ByteBuf, SuperliminalPlacePayload> CODEC = StreamCodec.composite(
                StreamCodec.of((buf, f) -> buf.writeFloat(f), ByteBuf::readFloat), payload -> payload.factor,
                VECTOR3D_CODEC, payload -> payload.center,
                SuperliminalPlacePayload::new);

        private final float factor;
        private final Vector3d center;

        public SuperliminalPlacePayload(final float factor, final Vector3dc center) {
            this.factor = factor;
            this.center = new Vector3d(center);
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
            StaffScaleData.place((ServerLevel) context.level(), player.getUUID(), this.factor, this.center);
        }
    }

    /** 结束缩放（保留结果）。 */
    public static final class EndScalePayload implements CustomPacketPayload {
        public static final Type<EndScalePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_scale_end"));
        /** ⚠ 不能用 StreamCodec.unit（它只接受创建时捕获的同一实例）。 */
        public static final StreamCodec<ByteBuf, EndScalePayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                },
                buf -> new EndScalePayload());

        public EndScalePayload() {
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            final Player player = context.player();
            StaffScaleData.end((ServerLevel) context.level(), player.getUUID());
        }
    }

    /** S2C：缩放表（Sable 自己的位姿同步不写 scale，必须由我们自己同步，否则客户端看不见缩放）。 */
    public record ScaleEntry(UUID subLevel, float scale) {
    }

    static final StreamCodec<ByteBuf, ScaleEntry> SCALE_ENTRY_CODEC = StreamCodec.of(
            (buf, e) -> {
                UUID_CODEC.encode(buf, e.subLevel());
                buf.writeFloat(e.scale());
            },
            buf -> new ScaleEntry(UUID_CODEC.decode(buf), buf.readFloat()));

    static final StreamCodec<ByteBuf, List<ScaleEntry>> SCALE_LIST_CODEC = StreamCodec.of(
            (buf, list) -> {
                buf.writeInt(list.size());
                for (final ScaleEntry entry : list) {
                    SCALE_ENTRY_CODEC.encode(buf, entry);
                }
            },
            buf -> {
                final int n = buf.readInt();
                final List<ScaleEntry> out = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    out.add(SCALE_ENTRY_CODEC.decode(buf));
                }
                return out;
            });

    public static final class SyncScalesPayload implements CustomPacketPayload {
        public static final Type<SyncScalesPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_sync_scales"));
        public static final StreamCodec<ByteBuf, SyncScalesPayload> CODEC = StreamCodec.composite(
                DIMENSION_CODEC, payload -> payload.dimension,
                SCALE_LIST_CODEC, payload -> payload.entries,
                SyncScalesPayload::new);

        private final ResourceLocation dimension;
        private final List<ScaleEntry> entries;

        public SyncScalesPayload(final ResourceLocation dimension, final List<ScaleEntry> entries) {
            this.dimension = dimension;
            this.entries = new ArrayList<>(entries);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            StaffEnhanceClientHandler.setScales(this.dimension, this.entries);
        }
    }

    // ============ S2C：同步「正在被拖拽（对拖拽者幽灵化）」+「缩放中（对所有玩家幽灵化）」的物理结构 ============
    public static final class SyncActiveGhostsPayload implements CustomPacketPayload {
        public static final Type<SyncActiveGhostsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_active_ghosts"));
        public static final StreamCodec<ByteBuf, SyncActiveGhostsPayload> CODEC = StreamCodec.composite(
                UUID_LIST_CODEC, payload -> payload.subLevels,
                UUID_LIST_CODEC, payload -> payload.draggers,
                UUID_LIST_CODEC, payload -> payload.globalIds,
                SyncActiveGhostsPayload::new);

        private final List<UUID> subLevels;
        private final List<UUID> draggers;
        private final List<UUID> globalIds;

        public SyncActiveGhostsPayload(final List<UUID> subLevels, final List<UUID> draggers, final List<UUID> globalIds) {
            this.subLevels = new ArrayList<>(subLevels);
            this.draggers = new ArrayList<>(draggers);
            this.globalIds = new ArrayList<>(globalIds);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            StaffEnhanceClientHandler.setActiveGhosts(this.subLevels, this.draggers, this.globalIds);
        }
    }

    // ============ S2C：彩蛋 Superliminal 开关状态 ============
    public static final class SyncSuperliminalPayload implements CustomPacketPayload {
        public static final Type<SyncSuperliminalPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "staff_superliminal"));
        public static final StreamCodec<ByteBuf, SyncSuperliminalPayload> CODEC = StreamCodec.composite(
                StreamCodec.of((buf, b) -> buf.writeBoolean(b), ByteBuf::readBoolean), payload -> payload.on,
                SyncSuperliminalPayload::new);

        private final boolean on;

        public SyncSuperliminalPayload(final boolean on) {
            this.on = on;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            StaffEnhanceClientHandler.setSuperliminal(this.on);
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
