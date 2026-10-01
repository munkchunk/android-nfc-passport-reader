package io.github.munkchunk.passportreader.reader

/**
 * Where a read has got to.
 *
 * A chip read takes around ten seconds and can fail, so the difference between
 * "still going" and "stuck" matters to whoever is holding the phone. These are
 * reported as they happen rather than estimated, because a fabricated progress
 * bar on an operation that can fail is worse than none.
 *
 * Ordered as they occur. Not every read passes through every stage - a BAC
 * document skips nothing, but a chip without a face image never reaches
 * [ReadingPhoto].
 */
enum class ReadStage {
    /** Opening the connection and working out what the chip supports. */
    Connecting,

    /** Proving we are allowed to read it, via PACE or BAC. */
    Authenticating,

    /** Reading the small files: the security object, the MRZ, chip keys. */
    ReadingData,

    /** Reading the face image, which is most of the bytes and most of the wait. */
    ReadingPhoto,

    /** Checking signatures, the certificate chain and the data group hashes. */
    Verifying
}
