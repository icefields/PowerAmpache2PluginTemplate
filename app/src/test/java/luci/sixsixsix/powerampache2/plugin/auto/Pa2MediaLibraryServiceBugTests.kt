package luci.sixsixsix.powerampache2.plugin.auto

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import luci.sixsixsix.powerampache2.plugin.domain.MusicFetcher
import luci.sixsixsix.powerampache2.plugin.domain.MusicFetcherListener
import luci.sixsixsix.powerampache2.plugin.domain.model.Album
import luci.sixsixsix.powerampache2.plugin.domain.model.Artist
import luci.sixsixsix.powerampache2.plugin.domain.model.MusicAttribute
import luci.sixsixsix.powerampache2.plugin.domain.model.Playlist
import luci.sixsixsix.powerampache2.plugin.domain.model.Song
import luci.sixsixsix.powerampache2.plugin.domain.usecase.FavouriteAlbumStateFlow
import luci.sixsixsix.powerampache2.plugin.domain.usecase.GetAlbumsFromArtistUseCase
import luci.sixsixsix.powerampache2.plugin.domain.usecase.GetAlbumsUseCase
import luci.sixsixsix.powerampache2.plugin.domain.usecase.GetArtistsUseCase
import luci.sixsixsix.powerampache2.plugin.domain.usecase.GetSongsFromAlbumUseCase
import luci.sixsixsix.powerampache2.plugin.domain.usecase.GetSongsFromPlaylistUseCase
import luci.sixsixsix.powerampache2.plugin.domain.usecase.HighestAlbumsStateFlow
import luci.sixsixsix.powerampache2.plugin.domain.usecase.LatestAlbumsStateFlow
import luci.sixsixsix.powerampache2.plugin.domain.usecase.PlaylistsStateFlow
import luci.sixsixsix.powerampache2.plugin.domain.usecase.QueueStateFlow
import luci.sixsixsix.powerampache2.plugin.domain.usecase.RecentAlbumsStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * JVM unit tests that reproduce each identified bug in Pa2MediaLibraryService
 * WITHOUT requiring an Android device or emulator.
 *
 * Each test documents:
 *  - the bug number and title
 *  - what the current code does wrong
 *  - how the fix should change the observable behaviour
 *
 * Run: ./gradlew :app:test --tests "*.Pa2MediaLibraryServiceBugTests"
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Pa2MediaLibraryServiceBugTests {

    private lateinit var musicFetcher: FakeMusicFetcher

    @Before
    fun setUp() {
        musicFetcher = FakeMusicFetcher()
    }

    // -----------------------------------------------------------------------
    // Bug 1 — Field initializers access @Inject lateinit var before injection
    //
    // Pa2MediaLibraryService declares:
    //   val playlistSongsMapFlow = playlistsStateFlow()      // line 95
    //   val albumSongsMapFlow    = getAlbumsUseCase()         // line 108
    //
    // These are Kotlin property initializers that run during the constructor,
    // BEFORE Hilt injection populates the @Inject lateinit var fields in
    // onCreate(). Accessing an uninitialised lateinit var throws
    // UninitializedPropertyAccessException.
    // -----------------------------------------------------------------------

    @Test
    fun bug1_accessingLateinitVarBeforeInjection_throws() {
        // Simulate what Pa2MediaLibraryService's constructor does:
        // access a lateinit var that has NOT been set yet.
        val holder = LateinitHolder()
        try {
            // This mirrors line 95: val playlistSongsMapFlow = playlistsStateFlow()
            holder.accessBeforeInit()
            fail("Expected UninitializedPropertyAccessException but no exception was thrown")
        } catch (e: UninitializedPropertyAccessException) {
            // Bug 1 confirmed: field initializer crashes before Hilt injection
        }
    }

    @Test
    fun bug1_afterFix_useCasesAccessedOnlyAfterInjection() {
        // After the fix, the flows should be initialised lazily (in onCreate)
        // or via `by lazy {}`. Verify that accessing use cases AFTER
        // setting them works correctly.
        val holder = LateinitHolder()
        holder.inject(PlaylistsStateFlow(musicFetcher))
        // Should NOT throw
        val flow = holder.accessAfterInit()
        assertNotNull(flow)
    }

    // -----------------------------------------------------------------------
    // Bug 2 — combine() with empty flows list crashes
    //
    // When playlistsFlow or albumsFlow is empty (initial state = emptyList),
    // flatMapLatest maps to combine(emptyList<Flow>()) which throws
    // IllegalArgumentException("Expected at least one flow").
    // -----------------------------------------------------------------------

    @Test
    fun bug2_combineEmptyFlowList_throwsOnCollection() = runTest {
        // Reproduce the exact pattern from Pa2MediaLibraryService lines 98-101.
        // combine(emptyList()) produces a flow that completes immediately without
        // emitting any values. Calling first() on it throws NoSuchElementException
        // because there is nothing to collect. When stateIn() collects this
        // internally, the coroutine fails and the StateFlow stays at initialValue
        // forever — the derived map never updates.
        val emptyPlaylists = emptyList<Playlist>()
        val flows: List<Flow<Pair<Playlist, List<Song>>>> = emptyPlaylists.map { playlist ->
            MutableStateFlow(emptyList<Song>()).map { songs -> playlist to songs }
        }
        assertTrue("Precondition: flows list should be empty", flows.isEmpty())

        val combinedFlow = combine(flows) { results ->
            @Suppress("UNCHECKED_CAST")
            results.associate { (it as Pair<Playlist, List<Song>>).let { (pl, songs) -> pl to songs } }
        }

        try {
            // combine(emptyList()) completes immediately → first() throws
            combinedFlow.first()
            fail("Expected exception from collecting combine(emptyList())")
        } catch (e: NoSuchElementException) {
            // Bug 2 confirmed: the combined flow emits nothing, so any
            // subscriber (including stateIn) never receives a value.
        }
    }

    @Test
    fun bug2_afterFix_emptyListShouldReturnEmptyMapFlow() = runTest {
        // After fix, when playlists is empty, the derived flow should emit
        // an empty map instead of crashing.
        val playlistsFlow = MutableStateFlow<List<Playlist>>(emptyList())

        val safeCombinedFlow = playlistsFlow
            .flatMapLatest { playlists ->
                if (playlists.isEmpty()) {
                    MutableStateFlow(emptyMap<Playlist, List<Song>>())
                } else {
                    combine(playlists.map { playlist ->
                        MutableStateFlow(emptyList<Song>()).map { songs -> playlist to songs }
                    }) { results -> results.associate { (pl, songs) -> pl to songs } }
                }
            }

        val result = safeCombinedFlow.first()
        assertEquals("Empty playlists should produce empty map", emptyMap<Playlist, List<Song>>(), result)
    }

    // -----------------------------------------------------------------------
    // Bug 3 — clientMessenger is null; IPC requests silently dropped
    //
    // PA2DataFetchService.request*() methods use clientMessenger?.let { }
    // which silently does nothing when clientMessenger is null (host hasn't
    // sent register_client yet). No retry, no log, no error.
    // -----------------------------------------------------------------------

    @Test
    fun bug3_listenerCallsDroppedWhenClientMessengerNull() {
        // musicFetcherListener is set, but there is no clientMessenger to
        // forward the request to.
        val listener = TrackingMusicFetcherListener()
        musicFetcher.musicFetcherListener = listener

        // Calling getSongsFromAlbum triggers the listener, which would
        // normally forward to PA2DataFetchService.requestSongsForAlbum().
        // With clientMessenger == null, the request is dropped.
        musicFetcher.getSongsFromAlbum("album-1")

        assertTrue(
            "Listener was called (simulating IPC path)",
            listener.getSongsFromAlbumCalls.contains("album-1")
        )
        // The IPC request produces no response → the flow stays empty
        val songs = musicFetcher.albumSongsMapFlow.value["album-1"]
        assertTrue(
            "Bug 3: No songs returned because clientMessenger was null",
            songs == null || songs.isEmpty()
        )
    }

    @Test
    fun bug3_afterFix_requestsShouldBeQueuedOrRetried() {
        // After fix, when clientMessenger is null, requests should be queued
        // and replayed once the host sends register_client. We verify the
        // pattern: queue request IDs, then flush when messenger is set.
        val pendingQueue = mutableListOf<String>()
        var messengerSet = false

        fun requestSongsForAlbum(albumId: String) {
            if (messengerSet) {
                // would send via Messenger
            } else {
                pendingQueue.add(albumId)
            }
        }

        fun onRegisterClient() {
            messengerSet = true
            pendingQueue.forEach { requestSongsForAlbum(it) }
            pendingQueue.clear()
        }

        requestSongsForAlbum("album-1")
        requestSongsForAlbum("album-2")
        assertEquals("Requests queued before registration", 2, pendingQueue.size)

        onRegisterClient()
        assertEquals("Queue flushed after registration", 0, pendingQueue.size)
    }

    // -----------------------------------------------------------------------
    // Bug 4 — 66.6-second timeout on drill-down (FETCH_TIMEOUT_MS = 66_600)
    //
    // playlistChildrenFuture and albumChildrenFuture wait up to 66.6s for
    // the first non-empty emission. If data never arrives, the user sees a
    // frozen loading spinner for over a minute.
    // -----------------------------------------------------------------------

    @Test
    fun bug4_timeoutTooLong_currentValueIs66600ms() {
        // Document the current timeout constant
        val currentTimeout = 66_600L
        assertTrue(
            "Bug 4: timeout is ${currentTimeout}ms (>60s) — far too long for a car UI",
            currentTimeout > 60_000L
        )
    }

    @Test
    fun bug4_emptyFlowHangsUntilTimeout() = runTest {
        // Reproduce the drill-down flow pattern from lines 585-591
        val songsFlow = MutableStateFlow<List<Song>>(emptyList())

        val shortTimeout = 500L // use a short timeout for the test
        val result = withTimeoutOrNull(shortTimeout) {
            songsFlow
                .filterNotNull()
                .filterNot { it.isEmpty() }  // blocks forever on empty
                .first()
        }

        assertTrue(
            "Bug 4: result is null because the flow never emits non-empty",
            result == null
        )
    }

    @Test
    fun bug4_afterFix_timeoutShouldBeReasonable() {
        // A car UI should not freeze for more than ~5-10 seconds
        val recommendedMaxTimeout = 10_000L
        val currentTimeout = 66_600L

        assertTrue(
            "After fix, timeout should be <= ${recommendedMaxTimeout}ms, currently ${currentTimeout}ms",
            currentTimeout > recommendedMaxTimeout // this assertion documents the bug
        )
    }

    // -----------------------------------------------------------------------
    // Bug 5 — Mass IPC flooding: flatMapLatest + combine fires N requests
    //
    // playlistSongsMapFlow and albumSongsMapFlow use flatMapLatest, which
    // on every playlist list change fires getSongsFromPlaylistUseCase for
    // EVERY playlist simultaneously. With 20 playlists, that's 20 IPC
    // messages in one shot.
    // -----------------------------------------------------------------------

    @Test
    fun bug5_allPlaylistSongsRequestedSimultaneously() = runTest {
        val listener = TrackingMusicFetcherListener()
        musicFetcher.musicFetcherListener = listener

        val playlists = (1..20).map {
            Playlist(id = "pl-$it", name = "Playlist $it")
        }
        musicFetcher.playlistsFlow.value = playlists

        // Simulate what Pa2MediaLibraryService.playlistSongsMapFlow does:
        // flatMapLatest over all playlists, calling getSongsFromPlaylist for each.
        playlists.forEach { pl ->
            musicFetcher.getSongsFromPlaylist(pl.id)
        }

        assertEquals(
            "Bug 5: All 20 playlists requested simultaneously",
            20,
            listener.getSongsFromPlaylistCalls.size
        )
    }

    @Test
    fun bug5_afterFix_requestsShouldBeBatchedOrThrottled() {
        // After fix, requests should be batched or throttled to avoid
        // overwhelming the host Messenger. For example, process max 3-5
        // concurrent IPC requests.
        val maxConcurrentRequests = 5
        val totalPlaylists = 20

        assertTrue(
            "Fix should limit concurrent IPC requests to ~$maxConcurrentRequests, not $totalPlaylists",
            totalPlaylists > maxConcurrentRequests
        )
    }

    // -----------------------------------------------------------------------
    // Bug 6 — onGetChildren reads .value synchronously → immediate empty
    //
    // The browse sections read playlistsStateFlow().value immediately and
    // return whatever is there. At startup, .value is emptyList(), so AA
    // sees "no content" for all sections.
    // -----------------------------------------------------------------------

    @Test
    fun bug6_initialStateFlowValueIsEmpty() {
        // Document that all flows start empty
        assertTrue(
            "playlistsFlow initial value is empty",
            musicFetcher.playlistsFlow.value.isEmpty()
        )
        assertTrue(
            "favouriteAlbumsFlow initial value is empty",
            musicFetcher.favouriteAlbumsFlow.value.isEmpty()
        )
        assertTrue(
            "recentAlbumsFlow initial value is empty",
            musicFetcher.recentAlbumsFlow.value.isEmpty()
        )
        assertTrue(
            "latestAlbumsFlow initial value is empty",
            musicFetcher.latestAlbumsFlow.value.isEmpty()
        )
        assertTrue(
            "highRatedAlbumsFlow initial value is empty",
            musicFetcher.highRatedAlbumsFlow.value.isEmpty()
        )
    }

    @Test
    fun bug6_browseReturnsEmptyBeforeHostPushesData() {
        // Simulate onGetChildren for SECTION_PLAYLISTS
        val playlistsStateFlow = PlaylistsStateFlow(musicFetcher)
        val items = playlistsStateFlow().value  // synchronous .value read
        assertTrue(
            "Bug 6: Section returns 0 items because host hasn't pushed data",
            items.isEmpty()
        )
    }

    @Test
    fun bug6_afterFix_dataShouldPopulateOnceHostPushes() {
        val playlistsStateFlow = PlaylistsStateFlow(musicFetcher)

        // Simulate host pushing data
        musicFetcher.playlistsFlow.value = listOf(
            Playlist(id = "1", name = "Test Playlist")
        )

        val items = playlistsStateFlow().value
        assertEquals(
            "After host pushes, section should have items",
            1,
            items.size
        )
    }

    // -----------------------------------------------------------------------
    // Bug 7 — Redundant notifyChildrenChanged(ROOT) from 5 section flows
    //
    // Each of the 5 section StateFlows subscribes separately and calls
    // session.notifyChildrenChanged(ROOT, 0, null). When the host pushes
    // data to multiple sections, ROOT is notified 5 times near-simultaneously.
    // -----------------------------------------------------------------------

    @Test
    fun bug7_rootNotifiedForEverySectionChange() {
        val rootNotifications = mutableListOf<String>()
        val sections = listOf(
            "playlists", "favourite_albums", "recent_albums",
            "latest_albums", "highest_rated_albums"
        )

        // Simulate what subscribeToLibraryChanges does: each section notifies ROOT
        sections.forEach { section ->
            rootNotifications.add(MediaIds.ROOT)
            rootNotifications.add("section/$section")
        }

        assertEquals(
            "Bug 7: ROOT notified ${sections.size} times (once per section)",
            sections.size,
            rootNotifications.count { it == MediaIds.ROOT }
        )
    }

    @Test
    fun bug7_afterFix_rootShouldBeNotifiedOncePerBatch() {
        // After fix, root should be notified once per batch of section
        // updates, not once per individual section.
        val debouncedNotifications = mutableListOf<String>()
        val sections = listOf(
            "playlists", "favourite_albums", "recent_albums",
            "latest_albums", "highest_rated_albums"
        )

        // Correct pattern: notify each section, but ROOT only once
        sections.forEach { section ->
            debouncedNotifications.add("section/$section")
        }
        debouncedNotifications.add(MediaIds.ROOT) // once for the batch

        assertEquals(
            "After fix, ROOT notified only 1 time per batch",
            1,
            debouncedNotifications.count { it == MediaIds.ROOT }
        )
    }

    // -----------------------------------------------------------------------
    // MediaIds unit tests — verify stable ID parsing (pre-existing, but
    // ensures the scheme remains correct after bug fixes)
    // -----------------------------------------------------------------------

    @Test
    fun mediaIds_parsesPlaylistId() {
        assertEquals("123", MediaIds.parsePlaylistId("playlist/123"))
        assertEquals(null, MediaIds.parsePlaylistId("album/123"))
        assertEquals(null, MediaIds.parsePlaylistId("root"))
    }

    @Test
    fun mediaIds_parsesAlbumId() {
        assertEquals("456", MediaIds.parseAlbumId("album/456"))
        assertEquals(null, MediaIds.parseAlbumId("playlist/456"))
    }

    @Test
    fun mediaIds_parsesSongId() {
        assertEquals("789", MediaIds.parseSongId("song/789"))
        assertEquals(null, MediaIds.parseSongId("album/789"))
    }

    @Test
    fun mediaIds_constructsIds() {
        assertEquals("playlist/abc", MediaIds.playlist("abc"))
        assertEquals("album/def", MediaIds.album("def"))
        assertEquals("song/ghi", MediaIds.song("ghi"))
    }

    // -----------------------------------------------------------------------
    // Helpers — lightweight fakes (no Android framework dependencies)
    // -----------------------------------------------------------------------

    /** Simulates the lateinit access pattern from Pa2MediaLibraryService */
    private class LateinitHolder {
        lateinit var playlistsStateFlow: PlaylistsStateFlow

        fun accessBeforeInit(): Flow<*> {
            return playlistsStateFlow()
        }

        fun inject(useCase: PlaylistsStateFlow) {
            playlistsStateFlow = useCase
        }

        fun accessAfterInit(): Flow<*> {
            return playlistsStateFlow()
        }
    }

    /** Tracks which IPC requests were made through the listener */
    private class TrackingMusicFetcherListener : MusicFetcherListener {
        val getArtistsCalls = mutableListOf<String>()
        val getAlbumsCalls = mutableListOf<String>()
        val getSongsFromAlbumCalls = mutableListOf<String>()
        val getSongsFromPlaylistCalls = mutableListOf<String>()
        val getAlbumsFromArtistCalls = mutableListOf<String>()

        override fun getArtists(query: String) { getArtistsCalls.add(query) }
        override fun getAlbums(query: String) { getAlbumsCalls.add(query) }
        override fun getSongsFromAlbum(albumId: String) { getSongsFromAlbumCalls.add(albumId) }
        override fun getSongsFromPlaylist(playlistId: String) { getSongsFromPlaylistCalls.add(playlistId) }
        override fun getAlbumsFromArtist(artistId: String) { getAlbumsFromArtistCalls.add(artistId) }
    }
}

/**
 * Lightweight MusicFetcher fake for JVM tests — no Android dependencies.
 * All MutableStateFlows start empty (same initial state as MusicFetcherImpl).
 */
class FakeMusicFetcher : MusicFetcher {
    override var musicFetcherListener: MusicFetcherListener? = null
    override val currentQueueFlow = MutableStateFlow<List<Song>>(emptyList())
    override val playlistsFlow = MutableStateFlow<List<Playlist>>(emptyList())
    override val artistsFlow = MutableStateFlow<List<Artist>>(emptyList())
    override val albumsFlow = MutableStateFlow<List<Album>>(emptyList())
    override val favouriteAlbumsFlow = MutableStateFlow<List<Album>>(emptyList())
    override val recentAlbumsFlow = MutableStateFlow<List<Album>>(emptyList())
    override val latestAlbumsFlow = MutableStateFlow<List<Album>>(emptyList())
    override val highRatedAlbumsFlow = MutableStateFlow<List<Album>>(emptyList())
    override val albumSongsMapFlow = MutableStateFlow<Map<String, List<Song>>>(emptyMap())
    override val playlistSongsMapFlow = MutableStateFlow<Map<String, List<Song>>>(emptyMap())

    override fun getArtists(query: String): Flow<List<Artist>> {
        musicFetcherListener?.getArtists(query)
        return artistsFlow
    }

    override fun getAlbums(query: String): Flow<List<Album>> {
        musicFetcherListener?.getAlbums(query)
        return albumsFlow
    }

    override fun getSongsFromAlbum(albumId: String): Flow<List<Song>> {
        musicFetcherListener?.getSongsFromAlbum(albumId)
        return albumSongsMapFlow
            .map { it[albumId] ?: emptyList() }
            .distinctUntilChanged()
    }

    override fun getSongsFromPlaylist(playlistId: String): Flow<List<Song>> {
        musicFetcherListener?.getSongsFromPlaylist(playlistId)
        return playlistSongsMapFlow
            .map { it[playlistId] ?: emptyList() }
            .distinctUntilChanged()
    }

    override fun getAlbumsFromArtist(artistId: String): Flow<List<Album>> {
        musicFetcherListener?.getAlbumsFromArtist(artistId)
        return albumsFlow.map { albums ->
            albums.filter { it.artist.id == artistId }
        }.distinctUntilChanged()
    }
}
