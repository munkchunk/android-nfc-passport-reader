package io.github.munkchunk.passportreader.sample.ui

import io.github.munkchunk.passportreader.sample.mrz.MrzDates
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.Locale

/**
 * Display forms for DG11, DG12, DG13 and DG16 values, which the library passes
 * through as the chip stores them.
 *
 * ICAO 9303 Part 10 gives those fields MRZ conventions without the MRZ's
 * limits: names keep `<<` between primary and secondary identifier and `<`
 * between words, and dates carry a four-digit year. Issuers do not all follow
 * it, so anything that does not fit the expected form is shown as stored
 * rather than guessed at.
 */
object ChipFields {

    private val FULL_DATE = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT)
    private val FULL_DATE_TIME = DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.UK)

    /** `ERIKSSON<<ANNA<MARIA` becomes `ERIKSSON, ANNA MARIA`. */
    fun name(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split("<<", limit = 2).map { words(it) }
        return parts.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }
    }

    /** Each entry with its fillers made spaces, blanks dropped, comma separated. */
    fun list(items: List<String>?): String? =
        items.orEmpty().map { words(it) }.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { null }

    /** Several names, one per line, each in [name]'s form. */
    fun names(items: List<String>?): String? =
        items.orEmpty().mapNotNull { name(it) }.joinToString("\n").ifEmpty { null }

    /** `YYYYMMDD` in the same form as the MRZ dates; anything else as stored. */
    fun date(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        return try {
            MrzDates.format(LocalDate.parse(trimmed, FULL_DATE))
        } catch (e: DateTimeParseException) {
            trimmed
        }
    }

    /** `YYYYMMDDhhmmss` as a date and a time; anything else as stored. */
    fun dateTime(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        return try {
            val parsed = LocalDateTime.parse(trimmed, FULL_DATE_TIME)
            "${MrzDates.format(parsed.toLocalDate())} ${parsed.format(TIME)}"
        } catch (e: DateTimeParseException) {
            trimmed
        }
    }

    /** DG16's `123 MAPLE RD<ANYTOWN<MN` as `123 MAPLE RD, ANYTOWN, MN`. */
    fun address(raw: String?): String? = list(raw?.split('<'))

    /**
     * Bytes as hex pairs, space separated so the line can wrap, stopping
     * after [limit] with an ellipsis. For DG13, whose format only its issuer
     * knows: showing the bytes is the one display that cannot misread them.
     */
    fun hex(bytes: ByteArray, limit: Int): String {
        val shown = bytes.take(limit).joinToString(" ") { "%02X".format(it.toUByte().toInt()) }
        return if (bytes.size > limit) "$shown …" else shown
    }

    private fun words(raw: String): String =
        raw.replace('<', ' ').trim().replace(Regex("\\s+"), " ")
}
