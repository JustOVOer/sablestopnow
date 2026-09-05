package com.ovo.sablestopnow.mixin.input;

import com.llamalad7.mixinextras.sugar.Local;
import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.client.StaffEnhanceClientHandler;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 MouseHandler.onPress / onScroll / turnPlayer 上拦截鼠标。
 *
 * <p>多选模式下右键=加入/Shift+右键=移出、左/中键禁用（吞掉整次 onPress，Simulated 的
 * PhysicsStaffMouseHandler 与 vanilla 都不会再收到），从而接管原手杖交互。非增强态一律放行。
 * turnPlayer 的捕获方式与航空学 MouseHandlerMixin 一致（取 LocalPlayer.turn 前的局部量），
 * 用于整组拖拽的 TAB 旋转并取消视角转动。
 */
@Mixin(MouseHandler.class)
public class MouseHandlerStaffEnhanceMixin {

    @Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void sablestopnow$staffEnhancePress(final long windowPointer, final int button, final int action,
                                                final int modifiers, final CallbackInfo ci) {
        try {
            if (StaffEnhanceClientHandler.handleMouse(button, action, modifiers)) {
                ci.cancel();
            }
        } catch (final Throwable t) {
            // 启动早期/异常状态兜底：绝不因我们的输入处理把游戏打崩
            SablestopNow.LOGGER.debug("Staff enhance mouse handler skipped", t);
        }
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void sablestopnow$staffEnhanceScroll(final long windowPointer, final double xOffset, final double yOffset,
                                                 final CallbackInfo ci) {
        try {
            if (StaffEnhanceClientHandler.handleScroll(yOffset)) {
                ci.cancel();
            }
        } catch (final Throwable t) {
            SablestopNow.LOGGER.debug("Staff enhance scroll handler skipped", t);
        }
    }

    /**
     * 整组拖拽旋转输入：取 MouseHandler.turnPlayer 中 LocalPlayer.turn 调用前的 yaw/pitch 局部量
     * （与航空学同源同量级），TAB 按住时旋转整组并取消视角转动。
     */
    @Inject(method = "turnPlayer", cancellable = true,
            at = @At(value = "INVOKE", shift = At.Shift.BEFORE, target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"))
    private void sablestopnow$staffEnhanceTurn(final double d, final CallbackInfo ci,
                                               @Local(ordinal = 4) final double yaw,
                                               @Local(ordinal = 5) final double pitch,
                                               @Local(ordinal = 0) final int pitchScale) {
        try {
            if (StaffEnhanceClientHandler.handleLookMove(yaw, pitch * pitchScale)) {
                ci.cancel();
            }
        } catch (final Throwable t) {
            SablestopNow.LOGGER.debug("Staff enhance turn handler skipped", t);
        }
    }
}
