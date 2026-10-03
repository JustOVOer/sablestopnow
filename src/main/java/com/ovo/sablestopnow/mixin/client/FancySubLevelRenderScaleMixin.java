package com.ovo.sablestopnow.mixin.client;

import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.render.dispatcher.FancySubLevelRenderDispatcher;
import dev.ryanhcode.sable.sublevel.render.fancy.FancySubLevelRenderData;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.joml.Vector3ic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 让物理结构的缩放真正“看得见”。
 *
 * <p>事实（源码核实）：Flywheel（fancy）渲染路径构造模型矩阵时只做 {@code translate + rotate}，
 * <b>完全没有 pose.scale</b>；只有 vanilla 路径的 {@code SubLevelRenderData.getTransformation} 会乘 scale。
 * 本 Mixin 在把矩阵交给 {@code SableTransform} uniform 之前补上缩放。
 *
 * <p>矩阵推导（fancy 路径把“旋转中心”折进了平移里）：
 * <pre>
 *   现状： p_cam = t + R·q          , t = pos − R·(rotationPoint − origin) − camera , q = p_plot − origin
 *   目标： p_cam = pos − camera + R·S·(p_plot − rotationPoint)
 *   差值 ⇒ 需要额外左乘平移 w = −R·S·(rotationPoint − origin)
 * </pre>
 * 所以顺序是「先给矩阵右乘 S（缩放基向量），再把 w 加进平移列」。
 */
@Mixin(value = FancySubLevelRenderDispatcher.class, remap = false)
public class FancySubLevelRenderScaleMixin {

    /**
     * 「隐藏」在 fancy 路径上的实现。
     *
     * <p>这条路径的入口收的是<b>一批</b>物理结构（{@code Iterable<ClientSubLevel>}），
     * 所以不能像另外两条单结构路径那样直接 cancel —— 那会把整层都画不出来。
     * 正确做法是把这一批里被隐藏的过滤掉再交回去，三个入口都要过滤
     * （方块层 / 附加层 / 方块实体层），否则隐藏会表现为「方块没了但方块实体还飘着」。
     *
     * <p>{@code require = 0} 与其它 mixin 同理：宁可隐藏失效，也不要让游戏因 Mixin 应用失败而崩。
     */
    @org.spongepowered.asm.mixin.injection.ModifyVariable(
            method = "renderSectionLayer", at = @At("HEAD"), argsOnly = true, index = 0, require = 0, remap = false)
    private Iterable<dev.ryanhcode.sable.sublevel.ClientSubLevel> sablestopnow$filterHiddenSections(
            final Iterable<dev.ryanhcode.sable.sublevel.ClientSubLevel> subLevels) {
        return filterHidden(subLevels);
    }

    @org.spongepowered.asm.mixin.injection.ModifyVariable(
            method = "renderAfterSections", at = @At("HEAD"), argsOnly = true, index = 0, require = 0, remap = false)
    private Iterable<dev.ryanhcode.sable.sublevel.ClientSubLevel> sablestopnow$filterHiddenAfterSections(
            final Iterable<dev.ryanhcode.sable.sublevel.ClientSubLevel> subLevels) {
        return filterHidden(subLevels);
    }

    @org.spongepowered.asm.mixin.injection.ModifyVariable(
            method = "renderBlockEntities", at = @At("HEAD"), argsOnly = true, index = 0, require = 0, remap = false)
    private Iterable<dev.ryanhcode.sable.sublevel.ClientSubLevel> sablestopnow$filterHiddenBlockEntities(
            final Iterable<dev.ryanhcode.sable.sublevel.ClientSubLevel> subLevels) {
        return filterHidden(subLevels);
    }

    /**
     * 没有隐藏项时原样返回（常见路径零分配）。
     *
     * <p>发现第一个隐藏项时才建列表，并把「已经遍历过的那几个」补进去 ——
     * 这里刻意不先物化整个 Iterable：热路径上（每帧、每层、每个结构）多一次 List 分配是能省则省的。
     */
    private static Iterable<dev.ryanhcode.sable.sublevel.ClientSubLevel> filterHidden(
            final Iterable<dev.ryanhcode.sable.sublevel.ClientSubLevel> subLevels) {
        java.util.List<dev.ryanhcode.sable.sublevel.ClientSubLevel> out = null;
        for (final dev.ryanhcode.sable.sublevel.ClientSubLevel sub : subLevels) {
            if (com.ovo.sablestopnow.client.model.ModelProperties.hidden(sub.getUniqueId())) {
                if (out == null) {
                    out = new java.util.ArrayList<>();
                    for (final dev.ryanhcode.sable.sublevel.ClientSubLevel earlier : subLevels) {
                        if (earlier == sub) {
                            break;
                        }
                        out.add(earlier);
                    }
                }
                continue;
            }
            if (out != null) {
                out.add(sub);
            }
        }
        return out == null ? subLevels : out;
    }

    @ModifyArg(
            method = "renderSectionLayer",
            require = 1,
            remap = false,
            at = @At(value = "INVOKE",
                    target = "Lfoundry/veil/api/client/render/shader/uniform/ShaderUniform;setMatrix(Lorg/joml/Matrix4fc;)V"))
    private Matrix4fc sablestopnow$applyPoseScale(final Matrix4fc matrixArgument,
                                                  @Local final FancySubLevelRenderData renderData,
                                                  @Local final Pose3dc renderPose) {
        if (!(matrixArgument instanceof final Matrix4f matrix) || renderData == null || renderPose == null) {
            return matrixArgument;
        }
        final Vector3dc scale = renderPose.scale();
        final float sx = (float) scale.x();
        final float sy = (float) scale.y();
        final float sz = (float) scale.z();
        if (Math.abs(sx - 1.0f) < 1.0e-3f && Math.abs(sy - 1.0f) < 1.0e-3f && Math.abs(sz - 1.0f) < 1.0e-3f) {
            return matrix;
        }
        // 与 dispatcher 里 renderCOR 的定义保持一致：cor = R·(rotationPoint − plotOrigin)
        final Vector3ic chunkOrigin = renderData.getChunkOrigin();
        final Vector3d originVec = new Vector3d(chunkOrigin.x(), chunkOrigin.y(), chunkOrigin.z());
        final Vector3d cor = renderPose.orientation().transform(
                new Vector3d(renderPose.rotationPoint()).sub(originVec));
        // 需要补的左乘平移 w = (1 − s)·R·(rotationPoint − origin) = (1 − s)·cor
        final Vector3d w = cor.mul(1.0 - sx);

        matrix.scale(sx, sy, sz);
        matrix.m30(matrix.m30() + (float) w.x);
        matrix.m31(matrix.m31() + (float) w.y);
        matrix.m32(matrix.m32() + (float) w.z);
        return matrix;
    }
}
