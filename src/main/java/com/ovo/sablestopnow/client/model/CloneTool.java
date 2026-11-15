package com.ovo.sablestopnow.client.model;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.network.SolidNetworking;
import com.ovo.sablestopnow.sketch.Sketch;
import com.ovo.sablestopnow.sketch.SketchClientState;
import com.ovo.sablestopnow.sketch.Solid;
import com.ovo.sablestopnow.sketch.SolidClientState;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.joml.Matrix3d;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * **克隆工具**的状态机与创建逻辑。
 *
 * <p>与阵列的关系：同样是"选特征 → 复制"，但克隆**只产生一个副本**，
 * 并且参数是连续量（三轴平移 + 绕公共中心的三轴旋转 + 等比缩放），
 * 全部在左侧栏调整、实时线框预览。
 *
 * <p>几何求值在 {@code Solid.placeAll} 里：取源特征**评估后**的几何
 *（已扣切割、已倒圆角）再做变换 ⇒ 克隆体和源看起来完全一致。
 */
public final class CloneTool {

    private CloneTool() {
    }

    private static final List<UUID> SOURCES = new ArrayList<>();
    private static UUID body;

    /** 预览几何缓存：只有"选中的特征/草图/贴图"变了才重跑求值（CSG 很贵）。 */
    private static String previewSig = "";
    private static List<Solid.SelectedEval> previewSelected = List.of();
    /** 参数没变就不重复把成千上万条线框做变换（每帧都会调 preview）。 */
    private static String lastSegSig = "";

    public static void reset() {
        SOURCES.clear();
        body = null;
        previewSig = "";
        previewSelected = List.of();
        lastSegSig = "";
        FeaturePreview.clear();
    }

    /** 当前克隆作用的体（面板据此列特征）。 */
    public static UUID bodyId() {
        return body;
    }

    public static int sourceCount() {
        return SOURCES.size();
    }

    public static boolean hasSource(final UUID id) {
        return id != null && SOURCES.contains(id);
    }

    /**
     * 进入工具时默认勾上该体全部**材料特征**（拉伸/旋转）。
     *
     * <p>切割、圆角、镜像、阵列、克隆本身不单列：克隆取的是材料特征**评估后**的几何，
     * 切割与圆角已经包含在里面 ✓（这也是它比阵列更"所见即所得"的地方）。
     */
    public static void prefill(final UUID bodyId, final List<Solid> solids) {
        body = bodyId;
        SOURCES.clear();
        if (solids == null) {
            return;
        }
        for (final Solid sd : solids) {
            if (sd.shape() instanceof Solid.Shape.Extrusion
                    || sd.shape() instanceof Solid.Shape.Revolution) {
                SOURCES.add(sd.id());
            }
        }
    }

    /** 编辑已有克隆时**精确**回填源集合（不保留预填的其它材料特征）。 */
    public static void setSources(final UUID bodyId, final List<UUID> sources) {
        body = bodyId;
        SOURCES.clear();
        if (sources != null) {
            SOURCES.addAll(sources);
        }
    }

    /** 清空源选择但**保留当前结构**（左侧栏"清空选择"用）。 */
    public static void clearSources() {
        SOURCES.clear();
    }

    /** 从复选框 Prop 反推源集合（不依赖 onChange 的时序）。 */
    public static void syncFromProps(
            final Map<UUID, com.ovo.sablestopnow.client.gui.lib.Prop<Boolean>> props) {
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

    /** 复选框回调：设置/取消某个源特征。 */
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
    }

    /** 按当前参数造形状（**不发送**）；缺源时返回 null。 */
    public static Solid.Shape.Clone buildShape(final double tx, final double ty, final double tz,
                                               final double rx, final double ry, final double rz,
                                               final double scale) {
        if (SOURCES.isEmpty()) {
            return null;
        }
        return new Solid.Shape.Clone(tx, ty, tz, rx, ry, rz,
                Math.max(0.05, Math.min(16.0, scale)), new ArrayList<>(SOURCES));
    }

    /**
     * 按**当前选中的特征**准备一份只包含它们的 Placed 列表。
     *
     * <p>关键：不能直接用 {@link SolidClientState#placed(Level)} —— 那是整个 body
     * 的最终结果（包含所有切割/圆角），只勾一个拉伸时会把它一起克隆出来
     *（用户实测"只选拉伸却把所有特征都克隆了"）。这里把选中的材料/切割/圆角
     * 单独喂给 {@link Solid#placeAll}，与 {@code Shape.Clone} 求值时的口径一致。
     *
     * <p>CSG 很贵 ⇒ 只在"选中集合 / 特征 / 草图"变化时重算，拖动平移/旋转/缩放
     * 滑条时直接复用这份几何（只做变换）。
     */
    private static void ensurePlaced(final Level level) {
        if (level == null) {
            return;
        }
        final List<Solid> all = SolidClientState.ofBody(level, body);
        final List<Sketch> sketches = SketchClientState.current(level);
        final StringBuilder sig = new StringBuilder();
        sig.append(body).append('|');
        for (final Solid s : all) {
            if (SOURCES.contains(s.id())) {
                sig.append(s.id()).append(':').append(s.hashCode()).append(';');
            }
        }
        for (final Sketch k : sketches) {
            sig.append(k.id()).append(':')
                    .append(java.util.Arrays.hashCode(k.variables())).append(':')
                    .append(k.constraints().size()).append(':')
                    .append(k.entities().size()).append(';');
        }
        final String now = sig.toString();
        if (now.equals(previewSig)) {
            return;
        }
        previewSig = now;
        final long t0 = System.nanoTime();
        previewSelected = Solid.evaluateSelected(all, sketches, SOURCES);
        final long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (ms >= 100L) {
            SablestopNow.LOGGER.info("[perf] clone preview geometry ms={} sources={}",
                    ms, SOURCES.size());
        }
    }

    /**
     * 所有选中特征的**公共中心**（实际几何包围盒中心）。
     *
     * <p>用选中特征求值后的 {@code boxes} 而不是草图包围盒：切割/圆角会把实际形体缩进去，
     * 用草图包围盒算出来的中心会偏。没有源/几何时返回 null。
     */
    public static Vector3d center(final Level level) {
        if (level == null || SOURCES.isEmpty()) {
            return null;
        }
        ensurePlaced(level);
        final List<double[]> boxes = new ArrayList<>();
        for (final Solid.SelectedEval se : previewSelected) {
            final double[] b = se.bounds();
            if (b != null) {
                boxes.add(b);
            }
        }
        return boxes.isEmpty() ? null : Solid.centerOfBoxes(boxes);
    }

    /**
     * 预览：黄色 = 克隆副本，青色 = 公共中心的三轴短十字。
     *
     * <p>源线框来自"只选中那些特征"的求值结果：选了切割/圆角就带上，
     * 没选就不带 —— 和真正创建出来的克隆体一致。
     */
    public static void preview(final double tx, final double ty, final double tz,
                               final double rx, final double ry, final double rz,
                               final double scale) {
        FeaturePreview.clear();
        if (body == null || SOURCES.isEmpty()) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final Level level = mc == null ? null : mc.level;
        if (level == null) {
            return;
        }
        ensurePlaced(level);
        final List<double[]> srcBoxes = new ArrayList<>();
        for (final Solid.SelectedEval se : previewSelected) {
            final double[] b = se.bounds();
            if (b != null) {
                srcBoxes.add(b);
            }
        }
        if (srcBoxes.isEmpty()) {
            return;
        }
        final String segSig = body + "|" + previewSig + "|"
                + String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f,%.3f,%.3f,%.3f,%.4f",
                        tx, ty, tz, rx, ry, rz, scale);
        if (segSig.equals(lastSegSig) && FeaturePreview.active()) {
            return; // 参数没变：保留上一帧的预览，别再逐条变换
        }
        lastSegSig = segSig;
        final Vector3d center = Solid.centerOfBoxes(srcBoxes);
        final Matrix3d rot = Solid.cloneRotation(rx, ry, rz);
        final double s = Math.max(0.05, Math.min(16.0, scale));
        // ① 产物预览：每个选中的材料特征线框做同一套变换
        final List<FeaturePreview.Seg> segs = new ArrayList<>();
        for (final Solid.SelectedEval se : previewSelected) {
            for (final Vector3d[] e : se.edges()) {
                segs.add(new FeaturePreview.Seg(
                        Solid.clonePoint(e[0], center, rot, s, tx, ty, tz),
                        Solid.clonePoint(e[1], center, rot, s, tx, ty, tz)));
            }
        }
        // ② 参考：公共中心（青色三轴十字，长度 0.5 格）
        final List<FeaturePreview.Seg> refs = new ArrayList<>();
        final double k = 0.5;
        refs.add(new FeaturePreview.Seg(
                new Vector3d(center.x - k, center.y, center.z),
                new Vector3d(center.x + k, center.y, center.z)));
        refs.add(new FeaturePreview.Seg(
                new Vector3d(center.x, center.y - k, center.z),
                new Vector3d(center.x, center.y + k, center.z)));
        refs.add(new FeaturePreview.Seg(
                new Vector3d(center.x, center.y, center.z - k),
                new Vector3d(center.x, center.y, center.z + k)));
        // 顺序固定：set() 会清参考，最后 setRefs ✓
        FeaturePreview.set(body, null, segs);
        FeaturePreview.setRefs(body, refs);
    }

    /** 真正创建克隆特征。 */
    public static void create(final double tx, final double ty, final double tz,
                              final double rx, final double ry, final double rz,
                              final double scale) {
        final Minecraft mc = Minecraft.getInstance();
        final Level level = mc == null ? null : mc.level;
        if (level == null || body == null || SOURCES.isEmpty()) {
            ModelMode.prompt("model.clone.no_sources");
            return;
        }
        int index = 1;
        for (final Solid s : SolidClientState.ofBody(level, body)) {
            index = Math.max(index, s.index() + 1);
        }
        // 挂到第一个源特征的真实草图上（否则树里不会显示）
        UUID sk = null;
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
        if (sk == null) {
            ModelMode.prompt("model.order.missing_sketch");
            return;
        }
        final Solid clone = Solid.createClone(body, sk, index,
                tx, ty, tz, rx, ry, rz, Math.max(0.05, Math.min(16.0, scale)),
                new ArrayList<>(SOURCES));
        SolidNetworking.sendSave(clone);
        SablestopNow.LOGGER.info(
                "[clone] created idx={} sources={} t=({},{},{}) r=({},{},{}) s={}",
                clone.index(), SOURCES.size(), tx, ty, tz, rx, ry, rz, scale);
        reset();
        ModelUi.refreshTreeNow();
        ModelMode.setTool(ModelTools.NONE);
    }
}
