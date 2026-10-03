import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** 最近邻放大一张 PNG（可选裁剪），方便肉眼检查渲染：
 *  java Zoom.java &lt;in&gt; &lt;out&gt; &lt;factor&gt; [cropX cropY cropW cropH] */
public final class Zoom {
    public static void main(String[] a) throws Exception {
        BufferedImage src = ImageIO.read(new File(a[0]));
        int f = Integer.parseInt(a[2]);
        if (a.length >= 7) {
            final int cx = Integer.parseInt(a[3]), cy = Integer.parseInt(a[4]);
            final int cw = Math.min(Integer.parseInt(a[5]), src.getWidth() - cx);
            final int ch = Math.min(Integer.parseInt(a[6]), src.getHeight() - cy);
            src = src.getSubimage(cx, cy, cw, ch);
        }
        int w = src.getWidth() * f, h = src.getHeight() * f;
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) dst.setRGB(x, y, src.getRGB(x / f, y / f));
        ImageIO.write(dst, "png", new File(a[1]));
        System.out.println(a[0] + " -> " + a[1] + " " + w + "x" + h);
    }
}
