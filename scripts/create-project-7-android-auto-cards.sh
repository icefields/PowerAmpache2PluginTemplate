#!/usr/bin/env bash
# Create draft cards on GitHub Project #7 (Android Auto guideline work items).
#
# Prerequisites:
#   1. GitHub CLI: gh auth login (or gh auth refresh) with scopes: read:project, project
#      Example: gh auth refresh -h github.com -s read:project -s project
#   2. Project must exist: https://github.com/users/shahzebqazi/projects/7
#
# Usage: from repo root, ./scripts/create-project-7-android-auto-cards.sh
# Re-running is NOT idempotent: each run adds duplicate drafts. Delete old drafts in the UI before re-running.

set -euo pipefail

OWNER="${GITHUB_PROJECT_OWNER:-shahzebqazi}"
PROJ="${GITHUB_PROJECT_NUMBER:-7}"

if ! gh auth status &>/dev/null; then
  echo "error: run 'gh auth login' first" >&2
  exit 1
fi

create_card() {
  local title="$1"
  local bodyfile="$2"
  echo "Creating: $title"
  # gh project item-create accepts --body only (no --body-file); read file into env safely.
  local body
  body="$(cat "$bodyfile")"
  gh project item-create "$PROJ" --owner "$OWNER" --title "$title" --body "$body"
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cat >"$TMP/1.md" <<'EOF'
Goal: Align browse root with Google’s Android for Cars guidance: root hints typically limit top-level tabs to four (MediaConstants.BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT).

Context: Pa2MediaLibraryService exposes five section nodes under root (playlists + four album sections). Some head units may drop or bury the fifth tab.

Tasks:
- Read: developer.android.com/training/cars/media/create-media-browser/content-hierarchy (“Structure the root menu”).
- In Media3, read LibraryParams / root-equivalent hints in onGetLibraryRoot or onGetChildren for parent root, and reshape the tree (e.g. nest two sections under one browsable “Albums” node, or merge categories) so default AA shows ≤4 top-level browsable children when the hint says 4.
- Update instrumented tests (root child count / IDs) and document the new hierarchy in the PR.

Out of scope unless agreed: changing domain/data or MainActivity launcher contract.

Agent instructions: AGENTS.md → “Guideline-alignment work”.
EOF

cat >"$TMP/2.md" <<'EOF'
Goal: Populate MediaMetadata artwork (artworkUri) for songs/albums where the domain model already provides URLs (e.g. Song.imageUrl), per Google media design guidance for in-car UI.

Tasks:
- Read: “Display media artwork” / browsing views in Android for Cars media docs and Design for Driving media checklist.
- In app/…/auto/Pa2MediaLibraryService (and helpers): set artwork on playable and browsable items when URLs are non-empty; handle invalid URIs gracefully.
- Verify assembleDebug; extend or add tests if metadata assertions are practical.

Scope: app/ only unless image URLs require data-layer fixes (then negotiate scope).

Agent instructions: AGENTS.md → “Guideline-alignment work”.
EOF

cat >"$TMP/3.md" <<'EOF'
Goal: Either implement play-from-search / voice search behavior expected for the manifest intent android.media.action.MEDIA_PLAY_FROM_SEARCH, or remove the intent filter if unsupported.

Tasks:
- Read: developer.android.com training/cars/media — voice actions, search; Media3 MediaLibrarySession.Callback for search/play-from-search APIs as applicable.
- Implement resolution of search queries into playable media, or document why the plugin defers to the host and adjust manifest accordingly.
- PR must state Assistant / AA test status (or “not tested on DHU” with logcat evidence for what was tested).

Scope: app/ preferred.

Agent instructions: AGENTS.md → “Guideline-alignment work”.
EOF

cat >"$TMP/4.md" <<'EOF'
Goal: Mitigate strict per-level item limits in Android Auto / AAOS when playlists or album track lists are very large (docs: “strict limits” on items per menu level).

Tasks:
- Read: content-hierarchy + browsing views design pages.
- Design chunked browsing or subfolders (e.g. “Page 2”, alphabetical buckets) without breaking MediaIds stability for existing clients if possible; document migration in PR.
- Add a test or documented cap behavior.

Scope: app/ only unless MusicFetcher contract must change.

Agent instructions: AGENTS.md → “Guideline-alignment work”.
EOF

cat >"$TMP/5.md" <<'EOF'
Goal: Optional polish: MediaLibrarySession.setSessionActivity to deep-link to an appropriate app screen; ensure service/app icon and theme support car branding (accent / colorPrimary as appropriate).

Tasks:
- Read: Media3 session docs and Google “Provide branding elements” for media apps.
- Implement session activity PendingIntent if product owner agrees on target activity (may be limited for plugin UX).
- Document what was set and what still requires human verification on DHU.

Scope: app/ only.

Agent instructions: AGENTS.md → “Guideline-alignment work”.
EOF

cat >"$TMP/6.md" <<'EOF'
Goal: Evaluate whether Pa2MediaLibraryService should restrict browse/session clients using an allowlist pattern (system apps, Android Auto, Google Assistant packages) while returning non-null root for trusted callers.

Tasks:
- Read: UAMP PackageValidator / developer.android.com media browser “Add package validation”.
- If implemented, document allowed packages and Assistant; do not block onGetRoot with slow I/O; keep behavior safe for Play policy.

Scope: app/ only.

Agent instructions: AGENTS.md → “Guideline-alignment work”.
EOF

create_card "Android Auto: Honor root hints (≤4 root tabs)" "$TMP/1.md"
create_card "Android Auto: Artwork on browse and playback items" "$TMP/2.md"
create_card "Android Auto: Play-from-search and MEDIA_PLAY_FROM_SEARCH" "$TMP/3.md"
create_card "Android Auto: Large browse lists and distraction limits" "$TMP/4.md"
create_card "Android Auto: Session activity and branding polish" "$TMP/5.md"
create_card "Android Auto: Caller validation (PackageValidator pattern)" "$TMP/6.md"

echo "Done. Open: https://github.com/users/${OWNER}/projects/${PROJ}"
