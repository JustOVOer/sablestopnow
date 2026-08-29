package com.ovo.sablestopnow;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Method;
import java.util.*;

public class SplitConfirmationManager {
    private static final Map<UUID, SplitRequest> PENDING_REQUESTS = new HashMap<>();

    public static void addPendingRequest(UUID subLevelId, ServerLevel level, List<BlockPos> blocks, BoundingBox3i bounds) {
        PENDING_REQUESTS.put(subLevelId, new SplitRequest(level, blocks, bounds));
    }

    public static boolean confirmSplit(UUID subLevelId) {
        SplitRequest request = PENDING_REQUESTS.remove(subLevelId);
        if (request == null) return false;

        ServerLevel level = request.level;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        ServerSubLevel subLevel = (ServerSubLevel) container.getSubLevel(subLevelId);
        if (subLevel == null) return false;

        ServerSubLevel newSubLevel = SubLevelAssemblyHelper.assembleBlocks(level, request.blocks.get(0), request.blocks, request.bounds);
        if (newSubLevel.getSelfMassTracker().getCenterOfMass() == null || newSubLevel.getSelfMassTracker().getMass() <= 0.0) {
            newSubLevel.getPlot().destroyAllBlocks();
            container.removeSubLevel(newSubLevel, SubLevelRemovalReason.REMOVED);
        }

        clearHeatMapData(subLevel);
        return true;
    }

    public static boolean confirmSplitForLevel(ServerLevel level) {
        for (UUID id : PENDING_REQUESTS.keySet()) {
            SplitRequest request = PENDING_REQUESTS.get(id);
            if (request.level.equals(level)) {
                return confirmSplit(id);
            }
        }
        return false;
    }

    public static boolean denySplitForLevel(ServerLevel level) {
        for (UUID id : PENDING_REQUESTS.keySet()) {
            SplitRequest request = PENDING_REQUESTS.get(id);
            if (request.level.equals(level)) {
                PENDING_REQUESTS.remove(id);
                ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                ServerSubLevel subLevel = (ServerSubLevel) container.getSubLevel(id);
                if (subLevel != null) {
                    clearHeatMapData(subLevel);
                }
                return true;
            }
        }
        return false;
    }

    private static void clearHeatMapData(ServerSubLevel subLevel) {
        try {
            SubLevelHeatMapManager heatMapManager = subLevel.getHeatMapManager();
            Method clearMethod = heatMapManager.getClass().getDeclaredMethod("sablestopnow$clearSplitData");
            clearMethod.setAccessible(true);
            clearMethod.invoke(heatMapManager);
        } catch (Exception e) {
            SablestopNow.LOGGER.error("Failed to clear heatmap split data", e);
        }
    }

    public static boolean hasPendingRequest(UUID subLevelId) {
        return PENDING_REQUESTS.containsKey(subLevelId);
    }

    public static Collection<UUID> getPendingIds() {
        return Collections.unmodifiableSet(PENDING_REQUESTS.keySet());
    }

    private record SplitRequest(ServerLevel level, List<BlockPos> blocks, BoundingBox3i bounds) {}
}