package io.github.munkchunk.passportreader.utils

import io.github.munkchunk.passportreader.model.error.statusWord
import io.github.munkchunk.passportreader.verification.DataGroupRead
import io.github.munkchunk.passportreader.verification.DocumentSource
import org.jmrtd.PassportService
import org.jmrtd.lds.LDSFileUtil
import java.security.PublicKey

/**
 * [DocumentSource] over a live chip session: the verifier's only way to the
 * chip on a device.
 *
 * Files are read raw, never parsed and re-encoded, because EF.SOd's hashes are
 * over the bytes exactly as the chip stores them.
 *
 * @param activeAuthenticationKey the public key from DG15, which JMRTD records
 *   in its result; null when the chip has no DG15
 */
internal class ChipDocumentSource(
    private val service: PassportService,
    private val maxBlockSize: Int,
    private val activeAuthenticationKey: PublicKey?,
) : DocumentSource {

    override fun readDataGroup(number: Int): DataGroupRead =
        readFile(LDSFileUtil.lookupFIDByTag(LDSFileUtil.lookupTagByDataGroupNumber(number)))

    /** EF.SOd exactly as the chip stores it. */
    fun readSecurityObject(): DataGroupRead = readFile(PassportService.EF_SOD)

    override fun activeAuthenticate(challenge: ByteArray): ByteArray {
        val key = checkNotNull(activeAuthenticationKey) { "no DG15, so no Active Authentication" }
        // JMRTD sends INTERNAL AUTHENTICATE and returns the chip's answer; the
        // algorithm names only label its result object, and checking the
        // answer is ActiveAuthentication's job, not JMRTD's.
        return service.doAA(key, null, null, challenge).response
    }

    private fun readFile(fid: Short): DataGroupRead =
        try {
            DataGroupRead.Bytes(service.getInputStream(fid, maxBlockSize).use { it.readBytes() })
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: SCUBA and JMRTD wrap a chip's refusal and a
            // lost link in several layers of exception, so the status word is
            // looked for anywhere in the chain rather than by catch type.
            val statusWord = e.statusWord()
            if (statusWord != null) DataGroupRead.Refused(statusWord) else DataGroupRead.Failed(e)
        }
}
