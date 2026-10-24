package com.example.playerpose.pose;

import net.minecraft.client.renderer.entity.state.HumanoidRenderState;

/**
 * Lets the renderer mixin re-apply the pose after animation mods (EMF / Fresh
 * Animations) have written their own bone data during the render pass.
 */
public interface PoseModelAccess {

    void playerpose$applyPoseAgain(HumanoidRenderState state);
}