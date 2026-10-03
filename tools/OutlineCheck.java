package com.ovo.sablestopnow.tools;

import com.ovo.sablestopnow.client.OutlineGeometry;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

/**
 * Offline assertions for the model-outline geometry ({@link OutlineGeometry}).
 *
 * <p>Why it exists: the "which block edges get drawn" rules failed <b>silently</b> once. The interior-seam
 * test compared two blocks' face planes after adding a +/-1 block offset to the face axis, so it could
 * never be true; no seam was ever culled and every flat surface came out cut into a grid of lines.
 * Nothing crashed and nothing logged - it just looked wrong. A predicate that is never true looks
 * exactly like a predicate that is always false, so it needs assertions.
 */
public final class OutlineCheck {

    private static int checks;
    private static int failures;

    private OutlineCheck() {
    }

    public static void main(final String[] args) {
        final AABB full = new AABB(0, 0, 0, 1, 1, 1);
        final AABB slabBottom = new AABB(0, 0, 0, 1, 0.5, 1);
        final AABB slabTop = new AABB(0, 0.5, 0, 1, 1, 1);
        // flush against the shared plane but only a quarter of the cross-section
        final AABB quarter = new AABB(0, 0, 0, 1, 1, 0.25);

        // ---- coplanar test: neighbours are offset along an IN-PLANE axis, so the face axis is unchanged ----
        ok("full/full top faces are coplanar", OutlineGeometry.coplanarFace(full, full, Direction.UP));
        ok("full vs bottom slab above: NOT coplanar (0.5 vs 1.0)",
                !OutlineGeometry.coplanarFace(full, slabBottom, Direction.UP));
        ok("full vs top slab above: coplanar", OutlineGeometry.coplanarFace(full, slabTop, Direction.UP));
        ok("bottom faces of two stacked full blocks are coplanar",
                OutlineGeometry.coplanarFace(full, full, Direction.DOWN));
        ok("full vs the slab directly below (same plane)",
                OutlineGeometry.coplanarFace(full, slabBottom, Direction.DOWN));

        // ---- occlusion test ----
        ok("full block above covers the top face", OutlineGeometry.coversFace(full, full, Direction.UP));
        ok("bottom slab sitting flush on top covers the top face",
                OutlineGeometry.coversFace(slabBottom, full, Direction.UP));
        ok("a quarter-section column flush on top does NOT cover the whole face",
                !OutlineGeometry.coversFace(quarter, full, Direction.UP));
        ok("a slab resting one level up does NOT cover (not on the same plane)",
                !OutlineGeometry.coversFace(slabTop, full, Direction.UP));
        ok("full block to the east covers the east face",
                OutlineGeometry.coversFace(full, full, Direction.EAST));
        ok("a half-width slab to the east does NOT cover the whole east face",
                !OutlineGeometry.coversFace(new AABB(0, 0, 0, 0.5, 1, 0.5), full, Direction.EAST));
        ok("a full-height, full-depth slab flush to the east DOES cover it",
                OutlineGeometry.coversFace(new AABB(0, 0, 0, 0.5, 1, 1), full, Direction.EAST));

        // ---- regression for the silent failure ----
        ok("regression: coplanarFace does not fold the block offset into the face axis",
                OutlineGeometry.coplanarFace(slabTop, slabTop, Direction.UP));
        ok("regression: two adjacent blocks on a flat wall are coplanar (UP and NORTH)",
                OutlineGeometry.coplanarFace(full, full, Direction.UP)
                        && OutlineGeometry.coplanarFace(full, full, Direction.NORTH));

        System.out.println("OUTLINE_CHECK=" + (failures == 0 ? "OK" : "FAILED")
                + " checks=" + checks + " failures=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void ok(final String name, final boolean condition) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("  FAIL " + name);
        }
    }
}
