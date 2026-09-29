package app

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Re-runs this application elevated instead of reimplementing the work in a PowerShell script:
 * the logic that needs administrator rights is the same logic, and a second copy of it in another
 * language is a second thing to get wrong.
 */
object Elevate {

    fun run(args: List<String>, timeoutSeconds: Long = 120): Boolean = runCatching {
        val (executable, leading) = relaunch()
        val arguments = (leading + args).joinToString(",") { "'${it.quoted()}'" }

        val command = "Start-Process '${executable.quoted()}' -Verb RunAs -WindowStyle Hidden -Wait " +
            "-WorkingDirectory '${AppPaths.appDir.absolutePath.quoted()}'" +
            if (arguments.isEmpty()) "" else " -ArgumentList $arguments"

        val proc = ProcessBuilder("powershell", "-NoProfile", "-Command", command)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)

        if (output.isNotBlank()) println("Elevated run: ${output.trim().takeLast(300)}")
        // A declined prompt makes Start-Process itself fail, so its output is the only signal.
        !output.contains("canceled", true) && !output.contains("cancelled", true)
    }.getOrElse {
        println("Could not start an elevated process: ${it.message}")
        false
    }

    /**
     * A packaged build starts through its own launcher, which already knows where its runtime and
     * jars are. Only an unpackaged one has to spell the JVM invocation out, and there the jars sit
     * in lib/ next to the scripts.
     */
    private fun relaunch(): Pair<String, List<String>> {
        AppPaths.launcher?.takeIf { AppPaths.packaged }?.let { return it.absolutePath to emptyList() }

        val home = File(System.getProperty("java.home"), "bin/javaw.exe")
        val javaw = if (home.isFile) home.absolutePath else "javaw"
        return javaw to listOf("-cp", "${AppPaths.appDir.absolutePath}\\lib\\*", "MainKt")
    }

    private fun String.quoted() = replace("'", "''")
}
