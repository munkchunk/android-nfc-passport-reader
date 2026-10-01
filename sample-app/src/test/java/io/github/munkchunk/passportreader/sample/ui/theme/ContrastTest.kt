package io.github.munkchunk.passportreader.sample.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import io.github.munkchunk.passportreader.sample.ui.SCAN_SCRIM_ALPHA
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Every colour pair on screen that someone needs to see, checked against WCAG
 * 2.2 AA in both themes. Decorative pairs are named as exempt in [pairs]. See docs/sample-app.md.
 *
 * The pairs are the real ones: each names the screen element it stands for.
 * A new pairing on screen wants a new row here.
 *
 * [KNOWN_FAILURES] holds the pairs that fall short today. The test asserts it
 * is exactly the failing set, so it fails if a new pair falls short, and also
 * if a known one is fixed without being struck off. The list must end empty.
 */
class ContrastTest {

    private class ColourPair(val name: String, val fg: Color, val bg: Color, val minimum: Double)

    private fun pairs(theme: String, c: ColorScheme, v: VerdictColors, b: BrandColors): List<ColourPair> {
        fun text(name: String, fg: Color, bg: Color) = ColourPair("$theme: $name", fg, bg, TEXT)
        fun graphic(name: String, fg: Color, bg: Color) = ColourPair("$theme: $name", fg, bg, NON_TEXT)

        // The camera feed can be anything; white paper is the worst case for
        // white text on the scrim.
        val scrimOverWhite = Color.Black.copy(alpha = SCAN_SCRIM_ALPHA).compositeOver(Color.White)

        // Exempt as decorative, and so not listed: the borders on the photo, MRZ
        // and chip-image boxes, dividers, the certificate chain's connector, the
        // expired pill's edge (its words carry the meaning), the reading-screen
        // illustration, the header's rule, and disabled controls.
        return listOf(
            // Page
            text("body text on page", c.onBackground, c.background),
            text("muted text on page", c.onSurfaceVariant, c.background),
            text("text button on page", b.text, c.background),
            text("field error on page", c.error, c.background),
            text("field input on page", c.onSurface, c.background),
            text("focused field label on page", b.text, c.background),

            // Brand
            text("header title on header", b.onHeader, b.header),
            text("on-primary on primary (filled button, cert role)", c.onPrimary, c.primary),

            // Panels
            text("body text on panel", c.onSurface, c.surfaceVariant),
            text("muted text on panel", c.onSurfaceVariant, c.surfaceVariant),
            text("text button on panel (warning card)", b.text, c.surfaceVariant),
            text("upcoming stage on panel", c.onSurfaceVariant, c.surfaceVariant),
            text("failed check on panel", v.fail, c.surfaceVariant),
            text("tally pass on panel", v.pass, c.surfaceVariant),
            text("tally or expiry unknown on panel", v.unknown, c.surfaceVariant),

            // Inset boxes inside a panel: MRZ, images
            text("muted text on inset", c.onSurfaceVariant, c.surface),

            // Verdict marks and the expired pill, each on its own container.
            // Held to the text minimum: docs/sample-app.md promises it.
            text("pass mark on its container", v.pass, v.passContainer),
            text("fail mark on its container", v.fail, v.failContainer),
            text("unknown mark and expired pill on container", v.unknown, v.unknownContainer),
            text("absent mark on its container", v.absent, v.absentContainer),

            // Components and state indicators
            graphic("text field border on page", c.outline, c.background),
            graphic("focused text field border on page", b.text, c.background),
            graphic("cursor handle on page", b.text, c.background),
            graphic("progress bar fill on its track", v.pass, c.outlineVariant),
            graphic("upcoming stage ring on panel", c.outline, c.surfaceVariant),
            graphic("active stage dot on panel", b.text, c.surfaceVariant),
            graphic("done stage dot on panel", v.pass, c.surfaceVariant),

            // Scanner: fixed white over the camera, whatever the theme
            text("scan hint on scrim over white", Color.White, scrimOverWhite)
        )
    }

    private val all = pairs("light", LightScheme, LightVerdicts, LightBrand) +
        pairs("dark", DarkScheme, DarkVerdicts, DarkBrand)

    @Test
    fun `only the known failures fall short of AA`() {
        val failing = all.filter { contrast(it.fg, it.bg) < it.minimum }.map { it.name }.toSet()
        assertEquals(report(failing), KNOWN_FAILURES, failing)
    }

    @Test
    fun `contrast matches the WCAG reference points`() {
        assertEquals(21.0, contrast(Color.Black, Color.White), 0.001)
        assertEquals(1.0, contrast(Color.White, Color.White), 0.001)
        // #767676 on white is the usual example of a grey that just passes.
        assertEquals(4.54, contrast(Color(0xFF767676), Color.White), 0.01)
    }

    /** What changed, then every pair with its ratio; shown when the first test fails. */
    private fun report(failing: Set<String>): String {
        val newlyFailing = failing - KNOWN_FAILURES
        val fixedButListed = KNOWN_FAILURES - failing
        val table = all.joinToString("\n") {
            val ratio = contrast(it.fg, it.bg)
            val mark = if (ratio < it.minimum) "FAIL" else "ok  "
            "$mark ${String.format(Locale.ROOT, "%5.2f", ratio)} (min ${it.minimum}) ${it.name}"
        }
        return "\nNewly failing: $newlyFailing\nFixed, still listed: $fixedButListed\n$table\n"
    }

    companion object {
        private const val TEXT = 4.5
        private const val NON_TEXT = 3.0

        /** Empty: every pair meets AA. A pair may be added here only while it is being fixed. */
        private val KNOWN_FAILURES = emptySet<String>()

        /** WCAG 2.x contrast ratio, from sRGB relative luminance. */
        fun contrast(a: Color, b: Color): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }

        private fun luminance(c: Color): Double =
            0.2126 * linear(c.red) + 0.7152 * linear(c.green) + 0.0722 * linear(c.blue)

        private fun linear(channel: Float): Double {
            val s = channel.toDouble()
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
    }
}
