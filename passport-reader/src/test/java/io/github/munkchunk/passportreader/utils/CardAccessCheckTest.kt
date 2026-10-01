package io.github.munkchunk.passportreader.utils

import org.jmrtd.lds.PACEInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EF.CardAccess held to DG14. Each failing case is an edit a clone could make
 * to the unsigned file to avoid a stronger protocol.
 */
class CardAccessCheckTest {

    /** id-PACE-ECDH-GM-AES-CBC-CMAC-256 on NIST P-256: what the in-date test passport offers. */
    private val gm = PACEInfo("0.4.0.127.0.7.2.2.4.2.4", 2, 12)

    /** id-PACE-ECDH-CAM-AES-CBC-CMAC-128 on brainpoolP256r1: ICAO 9303-11 Appendix I's. */
    private val cam = PACEInfo("0.4.0.127.0.7.2.2.4.6.2", 2, 13)

    @Test
    fun `the in-date passport's shape matches`() {
        assertEquals(CardAccessCheck.Matches, cardAccessCheck(signed = listOf(gm), offered = listOf(gm), used = gm))
    }

    @Test
    fun `a chip offering CAM and GM may be read with either`() {
        val both = listOf(cam, gm)
        assertEquals(CardAccessCheck.Matches, cardAccessCheck(both, both, used = gm))
        assertEquals(CardAccessCheck.Matches, cardAccessCheck(both, both, used = cam))
    }

    /** No PACEInfo in DG14, as on the expired BAC test passport, which has no DG14 at all. */
    @Test
    fun `nothing signed is nothing to compare`() {
        assertEquals(CardAccessCheck.NothingToCompare, cardAccessCheck(emptyList(), emptyList(), used = null))
        assertEquals(CardAccessCheck.NothingToCompare, cardAccessCheck(emptyList(), listOf(gm), used = gm))
    }

    /** EF.CardAccess removed or emptied, so the read fell back to BAC. */
    @Test
    fun `PACE hidden so that BAC is used fails`() {
        assertMismatch(cardAccessCheck(signed = listOf(gm), offered = emptyList(), used = null))
    }

    /** CAM dropped from EF.CardAccess, so a clone is never asked to prove its key. */
    @Test
    fun `CAM withheld from EF_CardAccess fails though GM was used`() {
        assertMismatch(cardAccessCheck(signed = listOf(cam, gm), offered = listOf(gm), used = gm))
    }

    @Test
    fun `a variant DG14 does not list fails`() {
        val other = PACEInfo("0.4.0.127.0.7.2.2.4.2.2", 2, 12)
        assertMismatch(cardAccessCheck(signed = listOf(gm), offered = listOf(gm, other), used = other))
    }

    /** Same protocol, different domain parameters: still not what was signed. */
    @Test
    fun `parameters are part of the variant`() {
        val gmOnBrainpool = PACEInfo("0.4.0.127.0.7.2.2.4.2.4", 2, 13)
        assertMismatch(cardAccessCheck(signed = listOf(gm), offered = listOf(gmOnBrainpool), used = gmOnBrainpool))
    }

    /** EF.CardAccess may carry more than DG14 lists; only the signed list is binding. */
    @Test
    fun `extra variants offered beyond DG14 are allowed if not used`() {
        val other = PACEInfo("0.4.0.127.0.7.2.2.4.2.2", 2, 12)
        assertEquals(CardAccessCheck.Matches, cardAccessCheck(listOf(gm), listOf(other, gm), used = gm))
    }

    /** PACEInfo's parameter ID is optional; a chip copying its SecurityInfos into DG14 keeps it absent in both. */
    @Test
    fun `an absent parameter ID matches only an absent one`() {
        val noParameter = PACEInfo("0.4.0.127.0.7.2.2.4.2.4", 2, null as java.math.BigInteger?)
        assertEquals(CardAccessCheck.Matches, cardAccessCheck(listOf(noParameter), listOf(noParameter), noParameter))
        assertMismatch(cardAccessCheck(listOf(noParameter), listOf(gm), used = gm))
    }

    private fun assertMismatch(check: CardAccessCheck) {
        assertTrue("expected a mismatch, got $check", check is CardAccessCheck.Mismatch)
    }
}
