package sources

import app.Browser
import app.OAuthCallback
import beatport.api.*
import io.github.tiefensuche.spotify.api.SpotifyApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.IOException
import xyz.gianlu.librespot.audio.decoders.AudioQuality
import xyz.gianlu.librespot.audio.decoders.VorbisOnlyAudioQuality
import xyz.gianlu.librespot.core.OAuth
import xyz.gianlu.librespot.core.Session
import xyz.gianlu.librespot.metadata.TrackId
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private const val RATE_LIMIT_RETRIES = 4

// Spotify's own desktop client id, hardcoded in librespot; its OAuth redirect is fixed to this port.
private const val KEYMASTER_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
private const val WEB_API = "https://api.spotify.com/v1"
private const val LIBRESPOT_REDIRECT_PORT = 5588
private const val LIBRESPOT_REDIRECT_URI = "http://127.0.0.1:$LIBRESPOT_REDIRECT_PORT/login"

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
    private var cachedUserId: String? = null
    private var searchQuery: String? = null
    private var searchNext: String? = null

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
        val playlistId = playlistIdAt(id) ?: return emptyList()
        return getAllTracks(playlistId) { i, refresh -> api.getArtist(i, refresh) }
    }

    /**
     * Fetched directly rather than through the api wrapper, which discards the owner. A
     * registered app can only read playlists the user owns or collaborates on; Spotify's own
     * editorial ones appear in the listing but 404 on their tracks, so they are dropped here
     * instead of showing up in Traktor as playlists that fail to open.
     */
    override fun getPlaylists(): List<Playlist> {
        val me = currentUserId()
        val entries = mutableListOf<Pair<String, String>>()
        var url: String? = "$WEB_API/me/playlists?limit=50"

        while (url != null) {
            val page = retryOnRateLimit { getJson(url!!) }
            page["items"]?.jsonArray?.forEach { item ->
                val playlist = item.jsonObject
                val owner = playlist["owner"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                val collaborative = playlist["collaborative"]?.jsonPrimitive?.booleanOrNull ?: false
                val id = playlist["id"]?.jsonPrimitive?.contentOrNull
                val title = playlist["name"]?.jsonPrimitive?.contentOrNull
                if (id != null && title != null && (owner == me || collaborative)) {
                    entries.add(id to title)
                }
            }
            url = page["next"]?.jsonPrimitive?.contentOrNull
        }

        return entries.map { (id, title) ->
            playlistIds.add(id)
            Playlist((playlistIds.size - 1).toLong(), title)
        }
    }

    private fun currentUserId(): String = cachedUserId
        ?: getJson("$WEB_API/me").getValue("id").jsonPrimitive.content.also { cachedUserId = it }

    private fun getJson(url: String): JsonObject {
        val connection = WebRequests.createConnection(url, "GET", mapOf("Authorization" to webAuth.token()))
        return Json.parseToJsonElement(WebRequests.request(connection).value).jsonObject
    }

    override fun getPlaylist(id: String): List<Track> {
        val playlistId = playlistIdAt(id) ?: return emptyList()
        return playlistTracks(playlistId)
    }

    // Traktor caches collection ids across restarts, by which time playlistIds is empty again.
    private fun playlistIdAt(id: String): String? =
        id.toIntOrNull()?.takeIf { it in playlistIds.indices }?.let { playlistIds[it] }

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

    /**
     * spotify-kt stamps every parsed track with its saved status via /me/tracks/contains, an extra
     * request per page that a Development Mode app is refused and whose result is unused here.
     */
    override fun query(query: String, reset: Boolean): List<Track> {
        val url = if (reset || query != searchQuery) {
            searchQuery = query
            // Since February 2026 the search endpoint rejects limit > 10 with "Invalid limit".
            "https://api.spotify.com/v1/search?type=track&limit=10&q=" + URLEncoder.encode(query, "utf-8")
        } else {
            searchNext ?: return emptyList()
        }
        val tracks = spotifyGet(url)["tracks"]?.jsonObject ?: return emptyList()
        searchNext = tracks["next"]?.jsonPrimitive?.contentOrNull
        return tracks["items"]?.jsonArray?.mapNotNull { trackFrom(it.jsonObject) } ?: emptyList()
    }

    override fun download(id: String): ByteArray {
        streamUri(id)
        return File("output.mp4").readBytes()
    }

    private fun createSession() {
        val credentialsFile = File("data/credentials.json")
        credentialsFile.parentFile?.mkdirs()

        val conf = Session.Configuration.Builder()
            .setCacheEnabled(false)
            .setStoreCredentials(true)
            .setStoredCredentialsFile(credentialsFile)
            .build()

        if (credentialsFile.exists()) {
            session = Session.Builder(conf).stored().create()
            return
        }

        // Session.Builder.oauth() only logs the URL, so the flow is driven here instead to get
        // the browser opened for the user. Credentials are stored by create() either way.
        OAuth(KEYMASTER_CLIENT_ID, LIBRESPOT_REDIRECT_URI).use { oauth ->
            Browser.open(oauth.authUrl)
            oauth.setCode(OAuthCallback.await(LIBRESPOT_REDIRECT_PORT, "/login", 10, TimeUnit.MINUTES))
            oauth.requestToken()
            session = Session.Builder(conf).credentials(oauth.credentials).create()
        }
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
                spotifyGet(url)
            } catch (ex: WebRequests.HttpException) {
                // A Development Mode app may only read playlists the user owns or collaborates on.
                if (ex.code != 403) throw ex
                println("Spotify: no access to playlist $playlistId, skipping it")
                return res
            }
            page["items"]?.jsonArray?.forEach { entry ->
                entry.jsonObject["item"]?.jsonObject?.let { trackFrom(it) }?.let { res.add(it) }
            }
            next = page["next"]?.jsonPrimitive?.contentOrNull
        }
        return res
    }

    private fun spotifyGet(url: String): JsonObject = retryOnRateLimit {
        val con = WebRequests.createConnection(url, "GET", mapOf("Authorization" to webAuth.token()))
        Json.parseToJsonElement(WebRequests.request(con).value).jsonObject
    }

    private fun trackFrom(track: JsonObject): Track? {
        if (track["is_playable"]?.jsonPrimitive?.booleanOrNull == false) return null
        val uri = track["uri"]?.jsonPrimitive?.contentOrNull ?: return null
        val artists = track["artists"]?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString() ?: ""
        return Track(
            uri.substring(uri.lastIndexOf(':') + 1),
            listOf(Artist(1, artists)),
            track["name"]?.jsonPrimitive?.contentOrNull ?: "",
            track["duration_ms"]?.jsonPrimitive?.long ?: 0
        )
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