package com.ovo.sablestopnow.server;

import com.ovo.sablestopnow.force.ForcePoint;
import com.ovo.sablestopnow.force.ForcePointData;
import com.ovo.sablestopnow.network.ForceNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.UUID;

/**
 * 可控施力点的服务端注册表：增删改 + 广播。
 *
 * <p>与 {@link MateRegistry} 的分工一样：这里只管「数据对不对、谁改的、广播给谁」，
 * 施力本身在 {@link ForcePointService}（每个物理子步）。
 */
public final class ForcePointRegistry {

    private ForcePointRegistry() {
    }

    /**
     * 新建一个施力点。
     *
     * @param localPoint plot 局部的受力点
     * @param face       命中的方块面（力的方向 = 该面法线）
     * @param enabled    建出来是不是「已开启」
     */
    @Nullable
    public static ForcePoint create(final ServerLevel level, final UUID body, final BlockPos block,
                                    final Direction face, final Vector3d localPoint,
                                    final double magnitude, final boolean enabled) {
        if (body == null || face == null || localPoint == null) {
            return null;
        }
        ForcePoint point = ForcePoint.onFace(body, block, face, localPoint, magnitude);
        if (!enabled) {
            point = point.withEnabled(false);
        }
        ForcePointData.get(level).put(point);
        ForceNetworking.broadcast(level);
        return point;
    }

    /** 旧重载：默认建出来就是开启的。 */
    @Nullable
    public static ForcePoint create(final ServerLevel level, final UUID body, final BlockPos block,
                                    final Direction face, final Vector3d localPoint,
                                    final double magnitude) {
        return create(level, body, block, face, localPoint, magnitude, true);
    }

    /** 改属性（力大小 / 频道两个槽 / 启用）。 */
    public static boolean update(final ServerLevel level, final UUID pointId,
                                 @Nullable final Double magnitude,
                                 @Nullable final String channelItem,
                                 @Nullable final String channelItem2,
                                 @Nullable final String channelName,
                                 @Nullable final Boolean enabled) {
        final ForcePointData data = ForcePointData.get(level);
        final ForcePoint current = data.all().get(pointId);
        if (current == null) {
            return false;
        }
        ForcePoint next = current;
        if (magnitude != null) {
            next = next.withMagnitude(magnitude);
        }
        if (channelItem != null || channelItem2 != null || channelName != null) {
            next = next.withChannel(channelItem == null ? next.channelItemId() : channelItem,
                    channelItem2 == null ? next.channelItemId2() : channelItem2,
                    channelName);
        }
        if (enabled != null) {
            next = next.withEnabled(enabled);
        }
        data.put(next);
        ForceNetworking.broadcast(level);
        return true;
    }

    public static boolean remove(final ServerLevel level, final UUID pointId) {
        final boolean removed = ForcePointData.get(level).remove(pointId);
        if (removed) {
            ForceNetworking.broadcast(level);
        }
        return removed;
    }

    /** 玩家所在维度的施力点（GUI 用）。 */
    public static void sendAllTo(final ServerPlayer player) {
        ForceNetworking.sendAllTo(player);
    }
}
