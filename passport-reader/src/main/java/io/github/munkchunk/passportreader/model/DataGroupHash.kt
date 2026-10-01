package io.github.munkchunk.passportreader.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * One data group's hash as signed in EF.SOd, against the hash of what the chip
 * returned.
 *
 * A group that was not read, such as DG3 without Terminal Authentication or an
 * optional group the chip refused, has no [actualHex]: it was not [compared],
 * which is not the same as matching.
 */
@Parcelize
data class DataGroupHash(
    /** The data group number, 1 to 16. */
    val dataGroup: Int,
    /** The hash EF.SOd holds for the group, upper-case hex. */
    val expectedHex: String,
    /** The hash of the group as read, upper-case hex; null when it was not read. */
    val actualHex: String? = null,
    /** True when both hashes are known and equal. */
    val matches: Boolean,
) : Parcelable {

    /** Whether the group was read and its hash compared at all. */
    val compared: Boolean get() = actualHex != null
}
