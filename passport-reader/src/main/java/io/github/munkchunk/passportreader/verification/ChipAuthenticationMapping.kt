package io.github.munkchunk.passportreader.verification

import io.github.munkchunk.passportreader.model.CheckResult
import io.github.munkchunk.passportreader.model.CheckVerdict
import io.github.munkchunk.passportreader.trust.CscaTrustStore
import io.github.munkchunk.passportreader.utils.NfcLog
import java.math.BigInteger
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.util.Date
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.jcajce.provider.asymmetric.util.ECUtil

/**
 * The chip-authentication half of PACE with Chip Authentication Mapping
 * (ICAO 9303-11 §4.4.3.5): proof that the chip holds the private key the
 * issuer signed for it, so it is not a clone.
 *
 * During PACE-CAM the chip sends its Chip Authentication Data,
 * CA_IC = SK_IC⁻¹ · SK_Map,IC mod n, encrypted under the session keys; JMRTD
 * decrypts it. The chip is genuine when PK_Map,IC = CA_IC · PK_IC
 * (§4.4.3.5.2), where PK_Map,IC is the public key the chip sent in PACE's
 * mapping step and PK_IC its static Chip Authentication key. Only a chip
 * holding SK_IC can produce a CA_IC that makes the two equal, and PK_Map,IC is
 * fresh for each session, so an answer cannot be replayed.
 *
 * PK_IC comes from EF.CardSecurity, which is only worth anything if the
 * issuer signed it: §4.4.3.5.2 requires Passive Authentication alongside, so
 * its signature and certificate chain are checked first, as EF.SOd's are -
 * the chain verdict judged against EF.SOd's - and a key from an
 * EF.CardSecurity that fails either is never used.
 *
 * DG14 should hold the same key (§9.2), but chip authentication (§6, item 3)
 * names EF.CardSecurity for PACE-CAM, so that is the file used.
 */
internal class ChipAuthenticationMapping(
    trustStore: CscaTrustStore,
    now: () -> Date = { Date() },
) {
    private val documentSignature = DocumentSignature(trustStore, now)

    /**
     * @param chipAuthenticationData CA_IC as JMRTD decrypted it; null when the
     *   chip sent none or it could not be decrypted
     * @param mappingKey PK_Map,IC
     * @param cardSecurity EF.CardSecurity as read from the chip
     * @param sodChained whether EF.SOd chained to a trusted CSCA. If it did,
     *   an EF.CardSecurity from the same issuer must too, and one that does
     *   not is FAILED: otherwise a clone could sign its own key with a home-made
     *   document signer and be reported as merely unchecked.
     * @return SUCCEEDED when the chip proved its key, FAILED when it answered
     *   with data its signed key does not account for, NOT_CHECKED when
     *   something needed for the check was missing or could not be trusted
     */
    fun verify(
        chipAuthenticationData: ByteArray?,
        mappingKey: PublicKey?,
        cardSecurity: DataGroupRead,
        sodChained: Boolean,
    ): CheckResult {
        val bytes = (cardSecurity as? DataGroupRead.Bytes)?.value
        return when {
            chipAuthenticationData == null -> notChecked("PACE-CAM: no chip authentication data")
            mappingKey !is ECPublicKey -> notChecked("PACE-CAM: no elliptic-curve mapping key")
            cardSecurity is DataGroupRead.Refused ->
                notChecked("PACE-CAM: EF.CardSecurity refused (SW %04X)".format(cardSecurity.statusWord))
            bytes == null -> notChecked("PACE-CAM: EF.CardSecurity could not be read")
            else -> when (val keys = trustedKeys(bytes, sodChained)) {
                is SignedKeys.Trusted -> checkAgainstSafely(keys.keys, chipAuthenticationData, mappingKey)
                is SignedKeys.Untrusted -> keys.result
            }
        }
    }

    private fun trustedKeys(cardSecurity: ByteArray, sodChained: Boolean): SignedKeys = try {
        signedKeys(cardSecurity, sodChained)
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        // Deliberately broad: BouncyCastle and JMRTD can throw anything on a
        // malformed file, and every outcome is the same - no key to trust.
        NfcLog.w(TAG, "EF.CardSecurity could not be parsed", e)
        SignedKeys.Untrusted(notChecked("PACE-CAM: EF.CardSecurity could not be parsed"))
    }

    private sealed interface SignedKeys {
        class Trusted(val keys: List<ECPublicKey>) : SignedKeys
        class Untrusted(val result: CheckResult) : SignedKeys
    }

    /**
     * The keys in [cardSecurity] once its signature and chain hold. A signature
     * that does not verify is a FAILED check, as it is for EF.SOd: the file is
     * not what the issuer signed. A chain that cannot be built is FAILED when
     * EF.SOd's could be, since both come from the same issuer, and NOT_CHECKED
     * when neither could - a country with no trust anchor, which says nothing
     * about the chip.
     */
    private fun signedKeys(cardSecurity: ByteArray, sodChained: Boolean): SignedKeys {
        val signed = CMSSignedData(cardSecurity)
        val signature = signed.takeIf { it.signedContentType == SECURITY_OBJECT }
            ?.let { documentSignature.verify(it, "EF.CardSecurity") }
        return when {
            // A chip that did PACE-CAM must carry one; a genuine one never serves something else.
            signature == null && sodChained ->
                SignedKeys.Untrusted(failed("PACE-CAM: EF.CardSecurity is not a security object"))
            signature == null ->
                SignedKeys.Untrusted(notChecked("PACE-CAM: EF.CardSecurity is not a security object"))
            signature.ds.verdict != CheckVerdict.SUCCEEDED ->
                SignedKeys.Untrusted(failed("PACE-CAM: ${signature.ds.reason}"))
            signature.cs.verdict != CheckVerdict.SUCCEEDED && sodChained ->
                SignedKeys.Untrusted(failed("PACE-CAM: EF.CardSecurity does not chain, though EF.SOd does"))
            signature.cs.verdict != CheckVerdict.SUCCEEDED ->
                SignedKeys.Untrusted(notChecked("PACE-CAM: EF.CardSecurity: ${signature.cs.reason}"))
            else -> CardSecurityKeys.ecKeys(signed.signedContent.content as ByteArray)
                .takeIf { it.isNotEmpty() }
                ?.let { SignedKeys.Trusted(it) }
                ?: SignedKeys.Untrusted(notChecked("PACE-CAM: no EC key in EF.CardSecurity"))
        }
    }

    private fun checkAgainstSafely(keys: List<ECPublicKey>, data: ByteArray, mappingKey: ECPublicKey): CheckResult =
        try {
            checkAgainst(keys, data, mappingKey)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: a key BouncyCastle cannot convert must not fail the read.
            NfcLog.w(TAG, "PACE-CAM keys could not be compared", e)
            notChecked("PACE-CAM: keys could not be compared")
        }

    private fun checkAgainst(keys: List<ECPublicKey>, data: ByteArray, mappingKey: ECPublicKey): CheckResult {
        val match = keys.indexOfFirst { mappingKeyMatches(data, mappingKey, it) }
        val outcome = if (match < 0) "none matched" else "#${match + 1} matched"
        NfcLog.i(TAG, "PACE-CAM: ${keys.size} signed key(s), $outcome")
        return if (match >= 0) {
            CheckResult(CheckVerdict.SUCCEEDED, "Chip authenticated by PACE-CAM")
        } else {
            failed("PACE-CAM: chip did not prove its signed key")
        }
    }

    private fun failed(reason: String): CheckResult {
        NfcLog.w(TAG, reason)
        return CheckResult(CheckVerdict.FAILED, reason)
    }

    private fun notChecked(reason: String): CheckResult {
        NfcLog.w(TAG, reason)
        return CheckResult(CheckVerdict.NOT_CHECKED, reason)
    }

    companion object {
        /** The same tag as Chip Authentication through DG14: both answer "is this chip genuine?". */
        private const val TAG = "CHIP_AUTH"

        /** id-SecurityObject, BSI TR-03110-3 A.1.2.2: EF.CardSecurity's signed content type. */
        private val SECURITY_OBJECT = ASN1ObjectIdentifier("0.4.0.127.0.7.3.2.1")

        /**
         * The chip authentication verdict when PACE-CAM was used and DG14's
         * Chip Authentication may have run too.
         *
         * A PACE-CAM failure stands whatever DG14 said: the chip could not prove
         * the key the issuer signed for it. A PACE-CAM that could not be checked
         * leaves DG14's verdict standing, if there is one. A success says
         * whether DG14's agreed, when DG14's was attempted at all.
         *
         * @param dg14 DG14's verdict; null when none was recorded
         */
        fun combine(cam: CheckResult, dg14: CheckResult?): CheckResult = when {
            dg14 == null || cam.verdict == CheckVerdict.FAILED -> cam
            cam.verdict == CheckVerdict.NOT_CHECKED -> dg14
            dg14.verdict == CheckVerdict.SUCCEEDED ->
                CheckResult(CheckVerdict.SUCCEEDED, "Chip authenticated by PACE-CAM and DG14")
            dg14.verdict == CheckVerdict.FAILED ->
                CheckResult(CheckVerdict.SUCCEEDED, "Chip authenticated by PACE-CAM; DG14 chip authentication failed")
            // DG14 was never attempted, so it has nothing to add.
            else -> cam
        }

        /**
         * Whether PK_Map,IC = CA_IC · PK_IC (ICAO 9303-11 §4.4.3.5.2). False,
         * never an exception, for keys on different curves or a CA_IC outside
         * 1 until n: a zero would make any key match a point at infinity.
         */
        fun mappingKeyMatches(
            chipAuthenticationData: ByteArray,
            mappingKey: ECPublicKey,
            staticKey: ECPublicKey,
        ): Boolean {
            val mapping = ECUtil.generatePublicKeyParameter(mappingKey) as ECPublicKeyParameters
            val static = ECUtil.generatePublicKeyParameter(staticKey) as ECPublicKeyParameters
            val domain = static.parameters
            val ca = BigInteger(1, chipAuthenticationData)
            return mapping.parameters.curve == domain.curve && mapping.parameters.g == domain.g &&
                ca.signum() > 0 && ca < domain.n &&
                static.q.multiply(ca).normalize() == mapping.q.normalize()
        }
    }
}
