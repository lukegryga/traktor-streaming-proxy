package app

import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.apache.log4j.PatternLayout
import org.apache.log4j.WriterAppender
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.io.RandomAccessFile

object Logging {

    private val logPath: File by lazy { AppPaths.dataFile("logs/proxy.log") }
    private const val MAX_BYTES = 256L * 1024
    private const val KEEP_BYTES = 128L * 1024

    private lateinit var sink: CappedFile

    /**
     * The tray launcher runs under javaw, which has no console at all, so anything written to
     * stdout or stderr is lost unless it also reaches a file. Without this a failed login is
     * undiagnosable.
     *
     * log4j writes through the same stream rather than its own appender: two writers on one file
     * means whichever trims it leaves the other writing at a stale offset.
     */
    fun configure() {
        sink = CappedFile(logPath)

        val console = System.out
        val tee = PrintStream(Tee(console, sink), true)
        System.setOut(tee)
        System.setErr(PrintStream(Tee(System.err, sink), true))

        Logger.getRootLogger().apply {
            removeAllAppenders()
            level = Level.INFO
            addAppender(WriterAppender(PatternLayout("%d{HH:mm:ss} [%t] %-5p %c{1} - %m%n"), tee))
        }
    }

    fun logFile(): File = logPath.absoluteFile

    fun setLevel(name: String) {
        val level = Level.toLevel(name.uppercase(), Level.INFO)
        Logger.getRootLogger().level = level
        println("Log level set to $level")
    }

    fun tail(lines: Int): String {
        val file = logPath
        if (!file.isFile) return ""
        return runCatching {
            val kept = ArrayDeque<String>(lines)
            file.bufferedReader().useLines { sequence ->
                sequence.forEach {
                    if (kept.size == lines) kept.removeFirst()
                    kept.addLast(it)
                }
            }
            kept.joinToString("\n")
        }.getOrElse { "Could not read the log: ${it.message}" }
    }

    /**
     * Trims the oldest half once the file passes the cap rather than emptying it, so a crash that
     * happens right after a trim still leaves the lines that led up to it.
     */
    private class CappedFile(private val file: File) : OutputStream() {

        private var out = java.io.FileOutputStream(file, true)
        private var written = file.length()

        @Synchronized
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            written += len
            if (written > MAX_BYTES) trim()
        }

        @Synchronized
        override fun flush() = out.flush()

        private fun trim() {
            runCatching {
                out.flush()
                out.close()

                val tail = RandomAccessFile(file, "r").use { raf ->
                    val from = (raf.length() - KEEP_BYTES).coerceAtLeast(0)
                    raf.seek(from)
                    if (from > 0) raf.readLine()
                    ByteArray((raf.length() - raf.filePointer).toInt()).also { raf.readFully(it) }
                }

                java.io.FileOutputStream(file, false).use { it.write(tail) }
                out = java.io.FileOutputStream(file, true)
                written = file.length()
            }.onFailure {
                out = java.io.FileOutputStream(file, true)
                written = 0
            }
        }
    }

    private class Tee(private val console: OutputStream, private val file: OutputStream) : OutputStream() {
        override fun write(b: Int) {
            console.write(b)
            file.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            console.write(b, off, len)
            file.write(b, off, len)
        }

        override fun flush() {
            console.flush()
            file.flush()
        }
    }
}
