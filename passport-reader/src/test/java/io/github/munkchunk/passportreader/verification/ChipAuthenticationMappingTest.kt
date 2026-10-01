package io.github.munkchunk.passportreader.verification

import io.github.munkchunk.passportreader.crypto.BouncyCastleSupport
import io.github.munkchunk.passportreader.model.CheckResult
import io.github.munkchunk.passportreader.model.CheckVerdict
import io.github.munkchunk.passportreader.trust.CscaTrustStore
import io.github.munkchunk.passportreader.verification.ChipAuthenticationMapping.Companion.mappingKeyMatches
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import org.bouncycastle.asn1.icao.ICAOObjectIdentifiers
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.spec.ECPublicKeySpec
import org.bouncycastle.math.ec.ECPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PACE-CAM's chip authentication, without a chip that does it: neither test
 * passport offers CAM. The equation is checked against ICAO 9303-11
 * Appendix I's worked example; the rest against chips simulated here, whose
 * EF.CardSecurity is signed by TestDocuments' generated PKI.
 */
class ChipAuthenticationMappingTest {

    private val provider = BouncyCastleSupport.provider
    private val curve = ECNamedCurveTable.getParameterSpec(CURVE)

    // ---------------------------------------------- ICAO 9303-11 Appendix I

    /** PK_IC, from the example's ChipAuthenticationPublicKeyInfo. */
    private val icaoStaticKey = publicKey(
        "18727094 94399E74 70A6431B E25E83EE E24FEA56 8C2ED28D B48E05DB 3A610DC8",
        "84D256A4 0E35EFCB 59BF6753 D3A489D2 8C7A4D97 3C2DA138 A6E7A4A0 8F68E16F",
    )

    /** PK_Map,IC, the chip's public key from the mapping step. */
    private val icaoMappingKey = publicKey(
        "A234236A A9B9621E 8EFB73B5 245C0E09 D2576E52 77183C12 08BDD552 80CAE8B3",
        "04F36571 3A356E65 A451E165 ECC9AC0A C46E3771 342C8FE5 AEDD0926 85338E23",
    )

    /** CA_IC, the decrypted Chip Authentication Data. */
    private val icaoChipData = hex("85DC3FA9 3D0952BF A82F5FD1 89EE75BD 82F11D1F 0B8ED4BF 5319AC9B 53C426B3")

    @Test
    fun `the ICAO worked example verifies`() {
        assertTrue(mappingKeyMatches(icaoChipData, icaoMappingKey, icaoStaticKey))
    }

    @Test
    fun `chip data changed by one does not verify`() {
        val changed = BigInteger(1, icaoChipData).add(BigInteger.ONE).toByteArray()
        assertFalse(mappingKeyMatches(changed, icaoMappingKey, icaoStaticKey))
    }

    /** A clone presenting someone else's signed key cannot make it fit. */
    @Test
    fun `the example's data does not verify against another chip's key`() {
        assertFalse(mappingKeyMatches(icaoChipData, icaoMappingKey, chip().staticKey))
    }

    /** Zero maps every key to the point at infinity; n and above are not reduced values. */
    @Test
    fun `chip data of zero or at least the group order is refused`() {
        assertFalse(mappingKeyMatches(ByteArray(32), icaoMappingKey, icaoStaticKey))
        val order = curve.n.toByteArray()
        assertFalse(mappingKeyMatches(order, icaoMappingKey, icaoStaticKey))
    }

    @Test
    fun `keys on different curves are refused, not compared`() {
        val p256 = KeyPairGenerator.getInstance("EC", provider)
            .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey
        assertFalse(mappingKeyMatches(icaoChipData, p256, icaoStaticKey))
    }

    // ------------------------------------------------------ the whole check

    private val pki = TestDocuments.pki()

    private fun verifier(trustStore: CscaTrustStore = pki.trustStore()) =
        ChipAuthenticationMapping(trustStore, now = { TestDocuments.NOW })

    private fun bytes(value: ByteArray) = DataGroupRead.Bytes(value)

    /** The check as the read makes it, after an EF.SOd that chained unless said otherwise. */
    private fun check(
        data: ByteArray?,
        mappingKey: PublicKey?,
        cardSecurity: DataGroupRead,
        sodChained: Boolean = true,
        trustStore: CscaTrustStore = pki.trustStore(),
    ) = verifier(trustStore).verify(data, mappingKey, cardSecurity, sodChained)

    @Test
    fun `a genuine chip with a signed EF_CardSecurity passes`() {
        val chip = chip()
        val cardSecurity = TestDocuments.cardSecurity(pki, listOf(chip.staticKey))

        val result = check(chip.data, chip.mappingKey, bytes(cardSecurity))

        assertEquals(result.reason, CheckVerdict.SUCCEEDED, result.verdict)
    }

    @Test
    fun `the matching key is found among several`() {
        val chip = chip()
        val cardSecurity = TestDocuments.cardSecurity(pki, listOf(chip().staticKey, chip.staticKey))

        assertEquals(CheckVerdict.SUCCEEDED, check(chip.data, chip.mappingKey, bytes(cardSecurity)).verdict)
    }

    /** A clone copies the genuine, signed EF.CardSecurity but not the private key behind it. */
    @Test
    fun `a clone with the genuine EF_CardSecurity fails`() {
        val genuine = chip()
        val clone = chip()
        val cardSecurity = TestDocuments.cardSecurity(pki, listOf(genuine.staticKey))

        val result = check(clone.data, clone.mappingKey, bytes(cardSecurity))

        assertEquals(CheckVerdict.FAILED, result.verdict)
    }

    /** A clone puts its own key in EF.CardSecurity, but cannot sign it as the issuer. */
    @Test
    fun `a clone's own key under a forged signature fails`() {
        val clone = chip()
        val cardSecurity = TestDocuments.cardSecurity(
            pki, listOf(clone.staticKey), signWith = TestDocuments.ecKeys().private,
        )

        val result = check(clone.data, clone.mappingKey, bytes(cardSecurity))

        assertEquals(CheckVerdict.FAILED, result.verdict)
    }

    /**
     * A clone that cannot use the issuer's key signs its own EF.CardSecurity
     * with a home-made document signer. The signature verifies; only the chain
     * gives it away, and EF.SOd from the same issuer did chain.
     */
    @Test
    fun `an EF_CardSecurity that does not chain when EF_SOd does fails`() {
        val clone = chip()
        val homeMade = TestDocuments.pki(country = "ZZ")
        val cardSecurity = TestDocuments.cardSecurity(homeMade, listOf(clone.staticKey))

        val result = check(clone.data, clone.mappingKey, bytes(cardSecurity), sodChained = true)

        assertEquals(CheckVerdict.FAILED, result.verdict)
    }

    /** No anchor for the issuing country at all says nothing about the chip, so it is not a failure. */
    @Test
    fun `an EF_CardSecurity that does not chain when EF_SOd did not either is not checked`() {
        val chip = chip()
        val elsewhere = TestDocuments.pki(country = "ZZ")
        val cardSecurity = TestDocuments.cardSecurity(elsewhere, listOf(chip.staticKey))

        val result = check(chip.data, chip.mappingKey, bytes(cardSecurity), sodChained = false)

        assertEquals(CheckVerdict.NOT_CHECKED, result.verdict)
    }

    /**
     * The example's ChipAuthenticationPublicKeyInfo exactly as ICAO 9303-11
     * Appendix I encodes it, with the curve as BSI standardized domain
     * parameter 13 rather than an X.509 one, through the same parsing as a
     * chip's EF.CardSecurity. JMRTD 0.8.8 returns no key for this encoding,
     * which is why CardSecurityKeys decodes it.
     */
    @Test
    fun `the ICAO example's encoded key verifies through EF_CardSecurity`() {
        val icaoKeyInfo = hex(
            "30620609 04007F00 07020201 02305230 0C060704 007F0007 01020201 0D034200" +
                "04187270 9494399E 7470A643 1BE25E83 EEE24FEA 568C2ED2 8DB48E05 DB3A610D" +
                "C884D256 A40E35EF CB59BF67 53D3A489 D28C7A4D 973C2DA1 38A6E7A4 A08F68E1 6F02010D"
        )
        val cardSecurity = TestDocuments.cardSecurityOf(pki, listOf(icaoKeyInfo))

        val result = check(icaoChipData, icaoMappingKey, bytes(cardSecurity))

        assertEquals(result.reason, CheckVerdict.SUCCEEDED, result.verdict)
    }

    @Test
    fun `a signed file that is not a security object fails, unless EF_SOd did not chain either`() {
        val chip = chip()
        val sodLike = TestDocuments.cardSecurity(
            pki, listOf(chip.staticKey), contentType = ICAOObjectIdentifiers.id_icao_ldsSecurityObject,
        )

        assertEquals(CheckVerdict.FAILED, check(chip.data, chip.mappingKey, bytes(sodLike)).verdict)
        assertEquals(
            CheckVerdict.NOT_CHECKED,
            check(chip.data, chip.mappingKey, bytes(sodLike), sodChained = false).verdict,
        )
    }

    @Test
    fun `missing pieces are not checked, and nothing throws`() {
        val chip = chip()
        val cardSecurity = bytes(TestDocuments.cardSecurity(pki, listOf(chip.staticKey)))
        val cases = listOf(
            check(null, chip.mappingKey, cardSecurity),
            check(chip.data, null, cardSecurity),
            check(chip.data, chip.mappingKey, DataGroupRead.Refused(0x6A82)),
            check(chip.data, chip.mappingKey, DataGroupRead.Failed(IOException("lost"))),
            check(chip.data, chip.mappingKey, bytes(byteArrayOf(0x30, 0x03, 0x01))),
        )
        cases.forEach { assertEquals(it.reason, CheckVerdict.NOT_CHECKED, it.verdict) }
    }

    // --------------------------------------------- alongside DG14's verdict

    private val camPassed = CheckResult(CheckVerdict.SUCCEEDED, "Chip authenticated by PACE-CAM")
    private val camFailed = CheckResult(CheckVerdict.FAILED, "PACE-CAM: chip did not prove its signed key")
    private val camUnchecked = CheckResult(CheckVerdict.NOT_CHECKED, "PACE-CAM: no chip authentication data")
    private val dg14Passed = CheckResult(CheckVerdict.SUCCEEDED, "CA succeeded")
    private val dg14Failed = CheckResult(CheckVerdict.FAILED, "CA could not be completed")

    @Test
    fun `without DG14 the PACE-CAM verdict stands, whatever it is`() {
        listOf(camPassed, camFailed, camUnchecked).forEach {
            assertEquals(it, ChipAuthenticationMapping.combine(it, null))
        }
    }

    /** DG14's success cannot outvote a chip that failed to prove the key it was issued with. */
    @Test
    fun `a PACE-CAM failure overrides DG14's success`() {
        assertEquals(CheckVerdict.FAILED, ChipAuthenticationMapping.combine(camFailed, dg14Passed).verdict)
    }

    @Test
    fun `an unchecked PACE-CAM leaves DG14's verdict standing`() {
        assertEquals(dg14Passed, ChipAuthenticationMapping.combine(camUnchecked, dg14Passed))
        assertEquals(dg14Failed, ChipAuthenticationMapping.combine(camUnchecked, dg14Failed))
    }

    @Test
    fun `a PACE-CAM success says whether DG14 agreed`() {
        val both = ChipAuthenticationMapping.combine(camPassed, dg14Passed)
        val camOnly = ChipAuthenticationMapping.combine(camPassed, dg14Failed)
        assertEquals(CheckVerdict.SUCCEEDED, both.verdict)
        assertEquals("Chip authenticated by PACE-CAM and DG14", both.reason)
        assertEquals(CheckVerdict.SUCCEEDED, camOnly.verdict)
        assertTrue(camOnly.reason!!.contains("DG14 chip authentication failed"))
    }

    /** DG14 listed but unreadable: nothing was attempted, so the reason must not say it failed. */
    @Test
    fun `a PACE-CAM success stands alone when DG14 was never attempted`() {
        val notAttempted = CheckResult(CheckVerdict.NOT_CHECKED, "CA not attempted: DG14 unreadable")
        assertEquals(camPassed, ChipAuthenticationMapping.combine(camPassed, notAttempted))
    }

    // ------------------------------------------------------------- helpers

    /** A chip's half of PACE-CAM: CA_IC = SK_IC⁻¹ · SK_Map,IC mod n, and PK_Map,IC = SK_Map,IC · G. */
    private class Chip(val staticKey: ECPublicKey, val mappingKey: ECPublicKey, val data: ByteArray)

    private fun chip(): Chip {
        val static = KeyPairGenerator.getInstance("EC", provider)
            .apply { initialize(ECGenParameterSpec(CURVE)) }.generateKeyPair()
        val staticPrivate = (static.private as ECPrivateKey).s
        val mappingPrivate = BigInteger(curve.n.bitLength() - 1, SecureRandom()).add(BigInteger.ONE)
        val data = staticPrivate.modInverse(curve.n).multiply(mappingPrivate).mod(curve.n)
        return Chip(static.public as ECPublicKey, publicKey(curve.g.multiply(mappingPrivate)), data.toByteArray())
    }

    private fun publicKey(x: String, y: String): ECPublicKey =
        publicKey(curve.curve.createPoint(BigInteger(1, hex(x)), BigInteger(1, hex(y))))

    private fun publicKey(point: ECPoint): ECPublicKey =
        KeyFactory.getInstance("EC", provider).generatePublic(ECPublicKeySpec(point.normalize(), curve)) as ECPublicKey

    private fun hex(s: String): ByteArray {
        val digits = s.replace(" ", "")
        return ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private companion object {
        /** The example's curve, and the one the simulated chips use. */
        const val CURVE = "brainpoolP256r1"
    }
}
