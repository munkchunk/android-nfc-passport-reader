package io.github.munkchunk.passportreader.model.error

import net.sf.scuba.smartcards.CardServiceException

/** The protocols that open secure messaging with the chip, ICAO 9303-11 §4.3 and §4.4. */
internal enum class AccessProtocol { BAC, PACE }

/**
 * BAC or PACE did not complete. The read path raises this around the
 * access-control step so the error mapper knows where a status word came from:
 * 0x6982 from an authentication command means the password cannot be used
 * (BSI TR-03110-3 B.14.2), while 0x6982 from a file read means secure messaging
 * is not in place (ICAO 9303-11 §4.3.2, §4.4.2). Only the first says anything
 * about the credential.
 *
 * [sw] is the chip's status word from anywhere in [cause]'s chain, or
 * [CardServiceException.SW_NONE] when the chip never answered. [step] is
 * JMRTD's step number, where it gave one. [mrzRejected] means the key could
 * not even be built, because JMRTD refused an MRZ field; no APDU was sent.
 */
internal class AccessControlException(
    val protocol: AccessProtocol,
    val step: Int?,
    cause: Throwable?,
    message: String = "$protocol failed" + (step?.let { " at step $it" } ?: ""),
    val mrzRejected: Boolean = false,
) : CardServiceException(message, cause, cause?.statusWord() ?: SW_NONE)

/**
 * Wraps whatever stopped BAC or PACE before or during the exchange with the
 * chip. JMRTD refuses a malformed MRZ field with an IllegalArgumentException
 * whose message names the value - BACKey's is "Illegal date: " followed by
 * the date - so that one is not kept at all: the result says only that the
 * MRZ was refused, and nothing of it can reach a log, a report or the screen.
 */
internal fun accessControlFailure(protocol: AccessProtocol, failure: Throwable): AccessControlException =
    if (failure is IllegalArgumentException) {
        AccessControlException(
            protocol, null, null, "$protocol key could not be built from the MRZ", mrzRejected = true
        )
    } else {
        AccessControlException(protocol, failure.protocolStep(), failure)
    }

/** JMRTD's BACProtocol step for MUTUAL AUTHENTICATE, the one that checks the MRZ-derived key. */
internal const val BAC_STEP_MUTUAL_AUTHENTICATE = 2

/** How the chip refused an authentication command, when its status word says it did. */
internal enum class AuthenticationRefusal {
    /** The credential was wrong: it can be corrected and tried again. */
    WrongCredential,

    /** The credential cannot be used at all: blocked, suspended or deactivated. */
    Unusable,
}

/**
 * Reads the status word [protocol]'s authentication command was refused with.
 * Neither specification says why authentication failed: treating
 * [AuthenticationRefusal.WrongCredential] as a wrong MRZ is this library's
 * inference, sound because the MRZ is the only secret BAC and PACE-MRZ use.
 *
 * PACE follows BSI TR-03110-3 B.14.2 (General Authenticate), which ICAO
 * 9303-11 §4.4.4.2 builds on:
 *
 * - 0x6300 "Authentication failed", and 0x63CX with X >= 2 tries remaining,
 *   are WrongCredential.
 * - 0x63C1 suspended, 0x63C0 and 0x6983 blocked, 0x6984 deactivated,
 *   0x6985 suspended, and 0x6982, which a chip MAY send for any of those three,
 *   are [AuthenticationRefusal.Unusable].
 *
 * BAC has no such table: ICAO leaves EXTERNAL AUTHENTICATE's failures
 * "operating system dependent" (§4.3.4.2). A BAC key is derived from the MRZ
 * and has no retry counter, so it cannot be blocked, suspended or deactivated,
 * and every word PACE reads as a password state is a refused credential here.
 * A GBR BAC-only chip, for one, answers 0x6985 to a document number with one
 * digit wrong.
 */
internal fun authenticationRefusal(sw: Int, protocol: AccessProtocol): AuthenticationRefusal? =
    when (sw and SW_MASK) {
        SW_AUTHENTICATION_FAILED, in SW_TRIES_REMAINING -> AuthenticationRefusal.WrongCredential
        SW_BLOCKED_NO_TRIES,
        SW_SUSPENDED_ONE_TRY,
        SW_SECURITY_STATUS_NOT_SATISFIED,
        SW_AUTHENTICATION_METHOD_BLOCKED,
        SW_REFERENCE_DATA_NOT_USABLE,
        SW_CONDITIONS_OF_USE_NOT_SATISFIED -> when (protocol) {
            AccessProtocol.PACE -> AuthenticationRefusal.Unusable
            AccessProtocol.BAC -> AuthenticationRefusal.WrongCredential
        }
        else -> null
    }

private const val SW_MASK = 0xFFFF
private const val SW_AUTHENTICATION_FAILED = 0x6300
private const val SW_BLOCKED_NO_TRIES = 0x63C0
private const val SW_SUSPENDED_ONE_TRY = 0x63C1
private const val SW_TWO_TRIES_REMAINING = 0x63C2
private const val SW_FIFTEEN_TRIES_REMAINING = 0x63CF
private val SW_TRIES_REMAINING = SW_TWO_TRIES_REMAINING..SW_FIFTEEN_TRIES_REMAINING
private const val SW_SECURITY_STATUS_NOT_SATISFIED = 0x6982
private const val SW_AUTHENTICATION_METHOD_BLOCKED = 0x6983
private const val SW_REFERENCE_DATA_NOT_USABLE = 0x6984
private const val SW_CONDITIONS_OF_USE_NOT_SATISFIED = 0x6985
