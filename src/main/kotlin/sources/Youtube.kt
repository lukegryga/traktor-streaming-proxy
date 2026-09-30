package sources

import app.Audio
import app.Ffmpeg
import app.Library
import beatport.api.*
import org.schabi.newpipe.extractor.*
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.VIDEOS
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.net.URL
import java.util.*

private const val SEARCH_RESULTS = 10

class Youtube : ISource {

    private val next = HashMap<String, Page?>()

    override val name: String
        get() = "YouTube"

    init {
        NewPipe.init(Downloader(), Localization.DEFAULT)
    }

    override fun getGenre(): List<Track> {
        return getTrending()
    }

    override fun getCuratedPlaylists(reset: Boolean): List<Playlist> {
        return emptyList()
    }

    override fun getCuratedPlaylist(id: String): List<Track> {
        return emptyList()
    }

    override fun getPlaylists(): List<Playlist> {
        return emptyList()
    }

    override fun getPlaylist(id: String): List<Track> {
        return emptyList()
    }

    override fun getTop100(): List<Track> {
        return emptyList()
    }

    /**
     * Videos rather than music songs. The song catalogue only holds what labels distributed, so
     * edits, bootlegs and mashups - the reason to reach for YouTube rather than Spotify - are
     * absent from it, and Spotify already covers what it does hold.
     *
     * Asking for both filters at once does not work: NewPipe runs a music search and ignores
     * VIDEOS entirely, which reads as the change having had no effect.
     */
    override fun query(query: String, reset: Boolean): List<Track> {
        val extractor = ServiceList.YouTube.getSearchExtractor(query, listOf(VIDEOS), "")
        val itemsPage = if (!reset && next.containsKey(query)) {
            if (next[query] == null)
                return emptyList()
            extractor.getPage(next[query])
        } else {
            extractor.fetchPage()
            extractor.initialPage
        }
        next[query] = itemsPage.nextPage
        return extractItems(itemsPage.items).take(SEARCH_RESULTS)
    }

    /**
     * Kept in the library like every other source. The m4a YouTube serves is already AAC in an mp4
     * container, so where Spotify re-encodes, this only ever remuxes - and it does that solely to
     * attach the title, channel and thumbnail, which the raw download carries none of.
     *
     * Without ffmpeg the bytes are stored exactly as they arrived. YouTube is the source that needs
     * no account and no setup at all, and tags are not worth giving that up for.
     */
    override fun fetch(id: String): File {
        // The page is fetched once for the stream url, the title and the thumbnail, so the library
        // file is named after the video rather than after its id.
        val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$id")
        extractor.fetchPage()
        val stream = extractor.audioStreams
            .filter { it.format!!.name == "m4a" }
            .maxBy { it.averageBitrate }

        val key = libraryKey(id)
        val title = extractor.name
        val uploader = runCatching { extractor.uploaderName }.getOrNull()
        val bytes = downloadTrack(stream.content)

        if (!Ffmpeg.isAvailable()) return Library.store(key, title, uploader, bytes)

        val downloaded = File.createTempFile("track-", ".m4a").apply { writeBytes(bytes) }
        val cover = thumbnail(extractor)
        val target = Library.prepare(key, title, uploader)
        return try {
            Audio.write(downloaded, cover, listOf("-c:a", "copy"), tags(extractor, id), target)
            Library.register(key, target.name)
            Library.enforceLimit()
            val (count, total) = Library.stats()
            println("Stored ${target.name}; library holds $count tracks, ${total / 1024 / 1024} MB")
            target
        } catch (ex: Exception) {
            // The audio is already downloaded and plays the same untagged, so a failed remux costs
            // the tags rather than the track.
            println("Could not tag $id (${ex.message}); keeping it as downloaded")
            Library.store(key, title, uploader, bytes)
        } finally {
            downloaded.delete()
            cover?.delete()
        }
    }

    /**
     * The widest thumbnail, preferring a jpg: YouTube serves webp as well, which ffmpeg only
     * decodes when it was built with libwebp.
     */
    private fun thumbnail(extractor: StreamExtractor): File? {
        val images = runCatching { extractor.thumbnails }.getOrNull().orEmpty()
        val best = images.filter { it.url.contains(".jpg") }.maxByOrNull { it.width }
            ?: images.maxByOrNull { it.width }
        return best?.url?.let { Audio.cover(it) }
    }

    private fun tags(extractor: StreamExtractor, id: String): Map<String, String?> = buildMap {
        put("title", runCatching { extractor.name }.getOrNull())
        // The channel is the nearest thing YouTube has to an artist, and it is already what the
        // library file is named after.
        put("artist", runCatching { extractor.uploaderName }.getOrNull())
        put("date", runCatching { extractor.uploadDate?.offsetDateTime()?.toLocalDate()?.toString() }.getOrNull())
        put("comment", "https://www.youtube.com/watch?v=$id")
    }

    private fun downloadTrack(path: String): ByteArray {
        val con = URL(path).openConnection()
        con.setRequestProperty("range", "bytes=0-")
        return con.inputStream.readBytes()
    }

    private fun getTrending(): List<Track> {
        val extractor = ServiceList.YouTube.kioskList.getExtractorById("trending_music", null)
        extractor.fetchPage()
        return extractItems(extractor.initialPage.items)
    }

    private fun extractItems(items: List<InfoItem>): List<Track> {
        val results = LinkedList<Track>()
        for (item in items) {
            if (item is StreamInfoItem) {
                results.add(Track(item.url.substring(item.url.indexOf('=') + 1), listOf(Artist(1, item.uploaderName)), item.name, item.duration * 1000))
            }
        }
        return results
    }

    class Downloader : org.schabi.newpipe.extractor.downloader.Downloader() {

        private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; WOW64; rv:68.0) Gecko/20100101 Firefox/68.0"

        override fun execute(request: Request): Response {
            val headers = HashMap<String, String>()
            headers["User-Agent"] = USER_AGENT
            request.headers().forEach { (k, v) -> headers[k] = v[0]}

            val con = WebRequests.createConnection(request.url(), request.httpMethod(), headers)

            if (request.httpMethod() == "POST")
                WebRequests.post(con, request.dataToSend() as ByteArray)

            val res = WebRequests.request(con)

            return Response(res.status, "", con.headerFields, res.value, request.url())
        }
    }
}
