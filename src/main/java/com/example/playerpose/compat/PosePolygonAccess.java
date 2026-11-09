package com.example.playerpose.compat;

/**
 * Version shim for {@code ModelPart.Polygon} (package-private in 1.21.1) so the cube
 * shim can snapshot and rewrite quads without leaking the nested type.
 */
public interface PosePolygonAccess {

    /** Opaque snapshot: vertex array plus normal components. */
    Object playerpose$snapshot();

    void playerpose$restore(Object snapshot);

    /** Vertex data (x, y, z, u, v per vertex). */
    float[] playerpose$data();

    /** Face normal (x, y, z). */
    float[] playerpose$normal();

    /** Vertex data stored in a snapshot taken earlier. */
    float[] playerpose$snapshotData(Object snapshot);

    /** Face normal stored in a snapshot taken earlier. */
    float[] playerpose$snapshotNormal(Object snapshot);

    /** Replace vertex data (x, y, z, u, v per vertex) and the normal. */
    void playerpose$set(float[] vertices, float nx, float ny, float nz);
}
