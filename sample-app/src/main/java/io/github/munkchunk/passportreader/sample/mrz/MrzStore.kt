package io.github.munkchunk.passportreader.sample.mrz

import android.content.Context
import io.github.munkchunk.passportreader.sample.BuildConfig

/**
 * Remembers the last MRZ that read successfully, so testing against the same
 * document repeatedly does not mean retyping it every time.
 *
 * Debug builds only. An MRZ identifies a real person, and a convenience worth
 * having on a developer's bench is not worth shipping: in a release build
 * [load] returns null and [save] does nothing, so nothing is ever written.
 */
class MrzStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(key: MrzKey) {
        if (!BuildConfig.DEBUG) return
        prefs.edit()
            .putString(KEY_DOCUMENT, key.documentNumber)
            .putString(KEY_BIRTH, key.dateOfBirth)
            .putString(KEY_EXPIRY, key.dateOfExpiry)
            .apply()
    }

    fun load(): MrzKey? {
        if (!BuildConfig.DEBUG) return null
        val document = prefs.getString(KEY_DOCUMENT, null) ?: return null
        val birth = prefs.getString(KEY_BIRTH, null) ?: return null
        val expiry = prefs.getString(KEY_EXPIRY, null) ?: return null
        return MrzKey(document, birth, expiry)
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val PREFS_NAME = "mrz_debug_store"
        const val KEY_DOCUMENT = "document_number"
        const val KEY_BIRTH = "date_of_birth"
        const val KEY_EXPIRY = "date_of_expiry"
    }
}
