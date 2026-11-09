package com.example.playerpose.mixin;

import com.example.playerpose.PlayerPoseClient;
import com.example.playerpose.pose.Pose;
import com.example.playerpose.pose.PoseBone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ElytraModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Poses the elytra.
 *
 * <p>The wings are direct children of the elytra model root, not of the player's body part,
 * so they have to be re-parented twice:</p>
 * <ul>
 *   <li>an outer anchor replays the torso pose, so a leaning or twisting body carries the
 *       wings with it instead of leaving them standing straight;</li>
 *   <li>an inner anchor carries the "elytra" bone, which is what the editor edits - the
 *       cape and the elytra cannot be worn together, so the elytra gets the same kind of
 *       dedicated bone instead of only inheriting the body.</li>
 * </ul>
 */
@Mixin(ElytraModel.class)
public abstract class PoseElytraMixin {

    private static final String BODY_ANCHOR = "playerpose_body_anchor";
    private static final String ELYTRA_ANCHOR = "playerpose_elytra_anchor";
    private static final String LEFT_WING = "left_wing";
    private static final String RIGHT_WING = "right_wing";

    @Shadow private ModelPart leftWing;
    @Shadow private ModelPart rightWing;

    private ModelPart playerpose$bodyAnchor;
    private ModelPart playerpose$elytraAnchor;

    @Inject(method = "<init>(Lnet/minecraft/client/model/geom/ModelPart;)V", at = @At("TAIL"))
    private void playerpose$anchorWings(ModelPart root, CallbackInfo ci) {
        if (root == null) return;
        Map<String, ModelPart> children = ((ModelPartChildrenAccessor) (Object) root).playerpose$children();
        ModelPart left = children.remove(LEFT_WING);
        ModelPart right = children.remove(RIGHT_WING);
        if (left == null || right == null) return;

        Map<String, ModelPart> wings = new HashMap<>();
        wings.put(LEFT_WING, left);
        wings.put(RIGHT_WING, right);
        ModelPart elytra = new ModelPart(List.of(), wings);

        Map<String, ModelPart> inner = new HashMap<>();
        inner.put(ELYTRA_ANCHOR, elytra);
        ModelPart body = new ModelPart(List.of(), inner);

        children.put(BODY_ANCHOR, body);
        playerpose$bodyAnchor = body;
        playerpose$elytraAnchor = elytra;
    }

    @Inject(
        method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
        at = @At("TAIL")
    )
    private void playerpose$applyBodyPose(HumanoidRenderState state, CallbackInfo ci) {
        ModelPart body = playerpose$bodyAnchor;
        ModelPart elytra = playerpose$elytraAnchor;
        if (body == null || elytra == null) return;
        body.setPos(0.0F, 0.0F, 0.0F);
        body.setRotation(0.0F, 0.0F, 0.0F);
        elytra.setPos(0.0F, 0.0F, 0.0F);
        elytra.setRotation(0.0F, 0.0F, 0.0F);

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(state instanceof PlayerRenderState avatar)) return;

        Pose pose = PlayerPoseClient.resolvePoseForEntity(mc, avatar.id);
        PoseBone bodyBone = findBone(pose, "body");
        if (bodyBone != null && !bodyBone.isRest()) {
            body.setPos((float) bodyBone.tx, (float) bodyBone.ty, (float) bodyBone.tz);
            body.setRotation((float) bodyBone.x, (float) bodyBone.y, (float) bodyBone.z);
        }
        PoseBone elytraBone = findBone(pose, "cape");
        if (elytraBone != null && !elytraBone.isRest()) {
            elytra.setPos((float) elytraBone.tx, (float) elytraBone.ty, (float) elytraBone.tz);
            elytra.setRotation((float) elytraBone.x, (float) elytraBone.y, (float) elytraBone.z);
        }

        // Opening angles: sweep both wings outward around each axis. The three values live
        // in one pseudo-bone so they save with the pose and stay undoable.
        PoseBone spread = findBone(pose, "elytraSpread");
        if (spread != null && !spread.isRest() && leftWing != null && rightWing != null) {
            float sx = (float) spread.x;
            float sy = (float) spread.y;
            float sz = (float) spread.z;
            leftWing.xRot += sx;
            leftWing.yRot += sy;
            leftWing.zRot -= sz;
            rightWing.xRot -= sx;
            rightWing.yRot -= sy;
            rightWing.zRot += sz;
        }

        // The gizmo needs a pivot even while the wings hang off the torso centre.
        if (avatar.id == mc.player.getId()) {
            PlayerPoseClient.publishJointPivot("cape", body.x, body.y, body.z);
        }
    }

    private static PoseBone findBone(Pose pose, String name) {
        if (pose == null || pose.getBones() == null) return null;
        for (PoseBone bone : pose.getBones()) {
            if (bone != null && name.equals(bone.bone)) return bone;
        }
        return null;
    }
}
