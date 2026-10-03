package com.ovo.sablestopnow.client.model;

import com.ovo.sablestopnow.network.StaffEnhanceNetworking;
import foundry.veil.api.network.VeilPacketManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 模型属性的客户端入口：隐藏（客户端渲染）、碰撞、锁定（物理冻结）。
 *
 * <p>碰撞与锁定<b>不自己存状态</b>，直接读写模组既有的服务端权威状态
 * （与 V 键无碰撞、快照锁定共用同一份），所以树状图里的开关和按键操作是同一件事，
 * 不会出现两套互相矛盾的记录；写操作只发包，等同步回来界面自然更新。
 * 隐藏目前只在客户端生效（描边与选中里跳过该结构），真正的「整块不渲染」要挂进 Sable 的子物理体渲染管线，
 * 留到下一阶段接。
 */
public final class ModelProperties {

    private static final Set<UUID> HIDDEN = new HashSet<>();

    private ModelProperties() {
    }

    // ============ 隐藏 ============

    public static boolean hidden(final UUID body) {
        return HIDDEN.contains(body);
    }

    public static void setHidden(final UUID body, final boolean value) {
        if (value) {
            HIDDEN.add(body);
        } else {
            HIDDEN.remove(body);
            ModelMode.selectModel(body);
        }
    }

    // ============ 碰撞 ============

    /**
     * 该结构是否处于「无碰撞」。
     *
     * <p><b>直接读手杖客户端状态</b>（{@code noCollision} 集合由服务端的
     * {@code SyncNoCollisionPayload} 维护），而不是自己再存一份镜像 ——
     * V 键、树状图、快照都可能改这个状态，两份镜像早晚会对不上。
     */
    public static boolean noCollision(final UUID body) {
        return com.ovo.sablestopnow.client.StaffEnhanceClientHandler.getNoCollision().contains(body);
    }

    public static void setNoCollision(final UUID body, final boolean noCollision) {
        // 只发包：服务端是权威，它广播回来之后上面的读取自然就变了
        VeilPacketManager.server().sendPacket(
                new StaffEnhanceNetworking.SetNoCollisionPayload(noCollision, List.of(body)));
    }

    // ============ 锁定 ============

    /** 该结构是否被物理冻结（同样直接读手杖客户端状态，见 {@link #noCollision}）。 */
    public static boolean locked(final UUID body) {
        return com.ovo.sablestopnow.client.StaffEnhanceClientHandler.isBodyLocked(body);
    }

    public static void setLocked(final UUID body, final boolean value) {
        VeilPacketManager.server().sendPacket(
                new StaffEnhanceNetworking.SetLocksPayload(value, List.of(body)));
    }

    /** 断线时清空（隐藏是纯客户端的，碰撞/锁定的状态归手杖那边管）。 */
    public static void clear() {
        HIDDEN.clear();
    }
}
