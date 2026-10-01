package io.github.munkchunk.passportreader.sample.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.munkchunk.passportreader.sample.ui.theme.brand
import io.github.munkchunk.passportreader.sample.ui.theme.PassportCover
import io.github.munkchunk.passportreader.sample.ui.theme.PassportCoverDark
import io.github.munkchunk.passportreader.sample.ui.theme.PassportGold

/**
 * A phone being placed onto a passport, seen face on.
 *
 * The cover follows the ICAO layout every biometric passport shares - PASSPORT
 * along the top, an emblem, then ePASSPORT above the chip symbol - which is why
 * they are recognisable at a glance whoever issued them. Nothing here names a
 * country, and the phone carries no notch or badge.
 *
 * Where the chip sits varies by issuer, so the picture claims no location. It
 * shows the gesture; the text beneath names the places to try.
 *
 * While [active] the phone arcs in, rests for a couple of seconds, then fades
 * and comes round again - long enough to be read as an instruction rather than
 * a loop in motion. Once a chip is found it holds where it landed, because
 * movement then is what breaks the connection.
 *
 * It also takes back the room the arc needed. The phone starts its travel low
 * and to the right, so the bottom [1 - SETTLED_HEIGHT_FRACTION] of the frame
 * exists only to keep it from being clipped mid-flight; once settled that space
 * is empty, and on the reading screen it pushed the progress list down into the
 * Cancel button. The drawing geometry is unchanged - the canvas is simply
 * shorter, and the unused bottom of the design space falls outside it.
 */
@Composable
fun NfcPlacementGraphic(
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "placement")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = CYCLE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "cycle"
    )

    val measurer = rememberTextMeasurer()
    val cover = if (isSystemInDarkTheme()) PassportCoverDark else PassportCover
    val phoneFill = MaterialTheme.colorScheme.surface
    val phoneInk = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.brand.text

    // Full width in portrait. In landscape, full width filled the whole height
    // and pushed the instruction off the screen, so there it is capped to a
    // share of the height.
    val config = LocalConfiguration.current
    val maxWidth = if (config.screenWidthDp > config.screenHeightDp) {
        config.screenHeightDp.dp * LANDSCAPE_HEIGHT_FRACTION * DESIGN_ASPECT
    } else {
        Dp.Unspecified
    }
    Canvas(
        modifier = modifier
            .widthIn(max = maxWidth)
            .fillMaxWidth()
            .aspectRatio(if (active) DESIGN_ASPECT else SETTLED_ASPECT)
    ) {
        // Always the full design height, whatever the canvas reserves, so the
        // passport and phone keep their proportions either way.
        val w = size.width
        val h = w / DESIGN_ASPECT

        drawPassport(w, h, cover, PassportGold, measurer)

        // Settled once a chip is being read; travelling while waiting.
        val progress = if (active) travelAt(cycle) else 1f
        val alpha = if (active) alphaAt(cycle) else 1f
        drawPhone(w, h, phoneFill, phoneInk, accent, progress, alpha)
    }
}

/** The most of the screen's height the drawing may take in landscape. */
private const val LANDSCAPE_HEIGHT_FRACTION = 0.3f

/** Width to height while the phone is travelling and needs the whole frame. */
private const val DESIGN_ASPECT = 0.95f

/**
 * How much of that frame the settled composition actually occupies.
 *
 * Passport top 0.030, settled phone and its shadow bottom out at 0.701; the
 * rest is arc clearance. A little over that, so the shadow is not grazing the
 * edge.
 */
private const val SETTLED_HEIGHT_FRACTION = 0.72f

private const val SETTLED_ASPECT = DESIGN_ASPECT / SETTLED_HEIGHT_FRACTION

// One pass of the loop: 2.5s arcing in, 3s at rest, then a fade and round
// again. The rest has to outlast the arc, or the picture reads as something
// moving rather than as an instruction to put the phone down and leave it.
private const val CYCLE_MS = 6000
private const val ARRIVE_AT = 0.417f
private const val FADE_FROM = 0.917f
private const val FADE_IN_BY = 0.045f

/** Position along the arc: travels, then holds. */
private fun travelAt(cycle: Float): Float =
    FastOutSlowInEasing.transform((cycle / ARRIVE_AT).coerceIn(0f, 1f))

/** Fades in as it appears and out at the end, so the restart is not a jump. */
private fun alphaAt(cycle: Float): Float = when {
    cycle < FADE_IN_BY -> cycle / FADE_IN_BY
    cycle > FADE_FROM -> 1f - (cycle - FADE_FROM) / (1f - FADE_FROM)
    else -> 1f
}

// The cover's box, as fractions of the canvas. The phone uses it too, so both
// are positioned against the same geometry rather than guessed apart.
private const val COVER_LEFT = 0.22f
private const val COVER_WIDTH = 0.46f
private const val COVER_TOP = 0.03f
private const val COVER_HEIGHT = 0.60f

/** The passport cover: ICAO layout, no issuer. */
private fun DrawScope.drawPassport(
    w: Float,
    h: Float,
    cover: Color,
    gold: Color,
    measurer: TextMeasurer
) {
    val left = w * COVER_LEFT
    val top = h * COVER_TOP
    val width = w * COVER_WIDTH
    val height = h * COVER_HEIGHT
    val corner = width * 0.045f

    drawRoundRect(
        color = cover,
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(corner, corner)
    )

    // Binding edge.
    drawRoundRect(
        color = Color.Black.copy(alpha = 0.16f),
        topLeft = Offset(left, top),
        size = Size(width * 0.06f, height),
        cornerRadius = CornerRadius(corner, corner)
    )

    val centreX = left + width * 0.53f

    drawCoverText(
        measurer = measurer,
        text = "PASSPORT",
        centreX = centreX,
        centreY = top + height * 0.14f,
        sizePx = width * 0.115f,
        letterSpacingEm = 0.18f,
        colour = gold
    )

    drawGlobe(
        centre = Offset(centreX, top + height * 0.44f),
        radius = width * 0.215f,
        colour = gold,
        stroke = width * 0.015f
    )

    drawCoverText(
        measurer = measurer,
        text = "ePASSPORT",
        centreX = centreX,
        centreY = top + height * 0.745f,
        sizePx = width * 0.092f,
        letterSpacingEm = 0.12f,
        colour = gold
    )

    drawChipSymbol(
        centre = Offset(centreX, top + height * 0.885f),
        width = width * 0.21f,
        colour = gold,
        cover = cover
    )
}

/** Centres a line of cover text on a point. */
private fun DrawScope.drawCoverText(
    measurer: TextMeasurer,
    text: String,
    centreX: Float,
    centreY: Float,
    sizePx: Float,
    letterSpacingEm: Float,
    colour: Color
) {
    // Canvas measures in pixels; text styles want scalable units. toSp() undoes
    // the user's font scale as well as density (non-linear from Android 14):
    // this lettering is part of a drawing sized to the cover, and scaled with
    // the font setting it spilled off the passport.
    val style = TextStyle(
        color = colour,
        fontSize = sizePx.toSp(),
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (sizePx * letterSpacingEm).toSp()
    )
    val laid = measurer.measure(text, style)
    drawText(
        textLayoutResult = laid,
        topLeft = Offset(
            centreX - laid.size.width / 2f,
            centreY - laid.size.height / 2f
        )
    )
}

/** A globe: meridians narrowing toward the centre, and parallels. */
private fun DrawScope.drawGlobe(centre: Offset, radius: Float, colour: Color, stroke: Float) {
    drawCircle(colour, radius = radius, center = centre, style = Stroke(width = stroke))

    listOf(0.93f, 0.50f).forEach { squash ->
        drawOval(
            color = colour,
            topLeft = Offset(centre.x - radius * squash, centre.y - radius),
            size = Size(radius * 2 * squash, radius * 2),
            style = Stroke(width = stroke * 0.85f)
        )
    }
    drawLine(
        colour,
        Offset(centre.x, centre.y - radius),
        Offset(centre.x, centre.y + radius),
        strokeWidth = stroke * 0.85f
    )
    drawLine(
        colour,
        Offset(centre.x - radius, centre.y),
        Offset(centre.x + radius, centre.y),
        strokeWidth = stroke * 0.85f
    )
    listOf(-0.52f, 0.52f).forEach { at ->
        val y = centre.y + radius * at
        val half = radius * 0.84f
        drawLine(colour, Offset(centre.x - half, y), Offset(centre.x + half, y), stroke * 0.7f)
    }
}

/**
 * The ICAO ePassport symbol.
 *
 * A filled rectangle with a gap straight through the middle and a ring around a
 * central disc. Commonly described as "camera-shaped", which is misleading - it
 * has no viewfinder and no lens. Drawn from the mark itself.
 *
 * The gap and the ring are painted in the cover colour rather than left empty,
 * so the symbol works as a solid stamp on the cover.
 */
private fun DrawScope.drawChipSymbol(
    centre: Offset,
    width: Float,
    colour: Color,
    cover: Color
) {
    val height = width / 1.52f

    drawRect(
        color = colour,
        topLeft = Offset(centre.x - width / 2f, centre.y - height / 2f),
        size = Size(width, height)
    )

    // The gap across the middle.
    drawRect(
        color = cover,
        topLeft = Offset(centre.x - width / 2f, centre.y - height * 0.045f),
        size = Size(width, height * 0.09f)
    )

    // Ring of cover colour, then the disc inside it.
    drawCircle(color = cover, radius = height * 0.25f, center = centre)
    drawCircle(color = colour, radius = height * 0.195f, center = centre)
}

/**
 * The phone, arcing from the cover's lower-right corner to its resting place:
 * horizontally centred, overhanging the cover's bottom edge by an eighth.
 *
 * It stays wholly within the frame at every point of the path, so the phone is
 * never clipped mid-instruction.
 */
private fun DrawScope.drawPhone(
    w: Float,
    h: Float,
    fill: Color,
    ink: Color,
    accent: Color,
    progress: Float,
    alpha: Float
) {
    // Roughly 2:1, which is what a handset looks like.
    val width = w * 0.24f
    val height = h * 0.48f

    val coverLeft = w * COVER_LEFT
    val coverWidth = w * COVER_WIDTH
    val coverBottom = h * COVER_TOP + h * COVER_HEIGHT

    val endX = coverLeft + coverWidth / 2f - width / 2f
    val endY = coverBottom - height * 0.875f

    val startX = coverLeft + coverWidth - width * 0.35f
    val startY = coverBottom - height * 0.35f

    val controlX = startX + width * 0.26f
    val controlY = startY + height * 0.16f

    val t = progress.coerceIn(0f, 1f)
    val inv = 1f - t
    val x = inv * inv * startX + 2f * inv * t * controlX + t * t * endX
    val y = inv * inv * startY + 2f * inv * t * controlY + t * t * endY

    val corner = width * 0.14f
    val lifted = 1f - t

    drawRoundRect(
        color = Color.Black.copy(alpha = (0.10f + lifted * 0.10f) * alpha),
        topLeft = Offset(x + w * 0.009f + lifted * w * 0.011f, y + h * 0.011f + lifted * h * 0.010f),
        size = Size(width, height),
        cornerRadius = CornerRadius(corner, corner)
    )

    drawRoundRect(
        color = fill.copy(alpha = alpha),
        topLeft = Offset(x, y),
        size = Size(width, height),
        cornerRadius = CornerRadius(corner, corner)
    )
    drawRoundRect(
        color = ink.copy(alpha = 0.5f * alpha),
        topLeft = Offset(x, y),
        size = Size(width, height),
        cornerRadius = CornerRadius(corner, corner),
        style = Stroke(width = width * 0.020f)
    )

    drawRoundRect(
        color = ink.copy(alpha = 0.3f * alpha),
        topLeft = Offset(x + width * 0.36f, y + height * 0.045f),
        size = Size(width * 0.28f, height * 0.013f),
        cornerRadius = CornerRadius(height * 0.007f, height * 0.007f)
    )

    // Contactless mark on the phone, brightening as it arrives.
    val origin = Offset(x + width * 0.5f, y + height * 0.46f)
    val strength = (0.35f + t * 0.65f) * alpha
    repeat(3) { ring ->
        val r = width * (0.15f + ring * 0.12f)
        drawArc(
            color = accent.copy(alpha = (0.9f - ring * 0.24f) * strength),
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(origin.x - r, origin.y - r),
            size = Size(r * 2, r * 2),
            style = Stroke(width = width * 0.028f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        )
    }
    drawCircle(accent.copy(alpha = strength), radius = width * 0.030f, center = origin)
}
