package com.example.playerpose.pose;

import com.example.playerpose.compat.PoseCubeAccess;
import com.example.playerpose.mixin.ModelPartAccessor;
import net.minecraft.client.model.geom.ModelPart;

import java.util.List;

/**
 * Post-bake fixup for the sliced humanoid armor meshes.
 *
 * <p>The armor limbs are baked from the {@code HumanoidArmorModel} body layer, which
 * always uses a 64x32 texture and has no second-layer sleeve/pants part. The slicing
 * itself lives in {@code PoseArmorSplitMixin}; this class only trims the internal
 * slice seams and moves the last slice's end face onto the original hand/foot pixels,
 * exactly like the skin path does.</p>
 */
public final class ArmorSliceFixer {

    private static final float TEXTURE_HEIGHT = 32.0F;

    private ArmorSliceFixer() {}

    public static void fix(ModelPart root) {
        if (root == null) return;
        fixLimb(root, "left_arm", "left_arm_slice", PoseLimbs.SLICES);
        fixLimb(root, "right_arm", "right_arm_slice", PoseLimbs.SLICES);
        fixLimb(root, "left_leg", "left_leg_slice", PoseLimbs.SLICES);
        fixLimb(root, "right_leg", "right_leg_slice", PoseLimbs.SLICES);
    }

    private static void fixLimb(ModelPart root, String limbName, String sliceBase, int slices) {
        if (!root.hasChild(limbName)) return;
        ModelPart limb = root.getChild(limbName);
        float endShift = -(slices - 1) / TEXTURE_HEIGHT;

        for (int i = 0; i < slices; i++) {
            if (!limb.hasChild(sliceBase + i)) continue;
            ModelPart slice = limb.getChild(sliceBase + i);
            if (i > 0) {
                clampSeam(slice, 0.0F, true);
                removeHorizontalFace(slice, -1.0F);
            }
            if (i < slices - 1) {
                clampSeam(slice, PoseLimbs.SLICE_H, false);
                removeHorizontalFace(slice, 1.0F);
            }
            if (i == slices - 1) {
                shiftEndFace(slice, endShift);
            }
        }
    }

    private static List<ModelPart.Cube> cubes(ModelPart part) {
        return ((ModelPartAccessor) (Object) part).playerpose$cubes();
    }

    /** Move every +Y (far end) face vertex back by dv rows of the 64x32 armor texture. */
    private static void shiftEndFace(ModelPart part, float dv) {
        for (ModelPart.Cube cube : cubes(part)) {
            PoseCubeAccess access = PoseCubeAccess.of(cube);
            for (int i = 0; i < access.playerpose$polygonCount(); i++) {
                float[] normal = access.playerpose$normal(i);
                if (normal[1] < 0.5F) continue; // +Y = hand/foot end
                float[] data = access.playerpose$vertices(i);
                for (int j = 0; j + 4 < data.length; j += 5) {
                    data[j + 4] += dv;
                }
                access.playerpose$setPolygon(i, data, normal[0], normal[1], normal[2]);
            }
        }
    }

    /** Hide one horizontal cap by collapsing its vertices to a point. */
    private static void removeHorizontalFace(ModelPart part, float normalY) {
        for (ModelPart.Cube cube : cubes(part)) {
            PoseCubeAccess access = PoseCubeAccess.of(cube);
            for (int i = 0; i < access.playerpose$polygonCount(); i++) {
                float[] normal = access.playerpose$normal(i);
                if (Math.abs(normal[1] - normalY) > 0.5F) continue;
                float[] data = access.playerpose$vertices(i);
                if (data.length < 5) continue;
                for (int j = 5; j + 4 < data.length; j += 5) {
                    System.arraycopy(data, 0, data, j, 5);
                }
                access.playerpose$setPolygon(i, data, normal[0], normal[1], normal[2]);
            }
        }
    }

    /** Put vertices past the slice boundary onto the boundary plane. */
    private static void clampSeam(ModelPart part, float seam, boolean below) {
        for (ModelPart.Cube cube : cubes(part)) {
            PoseCubeAccess access = PoseCubeAccess.of(cube);
            for (int i = 0; i < access.playerpose$polygonCount(); i++) {
                float[] data = access.playerpose$vertices(i);
                boolean changed = false;
                for (int j = 0; j + 4 < data.length; j += 5) {
                    float y = data[j + 1];
                    if (below ? y < seam : y > seam) {
                        data[j + 1] = seam;
                        changed = true;
                    }
                }
                if (changed) {
                    float[] normal = access.playerpose$normal(i);
                    access.playerpose$setPolygon(i, data, normal[0], normal[1], normal[2]);
                }
            }
        }
    }
}
