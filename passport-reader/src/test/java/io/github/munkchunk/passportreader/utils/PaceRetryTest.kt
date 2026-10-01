package io.github.munkchunk.passportreader.utils

import net.sf.scuba.smartcards.CardServiceException
import org.jmrtd.CardServiceProtocolException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** PACE retries are decided on type, status word and JMRTD's step, never on message text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PaceRetryTest {

    private fun atStep(step: Int, cause: Throwable) = CardServiceProtocolException("PACE failed", step, cause)
    private fun chipSaid(sw: Int) = CardServiceException("General Authenticate failed", sw)

    @Test
    fun `a lost tag stops PACE`() {
        val lost = atStep(1, CardServiceException("Could not transmit", android.nfc.TagLostException()))
        assertEquals(PaceRetry.Stop, paceRetryFor(lost))
        val stale = atStep(1, SecurityException("Permission Denial: Tag ( ID: 04 ) is out of date"))
        assertEquals(PaceRetry.Stop, paceRetryFor(stale))
    }

    @Test
    fun `a refused credential stops PACE at any step`() {
        for (sw in listOf(0x6300, 0x63C3, 0x63C1, 0x63C0, 0x6982, 0x6983, 0x6984, 0x6985)) {
            for (step in 1..4) {
                assertEquals("%04X step $step".format(sw), PaceRetry.Stop, paceRetryFor(atStep(step, chipSaid(sw))))
            }
        }
        // Step 4's rejection can arrive unwrapped.
        assertEquals(PaceRetry.Stop, paceRetryFor(chipSaid(0x6300)))
    }

    @Test
    fun `key agreement or token failure without a refusal moves to the next protocol`() {
        val crypto = java.security.GeneralSecurityException("PICC authentication token mismatch")
        assertEquals(PaceRetry.NextProtocol, paceRetryFor(atStep(3, crypto)))
        assertEquals(PaceRetry.NextProtocol, paceRetryFor(atStep(4, crypto)))
        assertEquals(PaceRetry.NextProtocol, paceRetryFor(atStep(3, chipSaid(0x6A80))))
    }

    @Test
    fun `earlier failures are retried on the same protocol`() {
        val crypto = java.security.GeneralSecurityException("x")
        assertEquals(PaceRetry.SameProtocol, paceRetryFor(atStep(0, crypto)))
        assertEquals(PaceRetry.SameProtocol, paceRetryFor(atStep(1, chipSaid(0x6A80))))
        assertEquals(PaceRetry.SameProtocol, paceRetryFor(atStep(2, crypto)))
        assertEquals(PaceRetry.SameProtocol, paceRetryFor(java.io.IOException("no answer")))
    }

    @Test
    fun `message text alone decides nothing`() {
        // Matching on these words would stop here; a message is not a status word.
        val wordy = atStep(1, java.io.IOException("authentication failed 6982 security status"))
        assertEquals(PaceRetry.SameProtocol, paceRetryFor(wordy))
    }
}
