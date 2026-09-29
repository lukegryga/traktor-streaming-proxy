package app

import java.io.File

/**
 * Where the application lives and where it writes, resolved once instead of left to the working
 * directory.
 *
 * Every path used to be relative, which worked only because the launcher guaranteed a working
 * directory of the install folder. An installed build cannot promise that: a shortcut sets whatever
 * start-in folder it likes, and a program folder is read only anyway. So the two are separated -
 * [appDir] is where the code sits, [dataDir] is the only place anything is written.
 *
 * Portable mode keeps the old single-folder layout: a `portable.txt` beside the application, or a
 * `config.properties` already there, means the two are the same folder. The second rule is what
 * lets an existing install carry on untouched, since the shipped dist puts a config there.
 */
object AppPaths {

    private const val PORTABLE_MARKER = "portable.txt"

    /** The folder the application was installed into. Read only as far as this process cares. */
    val appDir: File by lazy { resolveAppDir() }

    /** Config, certificate, logs, credentials and the track library all hang off this. */
    val dataDir: File by lazy { resolveDataDir().also { runCatching { it.mkdirs() } } }

    val portable: Boolean get() = dataDir == appDir

    /**
     * What another process should run to start this application: the native launcher when there is
     * one, otherwise the script that starts the JVM without a console. Used by the startup entry
     * and by the elevated helper run, neither of which can assume a JDK on PATH.
     */
    val launcher: File? by lazy {
        jpackageLauncher() ?: File(appDir, "traktor-proxy.vbs").takeIf { it.isFile }
    }

    /** True when running from a jpackage image, which bundles its own runtime and launcher. */
    val packaged: Boolean get() = jpackageLauncher() != null

    fun data(relative: String): File = File(dataDir, relative)

    /** Creates the parent folder as a side effect, so callers can write straight to the result. */
    fun dataFile(relative: String): File = data(relative).also { runCatching { it.parentFile?.mkdirs() } }

    private fun jpackageLauncher(): File? {
        // Set by the jpackage launcher itself and pointing at the .exe that started this process.
        val declared = System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }
        return declared?.let { File(it) }?.takeIf { it.isFile }
    }

    private fun resolveAppDir(): File {
        jpackageLauncher()?.parentFile?.let { return it }

        // A jpackage image without the property still has its runtime one level under the app
        // folder, beside the `app` folder holding the jars.
        val runtimeParent = File(System.getProperty("java.home", "")).parentFile
        if (runtimeParent != null && File(runtimeParent, "app").isDirectory) return runtimeParent

        // An installDist layout puts the jars in lib/, so the folder above that is the install.
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

        // Config beside the application means portable, but only for an unpackaged build: that
        // rule exists so the folder layout that shipped before this change keeps working, and a
        // packaged build free to put its own defaults next to the launcher must not trip it.
        if (!packaged && File(appDir, "config.properties").isFile) return appDir

        val local = System.getenv("LOCALAPPDATA")
        if (!local.isNullOrBlank()) return File(local, "TraktorProxy").distinctFromApp()
        return File(System.getProperty("user.home"), ".traktor-streaming-proxy").distinctFromApp()
    }

    /**
     * A packaged build must never keep its data where it was installed: an uninstall removes that
     * folder, and an upgrade replaces it, either of which would take the settings, the credentials
     * and the whole downloaded library with it.
     *
     * The installer is what normally keeps the two apart, and it is a flag away from not doing so:
     * a per-user install defaults to %LOCALAPPDATA%\<name>, which is this exact path. Keeping the
     * rule here as well means a change to the packaging cannot quietly reintroduce that.
     */
    private fun File.distinctFromApp(): File =
        if (packaged && canonical() == appDir.canonical()) File(parentFile, "$name Data") else this

    private fun File.canonical(): File = runCatching { canonicalFile }.getOrDefault(absoluteFile)
}
