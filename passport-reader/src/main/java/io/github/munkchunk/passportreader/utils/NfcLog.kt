package io.github.munkchunk.passportreader.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import timber.log.Timber

internal object NfcLog {

    /**
     * Tag for which optional data groups a read found and fetched:
     * `adb logcat -s DATA_GROUPS` gives one absent / read / failed line per
     * group, and says when one of DG12's images would not decode. Both test
     * documents carry only DG1, DG2 and DG14, so this is the only evidence of
     * how the other groups behave.
     */
    const val DATA_GROUPS = "DATA_GROUPS"

    /** Optional master switch if you ever want to kill NFC logs entirely */
    var enabled: Boolean = true

    /**
     * Whether logs may contain personal data read from the document - the MRZ,
     * the holder's name, the document number.
     *
     * Defaults to **false**, and stays false unless [configureFrom] sees a
     * debuggable host application. The library cannot use its own
     * `BuildConfig.DEBUG` for this: once published as an AAR it is always a
     * release build, so that flag would disable the logs developers want while
     * telling them nothing about the app doing the consuming.
     *
     * This matters because Timber only emits through a planted tree. An app
     * that plants one in release - routing to a crash reporter, say - would
     * otherwise ship passport data off the device.
     */
    @Volatile
    var allowPersonalData: Boolean = false

    /**
     * Enables personal-data logging only when the host application is
     * debuggable, which tracks the consuming app's build type rather than this
     * library's. Call [allowPersonalData] directly to override.
     */
    fun configureFrom(context: Context) {
        allowPersonalData =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    /**
     * Logs a message that contains personal data, and only when that is
     * permitted. The message is a lambda so nothing is even built otherwise.
     */
    fun personal(tag: String, message: () -> String) {
        if (!enabled || !allowPersonalData) return
        Timber.tag(tag).d(message())
    }

    /** Returns [value] when personal data is permitted, a placeholder if not. */
    fun redact(value: Any?): String =
        if (allowPersonalData) value?.toString() ?: "null" else "[redacted]"

    fun d(tag: String, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        if (throwable != null) {
            Timber.tag(tag).d(throwable, message)
        } else {
            Timber.tag(tag).d(message)
        }
    }

    fun i(tag: String, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        if (throwable != null) {
            Timber.tag(tag).i(throwable, message)
        } else {
            Timber.tag(tag).i(message)
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        if (throwable != null) {
            Timber.tag(tag).w(throwable, message)
        } else {
            Timber.tag(tag).w(message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!enabled) return
        if (throwable != null) {
            Timber.tag(tag).e(throwable, message)
        } else {
            Timber.tag(tag).e(message)
        }
    }
}
