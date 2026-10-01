package io.github.munkchunk.passportreader.sample.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChipFieldsTest {

    @Test
    fun `name splits primary from secondary identifier`() {
        assertEquals("ERIKSSON, ANNA MARIA", ChipFields.name("ERIKSSON<<ANNA<MARIA"))
    }

    @Test
    fun `name without a double filler is words only`() {
        assertEquals("ANNA MARIA ERIKSSON", ChipFields.name("ANNA<MARIA<ERIKSSON"))
    }

    @Test
    fun `name with only a primary identifier drops the empty half`() {
        assertEquals("ERIKSSON", ChipFields.name("ERIKSSON<<"))
    }

    @Test
    fun `name already in plain text is left alone`() {
        assertEquals("Anna Maria Eriksson", ChipFields.name("Anna Maria Eriksson"))
    }

    @Test
    fun `blank name is null`() {
        assertNull(ChipFields.name("<<"))
        assertNull(ChipFields.name(null))
    }

    @Test
    fun `list drops blanks and fillers`() {
        assertEquals("ZENITH, UTOPIA", ChipFields.list(listOf("ZENITH", "", "UTOPIA<")))
        assertNull(ChipFields.list(emptyList()))
    }

    @Test
    fun `names are one per line`() {
        assertEquals(
            "ERIKSSON, LARS\nERIKSSON, SOFIA",
            ChipFields.names(listOf("ERIKSSON<<LARS", "ERIKSSON<<SOFIA"))
        )
    }

    @Test
    fun `full date is formatted like the MRZ dates`() {
        assertEquals("12 AUG 1974", ChipFields.date("19740812"))
    }

    @Test
    fun `impossible or foreign dates are shown as stored`() {
        assertEquals("19740231", ChipFields.date("19740231"))
        assertEquals("740812", ChipFields.date("740812"))
        assertEquals("12.08.1974", ChipFields.date("12.08.1974"))
    }

    @Test
    fun `date and time of personalisation`() {
        assertEquals("02 MAR 2021 14:05:09", ChipFields.dateTime("20210302140509"))
        assertEquals("2021-03-02", ChipFields.dateTime("2021-03-02"))
    }

    @Test
    fun `a DG16 address is split at its fillers`() {
        assertEquals("123 MAPLE RD, ANYTOWN, MN, 55100", ChipFields.address("123 MAPLE RD<ANYTOWN<MN<55100"))
        assertNull(ChipFields.address(null))
        assertNull(ChipFields.address("<<"))
    }

    @Test
    fun `DG13 bytes are hex pairs, cut off with an ellipsis`() {
        val bytes = byteArrayOf(0x6D, 0x03, 0x00, 0x7F, -1)
        assertEquals("6D 03 00 7F FF", ChipFields.hex(bytes, limit = 5))
        assertEquals("6D 03 …", ChipFields.hex(bytes, limit = 2))
        assertEquals("", ChipFields.hex(ByteArray(0), limit = 5))
    }
}
