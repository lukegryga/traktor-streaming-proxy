package app

import sources.ISource
import java.io.File

enum class State { IDLE, STARTING, READY, FAILED }

/** What a status means to a user, and the colour every part of the UI shows it in. */
enum class Health { OK, WARN, ERROR }

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

    /**
     * Spotify is the only source with an interactive login, and the credentials it stores are the
     * only evidence of one. Kept here rather than in each UI so the tray and the panel cannot
     * disagree about whether a source is signed in.
     */
    private val credentialFiles = mapOf("spotify" to File("data/credentials.json"))

    fun statuses(): List<SourceStatus> = synchronized(statuses) { statuses.values.toList() }

    fun requiresSignIn(name: String) = credentialFiles.containsKey(name)

    fun isSignedIn(name: String) = credentialFiles[name]?.isFile ?: true

    /** Ready but unauthenticated is a warning, not success: it serves nothing until the login is done. */
    fun health(status: SourceStatus) = when {
        status.state == State.FAILED -> Health.ERROR
        status.state == State.READY && isSignedIn(status.name) -> Health.OK
        else -> Health.WARN
    }

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
            // The outermost cause carrying a message, not the innermost: sources are constructed
            // reflectively, so the top is an empty InvocationTargetException, while the bottom is
            // whatever low level failure a source has already explained in its own terms.
            val cause = generateSequence(ex) { it.cause }
                .firstOrNull { it !is java.lang.reflect.InvocationTargetException && !it.message.isNullOrBlank() }
                ?: generateSequence(ex) { it.cause }.last()
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
