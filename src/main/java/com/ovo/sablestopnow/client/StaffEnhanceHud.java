package com.ovo.sablestopnow.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.ovo.sablestopnow.SablestopNow;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 物理手杖增强 —— 屏幕左上角常驻 HUD：
 * 多选模式状态 / 穿透选择层数 / 已选中物理体数量 / 框选角点坐标。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT)
public final class StaffEnhanceHud {

    private static final int MARGIN = 6;
    private static final int LINE_HEIGHT = 10;

    private StaffEnhanceHud() {
    }

    @SubscribeEvent
    public static void onRenderGui(final RenderGuiEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !StaffEnhanceClientHandler.isEnabled()
                || !PhysicsStaffItem.isHolding(mc.player)) {
            return;
        }
        final boolean multi = StaffEnhanceClientHandler.isMultiSelect();
        final int regionStep = StaffEnhanceClientHandler.getRegionStep();
        final boolean armed = !multi && !StaffEnhanceClientHandler.getSelected().isEmpty();
        final boolean dragging = StaffEnhanceClientHandler.isGroupDragging();
        final boolean hovering = StaffEnhanceClientHandler.getHoverBody() != null;
        final boolean viewLock = StaffEnhanceClientHandler.isViewLockActive();
        final boolean scaling = StaffEnhanceClientHandler.isScaling();
        // 只要手杖“有事情正在做”就显示（穿透层数、悬停目标、选中队列、区域选择、整组控制、视角锁定、缩放）
        if (!multi && regionStep == 0 && !armed && !dragging && !hovering && !viewLock && !scaling
                && StaffEnhanceClientHandler.getPenetration() == 0) {
            return;
        }

        final GuiGraphics graphics = event.getGuiGraphics();
        final PoseStack pose = graphics.pose();
        pose.pushPose();

        final List<Component> lines = new ArrayList<>();
        final List<Integer> colors = new ArrayList<>();

        if (multi) {
            lines.add(Component.translatable("sablestopnow.staff.hud.multiselect"));
            colors.add(ChatFormatting.AQUA.getColor());
        } else if (regionStep != 0) {
            lines.add(Component.translatable(regionStep == 1
                    ? "sablestopnow.staff.hud.region_first"
                    : "sablestopnow.staff.hud.region_second"));
            colors.add(ChatFormatting.GREEN.getColor());
        } else if (dragging) {
            lines.add(Component.translatable("sablestopnow.staff.hud.controlling"));
            colors.add(ChatFormatting.LIGHT_PURPLE.getColor());
        } else if (armed) {
            lines.add(Component.translatable("sablestopnow.staff.hud.armed"));
            colors.add(ChatFormatting.GOLD.getColor());
        }

        if (multi || StaffEnhanceClientHandler.getPenetration() > 0) {
            lines.add(Component.translatable("sablestopnow.staff.hud.penetration", StaffEnhanceClientHandler.getPenetration()));
            colors.add(ChatFormatting.GOLD.getColor());
        }

        if (!StaffEnhanceClientHandler.getSelected().isEmpty()) {
            lines.add(Component.translatable("sablestopnow.staff.hud.selected", StaffEnhanceClientHandler.getSelected().size()));
            colors.add(ChatFormatting.GOLD.getColor());
        }

        if (hovering) {
            final String name = StaffEnhanceClientHandler.getHoverName();
            lines.add(name != null
                    ? Component.translatable("sablestopnow.staff.hud.aim_name", name)
                    : Component.translatable("sablestopnow.staff.hud.aim"));
            colors.add(ChatFormatting.WHITE.getColor());
            final var hoverId = StaffEnhanceClientHandler.getHoverBody();
            // 瞄准的物理结构如果被缩放过，直接显示它的倍率（原来的几倍）
            if (hoverId != null) {
                final float aimedScale = StaffEnhanceClientHandler.scaleOf(hoverId);
                if (Math.abs(aimedScale - 1.0f) > 1.0e-3f) {
                    lines.add(Component.translatable("sablestopnow.staff.hud.aim_scale",
                            String.format("%.2f", aimedScale)));
                    colors.add(ChatFormatting.AQUA.getColor());
                }
            }
            // 视线落在物理结构上时显示所有者（功能3）
            final String owner = hoverId != null ? StaffEnhanceClientHandler.ownerNameOf(hoverId) : null;
            if (owner != null) {
                lines.add(Component.translatable("sablestopnow.staff.hud.owner", owner));
                colors.add(ChatFormatting.LIGHT_PURPLE.getColor());
            }
        }

        if (scaling) {
            lines.add(Component.translatable("sablestopnow.staff.hud.scale",
                    String.format("%.2f", StaffEnhanceClientHandler.getScaleFactor())));
            colors.add(ChatFormatting.AQUA.getColor());
        } else if (scaling) {
            // 缩放操作中由下面的“缩放：×N”行显示，这里不重复
        }

        if (viewLock) {
            final String locked = StaffEnhanceClientHandler.getViewLockName();
            lines.add(locked != null
                    ? Component.translatable("sablestopnow.staff.hud.view_lock_name", locked)
                    : Component.translatable("sablestopnow.staff.hud.view_lock"));
            colors.add(ChatFormatting.AQUA.getColor());
        }

        if (regionStep != 0) {
            lines.add(Component.translatable("sablestopnow.staff.hud.region_distance",
                    String.format("%.1f", StaffEnhanceClientHandler.getRegionDistance())));
            colors.add(ChatFormatting.GREEN.getColor());
            final var first = StaffEnhanceClientHandler.getRegionFirst();
            if (first != null) {
                lines.add(Component.translatable("sablestopnow.staff.hud.box_a", first.getX(), first.getY(), first.getZ()));
                colors.add(ChatFormatting.GREEN.getColor());
            }
            final var cursor = StaffEnhanceClientHandler.getRegionCursor();
            if (cursor != null) {
                lines.add(Component.translatable(regionStep == 1
                                ? "sablestopnow.staff.hud.region_cursor"
                                : "sablestopnow.staff.hud.box_b",
                        cursor.getX(), cursor.getY(), cursor.getZ()));
                colors.add(ChatFormatting.GREEN.getColor());
            }
            if (regionStep == 2) {
                lines.add(Component.translatable("sablestopnow.staff.hud.region_candidates",
                        StaffEnhanceClientHandler.getRegionCandidates().size()));
                colors.add(ChatFormatting.YELLOW.getColor());
            }
        }

        int y = MARGIN;
        for (int i = 0; i < lines.size(); i++) {
            final Component line = lines.get(i);
            final int textWidth = mc.font.width(line);
            final int textColor = colors.get(i);
            graphics.fill(MARGIN - 2, y - 1, MARGIN + textWidth + 3, y + LINE_HEIGHT - 1, 0x66000000);
            graphics.drawString(mc.font, line, MARGIN, y, textColor, false);
            y += LINE_HEIGHT;
        }
        pose.popPose();
    }
}
