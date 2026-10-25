package com.example.playerpose.mixin;

import com.example.playerpose.PlayerPoseClient;
import com.example.playerpose.pose.BoneMath;
import com.example.playerpose.pose.HipBend;
import com.example.playerpose.pose.LayerBends;
import com.example.playerpose.pose.Pose;
import com.example.playerpose.pose.PoseBone;
import com.example.playerpose.pose.PoseLimbs;
import com.example.playerpose.pose.PoseLog;
import com.example.playerpose.pose.PoseModelAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerCapeModel;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Mixin for HumanoidModel (MC 26.2).
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

    /** Original slice vertices, so every frame can deform from the rest shape. */
    private static final Map<ModelPart.Cube, ModelPart.Polygon[]> REST_POLYGONS =
        new WeakHashMap<>();

    /** Diagnostic: log each leg pivot once if an animation mod moved it. */
    private static final java.util.Set<String> LOGGED_LEG_POS = new java.util.HashSet<>();

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
        playerpose$applyPoseInternal(state);
    }

    @Override
    public void playerpose$applyPoseAgain(HumanoidRenderState state) {
        playerpose$applyPoseInternal(state);
    }

    private void playerpose$applyPoseInternal(HumanoidRenderState state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!(state instanceof AvatarRenderState avatar)) return;
        boolean local = avatar.id == mc.player.getId();

        // The cape and armor layers are HumanoidModels too (armor is even baked as a
        // PlayerModel). Only the split body model owns the joint pivots and the
        // per-frame layer-bend table; the others still receive the pose, but must not
        // wipe what the body model registered.
        boolean playerModel = (Object) this instanceof PlayerModel
            && !((Object) this instanceof PlayerCapeModel);
        boolean bodyModel = playerModel && child(leftArm, "left_forearm") != null;

        if (bodyModel && local) LayerBends.clear();

        Pose pose = PlayerPoseClient.resolvePoseForEntity(mc, avatar.id);
        if (pose == null || pose.getBones() == null || pose.getBones().isEmpty()) {
            if (playerModel) {
                resetDeform();
                if (local) publishPivots();
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
            // Pivots feed the editor's gizmo. Remote players render with the same
            // shared model classes, so only the local player may publish them.
            if (local) publishPivots();
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
        float centerX = jointPose.x();
        float seam = jointPose.y();

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
        HipBend hipBend = null;
        if (hipBone != null && !hipBone.isRest()) {
            hipBend = HipBend.of(hipBone.x, hipBone.y, hipBone.z);
        }
        for (int i = 0; i < slices; i++) {
            ModelPart first = child(limb, firstBase + i);
            if (first != null) {
                deformSlice(first, centerX, seam, rx, ry, rz, tx, ty, tz, hipBend);
            }
            ModelPart second = child(overlay, secondBase + i);
            if (second != null) {
                deformSlice(second, centerX, seam, rx, ry, rz, tx, ty, tz, hipBend);
            }
        }
    }

    /** Bend a whole-limb (custom model) part around the middle of its geometry. */
    private static void deformWholeLimb(ModelPart limb, ModelPart overlay,
                                        String boneName, PoseBone bone) {
        float[] limbBounds = limbBounds(limb);
        float[] overlayBounds = overlay == null ? null : limbBounds(overlay);
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
        float[] bounds = limbBounds(part);
        if (bounds == null) return;
        var partPose = part.getInitialPose();
        float yOffset = partPose == null ? 0.0F : partPose.y();
        float seam = (bounds[2] + bounds[3]) / 2.0F + yOffset;
        deformSlice(part, centerX, seam, rx, ry, rz, tx, ty, tz, null);
    }

    /** Local-space x/y bounds of all cubes in a part, from the rest polygons. */
    private static float[] limbBounds(ModelPart part) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        List<ModelPart.Cube> cubes = ((ModelPartAccessor) (Object) part).playerpose$cubes();
        for (ModelPart.Cube cube : cubes) {
            ModelPart.Polygon[] rest = REST_POLYGONS.computeIfAbsent(cube,
                c -> c.polygons.clone());
            for (ModelPart.Polygon polygon : rest) {
                for (ModelPart.Vertex vertex : polygon.vertices()) {
                    minX = Math.min(minX, vertex.x());
                    maxX = Math.max(maxX, vertex.x());
                    minY = Math.min(minY, vertex.y());
                    maxY = Math.max(maxY, vertex.y());
                }
            }
        }
        if (minX > maxX || minY > maxY) return null;
        return new float[] {minX, maxX, minY, maxY};
    }

    private static void deformSlice(ModelPart slice, float centerX, float seam,
                                    float rx, float ry, float rz,
                                    float tx, float ty, float tz, HipBend hipBend) {
        var slicePose = slice.getInitialPose();
        if (slicePose == null) return;
        float yOffset = slicePose.y();

        List<ModelPart.Cube> cubes = ((ModelPartAccessor) (Object) slice).playerpose$cubes();
        for (ModelPart.Cube cube : cubes) {
            ModelPart.Polygon[] rest = REST_POLYGONS.computeIfAbsent(cube,
                c -> c.polygons.clone());
            for (int p = 0; p < rest.length; p++) {
                ModelPart.Polygon polygon = rest[p];
                ModelPart.Vertex[] old = polygon.vertices();
                ModelPart.Vertex[] out = new ModelPart.Vertex[old.length];
                float faceWeight = 0.0F;
                float faceY = 0.0F;

                for (int v = 0; v < old.length; v++) {
                    ModelPart.Vertex vertex = old[v];
                    float y = yOffset + vertex.y();
                    float weight = bendWeight(y, seam);
                    faceWeight += weight;
                    faceY += y;

                    double[] r = BoneMath.rotateZYX(vertex.x() - centerX, y - seam,
                        vertex.z(), rx * weight, ry * weight, rz * weight);
                    float nx = (float) (r[0] + centerX + tx * weight);
                    float ny = (float) (r[1] + seam + ty * weight - yOffset);
                    float nz = (float) (r[2] + tz * weight);

                    // The hip is a curved offset, not a rotation around one pivot.
                    // The knee result is moved onto the bent centreline locally,
                    // then the parent leg part's full rotation is undone so it
                    // renders exactly the bent curve.
                    if (hipBend != null) {
                        double[] hp = hipBend.apply(nx, ny + yOffset, nz);
                        double[] hq = hipBend.counterRotation(hp[0], hp[1], hp[2]);
                        nx = (float) hq[0];
                        ny = (float) (hq[1] - yOffset);
                        nz = (float) hq[2];
                    }
                    out[v] = new ModelPart.Vertex(nx, ny, nz, vertex.u(), vertex.v());
                }

                faceWeight /= old.length;
                faceY /= old.length;
                double[] n = BoneMath.rotateZYX(polygon.normal().x(), polygon.normal().y(),
                    polygon.normal().z(), rx * faceWeight, ry * faceWeight, rz * faceWeight);
                if (hipBend != null) {
                    double[] hn = hipBend.bendNormal(faceY, n[0], n[1], n[2]);
                    n = hipBend.counterRotation(hn[0], hn[1], hn[2]);
                }
                cube.polygons[p] = new ModelPart.Polygon(out,
                    new Vector3f((float) n[0], (float) n[1], (float) n[2]));
            }
        }
    }

    private static float bendWeight(float y, float seam) {
        float t = (y - (seam - PoseLimbs.BEND_HALF))
            / (PoseLimbs.BEND_HALF * 2.0F);
        if (t <= 0.0F) return 0.0F;
        if (t >= 1.0F) return 1.0F;
        return t * t * (3.0F - 2.0F * t);
    }

    private static void resetDeform() {
        for (Map.Entry<ModelPart.Cube, ModelPart.Polygon[]> entry : REST_POLYGONS.entrySet()) {
            ModelPart.Cube cube = entry.getKey();
            ModelPart.Polygon[] rest = entry.getValue();
            for (int i = 0; i < rest.length; i++) cube.polygons[i] = rest[i];
        }
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
            if (rest != null) return new float[] {rest.x(), rest.y(), rest.z()};
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
            HipBend hip = HipBend.of(parent.xRot, parent.yRot, parent.zRot);
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
