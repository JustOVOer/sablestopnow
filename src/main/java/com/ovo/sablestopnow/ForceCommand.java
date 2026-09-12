package com.ovo.sablestopnow;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.ovo.sablestopnow.server.StaffEnhanceServer;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3d;

import java.util.List;
import java.util.stream.Collectors;

public class ForceCommand {
    private static final int PAGE_SIZE = 10;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {

        dispatcher.register(
                Commands.literal("sablesn")
                        .then(Commands.literal("forces")
                                .executes(ctx -> listForces(ctx, false, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> listForces(ctx, false, IntegerArgumentType.getInteger(ctx, "page")))
                                )
                                .then(Commands.literal("filtered")
                                        .executes(ctx -> listForces(ctx, true, 1))
                                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                                .executes(ctx -> listForces(ctx, true, IntegerArgumentType.getInteger(ctx, "page")))
                                        )
                                )
                        )
                        .then(Commands.literal("confirm")
                                .executes(ctx -> confirmSplit(ctx))
                        )
                        .then(Commands.literal("deny")
                                .executes(ctx -> denySplit(ctx))
                        )
                        .then(Commands.literal("tick")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.argument("steps", IntegerArgumentType.integer(1))
                                        .executes(ForceCommand::stepTicks))
                        )
                        .then(Commands.literal("owner")
                                .requires(src -> src.hasPermission(2))
                                .then(Commands.literal("list")
                                        .executes(ForceCommand::listOwners))
                                .then(Commands.literal("clear")
                                        .then(Commands.argument("player", com.mojang.brigadier.arguments.StringArgumentType.word())
                                                .executes(ForceCommand::clearOwner))
                                        .then(Commands.literal("all")
                                                .executes(ForceCommand::clearAllOwners)))
                        )
                        .then(Commands.literal("Superliminal")
                                .executes(ForceCommand::toggleSuperliminal))
                        .then(Commands.literal("superliminal")
                                .executes(ForceCommand::toggleSuperliminal))

        );

    }

    /**
     * /sablesn Superliminal：切换彩蛋（超阈限空间：拖拽时保持物体屏幕大小不变并放到视线所指平面）。
     *
     * <p>⚠ <b>未完成（WIP）</b>：目前只打通了「抓起 → BeginScale → 绝对放置」这条链路，
     * 屏幕尺寸恒定、落点贴合与松手放下的手感都尚未验收，暂不作为对外功能宣传。</p>
     */
    private static int toggleSuperliminal(final CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        if (!(source.getEntity() instanceof final net.minecraft.server.level.ServerPlayer player)) {
            source.sendFailure(Component.translatable("sablestopnow.command.superliminal.players_only"));
            return 0;
        }
        final boolean on = com.ovo.sablestopnow.server.StaffSuperliminalState.toggle(player.getUUID());
        foundry.veil.api.network.VeilPacketManager.player(player)
                .sendPacket(new com.ovo.sablestopnow.network.StaffEnhanceNetworking.SyncSuperliminalPayload(on));
        player.displayClientMessage(Component.translatable(on
                ? "sablestopnow.command.superliminal.on"
                : "sablestopnow.command.superliminal.off"), false);
        return on ? 1 : 0;
    }

    /** /sablesn owner list：列出当前维度所有被设置所有权的物理结构。 */
    private static int listOwners(final CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        if (!(source.getLevel() instanceof final ServerLevel level)) {
            source.sendFailure(Component.translatable("sablestopnow.command.owner.no_level"));
            return 0;
        }
        final com.ovo.sablestopnow.server.StaffOwnershipData data =
                com.ovo.sablestopnow.server.StaffOwnershipData.get(level);
        if (data.allOwners().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("sablestopnow.command.owner.empty"), false);
            return 0;
        }
        int count = 0;
        for (final java.util.Map.Entry<java.util.UUID, java.util.UUID> entry : data.allOwners().entrySet()) {
            final String subName;
            final var sub = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level).getSubLevel(entry.getKey());
            subName = sub != null && sub.getName() != null ? sub.getName() : entry.getKey().toString();
            final String ownerName = data.ownerNameOf(entry.getKey());
            source.sendSuccess(() -> Component.translatable("sablestopnow.command.owner.entry", subName, ownerName), false);
            count++;
        }
        final int total = count;
        source.sendSuccess(() -> Component.translatable("sablestopnow.command.owner.count", total), false);
        return total;
    }

    /** /sablesn owner clear <player|all>：强制清除某玩家（或全部）的所有权。 */
    private static int clearOwner(final CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        if (!(source.getLevel() instanceof final ServerLevel level)) {
            source.sendFailure(Component.translatable("sablestopnow.command.owner.no_level"));
            return 0;
        }
        final String name = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "player");
        final com.ovo.sablestopnow.server.StaffOwnershipData data =
                com.ovo.sablestopnow.server.StaffOwnershipData.get(level);
        int cleared = 0;
        for (final java.util.Map.Entry<java.util.UUID, java.util.UUID> entry : new java.util.ArrayList<>(data.allOwners().entrySet())) {
            if (name.equals(data.ownerNameOf(entry.getKey()))) {
                data.setOwner(entry.getKey(), null);
                cleared++;
            }
        }
        StaffEnhanceServer.broadcastOwnership(level);
        final int total = cleared;
        source.sendSuccess(() -> Component.translatable("sablestopnow.command.owner.cleared", total, name), true);
        return total;
    }

    /** /sablesn owner clear all。 */
    private static int clearAllOwners(final CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        if (!(source.getLevel() instanceof final ServerLevel level)) {
            source.sendFailure(Component.translatable("sablestopnow.command.owner.no_level"));
            return 0;
        }
        final com.ovo.sablestopnow.server.StaffOwnershipData data =
                com.ovo.sablestopnow.server.StaffOwnershipData.get(level);
        final int before = data.allOwners().size();
        for (final java.util.UUID id : new java.util.ArrayList<>(data.allOwners().keySet())) {
            data.setOwner(id, null);
        }
        StaffEnhanceServer.broadcastOwnership(level);
        source.sendSuccess(() -> Component.translatable("sablestopnow.command.owner.cleared_all", before), true);
        return before;
    }

    /** /sablesn tick <steps>：物理暂停时步进指定数量的物理 tick（每 tick 含 Sable 的物理子步）。 */
    private static int stepTicks(final CommandContext<CommandSourceStack> ctx) {
        final CommandSourceStack source = ctx.getSource();
        final int steps = IntegerArgumentType.getInteger(ctx, "steps");
        if (!(source.getLevel() instanceof final ServerLevel level)) {
            source.sendFailure(Component.translatable("sablestopnow.command.player_only"));
            return 0;
        }
        if (!StaffEnhanceServer.startStepping(level, steps)) {
            source.sendFailure(Component.translatable("sablestopnow.command.tick.not_paused"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("sablestopnow.command.tick.started", steps), false);
        return 1;
    }

    private static int listForces(CommandContext<CommandSourceStack> ctx, boolean onlyFiltered, int page) {
        CommandSourceStack source = ctx.getSource();
        Player player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("sablestopnow.command.player_only"));
            return 0;
        }

        // 在 listForces 中，在发送 header 之前：
        List<ForceRecord> allRecords = SablestopNow.getForceRecords();
        if (!SablestopNow.hasNewForces() && !allRecords.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("sablestopnow.force.list.old_data_hint")
                    .withStyle(ChatFormatting.GRAY), false);
        }
// 继续原有的 header 和列表显示...
        List<ForceRecord> filtered = allRecords.stream()
                .filter(r -> onlyFiltered ? r.isFiltered() : true)
                .sorted((a, b) -> Double.compare(b.getMagnitude(), a.getMagnitude()))
                .collect(Collectors.toList());

        int total = filtered.size();
        int maxPage = (total + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page < 1) page = 1;
        if (page > maxPage && total > 0) page = maxPage;

        int start = (page - 1) * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, total);

        Component header = Component.translatable(
                onlyFiltered ? "sablestopnow.force.list.title_filtered" : "sablestopnow.force.list.title_all",
                page, maxPage
        ).withStyle(ChatFormatting.GOLD);

        source.sendSuccess(() -> header, false);

        if (total == 0) {
            source.sendSuccess(() -> Component.translatable("sablestopnow.force.list.no_records").withStyle(ChatFormatting.GRAY), false);
            return 0;
        }

        for (int i = start; i < end; i++) {
            ForceRecord record = filtered.get(i);
            String magnitudeStr = String.format("%.2f", record.getMagnitude());
            Component displayName = record.getDisplayName().copy().withStyle(ChatFormatting.WHITE);
            String targetId = record.getTargetId() != null ? record.getTargetId() : "unknown";
            Vector3d pos = record.getPosition();

            String suggestCmd = String.format("/tp @p %.2f %.2f %.2f", pos.x(), pos.y(), pos.z());
            MutableComponent idComponent = Component.literal(targetId)
                    .withStyle(style -> style
                            .withColor(ChatFormatting.AQUA)
                            .withUnderlined(true)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, suggestCmd))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.translatable("sablestopnow.force.list.click_to_tp")))
                    );

            MutableComponent line = Component.literal("")
                    .append(Component.literal(String.format("[%s] ", magnitudeStr)).withStyle(ChatFormatting.YELLOW))
                    .append(displayName)
                    .append(Component.literal(" → ").withStyle(ChatFormatting.GRAY))
                    .append(idComponent)
                    .append(Component.translatable(record.isFiltered() ? "sablestopnow.force.list.filtered" : "sablestopnow.force.list.normal")
                            .withStyle(record.isFiltered() ? ChatFormatting.RED : ChatFormatting.GREEN));

            source.sendSuccess(() -> line, false);
        }

        final int currentPage = page;
        MutableComponent nav = Component.empty();
        if (currentPage > 1) {
            nav.append(Component.translatable("sablestopnow.force.list.page_prev")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.BLUE)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                    "/sablesn forces" + (onlyFiltered ? " filtered " : " ") + (currentPage - 1)))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.translatable("sablestopnow.force.list.page_prev"))))
            );
        }
        if (currentPage < maxPage) {
            if (currentPage > 1) nav.append(Component.literal(" "));
            nav.append(Component.translatable("sablestopnow.force.list.page_next")
                    .withStyle(style -> style
                            .withColor(ChatFormatting.BLUE)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                    "/sablesn forces" + (onlyFiltered ? " filtered " : " ") + (currentPage + 1)))
                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                    Component.translatable("sablestopnow.force.list.page_next"))))
            );
        }
        if (currentPage > 1 || currentPage < maxPage) {
            source.sendSuccess(() -> nav, false);
        }

        return 1;
    }
    private static int confirmSplit(CommandContext<CommandSourceStack> ctx) {
        Player player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.translatable("sablestopnow.command.player_only"));
            return 0;
        }

        ServerLevel level = ctx.getSource().getLevel();
        if (SplitConfirmationManager.confirmSplitForLevel(level)) {
            player.sendSystemMessage(Component.translatable("sablestopnow.split.confirmed").withStyle(ChatFormatting.GREEN));
            return 1;
        } else {
            player.sendSystemMessage(Component.translatable("sablestopnow.split.no_pending").withStyle(ChatFormatting.RED));
            return 0;
        }
    }
    private static int denySplit(CommandContext<CommandSourceStack> ctx) {
        Player player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.translatable("sablestopnow.command.player_only"));
            return 0;
        }

        ServerLevel level = ctx.getSource().getLevel();
        if (SplitConfirmationManager.denySplitForLevel(level)) {
            player.sendSystemMessage(Component.translatable("sablestopnow.split.denied").withStyle(ChatFormatting.YELLOW));
            return 1;
        } else {
            player.sendSystemMessage(Component.translatable("sablestopnow.split.no_pending").withStyle(ChatFormatting.RED));
            return 0;
        }
    }
}