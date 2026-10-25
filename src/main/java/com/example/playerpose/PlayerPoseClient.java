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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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

    /**
     * Poses loaded onto other players on this client. The server never sees these;
     * each renderer resolves the pose by entity id. A null entry means the player
     * shows their normal pose.
     */
    private static final Map<UUID, Pose> remotePoses = new ConcurrentHashMap<>();

    /** Explicit "no local pose", even if the local skin still has one bound. */
    private static boolean localPoseCleared;

    /** Clear remote targets when the client changes world/server. */
    private static Object lastLevel;

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
     * Why the player may not open the editor, or null when opening is allowed.
     * The editor pauses the world, so it only opens while the player is standing
     * completely still and upright on solid ground. Water, lava, ladders,
     * vehicles (boats, minecarts, ...), elytra, swimming, sneaking, crawling and
     * sleeping are all rejected even when not moving.
     */
    public static String localPlayerStillBlockReason(Minecraft mc) {
        if (mc == null || mc.player == null) {
            return "playerpose.overlay.standStill";
        }
        var player = mc.player;
        double dx = player.getX() - player.xOld;
        double dy = player.getY() - player.yOld;
        double dz = player.getZ() - player.zOld;
        if (dx * dx + dy * dy + dz * dz > 1.0E-6) {
            PoseLog.info("still: moving dx=%.5f dy=%.5f dz=%.5f", dx, dy, dz);
            return "playerpose.overlay.standStill";
        }
        boolean standing = player.getPose() == net.minecraft.world.entity.Pose.STANDING
            && player.onGround()
            && !player.isInWater()
            && !player.isInLava()
            && !player.onClimbable()
            && !player.isPassenger()
            && !player.isFallFlying()
            && !player.isSwimming()
            && !player.isCrouching()
            && !player.isVisuallyCrawling()
            && !player.isSleeping();
        if (!standing) {
            PoseLog.info("still: not standing (pose=%s ground=%s water=%s lava=%s climb=%s "
                    + "passenger=%s elytra=%s swim=%s sneak=%s crawl=%s sleep=%s)",
                player.getPose(), player.onGround(), player.isInWater(), player.isInLava(),
                player.onClimbable(), player.isPassenger(), player.isFallFlying(),
                player.isSwimming(), player.isCrouching(), player.isVisuallyCrawling(),
                player.isSleeping());
            return "playerpose.overlay.standOnGround";
        }
        return null;
    }

    public static boolean isLocalPlayerStill(Minecraft mc) {
        return localPlayerStillBlockReason(mc) == null;
    }

    public static void openPoseEditor() {
        if (poseEditorOpen) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            LOGGER.warn("Cannot open editor: no player");
            return;
        }
        String blocked = localPlayerStillBlockReason(mc);
        if (blocked != null) {
            mc.player.sendOverlayMessage(Component.translatable(blocked));
            PoseLog.info("editor open blocked: %s", blocked);
            return;
        }
        String skin = getLocalPlayerSkinString(mc);
        PoseManager mgr = getPoseManager();

        // Open on the clean home view: no pose is selected automatically. The player
        // presses New, or picks a saved pose from the list. This keeps opening the
        // panel from silently editing/renaming whatever was last active.
        PoseLog.dumpPoses(mgr, "editor open");
        mc.gui.setScreen(new PoseEditorScreen(mgr, null, skin));
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
        if (localPoseCleared) {
            logApplied(null, "local target unchecked");
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

    /**
     * Resolve the pose for any player render state. Local players keep the editor
     * preview / active-pose behaviour; other players use the explicitly loaded
     * target map, then the "all players" pose.
     */
    public static Pose resolvePoseForEntity(Minecraft mc, int entityId) {
        clearRemotePosesOnWorldChange();
        if (mc == null || mc.player == null || mc.level == null) return null;

        Entity entity = mc.level.getEntity(entityId);
        if (!(entity instanceof Player player)) return null;
        if (mc.player != null && player.getUUID().equals(mc.player.getUUID())) {
            return resolvePoseForLocalPlayer(mc);
        }

        if (!posesEnabled) return null;
        return remotePoses.get(player.getUUID());
    }

    /** Load a pose onto one connected player (client-side only). */
    public static void applyRemotePose(UUID playerId, Pose pose) {
        if (playerId == null || pose == null) return;
        clearRemotePosesOnWorldChange();
        remotePoses.put(playerId, PoseManager.copy(pose));
    }

    public static void clearRemotePose(UUID playerId) {
        if (playerId != null) remotePoses.remove(playerId);
    }

    public static boolean hasRemotePose(UUID playerId, String poseName) {
        if (playerId == null || poseName == null) return false;
        Pose p = remotePoses.get(playerId);
        return p != null && poseName.equals(p.getName());
    }

    /** Name of the pose a remote player currently carries, or null. */
    public static String getRemotePoseName(UUID playerId) {
        if (playerId == null) return null;
        Pose p = remotePoses.get(playerId);
        return p == null ? null : p.getName();
    }

    /** Name of the pose any player (including self) currently carries, or null. */
    public static String getPoseName(UUID playerId) {
        if (playerId == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && playerId.equals(mc.player.getUUID())) {
            return localPoseCleared ? null : activePoseName;
        }
        return getRemotePoseName(playerId);
    }

    /** Clear all remote targets (world change / disconnect). */
    public static void clearRemotePoses() {
        remotePoses.clear();
    }

    /** Unload the local pose and every client-side remote pose. */
    public static void unloadAllPoses() {
        clearLocalPose();
        remotePoses.clear();
        posesEnabled = false;
    }

    private static void clearRemotePosesOnWorldChange() {
        Minecraft mc = Minecraft.getInstance();
        Object level = mc == null ? null : mc.level;
        if (level != lastLevel) {
            lastLevel = level;
            clearRemotePoses();
        }
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

    public static void setActivePoseName(String name) {
        activePoseName = name;
        if (name != null) localPoseCleared = false;
    }

    public static boolean hasLocalPose(String poseName) {
        return !localPoseCleared && poseName != null && poseName.equals(activePoseName);
    }

    public static boolean isLocalPoseCleared() {
        return localPoseCleared;
    }

    public static void applyLocalPose(String poseName) {
        activePoseName = poseName;
        localPoseCleared = false;
    }

    public static void clearLocalPose() {
        activePoseName = null;
        localPoseCleared = true;
    }

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
