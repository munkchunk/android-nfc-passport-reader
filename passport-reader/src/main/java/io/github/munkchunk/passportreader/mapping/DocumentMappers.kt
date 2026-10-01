package io.github.munkchunk.passportreader.mapping

import android.graphics.Bitmap
import io.github.munkchunk.passportreader.data.AdditionalDocumentDetails
import io.github.munkchunk.passportreader.data.AdditionalPersonDetails
import io.github.munkchunk.passportreader.data.MrzData
import io.github.munkchunk.passportreader.data.PersonDetails
import io.github.munkchunk.passportreader.model.BiometricEncoding
import io.github.munkchunk.passportreader.model.Sex
import io.github.munkchunk.passportreader.model.error.PassportReadException
import io.github.munkchunk.passportreader.utils.ImageDecoder
import io.github.munkchunk.passportreader.utils.NfcLog
import net.sf.scuba.data.Gender
import org.jmrtd.cbeff.BiometricDataBlock
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG12File
import org.jmrtd.lds.icao.MRZInfo
import org.jmrtd.lds.iso19794.FaceInfo
import org.jmrtd.lds.iso19794.FingerInfo
import org.jmrtd.lds.iso39794.FaceImageDataBlock
import org.jmrtd.lds.iso39794.FingerImageDataBlock

/**
 * Conversions from the chip libraries' types into this library's own.
 *
 * Everything JMRTD and SCUBA expose stops here. Keeping the conversions in one
 * place is what lets those two be `implementation` dependencies rather than
 * `api`, so consumers do not compile against LGPL libraries. Adding a
 * third-party type to a public data class undoes that; add a mapping instead.
 */

internal fun Gender.toSex(): Sex = when (this) {
    Gender.MALE -> Sex.MALE
    Gender.FEMALE -> Sex.FEMALE
    Gender.UNSPECIFIED -> Sex.UNSPECIFIED
    Gender.UNKNOWN -> Sex.UNKNOWN
}

internal fun MRZInfo.toMrzData(): MrzData {
    // JMRTD renders TD3 as "line1\nline2\n" and TD1 as "l1\nl2\nl3\n".
    val full = toString().trimEnd()
    val parts = full.split('\n')
    return MrzData(
        full = full,
        line1 = parts.getOrNull(0).orEmpty(),
        line2 = parts.getOrNull(1).orEmpty(),
    )
}

/** The holder and document as DG1's MRZ records them. */
internal fun MRZInfo.toPersonDetails() = PersonDetails(
    documentCode = documentCode,
    issuingState = issuingState,
    primaryIdentifier = primaryIdentifier,
    secondaryIdentifier = secondaryIdentifier,
    nationality = nationality,
    documentNumber = documentNumber,
    dateOfBirth = dateOfBirth,
    dateOfExpiry = dateOfExpiry,
    optionalData1 = optionalData1,
    optionalData2 = optionalData2,
    sex = genderCode.toSex(),
)

/**
 * The MRZInfo BAC and PACE derive their key from: document number, date of
 * birth and date of expiry. The other fields are placeholders; the chip's DG1
 * supplies the real ones.
 *
 * JMRTD refuses a character outside the MRZ set - a space, a slash - in any
 * of the three fields with an IllegalStateException whose cause names the
 * character, and a document number too long for the MRZ with "Argument too
 * wide (28 > 9)". Nothing of either is kept: it would reach the screen as an
 * Unknown error, the logs and any error reporter. A date's length is not
 * checked here; BACKey refuses a wrong one during the read, as a wrong MRZ.
 *
 * @throws PassportReadException.WrongMrz with no cause, if JMRTD refuses any field.
 */
internal fun bacKeyMrzInfo(documentNumber: String, dateOfBirth: String, dateOfExpiry: String): MRZInfo = try {
    MRZInfo.createTD3MRZInfo(
        "P", "XXX", "", "", documentNumber, "XXX", dateOfBirth, Gender.UNSPECIFIED, dateOfExpiry, ""
    )
} catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
    // Deliberately broad and deliberately dropped: see above.
    throw PassportReadException.WrongMrz(message = "MRZ refused before reading: ${e.javaClass.simpleName}")
}

internal fun DG11File.toAdditionalPersonDetails() = AdditionalPersonDetails(
    custodyInformation = custodyInformation,
    fullDateOfBirth = fullDateOfBirth,
    nameOfHolder = nameOfHolder,
    otherNames = otherNames,
    otherValidTDNumbers = otherValidTDNumbers,
    permanentAddress = permanentAddress,
    personalNumber = personalNumber,
    personalSummary = personalSummary,
    placeOfBirth = placeOfBirth,
    profession = profession,
    proofOfCitizenship = proofOfCitizenship,
    tag = tag,
    tagPresenceList = tagPresenceList,
    telephone = telephone,
    title = title,
)

internal fun DG12File.toAdditionalDocumentDetails() = AdditionalDocumentDetails(
    endorsementsAndObservations = endorsementsAndObservations,
    dateAndTimeOfPersonalization = dateAndTimeOfPersonalization,
    dateOfIssue = dateOfIssue,
    imageOfFront = imageOfFront.toBitmapOrNull("front"),
    imageOfRear = imageOfRear.toBitmapOrNull("rear"),
    issuingAuthority = issuingAuthority,
    namesOfOtherPersons = namesOfOtherPersons,
    personalizationSystemSerialNumber = personalizationSystemSerialNumber,
    taxOrExitRequirements = taxOrExitRequirements,
    tag = tag,
    tagPresenceList = tagPresenceList,
)

/** Which encoding a decoded DG2 or DG3 record is in; null for anything else. */
internal fun BiometricDataBlock.toBiometricEncoding(): BiometricEncoding? = when (this) {
    is FaceInfo, is FingerInfo -> BiometricEncoding.ISO_19794
    is FaceImageDataBlock, is FingerImageDataBlock -> BiometricEncoding.ISO_39794
    else -> null
}

/**
 * One of DG12's document images: null when absent, and null when present but
 * undecodable - logged, because the panel then shows nothing and the log is
 * the only sign the chip carried an image. DG12 gives no MIME type, so the
 * format is told from the bytes: `BitmapFactory` alone returns null for any
 * JPEG 2000 scan.
 */
private fun ByteArray?.toBitmapOrNull(which: String): Bitmap? = this?.let { bytes ->
    try {
        ImageDecoder.decodeUnlabelled(bytes)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Deliberately broad: an undecodable scan must not cost the rest of DG12.
        val format = ImageDecoder.sniffedMimeType(bytes)
        NfcLog.w(NfcLog.DATA_GROUPS, "DG12: $which image did not decode (${bytes.size} bytes, $format)", e)
        null
    }
}
