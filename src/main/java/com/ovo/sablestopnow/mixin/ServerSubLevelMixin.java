package com.ovo.sablestopnow.mixin;

import com.ovo.sablestopnow.ForceGroupCache;
import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerSubLevel.class)
public class ServerSubLevelMixin {

    @Inject(method = "getOrCreateQueuedForceGroup", at = @At("RETURN"), remap = false)
    private void onGetOrCreateQueuedForceGroup(ForceGroup forceGroup, CallbackInfoReturnable<QueuedForceGroup> cir) {
        QueuedForceGroup queued = cir.getReturnValue();
        if (queued != null) {
            ResourceLocation id = ForceGroups.REGISTRY.getKey(forceGroup);
            if (id != null) {
                ForceGroupCache.put(queued, id);
            }
        }
    }
}