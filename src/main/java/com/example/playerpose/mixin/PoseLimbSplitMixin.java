package com.example.playerpose.mixin;

import com.example.playerpose.pose.PoseLimbs;
import com.example.playerpose.pose.PoseLog;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.player.PlayerModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.List;

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
                32, 48, -1.0F, armWidth, 48, 48, -1.0F, 5.0F, 2.0F, -2.0F, 4.0F);
            buildLimb(root, deformation, "right_arm", "right_forearm", "right_sleeve",
                40, 16, slim ? -2.0F : -3.0F, armWidth,
                40, 32, slim ? -2.0F : -3.0F, -5.0F, 2.0F, -2.0F, 4.0F);

            buildLimb(root, deformation, "left_leg", "left_shin", "left_pants",
                16, 48, -2.0F, 4, 0, 48, -2.0F, 1.9F, 12.0F, 0.0F, 6.0F);
            buildLimb(root, deformation, "right_leg", "right_shin", "right_pants",
                0, 16, -2.0F, 4, 0, 32, -2.0F, -1.9F, 12.0F, 0.0F, 6.0F);

            PoseLog.info("model: built %d-slice bendable limbs (slim=%s)", PoseLimbs.SLICES, slim);
        } catch (Throwable t) {
            PoseLog.warn("model: bendable limb build failed, keeping single-piece limbs (%s)", t);
        }
    }

    private static void buildLimb(PartDefinition root, CubeDeformation deformation,
                                  String limbName, String jointName, String overlayName,
                                  int u, int v, float boxX, int width,
                                  int overlayU, int overlayV, float overlayBoxX,
                                  float pivotX, float pivotY, float startY, float seam) {
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

        for (int i = 0; i < PoseLimbs.SLICES; i++) {
            float y = startY + i;
            limb.addOrReplaceChild(limbName + "_slice" + i,
                CubeListBuilder.create().texOffs(u, v + i)
                    .addBox(boxX, 0.0F, -2.0F, width, PoseLimbs.SLICE_H, 4, deformation),
                PartPose.offset(0.0F, y, 0.0F));

            overlay.addOrReplaceChild(overlayName + "_slice" + i,
                CubeListBuilder.create().texOffs(overlayU, overlayV + i)
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
        fixLimb(root, "left_arm", "left_sleeve", "left_arm_slice", "left_sleeve_slice");
        fixLimb(root, "right_arm", "right_sleeve", "right_arm_slice", "right_sleeve_slice");
        fixLimb(root, "left_leg", "left_pants", "left_leg_slice", "left_pants_slice");
        fixLimb(root, "right_leg", "right_pants", "right_leg_slice", "right_pants_slice");
    }

    private static void fixLimb(ModelPart root, String limbName, String overlayName,
                                String firstBase, String secondBase) {
        if (root == null || !root.hasChild(limbName)) return;
        ModelPart limb = root.getChild(limbName);
        ModelPart overlay = child(limb, overlayName);

        for (int i = 0; i < PoseLimbs.SLICES; i++) {
            ModelPart first = child(limb, firstBase + i);
            if (first != null) {
                // Only the shoulder/hip and wrist/ankle ends are real caps; the internal
                // slice caps would peek out along the side of the limb as little rims.
                if (i > 0) removeHorizontalFace(first, -1.0F);
                if (i < PoseLimbs.SLICES - 1) removeHorizontalFace(first, 1.0F);
                if (i == PoseLimbs.SLICES - 1) {
                    shiftEndFace(first, -11.0F / 64.0F);
                }
            }

            ModelPart second = child(overlay, secondBase + i);
            if (second == null) continue;
            if (i > 0) {
                clampSeam(second, 0.0F, true);
                removeHorizontalFace(second, -1.0F);
            }
            if (i < PoseLimbs.SLICES - 1) {
                clampSeam(second, PoseLimbs.SLICE_H, false);
                removeHorizontalFace(second, 1.0F);
            }
            if (i == PoseLimbs.SLICES - 1) {
                shiftEndFace(second, -11.0F / 64.0F);
            }
        }
    }

    private static ModelPart child(ModelPart parent, String name) {
        if (parent == null || !parent.hasChild(name)) return null;
        return parent.getChild(name);
    }

    /** Move every +Y (far end) face vertex back by dv rows of the 64x64 skin texture. */
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
                    float y = v.y();
                    if (below ? y < seam : y > seam) {
                        y = seam;
                        changed = true;
                    }
                    fixed[j] = y == v.y() ? v : new ModelPart.Vertex(v.x(), y, v.z(), v.u(), v.v());
                }
                if (changed) cube.polygons[i] = new ModelPart.Polygon(fixed, polygon.normal());
            }
        }
    }
}
