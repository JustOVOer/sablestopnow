package com.ovo.sablestopnow.mixin;

import com.ovo.sablestopnow.SablestopNowConfig;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockPlaceContext.class)
public class BlockPlaceContextOverrideMixin {

    @Inject(method = "canPlace", at = @At("HEAD"), cancellable = true)
    private void sablestopnow$overrideCanPlace(CallbackInfoReturnable<Boolean> cir) {
        if (SablestopNowConfig.INSTANCE.disablePlacementCollisionCheck.get()) {
            cir.setReturnValue(true);
        }
    }
}