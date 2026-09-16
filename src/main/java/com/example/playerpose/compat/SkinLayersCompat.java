package com.example.playerpose.compat;

import com.example.playerpose.pose.LayerBends;
import com.example.playerpose.pose.PoseLog;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Optional integration with 3D Skin Layers (skinlayers3d).
 *
 * That mod renders the skin's second layer as its own mesh, attached to the limb /
 * sleeve part, and offers a hook for it:
 *
 *   SkinLayersAPI.setupMeshTransformerProvider(provider)
 *   MeshTransformerProvider.prepareTransformer(ModelPart) -> MeshTransformer
 *   MeshTransformer.transform(Vector3f position, Vector4f[] vertices)
 *
 * It calls prepareTransformer(part) while compiling the layer mesh and then hands each
 * polygon through transform(), in the part's local space and before the pose matrix is
 * applied - the same space as ModelPart cubes. So all this has to do is bend the part of
 * that mesh which sits below the joint, using the bend the pose mixin registered.
 *
 * Everything is reflective: the mod builds and runs without that mod present, and a
 * failure here only means "the layer does not bend", never a crash.
 */
public final class SkinLayersCompat {

    private static final String API = "dev.tr7zw.skinlayers.api.SkinLayersAPI";
    private static final String PROVIDER = "dev.tr7zw.skinlayers.api.MeshTransformerProvider";
    private static final String TRANSFORMER = "dev.tr7zw.skinlayers.api.MeshTransformer";

    private static boolean installed;

    private SkinLayersCompat() {}

    public static boolean isInstalled() {
        return installed;
    }

    /** Called once, on client init, when skinlayers3d is loaded. */
    public static void install() {
        if (installed) return;
        try {
            ClassLoader loader = SkinLayersCompat.class.getClassLoader();
            Class<?> apiClass = Class.forName(API, false, loader);
            Class<?> providerClass = Class.forName(PROVIDER, false, loader);
            Class<?> transformerClass = Class.forName(TRANSFORMER, false, loader);

            Object emptyTransformer = transformerClass.getField("EMPTY_TRANSFORMER").get(null);
            Object previous = apiClass.getMethod("getMeshTransformerProvider").invoke(null);

            Object provider = Proxy.newProxyInstance(loader, new Class<?>[] {providerClass},
                new ProviderHandler(emptyTransformer, previous));

            apiClass.getMethod("setupMeshTransformerProvider", providerClass).invoke(null, provider);
            installed = true;
            PoseLog.info("compat: 3D Skin Layers found - layer meshes will bend at the elbow/knee");
        } catch (Throwable t) {
            installed = false;
            PoseLog.warn("compat: 3D Skin Layers hook not installed (%s) - its layer will not bend", t);
        }
    }

    /** prepareTransformer(ModelPart) -> a transformer that bends that part's layer mesh. */
    private record ProviderHandler(Object emptyTransformer, Object previous) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("prepareTransformer") && args != null && args.length == 1
                && args[0] instanceof ModelPart part) {
                LayerBends.Bend bend = LayerBends.get(part);
                if (bend == null || bend.isIdentity()) return emptyTransformer;
                return Proxy.newProxyInstance(
                    SkinLayersCompat.class.getClassLoader(),
                    new Class<?>[] {method.getReturnType()},
                    new TransformerHandler(bend, delegateFor(part)));
            }
            if (method.getName().equals("toString")) return "playerpose-layer-bender";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return null;
        }

        /** Ask the previously installed provider what it wants to do, so we chain rather than replace. */
        private Object delegateFor(ModelPart part) {
            if (previous == null || previous == emptyTransformer()) return null;
            try {
                return previous.getClass().getMethod("prepareTransformer", ModelPart.class)
                    .invoke(previous, part);
            } catch (Throwable t) {
                return null;
            }
        }
    }

    /** transform(Vector3f, Vector4f[]) applies our bend, after any delegate. */
    private record TransformerHandler(LayerBends.Bend bend, Object delegate) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("transform")) {
                if (delegate != null) {
                    try {
                        delegate.getClass()
                            .getMethod("transform", Vector3f.class, Vector4f[].class)
                            .invoke(delegate, args);
                    } catch (Throwable t) {
                        // ignore: a broken delegate must not stop the bend
                    }
                }
                if (args != null && args.length == 2 && args[0] instanceof Vector3f pos
                    && args[1] instanceof Vector4f[] verts) {
                    bend.apply(pos);
                    for (Vector4f v : verts) {
                        if (v != null) bend.apply(v);
                    }
                }
                return null;
            }
            if (method.getName().equals("toString")) return "playerpose-layer-bend";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == args[0];
            return null; // transform(ModelPart.Cube) and anything else: no-op
        }
    }
}
