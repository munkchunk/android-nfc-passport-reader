package io.github.munkchunk.passportreader.sample.mrz

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs on-device text recognition over camera frames and reports an MRZ only
 * once it has been read identically more than once.
 *
 * Two things make a single check-digit-valid frame insufficient on its own.
 * The regex has to be permissive to survive OCR, and three check digits only
 * narrow a false match to roughly one in a thousand - which sounds safe until
 * you remember this sees tens of frames a second, so over a minute of hunting
 * a spurious match is likely rather than unlikely. Requiring
 * [REQUIRED_AGREEING_FRAMES] identical readings makes that vanishingly rare
 * while costing the user a fraction of a second.
 *
 * Frames arrive faster than they can be recognised, so one is processed at a
 * time and the rest are dropped.
 *
 * [onFound] fires at most once, and hands back the frame the reading came
 * from so the UI can show the evidence rather than a later approximation of
 * it. Ownership of that bitmap passes to the caller.
 */
class MrzImageAnalyzer(
    private val onStatus: (ScanStatus) -> Unit,
    private val onFound: (MrzKey, Bitmap?) -> Unit
) : ImageAnalysis.Analyzer {

    private val recogniser = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val busy = AtomicBoolean(false)
    private val finished = AtomicBoolean(false)

    private var lastCandidate: MrzKey? = null
    private var agreementCount = 0

    /**
     * The upright frame currently being recognised, kept so that the one that
     * turns out to be the accepted reading can be handed to the UI.
     *
     * Only ever one at a time - [busy] guarantees it - and recycled as soon as
     * recognition finishes unless it was the accepted frame.
     */
    private var inFlightFrame: Bitmap? = null

    private var framesSinceCandidate = 0
    private var reportedStatus: ScanStatus? = null
    private var pendingStatus: ScanStatus? = null
    private var pendingRepeats = 0
    private var reportedAt = 0L

    /**
     * Size of the preview the user is actually looking at, set from the UI on
     * layout and read on the analysis thread.
     *
     * Without it the crop can only be expressed as a fraction of the camera
     * frame, which is not the same region as a fraction of the preview: see
     * [toScanBandBitmap]. Null until the first layout, which is handled by
     * analysing nothing rather than by analysing the wrong thing.
     */
    @Volatile
    var viewportSize: Size? = null

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        if (finished.get() || !busy.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val cropped = try {
            imageProxy.toScanBandBitmap()
        } catch (e: Exception) {
            Timber.w(e, "Could not crop frame for analysis")
            null
        }

        if (cropped == null) {
            inFlightFrame?.recycle()
            inFlightFrame = null
            busy.set(false)
            imageProxy.close()
            return
        }

        val cropWidth = cropped.width

        recogniser.process(InputImage.fromBitmap(cropped, 0))
            .addOnSuccessListener { result ->
                if (!finished.get()) handleResult(result, cropWidth)
            }
            .addOnFailureListener { error ->
                Timber.w(error, "Text recognition failed on a frame")
            }
            .addOnCompleteListener {
                cropped.recycle()
                // Null here means it was handed to onFound, which now owns it.
                inFlightFrame?.recycle()
                inFlightFrame = null
                busy.set(false)
                imageProxy.close()
            }
    }

    private fun handleResult(result: Text, cropWidth: Int) {
        val candidate = MrzParser.parse(result.text)

        if (candidate == null) {
            // An unreadable frame - blur, glare, a bad angle - says nothing
            // about the readings either side of it, so it must not undo them.
            // Roughly one frame in three fails this way even while the user
            // holds steady, and resetting on each one would turn "three
            // agreeing readings" into "three consecutive readings", which takes
            // several seconds to satisfy. Only a *different* valid reading is evidence
            // of disagreement.
            framesSinceCandidate++

            // One unreadable frame says nothing, so a recent valid reading
            // keeps "checking" on screen for a moment. A *run* of them is
            // different: the MRZ has left the box, and continuing to claim we
            // are checking hides the guidance that would bring it back. Without
            // this the hints would be unreachable in practice - a single valid
            // frame early in the hunt would pin the status to Confirming for
            // the rest of the scan.
            if (agreementCount > 0 && framesSinceCandidate <= CONFIRMING_GRACE_FRAMES) {
                announce(ScanStatus.Confirming)
            } else {
                report(guidanceFor(result, cropWidth))
            }
            return
        }

        framesSinceCandidate = 0

        if (candidate == lastCandidate) {
            agreementCount++
        } else {
            lastCandidate = candidate
            agreementCount = 1
        }

        if (agreementCount < REQUIRED_AGREEING_FRAMES) {
            Timber.d("MRZ candidate seen $agreementCount time(s); confirming")
            announce(ScanStatus.Confirming)
            return
        }

        if (finished.compareAndSet(false, true)) {
            Timber.d("MRZ accepted after $agreementCount agreeing frames")
            val evidence = inFlightFrame
            inFlightFrame = null
            onFound(candidate, evidence)
        }
    }

    /**
     * Works out what to tell the user from where the MRZ actually fell in the
     * frame.
     *
     * Only the *start* of line 2 is needed - [MrzParser.KEY_FIELD_LENGTH] of
     * its 44 characters - so a row that runs out of the box on the right is
     * only a problem while fewer than that many characters have been read.
     * Which edge the row is clipped against is what makes the advice
     * directional: clipped at the left means its beginning is off-screen,
     * clipped at both means it does not fit at this distance.
     *
     * Deliberately conservative. Wrong directional advice is worse than none -
     * the user follows it, the scan gets further away, and the instruction has
     * actively cost them something - so anything ambiguous falls back to the
     * generic "hold steady" rather than guessing a direction.
     */
    private fun guidanceFor(result: Text, cropWidth: Int): ScanStatus {
        if (result.text.isBlank()) return ScanStatus.Searching
        if (cropWidth <= 0) return ScanStatus.TextSeenButNotValid

        val rows = result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val normalised = line.text.uppercase().replace(NON_MRZ, "")
                if (normalised.length >= MIN_MRZ_ISH_CHARS) normalised to box else null
            }

        // Text in shot, but nothing that looks like a row of MRZ.
        val (row, box) = rows.maxByOrNull { it.first.length }
            ?: return ScanStatus.TextSeenButNotValid

        // Enough characters are in the box already, so the problem is legibility
        // rather than framing and no amount of moving sideways will fix it.
        if (row.length >= MrzParser.KEY_FIELD_LENGTH) return ScanStatus.TextSeenButNotValid

        val clippedLeft = box.left <= cropWidth * EDGE_FRACTION
        val clippedRight = box.right >= cropWidth * (1f - EDGE_FRACTION)

        return when {
            clippedLeft && clippedRight -> ScanStatus.MoveBack
            clippedLeft -> ScanStatus.MoveLeft
            clippedRight -> ScanStatus.MoveRight
            // The whole row sits inside the box and still reads short: it is
            // too small or too blurred, not mispositioned.
            else -> ScanStatus.TextSeenButNotValid
        }
    }

    /**
     * Shows a status once it has survived [STATUS_CONFIRM_FRAMES] frames *and*
     * the one on screen has had [MIN_HINT_DWELL_MS] to be read.
     *
     * Two frames alone are not enough. Measured over a test session with only
     * that rule, 50% of guidance changes were direct reversals - A, then B,
     * then straight back to A - and 48% landed within a second of the change
     * before. The geometry genuinely alternates when the row sits near an edge
     * threshold, so confirming across frames confirms the flicker rather than
     * removing it. An instruction the user cannot finish reading before it
     * contradicts itself is worse than no instruction.
     *
     * The dwell only delays replacing one instruction with another. Success
     * goes through [announce] and is never held back.
     */
    private fun report(status: ScanStatus) {
        if (status == reportedStatus) {
            pendingStatus = null
            pendingRepeats = 0
            return
        }
        if (status == pendingStatus) {
            pendingRepeats++
        } else {
            pendingStatus = status
            pendingRepeats = 1
        }
        if (pendingRepeats < STATUS_CONFIRM_FRAMES) return
        if (SystemClock.elapsedRealtime() - reportedAt < MIN_HINT_DWELL_MS) return
        announce(status)
    }

    /** Reports at once, for a status that is already evidence-backed. */
    private fun announce(status: ScanStatus) {
        pendingStatus = null
        pendingRepeats = 0
        if (status == reportedStatus) return
        // Logged because the guidance is otherwise unverifiable after the fact:
        // the screen has moved on by the time anyone reads the log, and whether
        // a hint pointed the right way is exactly the thing worth checking.
        Timber.d("Scan guidance: $reportedStatus -> $status")
        reportedStatus = status
        reportedAt = SystemClock.elapsedRealtime()
        onStatus(status)
    }

    /**
     * Crops the frame to the band the on-screen guide marks out, so only what
     * the user is being asked to line up is actually read.
     *
     * The subtlety is that the guide is drawn in *preview* coordinates and this
     * runs in *camera frame* coordinates, and `FILL_CENTER` does not map one to
     * the other by a plain scale. Measured on a Galaxy A32: a 480x640 upright
     * frame filling a 720x1600 preview scales by height (x2.5) and crops 240px
     * from each side, so the preview shows only the middle 60% of the frame's
     * width. Cropping the same fractions of the frame would therefore read a
     * band 1.8x wider than the box drawn on screen, and accept an MRZ partly
     * off the side of the preview entirely - invisible to the person holding
     * the phone, who would have no way to know what had been read.
     *
     * So the band is computed in preview coordinates and mapped back through
     * the same transform `FILL_CENTER` applies. Sharing [BAND_TOP_FRACTION] and
     * friends with the guide is necessary but not sufficient.
     *
     * Besides matching what the UI promises, this keeps text elsewhere in shot
     * - a keyboard, a nearby document - from ever becoming a candidate, and
     * gives the recogniser far less to chew through per frame.
     */
    private fun ImageProxy.toScanBandBitmap(): Bitmap? {
        val viewport = viewportSize ?: return null
        if (viewport.width <= 0 || viewport.height <= 0) return null

        val full = toBitmap()

        val rotation = imageInfo.rotationDegrees
        val upright = if (rotation == 0) {
            full
        } else {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            Bitmap.createBitmap(full, 0, 0, full.width, full.height, matrix, true)
                .also { if (it != full) full.recycle() }
        }

        // What FILL_CENTER does: scale by whichever axis needs the most to
        // cover the view, then centre, so the overflow on the other axis falls
        // off both edges equally.
        val scale = maxOf(
            viewport.width.toFloat() / upright.width,
            viewport.height.toFloat() / upright.height
        )
        val visibleWidth = viewport.width / scale
        val visibleHeight = viewport.height / scale
        val visibleLeft = (upright.width - visibleWidth) / 2f
        val visibleTop = (upright.height - visibleHeight) / 2f

        // The guide's own geometry, in the coordinates it is drawn in.
        val left = visibleLeft + visibleWidth * BAND_SIDE_INSET_FRACTION
        val width = visibleWidth * (1f - 2f * BAND_SIDE_INSET_FRACTION)
        val top = visibleTop + visibleHeight * BAND_TOP_FRACTION
        val height = visibleHeight * BAND_HEIGHT_FRACTION

        val cropLeft = left.toInt().coerceIn(0, upright.width - 1)
        val cropTop = top.toInt().coerceIn(0, upright.height - 1)
        val cropWidth = width.toInt().coerceAtMost(upright.width - cropLeft)
        val cropHeight = height.toInt().coerceAtMost(upright.height - cropTop)

        // Degenerate band: hand the whole frame to the recogniser and keep no
        // evidence, rather than leaving the returned bitmap and inFlightFrame
        // as the same object for the completion listener to recycle.
        if (cropWidth <= 0 || cropHeight <= 0) return upright

        // Held rather than recycled: if this frame turns out to be the accepted
        // reading, it is the only honest thing to freeze on screen. Released in
        // the completion listener otherwise.
        inFlightFrame = upright

        return Bitmap.createBitmap(upright, cropLeft, cropTop, cropWidth, cropHeight)
    }

    fun close() {
        finished.set(true)
        recogniser.close()
    }

    companion object {
        /** Identical readings required before an MRZ is accepted. */
        const val REQUIRED_AGREEING_FRAMES = 3

        // Must match the guide drawn in ScanScreen. These are fractions of the
        // preview, not of the camera frame; toScanBandBitmap maps between them.
        const val BAND_TOP_FRACTION = 0.46f
        const val BAND_HEIGHT_FRACTION = 0.18f
        const val BAND_SIDE_INSET_FRACTION = 0.04f

        private val NON_MRZ = Regex("[^A-Z0-9<]")

        /** Shorter than this is not a row of MRZ, it is something else in shot. */
        private const val MIN_MRZ_ISH_CHARS = 12

        /** How close to an edge counts as the row being cut off by it. */
        private const val EDGE_FRACTION = 0.06f

        /** Frames a new instruction must survive before it is shown. */
        private const val STATUS_CONFIRM_FRAMES = 2

        /**
         * Unreadable frames a recent valid reading survives before the screen
         * goes back to telling the user how to frame it.
         */
        private const val CONFIRMING_GRACE_FRAMES = 3

        /**
         * How long an instruction stays put before a different one may replace
         * it.
         *
         * Long enough to read nine words and start acting on them, which is
         * also comfortably longer than the sub-second churn measured on device.
         * A hint that is briefly stale because the user has already complied
         * costs nothing; one that reverses itself mid-sentence costs trust.
         */
        private const val MIN_HINT_DWELL_MS = 1500L
    }
}

/**
 * What to tell the user while scanning.
 *
 * The wording lives in the UI; this only says what was observed.
 */
enum class ScanStatus {
    /** Nothing legible in the band yet. */
    Searching,

    /**
     * Text is being read but nothing has passed its check digits, and the
     * framing does not explain why. Usually too small, at an angle, or not
     * sharp enough.
     */
    TextSeenButNotValid,

    /** A row of MRZ runs off the left of the box, taking its start with it. */
    MoveLeft,

    /** A row of MRZ runs off the right before enough of it has been read. */
    MoveRight,

    /** A row of MRZ overflows both edges: it does not fit at this distance. */
    MoveBack,

    /** A valid MRZ has been read and is being confirmed across frames. */
    Confirming
}
