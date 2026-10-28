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
    private static final Map<UUID, Integer> LOCAL_INDEX = new HashMap<>();
    private static UUID body;

    public static void reset() {
        DIRS.clear();
        SOURCES.clear();
        body = null;
        FeaturePreview.clear();
    }

    public static int dirCount() {
        return DIRS.size();
    }

    public static int sourceCount() {
        return SOURCES.size();
    }

    /** 点棱：第一条 = 方向1；之后（已有源）再点 = 打开对话框；否则追加方向 ✓ */
    public static boolean pickEdge(final FeatureHover.Hit hit) {
        if (hit == null || hit.kind() != FeatureHover.Kind.EDGE
                || hit.index() < 0 || hit.index() >= hit.face().segments().size()) {
            ModelMode.prompt("model.pattern.pick_edge");
            return false;
        }
        final double[] seg = hit.face().segments().get(hit.index());
        final double dx = seg[3] - seg[0];
        final double dy = seg[4] - seg[1];
        final double dz = seg[5] - seg[2];
        if (Math.sqrt(dx * dx + dy * dy + dz * dz) < 1.0e-6) {
            return false;
        }
        if (DIRS.isEmpty()) {
            DIRS.add(new Solid.Shape.Dir(dx, dy, dz, 2, 1.0));
            body = hit.body();
            preview(2, 1.0, 1, 0.0);
            return true;
        }
        if (!SOURCES.isEmpty()) {
            openDialog();
            return true;
        }
        if (DIRS.size() < 3) {
            DIRS.add(new Solid.Shape.Dir(dx, dy, dz, 1, 0.0));
        }
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
        final Solid pat = Solid.createPattern(body, index, dirs, new ArrayList<>(SOURCES));
        SolidNetworking.sendSave(pat);
        LOCAL_INDEX.put(body, pat.index());
        SablestopNow.LOGGER.info("[pattern] created idx={} dirs={} sources={}",
                pat.index(), dirs.size(), SOURCES.size());
        reset();
        ModelUi.refreshTreeNow();
        ModelMode.setTool(ModelTools.NONE);
    }
}
