# Repository layout

The repository follows the branch-per-version-line layout used by Sodium, Iris and
Create: every supported Minecraft line has its own branch, while `main` tracks the
latest line.

## Branches

| Branch | Meaning |
|---|---|
| `main` | Latest stable line. Currently Minecraft 1.21.11. |
| `<minecraft>/stable` | Frozen release line for that compatibility group. |
| `<minecraft>/dev` | Optional development branch for a line, created only when needed. |
| `feature/*`, `patch/*` | Short-lived topic branches. |

Current lines:

```
main                (1.21.11)
26.3/stable
26.2/stable
26.1/stable
1.21.11/stable
1.21.8/stable        covers 1.21.6-1.21.8
1.21.5/stable
1.21.4/stable
1.21.3/stable        covers 1.21.2-1.21.3
1.21.1/stable        covers 1.21-1.21.1
1.20.6/stable        covers 1.20.5-1.20.6
1.20.4/stable        covers 1.20.3-1.20.4
1.20.2/stable
1.20.1/stable        covers 1.20-1.20.1
```

`main` and `1.21.11/stable` contain the same line; `main` is the default branch and the
place for shared docs and CI.

## Tags and releases

- One GitHub release per mod version, tagged `v<mod version>` (for example `v2.2.0`).
- Every supported Minecraft jar is attached to that release; adding a target later means
  uploading another jar to the same release.
- Jar names use `-` for ranges: `cheese-2.2.0-1.21.9-1.21.11.jar`, because Git refs and
  GitHub asset names cannot contain `~`.
- The jar metadata keeps the plain feature version (`2.2.0`) and declares the supported
  Minecraft range in `fabric.mod.json`.

See `docs/versioning.md` for the full matrix.

## CI

`.github/workflows/build-all.yml` builds every `<minecraft>/stable` branch in a matrix.
Every job runs on Java 21 because Fabric Loom requires a 21+ JVM; 1.20.1-1.20.4 still
compile with `release = 17`. Each job uploads the built jar as an artifact.

Run it from **Actions -> Build version lines -> Run workflow**, or on every push to
`main`.

## Why branches instead of one Stonecutter tree

Stonecutter is a good fit when several Minecraft versions share almost the same code.
Cheese spans render pipelines from 1.20.1 to 1.21.11, including multiple model and GUI
rewrites. Keeping one branch per line isolates those rewrites, keeps every line
buildable, and matches how Sodium, Iris and Create maintain their version lines.
