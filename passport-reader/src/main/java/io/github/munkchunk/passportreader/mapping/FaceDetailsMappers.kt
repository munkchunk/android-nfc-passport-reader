package io.github.munkchunk.passportreader.mapping

import io.github.munkchunk.passportreader.model.BiometricEncoding
import io.github.munkchunk.passportreader.model.EyeColour
import io.github.munkchunk.passportreader.model.FaceDetails
import io.github.munkchunk.passportreader.model.FacePose
import io.github.munkchunk.passportreader.model.HairColour
import io.github.munkchunk.passportreader.model.QualityScore
import java.time.DateTimeException
import java.time.LocalDate
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.iso19794.FaceImageInfo
import org.jmrtd.lds.iso19794.FaceInfo
import org.jmrtd.lds.iso39794.DateTimeBlock
import org.jmrtd.lds.iso39794.FaceImageDataBlock
import org.jmrtd.lds.iso39794.FaceImageIdentityMetadataBlock.EyeColourCode
import org.jmrtd.lds.iso39794.FaceImageIdentityMetadataBlock.HairColourCode
import org.jmrtd.lds.iso39794.FaceImagePoseAngleBlock.AngleDataBlock
import org.jmrtd.lds.iso39794.FaceImageRepresentationBlock

/** One [FaceDetails] per face image in DG2, in either encoding, in the order the chip stores them. */
internal fun DG2File.toFaceDetails(): List<FaceDetails> = subRecords.flatMap { record ->
    when (record) {
        is FaceInfo -> record.faceImageInfos.map { it.toFaceDetails() }
        is FaceImageDataBlock -> record.representationBlocks
            .filter { it.imageRepresentation2DBlock != null }
            .map { it.toFaceDetails() }
        else -> emptyList()
    }
}

// ------------------------------------------------------------ ISO/IEC 19794

private fun FaceImageInfo.toFaceDetails() = FaceDetails(
    encoding = BiometricEncoding.ISO_19794,
    eyeColour = eyeColor.toEyeColour(),
    hairColour = hairColor.toHairColour(),
    landmarkCount = featurePoints?.size ?: 0,
    rawPose = poseAngle?.toList(),
    rawQuality = quality,
)

/** JMRTD's names for the 19794 codes; 19794 spells grey and multi-coloured the American way. */
private fun FaceImageInfo.EyeColor?.toEyeColour(): EyeColour = when (this) {
    FaceImageInfo.EyeColor.BLACK -> EyeColour.BLACK
    FaceImageInfo.EyeColor.BLUE -> EyeColour.BLUE
    FaceImageInfo.EyeColor.BROWN -> EyeColour.BROWN
    FaceImageInfo.EyeColor.GRAY -> EyeColour.GREY
    FaceImageInfo.EyeColor.GREEN -> EyeColour.GREEN
    FaceImageInfo.EyeColor.MULTI_COLORED -> EyeColour.MULTI_COLOURED
    FaceImageInfo.EyeColor.PINK -> EyeColour.PINK
    FaceImageInfo.EyeColor.UNKNOWN -> EyeColour.UNKNOWN
    FaceImageInfo.EyeColor.UNSPECIFIED, null -> EyeColour.UNSPECIFIED
}

/**
 * JMRTD gives 19794's hair colour as its code. 19794 has no "other" colour, so
 * a code outside the ones JMRTD names is UNSPECIFIED - as an unrecognised eye
 * colour is - rather than a claim the record never made.
 */
private fun Int.toHairColour(): HairColour = when (this) {
    FaceImageInfo.HAIR_COLOR_UNSPECIFIED -> HairColour.UNSPECIFIED
    FaceImageInfo.HAIR_COLOR_BALD -> HairColour.BALD
    FaceImageInfo.HAIR_COLOR_BLACK -> HairColour.BLACK
    FaceImageInfo.HAIR_COLOR_BLONDE -> HairColour.BLONDE
    FaceImageInfo.HAIR_COLOR_BROWN -> HairColour.BROWN
    FaceImageInfo.HAIR_COLOR_GRAY -> HairColour.GREY
    FaceImageInfo.HAIR_COLOR_WHITE -> HairColour.WHITE
    FaceImageInfo.HAIR_COLOR_RED -> HairColour.RED
    FaceImageInfo.HAIR_COLOR_GREEN -> HairColour.GREEN
    FaceImageInfo.HAIR_COLOR_BLUE -> HairColour.BLUE
    FaceImageInfo.HAIR_COLOR_UNKNOWN -> HairColour.UNKNOWN
    else -> HairColour.UNSPECIFIED
}

// ------------------------------------------------------------ ISO/IEC 39794

private fun FaceImageRepresentationBlock.toFaceDetails(): FaceDetails {
    val identity = identityMetadataBlock
    val pose = identity?.poseAngleBlock
    return FaceDetails(
        encoding = BiometricEncoding.ISO_39794,
        eyeColour = identity?.eyeColourCode.toEyeColour(),
        hairColour = identity?.hairColourCode.toHairColour(),
        landmarkCount = landmarkBlocks?.size ?: 0,
        pose = pose?.let {
            FacePose(it.yawAngleDataBlock.degrees(), it.pitchAngleDataBlock.degrees(), it.rollAngleDataBlock.degrees())
        },
        qualityScores = qualityBlocks.orEmpty().map { block ->
            val result = block.scoreOrError
            QualityScore(
                score = result?.takeIf { it.isScore }?.score,
                algorithmOrganisation = block.algorithmIdBlock?.organization,
                algorithmId = block.algorithmIdBlock?.id,
            )
        },
        captureDate = captureDateTimeBlock?.isoDate(),
    )
}

private fun AngleDataBlock?.degrees(): Int? = this?.angleValue

private fun EyeColourCode?.toEyeColour(): EyeColour = when (this) {
    EyeColourCode.OTHER -> EyeColour.OTHER
    EyeColourCode.BLACK -> EyeColour.BLACK
    EyeColourCode.BLUE -> EyeColour.BLUE
    EyeColourCode.BROWN -> EyeColour.BROWN
    EyeColourCode.GREY -> EyeColour.GREY
    EyeColourCode.GREEN -> EyeColour.GREEN
    EyeColourCode.HAZEL -> EyeColour.HAZEL
    EyeColourCode.MULTI_COLOURED -> EyeColour.MULTI_COLOURED
    EyeColourCode.PINK -> EyeColour.PINK
    EyeColourCode.UNKNOWN -> EyeColour.UNKNOWN
    null -> EyeColour.UNSPECIFIED
}

private fun HairColourCode?.toHairColour(): HairColour = when (this) {
    HairColourCode.OTHER -> HairColour.OTHER
    HairColourCode.BALD -> HairColour.BALD
    HairColourCode.BLACK -> HairColour.BLACK
    HairColourCode.BLONDE -> HairColour.BLONDE
    HairColourCode.BROWN -> HairColour.BROWN
    HairColourCode.GREY -> HairColour.GREY
    HairColourCode.WHITE -> HairColour.WHITE
    HairColourCode.RED -> HairColour.RED
    HairColourCode.KNOWN_COLOURED -> HairColour.KNOWN_COLOURED
    HairColourCode.UNKNOWN -> HairColour.UNKNOWN
    null -> HairColour.UNSPECIFIED
}

/**
 * The date part only, as ISO 8601 YYYY-MM-DD, and only when a whole valid date
 * is present: a year alone is not a capture date. JMRTD marks an absent month
 * or day as negative. java.time formats without the device locale, which
 * String.format would apply to the digits.
 */
private fun DateTimeBlock.isoDate(): String? = try {
    if (year > 0 && month > 0 && day > 0) LocalDate.of(year, month, day).toString() else null
} catch (@Suppress("SwallowedException") e: DateTimeException) {
    // An impossible date, such as 30 February, is no capture date; nothing else is lost.
    null
}
