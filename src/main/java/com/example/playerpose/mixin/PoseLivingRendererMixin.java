package com.example.playerpose.mixin;

import com.example.playerpose.pose.PoseModelAccess;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
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
        if (model instanceof HumanoidModel<?> humanoid && state instanceof HumanoidRenderState humanoidState) {
            ((PoseModelAccess) (Object) humanoid).playerpose$applyPoseAgain(humanoidState);
        }
    }
}
