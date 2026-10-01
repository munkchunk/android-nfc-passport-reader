package io.github.munkchunk.passportreader

import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Environment
import io.github.munkchunk.passportreader.data.Passport
import io.github.munkchunk.passportreader.model.error.NfcErrorReporter
import io.github.munkchunk.passportreader.model.error.PassportReadException
import io.github.munkchunk.passportreader.model.error.toPassportReadException
import io.github.munkchunk.passportreader.trust.CscaCertificates
import io.github.munkchunk.passportreader.mapping.bacKeyMrzInfo
import java.io.File
import io.github.munkchunk.passportreader.utils.ChipReader
import io.github.munkchunk.passportreader.reader.ReadStage
import io.github.munkchunk.passportreader.timing.NfcReadTimingConfig
import io.github.munkchunk.passportreader.utils.NfcLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import net.sf.scuba.smartcards.CardServiceException
import io.github.munkchunk.passportreader.crypto.BouncyCastleSupport
import io.github.munkchunk.passportreader.model.CheckResult
import io.github.munkchunk.passportreader.trust.CscaTrustStore

/**
 * Reads a passport chip from a [Tag] the caller already has. For the whole
 * flow - waiting for the passport, retries, progress as a state flow - use
 * [io.github.munkchunk.passportreader.reader.NfcPassportReaderManager], which
 * is built on this.
 *
 * ```
 * val reader = NfcPassportReader(context)
 * if (reader.isNfcAvailable() && reader.isNfcEnabled()) {
 *     val result = reader.readPassport(tag, documentNumber, dateOfBirth, dateOfExpiry)
 * }
 * ```
 */
class NfcPassportReader(
    private val context: Context,
    private val errorReporter: NfcErrorReporter? = null,
    private val timingConfig: NfcReadTimingConfig = NfcReadTimingConfig()
) {
    
    /** Null on a device with no NFC hardware. */
    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(context)

    /**
     * Building the trust store parses several hundred PEM certificates and
     * takes roughly 0.7s warm, 2.2s cold. It depends only on bundled assets -
     * not on the tag or the MRZ - so it is started here rather than when a
     * passport appears, and is almost always ready by the time someone has
     * positioned their phone.
     */
    private val trustStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val trustStoreDeferred: Deferred<CscaTrustStore> =
        trustStoreScope.async { setupTrustStore() }

    init {
        // Personal data reaches the log only if the *consuming* app is
        // debuggable. See NfcLog.allowPersonalData.
        NfcLog.configureFrom(context)
    }

    companion object {
        private const val TAG = "NfcPassportReader"

        /** Self-signed CSCA certificates, the trust anchors; see tools/update-csca-masterlist.sh. */
        private const val TRUST_ANCHORS_ASSET = "csca/trust-anchors.pem"

        /** CSCA link certificates, for building paths only. */
        private const val LINK_CERTIFICATES_ASSET = "csca/link-certificates.pem"

        /** A consuming app's own CSCA certificates; see [addAppSuppliedCertificates]. */
        private const val APP_KEY_STORE_FILE = "csca-certificates.p12"
    }

    /** Whether the device has NFC hardware at all. */
    fun isNfcAvailable(): Boolean = adapter != null

    /** Whether NFC is switched on; false when there is no NFC hardware. */
    fun isNfcEnabled(): Boolean = adapter?.isEnabled == true

    /**
     * Read passport chip using NFC tag and MRZ information.
     *
     * This function:
     * 1. Sets up CSCA trust store with certificates
     * 2. Calls ChipReader to read and authenticate the chip
     * 3. Returns Passport object with all data and verification status
     *
     * @param tag The NFC tag detected from the passport chip
     * @param passportNumber Passport number from MRZ (e.g., "123456789")
     * @param dateOfBirth Date of birth from MRZ in YYMMDD format (e.g., "900101")
     * @param expiryDate Expiry date from MRZ in YYMMDD format (e.g., "301231")
     * @return the passport, or a [PassportReadException] saying why the read
     *   failed. Nothing is thrown except CancellationException when the
     *   caller is cancelled.
     */
    suspend fun readPassport(
        tag: Tag,
        passportNumber: String,
        dateOfBirth: String,
        expiryDate: String,
        onStage: (ReadStage) -> Unit = {}
    ): Result<Passport> {
        val trustStoreStartedAt = System.currentTimeMillis()
        val trustStore = trustStoreDeferred.await()
        val waitedForTrustStore = System.currentTimeMillis() - trustStoreStartedAt
        NfcLog.i("NFC_PERF", "Waited ${waitedForTrustStore}ms for CSCA trust store")

        return readPassportWith(trustStore, tag, passportNumber, dateOfBirth, expiryDate, onStage)
    }

    private suspend fun readPassportWith(
        trustStore: CscaTrustStore,
        tag: Tag,
        passportNumber: String,
        dateOfBirth: String,
        expiryDate: String,
        onStage: (ReadStage) -> Unit
    ): Result<Passport> {
        NfcLog.d(TAG, "Read started")
        NfcLog.personal(TAG) { "MRZ: number=$passportNumber, dob=$dateOfBirth, expiry=$expiryDate" }

        // Everything after the MRZ talks to the chip and blocks for seconds, so
        // it runs on the IO dispatcher. runInterruptible makes cancelling the
        // caller interrupt the read, rather than leave it running unseen.
        return try {
            val mrzInfo = bacKeyMrzInfo(passportNumber, dateOfBirth, expiryDate)
            val passport = withContext(Dispatchers.IO) {
                runInterruptible { ChipReader().read(tag, mrzInfo, trustStore, timingConfig, onStage) }
            }
            NfcLog.d(TAG, "Read finished")
            logSummary(passport)
            Result.success(passport)
        } catch (e: PassportReadException) {
            // Only the refused MRZ above: no chip was touched, so nothing to report.
            NfcLog.w(TAG, e.message.orEmpty())
            Result.failure(e)
        } catch (e: CancellationException) {
            NfcLog.d(TAG, "Passport read cancelled")
            throw e
        } catch (e: CardServiceException) {
            failed("CARD", e)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: the library's top-level boundary; every failure becomes a PassportReadException.
            failed("GENERAL", e)
        }
    }

    /**
     * [e] as a PassportReadException, logged and reported to [errorReporter]
     * under [where] on the main thread.
     *
     * A cancelled caller gets CancellationException instead, and nothing is
     * reported. Cancelling interrupts the read, and a catch inside it can wrap
     * that interrupt - an interrupted PACE retry comes out as a PACE failure -
     * but a read the user abandoned has not failed.
     */
    private suspend fun failed(where: String, e: Exception): Result<Passport> {
        if (!currentCoroutineContext().isActive) {
            // Usually the tag being released on cancel: the next APDU fails "Not connected".
            NfcLog.d(TAG, "Passport read cancelled; ${e.javaClass.simpleName} after it not reported")
        }
        currentCoroutineContext().ensureActive()
        val ex = e.toPassportReadException()
        NfcLog.e(TAG, "Passport read failed at $where: ${ex::class.simpleName}", ex)
        ex.technicalDetails?.let { NfcLog.w(TAG, it) }
        errorReporter?.let { reporter ->
            withContext(Dispatchers.Main) {
                // A reporter that throws must not replace the failure it was given.
                runCatching { reporter.report(where, ex) }
                    .onFailure { NfcLog.w(TAG, "Error reporter threw ${it.javaClass.simpleName}", it) }
            }
        }
        return Result.failure(ex)
    }



    /**
     * The trust store passive authentication checks against: the bundled CSCA
     * certificates (the German BSI master list, see tools/update-csca-masterlist.sh),
     * then any a consuming app supplies in a key store.
     */
    private fun setupTrustStore(): CscaTrustStore {
        // Registered explicitly rather than relying on whichever class happens
        // to be loaded first.
        BouncyCastleSupport.install()

        val trustStore = CscaTrustStore()

        addBundledCertificates(trustStore)
        addAppSuppliedCertificates(trustStore)
        return trustStore
    }

    /**
     * The CSCA bundles shipped in the library's assets: self-signed roots as
     * anchors, roots and link certificates to build paths through. Either
     * bundle failing to read or parse leaves the store without both, and every
     * chain check then fails with no trusted CSCA - logged here.
     */
    private fun addBundledCertificates(trustStore: CscaTrustStore) {
        runCatching {
            val readAsset = { path: String ->
                context.assets.open(path).use { it.readBytes().toString(Charsets.US_ASCII) }
            }
            val anchors = CscaCertificates.fromPem(readAsset(TRUST_ANCHORS_ASSET))
            val links = CscaCertificates.fromPem(readAsset(LINK_CERTIFICATES_ASSET))
            val (anchored, linked) = CscaCertificates.addBundles(trustStore, anchors, links)
            NfcLog.d(TAG, "Trust store: $anchored bundled anchors, $linked link certificates")
        }.onFailure {
            NfcLog.w(TAG, "Bundled CSCA certificates could not be loaded; only app-supplied anchors remain", it)
        }
    }

    /**
     * CSCA certificates a consuming app has placed in a PKCS12 key store named
     * [APP_KEY_STORE_FILE], with an empty password, in its external files
     * Downloads directory. Each is added as an anchor and to build paths
     * through. Absent is normal; unreadable is logged, and the bundles stand.
     */
    private fun addAppSuppliedCertificates(trustStore: CscaTrustStore) {
        runCatching {
            val file = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?.let { File(it, APP_KEY_STORE_FILE) }
            val keyStore = file?.let { CscaCertificates.keyStore(it, CharArray(0)) }
            if (keyStore == null) {
                NfcLog.d(TAG, "Trust store: no app-supplied key store")
                return
            }
            val added = CscaCertificates.addKeyStore(trustStore, keyStore)
            NfcLog.d(TAG, "Trust store: $added app-supplied certificates")
        }.onFailure {
            NfcLog.w(TAG, "App-supplied key store could not be read; bundled certificates only", it)
        }
    }

    /**
     * One block per read, from the report the caller receives rather than the
     * read path's working state, so the log shows what the caller sees. Names
     * and the document number only via [NfcLog.personal].
     */
    private fun logVerdict(check: String, result: CheckResult) {
        val reason = result.reason?.let { " ($it)" }.orEmpty()
        NfcLog.d(TAG, "$check: ${result.verdict}$reason")
    }

    private fun logSummary(passport: Passport) {
        passport.features?.let { f ->
            NfcLog.d(
                TAG,
                "Offered: PACE ${f.pace}, BAC ${f.basicAccessControl}, CA ${f.chipAuthentication}, " +
                    "AA ${f.activeAuthentication}, TA ${f.terminalAuthentication}"
            )
        }
        passport.verificationReport?.let { report ->
            listOf(
                "BAC" to report.basicAccessControl, "PACE" to report.pace,
                "CA" to report.chipAuthentication, "AA" to report.activeAuthentication,
                "TA" to report.terminalAuthentication, "signer" to report.documentSignature,
                "chain" to report.certificateChain, "hashes" to report.dataGroupHashes,
            ).forEach { (check, result) -> result?.let { logVerdict(check, it) } }
            report.chainCertificates?.let { NfcLog.d(TAG, "chain length ${it.size}") }
            report.hashes?.let { hashes ->
                val each = hashes.joinToString(" ") {
                    val outcome = when {
                        !it.compared -> "unchecked"
                        it.matches -> "ok"
                        else -> "MISMATCH"
                    }
                    "DG${it.dataGroup}=$outcome"
                }
                NfcLog.d(TAG, "hashes: $each")
            }
        }
        passport.personDetails?.let { person ->
            NfcLog.personal(TAG) {
                "Holder: ${person.primaryIdentifier} ${person.secondaryIdentifier}, " +
                    "${person.nationality}, ${person.documentNumber}"
            }
        }
        NfcLog.d(
            TAG,
            "Images: face=${passport.face != null} portrait=${passport.portrait != null} " +
                "signature=${passport.signature != null} fingerprints=${passport.fingerprints?.size ?: 0}"
        )
    }
}
