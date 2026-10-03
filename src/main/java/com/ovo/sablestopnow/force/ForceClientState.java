package com.ovo.sablestopnow.force;

import com.ovo.sablestopnow.network.ForceNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 可控施力点的客户端镜像（服务端权威，这里只缓存与查询）。
 *
 * <p>与 {@link com.ovo.sablestopnow.client.MateClientState} 同一模式：每次同步换一批新实例，
 * 所以界面里不能长期持有 {@link ForcePoint} 实例，要按 id 现查。
 */
public final class ForceClientState {

    private static final Map<ResourceLocation, List<ForcePoint>> POINTS = new HashMap<>();

    private ForceClientState() {
    }

    public static void setPoints(final ResourceLocation dimension, final List<ForcePoint> points) {
        POINTS.put(dimension, new ArrayList<>(points));
    }

    public static List<ForcePoint> current() {
        final var level = Minecraft.getInstance().level;
        if (level == null) {
            return List.of();
        }
        return POINTS.getOrDefault(level.dimension().location(), List.of());
    }

    /** 某个结构上的施力点（树状图分支用）。 */
    public static List<ForcePoint> ofBody(final UUID body) {
        final List<ForcePoint> out = new ArrayList<>();
        for (final ForcePoint point : current()) {
            if (point.body().equals(body)) {
                out.add(point);
            }
        }
        return out;
    }

    public static ForcePoint byId(final UUID id) {
        for (final ForcePoint point : current()) {
            if (point.id().equals(id)) {
                return point;
            }
        }
        return null;
    }

    public static void clear() {
        POINTS.clear();
    }

    // ============ 客户端请求（发包） ============

    /**
     * 请求改属性；<b>传 {@code null} 的字段表示「不改这一项」</b>。
     *
     * <p>⚠ 力大小在包体里是 {@code double}（不是可空类型），所以「不改」必须有一个<b>哨兵值</b>：
     * 这里用 {@link Double#NaN}。以前写的是 {@code magnitude == null ? 0.0 : magnitude}，
     * 于是「只改频道」的包会顺手把力大小写成 0 —— 服务端照单全收，下一次全量同步就把滑条拉回 0。
     * 用户实测反馈的「拖动施力点的滑动条后不久又会复位为 0」就是这条：
     * 拖动之后随便动一下频道/自定义名/开关（或者输入框失焦触发一次提交），力量就被清零了。
     */
    public static void requestUpdate(final UUID pointId, final Double magnitude,
                                     final String channelItem, final String channelItem2,
                                     final String channelName,
                                     final Boolean enabled) {
        ForceNetworking.sendCommand(ForceNetworking.Action.UPDATE, pointId, null, 0, 0, 0,
                0, 0, 0, magnitude == null ? Double.NaN : magnitude, channelItem, channelItem2,
                channelName, enabled);
    }

    public static void requestRemove(final UUID pointId) {
        ForceNetworking.sendCommand(ForceNetworking.Action.REMOVE, pointId, null, 0, 0, 0,
                0, 0, 0, 0.0, null, null, null, null);
    }
}
