// 离线复现：从存档读取草图/实体，直接跑旋转切割 CSG，不启动游戏。
// 编译/运行需要完整 Minecraft classpath（本仓库 build/moddev/artifacts/*merged.jar）。
// 输出：每个 RevolutionCut 对应的 target/cutter/result 体积与 OBJ（csg-dump-probe/）。

import com.ovo.sablestopnow.sketch.Revolve;
import com.ovo.sablestopnow.sketch.ManifoldNative;
import com.ovo.sablestopnow.sketch.Sketch;
import com.ovo.sablestopnow.sketch.SketchData;
import com.ovo.sablestopnow.sketch.Solid;
import com.ovo.sablestopnow.sketch.SolidData;
import com.ovo.sablestopnow.sketch.SolidMesh;
import com.ovo.sablestopnow.sketch.csg.Csg;
import com.ovo.sablestopnow.sketch.csg.CsgMesh;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class RevolutionCutProbe {

    private RevolutionCutProbe() {
    }

    public static void main(String[] args) throws Exception {
        final Path save = Path.of(args.length > 0 ? args[0]
                : "run/saves/New World");
        final CompoundTag solidTag = NbtIo.readCompressed(
                save.resolve("data/sablestopnow_solids.dat"),
                NbtAccounter.unlimitedHeap());
        final CompoundTag sketchTag = NbtIo.readCompressed(
                save.resolve("data/sablestopnow_sketches.dat"),
                NbtAccounter.unlimitedHeap());
        final SolidData solids = SolidData.load(solidTag.getCompound("data"), null);
        final SketchData sketches = SketchData.load(sketchTag.getCompound("data"), null);
        System.out.println("solidTag keys=" + solidTag.getAllKeys()
                + " sketchTag keys=" + sketchTag.getAllKeys());
        System.out.println("solids=" + solids.all().size()
                + " sketches=" + sketches.all().size());
        final List<String> dumped = new ArrayList<>();
        for (final Solid target : solids.all().values()) {
            if (target.shape() instanceof Solid.Shape.Cut
                    || target.shape() instanceof Solid.Shape.Plane
                    || target.shape() instanceof Solid.Shape.Fillet
                    || target.shape() instanceof Solid.Shape.Mirror
                    || target.shape() instanceof Solid.Shape.Pattern
                    || target.shape() instanceof Solid.Shape.Clone
                    || target.shape() instanceof Solid.Shape.RevolutionCut) {
                continue;
            }
            final Sketch targetSketch = sketches.all().get(target.sketchId());
            if (targetSketch == null) {
                continue;
            }
            final SolidMesh.Mesh targetMesh = target.mesh(targetSketch);
            if (targetMesh == null || targetMesh.isEmpty()) {
                continue;
            }
            for (final Solid cut : solids.all().values()) {
                if (!(cut.shape() instanceof final Solid.Shape.RevolutionCut rc)) {
                    continue;
                }
                if (!cut.body().equals(target.body())) {
                    continue;
                }
                final Sketch cutSketch = sketches.all().get(cut.sketchId());
                if (cutSketch == null) {
                    continue;
                }
                final SolidMesh.Mesh cutterMesh = Revolve.mesh(cutSketch, rc.params(), 0.02);
                if (cutterMesh == null || cutterMesh.isEmpty()) {
                    continue;
                }
                // 切割体参数 & 到旋转轴的最小半径（半径≈0 表示轮廓碰到轴，
                // 旋转体在极点退化，CSG 容易在极点留下小圆锥）。
                double minR = Double.MAX_VALUE;
                int nearAxis = 0;
                final var plane = cutSketch.plane();
                final double axU = plane.axisU().x;
                final double axUy = plane.axisU().y;
                final double axUz = plane.axisU().z;
                final double axVx = plane.axisV().x;
                final double axVy = plane.axisV().y;
                final double axVz = plane.axisV().z;
                final double ox = plane.origin().x;
                final double oy = plane.origin().y;
                final double oz = plane.origin().z;
                final double dl = Math.hypot(rc.params().dirU(), rc.params().dirV());
                final double du0 = rc.params().dirU() / dl;
                final double dv0 = rc.params().dirV() / dl;
                for (final SolidMesh.Vertex v : cutterMesh.vertices()) {
                    final double px = v.position().x - ox;
                    final double py = v.position().y - oy;
                    final double pz = v.position().z - oz;
                    final double u = px * axU + py * axUy + pz * axUz;
                    final double vv = px * axVx + py * axVy + pz * axVz;
                    final double rr = Math.abs(du0 * (vv - rc.params().axisV())
                            - dv0 * (u - rc.params().axisU()));
                    if (rr < minR) {
                        minR = rr;
                    }
                    if (rr < 1.0e-3) {
                        nearAxis++;
                    }
                }
                System.out.printf(java.util.Locale.ROOT,
                        "  revcut idx=%d axis=(%.4f,%.4f dir %.4f,%.4f angle %.2f) "
                                + "minR=%.6f nearAxisVerts=%d%n",
                        cut.index(), rc.params().axisU(), rc.params().axisV(),
                        rc.params().dirU(), rc.params().dirV(), rc.params().angleDeg(),
                        minR, nearAxis);
                for (final var e : cutSketch.entities()) {
                    final int[] ev = e.vars();
                    final StringBuilder sb = new StringBuilder(e.kind().name());
                    sb.append(" construction=").append(cutSketch.isConstruction(e));
                    sb.append(" reference=").append(cutSketch.isReference(e));
                    for (final int vi : ev) {
                        sb.append(' ').append(String.format(java.util.Locale.ROOT,
                                "%.4f", cutSketch.variable(vi)));
                    }
                    System.out.println("    " + sb);
                }
                // 诊断：去掉“从弧圆心连到弧端点”的两条内部分割线，重建轮廓再算。
                final Sketch cleaned = cleanInternalRadii(cutSketch);
                final SolidMesh.Mesh cleanMesh = Revolve.mesh(cleaned, rc.params());
                final List<Csg.Polygon> cleanTarget = CsgMesh.fromMesh(targetMesh, null);
                final List<Csg.Polygon> cleanCutter = CsgMesh.fromMesh(cleanMesh, null);
                final List<Csg.Polygon> cleanResult = Csg.subtract(cleanTarget, cleanCutter);
                final List<Csg.Polygon> cleanInter = Csg.intersect(
                        CsgMesh.fromMesh(targetMesh, null),
                        CsgMesh.fromMesh(cleanMesh, null));
                System.out.printf(java.util.Locale.ROOT,
                        "  cleanProfile cutterVol=%.6f resultVol=%.6f inter=%.6f%n",
                        Math.abs(volume(cleanCutter)), Math.abs(volume(cleanResult)),
                        Math.abs(volume(cleanInter)));
                for (final double off : new double[] {0.001, 0.002, 0.005, 0.01, 0.02, 0.05}) {
                    final Sketch offSketch = offsetProfile(cutSketch, off);
                    final SolidMesh.Mesh offMesh = Revolve.mesh(offSketch, rc.params());
                    final List<Csg.Polygon> rr = Csg.subtract(
                            CsgMesh.fromMesh(targetMesh, null),
                            CsgMesh.fromMesh(offMesh, null));
                    System.out.printf(java.util.Locale.ROOT,
                            "  offset=%.4f cutterVol=%.6f resultVol=%.6f%n",
                            off, Math.abs(volume(CsgMesh.fromMesh(offMesh, null))),
                            Math.abs(volume(rr)));
                }
                final List<Csg.Polygon> a = CsgMesh.fromMesh(targetMesh, null);
                final List<Csg.Polygon> b = CsgMesh.fromMesh(cutterMesh, null);
                final double tv = volume(a);
                final double cv = volume(b);
                final List<Csg.Polygon> r = Csg.subtract(a, b);
                final double rv = volume(r);
                final SolidMesh.Mesh man = ManifoldNative.difference(targetMesh, cutterMesh);
                if (man != null) {
                    System.out.printf(java.util.Locale.ROOT,
                            "  manifold resultVol=%.6f verts=%d tris=%d%n",
                            Math.abs(volume(CsgMesh.fromMesh(man, null))),
                            man.vertices().size(), man.indices().length / 3);
                } else {
                    System.out.println("  manifold result=null");
                }
                final List<Csg.Polygon> rClean = Solid.stripThinSheetPairs(r, 0.01);
                final double rCleanV = volume(rClean);
                // 离线复现游戏里的“平移到原点”分支：
                final List<Csg.Polygon> a2 = CsgMesh.fromMesh(targetMesh, null);
                final List<Csg.Polygon> b2 = CsgMesh.fromMesh(cutterMesh, null);
                final double[] tb0 = CsgMesh.bounds(a2);
                final double cx = (tb0[0] + tb0[3]) * 0.5;
                final double cy = (tb0[1] + tb0[4]) * 0.5;
                final double cz = (tb0[2] + tb0[5]) * 0.5;
                final List<Csg.Polygon> aS = translate(a2, -cx, -cy, -cz);
                final List<Csg.Polygon> bS = translate(b2, -cx, -cy, -cz);
                final List<Csg.Polygon> rS = Csg.subtract(aS, bS);
                final double rSv = volume(rS);
                final List<Csg.Polygon> a3 = CsgMesh.fromMesh(targetMesh, null);
                final List<Csg.Polygon> b3 = CsgMesh.fromMesh(cutterMesh, null);
                final List<Csg.Polygon> aS3 = translate(a3, -cx, -cy, -cz);
                final List<Csg.Polygon> bS3 = translate(b3, -cx, -cy, -cz);
                final List<Csg.Polygon> inter = Csg.intersect(aS3, bS3);
                final double iv = volume(inter);
                final List<Csg.Polygon> a4 = CsgMesh.fromMesh(targetMesh, null);
                final List<Csg.Polygon> aS4 = translate(a4, -cx, -cy, -cz);
                final List<Csg.Polygon> rC = Csg.subtract(aS4, translate(inter, 0, 0, 0));
                final double rCv = volume(rC);
                // 诊断：A 结果再减一次交集（数学上 A 与 inter 不相交，
                // 但 BSP 少切出来的残皮可能正好能被这次减法削掉）。
                final List<Csg.Polygon> aAgain = CsgMesh.fromMesh(targetMesh, null);
                final List<Csg.Polygon> bAgain = CsgMesh.fromMesh(cutterMesh, null);
                final List<Csg.Polygon> rA2 = Csg.subtract(aAgain, bAgain);
                final List<Csg.Polygon> inter2 = Csg.intersect(
                        CsgMesh.fromMesh(targetMesh, null),
                        CsgMesh.fromMesh(cutterMesh, null));
                final List<Csg.Polygon> rAminus = Csg.subtract(rA2, inter2);
                final double rAv = volume(rAminus);
                final double[] tb = CsgMesh.bounds(CsgMesh.fromMesh(targetMesh, null));
                final double[] cb = CsgMesh.bounds(CsgMesh.fromMesh(cutterMesh, null));
                System.out.printf(java.util.Locale.ROOT,
                        "body=%s target(idx=%d,id=%s) cut(idx=%d,id=%s) "
                                + "targetVol=%.6f cutterVol=%.6f resultVol=%.6f "
                                + "shifted=%.6f inter=%.6f minusInter=%.6f AminusInter=%.6f "
                                + "clean=%.6f cleanPolys=%d "
                                + "targetBounds=%s cutterBounds=%s%n",
                        target.body(), target.index(), target.id(),
                        cut.index(), cut.id(), tv, cv, rv, rSv, iv, rCv, rAv,
                        rCleanV, rClean.size(),
                        fmt(tb), fmt(cb));
                for (final double dx : new double[] {0.0, 0.001, -0.001, 0.002, -0.002, 0.005, -0.005}) {
                    final List<Csg.Polygon> cc = translate(
                            CsgMesh.fromMesh(cutterMesh, null), dx, 0.0, 0.0);
                    final List<Csg.Polygon> rr = Csg.subtract(
                            CsgMesh.fromMesh(targetMesh, null), cc);
                    System.out.printf(java.util.Locale.ROOT,
                            "  shiftX=%.4f sub=%.6f%n", dx, Math.abs(volume(rr)));
                }
                final var av = cutSketch.plane().axisV();
                for (final double d : new double[] {0.001, -0.001, 0.002, -0.002,
                        0.005, -0.005, 0.01, -0.01}) {
                    final List<Csg.Polygon> cc = translate(
                            CsgMesh.fromMesh(cutterMesh, null),
                            av.x * d, av.y * d, av.z * d);
                    final List<Csg.Polygon> rr = Csg.subtract(
                            CsgMesh.fromMesh(targetMesh, null), cc);
                    System.out.printf(java.util.Locale.ROOT,
                            "  shiftAxisV=%.4f sub=%.6f%n", d, Math.abs(volume(rr)));
                }
                final String base = "probe_t" + target.index() + "_c" + cut.index();
                dump("csg-dump-probe/" + base + "_target.obj", a);
                dump("csg-dump-probe/" + base + "_cutter.obj", b);
                dump("csg-dump-probe/" + base + "_result.obj", r);
                dumped.add(base);
            }
        }
        System.out.println("dumped=" + dumped);
    }

    private static Sketch offsetProfile(final Sketch src, final double off) {
        final Sketch out = new Sketch(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), "offset", src.plane());
        for (final Sketch.Entity e : src.entities()) {
            if (src.isConstruction(e) || src.isReference(e)) {
                continue;
            }
            final int[] v = e.vars();
            if (e.kind() == Sketch.EntityKind.ARC) {
                out.addArcPoints(
                        src.variable(v[0]), src.variable(v[1]) + off,
                        src.variable(v[2]), src.variable(v[3]) + off,
                        src.variable(v[4]), src.variable(v[5]) + off, e.cw());
            } else if (e.kind() == Sketch.EntityKind.LINE) {
                out.addLine(src.variable(v[0]), src.variable(v[1]) + off,
                        src.variable(v[2]), src.variable(v[3]) + off);
            }
        }
        return out;
    }

    private static Sketch cleanInternalRadii(final Sketch src) {
        final Sketch out = new Sketch(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), "clean", src.plane());
        Sketch.Entity arc = null;
        for (final Sketch.Entity e : src.entities()) {
            if (e.kind() == Sketch.EntityKind.ARC) {
                arc = e;
                break;
            }
        }
        if (arc == null) {
            return src;
        }
        final int[] av = arc.vars();
        final double cx = src.variable(av[0]);
        final double cy = src.variable(av[1]);
        final double sx = src.variable(av[2]);
        final double sy = src.variable(av[3]);
        final double ex = src.variable(av[4]);
        final double ey = src.variable(av[5]);
        out.addArcPoints(cx, cy, sx, sy, ex, ey, arc.cw());
        for (final Sketch.Entity e : src.entities()) {
            if (e.kind() != Sketch.EntityKind.LINE) {
                continue;
            }
            final int[] v = e.vars();
            final double ax = src.variable(v[0]);
            final double ay = src.variable(v[1]);
            final double bx = src.variable(v[2]);
            final double by = src.variable(v[3]);
            final boolean aAtCenter = Math.hypot(ax - cx, ay - cy) < 1.0e-4;
            final boolean bAtCenter = Math.hypot(bx - cx, by - cy) < 1.0e-4;
            final boolean otherAtArcEnd = (aAtCenter
                    && (Math.hypot(bx - sx, by - sy) < 1.0e-4
                    || Math.hypot(bx - ex, by - ey) < 1.0e-4))
                    || (bAtCenter
                    && (Math.hypot(ax - sx, ay - sy) < 1.0e-4
                    || Math.hypot(ax - ex, ay - ey) < 1.0e-4));
            if (otherAtArcEnd) {
                continue; // 内部半径线
            }
            out.addLine(ax, ay, bx, by);
        }
        return out;
    }

    private static List<Csg.Polygon> translate(final List<Csg.Polygon> polys,
                                              final double dx, final double dy,
                                              final double dz) {
        final List<Csg.Polygon> out = new ArrayList<>(polys.size());
        for (final Csg.Polygon p : polys) {
            final List<Csg.Vertex> vs = new ArrayList<>(p.vertices.size());
            for (final Csg.Vertex v : p.vertices) {
                final Csg.V3 q = v.pos();
                vs.add(new Csg.Vertex(new Csg.V3(q.x() + dx, q.y() + dy, q.z() + dz),
                        v.normal(), v.uv()));
            }
            Csg.Plane plane = p.plane;
            if (vs.size() >= 3) {
                final Csg.Plane np = Csg.Plane.fromPoints(
                        vs.get(0).pos(), vs.get(1).pos(), vs.get(2).pos());
                if (np != null) {
                    plane = np;
                }
            }
            out.add(new Csg.Polygon(vs, plane));
        }
        return out;
    }

    private static List<Csg.Polygon> scale(final List<Csg.Polygon> polys,
                                          final double factor) {
        final double[] b = CsgMesh.bounds(polys);
        final double cx = (b[0] + b[3]) * 0.5;
        final double cy = (b[1] + b[4]) * 0.5;
        final double cz = (b[2] + b[5]) * 0.5;
        final List<Csg.Polygon> out = new ArrayList<>(polys.size());
        for (final Csg.Polygon p : polys) {
            final List<Csg.Vertex> vs = new ArrayList<>(p.vertices.size());
            for (final Csg.Vertex v : p.vertices) {
                final Csg.V3 q = v.pos();
                vs.add(new Csg.Vertex(new Csg.V3(
                        cx + (q.x() - cx) * factor,
                        cy + (q.y() - cy) * factor,
                        cz + (q.z() - cz) * factor), v.normal(), v.uv()));
            }
            Csg.Plane plane = p.plane;
            if (vs.size() >= 3) {
                final Csg.Plane np = Csg.Plane.fromPoints(
                        vs.get(0).pos(), vs.get(1).pos(), vs.get(2).pos());
                if (np != null) {
                    plane = np;
                }
            }
            out.add(new Csg.Polygon(vs, plane));
        }
        return out;
    }

    private static double volume(final List<Csg.Polygon> polys) {
        if (polys == null || polys.isEmpty()) {
            return 0.0;
        }
        final Csg.V3 o = polys.get(0).vertices.get(0).pos();
        double sum = 0.0;
        for (final Csg.Polygon p : polys) {
            for (int i = 2; i < p.vertices.size(); i++) {
                final Csg.V3 a = p.vertices.get(0).pos();
                final Csg.V3 b = p.vertices.get(i - 1).pos();
                final Csg.V3 c = p.vertices.get(i).pos();
                final double ax = a.x() - o.x();
                final double ay = a.y() - o.y();
                final double az = a.z() - o.z();
                final double bx = b.x() - o.x();
                final double by = b.y() - o.y();
                final double bz = b.z() - o.z();
                final double cx = c.x() - o.x();
                final double cy = c.y() - o.y();
                final double cz = c.z() - o.z();
                sum += ax * (by * cz - bz * cy)
                        - ay * (bx * cz - bz * cx)
                        + az * (bx * cy - by * cx);
            }
        }
        return sum / 6.0;
    }

    private static String fmt(final double[] b) {
        return b == null ? "null" : String.format(java.util.Locale.ROOT,
                "(%.3f,%.3f,%.3f)..(%.3f,%.3f,%.3f)",
                b[0], b[1], b[2], b[3], b[4], b[5]);
    }

    private static void dump(final String name, final List<Csg.Polygon> polys)
            throws Exception {
        if (polys == null || polys.isEmpty()) {
            return;
        }
        final Path dir = Path.of("csg-dump-probe");
        Files.createDirectories(dir);
        final StringBuilder sb = new StringBuilder();
        for (final Csg.Polygon p : polys) {
            sb.append("f");
            for (final Csg.Vertex v : p.vertices) {
                sb.append(' ').append(sb.length()).append('\n');
                sb.append("v ").append(v.pos().x()).append(' ')
                        .append(v.pos().y()).append(' ')
                        .append(v.pos().z()).append('\n');
            }
            sb.append('\n');
        }
        // 上面的索引不可用，改用简单全局去重
        final java.util.Map<String, Integer> ids = new java.util.HashMap<>();
        final StringBuilder out = new StringBuilder();
        int next = 1;
        for (final Csg.Polygon p : polys) {
            out.append("f");
            for (final Csg.Vertex v : p.vertices) {
                final Csg.V3 q = v.pos();
                final String key = Math.round(q.x() * 1.0e7) + ","
                        + Math.round(q.y() * 1.0e7) + ","
                        + Math.round(q.z() * 1.0e7);
                Integer id = ids.get(key);
                if (id == null) {
                    id = next++;
                    ids.put(key, id);
                    out.append(' ').append(id);
                    out.append("\nv ").append(q.x()).append(' ')
                            .append(q.y()).append(' ').append(q.z()).append('\n');
                } else {
                    out.append(' ').append(id);
                }
            }
            out.append('\n');
        }
        Files.writeString(dir.resolve(Path.of(name).getFileName()), out.toString());
        System.out.println("[probe] wrote " + name);
    }
}
