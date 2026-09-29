package app

import io.ktor.network.tls.certificates.buildKeyStore
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val HOST = "api.beatport.com"
private const val ALIAS = "foo"
private const val PASSWORD = "changeit"
private const val VALID_DAYS = 3650L
private const val RENEW_WITHIN_DAYS = 30L

data class CertificateState(
    val present: Boolean,
    val subject: String?,
    val expiresAt: Long?,
    val daysRemaining: Long?,
    val hostMatches: Boolean,
    val trusted: Boolean,
    val staleCount: Int,
    val healthy: Boolean,
    val problem: String?
)

/**
 * Traktor reaches api.beatport.com over TLS and validates through Schannel, which builds its chain
 * from the Windows certificate stores. Nothing can be served to it without a certificate for that
 * name sitting in a trusted root store, so the app provisions one itself rather than leaving the
 * user to run openssl and keytool by hand.
 */
object Certificates {

    private val keystoreFile = File("cert/keystore.jks")
    private val crtFile = File("cert/server.crt")

    fun state(): CertificateState {
        val cert = load()
            ?: return CertificateState(
                false, null, null, null, false, false, staleCertificates(null).size, false,
                "No certificate yet"
            )

        val remaining = TimeUnit.MILLISECONDS.toDays(cert.notAfter.time - System.currentTimeMillis())
        val hostMatches = subjectNames(cert).contains(HOST)
        val trusted = isTrusted(cert)
        val stale = staleCertificates(fingerprint(cert)).size

        val problem = when {
            !hostMatches -> "Certificate is not valid for $HOST"
            remaining < 0 -> "Certificate expired"
            !trusted -> "Not installed as a trusted root"
            remaining < RENEW_WITHIN_DAYS -> "Expires in $remaining days"
            else -> null
        }

        return CertificateState(
            present = true,
            subject = cert.subjectX500Principal.name,
            expiresAt = cert.notAfter.time,
            daysRemaining = remaining,
            hostMatches = hostMatches,
            trusted = trusted,
            staleCount = stale,
            healthy = problem == null,
            problem = problem
        )
    }

    /** Provisions only when something is actually wrong, so a normal start touches nothing. */
    fun ensure(): Result<String> {
        val current = state()
        if (current.healthy) return Result.success("Certificate is valid and trusted")
        println("Certificate needs attention: ${current.problem}")
        return regenerate()
    }

    fun regenerate(): Result<String> = runCatching {
        keystoreFile.parentFile?.mkdirs()
        val store = buildKeyStore {
            certificate(ALIAS) {
                password = PASSWORD
                domains = listOf(HOST)
                daysValid = VALID_DAYS
                keySizeInBits = 4096
            }
        }
        keystoreFile.outputStream().use { store.store(it, PASSWORD.toCharArray()) }

        val cert = store.getCertificate(ALIAS) as X509Certificate
        crtFile.writeText(pem(cert))

        if (!subjectNames(cert).contains(HOST)) {
            throw IllegalStateException("Generated certificate has no $HOST subject alternative name")
        }

        val removed = removeStale(fingerprint(cert))
        val installed = install(crtFile)
        if (!installed) {
            throw IllegalStateException("Generated the certificate but could not add it to the trusted roots")
        }

        val suffix = if (removed > 0) ", removed $removed stale" else ""
        "Generated and trusted a new certificate$suffix. Restart the app to serve it."
    }

    fun removeStaleOnly(): Result<Int> = runCatching {
        val current = load()?.let { fingerprint(it) }
        removeStale(current)
    }

    private fun load(): X509Certificate? = runCatching {
        if (!keystoreFile.isFile) return null
        val store = KeyStore.getInstance("JKS")
        keystoreFile.inputStream().use { store.load(it, PASSWORD.toCharArray()) }
        store.getCertificate(ALIAS) as? X509Certificate
    }.getOrNull()

    /**
     * Read through Java's view of the Windows root stores, which merges the machine and user ones -
     * the same set Schannel builds chains from.
     */
    private fun windowsRoots(): List<X509Certificate> = runCatching {
        val store = KeyStore.getInstance("Windows-ROOT")
        store.load(null, null)
        store.aliases().toList().mapNotNull { store.getCertificate(it) as? X509Certificate }
    }.getOrDefault(emptyList())

    private fun isTrusted(cert: X509Certificate): Boolean {
        val target = fingerprint(cert)
        return windowsRoots().any { fingerprint(it) == target }
    }

    /** Anything claiming this host that is not the certificate currently being served. */
    private fun staleCertificates(keepFingerprint: String?): List<X509Certificate> =
        windowsRoots().filter {
            it.subjectX500Principal.name.contains(HOST, ignoreCase = true) &&
                fingerprint(it) != keepFingerprint
        }

    private fun removeStale(keepFingerprint: String?): Int {
        val targets = staleCertificates(keepFingerprint).map { sha1(it) }.toSet()
        var removed = 0
        targets.forEach { thumbprint ->
            // The user store needs no elevation; the machine store is attempted in case an earlier
            // certificate was installed there by hand, and simply fails when not running elevated.
            val userStore = certutil("-delstore", "-user", "Root", thumbprint)
            val machineStore = certutil("-delstore", "Root", thumbprint)
            if (userStore || machineStore) {
                removed++
                println("Removed stale certificate $thumbprint")
            } else {
                println("Could not remove stale certificate $thumbprint; it may need an elevated prompt")
            }
        }
        return removed
    }

    private fun install(file: File): Boolean {
        if (certutil("-addstore", "-f", "-user", "Root", file.absolutePath)) return true
        return certutil("-addstore", "-f", "Root", file.absolutePath)
    }

    private fun certutil(vararg args: String): Boolean = runCatching {
        val proc = ProcessBuilder(listOf("certutil") + args).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        val ok = proc.waitFor(30, TimeUnit.SECONDS) && proc.exitValue() == 0
        if (!ok) println("certutil ${args.joinToString(" ")} failed: ${output.trim().takeLast(200)}")
        ok
    }.getOrElse {
        println("certutil could not be run: ${it.message}")
        false
    }

    private fun subjectNames(cert: X509Certificate): List<String> = runCatching {
        cert.subjectAlternativeNames?.mapNotNull { it.getOrNull(1) as? String } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    private fun sha1(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-1").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    private fun pem(cert: X509Certificate): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded)
        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
    }
}
