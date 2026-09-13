package com.ovo.sablestopnow.client;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.SablestopNowConfig;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 新控制逻辑的 HUD（左上角）+ 右上角「瞄准结构信息面板」。
 *
 * <p>左上（仅新控制逻辑开启时）：模式标签（右边缘斜切、描边连续）+ 该模式**全部**功能
 * （离当前项越远越虚化）+ 会平滑滑动的蓝色选框 + 右侧**带左侧三角凸起的两行描述面板**
 * + 底部换行提示；左键应用功能时沿选框周长跑一条连续光条（Z/X 持续旋转）。</p>
 *
 * <p>右上（config {@code show_body_info}，默认开）：拿着手杖时显示准星所指物理结构的
 * 所有者 / 速度 / 质量 / 缩放 / 碰撞 / 快照，从右侧滑入滑出。</p>
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT)
public final class StaffControlHud {

    // 与 client/gui 的设置界面同一套配色
    private static final int PANEL = 0xF0162B52;
    private static final int PANEL_DARK = 0xF00E1D3A;
    private static final int EDGE = 0xFF8296B8;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xFFD6DEEA;
    private static final int STREAK = 0xFFC9D6E6;

    private static final int MARGIN_X = 6;
    private static final int MARGIN_Y = 8;
    private static final int BANNER_H = 14;
    private static final int BANNER_SLANT = 7;
    private static final int ROW_H = 12;
    private static final int BOX_W = 76;
    private static final int LIST_GAP = 5;
    private static final int DESC_GAP = 10;
    private static final int DESC_W = 96;
    private static final int LINE_H = 10;
    private static final int HINT_W = 118;

    // ---- 动画状态 ----
    private static float modeAnim = 1.0f;
    private static float descAnim = 1.0f;
    private static float selPos;
    private static float streak = -1.0f;
    private static boolean streakLoop;
    private static float infoAnim;
    private static long lastFrameNanos;

    private StaffControlHud() {
    }

    // ------------------------------------------------------------------
    // 由 StaffEnhanceClientHandler 调用的触发器
    // ------------------------------------------------------------------

    public static void notifyModeChanged() {
        modeAnim = 0.0f;
        descAnim = 1.0f;
    }

    public static void notifySelectionChanged() {
        descAnim = 0.0f;
    }

    public static void notifyApplied(final boolean sustained) {
        streak = 0.0f;
        streakLoop = sustained;
    }

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
                || !SablestopNowConfig.isStaffEnhanceEnabled()
                || !StaffEnhanceClientHandler.isEnabled()
                || !PhysicsStaffItem.isHolding(player)) {
            return;
        }
        final GuiGraphics gg = event.getGuiGraphics();
        final float dt = frameDelta();

        if (SablestopNowConfig.isNewControlScheme()) {
            if (StaffEnhanceClientHandler.isControlArmed()) {
                renderControlHud(gg, dt);
            } else {
                renderIdleHud(gg, dt);
            }
        }
        if (SablestopNowConfig.isShowBodyInfo()) {
            renderBodyInfo(gg, dt);
        }
    }

    /**
     * 未启用：只显示「物理手杖」标签 + 一行「左键启用」提示。
     *
     * <p>刚把手杖切到手上时**不进入任何模式**，滚轮与右键都放行，玩家可以直接滚过手杖去选别的东西；
     * 想用增强功能时按一次左键（或按一下 Ctrl）即可启用。</p>
     */
    private static void renderIdleHud(final GuiGraphics gg, final float dt) {
        final Minecraft mc = Minecraft.getInstance();
        modeAnim = approach(modeAnim, 1.0f, 7.0f, dt);
        final float alpha = modeAnim < 0.5f ? 1.0f - modeAnim * 2.0f : (modeAnim - 0.5f) * 2.0f;
        final float shift = modeAnim < 0.5f ? modeAnim * 2.0f : 1.0f - (modeAnim - 0.5f) * 2.0f;
        final int x = MARGIN_X - Math.round(shift * 44.0f);

        final String title = I18n.get("sablestopnow.control.idle.title");
        drawBanner(gg, x, MARGIN_Y, mc.font.width(title) + 13, BANNER_H, BANNER_SLANT, alpha);
        gg.drawString(mc.font, title, x + 6, MARGIN_Y + 3, withAlpha(TEXT, alpha), false);

        int y = MARGIN_Y + BANNER_H + LIST_GAP;
        for (final FormattedCharSequence line : wrap(mc, I18n.get("sablestopnow.control.idle.hint"), HINT_W)) {
            gg.drawString(mc.font, line, x, y, withAlpha(TEXT_DIM, alpha * 0.9f), false);
            y += LINE_H;
        }
    }

    /** 左上角：模式 + 功能列表 + 描述面板 + 提示 + 准星穿透层数。 */
    private static void renderControlHud(final GuiGraphics gg, final float dt) {
        final Minecraft mc = Minecraft.getInstance();
        final StaffControl.Mode mode = StaffEnhanceClientHandler.newControlMode();
        final List<StaffControl.Fn> fns = StaffControl.functionsFor(mode);
        final int index = Math.max(0, Math.min(fns.size() - 1, StaffEnhanceClientHandler.newControlIndex()));
        final boolean functionActive = StaffEnhanceClientHandler.newControlFunctionActive();

        modeAnim = approach(modeAnim, 1.0f, 7.0f, dt);
        descAnim = approach(descAnim, 1.0f, 9.0f, dt);
        selPos = approach(selPos, index, 13.0f, dt);
        if (streak >= 0.0f) {
            if (streakLoop && functionActive) {
                streak = (streak + dt * 0.85f) % 1.0f;
            } else {
                streak += dt * 2.0f;
                if (streak >= 1.0f) {
                    streak = -1.0f;
                }
            }
        }

        final float blockAlpha = modeAnim < 0.5f ? 1.0f - modeAnim * 2.0f : (modeAnim - 0.5f) * 2.0f;
        final float shift = modeAnim < 0.5f ? modeAnim * 2.0f : 1.0f - (modeAnim - 0.5f) * 2.0f;
        final int x = MARGIN_X - Math.round(shift * 44.0f);
        int y = MARGIN_Y;

        // ---- 模式标签 ----
        final String modeName = I18n.get(mode.nameKey);
        drawBanner(gg, x, y, mc.font.width(modeName) + 13, BANNER_H, BANNER_SLANT, blockAlpha);
        gg.drawString(mc.font, modeName, x + 6, y + 3, withAlpha(TEXT, blockAlpha), false);
        y += BANNER_H + LIST_GAP;

        // ---- 功能列表：先画会滑动的选框，再画文字 ----
        final float selY = y + selPos * ROW_H;
        final int boxY = Math.round(selY);
        fillBox(gg, x, boxY, BOX_W, ROW_H - 1, blockAlpha);
        drawArrow(gg, x + BOX_W + 2, boxY + (ROW_H - 1) / 2, blockAlpha);
        for (int i = 0; i < fns.size(); i++) {
            // 不做虚化：所有功能项同一亮度（用户要求取消距离虚化）
            gg.drawString(mc.font, I18n.get(fns.get(i).nameKey), x + 6, y + i * ROW_H + 3,
                    withAlpha(TEXT_DIM, blockAlpha), false);
        }

        // ---- 描述：带左侧三角凸起的面板，最多两行；先向右淡出，再从左侧从左到右出现 ----
        final float descAlpha;
        final float descOffset;
        if (descAnim < 0.5f) {
            final float a = descAnim * 2.0f;
            descAlpha = blockAlpha * (1.0f - a);
            descOffset = a * 26.0f;
        } else {
            final float a = (descAnim - 0.5f) * 2.0f;
            descAlpha = blockAlpha * a;
            descOffset = -(1.0f - a) * 26.0f;
        }
        if (descAlpha > 0.02f) {
            final List<FormattedCharSequence> lines = wrap(mc, I18n.get(fns.get(index).descKey), DESC_W);
            drawNotchedPanel(gg, mc, x + BOX_W + DESC_GAP + Math.round(descOffset), boxY, lines, descAlpha);
        }

        // ---- 光条 ----
        if (streak >= 0.0f) {
            drawStreak(gg, x, boxY, BOX_W, ROW_H - 1, streak, blockAlpha);
        }

        // ---- 底部提示（换行）----
        int hintY = y + fns.size() * ROW_H + 2;
        final String wheelKey = fns.get(index).wheelKey;
        if (functionActive && wheelKey != null) {
            for (final FormattedCharSequence line : wrap(mc, I18n.get(wheelKey), HINT_W)) {
                gg.drawString(mc.font, line, x, hintY, withAlpha(TEXT_DIM, blockAlpha), false);
                hintY += LINE_H;
            }
        }
        final boolean dragMode = mode == StaffControl.Mode.DRAG;
        for (final FormattedCharSequence line : wrap(mc, I18n.get(dragMode
                ? "sablestopnow.control.hint.switch_ctrl"
                : "sablestopnow.control.hint.switch"), HINT_W)) {
            gg.drawString(mc.font, line, x, hintY, withAlpha(TEXT_DIM, blockAlpha * 0.85f), false);
            hintY += LINE_H;
        }

        // ---- 准星右侧：实时穿透层数 ----
        final int penetration = StaffEnhanceClientHandler.getPenetration();
        gg.drawString(mc.font, I18n.get("sablestopnow.control.penetration", penetration),
                gg.guiWidth() / 2 + 10, gg.guiHeight() / 2 - 4,
                withAlpha(penetration > 0 ? TEXT : TEXT_DIM, penetration > 0 ? 1.0f : 0.7f), true);
    }

    /** 右上角：瞄准结构信息面板（从右侧滑入滑出）。 */
    private static void renderBodyInfo(final GuiGraphics gg, final float dt) {
        final Minecraft mc = Minecraft.getInstance();
        final UUID id = StaffEnhanceClientHandler.getInfoBody();
        final boolean show = id != null;
        infoAnim = approach(infoAnim, show ? 1.0f : 0.0f, show ? 9.0f : 7.0f, dt);
        if (infoAnim < 0.02f) {
            return;
        }

        final List<String> lines = new ArrayList<>();
        final String name = StaffEnhanceClientHandler.getInfoName();
        lines.add(name != null && !name.isBlank() ? name : I18n.get("sablestopnow.info.unnamed"));
        lines.add(I18n.get("sablestopnow.info.owner",
                StaffEnhanceClientHandler.ownerNameOf(id) != null && !StaffEnhanceClientHandler.ownerNameOf(id).isBlank()
                        ? StaffEnhanceClientHandler.ownerNameOf(id)
                        : I18n.get("sablestopnow.info.owner.none")));
        if (StaffEnhanceClientHandler.hasBodyInfo(id)) {
            lines.add(I18n.get("sablestopnow.info.speed", String.format("%.1f", StaffEnhanceClientHandler.getInfoSpeed())));
            lines.add(I18n.get("sablestopnow.info.mass", String.format("%.0f", StaffEnhanceClientHandler.getInfoMass())));
        } else {
            lines.add(I18n.get("sablestopnow.info.speed", "…"));
            lines.add(I18n.get("sablestopnow.info.mass", "…"));
        }
        lines.add(I18n.get("sablestopnow.info.scale", String.format("%.2f", StaffEnhanceClientHandler.scaleOf(id))));
        lines.add(I18n.get("sablestopnow.info.collision", I18n.get(
                StaffEnhanceClientHandler.getNoCollision().contains(id)
                        ? "sablestopnow.info.no" : "sablestopnow.info.yes")));
        lines.add(I18n.get("sablestopnow.info.snapshot", I18n.get(
                StaffEnhanceClientHandler.getSnapshotIds().contains(id)
                        ? "sablestopnow.info.yes" : "sablestopnow.info.no")));

        int width = 0;
        for (final String line : lines) {
            width = Math.max(width, mc.font.width(line));
        }
        final int panelW = width + 12;
        final int panelH = lines.size() * LINE_H + 8;
        final int slide = Math.round((1.0f - infoAnim) * (panelW + 14));
        final int px = gg.guiWidth() - MARGIN_X - panelW + slide;
        final int py = MARGIN_Y;

        fillBox(gg, px, py, panelW, panelH, infoAnim);
        int ty = py + 4;
        for (int i = 0; i < lines.size(); i++) {
            final float a = i == 0 ? infoAnim : infoAnim * 0.92f;
            gg.drawString(mc.font, lines.get(i), px + 6, ty, withAlpha(i == 0 ? TEXT : TEXT_DIM, a), false);
            ty += LINE_H;
        }
    }

    // ------------------------------------------------------------------
    // 绘制辅助
    // ------------------------------------------------------------------

    /** 按像素宽度换行（行数不设上限，面板高度自适应）。 */
    private static List<FormattedCharSequence> wrap(final Minecraft mc, final String text, final int maxWidth) {
        return mc.font.split(Component.literal(text), maxWidth);
    }

    /** 右边缘斜切的标签：底色 + 一圈连续描边（上边画满到斜切顶端）。 */
    private static void drawBanner(final GuiGraphics gg, final int x, final int y,
                                   final int w, final int h, final int slant, final float alpha) {
        final int fill = withAlpha(PANEL, alpha);
        final int dark = withAlpha(PANEL_DARK, alpha);
        final int edge = withAlpha(EDGE, alpha);
        for (int j = 0; j < h; j++) {
            final int xr = x + w + slant * (h - 1 - j) / Math.max(1, h - 1);
            gg.fill(x, y + j, xr, y + j + 1, (j == 0 || j == h - 1) ? dark : fill);
        }
        gg.fill(x, y, x + w + slant, y + 1, edge);
        gg.fill(x, y + h - 1, x + w, y + h, edge);
        gg.fill(x, y, x + 1, y + h, edge);
        for (int j = 0; j < h; j++) {
            final int xr = x + w + slant * (h - 1 - j) / Math.max(1, h - 1);
            gg.fill(xr - 1, y + j, xr, y + j + 1, edge);
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

    /**
     * 描述面板：蓝色长方体 + **左侧三角凸起**（指向选中行），内容两行、左对齐、垂直居中。
     * {@code anchorY} 是选中行的顶部 y，面板以该行为中心上下对齐。
     */
    private static void drawNotchedPanel(final GuiGraphics gg, final Minecraft mc,
                                         final int x, final int anchorY,
                                         final List<FormattedCharSequence> lines, final float alpha) {
        int textW = 0;
        for (final FormattedCharSequence line : lines) {
            textW = Math.max(textW, mc.font.width(line));
        }
        final int h = lines.size() * LINE_H + 6;
        final int w = textW + 12;
        final int y = anchorY + (ROW_H - 1) / 2 - h / 2;
        final int tip = x - 5;
        // 左侧三角凸起（尖朝左，指向选中行）
        for (int i = 0; i < 5; i++) {
            gg.fill(tip + i, y + h / 2 - i, tip + i + 1, y + h / 2 + i + 1, withAlpha(PANEL, alpha));
        }
        fillBox(gg, x, y, w, h, alpha);
        int ty = y + 3;
        for (final FormattedCharSequence line : lines) {
            gg.drawString(mc.font, line, x + 6, ty, withAlpha(TEXT_DIM, alpha), false);
            ty += LINE_H;
        }
    }

    private static void drawArrow(final GuiGraphics gg, final int x, final int cy, final float alpha) {
        final int color = withAlpha(EDGE, alpha);
        for (int i = 0; i < 4; i++) {
            gg.fill(x + i, cy - 3 + i, x + i + 1, cy + 4 - i, color);
        }
    }

    /** 沿选框周长跑一条连续的「蓝灰渐变到透明」光条（逐像素步进）。 */
    private static void drawStreak(final GuiGraphics gg, final int x, final int y,
                                   final int w, final int h, final float phase, final float alpha) {
        final int perimeter = 2 * (w + h);
        if (perimeter <= 0) {
            return;
        }
        final int tail = Math.max(10, perimeter / 4);
        for (int i = 0; i < tail; i++) {
            float t = phase * perimeter - i;
            while (t < 0) {
                t += perimeter;
            }
            while (t >= perimeter) {
                t -= perimeter;
            }
            final float fade = 1.0f - (float) i / tail;
            final float a = alpha * fade * fade;
            if (a <= 0.02f) {
                continue;
            }
            final int px;
            final int py;
            if (t < w) {
                px = x + (int) t;
                py = y;
            } else if (t < w + h) {
                px = x + w;
                py = y + (int) (t - w);
            } else if (t < 2 * w + h) {
                px = x + w - (int) (t - w - h);
                py = y + h;
            } else {
                px = x;
                py = y + h - (int) (t - 2 * w - h);
            }
            gg.fill(px, py, px + 1, py + 1, withAlpha(STREAK, a));
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
        final int a = Math.max(0, Math.min(255,
                Math.round(((argb >>> 24) & 0xFF) * Math.max(0.0f, Math.min(1.0f, alpha)))));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
