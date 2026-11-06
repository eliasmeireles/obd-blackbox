package io.github.eliasmeireles.obdblackbox.data.upload

import kotlin.math.min
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive

data class UploadStatus(
    val configured: Boolean = false,
    val enabled: Boolean = true,
    val pending: Long = 0,
    val lastSuccessMs: Long? = null,
    val lastError: String? = null,
    val live: Boolean = false,
)

enum class UploadOutcome { SENT_MORE, IDLE, FAILED, NOT_CONFIGURED }

/**
 * Drains the [UploadQueue] to monitorapi, one session batch at a time, retrying with backoff. When the queue is
 * empty it waits for [UploadSignal], so a live sample is sent as soon as it is queued.
 */
class Uploader(
    private val queue: UploadQueue,
    private val client: MonitorApiClient,
    private val configStore: ServerConfigStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val batchSize: Int = DEFAULT_BATCH,
    private val signal: UploadSignal = UploadSignal(),
    private val enabled: Boolean = true,
) {
    private val _status = MutableStateFlow(UploadStatus(enabled = enabled))
    private var logsPausedUntil = 0L
    val status: StateFlow<UploadStatus> = _status.asStateFlow()

    suspend fun run() {
        if (!enabled) return
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val outcome = runOnce()
            failures = if (outcome == UploadOutcome.FAILED) failures + 1 else 0
            if (outcome == UploadOutcome.IDLE) signal.awaitQueued(IDLE_DELAY_MS) else delay(delayAfter(outcome, failures))
        }
    }

    fun runOnce(): UploadOutcome {
        if (!enabled) return UploadOutcome.NOT_CONFIGURED
        val config = configStore.load()
        if (!config.isComplete) {
            client.disconnect()
            return finish(UploadOutcome.NOT_CONFIGURED, configured = false)
        }
        client.maintainConnection(config)
        registerSessions(config)?.let { return fail(it) }
        sendDiagnostics(config)?.let { return it }
        val batch = queue.nextBatch(batchSize)
        if (batch.isEmpty()) return sendLogs(config)
        return when (val result = client.postSamples(config, batch.first().sessionId, batch.map { it.json })) {
            // Caught up with the samples: send the logs in the same cycle. Each new sample triggers a cycle, so
            // waiting for an empty sample queue would hold the logs back for as long as the car keeps streaming.
            ApiResult.Ok -> sent(batch).let { outcome -> if (outcome == UploadOutcome.IDLE) sendLogs(config) else outcome }
            is ApiResult.Failed -> rejected(batch, result)
        }
    }

    /**
     * Logs go once the samples are caught up, so they never delay a backlog of live data. A failed log upload only
     * pauses the logs for [LOG_RETRY_MS]; it never slows the samples down.
     */
    private fun sendLogs(config: ServerConfig): UploadOutcome {
        if (clock() < logsPausedUntil) return finish(UploadOutcome.IDLE)
        val logs = queue.nextLogs(LOG_BATCH)
        if (logs.isEmpty()) return finish(UploadOutcome.IDLE)
        return when (val result = client.postLogs(config, logs.map { it.json })) {
            ApiResult.Ok -> {
                queue.removeLogs(logs.map { it.id })
                finish(if (logs.size >= LOG_BATCH) UploadOutcome.SENT_MORE else UploadOutcome.IDLE)
            }
            is ApiResult.Failed -> {
                // 400/413 mean the batch itself is unacceptable; anything else (server without the logs API yet,
                // network) keeps the logs for later.
                if (result.code == 413 || (result.code == 400 && !result.message.contains("unknown", ignoreCase = true))) {
                    queue.removeLogs(logs.map { it.id })
                }
                logsPausedUntil = clock() + LOG_RETRY_MS
                finish(UploadOutcome.IDLE)
            }
        }
    }

    private fun sendDiagnostics(config: ServerConfig): UploadOutcome? {
        val item = queue.nextDiagnostics(1).firstOrNull() ?: return null
        return when (val result = client.postDiagnostics(config, item.sessionId, item.json)) {
            ApiResult.Ok -> {
                queue.removeDiagnostics(listOf(item.id))
                _status.update { it.copy(lastSuccessMs = clock(), lastError = null) }
                finish(UploadOutcome.SENT_MORE)
            }
            is ApiResult.Failed -> {
                when (result.code) {
                    404 -> queue.markUnregistered(item.sessionId)
                    400, 413 -> queue.removeDiagnostics(listOf(item.id))
                }
                fail(result)
            }
        }
    }

    private fun registerSessions(config: ServerConfig): ApiResult.Failed? {
        for (session in queue.unregisteredSessions()) {
            when (val result = client.putSession(config, session)) {
                ApiResult.Ok -> queue.markRegistered(session.id)
                is ApiResult.Failed -> return result
            }
        }
        return null
    }

    private fun sent(batch: List<QueuedSample>): UploadOutcome {
        queue.remove(batch.map { it.id })
        _status.update { it.copy(lastSuccessMs = clock(), lastError = null) }
        return finish(if (batch.size >= batchSize) UploadOutcome.SENT_MORE else UploadOutcome.IDLE)
    }

    /** 404: the server lost the session, register it again. 400/413: the batch can never succeed, drop it. */
    private fun rejected(batch: List<QueuedSample>, result: ApiResult.Failed): UploadOutcome {
        when (result.code) {
            404 -> queue.markUnregistered(batch.first().sessionId)
            400, 413 -> queue.remove(batch.map { it.id })
        }
        return fail(result)
    }

    private fun fail(result: ApiResult.Failed): UploadOutcome {
        _status.update { it.copy(lastError = result.message) }
        return finish(UploadOutcome.FAILED)
    }

    private fun finish(outcome: UploadOutcome, configured: Boolean = true): UploadOutcome {
        _status.update { it.copy(configured = configured, pending = queue.pendingCount(), live = configured && client.live) }
        return outcome
    }

    private fun delayAfter(outcome: UploadOutcome, failures: Int): Long = when (outcome) {
        UploadOutcome.SENT_MORE -> 0
        UploadOutcome.IDLE -> 0 // run() waits on the signal instead
        UploadOutcome.NOT_CONFIGURED -> NOT_CONFIGURED_DELAY_MS
        UploadOutcome.FAILED -> min(MAX_BACKOFF_MS, 1_000L shl min(failures, 6))
    }

    companion object {
        const val DEFAULT_BATCH = 500
        private const val LOG_BATCH = 200
        private const val LOG_RETRY_MS = 60_000L
        private const val IDLE_DELAY_MS = 2_000L
        private const val NOT_CONFIGURED_DELAY_MS = 10_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }
}
