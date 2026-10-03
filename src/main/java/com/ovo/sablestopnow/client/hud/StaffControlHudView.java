package com.ovo.sablestopnow.client.hud;

import com.ovo.sablestopnow.client.StaffControl;
import com.ovo.sablestopnow.client.StaffEnhanceClientHandler;
import com.ovo.sablestopnow.client.gui.lib.Anim;
import com.ovo.sablestopnow.client.gui.lib.Callout;
import com.ovo.sablestopnow.client.gui.lib.Container;
import com.ovo.sablestopnow.client.gui.lib.Label;
import com.ovo.sablestopnow.client.gui.lib.Layouts;
import com.ovo.sablestopnow.client.gui.lib.SlantTag;
import com.ovo.sablestopnow.client.gui.lib.StreakBox;
import com.ovo.sablestopnow.client.gui.lib.TextBlock;
import com.ovo.sablestopnow.client.gui.lib.UiContext;
import com.ovo.sablestopnow.client.gui.lib.UiDock;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;

import java.util.ArrayList;
import java.util.List;

/**
 * 新控制逻辑的左上角 HUD（纯显示，全部由 GUI 库的控件与 {@code UiDraw} 原语画）。
 *
 * <p>控件树（{@link Container} 每帧重排，所以动画值改了位置就会跟着走）：
 * <pre>
 *   SlantTag  模式横幅（右端斜切） ─ Label 模式名
 *   StreakBox 选中功能的蓝色选框（滑动的蓝框 + 应用后绕框一圈的光条）
 *   Container 功能行（每项一个 Label；模式变了整份清单就换）
 *   Container 描述槽 ─ Callout（左侧带三角凸起） ─ TextBlock 描述文字
 *   TextBlock 滚轮提示 / 切换提示（自动换行）
 *   Label     准星右侧的穿透层数（在卡片之外，所以不跟着卡片滑动）
 * </pre>
 *
 * <p><b>为什么动画在 {@code arrange} 里推进</b>：库的布局发生在 {@code update} 之前，而这里每个子控件的
 * 坐标都由动画值算出来；如果在 {@code update} 里推进，布局用到的永远是上一帧的值，滑动时整块会与
 * 选框/描述错开一帧。
 *
 * <p><b>位置</b>：整块交给 {@link UiDock} 避让；避不开（模型模式的左边栏从顶铺到底）或挪出屏幕时
 * 整块淡出，而不是硬画在别人的面板上。
 */
public final class StaffControlHudView extends Container {

    /** 卡片身份：未启用 = idle，启用后 = 模式名。 */
    private static final String IDLE = "idle";

    private static final int PAD = 4;
    private static final int BANNER_PAD_X = 5;
    private static final int BANNER_H = 11;
    private static final int BANNER_GAP = 3;
    private static final int ROW_H = 10;
    private static final int BOX_PAD_X = 4;
    private static final int MIN_BOX_W = 54;
    private static final int MAX_BOX_W = 104;
    private static final int ARROW_W = 4;
    private static final int DESC_TIP = 5;
    private static final int DESC_GAP = 6;
    private static final int DESC_W = 104;
    private static final int DESC_PAD = 3;
    private static final int MAX_DESC_LINES = 3;
    private static final int HINT_GAP = 2;
    private static final int HINT_W = 118;
    /** 模式整块「先向左滑出、再从左侧滑入」的距离。 */
    private static final int SLIDE = 44;
    /** 描述「向左消失、再从左侧出现」的距离。 */
    private static final int DESC_SLIDE = 34;
    /** 滑到多小就算「已经看不见」，也就是可以换内容的时刻。 */
    private static final double SWAP_EPS = 0.05;
    /** 完全透明（连控件都不画）的阈值。 */
    private static final float OFF = 0.02f;

    // ---- 控件 ----
    private final SlantTag banner = new SlantTag();
    private final Label title = new Label(Component.empty());
    private final StreakBox box = new StreakBox();
    private final Container rows = new Container();
    private final List<Label> rowLabels = new ArrayList<>();
    private final Container descSlot = new Container();
    private final Callout descPanel = new Callout();
    private final TextBlock descText = new TextBlock(Component.empty(), DESC_W - DESC_PAD * 2);
    private final TextBlock wheelHint = new TextBlock(Component.empty(), HINT_W);
    private final TextBlock switchHint = new TextBlock(Component.empty(), HINT_W);
    private final Label aim = new Label(Component.empty());

    // ---- 动画 ----
    /** 整块的位置/透明度（模式或启用状态变化时：滑出 → 换内容 → 滑入）。 */
    private final Anim block = Anim.of(1.0).speed(18.0);
    /** 描述的滑动/淡出淡入（换功能时）。 */
    private final Anim desc = Anim.of(1.0).speed(16.0);
    /** 蓝色选框的纵向位置（单位：行）。 */
    private final Anim sel = Anim.of(0.0).speed(13.0);
    /** 被别的界面压住时的整体淡出。 */
    private final Anim docked = Anim.of(1.0).speed(14.0);

    // ---- 状态 ----
    private String pendingKey = IDLE;
    private String shownKey = IDLE;
    private boolean initialized;
    private boolean swapping;
    private boolean descSwapping;
    private boolean forceDescSwap;
    private int lastIndex = -1;
    private int boxW = MIN_BOX_W;
    private int descLines = 2;

    public StaffControlHudView() {
        // 尺寸自己量，让 Layouts.hud 决定放哪儿（Container 默认是 FILL，会把整块拉满屏幕）
        this.prefW = AUTO;
        this.prefH = AUTO;

        banner.slant = 5;
        banner.padLeft = BANNER_PAD_X;
        banner.padRight = BANNER_PAD_X + 1;
        banner.padTop = (BANNER_H - 7) / 2;
        banner.padBottom = (BANNER_H - 7) - banner.padTop;
        title.shadow = true;
        banner.add(title);

        rows.layout = Layouts.absolute();
        rows.prefW = AUTO;
        rows.prefH = AUTO;

        // 描述槽只是给 Callout 一个裁剪框：滑出的文字绝不能跑到左边的功能列表上
        descSlot.clip = true;
        descSlot.prefW = AUTO;
        descSlot.prefH = AUTO;
        descPanel.tipW = DESC_TIP;
        descPanel.padding(DESC_PAD);
        descText.lineGap = 1;
        descPanel.add(descText);
        descSlot.add(descPanel);

        aim.shadow = true;
        // 准星读数每帧都在变（滚轮调穿透层数），所以用供应商而不是固定文本
        aim.text = () -> Component.translatable("sablestopnow.control.penetration",
                StaffEnhanceClientHandler.getPenetration());

        add(banner, box, rows, descSlot, wheelHint, switchHint, aim);
    }

    // ==================================================================
    // 由 StaffControlHud 转发的事件（HUD 是纯显示，事件只影响动画）
    // ==================================================================

    public void onSelectionChanged() {
        forceDescSwap = true;
    }

    public void onModeChanged() {
        // 卡片换内容靠 tick 里比对模式；这里只需要让描述也重播一次
        forceDescSwap = true;
    }

    public void onApplied(final boolean sustained) {
        box.apply(sustained);
    }

    public void onSustainedEnded() {
        // 持续型功能（Z/X）结束：把这一圈转完再收
        box.stopLoop();
    }

    // ==================================================================
    // 布局 + 动画
    // ==================================================================

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
        final boolean liveArmed = StaffEnhanceClientHandler.isControlArmed();
        final String wantKey = liveArmed ? StaffEnhanceClientHandler.newControlMode().name() : IDLE;

        if (!initialized) {
            // 第一次画就落到目标状态：玩家刚把手杖拿到手上时不该看到一段没有来由的滑入
            initialized = true;
            pendingKey = wantKey;
            shownKey = wantKey;
            applyCard(ctx);
            final int start = liveIndex();
            lastIndex = start;      // 免得第一帧就误判成「换了功能」而重播描述动画
            sel.snap(start);
            desc.snap(1.0);
            block.snap(1.0);
        }
        if (!wantKey.equals(pendingKey)) {
            pendingKey = wantKey;
            swapping = true;
            block.to(0.0);          // 先向左滑出（滑到看不见时才换内容）
        }
        final int index = liveIndex();
        if (index != lastIndex || forceDescSwap) {
            lastIndex = index;
            forceDescSwap = false;
            desc.to(0.0);
            descSwapping = true;
        }

        if (advance) {
            if (swapping && block.value() <= SWAP_EPS) {
                // 「先从左消失」已经走完：在完全看不见的时刻换内容，再「从左进入」。
                // 功能清单在这里整体换掉，所以它一定是跟着模式走的。
                shownKey = pendingKey;
                applyCard(ctx);
                sel.snap(index);
                desc.snap(1.0);
                descSwapping = false;
                swapping = false;
                box.cancel();
                block.to(1.0);
            }
            if (descSwapping && desc.value() <= SWAP_EPS) {
                descSwapping = false;
                desc.to(1.0);
            }
            sel.to(index);
            ctx.tick(block);
            ctx.tick(desc);
            ctx.tick(sel);
        }

        // ---- 几何：尺寸按「就位后」的状态算，位置按动画值算 ----
        final List<StaffControl.Fn> fns = functionsForShown();
        final boolean armedShown = !fns.isEmpty();
        final int bannerW = banner.wantW(ctx);
        final int bannerH = Math.max(BANNER_H, banner.wantH(ctx));
        final int rowsTop = PAD + bannerH + BANNER_GAP;
        final int rowsH = fns.size() * ROW_H;
        final int descH = armedShown ? descHeight(ctx) : 0;
        final boolean showWheel = armedShown && StaffEnhanceClientHandler.newControlFunctionActive()
                && wheelKeyOf(fns, index) != null;
        final int hintTop = rowsTop + rowsH + HINT_GAP;
        final int wheelH = showWheel ? wheelHint.wantH(ctx) : 0;
        final int switchTop = showWheel ? hintTop + wheelH + HINT_GAP : hintTop;
        final int switchH = switchHint.wantH(ctx);

        // 尺寸用「选中的那一行」而不是滑动中的位置：否则选框每滑一格整块的高度就抖一下
        final int settledDescY = rowsTop + index * ROW_H + ROW_H / 2 - descH / 2;
        // 未启用时没有功能列表与描述，宽度只能按横幅和提示算（否则避让矩形会凭空宽出一大截）
        final int listRight = armedShown ? PAD + boxW + ARROW_W + DESC_GAP + DESC_TIP + DESC_W : 0;
        final int contentRight = Math.max(Math.max(PAD + bannerW, listRight), PAD + HINT_W);
        w = contentRight + PAD;
        h = Math.max(Math.max(rowsTop + rowsH, armedShown ? settledDescY + descH : 0),
                switchTop + switchH) + PAD;

        // ---- 避让：被别的面板压住、或被推到屏幕外，就整块淡出，不硬画在别人上面 ----
        if (advance) {
            final boolean fits = !UiDock.overlapsReserved(x, y, w, h) && UiDock.fitsOnScreen(x, y, w, h);
            docked.to(fits ? 1.0 : 0.0);
            ctx.tick(docked);
        }

        if (!advance) {
            return;
        }

        // ---- 套用到子控件（绝对布局：这里写 margin，父布局写 x/y） ----
        final float appear = (float) (block.value() * docked.value());
        final boolean on = appear > OFF;
        final int ox = -Math.round((1.0f - (float) block.value()) * SLIDE);
        final int left = PAD + ox;
        final int boxY = rowsTop + Math.round((float) sel.value() * ROW_H);
        final int descY = Math.max(PAD, boxY + ROW_H / 2 - descH / 2);

        banner.visible = on;
        banner.alpha = appear;
        banner.marginLeft = left;
        banner.marginTop = PAD;
        title.alpha = appear;

        box.visible = on && armedShown;
        box.alpha = appear;
        box.prefW = boxW;
        box.prefH = ROW_H - 1;
        box.marginLeft = left;
        box.marginTop = boxY;

        rows.visible = on && armedShown;
        rows.prefW = boxW;
        rows.prefH = Math.max(0, rowsH);
        rows.marginLeft = left;
        rows.marginTop = rowsTop;
        for (final Label label : rowLabels) {
            label.alpha = appear;
        }

        descSlot.visible = on && armedShown;
        descSlot.prefW = DESC_TIP + DESC_W;
        descSlot.prefH = Math.max(0, descH);
        descSlot.marginLeft = left + boxW + ARROW_W + DESC_GAP;
        descSlot.marginTop = descY;
        descPanel.prefW = DESC_W;
        descPanel.prefH = Math.max(0, descH);
        descPanel.alpha = appear;
        // 面板本体让出凸起的那一格：凸起在槽的左侧，超出槽就被裁掉了（指向选中行全靠它）
        descPanel.marginLeft = DESC_TIP;
        descText.color = ctx.theme.textDim;
        descText.alpha = appear * desc.f();
        descText.marginLeft = -Math.round((1.0f - desc.f()) * DESC_SLIDE);
        descText.marginTop = Math.max(0, (descH - descText.wantH(ctx)) / 2);

        wheelHint.visible = on && showWheel;
        wheelHint.alpha = appear;
        wheelHint.color = ctx.theme.textDim;
        wheelHint.marginLeft = left;
        wheelHint.marginTop = hintTop;

        switchHint.visible = on;
        switchHint.alpha = appear * 0.85f;
        switchHint.color = ctx.theme.textDim;
        switchHint.marginLeft = left;
        switchHint.marginTop = switchTop;

        // 准星读数不属于卡片：卡片滑动时它不跟着动，只跟着「整体可见/被压住」淡
        final int penetration = StaffEnhanceClientHandler.getPenetration();
        aim.visible = liveArmed && docked.value() > OFF;
        aim.alpha = liveArmed ? (float) docked.value() * (penetration > 0 ? 1.0f : 0.7f) : 0.0f;
        aim.color = penetration > 0 ? ctx.theme.text : ctx.theme.textDim;
        aim.marginLeft = ctx.screenW / 2 + 10 - innerX();
        aim.marginTop = ctx.screenH / 2 - 4 - innerY();
    }

    // ==================================================================
    // 卡片内容
    // ==================================================================

    /** 换卡片：横幅文字、功能行、描述，三者都随模式（或「未启用」）整体变。 */
    private void applyCard(final UiContext ctx) {
        final List<StaffControl.Fn> fns = functionsForShown();
        final boolean armed = !fns.isEmpty();

        title.text = () -> Component.translatable(armed
                ? modeOfShown().nameKey : "sablestopnow.control.idle.title");

        rows.clear();
        rowLabels.clear();
        int widest = MIN_BOX_W;
        for (int i = 0; i < fns.size(); i++) {
            final Label label = new Label(Component.translatable(fns.get(i).nameKey));
            label.marginLeft = BOX_PAD_X;
            // 行内竖直居中：行高固定，文字高度由主题字号决定
            label.marginTop = i * ROW_H + Math.max(0, (ROW_H - ctx.lineH()) / 2);
            rowLabels.add(label);
            rows.add(label);
            widest = Math.max(widest, ctx.textW(label.raw()) + BOX_PAD_X * 2);
        }
        boxW = Math.min(MAX_BOX_W, widest);

        // 描述面板的高度取「本模式下最长的描述」，这样切换功能时面板尺寸不会跳
        final float scale = ctx.theme.fontScale;
        int lines = 1;
        for (final StaffControl.Fn fn : fns) {
            final FormattedText text = Component.translatable(fn.descKey);
            lines = Math.max(lines, ctx.font.split(text,
                    Math.max(1, Math.round((DESC_W - DESC_PAD * 2) / scale))).size());
        }
        descLines = Math.min(MAX_DESC_LINES, Math.max(1, lines));

        descText.text = () -> {
            final List<StaffControl.Fn> list = functionsForShown();
            return list.isEmpty() ? Component.empty()
                    : Component.translatable(list.get(clampIndex(list)).descKey);
        };
        wheelHint.text = () -> {
            final List<StaffControl.Fn> list = functionsForShown();
            if (list.isEmpty()) {
                return Component.empty();
            }
            final String key = list.get(clampIndex(list)).wheelKey;
            return key == null ? Component.empty() : Component.translatable(key);
        };
        switchHint.text = () -> armed
                ? Component.translatable(modeOfShown() == StaffControl.Mode.DRAG
                        ? "sablestopnow.control.hint.switch_ctrl" : "sablestopnow.control.hint.switch")
                // 未启用时滚轮还是原版的物品栏，不能提示成「切换功能」
                : Component.translatable("sablestopnow.control.idle.hint");
    }

    private int descHeight(final UiContext ctx) {
        return descLines * (ctx.lineH() + descText.lineGap) - descText.lineGap + DESC_PAD * 2;
    }

    /** 当前画出来的那份功能清单（可能是上一张卡片：换内容发生在滑出之后）。 */
    private List<StaffControl.Fn> functionsForShown() {
        if (shownKey == null || IDLE.equals(shownKey)) {
            return List.of();
        }
        return StaffControl.functionsFor(StaffControl.Mode.valueOf(shownKey));
    }

    private StaffControl.Mode modeOfShown() {
        if (shownKey == null || IDLE.equals(shownKey)) {
            return StaffControl.Mode.NORMAL;
        }
        return StaffControl.Mode.valueOf(shownKey);
    }

    private static int liveIndex() {
        return Math.max(0, StaffEnhanceClientHandler.newControlIndex());
    }

    private static int clampIndex(final List<StaffControl.Fn> fns) {
        return Math.max(0, Math.min(fns.size() - 1, StaffEnhanceClientHandler.newControlIndex()));
    }

    private static String wheelKeyOf(final List<StaffControl.Fn> fns, final int index) {
        if (fns.isEmpty()) {
            return null;
        }
        return fns.get(Math.max(0, Math.min(fns.size() - 1, index))).wheelKey;
    }
}
