package com.example.playerpose.pose;

/**
 * A single bone pose.
 *
 * x/y/z   rotation in radians (fed straight into ModelPart#setRotation).
 * tx/ty/tz translation in model units, 16 units = 1 block (ModelPart's own space,
 *          so these are added to the part's pivot).
 */
public class PoseBone {
    public final String bone;
    public final double x, y, z;
    public final double tx, ty, tz;

    public PoseBone(String bone, double x, double y, double z) {
        this(bone, x, y, z, 0.0, 0.0, 0.0);
    }

    public PoseBone(String bone, double x, double y, double z,
                    double tx, double ty, double tz) {
        this.bone = bone;
        this.x = x;
        this.y = y;
        this.z = z;
        this.tx = tx;
        this.ty = ty;
        this.tz = tz;
    }

    /** True when the bone is on its rest pose and needs nothing applied. */
    public boolean isRest() {
        return x == 0.0 && y == 0.0 && z == 0.0 && tx == 0.0 && ty == 0.0 && tz == 0.0;
    }
}
