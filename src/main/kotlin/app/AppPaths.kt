package app

import java.io.File

/**
 * Where the application lives and where it writes, resolved once instead of left to the working
 * directory.
 *
 * Every path used to be relative, which worked only when the launcher guaranteed a working
 * directory of the app folder. A shortcut does not: it sets whatever start-in folder it likes. So
 * the two are separated - [appDir] is where the code sits, [dataDir] is the only place anything is
 * written.
 *
 * The shipped layout is portable, one folder holding both: a `portable.txt` beside the application,
 * or a `config.properties` already there, makes [dataDir] the same folder as [appDir]. The second
 * rule is what lets an install predating the marker file carry on untouched. Everything else - a
 * Gradle `run`, or an unzip with the marker deleted - writes under %LOCALAPPDATA%.
 */
object AppPaths {

    private const val PORTABLE_MARKER = "portable.txt"

    private const val LAUNCHER = "TraktorProxy.cmd"

    /** The folder the application was installed into. Read only as far as this process cares. */
    val appDir: File by lazy { resolveAppDir() }

    /** Config, certificate, logs, credentials and the track library all hang off this. */
    val dataDir: File by lazy { resolveDataDir().also { runCatching { it.mkdirs() } } }

    /**
     * What another process should run to start this application: the launcher script, which finds
     * the installed Java itself. Null when there is none to find, which is the case under Gradle.
     */
    val launcher: File? by lazy { File(appDir, LAUNCHER).takeIf { it.isFile } }

    fun data(relative: String): File = File(dataDir, relative)

    /** Creates the parent folder as a side effect, so callers can write straight to the result. */
    fun dataFile(relative: String): File = data(relative).also { runCatching { it.parentFile?.mkdirs() } }

    private fun resolveAppDir(): File {
        // The shipped layout puts the jars in lib/, so the folder above that is the app folder.
        val jar = runCatching {
            File(AppPaths::class.java.protectionDomain.codeSource.location.toURI())
        }.getOrNull()
        if (jar != null && jar.isFile && jar.parentFile?.name == "lib") {
            jar.parentFile.parentFile?.let { return it }
        }

        return File(System.getProperty("user.dir"))
    }

    private fun resolveDataDir(): File {
        // An escape hatch for running two copies, and what the tests would use.
        System.getProperty("traktorproxy.dataDir")?.takeIf { it.isNotBlank() }?.let { return File(it) }

        if (File(appDir, PORTABLE_MARKER).exists()) return appDir

        // A config already beside the application means portable too, so a folder set up before the
        // marker file existed keeps its settings where they are.
        if (File(appDir, "config.properties").isFile) return appDir

        val local = System.getenv("LOCALAPPDATA")
        if (!local.isNullOrBlank()) return File(local, "TraktorProxy")
        return File(System.getProperty("user.home"), ".traktor-streaming-proxy")
    }
}
