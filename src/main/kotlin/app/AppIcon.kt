package app

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * The application icon, read from the same .ico that ships beside the launcher for the shortcut to
 * use, so the tray and the shortcut cannot drift apart.
 *
 * Parsed here rather than handed to ImageIO, which has no .ico reader: the container is a short
 * directory of images, and each one is either a PNG, which ImageIO does read, or a bare Windows
 * DIB with no file header in front of it.
 */
object AppIcon {

    private const val RESOURCE = "/traktor-forwarder.ico"
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    /** Null when the icon is missing or unreadable; callers draw their own instead. */
    val image: BufferedImage? by lazy {
        runCatching { AppIcon::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }?.let(::parse) }
            .onFailure { println("Could not read $RESOURCE: ${it.message}") }
            .getOrNull()
    }

    private fun parse(bytes: ByteArray): BufferedImage? {
        if (bytes.size < 6 || u16(bytes, 0) != 0 || u16(bytes, 2) != 1) return null
        val count = u16(bytes, 4)
        if (count == 0) return null

        // Largest available, because the tray is scaled from it and downscaling is the only
        // direction that keeps edges clean.
        var best: BufferedImage? = null
        var bestPixels = -1
        for (i in 0 until count) {
            val entry = 6 + i * 16
            if (entry + 16 > bytes.size) break
            val width = (bytes[entry].toInt() and 0xFF).let { if (it == 0) 256 else it }
            val height = (bytes[entry + 1].toInt() and 0xFF).let { if (it == 0) 256 else it }
            val size = u32(bytes, entry + 8)
            val offset = u32(bytes, entry + 12)
            if (offset < 0 || size <= 0 || offset + size > bytes.size) continue

            val data = bytes.copyOfRange(offset, offset + size)
            val decoded = if (data.size > 4 && data.copyOfRange(0, 4).contentEquals(PNG_MAGIC)) {
                runCatching { ImageIO.read(ByteArrayInputStream(data)) }.getOrNull()
            } else {
                runCatching { dib(data) }.getOrNull()
            }

            if (decoded != null && width * height > bestPixels) {
                best = decoded
                bestPixels = width * height
            }
        }
        return best
    }

    /**
     * A BITMAPINFOHEADER followed by pixels bottom-up, with the height doubled because an icon
     * carries a 1 bit transparency mask under its colours. Only the 32 bit form is handled from
     * that mask onwards: it has its own alpha channel, so the mask adds nothing.
     */
    private fun dib(data: ByteArray): BufferedImage? {
        if (data.size < 40) return null
        val headerSize = u32(data, 0)
        val width = u32(data, 4)
        val height = u32(data, 8) / 2
        val bits = u16(data, 14)
        if (width <= 0 || height <= 0 || headerSize < 40) return null
        if (bits != 32 && bits != 24) return null

        val bytesPerPixel = bits / 8
        // Rows are padded out to a four byte boundary; at 32bpp they always already are.
        val stride = (width * bytesPerPixel + 3) / 4 * 4
        val start = headerSize
        if (start + stride * height > data.size) return null

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            val row = start + (height - 1 - y) * stride
            for (x in 0 until width) {
                val p = row + x * bytesPerPixel
                val b = data[p].toInt() and 0xFF
                val g = data[p + 1].toInt() and 0xFF
                val r = data[p + 2].toInt() and 0xFF
                val a = if (bits == 32) data[p + 3].toInt() and 0xFF else 0xFF
                image.setRGB(x, y, (a shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        return image
    }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or
        ((b[at + 1].toInt() and 0xFF) shl 8) or
        ((b[at + 2].toInt() and 0xFF) shl 16) or
        ((b[at + 3].toInt() and 0xFF) shl 24)
}
