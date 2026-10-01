package io.github.munkchunk.passportreader.utils

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Guards the JPEG 2000 path used for DG2 face images.
 *
 * This matters more than it looks: neither passport available for testing
 * stores DG2 as JPEG 2000 -- both use image/jpeg -- so this path gets no
 * coverage from a real document, and a regression in it would be invisible
 * until someone read a passport from a state that does use JPEG 2000.
 *
 * The vectors are 240x320 JP2 containers, a typical ICAO portrait size,
 * encoded from [sourcePixels] at two bitrates. The bounds below are the
 * errors measured for OpenJPEG against JMRTD's JJ2000 decoder; JJ2000
 * scored 44 on both vectors, so the high-bitrate bound in particular is a
 * real improvement worth not regressing.
 */
@RunWith(AndroidJUnit4::class)
class Jpeg2000DecoderTest {

    @Test
    fun decodesHighBitrateVectorAccurately() = decodeAndCheck("dg2-sample-high.jp2", maxError = 8)

    /** Low bitrate: quantisation loss dominates, so the bar is necessarily looser. */
    @Test
    fun decodesLowBitrateVector() = decodeAndCheck("dg2-sample-low.jp2", maxError = 45)

    /**
     * DG12's document scans arrive with no MIME type, so the decoder is chosen
     * from the bytes. A JP2 container, as DG2 uses.
     */
    @Test
    fun decodesUnlabelledJp2Container() =
        check("JP2", ImageDecoder.decodeUnlabelled(asset("dg2-sample-high.jp2")), maxError = 8)

    /** The same image as a bare codestream: JPEG 2000 with no JP2 boxes around it. */
    @Test
    fun decodesUnlabelledCodestream() {
        val jp2 = asset("dg2-sample-high.jp2")
        val codestream = jp2.copyOfRange(codestreamOffset(jp2), jp2.size)
        check("codestream", ImageDecoder.decodeUnlabelled(codestream), maxError = 8)
    }

    private fun asset(name: String): ByteArray =
        InstrumentationRegistry.getInstrumentation().context.assets.open("jp2/$name").use { it.readBytes() }

    /** Where the 'jp2c' box's contents start (ISO/IEC 15444-1 I.5.4); the vectors have it last. */
    private fun codestreamOffset(jp2: ByteArray): Int {
        val type = "jp2c".toByteArray()
        val at = (0..jp2.size - type.size).first { i -> type.indices.all { jp2[i + it] == type[it] } }
        return at + type.size
    }

    private fun decodeAndCheck(name: String, maxError: Int) {
        // Through ImageDecoder, as the read path decodes a JPEG 2000 face, and
        // with the MIME type in the other spelling and case it accepts.
        check(name, ImageDecoder.decode(asset(name), "IMAGE/JPEG2000"), maxError)
    }

    private fun check(name: String, decoded: Bitmap, maxError: Int) {
        assertEquals("$name width", WIDTH, decoded.width)
        assertEquals("$name height", HEIGHT, decoded.height)

        val error = maxChannelDifference(sourcePixels(), pixels(decoded))
        assertTrue(
            "$name: worst per-channel error $error exceeds $maxError",
            error <= maxError,
        )
    }

    /** Regenerates the image the vectors were encoded from. Must stay in step with the generator. */
    private fun sourcePixels(): IntArray {
        val px = IntArray(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                var r = x * 255 / WIDTH
                var g = y * 255 / HEIGHT
                var b = (x + y) * 255 / (WIDTH + HEIGHT)
                val bar = (x / 15 + y / 15) % 2 == 0 && y > HEIGHT * 3 / 4
                val flat = x > WIDTH / 3 && x < WIDTH * 2 / 3 && y > HEIGHT / 3 && y < HEIGHT / 2
                val oval = Math.pow((x - WIDTH / 2.0) / (WIDTH / 3.0), 2.0) +
                    Math.pow((y - HEIGHT / 2.5) / (HEIGHT / 4.0), 2.0) < 1.0
                if (bar) { r = 255; g = 255; b = 255 }
                if (flat) { r = 128; g = 128; b = 128 }
                if (oval) { r = 255 - r; g = 200; b = 60 }
                px[y * WIDTH + x] = -0x1000000 or (r shl 16) or (g shl 8) or b
            }
        }
        return px
    }

    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also {
        b.getPixels(it, 0, b.width, 0, 0, b.width, b.height)
    }

    private fun maxChannelDifference(a: IntArray, b: IntArray): Int {
        var worst = 0
        for (i in a.indices) {
            for (shift in intArrayOf(16, 8, 0)) {
                val d = abs(((a[i] ushr shift) and 0xFF) - ((b[i] ushr shift) and 0xFF))
                if (d > worst) worst = d
            }
        }
        return worst
    }

    private companion object {
        const val WIDTH = 240
        const val HEIGHT = 320
    }
}
