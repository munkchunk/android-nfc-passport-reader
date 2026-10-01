package io.github.munkchunk.passportreader.verification

import io.github.munkchunk.passportreader.crypto.BouncyCastleSupport
import io.github.munkchunk.passportreader.model.CheckResult
import io.github.munkchunk.passportreader.model.CheckVerdict
import io.github.munkchunk.passportreader.utils.NfcLog
import java.math.BigInteger
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.icao.ICAOObjectIdentifiers
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.digests.SHA1Digest
import org.bouncycastle.crypto.digests.SHA224Digest
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.digests.SHA384Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.engines.RSAEngine
import org.bouncycastle.crypto.params.RSAKeyParameters
import org.bouncycastle.crypto.signers.ISO9796d2Signer
import org.bouncycastle.crypto.signers.ISOTrailers
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * Active Authentication, per ICAO Doc 9303 Part 11: shows that the chip holds
 * the private key matching the public key in DG15, so the chip itself was not
 * copied. Passive Authentication cannot tell a genuine chip from a faithful
 * copy of its data; this can.
 *
 * Nothing here talks to NFC directly: the challenge goes through
 * [DocumentSource], which a test can back with a key of its own.
 */
internal class ActiveAuthentication(
    private val random: SecureRandom = SecureRandom(),
) {
    private val provider = BouncyCastleSupport.provider

    /**
     * Runs Active Authentication against the public key in [dg15], the raw
     * contents of EF.DG15.
     *
     * An RSA key signs with ISO/IEC 9796-2 scheme 1. An EC key signs with plain
     * ECDSA, and DG14 names the hash to use, so [dg14] is required for EC.
     */
    fun verify(dg15: ByteArray, dg14: ByteArray?, source: DocumentSource): CheckResult {
        return try {
            val publicKey = parsePublicKey(dg15)
            val challenge = ByteArray(AA_CHALLENGE_LENGTH).also(random::nextBytes)
            when (publicKey) {
                is RSAPublicKey -> verifyRsa(publicKey, challenge, source.activeAuthenticate(challenge))
                is ECPublicKey -> {
                    val algorithm = activeAuthenticationAlgorithm(dg14)
                        ?: return CheckResult(CheckVerdict.FAILED, "No Active Authentication info in EF.DG14")
                    verifyEcdsa(publicKey, algorithm, challenge, source.activeAuthenticate(challenge))
                }
                else -> CheckResult(CheckVerdict.FAILED, "Unsupported Active Authentication key")
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Deliberately broad: a malformed DG15, a refused challenge and a
            // lost tag all end the same way - the chip was not shown to be genuine.
            NfcLog.w(TAG, "Active Authentication could not be completed", e)
            CheckResult(CheckVerdict.FAILED, "AA failed with exception, not completed")
        }
    }

    private fun parsePublicKey(dg15: ByteArray): PublicKey =
        BouncyCastleProvider.getPublicKey(SubjectPublicKeyInfo.getInstance(LdsFile.value(dg15, LdsFile.EF_DG15)))
            ?: throw IllegalArgumentException("EF.DG15 holds an unsupported key type")

    /**
     * RSA Active Authentication: ISO/IEC 9796-2 signature scheme 1 with
     * partial message recovery. The chip signs its own nonce M1, which the
     * signature recovers, followed by our challenge, which it does not.
     *
     * The trailer names the hash: 0xBC means SHA-1 implicitly, otherwise the
     * last two bytes carry an explicit hash identifier.
     */
    private fun verifyRsa(key: RSAPublicKey, challenge: ByteArray, response: ByteArray): CheckResult {
        val recovered = BigInteger(1, response).modPow(key.publicExponent, key.modulus).toByteArray()
        val last = recovered.last().toInt() and BYTE_MASK
        val implicit = last == TRAILER_IMPLICIT
        val digest = if (implicit) {
            SHA1Digest()
        } else {
            val trailer = ((recovered[recovered.size - 2].toInt() and BYTE_MASK) shl Byte.SIZE_BITS) or last
            TRAILER_DIGESTS.map { it() }.firstOrNull { ISOTrailers.getTrailer(it) == trailer }
                ?: return CheckResult(CheckVerdict.FAILED, "Unrecognised ISO 9796-2 trailer %04X".format(trailer))
        }
        val signer = ISO9796d2Signer(RSAEngine(), digest, implicit)
        signer.init(false, RSAKeyParameters(false, key.modulus, key.publicExponent))
        signer.updateWithRecoveredMessage(response)
        signer.update(challenge, 0, challenge.size)
        return if (signer.verifySignature(response)) {
            CheckResult(CheckVerdict.SUCCEEDED, "AA succeeded")
        } else {
            CheckResult(CheckVerdict.FAILED, "AA failed, signature not verified with DG15")
        }
    }

    /** EC Active Authentication: a plain (r || s) ECDSA signature over the challenge. */
    private fun verifyEcdsa(
        key: ECPublicKey,
        algorithm: ASN1ObjectIdentifier,
        challenge: ByteArray,
        response: ByteArray,
    ): CheckResult {
        // BouncyCastle knows the BSI TR-03111 plain-ECDSA identifiers that
        // DG14 uses, so the OID selects the algorithm directly.
        val signature = Signature.getInstance(algorithm.id, provider)
        signature.initVerify(key)
        signature.update(challenge)
        return if (signature.verify(response)) {
            CheckResult(CheckVerdict.SUCCEEDED, "AA succeeded")
        } else {
            CheckResult(CheckVerdict.FAILED, "AA failed, signature not verified with DG15")
        }
    }

    /**
     * The signature algorithm from DG14's ActiveAuthenticationInfo:
     * `SEQUENCE { protocol OID, version INTEGER, signatureAlgorithm OID }`.
     */
    private fun activeAuthenticationAlgorithm(dg14: ByteArray?): ASN1ObjectIdentifier? {
        if (dg14 == null) return null
        val infos = ASN1Set.getInstance(ASN1Primitive.fromByteArray(LdsFile.value(dg14, LdsFile.EF_DG14)))
        return infos.objects.asSequence()
            .mapNotNull { it as? ASN1Sequence }
            .filter { it.size() >= AA_INFO_FIELDS }
            .filter { it.getObjectAt(0) == ICAOObjectIdentifiers.id_icao_aaProtocolObject }
            .onEach { info ->
                val version = ASN1Integer.getInstance(info.getObjectAt(1)).value
                if (version != BigInteger.ONE) NfcLog.w(TAG, "ActiveAuthenticationInfo version $version, expected 1")
            }
            .map { ASN1ObjectIdentifier.getInstance(it.getObjectAt(2)) }
            .firstOrNull()
    }

    companion object {
        private const val TAG = "ActiveAuthentication"

        private const val AA_CHALLENGE_LENGTH = 8
        private const val AA_INFO_FIELDS = 3
        private const val BYTE_MASK = 0xFF
        private const val TRAILER_IMPLICIT = 0xBC

        /** The hashes ISO/IEC 9796-2 names by trailer, matched through BouncyCastle's own table. */
        private val TRAILER_DIGESTS: List<() -> Digest> = listOf(
            ::SHA1Digest, ::SHA224Digest, ::SHA256Digest, ::SHA384Digest, ::SHA512Digest, ::RIPEMD160Digest,
        )
    }
}
