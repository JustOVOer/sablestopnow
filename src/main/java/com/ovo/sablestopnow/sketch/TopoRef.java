package com.ovo.sablestopnow.sketch;

import java.util.List;
import java.util.UUID;

/**
 * **拓扑引用**（点 / 棱 / 面）——模型几何里"可以被人引用的东西"的一等公民 ✓
 *
 * <h2>为什么需要它</h2>
 * 以前圆角直接把"两个点 + 两个法线"存进特征里，于是：
 * <ul>
 *   <li>棱没有身份 ⇒ 镜像/阵列**没法把棱选作源**</li>
 *   <li>棱没有父级 ⇒ 依赖链断在实体层：{@code 圆角 → 拉伸}，
 *       而真实依赖是 {@code 圆角 → 棱 → (拉伸 + 若干切割)}</li>
 *   <li>模型上重建出来的东西（棱/面）**没法存、没法参与依赖**</li>
 * </ul>
 *
 * <h2>一条棱可以依赖多个特征</h2>
 * {@link #contributors()} 是**集合**：例如一条棱由"拉伸3"与"切割5、切割7"共同决定，
 * 三者都在里面。任一被移动/删除/重排，都能显式判定这条引用是否失效 ✓
 *
 * <p>纯数据、无游戏依赖（本包被离线单独编译 ✓）。
 */
public record TopoRef(UUID id, Kind kind, UUID bodyId, List<UUID> contributors, double[] geom) {

    /** 引用的种类 ✓ */
    public enum Kind {
        /** 点：{@code geom = [x, y, z]} */
        POINT,
        /** 棱：{@code geom = [ax,ay,az, bx,by,bz, n1x,n1y,n1z, n2x,n2y,n2z]} */
        EDGE,
        /** 面：{@code geom = [cx,cy,cz, nx,ny,nz, area]}（中心 + 法线 + 面积） */
        FACE
    }

    /** 来源：拾取时创建 / 从模型重建 ✓ */
    public enum Provenance {
        PICKED,
        RECONSTRUCTED
    }

    public TopoRef {
        contributors = contributors == null ? List.of() : List.copyOf(contributors);
        geom = geom == null ? new double[0] : geom.clone();
    }

    /** 几何去重键（坐标按 1e-4 量化，避免同一处点两次产生两条记录）✓ */
    public String dedupKey() {
        final StringBuilder sb = new StringBuilder();
        sb.append(kind).append('|').append(bodyId).append('|');
        for (final double v : geom) {
            sb.append(Math.round(v * 1.0e4)).append(',');
        }
        sb.append('|');
        final java.util.List<UUID> sorted = new java.util.ArrayList<>(contributors);
        sorted.sort(java.util.Comparator.comparing(UUID::toString));
        for (final UUID c : sorted) {
            sb.append(c).append(',');
        }
        return sb.toString();
    }

    /** 棱的两个端点（非 EDGE 返回 null）✓ */
    public double[] edgeA() {
        return kind == Kind.EDGE && geom.length >= 6
                ? new double[] {geom[0], geom[1], geom[2]} : null;
    }

    /** 棱的另一个端点 ✓ */
    public double[] edgeB() {
        return kind == Kind.EDGE && geom.length >= 6
                ? new double[] {geom[3], geom[4], geom[5]} : null;
    }
}
