package net.minecraft.client.model.geom;

import com.example.playerpose.compat.PosePolygonAccess;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 1.21.1 implementation of {@link PosePolygonAccess}. A quad stores its vertices and
 * normal in plain (final) fields; {@code @Mutable} lets the shim swap the array.
 */
@Mixin(ModelPart.Polygon.class)
public abstract class PosePolygonMixin implements PosePolygonAccess {

    @Mutable
    @Shadow public ModelPart.Vertex[] vertices;

    @Shadow public Vector3f normal;

    @Override
    public Object playerpose$snapshot() {
        return new Object[] {vertices.clone(), normal.x, normal.y, normal.z};
    }

    @Override
    public void playerpose$restore(Object snapshot) {
        Object[] parts = (Object[]) snapshot;
        vertices = (ModelPart.Vertex[]) parts[0];
        normal.set((Float) parts[1], (Float) parts[2], (Float) parts[3]);
    }

    @Override
    public void playerpose$set(float[] data, float nx, float ny, float nz) {
        int count = data.length / 5;
        ModelPart.Vertex[] next = new ModelPart.Vertex[count];
        for (int i = 0; i < count; i++) {
            int base = i * 5;
            next[i] = new ModelPart.Vertex(data[base], data[base + 1], data[base + 2],
                data[base + 3], data[base + 4]);
        }
        vertices = next;
        normal.set(nx, ny, nz);
    }

    @Override
    public float[] playerpose$snapshotData(Object snapshot) {
        ModelPart.Vertex[] stored = (ModelPart.Vertex[]) ((Object[]) snapshot)[0];
        float[] data = new float[stored.length * 5];
        for (int i = 0; i < stored.length; i++) {
            ModelPart.Vertex vertex = stored[i];
            int base = i * 5;
            data[base] = vertex.pos.x;
            data[base + 1] = vertex.pos.y;
            data[base + 2] = vertex.pos.z;
            data[base + 3] = vertex.u;
            data[base + 4] = vertex.v;
        }
        return data;
    }

    @Override
    public float[] playerpose$snapshotNormal(Object snapshot) {
        Object[] parts = (Object[]) snapshot;
        return new float[] {(Float) parts[1], (Float) parts[2], (Float) parts[3]};
    }

    /** Vertex data (x, y, z, u, v per vertex) for the cube shim. */
    public float[] playerpose$data() {
        float[] data = new float[vertices.length * 5];
        for (int i = 0; i < vertices.length; i++) {
            ModelPart.Vertex vertex = vertices[i];
            int base = i * 5;
            data[base] = vertex.pos.x;
            data[base + 1] = vertex.pos.y;
            data[base + 2] = vertex.pos.z;
            data[base + 3] = vertex.u;
            data[base + 4] = vertex.v;
        }
        return data;
    }

    public float[] playerpose$normal() {
        return new float[] {normal.x, normal.y, normal.z};
    }
}
