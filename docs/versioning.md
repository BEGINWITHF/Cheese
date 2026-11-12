# Versioning and release matrix

Cheese ships one jar per Minecraft compatibility group. Minecraft point releases are
not binary compatible, so a single jar cannot cover the whole 1.21 line.

## Scheme

```
<mod version>-<minecraft version>
```

- **Mod version** (`2.2.0`) describes the feature set. Every target in the same feature
  release has the same features.
- **Minecraft version** names the target. A single version looks like `26.3` or
  `1.21.4`; a compatibility group uses a hyphen range, for example `1.21.6-1.21.8`.
- Bugfixes bump the mod version for every target that receives the fix, for example
  `2.2.1-1.21.4`. A target can stay on an older patch if it was not rebuilt.

## GitHub releases and tags

One GitHub release covers one mod version. The tag is `v<mod version>` and every
supported Minecraft jar is attached to that release:

```
tag:       v2.2.0
title:     v2.2.0
assets:    cheese-2.2.0-26.3.jar
           cheese-2.2.0-26.2.jar
           cheese-2.2.0-26.1.x.jar
           cheese-2.2.0-1.21.9-1.21.11.jar
           cheese-2.2.0-1.21.6-1.21.8.jar
           cheese-2.2.0-1.21.5.jar
```

- Jar names use the hyphen range form, for example
  `cheese-2.2.0-1.21.6-1.21.8.jar`.
- A jar that covers several point releases says so in its name:
  `cheese-2.2.0-1.21.6-1.21.8.jar` covers 1.21.6, 1.21.7 and 1.21.8.
- The release notes list every supported Minecraft version and the matching
  Fabric API / Java version.
- Adding a target later means uploading another jar to the same release. Do not create a
  second release for the same mod version.
- Git refs and GitHub asset names cannot contain `~`, so the hyphen form is used
  everywhere.

The in-game mod version stays the plain feature version:

```
fabric.mod.json:   2.2.0
```

The old per-target tags (`v2.2.0-26.2`, `v2.2.0-1.21.5`, ...) are retired. Every 2.2.0
build lives under `v2.2.0`. Releases that predate this rule (for example `v2.1.0`,
`v2.0.0-beta-26.2`) keep their tags.

## 1.21 compatibility groups

Groups are provisional until each one compiles and runs. A group gets one jar whose
`fabric.mod.json` accepts the whole Minecraft range.

| Group | Minecraft versions | Artifact | `fabric.mod.json` range |
|---|---|---|---|
| A | 1.21, 1.21.1 | `cheese-2.2.0-1.21-1.21.1.jar` | `>=1.21 <1.21.2` |
| B | 1.21.2, 1.21.3 | `cheese-2.2.0-1.21.2-1.21.3.jar` | `>=1.21.2 <1.21.4` |
| C | 1.21.4 | `cheese-2.2.0-1.21.4.jar` | `~1.21.4` |
| D | 1.21.5 | `cheese-2.2.0-1.21.5.jar` | `~1.21.5` |
| E | 1.21.6, 1.21.7, 1.21.8 | `cheese-2.2.0-1.21.6-1.21.8.jar` | `>=1.21.6 <1.21.9` |
| F | 1.21.9, 1.21.10, 1.21.11 | `cheese-2.2.0-1.21.9-1.21.11.jar` | `>=1.21.9 <1.21.12` |

All of them are attached to the `v2.2.0` release.

## 26.x line

| Minecraft | Branch | Artifact |
|---|---|---|
| 26.1, 26.1.1, 26.1.2 | `26.1/stable` | `cheese-2.2.0-26.1.x.jar` |
| 26.2 | `26.2/stable` | `cheese-2.2.0-26.2.jar` |
| 26.3 | `main` | `cheese-2.2.0-26.3.jar` |

All of them are attached to the `v2.2.0` release.

## 1.20 compatibility groups

| Group | Minecraft versions | Artifact | `fabric.mod.json` range |
|---|---|---|---|
| A | 1.20, 1.20.1 | `cheese-2.2.0-1.20-1.20.1.jar` | `>=1.20 <1.20.2` |
| B | 1.20.2 | `cheese-2.2.0-1.20.2.jar` | `~1.20.2` |
| C | 1.20.3, 1.20.4 | `cheese-2.2.0-1.20.3-1.20.4.jar` | `>=1.20.3 <1.20.5` |
| D | 1.20.5, 1.20.6 | `cheese-2.2.0-1.20.5-1.20.6.jar` | `>=1.20.5 <1.21` |

Future feature versions follow the same rule, for example `v2.2.1` with all rebuilt
targets attached.

## Build wiring

Each worktree has two Minecraft properties:

```properties
minecraft_version=1.21.8
mc_range=1.21.6-1.21.8
```

The Gradle project version is the full `mod-MC range` string, while `fabric.mod.json`
receives only the plain mod version:

```groovy
version = "${project.mod_version}-${project.findProperty('mc_range') ?: project.minecraft_version}"

processResources {
    expand "version": project.mod_version
}
```

That produces `cheese-2.2.0-1.21.6-1.21.8.jar` with `"version": "2.2.0"` inside. For a
single-version group, omit `mc_range` or set it to the same value as
`minecraft_version`.

## Development versions

While a group is being ported, use a pre-release version:

```
2.2.0-beta-1.21.4
```

The jar is named `cheese-2.2.0-beta-1.21.4.jar` and the in-game version is
`2.2.0-beta`. Publish it as a GitHub pre-release tagged `v2.2.0-beta`. Only after the
group reaches feature parity with 2.2.0 does it get the stable `2.2.0` version and join
the normal release.

## Release rules

1. One GitHub release per mod version, tagged `v<mod version>`.
2. Attach one jar per supported compatibility group.
3. The release body lists every Minecraft version with the matching Fabric API and Java.
4. `fabric.mod.json` declares the tested range, for example:

   ```json
   "depends": {
     "minecraft": ">=1.21 <1.21.2"
   }
   ```

5. Never reuse a full version for a different build. Bump the mod version instead.
6. Release titles always match `v<mod version>`.
