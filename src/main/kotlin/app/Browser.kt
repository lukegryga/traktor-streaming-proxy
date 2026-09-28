package app

import java.awt.Desktop
import java.net.URI

object Browser {

    /**
     * Kept so the tray can re-open a login page. Starting a fresh flow instead would mint a new
     * PKCE verifier and silently invalidate the tab the user already has open.
     */
    @Volatile
    var pendingUrl: String? = null
        private set

    /**
     * Falls back to printing because both auth flows block on a callback afterwards: failing to
     * launch a browser should leave the user a URL to open by hand, not strand the flow.
     */
    fun open(url: String) {
        pendingUrl = url
        val launched = runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                true
            } else {
                false
            }
        }.getOrDefault(false)

        // Logged even on success: the tab can be closed or land in the wrong browser, and the
        // URL is the only way back into a flow that is already waiting on its callback.
        println(if (launched) "Opened browser for: $url" else "Open this URL in your browser to continue: $url")
    }
}
