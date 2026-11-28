import com.ovo.sablestopnow.client.manifold.ManifoldBridge;
import com.ovo.sablestopnow.sketch.Revolve;
import com.ovo.sablestopnow.sketch.Sketch;
import com.ovo.sablestopnow.sketch.SketchPlane;
import com.ovo.sablestopnow.sketch.SolidMesh;
import org.joml.Vector3d;

import java.util.UUID;

public final class ManifoldRevolveProbe {
    public static void main(String[] args) {
        raw("rect", new double[][] {{1, 0, 2, 0, 2, 1, 1, 1}});
        raw("touch-axis", new double[][] {{0, 0, 1, 0, 1, 1, 0, 1}});
        raw("hole", new double[][] {
                {1, 0, 3, 0, 3, 1, 1, 1},
                {1.5, 0.25, 1.5, 0.75, 2.5, 0.75, 2.5, 0.25}
        });

        sketch("sketch-touch-axis", new double[][] {{0, 0, 1, 0, 1, 1, 0, 1}}, false);
        sketch("sketch-hole", new double[][] {
                {1, 0, 3, 0, 3, 1, 1, 1},
                {1.5, 0.25, 1.5, 0.75, 2.5, 0.75, 2.5, 0.25}
        }, true);
    }

    private static void raw(String name, double[][] contours) {
        long t = System.nanoTime();
        SolidMesh.Mesh m = ManifoldBridge.revolve(java.util.Arrays.asList(contours), 64, 360);
        report(name + " raw", m, t);
    }

    private static void sketch(String name, double[][] contours, boolean hole) {
        final SketchPlane plane = new SketchPlane(new Vector3d(0, 0, 0),
                new Vector3d(1, 0, 0), new Vector3d(0, 1, 0));
        final Sketch sk = new Sketch(UUID.randomUUID(), UUID.randomUUID(), name, plane);
        for (int i = 0; i < contours.length; i++) {
            final double[] c = contours[i];
            // 这里直接绕轴 x=0、方向 +Y，转 360°。
            final double rSign = i == 0 ? 1.0 : 1.0;
            for (int k = 0; k < c.length; k += 2) {
                final double a = c[k] * rSign;
                final double b = c[k + 1];
                final double na = c[(k + 2) % c.length] * rSign;
                final double nb = c[(k + 3) % c.length];
                sk.addLine(a, b, na, nb);
            }
        }
        final Revolve.Params p = new Revolve.Params(0, 0, 0, 1, 360, 1);
        long t = System.nanoTime();
        SolidMesh.Mesh m = Revolve.mesh(sk, p);
        report(name + " sketch-native", m, t);
    }

    private static void report(String name, SolidMesh.Mesh m, long t) {
        long ms = (System.nanoTime() - t) / 1_000_000L;
        if (m == null) {
            System.out.println(name + ": null ms=" + ms);
            return;
        }
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE,
                -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (SolidMesh.Vertex v : m.vertices()) {
            b[0] = Math.min(b[0], v.position().x);
            b[1] = Math.min(b[1], v.position().y);
            b[2] = Math.min(b[2], v.position().z);
            b[3] = Math.max(b[3], v.position().x);
            b[4] = Math.max(b[4], v.position().y);
            b[5] = Math.max(b[5], v.position().z);
        }
        System.out.println(name + ": verts=" + m.vertices().size()
                + " tris=" + m.indices().length / 3
                + " bounds=" + java.util.Arrays.toString(b)
                + " vol=" + volume(m)
                + " ms=" + ms);
    }

    private static double volume(SolidMesh.Mesh m) {
        double v = 0.0;
        final int[] idx = m.indices();
        for (int i = 0; i + 2 < idx.length; i += 3) {
            final Vector3d a = m.vertices().get(idx[i]).position();
            final Vector3d b = m.vertices().get(idx[i + 1]).position();
            final Vector3d c = m.vertices().get(idx[i + 2]).position();
            v += a.dot(b.cross(c, new Vector3d())) / 6.0;
        }
        return v;
    }
}
