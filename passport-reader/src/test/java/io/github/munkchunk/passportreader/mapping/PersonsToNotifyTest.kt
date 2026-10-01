package io.github.munkchunk.passportreader.mapping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * DG16 parsing, without a passport that carries it. Neither test document
 * has DG16, and JMRTD cannot build one, so the vectors are written out here.
 */
class PersonsToNotifyTest {

    /**
     * ICAO 9303-10 Appendix A.6, the two-entry example, encoded byte for byte.
     * Its lengths are the spec's own - '81A2' for the file, '4C' and '4F' for
     * the entries - which is the check that it was transcribed correctly.
     */
    private val icaoExample = hex(
        "7081A2020102" +
            "A14C" +
            "5F50083230303230313031" +
            "5F5110534D4954483C3C434841524C45533C52" +
            "5F520B3139353235353531323132" +
            "5F531D313233204D41504C452052443C414E59544F574E3C4D4E3C3535313030" +
            "A24F" +
            "5F50083230303230333135" +
            "5F510D42524F574E3C3C4D4152593C4A" +
            "5F520B3134313535353531323132" +
            "5F5323343920524544574F4F44204C4E3C4F4345414E20425245455A453C43413C3934303030"
    )

    @Test
    fun `the ICAO example gives both entries, every field`() {
        val persons = parsePersonsToNotify(icaoExample)

        assertEquals(2, persons.size)
        with(persons[0]) {
            assertEquals("20020101", dateRecorded)
            assertEquals("SMITH<<CHARLES<R", name)
            assertEquals("19525551212", telephone)
            assertEquals("123 MAPLE RD<ANYTOWN<MN<55100", address)
        }
        with(persons[1]) {
            assertEquals("20020315", dateRecorded)
            assertEquals("BROWN<<MARY<J", name)
            assertEquals("14155551212", telephone)
            assertEquals("49 REDWOOD LN<OCEAN BREEZE<CA<94000", address)
        }
    }

    /** Table 80 puts the count inside the first entry, where A.6 does not. */
    @Test
    fun `a count inside the first entry is accepted`() {
        val file = tlv(
            "70",
            tlv("A1", tlv("02", byteArrayOf(1)) + tlv("5F51", "SMITH<<CHARLES<R".toByteArray()))
        )

        val persons = parsePersonsToNotify(file)

        assertEquals(1, persons.size)
        assertEquals("SMITH<<CHARLES<R", persons[0].name)
    }

    @Test
    fun `a field the entry leaves out is null`() {
        val file = tlv("70", tlv("02", byteArrayOf(1)) + tlv("A1", tlv("5F51", "BROWN<<MARY<J".toByteArray())))

        val person = parsePersonsToNotify(file).single()

        assertEquals("BROWN<<MARY<J", person.name)
        assertNull(person.dateRecorded)
        assertNull(person.telephone)
        assertNull(person.address)
    }

    @Test
    fun `a count that disagrees with the entries is ignored`() {
        val file = tlv("70", tlv("02", byteArrayOf(3)) + tlv("A1", tlv("5F51", "A<<B".toByteArray())))

        assertEquals(1, parsePersonsToNotify(file).size)
    }

    @Test
    fun `no entries is an empty list`() {
        assertTrue(parsePersonsToNotify(tlv("70", tlv("02", byteArrayOf(0)))).isEmpty())
    }

    /** The address is free-form text, which ICAO 9303-10 encodes as UTF-8. */
    @Test
    fun `text is read as UTF-8`() {
        val address = "Øvre Slottsgate 1<Oslo"
        val file = tlv("70", tlv("A1", tlv("5F53", address.toByteArray(Charsets.UTF_8))))

        assertEquals(address, parsePersonsToNotify(file).single().address)
    }

    @Test
    fun `a file that is not DG16 is refused`() {
        // DG11's tag: the right shape, the wrong group.
        assertThrows(IOException::class.java) { parsePersonsToNotify(tlv("6B", tlv("A1", ByteArray(0)))) }
    }

    @Test
    fun `a truncated file is refused`() {
        assertThrows(IOException::class.java) { parsePersonsToNotify(icaoExample.copyOf(icaoExample.size - 10)) }
    }

    /**
     * A length is the chip's claim, not a fact. Taken on trust, '84 7FFFFFF0'
     * asks for a 2GB array, and the OutOfMemoryError that follows is not an
     * Exception, so it would get past every catch on the read path.
     */
    @Test
    fun `a length longer than the file is refused, not allocated`() {
        assertThrows(IOException::class.java) { parsePersonsToNotify(hex("70847FFFFFF0")) }
        assertThrows(IOException::class.java) { parsePersonsToNotify(hex("7006A104" + "5F51847FFFFFF0")) }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** A data object with a one- or two-byte tag and a short or '81' length - all these vectors need. */
    private fun tlv(tag: String, value: ByteArray): ByteArray {
        val length =
            if (value.size < 0x80) byteArrayOf(value.size.toByte()) else byteArrayOf(0x81.toByte(), value.size.toByte())
        return hex(tag) + length + value
    }
}
