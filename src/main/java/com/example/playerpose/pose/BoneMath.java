package com.example.playerpose.pose;

/**
 * Shared rotation maths for the pose mixins.
 *
 * MC applies ModelPart rotations as rotationZYX(zRot, yRot, xRot), i.e. Rz * Ry * Rx,
 * and this helper matches that order exactly so the posed result lines up.
 */
public final class BoneMath {

    private BoneMath() {}

    /** Apply Rz(rz) * Ry(ry) * Rx(rx) to a vector. */
    public static double[] rotateZYX(double x, double y, double z,
                                     double rx, double ry, double rz) {
        double x1, y1, z1;

        double c = Math.cos(rx), s = Math.sin(rx);
        y1 = y * c - z * s;
        z1 = y * s + z * c;
        y = y1;
        z = z1;

        c = Math.cos(ry);
        s = Math.sin(ry);
        x1 = x * c + z * s;
        z1 = -x * s + z * c;
        x = x1;
        z = z1;

        c = Math.cos(rz);
        s = Math.sin(rz);
        x1 = x * c - y * s;
        y1 = x * s + y * c;

        return new double[] {x1, y1, z};
    }

    /**
     * Offset that turns "rotate about the joint" into "rotate about the cursor".
     * P + R*(J - P) - J simplifies to cursor - R*cursor, and is exactly zero when the
     * cursor sits on the joint, so un-rotated bones are left untouched.
     */
    public static double[] cursorOffset(Pose pose, PoseBone bone) {
        double cx = pose.getCursorX();
        double cy = pose.getCursorY();
        double cz = pose.getCursorZ();
        if (!pose.hasCursor()) return new double[] {0.0, 0.0, 0.0};
        double[] r = rotateZYX(cx, cy, cz, bone.x, bone.y, bone.z);
        return new double[] {cx - r[0], cy - r[1], cz - r[2]};
    }
}
