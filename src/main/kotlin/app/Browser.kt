package app

import java.awt.Desktop
import java.net.URI

object Browser {

    /**
     * Falls back to printing because both auth flows block on a callback afterwards: failing to
     * launch a browser should leave the user a URL to open by hand, not strand the flow.
     */
    fun open(url: String) {
        val launched = runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
                true
            } else {
                false
            }
        }.getOrDefault(false)

        if (!launched) {
            println("Open this URL in your browser to continue: $url")
        }
    }
}
