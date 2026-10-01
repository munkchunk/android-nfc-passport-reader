package io.github.munkchunk.passportreader.model.error

import net.sf.scuba.smartcards.CardServiceException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Both ways a moved passport surfaces are a lost tag, however deeply wrapped.
 *
 * A superseded Tag handle: when the passport slips and is rediscovered, a
 * read on the old handle fails with Android's "Tag ... is out of date". A
 * TagLostException deep in JMRTD's chain: lifting the phone during DG2 wraps
 * it several levels down. Both chains here are as seen on a device, and
 * neither may be reported as Unknown.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TagLostMappingTest {

    // The platform's wording, from Tag.getTagService().
    private val stale = SecurityException("Permission Denial: Tag ( ID: 04 A1 B2 C3 ) is out of date")

    @Test
    fun `stale tag is TagLost`() {
        assertTrue(stale.toPassportReadException() is PassportReadException.TagLost)
    }

    @Test
    fun `stale tag wrapped by scuba is still TagLost`() {
        val wrapped = CardServiceException("Could not transmit", stale)
        assertTrue(wrapped.toPassportReadException() is PassportReadException.TagLost)
    }

    @Test
    fun `stale tag wrapped twice is still TagLost`() {
        val wrapped = RuntimeException(IllegalStateException(stale))
        assertTrue(wrapped.toPassportReadException() is PassportReadException.TagLost)
    }

    @Test
    fun `other SecurityException is not a lost tag`() {
        // A missing NFC permission is a real fault in the host app; calling it
        // a lost tag would send the user to reposition the phone forever.
        val permission = SecurityException("NFC permission required")
        assertFalse(permission.isStaleTag())
        assertTrue(permission.toPassportReadException() is PassportReadException.Unknown)
    }

    @Test
    fun `GeneralSecurityException mapping is unchanged`() {
        val gse = java.security.GeneralSecurityException("out of date")
        assertTrue(gse.toPassportReadException() is PassportReadException.TrustOrPassiveAuthFailed)
    }

    @Test
    fun `tag lost deep inside a data group read is TagLost`() {
        // The chain observed lifting the phone during DG2.
        val chain = java.io.IOException(
            "Unexpected exception",
            CardServiceException(
                "Read binary failed on file 102",
                CardServiceException("Could not tranceive APDU", android.nfc.TagLostException("Tag was lost."))
            )
        )
        assertTrue(chain.toPassportReadException() is PassportReadException.TagLost)
        assertFalse(chain.isStaleTag())
    }

    @Test
    fun `bare TagLostException is still TagLost`() {
        assertTrue(android.nfc.TagLostException().toPassportReadException() is PassportReadException.TagLost)
    }

    @Test
    fun `IOException with no lost tag is not TagLost`() {
        val io = java.io.IOException("Unexpected exception", IllegalStateException("parse"))
        assertTrue(io.toPassportReadException() is PassportReadException.Unknown)
    }
}
