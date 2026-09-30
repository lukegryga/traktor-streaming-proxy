package sources

import com.google.protobuf.InvalidProtocolBufferException
import app.Audio
import app.Browser
import app.Library
import app.OAuthCallback
import app.Settings
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
private const val SESSION_RETRIES = 2

// Spotify's own desktop client id, hardcoded in librespot; its OAuth redirect is fixed to this port.
private const val KEYMASTER_CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"
private const val WEB_API = "https://api.spotify.com/v1"
private const val LIBRESPOT_REDIRECT_PORT = 5588
private const val LIBRESPOT_REDIRECT_URI = "http://127.0.0.1:$LIBRESPOT_REDIRECT_PORT/login"
private const val LOGIN5_ENDPOINT = "https://login5.spotify.com/v3/login"

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
        try {
            createSession()
        } catch (ex: InvalidProtocolBufferException) {
            // librespot parses login5's response without checking the status, so any error page
            // arrives as a protobuf parse failure with the cause thrown away. The endpoint is
            // asked again here purely to recover the status code and say which failure it was.
            throw IllegalStateException(login5Diagnosis(), ex)
        }
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

    override fun fetch(id: String): File {
        // librespot's session drops and reconnects roughly every two minutes, and takes ten
        // seconds to recover when the socket resets. A load landing in that window fails
        // outright, which mid-set means a deck that will not load.
        var attempt = 0
        while (true) {
            try {
                return store(id)
            } catch (ex: Exception) {
                if (attempt == SESSION_RETRIES) throw ex
                println("Track load failed (${ex.message}); retrying in case the session is reconnecting")
                Thread.sleep(4000L * (attempt + 1))
                attempt++
            }
        }
    }

    /** Streams the track, tags it with what Spotify knows about it, and keeps the result. */
    private fun store(id: String): File {
        val track = metadata(id)
        val title = track?.get("name")?.jsonPrimitive?.contentOrNull
        val artist = track?.get("artists")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull

        val target = Library.prepare(libraryKey(id), title, artist)
        val ogg = File.createTempFile("track-", ".ogg")
        val cover = track?.let { coverArt(it) }

        try {
            val streamed = System.currentTimeMillis()
            streamOgg(id, ogg)
            val transcoded = System.currentTimeMillis()
            // The source is 320kbps Vorbis, so the AAC stage is matched to it rather than left at
            // the encoder default of roughly 128k, which threw away most of what VERY_HIGH buys.
            Audio.write(ogg, cover, listOf("-c:a", "aac", "-b:a", "320k"), tags(track), target)
            val done = System.currentTimeMillis()

            Library.register(libraryKey(id), target.name)
            Library.enforceLimit()
            val (count, bytes) = Library.stats()
            println(
                "Stored ${target.name} (stream ${transcoded - streamed}ms, convert ${done - transcoded}ms); " +
                    "library holds $count tracks, ${bytes / 1024 / 1024} MB"
            )
            return target
        } finally {
            ogg.delete()
            cover?.delete()
        }
    }

    private fun streamOgg(id: String, target: File) {
        val uri = "spotify:track:$id"
        val stream = session!!.contentFeeder()
            .load(
                TrackId.fromUri(uri),
                VorbisOnlyAudioQuality(if (Settings.veryHighQuality) AudioQuality.VERY_HIGH else AudioQuality.HIGH),
                true,
                null
            )
        stream.`in`.stream().use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    private fun metadata(id: String): JsonObject? =
        runCatching { spotifyGet("$WEB_API/tracks/$id") }
            .onFailure { println("No metadata for $id: ${it.message}") }
            .getOrNull()

    private fun coverArt(track: JsonObject): File? {
        // images are ordered widest first, which is the one worth embedding
        val url = track["album"]?.jsonObject?.get("images")?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull ?: return null
        return Audio.cover(url)
    }

    private fun tags(track: JsonObject?): Map<String, String?> {
        if (track == null) return emptyMap()
        val album = track["album"]?.jsonObject
        val artists = track["artists"]?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString(", ")

        return buildMap {
            put("title", track["name"]?.jsonPrimitive?.contentOrNull)
            put("artist", artists)
            put("album", album?.get("name")?.jsonPrimitive?.contentOrNull)
            put("album_artist", album?.get("artists")?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull)
            put("date", album?.get("release_date")?.jsonPrimitive?.contentOrNull)
            put("track", track["track_number"]?.jsonPrimitive?.contentOrNull)
            put("disc", track["disc_number"]?.jsonPrimitive?.contentOrNull)
            put("isrc", track["external_ids"]?.jsonObject?.get("isrc")?.jsonPrimitive?.contentOrNull)
            put("comment", track["uri"]?.jsonPrimitive?.contentOrNull)
        }
    }

    /**
     * librespot invents a random device id whenever one is not configured, so every restart
     * looked to Spotify like a brand new device logging in. Kept in its own file rather than
     * beside the credentials, so deleting those to force a fresh login reuses the same device
     * instead of registering yet another one.
     */
    private fun deviceId(): String {
        val file = app.AppPaths.dataFile("data/device-id")
        val stored = file.takeIf { it.exists() }?.readText()?.trim()?.lowercase()
        if (stored != null && stored.matches(Regex("[0-9a-f]{40}"))) return stored

        val generated = ByteArray(20).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        file.parentFile?.mkdirs()
        file.writeText(generated)
        println("Spotify: registered this installation as device $generated")
        return generated
    }

    private fun createSession() {
        val credentialsFile = app.AppPaths.dataFile("data/credentials.json")
        credentialsFile.parentFile?.mkdirs()

        val conf = Session.Configuration.Builder()
            .setCacheEnabled(false)
            .setStoreCredentials(true)
            .setStoredCredentialsFile(credentialsFile)
            .build()

        fun builder() = Session.Builder(conf)
            .setDeviceId(deviceId())
            .setDeviceName("Traktor Streaming Proxy")

        if (credentialsFile.exists()) {
            session = builder().stored().create()
            return
        }

        // Session.Builder.oauth() only logs the URL, so the flow is driven here instead to get
        // the browser opened for the user. Credentials are stored by create() either way.
        OAuth(KEYMASTER_CLIENT_ID, LIBRESPOT_REDIRECT_URI).use { oauth ->
            Browser.open(oauth.authUrl)
            oauth.setCode(OAuthCallback.await(LIBRESPOT_REDIRECT_PORT, "/login", 10, TimeUnit.MINUTES))
            oauth.requestToken()
            session = builder().credentials(oauth.credentials).create()
        }
    }

    /**
     * Asks login5 what it is doing so the failure can be named. An empty body is enough: the
     * statuses worth distinguishing are decided at the edge, before the request is parsed, and a
     * healthy endpoint rejects it with a 4xx that is not 429.
     */
    private fun login5Diagnosis(): String {
        val status = try {
            val con = WebRequests.createConnection(LOGIN5_ENDPOINT, "POST")
            WebRequests.post(con, ByteArray(0)).responseCode
        } catch (ex: IOException) {
            return "Spotify's login endpoint could not be reached (${ex.message}). Check the " +
                "network connection, then try again."
        }

        return when {
            status == 429 -> "Spotify's login endpoint is rate limiting this device (HTTP 429). " +
                "It clears on its own; try again in a few minutes."
            status >= 500 -> "Spotify's login endpoint is down (HTTP $status), which is an outage " +
                "on their side, not a problem with this install or the saved login. It will work " +
                "again once they fix it; the saved credentials stay valid, so nothing needs " +
                "re-authorising."
            else -> "Spotify's login endpoint returned something unexpected (HTTP $status) that " +
                "librespot could not parse. Try again in a few minutes."
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

}
