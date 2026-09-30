package app

import java.io.File
import java.util.Properties

data class LibraryEntry(val trackId: String, val file: File, val playedAt: Long)

/**
 * Downloaded tracks kept on disk so a second load costs no streaming at all.
 *
 * mp4 rather than the source Ogg Vorbis because Traktor's streaming path refuses Vorbis outright
 * (verified: a valid 44.1kHz stereo .ogg is rejected as unplayable), so the converted file is the
 * only one that can be served and a stored ogg would have to be converted on every load anyway.
 *
 * Files are named for their track so the folder is browsable, which means the id to file mapping
 * has to be recorded rather than derived from the name.
 */
object Library {

    private val indexFile = AppPaths.dataFile("data/library-index.properties")
    private val names = HashMap<String, String>()
    private val played = HashMap<String, Long>()

    private const val MAX_NAME = 120

    fun root(): File = Settings.libraryPath

    fun load() {
        if (!indexFile.exists()) return
        runCatching {
            val props = Properties()
            indexFile.inputStream().use { props.load(it) }
            props.forEach { key, value ->
                val id = key.toString()
                // "<epoch millis>|<file name>"; entries written before eviction existed have no timestamp.
                val raw = value.toString()
                val split = raw.indexOf('|')
                if (split > 0) {
                    names[id] = raw.substring(split + 1)
                    played[id] = raw.substring(0, split).toLongOrNull() ?: 0L
                } else {
                    names[id] = raw
                    played[id] = 0L
                }
            }
            println("Library index holds ${names.size} tracks")
        }.onFailure { println("Could not read ${indexFile.name}: ${it.message}") }
        migrateUnprefixedKeys()
        adoptOrphans()
    }

    /**
     * Files on disk with no index entry would otherwise be invisible: absent from the panel and not
     * counted against the size limit, so the library could grow past it unnoticed.
     */
    private fun adoptOrphans() {
        val known = synchronized(names) { names.values.toSet() }
        val orphans = root().listFiles { f: File -> f.isFile && f.name.endsWith(".mp4") }
            ?.filterNot { known.contains(it.name) }
            ?: return

        orphans.forEach { file ->
            // Prefixed because the name is not a track id: nothing can ever look this entry up, it
            // exists so the file counts against the size limit and can be evicted like any other.
            val id = "orphan:${file.name}"
            synchronized(names) { names[id] = file.name }
            synchronized(played) { played[id] = file.lastModified() }
            println("Adopted untracked library file ${file.name}")
        }
        if (orphans.isNotEmpty()) flush()
    }

    /**
     * Keys gained a "<source>:" prefix once sources other than Spotify started storing tracks.
     * Everything written before that came from Spotify, and renaming the entries is what keeps an
     * existing library from downloading every track it already holds a second time.
     */
    private fun migrateUnprefixedKeys() {
        val stale = synchronized(names) { names.keys.filterNot { it.contains(':') } }
        if (stale.isEmpty()) return
        synchronized(names) { stale.forEach { id -> names.remove(id)?.let { names["spotify:$id"] = it } } }
        synchronized(played) { stale.forEach { id -> played.remove(id)?.let { played["spotify:$id"] = it } } }
        println("Renamed ${stale.size} library entries to the source-prefixed form")
        flush()
    }

    /**
     * Keeps [bytes] and hands back the file they landed in.
     */
    fun store(trackId: String, title: String?, artist: String?, bytes: ByteArray): File {
        val target = prepare(trackId, title, artist)
        return runCatching {
            target.writeBytes(bytes)
            register(trackId, target.name)
            enforceLimit()
            val (count, total) = stats()
            println("Stored ${target.name}; library holds $count tracks, ${total / 1024 / 1024} MB")
            target
        }.onFailure {
            target.delete()
            println("Could not keep $trackId in the library: ${it.message}")
        }.getOrElse { unkept(bytes) }
    }

    /**
     * A track the library would not take is still served, from a file outside it that goes when
     * the process does: being unable to cache it is not a reason to fail the deck.
     */
    private fun unkept(bytes: ByteArray): File =
        File.createTempFile("unkept-", ".mp4").apply { deleteOnExit(); writeBytes(bytes) }

    /**
     * Returns null when the track has to be fetched, including when the index names a file that has
     * since been deleted or emptied - the entry is dropped so the next store rebuilds it.
     */
    fun cached(trackId: String): File? {
        val named = synchronized(names) { names[trackId] }
        if (named != null) {
            val file = File(root(), named)
            if (file.isFile && file.length() > 0) {
                touch(trackId)
                return file
            }
            println("Library entry for $trackId points at missing file $named; downloading it again")
            forget(trackId)
            return null
        }

        // Tracks stored before files were named adopt their old id-based name rather than download again.
        val legacy = File(root(), "${trackId.substringAfter(':')}.mp4")
        if (legacy.isFile && legacy.length() > 0) {
            register(trackId, legacy.name)
            return legacy
        }
        return null
    }

    /** Reserves a file for the track, replacing any previous file for the same id. */
    fun prepare(trackId: String, title: String?, artist: String?): File {
        root().mkdirs()
        val target = File(root(), fileName(trackId, title, artist))
        synchronized(names) { names[trackId] }
            ?.let { File(root(), it) }
            ?.takeIf { it.isFile && it.name != target.name }
            ?.delete()
        return target
    }

    fun register(trackId: String, fileName: String) {
        synchronized(names) { names[trackId] = fileName }
        touch(trackId)
    }

    fun touch(trackId: String) {
        synchronized(played) { played[trackId] = System.currentTimeMillis() }
        flush()
    }

    fun entries(): List<LibraryEntry> = synchronized(names) { names.toMap() }
        .mapNotNull { (id, name) ->
            File(root(), name).takeIf { it.isFile }?.let {
                LibraryEntry(id, it, synchronized(played) { played[id] } ?: 0L)
            }
        }

    fun stats(): Pair<Int, Long> = entries().let { it.size to it.sumOf { entry -> entry.file.length() } }

    fun delete(trackId: String): Boolean {
        val name = synchronized(names) { names[trackId] } ?: return false
        File(root(), name).delete()
        forget(trackId)
        return true
    }

    fun deleteAll(): Int {
        val all = entries()
        all.forEach { it.file.delete() }
        synchronized(names) { names.clear() }
        synchronized(played) { played.clear() }
        flush()
        return all.size
    }

    /**
     * Evicts least recently played tracks until the library fits. Silent by design: being refused a
     * track mid-set is worse than quietly losing the one played longest ago.
     */
    fun enforceLimit() {
        if (!Settings.libraryLimitEnabled) return
        val limit = Settings.libraryLimitBytes
        if (limit <= 0) return

        var total = entries().sumOf { it.file.length() }
        if (total <= limit) return

        entries().sortedBy { it.playedAt }.forEach { entry ->
            if (total <= limit) return
            total -= entry.file.length()
            println("Library over limit; evicting ${entry.file.name}")
            entry.file.delete()
            forget(entry.trackId)
        }
    }

    /**
     * Copies before deleting rather than renaming: the destination is often another drive, where a
     * rename fails outright. The index only moves once every file has landed.
     */
    fun moveTo(destination: File): Result<Int> = runCatching {
        val source = root()
        if (destination.canonicalFile == source.canonicalFile) return@runCatching 0

        destination.mkdirs()
        if (!destination.isDirectory) throw IllegalArgumentException("${destination.absolutePath} is not a directory")

        val files = entries()
        val needed = files.sumOf { it.file.length() }
        val free = destination.usableSpace
        if (free < needed) {
            throw IllegalStateException("Needs ${needed / 1024 / 1024} MB, only ${free / 1024 / 1024} MB free")
        }

        files.forEach { entry ->
            val target = File(destination, entry.file.name)
            entry.file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            if (target.length() != entry.file.length()) {
                target.delete()
                throw IllegalStateException("Copy of ${entry.file.name} is incomplete; nothing was removed")
            }
        }

        Settings.libraryPath = destination
        files.forEach { it.file.delete() }
        println("Moved ${files.size} tracks to ${destination.absolutePath}")
        files.size
    }

    private fun forget(trackId: String) {
        synchronized(names) { names.remove(trackId) }
        synchronized(played) { played.remove(trackId) }
        flush()
    }

    private fun fileName(trackId: String, title: String?, artist: String?): String {
        val base = listOfNotNull(title?.takeIf { it.isNotBlank() }, artist?.takeIf { it.isNotBlank() })
            .joinToString(" - ")
            .ifBlank { trackId }
        val safe = sanitise(base)

        // Two tracks can legitimately share a title and artist, and one must not overwrite the other.
        val taken = synchronized(names) { names.any { (key, value) -> key != trackId && value == "$safe.mp4" } }
        return if (taken) "$safe [${trackId.substringAfter(':').take(6)}].mp4" else "$safe.mp4"
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
            synchronized(names) {
                names.forEach { (key, value) ->
                    props.setProperty(key, "${synchronized(played) { played[key] } ?: 0L}|$value")
                }
            }
            indexFile.parentFile?.mkdirs()
            indexFile.outputStream().use { props.store(it, "source:track id -> last played millis and library file name") }
        }.onFailure { println("Could not write ${indexFile.name}: ${it.message}") }
    }
}
