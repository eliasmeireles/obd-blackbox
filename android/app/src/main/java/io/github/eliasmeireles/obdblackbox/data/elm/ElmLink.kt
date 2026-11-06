package io.github.eliasmeireles.obdblackbox.data.elm

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Raw byte pipe to an ELM327 adapter (Bluetooth Classic SPP or BLE). Blocking: use it from an IO thread. */
interface ElmLink : AutoCloseable {
    fun write(data: ByteArray)

    /** An actual ECU reply, as opposed to an adapter response. */
    fun onEcuAnswer() = Unit

    /** The next chunk of received bytes, waiting up to [timeoutMs]; null on timeout. Throws once the link is closed. */
    fun read(timeoutMs: Long): ByteArray?
}

/** Base for links whose bytes arrive on another thread (socket reader thread, GATT notifications). */
abstract class QueuedLink : ElmLink {
    private val incoming = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var failure: String? = null

    protected fun deliver(bytes: ByteArray) {
        if (bytes.isNotEmpty()) incoming.put(bytes)
    }

    /** Ends the link: pending bytes are still read, then every read throws with [reason]. */
    protected fun fail(reason: String) {
        if (failure != null) return
        failure = reason
        incoming.put(CLOSED)
    }

    override fun read(timeoutMs: Long): ByteArray? {
        val chunk = incoming.poll(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS) ?: return null
        if (chunk === CLOSED) {
            incoming.put(CLOSED)
            throw IOException(failure ?: text(R.string.msg_conexao_encerrada))
        }
        return chunk
    }

    private companion object {
        val CLOSED = ByteArray(0)
    }
}
