package com.ovo.sablestopnow.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.ovo.sablestopnow.PhysicsGhosts;
import dev.ryanhcode.sable.api.math.LevelReusedVectors;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.ArrayList;
import java.util.List;

/**
 * 「正在拖拽的物理结构不再碰撞拖拽者」—— 双端 Mixin。
 *
 * <p>事实依据（见 docs/player-sublevel-collision-hook.md，均以源码 + 运行期 jar 字节码核实）：
 * <ul>
 *   <li>实体 ↔ 物理结构的碰撞只有唯一漏斗 {@code SubLevelEntityCollision.collide(...)}
 *       （由 Sable 自己对 {@code Entity.move} 的 @Redirect 调用），sub-level 方块对原版碰撞不可见；</li>
 *   <li>玩家的真实碰撞在<b>客户端</b>算：服务端 {@code ServerPlayer} 分支直接早退、只做“假地面”，
 *       位置由客户端上报。所以纯服务端拦截无效，必须在 common 侧生效并同步状态；</li>
 *   <li>没有现成的「按体关闭实体碰撞」API，所以在 broad-phase 结果上做一次过滤最干净：
 *       只影响实体碰撞，不影响手杖射线、挖方块、getOnPos、寻路等。</li>
 * </ul>
 *
 * <p>刻意<b>不</b>动 {@code getTrackingSubLevel}（被寻路/getOnPos/骑乘继承等大量系统读取），
 * 也<b>不</b>全局过滤 {@code Sable.HELPER.getAllIntersecting}（会连手杖选取一起失效）。
 */
@Mixin(value = dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision.class, remap = false)
public class SubLevelEntityCollisionGhostMixin {

    @ModifyExpressionValue(
            method = "collide",
            require = 1,
            remap = false,
            at = @At(value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/ActiveSableCompanion;getAllIntersecting(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/companion/math/BoundingBox3dc;)Ljava/lang/Iterable;"))
    private static Iterable<SubLevel> sablestopnow$skipDraggedForDragger(final Iterable<SubLevel> original,
                                                                        final Entity entity,
                                                                        final Vec3 collisionMotion,
                                                                        final Vec3 velocityMotion,
                                                                        final LevelReusedVectors sink) {
        if (PhysicsGhosts.isEmpty() || !(entity instanceof final Player player)) {
            return original;
        }
        final var playerId = player.getUUID();

        // 先探测一遍：没有被幽灵化的结构就直接返回原集合（零额外分配）
        boolean any = false;
        for (final SubLevel sub : original) {
            if (PhysicsGhosts.ignores(sub.getUniqueId(), playerId)) {
                any = true;
                break;
            }
        }
        if (!any) {
            return original;
        }
        final List<SubLevel> kept = new ArrayList<>(4);
        for (final SubLevel sub : original) {
            if (!PhysicsGhosts.ignores(sub.getUniqueId(), playerId)) {
                kept.add(sub);
            }
        }
        return kept;
    }
}
