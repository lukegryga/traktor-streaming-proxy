package sources

import beatport.api.*
import io.github.tiefensuche.spotify.api.SpotifyApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.IOException
import xyz.gianlu.librespot.audio.decoders.AudioQuality
import xyz.gianlu.librespot.audio.decoders.VorbisOnlyAudioQuality
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.metadata.TrackId
import java.io.File

private const val RATE_LIMIT_RETRIES = 4

class Spotify : ISource {

    private val webAuth = SpotifyWebAuth()
    private val _api = SpotifyApi()
    private val api: SpotifyApi
        get() {
            _api.token = webAuth.token()
            return _api
        }

    private var session: Session? = null
    private val playlistIds = mutableListOf<String>()

    override val name: String
        get() = "Spotify"

    init {
        createSession()
        // Done at startup so both browser logins happen together rather than on a later request.
        webAuth.token()
    }

    override fun getGenre(): List<Track> {
        val res = retryOnRateLimit { api.getUsersSavedTracks(true) }.toMutableList()
        do {
            val next = retryOnRateLimit { api.getUsersSavedTracks(false) }
            res.addAll(next)
        } while (next.isNotEmpty())
        return mapTracks(res)
    }

    override fun getCuratedPlaylists(reset: Boolean): List<Playlist> {
        return mapPlaylists(retryOnRateLimit { api.getArtists(reset) })
    }

    override fun getCuratedPlaylist(id: String): List<Track> {
        return getAllTracks(playlistIds[id.toInt()]) { i, refresh -> api.getArtist(i, refresh) }
    }

    override fun getPlaylists(): List<Playlist> {
        return mapPlaylists(retryOnRateLimit { api.getUsersPlaylists(true) })
    }

    override fun getPlaylist(id: String): List<Track> {
        return playlistTracks(playlistIds[id.toInt()])
    }

    override fun getTop100(): List<Track> {
        for (category in retryOnRateLimit { api.getBrowseCategories(true) }) {
            if (category.name == "New Releases") {
                for (playlist in retryOnRateLimit { api.getCategoryPlaylists(category.id, true) }) {
                    if (playlist.title == "Release Radar") {
                        return playlistTracks(playlist.id)
                    }
                }
            }
        }
        return emptyList()
    }

    override fun query(query: String, reset: Boolean): List<Track> {
        return mapTracks(retryOnRateLimit { api.query(query, reset) })
    }

    override fun download(id: String): ByteArray {
        streamUri(id)
        return File("output.mp4").readBytes()
    }

    private fun createSession() {
        // Kept outside the working directory root so it can be bind-mounted in Docker
        // without shadowing the application files.
        val credentialsFile = File("data/credentials.json")
        credentialsFile.parentFile?.mkdirs()

        val conf = Session.Configuration.Builder()
            .setCacheEnabled(false)
            .setStoreCredentials(true)
            .setStoredCredentialsFile(credentialsFile)
            .build()

        session = Session.Builder(conf).oauth().create()
    }

    private fun getAllTracks(id: String, func: (id: String, refresh: Boolean) -> List<io.github.tiefensuche.spotify.api.Track>): List<Track> {
        val res = retryOnRateLimit { func(id, true) }.toMutableList()
        do {
            val next = retryOnRateLimit { func(id, false) }
            res.addAll(next)
        } while (next.isNotEmpty())
        return mapTracks(res)
    }

    /**
     * Spotify's March 2026 migration removed /playlists/{id}/tracks in favour of
     * /playlists/{id}/items, renaming the wrapper key from "track" to "item". spotify-kt still
     * targets the old endpoint and gets a 403, so this one collection is fetched directly.
     */
    private fun playlistTracks(playlistId: String): List<Track> {
        val res = mutableListOf<Track>()
        var next: String? = "https://api.spotify.com/v1/playlists/$playlistId/items?limit=50"
        while (true) {
            val url = next ?: break
            val page = try {
                retryOnRateLimit {
                    val con = WebRequests.createConnection(url, "GET", mapOf("Authorization" to webAuth.token()))
                    Json.parseToJsonElement(WebRequests.request(con).value).jsonObject
                }
            } catch (ex: WebRequests.HttpException) {
                // A Development Mode app may only read playlists the user owns or collaborates on.
                if (ex.code != 403) throw ex
                println("Spotify: no access to playlist $playlistId, skipping it")
                return res
            }
            page["items"]?.jsonArray?.forEach { entry ->
                val track = entry.jsonObject["item"]?.jsonObject ?: return@forEach
                if (track["is_playable"]?.jsonPrimitive?.booleanOrNull != false) {
                    val uri = track["uri"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val artists = track["artists"]?.jsonArray
                        ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                        ?.joinToString() ?: ""
                    res.add(
                        Track(
                            uri.substring(uri.lastIndexOf(':') + 1),
                            listOf(Artist(1, artists)),
                            track["name"]?.jsonPrimitive?.contentOrNull ?: "",
                            track["duration_ms"]?.jsonPrimitive?.long ?: 0
                        )
                    )
                }
            }
            next = page["next"]?.jsonPrimitive?.contentOrNull
        }
        return res
    }

    /**
     * Draining a whole library pages in a tight loop, which trips Spotify's rolling
     * request window; a 429 there is transient and clears within tens of seconds.
     */
    private fun <T> retryOnRateLimit(block: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (ex: IOException) {
                val code = when (ex) {
                    is SpotifyApi.HttpException -> ex.code
                    is WebRequests.HttpException -> ex.code
                    else -> throw ex
                }
                if (code != 429 || attempt == RATE_LIMIT_RETRIES) throw ex
                Thread.sleep(1000L shl attempt)
                attempt++
            }
        }
    }

    private fun mapTracks(tracks: List<io.github.tiefensuche.spotify.api.Track>): List<Track> {
        return tracks.filter { it.playable }.map { track -> Track(track.id.substring(track.id.lastIndexOf(':') + 1), listOf(Artist(1, track.artist)), track.title, track.duration) }
    }

    private fun mapPlaylists(playlists: List<io.github.tiefensuche.spotify.api.Playlist>): List<Playlist> {
        return playlists.map { artist ->
            playlistIds.add(artist.id)
            Playlist((playlistIds.size - 1).toLong(), artist.title)
        }
    }

    private fun streamUri(id: String) {
        val uri = "spotify:track:$id"
        val stream = session!!.contentFeeder().load(TrackId.fromUri(uri), VorbisOnlyAudioQuality(AudioQuality.HIGH), true, null)
        val proc = Runtime.getRuntime().exec(arrayOf("ffmpeg", "-y", "-f", "ogg", "-i", "pipe:", "output.mp4"))
        var cur: Int
        while (stream.`in`.stream().read().also { cur = it } != -1) {
            proc.outputStream.write(cur)
        }
        stream.`in`.stream().close()
        proc.outputStream.flush()
        proc.outputStream.close()
        proc.waitFor()
    }
}