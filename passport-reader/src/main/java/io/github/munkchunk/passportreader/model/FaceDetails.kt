package io.github.munkchunk.passportreader.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * What a DG2 face record says about its image, beyond the image itself: one
 * per face the chip holds.
 *
 * The two biometric encodings record different things, and not every field
 * means the same in both, so each field says which encoding fills it.
 * ISO/IEC 39794's values follow its published ASN.1 schemas. ISO/IEC
 * 19794-5:2005's pose and quality encodings are not freely published, so for
 * those the stored values are passed through unconverted, as [rawPose] and
 * [rawQuality], rather than guessed at.
 *
 * Eye and hair colour describe the holder, so treat them as personal data.
 *
 * A plain class rather than a data class, so that `toString()` does not print
 * the holder's eye and hair colour into whatever log it is interpolated into.
 */
@Parcelize
@Suppress("LongParameterList") // one property per detail a face record can carry
class FaceDetails(
    val encoding: BiometricEncoding,
    val eyeColour: EyeColour = EyeColour.UNSPECIFIED,
    val hairColour: HairColour = HairColour.UNSPECIFIED,
    /** Feature points (19794) or landmarks (39794) recorded for the face. */
    val landmarkCount: Int = 0,
    /** ISO/IEC 39794 only: yaw, pitch and roll; null when the record has no pose. */
    val pose: FacePose? = null,
    /**
     * ISO/IEC 19794 only: the pose angle's three stored values - yaw, pitch,
     * roll - exactly as the record holds them, not converted to degrees. A
     * 19794 record always has these fields, so this is set for every 19794
     * face, zeros included: whether zero means "not recorded" is part of what
     * the edition does not freely publish.
     */
    val rawPose: List<Int>? = null,
    /** ISO/IEC 39794 only: each quality assessment the record carries. */
    val qualityScores: List<QualityScore> = emptyList(),
    /** ISO/IEC 19794 only: the quality field exactly as stored, not converted to a scale; set for every 19794 face. */
    val rawQuality: Int? = null,
    /** ISO/IEC 39794 only: when the image was captured, as YYYY-MM-DD; null when not recorded. */
    val captureDate: String? = null,
) : Parcelable

/**
 * A face's pose, each angle -180 to 180 as ISO/IEC 39794-5's `AngleValue`
 * defines it; null for an angle the record leaves out.
 */
@Parcelize
data class FacePose(val yaw: Int?, val pitch: Int?, val roll: Int?) : Parcelable

/**
 * One ISO/IEC 39794 quality assessment (ISO/IEC 39794-1 `QualityBlock`).
 *
 * @property score 0 to 100, higher being better; null when the algorithm
 *   reported that it failed to assess the image
 * @property algorithmOrganisation who registered the algorithm that scored it;
 *   null if the record leaves its identifier out, which the schema does not allow
 * @property algorithmId which of that organisation's algorithms it was
 */
@Parcelize
data class QualityScore(val score: Int?, val algorithmOrganisation: Int?, val algorithmId: Int?) : Parcelable

/** Eye colour as a face record gives it: the codes both encodings share, plus 39794's hazel. */
@Parcelize
enum class EyeColour : Parcelable {
    UNSPECIFIED, UNKNOWN, OTHER, BLACK, BLUE, BROWN, GREY, GREEN, HAZEL, MULTI_COLOURED, PINK,
}

/** Hair colour as a face record gives it: 19794 adds green and blue, 39794 "known coloured". */
@Parcelize
enum class HairColour : Parcelable {
    UNSPECIFIED, UNKNOWN, OTHER, BALD, BLACK, BLONDE, BROWN, GREY, WHITE, RED, GREEN, BLUE, KNOWN_COLOURED,
}
