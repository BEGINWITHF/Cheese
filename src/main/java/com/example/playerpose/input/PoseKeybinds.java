package com.example.playerpose.input;

import com.example.playerpose.PlayerPoseClient;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mod's only key bind: open / close the pose panel.
 *
 * Everything else lives in the editor UI (Save, Load, Reset, Delete and the mode
 * buttons), so there is nothing else to bind.
 *
 * Default is Backspace (InputConstants.KEY_BACKSPACE); rebind in Options -> Controls
 * under the "Cheese" category.
 */
public final class PoseKeybinds {

    private static final Logger LOGGER = LoggerFactory.getLogger("playerpose.keybinds");

    /** 1.21.8 key bindings use a plain translation-key category. */
    private static final String CATEGORY = "key.category." + PlayerPoseClient.MOD_ID + ".main";

    private static KeyMapping editorBinding;

    private PoseKeybinds() {}

    public static void register() {
        editorBinding = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.playerpose.toggle", InputConstants.Type.KEYSYM,
            InputConstants.KEY_BACKSPACE, CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (editorBinding.consumeClick()) {
                if (PlayerPoseClient.isPoseEditorOpen()) {
                    PlayerPoseClient.closePoseEditor();
                } else {
                    PlayerPoseClient.openPoseEditor();
                }
            }
        });

        LOGGER.info("Registered the Open Panel key (default Backspace, rebindable in Controls)");
    }
}
