package com.ovo.sablestopnow.mixin.scale;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.util.SableNBTUtils;

/**
 * Persists the pose <b>scale</b> in Sable's sub-level save format.
 *
 * <p>{@code SableNBTUtils.writePose3d/readPose3d} back {@code SubLevelSerializer} (region files, holding chunks,
 * split records). Stock Sable saves position + orientation + rotation point and reconstructs the pose with scale
 * (1,1,1), so a scaled sub-level would snap back to normal size on reload. We stash the scale under a namespaced
 * key: absent on old saves (falls back to 1,1,1) and ignored by stock Sable if this mod is later removed &mdash;
 * save-compatible in both directions.</p>
 *
 * <p>中文：缩放随子关卡自己的 NBT 落盘，因此本模组不再需要把缩放表另存进 SavedData。</p>
 */
@Mixin(value = SableNBTUtils.class, remap = false)
public abstract class SableNBTUtilsMixin {

    private static final String SCALE_KEY = "sablestopnow:scale";

    @Inject(method = "writePose3d", at = @At("RETURN"))
    private static void sablestopnow$writeScale(final Pose3d pose, final CallbackInfoReturnable<CompoundTag> cir) {
        cir.getReturnValue().put(SCALE_KEY, SableNBTUtils.writeVector3d(pose.scale()));
    }

    @Inject(method = "readPose3d", at = @At("RETURN"))
    private static void sablestopnow$readScale(final CompoundTag tag, final CallbackInfoReturnable<Pose3d> cir) {
        if (tag.contains(SCALE_KEY, Tag.TAG_COMPOUND))
            cir.getReturnValue().scale().set(SableNBTUtils.readVector3d(tag.getCompound(SCALE_KEY)));
    }
}
