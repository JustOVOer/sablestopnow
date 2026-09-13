package com.ovo.sablestopnow.mixin.scale;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import io.netty.buffer.ByteBuf;
import org.joml.Vector3dc;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.util.SableBufferUtils;

/**
 * Carries the pose <b>scale</b> across Sable's network layer.
 *
 * <p>{@code SableBufferUtils.write/read(ByteBuf, Pose3d[c])} is the single (de)serialization point for every pose
 * that crosses the wire &mdash; the movement snapshots ({@code ClientboundSableSnapshotDualPacket}, both the UDP and
 * TCP paths), start-tracking, split and gizmo packets, and {@code POSE3D_STREAM_CODEC} all funnel through it. Stock
 * Sable writes position + orientation + rotation point and silently drops {@code Pose3d.scale()}, so a scale set on
 * the server could never reach clients. Appending it here means scaled sub-levels flow through Sable's existing
 * interpolation untouched ({@code Pose3dc.lerp} already lerps scale, so clients even animate the resize smoothly).</p>
 *
 * <p>Both sides of a connection must agree on this extended format &mdash; ship the mod on server and client.</p>
 *
 * <p>中文：这是位姿跨网络（反）序列化的唯一入口。附加 scale 之后，缩放随 Sable 自己的位姿包走到客户端，
 * 本模组不再需要自建的缩放同步包。</p>
 */
@Mixin(value = SableBufferUtils.class, remap = false)
public abstract class SableBufferUtilsMixin {

    @Inject(
        method = "write(Lio/netty/buffer/ByteBuf;Ldev/ryanhcode/sable/companion/math/Pose3dc;)V",
        at = @At("TAIL"))
    private static void sablestopnow$writeScale(final ByteBuf buf, final Pose3dc pose, final CallbackInfo ci) {
        final Vector3dc scale = pose.scale();
        buf.writeDouble(scale.x());
        buf.writeDouble(scale.y());
        buf.writeDouble(scale.z());
    }

    @Inject(
        method = "read(Lio/netty/buffer/ByteBuf;Ldev/ryanhcode/sable/companion/math/Pose3d;)Ldev/ryanhcode/sable/companion/math/Pose3d;",
        at = @At("TAIL"))
    private static void sablestopnow$readScale(final ByteBuf buf, final Pose3d pose, final CallbackInfoReturnable<Pose3d> cir) {
        pose.scale().set(buf.readDouble(), buf.readDouble(), buf.readDouble());
    }
}
