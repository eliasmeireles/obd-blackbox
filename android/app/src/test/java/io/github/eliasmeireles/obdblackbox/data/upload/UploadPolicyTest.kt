package io.github.eliasmeireles.obdblackbox.data.upload

import io.github.eliasmeireles.obdblackbox.ResourceTest
import io.github.eliasmeireles.obdblackbox.data.SourceKind
import io.github.eliasmeireles.obdblackbox.data.logs.LogLevel
import io.github.eliasmeireles.obdblackbox.data.logs.RemoteLog
import io.github.eliasmeireles.obdblackbox.data.recording.SessionInfo
import io.github.eliasmeireles.obdblackbox.data.recording.TelemetryRow
import io.github.eliasmeireles.obdblackbox.domain.snapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class UploadPolicyTest : ResourceTest() {
    @Test fun `URL-less builds never create durable queues or start the uploader`() {
        val policy = UploadPolicy("  ")
        var created = false
        var started = false
        val queue = policy.queue { created = true; error("must not create storage") }
        policy.startUploader { started = true }
        val recorder = QueueRecorder(SessionInfo("example", SourceKind.OBD, 0), queue)
        repeat(10) {
            recorder.record(TelemetryRow(snapshot(), null, emptyList(), null))
            queue.enqueueDiagnostic("example", "{}")
            RemoteLog(queue).log(LogLevel.INFO, "example", "example log")
        }
        assertFalse(policy.enabled)
        assertFalse(created)
        assertFalse(started)
        assertEquals(0L, queue.pendingCount())
        assertTrue(queue.unregisteredSessions().isEmpty())
        assertTrue(queue.nextLogs(100).isEmpty())
        assertTrue(queue.nextDiagnostics(100).isEmpty())
    }

    @Test fun `disabled uploader cannot open a connection even if invoked accidentally`() = runTest {
        val queue = UploadPolicy("").queue { error("no queue") }
        val client = object : MonitorApiClient {
            override fun maintainConnection(config: ServerConfig) = error("must not connect")
            override fun putSession(config: ServerConfig, session: SessionInfo): ApiResult = error("must not upload")
            override fun postSamples(config: ServerConfig, sessionId: String, samples: List<String>): ApiResult = error("must not upload")
            override fun postLogs(config: ServerConfig, entries: List<String>): ApiResult = error("must not upload")
        }
        val config = object : ServerConfigStore {
            override fun load() = ServerConfig("https://example.test", "example-key")
            override fun save(config: ServerConfig) = Unit
        }
        val uploader = Uploader(queue, client, config, enabled = false)
        uploader.run()
        assertEquals(UploadOutcome.NOT_CONFIGURED, uploader.runOnce())
        assertFalse(uploader.status.value.enabled)
    }

    @Test fun `a URL enables the uploader start hook`() {
        var started = false
        UploadPolicy("https://example.test").startUploader { started = true }
        assertTrue(started)
    }
}
