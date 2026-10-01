package io.github.munkchunk.passportreader.verification

/**
 * Everything [PassiveAuthentication] and [ActiveAuthentication] need from the
 * chip, and nothing more.
 *
 * Kept this narrow so verification can run against stored bytes in a test,
 * with no phone and no passport. On a device it is backed by the live session.
 */
internal interface DocumentSource {

    /**
     * EF.DG[number] exactly as the chip stores it: tag, length and value. That
     * is what EF.SOd hashes, so re-encoding a parsed copy would not do.
     */
    fun readDataGroup(number: Int): DataGroupRead

    /**
     * Sends [challenge] to the chip's Active Authentication (INTERNAL
     * AUTHENTICATE, ICAO 9303-11) and returns the chip's signature.
     */
    fun activeAuthenticate(challenge: ByteArray): ByteArray
}

/** The outcome of asking the chip for one data group. */
internal sealed interface DataGroupRead {

    class Bytes(val value: ByteArray) : DataGroupRead

    /**
     * The chip answered, refusing the file. [statusWord] is the ISO 7816 status
     * word it gave - 0x6982 when access conditions are not met, 0x6A82 when
     * there is no such file.
     */
    class Refused(val statusWord: Int) : DataGroupRead

    /** The read did not complete, so the chip gave no answer at all. */
    class Failed(val cause: Throwable) : DataGroupRead
}
