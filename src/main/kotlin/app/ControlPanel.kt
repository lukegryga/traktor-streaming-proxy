package app

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * Served on loopback over plain HTTP, deliberately separate from the Beatport server on 443: that
 * one carries a certificate for api.beatport.com and every path on it is a route Traktor may call.
 */
object ControlPanel {

    private val json = Json { prettyPrint = false }

    fun start() {
        val port = Settings.uiPort
        embeddedServer(Netty, host = "127.0.0.1", port = port) {
            install(ContentNegotiation) { json() }
            routing {
                get("/") { call.respondBytes(page(), ContentType.Text.Html) }
                get("/api/state") { call.respondText(state(), ContentType.Application.Json) }
                get("/api/logs") { call.respondText(Logging.tail(400), ContentType.Text.Plain) }

                get("/api/library") {
                    val size = (call.request.queryParameters["size"]?.toIntOrNull() ?: 25).coerceIn(1, 200)
                    val page = (call.request.queryParameters["page"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                    val query = call.request.queryParameters["q"].orEmpty().trim().lowercase()
                    call.respondText(libraryPage(page, size, query), ContentType.Application.Json)
                }

                post("/api/settings") {
                    val body = json.parseToJsonElement(call.receiveText()) as JsonObject
                    apply(body)
                    call.respondText(state(), ContentType.Application.Json)
                }

                post("/api/provider") {
                    val body = json.parseToJsonElement(call.receiveText()) as JsonObject
                    val name = body["name"]?.jsonPrimitive?.contentOrNull
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "name required")
                    val enabled = body["enabled"]?.jsonPrimitive?.booleanOrNull ?: false
                    call.respondText(setProvider(name, enabled), ContentType.Application.Json)
                }

                post("/api/library/delete") {
                    val body = json.parseToJsonElement(call.receiveText()) as JsonObject
                    val id = body["trackId"]?.jsonPrimitive?.contentOrNull
                    val removed = if (id == "*") Library.deleteAll() else if (id != null && Library.delete(id)) 1 else 0
                    call.respondText(result(true, "Removed $removed"), ContentType.Application.Json)
                }

                post("/api/library/move") {
                    val body = json.parseToJsonElement(call.receiveText()) as JsonObject
                    val path = body["path"]?.jsonPrimitive?.contentOrNull
                        ?: return@post call.respond(HttpStatusCode.BadRequest, "path required")
                    val outcome = Library.moveTo(File(path))
                    call.respondText(
                        outcome.fold(
                            { result(true, "Moved $it tracks") },
                            { result(false, it.message ?: "Move failed") }
                        ),
                        ContentType.Application.Json
                    )
                }

                post("/api/certificate/regenerate") {
                    val outcome = Certificates.regenerate()
                    call.respondText(
                        outcome.fold({ result(true, it) }, { result(false, it.message ?: "Failed") }),
                        ContentType.Application.Json
                    )
                }

                post("/api/hosts/fix") {
                    val outcome = Hosts.fix()
                    call.respondText(
                        outcome.fold({ result(true, it) }, { result(false, it.message ?: "Failed") }),
                        ContentType.Application.Json
                    )
                }

                post("/api/traktor/clear") {
                    val outcome = TraktorCache.clear()
                    call.respondText(
                        outcome.fold(
                            { result(true, "Cleared Traktor's cache") },
                            { result(false, it.message ?: "Clear failed") }
                        ),
                        ContentType.Application.Json
                    )
                }

                post("/api/signout") {
                    val body = json.parseToJsonElement(call.receiveText()) as JsonObject
                    val name = body["name"]?.jsonPrimitive?.contentOrNull
                    if (name == "spotify") {
                        File("data/credentials.json").delete()
                        File("data/spotify-refresh-token").delete()
                        call.respondText(result(true, "Signed out, restart to sign in again"), ContentType.Application.Json)
                    } else {
                        call.respondText(result(false, "Nothing to sign out of"), ContentType.Application.Json)
                    }
                }

                post("/api/startup") {
                    Startup.toggle()
                    call.respondText(state(), ContentType.Application.Json)
                }
            }
        }.start(wait = false)
        println("Control panel on http://127.0.0.1:$port")
    }

    private fun result(ok: Boolean, message: String) =
        buildJsonObject { put("ok", ok); put("message", message) }.toString()

    private fun setProvider(name: String, enabled: Boolean): String {
        val current = Settings.enabledSources.toMutableList()
        if (enabled) {
            if (!current.contains(name)) current.add(name)
        } else {
            current.remove(name)
        }
        Settings.enabledSources = current

        // The cache has to go either way: ids shift when the list changes length in either direction.
        val cleared = TraktorCache.clear()
        return cleared.fold(
            { result(true, "Saved. Restart Traktor, its cache was cleared.") },
            { result(false, "Saved, but ${it.message}") }
        )
    }

    private fun apply(body: JsonObject) {
        body["serverPort"]?.jsonPrimitive?.intOrNullSafe()?.let { Settings.serverPort = it }
        body["uiPort"]?.jsonPrimitive?.intOrNullSafe()?.let { Settings.uiPort = it }
        body["spotifyClientId"]?.jsonPrimitive?.contentOrNull?.let { Settings.spotifyClientId = it }
        body["tidalClientId"]?.jsonPrimitive?.contentOrNull?.let { Settings.tidalClientId = it }
        body["tidalClientSecret"]?.jsonPrimitive?.contentOrNull?.let { Settings.tidalClientSecret = it }
        body["searchableSources"]?.jsonPrimitive?.contentOrNull
            ?.let { Settings.searchableSources = it.split(",").filter { s -> s.isNotBlank() } }
        body["libraryLimitEnabled"]?.jsonPrimitive?.booleanOrNull?.let { Settings.libraryLimitEnabled = it }
        body["libraryLimitBytes"]?.jsonPrimitive?.longOrNull?.let { Settings.libraryLimitBytes = it }
        body["veryHighQuality"]?.jsonPrimitive?.booleanOrNull?.let { Settings.veryHighQuality = it }
        body["logLevel"]?.jsonPrimitive?.contentOrNull?.let {
            Settings.logLevel = it
            Logging.setLevel(it)
        }
        if (body.containsKey("libraryLimitEnabled") || body.containsKey("libraryLimitBytes")) {
            Library.enforceLimit()
        }
    }

    private fun JsonPrimitive.intOrNullSafe(): Int? = contentOrNull?.toIntOrNull()

    private fun state(): String {
        val (trackCount, trackBytes) = Library.stats()
        val (cacheFiles, cacheBytes) = TraktorCache.size()
        val statuses = SourceManager.statuses().associateBy { it.name }

        return buildJsonObject {
            put("serverPort", Settings.serverPort)
            put("uiPort", Settings.uiPort)

            val hosts = Hosts.state()
            put("hosts", buildJsonObject {
                put("ok", hosts.status == HostsStatus.OK)
                put("detail", hosts.detail)
            })

            val cert = Certificates.state()
            put("cert", buildJsonObject {
                put("present", cert.present)
                put("expiresAt", cert.expiresAt ?: 0L)
                put("healthy", cert.healthy)
                put("problem", cert.problem ?: "")
            })
            put("accountId", Settings.beatportAccountId)
            put("spotifyClientId", Settings.spotifyClientId)
            put("tidalClientId", Settings.tidalClientId)
            put("tidalClientSecret", if (Settings.tidalClientSecret.isBlank()) "" else "********")
            put("searchableSources", Settings.searchableSources.joinToString(","))
            put("veryHighQuality", Settings.veryHighQuality)
            put("logLevel", Settings.logLevel)
            put("autostart", Startup.isEnabled())
            put("libraryPath", Library.root().absolutePath)
            put("libraryLimitEnabled", Settings.libraryLimitEnabled)
            put("libraryLimitBytes", Settings.libraryLimitBytes)
            put("trackCount", trackCount)
            put("trackBytes", trackBytes)
            put("traktorCacheFiles", cacheFiles)
            put("traktorCacheBytes", cacheBytes)
            put("traktorRunning", TraktorCache.isTraktorRunning())

            put("providers", buildJsonArray {
                listOf("spotify", "youtube", "tidal").forEach { name ->
                    add(buildJsonObject {
                        put("name", name)
                        put("enabled", Settings.enabledSources.contains(name))
                        put("state", statuses[name]?.state?.name ?: "IDLE")
                        put("detail", statuses[name]?.detail ?: "")
                        put("signedIn", name == "spotify" && File("data/credentials.json").isFile)
                        put("needsSignIn", name == "spotify")
                    })
                }
            })

        }.toString()
    }

    /**
     * Paged on the server: the panel polls every few seconds, and a library at the 10GB limit holds
     * over a thousand tracks that nobody wants re-sent each time.
     */
    private fun libraryPage(page: Int, size: Int, query: String): String {
        val matching = Library.entries()
            .filter { query.isEmpty() || it.file.name.lowercase().contains(query) }
            .sortedByDescending { it.playedAt }

        val pages = if (matching.isEmpty()) 0 else (matching.size + size - 1) / size
        val current = page.coerceAtMost((pages - 1).coerceAtLeast(0))

        return buildJsonObject {
            put("total", matching.size)
            put("page", current)
            put("pages", pages)
            put("items", buildJsonArray {
                matching.drop(current * size).take(size).forEach { entry ->
                    add(buildJsonObject {
                        put("trackId", entry.trackId)
                        put("name", entry.file.nameWithoutExtension)
                        put("bytes", entry.file.length())
                        put("playedAt", entry.playedAt)
                    })
                }
            })
        }.toString()
    }

    private fun page(): ByteArray =
        ControlPanel::class.java.getResourceAsStream("/panel/index.html")?.readBytes()
            ?: "<h1>panel/index.html missing from the build</h1>".toByteArray()
}
