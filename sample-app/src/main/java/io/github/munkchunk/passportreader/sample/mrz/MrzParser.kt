package io.github.munkchunk.passportreader.sample.mrz

/**
 * Extracts the BAC/PACE key fields from the second line of a TD3 machine
 * readable zone, and only returns a result the check digits agree with.
 *
 * TD3 line 2, 44 characters:
 * ```
 * 0  ..8   document number          9 chars
 * 9        document number check    1
 * 10 ..12  nationality              3
 * 13 ..18  date of birth YYMMDD     6
 * 19       date of birth check      1
 * 20       sex                      1
 * 21 ..26  date of expiry YYMMDD    6
 * 27       date of expiry check     1
 * 28 ..42  personal number + check  15
 * 43       composite check          1
 * ```
 * Only the first 28 characters are needed to unlock a chip.
 *
 * OCR reliably confuses a handful of glyphs in the OCR-B font - `O` for `0`,
 * `I` for `1`, `S` for `5` and so on. Rather than substituting blindly and
 * hoping, as is tempting, this tries the plausible readings and lets the check
 * digits decide which one is right. A field that fails its check digit is
 * discarded, so a misread is rejected rather than passed to the chip, where it
 * would surface much later as a confusing authentication failure.
 */
object MrzParser {

    /** Characters OCR mistakes for a digit, mapped to the digit they resemble. */
    private val DIGIT_LOOKALIKES = mapOf(
        'O' to '0', 'Q' to '0', 'D' to '0',
        'I' to '1', 'L' to '1',
        'Z' to '2',
        'S' to '5',
        'G' to '6',
        'T' to '7',
        'B' to '8',
    )

    /**
     * Permissive by design: digit positions also accept the glyphs OCR
     * confuses them with, so a candidate is not rejected before the check
     * digits have had their say.
     */
    private const val DIGITISH = "[0-9OQDILZSGTB]"

    private val CANDIDATE = Regex(
        "[A-Z0-9<]{9}" +      // document number
            DIGITISH +        // its check digit
            "[A-Z<]{3}" +     // nationality
            "$DIGITISH{6}" +  // date of birth
            DIGITISH +        // its check digit
            "[MF<]" +         // sex
            "$DIGITISH{6}" +  // date of expiry
            DIGITISH          // its check digit
    )

    /**
     * Scans [text] - typically everything ML Kit found in one frame - for a
     * usable MRZ.
     *
     * @return a key whose three check digits all verify, or null.
     */
    fun parse(text: String): MrzKey? {
        val normalised = text
            .uppercase()
            .replace(Regex("[^A-Z0-9<]"), "")

        // A frame often yields several candidates; take the first that verifies.
        return CANDIDATE.findAll(normalised)
            .mapNotNull { resolve(it.value) }
            .firstOrNull()
    }

    /** Turns one candidate window into a key, or null if it cannot be trusted. */
    private fun resolve(candidate: String): MrzKey? {
        val documentNumber = candidate.substring(0, 9)
        val documentCheck = candidate[9]

        // A document number is left-justified and padded with '<' on the right
        // only, so filler anywhere else means characters that were not in shot
        // were read as filler.
        //
        // The check digit cannot be relied on to catch this, which is the whole
        // reason for the test: '<' scores 0, so losing a prefix whose weighted
        // sum is a multiple of ten leaves the check digit identical. Panning
        // can clip four characters off the left edge with a weighted sum of
        // exactly 60, leaving the check digit unchanged, and the wrong key then
        // reads identically in consecutive frames. Agreement across frames is
        // no defence, because a misread this systematic agrees with itself. Over random 9-character
        // numbers, 41.5% admit some 1-5 character left-clip that collides.
        if (!DOCUMENT_NUMBER_SHAPE.matches(documentNumber)) return null
        val dateOfBirth = candidate.substring(13, 19)
        val birthCheck = candidate[19]
        val dateOfExpiry = candidate.substring(21, 27)
        val expiryCheck = candidate[27]

        // Dates are digits only, so lookalikes there are unambiguous errors.
        val birth = digitsOnly(dateOfBirth) ?: return null
        val expiry = digitsOnly(dateOfExpiry) ?: return null

        val birthCheckDigit = asDigit(birthCheck) ?: return null
        val expiryCheckDigit = asDigit(expiryCheck) ?: return null
        val documentCheckDigit = asDigit(documentCheck) ?: return null

        if (Icao9303.checkDigit(birth) != birthCheckDigit) return null
        if (Icao9303.checkDigit(expiry) != expiryCheckDigit) return null

        // The document number is alphanumeric, so a lookalike may be genuine.
        // Try the readings and let the check digit pick.
        val document = documentNumberCandidates(documentNumber)
            .firstOrNull { Icao9303.checkDigit(it) == documentCheckDigit }
            ?: return null

        // Dates must still be real dates; a check digit cannot tell month 19
        // from month 09.
        if (validateMrzDate(birth) != null) return null
        if (validateMrzDate(expiry) != null) return null

        return MrzKey(
            documentNumber = document.trimEnd('<'),
            dateOfBirth = birth,
            dateOfExpiry = expiry
        )
    }

    /**
     * The readings of a document number worth trying, cheapest first: as read,
     * then with each combination of its ambiguous characters resolved to the
     * digit they resemble.
     *
     * Bounded deliberately - with more than [MAX_AMBIGUOUS] ambiguous
     * characters the combinations grow faster than the confidence in any of
     * them, and a frame that unclear is better discarded than guessed at.
     */
    private fun documentNumberCandidates(raw: String): Sequence<String> {
        val ambiguous = raw.indices.filter { raw[it] in DIGIT_LOOKALIKES }
        if (ambiguous.size > MAX_AMBIGUOUS) return sequenceOf(raw)

        return sequence {
            yield(raw)
            // Each bit picks whether that position keeps its letter or becomes
            // the digit it resembles.
            for (mask in 1 until (1 shl ambiguous.size)) {
                val chars = raw.toCharArray()
                ambiguous.forEachIndexed { bit, index ->
                    if (mask and (1 shl bit) != 0) {
                        chars[index] = DIGIT_LOOKALIKES.getValue(raw[index])
                    }
                }
                yield(String(chars))
            }
        }
    }

    /** Resolves a digit field, or null if it holds something that is not one. */
    private fun digitsOnly(field: String): String? {
        val resolved = field.map { char ->
            when {
                char.isDigit() -> char
                char in DIGIT_LOOKALIKES -> DIGIT_LOOKALIKES.getValue(char)
                else -> return null
            }
        }
        return String(resolved.toCharArray())
    }

    private fun asDigit(char: Char): Int? = when {
        char.isDigit() -> char - '0'
        char in DIGIT_LOOKALIKES -> DIGIT_LOOKALIKES.getValue(char) - '0'
        else -> null
    }

    private const val MAX_AMBIGUOUS = 6

    /** Left-justified, filler only as a trailing pad. ICAO 9303 part 3. */
    private val DOCUMENT_NUMBER_SHAPE = Regex("[A-Z0-9]+<*")

    /**
     * Characters of line 2 that must be in shot: document number through the
     * expiry check digit. The remaining 16 - personal number and composite
     * check - are never read, which is why the scanner asks for the *start* of
     * the row rather than all of it.
     */
    const val KEY_FIELD_LENGTH = 28
}
