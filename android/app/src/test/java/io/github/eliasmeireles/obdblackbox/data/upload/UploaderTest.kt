package io.github.eliasmeireles.obdblackbox.data.upload

import io.github.eliasmeireles.obdblackbox.data.SourceKind
import io.github.eliasmeireles.obdblackbox.data.recording.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploaderTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private class MemoryQueue : UploadQueue {
        val sessions = mutableMapOf<String, Pair<SessionInfo, Boolean>>()
        val samples = mutableListOf<QueuedSample>()
        private var nextId = 1L
        override fun addSession(info: SessionInfo) { sessions.putIfAbsent(info.id, info to false) }
        override fun enqueue(sessionId: String, timestampMs: Long, json: String) { samples += QueuedSample(nextId++, sessionId, json) }
        override fun unregisteredSessions() = sessions.values.filter { !it.second }.map { it.first }
        override fun markRegistered(sessionId: String) { sessions[sessionId] = sessions.getValue(sessionId).first to true }
        override fun markUnregistered(sessionId: String) { sessions[sessionId] = sessions.getValue(sessionId).first to false }
        override fun nextBatch(limit: Int): List<QueuedSample> {
            val first = samples.firstOrNull() ?: return emptyList()
            return samples.filter { it.sessionId == first.sessionId }.take(limit)
        }
        override fun remove(ids: List<Long>) { samples.removeAll { it.id in ids } }
        override fun pendingCount() = samples.size.toLong()
        val diagnostics = mutableListOf<QueuedDiagnostic>()
        override fun enqueueDiagnostic(sessionId: String, json: String) { diagnostics += QueuedDiagnostic(nextId++, sessionId, json) }
        override fun nextDiagnostics(limit: Int) = diagnostics.take(limit)
        override fun removeDiagnostics(ids: List<Long>) { diagnostics.removeAll { it.id in ids } }
        val logs = mutableListOf<QueuedLog>()
        override fun enqueueLog(json: String) { logs += QueuedLog(nextId++, json) }
        override fun nextLogs(limit: Int) = logs.take(limit)
        override fun removeLogs(ids: List<Long>) { logs.removeAll { it.id in ids } }
    }

    private class FakeClient(
        var sessionResult: ApiResult = ApiResult.Ok,
        var samplesResult: ApiResult = ApiResult.Ok,
        override val live: Boolean = false,
        var logsResult: ApiResult = ApiResult.Ok,
        var diagnosticResult: ApiResult = ApiResult.Ok,
    ) : MonitorApiClient {
        var connections = 0
        var disconnects = 0
        override fun maintainConnection(config: ServerConfig) { connections++ }
        override fun disconnect() { disconnects++ }
        val diagnosticPosts = mutableListOf<Pair<String, String>>()
        override fun postDiagnostics(config: ServerConfig, sessionId: String, json: String): ApiResult {
            assertTrue(putSessions.contains(sessionId))
            diagnosticPosts += sessionId to json
            return diagnosticResult
        }
        val putSessions = mutableListOf<String>()
        val posts = mutableListOf<String>()
        val logPosts = mutableListOf<List<String>>()
        override fun postLogs(config: ServerConfig, entries: List<String>): ApiResult = logsResult.also { logPosts += entries }
        override fun putSession(config: ServerConfig, session: SessionInfo): ApiResult = sessionResult.also { putSessions += session.id }
        override fun postSamples(config: ServerConfig, sessionId: String, samples: List<String>): ApiResult = samplesResult.also { posts += UploadPayload.batch(samples) }
    }

    private class FixedConfig(var config: ServerConfig) : ServerConfigStore {
        override fun load() = config
        override fun save(config: ServerConfig) { this.config = config }
    }

    private val configured = FixedConfig(ServerConfig("https://example.test", "key"))

    private fun queueWith(samples: Int, session: String = "s1") = MemoryQueue().apply {
        addSession(SessionInfo(session, SourceKind.SIMULATION, 0L))
        repeat(samples) { enqueue(session, it.toLong(), "{\"t\":$it}") }
    }

    @Test fun `without api key nothing is sent`() {
        val client = FakeClient()
        val uploader = Uploader(queueWith(3), client, FixedConfig(ServerConfig("https://example.test", "")))
        assertEquals(UploadOutcome.NOT_CONFIGURED, uploader.runOnce())
        assertTrue(client.posts.isEmpty())
        assertFalse(uploader.status.value.configured)
    }

    @Test fun `registers the session, sends the batch and empties the queue`() {
        val queue = queueWith(3)
        val client = FakeClient()
        val uploader = Uploader(queue, client, configured, clock = { 42L })
        assertEquals(UploadOutcome.IDLE, uploader.runOnce())
        assertEquals(listOf("s1"), client.putSessions)
        assertEquals(0L, queue.pendingCount())
        assertEquals(42L, uploader.status.value.lastSuccessMs)
    }

    @Test fun `full batch asks to run again immediately`() {
        val uploader = Uploader(queueWith(5), FakeClient(), configured, batchSize = 2)
        assertEquals(UploadOutcome.SENT_MORE, uploader.runOnce())
    }

    @Test fun `network failure keeps samples for retry`() {
        val queue = queueWith(3)
        val uploader = Uploader(queue, FakeClient(samplesResult = ApiResult.Failed(null, "timeout")), configured)
        assertEquals(UploadOutcome.FAILED, uploader.runOnce())
        assertEquals(3L, queue.pendingCount())
        assertNotNull(uploader.status.value.lastError)
    }

    @Test fun `unknown session on server is registered again`() {
        val queue = queueWith(3)
        val client = FakeClient(samplesResult = ApiResult.Failed(404, "HTTP 404"))
        val uploader = Uploader(queue, client, configured)
        uploader.runOnce()
        client.samplesResult = ApiResult.Ok
        uploader.runOnce()
        assertEquals(listOf("s1", "s1"), client.putSessions)
        assertEquals(0L, queue.pendingCount())
    }

    @Test fun `rejected batch is dropped so it does not block the queue`() {
        val queue = queueWith(3)
        val uploader = Uploader(queue, FakeClient(samplesResult = ApiResult.Failed(400, "HTTP 400")), configured)
        assertEquals(UploadOutcome.FAILED, uploader.runOnce())
        assertEquals(0L, queue.pendingCount())
    }

    @Test fun `session registration failure stops before sending samples`() {
        val client = FakeClient(sessionResult = ApiResult.Failed(401, "HTTP 401"))
        val uploader = Uploader(queueWith(3), client, configured)
        assertEquals(UploadOutcome.FAILED, uploader.runOnce())
        assertTrue(client.posts.isEmpty())
    }

    @Test fun `reports a live link after sending through a streaming client`() {
        val uploader = Uploader(queueWith(2), FakeClient(live = true), configured)

        uploader.runOnce()

        assertTrue(uploader.status.value.live)
    }

    @Test fun `logs go out in the same cycle once the samples are caught up`() {
        val queue = queueWith(1).apply { enqueueLog("{\"id\":\"a\"}") }
        val client = FakeClient()
        val uploader = Uploader(queue, client, configured, batchSize = 10)

        uploader.runOnce()
        assertEquals(1, client.posts.size)
        assertEquals(listOf(listOf("{\"id\":\"a\"}")), client.logPosts)
        assertTrue(queue.logs.isEmpty())
    }

    @Test fun `logs are not starved while new samples arrive every cycle`() {
        val queue = queueWith(1).apply { enqueueLog("{\"id\":\"a\"}") }
        val client = FakeClient()
        val uploader = Uploader(queue, client, configured, batchSize = 10)

        repeat(3) { cycle ->
            uploader.runOnce()
            queue.enqueue("s1", 10L + cycle, "{}")
        }
        assertEquals(1, client.logPosts.size)
    }

    @Test fun `logs wait while a backlog of samples is being sent`() {
        val queue = queueWith(5).apply { enqueueLog("{}") }
        val client = FakeClient()
        val uploader = Uploader(queue, client, configured, batchSize = 2)

        assertEquals(UploadOutcome.SENT_MORE, uploader.runOnce())
        assertTrue(client.logPosts.isEmpty())
    }

    @Test fun `a server without the logs api keeps the logs and does not slow the samples`() {
        var now = 0L
        val queue = queueWith(0).apply { enqueueLog("{}") }
        val client = FakeClient(logsResult = ApiResult.Failed(404, "HTTP 404"))
        val uploader = Uploader(queue, client, configured, clock = { now })

        assertEquals(UploadOutcome.IDLE, uploader.runOnce())
        assertEquals(1, queue.logs.size)
        queue.enqueue("s1", 1, "{}")
        assertEquals(UploadOutcome.IDLE, uploader.runOnce())
        assertTrue(queue.samples.isEmpty())
        assertEquals(1, client.logPosts.size)
        now = 61_000
        uploader.runOnce()
        assertEquals(2, client.logPosts.size)
    }

    @Test fun `diagnostics retain raw JSON and are sent after registering the session`() {
        val queue = queueWith(0).apply { enqueueDiagnostic("s1", "{\"t\":1,\"future_field\":true}") }
        val client = FakeClient()
        val uploader = Uploader(queue, client, configured)
        assertEquals(UploadOutcome.SENT_MORE, uploader.runOnce())
        assertEquals(listOf("s1" to "{\"t\":1,\"future_field\":true}"), client.diagnosticPosts)
        assertTrue(queue.diagnostics.isEmpty())
    }

    @Test fun `diagnostic 404 registers the session again and retries the snapshot`() {
        val queue = queueWith(0).apply { enqueueDiagnostic("s1", "{}") }
        val client = FakeClient(diagnosticResult = ApiResult.Failed(404, "sessão ausente"))
        val uploader = Uploader(queue, client, configured)
        assertEquals(UploadOutcome.FAILED, uploader.runOnce())
        assertEquals(1, queue.diagnostics.size)
        client.diagnosticResult = ApiResult.Ok
        uploader.runOnce()
        assertEquals(listOf("s1", "s1"), client.putSessions)
        assertTrue(queue.diagnostics.isEmpty())
    }

    @Test fun `invalid diagnostics are dropped`() {
        val queue = queueWith(0).apply { enqueueDiagnostic("s1", "{}") }
        val uploader = Uploader(queue, FakeClient(diagnosticResult = ApiResult.Failed(400, "inválido")), configured)
        assertEquals(UploadOutcome.FAILED, uploader.runOnce())
        assertTrue(queue.diagnostics.isEmpty())
    }

    @Test fun `oversized diagnostics are dropped`() {
        val queue = queueWith(0).apply { enqueueDiagnostic("s1", "{}") }
        val uploader = Uploader(queue, FakeClient(diagnosticResult = ApiResult.Failed(413, "grande")), configured)
        uploader.runOnce()
        assertTrue(queue.diagnostics.isEmpty())
    }

    @Test fun `diagnostic network failures retain the snapshot for retry`() {
        val queue = queueWith(0).apply { enqueueDiagnostic("s1", "{}") }
        val uploader = Uploader(queue, FakeClient(diagnosticResult = ApiResult.Failed(null, "sem rede")), configured)
        assertEquals(UploadOutcome.FAILED, uploader.runOnce())
        assertEquals(1, queue.diagnostics.size)
    }

    @Test fun `idle uploader opens ingest for commands without waiting for a sample`() {
        val client = FakeClient()
        val uploader = Uploader(queueWith(0), client, configured)
        assertEquals(UploadOutcome.IDLE, uploader.runOnce())
        assertEquals(1, client.connections)
    }

    @Test fun `removing server configuration closes the command socket`() {
        val client = FakeClient()
        val uploader = Uploader(queueWith(0), client, FixedConfig(ServerConfig("https://example.test", "")))
        assertEquals(UploadOutcome.NOT_CONFIGURED, uploader.runOnce())
        assertEquals(1, client.disconnects)
        assertEquals(0, client.connections)
    }
}
