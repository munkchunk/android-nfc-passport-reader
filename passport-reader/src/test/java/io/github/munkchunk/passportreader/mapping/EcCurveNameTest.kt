package io.github.munkchunk.passportreader.mapping

import java.io.File
import java.math.BigInteger
import java.util.Base64
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.teletrust.TeleTrusTObjectIdentifiers
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x9.ECNamedCurveTable
import org.bouncycastle.asn1.x9.X9ECParameters
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers
import org.bouncycastle.cert.X509CertificateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Naming the curve of an EC certificate key.
 *
 * ECParameterSpec.toString() is an object identity on Android, such as
 * `java.security.spec.ECParameterSpec@...`, so the name is read from the
 * encoding instead. ICAO
 * certificates may name the curve by OID or spell out its parameters, so
 * both encodings are covered, plus a real CSCA certificate.
 */
class EcCurveNameTest {

    private val brainpool = ECNamedCurveTable.getByName("brainpoolP256r1")

    private fun ecKey(parameters: Any): ByteArray = SubjectPublicKeyInfo(
        AlgorithmIdentifier(X9ObjectIdentifiers.id_ecPublicKey, parameters as org.bouncycastle.asn1.ASN1Encodable),
        brainpool.g.getEncoded(false),
    ).encoded

    @Test
    fun namedCurveIsNamed() {
        assertEquals("brainpoolP256r1", ecCurveNameOrNull(ecKey(TeleTrusTObjectIdentifiers.brainpoolP256r1)))
    }

    @Test
    fun explicitParametersAreMatchedToTheirName() {
        assertEquals("brainpoolP256r1", ecCurveNameOrNull(ecKey(brainpool.toASN1Primitive())))
    }

    /** P-256 has aliases (prime256v1, secp256r1, P-256); its OID picks the canonical one. */
    @Test
    fun explicitAliasedCurveReportsItsCanonicalName() {
        val p256 = ECNamedCurveTable.getByName("P-256")
        val expected = ECNamedCurveTable.getName(ECNamedCurveTable.getOID("P-256"))

        assertEquals(expected, ecCurveNameOrNull(ecKey(p256.toASN1Primitive())))
    }

    @Test
    fun unrecognisedExplicitCurveIsNull() {
        val odd = X9ECParameters(brainpool.curve, brainpool.baseEntry, brainpool.n.add(BigInteger.ONE), brainpool.h)

        assertNull(ecCurveNameOrNull(ecKey(odd.toASN1Primitive())))
    }

    @Test
    fun rsaKeyIsNull() {
        val rsa = SubjectPublicKeyInfo(AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE), byteArrayOf(0))

        assertNull(ecCurveNameOrNull(rsa.encoded))
    }

    @Test
    fun garbageIsNullNotAnException() {
        assertNull(ecCurveNameOrNull(byteArrayOf(1, 2, 3)))
    }

    /**
     * The GB CSCA shipped with the instrumented tests, read as a real issuer
     * encodes it: explicit parameters, no OID - so the explicit path is not
     * hypothetical. secp384r1 was measured from this certificate, not recalled.
     */
    @Test
    fun realCscaCurveIsNamed() {
        val pem = File("src/androidTest/assets/certs/csca-gb.pem").readText()
        val der = Base64.getMimeDecoder().decode(
            pem.substringAfter("-----BEGIN CERTIFICATE-----").substringBefore("-----END CERTIFICATE-----")
        )
        val spki = X509CertificateHolder(der).subjectPublicKeyInfo
        val explicit = spki.algorithm.parameters.toASN1Primitive() is ASN1Sequence

        val name = ecCurveNameOrNull(spki.encoded)

        assertEquals(true, explicit)
        assertEquals("secp384r1", name)
    }
}
