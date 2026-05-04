# Agents — Power Ampache 2 plugin template

Single onboarding doc for **humans** and **autonomous agents** (Cursor, CI bots). Follow this file; do not maintain parallel prose elsewhere.

## Goals

- **Android Auto that works** — browse and playback via Media3 (`Pa2MediaLibraryService`) verifiable on phone, DHU, or car—not “should work” from compile-only checks.
- **Reliable host IPC** — library data flows through **`MusicFetcher`** only after the Power Ampache 2 host binds **`PA2DataFetchService`** and Messenger delivers JSON; the plugin does not replace that pipeline.
- **Evidence-first** — `./gradlew` output + `adb logcat` when debugging; **screenshots/recordings** when claiming AA UI works (agents cannot see the user’s screen).

## Git

- **`main`** tracks **`upstream/main`** only (`icefields/PowerAmpache2PluginTemplate`). No feature or agent commits there; sync with `git fetch upstream` + `git reset --hard upstream/main` when asked.
- **Work on** **`cursor-cloud/dev-main-4dc1`** or **`cursor-cloud/<topic>`** branches.
- **Commit subjects (dev branches):** **`<branch-name>: <short imperative summary>`** using the **full** output of `git branch --show-current`. Rebases rewrite hashes; the prefix preserves attribution.

## Scope (default)

| In bounds | Out of bounds (unless maintainer explicitly expands scope) |
| --- | --- |
| **`app/`** — AA / Media3 / service / manifest / app tests / resources | **`domain/`**, **`data/`** (incl. **`PA2DataFetchService`**, **`MusicFetcher` / `MusicFetcherImpl`**, DTOs) |
| **`PowerAmpache2Theme`** when the task allows UI/theme work | **`MainActivity`** launcher contract / “open host + finish” flow for routine AA tasks |
| Coordinated changes **only** when assigned with a real host+plugin spec | Ad-hoc “IPC hardening”, “DTO keys”, **`START_STICKY`** tweaks, or protocol invention in shared layers |

**History:** A batch of commits on **`cursor-cloud/dev-main-4dc1`** (~2026‑04‑12; subjects around IPC / DTO / “harden”) was **reverted** as misaligned. **Do not** recreate that pattern without maintainer direction.

**Architecture:** Modules `domain`, `data`, `app`, `PowerAmpache2Theme` — respect Clean Architecture boundaries.

## Android Auto — behavior agents must respect

- **Browse tree:** **`Pa2MediaLibraryService`** — wire **only** through **`MusicFetcher`**. Stable IDs: **`MediaIds.kt`**. Host often must be opened (e.g. **`MainActivity`**) before lists populate.
- **Library search:** **Not implemented** (backlog). The service strips library search commands on connect so controllers do not show a broken search UI (**`Pa2LibraryCallback`** KDoc). To ship search: implement callbacks, then stop removing those commands.
- **Surfaces:** Google **“For You”** / recommendation widgets **≠** this repo’s **Media** browse tree—confirm repro scope before changing browse code.
- **Now Playing / queue:** Depends on host pushing **`MusicFetcher.currentQueueFlow`** and session mirroring; stale metadata → verify host IPC and playback sync in **`app/`**, not by editing **`domain/`** / **`data/`** without scope.

## Build environment

- **`local.properties`** → **`sdk.dir=...`** (gitignored). Align `sdk.dir` with the machine’s Android Studio SDK (common macOS path: `~/Library/Android/sdk`).
- **JDK:** Prefer **17 or 21** for Gradle. This repo’s **`gradlew`** may prefer a **project-local JDK 21** under **`.jdks/jdk-21*`** when present (`.jdks/` gitignored). **JDK 26+** Gradle daemons can throw **Java version parse errors**—do not claim `./gradlew` works without verifying JDK.
- **Signing:** Installing **debug** over **release** may require **`adb uninstall`** (signature mismatch). Root **`*.apk`** is gitignored.

## Debugging and logcat

Use **`adb logcat`** while reproducing. Useful filters (adjust as needed):

```bash
adb logcat | grep -E 'Pa2Media|MediaLibrary|ExoPlayer|MediaSession|AndroidRuntime'
```

Also filter by package **`luci.sixsixsix.powerampache2.plugin`** when isolating plugin-only noise.

## Startup (expect messy ordering until unified)

Several paths touch **`PA2DataFetchService`** (`PluginApplication`, **`Pa2MediaLibraryService`**, host **`register_client`**). **Cold repro:** `adb shell am force-stop luci.sixsixsix.powerampache2.plugin`, then vary **host first vs AA first** and inspect **`PA2DataFetch`**, **`MusicFetcherImpl`**, **`Pa2MediaLibraryService`** for `clientMessenger` / JSON flow.

## Verification

- **`./gradlew :app:assembleDebug`** (and **`installDebug`** when `adb` exists). No success claims without command output.
- **Browse/session regressions:** **`./gradlew :app:connectedDebugAndroidTest`** when a device is available; update **`Pa2MediaLibraryInstrumentedTest`** if root structure or **`MediaIds`** change.
- **Instrumented tests** validate **MediaBrowser API**, not DHU pixels — human DHU/car remains authoritative for “looks right.”

## DHU / install iteration

After AA UI fixes (unless the human skips):

```bash
./scripts/dev-aa-ui-iteration.sh
ANDROID_SERIAL=<serial> ./scripts/dev-aa-ui-iteration.sh   # multiple adb devices
```

**Options:** `INSTALL_ONLY=1` or `NO_DHU=1` — skip DHU stop/start; `NO_MAIN=1` — skip **`MainActivity`** after force-stop.

**DHU install:** Android SDK **`extras;google;auto`** (`sdkmanager`). **USB AOAP (recommended for DHU 2.x):** **`scripts/run-dhu-usb.sh`** — set **`ANDROID_SERIAL`** if multiple devices; **`DHU_BACKGROUND=1`** for detached run (Linux may use **`systemd-run --user --scope`** inside the script when available). **Linux/Wayland:** script may set **`SDL_VIDEODRIVER=x11`**; **`libc++`** needed on some distros. **Fallback:** on phone enable Android Auto **Developer → Start head unit server**, then:

```bash
adb forward tcp:5277 tcp:5277
./scripts/run-dhu-adb-tunnel.sh
```

**macOS:** system **`bash` is 3.2** — run DHU scripts with **`bash ./scripts/run-dhu-usb.sh`** if `./scripts/...` fails; USB TLS failures may require the **tunnel** path above.

## Projects and PRs

- Agents **cannot** manage GitHub Projects from the repo. Maintainer board: [Project #7](https://github.com/users/shahzebqazi/projects/7). Draft cards: **`./scripts/create-project-7-android-auto-cards.sh`** (requires **`gh`** with **`project`** scopes). Link assigned issue/card URLs in PRs; **do not block** on missing cards—state assumptions in the PR.

### Guideline-alignment work (when a card or issue is assigned)

1. **Review** [Android for Cars — media](https://developer.android.com/training/cars/media), [Content hierarchy](https://developer.android.com/training/cars/media/create-media-browser/content-hierarchy), [Design for Driving — media](https://developers.google.com/cars/design/create-apps/media-apps/overview). Cite what drove each change in the PR.
2. **Default scope:** **`app/`** only (`Pa2MediaLibraryService`, **`MediaIds`**, manifest, tests, resources). No **`domain/`** / **`data/`** / **`MainActivity`** launcher changes unless the card or maintainer expands scope.
3. **Verify:** **`./gradlew :app:assembleDebug`**; if browse/session changed, **`./gradlew :app:connectedDebugAndroidTest`** when possible.
4. **PR:** Link issue/card URL; list **automated** vs **human DHU/car** verification; attach **logcat** + **screenshots** for AA-facing changes when possible.
5. **Do not block** on the board—implement when asked; note if a card should be added to Project #7.

## Contributing upstream (`PluginAndroidAuto`)

One-off PRs to **`icefields/PowerAmpache2PluginTemplate`**:

| Topic | Policy |
| --- | --- |
| **Base branch** | **Confirm with upstream maintainer** (`PluginAndroidAuto`, **`main`**, or other) before rebasing—do not assume **`PluginAndroidAuto`** exists. |
| **Contributor branch** | Prefer **`cursor-cloud/<name>-PluginAndroidAuto-<topic>-<id>`** (or maintainer naming). |
| **`domain/` / `data/`** | **No** changes unless **explicitly approved** for that contribution. Default: **`app/`** only. |
| **Shape** | **`git fetch upstream`**; small branch off agreed base; **`./gradlew :app:assembleDebug`** (and tests upstream expects); PR body lists **`app/`** vs shared layers and cites approval if shared layers change. |
| **Host / IPC** | Plugin depends on host **`PA2DataFetchService`**—state what you verified in the PR. |

## Upstream vs this fork

This fork integrates on **`cursor-cloud/dev-main-4dc1`**. **`main`** stays a clean mirror of **`upstream/main`**.
