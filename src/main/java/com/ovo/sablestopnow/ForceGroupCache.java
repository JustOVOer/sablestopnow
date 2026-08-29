package com.ovo.sablestopnow;

import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.WeakHashMap;

public class ForceGroupCache {
    private static final Map<QueuedForceGroup, ResourceLocation> CACHE = new WeakHashMap<>();

    public static void put(QueuedForceGroup queued, ResourceLocation id) {
        CACHE.put(queued, id);
    }

    public static ResourceLocation get(QueuedForceGroup queued) {
        return CACHE.get(queued);
    }
}