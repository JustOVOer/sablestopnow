package com.ovo.sablestopnow.mixin;

import com.ovo.sablestopnow.ForceGroupCache;
import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.SablestopNowConfig;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(QueuedForceGroup.class)
public class QueuedForceGroupMixin {

    @Shadow private ServerSubLevel subLevel;

    @Inject(method = "applyAndRecordPointForce", at = @At("HEAD"), cancellable = true, remap = false)
    private void onApplyAndRecordPointForce(Vector3dc point, Vector3dc force, CallbackInfo ci) {
        // 获取力组ID（如果缓存中有）
        QueuedForceGroup self = (QueuedForceGroup) (Object) this;
        ResourceLocation groupId = ForceGroupCache.get(self);
        String groupIdStr = groupId != null ? groupId.toString() : "unknown";

        // 如果未启用超限力过滤，直接记录并返回
        if (!SablestopNowConfig.INSTANCE.filterExcessiveForce.get()) {
            recordForce(force, false, groupIdStr);
            return;
        }

        // 如果缓存中没有找到ID，则无法进行过滤，记录并警告
        if (groupId == null) {
            SablestopNow.LOGGER.warn("ForceGroup ID not found in cache, skipping force limit");
            recordForce(force, false, groupIdStr);
            return;
        }

        double magnitude = force.length();
        double threshold = SablestopNowConfig.INSTANCE.forceThreshold.get();
        List<? extends String> excludedGroups = SablestopNowConfig.INSTANCE.excludedGroups.get();

        boolean filtered = false;
        if (magnitude > threshold && !excludedGroups.contains(groupIdStr)) {
            filtered = true;
            SablestopNow.LOGGER.info("Force from group {} (magnitude: {}) exceeds threshold, discarding!", groupIdStr, magnitude);
            ci.cancel();

            if (SablestopNowConfig.INSTANCE.autoPauseOnFilter.get()) {
                ServerLevel level = subLevel.getLevel();
                if (level != null) {
                    SubLevelPhysicsSystem physSystem = SubLevelPhysicsSystem.get(level);
                    if (physSystem != null) {
                        physSystem.setPaused(true);
                        SablestopNow.LOGGER.info("Auto-pause triggered.");

                        // 发送消息给所有玩家
                        MinecraftServer server = level.getServer();
                        if (server != null) {
                            Component prefix = Component.translatable("sablestopnow.auto_pause.prefix")
                                    .withStyle(ChatFormatting.RED);
                            Component message = Component.translatable("sablestopnow.auto_pause.message")
                                    .withStyle(ChatFormatting.WHITE);
                            Component hintBefore = Component.translatable("sablestopnow.auto_pause.hint_before")
                                    .withStyle(ChatFormatting.GRAY);
                            Component command = Component.translatable("sablestopnow.auto_pause.command")
                                    .withStyle(style -> style.withColor(ChatFormatting.GOLD)
                                            .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/sablesn forces"))
                                            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                    Component.translatable("sablestopnow.auto_pause.command_hover"))));
                            Component hintAfter = Component.translatable("sablestopnow.auto_pause.hint_after")
                                    .withStyle(ChatFormatting.GRAY);

                            Component fullMessage = Component.empty()
                                    .append(prefix)
                                    .append(message)
                                    .append(hintBefore)
                                    .append(command)
                                    .append(hintAfter);

                            server.getPlayerList().broadcastSystemMessage(fullMessage, false);
                        }
                    }
                }
                SablestopNowConfig.INSTANCE.autoPauseOnFilter.set(false);
                SablestopNowConfig.save();
            }
        }

        recordForce(force, filtered, groupIdStr);
    }

    @Unique
    private void recordForce(Vector3dc force, boolean filtered, String groupIdStr) {
        String targetId = subLevel != null ? subLevel.getUniqueId().toString() : "unknown";
        Vector3d position = subLevel != null
                ? new Vector3d(subLevel.logicalPose().position())
                : new Vector3d(0, 0, 0);
        ServerLevel level = subLevel != null ? subLevel.getLevel() : null;

        Component displayName;
        if (groupIdStr != null) {
            ResourceLocation id = ResourceLocation.tryParse(groupIdStr);
            if (id != null) {
                ForceGroup fg = ForceGroups.REGISTRY.get(id);
                displayName = fg != null ? fg.name() : Component.literal(groupIdStr);
            } else {
                displayName = Component.literal(groupIdStr);
            }
        } else {
            displayName = Component.literal("unknown");
        }

        SablestopNow.recordForce(force.length(), displayName, groupIdStr != null ? groupIdStr : "unknown", targetId, filtered, position, level);
    }
}