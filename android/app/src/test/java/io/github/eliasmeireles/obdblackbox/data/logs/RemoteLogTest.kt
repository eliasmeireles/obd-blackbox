package io.github.eliasmeireles.obdblackbox.data.logs

import io.github.eliasmeireles.obdblackbox.data.recording.SessionInfo
import io.github.eliasmeireles.obdblackbox.data.upload.QueuedLog
import io.github.eliasmeireles.obdblackbox.data.upload.QueuedSample
import io.github.eliasmeireles.obdblackbox.data.upload.UploadQueue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteLogTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private class LogQueue : UploadQueue {
        val logs = mutableListOf<JSONObject>()
        override fun enqueueLog(json: String) { logs += JSONObject(json) }
        override fun nextLogs(limit: Int) = emptyList<QueuedLog>()
        override fun removeLogs(ids: List<Long>) = Unit
        override fun addSession(info: SessionInfo) = Unit
        override fun enqueue(sessionId: String, timestampMs: Long, json: String) = Unit
        override fun unregisteredSessions() = emptyList<SessionInfo>()
        override fun markRegistered(sessionId: String) = Unit
        override fun markUnregistered(sessionId: String) = Unit
        override fun nextBatch(limit: Int) = emptyList<QueuedSample>()
        override fun remove(ids: List<Long>) = Unit
        override fun pendingCount() = 0L
    }

    private var now = 0L
    private val queue = LogQueue()
    private val log = RemoteLog(queue, clock = { now }, repeatWindowMs = 1_000)

    @Test fun `disabled remote logging never enqueues an entry`() {
        val disabled = RemoteLog(queue, enabled = false)
        repeat(5) { disabled.log(LogLevel.ERROR, "example", "example log") }
        assertEquals(0, queue.logs.size)
    }

    @Test fun `entries carry level, tag, message and time`() {
        now = 42
        log.log(LogLevel.WARN, "elm", "no answer")
        val entry = queue.logs.single()
        assertEquals(listOf("warn", "elm", "no answer", 42L), listOf(entry.getString("level"), entry.getString("tag"), entry.getString("message"), entry.getLong("t")))
        assertEquals(36, entry.getString("id").length)
    }

    @Test fun `repeated warnings are counted and sent again after the window`() {
        repeat(5) { log.log(LogLevel.WARN, "source", "SPP failed") }
        now = 1_500
        log.log(LogLevel.WARN, "source", "SPP failed")

        assertEquals(listOf("SPP failed", "SPP failed (repeated 4× since last sent)"), queue.logs.map { it.getString("message") })
    }

    @Test fun `errors and debug lines are never collapsed`() {
        repeat(2) { log.log(LogLevel.ERROR, "crash", "boom") }
        repeat(2) { log.log(LogLevel.DEBUG, "elm", "> 010C") }
        assertEquals(4, queue.logs.size)
    }
}
