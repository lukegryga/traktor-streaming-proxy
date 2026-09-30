package app

import java.io.File

/**
 * A .vbs dropped in the Startup folder rather than a .lnk or a Run registry entry: shortcuts need
 * COM to create, and the user can see and delete this one without a registry editor.
 *
 * The file keeps the old project slug for its name on purpose. Anyone upgrading already has an entry
 * under that name, pointing at wherever the app used to live, and matching it is what lets this
 * overwrite or remove it rather than leave a second one behind that starts nothing.
 */
object Startup {

    private val entry: File?
        get() = System.getenv("APPDATA")
            ?.let { File(it, "Microsoft/Windows/Start Menu/Programs/Startup/traktor-streaming-proxy.vbs") }

    fun isEnabled(): Boolean = entry?.exists() == true

    fun toggle() {
        val file = entry ?: return
        if (file.exists()) {
            file.delete()
            return
        }

        // Nothing to point at under Gradle, and an entry naming a launcher that is not there would
        // fail quietly at every login.
        val launcher = AppPaths.launcher?.absolutePath ?: return

        // A .vbs wrapper around the launcher, rather than the launcher itself: run with a window
        // style of 0 it starts hidden, so nothing flashes at login. The path is absolute because
        // the working directory at login is not this folder.
        file.parentFile?.mkdirs()
        file.writeText(
            """
            ' Created by Traktor Streaming Proxy. Delete this file to stop it starting at login.
            CreateObject("WScript.Shell").Run "${'"'}${'"'}${launcher}${'"'}${'"'}", 0, False
            """.trimIndent()
        )
    }
}
