# Versioning and release matrix

Cheese ships one jar per Minecraft compatibility group. Minecraft point releases are
not binary compatible, so a single jar cannot cover the whole 1.21 line.

## Scheme

```
<mod version>-<minecraft version>
```

- **Mod version** (`2.2.0`) describes the feature set. Every target in the same feature
  release has the same features.
- **Minecraft version** (`26.3`, `1.21.4`) names the version the jar was built and tested
  against.
- Bugfixes bump the mod version for every target that receives the fix, for example
  `2.2.1-1.21.4`. A target can stay on an older patch if it was not rebuilt.

The same string is used for the artifact, the Git tag and the release title:

```
artifact:          cheese-2.2.0-1.21.4.jar
tag:               v2.2.0-1.21.4
release title:     v2.2.0-1.21.4
```

The in-game mod version stays the plain feature version, exactly like the existing
releases:

```
fabric.mod.json:   2.2.0
```

Existing releases follow the same split: mod version `2.2.0`, release title
`v2.2.0-26.3`.

Pre-releases keep the existing style:

```
v2.2.0-beta-1.21.4
v1.0.0-alpha-26.2
```

Existing releases keep their current tags:

```
v2.1.0   (release title: v2.1.0-26.2)
v2.2.0   (release title: v2.2.0-26.3)
```

All new tags and releases use the full `v<mod>-<mc>` form.

## 1.21 compatibility groups

Groups are provisional until each one compiles and runs. A group gets one jar whose
`fabric.mod.json` accepts the whole Minecraft range.

| Group | Minecraft versions | Release |
|---|---|---|
| A | 1.21, 1.21.1 | `v2.2.0-1.21.1` |
| B | 1.21.2, 1.21.3 | `v2.2.0-1.21.3` |
| C | 1.21.4 | `v2.2.0-1.21.4` |
| D | 1.21.5 | `v2.2.0-1.21.5` |
| E | 1.21.6, 1.21.7, 1.21.8 | `v2.2.0-1.21.8` |
| F | 1.21.9, 1.21.10, 1.21.11 | `v2.2.0-1.21.11` |

The 26.x line keeps its existing releases:

| Minecraft | Release | Tag |
|---|---|---|
| 26.2 | `v2.1.0-26.2` | `v2.1.0` |
| 26.3 | `v2.2.0-26.3` | `v2.2.0` |

Future 26.x builds follow the new scheme, for example `v2.2.1-26.3`.

## Build wiring

The Gradle project version is the full `mod-MC` string, while `fabric.mod.json`
receives only the plain mod version:

```groovy
version = "${project.mod_version}-${project.minecraft_version}"

processResources {
    expand "version": project.mod_version
}
```

That produces `cheese-2.2.0-1.21.4.jar` with `"version": "2.2.0"` inside.

## Development versions

While a group is being ported, use a pre-release version:

```
2.2.0-beta-1.21.4
```

The jar is named `cheese-2.2.0-beta-1.21.4.jar` and the in-game version is
`2.2.0-beta`. Publish it as a GitHub pre-release. Only after the group reaches feature
parity with 2.2.0 does it get the stable `2.2.0-<mc>` version and a normal release.

## Release rules

1. One GitHub release per compatibility group, not per point release.
2. The release body lists every Minecraft version the jar supports.
3. `fabric.mod.json` declares the tested range, for example:

   ```json
   "depends": {
     "minecraft": ">=1.21 <1.21.2"
   }
   ```

4. Never reuse a full version for a different build. Bump the mod version instead.
5. Release titles always match the `v<mod>-<mc>` form shown in the release sidebar.
