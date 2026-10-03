package com.ovo.sablestopnow.mixin.input;

import com.ovo.sablestopnow.SablestopNow;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把键盘事件转发给配合边栏的输入框。
 *
 * <p>配合边栏是 <b>HUD 覆盖层</b>（见 {@code MateClientState.syncPanel} 的说明：做成 Screen 会被
 * mod 生态里的 {@code setScreen(其它界面)} 顶掉），因此它<b>不在 screen 栈上</b> —— 原版
 * {@code Minecraft.keyPress}/{@code charTyped} 只会转发给 {@code this.screen}，边栏永远收不到。
 * 鼠标那条路当初也是这么接的（{@code MouseHandlerStaffEnhanceMixin}）。
 *
 * <p>这里只在「边栏里正有输入框在编辑」时接管（{@link MateModeInput#handleKey}/
 * {@link MateModeInput#handleChar} 会判断），其余情况完全不影响原版行为。
 */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerStaffEnhanceMixin {

    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void sablestopnow$mateKeyPress(final long windowPointer, final int key, final int scanCode,
                                           final int action, final int modifiers, final CallbackInfo ci) {
        try {
            if (com.ovo.sablestopnow.client.gui.lib.Ui.handleKey(key, scanCode, action, modifiers)) {
                ci.cancel();
                return;
            }
            if (com.ovo.sablestopnow.client.model.ModelInput.handleKey(key, scanCode, action, modifiers)) {
                ci.cancel();
                return;
            }
        } catch (final Throwable t) {
            // 与鼠标那边同样的理由：运行期日志级别是 INFO，写 DEBUG 等于把异常彻底吞掉
            SablestopNow.LOGGER.error("Mate keyboard handler threw", t);
        }
    }

    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void sablestopnow$mateCharTyped(final long windowPointer, final int codePoint, final int modifiers,
                                            final CallbackInfo ci) {
        try {
            if (com.ovo.sablestopnow.client.gui.lib.Ui.handleChar((char) codePoint, modifiers)) {
                ci.cancel();
            }
        } catch (final Throwable t) {
            SablestopNow.LOGGER.error("Mate char handler threw", t);
        }
    }
}
