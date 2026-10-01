package io.github.munkchunk.passportreader.sample.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Field labels: small, heavy, spaced and upper case, the way a data page prints
 * them. Distinct enough from the values that the eye separates the two columns
 * without a rule between them.
 */
val FieldLabel = TextStyle(
    fontSize = 10.sp,
    lineHeight = 14.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.9.sp
)

/** Field values. */
val FieldValue = TextStyle(
    fontSize = 15.sp,
    lineHeight = 20.sp,
    fontWeight = FontWeight.SemiBold
)

/**
 * Anything whose character positions matter: the MRZ lines, serials and
 * fingerprints. Monospaced because an MRZ read in a proportional face is
 * genuinely harder to check against the document in your hand. Not used for
 * field values, even document numbers and dates: a panel mixing two faces
 * reads as inconsistent rather than as a distinction.
 */
val MonoSmall = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    lineHeight = 16.sp
)

/**
 * The EXPIRED pill. Smaller and tighter than a field label so it fits beside
 * the date rather than pushing onto its own line.
 */
val ExpiredPill = TextStyle(
    fontSize = 9.sp,
    lineHeight = 12.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 0.6.sp
)

/** Panel headings. */
val SectionLabel = TextStyle(
    fontSize = 11.sp,
    lineHeight = 15.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 1.0.sp
)

val PassportTypography = Typography(
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp
    ),
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold
    ),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(
        fontSize = 15.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold
    )
)
