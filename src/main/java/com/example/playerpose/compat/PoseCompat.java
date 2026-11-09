package com.example.playerpose.compat;

import com.example.playerpose.pose.PoseLog;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Known things that can replace or animate the player rig after this mod has posed
 * it. None of these can be fixed by the editor itself, so the editor shows a COMPAT
 * badge and lists them in a tooltip instead of silently ignoring the drag.
 */
public final class PoseCompat {

    private static final String SODIUM_CONFIG = "sodium-mixins.properties";
    private static final String SODIUM_KEY = "mixin.features.render.entity";

    private PoseCompat() {}

    /** A short list of detected conflicts, ready for the editor's tooltip. */
    public static List<Component> warnings(Minecraft mc) {
        List<Component> out = new ArrayList<>();
        if (mc == null || mc.getResourceManager() == null) return out;

        boolean emf = mod("entity_model_features");
        boolean optifine = mod("optifine") || mod("optifabric");
        if (emf && hasResource(mc, "emf/cem/player.jem")) {
            out.add(Component.translatable("playerpose.compat.playerModel"));
        } else if (optifine && hasResource(mc, "optifine/cem/player.jem")) {
            out.add(Component.translatable("playerpose.compat.playerModel"));
        }
        if (mod("playeranimator")) {
            out.add(Component.translatable("playerpose.compat.playerAnimator"));
        }
        if (mod("notenoughanimations")) {
            out.add(Component.translatable("playerpose.compat.notEnoughAnimations"));
        }
        if (mod("emotecraft")) {
            out.add(Component.translatable("playerpose.compat.emotecraft"));
        }
        if (mod("figura")) {
            out.add(Component.translatable("playerpose.compat.figura"));
        }
        if (mod("physicsmod")) {
            out.add(Component.translatable("playerpose.compat.physicsMod"));
        }
        if (mod("capes") || mod("waveycapes")) {
            out.add(Component.translatable("playerpose.compat.capes"));
        }
        if (mod("skinlayers3d")) {
            out.add(Component.translatable("playerpose.compat.skinLayers"));
        }
        if (mod("sodium") && !sodiumEntityRendererDisabled()) {
            out.add(Component.translatable("playerpose.compat.sodiumEntity"));
        }
        return out;
    }

    /** Sodium 0.9+ is installed and its entity renderer still hides the limb bends. */
    public static boolean sodiumConflict() {
        return mod("sodium") && !sodiumEntityRendererDisabled();
    }

    /** The Sodium mixin config whose entity package has to be turned off. */
    public static Path sodiumConfig() {
        return FabricLoader.getInstance().getConfigDir().resolve(SODIUM_CONFIG);
    }

    /**
     * Disable Sodium's entity renderer mixin in its own config. The file is edited in
     * place so every other Sodium setting survives, and the change is picked up on the
     * next game start. Returns false when the file cannot be written.
     */
    public static boolean disableSodiumEntityMixin() {
        Path file = sodiumConfig();
        try {
            List<String> lines = Files.isRegularFile(file)
                ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>();
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                String text = lines.get(i).trim();
                if (text.isEmpty() || text.startsWith("#")) continue;
                int eq = text.indexOf('=');
                if (eq < 0) continue;
                if (text.substring(0, eq).trim().equals(SODIUM_KEY)) {
                    lines.set(i, SODIUM_KEY + "=false");
                    replaced = true;
                }
            }
            if (!replaced) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                    lines.add("");
                }
                lines.add("# Disabled by Player Pose so posed limbs keep their shape.");
                lines.add(SODIUM_KEY + "=false");
            }
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.write(file, lines);
            PoseLog.info("compat: set %s=false in %s", SODIUM_KEY, file);
            return true;
        } catch (Throwable error) {
            PoseLog.warn("compat: cannot update %s (%s)", file, error);
            return false;
        }
    }

    private static boolean mod(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }

    /**
     * Sodium 0.9+ bakes entity cubes and skips the vanilla polygon path, which hides
     * the per-vertex forearm/shin bends. Tell the player unless they already disabled
     * that mixin package.
     */
    private static boolean sodiumEntityRendererDisabled() {
        try {
            Class.forName("net.caffeinemc.mods.sodium.client.render.immediate.model.ModelCuboid",
                false, PoseCompat.class.getClassLoader());
        } catch (Throwable olderSodium) {
            return true;
        }
        try {
            Path file = FabricLoader.getInstance().getConfigDir()
                .resolve("sodium-mixins.properties");
            if (!Files.isRegularFile(file)) return false;
            for (String line : Files.readAllLines(file)) {
                String text = line.trim();
                if (text.isEmpty() || text.startsWith("#")) continue;
                int eq = text.indexOf('=');
                if (eq < 0) continue;
                if (text.substring(0, eq).trim().equals("mixin.features.render.entity")) {
                    return text.substring(eq + 1).trim().equalsIgnoreCase("false");
                }
            }
        } catch (Throwable ignored) {
            // If the config cannot be read, show the warning rather than hide it.
        }
        return false;
    }

    private static boolean hasResource(Minecraft mc, String path) {
        return mc.getResourceManager()
            .getResource(new ResourceLocation("minecraft", path))
            .isPresent();
    }
}
