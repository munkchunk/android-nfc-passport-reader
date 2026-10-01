package io.github.munkchunk.passportreader.reader

import android.app.Activity
import android.content.Context
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import io.github.munkchunk.passportreader.NfcPassportReader
import io.github.munkchunk.passportreader.data.Passport
import io.github.munkchunk.passportreader.model.error.NfcErrorReporter
import io.github.munkchunk.passportreader.timing.NfcReadTimingConfig
import io.github.munkchunk.passportreader.model.error.PassportReadException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import timber.log.Timber

/** How many times a tag rediscovered while settling restarts the settle delay. */
private const val MAX_RESETTLES = 2

/**
 * Drives a full passport read: owns NFC ReaderMode, waits for a tag, applies an
 * adaptive settle delay, then delegates the chip read to [NfcPassportReader].
 *
 * Handles the awkward real-world cases:
 * 1. Phone already on the passport when the screen opens
 * 2. Phone placed during the waiting period
 * 3. ReaderMode stays enabled until an explicit stop, so retries need no button press
 * 4. Retry resets state cleanly
 *
 * Create one per screen or per application, and pass the [Activity] via
 * [attachActivity] / [detachActivity] from the host's lifecycle callbacks.
 *
 * @param context application context
 * @param timingConfig RF timing tuning; defaults are tuned for PACE passports
 * @param errorReporter optional hook for crash/analytics reporting
 */
class NfcPassportReaderManager(
    private val context: Context,
    private val timingConfig: NfcReadTimingConfig = NfcReadTimingConfig(),
    errorReporter: NfcErrorReporter? = null
) {
    private val passportReader: NfcPassportReader = NfcPassportReader(context, errorReporter = errorReporter, timingConfig = timingConfig)
    private val timing = NfcAdaptiveTiming(timingConfig)
    private val readMutex = Mutex()

    // Per-instance so two managers never steal each other's tags.
    private val tagBroadcaster = NfcTagBroadcaster()
    
    // State tracking
    @Volatile private var currentActivity: Activity? = null
    @Volatile private var isReaderModeActive = false
    @Volatile private var readerEnabledAtMs = 0L
    
    // Expose current read state as a flow for UI observation
    private val _readState = MutableStateFlow<NfcReadState>(NfcReadState.Idle)
    val readState: StateFlow<NfcReadState> = _readState.asStateFlow()
    
    // Track if we're actively in a read attempt
    @Volatile private var isReadingInProgress = false
    
    // Track if this is a retry (to avoid disrupting stable connections)
    @Volatile private var hadPreviousAttempt = false
    
    // The current state, read and written through readState.
    private var currentReadState: NfcReadState
        get() = _readState.value
        set(value) {
            _readState.value = value
        }

    fun isNfcAvailable(): Boolean = passportReader.isNfcAvailable()
    fun isNfcEnabled(): Boolean = passportReader.isNfcEnabled()
    fun isScanningNow(): Boolean = isReaderModeActive
    fun getCurrentState(): NfcReadState = currentReadState

    /**
     * Handle NFC tag detected: hands the tag to the waiting read, if one is
     * waiting.
     */
    fun onNfcTagDetected(tag: Tag) {
        // Only accept tags if we're actively reading
        if (!isReadingInProgress) {
            Timber.d("🚫 Tag suppressed: not currently reading")
            return
        }

        // Accept tag
        Timber.d("✅ Tag accepted: techs=${tag.techList.contentToString()}")
        tagBroadcaster.broadcastTag(tag)
    }
    
    fun attachActivity(activity: Activity) {
        Timber.d("Attaching activity: ${activity.javaClass.simpleName}")
        currentActivity = activity
    }
    
    fun detachActivity() {
        Timber.d("Detaching activity")
        currentActivity?.let { activity ->
            if (isReaderModeActive) {
                disableReaderModeInternal(activity)
            }
        }
        currentActivity = null
        isReadingInProgress = false
        hadPreviousAttempt = false  // Reset for next time
        currentReadState = NfcReadState.Idle
    }
    
    /**
     * Main entry point: read passport using MRZ data.
     *
     * ReaderMode is enabled before the wait for a tag, a settle delay follows
     * detection, and ReaderMode stays enabled after an error until
     * [stopReading], so a retry needs no button press.
     */
    suspend fun readPassport(
        passportNumber: String,
        dateOfBirth: String,
        expiryDate: String
    ): Passport = withContext(Dispatchers.IO) {
        readMutex.withLock {
            try {
                // === PHASE 1: INITIALIZATION ===
                currentReadState = NfcReadState.Initializing
                Timber.d("🔵 NFC read starting: Initializing")
                
                // ALWAYS disable/re-enable ReaderMode for clean PACE connection
                // PACE is very sensitive and needs a fresh, stable RF field
                withContext(Dispatchers.Main) {
                    currentActivity?.let { activity ->
                        if (isReaderModeActive) {
                            Timber.d("Resetting ReaderMode for clean PACE-compatible RF field")
                            disableReaderModeInternal(activity)
                            // CRITICAL: Longer delay when tag might be present
                            // Ensures Android's NFC stack fully resets
                            delay(timingConfig.presenceCheckDelayMs.toLong())
                        } else {
                            Timber.d("Initializing ReaderMode for first time")
                            delay(timingConfig.settleAfterEnableFloorMs)
                        }
                    }
                }
                
                // Clear any pending tag events from previous attempts
                clearPendingTagEvents()
                
                // NOW set isReadingInProgress - from this point forward, accept new tags
                isReadingInProgress = true
                Timber.d("🟢 Now accepting tags")
                
                // === PHASE 2: ENABLE READER MODE ===
                currentReadState = NfcReadState.WaitingForTag
                Timber.d("🔵 NFC read state: WaitingForTag (accepting tags)")
                
                withContext(Dispatchers.Main) {
                    val activity = currentActivity
                        ?: throw PassportReadException.Unknown("No activity attached")
                    
                    // Enable ReaderMode if not already active
                    // Tags can be accepted immediately
                    enableReaderModeInternal(activity)
                }
                
                // Give ReaderMode a moment to initialize properly
                // Tags arriving during this period will be accepted
                delay(timingConfig.settleAfterEnableFloorMs)
                
                // === PHASE 3: AWAIT TAG ===
                Timber.d("🟢 Ready for tag detection (ReaderMode active)")
                
                val firstTag = try {
                    coroutineScope {
                        // The platform can discard the registration we just made
                        // when it re-applies NFC routing - see
                        // NfcReadTimingConfig.readerModeReassertDelayMs for the
                        // measurements. Nothing reports that, so re-assert once
                        // the routing has settled rather than waiting out the
                        // full timeout for a callback that will never come.
                        val reassert = launch {
                            var elapsed = 0L
                            for (at in timingConfig.readerModeReassertDelaysMs) {
                                delay(at - elapsed)
                                elapsed = at
                                // A tag has arrived; stop touching the field.
                                if (currentReadState !is NfcReadState.WaitingForTag) break
                                withContext(Dispatchers.Main) {
                                    currentActivity?.let {
                                        enableReaderModeInternal(it, force = true)
                                    }
                                }
                            }
                        }
                        try {
                            if (timingConfig.awaitTagTimeoutMs == Long.MAX_VALUE) {
                                // No limit: the wait ends when the read is cancelled.
                                tagBroadcaster.awaitTag()
                            } else {
                                withTimeout(timingConfig.awaitTagTimeoutMs) {
                                    tagBroadcaster.awaitTag()
                                }
                            }
                        } finally {
                            // A tag has arrived (or we gave up); never re-assert
                            // underneath an active read.
                            reassert.cancel()
                        }
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    throw PassportReadException.NoTagDetected(cause = e)
                }
                
                Timber.d("📱 Tag detected: techList=${firstTag.techList.contentToString()}")
                
                // === PHASE 4: SETTLE DELAY ===
                // Give the RF field time to stabilize after detection
                val sinceEnable = SystemClock.elapsedRealtime() - readerEnabledAtMs
                val settleDelay = timing.computeSettleDelay(sinceEnable)
                Timber.d("⏱️ Settling for ${settleDelay}ms before read")
                delay(settleDelay)
                val tag = newestTagAfterSettling(firstTag, settleDelay)
                
                // === PHASE 5: READ PASSPORT ===
                currentReadState = NfcReadState.Reading(ReadStage.Connecting)
                Timber.d("🔵 NFC read state: Reading")
                
                val passport = passportReader
                    .readPassport(tag, passportNumber, dateOfBirth, expiryDate) { stage ->
                        Timber.d("🔵 Read stage: $stage")
                        currentReadState = NfcReadState.Reading(stage)
                    }
                    .getOrThrow()
                
                // === PHASE 6: SUCCESS ===
                timing.onAttemptSuccess()
                currentReadState = NfcReadState.Success
                hadPreviousAttempt = true  // Mark for future retries
                Timber.d("✅ NFC read completed successfully")
                
                // Disable ReaderMode after success
                withContext(Dispatchers.Main) {
                    currentActivity?.let { disableReaderModeInternal(it) }
                }
                
                return@withContext passport
                
            } catch (e: PassportReadException) {
                hadPreviousAttempt = true  // Mark for future retries
                handleReadException(e)
                // Leave ReaderMode enabled so user can retry without button press
                throw e
                
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Expected when the user navigates away; must propagate for structured concurrency.
                Timber.d("NFC read cancelled (user navigated away)")
                throw e

            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Deliberately broad: the manager's boundary, where anything unexpected becomes Unknown.
                hadPreviousAttempt = true  // Mark for future retries
                val wrappedException = PassportReadException.Unknown(e.message, e)
                handleReadException(wrappedException)
                throw wrappedException
                
            } finally {
                isReadingInProgress = false
            }
        }
    }
    
    /**
     * The tag to read: the newest one delivered while settling, not the one
     * that ended the wait.
     *
     * A passport that slips off and back during the settle delay is
     * rediscovered as a new [Tag], and Android refuses all I/O on the old
     * handle ("Tag ... is out of date"). A slip can be rediscovered within a
     * second and still precede the read, which would then fail on the first
     * handle. A newer tag has just come into the field, so it gets a
     * settle delay of its own, up to [MAX_RESETTLES] times - a phone that keeps
     * slipping would otherwise hold the read here indefinitely. A tag that
     * reaches the app just after the read has started is not caught: that read
     * fails as TagLost and the user retries. The read is not restarted
     * automatically: a passport that returns quickly is usually still moving,
     * and a restarted read is usually lost again.
     */
    private suspend fun newestTagAfterSettling(first: Tag, settleDelay: Long): Tag {
        var tag = first
        var resettles = 0
        while (true) {
            val newer = tagBroadcaster.pollLatest() ?: return tag
            tag = newer
            if (resettles++ >= MAX_RESETTLES) {
                Timber.w("📱 Tag rediscovered again while settling; reading the newest without further delay")
                return tag
            }
            Timber.w("📱 Tag rediscovered while settling; settling ${settleDelay}ms on the new handle")
            delay(settleDelay)
        }
    }

    /**
     * Stop reading explicitly (called when user navigates away or presses stop).
     */
    suspend fun stopReading() = withContext(Dispatchers.Main) {
        Timber.d("🛑 Stopping NFC reading")
        isReadingInProgress = false
        currentReadState = NfcReadState.Idle
        
        currentActivity?.let { activity ->
            if (isReaderModeActive) {
                disableReaderModeInternal(activity)
            }
        }
    }
    
    /**
     * Retry after error - clears state and prepares for new attempt.
     * Note: ReaderMode will be disabled/re-enabled on next readPassport() call.
     */
    suspend fun retryAfterError() {
        Timber.d("🔄 Retry requested - clearing state")
        
        // Brief delay to let user reposition phone
        delay(timingConfig.retryReaderModeResetDelayMs)
        
        // Clear any pending events
        clearPendingTagEvents()
        
        // Reset state (ReaderMode stays active until next readPassport() call)
        currentReadState = NfcReadState.Idle
        isReadingInProgress = false
        
        Timber.d("✅ Ready for retry (ReaderMode will be reset on next read)")
    }

    /**
     * Handle read exceptions: update state and adaptive timing.
     */
    private fun handleReadException(e: PassportReadException) {
        // Update adaptive timing
        when (e) {
            is PassportReadException.TagLost,
            is PassportReadException.NfcIo -> {
                timing.onEarlyTagLost()
                Timber.w("⚠️ Early tag lost - adaptive timing increased")
            }
            else -> { }
        }

        currentReadState = NfcReadState.Error(
            titleRes = e.titleRes,
            messageRes = e.messageRes,
            messageArgs = e.messageArgs,
            canRetry = when (e) {
                is PassportReadException.WrongMrz -> false
                is PassportReadException.TrustOrPassiveAuthFailed -> false
                else -> true
            },
            technicalDetails = e.technicalDetails
        )

        // The copy is a resource, so the log records the failure by type
        // rather than by the words the user happened to be shown.
        Timber.e("""
        ❌ NFC read error: ${e::class.simpleName}
        
        Technical Details:
        ${e.technicalDetails ?: "None"}
    """.trimIndent())
    }


    /**
     * Internal: Enable ReaderMode.
     */
    private fun enableReaderModeInternal(activity: Activity, force: Boolean = false) {
        if (isReaderModeActive && !force) {
            Timber.d("ReaderMode already active")
            return
        }
        
        val adapter = NfcAdapter.getDefaultAdapter(activity) ?: run {
            Timber.e("NFC adapter not available")
            return
        }
        
        val flags = NfcAdapter.FLAG_READER_NFC_A or
                    NfcAdapter.FLAG_READER_NFC_B or
                    NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        
        val extras = Bundle().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, timingConfig.presenceCheckDelayMs)
            }
        }
        
        // Left alone on a re-assert: the field was never actually torn down,
        // so the settle delay should still date from the original enable.
        if (!force) readerEnabledAtMs = SystemClock.elapsedRealtime()
        
        adapter.enableReaderMode(
            activity,
            { tag -> onNfcTagDetected(tag) },
            flags,
            extras
        )
        
        isReaderModeActive = true
        Timber.d(if (force) "📡 ReaderMode re-asserted" else "📡 ReaderMode enabled")
    }
    
    /**
     * Internal: Disable ReaderMode.
     */
    private fun disableReaderModeInternal(activity: Activity) {
        if (!isReaderModeActive) return
        
        NfcAdapter.getDefaultAdapter(activity)?.disableReaderMode(activity)
        isReaderModeActive = false
        Timber.d("📡 ReaderMode disabled")
    }
    
    /**
     * Clear any pending tag events.
     */
    fun clearPendingTagEvents() {
        tagBroadcaster.clear()
        Timber.d("🗑️ Cleared pending tag events")
    }
    
    fun resetTiming() {
        timing.reset()
    }
    
    fun getNfcAdapter(): NfcAdapter? {
        return NfcAdapter.getDefaultAdapter(context)
    }
}
