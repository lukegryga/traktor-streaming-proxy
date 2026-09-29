package app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable
data class IndexedTrack(
    val source: Int,
    val remainder: String,
    val name: String,
    val artists: List<String>,
    val lengthMs: Long
)

/**
 * A Traktor track id only carries the first 10 characters of a source id, so the remainder, the
 * owning source and the metadata live here. Held in memory alone, a restart left every track in
 * Traktor's cached collection unloadable with a NullPointerException on download.
 */
object TrackIndex {

    private val file = AppPaths.dataFile("data/track-index.json")
    private val entries = HashMap<Long, IndexedTrack>()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var dirty = false

    fun load() {
        if (file.exists()) {
            runCatching {
                json.decodeFromString<Map<String, IndexedTrack>>(file.readText())
                    .forEach { (key, value) -> key.toLongOrNull()?.let { entries[it] = value } }
                println("Loaded ${entries.size} indexed tracks")
            }.onFailure { println("Could not read ${file.name}: ${it.message}") }
        }

        // Batched rather than written per track: browsing a large playlist would otherwise
        // rewrite the whole file on every request.
        Executors.newSingleThreadScheduledExecutor { Thread(it, "track-index").apply { isDaemon = true } }
            .scheduleWithFixedDelay(::flush, 5, 5, TimeUnit.SECONDS)
        Runtime.getRuntime().addShutdownHook(Thread(::flush))
    }

    fun put(traktorId: Long, track: IndexedTrack) {
        synchronized(entries) {
            if (entries.put(traktorId, track) != track) dirty = true
        }
    }

    fun get(traktorId: Long): IndexedTrack? = synchronized(entries) { entries[traktorId] }

    private fun flush() {
        if (!dirty) return
        runCatching {
            val snapshot = synchronized(entries) { entries.mapKeys { it.key.toString() } }
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(snapshot))
            dirty = false
        }.onFailure { println("Could not write ${file.name}: ${it.message}") }
    }
}
