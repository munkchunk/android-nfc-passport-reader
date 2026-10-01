package io.github.munkchunk.passportreader.utils

import io.github.munkchunk.passportreader.verification.ActiveAuthentication
import io.github.munkchunk.passportreader.verification.DataGroupRead
import io.github.munkchunk.passportreader.verification.PassiveAuthentication

import net.sf.scuba.smartcards.CardServiceException
import net.sf.scuba.smartcards.CardFileInputStream
import net.sf.scuba.smartcards.ISO7816
import io.github.munkchunk.passportreader.model.error.AccessControlException
import io.github.munkchunk.passportreader.model.error.AccessProtocol
import io.github.munkchunk.passportreader.model.error.PassportReadException
import io.github.munkchunk.passportreader.model.error.accessControlFailure
import io.github.munkchunk.passportreader.model.error.protocolStep
import io.github.munkchunk.passportreader.model.error.isLostTag
import io.github.munkchunk.passportreader.model.error.statusWord

import io.github.munkchunk.passportreader.data.PersonToNotify
import io.github.munkchunk.passportreader.mapping.parsePersonsToNotify
import io.github.munkchunk.passportreader.verification.ChipAuthenticationMapping
import io.github.munkchunk.passportreader.reader.ReadStage
import io.github.munkchunk.passportreader.timing.NfcReadTimingConfig

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.ArrayList
import java.util.TreeMap

import org.jmrtd.BACKey
import org.jmrtd.PACEKeySpec
import org.jmrtd.PassportService
import io.github.munkchunk.passportreader.model.CheckResult
import io.github.munkchunk.passportreader.model.CheckVerdict
import io.github.munkchunk.passportreader.verification.VerificationState
import io.github.munkchunk.passportreader.model.DocumentFeatures
import io.github.munkchunk.passportreader.model.FeatureSupport
import io.github.munkchunk.passportreader.trust.CscaTrustStore
import org.jmrtd.lds.CVCAFile
import org.jmrtd.lds.TerminalAuthenticationInfo
import org.jmrtd.lds.CardAccessFile
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.PACEInfo
import org.jmrtd.lds.SODFile
import org.jmrtd.lds.icao.DG11File
import org.jmrtd.lds.icao.DG12File
import org.jmrtd.lds.icao.DG14File
import org.jmrtd.lds.icao.DG15File
import org.jmrtd.lds.icao.DG1File
import org.jmrtd.lds.icao.DG2File
import org.jmrtd.lds.icao.DG3File
import org.jmrtd.lds.icao.DG5File
import org.jmrtd.lds.icao.DG7File
import org.jmrtd.lds.icao.MRZInfo
import org.jmrtd.protocol.BACResult
import org.jmrtd.protocol.EACCAResult
import org.jmrtd.protocol.PACECAMResult
import org.jmrtd.protocol.PACEGMMappingResult
import org.jmrtd.protocol.PACEResult

/**
 * One read of an eMRTD chip, done in the constructor: open a session (PACE or
 * BAC), read EF.SOd and the data groups it lists, run Chip and Terminal
 * Authentication where the chip offers them, and record what was found in
 * [features] and [verificationState]. [verifySecurity] then checks what was read.
 *
 * @throws CardServiceException when the session cannot be opened. A file the
 *   read cannot do without - EF.SOd, DG1, DG2 - that cannot be read or parsed
 *   fails the read with whatever was thrown, passed on unchanged.
 */
internal class PassportNFC @Throws(CardServiceException::class, GeneralSecurityException::class) constructor(
    private val service: PassportService,
    private val trustStore: CscaTrustStore,
    mrzInfo: MRZInfo,
    maxBlockSize: Int,
    private val timingConfig: NfcReadTimingConfig = NfcReadTimingConfig(),
    private val onStage: (ReadStage) -> Unit = {},
) {

    /** What the chip offers (BAC, PACE, CA, TA, AA), as found during the read. */
    val features = DocumentFeatures()

    /** The verdicts so far; the read flow and [verifySecurity] fill it in. */
    val verificationState = VerificationState()

    /** EF.SOd as JMRTD parsed it; see [securityObjectBytes] for the chip's own bytes. */
    val sodFile: SODFile

    /** The MRZ as the chip holds it. */
    val dg1File: DG1File

    /** The face. */
    val dg2File: DG2File

    // The optional groups: null when EF.SOd does not list them, or they could
    // not be read. DG3 is only asked for after Terminal Authentication.
    val dg3File: DG3File?
    val dg5File: DG5File?
    val dg7File: DG7File?
    val dg11File: DG11File?
    val dg12File: DG12File?

    /** DG13 as the chip stores it: its contents are the issuer's own, so it is not parsed. */
    val dg13Bytes: ByteArray?

    /** DG16's entries; JMRTD has no class for it. */
    val personsToNotify: List<PersonToNotify>?

    /** Active Authentication's public key, for [verifySecurity]; null without AA. */
    private val dg15File: DG15File?

    /** EF.CardSecurity, read only after PACE-CAM, whose chip proof needs the key in it. */
    private var cardSecurity: DataGroupRead? = null

    /** PACE's result when it used Chip Authentication Mapping, checked by [verifySecurity]. */
    private var chipAuthenticationMapping: PACECAMResult? = null

    // What EF.CardAccess offered and what the session used, which [verifySecurity]
    // holds against DG14 once DG14 is known to be signed: EF.CardAccess is not.
    private var paceOffered: List<PACEInfo> = emptyList()
    private var paceUsed: PACEInfo? = null

    init {
        if (!service.isOpen) {
            service.open()
        }
        val paceResult = try {
            openSession(service, mrzInfo, maxBlockSize)
        } catch (cse: CardServiceException) {
            throw cse
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: anything else that stops the session opening is
            // reported as a card fault, with its cause, for the error mapper.
            throw CardServiceException("Cannot open a session with the chip: ${e.javaClass.simpleName}", e)
        }

        onStage(ReadStage.ReadingData)
        // EF.SOd, not EF.COM, says which groups there are: it is the signed list.
        sodFile = readFile(PassportService.EF_SOD, "EF.SOd", maxBlockSize) { SODFile(it) }
        val listed = sodFile.dataGroupHashes.keys.sorted()
        NfcLog.d(TAG, "EF.SOd lists DGs $listed")
        dg1File = readFile(PassportService.EF_DG1, "EF.DG1", maxBlockSize) { DG1File(it) }

        val dg14 = readDg14(maxBlockSize)
        val cvca = readCvca(dg14, maxBlockSize)
        recordAuthenticationSupport(DG14 in listed, dg14, cvca)
        if (features.supportsChipAuthentication) {
            val caResult = doChipAuthentication(service, checkNotNull(dg14))
            if (caResult != null) {
                verificationState.setCa(CheckVerdict.SUCCEEDED, "CA succeeded", caResult)
            } else {
                verificationState.setCa(CheckVerdict.FAILED, "CA could not be completed", null)
            }
        }
        if (paceResult is PACECAMResult) {
            // Checked after Passive Authentication, whose chain it is judged against.
            features.chipAuthentication = FeatureSupport.SUPPORTED
            chipAuthenticationMapping = paceResult
        }
        if (features.chipAuthentication == FeatureSupport.NOT_SUPPORTED) {
            verificationState.setCa(CheckVerdict.NOT_PRESENT, "Not offered by this chip", null)
        }
        doTerminalAuthentication(service, mrzInfo, paceResult, cvca, trustStore.cvcaKeyStores)
        dg15File = readDg15(DG15 in listed, maxBlockSize)

        onStage(ReadStage.ReadingPhoto)
        dg2File = readFile(PassportService.EF_DG2, "EF.DG2", maxBlockSize) { DG2File(it) }

        // DG5 (portrait), DG7 (signature), DG11, DG12, DG13 and DG16 need no access beyond
        // BAC or PACE, and are read only when EF.SOd lists them, so a group the
        // chip does not have costs no APDU.
        //
        // DG3 and DG4 are behind terminal authentication, which needs a terminal
        // certificate from the issuing state. Asking without one is refused, and
        // some chips end secure messaging when they refuse, taking every later
        // read with them, so asking for DG3 can fail the whole read. So
        // they are attempted only after TA has succeeded, which in practice is
        // never; PassiveAuthentication skips their hashes on the same condition.
        dg3File = if (verificationState.eac?.verdict == CheckVerdict.SUCCEEDED) {
            readOptionalDataGroup(3, listed) {
                readFile(PassportService.EF_DG3, "EF.DG3", maxBlockSize) { DG3File(it) }
            }
        } else {
            null
        }
        dg5File = readOptionalDataGroup(5, listed) {
            readFile(PassportService.EF_DG5, "EF.DG5", maxBlockSize) { DG5File(it) }
        }
        dg7File = readOptionalDataGroup(7, listed) {
            readFile(PassportService.EF_DG7, "EF.DG7", maxBlockSize) { DG7File(it) }
        }
        dg11File = readOptionalDataGroup(11, listed) {
            readFile(PassportService.EF_DG11, "EF.DG11", maxBlockSize) { DG11File(it) }
        }
        dg12File = readOptionalDataGroup(12, listed) {
            readFile(PassportService.EF_DG12, "EF.DG12", maxBlockSize) { DG12File(it) }
        }
        dg13Bytes = readOptionalDataGroup(DG13, listed) {
            readRawFile(PassportService.EF_DG13, "EF.DG13", maxBlockSize)
        }
        personsToNotify = readOptionalDataGroup(DG16, listed) {
            parsePersonsToNotify(readRawFile(PassportService.EF_DG16, "EF.DG16", maxBlockSize))
        }
    }

    /**
     * DG14, which holds the Chip Authentication keys; null when the chip has
     * none, refuses it, or it cannot be parsed. Chip and Terminal
     * Authentication then say why they did not run.
     */
    private fun readDg14(maxBlockSize: Int): DG14File? = try {
        readFileIfPresent(PassportService.EF_DG14, "EF.DG14", maxBlockSize) { DG14File(it) }
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Deliberately broad: DG14 is optional; Passive Authentication does not need it.
        NfcLog.w(TAG, "EF.DG14 could not be read or parsed; no Chip Authentication", e)
        null
    }

    /** EF.CVCA, where DG14 says it is; null when absent or unreadable, and TA is then skipped. */
    private fun readCvca(dg14: DG14File?, maxBlockSize: Int): CVCAFile? = try {
        cvcaFileId(dg14).let { fid ->
            readFileIfPresent(fid, "EF.CVCA", maxBlockSize) { CVCAFile(fid, it) }
        }
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Deliberately broad: EF.CVCA is optional; TA is skipped without it.
        NfcLog.w(TAG, "EF.CVCA read failed; continuing without it", e)
        null
    }

    /**
     * What the chip offers beyond BAC and PACE. Chip Authentication: a key in
     * DG14. Terminal Authentication: EF.CVCA, or a TerminalAuthenticationInfo
     * (BSI TR-03110-3 A.1.1.3, A.7.2.4) - DG14 alone says nothing about it, as
     * it also carries Chip Authentication and PACE. When EF.SOd lists a DG14
     * that could not be read, neither can be known.
     */
    private fun recordAuthenticationSupport(dg14Listed: Boolean, dg14: DG14File?, cvca: CVCAFile?) {
        val dg14Unreadable = dg14Listed && dg14 == null
        features.chipAuthentication = when {
            dg14Unreadable -> FeatureSupport.UNKNOWN
            dg14 != null && dg14Listed && dg14.securityInfos.any { it is ChipAuthenticationPublicKeyInfo } ->
                FeatureSupport.SUPPORTED
            else -> FeatureSupport.NOT_SUPPORTED
        }
        if (dg14Unreadable) {
            NfcLog.w(TAG, "EF.SOd lists DG14 but it could not be read; Chip Authentication not attempted")
            verificationState.setCa(CheckVerdict.NOT_CHECKED, "CA not attempted: DG14 unreadable", null)
        }
        features.terminalAuthentication = when {
            cvca != null -> FeatureSupport.SUPPORTED
            dg14?.securityInfos.orEmpty().any { it is TerminalAuthenticationInfo } -> FeatureSupport.SUPPORTED
            dg14Unreadable -> FeatureSupport.UNKNOWN
            else -> FeatureSupport.NOT_SUPPORTED
        }
    }

    /**
     * DG15, Active Authentication's public key, when EF.SOd lists it. Records
     * AA as not present without it, and as not checked when it will not read.
     */
    private fun readDg15(listed: Boolean, maxBlockSize: Int): DG15File? {
        if (!listed) {
            features.activeAuthentication = FeatureSupport.NOT_SUPPORTED
            verificationState.aa = CheckResult(CheckVerdict.NOT_PRESENT, "Not offered by this chip")
            return null
        }
        features.activeAuthentication = FeatureSupport.SUPPORTED
        return try {
            readFile(PassportService.EF_DG15, "EF.DG15", maxBlockSize) { DG15File(it) }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: DG15 is optional; without it AA is reported as not checked.
            val why = when (e) {
                is CardServiceException -> "card error, SW " + (e.statusWord()?.let { "0x%04X".format(it) } ?: "none")
                is IOException -> "I/O error"
                else -> "unexpected error"
            }
            NfcLog.w(TAG, "EF.DG15 could not be read ($why); Active Authentication not checked", e)
            verificationState.aa = CheckResult(CheckVerdict.NOT_CHECKED, "DG15 could not be read ($why)")
            null
        }
    }

    /**
     * The raw contents of EF.SOd, as the chip stores them; set by [verifySecurity].
     * JMRTD's [sodFile] is a parsed copy, whose encoding matched the chip's on
     * both test passports but need not in general.
     */
    var securityObjectBytes: ByteArray? = null
        private set

    /**
     * Passive Authentication over what has been read, then Active
     * Authentication when the chip has DG15. The work is done by
     * [PassiveAuthentication] and [ActiveAuthentication]; this applies their
     * verdicts to [verificationState].
     */
    fun verifySecurity(): VerificationState {
        val source = ChipDocumentSource(service, MAX_BLOCK_SIZE, dg15File?.publicKey)
        securityObjectBytes = (source.readSecurityObject() as? DataGroupRead.Bytes)?.value

        val terminalAuthenticated = verificationState.eac?.verdict == CheckVerdict.SUCCEEDED
        val passive = PassiveAuthentication(trustStore)
            .verify(securityObjectBytes, source, terminalAuthenticated)
        verificationState.ds = passive.ds
        verificationState.setCs(passive.cs.verdict, passive.cs.reason, passive.chain)
        verificationState.setHt(passive.ht.verdict, passive.ht.reason, TreeMap(passive.hashes))
        if (passive.ds.verdict == CheckVerdict.SUCCEEDED && passive.cs.verdict == CheckVerdict.SUCCEEDED) {
            passive.hashes[DG14]?.verifiedContent?.let(::checkCardAccess)
        }
        chipAuthenticationMapping?.let { pace ->
            val sodChained = passive.cs.verdict == CheckVerdict.SUCCEEDED
            recordChipAuthenticationMapping(pace, checkNotNull(cardSecurity) { "read with PACE-CAM" }, sodChained)
        }

        // Without DG15 the read flow has already said why: not present, or not readable.
        if (dg15File != null) {
            val dg15 = (source.readDataGroup(DG15) as? DataGroupRead.Bytes)?.value
            val dg14 = (source.readDataGroup(DG14) as? DataGroupRead.Bytes)?.value
            verificationState.aa = dg15?.let { ActiveAuthentication().verify(it, dg14, source) }
                ?: CheckResult(CheckVerdict.NOT_CHECKED, "DG15 could not be read")
        }

        val hashes = passive.hashes.entries.joinToString(" ") { (group, result) ->
            "DG$group=" + when {
                result.computedHash == null -> "unchecked"
                result.isMatch -> "match"
                else -> "MISMATCH"
            }
        }
        NfcLog.d(
            TAG,
            "verified: ds=${passive.ds.verdict} cs=${passive.cs.verdict} (${passive.chain.size} certificates) " +
                "ht=${passive.ht.verdict} [$hashes] aa=${verificationState.aa?.verdict}",
        )
        return verificationState
    }

    /**
     * Holds EF.CardAccess, which is unsigned, to the PACEInfos in DG14 - see
     * [cardAccessCheck]. Called only with DG14 as Passive Authentication hashed
     * it, and only when EF.SOd's signature and chain both held, so the list is
     * the issuer's. A disagreement fails the PACE check whichever protocol the
     * session used: a chip whose EF.CardAccess hides PACE has been read with BAC.
     */
    private fun checkCardAccess(verifiedDg14: ByteArray) {
        val signed = try {
            DG14File(ByteArrayInputStream(verifiedDg14)).securityInfos.filterIsInstance<PACEInfo>()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: a signed DG14 JMRTD cannot parse leaves nothing to compare with,
            // which must not pass for "DG14 lists no PACE".
            NfcLog.w(TAG, "Signed DG14 could not be parsed; EF.CardAccess not checked", e)
            verificationState.sac = CheckResult(CheckVerdict.NOT_CHECKED, "DG14 could not be parsed")
            return
        }
        if (signed.isNotEmpty()) features.pace = FeatureSupport.SUPPORTED
        when (val check = cardAccessCheck(signed, paceOffered, paceUsed)) {
            CardAccessCheck.NothingToCompare ->
                NfcLog.d(TAG, "EF.CardAccess: DG14 lists no PACE, nothing to compare")
            CardAccessCheck.Matches -> {
                NfcLog.i(TAG, "EF.CardAccess matches DG14 (${signed.size} PACE variant(s))")
                verificationState.sac =
                    CheckResult(CheckVerdict.SUCCEEDED, "PACE completed; EF.CardAccess matches DG14")
            }
            is CardAccessCheck.Mismatch -> {
                NfcLog.w(TAG, "EF.CardAccess does not match DG14: ${check.reason}")
                verificationState.sac = CheckResult(CheckVerdict.FAILED, check.reason)
            }
        }
    }

    ////////////////////////////

    /**
     * Opens secure messaging with the chip and selects the eMRTD application
     * (ICAO 9303-11 §4): PACE when EF.CardAccess offers it, BAC when it does
     * not. A failed PACE is never followed by BAC. That is this library's
     * choice, not ICAO's - chips from the transition years accept both - so
     * that a document offering PACE is not read with the weaker protocol.
     *
     * @return PACE's result, or null when the session was opened with BAC.
     * @throws AccessControlException when PACE or BAC fails.
     */
    private fun openSession(ps: PassportService, mrzInfo: MRZInfo, maxBlockSize: Int): PACEResult? {
        val paceInfos = paceInfosFromCardAccess(ps, maxBlockSize)
        paceOffered = paceInfos
        onStage(ReadStage.Authenticating)
        if (paceInfos.isEmpty()) {
            ps.sendSelectApplet(false)
            authenticateWithBac(ps, mrzInfo)
            return null
        }
        features.pace = FeatureSupport.SUPPORTED
        val result = authenticateWithPace(ps, mrzInfo, paceInfos)
        if (result is PACECAMResult) {
            // In the master file, so read before the eMRTD application is selected.
            cardSecurity = readCardSecurity(ps, maxBlockSize)
        }
        ps.sendSelectApplet(true)
        return result
    }

    /**
     * The PACEInfos in EF.CardAccess. None when the file is absent or cannot
     * be read, which means the chip is read with BAC.
     */
    private fun paceInfosFromCardAccess(ps: PassportService, maxBlockSize: Int): List<PACEInfo> {
        NfcLog.i(TAG, "EF.CardAccess: reading for PACE parameters")
        return try {
            val securityInfos =
                CardAccessFile(ps.getInputStream(PassportService.EF_CARD_ACCESS, maxBlockSize)).securityInfos
            securityInfos.filterIsInstance<PACEInfo>().also { paceInfos ->
                NfcLog.d(PACE, "EF.CardAccess: ${paceInfos.size} variant(s) among ${securityInfos.size} SecurityInfos")
                paceInfos.forEachIndexed { index, info ->
                    val detail = "${info.protocolOIDString}, version ${info.version}, parameter ID ${info.parameterId}"
                    NfcLog.d(PACE, "offered #${index + 1}: $detail")
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: no EF.CardAccess, or an unreadable one, means BAC.
            if (e.statusWord() == ISO7816.SW_FILE_NOT_FOUND.toInt()) {
                NfcLog.i(TAG, "EF.CardAccess: not on this chip, so BAC")
            } else {
                NfcLog.w(TAG, "EF.CardAccess: unreadable (${e.javaClass.simpleName}), so BAC", e)
            }
            emptyList()
        }
    }

    /** PACE, recorded as the access control used. */
    private fun authenticateWithPace(ps: PassportService, mrzInfo: MRZInfo, paceInfos: List<PACEInfo>): PACEResult {
        val startedAt = System.currentTimeMillis()
        val result = try {
            doPACE(ps, mrzInfo, paceInfos)
        } catch (ace: AccessControlException) {
            NfcLog.w(TAG, "PACE did not complete: ${ace.message}", ace)
            throw ace
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // Deliberately broad: whatever stopped PACE before an attempt is still a PACE
            // failure. The class only: JMRTD's refusal of an MRZ field names its value.
            NfcLog.w(TAG, "PACE stopped by ${e.javaClass.simpleName} before an attempt")
            throw accessControlFailure(AccessProtocol.PACE, e)
        }
        NfcLog.i("NFC_PERF", "PACE negotiation took ${System.currentTimeMillis() - startedAt}ms")
        verificationState.sac = CheckResult(CheckVerdict.SUCCEEDED, "PACE completed")
        features.basicAccessControl = FeatureSupport.UNKNOWN
        verificationState.setBac(CheckVerdict.NOT_CHECKED, "Not needed: PACE was used", emptyList())
        return result
    }

    /** BAC, recorded as the access control used, then the eMRTD application selected under it. */
    private fun authenticateWithBac(ps: PassportService, mrzInfo: MRZInfo) {
        features.basicAccessControl = FeatureSupport.SUPPORTED
        val keysTried = mutableListOf<BACKey>()
        try {
            // Inside the try: BACKey refuses a malformed field with a message naming it.
            val bacKey = BACKey(mrzInfo.documentNumber, mrzInfo.dateOfBirth, mrzInfo.dateOfExpiry)
            keysTried += bacKey
            doBAC(ps, bacKey)
            // The key is the MRZ credential; it must not travel in a reason
            // string that callers may display, log or report.
            verificationState.setBac(CheckVerdict.SUCCEEDED, "BAC succeeded", keysTried)
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // Deliberately broad: any failure here is a failed BAC, and the read
            // stops. Carrying on sent EF.SOd without secure messaging, where one
            // chip sat out the whole isoDepTimeoutMs and the read ended as NfcIo.
            // The class only: a BAC failure's message may carry MRZ-derived data,
            // and accessControlFailure drops JMRTD's refusal of an MRZ field entirely.
            val sw = e.statusWord()?.let { "0x%04X".format(it) } ?: "none"
            NfcLog.w(TAG, "BAC failed: ${e.javaClass.simpleName}, step ${e.protocolStep()}, SW $sw")
            // Neutral on purpose: this catch also sees a malformed MRZ field and a
            // lost link, where "refused" would not be true.
            verificationState.setBac(CheckVerdict.FAILED, "BAC could not be completed", keysTried)
            throw accessControlFailure(AccessProtocol.BAC, e)
        }
        // Outside the BAC try: a failure here is not an authentication failure.
        ps.sendSelectApplet(true)
    }

    /**
     * Runs PACE with each protocol EF.CardAccess offered, in order, until one
     * succeeds.
     *
     * @throws AccessControlException when PACE could not be completed.
     */
    private fun doPACE(ps: PassportService, mrzInfo: MRZInfo, paceInfos: List<PACEInfo>): PACEResult {
        NfcLog.d(PACE, "starting, ${paceInfos.size} variant(s) to try")

        val bacKey = BACKey(mrzInfo.documentNumber, mrzInfo.dateOfBirth, mrzInfo.dateOfExpiry)
        val paceKeySpec = PACEKeySpec.createMRZKey(bacKey)
        var lastFailure: Throwable? = null

        for ((index, paceInfo) in paceInfos.withIndex()) {
            if (index > 0) {
                // Let the card reset between protocols.
                Thread.sleep(timingConfig.paceInterProtocolDelayMs)
            }
            tryPaceProtocol(ps, paceKeySpec, paceInfo, protocolNumber = index + 1)
                .onSuccess {
                    paceUsed = paceInfo
                    return it
                }
                .onFailure { lastFailure = it }
        }

        NfcLog.w(PACE, "no variant succeeded")
        throw AccessControlException(
            AccessProtocol.PACE,
            lastFailure?.protocolStep(),
            lastFailure,
            message = "PACE failed with every protocol offered"
        )
    }

    /**
     * Up to [NfcReadTimingConfig.paceMaxRetries] attempts at PACE with one
     * protocol, with exponential backoff, [paceRetryFor] deciding after each
     * failure whether to try again, move on, or stop.
     *
     * @return the result, or the last failure when the next protocol should be tried.
     * @throws AccessControlException when PACE should not be tried any further.
     */
    private fun tryPaceProtocol(
        ps: PassportService,
        paceKeySpec: PACEKeySpec,
        paceInfo: PACEInfo,
        protocolNumber: Int,
    ): Result<PACEResult> {
        NfcLog.d(
            PACE,
            "variant #$protocolNumber: ${paceInfo.protocolOIDString}, parameter ID ${paceInfo.parameterId}"
        )
        val maxAttempts = timingConfig.paceMaxRetries
        var lastFailure: Exception? = null

        for (attempt in 1..maxAttempts) {
            if (attempt > 1) {
                val delayMs = timingConfig.paceRetryBaseDelayMs * (1 shl (attempt - 2))
                NfcLog.d(PACE, "attempt $attempt/$maxAttempts in ${delayMs}ms")
                Thread.sleep(delayMs)
            }
            try {
                val result = ps.doPACE(
                    paceKeySpec,
                    paceInfo.objectIdentifier,
                    PACEInfo.toParameterSpec(paceInfo.parameterId),
                    paceInfo.parameterId
                )
                NfcLog.d(PACE, "succeeded: variant #$protocolNumber, attempt $attempt")
                return Result.success(result)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Deliberately broad: every failure is classified by paceRetryFor, on type and status word.
                lastFailure = e
                val decision = paceRetryFor(e)
                val sw = e.statusWord()?.let { "0x%04X".format(it) } ?: "none"
                NfcLog.e(
                    PACE,
                    "variant #$protocolNumber attempt $attempt/$maxAttempts failed at step " +
                        "${e.protocolStep()}, SW $sw: ${e.message}. Next: $decision"
                )
                if (decision == PaceRetry.Stop) throw AccessControlException(AccessProtocol.PACE, e.protocolStep(), e)
                if (decision == PaceRetry.NextProtocol) break
            }
        }
        return Result.failure(checkNotNull(lastFailure))
    }

    @Throws(CardServiceException::class)
    private fun doBAC(ps: PassportService, bacKey: BACKey): BACResult = ps.doBAC(bacKey)

    /**
     * Chip Authentication (ICAO 9303-11 §6.2) with the keys in DG14, tried in
     * the order [chipAuthenticationPlan] gives. Stops at the first success,
     * which restarts secure messaging with the new session keys (§6.2.2).
     *
     * @return the result, or null when no key and protocol succeeded; secure
     * messaging then carries on with the BAC or PACE keys.
     */
    private fun doChipAuthentication(ps: PassportService, dg14File: DG14File): EACCAResult? {
        val plan = chipAuthenticationPlan(dg14File.securityInfos)
        NfcLog.d(CHIP_AUTH, "DG14 SecurityInfos: " + dg14File.securityInfos.joinToString { it.javaClass.simpleName })
        val attempts = plan.flatMap { key -> key.protocolOids.map { protocolOid -> key to protocolOid } }
        for ((index, attempt) in attempts.withIndex()) {
            val (key, protocolOid) = attempt
            val source = if (key.declared) "declared" else "inferred"
            NfcLog.i(
                CHIP_AUTH,
                "CA attempt #${index + 1}: keyId ${key.keyId}, $source $protocolOid, key ${key.publicKeyOid}"
            )
            try {
                val result = ps.doEACCA(key.keyId, protocolOid, key.publicKeyOid, key.publicKey)
                NfcLog.i(CHIP_AUTH, "Chip Authentication succeeded on attempt #${index + 1}")
                return result
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Deliberately broad: a failed attempt leaves secure messaging as it
                // was (§6.2.2), so the next is tried - unless the passport has gone.
                if (e.isLostTag()) throw e
                val sw = e.statusWord()?.let { "0x%04X".format(it) } ?: "none"
                NfcLog.w(CHIP_AUTH, "CA attempt #${index + 1} failed, SW $sw", e)
            }
        }
        NfcLog.w(CHIP_AUTH, "Chip Authentication did not succeed: ${plan.size} key(s), ${attempts.size} attempt(s)")
        return null
    }

    /** EF.CardSecurity as the chip stores it, or how reading it went wrong. */
    private fun readCardSecurity(ps: PassportService, maxBlockSize: Int): DataGroupRead = try {
        DataGroupRead.Bytes(readRawFile(PassportService.EF_CARD_SECURITY, "EF.CardSecurity", maxBlockSize, ps))
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Deliberately broad: without the file PACE-CAM is not checked, and the
        // read carries on - unless the passport has gone.
        if (e.isLostTag()) throw e
        e.statusWord()?.let { DataGroupRead.Refused(it) } ?: DataGroupRead.Failed(e)
    }

    /**
     * Checks PACE-CAM's proof that the chip is genuine (ICAO 9303-11
     * §4.4.3.5.2) and records it as the chip authentication verdict.
     *
     * Chip Authentication through DG14 may have run as well: terminal
     * authentication needs its result, and §7.1 allows it after PACE-CAM.
     * [ChipAuthenticationMapping.combine] decides which verdict stands.
     */
    private fun recordChipAuthenticationMapping(pace: PACECAMResult, cardSecurity: DataGroupRead, sodChained: Boolean) {
        val mappingKey = (pace.mappingResult as? PACEGMMappingResult)?.piccMappingPublicKey
        val cam = ChipAuthenticationMapping(trustStore)
            .verify(pace.chipAuthenticationData, mappingKey, cardSecurity, sodChained)
        val combined = ChipAuthenticationMapping.combine(cam, dg14 = verificationState.ca)
        verificationState.setCa(combined.verdict, combined.reason, verificationState.caResult)
    }

    /**
     * Terminal Authentication (BSI TR-03110-3, EAC version 1), which is what
     * grants access to DG3 and DG4. It needs an inspection system certificate
     * chain from the issuing state's CVCA, given through
     * [CscaTrustStore.addCvcaKeyStore], and a completed Chip Authentication.
     * Records a verdict whatever happens, saying why when nothing was tried.
     */
    private fun doTerminalAuthentication(
        ps: PassportService,
        mrzInfo: MRZInfo,
        paceResult: PACEResult?,
        cvca: CVCAFile?,
        cvcaKeyStores: List<KeyStore>,
    ) {
        val caResult = verificationState.caResult
        when {
            features.terminalAuthentication == FeatureSupport.NOT_SUPPORTED ->
                verificationState.setEac(CheckVerdict.NOT_PRESENT, "Not offered by this chip", null)
            cvca == null -> eacNotChecked("EF.CVCA could not be read")
            cvcaKeyStores.isEmpty() -> eacNotChecked("No terminal certificate available")
            caResult == null -> eacNotChecked("DG14 chip authentication did not succeed")
            else -> authenticateTerminal(ps, mrzInfo, paceResult, cvca, caResult, cvcaKeyStores)
        }
    }

    private fun eacNotChecked(reason: String) = verificationState.setEac(CheckVerdict.NOT_CHECKED, reason, null)

    @Suppress("LongParameterList") // everything Terminal Authentication depends on, passed rather than re-read
    private fun authenticateTerminal(
        ps: PassportService,
        mrzInfo: MRZInfo,
        paceResult: PACEResult?,
        cvca: CVCAFile,
        caResult: EACCAResult,
        cvcaKeyStores: List<KeyStore>,
    ) {
        val references = listOfNotNull(cvca.caReference, cvca.altCAReference)
        val credentials = try {
            chooseTerminalCredentials(references, terminalKeyEntries(cvcaKeyStores))
        } catch (e: GeneralSecurityException) {
            NfcLog.w(TAG, "Terminal key store could not be read", e)
            eacNotChecked("Terminal key store could not be read")
            return
        }
        if (credentials == null) {
            eacNotChecked("No terminal certificate for this chip's CVCA (${references.joinToString { it.name }})")
            return
        }
        try {
            // JMRTD edits the list it is given, so it gets its own copy.
            val chain = ArrayList(credentials.chain)
            val result = if (paceResult == null) {
                ps.doEACTA(
                    credentials.caReference, chain, credentials.privateKey, null, caResult, mrzInfo.documentNumber
                )
            } else {
                ps.doEACTA(credentials.caReference, chain, credentials.privateKey, null, caResult, paceResult)
            }
            verificationState.setEac(CheckVerdict.SUCCEEDED, "Terminal authentication succeeded", result)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: a refusal is a failed verdict and the read carries on
            // without DG3 and DG4 - unless the passport has gone.
            if (e.isLostTag()) throw e
            val sw = e.statusWord()?.let { "0x%04X".format(it) } ?: "none"
            NfcLog.w(TAG, "Terminal authentication failed, SW $sw", e)
            verificationState.setEac(CheckVerdict.FAILED, "Terminal authentication refused", null)
        }
    }

    /**
     * Reads an optional data group if EF.SOd lists it; null otherwise, or if it
     * cannot be read or parsed. Either way the read carries on - the group is
     * extra detail, and its hash is checked separately by PassiveAuthentication. Every
     * outcome is logged under [NfcLog.DATA_GROUPS].
     */
    private fun <T> readOptionalDataGroup(
        dgNumber: Int,
        listedInSod: Collection<Int>,
        read: () -> T,
    ): T? {
        if (dgNumber !in listedInSod) {
            NfcLog.d(NfcLog.DATA_GROUPS, "DG$dgNumber: absent")
            return null
        }
        return try {
            read().also { NfcLog.i(NfcLog.DATA_GROUPS, "DG$dgNumber: read") }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: an optional group that cannot be read or parsed must not fail the read.
            NfcLog.w(NfcLog.DATA_GROUPS, "DG$dgNumber: failed (${e.javaClass.simpleName})", e)
            null
        }
    }

    /** A file's bytes exactly as the chip stores them, for a group JMRTD does not parse. */
    @Throws(CardServiceException::class, IOException::class)
    private fun readRawFile(fid: Short, label: String, maxBlockSize: Int, ps: PassportService = service): ByteArray =
        readFile(fid, label, maxBlockSize, ps) { it.readBytes() }

    /**
     * EF.CVCA's file identifier: the default, unless DG14's
     * TerminalAuthenticationInfo names another, which then overrides it
     * (BSI TR-03110-3 A.1.1.3 and A.7.2.4).
     */
    private fun cvcaFileId(dg14File: DG14File?): Short =
        dg14File?.securityInfos.orEmpty()
            .filterIsInstance<TerminalAuthenticationInfo>()
            .firstNotNullOfOrNull { info -> info.fileId.takeIf { it > 0 }?.toShort() }
            ?: PassportService.EF_CVCA

    /**
     * Reads one file and parses it with [parse], timing the transfer under
     * NFC_PERF. A failure is logged with the file's name and rethrown as it
     * came.
     */
    @Throws(CardServiceException::class, IOException::class)
    private fun <T> readFile(
        fid: Short,
        name: String,
        maxBlockSize: Int,
        ps: PassportService = service,
        parse: (InputStream) -> T,
    ): T {
        NfcLog.d(TAG, "$name: reading (file %04X)".format(fid.toInt() and 0xFFFF))
        val startedAt = System.currentTimeMillis()
        var input: CardFileInputStream? = null
        try {
            input = ps.getInputStream(fid, maxBlockSize)
            val parsed = parse(input)
            val elapsed = System.currentTimeMillis() - startedAt
            val size = input.length
            val rate = if (size > 0 && elapsed > 0) ", ${size * MS_PER_SECOND / elapsed} B/s" else ""
            NfcLog.i("NFC_PERF", "$name: $size bytes in ${elapsed}ms at block size $maxBlockSize$rate")
            return parsed
        } catch (cse: CardServiceException) {
            // An absent file is routine for optional ones; readFileIfPresent says so, and a
            // required one fails the read with this exception anyway.
            if (cse.sw != ISO7816.SW_FILE_NOT_FOUND.toInt()) {
                NfcLog.w(TAG, "$name: failed, status word ${PassportReadException.statusWordText(cse.sw)}", cse)
            }
            throw cse
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: logged with the file's name, then rethrown unchanged.
            NfcLog.w(TAG, "$name: ${e.javaClass.simpleName} while reading", e)
            throw e
        } finally {
            // A stream that will not close has nothing more to give; the read already stands or fell.
            runCatching { input?.close() }
        }
    }

    /** As [readFile], but null - logged - when the chip has no such file or will not give it up. */
    private fun <T> readFileIfPresent(
        fid: Short,
        name: String,
        maxBlockSize: Int,
        ps: PassportService = service,
        parse: (InputStream) -> T,
    ): T? = try {
        readFile(fid, name, maxBlockSize, ps, parse)
    } catch (cse: CardServiceException) {
        if (cse.sw == ISO7816.SW_FILE_NOT_FOUND.toInt()) {
            NfcLog.i(TAG, "$name: not on this chip")
        } else {
            NfcLog.w(TAG, "$name: refused, ${PassportReadException.statusWordText(cse.sw)}", cse)
        }
        null
    }

    companion object {
        private const val TAG = "PassportNFC"

        /** Tag for which PACE variants the chip offered, each attempt, and the outcome. */
        private const val PACE = "PACE"

        private const val MS_PER_SECOND = 1000L

        /** Tag for Chip Authentication: the DG14 contents, each attempt and its outcome. */
        private const val CHIP_AUTH = "CHIP_AUTH"

        public const val MAX_BLOCK_SIZE:Int= PassportService.DEFAULT_MAX_BLOCKSIZE
        public const val MAX_TRANSCEIVE_LENGTH_FOR_SECURE_MESSAGING:Int= PassportService.NORMAL_MAX_TRANCEIVE_LENGTH
        public const val MAX_TRANSCEIVE_LENGTH_FOR_PACE:Int= PassportService.NORMAL_MAX_TRANCEIVE_LENGTH

        /**
         * Read block size used when the phone supports extended-length APDUs.
         * Chosen well below the 65535 ceiling so secure-messaging overhead can
         * never push a single exchange over the limit.
         */
        public const val EXTENDED_BLOCK_SIZE: Int = 4096

        private const val DG13 = 13
        private const val DG14 = 14
        private const val DG15 = 15
        private const val DG16 = 16
    }

}
