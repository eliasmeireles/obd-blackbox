package io.github.eliasmeireles.obdblackbox.data.upload

import io.github.eliasmeireles.obdblackbox.data.bridge.DiagnosticResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RemoteCommandsTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private val server = MockWebServer()
    private val commandId = "00000000-0000-4000-8000-000000000001"
    private var client: WebSocketMonitorApiClient? = null

    @Before fun start() = server.start()
    @After fun stop() { client?.close(); server.close() }

    private fun execute(name: String, timeoutMs: Long = 2_000, handler: suspend (String) -> DiagnosticResult): JSONObject {
        val arrived = CountDownLatch(1)
        var received: JSONObject? = null
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "command").put("id", commandId).put("name", name).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = JSONObject(text)
                received = message
                webSocket.send(JSONObject().put("type", "ack").put("seq", message.getLong("seq")).toString())
                arrived.countDown()
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, null)
            }
        }).build())
        client = WebSocketMonitorApiClient("test", commandHandler = handler, commandTimeoutMs = timeoutMs)
        client!!.maintainConnection(ServerConfig(server.url("/").toString(), "example-key"))
        assertTrue("command response", arrived.await(5, TimeUnit.SECONDS))
        return received!!
    }

    @Test fun `an idle socket accepts commands and acknowledges the handler result`() {
        val result = execute("clear_dtcs") { name ->
            assertEquals("clear_dtcs", name)
            DiagnosticResult(true, "códigos apagados; a central confirmou")
        }
        assertEquals("command_result", result.getString("type"))
        assertEquals(commandId, result.getString("command_id"))
        assertTrue(result.getLong("seq") > 0)
        assertTrue(result.getBoolean("ok"))
        assertEquals("códigos apagados; a central confirmou", result.getString("message"))
        assertEquals(1, server.requestCount)
    }

    @Test fun `unknown commands fail without invoking the handler`() {
        val result = execute("unknown") { error("não deve executar") }
        assertFalse(result.getBoolean("ok"))
        assertEquals("comando desconhecido", result.getString("message"))
    }

    @Test fun `handler timeout produces a command result instead of blocking ingestion`() {
        val result = execute("read_diagnostics", timeoutMs = 30) {
            delay(Long.MAX_VALUE)
            error("não deve terminar")
        }
        assertFalse(result.getBoolean("ok"))
        assertEquals("tempo esgotado", result.getString("message"))
    }

    @Test fun `ECU refusal text is preserved in the result`() {
        val result = execute("clear_dtcs") {
            DiagnosticResult(false, "a central só apaga com a ignição ligada e o motor desligado")
        }
        assertFalse(result.getBoolean("ok"))
        assertEquals("a central só apaga com a ignição ligada e o motor desligado", result.getString("message"))
    }
}
