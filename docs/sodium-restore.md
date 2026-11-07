# Restoring Sodium after Player Pose's fix

Player Pose can disable Sodium's entity renderer so posed forearms and shins keep their
shape. This page explains how to undo that change and give entity rendering back to
Sodium.

## What was changed

Player Pose edits Sodium's mixin configuration:

```
config/sodium-mixins.properties
```

It sets this line:

```properties
mixin.features.render.entity=false
```

That disables Sodium's entity-renderer mixins (`CubeMixin`, `ModelPartMixin`, the entity
culling mixin and the entity shadow mixin). Sodium then uses the vanilla entity path,
which is what lets Player Pose bend the sliced limbs per vertex.

## Restore Sodium

1. Close Minecraft completely.
2. Open this file with a text editor:
   - Version-isolated instance (PCL, BHL, HMCL, Prism, etc.):
     `.minecraft/versions/<instance name>/config/sodium-mixins.properties`
   - Non-isolated game:
     `.minecraft/config/sodium-mixins.properties`
3. Find the line:

   ```properties
   mixin.features.render.entity=false
   ```

4. Change it to:

   ```properties
   mixin.features.render.entity=true
   ```

   Or delete the line completely; `true` is Sodium's default.
5. Save the file and start Minecraft again.

If the file does not exist, there is nothing to restore: Sodium is already using its
normal entity renderer.

Do not edit the other lines in the file. Sodium only reads this file at startup, so a
full game restart is required.

## Notes

- **Entity Culling**, **Entity Distance** and **Entity Shadows** in Sodium's video
  settings are unrelated to this switch.
- After restoring, Sodium's entity renderer takes over again and Player Pose's
  forearm/shin bends may look rigid. That is the original compatibility conflict, not a
  new bug.
- To re-apply the Player Pose fix, run the editor's Sodium prompt again or set the line
  back to `false`.
