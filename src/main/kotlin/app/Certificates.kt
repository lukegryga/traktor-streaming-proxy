package app

import io.ktor.network.tls.certificates.buildKeyStore
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.security.auth.x500.X500Principal

private const val HOST = "api.beatport.com"
private const val ALIAS = "foo"
private const val PASSWORD = "changeit"
// Ten years: this certificate is only ever trusted by the machine that generated it, so an expiry
// short enough to need rotating would be a chore with no one to protect.
private const val VALID_DAYS = 3650L

data class CertificateState(
    val present: Boolean,
    val expiresAt: Long?,
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
        val cert = load() ?: return CertificateState(false, null, false, "No certificate yet")

        val problem = when {
            !subjectNames(cert).contains(HOST) -> "Not valid for $HOST"
            cert.notAfter.time < System.currentTimeMillis() -> "Expired"
            !isTrusted(cert) -> "Not trusted by Windows"
            else -> null
        }

        return CertificateState(true, cert.notAfter.time, problem == null, problem)
    }

    /**
     * Provisions only when something is actually wrong, so a normal start touches nothing.
     *
     * Old certificates are cleared here rather than at generation time. The keystore is read once
     * when the server binds, so removing trust for what is currently being served breaks TLS until
     * a restart; at startup nothing is serving yet.
     */
    fun ensure(): Result<String> {
        val current = state()
        val outcome = if (current.healthy) {
            Result.success("Certificate is valid and trusted")
        } else {
            println("Certificate needs attention: ${current.problem}")
            regenerate()
        }

        load()?.let { served ->
            val removed = removeStale(fingerprint(served))
            if (removed > 0) println("Removed $removed superseded certificate(s)")
        }
        return outcome
    }

    fun regenerate(): Result<String> = runCatching {
        keystoreFile.parentFile?.mkdirs()
        val store = buildKeyStore {
            certificate(ALIAS) {
                password = PASSWORD
                domains = listOf(HOST)
                daysValid = VALID_DAYS
                keySizeInBits = 4096
                // Without this ktor stamps CN=localhost, OU=Kotlin, O=JetBrains, which is both
                // unidentifiable in certmgr and useless for finding our own old certificates.
                subject = X500Principal("CN=$HOST")
            }
        }
        keystoreFile.outputStream().use { store.store(it, PASSWORD.toCharArray()) }

        val cert = store.getCertificate(ALIAS) as X509Certificate
        crtFile.writeText(pem(cert))

        if (!subjectNames(cert).contains(HOST)) {
            throw IllegalStateException("Generated certificate has no $HOST subject alternative name")
        }

        if (!install(crtFile)) {
            throw IllegalStateException("Generated the certificate but could not add it to the trusted roots")
        }

        // The old certificate stays trusted until the next start, because it is the one still
        // being served; ensure() clears it once this one takes over.
        "New certificate trusted. Restart the app to start serving it."
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

    /**
     * Matched on subject alternative name as well as subject: a certificate is valid for a host
     * through its SAN, and ktor's default subject names neither the host nor this application.
     */
    private fun staleCertificates(keepFingerprint: String?): List<X509Certificate> =
        windowsRoots().filter {
            val claimsHost = subjectNames(it).contains(HOST) ||
                it.subjectX500Principal.name.contains(HOST, ignoreCase = true)
            claimsHost && fingerprint(it) != keepFingerprint
        }

    private fun removeStale(keepFingerprint: String?): Int {
        val before = staleCertificates(keepFingerprint).map { sha1(it) }.toSet()
        before.forEach { thumbprint ->
            // The user store needs no elevation; the machine store is attempted in case a
            // certificate was installed there by hand, and fails when not running elevated.
            certutil("-delstore", "-user", "Root", thumbprint)
            certutil("-delstore", "Root", thumbprint)
        }

        // Checked rather than inferred from the exit code: deleting from the user store reports
        // success for a certificate that only exists in the machine store, and leaves it there.
        val remaining = staleCertificates(keepFingerprint).map { sha1(it) }.toSet()
        remaining.forEach {
            println("Could not remove certificate $it; removing it from the machine store needs an elevated prompt")
        }
        return before.count { !remaining.contains(it) }
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
