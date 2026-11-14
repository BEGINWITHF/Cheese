package com.example.playerpose.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the first-person hand skin-coloured.
 *
 * <p>The limb splitter keeps the sleeve part under its arm so it follows the pose.
 * {@code PlayerRenderer#renderHand} draws the arm (including that sleeve child) with
 * {@code RenderType.entitySolid}, which turns every transparent sleeve pixel black.
 * Detach the sliced sleeve while the arm is drawn, then put it back afterwards.</p>
 */
@Mixin(PlayerRenderer.class)
public abstract class PoseHandMixin {

    private static ModelPart playerpose$detachedSleeve;
    private static String playerpose$detachedName;

    @Inject(
        method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;"
            + "ILnet/minecraft/client/player/AbstractClientPlayer;"
            + "Lnet/minecraft/client/model/geom/ModelPart;"
            + "Lnet/minecraft/client/model/geom/ModelPart;)V",
        at = @At("HEAD")
    )
    private void playerpose$detachSleeve(PoseStack poseStack, MultiBufferSource buffer, int light,
                                         AbstractClientPlayer player, ModelPart arm, ModelPart sleeve,
                                         CallbackInfo ci) {
        playerpose$detachedSleeve = null;
        playerpose$detachedName = null;
        if (arm == null) return;
        var children = ((ModelPartChildrenAccessor) (Object) arm).playerpose$children();
        ModelPart removed = children.remove("right_sleeve");
        String name = "right_sleeve";
        if (removed == null) {
            removed = children.remove("left_sleeve");
            name = "left_sleeve";
        }
        playerpose$detachedSleeve = removed;
        playerpose$detachedName = name;
    }

    @Inject(
        method = "renderHand(Lcom/mojang/blaze3d/vertex/PoseStack;"
            + "Lnet/minecraft/client/renderer/MultiBufferSource;"
            + "ILnet/minecraft/client/player/AbstractClientPlayer;"
            + "Lnet/minecraft/client/model/geom/ModelPart;"
            + "Lnet/minecraft/client/model/geom/ModelPart;)V",
        at = @At("RETURN")
    )
    private void playerpose$reattachSleeve(PoseStack poseStack, MultiBufferSource buffer, int light,
                                           AbstractClientPlayer player, ModelPart arm, ModelPart sleeve,
                                           CallbackInfo ci) {
        if (arm != null && playerpose$detachedSleeve != null && playerpose$detachedName != null) {
            ((ModelPartChildrenAccessor) (Object) arm).playerpose$children()
                .put(playerpose$detachedName, playerpose$detachedSleeve);
        }
        playerpose$detachedSleeve = null;
        playerpose$detachedName = null;
    }
}
