package io.github.munkchunk.passportreader.utils

import java.math.BigInteger
import org.jmrtd.lds.PACEInfo

/**
 * Whether EF.CardAccess told the truth about which PACE variants the chip
 * supports (ICAO 9303-11 §4.2: the inspection system "MUST verify the
 * authenticity of the contents of EF.CardAccess using EF.DG14 or
 * EF.CardSecurity").
 *
 * EF.CardAccess is read before any authentication and is not signed, so a
 * clone can edit it: drop PACE with Chip Authentication Mapping, so its own
 * missing key is never asked for, or drop PACE altogether so the read falls
 * back to BAC. A chip must list every PACE variant it supports in
 * EF.CardAccess, and DG14 carries the same SecurityInfos (§9.2.11) under
 * EF.SOd's signature. So every PACEInfo a signed DG14 lists must also have
 * been offered, and the one used must be among them.
 *
 * @param signed the PACEInfos in DG14, parsed from the bytes Passive Authentication verified
 * @param offered the PACEInfos in EF.CardAccess; empty when the chip offered none
 * @param used the PACEInfo the session was opened with; null for BAC
 */
internal fun cardAccessCheck(signed: List<PACEInfo>, offered: List<PACEInfo>, used: PACEInfo?): CardAccessCheck {
    val signedKeys = signed.map { it.key() }.toSet()
    val offeredKeys = offered.map { it.key() }.toSet()
    val withheld = signedKeys - offeredKeys
    return when {
        signedKeys.isEmpty() -> CardAccessCheck.NothingToCompare
        used == null -> CardAccessCheck.Mismatch("DG14 lists PACE but the chip did not offer it")
        withheld.isNotEmpty() ->
            CardAccessCheck.Mismatch("EF.CardAccess omits ${withheld.size} PACE variant(s) DG14 lists")
        used.key() !in signedKeys -> CardAccessCheck.Mismatch("The PACE variant used is not one DG14 lists")
        else -> CardAccessCheck.Matches
    }
}

internal sealed interface CardAccessCheck {
    /** DG14 lists no PACEInfo, so there is nothing to hold EF.CardAccess to. */
    data object NothingToCompare : CardAccessCheck

    data object Matches : CardAccessCheck

    /** EF.CardAccess, or the session, does not agree with what the issuer signed. */
    class Mismatch(val reason: String) : CardAccessCheck
}

/** A PACE variant: protocol, version and domain parameters. */
private data class PaceVariant(val protocol: String, val version: Int, val parameterId: BigInteger?)

private fun PACEInfo.key() = PaceVariant(objectIdentifier, version, parameterId)
