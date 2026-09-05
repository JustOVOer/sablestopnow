package com.ovo.sablestopnow;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3d;

import com.ovo.sablestopnow.network.StaffEnhanceNetworking;
import com.ovo.sablestopnow.server.StaffEnhanceServer;
import dev.ryanhcode.sable.platform.SableEventPlatform;

import java.util.ArrayList;
import java.util.List;

@Mod(SablestopNow.MOD_ID)
public class SablestopNow {
    public static final String MOD_ID = "sablestopnow";
    public static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    private static final List<ForceRecord> currentRecords = new ArrayList<>();
    private static List<ForceRecord> displayRecords = new ArrayList<>();
    private static boolean hasNewForces = false;  // 标记上一次 finalizeTick 是否有新力

    public SablestopNow(ModContainer container) {
        container.registerConfig(
                ModConfig.Type.COMMON,
                SablestopNowConfig.SPEC,
                MOD_ID + "-common.toml"
        );
        LOGGER.info("Sable Force Limiter initialized.");
        StaffEnhanceNetworking.init();
        // 整组拖拽：每物理子步驱动（跨平台 API，同 Simulated.init() 用法）
        SableEventPlatform.INSTANCE.onPhysicsTick(StaffEnhanceServer::physicsTick);
        NeoForge.EVENT_BUS.addListener(this::registerCommands);
    }

    private void registerCommands(RegisterCommandsEvent event) {
        ForceCommand.register(event.getDispatcher());
    }

    public static synchronized void recordForce(double magnitude, Component displayName, String groupId,
                                                String targetId, boolean filtered, Vector3d position, ServerLevel level) {
        // 检查是否已存在完全相同的记录（避免重复）
        ForceRecord newRecord = new ForceRecord(magnitude, displayName, groupId, targetId, filtered, position);
        for (ForceRecord existing : currentRecords) {
            if (existing.getMagnitude() == newRecord.getMagnitude() &&
                    existing.getGroupId().equals(newRecord.getGroupId()) &&
                    existing.getTargetId().equals(newRecord.getTargetId()) &&
                    existing.isFiltered() == newRecord.isFiltered() &&
                    existing.getPosition().equals(newRecord.getPosition())) {
                return; // 已存在，忽略
            }
        }
        currentRecords.add(newRecord);
    }

    public static synchronized void finalizeTick() {
        if (!currentRecords.isEmpty()) {
            displayRecords = new ArrayList<>(currentRecords);
            hasNewForces = true;
            currentRecords.clear();
        } else {
            hasNewForces = false;
            // displayRecords 保持不变（保留上一刻记录）
        }
    }

    public static synchronized List<ForceRecord> getForceRecords() {
        return new ArrayList<>(displayRecords);
    }

    public static synchronized boolean hasNewForces() {
        return hasNewForces;
    }

    public static synchronized void clearRecords() {
        currentRecords.clear();
        displayRecords.clear();
        hasNewForces = false;
    }

    public static void saveConfig() {
        try {
            SablestopNowConfig.save();
            LOGGER.info("SablestopNow config saved.");
        } catch (Exception e) {
            LOGGER.error("Failed to save config", e);
        }
    }
}