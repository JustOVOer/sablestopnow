package com.ovo.sablestopnow.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinhelpers.block_outline_render.SubLevelCamera;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 让「方块描边（碰撞箱边框）」在缩放后的物理体上也落到正确的位置。
 *
 * <p><b>上游 Sable 的变换顺序缺陷（已用 2.0.4 字节码核实）</b>：
 * {@code dev.ryanhcode.sable.neoforge.mixin.block_outline_render.LevelRendererMixin}（priority 2000）是 Sable
 * 渲染链路里<b>唯一</b>会读 {@code pose.scale()} 的地方，但它在「相机相对平移」<b>之后</b>才 scale：
 * <pre>
 *   T(pos − cam) · R · T(plotCam − rotPoint) · S
 * </pre>
 * 而逻辑变换（碰撞/拾取/我们补好的模型矩阵）是
 * <pre>
 *   T(pos − cam) · R · S · T(plotCam − rotPoint)
 * </pre>
 * 于是描边被平移了 {@code R·(I − S)·(plotCam − rotPoint)} —— ×0.5 时这个量正好是
 * 「相机 − 物理体」，描边会贴到相机上（看起来巨大且位置完全不对）；×2 时被推到物理体背面。
 *
 * <p><b>修法</b>（与参考实现 {@code depends/sable-scale-main} 的
 * {@code LevelRendererOutlineScaleMixin} 完全同构）：本 Mixin 也包住
 * {@code ClientHooks.onDrawHighlight} 这次调用，但 priority 1000 &lt; 2000，MixinExtras 会把我们串在
 * Sable 的 wrap <b>里面</b>：Sable 先压好它那套（顺序错的）变换，再调用我们；我们在调用真正的原方法之前
 * 追加平移 {@code T((I − S⁻¹)·(plotCam − rotPoint))}，代数上正好把栈上的矩阵补成正确形式：
 * <pre>
 *   T(A)·R·T(u)·S · T((I − S⁻¹)u) = T(A)·R·S·T(u)     （u = plotCam − rotPoint）
 * </pre>
 *
 * <p>双重保险：如果 MixinExtras 的链式顺序与预期相反（我们在外层），那么拿到的 {@code camera} 就是真实相机
 * 而不是 Sable 的 {@link SubLevelCamera}，下面的 {@code instanceof SubLevelCamera} 判定会直接放行、不做任何
 * 补偿 —— 宁可没修好也不会把描边推得更歪。
 *
 * <p>scale 为 1 时零开销直接放行；世界方块的描边（非子物理体）同样不受影响。
 * 若 Sable 上游修正了这个顺序，本 Mixin 应整体删除。
 */
@Mixin(value = LevelRenderer.class, priority = 1000)
public abstract class LevelRendererOutlineScaleMixin {

    @WrapOperation(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforge/client/ClientHooks;onDrawHighlight(Lnet/minecraft/client/renderer/LevelRenderer;Lnet/minecraft/client/Camera;Lnet/minecraft/world/phys/HitResult;Lnet/minecraft/client/DeltaTracker;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)Z"))
    private boolean sablestopnow$fixScaledOutline(final LevelRenderer context, final Camera camera, final HitResult target,
                                                  final DeltaTracker deltaTracker, final PoseStack poseStack,
                                                  final MultiBufferSource bufferSource, final Operation<Boolean> original) {
        // 只有 Sable 已经把子物理体的变换压上栈时（camera 被换成 SubLevelCamera）才补偿。
        if (camera instanceof SubLevelCamera && target instanceof final BlockHitResult blockTarget) {
            final var level = Minecraft.getInstance().level;
            if (level != null && Sable.HELPER.getContaining(level, blockTarget.getBlockPos()) instanceof final ClientSubLevel subLevel) {
                final Pose3dc pose = subLevel.renderPose();
                final Vector3dc scale = pose.scale();
                final double sx = scale.x();
                final double sy = scale.y();
                final double sz = scale.z();
                final boolean scaled = Math.abs(sx - 1.0) > 1.0e-3
                        || Math.abs(sy - 1.0) > 1.0e-3
                        || Math.abs(sz - 1.0) > 1.0e-3;
                // 退化缩放（任一轴 ≈ 0）无法求逆，跳过补偿（此时描边本来就是不可见的点/面）。
                if (scaled && Math.abs(sx) > 1.0e-4 && Math.abs(sy) > 1.0e-4 && Math.abs(sz) > 1.0e-4) {
                    // camera 是 Sable 的“plot 空间相机”：plotCam = rotPoint + S⁻¹·R⁻¹·(realCam − pos)
                    final Vec3 plotCam = camera.getPosition();
                    final Vector3dc rotationPoint = pose.rotationPoint();
                    poseStack.translate(
                            (float) ((plotCam.x - rotationPoint.x()) * (1.0 - 1.0 / sx)),
                            (float) ((plotCam.y - rotationPoint.y()) * (1.0 - 1.0 / sy)),
                            (float) ((plotCam.z - rotationPoint.z()) * (1.0 - 1.0 / sz)));
                }
            }
        }

        return original.call(context, camera, target, deltaTracker, poseStack, bufferSource);
    }
}
