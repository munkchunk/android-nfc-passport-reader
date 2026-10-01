package io.github.munkchunk.passportreader.utils

import android.graphics.Bitmap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import net.sf.scuba.data.Gender
import io.github.munkchunk.passportreader.model.BiometricEncoding
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.icao.DG3File
import org.jmrtd.lds.iso19794.FaceImageInfo
import org.jmrtd.lds.iso19794.FaceInfo
import org.jmrtd.lds.iso39794.FaceImageDataBlock
import org.jmrtd.lds.iso39794.FaceImageInformation2DBlock
import org.jmrtd.lds.iso39794.FaceImageRepresentation2DBlock
import org.jmrtd.lds.iso39794.FaceImageRepresentationBlock
import org.jmrtd.lds.iso39794.FingerImageDataBlock
import org.jmrtd.lds.iso39794.FingerImagePositionCode
import org.jmrtd.lds.iso39794.FingerImageRepresentationBlock
import org.jmrtd.lds.iso39794.VersionBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Getting the face out of DG2 in both biometric encodings.
 *
 * ISO/IEC 39794 replaces ISO/IEC 19794 for DG2 and DG3: inspection systems
 * were required to read it from 2026-01-01 and issuers must use it from
 * 2030-01-01, with a chip carrying one encoding, never both. Both test
 * passports are 19794, so the 39794 path is covered only here.
 *
 * Each DG2 is built with JMRTD, encoded, and parsed back from the bytes, as a
 * chip read would. That proves this library's use of JMRTD, not that JMRTD
 * reads real 39794 chips correctly; only such a document can show that.
 */
@RunWith(RobolectricTestRunner::class)
class BiometricImagesTest {

    private fun encode(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val out = ByteArrayOutputStream()
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).compress(format, 90, out)
        return out.toByteArray()
    }

    private fun jpeg(width: Int, height: Int) = encode(width, height, Bitmap.CompressFormat.JPEG)

    private fun reparse(dg2: DG2File) = DG2File(ByteArrayInputStream(dg2.encoded))

    private fun iso19794Dg2(image: ByteArray, width: Int, height: Int): DG2File {
        val imageInfo = FaceImageInfo(
            Gender.UNSPECIFIED,
            FaceImageInfo.EyeColor.UNSPECIFIED,
            0,                                       // feature mask
            FaceImageInfo.HAIR_COLOR_UNSPECIFIED,
            0,                                       // expression
            intArrayOf(0, 0, 0),                     // pose angle
            intArrayOf(0, 0, 0),                     // pose angle uncertainty
            FaceImageInfo.FACE_IMAGE_TYPE_FULL_FRONTAL,
            FaceImageInfo.IMAGE_COLOR_SPACE_UNSPECIFIED,
            FaceImageInfo.SOURCE_TYPE_STATIC_PHOTO_DIGITAL_CAM,
            0,                                       // device type
            0,                                       // quality
            emptyArray(),                            // feature points
            width,
            height,
            ByteArrayInputStream(image),
            image.size,
            FaceImageInfo.IMAGE_DATA_TYPE_JPEG,
        )
        return reparse(DG2File.createISO19794DG2File(listOf(FaceInfo(listOf(imageInfo)))))
    }

    private fun iso39794Dg2(image: ByteArray): DG2File {
        val information = FaceImageInformation2DBlock(
            FaceImageInformation2DBlock.ImageDataFormatCode.JPEG,
            FaceImageInformation2DBlock.FaceImageKind2DCode.MRTD,
            null, null, null, null, null, null, null, null, null,
        )
        val representation = FaceImageRepresentationBlock(
            BigInteger.ZERO,
            FaceImageRepresentation2DBlock(image, information, null),
            null, null, null, null, null, null, null, null,
        )
        val block = FaceImageDataBlock(VersionBlock(3, 0), listOf(representation), null)
        return reparse(DG2File.createISO39794DG2File(listOf(block)))
    }

    @Test
    fun iso19794_faceIsExtracted() {
        val face = PassportNfcUtils.faceImage(iso19794Dg2(jpeg(6, 8), 6, 8))

        assertEquals(6 to 8, face.width to face.height)
    }

    @Test
    fun iso39794_faceIsExtracted() {
        val face = PassportNfcUtils.faceImage(iso39794Dg2(jpeg(6, 8)))

        assertEquals(6 to 8, face.width to face.height)
    }

    @Test
    fun encodingIsReportedForEach() {
        assertEquals(BiometricEncoding.ISO_19794, PassportNfcUtils.faceEncoding(iso19794Dg2(jpeg(6, 8), 6, 8)))
        assertEquals(BiometricEncoding.ISO_39794, PassportNfcUtils.faceEncoding(iso39794Dg2(jpeg(6, 8))))
    }

    @Test
    fun iso39794_faceMimeTypeIsReported() {
        val image = PassportNfcUtils.faceImages(iso39794Dg2(jpeg(6, 8))).single()

        assertEquals("image/jpeg", image.mimeType)
    }

    /** No face record at all: no encoding, and an IOException rather than a crash. */
    @Test
    fun emptyDg2HasNoFaceAndNoEncoding() {
        val dg2 = reparse(DG2File.createISO19794DG2File(emptyList()))

        assertNull(PassportNfcUtils.faceEncoding(dg2))
        assertTrue(PassportNfcUtils.faceImages(dg2).isEmpty())
        assertThrows(IOException::class.java) { PassportNfcUtils.faceImage(dg2) }
    }

    private fun finger(format: FingerImageRepresentationBlock.ImageDataFormatCode, image: ByteArray) =
        FingerImageRepresentationBlock(
            FingerImagePositionCode.RIGHT_INDEX_FINGER,
            FingerImageRepresentationBlock.ImpressionCode.PLAIN_CONTACT,
            format,
            null, null, null, null, null, null, null, null, null, null, null,
            image,
            null, null,
        )

    private fun iso39794Dg3(vararg fingers: FingerImageRepresentationBlock): DG3File {
        val block = FingerImageDataBlock(VersionBlock(3, 0), fingers.toList(), null)
        return DG3File(ByteArrayInputStream(DG3File.createISO39794DG3File(listOf(block)).encoded))
    }

    private fun parsedFingers(dg3: DG3File) =
        (dg3.subRecords.single() as FingerImageDataBlock).representationBlocks

    /**
     * DG3 sits behind terminal authentication, so this library will rarely if
     * ever read one; it is kept on the same path as DG2 so the two cannot
     * drift. ISO/IEC 39794-4 has no plain JPEG, so PNG stands in.
     */
    @Test
    fun iso39794_fingerprintIsExtracted() {
        val dg3 = iso39794Dg3(finger(FingerImageRepresentationBlock.ImageDataFormatCode.PNG, encode(5, 7, Bitmap.CompressFormat.PNG)))

        val prints = PassportNfcUtils.fingerprintImages(dg3)

        assertEquals(listOf(5 to 7), prints.map { it.width to it.height })
        assertEquals(BiometricEncoding.ISO_39794, PassportNfcUtils.fingerEncoding(dg3))
    }

    /**
     * WSQ is the usual fingerprint format, and the one whose MIME type matters:
     * anything the library does not recognise falls through to BitmapFactory,
     * which under Robolectric returns a placeholder for any bytes at all. So a
     * WSQ record holding garbage must be routed to the WSQ decoder, fail
     * there, and be skipped without costing the good print beside it. A wrong
     * WSQ mapping would come back as two prints.
     */
    @Test
    fun iso39794_undecodableFingerprintIsSkippedAlone() {
        val dg3 = iso39794Dg3(
            finger(FingerImageRepresentationBlock.ImageDataFormatCode.PNG, encode(5, 7, Bitmap.CompressFormat.PNG)),
            finger(FingerImageRepresentationBlock.ImageDataFormatCode.WSQ, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)),
        )

        val prints = PassportNfcUtils.fingerprintImages(dg3)

        assertEquals(listOf(5 to 7), prints.map { it.width to it.height })
    }

    @Test
    fun iso39794_fingerprintMimeTypesAreResolvedFromTheFormat() {
        val expected = mapOf(
            FingerImageRepresentationBlock.ImageDataFormatCode.WSQ to "image/x-wsq",
            FingerImageRepresentationBlock.ImageDataFormatCode.JPEG2000_LOSSY to "image/jp2",
            FingerImageRepresentationBlock.ImageDataFormatCode.JPEG2000_LOSSLESS to "image/jp2",
            FingerImageRepresentationBlock.ImageDataFormatCode.PNG to "image/png",
            FingerImageRepresentationBlock.ImageDataFormatCode.PGM to "image/pgm",
        )
        val dg3 = iso39794Dg3(*expected.keys.map { finger(it, byteArrayOf(0)) }.toTypedArray())

        val resolved = with(PassportNfcUtils) { parsedFingers(dg3).map { it.imageDataFormat to it.resolvedMimeType() } }

        assertEquals(expected.toList(), resolved)
    }

    /**
     * Canary for the JMRTD 0.8.8 bug `resolvedMimeType` works around. When
     * this fails, JMRTD supplies fingerprint MIME types itself: check they
     * match the strings above, then delete the workaround and this test.
     */
    @Test
    fun jmrtdStillOmitsFingerprintMimeTypes() {
        val dg3 = iso39794Dg3(finger(FingerImageRepresentationBlock.ImageDataFormatCode.WSQ, byteArrayOf(0)))

        assertNull(parsedFingers(dg3).single().mimeType)
    }
}
