package io.github.munkchunk.passportreader.sample.mrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class Icao9303Test {

    /**
     * The specimen MRZ fields printed in ICAO 9303 part 3, with the check
     * digits the spec documents for them.
     */
    @Test
    fun `spec specimen fields produce documented check digits`() {
        assertEquals(3, Icao9303.checkDigit("L898902C")) // document number
        assertEquals(2, Icao9303.checkDigit("740812"))   // date of birth
        assertEquals(9, Icao9303.checkDigit("120415"))   // expiry date
    }

    @Test
    fun `filler characters score zero`() {
        assertEquals(Icao9303.checkDigit("AB2134"), Icao9303.checkDigit("AB2134<<<"))
    }

    @Test
    fun `letters score A as ten`() {
        // 'A' is 10, weighted by 7 => 70, mod 10 => 0
        assertEquals(0, Icao9303.checkDigit("A"))
        // 'B' is 11, weighted by 7 => 77, mod 10 => 7
        assertEquals(7, Icao9303.checkDigit("B"))
    }

    @Test
    fun `short document numbers pad to nine`() {
        assertEquals("AB2134<<<", "AB2134".padDocumentNumber())
        assertEquals("123456789", "123456789".padDocumentNumber())
    }

    @Test
    fun `document number validation rejects bad input`() {
        assertNull(validateDocumentNumber("AB2134"))
        assertNotNull(validateDocumentNumber(""))
        assertNotNull(validateDocumentNumber("0123456789"))
        assertNotNull(validateDocumentNumber("ab2134"))
    }

    @Test
    fun `date validation rejects bad input`() {
        assertNull(validateMrzDate("900101"))
        assertNotNull(validateMrzDate("9001"))
        assertNotNull(validateMrzDate("901301"))
        assertNotNull(validateMrzDate("900132"))
        assertNotNull(validateMrzDate("90010A"))
    }
}
