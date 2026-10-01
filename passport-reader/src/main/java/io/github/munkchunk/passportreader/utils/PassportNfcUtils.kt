package io.github.munkchunk.passportreader.utils

import android.graphics.Bitmap
import io.github.munkchunk.passportreader.mapping.toBiometricEncoding
import io.github.munkchunk.passportreader.model.BiometricEncoding

import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.icao.DG3File
import org.jmrtd.lds.icao.DG5File
import org.jmrtd.lds.icao.DG7File
import org.jmrtd.lds.ImageInfo
import org.jmrtd.lds.iso19794.FaceInfo
import org.jmrtd.lds.iso19794.FingerInfo
import org.jmrtd.lds.iso39794.FaceImageDataBlock
import org.jmrtd.lds.iso39794.FingerImageDataBlock
import org.jmrtd.lds.iso39794.FingerImageRepresentationBlock

import java.io.IOException

internal object PassportNfcUtils {

    /**
     * The face images in DG2, in either biometric encoding.
     *
     * Not [DG2File.getFaceInfos]: that returns ISO/IEC 19794 records only and
     * silently drops ISO/IEC 39794 ones, which lost the face on every 39794
     * document while the read still reported success. The sub-records carry
     * both, and each encoding's image record implements [ImageInfo], so from
     * here on the two are decoded the same way.
     */
    internal fun faceImages(dg2File: DG2File): List<ImageInfo> =
        dg2File.subRecords.flatMap { record ->
            when (record) {
                is FaceInfo -> record.faceImageInfos
                is FaceImageDataBlock -> record.representationBlocks.filter { it.imageRepresentation2DBlock != null }
                else -> emptyList()
            }
        }

    /** Which encoding DG2's face records use; null if it holds none. */
    internal fun faceEncoding(dg2File: DG2File): BiometricEncoding? =
        dg2File.subRecords.firstNotNullOfOrNull { it.toBiometricEncoding() }

    /** Which encoding DG3's fingerprint records use; null if it holds none. */
    internal fun fingerEncoding(dg3File: DG3File): BiometricEncoding? =
        dg3File.subRecords.firstNotNullOfOrNull { it.toBiometricEncoding() }

    /** The fingerprint images in DG3, in either encoding; see [faceImages]. */
    internal fun fingerImages(dg3File: DG3File): List<ImageInfo> =
        dg3File.subRecords.flatMap { record ->
            when (record) {
                is FingerInfo -> record.fingerImageInfos
                is FingerImageDataBlock -> record.representationBlocks
                else -> emptyList()
            }
        }

    /**
     * The image's MIME type, which for ISO/IEC 39794 fingerprints JMRTD 0.8.8
     * does not supply: `FingerImageRepresentationBlock.getMimeType()` returns
     * null for every format, although the format code itself survives parsing.
     * The strings are the ones JMRTD's own format enum carries.
     *
     * Only fingerprint records are filled in; anything else is passed through
     * as JMRTD gives it, so 19794 records behave exactly as they did.
     * `BiometricImagesTest` has a canary that fails once JMRTD supplies the
     * type itself, which is the signal to delete this.
     */
    internal fun ImageInfo.resolvedMimeType(): String? {
        if (this !is FingerImageRepresentationBlock || mimeType != null) return mimeType
        return when (imageDataFormat) {
            FingerImageRepresentationBlock.ImageDataFormatCode.WSQ -> "image/x-wsq"
            FingerImageRepresentationBlock.ImageDataFormatCode.JPEG2000_LOSSY,
            FingerImageRepresentationBlock.ImageDataFormatCode.JPEG2000_LOSSLESS -> "image/jp2"
            FingerImageRepresentationBlock.ImageDataFormatCode.PNG -> "image/png"
            FingerImageRepresentationBlock.ImageDataFormatCode.PGM -> "image/pgm"
            null -> null
        }
    }

    /**
     * Tag for how DG2's face and DG3's fingerprints were found, for the same
     * reason as the PACE probe's: both test documents are ISO/IEC 19794, so
     * the ISO/IEC 39794 path is known only from unit tests until someone reads
     * a 39794 document. `adb logcat -s BIOMETRIC_FORMAT` names the encoding,
     * MIME type and outcome of each, which is enough to tell an unfamiliar
     * encoding from an undecodable image.
     */
    internal const val BIOMETRIC_FORMAT = "BIOMETRIC_FORMAT"

    /** The first face in DG2, in either biometric encoding. */
    @Throws(IOException::class)
    fun faceImage(dg2File: DG2File): Bitmap {
        val face = faceImages(dg2File).firstOrNull() ?: throw IOException("No face image in DG2")
        return ImageDecoder.decode(face, face.resolvedMimeType())
    }

    /** The first displayed portrait in DG5. */
    @Throws(IOException::class)
    fun portraitImage(dg5File: DG5File): Bitmap {
        val portrait = dg5File.images.firstOrNull() ?: throw IOException("No portrait in DG5")
        return ImageDecoder.decode(portrait, portrait.mimeType)
    }

    /** The first displayed signature or usual mark in DG7. */
    @Throws(IOException::class)
    fun signatureImage(dg7File: DG7File): Bitmap {
        val signature = dg7File.images.firstOrNull() ?: throw IOException("No signature image in DG7")
        return ImageDecoder.decode(signature, signature.mimeType)
    }

    /**
     * Every fingerprint in DG3 that decodes. One that does not - ISO/IEC 39794
     * allows PGM, which nothing here decodes - is logged and skipped rather
     * than costing the others.
     *
     * @throws IOException when none decodes.
     */
    @Throws(IOException::class)
    fun fingerprintImages(dg3File: DG3File): List<Bitmap> {
        val decoded = fingerImages(dg3File).mapIndexedNotNull { index, finger ->
            val mimeType = finger.resolvedMimeType()
            try {
                ImageDecoder.decode(finger, mimeType)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Deliberately broad: one undecodable finger must not cost the others.
                NfcLog.w(BIOMETRIC_FORMAT, "fingerprint-skipped: #$index $mimeType", e)
                null
            }
        }
        if (decoded.isEmpty()) throw IOException("No decodable fingerprint image in DG3")
        return decoded
    }
}
