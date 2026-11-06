package io.github.eliasmeireles.obdblackbox.data.upload

import io.github.eliasmeireles.obdblackbox.data.bridge.DiagnosticResult
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real ingest listener and command dispatcher without opening OS sockets. */
class IngestCommandsTest : io.github.eliasmeireles.obdblackbox.ResourceTest() {
    private class Ingest : WebSocket.Factory {
        lateinit var listener: WebSocketListener
        lateinit var socket: WebSocket
        val received = LinkedBlockingQueue<JSONObject>()
        override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
            this.listener = listener
            socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun send(text: String): Boolean {
                    val message = JSONObject(text)
                    received.offer(message)
                    listener.onMessage(this, JSONObject().put("type", "ack").put("seq", message.getLong("seq")).toString())
                    return true
                }
                override fun send(bytes: ByteString) = false
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
            }
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Switching Protocols").build())
            return socket
        }
        fun command(name: String) {
            listener.onMessage(socket, """{"type":"command","id":"00000000-0000-4000-8000-000000000001","name":"$name"}""")
        }
        fun next(): JSONObject = received.poll(5, TimeUnit.SECONDS) ?: error("sem mensagem do cliente")
    }

    private val config = ServerConfig("https://example.test", "example-key")

    @Test fun `idle ingest routes commands through the handler and sends acknowledged results`() {
        val ingest = Ingest()
        val client = WebSocketMonitorApiClient("test", http = ingest, commandHandler = { name ->
            assertEquals("clear_dtcs", name)
            DiagnosticResult(true, "códigos apagados; a central confirmou")
        })
        try {
            client.maintainConnection(config)
            assertTrue(client.live)
            ingest.command("clear_dtcs")
            val result = ingest.next()
            assertEquals("command_result", result.getString("type"))
            assertEquals("00000000-0000-4000-8000-000000000001", result.getString("command_id"))
            assertEquals(1L, result.getLong("seq"))
            assertTrue(result.getBoolean("ok"))
            assertEquals("códigos apagados; a central confirmou", result.getString("message"))
        } finally { client.close() }
    }

    @Test fun `unknown command is rejected without calling the handler`() {
        val ingest = Ingest()
        val client = WebSocketMonitorApiClient("test", http = ingest, commandHandler = { error("não deve chamar") })
        try {
            client.maintainConnection(config)
            ingest.command("unknown")
            val result = ingest.next()
            assertFalse(result.getBoolean("ok"))
            assertEquals("comando desconhecido", result.getString("message"))
        } finally { client.close() }
    }

    @Test fun `handler timeout returns a negative result`() {
        val ingest = Ingest()
        val client = WebSocketMonitorApiClient("test", http = ingest, commandTimeoutMs = 30, commandHandler = {
            delay(Long.MAX_VALUE)
            DiagnosticResult(true, "não deve terminar")
        })
        try {
            client.maintainConnection(config)
            ingest.command("read_diagnostics")
            val result = ingest.next()
            assertFalse(result.getBoolean("ok"))
            assertEquals("tempo esgotado", result.getString("message"))
        } finally { client.close() }
    }

    @Test fun `a pending command never blocks sample replies or confuses their sequence`() {
        val ingest = Ingest()
        val release = CompletableDeferred<Unit>()
        val client = WebSocketMonitorApiClient("test", http = ingest, commandHandler = {
            release.await()
            DiagnosticResult(true, "diagnóstico lido")
        })
        try {
            client.maintainConnection(config)
            ingest.command("read_diagnostics")
            assertEquals(ApiResult.Ok, client.postSamples(config, "example-session", listOf("{\"t\":1}")))
            assertEquals("samples", ingest.next().getString("type"))
            release.complete(Unit)
            val result = ingest.next()
            assertEquals("command_result", result.getString("type"))
            assertEquals(2L, result.getLong("seq"))
        } finally { client.close() }
    }
}
