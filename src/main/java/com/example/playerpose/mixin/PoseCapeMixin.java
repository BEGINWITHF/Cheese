package com.example.playerpose.mixin;

import com.example.playerpose.PlayerPoseClient;
import com.example.playerpose.pose.BoneMath;
import com.example.playerpose.pose.Pose;
import com.example.playerpose.pose.PoseBone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Poses the cape.
 *
 * The cape is NOT part of the player's own model - it is a separate model
 * (PlayerCapeModel) drawn by CapeLayer - so it needs its own hook. By the tail of
 * setupAnim the vanilla sway (capeFlap / capeLean / capeLean2) has already been
 * applied to the cape part as a quaternion, so a "cape" entry in the pose simply
 * replaces it, exactly like the bones in PoseModelMixin.
 *
 * The cape's baked rest rotation is 180 degrees around Y (that is what makes it hang
 * behind the player), so a pose rotation has to be added to that rest rotation.
 * Calling setRotation(x, y, z) with the pose values alone would flip the cape through
 * the body.
 */
@Mixin(PlayerCapeModel.class)
public abstract class PoseCapeMixin {

    @Shadow private ModelPart cape;

    @Inject(
        method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V",
        at = @At("TAIL")
    )
    private void playerpose$applyCapePose(AvatarRenderState state, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (state.id != mc.player.getId()) return; // AvatarRenderState#id is the entity id

        Pose pose = PlayerPoseClient.resolvePoseForLocalPlayer(mc);
        if (pose != null && pose.getBones() != null) {
            for (PoseBone bone : pose.getBones()) {
                if (bone == null || !"cape".equals(bone.bone)) continue;
                // A zeroed cape entry means "vanilla": keep the model's sway.
                if (bone.isRest()) continue;

                // The rest pose carries the 180-degree Y yaw that faces the cape
                // backwards; a pose rotation is added to it, never used alone.
                var rest = cape.getInitialPose();
                float rx = rest == null ? 0.0F : rest.xRot();
                float ry = rest == null ? 0.0F : rest.yRot();
                float rz = rest == null ? 0.0F : rest.zRot();
                cape.setRotation(rx + (float) bone.x, ry + (float) bone.y, rz + (float) bone.z);

                double dx = bone.tx;
                double dy = bone.ty;
                double dz = bone.tz;
                if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 1.0E-4) {
                    float baseX = rest == null ? 0.0F : rest.x();
                    float baseY = rest == null ? 0.0F : rest.y();
                    float baseZ = rest == null ? 0.0F : rest.z();
                    cape.setPos((float) (baseX + dx), (float) (baseY + dy), (float) (baseZ + dz));
                }
            }
        }

        // Published after the pose is on, so the editor gets the cape's real position
        // (including its translate). The cape hangs off the body, so compose its local
        // pivot with the body's posed position and rotation to get model space.
        ModelPart body = ((PlayerCapeModel) (Object) this).body;
        double[] off = BoneMath.rotateZYX(cape.x, cape.y, cape.z, body.xRot, body.yRot, body.zRot);
        PlayerPoseClient.publishJointPivot("cape",
            (float) (body.x + off[0]), (float) (body.y + off[1]), (float) (body.z + off[2]));
    }
}
