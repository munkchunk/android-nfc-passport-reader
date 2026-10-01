package io.github.munkchunk.passportreader.timing

/**
 * Every timing parameter of an NFC read, each with a default. [NfcTimingPresets]
 * offers ready-made sets; retries and cooldowns are decided by the read state
 * machine using these values.
 */
data class NfcReadTimingConfig(
    // === USER EXPERIENCE TIMEOUTS ===
    
    /**
     * How long to wait for user to place phone on passport before giving up.
     * Default: 20 seconds
     *
     * [Long.MAX_VALUE] means no limit: the wait ends only when the read is
     * cancelled. The app must then cancel it when its Activity pauses, since
     * pausing turns ReaderMode off but does not end the wait, and when NFC is
     * switched off (NfcAdapter.ACTION_ADAPTER_STATE_CHANGED), which from the
     * quick-settings shade does not pause the Activity at all; an uncancelled
     * wait holds the manager's read lock for good, and every later read queues
     * behind it. It should also
     * tell the user when nothing has been found for a while, and can
     * add a late step to [readerModeReassertDelaysMs] so a registration the
     * platform dropped silently is renewed while the user waits. Under WCAG
     * 2.2.1 a fixed limit here is the app's choice, not an essential one.
     */
    val awaitTagTimeoutMs: Long = 20_000L,

    // === RF STABILIZATION DELAYS ===
    
    /**
     * Base settle delay between tag detection and first APDU command.
     * INCREASED to 1500ms for PACE passport compatibility.
     * PACE requires significantly more time than BAC due to complex cryptographic operations.
     * Default: 1500ms
     */
    val settleBaseMs: Long = 1_500L,
    
    /**
     * Minimum settle delay (floor for adaptive adjustment).
     * Default: 1000ms (increased for PACE)
     */
    val settleMinMs: Long = 1_000L,
    
    /**
     * Maximum settle delay (ceiling for adaptive adjustment).
     * Default: 2000ms (2s)
     */
    val settleMaxMs: Long = 2_000L,
    
    /**
     * Initial delay after ReaderMode is first enabled.
     * This ensures RF field stabilizes before accepting first tag.
     * INCREASED to 500ms for robust PACE support.
     * Default: 500ms
     */
    val settleAfterEnableFloorMs: Long = 500L,

    // === ANDROID READER MODE SETTINGS ===
    
    /**
     * Android ReaderMode presence check interval (Android 10+).
     * Default: 500ms - good balance between stability and battery.
     */
    val presenceCheckDelayMs: Int = 500,

    // === RETRY DELAYS ===
    
    /**
     * Brief pause when retrying after error.
     * Default: 250ms
     */
    val retryReaderModeResetDelayMs: Long = 250L,

    /**
     * When to re-assert ReaderMode while still waiting for a tag, as offsets
     * from the start of the wait.
     *
     * Not padding: a registration can be silently discarded by the platform
     * re-applying its NFC routing just after it was made. On a Galaxy A32 the
     * NFC service force-disables polling while the camera is open and calls
     * applyRouting when it closes, roughly 85ms after the app's
     * enableReaderMode lands. Over nine measured reads, every one where that
     * notification arrived *after* enableReaderMode failed with NoTagDetected
     * and the platform ran NDEF detection - proof ReaderMode was not actually
     * active - and every one where it arrived first succeeded.
     *
     * The app cannot see that happen: enableReaderMode reports nothing, there
     * is no way to ask whether reader mode is active, and the callback simply
     * never fires - so the read waits out [awaitTagTimeoutMs] and reports a
     * passport that was sitting on the phone the whole time.
     *
     * Several offsets rather than one because the camera is the cause that was
     * measured, not the only possible cause: the notification shade, another
     * NFC app taking the foreground, or a rotation can all make the platform
     * re-apply routing, and at any point in the wait. The ladder covers later
     * clobbers for two extra calls. The default is front-loaded, so it is not
     * churning the field while a tag is likely to be arriving, and each
     * re-assert is skipped once a tag has been seen. A late step, as an app
     * waiting with no limit may add, trades that for renewing a registration
     * dropped part-way through a long wait; a tag arriving at that moment may
     * have to be discovered again.
     */
    val readerModeReassertDelaysMs: List<Long> = listOf(400L, 1_500L, 4_000L),

    // === CHIP READ (applied once the tag is in hand) ===

    /**
     * IsoDep transceive timeout. JMRTD operations on a slow chip - notably
     * reading DG2 - can take many seconds, and the platform default is far
     * too short.
     * Default: 15000ms
     */
    val isoDepTimeoutMs: Int = 15_000,

    /**
     * Pause after opening the card connection, before the first APDU.
     *
     * A fresh IsoDep connection restarts the RF field, so the card needs time
     * to initialise and bring up its PACE state machine. Too short and PACE
     * fails during key derivation or nonce exchange.
     * Default: 1000ms
     */
    val cardStabilizationMs: Long = 1_000L,

    // === PACE NEGOTIATION ===

    /**
     * Pause between trying one PACE protocol and the next, letting the card
     * reset in between.
     * Default: 100ms
     */
    val paceInterProtocolDelayMs: Long = 100L,

    /**
     * How many times to attempt a single PACE protocol before moving on.
     * Default: 3
     */
    val paceMaxRetries: Int = 3,

    /**
     * First backoff delay between PACE attempts. Doubles each retry, so the
     * default gives 200ms, 400ms, 800ms.
     * Default: 200ms
     */
    val paceRetryBaseDelayMs: Long = 200L
)

/**
 * Preset configurations for different device types or scenarios.
 */
object NfcTimingPresets {
    /**
     * Default/balanced configuration.
     * Optimized for PACE passports (which need longer settle times).
     * Should work for most devices with PACE passports.
     */
    val DEFAULT = NfcReadTimingConfig()
    
    /**
     * Conservative configuration for devices with very flaky NFC or challenging PACE passports.
     * Maximum settle delays and stability.
     * Use if default settings still have TagLost errors during PACE.
     */
    val CONSERVATIVE = NfcReadTimingConfig(
        settleBaseMs = 1_800L,
        settleMinMs = 1_200L,
        settleMaxMs = 2_500L,
        settleAfterEnableFloorMs = 600L,
        presenceCheckDelayMs = 400,
        cardStabilizationMs = 1_400L,
        paceMaxRetries = 4,
        paceInterProtocolDelayMs = 200L
    )
    
    /**
     * Fast configuration for devices with very stable NFC and BAC-only passports.
     * Shorter delays for snappier experience.
     * NOT recommended for PACE passports - will likely fail!
     */
    val FAST = NfcReadTimingConfig(
        settleBaseMs = 1_000L,
        settleMinMs = 800L,
        settleMaxMs = 1_500L,
        settleAfterEnableFloorMs = 300L,
        presenceCheckDelayMs = 600,
        cardStabilizationMs = 600L,
        paceMaxRetries = 2
    )
}
