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
package luci.sixsixsix.powerampache2.plugin.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import luci.sixsixsix.powerampache2.plugin.domain.model.Album
import luci.sixsixsix.powerampache2.plugin.domain.model.Artist
import luci.sixsixsix.powerampache2.plugin.domain.model.Playlist
import luci.sixsixsix.powerampache2.plugin.domain.model.Song

interface MusicFetcher {
    var musicFetcherListener: MusicFetcherListener?
    val currentQueueFlow: MutableStateFlow<List<Song>>
    val playlistsFlow: MutableStateFlow<List<Playlist>>
    val artistsFlow: MutableStateFlow<List<Artist>>
    val albumsFlow: MutableStateFlow<List<Album>>
    val favouriteAlbumsFlow: MutableStateFlow<List<Album>>
    val recentAlbumsFlow: MutableStateFlow<List<Album>>
    val latestAlbumsFlow: MutableStateFlow<List<Album>>
    val highRatedAlbumsFlow: MutableStateFlow<List<Album>>
    val albumSongsMapFlow: MutableStateFlow<Map<String, List<Song>>>
    val playlistSongsMapFlow: MutableStateFlow<Map<String, List<Song>>>

    fun getArtists(query: String = ""): Flow<List<Artist>>
    fun getAlbums(query: String = ""): Flow<List<Album>>
    fun getSongsFromAlbum(albumId: String): Flow<List<Song>>
    fun getSongsFromPlaylist(playlistId: String): Flow<List<Song>>
    fun getAlbumsFromArtist(artistId: String): Flow<List<Album>>

    val messengerFlow: MutableStateFlow<Boolean?>
}
