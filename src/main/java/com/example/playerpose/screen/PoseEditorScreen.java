package com.example.playerpose.screen;

import com.example.playerpose.PlayerPoseClient;
import com.example.playerpose.pose.Pose;
import com.example.playerpose.pose.PoseBone;
import com.example.playerpose.pose.PoseLog;
import com.example.playerpose.pose.PoseManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    private EditBox nameBox;
    private Button newBtn;
    private Button resetBtn;
    private Button resetAllBtn;
    private Button deleteBtn;
    private Button loadBtn;
    private Button unloadBtn;
    private Button rotateBtn;
    private Button moveBtn;
    /** Bounds of the border drawn around the two mode buttons. */
    private int modeGroupX0, modeGroupY0, modeGroupX1, modeGroupY1;

    private String statusMsg = "";
    private long statusUntil;
    private List<String> savedPoseNames = new ArrayList<>();
    /** 0 = bone list, 1 = saved-pose list. */
    private int listTab;
    /** Scroll offset of the active tab's list, in rows. */
    private int listScroll;

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
        if (existing != null) {
            // Edit a copy: the manager keeps the last saved pose until Save is pressed,
            // so closing the editor without saving cannot silently apply draft edits.
            this.workingPose = PoseManager.copy(existing);
        } else {
            this.workingPose = new Pose();
            this.workingPose.setName("");
            this.workingPose.setSkin(skinStr);
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
        if (loadBtn != null) loadBtn.active = hasWorkingPose;
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
        if (nameBox != null) nameBox.setValue(p.getName() == null ? "" : p.getName());
        selected = "body";
        syncFromPose();
        refreshPoseList();
        setTab(1);
        ensureWorkingPoseVisible();
        setStatus(Component.translatable("playerpose.status.selected", name));
        String active = PlayerPoseClient.getActivePoseName();
        PoseLog.info("list: selected '%s' (editing only; loaded=%s)", name,
            active == null ? "none" : "'" + active + "'");
    }

    /** Bind the pose shown in the editor to this skin and make it the active one. */
    private void loadPose() {
        if (workingPose == null || workingPose.getName() == null || workingPose.getName().isBlank()) {
            setStatus(Component.translatable("playerpose.status.noPoseToLoad"));
            return;
        }
        lastDeletedName = null;
        commit();
        String name = workingPose.getName();
        workingPose.setSkin(skin);
        poseManager.bind(workingPose);
        if (!PlayerPoseClient.arePosesEnabled()) {
            PlayerPoseClient.setPosesEnabled(true);
            refreshUnloadButton();
        }
        PlayerPoseClient.setActivePoseName(name);
        PlayerPoseClient.setEditorPreviewPose(workingPose);
        refreshPoseList();
        setStatus(Component.translatable("playerpose.status.loaded", name));
        PoseLog.info("load: '%s' is now active for skin '%s'", name, skin);
    }

    // ---- actions ---------------------------------------------------------

    private void savePose() {
        String name = nameBox == null ? "" : nameBox.getValue().trim();
        if (name.isEmpty()) {
            setStatus(Component.translatable("playerpose.status.nameEmpty"));
            return;
        }
        // A successful save is a commit point: it also drops any pending "redo this
        // delete" state.
        lastDeletedName = null;
        commit();

        // Editing the name box renames: the old file is removed rather than leaving a
        // second copy behind under the old name.
        String oldName = workingPose.getName();
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

    /** Start a fresh, unsaved pose. Nothing is applied until it is saved. */
    private void newPose() {
        beginEdit();
        workingPose = new Pose();
        workingPose.setName("");
        workingPose.setSkin(skin);
        workingPose.setBones(new ArrayList<>());
        if (nameBox != null) nameBox.setValue("");
        PlayerPoseClient.setEditorPreviewPose(workingPose);
        // A new draft does not unload whatever pose is currently in effect.
        selected = "body";
        setTab(0);
        listScroll = 0;
        moveMode = false;
        refreshModeButtons();
        syncFromPose();
        refreshPoseList();
        setStatus(Component.translatable("playerpose.status.newPose"));
        PoseLog.info("new: blank pose started (nothing applied until saved)");
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
        if (nameBox != null) nameBox.setValue("");
        selectRow("body");
        refreshPoseList();
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
        if (nameBox != null) {
            nameBox.setValue(workingPose.getName() == null ? "" : workingPose.getName());
        }
        syncFromPose();
        // Redoing the delete of a pose: remove the copy that undoing it re-saved. Keep
        // the name on the blank state so a later redo does the same again.
        String deleteOnRestore = snap.deleteOnRestore();
        boolean blank = workingPose.getName() == null || workingPose.getName().isBlank();
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
        return Component.translatable(PlayerPoseClient.arePosesEnabled()
            ? "playerpose.unloadBtn" : "playerpose.applyBtn");
    }

    /**
     * Unload poses (character goes back to its vanilla pose, files untouched) or
     * re-apply them, which re-reads the pose files from disk.
     */
    private void toggleUnload() {
        lastDeletedName = null;
        if (PlayerPoseClient.arePosesEnabled()) {
            PlayerPoseClient.setPosesEnabled(false);
            setStatus(Component.translatable("playerpose.status.unloaded"));
        } else {
            poseManager.load();
            PlayerPoseClient.setPosesEnabled(true);
            refreshPoseList();
            setStatus(Component.translatable("playerpose.status.applied"));
        }
        refreshUnloadButton();
    }

    private void refreshUnloadButton() {
        if (unloadBtn == null) return;
        unloadBtn.setMessage(unloadLabel());
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

        nameBox = new EditBox(font, LEFT_X, 6, LEFT_W, CTRL_H, Component.empty());
        nameBox.setHint(Component.translatable("playerpose.poseNameHint"));
        nameBox.setMaxLength(32);
        nameBox.setValue(workingPose != null && workingPose.getName() != null ? workingPose.getName() : "");
        // No live rename here: the box is a rename field, applied when Save is pressed,
        // so typing a new name cannot detach the pose from its file.
        addRenderableWidget(nameBox);

        // Left sidebar: Save and Done are common; the active tab swaps the other four
        // buttons (Rotate/Move/Reset/Reset All on Bones, New/Delete/Unload on Poses).
        int col2 = LEFT_X + BTN_W + BTN_GAP;

        addRenderableWidget(Button.builder(Component.translatable("playerpose.saveBtn"), b -> savePose())
            .bounds(LEFT_X, BTN_Y, BTN_W, CTRL_H).build());
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
        // Load applies the selected pose without navigating away from the list.
        loadBtn = Button.builder(Component.translatable("playerpose.loadBtn"), b -> loadPose())
            .bounds(LEFT_X, BTN_Y + BTN_ROW_H * 2, BTN_W, CTRL_H).build();
        unloadBtn = Button.builder(unloadLabel(), b -> toggleUnload())
            .bounds(col2, BTN_Y + BTN_ROW_H * 2, BTN_W, CTRL_H).build();
        addRenderableWidget(newBtn);
        addRenderableWidget(deleteBtn);
        addRenderableWidget(loadBtn);
        addRenderableWidget(unloadBtn);
        refreshUnloadButton();

        updateActionButtons();

        vpX0 = LEFT_X + LEFT_W + 8;
        vpY0 = TOP_H;
        vpX1 = Math.max(vpX0 + 80, width - 8);
        vpY1 = Math.max(vpY0 + 80, height - STATUS_H - 4);

        PlayerPoseClient.setEditorPreviewPose(workingPose);
        refreshPoseList();
    }

    /** Save/Done stay; the other four actions follow the active list tab. */
    private void updateActionButtons() {
        boolean bones = listTab == 0;
        if (rotateBtn != null) rotateBtn.visible = bones;
        if (moveBtn != null) moveBtn.visible = bones;
        if (resetBtn != null) resetBtn.visible = bones;
        if (resetAllBtn != null) resetAllBtn.visible = bones;
        if (newBtn != null) newBtn.visible = !bones;
        if (deleteBtn != null) deleteBtn.visible = !bones;
        if (loadBtn != null) loadBtn.visible = !bones;
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

        // Keeps the mode button honest no matter how the mode changed.
        refreshModeButtons();
        refreshUnloadButton();

        drawViewport(graphics, mouseX, mouseY, partialTick);
        drawTopInfo(graphics, font);
        drawList(graphics, font, mouseX, mouseY);
        drawStatusBar(graphics, font);

        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        // On top of the widgets, so the group border and the mode marker stay visible.
        drawModeGroup(graphics);
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
        int hoverAxis = buildGizmo(graphics, PoseGizmo.NONE).hitTest(mouseX, mouseY);
        buildGizmo(graphics, hoverAxis).draw();
        graphics.disableScissor();

        // The model owns the viewport; the read-out lives in the top strip instead of
        // painting over the head.
        graphics.fill(vpX0, vpY1 - 14, vpX1, vpY1, 0x66000000);
        String hint = font.plainSubstrByWidth(
            Component.translatable("playerpose.viewportHint").getString(), vpX1 - vpX0 - 8);
        graphics.text(font, Component.literal(hint), vpX0 + 4, vpY1 - 12, 0xFF888899);
    }

    /** Selected bone, mode, unsaved badge and axis read-out, above the viewport. */
    private void drawTopInfo(GuiGraphicsExtractor graphics, Font font) {
        panel(graphics, vpX0, INFO_Y, vpX1, INFO_Y + INFO_H);
        int maxW = vpX1 - vpX0 - 8;

        Component mode = Component.translatable(moveMode ? "playerpose.modeMove" : "playerpose.modeRotate");
        String selectedLine = itemLabel(selected).getString() + "  [" + mode.getString() + "]";
        graphics.text(font, Component.literal(font.plainSubstrByWidth(selectedLine, maxW)),
            vpX0 + 4, INFO_Y + 3, 0xFFFFFFFF);

        if (workingPose == null || workingPose.getName() == null || workingPose.getName().isBlank()) {
            Component unsaved = Component.translatable("playerpose.unsavedBadge");
            if (font.width(selectedLine) + font.width(unsaved) + 16 < maxW) {
                graphics.text(font, unsaved, vpX1 - 4 - font.width(unsaved),
                    INFO_Y + 3, 0xFFFFC24A);
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

        for (int k = 0; k < rows; k++) {
            int index = listScroll + k;
            if (index >= listTotalRows()) break;
            int yy = LIST_Y + k * ROW_H;

            String name = savedPoseNames.get(index);
            String activeName = PlayerPoseClient.getActivePoseName();
            // Blue shading = the pose that is actually loaded. Amber frame = the pose
            // the editor is showing. They can be different, and both can share a row.
            boolean inEffect = enabled && name.equals(activeName);
            boolean isSelected = workingPose != null && name.equals(workingPose.getName());
            boolean hover = isOver(mouseX, mouseY, LEFT_X, yy, LEFT_W, ROW_H);

            if (inEffect) {
                graphics.fill(LEFT_X, yy, LEFT_X + LEFT_W, yy + ROW_H, 0x664444AA);
            } else if (hover) {
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
            int textColor = inEffect || isSelected ? 0xFFFFFFFF
                : enabled ? 0xFFCCCCCC : 0xFF8891A0;
            graphics.text(font, Component.literal(font.plainSubstrByWidth(name, LEFT_W - 8)),
                LEFT_X + 4, yy + 3, textColor);
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

        boolean capeSelected = "cape".equals(selected);
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
        boolean isSelected = bone.equals(selected);
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

    private void drawStatusBar(GuiGraphicsExtractor graphics, Font font) {
        int y = height - STATUS_H + 5;
        if (!statusMsg.isEmpty() && System.currentTimeMillis() < statusUntil) {
            graphics.text(font, Component.literal(font.plainSubstrByWidth(statusMsg, width - 16)),
                LEFT_X, y, 0xFFFFFF88);
        } else {
            String hint = font.plainSubstrByWidth(
                Component.translatable("playerpose.hint").getString(), width - 16);
            graphics.text(font, Component.literal(hint), LEFT_X, y, 0xFF888888);
        }
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
            String bone = boneAt(event.x(), event.y());
            if (bone != null) {
                activeAxis = PoseGizmo.NONE;
                if (!bone.equals(selected)) selectRow(bone);
                return true;
            }
        } else {
            int listRow = listRowAt(event.x(), event.y());
            if (listRow >= 0) {
                activeAxis = PoseGizmo.NONE;
                if (listRow < savedPoseNames.size()) {
                    selectPose(savedPoseNames.get(listRow));
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
                PoseGizmo probe = buildGizmo(null, PoseGizmo.NONE);
                int hit = probe.hitTest(event.x(), event.y());
                if (hit != PoseGizmo.NONE) {
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
                double delta = PoseGizmo.wrapAngle(now - dragStartAngle);
                setRotationFor(activeAxis, dragStartValue + delta);
            }
            return true;
        }
        if (orbiting) {
            camYaw = Math.max(-89.0, Math.min(89.0, camYaw + dragX * 0.6));
            camPitch = Math.max(-45.0, Math.min(45.0, camPitch + dragY * 0.5));
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
    public boolean keyPressed(KeyEvent event) {
        boolean ctrl = (event.modifiers() & 2) != 0; // GLFW_MOD_CONTROL
        if (!ctrl && nameBox != null && nameBox.isFocused()) return super.keyPressed(event);
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
