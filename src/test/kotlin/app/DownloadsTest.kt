package app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Two decks loading the same track used to stream and convert it twice, into the same library
 * file, each having deleted the other's. These pin the two halves of the fix: one at a time per
 * track, and no waiting between different tracks.
 */
class DownloadsTest {

    @Test
    fun oneCallerAtATimePerTrack() {
        val inside = AtomicInteger()
        val peak = AtomicInteger()
        val start = CountDownLatch(1)

        val threads = (1..4).map {
            Thread {
                start.await()
                Downloads.serialised("spotify:same") {
                    val now = inside.incrementAndGet()
                    peak.updateAndGet { max(it, now) }
                    Thread.sleep(50)
                    inside.decrementAndGet()
                }
            }.apply { start() }
        }

        start.countDown()
        threads.forEach { it.join() }
        assertEquals(1, peak.get(), "more than one caller was inside the block for one track")
    }

    @Test
    fun differentTracksDoNotWaitForEachOther() {
        // Each holds its key until the other has it too, so a lock shared across keys could not
        // let both through and the await would time out.
        val both = CountDownLatch(2)
        val through = AtomicInteger()

        val threads = listOf("spotify:a", "youtube:b").map { key ->
            Thread {
                Downloads.serialised(key) {
                    both.countDown()
                    if (both.await(5, TimeUnit.SECONDS)) through.incrementAndGet()
                }
            }.apply { start() }
        }

        threads.forEach { it.join() }
        assertEquals(2, through.get(), "one track's download blocked another's")
    }

    /** Browsing a collection touches every track in it, so the keys cannot be kept. */
    @Test
    fun keysAreNotKept() {
        val before = Downloads.tracked()
        repeat(100) { index -> Downloads.serialised("spotify:track$index") { } }
        assertEquals(before, Downloads.tracked())
    }
}
