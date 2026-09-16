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
 * Left bar selects what you are editing: the six bones, plus "cursor" - the pivot
 * that rotations happen around, editable the same way as everything else.
 *
 * Two modes, like Blender's G and R:
 *   ROTATE - drag a ring to rotate the selected bone about the cursor
 *            (cursor at 0 = rotate about the bone's own joint).
 *   MOVE   - drag a ring to translate the selected bone along that axis, or to
 *            move the cursor itself.
 *
 * G / R switch mode, right-click in the viewport toggles, 1-7 pick a row from the
 * left bar, drag empty viewport space to orbit, scroll to zoom.
 *
 * Rotations are radians in PoseBone; translations are model units (16 per block),
 * which is ModelPart's own space. The mixin applies both.
 */
public class PoseEditorScreen extends Screen {

    private static final int TITLE_H = 40;
    private static final int STATUS_H = 18;
    private static final int LEFT_X = 8;
    private static final int LEFT_W = 140;
    private static final int RIGHT_X = 160;
    private static final int ROW_H = 14;
    private static final int CTRL_H = 20;
    private static final double MAX_ANGLE = Math.PI;
    private static final double MAX_TRANSLATE = 32.0; // model units, 2 blocks

    private static final String CURSOR = "cursor";

    private static final List<String> ROWS = List.of(
        "body", "head",
        "leftArm", "leftForearm", "rightArm", "rightForearm",
        "leftLeg", "leftShin", "rightLeg", "rightShin",
        "cape", CURSOR
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
        {5.0F, 6.0F, 0.0F},    // leftForearm (elbow, arm seam at +4)
        {-5.0F, 2.0F, 0.0F},   // rightArm
        {-5.0F, 6.0F, 0.0F},   // rightForearm (elbow)
        {1.9F, 12.0F, 0.0F},   // leftLeg
        {1.9F, 18.0F, 0.0F},   // leftShin (knee)
        {-1.9F, 12.0F, 0.0F},  // rightLeg
        {-1.9F, 18.0F, 0.0F},  // rightShin (knee)
        {0.0F, 0.0F, 2.0F},    // cape
        {0.0F, 0.0F, 0.0F}     // cursor (anchored on the model origin)
    };

    private static final int BONE_Y0 = TITLE_H + 4;
    private static final int POSE_LABEL_Y = BONE_Y0 + ROWS.size() * ROW_H + 8;
    private static final int POSE_Y0 = POSE_LABEL_Y + ROW_H;

    private final PoseManager poseManager;
    private final String skin;
    private Pose workingPose;
    private String selected = "body";
    private boolean moveMode;

    // Working values for the selected bone
    private double rotX, rotY, rotZ;            // radians
    private double tX, tY, tZ;                  // model units
    // Working cursor (pivot) offset, model units
    private double cX, cY, cZ;

    private EditBox nameBox;
    private Button deleteBtn;
    private Button unloadBtn;
    private Button rotateBtn;
    private Button moveBtn;
    /** Bounds of the border drawn around the two mode buttons. */
    private int modeGroupX0, modeGroupY0, modeGroupX1, modeGroupY1;

    private String statusMsg = "";
    private long statusUntil;
    private List<String> savedPoseNames = new ArrayList<>();
    /** Index of the first saved pose shown; the list is longer than the panel. */
    private int poseScroll;

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
    private record Snapshot(Pose pose, String selected) {}

    private final java.util.Deque<Snapshot> undoStack = new java.util.ArrayDeque<>();
    private final java.util.Deque<Snapshot> redoStack = new java.util.ArrayDeque<>();
    /** Snapshot taken when a ring drag starts, committed only if the drag changed something. */
    private Snapshot dragSnapshot;

    public PoseEditorScreen(PoseManager mgr, Pose existing, String skinStr) {
        super(Component.translatable("screen.playerpose.editor"));
        this.poseManager = mgr;
        this.skin = skinStr;
        if (existing != null) {
            this.workingPose = existing;
        } else {
            this.workingPose = new Pose();
            this.workingPose.setName("");
            this.workingPose.setSkin(skinStr);
        }
        syncFromPose();
    }

    // ---- pose <-> editor state -------------------------------------------

    private boolean cursorSelected() { return CURSOR.equals(selected); }

    private void syncFromPose() {
        PoseBone bone = findBone(selected);
        if (bone != null) {
            rotX = bone.x; rotY = bone.y; rotZ = bone.z;
            tX = bone.tx; tY = bone.ty; tZ = bone.tz;
        } else {
            rotX = 0; rotY = 0; rotZ = 0;
            tX = 0; tY = 0; tZ = 0;
        }
        if (workingPose != null) {
            cX = workingPose.getCursorX();
            cY = workingPose.getCursorY();
            cZ = workingPose.getCursorZ();
        }
    }

    /** Write the working values for the selected row back into the pose. */
    private void commit() {
        if (workingPose == null) return;
        if (cursorSelected()) {
            workingPose.setCursor(cX, cY, cZ);
            return;
        }
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
        selected = row;
        // The cursor is a point: only moving it makes sense.
        moveMode = CURSOR.equals(row);
        refreshModeButtons();
        syncFromPose();
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
        if (cursorSelected()) {
            return switch (axis) {
                case PoseGizmo.AXIS_X -> cX;
                case PoseGizmo.AXIS_Y -> cY;
                default -> cZ;
            };
        }
        return switch (axis) {
            case PoseGizmo.AXIS_X -> tX;
            case PoseGizmo.AXIS_Y -> tY;
            default -> tZ;
        };
    }

    private void setTranslationFor(int axis, double units) {
        double v = Math.max(-MAX_TRANSLATE, Math.min(MAX_TRANSLATE, units));
        if (cursorSelected()) {
            switch (axis) {
                case PoseGizmo.AXIS_X -> cX = v;
                case PoseGizmo.AXIS_Y -> cY = v;
                default -> cZ = v;
            }
        } else {
            switch (axis) {
                case PoseGizmo.AXIS_X -> tX = v;
                case PoseGizmo.AXIS_Y -> tY = v;
                default -> tZ = v;
            }
        }
        commit();
    }

    private void refreshPoseList() {
        savedPoseNames = poseManager == null ? new ArrayList<>() : poseManager.listNames();
        if (poseScroll > maxPoseScroll()) poseScroll = maxPoseScroll();
        if (poseScroll < 0) poseScroll = 0;
        ensureWorkingPoseVisible();
        boolean hasLoadedPose = workingPose != null && workingPose.getName() != null
            && !workingPose.getName().isBlank();
        if (deleteBtn != null) deleteBtn.active = hasLoadedPose;
    }

    /** Scroll the list so the pose being edited is on screen. */
    private void ensureWorkingPoseVisible() {
        if (workingPose == null || workingPose.getName() == null) return;
        int window = poseWindow();
        if (window <= 0) return;
        int idx = savedPoseNames.indexOf(workingPose.getName());
        if (idx < 0) return;
        if (idx < poseScroll) {
            poseScroll = idx;
        } else if (idx >= poseScroll + window) {
            poseScroll = idx - window + 1;
        }
        poseScroll = Math.max(0, Math.min(poseScroll, maxPoseScroll()));
    }

    private void selectPose(String name) {
        Pose p = poseManager.get(name);
        if (p == null) return;
        beginEdit();
        workingPose = p;
        PlayerPoseClient.setEditorPreviewPose(workingPose);
        if (nameBox != null) nameBox.setValue(p.getName() == null ? "" : p.getName());
        selected = "body";
        // Loading a pose makes it this player's pose and drops every other binding, so
        // exactly one pose is ever in effect. Persisted, so it survives a restart.
        workingPose.setSkin(skin);
        poseManager.save(workingPose);
        PlayerPoseClient.setActivePoseName(p.getName());
        syncFromPose();
        refreshPoseList();
        setStatus(Component.translatable("playerpose.status.using", name));
        PoseLog.info("list: selected '%s' (bones=%d, skin now '%s')", name,
            workingPose.getBones() == null ? 0 : workingPose.getBones().size(), skin);
    }

    // ---- actions ---------------------------------------------------------

    private void savePose() {
        String name = nameBox == null ? "" : nameBox.getValue().trim();
        if (name.isEmpty()) {
            setStatus(Component.translatable("playerpose.status.nameEmpty"));
            return;
        }
        commit();

        // Editing the name box renames: the old file is removed rather than leaving a
        // second copy behind under the old name.
        String oldName = workingPose.getName();
        boolean renaming = oldName != null && !oldName.isBlank() && !oldName.equals(name);
        if (renaming) {
            poseManager.delete(oldName);
        }

        workingPose.setName(name);
        workingPose.setSkin(skin);
        poseManager.save(workingPose);
        PlayerPoseClient.setActivePoseName(name);
        // A save is a commit point: the file and the editor agree from here on.
        undoStack.clear();
        redoStack.clear();
        refreshPoseList();
        if (renaming) {
            setStatus(Component.translatable("playerpose.status.renamed", oldName, name));
            PlayerPoseClient.LOGGER.info("Pose renamed: {} -> {} for skin {}", oldName, name, skin);
        } else {
            setStatus(Component.translatable("playerpose.status.saved", name));
            PlayerPoseClient.LOGGER.info("Pose saved: {} for skin {}", name, skin);
        }
    }

    /** Start a fresh, unsaved pose. Nothing is applied until it is saved. */
    private void newPose() {
        beginEdit();
        workingPose = new Pose();
        workingPose.setName("");
        workingPose.setSkin(skin);
        workingPose.setCursor(0.0, 0.0, 0.0);
        workingPose.setBones(new ArrayList<>());
        if (nameBox != null) nameBox.setValue("");
        PlayerPoseClient.setEditorPreviewPose(workingPose);
        // A brand new pose is not in effect until it has been saved.
        PlayerPoseClient.setActivePoseName(null);
        selected = "body";
        moveMode = false;
        refreshModeButtons();
        syncFromPose();
        refreshPoseList();
        setStatus(Component.translatable("playerpose.status.newPose"));
        PoseLog.info("new: blank pose started (nothing applied until saved)");
    }

    /**
     * Clear every bone and the cursor in the working pose, i.e. back to the rest
     * pose. Undoable.
     */
    private void resetAll() {
        beginEdit();
        if (workingPose != null) {
            workingPose.setBones(new ArrayList<>());
            workingPose.setCursor(0.0, 0.0, 0.0);
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
    }

    private Snapshot snapshot() {
        return new Snapshot(PoseManager.copy(workingPose), selected);
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
        // Undoing a delete should bring the pose back to the manager too.
        String name = workingPose.getName();
        if (name != null && !name.isBlank() && poseManager.get(name) == null) {
            poseManager.save(workingPose);
        }
        refreshPoseList();
        refreshModeButtons();
    }

    private Component modeLabel() {
        return Component.translatable(moveMode ? "playerpose.modeMove" : "playerpose.modeRotate");
    }

    /** Rotate / Move are two buttons, not one toggle - clicking either picks that mode. */
    private void setMode(boolean move) {
        if (cursorSelected()) return; // the cursor row is always moved
        if (moveMode == move) return;
        moveMode = move;
        refreshModeButtons();
        setStatus(modeLabel());
    }

    private void refreshModeButtons() {
        if (rotateBtn == null || moveBtn == null) return;
        boolean forced = cursorSelected();
        if (forced) moveMode = true;
        // Both stay clickable so either can be picked; on the cursor row the cursor
        // can only be moved, so Rotate is greyed out instead of silently ignored.
        rotateBtn.active = !forced;
        moveBtn.active = true;
    }

    /** Border around the two mode buttons plus a marker on the mode in use. */
    private void drawModeGroup(GuiGraphicsExtractor graphics) {
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
        if (cursorSelected()) {
            cX = 0; cY = 0; cZ = 0;
        } else {
            rotX = 0; rotY = 0; rotZ = 0;
            tX = 0; tY = 0; tZ = 0;
        }
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
        int y0 = 6;

        nameBox = new EditBox(font, LEFT_X, y0, LEFT_W, CTRL_H, Component.empty());
        nameBox.setHint(Component.translatable("playerpose.poseNameHint"));
        nameBox.setMaxLength(32);
        nameBox.setValue(workingPose != null && workingPose.getName() != null ? workingPose.getName() : "");
        // No live rename here: the box is a rename field, applied when Save is pressed,
        // so typing a new name cannot detach the pose from its file.
        addRenderableWidget(nameBox);

        // Buttons shrink on narrow windows so the whole row of nine always fits.
        int gap = 4;
        int bx = LEFT_X + LEFT_W + 8;
        int btnW = Math.max(30, Math.min(52, (width - bx - 8 - gap * 8) / 9));

        addRenderableWidget(Button.builder(Component.translatable("playerpose.newBtn"), b -> newPose())
            .bounds(bx, y0, btnW, CTRL_H).build());
        addRenderableWidget(Button.builder(Component.translatable("playerpose.saveBtn"), b -> savePose())
            .bounds(bx + (btnW + gap), y0, btnW, CTRL_H).build());
        addRenderableWidget(Button.builder(Component.translatable("playerpose.resetBtn"), b -> resetRow())
            .bounds(bx + (btnW + gap) * 2, y0, btnW, CTRL_H).build());
        addRenderableWidget(Button.builder(Component.translatable("playerpose.resetAllBtn"), b -> resetAll())
            .bounds(bx + (btnW + gap) * 3, y0, btnW, CTRL_H).build());

        deleteBtn = Button.builder(Component.translatable("playerpose.deleteBtn"), b -> deletePose())
            .bounds(bx + (btnW + gap) * 4, y0, btnW, CTRL_H).build();
        addRenderableWidget(deleteBtn);

        unloadBtn = Button.builder(unloadLabel(), b -> toggleUnload())
            .bounds(bx + (btnW + gap) * 5, y0, btnW, CTRL_H).build();
        addRenderableWidget(unloadBtn);
        refreshUnloadButton();

        int groupX = bx + (btnW + gap) * 6;
        rotateBtn = Button.builder(Component.translatable("playerpose.modeRotate"), b -> setMode(false))
            .bounds(groupX, y0, btnW, CTRL_H).build();
        moveBtn = Button.builder(Component.translatable("playerpose.modeMove"), b -> setMode(true))
            .bounds(groupX + btnW + gap, y0, btnW, CTRL_H).build();
        addRenderableWidget(deleteBtn);
        addRenderableWidget(rotateBtn);
        addRenderableWidget(moveBtn);

        // 2px pad so the grouping border reads as a box around both buttons.
        modeGroupX0 = groupX - 2;
        modeGroupY0 = y0 - 2;
        modeGroupX1 = groupX + (btnW + gap) * 2 - gap + 2;
        modeGroupY1 = y0 + CTRL_H + 2; if (modeGroupY1 > TITLE_H - 1) modeGroupY1 = TITLE_H - 1;
        refreshModeButtons();

        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
            .bounds(Math.max(width - btnW - 8, bx + (btnW + gap) * 8), y0, btnW, CTRL_H).build());

        vpX0 = RIGHT_X;
        vpY0 = TITLE_H + 2;
        vpX1 = Math.max(vpX0 + 80, width - 8);
        vpY1 = Math.max(vpY0 + 80, height - STATUS_H - 4);

        PlayerPoseClient.setEditorPreviewPose(workingPose);
        refreshPoseList();
    }

    private double modelPxPerBlock() {
        return ((vpY1 - vpY0) * 0.8 / 2.0) * camZoom;
    }

    private double[] viewportCenter() {
        return new double[] {(vpX0 + vpX1) / 2.0, (vpY0 + vpY1) / 2.0};
    }

    /** Animated rest pivot of a row, in model units. */
    private float[] pivotFor(String row) {
        float[] pivot = PlayerPoseClient.getJointPivot(cursorSelected() ? "body" : row);
        if (pivot != null) return pivot;
        int idx = ROWS.indexOf(row);
        return FALLBACK_PIVOTS[idx < 0 ? 0 : idx];
    }

    /**
     * Screen position of the pivot the gizmo sits on: the selected bone's joint,
     * plus its own translate offset, plus the cursor.
     */
    private double[] gizmoCenter() {
        float[] pivot = pivotFor(selected);
        double ox = cursorSelected() ? 0.0 : tX;
        double oy = cursorSelected() ? 0.0 : tY;
        double oz = cursorSelected() ? 0.0 : tZ;
        ox += cX; oy += cY; oz += cZ;

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
            hoverAxis, activeAxis);
    }

    // ---- rendering -------------------------------------------------------

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, width, height, 0xFF101018);
        graphics.fill(0, 0, width, TITLE_H, 0x30FFFFFF);
        graphics.fill(0, height - STATUS_H, width, height, 0x30FFFFFF);

        Font font = this.font;

        // Keeps the mode button honest no matter how the mode changed.
        refreshModeButtons();
        refreshUnloadButton();

        drawViewport(graphics, mouseX, mouseY, partialTick);
        drawRowList(graphics, font, mouseX, mouseY);
        drawPoseList(graphics, font, mouseX, mouseY);
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

        int tx = vpX0 + 4;
        int ty = vpY0 + 4;
        Component mode = Component.translatable(moveMode ? "playerpose.modeMove" : "playerpose.modeRotate");
        graphics.text(font, Component.literal(selected + "  [" + mode.getString() + "]"), tx, ty, 0xFFFFFFFF);

        if (workingPose == null || workingPose.getName() == null || workingPose.getName().isBlank()) {
            graphics.text(font, Component.translatable("playerpose.unsavedPose"), tx, ty + 42, 0xFFFFC24A);
        }

        if (moveMode) {
            graphics.text(font, vecLabel("X", translationFor(PoseGizmo.AXIS_X)), tx, ty + 10,
                PoseGizmo.AXIS_X == activeAxis ? 0xFFFFFFFF : 0xFFFF5555);
            graphics.text(font, vecLabel("Y", translationFor(PoseGizmo.AXIS_Y)), tx, ty + 20,
                PoseGizmo.AXIS_Y == activeAxis ? 0xFFFFFFFF : 0xFF6FD44F);
            graphics.text(font, vecLabel("Z", translationFor(PoseGizmo.AXIS_Z)), tx, ty + 30,
                PoseGizmo.AXIS_Z == activeAxis ? 0xFFFFFFFF : 0xFF4F86FF);
        } else {
            graphics.text(font, angleLabel("X", PoseGizmo.AXIS_X), tx, ty + 10,
                PoseGizmo.AXIS_X == activeAxis ? 0xFFFFFFFF : 0xFFFF5555);
            graphics.text(font, angleLabel("Y", PoseGizmo.AXIS_Y), tx, ty + 20,
                PoseGizmo.AXIS_Y == activeAxis ? 0xFFFFFFFF : 0xFF6FD44F);
            graphics.text(font, angleLabel("Z", PoseGizmo.AXIS_Z), tx, ty + 30,
                PoseGizmo.AXIS_Z == activeAxis ? 0xFFFFFFFF : 0xFF4F86FF);
        }

        graphics.text(font, Component.translatable("playerpose.viewportHint"), tx, vpY1 - 12, 0xFF888899);
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

    private void drawRowList(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        // Framed panel so the list reads as one group, matching the mode box.
        panel(graphics, LEFT_X - 3, TITLE_H - 15, LEFT_X + LEFT_W + 3, BONE_Y0 + ROWS.size() * ROW_H + 3);
        graphics.text(font, Component.translatable("playerpose.bonesLabel"), LEFT_X + 4, TITLE_H - 12, 0xFFFFFFFF);
        separator(graphics, LEFT_X - 1, LEFT_X + LEFT_W + 1, TITLE_H - 1);

        for (int i = 0; i < ROWS.size(); i++) {
            String row = ROWS.get(i);
            int yy = BONE_Y0 + i * ROW_H;
            boolean isSelected = row.equals(selected);
            boolean hover = isOver(mouseX, mouseY, LEFT_X, yy, LEFT_W, ROW_H);
            if (isSelected) {
                graphics.fill(LEFT_X, yy, LEFT_X + LEFT_W, yy + ROW_H, 0x6644AA44);
            } else if (hover) {
                graphics.fill(LEFT_X, yy, LEFT_X + LEFT_W, yy + ROW_H, 0x33FFFFFF);
            }
            graphics.text(font, itemLabel(row), LEFT_X + 4, yy + 3,
                isSelected ? 0xFFFFFFFF : 0xFFCCCCCC);
        }
    }

    /** Display name for a left-bar row. */
    private Component itemLabel(String row) {
        if (CURSOR.equals(row)) return Component.translatable("playerpose.cursorRow");
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

    private void drawPoseList(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY) {
        int rows = visiblePoseRows();
        boolean enabled = PlayerPoseClient.arePosesEnabled();

        panel(graphics, LEFT_X - 3, POSE_LABEL_Y - 4, LEFT_X + LEFT_W + 3, POSE_Y0 + rows * ROW_H + 3);
        Component header = Component.translatable(enabled
            ? "playerpose.savedPosesLabel" : "playerpose.savedPosesLabelUnloaded");
        graphics.text(font, header, LEFT_X + 4, POSE_LABEL_Y, enabled ? 0xFFFFFFFF : 0xFFFFC24A);
        separator(graphics, LEFT_X - 1, LEFT_X + LEFT_W + 1, POSE_Y0 - 4);

        for (int i = 0; i < rows; i++) {
            String name = savedPoseNames.get(poseScroll + i);
            int yy = POSE_Y0 + i * ROW_H;
            boolean isWorking = workingPose != null && name.equals(workingPose.getName());
            // Blue means "this pose is actually in effect" - nothing is while unloaded.
            boolean inEffect = enabled && isWorking;
            boolean hover = isOver(mouseX, mouseY, LEFT_X, yy, LEFT_W, ROW_H);

            if (inEffect) {
                graphics.fill(LEFT_X, yy, LEFT_X + LEFT_W, yy + ROW_H, 0x664444AA);
            } else if (isWorking) {
                // Still the one being edited, but not applied: amber outline instead.
                graphics.fill(LEFT_X, yy, LEFT_X + LEFT_W, yy + 1, 0x99FFC24A);
                graphics.fill(LEFT_X, yy + ROW_H - 1, LEFT_X + LEFT_W, yy + ROW_H, 0x99FFC24A);
            } else if (hover) {
                graphics.fill(LEFT_X, yy, LEFT_X + LEFT_W, yy + ROW_H, 0x33FFFFFF);
            }
            graphics.text(font, Component.literal(name), LEFT_X + 4, yy + 3,
                inEffect ? 0xFFFFFFFF : enabled ? 0xFFCCCCCC : 0xFF8891A0);
        }

        // Scrollbar, so it is obvious there are more poses than the panel shows.
        if (maxPoseScroll() > 0) {
            int trackX = LEFT_X + LEFT_W - 3;
            int trackTop = POSE_Y0;
            int trackH = Math.max(ROW_H, rows * ROW_H);
            graphics.fill(trackX, trackTop, trackX + 2, trackTop + trackH, 0x40FFFFFF);
            int thumbH = Math.max(8, trackH * rows / Math.max(1, savedPoseNames.size()));
            int thumbY = trackTop + (trackH - thumbH) * poseScroll / Math.max(1, maxPoseScroll());
            graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0xCCFFFFFF);
        }
    }

    /** How many saved-pose rows fit between the header and the status bar. */
    private int poseWindow() {
        return Math.max(0, (height - STATUS_H - 4 - POSE_Y0) / ROW_H);
    }

    /** How many rows are actually drawn (the window, or fewer if the list is short). */
    private int visiblePoseRows() {
        return Math.min(savedPoseNames.size(), poseWindow());
    }

    private int maxPoseScroll() {
        return Math.max(0, savedPoseNames.size() - poseWindow());
    }

    private void drawStatusBar(GuiGraphicsExtractor graphics, Font font) {
        int y = height - STATUS_H + 5;
        if (!statusMsg.isEmpty() && System.currentTimeMillis() < statusUntil) {
            graphics.text(font, Component.literal(statusMsg), LEFT_X, y, 0xFFFFFF88);
        } else {
            graphics.text(font, Component.translatable("playerpose.hint"), LEFT_X, y, 0xFF888888);
        }
    }

    private static boolean isOver(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    private boolean inViewport(double x, double y) {
        return x >= vpX0 && x < vpX1 && y >= vpY0 && y < vpY1;
    }

    private boolean inRowList(double x, double y) {
        return x >= LEFT_X && x < LEFT_X + LEFT_W
            && y >= BONE_Y0 && y < BONE_Y0 + ROWS.size() * ROW_H;
    }

    private boolean inPoseList(double x, double y) {
        int rows = visiblePoseRows();
        return rows > 0 && x >= LEFT_X && x < LEFT_X + LEFT_W
            && y >= POSE_Y0 && y < POSE_Y0 + rows * ROW_H;
    }

    /** Row index under the cursor, or -1. */
    private int rowAt(double x, double y) {
        if (!inRowList(x, y)) return -1;
        int idx = (int) ((y - BONE_Y0) / ROW_H);
        return idx >= 0 && idx < ROWS.size() ? idx : -1;
    }

    private int poseRowAt(double x, double y) {
        if (!inPoseList(x, y)) return -1;
        int idx = poseScroll + (int) ((y - POSE_Y0) / ROW_H);
        return idx >= 0 && idx < savedPoseNames.size() ? idx : -1;
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

        int row = rowAt(event.x(), event.y());
        if (row >= 0) {
            activeAxis = PoseGizmo.NONE;
            if (!ROWS.get(row).equals(selected)) selectRow(ROWS.get(row));
            return true;
        }

        int poseRow = poseRowAt(event.x(), event.y());
        if (poseRow >= 0) {
            activeAxis = PoseGizmo.NONE;
            selectPose(savedPoseNames.get(poseRow));
            return true;
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
        if (inPoseList(mouseX, mouseY) && maxPoseScroll() > 0) {
            int step = scrollY < 0 ? 1 : scrollY > 0 ? -1 : 0;
            poseScroll = Math.max(0, Math.min(poseScroll + step, maxPoseScroll()));
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

    @Override
    public void onClose() {
        PoseLog.info("editor close: working='%s' active=%s",
            workingPose == null || workingPose.getName() == null || workingPose.getName().isBlank()
                ? "(unsaved)" : workingPose.getName(),
            PlayerPoseClient.getActivePoseName() == null ? "none" : "'" + PlayerPoseClient.getActivePoseName() + "'");
        PlayerPoseClient.setEditorPreviewPose(null);
        PlayerPoseClient.setPoseEditorOpen(false);
        super.onClose();
        // Tell the player what is now being applied to their model.
        PlayerPoseClient.announceAppliedPose();
        PoseLog.dumpPoses(poseManager, "editor close");
    }
}
