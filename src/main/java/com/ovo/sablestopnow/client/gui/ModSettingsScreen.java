package com.ovo.sablestopnow.client.gui;

import com.ovo.sablestopnow.client.gui.lib.Anim;
import com.ovo.sablestopnow.client.gui.lib.IconTile;
import com.ovo.sablestopnow.client.gui.lib.Label;
import com.ovo.sablestopnow.client.gui.lib.RevealPanel;
import com.ovo.sablestopnow.client.gui.lib.UiScreen;
import com.ovo.sablestopnow.client.gui.lib.UiSettings;
import com.ovo.sablestopnow.client.gui.lib.UiTheme;
import com.ovo.sablestopnow.client.gui.lib.Widget;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 模组设置界面 —— 大类选择（对应 newConfigGUI/GUI1.png），用自研 GUI 库重写。
 *
 * <p>进入顺序（要求原话）：背景模糊 → 左侧蓝色色块<b>从上到下</b>出现 → 标题<b>从左到右</b>滑入
 * → 三个大类<b>依次</b>从左滑入。点进某个大类、或按 Esc 关闭时全部反向播放。
 * 所有位移与淡入淡出都走 {@link Anim}（指数收敛 ⇒ 天然「由快变慢」），缓出曲线用
 * {@link Anim#easeOut} 再压一次，出现时会先冲得快、然后慢慢贴住目标。
 *
 * <p><b>为什么「先播完动画再 setScreen」：</b>反向动画要留在本界面上播，立刻切屏玩家只会看到闪一下。
 * 界面实例会被复用（从大类详情按 Esc 回来就是同一个对象），所以 {@link #build()} 每次重建整棵树，
 * 动画值也就跟着从头开始，不需要额外清理状态。
 */
public class ModSettingsScreen extends UiScreen {

    private static final ModConfigCategory[] CATEGORIES = ModConfigCategory.values();
    /** 大标题的字号倍率（覆盖主题字号：标题就该比正文大，不能为了它把整屏放大）。 */
    private static final float TITLE_SCALE = 1.8f;
    /** 大类图标的像素尺寸。 */
    private static final int TILE_ICON = 18;
    private static final int TILE_H = 26;
    private static final int TILE_GAP = 30;

    /** 标题的滑入进度（色块与图标的进度各自挂在控件上）。 */
    private final Anim titleAnim = Anim.of(0.0).speed(7.0);
    private final IconTile[] tiles = new IconTile[CATEGORIES.length];

    @Nullable
    private RevealPanel panel;
    @Nullable
    private Label titleLine1;
    @Nullable
    private Label titleLine2;

    /**
     * 界面状态机 ✓（P4 / docs/gui-统一.md 的原则 #4：**不要用一堆 boolean 判断当前在干什么** ✗）。
     *
     * <p>以前这里是 {@code closing} + {@code switchPending} 两个 boolean ✗ ⇒
     * "正在关闭"与"该切屏了"的组合状态靠人脑维护 ✓ 加一个状态就要多想一遍组合 ✗。
     * 现在只有一个枚举 ✓：{@link State#IDLE} / {@link State#LEAVING} / {@link State#SWITCH_PENDING} ✓。
     *
     * <p>⚠ {@code next == null} 不能当「没在关闭」的哨兵：Ctrl+O 打开时 parent 就是 null（关闭=回游戏）✓。
     */
    private enum State {
        /** 正常显示/交互中 ✓。 */
        IDLE,
        /** 正在播反向动画 ✓ 播完才允许切屏 ✓。 */
        LEAVING,
        /** 动画播完了 ✓ 下一帧切屏 ✓（不能在渲染中途换屏 ✗）。 */
        SWITCH_PENDING
    }

    private State state = State.IDLE;
    @Nullable
    private Screen next;

    private long lastNanos;
    private int panelW;

    public ModSettingsScreen(@Nullable final Screen parent) {
        super(Component.translatable("gui.sablestopnow.title"), parent);
    }

    /** 统一入口：Ctrl+O 与模组列表的「配置」按钮都从这里拿界面。 */
    public static ModSettingsScreen create(@Nullable final Screen parent) {
        return new ModSettingsScreen(parent);
    }

    // ============ 布局 ============

    @Override
    protected void build() {
        root.closePopup();
        root.clear();
        // ⚠⚠ 每次 build() 都是【新一代】控件 ⇒ 上一代的引用必须清掉 ✗✓（docs/gui-统一.md 的 I4 ✓）
        //   否则字段数组/字段引用里会留着幽灵控件：动画循环仍会写它们的 margin/enabled ✗，
        //   而控件树里已经没有它们 ⇒ 「看不见但仍在动的条目」✗。
        //   这里显式清空 ✓；消费方（updateAnims / tilesHidden / panelHidden）本来就做了 null 检查 ✓ 安全 ✓。
        java.util.Arrays.fill(tiles, null);
        panel = null;
        titleLine1 = null;
        titleLine2 = null;

        state = State.IDLE;
        next = null;
        // 重新显示（从大类详情返回）时从头播一遍出现动画；速度也要复原（离开时为了不拖沓调快过）
        titleAnim.speed(7.0).snap(0.0);

        panelW = Math.max(92, Math.min(170, Math.round(width * 0.30f)));

        final RevealPanel left = new RevealPanel();
        left.dir = RevealPanel.Dir.TOP_DOWN;
        left.reveal.speed(6.0).to(1.0);
        left.prefW = panelW;
        left.prefH = Widget.FILL;
        left.chevron = false;
        // 标题与图标都长在色块上：裁在「已经长出来的高度」里，随着色块往下推而露出来
        left.clipToReveal = true;
        panel = left;

        final int titleY = Math.round(height * 0.14f);
        titleLine1 = new Label(Component.translatable("gui.sablestopnow.title.line1")).scale(TITLE_SCALE).shadow(true);
        titleLine1.marginTop = titleY;
        titleLine2 = new Label(Component.translatable("gui.sablestopnow.title.line2")).scale(0.95f);
        // 第二行的纵坐标按「第一行自己的行高」推出来，而不是写死 9*1.8：
        // 标题用的是覆盖字号（不受界面字号缩放影响），行高由 Label 按 baseLine*scale 量出，
        // 这里必须用同一个来源，否则主题一改 baseLine 两行就会叠在一起。
        titleLine2.marginTop = titleY + Math.round(UiTheme.current().baseLine * TITLE_SCALE) + 2;
        left.add(titleLine1, titleLine2);

        for (int i = 0; i < CATEGORIES.length; i++) {
            final ModConfigCategory category = CATEGORIES[i];
            final IconTile tile = new IconTile(category::icon, category::title, category::subtitle);
            tile.id("category_" + category.id());
            tile.iconSize = TILE_ICON;
            tile.prefW = Math.max(40, panelW - rowX() - 8);
            tile.prefH = TILE_H;
            tile.marginTop = rowY(i);
            // 依次出现：后面的收敛更慢，于是自然错开（速度差就是错开量）
            tile.appearSpeed(5.0 + i * 1.1);
            tile.action(() -> enter(category, tile));
            tiles[i] = tile;
            left.add(tile);
        }

        root.add(left);
    }

    private int rowX() {
        return Math.max(8, Math.round(panelW * 0.14f));
    }

    private int rowY(final int index) {
        return Math.round(height * 0.44f) + index * TILE_GAP;
    }

    // ============ 每帧动画 ============

    private double frameDelta() {
        final long now = System.nanoTime();
        final double dt = lastNanos == 0L ? 1.0 / 60.0 : (now - lastNanos) / 1.0e9;
        lastNanos = now;
        return Math.max(0.0, Math.min(0.1, dt));
    }

    private void updateAnims() {
        final double dt = frameDelta();
        final boolean instant = !UiSettings.animations;

        // 依次登场：色块先落下来 → 标题再滑入 → 三个图标再从左到右依次出现。
        // 闸门用「前一个的进度」而不是写死延时：动画关掉、掉帧或窗口尺寸变化时顺序依然成立。
        final double panelProgress = instant || panel == null ? 1.0 : panel.reveal.value();
        titleAnim.to(state == State.IDLE && panelProgress > 0.35 ? 1.0 : 0.0);
        titleAnim.tick(UiSettings.animations, dt);

        // 标题：从左到右滑入，同时淡入；位置与透明度共用一个动画值
        final float t = Anim.easeOut(titleAnim.progress());
        final int titleX = Math.round(Anim.lerp(-panelW * 0.9f, panelW * 0.10f, t));
        for (final Label label : new Label[]{titleLine1, titleLine2}) {
            if (label != null) {
                label.marginLeft = titleX;
                label.alpha = titleAnim.progress();
            }
        }

        final double titleProgress = instant ? 1.0 : titleAnim.value();
        for (int i = 0; i < tiles.length; i++) {
            final IconTile tile = tiles[i];
            if (tile == null) {
                continue;
            }
            // 第 i 个要等前一个露出大半才开始（第一个等标题）
            final double gate = instant ? 1.0 : (i == 0 ? titleProgress : tiles[i - 1].appear.value());
            tile.appear.to(state == State.IDLE && gate > 0.6 ? 1.0 : 0.0);
            tile.marginLeft = Math.round(Anim.lerp(-panelW * 0.7f, rowX(), tile.progress()));
            tile.enabled = state == State.IDLE;
        }

        if (state == State.LEAVING && titleAnim.settled() && panelHidden() && tilesHidden()) {
            state = State.SWITCH_PENDING;
        }
    }

    private boolean panelHidden() {
        return panel == null || panel.reveal.settled();
    }

    private boolean tilesHidden() {
        for (final IconTile tile : tiles) {
            if (tile != null && !tile.appear.settled()) {
                return false;
            }
        }
        return true;
    }

    // ============ 渲染与切换 ============

    @Override
    public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        // 先推进动画，再让库布局（布局会读到这里写进去的 margin），否则位置永远慢一帧
        updateAnims();
        super.render(graphics, mouseX, mouseY, partialTick);
        if (state == State.SWITCH_PENDING) {
            state = State.IDLE;
            if (minecraft != null) {
                final Screen target = next;
                // 不能在渲染中途换屏：交给下一 tick
                minecraft.execute(() -> minecraft.setScreen(target));
            }
        }
    }

    // ============ 进入 / 离开 ============

    private void enter(final ModConfigCategory category, final IconTile from) {
        if (state != State.IDLE) {
            return;
        }
        // 把图标此刻的位置与缩放交给详情页，让「移动并放大」是从玩家点的那个图标接上去的
        beginLeave(new ModSettingsCategoryScreen(this, category,
                from.iconCenterX(), from.iconCenterY(), from.iconScale()));
    }

    @Override
    public void onClose() {
        beginLeave(parent);
    }

    private void beginLeave(@Nullable final Screen target) {
        if (state != State.IDLE) {
            return;
        }
        state = State.LEAVING;
        next = target;
        // 反向：先收色块、标题与图标跟着退回去；反向时序用更快的收敛，避免玩家等太久
        titleAnim.speed(11.0).to(0.0);
        if (panel != null) {
            panel.reveal.speed(9.0).to(0.0);
        }
        // 依次出现的逆过程：越靠后的图标收得越快（最后出现的先走）
        for (int i = 0; i < tiles.length; i++) {
            if (tiles[i] != null) {
                tiles[i].appear.speed(12.0 + (tiles.length - 1 - i) * 1.1).to(0.0);
            }
        }
    }
}
