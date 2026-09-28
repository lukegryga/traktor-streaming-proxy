package app

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * librespot's own callback server reads the code as everything after the first '=' in the query.
 * Spotify now appends a 'ubi' parameter to the redirect, so that grabs "<code>&ubi=..." and the
 * token exchange fails with 400. Parsing the query properly is the only difference here.
 */
object OAuthCallback {

    fun await(port: Int, path: String, timeout: Long, unit: TimeUnit): String {
        val received = ArrayBlockingQueue<String>(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)

        server.createContext(path) { exchange ->
            val code = exchange.requestURI.query
                ?.split("&")
                ?.map { it.split("=", limit = 2) }
                ?.firstOrNull { it.size == 2 && it[0] == "code" }
                ?.let { URLDecoder.decode(it[1], "utf-8") }

            val body = if (code == null) "Spotify authorization failed." else "Spotify authorization complete, you can close this tab."
            exchange.sendResponseHeaders(if (code == null) 400 else 200, body.length.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
            code?.let { received.offer(it) }
        }

        server.start()
        try {
            return received.poll(timeout, unit)
                ?: throw IllegalStateException("Timed out waiting for the Spotify authorization callback")
        } finally {
            server.stop(0)
        }
    }
}
