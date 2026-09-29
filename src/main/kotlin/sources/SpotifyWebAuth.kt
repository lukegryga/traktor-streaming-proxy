package sources

import Config.prop
import app.Browser
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

private const val AUTHORIZE_URL = "https://accounts.spotify.com/authorize"
private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
private const val SCOPES = "user-library-read playlist-read-private playlist-read-collaborative user-follow-read"
private const val REDIRECT_PORT = 5589
private const val REDIRECT_URI = "http://127.0.0.1:$REDIRECT_PORT/callback"
private const val AUTH_TIMEOUT_MINUTES = 10L

/**
 * Spotify meters Web API quota per client id. librespot's built-in id is shared by every user of
 * that library, so tokens minted from it are rate limited no matter how little we ask for. This
 * authenticates against the user's own registered app to get a quota that is actually theirs.
 */
class SpotifyWebAuth {

    private val clientId: String = prop.getProperty("spotify.clientId")
        ?.takeIf { it.isNotBlank() && it != "YOUR-CLIENT-ID" }
        ?: throw IllegalStateException("spotify.clientId is missing from config.properties")

    // Beside credentials.json so the same volume keeps both logins across container rebuilds.
    private val refreshTokenFile = app.AppPaths.dataFile("data/spotify-refresh-token")

    private var accessToken: String? = null
    private var expiresAt: Long = 0

    fun token(): String {
        if (accessToken == null || System.currentTimeMillis() >= expiresAt) {
            val refreshToken = refreshTokenFile.takeIf { it.exists() }?.readText()?.trim()
            if (refreshToken.isNullOrEmpty()) {
                authorize()
            } else {
                try {
                    store(requestToken("grant_type=refresh_token&refresh_token=${enc(refreshToken)}&client_id=${enc(clientId)}"))
                } catch (ex: WebRequests.HttpException) {
                    // Refresh tokens survive restarts but not a revoked app authorization.
                    authorize()
                }
            }
        }
        return "Bearer $accessToken"
    }

    private fun authorize() {
        val verifier = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(ByteArray(64).also { SecureRandom().nextBytes(it) })
        val challenge = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

        val received = ArrayBlockingQueue<String>(1)
        val server = HttpServer.create(InetSocketAddress(REDIRECT_PORT), 0)
        server.createContext("/callback") { exchange ->
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
            Browser.open(
                "$AUTHORIZE_URL?response_type=code" +
                    "&client_id=${enc(clientId)}&redirect_uri=${enc(REDIRECT_URI)}" +
                    "&code_challenge_method=S256&code_challenge=$challenge&scope=${enc(SCOPES)}"
            )
            val code = received.poll(AUTH_TIMEOUT_MINUTES, TimeUnit.MINUTES)
                ?: throw IllegalStateException("Timed out waiting for the Spotify authorization callback")
            store(
                requestToken(
                    "grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(REDIRECT_URI)}" +
                        "&client_id=${enc(clientId)}&code_verifier=$verifier"
                )
            )
        } finally {
            server.stop(0)
        }
    }

    private fun store(response: JsonObject) {
        accessToken = response.getValue("access_token").jsonPrimitive.content
        expiresAt = System.currentTimeMillis() + (response.getValue("expires_in").jsonPrimitive.int - 60) * 1000L
        response["refresh_token"]?.jsonPrimitive?.content?.let {
            refreshTokenFile.parentFile?.mkdirs()
            refreshTokenFile.writeText(it)
        }
    }

    private fun requestToken(form: String): JsonObject {
        val con = WebRequests.createConnection(
            TOKEN_URL, "POST", mapOf("Content-Type" to "application/x-www-form-urlencoded")
        )
        val body = WebRequests.request(WebRequests.post(con, form.toByteArray())).value
        return Json.parseToJsonElement(body).jsonObject
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "utf-8")
}
