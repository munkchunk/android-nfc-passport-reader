package io.github.munkchunk.passportreader.sample.mrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MrzDatesTest {

    private val today = LocalDate.of(2026, 9, 17)

    @Test
    fun `birth date in the current century stays there`() {
        assertEquals(LocalDate.of(2005, 3, 14), MrzDates.parseDateOfBirth("050314", today))
    }

    @Test
    fun `birth date that would be in the future falls back a century`() {
        // 740812 must be 1974, not 2074.
        assertEquals(LocalDate.of(1974, 8, 12), MrzDates.parseDateOfBirth("740812", today))
    }

    @Test
    fun `expiry just ahead stays in this century`() {
        assertEquals(LocalDate.of(2036, 3, 2), MrzDates.parseDateOfExpiry("360302", today))
    }

    @Test
    fun `expiry far in the future is really the previous century`() {
        // 120415 must be 2012, not 2112 - the bug this window exists to stop.
        assertEquals(LocalDate.of(2012, 4, 15), MrzDates.parseDateOfExpiry("120415", today))
    }

    @Test
    fun `recently expired is read as past, not a century ahead`() {
        assertEquals(LocalDate.of(2026, 2, 4), MrzDates.parseDateOfExpiry("260204", today))
    }

    @Test
    fun `expiry detection`() {
        assertTrue(MrzDates.isExpired("260204", today))   // Feb 2026, past
        assertTrue(MrzDates.isExpired("120415", today))   // Apr 2012, long past
        assertFalse(MrzDates.isExpired("360302", today))  // Mar 2036, future
        assertFalse(MrzDates.isExpired(null, today))
        assertFalse(MrzDates.isExpired("garbage", today))
    }

    @Test
    fun `rejects impossible dates`() {
        assertNull(MrzDates.parseDateOfBirth("741312", today))  // month 13
        assertNull(MrzDates.parseDateOfBirth("740230", today))  // 30 February
        assertNull(MrzDates.parseDateOfBirth("7408", today))    // too short
        assertNull(MrzDates.parseDateOfBirth("74081A", today))  // not digits
    }

    @Test
    fun `formats for display`() {
        assertEquals("12 AUG 1974", MrzDates.format(LocalDate.of(1974, 8, 12)))
        assertEquals("12 AUG 1974", MrzDates.formatDateOfBirth("740812"))
    }

    @Test
    fun `falls back to the raw digits when unparseable`() {
        assertEquals("741312", MrzDates.formatDateOfBirth("741312"))
    }
}
