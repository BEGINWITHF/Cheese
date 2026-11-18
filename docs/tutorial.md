# Cheese illustrated guide

This guide walks through the pose editor from opening it to saving and sharing a pose.
Screenshots live in `docs/images/tutorial/`; each section names the file it expects.

> Screenshot checklist for contributors is at the bottom of this page.

## 1. Before you start

- Fabric Loader and Fabric API installed for your Minecraft version.
- The matching `cheese-2.2.0-<version>.jar` in `mods/`.
- Stand still and upright on solid ground. The editor refuses to open while swimming,
  crawling, sneaking, riding, climbing, in water/lava, or elytra flying.

## 2. Opening the editor

Press **Backspace** (rebindable in *Options → Controls → Cheese*).

![In-game hint](images/tutorial/02-open-hint.png)

*The status line confirms why the editor cannot open when you are not standing.*

## 3. The interface

![Editor overview](images/tutorial/03-overview.png)

| Area | What it does |
|---|---|
| Name bar | Current pose name, **Rename**, **New**, **Save**, **Done** |
| Mode buttons | **Rotate** draws rings; **Move** draws axis lines |
| Reset buttons | **Reset** clears the selected bone; **Reset All** clears the pose |
| Tabs | **Bones** (mannequin and joint list), **Poses** (saved poses) |
| Viewport | The 3D model, gizmo, and camera |
| Skin button | Opens the skin and cape panel |
| Status bar | Hints and the last action |

## 4. Making a pose

### 4.1 Create it

Press **New** and enter a name. A new pose is a draft until you press **Save**.

![New pose dialog](images/tutorial/04-new-pose.png)

### 4.2 Pick a joint

Click a bone on the mannequin or in the list: Body, Head, arms, forearms, legs, shins,
or Cape.

![Bone selected](images/tutorial/05-select-bone.png)

### 4.3 Rotate

With **Rotate** active, drag the red, green, or blue ring. The gizmo sits on the real
joint, so elbows and knees hinge where they should.

![Rotate gizmo](images/tutorial/06-rotate.png)

### 4.4 Move

Switch to **Move** and drag an axis line to offset the selected bone. Move mode shows
lines instead of rings so the two modes stay distinct.

![Move gizmo](images/tutorial/07-move.png)

### 4.5 Camera

- Drag empty viewport space to orbit. The orbit is unrestricted, including top-down.
- Scroll to zoom.
- The gizmo stays anchored to the joint at every camera angle.

## 5. Eyelids

Open the **Eyes** panel to adjust the left and right eyelid separately. The values are
saved with the pose and work with SkinSpice and Just Expressions skins.

![Eyelid editor](images/tutorial/08-eyes.png)

## 6. Preview skins and capes

Click **Skin** at the top-right of the viewport.

![Skin panel](images/tutorial/09-skin-panel.png)

- **Player**: type a Mojang name and press **Load**.
- **File**: press **Browse** and pick a 64x64 or legacy 64x32 PNG.
- **Arms: Classic / Slim** switches the preview model.
- **Original skin** restores your real skin.
- The thumbnail shows the selected skin. It is editor-only and never changes your account.

### Capes

The cape tab offers 18 bundled official capes, the player's own cape, and a file import.
Elytra poses have an X/Y/Z spread panel so the wings can open independently.

![Cape panel](images/tutorial/10-cape-panel.png)

## 7. Saving and applying

1. Press **Save** to write the pose to `config/player-pose`.
2. On the **Poses** tab, press the **`+`** next to a pose.
3. Tick yourself and any online players. Checkboxes apply or remove the pose immediately.
4. Rows tint by the share of online players using them: no tint when unused, up to full
   opacity when everyone uses it.
5. **Unload all** clears your pose and every remote assignment.

![Target picker](images/tutorial/11-targets.png)

Poses are client-side only: other players see their own game, and nothing is sent to the
server.

## 8. Editing and history

- **Rename** edits the name as a draft until you press **Save**.
- **Delete** removes the pose.
- `Ctrl+Z` / `Ctrl+Y` undo and redo pose edits.
- **Reset** affects the selected bone; **Reset All** clears rotations, translations, the
  cape and the elytra.

## 9. Sodium and mod conflicts

The **COMPAT** badge lists model or animation overrides that can fight the editor, such as
3D Skin Layers, Entity Model Features, or Sodium's entity mixins.

![Compatibility dialog](images/tutorial/12-sodium.png)

If Sodium reports `1 override(s) found`, Cheese offers **Restart now** or **Later**. A
restart is required because Sodium reads its mixin rules at startup. The restore steps are
also available online:
<https://raw.githubusercontent.com/BEGINWITHF/Cheese/main/docs/sodium-restore.md>

## 10. Troubleshooting

| Symptom | Fix |
|---|---|
| Editor does not open | Stand still and upright on solid ground |
| Model looks dark at some angles | Update to a 2.2.0 jar built after the lighting fix |
| Second layer does not follow | Make sure `cheese-2.2.0-...` is the only Cheese jar in `mods/` |
| Poses missing | Check `config/player-pose` is writable and the mod matches your game version |

---

## Screenshot checklist

Place the files in `docs/images/tutorial/`. Capture at 1920x1080 or larger, GUI scale 2,
windowed or fullscreen, with a clean skin and no debug HUD. Crop to the relevant area
where noted.

| File | What to capture | Notes |
|---|---|---|
| `02-open-hint.png` | In-game view with the "stand still" hint | Crop the status line |
| `03-overview.png` | Full editor, a pose selected | Annotate: panel, viewport, status bar |
| `04-new-pose.png` | New/Rename dialog with a typed name | Crop to the dialog |
| `05-select-bone.png` | Mannequin with one bone highlighted | Show the bone list too |
| `06-rotate.png` | Rotate mode, one ring hovered or dragged | Close-up of the gizmo |
| `07-move.png` | Move mode with axis lines | Close-up of the gizmo |
| `08-eyes.png` | Eyes panel with L/R sliders | Show the model's eyes |
| `09-skin-panel.png` | Skin panel, Player tab and thumbnail | Crop to the panel |
| `10-cape-panel.png` | Cape tab with the cape list and elytra sliders | Crop to the panel |
| `11-targets.png` | Pose row expanded with target checkboxes | Show self and one online player |
| `12-sodium.png` | COMPAT tooltip or Sodium restart dialog | Crop to the dialog |

Optional extras: a before/after pose pair for the README hero, and a short GIF of a ring
drag for CurseForge.
