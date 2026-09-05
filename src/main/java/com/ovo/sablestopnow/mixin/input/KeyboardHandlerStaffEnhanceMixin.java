package com.ovo.sablestopnow.mixin.input;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.client.StaffEnhanceClientHandler;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 KeyboardHandler.keyPress 的 HEAD 拦截按键。
 *
 * <p>我们在 HEAD 消费（cancel）时，Simulated 的 KeyboardHandlerMixin（注入点在 Minecraft.screen GETFIELD
 * 之后）以及 vanilla 按键处理都不会再执行 —— 即多选模式的“接管禁用”。未消费时原样放行。
 */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerStaffEnhanceMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void sablestopnow$staffEnhanceKey(final long windowPointer, final int key, final int scanCode,
                                              final int action, final int modifiers, final CallbackInfo ci) {
        try {
            if (StaffEnhanceClientHandler.handleKey(key, scanCode, action, modifiers)) {
                ci.cancel();
            }
        } catch (final Throwable t) {
            // 启动早期/异常状态兜底：绝不因我们的输入处理把游戏打崩
            SablestopNow.LOGGER.debug("Staff enhance key handler skipped", t);
        }
    }
}
