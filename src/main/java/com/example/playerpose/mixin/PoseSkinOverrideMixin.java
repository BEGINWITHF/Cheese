package com.example.playerpose.mixin;

import com.example.playerpose.compat.PlayerSkin;
import com.example.playerpose.skin.PreviewSkinManager;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the pose editor draw the local player with its preview skin.
 *
 * <p>1.20.1 has no aggregate skin record: the player renderer, the cape layer and
 * the elytra layer ask the player for the texture/model pieces separately. During
 * the editor's immediate GUI draw a thread-local preview is installed, so exactly
 * that draw uses it; the real player and everything sent to the server stay
 * untouched.</p>
 */
@Mixin(AbstractClientPlayer.class)
public abstract class PoseSkinOverrideMixin {

    @Inject(method = "getSkinTextureLocation", at = @At("HEAD"), cancellable = true)
    private void playerpose$previewBody(CallbackInfoReturnable<ResourceLocation> cir) {
        PlayerSkin preview = PreviewSkinManager.renderSkin();
        if (preview != null && preview.texture() != null) {
            cir.setReturnValue(preview.texture());
        }
    }

    @Inject(method = "getCloakTextureLocation", at = @At("HEAD"), cancellable = true)
    private void playerpose$previewCape(CallbackInfoReturnable<ResourceLocation> cir) {
        PlayerSkin preview = PreviewSkinManager.renderSkin();
        if (preview != null) {
            cir.setReturnValue(preview.capeTexture());
        }
    }

    @Inject(method = "getElytraTextureLocation", at = @At("HEAD"), cancellable = true)
    private void playerpose$previewElytra(CallbackInfoReturnable<ResourceLocation> cir) {
        PlayerSkin preview = PreviewSkinManager.renderSkin();
        if (preview != null) {
            cir.setReturnValue(preview.elytraTexture());
        }
    }

    @Inject(method = "getModelName", at = @At("HEAD"), cancellable = true)
    private void playerpose$previewModel(CallbackInfoReturnable<String> cir) {
        PlayerSkin preview = PreviewSkinManager.renderSkin();
        if (preview != null) {
            cir.setReturnValue(preview.model().id());
        }
    }
}
