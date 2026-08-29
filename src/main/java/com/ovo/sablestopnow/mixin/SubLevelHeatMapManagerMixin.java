package com.ovo.sablestopnow.mixin;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.SablestopNowConfig;
import com.ovo.sablestopnow.SplitConfirmationManager;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.heat.HeatMapPropagationState;
import dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Mixin(SubLevelHeatMapManager.class)
public class SubLevelHeatMapManagerMixin {

    @Shadow private Long2IntOpenHashMap subLevelSplits;
    @Shadow private ObjectList<BlockPos> floodfill;
    @Shadow private ObjectList<BlockPos> removed;
    @Shadow private ObjectList<BlockPos> newStarts;
    @Shadow private IntArrayList splitIndexMap;
    @Shadow private HeatMapPropagationState state;
    @Shadow private boolean splitComplete;
    @Shadow private ServerSubLevel subLevel;
    @Shadow private void split() {}

    @Unique
    public void sablestopnow$clearSplitData() {
        this.subLevelSplits.clear();
        this.splitIndexMap.clear();
        this.splitIndexMap.add(0);
        this.floodfill.clear();
        this.removed.clear();
        this.newStarts.clear();
        this.state = HeatMapPropagationState.CLEARING;
        this.splitComplete = true;
    }

    @Redirect(
            method = "step",
            at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/plot/heat/SubLevelHeatMapManager;split()V", ordinal = 0, remap = false),
            remap = false
    )
    private void sablestopnow$redirectSplit1(SubLevelHeatMapManager manager) {
        this.sablestopnow$handleSplit();
    }

    @Redirect(
            method = "step",
            at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/plot/heat/SubLevelHeatMapManager;split()V", ordinal = 1, remap = false),
            remap = false
    )
    private void sablestopnow$redirectSplit2(SubLevelHeatMapManager manager) {
        this.sablestopnow$handleSplit();
    }

    @Unique
    private void sablestopnow$handleSplit() {
        if (!SablestopNowConfig.INSTANCE.requireConfirmationBeforeSplit.get()) {
            this.split();
            return;
        }

        UUID subLevelId = this.subLevel.getUniqueId();
        if (SplitConfirmationManager.hasPendingRequest(subLevelId)) {
            return;
        }

        List<BlockPos> blocks = new ArrayList<>();
        for (long l : this.subLevelSplits.keySet()) {
            blocks.add(BlockPos.of(l));
        }

        if (blocks.isEmpty()) {
            this.split();
            return;
        }

        BoundingBox3i bounds = Objects.requireNonNull(BoundingBox3i.from(blocks)).expand(1, 1, 1);
        Level level = this.subLevel.getLevel();

        SplitConfirmationManager.addPendingRequest(subLevelId, (ServerLevel) level, blocks, bounds);

        Component prefix = Component.translatable("sablestopnow.split.prefix").withStyle(ChatFormatting.GOLD);
        Component message = Component.translatable("sablestopnow.split.ask_confirm")
                .withStyle(ChatFormatting.WHITE);
        Component confirmCmd = Component.literal("/sablesn confirm")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/sablesn confirm"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("sablestopnow.split.confirm_hover"))));
        Component denyCmd = Component.literal("/sablesn deny")
                .withStyle(style -> style.withColor(ChatFormatting.RED)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/sablesn deny"))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("sablestopnow.split.deny_hover"))));
        Component full = Component.empty()
                .append(prefix)
                .append(message)
                .append(Component.literal(" "))
                .append(confirmCmd)
                .append(Component.literal(" 或 "))
                .append(denyCmd);

        level.getServer().getPlayerList().broadcastSystemMessage(full, false);
        SablestopNow.LOGGER.info("Split pending for sub-level {}, awaiting confirmation.", subLevelId);
    }
}