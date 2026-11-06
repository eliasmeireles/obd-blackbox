package io.github.eliasmeireles.obdblackbox.data.upload

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.data.recording.RecorderFactory
import io.github.eliasmeireles.obdblackbox.data.recording.SessionInfo
import io.github.eliasmeireles.obdblackbox.data.recording.SessionRecorder
import io.github.eliasmeireles.obdblackbox.data.recording.TelemetryRow

/** Puts every sample in the upload queue; the [Uploader] sends it when there is internet. */
class QueueRecorder(
    private val session: SessionInfo,
    private val queue: UploadQueue,
    private val signal: UploadSignal? = null,
) : SessionRecorder {
    init {
        queue.addSession(session)
    }

    override val name: String = text(R.string.msg_fila_value, (session.id).toString())

    override fun record(row: TelemetryRow) {
        queue.enqueue(session.id, row.snapshot.timestampMs, UploadPayload.sample(row))
        signal?.notifyQueued()
    }

    override fun close() = Unit

    companion object {
        fun factory(queue: UploadQueue, signal: UploadSignal? = null) = RecorderFactory { QueueRecorder(it, queue, signal) }
    }
}
