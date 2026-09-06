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
        final int boxStep = StaffEnhanceClientHandler.getBoxStep();
        final boolean armed = !multi && !StaffEnhanceClientHandler.getSelected().isEmpty();
        if (!multi && boxStep == 0 && !armed) {
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
        } else if (armed) {
            lines.add(Component.translatable("sablestopnow.staff.hud.armed"));
            colors.add(ChatFormatting.GOLD.getColor());
        }

        lines.add(Component.translatable("sablestopnow.staff.hud.penetration", StaffEnhanceClientHandler.getPenetration()));
        colors.add(ChatFormatting.GOLD.getColor());

        lines.add(Component.translatable("sablestopnow.staff.hud.selected", StaffEnhanceClientHandler.getSelected().size()));
        colors.add(ChatFormatting.GOLD.getColor());

        if (boxStep != 0) {
            final var first = StaffEnhanceClientHandler.getBoxFirst();
            if (first != null) {
                lines.add(Component.translatable("sablestopnow.staff.hud.box_a", first.getX(), first.getY(), first.getZ()));
                colors.add(ChatFormatting.GREEN.getColor());
            }
            final var second = StaffEnhanceClientHandler.getBoxStep() == 2
                    ? StaffEnhanceClientHandler.getBoxSecond()
                    : StaffEnhanceClientHandler.getBoxPreview();
            if (second != null) {
                lines.add(Component.translatable("sablestopnow.staff.hud.box_b", second.getX(), second.getY(), second.getZ()));
                colors.add(ChatFormatting.GREEN.getColor());
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
