package io.github.eliasmeireles.obdblackbox.data.bridge

import io.github.eliasmeireles.obdblackbox.i18n.text
import io.github.eliasmeireles.obdblackbox.R
import io.github.eliasmeireles.obdblackbox.data.logs.AppLog
import io.github.eliasmeireles.obdblackbox.data.logs.LogLevel
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** TMB has no request IDs: serialize operations and discard the link after a timeout to avoid stale replies. */
internal class DiagnosticCommands(
    private val timeoutMs: Long,
    private val dispatcher: CoroutineDispatcher,
    private val log: AppLog,
    private val connection: () -> BridgeConnection?,
) {
    private val lock = Mutex()
    @Volatile private var pending: Pair<String, CompletableDeferred<DiagnosticResult>>? = null

    suspend fun execute(name: String, success: String): DiagnosticResult {
        log.log(LogLevel.INFO, "diag", "request: $name")
        val result = try {
            withTimeoutOrNull(timeoutMs) { lock.withLock { exchange(name, success) } }
                ?: DiagnosticResult(false, text(R.string.msg_tempo_esgotado))
        } catch (e: CancellationException) {
            log.log(LogLevel.WARN, "diag", "$name: cancelled")
            throw e
        }
        log.log(if (result.ok) LogLevel.INFO else LogLevel.WARN, "diag", "$name: ${result.message}")
        return result
    }

    private suspend fun exchange(name: String, success: String): DiagnosticResult {
        val link = connection() ?: return DiagnosticResult(false, text(R.string.msg_o_app_nao_esta_conectado_ao_carro))
        val reply = CompletableDeferred<DiagnosticResult>()
        pending = name to reply
        try {
            withContext(dispatcher) { link.send(name) }
            return reply.await().let { if (it.ok) it.copy(message = success) else it }
        } catch (e: CancellationException) {
            // A late OK must never complete a later clear request on this same stream.
            runCatching { link.close() }
            throw e
        } catch (_: IOException) {
            return DiagnosticResult(false, text(R.string.msg_o_app_nao_esta_conectado_ao_carro))
        } finally {
            pending = null
        }
    }

    fun complete(name: String, result: DiagnosticResult) {
        pending?.takeIf { it.first == name }?.second?.complete(result)
    }

    fun disconnected() {
        pending?.second?.complete(DiagnosticResult(false, text(R.string.msg_o_app_nao_esta_conectado_ao_carro)))
    }
}
