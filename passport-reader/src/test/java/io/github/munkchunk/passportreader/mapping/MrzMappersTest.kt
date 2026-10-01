package io.github.munkchunk.passportreader.mapping

import io.github.munkchunk.passportreader.model.Sex
import org.jmrtd.lds.icao.MRZInfo
import io.github.munkchunk.passportreader.model.error.PassportReadException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail

/** DG1's MRZ as the library's own PersonDetails, on the ICAO 9303 specimen. */
class MrzMappersTest {

    private val specimen = MRZInfo(
        "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<" +
            "L898902C36UTO7408122F1204159ZE184226B<<<<<10"
    )

    @Test
    fun `every person detail comes from the MRZ`() {
        val details = specimen.toPersonDetails()
        assertEquals("P", details.documentCode?.trim('<'))
        assertEquals("UTO", details.issuingState)
        assertEquals("UTO", details.nationality)
        assertEquals("L898902C3", details.documentNumber)
        assertEquals("740812", details.dateOfBirth)
        assertEquals("120415", details.dateOfExpiry)
        assertEquals("ERIKSSON", details.primaryIdentifier)
        assertTrue(details.secondaryIdentifier.orEmpty().startsWith("ANNA"))
        assertEquals(Sex.FEMALE, details.sex)
        assertEquals(specimen.optionalData1, details.optionalData1)
    }

    @Test
    fun `the BAC key MRZ carries the three fields BAC uses`() {
        val mrz = bacKeyMrzInfo("L898902C3", "740812", "120415")
        assertEquals("L898902C3", mrz.documentNumber)
        assertEquals("740812", mrz.dateOfBirth)
        assertEquals("120415", mrz.dateOfExpiry)
    }

    @Test
    fun `a refused field is WrongMrz and nothing of it survives`() {
        // Each refusal JMRTD makes: a character outside the MRZ set, in the
        // number or a date (IllegalStateException, the character in its
        // cause), and a number too long (IllegalArgumentException).
        val refused = listOf(
            Triple("L898/02C3", "740812", "120415"),
            Triple("L898902C3", "74/812", "120415"),
            Triple("L898902C3" + "X".repeat(20), "740812", "120415"),
        )
        for ((number, dob, expiry) in refused) {
            try {
                bacKeyMrzInfo(number, dob, expiry)
                fail("accepted $number $dob")
            } catch (e: PassportReadException.WrongMrz) {
                assertNull(e.cause)
                val message = e.message.orEmpty()
                assertFalse(message, message.contains("/") || message.contains("XXX") || message.contains("29"))
            }
        }
    }
}
