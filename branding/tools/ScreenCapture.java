import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;

/** 截取当前屏幕，用于验证 GUI 程序（如 Blockbench）的实际渲染结果。
 *  用法: java ScreenCapture.java <out.png> [maxWidth] [cropX cropY cropW cropH]  */
public final class ScreenCapture {
    public static void main(String[] args) throws Exception {
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        BufferedImage shot = new Robot().createScreenCapture(screen);
        if (args.length >= 6) {
            int x = Integer.parseInt(args[2]), y = Integer.parseInt(args[3]);
            int w = Integer.parseInt(args[4]), h = Integer.parseInt(args[5]);
            x = Math.max(0, Math.min(x, shot.getWidth() - 1));
            y = Math.max(0, Math.min(y, shot.getHeight() - 1));
            w = Math.min(w, shot.getWidth() - x);
            h = Math.min(h, shot.getHeight() - y);
            shot = shot.getSubimage(x, y, w, h);
        }
        int max = args.length > 1 ? Integer.parseInt(args[1]) : 1280;
        if (shot.getWidth() > max) {
            int w = max, h = shot.getHeight() * max / shot.getWidth();
            BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = small.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(shot, 0, 0, w, h, null);
            g.dispose();
            shot = small;
        }
        ImageIO.write(shot, "png", new File(args[0]));
        System.out.println("captured " + screen.width + "x" + screen.height + " -> " + args[0]);
    }
}
