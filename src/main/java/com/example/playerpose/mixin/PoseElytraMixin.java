package com.example.playerpose.mixin;

import com.example.playerpose.PlayerPoseClient;
import com.example.playerpose.pose.Pose;
import com.example.playerpose.pose.PoseBone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Poses the elytra.
 *
 * <p>The wings are direct children of the elytra model root and {@code AgeableListModel}
 * renders the wing parts directly (it does not walk the root), so 1.21.1 cannot re-parent
 * them under a body/elytra anchor like 1.21.3 does. Instead the composed body + elytra
 * transform is applied to each wing at the tail of {@code setupAnim}: the wing's vanilla
 * local transform is preserved and the assembly rotation is pre-multiplied, which is the
 * same result a parent anchor would produce.</p>
 */
@Mixin(ElytraModel.class)
public abstract class PoseElytraMixin {

    @Shadow private ModelPart leftWing;
    @Shadow private ModelPart rightWing;

    @Inject(
        method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
        at = @At("TAIL")
    )
    private void playerpose$applyBodyPose(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                          float ageInTicks, float netHeadYaw, float headPitch,
                                          CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(entity instanceof Player avatar)) return;

        Pose pose = PlayerPoseClient.resolvePoseForEntity(mc, avatar.getId());
        PoseBone bodyBone = findBone(pose, "body");
        PoseBone elytraBone = findBone(pose, "cape");
        PoseBone spread = findBone(pose, "elytraSpread");

        boolean hasBody = bodyBone != null && !bodyBone.isRest();
        boolean hasElytra = elytraBone != null && !elytraBone.isRest();
        boolean hasSpread = spread != null && !spread.isRest();
        if (!hasBody && !hasElytra && !hasSpread) return;

        // Compose in the same order ModelPart uses: Z, then Y, then X.
        Quaternionf assembly = new Quaternionf();
        Vector3f translation = new Vector3f();
        if (hasBody) {
            assembly.rotateZ((float) bodyBone.z)
                .rotateY((float) bodyBone.y)
                .rotateX((float) bodyBone.x);
            translation.add((float) bodyBone.tx, (float) bodyBone.ty, (float) bodyBone.tz);
        }
        if (hasElytra) {
            assembly.rotateZ((float) elytraBone.z)
                .rotateY((float) elytraBone.y)
                .rotateX((float) elytraBone.x);
            translation.add((float) elytraBone.tx, (float) elytraBone.ty, (float) elytraBone.tz);
        }

        // Opening angles: sweep both wings outward around each axis. The three values live
        // in one pseudo-bone so they save with the pose and stay undoable.
        PoseBone mirrorSpread = hasSpread ? spread : null;
        playerpose$composeWing(leftWing, assembly, translation, mirrorSpread, 1.0F, 1.0F, -1.0F);
        playerpose$composeWing(rightWing, assembly, translation, mirrorSpread, -1.0F, -1.0F, 1.0F);

        // The gizmo needs a pivot even while the wings hang off the torso centre.
        if (avatar.getId() == mc.player.getId()) {
            PlayerPoseClient.publishJointPivot("cape", translation.x, translation.y, translation.z);
        }
    }

    private static void playerpose$composeWing(ModelPart wing, Quaternionf assembly,
                                               Vector3f translation, PoseBone spread,
                                               float signX, float signY, float signZ) {
        if (wing == null) return;
        float rotX = wing.xRot;
        float rotY = wing.yRot;
        float rotZ = wing.zRot;
        if (spread != null) {
            rotX += signX * (float) spread.x;
            rotY += signY * (float) spread.y;
            rotZ += signZ * (float) spread.z;
        }

        Quaternionf local = new Quaternionf().rotationZYX(rotZ, rotY, rotX);
        Quaternionf combined = new Quaternionf(assembly).mul(local);
        Vector3f euler = combined.getEulerAnglesZYX(new Vector3f());
        wing.setRotation(euler.x, euler.y, euler.z);

        var rest = wing.getInitialPose();
        Vector3f pos = new Vector3f(rest == null ? wing.x : rest.x,
            rest == null ? wing.y : rest.y,
            rest == null ? wing.z : rest.z);
        assembly.transform(pos).add(translation);
        wing.setPos(pos.x, pos.y, pos.z);
    }

    private static PoseBone findBone(Pose pose, String name) {
        if (pose == null || pose.getBones() == null) return null;
        for (PoseBone bone : pose.getBones()) {
            if (bone != null && name.equals(bone.bone)) return bone;
        }
        return null;
    }
}
