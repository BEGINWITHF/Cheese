package com.example.playerpose.skin;

import com.example.playerpose.pose.PoseLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
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
import java.util.List;
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
    private static final String USER_AGENT = "Cheese/2.2.0 (Minecraft mod)";

    /** An official cape texture bundled with the mod. */
    public record OfficialCape(String id, String label) {}

    /** Menu order for the bundled official capes. */
    public static final List<OfficialCape> OFFICIAL_CAPES = List.of(
        new OfficialCape("migrator", "Migrator"),
        new OfficialCape("mojang", "Mojang"),
        new OfficialCape("mojang_studios", "Mojang Studios"),
        new OfficialCape("mojang_classic", "Mojang Classic"),
        new OfficialCape("vanilla", "Vanilla"),
        new OfficialCape("common", "Common"),
        new OfficialCape("pan", "Pan"),
        new OfficialCape("mapmaker", "Mapmaker"),
        new OfficialCape("translator", "Translator"),
        new OfficialCape("translator_cn", "Translator CN"),
        new OfficialCape("cherry", "Cherry"),
        new OfficialCape("purple_heart", "Purple Heart"),
        new OfficialCape("followers", "Follower's"),
        new OfficialCape("anniversary_15", "15th Anniv."),
        new OfficialCape("experience", "Experience"),
        new OfficialCape("minecon_2011", "Minecon 2011"),
        new OfficialCape("minecon_2016", "Minecon 2016"),
        new OfficialCape("turtle", "Turtle")
    );

    private static volatile boolean active;
    private static volatile boolean loading;
    private static volatile boolean demo;
    private static volatile int version;
    private static volatile String status = "";
    private static Identifier textureId;
    private static PlayerSkin skin;
    private static NativeImage pixels;
    private static ClientAsset.Texture cape;
    private static ClientAsset.Texture elytra;
    private static ClientAsset.Texture bodyTexture;
    private static ClientAsset.Texture demoCapeTexture;
    private static Identifier demoCapeId;
    private static ClientAsset.Texture chosenCapeTexture;
    private static Identifier chosenCapeId;
    private static NativeImage chosenCapePixels;
    private static String chosenCapeKey = "";
    private static ClientAsset.Texture fetchedCapeTexture;
    private static Identifier fetchedCapeId;
    private static ClientAsset.Texture fetchedElytraTexture;
    private static Identifier fetchedElytraId;
    private static boolean demoCape;
    /** A player preview owns the cape/elytra slot: the local ones must not leak in. */
    private static boolean fetchedGear;
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

    /** True while the preview body is the bundled blink demo skin. */
    public static boolean isDemo() {
        return active && demo;
    }

    /** True while the bundled demo cape is drawn on top of the real one. */
    public static boolean isDemoCape() {
        return active && demoCape;
    }

    /** The cape the editor should render: a picked cape, then the demo one, then the real one. */
    public static ClientAsset.Texture effectiveCape() {
        if (chosenCapeTexture != null) return chosenCapeTexture;
        if (demoCapeTexture != null) return demoCapeTexture;
        // A player preview owns the slot: they may have no cape at all, and the local
        // player's cape must not leak onto their back.
        if (fetchedGear) return fetchedCapeTexture;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getSkin() != null) {
            return mc.player.getSkin().cape();
        }
        return cape;
    }

    /**
     * Toggle the bundled demo cape. Used when the player has no cape of their own, so the
     * cape bone can still be tried; clicking again puts the real (empty) cape back.
     */
    public static void toggleDemoCape(Consumer<String> feedback) {
        if (demoCape) {
            clearDemoCape();
            rebuildSkin();
            feedback.accept("Original cape");
            version++;
            EyelidManager.onBaseChanged();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Identifier resource = Identifier.fromNamespaceAndPath("player-pose",
            "textures/cape/demo_cape.png");
        var res = mc.getResourceManager().getResource(resource);
        if (res.isEmpty()) {
            feedback.accept("Demo cape texture missing");
            return;
        }
        try (var in = res.get().open()) {
            NativeImage image = NativeImage.read(in);
            Identifier id = Identifier.fromNamespaceAndPath("player-pose",
                "preview/cape_" + (counter++));
            DynamicTexture texture = new DynamicTexture(() -> "cheese demo cape", image);
            mc.getTextureManager().register(id, texture);
            demoCapeId = id;
            demoCapeTexture = new ClientAsset.ResourceTexture(id, id);
            demoCape = true;
            rebuildSkin();
            version++;
            EyelidManager.onBaseChanged();
            feedback.accept("Demo cape loaded");
        } catch (Throwable error) {
            PoseLog.warn("skin preview: demo cape failed (%s)", error);
            feedback.accept("Could not load the demo cape");
        }
    }

    private static void clearDemoCape() {
        demoCape = false;
        demoCapeTexture = null;
        if (demoCapeId != null) {
            try {
                Minecraft.getInstance().getTextureManager().release(demoCapeId);
            } catch (Throwable ignored) {
                // already released by a resource reload
            }
            demoCapeId = null;
        }
    }

    /** Pixels of the picked cape, for the panel thumbnail; null when none is picked. */
    public static NativeImage capePixels() {
        return chosenCapePixels != null && !chosenCapePixels.isClosed()
            ? chosenCapePixels : null;
    }

    /** Which menu entry is picked ("migrator", "file", ...); empty when none. */
    public static String chosenCapeKey() {
        return chosenCapeKey;
    }

    /** Use a bundled official cape texture for the preview. */
    public static void selectOfficialCape(OfficialCape cape, Consumer<String> feedback) {
        Identifier id = Identifier.fromNamespaceAndPath("player-pose",
            "textures/cape/official/" + cape.id() + ".png");
        var resource = Minecraft.getInstance().getResourceManager().getResource(id);
        if (resource.isEmpty()) {
            feedback.accept("Cape texture missing");
            return;
        }
        try (var in = resource.get().open()) {
            NativeImage image = NativeImage.read(in);
            if (!applyCapeImage(image, cape.id())) {
                image.close();
                feedback.accept("Cape must be a 64x32 PNG");
                return;
            }
            feedback.accept("Cape: " + cape.label());
        } catch (Throwable error) {
            PoseLog.warn("skin preview: official cape '%s' failed (%s)", cape.id(), error);
            feedback.accept("Could not load that cape");
        }
    }

    /** Read and apply a 64x32 cape PNG from disk, off the render thread. */
    public static void importCapeFile(Path path, Consumer<String> feedback) {
        if (path == null) {
            feedback.accept("Choose a cape PNG");
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                byte[] bytes = Files.readAllBytes(path);
                NativeImage image = NativeImage.read(bytes);
                Minecraft.getInstance().execute(() -> {
                    if (!active) {
                        image.close();
                        return;
                    }
                    if (applyCapeImage(image, "file")) {
                        feedback.accept("Cape: " + path.getFileName());
                    } else {
                        image.close();
                        feedback.accept("Cape must be a 64x32 PNG");
                    }
                });
            } catch (Throwable error) {
                PoseLog.warn("skin preview: cape file '%s' failed (%s)", path, error);
                Minecraft.getInstance().execute(
                    () -> feedback.accept("Could not read that cape PNG"));
            }
        }, WORKER);
    }

    /** Drop the picked cape and the demo cape, going back to the real one. */
    public static void clearPreviewCape(Consumer<String> feedback) {
        releaseChosenCape();
        clearDemoCape();
        rebuildSkin();
        version++;
        EyelidManager.onBaseChanged();
        feedback.accept("Original cape");
    }

    private static boolean applyCapeImage(NativeImage image, String key) {
        if (image.getWidth() != 64 || image.getHeight() != 32) return false;
        releaseChosenCape();
        Identifier id = Identifier.fromNamespaceAndPath("player-pose",
            "preview/cape_" + (counter++));
        DynamicTexture texture = new DynamicTexture(() -> "cheese preview cape", image);
        Minecraft.getInstance().getTextureManager().register(id, texture);
        chosenCapeId = id;
        chosenCapeTexture = new ClientAsset.ResourceTexture(id, id);
        chosenCapePixels = image;
        chosenCapeKey = key == null ? "" : key;
        clearDemoCape();
        rebuildSkin();
        version++;
        EyelidManager.onBaseChanged();
        PoseLog.info("skin preview: cape '%s' (%dx%d)", chosenCapeKey,
            image.getWidth(), image.getHeight());
        return true;
    }

    private static void releaseChosenCape() {
        chosenCapeTexture = null;
        chosenCapePixels = null;
        chosenCapeKey = "";
        if (chosenCapeId != null) {
            try {
                Minecraft.getInstance().getTextureManager().release(chosenCapeId);
            } catch (Throwable ignored) {
                // already released by a resource reload
            }
            chosenCapeId = null;
        }
    }

    /** Rebuild the preview PlayerSkin from whatever body/cape are currently active. */
    private static void rebuildSkin() {
        Minecraft mc = Minecraft.getInstance();
        PlayerSkin original = mc.player != null ? mc.player.getSkin() : null;
        if (bodyTexture == null && original != null) {
            // No preview body: the model must match the player's real skin.
            model = original.model();
        }
        ClientAsset.Texture body = bodyTexture != null ? bodyTexture
            : (original != null ? original.body() : null);
        if (body == null) {
            skin = null;
            return;
        }
        ClientAsset.Texture capeTex;
        ClientAsset.Texture elytraTex;
        if (chosenCapeTexture != null) {
            capeTex = chosenCapeTexture;
            // Vanilla capes also provide the elytra art, so keep that preview consistent.
            elytraTex = fetchedElytraTexture != null ? fetchedElytraTexture
                : chosenCapeTexture;
        } else if (fetchedGear) {
            capeTex = demoCapeTexture != null ? demoCapeTexture : fetchedCapeTexture;
            // No ELYTRA entry in that profile? Keep the usual elytra texture; passing
            // null would let the cape texture fall through onto the wings.
            elytraTex = fetchedElytraTexture != null ? fetchedElytraTexture
                : (original != null ? original.elytra() : elytra);
        } else {
            capeTex = demoCapeTexture != null ? demoCapeTexture
                : (original != null ? original.cape() : cape);
            elytraTex = original != null ? original.elytra() : elytra;
        }
        skin = PlayerSkin.insecure(body, capeTex, elytraTex, model);
    }

    public static PlayerModelType model() {
        return model;
    }

    /** The editor is open: previews may be applied. */
    public static void setActive(boolean value) {
        active = value;
        if (!value) {
            releaseTexture();
            releaseChosenCape();
            clearDemoCape();
            fetchedGear = false;
            demo = false;
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
        bodyTexture = null;
        fetchedGear = false;
        demo = false;
        version++;
        status = "";
        loading = false;
        // Original skin keeps whatever cape the Cape tab picked; it only drops the body.
        if (active && (chosenCapeTexture != null || demoCapeTexture != null)) {
            rebuildSkin();
        }
        EyelidManager.onBaseChanged();
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
                FetchedSkin fetched = fetchPlayer(name);
                Minecraft.getInstance().execute(() -> apply(fetched.image, fetched.type,
                    "Preview: " + fetched.displayName, feedback, false,
                    fetched.capePng(), fetched.elytraPng(), true));
            } catch (Throwable error) {
                PoseLog.warn("skin preview: player '%s' failed (%s)", name, error);
                fail("Could not load skin: " + shortMessage(error), feedback);
            }
        }, WORKER);
    }

    private record FetchedSkin(NativeImage image, PlayerModelType type, String displayName,
                               byte[] capePng, byte[] elytraPng) {}

    private static FetchedSkin fetchPlayer(String name) throws IOException, InterruptedException {
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
        NativeImage image = NativeImage.read(getBytes(url));
        return new FetchedSkin(image, type, displayName,
            downloadOptional(texturesUrl(decoded, "CAPE")),
            downloadOptional(texturesUrl(decoded, "ELYTRA")));
    }

    /** URL of one texture kind ("CAPE", "ELYTRA") in a decoded textures property, or null. */
    private static String texturesUrl(String decoded, String kind) {
        try {
            JsonObject textures = JsonParser.parseString(decoded).getAsJsonObject()
                .getAsJsonObject("textures");
            if (!textures.has(kind)) return null;
            JsonObject entry = textures.getAsJsonObject(kind);
            return entry.has("url") ? entry.get("url").getAsString() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] downloadOptional(String url) {
        if (url == null) return null;
        try {
            return getBytes(url);
        } catch (Throwable error) {
            PoseLog.warn("skin preview: cape/elytra download failed (%s)", error);
            return null;
        }
    }

    /** Register a texture from raw PNG bytes; returns null when the bytes are unusable. */
    private static ClientAsset.Texture registerFetched(byte[] png, String name,
                                                       Identifier[] idOut) {
        if (png == null || png.length == 0) return null;
        try {
            NativeImage image = NativeImage.read(png);
            Identifier id = Identifier.fromNamespaceAndPath("player-pose",
                "preview/" + name + "_" + (counter++));
            DynamicTexture texture = new DynamicTexture(() -> "cheese preview " + name, image);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            idOut[0] = id;
            return new ClientAsset.ResourceTexture(id, id);
        } catch (Throwable error) {
            PoseLog.warn("skin preview: %s texture failed (%s)", name, error);
            return null;
        }
    }

    private static void releaseFetched() {
        fetchedCapeTexture = null;
        fetchedElytraTexture = null;
        Minecraft mc = Minecraft.getInstance();
        if (fetchedCapeId != null) {
            try { mc.getTextureManager().release(fetchedCapeId); } catch (Throwable ignored) { }
            fetchedCapeId = null;
        }
        if (fetchedElytraId != null) {
            try { mc.getTextureManager().release(fetchedElytraId); } catch (Throwable ignored) { }
            fetchedElytraId = null;
        }
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
            // Go through rebuildSkin so the player-preview cape/elytra (or the demo cape)
            // survive the model switch; building the skin here used to fall back to the
            // body texture, which put the skin on the player's back.
            rebuildSkin();
            status = model == PlayerModelType.SLIM
                ? "Preview arms: slim" : "Preview arms: classic";
            feedback.accept(status);
        }
    }

    /** Copy of the active preview texture pixels, for eye composition. */
    public static NativeImage copyPixels() {
        NativeImage source;
        if (active && pixels != null && !pixels.isClosed()) {
            source = pixels;
        } else {
            // No preview skin: the eyes panel still inspects the skin the player really
            // wears, read from the texture the renderer is using.
            source = currentSkinImage();
        }
        if (source == null) return null;
        NativeImage copy = new NativeImage(NativeImage.Format.RGBA,
            source.getWidth(), source.getHeight(), true);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                copy.setPixel(x, y, source.getPixel(x, y));
            }
        }
        return modernize(copy);
    }

    /**
     * Pixels of the skin the local player is rendered with. Works for downloaded skins
     * (the texture is a {@link DynamicTexture} that keeps its image) and for skins loaded
     * from a resource pack (read back from the resource).
     */
    private static NativeImage currentSkinImage() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        PlayerSkin skin = mc.player.getSkin();
        if (skin == null || skin.body() == null) return null;
        Identifier id = skin.body().texturePath();
        if (id == null) return null;
        try {
            AbstractTexture texture = mc.getTextureManager().getTexture(id);
            if (texture instanceof DynamicTexture dynamic) {
                NativeImage image = dynamic.getPixels();
                if (image != null && !image.isClosed()) return image;
            }
        } catch (Throwable ignored) {
            // not registered yet: fall through to the resource lookup
        }
        try {
            var resource = mc.getResourceManager().getResource(id);
            if (resource.isPresent()) {
                try (var in = resource.get().open()) {
                    return NativeImage.read(in);
                }
            }
        } catch (Throwable error) {
            PoseLog.warn("skin preview: cannot read the player skin (%s)", error);
        }
        return null;
    }

    /**
     * Make sure the editor has a preview base skin to inspect.
     *
     * <p>Nothing is fetched here. The old fallback looked the local player up by name,
     * which on offline or dev accounts can be a real account: the editor would quietly
     * start wearing a stranger's skin just because the eyelid panel was opened. Without a
     * preview the panel simply reports that there is nothing to inspect, and the user can
     * load a skin or the bundled demo.</p>
     */
    public static void ensureBaseSkin(Runnable done) {
        done.run();
    }

    /** Load the built-in blink demo skin (supports both skin conventions). */
    public static void loadBundledDemo(PlayerModelType type, Consumer<String> feedback) {
        Minecraft mc = Minecraft.getInstance();
        Identifier id = Identifier.fromNamespaceAndPath("player-pose",
            "textures/skin/blink_demo_"
                + (type == PlayerModelType.SLIM ? "slim" : "wide") + ".png");
        var resource = mc.getResourceManager().getResource(id);
        if (resource.isEmpty()) {
            PoseLog.info("skin preview: bundled demo missing (%s)", id);
            feedback.accept("Blink demo skin missing");
            return;
        }
        try (var in = resource.get().open()) {
            NativeImage image = NativeImage.read(in);
            apply(image, type, "Preview: blink demo", feedback, true);
        } catch (Throwable error) {
            PoseLog.warn("skin preview: blink demo failed (%s)", error);
            feedback.accept("Could not load the blink demo skin");
        }
    }

    private static void apply(NativeImage image, PlayerModelType type, String label,
                              Consumer<String> feedback) {
        apply(image, type, label, feedback, false, null, null, false);
    }

    private static void apply(NativeImage image, PlayerModelType type, String label,
                              Consumer<String> feedback, boolean demoSource) {
        apply(image, type, label, feedback, demoSource, null, null, false);
    }

    private static void apply(NativeImage image, PlayerModelType type, String label,
                              Consumer<String> feedback, boolean demoSource,
                              byte[] capePng, byte[] elytraPng, boolean fromPlayer) {
        try {
            applyInternal(image, type, label, feedback, capePng, elytraPng, fromPlayer);
            demo = demoSource;
        } catch (Throwable error) {
            PoseLog.warn("skin preview: apply failed (%s)", error);
            try {
                image.close();
            } catch (Throwable ignored) {
                // already closed
            }
            fail("Could not apply skin: " + shortMessage(error), feedback);
            demo = false;
        }
    }

    private static void applyInternal(NativeImage image, PlayerModelType type, String label,
                                      Consumer<String> feedback, byte[] capePng,
                                      byte[] elytraPng, boolean fromPlayer) {
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
        bodyTexture = new ClientAsset.ResourceTexture(id, id);
        // The previewed player's own cape/elytra ride along, so the back is not the local
        // player's gear.
        Identifier[] capeId = new Identifier[1];
        fetchedCapeTexture = registerFetched(capePng, "cape", capeId);
        fetchedCapeId = capeId[0];
        Identifier[] elytraId = new Identifier[1];
        fetchedElytraTexture = registerFetched(elytraPng, "elytra", elytraId);
        fetchedElytraId = elytraId[0];
        fetchedGear = fromPlayer;
        // A missing cape must stay missing: passing the body texture here made the player
        // wear their own skin as a cape. The demo cape, when loaded, wins instead.
        rebuildSkin();
        loading = false;
        status = label;
        version++;
        PoseLog.info("skin preview: applied %dx%d model=%s (%s) cape=%s elytra=%s",
            pixels.getWidth(), pixels.getHeight(), model, label,
            fetchedCapeTexture != null ? "yes" : "none",
            fetchedElytraTexture != null ? "yes" : "none");
        EyelidManager.onBaseChanged();
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
        PoseLog.info("skin preview: failed (%s)", message);
        loading = false;
        status = message;
        Minecraft.getInstance().execute(() -> feedback.accept(message));
    }

    private static void releaseTexture() {
        pixels = null;
        bodyTexture = null;
        releaseFetched();
        fetchedGear = false;
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
