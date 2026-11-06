package io.github.eliasmeireles.obdblackbox.data.upload

import io.github.eliasmeireles.obdblackbox.data.SourceKind
import io.github.eliasmeireles.obdblackbox.data.recording.SessionInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class FallbackMonitorApiClientTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private class Scripted(private val result: ApiResult, override val live: Boolean = false) : MonitorApiClient {
        var calls = 0
        override fun putSession(config: ServerConfig, session: SessionInfo): ApiResult = result.also { calls++ }
        override fun postSamples(config: ServerConfig, sessionId: String, samples: List<String>): ApiResult = result.also { calls++ }
        override fun postLogs(config: ServerConfig, entries: List<String>): ApiResult = result.also { calls++ }
    }

    private val config = ServerConfig("https://example.test", "key")
    private val session = SessionInfo("s1", SourceKind.OBD, 0L)

    @Test fun `uses the websocket while it answers`() {
        val primary = Scripted(ApiResult.Ok, live = true)
        val fallback = Scripted(ApiResult.Ok)
        val client = FallbackMonitorApiClient(primary, fallback)

        assertEquals(ApiResult.Ok, client.postSamples(config, "s1", listOf("{}")))
        assertEquals(0, fallback.calls)
        assertEquals(true, client.live)
    }

    @Test fun `repeats over http when the websocket cannot carry the request`() {
        val fallback = Scripted(ApiResult.Ok)
        val client = FallbackMonitorApiClient(Scripted(ApiResult.Failed(null, "sem conexão")), fallback)

        assertEquals(ApiResult.Ok, client.putSession(config, session))
        assertEquals(1, fallback.calls)
    }

    @Test fun `server answers are final and not repeated over http`() {
        val fallback = Scripted(ApiResult.Ok)
        val client = FallbackMonitorApiClient(Scripted(ApiResult.Failed(400, "invalid")), fallback)

        assertEquals(ApiResult.Failed(400, "invalid"), client.postSamples(config, "s1", listOf("{}")))
        assertEquals(0, fallback.calls)
    }
}
