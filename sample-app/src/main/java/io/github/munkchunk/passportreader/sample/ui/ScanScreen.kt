package io.github.munkchunk.passportreader.sample.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.MediaActionSound
import android.media.SoundPool
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.munkchunk.passportreader.sample.R
import io.github.munkchunk.passportreader.sample.mrz.MrzImageAnalyzer
import io.github.munkchunk.passportreader.sample.mrz.MrzKey
import io.github.munkchunk.passportreader.sample.mrz.MrzParser
import io.github.munkchunk.passportreader.sample.mrz.ScanStatus
import io.github.munkchunk.passportreader.sample.ui.theme.brandTextButtonColors
import kotlinx.coroutines.delay
import java.io.File
import java.util.concurrent.Executors

/**
 * Points the camera at the machine readable zone - the two lines of OCR-B
 * along the bottom of the photo page - and hands back the first reading whose
 * check digits verify.
 *
 * Only the *start* of the bottom line is needed: the first
 * [MrzParser.KEY_FIELD_LENGTH] of its 44 characters carry the document number,
 * the two dates and their check digits, and the top line carries nothing this
 * needs at all. Asking for the whole zone would mean holding the phone further
 * back for characters that are never read.
 *
 * The guide band is deliberately a plain rectangle rather than a passport or
 * face outline: the only part that matters is the MRZ, and a face-shaped guide
 * would have people framing the photograph instead.
 *
 * Acceptance is deliberately not instantaneous. See [CAPTURE_HOLD_MS].
 */
@Composable
fun ScanScreen(
    onMrzFound: (MrzKey) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) {
        PermissionPrompt(
            onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onCancel = onCancel
        )
        return
    }

    CameraScanner(onMrzFound = onMrzFound, onCancel = onCancel)
}

@Composable
private fun CameraScanner(
    onMrzFound: (MrzKey) -> Unit,
    onCancel: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var status by remember { mutableStateOf(ScanStatus.Searching) }

    // Nothing here is required to read an MRZ; it exists so that acceptance is
    // visible. Reported from testing: the scanner completes while the user is
    // still lining the camera up, so the jump to the NFC screen reads as the
    // app wandering off on its own rather than as a result. A shutter click, a
    // flash and a held still frame say "that one, and we have stopped looking"
    // in the vocabulary every camera already uses.
    var pendingKey by remember { mutableStateOf<MrzKey?>(null) }
    var capturedFrame by remember { mutableStateOf<Bitmap?>(null) }
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    val flash = remember { Animatable(0f) }
    val shutter = remember { ShutterClick() }

    val executor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember {
        MrzImageAnalyzer(
            onStatus = { status = it },
            onFound = { key, evidence ->
                // ML Kit's listeners come back on the main thread, so touching
                // Compose state is safe here.
                //
                // The frame shown is the one the reading actually came from,
                // not the preview as it stands now. Measured on an A32:
                // recognition takes 74-101ms per frame and acceptance needs
                // three of them, so the preview is 156-381ms ahead of the
                // evidence. Freezing the preview would show a phone that has
                // moved on, sometimes convincingly enough that a correct read
                // looks like a bad capture. Falling back to the preview if
                // there is no evidence bitmap costs honesty, not the read.
                capturedFrame = evidence ?: previewViewRef?.bitmap
                shutter.play()
                pendingKey = key
            }
        )
    }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(pendingKey) {
        val key = pendingKey ?: return@LaunchedEffect
        flash.snapTo(FLASH_PEAK_ALPHA)
        flash.animateTo(0f, tween(FLASH_FADE_MS))
        delay(CAPTURE_HOLD_MS - FLASH_FADE_MS)
        onMrzFound(key)
    }

    DisposableEffect(Unit) {
        onDispose {
            // Order matters: stop delivering frames before the executor that
            // handles them goes away, and release the camera so the next screen
            // is not competing with a still-bound use case.
            analyzer.close()
            cameraProvider?.unbindAll()
            executor.shutdown()
            shutter.release()
            // The frozen frame is left to the garbage collector rather than
            // recycled: composition may still be drawing it on the way out,
            // and a recycled bitmap in a draw pass is a crash.
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // The analyzer crops in these coordinates, not the camera frame's.
            // Until this arrives it analyses nothing, which costs the first
            // frame or two and avoids reading a band nobody can see.
            .onSizeChanged { analyzer.viewportSize = Size(it.width, it.height) }
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                previewViewRef = previewView
                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    cameraProvider = provider

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val analysis = ImageAnalysis.Builder()
                        // The MRZ is small text; dropping frames is fine but
                        // reading a stale one is not.
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(executor, analyzer) }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            }
        )

        // Over the live preview, under the guide band, so the box the user was
        // asked to line up still frames what was captured.
        capturedFrame?.let { frame ->
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        MrzGuideBand()

        if (flash.value > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White.copy(alpha = flash.value))
            )
        }

        // Directly under the box, not at the foot of the screen. The
        // instruction is about what is inside the box, and a caption two thirds
        // of a screen away from the thing it describes is read late or not at
        // all. Positioned from the same fractions the band is drawn with, so it
        // tracks the box rather than being placed by eye.
        Text(
            text = stringResource(
                if (pendingKey != null) {
                    R.string.scan_captured
                } else {
                    when (status) {
                        ScanStatus.Searching -> R.string.scan_hint_searching
                        ScanStatus.MoveLeft -> R.string.scan_hint_move_left
                        ScanStatus.MoveRight -> R.string.scan_hint_move_right
                        ScanStatus.MoveBack -> R.string.scan_hint_move_back
                        ScanStatus.TextSeenButNotValid -> R.string.scan_hint_unclear
                        ScanStatus.Confirming -> R.string.scan_hint_confirming
                    }
                }
            ),
            color = Color.White,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(
                    y = maxHeight *
                        (MrzImageAnalyzer.BAND_TOP_FRACTION +
                            MrzImageAnalyzer.BAND_HEIGHT_FRACTION) + HINT_GAP
                )
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        )

        // The fallback action stays at the foot of the screen: it is not about
        // the box, and it should not compete with the instruction.
        if (pendingKey == null) {
            TextButton(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            ) {
                Text(stringResource(R.string.scan_enter_by_hand), color = Color.White)
            }
        }
    }
}

/** Dims everything outside the strip where the MRZ should sit. */
@Composable
private fun MrzGuideBand() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        // Same fractions the analyzer crops to, so the box shows exactly what
        // is actually being read.
        val bandHeight = size.height * MrzImageAnalyzer.BAND_HEIGHT_FRACTION
        val bandTop = size.height * MrzImageAnalyzer.BAND_TOP_FRACTION
        val inset = size.width * MrzImageAnalyzer.BAND_SIDE_INSET_FRACTION

        val scrim = Color.Black.copy(alpha = SCAN_SCRIM_ALPHA)

        // Above and below the band.
        drawRect(color = scrim, size = size.copy(height = bandTop))
        drawRect(
            color = scrim,
            topLeft = androidx.compose.ui.geometry.Offset(0f, bandTop + bandHeight),
            size = size.copy(height = size.height - bandTop - bandHeight)
        )

        // And the margins either side of it. These were left clear, which read
        // as "this counts too" - and it does not: the analyzer crops to the
        // inset, so anything in those strips is never looked at.
        drawRect(
            color = scrim,
            topLeft = androidx.compose.ui.geometry.Offset(0f, bandTop),
            size = androidx.compose.ui.geometry.Size(inset, bandHeight)
        )
        drawRect(
            color = scrim,
            topLeft = androidx.compose.ui.geometry.Offset(size.width - inset, bandTop),
            size = androidx.compose.ui.geometry.Size(inset, bandHeight)
        )

        drawRect(
            color = Color.White,
            topLeft = androidx.compose.ui.geometry.Offset(inset, bandTop),
            size = androidx.compose.ui.geometry.Size(size.width - inset * 2, bandHeight),
            style = Stroke(width = 3f)
        )
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit, onCancel: () -> Unit) {
    // Its own page colour: the scanner around it is framed in black.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(stringResource(R.string.permission_camera_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            stringResource(R.string.permission_camera_body),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = onRequest) { Text(stringResource(R.string.permission_camera_allow)) }
        TextButton(colors = brandTextButtonColors(), onClick = onCancel) {
            Text(stringResource(R.string.scan_enter_by_hand))
        }
    }
}

/**
 * The capture click.
 *
 * [MediaActionSound] is the obvious choice and was the first one, but it plays
 * on the enforced system stream at whatever level that stream happens to be
 * set to, and exposes no volume control at all - with system volume up it
 * startles rather than confirms. SoundPool over the device's own
 * `camera_click.ogg` is the same familiar sound at a level this app chooses.
 *
 * That path is a long-standing convention rather than API, so where the file
 * is missing [MediaActionSound] is still better than silence. Nothing is
 * bundled: an audio asset would be one more thing needing provenance in a
 * repository published under a licence.
 */
private class ShutterClick {

    private val pool: SoundPool?
    private var soundId = 0
    private var loaded = false
    private var fallback: MediaActionSound? = null

    init {
        val click = File(SYSTEM_CAMERA_CLICK)
        pool = if (click.canRead()) {
            SoundPool.Builder()
                .setMaxStreams(1)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
                .apply {
                    setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
                    soundId = load(click.absolutePath, 1)
                }
        } else {
            null
        }

        if (pool == null) {
            fallback = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
        }
    }

    /**
     * Loading is asynchronous, so in principle a scan could beat it. In
     * practice it cannot: the scanner needs several agreeing frames, which
     * takes far longer than decoding an 8KB ogg. If it ever did, the flash and
     * the frozen frame still report the capture.
     */
    fun play() {
        val p = pool
        if (p != null && loaded) {
            p.play(soundId, CLICK_VOLUME, CLICK_VOLUME, 1, 0, 1f)
        } else {
            fallback?.play(MediaActionSound.SHUTTER_CLICK)
        }
    }

    fun release() {
        pool?.release()
        fallback?.release()
        fallback = null
    }
}

private const val SYSTEM_CAMERA_CLICK = "/system/media/audio/ui/camera_click.ogg"

/** Present rather than startling. The system stream is often turned up. */
private const val CLICK_VOLUME = 0.35f

/**
 * How long the accepted frame is held on screen before the NFC step takes
 * over. Long enough to register as a deliberate capture, short enough not to
 * feel like the app has stalled.
 */
private const val CAPTURE_HOLD_MS = 600L

/** Fade of the capture flash, part of [CAPTURE_HOLD_MS] rather than on top. */
private const val FLASH_FADE_MS = 220

private const val FLASH_PEAK_ALPHA = 0.8f

/** Breathing room between the guide box and the instruction under it. */
private val HINT_GAP = 14.dp

/**
 * How dark the scrim outside the guide band is. The hint and the "enter by
 * hand" button are white text on it, so this sets their contrast against a
 * white page under the camera; ContrastTest checks that worst case.
 */
internal const val SCAN_SCRIM_ALPHA = 0.55f
