package com.example.playerpose.compat;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;

/**
 * 1.20.1 stand-in for the {@code net.minecraft.client.resources.PlayerSkin} record
 * that only exists from 1.20.2 onwards.
 *
 * <p>In 1.20.1 the renderer asks the player for each piece separately
 * ({@link AbstractClientPlayer#getSkinTextureLocation()}, cloak, elytra and
 * {@link AbstractClientPlayer#getModelName()}). The editor code was written against
 * the 1.20.2 aggregate record, so this small value type keeps that shape and
 * {@link #of(AbstractClientPlayer)} reads the 1.20.1 getters.</p>
 */
public record PlayerSkin(ResourceLocation texture, String url,
                         ResourceLocation capeTexture, ResourceLocation elytraTexture,
                         Model model, boolean secure) {

    /** Arm width variants, mapping to the 1.20.1 model-name strings. */
    public enum Model {
        WIDE("default"),
        SLIM("slim");

        private final String id;

        Model(String id) {
            this.id = id;
        }

        /** The value returned by {@code AbstractClientPlayer#getModelName()}. */
        public String id() {
            return id;
        }

        public static Model byId(String id) {
            return "slim".equals(id) ? SLIM : WIDE;
        }
    }

    /** Snapshot of a player's real (not previewed) skin in 1.20.1 terms. */
    public static PlayerSkin of(AbstractClientPlayer player) {
        if (player == null) return null;
        return new PlayerSkin(player.getSkinTextureLocation(),
            null,
            player.getCloakTextureLocation(),
            player.getElytraTextureLocation(),
            Model.byId(player.getModelName()),
            false);
    }
}
