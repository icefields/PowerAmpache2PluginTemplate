# Power Ampache 2 — plugin template & Android Auto (dev)

This repository is the **Power Ampache 2 plugin template**: `domain`, `data`, `app`, and `PowerAmpache2Theme` modules with Clean Architecture.

On **`cursor-cloud/dev-main-4dc1`**, work focuses on a **functional** Android Auto browse/playback path via **`Pa2MediaLibraryService`**, **`MusicFetcher`**, and the host’s **`PA2DataFetchService`** IPC. **Known bugs** remain until addressed before release.

**Agents and contributors:** read **[`AGENTS.md`](AGENTS.md)** (branch policy, scope, build/DHU, verification, upstream checklist).

## Design reference (DHU)

Static reference image: **`mockups/assets/`**. The head unit renders now playing; the plugin supplies Media3 session metadata — the PNG is a **reference**, not a promise of identical pixels on every device.

| | |
| --- | --- |
| ![DHU — now playing](mockups/assets/dhu-now-playing.png) | *DHU — now playing (reference)* |

**Full mockups & research** live on branch **`mockups`** — see that branch’s [README](https://github.com/shahzebqazi/PowerAmpache2PluginTemplate/blob/mockups/README.md) on GitHub.

## Branches (this fork)

| Branch | Role |
| --- | --- |
| **`main`** | Tracks **`upstream/main`** only (`icefields/PowerAmpache2PluginTemplate`). No feature work; sync with `git fetch upstream` + `git reset --hard upstream/main` when aligning with upstream. |
| **`cursor-cloud/dev-main-4dc1`** | Integration branch; topic branches use the **`cursor-cloud/`** prefix. |
| **`mockups`** | Design/docs assets only (no app code on that branch). |

## Contributing upstream

See **Contributing upstream (`PluginAndroidAuto`)** in [`AGENTS.md`](AGENTS.md). Do not change **`domain/`** or **`data/`** upstream unless the **upstream developer explicitly approves**. Match upstream **CI** and contribution rules published there.
