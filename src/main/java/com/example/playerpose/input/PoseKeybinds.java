package com.example.playerpose.input;

import com.example.playerpose.PlayerPoseClient;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mod's only key bind: open / close the pose editor.
 *
 * Everything else lives in the editor UI (Save, Load, Reset, Delete and the mode
 * buttons), so there is nothing else to bind.
 *
 * Default is "/" (GLFW_KEY_SLASH). Note this is the same default as the vanilla
 * "Open Command" binding (Options#keyCommand), so with both on "/" the chat opens
 * and this editor takes over the screen - rebind here in Options -> Controls,
 * category "Player Pose", if that gets in the way. P was avoided because it opens
 * the vanilla social interactions / player report screen.
 */
public final class PoseKeybinds {

    private static final Logger LOGGER = LoggerFactory.getLogger("playerpose.keybinds");

    private static final KeyMapping.Category CATEGORY =
        KeyMapping.Category.register(
            net.minecraft.resources.Identifier.fromNamespaceAndPath(PlayerPoseClient.MOD_ID, "main"));

    private static KeyMapping editorBinding;

    private PoseKeybinds() {}

    public static void register() {
        editorBinding = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.playerpose.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_SLASH, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (editorBinding.consumeClick()) {
                if (PlayerPoseClient.isPoseEditorOpen()) {
                    PlayerPoseClient.closePoseEditor();
                } else {
                    PlayerPoseClient.openPoseEditor();
                }
            }
        });

        LOGGER.info("Registered the pose editor key (default /, rebindable in Controls)");
    }
}
