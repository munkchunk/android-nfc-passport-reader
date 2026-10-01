package io.github.munkchunk.passportreader.sample.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.annotation.StringRes
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.munkchunk.passportreader.reader.NfcReadState
import io.github.munkchunk.passportreader.sample.R
import io.github.munkchunk.passportreader.sample.STILL_WAITING_MS
import io.github.munkchunk.passportreader.reader.ReadStage
import io.github.munkchunk.passportreader.sample.ui.theme.brand
import io.github.munkchunk.passportreader.sample.ui.theme.brandTextButtonColors
import io.github.munkchunk.passportreader.sample.ui.theme.SectionLabel
import io.github.munkchunk.passportreader.sample.ui.theme.verdicts

@Composable
fun NfcReadScreen(
    state: NfcReadState,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state) {
                is NfcReadState.Error -> ErrorContent(state, onRetry, onBack)
                else -> ProgressContent(state)
            }
        }

        // Below the scrolling area rather than pinned over it. Pinned, the
        // stage list scrolls underneath and "Checking signatures" can sit
        // behind this button; reserving padding only helps once the
        // content is scrolled to the very bottom, which it need not be.
        //
        // Still at the bottom edge, which is the point: mid-screen is where a
        // hand crosses while laying the phone onto a passport, and one tap
        // there silently abandons the wait.
        if (state !is NfcReadState.Error) {
            TextButton(
                colors = brandTextButtonColors(),
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 4.dp, bottom = 24.dp)
            ) {
                Text(stringResource(R.string.read_cancel))
            }
        }
    }
}

@Composable
private fun ProgressContent(state: NfcReadState) {
    val stage = (state as? NfcReadState.Reading)?.stage
    val waiting = state is NfcReadState.WaitingForTag ||
        state is NfcReadState.Idle ||
        state is NfcReadState.Initializing

    // The wait has no limit, so after a while say so and suggest what to try,
    // rather than leave the same instruction up indefinitely.
    var stillWaiting by remember { mutableStateOf(false) }
    LaunchedEffect(waiting) {
        stillWaiting = false
        if (waiting) {
            delay(STILL_WAITING_MS)
            stillWaiting = true
        }
    }

    NfcPlacementGraphic(
        active = waiting,
        modifier = Modifier.padding(top = 8.dp)
    )

    Text(
        // The phone goes on the passport, not the other way about: a passport
        // lies flat on a surface and the phone is what moves. The graphic shows
        // the same thing.
        text = stringResource(
            when {
                waiting -> R.string.read_heading_waiting
                state is NfcReadState.Success -> R.string.read_heading_done
                else -> R.string.read_heading_reading
            }
        ),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground
    )

    if (waiting && stillWaiting) {
        // Its own line, set apart, rather than a changed sentence in the same
        // paragraph: a change of layout draws the eye where a change of
        // wording alone goes unnoticed.
        Text(
            text = stringResource(R.string.read_still_waiting_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
    }

    Text(
        // "Move it around" is the wrong instruction: the field needs a moment of
        // stillness to find the chip, and sweeping the phone can stop it being
        // detected at all. Lifting and placing again somewhere else is what
        // actually works, so say that instead.
        text = stringResource(
            when {
                waiting && stillWaiting -> R.string.read_body_still_waiting
                waiting -> R.string.read_body_waiting
                else -> R.string.read_body_reading
            }
        ),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (stage != null) StageList(stage)
}

/**
 * The stages of a read, each marked as it is reached.
 *
 * Reported by the library as they happen rather than estimated. A read takes
 * about ten seconds and can fail, and on an operation like that a progress bar
 * invented from a timer is worse than none - it keeps moving when things have
 * actually stopped.
 */
@Composable
private fun StageList(current: ReadStage) {
    val stages = ReadStage.entries
    val index = stages.indexOf(current)
    val progress by animateFloatAsState(
        targetValue = (index + 1).toFloat() / stages.size,
        label = "stage-progress"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            stringResource(R.string.read_progress),
            style = SectionLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            color = MaterialTheme.verdicts.pass,
            trackColor = MaterialTheme.colorScheme.outlineVariant
        )

        stages.forEachIndexed { position, stage ->
            val done = position < index
            val active = position == index

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StageDot(done = done, active = active)
                Text(
                    text = stringResource(stage.describeRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    // Done and upcoming share the muted colour: fading upcoming
                    // stages further put them under the text minimum. The dot
                    // tells them apart, by shape as well as colour.
                    color = if (active) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun StageDot(done: Boolean, active: Boolean) {
    // Upcoming is a hollow ring, not a paler dot, so a stage's state never
    // rests on colour alone.
    val base = Modifier
        .size(if (active) 12.dp else 8.dp)
        .clip(CircleShape)
    val modifier = when {
        done -> base.background(MaterialTheme.verdicts.pass)
        active -> base.background(MaterialTheme.brand.text)
        else -> base.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
    }
    Column(modifier = modifier) {}
}

@StringRes
private fun ReadStage.describeRes(): Int = when (this) {
    ReadStage.Connecting -> R.string.stage_connecting
    ReadStage.Authenticating -> R.string.stage_authenticating
    ReadStage.ReadingData -> R.string.stage_reading_data
    ReadStage.ReadingPhoto -> R.string.stage_reading_photo
    ReadStage.Verifying -> R.string.stage_verifying
}

@Composable
private fun ErrorContent(
    state: NfcReadState.Error,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Text(
        // The library names the failure; a generic heading here hid that.
        stringResource(state.titleRes),
        style = MaterialTheme.typography.headlineSmall,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 24.dp)
    )
    Text(
        stringResource(state.messageRes, *state.messageArgs.toTypedArray()),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    state.technicalDetails?.let { details ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                stringResource(R.string.error_technical_details),
                style = SectionLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }

    if (state.canRetry) {
        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.error_try_again), style = MaterialTheme.typography.labelLarge)
        }
    }

    TextButton(colors = brandTextButtonColors(), onClick = onBack) {
        Text(stringResource(R.string.error_change_details))
    }
}
