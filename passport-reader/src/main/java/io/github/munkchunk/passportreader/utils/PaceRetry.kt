package io.github.munkchunk.passportreader.utils

import io.github.munkchunk.passportreader.model.error.AccessProtocol
import io.github.munkchunk.passportreader.model.error.authenticationRefusal
import io.github.munkchunk.passportreader.model.error.isLostTag
import io.github.munkchunk.passportreader.model.error.protocolStep
import io.github.munkchunk.passportreader.model.error.statusWord

/** What to do after one PACE attempt fails. */
internal enum class PaceRetry {
    /** Try the same protocol again after a backoff. */
    SameProtocol,

    /** Give up on this protocol and try the next one EF.CardAccess offers. */
    NextProtocol,

    /** Give up on PACE entirely. */
    Stop,
}

/**
 * Decides on what the failure is, never on its message.
 *
 * - [PaceRetry.Stop] when the passport has gone, or the chip refused the
 *   credential ([authenticationRefusal]). The MRZ is the same whichever
 *   protocol carries it, so a refused MRZ fails on all of them; and where a
 *   password has a retry counter (0x63CX, BSI TR-03110-3 B.14.2), each attempt
 *   spends a try.
 * - [PaceRetry.NextProtocol] when JMRTD failed at step 3 (key agreement) or
 *   step 4 (authentication token) without a refusal. Kept from the earlier
 *   rule: failures that late are less likely to be transient.
 * - [PaceRetry.SameProtocol] otherwise, including step 0 (key derivation),
 *   which fails transiently while the chip settles.
 */
internal fun paceRetryFor(failure: Throwable): PaceRetry {
    val sw = failure.statusWord()
    val step = failure.protocolStep()
    return when {
        failure.isLostTag() -> PaceRetry.Stop
        sw != null && authenticationRefusal(sw, AccessProtocol.PACE) != null -> PaceRetry.Stop
        step == PACE_STEP_KEY_AGREEMENT || step == PACE_STEP_AUTHENTICATION_TOKEN -> PaceRetry.NextProtocol
        else -> PaceRetry.SameProtocol
    }
}

/** JMRTD's step numbers, from the `CardServiceProtocolException`s its PACEProtocol throws. */
private const val PACE_STEP_KEY_AGREEMENT = 3
private const val PACE_STEP_AUTHENTICATION_TOKEN = 4
