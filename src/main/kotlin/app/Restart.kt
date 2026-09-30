package app

import kotlin.system.exitProcess

/**
 * Restarts the application in place, used after a change that only takes effect at startup.
 *
 * The new process cannot simply be started before this one leaves: both would race for port 443,
 * and SingleInstance holds a lock that is only released when this process dies. So a detached
 * watcher is started first, and it waits for this pid to disappear before launching the app again.
 */
object Restart {

    /** False when there is nothing to relaunch, which is the case when running from Gradle. */
    fun isAvailable(): Boolean = AppPaths.launcher != null

    /**
     * Hands back immediately so an HTTP response can still be written. The caller decides how long
     * to wait before the process goes; too short and the browser sees a dropped connection rather
     * than the reply telling it a restart is coming.
     */
    fun schedule(afterMillis: Long = 700): Boolean {
        val launcher = AppPaths.launcher ?: return false

        Thread({
            Thread.sleep(afterMillis)
            if (spawnWatcher(launcher.absolutePath)) {
                println("Restarting")
                exitProcess(0)
            }
        }, "restart").apply { isDaemon = false }.start()

        return true
    }

    private fun spawnWatcher(launcher: String): Boolean = runCatching {
        val pid = ProcessHandle.current().pid()
        // -Timeout rather than an unbounded wait: a watcher that outlives a shutdown which never
        // finishes would start a second copy on top of a live one.
        val command = "Wait-Process -Id $pid -Timeout 60 -ErrorAction SilentlyContinue; " +
            // Hidden because the launcher is a .cmd: without it the restart pops up a console
            // window, which closes again the moment the launcher has handed over to javaw.
            "Start-Process -FilePath '${launcher.quoted()}' -WindowStyle Hidden " +
            "-WorkingDirectory '${AppPaths.appDir.absolutePath.quoted()}'"

        ProcessBuilder("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", command).start()
        true
    }.getOrElse {
        println("Could not arrange a restart: ${it.message}")
        false
    }

    private fun String.quoted() = replace("'", "''")
}
