import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The route that hands Traktor the audio. Worth pinning because Traktor asks for byte ranges and
 * reports anything it does not understand as a network error, which looks like a broken source
 * rather than a broken response.
 */
class OutputRoutesTest {

    private val audio = ByteArray(4096) { (it % 251).toByte() }

    private fun servingOne(block: suspend ApplicationTestBuilder.(id: Long) -> Unit) {
        val file = File.createTempFile("test-track-", ".mp4").apply { writeBytes(audio) }
        try {
            testApplication {
                application {
                    install(PartialContent)
                    routing { outputRoutes(mapOf(7L to file)) }
                }
                block(7L)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun servesTheWholeFileAsMp4() = servingOne { id ->
        val response = client.get("/output/$id.mp4")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Video.MP4, response.contentType()?.withoutParameters())
        assertContentEquals(audio, response.bodyAsBytes())
    }

    @Test
    fun answersARangeWithJustThatRange() = servingOne { id ->
        val response = client.get("/output/$id.mp4") { header(HttpHeaders.Range, "bytes=100-199") }
        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertEquals("bytes 100-199/4096", response.headers[HttpHeaders.ContentRange])
        assertContentEquals(audio.copyOfRange(100, 200), response.bodyAsBytes())
    }

    @Test
    fun advertisesRangeSupport() = servingOne { id ->
        val response = client.head("/output/$id.mp4")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
        assertEquals("4096", response.headers[HttpHeaders.ContentLength])
    }

    /** Traktor keeps its own collection, so it asks for tracks no download call put aside. */
    @Test
    fun unknownTrackIsNotFound() = servingOne {
        assertEquals(HttpStatusCode.NotFound, client.get("/output/99.mp4").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/output/not-a-number.mp4").status)
    }

    /** The library evicts on its size limit, which can take a file out from under a kept entry. */
    @Test
    fun evictedFileIsNotFound() {
        val file = File.createTempFile("test-track-", ".mp4").apply { writeBytes(audio) }
        testApplication {
            application {
                install(PartialContent)
                routing { outputRoutes(mapOf(7L to file)) }
            }
            assertTrue(file.delete())
            assertEquals(HttpStatusCode.NotFound, client.get("/output/7.mp4").status)
        }
    }

    private fun assertContentEquals(expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size, "body length")
        assertTrue(expected.contentEquals(actual), "body content")
    }
}
