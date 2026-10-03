package com.ovo.sablestopnow.mixin.scale;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;

/** Static invokers for the package-private chunk/bounds/mass natives the scaled-collider resampler uploads through. */
@Mixin(value = Rapier3D.class, remap = false)
public interface Rapier3DAccessor {

    @Invoker(value = "addChunk", remap = false)
    static void sablestopnow$addChunk(final long handle, final int x, final int y, final int z,
                                      final int[] data, final boolean global, final int bodyId) {
        throw new AssertionError();
    }

    @Invoker(value = "removeChunk", remap = false)
    static void sablestopnow$removeChunk(final long handle, final int x, final int y, final int z, final boolean global) {
        throw new AssertionError();
    }

    @Invoker(value = "setLocalBounds", remap = false)
    static void sablestopnow$setLocalBounds(final long handle, final int bodyId,
                                            final int minX, final int minY, final int minZ,
                                            final int maxX, final int maxY, final int maxZ) {
        throw new AssertionError();
    }

    /** The raw native behind {@code setMassPropertiesFrom}; inertia tensor is 9 doubles, row by row. */
    @Invoker(value = "setMassProperties", remap = false)
    static void sablestopnow$setMassProperties(final long handle, final int bodyId, final double mass,
                                               final double[] centerOfMass, final double[] inertiaTensor) {
        throw new AssertionError();
    }

    @Invoker(value = "setMassPropertiesFrom", remap = false)
    static void sablestopnow$setMassPropertiesFrom(final long handle, final int bodyId, final MassData massData) {
        throw new AssertionError();
    }

    /** Force + torque on a body (body frame); re-dispatched with scale-corrected magnitudes. */
    @Invoker(value = "applyForceAndTorque", remap = false)
    static void sablestopnow$applyForceAndTorque(final long handle, final int bodyId,
                                                 final double fx, final double fy, final double fz,
                                                 final double tx, final double ty, final double tz, final boolean wakeUp) {
        throw new AssertionError();
    }

    /** Force at a body-frame offset from the centre of mass (native derives the torque); used by {@code ScaledDrag}. */
    @Invoker(value = "applyForce", remap = false)
    static void sablestopnow$applyForce(final long handle, final int bodyId,
                                        final double px, final double py, final double pz,
                                        final double fx, final double fy, final double fz, final boolean wakeUp) {
        throw new AssertionError();
    }

    /**
     * Re-aims one side ({@code side} 0 = body1, 1 = body2) of a live joint: sets its local anchor position and
     * frame orientation. Used to keep a scaled body's joints on the resampled geometry after the rotation point
     * moves (see {@link com.ovo.sablestopnow.scale.ScaledConstraints}). Preserves the native joint, so its motor,
     * limits and locked axes survive untouched.
     */
    @Invoker(value = "setConstraintFrame", remap = false)
    static void sablestopnow$setConstraintFrame(final long sceneHandle, final long handle, final int side,
                                                final double localPosX, final double localPosY, final double localPosZ,
                                                final double localOrientationX, final double localOrientationY,
                                                final double localOrientationZ, final double localOrientationW) {
        throw new AssertionError();
    }

    /** True while the native joint behind this handle is still in the scene (guards re-aim against removed joints). */
    @Invoker(value = "isConstraintValid", remap = false)
    static boolean sablestopnow$isConstraintValid(final long sceneHandle, final long handle) {
        throw new AssertionError();
    }

    // ==================================================================
    // 体素碰撞体（我们自己模型的复杂碰撞形状走这条路）
    // ==================================================================

    /**
     * 建一个「方块形状」条目，返回它的下标。
     *
     * <p>⚠ 它描述的是**一个方块**的碰撞形状：随后用
     * {@link dev.ryanhcode.sable.physics.impl.rapier.Rapier3D#addVoxelColliderBox(int, double[])}
     * 喂进去的坐标必须在**方块局部的单位立方体 0..1** 里
     * （依据：`RapierVoxelColliderData.addBox` 的 javadoc 明写 "within a single voxel space
     * of the block, 0-1"，且烘焙器把边界夹到 [0,1]）。
     * 所以「1/16 精度」= 把一个方块 16 等分。
     *
     * @param contactEvents 可以为 null（Rust 里判空后走无回调分支）
     */
    @Invoker(value = "newVoxelCollider", remap = false)
    static int sablestopnow$newVoxelCollider(
            final double frictionMultiplier, final double volume, final double restitution,
            final boolean isFluid,
            final dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback contactEvents) {
        throw new AssertionError();
    }

    /**
     * 建一个**运动学装置**（Sable 用 `LevelCollider` 自定义形状做碰撞的那套机制）。
     *
     * <p>⚠ {@code mountId} 的语义（Rust `contraptions.rs` 里读死的）：
     * <ul>
     *   <li>{@code -1} → 新建一个独立的运动学刚体；</li>
     *   <li>其它值 → **挂到那个已有刚体上**（{@code rigid_bodies.get(&(mountId as LevelColliderID))}）。</li>
     * </ul>
     * 挂到**子结构自己的刚体**上正是我们要的：**同一个刚体上的碰撞体之间永不互相碰撞**，
     * 所以我们模型的形状与子结构自身的方块重合也**无害**，而且**不新增刚体**。
     *
     * <p>⚠ {@code pose} 在 Rust 侧是 {@code _pose} —— **被忽略**，传什么都行。
     */
    @Invoker(value = "createKinematicContraption", remap = false)
    static void sablestopnow$createKinematicContraption(final long sceneHandle,
                                                        final int mountId, final int id,
                                                        final double[] pose) {
        throw new AssertionError();
    }

    /**
     * 往装置里喂一个 **16³ 的方块数据段**（4096 个 int）。
     *
     * <p>⚠ 每个 int 的打包方式（Rust `contraptions.rs` 里读死的）：
     * <pre>
     * 高 16 位 = 体素碰撞体下标 **+1**（0 = 空气；依据 lib.rs 的 {@code voxel_colliders[block.0 - 1]}）
     * 低 16 位 = 索引进 ALL_VOXEL_PHYSICS_STATES = [Empty, Face, Edge, Corner, Interior]
     *            （我们自己造实心形状时填 4 = Interior）
     * </pre>
     * 所以 {@code packed = ((voxelColliderIndex + 1) << 16) | 4;}。
     *
     * <p>关键含义：这个打包是**逐方块**的 → **每个方块格可以引用它自己的体素碰撞体**，
     * 于是能给每个方块不同的 1/16 精确局部形状，拼起来就是 1/16 精度的曲面。
     */
    @Invoker(value = "addKinematicContraptionChunkSection", remap = false)
    static void sablestopnow$addKinematicContraptionChunkSection(final long sceneHandle,
                                                                 final int id,
                                                                 final int x, final int y,
                                                                 final int z,
                                                                 final int[] data) {
        throw new AssertionError();
    }

    /** 装置的下标也是从同一个分配器拿的。 */
    @Invoker(value = "removeKinematicContraption", remap = false)
    static void sablestopnow$removeKinematicContraption(final long sceneHandle, final int id) {
        throw new AssertionError();
    }

    /**
     * **把装置摆到世界里** —— 这一步我漏过一次，代价是服务端崩溃：
     * {@code Rapier native panic: compute_local_aabb called before bounds were set}。
     *
     * <p>只调 {@code createKinematicContraption} + {@code addKinematicContraptionChunkSection}
     * + {@code setLocalBounds} **不够**：{@code setLocalBounds} 是拿**主关卡**的 chunks 去算
     * AABB 的（Rust 里传的是 {@code main_level_chunks}），而装置的方块在它自己的
     * {@code chunk_map} 里 —— 所以对装置它算不出 bounds，步进时就 panic。
     * Sable 自己的 {@code updateContraptionPose} 在这之前会调本函数，靠它建立/更新碰撞体。
     *
     * <p>⚠ 参数数组长度是**写死的**：{@code centerOfMass} 3 个、{@code pose} 7 个
     * （xyz + xyzw）、{@code velocities} 6 个（线速度 3 + 角速度 3）。
     * 少一个都会在原生侧越界。
     *
     * <p>⚠ {@code pose} 是**相对于父体质心**的（Sable 自己就是
     * {@code pos.sub(parentCenterOfMass)}）—— 传绝对坐标会把装置摆到很远的地方。
     */
    @Invoker(value = "setKinematicContraptionTransform", remap = false)
    static void sablestopnow$setKinematicContraptionTransform(final long sceneHandle,
                                                              final int id,
                                                              final double[] centerOfMass,
                                                              final double[] pose,
                                                              final double[] velocities) {
        throw new AssertionError();
    }
}
