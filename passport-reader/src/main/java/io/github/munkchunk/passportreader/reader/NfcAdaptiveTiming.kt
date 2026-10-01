package io.github.munkchunk.passportreader.reader

import io.github.munkchunk.passportreader.timing.NfcReadTimingConfig
import timber.log.Timber
import kotlin.math.min

/**
 * Adapts the settle delay before the first APDU to how the connection is
 * behaving. It manages settle delays only; retries and cooldowns belong to
 * the read state machine.
 */
internal class NfcAdaptiveTiming(
    private val config: NfcReadTimingConfig
) {
    @Volatile private var currentSettleMs = config.settleBaseMs
    
    /**
     * Compute settle delay based on how long since ReaderMode was enabled.
     * If detection happened very quickly (< settleAfterEnableFloorMs), enforce the floor.
     * 
     * This is critical for PACE passports which need longer RF stabilization.
     */
    fun computeSettleDelay(sinceEnableMs: Long): Long {
        val delay = if (sinceEnableMs < config.settleAfterEnableFloorMs) {
            // Quick detection - use the floor to ensure RF stability
            config.settleAfterEnableFloorMs
        } else {
            // Normal detection - use current adaptive value
            currentSettleMs
        }
        
        Timber.d("NFC settle delay: ${delay}ms (sinceEnable=${sinceEnableMs}ms)")
        return delay
    }
    
    /**
     * Called when an early tag-lost or I/O error occurs.
     * Increases settle time (up to configured maximum).
     */
    fun onEarlyTagLost() {
        val oldSettle = currentSettleMs
        
        // Increase by 200ms, capped at max
        currentSettleMs = min(currentSettleMs + 200, config.settleMaxMs)
        
        Timber.w("NFC adaptive timing increased: settle ${oldSettle}→${currentSettleMs}ms")
    }
    
    /**
     * Called after a successful read.
     * Gradually reduces timing back toward base value.
     */
    fun onAttemptSuccess() {
        val oldSettle = currentSettleMs
        
        // Decrease by 100ms, but not below minimum
        currentSettleMs = maxOf(currentSettleMs - 100, config.settleMinMs)
        
        if (currentSettleMs != oldSettle) {
            Timber.d("NFC adaptive timing decreased: settle ${oldSettle}→${currentSettleMs}ms")
        }
    }
    
    /**
     * Reset to base value (e.g., when user navigates away).
     */
    fun reset() {
        currentSettleMs = config.settleBaseMs
        Timber.d("NFC adaptive timing reset to base value")
    }
}
