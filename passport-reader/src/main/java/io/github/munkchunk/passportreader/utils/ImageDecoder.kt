package io.github.munkchunk.passportreader.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.gemalto.jp2.JP2Decoder
import org.jmrtd.lds.ImageInfo
import org.jnbis.internal.WsqDecoder
import java.io.DataInputStream
import java.io.IOException

/**
 * Turns the images a chip holds - faces, portraits, signatures, fingerprints -
 * into bitmaps, by the MIME type the record gives: JPEG 2000, WSQ, and
 * whatever Android decodes natively (JPEG, PNG). A type nothing here decodes,
 * such as PGM, fails as an IOException.
 */
internal object ImageDecoder {

    /**
     * Reads exactly [ImageInfo.getImageLength] bytes of [image] and decodes
     * them as [mimeType].
     *
     * @throws IOException when there is no MIME type, the image is short, or
     *   it does not decode.
     */
    fun decode(image: ImageInfo, mimeType: String?): Bitmap {
        if (mimeType == null) throw IOException("Image has no MIME type")
        val bytes = ByteArray(image.imageLength)
        DataInputStream(image.imageInputStream).readFully(bytes)
        return decode(bytes, mimeType)
    }

    /** Decodes [bytes] as [mimeType]; see [decode]. */
    fun decode(bytes: ByteArray, mimeType: String): Bitmap = when (mimeType.lowercase()) {
        // OpenJPEG via jp2-android. Measured against JMRTD's JJ2000 decoder:
        // same dimensions, materially more accurate, and an order of
        // magnitude faster. See Jpeg2000DecoderTest.
        JPEG_2000, JPEG_2000_ALTERNATIVE ->
            JP2Decoder(bytes).decode() ?: throw IOException("JPEG 2000 image did not decode")
        WSQ -> wsq(bytes)
        else -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IOException("$mimeType image did not decode")
    }

    /**
     * Decodes an image that arrives with no MIME type - DG12's scans of the
     * document's front and rear, which ICAO 9303-10 stores as bare bytes - by
     * its signature: JPEG 2000, as a JP2 container or a bare codestream, goes
     * to OpenJPEG, which [BitmapFactory] cannot decode; anything else is left
     * to the platform.
     *
     * @throws IOException when it does not decode.
     */
    fun decodeUnlabelled(bytes: ByteArray): Bitmap = decode(bytes, sniffedMimeType(bytes))

    /** [JPEG_2000] when [bytes] start with a JP2 or J2K signature; otherwise a type the platform decodes. */
    internal fun sniffedMimeType(bytes: ByteArray): String = when {
        bytes.startsWith(JP2_SIGNATURE) || bytes.startsWith(J2K_SIGNATURE) -> JPEG_2000
        else -> PLATFORM
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    /** WSQ decodes to 8-bit grey, one byte a pixel; Android wants colour. */
    private fun wsq(bytes: ByteArray): Bitmap {
        val grey = WsqDecoder().decode(bytes)
        val colours = IntArray(grey.pixels.size) { i ->
            val level = grey.pixels[i].toUByte().toInt()
            Color.rgb(level, level, level)
        }
        return Bitmap.createBitmap(colours, grey.width, grey.height, Bitmap.Config.ARGB_8888)
    }

    private const val JPEG_2000 = "image/jp2"
    private const val JPEG_2000_ALTERNATIVE = "image/jpeg2000"
    private const val WSQ = "image/x-wsq"

    /** Not a real MIME type: whatever [BitmapFactory] recognises, which needs none. */
    private const val PLATFORM = "application/octet-stream"

    /** The JP2 signature box, ISO/IEC 15444-1 Annex I.5.1. */
    @Suppress("MagicNumber") // the signature bytes are the spec
    private val JP2_SIGNATURE = byteArrayOf(
        0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87.toByte(), 0x0A
    )

    /** A bare JPEG 2000 codestream: SOC then SIZ, ISO/IEC 15444-1 Annex A. */
    @Suppress("MagicNumber") // the marker bytes are the spec
    private val J2K_SIGNATURE = byteArrayOf(0xFF.toByte(), 0x4F, 0xFF.toByte(), 0x51)
}
