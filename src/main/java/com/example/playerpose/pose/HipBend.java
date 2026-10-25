package com.example.playerpose.pose;

/**
 * Smooth hip bend for the thigh.
 *
 * <p>Rotating every vertex of a thick leg around one hip pivot folds the back
 * edge: the edge is offset from the pivot, so the chord turns much faster than
 * the leg centre and the silhouette gets a notch. This deformer instead moves
 * the cross-sections along a curved centreline and turns each cross-section to
 * the local tangent. The top cross-section is pinned at the body; the band ends
 * tangent-continuous with the straight lower leg. The result is an offset curve,
 * not a set of circles around the hip, so both the front and back silhouettes
 * stay smooth.</p>
 *
 * <p>The hip rotation is split into a minimal bend (rotating local +Y to the
 * posed thigh direction) and a twist about the thigh. The bend angle uses a C2
 * curve: value, slope and curvature are zero at the pinned top and at the
 * straight lower leg, with the curvature peak at 37.5% of the band (1.5 units
 * from the hip with the current 4-unit band).</p>
 */
public final class HipBend {

    private static final double STEP = 0.25;
    private static final int TABLE = 49;
    private static final double EPS = 1.0E-7;

    private final double rx;
    private final double ry;
    private final double rz;
    private final double half;
    private final double[] axis;
    private final double theta;
    private final double twist;
    private final double[] cx = new double[TABLE];
    private final double[] cy = new double[TABLE];
    private final double[] cz = new double[TABLE];

    private HipBend(double rx, double ry, double rz) {
        this.rx = rx;
        this.ry = ry;
        this.rz = rz;
        this.half = PoseLimbs.HIP_BEND_HALF;

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

    public static HipBend of(double rx, double ry, double rz) {
        return new HipBend(rx, ry, rz);
    }

    /**
     * Bend a leg-local point. The point is the already knee-bent position, with
     * x/z measured from the leg centreline and y measured from the hip.
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

    /** Undo the parent leg part's full hip rotation, which is applied at render. */
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
        // Integral of 504*t^3*(1-t)^5: C2 at both ends, with the steepest part
        // (and therefore the tightest bend) at t = 0.375, i.e. 1.5 units from
        // the hip with the 4-unit band.
        return t * t * t * t * (126.0 - 504.0 * t + 840.0 * t * t
            - 720.0 * t * t * t + 315.0 * t * t * t * t
            - 56.0 * t * t * t * t * t);
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
