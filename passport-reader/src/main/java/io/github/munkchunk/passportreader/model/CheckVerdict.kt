package io.github.munkchunk.passportreader.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** The outcome of one check in a [VerificationReport]. */
@Parcelize
enum class CheckVerdict : Parcelable {
    /** The check ran and held. */
    SUCCEEDED,

    /** The check ran and did not hold. */
    FAILED,

    /** The chip does not offer what this check needs, such as Active Authentication's key. */
    NOT_PRESENT,

    /** The check could have run but did not, for the reason given; nothing is wrong with the chip. */
    NOT_CHECKED,

    /** The check reached no conclusion, for instance because the read ended first. */
    UNKNOWN,
}
