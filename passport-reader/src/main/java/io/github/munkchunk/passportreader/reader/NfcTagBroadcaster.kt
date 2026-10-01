package io.github.munkchunk.passportreader.reader

import android.nfc.Tag
import kotlinx.coroutines.channels.Channel

/**
 * Hands a detected [Tag] from the NFC ReaderMode callback to the coroutine
 * waiting inside a read attempt.
 *
 * Conflated: only the latest tag is kept, so a tag arriving while one is already
 * pending replaces it rather than queueing.
 */
internal class NfcTagBroadcaster {
    private val channel = Channel<Tag>(capacity = Channel.CONFLATED)

    fun broadcastTag(tag: Tag) {
        channel.trySend(tag)
    }

    suspend fun awaitTag(): Tag = channel.receive()

    /** The newest tag delivered since the last receive, or null if none has arrived. */
    fun pollLatest(): Tag? = channel.tryReceive().getOrNull()

    /** Drop any stale tag event, e.g. on retry. */
    fun clear() {
        while (channel.tryReceive().isSuccess) { /* drain */ }
    }
}
