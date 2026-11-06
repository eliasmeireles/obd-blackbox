package io.github.eliasmeireles.obdblackbox.data.upload

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.data.recording.SessionInfo
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import io.github.eliasmeireles.obdblackbox.data.bridge.DiagnosticResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends to the server over one long-lived WebSocket (`/api/v1/ingest`, see docs/backend-spec.md): every call becomes
 * one message and waits for the server's reply, so samples reach the dashboard as soon as they are read.
 *
 * Connection problems come back as failures without an HTTP code, which makes [FallbackMonitorApiClient] repeat the
 * request over plain HTTP. When the server answers the handshake without upgrading (old server, proxy), the
 * WebSocket is not tried again for [handshakeRetryMs].
 */
class WebSocketMonitorApiClient(
    private val appVersion: String,
    private val http: WebSocket.Factory = defaultHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val replyTimeoutMs: Long = REPLY_TIMEOUT_MS,
    private val openTimeoutMs: Long = OPEN_TIMEOUT_MS,
    private val handshakeRetryMs: Long = HANDSHAKE_RETRY_MS,
    private val commandHandler: suspend (String) -> DiagnosticResult = { DiagnosticResult(false, text(R.string.msg_comando_desconhecido)) },
    private val commandTimeoutMs: Long = 65_000,
) : MonitorApiClient {
    private val sequence = AtomicLong()

    @Volatile
    private var link: IngestLink? = null
    private var handshakeBlockedUntil = 0L
    private var blockedConfig: ServerConfig? = null

    override val live: Boolean get() = link?.usable == true

    override fun maintainConnection(config: ServerConfig) { open(config) }
    override fun disconnect() = close()

    override fun postDiagnostics(config: ServerConfig, sessionId: String, json: String): ApiResult =
        exchange(config) { seq -> UploadPayload.diagnosticsMessage(seq, sessionId, json) }

    override fun putSession(config: ServerConfig, session: SessionInfo): ApiResult =
        exchange(config) { seq -> UploadPayload.sessionMessage(seq, session, appVersion) }

    override fun postSamples(config: ServerConfig, sessionId: String, samples: List<String>): ApiResult =
        exchange(config) { seq -> UploadPayload.samplesMessage(seq, sessionId, samples) }

    override fun postLogs(config: ServerConfig, entries: List<String>): ApiResult =
        exchange(config) { seq -> UploadPayload.logsMessage(seq, entries) }

    @Synchronized
    fun close() {
        link?.close()
        link = null
    }

    private fun exchange(config: ServerConfig, message: (Long) -> String): ApiResult {
        val current = when (val opened = open(config)) {
            is Opened.Ready -> opened.link
            is Opened.Unavailable -> return ApiResult.Failed(null, opened.reason)
        }
        return exchangeOn(current, message)
    }

    private fun exchangeOn(current: IngestLink, message: (Long) -> String): ApiResult {
        val seq = sequence.incrementAndGet()
        val reply = current.expect(seq)
        try {
            if (!current.send(message(seq))) return current.fail(text(R.string.msg_conexao_fechada))
            return reply.get(replyTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            return current.fail(text(R.string.msg_sem_resposta_do_servidor))
        } finally {
            current.forget(seq)
        }
    }

    @Synchronized
    private fun open(config: ServerConfig): Opened {
        link?.let { existing ->
            if (existing.config == config && existing.usable) return Opened.Ready(existing)
            existing.close()
        }
        link = null
        if (blockedConfig == config && clock() < handshakeBlockedUntil) return Opened.Unavailable(text(R.string.msg_websocket_indisponivel_usando_http))
        val next = IngestLink(config) { current, id, name -> handleCommand(current, id, name) }
        next.connect(http)
        val error = next.awaitOpen(openTimeoutMs) ?: return Opened.Ready(next.also { link = it })
        next.close()
        blockedConfig = config
        handshakeBlockedUntil = clock() + if (next.refused) handshakeRetryMs else CONNECTION_RETRY_MS
        return Opened.Unavailable(error)
    }

    private fun handleCommand(current: IngestLink, id: String, name: String) {
        current.commands.launch {
            val result = withTimeoutOrNull(commandTimeoutMs) {
                if (name !in setOf("clear_dtcs", "read_diagnostics")) DiagnosticResult(false, text(R.string.msg_comando_desconhecido))
                else try { commandHandler(name) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { DiagnosticResult(false, text(R.string.msg_nao_foi_possivel_executar_o_comando)) }
            } ?: DiagnosticResult(false, text(R.string.msg_tempo_esgotado))
            withContext(Dispatchers.IO) { exchangeOn(current) { seq -> UploadPayload.commandResult(seq, id, result) } }
        }
    }

    private sealed interface Opened {
        data class Ready(val link: IngestLink) : Opened

        data class Unavailable(val reason: String) : Opened
    }

    companion object {
        const val PATH = "/api/v1/ingest"
        private const val REPLY_TIMEOUT_MS = 20_000L
        private const val OPEN_TIMEOUT_MS = 15_000L
        private const val CONNECTION_RETRY_MS = 5_000L
        private const val HANDSHAKE_RETRY_MS = 5 * 60_000L

        /** Pings keep NAT mappings alive and detect dead links; small messages are compressed too. */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .minWebSocketMessageToCompress(MIN_COMPRESSED_BYTES)
            .build()

        private const val MIN_COMPRESSED_BYTES = 64L
    }
}
