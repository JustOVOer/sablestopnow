package com.ovo.sablestopnow.force;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * 一个「可控施力点」：贴在某个模型表面上的一个点，沿该点表面法线施力，由 Create 无线红石频道触发。
 *
 * <h2>存的是什么</h2>
 * 与 {@link com.ovo.sablestopnow.mate.MateRef} 同一思路：存<b>描述</b>（哪个体 + plot 局部的点与法线），
 * 世界坐标每帧从 {@code logicalPose()} 现算 —— 因为结构会动、会被缩放旋转，
 * 存世界坐标的话模型一转力就指错方向了。
 *
 * <h2>力的方向</h2>
 * 「垂直于此点」= 沿命中面的法线（plot 局部存的是方块面法线，世界系里随结构朝向一起转）。
 * 这样把结构翻个面，力也跟着翻，符合「力贴在表面上」的直觉。
 */
public record ForcePoint(
        UUID id,
        UUID body,
        Vector3d localPoint,
        Vector3d localNormal,
        double magnitude,
        @Nullable String channelItemId,
        @Nullable String channelItemId2,
        @Nullable String channelName,
        boolean enabled) {

    /** 力的大小范围（牛顿）——用户确认：0–1000，默认 50。 */
    public static final double MIN_MAGNITUDE = 0.0;
    public static final double MAX_MAGNITUDE = 1000.0;
    public static final double DEFAULT_MAGNITUDE = 50.0;

    /** 在一个方块面上、按「面内偏移」建一个施力点（放置流程用）。 */
    public static ForcePoint onFace(final UUID body, final BlockPos block, final Direction face,
                                    final Vector3d localPoint, final double magnitude) {
        final Vector3d normal = new Vector3d(face.getStepX(), face.getStepY(), face.getStepZ());
        return new ForcePoint(UUID.randomUUID(), body, new Vector3d(localPoint), normal,
                clampMagnitude(magnitude), null, null, null, true);
    }

    public static double clampMagnitude(final double value) {
        if (!Double.isFinite(value)) {
            return DEFAULT_MAGNITUDE;
        }
        return Math.max(MIN_MAGNITUDE, Math.min(MAX_MAGNITUDE, value));
    }

    public ForcePoint withMagnitude(final double value) {
        return new ForcePoint(this.id, this.body, this.localPoint, this.localNormal,
                clampMagnitude(value), this.channelItemId, this.channelItemId2, this.channelName, this.enabled);
    }

    /**
     * 改频道。
     *
     * <p>Create 的红石链接频率是<b>两个槽</b>（{@code Couple<Frequency>}）：两个物品一起构成频率对。
     * 所以这里存两段物品注册名；第二段为空时退回「同一个物品放两个槽」（见 {@link #channelStack2()}），
     * 与旧存档、旧习惯完全兼容。
     */
    public ForcePoint withChannel(@Nullable final String itemId, @Nullable final String itemId2,
                                  @Nullable final String name) {
        return new ForcePoint(this.id, this.body, this.localPoint, this.localNormal,
                this.magnitude, itemId, itemId2, name, this.enabled);
    }

    public ForcePoint withEnabled(final boolean value) {
        return new ForcePoint(this.id, this.body, this.localPoint, this.localNormal,
                this.magnitude, this.channelItemId, this.channelItemId2, this.channelName, value);
    }

    /** 世界系的力的方向（单位向量，已按结构朝向旋转）；法线退化为 0 时返回 null。 */
    @Nullable
    public Vector3d worldDirection(final Pose3dc pose) {
        final Vector3d out = pose.orientation().transform(new Vector3d(this.localNormal));
        if (out.lengthSquared() < 1.0e-12) {
            return null;
        }
        return out.normalize();
    }

    /** 世界系的受力点位置。 */
    public Vector3d worldPoint(final Pose3dc pose) {
        return pose.transformPosition(new Vector3d(this.localPoint), new Vector3d());
    }

    /**
     * 世界系的力矢量（牛顿·方向）。
     *
     * <p>注意力的<b>大小</b>不乘结构的缩放：牛顿是绝对的物理量，
     * 而结构被放大后同样的力看起来效果变弱是符合直觉的（真实世界也一样）。
     */
    @Nullable
    public Vector3d worldForce(final Pose3dc pose) {
        final Vector3d dir = worldDirection(pose);
        return dir == null ? null : dir.mul(this.magnitude);
    }

    // ============ 频道 ============

    /**
     * 频道是否配置完整（物品注册名非空且能在注册表里找到）。
     *
     * <p>Create 的红石链接频率就是一个 {@code ItemStack}（物品 + 可选自定义名），
     * 所以「频道」在这里就是「物品注册名 + 自定义名」两段。
     */
    public boolean channelConfigured() {
        return item() != null;
    }

    /** 频道物品；注册名非法/找不到时返回 null。 */
    @Nullable
    public Item item() {
        return item(this.channelItemId);
    }

    /** 第二个频率槽的物品；未设置时返回第一个槽的物品（等价于两槽放同一个物品）。 */
    @Nullable
    public Item item2() {
        final Item second = item(this.channelItemId2);
        return second != null ? second : item();
    }

    @Nullable
    private static Item item(@Nullable final String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        final ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return null;
        }
        final Item found = BuiltInRegistries.ITEM.get(id);
        return found == Items.AIR ? null : found;
    }

    /**
     * 频道对应的物品堆（Create 的红石链接频率就是这个东西）。
     *
     * <p>自定义名走 {@code CustomData} 里的 {@code display.Name} —— 与 Create 自己
     * 在频率槽里放一个改了名的物品等价，这样玩家的「机械动力无线红石」装置能直接对上。
     */
    @Nullable
    public ItemStack channelStack() {
        return stack(this.item());
    }

    /** 第二个频率槽的物品堆（未设置第二槽时与 {@link #channelStack()} 相同）。 */
    @Nullable
    public ItemStack channelStack2() {
        return stack(this.item2());
    }

    @Nullable
    private ItemStack stack(@Nullable final Item found) {
        if (found == null) {
            return null;
        }
        final ItemStack stack = new ItemStack(found);
        if (this.channelName != null && !this.channelName.isBlank()) {
            stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal(this.channelName));
        }
        return stack;
    }

    /** 频道的人类可读描述（面板上显示用）。 */
    public String channelLabel() {
        final String first = this.channelItemId == null || this.channelItemId.isEmpty()
                ? "—" : this.channelItemId;
        final String second = this.channelItemId2 == null || this.channelItemId2.isEmpty()
                ? first : this.channelItemId2;
        final String pair = first.equals(second) ? first : first + " + " + second;
        return this.channelName == null || this.channelName.isBlank()
                ? pair : pair + " #" + this.channelName;
    }

    public String describe() {
        return "force@" + this.body + " " + String.format("%.0f", this.magnitude) + "N "
                + (this.enabled ? "on" : "off") + " [" + channelLabel() + "]";
    }
}
