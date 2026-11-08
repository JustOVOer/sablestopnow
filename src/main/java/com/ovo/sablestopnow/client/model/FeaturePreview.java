package com.ovo.sablestopnow.client.model;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

import java.util.List;
import java.util.UUID;

/**
 * **模型操作的线框显示** ✓（item 5）+ **已选参考几何的高亮** ✓（bug 3/4）
 *
 * <p>设计见 {@code docs/特征系统v3-设计.md} §6：
 * 进入拉伸/切割/旋转/基准面/圆角/镜像/阵列等操作时就创建预览，
 * 数值或选择变化时**只更新预览**，只有"确认"才真正提交数据 ✓
 *
 * <h2>两路数据，互不影响（这是 bug 3/4 的修法）</h2>
 * <ul>
 *   <li>{@link #set} = **产物预览**（黄色）：拉伸出来的线框、镜像/阵列的副本……
 *       它依赖"参数 + 源"都齐了才有意义。</li>
 *   <li>{@link #setRefs} = **已选参考几何**（青色）：用户点中的那张面 / 那条棱。
 *       它**只依赖选择本身**，与"源列表是否为空"无关 —— 以前参考几何和产物预览
 *       共用一份数据，而那份数据在"选择还不完整"时被每帧清空 ⇒
 *       选完镜像面/阵列方向棱之后什么都看不到（用户实测 bug 3/4）。</li>
 * </ul>
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
    private static List<Seg> refs = List.of();

    /**
     * 设置**产物预览** ✓（会清掉上一轮的参考几何 ⇒ 需要参考时随后调 {@link #setRefs}）
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
        refs = List.of();
    }

    /**
     * 设置**已选参考几何**（青色高亮）✓
     *
     * <p>与 {@link #set} 分开：镜像/阵列的"选中的那张面/那条棱"必须在
     * **源列表为空时也可见**（用户先选参考、再选源）。
     *
     * @param bodyId 参考几何属于哪个结构
     * @param reference 参考线段（局部坐标）
     */
    public static void setRefs(@Nullable final UUID bodyId, @Nullable final List<Seg> reference) {
        if (bodyId != null) {
            body = bodyId;
        }
        refs = reference == null ? List.of() : reference;
    }

    /** 清掉两路数据 ✓（退出操作 / 取消 / 已提交时都要调 —— GUI 自查项 8） */
    public static void clear() {
        body = null;
        normal = null;
        segs = List.of();
        refs = List.of();
    }

    public static boolean active() {
        return body != null && (!segs.isEmpty() || !refs.isEmpty());
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

    /** 已选参考几何（青色）✓ */
    public static List<Seg> refs() {
        return refs;
    }

    /** 预览线段数量（诊断用）✓ */
    public static int count() {
        return segs.size();
    }

    /** 参考线段数量（诊断用）✓ */
    public static int refCount() {
        return refs.size();
    }
}
