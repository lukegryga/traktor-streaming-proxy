package app

import java.io.File

/**
 * The one ffmpeg invocation the sources share: a single audio stream, cover art as an attached
 * picture, and metadata.
 *
 * Spotify hands it Ogg Vorbis, which Traktor refuses, so there the audio is re-encoded. YouTube's
 * m4a is already AAC in an mp4 container, so there it is copied and the run costs a rewrite of the
 * file rather than an encode.
 */
object Audio {

    /**
     * @param audio the codec arguments for the audio stream, the one thing that differs per source.
     * @param tags metadata keys in the order they should be written; blank values are left out.
     */
    fun write(source: File, cover: File?, audio: List<String>, tags: Map<String, String?>, target: File) {
        // Resolved rather than left to PATH, so a copy installed from the panel works without a
        // restart: this process inherited its PATH before that install ran.
        val command = mutableListOf(Ffmpeg.executable(), "-y", "-i", source.absolutePath)
        if (cover != null) command.addAll(listOf("-i", cover.absolutePath))

        command.addAll(listOf("-map", "0:a"))
        if (cover != null) {
            command.addAll(listOf("-map", "1:v", "-c:v", "mjpeg", "-disposition:v", "attached_pic"))
        }
        command.addAll(audio)
        tags.forEach { (key, value) -> if (!value.isNullOrBlank()) command.addAll(listOf("-metadata", "$key=$value")) }
        command.add(target.absolutePath)

        val proc = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().use { it.readText() }
        if (proc.waitFor() != 0) {
            target.delete()
            throw IllegalStateException("ffmpeg failed: ${output.takeLast(500)}")
        }
    }

    /**
     * Fetched to a file because ffmpeg takes a second input rather than a stream. Null on any
     * failure: art is worth having, never worth failing a load over.
     */
    fun cover(url: String): File? = runCatching {
        val file = File.createTempFile("cover-", ".jpg")
        java.net.URI(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        file
    }.onFailure { println("No cover art: ${it.message}") }.getOrNull()
}
