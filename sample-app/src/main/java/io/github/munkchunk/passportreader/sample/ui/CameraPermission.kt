package io.github.munkchunk.passportreader.sample.ui

/**
 * Where the scanner stands with the camera permission.
 *
 * Android stops showing its permission dialog after the second refusal
 * (Android 11 and later) or once "Don't ask again" is ticked (Android 10), and
 * refuses on the user's behalf from then on, so a button that asks again does
 * nothing. [Blocked] is that case: the only way back is the app's page in
 * Settings.
 */
enum class CameraPermission {
    Granted,

    /** The system dialog is up; the scanner shows nothing behind it. */
    Asking,

    /** Refused, but Android will still show its dialog if asked again. */
    Denied,

    /** Refused, and Android will not ask again. */
    Blocked;

    companion object {
        /**
         * The state after the system dialog returns.
         *
         * [canAskAgain] is `shouldShowRequestPermissionRationale`, which is
         * true only once the user has refused and Android is still willing
         * to ask. It is also false when the very first dialog is dismissed
         * without an answer, so that case reads as [Blocked]; Settings still
         * grants the permission from there.
         */
        fun afterRequest(granted: Boolean, canAskAgain: Boolean): CameraPermission = when {
            granted -> Granted
            canAskAgain -> Denied
            else -> Blocked
        }
    }
}
