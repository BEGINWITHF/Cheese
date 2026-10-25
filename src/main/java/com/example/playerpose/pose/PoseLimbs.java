package com.example.playerpose.pose;

/** Shared constants for the sliced limbs; kept outside the mixins. */
public final class PoseLimbs {

    /** One-unit vertical slices; twelve covers the vanilla twelve-unit limb. */
    public static final int SLICES = 12;

    /** Half-width of the smooth bend band, in model units. */
    public static final float BEND_HALF = 2.0F;

    /**
     * Hip blend length. The torso is rigid, so the thigh absorbs the whole joint.
     * Four units puts the curvature peak 1.5 units below the hip; the rest of the
     * leg below the band stays straight.
     */
    public static final float HIP_BEND_HALF = 4.0F;

    public static final float SLICE_H = 1.0F;

    private PoseLimbs() {}
}
