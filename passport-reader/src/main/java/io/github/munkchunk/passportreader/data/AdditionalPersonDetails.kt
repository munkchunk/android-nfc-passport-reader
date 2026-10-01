package io.github.munkchunk.passportreader.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * DG11, the optional additional personal details.
 *
 * Every field is optional in ICAO 9303 and most issuers populate only a few,
 * so null is the normal case rather than a sign that something went wrong.
 *
 * A plain class rather than a data class: [proofOfCitizenship] is an array,
 * which a generated `equals` would compare by reference.
 */
@Parcelize
class AdditionalPersonDetails(
    var custodyInformation: String? = null,
    /** Date of birth in full, nominally YYYYMMDD, where DG1 has room only for YYMMDD. */
    var fullDateOfBirth: String? = null,
    /** Name as the issuer records it, not truncated to the MRZ's 39 characters. */
    var nameOfHolder: String? = null,
    var otherNames: List<String>? = emptyList(),
    var otherValidTDNumbers: List<String>? = emptyList(),
    var permanentAddress: List<String>? = emptyList(),
    var personalNumber: String? = null,
    var personalSummary: String? = null,
    var placeOfBirth: List<String>? = emptyList(),
    var profession: String? = null,
    /** An encoded image of a citizenship document. */
    var proofOfCitizenship: ByteArray? = null,
    /** The DG11 file tag. */
    var tag: Int = 0,
    /** Tags of the fields this document actually carries. */
    var tagPresenceList: List<Int>? = emptyList(),
    var telephone: String? = null,
    var title: String? = null,
) : Parcelable
