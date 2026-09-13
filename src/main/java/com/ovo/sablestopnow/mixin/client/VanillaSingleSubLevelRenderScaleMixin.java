package com.ovo.sablestopnow.mixin.client;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaSingleSubLevelRenderData;
import net.minecraft.core.BlockPos;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 让<b>单方块物理体</b>的模型也跟着缩放 —— 这是「模型和碰撞箱边框缩放大小不一致」最直接的那条路径。
 *
 * <p><b>为什么会有这条路径</b>：Sable 用两个渲染器，{@code VanillaSubLevelRenderDispatcher.createRenderData}
 * 里 {@code isSingleBlock(subLevel)} 为真（plot 包围盒恰好一个方块）时走
 * {@link VanillaSingleSubLevelRenderData#renderSingleBlock}，其余才走
 * {@link dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData}。
 * 本模组先前只补了「区块渲染」与「fancy 调度器」两条路径，<b>单方块路径完全没补</b>：stock 的
 * {@code renderSingleBlock} 只构造 {@code T(renderCOR − cam)·R}（字节码核实：identity → translate → rotate，
 * 之后才 mul 进 PoseStack，全程不读 {@code renderPose().scale()}）。
 * 于是缩放一个单方块物理体时：描边（Sable 的 LevelRendererMixin，乘 S）和碰撞体
 * （{@code ScaledColliders} 重采样到 {@code rotPoint + S·(blockPos − rotPoint)}）都变大了，
 * 只有模型还是 ×1 —— 正是用户报的「模型和碰撞箱边框缩放大小不一致」。
 *
 * <p><b>修法</b>（与参考实现 {@code VanillaSingleSubLevelRenderDataMixin} 同构）：stock 构造的是
 * <pre>
 *   T(renderCOR − cam) · R ,  renderCOR = pos − R·pivot ,  pivot = rotPoint − singleBlockPos
 * </pre>
 * 而顶点是方块局部坐标（0..1，相对 {@code singleBlockPos}），期望结果是
 * {@code pos − cam + R·S·(o − pivot)}。所以只需在它那次 {@code Matrix4f.rotate} 之后把
 * <b>绕 pivot 的缩放夹心</b> {@code T(pivot)·S·T(−pivot)} 追加上去：
 * <pre>
 *   T(renderCOR − cam)·R·T(pivot)·S·T(−pivot)
 *     = pos − cam − R·pivot + R·pivot + R·S·(o − pivot) = pos − cam + R·S·(o − pivot)  ✓
 * </pre>
 * 这里直接改写 stock 手里那个 {@code Matrix4f}（{@code TRANSFORM}）实例，因此紧随其后的
 * {@code transform.normal(stack.last().normal())} 算出的法线矩阵天然来自打过补丁的矩阵，光照仍然正确。
 *
 * <p>scale = 1 时逐字节等价于 stock（返回未改动的矩阵）。
 */
@Mixin(value = VanillaSingleSubLevelRenderData.class, remap = false)
public abstract class VanillaSingleSubLevelRenderScaleMixin {

    @Shadow
    @Final
    private ClientSubLevel subLevel;

    @Shadow
    private BlockPos singleBlockPos;

    @Redirect(
            method = "renderSingleBlock",
            require = 1,
            remap = false,
            at = @At(value = "INVOKE",
                    target = "Lorg/joml/Matrix4f;rotate(Lorg/joml/Quaternionfc;)Lorg/joml/Matrix4f;"))
    private Matrix4f sablestopnow$applyPoseScale(final Matrix4f transform, final Quaternionfc rotation) {
        transform.rotate(rotation);

        final Pose3dc renderPose = this.subLevel.renderPose();
        final Vector3dc scale = renderPose.scale();
        final float sx = (float) scale.x();
        final float sy = (float) scale.y();
        final float sz = (float) scale.z();
        if (Math.abs(sx - 1.0f) < 1.0e-3f && Math.abs(sy - 1.0f) < 1.0e-3f && Math.abs(sz - 1.0f) < 1.0e-3f) {
            return transform;
        }

        // pivot 是「旋转点相对本方块」的偏移；stock 已经把 renderCOR 里的 pivot 折进平移。
        final Vector3d pivot = new Vector3d(renderPose.rotationPoint())
                .sub(this.singleBlockPos.getX(), this.singleBlockPos.getY(), this.singleBlockPos.getZ());
        return transform
                .translate((float) pivot.x, (float) pivot.y, (float) pivot.z)
                .scale(sx, sy, sz)
                .translate((float) -pivot.x, (float) -pivot.y, (float) -pivot.z);
    }
}
