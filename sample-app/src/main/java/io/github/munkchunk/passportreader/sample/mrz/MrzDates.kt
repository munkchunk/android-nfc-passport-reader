package io.github.munkchunk.passportreader.sample.mrz

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * MRZ dates are `YYMMDD` with no century, so one has to be inferred.
 *
 * ICAO 9303 does not mandate a rule, and the right one differs by field: a date
 * of birth is always in the past, while an expiry may be either side of today.
 * Read naively a passport expiring in 2012 lands in 2112, and one issued to
 * someone born in 1974 lands in 2074.
 */
object MrzDates {

    private val DISPLAY = DateTimeFormatter.ofPattern("dd MMM uuuu", Locale.UK)

    /** A birth date cannot be in the future, so a year that would be gets the previous century. */
    fun parseDateOfBirth(yymmdd: String, today: LocalDate = LocalDate.now()): LocalDate? =
        parse(yymmdd) { candidate -> if (candidate.isAfter(today)) candidate.minusYears(100) else candidate }

    /**
     * An expiry may be past or future. Passports run to about eleven years, so
     * anything landing more than twenty years out is really a past century.
     */
    fun parseDateOfExpiry(yymmdd: String, today: LocalDate = LocalDate.now()): LocalDate? =
        parse(yymmdd) { candidate ->
            if (candidate.isAfter(today.plusYears(20))) candidate.minusYears(100) else candidate
        }

    private fun parse(yymmdd: String, adjust: (LocalDate) -> LocalDate): LocalDate? {
        if (yymmdd.length != 6 || !yymmdd.all { it.isDigit() }) return null
        val year = yymmdd.substring(0, 2).toInt()
        val month = yymmdd.substring(2, 4).toInt()
        val day = yymmdd.substring(4, 6).toInt()
        if (month !in 1..12 || day !in 1..31) return null

        // Start in the current century, then let the caller's rule pull it back.
        val base = try {
            LocalDate.of(2000 + year, month, day)
        } catch (e: java.time.DateTimeException) {
            return null
        }
        return adjust(base)
    }

    fun format(date: LocalDate): String = date.format(DISPLAY).uppercase(Locale.UK)

    /** Formats for display, falling back to the raw MRZ digits if unparseable. */
    fun formatDateOfBirth(yymmdd: String?): String? =
        yymmdd?.let { parseDateOfBirth(it)?.let(::format) ?: it }

    fun formatDateOfExpiry(yymmdd: String?): String? =
        yymmdd?.let { parseDateOfExpiry(it)?.let(::format) ?: it }

    /** True only when the date parses and is in the past. */
    fun isExpired(yymmdd: String?, today: LocalDate = LocalDate.now()): Boolean {
        val expiry = yymmdd?.let { parseDateOfExpiry(it, today) } ?: return false
        return expiry.isBefore(today)
    }
}
