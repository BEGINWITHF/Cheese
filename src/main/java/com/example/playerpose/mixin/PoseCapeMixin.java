package com.example.playerpose.mixin;

import com.example.playerpose.PlayerPoseClient;
import com.example.playerpose.pose.BoneMath;
import com.example.playerpose.pose.Pose;
import com.example.playerpose.pose.PoseBone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Poses the cape.
 *
 * <p>1.21.1 has no separate {@code PlayerCapeModel}: the cape is the {@code cloak}
 * part of {@link PlayerModel} and {@code CapeLayer} draws it with its own transform.
 * Applying the pose to that part is the equivalent of the 1.21.3 hook. The cloak's
 * baked rest pose is the neutral pose, so a pose rotation is added to it.</p>
 */
@Mixin(PlayerModel.class)
public abstract class PoseCapeMixin {

    @Shadow private ModelPart cloak;

    @Inject(
        method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
        at = @At("TAIL")
    )
    private void playerpose$applyCapePose(LivingEntity entity, float limbSwing, float limbSwingAmount,
                                          float ageInTicks, float netHeadYaw, float headPitch,
                                          CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!(entity instanceof Player player)) return;
        if (player.getId() != mc.player.getId()) return; // Model is shared by all players.

        Pose pose = PlayerPoseClient.resolvePoseForLocalPlayer(mc);
        if (pose != null && pose.getBones() != null) {
            for (PoseBone bone : pose.getBones()) {
                if (bone == null || !"cape".equals(bone.bone)) continue;
                // A zeroed cape entry means "vanilla": keep the model's rest pose.
                if (bone.isRest()) continue;

                var rest = cloak.getInitialPose();
                float rx = rest == null ? 0.0F : rest.xRot;
                float ry = rest == null ? 0.0F : rest.yRot;
                float rz = rest == null ? 0.0F : rest.zRot;
                // CapeLayer draws the cloak under a 180-degree Y flip, which inverts the
                // local X axis, so the X pose value is negated to keep the gizmo's X
                // pointing the same way as the rest of the model (same convention as the
                // separate cape model in 1.21.3).
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
        // The body part lives on HumanoidModel; a @Shadow here would target the
        // inherited field and fail to resolve, so read it through the superclass.
        ModelPart body = ((HumanoidModel<?>) (Object) this).body;
        double[] off = BoneMath.rotateZYX(cloak.x, cloak.y, cloak.z,
            body.xRot, body.yRot, body.zRot);
        PlayerPoseClient.publishJointPivot("cape",
            (float) (body.x + off[0]), (float) (body.y + off[1]), (float) (body.z + off[2]));
    }
}
