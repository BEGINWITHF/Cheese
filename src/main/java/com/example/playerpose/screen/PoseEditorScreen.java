package com.example.playerpose.screen;

import com.example.playerpose.PlayerPoseClient;
import com.example.playerpose.compat.GuiGraphicsExtractor;
import com.example.playerpose.compat.PoseCompat;
import com.example.playerpose.pose.Pose;
import com.example.playerpose.pose.PoseBone;
import com.example.playerpose.pose.PoseLog;
import com.example.playerpose.pose.PoseManager;
import com.example.playerpose.skin.EyelidManager;
import com.example.playerpose.skin.PreviewSkinManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import com.example.playerpose.compat.PoseInputEvent.CharacterEvent;
import com.example.playerpose.compat.PoseInputEvent.KeyEvent;
import com.example.playerpose.compat.PoseInputEvent.MouseButtonEvent;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.FileDialog;
import java.awt.Frame;
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
        {0.0F, 0.0F, 2.0F}     // cape / elytra (one bone, whichever is worn)
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
    private PlayerSkin.Model skinModel = PlayerSkin.Model.WIDE;
    private String skinStatus = "";
    private Rect skinButtonRect;
    private Rect skinPanelRect;
    private Rect skinBrowseRect;
    private Rect skinLoadRect;
    private Rect skinModelRect;
    private Rect skinOriginalRect;
    private Rect skinCloseRect;
    /** 0 = skin tab, 1 = cape tab. */
    private int skinTab;
    private Rect skinTabSkinRect;
    private Rect skinTabCapeRect;
    private final Rect[] capeEntryRects =
        new Rect[PreviewSkinManager.OFFICIAL_CAPES.size()];
    private Rect capeImportRect;
    private Rect capeOriginalRect;
    private Rect capeCloseRect;

    /** Editor-only eyelid preview. */
    private boolean eyesPanelOpen;
    /** True while the eyes are the edit target, even after the sliders are closed. */
    private boolean eyesSelected;
    private boolean eyesPrompt;
    private boolean eyesLoading;
    private String eyesMessage = "";
    private Rect eyesButtonRect;
    private Rect eyesPanelRect;
    private Rect eyesFormatSpiceRect;
    private Rect eyesFormatJeRect;
    private Rect eyesLeftSlider;
    private Rect eyesRightSlider;
    private Rect eyesDemoRect;
    private Rect eyesCloseRect;
    private Rect eyesYesRect;
    private Rect eyesNoRect;
    private boolean draggingLeftEye;
    private boolean draggingRightEye;

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

    /**
     * Sodium conflict helper. The prompt is offered once per game session and can be
     * reopened by clicking the COMPAT badge while the conflict is still detected.
     */
    private static final String SODIUM_GUIDE_URL =
        "https://raw.githubusercontent.com/BEGINWITHF/Cheese/main/docs/sodium-restore.md";
    private static boolean sodiumPromptShown;
    private boolean sodiumDialogOpen;
    private boolean sodiumDialogFixed;
    private String sodiumDialogError = "";
    private int sodiumX0, sodiumY0, sodiumX1, sodiumY1;
    private Rect sodiumConfirmRect;
    private Rect sodiumCancelRect;
    private Rect sodiumLinkRect;

    /** Elytra opening angles, radians, edited by the three viewport sliders. */
    private static final float ELYTRA_SPREAD_MAX = 1.31F; // ~75 degrees
    private float elytraSpreadX;
    private float elytraSpreadY;
    private float elytraSpreadZ;
    private final Rect[] elytraSliderRects = new Rect[3];
    private int draggingElytraAxis = -1;
    /** The elytra control panel starts open and the user can fold it away. */
    private boolean elytraPanelOpen = true;
    private Rect capeDemoRect;
    private Rect elytraToggleRect;
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
        PoseBone spread = findBone("elytraSpread");
        elytraSpreadX = spread == null ? 0.0F : (float) spread.x;
        elytraSpreadY = spread == null ? 0.0F : (float) spread.y;
        elytraSpreadZ = spread == null ? 0.0F : (float) spread.z;
    }

    /** Write the three elytra opening angles back into the pose as one pseudo-bone. */
    private void commitElytraSpread() {
        if (workingPose == null) return;
        if (workingPose.getBones() == null) workingPose.setBones(new ArrayList<>());
        List<PoseBone> bones = workingPose.getBones();
        PoseBone value = new PoseBone("elytraSpread",
            elytraSpreadX, elytraSpreadY, elytraSpreadZ);
        for (int i = 0; i < bones.size(); i++) {
            if ("elytraSpread".equals(bones.get(i).bone)) {
                bones.set(i, value);
                return;
            }
        }
        bones.add(value);
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
        deselectEyes();
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
        deselectEyes();
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
                    || info.getProfile().getId() == null) continue;
                UUID id = info.getProfile().getId();
                if (id.equals(self)) continue;
                String name = info.getProfile().getName();
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

    private void sodiumDialogLayout() {
        int w = 400;
        int h = 206;
        sodiumX0 = Math.max(4, (width - w) / 2);
        sodiumY0 = Math.max(4, (height - h) / 2);
        sodiumX1 = sodiumX0 + w;
        sodiumY1 = sodiumY0 + h;
        int by = sodiumY0 + 178;
        int bh = 18;
        Component confirmLabel = Component.translatable(sodiumDialogFixed
            ? "playerpose.sodium.restartNow" : "playerpose.sodium.confirm");
        Component cancelLabel = Component.translatable(sodiumDialogFixed
            ? "playerpose.sodium.later" : "playerpose.sodium.cancel");
        int confirmW = Math.max(78, font.width(confirmLabel) + 20);
        int cancelW = Math.max(72, font.width(cancelLabel) + 20);
        sodiumConfirmRect = new Rect(sodiumX1 - 12 - confirmW, by,
            sodiumX1 - 12, by + bh);
        sodiumCancelRect = new Rect(sodiumConfirmRect.x0 - 8 - cancelW, by,
            sodiumConfirmRect.x0 - 8, by + bh);
        int linkW = font.width(Component.translatable("playerpose.sodium.link"));
        sodiumLinkRect = new Rect(sodiumX0 + 10, sodiumY0 + 155,
            sodiumX0 + 14 + linkW, sodiumY0 + 171);
    }

    private void drawSodiumDialog(GuiGraphicsExtractor graphics, Font font,
                                  int mouseX, int mouseY) {
        if (!sodiumDialogOpen) return;
        sodiumDialogLayout();
        graphics.fill(0, 0, width, height, 0x99000000);
        graphics.fill(sodiumX0, sodiumY0, sodiumX1, sodiumY1, 0xFF181A22);
        graphics.fill(sodiumX0, sodiumY0, sodiumX1, sodiumY0 + 1, 0xFF8891A0);
        graphics.fill(sodiumX0, sodiumY1 - 1, sodiumX1, sodiumY1, 0xFF8891A0);
        graphics.fill(sodiumX0, sodiumY0, sodiumX0 + 1, sodiumY1, 0xFF8891A0);
        graphics.fill(sodiumX1 - 1, sodiumY0, sodiumX1, sodiumY1, 0xFF8891A0);

        Component title = Component.translatable("playerpose.sodium.title");
        graphics.text(font, title, sodiumX0 + 12, sodiumY0 + 10, 0xFFFFD48A);

        String body1;
        String body2;
        int body1Color = 0xFFD0D0D0;
        if (sodiumDialogFixed) {
            body1 = Component.translatable("playerpose.sodium.fixed").getString();
            body2 = Component.translatable("playerpose.sodium.restart").getString();
            body1Color = 0xFF7FD46A;
        } else if (!sodiumDialogError.isEmpty()) {
            body1 = Component.translatable("playerpose.sodium.failed").getString();
            body2 = sodiumDialogError;
            body1Color = 0xFFFF8080;
        } else {
            body1 = Component.translatable("playerpose.sodium.body").getString();
            body2 = Component.translatable("playerpose.sodium.change").getString();
        }
        int textX = sodiumX0 + 12;
        int textW = sodiumX1 - sodiumX0 - 24;
        drawWrapped(graphics, font, body1, textX, sodiumY0 + 28, textW, 3, body1Color);
        drawWrapped(graphics, font, body2, textX, sodiumY0 + 62, textW, 3, 0xFFD0D0D0);

        // The exact file and line this button changes.
        graphics.text(font, Component.literal("config/sodium-mixins.properties"),
            textX, sodiumY0 + 96, 0xFF9FC4FF);
        graphics.text(font, Component.literal("mixin.features.render.entity = false"),
            textX, sodiumY0 + 108, 0xFF9FC4FF);

        drawWrapped(graphics, font,
            Component.translatable("playerpose.sodium.restore").getString(),
            textX, sodiumY0 + 124, textW, 3, 0xFFB0B6C4);

        Component link = Component.translatable("playerpose.sodium.link");
        int linkW = font.width(link);
        graphics.text(font, link, textX, sodiumY0 + 158, 0xFFFFC24A);
        graphics.fill(textX, sodiumY0 + 168, textX + linkW, sodiumY0 + 169, 0xFFFFC24A);

        boolean confirmHover = sodiumConfirmRect.contains(mouseX, mouseY);
        graphics.fill(sodiumConfirmRect.x0, sodiumConfirmRect.y0,
            sodiumConfirmRect.x1, sodiumConfirmRect.y1,
            confirmHover ? 0xCC4F9A52 : 0xAA356B38);
        Component confirm = Component.translatable(sodiumDialogFixed
            ? "playerpose.sodium.restartNow" : "playerpose.sodium.confirm");
        graphics.text(font, confirm,
            sodiumConfirmRect.x0 + (sodiumConfirmRect.x1 - sodiumConfirmRect.x0
                - font.width(confirm)) / 2,
            sodiumConfirmRect.y0 + 5, 0xFFFFFFFF);

        if (sodiumCancelRect != null) {
            boolean cancelHover = sodiumCancelRect.contains(mouseX, mouseY);
            graphics.fill(sodiumCancelRect.x0, sodiumCancelRect.y0,
                sodiumCancelRect.x1, sodiumCancelRect.y1,
                cancelHover ? 0xCC555566 : 0xAA333344);
            Component cancel = Component.translatable(sodiumDialogFixed
                ? "playerpose.sodium.later" : "playerpose.sodium.cancel");
            graphics.text(font, cancel,
                sodiumCancelRect.x0 + (sodiumCancelRect.x1 - sodiumCancelRect.x0
                    - font.width(cancel)) / 2,
                sodiumCancelRect.y0 + 5, 0xFFE0E0E0);
        }
    }

    private void openSodiumDialog() {
        sodiumDialogOpen = true;
        sodiumPromptShown = true;
        sodiumDialogFixed = false;
        sodiumDialogError = "";
    }

    private void closeSodiumDialog() {
        boolean needsRestart = sodiumDialogFixed;
        sodiumDialogOpen = false;
        sodiumDialogFixed = false;
        sodiumDialogError = "";
        if (needsRestart) {
            setStatus(Component.translatable("playerpose.sodium.restart"));
        }
    }

    private void confirmSodiumFix() {
        if (PoseCompat.disableSodiumEntityMixin()) {
            sodiumDialogFixed = true;
            sodiumDialogError = "";
            compatWarnings = PoseCompat.warnings(Minecraft.getInstance());
            PoseLog.info("compat: sodium fix written; restart required");
        } else {
            sodiumDialogError = PoseCompat.sodiumConfig().toString();
        }
    }

    /**
     * Start a second client with the same command line, then close this one. Used after
     * the Sodium config change because Sodium only reads its mixin rules at startup.
     */
    private void restartGame() {
        try {
            ProcessHandle.Info info = ProcessHandle.current().info();
            String command = info.command().orElse(null);
            String[] args = info.arguments().orElse(null);
            if (command == null || args == null || args.length == 0) {
                PoseLog.warn("restart: command line unavailable, stopping only");
            } else {
                List<String> line = new ArrayList<>(args.length + 1);
                line.add(command);
                for (String arg : args) line.add(arg);
                new ProcessBuilder(line)
                    .directory(new java.io.File(System.getProperty("user.dir")))
                    .inheritIO()
                    .start();
                PoseLog.info("restart: started a new client");
            }
        } catch (Throwable error) {
            PoseLog.warn("restart: could not start a new client (%s)", error);
        }
        Minecraft.getInstance().stop();
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
        boolean capeTab = skinTab == 1;
        int w = 360;
        int h = capeTab ? 200 : 172;
        int x = Math.max(8, vpX1 - w - 8);
        int y = vpY0 + 28;
        if (y + h > height - STATUS_H - 4) {
            y = Math.max(4, height - STATUS_H - 4 - h);
        }
        if (x + w > width - 4) x = Math.max(4, width - w - 4);

        skinPanelRect = new Rect(x, y, x + w, y + h);

        // "Skin / Cape" tabs share the old title row.
        int tabX = x + 12;
        int tabY = y + 8;
        int skinW = font.width(Component.translatable("playerpose.skin.tabSkin"));
        int slashW = font.width(" / ");
        int capeW = font.width(Component.translatable("playerpose.skin.tabCape"));
        skinTabSkinRect = new Rect(tabX - 2, tabY - 2, tabX + skinW + 2, tabY + 11);
        skinTabCapeRect = new Rect(tabX + skinW + slashW - 2, tabY - 2,
            tabX + skinW + slashW + capeW + 2, tabY + 11);

        for (int i = 0; i < capeEntryRects.length; i++) capeEntryRects[i] = null;
        capeImportRect = null;
        capeOriginalRect = null;
        capeCloseRect = null;

        if (capeTab) {
            setSkinModeVisible(false);
            if (skinInputBox != null) {
                skinInputBox.setVisible(false);
                skinInputBox.setFocused(false);
            }
            int colW = 109;
            int gap = 5;
            int top = y + 28;
            for (int i = 0; i < capeEntryRects.length; i++) {
                int col = i % 3;
                int row = i / 3;
                int ex = x + 12 + col * (colW + gap);
                int ey = top + row * 16;
                capeEntryRects[i] = new Rect(ex, ey, ex + colW, ey + 15);
            }
            capeImportRect = new Rect(x + 12, y + 166, x + 120, y + 186);
            capeOriginalRect = new Rect(x + 126, y + 166, x + 240, y + 186);
            capeCloseRect = new Rect(x + 246, y + 166, x + 348, y + 186);
            return;
        }

        setSkinModeVisible(true);
        if (skinInputBox != null) {
            skinInputBox.setX(x + 140);
            skinInputBox.setY(y + 28);
            skinInputBox.setWidth(208);
            skinInputBox.setHeight(CTRL_H);
            skinInputBox.setVisible(true);
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

        drawSkinTabs(graphics, font, mouseX, mouseY);

        if (skinTab == 1) {
            drawCapeTab(graphics, font, mouseX, mouseY);
            return;
        }

        drawSkinAction(graphics, font, skinBrowseRect,
            Component.translatable("playerpose.skin.browse"), false, mouseX, mouseY);
        drawSkinAction(graphics, font, skinLoadRect,
            Component.translatable("playerpose.skin.load"), true, mouseX, mouseY);
        drawSkinAction(graphics, font, skinModelRect,
            Component.translatable(skinModel == PlayerSkin.Model.SLIM
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
            || status.startsWith("Cape must") || status.startsWith("Cape texture")
            || status.startsWith("Choose a cape")
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
        if (face != null) {
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
            skinPlayerBtn.render(graphics.delegate(), mouseX, mouseY, partialTick);
        }
        if (skinFileBtn != null && skinFileBtn.visible) {
            skinFileBtn.render(graphics.delegate(), mouseX, mouseY, partialTick);
        }
        drawSkinModeGroup(graphics);
        if (skinInputBox != null && skinInputBox.visible) {
            skinInputBox.render(graphics.delegate(), mouseX, mouseY, partialTick);
        }
    }

    /** "Skin / Cape" header: two clickable words sharing the old title row. */
    private void drawSkinTabs(GuiGraphicsExtractor graphics, Font font,
                              int mouseX, int mouseY) {
        Rect p = skinPanelRect;
        Component skin = Component.translatable("playerpose.skin.tabSkin");
        Component cape = Component.translatable("playerpose.skin.tabCape");
        int tx = p.x0 + 12;
        int ty = p.y0 + 8;
        boolean skinHover = skinTabSkinRect != null && skinTabSkinRect.contains(mouseX, mouseY);
        boolean capeHover = skinTabCapeRect != null && skinTabCapeRect.contains(mouseX, mouseY);
        graphics.text(font, skin, tx, ty, skinTab == 0 ? 0xFFFFD48A
            : skinHover ? 0xFFFFFFFF : 0xFF9AA3B4);
        int slashX = tx + font.width(skin);
        graphics.text(font, Component.literal(" / "), slashX, ty, 0xFF667080);
        int capeX = slashX + font.width(" / ");
        graphics.text(font, cape, capeX, ty, skinTab == 1 ? 0xFFFFD48A
            : capeHover ? 0xFFFFFFFF : 0xFF9AA3B4);
    }

    private void drawCapeTab(GuiGraphicsExtractor graphics, Font font,
                             int mouseX, int mouseY) {
        Rect p = skinPanelRect;
        String chosen = PreviewSkinManager.chosenCapeKey();
        for (int i = 0; i < PreviewSkinManager.OFFICIAL_CAPES.size()
            && i < capeEntryRects.length; i++) {
            Rect r = capeEntryRects[i];
            if (r == null) continue;
            PreviewSkinManager.OfficialCape cape = PreviewSkinManager.OFFICIAL_CAPES.get(i);
            boolean selected = cape.id().equals(chosen);
            boolean hover = r.contains(mouseX, mouseY);
            graphics.fill(r.x0, r.y0, r.x1, r.y1,
                selected ? 0x66443322 : hover ? 0x33FFFFFF : 0x222A2E3A);
            int border = selected ? 0xFFFFC24A : hover ? 0xFF8891A0 : 0xFF3E4862;
            graphics.fill(r.x0, r.y0, r.x1, r.y0 + 1, border);
            graphics.fill(r.x0, r.y1 - 1, r.x1, r.y1, border);
            graphics.fill(r.x0, r.y0, r.x0 + 1, r.y1, border);
            graphics.fill(r.x1 - 1, r.y0, r.x1, r.y1, border);
            String label = font.plainSubstrByWidth(cape.label(), r.x1 - r.x0 - 8);
            graphics.text(font, Component.literal(label), r.x0 + 4, r.y0 + 4,
                selected ? 0xFFFFD48A : 0xFFD0D0D0);
        }

        String status = skinStatus;
        if (status == null || status.isBlank()) {
            status = Component.translatable("playerpose.cape.hint").getString();
        }
        boolean error = status.startsWith("Cape must") || status.startsWith("Cape texture")
            || status.startsWith("Could") || status.startsWith("Choose");
        drawWrapped(graphics, font, status, p.x0 + 12, p.y0 + 128,
            p.x1 - p.x0 - 24 - 34, 2, error ? 0xFFFF9A9A : 0xFFAEB6C8);

        // Small front view of the picked cape.
        int scale = 2;
        int tx = p.x1 - 12 - 10 * scale;
        int ty = p.y0 + 126;
        graphics.fill(tx - 1, ty - 1, tx + 10 * scale + 1, ty + 16 * scale + 1,
            0xFF667080);
        graphics.fill(tx, ty, tx + 10 * scale, ty + 16 * scale, 0xFF0B0C10);
        drawCapeThumb(graphics, tx, ty, scale);

        drawSkinAction(graphics, font, capeImportRect,
            Component.translatable("playerpose.cape.import"), true, mouseX, mouseY);
        drawSkinAction(graphics, font, capeOriginalRect,
            Component.translatable("playerpose.cape.original"), false, mouseX, mouseY);
        drawSkinAction(graphics, font, capeCloseRect,
            Component.translatable("playerpose.skin.close"), false, mouseX, mouseY);
    }

    /** Draw the cape texture's front region (10x16) scaled up. */
    private void drawCapeThumb(GuiGraphicsExtractor graphics, int x, int y, int scale) {
        NativeImage image = PreviewSkinManager.capePixels();
        if (image == null) return;
        for (int py = 0; py < 16; py++) {
            for (int px = 0; px < 10; px++) {
                int color = image.getPixel(px, py);
                if ((color >>> 24) == 0) continue;
                graphics.fill(x + px * scale, y + py * scale,
                    x + (px + 1) * scale, y + (py + 1) * scale, color);
            }
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

    // ---- eyelid editor ---------------------------------------------------

    private void drawEyesPanel(GuiGraphicsExtractor graphics, Font font,
                               int mouseX, int mouseY) {
        if (!eyesPanelOpen) return;
        int w = 300;
        int h = 174;
        int x = Math.max(8, vpX1 - w - 8);
        int y = vpY0 + 28;
        if (y + h > height - STATUS_H - 4) {
            y = Math.max(4, height - STATUS_H - 4 - h);
        }
        if (x + w > width - 4) x = Math.max(4, width - w - 4);
        eyesPanelRect = new Rect(x, y, x + w, y + h);

        graphics.fill(x, y, x + w, y + h, 0xFF181A22);
        int edge = 0xFF8891A0;
        graphics.fill(x, y, x + w, y + 1, edge);
        graphics.fill(x, y + h - 1, x + w, y + h, edge);
        graphics.fill(x, y, x + 1, y + h, edge);
        graphics.fill(x + w - 1, y, x + w, y + h, edge);

        graphics.text(font, Component.translatable("playerpose.eyes.title"),
            x + 12, y + 8, 0xFFFFD48A);

        // Format segments.
        eyesFormatSpiceRect = new Rect(x + 12, y + 26, x + 112, y + 46);
        eyesFormatJeRect = new Rect(x + 114, y + 26, x + 214, y + 46);
        graphics.fill(x + 12, y + 25, x + 214, y + 26, 0xFFAAB4D4);
        graphics.fill(x + 12, y + 46, x + 214, y + 47, 0xFFAAB4D4);
        graphics.fill(x + 12, y + 25, x + 13, y + 47, 0xFFAAB4D4);
        graphics.fill(x + 213, y + 25, x + 214, y + 47, 0xFFAAB4D4);
        drawEyesSegment(graphics, font, eyesFormatSpiceRect,
            Component.translatable("playerpose.eyes.formatSpice"),
            EyelidManager.format() == EyelidManager.Format.SKINSPICE, mouseX, mouseY);
        drawEyesSegment(graphics, font, eyesFormatJeRect,
            Component.translatable("playerpose.eyes.formatJe"),
            EyelidManager.format() == EyelidManager.Format.JUST_EXPRESSIONS, mouseX, mouseY);

        // Support / status.
        if (eyesLoading) {
            graphics.text(font, Component.translatable("playerpose.eyes.loading"),
                x + 12, y + 54, 0xFFAEB6C8);
        } else if (!EyelidManager.hasBase()) {
            graphics.text(font, Component.translatable("playerpose.eyes.noBase"),
                x + 12, y + 54, 0xFFFF9A9A);
        } else {
            drawEyeSupport(graphics, font, x + 12, y + 54, true,
                EyelidManager.leftSupported());
            drawEyeSupport(graphics, font, x + 100, y + 54, false,
                EyelidManager.rightSupported());
            if (!eyesMessage.isBlank()) {
                graphics.text(font, Component.literal(eyesMessage),
                    x + 12, y + 66, 0xFFAEB6C8);
            }
        }

        // Sliders. While the base skin is still loading the support of the previous skin
        // is meaningless, so both stay neutral instead of flashing green.
        eyesLeftSlider = new Rect(x + 58, y + 78, x + 232, y + 96);
        eyesRightSlider = new Rect(x + 58, y + 102, x + 232, y + 120);
        boolean leftLit = !eyesLoading && EyelidManager.leftSupported();
        boolean rightLit = !eyesLoading && EyelidManager.rightSupported();
        drawEyeSlider(graphics, font, x, y + 82, eyesLeftSlider,
            Component.translatable("playerpose.eyes.left"),
            EyelidManager.leftOpen(), leftLit);
        drawEyeSlider(graphics, font, x, y + 106, eyesRightSlider,
            Component.translatable("playerpose.eyes.right"),
            EyelidManager.rightOpen(), rightLit);

        if (eyesPrompt) {
            drawWrapped(graphics, font, Component.translatable("playerpose.eyes.prompt").getString(),
                x + 12, y + 124, w - 24, 2, 0xFFFFD48A);
            eyesYesRect = new Rect(x + 12, y + 146, x + 148, y + 166);
            eyesNoRect = new Rect(x + 216, y + 146, x + 288, y + 166);
            eyesDemoRect = null;
            eyesCloseRect = null;
            drawSkinAction(graphics, font, eyesYesRect,
                Component.translatable("playerpose.eyes.useDemo"), true, mouseX, mouseY);
            drawSkinAction(graphics, font, eyesNoRect,
                Component.translatable("playerpose.eyes.cancel"), false, mouseX, mouseY);
        } else {
            eyesYesRect = null;
            eyesNoRect = null;
            String hint = Component.translatable("playerpose.eyes.hint").getString();
            graphics.text(font, Component.literal(hint), x + 12, y + 124,
                0xFF8891A0);
            if (compatEyeWarning()) {
                graphics.text(font, Component.translatable("playerpose.eyes.conflict"),
                    x + 12, y + 134, 0xFFAA8860);
            }
            eyesDemoRect = new Rect(x + 12, y + 146, x + 148, y + 166);
            eyesCloseRect = new Rect(x + 216, y + 146, x + 288, y + 166);
            boolean demoLoaded = PreviewSkinManager.isDemo();
            drawSkinAction(graphics, font, eyesDemoRect,
                Component.translatable(demoLoaded
                    ? "playerpose.skin.original" : "playerpose.eyes.demo"),
                demoLoaded, mouseX, mouseY);
            drawSkinAction(graphics, font, eyesCloseRect,
                Component.translatable("playerpose.eyes.close"), false, mouseX, mouseY);
        }
    }

    private void drawEyesSegment(GuiGraphicsExtractor graphics, Font font, Rect r,
                                 Component label, boolean active, int mouseX, int mouseY) {
        boolean hover = r.contains(mouseX, mouseY);
        graphics.fill(r.x0 + 1, r.y0 + 1, r.x1 - 1, r.y1 - 1,
            active ? 0x33FFD48A : hover ? 0xAA555566 : 0xAA333344);
        if (active) {
            graphics.fill(r.x0 + 1, r.y1 - 2, r.x1 - 1, r.y1, 0xFFFFC24A);
        }
        graphics.text(font, label, r.x0 + ((r.x1 - r.x0) - font.width(label)) / 2,
            r.y0 + 5, active || hover ? 0xFFFFFFFF : 0xFFE0E0E0);
    }

    private void drawEyeSupport(GuiGraphicsExtractor graphics, Font font,
                                int x, int y, boolean left, boolean supported) {
        Component label = Component.translatable(left
            ? "playerpose.eyes.left" : "playerpose.eyes.right");
        graphics.text(font, label, x, y, 0xFFB8C0D0);
        Component state = Component.translatable(supported
            ? "playerpose.eyes.supported" : "playerpose.eyes.unsupported");
        graphics.text(font, state, x + font.width(label) + 6, y,
            supported ? 0xFF7FD46A : 0xFF9A6A6A);
    }

    private void drawEyeSlider(GuiGraphicsExtractor graphics, Font font, int panelX,
                               int y, Rect track, Component label,
                               float open, boolean supported) {
        graphics.text(font, label, panelX + 12, y + 3, 0xFFB8C0D0);
        int tx0 = panelX + 58;
        int tx1 = panelX + 232;
        int ty0 = y + 5;
        int ty1 = y + 11;
        graphics.fill(tx0, ty0, tx1, ty1, 0xFF0B0C10);
        graphics.fill(tx0, ty0, tx1, ty0 + 1, 0xFF667080);
        graphics.fill(tx0, ty1 - 1, tx1, ty1, 0xFF667080);
        graphics.fill(tx0, ty0, tx0 + 1, ty1, 0xFF667080);
        graphics.fill(tx1 - 1, ty0, tx1, ty1, 0xFF667080);
        int knobW = 6;
        int kx = tx0 + Math.round(open * (tx1 - tx0 - knobW));
        int fill = supported ? 0xFF7FD46A : 0xFF555566;
        if (supported) {
            graphics.fill(tx0 + 1, ty0 + 2, kx + knobW, ty1 - 2, 0x557FD46A);
        }
        graphics.fill(kx, y + 2, kx + knobW, y + 14, fill);
        graphics.fill(kx, y + 2, kx + knobW, y + 3, 0xFFFFFFFF);
        Component value = Component.literal(supported
            ? Math.round(open * 100.0F) + "%" : "-");
        graphics.text(font, value, panelX + 240, y + 3,
            supported ? 0xFFE0E0E0 : 0xFF7A8090);
    }

    private boolean compatEyeWarning() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
            .isModLoaded("entity_texture_features")
            || net.fabricmc.loader.api.FabricLoader.getInstance()
            .isModLoaded("entity_model_features");
    }

    private void drawWrapped(GuiGraphicsExtractor graphics, Font font, String text,
                             int x, int y, int maxWidth, int maxLines, int color) {
        List<String> lines = wrapText(font, text, maxWidth, maxLines);
        for (int i = 0; i < lines.size(); i++) {
            graphics.text(font, Component.literal(lines.get(i)), x, y + i * 10, color);
        }
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
        if (eyesPanelOpen) closeEyesPanel();
        skinPanelOpen = true;
        skinTab = 0;
        applySkinMode();
        if (skinStatus == null || skinStatus.isBlank()) {
            skinStatus = skinModeHint();
        }
        layoutSkinPanel();
        setSkinModeVisible(true);
        if (skinInputBox != null) {
            skinInputBox.setVisible(true);
            setFocused(skinInputBox);
            skinInputBox.setFocused(true);
            // 1.21.11 EditBox drives OS text input / IME from focus alone.
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

    // ---- eyelid editor ---------------------------------------------------

    private void openEyesPanel() {
        if (skinPanelOpen) closeSkinPanel();
        eyesPanelOpen = true;
        eyesSelected = true;
        updateActionButtons();
        eyesPrompt = false;
        eyesMessage = "";
        draggingLeftEye = false;
        draggingRightEye = false;
        EyelidManager.setActive(true);
        if (PreviewSkinManager.pixels() != null) {
            EyelidManager.refresh();
            eyesLoading = false;
        } else {
            eyesLoading = true;
            PreviewSkinManager.ensureBaseSkin(() -> {
                eyesLoading = false;
                EyelidManager.refresh();
                if (!EyelidManager.hasBase()) {
                    eyesMessage = Component.translatable("playerpose.eyes.noBase").getString();
                }
            });
        }
    }

    private void closeEyesPanel() {
        eyesPanelOpen = false;
        eyesPrompt = false;
        draggingLeftEye = false;
        draggingRightEye = false;
        eyesYesRect = null;
        eyesNoRect = null;
        eyesDemoRect = null;
        eyesCloseRect = null;
    }

    private void loadBlinkDemo() {
        PlayerSkin.Model type = PreviewSkinManager.model();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.getSkin() != null
            && PreviewSkinManager.pixels() == null) {
            type = mc.player.getSkin().model();
        }
        PreviewSkinManager.loadBundledDemo(type, this::onEyesMessage);
        EyelidManager.refresh();
        eyesPrompt = false;
        eyesMessage = Component.translatable("playerpose.eyes.demoLoaded").getString();
    }

    private void onEyesMessage(String message) {
        eyesMessage = message == null ? "" : message;
    }

    private void setEyeFromMouse(boolean left, double mouseX) {
        Rect track = left ? eyesLeftSlider : eyesRightSlider;
        if (track == null) return;
        float value = (float) ((mouseX - track.x0) / (double) (track.x1 - track.x0));
        EyelidManager.setOpenness(left, value);
    }

    private boolean isButtonClick(Button button, double mouseX, double mouseY) {
        if (button == null || !button.visible || !button.active) return false;
        return mouseX >= button.getX() && mouseX < button.getX() + button.getWidth()
            && mouseY >= button.getY() && mouseY < button.getY() + button.getHeight();
    }

    private void handleEyesClick(MouseButtonEvent event) {
        double mx = event.x();
        double my = event.y();
        if (eyesPrompt) {
            if (eyesYesRect != null && eyesYesRect.contains(mx, my)) {
                loadBlinkDemo();
            } else if (eyesNoRect != null && eyesNoRect.contains(mx, my)) {
                eyesPrompt = false;
            }
            return;
        }
        if (eyesFormatSpiceRect != null && eyesFormatSpiceRect.contains(mx, my)) {
            EyelidManager.setFormat(EyelidManager.Format.SKINSPICE);
            eyesMessage = "";
            return;
        }
        if (eyesFormatJeRect != null && eyesFormatJeRect.contains(mx, my)) {
            EyelidManager.setFormat(EyelidManager.Format.JUST_EXPRESSIONS);
            eyesMessage = "";
            return;
        }
        if (eyesDemoRect != null && eyesDemoRect.contains(mx, my)) {
            if (PreviewSkinManager.isDemo()) {
                restoreOriginalSkin();
                eyesMessage = Component.translatable("playerpose.skin.originalSet").getString();
            } else {
                loadBlinkDemo();
            }
            return;
        }
        if (eyesCloseRect != null && eyesCloseRect.contains(mx, my)) {
            closeEyesPanel();
            return;
        }
        if (eyesLeftSlider != null && eyesLeftSlider.contains(mx, my)) {
            if (!eyesLoading && EyelidManager.hasBase()) {
                if (!EyelidManager.leftSupported()) {
                    eyesPrompt = true;
                } else {
                    draggingLeftEye = true;
                    setEyeFromMouse(true, mx);
                }
            }
            return;
        }
        if (eyesRightSlider != null && eyesRightSlider.contains(mx, my)) {
            if (!eyesLoading && EyelidManager.hasBase()) {
                if (!EyelidManager.rightSupported()) {
                    eyesPrompt = true;
                } else {
                    draggingRightEye = true;
                    setEyeFromMouse(false, mx);
                }
            }
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
        try {
            String path = openPngDialog(
                Component.translatable("playerpose.skin.dialogTitle").getString());
            if (path != null && !path.isBlank()) {
                skinInputBox.setValue(path);
                loadSkinInput();
            }
        } catch (Throwable error) {
            PoseLog.warn("skin preview: file dialog failed (%s)", error);
            onSkinStatus(Component.translatable("playerpose.skin.dialogError").getString());
        }
    }

    private void browseCapeFile() {
        try {
            String path = openPngDialog(
                Component.translatable("playerpose.cape.dialogTitle").getString());
            if (path != null && !path.isBlank()) {
                PreviewSkinManager.importCapeFile(java.nio.file.Path.of(path),
                    this::onSkinStatus);
            }
        } catch (Throwable error) {
            PoseLog.warn("skin preview: cape file dialog failed (%s)", error);
            onSkinStatus(Component.translatable("playerpose.skin.dialogError").getString());
        }
    }

    /** Native PNG picker; AWT keeps working after 26.3 dropped the LWJGL dialog. */
    private static String openPngDialog(String title) {
        FileDialog dialog = new FileDialog((Frame) null, title, FileDialog.LOAD);
        dialog.setFilenameFilter((dir, name) ->
            name.toLowerCase(java.util.Locale.ROOT).endsWith(".png"));
        dialog.setFile("*.png");
        dialog.setVisible(true);
        String file = dialog.getFile();
        String dir = dialog.getDirectory();
        dialog.dispose();
        if (file == null || file.isBlank()) return null;
        return dir == null ? file : new java.io.File(dir, file).getAbsolutePath();
    }

    private static void openBrowser(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
                if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                    desktop.browse(java.net.URI.create(url));
                    return;
                }
            }
            PoseLog.warn("sodium guide: desktop browser is not available");
        } catch (Throwable error) {
            PoseLog.warn("sodium guide: cannot open browser (%s)", error);
        }
    }

    private void toggleSkinModel() {
        skinModel = skinModel == PlayerSkin.Model.SLIM
            ? PlayerSkin.Model.WIDE : PlayerSkin.Model.SLIM;
        if (PreviewSkinManager.skin() != null) {
            PreviewSkinManager.setModel(skinModel, this::onSkinStatus);
        } else {
            onSkinStatus(Component.translatable(skinModel == PlayerSkin.Model.SLIM
                ? "playerpose.skin.armsSlim" : "playerpose.skin.armsClassic").getString());
        }
    }

    private void restoreOriginalSkin() {
        PreviewSkinManager.clear();
        skinModel = PreviewSkinManager.model();
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
        nameDialogLayout();
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
            deselectEyes();
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
        resetEyelids();
        syncFromPose();
        setStatus(Component.translatable("playerpose.status.resetAll"));
    }

    /** Both eyelids back to fully open; they are editor state, not part of the pose. */
    private void resetEyelids() {
        EyelidManager.setOpenness(true, 1.0F);
        EyelidManager.setOpenness(false, 1.0F);
        draggingLeftEye = false;
        draggingRightEye = false;
    }

    /**
     * Leave eye mode. The eyelid values stay where they are: the eyes keep rendering as
     * set until Reset, another skin, or closing the editor, so switching focus to a bone
     * does not silently undo the eyelid edit.
     */
    private void deselectEyes() {
        eyesSelected = false;
        updateActionButtons();
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
        // With the eyelid panel open the eyes are the selection, so Reset targets them and
        // leaves whatever body bone was last picked untouched.
        if (eyesSelected) {
            resetEyelids();
            setStatus(Component.translatable("playerpose.status.reset",
                Component.translatable("playerpose.eyes.button")));
            return;
        }
        beginEdit();
        rotX = 0; rotY = 0; rotZ = 0;
        tX = 0; tY = 0; tZ = 0;
        commit();
        if ("cape".equals(selected)) {
            elytraSpreadX = 0.0F;
            elytraSpreadY = 0.0F;
            elytraSpreadZ = 0.0F;
            commitElytraSpread();
        }
        setStatus(Component.translatable("playerpose.status.reset", selected));
    }

    private static double wrapDegrees(double value) {
        double v = value % 360.0;
        if (v > 180.0) v -= 360.0;
        if (v < -180.0) v += 360.0;
        return v;
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
        EyelidManager.setActive(true);

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
        // Offer the one-click Sodium fix once per game session; the COMPAT badge can
        // reopen it from then on.
        if (!sodiumPromptShown && PoseCompat.sodiumConflict()) {
            openSodiumDialog();
        }
    }

    /** Save/Done stay; the other four actions follow the active list tab. */
    private void updateActionButtons() {
        boolean bones = listTab == 0;
        if (saveBtn != null) saveBtn.active = editingPose;
        if (rotateBtn != null) {
            rotateBtn.visible = bones;
            rotateBtn.active = editingPose && !eyesSelected;
        }
        if (moveBtn != null) {
            moveBtn.visible = bones;
            moveBtn.active = editingPose && !eyesSelected;
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
    public void render(GuiGraphics rawGraphics, int mouseX, int mouseY, float partialTick) {
        GuiGraphicsExtractor graphics = new GuiGraphicsExtractor(rawGraphics);
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

        super.render(rawGraphics, mouseX, mouseY, partialTick);

        // On top of the widgets, so the group border and the mode marker stay visible.
        drawModeGroup(graphics);
        drawLoadMenu(graphics, font, mouseX, mouseY);
        drawNameDialog(graphics, font, mouseX, mouseY);
        drawSkinPanel(graphics, font, mouseX, mouseY, partialTick);
        drawEyesPanel(graphics, font, mouseX, mouseY);
        drawSodiumDialog(graphics, font, mouseX, mouseY);

        if (!sodiumDialogOpen && !compatWarnings.isEmpty() && compatX1 > compatX0
            && isOver(mouseX, mouseY, compatX0, compatY0,
                compatX1 - compatX0, compatY1 - compatY0)) {
            int index = compatWarningIndex();
            List<Component> tip = new ArrayList<>();
            tip.add(compatWarnings.get(index));
            if (PoseCompat.sodiumConflict()) {
                tip.add(Component.translatable("playerpose.sodium.clickFix"));
            }
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
        if (gizmoVisible && editingPose && !eyesSelected) {
            int hoverAxis = buildGizmo(graphics, PoseGizmo.NONE).hitTest(mouseX, mouseY);
            buildGizmo(graphics, hoverAxis).draw();
        }
        graphics.disableScissor();

        if (!skinPanelOpen && !eyesPanelOpen) {
            drawSkinButton(graphics, font, mouseX, mouseY);
        } else {
            skinButtonRect = null;
        }

        drawElytraSpreadSlider(graphics, font, mouseX, mouseY);

        // The model owns the viewport; the read-out lives in the top strip instead of
        // painting over the head. Long hints scroll instead of being cut off.
        graphics.fill(vpX0, vpY1 - 14, vpX1, vpY1, 0x66000000);
        drawMarquee(graphics, font,
            Component.translatable("playerpose.viewportHint").getString(),
            vpX0 + 4, vpY1 - 12, vpX1 - vpX0 - 8, 0xFF888899);
    }

    /**
     * Elytra control: three vertical sliders for the X/Y/Z opening angles, in a small
     * panel at the top-left of the viewport. The title row sits above the sliders and
     * folds the panel away; the panel starts open.
     */
    private void drawElytraSpreadSlider(GuiGraphicsExtractor graphics, Font font,
                                        int mouseX, int mouseY) {
        boolean show = !eyesSelected && !eyesPanelOpen && !skinPanelOpen
            && listTab == 0 && editingPose && "cape".equals(selected) && wearingElytra();
        if (!show) {
            for (int i = 0; i < elytraSliderRects.length; i++) elytraSliderRects[i] = null;
            elytraToggleRect = null;
            return;
        }
        Component title = Component.translatable("playerpose.elytra.control");
        Component caption = Component.literal(elytraPanelOpen ? "\u25BE " : "\u25B8 ")
            .append(title);
        int captionW = font.width(caption);

        String[] names = {"X", "Y", "Z"};
        int[] colors = {0xFFFF5555, 0xFF6FD44F, 0xFF4F86FF};
        float[] values = {elytraSpreadX, elytraSpreadY, elytraSpreadZ};

        int groupW = 44;
        int boxW = Math.max(groupW, captionW) + 14;
        int boxX0 = vpX0 + 4;
        int boxX1 = boxX0 + boxW;
        int boxY0 = vpY0 + 4;
        int captionY = boxY0 + 5;
        int trackH = 56;
        int trackY0 = captionY + 13;
        int trackY1 = trackY0 + trackH;
        int boxY1 = elytraPanelOpen ? trackY1 + 16 : boxY0 + 20;
        graphics.fill(boxX0, boxY0, boxX1, boxY1, 0x66000000);

        elytraToggleRect = new Rect(boxX0 + 2, captionY - 3, boxX1 - 2, captionY + 10);
        boolean toggleHover = elytraToggleRect.contains(mouseX, mouseY);
        graphics.text(font, caption, boxX0 + 6, captionY,
            toggleHover ? 0xFFFFC24A : 0xFFB8C0D0);

        if (!elytraPanelOpen) {
            for (int i = 0; i < elytraSliderRects.length; i++) elytraSliderRects[i] = null;
            return;
        }

        int groupX0 = boxX0 + (boxW - groupW) / 2;
        for (int axis = 0; axis < 3; axis++) {
            int trackX0 = groupX0 + axis * 18;
            int trackX1 = trackX0 + 8;
            elytraSliderRects[axis] = new Rect(trackX0 - 3, trackY0 - 4,
                trackX1 + 3, trackY1 + 4);
            boolean hover = elytraSliderRects[axis].contains(mouseX, mouseY);
            boolean active = hover || draggingElytraAxis == axis;

            graphics.fill(trackX0, trackY0, trackX1, trackY1, 0xFF0B0C10);
            int border = active ? 0xFFFFC24A : 0xFF667080;
            graphics.fill(trackX0, trackY0, trackX1, trackY0 + 1, border);
            graphics.fill(trackX0, trackY1 - 1, trackX1, trackY1, border);
            graphics.fill(trackX0, trackY0, trackX0 + 1, trackY1, border);
            graphics.fill(trackX1 - 1, trackY0, trackX1, trackY1, border);

            float ratio = sliderRatio(axis, values[axis]);
            int knobH = 6;
            int ky = trackY1 - knobH - Math.round(ratio * (trackH - knobH));
            graphics.fill(trackX0 + 1, ky + knobH, trackX1 - 1, trackY1 - 1, 0x55FFC24A);
            graphics.fill(trackX0 - 2, ky, trackX1 + 2, ky + knobH, colors[axis] | 0xFF000000);
            if (axis == 0) {
                graphics.fill(trackX0 - 2, ky, trackX1 + 2, ky + 1, 0xFFFFFFFF);
            }

            Component label = Component.literal(names[axis]);
            graphics.text(font, label, trackX0 + (8 - font.width(label)) / 2,
                trackY1 + 4, active ? 0xFFFFFFFF : colors[axis]);
        }
    }

    /** Slider position for one axis; X is centred so it can go negative. */
    private static float sliderRatio(int axis, float value) {
        float ratio = axis == 0
            ? (value + ELYTRA_SPREAD_MAX) / (ELYTRA_SPREAD_MAX * 2.0F)
            : value / ELYTRA_SPREAD_MAX;
        return Math.max(0.0F, Math.min(1.0F, ratio));
    }

    private void setElytraSpreadFromMouse(int axis, double mouseY) {
        if (axis < 0 || axis >= elytraSliderRects.length) return;
        Rect track = elytraSliderRects[axis];
        if (track == null) return;
        // Up is open: the top of the track is the maximum.
        float ratio = (float) ((track.y1 - mouseY) / (double) (track.y1 - track.y0));
        ratio = Math.max(0.0F, Math.min(1.0F, ratio));
        float value = axis == 0
            ? (ratio * 2.0F - 1.0F) * ELYTRA_SPREAD_MAX
            : ratio * ELYTRA_SPREAD_MAX;
        switch (axis) {
            case 0 -> elytraSpreadX = value;
            case 1 -> elytraSpreadY = value;
            default -> elytraSpreadZ = value;
        }
        commitElytraSpread();
    }
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
            // Eye mode: the bone values are not the thing being edited, so they go grey.
            Component label = moveMode
                ? vecLabel(axisNames[i], translationFor(i))
                : angleLabel(axisNames[i], i);
            int labelW = font.width(label);
            if (ax + labelW > vpX1 - 4) break;
            int color = eyesSelected ? 0xFF6A7080
                : i == activeAxis ? 0xFFFFFFFF : axisColors[i];
            graphics.text(font, label, ax, INFO_Y + 13, color);
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
        if (state instanceof PlayerRenderState avatar) {
            var preview = PreviewSkinManager.skin();
            if (preview != null) {
                // Editor-only: replace the render state's skin, never the player's.
                avatar.skin = preview;
            }
        }
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
        graphics.entity(state, (float) modelPxPerBlock(), offset, rot, pitchQ,
            vpX0, vpY0, vpX1, vpY1);
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
        eyesButtonRect = null;

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
                    && info.getProfile().getId() != null) {
                    ids.add(info.getProfile().getId());
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
        // While the eyelid panel is open the eyes are the selection; body parts are not
        // pickable then, so neither hover nor the selection frame should react.
        String hover = eyesSelected ? null : boneAt(mouseX, mouseY);

        // Eyelid editor entry, in the free space left of the mannequin's head.
        int bx = LEFT_X + 2;
        int by = LIST_Y + 2;
        int bw = 34;
        int bh = 16;
        boolean eyesHover = isOver(mouseX, mouseY, bx, by, bw, bh);
        boolean eyesLit = EyelidManager.leftSupported() || EyelidManager.rightSupported();
        // Only the open panel marks "eyes" as the current selection; a supported skin just
        // gets a small dot, so the lit border cannot be mistaken for a selection.
        int eyesEdge = eyesSelected ? 0xFFFFC24A
            : eyesHover ? 0xFFFFC24A : 0xFF667080;
        graphics.fill(bx, by, bx + bw, by + bh, eyesHover ? 0xCC555566 : 0xAA333344);
        graphics.fill(bx, by, bx + bw, by + 1, eyesEdge);
        graphics.fill(bx, by + bh - 1, bx + bw, by + bh, eyesEdge);
        graphics.fill(bx, by, bx + 1, by + bh, eyesEdge);
        graphics.fill(bx + bw - 1, by, bx + bw, by + bh, eyesEdge);
        if (eyesLit) {
            graphics.fill(bx + bw - 6, by + bh - 6, bx + bw - 3, by + bh - 3, 0xFF7FD46A);
        }
        Component eyesLabel = Component.translatable("playerpose.eyes.button");
        graphics.text(font, eyesLabel, bx + (bw - font.width(eyesLabel)) / 2, by + 4,
            eyesHover || eyesPanelOpen ? 0xFFFFFFFF : 0xFFE0E0E0);
        eyesButtonRect = new Rect(bx, by, bx + bw, by + bh);

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

        boolean capeSelected = selectionShown() && "cape".equals(selected);
        boolean capeHover = "cape".equals(hover);
        drawChip(graphics, font, f.chipX0(), f.chipX1(), f.chipY0(), f.chipY1(),
            "cape", capeSelected, capeHover);

        // No cape of your own? Offer the bundled demo cape right next to the chip. The same
        // button puts the real (empty) cape back when the demo is loaded.
        drawDemoCapeChip(graphics, font, f, mouseX, mouseY);

        // Hover wins so the name under the cursor is readable before clicking.
        String shown = hover != null ? hover : selected;
        Component caption = eyesSelected
            ? Component.translatable("playerpose.eyes.button")
            : itemLabel(shown);
        graphics.text(font, caption, f.cx() - font.width(caption) / 2, f.captionY(),
            eyesSelected ? 0xFFFFC24A : hover != null ? 0xFFFFC24A : 0xFFCCCCCC);
    }

    private void drawDemoCapeChip(GuiGraphicsExtractor graphics, Font font, BoneFigure f,
                                  int mouseX, int mouseY) {
        boolean hasCape = previewHasCape();
        boolean demo = PreviewSkinManager.isDemoCape();
        if (hasCape && !demo) {
            capeDemoRect = null;
            return;
        }
        int w = 46;
        int x1 = f.chipX0() - 4;
        int x0 = Math.max(LEFT_X + 2, x1 - w);
        if (x0 >= x1 - 8) {
            capeDemoRect = null;
            return;
        }
        capeDemoRect = new Rect(x0, f.chipY0(), x1, f.chipY1());
        boolean hover = capeDemoRect.contains(mouseX, mouseY);

        // Same chip look as the bone chips, but the full label is longer than the space
        // available, so it scrolls.
        graphics.fill(x0, f.chipY0(), x1, f.chipY1(),
            demo ? 0x6644AA44 : hover ? 0x33FFFFFF : 0x55222233);
        int border = demo ? 0xFF7FD46A : hover ? 0xFFFFC24A : 0xFF3E4862;
        graphics.fill(x0, f.chipY0(), x1, f.chipY0() + 1, border);
        graphics.fill(x0, f.chipY1() - 1, x1, f.chipY1(), border);
        graphics.fill(x0, f.chipY0(), x0 + 1, f.chipY1(), border);
        graphics.fill(x1 - 1, f.chipY0(), x1, f.chipY1(), border);

        String label = Component.translatable(demo
            ? "playerpose.cape.unload" : "playerpose.cape.demo").getString();
        drawMarquee(graphics, font, label, x0 + 3, f.chipY0() + 3, (x1 - x0) - 6,
            demo || hover ? 0xFFFFFFFF : 0xFFCCCCCC);
    }

    private boolean previewHasCape() {
        // The editor may be showing another player's skin: their cape decides whether the
        // demo cape is offered, not the local player's real one.
        return PreviewSkinManager.effectiveCape() != null;
    }

    private void drawChip(GuiGraphicsExtractor graphics, Font font, int x0, int x1,
                          int y0, int y1, String bone, boolean selected, boolean hover) {
        drawChip(graphics, font, x0, x1, y0, y1, itemLabel(bone), selected, hover);
    }

    private void drawChip(GuiGraphicsExtractor graphics, Font font, int x0, int x1,
                          int y0, int y1, Component label, boolean selected, boolean hover) {
        graphics.fill(x0, y0, x1, y1, selected ? 0x6644AA44 : hover ? 0x33FFFFFF : 0x55222233);
        int border = selected ? 0xFF7FD46A : hover ? 0xFFFFC24A : 0xFF3E4862;
        graphics.fill(x0, y0, x1, y0 + 1, border);
        graphics.fill(x0, y1 - 1, x1, y1, border);
        graphics.fill(x0, y0, x0 + 1, y1, border);
        graphics.fill(x1 - 1, y0, x1, y1, border);
        graphics.text(font, label, x0 + (x1 - x0 - font.width(label)) / 2, y0 + 3,
            selected || hover ? 0xFFFFFFFF : 0xFFCCCCCC);
    }

    private void drawBonePart(GuiGraphicsExtractor graphics, BoneFigure f, String bone,
                              double x0, double y0, double x1, double y1,
                              int argb, String hover) {
        int px0 = f.x(x0), py0 = f.y(y0), px1 = f.x(x1), py1 = f.y(y1);
        graphics.fill(px0, py0, px1, py1, argb);
        boolean isSelected = selectionShown() && bone.equals(selected);
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
    private boolean selectionShown() {
        return selectionVisible && !eyesSelected;
    }

    private String boneAt(double mouseX, double mouseY) {
        if (listTab != 0 || eyesSelected) return null;
        return boneAtRaw(mouseX, mouseY);
    }

    /** Part under the cursor, ignoring the eye selection. */
    private String boneAtRaw(double mouseX, double mouseY) {
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
        // Cape and elytra are mutually exclusive, so they share one bone; the label just
        // follows whatever the player is wearing.
        if ("cape".equals(row)) {
            return Component.translatable(wearingElytra()
                ? "playerpose.bone.elytra" : "playerpose.bone.cape");
        }
        return Component.translatable("playerpose.bone." + row);
    }

    private boolean wearingElytra() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null
            && mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST)
                .is(net.minecraft.world.item.Items.ELYTRA);
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return mouseClicked(new MouseButtonEvent(mouseX, mouseY, button), false);
    }

    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (sodiumDialogOpen) {
            sodiumDialogLayout();
            double mx = event.x();
            double my = event.y();
            if (sodiumLinkRect != null && sodiumLinkRect.contains(mx, my)) {
                if (!SODIUM_GUIDE_URL.isEmpty()) {
                    openBrowser(SODIUM_GUIDE_URL);
                }
                return true;
            }
            if (sodiumConfirmRect != null && sodiumConfirmRect.contains(mx, my)) {
                if (sodiumDialogFixed) restartGame(); else confirmSodiumFix();
                return true;
            }
            if (sodiumCancelRect != null && sodiumCancelRect.contains(mx, my)) {
                closeSodiumDialog();
                return true;
            }
            return true; // modal: clicks never reach the editor
        }
        // The COMPAT badge doubles as the entry point for the Sodium helper.
        if (!compatWarnings.isEmpty() && compatX1 > compatX0
            && isOver((int) event.x(), (int) event.y(), compatX0, compatY0,
                compatX1 - compatX0, compatY1 - compatY0)
            && PoseCompat.sodiumConflict()) {
            openSodiumDialog();
            return true;
        }
        if (eyesPanelOpen) {
            // Reset / Reset All stay usable while the panel is open, and act on the eyes.
            if (isButtonClick(resetBtn, event.x(), event.y())) {
                resetRow();
                return true;
            }
            if (isButtonClick(resetAllBtn, event.x(), event.y())) {
                resetAll();
                return true;
            }
            // Clicking the (now amber) Eyes button again just hides the sliders; the eyes
            // stay the edit target until a body part is picked.
            if (eyesButtonRect != null && eyesButtonRect.contains(event.x(), event.y())) {
                closeEyesPanel();
                return true;
            }
            handleEyesClick(event);
            return true;
        }
        if (skinPanelOpen) {
            layoutSkinPanel();
            int mx = (int) event.x();
            int my = (int) event.y();
            if (skinTabSkinRect != null && skinTabSkinRect.contains(mx, my)) {
                skinTab = 0;
                applySkinMode();
                if (skinInputBox != null) {
                    setFocused(skinInputBox);
                    skinInputBox.setFocused(true);
                }
                return true;
            }
            if (skinTabCapeRect != null && skinTabCapeRect.contains(mx, my)) {
                skinTab = 1;
                setFocused(null);
                return true;
            }
            if (skinTab == 1) {
                for (int i = 0; i < capeEntryRects.length
                    && i < PreviewSkinManager.OFFICIAL_CAPES.size(); i++) {
                    Rect r = capeEntryRects[i];
                    if (r != null && r.contains(mx, my)) {
                        PreviewSkinManager.selectOfficialCape(
                            PreviewSkinManager.OFFICIAL_CAPES.get(i), this::onSkinStatus);
                        return true;
                    }
                }
                if (capeImportRect != null && capeImportRect.contains(mx, my)) {
                    browseCapeFile();
                    return true;
                }
                if (capeOriginalRect != null && capeOriginalRect.contains(mx, my)) {
                    PreviewSkinManager.clearPreviewCape(this::onSkinStatus);
                    return true;
                }
                if (capeCloseRect != null && capeCloseRect.contains(mx, my)) {
                    closeSkinPanel();
                    return true;
                }
                return true;
            }
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
                // Focus explicitly: 1.21.5 routes text input through the focused widget.
                if (skinInputBox != null && skinInputBox.visible
                    && mx >= skinInputBox.getX()
                    && mx < skinInputBox.getX() + skinInputBox.getWidth()
                    && my >= skinInputBox.getY()
                    && my < skinInputBox.getY() + skinInputBox.getHeight()) {
                    setFocused(skinInputBox);
                    skinInputBox.setFocused(true);
                    if (skinInputBox.mouseClicked(event.x(), event.y(), event.button())) return true;
                }
                return super.mouseClicked(event.x(), event.y(), event.button());
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

        if (eyesButtonRect != null && eyesButtonRect.contains(event.x(), event.y())) {
            openEyesPanel();
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
            // Eye mode survives closing the sliders: picking a body part is what leaves it.
            if (eyesSelected && boneAtRaw(event.x(), event.y()) != null) {
                deselectEyes();
            }
            if (capeDemoRect != null && capeDemoRect.contains(event.x(), event.y())) {
                PreviewSkinManager.toggleDemoCape(this::onSkinStatus);
                return true;
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
            if (event.button() == 0) {
                if (elytraToggleRect != null && elytraToggleRect.contains(event.x(), event.y())) {
                    elytraPanelOpen = !elytraPanelOpen;
                    return true;
                }
                for (int axis = 0; axis < elytraSliderRects.length; axis++) {
                    Rect track = elytraSliderRects[axis];
                    if (track == null || !track.contains(event.x(), event.y())) continue;
                    beginEdit();
                    draggingElytraAxis = axis;
                    setElytraSpreadFromMouse(axis, event.y());
                    return true;
                }
            }
            if (event.button() == 2) {
                // middle mouse orbits, like most 3D viewers
                activeAxis = PoseGizmo.NONE;
                orbiting = true;
                return true;
            }
            if (event.button() == 0) {
                int hit = PoseGizmo.NONE;
                PoseGizmo probe = null;
                // Eye mode owns the selection: the viewport must not rotate a bone that is
                // not the current edit target.
                if (gizmoVisible && !eyesSelected) {
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
        return super.mouseClicked(event.x(), event.y(), event.button());
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return mouseDragged(new MouseButtonEvent(mouseX, mouseY, button), dragX, dragY);
    }

    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (eyesPanelOpen) {
            if (draggingLeftEye) setEyeFromMouse(true, event.x());
            if (draggingRightEye) setEyeFromMouse(false, event.x());
            return true;
        }
        if (skinPanelOpen) return true;
        if (nameDialogOpen) return true;
        if (draggingElytraAxis >= 0) {
            setElytraSpreadFromMouse(draggingElytraAxis, event.y());
            return true;
        }
        if (activeAxis != PoseGizmo.NONE && !eyesSelected) {
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
            // right/down instead of inverting both axes. The orbit is unrestricted, so the
            // model can be inspected from below and behind as well.
            camYaw = wrapDegrees(camYaw - dragX * 0.6);
            camPitch = wrapDegrees(camPitch - dragY * 0.5);
            return true;
        }
        return super.mouseDragged(event.x(), event.y(), event.button(), dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return mouseReleased(new MouseButtonEvent(mouseX, mouseY, button));
    }

    public boolean mouseReleased(MouseButtonEvent event) {
        if (eyesPanelOpen) {
            draggingLeftEye = false;
            draggingRightEye = false;
            return true;
        }
        if (draggingElytraAxis >= 0) {
            draggingElytraAxis = -1;
            return true;
        }
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
        return super.mouseReleased(event.x(), event.y(), event.button());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (eyesPanelOpen) return true;
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
            camZoom = Math.max(0.2, Math.min(6.0, camZoom * (1.0 + scrollY * 0.1)));
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
    public boolean charTyped(char codePoint, int modifiers) {
        return charTyped(new CharacterEvent(codePoint));
    }

    public boolean charTyped(CharacterEvent event) {
        if (sodiumDialogOpen) return true;
        if (eyesPanelOpen) return true;
        if (skinPanelOpen) {
            if (skinTab == 1) return true;
            if (skinInputBox != null && skinInputBox.isFocused()
                && skinInputBox.charTyped(event.codepoint(), 0)) {
                return true;
            }
            return super.charTyped(event.codepoint(), 0);
        }
        if (nameDialogOpen) {
            int cp = event.codepoint();
            if (cp >= 32
                && nameDialogText.codePointCount(0, nameDialogText.length()) < 32) {
                nameDialogText += new String(Character.toChars(cp));
            }
            return true;
        }
        return super.charTyped(event.codepoint(), 0);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return keyPressed(new KeyEvent(keyCode, scanCode, modifiers));
    }

    public boolean keyPressed(KeyEvent event) {
        if (sodiumDialogOpen) {
            if (event.key() == 256) { // ESC: not now
                closeSodiumDialog();
            } else if (event.key() == 257 || event.key() == 335) { // ENTER
                if (sodiumDialogFixed) restartGame(); else confirmSodiumFix();
            }
            return true;
        }
        if (eyesPanelOpen) {
            if (event.key() == 256) { // ESC: cancel the prompt, then close the panel
                if (eyesPrompt) eyesPrompt = false; else closeEyesPanel();
            }
            return true;
        }
        if (skinPanelOpen) {
            if (event.key() == 256) { // ESC closes the skin panel, not the editor
                closeSkinPanel();
                return true;
            }
            if (skinTab == 1) return true;
            if (event.key() == 257 || event.key() == 335) { // ENTER / keypad ENTER
                loadSkinInput();
                return true;
            }
            if (skinInputBox != null && skinInputBox.isFocused()
                && skinInputBox.keyPressed(event.key(), event.scancode(), event.modifiers())) {
                return true;
            }
            return super.keyPressed(event.key(), event.scancode(), event.modifiers());
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
            return super.keyPressed(event.key(), event.scancode(), event.modifiers());
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
            return super.keyPressed(event.key(), event.scancode(), event.modifiers());
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
                deselectEyes();
                int idx = event.key() - 49;
                if (idx < ROWS.size() && !ROWS.get(idx).equals(selected)) {
                    selectRow(ROWS.get(idx));
                }
                return true;
            }
            default -> { }
        }
        // Screen#keyPressed closes on ESC when shouldCloseOnEsc() is true.
        return super.keyPressed(event.key(), event.scancode(), event.modifiers());
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
        EyelidManager.setActive(false);
        eyesPanelOpen = false;
        eyesSelected = false;
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
