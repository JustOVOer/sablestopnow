package com.ovo.sablestopnow.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.render.SubLevelRenderData;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 让物理结构的缩放真正“看得见”（vanilla 区块渲染路径）。
 *
 * <p>事实（源码核实）：{@code VanillaChunkedSubLevelRenderData.renderChunkedSubLevel} 构造模型矩阵时
 * 只做 {@code translate(renderPos − renderCOR − cam) + rotate(renderRot)}，<b>完全没有 pose.scale</b>；
 * 而 Sable 自己的方块碰撞箱描边（{@code neoforge/mixin/block_outline_render/LevelRendererMixin}）却有
 * {@code poseStack.scale(scale)} —— 这就是"描边和原版碰撞箱缩放程度不一样 / 网格不缩放"的原因。
 *
 * <p>矩阵推导（该路径把相机偏移放进 CHUNK_OFFSET uniform，矩阵平移列恒为 0）：
 * <pre>
 *   管线： p_cam = modelView · transform · (q + R⁻¹·(pos − cam − renderCOR)) , q = v − origin
 *   期望： p_cam = pos − cam + R·S·(v − rotationPoint)
 *   ⇒ 整块重写 transform = T((1 − s)·(pos − cam)) · R · S
 * </pre>
 * 这样当 s = 1 时与原实现等价（平移 0、纯旋转），s ≠ 1 时才是绕“旋转中心”的等比缩放。
 */
@Mixin(value = VanillaChunkedSubLevelRenderData.class, remap = false)
public class VanillaChunkedSubLevelRenderScaleMixin {

    @ModifyArg(
            method = "renderChunkedSubLevel",
            require = 1,
            remap = false,
            at = @At(value = "INVOKE",
                    target = "Lorg/joml/Matrix4f;mul(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4f;)Lorg/joml/Matrix4f;"))
    private Matrix4fc sablestopnow$applyPoseScale(final Matrix4fc transformArgument,
                                                  @Local(argsOnly = true, ordinal = 0) final double camX,
                                                  @Local(argsOnly = true, ordinal = 1) final double camY,
                                                  @Local(argsOnly = true, ordinal = 2) final double camZ) {
        if (!(transformArgument instanceof final Matrix4f matrix)) {
            return transformArgument;
        }
        final Pose3dc pose = ((SubLevelRenderData) (Object) this).getSubLevel().renderPose();
        final Vector3dc scale = pose.scale();
        final float sx = (float) scale.x();
        final float sy = (float) scale.y();
        final float sz = (float) scale.z();
        if (Math.abs(sx - 1.0f) < 1.0e-3f && Math.abs(sy - 1.0f) < 1.0e-3f && Math.abs(sz - 1.0f) < 1.0e-3f) {
            return matrix;
        }
        // ⚠ 这条路径的矩阵平移列恒为 0：相机偏移被放进每区块的 CHUNK_OFFSET uniform
        // （sectionPos − origin + R⁻¹·(renderPos − cam)）。所以必须整块重写矩阵，不能只往平移列加东西。
        // 推导见类注释：transform = T((1−s)·(pos − cam)) · R · S
        final Vector3dc pos = pose.position();
        matrix.identity()
                .translate((float) ((1.0 - sx) * (pos.x() - camX)),
                        (float) ((1.0 - sy) * (pos.y() - camY)),
                        (float) ((1.0 - sz) * (pos.z() - camZ)))
                .rotate(new org.joml.Quaternionf(pose.orientation()))
                .scale(sx, sy, sz);
        return matrix;
    }
}
