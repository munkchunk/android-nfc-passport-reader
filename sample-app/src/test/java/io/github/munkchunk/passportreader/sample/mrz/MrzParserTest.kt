package io.github.munkchunk.passportreader.sample.mrz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MrzParserTest {

    /**
     * TD3 line 2 built from the ICAO 9303 specimen fields, with check digits
     * computed from the spec: document L898902C< -> 3, birth 740812 -> 2,
     * expiry 120415 -> 9.
     */
    private val specimenLine2 = "L898902C<3UTO7408122F1204159ZE184226B<<<<<1"

    @Test
    fun `parses a clean specimen line`() {
        val key = MrzParser.parse(specimenLine2)
        assertEquals(MrzKey("L898902C", "740812", "120415"), key)
    }

    @Test
    fun `finds the mrz among other text on the page`() {
        val page = """
            PASSPORT  UNITED KINGDOM
            Surname  ERIKSSON
            P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<
            $specimenLine2
        """.trimIndent()
        assertEquals(MrzKey("L898902C", "740812", "120415"), MrzParser.parse(page))
    }

    @Test
    fun `tolerates spaces and line breaks introduced by ocr`() {
        val noisy = "L898 902C<3 UTO\n7408122F\t1204159ZE184226B<<<<<1"
        assertEquals(MrzKey("L898902C", "740812", "120415"), MrzParser.parse(noisy))
    }

    @Test
    fun `check digit resolves a zero misread as the letter O`() {
        // OCR reads the 0 in L898902C as O.
        val misread = specimenLine2.replaceFirst("L898902C", "L8989O2C")
        assertEquals(MrzKey("L898902C", "740812", "120415"), MrzParser.parse(misread))
    }

    @Test
    fun `check digit resolves digits misread in the date fields`() {
        // 740812 read as 74O8I2: O for 0, I for 1.
        val misread = specimenLine2.replaceFirst("7408122", "74O8I22")
        assertEquals(MrzKey("L898902C", "740812", "120415"), MrzParser.parse(misread))
    }

    /**
     * The failure this guards against, on a real document: panning across the
     * page, the first four characters of the document number were off the left
     * edge of the scan band and were recognised as filler. Filler scores 0 in
     * the check digit, the lost prefix happened to weigh a multiple of ten, and
     * the check digit was therefore unchanged - so a wrong key verified, agreed
     * with itself across three frames, and was handed to the chip, which
     * answered 6982.
     *
     * The ICAO specimen has the same property, which is what is used here:
     * `L898902C<` and `<<<8902C<` both check to 3. Verified with a script
     * rather than recalled.
     */
    @Test
    fun `rejects a document number clipped into leading filler`() {
        val clipped = specimenLine2.replaceFirst("L898902C<3", "<<<8902C<3")
        assertEquals(
            "the clipped number must still pass its check digit, or this " +
                "test would pass for the wrong reason",
            Icao9303.checkDigit("L898902C<"),
            Icao9303.checkDigit("<<<8902C<")
        )
        assertNull(MrzParser.parse(clipped))
    }

    @Test
    fun `rejects filler inside a document number`() {
        // Not reachable by clipping, but equally impossible in a real MRZ -
        // and chosen so it collides too: `L8989<2C<` also checks to 3, so this
        // fails without the shape test rather than passing on the check digit.
        val holed = specimenLine2.replaceFirst("L898902C<3", "L8989<2C<3")
        assertEquals(
            Icao9303.checkDigit("L898902C<"),
            Icao9303.checkDigit("L8989<2C<")
        )
        assertNull(MrzParser.parse(holed))
    }

    @Test
    fun `still accepts a short document number padded on the right`() {
        // AB2134 padded to nine. Filler as a trailing pad is legitimate.
        val short = "AB2134<<<" + Icao9303.checkDigit("AB2134<<<") +
            "UTO7408122F1204159ZE184226B<<<<<1"
        assertEquals(MrzKey("AB2134", "740812", "120415"), MrzParser.parse(short))
    }

    @Test
    fun `rejects a line whose document check digit does not agree`() {
        val corrupted = specimenLine2.replaceFirst("L898902C<3", "L898902C<4")
        assertNull(MrzParser.parse(corrupted))
    }

    @Test
    fun `rejects a line whose date check digit does not agree`() {
        val corrupted = specimenLine2.replaceFirst("7408122", "7408123")
        assertNull(MrzParser.parse(corrupted))
    }

    @Test
    fun `rejects a date that passes its check digit but is not a real date`() {
        // Month 19 with a check digit computed to match, so only the calendar
        // rules can reject it.
        val birth = "741912"
        val check = Icao9303.checkDigit(birth)
        val corrupted = specimenLine2.replaceFirst("7408122", "$birth$check")
        assertNull(MrzParser.parse(corrupted))
    }

    @Test
    fun `returns null when there is no mrz at all`() {
        assertNull(MrzParser.parse("PASSPORT UNITED KINGDOM ERIKSSON ANNA MARIA"))
        assertNull(MrzParser.parse(""))
    }

    @Test
    fun `strips filler from a short document number`() {
        val document = "AB2134<<<"
        val check = Icao9303.checkDigit(document)
        val line = "$document${check}UTO7408122F1204159ZE184226B<<<<<1"
        assertEquals("AB2134", MrzParser.parse(line)?.documentNumber)
    }
}
