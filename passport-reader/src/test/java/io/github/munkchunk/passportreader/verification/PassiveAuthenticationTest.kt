package io.github.munkchunk.passportreader.verification

import io.github.munkchunk.passportreader.model.CheckVerdict
import io.github.munkchunk.passportreader.trust.CscaTrustStore
import io.github.munkchunk.passportreader.verification.TestDocuments.DATA_GROUPS
import io.github.munkchunk.passportreader.verification.TestDocuments.FakeChip
import io.github.munkchunk.passportreader.verification.TestDocuments.NOW
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Passive Authentication against invented documents.
 *
 * This is the first automated coverage of anything the read path concludes
 * about a document. Each test names the property it pins; the documents come
 * from [TestDocuments], and none of them is real.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PassiveAuthenticationTest {

    private fun verifier(trust: CscaTrustStore) = PassiveAuthentication(trust, now = { NOW })

    // ------------------------------------------------------ the good document

    @Test
    fun `a genuine document passes all three checks`() {
        val pki = TestDocuments.pki()
        val result = verifier(pki.trustStore())
            .verify(TestDocuments.securityObject(pki), FakeChip.holding(DATA_GROUPS), false)

        assertEquals(CheckVerdict.SUCCEEDED, result.ds.verdict)
        assertEquals(CheckVerdict.SUCCEEDED, result.cs.verdict)
        assertEquals(CheckVerdict.SUCCEEDED, result.ht.verdict)
        assertEquals(listOf(pki.signer, pki.csca), result.chain)
        assertEquals(setOf(1, 2, 14), result.hashes.keys)
        assertTrue(result.hashes.values.all { it.isMatch })
    }

    @Test
    fun `every hash and signature algorithm pairing an issuer might use verifies`() {
        val cases = listOf(
            Triple("SHA-1", "SHA1withRSA", TestDocuments.rsaKeys()),
            Triple("SHA-256", "SHA256withRSA", TestDocuments.rsaKeys()),
            Triple("SHA-256", "SHA256withRSAandMGF1", TestDocuments.rsaKeys()),
            Triple("SHA-384", "SHA384withECDSA", TestDocuments.ecKeys()),
            Triple("SHA-512", "SHA512withECDSA", TestDocuments.ecKeys()),
        )
        for ((digest, signature, keys) in cases) {
            val pki = TestDocuments.pki(signerKeys = keys)
            val sod = TestDocuments.securityObject(pki, algorithms = TestDocuments.Algorithms(digest, signature))
            val result = verifier(pki.trustStore()).verify(sod, FakeChip.holding(DATA_GROUPS), false)
            assertEquals("$digest / $signature", CheckVerdict.SUCCEEDED, result.ds.verdict)
            assertEquals("$digest / $signature", CheckVerdict.SUCCEEDED, result.ht.verdict)
        }
    }

    // ----------------------------------------------------- document signer

    @Test
    fun `a signature made with some other key fails the document signer check only`() {
        val pki = TestDocuments.pki()
        val sod = TestDocuments.securityObject(pki, signWith = TestDocuments.ecKeys().private)
        val result = verifier(pki.trustStore()).verify(sod, FakeChip.holding(DATA_GROUPS), false)

        assertEquals(CheckVerdict.FAILED, result.ds.verdict)
        assertEquals(CheckVerdict.SUCCEEDED, result.cs.verdict)
    }

    @Test
    fun `hashes swapped in after signing fail the document signer check`() {
        // The attack the messageDigest attribute exists to stop: keep a valid
        // signature, substitute the list of hashes it was meant to cover.
        val forged = DATA_GROUPS + (2 to TestDocuments.tlv(0x75, ByteArray(600) { 0x41 }))
        val pki = TestDocuments.pki()
        val sod = TestDocuments.securityObject(pki, substituteContent = forged)
        val result = verifier(pki.trustStore()).verify(sod, FakeChip.holding(forged), false)

        assertEquals(CheckVerdict.FAILED, result.ds.verdict)
        // The substituted hashes do match the substituted data, which is why
        // the signature check has to catch this on its own.
        assertEquals(CheckVerdict.SUCCEEDED, result.ht.verdict)
    }

    // --------------------------------------------------- certificate chain

    @Test
    fun `a document signed under a CSCA we do not trust fails the chain only`() {
        val issuer = TestDocuments.pki(country = "UT")
        val trusted = TestDocuments.pki(country = "ZZ")
        val result = verifier(trusted.trustStore())
            .verify(TestDocuments.securityObject(issuer), FakeChip.holding(DATA_GROUPS), false)

        assertEquals(CheckVerdict.SUCCEEDED, result.ds.verdict)
        assertEquals(CheckVerdict.FAILED, result.cs.verdict)
        assertEquals(listOf(issuer.signer), result.chain)
    }

    @Test
    fun `an expired document signer certificate fails the chain`() {
        // Validity is judged at the verifier's clock, not at signing time.
        val pki = TestDocuments.pki(
            signerValidFrom = TestDocuments.date(2014, 1, 1),
            signerValidTo = TestDocuments.date(2024, 1, 1),
        )
        val result = verifier(pki.trustStore())
            .verify(TestDocuments.securityObject(pki), FakeChip.holding(DATA_GROUPS), false)

        assertEquals(CheckVerdict.SUCCEEDED, result.ds.verdict)
        assertEquals(CheckVerdict.FAILED, result.cs.verdict)
    }

    @Test
    fun `an empty trust store fails the chain without throwing`() {
        val pki = TestDocuments.pki()
        val result = verifier(CscaTrustStore())
            .verify(TestDocuments.securityObject(pki), FakeChip.holding(DATA_GROUPS), false)

        assertEquals(CheckVerdict.FAILED, result.cs.verdict)
        assertEquals(CheckVerdict.SUCCEEDED, result.ds.verdict)
    }

    // --------------------------------------------------------------- hashes

    /**
     * The EF.CardAccess check parses DG14 from verifiedContent, so a chip
     * cannot show it one DG14 and the hash another. That only holds while
     * verifiedContent is the hashed bytes, and only when they matched.
     */
    @Test
    fun `verified content is the hashed bytes, and only when they match`() {
        val pki = TestDocuments.pki()
        val altered = DATA_GROUPS + (2 to TestDocuments.tlv(0x75, ByteArray(600) { 0x42 }))
        val result = verifier(pki.trustStore())
            .verify(TestDocuments.securityObject(pki), FakeChip.holding(altered), false)

        assertArrayEquals(DATA_GROUPS.getValue(14), result.hashes.getValue(14).verifiedContent)
        assertNull(result.hashes.getValue(2).verifiedContent)
    }

    @Test
    fun `a data group that differs from what was signed fails the hash check`() {
        val pki = TestDocuments.pki()
        val altered = DATA_GROUPS + (2 to TestDocuments.tlv(0x75, ByteArray(600) { 0x42 }))
        val result = verifier(pki.trustStore())
            .verify(TestDocuments.securityObject(pki), FakeChip.holding(altered), false)

        assertEquals(CheckVerdict.FAILED, result.ht.verdict)
        assertFalse(result.hashes.getValue(2).isMatch)
        assertTrue(result.hashes.getValue(1).isMatch)
        assertEquals(CheckVerdict.SUCCEEDED, result.ds.verdict)
    }

    @Test
    fun `DG3 is never requested without terminal authentication and does not fail the check`() {
        val groups = DATA_GROUPS + (3 to TestDocuments.tlv(0x63, ByteArray(40)))
        val pki = TestDocuments.pki()
        val chip = FakeChip.holding(groups)
        val result = verifier(pki.trustStore())
            .verify(TestDocuments.securityObject(pki, groups), chip, terminalAuthenticated = false)

        assertFalse(3 in chip.requested)
        assertNull(result.hashes.getValue(3).computedHash)
        assertEquals(CheckVerdict.SUCCEEDED, result.ht.verdict)
    }

    @Test
    fun `DG3 is checked once terminal authentication has succeeded`() {
        val groups = DATA_GROUPS + (3 to TestDocuments.tlv(0x63, ByteArray(40)))
        val pki = TestDocuments.pki()
        val chip = FakeChip.holding(groups)
        val result = verifier(pki.trustStore())
            .verify(TestDocuments.securityObject(pki, groups), chip, terminalAuthenticated = true)

        assertTrue(3 in chip.requested)
        assertTrue(result.hashes.getValue(3).isMatch)
    }

    @Test
    fun `an optional group the chip refuses is recorded unchecked, not failed`() {
        val groups = DATA_GROUPS + (11 to TestDocuments.tlv(0x6B, ByteArray(20)))
        val pki = TestDocuments.pki()
        val reads = DATA_GROUPS.mapValues<Int, ByteArray, DataGroupRead> { DataGroupRead.Bytes(it.value) } +
            (11 to DataGroupRead.Refused(0x6982))
        val result = verifier(pki.trustStore())
            .verify(TestDocuments.securityObject(pki, groups), FakeChip(reads), false)

        assertEquals(CheckVerdict.SUCCEEDED, result.ht.verdict)
        assertNull(result.hashes.getValue(11).computedHash)
    }

    @Test
    fun `an optional group refused for an unexpected reason fails the check`() {
        val groups = DATA_GROUPS + (11 to TestDocuments.tlv(0x6B, ByteArray(20)))
        val pki = TestDocuments.pki()
        val reads = DATA_GROUPS.mapValues<Int, ByteArray, DataGroupRead> { DataGroupRead.Bytes(it.value) } +
            (11 to DataGroupRead.Refused(0x6700))
        val result = verifier(pki.trustStore())
            .verify(TestDocuments.securityObject(pki, groups), FakeChip(reads), false)

        assertEquals(CheckVerdict.FAILED, result.ht.verdict)
    }

    @Test
    fun `a critical group that cannot be read fails the check however it failed`() {
        val pki = TestDocuments.pki()
        val sod = TestDocuments.securityObject(pki)
        for (failure in listOf(DataGroupRead.Refused(0x6982), DataGroupRead.Failed(RuntimeException("lost")))) {
            val reads = DATA_GROUPS.mapValues<Int, ByteArray, DataGroupRead> { DataGroupRead.Bytes(it.value) } +
                (2 to failure)
            val result = verifier(pki.trustStore()).verify(sod, FakeChip(reads), false)
            assertEquals(failure.javaClass.simpleName, CheckVerdict.FAILED, result.ht.verdict)
        }
    }

    // ------------------------------------------------------- malformed input

    @Test
    fun `a missing or malformed EF_SOd fails everything without throwing`() {
        val trust = TestDocuments.pki().trustStore()
        for (sod in listOf(null, ByteArray(0), byteArrayOf(0x77, 0x03, 0x01, 0x02, 0x03), ByteArray(64) { 0x30 })) {
            val result = verifier(trust).verify(sod, FakeChip.holding(DATA_GROUPS), false)
            assertEquals(CheckVerdict.FAILED, result.ds.verdict)
            assertEquals(CheckVerdict.FAILED, result.cs.verdict)
            assertEquals(CheckVerdict.FAILED, result.ht.verdict)
        }
    }

    @Test
    fun `LDS files with short and long definite lengths are unwrapped`() {
        val short = ByteArray(5) { it.toByte() }
        val long = ByteArray(300) { it.toByte() }
        assertArrayEquals(short, LdsFile.value(TestDocuments.tlv(0x77, short), 0x77))
        assertArrayEquals(long, LdsFile.value(TestDocuments.tlv(0x77, long), 0x77))
    }
}
