package io.github.eliasmeireles.obdblackbox.data.upload

import io.github.eliasmeireles.obdblackbox.data.SourceKind
import io.github.eliasmeireles.obdblackbox.data.recording.SessionInfo
import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebSocketMonitorApiClientTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private val server = MockWebServer()
    private val session = SessionInfo("0b9e6f6e-1111-4111-8111-111111111111", SourceKind.SIMULATION, 1_000L)

    /** Server side of the socket: records every message and answers with [reply]. */
    private class FakeIngest(private val reply: (JSONObject) -> String?) : WebSocketListener() {
        val received = CopyOnWriteArrayList<JSONObject>()
        var headers: okhttp3.Headers? = null

        override fun onOpen(webSocket: WebSocket, response: Response) {
            headers = response.request.headers
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = JSONObject(text)
            received += message
            reply(message)?.let(webSocket::send)
        }

        /** Completes the close handshake the client starts, so the server can shut down. */
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }
    }

    private fun ack(message: JSONObject) = """{"type":"ack","seq":${message.getLong("seq")}}"""

    private fun config() = ServerConfig(server.url("/").toString(), "device-key")

    private fun client(clock: () -> Long = { 0L }) =
        WebSocketMonitorApiClient("1.0", clock = clock, replyTimeoutMs = 2_000, openTimeoutMs = 2_000)

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    @Test fun `session and samples travel as acknowledged messages over one connection`() {
        val ingest = FakeIngest(::ack)
        server.enqueue(MockResponse.Builder().webSocketUpgrade(ingest).build())
        val client = client()

        assertEquals(ApiResult.Ok, client.putSession(config(), session))
        assertEquals(ApiResult.Ok, client.postSamples(config(), session.id, listOf("""{"t":1}""", """{"t":2}""")))

        assertEquals(1, server.requestCount)
        assertTrue(client.live)
        val (sessionMessage, samplesMessage) = ingest.received
        assertEquals("session", sessionMessage.getString("type"))
        assertEquals(session.id, sessionMessage.getString("id"))
        assertEquals("sim", sessionMessage.getString("source"))
        assertEquals("samples", samplesMessage.getString("type"))
        assertEquals(session.id, samplesMessage.getString("session_id"))
        assertEquals(2, samplesMessage.getJSONArray("samples").length())
        assertEquals(sessionMessage.getLong("seq") + 1, samplesMessage.getLong("seq"))
        assertEquals("Bearer device-key", ingest.headers?.get("Authorization"))
    }

    @Test fun `error replies keep the http status so the uploader reacts as with http`() {
        server.enqueue(
            MockResponse.Builder().webSocketUpgrade(
                FakeIngest { """{"type":"error","seq":${it.getLong("seq")},"status":404,"error":"session not found"}""" },
            ).build(),
        )

        val result = client().postSamples(config(), session.id, listOf("""{"t":1}"""))

        assertEquals(ApiResult.Failed(404, "session not found"), result)
    }

    @Test fun `a refused handshake fails without code and is not retried for a while`() {
        server.enqueue(MockResponse.Builder().code(404).build())
        var now = 0L
        val client = client { now }

        val first = client.putSession(config(), session)
        val second = client.putSession(config(), session)

        assertEquals(null, (first as ApiResult.Failed).code)
        assertEquals(null, (second as ApiResult.Failed).code)
        assertEquals(1, server.requestCount)
        assertFalse(client.live)
    }

    @Test fun `a missing reply fails the request and drops the connection`() {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(FakeIngest { null }).build())
        val client = client()

        val result = client.putSession(config(), session)

        assertEquals(ApiResult.Failed(null, "sem resposta do servidor"), result)
        assertFalse(client.live)
    }

    @Test fun `diagnostic payload embeds the original snapshot as an object`() {
        val ingest = FakeIngest(::ack)
        server.enqueue(MockResponse.Builder().webSocketUpgrade(ingest).build())
        val client = client()
        try {
            assertEquals(ApiResult.Ok, client.postDiagnostics(config(), session.id, "{\"t\":1,\"reason\":\"manual\"}"))
            val message = ingest.received.single()
            assertEquals("diagnostics", message.getString("type"))
            assertEquals(session.id, message.getString("session_id"))
            assertEquals("manual", message.getJSONObject("diagnostics").getString("reason"))
            assertTrue(message.getLong("seq") > 0)
        } finally { client.close() }
    }
}
