package io.github.munkchunk.passportreader.utils

import android.graphics.Bitmap
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import io.github.munkchunk.passportreader.data.Passport
import io.github.munkchunk.passportreader.mapping.toAdditionalDocumentDetails
import io.github.munkchunk.passportreader.mapping.toAdditionalPersonDetails
import io.github.munkchunk.passportreader.mapping.toFaceDetails
import io.github.munkchunk.passportreader.mapping.toDetails
import io.github.munkchunk.passportreader.mapping.toMrzData
import io.github.munkchunk.passportreader.mapping.toPersonDetails
import io.github.munkchunk.passportreader.mapping.toReport
import io.github.munkchunk.passportreader.model.CertificateRole
import io.github.munkchunk.passportreader.model.FaceDetails
import io.github.munkchunk.passportreader.model.error.isStaleTag
import io.github.munkchunk.passportreader.reader.ReadStage
import io.github.munkchunk.passportreader.timing.NfcReadTimingConfig
import io.github.munkchunk.passportreader.trust.CscaTrustStore
import net.sf.scuba.smartcards.CardService
import net.sf.scuba.smartcards.CardServiceException
import org.jmrtd.PassportService
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.icao.DG3File
import org.jmrtd.lds.icao.MRZInfo
import java.io.IOException

/**
 * Reads one passport from a [Tag]: connects, chooses how large each read can
 * be, runs [PassportNFC] and its verification, and assembles the [Passport].
 *
 * Blocking, and slow - seconds, not milliseconds. Call it off the main thread;
 * NfcPassportReader runs it interruptibly on Dispatchers.IO so that a
 * cancelled read stops.
 */
internal class ChipReader {

    /**
     * @throws Exception whatever stopped the read; NfcPassportReader maps it
     *   to a PassportReadException.
     */
    fun read(
        tag: Tag,
        mrzInfo: MRZInfo,
        trustStore: CscaTrustStore,
        timingConfig: NfcReadTimingConfig,
        onStage: (ReadStage) -> Unit,
    ): Passport {
        onStage(ReadStage.Connecting)
        val isoDep = IsoDep.get(tag) ?: error("IsoDep not supported")
        var service: PassportService? = null
        try {
            connect(isoDep, timingConfig)
            val session = openSession(isoDep)
            service = session.first
            // CRITICAL: Full RF and card stabilization delay
            // The library creates a NEW NFC connection (nfc.connect()), so we need
            // significant time for:
            // 1. RF field to stabilize after new connection (500-700ms)
            // 2. Card to initialize after ps.open() (200-300ms)
            // 3. PACE cryptographic state machine to be ready (100-200ms)
            // Without adequate delay, PACE fails at various steps:
            // - Step 0: Key derivation (card not ready)
            // - Step 1: Nonce exchange (RF unstable)
            // - Step 2: Nonce mapping (crypto not initialized)
            Thread.sleep(timingConfig.cardStabilizationMs)
            val chip = PassportNFC(service, trustStore, mrzInfo, session.second, timingConfig, onStage)
            onStage(ReadStage.Verifying)
            // Populates chip.verificationState, which becomes the report below.
            chip.verifySecurity()
            return passportFrom(chip)
        } finally {
            try { service?.close() } catch (_: Exception) {}
            try { isoDep.close() } catch (_: SecurityException) {} catch (_: Throwable) {}
        }
    }

    private fun connect(isoDep: IsoDep, timingConfig: NfcReadTimingConfig) {
        isoDep.timeout = timingConfig.isoDepTimeoutMs   // be generous; JMRTD ops can be long
        try {
            isoDep.connect()
        } catch (e: IOException) {
            // connect() fails this way only when the tag cannot be reached: the
            // passport left the field during the settle delay and has not come
            // back. It is a bare IOException with no message, which would
            // otherwise be reported as Unknown.
            throw TagLostException("Could not connect: the passport is no longer in the field")
                .apply { initCause(e) }
        }
        // Capability probe: determines whether large data groups can be
        // read in a few round trips or must be chunked into ~223 bytes.
        NfcLog.i(
            "NFC_PERF",
            "IsoDep: maxTransceiveLength=${isoDep.maxTransceiveLength}, " +
                "extendedLengthApduSupported=${isoDep.isExtendedLengthApduSupported}, " +
                "timeout=${isoDep.timeout}ms"
        )
    }

    /**
     * A PassportService over [isoDep], opened, and the block size to read
     * files in.
     *
     * Large data groups - DG2 especially - are dominated by APDU round trips,
     * not crypto. At the 223-byte default a ~19KB face image needs ~87
     * exchanges at ~70ms each; reading in 4KB blocks cuts that to five and
     * roughly halves the time.
     *
     * The phone supporting extended-length APDUs is necessary but NOT
     * sufficient - the chip must support them too, and older BAC-era chips do
     * not. Asking one for a 4KB read kills the transport mid-transfer.
     * Presence of EF.CardAccess (i.e. PACE support) is the practical signal
     * that a chip is modern enough, and it is readable before any
     * authentication, so probing costs one cheap exchange.
     */
    private fun openSession(isoDep: IsoDep): Pair<PassportService, Int> {
        val cs = CardService.getInstance(isoDep)
        val chipSupportsPace = probeForPaceSupport(cs)
        val useExtendedApdu = chipSupportsPace &&
            isoDep.isExtendedLengthApduSupported &&
            isoDep.maxTransceiveLength >= PassportNFC.EXTENDED_BLOCK_SIZE
        val secureMessagingLength = if (useExtendedApdu) {
            PassportService.EXTENDED_MAX_TRANCEIVE_LENGTH
        } else {
            PassportNFC.MAX_TRANSCEIVE_LENGTH_FOR_SECURE_MESSAGING
        }
        val blockSize = if (useExtendedApdu) PassportNFC.EXTENDED_BLOCK_SIZE else PassportNFC.MAX_BLOCK_SIZE
        NfcLog.i(
            "NFC_PERF",
            "APDU mode: ${if (useExtendedApdu) "EXTENDED" else "NORMAL"} " +
                "(pace=$chipSupportsPace, blockSize=$blockSize, " +
                "secureMessagingLength=$secureMessagingLength)"
        )
        val service = PassportService(
            cs, PassportNFC.MAX_TRANSCEIVE_LENGTH_FOR_PACE, secureMessagingLength, blockSize, false, true
        )
        service.open()
        return service to blockSize
    }

    /**
     * Reads EF.CardAccess to decide whether the chip does PACE, and therefore
     * whether extended-length APDUs are safe.
     *
     * The distinction that matters is *why* the read failed. A chip that
     * answers "no such file" has told us something: it is a BAC-era document
     * and the universal path is correct. A link that drops mid-probe has told
     * us nothing at all.
     *
     * Treating both as "no PACE" would not be failing safe: on a PACE document
     * whose link glitches on the first APDU, it would silently select the
     * wrong protocol path - NORMAL mode, 223-byte blocks - and the read would
     * then die with an NfcIo that looks like a chip fault. Such a glitch can
     * happen with the phone resting on the passport; lifting and replacing
     * the phone usually gives a read that chooses EXTENDED and succeeds.
     *
     * Scuba reports the difference: a status word from the chip, or
     * [CardServiceException.SW_NONE] when the APDU never completed. A link
     * failure is rethrown so the read fails as what it is and the retry path
     * handles it, rather than being converted into a wrong answer about the
     * document.
     */
    private fun probeForPaceSupport(cs: CardService): Boolean = try {
        val probe = PassportService(
            cs,
            PassportNFC.MAX_TRANSCEIVE_LENGTH_FOR_PACE,
            PassportNFC.MAX_TRANSCEIVE_LENGTH_FOR_SECURE_MESSAGING,
            PassportNFC.MAX_BLOCK_SIZE,
            false,
            true
        )
        probe.open()
        probe.getInputStream(PassportService.EF_CARD_ACCESS, PassportNFC.MAX_BLOCK_SIZE)
            .use { CardAccessFile(it) }
        NfcLog.i(PACE_PROBE, "supported: EF.CardAccess read")
        true
    } catch (e: CardServiceException) {
        if (e.sw == CardServiceException.SW_NONE) {
            NfcLog.w(
                PACE_PROBE,
                "link-failed: ${e.javaClass.simpleName} with no status word - the APDU " +
                    "never completed, which says nothing about PACE support"
            )
            throw e
        }
        if (e.sw == SW_FILE_NOT_FOUND) {
            NfcLog.i(PACE_PROBE, "absent: chip answered ${hexSw(e.sw)}, no EF.CardAccess")
        } else {
            // Still an answer, so still "no PACE" - but not the one expected,
            // and worth surfacing. Only two documents have ever been tested
            // here; the interesting reports will come from other issuers.
            NfcLog.w(
                PACE_PROBE,
                "absent-unexpected-sw: chip answered ${hexSw(e.sw)} rather than " +
                    "${hexSw(SW_FILE_NOT_FOUND)}. Treated as no PACE because the chip " +
                    "replied. Worth reporting with the issuing country."
            )
        }
        false
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Deliberately broad: any failure reading a present EF.CardAccess is logged
        // as "unusable" and treated as no PACE.
        // Except a dead Tag handle: like a link failure, that says nothing about the chip.
        if (e.isStaleTag()) {
            NfcLog.w(PACE_PROBE, "link-failed: tag handle out of date, the passport was rediscovered")
            throw e
        }
        NfcLog.w(
            PACE_PROBE,
            "unusable: EF.CardAccess present but ${e.javaClass.simpleName} - treated as " +
                "no PACE. Worth reporting with the issuing country."
        )
        false
    }

    /**
     * What the read found, as the library's own types. An image that will not
     * decode is left out and logged under [PassportNfcUtils.BIOMETRIC_FORMAT]:
     * the read has still succeeded, and every hash may have verified, so the
     * log is the only sign it was lost.
     */
    private fun passportFrom(chip: PassportNFC): Passport {
        val mrz = chip.dg1File.mrzInfo
        val report = chip.verificationState.toReport()
        return Passport().apply {
            features = chip.features
            // The chip's own bytes. JMRTD's re-encoding matched them on both
            // test passports, but a re-encoding need not in general.
            sodBytes = chip.securityObjectBytes ?: chip.sodFile.encoded
            personDetails = mrz.toPersonDetails()
            this.mrz = mrz.toMrzData()
            faceEncoding = PassportNfcUtils.faceEncoding(chip.dg2File)
            face = face(chip.dg2File)
            faceDetails = faceDetails(chip.dg2File)
            portrait = chip.dg5File?.let { image("portrait") { PassportNfcUtils.portraitImage(it) } }
            signature = chip.dg7File?.let { image("signature") { PassportNfcUtils.signatureImage(it) } }
            chip.dg3File?.let { dg3 -> fingerprints(dg3)?.let { fingerprints = it } }
            additionalPersonDetails = chip.dg11File?.toAdditionalPersonDetails()
            additionalDocumentDetails = chip.dg12File?.toAdditionalDocumentDetails()
            optionalDetails = chip.dg13Bytes
            personsToNotify = chip.personsToNotify
            documentSigner = chip.sodFile.docSigningCertificate?.toDetails(CertificateRole.DOCUMENT_SIGNER)
            chainCertificates = report.chainCertificates.orEmpty()
            verificationReport = report
        }
    }

    private fun face(dg2: DG2File): Bitmap? {
        val encoding = PassportNfcUtils.faceEncoding(dg2)
        return try {
            PassportNfcUtils.faceImage(dg2).also { face ->
                val mimeType = PassportNfcUtils.faceImages(dg2).firstOrNull()?.mimeType
                val size = "${face.width}x${face.height}"
                NfcLog.i(PassportNfcUtils.BIOMETRIC_FORMAT, "face: $encoding $mimeType $size")
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: a lost face must not fail the read, but must be seen.
            val records = dg2.subRecords.map { it.javaClass.name }
            NfcLog.w(PassportNfcUtils.BIOMETRIC_FORMAT, "no-face-image: encoding=$encoding records=$records", e)
            null
        }
    }

    /** DG2's face records' details; empty, and logged, when they will not map. */
    private fun faceDetails(dg2: DG2File): List<FaceDetails> = try {
        dg2.toFaceDetails().also { details ->
            val summary = details.joinToString { face ->
                "${face.encoding} landmarks=${face.landmarkCount} pose=${face.pose != null} " +
                    "scores=${face.qualityScores.size} rawPose=${face.rawPose} rawQuality=${face.rawQuality}"
            }
            NfcLog.i(PassportNfcUtils.BIOMETRIC_FORMAT, "face-details: ${details.size} record(s) [$summary]")
            NfcLog.personal(PassportNfcUtils.BIOMETRIC_FORMAT) {
                details.joinToString { "eye=${it.eyeColour} hair=${it.hairColour} rawPose=${it.rawPose} " +
                    "rawQuality=${it.rawQuality} pose=${it.pose} scores=${it.qualityScores}" }
            }
        }
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Deliberately broad: details that will not map must not cost the face or the read.
        NfcLog.w(PassportNfcUtils.BIOMETRIC_FORMAT, "no-face-details: ${e.javaClass.simpleName}", e)
        emptyList()
    }

    private fun fingerprints(dg3: DG3File): List<Bitmap>? {
        val encoding = PassportNfcUtils.fingerEncoding(dg3)
        return try {
            PassportNfcUtils.fingerprintImages(dg3).also { prints ->
                NfcLog.i(PassportNfcUtils.BIOMETRIC_FORMAT, "fingerprints: $encoding count=${prints.size}")
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: lost fingerprints must not fail the read, but must be seen.
            val records = dg3.subRecords.map { it.javaClass.name }
            NfcLog.w(
                PassportNfcUtils.BIOMETRIC_FORMAT, "no-fingerprint-image: encoding=$encoding records=$records", e
            )
            null
        }
    }

    /** A portrait or signature image, or null - logged as no-<what>-image - when it will not decode. */
    private fun image(what: String, decode: () -> Bitmap): Bitmap? = try {
        decode()
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Deliberately broad: a lost image must not fail the read, but must be seen.
        NfcLog.w(PassportNfcUtils.BIOMETRIC_FORMAT, "no-$what-image: ${e.javaClass.simpleName}", e)
        null
    }

    private companion object {
        /**
         * Tag for every line the PACE probe emits, so a field report can be reduced
         * to `adb logcat -s PACE_PROBE` and still say which branch was taken and
         * why.
         *
         * The classification is only as good as the documents behind it, and there
         * have been two: one GBR BAC-era chip answering 0x6A82 and one GBR PACE
         * chip. Every line names its branch first - supported, absent, link-failed,
         * absent-unexpected-sw, unusable - so an issuer that behaves differently
         * shows up as a branch nobody expected rather than as a mysterious failure.
         */
        private const val PACE_PROBE = "PACE_PROBE"

        private const val SW_FILE_NOT_FOUND = 0x6A82

        /** Status words print as unsigned 16-bit, which is how ISO 7816 quotes them. */
        private fun hexSw(sw: Int): String =
            "0x%04X".format(sw.toUShort().toInt())
    }
}
