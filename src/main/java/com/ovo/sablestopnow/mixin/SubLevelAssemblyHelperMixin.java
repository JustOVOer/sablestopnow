package com.ovo.sablestopnow.mixin;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.SablestopNowConfig;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SubLevelAssemblyHelper.class)
public class SubLevelAssemblyHelperMixin {

    @Inject(method = "assembleBlocks", at = @At("RETURN"), remap = false)
    private static void onAssembleBlocks(ServerLevel level, BlockPos anchor, Iterable<BlockPos> blocks,
                                         BoundingBox3ic bounds, CallbackInfoReturnable<ServerSubLevel> cir) {
        if (!SablestopNowConfig.INSTANCE.lockNewSubLevels.get()) {
            return;
        }

        ServerSubLevel subLevel = cir.getReturnValue();
        if (subLevel == null) {
            return;
        }

        // 使用航空学 PhysicsStaffServerHandler 的 toggleLock 方法
        PhysicsStaffServerHandler handler = PhysicsStaffServerHandler.get(level);
        handler.toggleLock(subLevel.getUniqueId());

        SablestopNow.LOGGER.info("Locked new sub-level via PhysicsStaffServerHandler at {}", subLevel.logicalPose().position());
    }
}