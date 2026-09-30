package app

import java.util.concurrent.ConcurrentHashMap

/**
 * Serialises work on one track so that two decks asking for it at once do it once.
 *
 * Traktor loads decks independently, so the same track can be requested twice within a second.
 * Both requests missed the library, both streamed the track, and both ran their conversion into
 * the same library file - which [Library.prepare] had already deleted out from under the other.
 * The second caller now waits for the first and reads the finished file instead.
 *
 * Keys are reference counted rather than left behind. Browsing a large collection touches every
 * track in it, and a plain map would keep an entry per track until the process ended.
 */
object Downloads {

    private class Guard {
        /** Guarded by the [guards] map, which only ever touches this inside a compute. */
        var waiting = 1
    }

    private val guards = ConcurrentHashMap<String, Guard>()

    fun <T> serialised(key: String, block: () -> T): T {
        val guard = guards.compute(key) { _, existing -> existing?.also { it.waiting++ } ?: Guard() }!!
        try {
            return synchronized(guard) { block() }
        } finally {
            guards.compute(key) { _, existing ->
                if (existing == null || --existing.waiting <= 0) null else existing
            }
        }
    }

    /** Held only for the tests; a caller that has to ask this is racing something. */
    internal fun tracked(): Int = guards.size
}
