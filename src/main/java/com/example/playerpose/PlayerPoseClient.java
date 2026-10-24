package com.example.playerpose;

import com.example.playerpose.input.PoseKeybinds;
import com.example.playerpose.pose.Pose;
import com.example.playerpose.pose.PoseManager;
import com.example.playerpose.compat.SkinLayersCompat;
import com.example.playerpose.pose.PoseLog;
import com.example.playerpose.screen.PoseEditorScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PlayerPoseClient implements ClientModInitializer {

    public static final String MOD_ID = "player-pose";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static PoseManager poseManager;
    private static boolean poseEditorOpen;

    /**
     * Model-space pivot of each bone, published by PoseModelMixin from the model
     * that is actually rendering the local player. Values are in model units
     * (16 units = 1 block, +Y pointing down), which is what ModelPart stores.
     * The editor uses them to sit the gizmo on the real joint instead of a guess.
     */
    private static final java.util.Map<String, float[]> jointPivots = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Pose the editor is currently working on. While the editor is open the local
     * player's model renders with this instead of the saved pose, so the viewport
     * previews edits before they are saved.
     */
    private static Pose editorPreviewPose;

    /**
     * When false, no pose is applied to the player at all - the character renders
     * with its normal vanilla pose. Pose files stay on disk and stay listed.
     */
    private static boolean posesEnabled = true;

    /**
     * The one pose currently in effect, by name. Kept explicitly so that closing the
     * editor keeps applying the pose you were editing instead of re-deriving one from
     * the pose map (which used to snap to an arbitrary entry).
     */
    private static String activePoseName;

    @Override
    public void onInitializeClient() {
        LOGGER.info("Player Pose mod initializing for MC 26.2...");

        poseManager = new PoseManager();
        PoseManager.setInstance(poseManager);
        poseManager.load();
        PoseLog.info("init: %d pose(s) available", poseManager.listNames().size());
        PoseLog.dumpPoses(poseManager, "startup");

        PoseKeybinds.register();

        // Optional: let 3D Skin Layers bend its own second-layer meshes with our
        // elbow/knee, since it draws them itself instead of using the vanilla parts.
        if (FabricLoader.getInstance().isModLoaded("skinlayers3d")) {
            SkinLayersCompat.install();
        } else {
            PoseLog.info("compat: 3D Skin Layers not present");
        }

        LOGGER.info("Player Pose mod initialized");
    }

    /**
     * Skin identity used to match a saved pose to a player.
     * MC 26.2 exposes the game profile through Entity#getGameProfile().
     */
    public static String getLocalPlayerSkinString(Minecraft minecraft) {
        var player = minecraft.player;
        if (player == null) return "";
        var profile = player.getGameProfile();
        // authlib 9.x made GameProfile a record: name() instead of getName().
        if (profile != null && profile.name() != null) {
            return profile.name();
        }
        return "";
    }

    /** The screen currently shown, or null when the player is in the world. */
    public static net.minecraft.client.gui.screens.Screen currentScreen(Minecraft minecraft) {
        return minecraft.gui.screen();
    }

    /**
     * The editor pauses the world, so opening it mid-step would freeze the player in
     * a walking pose and apply the pose to a model that is still animating. "Still"
     * means the coordinates did not change on the last tick and the player has
     * something under them: ground, water/lava, a ladder or a vehicle. Jumping,
     * falling and flying count as airborne and are rejected.
     */
    public static boolean isLocalPlayerStill(Minecraft mc) {
        if (mc == null || mc.player == null) return false;
        var player = mc.player;
        double dx = player.getX() - player.xOld;
        double dy = player.getY() - player.yOld;
        double dz = player.getZ() - player.zOld;
        if (dx * dx + dy * dy + dz * dz > 1.0E-6) {
            PoseLog.info("still: moving dx=%.5f dy=%.5f dz=%.5f", dx, dy, dz);
            return false;
        }
        boolean supported = player.onGround()
            || player.isInWater()
            || player.isInLava()
            || player.onClimbable()
            || player.isPassenger();
        if (!supported) {
            PoseLog.info("still: airborne (ground=%s water=%s lava=%s climb=%s passenger=%s)",
                player.onGround(), player.isInWater(), player.isInLava(),
                player.onClimbable(), player.isPassenger());
        }
        return supported;
    }

    public static void openPoseEditor() {
        if (poseEditorOpen) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            LOGGER.warn("Cannot open editor: no player");
            return;
        }
        if (!isLocalPlayerStill(mc)) {
            mc.player.sendOverlayMessage(Component.translatable("playerpose.overlay.standStill"));
            PoseLog.info("editor open blocked: player is still moving");
            return;
        }
        String skin = getLocalPlayerSkinString(mc);
        PoseManager mgr = getPoseManager();

        // Re-opening must show the pose you last loaded, not an arbitrary one: the
        // active pose wins, and the skin lookup is only the initial fallback.
        Pose existing = activePoseName == null ? null : mgr.get(activePoseName);
        if (existing != null) {
            PoseLog.info("editor open: skin='%s' keeping active pose '%s'", skin, activePoseName);
        } else {
            existing = mgr.findForSkin(skin);
            activePoseName = existing == null ? null : existing.getName();
            PoseLog.info("editor open: skin='%s' matched pose %s", skin,
                existing == null ? "none (starting empty)" : "'" + activePoseName + "'");
        }
        PoseLog.dumpPoses(mgr, "editor open");

        mc.gui.setScreen(new PoseEditorScreen(mgr, existing, skin));
        poseEditorOpen = true;
        LOGGER.info("Opened pose editor for skin: {}", skin);
    }

    public static void closePoseEditor() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui.screen() instanceof PoseEditorScreen) {
            // PoseEditorScreen#removed() clears the preview and the open flag.
            mc.gui.setScreen(null);
        }
        // Defensive, and also needed when the screen was replaced without removed()
        // (world unload or another mod taking over the screen): a stuck flag would stop
        // poses from applying and keep returning the stale editor preview.
        poseEditorOpen = false;
        editorPreviewPose = null;
        PoseLog.info("editor close (key): active=%s", activePoseName == null ? "none" : "'" + activePoseName + "'");
        announceAppliedPose();
    }

    public static boolean isPoseEditorOpen() { return poseEditorOpen; }

    public static boolean shouldApplyPose() {
        if (poseEditorOpen) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        Pose pose = resolvePoseForLocalPlayer(mc);
        return pose != null && pose.getBones() != null && !pose.getBones().isEmpty();
    }

    /**
     * Single lookup used by the model mixin: the editor's working pose while the
     * editor is open, otherwise the pose saved for the local player's skin.
     */
    public static Pose resolvePoseForLocalPlayer(Minecraft mc) {
        if (mc == null || mc.player == null) return null;
        // Editing always shows what you are working on, even when poses are unloaded.
        if (poseEditorOpen && editorPreviewPose != null) {
            return editorPreviewPose;
        }
        if (!posesEnabled) {
            logApplied(null, "poses unloaded");
            return null;
        }
        PoseManager mgr = getPoseManager();
        if (mgr == null) return null;

        // There is exactly one active pose. Keep applying it after the editor closes.
        if (activePoseName != null) {
            Pose active = mgr.get(activePoseName);
            if (active != null) {
                logApplied(active.getName(), "active pose");
                return active;
            }
            PoseLog.warn("apply: active pose '%s' is gone (renamed or deleted); "
                + "falling back to a skin lookup", activePoseName);
            activePoseName = null;
        }
        Pose bySkin = mgr.findForSkin(getLocalPlayerSkinString(mc));
        if (bySkin != null) {
            activePoseName = bySkin.getName();
            logApplied(activePoseName, "skin lookup");
        } else {
            logApplied(null, "nothing bound");
        }
        return bySkin;
    }

    /** Last pose name written to the log, so the per-frame apply path stays quiet. */
    private static String lastAppliedLogged;

    private static void logApplied(String name, String why) {
        String key = name == null ? "<none:" + why + ">" : name;
        if (key.equals(lastAppliedLogged)) return;
        lastAppliedLogged = key;
        PoseLog.info("apply: %s (%s)", name == null ? "no pose" : "'" + name + "'", why);
    }

    public static String getActivePoseName() { return activePoseName; }

    public static void setActivePoseName(String name) { activePoseName = name; }

    public static boolean arePosesEnabled() { return posesEnabled; }

    public static void setPosesEnabled(boolean enabled) { posesEnabled = enabled; }

    public static void setEditorPreviewPose(Pose pose) { editorPreviewPose = pose; }

    /**
     * Action-bar feedback for "is my pose actually on my character?".
     * Called when the editor closes: from then on the saved pose for this skin is
     * what the model mixin applies.
     */
    public static void announceAppliedPose() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (!posesEnabled) {
            mc.player.sendOverlayMessage(Component.translatable("playerpose.overlay.unloaded"));
            PoseLog.info("editor close: poses unloaded, character stays vanilla");
            return;
        }
        String skin = getLocalPlayerSkinString(mc);
        Pose pose = resolvePoseForLocalPlayer(mc);
        if (pose == null || pose.getBones() == null || pose.getBones().isEmpty()) {
            mc.player.sendOverlayMessage(
                Component.translatable("playerpose.overlay.none", skin.isBlank() ? "you" : skin));
            PoseLog.warn("editor close: nothing to apply for skin '%s'", skin);
        } else {
            String name = pose.getName() == null || pose.getName().isBlank() ? "unnamed" : pose.getName();
            mc.player.sendOverlayMessage(Component.translatable("playerpose.overlay.applied", name));
            PoseLog.info("editor close: applying '%s' (%d bones) for skin '%s'",
                name, pose.getBones().size(), skin);
        }
    }

    /** Called by the mixin for the model that renders the local player. */
    public static void publishJointPivot(String bone, float x, float y, float z) {
        jointPivots.put(bone, new float[] {x, y, z});
    }

    /** Model-space pivot of a bone, or null if the model has not rendered yet. */
    public static float[] getJointPivot(String bone) {
        return jointPivots.get(bone);
    }

    public static PoseManager getPoseManager() { return poseManager; }
    public static void setPoseEditorOpen(boolean open) { poseEditorOpen = open; }
}
