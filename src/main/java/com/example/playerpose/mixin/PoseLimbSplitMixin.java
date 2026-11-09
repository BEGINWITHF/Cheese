package com.example.playerpose.mixin;

import com.example.playerpose.pose.ModelSliceFixer;
import com.example.playerpose.pose.PoseLimbs;
import com.example.playerpose.pose.PoseLog;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.PlayerModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Turns each player limb into a continuous, finely sliced mesh instead of two rigid
 * halves.
 *
 * <p>The limb pivot ({@code left_arm} / {@code left_leg}) becomes an empty pivot and
 * twelve one-unit slices are its children. Each slice samples the matching rows of the
 * skin texture. The arm/leg {@code left_forearm} / {@code left_shin} part becomes an
 * empty marker at the elbow/knee centre so the pose data and the editor gizmo still
 * have a joint to aim at.</p>
 *
 * <p>{@link PoseModelMixin} bends the slice vertices with a smooth weight over a short
 * band around that marker, so the limb curves like a skinned mesh instead of breaking
 * in two. The second-layer sleeve/pants is sliced the same way; its 0.25 inflation is
 * clamped at the internal slice seams and kept at the shoulder/hip and wrist/ankle
 * ends.</p>
 */
@Mixin(PlayerModel.class)
public abstract class PoseLimbSplitMixin {

    /** Sleeve/pants second layer inflation. */
    private static final CubeDeformation OVERLAY = new CubeDeformation(0.25F);

    @Inject(
        method = "createMesh(Lnet/minecraft/client/model/geom/builders/CubeDeformation;Z)Lnet/minecraft/client/model/geom/builders/MeshDefinition;",
        at = @At("RETURN"),
        cancellable = true
    )
    private static void playerpose$buildBendableLimbs(CubeDeformation deformation, boolean slim,
                                                      CallbackInfoReturnable<MeshDefinition> cir) {
        MeshDefinition mesh = cir.getReturnValue();
        if (mesh == null) return;
        try {
            PartDefinition root = mesh.getRoot();
            int armWidth = slim ? 3 : 4;

            buildLimb(root, deformation, "left_arm", "left_forearm", "left_sleeve",
                32, 48, -1.0F, armWidth, 48, 48, -1.0F, 5.0F, 2.0F, -2.0F, 4.0F,
                PoseLimbs.SLICES);
            buildLimb(root, deformation, "right_arm", "right_forearm", "right_sleeve",
                40, 16, slim ? -2.0F : -3.0F, armWidth,
                40, 32, slim ? -2.0F : -3.0F, -5.0F, 2.0F, -2.0F, 4.0F,
                PoseLimbs.SLICES);

            buildLimb(root, deformation, "left_leg", "left_shin", "left_pants",
                16, 48, -2.0F, 4, 0, 48, -2.0F, 1.9F, 12.0F, 0.0F, 6.0F,
                PoseLimbs.SLICES);
            buildLimb(root, deformation, "right_leg", "right_shin", "right_pants",
                0, 16, -2.0F, 4, 0, 32, -2.0F, -1.9F, 12.0F, 0.0F, 6.0F,
                PoseLimbs.SLICES);

            addEyelids(root);

            PoseLog.info("model: built %d-slice bendable limbs (slim=%s)",
                PoseLimbs.SLICES, slim);
        } catch (Throwable t) {
            PoseLog.warn("model: bendable limb build failed, keeping single-piece limbs (%s)", t);
        }
    }

    private static void buildLimb(PartDefinition root, CubeDeformation deformation,
                                  String limbName, String jointName, String overlayName,
                                  int u, int v, float boxX, int width,
                                  int overlayU, int overlayV, float overlayBoxX,
                                  float pivotX, float pivotY, float startY, float seam,
                                  int slices) {
        if (root.getChild(limbName) == null) {
            PoseLog.warn("model: %s missing, skipping", limbName);
            return;
        }

        // Replace the vanilla limb cube with an empty pivot; the slices carry the skin.
        PartDefinition limb = root.addOrReplaceChild(limbName, CubeListBuilder.create(),
            PartPose.offset(pivotX, pivotY, 0.0F));
        // Keep the vanilla overlay name so PlayerModel still toggles its visibility;
        // the sleeve/pants slices are children of it.
        PartDefinition overlay = limb.addOrReplaceChild(overlayName, CubeListBuilder.create(),
            PartPose.ZERO);

        // Empty marker at the elbow/knee centre: pose data and the gizmo aim at this.
        float centerX = boxX + width / 2.0F;
        limb.addOrReplaceChild(jointName, CubeListBuilder.create(),
            PartPose.offset(centerX, seam, 0.0F));

        // Extra hidden slices, if a limb ever needs more than the twelve texture
        // rows, reuse the nearest row instead of sampling outside the skin.
        int extra = Math.max(0, slices - PoseLimbs.SLICES);
        for (int i = 0; i < slices; i++) {
            float y = startY + i;
            int row = Math.max(0, Math.min(PoseLimbs.SLICES - 1, i - extra));
            limb.addOrReplaceChild(limbName + "_slice" + i,
                CubeListBuilder.create().texOffs(u, v + row)
                    .addBox(boxX, 0.0F, -2.0F, width, PoseLimbs.SLICE_H, 4, deformation),
                PartPose.offset(0.0F, y, 0.0F));

            overlay.addOrReplaceChild(overlayName + "_slice" + i,
                CubeListBuilder.create().texOffs(overlayU, overlayV + row)
                    .addBox(overlayBoxX, 0.0F, -2.0F, width, PoseLimbs.SLICE_H, 4, OVERLAY),
                PartPose.offset(0.0F, y, 0.0F));
        }
    }

    /**
     * Post-bake cleanup:
     * <ul>
     *   <li>the last first-layer and overlay slice's far-end face (hand/foot) samples the
     *       original end face instead of side pixels;</li>
     *   <li>the overlay's internal slice seams lose their 0.25 inflation so adjacent
     *       slices meet flush; only the shoulder/hip and wrist/ankle ends stay inflated.</li>
     * </ul>
     */
    @Inject(method = "<init>(Lnet/minecraft/client/model/geom/ModelPart;Z)V", at = @At("TAIL"))
    private void playerpose$fixSlices(ModelPart root, boolean slim, CallbackInfo ci) {
        // UVs are normalized by the layer's declared texture size, not by the skin: the
        // skin layer is 64x64, the armor layers are 64x32. The hand/foot end face is
        // eleven rows away from where a one-unit slice puts it, so the row shift has to
        // use the right height or armor samples the middle of the arm's side texture.
        float textureHeight = isSkinModel(root) ? 64.0F : 32.0F;
        ModelSliceFixer.fixLimb(root, "left_arm", "left_sleeve", "left_arm_slice", "left_sleeve_slice",
            PoseLimbs.SLICES, textureHeight, true);
        ModelSliceFixer.fixLimb(root, "right_arm", "right_sleeve", "right_arm_slice", "right_sleeve_slice",
            PoseLimbs.SLICES, textureHeight, true);
        ModelSliceFixer.fixLimb(root, "left_leg", "left_pants", "left_leg_slice", "left_pants_slice",
            PoseLimbs.SLICES, textureHeight, true);
        ModelSliceFixer.fixLimb(root, "right_leg", "right_pants", "right_leg_slice", "right_pants_slice",
            PoseLimbs.SLICES, textureHeight, true);
        fixEyelids(root);
    }

    /** Only the split skin model carries the eyelid quads; armor meshes never do. */
    private static boolean isSkinModel(ModelPart root) {
        if (root == null || !root.hasChild("head")) return false;
        return root.getChild("head").hasChild("playerpose_left_eyelid");
    }

    private static void addEyelids(PartDefinition root) {
        PartDefinition head = root.getChild("head");
        if (head == null) return;
        head.addOrReplaceChild("playerpose_right_eyelid",
            CubeListBuilder.create().texOffs(11, 12)
                .addBox(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.01F),
            PartPose.ZERO);
        head.addOrReplaceChild("playerpose_left_eyelid",
            CubeListBuilder.create().texOffs(11, 12)
                .addBox(0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 0.01F),
            PartPose.ZERO);
    }

    private static void fixEyelids(ModelPart root) {
        if (root == null || !root.hasChild("head")) return;
        ModelPart head = root.getChild("head");
        fixEyelid(head, "playerpose_right_eyelid");
        fixEyelid(head, "playerpose_left_eyelid");
    }

    /** Make every face of the eyelid cube sample one skin pixel, for a flat lid. */
    private static void fixEyelid(ModelPart head, String name) {
        if (head == null || !head.hasChild(name)) return;
        ModelPart part = head.getChild(name);
        float u = 11.5F / 64.0F;
        float v = 12.5F / 64.0F;
        for (ModelPart.Cube cube : ((ModelPartAccessor) (Object) part).playerpose$cubes()) {
            for (int i = 0; i < cube.polygons.length; i++) {
                ModelPart.Polygon polygon = cube.polygons[i];
                ModelPart.Vertex[] old = polygon.vertices();
                ModelPart.Vertex[] fixed = new ModelPart.Vertex[old.length];
                for (int j = 0; j < old.length; j++) {
                    fixed[j] = old[j].remap(u, v);
                }
                cube.polygons[i] = new ModelPart.Polygon(fixed, polygon.normal());
            }
        }
    }
}
