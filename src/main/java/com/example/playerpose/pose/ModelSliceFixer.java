package com.example.playerpose.pose;

import com.example.playerpose.mixin.ModelPartAccessor;
import net.minecraft.client.model.geom.ModelPart;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Post-bake cleanup shared by the skin and armor limb slicers.
 *
 * <p>The limb splitter turns a limb into one-unit slice boxes. This fixer moves the
 * hand/foot end face back onto the original end-of-arm texture row, clamps the
 * inflation at internal slice seams so the slices meet flush, and removes the
 * internal horizontal caps that would otherwise render as little rims.</p>
 */
public final class ModelSliceFixer {

    private ModelSliceFixer() {}

    public static void fixLimb(ModelPart root, String limbName, String overlayName,
                               String firstBase, String secondBase, int slices,
                               float textureHeight, boolean addPlacementCube) {
        if (root == null || !root.hasChild(limbName)) return;
        ModelPart limb = root.getChild(limbName);
        ModelPart overlay = child(limb, overlayName);
        float endShift = -(slices - 1) / textureHeight;

        if (addPlacementCube) {
            addPlacementCube(limb, limbName, firstBase, slices);
        }

        for (int i = 0; i < slices; i++) {
            ModelPart first = child(limb, firstBase + i);
            if (first != null) {
                // Only the shoulder/hip and wrist/ankle ends are real caps; the internal
                // slice caps would peek out along the side of the limb as little rims.
                // Sliced armor carries the armor cube's own inflation, which has to be
                // trimmed at the internal seams or every slice would bulge past its
                // neighbours; with the player's zero inflation this is a no-op.
                if (i > 0) {
                    clampSeam(first, 0.0F, true);
                    removeHorizontalFace(first, -1.0F);
                }
                if (i < slices - 1) {
                    clampSeam(first, PoseLimbs.SLICE_H, false);
                    removeHorizontalFace(first, 1.0F);
                }
                if (i == slices - 1) {
                    shiftEndFace(first, endShift);
                }
            }

            ModelPart second = child(overlay, secondBase + i);
            if (second == null) continue;
            if (i > 0) {
                clampSeam(second, 0.0F, true);
                removeHorizontalFace(second, -1.0F);
            }
            if (i < slices - 1) {
                clampSeam(second, PoseLimbs.SLICE_H, false);
                removeHorizontalFace(second, 1.0F);
            }
            if (i == slices - 1) {
                shiftEndFace(second, endShift);
            }
        }
    }

    /**
     * The vanilla limb cube was replaced by the slice children, so the limb pivot has
     * no cubes of its own. StuckInBodyLayer picks a random body part and calls
     * ModelPart.getRandomCube, which crashes with "Bound must be positive" when that
     * part is empty (for example the inventory preview of a player with arrows stuck
     * in them). Keep an invisible cube with the original limb bounds so arrow
     * placement still lands on the limb.
     */
    private static void addPlacementCube(ModelPart limb, String limbName, String firstBase,
                                         int slices) {
        if (limb == null) return;
        ModelPartAccessor accessor = (ModelPartAccessor) (Object) limb;
        List<ModelPart.Cube> cubes = accessor.playerpose$cubes();
        if (!cubes.isEmpty()) return;
        ModelPart first = child(limb, firstBase + "0");
        if (first == null) return;
        List<ModelPart.Cube> sliceCubes =
            ((ModelPartAccessor) (Object) first).playerpose$cubes();
        if (sliceCubes.isEmpty()) return;
        ModelPart.Cube base = sliceCubes.get(0);
        ModelPart.Cube anchor = new ModelPart.Cube(0, 0,
            first.x + base.minX, first.y + base.minY, first.z + base.minZ,
            base.maxX - base.minX, slices * (base.maxY - base.minY),
            base.maxZ - base.minZ,
            0.0F, 0.0F, 0.0F, false, 64.0F, 64.0F, Set.of());
        // The baked cube list is unmodifiable, so hand the part a new list.
        List<ModelPart.Cube> replaced = new java.util.ArrayList<>(cubes);
        replaced.add(anchor);
        accessor.playerpose$setCubes(replaced);
        PoseLog.info("model: %s keeps an invisible placement cube for stuck projectiles", limbName);
    }

    private static ModelPart child(ModelPart parent, String name) {
        if (parent == null || !parent.hasChild(name)) return null;
        return parent.getChild(name);
    }

    /** Move every +Y (far end) face vertex back by dv rows of the declared texture height. */
    private static void shiftEndFace(ModelPart part, float dv) {
        List<ModelPart.Cube> cubes = ((ModelPartAccessor) (Object) part).playerpose$cubes();
        for (ModelPart.Cube cube : cubes) {
            for (int i = 0; i < cube.polygons.length; i++) {
                ModelPart.Polygon polygon = cube.polygons[i];
                if (polygon.normal().y() < 0.5F) continue; // +Y = hand/foot end
                ModelPart.Vertex[] old = polygon.vertices();
                ModelPart.Vertex[] fixed = new ModelPart.Vertex[old.length];
                for (int j = 0; j < old.length; j++) {
                    fixed[j] = old[j].remap(old[j].u(), old[j].v() + dv);
                }
                cube.polygons[i] = new ModelPart.Polygon(fixed, polygon.normal());
            }
        }
    }

    /**
     * Hide one horizontal cap by collapsing its vertices to a point. Internal slice
     * caps are not part of the outer surface; leaving them rendered leaves a little
     * rim of second-layer pixels at every slice boundary.
     */
    private static void removeHorizontalFace(ModelPart part, float normalY) {
        List<ModelPart.Cube> cubes = ((ModelPartAccessor) (Object) part).playerpose$cubes();
        for (ModelPart.Cube cube : cubes) {
            for (int i = 0; i < cube.polygons.length; i++) {
                ModelPart.Polygon polygon = cube.polygons[i];
                if (Math.abs(polygon.normal().y() - normalY) > 0.5F) continue;
                ModelPart.Vertex[] old = polygon.vertices();
                if (old.length == 0) continue;
                ModelPart.Vertex[] degenerate = new ModelPart.Vertex[old.length];
                Arrays.fill(degenerate, old[0]);
                cube.polygons[i] = new ModelPart.Polygon(degenerate, polygon.normal());
            }
        }
    }

    /** Put vertices past the slice boundary onto the boundary plane. */
    private static void clampSeam(ModelPart part, float seam, boolean below) {
        List<ModelPart.Cube> cubes = ((ModelPartAccessor) (Object) part).playerpose$cubes();
        for (ModelPart.Cube cube : cubes) {
            for (int i = 0; i < cube.polygons.length; i++) {
                ModelPart.Polygon polygon = cube.polygons[i];
                ModelPart.Vertex[] old = polygon.vertices();
                ModelPart.Vertex[] fixed = new ModelPart.Vertex[old.length];
                boolean changed = false;
                for (int j = 0; j < old.length; j++) {
                    ModelPart.Vertex v = old[j];
                    float y = v.pos().y();
                    if (below ? y < seam : y > seam) {
                        y = seam;
                        changed = true;
                    }
                    fixed[j] = y == v.pos().y() ? v
                        : new ModelPart.Vertex(v.pos().x(), y, v.pos().z(), v.u(), v.v());
                }
                if (changed) cube.polygons[i] = new ModelPart.Polygon(fixed, polygon.normal());
            }
        }
    }
}
