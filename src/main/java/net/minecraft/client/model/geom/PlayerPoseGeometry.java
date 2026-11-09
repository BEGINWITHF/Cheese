package net.minecraft.client.model.geom;

import com.example.playerpose.mixin.ModelPartAccessor;
import com.example.playerpose.pose.BoneMath;
import com.example.playerpose.pose.JointBend;
import com.example.playerpose.pose.PoseLimbs;
import net.minecraft.core.Direction;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 1.20.6 compatibility helper.
 *
 * <p>In this Minecraft version {@link ModelPart.Cube#polygons} is private and the
 * {@code Polygon}/{@code Vertex} nested types are package-private, while the 1.21.3
 * source could touch them directly. Keeping the vertex math in the same package gives
 * the rest of the mod a stable, typed entry point without reflection.</p>
 */
public final class PlayerPoseGeometry {

    /** Original slice vertices, so every frame can deform from the rest shape. */
    private static final Map<ModelPart.Cube, ModelPart.Polygon[]> REST_POLYGONS =
        new WeakHashMap<>();

    /** 1.20.6 keeps the polygon array private; find it by type so remapping is safe. */
    private static final java.lang.reflect.Field CUBE_POLYGONS;

    static {
        java.lang.reflect.Field found = null;
        for (java.lang.reflect.Field field : ModelPart.Cube.class.getDeclaredFields()) {
            if (field.getType() == ModelPart.Polygon[].class) {
                found = field;
                break;
            }
        }
        if (found == null) {
            throw new ExceptionInInitializerError("ModelPart.Cube has no polygon array");
        }
        found.setAccessible(true);
        CUBE_POLYGONS = found;
    }

    private PlayerPoseGeometry() {}

    private static ModelPart.Polygon[] polygons(ModelPart.Cube cube) {
        try {
            return (ModelPart.Polygon[]) CUBE_POLYGONS.get(cube);
        } catch (IllegalAccessException error) {
            throw new IllegalStateException("cannot read ModelPart.Cube polygons", error);
        }
    }

    public static void resetDeform() {
        for (Map.Entry<ModelPart.Cube, ModelPart.Polygon[]> entry : REST_POLYGONS.entrySet()) {
            ModelPart.Cube cube = entry.getKey();
            ModelPart.Polygon[] rest = entry.getValue();
            System.arraycopy(rest, 0, polygons(cube), 0, rest.length);
        }
    }

    /** Local-space x/y bounds of all cubes in a part, from the rest polygons. */
    public static float[] limbBounds(ModelPart part) {
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        List<ModelPart.Cube> cubes = cubes(part);
        for (ModelPart.Cube cube : cubes) {
            ModelPart.Polygon[] rest = REST_POLYGONS.computeIfAbsent(cube,
                c -> polygons(c).clone());
            for (ModelPart.Polygon polygon : rest) {
                for (ModelPart.Vertex vertex : polygon.vertices) {
                    minX = Math.min(minX, vertex.pos.x);
                    maxX = Math.max(maxX, vertex.pos.x);
                    minY = Math.min(minY, vertex.pos.y);
                    maxY = Math.max(maxY, vertex.pos.y);
                }
            }
        }
        if (minX > maxX || minY > maxY) return null;
        return new float[] {minX, maxX, minY, maxY};
    }

    public static void deformSlice(ModelPart slice, float centerX, float seam,
                                   float rx, float ry, float rz,
                                   float tx, float ty, float tz,
                                   JointBend hipBend, JointBend jointBend) {
        PartPose slicePose = slice.getInitialPose();
        if (slicePose == null) return;
        float yOffset = slicePose.y;

        List<ModelPart.Cube> cubes = cubes(slice);
        for (ModelPart.Cube cube : cubes) {
            ModelPart.Polygon[] rest = REST_POLYGONS.computeIfAbsent(cube,
                c -> polygons(c).clone());
            for (int p = 0; p < rest.length; p++) {
                ModelPart.Polygon polygon = rest[p];
                ModelPart.Vertex[] old = polygon.vertices;
                ModelPart.Vertex[] out = new ModelPart.Vertex[old.length];
                float faceWeight = 0.0F;
                float faceY = 0.0F;
                float jointStart = seam - PoseLimbs.JOINT_BEND_HALF;

                for (int v = 0; v < old.length; v++) {
                    ModelPart.Vertex vertex = old[v];
                    float y = yOffset + vertex.pos.y;
                    float weight = bendWeight(y, seam);
                    double jointWeight = jointBend == null ? 0.0
                        : jointBend.weightAt(y - jointStart);
                    faceWeight += weight;
                    faceY += y;

                    double[] r;
                    if (jointBend != null) {
                        r = jointBend.apply(vertex.pos.x - centerX,
                            y - jointStart, vertex.pos.z);
                    } else {
                        r = BoneMath.rotateZYX(vertex.pos.x - centerX, y - seam,
                            vertex.pos.z, rx * weight, ry * weight, rz * weight);
                    }
                    float origin = jointBend != null ? jointStart : seam;
                    float w = jointBend != null ? (float) jointWeight : weight;
                    float nx = (float) (r[0] + centerX + tx * w);
                    float ny = (float) (r[1] + origin + ty * w - yOffset);
                    float nz = (float) (r[2] + tz * w);

                    if (hipBend != null) {
                        double[] hp = hipBend.apply(nx, ny + yOffset, nz);
                        double[] hq = hipBend.counterRotation(hp[0], hp[1], hp[2]);
                        nx = (float) hq[0];
                        ny = (float) (hq[1] - yOffset);
                        nz = (float) hq[2];
                    }
                    out[v] = new ModelPart.Vertex(nx, ny, nz, vertex.u, vertex.v);
                }

                faceWeight /= old.length;
                faceY /= old.length;
                double[] n;
                Vector3f normal = polygon.normal;
                if (jointBend != null) {
                    n = jointBend.bendNormal(faceY - jointStart, normal.x(),
                        normal.y(), normal.z());
                } else {
                    n = BoneMath.rotateZYX(normal.x(), normal.y(), normal.z(),
                        rx * faceWeight, ry * faceWeight, rz * faceWeight);
                }
                if (hipBend != null) {
                    double[] hn = hipBend.bendNormal(faceY, n[0], n[1], n[2]);
                    n = hipBend.counterRotation(hn[0], hn[1], hn[2]);
                }
                polygons(cube)[p] = polygon(out,
                    (float) n[0], (float) n[1], (float) n[2]);
            }
        }
    }

    /** Make every face of the eyelid cube sample one skin pixel, for a flat lid. */
    public static void fixEyelid(ModelPart part, float u, float v) {
        if (part == null) return;
        for (ModelPart.Cube cube : cubes(part)) {
            for (int i = 0; i < polygons(cube).length; i++) {
                ModelPart.Polygon polygon = polygons(cube)[i];
                ModelPart.Vertex[] old = polygon.vertices;
                ModelPart.Vertex[] fixed = new ModelPart.Vertex[old.length];
                for (int j = 0; j < old.length; j++) {
                    fixed[j] = old[j].remap(u, v);
                }
                polygons(cube)[i] = polygon(fixed, polygon.normal);
            }
        }
    }

    /** Move every +Y (far end) face vertex back by dv rows of the skin texture. */
    public static void shiftEndFace(ModelPart part, float dv) {
        for (ModelPart.Cube cube : cubes(part)) {
            for (int i = 0; i < polygons(cube).length; i++) {
                ModelPart.Polygon polygon = polygons(cube)[i];
                if (polygon.normal.y() < 0.5F) continue;
                ModelPart.Vertex[] old = polygon.vertices;
                ModelPart.Vertex[] fixed = new ModelPart.Vertex[old.length];
                for (int j = 0; j < old.length; j++) {
                    fixed[j] = old[j].remap(old[j].u, old[j].v + dv);
                }
                polygons(cube)[i] = polygon(fixed, polygon.normal);
            }
        }
    }

    /** Collapse one horizontal cap to a point so its rim is not rendered. */
    public static void removeHorizontalFace(ModelPart part, float normalY) {
        for (ModelPart.Cube cube : cubes(part)) {
            for (int i = 0; i < polygons(cube).length; i++) {
                ModelPart.Polygon polygon = polygons(cube)[i];
                if (Math.abs(polygon.normal.y() - normalY) > 0.5F) continue;
                ModelPart.Vertex[] old = polygon.vertices;
                if (old.length == 0) continue;
                ModelPart.Vertex[] degenerate = new ModelPart.Vertex[old.length];
                java.util.Arrays.fill(degenerate, old[0]);
                polygons(cube)[i] = polygon(degenerate, polygon.normal);
            }
        }
    }

    /** Put vertices past the slice boundary onto the boundary plane. */
    public static void clampSeam(ModelPart part, float seam, boolean below) {
        for (ModelPart.Cube cube : cubes(part)) {
            for (int i = 0; i < polygons(cube).length; i++) {
                ModelPart.Polygon polygon = polygons(cube)[i];
                ModelPart.Vertex[] old = polygon.vertices;
                ModelPart.Vertex[] fixed = new ModelPart.Vertex[old.length];
                boolean changed = false;
                for (int j = 0; j < old.length; j++) {
                    ModelPart.Vertex v = old[j];
                    float y = v.pos.y;
                    if (below ? y < seam : y > seam) {
                        y = seam;
                        changed = true;
                    }
                    fixed[j] = y == v.pos.y ? v
                        : new ModelPart.Vertex(v.pos.x, y, v.pos.z, v.u, v.v);
                }
                if (changed) polygons(cube)[i] = polygon(fixed, polygon.normal);
            }
        }
    }

    private static List<ModelPart.Cube> cubes(ModelPart part) {
        return ((ModelPartAccessor) (Object) part).playerpose$cubes();
    }

    private static float bendWeight(float y, float seam) {
        float t = (y - (seam - PoseLimbs.BEND_HALF))
            / (PoseLimbs.BEND_HALF * 2.0F);
        if (t <= 0.0F) return 0.0F;
        if (t >= 1.0F) return 1.0F;
        return t * t * (3.0F - 2.0F * t);
    }

    /**
     * 1.20.6's polygon constructor takes the cube's UV rectangle and a face direction,
     * not a normal. Zero UV offsets keep the vertices untouched, and the normal vector
     * is overwritten afterwards.
     */
    private static ModelPart.Polygon polygon(ModelPart.Vertex[] vertices,
                                             Vector3f normal) {
        Direction direction = Direction.getNearest(normal.x(), normal.y(), normal.z());
        ModelPart.Polygon result = new ModelPart.Polygon(vertices,
            0.0F, 0.0F, 0.0F, 0.0F, 1.0F, 1.0F, false, direction);
        result.normal.set(normal);
        return result;
    }

    private static ModelPart.Polygon polygon(ModelPart.Vertex[] vertices,
                                             float nx, float ny, float nz) {
        return polygon(vertices, new Vector3f(nx, ny, nz));
    }
}
