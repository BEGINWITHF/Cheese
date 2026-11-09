package com.example.playerpose.mixin;

import com.example.playerpose.pose.PoseLimbs;
import com.example.playerpose.pose.PoseLog;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.PlayerPoseGeometry;
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
        fixLimb(root, "left_arm", "left_sleeve", "left_arm_slice", "left_sleeve_slice",
            PoseLimbs.SLICES, textureHeight);
        fixLimb(root, "right_arm", "right_sleeve", "right_arm_slice", "right_sleeve_slice",
            PoseLimbs.SLICES, textureHeight);
        fixLimb(root, "left_leg", "left_pants", "left_leg_slice", "left_pants_slice",
            PoseLimbs.SLICES, textureHeight);
        fixLimb(root, "right_leg", "right_pants", "right_leg_slice", "right_pants_slice",
            PoseLimbs.SLICES, textureHeight);
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
        PlayerPoseGeometry.fixEyelid(part, 11.5F / 64.0F, 12.5F / 64.0F);
    }

    private static void fixLimb(ModelPart root, String limbName, String overlayName,
                                String firstBase, String secondBase, int slices,
                                float textureHeight) {
        if (root == null || !root.hasChild(limbName)) return;
        ModelPart limb = root.getChild(limbName);
        ModelPart overlay = child(limb, overlayName);
        float endShift = -(slices - 1) / textureHeight;

        addPlacementCube(limb, limbName, firstBase, slices);

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

    /** Move every +Y (far end) face vertex back by dv rows of the 64x64 skin texture. */
    private static void shiftEndFace(ModelPart part, float dv) {
        PlayerPoseGeometry.shiftEndFace(part, dv);
    }

    /**
     * Hide one horizontal cap by collapsing its vertices to a point. Internal slice
     * caps are not part of the outer surface; leaving them rendered leaves a little
     * rim of second-layer pixels at every slice boundary.
     */
    private static void removeHorizontalFace(ModelPart part, float normalY) {
        PlayerPoseGeometry.removeHorizontalFace(part, normalY);
    }

    /** Put vertices past the slice boundary onto the boundary plane. */
    private static void clampSeam(ModelPart part, float seam, boolean below) {
        PlayerPoseGeometry.clampSeam(part, seam, below);
    }
}
