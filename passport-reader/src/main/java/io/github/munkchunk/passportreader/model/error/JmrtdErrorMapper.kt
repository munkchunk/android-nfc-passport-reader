package io.github.munkchunk.passportreader.model.error

/**
 * True when this, or anything it wraps, is Android refusing I/O on a [android.nfc.Tag]
 * handle that has been superseded.
 *
 * A passport that slips off and back is rediscovered as a new Tag, and the old
 * one is dead from then on: `IsoDep.connect()` and every transceive on it throw
 * `java.lang.SecurityException: Permission Denial: Tag ( ID: ... ) is out of
 * date`. That is a lost tag, not a security failure. It is also not
 * [java.security.GeneralSecurityException], so without this it would fall
 * through to Unknown. Matched on the platform's message because the type alone would also
 * catch a genuine permission problem, which is not a lost tag.
 */
internal fun Throwable.isStaleTag(): Boolean =
    causeChain().any { it is SecurityException && it.message?.contains(STALE_TAG_MESSAGE, ignoreCase = true) == true }

/** This throwable and what it wraps, bounded in case a chain loops. */
private fun Throwable.causeChain(): Sequence<Throwable> =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }.take(MAX_CAUSE_DEPTH)

/** True when the passport has gone: a stale Tag handle or a TagLostException anywhere in the chain. */
internal fun Throwable.isLostTag(): Boolean =
    isStaleTag() || causeChain().any { it is android.nfc.TagLostException }

/**
 * The chip's status word from the first exception in the chain that carries
 * one, or null when no APDU was answered. SCUBA and JMRTD wrap a chip's answer
 * in several layers, and `CardServiceException(message, cause)` copies the
 * cause's word upward, but not every wrapper is a CardServiceException.
 */
internal fun Throwable.statusWord(): Int? =
    causeChain()
        .filterIsInstance<net.sf.scuba.smartcards.CardServiceException>()
        .map { it.sw }
        .firstOrNull { it != net.sf.scuba.smartcards.CardServiceException.SW_NONE && it > 0 }

/** JMRTD's protocol step from the first exception in the chain that has one. */
internal fun Throwable.protocolStep(): Int? =
    causeChain().filterIsInstance<org.jmrtd.CardServiceProtocolException>().firstOrNull()?.step

private const val STALE_TAG_MESSAGE = "is out of date"
private const val MAX_CAUSE_DEPTH = 8

/**
 * A lost tag is recognised wherever it sits in the chain. JMRTD wraps it
 * deeply while streaming a data group: lifting the phone during DG2 gives
 * `IOException("Unexpected exception")` <- CardServiceException
 * <- CardServiceException <- TagLostException, so checking only the top
 * level and its direct cause would report it as Unknown. It also
 * outranks whatever it caused: a PACE or BAC step that failed because the
 * passport moved is a lost tag, not a protocol or MRZ problem.
 */
internal fun Throwable.toPassportReadException(): PassportReadException = when {
    isStaleTag() ->
        PassportReadException.TagLost(message = "Tag handle out of date: the passport was rediscovered", cause = this)
    this !is android.nfc.TagLostException && causeChain().any { it is android.nfc.TagLostException } ->
        PassportReadException.TagLost(cause = this)
    else -> mapByType()
}

/**
 * Everything not recognised above, by type. The message on each is a one-line
 * diagnostic for logs and bug reports; what a user is told comes from the
 * exception's string resources. None of them repeats a JMRTD message, which
 * can carry MRZ-derived values.
 */
@Suppress("DEPRECATION") // JMRTD's deprecated exception types are matched here and nowhere else
private fun Throwable.mapByType(): PassportReadException = when (this) {
    is AccessControlException -> mapAccessControl()
    is org.jmrtd.BACDeniedException ->
        PassportReadException.WrongMrz(message = "Chip refused BAC", cause = this)
    is org.jmrtd.AccessDeniedException ->
        PassportReadException.AuthenticationFailed(message = "Chip refused access", cause = this)
    is org.jmrtd.PACEException -> PassportReadException.PaceFailed(
        message = "PACE failed at step $step, status word ${PassportReadException.statusWordText(sw)}",
        cause = this,
        step = step,
    )
    // Newer JMRTD may throw the protocol exception directly rather than inside a PACEException.
    is org.jmrtd.CardServiceProtocolException ->
        PassportReadException.PaceFailed(message = "Chip protocol failed at step $step", cause = this, step = step)
    is android.nfc.TagLostException ->
        PassportReadException.TagLost(message = "Passport left the field", cause = this)
    is net.sf.scuba.smartcards.CardServiceException -> when (val lost = cause) {
        // Not WrongMrz whatever the status word: access control failures
        // arrive as AccessControlException, so a word here came from some other
        // command. 0x6982 on a file read means secure messaging is not in
        // place (ICAO 9303-11 §4.3.2, §4.4.2) - most likely the chip aborted
        // the channel, which it does on de-power or any SM error (§9.8.5).
        is android.nfc.TagLostException -> PassportReadException.TagLost(cause = lost)
        // SW_NONE is no answer at all: the link, so no status word to show.
        else -> PassportReadException.NfcIo(
            message = "Chip status word ${PassportReadException.statusWordText(sw)}",
            cause = this,
            swCode = sw.takeIf { it >= 0 },
        )
    }
    is java.util.concurrent.TimeoutException,
    is kotlinx.coroutines.TimeoutCancellationException ->
        PassportReadException.NoTagDetected(message = "Timed out waiting for a passport", cause = this)
    is java.security.GeneralSecurityException ->
        PassportReadException.TrustOrPassiveAuthFailed(
            message = "Security check failed: ${javaClass.simpleName}",
            cause = this,
        )
    else -> PassportReadException.Unknown(message = "Unexpected ${javaClass.simpleName}", cause = this)
}

/**
 * Classifies BAC or PACE failing on the status word the chip gave, see
 * [authenticationRefusal]. A lost tag never reaches here: it outranks
 * everything in [toPassportReadException].
 *
 * Two cases are a wrong MRZ whatever the word. JMRTD refusing an MRZ field
 * before anything was sent is one. BAC's MUTUAL AUTHENTICATE refused with any
 * word is the other: that step checks nothing but the MRZ-derived key, and
 * ICAO 9303-11 §4.3.4.2 leaves its failure codes to the chip, so a word not in
 * the table is still far more likely a typo than a faulty chip.
 */
private fun AccessControlException.mapAccessControl(): PassportReadException {
    val chipAnswered = sw != net.sf.scuba.smartcards.CardServiceException.SW_NONE
    val bacKeyRefused = protocol == AccessProtocol.BAC && step == BAC_STEP_MUTUAL_AUTHENTICATE && chipAnswered
    return when {
        mrzRejected || bacKeyRefused -> PassportReadException.WrongMrz(cause = this)
        else -> when (authenticationRefusal(sw, protocol)) {
            AuthenticationRefusal.WrongCredential -> PassportReadException.WrongMrz(cause = this)
            AuthenticationRefusal.Unusable ->
                PassportReadException.AuthenticationFailed(message = message, cause = this)
            null -> when {
                protocol == AccessProtocol.PACE ->
                    PassportReadException.PaceFailed(message = message, cause = this, step = step)
                // BAC with no answer from the chip: the link, not the credential.
                !chipAnswered -> PassportReadException.NfcIo(message = message, cause = this)
                else -> PassportReadException.AuthenticationFailed(message = message, cause = this)
            }
        }
    }
}
