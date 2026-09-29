package app

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

private val MAC_KEY = """
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA5otUtjLv5LJmLK+Lw+TI
UzrX0j3UP493K8T2dzqE/tLMVvOvNOwUDvzomX0VpTZrXesLFpCrztdMG5p2I4M0
jTVTl6cpU8SD68WUjqlvLUYCHIGub4okQK57f5d4iTagU9FjyB2VwfA3nuuhhEpj
4ioQuYR8ENhMiMNMydITsXCFEbRgxpDRvIj24+/QthsOETtu2Ooq4U+pvidQPu5l
rcZdgemPUFPtTn4GqQ0/wZpaD2mzMlLUi4xlqcGo0LsCtTkPtAhSWxWrl+ReKj+k
9zJCK8qzeYUPf/fuA5I7owuyRrfN6ReiFdU/UF38Ou6pSrRCvVkQkmpTmv8kEnvn
RwIDAQAB
-----END PUBLIC KEY-----""".trim().toByteArray()

private val WINDOWS_KEY = """
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAozFFb+t0RSB1AdDBwap+
dZ/8AH/FseqpSkG8oW3rzonQ/jEMtsY6AJogv+HfFclnrVlx1aYyJvQXIwIBx+Sk
E5J+YTdUKYlLX62xL44TQLDOw1varMnxWfCX4ih5taXDWacu1HemI+peRtsi8r9m
FVCBMFuFVOCv9vGL8H4L/12GTO0+rIIpBZr11pQ/K44WFyr9GOVx/GTeDH52Ktlx
CgOMADfgdH9hjLryS+EN/LL/yg1bw7OF9UmpZGzUaTjn1qYErlq5bqlDcBFSdo6v
b5v74acNV8Qjbov8okSoUd13A6JJkJp4Sxi/Ve07DTvPZHGIZn01nVpLX9tkDRcT
2wIDAQAB
-----END PUBLIC KEY-----""".trim().toByteArray()

enum class PatchStatus { PATCHED, UNPATCHED, NOT_FOUND, UNRECOGNISED }

data class PatchState(val status: PatchStatus, val path: String, val detail: String)

/**
 * Traktor verifies the Beatport license with a platform specific public key, and only the macOS
 * key matches the license this server serves. Swapping the embedded key is what makes Traktor
 * accept it; the two are the same length, so the replacement is written in place.
 */
object TraktorPatch {

    fun state(): PatchState {
        val exe = locate()
            ?: return PatchState(PatchStatus.NOT_FOUND, "", "Traktor was not found; set its path below")

        return when {
            find(exe, MAC_KEY) != null ->
                PatchState(PatchStatus.PATCHED, exe.absolutePath, "${exe.name} is patched")
            find(exe, WINDOWS_KEY) != null ->
                PatchState(PatchStatus.UNPATCHED, exe.absolutePath, "${exe.name} is not patched yet")
            else ->
                PatchState(PatchStatus.UNRECOGNISED, exe.absolutePath, "No known key in ${exe.name}")
        }
    }

    /**
     * Searched in order of confidence: what the user set, what is running, then the usual install
     * folders. Nothing walks whole drives - the executable is over half a gigabyte and the scan
     * would cost more than asking.
     */
    fun locate(): File? {
        Settings.traktorPath.takeIf { it.isNotBlank() }
            ?.let { File(it) }
            ?.takeIf { it.isFile }
            ?.let { return it }

        runningTraktor()?.let { return it }

        return installDirectories()
            .flatMap { dir -> dir.walkTopDown().maxDepth(3).filter { candidate(it) } }
            .firstOrNull()
    }

    private fun candidate(file: File): Boolean =
        file.isFile &&
            file.name.startsWith("Traktor", ignoreCase = true) &&
            file.name.endsWith(".exe", ignoreCase = true) &&
            !file.name.contains("crashpad", ignoreCase = true)

    private fun runningTraktor(): File? = runCatching {
        ProcessHandle.allProcesses()
            .map { it.info().command().orElse("") }
            .filter { it.endsWith(".exe", true) && it.substringAfterLast('\\').startsWith("Traktor", true) }
            .findFirst()
            .map { File(it) }
            .orElse(null)
            ?.takeIf { it.isFile }
    }.getOrNull()

    private fun installDirectories(): List<File> = listOfNotNull(
        System.getenv("ProgramFiles"),
        System.getenv("ProgramFiles(x86)"),
        System.getenv("ProgramW6432")
    ).distinct().map { File(it, "Native Instruments") }.filter { it.isDirectory }

    fun patch(target: File = locate() ?: throw IllegalStateException("Traktor was not found")): Result<String> =
        runCatching {
            if (TraktorCache.isTraktorRunning()) {
                throw IllegalStateException("Close Traktor first, it is holding its executable open")
            }
            if (find(target, MAC_KEY) != null) return@runCatching "${target.name} is already patched"

            val offset = find(target, WINDOWS_KEY)
                ?: throw IllegalStateException("No Windows key found in ${target.name}; it may already differ")

            val backup = File(target.absolutePath + ".backup")
            if (!backup.isFile) target.copyTo(backup, overwrite = false)

            // Written at the offset rather than rewriting the file: the keys are the same length,
            // and the executable is over half a gigabyte.
            RandomAccessFile(target, "rw").use {
                it.seek(offset)
                it.write(MAC_KEY)
            }

            if (find(target, MAC_KEY) == null) throw IllegalStateException("The patch did not take")
            "Patched ${target.name}; a backup is beside it"
        }

    /** Program Files denies writes to anything unelevated, so the work is repeated under UAC. */
    fun patchElevated(): Result<String> {
        val target = locate() ?: return Result.failure(IllegalStateException("Traktor was not found"))
        if (TraktorCache.isTraktorRunning()) {
            return Result.failure(IllegalStateException("Close Traktor first, it is holding its executable open"))
        }

        val direct = patch(target)
        if (direct.isSuccess) return direct

        val relaunched = Elevate.run(listOf("--patch", target.absolutePath))
        if (!relaunched) return Result.failure(IllegalStateException("The administrator prompt was declined"))

        return if (find(target, MAC_KEY) != null) {
            Result.success("Patched ${target.name}; a backup is beside it")
        } else {
            Result.failure(IllegalStateException(direct.exceptionOrNull()?.message ?: "The patch did not take"))
        }
    }

    /** Mapped in windows so a half gigabyte executable is never held in memory at once. */
    private fun find(file: File, needle: ByteArray): Long? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            raf.channel.use { channel ->
                val size = channel.size()
                val window = 32L * 1024 * 1024
                var start = 0L
                while (start < size) {
                    val length = minOf(window, size - start)
                    val buffer = channel.map(FileChannel.MapMode.READ_ONLY, start, length)
                    val bytes = ByteArray(length.toInt())
                    buffer.get(bytes)

                    val at = indexOf(bytes, needle)
                    if (at >= 0) return@runCatching start + at
                    if (start + length >= size) break
                    start += length - needle.size
                }
                null
            }
        }
    }.getOrNull()

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
