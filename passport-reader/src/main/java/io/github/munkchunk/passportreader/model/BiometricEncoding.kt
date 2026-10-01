package io.github.munkchunk.passportreader.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * How the chip encodes its biometric data groups (DG2 face, DG3 fingerprints).
 *
 * ICAO Doc 9303 moved from ISO/IEC 19794 to ISO/IEC 39794: inspection systems
 * were required to read 39794 from 2026-01-01, and issuers must use it from
 * 2030-01-01. Until then both are in circulation, and a chip carries one or
 * the other, never both. The two are not compatible with each other.
 *
 * Reported so a caller - or someone filing a bug - can tell which path a read
 * took. A face that fails to decode on an [ISO_39794] document, which is the
 * newer and less exercised path, is worth knowing about.
 */
@Parcelize
enum class BiometricEncoding : Parcelable {
    /** ISO/IEC 19794:2005, the original encoding. */
    ISO_19794,

    /** ISO/IEC 39794, its extensible successor. */
    ISO_39794,
}
