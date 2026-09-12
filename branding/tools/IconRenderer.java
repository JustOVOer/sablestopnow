import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 branding/scene.txt（build_scene.py 生成）渲染成 mod 图标 PNG。
 *
 * 自写软件光栅化器：
 *   - 正交等轴测投影
 *   - 每个面在屏幕上是平行四边形，用 (a,b) 参数化做逐像素覆盖测试，
 *     a/b 同时给出精确的 uv 与线性插值深度
 *   - z-buffer（不透明面写深度；半透明外壳只测不写，按远近混合）
 *   - 2x 超采样后降采样，边缘干净
 *   - 方向光做 MC 风的平面着色，贴图用最近邻（保持 16x16 像素画的锐利感）
 *
 * 用法: java IconRenderer.java <scene.txt> <texturesDir> <out.png> [supersample]
 */
public final class IconRenderer {

    static final class Quad {
        String tex; String face;
        double[][] p = new double[4][3];
        double[][] uv = new double[4][2];
        double[] sx = new double[4], sy = new double[4];
        double depth, shade;
        BufferedImage img;
        boolean opaque;
        boolean flipNormal;      // 镜像件：法线要取反，否则光照会反向
        boolean flipScreenY;     // 屏幕空间上下镜像（像翻图片一样）
        int gid;                 // 所属物件 id（用于求镜像中心）
    }

    static int SS = 2;
    static int bg = 0x33383D;          // 深灰底
    static int gridColor = 0x4A5158;   // 浅灰网格
    static int haloColor = 0x9FB6CC;   // 模型外光边
    static double haloA = 0.55;
    static int gridStep = 32;
    static double brightness = 1.6;    // 合背景之前先把模型调亮（gamma 提亮：1.0 = 不变，越大越亮且不爆高光）

    public static void main(String[] args) throws Exception {
        File scene = new File(args[0]);
        File texDir = new File(args[1]);
        File out = new File(args[2]);
        if (args.length > 3) SS = Integer.parseInt(args[3]);
        if (args.length > 4) bg = (int) Long.parseLong(args[4].replace("0x", ""), 16);
        if (args.length > 5) gridColor = (int) Long.parseLong(args[5].replace("0x", ""), 16);
        if (args.length > 6) gridStep = Integer.parseInt(args[6]);
        if (args.length > 7) haloColor = (int) Long.parseLong(args[7].replace("0x", ""), 16);
        if (args.length > 8) haloA = Double.parseDouble(args[8]);
        if (args.length > 9) brightness = Double.parseDouble(args[9]);

        int canvas = 512, padding = 26;
        String[] texNames = new String[0];
        List<Quad> quads = new ArrayList<>();

        try (BufferedReader r = new BufferedReader(new FileReader(scene))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] t = line.split("\\s+");
                switch (t[0]) {
                    case "CANVAS" -> { canvas = Integer.parseInt(t[1]); padding = Integer.parseInt(t[2]); }
                    case "NTEX" -> texNames = new String[Integer.parseInt(t[1])];
                    case "TEX" -> texNames[Integer.parseInt(t[1])] = t[2];
                    case "QUAD" -> {
                        Quad q = new Quad();
                        q.tex = texNames[Integer.parseInt(t[1])];
                        q.face = t[2];
                        int flags = Integer.parseInt(t[3]);
                        q.flipNormal = (flags & 1) != 0;
                        q.flipScreenY = (flags & 2) != 0;
                        q.gid = Integer.parseInt(t[4]);
                        int k = 5;
                        for (int i = 0; i < 4; i++)
                            for (int j = 0; j < 3; j++) q.p[i][j] = Double.parseDouble(t[k++]);
                        for (int i = 0; i < 4; i++)
                            for (int j = 0; j < 2; j++) q.uv[i][j] = Double.parseDouble(t[k++]);
                        quads.add(q);
                    }
                }
            }
        }

        Map<String, BufferedImage> cache = new HashMap<>();
        for (String n : texNames) if (n != null) cache.put(n, ImageIO.read(new File(texDir, n + ".png")));

        // ---- 正交等轴测投影 ----
        Map<Integer, double[]> flipCenter = new HashMap<>();   // gid -> {sumY, count}
        for (Quad q : quads) {
            q.img = cache.get(q.tex);
            q.opaque = isOpaque(q.img);
            double d = 0;
            for (int i = 0; i < 4; i++) {
                double vx = q.p[i][0], vy = q.p[i][1], vz = q.p[i][2];
                q.sx[i] = vx; q.sy[i] = -vy; d += vz;
                if (q.flipScreenY) {
                    double[] acc = flipCenter.computeIfAbsent(q.gid, k -> new double[2]);
                    acc[0] += -vy; acc[1] += 1;
                }
            }
            q.depth = d / 4.0;
            double[] n = normal(q.p[0], q.p[1], q.p[3]);
            if (q.flipNormal) { n[0] = -n[0]; n[1] = -n[1]; n[2] = -n[2]; }
            double[] L = normalize(new double[]{-0.38, 0.86, 0.34});
            double lam = Math.max(0, n[0] * L[0] + n[1] * L[1] + n[2] * L[2]);
            q.shade = 0.62 + 0.38 * lam;
        }
        // 屏幕上下镜像：以该物件的投影中心为轴，把 sy 取反（像翻图片）
        for (Quad q : quads) {
            if (!q.flipScreenY) continue;
            double[] acc = flipCenter.get(q.gid);
            double c = acc[0] / acc[1];
            for (int i = 0; i < 4; i++) q.sy[i] = 2.0 * c - q.sy[i];
        }
        double minX = 1e18, maxX = -1e18, minY = 1e18, maxY = -1e18;
        for (Quad q : quads) {
            for (int i = 0; i < 4; i++) {
                minX = Math.min(minX, q.sx[i]); maxX = Math.max(maxX, q.sx[i]);
                minY = Math.min(minY, q.sy[i]); maxY = Math.max(maxY, q.sy[i]);
            }
        }

        double span = Math.max(maxX - minX, maxY - minY);
        double scale = (canvas - 2.0 * padding) / span;
        double cx = (minX + maxX) / 2.0, cy = (minY + maxY) / 2.0;
        int W = canvas * SS;
        for (Quad q : quads) {
            for (int i = 0; i < 4; i++) {
                q.sx[i] = ((q.sx[i] - cx) * scale + canvas / 2.0) * SS;
                q.sy[i] = ((q.sy[i] - cy) * scale + canvas / 2.0) * SS;
            }
        }

        double[] col = new double[W * W * 4];       // 直通 alpha，RGB 未预乘
        double[] zbuf = new double[W * W];
        java.util.Arrays.fill(zbuf, Double.NEGATIVE_INFINITY);

        quads.sort(Comparator.comparingDouble(q -> q.depth));
        for (Quad q : quads) if (q.opaque) raster(q, col, zbuf, W, true);
        for (Quad q : quads) if (!q.opaque) raster(q, col, zbuf, W, false);

        BufferedImage img = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_RGB);
        // 1) 先把模型降采样成 canvas 尺寸的 RGBA + 掩码
        double[] mr = new double[canvas * canvas];
        double[] mg = new double[canvas * canvas];
        double[] mb = new double[canvas * canvas];
        double[] ma = new double[canvas * canvas];
        boolean[] mask = new boolean[canvas * canvas];
        for (int y = 0; y < canvas; y++) {
            for (int x = 0; x < canvas; x++) {
                double a = 0, r = 0, g = 0, b = 0;
                for (int dy = 0; dy < SS; dy++) {
                    for (int dx = 0; dx < SS; dx++) {
                        int i = ((y * SS + dy) * W + (x * SS + dx)) * 4;
                        double al = col[i + 3];
                        r += col[i] * al; g += col[i + 1] * al; b += col[i + 2] * al; a += al;
                    }
                }
                double n = SS * SS;
                r /= n; g /= n; b /= n; a /= n;
                if (a > 1e-6) { r /= a; g /= a; b /= a; }
                int p = y * canvas + x;
                mr[p] = r; mg[p] = g; mb[p] = b; ma[p] = a;
                mask[p] = a > 0.35;
            }
        }
        // 2) 掩码膨胀 -> 外光边（深色模型压在深灰底上需要一圈亮边才立得住）
        int halo = Math.max(2, canvas / 150);
        boolean[] dil = dilate(mask, canvas, halo);
        // 3) 合成：底色 / 网格 -> 光边 -> 模型
        for (int y = 0; y < canvas; y++) {
            for (int x = 0; x < canvas; x++) {
                int p = y * canvas + x;
                int base = bg;
                if (gridStep > 0 && (x % gridStep == 0 || y % gridStep == 0)) base = gridColor;
                double br = ((base >> 16) & 0xFF) / 255.0;
                double bgc = ((base >> 8) & 0xFF) / 255.0;
                double bb = (base & 0xFF) / 255.0;
                if (dil[p] && !mask[p]) {
                    br = br * (1 - haloA) + ((haloColor >> 16) & 0xFF) / 255.0 * haloA;
                    bgc = bgc * (1 - haloA) + ((haloColor >> 8) & 0xFF) / 255.0 * haloA;
                    bb = bb * (1 - haloA) + (haloColor & 0xFF) / 255.0 * haloA;
                }
                double a = ma[p];
                double r = br * (1 - a) + lift(mr[p]) * a;
                double g = bgc * (1 - a) + lift(mg[p]) * a;
                double bl = bb * (1 - a) + lift(mb[p]) * a;
                img.setRGB(x, y, (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(bl));
            }
        }
        ImageIO.write(img, "png", out);
        System.out.printf("[render] quads=%d canvas=%d ss=%d -> %s%n",
                quads.size(), canvas, SS, out.getName());
    }

    /** gamma 提亮：先把模型调亮再合背景，暗部抬得多、高光不溢出。 */
    static double lift(double c) {
        if (c <= 0) return 0;
        return 1.0 - Math.pow(1.0 - Math.min(1.0, c), brightness);
    }

    /** 方形结构元的最大值滤波（分离成水平/垂直两趟）。 */
    static boolean[] dilate(boolean[] src, int n, int r) {
        boolean[] tmp = new boolean[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                boolean v = false;
                for (int d = -r; d <= r && !v; d++) {
                    int xx = x + d;
                    if (xx >= 0 && xx < n && src[y * n + xx]) v = true;
                }
                tmp[y * n + x] = v;
            }
        }
        boolean[] out = new boolean[n * n];
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                boolean v = false;
                for (int d = -r; d <= r && !v; d++) {
                    int yy = y + d;
                    if (yy >= 0 && yy < n && tmp[yy * n + x]) v = true;
                }
                out[y * n + x] = v;
            }
        }
        return out;
    }

    static void raster(Quad q, double[] col, double[] zbuf, int W, boolean writeDepth) {
        double x0 = q.sx[0], y0 = q.sy[0];
        double ex = q.sx[1] - x0, ey = q.sy[1] - y0;      // a 方向
        double fx = q.sx[3] - x0, fy = q.sy[3] - y0;      // b 方向
        double det = ex * fy - ey * fx;
        if (Math.abs(det) < 1e-9) return;

        int minX = (int) Math.floor(Math.min(Math.min(q.sx[0], q.sx[1]), Math.min(q.sx[2], q.sx[3])));
        int maxX = (int) Math.ceil(Math.max(Math.max(q.sx[0], q.sx[1]), Math.max(q.sx[2], q.sx[3])));
        int minY = (int) Math.floor(Math.min(Math.min(q.sy[0], q.sy[1]), Math.min(q.sy[2], q.sy[3])));
        int maxY = (int) Math.ceil(Math.max(Math.max(q.sy[0], q.sy[1]), Math.max(q.sy[2], q.sy[3])));
        minX = Math.max(0, minX); minY = Math.max(0, minY);
        maxX = Math.min(W - 1, maxX); maxY = Math.min(W - 1, maxY);

        double u1 = q.uv[0][0], v1 = q.uv[0][1];
        double du = q.uv[1][0] - u1, dv = q.uv[3][1] - v1;
        BufferedImage im = q.img;
        int tw = im.getWidth(), th = im.getHeight();
        double d0 = q.p[0][2], da = q.p[1][2] - d0, db = q.p[3][2] - d0;
        final double eps = 0.004;

        for (int py = minY; py <= maxY; py++) {
            double ry = py + 0.5 - y0;
            for (int px = minX; px <= maxX; px++) {
                double rx = px + 0.5 - x0;
                double a = (rx * fy - ry * fx) / det;
                if (a < -eps || a > 1 + eps) continue;
                double b = (ex * ry - ey * rx) / det;
                if (b < -eps || b > 1 + eps) continue;

                double depth = d0 + a * da + b * db;
                int idx = py * W + px;
                if (writeDepth) {
                    if (depth <= zbuf[idx]) continue;
                } else if (depth <= zbuf[idx]) {
                    continue;
                }

                int tx = (int) Math.floor(u1 + a * du);
                int ty = (int) Math.floor(v1 + b * dv);
                tx = tx < 0 ? 0 : (tx >= tw ? tw - 1 : tx);
                ty = ty < 0 ? 0 : (ty >= th ? th - 1 : ty);
                int argb = im.getRGB(tx, ty);
                double sa = ((argb >>> 24) & 0xFF) / 255.0;
                if (sa <= 0) continue;
                double sr = ((argb >> 16) & 0xFF) / 255.0 * q.shade;
                double sg = ((argb >> 8) & 0xFF) / 255.0 * q.shade;
                double sb = (argb & 0xFF) / 255.0 * q.shade;

                int o = idx * 4;
                double da2 = col[o + 3];
                double outA = sa + da2 * (1 - sa);
                if (outA <= 1e-9) continue;
                col[o] = (sr * sa + col[o] * da2 * (1 - sa)) / outA;
                col[o + 1] = (sg * sa + col[o + 1] * da2 * (1 - sa)) / outA;
                col[o + 2] = (sb * sa + col[o + 2] * da2 * (1 - sa)) / outA;
                col[o + 3] = outA;
                if (writeDepth) zbuf[idx] = depth;
            }
        }
    }

    static boolean isOpaque(BufferedImage im) {
        for (int y = 0; y < im.getHeight(); y++)
            for (int x = 0; x < im.getWidth(); x++)
                if (((im.getRGB(x, y) >>> 24) & 0xFF) < 255) return false;
        return true;
    }

    static int clamp8(double v) {
        int i = (int) Math.round(v * 255.0);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    static double[] normal(double[] a, double[] b, double[] c) {
        double[] u = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
        double[] v = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
        return normalize(new double[]{
                u[1] * v[2] - u[2] * v[1],
                u[2] * v[0] - u[0] * v[2],
                u[0] * v[1] - u[1] * v[0]});
    }

    static double[] normalize(double[] v) {
        double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (l < 1e-12) return new double[]{0, 0, 1};
        return new double[]{v[0] / l, v[1] / l, v[2] / l};
    }
}
