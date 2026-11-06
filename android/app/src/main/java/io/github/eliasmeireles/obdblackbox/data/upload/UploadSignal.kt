package io.github.eliasmeireles.obdblackbox.data.upload

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Wakes the [Uploader] as soon as a sample is queued, so live samples leave the phone right away. */
class UploadSignal {
    private val queued = Channel<Unit>(Channel.CONFLATED)

    fun notifyQueued() {
        queued.trySend(Unit)
    }

    /** Returns once a sample was queued since the previous call, or after [timeoutMs]. */
    suspend fun awaitQueued(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { queued.receive() }
    }
}
