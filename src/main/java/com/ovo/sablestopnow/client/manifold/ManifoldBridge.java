package com.ovo.sablestopnow.client.manifold;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.ovo.sablestopnow.sketch.SolidMesh;
import org.joml.Vector3d;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Manifold C API JNA wrapper (Apache-2.0 native library). */
public final class ManifoldBridge {
    private static volatile boolean failed;
    private static Api api;

    private ManifoldBridge() {
    }

    private interface Api extends Library {
        long manifold_manifold_size();
        long manifold_meshgl64_size();
        Pointer manifold_meshgl64(Pointer mem, double[] v, long nv, long np,
                                  long[] t, long nt);
        Pointer manifold_meshgl64_merge(Pointer mem, Pointer mesh);
        Pointer manifold_of_meshgl64(Pointer mem, Pointer mesh);
        int manifold_status(Pointer m);
        Pointer manifold_difference(Pointer mem, Pointer a, Pointer b);
        Pointer manifold_simplify(Pointer mem, Pointer m, double tolerance);
        Pointer manifold_get_meshgl64(Pointer mem, Pointer m);
        long manifold_meshgl64_num_vert(Pointer m);
        long manifold_meshgl64_num_tri(Pointer m);
        long manifold_meshgl64_num_prop(Pointer m);
        Pointer manifold_meshgl64_vert_properties(Pointer dest, Pointer m);
        Pointer manifold_meshgl64_tri_verts(Pointer dest, Pointer m);
        void manifold_destruct_manifold(Pointer m);
        void manifold_destruct_meshgl64(Pointer m);
    }

    private static synchronized Api api() {
        if (api != null || failed) return api;
        try {
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                failed = true;
                return null;
            }
            Path dir = Files.createTempDirectory("sablestopnow-manifold");
            Path m = extract(dir, "manifold.dll");
            Path c = extract(dir, "manifoldc.dll");
            System.load(m.toAbsolutePath().toString());
            System.load(c.toAbsolutePath().toString());
            api = Native.load(c.toAbsolutePath().toString(), Api.class);
        } catch (Throwable t) {
            failed = true;
            System.out.println("[manifold] load failed: " + t);
        }
        return api;
    }

    private static Path extract(Path dir, String name) throws Exception {
        String res = "/sablestopnow/native/win-x86_64/" + name;
        try (InputStream in = ManifoldBridge.class.getResourceAsStream(res)) {
            if (in == null) throw new IllegalStateException("missing " + res);
            Path out = dir.resolve(name);
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            return out;
        }
    }

    public static SolidMesh.Mesh difference(SolidMesh.Mesh a, SolidMesh.Mesh b) {
        Api c = api();
        if (c == null || a == null || b == null) return null;
        List<Memory> keep = new ArrayList<>();
        List<Pointer> meshes = new ArrayList<>();
        List<Pointer> manifolds = new ArrayList<>();
        try {
            long meshSize = c.manifold_meshgl64_size();
            long manSize = c.manifold_manifold_size();
            Pointer amMesh = mesh(c, keep, meshSize, a);
            Pointer bmMesh = mesh(c, keep, meshSize, b);
            if (amMesh == null || bmMesh == null) return null;
            meshes.add(amMesh);
            meshes.add(bmMesh);
            Pointer mergedA = c.manifold_meshgl64_merge(mem(keep, meshSize), amMesh);
            if (mergedA != null) {
                amMesh = mergedA;
                meshes.add(amMesh);
            }
            Pointer mergedB = c.manifold_meshgl64_merge(mem(keep, meshSize), bmMesh);
            if (mergedB != null) {
                bmMesh = mergedB;
                meshes.add(bmMesh);
            }
            Pointer mA = c.manifold_of_meshgl64(mem(keep, manSize), amMesh);
            Pointer mB = c.manifold_of_meshgl64(mem(keep, manSize), bmMesh);
            if (mA == null || mB == null || c.manifold_status(mA) != 0
                    || c.manifold_status(mB) != 0) return null;
            manifolds.add(mA);
            manifolds.add(mB);
            Pointer result = c.manifold_difference(mem(keep, manSize), mA, mB);
            if (result == null || c.manifold_status(result) != 0) return null;
            manifolds.add(result);
            // 去掉切面边缘 < 2e-3 的残片/短边，不改动正常曲面。
            Pointer simplified = c.manifold_simplify(mem(keep, manSize), result, 5.0e-4);
            if (simplified != null && c.manifold_status(simplified) == 0) {
                result = simplified;
                manifolds.add(result);
            }
            Pointer outMesh = c.manifold_get_meshgl64(mem(keep, meshSize), result);
            if (outMesh == null) return null;
            meshes.add(outMesh);
            int nv = (int) c.manifold_meshgl64_num_vert(outMesh);
            int nt = (int) c.manifold_meshgl64_num_tri(outMesh);
            int np = (int) c.manifold_meshgl64_num_prop(outMesh);
            if (nv <= 0 || nt <= 0 || np < 3) return null;
            Memory p = mem(keep, (long) nv * np * 8);
            Memory t = mem(keep, (long) nt * 3 * 8);
            c.manifold_meshgl64_vert_properties(p, outMesh);
            c.manifold_meshgl64_tri_verts(t, outMesh);
            double[] ps = p.getDoubleArray(0, nv * np);
            long[] ts = t.getLongArray(0, nt * 3);
            List<SolidMesh.Vertex> vs = new ArrayList<>(nv);
            for (int i = 0; i < nv; i++) {
                vs.add(new SolidMesh.Vertex(new Vector3d(ps[i * np], ps[i * np + 1],
                        ps[i * np + 2]), new Vector3d(0, 0, 1), 0, 0));
            }
            int[] idx = new int[nt * 3];
            for (int i = 0; i < idx.length; i++) idx[i] = (int) ts[i];
            return new SolidMesh.Mesh(vs, idx);
        } catch (Throwable x) {
            System.out.println("[manifold] difference failed: " + x);
            return null;
        } finally {
            for (Pointer p : meshes) {
                try { c.manifold_destruct_meshgl64(p); } catch (Throwable ignored) { }
            }
            for (Pointer p : manifolds) {
                try { c.manifold_destruct_manifold(p); } catch (Throwable ignored) { }
            }
        }
    }

    private static Pointer mesh(Api c, List<Memory> keep, long size, SolidMesh.Mesh m) {
        double[] v = new double[m.vertices().size() * 3];
        for (int i = 0; i < m.vertices().size(); i++) {
            v[i * 3] = m.vertices().get(i).position().x;
            v[i * 3 + 1] = m.vertices().get(i).position().y;
            v[i * 3 + 2] = m.vertices().get(i).position().z;
        }
        int[] src = m.indices();
        long[] t = new long[src.length];
        for (int i = 0; i < src.length; i++) t[i] = src[i] & 0xFFFFFFFFL;
        return c.manifold_meshgl64(mem(keep, size), v, m.vertices().size(), 3,
                t, src.length / 3);
    }

    private static Memory mem(List<Memory> keep, long bytes) {
        Memory m = new Memory(Math.max(bytes, 8));
        keep.add(m);
        return m;
    }
}
