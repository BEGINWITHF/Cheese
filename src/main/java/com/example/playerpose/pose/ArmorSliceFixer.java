package com.example.playerpose.pose;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PlayerPoseGeometry;

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

    /** Move every +Y (far end) face vertex back by dv rows of the 64x32 armor texture. */
    private static void shiftEndFace(ModelPart part, float dv) {
        PlayerPoseGeometry.shiftEndFace(part, dv);
    }

    /** Hide one horizontal cap by collapsing its vertices to a point. */
    private static void removeHorizontalFace(ModelPart part, float normalY) {
        PlayerPoseGeometry.removeHorizontalFace(part, normalY);
    }

    /** Put vertices past the slice boundary onto the boundary plane. */
    private static void clampSeam(ModelPart part, float seam, boolean below) {
        PlayerPoseGeometry.clampSeam(part, seam, below);
    }
}
