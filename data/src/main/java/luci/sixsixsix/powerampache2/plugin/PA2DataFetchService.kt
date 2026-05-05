/**
 * Copyright (C) 2025  Antonio Tari
 *
 * This file is a part of Power Ampache 2
 * Ampache Android client application
 * @author Antonio Tari
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package luci.sixsixsix.powerampache2.plugin

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.google.gson.Gson
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import luci.sixsixsix.powerampache2.plugin.data.dto.AlbumsDto
import luci.sixsixsix.powerampache2.plugin.data.dto.ArtistsDto
import luci.sixsixsix.powerampache2.plugin.data.dto.PlaylistsDto
import luci.sixsixsix.powerampache2.plugin.data.dto.SongsDto
import luci.sixsixsix.powerampache2.plugin.domain.MusicFetcher
import luci.sixsixsix.powerampache2.plugin.domain.MusicFetcherListener
import luci.sixsixsix.powerampache2.plugin.domain.common.ACTION_ALBUMS
import luci.sixsixsix.powerampache2.plugin.domain.common.ACTION_ARTISTS
import luci.sixsixsix.powerampache2.plugin.domain.common.ACTION_GET_ALBUMS
import luci.sixsixsix.powerampache2.plugin.domain.common.ACTION_GET_ALBUMS_ARTIST
import luci.sixsixsix.powerampache2.plugin.domain.common.ACTION_GET_ARTISTS
import luci.sixsixsix.powerampache2.plugin.domain.common.ACTION_GET_SONGS_ALBUM
import luci.sixsixsix.powerampache2.plugin.domain.common.ACTION_GET_SONGS_PLAYLIST
import luci.sixsixsix.powerampache2.plugin.domain.common.ACTION_PLAYLISTS
import luci.sixsixsix.powerampache2.plugin.domain.common.ACTION_SONGS_ALBUM
import luci.sixsixsix.powerampache2.plugin.domain.common.ACTION_SONGS_PLAYLIST
import luci.sixsixsix.powerampache2.plugin.domain.common.KEY_ACTION
import luci.sixsixsix.powerampache2.plugin.domain.common.KEY_ALBUM_ID
import luci.sixsixsix.powerampache2.plugin.domain.common.KEY_ID
import luci.sixsixsix.powerampache2.plugin.domain.common.KEY_PLAYLIST_ID
import luci.sixsixsix.powerampache2.plugin.domain.common.KEY_QUERY
import luci.sixsixsix.powerampache2.plugin.domain.common.KEY_REQUEST_JSON
import luci.sixsixsix.powerampache2.plugin.domain.common.KEY_RESPONSE_SUCCESS
import luci.sixsixsix.powerampache2.plugin.domain.model.Album
import luci.sixsixsix.powerampache2.plugin.domain.model.Song
import javax.inject.Inject
import kotlin.io.println

@AndroidEntryPoint
class PA2DataFetchService : Service(), MusicFetcherListener {
    @Inject lateinit var musicFetcher: MusicFetcher
    @Inject lateinit var applicationCoroutineScope: CoroutineScope
    private val gson = Gson()
    private var clientMessenger: Messenger? = null // client's messenger for bidirectional communication

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val action = msg.data.getString(KEY_ACTION) ?: return

            when (action) {
                "register_client" -> {
                    // ONLY store clientMessenger here
                    clientMessenger = msg.replyTo
                }
                else -> {
                    parseJsonString(
                        action,
                        jsonStr = msg.data.getString(KEY_REQUEST_JSON) ?: return,
                        entityId = resolveEntityId(msg)
                    )
                    val replyTo = msg.replyTo ?: return
                    replyTo.send(Message.obtain().apply { data = Bundle().apply { putBoolean(KEY_RESPONSE_SUCCESS, true) } })
                }
            }
        }
    })

    override fun onCreate() {
        super.onCreate()
        musicFetcher.musicFetcherListener = this
    }

    override fun onDestroy() {
        musicFetcher.musicFetcherListener = null
        super.onDestroy()
    }

    fun requestArtists(query: String = "") {
        clientMessenger?.let { messenger ->
            val msg = Message.obtain().apply {
                data = Bundle().apply {
                    putString(KEY_ACTION, ACTION_GET_ARTISTS)
                    putString(KEY_QUERY, query)
                }
                println("aaaa sending req ACTION_GET_ARTISTS query: ${query}")
            }
            try {
                messenger.send(msg)
            } catch (e: RemoteException) {
                clientMessenger = null
            }
        }
    }

    fun requestAlbums(query: String = "") {
        clientMessenger?.let { messenger ->
            val msg = Message.obtain().apply {
                data = Bundle().apply {
                    putString(KEY_ACTION, ACTION_GET_ALBUMS)
                    putString(KEY_QUERY, query)
                }
                println("aaaa sending req ACTION_GET_ALBUMS query: ${query}")
            }
            try {
                messenger.send(msg)
            } catch (e: RemoteException) {
                clientMessenger = null
            }
        }
    }

    /**
     * Request songs for a specific album from the client
     */
    fun requestSongsForAlbum(albumId: String) {
        clientMessenger?.let { messenger ->
            val msg = Message.obtain().apply {
                data = Bundle().apply {
                    putString(KEY_ACTION, ACTION_GET_SONGS_ALBUM)
                    putString(KEY_ALBUM_ID, albumId)
                }
                println("aaaa Fetcher.requestSongsForAlbum id: ${albumId}")
            }
            try {
                messenger.send(msg)
            } catch (e: RemoteException) {
                println("aaaa requestSongsForAlbum clientMessenger NULL RemoteException ${e.stackTraceToString()}")
                clientMessenger = null
                musicFetcher.messengerFlow.value = true
            }
        } ?: println("aaaa requestSongsForAlbum clientMessenger NULL")
    }

    /**
     * Request songs for a specific album from the client
     */
    fun requestAlbumsFromArtist(artistId: String) {
        // TODO: NOT IMPLEMENTED
        clientMessenger?.let { messenger ->
            val msg = Message.obtain().apply {
                data = Bundle().apply {
                    putString(KEY_ACTION, ACTION_GET_ALBUMS_ARTIST)
                    putString(KEY_ID, artistId)
                }
                println("aaaa Fetcher.requestAlbumsFromArtist id: ${artistId}")
            }
            try {
                messenger.send(msg)
            } catch (e: RemoteException) {
                clientMessenger = null
            }
        }
    }

    fun requestSongsForPlaylist(playlistId: String) {
        clientMessenger?.let { messenger ->
            val msg = Message.obtain().apply {
                data = Bundle().apply {
                    putString(KEY_ACTION, ACTION_GET_SONGS_PLAYLIST)
                    putString(KEY_PLAYLIST_ID, playlistId)
                }
                //println("aaaa requestSongsForPlaylist id: ${playlistId}")
            }
            try {
                messenger.send(msg)
            } catch (e: RemoteException) {
                println("aaaa requestSongsForPlaylist clientMessenger NULL RemoteException ${e.stackTraceToString()}")
                clientMessenger = null
                musicFetcher.messengerFlow.value = true
            }
        } ?: println("aaaa requestSongsForPlaylist clientMessenger NULL")
    }

    /** Album / playlist id from the host bundle ([KEY_ID], [KEY_ALBUM_ID], [KEY_PLAYLIST_ID] are all `"id"` today). */
    private fun resolveEntityId(msg: Message): String? =
        msg.data.getString(KEY_ID)
            ?: msg.data.getString(KEY_ALBUM_ID)
            ?: msg.data.getString(KEY_PLAYLIST_ID)

    private fun parseJsonString(action: String, jsonStr: String, entityId: String? = null) {
        //println("aaaa parseJsonString $action")
        when(action) {
            ACTION_PLAYLISTS -> gson.fromJson(jsonStr, PlaylistsDto::class.java).playlists.also { playlists ->
                println("aaaa ACTION_PLAYLISTS ${playlists.size}")
                musicFetcher.playlistsFlow.value = playlists
            }
            ACTION_ARTISTS -> gson.fromJson(jsonStr, ArtistsDto::class.java).artists.also { artists ->
                musicFetcher.artistsFlow.update { oldArtists ->
                    val combined = (oldArtists + artists).distinct()
                    println("aaaa parseJsonString ACTION_ARTISTS $entityId size ${combined.size}")
                    combined
                }
            }
            ACTION_ALBUMS -> gson.fromJson(jsonStr, AlbumsDto::class.java).albums.also { albums ->
                musicFetcher.albumsFlow.update { oldAlbums ->
                    val combined = (oldAlbums + albums).distinct()
                    println("aaaa parseJsonString ACTION_ALBUMS $entityId size ${combined.size}")
                    combined
                }
            }
            ACTION_SONGS_ALBUM -> {
                val id = entityId ?: return
                gson.fromJson(jsonStr, SongsDto::class.java).songs.also { songs ->
                    musicFetcher.albumSongsMapFlow.update { map ->
                        (map + (id to songs))
                    }
                }
            }
            ACTION_SONGS_PLAYLIST -> {
                val id = entityId ?: return
                gson.fromJson(jsonStr, SongsDto::class.java).songs.also { songs ->
                    musicFetcher.playlistSongsMapFlow.update { map ->
                        val newList: LinkedHashSet<Song> = LinkedHashSet(map[id] ?: emptyList())
                        newList.addAll(songs)
                        (map + (id to newList.toList()))
                    }
                }
            }
            "highest_albums" -> gson.fromJson(jsonStr, AlbumsDto::class.java).albums.also { albums ->
                addToAlbumsList(albums)
                musicFetcher.highRatedAlbumsFlow.update { oldAlbums ->
                    val combined = (oldAlbums + albums).distinct()
                    println("aaaa parseJsonString highest_albums $entityId size ${combined.size}")
                    combined
                }
            }
            "favourite_albums" -> gson.fromJson(jsonStr, AlbumsDto::class.java).albums.also { albums ->
                addToAlbumsList(albums)
                musicFetcher.favouriteAlbumsFlow.update { oldAlbums ->
                    val combined = (oldAlbums + albums).distinct()
                    println("aaaa favouriteAlbumsFlow $action $entityId size ${combined.size}")
                    combined
                }
            }
            "recent_albums" -> gson.fromJson(jsonStr, AlbumsDto::class.java).albums.also { albums ->
                addToAlbumsList(albums)
                musicFetcher.recentAlbumsFlow.update { oldAlbums ->
                    val combined = (oldAlbums + albums).distinct()
                    println("aaaa recentAlbumsFlow $action $entityId size ${combined.size}")
                    combined
                }
            }
            "latest_albums" -> gson.fromJson(jsonStr, AlbumsDto::class.java).albums.also { albums ->
                addToAlbumsList(albums)
                musicFetcher.latestAlbumsFlow.update { oldAlbums ->
                    val combined = (oldAlbums + albums).distinct()
                    println("aaaa latestAlbumsFlow $action $entityId size ${combined.size}")
                    combined
                }
            }
            "queue" -> gson.fromJson(jsonStr, SongsDto::class.java).songs.also {
                musicFetcher.currentQueueFlow.value = it
            }
        }
    }

    private fun addToAlbumsList(albums: List<Album>) {
        musicFetcher.albumsFlow.update { oldAlbums ->
            val combined = (oldAlbums + albums).distinct()
            println("aaaa addToAlbumsList size ${combined.size}")
            combined
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return messenger.binder
    }

    private fun sendResponse(replyTo: Messenger?, data: Map<String, Any>) {
        replyTo?.let { messenger ->
            val msg = Message.obtain().apply {
                this.data = Bundle().apply {
                    data.forEach { (key, value) ->
                        when (value) {
                            is Boolean -> putBoolean(key, value)
                            is String -> putString(key, value)
                            is Int -> putInt(key, value)
                        }
                    }
                }
            }
            try {
                messenger.send(msg)
            } catch (e: RemoteException) {
                // Handle disconnected client
            }
        }
    }

    // MUSIC LISTENER METHODS
    override fun getAlbums(query: String) = requestAlbums(query)
    override fun getArtists(query: String) = requestArtists(query)
    override fun getSongsFromAlbum(albumId: String) = requestSongsForAlbum(albumId)
    override fun getSongsFromPlaylist(playlistId: String) = requestSongsForPlaylist(playlistId)
    override fun getAlbumsFromArtist(artistId: String) = requestAlbumsFromArtist(artistId)
}
