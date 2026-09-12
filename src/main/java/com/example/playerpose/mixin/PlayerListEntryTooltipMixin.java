package com.example.playerpose.mixin;

import com.example.playerpose.PlayerPoseClient;
import com.example.playerpose.pose.Pose;
import net.minecraft.client.Minecraft;

/**
 * Plain helper for "is the local player currently posed?" questions.
 *
 * This used to be a @Mixin on AbstractClientPlayer targeting an addTooltip method
 * that does not exist in MC 26.2 (and it was never listed in player-pose.mixins.json,
 * so it never loaded). It is kept as a normal class so callers still have the two
 * lookups; wire it to a real UI hook (tab-list / nametag tooltip) if that feature
 * is wanted later.
 */
public final class PlayerListEntryTooltipMixin {

    private PlayerListEntryTooltipMixin() {}

    /** True when the local player has a pose that will be applied on the next render. */
    public static boolean hasActivePose() {
        return PlayerPoseClient.shouldApplyPose();
    }

    /** The pose currently matched to the local player's skin, or null. */
    public static Pose getCurrentPose() {
        var mgr = PlayerPoseClient.getPoseManager();
        if (mgr == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        return mgr.findForSkin(PlayerPoseClient.getLocalPlayerSkinString(mc));
    }
}
