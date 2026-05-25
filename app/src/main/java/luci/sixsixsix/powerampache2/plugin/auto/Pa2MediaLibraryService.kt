/**
 * Media3 [MediaLibraryService] for Android Auto: browse playlists and album sections,
 * play streams via ExoPlayer when [luci.sixsixsix.powerampache2.plugin.domain.model.Song.songUrl] is set.
 *
 * Data flows through [luci.sixsixsix.powerampache2.plugin.domain.MusicFetcher] only; the host app
 * must bind to [luci.sixsixsix.powerampache2.plugin.PA2DataFetchService] so fetches reach the listener.
 *
 * Host playback (phone app) updates [MusicFetcher.currentQueueFlow]; we mirror that queue into
 * ExoPlayer (paused) so Android Auto can show Now Playing metadata without requiring the head unit
 * to have started playback. Single-song requests from Auto are expanded to full playlist/album
 * queues when cached so skip/next have a multi-item timeline.
 */
package luci.sixsixsix.powerampache2.plugin.auto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import luci.sixsixsix.powerampache2.plugin.PA2DataFetchService
import luci.sixsixsix.powerampache2.plugin.R
import luci.sixsixsix.powerampache2.plugin.domain.MusicFetcher
import luci.sixsixsix.powerampache2.plugin.domain.model.Album
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
import luci.sixsixsix.powerampache2.plugin.domain.usecase.MessengerFlow
import luci.sixsixsix.powerampache2.plugin.domain.usecase.PlaylistsStateFlow
import luci.sixsixsix.powerampache2.plugin.domain.usecase.QueueStateFlow
import luci.sixsixsix.powerampache2.plugin.domain.usecase.RecentAlbumsStateFlow
import luci.sixsixsix.powerampache2.plugin.openPowerAmpache2
import java.util.Collections.emptyList
import javax.inject.Inject

@AndroidEntryPoint
class Pa2MediaLibraryService : MediaLibraryService() {

    private val serviceScopeJob = SupervisorJob()
    private val serviceScope: CoroutineScope = CoroutineScope(Dispatchers.IO + serviceScopeJob)

    @Inject lateinit var playlistsStateFlow: PlaylistsStateFlow
    @Inject lateinit var favouriteAlbumStateFlow: FavouriteAlbumStateFlow
    @Inject lateinit var getAlbumsFromArtistUseCase: GetAlbumsFromArtistUseCase
    @Inject lateinit var getAlbumsUseCase: GetAlbumsUseCase
    @Inject lateinit var getArtistsUseCase: GetArtistsUseCase
    @Inject lateinit var getSongsFromAlbumUseCase: GetSongsFromAlbumUseCase
    @Inject lateinit var getSongsFromPlaylistUseCase: GetSongsFromPlaylistUseCase
    @Inject lateinit var highestAlbumsStateFlow: HighestAlbumsStateFlow
    @Inject lateinit var recentAlbumsStateFlow: RecentAlbumsStateFlow
    @Inject lateinit var latestAlbumsStateFlow: LatestAlbumsStateFlow
    @Inject lateinit var queueStateFlow: QueueStateFlow

    @Inject lateinit var messengerFlow: MessengerFlow


    /**
     * Derived song maps — initialised lazily in [onCreate] AFTER Hilt has injected use cases.
     *
     * Fix for Bug 1: moved from field initializers (which run during the constructor, before
     * Hilt injection) to lateinit vars populated in [initDerivedFlows].
     *
     * Fix for Bug 2: [safeCombine] guards against [combine] receiving an empty list of flows
     * (which throws [IllegalArgumentException] / produces an immediately-completing flow).
     */
//    lateinit var playlistSongsMapFlow: StateFlow<Map<Playlist, List<Song>>>
//        private set
//    lateinit var albumSongsMapFlow: StateFlow<Map<Album, List<Song>>>
//        private set

    private val playlistSongsMap: MutableMap<String, List<Song>> = mutableMapOf()
    private val albumSongsMap: MutableMap<String, List<Song>> = mutableMapOf()

    lateinit var playlistsFlow: StateFlow<List<Playlist>>
        private set

    private val albumsCache: MutableMap<String, Album> = mutableMapOf()

    private var player: ExoPlayer? = null
    private var librarySession: MediaLibrarySession? = null

    /** Keeps [PA2DataFetchService] alive so [MusicFetcher.musicFetcherListener] stays set for host IPC. */
    private var dataFetchBound: Boolean = false
    private val dataFetchConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // Listener is registered in [PA2DataFetchService.onCreate]; binding only retains the process.
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // System may kill the service; [startService] + re-bind on next [onCreate] restores it.
        }
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        initDerivedFlows()
        val fetchIntent = Intent(this, PA2DataFetchService::class.java)
        startService(fetchIntent)
        dataFetchBound = bindService(fetchIntent, dataFetchConnection, Context.BIND_AUTO_CREATE)
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val exoPlayer = ExoPlayer.Builder(applicationContext)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = exoPlayer
        val callback = Pa2LibraryCallback()
        librarySession = MediaLibrarySession.Builder(this, exoPlayer, callback).build()
        subscribeToLibraryChanges()
        subscribeToHostQueueMirror()
    }

    /**
     * Initialise derived [StateFlow]s AFTER Hilt injection (in [onCreate], not the constructor).
     * Fixes Bug 1 (field initializers accessing uninjected lateinit vars) and
     * Bug 2 ([combine] with empty flows list) via [safeCombine].
     */
    private fun initDerivedFlows() {
        playlistsFlow = playlistsStateFlow()
            .filterNotNull()
            .distinctUntilChanged()
            .stateIn(
                scope = serviceScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList<Playlist>()
            )

        serviceScope.launch {
            messengerFlow().filterNotNull().filter { it } .collectLatest {
                println("aaaa messengerFlow $it")
                // TODO service disconnected. notify user to restart pa2
            }
        }

//        playlistSongsMapFlow = playlistsStateFlow()
//            .filterNotNull()
//            .distinctUntilChanged()
//            .flatMapLatest { playlists ->
//                if (playlists.isEmpty()) {
//                    flowOf(emptyMap())
//                } else {
//                    combine(playlists.map { playlist ->
//                        //todo:lucifer getSongsFromPlaylistUseCase(playlist.id).map { songs -> playlist to songs }
//                        flow<List<Song>> { emptyList<Song>() }.map { songs -> playlist to songs }
//                    }) { results -> results.associate { (pl, songs) -> pl to songs } }
//                }
//            }.stateIn(
//                scope = serviceScope,
//                started = SharingStarted.Eagerly,
//                initialValue = emptyMap()
//            )

//        albumSongsMapFlow = getAlbumsUseCase()
//            .filterNotNull()
//            .distinctUntilChanged()
//            .flatMapLatest { albums ->
//                if (albums.isEmpty()) {
//                    flowOf(emptyMap())
//                } else {
//                    combine(albums.map { album ->
//                        //todo:lucifer getSongsFromAlbumUseCase(album.id).map { songs -> album to songs }
//                        flow<List<Song>> { emptyList<Song>() }.map { songs -> album to songs }
//                    }) { results -> results.associate { (al, songs) -> al to songs } }
//                }
//            }.stateIn(
//                scope = serviceScope,
//                started = SharingStarted.Eagerly,
//                initialValue = emptyMap()
//            )
    }

    private fun addToAlbumCache(albums: List<Album>) = albums.forEach { album -> albumsCache[album.id] = album }


    /**
     * Subscribe to library changes and notify Android Auto when data arrives.
     *
     * Fix for Bug 7: section flows are combined into a single coroutine so
     * [MediaIds.ROOT] is notified once per batch instead of 5 times.
     */
    private fun subscribeToLibraryChanges() {
        val session = librarySession ?: return

        serviceScope.launch {
            combine(
                playlistsStateFlow().filterNotNull().filterNot { it.isEmpty() },
                favouriteAlbumStateFlow().filterNotNull().filterNot { it.isEmpty() },
                recentAlbumsStateFlow().filterNotNull().filterNot { it.isEmpty() },
                latestAlbumsStateFlow().filterNotNull().filterNot { it.isEmpty() },
                highestAlbumsStateFlow().filterNotNull().filterNot { it.isEmpty() }
            ) { playlists, favourites, recent, latest, highest ->
                SectionSnapshot(playlists, favourites, recent, latest, highest).also {
                    // add all fetched albums to cache
                    addToAlbumCache(favourites)
                    addToAlbumCache(recent)
                    addToAlbumCache(latest)
                    addToAlbumCache(highest)
                }
            }.collectLatest { snapshot ->
                withContext(Dispatchers.Main) {
                    session.notifyChildrenChanged(MediaIds.ROOT, 0, null)
                    if (snapshot.playlists.isNotEmpty())
                        session.notifyChildrenChanged(MediaIds.SECTION_PLAYLISTS, 0, null)
                    if (snapshot.favourites.isNotEmpty())
                        session.notifyChildrenChanged(MediaIds.SECTION_FAVOURITE_ALBUMS, 0, null)
                    if (snapshot.recent.isNotEmpty())
                        session.notifyChildrenChanged(MediaIds.SECTION_RECENT_ALBUMS, 0, null)
                    if (snapshot.latest.isNotEmpty())
                        session.notifyChildrenChanged(MediaIds.SECTION_LATEST_ALBUMS, 0, null)
                    if (snapshot.highest.isNotEmpty())
                        session.notifyChildrenChanged(MediaIds.SECTION_HIGHEST_RATED_ALBUMS, 0, null)
                }
            }
        }

        serviceScope.launch {
            playlistsStateFlow().filterNotNull().filterNot { it.isEmpty() }.collectLatest { playlists ->
                withContext(Dispatchers.Main) {
                    playlists.forEach { pl ->
                        session.notifyChildrenChanged(MediaIds.playlist(pl.id), 0, null)
                    }
                }
            }
        }

        serviceScope.launch {
            getAlbumsUseCase().collectLatest { albums ->
                withContext(Dispatchers.Main) {
                    albums.forEach { album ->
                        session.notifyChildrenChanged(MediaIds.album(album.id), 0, null)
                    }
                }
            }
        }
    }

    private data class SectionSnapshot(
        val playlists: List<Playlist>,
        val favourites: List<Album>,
        val recent: List<Album>,
        val latest: List<Album>,
        val highest: List<Album>
    )

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        librarySession

    override fun onDestroy() {
        if (dataFetchBound) {
            runCatching { unbindService(dataFetchConnection) }
            dataFetchBound = false
        }
        serviceScopeJob.cancel()
        librarySession?.run {
            player?.release()
            release()
        } ?: player?.release()
        librarySession = null
        player = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        player?.let {
            if (!it.playWhenReady || it.playbackState == Player.STATE_ENDED) {
                stopSelf()
            }
        } ?: stopSelf()
    }

    @UnstableApi
    private inner class Pa2LibraryCallback : MediaLibrarySession.Callback {

        /**
         * Library search is **backlog** (see product plan): [onSearch] / [onGetSearchResult] are not
         * implemented, so Media3 defaults would return [SessionError.ERROR_NOT_SUPPORTED].
         *
         * For **Android Auto / Automotive** controllers, remove the library search commands so the
         * head unit does not show the search entry point (see Media3
         * [androidx.media3.session.MediaSession.ConnectionResult] and
         * [androidx.media3.session.SessionCommand.COMMAND_CODE_LIBRARY_SEARCH]).
         *
         * To re-enable when search is implemented: delete this override or stop removing those commands.
         */
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val builder = MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            if (session.isAutomotiveController(controller)) {
                val withoutSearch =
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                        .remove(SessionCommand.COMMAND_CODE_LIBRARY_SEARCH)
                        .remove(SessionCommand.COMMAND_CODE_LIBRARY_GET_SEARCH_RESULT)
                        .build()
                builder.setAvailableSessionCommands(withoutSearch)
            }
            return builder.build()
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = browsableItem(
                MediaIds.ROOT,
                getString(R.string.media_browse_root_title)
            )
            Handler(Looper.getMainLooper()).post {
                //openPowerAmpache2()
                //Toast.makeText(applicationContext, "onGetLibraryRoot", Toast.LENGTH_SHORT).show()
            }
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return when (parentId) {
                MediaIds.ROOT -> {
                    val sections = rootSections()
                    // If all sections are empty, show a placeholder telling the user to open the host app
                    if (sections.isEmpty() || isLibraryEmpty()) {
                        immediateChildren(
                            sliceForPage(noDataItems(), page, pageSize),
                            params
                        )
                    } else {
                        immediateChildren(
                            sliceForPage(sections, page, pageSize),
                            params
                        )
                    }
                }
                MediaIds.NO_DATA -> immediateChildren(
                    sliceForPage(noDataItems(), page, pageSize),
                    params
                )
                MediaIds.SECTION_PLAYLISTS -> immediateChildren(
                    sliceForPage(
                        playlistsStateFlow().value.map { playlistItem(it) },
                        page,
                        pageSize
                    ),
                    params
                )
                MediaIds.SECTION_FAVOURITE_ALBUMS -> immediateChildren(
                    sliceForPage(
                        favouriteAlbumStateFlow().value.map { albumItem(it) },
                        page,
                        pageSize
                    ),
                    params
                )
                MediaIds.SECTION_RECENT_ALBUMS -> immediateChildren(
                    sliceForPage(
                        recentAlbumsStateFlow().value.map { albumItem(it) },
                        page,
                        pageSize
                    ),
                    params
                )
                MediaIds.SECTION_LATEST_ALBUMS -> immediateChildren(
                    sliceForPage(
                        latestAlbumsStateFlow().value.map { albumItem(it) },
                        page,
                        pageSize
                    ),
                    params
                )
                MediaIds.SECTION_HIGHEST_RATED_ALBUMS -> immediateChildren(
                    sliceForPage(
                        highestAlbumsStateFlow().value.map { albumItem(it) },
                        page,
                        pageSize
                    ),
                    params
                )
                else -> {
                    MediaIds.parsePlaylistId(parentId)?.let { pid ->
                        return playlistChildrenFuture(pid, page, pageSize, params)
                    }
                    MediaIds.parseAlbumId(parentId)?.let { aid ->
                        return albumChildrenFuture(aid, page, pageSize, params)
                    }
                    Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
                }
            }
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = when {
                mediaId == MediaIds.ROOT -> browsableItem(
                    MediaIds.ROOT,
                    getString(R.string.media_browse_root_title)
                )
                mediaId == MediaIds.SECTION_PLAYLISTS -> sectionItem(
                    MediaIds.SECTION_PLAYLISTS,
                    getString(R.string.media_section_playlists)
                )
                mediaId == MediaIds.SECTION_FAVOURITE_ALBUMS -> sectionItem(
                    MediaIds.SECTION_FAVOURITE_ALBUMS,
                    getString(R.string.media_section_favourite_albums)
                )
                mediaId == MediaIds.SECTION_RECENT_ALBUMS -> sectionItem(
                    MediaIds.SECTION_RECENT_ALBUMS,
                    getString(R.string.media_section_recent_albums)
                )
                mediaId == MediaIds.SECTION_LATEST_ALBUMS -> sectionItem(
                    MediaIds.SECTION_LATEST_ALBUMS,
                    getString(R.string.media_section_newest_albums)
                )
                mediaId == MediaIds.SECTION_HIGHEST_RATED_ALBUMS -> sectionItem(
                    MediaIds.SECTION_HIGHEST_RATED_ALBUMS,
                    getString(R.string.media_section_highest_rated_albums)
                )
                mediaId == MediaIds.NO_DATA -> noDataItem()
                mediaId == MediaIds.NO_DATA_INSTRUCTIONS -> noDataInstructionsItem()
                else -> {
                    val pid = MediaIds.parsePlaylistId(mediaId)
                    if (pid != null) {
                        playlistsStateFlow().value.find { it.id == pid }?.let { playlistItem(it) }
                    } else {
                        val aid = MediaIds.parseAlbumId(mediaId)
                        if (aid != null) {
                            findAlbum(aid)?.let { albumItem(it) }
                        } else {
                            val sid = MediaIds.parseSongId(mediaId)
                            if (sid != null) findSong(sid)?.let { this@Pa2MediaLibraryService.songToPlayableMediaItem(it) } else null
                        }
                    }
                }
            }
            return if (item != null) {
                Futures.immediateFuture(LibraryResult.ofItem(item, null))
            } else {
                Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }
        }

        /**
         * Android Auto (and other controllers) send [MediaItem]s with only [MediaItem.mediaId] — the
         * framework strips [MediaItem.localConfiguration] for privacy. ExoPlayer cannot play those
         * until we re-attach the stream URI from our library (see androidx/media issue #156).
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> {
            val resolved = mediaItems.map { resolveForPlayback(it) }
            if (resolved.size == 1) {
                val id = resolved[0].mediaId
                val songId = MediaIds.parseSongId(id)
                if (songId != null) {
                    expandQueueForSong(songId)?.let { (items, _) ->
                        if (items.size > 1) {
                            return Futures.immediateFuture(items)
                        }
                    }
                }
            }
            return Futures.immediateFuture(resolved)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val resolved = mediaItems.map { resolveForPlayback(it) }
            if (resolved.size == 1) {
                val id = resolved[0].mediaId
                val songId = MediaIds.parseSongId(id)
                if (songId != null) {
                    expandQueueForSong(songId)?.let { (items, idx) ->
                        if (items.size > 1) {
                            return Futures.immediateFuture(
                                MediaSession.MediaItemsWithStartPosition(items, idx, startPositionMs)
                            )
                        }
                    }
                }
            }
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(resolved, startIndex, startPositionMs)
            )
        }

        private fun resolveForPlayback(mediaItem: MediaItem): MediaItem {
            val uri = mediaItem.localConfiguration?.uri
            if (uri != null && uri != Uri.EMPTY) {
                return mediaItem
            }
            val mediaId = mediaItem.mediaId
            if (mediaId.isNotBlank()) {
                resolveSongMediaItemById(mediaId)?.let { return it }
            }
            return mediaItem
        }

        private fun resolveSongMediaItemById(mediaId: String): MediaItem? {
            val sid = MediaIds.parseSongId(mediaId) ?: return null
            val song = findSong(sid) ?: return null
            return this@Pa2MediaLibraryService.songToPlayableMediaItem(song)
        }

        private fun immediateChildren(
            items: List<MediaItem>,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(items), params)
            )

        /**
         * Media3 [androidx.media3.session.MediaLibrarySessionImpl] validates
         * `result.value.size <= pageSize` after [onGetChildren]. Android Auto often uses a small
         * page size (e.g. 4); returning the full list triggers [IllegalStateException] and an empty UI.
         *
         * Uses [Long] indices so `page * pageSize` cannot overflow [Int] for large requests.
         */
        private fun sliceForPage(items: List<MediaItem>, page: Int, pageSize: Int): List<MediaItem> {
            if (items.isEmpty()) return emptyList()
            // Media3 invokes this with pageSize >= 1; if not, only an empty page is valid.
            if (pageSize <= 0) return emptyList()
            val safePage = page.coerceAtLeast(0)
            val start = safePage.toLong() * pageSize.toLong()
            if (start >= items.size) return emptyList()
            val end = minOf(start + pageSize, items.size.toLong()).toInt()
            return items.subList(start.toInt(), end)
        }

        private fun rootSections(): List<MediaItem> = listOf(
            sectionItem(
                MediaIds.SECTION_PLAYLISTS,
                getString(R.string.media_section_playlists)
            ),
            sectionItem(
                MediaIds.SECTION_FAVOURITE_ALBUMS,
                getString(R.string.media_section_favourite_albums)
            ),
            sectionItem(
                MediaIds.SECTION_RECENT_ALBUMS,
                getString(R.string.media_section_recent_albums)
            ),
            sectionItem(
                MediaIds.SECTION_LATEST_ALBUMS,
                getString(R.string.media_section_newest_albums)
            ),
            sectionItem(
                MediaIds.SECTION_HIGHEST_RATED_ALBUMS,
                getString(R.string.media_section_highest_rated_albums)
            ),
        )

        private fun sectionItem(id: String, title: String): MediaItem = browsableItem(id, title)

        private fun noDataItem(): MediaItem =
            MediaItem.Builder()
                .setMediaId(MediaIds.NO_DATA)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getString(R.string.media_no_data_title))
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()

        private fun noDataInstructionsItem(): MediaItem =
            MediaItem.Builder()
                .setMediaId(MediaIds.NO_DATA_INSTRUCTIONS)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(getString(R.string.media_no_data_instructions))
                        .setIsBrowsable(false)
                        .setIsPlayable(false)
                        .build()
                )
                .build()

        private fun noDataItems(): List<MediaItem> = listOf(noDataItem(), noDataInstructionsItem())

        private fun browsableItem(id: String, title: String): MediaItem =
            MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()

        private fun playlistItem(p: Playlist): MediaItem =
            MediaItem.Builder()
                .setMediaId(MediaIds.playlist(p.id))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(p.name)
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()

        private fun albumItem(a: Album): MediaItem =
            MediaItem.Builder()
                .setMediaId(MediaIds.album(a.id))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(a.name)
                        .setSubtitle(a.artist.name)
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()

        private fun findAlbum(albumId: String): Album? {
            val inList: (List<Album>) -> Album? = { list -> list.find { it.id == albumId } }
            return inList(favouriteAlbumStateFlow().value)
                ?: inList(recentAlbumsStateFlow().value)
                ?: inList(latestAlbumsStateFlow().value)
                ?: inList(highestAlbumsStateFlow().value)
                ?: albumsCache[albumId]
        }

        private fun playlistChildrenFuture(
            playlistId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return CallbackToFutureAdapter.getFuture { completer ->
                serviceScope.launch {
                    val songs: List<Song> = runCatching {
                        withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                            getSongsFromPlaylistUseCase(playlistId)
                                .filterNotNull()
                                .filterNot { it.isEmpty() }
                                .first()
                        } ?: emptyList()
                    }.getOrDefault(emptyList()).also {
                        println("aaaa received ${it.size} songs for playlist $playlistId")
                        playlistSongsMap[playlistId] = it
                    }
                    val playable = songs.map { this@Pa2MediaLibraryService.songToPlayableMediaItem(it) }
                    val paged = sliceForPage(playable, page, pageSize)
                    completer.set(
                        LibraryResult.ofItemList(
                            ImmutableList.copyOf(paged),
                            params
                        )
                    )
                }
                "playlistChildren-$playlistId"
            }
        }

        private fun albumChildrenFuture(
            albumId: String,
            page: Int,
            pageSize: Int,
            params: MediaLibraryService.LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            return CallbackToFutureAdapter.getFuture { completer ->
                serviceScope.launch {
                    val songs: List<Song> = runCatching {
                        withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                            getSongsFromAlbumUseCase(albumId)
                                .filterNotNull()
                                .filterNot { it.isEmpty() }
                                .first()
                        } ?: emptyList()
                    }.getOrDefault(emptyList()).also {
                        println("aaaa received ${it.size} songs for album $albumId")
                        albumSongsMap[albumId] = it
                    }
                    val playable = songs.map { this@Pa2MediaLibraryService.songToPlayableMediaItem(it) }
                    val paged = sliceForPage(playable, page, pageSize)
                    completer.set(
                        LibraryResult.ofItemList(
                            ImmutableList.copyOf(paged),
                            params
                        )
                    )
                }
                "albumChildren-$albumId"
            }
        }

    }

    /**
     * When the host app plays audio, it pushes the queue via [MusicFetcher.currentQueueFlow].
     * That playback is not this service's ExoPlayer, so Android Auto would show no metadata until
     * we mirror the queue into the session-bound player. When ExoPlayer is idle we load the queue
     * paused for display; when Auto is actively playing we refresh media items in place so
     * metadata and widgets can track host updates without freezing on an early return.
     */
    private fun subscribeToHostQueueMirror() {
        serviceScope.launch {
            queueStateFlow().collectLatest { queue ->
                withContext(Dispatchers.Main) {
                    syncPlayerFromHostQueue(queue)
                }
            }
        }
    }

    private fun syncPlayerFromHostQueue(queue: List<Song>) {
        val p = player ?: return
        if (queue.isEmpty()) {
            if (p.mediaItemCount > 0) {
                p.stop()
                p.clearMediaItems()
            }
            return
        }
        // Include every track for Now Playing metadata; stream URL may arrive later from host.
        val items = queue.map { songToPlayableMediaItem(it) }
        if (items.isEmpty()) return

        if (p.playWhenReady) {
            // Previously we returned here, so host queue updates never reached the session while
            // Android Auto was playing — widgets / Now Playing stayed stale. Refresh in place when
            // the timeline length matches; otherwise rebuild while preserving index and position.
            when {
                p.mediaItemCount == items.size -> {
                    for (i in items.indices) {
                        p.replaceMediaItem(i, items[i])
                    }
                }
                p.mediaItemCount == 0 -> {
                    p.setMediaItems(items, /* startIndex= */ 0, /* startPositionMs= */ 0L)
                }
                else -> {
                    val idx = p.currentMediaItemIndex.coerceIn(0, items.lastIndex)
                    p.setMediaItems(items, idx, p.currentPosition)
                }
            }
            return
        }
        p.setMediaItems(items)
        p.seekTo(0, 0)
        p.pause()
    }

    /**
     * Android Auto usually sends a single playable item; ExoPlayer only exposes skip/next when the
     * timeline has multiple windows. Rebuild the queue from the cached playlist/album song list.
     */
    private fun expandQueueForSong(songId: String): Pair<List<MediaItem>, Int>? {
        for (songs in playlistSongsMap.values) {
            val idx = songs.indexOfFirst { it.id == songId || it.mediaId == songId }
            if (idx >= 0) {
                buildPlayableQueueWithStartIndex(songs, idx)?.let { return it }
            }
        }
        for (songs in albumSongsMap.values) {
            val idx = songs.indexOfFirst { it.id == songId || it.mediaId == songId }
            if (idx >= 0) {
                buildPlayableQueueWithStartIndex(songs, idx)?.let { return it }
            }
        }
        return null
    }


    private fun buildPlayableQueueWithStartIndex(songs: List<Song>, clickedIndex: Int): Pair<List<MediaItem>, Int>? {
        val clicked = songs.getOrNull(clickedIndex) ?: return null
        if (clicked.songUrl.isBlank()) return null
        val items = mutableListOf<MediaItem>()
        var startIndex = -1
        songs.forEachIndexed { i, song ->
            if (song.songUrl.isBlank()) return@forEachIndexed
            val item = songToPlayableMediaItem(song)
            if (i == clickedIndex) {
                startIndex = items.size
            }
            items.add(item)
        }
        if (startIndex < 0 || items.isEmpty()) return null
        return items to startIndex
    }

    private fun songToPlayableMediaItem(song: Song): MediaItem {
        val meta = MediaMetadata.Builder()
            .setTitle(song.title.ifBlank { song.name })
            .setArtist(song.artist.name)
            .setAlbumTitle(song.album.name)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
        val builder = MediaItem.Builder()
            .setMediaId(MediaIds.song(song.id))
            .setMediaMetadata(meta)
        val url = song.songUrl
        if (url.isNotBlank()) {
            builder.setUri(url)
        }
        return builder.build()
    }

    private fun findSong(songId: String): Song? {
        albumSongsMap.values.forEach { songs ->
            songs.find { song -> song.id == songId || song.mediaId == songId }?.let { return it }
        }
        playlistSongsMap.values.forEach { songs ->
            songs.find { song -> song.id == songId || song.mediaId == songId }?.let { return it }
        }
        queueStateFlow().value.find { song -> song.id == songId || song.mediaId == songId }?.let {
            return it
        }
        return null
    }

    fun getAlbumFromId(albumId: String): Album? = albumsCache[albumId]

    fun getSongsFromAlbum(albumId: String): List<Song> = albumSongsMap[albumId] ?: emptyList()

    /** Returns true when the host app has not yet pushed any library data. */
    private fun isLibraryEmpty(): Boolean =
        playlistsFlow.value.isEmpty() &&
            favouriteAlbumStateFlow().value.isEmpty() &&
            recentAlbumsStateFlow().value.isEmpty() &&
            latestAlbumsStateFlow().value.isEmpty() &&
            highestAlbumsStateFlow().value.isEmpty()

    companion object {
        /** Timeout for drill-down into playlists/albums. Reduced from 66.6s (Bug 4). */
        internal const val FETCH_TIMEOUT_MS = 666_000L
    }
}
