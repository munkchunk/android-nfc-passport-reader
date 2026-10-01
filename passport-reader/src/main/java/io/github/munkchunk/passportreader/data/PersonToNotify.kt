package io.github.munkchunk.passportreader.data

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * One entry from DG16, the optional list of persons to notify in an
 * emergency (ICAO 9303-10 §4.7.16).
 *
 * Every field is passed through as the chip stores it. ICAO makes all four
 * mandatory within an entry, but nothing here relies on that: a field the
 * chip left out is null.
 *
 * A plain class rather than a data class, so that `toString()` does not print
 * someone's name, telephone number and address into whatever log it is
 * interpolated into.
 */
@Parcelize
class PersonToNotify(
    /** When the entry was recorded, nominally YYYYMMDD. */
    var dateRecorded: String? = null,
    /** Primary and secondary identifiers with MRZ filler, as `SMITH<<CHARLES<R`. */
    var name: String? = null,
    /** In international form, nominally E.164 digits. */
    var telephone: String? = null,
    /** Free-form, with `<` between lines as in DG11's address. */
    var address: String? = null,
) : Parcelable
