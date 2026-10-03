package com.ovo.sablestopnow.network;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.client.MateClientState;
import com.ovo.sablestopnow.mate.Mate;
import com.ovo.sablestopnow.mate.MateRef;
import com.ovo.sablestopnow.mate.MateType;
import com.ovo.sablestopnow.mate.RefKind;
import com.ovo.sablestopnow.server.MateRegistry;
import com.ovo.sablestopnow.server.MateSelectionRegistry;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import foundry.veil.api.network.VeilPacketManager;
import foundry.veil.api.network.handler.PacketContext;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 配合系统的网络载荷。
 *
 * <p>C2S：进出配合模式、提交一端参考（右键 / Shift+右键撤销）、删除/改值/改类型；
 * S2C：同步某维度的全部配合（GUI 树 + 世界渲染）、以及一次操作的结果反馈
 * （成功时带新配合 id，客户端据此自动打开树形界面并展开定位）。
 */
public final class MateNetworking {

    private static final VeilPacketManager MANAGER = VeilPacketManager.create(SablestopNow.MOD_ID, "mate");

    private MateNetworking() {
    }

    public static void init() {
        MANAGER.registerServerbound(MateModePayload.TYPE, MateModePayload.CODEC,
                (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(MatePickPayload.TYPE, MatePickPayload.CODEC,
                (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(MateCommandPayload.TYPE, MateCommandPayload.CODEC,
                (payload, context) -> payload.handle(context));
        MANAGER.registerServerbound(ModelMovePayload.TYPE, ModelMovePayload.CODEC,
                (payload, context) -> payload.handle(context));
        MANAGER.registerClientbound(SyncMatesPayload.TYPE, SyncMatesPayload.CODEC,
                (payload, context) -> payload.handle(context));
        MANAGER.registerClientbound(MateFeedbackPayload.TYPE, MateFeedbackPayload.CODEC,
                (payload, context) -> payload.handle(context));
    }

    // ============ 基础 codec ============

    /**
     * C2S：模型模式左键拖动模型 —— 把结构「缓慢搬运」到目标世界位置。
     *
     * <p>发的是<b>绝对目标位置</b>而不是「这一帧移动了多少」：拖动是每帧连续发的，
     * 绝对目标天然幂等（丢一两包也不会让结构跑偏），服务端也就能直接做限速插值。
     * {@code stop = true} 表示松手，服务端立刻停止搬运。
     */
    public static final class ModelMovePayload implements CustomPacketPayload {
        public static final Type<ModelMovePayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "model_move"));

        public static final StreamCodec<ByteBuf, ModelMovePayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    UUID_CODEC.encode(buf, payload.body);
                    buf.writeDouble(payload.x);
                    buf.writeDouble(payload.y);
                    buf.writeDouble(payload.z);
                    buf.writeBoolean(payload.stop);
                },
                buf -> new ModelMovePayload(UUID_CODEC.decode(buf),
                        buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readBoolean()));

        private final UUID body;
        private final double x;
        private final double y;
        private final double z;
        private final boolean stop;

        public ModelMovePayload(final UUID body, final double x, final double y, final double z,
                                final boolean stop) {
            this.body = body;
            this.x = x;
            this.y = y;
            this.z = z;
            this.stop = stop;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            final Player player = context.player();
            if (!PhysicsStaffItem.isHolding(player)) {
                context.disconnect(net.minecraft.network.chat.Component.literal("Invalid packet"));
                return;
            }
            final ServerLevel level = (ServerLevel) context.level();
            com.ovo.sablestopnow.server.ModelMoveService.request(level, this.body,
                    this.stop ? null : new org.joml.Vector3d(this.x, this.y, this.z));
        }
    }
    private static final StreamCodec<ByteBuf, UUID> UUID_CODEC = StreamCodec.of(
            (buf, uuid) -> {
                buf.writeLong(uuid.getMostSignificantBits());
                buf.writeLong(uuid.getLeastSignificantBits());
            },
            buf -> new UUID(buf.readLong(), buf.readLong()));

    private static final StreamCodec<ByteBuf, String> STRING_CODEC = StreamCodec.of(
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

    private static final StreamCodec<ByteBuf, ResourceLocation> DIMENSION_CODEC = StreamCodec.of(
            (buf, rl) -> STRING_CODEC.encode(buf, rl.toString()),
            buf -> ResourceLocation.parse(STRING_CODEC.decode(buf)));

    /** 参考的编解码器（public：耦合类配合的包要复用同一套，避免两份格式各写各的）。 */
    public static final StreamCodec<ByteBuf, MateRef> MATE_REF_CODEC = StreamCodec.of(
            (buf, ref) -> {
                UUID_CODEC.encode(buf, ref.body());
                buf.writeByte(ref.kind().ordinal());
                final BlockPos block = ref.block();
                buf.writeBoolean(block != null);
                if (block != null) {
                    buf.writeInt(block.getX());
                    buf.writeInt(block.getY());
                    buf.writeInt(block.getZ());
                }
                buf.writeByte(ref.face() == null ? -1 : ref.face().get3DDataValue());
                buf.writeByte(ref.feature());
                buf.writeByte(ref.axis());
                // 碰撞盒子盒（整格写 false，省字节且与旧协议一致）
                final net.minecraft.world.phys.AABB box = ref.box();
                final boolean hasBox = box != null && !box.equals(MateRef.FULL_BOX);
                buf.writeBoolean(hasBox);
                if (hasBox) {
                    buf.writeDouble(box.minX);
                    buf.writeDouble(box.minY);
                    buf.writeDouble(box.minZ);
                    buf.writeDouble(box.maxX);
                    buf.writeDouble(box.maxY);
                    buf.writeDouble(box.maxZ);
                }
            },
            buf -> {
                final UUID body = UUID_CODEC.decode(buf);
                final RefKind kind = RefKind.values()[buf.readByte()];
                BlockPos block = null;
                if (buf.readBoolean()) {
                    block = new BlockPos(buf.readInt(), buf.readInt(), buf.readInt());
                }
                final int faceId = buf.readByte();
                final Direction face = faceId < 0 ? null : Direction.from3DDataValue(faceId);
                final int feature = buf.readByte();
                final int axis = buf.readByte();
                net.minecraft.world.phys.AABB box = null;
                if (buf.readBoolean()) {
                    box = new net.minecraft.world.phys.AABB(
                            buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble());
                }
                return new MateRef(body, kind, block, face, feature, axis, box);
            });

    private static final StreamCodec<ByteBuf, Mate> MATE_CODEC = StreamCodec.of(
            (buf, mate) -> {
                UUID_CODEC.encode(buf, mate.id());
                STRING_CODEC.encode(buf, mate.type().id());
                MATE_REF_CODEC.encode(buf, mate.a());
                MATE_REF_CODEC.encode(buf, mate.b());
                buf.writeDouble(mate.value());
                buf.writeDouble(mate.value2());
                buf.writeBoolean(mate.flip());
                buf.writeBoolean(mate.name() != null);
                if (mate.name() != null) {
                    STRING_CODEC.encode(buf, mate.name());
                }
                UUID_CODEC.encode(buf, mate.owner());
                buf.writeBoolean(mate.ownerName() != null);
                if (mate.ownerName() != null) {
                    STRING_CODEC.encode(buf, mate.ownerName());
                }
            },
            buf -> {
                final UUID id = UUID_CODEC.decode(buf);
                final MateType type = MateType.byId(STRING_CODEC.decode(buf));
                final MateRef a = MATE_REF_CODEC.decode(buf);
                final MateRef b = MATE_REF_CODEC.decode(buf);
                final double value = buf.readDouble();
                final double value2 = buf.readDouble();
                final boolean flip = buf.readBoolean();
                final String name = buf.readBoolean() ? STRING_CODEC.decode(buf) : null;
                final UUID owner = UUID_CODEC.decode(buf);
                final String ownerName = buf.readBoolean() ? STRING_CODEC.decode(buf) : null;
                return new Mate(id, type == null ? MateType.COINCIDENT : type, a, b,
                        value, value2, flip, name, owner, ownerName);
            });

    private static final StreamCodec<ByteBuf, List<Mate>> MATE_LIST_CODEC = StreamCodec.of(
            (buf, list) -> {
                buf.writeInt(list.size());
                for (final Mate mate : list) {
                    MATE_CODEC.encode(buf, mate);
                }
            },
            buf -> {
                final int n = buf.readInt();
                final List<Mate> out = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    out.add(MATE_CODEC.decode(buf));
                }
                return out;
            });

    private static final StreamCodec<ByteBuf, List<MateRef>> REF_LIST_CODEC = StreamCodec.of(
            (buf, list) -> {
                buf.writeInt(list.size());
                for (final MateRef ref : list) {
                    MATE_REF_CODEC.encode(buf, ref);
                }
            },
            buf -> {
                final int n = buf.readInt();
                final List<MateRef> out = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    out.add(MATE_REF_CODEC.decode(buf));
                }
                return out;
            });

    // ============ C2S：进入 / 退出配合模式 ============

    public static final class MateModePayload implements CustomPacketPayload {
        public static final Type<MateModePayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "mate_mode"));
        public static final StreamCodec<ByteBuf, MateModePayload> CODEC = StreamCodec.of(
                (buf, payload) -> buf.writeBoolean(payload.enable),
                buf -> new MateModePayload(buf.readBoolean()));

        private final boolean enable;

        public MateModePayload(final boolean enable) {
            this.enable = enable;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            final Player player = context.player();
            // ⚠ 这里刻意<b>不用</b> context.disconnect()：客户端在「手杖消失」时会自动退出配合模式
            // 并补发一个 enable=false，那种情况下玩家本来就没持杖 —— 踢人就成了必须复现的恶性 bug。
            // 进入模式仍然要求持杖；退出/清理一律放行（没有任何权限提升，只是清自己的待选）。
            if (this.enable && !PhysicsStaffItem.isHolding(player)) {
                return;
            }
            final boolean inMode = MateSelectionRegistry.setMode(player.getUUID(), this.enable);
            if (player instanceof final ServerPlayer serverPlayer) {
                VeilPacketManager.player(serverPlayer).sendPacket(new MateFeedbackPayload(
                        inMode ? MateFeedbackPayload.Status.MODE_ON : MateFeedbackPayload.Status.MODE_OFF,
                        "", null, List.of(), false));
            }
        }
    }

    // ============ C2S：提交 / 撤销一端参考 ============

    public static final class MatePickPayload implements CustomPacketPayload {
        public static final Type<MatePickPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "mate_pick"));
        public static final StreamCodec<ByteBuf, MatePickPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeBoolean(payload.undo);
                    if (!payload.undo && payload.ref != null) {
                        MATE_REF_CODEC.encode(buf, payload.ref);
                    }
                },
                buf -> {
                    final boolean undo = buf.readBoolean();
                    return new MatePickPayload(undo, undo ? null : MATE_REF_CODEC.decode(buf));
                });

        private final boolean undo;
        @Nullable
        private final MateRef ref;

        private MatePickPayload(final boolean undo, @Nullable final MateRef ref) {
            this.undo = undo;
            this.ref = ref;
        }

        public static MatePickPayload pick(final MateRef ref) {
            return new MatePickPayload(false, ref);
        }

        public static MatePickPayload undo() {
            return new MatePickPayload(true, null);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            final Player player = context.player();
            // 不踢人：选取本身有 inMode + 结构存在性校验，忽略越界的包就够了
            if (!PhysicsStaffItem.isHolding(player)) {
                return;
            }
            if (!(player instanceof final ServerPlayer serverPlayer)) {
                return;
            }
            final ServerLevel level = (ServerLevel) context.level();
            final UUID playerId = player.getUUID();
            if (!MateSelectionRegistry.inMode(playerId)) {
                return;
            }

            if (this.undo) {
                MateSelectionRegistry.undo(playerId);
                feedback(serverPlayer, MateFeedbackPayload.Status.PENDING, "",
                        null, MateSelectionRegistry.pending(playerId), false);
                return;
            }
            if (this.ref == null) {
                return;
            }

            final List<MateRef> before = MateSelectionRegistry.pending(playerId);
            // 同一端点了两次 / 点到同一个结构 → 直接拒绝，不进入待选
            if (!before.isEmpty() && before.get(before.size() - 1).sameBody(this.ref)) {
                feedback(serverPlayer, MateFeedbackPayload.Status.REJECTED, "mate.error.same_body",
                        null, before, false);
                return;
            }

            MateSelectionRegistry.add(playerId, this.ref);
            final List<MateRef> pending = MateSelectionRegistry.pending(playerId);
            if (pending.size() < 2) {
                feedback(serverPlayer, MateFeedbackPayload.Status.PENDING, "",
                        null, pending, false);
                return;
            }

            // 点满两端：**不再自动成配**，而是把两个待选原样留着，让客户端弹出类型选项让玩家确认
            // （玩家要求「选定好两个元素后应该自动弹出配合类型选项，让玩家确认配合类型」）。
            // 客户端看到 pending 有两条就会在树里展开类型选项，选定后发 Action.CREATE。
            feedback(serverPlayer, MateFeedbackPayload.Status.PENDING, "mate.prompt.confirm_type",
                    null, pending, false);
        }
    }

    // ============ C2S：对已有配合的操作 ============

    public static final class MateCommandPayload implements CustomPacketPayload {
        public static final Type<MateCommandPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "mate_command"));
        public static final StreamCodec<ByteBuf, MateCommandPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeByte(payload.action.ordinal());
                    UUID_CODEC.encode(buf, payload.mateId);
                    buf.writeDouble(payload.value);
                    buf.writeDouble(payload.value2);
                    buf.writeBoolean(payload.flag);
                    STRING_CODEC.encode(buf, payload.text == null ? "" : payload.text);
                    STRING_CODEC.encode(buf, payload.text2 == null ? "" : payload.text2);
                },
                buf -> new MateCommandPayload(
                        Action.values()[buf.readByte()],
                        UUID_CODEC.decode(buf),
                        buf.readDouble(),
                        buf.readDouble(),
                        buf.readBoolean(),
                        STRING_CODEC.decode(buf),
                        STRING_CODEC.decode(buf)));

        public enum Action {
            /** 删除一条配合。 */
            REMOVE,
            /** 改距离/角度。 */
            SET_VALUE,
            /** 切换同向/反向对齐。 */
            SET_FLIP,
            /** 换配合类型（等价于删掉重建，会重新吸附）。 */
            CHANGE_TYPE,
            /** 改名（空串 = 恢复自动名字）。 */
            RENAME,
            /** 用玩家在界面里确认的类型，把当前待选的两端成配。 */
            CREATE
        }

        private final Action action;
        private final UUID mateId;
        private final double value;
        /** 区间类型的第二个端点（上限）；非区间类型忽略。 */
        private final double value2;
        private final boolean flag;
        /** 随 {@link Action} 解释的文本：{@code CHANGE_TYPE} 是类型 id，{@code RENAME} 是新名字。 */
        @Nullable
        private final String text;
        /** 第二个文本：目前只有 {@code CREATE} 用（{@code text} 放类型 id，这里放自定义名）。 */
        @Nullable
        private final String text2;

        private MateCommandPayload(final Action action, final UUID mateId, final double value,
                                   final double value2, final boolean flag, @Nullable final String text) {
            this(action, mateId, value, value2, flag, text, null);
        }

        private MateCommandPayload(final Action action, final UUID mateId, final double value,
                                   final double value2, final boolean flag, @Nullable final String text,
                                   @Nullable final String text2) {
            this.action = action;
            this.mateId = mateId;
            this.value = value;
            this.value2 = value2;
            this.flag = flag;
            this.text = text == null || text.isEmpty() ? null : text;
            this.text2 = text2 == null || text2.isEmpty() ? null : text2;
        }

        public static MateCommandPayload remove(final UUID mateId) {
            return new MateCommandPayload(Action.REMOVE, mateId, 0, 0, false, null);
        }

        public static MateCommandPayload setValue(final UUID mateId, final double value) {
            return new MateCommandPayload(Action.SET_VALUE, mateId, value, value, false, null);
        }

        /** 区间类型（限制）：一次改两个端点。 */
        public static MateCommandPayload setRange(final UUID mateId, final double min, final double max) {
            return new MateCommandPayload(Action.SET_VALUE, mateId,
                    Math.min(min, max), Math.max(min, max), false, null);
        }

        public static MateCommandPayload setFlip(final UUID mateId, final boolean flip) {
            return new MateCommandPayload(Action.SET_FLIP, mateId, 0, 0, flip, null);
        }

        public static MateCommandPayload changeType(final UUID mateId, final String typeId) {
            return new MateCommandPayload(Action.CHANGE_TYPE, mateId, 0, 0, false, typeId);
        }

        /** 改名；{@code name} 为空 = 清掉自定义名，回到自动名字。 */
        public static MateCommandPayload rename(final UUID mateId, @Nullable final String name) {
            return new MateCommandPayload(Action.RENAME, mateId, 0, 0, false, name);
        }

        /** 用玩家确认的类型，把待选的两端成配（{@code mateId} 不用，占位）。 */
        public static MateCommandPayload create(final String typeId) {
            return new MateCommandPayload(Action.CREATE, new UUID(0L, 0L), 0, 0, false, typeId);
        }

        /**
         * 用玩家在**创建页**里填好的整套草稿把待选两端成配。
         *
         * <p>「创建页就是编辑页」要求创建时填的数值/区间/翻转/名字都算数，
         * 所以这里把它们一并带上（{@code text} 是类型 id，{@code text2} 是自定义名）。
         */
        public static MateCommandPayload create(final String typeId, final double value, final double value2,
                                                final boolean flip, @Nullable final String name) {
            return new MateCommandPayload(Action.CREATE, new UUID(0L, 0L), value, value2, flip, typeId, name);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            final Player player = context.player();
            // 不踢人：打开着配合界面时玩家可能切走手杖，那时按钮仍然可点。
            // 真正的权限在下面按「创建者本人」校验，忽略越界的包不会造成任何越权。
            if (!PhysicsStaffItem.isHolding(player)) {
                return;
            }
            if (!(player instanceof final ServerPlayer serverPlayer)) {
                return;
            }
            final ServerLevel level = (ServerLevel) context.level();
            // CREATE 针对的是「还没建出来的配合」，必须先于所有权检查处理
            if (this.action == Action.CREATE) {
                createFromPending(level, serverPlayer);
                return;
            }
            final Mate existing = MateRegistry.byId(level, this.mateId);
            if (existing == null) {
                return;
            }
            // 只有创建者能改自己的配合
            if (!existing.owner().equals(player.getUUID())) {
                feedback(serverPlayer, MateFeedbackPayload.Status.REJECTED, "mate.error.not_owner",
                        null, List.of(), false);
                return;
            }

            boolean changed = switch (this.action) {
                case REMOVE -> MateRegistry.remove(level, this.mateId);
                case SET_VALUE -> MateRegistry.updateValue(level, this.mateId, this.value, this.value2);
                case SET_FLIP -> MateRegistry.updateFlip(level, this.mateId, this.flag);
                case CHANGE_TYPE -> changeType(level, serverPlayer);
                case RENAME -> MateRegistry.rename(level, this.mateId, this.text);
                case CREATE -> false;   // 上面已单独处理，不会走到这里
            };
            if (changed) {
                broadcast(level);
            }
            feedback(serverPlayer,
                    changed ? MateFeedbackPayload.Status.UPDATED : MateFeedbackPayload.Status.REJECTED,
                    changed ? "" : "mate.error.cannot_change",
                    null, MateSelectionRegistry.pending(player.getUUID()), false);
        }

        /**
         * 用玩家在界面里确认的类型，把当前待选的两端成配。
         *
         * <p>这是「选满两端 → 弹出类型选项 → 玩家确认」这条流程的最后一步：服务端一直留着
         * 那两条待选（{@link MateSelectionRegistry}），所以这里只要按类型建出来即可。
         */
        private void createFromPending(final ServerLevel level, final ServerPlayer player) {
            final UUID playerId = player.getUUID();
            if (!MateSelectionRegistry.inMode(playerId)) {
                return;
            }
            final List<MateRef> pending = MateSelectionRegistry.pending(playerId);
            if (pending.size() < 2 || this.text == null) {
                return;
            }
            final MateType type = MateType.byId(this.text);
            if (type == null) {
                return;
            }
            final MateRef refA = pending.get(0);
            final MateRef refB = pending.get(1);
            // 创建页填的整套草稿都算数：数值按类型的取值范围夹一下，不需要数值的类型忽略它
            final double wanted = type.needsValue()
                    ? Math.clamp(this.value, type.minValue(), type.maxValue())
                    : type.defaultValue();
            final MateRegistry.Result result = MateRegistry.create(
                    level, player, type, refA, refB, wanted, this.flag);
            if (result.ok() && result.mate() != null) {
                final UUID newId = result.mate().id();
                // 区间类型：创建只带一个数值，第二个端点补一次
                if (type.needsSecondValue()) {
                    MateRegistry.updateValue(level, newId,
                            Math.min(wanted, this.value2), Math.max(wanted, this.value2));
                }
                if (this.text2 != null) {
                    MateRegistry.rename(level, newId, this.text2);
                }
            }
            if (result.ok()) {
                MateSelectionRegistry.clear(playerId);
                broadcast(level);
                feedback(player, MateFeedbackPayload.Status.CREATED, "",
                        result.mate() == null ? null : result.mate().id(), List.of(), true);
            } else {
                // 冲突：不清空待选，玩家可以改选别的类型，或者 Shift+右键撤掉一端
                feedback(player, MateFeedbackPayload.Status.REJECTED,
                        result.detail() == null ? "mate.error.internal" : result.detail(),
                        null, pending, false);
            }
        }

        /** 换类型：删掉旧的、按同样的两端重建（会用新类型的默认数值并重新吸附）。 */
        private boolean changeType(final ServerLevel level, final ServerPlayer player) {
            final Mate old = MateRegistry.byId(level, this.mateId);
            if (old == null || this.text == null) {
                return false;
            }
            final MateType type = MateType.byId(this.text);
            if (type == null || type == old.type()) {
                return false;
            }
            MateRegistry.remove(level, this.mateId);
            // 同样自动挑对齐方向：换类型不该让结构突然翻 180°
            final MateRegistry.Result result = MateRegistry.createAuto(
                    level, player, type, old.a(), old.b(), type.defaultValue());
            if (!result.ok() && player != null) {
                // 换类型失败就把原来的放回去，避免「改一下类型配合就没了」
                MateRegistry.create(level, player, old.type(), old.a(), old.b(), old.value(), old.flip());
            }
            return result.ok();
        }
    }

    // ============ S2C：同步某维度的全部配合 ============

    public static final class SyncMatesPayload implements CustomPacketPayload {
        public static final Type<SyncMatesPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "mate_sync"));
        public static final StreamCodec<ByteBuf, SyncMatesPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    DIMENSION_CODEC.encode(buf, payload.dimension);
                    MATE_LIST_CODEC.encode(buf, payload.mates);
                },
                buf -> new SyncMatesPayload(DIMENSION_CODEC.decode(buf), MATE_LIST_CODEC.decode(buf)));

        private final ResourceLocation dimension;
        private final List<Mate> mates;

        public SyncMatesPayload(final ResourceLocation dimension, final List<Mate> mates) {
            this.dimension = dimension;
            this.mates = new ArrayList<>(mates);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            MateClientState.setMates(this.dimension, this.mates);
        }
    }

    // ============ S2C：操作结果反馈 ============

    public static final class MateFeedbackPayload implements CustomPacketPayload {
        public static final Type<MateFeedbackPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(SablestopNow.MOD_ID, "mate_feedback"));

        public enum Status {
            /** 已进入配合模式。 */
            MODE_ON,
            /** 已退出配合模式。 */
            MODE_OFF,
            /** 已记录一端（还没满）。 */
            PENDING,
            /** 成配成功。 */
            CREATED,
            /** 被拒绝（冲突/不合法），带 message 键。 */
            REJECTED,
            /** 对已有配合的修改生效。 */
            UPDATED
        }

        public static final StreamCodec<ByteBuf, MateFeedbackPayload> CODEC = StreamCodec.of(
                (buf, payload) -> {
                    buf.writeByte(payload.status.ordinal());
                    STRING_CODEC.encode(buf, payload.message);
                    buf.writeBoolean(payload.mateId != null);
                    if (payload.mateId != null) {
                        UUID_CODEC.encode(buf, payload.mateId);
                    }
                    REF_LIST_CODEC.encode(buf, payload.pending);
                    buf.writeBoolean(payload.openGui);
                },
                buf -> {
                    final Status status = Status.values()[buf.readByte()];
                    final String message = STRING_CODEC.decode(buf);
                    final UUID mateId = buf.readBoolean() ? UUID_CODEC.decode(buf) : null;
                    final List<MateRef> pending = REF_LIST_CODEC.decode(buf);
                    final boolean openGui = buf.readBoolean();
                    return new MateFeedbackPayload(status, message, mateId, pending, openGui);
                });

        private final Status status;
        private final String message;
        @Nullable
        private final UUID mateId;
        private final List<MateRef> pending;
        private final boolean openGui;

        public MateFeedbackPayload(final Status status, final String message, @Nullable final UUID mateId,
                                   final List<MateRef> pending, final boolean openGui) {
            this.status = status;
            this.message = message;
            this.mateId = mateId;
            this.pending = new ArrayList<>(pending);
            this.openGui = openGui;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        public void handle(final PacketContext context) {
            MateClientState.onFeedback(this.status, this.message, this.mateId, this.pending, this.openGui);
        }
    }

    // ============ 辅助 ============

    private static void feedback(final ServerPlayer player, final MateFeedbackPayload.Status status,
                                 final String message, @Nullable final UUID mateId,
                                 final List<MateRef> pending, final boolean openGui) {
        VeilPacketManager.player(player).sendPacket(
                new MateFeedbackPayload(status, message, mateId, pending, openGui));
    }

    /** 把某维度的配合快照广播给所有人（客户端自行按维度过滤）。 */
    public static void broadcast(final ServerLevel level) {
        if (level.getServer() == null) {
            return;
        }
        VeilPacketManager.all(level.getServer()).sendPacket(
                new SyncMatesPayload(level.dimension().location(), MateRegistry.of(level)));
    }

    /** 玩家加入时补发所有维度的配合。 */
    public static void sendAllTo(final ServerPlayer player) {
        if (player.server == null) {
            return;
        }
        for (final ServerLevel level : player.server.getAllLevels()) {
            VeilPacketManager.player(player).sendPacket(
                    new SyncMatesPayload(level.dimension().location(), MateRegistry.of(level)));
        }
    }

    /** 供 {@code StaffEnhanceServerEvents} 之类的地方拿到维度键（当前未使用，保留给命令）。 */
    public static ResourceKey<Level> dimensionOf(final String id) {
        return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, ResourceLocation.parse(id));
    }
}
