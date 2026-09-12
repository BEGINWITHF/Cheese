package com.example.playerpose.pose;

import com.example.playerpose.PlayerPoseClient;
import net.fabricmc.loader.api.FabricLoader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Append-only log for the pose lifecycle, written next to the pose files:
 *
 *   config/player-pose/player-pose.log
 *
 * The game log mixes this mod in with everything else and rotates every launch. This
 * file holds only pose decisions - which pose was loaded, which got bound or unbound,
 * which one is actually applied - and survives restarts, so "which pose is in effect
 * and why" can be answered from one file.
 *
 * Every line is mirrored to the game log as well.
 */
public final class PoseLog {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final long MAX_BYTES = 512L * 1024L;

    private static Path file;
    private static boolean broken;

    private PoseLog() {}

    public static synchronized void info(String format, Object... args) {
        write("INFO", format, args);
    }

    public static synchronized void warn(String format, Object... args) {
        write("WARN", format, args);
    }

    private static void write(String level, String format, Object... args) {
        String line = "[" + LocalTime.now().format(TIME) + " " + level + "] " + String.format(format, args);
        PlayerPoseClient.LOGGER.info("[player-pose] {}", line);

        Path target = file();
        if (target == null) return;
        try {
            rotateIfHuge(target);
            Files.writeString(target, line + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            broken = true;
            PlayerPoseClient.LOGGER.warn("Cannot write the pose log", e);
        }
    }

    private static Path file() {
        if (broken) return null;
        if (file == null) {
            try {
                Path dir = FabricLoader.getInstance().getConfigDir().resolve("player-pose");
                Files.createDirectories(dir);
                file = dir.resolve("player-pose.log");
            } catch (Exception e) {
                broken = true;
                PlayerPoseClient.LOGGER.warn("Cannot open the pose log file", e);
                return null;
            }
        }
        return file;
    }

    /** Keep it small enough to attach: drop the oldest half once it gets big. */
    private static void rotateIfHuge(Path target) throws IOException {
        if (!Files.exists(target) || Files.size(target) < MAX_BYTES) return;
        List<String> lines = Files.readAllLines(target, StandardCharsets.UTF_8);
        int from = lines.size() / 2;
        StringBuilder sb = new StringBuilder("[---- older lines dropped, log rotated ----]\n");
        for (int i = from; i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        Files.writeString(target, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Every saved pose with its binding, plus what is currently active. */
    public static void dumpPoses(PoseManager mgr, String why) {
        if (mgr == null) {
            info("state (%s): no pose manager", why);
            return;
        }
        List<String> names = mgr.listNames();
        info("state (%s): %d saved poses", why, names.size());
        for (String name : names) {
            Pose p = mgr.get(name);
            if (p == null) continue;
            String skin = p.getSkin();
            info("    '%s' skin=%s bones=%d cursor=%s",
                name,
                skin == null || skin.isBlank() ? "none" : "'" + skin + "'",
                p.getBones() == null ? 0 : p.getBones().size(),
                p.hasCursor() ? "set" : "0");
        }
        String active = PlayerPoseClient.getActivePoseName();
        info("    active=%s posesEnabled=%s editorOpen=%s",
            active == null ? "none" : "'" + active + "'",
            PlayerPoseClient.arePosesEnabled(),
            PlayerPoseClient.isPoseEditorOpen());
    }
}
