package com.ovo.sablestopnow;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
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

        );

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