package io.github.munkchunk.passportreader.mapping

import io.github.munkchunk.passportreader.model.BiometricEncoding
import io.github.munkchunk.passportreader.model.EyeColour
import io.github.munkchunk.passportreader.model.FacePose
import io.github.munkchunk.passportreader.model.HairColour
import io.github.munkchunk.passportreader.model.QualityScore
import java.io.ByteArrayInputStream
import java.math.BigInteger
import net.sf.scuba.data.Gender
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.iso19794.FaceImageInfo
import org.jmrtd.lds.iso19794.FaceInfo
import org.jmrtd.lds.iso39794.CoordinateCartesian2DUnsignedShortBlock
import org.jmrtd.lds.iso39794.DateTimeBlock
import org.jmrtd.lds.iso39794.FaceImageDataBlock
import org.jmrtd.lds.iso39794.FaceImageIdentityMetadataBlock
import org.jmrtd.lds.iso39794.FaceImageInformation2DBlock
import org.jmrtd.lds.iso39794.FaceImageLandmarkBlock
import org.jmrtd.lds.iso39794.FaceImageLandmarkKind
import org.jmrtd.lds.iso39794.FaceImagePoseAngleBlock
import org.jmrtd.lds.iso39794.FaceImageRepresentation2DBlock
import org.jmrtd.lds.iso39794.FaceImageRepresentationBlock
import org.jmrtd.lds.iso39794.QualityBlock
import org.jmrtd.lds.iso39794.RegistryIdBlock
import org.jmrtd.lds.iso39794.ScoreOrError
import org.jmrtd.lds.iso39794.VersionBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DG2's face details in both encodings. Neither test passport fills these in
 * as far as anyone has looked, so each record is built with JMRTD, encoded,
 * and parsed back from the bytes - the parse a chip read goes through.
 */
class FaceDetailsMappersTest {

    /** Not a real image: the details are what is under test, and nothing here decodes it. */
    private val image = ByteArray(16) { it.toByte() }

    private fun reparse(dg2: DG2File) = DG2File(ByteArrayInputStream(dg2.encoded))

    private fun iso19794(
        eye: FaceImageInfo.EyeColor = FaceImageInfo.EyeColor.UNSPECIFIED,
        hair: Int = FaceImageInfo.HAIR_COLOR_UNSPECIFIED,
        pose: IntArray = intArrayOf(0, 0, 0),
        quality: Int = 0,
        points: Array<FaceImageInfo.FeaturePoint> = emptyArray(),
    ) = FaceImageInfo(
        Gender.UNSPECIFIED, eye, 0, hair, 0, pose, intArrayOf(0, 0, 0),
        FaceImageInfo.FACE_IMAGE_TYPE_FULL_FRONTAL, FaceImageInfo.IMAGE_COLOR_SPACE_UNSPECIFIED,
        FaceImageInfo.SOURCE_TYPE_STATIC_PHOTO_DIGITAL_CAM, 0, quality, points,
        240, 320, ByteArrayInputStream(image), image.size, FaceImageInfo.IMAGE_DATA_TYPE_JPEG,
    )

    private fun dg2(vararg faces: FaceImageInfo) =
        reparse(DG2File.createISO19794DG2File(listOf(FaceInfo(faces.toList()))))

    private fun representation(
        identity: FaceImageIdentityMetadataBlock? = null,
        quality: List<QualityBlock>? = null,
        captured: DateTimeBlock? = null,
        landmarks: List<FaceImageLandmarkBlock>? = null,
    ): FaceImageRepresentationBlock {
        val information = FaceImageInformation2DBlock(
            FaceImageInformation2DBlock.ImageDataFormatCode.JPEG,
            FaceImageInformation2DBlock.FaceImageKind2DCode.MRTD,
            null, null, null, null, null, null, null, null, null,
        )
        return FaceImageRepresentationBlock(
            BigInteger.ZERO, FaceImageRepresentation2DBlock(image, information, null),
            captured, quality, null, null, null, null, identity, landmarks,
        )
    }

    private fun dg2(vararg faces: FaceImageRepresentationBlock) =
        reparse(DG2File.createISO39794DG2File(listOf(FaceImageDataBlock(VersionBlock(3, 0), faces.toList(), null))))

    // ------------------------------------------------------------ 19794

    @Test
    fun `19794 colours and feature points are mapped`() {
        val points = arrayOf(
            FaceImageInfo.FeaturePoint(1, 12, 1, 100, 120),
            FaceImageInfo.FeaturePoint(1, 12, 2, 140, 120),
        )
        val face = dg2(iso19794(FaceImageInfo.EyeColor.GRAY, FaceImageInfo.HAIR_COLOR_BLONDE, points = points))
            .toFaceDetails().single()

        assertEquals(BiometricEncoding.ISO_19794, face.encoding)
        assertEquals(EyeColour.GREY, face.eyeColour)
        assertEquals(HairColour.BLONDE, face.hairColour)
        assertEquals(2, face.landmarkCount)
    }

    /** ISO/IEC 19794-5:2005 is not freely published, so pose and quality pass through unconverted. */
    @Test
    fun `19794 pose and quality are passed through raw`() {
        val face = dg2(iso19794(pose = intArrayOf(91, 3, 180), quality = 57)).toFaceDetails().single()

        assertEquals(listOf(91, 3, 180), face.rawPose)
        assertEquals(57, face.rawQuality)
        assertNull(face.pose)
        assertTrue(face.qualityScores.isEmpty())
    }

    /** 19794 has no "other" hair colour, so a reserved code is not one. */
    @Test
    fun `a 19794 hair colour outside JMRTD's codes is unspecified`() {
        assertEquals(HairColour.UNSPECIFIED, dg2(iso19794(hair = 42)).toFaceDetails().single().hairColour)
    }

    /** What a sparse issuer's record maps to: raw pose and quality are always there, zeros included. */
    @Test
    fun `an empty 19794 record still carries raw pose and quality`() {
        val face = dg2(iso19794()).toFaceDetails().single()

        assertEquals(EyeColour.UNSPECIFIED, face.eyeColour)
        assertEquals(HairColour.UNSPECIFIED, face.hairColour)
        assertEquals(0, face.landmarkCount)
        assertEquals(listOf(0, 0, 0), face.rawPose)
        assertEquals(0, face.rawQuality)
    }

    @Test
    fun `each face in DG2 gets its own details`() {
        val faces = dg2(iso19794(FaceImageInfo.EyeColor.BLUE), iso19794(FaceImageInfo.EyeColor.BROWN)).toFaceDetails()
        assertEquals(listOf(EyeColour.BLUE, EyeColour.BROWN), faces.map { it.eyeColour })
    }

    // ------------------------------------------------------------ 39794

    @Test
    fun `39794 identity metadata, pose and landmarks are mapped`() {
        val pose = FaceImagePoseAngleBlock(
            FaceImagePoseAngleBlock.AngleDataBlock(-12, 2),
            FaceImagePoseAngleBlock.AngleDataBlock(5, 2),
            FaceImagePoseAngleBlock.AngleDataBlock(180, 0),
        )
        val identity = FaceImageIdentityMetadataBlock(
            FaceImageIdentityMetadataBlock.GenderCode.UNKNOWN,
            FaceImageIdentityMetadataBlock.EyeColourCode.HAZEL,
            FaceImageIdentityMetadataBlock.HairColourCode.KNOWN_COLOURED,
            170, null, null, pose,
        )
        val landmark = FaceImageLandmarkBlock(
            FaceImageLandmarkKind.MPEGFeaturePointCode.MPEG4_POINT_CODE_02_01,
            CoordinateCartesian2DUnsignedShortBlock(100, 120),
        )

        val face = dg2(representation(identity, landmarks = listOf(landmark))).toFaceDetails().single()

        assertEquals(BiometricEncoding.ISO_39794, face.encoding)
        assertEquals(EyeColour.HAZEL, face.eyeColour)
        assertEquals(HairColour.KNOWN_COLOURED, face.hairColour)
        assertEquals(FacePose(yaw = -12, pitch = 5, roll = 180), face.pose)
        assertEquals(1, face.landmarkCount)
        assertNull(face.rawPose)
        assertNull(face.rawQuality)
    }

    @Test
    fun `39794 quality scores and a failure to assess are mapped`() {
        val quality = listOf(
            QualityBlock(RegistryIdBlock(257, 2), 87),
            QualityBlock(RegistryIdBlock(257, 3), ScoreOrError(ScoreOrError.ScoringErrorCode.FAILURE_TO_ASSESS)),
        )

        val face = dg2(representation(quality = quality)).toFaceDetails().single()

        assertEquals(listOf(QualityScore(87, 257, 2), QualityScore(null, 257, 3)), face.qualityScores)
    }

    @Test
    fun `39794 capture date is the date part`() {
        val face = dg2(representation(captured = DateTimeBlock(2025, 3, 14, 9, 26, 53, 0))).toFaceDetails().single()
        assertEquals("2025-03-14", face.captureDate)
    }

    /** JMRTD leaves a negative month or day out of the encoding; parsed back, it is no date. */
    @Test
    fun `a 39794 capture time with only a year is no date`() {
        val face = dg2(representation(captured = DateTimeBlock(2025, -1, -1, -1, -1, -1, -1))).toFaceDetails().single()
        assertNull(face.captureDate)
    }

    @Test
    fun `an impossible 39794 capture date is no date`() {
        val face = dg2(representation(captured = DateTimeBlock(2025, 2, 30, -1, -1, -1, -1))).toFaceDetails().single()
        assertNull(face.captureDate)
    }

    /** A record with no metadata at all, which is what a sparse issuer writes. */
    @Test
    fun `an absent 39794 metadata block is unspecified, not an error`() {
        val face = dg2(representation()).toFaceDetails().single()

        assertEquals(EyeColour.UNSPECIFIED, face.eyeColour)
        assertEquals(HairColour.UNSPECIFIED, face.hairColour)
        assertNull(face.pose)
        assertEquals(0, face.landmarkCount)
        assertTrue(face.qualityScores.isEmpty())
        assertNull(face.captureDate)
    }
}
