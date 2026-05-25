/**
 * Stable media ID scheme for Android Auto / Media3 browse trees.
 *
 * Format:
 * - `root` — library root (browsable)
 * - `section/playlists` | `section/favourite_albums` | `section/recent_albums` |
 *   `section/latest_albums` | `section/highest_rated_albums` — section nodes (browsable)
 * - `playlist/{id}` — playlist container (browsable)
 * - `album/{id}` — album container (browsable)
 * - `song/{id}` — playable leaf (stream URL on [luci.sixsixsix.powerampache2.plugin.domain.model.Song.songUrl])
 */
package luci.sixsixsix.powerampache2.plugin.auto

internal object MediaIds {
    const val ROOT = "root"

    const val SECTION_PLAYLISTS = "section/playlists"
    const val SECTION_FAVOURITE_ALBUMS = "section/favourite_albums"
    const val SECTION_RECENT_ALBUMS = "section/recent_albums"
    const val SECTION_LATEST_ALBUMS = "section/latest_albums"
    const val SECTION_HIGHEST_RATED_ALBUMS = "section/highest_rated_albums"

    const val NO_DATA = "no_data"

    private const val PREFIX_PLAYLIST = "playlist/"
    private const val PREFIX_ALBUM = "album/"
    private const val PREFIX_SONG = "song/"

    fun playlist(playlistId: String): String = "$PREFIX_PLAYLIST$playlistId"
    fun album(albumId: String): String = "$PREFIX_ALBUM$albumId"
    fun song(songId: String): String = "$PREFIX_SONG$songId"

    fun parsePlaylistId(mediaId: String): String? =
        if (mediaId.startsWith(PREFIX_PLAYLIST)) mediaId.removePrefix(PREFIX_PLAYLIST) else null

    fun parseAlbumId(mediaId: String): String? =
        if (mediaId.startsWith(PREFIX_ALBUM)) mediaId.removePrefix(PREFIX_ALBUM) else null

    fun parseSongId(mediaId: String): String? =
        if (mediaId.startsWith(PREFIX_SONG)) mediaId.removePrefix(PREFIX_SONG) else null
}
