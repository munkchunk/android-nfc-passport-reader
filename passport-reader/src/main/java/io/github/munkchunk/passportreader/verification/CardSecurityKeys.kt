package io.github.munkchunk.passportreader.verification

import io.github.munkchunk.passportreader.crypto.BouncyCastleSupport
import io.github.munkchunk.passportreader.utils.NfcLog
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.spec.ECPublicKeySpec

/**
 * The elliptic-curve Chip Authentication public keys among EF.CardSecurity's
 * SecurityInfos (ICAO 9303-11 §9.2.6, ChipAuthenticationPublicKeyInfo).
 *
 * A chip may name the key's curve the X.509 way, or as a BSI standardized
 * domain parameter ID - an INTEGER under id-standardizedDomainParameters -
 * which is how ICAO's own PACE-CAM worked example does it. JMRTD 0.8.8 cannot
 * decode the second form and returns no key, so both are decoded here.
 */
internal object CardSecurityKeys {

    private val provider = BouncyCastleSupport.provider

    /** id-PK-ECDH: the SecurityInfo holds an ECDH Chip Authentication key. */
    private val ID_PK_ECDH = ASN1ObjectIdentifier("0.4.0.127.0.7.2.2.1.2")

    /** standardizedDomainParameters (ICAO 9303-11 §9.5.1). */
    private val STANDARDIZED_DOMAIN_PARAMETERS = ASN1ObjectIdentifier("0.4.0.127.0.7.1.2")

    /** The elliptic curves among ICAO 9303-11's standardized domain parameter IDs (§9.5.1). */
    @Suppress("MagicNumber") // the IDs are the spec's table
    private val STANDARDIZED_CURVES = mapOf(
        8 to "secp192r1",
        9 to "brainpoolP192r1",
        10 to "secp224r1",
        11 to "brainpoolP224r1",
        12 to "secp256r1",
        13 to "brainpoolP256r1",
        14 to "brainpoolP320r1",
        15 to "secp384r1",
        16 to "brainpoolP384r1",
        17 to "brainpoolP512r1",
        18 to "secp521r1",
    )

    /**
     * Every ECDH key in [securityInfos], the DER SET of SecurityInfos that
     * EF.CardSecurity signs. One that will not decode is logged and skipped,
     * so it cannot cost the others.
     */
    fun ecKeys(securityInfos: ByteArray): List<ECPublicKey> =
        ASN1Set.getInstance(securityInfos).mapNotNull { info ->
            try {
                val sequence = ASN1Sequence.getInstance(info)
                val isEcdhKey = sequence.getObjectAt(0) == ID_PK_ECDH
                if (isEcdhKey) key(SubjectPublicKeyInfo.getInstance(sequence.getObjectAt(1))) else null
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Deliberately broad: a malformed SecurityInfo is skipped, whatever BouncyCastle throws.
                NfcLog.w(TAG, "A SecurityInfo in EF.CardSecurity could not be decoded", e)
                null
            }
        }

    private fun key(info: SubjectPublicKeyInfo): ECPublicKey? {
        val keys = KeyFactory.getInstance("EC", provider)
        val algorithm = info.algorithm
        return if (algorithm.algorithm == STANDARDIZED_DOMAIN_PARAMETERS) {
            val id = ASN1Integer.getInstance(algorithm.parameters).intValueExact()
            STANDARDIZED_CURVES[id]?.let { name ->
                val curve = ECNamedCurveTable.getParameterSpec(name)
                val point = curve.curve.decodePoint(info.publicKeyData.bytes)
                keys.generatePublic(ECPublicKeySpec(point, curve)) as ECPublicKey
            }
        } else {
            keys.generatePublic(X509EncodedKeySpec(info.encoded)) as? ECPublicKey
        }
    }

    private const val TAG = "CHIP_AUTH"
}
