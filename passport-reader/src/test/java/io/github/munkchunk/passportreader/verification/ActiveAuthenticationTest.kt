package io.github.munkchunk.passportreader.verification

import io.github.munkchunk.passportreader.model.CheckVerdict
import io.github.munkchunk.passportreader.verification.TestDocuments.FakeChip
import org.bouncycastle.crypto.digests.SHA256Digest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Active Authentication against invented chips: each test's key pair is
 * generated for it, and the "chip" signs with BouncyCastle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ActiveAuthenticationTest {

    @Test
    fun `RSA active authentication with the implicit SHA-1 trailer`() {
        val keys = TestDocuments.rsaKeys(1024)
        val chip = FakeChip(emptyMap(), TestDocuments.rsaChip(keys))
        val result = ActiveAuthentication().verify(TestDocuments.dg15(keys), null, chip)
        assertEquals(result.reason, CheckVerdict.SUCCEEDED, result.verdict)
    }

    @Test
    fun `RSA active authentication with an explicit SHA-256 trailer`() {
        val keys = TestDocuments.rsaKeys(1024)
        val chip = FakeChip(emptyMap(), TestDocuments.rsaChip(keys, ::SHA256Digest))
        val result = ActiveAuthentication().verify(TestDocuments.dg15(keys), null, chip)
        assertEquals(result.reason, CheckVerdict.SUCCEEDED, result.verdict)
    }

    @Test
    fun `RSA active authentication fails when a different chip answers`() {
        // A cloned chip has the data but not the private key.
        val keys = TestDocuments.rsaKeys(1024)
        val chip = FakeChip(emptyMap(), TestDocuments.rsaChip(TestDocuments.rsaKeys(1024)))
        val result = ActiveAuthentication().verify(TestDocuments.dg15(keys), null, chip)
        assertEquals(CheckVerdict.FAILED, result.verdict)
    }

    @Test
    fun `RSA active authentication fails when the answer is not to our challenge`() {
        val keys = TestDocuments.rsaKeys(1024)
        val replay = TestDocuments.rsaChip(keys)(ByteArray(8) { 9 })
        val chip = FakeChip(emptyMap()) { replay }
        val result = ActiveAuthentication().verify(TestDocuments.dg15(keys), null, chip)
        assertEquals(CheckVerdict.FAILED, result.verdict)
    }

    @Test
    fun `EC active authentication uses the algorithm DG14 names`() {
        val keys = TestDocuments.ecKeys()
        val chip = FakeChip(emptyMap(), TestDocuments.ecChip(keys))
        val result = ActiveAuthentication()
            .verify(TestDocuments.dg15(keys), TestDocuments.dg14WithEcdsaSha256(), chip)
        assertEquals(result.reason, CheckVerdict.SUCCEEDED, result.verdict)
    }

    @Test
    fun `EC active authentication fails when a different chip answers`() {
        val keys = TestDocuments.ecKeys()
        val chip = FakeChip(emptyMap(), TestDocuments.ecChip(TestDocuments.ecKeys()))
        val result = ActiveAuthentication()
            .verify(TestDocuments.dg15(keys), TestDocuments.dg14WithEcdsaSha256(), chip)
        assertEquals(CheckVerdict.FAILED, result.verdict)
    }

    @Test
    fun `EC active authentication without DG14 fails before sending a challenge`() {
        val keys = TestDocuments.ecKeys()
        var challenged = false
        val chip = FakeChip(emptyMap()) { challenged = true; ByteArray(64) }
        val result = ActiveAuthentication().verify(TestDocuments.dg15(keys), null, chip)
        assertEquals(CheckVerdict.FAILED, result.verdict)
        assertFalse(challenged)
    }

    @Test
    fun `a chip that cannot answer the challenge fails active authentication without throwing`() {
        val keys = TestDocuments.rsaKeys(1024)
        val chip = FakeChip(emptyMap()) { throw android.nfc.TagLostException() }
        val result = ActiveAuthentication().verify(TestDocuments.dg15(keys), null, chip)
        assertEquals(CheckVerdict.FAILED, result.verdict)
    }
}
