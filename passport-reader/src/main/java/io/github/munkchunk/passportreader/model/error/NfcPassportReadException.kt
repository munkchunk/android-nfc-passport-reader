package io.github.munkchunk.passportreader.model.error

import androidx.annotation.StringRes
import io.github.munkchunk.passportreader.R

/**
 * Domain-level errors. App module catches only these - no JMRTD imports needed.
 *
 * These carry *which* error happened and the arguments needed to describe it,
 * not the words. The words live in `res/values/strings.xml` so that all of this
 * library's user-facing copy can be read in one place and overridden by name in
 * a consuming app. Resolve with `context.getString(e.messageRes, *e.messageArgs)`.
 *
 * [technicalDetails] is the exception: it stays a plain String, because it is a
 * diagnostic for a bug report rather than copy, and status words are easier to
 * search for untranslated.
 */
sealed class PassportReadException(
    message: String? = null,
    cause: Throwable? = null
) : Exception(message, cause) {

    /** Short heading for this failure. */
    @get:StringRes
    abstract val titleRes: Int

    /** Body copy for this failure, to be formatted with [messageArgs]. */
    @get:StringRes
    abstract val messageRes: Int

    /** Format arguments for [messageRes]; empty when it takes none. */
    open val messageArgs: List<Any> get() = emptyList()

    /**
     * What went wrong underneath, for a bug report or a collapsible "details"
     * section: the cause's type and message and, when the chip answered, its
     * status word and what ISO/IEC 7816-4 says it means.
     */
    open val technicalDetails: String?
        get() = cause?.let { throwable ->
            buildString {
                append("Cause: ${throwable::class.simpleName}\n")
                throwable.message?.let { append("Detail: $it\n") }
                if (throwable is net.sf.scuba.smartcards.CardServiceException) {
                    append("Status word: ${statusWordText(throwable.sw)}")
                }
            }
        }

    /** MRZ (passportNumber/DOB/expiry) is wrong - BAC refused. */
    class WrongMrz(
        message: String? = null,
        cause: Throwable? = null
    ) : PassportReadException(message, cause) {
        override val titleRes = R.string.passport_error_wrong_mrz_title
        override val messageRes = R.string.passport_error_wrong_mrz_message
    }

    /** BAC/PACE auth failed with correct MRZ - usually chip/auth issue. */
    class AuthenticationFailed(
        message: String? = null,
        cause: Throwable? = null
    ) : PassportReadException(message, cause) {
        override val titleRes = R.string.passport_error_authentication_failed_title
        override val messageRes = R.string.passport_error_authentication_failed_message
    }

    /** PACE not supported or failed. */
    class PaceFailed(
        message: String? = null,
        cause: Throwable? = null,
        val step: Int? = null
    ) : PassportReadException(message, cause) {

        override val titleRes = R.string.passport_error_pace_failed_title

        override val messageRes: Int
            get() = if (step == null) {
                R.string.passport_error_pace_failed_message
            } else {
                R.string.passport_error_pace_failed_message_at_step
            }

        override val messageArgs: List<Any>
            get() = listOfNotNull(step)

        override val technicalDetails: String
            get() = buildString {
                append(super.technicalDetails ?: "")
                step?.let {
                    appendLine()
                    appendLine("PACE step $it: ${paceStepName(it)}")
                }
            }
    }

    /** NFC link dropped during APDU exchange. */
    class TagLost(
        message: String? = null,
        cause: Throwable? = null
    ) : PassportReadException(message, cause) {
        override val titleRes = R.string.passport_error_tag_lost_title
        override val messageRes = R.string.passport_error_tag_lost_message
    }

    /** General NFC I/O (APDU) problem. */
    class NfcIo(
        message: String? = null,
        cause: Throwable? = null,
        val swCode: Int? = null
    ) : PassportReadException(message, cause) {

        override val titleRes = R.string.passport_error_nfc_io_title

        override val messageRes: Int
            get() = if (swCode == null) {
                R.string.passport_error_nfc_io_message
            } else {
                R.string.passport_error_nfc_io_message_with_code
            }

        // The status word is diagnostic vocabulary rather than copy, so it is
        // passed in as an argument rather than translated.
        override val messageArgs: List<Any>
            get() = swCode?.takeIf { it >= 0 }?.let { listOf(statusWordText(it)) } ?: emptyList()
    }

    /** No tag detected in time (UI/UX timeout). */
    class NoTagDetected(
        message: String? = null,
        cause: Throwable? = null
    ) : PassportReadException(message, cause) {
        override val titleRes = R.string.passport_error_no_tag_detected_title
        override val messageRes = R.string.passport_error_no_tag_detected_message
    }

    /** Passive Auth / CSCA trust problems. */
    class TrustOrPassiveAuthFailed(
        message: String? = null,
        cause: Throwable? = null
    ) : PassportReadException(message, cause) {
        override val titleRes = R.string.passport_error_trust_failed_title
        override val messageRes = R.string.passport_error_trust_failed_message
    }

    /** Fallback for anything else. */
    class Unknown(
        message: String? = null,
        cause: Throwable? = null
    ) : PassportReadException(message, cause) {

        override val titleRes = R.string.passport_error_unknown_title

        private val causeMessage: String? get() = cause?.message?.takeIf { it.isNotBlank() }

        override val messageRes: Int
            get() = if (causeMessage == null) {
                R.string.passport_error_unknown_message
            } else {
                R.string.passport_error_unknown_message_with_cause
            }

        override val messageArgs: List<Any>
            get() = listOfNotNull(causeMessage)
    }

    companion object {
        /**
         * A status word as hex, with its meaning when it is one a passport read
         * meets. SCUBA's SW_NONE (-1) means the APDU never completed, so it is
         * said in words: as hex it would read 0xFFFF, which looks like an answer.
         */
        @Suppress("MagicNumber") // a status word is two bytes
        internal fun statusWordText(sw: Int): String =
            if (sw < 0) NO_ANSWER else "0x%04X (%s)".format(sw and 0xFFFF, statusWordMeaning(sw))

        internal const val NO_ANSWER = "none, the chip did not answer"

        /**
         * ISO/IEC 7816-4's meaning for the status words a passport read meets,
         * and BSI TR-03110-3 B.14.2's for PACE's 0x63CX.
         */
        @Suppress("CyclomaticComplexMethod", "MagicNumber") // a lookup table
        private fun statusWordMeaning(sw: Int): String = when (sw and 0xFFFF) {
            in 0x63C2..0x63CF -> "wrong credential, ${sw and 0xF} tries left"
            0x63C1 -> "wrong credential, password suspended"
            0x63C0 -> "wrong credential, password blocked"
            0x9000 -> "success"
            0x6300 -> "verification failed"
            0x6400 -> "execution error, state unchanged"
            0x6581 -> "memory failure"
            0x6700 -> "wrong length"
            0x6881 -> "secure messaging not supported"
            0x6982 -> "security status not satisfied"
            0x6983 -> "authentication method blocked"
            0x6984 -> "reference data not usable"
            0x6985 -> "conditions of use not satisfied"
            0x6986 -> "command not allowed, no current EF"
            0x6987 -> "secure messaging data objects missing"
            0x6988 -> "secure messaging data objects incorrect"
            0x6A80 -> "incorrect data field"
            0x6A81 -> "function not supported"
            0x6A82 -> "file not found"
            0x6A86 -> "incorrect P1-P2"
            0x6A88 -> "referenced data not found"
            else -> "not one this library interprets"
        }

        /** JMRTD's PACE step numbers, 0 being deriving the key from the MRZ. */
        private fun paceStepName(step: Int): String = when (step) {
            0 -> "key derivation from the MRZ"
            1 -> "decrypting the chip's nonce"
            2 -> "mapping the nonce to new domain parameters"
            3 -> "key agreement"
            4 -> "exchanging authentication tokens"
            else -> "not one JMRTD documents"
        }
    }
}