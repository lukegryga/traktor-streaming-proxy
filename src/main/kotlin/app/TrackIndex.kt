package app

import java.io.File
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A Traktor track id only carries the first 10 characters of a source id, so the remainder and
 * the owning source live here. Held in memory alone, a restart left every track in Traktor's
 * cached collection unloadable with a NullPointerException on download.
 */
object TrackIndex {

    private val file = File("data/track-index.properties")
    private val entries = HashMap<Long, String>()

    @Volatile
    private var dirty = false

    fun load() {
        if (!file.exists()) return
        runCatching {
            val props = Properties()
            file.inputStream().use { props.load(it) }
            props.forEach { key, value -> entries[key.toString().toLong()] = value.toString() }
            println("Loaded ${entries.size} track ids")
        }.onFailure { println("Could not read ${file.name}: ${it.message}") }

        // Batched rather than written per track: browsing a large playlist would otherwise
        // rewrite the whole file on every request.
        Executors.newSingleThreadScheduledExecutor { Thread(it, "track-index").apply { isDaemon = true } }
            .scheduleWithFixedDelay(::flush, 5, 5, TimeUnit.SECONDS)
        Runtime.getRuntime().addShutdownHook(Thread(::flush))
    }

    fun put(traktorId: Long, sourceIndex: Int, remainder: String) {
        val value = "$sourceIndex:$remainder"
        synchronized(entries) {
            if (entries.put(traktorId, value) != value) dirty = true
        }
    }

    fun sourceIndex(traktorId: Long): Int? =
        synchronized(entries) { entries[traktorId] }?.substringBefore(':')?.toIntOrNull()

    fun remainder(traktorId: Long): String? =
        synchronized(entries) { entries[traktorId] }?.substringAfter(':')

    private fun flush() {
        if (!dirty) return
        runCatching {
            val props = Properties()
            synchronized(entries) { entries.forEach { (key, value) -> props.setProperty(key.toString(), value) } }
            file.parentFile?.mkdirs()
            file.outputStream().use { props.store(it, "traktor id -> source index and remaining source id") }
            dirty = false
        }.onFailure { println("Could not write ${file.name}: ${it.message}") }
    }
}
