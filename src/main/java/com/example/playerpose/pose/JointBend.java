package com.example.playerpose.pose;

/**
 * Smooth bend for a limb joint (hip, elbow or knee).
 *
 * <p>Rotating every vertex of a thick limb around one joint pivot folds the back
 * edge: the edge is offset from the pivot, so the chord turns much faster than
 * the limb centre and the silhouette gets a notch. This deformer instead moves
 * the cross-sections along a curved centreline and turns each cross-section to
 * the local tangent. The cross-section at the joint is pinned to the parent
 * segment; the band ends tangent-continuous with the straight child segment. The
 * result is an offset curve, not a set of circles around the joint, so both the
 * front and back silhouettes stay smooth.</p>
 *
 * <p>The joint rotation is split into a minimal bend (rotating local +Y to the
 * posed child direction) and a twist about the child. The bend angle uses a C2
 * curve: value, slope and curvature are zero at the pinned joint and at the
 * straight child segment, with the curvature peak at 37.5% of the band.</p>
 */
public final class JointBend {

    private static final double STEP = 0.25;
    private static final int TABLE = 49;
    private static final double EPS = 1.0E-7;

    private final double rx;
    private final double ry;
    private final double rz;
    private final double half;
    private final boolean symmetric;
    private final double[] axis;
    private final double theta;
    private final double twist;
    private final double[] cx = new double[TABLE];
    private final double[] cy = new double[TABLE];
    private final double[] cz = new double[TABLE];

    private JointBend(double rx, double ry, double rz, double half, boolean symmetric) {
        this.rx = rx;
        this.ry = ry;
        this.rz = rz;
        this.half = half;
        this.symmetric = symmetric;

        double[] tip = BoneMath.rotateZYX(0.0, 1.0, 0.0, rx, ry, rz);
        double dot = Math.max(-1.0, Math.min(1.0, tip[1]));
        this.theta = Math.acos(dot);
        double sin = Math.sqrt(Math.max(0.0, 1.0 - dot * dot));
        if (sin < EPS) {
            // A pure twist (or no rotation): any perpendicular axis works.
            this.axis = new double[] {1.0, 0.0, 0.0};
        } else {
            this.axis = new double[] {tip[2] / sin, 0.0, -tip[0] / sin};
        }

        double[] xr = BoneMath.rotateZYX(1.0, 0.0, 0.0, rx, ry, rz);
        double[] xb = rotateAxis(xr[0], xr[1], xr[2], axis, -theta);
        this.twist = Math.atan2(-xb[2], xb[0]);

        buildCentreline();
    }

    public static JointBend of(double rx, double ry, double rz, double half) {
        return new JointBend(rx, ry, rz, half, false);
    }

    /**
     * Symmetric joint blend: the band straddles the joint, so both the parent end
     * and the child end share the bend. Used for elbows and knees where both sides
     * are visible.
     */
    public static JointBend ofSymmetric(double rx, double ry, double rz, double half) {
        return new JointBend(rx, ry, rz, half, true);
    }

    /**
     * Bend a limb-local point. The point is the already child-bent position, with
     * x/z measured from the limb centreline and y measured from the joint.
     */
    public double[] apply(double x, double y, double z) {
        // The second-layer top can sit slightly above the hip (negative y). It is
        // above the joint and must stay put, not snap down to the seam plane.
        if (y <= 0.0) return new double[] {x, y, z};
        double[] r = localRotate(y, x, 0.0, z);
        double[] c = centre(y);
        return new double[] {c[0] + r[0], c[1] + r[1], c[2] + r[2]};
    }

    /** Rotate a normal by the local cross-section orientation at height y. */
    public double[] bendNormal(double y, double nx, double ny, double nz) {
        return localRotate(y, nx, ny, nz);
    }

    /** Undo the parent limb part's full joint rotation, which is applied at render. */
    public double[] counterRotation(double x, double y, double z) {
        return BoneMath.rotateInverseZYX(x, y, z, rx, ry, rz);
    }

    private double[] localRotate(double y, double x, double vy, double z) {
        double w = weight(y);
        double phi = theta * w;
        double psi = twist * w;
        double[] t = BoneMath.rotateZYX(x, vy, z, 0.0, psi, 0.0);
        return rotateAxis(t[0], t[1], t[2], axis, phi);
    }

    private double weight(double y) {
        if (y <= 0.0) return 0.0;
        if (y >= half) return 1.0;
        double t = y / half;
        if (symmetric) {
            // Smootherstep: C2 at both ends, symmetric, steepest at the joint.
            return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
        }
        // Integral of 504*t^3*(1-t)^5: C2 at both ends, with the steepest part
        // (and therefore the tightest bend) at t = 0.375 of the band.
        return t * t * t * t * (126.0 - 504.0 * t + 840.0 * t * t
            - 720.0 * t * t * t + 315.0 * t * t * t * t
            - 56.0 * t * t * t * t * t);
    }

    /** Blend weight at a distance from the band origin. */
    public double weightAt(double y) {
        return weight(y);
    }

    private void buildCentreline() {
        cx[0] = 0.0;
        cy[0] = 0.0;
        cz[0] = 0.0;
        for (int i = 1; i < TABLE; i++) {
            double mid = (i - 0.5) * STEP;
            double phi = theta * weight(mid);
            double[] t = rotateAxis(0.0, 1.0, 0.0, axis, phi);
            cx[i] = cx[i - 1] + t[0] * STEP;
            cy[i] = cy[i - 1] + t[1] * STEP;
            cz[i] = cz[i - 1] + t[2] * STEP;
        }
    }

    private double[] centre(double y) {
        if (y <= 0.0) return new double[] {0.0, 0.0, 0.0};
        double f = y / STEP;
        int i = (int) f;
        if (i >= TABLE - 1) {
            // Second-layer pixels can extend 0.25 past the foot; keep them on the
            // straight tangent instead of pulling them back to the last sample.
            double[] t = rotateAxis(0.0, 1.0, 0.0, axis, theta);
            double d = y - (TABLE - 1) * STEP;
            return new double[] {
                cx[TABLE - 1] + t[0] * d,
                cy[TABLE - 1] + t[1] * d,
                cz[TABLE - 1] + t[2] * d
            };
        }
        double t = f - i;
        return new double[] {
            cx[i] + (cx[i + 1] - cx[i]) * t,
            cy[i] + (cy[i + 1] - cy[i]) * t,
            cz[i] + (cz[i + 1] - cz[i]) * t
        };
    }

    private static double[] rotateAxis(double x, double y, double z,
                                       double[] axis, double angle) {
        if (Math.abs(angle) < EPS) return new double[] {x, y, z};
        double c = Math.cos(angle);
        double s = Math.sin(angle);
        double ax = axis[0], ay = axis[1], az = axis[2];
        double dot = ax * x + ay * y + az * z;
        double crossX = ay * z - az * y;
        double crossY = az * x - ax * z;
        double crossZ = ax * y - ay * x;
        double k = dot * (1.0 - c);
        return new double[] {
            x * c + crossX * s + ax * k,
            y * c + crossY * s + ay * k,
            z * c + crossZ * s + az * k
        };
    }
}
