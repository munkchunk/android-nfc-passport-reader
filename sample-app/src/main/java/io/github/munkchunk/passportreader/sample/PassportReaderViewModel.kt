package io.github.munkchunk.passportreader.sample

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.munkchunk.passportreader.data.Passport
import io.github.munkchunk.passportreader.reader.NfcPassportReaderManager
import io.github.munkchunk.passportreader.reader.NfcReadState
import io.github.munkchunk.passportreader.timing.NfcTimingPresets
import io.github.munkchunk.passportreader.sample.mrz.MrzKey
import io.github.munkchunk.passportreader.sample.mrz.MrzStore
import io.github.munkchunk.passportreader.sample.mrz.padDocumentNumber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * How long the wait for a passport runs before the reading screen says nothing
 * has been found yet, and ReaderMode is re-asserted once more.
 */
const val STILL_WAITING_MS = 20_000L

/** Which of the three screens is showing. */
enum class Screen { MrzEntry, Scan, Reading, Result }

class PassportReaderViewModel(application: Application) : AndroidViewModel(application) {

    // No limit on the wait for a passport (WCAG 2.2.1; see docs/sample-app.md): it ends on
    // Cancel, Back, NFC being switched off, or the Activity pausing, which
    // nfcStateReceiver and detachActivity handle. At
    // STILL_WAITING_MS the reading screen shows a hint, and ReaderMode is
    // re-asserted once more in case the platform dropped it without a word.
    private val manager = NfcPassportReaderManager(
        context = application,
        timingConfig = NfcTimingPresets.DEFAULT.copy(
            awaitTagTimeoutMs = Long.MAX_VALUE,
            readerModeReassertDelaysMs = NfcTimingPresets.DEFAULT.readerModeReassertDelaysMs + STILL_WAITING_MS
        )
    )

    private val mrzStore = MrzStore(application)

    val readState: StateFlow<NfcReadState> = manager.readState

    var screen by mutableStateOf(Screen.MrzEntry)
        private set

    var passport by mutableStateOf<Passport?>(null)
        private set

    /** Last MRZ that read successfully. Debug builds only; null in release. */
    var previousMrz by mutableStateOf(mrzStore.load())
        private set

    /**
     * What was last typed or scanned, kept so leaving the form and coming back
     * does not lose it - including when a read is interrupted by the screen
     * locking. Dropped once a read succeeds: the next form is for another
     * passport, and the debug Previous button is how to read the same one.
     */
    var draftMrz by mutableStateOf<MrzKey?>(null)
        private set

    private var mrzKey: MrzKey? = null
    private var readJob: Job? = null

    fun isNfcAvailable(): Boolean = manager.isNfcAvailable()

    /**
     * Whether NFC is switched on, kept live. Asked once, the answer goes
     * stale: switching NFC on from the quick-settings shade does not pause the
     * app, so the "NFC is switched off" warning would stay until a restart. The
     * system announces every change, and a return from the Settings app is
     * covered again by [attachActivity].
     */
    var nfcEnabled by mutableStateOf(manager.isNfcEnabled())
        private set

    private val nfcStateReceiver = object : BroadcastReceiver() {
        // Only a prompt to ask again: the adapter is the one source of the answer.
        // Exporting the receiver opens nothing, since this is a protected
        // broadcast that only the platform may send.
        override fun onReceive(context: Context, intent: Intent) {
            nfcEnabled = manager.isNfcEnabled()
            // Switched off mid-read (the quick-settings shade does not pause
            // the app): nothing is listening any more and, with no limit on the
            // wait, nothing would end it. Back to the form, where the warning
            // says why and the details are kept.
            if (!nfcEnabled && screen == Screen.Reading) backToEntry()
        }
    }

    init {
        // Exported, or the broadcast never arrives: it is sent by the NFC
        // service (com.android.nfc), not by the system uid, so a not-exported
        // receiver is refused it ("Exported Denial" in logcat).
        ContextCompat.registerReceiver(
            application,
            nfcStateReceiver,
            IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun attachActivity(activity: Activity) {
        nfcEnabled = manager.isNfcEnabled()
        manager.attachActivity(activity)
    }

    /**
     * Called when the Activity pauses - the screen locking, or the app going to
     * the background.
     *
     * A read cannot survive that: the manager turns ReaderMode off and starts
     * suppressing tags, so the coroutine sits waiting for something that can
     * never arrive. The wait has no limit, so this cancel is what ends it;
     * left alone it would hold the manager's read lock for good. Stop, and
     * return to the form with what they typed still in it.
     */
    fun detachActivity() {
        manager.detachActivity()
        if (screen == Screen.Reading) {
            readJob?.cancel()
            screen = Screen.MrzEntry
        }
    }

    fun startScan() {
        screen = Screen.Scan
    }

    fun cancelScan() {
        screen = Screen.MrzEntry
    }

    fun startRead(key: MrzKey) {
        draftMrz = key
        // The form's buttons are disabled while NFC is off, but a scan can
        // finish after NFC was switched off with the camera open. A read then
        // would wait for a chip it cannot hear; back to the form instead,
        // where the warning says why and the scanned fields are kept.
        if (!nfcEnabled) {
            screen = Screen.MrzEntry
            return
        }
        mrzKey = key
        screen = Screen.Reading
        launchRead(key)
    }

    fun retry() {
        val key = mrzKey ?: return
        readJob?.cancel()
        // One tracked job, pause included: otherwise Cancel during the pause
        // misses it, and the read it then starts waits with no screen showing it.
        readJob = viewModelScope.launch {
            manager.retryAfterError()
            read(key)
        }
    }

    /** Back to the form; stops the reader so ReaderMode does not stay on. */
    fun backToEntry() {
        readJob?.cancel()
        viewModelScope.launch { manager.stopReading() }
        passport = null
        screen = Screen.MrzEntry
    }

    private fun launchRead(key: MrzKey) {
        readJob = viewModelScope.launch { read(key) }
    }

    private suspend fun read(key: MrzKey) {
        try {
            passport = manager.readPassport(
                passportNumber = key.documentNumber.padDocumentNumber(),
                dateOfBirth = key.dateOfBirth,
                expiryDate = key.dateOfExpiry
            )
            // Only remember an MRZ the chip actually accepted, so the
            // shortcut can never replay a typo.
            mrzStore.save(key)
            previousMrz = key
            draftMrz = null
            screen = Screen.Result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The manager has already published an Error state for the UI.
            Timber.w(e, "Passport read failed")
        }
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(nfcStateReceiver)
        manager.detachActivity()
    }
}
