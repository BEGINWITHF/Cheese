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
import org.spongepowered.asm.mixin.Unique;
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
 */
@Mixin(PlayerCapeModel.class)
public abstract class PoseCapeMixin {

    @Shadow private ModelPart cape;

    /** Cape rest pivot, captured before this mod ever moves the part. */
    @Unique private static float[] playerpose$capeRest;

    @Inject(
        method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V",
        at = @At("TAIL")
    )
    private void playerpose$applyCapePose(AvatarRenderState state, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (state.id != mc.player.getId()) return; // AvatarRenderState#id is the entity id

        if (playerpose$capeRest == null) {
            playerpose$capeRest = new float[] {cape.x, cape.y, cape.z};
        }
        // Same pivot the editor anchors its gizmo on.
        PlayerPoseClient.publishJointPivot("cape", playerpose$capeRest[0], playerpose$capeRest[1], playerpose$capeRest[2]);

        Pose pose = PlayerPoseClient.resolvePoseForLocalPlayer(mc);
        if (pose == null || pose.getBones() == null || pose.getBones().isEmpty()) return;

        for (PoseBone bone : pose.getBones()) {
            if (bone == null || !"cape".equals(bone.bone)) continue;

            cape.setRotation((float) bone.x, (float) bone.y, (float) bone.z);

            double[] off = BoneMath.cursorOffset(pose, bone);
            double dx = off[0] + bone.tx;
            double dy = off[1] + bone.ty;
            double dz = off[2] + bone.tz;
            if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 1.0E-4) {
                cape.setPos((float) (playerpose$capeRest[0] + dx),
                            (float) (playerpose$capeRest[1] + dy),
                            (float) (playerpose$capeRest[2] + dz));
            }
        }
    }
}
