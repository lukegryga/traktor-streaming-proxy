import Config.prop
import beatport.api.*
import io.ktor.http.*
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.engine.*
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.partialcontent.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.apache.log4j.BasicConfigurator
import app.AppPaths
import app.ControlPanel
import app.Library
import app.Logging
import app.SingleInstance
import app.SourceManager
import app.IndexedTrack
import app.TrackIndex
import app.TrayUi
import sources.ISource
import sources.Spotify
import sources.Youtube
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.*
import kotlin.math.min


// Spotify first: it is the order the panel offers sources that are not enabled yet, and the one
// this build exists for. Registration order comes from the saved list, not from here.
val allSources = mapOf(
    "spotify" to Spotify::class.java,
    "youtube" to Youtube::class.java
)

object Config {
    val prop = Properties()

    fun readConfig() {
        val file = AppPaths.data("config.properties")
        if (!file.exists())
            return
        FileInputStream(file).use { prop.load(it) }
    }

    fun saveConfig() {
        val file = AppPaths.data("config.properties")
        FileOutputStream(file).use {
            prop.store(it, "")
        }
    }
}

val sources: MutableList<ISource> get() = SourceManager.sources

fun processTracks(id: Int, tracks: List<Track>): List<TrackResponse> {
    return tracks.map { track ->
        val traktorId = Utils.encode(track.id.substring(0, min(track.id.length, 10)))
        TrackIndex.put(
            traktorId,
            IndexedTrack(
                source = id,
                remainder = if (track.id.length > 10) track.id.substring(10) else "",
                name = track.name,
                artists = track.artists.map { it.name },
                lengthMs = track.length_ms
            )
        )
        TrackResponse(traktorId, track.artists, track.name, track.length_ms)
    }
}

/**
 * Executes a search across enabled sources.
 *
 * The sources are asked at once rather than in turn: each one is a blocking round trip to its own
 * API, so in sequence the wait was the sum of them all instead of the slowest. [awaitAll] keeps
 * the results in source order, which is the order the panel lets you arrange.
 *
 * @param q The raw query string from the request.
 * @param hasMoreParameter A flag indicating if the 'more' parameter is present.
 * @return A list of processed track responses.
 */
private suspend fun executeSearch(q: String, hasMoreParameter: Boolean): List<TrackResponse> {
    // Only a prefix that names a real source scopes the search. Splitting on any colon turned
    // "Jeux Interdits: Spanish Romance" into a search of every source for " Spanish Romance".
    val prefix = q.substringBefore(':', "").trim().lowercase()
    val scoped = allSources[prefix]
    val query = if (scoped != null) q.substringAfter(':').trim() else q

    return coroutineScope {
        sources.mapIndexed { id, source ->
            async(Dispatchers.IO) {
                if (scoped != null && source::class.java != scoped) {
                    emptyList()
                } else {
                    // Caught per source rather than allowed out: in parallel one provider throwing
                    // would cancel the siblings, so a single broken source would empty the whole
                    // search instead of costing only its own results.
                    runCatching {
                        // Traktor shows one merged list, so without this there is no way to tell
                        // which source a result came from.
                        processTracks(id, source.query(query, !hasMoreParameter))
                            .map { it.copy(name = "[${source.name}] ${it.name}") }
                    }.onFailure {
                        System.err.println("Search on ${source.name} failed: ${it.message}")
                    }.getOrDefault(emptyList())
                }
            }
        }.awaitAll()
    }.flatten()
}

fun main(args: Array<String>) {
    // Handled before anything else initialises: this runs as a short lived elevated process whose
    // only job is the write that Program Files refuses unelevated.
    if (args.size == 2 && args[0] == "--patch") {
        app.TraktorPatch.patch(File(args[1]))
            .onSuccess { println(it) }
            .onFailure { System.err.println(it.message) }
        return
    }

    BasicConfigurator.configure()
    Logging.configure()

    Config.readConfig()
    TrackIndex.load()
    Library.load()
    Runtime.getRuntime().addShutdownHook(object : Thread() {
        override fun run() {
            Config.saveConfig()
        }
    })

    // Before anything that touches the certificate stores. A second copy started by hand while one
    // is already serving used to provision certificates first and only then notice it was not
    // wanted, and clearing superseded certificates removes the one the running server is in the
    // middle of serving - which Traktor reports as a failed login until that server is restarted.
    if (!SingleInstance.acquire()) {
        println("Another instance is already running; exiting.")
        return
    }

    // Checked before the sources start, otherwise an interactive source login such as
    // Spotify's completes only to have the server die on the missing keystore afterwards.
    val serverPort = prop.getProperty("server.port", "443").toInt()
    val keystoreFile = AppPaths.data("cert/keystore.jks")

    // Provisioned here rather than by a setup script: without a trusted certificate for
    // api.beatport.com, Traktor refuses the connection and nothing else the server does matters.
    app.Hosts.ensure()
    app.Certificates.ensure()
        .onSuccess { println(it) }
        .onFailure { System.err.println("Certificate setup failed: ${it.message}") }
    if (!keystoreFile.exists()) {
        System.err.println("No usable certificate at ${keystoreFile.absolutePath}; cannot serve HTTPS.")
        return
    }

    // Read through Settings rather than the property directly, so the default for a fresh install
    // is decided in one place and startup cannot disagree with what the panel shows.
    app.Settings.enabledSources
        .filter { allSources.containsKey(it) }
        .forEach { SourceManager.register(it, allSources.getValue(it)) }

    Logging.setLevel(app.Settings.logLevel)
    ControlPanel.start()

    if (!TrayUi.install()) {
        println("System tray unavailable; running headless.")
    }
    // Deliberately after the tray is up and not awaited: a source may block on an interactive
    // login, and the server has to be listening for Traktor to link at all.
    SourceManager.startAll()

    val alias = "foo"
    val keystorePassword = "changeit"
    val keyStore = KeyStore.getInstance("JKS").apply {
        keystoreFile.inputStream().use { load(it, keystorePassword.toCharArray()) }
    }
    val serverConfiguration: NettyApplicationEngine.Configuration.() -> Unit = {
        sslConnector(
            keyStore,
            alias,
            { keystorePassword.toCharArray() },
            { keystorePassword.toCharArray() }
        ) {
            port = serverPort
        }
    }

    embeddedServer(Netty, applicationEnvironment(), serverConfiguration, module = {
        install(CallLogging) {
            // Default format logs the path only, which hides the query Traktor actually sends.
            // Duration matters because Traktor gives up on a slow download and reports a network error.
            format { call ->
                "${call.response.status()}: ${call.request.httpMethod.value} - ${call.request.uri}" +
                    " in ${call.processingTimeMillis()}ms"
            }
        }
        // Traktor asks for byte ranges of the track rather than the whole file. Without this
        // every range request was answered with the entire body and a 200, so a load could not be
        // resumed and nothing could be fetched in pieces.
        install(PartialContent)
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        // One shared buffer served whatever had finished downloading last, so loading a second
        // deck while the first was still fetching handed Traktor the wrong track, or an empty
        // body on the very first load. The url names the track instead, and the last few loads are
        // kept so every deck can still read its own.
        //
        // The library file rather than its bytes: the track is on disk either way, so holding the
        // bytes meant a copy of every recent load on the heap and a full read of a cached file
        // just to write it straight back out.
        val loaded = Collections.synchronizedMap(object : LinkedHashMap<Long, File>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, File>) = size > 4
        })
        routing {

            get("/v4/auth/o/authorize/") {
                call.respondRedirect("traktor://bp_oauth?code=foo")
            }

            post("/v4/auth/o/token/") {
                call.respond(Auth("foo", 36000, "Bearer", "app:locker user:dj", "bar"))
            }

            get("/v4/auth/logout/") {
                call.respond(HttpStatusCode.OK)
            }

            get("/v4/my/account/") {
                // Through Settings so the compiled-in default applies. Traktor calls this straight
                // after the token exchange and treats any failure here as a rejected login.
                val accountId = app.Settings.beatportAccountId.toIntOrNull()
                if (accountId == null) {
                    System.err.println("beatport.accountId is not a number; Traktor will refuse to log in")
                    return@get call.respond(HttpStatusCode.InternalServerError, "Invalid beatport.accountId")
                }
                call.respond(Account(accountId))
            }

            get("/v4/my/license/") {
                // This build only targets Windows, so the license is not a choice worth offering.
                val licenseFile = Config::class.java.getResource("licenses/windows.json")

                if (licenseFile == null) {
                    call.respond(HttpStatusCode.InternalServerError, "License file 'windows' not found")
                } else {
                    call.respondBytes(licenseFile.readBytes())
                }
            }

            get("/v4/catalog/search") {
                val q = call.parameters["q"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val results = executeSearch(q, call.parameters.contains("more"))
                val nextUrl = if (results.isNotEmpty()) "api.beatport.com/v4/catalog/search?q=$q&more" else ""
                call.respond(QueryTrackResponse(results, nextUrl))
            }

            get("/search/v1/tracks") {
                val q = call.parameters["q"] ?: return@get call.respond(HttpStatusCode.BadRequest)
                val results = executeSearch(q, call.parameters.contains("more"))
                call.respond(BeatportSearchResponse(results.toNewSearchApi()))
            }

            get("/v4/catalog/genres") {
                call.respondRedirect("/v4/catalog/genres/")
            }

            get("/v4/catalog/genres/") {
                call.respond(Genres(sources.mapIndexed { id, source -> Genre(id + 1, source.name) }))
            }

            get("/v4/catalog/genres/{id}/tracks/") {
                call.parameters["id"]?.let {
                    val sourceId = it.toInt() - 1
                    val tracks = withContext(Dispatchers.IO) { sources[sourceId].getGenre() }
                    call.respond(GenreTrackResponse(processTracks(sourceId, tracks), "" /* unused by Traktor */))
                }
            }

            get("/v4/curation/playlists/") {
                call.parameters["genre_id"]?.let { genreId ->
                    val reset = !call.parameters.contains("more")
                    val playlists = withContext(Dispatchers.IO) {
                        sources[genreId.toInt() - 1].getCuratedPlaylists(reset)
                    }
                    val results = playlists.map { Playlist((genreId + it.id).toLong(), it.name) }
                    call.respond(CuratedPlaylistsResponse(results, if (results.isNotEmpty()) "api.beatport.com/v4/curation/playlists/?genre_id=$genreId&more" else ""))
                }
            }

            get("/v4/curation/playlists/{id}/tracks/") {
                call.parameters["id"]?.let {
                    val sourceId = it.substring(0, 1).toInt() - 1
                    val tracks = withContext(Dispatchers.IO) { sources[sourceId].getCuratedPlaylist(it.substring(1)) }
                    val results = processTracks(sourceId, tracks)
                    call.respond(CuratedPlaylistResponse(results.map { track -> PlaylistItem(track) }, "" /* unused by Traktor */))
                }
            }

            get("/v4/my/playlists/") {
                val results = withContext(Dispatchers.IO) {
                    sources.mapIndexed { id, source -> source.getPlaylists().map { playlist -> Playlist("${id + 1}${playlist.id}".toLong(), playlist.name) } }.flatten()
                }
                call.respond(CuratedPlaylistsResponse(results, "" /* not needed */))
            }

            get("/v4/my/playlists/{id}/tracks/") {
                call.parameters["id"]?.let {
                    val sourceId = it.substring(0, 1).toInt() - 1
                    val tracks = withContext(Dispatchers.IO) { sources[sourceId].getPlaylist(it.substring(1)) }
                    val results = processTracks(sourceId, tracks)
                    call.respond(CuratedPlaylistResponse(results.map { track -> PlaylistItem(track) }, "" /* unused by Traktor */))
                }
            }

            get("/v4/catalog/genres/{id}/top/100/") {
                call.parameters["id"]?.let {
                    val sourceId = it.toInt() - 1
                    val tracks = withContext(Dispatchers.IO) { sources[sourceId].getTop100() }
                    call.respond(GenreTrackResponse(processTracks(sourceId, tracks), "" /* unused by Traktor */))
                }
            }

            // Traktor asks for single track metadata before loading a deck. Unimplemented it
            // returned 404 and Traktor retried in a tight loop, dozens of times per load.
            get("/v4/catalog/tracks/") {
                val traktorId = call.parameters["id"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val indexed = TrackIndex.get(traktorId)
                    ?: return@get call.respond(HttpStatusCode.NotFound)
                call.respond(
                    GenreTrackResponse(
                        listOf(
                            TrackResponse(
                                traktorId,
                                indexed.artists.map { Artist(1, it) },
                                indexed.name,
                                indexed.lengthMs
                            )
                        ),
                        "" /* unused by Traktor */
                    )
                )
            }

            get("/v4/catalog/tracks/{id}/download/") {
                val traktorId = call.parameters["id"]?.toLongOrNull()
                    ?: return@get call.respond(HttpStatusCode.BadRequest)
                val indexed = TrackIndex.get(traktorId)
                if (indexed == null || indexed.source !in sources.indices) {
                    // Traktor keeps its own collection, so it asks for tracks this process has
                    // never listed. Previously that dereferenced a null and returned a 500.
                    println("Unknown track $traktorId; browse its playlist again to re-index it")
                    return@get call.respond(HttpStatusCode.NotFound)
                }
                // On the IO dispatcher because it streams, converts and writes: minutes of blocking
                // work in the worst case, which on a request thread stalls every other route
                // Traktor is calling at the same time.
                loaded[traktorId] = withContext(Dispatchers.IO) {
                    sources[indexed.source].download(Utils.decode(traktorId) + indexed.remainder)
                }
                call.respond(Download("https://api.beatport.com/output/$traktorId.mp4", "foo", 1337))
            }

            // Traktor reports what it played and polls for its streaming link. Nothing here needs
            // either, but answering 404 left an error in the log in the middle of every load.
            post("/v4/events/play/") {
                call.respond(HttpStatusCode.OK)
            }

            outputRoutes(loaded)
        }
    }).start(wait = true)
}

/**
 * The two routes that hand Traktor the audio.
 *
 * The id is a whole path segment rather than a suffix so the extension is only there for Traktor's
 * benefit; the download route is what decides the url. Extracted from the module so a test can
 * mount them over a known file and check the range handling.
 */
internal fun Route.outputRoutes(loaded: Map<Long, File>) {
    head("/output/{id}") {
        call.respondTrack(loaded)
    }

    get("/output/{id}") {
        call.respondTrack(loaded)
    }
}

/**
 * Serves the file a download call put aside for this track. A miss is answered with 404 rather
 * than with whatever else is at hand: playing the wrong track is worse than a failed load.
 *
 * Content type is stated rather than inferred: library files are named after the track, and only
 * the extension this appends makes them mp4 to a sniffer.
 */
internal suspend fun ApplicationCall.respondTrack(loaded: Map<Long, File>) {
    val traktorId = parameters["id"]?.removeSuffix(".mp4")?.toLongOrNull()
    val file = traktorId?.let { loaded[it] }
    // Checked as well as looked up: the library evicts on a size limit, so a file put aside a few
    // loads ago can be gone by the time Traktor comes back for it.
    if (file == null || !file.isFile) {
        println("No pending download for ${parameters["id"]}; Traktor has to ask for it again")
        return respond(HttpStatusCode.NotFound)
    }
    respond(LocalFileContent(file, ContentType.Video.MP4))
}

private fun List<TrackResponse>.toNewSearchApi(): List<BeatportTrack> {
    return this.map {
        BeatportTrack(
            artists = it.artists.map {
                BeatportArtist(
                    it.name,
                    "Artist" // Required by traktor
                )
            },
            track_name = it.name,
            track_id = it.id,
            length = it.length_ms
        )
    }
}
