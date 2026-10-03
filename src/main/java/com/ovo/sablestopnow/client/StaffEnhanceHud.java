package com.ovo.sablestopnow.client;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.SablestopNowConfig;
import com.ovo.sablestopnow.client.gui.lib.Container;
import com.ovo.sablestopnow.client.gui.lib.Layouts;
import com.ovo.sablestopnow.client.gui.lib.Ui;
import com.ovo.sablestopnow.client.gui.lib.UiDock;
import com.ovo.sablestopnow.client.gui.lib.UiLayer;
import com.ovo.sablestopnow.client.gui.lib.UiSettings;
import com.ovo.sablestopnow.client.hud.HudLinesWidget;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 物理手杖增强 —— 旧的左上角常驻 HUD（多选模式状态 / 穿透层数 / 已选数量 / 框选角点）。
 *
 * <h2>改造说明</h2>
 * 这个 HUD 以前是「每帧直接往 {@code GuiGraphics} 上画一堆字符串」。现在改成 GUI 库里的一个
 * {@link UiLayer}（纯展示层：不吃输入、不占位），内容由 {@link HudLinesWidget} 按帧现算。
 *
 * <p>这么做换来三件事：
 * <ol>
 *   <li>行高与配色跟其它界面统一（库里文本按 {@code theme.fontScale} 缩放，比原版 9px 矮）；</li>
 *   <li>位置可以交给 {@link UiDock} 自动避让模型模式的左/上边栏，不再和别的界面叠在一起；</li>
 *   <li>库的全局动画开关对它有统一语义（本 HUD 无动画，但不会出现「一个 HUD 不听开关」的例外）。</li>
 * </ol>
 *
 * <p>显示条件与原实现完全一致：手杖增强开启 + 手持手杖 + <b>没有</b>启用新控制逻辑
 * （启用时左上角交给 {@link StaffControlHud}），且「有事情正在做」时才出现。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT)
public final class StaffEnhanceHud {

    private static final String LAYER = "staff.enhance.hud";
    private static final int MARGIN = 6;

    private StaffEnhanceHud() {
    }

    @SubscribeEvent
    public static void onRenderGui(final RenderGuiEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !StaffEnhanceClientHandler.isEnabled()
                || !PhysicsStaffItem.isHolding(mc.player) || SablestopNowConfig.isNewControlScheme()) {
            Ui.close(LAYER);
            return;
        }
        UiLayer layer = Ui.layer(LAYER);
        if (layer == null) {
            layer = Ui.open(UiLayer.of(LAYER));
            // 纯展示：绝不能吞掉鼠标/键盘，也不占 HUD 避让位（它是被避让的那一方）
            layer.hud = true;
            layer.consumeInput = false;
            layer.consumeKeys = false;
            layer.reserveSpace = false;
            layer.closeOnEscape = false;
            layer.root.layout = Layouts.absolute();
            layer.root.add(new HudLinesWidget(StaffEnhanceHud::lines));
        }
        place(layer);
    }

    /** 用库的避让工具找位置：被模型模式的面板压住时自动下移。 */
    private static void place(final UiLayer layer) {
        final Container root = layer.root;
        int width = 0;
        int height = 0;
        for (final var child : root.children) {
            width = Math.max(width, child.w);
            height = Math.max(height, child.h);
        }
        // 首帧尺寸还是 0：UiDock 会退回边距位置，下一帧就正了
        final int[] pos = UiDock.place(Math.max(width, 90), Math.max(height, 12),
                UiDock.Corner.TOP_LEFT, MARGIN);
        root.padding(pos[0], pos[1], 0, 0);
    }

    // ============ 内容 ============

    private static List<HudLinesWidget.Line> lines() {
        final List<HudLinesWidget.Line> out = new ArrayList<>();
        final boolean multi = StaffEnhanceClientHandler.isMultiSelect();
        final int regionStep = StaffEnhanceClientHandler.getRegionStep();
        final boolean armed = !multi && !StaffEnhanceClientHandler.getSelected().isEmpty();
        final boolean dragging = StaffEnhanceClientHandler.isGroupDragging();
        final boolean hovering = StaffEnhanceClientHandler.getHoverBody() != null;
        final boolean viewLock = StaffEnhanceClientHandler.isViewLockActive();
        final boolean scaling = StaffEnhanceClientHandler.isScaling();

        // 只要手杖「有事情正在做」就显示（与原实现的判断一致）
        if (!multi && regionStep == 0 && !armed && !dragging && !hovering && !viewLock && !scaling
                && StaffEnhanceClientHandler.getPenetration() == 0) {
            return out;
        }

        if (multi) {
            add(out, "sablestopnow.staff.hud.multiselect", ChatFormatting.AQUA);
        } else if (regionStep != 0) {
            add(out, regionStep == 1 ? "sablestopnow.staff.hud.region_first"
                    : "sablestopnow.staff.hud.region_second", ChatFormatting.GREEN);
        } else if (dragging) {
            add(out, "sablestopnow.staff.hud.controlling", ChatFormatting.LIGHT_PURPLE);
        } else if (armed) {
            add(out, "sablestopnow.staff.hud.armed", ChatFormatting.GOLD);
        }

        if (multi || StaffEnhanceClientHandler.getPenetration() > 0) {
            add(out, "sablestopnow.staff.hud.penetration", ChatFormatting.GOLD,
                    StaffEnhanceClientHandler.getPenetration());
        }
        if (!StaffEnhanceClientHandler.getSelected().isEmpty()) {
            add(out, "sablestopnow.staff.hud.selected", ChatFormatting.GOLD,
                    StaffEnhanceClientHandler.getSelected().size());
        }

        if (hovering) {
            final String name = StaffEnhanceClientHandler.getHoverName();
            if (name != null) {
                add(out, "sablestopnow.staff.hud.aim_name", ChatFormatting.WHITE, name);
            } else {
                add(out, "sablestopnow.staff.hud.aim", ChatFormatting.WHITE);
            }
            final var hoverId = StaffEnhanceClientHandler.getHoverBody();
            if (hoverId != null) {
                // 瞄准的物理结构被缩放过时直接显示倍率
                final float aimedScale = StaffEnhanceClientHandler.scaleOf(hoverId);
                if (Math.abs(aimedScale - 1.0f) > 1.0e-3f) {
                    add(out, "sablestopnow.staff.hud.aim_scale", ChatFormatting.AQUA,
                            String.format("%.2f", aimedScale));
                }
                final String owner = StaffEnhanceClientHandler.ownerNameOf(hoverId);
                if (owner != null) {
                    add(out, "sablestopnow.staff.hud.owner", ChatFormatting.LIGHT_PURPLE, owner);
                }
            }
        }

        if (scaling) {
            add(out, "sablestopnow.staff.hud.scale", ChatFormatting.AQUA,
                    String.format("%.2f", StaffEnhanceClientHandler.getScaleFactor()));
        }
        if (viewLock) {
            final String locked = StaffEnhanceClientHandler.getViewLockName();
            if (locked != null) {
                add(out, "sablestopnow.staff.hud.view_lock_name", ChatFormatting.AQUA, locked);
            } else {
                add(out, "sablestopnow.staff.hud.view_lock", ChatFormatting.AQUA);
            }
        }

        if (regionStep != 0) {
            add(out, "sablestopnow.staff.hud.region_distance", ChatFormatting.GREEN,
                    String.format("%.1f", StaffEnhanceClientHandler.getRegionDistance()));
            final var first = StaffEnhanceClientHandler.getRegionFirst();
            if (first != null) {
                add(out, "sablestopnow.staff.hud.box_a", ChatFormatting.GREEN,
                        first.getX(), first.getY(), first.getZ());
            }
            final var cursor = StaffEnhanceClientHandler.getRegionCursor();
            if (cursor != null) {
                add(out, regionStep == 1 ? "sablestopnow.staff.hud.region_cursor"
                        : "sablestopnow.staff.hud.box_b", ChatFormatting.GREEN,
                        cursor.getX(), cursor.getY(), cursor.getZ());
            }
            if (regionStep == 2) {
                add(out, "sablestopnow.staff.hud.region_candidates", ChatFormatting.YELLOW,
                        StaffEnhanceClientHandler.getRegionCandidates().size());
            }
        }
        return out;
    }

    private static void add(final List<HudLinesWidget.Line> out, final String key,
                            final ChatFormatting format, final Object... args) {
        out.add(new HudLinesWidget.Line(Component.translatable(key, args), format.getColor()));
    }

    /** 库的调试开关打开时把控件边界画出来（库自己处理，这里只是保留入口）。 */
    public static boolean debugBounds() {
        return UiSettings.debugBounds;
    }
}
