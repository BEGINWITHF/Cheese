package com.example.playerpose.mixin;

import com.example.playerpose.PlayerPoseClient;
import com.example.playerpose.pose.BoneMath;
import com.example.playerpose.pose.JointBend;
import com.example.playerpose.pose.LayerBends;
import com.example.playerpose.pose.Pose;
import com.example.playerpose.pose.PoseBone;
import com.example.playerpose.pose.PoseLimbs;
import com.example.playerpose.pose.PoseLog;
import com.example.playerpose.pose.PoseModelAccess;
import com.example.playerpose.skin.EyelidManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidArmorModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PlayerPoseGeometry;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * Mixin for HumanoidModel (MC 1.21.11).
 *
 * <p>PoseLimbSplitMixin turns each limb into twelve one-unit slices under an empty
 * limb pivot, plus an empty forearm/shin marker at the elbow/knee centre. This mixin
 * applies the pose to the body/head/limb pivots as before, then bends the slice
 * vertices with a smooth weight around the marker. The result is a continuous,
 * Blender-style bend instead of two rigid boxes that separate at the joint.</p>
 *
 * <p>The first-layer and sleeve/pants slice meshes are both deformed; the marker is
 * also what the editor gizmo anchors to and what the 3D Skin Layers compat hook uses
 * for its bend point.</p>
 */
@Mixin(HumanoidModel.class)
public abstract class PoseModelMixin implements PoseModelAccess {

    /**
     * Rest pivots of the humanoid parts, in model units (16 per block, +Y down).
     * Used only as a fallback if a baked part has no initial pose.
     */
    private static final float[][] REST = {
        {0.0F, 0.0F, 0.0F},   // body
        {0.0F, 0.0F, 0.0F},   // head
        {5.0F, 2.0F, 0.0F},   // leftArm
        {-5.0F, 2.0F, 0.0F},  // rightArm
        {1.9F, 12.0F, 0.0F},  // leftLeg
        {-1.9F, 12.0F, 0.0F}, // rightLeg
        {1.0F, 4.0F, 0.0F},   // leftForearm marker (elbow centre)
        {-1.0F, 4.0F, 0.0F},  // rightForearm marker
        {0.0F, 6.0F, 0.0F},   // leftShin marker (knee centre)
        {0.0F, 6.0F, 0.0F}    // rightShin marker
    };

    /** Diagnostic: log each leg pivot once if an animation mod moved it. */
    private static final java.util.Set<String> LOGGED_LEG_POS = new java.util.HashSet<>();

    @Final @Shadow public ModelPart head;
    @Final @Shadow public ModelPart body;
    @Final @Shadow public ModelPart leftArm;
    @Final @Shadow public ModelPart rightArm;
    @Final @Shadow public ModelPart leftLeg;
    @Final @Shadow public ModelPart rightLeg;

    @Inject(
        method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
        at = @At("TAIL")
    )
    private void playerpose$applyPose(LivingEntity entity, float limbSwing,
                                      float limbSwingAmount, float ageInTicks,
                                      float netHeadYaw, float headPitch,
                                      CallbackInfo ci) {
        playerpose$applyPoseInternal(entity);
    }

    @Override
    public void playerpose$applyPoseAgain(LivingEntity entity) {
        playerpose$applyPoseInternal(entity);
    }

    private void playerpose$applyPoseInternal(LivingEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || entity == null) return;
        boolean local = entity.getId() == mc.player.getId();

        // The armor layers are HumanoidArmorModels in 1.21.8; the cape is a
        // HumanoidModel too. Only the split skin model owns the joint pivots, the
        // eyelid quads and the per-frame layer-bend table; armor and the cape still
        // receive the pose and the armor's sliced limbs are bent, but they must not
        // wipe what the body model registered or publish pivots of their own.
        boolean playerModel = (Object) this instanceof PlayerModel
            || (Object) this instanceof HumanoidArmorModel;
        boolean bodyModel = playerModel && child(head, "playerpose_left_eyelid") != null;

        if (bodyModel && local) LayerBends.clear();
        if (playerModel) applyEyelids(local && PlayerPoseClient.isPoseEditorOpen());

        Pose pose = PlayerPoseClient.resolvePoseForEntity(mc, entity.getId());
        if (pose == null || pose.getBones() == null || pose.getBones().isEmpty()) {
            if (playerModel) {
                resetDeform();
                if (bodyModel && local) publishPivots();
            }
            return;
        }

        for (PoseBone bone : pose.getBones()) {
            if (bone == null) continue;
            int index = restIndex(bone.bone);
            ModelPart part = findPart(bone.bone);
            if (part == null || index < 0) continue;

            // Animation mods can move the leg pivot in Z after setupAnim. That puts
            // the hip axis on the front/back edge of the 4-deep leg instead of its
            // centre, so the leg appears hinged at its front face. Legs are ours to
            // drive: always restore their rest pivot (plus the pose translation).
            boolean legPart = "leftLeg".equals(bone.bone) || "rightLeg".equals(bone.bone)
                || "leftShin".equals(bone.bone) || "rightShin".equals(bone.bone);
            double dx = bone.tx;
            double dy = bone.ty;
            double dz = bone.tz;
            if (legPart) {
                float[] rest = restFor(part, index);
                if (LOGGED_LEG_POS.add(bone.bone)
                    && (Math.abs(part.x - rest[0]) + Math.abs(part.y - rest[1])
                        + Math.abs(part.z - rest[2]) > 1.0E-3)) {
                    PoseLog.info("leg pivot corrected: %s (%.2f,%.2f,%.2f) -> (%.2f,%.2f,%.2f)",
                        bone.bone, part.x, part.y, part.z, rest[0], rest[1], rest[2]);
                }
                part.setPos(rest[0] + (float) dx, rest[1] + (float) dy, rest[2] + (float) dz);
                if (bone.isRest()) {
                    part.setRotation(0.0F, 0.0F, 0.0F);
                    continue;
                }
            } else if (bone.isRest()) {
                continue;
            }

            float rx = (float) bone.x;
            float ry = (float) bone.y;
            float rz = (float) bone.z;
            part.setRotation(rx, ry, rz);

            if (!legPart && Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 1.0E-4) {
                float[] rest = restFor(part, index);
                part.setPos(rest[0] + (float) dx, rest[1] + (float) dy, rest[2] + (float) dz);
            }

            if (bodyModel && local) registerLayerBend(bone.bone, rx, ry, rz, dx, dy, dz);
        }

        if (playerModel) {
            deformLimbs(pose);
            // The sleeve/pants overlays are root parts. Vanilla setupAnim copied the
            // rest limb transforms before this pose ran, so refresh them now.
            if ((Object) this instanceof PlayerModel pm) {
                pm.leftSleeve.copyFrom(leftArm);
                pm.rightSleeve.copyFrom(rightArm);
                pm.leftPants.copyFrom(leftLeg);
                pm.rightPants.copyFrom(rightLeg);
            }
            // Pivots feed the editor's gizmo. Remote players render with the same
            // shared model classes, and the armor model is a PlayerModel too, so only
            // the split skin model of the local player may publish them.
            if (bodyModel && local) publishPivots();
        }
    }

    // ---- smooth limb skinning -------------------------------------------

    private void deformLimbs(Pose pose) {
        deformLimb(pose, "leftForearm", null, leftArm, "left_arm_slice", "left_sleeve_slice",
            "left_forearm", "left_sleeve", PoseLimbs.SLICES);
        deformLimb(pose, "rightForearm", null, rightArm, "right_arm_slice", "right_sleeve_slice",
            "right_forearm", "right_sleeve", PoseLimbs.SLICES);
        deformLimb(pose, "leftShin", "leftLeg", leftLeg, "left_leg_slice", "left_pants_slice",
            "left_shin", "left_pants", PoseLimbs.SLICES);
        deformLimb(pose, "rightShin", "rightLeg", rightLeg, "right_leg_slice", "right_pants_slice",
            "right_shin", "right_pants", PoseLimbs.SLICES);
    }

    private static void deformLimb(Pose pose, String boneName, String hipBoneName, ModelPart limb,
                                   String firstBase, String secondBase,
                                   String jointName, String overlayName, int slices) {
        if (limb == null) return;
        PoseBone bone = findBone(pose, boneName);
        PoseBone hipBone = hipBoneName == null ? null : findBone(pose, hipBoneName);
        ModelPart overlay = child(limb, overlayName);

        boolean sliced = child(limb, firstBase + "0") != null;
        if (!sliced) {
            // EMF / OptiFine custom player models keep the whole limb as one piece
            // and have no forearm/shin marker. Bend the limb's own cubes around its
            // midpoint instead, so the second segment still moves with these packs.
            if (bone != null && !bone.isRest()) {
                deformWholeLimb(limb, overlay, boneName, bone);
            }
            return;
        }

        ModelPart joint = child(limb, jointName);
        if (joint == null) return;
        var jointPose = joint.getInitialPose();
        if (jointPose == null) return;
        float centerX = jointPose.x;
        float seam = jointPose.y;

        float rx = 0.0F, ry = 0.0F, rz = 0.0F;
        float tx = 0.0F, ty = 0.0F, tz = 0.0F;
        if (bone != null && !bone.isRest()) {
            rx = (float) bone.x;
            ry = (float) bone.y;
            rz = (float) bone.z;
            tx = (float) bone.tx;
            ty = (float) bone.ty;
            tz = (float) bone.tz;
        }
        JointBend hipBend = null;
        if (hipBone != null && !hipBone.isRest()) {
            hipBend = JointBend.of(hipBone.x, hipBone.y, hipBone.z,
                PoseLimbs.HIP_BEND_HALF);
        }
        // The forearm/shin bends around its own joint with the same curved-centreline
        // solver the hip uses, so the elbow/knee cannot pinch the back edge either.
        JointBend jointBend = null;
        if (bone != null && !bone.isRest()) {
            jointBend = JointBend.ofSymmetric(bone.x, bone.y, bone.z,
                PoseLimbs.JOINT_BEND_HALF * 2.0);
        }
        for (int i = 0; i < slices; i++) {
            ModelPart first = child(limb, firstBase + i);
            if (first != null) {
                PlayerPoseGeometry.deformSlice(first, centerX, seam, rx, ry, rz, tx, ty, tz,
                    hipBend, jointBend);
            }
            ModelPart second = child(overlay, secondBase + i);
            if (second != null) {
                PlayerPoseGeometry.deformSlice(second, centerX, seam, rx, ry, rz, tx, ty, tz,
                    hipBend, jointBend);
            }
        }
    }

    /** Bend a whole-limb (custom model) part around the middle of its geometry. */
    private static void deformWholeLimb(ModelPart limb, ModelPart overlay,
                                        String boneName, PoseBone bone) {
        float[] limbBounds = PlayerPoseGeometry.limbBounds(limb);
        float[] overlayBounds = overlay == null ? null : PlayerPoseGeometry.limbBounds(overlay);
        float[] bounds = limbBounds != null ? limbBounds : overlayBounds;
        if (bounds == null) return;

        float centerX = (bounds[0] + bounds[1]) / 2.0F;
        float midY = (bounds[2] + bounds[3]) / 2.0F;
        float rx = (float) bone.x;
        float ry = (float) bone.y;
        float rz = (float) bone.z;
        float tx = (float) bone.tx;
        float ty = (float) bone.ty;
        float tz = (float) bone.tz;

        if (limbBounds != null) {
            deformWholePart(limb, centerX, rx, ry, rz, tx, ty, tz);
        }
        if (overlayBounds != null) {
            deformWholePart(overlay, centerX, rx, ry, rz, tx, ty, tz);
        }
        PlayerPoseClient.publishJointPivot(boneName,
            limb.x + centerX, limb.y + midY, limb.z);
    }

    private static void deformWholePart(ModelPart part, float centerX,
                                        float rx, float ry, float rz,
                                        float tx, float ty, float tz) {
        float[] bounds = PlayerPoseGeometry.limbBounds(part);
        if (bounds == null) return;
        var partPose = part.getInitialPose();
        float yOffset = partPose == null ? 0.0F : partPose.y;
        float seam = (bounds[2] + bounds[3]) / 2.0F + yOffset;
        JointBend jointBend = JointBend.ofSymmetric(rx, ry, rz,
            PoseLimbs.JOINT_BEND_HALF * 2.0);
        PlayerPoseGeometry.deformSlice(part, centerX, seam, rx, ry, rz,
            tx, ty, tz, null, jointBend);
    }

    private static void resetDeform() {
        PlayerPoseGeometry.resetDeform();
    }

    private static PoseBone findBone(Pose pose, String name) {
        if (pose == null || pose.getBones() == null) return null;
        for (PoseBone bone : pose.getBones()) {
            if (bone != null && name.equals(bone.bone)) return bone;
        }
        return null;
    }

    // ---- existing pose/part helpers -------------------------------------

    /**
     * Rest offset of a part: its own baked initial pose (the marker centre for the
     * forearm/shin, the limb pivot otherwise). Per-model, so slim and classic skins do
     * not share a hinge table.
     */
    private static float[] restFor(ModelPart part, int index) {
        if (part != null) {
            var rest = part.getInitialPose();
            if (rest != null) return new float[] {rest.x, rest.y, rest.z};
        }
        return REST[index < 0 ? 0 : index];
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

    /** Position the two eyelid quads on the face, editor-only. */
    private void applyEyelids(boolean editor) {
        if (head == null) return;
        ModelPart right = child(head, "playerpose_right_eyelid");
        ModelPart left = child(head, "playerpose_left_eyelid");
        boolean on = editor && EyelidManager.hasBase();
        applyEyelid(right, on && EyelidManager.rightSupported()
                ? EyelidManager.rightEyeBox() : null,
            1.0F - EyelidManager.rightOpen());
        applyEyelid(left, on && EyelidManager.leftSupported()
                ? EyelidManager.leftEyeBox() : null,
            1.0F - EyelidManager.leftOpen());
    }

    private static void applyEyelid(ModelPart part, EyelidManager.EyeBox box, float closed) {
        if (part == null) return;
        if (box == null || closed <= 0.01F) {
            part.visible = false;
            return;
        }
        part.visible = true;
        part.x = -4.0F + box.x0();
        part.y = -8.0F + box.y0();
        part.z = -4.02F;
        part.xScale = box.width();
        part.yScale = box.height() * closed;
        part.zScale = 1.0F;
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
        ModelPart segment = findPart(bone);
        if (parent == null || segment == null) return;

        float[] joint = restFor(segment, restIndex(bone));
        LayerBends.Bend bend = new LayerBends.Bend(joint[0], joint[1], joint[2],
            rx, ry, rz, dx, dy, dz);

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
        publishChildPivot("leftForearm", leftArm, "left_forearm");
        publishChildPivot("rightForearm", rightArm, "right_forearm");
        publishChildPivot("leftShin", leftLeg, "left_shin");
        publishChildPivot("rightShin", rightLeg, "right_shin");
    }

    /** Model-space pivot of a child part, for the editor's gizmo anchor. */
    private static void publishChildPivot(String bone, ModelPart parent, String childName) {
        ModelPart part = child(parent, childName);
        if (part == null) return;

        if (bone.endsWith("Shin")) {
            // The shin marker is bent by the hip centreline, not by the parent's
            // rigid rotation. Publish the curved position so the gizmo sits on the
            // knee the user can actually see.
            JointBend hip = JointBend.of(parent.xRot, parent.yRot, parent.zRot,
                PoseLimbs.HIP_BEND_HALF);
            double[] k = hip.apply(part.x, part.y, part.z);
            PlayerPoseClient.publishJointPivot(bone,
                (float) (parent.x + k[0]), (float) (parent.y + k[1]),
                (float) (parent.z + k[2]));
            return;
        }

        double[] off = BoneMath.rotateZYX(part.x, part.y, part.z,
            parent.xRot, parent.yRot, parent.zRot);
        PlayerPoseClient.publishJointPivot(bone,
            (float) (parent.x + off[0]), (float) (parent.y + off[1]), (float) (parent.z + off[2]));
    }
}
