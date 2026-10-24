package com.example.playerpose.pose;

import net.minecraft.client.model.geom.ModelPart;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Second-layer meshes that have to bend with a pose's elbow or knee.
 *
 * Other mods (3D Skin Layers) render the skin's second layer as their own mesh attached
 * to a limb or sleeve part, so the child part this mod adds at the elbow is invisible to
 * them. The pose mixin registers a bend per part here each frame, and the compatibility
 * hook in {@code compat/SkinLayersCompat} applies it to those meshes' vertices.
 *
 * Keyed by part identity, so it is cleared every frame - a mesh with no bend stays
 * untouched.
 */
public final class LayerBends {

    /**
     * Rotate everything below {@code seamY} about the point (0, seamY, 0) by (rx,ry,rz),
     * then shift by (ox,oy,oz). Units match ModelPart cubes: 16 per block, +Y down.
     */
    public record Bend(double px, double seamY, double pz,
                       double rx, double ry, double rz,
                       double ox, double oy, double oz) {

        /** True when this bend would not move anything. */
        public boolean isIdentity() {
            return rx == 0.0 && ry == 0.0 && rz == 0.0 && ox == 0.0 && oy == 0.0 && oz == 0.0;
        }

        /** Apply to a single point, in place. */
        public void apply(org.joml.Vector3f p) {
            double y = p.y - seamY;
            if (y <= 0.0) return; // above the joint: the limb itself moves it
            double[] r = BoneMath.rotateZYX(p.x - px, y, p.z - pz, rx, ry, rz);
            p.set((float) (r[0] + px + ox), (float) (r[1] + seamY + oy), (float) (r[2] + pz + oz));
        }

        /** Apply to a vertex (w is kept). */
        public void apply(org.joml.Vector4f v) {
            double y = v.y - seamY;
            if (y <= 0.0) return;
            double[] r = BoneMath.rotateZYX(v.x - px, y, v.z - pz, rx, ry, rz);
            v.set((float) (r[0] + px + ox), (float) (r[1] + seamY + oy), (float) (r[2] + pz + oz), v.w);
        }
    }

    private static final Map<ModelPart, Bend> BENDS = new IdentityHashMap<>();

    private LayerBends() {}

    public static synchronized void clear() {
        BENDS.clear();
    }

    public static synchronized void put(ModelPart part, Bend bend) {
        if (part != null && bend != null) BENDS.put(part, bend);
    }

    /** The bend for a part, or null. */
    public static synchronized Bend get(ModelPart part) {
        return part == null ? null : BENDS.get(part);
    }
}
