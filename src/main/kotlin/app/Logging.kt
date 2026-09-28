package app

import org.apache.log4j.FileAppender
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.apache.log4j.PatternLayout
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream

object Logging {

    private const val LOG_PATH = "logs/proxy.log"

    /**
     * The tray launcher runs under javaw, which has no console at all, so anything written to
     * stdout or stderr is lost unless it also reaches a file. Without this a failed login is
     * undiagnosable.
     */
    fun configure() {
        File(LOG_PATH).parentFile?.mkdirs()

        Logger.getRootLogger().apply {
            level = Level.INFO
            addAppender(FileAppender(PatternLayout("%d{HH:mm:ss} [%t] %-5p %c{1} - %m%n"), LOG_PATH, true))
        }

        val sink = FileOutputStream(LOG_PATH, true)
        System.setOut(PrintStream(Tee(System.out, sink), true))
        System.setErr(PrintStream(Tee(System.err, sink), true))
    }

    fun logFile(): File = File(LOG_PATH).absoluteFile

    fun setLevel(name: String) {
        val level = Level.toLevel(name.uppercase(), Level.INFO)
        Logger.getRootLogger().level = level
        println("Log level set to ${level.toString()}")
    }

    /** Read from the end so a log that has grown to megabytes does not have to be held in memory. */
    fun tail(lines: Int): String {
        val file = File(LOG_PATH)
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
