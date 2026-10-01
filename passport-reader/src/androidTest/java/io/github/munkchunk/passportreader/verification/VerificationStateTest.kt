package io.github.munkchunk.passportreader.verification

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.munkchunk.passportreader.model.CheckVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the starting state of [VerificationState].
 *
 * This is not ceremony. The read path decides whether it is the first to reach
 * a conclusion by testing `cs?.verdict == UNKNOWN`, so a check that starts null
 * instead of UNKNOWN silently skips its own success branch: the certificate
 * chain result vanishes from the report while every other check still passes,
 * and nothing fails to compile.
 */
@RunWith(AndroidJUnit4::class)
class VerificationStateTest {

    @Test
    fun checksReachedBySetAllStartUnknown() {
        val state = VerificationState()
        assertEquals("bac", CheckVerdict.UNKNOWN, state.bac?.verdict)
        assertEquals("cs", CheckVerdict.UNKNOWN, state.cs?.verdict)
        assertEquals("ds", CheckVerdict.UNKNOWN, state.ds?.verdict)
        assertEquals("ht", CheckVerdict.UNKNOWN, state.ht?.verdict)
        assertEquals("aa", CheckVerdict.UNKNOWN, state.aa?.verdict)
        assertEquals("eac", CheckVerdict.UNKNOWN, state.eac?.verdict)
    }

    /**
     * SAC and CA are the two setAll leaves alone. A document that never
     * attempts PACE should report nothing for it rather than UNKNOWN, and the
     * same goes for chip authentication.
     */
    @Test
    fun sacAndCaStartUnset() {
        val state = VerificationState()
        assertNull("sac", state.sac)
        assertNull("ca", state.ca)
    }

    @Test
    fun setAllLeavesSacAndCaAlone() {
        val state = VerificationState()
        state.sac = io.github.munkchunk.passportreader.model.CheckResult(CheckVerdict.SUCCEEDED, "PACE")
        state.setCa(CheckVerdict.SUCCEEDED, "CA succeeded", null)

        state.setAll(CheckVerdict.FAILED, "something failed early")

        assertEquals(CheckVerdict.SUCCEEDED, state.sac?.verdict)
        assertEquals(CheckVerdict.SUCCEEDED, state.ca?.verdict)
        assertEquals(CheckVerdict.FAILED, state.ht?.verdict)
    }

    @Test
    fun pairedSetterKeepsVerdictAndEvidenceTogether() {
        val state = VerificationState()
        val hashes = mutableMapOf(1 to HashMatchResult(byteArrayOf(1, 2), byteArrayOf(1, 2)))

        state.setHt(CheckVerdict.SUCCEEDED, "Data Group hashes match", hashes)

        assertEquals(CheckVerdict.SUCCEEDED, state.ht?.verdict)
        assertEquals("Data Group hashes match", state.ht?.reason)
        assertEquals(1, state.hashResults?.size)
    }

    @Test
    fun hashMatchResultNeedsBothSidesToMatch() {
        assertEquals(true, HashMatchResult(byteArrayOf(1, 2), byteArrayOf(1, 2)).isMatch)
        assertEquals(false, HashMatchResult(byteArrayOf(1, 2), byteArrayOf(3, 4)).isMatch)
        assertEquals(false, HashMatchResult(byteArrayOf(1, 2), null).isMatch)
        assertEquals(false, HashMatchResult(null, null).isMatch)
    }
}
