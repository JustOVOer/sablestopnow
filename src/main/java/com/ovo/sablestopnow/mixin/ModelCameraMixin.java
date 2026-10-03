package com.ovo.sablestopnow.mixin;

import com.ovo.sablestopnow.client.model.ModelCamera;
import com.ovo.sablestopnow.client.model.ModelMode;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 模型模式的相机接管。
 *
 * <p>为什么必须改 {@code Camera} 本体而不是渲染时套一层矩阵：世界渲染、区块剔除、声音衰减、
 * 天空盒都读这个相机。只改矩阵的话，画面会动而剔除/声音还在原地，转头就会看到区块闪。
 * {@code setPosition}/{@code setRotation} 是 protected，用 {@link Shadow} 拿到。
 *
 * <p>注入在 {@code setup} 的 TAIL：等原版把「跟随实体」的位姿全部算完之后再覆盖，
 * 这样不需要关心它内部的三/第一人称分支。
 */
@Mixin(Camera.class)
public abstract class ModelCameraMixin {

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    /**
     * 相机平滑必须在<b>本帧摆相机之前</b>推进。
     *
     * <p>以前在 {@code RenderLevelStageEvent} 里推进（那是摆完相机之后），于是「画面用的相机」
     * 比「投影用的相机」慢一帧 —— 一转视角标记就整体偏出去（用户实测 1/4 方块）。
     */
    @Inject(method = "setup", at = @At("HEAD"))
    private void sablestopnow$tickModelCamera(final BlockGetter level, final Entity entity, final boolean detached,
                                              final boolean mirrored, final float partialTick, final CallbackInfo ci) {
        if (!ModelMode.isOn()) {
            return;
        }
        ModelCamera.tickOnce(net.minecraft.client.Minecraft.getInstance());
    }

    @Inject(method = "setup", at = @At("TAIL"))
    private void sablestopnow$modelCamera(final BlockGetter level, final Entity entity, final boolean detached,
                                          final boolean mirrored, final float partialTick, final CallbackInfo ci) {
        if (!ModelMode.isOn() || !ModelCamera.initialized()) {
            return;
        }
        final Vec3 position = ModelCamera.position();
        this.setPosition(position.x, position.y, position.z);
        this.setRotation(ModelCamera.yaw(), ModelCamera.pitch());
    }
}
