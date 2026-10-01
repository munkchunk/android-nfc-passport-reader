package io.github.munkchunk.passportreader.reader

import androidx.annotation.StringRes

/**
 * Where a passport read is, as one state machine for the whole flow.
 */
sealed class NfcReadState {
    /**
     * Initial state or after successful read/navigation away.
     */
    data object Idle : NfcReadState()
    
    /**
     * Preparing to read: clearing state, waiting for cooldown, enabling ReaderMode.
     */
    data object Initializing : NfcReadState()
    
    /**
     * ReaderMode active, waiting for user to tap passport.
     */
    data object WaitingForTag : NfcReadState()
    
    /**
     * Tag detected; the chip is being read.
     * @param stage where the read has got to, reported as it happens
     */
    data class Reading(val stage: ReadStage = ReadStage.Connecting) : NfcReadState()
    
    /**
     * Read completed successfully.
     */
    data object Success : NfcReadState()
    
    /**
     * Read failed with an error.
     *
     * Carries which failure this was, not the words for it: resolve with
     * `context.getString(messageRes, *messageArgs.toTypedArray())`. The copy
     * lives in this library's `strings.xml` and a consuming app can override
     * any of it by redeclaring the name.
     *
     * @param titleRes short heading for the failure
     * @param messageRes body copy, formatted with [messageArgs]
     * @param messageArgs format arguments for [messageRes]; empty when none
     * @param technicalDetails diagnostics for a bug report, not copy
     * @param canRetry whether the user can/should retry
     */
    data class Error(
        @StringRes val titleRes: Int,
        @StringRes val messageRes: Int,
        val messageArgs: List<Any> = emptyList(),
        val technicalDetails: String? = null,
        val canRetry: Boolean = true,
    ) : NfcReadState()
}

/**
 * Helper to check if we're actively reading (waiting for tag or processing).
 */
fun NfcReadState.isActivelyReading(): Boolean {
    return this is NfcReadState.WaitingForTag || this is NfcReadState.Reading
}

/**
 * Helper to check if ReaderMode should be enabled for this state.
 */
fun NfcReadState.shouldEnableReaderMode(): Boolean {
    return this is NfcReadState.Initializing || 
           this is NfcReadState.WaitingForTag || 
           this is NfcReadState.Reading
}
