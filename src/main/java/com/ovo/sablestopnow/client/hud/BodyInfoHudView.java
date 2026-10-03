package com.ovo.sablestopnow.client.hud;

import com.ovo.sablestopnow.client.StaffEnhanceClientHandler;
import com.ovo.sablestopnow.client.gui.lib.Anim;
import com.ovo.sablestopnow.client.gui.lib.Callout;
import com.ovo.sablestopnow.client.gui.lib.Container;
import com.ovo.sablestopnow.client.gui.lib.Label;
import com.ovo.sablestopnow.client.gui.lib.UiContext;
import com.ovo.sablestopnow.client.gui.lib.UiDock;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 右上角「瞄准的物理结构信息」面板（config {@code show_body_info}），同样只由 GUI 库绘制。
 *
 * <p>位置交给 {@link UiDock} 避让：模型模式的树状图常驻右侧，面板会自动整体让到树的左边，
 * 而不是像以前那样靠 {@code MateClientState.sidebarWidth()} 猜一个宽度。
 *
 * <p>面板宽度固定：里面的速度/质量每帧都在变，宽度跟着文字走的话整块会左右抖。
 * 放不下的长名字由 {@link Label} 自己省略，玩家至少知道「这里有个名字」。
 */
public final class BodyInfoHudView extends Container {

    private static final int PAD = 4;
    private static final int LINE_GAP = 1;
    private static final int LINE_W = 118;
    private static final int LINES = 7;
    private static final float OFF = 0.02f;

    private final Callout panel = new Callout();
    private final List<Label> lines = new ArrayList<>();

    /** 出现/收起的滑动（从右侧滑入）。 */
    private final Anim appear = Anim.of(0.0);

    public BodyInfoHudView() {
        this.prefW = AUTO;
        this.prefH = AUTO;

        panel.tipW = 0;   // 不指向任何一行 → 普通信息面板
        panel.padding(PAD);
        panel.clip = true;
        for (int i = 0; i < LINES; i++) {
            final int index = i;
            final Label label = new Label(() -> lineComponent(index));
            label.prefW = LINE_W;
            label.shadow = i == 0;
            lines.add(label);
            panel.add(label);
        }
        add(panel);
    }

    @Override
    public void measure(final UiContext ctx) {
        layoutContents(ctx, false);
    }

    @Override
    public void arrange(final UiContext ctx) {
        layoutContents(ctx, true);
        super.arrange(ctx);
    }

    private void layoutContents(final UiContext ctx, final boolean advance) {
        final UUID id = StaffEnhanceClientHandler.getInfoBody();
        final boolean show = id != null;

        // 尺寸用面板的实际大小问避让；滑动只是画的时候加一个偏移，不参与尺寸
        w = panel.wantW(ctx);
        h = panel.wantH(ctx);

        // 被模型模式的树状图压住、或挪出屏幕时就收起来，而不是硬画在别人面板上。
        // 树状图整条铺满右侧时 UiDock 会把我们让到它左边，让不开才走到这里。
        final boolean fits = !UiDock.overlapsReserved(x, y, w, h) && UiDock.fitsOnScreen(x, y, w, h);
        if (!advance) {
            return;
        }

        // 出现比收起稍快：瞄准新结构时希望信息立刻到位，移开视线时慢一点收
        appear.speed(show ? 9.0 : 7.0).to(show && fits ? 1.0 : 0.0);
        ctx.tick(appear);

        final float a = (float) appear.value();
        final int slide = Math.round((1.0f - a) * (w + 14));
        panel.visible = a > OFF;
        panel.alpha = a;
        panel.marginLeft = slide;
        panel.marginTop = 0;

        final int step = ctx.lineH() + LINE_GAP;
        for (int i = 0; i < lines.size(); i++) {
            final Label label = lines.get(i);
            label.visible = a > OFF;
            label.alpha = i == 0 ? a : a * 0.92f;
            label.color = i == 0 ? ctx.theme.text : ctx.theme.textDim;
            label.marginLeft = 0;
            label.marginTop = i * step;
        }
    }

    /** 面板里的第 index 行（和旧 HUD 完全一样的口径，只是换成了 lang 组件）。 */
    private static Component lineComponent(final int index) {
        final UUID id = StaffEnhanceClientHandler.getInfoBody();
        if (id == null) {
            return Component.empty();
        }
        switch (index) {
            case 0: {
                final String name = StaffEnhanceClientHandler.getInfoName();
                return name != null && !name.isBlank()
                        ? Component.literal(name) : Component.translatable("sablestopnow.info.unnamed");
            }
            case 1: {
                final String owner = StaffEnhanceClientHandler.ownerNameOf(id);
                return Component.translatable("sablestopnow.info.owner",
                        owner != null && !owner.isBlank()
                                ? Component.literal(owner)
                                : Component.translatable("sablestopnow.info.owner.none"));
            }
            case 2:
                return Component.translatable("sablestopnow.info.speed",
                        StaffEnhanceClientHandler.hasBodyInfo(id)
                                ? String.format("%.1f", StaffEnhanceClientHandler.getInfoSpeed()) : "…");
            case 3:
                return Component.translatable("sablestopnow.info.mass",
                        StaffEnhanceClientHandler.hasBodyInfo(id)
                                ? String.format("%.0f", StaffEnhanceClientHandler.getInfoMass()) : "…");
            case 4:
                return Component.translatable("sablestopnow.info.scale",
                        String.format("%.2f", StaffEnhanceClientHandler.scaleOf(id)));
            case 5:
                return Component.translatable("sablestopnow.info.collision", yesNo(
                        StaffEnhanceClientHandler.getNoCollision().contains(id)));
            default:
                return Component.translatable("sablestopnow.info.snapshot", yesNo(
                        StaffEnhanceClientHandler.getSnapshotIds().contains(id)));
        }
    }

    private static Component yesNo(final boolean flagged) {
        // 语义与旧面板一致：「碰撞：无」= 被标记为无碰撞
        return Component.translatable(flagged ? "sablestopnow.info.no" : "sablestopnow.info.yes");
    }
}
