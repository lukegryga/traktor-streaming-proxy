package app

import Config
import Config.prop
import java.io.File

/**
 * Every setting the control panel can change, in one place. Values live in config.properties as
 * before, so an existing install keeps working, but nothing outside here reads those keys.
 *
 * beatport.license is absent on purpose: this build is Windows only.
 */
object Settings {

    const val DEFAULT_LIBRARY_LIMIT_BYTES = 10L * 1024 * 1024 * 1024

    var serverPort: Int
        get() = prop.getProperty("server.port", "443").toIntOrNull() ?: 443
        set(value) = set("server.port", value.toString())


    var enabledSources: List<String>
        get() = prop.getProperty("sources.enabled", "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        set(value) = set("sources.enabled", value.joinToString(","))


    var spotifyClientId: String
        get() = prop.getProperty("spotify.clientId", "")
        set(value) = set("spotify.clientId", value.trim())

    var tidalClientId: String
        get() = prop.getProperty("tidal.clientId", "")
        set(value) = set("tidal.clientId", value.trim())

    var tidalClientSecret: String
        get() = prop.getProperty("tidal.clientSecret", "")
        set(value) = set("tidal.clientSecret", value.trim())

    var traktorPath: String
        get() = prop.getProperty("traktor.path", "")
        set(value) = set("traktor.path", value.trim())

    val beatportAccountId: String
        get() = prop.getProperty("beatport.accountId", "")

    /**
     * Anything relative resolves against the data folder rather than the working directory, which
     * is what the unset default used to rely on. The setter only ever writes absolute paths, so a
     * relative value here means an install that predates that.
     */
    var libraryPath: File
        get() {
            val configured = prop.getProperty("library.path", "")
            if (configured.isBlank()) return AppPaths.data("library")
            return File(configured).takeIf { it.isAbsolute } ?: File(AppPaths.dataDir, configured)
        }
        set(value) = set("library.path", value.absolutePath)

    var libraryLimitEnabled: Boolean
        get() = prop.getProperty("library.limitEnabled", "true").toBoolean()
        set(value) = set("library.limitEnabled", value.toString())

    var libraryLimitBytes: Long
        get() = prop.getProperty("library.limitBytes", DEFAULT_LIBRARY_LIMIT_BYTES.toString()).toLongOrNull()
            ?: DEFAULT_LIBRARY_LIMIT_BYTES
        set(value) = set("library.limitBytes", value.coerceAtLeast(0).toString())

    /** Only the two levels librespot's stream offers; a free account cannot use either reliably. */
    var veryHighQuality: Boolean
        get() = prop.getProperty("audio.veryHigh", "true").toBoolean()
        set(value) = set("audio.veryHigh", value.toString())

    var logLevel: String
        get() = prop.getProperty("log.level", "INFO").uppercase()
        set(value) = set("log.level", value.uppercase())

    var uiPort: Int
        get() = prop.getProperty("ui.port", "8088").toIntOrNull() ?: 8088
        set(value) = set("ui.port", value.toString())

    private fun set(key: String, value: String) {
        prop.setProperty(key, value)
        // Written immediately: the panel edits settings the user expects to survive a crash, not
        // only a clean shutdown.
        Config.saveConfig()
    }
}
