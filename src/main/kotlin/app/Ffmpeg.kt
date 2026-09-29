package app

import java.io.File
import java.util.concurrent.TimeUnit

data class FfmpegState(
    val path: String?,
    val version: String?,
    val installing: Boolean,
    val message: String,
    val canInstall: Boolean
) {
    val found: Boolean get() = path != null
}

/**
 * Spotify is streamed as Ogg Vorbis, which Traktor refuses, so every track goes through ffmpeg on
 * its way to the library. It is the one requirement this app cannot provision itself, which is why
 * it is reported in the panel beside the certificate and the hosts entry rather than only failing
 * at the moment a track is loaded.
 *
 * Resolved to an absolute path rather than trusted to PATH: the process environment is a snapshot
 * taken at launch, so a copy installed while the app is running would not be on it.
 */
object Ffmpeg {

    private const val WINGET_ID = "Gyan.FFmpeg"

    @Volatile
    private var installing = false

    @Volatile
    private var message = ""

    // Reading the version starts a process, so it is remembered against the path it came from and
    // re-read only if that path changes.
    @Volatile
    private var cachedVersion: Pair<String, String?>? = null

    @Volatile
    private var cachedWinget: Boolean? = null

    /** What to invoke. Falls back to the bare name so the failure still reads as an ffmpeg one. */
    fun executable(): String = locate()?.absolutePath ?: "ffmpeg"

    fun state(): FfmpegState {
        val found = locate()
        return FfmpegState(
            path = found?.absolutePath,
            version = found?.let { version(it) },
            installing = installing,
            message = message,
            canInstall = hasWinget()
        )
    }

    /**
     * Returns as soon as winget is started rather than waiting for it: the download runs to a few
     * hundred megabytes, and the panel polls, so progress belongs in [state] and not in a request
     * the browser would hold open for minutes.
     */
    @Synchronized
    fun install(): Pair<Boolean, String> {
        if (installing) return false to "Already installing"
        if (locate() != null) return false to "ffmpeg is already installed"
        if (!hasWinget()) return false to "winget is not available on this machine"

        installing = true
        message = "Installing ffmpeg..."
        Thread(::runInstall, "ffmpeg-install").apply { isDaemon = true }.start()
        return true to "Installing ffmpeg, this takes a few minutes"
    }

    private fun runInstall() {
        runCatching {
            // --exact so a partial id match cannot pull in something else, and both agreement
            // flags because winget otherwise stops on a prompt no one can answer here.
            val proc = ProcessBuilder(
                "winget", "install", "--exact", "--id", WINGET_ID,
                "--accept-package-agreements", "--accept-source-agreements"
            ).redirectErrorStream(true).start()

            val output = proc.inputStream.bufferedReader().use { it.readText() }
            val finished = proc.waitFor(20, TimeUnit.MINUTES)
            if (!finished) proc.destroyForcibly()

            println("winget install $WINGET_ID: ${output.trim().takeLast(500)}")

            message = when {
                !finished -> "winget did not finish in time"
                locate() != null -> "ffmpeg installed"
                proc.exitValue() == 0 -> "winget reported success but ffmpeg is still not on PATH"
                else -> "winget failed: ${output.trim().lines().lastOrNull { it.isNotBlank() } ?: "no output"}"
            }
        }.onFailure {
            message = "Could not run winget: ${it.message}"
            println(message)
        }
        installing = false
    }

    private fun locate(): File? = candidates("ffmpeg.exe").firstOrNull { it.isFile }

    private fun candidates(executable: String): Sequence<File> = sequence {
        System.getenv("PATH")?.split(File.pathSeparatorChar)?.forEach { entry ->
            val dir = entry.trim().trim('"')
            if (dir.isNotEmpty()) yield(File(dir, executable))
        }
        // Where winget puts its shims. Worth looking at explicitly: an install done from here
        // lands there, and this process's PATH was captured before that happened.
        System.getenv("LOCALAPPDATA")?.let { yield(File(it, "Microsoft\\WinGet\\Links\\$executable")) }
    }

    /**
     * Probed by running it rather than by looking for the file. winget is delivered as an App
     * Execution Alias, a zero byte reparse point that Java reports as absent even while starting
     * it by name works, so any existence check would say it is missing on every machine that has it.
     */
    private fun hasWinget(): Boolean = cachedWinget ?: runCatching {
        val proc = ProcessBuilder("winget", "--version").redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor(10, TimeUnit.SECONDS) && proc.exitValue() == 0
    }.getOrDefault(false).also { cachedWinget = it }

    /**
     * Only the version out of `ffmpeg version 7.1-full_build-www.gyan.dev ...`. The build suffix
     * is dropped because every Gyan build carries it and it is half the width of the row.
     */
    private fun version(binary: File): String? {
        val key = binary.absolutePath
        cachedVersion?.takeIf { it.first == key }?.let { return it.second }

        val read = runCatching {
            val proc = ProcessBuilder(key, "-version").redirectErrorStream(true).start()
            val first = proc.inputStream.bufferedReader().use { it.readLine() }
            proc.waitFor(10, TimeUnit.SECONDS)
            first?.substringAfter("version ", "")
                ?.substringBefore(' ')
                ?.replace(Regex("-(?:full|essentials|shared)_build.*$"), "")
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()

        cachedVersion = key to read
        return read
    }
}
