package io.github.eliasmeireles.obdblackbox.data.upload

import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DiagnosticHttpFallbackTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private val server = MockWebServer()
    private val ws = WebSocketMonitorApiClient("test", openTimeoutMs = 1_000)
    @Before fun start() = server.start()
    @After fun stop() { ws.close(); server.close() }

    @Test fun `unavailable WebSocket falls back to posting the original snapshot`() {
        server.enqueue(MockResponse.Builder().code(404).build())
        server.enqueue(MockResponse.Builder().code(200).build())
        val client = FallbackMonitorApiClient(ws, HttpMonitorApiClient("test"))
        val json = "{\"t\":1,\"reason\":\"before_clear\"}"
        val config = ServerConfig(server.url("/").toString(), "example-key")
        assertEquals(ApiResult.Ok, client.postDiagnostics(config, "example-session", json))
        server.takeRequest(2, TimeUnit.SECONDS)
        val post = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("POST", post.method)
        assertEquals("/api/v1/sessions/example-session/diagnostics", post.url.encodedPath)
        assertEquals("before_clear", JSONObject(post.body!!.utf8()).getString("reason"))
        assertEquals("Bearer example-key", post.headers["Authorization"])
    }

    @Test fun `HTTP error codes are retained for uploader retry rules`() {
        server.enqueue(MockResponse.Builder().code(404).build())
        val result = HttpMonitorApiClient("test").postDiagnostics(ServerConfig(server.url("/").toString(), "example-key"), "example-session", "{}")
        assertEquals(ApiResult.Failed(404, "HTTP 404"), result)
    }
}
