package io.github.eliasmeireles.obdblackbox.data.upload

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.data.recording.SessionInfo

data class QueuedSample(val id: Long, val sessionId: String, val json: String)

data class QueuedDiagnostic(val id: Long, val sessionId: String, val json: String)

data class QueuedLog(val id: Long, val json: String)

/** Durable store of samples waiting to be sent; survives app kills and days without internet. */
interface UploadQueue {
    fun enqueueDiagnostic(sessionId: String, json: String) { error(text(R.string.msg_fila_sem_suporte_a_diagnosticos)) }
    fun nextDiagnostics(limit: Int): List<QueuedDiagnostic> = emptyList()
    fun removeDiagnostics(ids: List<Long>) = Unit

    fun addSession(info: SessionInfo)

    fun enqueue(sessionId: String, timestampMs: Long, json: String)

    fun unregisteredSessions(): List<SessionInfo>

    fun markRegistered(sessionId: String)

    fun markUnregistered(sessionId: String)

    /** Oldest pending samples of a single session (the API takes one session per request). */
    fun nextBatch(limit: Int): List<QueuedSample>

    fun remove(ids: List<Long>)

    fun pendingCount(): Long

    /** App log entries for the server (docs/backend-spec.md); the oldest are dropped past a limit. */
    fun enqueueLog(json: String)

    fun nextLogs(limit: Int): List<QueuedLog>

    fun removeLogs(ids: List<Long>)
}
