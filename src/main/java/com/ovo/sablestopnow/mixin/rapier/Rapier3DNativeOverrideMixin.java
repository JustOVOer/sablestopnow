package com.ovo.sablestopnow.mixin.rapier;

import com.ovo.sablestopnow.physics.NativeLibrary;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * **把 Sable 的原生库加载重定向到我们自己编的那份**（含凸包支持）。
 *
 * <h2>为什么必须"重定向"而不是替换文件</h2>
 *
 * Sable 每次启动都会从 jar 里的 {@code sable_rapier_binaries.zip.l4z}（LZ4 压缩的 zip）
 * 解压出自己的 DLL 到 {@code <gamedir>/.sable/natives/}，**覆盖**掉我们放进去的那份 ✗
 * （实测：部署文件的时间戳 = 启动时刻，SHA256 与原始版完全相同 ✓）
 *
 * <h2>为什么必须只加载一份</h2>
 *
 * Rapier 是**静态链接**进这个 DLL 的 ✓ 两份 DLL = **两个独立的物理世界** ✗
 * 我们的凸包会碰不到 Sable 的地形和结构 ✗✗ 所以必须**取消它的加载、换成我们的** ✓
 *
 * <h2>为什么改这个点</h2>
 *
 * {@code Rapier3D} 里只有一处加载原生库：私有的静态方法 {@code loadLibrary()}（由
 * {@code static {}} 触发）✓ 在它 HEAD 处取消、自己加载，是**改动最小**的做法 ✓
 * 不需要碰它的 jar、也不需要解 LZ4 ✓
 */
@Mixin(value = Rapier3D.class, remap = false)
public class Rapier3DNativeOverrideMixin {

    @Inject(method = "loadLibrary", at = @At("HEAD"), cancellable = true, remap = false)
    private static void sablestopnow$loadOwnNative(final CallbackInfo ci) {
        if (NativeLibrary.load() != null) {
            // 我们这份里有 Sable 全部 53 个符号（从同一份源码编的）+ 我们加的 2 个 ✓
            ci.cancel();
        }
        // 加载失败就不取消 —— 让 Sable 走它自己的路径，至少游戏还能跑
    }
}
