package com.example.playerpose.pose;

import com.example.playerpose.PlayerPoseClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Loads/saves poses as JSON files in <config>/player-pose/. */
public class PoseManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static PoseManager instance;

    /** Deep copy of a pose, used for the editor's undo snapshots. */
    public static Pose copy(Pose pose) {
        if (pose == null) return null;
        return GSON.fromJson(GSON.toJson(pose), Pose.class);
    }

    /** Canonical JSON for a pose, used to tell whether an edit changed anything. */
    public static String toJson(Pose pose) {
        return pose == null ? "" : GSON.toJson(pose);
    }

    private final Path dir;
    private final Map<String, Pose> poses = new ConcurrentHashMap<>();

    public PoseManager() {
        this.dir = FabricLoader.getInstance().getConfigDir().resolve("player-pose");
        try { Files.createDirectories(dir); }
        catch (IOException e) {
            PlayerPoseClient.LOGGER.warn("Cannot create pose dir: {}", dir, e);
        }
    }

    public static synchronized PoseManager instance() {
        return instance;
    }

    public static synchronized void setInstance(PoseManager m) {
        instance = m;
    }

    public void load() {
        poses.clear();
        int n = 0;
        try (var listing = Files.list(dir)) {
            var files = listing.filter(p -> p.toString().endsWith(".json")).toList();
            for (Path f : files) {
                if (loadOne(f)) n++;
            }
        } catch (IOException e) {
            PlayerPoseClient.LOGGER.warn("Failed to list pose dir {}", dir, e);
        }
        PoseLog.info("load: read %d pose(s) from %s", n, dir);
    }

    private boolean loadOne(Path file) {
        try {
            String text = Files.readString(file);
            Pose pose = GSON.fromJson(text, Pose.class);
            if (pose != null && pose.getName() != null && !pose.getName().isBlank()) {
                Pose previous = poses.put(pose.getName(), pose);
                if (previous != null) {
                    PoseLog.warn("load: duplicate pose '%s' in %s (another file already holds it)",
                        pose.getName(), file.getFileName());
                }
                return true;
            }
            PoseLog.warn("load: skipping %s (no name)", file.getFileName());
        } catch (Exception e) {
            PlayerPoseClient.LOGGER.warn("Failed to load {}", file, e);
            PoseLog.warn("load: failed to read %s (%s)", file.getFileName(), e.getMessage());
        }
        return false;
    }

    /**
     * Write a pose to disk and into the in-memory map, without touching bindings.
     * Selecting another pose in the editor and saving it must not unload the pose
     * that is currently in effect.
     */
    public void save(Pose pose) {
        if (pose == null || pose.getName() == null || pose.getName().isBlank()) return;
        // Keep the manager's copy independent from the editor's working pose, so later
        // unsaved edits cannot leak into what is stored or applied.
        Pose stored = copy(pose);
        writeToDisk(stored);
        poses.put(stored.getName(), stored);
        PoseLog.info("save: '%s' skin=%s bones=%d",
            stored.getName(),
            stored.getSkin() == null || stored.getSkin().isBlank() ? "none" : "'" + stored.getSkin() + "'",
            stored.getBones() == null ? 0 : stored.getBones().size());
    }

    /**
     * Save a pose and make it the one bound to its skin: every other pose for that
     * player loses its binding. Only an explicit load (or the first save, when nothing
     * is loaded yet) goes through here.
     */
    public void bind(Pose pose) {
        if (pose == null || pose.getName() == null || pose.getName().isBlank()) return;
        Pose stored = copy(pose);
        unbindOthers(stored);
        writeToDisk(stored);
        poses.put(stored.getName(), stored);
        PoseLog.info("save: '%s' skin=%s bones=%d",
            stored.getName(),
            stored.getSkin() == null || stored.getSkin().isBlank() ? "none" : "'" + stored.getSkin() + "'",
            stored.getBones() == null ? 0 : stored.getBones().size());
    }

    /** Write a single pose file. Never touches the in-memory map. */
    private void writeToDisk(Pose pose) {
        if (pose == null || pose.getName() == null || pose.getName().isBlank()) return;
        Path file = dir.resolve(sanitize(pose.getName()) + ".json");
        try {
            Files.writeString(file, GSON.toJson(pose));
            // A pose saved with an older/alternate file name (before non-ASCII names got
            // an encoded file name, or after a manual rename) would otherwise be loaded
            // again next to this one.
            deleteOtherFilesFor(pose.getName(), file);
        } catch (IOException e) {
            PlayerPoseClient.LOGGER.warn("Failed to save {}", file, e);
        }
    }

    /** Delete every file that holds this pose, except {@code keep}. */
    private void deleteOtherFilesFor(String name, Path keep) {
        Path encoded = dir.resolve(sanitize(name) + ".json");
        Path legacy = dir.resolve(legacySanitize(name) + ".json");
        if (!encoded.equals(keep)) deleteIfBelongsTo(encoded, name);
        if (!legacy.equals(keep) && !legacy.equals(encoded)) deleteIfBelongsTo(legacy, name);
    }

    /** Delete the file(s) that hold exactly this pose name. */
    private void deleteFilesFor(String name) {
        Path encoded = dir.resolve(sanitize(name) + ".json");
        Path legacy = dir.resolve(legacySanitize(name) + ".json");
        deleteIfBelongsTo(encoded, name);
        if (!legacy.equals(encoded)) deleteIfBelongsTo(legacy, name);
    }

    /**
     * Only delete a file if the JSON inside really is this pose - a sanitised file name
     * can collide with an unrelated pose, and that pose's data must survive.
     */
    private void deleteIfBelongsTo(Path file, String name) {
        try {
            if (!Files.exists(file)) return;
            Pose other = GSON.fromJson(Files.readString(file), Pose.class);
            if (other != null && name.equals(other.getName())) {
                Files.deleteIfExists(file);
                PoseLog.info("cleanup: removed alternate file %s for '%s'", file.getFileName(), name);
            }
        } catch (Exception e) {
            PlayerPoseClient.LOGGER.warn("Failed to inspect {}", file, e);
        }
    }

    /**
     * Make this the one pose for this player: every other pose loses its binding, so a
     * later lookup can only ever find this one. Deliberately blunt - the mod tracks a
     * single local player, so any other binding is stale.
     */
    private void unbindOthers(Pose pose) {
        if (pose == null || pose.getSkin() == null || pose.getSkin().isBlank()) return;
        String name = pose.getName();
        StringBuilder unbound = new StringBuilder();
        for (Pose other : poses.values()) {
            if (other == pose) continue;
            if (other.getName() != null && other.getName().equals(name)) continue;
            if (other.getSkin() != null && !other.getSkin().isBlank()) {
                other.setSkin(null);
                writeToDisk(other);
                if (unbound.length() > 0) unbound.append(", ");
                unbound.append(other.getName());
            }
        }
        if (unbound.length() > 0) {
            PoseLog.info("bind: '%s' -> '%s'; unbound [%s]",
                pose.getSkin(), name, unbound);
        } else {
            PoseLog.info("bind: '%s' -> '%s' (no other poses were bound)", pose.getSkin(), name);
        }
    }

    public void delete(String name) {
        if (name == null) return;
        poses.remove(name);
        deleteFilesFor(name);
        PoseLog.info("delete: '%s'", name);
    }

    /** Return all pose names sorted alphabetically. */
    public List<String> listNames() {
        List<String> names = new ArrayList<>(poses.keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** Return all poses sorted by name, with skin filter visible to the UI. */
    public List<Pose> listAllPoses() {
        List<Pose> out = new ArrayList<>(poses.values());
        out.sort(Comparator.comparing(Pose::getName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    public Pose get(String name) {
        return poses.get(name);
    }

    /**
     * The pose bound to this skin, or null.
     *
     * Exact match only - no falling back to some arbitrary unbound pose - and sorted
     * so it is deterministic even if a hand-edited file still leaves duplicates.
     */
    public Pose findForSkin(String skin) {
        if (skin == null || skin.isBlank()) return null;
        return poses.values().stream()
            .filter(p -> sameSkin(skin, p.getSkin()))
            .min(Comparator.comparing(p -> p.getName() == null ? "" : p.getName(),
                String.CASE_INSENSITIVE_ORDER))
            .orElse(null);
    }

    /** Skin strings compared trimmed and case-insensitively - a stray space used to
     *  leave one pose bound and another not. */
    private static boolean sameSkin(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }

    /**
     * File name for a pose. Plain ASCII names keep their historical file name;
     * anything else gets a readable (Unicode-preserving) stem plus a short hash, so
     * "wave" and "wave!" or two Chinese names cannot overwrite each other. The hash
     * also keeps Windows device names like CON from being produced.
     */
    private static String sanitize(String name) {
        if (name == null) return "pose";
        if (name.matches("[A-Za-z0-9_-]{1,64}") && !WINDOWS_RESERVED.contains(name.toUpperCase(Locale.ROOT))) {
            return name;
        }
        String readable = name.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (readable.length() > 48) {
            int end = 48;
            if (Character.isHighSurrogate(readable.charAt(end - 1))) end--;
            readable = readable.substring(0, end).trim();
        }
        if (readable.isEmpty() || readable.equals(".") || readable.equals("..")) readable = "pose";
        String hash = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8))
            .toString().substring(0, 8);
        return readable + "~" + hash;
    }

    /** The file name scheme used before Unicode names got an encoded name. */
    private static String legacySanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }

    private static final Set<String> WINDOWS_RESERVED = Set.of(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");
}
