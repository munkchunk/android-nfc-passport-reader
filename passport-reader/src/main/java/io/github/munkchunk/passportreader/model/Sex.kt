package io.github.munkchunk.passportreader.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * The sex recorded in the MRZ.
 *
 * ICAO 9303 calls this field "sex" and allows `M`, `F`, or `<` for
 * unspecified; some issuers write `X`. The name follows the specification
 * rather than being a statement about the holder.
 *
 * [UNSPECIFIED] is a document that declined to state, which the specification
 * permits. [UNKNOWN] means this library could not determine it, which is a
 * different thing and usually means the field was unreadable.
 */
@Parcelize
enum class Sex : Parcelable {
    MALE,
    FEMALE,
    UNSPECIFIED,
    UNKNOWN,
    ;

    companion object {
        /**
         * Maps the MRZ character. `<` is the specification's filler and means
         * unspecified; `X` is not in the specification but is used by some
         * issuers for the same purpose.
         */
        fun fromMrzCharacter(code: Char?): Sex = when (code?.uppercaseChar()) {
            'M' -> MALE
            'F' -> FEMALE
            '<', 'X' -> UNSPECIFIED
            else -> UNKNOWN
        }
    }
}
