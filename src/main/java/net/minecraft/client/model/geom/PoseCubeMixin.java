package net.minecraft.client.model.geom;

import com.example.playerpose.compat.PoseCubeAccess;
import com.example.playerpose.compat.PosePolygonAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 1.21.1 implementation of {@link PoseCubeAccess}.
 *
 * <p>This mixin intentionally lives in the {@code net.minecraft.client.model.geom}
 * package: {@code ModelPart.Polygon} and {@code ModelPart.Vertex} are package-private
 * there, so the geometry can only be inspected and rewritten from inside the package.</p>
 */
@Mixin(ModelPart.Cube.class)
public abstract class PoseCubeMixin implements PoseCubeAccess {

    @Shadow private ModelPart.Polygon[] polygons;

    @Override
    public int playerpose$polygonCount() {
        return polygons.length;
    }

    @Override
    public float[] playerpose$vertices(int polygon) {
        return polygon(polygon).playerpose$data();
    }

    @Override
    public float[] playerpose$normal(int polygon) {
        return polygon(polygon).playerpose$normal();
    }

    @Override
    public void playerpose$setPolygon(int polygon, float[] vertices, float nx, float ny, float nz) {
        polygon(polygon).playerpose$set(vertices, nx, ny, nz);
    }

    @Override
    public Object playerpose$snapshot() {
        Object[] snapshot = new Object[polygons.length];
        for (int i = 0; i < polygons.length; i++) {
            snapshot[i] = polygon(i).playerpose$snapshot();
        }
        return snapshot;
    }

    @Override
    public void playerpose$restore(Object snapshot) {
        Object[] parts = (Object[]) snapshot;
        int count = Math.min(parts.length, polygons.length);
        for (int i = 0; i < count; i++) {
            polygon(i).playerpose$restore(parts[i]);
        }
    }

    @Override
    public float[] playerpose$restVertices(Object snapshot, int polygon) {
        return this.polygon(polygon).playerpose$snapshotData(((Object[]) snapshot)[polygon]);
    }

    @Override
    public float[] playerpose$restNormal(Object snapshot, int polygon) {
        return this.polygon(polygon).playerpose$snapshotNormal(((Object[]) snapshot)[polygon]);
    }

    private PosePolygonAccess polygon(int index) {
        return (PosePolygonAccess) (Object) polygons[index];
    }
}
