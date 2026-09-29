package app

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val HOST = "api.beatport.com"
private const val TARGET = "127.0.0.1"

enum class HostsStatus { OK, MISSING, WRONG_ADDRESS }

data class HostsState(val status: HostsStatus, val detail: String)

/**
 * Traktor only ever asks for api.beatport.com, so the name has to resolve here for any of this to
 * be reached. The file needs administrator rights to write, which this process does not normally
 * have, so a direct write is attempted and an elevated one offered when it fails.
 */
object Hosts {

    private val file = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32/drivers/etc/hosts")

    fun state(): HostsState {
        if (!file.isFile) return HostsState(HostsStatus.MISSING, "No hosts file at ${file.absolutePath}")

        val mapped = runCatching {
            file.readLines()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line ->
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2 && parts.drop(1).any { it.equals(HOST, true) }) parts[0] else null
                }
        }.getOrElse { return HostsState(HostsStatus.MISSING, "Could not read the hosts file") }

        return when {
            mapped.isEmpty() -> HostsState(HostsStatus.MISSING, "$HOST is not redirected here")
            mapped.contains(TARGET) -> HostsState(HostsStatus.OK, "$HOST resolves to $TARGET")
            else -> HostsState(HostsStatus.WRONG_ADDRESS, "$HOST points at ${mapped.first()}")
        }
    }

    /** Silently succeeds only when already running elevated; otherwise the panel offers to fix it. */
    fun ensure() {
        if (state().status == HostsStatus.OK) return
        val written = runCatching {
            file.appendText(System.lineSeparator() + "$TARGET\t$HOST" + System.lineSeparator())
            true
        }.getOrDefault(false)

        if (written && state().status == HostsStatus.OK) {
            println("Added $HOST to the hosts file")
        } else {
            println("$HOST is not in the hosts file and adding it needs administrator rights")
        }
    }

    fun fix(): Result<String> = runCatching {
        if (state().status == HostsStatus.OK) return@runCatching "Already correct"

        // Existing lines for the host are dropped rather than edited, so a wrong address and a
        // duplicate both end up as the one correct entry. Windows caches resolutions, so the
        // flush matters as much as the write.
        // The entry is built here and embedded literally. A PowerShell escape such as `t means
        // nothing inside single quotes and would be written to the file as typed.
        val entry = "$TARGET\t$HOST"
        val pattern = HOST.replace(".", "\\.")

        val script = """
            ${'$'}p = '${file.absolutePath.replace("'", "''")}'
            ${'$'}keep = Get-Content ${'$'}p | Where-Object { ${'$'}_ -notmatch '$pattern' }
            (${'$'}keep + '$entry') | Set-Content ${'$'}p -Encoding ASCII
            ipconfig /flushdns | Out-Null
        """.trimIndent()

        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(StandardCharsets.UTF_16LE))
        val proc = ProcessBuilder(
            "powershell", "-NoProfile", "-Command",
            "Start-Process powershell -Verb RunAs -WindowStyle Hidden " +
                "-ArgumentList '-NoProfile','-EncodedCommand','$encoded' -Wait"
        ).redirectErrorStream(true).start()

        val output = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor(90, TimeUnit.SECONDS)

        val after = state()
        if (after.status != HostsStatus.OK) {
            throw IllegalStateException(
                if (output.contains("canceled", true) || output.contains("cancelled", true)) {
                    "The administrator prompt was declined"
                } else {
                    "Could not update the hosts file"
                }
            )
        }
        "$HOST now resolves to $TARGET"
    }
}
