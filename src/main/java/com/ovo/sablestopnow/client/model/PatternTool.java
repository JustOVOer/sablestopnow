package com.ovo.sablestopnow.client.model;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.client.gui.PatternCreateScreen;
import com.ovo.sablestopnow.network.SolidNetworking;
import com.ovo.sablestopnow.sketch.Sketch;
import com.ovo.sablestopnow.sketch.SketchClientState;
import com.ovo.sablestopnow.sketch.Solid;
import com.ovo.sablestopnow.sketch.SolidClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * **阵列工具**的状态机与创建逻辑 ✓（item 10）。
 *
 * <p>抽成独立类（而不是继续往 {@code ModelUi} 里塞）：ModelUi 已经很大，
 * 而阵列的状态（方向/源/预览）自成一体 ✓
 *
 * <p>流程：进入工具 → 点 1–3 条棱定方向 → 右栏点特征加源 → **再点一次第一条棱** ⇒ 弹出次数/间距框
 *（改动即时预览）⇒ 创建 ✓
 */
public final class PatternTool {

    private PatternTool() {
    }

    private static final List<Solid.Shape.Dir> DIRS = new ArrayList<>();
    private static final List<UUID> SOURCES = new ArrayList<>();
    /** 每条方向对应的**那条棱**（局部端点）✓ 预览据此画出"选中的棱" */
    private static final List<double[]> DIR_EDGES = new ArrayList<>();
    private static final Map<UUID, Integer> LOCAL_INDEX = new HashMap<>();
    private static UUID body;

    public static void reset() {
        DIRS.clear();
        SOURCES.clear();
        DIR_EDGES.clear();
        body = null;
        FeaturePreview.clear();
    }

    /**
     * 从复选框 Prop **反推**源集合 ✓（不依赖 onChange 的时序）
     */
    public static void syncFromProps(
            final java.util.Map<UUID,
                    com.ovo.sablestopnow.client.gui.lib.Prop<Boolean>> props) {
        if (props == null) {
            return;
        }
        props.forEach((id, prop) -> {
            if (Boolean.TRUE.equals(prop.get())) {
                if (!SOURCES.contains(id)) {
                    SOURCES.add(id);
                }
            } else {
                SOURCES.remove(id);
            }
        });
    }

    /** 当前阵列作用的体 ✓（面板据此列特征；可能比 selectedModel 更可靠） */
    public static UUID bodyId() {
        return body;
    }

    public static int dirCount() {
        return DIRS.size();
    }

    /** 复选框回调：设置/取消某个源特征 ✓（不重建面板） */
    public static void setSource(final UUID id, final boolean on, final UUID bodyId) {
        if (id == null) {
            return;
        }
        if (on) {
            if (!SOURCES.contains(id)) {
                SOURCES.add(id);
            }
        } else {
            SOURCES.remove(id);
        }
        body = bodyId;
        preview((int) (double) 2.0, 1.0, 1, 0.0);
    }

    /** 某个特征是否已被选为阵列源 ✓（左栏列表勾选态） */
    public static boolean hasSource(final UUID id) {
        return id != null && SOURCES.contains(id);
    }

    public static int sourceCount() {
        return SOURCES.size();
    }

    /** 点棱：第一条 = 方向1；之后（已有源）再点 = 打开对话框；否则追加方向 ✓ */
    public static boolean pickEdge(final FeatureHover.Hit hit) {
        if (hit == null || hit.face() == null || hit.face().segments().isEmpty()) {
            final Minecraft mc0 = Minecraft.getInstance();
            final BoxPick.Hit bh = BoxPick.nearest(mc0);
            if (bh == null || mc0.level == null) {
                ModelMode.prompt("model.pattern.pick_edge");
                return false;
            }
            final double[] loc = BoxPick.toLocal(mc0.level, bh.body(), bh.worldPoint(),
                    bh.worldNormal());
            if (loc == null) {
                ModelMode.prompt("model.pattern.pick_edge");
                return false;
            }
            final double[] segv = BoxPick.nearestEdgeLocal(bh.localBox(),
                    new Vector3d(loc[0], loc[1], loc[2]));
            if (segv == null) {
                ModelMode.prompt("model.pattern.pick_edge");
                return false;
            }
              return addDirection(segv[3] - segv[0], segv[4] - segv[1],
                      segv[5] - segv[2], bh.body(), segv);
        }
        // ★ 容错：判级可能是面/点 ⇒ 取**离命中中心最近的棱**（与圆角同一套思路）
        int edgeIdx = hit.index();
        if (hit.kind() != FeatureHover.Kind.EDGE || edgeIdx < 0
                || edgeIdx >= hit.face().segments().size()) {
            final org.joml.Vector3d p = new org.joml.Vector3d(hit.localHit());
            double best = Double.MAX_VALUE;
            int bestIdx = -1;
            final java.util.List<double[]> all = hit.face().segments();
            for (int i = 0; i < all.size(); i++) {
                final double[] sg = all.get(i);
                final org.joml.Vector3d a = new org.joml.Vector3d(sg[0], sg[1], sg[2]);
                final org.joml.Vector3d ab = new org.joml.Vector3d(sg[3], sg[4], sg[5]).sub(a);
                final double len2 = ab.lengthSquared();
                double tt = len2 < 1.0e-12 ? 0.0 : new org.joml.Vector3d(p).sub(a).dot(ab) / len2;
                tt = Math.max(0.0, Math.min(1.0, tt));
                final double d = new org.joml.Vector3d(a).fma(tt, ab).distance(p);
                if (d < best) {
                    best = d;
                    bestIdx = i;
                }
            }
            // ★ 阈值：面判级时，离棱太远就不许当方向（用户："不应该能选到面作为方向"）
            if (best > 0.35) {
                ModelMode.prompt("model.pattern.pick_edge");
                SablestopNow.LOGGER.info("[pattern] reject: nearest edge dist={} (too far)", best);
                return false;
            }
            edgeIdx = bestIdx;
            SablestopNow.LOGGER.info("[pattern] hover {} -> nearest edge idx={} dist={}",
                    hit.kind(), edgeIdx, best);
        }
        if (edgeIdx < 0 || edgeIdx >= hit.face().segments().size()) {
            ModelMode.prompt("model.pattern.pick_edge");
            return false;
        }
        final double[] seg = hit.face().segments().get(edgeIdx);
        final double dx = seg[3] - seg[0];
        final double dy = seg[4] - seg[1];
        final double dz = seg[5] - seg[2];
        final UUID bodyForDir = hit.body();
        return addDirection(dx, dy, dz, bodyForDir, seg);
    }

    /** 追加一个阵列方向（第一条 = 方向1；已有源特征时再点 = 弹框）✓ */
    private static boolean addDirection(final double dx, final double dy, final double dz,
                                        final UUID bodyForDir, final double[] seg) {
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) < 1.0e-6) {
            return false;
        }
        if (seg != null) {
            DIR_EDGES.add(new double[] {seg[0], seg[1], seg[2], seg[3], seg[4], seg[5]});
        }
        if (DIRS.isEmpty()) {
            DIRS.add(new Solid.Shape.Dir(dx, dy, dz, 2, 1.0));
            body = bodyForDir;
            preview(2, 1.0, 1, 0.0);
            ModelUi.forceRefreshLeft();
            return true;
        }
        if (!SOURCES.isEmpty()) {
            openDialog();
            return true;
        }
        if (DIRS.size() < 3) {
            DIRS.add(new Solid.Shape.Dir(dx, dy, dz, 1, 0.0));
        }
        ModelUi.forceRefreshLeft();
        return true;
    }

    /** 右栏点特征：加入/移出被阵列列表 ✓ */
    public static void toggleSource(final Solid solid) {
        if (solid == null || solid.isPlane() || solid.isFillet() || solid.isMirror()
                || solid.isPattern()) {
            return;
        }
        if (!SOURCES.remove(solid.id())) {
            SOURCES.add(solid.id());
        }
        body = solid.body();
        preview(2, 1.0, 1, 0.0);
        ModelUi.forceRefreshLeft();
        SablestopNow.LOGGER.info("[pattern] sources={}", SOURCES.size());
    }

    /** 左栏"确认"按钮与"再点棱"都走这里 ✓ */
    public static void openDialog() {
        final Minecraft mc = Minecraft.getInstance();
        mc.setScreen(new PatternCreateScreen(mc.screen, DIRS.size(), 2, 1.0, 1, 0.0,
                new PatternCreateScreen.Sink() {
                    @Override
                    public void preview(final int n1, final double s1, final int n2,
                                        final double s2) {
                        PatternTool.preview(n1, s1, n2, s2);
                    }

                    @Override
                    public void create(final int n1, final double s1, final int n2,
                                       final double s2) {
                        PatternTool.create(n1, s1, n2, s2);
                    }

                    @Override
                    public void cancel() {
                        FeaturePreview.clear();
                    }
                }));
    }

    /** 预览：把源特征按偏移复制后的线框画成黄色 ✓ */
    public static void preview(final int n1, final double s1, final int n2, final double s2) {
        FeaturePreview.clear();
        if (body == null || DIRS.isEmpty() || SOURCES.isEmpty()) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final Level level = mc == null ? null : mc.level;
        if (level == null) {
            return;
        }
        final List<double[]> offsets = new ArrayList<>();
        final Solid.Shape.Dir d0 = DIRS.get(0);
        for (int i = 1; i < Math.max(1, n1); i++) {
            offsets.add(new double[] {d0.dx() * s1 * i, d0.dy() * s1 * i, d0.dz() * s1 * i});
        }
        if (DIRS.size() >= 2 && n2 > 1) {
            final Solid.Shape.Dir d1 = DIRS.get(1);
            final List<double[]> base = new ArrayList<>(offsets);
            for (int j = 1; j < n2; j++) {
                for (final double[] o0 : base) {
                    offsets.add(new double[] {o0[0] + d1.dx() * s2 * j,
                            o0[1] + d1.dy() * s2 * j, o0[2] + d1.dz() * s2 * j});
                }
            }
        }
        final List<FeaturePreview.Seg> segs = new ArrayList<>();
        final var sketches = SketchClientState.current(level);
        for (final Solid src : SolidClientState.ofBody(level, body)) {
            if (!SOURCES.contains(src.id())) {
                continue;
            }
            final Sketch sk = src.resolve(sketches);
            if (sk == null) {
                continue;
            }
            for (final double[] off : offsets) {
                for (final Vector3d[] e : src.edges(sk)) {
                    segs.add(new FeaturePreview.Seg(
                            new Vector3d(e[0]).add(off[0], off[1], off[2]),
                            new Vector3d(e[1]).add(off[0], off[1], off[2])));
                }
            }
        }
        FeaturePreview.set(body, null, segs);
    }

    /** 真正创建阵列特征 ✓ */
    public static void create(final int n1, final double s1, final int n2, final double s2) {
        final Minecraft mc = Minecraft.getInstance();
        final Level level = mc == null ? null : mc.level;
        if (level == null || body == null || DIRS.isEmpty() || SOURCES.isEmpty()) {
            ModelMode.prompt("model.pattern.no_sources");
            return;
        }
        int index = 1;
        for (final Solid s : SolidClientState.ofBody(level, body)) {
            index = Math.max(index, s.index() + 1);
        }
        index = Math.max(index, LOCAL_INDEX.getOrDefault(body, 0) + 1);
        final List<Solid.Shape.Dir> dirs = new ArrayList<>();
        dirs.add(new Solid.Shape.Dir(DIRS.get(0).dx(), DIRS.get(0).dy(), DIRS.get(0).dz(),
                Math.max(1, n1), s1));
        if (DIRS.size() >= 2 && n2 > 1) {
            dirs.add(new Solid.Shape.Dir(DIRS.get(1).dx(), DIRS.get(1).dy(), DIRS.get(1).dz(),
                    n2, s2));
        }
        // 挂到第一个源特征的真实草图上（否则树里不会显示）
        java.util.UUID sk = null;
        for (final Solid sd : SolidClientState.ofBody(level, body)) {
            if (SOURCES.contains(sd.id()) && sd.sketchId() != null) {
                sk = sd.sketchId();
                break;
            }
        }
        if (sk == null) {
            for (final Solid sd : SolidClientState.ofBody(level, body)) {
                if (sd.sketchId() != null) {
                    sk = sd.sketchId();
                    break;
                }
            }
        }
        final Solid pat = Solid.createPattern(body, sk, index, dirs, new ArrayList<>(SOURCES));
        SolidNetworking.sendSave(pat);
        LOCAL_INDEX.put(body, pat.index());
        SablestopNow.LOGGER.info("[pattern] created idx={} dirs={} sources={}",
                pat.index(), dirs.size(), SOURCES.size());
        reset();
        ModelUi.refreshTreeNow();
        ModelMode.setTool(ModelTools.NONE);
    }
}
