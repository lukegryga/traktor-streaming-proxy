package app

import java.io.File
import java.util.Properties

/**
 * Transcoded tracks kept on disk so a second load costs no Spotify streaming at all.
 *
 * mp4 rather than the original Ogg Vorbis because Traktor's streaming path refuses Vorbis outright
 * (verified: a valid 44.1kHz stereo .ogg is rejected as unplayable), so the converted file is the
 * only one that can be served and a stored ogg would have to be converted on every load anyway.
 *
 * Files are named for their track so the directory is browsable, which means the id to file mapping
 * has to be recorded rather than derived from the name.
 */
object Library {

    private val root = File("library")
    private val indexFile = File("data/library-index.properties")
    private val index = HashMap<String, String>()

    private const val MAX_NAME = 120

    fun load() {
        if (!indexFile.exists()) return
        runCatching {
            val props = Properties()
            indexFile.inputStream().use { props.load(it) }
            props.forEach { key, value -> index[key.toString()] = value.toString() }
            println("Library index holds ${index.size} tracks")
        }.onFailure { println("Could not read ${indexFile.name}: ${it.message}") }
    }

    /**
     * Returns null when the track has to be fetched, including when the index names a file that has
     * since been deleted or emptied - the entry is dropped so the next store rebuilds it.
     */
    fun cached(trackId: String): File? {
        val named = synchronized(index) { index[trackId] }
        if (named != null) {
            val file = File(root, named)
            if (file.isFile && file.length() > 0) return file
            println("Library entry for $trackId points at missing file $named; downloading it again")
            synchronized(index) { index.remove(trackId) }
            flush()
            return null
        }

        // Tracks stored before files were named adopt their old id-based name rather than download again.
        val legacy = File(root, "$trackId.mp4")
        if (legacy.isFile && legacy.length() > 0) {
            register(trackId, legacy.name)
            return legacy
        }
        return null
    }

    /** Reserves a file for the track, replacing any previous file for the same id. */
    fun prepare(trackId: String, title: String?, artist: String?): File {
        root.mkdirs()
        val target = File(root, fileName(trackId, title, artist))
        synchronized(index) { index[trackId] }
            ?.let { File(root, it) }
            ?.takeIf { it.isFile && it.name != target.name }
            ?.delete()
        return target
    }

    fun register(trackId: String, fileName: String) {
        synchronized(index) { index[trackId] = fileName }
        flush()
    }

    fun stats(): Pair<Int, Long> {
        val files = root.listFiles { f: File -> f.isFile && f.name.endsWith(".mp4") } ?: return 0 to 0L
        return files.size to files.sumOf { it.length() }
    }

    private fun fileName(trackId: String, title: String?, artist: String?): String {
        val base = listOfNotNull(title?.takeIf { it.isNotBlank() }, artist?.takeIf { it.isNotBlank() })
            .joinToString(" - ")
            .ifBlank { trackId }
        val safe = sanitise(base)

        // Two tracks can legitimately share a title and artist, and one must not overwrite the other.
        val taken = synchronized(index) { index.any { (key, value) -> key != trackId && value == "$safe.mp4" } }
        return if (taken) "$safe [${trackId.take(6)}].mp4" else "$safe.mp4"
    }

    private fun sanitise(value: String): String = value
        .map { if (it in "\\/:*?\"<>|" || it.code < 0x20) '_' else it }
        .joinToString("")
        .trim()
        .take(MAX_NAME)
        // Windows silently strips these from the end of a name, which would break the lookup.
        .trimEnd('.', ' ')
        .ifBlank { "track" }

    private fun flush() {
        runCatching {
            val props = Properties()
            synchronized(index) { index.forEach { (key, value) -> props.setProperty(key, value) } }
            indexFile.parentFile?.mkdirs()
            indexFile.outputStream().use { props.store(it, "spotify track id -> library file name") }
        }.onFailure { println("Could not write ${indexFile.name}: ${it.message}") }
    }
}
