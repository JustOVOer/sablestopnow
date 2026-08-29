package com.ovo.sablestopnow;

import net.minecraft.network.chat.Component;
import org.joml.Vector3d;

public class ForceRecord {
    private final double magnitude;
    private final Component displayName;
    private final String groupId;
    private final String targetId;
    private final boolean filtered;
    private final Vector3d position;

    public ForceRecord(double magnitude, Component displayName, String groupId, String targetId, boolean filtered, Vector3d position) {
        this.magnitude = magnitude;
        this.displayName = displayName;
        this.groupId = groupId;
        this.targetId = targetId;
        this.filtered = filtered;
        this.position = position;
    }

    public double getMagnitude() { return magnitude; }
    public Component getDisplayName() { return displayName; }
    public String getGroupId() { return groupId; }
    public String getTargetId() { return targetId; }
    public boolean isFiltered() { return filtered; }
    public Vector3d getPosition() { return position; }
}