package com.ovo.sablestopnow.scale;

import net.minecraft.server.level.ServerLevel;

/** 由 {@code RapierPhysicsPipelineMixin} 实现在 {@code RapierPhysicsPipeline} 上。 */
public interface ScaledColliderPipeline {

    ServerLevel sablestopnow$level();

    long sablestopnow$sceneHandle();
}
