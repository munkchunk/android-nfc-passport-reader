package io.github.munkchunk.passportreader.data

import android.graphics.Bitmap
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * DG12, the optional additional document details.
 *
 * Like DG11, every field is optional and sparsely populated in practice.
 *
 * A plain class rather than a data class: [Bitmap] has no value equality, so
 * a generated `equals` would compare the images by reference.
 */
@Parcelize
class AdditionalDocumentDetails(
    var endorsementsAndObservations: String? = null,
    /** Nominally YYYYMMDDhhmmss, passed through as the chip stores it. */
    var dateAndTimeOfPersonalization: String? = null,
    /** Nominally YYYYMMDD, passed through as the chip stores it. */
    var dateOfIssue: String? = null,
    var imageOfFront: Bitmap? = null,
    var imageOfRear: Bitmap? = null,
    var issuingAuthority: String? = null,
    /** Other people included on the document, such as children. */
    var namesOfOtherPersons: List<String>? = emptyList(),
    var personalizationSystemSerialNumber: String? = null,
    var taxOrExitRequirements: String? = null,
    /** The DG12 file tag. */
    var tag: Int = 0,
    /** Tags of the fields this document actually carries. */
    var tagPresenceList: List<Int>? = emptyList(),
) : Parcelable
