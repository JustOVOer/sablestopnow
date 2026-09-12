import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * 把 Create 的 cogwheel 贴图「保形换色」成钢铁材质：保留原有明暗花纹，
 * 但把色相换成冷灰蓝的钢色渐变（用户要的是钢铁齿轮，不是木色齿轮）。
 *
 * 用法: java Steelify.java <in.png> <out.png>
 */
public final class Steelify {
    // 钢色渐变：暗 -> 亮（偏亮一点，作为图标要能看清齿形）
    static final int[] DARK = {0x46, 0x50, 0x5C};
    static final int[] LIGHT = {0xC6, 0xD1, 0xDC};

    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = src.getRGB(x, y);
                int alpha = (p >>> 24) & 0xFF;
                if (alpha == 0) { dst.setRGB(x, y, 0); continue; }
                double r = ((p >> 16) & 0xFF) / 255.0;
                double g = ((p >> 8) & 0xFF) / 255.0;
                double b = (p & 0xFF) / 255.0;
                double lum = 0.299 * r + 0.587 * g + 0.114 * b;
                // 拉一点对比度，让齿轮的齿面/侧面区分更清楚
                double t = clamp01((lum - 0.16) / (0.80 - 0.16));
                t = Math.pow(t, 0.85);
                int rr = (int) Math.round(lerp(DARK[0], LIGHT[0], t));
                int gg = (int) Math.round(lerp(DARK[1], LIGHT[1], t));
                int bb = (int) Math.round(lerp(DARK[2], LIGHT[2], t));
                // 冷色偏移：轻微压红、抬蓝
                rr = clamp8(rr * 0.97);
                gg = clamp8(gg * 1.00);
                bb = clamp8(bb * 1.07);
                dst.setRGB(x, y, (alpha << 24) | (rr << 16) | (gg << 8) | bb);
            }
        }
        ImageIO.write(dst, "png", new File(a[1]));
        System.out.println("steelified -> " + a[1] + " (" + w + "x" + h + ")");
    }

    static double lerp(double a, double b, double t) { return a + (b - a) * t; }
    static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
    static int clamp8(double v) { int i = (int) Math.round(v); return i < 0 ? 0 : (i > 255 ? 255 : i); }
}
