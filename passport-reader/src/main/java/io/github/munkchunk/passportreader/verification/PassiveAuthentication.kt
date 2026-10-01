package io.github.munkchunk.passportreader.verification

import io.github.munkchunk.passportreader.crypto.BouncyCastleSupport
import io.github.munkchunk.passportreader.model.CheckResult
import io.github.munkchunk.passportreader.model.CheckVerdict
import io.github.munkchunk.passportreader.trust.CscaTrustStore
import io.github.munkchunk.passportreader.utils.NfcLog
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Date
import java.util.TreeMap
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.icao.ICAOObjectIdentifiers
import org.bouncycastle.asn1.icao.LDSSecurityObject
import org.bouncycastle.cms.CMSSignedData

/**
 * Passive Authentication, per ICAO Doc 9303 Part 11: establishes that the data
 * groups are the ones the issuing state signed.
 *
 * - **Document signer (DS)**: EF.SOd is a CMS SignedData (RFC 5652) whose
 *   content is an LDS Security Object. Its one signature verifies under the
 *   document signer certificate it carries. That includes the `messageDigest`
 *   signed attribute matching the content, so the list of hashes is covered
 *   by the signature.
 * - **Certificate chain (CS)**: the document signer certificate chains to a
 *   CSCA certificate in [trustStore].
 * - **Hashes (HT)**: each data group, read raw from the chip, hashes to the
 *   value the Security Object lists for it.
 *
 * Nothing here talks to NFC directly. What it needs from the chip comes
 * through [DocumentSource], which a test can back with stored bytes.
 *
 * @param now the time certificates must be valid at
 */
internal class PassiveAuthentication(
    private val trustStore: CscaTrustStore,
    private val now: () -> Date = { Date() },
) {
    private val provider = BouncyCastleSupport.provider
    private val documentSignature = DocumentSignature(trustStore, now)

    /**
     * Checks the document signer, the certificate chain and every data group
     * hash in [sod], the raw contents of EF.SOd.
     *
     * @param terminalAuthenticated whether terminal authentication succeeded.
     *   Without it DG3 and DG4 are never requested - see [TERMINAL_AUTH_GROUPS].
     */
    fun verify(
        sod: ByteArray?,
        source: DocumentSource,
        terminalAuthenticated: Boolean,
    ): PassiveAuthResult {
        val securityObject = try {
            parseSecurityObject(sod ?: throw IllegalArgumentException("no EF.SOd"))
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: a malformed EF.SOd can make BouncyCastle throw
            // anything, and every outcome is the same - nothing can be verified.
            NfcLog.w(TAG, "EF.SOd could not be parsed", e)
            return PassiveAuthResult.unverifiable("EF.SOd could not be parsed")
        }

        val signature = documentSignature.verify(securityObject.signed, "EF.SOd")
        val hashes = checkHashes(securityObject.lds, source, terminalAuthenticated)
        return PassiveAuthResult(
            ds = signature.ds,
            cs = signature.cs,
            chain = signature.chain,
            ht = hashes.result,
            hashes = hashes.matches,
        )
    }

    // ---------------------------------------------------------------- EF.SOd

    private class SecurityObject(val signed: CMSSignedData, val lds: LDSSecurityObject)

    private fun parseSecurityObject(sod: ByteArray): SecurityObject {
        val contentInfo = ContentInfo.getInstance(ASN1Primitive.fromByteArray(LdsFile.value(sod, LdsFile.EF_SOD)))
        val signed = CMSSignedData(contentInfo)
        require(signed.signedContentType == ICAOObjectIdentifiers.id_icao_ldsSecurityObject) {
            "EF.SOd content is ${signed.signedContentType}, not an LDS Security Object"
        }
        val content = signed.signedContent?.content as? ByteArray
            ?: throw IllegalArgumentException("EF.SOd has no encapsulated content")
        return SecurityObject(signed, LDSSecurityObject.getInstance(ASN1Primitive.fromByteArray(content)))
    }

    // ---------------------------------------------------------------- hashes

    private class HashCheck(val result: CheckResult, val matches: Map<Int, HashMatchResult>)

    /**
     * Compares each data group hash listed in [lds] with one computed from the
     * data group as the chip stores it.
     *
     * A mismatch in any group fails the check. A group that cannot be read
     * fails it only if it is one of [CRITICAL_GROUPS], or if the chip refused
     * it for a reason other than access or absence. An optional group the chip
     * will not give up is recorded with no computed hash and is not a failure:
     * the verdict covers the data the reader actually has.
     */
    private fun checkHashes(
        lds: LDSSecurityObject,
        source: DocumentSource,
        terminalAuthenticated: Boolean,
    ): HashCheck {
        val digest = try {
            MessageDigest.getInstance(lds.digestAlgorithmIdentifier.algorithm.id, provider)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: an unknown or malformed algorithm identifier
            // can surface as several exception types.
            NfcLog.w(TAG, "Unsupported data group hash algorithm", e)
            return HashCheck(CheckResult(CheckVerdict.FAILED, "Unsupported hash algorithm"), emptyMap())
        }

        val matches = TreeMap<Int, HashMatchResult>()
        var failure: String? = null
        for (entry in lds.datagroupHash) {
            val number = entry.dataGroupNumber
            val stored = entry.dataGroupHashValue.octets
            val (match, problem) = checkHash(number, stored, digest, source, terminalAuthenticated)
            matches[number] = match
            if (problem != null) {
                NfcLog.w(TAG, problem)
                failure = failure ?: problem
            }
        }
        val result = failure?.let { CheckResult(CheckVerdict.FAILED, it) }
            ?: CheckResult(CheckVerdict.SUCCEEDED, "Data Group hashes match")
        return HashCheck(result, matches)
    }

    /** One data group's hash, and what is wrong with it if anything is. */
    private fun checkHash(
        number: Int,
        stored: ByteArray,
        digest: MessageDigest,
        source: DocumentSource,
        terminalAuthenticated: Boolean,
    ): Pair<HashMatchResult, String?> {
        val unchecked = HashMatchResult(stored, null)
        if (number in TERMINAL_AUTH_GROUPS && !terminalAuthenticated) {
            // Never requested: asking without terminal authentication is
            // refused, and some chips end secure messaging when they refuse.
            return unchecked to null
        }
        return when (val read = source.readDataGroup(number)) {
            is DataGroupRead.Bytes -> {
                val match = HashMatchResult(stored, digest.digest(read.value), read.value)
                match to if (match.isMatch) null else "Hash mismatch for DG$number"
            }
            is DataGroupRead.Refused -> {
                val expected = read.statusWord in UNAVAILABLE_STATUS_WORDS
                val fails = number in CRITICAL_GROUPS || !expected
                unchecked to if (fails) "DG$number refused (SW %04X)".format(read.statusWord) else null
            }
            is DataGroupRead.Failed ->
                unchecked to if (number in CRITICAL_GROUPS) "DG$number could not be read" else null
        }
    }

    companion object {
        private const val TAG = "PassiveAuthentication"

        /** Groups whose hash must verify for the document to pass. */
        val CRITICAL_GROUPS = setOf(1, 2, 14, 15)

        /** Behind terminal authentication, which this library cannot perform. */
        val TERMINAL_AUTH_GROUPS = setOf(3, 4)

        /** Security status not satisfied, conditions of use not satisfied, file not found. */
        private val UNAVAILABLE_STATUS_WORDS = setOf(0x6982, 0x6986, 0x6A82)
    }
}

/**
 * The three Passive Authentication verdicts, with the chain and per-group
 * hashes they rest on.
 */
internal class PassiveAuthResult(
    val ds: CheckResult,
    val cs: CheckResult,
    val chain: List<X509Certificate>,
    val ht: CheckResult,
    val hashes: Map<Int, HashMatchResult>,
) {
    companion object {
        fun unverifiable(reason: String): PassiveAuthResult {
            val failed = CheckResult(CheckVerdict.FAILED, reason)
            return PassiveAuthResult(failed, failed, emptyList(), failed, emptyMap())
        }
    }
}
