# Agent Mission Briefs — player-pose mod

All agents work on the same codebase: `C:\Users\begin\Desktop\Cheese`.
MC 26.2, Fabric 26.2, Java 25, Fabric API 0.158.0+26.2.
Mod ID: `player-pose`. Package: `com.example.playerpose`.

Branch: `main`. Do not commit unless asked.

---

## Agent A — Rotation input widgets + lang entries

**Goal:** Make the pose editor actually editable. Right now the screen shows rotation values in degrees but has no way to change them.

**Files you'll touch:**
- `src/main/java/com/example/playerpose/screen/PoseEditorScreen.java`
- `src/main/resources/assets/player-pose/lang/en_us.json`

**What to do:**

1. **Add 4 missing lang keys** to `en_us.json`:
   - `playerpose.poseNameHint` — placeholder text for the name EditBox
   - `playerpose.saveBtn` — Save button label
   - `playerpose.loadBtn` — Load button label
   - `playerpose.resetBtn` — Reset button label

2. **Add rotation input widgets** to the screen. Current layout has a bone list on the left (x: 8–148, y: TITLE_H+24 down) and a rotation read-out on the right (x: RIGHT_X = 160). The read-out currently just displays degrees — replace/augment it with actual controls.

   Decision points (pick one, document your choice in a comment at the top of the file):
   - **Widget type:** Fabric `Slider` (if available in 26.2), or `EditBox` accepting numeric input, or a custom clickable+draggable widget. Prefer something that feels decent.
   - **Unit convention:** The mixin in `PoseModelMixin.java` reads `bone.x/y/z` as **radians**. The screen displays **degrees**. Either:
     - (a) Store radians in `PoseBone`, convert degrees→radians when the widget commits, convert radians→degrees for display. (Current convention — keep it.)
     - (b) Store degrees in `PoseBone` and convert to radians in the mixin.
     - Recommend (a) — less risky, only the screen needs to change.

   **Required behaviour:**
   - Selecting a bone (clicking in the bone list) loads that bone's current rotation into the widgets.
   - Changing a widget value updates the in-memory `rotX/Y/Z` and calls `updatePoseBone()` so the working pose stays in sync.
   - Display the current value in degrees next to or under each slider.
   - The reset button zeroes the currently selected bone's rotation and updates the pose.

3. **Verify:** `./gradlew build` passes.

**Constraints:**
- EditBox constructor in 26.2: `(WidgetParent, int, int, int, int, Component, String, boolean)` — already used for the name box.
- Keep the screen layout readable; don't let widgets overflow the window.
- No Chinese in code or comments.

---

## Agent B — F5/F7 keybind semantics

**Goal:** Make the quick-save and quick-delete keybinds behave sensibly.

**Files you'll touch:**
- `src/main/java/com/example/playerpose/input/PoseKeybinds.java`
- `src/main/java/com/example/playerpose/screen/PoseEditorScreen.java` (maybe — if you add a delete button)

**Current behaviour:**
- **F5 (save):** if editor is closed, re-saves the existing pose for the current skin under the same name. If no pose exists, does nothing.
- **F7 (reset):** calls `mgr.delete(skin)` — deletes the pose whose **name** equals the skin string.

**Problem:** The pose name is independent of the skin string. A pose named "wave" saved for skin "Steve" won't be touched by F7 (which looks for a pose named "Steve").

**What to decide and implement (pick one):**

- **Option 1 — Delete by currently-loaded pose name:** F7 deletes whichever pose is currently loaded for this skin (the one `findForSkin` returned), regardless of its name. Add a confirmation step (e.g. a one-shot status message "Deleted X — reload to confirm" or a simple yes/no via a quick secondary key press within 2 seconds).
- **Option 2 — Add a Delete button to the editor:** Put a Delete button in `PoseEditorScreen` that deletes the currently loaded pose (the one being edited). F7 stays as-is or is remapped.
- **Option 3 — Document-only:** Leave the behaviour, add a clear comment in `PoseKeybinds.java` explaining the semantics so it's not surprising.

Recommend **Option 1 or 2** — Option 3 leaves a real footgun. If you go with Option 2, add the lang key `playerpose.deleteBtn` too.

**Verify:** `./gradlew build` passes.

---

## Agent C — Pose preview panel (optional, nice-to-have)

**Goal:** Show a live preview of the pose inside the editor so the user can see what they're building without saving and looking at their character in-game.

**Files you'll touch:**
- `src/main/java/com/example/playerpose/screen/PoseEditorScreen.java` (render pass)
- Possibly new file: `src/main/java/com/example/playerpose/screen/PosePreview.java` (model renderer helper)

**What to do:**

Render a small `HumanoidModel` in the right-hand panel of the editor (the area currently used for rotation read-out, roughly x: 160+, y: 40+).

**Approach:**
- In `render()`, after the GUI fill, set up a `MatrixStack` with an orthographic or slight perspective transform positioned in the right panel.
- Create a `HumanoidModel<HumanoidRenderState>` (or reuse the accessor that's available), apply the working pose's bone rotations directly to its `ModelPart`s (same rotation calls the mixin uses: `part.setRotation(x, y, z)`), then render it through `GameRenderer`.
- Size it to fit ~120×120 px in the panel; center it.
- The pose applied to the preview should match what the mixin will apply — same bone set, same rotation values from `workingPose.getBones()`.

**Complication:** Rendering a Minecraft model inside a GUI screen requires the right render state (light, shader, matrix stack). If you get stuck on the render setup, fall back to a simpler representation: draw the 6 bone rectangles as a stick-figure-like layout with rotation arcs, or just leave a labelled placeholder and document what's needed. Don't block on this — a partial preview is better than nothing.

**Verify:** `./gradlew build` passes. Visual correctness is best-effort; the user will check the render.

**Constraints:**
- No Chinese in code or comments.
- Don't fight the existing screen layout — place the preview in the right panel, not overlapping the bone list.

---

## Agent D — Pose delete + list/selector UI (optional, nice-to-have)

**Goal:** Let the user browse all saved poses and delete them from the editor, not just load the one matching their skin.

**Files you'll touch:**
- `src/main/java/com/example/playerpose/screen/PoseEditorScreen.java`
- `src/main/resources/assets/player-pose/lang/en_us.json` (add keys as needed)

**What to do:**

1. **Pose list / selector:** Add a way to browse and select any saved pose, not just the one matching the current skin. A simple scrollable list or a dropdown. When a different pose is selected, load its bones into the editor (same as the Load button does now, but user-driven). Show the pose name + skin filter (if any) in the list entry.

2. **Delete button:** Add a Delete button that removes the currently loaded/selected pose. If you also did Agent B's Option 2, coordinate — don't duplicate.

3. **Lang keys:** Add whatever labels the new UI needs.

**Design note:** The screen is already fairly full. If space is tight, consider collapsing the bone list and pose list into a tabbed or scrollable area, or put the pose list in a side panel. Don't make it worse to use.

**Verify:** `./gradlew build` passes.

**Constraints:**
- No Chinese in code or comments.
- Coordinate with Agent B if both touch delete semantics.

---

## Agent E — Build verification (trivial)

**Goal:** Confirm the project compiles Clean.

**What to do:**
- Run `./gradlew build` in `C:\Users\begin\Desktop\Cheese`.
- Report: exit code, any errors, any warnings worth noting.
- If it fails, capture the error and stop — don't try to fix unless asked.

---

## Coordination notes

- **Agent A and Agent D** both touch `PoseEditorScreen.java`. If they run in parallel, they will conflict. Either serialize them, or pick one agent to own the screen and the other to stay clear.
- **Agent B and Agent D** both may add a delete path. Coordinate on a single delete design.
- **Agent C** is independent — the preview render is additive and shouldn't conflict with the others as long as it stays in the right panel.
- All agents: run `./gradlew build` before reporting done.
