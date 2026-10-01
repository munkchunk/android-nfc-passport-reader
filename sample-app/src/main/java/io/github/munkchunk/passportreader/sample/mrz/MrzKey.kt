package io.github.munkchunk.passportreader.sample.mrz

/**
 * The three MRZ fields that make up the BAC/PACE key.
 *
 * Dates are the raw 6-digit MRZ form (YYMMDD), not a display format.
 */
data class MrzKey(
    val documentNumber: String,
    val dateOfBirth: String,
    val dateOfExpiry: String
)

/**
 * ICAO 9303 check digit, part 3 section 4.9.
 *
 * Each character is weighted by a repeating 7-3-1 pattern and summed mod 10.
 * Digits score their face value, letters score A=10 through Z=35, and the
 * filler '<' scores 0.
 */
object Icao9303 {

    fun checkDigit(input: String): Int {
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        input.forEachIndexed { index, char ->
            sum += charValue(char) * weights[index % 3]
        }
        return sum % 10
    }

    private fun charValue(char: Char): Int = when (char) {
        in '0'..'9' -> char - '0'
        in 'A'..'Z' -> char - 'A' + 10
        '<' -> 0
        else -> throw IllegalArgumentException("Character not valid in an MRZ field: '$char'")
    }

    /** True if [input] contains only characters the MRZ alphabet allows. */
    fun isMrzAlphabet(input: String): Boolean =
        input.all { it in '0'..'9' || it in 'A'..'Z' || it == '<' }
}

/**
 * Why a field was rejected.
 *
 * A reason rather than a sentence, because these are used two ways: the entry
 * screen turns them into text, and [MrzParser] uses them as a plain yes/no on a
 * candidate reading. Only the first needs words, and putting them here would
 * have meant the parser depending on a Context to reject a bad date.
 */
enum class MrzFieldError {
    REQUIRED,
    DOCUMENT_TOO_LONG,
    DOCUMENT_ALPHABET,
    DATE_WRONG_LENGTH,
    DATE_NOT_DIGITS,
    DATE_MONTH_RANGE,
    DATE_DAY_RANGE,
}

/** Why the field was rejected, or null when it is usable. */
fun validateDocumentNumber(value: String): MrzFieldError? = when {
    value.isEmpty() -> MrzFieldError.REQUIRED
    value.length > 9 -> MrzFieldError.DOCUMENT_TOO_LONG
    !Icao9303.isMrzAlphabet(value) -> MrzFieldError.DOCUMENT_ALPHABET
    else -> null
}

fun validateMrzDate(value: String): MrzFieldError? = when {
    value.isEmpty() -> MrzFieldError.REQUIRED
    value.length != 6 -> MrzFieldError.DATE_WRONG_LENGTH
    !value.all { it.isDigit() } -> MrzFieldError.DATE_NOT_DIGITS
    value.substring(2, 4).toInt() !in 1..12 -> MrzFieldError.DATE_MONTH_RANGE
    value.substring(4, 6).toInt() !in 1..31 -> MrzFieldError.DATE_DAY_RANGE
    else -> null
}

/**
 * Document numbers shorter than 9 characters are padded with '<' before the
 * check digit is computed, which is also the form the chip expects.
 */
fun String.padDocumentNumber(): String = padEnd(9, '<')
