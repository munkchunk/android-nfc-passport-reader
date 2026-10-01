package io.github.munkchunk.passportreader.model.error

import net.sf.scuba.smartcards.CardServiceException
import org.jmrtd.BACKey
import org.jmrtd.CardServiceProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * BAC and PACE failures are classified on the chip's status word, read by BSI
 * TR-03110-3 B.14.2, and only an authentication command's word can mean a
 * wrong MRZ. The chains are built with the constructors JMRTD 0.8.8 uses:
 * BACProtocol throws `CardServiceProtocolException("BAC failed in MUTUAL
 * AUTH", 2, cause)`, and PACEProtocol the same with steps 0 to 4.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AccessControlMappingTest {

    private fun chipSaid(sw: Int) = CardServiceException("Command failed", sw)

    private fun bac(sw: Int) = AccessControlException(
        AccessProtocol.BAC, 2, CardServiceProtocolException("BAC failed in MUTUAL AUTH", 2, chipSaid(sw))
    )

    private fun pace(sw: Int, step: Int = 4) = AccessControlException(
        AccessProtocol.PACE, step, CardServiceProtocolException("PICC side exception", step, chipSaid(sw))
    )

    @Test
    fun `scuba copies the chip's status word up through JMRTD's wrapper`() {
        // What the design rests on. If a SCUBA upgrade stops copying it,
        // every refusal below turns into PaceFailed or NfcIo.
        val wrapped = CardServiceProtocolException("BAC failed in MUTUAL AUTH", 2, chipSaid(0x6300))
        assertEquals(0x6300, wrapped.sw)
        assertEquals(0x6300, AccessControlException(AccessProtocol.BAC, 2, wrapped).sw)
    }

    @Test
    fun `status word is found below a wrapper that does not carry one`() {
        val chain = java.io.IOException("Unexpected exception", chipSaid(0x6A82))
        assertEquals(0x6A82, chain.statusWord())
        assertNull(java.io.IOException("no chip answer").statusWord())
    }

    @Test
    fun `6300 from BAC or PACE is a wrong MRZ`() {
        assertTrue(bac(0x6300).toPassportReadException() is PassportReadException.WrongMrz)
        assertTrue(pace(0x6300).toPassportReadException() is PassportReadException.WrongMrz)
    }

    @Test
    fun `6300 straight from step 4, not wrapped by JMRTD, is still a wrong MRZ`() {
        val raw = AccessControlException(AccessProtocol.PACE, null, chipSaid(0x6300))
        assertTrue(raw.toPassportReadException() is PassportReadException.WrongMrz)
    }

    @Test
    fun `63CX with tries left is a wrong MRZ`() {
        for (sw in listOf(0x63C2, 0x63C3, 0x63CF)) {
            assertTrue("%04X".format(sw), pace(sw).toPassportReadException() is PassportReadException.WrongMrz)
        }
    }

    @Test
    fun `PACE blocked, suspended or deactivated is not a wrong MRZ`() {
        for (sw in listOf(0x63C1, 0x63C0, 0x6982, 0x6983, 0x6984, 0x6985)) {
            val label = "%04X".format(sw)
            assertTrue(label, pace(sw).toPassportReadException() is PassportReadException.AuthenticationFailed)
        }
    }

    @Test
    fun `BAC has no password states, so the same words are a wrong MRZ`() {
        for (sw in listOf(0x63C1, 0x63C0, 0x6982, 0x6983, 0x6984, 0x6985)) {
            val label = "%04X".format(sw)
            assertTrue(label, bac(sw).toPassportReadException() is PassportReadException.WrongMrz)
        }
    }

    @Test
    fun `BAC refused with 6985 is a wrong MRZ`() {
        // A GBR BAC-only chip answers 0x6985 at MUTUAL AUTH to a document
        // number with its last digit changed. That is a wrong MRZ, not
        // AuthenticationFailed.
        assertTrue(bac(0x6985).toPassportReadException() is PassportReadException.WrongMrz)
    }

    @Test
    fun `6982 on a file read is NfcIo with its status word, not a wrong MRZ`() {
        // After access control succeeded, the chip lost the secure channel.
        val read = CardServiceException("Read binary failed", chipSaid(0x6982))
        val mapped = read.toPassportReadException()
        assertTrue(mapped is PassportReadException.NfcIo)
        assertEquals(0x6982, (mapped as PassportReadException.NfcIo).swCode)
    }

    @Test
    fun `6300 outside access control is not a wrong MRZ`() {
        assertTrue(chipSaid(0x6300).toPassportReadException() is PassportReadException.NfcIo)
    }

    @Test
    fun `PACE failing without a refusal is PaceFailed at JMRTD's step`() {
        val failed = AccessControlException(
            AccessProtocol.PACE, 2,
            CardServiceProtocolException(
                "PCD side error in mapping nonce step", 2, java.security.GeneralSecurityException("x")
            )
        ).toPassportReadException()
        assertTrue(failed is PassportReadException.PaceFailed)
        assertEquals(2, (failed as PassportReadException.PaceFailed).step)
    }

    @Test
    fun `no PACE protocols is PaceFailed, not Unknown`() {
        val none = AccessControlException(AccessProtocol.PACE, null, null, "No PACE protocols in EF.CardAccess")
        assertTrue(none.toPassportReadException() is PassportReadException.PaceFailed)
    }

    @Test
    fun `BAC with no answer from the chip is NfcIo`() {
        val noAnswer = AccessControlException(AccessProtocol.BAC, 2, java.io.IOException("transceive failed"))
        assertTrue(noAnswer.toPassportReadException() is PassportReadException.NfcIo)
    }

    @Test
    fun `BAC's mutual authentication refused with any word is a wrong MRZ`() {
        // ICAO 9303-11 §4.3.4.2 leaves BAC's failure codes to the chip's OS,
        // and that step checks nothing but the MRZ-derived key.
        assertTrue(bac(0x6A80).toPassportReadException() is PassportReadException.WrongMrz)
        assertTrue(bac(0x6988).toPassportReadException() is PassportReadException.WrongMrz)
    }

    @Test
    fun `BAC's GET CHALLENGE refused is AuthenticationFailed, not a wrong MRZ`() {
        // No key is involved before MUTUAL AUTHENTICATE.
        val refused = AccessControlException(
            AccessProtocol.BAC, 1, CardServiceProtocolException("BAC failed in GET CHALLENGE", 1, chipSaid(0x6A80))
        )
        assertTrue(refused.toPassportReadException() is PassportReadException.AuthenticationFailed)
    }

    @Test
    fun `a lost tag during BAC or PACE is TagLost, whatever the step`() {
        val lost = CardServiceProtocolException(
            "BAC failed in MUTUAL AUTH", 2, CardServiceException("Could not transmit", android.nfc.TagLostException())
        )
        for (protocol in AccessProtocol.entries) {
            val mapped = AccessControlException(protocol, 2, lost).toPassportReadException()
            assertTrue(protocol.name, mapped is PassportReadException.TagLost)
        }
    }

    // What PassportNFC's catches hand to the mapper, built from what JMRTD throws.

    @Test
    fun `a BAC refusal wrapped as PassportNFC wraps it is a wrong MRZ`() {
        val thrown = CardServiceProtocolException("BAC failed in MUTUAL AUTH", 2, chipSaid(0x6985))
        val wrapped = accessControlFailure(AccessProtocol.BAC, thrown)
        assertEquals(2, wrapped.step)
        assertEquals(0x6985, wrapped.sw)
        assertTrue(wrapped.toPassportReadException() is PassportReadException.WrongMrz)
    }

    @Test
    fun `a malformed MRZ field is a wrong MRZ, and its value goes nowhere`() {
        // JMRTD's own BACKey, given a seven-character date of birth, refuses it
        // with "Illegal date: " and the date. The value must not survive into
        // anything a caller can display, log or report.
        val badDate = "1234567"
        val refusal = assertThrows(IllegalArgumentException::class.java) { BACKey("L898902C3", badDate, "121104") }
        assertTrue("premise: JMRTD names the value", refusal.message.orEmpty().contains(badDate))

        for (protocol in AccessProtocol.entries) {
            val mapped = accessControlFailure(protocol, refusal).toPassportReadException()
            assertTrue(protocol.name, mapped is PassportReadException.WrongMrz)
            val everything = generateSequence<Throwable>(mapped) { it.cause }.joinToString { it.toString() } +
                mapped.technicalDetails.orEmpty()
            assertFalse(protocol.name, everything.contains(badDate))
        }
    }

    @Test
    fun `PACE failing at JMRTD's step 0 is described as key derivation`() {
        val failed = accessControlFailure(
            AccessProtocol.PACE,
            CardServiceProtocolException(
                "PCD side error in key derivation step", 0, java.security.GeneralSecurityException("x")
            ),
        ).toPassportReadException()
        assertTrue(failed is PassportReadException.PaceFailed)
        assertTrue(failed.technicalDetails.orEmpty().contains("PACE step 0: key derivation from the MRZ"))
    }
}
