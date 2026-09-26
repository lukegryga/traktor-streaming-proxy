package app

import sources.ISource

enum class State { IDLE, STARTING, READY, FAILED }

data class SourceStatus(val name: String, val state: State, val detail: String? = null)

/**
 * Sources used to be constructed inline during startup, which meant an interactive login blocked
 * the HTTP server from ever binding. Traktor cannot link to a server that is not listening, so
 * initialisation happens on a worker thread and the server comes up regardless of auth state.
 */
object SourceManager {

    val sources: MutableList<ISource> = mutableListOf()

    private val statuses = linkedMapOf<String, SourceStatus>()
    private val registry = linkedMapOf<String, Class<out ISource>>()
    private val listeners = mutableListOf<() -> Unit>()

    @Volatile
    private var running = false

    fun onChange(listener: () -> Unit) = synchronized(listeners) { listeners.add(listener); Unit }

    fun statuses(): List<SourceStatus> = synchronized(statuses) { statuses.values.toList() }

    fun register(name: String, type: Class<out ISource>) {
        registry[name] = type
        synchronized(statuses) { statuses[name] = SourceStatus(name, State.IDLE) }
    }

    /** No-ops while a previous attempt is still running so repeated tray clicks cannot pile up. */
    fun startAll() {
        synchronized(this) {
            if (running) return
            running = true
        }
        Thread({
            try {
                registry.forEach { (name, type) -> start(name, type) }
            } finally {
                running = false
            }
        }, "source-init").apply { isDaemon = true }.start()
    }

    private fun start(name: String, type: Class<out ISource>) {
        if (synchronized(statuses) { statuses[name]?.state } in setOf(State.READY, State.STARTING)) return

        set(SourceStatus(name, State.STARTING))
        try {
            val source = type.getConstructor().newInstance()
            synchronized(sources) { sources.add(source) }
            println("$name is ready")
            set(SourceStatus(name, State.READY))
        } catch (ex: Throwable) {
            val cause = generateSequence(ex) { it.cause }.last()
            // Logged as well as shown in the tray: a tooltip cannot carry a stack trace, and an
            // interactive login has plenty of ways to fail that need one to diagnose.
            System.err.println("$name failed to initialise")
            cause.printStackTrace()
            set(SourceStatus(name, State.FAILED, cause.message ?: cause::class.java.simpleName))
        }
    }

    private fun set(status: SourceStatus) {
        synchronized(statuses) { statuses[status.name] = status }
        synchronized(listeners) { listeners.toList() }.forEach { runCatching { it() } }
    }
}
