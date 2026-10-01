package io.github.munkchunk.passportreader.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.annotation.StringRes
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import io.github.munkchunk.passportreader.model.CheckResult
import io.github.munkchunk.passportreader.model.CheckVerdict
import io.github.munkchunk.passportreader.sample.R
import io.github.munkchunk.passportreader.model.VerificationReport
import io.github.munkchunk.passportreader.sample.ui.theme.SectionLabel
import io.github.munkchunk.passportreader.sample.ui.theme.verdicts

/**
 * How a verdict is spoken. Screen readers get this, so the mark is never the
 * only way to know the outcome.
 */
@StringRes
fun CheckVerdict.spokenRes(): Int = when (this) {
    CheckVerdict.SUCCEEDED -> R.string.verdict_passed
    CheckVerdict.FAILED -> R.string.verdict_failed
    CheckVerdict.NOT_PRESENT -> R.string.verdict_not_present
    CheckVerdict.NOT_CHECKED -> R.string.verdict_not_checked
    CheckVerdict.UNKNOWN -> R.string.verdict_unknown
}

/**
 * A verdict as a small mark. Shape carries the meaning and colour reinforces
 * it - never colour alone, or a red cross and a green tick are the same mark to
 * a colourblind reader.
 */
@Composable
fun VerdictMark(
    verdict: CheckVerdict,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 20.dp
) {
    val v = MaterialTheme.verdicts
    // Resolved out here: semantics {} is not a composable scope.
    val spoken = stringResource(verdict.spokenRes())
    val (ink, ground) = when (verdict) {
        CheckVerdict.SUCCEEDED -> v.pass to v.passContainer
        CheckVerdict.FAILED -> v.fail to v.failContainer
        CheckVerdict.UNKNOWN -> v.unknown to v.unknownContainer
        CheckVerdict.NOT_PRESENT, CheckVerdict.NOT_CHECKED -> v.absent to v.absentContainer
    }

    Box(
        modifier = modifier
            .size(size)
            .background(ground, CircleShape)
            .semantics { contentDescription = spoken },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size * 0.58f)) {
            when (verdict) {
                CheckVerdict.SUCCEEDED -> drawTick(ink)
                CheckVerdict.FAILED -> drawCross(ink)
                CheckVerdict.NOT_PRESENT -> drawDash(ink)
                CheckVerdict.NOT_CHECKED -> drawStruckCircle(ink)
                CheckVerdict.UNKNOWN -> drawQuery(ink)
            }
        }
    }
}

private fun DrawScope.stroke(width: Float = size.minDimension * 0.16f) =
    Stroke(width = width, cap = StrokeCap.Round)

private fun DrawScope.drawTick(color: Color) {
    val w = size.width
    val h = size.height
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.14f, h * 0.52f)
        lineTo(w * 0.40f, h * 0.78f)
        lineTo(w * 0.87f, h * 0.20f)
    }
    drawPath(path, color, style = stroke())
}

private fun DrawScope.drawCross(color: Color) {
    val i = size.minDimension * 0.22f
    drawLine(color, Offset(i, i), Offset(size.width - i, size.height - i), stroke().width, StrokeCap.Round)
    drawLine(color, Offset(size.width - i, i), Offset(i, size.height - i), stroke().width, StrokeCap.Round)
}

private fun DrawScope.drawDash(color: Color) {
    val y = size.height / 2f
    drawLine(color, Offset(size.width * 0.16f, y), Offset(size.width * 0.84f, y), stroke().width, StrokeCap.Round)
}

private fun DrawScope.drawStruckCircle(color: Color) {
    val r = size.minDimension * 0.40f
    drawCircle(color, radius = r, style = stroke(size.minDimension * 0.13f))
    drawLine(
        color,
        Offset(size.width * 0.26f, size.height * 0.74f),
        Offset(size.width * 0.74f, size.height * 0.26f),
        stroke(size.minDimension * 0.13f).width,
        StrokeCap.Round
    )
}

private fun DrawScope.drawQuery(color: Color) {
    val w = size.width
    val h = size.height
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.30f, h * 0.34f)
        cubicTo(w * 0.32f, h * 0.06f, w * 0.80f, h * 0.12f, w * 0.60f, h * 0.44f)
        cubicTo(w * 0.52f, h * 0.56f, w * 0.50f, h * 0.58f, w * 0.50f, h * 0.68f)
    }
    drawPath(path, color, style = stroke(size.minDimension * 0.15f))
    drawCircle(color, radius = size.minDimension * 0.075f, center = Offset(w * 0.50f, h * 0.88f))
}

/** One row of the verification list. */
@Composable
fun CheckRow(
    label: String,
    result: CheckResult?,
    detail: String? = null,
    modifier: Modifier = Modifier
) {
    val verdict = result?.verdict ?: CheckVerdict.NOT_CHECKED
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top
    ) {
        VerdictMark(verdict)

        // The acronym belongs with the label - it names the same thing - so it
        // sits inline after a bullet. Only the reason, which is a sentence and
        // can run long, goes underneath.
        //
        // The reason is used verbatim: lowercasing its first letter to make it
        // read as a continuation would turn "AA is not supported" into "aA".
        val reason = result?.reason?.takeIf { verdict != CheckVerdict.SUCCEEDED }

        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(
                text = buildAnnotatedString {
                    withStyle(
                        SpanStyle(
                            color = if (verdict == CheckVerdict.FAILED) {
                                MaterialTheme.verdicts.fail
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    ) { append(label) }
                    if (!detail.isNullOrBlank()) {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                            append("  ·  ")
                            append(detail)
                        }
                    }
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (!reason.isNullOrBlank()) {
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * The one-line summary beside the section heading.
 *
 * Red if anything failed; otherwise amber if anything is unknown; otherwise
 * green. Absent and not-checked never colour it - a document that simply lacks
 * a feature has done nothing wrong. See docs/sample-app.md.
 */
@Composable
fun VerificationTally(report: VerificationReport, modifier: Modifier = Modifier) {
    val all = listOfNotNull(
        report.basicAccessControl, report.pace, report.chipAuthentication, report.activeAuthentication,
        report.documentSignature, report.certificateChain, report.dataGroupHashes, report.terminalAuthentication
    )
    val counts = all.groupingBy { it.verdict }.eachCount()
    val passed = counts[CheckVerdict.SUCCEEDED] ?: 0
    val failed = counts[CheckVerdict.FAILED] ?: 0
    val unknown = counts[CheckVerdict.UNKNOWN] ?: 0
    val absent = (counts[CheckVerdict.NOT_PRESENT] ?: 0) + (counts[CheckVerdict.NOT_CHECKED] ?: 0)

    val v = MaterialTheme.verdicts
    val colour = when {
        failed > 0 -> v.fail
        unknown > 0 -> v.unknown
        else -> v.pass
    }

    val parts = buildList {
        if (failed > 0) add(pluralStringResource(R.plurals.tally_failed, failed, failed))
        if (passed > 0) add(pluralStringResource(R.plurals.tally_passed, passed, passed))
        if (unknown > 0) add(pluralStringResource(R.plurals.tally_unknown, unknown, unknown))
        if (absent > 0) add(pluralStringResource(R.plurals.tally_not_available, absent, absent))
    }

    Text(
        text = parts.joinToString(" · "),
        style = SectionLabel.copy(letterSpacing = 0.2.sp, fontSize = 11.5.sp),
        color = colour,
        modifier = modifier
    )
}
