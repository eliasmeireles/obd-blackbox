package io.github.eliasmeireles.obdblackbox.data.upload

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** One WebSocket and the requests waiting for their replies, matched by `seq`. */
internal class IngestLink(val config: ServerConfig, val command: (IngestLink, String, String) -> Unit) : WebSocketListener() {
    val commands = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opened = CompletableFuture<String?>()
    private val pending = ConcurrentHashMap<Long, CompletableFuture<ApiResult>>()
    private lateinit var socket: WebSocket

    @Volatile
    private var closedReason: String? = null

    @Volatile
    private var open = false

    /** The server answered the handshake with a plain HTTP response instead of upgrading. */
    @Volatile
    var refused = false
        private set

    val usable: Boolean get() = open && closedReason == null

    fun connect(http: WebSocket.Factory) {
        val request = Request.Builder()
            .url(config.baseUrl.trimEnd('/') + WebSocketMonitorApiClient.PATH)
            .header("Authorization", "Bearer ${config.apiKey}")
            .build()
        socket = http.newWebSocket(request, this)
    }

    /** Null once open, otherwise why it could not open. */
    fun awaitOpen(timeoutMs: Long): String? = try {
        opened.get(timeoutMs, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        text(R.string.msg_tempo_esgotado_ao_conectar)
    }

    fun expect(seq: Long): CompletableFuture<ApiResult> = CompletableFuture<ApiResult>().also { future ->
        pending[seq] = future
        closedReason?.let { future.complete(ApiResult.Failed(null, it)) }
    }

    fun forget(seq: Long) {
        pending.remove(seq)
    }

    fun send(text: String): Boolean = socket.send(text)

    fun fail(reason: String): ApiResult.Failed {
        finish(reason)
        socket.cancel()
        return ApiResult.Failed(null, reason)
    }

    fun close() {
        finish(text(R.string.msg_conexao_encerrada))
        if (::socket.isInitialized) socket.close(NORMAL_CLOSURE, null)
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        open = true
        opened.complete(null)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val reply = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (reply.optString("type") == "command") {
            val id = reply.optString("id")
            if (id.isNotBlank()) command(this, id, reply.optString("name"))
            return
        }
        val result = when (reply.optString("type")) {
            "ack" -> ApiResult.Ok
            "error" -> ApiResult.Failed(reply.optInt("status").takeIf { it > 0 }, reply.optString("error", text(R.string.msg_erro_do_servidor)))
            else -> return
        }
        pending[reply.optLong("seq")]?.complete(result)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        finish(text(R.string.msg_servidor_encerrou_a_conexao_value, (code).toString()))
        webSocket.close(NORMAL_CLOSURE, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(text(R.string.msg_conexao_encerrada_value, (code).toString()))

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        if (response != null) refused = true
        finish(response?.let { text(R.string.msg_websocket_recusado_http_value, (it.code).toString()) } ?: (t.message ?: t.javaClass.simpleName))
    }

    private fun finish(reason: String) {
        if (closedReason == null) closedReason = reason
        commands.cancel()
        opened.complete(reason)
        pending.values.forEach { it.complete(ApiResult.Failed(null, reason)) }
    }
}

private const val NORMAL_CLOSURE = 1000
