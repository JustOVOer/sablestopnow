// 离线门禁：草图存档的 NBT 往返。
//
// 存档是最容易"悄悄坏掉"的一层：写进去读不出来、或者读出来的下标错位，
// 都要等到玩家重开世界才发现。这个 harness 直接构造 NBT、读回来、逐字段比对。
//
// 需要 Minecraft 的 NBT 类，所以必须跑在完整 classpath 上（build/moddev/clientLegacyClasspath.txt），
// 不能用 SketchModelCheck 那种轻量 classpath。
//
// 用法：
//   javac -encoding UTF-8 -nowarn -cp "<mc cp>;<jna>;<joml>" -d <out> <sketch 源码> SketchDataCheck.java
//   java  -cp "<out>;<mc cp>;<jna>;<joml>" SketchDataCheck
//
// 最后一行固定输出 SKETCH_DATA_CHECK=OK checks=N failures=0 供脚本 grep。

import com.ovo.sablestopnow.sketch.Sketch;
import com.ovo.sablestopnow.sketch.SketchData;
import com.ovo.sablestopnow.sketch.SketchPlane;
import com.ovo.sablestopnow.sketch.SketchSolver;

import net.minecraft.nbt.CompoundTag;

import org.joml.Vector3d;

import java.util.List;
import java.util.UUID;

public final class SketchDataCheck {

    private static int checks;
    private static int failures;

    private SketchDataCheck() {
    }

    private static void check(final boolean condition, final String label) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("  FAIL " + label);
        }
    }

    private static void eq(final Object actual, final Object expected, final String label) {
        check(actual == null ? expected == null : actual.equals(expected),
                label + ": 期望 " + expected + "，实际 " + actual);
    }

    private static void near(final double actual, final double expected, final String label) {
        check(Math.abs(actual - expected) < 1e-9,
                label + ": 期望 " + expected + "，实际 " + actual);
    }

    public static void main(final String[] args) {
        System.out.println("=== 草图存档 NBT 往返自检 ===");
        roundTrip();
        emptyArchive();
        badRecordIsSkippedNotFatal();
        dropOfBodyRemovesOnlyThatBody();
        clientMirror();
        solidRoundTrip();
        solidBadRecordsSkipped();
        solidDropOfSketch();
        solidNextIndex();
        solidShapesRoundTrip();
        staleBodyReconciliation();
        treeGrouping();
        solidClientMirror();

        System.out.println("=== " + checks + " 项检查，" + failures + " 项失败 ===");
        System.out.println("SKETCH_DATA_CHECK=" + (failures == 0 ? "OK" : "FAIL")
                + " checks=" + checks + " failures=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    /** 原版 NBT 没有 double 数组，手工塞坏记录时也要按 ListTag of TAG_DOUBLE 来造。 */
    private static net.minecraft.nbt.ListTag doubleList(final double[] values) {
        final net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (final double v : values) {
            list.add(net.minecraft.nbt.DoubleTag.valueOf(v));
        }
        return list;
    }

    /** 造一张有平面/变量/图元/约束的草图，看能不能原样读回来。 */
    private static Sketch buildSample() {
        final UUID id = UUID.randomUUID();
        final UUID body = UUID.randomUUID();
        final SketchPlane plane = SketchPlane.onFace(new Vector3d(20481032.5, 127.5, 20519944.0),
                new Vector3d(0, 1, 0));
        final Sketch s = new Sketch(id, body, "Sketch1", plane);

        final Sketch.Entity line = s.addLine(0.0, 0.0, 3.0, 0.0);
        final Sketch.Entity circle = s.addCircle(5.0, 5.0, 2.0);
        final int[] v = line.vars();
        s.addConstraint(SketchSolver.FIXED, 0.0, v[0]);
        s.addConstraint(SketchSolver.HORIZONTAL, 0.0, v[0], v[1], v[2], v[3]);
        s.addConstraint(SketchSolver.DISTANCE, 3.0, v[0], v[1], v[2], v[3]);
        s.addConstraint(SketchSolver.RADIUS, 2.0, circle.vars()[0], circle.vars()[1], circle.vars()[2]);
        return s;
    }

    private static void roundTrip() {
        final Sketch original = buildSample();
        final SketchData data = new SketchData();
        data.put(original);

        final CompoundTag tag = data.save(new CompoundTag(), null);
        final SketchData loaded = SketchData.load(tag, null);
        eq(loaded.all().size(), 1, "存档里应有 1 张草图");

        final Sketch back = loaded.all().get(original.id());
        check(back != null, "按 id 找得回草图");
        if (back == null) {
            return;
        }
        eq(back.id(), original.id(), "id");
        eq(back.body(), original.body(), "所属结构");
        eq(back.name(), original.name(), "名字");

        // 平面：原点与两个轴都要原样回来（轴会被归一化，所以用 1e-9 比较）
        near(back.plane().origin().x, original.plane().origin().x, "平面原点 x");
        near(back.plane().origin().y, original.plane().origin().y, "平面原点 y");
        near(back.plane().origin().z, original.plane().origin().z, "平面原点 z");
        near(back.plane().axisU().dot(original.plane().axisU()), 1.0, "u 轴方向一致");
        near(back.plane().axisV().dot(original.plane().axisV()), 1.0, "v 轴方向一致");
        near(back.plane().normal().dot(original.plane().normal()), 1.0, "法线方向一致");

        // 变量
        eq(back.variableCount(), original.variableCount(), "变量个数");
        for (int i = 0; i < original.variableCount(); i++) {
            near(back.variable(i), original.variable(i), "变量 " + i);
        }

        // 图元：种类与下标都要一致（下标错位会直接让几何散架）
        eq(back.entities().size(), original.entities().size(), "图元个数");
        for (int i = 0; i < original.entities().size(); i++) {
            eq(back.entities().get(i).kind(), original.entities().get(i).kind(), "图元 " + i + " 种类");
            final int[] a = back.entities().get(i).vars();
            final int[] b = original.entities().get(i).vars();
            eq(a.length, b.length, "图元 " + i + " 变量个数");
            for (int k = 0; k < b.length; k++) {
                eq(a[k], b[k], "图元 " + i + " 变量下标 " + k);
            }
        }

        // 约束
        eq(back.constraints().size(), original.constraints().size(), "约束个数");
        for (int i = 0; i < original.constraints().size(); i++) {
            final Sketch.Constraint a = back.constraints().get(i);
            final Sketch.Constraint b = original.constraints().get(i);
            eq(a.kind(), b.kind(), "约束 " + i + " 种类");
            near(a.value(), b.value(), "约束 " + i + " 常量");
            final int[] ao = a.operands();
            final int[] bo = b.operands();
            eq(ao.length, bo.length, "约束 " + i + " 操作数个数");
            for (int k = 0; k < bo.length; k++) {
                eq(ao[k], bo[k], "约束 " + i + " 操作数 " + k);
            }
        }
    }

    /** 空存档也要能存能读（新世界第一次落盘就是这种情况）。 */
    private static void emptyArchive() {
        final SketchData data = new SketchData();
        final CompoundTag tag = data.save(new CompoundTag(), null);
        final SketchData loaded = SketchData.load(tag, null);
        eq(loaded.all().size(), 0, "空存档读回来还是空");
    }

    /**
     * 一条坏记录（变量下标越界）必须被**跳过**，而不是把整份存档带崩。
     *
     * <p>玩家宁可少一张草图，也不愿意整个世界读不出来。
     */
    private static void badRecordIsSkippedNotFatal() {
        final Sketch good = buildSample();
        final SketchData data = new SketchData();
        data.put(good);
        final CompoundTag tag = data.save(new CompoundTag(), null);

        // 手工塞一条越界的记录进去
        final CompoundTag bad = new CompoundTag();
        bad.put("id", net.minecraft.nbt.NbtUtils.createUUID(UUID.randomUUID()));
        bad.put("body", net.minecraft.nbt.NbtUtils.createUUID(UUID.randomUUID()));
        bad.putString("name", "Broken");
        final CompoundTag plane = new CompoundTag();
        plane.putDouble("ox", 0);
        plane.putDouble("oy", 0);
        plane.putDouble("oz", 0);
        plane.putDouble("ux", 1);
        plane.putDouble("uy", 0);
        plane.putDouble("uz", 0);
        plane.putDouble("vx", 0);
        plane.putDouble("vy", 1);
        plane.putDouble("vz", 0);
        bad.put("plane", plane);
        bad.put("vars", doubleList(new double[] {0, 0}));
        final net.minecraft.nbt.ListTag entities = new net.minecraft.nbt.ListTag();
        final CompoundTag e = new CompoundTag();
        e.putString("kind", "LINE");
        e.putIntArray("vars", new int[] {0, 1, 2, 99}); // 99 越界
        entities.add(e);
        bad.put("entities", entities);
        bad.put("constraints", new net.minecraft.nbt.ListTag());
        tag.getList("sketches", net.minecraft.nbt.Tag.TAG_COMPOUND).add(bad);

        final SketchData loaded = SketchData.load(tag, null);
        eq(loaded.all().size(), 1, "坏记录被跳过，好记录还在");
        check(loaded.all().containsKey(good.id()), "活下来的是那条好记录");
    }

    /** 结构没了才清它的草图，别的结构不受影响。 */
    private static void dropOfBodyRemovesOnlyThatBody() {
        final Sketch a = buildSample();
        final Sketch b = buildSample();
        final SketchData data = new SketchData();
        data.put(a);
        data.put(b);
        eq(data.dropOfBody(a.body()), 1, "只清掉目标结构的那一张");
        eq(data.all().size(), 1, "另一张还在");
        check(data.all().containsKey(b.id()), "留下的是另一个结构的草图");
    }

    /**
     * 客户端镜像：服务端广播的整份 NBT 客户端要能解出来，且维度之间互不串。
     *
     * <p>这条覆盖的是网络那条路径 —— 它和存档用的是同一个解码器，
     * 但「按维度分桶」这层逻辑只在客户端有。
     */
    private static void clientMirror() {
        com.ovo.sablestopnow.sketch.SketchClientState.clear();
        final Sketch a = buildSample();
        final Sketch b = buildSample();

        final SketchData overworld = new SketchData();
        overworld.put(a);
        final net.minecraft.resources.ResourceLocation dimA =
                net.minecraft.resources.ResourceLocation.parse("minecraft:overworld");
        final net.minecraft.resources.ResourceLocation dimB =
                net.minecraft.resources.ResourceLocation.parse("minecraft:the_nether");
        com.ovo.sablestopnow.sketch.SketchClientState.accept(dimA,
                overworld.save(new CompoundTag(), null));

        eq(com.ovo.sablestopnow.sketch.SketchClientState.in(dimA).size(), 1, "维度 A 收到 1 张");
        eq(com.ovo.sablestopnow.sketch.SketchClientState.in(dimB).size(), 0, "维度 B 还是空的");
        eq(com.ovo.sablestopnow.sketch.SketchClientState.total(), 1, "总数 1");
        check(com.ovo.sablestopnow.sketch.SketchClientState.byId(dimA, a.id()) != null,
                "按 id 找得到");
        eq(com.ovo.sablestopnow.sketch.SketchClientState.ofBody(dimA, a.body()).size(), 1,
                "按结构找得到");

        // 第二次广播整个替换（不是追加）
        final SketchData second = new SketchData();
        second.put(b);
        com.ovo.sablestopnow.sketch.SketchClientState.accept(dimA,
                second.save(new CompoundTag(), null));
        eq(com.ovo.sablestopnow.sketch.SketchClientState.in(dimA).size(), 1, "整份替换而非追加");
        check(com.ovo.sablestopnow.sketch.SketchClientState.byId(dimA, b.id()) != null,
                "替换后是新那张");
        check(com.ovo.sablestopnow.sketch.SketchClientState.byId(dimA, a.id()) == null,
                "旧那张已经不在了");

        // 坏包不能让客户端崩
        final CompoundTag garbage = new CompoundTag();
        garbage.putString("sketches", "not a list");
        com.ovo.sablestopnow.sketch.SketchClientState.accept(dimB, garbage);
        eq(com.ovo.sablestopnow.sketch.SketchClientState.in(dimB).size(), 0, "坏包不产生草图");

        com.ovo.sablestopnow.sketch.SketchClientState.clear();
        eq(com.ovo.sablestopnow.sketch.SketchClientState.total(), 0, "clear 之后清空");
    }

    // ==================================================================
    // 拉伸实体（SolidData / SolidClientState）
    // ==================================================================

    /** 造一条合法的实体 NBT（引用给定草图）。 */
    private static CompoundTag solidTag(final Sketch sketch, final int index) {
        final com.ovo.sablestopnow.sketch.Solid solid = com.ovo.sablestopnow.sketch.Solid.create(
                sketch.body(), sketch.id(), index,
                com.ovo.sablestopnow.sketch.Extrude.Params.twoSided(5.0, 1.5));
        return com.ovo.sablestopnow.sketch.SolidData.writeSolid(solid);
    }

    /** 实体存档往返：身份、引用、参数一个都不能丢。 */
    private static void solidRoundTrip() {
        final Sketch sketch = buildSample();
        final com.ovo.sablestopnow.sketch.Solid original =
                com.ovo.sablestopnow.sketch.Solid.create(sketch.body(), sketch.id(), 3,
                        com.ovo.sablestopnow.sketch.Extrude.Params.twoSided(5.0, 1.5));

        final com.ovo.sablestopnow.sketch.SolidData data =
                new com.ovo.sablestopnow.sketch.SolidData();
        data.put(original);
        final CompoundTag tag = data.save(new CompoundTag(), null);

        final com.ovo.sablestopnow.sketch.SolidData reloaded =
                com.ovo.sablestopnow.sketch.SolidData.load(tag, null);
        eq(reloaded.all().size(), 1, "往返后有 1 个实体");
        final com.ovo.sablestopnow.sketch.Solid back = reloaded.all().get(original.id());
        check(back != null, "按 id 找得回来");
        if (back == null) {
            return;
        }
        eq(back.id(), original.id(), "id 一致");
        eq(back.body(), original.body(), "所属结构一致");
        eq(back.sketchId(), original.sketchId(), "引用的草图一致");
        eq(back.index(), 3, "序号一致");
        eq(back.extrusion().mode(), com.ovo.sablestopnow.sketch.Extrude.Mode.TWO_SIDED, "拉伸方式一致");
        near(back.extrusion().depth1(), 5.0, "深度 1 一致");
        near(back.extrusion().depth2(), 1.5, "深度 2 一致");

        // 四种模式都要能往返（枚举名字存错了会静默退化成默认值）
        for (final com.ovo.sablestopnow.sketch.Extrude.Mode mode
                : com.ovo.sablestopnow.sketch.Extrude.Mode.values()) {
            final com.ovo.sablestopnow.sketch.Solid s = com.ovo.sablestopnow.sketch.Solid.create(
                    sketch.body(), sketch.id(), 1,
                    new com.ovo.sablestopnow.sketch.Extrude.Params(mode, 2.0, 0.0));
            final com.ovo.sablestopnow.sketch.Solid r = com.ovo.sablestopnow.sketch.SolidData.readSolid(
                    com.ovo.sablestopnow.sketch.SolidData.writeSolid(s));
            check(r != null && r.extrusion().mode() == mode, "拉伸方式 " + mode + " 往返不变");
        }
    }

    /**
     * 坏记录逐条跳过 —— 一条烂实体不能让整份存档读不出来。
     *
     * <p>顺带钉住"方式存名字不存序号"这个决定：认不出的名字要**读失败**（被跳过），
     * 而不是退化成某个默认方向 —— 后者会让存档里的实体悄悄转了 90 度。
     */
    private static void solidBadRecordsSkipped() {
        final Sketch sketch = buildSample();
        final CompoundTag tag = new CompoundTag();
        final net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();

        list.add(solidTag(sketch, 1)); // 好的

        // 认不出的拉伸方式
        final CompoundTag unknownMode = solidTag(sketch, 2);
        unknownMode.putString("mode", "DIAGONAL_FROM_MARS");
        list.add(unknownMode);

        // 深度为 0（构造器会拒绝）
        final CompoundTag zeroDepth = solidTag(sketch, 2);
        zeroDepth.putDouble("d1", 0.0);
        list.add(zeroDepth);

        // 负深度
        final CompoundTag negative = solidTag(sketch, 2);
        negative.putDouble("d1", -4.0);
        list.add(negative);

        // 引用缺失：没有 sketch 字段
        final CompoundTag noSketch = solidTag(sketch, 2);
        noSketch.remove("sketch");
        list.add(noSketch);

        // 序号为 0
        final CompoundTag zeroIndex = solidTag(sketch, 1);
        zeroIndex.putInt("index", 0);
        list.add(zeroIndex);

        // 完全不是那么回事
        final CompoundTag nonsense = new CompoundTag();
        nonsense.putString("id", "not-a-uuid");
        list.add(nonsense);

        tag.put("solids", list);
        final com.ovo.sablestopnow.sketch.SolidData loaded =
                com.ovo.sablestopnow.sketch.SolidData.load(tag, null);
        eq(loaded.all().size(), 1, "7 条里只有那 1 条好的活下来");

        // 坏包也不许崩
        final CompoundTag garbage = new CompoundTag();
        garbage.putString("solids", "not a list");
        eq(com.ovo.sablestopnow.sketch.SolidData.load(garbage, null).all().size(), 0,
                "整个 solids 字段类型不对 → 空表而不是异常");
    }

    /** 草图被删时连带清掉引用它的实体；别的草图的实体不受影响。 */
    private static void solidDropOfSketch() {
        final Sketch a = buildSample();
        final Sketch b = buildSample();
        final com.ovo.sablestopnow.sketch.SolidData data =
                new com.ovo.sablestopnow.sketch.SolidData();
        final com.ovo.sablestopnow.sketch.Solid sa1 = com.ovo.sablestopnow.sketch.Solid.create(
                a.body(), a.id(), 1, com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        final com.ovo.sablestopnow.sketch.Solid sa2 = com.ovo.sablestopnow.sketch.Solid.create(
                a.body(), a.id(), 2, com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        final com.ovo.sablestopnow.sketch.Solid sb = com.ovo.sablestopnow.sketch.Solid.create(
                b.body(), b.id(), 1, com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        data.put(sa1);
        data.put(sa2);
        data.put(sb);

        eq(data.dropOfSketch(a.id()), 2, "删草图 A 连带清掉引用它的 2 个实体");
        eq(data.all().size(), 1, "草图 B 的实体还在");
        check(data.all().containsKey(sb.id()), "留下的是引用草图 B 的那个");
        eq(data.dropOfSketch(a.id()), 0, "再删一次没有可清的（幂等）");

        // 按结构清
        eq(data.dropOfBody(b.body()), 1, "按结构清掉 B 的实体");
        eq(data.all().size(), 0, "清空了");
    }

    /**
     * 实体有两种成形方式（拉伸 / 旋转），存档必须都往返得回来。
     *
     * <p>⚠ 还要守住**旧存档兼容**：`shape` 字段是后来才加的，老记录里没有它。
     * 缺字段必须当成"拉伸"读回来 —— 不兜底的话，升级之后玩家所有旧实体都会读失败被跳过，
     * 表现就是"我的模型全没了"，而且看起来像存档损坏。
     */
    private static void solidShapesRoundTrip() {
        final Sketch sketch = buildSample();

        // --- 旋转实体往返 ---
        final com.ovo.sablestopnow.sketch.Solid original =
                com.ovo.sablestopnow.sketch.Solid.createRevolution(sketch.body(), sketch.id(), 4,
                        new com.ovo.sablestopnow.sketch.Revolve.Params(1.5, -2.5, 0.0, 1.0,
                                137.5, -1));
        final com.ovo.sablestopnow.sketch.SolidData data =
                new com.ovo.sablestopnow.sketch.SolidData();
        data.put(original);
        final com.ovo.sablestopnow.sketch.SolidData reloaded =
                com.ovo.sablestopnow.sketch.SolidData.load(
                        data.save(new CompoundTag(), null), null);
        final com.ovo.sablestopnow.sketch.Solid back = reloaded.all().get(original.id());
        check(back != null, "旋转实体往返后找得回来");
        if (back != null) {
            check(back.isRevolution(), "往返后仍然是旋转实体");
            final com.ovo.sablestopnow.sketch.Revolve.Params p = back.revolution();
            check(p != null, "旋转参数在");
            if (p != null) {
                near(p.axisU(), 1.5, "轴点 u 一致");
                near(p.axisV(), -2.5, "轴点 v 一致");
                near(p.dirU(), 0.0, "轴方向 u 一致");
                near(p.dirV(), 1.0, "轴方向 v 一致");
                near(p.angleDeg(), 137.5, "角度一致");
                check(p.direction() == -1, "旋向一致（−1）");
            }
            eq(back.index(), 4, "序号一致");
        }

        // --- ⚠ 旧存档兼容：手工造一条**没有 shape 字段**的老记录 ---
        final com.ovo.sablestopnow.sketch.Solid oldStyle =
                com.ovo.sablestopnow.sketch.Solid.create(sketch.body(), sketch.id(), 2,
                        com.ovo.sablestopnow.sketch.Extrude.Params.twoSided(3.0, 1.0));
        final CompoundTag legacy = com.ovo.sablestopnow.sketch.SolidData.writeSolid(oldStyle);
        legacy.remove("shape"); // 模拟升级前的记录
        final com.ovo.sablestopnow.sketch.Solid legacyRead =
                com.ovo.sablestopnow.sketch.SolidData.readSolid(legacy);
        check(legacyRead != null, "缺 shape 字段的旧记录**必须**读得出来（否则玩家的模型全没了）");
        if (legacyRead != null) {
            check(!legacyRead.isRevolution(), "旧记录当成拉伸读");
            near(legacyRead.extrusion().depth1(), 3.0, "旧记录的深度 1 保住了");
            near(legacyRead.extrusion().depth2(), 1.0, "旧记录的深度 2 保住了");
        }

        // --- 坏旋转参数（零方向）→ 跳过而不是崩 ---
        final CompoundTag bad = com.ovo.sablestopnow.sketch.SolidData.writeSolid(
                com.ovo.sablestopnow.sketch.Solid.createRevolution(sketch.body(), sketch.id(), 1,
                        new com.ovo.sablestopnow.sketch.Revolve.Params(0, 0, 0, 1, 90, 1)));
        bad.putDouble("du", 0.0);
        bad.putDouble("dv", 0.0);
        check(com.ovo.sablestopnow.sketch.SolidData.readSolid(bad) == null,
                "轴方向为零的旋转记录 → 跳过（不抛）");

        // --- 认不出的 shape 名字 → 兜底成拉伸（而不是把实体整条丢掉）---
        final CompoundTag unknown = com.ovo.sablestopnow.sketch.SolidData.writeSolid(oldStyle);
        unknown.putString("shape", "teleportation");
        check(com.ovo.sablestopnow.sketch.SolidData.readSolid(unknown) != null,
                "认不出的 shape 名字 → 兜底成拉伸（宁可读成拉伸，也别让实体凭空消失）");
    }

    /** 实体序号：同一结构内 max+1，别的结构互不影响。 */
    private static void solidNextIndex() {
        final Sketch a = buildSample();
        final Sketch b = buildSample();
        final com.ovo.sablestopnow.sketch.SolidData data =
                new com.ovo.sablestopnow.sketch.SolidData();
        eq(data.nextIndex(a.body()), 1, "空表上第一个序号是 1");
        data.put(com.ovo.sablestopnow.sketch.Solid.create(a.body(), a.id(), 1,
                com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(1.0)));
        data.put(com.ovo.sablestopnow.sketch.Solid.create(a.body(), a.id(), 7,
                com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(1.0)));
        eq(data.nextIndex(a.body()), 8, "取最大 + 1（不是数量 + 1）");
        eq(data.nextIndex(b.body()), 1, "别的结构从 1 开始");
    }

    /**
     * 对账清理的**决策**：哪些 body 上的实体该清掉。
     *
     * <p>⚠ 最要紧的一条是**不对称性**：
     * "一个活着的子结构都没看到" 必须当成"还没准备好"，而不是"全都没了"。
     * 前者是容器还没加载（刚进世界 / 维度刚加载），照常清就会把**存档里所有实体删光**，
     * 而且不可恢复。漏清一个只是留个幽灵 —— 两个方向的代价差着量级。
     */
    private static void staleBodyReconciliation() {
        final UUID a = UUID.randomUUID();
        final UUID b = UUID.randomUUID();
        final UUID c = UUID.randomUUID();

        check(com.ovo.sablestopnow.sketch.SolidData
                .staleBodies(java.util.List.of(a, b), java.util.List.of(a, b, c)).isEmpty(),
                "全活着 → 没有要清的");

        final java.util.Set<UUID> one = com.ovo.sablestopnow.sketch.SolidData
                .staleBodies(java.util.List.of(a, b), java.util.List.of(a));
        eq(one.size(), 1, "只剩下 a 活着 → 清 1 个");
        check(one.contains(b), "清的是 b（不在了的那个），不是 a");

        eq(com.ovo.sablestopnow.sketch.SolidData
                .staleBodies(java.util.List.of(a, b), java.util.List.of(c)).size(), 2,
                "两个都不在了 → 清 2 个");

        // --- ⚠ 关键守卫：alive 为空 —— 一个都别动 ---
        check(com.ovo.sablestopnow.sketch.SolidData
                .staleBodies(java.util.List.of(a, b), java.util.List.of()).isEmpty(),
                "**alive 为空时一个都不清** —— 那更可能是容器还没加载好，"
                        + "照常清会把存档里所有实体删光");
        check(com.ovo.sablestopnow.sketch.SolidData
                .staleBodies(java.util.List.of(a, b), null).isEmpty(),
                "alive 为 null 也不清（更早形式的「没准备好」）");

        check(com.ovo.sablestopnow.sketch.SolidData
                .staleBodies(java.util.List.of(), java.util.List.of(a)).isEmpty(),
                "没有任何实体引用 → 没什么可清");
        check(com.ovo.sablestopnow.sketch.SolidData
                .staleBodies(null, java.util.List.of(a)).isEmpty(), "referenced 为 null → 空");
        // 幂等：清完之后再算一次应当为空
        check(com.ovo.sablestopnow.sketch.SolidData
                .staleBodies(java.util.List.of(a), java.util.List.of(a)).isEmpty(),
                "清完再算一次 → 空（幂等，可以每秒跑）");
        // null 元素不该把整批带崩
        check(com.ovo.sablestopnow.sketch.SolidData
                .staleBodies(java.util.Arrays.asList(a, null), java.util.List.of(a)).isEmpty(),
                "列表里有 null 也不崩、也不算要清");
    }

    /**
     * 设计树分组：草图的"吸收"规则（对齐 SolidWorks 的 FeatureManager）。
     *
     * <p>被特征用掉的草图吸收到特征下，没被用掉的留在顶层；
     * 一张草图被两个特征用时**两处都挂**；指向已删除草图时不挂（别画出点不开的节点）。
     */
    private static void treeGrouping() {
        final Sketch a = buildSample();
        final Sketch b = buildSample();
        // 两张"没用掉"的草图 id
        final java.util.List<java.util.UUID> sketchIds =
                new java.util.ArrayList<>(java.util.List.of(a.id(), b.id()));
        final java.util.List<com.ovo.sablestopnow.sketch.Solid> solids =
                new java.util.ArrayList<>();

        // --- 都没有特征 → 两张都自由 ---
        com.ovo.sablestopnow.sketch.Solid.TreeGrouping g =
                com.ovo.sablestopnow.sketch.Solid.groupSketches(solids, sketchIds);
        eq(g.freeSketches().size(), 2, "没有特征时两张草图都自由");
        eq(g.absorbed().size(), 0, "没有特征时没有吸收关系");

        // --- a 被一个特征用掉 → 吸收；b 仍自由 ---
        final com.ovo.sablestopnow.sketch.Solid s1 =
                com.ovo.sablestopnow.sketch.Solid.create(a.body(), a.id(), 1,
                        com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));
        solids.add(s1);
        g = com.ovo.sablestopnow.sketch.Solid.groupSketches(solids, sketchIds);
        eq(g.freeSketches().size(), 1, "a 被用掉后只剩 1 张自由草图");
        check(g.freeSketches().contains(b.id()), "自由的是 b");
        eq(g.absorbedCount(s1.id()), 1, "s1 下面挂了 1 张草图");
        eq(g.consumerCount(a.id()), 1, "a 被 1 个特征使用");

        // --- 同一张草图再被第二个特征用 → 两处都挂，且都被吸收 ---
        final com.ovo.sablestopnow.sketch.Solid s2 =
                com.ovo.sablestopnow.sketch.Solid.create(a.body(), a.id(), 2,
                        com.ovo.sablestopnow.sketch.Extrude.Params.symmetric(3.0));
        solids.add(s2);
        g = com.ovo.sablestopnow.sketch.Solid.groupSketches(solids, sketchIds);
        eq(g.absorbedCount(s1.id()), 1, "s1 下仍有 1 张");
        eq(g.absorbedCount(s2.id()), 1, "s2 下也挂了同一张（**两处都挂**）");
        eq(g.consumerCount(a.id()), 2,
                "a 被 2 个特征使用 —— UI 要拿这个提示「改草图会影响 2 处」");
        eq(g.freeSketches().size(), 1, "a 仍不算自由（只要被用掉就不在顶层）");

        // --- 指向已删除草图 → 不挂上去（别画出点不开的节点）---
        final java.util.List<java.util.UUID> onlyB = java.util.List.of(b.id());
        g = com.ovo.sablestopnow.sketch.Solid.groupSketches(solids, onlyB);
        eq(g.absorbed().size(), 0, "草图表里没有 a 时，指向 a 的特征不产生吸收关系");
        eq(g.freeSketches().size(), 1, "只剩 b 自由");
        eq(g.consumerCount(a.id()), 0, "a 已不存在 → 使用数为 0");

        // --- 空输入 ---
        g = com.ovo.sablestopnow.sketch.Solid.groupSketches(java.util.List.of(),
                java.util.List.of());
        check(g.freeSketches().isEmpty() && g.absorbed().isEmpty(), "全空 → 全空");
        g = com.ovo.sablestopnow.sketch.Solid.groupSketches(null, null);
        check(g.freeSketches().isEmpty() && g.absorbed().isEmpty(), "null 也不崩");
        // absorbedCount 对不存在的特征要返回 0，不是 null 解引用
        eq(g.absorbedCount(java.util.UUID.randomUUID()), 0, "查不存在的特征 → 0（不抛）");
    }

    /**
     * 客户端实体镜像：整份替换、按维度分桶，并且能把实体和**草图镜像**拼出盒子。
     *
     * <p>最后一条是关键 —— 实体镜像里没有几何，盒子要靠草图镜像现算。
     * 草图没同步过来时应当**跳过**（画不出来），而不是抛异常或画出错位的东西。
     */
    private static void solidClientMirror() {
        com.ovo.sablestopnow.sketch.SolidClientState.clear();
        com.ovo.sablestopnow.sketch.SketchClientState.clear();

        // 造一张真能拉出盒子的草图：4×3 矩形
        final com.ovo.sablestopnow.sketch.SketchSession session =
                new com.ovo.sablestopnow.sketch.SketchSession();
        session.beginPlaneChoice(UUID.randomUUID());
        session.choosePlane(SketchPlane.base(SketchPlane.Base.FRONT, new Vector3d()), "Sketch1");
        session.setTool(com.ovo.sablestopnow.sketch.SketchSession.Tool.RECTANGLE);
        session.click(0.0, 0.0);
        session.click(4.0, 3.0);
        final Sketch rect = session.working();

        final com.ovo.sablestopnow.sketch.Solid solid = com.ovo.sablestopnow.sketch.Solid.create(
                rect.body(), rect.id(), 1,
                com.ovo.sablestopnow.sketch.Extrude.Params.oneSided(2.0));

        final net.minecraft.resources.ResourceLocation dim =
                net.minecraft.resources.ResourceLocation.parse("minecraft:overworld");
        final com.ovo.sablestopnow.sketch.SolidData solids =
                new com.ovo.sablestopnow.sketch.SolidData();
        solids.put(solid);
        com.ovo.sablestopnow.sketch.SolidClientState.accept(dim,
                solids.save(new CompoundTag(), null));

        eq(com.ovo.sablestopnow.sketch.SolidClientState.in(dim).size(), 1, "维度收到 1 个实体");
        check(com.ovo.sablestopnow.sketch.SolidClientState.byId(dim, solid.id()) != null,
                "按 id 找得到实体");
        eq(com.ovo.sablestopnow.sketch.SolidClientState.ofBody(dim, solid.body()).size(), 1,
                "按结构找得到实体");

        // ⚠ 草图镜像还是空的 → 拼不出盒子，必须安静地跳过
        eq(com.ovo.sablestopnow.sketch.SolidClientState.placed(dim).size(), 0,
                "草图还没同步过来时 placed 为空（画不出来，但不抛）");

        // 补上草图镜像 → 盒子就出来了
        final SketchData sketches = new SketchData();
        sketches.put(rect);
        com.ovo.sablestopnow.sketch.SketchClientState.accept(dim,
                sketches.save(new CompoundTag(), null));
        final List<com.ovo.sablestopnow.sketch.Solid.Placed> placed =
                com.ovo.sablestopnow.sketch.SolidClientState.placed(dim);
        eq(placed.size(), 1, "草图到位后 placed 有 1 项");
        check(!placed.get(0).boxes().isEmpty(), "拼出来的盒子非空");

        // 实体引用的草图被删（镜像里没了）→ 又变回空，不是崩
        final SketchData others = new SketchData();
        com.ovo.sablestopnow.sketch.SketchClientState.accept(dim,
                others.save(new CompoundTag(), null));
        eq(com.ovo.sablestopnow.sketch.SolidClientState.placed(dim).size(), 0,
                "草图镜像没了 → 实体画不出来（不崩）");

        // 整份替换
        final com.ovo.sablestopnow.sketch.SolidData second =
                new com.ovo.sablestopnow.sketch.SolidData();
        com.ovo.sablestopnow.sketch.SolidClientState.accept(dim,
                second.save(new CompoundTag(), null));
        eq(com.ovo.sablestopnow.sketch.SolidClientState.in(dim).size(), 0, "整份替换而非追加");

        // 坏包
        final CompoundTag garbage = new CompoundTag();
        garbage.putString("solids", "not a list");
        com.ovo.sablestopnow.sketch.SolidClientState.accept(dim, garbage);
        eq(com.ovo.sablestopnow.sketch.SolidClientState.in(dim).size(), 0, "坏包不产生实体");

        com.ovo.sablestopnow.sketch.SolidClientState.clear();
        eq(com.ovo.sablestopnow.sketch.SolidClientState.total(), 0, "clear 之后清空");
        com.ovo.sablestopnow.sketch.SketchClientState.clear();
    }
}
