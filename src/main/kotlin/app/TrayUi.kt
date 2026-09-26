package app

import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.io.File
import kotlin.system.exitProcess

object TrayUi {

    private var icon: TrayIcon? = null

    fun install(): Boolean {
        if (!SystemTray.isSupported()) return false

        val tray = SystemTray.getSystemTray()
        val trayIcon = TrayIcon(render(State.IDLE), "Traktor Streaming Proxy").apply {
            isImageAutoSize = true
        }

        return try {
            tray.add(trayIcon)
            icon = trayIcon
            SourceManager.onChange { refresh() }
            refresh()
            true
        } catch (ex: Exception) {
            false
        }
    }

    private fun refresh() {
        val trayIcon = icon ?: return
        val statuses = SourceManager.statuses()
        val overall = when {
            statuses.isEmpty() -> State.IDLE
            statuses.any { it.state == State.STARTING } -> State.STARTING
            statuses.all { it.state == State.READY } -> State.READY
            statuses.any { it.state == State.FAILED } -> State.FAILED
            else -> State.IDLE
        }

        trayIcon.image = render(overall)
        trayIcon.toolTip = buildString {
            append("Traktor Streaming Proxy")
            statuses.forEach { append("\n${it.name}: ${label(it)}") }
        }
        trayIcon.popupMenu = menu(statuses)
    }

    private fun label(status: SourceStatus) = when (status.state) {
        State.IDLE -> "not started"
        State.STARTING -> "signing in..."
        State.READY -> "ready"
        State.FAILED -> "failed - ${status.detail}"
    }

    private fun menu(statuses: List<SourceStatus>) = PopupMenu().apply {
        statuses.forEach { add(MenuItem("${it.name}: ${label(it)}").apply { isEnabled = false }) }
        addSeparator()

        // Also retries a failed source, since the usual reason for both is a login not yet done.
        add(MenuItem("Sign in / retry sources").apply {
            addActionListener { SourceManager.startAll() }
        })
        add(MenuItem(if (Startup.isEnabled()) "Disable start with Windows" else "Start with Windows").apply {
            addActionListener {
                Startup.toggle()
                refresh()
            }
        })
        add(MenuItem("Open app folder").apply {
            addActionListener { runCatching { java.awt.Desktop.getDesktop().open(File(".").absoluteFile) } }
        })
        addSeparator()
        add(MenuItem("Quit").apply { addActionListener { exitProcess(0) } })
    }

    private fun render(state: State): BufferedImage {
        val size = SystemTray.getSystemTray().trayIconSize
        val width = size.width.coerceAtLeast(16)
        val height = size.height.coerceAtLeast(16)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

        (image.graphics as Graphics2D).apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            color = when (state) {
                State.READY -> Color(0x2E, 0xA0, 0x43)
                State.STARTING -> Color(0xD2, 0x9A, 0x22)
                State.FAILED -> Color(0xC0, 0x39, 0x2B)
                State.IDLE -> Color(0x6E, 0x77, 0x81)
            }
            fillOval(0, 0, width - 1, height - 1)
            color = Color.WHITE
            font = Font(Font.SANS_SERIF, Font.BOLD, (height * 0.62).toInt().coerceAtLeast(8))
            val metrics = fontMetrics
            drawString("T", (width - metrics.stringWidth("T")) / 2, (height - metrics.height) / 2 + metrics.ascent)
            dispose()
        }
        return image
    }
}
