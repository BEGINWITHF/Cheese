package com.example.playerpose.mixin;

import com.example.playerpose.pose.PoseLog;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.player.PlayerModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Splits the player's arms and legs into two segments each, so a pose can bend an
 * elbow or a knee instead of rotating a limb as one rigid piece.
 *
 * This works on the mesh definition that PlayerModel.createMesh returns, before it is
 * baked: each limb's 12-unit cube is replaced by a 6-unit upper cube plus a child
 * 6-unit lower cube pivoted at the joint (y = +6 in the limb's own space). The lower
 * cube samples the limb texture starting 6 rows further down, so its side pixels are
 * the lower half of the arm/leg texture rather than a squashed copy.
 *
 * Sleeves and pants are children of the limbs, so they are split the same way; the
 * upper overlay keeps its original name so PlayerModel keeps syncing it with the limb,
 * and the lower overlay is a child of the lower limb so it follows the elbow/knee for
 * free.
 *
 * Everything is wrapped: if any of this fails, the original mesh is returned untouched
 * and the limbs stay single-piece - the mod still works, just without elbow/knee.
 */
@Mixin(PlayerModel.class)
public abstract class PoseLimbSplitMixin {

    /** Half of the vanilla 12-unit limb; both halves sample from the same texture column. */
    private static final int HALF = 6;
    private static final CubeDeformation OVERLAY = new CubeDeformation(0.25F);

    @Inject(
        method = "createMesh(Lnet/minecraft/client/model/geom/builders/CubeDeformation;Z)Lnet/minecraft/client/model/geom/builders/MeshDefinition;",
        at = @At("RETURN"),
        cancellable = true
    )
    private static void playerpose$splitLimbs(CubeDeformation deformation, boolean slim,
                                              CallbackInfoReturnable<MeshDefinition> cir) {
        MeshDefinition mesh = cir.getReturnValue();
        if (mesh == null) return;
        try {
            PartDefinition root = mesh.getRoot();
            int armWidth = slim ? 3 : 4;

            // arms: cube x offset, texture origin, pivot
            split(root, deformation, "left_arm", "left_forearm", "left_sleeve",
                32, 48, -1.0F, -2.0F, armWidth, 48, 48, -1.0F, 5.0F, 2.0F, 0.0F);
            split(root, deformation, "right_arm", "right_forearm", "right_sleeve",
                40, 16, -2.0F, -2.0F, armWidth, 40, 32, slim ? -2.0F : -3.0F, -5.0F, 2.0F, 0.0F);

            // legs: same shape for both model types
            split(root, deformation, "left_leg", "left_shin", "left_pants",
                16, 48, -2.0F, 0.0F, 4, 0, 48, -2.0F, 1.9F, 12.0F, 0.0F);
            split(root, deformation, "right_leg", "right_shin", "right_pants",
                0, 16, -2.0F, 0.0F, 4, 0, 32, -2.0F, -1.9F, 12.0F, 0.0F);

            PoseLog.info("model: split arms and legs into two segments each (slim=%s)", slim);
        } catch (Throwable t) {
            PoseLog.warn("model: limb split failed, keeping single-segment limbs (%s)", t);
        }
    }

    private static void split(PartDefinition root, CubeDeformation deformation,
                              String limbName, String lowerName, String overlayName,
                              int u, int v, float boxX, float boxY, int width,
                              int overlayU, int overlayV, float overlayBoxX,
                              float pivotX, float pivotY, float pivotZ) {
        if (root.getChild(limbName) == null) {
            PoseLog.warn("model: %s missing, skipping its split", limbName);
            return;
        }

        PartDefinition upper = root.addOrReplaceChild(limbName,
            CubeListBuilder.create().texOffs(u, v)
                .addBox(boxX, boxY, -2.0F, width, HALF, 4, deformation),
            PartPose.offset(pivotX, pivotY, pivotZ));

        // Upper overlay keeps the vanilla name so PlayerModel keeps driving it.
        upper.addOrReplaceChild(overlayName,
            CubeListBuilder.create().texOffs(overlayU, overlayV)
                .addBox(overlayBoxX, boxY, -2.0F, width, HALF, 4, OVERLAY),
            PartPose.ZERO);

        // Pivot at the geometric seam of the limb, which is not always +6: the vanilla arm
        // cube spans -2..10, so its midpoint is +4, while the leg cube spans 0..12 (+6).
        float seam = boxY + HALF;
        PartDefinition lower = upper.addOrReplaceChild(lowerName,
            CubeListBuilder.create().texOffs(u, v + HALF)
                .addBox(boxX, 0.0F, -2.0F, width, HALF, 4, deformation),
            PartPose.offset(0.0F, seam, 0.0F));

        // The lower overlay hangs off the lower limb, whose pivot IS the joint - so it
        // sits at zero here. Adding another 6-unit offset (as this first did) pushes the
        // second skin layer 6 units past the elbow, which is exactly the floating sleeve
        // it used to show.
        lower.addOrReplaceChild(overlayName + "_lower",
            CubeListBuilder.create().texOffs(overlayU, overlayV + HALF)
                .addBox(overlayBoxX, 0.0F, -2.0F, width, HALF, 4, OVERLAY),
            PartPose.ZERO);
    }
}
