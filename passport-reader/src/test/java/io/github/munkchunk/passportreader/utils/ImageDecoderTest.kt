package io.github.munkchunk.passportreader.utils

import org.jmrtd.lds.ImageInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * The ways an image from the chip can fail to decode, each an IOException the
 * read path already catches: a lost face or signature must not fail the read.
 * Decoding itself is covered by BiometricImagesTest (JPEG) and the
 * instrumented Jpeg2000DecoderTest, which decodes through ImageDecoder. WSQ
 * has no test vector here: neither the tests nor either test passport carry
 * a real WSQ fingerprint.
 */
class ImageDecoderTest {

    private class Image(private val bytes: ByteArray, private val claimedLength: Int = bytes.size) : ImageInfo {
        override fun getType() = ImageInfo.TYPE_PORTRAIT
        override fun getMimeType() = "image/jpeg"
        override fun getWidth() = 1
        override fun getHeight() = 1
        override fun getRecordLength() = claimedLength.toLong()
        override fun getImageLength() = claimedLength
        override fun getImageInputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun getEncoded() = bytes
    }

    @Test
    fun `an image without a MIME type is refused`() {
        assertThrows(IOException::class.java) { ImageDecoder.decode(Image(byteArrayOf(1, 2, 3)), null) }
    }

    @Test
    fun `an image shorter than its record claims is refused`() {
        // The record says how many bytes to read; a short stream must not
        // decode a truncated picture as if it were whole.
        assertThrows(IOException::class.java) {
            ImageDecoder.decode(Image(byteArrayOf(1, 2, 3), claimedLength = 10), "image/jpeg")
        }
    }

    /**
     * DG12's images carry no MIME type, so JPEG 2000 is told by its signature.
     * Missing it sends the scan to BitmapFactory, which returns null for JPEG
     * 2000. The decode itself is in Jpeg2000DecoderTest.
     */
    @Test
    fun `a JP2 container is recognised as JPEG 2000`() {
        val jp2 = bytes(0x00, 0x00, 0x00, 0x0C, 0x6A, 0x50, 0x20, 0x20, 0x0D, 0x0A, 0x87, 0x0A, 0x00)
        assertEquals("image/jp2", ImageDecoder.sniffedMimeType(jp2))
    }

    @Test
    fun `a bare JPEG 2000 codestream is recognised as JPEG 2000`() {
        assertEquals("image/jp2", ImageDecoder.sniffedMimeType(bytes(0xFF, 0x4F, 0xFF, 0x51, 0x00)))
    }

    @Test
    fun `JPEG, PNG and anything short are left to the platform`() {
        val platform = "application/octet-stream"
        assertEquals(platform, ImageDecoder.sniffedMimeType(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(platform, ImageDecoder.sniffedMimeType(bytes(0x89, 0x50, 0x4E, 0x47)))
        assertEquals(platform, ImageDecoder.sniffedMimeType(bytes(0xFF, 0x4F)))
        assertEquals(platform, ImageDecoder.sniffedMimeType(ByteArray(0)))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
