package com.example.playerpose.mixin;

import com.example.playerpose.skin.PreviewSkinManager;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.21.1 draws the player straight from the entity (no render-state copy), so the
 * editor's preview skin, cape and model are applied by overriding the local player's
 * skin lookup for the frame. 1.21.3 could simply write a render state field instead.
 */
@Mixin(AbstractClientPlayer.class)
public abstract class PreviewSkinMixin {

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void playerpose$previewSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerSkin preview = PreviewSkinManager.previewSkin((AbstractClientPlayer) (Object) this);
        if (preview != null) {
            cir.setReturnValue(preview);
        }
    }
}
