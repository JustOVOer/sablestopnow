package com.ovo.sablestopnow.server;

import com.ovo.sablestopnow.StaffColors;
import com.ovo.sablestopnow.network.StaffEnhanceNetworking;
import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 物理手杖增强 —— 多选“独占”登记表（服务端权威）。
 *
 * <p>规则（已与用户确认）：
 * <ul>
 *   <li>每个玩家有自己的选择列表；同一个物理结构同一时刻只能被一个玩家选中；</li>
 *   <li>他人在多选模式下尝试选中已被占用的结构会被拒绝并收到提示；</li>
 *   <li>占用在「移出队列 / 清空队列 / 退出多选 / 掉线」时立即释放；</li>
 *   <li>颜色索引按玩家分配，保证在线玩家之间不同；</li>
 *   <li>选择列表按维度广播给所有客户端（客户端自行按 64 格与维度过滤渲染）。</li>
 * </ul>
 */
public final class StaffSelectionRegistry {

    /** 维度 -> (物理结构 -> 占用者) */
    private static final Map<ResourceKey<Level>, Map<UUID, UUID>> CLAIMS = new HashMap<>();
    /** 维度 -> (玩家 -> 选中的物理结构集合) */
    private static final Map<ResourceKey<Level>, Map<UUID, Set<UUID>>> BY_PLAYER = new HashMap<>();
    /** 玩家 -> 颜色索引（会话内稳定） */
    private static final Map<UUID, Integer> COLORS = new HashMap<>();
    private static int nextColor;

    private StaffSelectionRegistry() {
    }

    // ============ 查询 ============

    /** 该物理结构当前的占用者（没有则 null）。 */
    public static synchronized UUID ownerOf(final ResourceKey<Level> dimension, final UUID subLevel) {
        final Map<UUID, UUID> claims = CLAIMS.get(dimension);
        return claims == null ? null : claims.get(subLevel);
    }

    public static synchronized int colorOf(final UUID player) {
        return COLORS.computeIfAbsent(player, k -> nextColor++ % StaffColors.size());
    }

    /** 该维度所有玩家的选择快照（供 S2C 广播）。 */
    public static synchronized List<StaffEnhanceNetworking.SelectionEntry> snapshot(final ResourceKey<Level> dimension) {
        final List<StaffEnhanceNetworking.SelectionEntry> out = new ArrayList<>();
        final Map<UUID, Set<UUID>> perPlayer = BY_PLAYER.get(dimension);
        if (perPlayer == null) {
            return out;
        }
        for (final Map.Entry<UUID, Set<UUID>> entry : perPlayer.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            out.add(new StaffEnhanceNetworking.SelectionEntry(entry.getKey(), colorOf(entry.getKey()), new ArrayList<>(entry.getValue())));
        }
        return out;
    }

    // ============ 改动 ============

    /** 一次 claim 的结果：成功 / 被谁占用（显示名，可能来自“选中的玩家”或“所有者”）。 */
    public record Result(List<UUID> granted, Map<UUID, String> denied) {
    }

    /** 玩家把一组物理结构加入自己的选择列表；已被他人选中、或已被他人拥有的会被拒绝。 */
    public static synchronized Result add(final ServerLevel level, final UUID player, final Collection<UUID> ids) {
        final ResourceKey<Level> dimension = level.dimension();
        final Map<UUID, UUID> claims = CLAIMS.computeIfAbsent(dimension, k -> new HashMap<>());
        final Map<UUID, Set<UUID>> perPlayer = BY_PLAYER.computeIfAbsent(dimension, k -> new HashMap<>());
        final Set<UUID> mine = perPlayer.computeIfAbsent(player, k -> new LinkedHashSet<>());
        final StaffOwnershipData ownership = StaffOwnershipData.get(level);

        final List<UUID> granted = new ArrayList<>();
        final Map<UUID, String> denied = new HashMap<>();
        for (final UUID id : ids) {
            final UUID owner = claims.get(id);
            if (owner != null && !owner.equals(player)) {
                denied.put(id, playerName(level, owner));
                continue;
            }
            if (ownership.isOwnedByOther(id, player)) {
                denied.put(id, ownership.ownerNameOf(id));
                continue;
            }
            claims.put(id, player);
            mine.add(id);
            granted.add(id);
        }
        return new Result(granted, denied);
    }

    /** 在线玩家名（离线/找不到时退回 UUID 文本）。 */
    private static String playerName(final ServerLevel level, final UUID player) {
        final ServerPlayer online = level.getServer() == null ? null
                : level.getServer().getPlayerList().getPlayer(player);
        return online != null ? online.getGameProfile().getName() : player.toString();
    }

    /** 玩家把一组物理结构移出自己的选择列表（释放占用）。 */
    public static synchronized void remove(final ResourceKey<Level> dimension, final UUID player, final Collection<UUID> ids) {
        final Map<UUID, UUID> claims = CLAIMS.get(dimension);
        final Map<UUID, Set<UUID>> perPlayer = BY_PLAYER.get(dimension);
        if (perPlayer == null) {
            return;
        }
        final Set<UUID> mine = perPlayer.get(player);
        if (mine == null) {
            return;
        }
        for (final UUID id : ids) {
            mine.remove(id);
            if (claims != null && player.equals(claims.get(id))) {
                claims.remove(id);
            }
        }
    }

    /** 玩家清空自己的选择列表（释放全部占用）。 */
    public static synchronized void clear(final ResourceKey<Level> dimension, final UUID player) {
        remove(dimension, player, new ArrayList<>(selectionOf(dimension, player)));
    }

    public static synchronized Set<UUID> selectionOf(final ResourceKey<Level> dimension, final UUID player) {
        final Map<UUID, Set<UUID>> perPlayer = BY_PLAYER.get(dimension);
        if (perPlayer == null) {
            return Set.of();
        }
        final Set<UUID> mine = perPlayer.get(player);
        return mine == null ? Set.of() : new LinkedHashSet<>(mine);
    }

    /** 玩家掉线/退出多选：释放他在所有维度的占用，返回受影响的维度。 */
    public static synchronized Set<ResourceKey<Level>> releaseAll(final UUID player) {
        final Set<ResourceKey<Level>> affected = new HashSet<>();
        for (final Map.Entry<ResourceKey<Level>, Map<UUID, Set<UUID>>> entry : BY_PLAYER.entrySet()) {
            final Set<UUID> mine = entry.getValue().get(player);
            if (mine == null || mine.isEmpty()) {
                continue;
            }
            final Map<UUID, UUID> claims = CLAIMS.get(entry.getKey());
            for (final UUID id : mine) {
                if (claims != null && player.equals(claims.get(id))) {
                    claims.remove(id);
                }
            }
            mine.clear();
            affected.add(entry.getKey());
        }
        return affected;
    }

    /** 服务端重启/世界卸载时清空运行时占用（占用不落盘）。 */
    public static synchronized void clearAll() {
        CLAIMS.clear();
        BY_PLAYER.clear();
    }

    // ============ 广播 ============

    /** 把某维度的选择快照广播给所有玩家（客户端按维度过滤）。 */
    public static void broadcast(final MinecraftServer server, final ResourceKey<Level> dimension) {
        final List<StaffEnhanceNetworking.SelectionEntry> snapshot = snapshot(dimension);
        VeilPacketManager.all(server).sendPacket(new StaffEnhanceNetworking.SyncSelectionsPayload(
                dimension.location(), snapshot));
    }

    /** 释放占用后按受影响的维度逐个广播。 */
    public static void broadcast(final MinecraftServer server, final Collection<ResourceKey<Level>> dimensions) {
        for (final ResourceKey<Level> dimension : dimensions) {
            broadcast(server, dimension);
        }
    }

    /** 玩家加入时补发所有维度的选择快照。 */
    public static void sendAllTo(final ServerPlayer player) {
        final MinecraftServer server = player.server;
        if (server == null) {
            return;
        }
        for (final ServerLevel level : server.getAllLevels()) {
            VeilPacketManager.player(player).sendPacket(new StaffEnhanceNetworking.SyncSelectionsPayload(
                    level.dimension().location(), snapshot(level.dimension())));
        }
    }
}
