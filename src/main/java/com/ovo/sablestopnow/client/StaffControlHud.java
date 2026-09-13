package com.ovo.sablestopnow.client;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.SablestopNowConfig;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.List;

/**
 * 新控制逻辑的 HUD（左上角）—— 参考 {@code newControl/GUI.png}：
 * <ul>
 *   <li>顶部一条蓝色「模式」标签（右边斜切），模式切换时整块先向左消失、再从左侧进入；</li>
 *   <li>下面列出该模式的<b>全部</b>功能，离当前选中项越远越虚化；选中项有一个蓝色选框 + 指向右侧的三角；</li>
 *   <li>选中项的<b>描述</b>显示在选框右侧，切换时从右向左消失、再从左向右出现；</li>
 *   <li>底部提示当前的切换方式（普通=滚轮 / 拖拽=Ctrl+滚轮）；功能激活后改为提示滚轮用途；</li>
 *   <li>左键应用功能时，一条蓝灰渐变到透明的光条沿选框边缘跑一圈；Z / X 这类持续功能会一直转到动作结束。</li>
 * </ul>
 * 配色沿用自定义设置界面那套蓝（{@code PANEL}/{@code PANEL_DARK}/{@code EDGE}）。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT)
public final class StaffControlHud {

    // 与 client/gui 的设置界面同一套配色
    private static final int PANEL = 0xF0162B52;
    private static final int PANEL_DARK = 0xF00E1D3A;
    private static final int EDGE = 0xFF8296B8;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFD6DEEA;

    private static final int MARGIN_X = 6;
    private static final int MARGIN_Y = 8;
    private static final int BANNER_H = 15;
    private static final int BANNER_SLANT = 7;
    private static final int ROW_H = 13;
    private static final int BOX_W = 86;
    private static final int LIST_GAP = 5;

    // ---- 动画状态 ----
    /** 0=完全离开(向左)，1=完全就位。 */
    private static float modeAnim = 1.0f;
    /** 0=描述已向右离开，1=描述就位。 */
    private static float descAnim = 1.0f;
    /** 选框的插值行号（缓动移动）。 */
    private static float selPos;
    /** <0=无光条；否则为 0..1 的一次性进度。 */
    private static float streak = -1.0f;
    /** 光条是否持续旋转（Z / X 激活中）。 */
    private static boolean streakLoop;
    private static long lastFrameNanos;

    private StaffControlHud() {
    }

    // ------------------------------------------------------------------
    // 由 StaffEnhanceClientHandler 调用的触发器
    // ------------------------------------------------------------------

    /** 模式变了：整块先向左消失再从左侧进入。 */
    public static void notifyModeChanged() {
        modeAnim = 0.0f;
        descAnim = 1.0f;
    }

    /** 选中项变了：描述从右向左消失（再由渲染侧自动从左进入），选框缓动到新行。 */
    public static void notifySelectionChanged() {
        descAnim = 0.0f;
    }

    /** 左键应用了某个功能：开始跑光条；{@code sustained}=持续型（Z/X）会一直转到结束。 */
    public static void notifyApplied(final boolean sustained) {
        streak = 0.0f;
        streakLoop = sustained;
    }

    /** 持续型功能结束了（区域选择取消/确认、缩放确认）。 */
    public static void notifySustainedEnded() {
        if (streakLoop) {
            streakLoop = false;
            streak = -1.0f;
        }
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderGui(final RenderGuiEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.screen != null
                || !SablestopNowConfig.isStaffEnhanceEnabled() || !SablestopNowConfig.isNewControlScheme()
                || !StaffEnhanceClientHandler.isEnabled() || !PhysicsStaffItem.isHolding(player)) {
            return;
        }

        final float dt = frameDelta();
        final StaffControl.Mode mode = StaffEnhanceClientHandler.newControlMode();
        final List<StaffControl.Fn> fns = StaffControl.functionsFor(mode);
        final int index = Math.max(0, Math.min(fns.size() - 1, StaffEnhanceClientHandler.newControlIndex()));
        final boolean functionActive = StaffEnhanceClientHandler.newControlFunctionActive();

        // 缓动推进
        modeAnim = approach(modeAnim, 1.0f, 7.0f, dt);
        descAnim = approach(descAnim, 1.0f, 9.0f, dt);
        selPos = approach(selPos, index, 14.0f, dt);
        if (streak >= 0.0f) {
            if (streakLoop && functionActive) {
                streak = (streak + dt * 1.1f) % 1.0f;
            } else {
                streak += dt * 2.2f;
                if (streak >= 1.0f) {
                    streak = -1.0f;
                }
            }
        }

        final GuiGraphics gg = event.getGuiGraphics();
        final var font = mc.font;

        // 模式切换：先向左消失(0..0.5)，再从左侧进入(0.5..1)
        final float slideOut = modeAnim < 0.5f ? (1.0f - modeAnim * 2.0f) : 0.0f;
        final float slideIn = modeAnim < 0.5f ? 1.0f : (1.0f - (modeAnim - 0.5f) * 2.0f);
        final float blockX = MARGIN_X - (slideOut + slideIn) * 46.0f;
        final float blockAlpha = Math.max(0.0f, modeAnim < 0.5f ? 1.0f - modeAnim * 2.0f : (modeAnim - 0.5f) * 2.0f);

        final int x = Math.round(blockX);
        int y = MARGIN_Y;

        // ---- 模式标签（右边缘斜切）----
        final String modeName = I18n.get(mode.nameKey);
        final int bannerW = font.width(modeName) + 14;
        drawBanner(gg, x, y, bannerW, BANNER_H, BANNER_SLANT, blockAlpha);
        gg.drawString(font, modeName, x + 6, y + 4, withAlpha(TEXT, blockAlpha), false);
        y += BANNER_H + LIST_GAP;

        // ---- 功能列表（全部，按距离虚化）----
        final int selRowY = y + Math.round(selPos * ROW_H);
        for (int i = 0; i < fns.size(); i++) {
            final int rowY = y + i * ROW_H;
            final float distance = Math.abs(i - selPos);
            final boolean isSelected = Math.abs(i - selPos) < 0.5f;
            final float rowAlpha = blockAlpha * Math.max(0.16f, 1.0f - 0.30f * distance);
            final String name = I18n.get(fns.get(i).nameKey);

            if (isSelected) {
                // 选框 + 右侧三角
                fillBox(gg, x, rowY, BOX_W, ROW_H - 2, blockAlpha);
                gg.drawString(font, name, x + 6, rowY + 3, withAlpha(TEXT, blockAlpha), false);
                drawArrow(gg, x + BOX_W + 2, rowY + (ROW_H - 2) / 2, blockAlpha);
            } else {
                gg.drawString(font, name, x + 6, rowY + 3, withAlpha(TEXT_DIM, rowAlpha), false);
            }
        }

        // ---- 选中项描述（右侧，从右消失/从左进入）----
        final float descOut = descAnim < 0.5f ? (1.0f - descAnim * 2.0f) : 0.0f;
        final float descIn = descAnim < 0.5f ? 1.0f : (1.0f - (descAnim - 0.5f) * 2.0f);
        final String desc = I18n.get(fns.get(index).descKey);
        final int descX = x + BOX_W + 14 + Math.round((descOut + descIn) * 24.0f);
        gg.drawString(font, desc, descX, selRowY + 3, withAlpha(TEXT_DIM, blockAlpha), false);

        // ---- 光条（沿选框边缘）----
        if (streak >= 0.0f) {
            drawStreak(gg, x, selRowY, BOX_W, ROW_H - 2, streak, blockAlpha);
        }

        // ---- 底部提示 ----
        int hintY = y + fns.size() * ROW_H + 3;
        final String wheelKey = fns.get(index).wheelKey;
        if (functionActive && wheelKey != null) {
            gg.drawString(font, I18n.get(wheelKey), x, hintY, withAlpha(TEXT_DIM, blockAlpha), false);
            hintY += 10;
        }
        final boolean dragMode = mode == StaffControl.Mode.DRAG;
        gg.drawString(font, I18n.get(dragMode
                ? "sablestopnow.control.hint.switch_ctrl"
                : "sablestopnow.control.hint.switch"), x, hintY, withAlpha(TEXT_DIM, blockAlpha * 0.85f), false);
    }

    // ------------------------------------------------------------------
    // 绘制辅助
    // ------------------------------------------------------------------

    private static void drawBanner(final GuiGraphics gg, final int x, final int y,
                                   final int w, final int h, final int slant, final float alpha) {
        final int fill = withAlpha(PANEL, alpha);
        final int dark = withAlpha(PANEL_DARK, alpha);
        final int edge = withAlpha(EDGE, alpha);
        for (int j = 0; j < h; j++) {
            // 右边缘从下往上向左收，形成斜切
            final int extra = slant * (h - 1 - j) / Math.max(1, h - 1);
            gg.fill(x, y + j, x + w + extra, y + j + 1, j == 0 || j == h - 1 ? dark : fill);
        }
        gg.fill(x, y, x + 1, y + h, edge);
        gg.fill(x, y, x + w, y + 1, edge);
        gg.fill(x, y + h - 1, x + w + slant, y + h, edge);
        for (int j = 0; j < h; j++) {
            final int extra = slant * (h - 1 - j) / Math.max(1, h - 1);
            gg.fill(x + w + extra - 1, y + j, x + w + extra, y + j + 1, edge);
        }
    }

    private static void fillBox(final GuiGraphics gg, final int x, final int y,
                                final int w, final int h, final float alpha) {
        gg.fill(x, y, x + w, y + h, withAlpha(PANEL, alpha));
        final int edge = withAlpha(EDGE, alpha);
        gg.fill(x, y, x + w, y + 1, edge);
        gg.fill(x, y + h - 1, x + w, y + h, edge);
        gg.fill(x, y, x + 1, y + h, edge);
        gg.fill(x + w - 1, y, x + w, y + h, edge);
    }

    private static void drawArrow(final GuiGraphics gg, final int x, final int cy, final float alpha) {
        final int color = withAlpha(EDGE, alpha);
        for (int i = 0; i < 4; i++) {
            gg.fill(x + i, cy - 3 + i, x + i + 1, cy + 4 - i, color);
        }
    }

    /** 沿选框周长跑一条「蓝灰渐变到透明」的光条。 */
    private static void drawStreak(final GuiGraphics gg, final int x, final int y,
                                   final int w, final int h, final float phase, final float alpha) {
        final int perimeter = 2 * (w + h);
        if (perimeter <= 0) {
            return;
        }
        final int segments = 30;
        for (int i = 0; i < segments; i++) {
            float t = (phase * perimeter) - i * (perimeter / (float) segments);
            while (t < 0) {
                t += perimeter;
            }
            final float fade = 1.0f - (float) i / segments;
            final float a = alpha * fade * fade * 0.9f;
            if (a <= 0.02f) {
                continue;
            }
            final int color = withAlpha(0xFFC9D6E6, a);
            final int px;
            final int py;
            if (t < w) {
                px = x + Math.round(t);
                py = y;
            } else if (t < w + h) {
                px = x + w;
                py = y + Math.round(t - w);
            } else if (t < 2 * w + h) {
                px = x + w - Math.round(t - w - h);
                py = y + h;
            } else {
                px = x;
                py = y + h - Math.round(t - 2 * w - h);
            }
            gg.fill(px - 1, py - 1, px + 1, py + 1, color);
        }
    }

    // ------------------------------------------------------------------

    private static float frameDelta() {
        final long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return 1.0f / 60.0f;
        }
        final float dt = (now - lastFrameNanos) / 1.0e9f;
        lastFrameNanos = now;
        return Math.max(0.0f, Math.min(0.1f, dt));
    }

    private static float approach(final float value, final float target, final float speed, final float dt) {
        return value + (target - value) * (1.0f - (float) Math.exp(-speed * dt));
    }

    private static int withAlpha(final int argb, final float alpha) {
        final int a = Math.max(0, Math.min(255, Math.round(((argb >>> 24) & 0xFF) * Math.max(0.0f, Math.min(1.0f, alpha)))));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
