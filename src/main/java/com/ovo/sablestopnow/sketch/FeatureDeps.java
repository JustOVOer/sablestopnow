package com.ovo.sablestopnow.sketch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * **特征顺序 = 操作顺序** ✓（item 4）。
 *
 * <p>我们现有的"实体"（拉伸/旋转/切割）就是特征；顺序用 {@link Solid#index()} 表示
 * （树按序号从小到大显示 = 求值顺序）✓。新特征（基准面/圆角/镜像/阵列）在各自的里程碑里
 * 以同样方式**占用一个序号**，因此顺序与依赖只需要一套规则 ✓
 *
 * <h2>这一版能做的校验</h2>
 * <ol>
 *   <li>序号必须**唯一**（重排时用交换，天然唯一 ✓）</li>
 *   <li>**切割必须排在该 body 的第一个实心特征之后** ✓
 *       （没有材料就没有东西可切 ⇒ 把切割移到最前面是无意义且会让玩家困惑的）✗</li>
 *   <li>依赖的草图必须存在 ✓</li>
 * </ol>
 *
 * <p>为后续里程碑留的口子：`requiresBefore(...)` 里按特征种类追加规则
 * （圆角依赖棱所属实体、镜像/阵列依赖被引用特征）✓ —— 那时只改这一个方法 ✓
 */
public final class FeatureDeps {

    private FeatureDeps() {
    }

    /** 一个特征的"依赖视图" ✓ */
    public record Node(UUID id, int index, UUID sketchId, boolean material, boolean cut) {
    }

    /** 把一批实体转成排序后的依赖视图（按序号升序）✓ */
    public static List<Node> nodes(final List<Solid> solids) {
        final List<Node> out = new ArrayList<>(solids.size());
        for (final Solid s : solids) {
            final boolean cut = s.shape() instanceof Solid.Shape.Cut;
            out.add(new Node(s.id(), s.index(), s.sketchId(), !cut, cut));
        }
        out.sort(Comparator.comparingInt(Node::index));
        return out;
    }

    /**
     * 依赖校验 ✓（顺序调整前后都可以调；不满足就拒绝移动）。
     *
     * @return null = 通过；否则返回提示用的语言键
     */
    public static String validate(final List<Solid> solids, final List<UUID> existingSketchIds) {
        if (solids == null || solids.isEmpty()) {
            return null;
        }
        // ① 序号唯一
        final java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (final Solid s : solids) {
            if (!seen.add(s.index())) {
                return "model.order.duplicate_index";
            }
        }
        // ② 依赖的草图必须存在（草图集合允许为 null = 调用方不检查）
        if (existingSketchIds != null) {
            for (final Solid s : solids) {
                if (s.shape() instanceof Solid.Shape.Plane) {
                    continue; // 基准面没有真实草图（占位 id）✓
                }
                if (!existingSketchIds.contains(s.sketchId())) {
                    return "model.order.missing_sketch";
                }
            }
        }
        // ③ 基准面必须排在它的**源实体**之后（依赖它才能算出来 ✓）
        final java.util.Map<java.util.UUID, Integer> indexOf = new java.util.HashMap<>();
        for (final Solid s : solids) {
            indexOf.put(s.id(), s.index());
        }
        for (final Solid s : solids) {
            if (s.shape() instanceof final Solid.Shape.Plane pl && pl.sourceSolid() != null) {
                final Integer src = indexOf.get(pl.sourceSolid());
                if (src != null && src > s.index()) {
                    return "model.order.plane_before_source";
                }
            }
        }
        // ④ 切割必须在第一个实心特征之后
        final List<Node> ordered = nodes(solids);
        boolean materialSeen = false;
        for (final Node n : ordered) {
            if (n.material()) {
                materialSeen = true;
            } else if (n.cut() && !materialSeen) {
                return "model.order.cut_before_material";
            }
        }
        return null;
    }

    /**
     * 尝试把某个特征**上移/下移一格** ✓
     *
     * @param dir -1 = 上移（更早执行），+1 = 下移
     * @return 重新分配好的实体列表（序号已交换）；**不允许**时返回 null（调用方提示并保持原样）
     */
    public static List<Solid> moved(final List<Solid> solids, final UUID id, final int dir,
                                    final List<UUID> existingSketchIds) {
        if (solids == null || id == null || (dir != -1 && dir != 1)) {
            return null;
        }
        final List<Solid> ordered = new ArrayList<>(solids);
        ordered.sort(Comparator.comparingInt(Solid::index));
        final int at = indexOf(ordered, id);
        if (at < 0) {
            return null;
        }
        final int to = at + dir;
        if (to < 0 || to >= ordered.size()) {
            return null; // 已经在头/尾 ✓
        }
        // 交换序号 ⇒ 顺序变化，且序号仍然唯一 ✓
        final int a = ordered.get(at).index();
        final int b = ordered.get(to).index();
        ordered.set(at, withIndex(ordered.get(at), b));
        ordered.set(to, withIndex(ordered.get(to), a));
        // 校验：违反依赖 ⇒ 撤销这次移动（返回 null，调用方保持原样）
        if (validate(ordered, existingSketchIds) != null) {
            return null;
        }
        return ordered;
    }

    /** 同 {@link Solid} 但换一个序号 ✓（record ⇒ 构造新对象）。 */
    public static Solid withIndex(final Solid solid, final int index) {
        return new Solid(solid.id(), solid.body(), solid.sketchId(), index, solid.shape(),
                solid.appearance());
    }

    private static int indexOf(final List<Solid> ordered, final UUID id) {
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    /** 上一个特征（更早执行）的序号，没有则 -1 ✓（UI 用来禁用 ↑ 按钮）。 */
    public static int previousIndex(final List<Solid> solids, final UUID id) {
        final List<Solid> ordered = new ArrayList<>(solids);
        ordered.sort(Comparator.comparingInt(Solid::index));
        final int at = indexOf(ordered, id);
        return at > 0 ? ordered.get(at - 1).index() : -1;
    }

    /** 下一个特征的序号，没有则 -1 ✓（UI 用来禁用 ↓ 按钮）。 */
    public static int nextIndex(final List<Solid> solids, final UUID id) {
        final List<Solid> ordered = new ArrayList<>(solids);
        ordered.sort(Comparator.comparingInt(Solid::index));
        final int at = indexOf(ordered, id);
        return at >= 0 && at + 1 < ordered.size() ? ordered.get(at + 1).index() : -1;
    }
}
