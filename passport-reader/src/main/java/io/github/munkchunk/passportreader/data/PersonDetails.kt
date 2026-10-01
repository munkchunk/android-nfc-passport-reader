package io.github.munkchunk.passportreader.data

import android.os.Parcelable
import io.github.munkchunk.passportreader.model.Sex
import kotlinx.parcelize.Parcelize

/**
 * The holder and document fields from DG1, the chip's copy of the MRZ.
 *
 * Names have their trailing filler removed and any `<` within them replaced
 * by a space, so "ANNA<MARIA" arrives as "ANNA MARIA". Dates are YYMMDD, as
 * in the MRZ. For the raw MRZ lines see [MrzData].
 *
 * A plain class rather than a data class, so that `toString()` does not print
 * the holder's name, document number and date of birth into whatever log it
 * is interpolated into.
 */
@Parcelize
class PersonDetails(
    var documentCode: String? = null,
    var issuingState: String? = null,
    /** Surname, or the whole name where the issuer does not split it. */
    var primaryIdentifier: String? = null,
    /** Given names. */
    var secondaryIdentifier: String? = null,
    var nationality: String? = null,
    var documentNumber: String? = null,
    var dateOfBirth: String? = null,
    var dateOfExpiry: String? = null,
    /**
     * Issuer-defined. Some states put a personal number here; others use it
     * for the overflow of a document number longer than nine characters.
     */
    var optionalData1: String? = null,
    var optionalData2: String? = null,
    var sex: Sex? = Sex.UNKNOWN,
) : Parcelable
