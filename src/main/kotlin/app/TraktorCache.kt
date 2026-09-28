package app

import java.io.File

/**
 * Traktor identifies genres and playlists by their position in the source list, so enabling or
 * disabling a provider shifts every id its collection holds. Clearing this cache is what stops it
 * asking for tracks by an id that now means something else.
 */
object TraktorCache {

    private fun accountCache(): File? {
        val local = System.getenv("LOCALAPPDATA") ?: return null
        val account = Settings.beatportAccountId.ifBlank { return null }
        return File(local, "Native Instruments/Traktor/Streaming/Beatport/$account")
    }

    fun size(): Pair<Int, Long> {
        val dir = accountCache() ?: return 0 to 0L
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        return files.size to files.sumOf { it.length() }
    }

    /**
     * Traktor keeps sqlite3.db open while running, so a delete underneath it can leave a half
     * removed index - the very state this is meant to prevent.
     */
    fun isTraktorRunning(): Boolean = runCatching {
        ProcessHandle.allProcesses().anyMatch { handle ->
            handle.info().command().orElse("").substringAfterLast('\\').equals("Traktor.exe", ignoreCase = true)
        }
    }.getOrDefault(false)

    fun clear(): Result<Int> = runCatching {
        if (isTraktorRunning()) throw IllegalStateException("Close Traktor first, it is holding its cache open")
        val dir = accountCache() ?: throw IllegalStateException("Could not locate Traktor's cache")
        if (!dir.isDirectory) return@runCatching 0

        var removed = 0
        dir.listFiles()?.forEach { entry ->
            if (entry.deleteRecursively()) removed++
        }
        println("Cleared Traktor's Beatport cache at ${dir.absolutePath}")
        removed
    }
}
