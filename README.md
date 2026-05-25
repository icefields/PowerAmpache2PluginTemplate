# Power Ampache 2 — Plugin Template & Android Auto

This repository is the **Power Ampache 2 plugin template** — a companion app that provides Android Auto browse and playback for a self-hosted [Ampache](https://ampache.org/) music server, powered by [Power Ampache 2](https://github.com/icefields/PowerAmpache2PluginTemplate) as the host app.

Built with **Clean Architecture** across four modules: `domain`, `data`, `app`, and `PowerAmpache2Theme`.

## How It Works

### Architecture Overview

The plugin is an **IPC client** of the main Power Ampache 2 app. It does not talk to the Ampache server directly — all data comes through the host app via a **Messenger-based IPC bridge**.

```
┌─────────────────────┐    Messenger IPC    ┌──────────────────────────┐
│   Power Ampache 2    │ ◄─────────────────► │  Plugin (this app)       │
│   (host app)         │   (bidirectional)   │                          │
│                     │                     │  PA2DataFetchService     │
│   - Ampache API     │ ─── sends data ───► │   └─ MusicFetcherImpl   │
│   - Stream URLs     │                     │       └─ StateFlows     │
│   - Playback queue  │                     │                          │
│                     │ ◄── requests data ── │  Pa2MediaLibraryService │
│                     │                     │   └─ ExoPlayer           │
│                     │                     │   └─ Media3 Session      │
└─────────────────────┘                     └──────────────────────────┘
                                                      │
                                              Android Auto (car head unit)
                                              browses via Media3 library
```

### Data Flow

1. **Plugin binds to host.** When `Pa2MediaLibraryService` starts, it starts and binds to `PA2DataFetchService` (in the `data` module). This service exposes a `Messenger` interface — the host app connects as a client and registers itself via `register_client`.

2. **Host pushes data.** The host app sends JSON-serialized playlists, albums, artists, and songs through the Messenger. `PA2DataFetchService` parses these with Gson and updates `MusicFetcherImpl`'s `StateFlow`s (`playlistsFlow`, `albumsFlow`, `albumSongsMapFlow`, etc.).

3. **Plugin requests data.** When Android Auto drills into a browse node (e.g. an album), `Pa2MediaLibraryService` calls domain use cases (`GetSongsFromAlbumUseCase`, etc.) which flow through `MusicFetcherImpl` → `MusicFetcherListener` → `PA2DataFetchService`, which sends a Messenger request to the host. The host responds asynchronously with the JSON data, which updates the relevant `StateFlow`.

4. **Auto browses the library.** `Pa2MediaLibraryService` implements Media3's `MediaLibraryService`. Android Auto calls `onGetLibraryRoot`, `onGetChildren`, and `onGetItem` to navigate a browse tree: root → sections (playlists, favourite/recent/latest/highest albums) → items → songs.

5. **Playback via ExoPlayer.** When a user taps a song on the car display, Android Auto sends a `MediaItem` with only a `mediaId` (no URI — the framework strips `localConfiguration` for privacy). The `onAddMediaItems`/`onSetMediaItems` callbacks resolve the ID back to a `Song` object, re-attach the stream URL from `song.songUrl`, and expand a single song into a full album/playlist queue so skip/next works. ExoPlayer then streams the Ampache URL directly with proper `AudioAttributes`, audio focus handling, and wake lock.

6. **Host queue mirroring.** When the host app plays audio on the phone, it pushes its queue via `MusicFetcher.currentQueueFlow`. The plugin mirrors this queue into ExoPlayer (paused) so Android Auto shows Now Playing metadata without requiring the head unit to have initiated playback.

### Libraries Used

- **Media3 / ExoPlayer** — Android Auto browse tree, playback session, and audio streaming
- **Dagger Hilt** — dependency injection
- **Gson** — JSON deserialization of host app data into domain models
- **Jetpack Compose + Material3** — phone UI

## Contributing

See `AGENTS.md` for rules on scope, branch policy, and git safety.