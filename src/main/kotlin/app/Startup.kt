package app

import java.io.File

/**
 * A .vbs dropped in the Startup folder rather than a .lnk or a Run registry entry: shortcuts need
 * COM to create, and the user can see and delete this one without a registry editor.
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

        val launcher = File(File(System.getProperty("user.dir")), "traktor-proxy.vbs").absolutePath
        file.parentFile?.mkdirs()
        file.writeText(
            """
            ' Created by Traktor Streaming Proxy. Delete this file to stop it starting at login.
            CreateObject("WScript.Shell").Run "${'"'}${'"'}${launcher}${'"'}${'"'}", 0, False
            """.trimIndent()
        )
    }
}
