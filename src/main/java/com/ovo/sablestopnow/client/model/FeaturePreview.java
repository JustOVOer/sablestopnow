package com.ovo.sablestopnow.client.model;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.List;
import java.util.UUID;

/**
 * **模型操作的黄色线框预览** ✓（item 5）。
 *
 * <p>设计见 {@code docs/特征系统v3-设计.md} §6：
 * 进入拉伸/切割/旋转/基准面/圆角/镜像/阵列等操作时就创建预览，
 * 数值或选择变化时**只更新预览**，只有"确认"才真正提交数据 ✓
 *
 * <p>几何存的是**实体局部坐标**（线框端点对）+ **基准面法线**：
 * 渲染侧用 {@code renderPose} 变换到世界、用同一条 3D 棒图元画 ✓
 * （法线决定棒子的"厚度方向"，不传的话会退化成屏幕线 ✗）
 */
public final class FeaturePreview {

    private FeaturePreview() {
    }

    /** 一条预览线段（实体局部坐标）✓ */
    public record Seg(Vector3d a, Vector3d b) {
    }

    private static UUID body;
    private static Vector3d normal;
    private static List<Seg> segs = List.of();

    /**
     * 设置预览 ✓
     *
     * @param bodyId       预览属于哪个结构（用它的 renderPose 变换）
     * @param planeNormal  基准面法线（局部坐标）—— 决定线框棒的厚度方向；可 null
     * @param segments     线框线段（局部坐标）
     */
    public static void set(@Nullable final UUID bodyId, @Nullable final Vector3d planeNormal,
                           @Nullable final List<Seg> segments) {
        body = bodyId;
        normal = planeNormal == null ? null : new Vector3d(planeNormal);
        segs = segments == null ? List.of() : segments;
    }

    /** 清掉预览 ✓（退出操作 / 取消 / 已提交时都要调 —— GUI 自查项 8） */
    public static void clear() {
        body = null;
        normal = null;
        segs = List.of();
    }

    public static boolean active() {
        return body != null && !segs.isEmpty();
    }

    @Nullable
    public static UUID body() {
        return body;
    }

    @Nullable
    public static Vector3d normal() {
        return normal == null ? null : new Vector3d(normal);
    }

    public static List<Seg> segments() {
        return segs;
    }

    /** 预览线段数量（诊断用）✓ */
    public static int count() {
        return segs.size();
    }
}
