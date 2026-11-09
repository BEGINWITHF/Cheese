package com.example.playerpose.mixin;

import com.example.playerpose.skin.PreviewSkinManager;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the pose editor draw the local player with its preview skin.
 *
 * <p>1.20.6 has no per-render-state skin slot: the player renderer, the cape layer and
 * the elytra layer all ask {@link AbstractClientPlayer#getSkin()}. During the editor's
 * immediate GUI draw a thread-local preview is installed, so exactly that draw uses
 * it; the real player and everything sent to the server stay untouched.</p>
 */
@Mixin(AbstractClientPlayer.class)
public abstract class PoseSkinOverrideMixin {

    @Inject(method = "getSkin", at = @At("HEAD"), cancellable = true)
    private void playerpose$previewSkin(CallbackInfoReturnable<PlayerSkin> cir) {
        PlayerSkin preview = PreviewSkinManager.renderSkin();
        if (preview != null) {
            cir.setReturnValue(preview);
        }
    }
}
