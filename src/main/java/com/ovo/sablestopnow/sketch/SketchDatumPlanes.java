package com.ovo.sablestopnow.sketch;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;

/**
 * 三个基准面 + 它们的拾取。
 *
 * <h2>为什么基准面要有"范围"</h2>
 * 数学上平面是无限的，但 SolidWorks 里的基准面是显示成一块矩形板的 ——
 * 玩家点的是那块板。如果按无限平面拾取，鼠标指向结构旁边的空气也会命中，
 * 玩家会觉得"怎么点哪都能选中"。
 *
 * <p>所以每个基准面带半宽/半高（由结构包围盒投影到平面两个轴上算出来），
 * 拾取时要求落点落在范围内。
 *
 * <h2>坐标系</h2>
 * 全部是<b>子结构 plot 局部系</b>：包围盒是局部的，射线也要先换算到局部再进来。
 * 调用方（客户端拾取）负责把世界射线换到局部。
 *
 * <p>⚠ 不引用任何 Minecraft 类，可用 {@code tools/SketchModelCheck.java} 验证。
 */
public final class SketchDatumPlanes {

    /** 默认在包围盒外扩的余量（格）：让基准面板比结构稍大一点，好点。 */
    public static final double DEFAULT_MARGIN = 0.5;

    /**
     * 基准板的**最小半边长**（格）—— 也就是最短的那条边至少 2×这个数。
     *
     * <p>为什么要下限：板的尺寸是「结构包围盒在该平面两个轴上的跨度 + 余量」，
     * 于是**薄结构会得到一条很窄的板**。实测某结构局部跨度只有 X=1、Y=4、Z=3 格，
     * 前视板就只有 2 格宽 —— 视角拉远一点基本点不中。
     *
     * <p>取 3.0（最短边 6 格）是权衡：小结构变得可点，而大结构本来就超过这个尺寸、
     * 完全不受影响。不需要按结构大小做动态调整——"小的补足、大的不动"这个性质
     * 由 {@code max} 天然给出。
     */
    public static final double MIN_HALF_EXTENT = 3.0;

    /**
     * 一块基准面：平面 + 它在两个轴向上的半宽/半高。
     *
     * <p>{@code (u,v)} 落在 {@code [-halfU, halfU] × [-halfV, halfV]} 内才算命中。
     */
    public record DatumPlane(SketchPlane plane, double halfU, double halfV) {

        public DatumPlane {
            if (halfU <= 0.0 || halfV <= 0.0) {
                throw new IllegalArgumentException("基准面的半宽/半高必须为正");
            }
        }

        /** 平面内坐标是否落在这块板上。 */
        public boolean contains(final double u, final double v) {
            return Math.abs(u) <= halfU && Math.abs(v) <= halfV;
        }
    }

    /** 一次拾取命中：命中的是哪块板、平面内坐标是多少、沿射线走了多远。 */
    public record Hit(DatumPlane plane, double u, double v, double distance) {
    }

    private SketchDatumPlanes() {
    }

    /**
     * 由结构的局部包围盒生成三个基准面，都以包围盒中心为原点。
     *
     * <p>半宽/半高不是逐个基准面硬编码，而是把包围盒 8 个角投影到该平面的两个轴上取最大值 ——
     * 这样不管将来基准面怎么定义（甚至加上贴面的斜平面），范围都自动是对的。
     */
    public static List<DatumPlane> of(final Vector3dc bboxMin, final Vector3dc bboxMax,
                                      final double margin) {
        final Vector3d center = new Vector3d(bboxMin).add(bboxMax).mul(0.5);
        final List<DatumPlane> out = new ArrayList<>(SketchPlane.Base.values().length);
        for (final SketchPlane.Base base : SketchPlane.Base.values()) {
            out.add(of(base, center, bboxMin, bboxMax, margin));
        }
        return out;
    }

    public static List<DatumPlane> of(final Vector3dc bboxMin, final Vector3dc bboxMax) {
        return of(bboxMin, bboxMax, DEFAULT_MARGIN);
    }

    /** 单个基准面（指定原点，而不是包围盒中心）。 */
    public static DatumPlane of(final SketchPlane.Base base, final Vector3dc origin,
                                final Vector3dc bboxMin, final Vector3dc bboxMax,
                                final double margin) {
        final SketchPlane plane = SketchPlane.base(base, origin);
        double halfU = 0.0;
        double halfV = 0.0;
        for (int i = 0; i < 8; i++) {
            final Vector3d corner = new Vector3d(
                    (i & 1) == 0 ? bboxMin.x() : bboxMax.x(),
                    (i & 2) == 0 ? bboxMin.y() : bboxMax.y(),
                    (i & 4) == 0 ? bboxMin.z() : bboxMax.z()).sub(origin);
            halfU = Math.max(halfU, Math.abs(corner.dot(plane.axisU())));
            halfV = Math.max(halfV, Math.abs(corner.dot(plane.axisV())));
        }
        // 退化包围盒（单方块平面）也要有个能点的尺寸；再套一层最小边长下限，
        // 免得薄结构得到一条窄到点不中的板。
        return new DatumPlane(plane,
                Math.max(Math.max(halfU, 0.5) + margin, MIN_HALF_EXTENT),
                Math.max(Math.max(halfV, 0.5) + margin, MIN_HALF_EXTENT));
    }

    /**
     * 在若干块基准面里挑出射线打中的那一块。
     *
     * <p>命中多块时取**最近**的：从斜上方看结构时会同时穿过 Top 和 Front，
     * 取最近的才符合"我点的是我看到的那块板"。
     *
     * @return 命中；全都没打中返回 null
     */
    @Nullable
    public static Hit pick(final List<DatumPlane> candidates, final Vector3dc rayOrigin,
                           final Vector3dc rayDir) {
        Hit best = null;
        for (final DatumPlane candidate : candidates) {
            final double t = candidate.plane().rayParameter(rayOrigin, rayDir);
            if (Double.isNaN(t)) {
                continue;
            }
            final Vector3d hit = new Vector3d(rayDir).mul(t).add(rayOrigin);
            final Vector3d uv = candidate.plane().toPlane(hit, new Vector3d());
            if (!candidate.contains(uv.x, uv.y)) {
                continue;
            }
            if (best == null || t < best.distance()) {
                best = new Hit(candidate, uv.x, uv.y, t);
            }
        }
        return best;
    }
}
