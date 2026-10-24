# player-pose — status / TODO

Fabric client mod for Minecraft 26.2. Pick a bone, rotate or translate it on the rendered
player model, save the result as a named pose; mixins apply it to the local player.

## Status

The mod builds clean on Java 25 (`gradlew build`).

Implemented:

- Blender-style viewport: real player model, X/Y/Z rings, orbit (empty-space drag or
  middle-drag), scroll zoom.
- 11 editable bones: body, head, left/right upper arm and forearm, left/right upper leg
  and shin, cape. Each limb is built from 12 one-unit texture slices by
  `PoseLimbSplitMixin`; `PoseModelMixin` bends the slice vertices around the elbow/knee
  with a smooth weight, so the limb curves continuously instead of breaking apart. The
  second layer is sliced too, with its internal slice seams clamped to avoid z-fighting.
- Rotate and Move modes (`R` / `G`), blue/amber highlight for the selected row and mode.
- Editor layout: compact left sidebar (name, 2-column action grid, Bones/Poses tabs) and
  a right viewport with a top info strip (selected part, mode, axis values, small UNSAVED
  badge) plus a bottom hint strip; nothing is painted over the model.
- Bones tab is a small clickable mannequin: head, torso, upper/lower arms, upper/lower
  legs, plus a Cape button. Hover shows the part name; the selected part is framed.
- Save, rename-on-save, delete, New, Load, reset row, reset all, unload/apply.
- Saved-pose list with scrolling; blue shading marks the loaded pose and an amber frame
  marks the pose being edited. Selecting another pose does not unload the blue one;
  **Load** binds and applies the framed pose.
- Undo/redo (`Ctrl+Z` / `Ctrl+Y`), one step per ring drag.
- Live preview: while the editor is open the real model renders the working pose.
- Unsaved edits stay drafts; closing applies the loaded pose. Undo of a delete restores
  the file without implicitly loading it.
- Unicode-safe pose file names (readable stem + short hash) so different non-ASCII names
  cannot overwrite each other.
- Optional 3D Skin Layers compatibility: its second-layer meshes bend at the elbow/knee.
- Append-only lifecycle log at `config/player-pose/player-pose.log`.

## Known limitations

- **Armor** uses separate single-piece arm/leg models, so it does not follow a bent elbow
  or knee; poses with bent limbs look best without armor.
- **Elytra** is a different model and is not posed; only an actual cape uses the cape
  hook.
- **3D Skin Layers**' own second-layer meshes are not part of the sliced player model;
  the compat hook bends them at the joint, but they may not curve as smoothly as the
  vanilla sleeve/pants.
- The gizmo's vertical centring constant (`PoseGizmo.MODEL_CENTER_UNITS`) was derived from
  the GUI entity render pipeline but has not been checked by eye in game.
- Only one pose can be loaded at a time. **Load** binds it immediately (no confirmation)
  and unbinds the previous pose; **Delete** is likewise immediate.
- Delete and rename-overwrite are immediate. Delete is undoable in the current session,
  but the file is gone after closing.

## Next

- [ ] Confirm before Delete, and warn before Save overwrites a different existing pose.
- [ ] Calibrate the gizmo vertical offset in game (`MODEL_CENTER_UNITS`), especially at
      different GUI scales and camera pitches.
- [ ] Give the last two bones (right shin, cape) a keyboard shortcut; `1`-`9` cover only
      the first nine, although every part is clickable on the mannequin.
- [ ] Decide whether unsaved drafts should be restorable after closing (currently they
      are discarded, except via undo while the editor is still open).
- [ ] Test the 3D Skin Layers path with the actual mod installed (the transformer is
      reflective and silently no-ops on failure).
- [ ] Pin `loom_version` to a released version instead of `1.17-SNAPSHOT`.
- [ ] Consider `JAVA_25` for the mixin `compatibilityLevel` (project targets Java 25).
