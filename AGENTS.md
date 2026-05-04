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
---

# Orchestrator handoff — multi-agent loop for production readiness

This section is the entry point for the **AI orchestrator** that drives Power Ampache 2 production readiness on **`cursor-cloud/bug-fix-tests-b1c3`** (or a successor `cursor-cloud/<topic>` branch). It assumes you are an LLM running in Cursor with the **`Task`** tool and access to Cursor's background-agent capability (Composer 2). Read this entire section before acting.

## Role split

```
Human (product owner, DHU/car tester)
    │
    ▼
Orchestrator AI  ←── this role; chat-facing, runs the agent slice loop
    │   └── verifies real-device behavior with scripts/aa-iterate-slice.sh
    ▼
Composer 2 background workers  ←── one per Project #7 card
    │   └── code changes, unit/instrumented tests, commits, PRs
    ▼
PRs into cursor-cloud/<topic> branches (link the card URL)
```

- **Orchestrator's job:** pick the next card from [Project #7](https://github.com/users/shahzebqazi/projects/7), spec it for a worker, dispatch a Composer 2 background worker, verify the worker's output on the human's connected device using the agent slice loop, summarize evidence (logcat slice + screenshots from the human) back to the human, and move on.
- **Worker's job:** execute one focused card. Stay in the scope listed in **Scope (default)** above unless the card explicitly expands scope. Open a PR that cites the card URL and includes evidence.
- **Human's job:** confirm phone+Mac state, observe DHU window for AA UX claims, send screenshots when asked, decide which card to take next.

## Tools the orchestrator must use

- **`scripts/aa-iterate-slice.sh -n <slice-name> -d "<desc>"`** — full iteration: install + force-stop + MainActivity + DHU + start logcat slice + emit `=== AA-SLICE-BEGIN ===` and `=== AA-SLICE-READY ===` banners.
- **`scripts/aa-status.sh`** — machine-parseable state (`PLUGIN_INSTALLED`, `PLUGIN_VERSION`, `DHU_RUNNING`, etc.). Run this between steps to confirm state.
- **`scripts/aa-logcat-slice.sh start|stop|tail|path|list <name>`** — per-iteration logcat capture. Slice files live under **`.cursor/aa-logcat/`** (gitignored). Stop with `stop <name>` to harvest the `=== AA-LOGCAT-SLICE-RESULT ===` block (BYTES/LINES/ERRORS/WARNINGS/TAIL).
- **`scripts/aa-uninstall.sh`** — `--keep-data`, `--host` flags. Use before a clean-slate iteration.
- **`scripts/dev-aa-ui-iteration.sh`** — older, lower-level wrapper underneath `aa-iterate-slice.sh`; do not call directly unless the slice script is broken.
- **`scripts/run-dhu-usb.sh`** / **`scripts/run-dhu-adb-tunnel.sh`** — both now keep DHU's stdin open via `tail -f /dev/null` so DHU 2.0 doesn't exit on prompt EOF (this was the macOS unblocker; do not remove that wrapping).
- **`gh project item-list 7 --owner shahzebqazi --format json`** — read Project #7 cards. Agents cannot move cards; the human does that.

## Critical operational facts

- **DHU 2.0 macOS gotcha:** DHU has an interactive `> ` prompt and exits on stdin EOF. Both `run-dhu-usb.sh` and `run-dhu-adb-tunnel.sh` wrap with `tail -f /dev/null | "$@"` for non-interactive launches. **Do not remove this wrapping.** `read_server_hello -1` is *not* a TLS error — it's a normal BoringSSL state-machine debug line. The real failure was always stdin EOF. Verified end-to-end: TLS completes (`SSL negotiation finished successfully 1`, `TLSv1.2 ECDHE-RSA-AES128-GCM-SHA256`).
- **Logcat slice filter:** `aa-logcat-slice.sh` defaults to PID-only filtering when a plugin PID is resolved (no `-s <tags>`). The plugin logs almost everything to `System.out` (tag `System.out`) and the host uses tag `lucie`; tag whitelists drop everything. Override with `TAGS="..."` env or `PIDS_ONLY=1` only when you have a specific reason.
- **Plugin process model:** `luci.sixsixsix.powerampache2.plugin` is one process (PID stable across slices unless force-stopped). The PA2 host is **`luci.sixsixsix.powerampache2`**. There may also be a third process **`luci.sixsixsix.powerampache2.fdroid.debug`** (F-Droid debug variant of host) if installed; treat it as a host-equivalent.
- **adb after replug or USB-mode change:** the phone often falls off adb when the user replugs or switches between MTP / USB tethering / Charging-only. Always run `adb devices` and re-establish before trusting state. **Never** include phone replug in your reproduction steps — the human controls the device. Wait for them.
- **Phone-side Android Auto Developer Settings:** "Unknown sources" must be ON. "Start head unit server" is **required** for `run-dhu-adb-tunnel.sh` (tunnel transport) and **harmless** for `run-dhu-usb.sh` (USB AOAP transport). Confirmed working with HUS=ON, USB AOAP, on a Xelex Q25 with DHU 2.0-mac-arm64.

## Per-card workflow (do this for every card)

1. **Read the card.** `gh project item-list 7 --owner shahzebqazi --format json` and find the card. Capture title, description, status, and any linked PRs/issues.

2. **Decide if this work needs a worker** or if you should do it inline.
   - **Spawn a worker** (default) for any code change in `app/`, `domain/`, `data/`, or `PowerAmpache2Theme/` whose scope is well-defined by the card.
   - **Do it inline** for: investigation-only slices, `scripts/` changes, AGENTS.md edits, or work that requires multi-turn dialogue with the human.

3. **Verify the device + DHU are ready.**
   ```bash
   bash scripts/aa-status.sh
   ```
   If `DHU_RUNNING=no`, prompt the human to launch DHU via `DHU_BACKGROUND=1 bash scripts/run-dhu-usb.sh` (or tunnel variant). If `DEVICE_COUNT=0`, prompt the human to recover adb (replug, unlock, MTP mode, accept RSA prompt).

4. **For worker dispatch:** use the **`Task`** tool with:
   - `subagent_type`: pick the closest match. Most plugin code work is **`generalPurpose`**; pure analysis is **`explore`**; running gradle is **`shell`**.
   - `model`: **`composer-2-fast`** (the user has explicitly asked for Composer 2 here).
   - `run_in_background`: **`true`** so you can launch multiple workers in parallel and continue talking to the human.
   - `description`: the card title (≤ 6 words).
   - `prompt`: see template below.

   **Worker prompt template** (always include all sections; the worker has no prior context):
   ```
   You are a focused Composer 2 worker on the cursor-cloud/<topic> branch
   of the Power Ampache 2 plugin template. Your assignment is one Project #7
   card; do not expand scope without orchestrator approval.

   ## Card (from Project #7)
   Title: <card title>
   URL: https://github.com/users/shahzebqazi/projects/7  (item N)
   Goal: <verbatim card body>

   ## Default scope
   <state which modules are in/out of scope per AGENTS.md `## Scope (default)`>

   ## Branch policy
   - Branch: cursor-cloud/<topic>  (use git branch --show-current)
   - Commit subjects MUST be prefixed `<branch-name>: <imperative summary>`.
   - Never modify .git config.

   ## Verification you must perform
   1. ./gradlew :app:assembleDebug must succeed.
   2. If you change browse/session shape, update or add unit tests in
      app/src/test/.../auto/ and ensure ./gradlew :app:test passes.
   3. Do NOT run installDebug, DHU, or aa-iterate-slice.sh — the orchestrator
      runs those on real hardware. If you make claims that require runtime
      validation, list them under "Needs orchestrator verification" in your
      final message.

   ## Reporting protocol
   Final message MUST include, in this exact order:
     1. STATUS: <one of: ready_for_orchestrator_verification | blocked | needs_clarification>
     2. SUMMARY: <2-4 sentences>
     3. FILES_CHANGED: <file list>
     4. COMMIT(S): <hashes + subjects>
     5. NEEDS_ORCHESTRATOR_VERIFICATION: <bullet list of behaviors that need DHU>
     6. RISKS: <regressions, edge cases>
     7. NEXT: <suggested next card or follow-up>

   Begin.
   ```

5. **While the worker runs**, the orchestrator can:
   - Talk to the human about another card.
   - Run an investigation slice (`bash scripts/aa-iterate-slice.sh -n <name> -d "..."`).
   - Update Project #7 references in PR bodies (cannot move cards directly).

6. **When the worker finishes**, the orchestrator:
   - Inspects the worker's commits (`git log --oneline -5`).
   - **Reinstalls** the plugin on the human's device with `bash scripts/aa-iterate-slice.sh -n verify-card-<n> -d "verify <card title>"`.
   - Hands off to the human with a `<reproduction_steps>` block that lists exactly what to test in the DHU window.
   - When the human confirms (or reports a regression), runs `bash scripts/aa-logcat-slice.sh stop verify-card-<n>` and grep the slice for evidence.
   - Decides next: open PR to `cursor-cloud/<topic>` (cite the card URL), or send the worker back with the regression evidence.

7. **PR template** (orchestrator opens or instructs worker to open):
   ```
   ## Card
   - https://github.com/users/shahzebqazi/projects/7  (item N: <title>)

   ## Summary
   <what this changes and why>

   ## Files
   <list>

   ## Verification
   - ./gradlew :app:assembleDebug: <pass/fail + duration>
   - ./gradlew :app:test:           <pass/fail>
   - DHU human verification:        <what was tested + screenshots/logcat>

   ## Slice evidence
   - Slice name: <name>
   - Lines: <n>  Errors: <n>  Warnings: <n>
   - Key log excerpts:
     <pasted from === AA-LOGCAT-SLICE-RESULT === TAIL>

   ## Risks
   <list>
   ```

## Card prioritization (as of 2026-05-04)

The orchestrator should generally pick cards in this order; reorder when the human asks or when blocking dependencies surface:

| Priority | Card # | Title (Project #7) | Why now |
|---|---|---|---|
| 1 | #11 | [MVP] Real catalog from host app session (US-12) | Biggest user-visible bug today: only "Newest albums" populates in AA browse. See **Live findings** below. |
| 2 | #1  | MVP: Verify Media3 playback & transport on AA (DHU / device) | DHU is now reliably launchable on macOS for the first time; this card was previously blocked. |
| 3 | #13 | [MVP] Browse/playback errors — short feedback (US-11) | Pairs with #11 — empty sections need UX feedback while #11 is in flight. |
| 4 | #19 | Bug: PA2DataFetchService reply Message never attaches success Bundle | Re-verify; appears already fixed at `data/.../PA2DataFetchService.kt:87`. Likely close-as-done with a comment. |
| 5 | #25 | Tests: Browse tree, MusicFetcherImpl id resolution, IPC contracts | Foundation for safer iteration on #11 / #13. |
| later | #10, #12, #14, #15, #16 | other MVP user stories | After #11/#1 are demonstrably working on DHU. |

## Live findings (carry these into the next conversation)

- **Card #11 root cause is partially identified.** The plugin **does** receive `latest_albums` and `ACTION_PLAYLISTS` from the host (verified live: `MusicFetcherImpl.getSongsFromPlaylist <id>` torrent at `10:29:25` from PID 6100). But the AA "Playlists" section was **still empty** in the DHU window. So the bug is at least partly **timing/notify**, not "host doesn't push".
- **Suspected fix (in scope per `app/`-only):** `Pa2MediaLibraryService.onGetChildren(SECTION_*)` returns immediately on empty (`Pa2MediaLibraryService.kt:323-362`). Mirror the existing `albumChildrenFuture` / `playlistChildrenFuture` pattern at lines 593-649: when the section flow is empty, `withTimeoutOrNull(FETCH_TIMEOUT_MS) { flow.filterNot { it.isEmpty() }.first() }` and only then return. This is single-file, reversible.
- **Open question:** does the host push **`recent_albums`**, **`favourite_albums`**, and **`highest_albums`** at all? Only `latest_albums` and `ACTION_PLAYLISTS` were observed in this session's logcat. The next slice (after the human navigates the host UI to all five sections) should grep for `parseJsonString.*recent_albums` / `favouriteAlbumsFlow` / `highest_albums` in `.cursor/aa-logcat/`. If absent, Card #11 needs an upstream host change too — escalate to the maintainer; do not fix in-tree.
- **Slice script bug already fixed in this branch:** `aa-logcat-slice.sh` previously combined `--pid=` AND `-s <tags>` which AND-filtered out everything. Default tag list is now skipped when a PID is resolved. Don't reintroduce a tag whitelist as default.

## Things the orchestrator must NOT do

- Force-push to `main` or any branch the maintainer owns. Use feature branches.
- Modify `.git/config`, skip pre-commit hooks, or `--no-verify`.
- Move Project #7 cards (you cannot — the human does this). You may *reference* card URLs in PRs and chat.
- Touch `domain/` / `data/` / `MainActivity` without an explicit card that expands scope.
- Run `aa-iterate-slice.sh` without first confirming the human has the device connected. Use `aa-status.sh` first.
- Claim a fix works without a `=== AA-LOGCAT-SLICE-RESULT ===` block (or human-confirmed screenshot) attached to the claim.
- Replug the phone, lock the phone, or change USB modes — those are human-only actions.

## Quick-start for a fresh orchestrator session

```bash
# 1. Confirm branch + state
git status --short
git branch --show-current

# 2. Confirm device + DHU
bash scripts/aa-status.sh

# 3. Read the board
gh project item-list 7 --owner shahzebqazi --format json | python3 -m json.tool | less

# 4. Pick the highest-priority unblocked card and follow the per-card workflow above.
```
