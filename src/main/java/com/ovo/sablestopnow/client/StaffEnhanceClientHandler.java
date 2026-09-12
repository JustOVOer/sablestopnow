package com.ovo.sablestopnow.client;

import com.ovo.sablestopnow.SablestopNowConfig;
import com.ovo.sablestopnow.network.StaffEnhanceNetworking;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension;
import dev.ryanhcode.sable.mixinterface.clip_overwrite.LevelPoseProviderExtension;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffItem;
import dev.simulated_team.simulated.index.SimKeys;
import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;
import org.joml.AxisAngle4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 物理手杖增强 —— 客户端模式状态机（阶段 A/B/C/D）。
 *
 * <p>功能（全部仅在本地玩家手持 PhysicsStaffItem 且 config.staff_enhance.enable_staff_enhance=true 时生效）：
 * <ul>
 *   <li><b>Ctrl</b>：进入/退出多选模式（多选期间原手杖拖拽/锁定被接管禁用）。</li>
 *   <li>多选模式<b>右键</b>：点选/取消选中视线（穿透穿透层）处的物理体（描边+选中图标）。</li>
 *   <li>多选模式<b>Alt+滚轮</b>：调节视线穿透层数。</li>
 *   <li>多选模式<b>Z</b>：两次标记格点形成轴对齐立方体选区，框内物理体全部加入选中。</li>
 *   <li>退出多选后（已选非空=armed）：对组内任一物理体<b>右键</b>开始整组拖拽 —— 服务端按每成员马达
 *       驱动到“组质心 + 组旋转”的精确目标位姿（保持组内相对位姿）；<b>滚轮</b>调组距离、<b>TAB+鼠标</b>旋转。</li>
 *   <li>非多选模式<b>V</b>：切换视线所指物理体的“无碰撞”标记（服务端存档+图标；Sable 暂不支持真实穿模）。</li>
 * </ul>
 * 键盘输入走 {@link StaffKeyMappings}（Minecraft KeyMapping，可在「选项→按键控制」里改），
 * 每 client tick 在 {@link #tick()} 里消费边沿；鼠标输入由 MouseHandler Mixin 调
 * {@link #handleMouse} / {@link #handleScroll} / {@link #handleLookMove}，返回 true 表示已消费（Mixin 里 cancel）。
 */
public final class StaffEnhanceClientHandler {

    public static final int MOUSE_RIGHT = GLFW.GLFW_MOUSE_BUTTON_RIGHT;
    public static final int MOUSE_LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;
    public static final int MOUSE_MIDDLE = GLFW.GLFW_MOUSE_BUTTON_MIDDLE;

    private static final double RAY_RANGE = 128.0;

    // ---- 模式状态 ----
    private static boolean multiSelect;
    private static final Set<UUID> selected = new LinkedHashSet<>();
    private static int penetration;

    // ---- 区域选择（Z）状态 ----
    /** 0=未激活；1=正在选第一个角点；2=正在选第二个角点。 */
    private static int regionStep;
    /** 选择点到玩家的距离（滚轮调节）。 */
    private static double regionDistance = 8.0;
    @Nullable private static BlockPos regionFirst;
    @Nullable private static BlockPos regionSecond;
    /** 当前视线+距离指示的格点（未确认的候选角点）。 */
    @Nullable private static BlockPos regionCursor;
    /** 选第二点时实时预览“将会被选中”的物理结构。 */
    private static final Set<UUID> regionCandidates = new LinkedHashSet<>();

    private static final double REGION_MIN_DISTANCE = 1.0;
    private static final double REGION_MAX_DISTANCE = 128.0;

    /** 当前维度的无碰撞标记集（来自服务端同步；乐观更新用于即时提示）。 */
    private static ResourceLocation noCollisionDimension;
    private static final Set<UUID> noCollision = new LinkedHashSet<>();

    /** 当前维度已锁定物理体集（S2C 同步，用于左键锁定/解锁方向判断）。 */
    private static ResourceLocation lockedDimension;
    private static final Set<UUID> staffLocks = new LinkedHashSet<>();

    /** 整组拖拽会话（非空=正在拖拽整组）。 */
    @Nullable private static ClientGroupDrag groupDrag;

    // ---- 多人选择同步（功能2） ----
    /** 服务端同步过来的“其他玩家”的选中集合（玩家 -> 物理结构）。 */
    private static final Map<UUID, Set<UUID>> otherSelections = new LinkedHashMap<>();
    /** 玩家 -> 描边颜色索引（服务端分配）。 */
    private static final Map<UUID, Integer> selectionColors = new java.util.HashMap<>();
    /** 我自己的颜色索引。 */
    private static int myColorIndex;
    /** 本地队列里是否有需要向服务端释放的“占用”。 */
    private static boolean claimsPending;

    /** 视角锁定（功能8）当前锁定的物理结构。 */
    @Nullable private static UUID viewLockTarget;
    /** 视角锁定目标的显示名（可能为 null）。 */
    @Nullable private static String viewLockName;

    // ---- 快照（功能9，按玩家独立） ----
    /** 我当前存了快照的物理结构（服务端同步）。 */
    private static final Set<UUID> snapshotIds = new LinkedHashSet<>();
    private static ResourceLocation snapshotDimension;

    // ---- 缩放（X + 滚轮） ----
    /** 是否处于「按住 X 缩放」会话中。 */
    private static boolean scalingSession;
    /** 当前缩放倍率（显示用）。 */
    private static float scaleFactor = 1.0f;

    // ---- 彩蛋：Superliminal ----
    /** 服务端同步的彩蛋开关。 */
    private static boolean superliminalOn;
    /** 抓取瞬间「眼睛 → 落点」的距离，用于保持屏幕视觉大小不变。 */
    private static double superliminalBaseDistance = 1.0;

    // ---- 缩放同步（Sable 的位姿同步不含 scale，所以由模组自己同步） ----
    /** 物理结构 -> 缩放倍率（服务端权威）。 */
    private static final Map<UUID, Float> syncedScales = new java.util.HashMap<>();
    private static ResourceLocation scalesDimension;

    // ---- 所有权（功能3） ----
    /** 物理结构 -> 所有者 UUID（服务端同步）。 */
    private static final Map<UUID, UUID> ownershipOwners = new java.util.HashMap<>();
    /** 物理结构 -> 所有者显示名。 */
    private static final Map<UUID, String> ownershipNames = new java.util.HashMap<>();

    /** 多选模式下“当前右键将选中”的物理体（每 tick 刷新，供渲染悬停高亮）。 */
    @Nullable private static UUID hoverBody;
    /** 上述物理体的显示名（可能为 null；供 HUD 提示）。 */
    @Nullable private static String hoverName;
    private static int tickCounter;

    private StaffEnhanceClientHandler() {
    }

    // ============ 外部只读 ============
    public static boolean isMultiSelect() {
        return multiSelect;
    }

    public static Set<UUID> getSelected() {
        return selected;
    }

    public static Set<UUID> getNoCollision() {
        return noCollision;
    }

    // ---- 多人选择（功能2）只读 ----

    /** 我自己的描边颜色索引（服务端分配）。 */
    public static int getMyColorIndex() {
        return myColorIndex;
    }

    /** 某玩家的描边颜色索引。 */
    public static int colorIndexOf(final UUID player) {
        return selectionColors.getOrDefault(player, 0);
    }

    /** 其他玩家当前选中的物理结构（玩家 -> 集合）。 */
    public static Map<UUID, Set<UUID>> getOtherSelections() {
        return otherSelections;
    }

    /** 该物理结构的所有者显示名（无主返回 null）。 */
    @Nullable
    public static String ownerNameOf(final UUID subLevel) {
        return ownershipNames.get(subLevel);
    }

    /** 该物理结构是否属于我。 */
    public static boolean isOwnedByMe(final UUID subLevel) {
        final LocalPlayer player = localPlayer();
        return player != null && player.getUUID().equals(ownershipOwners.get(subLevel));
    }

    public static int getPenetration() {
        return penetration;
    }

    // ---- 区域选择只读状态（渲染/HUD 用） ----
    public static int getRegionStep() {
        return regionStep;
    }

    public static double getRegionDistance() {
        return regionDistance;
    }

    @Nullable
    public static BlockPos getRegionFirst() {
        return regionFirst;
    }

    @Nullable
    public static BlockPos getRegionSecond() {
        return regionSecond;
    }

    /** 当前视线+距离指示的候选角点（未确认）。 */
    @Nullable
    public static BlockPos getRegionCursor() {
        return regionCursor;
    }

    /** 选第二点时“将会被选中”的物理结构（实时预览）。 */
    public static Set<UUID> getRegionCandidates() {
        return regionCandidates;
    }

    /** 当前悬停（将被右键选中）的物理体 id，多选模式外为 null。 */
    @Nullable
    public static UUID getHoverBody() {
        return hoverBody;
    }

    /** 当前悬停物理体的显示名（没有名字时为 null）。 */
    @Nullable
    public static String getHoverName() {
        return hoverName;
    }

    // ============ 条件判断 ============
    public static boolean isEnabled() {
        return SablestopNowConfig.isStaffEnhanceEnabled();
    }

    @Nullable
    private static LocalPlayer localPlayer() {
        return Minecraft.getInstance().player;
    }

    public static boolean isActive() {
        if (!isEnabled()) {
            return false;
        }
        final LocalPlayer player = localPlayer();
        return player != null && Minecraft.getInstance().screen == null && PhysicsStaffItem.isHolding(player);
    }

    /** 已退出多选且仍有选中（=整组拖拽待命）。 */
    private static boolean isArmed() {
        return !multiSelect && groupDrag == null && !selected.isEmpty();
    }

    /** 是否正在整组控制（拖拽）中。 */
    public static boolean isGroupDragging() {
        return groupDrag != null;
    }

    // ============ 键盘（KeyMapping，边沿在 tick 里消费） ============
    /** 上一 tick 各键的按下状态，用于在 GLFW 按键重复（REPEAT）时只触发一次。 */
    private static final java.util.Map<KeyMapping, Boolean> KEY_WAS_DOWN = new java.util.IdentityHashMap<>();

    /**
     * 每 client tick 轮询一次全部键位。仅“持杖 + 增强开启 + 无 GUI”时执行动作，
     * 其余情况只排空点击队列（避免重新持杖后触发陈旧点击）。
     */
    private static void pollKeys() {
        final boolean active = isActive();
        // 打开模组设置界面（默认 Ctrl+O）：不需要持杖，任何时候都可用
        if (justPressed(StaffKeyMappings.OPEN_CONFIG)) {
            openConfigScreen();
        }
        if (justPressed(StaffKeyMappings.MULTI_SELECT) && active) {
            toggleMultiSelect();
        }
        if (justPressed(StaffKeyMappings.REGION_SELECT) && active) {
            onRegionSelectKey();
        }
        if (justPressed(StaffKeyMappings.COLLISION_TOGGLE) && active && !multiSelect) {
            onCollisionToggleKey();
        }
        if (justPressed(StaffKeyMappings.OWNERSHIP) && active) {
            onOwnershipKey();
        }
        if (justPressed(StaffKeyMappings.SNAPSHOT) && active) {
            onSnapshotKey();
        }
        if (justPressed(StaffKeyMappings.RESTORE) && active) {
            onRestoreKey();
        }
        // 缩放：按住 X 开始（服务端会先把选中结构统一回 1.0 倍），松开结束
        final boolean scalePressed = justPressed(StaffKeyMappings.SCALE);
        if (active && scalePressed) {
            startScaling();
        }
        if (scalingSession && (!active || !StaffKeyMappings.SCALE.isDown())) {
            stopScaling();
        }
        suppressVanillaKeyClash();
    }

    /** Ctrl+O：打开自定义模组设置界面。 */
    private static void openConfigScreen() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) {
            return;
        }
        mc.setScreen(new com.ovo.sablestopnow.client.gui.ModConfigScreen(null));
    }

    /**
     * 本 tick 是否“刚按下”该键。
     *
     * <p>不能用裸 {@code consumeClick()}：原版 {@code KeyboardHandler} 在 GLFW 的 REPEAT 事件里
     * 也会调用 {@code KeyMapping.click()}（见 1.21.1 字节码），按住不放会反复触发。
     * 这里以“上一 tick 未按下 → 本 tick 按下”作为上升沿；若按下与抬起发生在同一 tick 内
     * （{@code isDown()} 已为 false）则仍按一次点击处理。
     */
    private static boolean justPressed(final KeyMapping mapping) {
        boolean clicked = false;
        while (mapping.consumeClick()) {
            clicked = true;
        }
        final boolean down = mapping.isDown();
        final boolean wasDown = Boolean.TRUE.equals(KEY_WAS_DOWN.put(mapping, down));
        return clicked && (down ? !wasDown : true);
    }

    /**
     * 多选键默认是 左Ctrl，而原版「疾跑」默认也是 左Ctrl（{@code KeyMapping.set} 会把同一个键上的所有映射一起按下）。
     * 旧实现靠 KeyboardHandler HEAD cancel 顺带屏蔽了疾跑；改走 KeyMapping 后该副作用会回来，因此这里显式压掉：
     * 只有当多选键与疾跑的绑定是同一个输入、且多选键按住时，每 tick 把疾跑状态按回 false。
     */
    private static void suppressVanillaKeyClash() {
        final Minecraft mc = Minecraft.getInstance();
        if (!StaffKeyMappings.MULTI_SELECT.isDown() || mc.options == null) {
            return;
        }
        final KeyMapping sprint = mc.options.keySprint;
        if (sprint != null && sprint.getKey().equals(StaffKeyMappings.MULTI_SELECT.getKey())) {
            sprint.setDown(false);
        }
    }

    public static boolean handleMouse(final int button, final int action, final int modifiers) {
        if (!isActive()) {
            return false;
        }
        if (action != GLFW.GLFW_PRESS) {
            return false;
        }
        // 区域选择中：右键=确定当前选择点；左/中键吞掉
        if (regionStep != 0) {
            if (button == MOUSE_RIGHT) {
                confirmRegionPoint();
            }
            return true;
        }
        if (multiSelect) {
            // 多选模式：右键=加入队列；Shift+右键=移出；左/中键吞掉（原手杖交互禁用）
            if (button == MOUSE_RIGHT) {
                if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
                    onShiftRightRemove();
                } else {
                    onRightClickAdd();
                }
            }
            return true;
        }
        // 非多选
        if (groupDrag != null) {
            // 整组拖拽期间：右键结束（保留队列）；左键=切换全部选中体锁定；中键禁用
            if (button == MOUSE_RIGHT) {
                stopGroupDrag();
            } else if (button == MOUSE_LEFT) {
                toggleLocksAll();
            }
            return true;
        }
        if (button == MOUSE_RIGHT) {
            if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) {
                // 退出多选后的待命态：Shift+右键清空选中队列（弃用右键清空）
                if (isArmed()) {
                    clearQueue();
                    return true;
                }
                return false;
            }
            // 彩蛋 Superliminal：非多选时右键直接抓起准星指向的结构，
            // 走本模组的「缩放会话 + 绝对定位」驱动（航空学自己的拖拽马达会和缩放会话打架）。
            if (superliminalOn && groupDrag == null) {
                final Pick pick = pickAtDepth(penetration);
                if (pick != null) {
                    selected.add(pick.body.getUniqueId());
                    startGroupDrag(pick);
                    return true;
                }
            }
            if (isArmed()) {
                // 沿视线找第一个属于选中队列的物理体来发起整组拖拽（与穿透层数无关）
                final Pick pick = pickQueueLeader();
                if (pick != null) {
                    startGroupDrag(pick);
                    return true;
                }
            }
            return false;
        }
        if (button == MOUSE_LEFT && isArmed()) {
            // 待命态：左键=切换全部选中体的锁定
            toggleLocksAll();
            return true;
        }
        return false;
    }

    public static boolean handleScroll(final double deltaY) {
        if (!isActive()) {
            return false;
        }
        // X + 滚轮：缩放选中队列（倍率是绝对值，服务端按基线重算，不会累积漂移）
        if (scalingSession && StaffKeyMappings.SCALE.isDown()) {
            final float step = 1.0f + (float) SablestopNowConfig.scaleSensitivity();
            scaleFactor = Math.clamp(scaleFactor * (deltaY > 0 ? step : 1.0f / step),
                    (float) SablestopNowConfig.scaleMin(), (float) SablestopNowConfig.scaleMax());
            VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.UpdateScalePayload(scaleFactor));
            prompt("sablestopnow.staff.scale_value", String.format("%.2f", scaleFactor));
            return true;
        }
        // 穿透层数：任何状态（多选 / 待命 / 整组控制 / 区域选择）下按修饰键（默认 Alt）+滚轮都可调
        if (StaffKeyMappings.isPenetrationModifierDown()) {
            final int old = penetration;
            penetration = Math.max(0, Math.min(16, penetration + (deltaY > 0 ? 1 : -1)));
            if (penetration != old) {
                prompt("sablestopnow.staff.penetration", penetration);
            }
            return true;
        }
        // 区域选择中：滚轮调“选择点到玩家”的距离。
        // ⚠ 必须放在 multiSelect 早退之前：多选模式下也要吞掉滚轮，否则会切快捷栏→手上没手杖→状态被清空。
        if (regionStep != 0) {
            final double mult = Minecraft.getInstance().options.keySprint.isDown() ? 4.0 : 1.0;
            final double old = regionDistance;
            regionDistance = Math.clamp(regionDistance + deltaY * SablestopNowConfig.scrollSensitivity() * 4.0 * mult,
                    REGION_MIN_DISTANCE, REGION_MAX_DISTANCE);
            if (Math.abs(regionDistance - old) > 1.0e-4) {
                prompt("sablestopnow.staff.region_distance", String.format("%.1f", regionDistance));
            }
            return true;
        }
        if (multiSelect) {
            return false;
        }
        if (groupDrag != null) {
            // 沿“眼睛→质心”连线缩放距离（质心随该方向靠近/远离玩家）
            final LocalPlayer player = localPlayer();
            if (player != null) {
                final Vec3 center = dragCenter(player, groupDrag);
                final Vec3 fromEye = center.subtract(player.getEyePosition(1.0f));
                final double len = fromEye.length();
                final double mult = Minecraft.getInstance().options.keySprint.isDown() ? 4.0 : 1.0;
                final double newLen = Math.clamp(len + deltaY * SablestopNowConfig.scrollSensitivity() * mult, 2.0, RAY_RANGE);
                if (len > 1.0e-4 && Math.abs(newLen - len) > 1.0e-4) {
                    final Vec3 dir = fromEye.scale(1.0 / len);
                    final Vec3 newCenter = player.getEyePosition(1.0f).add(dir.scale(newLen));
                    setDragCenterFromWorld(player, groupDrag, newCenter);
                    groupDrag.distance = newLen;
                }
                prompt("sablestopnow.staff.group_distance", String.format("%.1f", groupDrag.distance));
            }
            return true;
        }
        return false;
    }

    /**
     * 鼠标移动（MouseHandler.turnPlayer 预转局部量，与航空学同量级）。整组拖拽 + 按住 TAB 时旋转整组并吞掉视角转动。
     */
    public static boolean handleLookMove(final double yawRaw, final double pitchRaw) {
        if (!isActive()) {
            return false;
        }
        // 视角锁定期间冻结鼠标转视角（否则会和锁定打架）
        if (isViewLockActive()) {
            return true;
        }
        if (groupDrag == null || !SimKeys.ROTATE_MODE.isPressed()) {
            return false;
        }
        final LocalPlayer player = localPlayer();
        if (player == null) {
            return false;
        }
        // 与航空学 PhysicsStaffClientHandler.onMouseMove 完全相同的旋转公式与输入量级
        final Vec3 axis = player.calculateViewVector(0.0f, player.getYRot() - 90.0f);
        final Quaterniond orientation = groupDrag.orientation;

        final double yawChange = Math.toRadians(yawRaw) * SablestopNowConfig.rotateSensitivity();
        orientation.rotateLocalY(yawChange);
        orientation.premul(new Quaterniond(new AxisAngle4d(Math.toRadians(-pitchRaw) * SablestopNowConfig.rotateSensitivity(), axis.x, axis.y, axis.z)));
        return true;
    }

    // ============ 每 tick ============
    public static void tick() {
        final Minecraft mc = Minecraft.getInstance();
        final LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            pollKeys();
            resetAll();
            return;
        }
        // 缩放同步与是否持杖无关（Sable 的位姿同步不含 scale），必须每 tick 都写回
        applySyncedScales(player);
        if (!PhysicsStaffItem.isHolding(player)) {
            pollKeys();
            resetAll();
            return;
        }
        pollKeys();
        if (groupDrag != null) {
            sendGroupDragTick(player);
        }
        // 维度变化时清空无碰撞/锁定缓存
        final ResourceLocation dim = player.level().dimension().location();
        if (!dim.equals(noCollisionDimension)) {
            noCollisionDimension = dim;
            noCollision.clear();
        }
        if (!dim.equals(lockedDimension)) {
            lockedDimension = dim;
            staffLocks.clear();
        }
        // 悬停高亮：隔帧刷新，避免每帧做 raycast
        tickCounter++;
        if (tickCounter % 2 == 0) {
            // 「手杖可能选中的物理结构」在任何状态下都刷新（功能4/6：穿透层数与悬停描边随时可用）
            if (isEnabled()) {
                final Pick pick = pickAtDepth(penetration);
                hoverBody = pick != null ? pick.body.getUniqueId() : null;
                hoverName = pick != null ? pick.body.getName() : null;
            } else {
                hoverBody = null;
                hoverName = null;
            }
        }
        // 区域选择指示点：每 tick 跟随视线/距离（很便宜）
        updateRegionCursor(player);
        // 功能8：非多选、非整组控制时，长按 C 把视线锁定到“与视线夹角最小”的物理结构
        if (isViewLockActive()) {
            lockViewToNearestAngle(player);
        } else {
            viewLockTarget = null;
            viewLockName = null;
        }
    }

    // ============ 功能8：视角锁定（长按 C） ============
    /** 当前是否处于视角锁定状态（整组控制中 C 仍是“归中”，多选模式下 C 不生效）。 */
    public static boolean isViewLockActive() {
        return !multiSelect && groupDrag == null && isActive() && StaffKeyMappings.CENTER_PULL.isDown();
    }

    /** 正在被锁定视角的物理结构 id（未锁定时 null；供 HUD）。 */
    @Nullable
    public static UUID getViewLockTarget() {
        return viewLockTarget;
    }

    /** 正在被锁定视角的物理结构显示名（可能为 null）。 */
    @Nullable
    public static String getViewLockName() {
        return viewLockName;
    }

    /** 我当前存了快照的物理结构集合（供渲染图标）。 */
    public static Set<UUID> getSnapshotIds() {
        return snapshotIds;
    }

    /** 是否正在按住 X 缩放选中队列。 */
    public static boolean isScaling() {
        return scalingSession;
    }

    /** 当前缩放倍率（1.0 = 未缩放）。 */
    public static float getScaleFactor() {
        return scaleFactor;
    }

    /** 彩蛋 Superliminal 是否开启（S2C 同步）。 */
    public static boolean isSuperliminalOn() {
        return superliminalOn;
    }

    /** 该物理结构当前的缩放倍率（1.0 = 未缩放）。 */
    public static float scaleOf(final UUID subLevel) {
        return syncedScales.getOrDefault(subLevel, 1.0f);
    }

    /** 当前是否有任何「缩放 ≠ 1」的物理结构（供渲染/HUD 判断是否需要绘制）。 */
    public static boolean hasAnyScaled() {
        for (final Float value : syncedScales.values()) {
            if (value != null && Math.abs(value - 1.0f) > 1.0e-3f) {
                return true;
            }
        }
        return false;
    }

    /** 已缩放结构的数量（HUD 用）。 */
    public static int scaledCount() {
        int count = 0;
        for (final Float value : syncedScales.values()) {
            if (value != null && Math.abs(value - 1.0f) > 1.0e-3f) {
                count++;
            }
        }
        return count;
    }

    /** 已缩放结构的倍率（降序，最多 n 个；HUD 用）。 */
    public static List<Float> topScaleFactors(final int n) {
        final List<Float> out = new ArrayList<>();
        for (final Float value : syncedScales.values()) {
            if (value != null && Math.abs(value - 1.0f) > 1.0e-3f) {
                out.add(value);
            }
        }
        out.sort(java.util.Comparator.reverseOrder());
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    /** S2C：服务端权威的缩放表。 */
    public static void setScales(final ResourceLocation dimension, final List<StaffEnhanceNetworking.ScaleEntry> entries) {
        final LocalPlayer player = localPlayer();
        if (player == null || player.level() == null) {
            return;
        }
        scalesDimension = dimension;
        if (!player.level().dimension().location().equals(dimension)) {
            return;
        }
        syncedScales.clear();
        for (final StaffEnhanceNetworking.ScaleEntry entry : entries) {
            syncedScales.put(entry.subLevel(), entry.scale());
        }
    }

    /**
     * 每 tick 把同步来的缩放写进客户端物理体的位姿。
     * ⚠ Sable 的位姿同步包（SableBufferUtils.write(Pose3d)）只写 position/orientation/rotationPoint，
     * <b>不写 scale</b>，所以不这样做客户端永远看不见缩放。
     */
    private static void applySyncedScales(final LocalPlayer player) {
        if (syncedScales.isEmpty()) {
            return;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(player.level());
        if (container == null) {
            return;
        }
        for (final Map.Entry<UUID, Float> entry : syncedScales.entrySet()) {
            final SubLevel sub = container.getSubLevel(entry.getKey());
            if (sub == null || sub.isRemoved()) {
                continue;
            }
            final float scale = entry.getValue();
            if (Math.abs(sub.logicalPose().scale().x() - scale) > 1.0e-3) {
                sub.logicalPose().scale().set(scale, scale, scale);
            }
        }
    }

    /** S2C：彩蛋开关状态。 */
    public static void setSuperliminal(final boolean on) {
        superliminalOn = on;
        prompt(on ? "sablestopnow.command.superliminal.on" : "sablestopnow.command.superliminal.off");
    }

    /** S2C：正在被拖拽 / 正在缩放（对所有玩家）的物理结构。玩家真实碰撞在客户端算，所以这份状态必须同步过来。 */
    public static void setActiveGhosts(final List<UUID> subLevels, final List<UUID> draggers, final List<UUID> globalIds) {
        final java.util.Map<UUID, UUID> map = new java.util.HashMap<>();
        final int size = Math.min(subLevels.size(), draggers.size());
        for (int i = 0; i < size; i++) {
            map.put(subLevels.get(i), draggers.get(i));
        }
        com.ovo.sablestopnow.PhysicsGhosts.set(map);
        com.ovo.sablestopnow.PhysicsGhosts.setGlobal(globalIds);
    }

    /**
     * 每 tick 重新选取「与当前视线夹角最小」的已加载物理结构，并把玩家朝向直接设为指向它的中心。
     * 只改当前朝向、不改上一 tick 朝向，让渲染层的插值把转动做平滑。
     */
    private static void lockViewToNearestAngle(final LocalPlayer player) {
        final SubLevelContainer container = SubLevelContainer.getContainer(player.level());
        if (container == null) {
            viewLockTarget = null;
            return;
        }
        final Vec3 eye = player.getEyePosition(1.0f);
        final Vec3 look = player.getLookAngle();

        SubLevel best = null;
        Pose3dc bestPose = null;
        double bestDot = -2.0;
        for (final SubLevel sub : container.getAllSubLevels()) {
            if (sub == null || sub.isRemoved()) {
                continue;
            }
            final Pose3dc pose = sub instanceof final ClientSubLevel client ? client.renderPose() : sub.logicalPose();
            final double dx = pose.position().x() - eye.x;
            final double dy = pose.position().y() - eye.y;
            final double dz = pose.position().z() - eye.z;
            final double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0e-4) {
                continue;
            }
            final double dot = (dx * look.x + dy * look.y + dz * look.z) / len;
            if (dot > bestDot) {
                bestDot = dot;
                best = sub;
                bestPose = pose;
            }
        }
        if (best == null || bestPose == null) {
            viewLockTarget = null;
            viewLockName = null;
            return;
        }
        viewLockTarget = best.getUniqueId();
        viewLockName = best.getName();

        final double dx = bestPose.position().x() - eye.x;
        final double dy = bestPose.position().y() - eye.y;
        final double dz = bestPose.position().z() - eye.z;
        final double horizontal = Math.sqrt(dx * dx + dz * dz);
        final float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        final float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        // 只设置“当前朝向”，不覆盖 yRotO/xRotO —— 渲染插值会把 20Hz 的更新补成平滑转动
        player.setYRot(yaw);
        player.setXRot(Math.clamp(pitch, -90.0f, 90.0f));
    }

    // ============ 区域选择（Z）：距离×视线定角点，右键确定 ============
    /** 当前指示点 = 眼睛 + 视线 × 距离，取所在方块格。 */
    private static void updateRegionCursor(final LocalPlayer player) {
        if (regionStep == 0) {
            regionCursor = null;
            regionCandidates.clear();
            return;
        }
        final Vec3 eye = player.getEyePosition(1.0f);
        final Vec3 look = player.getLookAngle();
        final Vec3 point = eye.add(look.scale(regionDistance));
        regionCursor = BlockPos.containing(point);
        if (regionStep == 2 && regionFirst != null) {
            regionCandidates.clear();
            collectRegionCandidates(player, regionFirst, regionCursor, regionCandidates);
        } else {
            regionCandidates.clear();
        }
    }

    /** Z 键：进入区域选择；已在区域选择中则取消。 */
    private static void onRegionSelectKey() {
        if (regionStep == 0) {
            regionStep = 1;
            regionFirst = null;
            regionSecond = null;
            prompt("sablestopnow.staff.region_enter", String.format("%.1f", regionDistance));
            return;
        }
        cancelRegionSelect(true);
    }

    private static void cancelRegionSelect(final boolean announce) {
        regionStep = 0;
        regionFirst = null;
        regionSecond = null;
        regionCursor = null;
        regionCandidates.clear();
        if (announce) {
            prompt("sablestopnow.staff.region_cancel");
        }
    }

    /** 右键确定当前选择点：第一次定第一角点，第二次定第二角点并立刻结算。 */
    private static void confirmRegionPoint() {
        final LocalPlayer player = localPlayer();
        if (player == null) {
            return;
        }
        if (regionCursor == null) {
            prompt("sablestopnow.staff.region_no_point");
            return;
        }
        if (regionStep == 1) {
            regionFirst = regionCursor;
            regionStep = 2;
            prompt("sablestopnow.staff.region_first", regionFirst.getX(), regionFirst.getY(), regionFirst.getZ());
            return;
        }

        regionSecond = regionCursor;
        final Set<UUID> candidates = new LinkedHashSet<>();
        collectRegionCandidates(player, regionFirst, regionSecond, candidates);
        if (candidates.isEmpty()) {
            prompt("sablestopnow.staff.region_empty");
        } else {
            selected.addAll(candidates);
            claimSelection(candidates);
            prompt("sablestopnow.staff.region_done", candidates.size(), selected.size());
        }
        cancelRegionSelect(false);
    }

    /**
     * 收集“长方体与物理体包围盒相交”的物理体（与旧框选同一判定；相交即算，不要求完全包含）。
     * 体积超过 100 万格直接返回空集。
     */
    private static void collectRegionCandidates(final LocalPlayer player, @Nullable final BlockPos a,
                                                @Nullable final BlockPos b, final Set<UUID> out) {
        if (a == null || b == null || player.level() == null) {
            return;
        }
        final int minX = Math.min(a.getX(), b.getX());
        final int minY = Math.min(a.getY(), b.getY());
        final int minZ = Math.min(a.getZ(), b.getZ());
        final int maxX = Math.max(a.getX(), b.getX());
        final int maxY = Math.max(a.getY(), b.getY());
        final int maxZ = Math.max(a.getZ(), b.getZ());

        final long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > 1_000_000) {
            return;
        }

        final double boxMinX = minX - 0.5;
        final double boxMinY = minY - 0.5;
        final double boxMinZ = minZ - 0.5;
        final double boxMaxX = maxX + 1.5;
        final double boxMaxY = maxY + 1.5;
        final double boxMaxZ = maxZ + 1.5;
        final SubLevelContainer container = SubLevelContainer.getContainer(player.level());
        if (container == null) {
            return;
        }
        for (final SubLevel sub : container.getAllSubLevels()) {
            if (sub == null || sub.isRemoved()) {
                continue;
            }
            final BoundingBox3dc bounds = sub.boundingBox();
            if (bounds == null) {
                continue;
            }
            final boolean overlaps = bounds.maxX() >= boxMinX && bounds.minX() <= boxMaxX
                    && bounds.maxY() >= boxMinY && bounds.minY() <= boxMaxY
                    && bounds.maxZ() >= boxMinZ && bounds.minZ() <= boxMaxZ;
            if (overlaps) {
                out.add(sub.getUniqueId());
            }
        }
    }

    private static void resetAll() {
        stopScaling();
        releaseAllClaims();
        multiSelect = false;
        selected.clear();
        penetration = 0;
        cancelRegionSelect(false);
        hoverBody = null;
        hoverName = null;
        viewLockTarget = null;
        if (groupDrag != null) {
            groupDrag = null; // 本地直接丢弃；服务端由超时/停服 tick 清理
        }
    }

    // ============ 多选模式动作 ============
    private static void toggleMultiSelect() {
        multiSelect = !multiSelect;
        if (multiSelect) {
            // 进入多选：把（上次退出后保留的）队列重新登记为我的占用，被他人占用的会被服务端剔除
            claimSelection(selected);
            prompt("sablestopnow.staff.multi_enter");
        } else {
            cancelRegionSelect(false);
            // 退出多选：释放全部占用，但本地队列保留（仍可整组拖拽）
            releaseAllClaims();
            if (selected.isEmpty()) {
                prompt("sablestopnow.staff.multi_exit_empty");
            } else {
                prompt("sablestopnow.staff.multi_exit_armed", selected.size());
            }
        }
    }

    private static void onRightClickAdd() {
        final SubLevel target = pickTarget();
        if (target == null) {
            prompt("sablestopnow.staff.select_miss", penetration);
            return;
        }
        final UUID id = target.getUniqueId();
        if (!selected.add(id)) {
            prompt("sablestopnow.staff.already_selected", id.toString(), selected.size());
            return;
        }
        claimSelection(List.of(id));
        prompt("sablestopnow.staff.select_add", target.getName() != null ? target.getName() : id.toString(), selected.size());
    }

    /** 多选模式 Shift+右键：把指向的物理体移出队列。 */
    private static void onShiftRightRemove() {
        final SubLevel target = pickTarget();
        if (target == null) {
            prompt("sablestopnow.staff.select_miss", penetration);
            return;
        }
        final UUID id = target.getUniqueId();
        if (selected.remove(id)) {
            releaseSelection(List.of(id));
            prompt("sablestopnow.staff.select_remove", id.toString(), selected.size());
        } else {
            prompt("sablestopnow.staff.not_selected", id.toString());
        }
    }

    /** 退出多选后的待命态：Shift+右键清空选中队列。 */
    private static void clearQueue() {
        final int size = selected.size();
        selected.clear();
        releaseAllClaims();
        prompt(size > 0 ? "sablestopnow.staff.queue_cleared" : "sablestopnow.staff.multi_exit_empty", size);
    }

    /**
     * 左键：对队列内全部物理体智能切换锁定 —— 全部已锁定 → 解锁；否则（含部分锁定）→ 先全部锁定。
     * 方向按服务端 S2C 同步的锁定状态判断。
     */
    private static void toggleLocksAll() {
        if (selected.isEmpty()) {
            return;
        }
        boolean allLocked = true;
        for (final UUID id : selected) {
            if (!staffLocks.contains(id)) {
                allLocked = false;
                break;
            }
        }
        final boolean lock = !allLocked;
        sendSetLocks(lock, selected);
        // 乐观更新本地（服务端随后会 S2C 回推校准）
        if (lock) {
            staffLocks.addAll(selected);
        } else {
            staffLocks.removeAll(selected);
        }
        prompt(lock ? "sablestopnow.staff.group_locked" : "sablestopnow.staff.group_unlocked", selected.size());
    }

    // ============ V：无碰撞切换（对整组选中队列应用/取消，语义与左键锁定一致） ============
    private static void onCollisionToggleKey() {
        if (selected.isEmpty()) {
            prompt("sablestopnow.staff.need_queue");
            return;
        }
        boolean allMarked = true;
        for (final UUID id : selected) {
            if (!noCollision.contains(id)) {
                allMarked = false;
                break;
            }
        }
        final boolean mark = !allMarked; // 全部已标记→取消；否则（含部分）→先全标记
        // 乐观更新本地（随后服务端 S2C 同步覆盖）
        if (mark) {
            noCollision.addAll(selected);
        } else {
            noCollision.removeAll(selected);
        }
        prompt(mark
                        ? (SablestopNowConfig.isGhostReal() ? "sablestopnow.staff.group_ghost_on_real" : "sablestopnow.staff.group_ghost_on")
                        : "sablestopnow.staff.group_ghost_off",
                selected.size());
        VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.SetNoCollisionPayload(mark, new ArrayList<>(selected)));
    }

    public static void setNoCollision(final ResourceLocation dimension, final Collection<UUID> ids) {
        final LocalPlayer player = localPlayer();
        if (player == null || player.level() == null || !player.level().dimension().location().equals(dimension)) {
            return;
        }
        noCollision.clear();
        noCollision.addAll(ids);
    }

    /** S2C：服务端同步的当前维度锁定物理体集。 */
    public static void setStaffLocks(final ResourceLocation dimension, final Collection<UUID> ids) {
        final LocalPlayer player = localPlayer();
        if (player == null || player.level() == null || !player.level().dimension().location().equals(dimension)) {
            return;
        }
        lockedDimension = dimension;
        staffLocks.clear();
        staffLocks.addAll(ids);
    }

    // ============ 多人选择（功能2） ============
    /**
     * S2C：服务端权威的选择快照。只用来记录「别人的选择 + 各自的颜色 + 我的颜色」，
     * <b>不会</b>覆盖我自己的本地队列 —— 退出多选后队列仍保留（服务端占用已释放），
     * 所以服务端的“我没有选任何东西”不能当作清空本地队列的依据。
     */
    public static void setSelections(final ResourceLocation dimension, final List<StaffEnhanceNetworking.SelectionEntry> entries) {
        final LocalPlayer player = localPlayer();
        if (player == null || player.level() == null || !player.level().dimension().location().equals(dimension)) {
            return;
        }
        final UUID me = player.getUUID();
        otherSelections.clear();
        selectionColors.clear();
        for (final StaffEnhanceNetworking.SelectionEntry entry : entries) {
            selectionColors.put(entry.player(), entry.colorIndex());
            if (entry.player().equals(me)) {
                myColorIndex = entry.colorIndex();
            } else if (!entry.ids().isEmpty()) {
                otherSelections.put(entry.player(), new LinkedHashSet<>(entry.ids()));
            }
        }
    }

    /** S2C：本次选中被服务端拒绝（已被他人占用）。 */
    public static void onSelectionDenied(final Collection<UUID> denied, final String owners) {
        int removed = 0;
        for (final UUID id : denied) {
            if (selected.remove(id)) {
                removed++;
            }
        }
        final LocalPlayer player = localPlayer();
        if (player != null) {
            player.displayClientMessage(
                    Component.translatable("sablestopnow.staff.select_denied", denied.size(), owners), false);
        }
    }

    /** C2S：把一组物理结构登记为“我选中”（服务端做独占校验）。 */
    private static void claimSelection(final Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        claimsPending = true;
        VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.SelectBodiesPayload(true, new ArrayList<>(ids)));
    }

    /** C2S：把一组物理结构移出我的选择（释放占用）。 */
    private static void releaseSelection(final Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.SelectBodiesPayload(false, new ArrayList<>(ids)));
    }

    /** C2S：清空我的选择并释放全部占用（退出多选 / 清空队列 / 丢下手杖时调用）。 */
    private static void releaseAllClaims() {
        if (!claimsPending) {
            return;
        }
        claimsPending = false;
        if (localPlayer() == null || Minecraft.getInstance().level == null) {
            return;
        }
        VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.ClearSelectionPayload());
    }

    // ============ 快照（功能9） ============

    /** S2C：我当前存了快照的物理结构集合。 */
    public static void setSnapshotIds(final ResourceLocation dimension, final Collection<UUID> ids) {
        final LocalPlayer player = localPlayer();
        if (player == null || player.level() == null) {
            return;
        }
        snapshotDimension = dimension;
        snapshotIds.clear();
        if (player.level().dimension().location().equals(dimension)) {
            snapshotIds.addAll(ids);
        }
    }

    /** K：当前没有快照 → 用选中队列创建；已有快照 → 取消。 */
    private static void onSnapshotKey() {
        if (!snapshotIds.isEmpty()) {
            VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.SnapshotPayload(false, List.of()));
            snapshotIds.clear();
            return;
        }
        if (selected.isEmpty()) {
            prompt("sablestopnow.staff.snap_need_queue");
            return;
        }
        VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.SnapshotPayload(true, new ArrayList<>(selected)));
    }

    /** R：把整份快照回退。 */
    private static void onRestoreKey() {
        if (snapshotIds.isEmpty()) {
            prompt("sablestopnow.staff.restore_none");
            return;
        }
        VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.RestoreSnapshotPayload());
    }

    // ============ 缩放（X + 滚轮） ============

    /** 按下 X：开始缩放会话（服务端先把队列里不同缩放的结构统一到未缩放状态）。 */
    private static void startScaling() {
        if (scalingSession) {
            return;
        }
        if (selected.isEmpty()) {
            prompt("sablestopnow.staff.scale_need_queue");
            return;
        }
        scalingSession = true;
        scaleFactor = 1.0f;
        VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.BeginScalePayload(new ArrayList<>(selected)));
        prompt("sablestopnow.staff.scale_begin", selected.size());
    }

    /** 松开 X：结束会话（保留当前缩放结果）。 */
    private static void stopScaling() {
        if (!scalingSession) {
            return;
        }
        scalingSession = false;
        VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.EndScalePayload());
    }

    // ============ 所有权（功能3） ============

    /** S2C：服务端权威的所有权表（当前维度）。 */
    public static void setOwnerships(final ResourceLocation dimension, final List<StaffEnhanceNetworking.OwnershipEntry> entries) {
        final LocalPlayer player = localPlayer();
        if (player == null || player.level() == null || !player.level().dimension().location().equals(dimension)) {
            return;
        }
        ownershipOwners.clear();
        ownershipNames.clear();
        for (final StaffEnhanceNetworking.OwnershipEntry entry : entries) {
            ownershipOwners.put(entry.subLevel(), entry.owner());
            ownershipNames.put(entry.subLevel(), entry.ownerName());
        }
    }

    /**
     * O 键：对当前选中队列切换所有权。
     * 全部已属于我 → 取消；否则 → 设为我的（已是他人所有的会被服务端拒绝并提示）。
     */
    private static void onOwnershipKey() {
        final LocalPlayer player = localPlayer();
        if (player == null) {
            return;
        }
        if (selected.isEmpty()) {
            prompt("sablestopnow.staff.own_need_queue");
            return;
        }
        boolean allMine = true;
        for (final UUID id : selected) {
            if (!player.getUUID().equals(ownershipOwners.get(id))) {
                allMine = false;
                break;
            }
        }
        final boolean own = !allMine;
        // 乐观更新（服务端随后回推权威表）
        for (final UUID id : selected) {
            if (own) {
                ownershipOwners.put(id, player.getUUID());
                ownershipNames.put(id, player.getGameProfile().getName());
            } else if (player.getUUID().equals(ownershipOwners.get(id))) {
                ownershipOwners.remove(id);
                ownershipNames.remove(id);
            }
        }
        VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.SetOwnershipPayload(own, new ArrayList<>(selected)));
        prompt(own ? "sablestopnow.staff.own_set" : "sablestopnow.staff.own_cleared", selected.size());
    }

    // ============ 整组拖拽 ============
    private static void startGroupDrag(final Pick pick) {
        final LocalPlayer player = localPlayer();
        if (player == null || pick == null || pick.body == null) {
            return;
        }
        final UUID leaderUuid = pick.body.getUniqueId();

        final List<UUID> members = new ArrayList<>(selected);
        if (!members.contains(leaderUuid)) {
            members.add(0, leaderUuid);
        }

        // 把“质心 − 眼睛”向量分解到“视线坐标系”（前/右/上）并保持：初始时由真实质心解出，
        // 因此第一帧目标=当前质心，无瞬间位移；此后转动视线会带动质心按同分量环绕移动。
        final Vec3 eye = player.getEyePosition(1.0f);
        final Vec3 centroid = centroidWorld(members);
        final Vec3 startLook = player.getLookAngle();
        final Vec3 startRight = viewRight(startLook);
        final Vec3 startUp = viewUp(startLook, startRight);
        final Vec3 delta = centroid != null ? centroid.subtract(eye) : pick.worldPos.subtract(eye);

        final double offF = delta.dot(startLook);
        final double offR = delta.dot(startRight);
        final double offU = delta.dot(startUp);
        final double dist = Math.clamp(delta.length(), 2.0, RAY_RANGE);

        // 组旋转初始为恒等（不引入额外旋转；服务端对“初始朝向”再乘组旋转）
        groupDrag = new ClientGroupDrag(leaderUuid, dist, offF, offR, offU);

        // 先解除全部选中体的锁定，再进入整组拖拽
        sendSetLocks(false, members);
        if (superliminalOn) {
            // 彩蛋模式：只开缩放会话（绝对定位），**不**发整组拖拽包 ——
            // 否则服务端的整组马达与缩放会话会互相拉扯，结构会乱飘。
            beginSuperliminalScaling(player);
        } else {
            VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.StartGroupPayload(leaderUuid, members));
        }
        prompt("sablestopnow.staff.group_start", members.size());
    }

    // ============ 彩蛋：Superliminal ============

    /** 抓取瞬间记录基线距离与半尺寸，并开始缩放会话。 */
    private static void beginSuperliminalScaling(final LocalPlayer player) {
        scalingSession = true;
        scaleFactor = 1.0f;
        VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.BeginScalePayload(new ArrayList<>(selected)));
        final double maxDistance = SablestopNowConfig.superliminalMaxDistance();
        final double half = groupHalfExtent(selected);
        final Vec3 target = superliminalTargetPoint(player, maxDistance, half);
        superliminalBaseDistance = target != null
                ? Math.max(0.5, target.distanceTo(player.getEyePosition(1.0f)))
                : Math.max(0.5, maxDistance);
        prompt("sablestopnow.staff.superliminal_grab", String.format("%.1f", superliminalBaseDistance));
    }

    /**
     * 视线射线命中的世界点（沿命中面法线外推 objectHalfExtent 以避免穿模）。
     * 未命中任何方块/物理结构时返回 null（调用方退化为“放在最大距离处”）。
     */
    @Nullable
    private static Vec3 superliminalTargetPoint(final LocalPlayer player, final double maxDistance,
                                                final double objectHalfExtent) {
        final Vec3 eye = player.getEyePosition(1.0f);
        final Vec3 look = player.getLookAngle();
        final BlockHitResult hit = clipOnce(player, eye, eye.add(look.scale(maxDistance)), null);
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return null;
        }
        final SubLevel body = Sable.HELPER.getContainingClient(hit.getLocation());
        final Vec3 world = body != null ? toWorld(body, hit.getLocation()) : hit.getLocation();
        Vec3 normal = new Vec3(hit.getDirection().getStepX(), hit.getDirection().getStepY(), hit.getDirection().getStepZ());
        if (body instanceof final ClientSubLevel client) {
            // 物理结构的命中点/法线都是基准坐标，需要按位姿把法线转回世界
            final Vector3dc rotated = client.renderPose().transformNormal(new Vector3d(normal.x, normal.y, normal.z));
            final double len = rotated.length();
            if (len > 1.0e-4) {
                normal = new Vec3(rotated.x() / len, rotated.y() / len, rotated.z() / len);
            }
        }
        return world.add(normal.scale(objectHalfExtent + 0.05));
    }

    /** 选中队列的世界包围盒对角线的一半（用于把物体推到表面外侧）。 */
    private static double groupHalfExtent(final Collection<UUID> ids) {
        final LocalPlayer player = localPlayer();
        if (player == null || ids.isEmpty()) {
            return 0.5;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(player.level());
        if (container == null) {
            return 0.5;
        }
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        int count = 0;
        for (final UUID id : ids) {
            final SubLevel sub = container.getSubLevel(id);
            if (sub == null || sub.isRemoved()) {
                continue;
            }
            final BoundingBox3dc box = sub.boundingBox();
            if (box == null) {
                continue;
            }
            minX = Math.min(minX, box.minX());
            minY = Math.min(minY, box.minY());
            minZ = Math.min(minZ, box.minZ());
            maxX = Math.max(maxX, box.maxX());
            maxY = Math.max(maxY, box.maxY());
            maxZ = Math.max(maxZ, box.maxZ());
            count++;
        }
        if (count == 0) {
            return 0.5;
        }
        final double dx = maxX - minX;
        final double dy = maxY - minY;
        final double dz = maxZ - minZ;
        return Math.max(0.25, 0.5 * Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    /** 视线右向量（水平分量叉乘上向量，退化时退回 X 轴）。 */
    private static Vec3 viewRight(final Vec3 look) {
        final Vec3 up = new Vec3(0, 1, 0);
        final Vec3 cross = look.cross(up);
        final double len = cross.length();
        return len > 1.0e-4 ? cross.scale(1.0 / len) : new Vec3(1, 0, 0);
    }

    /** 视线上的“上”向量 = right × look。 */
    private static Vec3 viewUp(final Vec3 look, final Vec3 right) {
        final Vec3 up = right.cross(look);
        final double len = up.length();
        return len > 1.0e-4 ? up.scale(1.0 / len) : new Vec3(0, 1, 0);
    }

    /** 当前视线分量对应的组中心世界坐标。 */
    private static Vec3 dragCenter(final LocalPlayer player, final ClientGroupDrag drag) {
        final Vec3 look = player.getLookAngle();
        final Vec3 right = viewRight(look);
        final Vec3 up = viewUp(look, right);
        return player.getEyePosition(1.0f)
                .add(look.scale(drag.offsetF))
                .add(right.scale(drag.offsetR))
                .add(up.scale(drag.offsetU));
    }

    /** 按世界坐标点反解视线分量（滚轮沿眼→心线缩放后回写）。 */
    private static void setDragCenterFromWorld(final LocalPlayer player, final ClientGroupDrag drag, final Vec3 world) {
        final Vec3 delta = world.subtract(player.getEyePosition(1.0f));
        final Vec3 look = player.getLookAngle();
        final Vec3 right = viewRight(look);
        final Vec3 up = viewUp(look, right);
        drag.offsetF = delta.dot(look);
        drag.offsetR = delta.dot(right);
        drag.offsetU = delta.dot(up);
    }

    /** 队列成员世界中心近似（renderPose.position 均值）；无成员返回 null。 */
    @Nullable
    private static Vec3 centroidWorld(final Collection<UUID> ids) {
        final LocalPlayer player = localPlayer();
        if (player == null || ids.isEmpty()) {
            return null;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(player.level());
        if (container == null) {
            return null;
        }
        double sx = 0;
        double sy = 0;
        double sz = 0;
        int count = 0;
        for (final UUID id : ids) {
            final SubLevel sub = container.getSubLevel(id);
            if (sub == null || sub.isRemoved()) {
                continue;
            }
            final Pose3dc pose = sub instanceof final ClientSubLevel c ? c.renderPose() : sub.logicalPose();
            sx += pose.position().x();
            sy += pose.position().y();
            sz += pose.position().z();
            count++;
        }
        if (count == 0) {
            return null;
        }
        return new Vec3(sx / count, sy / count, sz / count);
    }

    /** 结束整组拖拽：解绑服务端会话；选中队列保留（仍处待命态），清空请用 Shift+右键。 */
    private static void stopGroupDrag() {
        if (groupDrag == null) {
            return;
        }
        final UUID leaderUuid = groupDrag.leader;
        VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.StopGroupPayload(leaderUuid));
        groupDrag = null;
        // 彩蛋会话同时结束缩放
        if (scalingSession) {
            stopScaling();
        }
        prompt("sablestopnow.staff.group_stop");
    }

    /** 静默发送锁定/解锁请求（不带提示）。 */
    private static void sendSetLocks(final boolean lock, final Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.SetLocksPayload(lock, new ArrayList<>(ids)));
    }

    /** 每 tick：组中心 = 眼睛 + (前/右/上 视线分量偏移)；长按 C 时把分量缓推向“正前方 distance”。 */
    private static void sendGroupDragTick(final LocalPlayer player) {
        final ClientGroupDrag drag = groupDrag;
        if (drag == null) {
            return;
        }
        final Vec3 eye = player.getEyePosition(1.0f);
        final Vec3 look = player.getLookAngle();

        // 彩蛋 Superliminal：把物体放到视线射线命中的平面/结构上，并按距离比缩放以保持屏幕大小不变。
        // ⚠ 这条路径“绝对定位”，且**不**发送整组拖拽包：否则服务端的整组马达会把成员拉回原始相对位姿，
        //   与缩放会话的“相对偏移 × 倍率”互相打架，表现就是结构乱飘。
        if (superliminalOn) {
            final double maxDistance = SablestopNowConfig.superliminalMaxDistance();
            final double baseHalf = groupHalfExtent(selected);
            final BlockHitResult hit = clipOnce(player, eye, eye.add(look.scale(maxDistance)), null);
            Vec3 surface = null;
            Vec3 normal = null;
            if (hit != null && hit.getType() != HitResult.Type.MISS) {
                final SubLevel body = Sable.HELPER.getContainingClient(hit.getLocation());
                surface = body != null ? toWorld(body, hit.getLocation()) : hit.getLocation();
                Vec3 n = new Vec3(hit.getDirection().getStepX(), hit.getDirection().getStepY(), hit.getDirection().getStepZ());
                if (body instanceof final ClientSubLevel client) {
                    final Vector3dc rotated = client.renderPose().transformNormal(new Vector3d(n.x, n.y, n.z));
                    final double len = rotated.length();
                    if (len > 1.0e-4) {
                        n = new Vec3(rotated.x() / len, rotated.y() / len, rotated.z() / len);
                    }
                }
                normal = n;
            }
            final Vec3 point = surface != null ? surface : eye.add(look.scale(maxDistance));
            final double distance = Math.max(0.5, point.distanceTo(eye));
            final float factor = (float) Math.clamp(distance / Math.max(0.5, superliminalBaseDistance),
                    SablestopNowConfig.scaleMin(), SablestopNowConfig.scaleMax());
            // 沿命中面法线外推「放大后的半尺寸」，避免穿模（用 r0×f，且 f 不依赖这个偏移 → 不会自激振荡）
            final Vec3 center = normal != null
                    ? point.add(normal.scale(baseHalf * factor))
                    : point;
            scaleFactor = factor;
            VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.SuperliminalPlacePayload(
                    factor, new Vector3d(center.x, center.y, center.z)));
            return;
        }

        final Vec3 right = viewRight(look);
        final Vec3 up = viewUp(look, right);
        if (StaffKeyMappings.CENTER_PULL.isDown()) {
            drag.offsetF += (drag.distance - drag.offsetF) * SablestopNowConfig.centerPullSpeed();
            drag.offsetR *= (1.0 - SablestopNowConfig.centerPullSpeed());
            drag.offsetU *= (1.0 - SablestopNowConfig.centerPullSpeed());
        }
        final Vec3 center = eye
                .add(look.scale(drag.offsetF))
                .add(right.scale(drag.offsetR))
                .add(up.scale(drag.offsetU));
        VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.MoveGroupPayload(
                new Vector3d(center.x, center.y, center.z), drag.orientation));
    }

    // ============ 拾取 / 标记（基于 Sable 位姿感知 raycast） ============
    /**
     * 沿视线取“穿透 depth 个物理体后的下一个物理体”。
     *
     * <p>关键：Sable 物理体按位姿渲染，区块归属查询（getContaining/getContainingClient）使用的是
     * plot base 坐标。因此必须走 Sable 自带的位姿感知 clip（level.clip 已被 Sable @Overwrite，
     * 这里照航空学 updateHoverPos 的做法再 push renderPose supplier），命中点即 base 坐标，
     * 可直接用 getContainingClient 识别物理体；地形命中不算层数、越过继续找。
     */
    @Nullable
    private static Pick pickAtDepth(final int depth) {
        final LocalPlayer player = localPlayer();
        if (player == null || player.level() == null) {
            return null;
        }
        final Vec3 eye = player.getEyePosition(1.0f);
        final Vec3 dir = player.getLookAngle();
        final Vec3 end = eye.add(dir.scale(RAY_RANGE));
        Vec3 from = eye;
        SubLevel ignore = null;
        int found = 0;
        for (int i = 0; i < 128; i++) {
            final BlockHitResult hit = clipOnce(player, from, end, ignore);
            if (hit == null || hit.getType() == HitResult.Type.MISS) {
                return null;
            }
            final SubLevel body = Sable.HELPER.getContainingClient(hit.getLocation());
            if (body == null || body == ignore) {
                // 地形 / 刚被跳过的体：越过后继续
                from = body == null ? hit.getLocation() : toWorld(body, hit.getLocation());
                from = from.add(dir.scale(0.05));
                continue;
            }
            final BlockPos baseBlock = BlockPos.containing(hit.getLocation());
            final Vec3 worldPos = toWorld(body, hit.getLocation());
            if (found == depth) {
                return new Pick(body, baseBlock, worldPos);
            }
            found++;
            ignore = body;
            from = worldPos.add(dir.scale(0.05));
            if (from.distanceToSqr(eye) > RAY_RANGE * RAY_RANGE) {
                return null;
            }
        }
        return null;
    }

    /**
     * 沿视线找第一个“已在选中队列”的物理体（与当前穿透层数无关）。用于退出多选后右键发起整组拖拽，
     * 保证即使视线先穿过其它非队列体、或穿透层数>0，也能正确激活队列整体。
     */
    @Nullable
    private static Pick pickQueueLeader() {
        final LocalPlayer player = localPlayer();
        if (player == null || player.level() == null) {
            return null;
        }
        final Vec3 eye = player.getEyePosition(1.0f);
        final Vec3 dir = player.getLookAngle();
        final Vec3 end = eye.add(dir.scale(RAY_RANGE));
        Vec3 from = eye;
        SubLevel ignore = null;
        for (int i = 0; i < 128; i++) {
            final BlockHitResult hit = clipOnce(player, from, end, ignore);
            if (hit == null || hit.getType() == HitResult.Type.MISS) {
                return null;
            }
            final SubLevel body = Sable.HELPER.getContainingClient(hit.getLocation());
            if (body == null || body == ignore) {
                from = body == null ? hit.getLocation() : toWorld(body, hit.getLocation());
                from = from.add(dir.scale(0.05));
                continue;
            }
            if (selected.contains(body.getUniqueId())) {
                return new Pick(body, BlockPos.containing(hit.getLocation()), toWorld(body, hit.getLocation()));
            }
            ignore = body;
            from = toWorld(body, hit.getLocation()).add(dir.scale(0.05));
        }
        return null;
    }

    @Nullable
    private static SubLevel pickTarget() {
        final Pick pick = pickAtDepth(penetration);
        return pick != null ? pick.body : null;
    }

    /**
     * 一次位姿感知 clip：与航空学 hover 相同，push ClientSubLevel.renderPose supplier 后调用
     * Sable 已重写的 level.clip。ignore 用于跳过一个已选中的物理体（穿透时逐层前进）。
     */
    @Nullable
    private static BlockHitResult clipOnce(final LocalPlayer player, final Vec3 from, final Vec3 to,
                                           @Nullable final SubLevel ignore) {
        final Level level = player.level();
        if (level == null || !(level instanceof final LevelPoseProviderExtension extension)) {
            return null;
        }
        final ClipContext context = new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE,
                CollisionContext.of(player));
        if (ignore != null) {
            ((ClipContextExtension) (Object) context).sable$setIgnoredSubLevel(ignore);
        }
        extension.sable$pushPoseSupplier(x -> ((ClientSubLevel) x).renderPose());
        try {
            return level.clip(context);
        } finally {
            extension.sable$popPoseSupplier();
        }
    }

    /** 把物理体 plot(base) 坐标点映射回世界可视坐标（用与 clip 一致的 renderPose）。 */
    private static Vec3 toWorld(final SubLevel body, final Vec3 basePos) {
        if (body instanceof final ClientSubLevel client) {
            final Vector3dc w = client.renderPose().transformPosition(new Vector3d(basePos.x, basePos.y, basePos.z));
            return new Vec3(w.x(), w.y(), w.z());
        }
        return basePos;
    }

    /** Z 框选标记：准星命中（地形或物理体）的“可视方块格点”。射线从眼睛前方 1.5m 起算，
     *  避免玩家站在结构内部时把“玩家所在格”误判为标记点。 */
    @Nullable
    private static BlockPos aimBlock(final Level level) {
        final LocalPlayer player = localPlayer();
        if (player == null || player.level() == null) {
            return null;
        }
        final Vec3 eye = player.getEyePosition(1.0f);
        final Vec3 dir = player.getLookAngle();
        final Vec3 start = eye.add(dir.scale(1.5));
        final BlockHitResult hit = clipOnce(player, start, eye.add(dir.scale(RAY_RANGE)), null);
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return null;
        }
        final SubLevel body = Sable.HELPER.getContainingClient(hit.getLocation());
        final Vec3 world = body != null ? toWorld(body, hit.getLocation()) : hit.getLocation();
        return BlockPos.containing(world);
    }

    /** 一次命中的结果：物理体 + plot(base) 格点 + 世界坐标点。 */
    private static final class Pick {
        private final SubLevel body;
        private final BlockPos baseBlock;
        private final Vec3 worldPos;

        private Pick(final SubLevel body, final BlockPos baseBlock, final Vec3 worldPos) {
            this.body = body;
            this.baseBlock = baseBlock;
            this.worldPos = worldPos;
        }
    }

    // ============ 提示 ============
    private static void prompt(final String key, final Object... args) {
        final LocalPlayer player = localPlayer();
        if (player == null) {
            return;
        }
        player.displayClientMessage(Component.translatable(key, args), true);
    }

    public static Collection<UUID> selectedSnapshot() {
        return List.copyOf(selected);
    }

    /** 整组拖拽会话（客户端）。 */
    private static final class ClientGroupDrag {
        private final UUID leader;
        /** 质心相对视线坐标系的偏移：前向/右向/上向分量（米）。 */
        private double offsetF;
        private double offsetR;
        private double offsetU;
        /** 长按 C 时视线拉近的目标距离。 */
        private double distance;
        private final Quaterniond orientation = new Quaterniond();

        private ClientGroupDrag(final UUID leader, final double distance,
                                final double offsetF, final double offsetR, final double offsetU) {
            this.leader = leader;
            this.distance = distance;
            this.offsetF = offsetF;
            this.offsetR = offsetR;
            this.offsetU = offsetU;
        }
    }
}
