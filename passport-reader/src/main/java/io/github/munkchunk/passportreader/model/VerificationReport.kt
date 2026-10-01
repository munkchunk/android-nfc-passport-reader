package io.github.munkchunk.passportreader.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * What could be verified about a passport, check by check.
 *
 * Each check carries a [CheckVerdict] and a reason, and is null when the read
 * never reached it. [pace] is also null when PACE was not used and no signed
 * DG14 lists it. The library does not combine them into one answer: whether a
 * given report is good enough is the app's decision.
 *
 * Passive Authentication is three checks: [documentSignature],
 * [certificateChain] and [dataGroupHashes]. Together they show the data was
 * issued and not altered. [chipAuthentication] and [activeAuthentication] show
 * the chip itself is genuine rather than a copy.
 */
@Parcelize
data class VerificationReport(
    /** Basic Access Control: whether BAC ran and completed. */
    val basicAccessControl: CheckResult? = null,
    /**
     * PACE: whether it ran and completed, held to the PACE list DG14 signs, so
     * it fails when EF.CardAccess hid PACE from a chip that offers it. Null
     * when PACE was not used and no signed DG14 lists it.
     */
    val pace: CheckResult? = null,
    /** EF.SOd's signature verifies against the document signer certificate. */
    val documentSignature: CheckResult? = null,
    /** The document signer chains to a trusted CSCA certificate. */
    val certificateChain: CheckResult? = null,
    /**
     * Every data group read hashes to the value EF.SOd signs. Passing does not
     * mean every group was compared; see [hashes].
     */
    val dataGroupHashes: CheckResult? = null,
    /**
     * Chip Authentication through DG14, or PACE-CAM's chip proof checked
     * against EF.CardSecurity. [CheckVerdict.NOT_PRESENT] when the chip offers
     * neither.
     */
    val chipAuthentication: CheckResult? = null,
    /** Active Authentication: the chip signs a fresh challenge with DG15's key. */
    val activeAuthentication: CheckResult? = null,
    /**
     * Terminal Authentication, which grants access to DG3 and DG4. Needs the
     * issuing state's inspection-system credentials, which the public API
     * cannot supply yet, so it is not checked when the chip offers it.
     */
    val terminalAuthentication: CheckResult? = null,
    /** One entry per data group EF.SOd lists, in data-group order. */
    val hashes: List<DataGroupHash>? = null,
    /**
     * The chain [certificateChain] checked, document signer first and CSCA
     * last. When no chain could be built it holds the document signer alone;
     * it is empty when EF.SOd carries no document signer or could not be read.
     */
    val chainCertificates: List<CertificateDetails>? = null,
) : Parcelable
