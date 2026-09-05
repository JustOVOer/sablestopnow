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
import java.util.LinkedHashSet;
import java.util.List;
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
 * 输入由 KeyboardHandler/MouseHandler/LocalPlayer 的 Mixin 调 {@link #handleKey} / {@link #handleMouse}
 * / {@link #handleScroll} / {@link #handleLookMove}，返回 true 表示已消费（需在 Mixin 里 cancel）。
 */
public final class StaffEnhanceClientHandler {

    // ---- 固定键位/常量（Ctrl/Z/V/C 键与灵敏度已在 config staff_enhance 段配置） ----
    public static final int KEY_ALT = GLFW.GLFW_KEY_LEFT_ALT;

    public static final int MOUSE_RIGHT = GLFW.GLFW_MOUSE_BUTTON_RIGHT;
    public static final int MOUSE_LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;
    public static final int MOUSE_MIDDLE = GLFW.GLFW_MOUSE_BUTTON_MIDDLE;

    private static final double RAY_RANGE = 128.0;

    // ---- 模式状态 ----
    private static boolean multiSelect;
    private static final Set<UUID> selected = new LinkedHashSet<>();
    private static int penetration;
    private static int boxStep;
    @Nullable private static BlockPos boxFirst;
    @Nullable private static BlockPos boxSecond;

    /** 当前维度的无碰撞标记集（来自服务端同步；乐观更新用于即时提示）。 */
    private static ResourceLocation noCollisionDimension;
    private static final Set<UUID> noCollision = new LinkedHashSet<>();

    /** 当前维度已锁定物理体集（S2C 同步，用于左键锁定/解锁方向判断）。 */
    private static ResourceLocation lockedDimension;
    private static final Set<UUID> staffLocks = new LinkedHashSet<>();

    /** 整组拖拽会话（非空=正在拖拽整组）。 */
    @Nullable private static ClientGroupDrag groupDrag;

    /** 多选模式下“当前右键将选中”的物理体（每 tick 刷新，供渲染悬停高亮）。 */
    @Nullable private static UUID hoverBody;
    /** 框选等待第二点时实时预览的角点（每 tick 刷新）。 */
    @Nullable private static BlockPos boxPreview;
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

    public static int getPenetration() {
        return penetration;
    }

    public static int getBoxStep() {
        return boxStep;
    }

    @Nullable
    public static BlockPos getBoxFirst() {
        return boxFirst;
    }

    @Nullable
    public static BlockPos getBoxSecond() {
        return boxSecond;
    }

    /** 当前悬停（将被右键选中）的物理体 id，多选模式外为 null。 */
    @Nullable
    public static UUID getHoverBody() {
        return hoverBody;
    }

    /** 框选实时预览角点（每 tick 刷新；boxStep==1 时有效）。 */
    @Nullable
    public static BlockPos getBoxPreview() {
        return boxPreview;
    }

    @Nullable
    public static BlockPos aimBlockPreview(final Level level) {
        return boxPreview;
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

    // ============ 输入入口 ============
    public static boolean handleKey(final int key, final int scanCode, final int action, final int modifiers) {
        if (!isActive()) {
            return false;
        }
        if (action != GLFW.GLFW_PRESS) {
            return false;
        }
        if (key == SablestopNowConfig.keyMultiSelect()) {
            toggleMultiSelect();
            return false;
        }
        if (key == SablestopNowConfig.keyBoxSelect()) {
            if (multiSelect) {
                onBoxSelectKey();
                return true;
            }
            return false;
        }
        if (key == SablestopNowConfig.keyCollisionToggle()) {
            if (!multiSelect && groupDrag == null) {
                onCollisionToggleKey();
                return true;
            }
            return false;
        }
        return false;
    }

    public static boolean handleMouse(final int button, final int action, final int modifiers) {
        if (!isActive()) {
            return false;
        }
        if (action != GLFW.GLFW_PRESS) {
            return false;
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
        if (multiSelect) {
            if (isKeyDown(KEY_ALT)) {
                final int old = penetration;
                penetration = Math.max(0, Math.min(16, penetration + (deltaY > 0 ? 1 : -1)));
                if (penetration != old) {
                    prompt("sablestopnow.staff.penetration", penetration);
                }
                return true;
            }
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
        if (!isActive() || groupDrag == null || !SimKeys.ROTATE_MODE.isPressed()) {
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

    private static boolean isKeyDown(final int key) {
        final long window = Minecraft.getInstance().getWindow().getWindow();
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
    }

    // ============ 每 tick ============
    public static void tick() {
        final Minecraft mc = Minecraft.getInstance();
        final LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            resetAll();
            return;
        }
        if (!PhysicsStaffItem.isHolding(player)) {
            resetAll();
            return;
        }
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
        // 悬停高亮 / 框选预览：隔帧刷新，避免每帧做 raycast
        tickCounter++;
        if (tickCounter % 2 == 0) {
            if (multiSelect) {
                final Pick pick = pickAtDepth(penetration);
                hoverBody = pick != null ? pick.body.getUniqueId() : null;
            } else {
                hoverBody = null;
            }
            boxPreview = boxStep == 1 ? player.blockPosition() : null;
        }
    }

    private static void resetAll() {
        multiSelect = false;
        selected.clear();
        penetration = 0;
        boxStep = 0;
        boxFirst = null;
        boxSecond = null;
        hoverBody = null;
        boxPreview = null;
        if (groupDrag != null) {
            groupDrag = null; // 本地直接丢弃；服务端由超时/停服 tick 清理
        }
    }

    // ============ 多选模式动作 ============
    private static void toggleMultiSelect() {
        multiSelect = !multiSelect;
        if (!multiSelect) {
            boxStep = 0;
            boxFirst = null;
            boxSecond = null;
        }
        if (multiSelect) {
            prompt("sablestopnow.staff.multi_enter");
        } else {
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
            prompt("sablestopnow.staff.select_remove", id.toString(), selected.size());
        } else {
            prompt("sablestopnow.staff.not_selected", id.toString());
        }
    }

    /** 退出多选后的待命态：Shift+右键清空选中队列。 */
    private static void clearQueue() {
        final int size = selected.size();
        selected.clear();
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

    private static void onBoxSelectKey() {
        final LocalPlayer player = localPlayer();
        if (player == null) {
            return;
        }
        if (boxStep == 0) {
            // 第一次 Z：角点 A = 玩家所在块（玩家移动自己来定范围）
            final BlockPos marker = player.blockPosition();
            boxFirst = marker;
            boxStep = 1;
            boxSecond = null;
            prompt("sablestopnow.staff.box_first", marker.getX(), marker.getY(), marker.getZ());
            return;
        }
        // 第二次 Z：角点 B = 玩家所在块（玩家已移动到目标位置）
        final BlockPos marker = player.blockPosition();
        boxSecond = marker;
        boxStep = 2;

        final int minX = Math.min(boxFirst.getX(), boxSecond.getX());
        final int minY = Math.min(boxFirst.getY(), boxSecond.getY());
        final int minZ = Math.min(boxFirst.getZ(), boxSecond.getZ());
        final int maxX = Math.max(boxFirst.getX(), boxSecond.getX());
        final int maxY = Math.max(boxFirst.getY(), boxSecond.getY());
        final int maxZ = Math.max(boxFirst.getZ(), boxSecond.getZ());

        final long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > 1_000_000) {
            prompt("sablestopnow.staff.box_too_large");
            boxStep = 0;
            boxFirst = null;
            boxSecond = null;
            return;
        }

        // 世界坐标 AABB 与每个物理体的世界扫掠包围盒求交（boundingBox 由 Sable 按位姿更新）
        int added = 0;
        final double boxMinX = minX - 0.5;
        final double boxMinY = minY - 0.5;
        final double boxMinZ = minZ - 0.5;
        final double boxMaxX = maxX + 1.5;
        final double boxMaxY = maxY + 1.5;
        final double boxMaxZ = maxZ + 1.5;
        final SubLevelContainer container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(player.level());
        if (container != null) {
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
                if (overlaps && selected.add(sub.getUniqueId())) {
                    added++;
                }
            }
        }
        boxStep = 0;
        boxFirst = null;
        boxSecond = null;
        prompt("sablestopnow.staff.box_done", added, selected.size());
    }

    // ============ V：无碰撞切换（占位：服务端状态 + 图标） ============
    private static void onCollisionToggleKey() {
        final SubLevel target = pickTarget();
        if (target == null) {
            prompt("sablestopnow.staff.select_miss", penetration);
            return;
        }
        final UUID id = target.getUniqueId();
        // 乐观更新本地状态（随后服务端 S2C 同步覆盖）
        final boolean on = !noCollision.remove(id);
        if (on) {
            noCollision.add(id);
        }
        prompt(on ? "sablestopnow.staff.collision_on" : "sablestopnow.staff.collision_off", id.toString());
        VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.ToggleNoCollisionPayload(id));
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
        VeilPacketManager.server().sendPacket(new StaffEnhanceNetworking.StartGroupPayload(leaderUuid, members));
        prompt("sablestopnow.staff.group_start", members.size());
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
        final Vec3 right = viewRight(look);
        final Vec3 up = viewUp(look, right);
        if (isKeyDown(SablestopNowConfig.keyCenterPull())) {
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
