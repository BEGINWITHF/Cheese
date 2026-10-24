package com.example.playerpose.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Known things that can replace or animate the player rig after this mod has posed
 * it. None of these can be fixed by the editor itself, so the editor shows a COMPAT
 * badge and lists them in a tooltip instead of silently ignoring the drag.
 */
public final class PoseCompat {

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

        // Vanilla layered models are not driven by the pose either.
        if (mc.player != null) {
            boolean armor = false;
            boolean elytra = false;
            for (EquipmentSlot slot : new EquipmentSlot[] {EquipmentSlot.HEAD,
                EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
                ItemStack stack = mc.player.getItemBySlot(slot);
                if (stack.isEmpty()) continue;
                if (slot == EquipmentSlot.CHEST && stack.is(Items.ELYTRA)) {
                    elytra = true;
                } else {
                    armor = true;
                }
            }
            if (elytra) out.add(Component.translatable("playerpose.compat.elytra"));
            if (armor) out.add(Component.translatable("playerpose.compat.armor"));
        }
        return out;
    }

    private static boolean mod(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }

    private static boolean hasResource(Minecraft mc, String path) {
        return mc.getResourceManager()
            .getResource(Identifier.fromNamespaceAndPath("minecraft", path))
            .isPresent();
    }
}
