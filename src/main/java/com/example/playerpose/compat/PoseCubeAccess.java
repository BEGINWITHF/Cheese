package com.example.playerpose.compat;

import net.minecraft.client.model.geom.ModelPart;

/**
 * Version shim for {@link ModelPart.Cube}.
 *
 * <p>1.21.1 keeps the baked geometry in package-private {@code ModelPart.Polygon} and
 * {@code ModelPart.Vertex} classes with plain fields, where 1.21.3 exposes accessor
 * methods. The implementation of this interface is a mixin living in
 * {@code net.minecraft.client.model.geom} (see {@code PoseCubeMixin}), so the rest of
 * the mod can keep working with plain float arrays.</p>
 */
public interface PoseCubeAccess {

    /** Number of quads in this cube. */
    int playerpose$polygonCount();

    /** Vertex data of one quad: x, y, z, u, v for each vertex, concatenated. */
    float[] playerpose$vertices(int polygon);

    /** Face normal of one quad: x, y, z. */
    float[] playerpose$normal(int polygon);

    /** Replace one quad's vertices and normal. */
    void playerpose$setPolygon(int polygon, float[] vertices, float nx, float ny, float nz);

    /** Opaque copy of the current geometry, for restoring the rest shape. */
    Object playerpose$snapshot();

    /** Restore geometry previously captured with {@link #playerpose$snapshot()}. */
    void playerpose$restore(Object snapshot);

    /** Vertex data stored in a snapshot, in the same layout as {@link #playerpose$vertices(int)}. */
    float[] playerpose$restVertices(Object snapshot, int polygon);

    /** Face normal stored in a snapshot. */
    float[] playerpose$restNormal(Object snapshot, int polygon);

    static PoseCubeAccess of(ModelPart.Cube cube) {
        return (PoseCubeAccess) (Object) cube;
    }
}
