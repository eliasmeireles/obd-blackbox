package io.github.eliasmeireles.obdblackbox.data.upload

import io.github.eliasmeireles.obdblackbox.data.recording.SessionInfo

/** One build-time switch covers the loop, streaming and every durable producer. */
class UploadPolicy(baseUrl: String) {
    val enabled = baseUrl.isNotBlank()

    fun queue(create: () -> UploadQueue): UploadQueue = if (enabled) create() else DisabledUploadQueue

    fun startUploader(start: () -> Unit) { if (enabled) start() }
}

private object DisabledUploadQueue : UploadQueue {
    override fun addSession(info: SessionInfo) = Unit
    override fun enqueue(sessionId: String, timestampMs: Long, json: String) = Unit
    override fun enqueueDiagnostic(sessionId: String, json: String) = Unit
    override fun enqueueLog(json: String) = Unit
    override fun unregisteredSessions() = emptyList<SessionInfo>()
    override fun markRegistered(sessionId: String) = Unit
    override fun markUnregistered(sessionId: String) = Unit
    override fun nextBatch(limit: Int) = emptyList<QueuedSample>()
    override fun nextLogs(limit: Int) = emptyList<QueuedLog>()
    override fun remove(ids: List<Long>) = Unit
    override fun removeLogs(ids: List<Long>) = Unit
    override fun pendingCount() = 0L
}
