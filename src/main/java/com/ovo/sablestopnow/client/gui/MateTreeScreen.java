package com.ovo.sablestopnow.client.gui;

import com.ovo.sablestopnow.client.MateClientState;
import com.ovo.sablestopnow.client.StaffEnhanceClientHandler;
import com.ovo.sablestopnow.client.StaffKeyMappings;
import com.ovo.sablestopnow.mate.Mate;
import com.ovo.sablestopnow.mate.MateType;
import com.ovo.sablestopnow.network.MateNetworking;
import com.mojang.blaze3d.platform.InputConstants;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 配合树形界面 —— 对应草稿里的「物理结构 → 其上的配合」两级列表。
 *
 * <p><b>为什么是树而不是平铺列表：</b>一条配合天生连着两个物理结构，而玩家的实际问题是
 * 「我手上这块结构跟谁配合了、怎么配合的」。所以顶层按<b>物理结构</b>分组，每个结构下面挂它牵扯到的
 * 全部配合；一条跨结构配合会<b>同时</b>出现在两侧结构下面（同一行数据渲染两次，行尾用另一侧结构名
 * 做后缀「→ 物理结构2」区分）—— 从任一侧都能看到它，这比让玩家猜「这条配合到底归谁」要直观得多。
 * 结构名从 Sable 客户端容器里的 {@link SubLevel#getName()} 现取；没命名就退化成 UUID 前 8 位，
 * 已移除的结构直接跳过（它的配合仍会挂在对面那个还活着的结构下）。
 *
 * <p><b>为什么要自动展开定位：</b>玩家刚在两块结构之间做成一条配合，下一步几乎必然是核对/微调它，
 * 而配合表可能很长。因此服务端在建配成功的回执里带上新配合的 id（见 {@link MateClientState}），
 * 界面打开时把这个 id 所在的结构展开、把列表滚动到该行、并给该行一段高亮脉冲 —— 免去玩家自己翻找。
 * 目标 id 只在 {@code init()} 里消费一次：窗口尺寸变化会再次触发 {@code init()}，若不缓存就会反复
 * 抢滚动位置，玩家一拉窗口列表就跳回去。
 *
 * <p><b>动画/风格：</b>沿用 {@link ModConfigScreen} / {@link ModConfigCategoryScreen} 的蓝色面板，
 * 面板沿竖直方向由薄到厚展开，标题淡入，每一行按序号错开速度从左滑入；关闭时全部反向、播完再切界面。
 */
public class MateTreeScreen extends Screen {

    // ---- 布局常量 ----
    private static final int STRUCTURE_H = 20;
    private static final int MATE_H = 22;
    /**
     * 配合行展开后多出来的一条「数值 + 更改按钮」明细带的高度。
     *
     * <p>以前这些控件全挤在 22 像素的紧凑行里，和名字、对面结构名抢宽度，边栏一窄就叠在一起。
     * 现在紧凑行只放图标、名字和展开箭头，数值与更改按钮一律下移到这条明细带里。
     */
    private static final int MATE_DETAIL_H = 20;
    /** 配合行相对结构行的缩进。 */
    private static final int INDENT = 12;
    private static final int ICON_BOX = 16;
    /** 展开箭头的占位宽度（箭头本身约 5px，留点余量免得贴着文字）。 */
    private static final int CHEVRON_W = 7;
    private static final int SMALL_BUTTON = 12;

    // ---- 按钮种类（用 int 常量而不是 enum：这几个值只在本类内部用，且 switch 需要编译期常量）----
    private static final int BTN_ICON = 0;
    private static final int BTN_DELETE = 1;
    private static final int BTN_MINUS = 2;
    private static final int BTN_PLUS = 3;
    private static final int BTN_ALIGN = 4;
    /** 结构行的「锁定」按钮（航空学 FixedConstraint，与左键锁定共用同一份状态）。 */
    private static final int BTN_LOCK = 5;

    /** 距离类配合的步进；角度类固定 15°，与草稿一致。 */
    private static final double DISTANCE_STEP = 0.5;
    private static final double ANGLE_STEP = 15.0;

    /** 要自动展开定位的配合；构造时给的是「优先值」，为 null 时从客户端状态里取一次。 */
    @Nullable
    private UUID focusMateId;
    private boolean focusResolved;

    private long lastFrame;

    private float panelAnim;
    private float titleAnim;
    /** 展开/收起的结构（默认全部展开，所以存的是「收起的」）。 */
    private final Set<UUID> collapsed = new HashSet<>();
    /**
     * 已展开的配合行（结构行用 {@link #collapsed}，语义相反）。
     *
     * <p><b>默认收起</b>：一条配合在收起状态下只显示类型图标与名字；数值、步进、对齐、删除
     * 这些「详细信息和更改」必须展开该配合后才出现。新建的配合由
     * {@link #expandAround(UUID)} 自动展开。
     */
    private final Set<UUID> expandedMates = new HashSet<>();
    /** 每行的出现动画，key 见 {@link TreeRow#key}；用 key 而不是下标，mate 增删后才不会串行。 */
    private final Map<String, Float> rowAnim = new HashMap<>();
    private float scrollTarget;
    private float scrollAnim;
    /** 1 → 0 的定位高亮脉冲。 */
    private float focusPulse;

    private List<TreeRow> rows = new ArrayList<>();
    /** 当前有效的结构（已移除的不在里面）。 */
    private final Set<UUID> validBodies = new HashSet<>();
    /** 结构 UUID → 显示名（本帧的容器查询结果，渲染时不再逐行查容器）。 */
    private final Map<UUID, String> structureNames = new HashMap<>();

    /**
     * @param parent      仅为兼容调用方保留；常驻边栏没有「返回上一级界面」的概念，
     *                    退出配合模式直接回到游戏。传 null 即可。
     * @param focusMateId 要自动展开定位的配合
     */
    public MateTreeScreen(@Nullable final Screen parent, @Nullable final UUID focusMateId) {
        super(Component.translatable("gui.sablestopnow.mate.title"));
        this.focusMateId = focusMateId;
        // 开屏这一刻，这些键很可能还按着（Y 就是用来开这个界面的）。
        // 原版会把按键重复（GLFW_REPEAT）也送进 Screen.keyPressed，于是「按 Y 进来」会立刻被
        // 当成「按 Y 退出」—— 界面闪一下就消失。所以先闩住，等真正松开才认下一次按下。
        latch(StaffKeyMappings.MATE_MODE);
        latch(StaffKeyMappings.MATE_TREE);
        latch(StaffKeyMappings.MATE_MOUSE);
    }

    /** 把一条 KeyMapping 绑定的键闩住（等它松开后才允许触发）。 */
    private void latch(final KeyMapping mapping) {
        final InputConstants.Key key = mapping.getKey();
        if (key.getType() == InputConstants.Type.KEYSYM) {
            this.latchedKeys.add(key.getValue());
        }
    }

    // ============ 布局 ============
    //
    // 这是一条<b>常驻右侧边栏</b>，不是一个弹窗：
    //  · 贴右边、满高，宽度最多占屏幕 1/3（再宽就把世界挡没了）；
    //  · 进入配合模式就一直在，鼠标锁定状态下它是「只读看板」（准星可转、右键选取）；
    //  · 不画背景模糊，否则整个世界的可视性都没了。

    /** 边栏最大宽度占屏幕的比例。 */
    private static final float MAX_PANEL_FRACTION = 0.34f;

    private int panelW() {
        return Math.clamp(this.width / 4, 104, Math.max(104, (int) (this.width * MAX_PANEL_FRACTION)));
    }

    private int panelX() {
        return this.width - panelW();
    }

    /** 贴顶满高：常驻边栏不该有上下浮动。 */
    private int panelY() {
        return 0;
    }

    private int panelH() {
        return this.height;
    }

    private int listX() {
        return panelX() + 6;
    }

    private int listRight() {
        return panelX() + panelW() - 6;
    }

    private int listTop() {
        return panelY() + 26;
    }

    private int listBottom() {
        return panelY() + panelH() - 18;
    }

    private int viewHeight() {
        return Math.max(0, listBottom() - listTop());
    }

    private int rowHeight(final TreeRow row) {
        if (row.structure) {
            return STRUCTURE_H;
        }
        return MATE_H + (isMateExpanded(row) ? MATE_DETAIL_H : 0);
    }

    /** 这条配合行是否处于展开状态（展开才有数值与更改按钮）。 */
    private boolean isMateExpanded(final TreeRow row) {
        return row.mate != null && this.expandedMates.contains(row.mate.id());
    }

    /** 第 index 行相对内容顶部的偏移。 */
    private int rowOffset(final int index) {
        int offset = 0;
        for (int i = 0; i < index && i < this.rows.size(); i++) {
            offset += rowHeight(this.rows.get(i));
        }
        return offset;
    }

    private int rowY(final int index) {
        return listTop() + rowOffset(index) - (int) this.scrollAnim;
    }

    private int contentHeight() {
        int total = 0;
        for (final TreeRow row : this.rows) {
            total += rowHeight(row);
        }
        return total;
    }

    private float maxScroll() {
        return Math.max(0.0f, contentHeight() - viewHeight());
    }

    // ============ 生命周期 ============

    @Override
    protected void init() {
        if (!this.focusResolved) {
            this.focusResolved = true;
            if (this.focusMateId == null) {
                // 建配成功时服务端回执塞进来的待定位目标，只消费一次
                this.focusMateId = MateClientState.consumeFocusMate();
            }
            if (this.focusMateId != null) {
                expandAround(this.focusMateId);
                this.focusPulse = 1.0f;
            }
        }
        rebuild();
        applyFocusScroll();
    }

    /** 尚未松开的键（开屏瞬间按着的那些）；松开之前一律不响应。 */
    private final Set<Integer> latchedKeys = new HashSet<>();

    @Override
    public void onClose() {
        // Esc / Y 都表示「退出配合模式」。边栏是常驻的，单独把它关掉没有意义，
        // 关掉之后玩家会处在一个「还在配合模式但看不到界面」的夹缝状态。
        com.ovo.sablestopnow.SablestopNow.LOGGER.info("[mate] sidebar onClose() -> exit mate mode");
        MateClientState.requestExitMode();
    }

    @Override
    public void removed() {
        // 诊断：removed() 是在 Minecraft.setScreen 里「换屏时对旧屏」调用的，
        // 此刻 this.screen 还没换成新屏，所以要延后一拍再看「到底被谁顶掉了」。
        final Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[mate] sidebar was evicted; the screen that replaced it = {}",
                mc.screen == null ? "null" : mc.screen.getClass().getName()));
        super.removed();
    }

    /**
     * 边栏里的按键。
     *
     * <p>不能用 {@code KeyMapping.consumeClick()}：原版在<b>开屏期间根本不更新 KeyMapping</b>
     * （见 {@link StaffKeyMappings} 的注释），所以这里直接拿按键码去比。
     *
     * <p>另有一层 {@link #latchedKeys} 闩锁：开屏那一刻这三个键很可能还按着，
     * 不闩住的话「按 Y 进来」会被立刻当成「按 Y 退出」。
     */
    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        // 开屏时还按着的键（含 GLFW 的按键重复）直接吞掉，避免「进来的那一下把界面又关掉」
        if (this.latchedKeys.contains(keyCode)) {
            return true;
        }
        if (boundTo(StaffKeyMappings.MATE_MODE, keyCode) || boundTo(StaffKeyMappings.MATE_TREE, keyCode)) {
            com.ovo.sablestopnow.SablestopNow.LOGGER.info("[mate] sidebar keyPressed({}) -> exit mate mode", keyCode);
            MateClientState.requestExitMode();
            return true;
        }
        if (boundTo(StaffKeyMappings.MATE_MOUSE, keyCode)) {
            MateClientState.toggleCursorLock();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(final int keyCode, final int scanCode, final int modifiers) {
        this.latchedKeys.remove(keyCode);
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    /** 该按键码是否就是这条 KeyMapping 绑定的键（只认键盘类型）。 */
    private static boolean boundTo(final KeyMapping mapping, final int keyCode) {
        final InputConstants.Key key = mapping.getKey();
        return key.getType() == InputConstants.Type.KEYSYM && key.getValue() == keyCode;
    }

    /** 定位目标所在的结构一定展开（它可能被玩家手动收起来过），配合行本身也自动展开。 */
    private void expandAround(final UUID mateId) {
        for (final Mate mate : MateClientState.currentMates()) {
            if (mate.id().equals(mateId)) {
                this.collapsed.remove(mate.a().body());
                this.collapsed.remove(mate.b().body());
                // 新配合自动展开：玩家一眼能看到数值和更改按钮，不用再点一下
                this.expandedMates.add(mateId);
            }
        }
    }

    /** 把定位目标那一行滚进可视区（只做最小移动，不让列表无谓地跳）。 */
    private void applyFocusScroll() {
        if (this.focusMateId == null) {
            return;
        }
        final int view = viewHeight();
        if (view <= 0) {
            return;
        }
        for (int i = 0; i < this.rows.size(); i++) {
            final TreeRow row = this.rows.get(i);
            if (row.mate == null || !row.mate.id().equals(this.focusMateId)) {
                continue;
            }
            final int top = rowOffset(i);
            final int bottom = top + rowHeight(row);
            float target = this.scrollTarget;
            if (top < target) {
                target = top;
            } else if (bottom > target + view) {
                target = bottom - view;
            }
            this.scrollTarget = Math.clamp(target, 0.0f, maxScroll());
            return;
        }
    }

    // ============ 数据 → 行 ============

    /**
     * 重建行列表。每帧调用：配合表由服务端广播，随时可能变；结构名也每帧查一次容器
     * （<b>整帧只查一次</b>，逐行查容器在结构多的时候会明显掉帧）。
     */
    private void rebuild() {
        final List<Mate> mates = MateClientState.currentMates();
        this.validBodies.clear();
        this.structureNames.clear();

        final List<UUID> bodies = new ArrayList<>();
        for (final Mate mate : mates) {
            addBody(bodies, mate.a().body());
            addBody(bodies, mate.b().body());
        }

        final ClientLevel level = this.minecraft == null ? null : this.minecraft.level;
        final SubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
        for (final UUID body : bodies) {
            if (container == null) {
                // 拿不到容器（未连接/维度未就绪）时宁可显示 UUID，也不要让整个界面空掉
                this.validBodies.add(body);
                this.structureNames.put(body, shortId(body));
                continue;
            }
            final SubLevel sub = container.getSubLevel(body);
            if (sub == null || sub.isRemoved()) {
                continue;
            }
            this.validBodies.add(body);
            final String name = sub.getName();
            this.structureNames.put(body, name == null || name.isBlank() ? shortId(body) : name);
        }

        this.rows = new ArrayList<>();
        for (final UUID body : bodies) {
            if (!this.validBodies.contains(body)) {
                continue;
            }
            this.rows.add(TreeRow.structure(body, this.structureNames.get(body)));
            if (this.collapsed.contains(body)) {
                continue;
            }
            int index = 1;
            for (final Mate mate : mates) {
                if (mate.touches(body)) {
                    this.rows.add(TreeRow.mate(body, mate, index));
                    index++;
                }
            }
        }
    }

    private static void addBody(final List<UUID> bodies, final UUID body) {
        if (!bodies.contains(body)) {
            bodies.add(body);
        }
    }

    private static String shortId(final UUID body) {
        final String text = body.toString();
        return text.length() <= 8 ? text : text.substring(0, 8);
    }

    // ============ 动画 ============

    private void updateAnims() {
        final long now = Util.getMillis();
        float dt = this.lastFrame == 0 ? 0.016f : (now - this.lastFrame) / 1000.0f;
        this.lastFrame = now;
        dt = Math.clamp(dt, 0.0f, 0.1f);

        this.panelAnim = Ease.approach(this.panelAnim, 1.0f, 6.0f, dt);
        this.titleAnim = Ease.approach(this.titleAnim, 1.0f, 7.0f, dt);

        final Set<String> live = new HashSet<>();
        for (int i = 0; i < this.rows.size(); i++) {
            final String key = this.rows.get(i).key;
            live.add(key);
            // 依次出现：后面的行速度略慢 → 天然错开
            final float speed = 6.0f + i * 0.9f;
            this.rowAnim.put(key, Ease.approach(animOf(key), 1.0f, speed, dt));
        }
        // 已消失的行（配合被删/结构被收）丢掉动画状态，免得 map 无限增长
        this.rowAnim.keySet().retainAll(live);

        this.scrollAnim = Ease.approach(this.scrollAnim, this.scrollTarget, 12.0f, dt);
        this.focusPulse = Math.max(0.0f, this.focusPulse - dt * 0.7f);
    }

    private float animOf(final String key) {
        final Float value = this.rowAnim.get(key);
        return value == null ? 0.0f : value;
    }

    private boolean allRowsHidden() {
        for (final float value : this.rowAnim.values()) {
            if (value > 0.02f) {
                return false;
            }
        }
        return true;
    }

    // ============ 渲染 ============

    @Override
    public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        // 原版切屏时会自己 releaseMouse()，所以「锁定」不能只在 init() 里写一次，
        // 每帧按状态校正一次（只比一个 boolean，代价可忽略），否则准星会莫名其妙不能转。
        final Minecraft mc = Minecraft.getInstance();
        if (mc.mouseHandler != null && mc.mouseHandler.isMouseGrabbed() != MateClientState.cursorLocked()) {
            MateClientState.applyCursorLock(MateClientState.cursorLocked());
        }
        rebuild();
        updateAnims();

        final float panelEase = Ease.outCubic(this.panelAnim);
        if (panelEase > 0.01f) {
            drawPanel(graphics, panelEase);
        }
        drawHeader(graphics, panelEase);
        drawList(graphics, mouseX, mouseY, panelEase);
        drawFooter(graphics, panelEase);
        drawCursorButton(graphics, mouseX, mouseY, panelEase);
        // 左上角状态牌画在边栏之外：开屏时 StaffControlHud 会自动让位，得由这里接管
        drawModeIndicator(graphics);
    }

    /** 面板：一块半透明蓝色边栏，宽度由右向左展开（透明度跟着进场动画走）。 */
    private void drawPanel(final GuiGraphics graphics, final float ease) {
        if (ease <= 0.01f) {
            return;
        }
        final int x0 = panelX();
        final int x1 = this.width;
        graphics.fill(x0, 0, x1, this.height, Ease.fade(Ease.PANEL, ease));
        graphics.fill(x0, 0, x0 + 1, this.height, Ease.fade(Ease.EDGE, ease));
    }

    /**
     * 刻意<b>不</b>画背景。
     *
     * <p>{@code Screen.renderBackground} 默认会把世界做高斯模糊并压暗——对一条常驻边栏来说
     * 那是灾难：玩家要一边看结构一边在边栏上操作，世界糊掉就没法用了。
     */
    @Override
    public void renderBackground(final GuiGraphics graphics, final int mouseX, final int mouseY,
                                 final float partialTick) {
        // 什么都不画
    }

    /**
     * 左上角的「配合模式」状态牌。
     *
     * <p>边栏是 Screen，原版在开屏时不会再走 {@code StaffControlHud}，所以手杖那套左上角 HUD
     * 会自动消失——这里必须接管，否则玩家进了配合模式反而看不到任何当前状态。
     */
    private void drawModeIndicator(final GuiGraphics graphics) {
        if (!MateClientState.inMode()) {
            return;
        }
        final int x = 6;
        int y = 6;

        final String title = I18n.get("sablestopnow.mate.hud.title");
        final int titleW = this.font.width(title) + 12;
        graphics.fill(x, y, x + titleW, y + 14, Ease.PANEL);
        graphics.fill(x, y, x + titleW, y + 1, Ease.BUTTON_ON);
        graphics.drawString(this.font, title, x + 6, y + 3, Ease.TEXT, true);
        y += 17;

        final boolean locked = MateClientState.cursorLocked();
        final String mouse = I18n.get(locked
                ? "sablestopnow.mate.hud.mouse_locked"
                : "sablestopnow.mate.hud.mouse_free");
        graphics.drawString(this.font, mouse, x, y, locked ? 0xFF8BE38B : 0xFFFFC66D, false);
        y += 10;

        final int picked = MateClientState.pendingCount();
        graphics.drawString(this.font, I18n.get("sablestopnow.mate.hud.picked", picked), x, y,
                picked > 0 ? 0xFF8BE38B : Ease.TEXT_DIM, false);
        y += 10;

        final com.ovo.sablestopnow.mate.MateRef target = MateClientState.previewTarget();
        final String aimed = target == null
                ? I18n.get("sablestopnow.mate.hud.no_target")
                : I18n.get("sablestopnow.mate.hud.target") + " "
                        + I18n.get("mate.ref." + target.kind().id());
        graphics.drawString(this.font, aimed, x, y, target == null ? Ease.TEXT_DIM : 0xFF9AD9FF, false);
        y += 12;

        // 提示行跟着鼠标状态变：锁定时告诉玩家怎么放鼠标，放开时告诉玩家怎么锁回去
        for (final net.minecraft.util.FormattedCharSequence line : this.font.split(
                Component.translatable(locked
                        ? "sablestopnow.mate.hud.hint_locked"
                        : "sablestopnow.mate.hud.hint_free"),
                168)) {
            graphics.drawString(this.font, line, x, y, Ease.TEXT_DIM, false);
            y += 9;
        }
    }

    /**
     * 边栏底部那条「鼠标锁定」按钮；返回 {x, y, w, h}。
     *
     * <p>放在底部整条而不是标题右侧：边栏只有屏幕 1/3 宽，标题那行再塞个按钮会打架。
     * 另外它是<b>释放鼠标之后才点得到</b>的——锁着的时候用 Tab 放出来。
     */
    private int[] cursorButtonRect() {
        return new int[] { panelX() + 6, listBottom() + 3, panelW() - 12, 12 };
    }

    /** 画「鼠标锁定 / 释放」按钮，并显示当前状态。 */
    private void drawCursorButton(final GuiGraphics graphics, final int mouseX, final int mouseY,
                                  final float ease) {
        if (ease <= 0.01f) {
            return;
        }
        final int[] r = cursorButtonRect();
        final boolean locked = MateClientState.cursorLocked();
        final boolean hovered = mouseX >= r[0] && mouseX <= r[0] + r[2]
                && mouseY >= r[1] && mouseY <= r[1] + r[3];
        final int bg = hovered ? Ease.BUTTON_HOVER : (locked ? Ease.BUTTON_ON : Ease.BUTTON);
        graphics.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], Ease.fade(bg, ease * 0.9f));
        if (locked) {
            // 锁定时把整条画亮一点，作为「当前处于锁定」的强提示
            graphics.fill(r[0], r[1], r[0] + r[2], r[1] + 1, Ease.fade(0xFFFFFFFF, ease));
        }
        final String label = I18n.get(locked
                ? "sablestopnow.mate.hud.button_unlock"
                : "sablestopnow.mate.hud.button_lock");
        graphics.drawCenteredString(this.font, label, r[0] + r[2] / 2, r[1] + 2,
                Ease.fade(locked ? 0xFF10203A : Ease.TEXT, ease));
    }

    private void drawHeader(final GuiGraphics graphics, final float ease) {
        if (ease <= 0.01f) {
            return;
        }
        final int titleColor = Ease.fade(Ease.TEXT, this.titleAnim * ease);
        graphics.pose().pushPose();
        graphics.pose().translate(panelX() + 10, panelY() + 8, 0);
        graphics.pose().scale(1.25f, 1.25f, 1.0f);
        graphics.drawString(this.font, Component.translatable("gui.sablestopnow.mate.title"),
                0, 0, titleColor, true);
        graphics.pose().popPose();
        graphics.fill(panelX() + 6, listTop() - 3, listRight(), listTop() - 2, Ease.fade(Ease.EDGE, ease));
    }

    private void drawList(final GuiGraphics graphics, final int mouseX, final int mouseY, final float ease) {
        final int top = listTop();
        final int bottom = listBottom();
        if (bottom - top <= 4) {
            return;
        }
        if (this.rows.isEmpty()) {
            drawEmpty(graphics, ease);
            return;
        }
        graphics.enableScissor(panelX() + 2, top, panelX() + panelW() - 2, bottom);
        for (int i = 0; i < this.rows.size(); i++) {
            final TreeRow row = this.rows.get(i);
            final int y = rowY(i);
            final int h = rowHeight(row);
            if (y + h < top - 2 || y > bottom + 2) {
                continue;
            }
            final float anim = Ease.outCubic(animOf(row.key)) * ease;
            if (anim <= 0.01f) {
                continue;
            }
            if (row.structure) {
                drawStructureRow(graphics, row, i, y, h, mouseX, mouseY, anim);
            } else {
                drawMateRow(graphics, row, i, y, h, mouseX, mouseY, anim);
            }
        }
        graphics.disableScissor();
    }

    private void drawEmpty(final GuiGraphics graphics, final float ease) {
        graphics.drawCenteredString(this.font, Component.translatable("gui.sablestopnow.mate.empty"),
                panelX() + panelW() / 2, (listTop() + listBottom()) / 2 - 4,
                Ease.fade(Ease.TEXT_DIM, ease));
    }

    /** 结构行：折叠标记 + 结构名 + 右端锁定按钮，整行可点（切换展开）。 */
    private void drawStructureRow(final GuiGraphics graphics, final TreeRow row, final int index,
                                  final int y, final int h,
                                  final int mouseX, final int mouseY, final float anim) {
        final int x = listX();
        final int right = listRight();
        final boolean hovered = mouseX >= x && mouseX <= right && mouseY >= y && mouseY <= y + h - 2;
        graphics.fill(x, y, right, y + h - 2, Ease.fade(hovered ? Ease.BUTTON : Ease.PANEL_DARK,
                anim * (hovered ? 0.95f : 0.8f)));
        if (hovered) {
            graphics.fill(x, y, right, y + 1, Ease.fade(Ease.BUTTON_ON, anim));
        }
        final boolean expanded = row.body != null && !this.collapsed.contains(row.body);
        drawChevron(graphics, x + 5, y + (h - 2) / 2 - 4, expanded, Ease.fade(Ease.TEXT_DIM, anim));
        // 结构名要给右端的锁定按钮让位
        final String title = row.title == null ? "" : this.font.plainSubstrByWidth(row.title,
                Math.max(20, right - x - 42));
        graphics.drawString(this.font, title, x + 18, y + (h - 2 - 8) / 2, Ease.fade(Ease.TEXT, anim), true);

        // 锁定按钮：复用配合类型里那枚「锁」图标；锁上时亮金色、未锁时暗灰
        final RowButton lock = findButton(layoutButtons(row, index), BTN_LOCK);
        if (lock != null && row.body != null) {
            final boolean locked = StaffEnhanceClientHandler.isBodyLocked(row.body);
            final boolean lockHovered = isHovered(lock, mouseX, mouseY);
            graphics.fill(lock.x, lock.y, lock.x + lock.w, lock.y + lock.h,
                    Ease.fade(lockHovered ? Ease.BUTTON_HOVER : Ease.PANEL, anim * 0.9f));
            MateIcons.draw(graphics, MateType.LOCK, lock.x, lock.y, SMALL_BUTTON,
                    Ease.fade(locked ? 0xFFFFD479 : Ease.TEXT_DIM, anim));
        }
    }

    /** 紧凑行：图标（点击换类型）+ 名字 + 展开箭头；展开后下面再多一条明细带。 */
    private void drawMateRow(final GuiGraphics graphics, final TreeRow row, final int index, final int y, final int h,
                             final int mouseX, final int mouseY, final float anim) {
        final Mate mate = row.mate;
        if (mate == null) {
            return;
        }
        final int x = listX() + INDENT;
        final int right = listRight();
        final boolean expanded = isMateExpanded(row);
        final List<RowButton> buttons = layoutButtons(row, index);

        final boolean focusedRow = this.focusMateId != null && this.focusMateId.equals(mate.id());
        final boolean hovered = !focusedRow && mouseX >= x && mouseX <= right && mouseY >= y && mouseY <= y + h - 2;

        if (focusedRow) {
            // 定位高亮：脉冲衰减到 0.45 后保持，这样「在哪一行」一直看得见
            final float pulse = 0.45f + 0.55f * this.focusPulse;
            graphics.fill(x, y, right, y + h - 2, Ease.fade(Ease.BUTTON_HOVER, anim * 0.55f * pulse));
            graphics.fill(x, y, x + 2, y + h - 2, Ease.fade(Ease.BUTTON_ON, anim * pulse));
        } else {
            graphics.fill(x, y, right, y + h - 2, Ease.fade(hovered ? 0x40FFFFFF : 0x28000000, anim));
        }

        final RowButton icon = findButton(buttons, BTN_ICON);
        final int labelX;
        if (icon == null) {
            labelX = x + 4;
        } else {
            final boolean iconHovered = isHovered(icon, mouseX, mouseY);
            graphics.fill(icon.x, icon.y, icon.x + icon.w, icon.y + icon.h,
                    Ease.fade(iconHovered ? Ease.BUTTON_HOVER : Ease.BUTTON, anim * 0.8f));
            MateIcons.draw(graphics, mate.type(), icon.x, icon.y, ICON_BOX,
                    Ease.fade(iconHovered ? Ease.TEXT : Ease.TEXT_DIM, anim));
            labelX = icon.x + icon.w + 4;
        }

        // 紧凑行：文字右边界只让出展开箭头，数值与按钮都在下面的明细带里，不会再压到名字上
        final int textY = y + (MATE_H - 2 - 8) / 2;
        final int labelRight = right - CHEVRON_W - 6;
        final int available = Math.max(24, labelRight - labelX - 4);
        final String label = this.font.plainSubstrByWidth(mateLabel(row).getString(), available);
        graphics.drawString(this.font, label, labelX, textY, Ease.fade(Ease.TEXT, anim), true);

        // 行尾的「→ 对面结构名」：同一条配合会挂在两侧结构下，用这个后缀区分
        final String other = this.structureNames.get(mate.other(row.body));
        if (other != null) {
            final int otherX = labelX + this.font.width(label) + 5;
            if (otherX < labelRight - 12) {
                final String shown = this.font.plainSubstrByWidth("\u2192 " + other, labelRight - otherX - 2);
                graphics.drawString(this.font, shown, otherX, textY, Ease.fade(Ease.TEXT_DIM, anim), false);
            }
        }

        // 展开箭头：点整行切换（配合行没有别的「空白区」功能，整行当开关最省事）
        drawChevron(graphics, right - CHEVRON_W - 1, y + (MATE_H - 2) / 2 - 2, expanded,
                Ease.fade(hovered ? Ease.TEXT : Ease.TEXT_DIM, anim));
        if (!expanded) {
            return;
        }

        // ---- 明细带：左边写数值，右边排更改按钮，两块区域互不相交 ----
        final int detailTop = y + MATE_H;
        graphics.fill(x, detailTop, right, y + h - 2, Ease.fade(0x30000000, anim));
        final int detailTextY = detailTop + (MATE_DETAIL_H - 2 - 8) / 2;
        final int detailRight = leftmostNonIconButton(buttons, right);
        if (mate.type().needsValue()) {
            final String value = Component.translatable("gui.sablestopnow.mate.value", formatValue(mate)).getString();
            final String shown = this.font.plainSubstrByWidth(value, Math.max(16, detailRight - (x + 4) - 6));
            graphics.drawString(this.font, shown, x + 4, detailTextY, Ease.fade(Ease.TEXT_DIM, anim), false);
        } else if (mate.supportsAlignment()) {
            // 不需要数值的配合（重合/平行/同心…）：把「当前对齐方向」写在这儿，省得只靠按钮文字猜
            final String shown = this.font.plainSubstrByWidth(alignLabel(mate).getString(),
                    Math.max(16, detailRight - (x + 4) - 6));
            graphics.drawString(this.font, shown, x + 4, detailTextY, Ease.fade(Ease.TEXT_DIM, anim), false);
        }

        for (final RowButton button : buttons) {
            if (button.kind == BTN_ICON) {
                continue;
            }
            drawButton(graphics, button, mate, mouseX, mouseY, anim);
        }
    }

    private void drawButton(final GuiGraphics graphics, final RowButton button, final Mate mate,
                            final int mouseX, final int mouseY, final float anim) {
        final boolean hovered = isHovered(button, mouseX, mouseY);
        graphics.fill(button.x, button.y, button.x + button.w, button.y + button.h,
                Ease.fade(hovered ? Ease.BUTTON_HOVER : Ease.BUTTON, anim * 0.95f));
        final int fg = Ease.fade(hovered ? Ease.TEXT : Ease.TEXT_DIM, anim);
        switch (button.kind) {
            case BTN_DELETE -> {
                line(graphics, button.x + 4, button.y + 4, button.x + button.w - 4, button.y + button.h - 4, fg);
                line(graphics, button.x + 4, button.y + button.h - 4, button.x + button.w - 4, button.y + 4, fg);
            }
            case BTN_MINUS -> graphics.fill(button.x + 3, button.y + button.h / 2,
                    button.x + button.w - 3, button.y + button.h / 2 + 1, fg);
            case BTN_PLUS -> {
                graphics.fill(button.x + 3, button.y + button.h / 2,
                        button.x + button.w - 3, button.y + button.h / 2 + 1, fg);
                graphics.fill(button.x + button.w / 2, button.y + 3,
                        button.x + button.w / 2 + 1, button.y + button.h - 3, fg);
            }
            case BTN_ALIGN -> graphics.drawCenteredString(this.font, alignLabel(mate),
                    button.x + button.w / 2, button.y + (button.h - 8) / 2, fg);
            default -> {
            }
        }
    }

    private void drawFooter(final GuiGraphics graphics, final float ease) {
        final int y = panelY() + panelH() - 12;
        graphics.drawString(this.font, Component.translatable("gui.sablestopnow.back"),
                panelX() + 8, y, Ease.fade(Ease.TEXT_DIM, ease), false);
        final Component hint = Component.translatable("gui.sablestopnow.mate.type_cycle");
        final int hintX = panelX() + panelW() - 8 - this.font.width(hint);
        if (hintX > panelX() + 40) {
            graphics.drawString(this.font, hint, hintX, y, Ease.fade(Ease.TEXT_DIM, ease * 0.85f), false);
        }
    }

    /** 折叠标记：展开 = 向下的「v」，收起 = 向右的「>」。用线画，避免多语言/字体差异。 */
    private static void drawChevron(final GuiGraphics graphics, final int x, final int y,
                                    final boolean expanded, final int color) {
        if (expanded) {
            line(graphics, x, y, x + 4, y + 4, color);
            line(graphics, x + 4, y + 4, x + 8, y, color);
        } else {
            line(graphics, x, y, x + 4, y + 4, color);
            line(graphics, x + 4, y + 4, x, y + 8, color);
        }
    }

    /** 1px 的逐点直线（斜线不会断）。 */
    private static void line(final GuiGraphics graphics, final int x0, final int y0,
                             final int x1, final int y1, final int color) {
        final int dx = x1 - x0;
        final int dy = y1 - y0;
        final int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps <= 0) {
            graphics.fill(x0, y0, x0 + 1, y0 + 1, color);
            return;
        }
        for (int i = 0; i <= steps; i++) {
            final float t = i / (float) steps;
            final int px = x0 + Math.round(dx * t);
            final int py = y0 + Math.round(dy * t);
            graphics.fill(px, py, px + 1, py + 1, color);
        }
    }

    // ============ 文本 ============

    /** 「配合N 类型 数值」；有自定义名就用自定义名。序号是它在当前结构子列表里的 1-based 位置。 */
    private Component mateLabel(final TreeRow row) {
        final Mate mate = row.mate;
        if (mate == null) {
            return Component.empty();
        }
        final Component base = mate.name() != null && !mate.name().isBlank()
                ? Component.literal(mate.name())
                : Component.translatable("gui.sablestopnow.mate.entry", row.index);
        final Component head = base.copy()
                .append(Component.literal(" "))
                .append(Component.translatable("mate.type." + mate.type().id()));
        if (mate.type().needsValue()) {
            return head.copy().append(Component.literal(" " + formatValue(mate)));
        }
        return head;
    }

    private static Component alignLabel(final Mate mate) {
        return Component.translatable(mate.flip()
                ? "gui.sablestopnow.mate.anti_aligned"
                : "gui.sablestopnow.mate.aligned");
    }

    private static String formatValue(final Mate mate) {
        if (mate.type().valueKind() == MateType.ValueKind.ANGLE) {
            return Math.round(mate.value()) + "\u00B0";
        }
        return String.format(Locale.ROOT, "%.1f", mate.value());
    }

    // ============ 按钮布局 ============

    private List<RowButton> layoutButtons(final TreeRow row, final int index) {
        final List<RowButton> out = new ArrayList<>(5);
        final Mate mate = row.mate;
        if (mate == null) {
            // 结构行：右端一个「锁定」按钮（锁该物理结构，与左键锁定同一份状态）
            if (row.body != null) {
                final int y = rowY(index);
                final int by = y + (STRUCTURE_H - 2 - SMALL_BUTTON) / 2;
                out.add(new RowButton(BTN_LOCK, listRight() - 4 - SMALL_BUTTON, by, SMALL_BUTTON, SMALL_BUTTON));
            }
            return out;
        }
        final int y = rowY(index);

        // 类型循环按钮就是图标本身（点图标换下一种类型）：紧凑行上唯一的按钮，永远在
        out.add(new RowButton(BTN_ICON, listX() + INDENT + 3, y + 3, ICON_BOX, ICON_BOX));

        // 收起状态到此为止：数值、步进、对齐、删除全部不出现，也就不会和名字抢宽度
        if (!isMateExpanded(row)) {
            return out;
        }

        // 展开后的明细带：控件都排在紧凑行下面那条带子里
        final int detailTop = y + MATE_H;
        final int by = detailTop + (MATE_DETAIL_H - 2 - SMALL_BUTTON) / 2;
        int right = listRight() - 4;

        right -= SMALL_BUTTON;
        out.add(new RowButton(BTN_DELETE, right, by, SMALL_BUTTON, SMALL_BUTTON));
        right -= SMALL_BUTTON + 2;

        // 用 Mate 级的判断（把「参考有没有方向」也算进去）：
        // 顶点—顶点重合没有朝向可谈，不该给出一个点了没反应的对齐按钮。
        if (mate.supportsAlignment()) {
            final int width = Math.max(SMALL_BUTTON + 8, this.font.width(alignLabel(mate)) + 8);
            right -= width;
            out.add(new RowButton(BTN_ALIGN, right, by, width, SMALL_BUTTON));
            right -= 2;
        }

        if (mate.type().needsValue()) {
            right -= SMALL_BUTTON;
            out.add(new RowButton(BTN_PLUS, right, by, SMALL_BUTTON, SMALL_BUTTON));
            right -= SMALL_BUTTON + 2;
            right -= SMALL_BUTTON;
            out.add(new RowButton(BTN_MINUS, right, by, SMALL_BUTTON, SMALL_BUTTON));
        }
        return out;
    }

    /** 最左侧那个「非图标」按钮的 x；没有按钮时返回行右边界。 */
    private static int leftmostNonIconButton(final List<RowButton> buttons, final int fallback) {
        int left = fallback;
        for (final RowButton button : buttons) {
            if (button.kind != BTN_ICON) {
                left = Math.min(left, button.x);
            }
        }
        return left;
    }

    @Nullable
    private static RowButton findButton(final List<RowButton> buttons, final int kind) {
        for (final RowButton button : buttons) {
            if (button.kind == kind) {
                return button;
            }
        }
        return null;
    }

    private static boolean isHovered(final RowButton button, final int mouseX, final int mouseY) {
        return mouseX >= button.x && mouseX <= button.x + button.w
                && mouseY >= button.y && mouseY <= button.y + button.h;
    }

    // ============ 交互 ============

    // ============ 覆盖层入口（刻意<b>不</b>进 screen 栈）============
    //
    // 这个类仍然继承 Screen —— 只为复用它的绘制原语（font / width / height），
    // 但它<b>永远不被 setScreen 推入栈</b>：常驻边栏一旦占着 screen 栈，mod 生态里
    // 任何一次 setScreen(其它界面) 都会把它顶掉（实测按 Y 后 18ms 内被顶掉两次）。
    // 因此它由 HUD 渲染事件每帧驱动，生命周期里没有 init()/removed()/onClose()。

    /**
     * 每帧由 HUD 渲染事件调用：同步分辨率并保证导航状态只初始化一次。
     *
     * <p>之所以不叫 {@code init()}：那个名字属于 Screen 的入栈生命周期，我们并不入栈；
     * 这里只做「把 Screen 的绘制前置条件补齐」。
     */
    public void prepareForOverlay(final Minecraft mc, final int width, final int height) {
        this.minecraft = mc;
        this.font = mc.font;
        this.width = width;
        this.height = height;
        if (!this.focusResolved) {
            this.focusResolved = true;
            if (this.focusMateId == null) {
                this.focusMateId = MateClientState.consumeFocusMate();
            }
            if (this.focusMateId != null) {
                expandAround(this.focusMateId);
                this.focusPulse = 1.0f;
            }
            rebuild();
            applyFocusScroll();
        }
    }

    /** 鼠标是否落在边栏上（供输入路由判断）。 */
    /**
     * 边栏当前占用的宽度（像素）。面板还没做过一次 {@link #prepareForOverlay} 时返回 0，
     * 免得给出一个假的占位把别的 HUD 推到屏幕外。
     */
    public int overlayWidth() {
        return this.width > 0 ? panelW() : 0;
    }

    public boolean containsPanel(final int mx, final int my) {
        return mx >= panelX() && mx <= this.width && my >= 0 && my <= this.height;
    }

    /** 滚轮滚动列表（调用方已确认鼠标在边栏内）。 */
    public void scrollBy(final double deltaY) {
        this.scrollTarget = Math.clamp(this.scrollTarget - (float) deltaY * 20.0f, 0.0f, maxScroll());
    }

    /** 定位到某条配合：展开它所在结构、滚动到该行、给一段高亮脉冲。 */
    public void focusOn(final UUID mateId) {
        this.focusMateId = mateId;
        expandAround(mateId);
        this.focusPulse = 1.0f;
        rebuild();
        applyFocusScroll();
    }

    /**
     * 覆盖层的点击处理。
     *
     * <p>与 {@link #mouseClicked} 的区别只是「没命中就返回 false」，不去碰 Screen 的默认实现
     * —— 我们不在 screen 栈上，没有默认实现可调。
     */
    public boolean handleClick(final int mx, final int my) {
        // 底部的鼠标锁定按钮优先判定（它在行的下方，不在列表视口里）
        final int[] cursor = cursorButtonRect();
        if (mx >= cursor[0] && mx <= cursor[0] + cursor[2]
                && my >= cursor[1] && my <= cursor[1] + cursor[3]) {
            MateClientState.toggleCursorLock();
            return true;
        }
        // ⚠ 行是按内容坐标算 y 的，滚出视口的行 y 会落到列表之外（标题/页脚区）。
        // 不夹住视口的话，点标题就会命中一个看不见的行、触发它的按钮。
        if (my < listTop() || my > listBottom()) {
            return false;
        }
        for (int i = 0; i < this.rows.size(); i++) {
            final TreeRow row = this.rows.get(i);
            final int y = rowY(i);
            final int h = rowHeight(row);
            if (my < y || my > y + h - 2) {
                continue;
            }
            if (row.structure) {
                // 结构行上的按钮（锁定）优先判定，其余区域才是「展开/收起」
                for (final RowButton hit : layoutButtons(row, i)) {
                    if (isHovered(hit, mx, my)) {
                        onStructureButton(row, hit.kind);
                        return true;
                    }
                }
                if (mx >= listX() && mx <= listRight() && row.body != null) {
                    if (!this.collapsed.remove(row.body)) {
                        this.collapsed.add(row.body);
                    }
                    this.scrollTarget = Math.clamp(this.scrollTarget, 0.0f, maxScroll());
                    return true;
                }
                continue;
            }
            for (final RowButton hit : layoutButtons(row, i)) {
                if (isHovered(hit, mx, my)) {
                    onMateButton(row, hit.kind);
                    return true;
                }
            }
            // 没命中按钮，说明点在这一行本身上：切换「展开 / 收起」。
            // 收起后数值与更改按钮都不再绘制，也不会留下看不见的点击区（layoutButtons 同步收缩）。
            if (row.mate != null) {
                if (!this.expandedMates.remove(row.mate.id())) {
                    this.expandedMates.add(row.mate.id());
                }
                this.scrollTarget = Math.clamp(this.scrollTarget, 0.0f, maxScroll());
            }
            return true;
        }
        return false;
    }

    /**
     * 处理配合行上的按钮。
     *
     * <p><b>只发命令、不改本地状态</b>：服务端是权威，改完会广播一份新的配合表过来，
     * 界面下一帧自然就更新了。本地先改会在服务端拒绝时留下错误显示。
     */
    private void onMateButton(final TreeRow row, final int kind) {
        final Mate mate = row.mate;
        if (mate == null) {
            return;
        }
        final MateType type = mate.type();
        switch (kind) {
            case BTN_DELETE -> sendCommand(MateNetworking.MateCommandPayload.remove(mate.id()));
            case BTN_MINUS -> sendCommand(MateNetworking.MateCommandPayload.setValue(mate.id(),
                    clampValue(type, mate.value() - stepOf(type))));
            case BTN_PLUS -> sendCommand(MateNetworking.MateCommandPayload.setValue(mate.id(),
                    clampValue(type, mate.value() + stepOf(type))));
            case BTN_ALIGN -> sendCommand(MateNetworking.MateCommandPayload.setFlip(mate.id(), !mate.flip()));
            case BTN_ICON -> sendCommand(MateNetworking.MateCommandPayload.changeType(mate.id(), nextType(type).id()));
            default -> {
            }
        }
    }

    /**
     * 结构行上的按钮。
     *
     * <p>「锁定」复用模组既有的锁定状态（航空学 FixedConstraint）：与左键锁定走同一条发送路径，
     * 所以边栏里锁掉之后，手杖那套的左键状态也是同步的，不会出现两份互相矛盾的锁。
     */
    private void onStructureButton(final TreeRow row, final int kind) {
        if (row.body == null || kind != BTN_LOCK) {
            return;
        }
        StaffEnhanceClientHandler.setBodiesLocked(
                !StaffEnhanceClientHandler.isBodyLocked(row.body), List.of(row.body));
    }

    private void sendCommand(final MateNetworking.MateCommandPayload payload) {
        // 没进世界就别发包（此时也不可能有配合表）
        if (this.minecraft == null || this.minecraft.level == null) {
            return;
        }
        VeilPacketManager.server().sendPacket(payload);
    }

    private static double stepOf(final MateType type) {
        return type.valueKind() == MateType.ValueKind.ANGLE ? ANGLE_STEP : DISTANCE_STEP;
    }

    private static double clampValue(final MateType type, final double value) {
        return Math.clamp(value, type.minValue(), type.maxValue());
    }

    private static MateType nextType(final MateType type) {
        final MateType[] all = MateType.values();
        return all[(type.ordinal() + 1) % all.length];
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY,
                                 final double scrollX, final double scrollY) {
        if (mouseY < listTop() || mouseY > listBottom() || mouseX < panelX() || mouseX > panelX() + panelW()) {
            return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
        }
        this.scrollTarget = Math.clamp(this.scrollTarget - (float) scrollY * 20.0f, 0.0f, maxScroll());
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ============ 行/按钮数据结构 ============

    /** 一行：要么是结构（顶层），要么是挂在某个结构下的配合（缩进一级）。 */
    private static final class TreeRow {
        final boolean structure;
        @Nullable
        final UUID body;
        @Nullable
        final Mate mate;
        /** 配合在当前结构子列表里的 1-based 序号（生成「配合N」用）。 */
        final int index;
        /** 动画状态 key：同一条配合挂在两个结构下时必须是两行，所以 key 里带上 body。 */
        final String key;
        @Nullable
        final String title;

        private TreeRow(final boolean structure, @Nullable final UUID body, @Nullable final Mate mate,
                        final int index, final String key, @Nullable final String title) {
            this.structure = structure;
            this.body = body;
            this.mate = mate;
            this.index = index;
            this.key = key;
            this.title = title;
        }

        static TreeRow structure(final UUID body, @Nullable final String title) {
            return new TreeRow(true, body, null, 0, "s:" + body, title);
        }

        static TreeRow mate(final UUID body, final Mate mate, final int index) {
            return new TreeRow(false, body, mate, index, "m:" + body + ":" + mate.id(), null);
        }
    }

    /** 配合行上的一个小按钮（含图标按钮）。 */
    private static final class RowButton {
        final int kind;
        final int x;
        final int y;
        final int w;
        final int h;

        RowButton(final int kind, final int x, final int y, final int w, final int h) {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }
}
