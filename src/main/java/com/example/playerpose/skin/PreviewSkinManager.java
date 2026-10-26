package com.example.playerpose.skin;

import com.example.playerpose.pose.PoseLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Editor-only skin preview.
 *
 * <p>The editor renders a fresh {@code AvatarRenderState} every frame. This class keeps a
 * preview texture and a matching {@link PlayerSkin}, and the editor swaps only that
 * render state's skin. The local player's real skin, the entity, and anything sent to the
 * server are untouched.</p>
 */
public final class PreviewSkinManager {

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "cheese-skin-preview");
        thread.setDaemon(true);
        return thread;
    });
    private static final String USER_AGENT = "Cheese/2.0.0 (Minecraft mod)";

    private static volatile boolean active;
    private static volatile boolean loading;
    private static volatile String status = "";
    private static Identifier textureId;
    private static PlayerSkin skin;
    private static NativeImage pixels;
    private static ClientAsset.Texture cape;
    private static ClientAsset.Texture elytra;
    private static PlayerModelType model = PlayerModelType.WIDE;
    private static int counter;

    private PreviewSkinManager() {}

    public static PlayerSkin skin() {
        return active ? skin : null;
    }

    /** Pixels of the active preview body texture, for the panel thumbnail. */
    public static NativeImage pixels() {
        return active ? pixels : null;
    }

    public static String status() {
        return status;
    }

    public static boolean loading() {
        return loading;
    }

    public static PlayerModelType model() {
        return model;
    }

    /** The editor is open: previews may be applied. */
    public static void setActive(boolean value) {
        active = value;
        if (!value) {
            releaseTexture();
            skin = null;
            status = "";
            loading = false;
        }
    }

    public static void clear() {
        releaseTexture();
        skin = null;
        cape = null;
        elytra = null;
        status = "";
        loading = false;
    }

    /** Load a Mojang account's current skin by player name. */
    public static void loadFromPlayer(String name, Consumer<String> feedback) {
        if (name == null || name.isBlank()) {
            feedback.accept("Enter a player name");
            return;
        }
        loading = true;
        status = "Loading " + name + "...";
        feedback.accept(status);
        CompletableFuture.runAsync(() -> {
            try {
                String encoded = URLEncoder.encode(name.trim(), StandardCharsets.UTF_8)
                    .replace("+", "%20");
                String profileBody = getText(
                    "https://api.mojang.com/users/profiles/minecraft/" + encoded);
                JsonObject profile = JsonParser.parseString(profileBody).getAsJsonObject();
                String uuid = profile.get("id").getAsString();
                String displayName = profile.has("name")
                    ? profile.get("name").getAsString() : name.trim();

                String sessionBody = getText(
                    "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid);
                JsonObject session = JsonParser.parseString(sessionBody).getAsJsonObject();
                String encodedTextures = null;
                for (JsonElement element : session.getAsJsonArray("properties")) {
                    JsonObject property = element.getAsJsonObject();
                    if ("textures".equals(property.get("name").getAsString())) {
                        encodedTextures = property.get("value").getAsString();
                        break;
                    }
                }
                if (encodedTextures == null) {
                    throw new IOException("profile has no textures");
                }
                String decoded = new String(Base64.getDecoder().decode(encodedTextures),
                    StandardCharsets.UTF_8);
                JsonObject skinObject = JsonParser.parseString(decoded).getAsJsonObject()
                    .getAsJsonObject("textures").getAsJsonObject("SKIN");
                String url = skinObject.get("url").getAsString();
                PlayerModelType type = PlayerModelType.WIDE;
                if (skinObject.has("metadata")
                    && skinObject.getAsJsonObject("metadata").has("model")
                    && "slim".equalsIgnoreCase(
                        skinObject.getAsJsonObject("metadata").get("model").getAsString())) {
                    type = PlayerModelType.SLIM;
                }
                byte[] png = getBytes(url);
                NativeImage image = NativeImage.read(png);
                String label = "Preview: " + displayName;
                PlayerModelType resolvedType = type;
                Minecraft.getInstance().execute(() -> apply(image, resolvedType, label, feedback));
            } catch (Throwable error) {
                PoseLog.warn("skin preview: player '%s' failed (%s)", name, error);
                fail("Could not load skin: " + shortMessage(error), feedback);
            }
        }, WORKER);
    }

    /** Load a local 64x64 PNG skin. */
    public static void loadFromFile(Path path, PlayerModelType type, Consumer<String> feedback) {
        if (path == null) {
            feedback.accept("Choose a skin file first");
            return;
        }
        loading = true;
        status = "Loading file...";
        feedback.accept(status);
        CompletableFuture.runAsync(() -> {
            try {
                byte[] bytes = Files.readAllBytes(path);
                NativeImage image = NativeImage.read(bytes);
                Minecraft.getInstance().execute(() -> apply(image, type,
                    "Preview: " + path.getFileName(), feedback));
            } catch (Throwable error) {
                PoseLog.warn("skin preview: file '%s' failed (%s)", path, error);
                fail("Could not read that PNG", feedback);
            }
        }, WORKER);
    }

    /** Rebuild the preview for a different arm model. */
    public static void setModel(PlayerModelType type, Consumer<String> feedback) {
        model = type;
        if (skin != null && textureId != null) {
            ClientAsset.ResourceTexture body = new ClientAsset.ResourceTexture(textureId, textureId);
            PlayerSkin rebuilt = PlayerSkin.insecure(body,
                cape != null ? cape : body,
                elytra != null ? elytra : body,
                model);
            skin = rebuilt;
            status = model == PlayerModelType.SLIM
                ? "Preview arms: slim" : "Preview arms: classic";
            feedback.accept(status);
        }
    }

    private static void apply(NativeImage image, PlayerModelType type, String label,
                              Consumer<String> feedback) {
        try {
            applyInternal(image, type, label, feedback);
        } catch (Throwable error) {
            PoseLog.warn("skin preview: apply failed (%s)", error);
            try {
                image.close();
            } catch (Throwable ignored) {
                // already closed
            }
            fail("Could not apply skin: " + shortMessage(error), feedback);
        }
    }

    private static void applyInternal(NativeImage image, PlayerModelType type, String label,
                                      Consumer<String> feedback) {
        if (!active) {
            image.close();
            return;
        }
        image = modernize(image);
        if (image == null) {
            fail("Skin must be a 64x64 or 64x32 PNG", feedback);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            image.close();
            fail("No player to preview", feedback);
            return;
        }
        PlayerSkin original = mc.player.getSkin();
        if (original == null) {
            image.close();
            fail("No player skin available", feedback);
            return;
        }

        releaseTexture();
        Identifier id = Identifier.fromNamespaceAndPath("player-pose",
            "preview/skin_" + (counter++));
        DynamicTexture texture = new DynamicTexture(() -> "cheese preview skin", image);
        mc.getTextureManager().register(id, texture);

        textureId = id;
        model = type;
        pixels = image;
        cape = original.cape();
        elytra = original.elytra();
        ClientAsset.ResourceTexture body = new ClientAsset.ResourceTexture(id, id);
        skin = PlayerSkin.insecure(body,
            cape != null ? cape : body,
            elytra != null ? elytra : body,
            model);
        loading = false;
        status = label;
        feedback.accept(label);
    }

    /** Accept modern 64x64 skins and convert legacy 64x32 skins. */
    private static NativeImage modernize(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width == 64 && height == 64) return image;
        if (width == 64 && height == 32) {
            NativeImage converted = convertLegacy(image);
            image.close();
            return converted;
        }
        image.close();
        return null;
    }

    /**
     * Legacy skins only contain the right arm/leg. Copy the top half, duplicate the
     * right limbs into the modern left-limb regions, and clear the old unused
     * top-right block (modern skins use it for the hat layer).
     */
    private static NativeImage convertLegacy(NativeImage source) {
        NativeImage out = new NativeImage(NativeImage.Format.RGBA, 64, 64, true);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                out.setPixel(x, y, source.getPixel(x, y));
            }
        }
        for (int y = 0; y < 16; y++) {
            for (int x = 32; x < 64; x++) {
                out.setPixel(x, y, 0);
            }
        }
        copyRegion(source, out, 40, 16, 32, 48); // right arm -> left arm
        copyRegion(source, out, 0, 16, 16, 48);  // right leg -> left leg
        return out;
    }

    private static void copyRegion(NativeImage source, NativeImage out,
                                   int sx, int sy, int dx, int dy) {
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                out.setPixel(dx + x, dy + y, source.getPixel(sx + x, sy + y));
            }
        }
    }

    private static void fail(String message, Consumer<String> feedback) {
        loading = false;
        status = message;
        Minecraft.getInstance().execute(() -> feedback.accept(message));
    }

    private static void releaseTexture() {
        pixels = null;
        if (textureId != null) {
            try {
                Minecraft.getInstance().getTextureManager().release(textureId);
            } catch (Throwable ignored) {
                // texture may already be gone after a resource reload
            }
            textureId = null;
        }
    }

    private static String getText(String url) throws IOException, InterruptedException {
        HttpResponse<String> response = get(url, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static byte[] getBytes(String url) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = get(url, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }

    private static <T> HttpResponse<T> get(String url,
                                           HttpResponse.BodyHandler<T> handler)
        throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
        return HTTP.send(request, handler);
    }

    private static String shortMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message;
    }
}
