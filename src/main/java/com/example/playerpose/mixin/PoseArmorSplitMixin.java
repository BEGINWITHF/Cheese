package com.example.playerpose.mixin;

import com.example.playerpose.pose.ModelSliceFixer;
import com.example.playerpose.pose.PoseLimbs;
import com.example.playerpose.pose.PoseLog;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDefinition;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.geom.builders.UVPair;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Gives humanoid armor the same twelve-slice limbs as the skin, so a chestplate or
 * leggings bends with the elbow/knee instead of staying a straight rigid box.
 *
 * <p>1.21.5 bakes player armor from {@link HumanoidArmorModel#createBodyLayer}, the
 * shared humanoid armor mesh, rather than from a player-specific armor model set.
 * The limb boxes still come from the same humanoid mesh and are the same twelve units
 * tall, so the slicing code is unchanged.</p>
 */
@Mixin(HumanoidArmorModel.class)
public abstract class PoseArmorSplitMixin {

    @Inject(
        method = "createBodyLayer(Lnet/minecraft/client/model/geom/builders/CubeDeformation;)"
            + "Lnet/minecraft/client/model/geom/builders/MeshDefinition;",
        at = @At("RETURN")
    )
    private static void playerpose$sliceArmorLimbs(CubeDeformation deformation,
                                                   CallbackInfoReturnable<MeshDefinition> cir) {
        MeshDefinition mesh = cir.getReturnValue();
        if (mesh == null) return;
        try {
            PartDefinition root = mesh.getRoot();
            int sliced = 0;
            sliced += slice(root, "left_arm", "left_forearm");
            sliced += slice(root, "right_arm", "right_forearm");
            sliced += slice(root, "left_leg", "left_shin");
            sliced += slice(root, "right_leg", "right_shin");
            PoseLog.info("armor: built %d-slice bendable armor limbs (%d part(s))",
                PoseLimbs.SLICES, sliced);
        } catch (Throwable t) {
            PoseLog.warn("armor: sliced armor build failed, keeping the rigid armor model (%s)", t);
        }
    }

    /**
     * 1.21.5 bakes armor as a HumanoidArmorModel instead of a PlayerModel, so it misses
     * the post-bake cleanup {@link PoseLimbSplitMixin} performs for the skin rig.
     * Run the same seam/end-face fix on the baked armor root.
     */
    @Inject(method = "<init>(Lnet/minecraft/client/model/geom/ModelPart;)V", at = @At("TAIL"))
    private void playerpose$fixArmorSlices(ModelPart root, CallbackInfo ci) {
        ModelSliceFixer.fixLimb(root, "left_arm", "left_sleeve", "left_arm_slice",
            "left_sleeve_slice", PoseLimbs.SLICES, 32.0F, false);
        ModelSliceFixer.fixLimb(root, "right_arm", "right_sleeve", "right_arm_slice",
            "right_sleeve_slice", PoseLimbs.SLICES, 32.0F, false);
        ModelSliceFixer.fixLimb(root, "left_leg", "left_pants", "left_leg_slice",
            "left_pants_slice", PoseLimbs.SLICES, 32.0F, false);
        ModelSliceFixer.fixLimb(root, "right_leg", "right_pants", "right_leg_slice",
            "right_pants_slice", PoseLimbs.SLICES, 32.0F, false);
    }

    private static int slice(PartDefinition root, String limbName, String jointName) {
        PartDefinition limb = root.getChild(limbName);
        if (limb == null) return 0;

        List<CubeDefinition> cubes = ((PartDefinitionAccessor) limb).playerpose$cubes();
        if (cubes == null || cubes.size() != 1) return 0;

        CubeDefinitionAccessor cube = (CubeDefinitionAccessor) (Object) cubes.get(0);
        Vector3f origin = cube.playerpose$origin();
        Vector3f size = cube.playerpose$dimensions();
        if (origin == null || size == null) return 0;
        if (Math.abs(size.y() - PoseLimbs.SLICES) > 1.0E-3F) return 0;

        UVPair uv = cube.playerpose$texCoord();
        CubeDeformation grow = cube.playerpose$grow();
        boolean mirror = cube.playerpose$mirror();
        PartPose pose = ((PartDefinitionAccessor) limb).playerpose$partPose();
        if (pose == null) pose = PartPose.ZERO;

        float startY = origin.y();
        PartDefinition sliced = root.addOrReplaceChild(limbName, CubeListBuilder.create(), pose);

        // Empty elbow/knee marker: the pose data and the bend code aim at this.
        sliced.addOrReplaceChild(jointName, CubeListBuilder.create(),
            PartPose.offset(origin.x() + size.x() / 2.0F,
                startY + PoseLimbs.SLICES / 2.0F, 0.0F));

        for (int i = 0; i < PoseLimbs.SLICES; i++) {
            CubeListBuilder builder = CubeListBuilder.create()
                .texOffs((int) uv.u(), (int) uv.v() + i);
            if (mirror) builder.mirror();
            builder.addBox(origin.x(), 0.0F, origin.z(),
                size.x(), PoseLimbs.SLICE_H, size.z(), grow);
            sliced.addOrReplaceChild(limbName + "_slice" + i, builder,
                PartPose.offset(0.0F, startY + i, 0.0F));
        }
        return 1;
    }
}
