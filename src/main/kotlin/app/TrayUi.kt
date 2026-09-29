package app

import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.JDialog
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.UIManager
import javax.swing.event.PopupMenuEvent
import javax.swing.event.PopupMenuListener
import kotlin.system.exitProcess

private val OK_COLOUR = Color(0x2E, 0xA0, 0x43)
private val WARN_COLOUR = Color(0xD2, 0x9A, 0x22)
private val ERROR_COLOUR = Color(0xC0, 0x39, 0x2B)
private val OFF_COLOUR = Color(0x6E, 0x77, 0x81)

private fun colour(health: Health?) = when (health) {
    Health.OK -> OK_COLOUR
    Health.WARN -> WARN_COLOUR
    Health.ERROR -> ERROR_COLOUR
    null -> OFF_COLOUR
}

/** The parts of the install without which Traktor cannot reach this server at all. */
private data class Setup(val missing: List<String>) {
    val complete get() = missing.isEmpty()
}

/**
 * Three actions and a status readout above them. Everything the tray used to offer beyond that
 * lives in the control panel, which is the only place worth keeping any of it correct.
 *
 * The menu is Swing rather than AWT's PopupMenu because each status line carries a coloured dot,
 * and a native menu item cannot hold one.
 */
object TrayUi {

    private var icon: TrayIcon? = null

    // Recomputed on a timer rather than per menu open: reading the Windows root store and the
    // hosts file is too slow to do on the event thread while a menu is being built.
    @Volatile
    private var setup = Setup(emptyList())

    fun install(): Boolean {
        if (!SystemTray.isSupported()) return false

        // Without the native look and feel the menu is Metal, which looks nothing like a tray menu.
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        setup = check()

        val tray = SystemTray.getSystemTray()
        val trayIcon = TrayIcon(render(null), "Traktor Streaming Proxy").apply {
            isImageAutoSize = true
        }

        return try {
            tray.add(trayIcon)
            icon = trayIcon
            // Either button opens the menu: no single action here is obvious enough to claim left click.
            trayIcon.addMouseListener(object : MouseAdapter() {
                override fun mouseReleased(event: MouseEvent) {
                    if (event.isPopupTrigger || event.button == MouseEvent.BUTTON1) {
                        show(event.xOnScreen, event.yOnScreen)
                    }
                }
            })
            SourceManager.onChange { refresh() }
            watchSetup()
            refresh()
            true
        } catch (ex: Exception) {
            false
        }
    }

    /** Only the icon and its tooltip: the menu is rebuilt from scratch every time it is opened. */
    private fun refresh() {
        val trayIcon = icon ?: return
        val rows = rows()

        trayIcon.image = render(rows.map { it.health }.worst())
        trayIcon.toolTip = buildString {
            append("Traktor Streaming Proxy")
            rows.forEach { append("\n${it.text}") }
            if (!setup.complete) append("\nSetup incomplete: ${setup.missing.joinToString(", ")}")
        }
    }

    private fun watchSetup() {
        Thread({
            while (true) {
                Thread.sleep(15_000)
                val current = check()
                if (current != setup) {
                    setup = current
                    refresh()
                }
            }
        }, "setup-watch").apply { isDaemon = true }.start()
    }

    private fun check(): Setup {
        val missing = mutableListOf<String>()
        if (!Certificates.state().healthy) missing.add("certificate")
        if (Hosts.state().status != HostsStatus.OK) missing.add("hosts file")
        if (TraktorPatch.state().status != PatchStatus.PATCHED) missing.add("Traktor patch")
        return Setup(missing)
    }

    private data class Row(val health: Health, val text: String)

    /**
     * Driven by the enabled list rather than by what started, so a source enabled in the panel but
     * not yet running is reported instead of being silently absent from the menu.
     */
    private fun rows(): List<Row> {
        val statuses = SourceManager.statuses().associateBy { it.name }
        return Settings.enabledSources.map { name ->
            val title = name.replaceFirstChar { it.uppercase() }
            val status = statuses[name]
                ?: return@map Row(Health.WARN, "$title - enabled, restart to start it")
            Row(SourceManager.health(status), "$title - ${label(status)}")
        }
    }

    private fun label(status: SourceStatus) = when (status.state) {
        State.IDLE -> "not started"
        State.STARTING -> "signing in..."
        State.READY ->
            if (SourceManager.requiresSignIn(status.name) && !SourceManager.isSignedIn(status.name)) {
                "working, not signed in"
            } else {
                "working"
            }
        // Truncated because a menu item as wide as an exception message is unusable.
        State.FAILED -> "failed: ${status.detail.orEmpty().ifBlank { "unknown error" }.take(60)}"
    }

    private fun List<Health>.worst() = when {
        isEmpty() -> null
        contains(Health.ERROR) -> Health.ERROR
        contains(Health.WARN) -> Health.WARN
        else -> Health.OK
    }

    private fun menu() = JPopupMenu().apply {
        val rows = rows()
        if (rows.isEmpty()) {
            add(status(null, "No providers enabled"))
        } else {
            rows.forEach { add(status(it.health, it.text)) }
        }
        if (!setup.complete) {
            // The one status line that is clickable, because it is the only one with somewhere to
            // go: the panel's Settings tab holds all three fixes.
            add(JMenuItem("Setup incomplete: ${setup.missing.joinToString(", ")}").apply {
                icon = Dot(WARN_COLOUR)
                toolTipText = "Open the control panel to finish setting up"
                addActionListener { panel("#settings") }
            })
        }
        addSeparator()

        add(JMenuItem("Control Panel").apply { addActionListener { panel("") } })
        add(JMenuItem("Music Folder").apply { addActionListener { openLibrary() } })
        addSeparator()
        add(JMenuItem("Quit").apply { addActionListener { exitProcess(0) } })
    }

    private fun status(health: Health?, text: String) = JMenuItem(text).apply {
        isEnabled = false
        icon = Dot(colour(health))
        // Swing drops the icon of a disabled item unless it is given one of its own, and a status
        // line without its dot is the whole point gone.
        disabledIcon = icon
    }

    private fun panel(fragment: String) = Browser.open("http://127.0.0.1:${Settings.uiPort}/$fragment")

    private fun openLibrary() {
        val folder = Library.root().absoluteFile
        // Created first: nothing has been downloaded on a fresh install, so the folder the menu
        // promises to open does not exist yet.
        runCatching {
            folder.mkdirs()
            Desktop.getDesktop().open(folder)
        }.onFailure { System.err.println("Could not open ${folder.absolutePath}: ${it.message}") }
    }

    // A tray icon cannot own a Swing popup, and an unowned popup never learns that focus moved
    // elsewhere, so it stays on screen until something is picked. This 1x1 window is that owner.
    private val host by lazy {
        JDialog().apply {
            isUndecorated = true
            isAlwaysOnTop = true
            setSize(1, 1)
        }
    }

    // Guarded because this is the only way into the menu: a throw here happens on the event thread,
    // where it would be swallowed and leave the tray icon looking dead.
    private fun show(x: Int, y: Int) = runCatching { open(x, y) }
        .onFailure { System.err.println("Could not open the tray menu: ${it.message}") }

    private fun open(x: Int, y: Int) {
        val popup = menu()
        popup.addPopupMenuListener(object : PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: PopupMenuEvent) = Unit
            override fun popupMenuWillBecomeInvisible(event: PopupMenuEvent) { host.isVisible = false }
            override fun popupMenuCanceled(event: PopupMenuEvent) { host.isVisible = false }
        })

        // Placed by hand rather than left to Swing: the tray sits at the bottom right of the
        // screen, so a menu drawn down and to the right of the cursor is mostly off it.
        val size = popup.preferredSize
        // maximumWindowBounds excludes the taskbar, which is exactly the edge to stay clear of.
        val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val left = (x - size.width / 2)
            .coerceIn(screen.x, (screen.x + screen.width - size.width).coerceAtLeast(screen.x))
        val top = if (y + size.height > screen.y + screen.height) y - size.height else y

        host.setLocation(left, top)
        host.isVisible = true
        host.toFront()
        popup.show(host, 0, 0)
    }

    private class Dot(private val fill: Color) : Icon {
        private val size = 10

        override fun getIconWidth() = size + 4
        override fun getIconHeight() = size

        override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
            (graphics.create() as Graphics2D).apply {
                setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                paint = fill
                fillOval(x, y, size, size)
                dispose()
            }
        }
    }

    private fun render(health: Health?): BufferedImage {
        val size = SystemTray.getSystemTray().trayIconSize
        val width = size.width.coerceAtLeast(16)
        val height = size.height.coerceAtLeast(16)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

        // Setup counts towards the icon as well as the menu: a green icon on an install Traktor
        // cannot reach would be the one thing the user checks, and it would say nothing is wrong.
        val overall = if (!setup.complete && health != Health.ERROR) Health.WARN else health

        (image.graphics as Graphics2D).apply {
            setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            color = colour(overall)
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
