package com.example.playerpose.mixin;

import com.example.playerpose.PlayerPoseClient;
import com.example.playerpose.pose.BoneMath;
import com.example.playerpose.pose.LayerBends;
import com.example.playerpose.pose.Pose;
import com.example.playerpose.pose.PoseBone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin for HumanoidModel (MC 26.2).
 *
 * Bone fields are public final ModelPart fields (no getters):
 *   head, hat, body, rightArm, leftArm, rightLeg, leftLeg
 *
 * setupAnim(T) erases to setupAnim(HumanoidRenderState), which is what we hook.
 * At TAIL the vanilla walk/idle animation has already been applied, so the saved
 * pose overwrites the bones it defines and leaves the rest alone.
 *
 * setupAnim runs for every humanoid model in the world, so the pose is only
 * applied to the model whose render state belongs to the local player
 * (AvatarRenderState#id is the entity id).
 *
 * Applied per bone: rotation, then a translation made of two parts -
 *   - the pose's own translate offset for that bone, and
 *   - the compensation that turns "rotate about the joint" into
 *     "rotate about the cursor", i.e. offset = cursor - R * cursor.
 */
@Mixin(HumanoidModel.class)
public abstract class PoseModelMixin {

    /**
     * Rest pivots of the humanoid parts, in model units (16 per block, +Y down).
     * Taken from HumanoidModel.createMesh: every part is a direct child of the
     * root, so ModelPart.x/y/z is the model-space joint.
     */
    private static final float[][] REST = {
        {0.0F, 0.0F, 0.0F},   // body
        {0.0F, 0.0F, 0.0F},   // head
        {5.0F, 2.0F, 0.0F},   // leftArm
        {-5.0F, 2.0F, 0.0F},  // rightArm
        {1.9F, 12.0F, 0.0F},  // leftLeg
        {-1.9F, 12.0F, 0.0F}, // rightLeg
        // Second segments: their position is relative to the limb they hang from, so the
        // rest offset is the limb's geometric seam - +4 for arms (cube spans -2..10),
        // +6 for legs (cube spans 0..12). See PoseLimbSplitMixin.
        {0.0F, 4.0F, 0.0F},   // leftForearm
        {0.0F, 4.0F, 0.0F},   // rightForearm
        {0.0F, 6.0F, 0.0F},   // leftShin
        {0.0F, 6.0F, 0.0F}    // rightShin
    };

    @Shadow public ModelPart head;
    @Shadow public ModelPart body;
    @Shadow public ModelPart leftArm;
    @Shadow public ModelPart rightArm;
    @Shadow public ModelPart leftLeg;
    @Shadow public ModelPart rightLeg;

    @Inject(
        method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
        at = @At("TAIL")
    )
    private void playerpose$applyPose(HumanoidRenderState state, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!(state instanceof AvatarRenderState avatar) || avatar.id != mc.player.getId()) return;

        // Published before anything is applied, so the editor reads the animated
        // rest pivots rather than positions this mixin moved.
        publishPivots();
        // Rebuilt every frame: a mesh with no bend registered stays untouched.
        LayerBends.clear();

        Pose pose = PlayerPoseClient.resolvePoseForLocalPlayer(mc);
        if (pose == null || pose.getBones() == null || pose.getBones().isEmpty()) return;

        for (PoseBone bone : pose.getBones()) {
            if (bone == null) continue;
            int index = restIndex(bone.bone);
            ModelPart part = findPart(bone.bone);
            if (part == null || index < 0) continue;
            if (bone.isRest() && !pose.hasCursor()) continue;

            float rx = (float) bone.x;
            float ry = (float) bone.y;
            float rz = (float) bone.z;
            part.setRotation(rx, ry, rz);

            // Rotation about the cursor instead of the joint (see BoneMath).
            double[] off = BoneMath.cursorOffset(pose, bone);
            double dx = off[0] + bone.tx;
            double dy = off[1] + bone.ty;
            double dz = off[2] + bone.tz;

            if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 1.0E-4) {
                float[] rest = REST[index];
                part.setPos((float) (rest[0] + dx), (float) (rest[1] + dy), (float) (rest[2] + dz));
            }

            // Second layers drawn by other mods (3D Skin Layers) attach to this limb, not
            // to the child part, so they need the bend handed over separately.
            registerLayerBend(bone.bone, rx, ry, rz, dx, dy, dz);
        }
    }

    private static int restIndex(String boneName) {
        if (boneName == null) return -1;
        return switch (boneName) {
            case "body" -> 0;
            case "head" -> 1;
            case "leftArm" -> 2;
            case "rightArm" -> 3;
            case "leftLeg" -> 4;
            case "rightLeg" -> 5;
            case "leftForearm" -> 6;
            case "rightForearm" -> 7;
            case "leftShin" -> 8;
            case "rightShin" -> 9;
            default -> -1;
        };
    }

    private ModelPart findPart(String boneName) {
        if (boneName == null) return null;
        return switch (boneName) {
            case "body" -> body;
            case "head" -> head;
            case "leftArm" -> leftArm;
            case "rightArm" -> rightArm;
            case "leftLeg" -> leftLeg;
            case "rightLeg" -> rightLeg;
            // Second segments, added to the mesh by PoseLimbSplitMixin.
            case "leftForearm" -> child(leftArm, "left_forearm");
            case "rightForearm" -> child(rightArm, "right_forearm");
            case "leftShin" -> child(leftLeg, "left_shin");
            case "rightShin" -> child(rightLeg, "right_shin");
            default -> null;
        };
    }

    private static ModelPart child(ModelPart parent, String name) {
        if (parent == null || !parent.hasChild(name)) return null;
        return parent.getChild(name);
    }

    /**
     * Hand over the bend for a second segment, so mods that draw their own second layer
     * (3D Skin Layers) can bend it too. They attach their mesh to the limb or to its
     * sleeve/pants overlay, so both parts get registered.
     */
    private void registerLayerBend(String bone, float rx, float ry, float rz,
                                   double dx, double dy, double dz) {
        String parentName = switch (bone) {
            case "leftForearm" -> "leftArm";
            case "rightForearm" -> "rightArm";
            case "leftShin" -> "leftLeg";
            case "rightShin" -> "rightLeg";
            default -> null;
        };
        if (parentName == null) return;
        ModelPart parent = findPart(parentName);
        if (parent == null) return;

        int index = restIndex(bone);
        double seam = index < 0 ? 0.0 : REST[index][1];
        LayerBends.Bend bend = new LayerBends.Bend(seam, rx, ry, rz, dx, dy, dz);

        LayerBends.put(parent, bend);
        String overlay = switch (bone) {
            case "leftForearm" -> "left_sleeve";
            case "rightForearm" -> "right_sleeve";
            case "leftShin" -> "left_pants";
            case "rightShin" -> "right_pants";
            default -> null;
        };
        if (overlay != null && parent.hasChild(overlay)) {
            LayerBends.put(parent.getChild(overlay), bend);
        }
    }

    /**
     * Hand the model's joint pivots to the editor. ModelPart stores these in model
     * units (16 per block, +Y down) relative to the model root, which is exactly the
     * space the editor's gizmo projection works in.
     */
    private void publishPivots() {
        PlayerPoseClient.publishJointPivot("head", head.x, head.y, head.z);
        PlayerPoseClient.publishJointPivot("body", body.x, body.y, body.z);
        PlayerPoseClient.publishJointPivot("leftArm", leftArm.x, leftArm.y, leftArm.z);
        PlayerPoseClient.publishJointPivot("rightArm", rightArm.x, rightArm.y, rightArm.z);
        PlayerPoseClient.publishJointPivot("leftLeg", leftLeg.x, leftLeg.y, leftLeg.z);
        PlayerPoseClient.publishJointPivot("rightLeg", rightLeg.x, rightLeg.y, rightLeg.z);
        // Second segments: their pivot is expressed in the limb's own space, so compose
        // the limb's pivot with its current rotation applied to the child offset.
        publishChildPivot("leftForearm", leftArm, "left_forearm");
        publishChildPivot("rightForearm", rightArm, "right_forearm");
        publishChildPivot("leftShin", leftLeg, "left_shin");
        publishChildPivot("rightShin", rightLeg, "right_shin");
    }

    /** Model-space pivot of a child part, for the editor's gizmo anchor. */
    private static void publishChildPivot(String bone, ModelPart parent, String childName) {
        ModelPart part = child(parent, childName);
        if (part == null) return;
        double[] off = BoneMath.rotateZYX(part.x, part.y, part.z, parent.xRot, parent.yRot, parent.zRot);
        PlayerPoseClient.publishJointPivot(bone,
            (float) (parent.x + off[0]), (float) (parent.y + off[1]), (float) (parent.z + off[2]));
    }
}
