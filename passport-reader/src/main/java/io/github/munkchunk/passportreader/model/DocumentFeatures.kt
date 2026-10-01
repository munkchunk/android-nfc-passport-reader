package io.github.munkchunk.passportreader.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Whether a chip supports a given security mechanism.
 *
 * [UNKNOWN] is the starting state and also the honest answer when a chip
 * neither advertises nor refuses a mechanism: absence of evidence is not
 * evidence of absence, and the read path distinguishes the two.
 */
@Parcelize
enum class FeatureSupport : Parcelable { UNKNOWN, SUPPORTED, NOT_SUPPORTED }

/**
 * What the chip appears to support, as discovered during a read.
 *
 * Populated progressively: [pace] from EF.CardAccess before authenticating,
 * and later from the PACE list a verified DG14 signs; [basicAccessControl] is
 * SUPPORTED when BAC was used, and left UNKNOWN when PACE was, since a PACE
 * session never tries BAC; [chipAuthentication] and [terminalAuthentication]
 * from DG14 and EF.CVCA, and [activeAuthentication] from whether EF.SOd lists
 * DG15.
 *
 * This describes *capability*, not outcome. Whether a mechanism was actually
 * used and whether it passed is [VerificationReport].
 */
@Parcelize
data class DocumentFeatures(
    /** Basic Access Control: SUPPORTED when it was used, otherwise not tested. */
    var basicAccessControl: FeatureSupport = FeatureSupport.UNKNOWN,
    /** PACE (Supplemental Access Control): offered in EF.CardAccess, or listed in a verified DG14. */
    var pace: FeatureSupport = FeatureSupport.UNKNOWN,
    /** Chip Authentication: a key in DG14, or PACE-CAM. */
    var chipAuthentication: FeatureSupport = FeatureSupport.UNKNOWN,
    /** Active Authentication, indicated by DG15. */
    var activeAuthentication: FeatureSupport = FeatureSupport.UNKNOWN,
    /** Terminal Authentication: EF.CVCA, or a TerminalAuthenticationInfo in DG14. */
    var terminalAuthentication: FeatureSupport = FeatureSupport.UNKNOWN,
) : Parcelable {

    /** True only on a positive indication; [FeatureSupport.UNKNOWN] is not a yes. */
    val supportsBasicAccessControl: Boolean get() = basicAccessControl == FeatureSupport.SUPPORTED
    val supportsPace: Boolean get() = pace == FeatureSupport.SUPPORTED
    val supportsChipAuthentication: Boolean get() = chipAuthentication == FeatureSupport.SUPPORTED
    val supportsActiveAuthentication: Boolean get() = activeAuthentication == FeatureSupport.SUPPORTED
    val supportsTerminalAuthentication: Boolean get() = terminalAuthentication == FeatureSupport.SUPPORTED
}
