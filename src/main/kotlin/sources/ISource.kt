package sources

import app.Downloads
import app.Library
import beatport.api.Playlist
import beatport.api.Track
import java.io.File

interface ISource {
    /**
     * Displayed source name in Traktor
     */
    val name:String

    /**
     * Contents showing in Traktor when navigating to Genre-><source name>
     */
    fun getGenre(): List<Track>

    /**
     * Playlist names showing in Traktor when navigating to Curated Playlists-><source name>
     */
    fun getCuratedPlaylists(reset: Boolean): List<Playlist>

    /**
     * Contents showing in Traktor when navigating to Curated Playlists-><source name>-><playlist name>
     */
    fun getCuratedPlaylist(id: String): List<Track>

    /**
     * Playlist names showing in Traktor when navigating to Playlists
     */
    fun getPlaylists(): List<Playlist>

    /**
     * Contents showing in Traktor when navigating to Playlists-><playlist name>
     */
    fun getPlaylist(id: String): List<Track>

    /**
     * Contents showing in Traktor when navigating to Top 100-><source name>
     */
    fun getTop100(): List<Track>

    /**
     * Called when using search within Traktor
     */
    fun query(query: String, reset: Boolean): List<Track>

    /**
     * Fetches the track and puts it in the library, returning the file it landed in. The file must
     * be mp4, which is the one container Traktor's streaming path accepts.
     *
     * Only ever called on a library miss, and never twice at once for the same track, so an
     * implementation needs neither a cache check of its own nor any locking - see [download].
     */
    fun fetch(id: String): File

    /**
     * The library file to serve Traktor. A hit costs a stat, a miss goes to [fetch].
     *
     * Serialised per track, with the library checked again inside the lock: Traktor loads decks
     * independently, so a second deck asking for a track the first is still fetching waits here
     * and then reads the finished file rather than fetching it a second time into the same place.
     */
    fun download(id: String): File = Downloads.serialised(libraryKey(id)) {
        Library.cached(libraryKey(id))?.also { println("Serving $id from the library") } ?: fetch(id)
    }

    /**
     * Key this track is filed under in the shared library. Prefixed by source because ids are only
     * unique within one of them, and an unprefixed key would eventually serve one source's audio
     * for another source's track.
     */
    fun libraryKey(id: String): String = "${name.lowercase()}:$id"
}
