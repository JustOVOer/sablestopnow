package com.ovo.sablestopnow.mixin;

import com.ovo.sablestopnow.SablestopNow;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SubLevelPhysicsSystem.class)
public class SubLevelPhysicsSystemMixin {

    @Inject(method = "tickPipelinePhysics", at = @At("RETURN"), remap = false)
    private void onTickPipelinePhysics(ServerSubLevelContainer container, CallbackInfo ci) {
        SablestopNow.finalizeTick();
    }
}