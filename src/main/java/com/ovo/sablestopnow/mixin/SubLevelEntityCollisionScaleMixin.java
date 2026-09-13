package com.ovo.sablestopnow.mixin;

import dev.ryanhcode.sable.api.math.OrientedBoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 让「实体 ↔ 物理结构」碰撞遵守位姿的 <b>scale</b>（移植自 {@code depends/sable-scale-main} 的同名 Mixin）。
 *
 * <p>原生 {@code SubLevelEntityCollision} 给每个碰撞盒建一个世界空间 OBB：盒<b>中心</b>经过
 * {@code subLevelPose.transformPosition}（是 scale-aware 的），但盒<b>尺寸</b>直接抄 plot 空间的体素形状
 * （{@code box.size(cubeOBB.getDimensions())}）—— 于是在 ×0.5 时每个方块仍以"满尺寸立方体"推实体，
 * 只是中心缩到了正确位置，相邻的幽灵盒还会互相重叠（表现为"实体被奇怪地推来推去"，也就是
 * 用户看到的"按缩放前的大小碰撞"）。这里把两处 SAT 调用（{@code collide} 主循环与
 * {@code hasCollision} 里的上台阶探测）在测试前把立方体尺寸乘上缩放即可。</p>
 *
 * <p>当前结构的缩放是通过包住循环里唯一的 {@code subLevel.logicalPose()} 调用捕获并塞进 ThreadLocal 的
 * —— <b>不用</b> MixinExtras 的 {@code @Local}：编译后的 {@code collide} 在 SAT 指令处有两个 {@code SubLevel}
 * 局部变量，无法区分（参考实现实测会导致整个注入器失败并崩服务端）。{@code hasCollision} 只会在该循环内
 * （经 {@code tryStepUp}）被调用，所以捕获到的缩放一定是对的。</p>
 */
@Mixin(value = SubLevelEntityCollision.class, remap = false)
public abstract class SubLevelEntityCollisionScaleMixin {

    @Unique
    private static final ThreadLocal<Vector3d> sablestopnow$currentScale =
            ThreadLocal.withInitial(() -> new Vector3d(1.0));

    @Redirect(
            method = "collide",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/sublevel/SubLevel;logicalPose()Ldev/ryanhcode/sable/companion/math/Pose3d;"))
    private static Pose3d sablestopnow$captureScale(final SubLevel subLevel) {
        final Pose3d pose = subLevel.logicalPose();
        sablestopnow$currentScale.get().set(pose.scale());
        return pose;
    }

    @Redirect(
            method = {"collide", "hasCollision"},
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/api/math/OrientedBoundingBox3d;sat(Ldev/ryanhcode/sable/api/math/OrientedBoundingBox3d;Ldev/ryanhcode/sable/api/math/OrientedBoundingBox3d;Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"))
    private static Vector3d sablestopnow$scaledSat(final OrientedBoundingBox3d entityObb,
                                                   final OrientedBoundingBox3d cubeObb,
                                                   final Vector3d mtv) {
        final Vector3d scale = sablestopnow$currentScale.get();
        if (scale.x != 1.0 || scale.y != 1.0 || scale.z != 1.0) {
            cubeObb.getDimensions().mul(scale.x, scale.y, scale.z);
        }
        return OrientedBoundingBox3d.sat(entityObb, cubeObb, mtv);
    }
}
