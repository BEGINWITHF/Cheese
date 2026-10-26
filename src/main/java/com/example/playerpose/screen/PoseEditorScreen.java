package com.example.playerpose.screen;

import com.example.playerpose.PlayerPoseClient;
import com.example.playerpose.compat.PoseCompat;
import com.example.playerpose.pose.Pose;
import com.example.playerpose.pose.PoseBone;
import com.example.playerpose.pose.PoseLog;
import com.example.playerpose.pose.PoseManager;
import com.example.playerpose.skin.PreviewSkinManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.PlayerModelType;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Blender-style bone posture editor.
 *
 * The viewport renders the real local player model, with a rotate gizmo over it.
 *
 * The left bar selects what you are editing: body, head, the upper and lower arm and
 * leg segments, and the cape. Picking a part picks its joint - there is no separate
 * pivot to place, so rotations always hinge where the part is attached (for a forearm
 * or shin, the hinge line the splitter built).
 *
 * Two modes, like Blender's G and R:
 *   ROTATE - drag a ring to rotate the selected part.
 *   MOVE   - drag a ring to translate the selected part along that axis.
 *
 * G / R switch mode, 1-9 pick a row from the left bar, drag empty viewport space to
 * orbit, middle-drag orbits, scroll to zoom. The last two rows are click-only.
 *
 * Rotations are radians in PoseBone; translations are model units (16 per block),
 * which is ModelPart's own space. The mixin applies both.
 */
public class PoseEditorScreen extends Screen {

    private static final int LEFT_X = 8;
    private static final int LEFT_W = 160;
    private static final int ROW_H = 14;
    private static final int CTRL_H = 18;
    private static final int BTN_GAP = 4;
    private static final int BTN_W = (LEFT_W - BTN_GAP) / 2;
    private static final int BTN_Y = 28;
    private static final int BTN_ROW_H = 20;
    private static final int STATUS_H = 18;
    private static final double MAX_ANGLE = Math.PI;
    private static final double MAX_TRANSLATE = 32.0; // model units, 2 blocks

    /** Top viewport info strip (selected bone, mode, axis read-out). */
    private static final int INFO_Y = 6;
    private static final int INFO_H = 22;
    private static final int TOP_H = 34;

    private static final int TAB_Y = 92;
    private static final int TAB_H = 16;
    private static final int TAB_GAP = 3;
    private static final int LIST_Y = 112;

    private static final List<String> ROWS = List.of(
        "body", "head",
        "leftArm", "leftForearm", "rightArm", "rightForearm",
        "leftLeg", "leftShin", "rightLeg", "rightShin",
        "cape"
    );

    /**
     * Canonical pivots (model units, 16 per block, +Y down), used only before the
     * live values have been published by the mixins. The second segments sit 6 units
     * down their limb; the cape pivot comes from PlayerCapeModel.
     */
    private static final float[][] FALLBACK_PIVOTS = {
        {0.0F, 0.0F, 0.0F},    // body
        {0.0F, 0.0F, 0.0F},    // head
        {5.0F, 2.0F, 0.0F},    // leftArm
        {6.0F, 6.0F, 0.0F},    // leftForearm (elbow centre)
        {-5.0F, 2.0F, 0.0F},   // rightArm
        {-6.0F, 6.0F, 0.0F},   // rightForearm (elbow centre)
        {1.9F, 12.0F, 0.0F},   // leftLeg
        {1.9F, 18.0F, 0.0F},   // leftShin (knee centre)
        {-1.9F, 12.0F, 0.0F},  // rightLeg
        {-1.9F, 18.0F, 0.0F},  // rightShin (knee centre)
        {0.0F, 0.0F, 2.0F}     // cape
    };

    private final PoseManager poseManager;
    private final String skin;
    private Pose workingPose;
    private String selected = "body";
    private boolean moveMode;

    // Working values for the selected bone
    private double rotX, rotY, rotZ;            // radians
    private double tX, tY, tZ;                  // model units

    /** Saved name of the working pose, used to detect a rename on Save. */
    private String originalName;
    /** False until New or a saved pose is picked: the panel opens on a clean home view. */
    private boolean editingPose;

    /** Simple in-screen rename/new-name dialog (no direct typing in the name bar). */
    private boolean nameDialogOpen;
    private String nameDialogText = "";
    private boolean nameDialogForNew;
    private int nameDialogX0, nameDialogY0, nameDialogX1, nameDialogY1;
    private int nameDialogOkX0, nameDialogOkY0, nameDialogOkX1, nameDialogOkY1;
    private int nameDialogCancelX0, nameDialogCancelY0, nameDialogCancelX1, nameDialogCancelY1;

    /** Editor-only skin preview (never changes the real player's skin). */
    private boolean skinPanelOpen;
    private EditBox skinInputBox;
    /** false = the field is a player name, true = a local file path. */
    private boolean skinUseFile;
    private Button skinPlayerBtn;
    private Button skinFileBtn;
    private int skinModeGroupX0, skinModeGroupY0, skinModeGroupX1, skinModeGroupY1;
    private PlayerModelType skinModel = PlayerModelType.WIDE;
    private String skinStatus = "";
    private Rect skinButtonRect;
    private Rect skinPanelRect;
    private Rect skinBrowseRect;
    private Rect skinLoadRect;
    private Rect skinModelRect;
    private Rect skinOriginalRect;
    private Rect skinCloseRect;

    private record Rect(int x0, int y0, int x1, int y1) {
        boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    private Button newBtn;
    private Button saveBtn;
    private Button resetBtn;
    private Button resetAllBtn;
    private Button deleteBtn;
    private Button unloadBtn;
    private Button rotateBtn;
    private Button moveBtn;
    /** Bounds of the border drawn around the two mode buttons. */
    private int modeGroupX0, modeGroupY0, modeGroupX1, modeGroupY1;

    private String statusMsg = "";
    private long statusUntil;
    /** Detected mods/resource packs that can override the player rig. */
    private List<Component> compatWarnings = new ArrayList<>();
    private boolean compatLogged;
    private int compatX0, compatY0, compatX1, compatY1;
    private List<String> savedPoseNames = new ArrayList<>();
    /** 0 = bone list, 1 = saved-pose list. */
    private int listTab;
    /** Scroll offset of the active tab's list, in rows. */
    private int listScroll;

    // Load-to popup
    private static final int LOAD_SELF = 0;
    private static final int LOAD_PLAYER = 1;
    private static final int LOAD_MENU_ROW_H = 12;
    private static final int LOAD_MENU_TITLE_H = 14;
    private static final int LOAD_MENU_FOOTER_H = 20;

    private record LoadTarget(int kind, UUID id, String name) {}

    private boolean loadMenuOpen;
    private int loadMenuScroll;
    private String loadPoseName;
    private Pose loadPose;
    private List<LoadTarget> loadTargets = new ArrayList<>();
    private int loadMenuX0, loadMenuY0, loadMenuX1, loadMenuY1;
    private int loadMenuTargetsY0;
    private int loadMenuVisibleRows;
    private int loadMenuAllY0, loadMenuAllY1;
    private int loadMenuCloseX0, loadMenuCloseY0, loadMenuCloseX1, loadMenuCloseY1;

    /** Clicking empty space in the Bones panel hides the selection frame and gizmo. */
    private boolean gizmoVisible = true;
    private boolean selectionVisible = true;

    // Viewport / camera
    private int vpX0, vpY0, vpX1, vpY1;
    private double camYaw = 20.0;
    private double camPitch = 10.0;
    private double camZoom = 1.0;

    // Interaction state
    private int activeAxis = PoseGizmo.NONE;
    private double dragStartAngle;
    private double dragStartMouseX, dragStartMouseY;
    private double dragStartValue;
    private boolean orbiting;

    // ---- undo / redo ------------------------------------------------------
    private static final int MAX_UNDO = 64;

    /** A pose plus which row was being edited when it was taken. */
    private record Snapshot(Pose pose, String selected, String deleteOnRestore) {}

    private final java.util.Deque<Snapshot> undoStack = new java.util.ArrayDeque<>();
    private final java.util.Deque<Snapshot> redoStack = new java.util.ArrayDeque<>();
    /** Snapshot taken when a ring drag starts, committed only if the drag changed something. */
    private Snapshot dragSnapshot;
    /**
     * Pose that was just deleted, waiting for the next snapshot. That snapshot is the
     * post-delete blank state; restoring it (redo) has to delete the pose again.
     */
    private String lastDeletedName;

    public PoseEditorScreen(PoseManager mgr, Pose existing, String skinStr) {
        super(Component.translatable("screen.playerpose.editor"));
        this.poseManager = mgr;
        this.skin = skinStr;
        // Opening the panel is a clean home view: nothing is selected, so no pose
        // can be edited or renamed by accident. New (or picking a saved pose) starts
        // editing. The Poses tab is shown by default.
        this.listTab = 1;
        if (existing != null) {
            this.workingPose = PoseManager.copy(existing);
            this.editingPose = true;
            this.originalName = existing.getName();
        } else {
            this.workingPose = new Pose();
            this.workingPose.setName("");
            this.workingPose.setSkin(skinStr);
            this.editingPose = false;
            // No pose is being edited yet: no selection frame and no gizmo.
            this.selectionVisible = false;
            this.gizmoVisible = false;
        }
        syncFromPose();
    }

    // ---- pose <-> editor state -------------------------------------------

    private void syncFromPose() {
        PoseBone bone = findBone(selected);
        if (bone != null) {
            rotX = bone.x; rotY = bone.y; rotZ = bone.z;
            tX = bone.tx; tY = bone.ty; tZ = bone.tz;
        } else {
            rotX = 0; rotY = 0; rotZ = 0;
            tX = 0; tY = 0; tZ = 0;
        }
    }

    /** Write the working values for the selected row back into the pose. */
    private void commit() {
        if (workingPose == null) return;
        if (workingPose.getBones() == null) workingPose.setBones(new ArrayList<>());
        List<PoseBone> bones = workingPose.getBones();
        for (int i = 0; i < bones.size(); i++) {
            if (selected.equals(bones.get(i).bone)) {
                bones.set(i, new PoseBone(selected, rotX, rotY, rotZ, tX, tY, tZ));
                return;
            }
        }
        bones.add(new PoseBone(selected, rotX, rotY, rotZ, tX, tY, tZ));
    }

    private PoseBone findBone(String name) {
        if (workingPose == null || workingPose.getBones() == null) return null;
        for (PoseBone b : workingPose.getBones()) {
            if (b != null && name.equals(b.bone)) return b;
        }
        return null;
    }

    private void selectRow(String row) {
        setTab(0);
        selected = row;
        selectionVisible = true;
        gizmoVisible = true;
        syncFromPose();
        ensureRowVisible(ROWS.indexOf(row));
    }

    private double rotationFor(int axis) {
        return switch (axis) {
            case PoseGizmo.AXIS_X -> rotX;
            case PoseGizmo.AXIS_Y -> rotY;
            default -> rotZ;
        };
    }

    private void setRotationFor(int axis, double radians) {
        double v = Math.max(-MAX_ANGLE, Math.min(MAX_ANGLE, radians));
        switch (axis) {
            case PoseGizmo.AXIS_X -> rotX = v;
            case PoseGizmo.AXIS_Y -> rotY = v;
            default -> rotZ = v;
        }
        commit();
    }

    private double translationFor(int axis) {
        return switch (axis) {
            case PoseGizmo.AXIS_X -> tX;
            case PoseGizmo.AXIS_Y -> tY;
            default -> tZ;
        };
    }

    private void setTranslationFor(int axis, double units) {
        double v = Math.max(-MAX_TRANSLATE, Math.min(MAX_TRANSLATE, units));
        switch (axis) {
            case PoseGizmo.AXIS_X -> tX = v;
            case PoseGizmo.AXIS_Y -> tY = v;
            default -> tZ = v;
        }
        commit();
    }

    private void refreshPoseList() {
        savedPoseNames = poseManager == null ? new ArrayList<>() : poseManager.listNames();
        listScroll = Math.max(0, Math.min(listScroll, maxListScroll()));
        boolean hasWorkingPose = workingPose != null && workingPose.getName() != null
            && !workingPose.getName().isBlank();
        if (deleteBtn != null) deleteBtn.active = hasWorkingPose;
    }

    /** Scroll the Pose tab so the pose being edited is on screen. */
    private void ensureWorkingPoseVisible() {
        if (listTab != 1) return;
        if (workingPose == null || workingPose.getName() == null) return;
        int idx = savedPoseNames.indexOf(workingPose.getName());
        if (idx >= 0) ensureRowVisible(idx);
    }

    /** Scroll the list so a row is visible, clamped to the list bounds. */
    private void ensureRowVisible(int row) {
        int window = listWindow();
        if (window <= 0) return;
        if (row < listScroll) {
            listScroll = row;
        } else if (row >= listScroll + window) {
            listScroll = row - window + 1;
        }
        listScroll = Math.max(0, Math.min(listScroll, maxListScroll()));
    }

    /**
     * Pick a pose to edit. This only moves the selection (amber frame) - the pose that
     * is currently loaded (blue shading) keeps rendering until Load or Unload is used.
     */
    private void selectPose(String name) {
        Pose p = poseManager.get(name);
        if (p == null) return;
        beginEdit();
        // The editor works on a copy, so later edits stay drafts until Save.
        workingPose = PoseManager.copy(p);
        PlayerPoseClient.setEditorPreviewPose(workingPose);
        editingPose = true;
        originalName = p.getName();
        selected = "body";
        syncFromPose();
        refreshPoseList();
        setTab(1);
        ensureWorkingPoseVisible();
        updateActionButtons();
        setStatus(Component.translatable("playerpose.status.selected", name));
        String active = PlayerPoseClient.getActivePoseName();
        PoseLog.info("list: selected '%s' (editing only; loaded=%s)", name,
            active == null ? "none" : "'" + active + "'");
    }

    /** Open the target chooser; Load itself no longer assumes the local player. */
    /** Open the multi-target panel for one saved pose. */
    private void openLoadMenu(String poseName) {
        Pose pose = poseManager == null ? null : poseManager.get(poseName);
        if (pose == null) return;

        List<LoadTarget> targets = new ArrayList<>();
        targets.add(new LoadTarget(LOAD_SELF, null, ""));

        Minecraft mc = Minecraft.getInstance();
        UUID self = mc.player == null ? null : mc.player.getUUID();
        if (mc.getConnection() != null) {
            List<LoadTarget> players = new ArrayList<>();
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                if (info == null || info.getProfile() == null
                    || info.getProfile().id() == null) continue;
                UUID id = info.getProfile().id();
                if (id.equals(self)) continue;
                String name = info.getProfile().name();
                if (name == null || name.isBlank()) name = id.toString();
                players.add(new LoadTarget(LOAD_PLAYER, id, name));
            }
            players.sort(Comparator.comparing(LoadTarget::name, String.CASE_INSENSITIVE_ORDER));
            targets.addAll(players);
        }

        loadPoseName = poseName;
        loadPose = PoseManager.copy(pose);
        loadTargets = targets;
        loadMenuScroll = 0;
        loadMenuOpen = true;
    }

    private String targetLabel(LoadTarget target) {
        return target.kind == LOAD_SELF
            ? Component.translatable("playerpose.loadTarget.self").getString()
            : target.name;
    }

    private boolean targetHasPose(LoadTarget target) {
        if (loadPoseName == null) return false;
        return target.kind == LOAD_SELF
            ? PlayerPoseClient.hasLocalPose(loadPoseName)
            : PlayerPoseClient.hasRemotePose(target.id, loadPoseName);
    }

    /** Pose a target currently carries, whichever pose it is, or null. */
    private String targetPoseName(LoadTarget target) {
        return target.kind == LOAD_SELF
            ? (PlayerPoseClient.isLocalPoseCleared() ? null : PlayerPoseClient.getActivePoseName())
            : PlayerPoseClient.getRemotePoseName(target.id);
    }

    private void setTargetPose(LoadTarget target, boolean enabled) {
        if (target == null || loadPose == null || loadPoseName == null) return;
        if (target.kind == LOAD_SELF) {
            if (enabled) {
                PlayerPoseClient.applyLocalPose(loadPoseName);
            } else if (PlayerPoseClient.hasLocalPose(loadPoseName)) {
                PlayerPoseClient.clearLocalPose();
            }
            return;
        }

        if (enabled) {
            PlayerPoseClient.applyRemotePose(target.id, loadPose);
        } else {
            PlayerPoseClient.clearRemotePose(target.id);
        }
    }

    private void toggleTarget(LoadTarget target) {
        boolean enabling = !targetHasPose(target);
        String previous = targetPoseName(target);
        setTargetPose(target, enabling);
        if (enabling && previous != null && !previous.equals(loadPoseName)) {
            // One pose per player: checking this pose replaces whatever was there.
            setStatus(Component.translatable("playerpose.status.poseReplaced",
                targetLabel(target), previous, loadPoseName));
        }
        if (!PlayerPoseClient.arePosesEnabled()) {
            PlayerPoseClient.setPosesEnabled(true);
        }
    }

    private boolean allTargetsHavePose() {
        if (loadTargets.isEmpty()) return false;
        for (LoadTarget target : loadTargets) {
            if (!targetHasPose(target)) return false;
        }
        return true;
    }

    private void toggleAllTargets() {
        boolean enable = !allTargetsHavePose();
        for (LoadTarget target : loadTargets) {
            setTargetPose(target, enable);
        }
        if (!PlayerPoseClient.arePosesEnabled()) {
            PlayerPoseClient.setPosesEnabled(true);
        }
    }

    private int loadMenuMaxScroll() {
        return Math.max(0, loadTargets.size() - loadMenuVisibleRows);
    }

    /** Target row under the cursor, as an index into loadTargets, or -1. */
    private int loadMenuRowAt(int mouseX, int mouseY) {
        if (!loadMenuOpen || loadMenuVisibleRows <= 0) return -1;
        if (mouseX < loadMenuX0 || mouseX >= loadMenuX1
            || mouseY < loadMenuTargetsY0 || mouseY >= loadMenuTargetsY0
                + loadMenuVisibleRows * LOAD_MENU_ROW_H) return -1;
        int row = (mouseY - loadMenuTargetsY0) / LOAD_MENU_ROW_H;
        if (row < 0 || row >= loadMenuVisibleRows) return -1;
        int index = loadMenuScroll + row;
        return index >= 0 && index < loadTargets.size() ? index : -1;
    }

    private void drawLoadMenu(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        if (!loadMenuOpen) return;

        int x0 = LEFT_X;
        int x1 = LEFT_X + LEFT_W;
        int anchorY = BTN_Y + BTN_ROW_H * 2 + CTRL_H + 4;
        int maxRows = Math.max(1, Math.min(8,
            (height - STATUS_H - 4 - anchorY - LOAD_MENU_TITLE_H
                - LOAD_MENU_ROW_H - LOAD_MENU_FOOTER_H) / LOAD_MENU_ROW_H));
        int rows = Math.min(maxRows, loadTargets.size());
        int h = LOAD_MENU_TITLE_H + LOAD_MENU_ROW_H + rows * LOAD_MENU_ROW_H
            + LOAD_MENU_FOOTER_H + 2;
        int y0 = Math.min(anchorY, height - STATUS_H - 4 - h);
        y0 = Math.max(4, y0);
        int y1 = y0 + h;

        loadMenuX0 = x0;
        loadMenuX1 = x1;
        loadMenuY0 = y0;
        loadMenuY1 = y1;
        loadMenuVisibleRows = rows;
        loadMenuScroll = Math.max(0, Math.min(loadMenuScroll, loadMenuMaxScroll()));

        graphics.fill(x0, y0, x1, y1, 0xF0181A22);
        graphics.fill(x0, y0, x1, y0 + 1, 0xFF8891A0);
        graphics.fill(x0, y1 - 1, x1, y1, 0xFF8891A0);
        graphics.fill(x0, y0, x0 + 1, y1, 0xFF8891A0);
        graphics.fill(x1 - 1, y0, x1, y1, 0xFF8891A0);

        String title = "Load \"" + loadPoseName + "\" to";
        graphics.text(font,
            Component.literal(font.plainSubstrByWidth(title, LEFT_W - 8)),
            x0 + 4, y0 + 3, 0xFFFFD48A);

        // Fixed "select all" row. Its label flips to "Deselect all" once every
        // target currently has this pose.
        boolean all = allTargetsHavePose();
        loadMenuAllY0 = y0 + LOAD_MENU_TITLE_H;
        loadMenuAllY1 = loadMenuAllY0 + LOAD_MENU_ROW_H;
        boolean allHover = isOver(mouseX, mouseY, x0 + 1, loadMenuAllY0,
            x1 - x0 - 2, LOAD_MENU_ROW_H);
        if (allHover) {
            graphics.fill(x0 + 1, loadMenuAllY0, x1 - 1, loadMenuAllY1, 0x33FFD48A);
        }
        String allMarker = all ? "[x] " : "[ ] ";
        graphics.text(font, Component.literal(allMarker),
            x0 + 4, loadMenuAllY0 + 2, all ? 0xFF7FD46A : 0xFF8891A0);
        graphics.text(font, Component.translatable(all
                ? "playerpose.loadTarget.deselectAll"
                : "playerpose.loadTarget.selectAll"),
            x0 + 5 + font.width("[x] "), loadMenuAllY0 + 2, 0xFFFFD48A);

        loadMenuTargetsY0 = loadMenuAllY1;
        for (int row = 0; row < rows; row++) {
            int index = loadMenuScroll + row;
            if (index >= loadTargets.size()) break;
            LoadTarget target = loadTargets.get(index);
            int ry = loadMenuTargetsY0 + row * LOAD_MENU_ROW_H;
            boolean selected = targetHasPose(target);
            // A player carries at most one pose. If this target already has a
            // different pose, show it here so this panel never looks free.
            String otherPose = selected ? null : targetPoseName(target);
            boolean hover = isOver(mouseX, mouseY, x0 + 1, ry, x1 - x0 - 2,
                LOAD_MENU_ROW_H);
            if (hover) {
                graphics.fill(x0 + 1, ry, x1 - 1, ry + LOAD_MENU_ROW_H, 0x22FFD48A);
            }
            int color = otherPose != null ? 0xFFE0A24A
                : target.kind == LOAD_SELF ? 0xFFB8E0FF : 0xFFE0E0E0;
            String marker = selected ? "[x] " : otherPose != null ? "[~] " : "[ ] ";
            int markerX = x0 + 4;
            // Fixed width for all markers so names do not jitter when toggling.
            int labelX = markerX + font.width("[x] ") + 1;
            graphics.text(font, Component.literal(marker), markerX, ry + 2,
                selected ? 0xFF7FD46A : otherPose != null ? 0xFFE0A24A : 0xFF8891A0);

            // Keep the player name and the "already has another pose" suffix from
            // overlapping: measure the marker first, then split the remaining width.
            int labelMax = Math.max(8, x1 - 6 - labelX);
            String suffix = otherPose == null ? "" : " (" + otherPose + ")";
            int suffixMax = labelMax / 2;
            if (font.width(suffix) > suffixMax) {
                suffix = font.plainSubstrByWidth(suffix, suffixMax);
            }
            String label = font.plainSubstrByWidth(targetLabel(target),
                Math.max(8, labelMax - font.width(suffix)));
            graphics.text(font, Component.literal(label), labelX, ry + 2, color);
            if (!suffix.isEmpty()) {
                graphics.text(font, Component.literal(suffix),
                    labelX + font.width(label), ry + 2, 0xFF9A7A4A);
            }
        }

        if (loadTargets.size() > rows) {
            int trackX = x1 - 4;
            int trackTop = loadMenuTargetsY0;
            int trackH = rows * LOAD_MENU_ROW_H;
            graphics.fill(trackX, trackTop, trackX + 2, trackTop + trackH, 0x40FFFFFF);
            int thumbH = Math.max(6, trackH * rows / Math.max(1, loadTargets.size()));
            int thumbY = trackTop + (trackH - thumbH)
                * loadMenuScroll / Math.max(1, loadMenuMaxScroll());
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xCCFFFFFF);
        }

        int footerTop = loadMenuTargetsY0 + rows * LOAD_MENU_ROW_H + 2;
        loadMenuCloseX0 = x0 + 4;
        loadMenuCloseY0 = footerTop;
        loadMenuCloseX1 = x1 - 4;
        loadMenuCloseY1 = footerTop + LOAD_MENU_FOOTER_H - 4;
        boolean closeHover = isOver(mouseX, mouseY, loadMenuCloseX0, loadMenuCloseY0,
            loadMenuCloseX1 - loadMenuCloseX0, loadMenuCloseY1 - loadMenuCloseY0);
        graphics.fill(loadMenuCloseX0, loadMenuCloseY0, loadMenuCloseX1, loadMenuCloseY1,
            closeHover ? 0xCC555566 : 0xAA333344);
        Component closeLabel = Component.translatable("gui.done");
        graphics.text(font, closeLabel,
            loadMenuCloseX0 + ((loadMenuCloseX1 - loadMenuCloseX0)
                - font.width(closeLabel)) / 2,
            loadMenuCloseY0 + 4, 0xFFE0E0E0);
    }

    // ---- name bar / rename dialog ---------------------------------------

    /** Read-only pose-name bar. Clicking it opens the rename dialog. */
    private void drawNameBar(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        int x0 = LEFT_X;
        int y0 = 6;
        int x1 = LEFT_X + LEFT_W;
        int y1 = y0 + CTRL_H;
        boolean hover = editingPose && isOver(mouseX, mouseY, x0, y0, x1 - x0, CTRL_H);
        int edge = hover ? 0xFFFFC24A : 0xFF8891A0;
        graphics.fill(x0, y0, x1, y1, hover ? 0xFF2A2E3A : 0xFF12141B);
        graphics.fill(x0, y0, x1, y0 + 1, edge);
        graphics.fill(x0, y1 - 1, x1, y1, edge);
        graphics.fill(x0, y0, x0 + 1, y1, edge);
        graphics.fill(x1 - 1, y0, x1, y1, edge);

        String name = workingPose == null || workingPose.getName() == null
            ? "" : workingPose.getName();
        boolean hasPose = editingPose && !name.isBlank();
        Component hint = Component.translatable("playerpose.renameHint");
        int hintW = hasPose ? font.width(hint) + 4 : 0;
        String shown = hasPose ? name
            : Component.translatable("playerpose.noPoseSelected").getString();
        shown = font.plainSubstrByWidth(shown, LEFT_W - 12 - hintW);
        graphics.text(font, Component.literal(shown), x0 + 5, y0 + 5,
            hasPose ? 0xFFFFFFFF : 0xFF8891A0);
        if (hasPose) {
            graphics.text(font, hint, x1 - 4 - font.width(hint), y0 + 5,
                hover ? 0xFFFFD48A : 0xFF7A8090);
        }
    }

    /** Small modal for New and Rename: type here, never in the name bar. */
    private void drawNameDialog(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        if (!nameDialogOpen) return;
        nameDialogLayout();

        graphics.fill(0, 0, width, height, 0x99000000);
        graphics.fill(nameDialogX0, nameDialogY0, nameDialogX1, nameDialogY1, 0xFF181A22);
        graphics.fill(nameDialogX0, nameDialogY0, nameDialogX1, nameDialogY0 + 1, 0xFF8891A0);
        graphics.fill(nameDialogX0, nameDialogY1 - 1, nameDialogX1, nameDialogY1, 0xFF8891A0);
        graphics.fill(nameDialogX0, nameDialogY0, nameDialogX0 + 1, nameDialogY1, 0xFF8891A0);
        graphics.fill(nameDialogX1 - 1, nameDialogY0, nameDialogX1, nameDialogY1, 0xFF8891A0);

        Component title = Component.translatable(nameDialogForNew
            ? "playerpose.nameDialog.newTitle" : "playerpose.nameDialog.renameTitle");
        graphics.text(font, title, nameDialogX0 + 10, nameDialogY0 + 8, 0xFFFFD48A);

        int bx0 = nameDialogX0 + 10;
        int by0 = nameDialogY0 + 24;
        int bx1 = nameDialogX1 - 10;
        int by1 = by0 + 20;
        graphics.fill(bx0, by0, bx1, by1, 0xFF0A0B0F);
        graphics.fill(bx0, by0, bx1, by0 + 1, 0xFF8891A0);
        graphics.fill(bx0, by1 - 1, bx1, by1, 0xFF8891A0);
        graphics.fill(bx0, by0, bx0 + 1, by1, 0xFF8891A0);
        graphics.fill(bx1 - 1, by0, bx1, by1, 0xFF8891A0);
        String shown = font.plainSubstrByWidth(nameDialogText, bx1 - bx0 - 8);
        graphics.text(font, Component.literal(shown), bx0 + 4, by0 + 6, 0xFFFFFFFF);
        if ((System.currentTimeMillis() / 500L) % 2L == 0L) {
            int cx = bx0 + 4 + font.width(shown);
            graphics.fill(cx, by0 + 4, cx + 1, by1 - 4, 0xFFFFFFFF);
        }

        boolean cancelHover = isOver(mouseX, mouseY, nameDialogCancelX0, nameDialogCancelY0,
            nameDialogCancelX1 - nameDialogCancelX0, CTRL_H);
        boolean okHover = isOver(mouseX, mouseY, nameDialogOkX0, nameDialogOkY0,
            nameDialogOkX1 - nameDialogOkX0, CTRL_H);
        graphics.fill(nameDialogCancelX0, nameDialogCancelY0, nameDialogCancelX1,
            nameDialogCancelY1, cancelHover ? 0xCC555566 : 0xAA333344);
        graphics.fill(nameDialogOkX0, nameDialogOkY0, nameDialogOkX1, nameDialogOkY1,
            okHover ? 0xCC4F9A52 : 0xAA356B38);
        Component cancel = Component.translatable("gui.cancel");
        Component ok = Component.translatable("gui.ok");
        graphics.text(font, cancel,
            nameDialogCancelX0 + ((nameDialogCancelX1 - nameDialogCancelX0)
                - font.width(cancel)) / 2,
            nameDialogCancelY0 + 4, 0xFFE0E0E0);
        graphics.text(font, ok,
            nameDialogOkX0 + ((nameDialogOkX1 - nameDialogOkX0)
                - font.width(ok)) / 2,
            nameDialogOkY0 + 4, 0xFFFFFFFF);
    }

    // ---- preview skin ----------------------------------------------------

    /** Small "Skin" button in the top-right corner of the viewport. */
    private void drawSkinButton(GuiGraphicsExtractor graphics, Font font,
                                int mouseX, int mouseY) {
        int w = 54;
        int h = 18;
        int x = Math.max(vpX0 + 4, vpX1 - w - 6);
        int y = vpY0 + 6;
        boolean hover = isOver(mouseX, mouseY, x, y, w, h);
        graphics.fill(x, y, x + w, y + h, 0xCC14161E);
        int edge = hover ? 0xFFFFC24A : 0xFF8891A0;
        graphics.fill(x, y, x + w, y + 1, edge);
        graphics.fill(x, y + h - 1, x + w, y + h, edge);
        graphics.fill(x, y, x + 1, y + h, edge);
        graphics.fill(x + w - 1, y, x + w, y + h, edge);
        Component label = Component.translatable("playerpose.skin.button");
        graphics.text(font, label, x + (w - font.width(label)) / 2, y + 5,
            hover ? 0xFFFFD48A : 0xFFE0E0E0);
        skinButtonRect = new Rect(x, y, x + w, y + h);
    }

    private void layoutSkinPanel() {
        int w = 360;
        int h = 172;
        int x = Math.max(8, vpX1 - w - 8);
        int y = vpY0 + 28;
        if (y + h > height - STATUS_H - 4) {
            y = Math.max(4, height - STATUS_H - 4 - h);
        }
        if (x + w > width - 4) x = Math.max(4, width - w - 4);

        skinPanelRect = new Rect(x, y, x + w, y + h);
        if (skinInputBox != null) {
            skinInputBox.setX(x + 140);
            skinInputBox.setY(y + 28);
            skinInputBox.setWidth(208);
            skinInputBox.setHeight(CTRL_H);
        }
        if (skinPlayerBtn != null) {
            skinPlayerBtn.setX(x + 14);
            skinPlayerBtn.setY(y + 28);
            skinPlayerBtn.setWidth(56);
            skinPlayerBtn.setHeight(CTRL_H);
        }
        if (skinFileBtn != null) {
            skinFileBtn.setX(x + 72);
            skinFileBtn.setY(y + 28);
            skinFileBtn.setWidth(56);
            skinFileBtn.setHeight(CTRL_H);
        }
        skinModeGroupX0 = x + 12;
        skinModeGroupY0 = y + 26;
        skinModeGroupX1 = x + 130;
        skinModeGroupY1 = y + 48;
        skinBrowseRect = new Rect(x + 12, y + 58, x + 98, y + 78);
        skinLoadRect = new Rect(x + 104, y + 58, x + 190, y + 78);
        skinModelRect = new Rect(x + 196, y + 58, x + 348, y + 78);
        skinOriginalRect = new Rect(x + 12, y + 140, x + 130, y + 160);
        skinCloseRect = new Rect(x + 270, y + 140, x + 348, y + 160);
    }

    private void drawSkinPanel(GuiGraphicsExtractor graphics, Font font,
                               int mouseX, int mouseY, float partialTick) {
        if (!skinPanelOpen) return;
        layoutSkinPanel();
        Rect p = skinPanelRect;
        graphics.fill(p.x0, p.y0, p.x1, p.y1, 0xFF181A22);
        int edge = 0xFF8891A0;
        graphics.fill(p.x0, p.y0, p.x1, p.y0 + 1, edge);
        graphics.fill(p.x0, p.y1 - 1, p.x1, p.y1, edge);
        graphics.fill(p.x0, p.y0, p.x0 + 1, p.y1, edge);
        graphics.fill(p.x1 - 1, p.y0, p.x1, p.y1, edge);

        graphics.text(font, Component.translatable("playerpose.skin.title"),
            p.x0 + 12, p.y0 + 8, 0xFFFFD48A);

        drawSkinAction(graphics, font, skinBrowseRect,
            Component.translatable("playerpose.skin.browse"), false, mouseX, mouseY);
        drawSkinAction(graphics, font, skinLoadRect,
            Component.translatable("playerpose.skin.load"), true, mouseX, mouseY);
        drawSkinAction(graphics, font, skinModelRect,
            Component.translatable(skinModel == PlayerModelType.SLIM
                ? "playerpose.skin.armsSlim" : "playerpose.skin.armsClassic"),
            false, mouseX, mouseY);
        drawSkinAction(graphics, font, skinOriginalRect,
            Component.translatable("playerpose.skin.original"), false, mouseX, mouseY);
        drawSkinAction(graphics, font, skinCloseRect,
            Component.translatable("playerpose.skin.close"), false, mouseX, mouseY);

        String status = skinStatus;
        if (PreviewSkinManager.loading()) {
            status = Component.translatable("playerpose.skin.loading").getString();
        }
        if (status == null || status.isBlank()) {
            status = skinModeHint();
        }
        int thumbSize = 48;
        int thumbX = p.x1 - thumbSize - 12;
        int thumbY = p.y0 + 84;
        int maxW = p.x1 - p.x0 - 24 - thumbSize - 10;
        int color = status.startsWith("Could") || status.startsWith("Skin must")
            ? 0xFFFF9A9A : 0xFFAEB6C8;
        List<String> lines = wrapText(font, status, maxW, 3);
        for (int line = 0; line < lines.size(); line++) {
            graphics.text(font, Component.literal(lines.get(line)), p.x0 + 12,
                p.y0 + 88 + line * 12, color);
        }

        // Face thumbnail so the selected skin is visible while the panel is open.
        graphics.fill(thumbX - 1, thumbY - 1, thumbX + thumbSize + 1,
            thumbY + thumbSize + 1, 0xFF667080);
        graphics.fill(thumbX, thumbY, thumbX + thumbSize, thumbY + thumbSize,
            0xFF0B0C10);
        var face = PreviewSkinManager.pixels();
        if (face != null && !face.isClosed()) {
            drawSkinFace(graphics, face, thumbX, thumbY, thumbSize);
        } else {
            Component none = Component.translatable("playerpose.skin.noPreview");
            graphics.text(font, none,
                thumbX + (thumbSize - font.width(none)) / 2, thumbY + 20,
                0xFF7A8090);
        }

        // Widgets draw before this panel; render the controls again on top so the
        // modal never sits underneath the left-panel buttons.
        if (skinPlayerBtn != null && skinPlayerBtn.visible) {
            skinPlayerBtn.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
        if (skinFileBtn != null && skinFileBtn.visible) {
            skinFileBtn.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
        drawSkinModeGroup(graphics);
        if (skinInputBox != null && skinInputBox.visible) {
            skinInputBox.extractRenderState(graphics, mouseX, mouseY, partialTick);
        }
    }

    /** Frame around the Player/File pair plus an amber bar on the active one. */
    private void drawSkinModeGroup(GuiGraphicsExtractor graphics) {
        if (!skinPanelOpen || skinPlayerBtn == null || skinFileBtn == null) return;
        graphics.fill(skinModeGroupX0, skinModeGroupY0, skinModeGroupX1,
            skinModeGroupY0 + 1, 0xFFAAB4D4);
        graphics.fill(skinModeGroupX0, skinModeGroupY1 - 1, skinModeGroupX1,
            skinModeGroupY1, 0xFFAAB4D4);
        graphics.fill(skinModeGroupX0, skinModeGroupY0, skinModeGroupX0 + 1,
            skinModeGroupY1, 0xFFAAB4D4);
        graphics.fill(skinModeGroupX1 - 1, skinModeGroupY0, skinModeGroupX1,
            skinModeGroupY1, 0xFFAAB4D4);

        Button active = skinUseFile ? skinFileBtn : skinPlayerBtn;
        int x = active.getX();
        int y = active.getY();
        int w = active.getWidth();
        int h = active.getHeight();
        graphics.fill(x, y, x + w, y + h, 0x33FFD48A);
        graphics.fill(x + 1, y + h - 2, x + w - 1, y + h, 0xFFFFC24A);
    }

    private void drawSkinFace(GuiGraphicsExtractor graphics, NativeImage image,
                              int x, int y, int size) {
        int cell = Math.max(1, size / 8);
        for (int py = 0; py < 8; py++) {
            for (int px = 0; px < 8; px++) {
                int color = image.getPixel(8 + px, 8 + py);
                if ((color >>> 24) != 0) {
                    graphics.fill(x + px * cell, y + py * cell,
                        x + (px + 1) * cell, y + (py + 1) * cell, color);
                }
            }
        }
        // Hat/outer head layer.
        for (int py = 0; py < 8; py++) {
            for (int px = 0; px < 8; px++) {
                int color = image.getPixel(40 + px, 8 + py);
                if (((color >>> 24) & 0xFF) > 8) {
                    graphics.fill(x + px * cell, y + py * cell,
                        x + (px + 1) * cell, y + (py + 1) * cell, color);
                }
            }
        }
    }

    private static List<String> wrapText(Font font, String text, int maxWidth, int maxLines) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            if (font.width(word) > maxWidth) {
                word = font.plainSubstrByWidth(word, maxWidth);
            }
            String candidate = current.length() == 0
                ? word : current + " " + word;
            if (font.width(candidate) <= maxWidth) {
                if (current.length() > 0) current.append(' ');
                current.append(word);
            } else {
                if (current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                    if (lines.size() >= maxLines) break;
                }
                current.append(word);
            }
        }
        if (lines.size() < maxLines && current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private void drawSkinAction(GuiGraphicsExtractor graphics, Font font, Rect r,
                                Component label, boolean primary,
                                int mouseX, int mouseY) {
        boolean hover = r.contains(mouseX, mouseY);
        int fill;
        if (primary) {
            fill = hover ? 0xCC4F9A52 : 0xAA356B38;
        } else {
            fill = hover ? 0xCC555566 : 0xAA333344;
        }
        graphics.fill(r.x0, r.y0, r.x1, r.y1, fill);
        int edge = hover ? 0xFFFFC24A : 0xFF667080;
        graphics.fill(r.x0, r.y0, r.x1, r.y0 + 1, edge);
        graphics.fill(r.x0, r.y1 - 1, r.x1, r.y1, edge);
        graphics.fill(r.x0, r.y0, r.x0 + 1, r.y1, edge);
        graphics.fill(r.x1 - 1, r.y0, r.x1, r.y1, edge);
        graphics.text(font, label, r.x0 + ((r.x1 - r.x0) - font.width(label)) / 2,
            r.y0 + 4, hover ? 0xFFFFFFFF : 0xFFE0E0E0);
    }

    private void openSkinPanel() {
        skinPanelOpen = true;
        applySkinMode();
        if (skinStatus == null || skinStatus.isBlank()) {
            skinStatus = skinModeHint();
        }
        layoutSkinPanel();
        setSkinModeVisible(true);
        if (skinInputBox != null) {
            skinInputBox.setVisible(true);
            setFocused(skinInputBox);
        }
    }

    private void setSkinModeVisible(boolean visible) {
        if (skinPlayerBtn != null) skinPlayerBtn.visible = visible;
        if (skinFileBtn != null) skinFileBtn.visible = visible;
    }

    private String skinModeHint() {
        return Component.translatable(skinUseFile
            ? "playerpose.skin.modeFileHint"
            : "playerpose.skin.modePlayerHint").getString();
    }

    /** Point the single input field at either a player name or a file path. */
    private void setSkinUseFile(boolean useFile, boolean clear) {
        skinUseFile = useFile;
        if (skinInputBox != null) {
            if (clear) skinInputBox.setValue("");
            skinInputBox.setHint(Component.translatable(useFile
                ? "playerpose.skin.fileHint" : "playerpose.skin.playerHint"));
        }
    }

    private void applySkinMode() {
        setSkinUseFile(skinUseFile, false);
    }

    /** Clicking a segment selects that mode; clicking the active one only refocuses. */
    private void chooseSkinMode(boolean useFile) {
        if (skinUseFile != useFile) {
            setSkinUseFile(useFile, true);
            skinStatus = skinModeHint();
        }
        if (skinInputBox != null) setFocused(skinInputBox);
    }

    private void closeSkinPanel() {
        skinPanelOpen = false;
        setFocused(null);
        setSkinModeVisible(false);
        if (skinInputBox != null) {
            skinInputBox.setVisible(false);
            skinInputBox.setFocused(false);
        }
    }

    private void onSkinStatus(String message) {
        skinStatus = message == null ? "" : message;
        if (PreviewSkinManager.skin() != null) {
            skinModel = PreviewSkinManager.model();
        }
    }

    private void loadSkinInput() {
        if (skinInputBox == null) return;
        String text = skinInputBox.getValue().trim();
        if (text.isEmpty()) {
            onSkinStatus(Component.translatable(skinUseFile
                ? "playerpose.skin.noFile" : "playerpose.skin.noPlayer").getString());
            return;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        boolean looksLikePath = text.contains("\\") || text.contains("/")
            || lower.endsWith(".png");
        if (!skinUseFile && looksLikePath) {
            // A Mojang name can never contain a separator or end in .png; treat it
            // as a file path even if the mode button was still on Player.
            setSkinUseFile(true, false);
        }
        if (!skinUseFile) {
            PreviewSkinManager.loadFromPlayer(text, this::onSkinStatus);
            return;
        }
        try {
            PreviewSkinManager.loadFromFile(java.nio.file.Path.of(text), skinModel,
                this::onSkinStatus);
        } catch (Throwable error) {
            onSkinStatus(Component.translatable("playerpose.skin.badFile").getString());
        }
    }

    private void browseSkinFile() {
        setSkinUseFile(true, true);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.png"));
            filters.flip();
            String path = TinyFileDialogs.tinyfd_openFileDialog(
                Component.translatable("playerpose.skin.dialogTitle").getString(),
                "", filters,
                Component.translatable("playerpose.skin.dialogFilter").getString(),
                false);
            if (path != null && !path.isBlank()) {
                skinInputBox.setValue(path);
                loadSkinInput();
            }
        } catch (Throwable error) {
            PoseLog.warn("skin preview: file dialog failed (%s)", error);
            onSkinStatus(Component.translatable("playerpose.skin.dialogError").getString());
        }
    }

    private void toggleSkinModel() {
        skinModel = skinModel == PlayerModelType.SLIM
            ? PlayerModelType.WIDE : PlayerModelType.SLIM;
        if (PreviewSkinManager.skin() != null) {
            PreviewSkinManager.setModel(skinModel, this::onSkinStatus);
        } else {
            onSkinStatus(Component.translatable(skinModel == PlayerModelType.SLIM
                ? "playerpose.skin.armsSlim" : "playerpose.skin.armsClassic").getString());
        }
    }

    private void restoreOriginalSkin() {
        PreviewSkinManager.clear();
        skinModel = PlayerModelType.WIDE;
        onSkinStatus(Component.translatable("playerpose.skin.originalSet").getString());
    }

    // ---- actions ---------------------------------------------------------

    private void savePose() {
        if (!editingPose || workingPose == null) {
            setStatus(Component.translatable("playerpose.status.pressNew"));
            return;
        }
        String name = workingPose.getName() == null ? "" : workingPose.getName().trim();
        if (name.isEmpty()) {
            openNameDialog(false);
            return;
        }
        if (!name.equals(originalName) && poseManager.get(name) != null) {
            setStatus(Component.translatable("playerpose.status.nameTaken", name));
            return;
        }
        // A successful save is a commit point: it also drops any pending "redo this
        // delete" state.
        lastDeletedName = null;
        commit();

        // The name dialog changed the draft name: Save renames the stored file. The
        // old file is removed rather than leaving a second copy behind.
        String oldName = originalName;
        boolean renaming = oldName != null && !oldName.isBlank() && !oldName.equals(name);
        String activeBefore = PlayerPoseClient.getActivePoseName();
        boolean wasActive = oldName != null && oldName.equals(activeBefore);
        // Saving is not loading: when another pose is already loaded it stays loaded.
        // Only the first save (nothing loaded yet) also loads the result.
        boolean nothingLoaded = activeBefore == null || poseManager.get(activeBefore) == null;
        boolean loadNow = !wasActive && nothingLoaded && PlayerPoseClient.arePosesEnabled();
        if (renaming) {
            poseManager.delete(oldName);
        }

        workingPose.setName(name);
        originalName = name;
        if (wasActive || loadNow) {
            workingPose.setSkin(skin);
            poseManager.bind(workingPose);
            PlayerPoseClient.setActivePoseName(name);
        } else {
            // Keep the stored skin binding: saving a pose that is not loaded must not
            // unbind the pose that is currently in effect.
            poseManager.save(workingPose);
        }
        // A save is a commit point: the file and the editor agree from here on.
        undoStack.clear();
        redoStack.clear();
        refreshPoseList();
        setTab(1);
        ensureWorkingPoseVisible();
        if (renaming) {
            setStatus(Component.translatable("playerpose.status.renamed", oldName, name));
            PlayerPoseClient.LOGGER.info("Pose renamed: {} -> {} for skin {}", oldName, name, skin);
        } else if (loadNow) {
            setStatus(Component.translatable("playerpose.status.savedLoaded", name));
            PlayerPoseClient.LOGGER.info("Pose saved and loaded: {} for skin {}", name, skin);
        } else if (wasActive) {
            setStatus(Component.translatable("playerpose.status.saved", name));
            PlayerPoseClient.LOGGER.info("Pose saved: {} for skin {}", name, skin);
        } else {
            setStatus(Component.translatable("playerpose.status.savedNotLoaded", name));
            PlayerPoseClient.LOGGER.info("Pose saved (not loaded): {} for skin {}", name, skin);
        }
    }

    /** New pose: ask for a name first, then start a fresh draft. */
    private void newPose() {
        openNameDialog(true);
    }

    /** Open the small name dialog; used for both New and Rename. */
    private void openNameDialog(boolean forNew) {
        if (!forNew && !editingPose) {
            setStatus(Component.translatable("playerpose.status.pressNew"));
            return;
        }
        nameDialogForNew = forNew;
        nameDialogText = forNew ? "" : (workingPose.getName() == null ? "" : workingPose.getName());
        nameDialogOpen = true;
    }

    private void closeNameDialog() {
        nameDialogOpen = false;
        nameDialogText = "";
    }

    /** Apply the dialog: create a new draft, or rename the current draft. */
    private void confirmNameDialog() {
        String name = nameDialogText.trim();
        if (name.isEmpty()) {
            setStatus(Component.translatable("playerpose.status.nameEmpty"));
            return;
        }
        boolean sameAsCurrent = !nameDialogForNew && originalName != null
            && name.equals(originalName);
        if (!sameAsCurrent && poseManager.get(name) != null) {
            setStatus(Component.translatable("playerpose.status.nameTaken", name));
            return;
        }

        if (nameDialogForNew) {
            workingPose = new Pose();
            workingPose.setName(name);
            workingPose.setSkin(skin);
            workingPose.setBones(new ArrayList<>());
            originalName = "";
            editingPose = true;
            PlayerPoseClient.setEditorPreviewPose(workingPose);
            selected = "body";
            selectionVisible = true;
            gizmoVisible = true;
            listScroll = 0;
            moveMode = false;
            undoStack.clear();
            redoStack.clear();
            refreshModeButtons();
            syncFromPose();
            setTab(0);
            refreshPoseList();
            updateActionButtons();
            setStatus(Component.translatable("playerpose.status.newPose", name));
            PoseLog.info("new: blank pose '%s' started (nothing applied until saved)", name);
        } else {
            String old = originalName == null || originalName.isBlank()
                ? "(unsaved)" : originalName;
            workingPose.setName(name);
            refreshPoseList();
            setStatus(Component.translatable("playerpose.status.renameDraft", old, name));
        }
        closeNameDialog();
    }

    /** Geometry of the name dialog, recomputed on layout changes. */
    private void nameDialogLayout() {
        int w = Math.min(240, width - 20);
        int h = 96;
        nameDialogX0 = (width - w) / 2;
        nameDialogY0 = (height - h) / 2;
        nameDialogX1 = nameDialogX0 + w;
        nameDialogY1 = nameDialogY0 + h;

        int bw = (w - 30) / 2;
        int by = nameDialogY1 - 28;
        nameDialogCancelX0 = nameDialogX0 + 10;
        nameDialogCancelY0 = by;
        nameDialogCancelX1 = nameDialogCancelX0 + bw;
        nameDialogCancelY1 = by + CTRL_H;
        nameDialogOkX0 = nameDialogCancelX1 + 10;
        nameDialogOkY0 = by;
        nameDialogOkX1 = nameDialogOkX0 + bw;
        nameDialogOkY1 = by + CTRL_H;
    }

    /**
     * Clear every bone in the working pose, i.e. back to the rest pose. Undoable.
     */
    private void resetAll() {
        beginEdit();
        if (workingPose != null) {
            workingPose.setBones(new ArrayList<>());
        }
        syncFromPose();
        setStatus(Component.translatable("playerpose.status.resetAll"));
    }

    private void deletePose() {
        if (workingPose == null || workingPose.getName() == null || workingPose.getName().isBlank()) {
            setStatus(Component.translatable("playerpose.status.noPoseToDelete"));
            return;
        }
        String name = workingPose.getName();
        beginEdit();
        poseManager.delete(name);
        // Nothing is active if the active pose just went away - never fall back silently.
        if (name.equals(PlayerPoseClient.getActivePoseName())) {
            PlayerPoseClient.setActivePoseName(null);
        }
        workingPose = new Pose();
        workingPose.setSkin(skin);
        PlayerPoseClient.setEditorPreviewPose(workingPose);
        editingPose = false;
        originalName = null;
        selectionVisible = false;
        gizmoVisible = false;
        selectRow("body");
        refreshPoseList();
        updateActionButtons();
        setStatus(Component.translatable("playerpose.status.deleted", name));
        // The next snapshot describes the post-delete blank state. Redoing an undo of
        // this delete must remove the pose again, not just show an empty editor.
        lastDeletedName = name;
    }

    private Snapshot snapshot() {
        Snapshot snap = new Snapshot(PoseManager.copy(workingPose), selected, lastDeletedName);
        lastDeletedName = null; // the delete intent belongs to this snapshot only
        return snap;
    }

    /** Remember the current state before a mutating action. */
    private void beginEdit() {
        pushUndo(snapshot());
    }

    private void pushUndo(Snapshot snap) {
        if (snap == null || snap.pose() == null) return;
        undoStack.push(snap);
        while (undoStack.size() > MAX_UNDO) undoStack.removeLast();
        redoStack.clear();
    }

    /** True when the working pose differs from a snapshot taken earlier. */
    private boolean changedSince(Snapshot snap) {
        if (snap == null || snap.pose() == null || workingPose == null) return true;
        return !PoseManager.toJson(snap.pose()).equals(PoseManager.toJson(workingPose));
    }

    private void undo() {
        if (undoStack.isEmpty()) {
            setStatus(Component.translatable("playerpose.status.nothingToUndo"));
            return;
        }
        redoStack.push(snapshot());
        restore(undoStack.pop());
        setStatus(Component.translatable("playerpose.status.undone", undoStack.size()));
    }

    private void redo() {
        if (redoStack.isEmpty()) {
            setStatus(Component.translatable("playerpose.status.nothingToRedo"));
            return;
        }
        undoStack.push(snapshot());
        restore(redoStack.pop());
        setStatus(Component.translatable("playerpose.status.redone", redoStack.size()));
    }

    private void restore(Snapshot snap) {
        if (snap == null || snap.pose() == null) return;
        workingPose = snap.pose();
        selected = snap.selected() == null ? "body" : snap.selected();
        PlayerPoseClient.setEditorPreviewPose(workingPose);
        syncFromPose();
        // Redoing the delete of a pose: remove the copy that undoing it re-saved. Keep
        // the name on the blank state so a later redo does the same again.
        String deleteOnRestore = snap.deleteOnRestore();
        boolean blank = workingPose.getName() == null || workingPose.getName().isBlank();
        editingPose = !blank;
        originalName = blank ? null : workingPose.getName();
        if (!editingPose) {
            selectionVisible = false;
            gizmoVisible = false;
        }
        updateActionButtons();
        if (deleteOnRestore != null && blank) {
            poseManager.delete(deleteOnRestore);
            lastDeletedName = deleteOnRestore;
        } else {
            lastDeletedName = null;
            // Undoing a delete restores the file exactly as the snapshot had it.
            // Undo never changes which pose is loaded or its skin binding.
            String name = workingPose.getName();
            if (name != null && !name.isBlank() && poseManager.get(name) == null) {
                poseManager.save(workingPose);
            }
        }
        refreshPoseList();
        ensureWorkingPoseVisible();
        refreshModeButtons();
    }

    private Component modeLabel() {
        return Component.translatable(moveMode ? "playerpose.modeMove" : "playerpose.modeRotate");
    }

    /** Rotate / Move are two buttons, not one toggle - clicking either picks that mode. */
    private void setMode(boolean move) {
        if (moveMode == move) return;
        moveMode = move;
        refreshModeButtons();
        setStatus(modeLabel());
    }

    private void refreshModeButtons() {
        if (rotateBtn == null || moveBtn == null) return;
        rotateBtn.active = true;
        moveBtn.active = true;
    }

    /** Border around the two mode buttons plus a marker on the mode in use. */
    private void drawModeGroup(GuiGraphicsExtractor graphics) {
        if (listTab != 0) return;
        if (rotateBtn == null || moveBtn == null) return;
        if (modeGroupX1 <= modeGroupX0) return;

        // Outer frame, drawn after the widgets so it sits on top of their edges.
        graphics.fill(modeGroupX0, modeGroupY0, modeGroupX1, modeGroupY0 + 1, 0xFFAAB4D4);
        graphics.fill(modeGroupX0, modeGroupY1 - 1, modeGroupX1, modeGroupY1, 0xFFAAB4D4);
        graphics.fill(modeGroupX0, modeGroupY0, modeGroupX0 + 1, modeGroupY1, 0xFFAAB4D4);
        graphics.fill(modeGroupX1 - 1, modeGroupY0, modeGroupX1, modeGroupY1, 0xFFAAB4D4);

        Button active = moveMode ? moveBtn : rotateBtn;
        int x = active.getX();
        int y = active.getY();
        int w = active.getWidth();
        int h = active.getHeight();
        // Tint the chosen one and bar its bottom edge, so "which mode am I in" is obvious.
        graphics.fill(x, y, x + w, y + h, 0x33FFD48A);
        graphics.fill(x + 1, y + h - 2, x + w - 1, y + h, 0xFFFFC24A);
    }

    private Component unloadLabel() {
        return Component.translatable("playerpose.unloadAllBtn");
    }

    /**
     * Unload every pose at once: the local player, all remote targets and the
     * saved-pose preview. Pose files are untouched.
     */
    private void unloadAll() {
        lastDeletedName = null;
        PlayerPoseClient.unloadAllPoses();
        refreshPoseList();
        setStatus(Component.translatable("playerpose.status.unloadedAll"));
    }

    private void resetRow() {
        beginEdit();
        rotX = 0; rotY = 0; rotZ = 0;
        tX = 0; tY = 0; tZ = 0;
        commit();
        setStatus(Component.translatable("playerpose.status.reset", selected));
    }

    private void setStatus(Component msg) {
        statusMsg = msg == null ? "" : msg.getString();
        statusUntil = System.currentTimeMillis() + 3000L;
    }

    // ---- layout / widgets ------------------------------------------------

    @Override
    protected void init() {
        super.init();
        Font font = this.font;

        skinInputBox = new EditBox(font, 0, 0, 208, CTRL_H, Component.empty());
        skinInputBox.setHint(Component.translatable("playerpose.skin.playerHint"));
        skinInputBox.setMaxLength(Integer.MAX_VALUE);
        skinInputBox.setVisible(false);
        addRenderableWidget(skinInputBox);

        skinPlayerBtn = Button.builder(
            Component.translatable("playerpose.skin.modePlayer"), b -> chooseSkinMode(false))
            .bounds(0, 0, 56, CTRL_H).build();
        skinFileBtn = Button.builder(
            Component.translatable("playerpose.skin.modeFile"), b -> chooseSkinMode(true))
            .bounds(0, 0, 56, CTRL_H).build();
        skinPlayerBtn.visible = false;
        skinFileBtn.visible = false;
        addRenderableWidget(skinPlayerBtn);
        addRenderableWidget(skinFileBtn);

        PreviewSkinManager.setActive(true);

        // Name bar is drawn by us and is click-to-rename; there is no free-typing
        // field here anymore.

        // Left sidebar: Save and Done are common; the active tab swaps the other four
        // buttons (Rotate/Move/Reset/Reset All on Bones, New/Delete/Unload on Poses).
        int col2 = LEFT_X + BTN_W + BTN_GAP;

        saveBtn = Button.builder(Component.translatable("playerpose.saveBtn"), b -> savePose())
            .bounds(LEFT_X, BTN_Y, BTN_W, CTRL_H).build();
        addRenderableWidget(saveBtn);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
            .bounds(col2, BTN_Y, BTN_W, CTRL_H).build());

        rotateBtn = Button.builder(Component.translatable("playerpose.modeRotate"), b -> setMode(false))
            .bounds(LEFT_X, BTN_Y + BTN_ROW_H, BTN_W, CTRL_H).build();
        moveBtn = Button.builder(Component.translatable("playerpose.modeMove"), b -> setMode(true))
            .bounds(col2, BTN_Y + BTN_ROW_H, BTN_W, CTRL_H).build();
        resetBtn = Button.builder(Component.translatable("playerpose.resetBtn"), b -> resetRow())
            .bounds(LEFT_X, BTN_Y + BTN_ROW_H * 2, BTN_W, CTRL_H).build();
        resetAllBtn = Button.builder(Component.translatable("playerpose.resetAllBtn"), b -> resetAll())
            .bounds(col2, BTN_Y + BTN_ROW_H * 2, BTN_W, CTRL_H).build();
        addRenderableWidget(rotateBtn);
        addRenderableWidget(moveBtn);
        addRenderableWidget(resetBtn);
        addRenderableWidget(resetAllBtn);

        // 2px pad so the grouping border reads as a box around both mode buttons.
        modeGroupX0 = LEFT_X - 2;
        modeGroupY0 = BTN_Y + BTN_ROW_H - 2;
        modeGroupX1 = col2 + BTN_W + 2;
        modeGroupY1 = BTN_Y + BTN_ROW_H + CTRL_H + 2;
        refreshModeButtons();

        newBtn = Button.builder(Component.translatable("playerpose.newBtn"), b -> newPose())
            .bounds(LEFT_X, BTN_Y + BTN_ROW_H, BTN_W, CTRL_H).build();
        deleteBtn = Button.builder(Component.translatable("playerpose.deleteBtn"), b -> deletePose())
            .bounds(col2, BTN_Y + BTN_ROW_H, BTN_W, CTRL_H).build();
        unloadBtn = Button.builder(unloadLabel(), b -> unloadAll())
            .bounds(LEFT_X, BTN_Y + BTN_ROW_H * 2, BTN_W, CTRL_H).build();
        addRenderableWidget(newBtn);
        addRenderableWidget(deleteBtn);
        addRenderableWidget(unloadBtn);

        updateActionButtons();

        vpX0 = LEFT_X + LEFT_W + 8;
        vpY0 = TOP_H;
        vpX1 = Math.max(vpX0 + 80, width - 8);
        vpY1 = Math.max(vpY0 + 80, height - STATUS_H - 4);

        if (skinPanelOpen) {
            layoutSkinPanel();
            applySkinMode();
            setSkinModeVisible(true);
            skinInputBox.setVisible(true);
        }

        PlayerPoseClient.setEditorPreviewPose(workingPose);
        refreshPoseList();
        compatWarnings = PoseCompat.warnings(Minecraft.getInstance());
        if (!compatLogged) {
            compatLogged = true;
            for (Component warning : compatWarnings) {
                PoseLog.warn("compat: %s", warning.getString());
            }
        }
    }

    /** Save/Done stay; the other four actions follow the active list tab. */
    private void updateActionButtons() {
        boolean bones = listTab == 0;
        if (saveBtn != null) saveBtn.active = editingPose;
        if (rotateBtn != null) {
            rotateBtn.visible = bones;
            rotateBtn.active = editingPose;
        }
        if (moveBtn != null) {
            moveBtn.visible = bones;
            moveBtn.active = editingPose;
        }
        if (resetBtn != null) {
            resetBtn.visible = bones;
            resetBtn.active = editingPose;
        }
        if (resetAllBtn != null) {
            resetAllBtn.visible = bones;
            resetAllBtn.active = editingPose;
        }
        if (newBtn != null) newBtn.visible = !bones;
        if (deleteBtn != null) {
            deleteBtn.visible = !bones;
            deleteBtn.active = editingPose && originalName != null && !originalName.isBlank();
        }
        if (unloadBtn != null) unloadBtn.visible = !bones;
    }

    private void setTab(int tab) {
        if (listTab == tab) return;
        listTab = tab;
        listScroll = 0;
        updateActionButtons();
        if (tab == 0) {
            ensureRowVisible(ROWS.indexOf(selected));
        } else {
            ensureWorkingPoseVisible();
        }
    }

    private double modelPxPerBlock() {
        return ((vpY1 - vpY0) * 0.8 / 2.0) * camZoom;
    }

    private double[] viewportCenter() {
        return new double[] {(vpX0 + vpX1) / 2.0, (vpY0 + vpY1) / 2.0};
    }

    /**
     * Screen position of the joint the gizmo sits on. The pivots published by the
     * mixins are live model positions, so they already include the pose's translate
     * offset; only the static fallback table still needs it added.
     */
    private double[] gizmoCenter() {
        float[] pivot = PlayerPoseClient.getJointPivot(selected);
        double ox = 0.0, oy = 0.0, oz = 0.0;
        if (pivot == null) {
            int idx = ROWS.indexOf(selected);
            pivot = FALLBACK_PIVOTS[idx < 0 ? 0 : idx];
            ox = tX;
            oy = tY;
            oz = tZ;
        }

        double[] c = viewportCenter();
        return PoseGizmo.projectPivot(c[0], c[1], modelPxPerBlock(),
            Math.toRadians(180.0 + camYaw), Math.toRadians(camPitch),
            (float) (pivot[0] + ox), (float) (pivot[1] + oy), (float) (pivot[2] + oz));
    }

    private double gizmoRadius() {
        double r = Math.min(vpX1 - vpX0, vpY1 - vpY0) * 0.20;
        return Math.max(18.0, Math.min(r, 66.0));
    }

    private PoseGizmo buildGizmo(GuiGraphicsExtractor gfx, int hoverAxis) {
        double[] c = gizmoCenter();
        return new PoseGizmo(gfx, c[0], c[1], gizmoRadius(),
            Math.toRadians(180.0 + camYaw), Math.toRadians(camPitch),
            hoverAxis, activeAxis, moveMode);
    }

    // ---- rendering -------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF101018);
        graphics.fill(0, height - STATUS_H, width, height, 0x30FFFFFF);

        Font font = this.font;

        // Left sidebar behind the name field, action grid, bone list and pose list.
        panel(graphics, 4, 4, LEFT_X + LEFT_W + 4, height - STATUS_H);
        drawNameBar(graphics, font, mouseX, mouseY);

        // Keeps the mode button honest no matter how the mode changed.
        refreshModeButtons();

        drawViewport(graphics, mouseX, mouseY, partialTick);
        drawTopInfo(graphics, font);
        drawList(graphics, font, mouseX, mouseY);
        drawStatusBar(graphics, font);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // On top of the widgets, so the group border and the mode marker stay visible.
        drawModeGroup(graphics);
        drawLoadMenu(graphics, font, mouseX, mouseY);
        drawNameDialog(graphics, font, mouseX, mouseY);
        drawSkinPanel(graphics, font, mouseX, mouseY, partialTick);

        if (!compatWarnings.isEmpty() && compatX1 > compatX0
            && isOver(mouseX, mouseY, compatX0, compatY0,
                compatX1 - compatX0, compatY1 - compatY0)) {
            int index = compatWarningIndex();
            List<Component> tip = new ArrayList<>();
            tip.add(compatWarnings.get(index));
            if (compatWarnings.size() > 1) {
                tip.add(Component.literal((index + 1) + "/" + compatWarnings.size()));
            }
            graphics.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
        }
    }

    private void drawViewport(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        Font font = this.font;

        graphics.fill(vpX0, vpY0, vpX1, vpY1, 0xFF181A22);
        for (int gx = vpX0 + 16; gx < vpX1; gx += 32) {
            graphics.fill(gx, vpY0, gx + 1, vpY1, 0x10FFFFFF);
        }
        for (int gy = vpY0 + 16; gy < vpY1; gy += 32) {
            graphics.fill(vpX0, gy, vpX1, gy + 1, 0x10FFFFFF);
        }
        graphics.fill(vpX0, vpY0, vpX1, vpY0 + 1, 0xFF666688);
        graphics.fill(vpX0, vpY1 - 1, vpX1, vpY1, 0xFF666688);
        graphics.fill(vpX0, vpY0, vpX0 + 1, vpY1, 0xFF666688);
        graphics.fill(vpX1 - 1, vpY0, vpX1, vpY1, 0xFF666688);

        graphics.enableScissor(vpX0 + 1, vpY0 + 1, vpX1 - 1, vpY1 - 1);
        renderPlayerModel(graphics, partialTick);
        if (gizmoVisible && editingPose) {
            int hoverAxis = buildGizmo(graphics, PoseGizmo.NONE).hitTest(mouseX, mouseY);
            buildGizmo(graphics, hoverAxis).draw();
        }
        graphics.disableScissor();

        if (!skinPanelOpen) {
            drawSkinButton(graphics, font, mouseX, mouseY);
        } else {
            skinButtonRect = null;
        }

        // The model owns the viewport; the read-out lives in the top strip instead of
        // painting over the head. Long hints scroll instead of being cut off.
        graphics.fill(vpX0, vpY1 - 14, vpX1, vpY1, 0x66000000);
        drawMarquee(graphics, font,
            Component.translatable("playerpose.viewportHint").getString(),
            vpX0 + 4, vpY1 - 12, vpX1 - vpX0 - 8, 0xFF888899);
    }

    /** Selected bone, mode, compat/unsaved badges and axis read-out, above the viewport. */
    private void drawTopInfo(GuiGraphicsExtractor graphics, Font font) {
        panel(graphics, vpX0, INFO_Y, vpX1, INFO_Y + INFO_H);
        int maxW = vpX1 - vpX0 - 8;

        boolean unsaved = editingPose && (workingPose == null || workingPose.getName() == null
            || workingPose.getName().isBlank());
        Component unsavedLabel = Component.translatable("playerpose.unsavedBadge");
        Component compatLabel = Component.translatable("playerpose.compat.badge");
        int compatW = compatWarnings.isEmpty() ? 0 : font.width(compatLabel) + 6;
        int reserved = (unsaved ? font.width(unsavedLabel) + 4 : 0)
            + (compatW > 0 ? compatW + 4 : 0);

        Component mode = Component.translatable(moveMode ? "playerpose.modeMove" : "playerpose.modeRotate");
        String selectedLine = itemLabel(selected).getString() + "  [" + mode.getString() + "]";
        int textW = Math.max(32, maxW - reserved);
        drawMarquee(graphics, font, selectedLine, vpX0 + 4, INFO_Y + 3, textW,
            0xFFFFFFFF);

        int badgeRight = vpX1 - 4;
        if (compatW > 0) {
            int x = badgeRight - compatW;
            boolean blink = warnBlink();
            graphics.fill(x, INFO_Y + 2, badgeRight, INFO_Y + 12,
                blink ? 0x55FFC24A : 0x22FFC24A);
            graphics.text(font, compatLabel, x + 3, INFO_Y + 3,
                blink ? 0xFFFFC24A : 0xFF7A5A1A);
            compatX0 = x;
            compatY0 = INFO_Y + 2;
            compatX1 = badgeRight;
            compatY1 = INFO_Y + 12;
            badgeRight = x - 4;
        } else {
            compatX0 = 0;
            compatX1 = 0;
        }
        if (unsaved) {
            int x = badgeRight - font.width(unsavedLabel);
            if (x > vpX0 + 4) {
                graphics.text(font, unsavedLabel, x, INFO_Y + 3, 0xFFFFC24A);
            }
        }

        String[] axisNames = {"X", "Y", "Z"};
        int[] axisColors = {0xFFFF5555, 0xFF6FD44F, 0xFF4F86FF};
        int ax = vpX0 + 4;
        for (int i = 0; i < 3; i++) {
            Component label = moveMode
                ? vecLabel(axisNames[i], translationFor(i))
                : angleLabel(axisNames[i], i);
            int labelW = font.width(label);
            if (ax + labelW > vpX1 - 4) break;
            graphics.text(font, label, ax, INFO_Y + 13,
                i == activeAxis ? 0xFFFFFFFF : axisColors[i]);
            ax += labelW + 8;
        }
    }

    private Component angleLabel(String name, int axis) {
        return Component.literal(String.format(Locale.ROOT, "%s %+.1f", name, Math.toDegrees(rotationFor(axis))));
    }

    private Component vecLabel(String name, double units) {
        return Component.literal(String.format(Locale.ROOT, "%s %+.2f", name, units / 16.0));
    }

    private void renderPlayerModel(GuiGraphicsExtractor graphics, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        LivingEntity entity = mc.player;
        if (entity == null) return;

        EntityRenderer<? super LivingEntity, ?> renderer =
            mc.getEntityRenderDispatcher().getRenderer(entity);
        if (renderer == null) return;

        EntityRenderState state = renderer.createRenderState(entity, partialTick);
        if (state instanceof AvatarRenderState avatar) {
            var preview = PreviewSkinManager.skin();
            if (preview != null) {
                // Editor-only: replace the render state's skin, never the player's.
                avatar.skin = preview;
            }
        }
        state.shadowPieces.clear();
        state.outlineColor = 0;

        if (state instanceof LivingEntityRenderState living) {
            float scale = living.scale;
            state.boundingBoxWidth *= scale;
            state.boundingBoxHeight *= scale;
            living.scale = 1.0F;
            // bodyRot faces the whole model at the camera...
            living.bodyRot = 180.0F + (float) camYaw;
            // ...and yRot/xRot are the HEAD's offset from the body (HumanoidModel
            // writes them straight into head.yRot/xRot). Vanilla's inventory preview
            // points them at the mouse, which makes the head follow the cursor and
            // fight whatever the pose sets for "head", so keep the head on the torso.
            living.yRot = 0.0F;
            living.xRot = 0.0F;
        }

        Quaternionf rot = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf pitchQ = new Quaternionf().rotateX((float) Math.toRadians(camPitch));
        rot.mul(pitchQ);

        Vector3f offset = new Vector3f(0.0F, state.boundingBoxHeight / 2.0F, 0.0F);
        graphics.entity(state, (float) modelPxPerBlock(), offset, rot, pitchQ, vpX0, vpY0, vpX1, vpY1);
    }

    /**
     * The left panel body: a clickable mannequin on the Bones tab, a scrollable saved
     * pose list on the Poses tab. Tabs keep both sets reachable on short GUI scales
     * without the old fixed-height lists overlapping the status bar.
     */
    private void drawList(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        int bottom = height - STATUS_H - 2;
        panel(graphics, LEFT_X - 3, TAB_Y - 2, LEFT_X + LEFT_W + 3, bottom);
        drawTabs(graphics, font, mouseX, mouseY);

        int rows = listWindow();
        boolean enabled = PlayerPoseClient.arePosesEnabled();

        if (listTab == 0) {
            drawBoneFigure(graphics, font, mouseX, mouseY);
            return;
        }

        if (savedPoseNames.isEmpty()) {
            graphics.text(font, Component.translatable("playerpose.noPoses"),
                LEFT_X + 4, LIST_Y + 4, 0xFF888888);
        }

        Minecraft mc = Minecraft.getInstance();
        UUID self = mc.player == null ? null : mc.player.getUUID();
        Set<UUID> online = onlinePlayerIds(mc);
        int onlineCount = Math.max(1, online.size());

        for (int k = 0; k < rows; k++) {
            int index = listScroll + k;
            if (index >= listTotalRows()) break;
            int yy = LIST_Y + k * ROW_H;

            String name = savedPoseNames.get(index);

            // Number of online players currently carrying this pose. The row tint
            // opacity simply follows the share of online players: 30% -> 30%,
            // 50% -> 50%, 100% -> fully opaque.
            int used = 0;
            for (UUID id : online) {
                boolean has = self != null && id.equals(self)
                    ? PlayerPoseClient.hasLocalPose(name)
                    : PlayerPoseClient.hasRemotePose(id, name);
                if (has) used++;
            }
            float share = (float) used / onlineCount;
            int usedAlpha = used == 0 ? 0
                : Math.max(1, Math.min(255, Math.round(255.0F * share)));

            boolean isSelected = workingPose != null && name.equals(workingPose.getName());
            boolean hover = isOver(mouseX, mouseY, LEFT_X, yy, LEFT_W, ROW_H);

            if (usedAlpha > 0) {
                graphics.fill(LEFT_X, yy, LEFT_X + LEFT_W, yy + ROW_H,
                    (usedAlpha << 24) | 0x004444AA);
            }
            if (hover) {
                graphics.fill(LEFT_X, yy, LEFT_X + LEFT_W, yy + ROW_H, 0x33FFFFFF);
            }
            if (isSelected) {
                int y0 = yy;
                int y1 = yy + ROW_H;
                graphics.fill(LEFT_X, y0, LEFT_X + LEFT_W, y0 + 1, 0xFFFFC24A);
                graphics.fill(LEFT_X, y1 - 1, LEFT_X + LEFT_W, y1, 0xFFFFC24A);
                graphics.fill(LEFT_X, y0, LEFT_X + 1, y1, 0xFFFFC24A);
                graphics.fill(LEFT_X + LEFT_W - 1, y0, LEFT_X + LEFT_W, y1, 0xFFFFC24A);
            }
            int textColor = used > 0 || isSelected ? 0xFFFFFFFF
                : enabled ? 0xFFCCCCCC : 0xFF8891A0;
            graphics.text(font, Component.literal(font.plainSubstrByWidth(name, LEFT_W - 22)),
                LEFT_X + 4, yy + 3, textColor);

            // Per-pose "+" opens the multi-target picker. The pose row itself still
            // selects the pose for editing.
            int plusX = LEFT_X + LEFT_W - 14;
            boolean plusHover = isOver(mouseX, mouseY, plusX, yy + 1, 10, ROW_H - 2);
            graphics.fill(plusX, yy + 1, plusX + 10, yy + ROW_H - 1,
                plusHover ? 0x664F9A52 : 0x33222222);
            graphics.text(font, Component.literal("+"), plusX + 3, yy + 3,
                plusHover ? 0xFFFFFFFF : 0xFFFFD48A);
        }

        // Scrollbar for the active tab.
        if (maxListScroll() > 0) {
            int trackX = LEFT_X + LEFT_W - 3;
            int trackTop = LIST_Y;
            int trackH = Math.max(ROW_H, rows * ROW_H);
            graphics.fill(trackX, trackTop, trackX + 2, trackTop + trackH, 0x40FFFFFF);
            int total = Math.max(1, listTotalRows());
            int thumbH = Math.max(8, trackH * rows / total);
            int thumbY = trackTop + (trackH - thumbH) * listScroll / Math.max(1, maxListScroll());
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xCCFFFFFF);
        }
    }

    /** Unique UUIDs of the local player plus every player listed by the server. */
    private static Set<UUID> onlinePlayerIds(Minecraft mc) {
        Set<UUID> ids = new HashSet<>();
        if (mc.player != null) ids.add(mc.player.getUUID());
        if (mc.getConnection() != null) {
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                if (info != null && info.getProfile() != null
                    && info.getProfile().id() != null) {
                    ids.add(info.getProfile().id());
                }
            }
        }
        return ids;
    }

    // ---- clickable mannequin (Bones tab) ---------------------------------

    private static final int FIG_CAPE_CHIP_H = 14;

    /**
     * Geometry of the small mannequin. Parts are laid out in classic 16 x 32 skin
     * units (the player model), then scaled to whatever height the sidebar has.
     */
    private record BoneFigure(int cx, int top, double scale,
                              int chipX0, int chipY0, int chipX1, int chipY1,
                              int captionY) {
        int x(double unit) { return (int) Math.round(cx + unit * scale); }
        int y(double unit) { return (int) Math.round(top + unit * scale); }
    }

    private BoneFigure boneFigure() {
        int areaTop = LIST_Y + 2;
        int areaBottom = height - STATUS_H - 4;
        int captionY = areaBottom - 9;
        int chipY1 = captionY - 3;
        int chipY0 = chipY1 - FIG_CAPE_CHIP_H;
        int available = Math.max(40, chipY0 - 4 - areaTop);
        double scale = Math.min(available / 32.0, (LEFT_W - 56) / 16.0);
        scale = Math.max(1.5, scale);
        int figureH = (int) Math.round(32 * scale);
        int top = areaTop + Math.max(0, (available - figureH) / 2);
        int cx = LEFT_X + LEFT_W / 2;
        int chipW = 64;
        return new BoneFigure(cx, top, scale,
            cx - chipW / 2, chipY0, cx + chipW / 2, chipY1, captionY);
    }

    private void drawBoneFigure(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        BoneFigure f = boneFigure();
        String hover = boneAt(mouseX, mouseY);

        // Same left/right as the rendered model: facing the viewer, the player's left
        // hand is on the right of the panel.
        drawBonePart(graphics, f, "head", -4, 0, 4, 8, 0xFFD8A77A, hover);
        drawBonePart(graphics, f, "body", -4, 8, 4, 20, 0xFF41689A, hover);
        drawBonePart(graphics, f, "leftArm", 4, 8, 8, 14, 0xFF41689A, hover);
        drawBonePart(graphics, f, "leftForearm", 4, 14, 8, 20, 0xFFD8A77A, hover);
        drawBonePart(graphics, f, "rightArm", -8, 8, -4, 14, 0xFF41689A, hover);
        drawBonePart(graphics, f, "rightForearm", -8, 14, -4, 20, 0xFFD8A77A, hover);
        drawBonePart(graphics, f, "leftLeg", 0, 20, 4, 26, 0xFF344A66, hover);
        drawBonePart(graphics, f, "leftShin", 0, 26, 4, 32, 0xFF22364F, hover);
        drawBonePart(graphics, f, "rightLeg", -4, 20, 0, 26, 0xFF344A66, hover);
        drawBonePart(graphics, f, "rightShin", -4, 26, 0, 32, 0xFF22364F, hover);

        // Side letters, so "left" means the player's left, not the viewer's.
        int markerY = f.y(12);
        graphics.text(font, Component.literal("L"), f.x(8) + 3, markerY, 0xFF7F889A);
        Component right = Component.literal("R");
        graphics.text(font, right, f.x(-8) - 3 - font.width(right), markerY, 0xFF7F889A);

        boolean capeSelected = selectionVisible && "cape".equals(selected);
        boolean capeHover = "cape".equals(hover);
        graphics.fill(f.chipX0(), f.chipY0(), f.chipX1(), f.chipY1(),
            capeSelected ? 0x6644AA44 : capeHover ? 0x33FFFFFF : 0x55222233);
        int capeBorder = capeSelected ? 0xFF7FD46A
            : capeHover ? 0xFFFFC24A : 0xFF3E4862;
        graphics.fill(f.chipX0(), f.chipY0(), f.chipX1(), f.chipY0() + 1, capeBorder);
        graphics.fill(f.chipX0(), f.chipY1() - 1, f.chipX1(), f.chipY1(), capeBorder);
        graphics.fill(f.chipX0(), f.chipY0(), f.chipX0() + 1, f.chipY1(), capeBorder);
        graphics.fill(f.chipX1() - 1, f.chipY0(), f.chipX1(), f.chipY1(), capeBorder);
        Component cape = itemLabel("cape");
        graphics.text(font, cape, f.cx() - font.width(cape) / 2, f.chipY0() + 3,
            capeSelected || capeHover ? 0xFFFFFFFF : 0xFFCCCCCC);

        // Hover wins so the name under the cursor is readable before clicking.
        String shown = hover != null ? hover : selected;
        Component caption = itemLabel(shown);
        graphics.text(font, caption, f.cx() - font.width(caption) / 2, f.captionY(),
            hover != null ? 0xFFFFC24A : 0xFFCCCCCC);
    }

    private void drawBonePart(GuiGraphicsExtractor graphics, BoneFigure f, String bone,
                              double x0, double y0, double x1, double y1,
                              int argb, String hover) {
        int px0 = f.x(x0), py0 = f.y(y0), px1 = f.x(x1), py1 = f.y(y1);
        graphics.fill(px0, py0, px1, py1, argb);
        boolean isSelected = selectionVisible && bone.equals(selected);
        if (!isSelected && bone.equals(hover)) {
            graphics.fill(px0, py0, px1, py1, 0x40FFFFFF);
        }
        if (isSelected) {
            graphics.fill(px0, py0, px1, py1, 0x6644AA44);
            graphics.fill(px0, py0, px1, py0 + 1, 0xFF7FD46A);
            graphics.fill(px0, py1 - 1, px1, py1, 0xFF7FD46A);
            graphics.fill(px0, py0, px0 + 1, py1, 0xFF7FD46A);
            graphics.fill(px1 - 1, py0, px1, py1, 0xFF7FD46A);
        }
    }

    /** Bone under the cursor on the Bones tab, or null. */
    private String boneAt(double mouseX, double mouseY) {
        if (listTab != 0) return null;
        BoneFigure f = boneFigure();

        if (mouseX >= f.chipX0() && mouseX < f.chipX1()
            && mouseY >= f.chipY0() && mouseY < f.chipY1()) {
            return "cape";
        }
        if (inFigure(f, mouseX, mouseY, -4, 0, 4, 8)) return "head";
        if (inFigure(f, mouseX, mouseY, -4, 8, 4, 20)) return "body";
        if (inFigure(f, mouseX, mouseY, 4, 8, 8, 14)) return "leftArm";
        if (inFigure(f, mouseX, mouseY, 4, 14, 8, 20)) return "leftForearm";
        if (inFigure(f, mouseX, mouseY, -8, 8, -4, 14)) return "rightArm";
        if (inFigure(f, mouseX, mouseY, -8, 14, -4, 20)) return "rightForearm";
        if (inFigure(f, mouseX, mouseY, 0, 20, 4, 26)) return "leftLeg";
        if (inFigure(f, mouseX, mouseY, 0, 26, 4, 32)) return "leftShin";
        if (inFigure(f, mouseX, mouseY, -4, 20, 0, 26)) return "rightLeg";
        if (inFigure(f, mouseX, mouseY, -4, 26, 0, 32)) return "rightShin";
        return null;
    }

    private static boolean inFigure(BoneFigure f, double x, double y,
                                    double x0, double y0, double x1, double y1) {
        return x >= f.x(x0) && x < f.x(x1) && y >= f.y(y0) && y < f.y(y1);
    }

    private void drawTabs(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        int tabW = (LEFT_W - TAB_GAP) / 2;
        boolean enabled = PlayerPoseClient.arePosesEnabled();
        for (int tab = 0; tab < 2; tab++) {
            int x = LEFT_X + tab * (tabW + TAB_GAP);
            boolean active = listTab == tab;
            boolean hover = isOver(mouseX, mouseY, x, TAB_Y, tabW, TAB_H);
            int bg = active ? 0x6644AA44 : hover ? 0x33FFFFFF : 0x33000000;
            graphics.fill(x, TAB_Y, x + tabW, TAB_Y + TAB_H, bg);

            Component label = Component.translatable(tab == 0
                ? "playerpose.bonesTab" : "playerpose.posesTab");
            int color = active ? 0xFFFFFFFF
                : tab == 1 && !enabled ? 0xFFFFC24A : 0xFFCCCCCC;
            graphics.text(font, label, x + (tabW - font.width(label)) / 2, TAB_Y + 4, color);
        }
        separator(graphics, LEFT_X - 1, LEFT_X + LEFT_W + 1, TAB_Y + TAB_H);
    }

    /** Tab index under the cursor, or -1. */
    private int tabAt(double x, double y) {
        if (y < TAB_Y || y >= TAB_Y + TAB_H) return -1;
        int tabW = (LEFT_W - TAB_GAP) / 2;
        if (x >= LEFT_X && x < LEFT_X + tabW) return 0;
        if (x >= LEFT_X + tabW + TAB_GAP && x < LEFT_X + LEFT_W) return 1;
        return -1;
    }

    /** Display name for a left-bar row. */
    private Component itemLabel(String row) {
        return Component.translatable("playerpose.bone." + row);
    }

    private void panel(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
        graphics.fill(x0, y0, x1, y1, 0x66000000);
        graphics.fill(x0, y0, x1, y0 + 1, 0xFF3E4862);
        graphics.fill(x0, y1 - 1, x1, y1, 0xFF3E4862);
        graphics.fill(x0, y0, x0 + 1, y1, 0xFF3E4862);
        graphics.fill(x1 - 1, y0, x1, y1, 0xFF3E4862);
    }

    private void separator(GuiGraphicsExtractor graphics, int x0, int x1, int y) {
        graphics.fill(x0, y, x1, y + 1, 0x55FFFFFF);
    }

    /** Number of rows that fit in the combined list above the status bar. */
    private int listWindow() {
        int bottom = height - STATUS_H - 4;
        return Math.max(0, (bottom - LIST_Y) / ROW_H);
    }

    /** Rows of the active tab; the Bones tab is a mannequin, not a list. */
    private int listTotalRows() {
        return listTab == 0 ? 0 : savedPoseNames.size();
    }

    private int maxListScroll() {
        return Math.max(0, listTotalRows() - listWindow());
    }

    /**
     * Draw text that is wider than its slot as a slow marquee instead of cutting
     * off the end. The scissor keeps it inside the hint / status strip.
     */
    private static void drawMarquee(GuiGraphicsExtractor graphics, Font font, String text,
                                    int x, int y, int maxWidth, int color) {
        int textW = font.width(text);
        if (textW <= maxWidth) {
            graphics.text(font, Component.literal(text), x, y, color);
            return;
        }
        int gap = 24;
        int cycle = textW + gap;
        int offset = (int) ((System.currentTimeMillis() / 35L) % cycle);
        graphics.enableScissor(x, y - 1, x + maxWidth, y + font.lineHeight + 1);
        graphics.text(font, Component.literal(text), x - offset, y, color);
        graphics.text(font, Component.literal(text), x - offset + cycle, y, color);
        graphics.disableScissor();
    }

    /** Which compatibility warning is currently in the rotating slot. */
    private int compatWarningIndex() {
        if (compatWarnings.isEmpty()) return 0;
        return (int) ((System.currentTimeMillis() / 3000L) % compatWarnings.size());
    }

    private void drawStatusBar(GuiGraphicsExtractor graphics, Font font) {
        int y = height - STATUS_H + 5;
        if (!statusMsg.isEmpty() && System.currentTimeMillis() < statusUntil) {
            drawMarquee(graphics, font, statusMsg, LEFT_X, y, width - 16, 0xFFFFFF88);
        } else if (!compatWarnings.isEmpty()) {
            int index = compatWarningIndex();
            String warn = "[!] " + compatWarnings.get(index).getString();
            if (compatWarnings.size() > 1) {
                warn += "  (" + (index + 1) + "/" + compatWarnings.size() + ")";
            }
            drawMarquee(graphics, font, warn, LEFT_X, y, width - 16,
                warnBlink() ? 0xFFFFC24A : 0xFF7A5A1A);
        } else {
            String hint = Component.translatable("playerpose.hint").getString();
            drawMarquee(graphics, font, hint, LEFT_X, y, width - 16, 0xFF888888);
        }
    }

    /** Slow attention blink for compatibility warnings. */
    private static boolean warnBlink() {
        return (System.currentTimeMillis() / 450L) % 2L == 0L;
    }

    private static boolean isOver(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private boolean inViewport(double x, double y) {
        return x >= vpX0 && x < vpX1 && y >= vpY0 && y < vpY1;
    }

    private boolean inList(double x, double y) {
        return x >= LEFT_X && x < LEFT_X + LEFT_W
            && y >= LIST_Y && y < height - STATUS_H - 4;
    }

    /** Combined-list row index under the cursor, or -1. */
    private int listRowAt(double x, double y) {
        if (!inList(x, y)) return -1;
        int index = listScroll + (int) ((y - LIST_Y) / ROW_H);
        return index >= 0 && index < listTotalRows() ? index : -1;
    }

    // ---- input -----------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (skinPanelOpen) {
            layoutSkinPanel();
            int mx = (int) event.x();
            int my = (int) event.y();
            if (skinBrowseRect != null && skinBrowseRect.contains(mx, my)) {
                browseSkinFile();
                return true;
            }
            if (skinLoadRect != null && skinLoadRect.contains(mx, my)) {
                loadSkinInput();
                return true;
            }
            if (skinModelRect != null && skinModelRect.contains(mx, my)) {
                toggleSkinModel();
                return true;
            }
            if (skinOriginalRect != null && skinOriginalRect.contains(mx, my)) {
                restoreOriginalSkin();
                return true;
            }
            if (skinCloseRect != null && skinCloseRect.contains(mx, my)) {
                closeSkinPanel();
                return true;
            }
            if (skinPanelRect != null && skinPanelRect.contains(mx, my)) {
                // Let the EditBox handle its own clicks.
                return super.mouseClicked(event, doubleClick);
            }
            return true;
        }
        if (nameDialogOpen) {
            nameDialogLayout();
            int mx = (int) event.x();
            int my = (int) event.y();
            if (isOver(mx, my, nameDialogOkX0, nameDialogOkY0,
                nameDialogOkX1 - nameDialogOkX0, CTRL_H)) {
                confirmNameDialog();
                return true;
            }
            if (isOver(mx, my, nameDialogCancelX0, nameDialogCancelY0,
                nameDialogCancelX1 - nameDialogCancelX0, CTRL_H)) {
                closeNameDialog();
                return true;
            }
            if (mx >= nameDialogX0 && mx < nameDialogX1
                && my >= nameDialogY0 && my < nameDialogY1) {
                return true; // inside the dialog: keep it open
            }
            closeNameDialog();
            return true;
        }
        if (loadMenuOpen) {
            int mx = (int) event.x();
            int my = (int) event.y();
            if (isOver(mx, my, loadMenuX0 + 1, loadMenuAllY0,
                loadMenuX1 - loadMenuX0 - 2, LOAD_MENU_ROW_H)) {
                toggleAllTargets();
                return true;
            }
            if (isOver(mx, my, loadMenuCloseX0, loadMenuCloseY0,
                loadMenuCloseX1 - loadMenuCloseX0, loadMenuCloseY1 - loadMenuCloseY0)) {
                loadMenuOpen = false;
                return true;
            }
            int row = loadMenuRowAt(mx, my);
            if (row >= 0) {
                toggleTarget(loadTargets.get(row));
                return true;
            }
            if (mx >= loadMenuX0 && mx < loadMenuX1
                && my >= loadMenuY0 && my < loadMenuY1) {
                return true; // title / scrollbar / padding
            }
            loadMenuOpen = false;
            return true;
        }

        // The name bar is read-only; clicking it opens the rename dialog.
        if (isOver((int) event.x(), (int) event.y(), LEFT_X, 6, LEFT_W, CTRL_H)) {
            if (!editingPose) {
                setStatus(Component.translatable("playerpose.status.pressNew"));
            } else {
                openNameDialog(false);
            }
            return true;
        }

        // Editor-only skin preview button, top-right of the viewport.
        if (skinButtonRect != null && skinButtonRect.contains(event.x(), event.y())) {
            openSkinPanel();
            return true;
        }

        // Never let a previous drag state swallow this click.
        int previousAxis = activeAxis;
        if (!orbiting && previousAxis != PoseGizmo.NONE && !inViewport(event.x(), event.y())) {
            activeAxis = PoseGizmo.NONE;
        }
        orbiting = false;

        int tab = tabAt(event.x(), event.y());
        if (tab >= 0) {
            setTab(tab);
            return true;
        }

        if (listTab == 0) {
            if (!editingPose) {
                if (boneAt(event.x(), event.y()) != null || inList(event.x(), event.y())) {
                    setStatus(Component.translatable("playerpose.status.pressNew"));
                    return true;
                }
            }
            String bone = boneAt(event.x(), event.y());
            if (bone != null) {
                activeAxis = PoseGizmo.NONE;
                // Selecting a bone brings the frame and gizmo back automatically.
                selectionVisible = true;
                gizmoVisible = true;
                if (!bone.equals(selected)) selectRow(bone);
                return true;
            }
            if (inList(event.x(), event.y())) {
                // Empty space in the Bone figure clears the visual selection. The
                // pose data is untouched; clicking any bone selects it again.
                gizmoVisible = false;
                selectionVisible = false;
                activeAxis = PoseGizmo.NONE;
                setStatus(Component.translatable("playerpose.status.gizmoHidden"));
                return true;
            }
        } else {
            int listRow = listRowAt(event.x(), event.y());
            if (listRow >= 0) {
                activeAxis = PoseGizmo.NONE;
                if (listRow < savedPoseNames.size()) {
                    int rowY = LIST_Y + (listRow - listScroll) * ROW_H;
                    int plusX0 = LEFT_X + LEFT_W - 14;
                    if (event.x() >= plusX0 && event.x() < LEFT_X + LEFT_W - 4
                        && event.y() >= rowY && event.y() < rowY + ROW_H) {
                        openLoadMenu(savedPoseNames.get(listRow));
                    } else {
                        selectPose(savedPoseNames.get(listRow));
                    }
                }
                return true;
            }
        }

        if (inViewport(event.x(), event.y())) {
            if (event.button() == 2) {
                // middle mouse orbits, like most 3D viewers
                activeAxis = PoseGizmo.NONE;
                orbiting = true;
                return true;
            }
            if (event.button() == 0) {
                int hit = PoseGizmo.NONE;
                PoseGizmo probe = null;
                if (gizmoVisible) {
                    probe = buildGizmo(null, PoseGizmo.NONE);
                    hit = probe.hitTest(event.x(), event.y());
                }
                if (hit != PoseGizmo.NONE && probe != null) {
                    activeAxis = hit;
                    dragStartAngle = PoseGizmo.angleAt(event.x(), event.y(), probe.centerX(), probe.centerY());
                    dragStartMouseX = event.x();
                    dragStartMouseY = event.y();
                    dragStartValue = moveMode ? translationFor(hit) : rotationFor(hit);
                    dragSnapshot = snapshot();
                } else {
                    activeAxis = PoseGizmo.NONE;
                    orbiting = true;
                }
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (skinPanelOpen) return true;
        if (nameDialogOpen) return true;
        if (activeAxis != PoseGizmo.NONE) {
            if (moveMode) {
                PoseGizmo gizmo = buildGizmo(null, PoseGizmo.NONE);
                double[] dir = gizmo.axisScreenDir(activeAxis);
                if (dir == null) return true; // axis points at the camera
                double pxPerUnit = modelPxPerBlock() / 16.0;
                if (pxPerUnit <= 0.0) return true;
                double units = ((event.x() - dragStartMouseX) * dir[0]
                    + (event.y() - dragStartMouseY) * dir[1]) / pxPerUnit;
                setTranslationFor(activeAxis, dragStartValue + units);
            } else {
                PoseGizmo gizmo = buildGizmo(null, PoseGizmo.NONE);
                double now = PoseGizmo.angleAt(event.x(), event.y(), gizmo.centerX(), gizmo.centerY());
                double delta = PoseGizmo.wrapAngle(now - dragStartAngle)
                    * gizmo.rotationSign(activeAxis);
                setRotationFor(activeAxis, dragStartValue + delta);
            }
            return true;
        }
        if (orbiting) {
            // Follow the drag: moving the empty viewport right/down turns the scene
            // right/down instead of inverting both axes.
            camYaw = Math.max(-89.0, Math.min(89.0, camYaw - dragX * 0.6));
            camPitch = Math.max(-45.0, Math.min(45.0, camPitch - dragY * 0.5));
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        // A drag becomes one undo step, and only if it actually changed something.
        if (dragSnapshot != null) {
            if (changedSince(dragSnapshot)) pushUndo(dragSnapshot);
            dragSnapshot = null;
        }
        if (activeAxis != PoseGizmo.NONE || orbiting) {
            activeAxis = PoseGizmo.NONE;
            orbiting = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (skinPanelOpen) return true;
        if (nameDialogOpen) return true;
        if (loadMenuOpen) {
            if (mouseX >= loadMenuX0 && mouseX < loadMenuX1
                && mouseY >= loadMenuY0 && mouseY < loadMenuY1) {
                int step = scrollY < 0 ? 1 : scrollY > 0 ? -1 : 0;
                loadMenuScroll = Math.max(0, Math.min(loadMenuScroll + step,
                    loadMenuMaxScroll()));
            } else {
                loadMenuOpen = false;
            }
            return true;
        }
        if (inViewport(mouseX, mouseY)) {
            camZoom = Math.max(0.4, Math.min(2.5, camZoom * (1.0 + scrollY * 0.1)));
            return true;
        }
        // The wheel over the saved-pose list walks the list. Without this, poses past
        // the bottom of the panel were visible in the list but impossible to select.
        if (inList(mouseX, mouseY) && maxListScroll() > 0) {
            int step = scrollY < 0 ? 1 : scrollY > 0 ? -1 : 0;
            listScroll = Math.max(0, Math.min(listScroll + step, maxListScroll()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (skinPanelOpen) {
            return super.charTyped(event);
        }
        if (nameDialogOpen) {
            int cp = event.codepoint();
            if (cp >= 32
                && nameDialogText.codePointCount(0, nameDialogText.length()) < 32) {
                nameDialogText += new String(Character.toChars(cp));
            }
            return true;
        }
        return super.charTyped(event);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (skinPanelOpen) {
            if (event.key() == 256) { // ESC closes the skin panel, not the editor
                closeSkinPanel();
                return true;
            }
            if (event.key() == 257 || event.key() == 335) { // ENTER / keypad ENTER
                loadSkinInput();
                return true;
            }
            return super.keyPressed(event);
        }
        if (nameDialogOpen) {
            if (event.key() == 256) { // ESC cancels
                closeNameDialog();
                return true;
            }
            if (event.key() == 257 || event.key() == 335) { // ENTER / keypad ENTER
                confirmNameDialog();
                return true;
            }
            if (event.key() == 259 && !nameDialogText.isEmpty()) { // BACKSPACE
                nameDialogText = nameDialogText.substring(0,
                    nameDialogText.offsetByCodePoints(nameDialogText.length(), -1));
            }
            return true;
        }
        if (loadMenuOpen) {
            if (event.key() == 256) { // ESC closes only the chooser, not the editor
                loadMenuOpen = false;
                return true;
            }
            return true;
        }
        boolean ctrl = (event.modifiers() & 2) != 0; // GLFW_MOD_CONTROL
        if (!editingPose && event.key() != 256) {
            return super.keyPressed(event);
        }
        if (ctrl) {
            switch (event.key()) {
                case 90 -> { // Z: undo, or redo with shift
                    if ((event.modifiers() & 1) != 0) redo(); else undo();
                    return true;
                }
                case 89 -> { // Y: redo
                    redo();
                    return true;
                }
                default -> { }
            }
            return super.keyPressed(event);
        }
        switch (event.key()) {
            case 82 -> { // R
                setMode(false);
                return true;
            }
            case 71 -> { // G
                setMode(true);
                return true;
            }
            case 49, 50, 51, 52, 53, 54, 55, 56, 57 -> { // 1..9 select a left-bar row
                int idx = event.key() - 49;
                if (idx < ROWS.size() && !ROWS.get(idx).equals(selected)) {
                    selectRow(ROWS.get(idx));
                }
                return true;
            }
            default -> { }
        }
        // Screen#keyPressed closes on ESC when shouldCloseOnEsc() is true.
        return super.keyPressed(event);
    }

    private boolean cleanedUp;

    /**
     * Clear the editor's session state. Called from both onClose (ESC) and removed
     * (keybind close, another screen replacing this one, world unload), because those
     * paths do not all go through onClose.
     */
    private void cleanupEditor() {
        if (cleanedUp) return;
        cleanedUp = true;
        PoseLog.info("editor close: working='%s' active=%s",
            workingPose == null || workingPose.getName() == null || workingPose.getName().isBlank()
                ? "(unsaved)" : workingPose.getName(),
            PlayerPoseClient.getActivePoseName() == null ? "none" : "'" + PlayerPoseClient.getActivePoseName() + "'");
        PlayerPoseClient.setEditorPreviewPose(null);
        PlayerPoseClient.setPoseEditorOpen(false);
        PreviewSkinManager.setActive(false);
        PoseLog.dumpPoses(poseManager, "editor close");
    }

    @Override
    public void removed() {
        super.removed();
        cleanupEditor();
    }

    @Override
    public void onClose() {
        super.onClose(); // Minecraft#setScreen(null) -> removed() in the normal path
        cleanupEditor();
        // Tell the player what is now being applied to their model.
        PlayerPoseClient.announceAppliedPose();
    }
}
