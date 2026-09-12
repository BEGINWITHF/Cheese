package com.example.playerpose.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Blender-style rotate gizmo.
 *
 * Draws three axis rings (X red, Y green, Z blue) around a joint, an outer white
 * ring that rotates about whichever axis faces the camera, plus the usual axis
 * arms, handles and centre puck. Orientation is a simple orthographic camera
 * defined by a yaw/pitch pair so the rings look like they sit in 3D space.
 *
 * Interaction is drag-based: pressing on a ring records the angle of the cursor
 * around the gizmo centre, and dragging sweeps that angle; the swept delta is the
 * rotation applied to the bone.
 */
final class PoseGizmo {

    static final int NONE = -1;
    static final int AXIS_X = 0;
    static final int AXIS_Y = 1;
    static final int AXIS_Z = 2;

    private static final int SAMPLES = 72;
    private static final double HIT_PX = 9.0;

    /**
     * Vertical centre of the player model in model units. The model spans roughly
     * y = -8 (top of the head cube) to y = 24 (feet), so its centre is y = 8, and
     * the GUI entity renderer centres that on the viewport rectangle.
     */
    private static final double MODEL_CENTER_UNITS = 8.0;

    // Blender's axis colours (X red, Y green, Z blue)
    private static final int[] BRIGHT = {0xFFFF5555, 0xFF6FD44F, 0xFF4F86FF};
    private static final int[] DIM = {0x55FF5555, 0x556FD44F, 0x554F86FF};
    private static final int CROSSHAIR = 0xFF101010;

    private final GuiGraphicsExtractor gfx;
    private final double centerX, centerY, radius;
    /** Entity body rotation the renderer applies, i.e. radians(180 + camera yaw). */
    private final double viewYaw;
    private final double pitch; // radians
    private final int hoverAxis, activeAxis;

    PoseGizmo(GuiGraphicsExtractor gfx, double centerX, double centerY, double radius,
              double viewYawRad, double pitchRad, int hoverAxis, int activeAxis) {
        this.gfx = gfx;
        this.centerX = centerX;
        this.centerY = centerY;
        this.radius = Math.max(12.0, radius);
        this.viewYaw = viewYawRad;
        this.pitch = pitchRad;
        this.hoverAxis = hoverAxis;
        this.activeAxis = activeAxis;
    }

    // ---- projection ------------------------------------------------------

    /**
     * Rz(pi) * Rx(pitch) * Ry(viewYaw) applied to a model-space vector. This is the
     * transform the GUI entity renderer uses, so both the rings and the joint
     * anchors stay lined up with the rendered model.
     */
    private static double[] rotateChain(double x, double y, double z, double viewYaw, double pitch) {
        // Ry(viewYaw) - the entity body rotation
        double cy = Math.cos(viewYaw), sy = Math.sin(viewYaw);
        double x1 = x * cy + z * sy;
        double z1 = -x * sy + z * cy;

        // Rx(pitch)
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double y2 = y * cp - z1 * sp;
        double z2 = y * sp + z1 * cp;

        // Rz(pi) - flips both screen axes
        return new double[] {-x1, -y2, z2};
    }

    /** Orthographic projection of a model-space point: {screenX, screenY, depth}. */
    private double[] project(double x, double y, double z) {
        double[] r = rotateChain(x, y, z, viewYaw, pitch);
        return new double[] {centerX + r[0] * radius, centerY - r[1] * radius, r[2]};
    }

    /**
     * Screen position of a bone joint.
     *
     * @param pivotX,pivotY,pivotZ ModelPart pivot in model units (16 per block, +Y down)
     * @param pxPerBlock           the same size value handed to GuiGraphicsExtractor#entity
     * @return {screenX, screenY}
     */
    static double[] projectPivot(double rectCenterX, double rectCenterY, double pxPerBlock,
                                 double viewYaw, double pitch,
                                 float pivotX, float pivotY, float pivotZ) {
        double per = pxPerBlock / 16.0;
        double[] r = rotateChain(
            pivotX * per,
            (pivotY - MODEL_CENTER_UNITS) * per,
            pivotZ * per,
            viewYaw, pitch);
        return new double[] {rectCenterX + r[0], rectCenterY - r[1]};
    }

    private double[] ringPoint(int axis, double t) {
        double c = Math.cos(t), s = Math.sin(t);
        return switch (axis) {
            case AXIS_X -> project(0.0, c, s);
            case AXIS_Y -> project(c, 0.0, s);
            default -> project(c, s, 0.0);
        };
    }

    // ---- hit testing -----------------------------------------------------

    int hitTest(double mouseX, double mouseY) {
        int best = NONE;
        double bestDist = HIT_PX;
        for (int a = 0; a < 3; a++) {
            for (int i = 0; i < SAMPLES; i++) {
                double[] p = ringPoint(a, 2.0 * Math.PI * i / SAMPLES);
                double dd = Math.hypot(mouseX - p[0], mouseY - p[1]);
                if (dd < bestDist) {
                    bestDist = dd;
                    best = a;
                }
            }
        }
        return best;
    }

    static double angleAt(double mouseX, double mouseY, double centerX, double centerY) {
        return Math.atan2(mouseY - centerY, mouseX - centerX);
    }

    double centerX() { return centerX; }

    double centerY() { return centerY; }

    /**
     * Screen-space unit direction of a world axis, or null when that axis points
     * at the camera and cannot be dragged meaningfully.
     */
    double[] axisScreenDir(int axis) {
        double[] p = switch (axis) {
            case AXIS_X -> project(1, 0, 0);
            case AXIS_Y -> project(0, 1, 0);
            default -> project(0, 0, 1);
        };
        double dx = p[0] - centerX;
        double dy = p[1] - centerY;
        double n = Math.hypot(dx, dy);
        if (n < 0.15 * radius) return null;
        return new double[] {dx / n, dy / n};
    }

    /** Normalise an angle delta into (-pi, pi]. */
    static double wrapAngle(double a) {
        while (a > Math.PI) a -= 2.0 * Math.PI;
        while (a <= -Math.PI) a += 2.0 * Math.PI;
        return a;
    }

    // ---- drawing ---------------------------------------------------------

    void draw() {
        drawCenter();
        drawAxes();
        for (int a = 0; a < 3; a++) drawRing(a);
    }

    private void drawRing(int axis) {
        boolean hot = axis == hoverAxis || axis == activeAxis;
        int thickness = hot ? 2 : 1;

        double[] prev = null;
        for (int i = 0; i <= SAMPLES; i++) {
            double[] p = ringPoint(axis, 2.0 * Math.PI * i / SAMPLES);
            if (prev != null) {
                boolean front = p[2] >= 0.0;
                int color = hot || front ? BRIGHT[axis] : DIM[axis];
                stroke(prev[0], prev[1], p[0], p[1], thickness, color);
            }
            prev = p;
        }
    }

    private void drawAxes() {
        double len = radius * 1.6;
        for (int a = 0; a < 3; a++) {
            double[] unit = switch (a) {
                case AXIS_X -> project(1, 0, 0);
                case AXIS_Y -> project(0, 1, 0);
                default -> project(0, 0, 1);
            };
            double dx = unit[0] - centerX;
            double dy = unit[1] - centerY;
            double n = Math.hypot(dx, dy);
            if (n < 1.0E-4) continue;
            double tipX = centerX + dx / n * len;
            double tipY = centerY + dy / n * len;
            stroke(centerX, centerY, tipX, tipY, 1, BRIGHT[a]);
        }
    }

    private void drawCenter() {
        double r = radius * 0.42;
        stroke(centerX - r * 0.95, centerY, centerX + r * 0.95, centerY, 1, CROSSHAIR);
        stroke(centerX, centerY - r * 0.95, centerX, centerY + r * 0.95, 1, CROSSHAIR);
    }

    // ---- primitives ------------------------------------------------------

    /** GUI has no line primitive, so walk the segment dropping small quads. */
    private void stroke(double x1, double y1, double x2, double y2, int thickness, int color) {
        int X1 = (int) Math.round(x1), Y1 = (int) Math.round(y1);
        int X2 = (int) Math.round(x2), Y2 = (int) Math.round(y2);
        int half = Math.max(0, thickness / 2);
        int dx = X2 - X1, dy = Y2 - Y1;
        int dist = (int) Math.round(Math.sqrt((double) dx * dx + (double) dy * dy));

        if (dist == 0) {
            gfx.fill(X1 - half, Y1 - half, X1 + half + 1, Y1 + half + 1, color);
            return;
        }
        int step = Math.max(1, half);
        for (int i = 0; i <= dist; i += step) {
            int px = X1 + dx * i / dist;
            int py = Y1 + dy * i / dist;
            gfx.fill(px - half, py - half, px + half + 1, py + half + 1, color);
        }
    }
}
