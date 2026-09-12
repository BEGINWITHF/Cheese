package com.example.playerpose.pose;

import com.example.playerpose.PlayerPoseClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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
                poses.put(pose.getName(), pose);
                return true;
            }
            PoseLog.warn("load: skipping %s (no name)", file.getFileName());
        } catch (Exception e) {
            PlayerPoseClient.LOGGER.warn("Failed to load {}", file, e);
            PoseLog.warn("load: failed to read %s (%s)", file.getFileName(), e.getMessage());
        }
        return false;
    }

    public void save(Pose pose) {
        if (pose == null || pose.getName() == null || pose.getName().isBlank()) return;
        // One pose per skin: saving this one unbinds any other pose bound to the same
        // player, so closing the editor can never land on a different pose.
        unbindOthers(pose);
        writeToDisk(pose);
        poses.put(pose.getName(), pose);
        PoseLog.info("save: '%s' skin=%s bones=%d",
            pose.getName(),
            pose.getSkin() == null || pose.getSkin().isBlank() ? "none" : "'" + pose.getSkin() + "'",
            pose.getBones() == null ? 0 : pose.getBones().size());
    }

    /** Write a single pose file. Never touches the in-memory map. */
    private void writeToDisk(Pose pose) {
        if (pose == null || pose.getName() == null || pose.getName().isBlank()) return;
        Path file = dir.resolve(sanitize(pose.getName()) + ".json");
        try {
            Files.writeString(file, GSON.toJson(pose));
        } catch (IOException e) {
            PlayerPoseClient.LOGGER.warn("Failed to save {}", file, e);
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
        String safe = sanitize(name);
        try {
            Files.deleteIfExists(dir.resolve(safe + ".json"));
            PoseLog.info("delete: '%s' (file %s.json)", name, safe);
        } catch (IOException e) {
            PlayerPoseClient.LOGGER.warn("Failed to delete {}", safe, e);
            PoseLog.warn("delete: failed for '%s' (%s)", name, e.getMessage());
        }
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

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_\\-]", "_");
    }
}
