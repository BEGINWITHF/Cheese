package com.example.playerpose.mixin;

import com.example.playerpose.PlayerPoseClient;
import com.example.playerpose.pose.BoneMath;
import com.example.playerpose.pose.Pose;
import com.example.playerpose.pose.PoseBone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Poses the cape.
 *
 * <p>In 1.20.6 the cape is the {@code cloak} part of {@link PlayerModel}; there is no
 * separate cape model. By the tail of PlayerModel#setupAnim the vanilla sway
 * (capeFlap / capeLean / capeLean2) has already been applied to the part as a
 * quaternion, so a "cape" entry in the pose replaces it.</p>
 *
 * <p>The cape's baked rest rotation is 180 degrees around Y (that is what makes it hang
 * behind the player), so a pose rotation has to be added to that rest rotation.
 * Calling setRotation(x, y, z) with the pose values alone would flip the cape through
 * the body.</p>
 */
@Mixin(PlayerModel.class)
public abstract class PoseCapeMixin {

    @Final @Shadow private ModelPart cloak;

    @Inject(
        method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
        at = @At("TAIL")
    )
    private void playerpose$applyCapePose(LivingEntity entity, float limbSwing,
                                          float limbSwingAmount, float ageInTicks,
                                          float netHeadYaw, float headPitch,
                                          CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || entity == null) return;
        if (entity.getId() != mc.player.getId()) return;

        Pose pose = PlayerPoseClient.resolvePoseForLocalPlayer(mc);
        if (pose != null && pose.getBones() != null) {
            for (PoseBone bone : pose.getBones()) {
                if (bone == null || !"cape".equals(bone.bone)) continue;
                // A zeroed cape entry means "vanilla": keep the model's sway.
                if (bone.isRest()) continue;

                // The rest pose carries the 180-degree Y yaw that faces the cape
                // backwards; a pose rotation is added to it, never used alone. That flip
                // also inverts the local X axis, so the X pose value is negated to keep the
                // gizmo's X pointing the same way as the rest of the model.
                var rest = cloak.getInitialPose();
                float rx = rest == null ? 0.0F : rest.xRot;
                float ry = rest == null ? 0.0F : rest.yRot;
                float rz = rest == null ? 0.0F : rest.zRot;
                cloak.setRotation(rx - (float) bone.x, ry + (float) bone.y,
                    rz + (float) bone.z);

                double dx = bone.tx;
                double dy = bone.ty;
                double dz = bone.tz;
                if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 1.0E-4) {
                    float baseX = rest == null ? 0.0F : rest.x;
                    float baseY = rest == null ? 0.0F : rest.y;
                    float baseZ = rest == null ? 0.0F : rest.z;
                    cloak.setPos((float) (baseX + dx), (float) (baseY + dy), (float) (baseZ + dz));
                }
            }
        }

        // Published after the pose is on, so the editor gets the cape's real position
        // (including its translate). The cape hangs off the body, so compose its local
        // pivot with the body's posed position and rotation to get model space.
        ModelPart body = ((PlayerModel<?>) (Object) this).body;
        double[] off = BoneMath.rotateZYX(cloak.x, cloak.y, cloak.z,
            body.xRot, body.yRot, body.zRot);
        PlayerPoseClient.publishJointPivot("cape",
            (float) (body.x + off[0]), (float) (body.y + off[1]), (float) (body.z + off[2]));
    }
}
