package io.github.munkchunk.passportreader.sample.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours for verification outcomes.
 *
 * These sit outside the Material scheme on purpose. Material's `primary` and
 * `error` describe the app's identity and its own failures; a passport check
 * that did not pass is neither. Keeping them separate also stops a future theme
 * change from quietly altering what a tick means.
 */
data class VerdictColors(
    val pass: Color,
    val passContainer: Color,
    val fail: Color,
    val failContainer: Color,
    val unknown: Color,
    val unknownContainer: Color,
    val unknownEdge: Color,
    val absent: Color,
    val absentContainer: Color
)

internal val LightVerdicts = VerdictColors(
    pass = PassLight,
    passContainer = PassContainerLight,
    fail = FailLight,
    failContainer = FailContainerLight,
    unknown = UnknownLight,
    unknownContainer = UnknownContainerLight,
    unknownEdge = UnknownEdgeLight,
    absent = AbsentLight,
    absentContainer = AbsentContainerLight
)

internal val DarkVerdicts = VerdictColors(
    pass = PassDark,
    passContainer = PassContainerDark,
    fail = FailDark,
    failContainer = FailContainerDark,
    unknown = UnknownDark,
    unknownContainer = UnknownContainerDark,
    unknownEdge = UnknownEdgeDark,
    absent = AbsentDark,
    absentContainer = AbsentContainerDark
)

/**
 * The brand where Material's scheme has no role for it.
 *
 * [text] is the brand as a foreground (see BrandTextDark). The header is white
 * with a rule under it in light mode, so the title has its own zone without a
 * heavy block of colour, and solid brand green in dark mode, where the block
 * sits close to the page. [headerRule] is null where there is no rule.
 */
data class BrandColors(
    val text: Color,
    val header: Color,
    val onHeader: Color,
    val headerRule: Color?
)

internal val LightBrand = BrandColors(
    text = BrandTextLight,
    header = PanelLight,
    onHeader = BrandGreen,
    headerRule = LineLight
)

internal val DarkBrand = BrandColors(
    text = BrandTextDark,
    header = BrandGreenDark,
    onHeader = OnBrandDark,
    headerRule = null
)

private val LocalBrandColors = staticCompositionLocalOf { LightBrand }

/** `MaterialTheme.brand.text` and friends. */
val MaterialTheme.brand: BrandColors
    @Composable @ReadOnlyComposable get() = LocalBrandColors.current

private val LocalVerdictColors = staticCompositionLocalOf { LightVerdicts }

/** `MaterialTheme.verdicts.pass` and friends. */
val MaterialTheme.verdicts: VerdictColors
    @Composable @ReadOnlyComposable get() = LocalVerdictColors.current

internal val LightScheme = lightColorScheme(
    primary = BrandGreen,
    onPrimary = OnBrand,
    secondary = BrandGreen,
    onSecondary = OnBrand,
    background = GroundLight,
    onBackground = InkLight,
    surface = SurfaceLight,
    onSurface = InkLight,
    surfaceVariant = PanelLight,
    onSurfaceVariant = MutedLight,
    outline = BorderLight,
    outlineVariant = LineLight,
    error = FailLight,
    onError = Color.White,
    errorContainer = FailContainerLight,
    onErrorContainer = FailLight
)

internal val DarkScheme = darkColorScheme(
    primary = BrandGreenDark,
    onPrimary = OnBrandDark,
    secondary = BrandGreenDark,
    onSecondary = OnBrandDark,
    background = GroundDark,
    onBackground = InkDark,
    surface = SurfaceDark,
    onSurface = InkDark,
    surfaceVariant = PanelDark,
    onSurfaceVariant = MutedDark,
    outline = BorderDark,
    outlineVariant = LineDark,
    error = FailDark,
    onError = Color(0xFF3A0B08),
    errorContainer = FailContainerDark,
    onErrorContainer = FailDark
)

/**
 * No dynamic colour. The palette carries meaning here - a passed check has to
 * look the same on every phone - so it is not handed over to the wallpaper.
 */
@Composable
fun PassportReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalVerdictColors provides if (darkTheme) DarkVerdicts else LightVerdicts,
        LocalBrandColors provides if (darkTheme) DarkBrand else LightBrand
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = PassportTypography
        ) {
            // Inside MaterialTheme, which sets its own. Material draws the cursor
            // handle and selection in `primary`, which in dark mode is too dark
            // to see (2.9:1); the brand's text colour instead.
            CompositionLocalProvider(
                LocalTextSelectionColors provides selectionColors(if (darkTheme) DarkBrand else LightBrand),
                content = content
            )
        }
    }
}

/** A text button in the brand's text colour, which in dark mode is not `primary`. */
@Composable
fun brandTextButtonColors(): ButtonColors =
    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.brand.text)

/**
 * An outlined field whose focus colours are the brand's text colour rather than
 * `primary`, which in dark mode is too dark to read as a label or a border.
 */
@Composable
fun brandTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = MaterialTheme.brand.text,
    focusedLabelColor = MaterialTheme.brand.text,
    cursorColor = MaterialTheme.brand.text
)

private fun selectionColors(brand: BrandColors) = TextSelectionColors(
    handleColor = brand.text,
    backgroundColor = brand.text.copy(alpha = SELECTION_ALPHA)
)

// Material's own proportion for the selection highlight behind text.
private const val SELECTION_ALPHA = 0.4f
