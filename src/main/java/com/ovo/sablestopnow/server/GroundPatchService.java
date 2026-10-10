package com.ovo.sablestopnow.server;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.physics.SablePhysicsExt;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * **精细地面碰撞箱**（"地面补丁"）—— 用户方向 ✓ 2026-10-03。
 *
 * <h2>要解决什么</h2>
 * 曲面实体与**地面**接触时，对侧是"**巨大而极薄**的箱" ✗ ⇒ 落进 Rapier 官方写明的
 * EPA 退化区（接触法线算错 ✗）。而"**结构 ↔ 结构**"正常 ✓（用户实测）——
 * 因为两边都是**正常尺寸的小盒** ✓。
 *
 * <h2>⚠ 预算必须小（2026-10-03 实测教训）</h2>
 * 第一版给的是 水平 8 / 向下 4 / 512 格 / 每格 16 盒 ✗ ⇒ 最坏 **8192 个碰撞体** ✗，
 * 而它**每 20 tick 重建一次** ✗ ⇒ 日志里 MSPT 出现 **347–410 ms** ✗✗
 * （同局后面回落到 76–86 ms ✓）。⇒ 现在砍到 水平 4 / 向下 2 / 64 格 / 每格 4 盒 ✓
 * （最坏 **256 个盒** ✓，是原来的 1/32 ✓）。**一次只动这一个量** ✓。
 *
 * <h2>做法</h2>
 * 取实体下方的世界方块（只保留碰撞形状非空者 ✓），把每个方块的 {@link VoxelShape}
 * 拆成盒子 ✓，交给原生 {@link SablePhysicsExt#createStaticBoxes} 建成
 * **独立静态刚体** ✓（不动 ✓ 无质量 ✓ 不经过 Sable 那套易碎的 hook 机制 ✓）。
 * 每轮先拆旧再建新 ✓（跟随移动 ✓ 不泄漏 ✓）。
 */
public final class GroundPatchService {

    /** 水平范围（格，围绕实体中心 ✓）—— 第一版 8 太贵 ✗ 现改 4 ✓。 */
    public static final double PATCH_RADIUS = 4.0;
    /** 向下深度（格）—— 第一版 4 太贵 ✗ 现改 2 ✓。 */
    public static final double PATCH_DEPTH = 2.0;
    /** 补丁摩擦（与世界方块同量级 ✓）。 */
    private static final double PATCH_FRICTION = 0.6;
    /** 总格数上限 ✓ —— 第一版 512 太贵 ✗ 现改 64 ✓。 */
    private static final int MAX_CELLS = 64;
    /** 每格盒子数上限（超出 ⇒ 该格退化为整格盒 ✓）—— 第一版 16 太贵 ✗ 现改 4 ✓。 */
    private static final int MAX_BOXES_PER_CELL = 4;

    /** 每个实体当前挂着的补丁刚体 id ✓（下一轮刷新时先拆它 ✓）。 */
    private static final Map<UUID, Integer> PATCHES = new HashMap<>();
    /** 已经打过日志的实体 ✓（否则每 20 tick 刷屏 ✗）。 */
    private static final Set<UUID> LOGGED = new HashSet<>();

    private GroundPatchService() {
    }

    /**
     * 每轮（20 tick ✓）为某个实体刷新地面补丁 ✓：先拆旧的 ✓ 再按当前位置重建 ✓。
     *
     * <p>⚠ 必须**不受自愈的一次性记录限制** ✗ —— 补丁要跟着实体走 ✓，
     * 所以调用方要把它放在"已补过就 continue"的**前面** ✓。
     *
     * @param level 服务端世界（取 scene 与世界方块 ✓）
     * @param body  实体 UUID（做补丁的键 ✓）
     * @param sub   它对应的子结构（取世界坐标 ✓）
     */
    /**
     * **对照实验开关** ✓（2026-10-03）：
     * {@code false} ⇒ 补丁完全不建 ✓ —— 用来判定"**有时悬在空中好像被托住了**"✗
     * 到底是不是本补丁造成的（若是 ⇒ 专修拆除/截断逻辑 ✓；若否 ⇒ 是别的东西 ✓）。
     *
     * <p>⚠ 一次只动这一个量 ✗（圆角、凸包路、inset 全都不动 ✓）。
     */
    public static final boolean PATCH_ENABLED = false;

    public static void update(final ServerLevel level, final UUID body, final ServerSubLevel sub) {
        if (!PATCH_ENABLED || level == null || body == null || sub == null) {
            return;
        }
        final long scene = Rapier3D.getSceneHandle(level);
        final var p = sub.logicalPose().position();

        final Integer old = PATCHES.remove(body);
        if (old != null && scene != 0L) {
            SablePhysicsExt.removeStaticBoxes(scene, old);
        }
        if (scene == 0L) {
            return;
        }

        final double[] boxes = collectBoxes(level, p.x(), p.y(), p.z());
        if (boxes.length < 6) {
            if (LOGGED.add(body)) {
                SablestopNow.LOGGER.info(
                        "[sablestopnow] 地面补丁: body={} 脚下没有可碰方块 ⇒ 不铺 ✓（pos={},{},{}）",
                        body, (int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()));
            }
            return;
        }
        final int id = build(scene, boxes);
        if (id >= 0 && LOGGED.add(body)) {
            // ★ 这一行就是"补丁到底有没有建起来"的证据 ✓（以前没有它 ✗ 只能靠 MSPT 猜 ✗）
            SablestopNow.LOGGER.info(
                    "[sablestopnow] 地面补丁: body={} 盒数={} id={} pos={},{},{}"
                            + "（水平 {}格/向下 {}格/预算 {}格×{}盒 ✓）",
                    body, boxes.length / 6, id,
                    (int) Math.floor(p.x()), (int) Math.floor(p.y()), (int) Math.floor(p.z()),
                    (int) PATCH_RADIUS, (int) PATCH_DEPTH, MAX_CELLS, MAX_BOXES_PER_CELL);
        }
    }

    /** 某实体被移除/世界卸载时调 ✓（幂等 ✓）。 */
    public static void clear(final ServerLevel level, final UUID body) {
        LOGGED.remove(body);
        final Integer old = PATCHES.remove(body);
        if (old == null || level == null) {
            return;
        }
        final long scene = Rapier3D.getSceneHandle(level);
        if (scene != 0L) {
            SablePhysicsExt.removeStaticBoxes(scene, old);
        }
    }

    /** 全部拆掉 ✓（服务器停止时调 ✓）。 */
    public static void clearAll(final ServerLevel level) {
        final long scene = level == null ? 0L : Rapier3D.getSceneHandle(level);
        if (scene != 0L) {
            for (final Integer id : PATCHES.values()) {
                SablePhysicsExt.removeStaticBoxes(scene, id);
            }
        }
        PATCHES.clear();
        LOGGED.clear();
    }

    /**
     * 收集 {@code [cx, cy, cz]} 周围（水平 {@link #PATCH_RADIUS}、向下 {@link #PATCH_DEPTH}）
     * 的世界方块，展开成扁平的世界坐标盒数组（原生 {@code createStaticBoxes} 的入参格式 ✓）。
     *
     * @return 盒数组；没有任何可碰方块时返回长度 0 的数组 ✓（调用方据此跳过 ✓）
     */
    public static double[] collectBoxes(final ServerLevel level,
                                        final double cx, final double cy, final double cz) {
        final int minX = (int) Math.floor(cx - PATCH_RADIUS);
        final int maxX = (int) Math.floor(cx + PATCH_RADIUS);
        final int minY = (int) Math.floor(cy - PATCH_DEPTH);
        final int maxY = (int) Math.floor(cy + 1.0);
        final int minZ = (int) Math.floor(cz - PATCH_RADIUS);
        final int maxZ = (int) Math.floor(cz + PATCH_RADIUS);

        final List<double[]> out = new ArrayList<>();
        final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int cells = 0;

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (cells >= MAX_CELLS) {
                        return flatten(out);
                    }
                    cursor.set(x, y, z);
                    final BlockState state = level.getBlockState(cursor);
                    // 空气 / 无碰撞方块直接跳过 ✓（这一步是预算的第一道闸 ✓）
                    if (state.isAir()) {
                        continue;
                    }
                    final VoxelShape shape = state.getCollisionShape(level, cursor);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    cells++;
                    final AABB[] parts = shape.toAabbs().toArray(new AABB[0]);
                    if (parts.length == 0 || parts.length > MAX_BOXES_PER_CELL) {
                        // 降级：盒子太碎 ⇒ 直接用整格（仍然远精细于"巨大极薄的板" ✓）
                        out.add(new double[]{x, y, z, x + 1.0, y + 1.0, z + 1.0});
                        continue;
                    }
                    for (final AABB a : parts) {
                        out.add(new double[]{
                                x + a.minX, y + a.minY, z + a.minZ,
                                x + a.maxX, y + a.maxY, z + a.maxZ,
                        });
                    }
                }
            }
        }
        return flatten(out);
    }

    /**
     * 把 {@link #collectBoxes} 收集到的盒子建成一个**独立静态刚体** ✓。
     *
     * @param scene 物理场景句柄（{@code Rapier3D.getSceneHandle(level)}）
     * @return 刚体 id（{@code < 0} 表示失败或无需补丁 ✓）
     */
    public static int build(final long scene, final double[] boxes) {
        if (boxes.length < 6) {
            return -1;
        }
        final int id = Rapier3D.nextBodyID();
        final long r = SablePhysicsExt.createStaticBoxes(scene, id, boxes, PATCH_FRICTION);
        return r < 0 ? -1 : id;
    }

    private static double[] flatten(final List<double[]> list) {
        final double[] out = new double[list.size() * 6];
        int i = 0;
        for (final double[] b : list) {
            System.arraycopy(b, 0, out, i, 6);
            i += 6;
        }
        return out;
    }
}
