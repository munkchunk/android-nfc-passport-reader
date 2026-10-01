package io.github.munkchunk.passportreader.model.error

import net.sf.scuba.smartcards.CardServiceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a status word is put into words, and that "no answer" is never dressed
 * up as one: SCUBA's SW_NONE is -1, which as hex reads 0xFFFF and looks like a
 * chip's reply, and a link failure mistaken for a chip fault misleads a
 * diagnosis.
 */
class StatusWordTest {

    @Test
    fun `a status word is hex with its meaning`() {
        assertEquals("0x6A82 (file not found)", PassportReadException.statusWordText(0x6A82))
        assertEquals("0x63C3 (wrong credential, 3 tries left)", PassportReadException.statusWordText(0x63C3))
    }

    @Test
    fun `no status word is said in words, not as 0xFFFF`() {
        val text = PassportReadException.statusWordText(CardServiceException.SW_NONE)
        assertEquals(PassportReadException.NO_ANSWER, text)
        assertFalse(text.contains("FFFF"))
    }

    @Test
    fun `a card error with no answer is NfcIo with no code to show`() {
        val mapped = CardServiceException("link dropped", CardServiceException.SW_NONE).toPassportReadException()

        assertTrue(mapped is PassportReadException.NfcIo)
        assertNull((mapped as PassportReadException.NfcIo).swCode)
        assertTrue(mapped.messageArgs.isEmpty())
    }

    @Test
    fun `a card error with an answer keeps its code`() {
        val mapped = CardServiceException("refused", 0x6982).toPassportReadException() as PassportReadException.NfcIo

        assertEquals(0x6982, mapped.swCode)
        assertEquals(listOf("0x6982 (security status not satisfied)"), mapped.messageArgs)
    }
}
