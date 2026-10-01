package io.github.munkchunk.passportreader.utils

import org.jmrtd.lds.ChipAuthenticationInfo
import org.jmrtd.lds.ChipAuthenticationPublicKeyInfo
import org.jmrtd.lds.SecurityInfo
import org.jmrtd.lds.icao.DG14File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Pairing DG14's Chip Authentication keys with their protocols, ICAO 9303-11
 * §9.2.5-9.2.6. Built with JMRTD's own SecurityInfo constructors and freshly
 * generated keys; no document data.
 */
class ChipAuthenticationPlanTest {

    private val ecKey: PublicKey = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public
    private val otherEcKey: PublicKey = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public
    private val dhKey: PublicKey = KeyPairGenerator.getInstance("DH")
        .apply { initialize(DH_BITS) }.generateKeyPair().public

    private fun ecdhKey(key: PublicKey = ecKey, keyId: Long? = null) =
        ChipAuthenticationPublicKeyInfo(SecurityInfo.ID_PK_ECDH, key, keyId?.let(BigInteger::valueOf))

    private fun info(protocol: String, keyId: Long? = null) =
        ChipAuthenticationInfo(protocol, 1, keyId?.let(BigInteger::valueOf))

    @Test
    fun `one key and one info without keyIds are paired`() {
        val plan = chipAuthenticationPlan(listOf(info(SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128), ecdhKey()))
        assertEquals(1, plan.size)
        assertTrue(plan[0].declared)
        assertEquals(listOf(SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128), plan[0].protocolOids)
        assertEquals(SecurityInfo.ID_PK_ECDH, plan[0].publicKeyOid)
    }

    @Test
    fun `with two keys, each gets the protocol whose keyId matches`() {
        // Pairing by position or by "last info seen" would give every key the
        // protocol and keyId of the last ChipAuthenticationInfo.
        val plan = chipAuthenticationPlan(
            listOf(
                info(SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128, keyId = 1),
                info(SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_256, keyId = 2),
                ecdhKey(ecKey, keyId = 1),
                ecdhKey(otherEcKey, keyId = 2),
            )
        )
        val byKeyId = plan.associateBy { it.keyId?.toInt() }
        assertEquals(listOf(SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128), byKeyId.getValue(1).protocolOids)
        assertEquals(listOf(SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_256), byKeyId.getValue(2).protocolOids)
        // JMRTD re-encodes EC keys with explicit domain parameters, as ICAO
        // requires for Chip Authentication, so compare the point itself.
        assertEquals(ecKey.point(), byKeyId.getValue(1).publicKey.point())
        assertEquals(otherEcKey.point(), byKeyId.getValue(2).publicKey.point())
    }

    @Test
    fun `an ECDH key with no info is tried with 3DES first, then AES`() {
        val plan = chipAuthenticationPlan(listOf(ecdhKey()))
        assertFalse(plan.single().declared)
        assertEquals(
            listOf(
                SecurityInfo.ID_CA_ECDH_3DES_CBC_CBC,
                SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128,
                SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_192,
                SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_256,
            ),
            plan.single().protocolOids
        )
    }

    @Test
    fun `a DH key with no info is tried with the DH protocols`() {
        val plan = chipAuthenticationPlan(listOf(ChipAuthenticationPublicKeyInfo(SecurityInfo.ID_PK_DH, dhKey, null)))
        assertEquals(SecurityInfo.ID_CA_DH_3DES_CBC_CBC, plan.single().protocolOids.first())
        assertEquals(4, plan.single().protocolOids.size)
    }

    @Test
    fun `an info for the other key agreement does not belong to the key`() {
        val plan = chipAuthenticationPlan(
            listOf(info(SecurityInfo.ID_CA_DH_AES_CBC_CMAC_128), ecdhKey())
        )
        assertFalse(plan.single().declared)
        assertEquals(SecurityInfo.ID_CA_ECDH_3DES_CBC_CBC, plan.single().protocolOids.first())
    }

    @Test
    fun `a key without a keyId takes the keyId of its info`() {
        // The chip may expect the keyId in MSE:Set KAT, so the info's is sent.
        val plan = chipAuthenticationPlan(listOf(info(SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128, keyId = 5), ecdhKey()))
        assertEquals(BigInteger.valueOf(5), plan.single().keyId)
    }

    @Test
    fun `contradicting keyIds are not paired`() {
        val plan = chipAuthenticationPlan(
            listOf(info(SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128, keyId = 1), ecdhKey(keyId = 2))
        )
        assertFalse(plan.single().declared)
    }

    @Test
    fun `keys with a declared protocol are tried before inferred ones`() {
        val plan = chipAuthenticationPlan(
            listOf(
                ecdhKey(otherEcKey, keyId = 9),
                info(SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128, keyId = 1),
                ecdhKey(ecKey, keyId = 1),
            )
        )
        assertEquals(listOf(true, false), plan.map { it.declared })
        assertEquals(BigInteger.ONE, plan.first().keyId)
    }

    @Test
    fun `no public key means nothing to try`() {
        assertTrue(chipAuthenticationPlan(listOf(info(SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128))).isEmpty())
        assertTrue(chipAuthenticationPlan(emptyList()).isEmpty())
    }

    @Test
    fun `the plan survives JMRTD encoding and parsing DG14`() {
        // What PassportNFC actually passes in: SecurityInfos parsed by JMRTD
        // from a DG14's bytes, not the objects that were encoded.
        val encoded = DG14File(listOf(info(SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128), ecdhKey())).encoded
        val parsed = DG14File(encoded.inputStream())
        val key = chipAuthenticationPlan(parsed.securityInfos).single()
        assertTrue(key.declared)
        assertEquals(listOf(SecurityInfo.ID_CA_ECDH_AES_CBC_CMAC_128), key.protocolOids)
        assertEquals(SecurityInfo.ID_PK_ECDH, key.publicKeyOid)
    }

    private fun PublicKey.point() = (this as ECPublicKey).w

    private companion object {
        /** A size the JDK has built-in parameters for, so no generation cost. */
        const val DH_BITS = 2048
    }
}
