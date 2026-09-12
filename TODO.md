# player-pose — TODO

Fabric mod for Minecraft 26.2. Posture editor: pick a bone, set rotation, save by skin name, mixin applies it to your model every render.

## Status

Skeleton compiles and wires together. Editor screen exists but is missing its input widgets and a few lang entries. No in-editor preview.

---

## 1. Core gaps (must fix before it's usable)

### 1.1 Rotation input widgets
- Screen hint says "drag sliders to rotate" — no sliders exist.
- Need X/Y/Z rotation editors. Candidates:
  - Fabric `Slider` (needs ` net.minecraft.client.gui.components.Slider` — verify availability in 26.2)
  - `Button` + scroll-wheel or drag on the rotation read-out
  - Mini text input boxes (EditBox) that accept numeric values
- Whatever is picked, the widget must update `rotX/Y/Z` and call `updatePoseBone()` so the working pose stays in sync.

### 1.2 Missing lang entries
`assets/player-pose/lang/en_us.json` is missing these keys referenced by the screen:

| Key | Where used |
|---|---|
| `playerpose.poseNameHint` | `nameBox.setPrompt(...)` |
| `playerpose.saveBtn` | Save button label |
| `playerpose.loadBtn` | Load button label |
| `playerpose.resetBtn` | Reset button label |

Add them. The button labels should be short: "Save", "Load", "Reset" with context in tooltips if needed.

### 1.3 Pose preview (nice-to-have, not blocking)
No way to see what the pose looks like without saving + reloading + looking at your character in-game.
- Options: render a small `HumanoidModel` in the screen's right panel using `GameRenderer`, or
- defer to a follow-up — the rotation read-out in degrees is a reasonable minimum.

---

## 2. Behaviour clarifications

### 2.1 F5 / F7 semantics
- **F5 (save):** re-saves the existing pose for the current skin under the same name. If no pose exists yet, it does nothing.
- **F7 (reset/delete):** calls `mgr.delete(skin)` — deletes the pose whose **name** equals the skin string.

This means:
- The pose name is expected to equal the skin string for these keybinds to make sense.
- If a user saves a pose named "myPose" for skin "Steve", then F7 won't touch it (it looks for a pose named "Steve").
- **Decision needed:** either document this, make F7 delete the *currently loaded* pose by its name instead of by skin, or change the keybind to delete-by-name.

### 2.2 Mixin bone reset
`PoseModelMixin.applyPose` does:
```java
part.setRotation(0f, 0f, 0f);
part.setRotation((float) bone.x(), (float) bone.y(), (float) bone.z());
```
This clears whatever `setupAnim` computed and replaces it with the pose. That's the intended behaviour for an overriding pose editor, but:
- If a pose has only some bones defined, the undefined bones get zeroed too (because the loop only touches bones that are in the list, and the reset only happens for found parts — actually, re-reading: the reset is inside `if (part != null)` which only fires for bones in the pose. Bones NOT in the pose are left alone. So it's fine — only defined bones get overridden.)

### 2.3 Editor open-guard
`shouldApplyPose()` returns `false` when the editor is open, so the mixin won't fight the editor's working pose. That's correct.

---

## 3. Polish / follow-ups

- **Delete pose from editor UI:** no button to delete the currently loaded pose. Add a "Delete" button or a long-press / right-click handler on the bone list.
- **Confirm before delete:** deleting a pose is destructive; consider a confirmation step.
- **Pose list / selector:** currently you can only load the pose that matches your skin. No way to browse or switch to a different saved pose from the editor. Could add a dropdown or list.
- **Skin field editable:** the skin is set from the local player at open time and stored on the pose. If a user wants to save a pose "for any skin" (skin = null), there's currently no UI path to clear the skin filter.
- **Number formatting:** rotation is displayed in degrees with `%.2f`. Radians are stored. That's fine, but make sure the input widget uses the same unit convention (degrees in, converted to radians on save — or store degrees and convert in the mixin). Currently the mixin reads radians from the bone, so the editor must store radians.
- **Keybind category:** `new KeyMapping.Category("playerpose")` — the category ID is used for the lang key `key.categories.playerpose` which exists. Confirm it shows up in the keybinds screen.

---

## 4. Build / infra

- Gradle wrapper is present (`gradlew`, `gradlew.bat`).
- `build.gradle` uses `${loom_version}`, `${fabric_api_version}` etc. from `gradle.properties` — those are set.
- No `repositories` block in `build.gradle` — Fabric Loom adds Maven Central + Fabric Maven by default, so this is fine for the declared deps.
- `java { sourceCompatibility = VERSION_25 }` — matches the toolchain.
- Run `./gradlew build` to verify it compiles.

---

## 5. Quick checklist

- [ ] Add rotation input widgets to `PoseEditorScreen`
- [ ] Add missing lang keys to `en_us.json`
- [ ] Decide F5/F7 semantics and document or adjust
- [ ] Verify radian/degree convention between editor and mixin
- [ ] (Optional) Add pose preview panel
- [ ] (Optional) Add pose delete + list/selector UI
- [ ] `./gradlew build` passes
