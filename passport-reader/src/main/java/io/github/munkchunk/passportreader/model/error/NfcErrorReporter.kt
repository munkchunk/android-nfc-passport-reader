package io.github.munkchunk.passportreader.model.error

/**
 * A hook for sending read failures somewhere - crash reporting, analytics.
 *
 * Called on the main thread, once for each read that got as far as the tag and
 * failed. Not called for an MRZ refused before reading, for a manager that
 * never found a tag, or for a read the caller cancelled before it failed. If
 * [report] throws, the exception is logged and the read still returns its
 * own failure.
 */
interface NfcErrorReporter {
    /**
     * @param where "CARD" for a card service error from the exchange with the
     *   chip - a refused BAC or PACE, or the link lost mid-exchange. "GENERAL"
     *   for anything else, including a passport gone before the connection
     *   was made.
     * @param ex the failure, as readPassport also returns it.
     */
    fun report(where: String, ex: PassportReadException)
}