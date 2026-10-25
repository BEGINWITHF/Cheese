package com.example.playerpose.mixin;

import com.example.playerpose.pose.PoseLog;
import com.example.playerpose.pose.PoseModelAccess;
import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the pose once more after LivingEntityRenderer#submit has run
 * EntityModel#setupAnim, and therefore after animation mods that inject at the HEAD
 * of submit (EMF / Fresh Animations: Player Extension).
 *
 * <p>Mixin priority alone cannot beat those mods: our normal injection sits at the
 * TAIL of HumanoidModel#setupAnim, while EMF writes its animation later, at the start
 * of submit. Injecting after the setupAnim call puts our pose back as the last writer
 * while still leaving the rest of the render pipeline untouched.</p>
 */
@Mixin(LivingEntityRenderer.class)
public abstract class PoseLivingRendererMixin {

    @Shadow protected EntityModel<?> model;

    private static final java.util.Set<String> LOGGED_RIGS = new java.util.HashSet<>();
    private static boolean loggedHook;

    @Inject(
        method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"
            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
            + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/model/EntityModel;setupAnim(Ljava/lang/Object;)V",
            shift = At.Shift.AFTER
        )
    )
    private void playerpose$applyAfterAnimationMods(LivingEntityRenderState state, PoseStack poseStack,
                                                    SubmitNodeCollector collector, CameraRenderState camera,
                                                    CallbackInfo ci) {
        // Only EMF needs this second pass; without it, keep the original one-pass
        // setupAnim path exactly as before.
        if (!FabricLoader.getInstance().isModLoaded("entity_model_features")) return;
        if (!loggedHook) {
            loggedHook = true;
            PoseLog.info("submit hook: model=%s humanoid=%s state=%s humanoidState=%s",
                model == null ? "null" : model.getClass().getName(),
                model instanceof HumanoidModel<?>,
                state == null ? "null" : state.getClass().getName(),
                state instanceof HumanoidRenderState);
        }
        if (model instanceof HumanoidModel<?> humanoid && state instanceof HumanoidRenderState humanoidState) {
            // Our own split player model was already posed in setupAnim. Running the
            // pass again would double-apply the pose and change the arm feel, so only
            // custom (EMF) rigs without the slice markers get the second pass.
            if (humanoid.leftArm != null && humanoid.leftArm.hasChild("left_arm_slice0")) return;
            String rig = model.getClass().getName();
            if (LOGGED_RIGS.add(rig)) {
                PoseLog.info("render rig: %s body=%d arm=%d leg=%d sleeve=%s pants=%s",
                    model.getClass().getSimpleName(),
                    cubeCount(humanoid.body), cubeCount(humanoid.leftArm), cubeCount(humanoid.leftLeg),
                    humanoid.leftArm.hasChild("left_sleeve"),
                    humanoid.leftLeg.hasChild("left_pants"));
            }
            ((PoseModelAccess) (Object) humanoid).playerpose$applyPoseAgain(humanoidState);
        }
    }

    private static int cubeCount(ModelPart part) {
        return part == null ? 0
            : ((ModelPartAccessor) (Object) part).playerpose$cubes().size();
    }
}
