package io.github.munkchunk.passportreader.mapping

import io.github.munkchunk.passportreader.data.PersonToNotify
import net.sf.scuba.tlv.TLVInputStream
import java.io.ByteArrayInputStream
import java.io.IOException

/*
 * DG16, Person(s) to Notify, from the layout in ICAO 9303-10 §4.7.16 and its
 * worked example in Appendix A.6. JMRTD has no class for DG16, so this reads
 * the file's bytes directly.
 *
 *   '70'                       DG16
 *     '02' '01'                number of entries
 *     'A1'                     first entry; 'A2', 'A3', ... after it
 *       '5F50' '08'            date recorded, YYYYMMDD
 *       '5F51'                 name
 *       '5F52'                 telephone
 *       '5F53'                 address
 *
 * Table 80 says the count "occurs only in first template" while A.6 puts it
 * beside the templates, so it is accepted in either place. It is not needed
 * to find the entries, and a count that disagrees with them is ignored: the
 * entries themselves are what EF.SOd's hash covers.
 */

private const val DG16_TAG = 0x70
private const val DATE_RECORDED_TAG = 0x5F50
private const val NAME_TAG = 0x5F51
private const val TELEPHONE_TAG = 0x5F52
private const val ADDRESS_TAG = 0x5F53

/** 'A1' to 'BE': a one-byte context-specific constructed tag, which is what 'Ax' allows. */
private const val FIRST_ENTRY_TAG = 0xA1
private const val LAST_ENTRY_TAG = 0xBE
private val ENTRY_TAGS = FIRST_ENTRY_TAG..LAST_ENTRY_TAG

/**
 * The entries in an EF.DG16, as the chip stores it.
 *
 * @throws IOException when the file is not a DG16, or its structure is broken.
 */
internal fun parsePersonsToNotify(file: ByteArray): List<PersonToNotify> {
    val (tag, content) = elements(file).firstOrNull() ?: throw IOException("Empty DG16")
    if (tag != DG16_TAG) throw IOException("Not DG16: tag %X".format(tag))
    return elements(content)
        .filter { (tag, _) -> tag in ENTRY_TAGS }
        .map { (_, value) -> personToNotify(value) }
}

private fun personToNotify(entry: ByteArray): PersonToNotify {
    val fields = elements(entry).toMap()
    return PersonToNotify(
        dateRecorded = fields[DATE_RECORDED_TAG]?.text(),
        name = fields[NAME_TAG]?.text(),
        telephone = fields[TELEPHONE_TAG]?.text(),
        address = fields[ADDRESS_TAG]?.text(),
    )
}

/**
 * The data objects one level down in [bytes], as (tag, value), in order; the
 * '02' count included.
 *
 * Each length is checked against the bytes actually left before its value is
 * read. SCUBA allocates whatever length it is given, so a chip claiming
 * '84 7FFFFFF0' would otherwise raise OutOfMemoryError - an Error, which no
 * catch on the read path stops.
 */
private fun elements(bytes: ByteArray): List<Pair<Int, ByteArray>> {
    val input = ByteArrayInputStream(bytes)
    val tlv = TLVInputStream(input)
    val found = mutableListOf<Pair<Int, ByteArray>>()
    while (input.available() > 0) {
        val tag = tlv.readTag()
        val length = tlv.readLength()
        if (length < 0 || length > input.available()) {
            throw IOException("Length $length overruns DG16 at tag %X".format(tag))
        }
        found += tag to tlv.readValue()
    }
    return found
}

/** ICAO 9303-10 text fields are UTF-8. */
private fun ByteArray.text(): String = toString(Charsets.UTF_8)
