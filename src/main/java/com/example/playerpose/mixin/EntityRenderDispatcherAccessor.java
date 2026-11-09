package com.example.playerpose.mixin;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * Lets the editor pick the player renderer that matches the render state's skin
 * model. The dispatcher keeps one renderer per arm model, but its lookup is
 * entity-based, so a slim preview would otherwise be drawn with a classic model.
 */
@Mixin(EntityRenderDispatcher.class)
public interface EntityRenderDispatcherAccessor {

    @Accessor("playerRenderers")
    Map<PlayerSkin.Model, EntityRenderer<? extends Player, ?>> playerpose$playerRenderers();
}
